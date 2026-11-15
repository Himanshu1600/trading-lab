"""The endpoints with a fake model: no network, no API key, no cost."""

from __future__ import annotations

from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.api import routes
from app.config import settings
from app.llm.client import BudgetExceeded, LlmRefused
from app.main import app
from app.strategy.draft import (
    Comparison,
    DraftResponse,
    DraftStrategy,
    Operand,
    Risk,
    Rule,
)

TOKEN = "test-service-token"


class FakeLlm:
    def __init__(self) -> None:
        self.structured_result: Any = None
        self.texts: list[str] = []
        self.error: Exception | None = None
        self.calls: list[dict] = []

    def structured(self, **kwargs: Any) -> Any:
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        return self.structured_result

    def text(self, **kwargs: Any) -> str:
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        return self.texts.pop(0)


@pytest.fixture
def fake(monkeypatch: pytest.MonkeyPatch) -> FakeLlm:
    monkeypatch.setenv("AI_SERVICE_TOKEN", TOKEN)
    settings.cache_clear()
    llm = FakeLlm()
    app.dependency_overrides[routes.llm_dependency] = lambda: llm
    yield llm
    app.dependency_overrides.clear()
    settings.cache_clear()


client = TestClient(app)


def sma(period: int) -> Operand:
    return Operand(kind="indicator", indicator="SMA", period=period)


def crossover() -> DraftResponse:
    return DraftResponse(
        kind="strategy",
        strategy=DraftStrategy(
            name="Apple 20/50 crossover",
            symbols=["aapl"],
            entry=Rule(combine="all", conditions=[Comparison(op="crossesAbove", left=sma(20), right=sma(50))]),
            exit=Rule(combine="all", conditions=[Comparison(op="crossesBelow", left=sma(20), right=sma(50))]),
            risk=Risk(stop_loss_pct=5),
        ),
        assumptions=["No sizing was given, so the whole account is used."],
    )


def post(path: str, body: dict, token: str | None = TOKEN):
    headers = {"X-Service-Token": token} if token else {}
    return client.post(path, json=body, headers=headers)


def test_parse_returns_dsl_v1(fake: FakeLlm) -> None:
    fake.structured_result = crossover()

    response = post("/v1/strategy/parse", {"prompt": "Buy Apple on a 20/50 golden cross, 5% stop", "userId": "u1"})

    assert response.status_code == 200
    body = response.json()
    assert body["kind"] == "strategy"
    assert body["dsl"] == {
        "dslVersion": 1,
        "name": "Apple 20/50 crossover",
        "symbols": ["AAPL"],
        "timeframe": "1D",
        "entry": {"crossesAbove": [{"indicator": "SMA", "period": 20}, {"indicator": "SMA", "period": 50}]},
        "exit": {"crossesBelow": [{"indicator": "SMA", "period": 20}, {"indicator": "SMA", "period": 50}]},
        "risk": {"stopLossPct": 5.0},
    }
    assert body["assumptions"] == ["No sizing was given, so the whole account is used."]
    assert fake.calls[0]["model"] == "claude-haiku-5-5"
    assert fake.calls[0]["user_id"] == "u1"


def test_parse_passes_the_validator_error_back_for_a_repair(fake: FakeLlm) -> None:
    fake.structured_result = crossover()

    post("/v1/strategy/parse", {
        "prompt": "Buy Apple on a golden cross",
        "previousDsl": {"name": "x"},
        "previousError": "symbols[0]: ZZZZ isn't a tradable stock",
    })

    assert "ZZZZ isn't a tradable stock" in fake.calls[0]["user"]
    assert "<request>" in fake.calls[0]["user"]


def test_parse_can_ask_a_question(fake: FakeLlm) -> None:
    fake.structured_result = DraftResponse(kind="clarification", question="Which stocks should I test?")

    body = post("/v1/strategy/parse", {"prompt": "make me money"}).json()

    assert body == {"kind": "clarification", "question": "Which stocks should I test?", "dsl": None,
                    "assumptions": [], "promptVersion": "strategy_parse.v1"}


def test_explain_accepts_numbers_from_the_input(fake: FakeLlm) -> None:
    fake.texts = ["The strategy returned 12.35% against 20.1% for buy-and-hold, over 14 trades."]

    response = post("/v1/backtest/explain", {
        "strategy": {"name": "x"},
        "params": {"from": "2023-01-01"},
        "metrics": {"strategy": {"totalReturnPct": 12.3456, "trades": 14},
                    "benchmark": {"totalReturnPct": 20.1}},
    })

    assert response.status_code == 200
    assert response.json()["explanation"].startswith("The strategy returned 12.35%")
    assert fake.calls[0]["model"] == "claude-sonnet-5-5"


def test_explain_retries_once_when_a_number_is_invented(fake: FakeLlm) -> None:
    fake.texts = ["It returned 99.9%.", "It returned 12.35%."]

    response = post("/v1/backtest/explain", {
        "strategy": {}, "params": {}, "metrics": {"strategy": {"totalReturnPct": 12.3456}},
    })

    assert response.status_code == 200
    assert len(fake.calls) == 2
    assert "99.9" in fake.calls[1]["user"]


def test_explain_reports_a_second_invented_number(fake: FakeLlm) -> None:
    fake.texts = ["It returned 99.9%.", "It returned 88.8%."]

    response = post("/v1/backtest/explain", {"strategy": {}, "params": {}, "metrics": {"x": 1.5}})

    assert response.status_code == 502


@pytest.mark.parametrize(("error", "status"), [
    (LlmRefused("no"), 422),
    (BudgetExceeded("too much"), 429),
])
def test_model_problems_become_http_statuses(fake: FakeLlm, error: Exception, status: int) -> None:
    fake.error = error
    assert post("/v1/strategy/parse", {"prompt": "buy apple"}).status_code == status


def test_calls_need_the_service_token(fake: FakeLlm) -> None:
    assert post("/v1/strategy/parse", {"prompt": "buy apple"}, token=None).status_code == 401
    assert post("/v1/strategy/parse", {"prompt": "buy apple"}, token="wrong").status_code == 401


def test_without_a_configured_token_every_call_is_refused(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_SERVICE_TOKEN", "")
    settings.cache_clear()
    try:
        assert post("/v1/strategy/parse", {"prompt": "buy apple"}, token="").status_code == 503
    finally:
        settings.cache_clear()


def test_journal_insights_use_only_the_statistics(fake: FakeLlm) -> None:
    fake.texts = ["- You won 66.67% of 3 closed trades.", "- You won about two thirds of 3 closed trades."]

    first = post("/v1/journal/insights", {"stats": {"winRatePct": 66.6667, "closedTrades": 3}})

    assert first.status_code == 200
    assert first.json()["insights"].startswith("- You won 66.67%")
    assert fake.calls[0]["model"] == "claude-haiku-5-5"
    assert fake.calls[0]["feature"] == "journal_insights"


def test_status_reports_which_keys_are_set_without_revealing_them(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_SERVICE_TOKEN", TOKEN)
    monkeypatch.setenv("ANTHROPIC_API_KEY", "")
    monkeypatch.setenv("SEC_USER_AGENT", "Trading Lab your-email@example.com")
    settings.cache_clear()
    try:
        response = client.get("/v1/status", headers={"X-Service-Token": TOKEN})
        assert response.json() == {"claude": False, "research": False}
        assert client.get("/v1/status").status_code == 401
    finally:
        settings.cache_clear()
