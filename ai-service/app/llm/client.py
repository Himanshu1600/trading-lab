"""The one place that calls Claude. Every feature goes through `Llm`, which:

- checks the user's daily token budget in Redis before calling, and adds to it after;
- logs model, tokens (including cache reads), cost, latency, stop reason and prompt version
  to the `llm_calls` table;
- turns stop reasons into exceptions: `refusal` -> LlmRefused, `max_tokens` -> LlmTruncated;
- relies on the SDK's typed exceptions and built-in retries rather than matching error text.

Redis and Postgres are for accounting only. If either is down the call still goes ahead and the
problem is logged, so an outage of bookkeeping never blocks a user.
"""

from __future__ import annotations

import logging
import time
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, Protocol, TypeVar

import anthropic
from pydantic import BaseModel

log = logging.getLogger(__name__)

T = TypeVar("T", bound=BaseModel)

# US dollars per million tokens: input, output, cache read, cache write. Haiku's cache prices are
# estimated at the usual 0.1x and 1.25x of input; the others are published rates.
PRICES = {
    "claude-haiku-5-5": (0.10, 0.50, 0.01, 0.125),
    "claude-sonnet-5-5": (2.00, 10.00, 0.20, 2.50),
    "claude-opus-5-5": (4.00, 20.00, 0.20, 5.00),
}


class LlmError(Exception):
    """The model couldn't produce a usable answer."""


class LlmRefused(LlmError):
    """The model declined, for safety reasons."""


class LlmTruncated(LlmError):
    """The answer hit max_tokens before it finished."""


class LlmUnavailable(LlmError):
    """No API key is configured, or Anthropic can't be reached."""


class BudgetExceeded(Exception):
    """The user has used today's token allowance."""


@dataclass(frozen=True)
class Usage:
    input_tokens: int
    output_tokens: int
    cache_read_tokens: int
    cache_write_tokens: int

    @property
    def total(self) -> int:
        return self.input_tokens + self.output_tokens + self.cache_read_tokens + self.cache_write_tokens

    def cost_usd(self, model: str) -> float:
        price_in, price_out, price_read, price_write = PRICES.get(model, (0, 0, 0, 0))
        return (
            self.input_tokens * price_in
            + self.output_tokens * price_out
            + self.cache_read_tokens * price_read
            + self.cache_write_tokens * price_write
        ) / 1_000_000


class Llm(Protocol):
    def structured(
        self, *, feature: str, prompt_version: str, user_id: str | None, model: str,
        system: str, user: str, output_format: type[T], effort: str, max_tokens: int,
    ) -> T: ...

    def text(
        self, *, feature: str, prompt_version: str, user_id: str | None, model: str,
        system: str, user: str, effort: str, max_tokens: int,
    ) -> str: ...

    def cited(
        self, *, feature: str, prompt_version: str, user_id: str | None, model: str,
        system: str, documents: list[dict], question: str, effort: str, max_tokens: int,
    ) -> list[Any]: ...


class TokenBudget:
    """Tokens used per user per UTC day, in Redis under llm:tokens:<user>:<date>."""

    def __init__(self, redis_client: Any, daily_limit: int) -> None:
        self._redis = redis_client
        self._limit = daily_limit

    def _key(self, user_id: str) -> str:
        return f"llm:tokens:{user_id}:{datetime.now(UTC).date().isoformat()}"

    def check(self, user_id: str | None) -> None:
        if user_id is None:
            return
        try:
            used = int(self._redis.get(self._key(user_id)) or 0)
        except Exception as error:  # noqa: BLE001 - accounting must never block a call
            log.warning("Token budget check skipped, Redis unavailable: %s", error)
            return
        if used >= self._limit:
            raise BudgetExceeded(f"Daily AI allowance of {self._limit:,} tokens used. It resets at midnight UTC.")

    def add(self, user_id: str | None, tokens: int) -> None:
        if user_id is None or tokens <= 0:
            return
        try:
            key = self._key(user_id)
            self._redis.incrby(key, tokens)
            self._redis.expire(key, 2 * 24 * 3600)
        except Exception as error:  # noqa: BLE001
            log.warning("Token use not recorded, Redis unavailable: %s", error)


class CallLog:
    """Writes one row per model call to llm_calls."""

    def __init__(self, database_url: str) -> None:
        self._database_url = database_url

    def record(
        self, *, feature: str, model: str, prompt_version: str, user_id: str | None,
        usage: Usage | None, latency_ms: int, stop_reason: str | None, error: str | None,
    ) -> None:
        try:
            import psycopg

            with psycopg.connect(self._database_url, connect_timeout=3) as connection:
                connection.execute(
                    """
                    INSERT INTO llm_calls (id, feature, model, prompt_version, user_id, input_tokens,
                      output_tokens, cache_read_tokens, cache_write_tokens, cost_usd, latency_ms,
                      stop_reason, error, created_at)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, now())
                    """,
                    (
                        uuid.uuid4(), feature, model, prompt_version, _uuid_or_none(user_id),
                        usage.input_tokens if usage else 0, usage.output_tokens if usage else 0,
                        usage.cache_read_tokens if usage else 0, usage.cache_write_tokens if usage else 0,
                        usage.cost_usd(model) if usage else 0, latency_ms, stop_reason, error,
                    ),
                )
        except Exception as failure:  # noqa: BLE001
            log.warning("LLM call not logged: %s", failure)


