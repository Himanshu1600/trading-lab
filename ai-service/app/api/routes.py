"""Internal endpoints, called only by core-service with the shared service token."""

from __future__ import annotations

import hmac
from functools import lru_cache
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, Field

from app.config import settings
from app.copilot import answer, sec
from app.copilot.store import PostgresStore, Store
from app.journal import insights
from app.llm.client import (
    AnthropicLlm,
    BudgetExceeded,
    CallLog,
    Llm,
    LlmError,
    LlmRefused,
    LlmUnavailable,
    TokenBudget,
)
from app.strategy import draft, explain

router = APIRouter(prefix="/v1")


def require_service_token(x_service_token: Annotated[str | None, Header()] = None) -> None:
    expected = settings().service_token
    if not expected:
        raise HTTPException(503, "AI_SERVICE_TOKEN isn't set, so the AI service accepts no calls.")
    if not x_service_token or not hmac.compare_digest(x_service_token, expected):
        raise HTTPException(401, "Missing or wrong service token.")


@lru_cache
def get_llm() -> Llm:
    import anthropic
    import redis

    config = settings()
    if not config.anthropic_api_key:
        raise LlmUnavailable("ANTHROPIC_API_KEY isn't set, so AI features are off.")
    return AnthropicLlm(
        anthropic.Anthropic(api_key=config.anthropic_api_key),
        TokenBudget(redis.Redis.from_url(config.redis_url, socket_timeout=2), config.daily_token_budget),
        CallLog(config.database_url),
    )


def llm_dependency() -> Llm:
    try:
        return get_llm()
    except LlmUnavailable as error:
        raise HTTPException(503, str(error)) from error


Authorized = Depends(require_service_token)
LlmDep = Annotated[Llm, Depends(llm_dependency)]


class ParseRequest(BaseModel):
    prompt: str = Field(min_length=3, max_length=2000)
    userId: str | None = None
    previousDsl: dict[str, Any] | None = None
    previousError: str | None = Field(default=None, max_length=2000)


class ParseResponse(BaseModel):
    kind: str
    question: str | None = None
    dsl: dict[str, Any] | None = None
    assumptions: list[str] = []
    promptVersion: str


@router.post("/strategy/parse", dependencies=[Authorized])
def parse_strategy(request: ParseRequest, llm: LlmDep) -> ParseResponse:
    result = _call(lambda: llm.structured(
        feature="strategy_parse",
        prompt_version=draft.PROMPT_VERSION,
        user_id=request.userId,
        model=settings().strategy_model,
        system=draft.SYSTEM_PROMPT,
        user=draft.user_message(request.prompt, request.previousDsl, request.previousError),
        output_format=draft.DraftResponse,
        effort="low",
        max_tokens=8000,
    ))
    if result.kind == "strategy" and result.strategy is not None:
        return ParseResponse(kind="strategy", dsl=draft.to_dsl(result.strategy),
                             assumptions=result.assumptions, promptVersion=draft.PROMPT_VERSION)
    question = result.question or "Could you describe the buying and selling rules you want to test?"
    return ParseResponse(kind="clarification", question=question, promptVersion=draft.PROMPT_VERSION)


class ExplainRequest(BaseModel):
    userId: str | None = None
    strategy: dict[str, Any]
    params: dict[str, Any]
    metrics: dict[str, Any]


class ExplainResponse(BaseModel):
    explanation: str
    promptVersion: str


@router.post("/backtest/explain", dependencies=[Authorized])
def explain_backtest(request: ExplainRequest, llm: LlmDep) -> ExplainResponse:
    allowed = explain.allowed_numbers(request.strategy, request.params, request.metrics)
    message = explain.user_message(request.strategy, request.params, request.metrics)
    # One retry with the problem pointed out; the second failure is reported, not hidden.
    for attempt in range(2):
        text = _call(lambda user=message: llm.text(
            feature="backtest_explain",
            prompt_version=explain.PROMPT_VERSION,
            user_id=request.userId,
            model=settings().explain_model,
            system=explain.SYSTEM_PROMPT,
            user=user,
            effort="medium",
            max_tokens=4000,
        ))
        problems = explain.unverified_numbers(text, allowed)
        if not problems:
            return ExplainResponse(explanation=text, promptVersion=explain.PROMPT_VERSION)
        message += (
            f"\n\nYour previous answer used numbers that aren't in the input: {', '.join(problems)}. "
            "Write it again using only numbers from the input."
        )
    raise HTTPException(502, "The explanation contained numbers that couldn't be verified. Try again.")


