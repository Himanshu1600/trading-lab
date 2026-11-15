"""Backtest metrics -> a short plain-English explanation.

The model only narrates: every number comes from core-service's calculations. `unverified_numbers`
checks that each number in the explanation appears in the input, because a model that does its own
arithmetic will sometimes get it wrong.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

PROMPT_VERSION = "backtest_explain.v1"
SYSTEM_PROMPT = (Path(__file__).resolve().parents[2] / "prompts" / f"{PROMPT_VERSION}.md").read_text(
    encoding="utf-8"
)

# Numbers as written in prose: 12, 12.5, 1,234.56, -3.2. Years and day counts are numbers too.
NUMBER = re.compile(r"-?\d{1,3}(?:,\d{3})+(?:\.\d+)?|-?\d+(?:\.\d+)?")
# Small whole numbers ("one or two", "3 paragraphs") are allowed: they're counts, not results.
SMALL_COUNTS = {float(n) for n in range(11)}


def user_message(strategy: dict, params: dict, metrics: dict) -> str:
    return (
        "<strategy>\n" + json.dumps(strategy, indent=2, sort_keys=True) + "\n</strategy>\n"
        "<settings>\n" + json.dumps(params, indent=2, sort_keys=True) + "\n</settings>\n"
        "<metrics>\n" + json.dumps(metrics, indent=2, sort_keys=True) + "\n</metrics>"
    )


def allowed_numbers(*sources: object) -> set[float]:
    """Every number in the inputs, with its sign flipped and rounded to 0, 1 and 2 decimals."""
    found: set[float] = set(SMALL_COUNTS)

    def walk(value: object) -> None:
        if isinstance(value, bool):
            return
        if isinstance(value, (int, float)):
            for number in (float(value), -float(value)):
                found.add(number)
                for digits in (0, 1, 2):
                    found.add(round(number, digits))
        elif isinstance(value, dict):
            for item in value.values():
                walk(item)
        elif isinstance(value, (list, tuple)):
            for item in value:
                walk(item)
        elif isinstance(value, str):
            for match in NUMBER.findall(value):
                walk(float(match.replace(",", "")))

    for source in sources:
        walk(source)
    return found


def unverified_numbers(text: str, allowed: set[float]) -> list[str]:
    """Numbers in the text that don't appear in the inputs."""
    problems = []
    for match in NUMBER.findall(text):
        value = float(match.replace(",", ""))
        if not any(abs(value - a) < 0.0051 for a in allowed):
            problems.append(match)
    return problems
