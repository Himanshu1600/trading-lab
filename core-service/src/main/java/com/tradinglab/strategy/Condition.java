package com.tradinglab.strategy;

import java.util.List;

/** A rule checked at the close of each bar. */
public sealed interface Condition {

	/** {@code left} was at or below {@code right} on the previous bar and is above it now. */
	record CrossesAbove(Operand left, Operand right) implements Condition {
	}

	/** {@code left} was at or above {@code right} on the previous bar and is below it now. */
	record CrossesBelow(Operand left, Operand right) implements Condition {
	}

	record GreaterThan(Operand left, Operand right) implements Condition {
	}

	record LessThan(Operand left, Operand right) implements Condition {
	}

	record All(List<Condition> conditions) implements Condition {
	}

	record Any(List<Condition> conditions) implements Condition {
	}

	record Not(Condition condition) implements Condition {
	}

}