class AnthropicLlm:
    def __init__(self, client: anthropic.Anthropic, budget: TokenBudget, calls: CallLog) -> None:
        self._client = client
        self._budget = budget
        self._calls = calls

    def structured(
        self, *, feature: str, prompt_version: str, user_id: str | None, model: str,
        system: str, user: str, output_format: type[T], effort: str, max_tokens: int,
    ) -> T:
        def call() -> Any:
            return self._client.messages.parse(
                model=model,
                max_tokens=max_tokens,
                # The system prompt is stable and first, so repeat calls read it from the cache.
                system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
                messages=[{"role": "user", "content": user}],
                output_format=output_format,
                output_config={"effort": effort},
            )

        response = self._run(feature, prompt_version, user_id, model, call)
        if response.parsed_output is None:
            raise LlmError("The model's answer didn't match the expected format.")
        return response.parsed_output

    def text(
        self, *, feature: str, prompt_version: str, user_id: str | None, model: str,
        system: str, user: str, effort: str, max_tokens: int,
    ) -> str:
        def call() -> Any:
            return self._client.beta.messages.create(
                model=model,
                max_tokens=max_tokens,
                system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
                messages=[{"role": "user", "content": user}],
                output_config={"effort": effort},
                # If a safety classifier declines, the API retries on a suitable model itself.
                betas=["server-side-fallback-2026-07-01"],
                fallbacks="default",
            )

        response = self._run(feature, prompt_version, user_id, model, call)
        return "".join(block.text for block in response.content if block.type == "text").strip()

    def cited(
        self, *, feature: str, prompt_version: str, user_id: str | None, model: str,
        system: str, documents: list[dict], question: str, effort: str, max_tokens: int,
    ) -> list[Any]:
        """Answers from documents with citations on. Returns the response's content blocks."""

        def call() -> Any:
            return self._client.beta.messages.create(
                model=model,
                max_tokens=max_tokens,
                system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
                messages=[{"role": "user", "content": [*documents, {"type": "text", "text": question}]}],
                output_config={"effort": effort},
                betas=["server-side-fallback-2026-07-01"],
                fallbacks="default",
            )

        return list(self._run(feature, prompt_version, user_id, model, call).content)

    def _run(self, feature: str, prompt_version: str, user_id: str | None, model: str, call: Any) -> Any:
        self._budget.check(user_id)
        started = time.monotonic()
        usage: Usage | None = None
        stop_reason: str | None = None
        error: str | None = None
        try:
            response = call()
            usage = _usage(response)
            stop_reason = response.stop_reason
            if stop_reason == "refusal":
                raise LlmRefused("The AI declined this request. Try rephrasing it.")
            if stop_reason == "max_tokens":
                raise LlmTruncated("The AI's answer was cut off. Try a shorter request.")
            return response
        except anthropic.AuthenticationError as exception:
            error = "authentication"
            raise LlmUnavailable("The AI service's API key was rejected.") from exception
        except anthropic.RateLimitError as exception:
            error = "rate_limited"
            raise LlmUnavailable("The AI is busy right now. Try again in a minute.") from exception
        except (anthropic.APIConnectionError, anthropic.InternalServerError) as exception:
            error = type(exception).__name__
            raise LlmUnavailable("The AI can't be reached right now.") from exception
        except anthropic.APIStatusError as exception:
            error = f"status_{exception.status_code}"
            raise LlmError(f"The AI request failed ({exception.status_code}).") from exception
        except LlmError as exception:
            error = type(exception).__name__
            raise
        finally:
            latency = int((time.monotonic() - started) * 1000)
            self._calls.record(
                feature=feature, model=model, prompt_version=prompt_version, user_id=user_id,
                usage=usage, latency_ms=latency, stop_reason=stop_reason, error=error,
            )
            if usage:
                self._budget.add(user_id, usage.total)


def _usage(response: Any) -> Usage:
    u = response.usage
    return Usage(
        input_tokens=u.input_tokens or 0,
        output_tokens=u.output_tokens or 0,
        cache_read_tokens=getattr(u, "cache_read_input_tokens", 0) or 0,
        cache_write_tokens=getattr(u, "cache_creation_input_tokens", 0) or 0,
    )


def _uuid_or_none(value: str | None) -> uuid.UUID | None:
    try:
        return uuid.UUID(value) if value else None
    except ValueError:
        return None
