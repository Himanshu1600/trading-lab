package com.tradinglab.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.tradinglab.backtest.BacktestEngine.Bar;
import com.tradinglab.backtest.BacktestEngine.ExitReason;
import com.tradinglab.backtest.BacktestEngine.Result;
import com.tradinglab.backtest.BacktestEngine.Settings;
import com.tradinglab.backtest.BacktestEngine.Trade;
import com.tradinglab.strategy.Strategy;
import com.tradinglab.strategy.StrategyParser;

import tools.jackson.databind.json.JsonMapper;

/**
 * The golden scenario, worked out by hand. Seven days, buy when the close crosses above 100, sell
 * when it crosses below 95, ten shares, no fees or slippage:
 *
 * <pre>
 * day  open  high  low   close
 * 0     98    99    97    98
 * 1     99   101    98   101   close crosses above 100 → buy at tomorrow's open
 * 2    102   104   101   103   bought 10 at 102
 * 3    103   103    96    97
 * 4     96    97    93    94   close crosses below 95 → sell at tomorrow's open
 * 5     93    95    92    94   sold 10 at 93: P&L (93 − 102) × 10 = −90
 * 6     94    95    93    95
 * </pre>
 */
class BacktestEngineTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final LocalDate DAY0 = LocalDate.of(2025, 1, 6);

	private static final List<Bar> GOLDEN = List.of(bar(0, 98, 99, 97, 98), bar(1, 99, 101, 98, 101),
			bar(2, 102, 104, 101, 103), bar(3, 103, 103, 96, 97), bar(4, 96, 97, 93, 94), bar(5, 93, 95, 92, 94),
			bar(6, 94, 95, 93, 95));

	private static final String THRESHOLDS = """
			"entry": {"crossesAbove": [{"price": "close"}, 100]},
			"exit": {"crossesBelow": [{"price": "close"}, 95]},
			"sizing": {"type": "fixedQty", "value": 10}
			""";

	@Test
	void signalsAtTheCloseFillAtTheNextOpen() {
		Result result = run(strategy(THRESHOLDS), GOLDEN, 0, 0);

		assertThat(result.trades()).singleElement().satisfies(trade -> {
			assertThat(trade.entryDate()).isEqualTo(day(2));
			assertThat(trade.entryPrice()).isEqualTo(102);
			assertThat(trade.exitDate()).isEqualTo(day(5));
			assertThat(trade.exitPrice()).isEqualTo(93);
			assertThat(trade.qty()).isEqualTo(10);
			assertThat(trade.pnl()).isCloseTo(-90, within(1e-9));
			assertThat(trade.exitReason()).isEqualTo(ExitReason.SIGNAL);
		});
		// Cash 10,000 − 1,020 = 8,980, plus 10 shares at each close.
		assertThat(result.equity()).extracting(BacktestEngine.EquityPoint::equity)
			.containsExactly(10_000.0, 10_000.0, 10_010.0, 9_950.0, 9_920.0, 9_910.0, 9_910.0);
		BacktestEngine.Metrics metrics = result.metrics();
		assertThat(metrics.totalReturnPct()).isEqualTo(-0.9);
		assertThat(metrics.maxDrawdownPct()).isCloseTo(100.0 * 100 / 10_010, within(1e-4));
		assertThat(metrics.trades()).isEqualTo(1);
		assertThat(metrics.winRatePct()).isEqualTo(0.0);
		// No profit over a 90 loss.
		assertThat(metrics.profitFactor()).isEqualTo(0.0);
		assertThat(metrics.exposurePct()).isCloseTo(100.0 * 3 / 7, within(1e-4));
		// Buy and hold: 10,000 / 98 shares at the first open, worth 95 each at the end.
		assertThat(result.benchmark().endEquity()).isCloseTo(10_000.0 / 98 * 95, within(1e-3));
	}

	@Test
	void stopLossFillsAtTheStopPrice() {
		// Stop at 102 × 0.95 = 96.9. Day 3's low of 96 reaches it.
		Result result = run(strategy(THRESHOLDS + ", \"risk\": {\"stopLossPct\": 5}"), GOLDEN, 0, 0);
		Trade trade = result.trades().getFirst();
		assertThat(trade.exitReason()).isEqualTo(ExitReason.STOP_LOSS);
		assertThat(trade.exitDate()).isEqualTo(day(3));
		assertThat(trade.exitPrice()).isCloseTo(96.9, within(1e-9));
	}

	@Test
	void takeProfitCanFillOnTheEntryBar() {
		// Target 102 × 1.01 = 103.02. Day 2's high of 104 reaches it the day the position opens.
		Result result = run(strategy(THRESHOLDS + ", \"risk\": {\"takeProfitPct\": 1}"), GOLDEN, 0, 0);
		Trade trade = result.trades().getFirst();
		assertThat(trade.exitReason()).isEqualTo(ExitReason.TAKE_PROFIT);
		assertThat(trade.exitDate()).isEqualTo(day(2));
		assertThat(trade.exitPrice()).isCloseTo(103.02, within(1e-9));
		assertThat(trade.pnl()).isCloseTo(10.2, within(1e-9));
	}

	@Test
	void whenStopAndTargetAreBothTouchedTheStopIsAssumedFirst() {
		// Day 3 spans 96 to 103: both a 5% stop (96.9) and a 0.9% target (102.918) are inside it.
		List<Bar> bars = new ArrayList<>(GOLDEN);
		bars.set(2, bar(2, 102, 102.5, 101, 102));
		Result result = run(strategy(THRESHOLDS + ", \"risk\": {\"stopLossPct\": 5, \"takeProfitPct\": 0.9}"), bars,
				0, 0);
		assertThat(result.trades().getFirst().exitReason()).isEqualTo(ExitReason.STOP_LOSS);
	}

	@Test
	void aGapThroughTheStopFillsAtTheOpen() {
		List<Bar> bars = new ArrayList<>(GOLDEN);
		bars.set(3, bar(3, 90, 91, 89, 90));
		Result result = run(strategy(THRESHOLDS + ", \"risk\": {\"stopLossPct\": 5}"), bars, 0, 0);
		assertThat(result.trades().getFirst().exitPrice()).isEqualTo(90);
	}

	@Test
	void feesAndSlippageAreCharged() {
		// 10 bps fee: 1.02 on the 1,020 entry and 0.93 on the 930 exit.
		Result withFees = run(strategy(THRESHOLDS), GOLDEN, 10, 0);
		assertThat(withFees.trades().getFirst().pnl()).isCloseTo(-90 - 1.02 - 0.93, within(1e-9));

		// 100 bps slippage: buy at 102 × 1.01, sell at 93 × 0.99.
		Result withSlippage = run(strategy(THRESHOLDS), GOLDEN, 0, 100);
		assertThat(withSlippage.trades().getFirst().entryPrice()).isCloseTo(103.02, within(1e-9));
		assertThat(withSlippage.trades().getFirst().exitPrice()).isCloseTo(92.07, within(1e-9));
	}

	@Test
	void aPositionStillOpenAtTheEndIsValuedAtTheLastClose() {
		Result result = run(strategy("""
				"entry": {"crossesAbove": [{"price": "close"}, 100]},
				"risk": {"stopLossPct": 50},
				"sizing": {"type": "fixedQty", "value": 10}
				"""), GOLDEN, 0, 0);
		Trade trade = result.trades().getFirst();
		assertThat(trade.exitReason()).isEqualTo(ExitReason.END_OF_TEST);
		assertThat(trade.exitPrice()).isEqualTo(95);
		assertThat(result.metrics().endEquity()).isEqualTo(10_000 - 1_020 + 950);
	}

	@Test
	void sizingNeverSpendsMoreThanTheCash() {
		Result result = run(strategy("""
				"entry": {"crossesAbove": [{"price": "close"}, 100]},
				"risk": {"stopLossPct": 50},
				"sizing": {"type": "fixedQty", "value": 1000}
				"""), GOLDEN, 0, 0);
		// 10,000 / 102 = 98 whole shares.
		assertThat(result.trades().getFirst().qty()).isEqualTo(98);
	}

	@Test
	void sameInputsGiveTheSameResults() {
		List<Bar> bars = randomWalk(1_260, 42);
		Strategy strategy = smaCrossover();
		assertThat(run(strategy, bars, 5, 5)).isEqualTo(run(strategy, bars, 5, 5));
	}

	/** PLAN.md's target: five years of daily bars with an SMA crossover, well under a second. */
	@Test
	void fiveYearsOfDailyBarsRunWellUnderASecond() {
		List<Bar> bars = randomWalk(5 * BacktestEngine.TRADING_DAYS_PER_YEAR, 7);
		Strategy strategy = smaCrossover();
		run(strategy, bars, 5, 5);
		long started = System.nanoTime();
		Result result = run(strategy, bars, 5, 5);
		long millis = (System.nanoTime() - started) / 1_000_000;
		System.out.printf("Backtest of %d bars took %d ms and made %d trades%n", bars.size(), millis,
				result.trades().size());
		assertThat(result.trades()).isNotEmpty();
		assertThat(millis).isLessThan(1_000);
	}

	private static Strategy smaCrossover() {
		return strategy("""
				"entry": {"crossesAbove": [{"indicator": "SMA", "period": 20}, {"indicator": "SMA", "period": 50}]},
				"exit": {"crossesBelow": [{"indicator": "SMA", "period": 20}, {"indicator": "SMA", "period": 50}]}
				""");
	}

	private static Result run(Strategy strategy, List<Bar> bars, double feeBps, double slippageBps) {
		return BacktestEngine.run(strategy, Map.of("TEST", bars),
				new Settings(bars.getFirst().date(), bars.getLast().date(), 10_000, feeBps, slippageBps));
	}

	private static Strategy strategy(String rules) {
		return StrategyParser.parse(JSON.readValue(
				"{\"dslVersion\": 1, \"name\": \"test\", \"symbols\": [\"TEST\"], " + rules + "}", Map.class));
	}

	private static List<Bar> randomWalk(int days, long seed) {
		Random random = new Random(seed);
		List<Bar> bars = new ArrayList<>();
		double close = 100;
		for (int i = 0; i < days; i++) {
			double open = close * (1 + random.nextGaussian() * 0.005);
			close = open * (1 + random.nextGaussian() * 0.015);
			double high = Math.max(open, close) * (1 + Math.abs(random.nextGaussian()) * 0.005);
			double low = Math.min(open, close) * (1 - Math.abs(random.nextGaussian()) * 0.005);
			bars.add(new Bar(DAY0.plusDays(i), open, high, low, close));
		}
		return bars;
	}

	private static Bar bar(int day, double open, double high, double low, double close) {
		return new Bar(day(day), open, high, low, close);
	}

	private static LocalDate day(int day) {
		return DAY0.plusDays(day);
	}

}
