from __future__ import annotations

from app.llm.client import BudgetExceeded, TokenBudget, Usage
from app.strategy import explain
from app.strategy.draft import Comparison, DraftStrategy, Operand, Risk, Rule, Sizing, to_dsl


class MemoryRedis:
    def __init__(self) -> None:
        self.values: dict[str, int] = {}

    def get(self, key: str):
        return self.values.get(key)

    def incrby(self, key: str, amount: int) -> None:
        self.values[key] = self.values.get(key, 0) + amount

    def expire(self, key: str, seconds: int) -> None:
        pass


class BrokenRedis:
    def get(self, key: str):
        raise ConnectionError("down")

    def incrby(self, key: str, amount: int) -> None:
        raise ConnectionError("down")

    def expire(self, key: str, seconds: int) -> None:
        raise ConnectionError("down")


def test_budget_refuses_once_the_daily_limit_is_used() -> None:
    budget = TokenBudget(MemoryRedis(), daily_limit=1000)
    budget.check("u1")
    budget.add("u1", 999)
    budget.check("u1")
    budget.add("u1", 1)
    try:
        budget.check("u1")
        raise AssertionError("expected BudgetExceeded")
    except BudgetExceeded:
        pass
    budget.check("u2")


def test_a_redis_outage_never_blocks_a_call() -> None:
    budget = TokenBudget(BrokenRedis(), daily_limit=1)
    budget.check("u1")
    budget.add("u1", 5)


def test_cost_uses_each_token_type() -> None:
    usage = Usage(input_tokens=1_000_000, output_tokens=1_000_000, cache_read_tokens=0, cache_write_tokens=0)
    assert usage.cost_usd("claude-sonnet-5-5") == 12.0
    assert usage.cost_usd("unknown-model") == 0


def test_groups_negation_macd_and_bollinger_convert_to_dsl() -> None:
    draft = DraftStrategy(
        name="  Mixed  ",
        symbols=["msft", "nvda"],
        entry=Rule(combine="any", conditions=[
            Comparison(op="lt", left=Operand(kind="indicator", indicator="RSI", period=14),
                       right=Operand(kind="number", number=30)),
            Comparison(op="crossesAbove", left=Operand(kind="indicator", indicator="MACD", output="macd"),
                       right=Operand(kind="indicator", indicator="MACD", output="signal"), negate=True),
        ]),
        exit=Rule(combine="all", conditions=[
            Comparison(op="gt", left=Operand(kind="price", price="close"),
                       right=Operand(kind="indicator", indicator="BOLLINGER", period=20, std_dev=2,
                                     output="upper")),
        ]),
        risk=Risk(),
        sizing=Sizing(type="percentOfEquity", value=50, max_positions=2),
    )

    dsl = to_dsl(draft)

    assert dsl["name"] == "Mixed"
    assert dsl["symbols"] == ["MSFT", "NVDA"]
    assert dsl["entry"] == {"any": [
        {"lt": [{"indicator": "RSI", "period": 14}, 30.0]},
        {"not": {"crossesAbove": [{"indicator": "MACD", "output": "macd"},
                                  {"indicator": "MACD", "output": "signal"}]}},
    ]}
    assert dsl["exit"] == {"gt": [{"price": "close"},
                                  {"indicator": "BOLLINGER", "period": 20, "stdDev": 2.0, "output": "upper"}]}
    assert "risk" not in dsl
    assert dsl["sizing"] == {"type": "percentOfEquity", "value": 50.0, "maxPositions": 2}


def test_number_check_allows_rounding_and_small_counts_only() -> None:
    allowed = explain.allowed_numbers({"totalReturnPct": 12.3456, "maxDrawdownPct": 8.0, "endEquity": 112345.6},
                                      {"from": "2023-01-01"})
    assert explain.unverified_numbers("Up 12.35%, or 12.3% or 12%, worst drop 8%.", allowed) == []
    assert explain.unverified_numbers("It ended at $112,345.60 after 3 cautions.", allowed) == []
    assert explain.unverified_numbers("Since 2023 it gained.", allowed) == []
    assert explain.unverified_numbers("It made 15.2% a year.", allowed) == ["15.2"]
