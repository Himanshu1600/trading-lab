package com.tradinglab.strategy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.tradinglab.strategy.Operand.Indicator;
import com.tradinglab.strategy.Operand.Indicator.Kind;
import com.tradinglab.strategy.Operand.Indicator.Output;

/**
 * Turns strategy JSON, already read into maps and lists, into a {@link Strategy}. Strict on
 * purpose: unknown fields are errors, so a typo (or an LLM's invention) fails loudly instead of
 * being ignored. The first problem found is thrown, with the path to the field.
 *
 * <p>The same rules are written as a JSON Schema in {@code contracts/strategy-dsl.v1.schema.json}
 * for the ai-service. {@code StrategyParserTests} checks the schema's examples parse here.
 */
public final class StrategyParser {

	public static final int DSL_VERSION = 1;

	static final int MAX_SYMBOLS = 10;
	static final int MAX_PERIOD = 500;
	static final int MAX_DEPTH = 8;
	static final int MAX_CONDITIONS = 50;
	static final int MAX_CHILDREN = 10;

	private static final Pattern SYMBOL = Pattern.compile("[A-Z][A-Z.]{0,9}");
	private static final Set<String> TOP_LEVEL = Set.of("dslVersion", "name", "symbols", "timeframe", "entry",
			"exit", "risk", "sizing");

	private int conditionCount;

	private StrategyParser() {
	}

	public static Strategy parse(Object json) {
		return new StrategyParser().strategy(json);
	}

	private Strategy strategy(Object json) {
		Map<String, Object> root = object(json, "");
		onlyKeys(root, TOP_LEVEL, "");
		Object version = root.get("dslVersion");
		if (!(version instanceof Number number) || number.doubleValue() != DSL_VERSION) {
			throw new InvalidStrategyException("dslVersion", "must be " + DSL_VERSION);
		}
		String name = string(root.get("name"), "name").trim();
		if (name.isEmpty() || name.length() > 100) {
			throw new InvalidStrategyException("name", "must be 1 to 100 characters");
		}
		List<String> symbols = symbols(root.get("symbols"));
		Object timeframe = root.getOrDefault("timeframe", "1D");
		if (!"1D".equals(timeframe)) {
			throw new InvalidStrategyException("timeframe", "only \"1D\" (daily bars) is supported");
		}
		Condition entry = condition(required(root, "entry", ""), "entry", 1);
		Condition exit = root.containsKey("exit") ? condition(root.get("exit"), "exit", 1) : null;
		Strategy.Risk risk = root.containsKey("risk") ? risk(root.get("risk")) : Strategy.Risk.NONE;
		if (exit == null && risk.stopLossPct() == null && risk.takeProfitPct() == null) {
			throw new InvalidStrategyException("exit",
					"a strategy needs an exit rule, a stop-loss or a take-profit, or it would never sell");
		}
		Strategy.Sizing sizing = root.containsKey("sizing") ? sizing(root.get("sizing"), symbols.size())
				: new Strategy.Sizing(Strategy.Sizing.Type.PERCENT_OF_EQUITY,
						Math.floor(10_000.0 / symbols.size()) / 100, symbols.size());
		return new Strategy(name, symbols, "1D", entry, exit, risk, sizing);
	}

	private static List<String> symbols(Object json) {
		if (!(json instanceof List<?> list) || list.isEmpty() || list.size() > MAX_SYMBOLS) {
			throw new InvalidStrategyException("symbols", "must be a list of 1 to " + MAX_SYMBOLS + " symbols");
		}
		Set<String> symbols = new LinkedHashSet<>();
		for (int i = 0; i < list.size(); i++) {
			String symbol = string(list.get(i), "symbols[" + i + "]").trim().toUpperCase(Locale.ROOT);
			if (!SYMBOL.matcher(symbol).matches()) {
				throw new InvalidStrategyException("symbols[" + i + "]", "\"" + symbol + "\" isn't a stock symbol");
			}
			if (!symbols.add(symbol)) {
				throw new InvalidStrategyException("symbols[" + i + "]", symbol + " is listed twice");
			}
		}
		return List.copyOf(symbols);
	}

	private Condition condition(Object json, String path, int depth) {
		if (depth > MAX_DEPTH) {
			throw new InvalidStrategyException(path, "conditions can be nested at most " + MAX_DEPTH + " deep");
		}
		if (++conditionCount > MAX_CONDITIONS) {
			throw new InvalidStrategyException(path, "a strategy can have at most " + MAX_CONDITIONS + " conditions");
		}
		Map<String, Object> node = object(json, path);
		if (node.size() != 1) {
			throw new InvalidStrategyException(path,
					"a condition has exactly one of crossesAbove, crossesBelow, gt, lt, all, any or not");
		}
		Map.Entry<String, Object> only = node.entrySet().iterator().next();
		String key = only.getKey();
		String child = path + "." + key;
		return switch (key) {
			case "crossesAbove" -> {
				Operand[] pair = pair(only.getValue(), child);
				yield new Condition.CrossesAbove(pair[0], pair[1]);
			}
			case "crossesBelow" -> {
				Operand[] pair = pair(only.getValue(), child);
				yield new Condition.CrossesBelow(pair[0], pair[1]);
			}
			case "gt" -> {
				Operand[] pair = pair(only.getValue(), child);
				yield new Condition.GreaterThan(pair[0], pair[1]);
			}
			case "lt" -> {
				Operand[] pair = pair(only.getValue(), child);
				yield new Condition.LessThan(pair[0], pair[1]);
			}
			case "all" -> new Condition.All(children(only.getValue(), child, depth));
			case "any" -> new Condition.Any(children(only.getValue(), child, depth));
			case "not" -> new Condition.Not(condition(only.getValue(), child, depth + 1));
			default -> throw new InvalidStrategyException(path, "unknown condition \"" + key
					+ "\". Use crossesAbove, crossesBelow, gt, lt, all, any or not");
		};
	}

