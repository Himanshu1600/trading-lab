"""Scores the prompts against the real model. Costs real money, so it only runs by hand:

    uv run python -m evals.run_evals                  # every suite
    uv run python -m evals.run_evals strategy_gen     # one suite

Needs ANTHROPIC_API_KEY. Results print as a table and are saved to the eval_runs table.

Suites (PLAN.md §10):
- strategy_gen: did the request become the expected kind (strategy or question), and for
  strategies, the right symbols, indicators, numbers, risk and sizing?
- advice_refusal: "should I buy X" requests must never become a strategy.
- explain: every number in the explanation must appear in the metrics (target 100%).
"""

from __future__ import annotations

import json
import subprocess
import sys
import uuid
from pathlib import Path

import anthropic

from app.config import settings
from app.llm.client import AnthropicLlm, CallLog, LlmError, TokenBudget
from app.strategy import draft, explain

DATASETS = Path(__file__).parent / "datasets"


class NoBudget:
    def get(self, key: str) -> None:
        return None

    def incrby(self, key: str, amount: int) -> None:
        pass

    def expire(self, key: str, seconds: int) -> None:
        pass


def load(name: str) -> list[dict]:
    lines = (DATASETS / f"{name}.jsonl").read_text(encoding="utf-8").splitlines()
    return [json.loads(line) for line in lines if line.strip()]


def parse(llm: AnthropicLlm, prompt: str) -> draft.DraftResponse:
    return llm.structured(
        feature="eval_strategy_parse", prompt_version=draft.PROMPT_VERSION, user_id=None,
        model=settings().strategy_model, system=draft.SYSTEM_PROMPT,
        user=draft.user_message(prompt, None, None), output_format=draft.DraftResponse,
        effort="low", max_tokens=8000,
    )


def indicators_in(dsl: dict) -> set[str]:
    found: set[str] = set()

    def walk(node: object) -> None:
        if isinstance(node, dict):
            if "indicator" in node:
                found.add(node["indicator"])
                if "period" in node and node["indicator"] in ("SMA", "EMA", "RSI", "ATR"):
                    found.add(f"{node['indicator']}:{node['period']}")
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(dsl)
    return found


def numbers_in(dsl: dict) -> set[float]:
    found: set[float] = set()

    def walk(node: object) -> None:
        if isinstance(node, (int, float)) and not isinstance(node, bool):
            found.add(float(node))
        elif isinstance(node, dict):
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(dsl.get("entry"))
    walk(dsl.get("exit"))
    return found


def strategy_gen(llm: AnthropicLlm) -> dict:
    cases = load("strategy_gen")
    kind_right = 0
    field_checks = 0
    field_right = 0
    for case in cases:
        expect = case["expect"]
        try:
            result = parse(llm, case["prompt"])
        except LlmError as error:
            print(f"  ERROR  {case['prompt'][:60]}: {error}")
            continue
        ok = result.kind == expect["kind"] and (result.kind != "strategy" or result.strategy is not None)
        kind_right += ok
        notes = []
        if ok and result.strategy is not None:
            dsl = draft.to_dsl(result.strategy)
            checks = []
            if "symbols" in expect:
                checks.append(("symbols", sorted(dsl["symbols"]) == sorted(expect["symbols"])))
            for indicator in expect.get("indicators", []):
                checks.append((indicator, indicator in indicators_in(dsl)))
            for number in expect.get("numbers", []):
                checks.append((str(number), float(number) in numbers_in(dsl)))
            for key in ("stopLossPct", "takeProfitPct"):
                if key in expect:
                    checks.append((key, dsl.get("risk", {}).get(key) == expect[key]))
            if "sizing" in expect:
                checks.append(("sizing", dsl.get("sizing", {}).get("type") == expect["sizing"]))
            field_checks += len(checks)
            field_right += sum(passed for _, passed in checks)
            notes = [name for name, passed in checks if not passed]
        status = "ok   " if ok and not notes else "MISS "
        print(f"  {status} {case['prompt'][:60]:<60} {result.kind:<13} {', '.join(notes)}")
    return {
        "cases": len(cases),
        "kindAccuracy": round(kind_right / len(cases), 4),
        "fieldAccuracy": round(field_right / field_checks, 4) if field_checks else None,
    }


def advice_refusal(llm: AnthropicLlm) -> dict:
    cases = load("advice_refusal")
    held_back = 0
    for case in cases:
        try:
            result = parse(llm, case["prompt"])
            ok = result.kind == "clarification"
        except LlmError:
            ok = True  # A refusal also gives no advice.
        held_back += ok
        print(f"  {'ok   ' if ok else 'MISS '} {case['prompt']}")
    return {"cases": len(cases), "noAdviceRate": round(held_back / len(cases), 4)}


def explain_suite(llm: AnthropicLlm) -> dict:
    cases = load("explain")
    verified = 0
    for case in cases:
        text = llm.text(
            feature="eval_backtest_explain", prompt_version=explain.PROMPT_VERSION, user_id=None,
            model=settings().explain_model, system=explain.SYSTEM_PROMPT,
            user=explain.user_message(case["strategy"], case["params"], case["metrics"]),
            effort="medium", max_tokens=4000,
        )
        problems = explain.unverified_numbers(
            text, explain.allowed_numbers(case["strategy"], case["params"], case["metrics"]))
        verified += not problems
        print(f"  {'ok   ' if not problems else 'MISS '} {case['strategy']['name']:<25} {', '.join(problems)}")
    return {"cases": len(cases), "numbersVerifiedRate": round(verified / len(cases), 4)}


SUITES = {
    "strategy_gen": (strategy_gen, draft.PROMPT_VERSION, lambda: settings().strategy_model),
    "advice_refusal": (advice_refusal, draft.PROMPT_VERSION, lambda: settings().strategy_model),
    "explain": (explain_suite, explain.PROMPT_VERSION, lambda: settings().explain_model),
}


def save(suite: str, prompt_version: str, model: str, metrics: dict) -> None:
    try:
        import psycopg

        sha = subprocess.run(["git", "rev-parse", "--short", "HEAD"], check=False, capture_output=True,
                             text=True).stdout.strip()
        with psycopg.connect(settings().database_url, connect_timeout=3) as connection:
            connection.execute(
                "INSERT INTO eval_runs (id, suite, prompt_version, model, git_sha, metrics, created_at) "
                "VALUES (%s, %s, %s, %s, %s, %s::jsonb, now())",
                (uuid.uuid4(), suite, prompt_version, model, sha or None, json.dumps(metrics)),
            )
    except Exception as error:  # noqa: BLE001
        print(f"  (not saved to eval_runs: {error})")


def main(names: list[str]) -> None:
    config = settings()
    if not config.anthropic_api_key:
        sys.exit("ANTHROPIC_API_KEY isn't set.")
    llm = AnthropicLlm(anthropic.Anthropic(api_key=config.anthropic_api_key),
                       TokenBudget(NoBudget(), daily_limit=1), CallLog(config.database_url))
    for name in names or list(SUITES):
        run, prompt_version, model = SUITES[name]
        print(f"\n{name} ({prompt_version}, {model()})")
        metrics = run(llm)
        print(f"  => {json.dumps(metrics)}")
        save(name, prompt_version, model(), metrics)


if __name__ == "__main__":
    main(sys.argv[1:])
