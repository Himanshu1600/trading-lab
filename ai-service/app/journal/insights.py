"""Journal statistics -> a few short coaching notes about trading behaviour.

The statistics come from core-service. As with backtest explanations, every number in the notes
must appear in the input, and the model is asked again once if one doesn't.
"""

from __future__ import annotations

import json
from pathlib import Path

PROMPT_VERSION = "journal_insights.v1"
SYSTEM_PROMPT = (Path(__file__).resolve().parents[2] / "prompts" / f"{PROMPT_VERSION}.md").read_text(
    encoding="utf-8"
)


def user_message(stats: dict) -> str:
    return "<statistics>\n" + json.dumps(stats, indent=2, sort_keys=True) + "\n</statistics>"