class InsightsRequest(BaseModel):
    userId: str | None = None
    stats: dict[str, Any]


class InsightsResponse(BaseModel):
    insights: str
    promptVersion: str


@router.post("/journal/insights", dependencies=[Authorized])
def journal_insights(request: InsightsRequest, llm: LlmDep) -> InsightsResponse:
    allowed = explain.allowed_numbers(request.stats)
    message = insights.user_message(request.stats)
    for _attempt in range(2):
        text = _call(lambda user=message: llm.text(
            feature="journal_insights",
            prompt_version=insights.PROMPT_VERSION,
            user_id=request.userId,
            model=settings().strategy_model,
            system=insights.SYSTEM_PROMPT,
            user=user,
            effort="low",
            max_tokens=4000,
        ))
        problems = explain.unverified_numbers(text, allowed)
        if not problems:
            return InsightsResponse(insights=text, promptVersion=insights.PROMPT_VERSION)
        message += (
            f"\n\nYour previous answer used numbers that aren't in the input: {', '.join(problems)}. "
            "Write it again using only numbers from the input."
        )
    raise HTTPException(502, "The coaching notes contained numbers that couldn't be verified. Try again.")


def store_dependency() -> Store:
    return PostgresStore(settings().database_url)


def edgar_dependency() -> sec.EdgarClient:
    try:
        return sec.EdgarClient(settings().sec_user_agent)
    except sec.SecUnavailable as error:
        raise HTTPException(503, str(error)) from error


StoreDep = Annotated[Store, Depends(store_dependency)]
EdgarDep = Annotated[sec.EdgarClient, Depends(edgar_dependency)]

# How many passages go to the model: enough to cover a question, few enough to stay cheap.
PASSAGES = 8


class IngestRequest(BaseModel):
    symbol: str = Field(pattern=r"^[A-Za-z][A-Za-z.]{0,9}$")


@router.post("/copilot/ingest", dependencies=[Authorized])
def ingest(request: IngestRequest, store: StoreDep, edgar: EdgarDep) -> dict[str, Any]:
    """Fetches the latest 10-K and 10-Q for a stock and stores their sections, replacing older copies."""
    return _ingest(request.symbol.upper(), store, edgar)


def _ingest(ticker: str, store: Store, edgar: sec.EdgarClient) -> dict[str, Any]:
    try:
        filings = edgar.latest_filings(ticker)
    except sec.SecUnavailable as error:
        raise HTTPException(502, str(error)) from error
    chunks = sum(store.save(ticker, filing) for filing in filings)
    return {"symbol": ticker, "filings": [f.title for f in filings], "chunks": chunks}


class AskRequest(BaseModel):
    userId: str | None = None
    symbol: str = Field(pattern=r"^[A-Za-z][A-Za-z.]{0,9}$")
    question: str = Field(min_length=3, max_length=1000)


@router.post("/copilot/ask", dependencies=[Authorized])
def ask(request: AskRequest, llm: LlmDep, store: StoreDep, edgar: EdgarDep) -> dict[str, Any]:
    """Answers from the stock's filings, fetching them first if this is the first question about it."""
    ticker = request.symbol.upper()
    if not store.has_documents(ticker):
        _ingest(ticker, store, edgar)
    passages = store.search(ticker, request.question, PASSAGES)
    if not passages:
        raise HTTPException(404, f"No filing text was found for {ticker}.")
    blocks = _call(lambda: llm.cited(
        feature="copilot_answer",
        prompt_version=answer.PROMPT_VERSION,
        user_id=request.userId,
        model=settings().explain_model,
        system=answer.SYSTEM_PROMPT,
        documents=answer.documents(passages),
        question=request.question,
        effort="medium",
        max_tokens=8000,
    ))
    return {"symbol": ticker, **answer.to_answer(blocks, passages), "promptVersion": answer.PROMPT_VERSION}


def _call(action):
    try:
        return action()
    except BudgetExceeded as error:
        raise HTTPException(429, str(error)) from error
    except LlmRefused as error:
        raise HTTPException(422, str(error)) from error
    except LlmUnavailable as error:
        raise HTTPException(503, str(error)) from error
    except LlmError as error:
        raise HTTPException(502, str(error)) from error
