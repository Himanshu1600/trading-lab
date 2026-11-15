package com.tradinglab.backtest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.tradinglab.strategy.Condition;
import com.tradinglab.strategy.Operand;
import com.tradinglab.strategy.Strategy;

/**
 * Replays a strategy over daily bars. Pure and deterministic: the same strategy, bars and settings
 * always give the same result, and nothing here touches the database or the network.
 *
 * <p>Each trading day, for each symbol in the strategy's order:
 * <ol>
 * <li>Orders decided at the previous close fill at today's <b>open</b>, plus slippage. Deciding at
 * one bar's close and filling at the next bar's open is what prevents look-ahead bias: the strategy
 * never trades on a price it couldn't have known.</li>
 * <li>Stop-loss and take-profit are checked against today's low and high. If both are touched in
 * the same bar, the stop-loss is assumed to come first, the cautious choice, since daily bars don't
 * say which happened first. A gap past either level fills at the open.</li>
 * <li>At today's <b>close</b>, exit rules are checked for open positions and entry rules for the
 * rest. A match becomes an order for tomorrow's open.</li>
 * </ol>
 * Equity is cash plus every holding at its latest close. Positions still open at the end are
 * valued at the last close, as if sold there without fees.
 *
 * <p>Prices are {@code double}s here: a backtest is a simulation, and doubles make a five-year run
 * fast. Paper trading, which moves real account balances, uses {@code BigDecimal}.
 */
final class BacktestEngine {

	static final int TRADING_DAYS_PER_YEAR = 252;

	private static final double BASIS_POINTS = 10_000;

	record Bar(LocalDate date, double open, double high, double low, double close) {
	}

	record Settings(LocalDate from, LocalDate to, double initialCash, double feeBps, double slippageBps) {
	}

	enum ExitReason {
		SIGNAL, STOP_LOSS, TAKE_PROFIT, END_OF_TEST
	}

	record Trade(String symbol, long qty, LocalDate entryDate, double entryPrice, LocalDate exitDate, double exitPrice,
			double pnl, double returnPct, ExitReason exitReason) {
	}

	record EquityPoint(LocalDate date, double equity, double drawdown, double benchmark) {
	}

	/** Percentages are in percent (12.5 means 12.5%). Null where a figure doesn't apply. */
	record Metrics(double startEquity, double endEquity, double totalReturnPct, double cagrPct, double sharpe,
			double maxDrawdownPct, int tradingDays, Integer trades, Double winRatePct, Double profitFactor,
			Double exposurePct) {
	}

	record Result(List<Trade> trades, List<EquityPoint> equity, Metrics metrics, Metrics benchmark) {
	}

	private final Strategy strategy;
	private final Settings settings;
	private final List<Series> series = new ArrayList<>();
	private final double fee;
	private final double slippage;

	private double cash;
	private final Map<String, Position> open = new LinkedHashMap<>();
	private final Set<String> pendingEntries = new HashSet<>();
	private final Set<String> pendingExits = new HashSet<>();
	private final Map<String, Double> lastClose = new HashMap<>();
	private final List<Trade> trades = new ArrayList<>();

	private BacktestEngine(Strategy strategy, Map<String, List<Bar>> history, Settings settings) {
		this.strategy = strategy;
		this.settings = settings;
		this.fee = settings.feeBps() / BASIS_POINTS;
		this.slippage = settings.slippageBps() / BASIS_POINTS;
		this.cash = settings.initialCash();
		for (String symbol : strategy.symbols()) {
			series.add(new Series(symbol, history.getOrDefault(symbol, List.of())));
		}
	}

	/**
	 * @param history bars per symbol, oldest first. Bars before {@code settings.from()} are used
	 * only to warm up indicators, so a 50-day average is ready on the first simulated day.
	 */
	static Result run(Strategy strategy, Map<String, List<Bar>> history, Settings settings) {
		return new BacktestEngine(strategy, history, settings).simulate();
	}

