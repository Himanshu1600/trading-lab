package com.tradinglab.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tradinglab.trading.TradeHistory.FillRecord;

class JournalCalculatorTests {

	private static final Instant DAY0 = Instant.parse("2026-01-05T15:00:00Z");

	@Test
	void anEmptyHistoryHasNoRatios() {
		Map<String, Object> stats = JournalCalculator.stats(List.of());
		assertThat(stats).containsEntry("fills", 0).containsEntry("closedTrades", 0).containsEntry("realizedPnl", 0.0);
		assertThat(stats.get("winRatePct")).isNull();
		assertThat(stats.get("bestSymbol")).isNull();
	}

	/**
	 * AAPL: buy 10 at 100 on day 0, buy 10 at 120 on day 2 (average 110), sell 10 at 130 on day 4
	 * (+200, held 4 days), sell 10 at 99 on day 6 (−110, held 6 days). MSFT: buy 5 at 200 on day 1,
	 * sell 5 at 180 on day 2 (−100, held 1 day).
	 */
	@Test
	void profitIsMeasuredAgainstTheAverageCost() {
		Map<String, Object> stats = JournalCalculator.stats(List.of(buy("AAPL", 10, "100", 0), buy("MSFT", 5, "200", 1),
				buy("AAPL", 10, "120", 2), sell("MSFT", 5, "180", 2), sell("AAPL", 10, "130", 4),
				sell("AAPL", 10, "99", 6)));

		assertThat(stats).containsEntry("fills", 6)
			.containsEntry("buys", 3)
			.containsEntry("sells", 3)
			.containsEntry("closedTrades", 3)
			.containsEntry("winningTrades", 1)
			.containsEntry("losingTrades", 2)
			.containsEntry("winRatePct", 33.33)
			.containsEntry("realizedPnl", -10.0)
			.containsEntry("averageWin", 200.0)
			.containsEntry("averageLoss", -105.0)
			.containsEntry("largestWin", 200.0)
			.containsEntry("largestLoss", -110.0)
			.containsEntry("averageDaysHeldWinners", 4.0)
			.containsEntry("averageDaysHeldLosers", 3.5)
			.containsEntry("symbolsTraded", 2)
			.containsEntry("mostTradedSymbol", "AAPL");
		assertThat(stats.get("bestSymbol")).isEqualTo(Map.of("symbol", "AAPL", "realizedPnl", 90.0));
		assertThat(stats.get("worstSymbol")).isEqualTo(Map.of("symbol", "MSFT", "realizedPnl", -100.0));
	}

	@Test
	void aNewPositionRestartsTheHoldingClock() {
		Map<String, Object> stats = JournalCalculator.stats(List.of(buy("AAPL", 1, "100", 0), sell("AAPL", 1, "110", 10),
				buy("AAPL", 1, "100", 20), sell("AAPL", 1, "110", 22)));
		// 10 days, then 2 days: not 22.
		assertThat(stats).containsEntry("averageDaysHeldWinners", 6.0).containsEntry("winRatePct", 100.0);
		assertThat(stats.get("worstSymbol")).isNull();
	}

	private static FillRecord buy(String symbol, long qty, String price, int day) {
		return new FillRecord(symbol, true, qty, new BigDecimal(price), DAY0.plusSeconds(day * 86_400L));
	}

	private static FillRecord sell(String symbol, long qty, String price, int day) {
		return new FillRecord(symbol, false, qty, new BigDecimal(price), DAY0.plusSeconds(day * 86_400L));
	}

}
