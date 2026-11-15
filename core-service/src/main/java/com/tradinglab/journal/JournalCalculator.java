package com.tradinglab.journal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tradinglab.trading.TradeHistory.FillRecord;

/**
 * Turns a list of fills into trading statistics. Pure: no database, no clock.
 *
 * <p>Each sell is one "closed trade". Its profit is measured against the position's average cost
 * at that moment, the same rule the portfolio uses, and its holding time runs from when the
 * position was opened (the first buy after holding nothing) to the sell.
 */
final class JournalCalculator {

	private JournalCalculator() {
	}

	/** Figures are plain numbers so they can be stored as JSON and handed to the AI coach as they are. */
	static Map<String, Object> stats(List<FillRecord> fills) {
		Map<String, Holding> holdings = new HashMap<>();
		Map<String, BigDecimal> pnlBySymbol = new HashMap<>();
		Map<String, Integer> fillsBySymbol = new HashMap<>();
		List<Closed> closed = new ArrayList<>();
		int buys = 0;
		for (FillRecord fill : fills) {
			fillsBySymbol.merge(fill.symbol(), 1, Integer::sum);
			Holding holding = holdings.computeIfAbsent(fill.symbol(), symbol -> new Holding());
			BigDecimal qty = BigDecimal.valueOf(fill.qty());
			if (fill.buy()) {
				buys++;
				if (holding.qty == 0) {
					holding.openedAt = fill.filledAt();
				}
				BigDecimal cost = holding.avgCost.multiply(BigDecimal.valueOf(holding.qty)).add(fill.price().multiply(qty));
				holding.qty += fill.qty();
				holding.avgCost = cost.divide(BigDecimal.valueOf(holding.qty), MathContext.DECIMAL64);
			}
			else if (holding.qty > 0) {
				BigDecimal pnl = fill.price().subtract(holding.avgCost).multiply(qty);
				double returnPct = fill.price()
					.divide(holding.avgCost, MathContext.DECIMAL64)
					.subtract(BigDecimal.ONE)
					.doubleValue() * 100;
				double days = Duration.between(holding.openedAt, fill.filledAt()).toMinutes() / (60.0 * 24);
				closed.add(new Closed(fill.symbol(), pnl, returnPct, days));
				pnlBySymbol.merge(fill.symbol(), pnl, BigDecimal::add);
				holding.qty -= Math.min(fill.qty(), holding.qty);
			}
		}
		List<Closed> wins = closed.stream().filter(trade -> trade.pnl.signum() > 0).toList();
		List<Closed> losses = closed.stream().filter(trade -> trade.pnl.signum() < 0).toList();

		Map<String, Object> stats = new LinkedHashMap<>();
		stats.put("fills", fills.size());
		stats.put("buys", buys);
		stats.put("sells", fills.size() - buys);
		stats.put("closedTrades", closed.size());
		stats.put("winningTrades", wins.size());
		stats.put("losingTrades", losses.size());
		stats.put("winRatePct", closed.isEmpty() ? null : round(100.0 * wins.size() / closed.size()));
		stats.put("realizedPnl", money(sum(closed)));
		stats.put("averageWin", wins.isEmpty() ? null : money(average(wins)));
		stats.put("averageLoss", losses.isEmpty() ? null : money(average(losses)));
		stats.put("largestWin", wins.isEmpty() ? null : money(wins.stream().map(Closed::pnl).max(Comparator.naturalOrder()).orElseThrow()));
		stats.put("largestLoss", losses.isEmpty() ? null : money(losses.stream().map(Closed::pnl).min(Comparator.naturalOrder()).orElseThrow()));
		stats.put("averageReturnPct", closed.isEmpty() ? null : round(closed.stream().mapToDouble(Closed::returnPct).average().orElse(0)));
		stats.put("averageDaysHeldWinners", wins.isEmpty() ? null : round(wins.stream().mapToDouble(Closed::days).average().orElse(0)));
		stats.put("averageDaysHeldLosers", losses.isEmpty() ? null : round(losses.stream().mapToDouble(Closed::days).average().orElse(0)));
		stats.put("symbolsTraded", fillsBySymbol.size());
		stats.put("mostTradedSymbol", fillsBySymbol.entrySet()
			.stream()
			.max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
			.map(Map.Entry::getKey)
			.orElse(null));
		stats.put("bestSymbol", symbol(pnlBySymbol, true));
		stats.put("worstSymbol", symbol(pnlBySymbol, false));
		stats.put("firstTradeAt", fills.isEmpty() ? null : fills.getFirst().filledAt().toString());
		stats.put("lastTradeAt", fills.isEmpty() ? null : fills.getLast().filledAt().toString());
		return stats;
	}

	/** The symbol with the highest (or lowest) total profit, but only if that total is a gain (or a loss). */
	private static Map<String, Object> symbol(Map<String, BigDecimal> pnlBySymbol, boolean best) {
		Comparator<Map.Entry<String, BigDecimal>> byPnl = Map.Entry.comparingByValue();
		return pnlBySymbol.entrySet()
			.stream()
			.filter(entry -> best ? entry.getValue().signum() > 0 : entry.getValue().signum() < 0)
			.max(best ? byPnl : byPnl.reversed())
			.map(entry -> {
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("symbol", entry.getKey());
				result.put("realizedPnl", money(entry.getValue()));
				return result;
			})
			.orElse(null);
	}

	private static BigDecimal sum(List<Closed> trades) {
		return trades.stream().map(Closed::pnl).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private static BigDecimal average(List<Closed> trades) {
		return sum(trades).divide(BigDecimal.valueOf(trades.size()), MathContext.DECIMAL64);
	}

	private static double money(BigDecimal value) {
		return value.setScale(2, RoundingMode.HALF_UP).doubleValue();
	}

	private static double round(double value) {
		return Math.round(value * 100) / 100.0;
	}

	private record Closed(String symbol, BigDecimal pnl, double returnPct, double days) {
	}

	private static final class Holding {

		long qty;
		BigDecimal avgCost = BigDecimal.ZERO;
		Instant openedAt;

	}

}