	private Result simulate() {
		TreeSet<LocalDate> days = new TreeSet<>();
		for (Series s : series) {
			s.bars.stream()
				.map(Bar::date)
				.filter(day -> !day.isBefore(settings.from()) && !day.isAfter(settings.to()))
				.forEach(days::add);
		}
		if (days.isEmpty()) {
			throw new IllegalStateException("No price history for " + strategy.symbols() + " between "
					+ settings.from() + " and " + settings.to());
		}
		Benchmark benchmark = new Benchmark(settings.initialCash(), series.size());
		List<EquityPoint> equity = new ArrayList<>();
		double peak = settings.initialCash();
		int exposedDays = 0;
		for (LocalDate day : days) {
			for (Series s : series) {
				Integer i = s.index.get(day);
				if (i != null) {
					step(s, i);
					benchmark.see(s.symbol, s.bars.get(i));
				}
			}
			double value = markToMarket();
			peak = Math.max(peak, value);
			equity.add(new EquityPoint(day, value, (peak - value) / peak, benchmark.value()));
			if (!open.isEmpty()) {
				exposedDays++;
			}
		}
		LocalDate lastDay = days.last();
		for (Position position : List.copyOf(open.values())) {
			close(position, lastClose.get(position.symbol), lastDay, ExitReason.END_OF_TEST, false);
		}
		Metrics metrics = metrics(equity.stream().mapToDouble(EquityPoint::equity).toArray(), trades,
				(double) exposedDays / days.size());
		Metrics benchmarkMetrics = metrics(equity.stream().mapToDouble(EquityPoint::benchmark).toArray(), null, null);
		return new Result(List.copyOf(trades), List.copyOf(equity), metrics, benchmarkMetrics);
	}

	private void step(Series s, int i) {
		Bar bar = s.bars.get(i);
		Position position = open.get(s.symbol);

		// 1. Yesterday's decisions fill at today's open.
		boolean exitNow = pendingExits.remove(s.symbol);
		boolean enterNow = pendingEntries.remove(s.symbol);
		if (position != null && exitNow) {
			close(position, bar.open() * (1 - slippage), bar.date(), ExitReason.SIGNAL, true);
			position = null;
		}
		else if (position == null && enterNow && open.size() < strategy.sizing().maxPositions()) {
			position = enter(s.symbol, bar);
		}

		// 2. Stop-loss and take-profit inside the bar.
		if (position != null) {
			position = checkRisk(position, bar);
		}

		// 3. Decide at the close.
		if (position != null) {
			if (strategy.exit() != null && evaluate(strategy.exit(), s, i)) {
				pendingExits.add(s.symbol);
			}
		}
		else if (evaluate(strategy.entry(), s, i)) {
			pendingEntries.add(s.symbol);
		}
		lastClose.put(s.symbol, bar.close());
	}

	private Position enter(String symbol, Bar bar) {
		double price = bar.open() * (1 + slippage);
		double perShare = price * (1 + fee);
		Strategy.Sizing sizing = strategy.sizing();
		long qty = switch (sizing.type()) {
			case PERCENT_OF_EQUITY -> (long) Math.floor(markToMarket() * sizing.value() / 100 / perShare);
			case FIXED_CASH -> (long) Math.floor(sizing.value() / perShare);
			case FIXED_QTY -> (long) sizing.value();
		};
		qty = Math.min(qty, (long) Math.floor(cash / perShare));
		if (qty < 1) {
			return null;
		}
		double notional = qty * price;
		double entryFee = notional * fee;
		cash -= notional + entryFee;
		Position position = new Position(symbol, qty, price, bar.date(), entryFee);
		open.put(symbol, position);
		return position;
	}

