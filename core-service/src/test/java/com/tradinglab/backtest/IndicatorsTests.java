package com.tradinglab.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** Every expected value here was worked out by hand; the arithmetic is in the comments. */
class IndicatorsTests {

	private static final double EPSILON = 1e-9;

	@Test
	void simpleMovingAverage() {
		double[] sma = Indicators.sma(new double[] { 1, 2, 3, 4, 5 }, 3);
		assertThat(sma[0]).isNaN();
		assertThat(sma[1]).isNaN();
		assertThat(sma[2]).isCloseTo(2, within(EPSILON));
		assertThat(sma[3]).isCloseTo(3, within(EPSILON));
		assertThat(sma[4]).isCloseTo(4, within(EPSILON));
	}

	@Test
	void exponentialMovingAverageStartsFromTheSimpleAverage() {
		// k = 2 / (3 + 1) = 0.5. Seed = (10 + 11 + 12) / 3 = 11.
		// Then 10 * 0.5 + 11 * 0.5 = 10.5, and 14 * 0.5 + 10.5 * 0.5 = 12.25.
		double[] ema = Indicators.ema(new double[] { 10, 11, 12, 10, 14 }, 3);
		assertThat(ema[1]).isNaN();
		assertThat(ema[2]).isCloseTo(11, within(EPSILON));
		assertThat(ema[3]).isCloseTo(10.5, within(EPSILON));
		assertThat(ema[4]).isCloseTo(12.25, within(EPSILON));
	}

	@Test
	void relativeStrengthIndexUsesWilderSmoothing() {
		// Changes: +1, +1, -1, +2. First averages over 2 changes: gain 1, loss 0, so RSI 100.
		// Next: gain (1 + 0) / 2 = 0.5, loss (0 + 1) / 2 = 0.5, RS 1, RSI 50.
		// Next: gain (0.5 + 2) / 2 = 1.25, loss (0.5 + 0) / 2 = 0.25, RS 5, RSI 100 - 100 / 6.
		double[] rsi = Indicators.rsi(new double[] { 1, 2, 3, 2, 4 }, 2);
		assertThat(rsi[1]).isNaN();
		assertThat(rsi[2]).isCloseTo(100, within(EPSILON));
		assertThat(rsi[3]).isCloseTo(50, within(EPSILON));
		assertThat(rsi[4]).isCloseTo(100 - 100 / 6.0, within(EPSILON));
	}

	@Test
	void averageTrueRangeIncludesGapsFromThePreviousClose() {
		// True ranges from bar 1: max(2, |11 - 9|, |9 - 9|) = 2; max(3, |12 - 10|, |9 - 10|) = 3;
		// max(1, |11 - 11|, |10 - 11|) = 1. ATR(2): (2 + 3) / 2 = 2.5, then (2.5 + 1) / 2 = 1.75.
		double[] high = { 10, 11, 12, 11 };
		double[] low = { 8, 9, 9, 10 };
		double[] close = { 9, 10, 11, 10.5 };
		double[] atr = Indicators.atr(high, low, close, 2);
		assertThat(atr[1]).isNaN();
		assertThat(atr[2]).isCloseTo(2.5, within(EPSILON));
		assertThat(atr[3]).isCloseTo(1.75, within(EPSILON));
	}

	@Test
	void macdLineSignalAndHistogram() {
		// EMA(2) of 1..6: 1.5, 2.5, 3.5, 4.5, 5.5 from bar 1. EMA(3): 2, 3, 4, 5 from bar 2.
		// MACD line = 0.5 from bar 2. Signal = EMA(2) of the line, ready from bar 3, also 0.5.
		double[][] macd = Indicators.macd(new double[] { 1, 2, 3, 4, 5, 6 }, 2, 3, 2);
		assertThat(macd[0][1]).isNaN();
		assertThat(macd[0][2]).isCloseTo(0.5, within(EPSILON));
		assertThat(macd[1][2]).isNaN();
		assertThat(macd[1][3]).isCloseTo(0.5, within(EPSILON));
		assertThat(macd[2][5]).isCloseTo(0, within(EPSILON));
	}

	@Test
	void bollingerBandsUseThePopulationDeviation() {
		// Middle of 1, 2, 3 is 2. Deviation sqrt((1 + 0 + 1) / 3) = sqrt(2 / 3).
		double[][] bands = Indicators.bollinger(new double[] { 1, 2, 3 }, 3, 2);
		double deviation = Math.sqrt(2.0 / 3);
		assertThat(bands[0][2]).isCloseTo(2 + 2 * deviation, within(EPSILON));
		assertThat(bands[1][2]).isCloseTo(2, within(EPSILON));
		assertThat(bands[2][2]).isCloseTo(2 - 2 * deviation, within(EPSILON));
		assertThat(bands[1][1]).isNaN();
	}

	@Test
	void tooLittleDataGivesNoValues() {
		assertThat(Indicators.ema(new double[] { 1, 2 }, 3)).containsOnly(Double.NaN);
		assertThat(Indicators.rsi(new double[] { 1, 2 }, 2)).containsOnly(Double.NaN);
	}

}
