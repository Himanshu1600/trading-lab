package com.tradinglab.strategy;

/** Something a condition compares: a bar's price, an indicator, or a constant. */
public sealed interface Operand {

	enum Field {
		OPEN, HIGH, LOW, CLOSE
	}

	record Price(Field field) implements Operand {
	}

	record Constant(double value) implements Operand {
	}

	/**
	 * An indicator with all its parameters. Two equal records mean the same series, so the engine
	 * computes each distinct indicator once. Unused parameters are zero.
	 */
	record Indicator(Kind kind, int period, int fast, int slow, int signal, double stdDev, Output output)
			implements Operand {

		public enum Kind {
			SMA, EMA, RSI, ATR, MACD, BOLLINGER
		}

		/** Which line of a multi-line indicator. {@code VALUE} for single-line ones. */
		public enum Output {
			VALUE, MACD, SIGNAL, HISTOGRAM, UPPER, MIDDLE, LOWER
		}

		/** Bars needed before the first value exists. */
		public int warmUp() {
			return switch (kind) {
				case SMA, EMA, BOLLINGER -> period;
				case RSI, ATR -> period + 1;
				case MACD -> slow + signal;
			};
		}

	}

}