	private Position checkRisk(Position position, Bar bar) {
		Strategy.Risk risk = strategy.risk();
		if (risk.stopLossPct() != null) {
			double stop = position.entryPrice * (1 - risk.stopLossPct() / 100);
			if (bar.low() <= stop) {
				double fill = Math.min(bar.open(), stop) * (1 - slippage);
				close(position, fill, bar.date(), ExitReason.STOP_LOSS, true);
				return null;
			}
		}
		if (risk.takeProfitPct() != null) {
			double target = position.entryPrice * (1 + risk.takeProfitPct() / 100);
			if (bar.high() >= target) {
				// A take-profit is a limit order: it fills at its price, or better on a gap up.
				close(position, Math.max(bar.open(), target), bar.date(), ExitReason.TAKE_PROFIT, true);
				return null;
			}
		}
		return position;
	}

	private void close(Position position, double price, LocalDate day, ExitReason reason, boolean chargeFee) {
		double notional = position.qty * price;
		double exitFee = chargeFee ? notional * fee : 0;
		if (reason != ExitReason.END_OF_TEST) {
			cash += notional - exitFee;
		}
		double pnl = (price - position.entryPrice) * position.qty - position.entryFee - exitFee;
		double invested = position.entryPrice * position.qty + position.entryFee;
		trades.add(new Trade(position.symbol, position.qty, position.entryDate, position.entryPrice, day, price, pnl,
				pnl / invested * 100, reason));
		open.remove(position.symbol);
	}

	private double markToMarket() {
		double value = cash;
		for (Position position : open.values()) {
			value += position.qty * lastClose.getOrDefault(position.symbol, position.entryPrice);
		}
		return value;
	}

	static boolean evaluate(Condition condition, Series s, int i) {
		return switch (condition) {
			case Condition.CrossesAbove(Operand left, Operand right) -> i > 0
					&& lessOrEqual(s.value(left, i - 1), s.value(right, i - 1))
					&& greater(s.value(left, i), s.value(right, i));
			case Condition.CrossesBelow(Operand left, Operand right) -> i > 0
					&& lessOrEqual(s.value(right, i - 1), s.value(left, i - 1))
					&& greater(s.value(right, i), s.value(left, i));
			case Condition.GreaterThan(Operand left, Operand right) -> greater(s.value(left, i), s.value(right, i));
			case Condition.LessThan(Operand left, Operand right) -> greater(s.value(right, i), s.value(left, i));
			case Condition.All(List<Condition> all) -> all.stream().allMatch(c -> evaluate(c, s, i));
			case Condition.Any(List<Condition> any) -> any.stream().anyMatch(c -> evaluate(c, s, i));
			case Condition.Not(Condition inner) -> !evaluate(inner, s, i);
		};
	}

	// Comparisons with a missing value (NaN) are false, so warm-up bars never trigger anything.
	private static boolean greater(double a, double b) {
		return a > b;
	}

	private static boolean lessOrEqual(double a, double b) {
		return a <= b;
	}

	static Metrics metrics(double[] equity, List<Trade> trades, Double exposure) {
		double start = equity[0];
		double end = equity[equity.length - 1];
		int days = equity.length;
		double cagr = (days < 2 || end <= 0) ? ((end <= 0) ? -1 : 0)
				: Math.pow(end / start, (double) TRADING_DAYS_PER_YEAR / days) - 1;
		double peak = equity[0];
		double maxDrawdown = 0;
		double sum = 0;
		double sumSquares = 0;
		for (int i = 0; i < days; i++) {
			peak = Math.max(peak, equity[i]);
			maxDrawdown = Math.max(maxDrawdown, (peak - equity[i]) / peak);
			if (i > 0) {
				double dailyReturn = equity[i] / equity[i - 1] - 1;
				sum += dailyReturn;
				sumSquares += dailyReturn * dailyReturn;
			}
		}
		int returns = days - 1;
		double sharpe = 0;
		if (returns >= 2) {
			double mean = sum / returns;
			double variance = (sumSquares - returns * mean * mean) / (returns - 1);
			if (variance > 1e-18) {
				sharpe = mean / Math.sqrt(variance) * Math.sqrt(TRADING_DAYS_PER_YEAR);
			}
		}
		Integer count = null;
		Double winRate = null;
		Double profitFactor = null;
		if (trades != null) {
			count = trades.size();
			double grossProfit = trades.stream().mapToDouble(Trade::pnl).filter(p -> p > 0).sum();
			double grossLoss = -trades.stream().mapToDouble(Trade::pnl).filter(p -> p < 0).sum();
			long wins = trades.stream().filter(t -> t.pnl() > 0).count();
			winRate = trades.isEmpty() ? null : round(100.0 * wins / trades.size());
			// With no losing trades the ratio is unbounded, so it's left out rather than shown as infinity.
			profitFactor = (grossLoss > 0) ? round(grossProfit / grossLoss) : null;
		}
		return new Metrics(round(start), round(end), round((end / start - 1) * 100), round(cagr * 100), round(sharpe),
				round(maxDrawdown * 100), days, count, winRate, profitFactor,
				(exposure != null) ? round(exposure * 100) : null);
	}

