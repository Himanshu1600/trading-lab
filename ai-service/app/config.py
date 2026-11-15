"""Settings from the environment. Values in ../.env fill in anything not already set."""

import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

ENV_FILE = Path(__file__).resolve().parents[2] / ".env"


def _load_env_file(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip().strip('"').strip("'")
        if value:
            os.environ.setdefault(key.strip(), value)


@dataclass(frozen=True)
class Settings:
    service_token: str
    anthropic_api_key: str
    database_url: str
    redis_url: str
    daily_token_budget: int
    strategy_model: str
    explain_model: str
    sec_user_agent: str


@lru_cache
def settings() -> Settings:
    _load_env_file(ENV_FILE)
    env = os.environ
    database_url = (
        f"postgresql://{env.get('POSTGRES_USER', 'tradinglab')}:"
        f"{env.get('POSTGRES_PASSWORD', 'tradinglab')}@{env.get('POSTGRES_HOST', 'localhost')}:"
        f"{env.get('POSTGRES_PORT', '5433')}/{env.get('POSTGRES_DB', 'tradinglab')}"
    )
    return Settings(
        service_token=env.get("AI_SERVICE_TOKEN", ""),
        anthropic_api_key=env.get("ANTHROPIC_API_KEY", ""),
        database_url=database_url,
        redis_url=f"redis://{env.get('REDIS_HOST', 'localhost')}:{env.get('REDIS_PORT', '6379')}/0",
        daily_token_budget=int(env.get("AI_DAILY_TOKEN_BUDGET", "200000")),
        # PLAN.md §9: a fast model writes strategy JSON, a stronger one explains results.
        strategy_model=env.get("AI_STRATEGY_MODEL", "claude-haiku-5-5"),
        explain_model=env.get("AI_EXPLAIN_MODEL", "claude-sonnet-5-5"),
        # SEC EDGAR requires "App name contact@email". Never filled in for you.
        sec_user_agent=env.get("SEC_USER_AGENT", ""),
    )
