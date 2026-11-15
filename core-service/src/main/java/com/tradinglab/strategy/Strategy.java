package com.tradinglab.strategy;

import java.util.List;

/**
 * A parsed, validated strategy (DSL v1). Built only by {@link StrategyParser}, so every instance
 * obeys the rules in {@code contracts/strategy-dsl.v1.schema.json}.
 *
 * @param exit null when only the risk rules close positions
 */
public record Strategy(String name, List<String> symbols, String timeframe, Condition entry, Condition exit, Risk risk,
		Sizing sizing) {

	/** Percentages of the entry price; null when not used. */
	public record Risk(Double stopLossPct, Double takeProfitPct) {

		static final Risk NONE = new Risk(null, null);

	}

	/** How big each new position is, and how many can be open at once. */
	public record Sizing(Type type, double value, int maxPositions) {

		public enum Type {
			PERCENT_OF_EQUITY, FIXED_QTY, FIXED_CASH
		}

	}

}
