"""Plain English -> strategy JSON (DSL v1).

Structured outputs can't express recursive schemas, and DSL v1 nests conditions to any depth.
So the model fills `DraftResponse`, a flat shape with at most one level of all/any grouping, and
`to_dsl()` turns that into DSL v1. core-service's StrategyParser then checks the result, so a bad
draft can never be saved or run.
"""

from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

PROMPT_VERSION = "strategy_parse.v1"
SYSTEM_PROMPT = (Path(__file__).resolve().parents[2] / "prompts" / f"{PROMPT_VERSION}.md").read_text(
    encoding="utf-8"
)


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Operand(Strict):
    """One side of a comparison. Fill only the fields for its kind; leave the rest null."""

    kind: Literal["price", "number", "indicator"]
    price: Literal["open", "high", "low", "close"] | None = None
    number: float | None = None
    indicator: Literal["SMA", "EMA", "RSI", "ATR", "MACD", "BOLLINGER"] | None = None
    period: int | None = Field(default=None, ge=1, le=500)
    fast: int | None = Field(default=None, ge=1, le=500)
    slow: int | None = Field(default=None, ge=1, le=500)
    signal: int | None = Field(default=None, ge=1, le=500)
    std_dev: float | None = None
    output: Literal["macd", "signal", "histogram", "upper", "middle", "lower"] | None = None


class Comparison(Strict):
    op: Literal["crossesAbove", "crossesBelow", "gt", "lt"]
    left: Operand
    right: Operand
    negate: bool = False


class Rule(Strict):
    """Conditions joined by all (every one true) or any (at least one true)."""

    combine: Literal["all", "any"]
    conditions: list[Comparison] = Field(min_length=1, max_length=10)


class Risk(Strict):
    stop_loss_pct: float | None = None
    take_profit_pct: float | None = None


class Sizing(Strict):
    type: Literal["percentOfEquity", "fixedQty", "fixedCash"]
    value: float
    max_positions: int | None = None


class DraftStrategy(Strict):
    name: str
    symbols: list[str] = Field(min_length=1, max_length=10)
    entry: Rule
    exit: Rule | None = None
    risk: Risk
    sizing: Sizing | None = None


class DraftResponse(Strict):
    """Either a strategy, or one question when the request is too vague to turn into rules."""

    kind: Literal["strategy", "clarification"]
    question: str | None = None
    strategy: DraftStrategy | None = None
    assumptions: list[str] = Field(default_factory=list)


def to_dsl(draft: DraftStrategy) -> dict:
    dsl: dict = {
        "dslVersion": 1,
        "name": draft.name.strip()[:100] or "Untitled strategy",
        "symbols": [symbol.strip().upper() for symbol in draft.symbols],
        "timeframe": "1D",
        "entry": _rule(draft.entry),
    }
    if draft.exit is not None:
        dsl["exit"] = _rule(draft.exit)
    risk = {}
    if draft.risk.stop_loss_pct is not None:
        risk["stopLossPct"] = draft.risk.stop_loss_pct
    if draft.risk.take_profit_pct is not None:
        risk["takeProfitPct"] = draft.risk.take_profit_pct
    if risk:
        dsl["risk"] = risk
    if draft.sizing is not None:
        sizing: dict = {"type": draft.sizing.type, "value": draft.sizing.value}
        if draft.sizing.max_positions is not None:
            sizing["maxPositions"] = draft.sizing.max_positions
        dsl["sizing"] = sizing
    return dsl


def _rule(rule: Rule) -> dict:
    conditions = [_comparison(c) for c in rule.conditions]
    return conditions[0] if len(conditions) == 1 else {rule.combine: conditions}


def _comparison(comparison: Comparison) -> dict:
    condition = {comparison.op: [_operand(comparison.left), _operand(comparison.right)]}
    return {"not": condition} if comparison.negate else condition


def _operand(operand: Operand) -> float | dict:
    if operand.kind == "number":
        return operand.number if operand.number is not None else 0
    if operand.kind == "price":
        return {"price": operand.price or "close"}
    indicator: dict = {"indicator": operand.indicator}
    if operand.indicator in ("SMA", "EMA", "RSI", "ATR"):
        indicator["period"] = operand.period
    elif operand.indicator == "MACD":
        for key in ("fast", "slow", "signal"):
            if getattr(operand, key) is not None:
                indicator[key] = getattr(operand, key)
        if operand.output:
            indicator["output"] = operand.output
    elif operand.indicator == "BOLLINGER":
        if operand.period is not None:
            indicator["period"] = operand.period
        if operand.std_dev is not None:
            indicator["stdDev"] = operand.std_dev
        if operand.output:
            indicator["output"] = operand.output
    return indicator


def user_message(prompt: str, previous_dsl: dict | None, previous_error: str | None) -> str:
    """The request, plus the last attempt and why it was refused when core-service asks for a repair."""
    message = f"<request>\n{prompt}\n</request>"
    if previous_dsl is not None and previous_error:
        message += (
            "\n\nYour previous draft was rejected by the validator. Fix exactly this problem and keep "
            f"everything else the same.\n<previous_draft>\n{previous_dsl}\n</previous_draft>\n"
            f"<validator_error>\n{previous_error}\n</validator_error>"
        )
    return message
