package com.tradinglab.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tradinglab.strategy.Operand.Indicator;

import tools.jackson.databind.json.JsonMapper;

class StrategyParserTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void everyExampleInTheSharedSchemaParses() throws Exception {
		Map<?, ?> schema = JSON.readValue(Files.readString(Path.of("../contracts/strategy-dsl.v1.schema.json")),
				Map.class);
		List<?> examples = (List<?>) schema.get("examples");
		assertThat(examples).hasSizeGreaterThanOrEqualTo(3);
		for (Object example : examples) {
			assertThat(StrategyParser.parse(example).name()).isNotBlank();
		}
	}

	@Test
	void parsesTheCrossoverExampleIntoTheTree() {
		Strategy strategy = parse("""
				{"dslVersion": 1, "name": "Cross", "symbols": ["aapl"],
				 "entry": {"crossesAbove": [{"indicator": "SMA", "period": 20}, {"indicator": "sma", "period": 50}]},
				 "exit": {"crossesBelow": [{"indicator": "SMA", "period": 20}, {"indicator": "SMA", "period": 50}]},
				 "risk": {"stopLossPct": 5}}
				""");
		assertThat(strategy.symbols()).containsExactly("AAPL");
		assertThat(strategy.entry()).isInstanceOf(Condition.CrossesAbove.class);
		Condition.CrossesAbove entry = (Condition.CrossesAbove) strategy.entry();
		assertThat(entry.left()).isEqualTo(new Indicator(Indicator.Kind.SMA, 20, 0, 0, 0, 0, Indicator.Output.VALUE));
		assertThat(strategy.risk().stopLossPct()).isEqualTo(5.0);
		assertThat(strategy.risk().takeProfitPct()).isNull();
		// No sizing given: the equity is split evenly, one position per symbol.
		assertThat(strategy.sizing()).isEqualTo(new Strategy.Sizing(Strategy.Sizing.Type.PERCENT_OF_EQUITY, 100, 1));
	}

	@Test
	void bareNumbersAreConstantsAndIndicatorsGetTheirDefaults() {
		Strategy strategy = parse("""
				{"dslVersion": 1, "name": "x", "symbols": ["SPY", "QQQ", "IWM"],
				 "entry": {"all": [{"lt": [{"indicator": "RSI", "period": 14}, 30]},
				                   {"gt": [{"indicator": "MACD"}, {"value": 0}]},
				                   {"not": {"lt": [{"price": "close"}, {"indicator": "BOLLINGER", "output": "lower"}]}}]},
				 "risk": {"takeProfitPct": 10}}
				""");
		Condition.All all = (Condition.All) strategy.entry();
		assertThat(((Condition.LessThan) all.conditions().get(0)).right()).isEqualTo(new Operand.Constant(30));
		assertThat(((Condition.GreaterThan) all.conditions().get(1)).left())
			.isEqualTo(new Indicator(Indicator.Kind.MACD, 0, 12, 26, 9, 0, Indicator.Output.MACD));
		Condition.Not not = (Condition.Not) all.conditions().get(2);
		assertThat(((Condition.LessThan) not.condition()).right())
			.isEqualTo(new Indicator(Indicator.Kind.BOLLINGER, 20, 0, 0, 0, 2, Indicator.Output.LOWER));
		assertThat(strategy.exit()).isNull();
		assertThat(strategy.sizing().value()).isEqualTo(33.33);
	}

	@Test
	void errorsNameTheFieldThatIsWrong() {
		assertError("""
				{"dslVersion": 1, "name": "x", "symbols": ["AAPL"],
				 "entry": {"all": [{"gt": [{"price": "close"}, 1]}, {"lt": [{"indicator": "SMA", "period": 0}, 5]}]},
				 "risk": {"stopLossPct": 5}}
				""", "entry.all[1].lt[0].period");
		assertError("""
				{"dslVersion": 1, "name": "x", "symbols": ["AAPL"], "entry": {"above": [1, 2]}, "risk": {"stopLossPct": 5}}
				""", "entry");
		assertError("""
				{"dslVersion": 1, "name": "x", "symbols": ["AAPL"], "entry": {"gt": [{"price": "close"}, 1]},
				 "risk": {"stopLoss": 5}}
				""", "risk.stopLoss");
		assertError("""
				{"dslVersion": 2, "name": "x", "symbols": ["AAPL"], "entry": {"gt": [{"price": "close"}, 1]}}
				""", "dslVersion");
		assertError("""
				{"dslVersion": 1, "name": "x", "symbols": ["AAPL", "aapl"], "entry": {"gt": [{"price": "close"}, 1]},
				 "risk": {"stopLossPct": 5}}
				""", "symbols[1]");
	}

	@Test
	void aStrategyThatCanNeverSellIsRefused() {
		assertThatThrownBy(() -> parse("""
				{"dslVersion": 1, "name": "x", "symbols": ["AAPL"], "entry": {"gt": [{"price": "close"}, 1]}}
				""")).isInstanceOf(InvalidStrategyException.class).hasMessageContaining("never sell");
	}

	@Test
	void indicatorRulesAreChecked() {
		assertError("""
				{"dslVersion": 1, "name": "x", "symbols": ["AAPL"],
				 "entry": {"gt": [{"indicator": "MACD", "fast": 30, "slow": 10}, 0]}, "risk": {"stopLossPct": 5}}
				""", "entry.gt[0]");
		assertError("""
				{"dslVersion": 1, "name": "x", "symbols": ["AAPL"],
				 "entry": {"gt": [{"indicator": "SMA", "period": 5, "output": "upper"}, 0]}, "risk": {"stopLossPct": 5}}
				""", "entry.gt[0].output");
		assertError("""
				{"dslVersion": 1, "name": "x", "symbols": ["AAPL"], "entry": {"gt": [1, 2]}, "risk": {"stopLossPct": 5}}
				""", "entry.gt");
	}

	@Test
	void sizingRulesAreChecked() {
		assertError("""
				{"dslVersion": 1, "name": "x", "symbols": ["AAPL"], "entry": {"gt": [{"price": "close"}, 1]},
				 "risk": {"stopLossPct": 5}, "sizing": {"type": "percentOfEquity", "value": 150}}
				""", "sizing.value");
		assertError("""
				{"dslVersion": 1, "name": "x", "symbols": ["AAPL"], "entry": {"gt": [{"price": "close"}, 1]},
				 "risk": {"stopLossPct": 5}, "sizing": {"type": "fixedQty", "value": 10, "maxPositions": 2}}
				""", "sizing.maxPositions");
	}

	@Test
	void deeplyNestedConditionsAreRefused() {
		String condition = "{\"gt\": [{\"price\": \"close\"}, 1]}";
		for (int i = 0; i < StrategyParser.MAX_DEPTH; i++) {
			condition = "{\"not\": " + condition + "}";
		}
		assertError("{\"dslVersion\": 1, \"name\": \"x\", \"symbols\": [\"AAPL\"], \"entry\": " + condition
				+ ", \"risk\": {\"stopLossPct\": 5}}", "entry");
	}

	private static Strategy parse(String text) {
		return StrategyParser.parse(JSON.readValue(text, Map.class));
	}

	private static void assertError(String text, String path) {
		assertThatThrownBy(() -> parse(text)).isInstanceOfSatisfying(InvalidStrategyException.class,
				ex -> assertThat(ex.path()).startsWith(path));
	}

}