	private static double round(double value) {
		return Math.round(value * 10_000) / 10_000.0;
	}

	/** One symbol's bars and the indicator series computed from them, each computed once. */
	static final class Series {

		final String symbol;
		final List<Bar> bars;
		final Map<LocalDate, Integer> index = new HashMap<>();
		private final double[] open;
		private final double[] high;
		private final double[] low;
		private final double[] close;
		private final Map<Operand.Indicator, double[]> indicators = new HashMap<>();

		Series(String symbol, List<Bar> bars) {
			this.symbol = symbol;
			this.bars = bars;
			int n = bars.size();
			open = new double[n];
			high = new double[n];
			low = new double[n];
			close = new double[n];
			for (int i = 0; i < n; i++) {
				Bar bar = bars.get(i);
				open[i] = bar.open();
				high[i] = bar.high();
				low[i] = bar.low();
				close[i] = bar.close();
				index.put(bar.date(), i);
			}
		}

		double value(Operand operand, int i) {
			return switch (operand) {
				case Operand.Constant(double value) -> value;
				case Operand.Price(Operand.Field field) -> switch (field) {
					case OPEN -> open[i];
					case HIGH -> high[i];
					case LOW -> low[i];
					case CLOSE -> close[i];
				};
				case Operand.Indicator indicator -> indicators
					.computeIfAbsent(indicator, key -> Indicators.compute(key, high, low, close))[i];
			};
		}

	}

	private static final class Position {

		final String symbol;
		final long qty;
		final double entryPrice;
		final LocalDate entryDate;
		final double entryFee;

		Position(String symbol, long qty, double entryPrice, LocalDate entryDate, double entryFee) {
			this.symbol = symbol;
			this.qty = qty;
			this.entryPrice = entryPrice;
			this.entryDate = entryDate;
			this.entryFee = entryFee;
		}

	}

	/**
	 * Buy and hold, split equally between the symbols: each share of the starting cash buys its
	 * stock at that stock's first open in the period, with no fees. Fractional shares are allowed,
	 * so the comparison isn't distorted by rounding.
	 */
	private static final class Benchmark {

		private final double perSymbol;
		private double uninvested;
		private final Map<String, Double> shares = new HashMap<>();
		private final Map<String, Double> closes = new HashMap<>();

		Benchmark(double initialCash, int symbols) {
			this.perSymbol = initialCash / symbols;
			this.uninvested = initialCash;
		}

		void see(String symbol, Bar bar) {
			if (!shares.containsKey(symbol)) {
				shares.put(symbol, perSymbol / bar.open());
				uninvested -= perSymbol;
			}
			closes.put(symbol, bar.close());
		}

		double value() {
			double value = uninvested;
			for (Map.Entry<String, Double> held : shares.entrySet()) {
				value += held.getValue() * closes.get(held.getKey());
			}
			return value;
		}

	}

}