	private List<Condition> children(Object json, String path, int depth) {
		if (!(json instanceof List<?> list) || list.isEmpty() || list.size() > MAX_CHILDREN) {
			throw new InvalidStrategyException(path, "must be a list of 1 to " + MAX_CHILDREN + " conditions");
		}
		List<Condition> conditions = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			conditions.add(condition(list.get(i), path + "[" + i + "]", depth + 1));
		}
		return List.copyOf(conditions);
	}

	private static Operand[] pair(Object json, String path) {
		if (!(json instanceof List<?> list) || list.size() != 2) {
			throw new InvalidStrategyException(path, "must be a list of exactly two operands");
		}
		Operand left = operand(list.get(0), path + "[0]");
		Operand right = operand(list.get(1), path + "[1]");
		if (left instanceof Operand.Constant && right instanceof Operand.Constant) {
			throw new InvalidStrategyException(path, "comparing two constants is always true or always false");
		}
		return new Operand[] { left, right };
	}

	private static Operand operand(Object json, String path) {
		if (json instanceof Number number) {
			return constant(number.doubleValue(), path);
		}
		Map<String, Object> node = object(json, path);
		if (node.containsKey("price")) {
			onlyKeys(node, Set.of("price"), path);
			String field = string(node.get("price"), path + ".price").toUpperCase(Locale.ROOT);
			try {
				return new Operand.Price(Operand.Field.valueOf(field));
			}
			catch (IllegalArgumentException ex) {
				throw new InvalidStrategyException(path + ".price", "must be open, high, low or close");
			}
		}
		if (node.containsKey("value")) {
			onlyKeys(node, Set.of("value"), path);
			return constant(number(node.get("value"), path + ".value"), path + ".value");
		}
		if (node.containsKey("indicator")) {
			return indicator(node, path);
		}
		throw new InvalidStrategyException(path, "an operand is {\"price\": ...}, {\"indicator\": ...} or a number");
	}

	private static Operand.Constant constant(double value, String path) {
		if (!Double.isFinite(value)) {
			throw new InvalidStrategyException(path, "must be a finite number");
		}
		return new Operand.Constant(value);
	}

	private static Indicator indicator(Map<String, Object> node, String path) {
		String name = string(node.get("indicator"), path + ".indicator").toUpperCase(Locale.ROOT);
		Kind kind;
		try {
			kind = Kind.valueOf(name);
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidStrategyException(path + ".indicator",
					"must be one of SMA, EMA, RSI, ATR, MACD or BOLLINGER");
		}
		return switch (kind) {
			case SMA, EMA, RSI, ATR -> {
				onlyKeys(node, Set.of("indicator", "period", "output"), path);
				output(node, path, Output.VALUE, Set.of(Output.VALUE));
				yield new Indicator(kind, period(required(node, "period", path), path + ".period"), 0, 0, 0, 0,
						Output.VALUE);
			}
			case MACD -> {
				onlyKeys(node, Set.of("indicator", "fast", "slow", "signal", "output"), path);
				int fast = period(node.getOrDefault("fast", 12), path + ".fast");
				int slow = period(node.getOrDefault("slow", 26), path + ".slow");
				int signal = period(node.getOrDefault("signal", 9), path + ".signal");
				if (fast >= slow) {
					throw new InvalidStrategyException(path, "fast must be shorter than slow");
				}
				Output output = output(node, path, Output.MACD, Set.of(Output.MACD, Output.SIGNAL, Output.HISTOGRAM));
				yield new Indicator(kind, 0, fast, slow, signal, 0, output);
			}
			case BOLLINGER -> {
				onlyKeys(node, Set.of("indicator", "period", "stdDev", "output"), path);
				int period = period(node.getOrDefault("period", 20), path + ".period");
				double stdDev = number(node.getOrDefault("stdDev", 2), path + ".stdDev");
				if (!(stdDev > 0 && stdDev <= 5)) {
					throw new InvalidStrategyException(path + ".stdDev", "must be above 0 and at most 5");
				}
				Output output = output(node, path, Output.MIDDLE, Set.of(Output.UPPER, Output.MIDDLE, Output.LOWER));
				yield new Indicator(kind, period, 0, 0, 0, stdDev, output);
			}
		};
	}

	private static Output output(Map<String, Object> node, String path, Output fallback, Set<Output> allowed) {
		if (!node.containsKey("output")) {
			return fallback;
		}
		String name = string(node.get("output"), path + ".output").toUpperCase(Locale.ROOT);
		Output output;
		try {
			output = Output.valueOf(name);
		}
		catch (IllegalArgumentException ex) {
			output = null;
		}
		if (output == null || !allowed.contains(output)) {
			throw new InvalidStrategyException(path + ".output",
					"must be one of " + allowed.stream().map(o -> o.name().toLowerCase(Locale.ROOT)).sorted().toList());
		}
		return output;
	}

	private static int period(Object json, String path) {
		double value = number(json, path);
		if (value != Math.rint(value) || value < 1 || value > MAX_PERIOD) {
			throw new InvalidStrategyException(path, "must be a whole number from 1 to " + MAX_PERIOD);
		}
		return (int) value;
	}

	private static Strategy.Risk risk(Object json) {
		Map<String, Object> node = object(json, "risk");
		onlyKeys(node, Set.of("stopLossPct", "takeProfitPct"), "risk");
		Double stopLoss = null;
		Double takeProfit = null;
		if (node.containsKey("stopLossPct")) {
			stopLoss = number(node.get("stopLossPct"), "risk.stopLossPct");
			if (!(stopLoss > 0 && stopLoss < 100)) {
				throw new InvalidStrategyException("risk.stopLossPct", "must be above 0 and below 100");
			}
		}
		if (node.containsKey("takeProfitPct")) {
			takeProfit = number(node.get("takeProfitPct"), "risk.takeProfitPct");
			if (!(takeProfit > 0 && takeProfit <= 1000)) {
				throw new InvalidStrategyException("risk.takeProfitPct", "must be above 0 and at most 1000");
			}
		}
		return new Strategy.Risk(stopLoss, takeProfit);
	}

	private static Strategy.Sizing sizing(Object json, int symbolCount) {
		Map<String, Object> node = object(json, "sizing");
		onlyKeys(node, Set.of("type", "value", "maxPositions"), "sizing");
		String type = string(required(node, "type", "sizing"), "sizing.type");
		double value = number(required(node, "value", "sizing"), "sizing.value");
		Strategy.Sizing.Type parsed = switch (type) {
			case "percentOfEquity" -> {
				if (!(value > 0 && value <= 100)) {
					throw new InvalidStrategyException("sizing.value", "a percentage must be above 0 and at most 100");
				}
				yield Strategy.Sizing.Type.PERCENT_OF_EQUITY;
			}
			case "fixedQty" -> {
				if (value != Math.rint(value) || value < 1 || value > 1_000_000) {
					throw new InvalidStrategyException("sizing.value", "a share count must be a whole number from 1 to 1,000,000");
				}
				yield Strategy.Sizing.Type.FIXED_QTY;
			}
			case "fixedCash" -> {
				if (!(value > 0 && value <= 1_000_000_000)) {
					throw new InvalidStrategyException("sizing.value", "a cash amount must be above 0");
				}
				yield Strategy.Sizing.Type.FIXED_CASH;
			}
			default -> throw new InvalidStrategyException("sizing.type",
					"must be percentOfEquity, fixedQty or fixedCash");
		};
		int maxPositions = symbolCount;
		if (node.containsKey("maxPositions")) {
			double max = number(node.get("maxPositions"), "sizing.maxPositions");
			if (max != Math.rint(max) || max < 1 || max > symbolCount) {
				throw new InvalidStrategyException("sizing.maxPositions", "must be a whole number from 1 to the number of symbols");
			}
			maxPositions = (int) max;
		}
		return new Strategy.Sizing(parsed, value, maxPositions);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> object(Object json, String path) {
		if (json instanceof Map<?, ?> map) {
			return (Map<String, Object>) map;
		}
		throw new InvalidStrategyException(path, "must be an object");
	}

	private static Object required(Map<String, Object> node, String key, String path) {
		if (!node.containsKey(key) || node.get(key) == null) {
			throw new InvalidStrategyException(path.isEmpty() ? key : path + "." + key, "is required");
		}
		return node.get(key);
	}

	private static String string(Object json, String path) {
		if (json instanceof String text) {
			return text;
		}
		throw new InvalidStrategyException(path, "must be text");
	}

	private static double number(Object json, String path) {
		if (json instanceof Number number && Double.isFinite(number.doubleValue())) {
			return number.doubleValue();
		}
		throw new InvalidStrategyException(path, "must be a number");
	}

	private static void onlyKeys(Map<String, Object> node, Set<String> allowed, String path) {
		Set<String> unknown = new HashSet<>(node.keySet());
		unknown.removeAll(allowed);
		if (!unknown.isEmpty()) {
			String field = unknown.stream().sorted().findFirst().orElseThrow();
			throw new InvalidStrategyException(path.isEmpty() ? field : path + "." + field, "isn't a known field");
		}
	}

}
