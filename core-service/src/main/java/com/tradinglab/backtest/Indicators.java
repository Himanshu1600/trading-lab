package com.tradinglab.backtest;

import java.util.Arrays;

import com.tradinglab.strategy.Operand.Indicator;

/**
 * Technical indicators over a whole price series at once. Each result has one value per bar, and
 * bars before the indicator has enough data are {@code NaN}, which every comparison treats as false.
 *
 * <p>RSI and ATR use Wilder's smoothing, and EMA starts from the simple average of its first
 * {@code period} values, as most charting tools do. {@code IndicatorsTests} checks each one
 * against hand-worked values.
 */
final class Indicators {

	private Indicators() {
	}

	static double[] compute(Indicator indicator, double[] high, double[] low, double[] close) {
		return switch (indicator.kind()) {
			case SMA -> sma(close, indicator.period());
			case EMA -> ema(close, indicator.period());
			case RSI -> rsi(close, indicator.period());
			case ATR -> atr(high, low, close, indicator.period());
			case MACD -> {
				double[][] lines = macd(close, indicator.fast(), indicator.slow(), indicator.signal());
				yield switch (indicator.output()) {
					case SIGNAL -> lines[1];
					case HISTOGRAM -> lines[2];
					default -> lines[0];
				};
			}
			case BOLLINGER -> {
				double[][] bands = bollinger(close, indicator.period(), indicator.stdDev());
				yield switch (indicator.output()) {
					case UPPER -> bands[0];
					case LOWER -> bands[2];
					default -> bands[1];
				};
			}
		};
	}

	static double[] sma(double[] values, int period) {
		double[] out = nans(values.length);
		double sum = 0;
		int valid = 0;
		for (int i = 0; i < values.length; i++) {
			if (Double.isNaN(values[i])) {
				// A gap in the input (such as the start of a MACD line) restarts the window.
				sum = 0;
				valid = 0;
				continue;
			}
			sum += values[i];
			valid++;
			if (valid > period) {
				sum -= values[i - period];
			}
			if (valid >= period) {
				out[i] = sum / period;
			}
		}
		return out;
	}

	static double[] ema(double[] values, int period) {
		double[] out = nans(values.length);
		double k = 2.0 / (period + 1);
		int start = firstValid(values);
		if (start < 0 || start + period > values.length) {
			return out;
		}
		double seed = 0;
		for (int i = start; i < start + period; i++) {
			seed += values[i];
		}
		double previous = seed / period;
		out[start + period - 1] = previous;
		for (int i = start + period; i < values.length; i++) {
			previous = values[i] * k + previous * (1 - k);
			out[i] = previous;
		}
		return out;
	}

	static double[] rsi(double[] close, int period) {
		double[] out = nans(close.length);
		if (close.length <= period) {
			return out;
		}
		double gain = 0;
		double loss = 0;
		for (int i = 1; i <= period; i++) {
			double change = close[i] - close[i - 1];
			gain += Math.max(change, 0);
			loss += Math.max(-change, 0);
		}
		gain /= period;
		loss /= period;
		out[period] = rsiValue(gain, loss);
		for (int i = period + 1; i < close.length; i++) {
			double change = close[i] - close[i - 1];
			gain = (gain * (period - 1) + Math.max(change, 0)) / period;
			loss = (loss * (period - 1) + Math.max(-change, 0)) / period;
			out[i] = rsiValue(gain, loss);
		}
		return out;
	}

	private static double rsiValue(double gain, double loss) {
		if (loss == 0) {
			return (gain == 0) ? 50 : 100;
		}
		return 100 - 100 / (1 + gain / loss);
	}

	static double[] atr(double[] high, double[] low, double[] close, int period) {
		double[] out = nans(close.length);
		if (close.length <= period) {
			return out;
		}
		double[] trueRange = new double[close.length];
		for (int i = 1; i < close.length; i++) {
			trueRange[i] = Math.max(high[i] - low[i],
					Math.max(Math.abs(high[i] - close[i - 1]), Math.abs(low[i] - close[i - 1])));
		}
		double average = 0;
		for (int i = 1; i <= period; i++) {
			average += trueRange[i];
		}
		average /= period;
		out[period] = average;
		for (int i = period + 1; i < close.length; i++) {
			average = (average * (period - 1) + trueRange[i]) / period;
			out[i] = average;
		}
		return out;
	}

	/** Returns the MACD line, its signal line and the histogram. */
	static double[][] macd(double[] close, int fast, int slow, int signal) {
		double[] fastEma = ema(close, fast);
		double[] slowEma = ema(close, slow);
		double[] line = nans(close.length);
		for (int i = 0; i < close.length; i++) {
			line[i] = fastEma[i] - slowEma[i];
		}
		double[] signalLine = ema(line, signal);
		double[] histogram = nans(close.length);
		for (int i = 0; i < close.length; i++) {
			histogram[i] = line[i] - signalLine[i];
		}
		return new double[][] { line, signalLine, histogram };
	}

	/** Returns the upper band, the middle (a simple average) and the lower band, using the population deviation. */
	static double[][] bollinger(double[] close, int period, double stdDevs) {
		double[] middle = sma(close, period);
		double[] upper = nans(close.length);
		double[] lower = nans(close.length);
		for (int i = period - 1; i < close.length; i++) {
			if (Double.isNaN(middle[i])) {
				continue;
			}
			double squares = 0;
			for (int j = i - period + 1; j <= i; j++) {
				squares += (close[j] - middle[i]) * (close[j] - middle[i]);
			}
			double deviation = Math.sqrt(squares / period);
			upper[i] = middle[i] + stdDevs * deviation;
			lower[i] = middle[i] - stdDevs * deviation;
		}
		return new double[][] { upper, middle, lower };
	}

	private static int firstValid(double[] values) {
		for (int i = 0; i < values.length; i++) {
			if (!Double.isNaN(values[i])) {
				return i;
			}
		}
		return -1;
	}

	private static double[] nans(int length) {
		double[] out = new double[length];
		Arrays.fill(out, Double.NaN);
		return out;
	}

}
