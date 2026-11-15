"""Answers a question from retrieved filing passages, with Claude's built-in citations.

Each passage goes to Claude as its own plain-text document block with citations on. Claude's
answer then comes back as text blocks, each carrying the exact sentences it relied on and which
document they came from, so every claim links to a real filing section. The passages are data:
they sit in document blocks, never in the instructions, and the copilot has no tools.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from app.copilot.store import Passage

PROMPT_VERSION = "copilot_answer.v1"
SYSTEM_PROMPT = (Path(__file__).resolve().parents[2] / "prompts" / f"{PROMPT_VERSION}.md").read_text(
    encoding="utf-8"
)


def documents(passages: list[Passage]) -> list[dict[str, Any]]:
    return [
        {
            "type": "document",
            "source": {"type": "text", "media_type": "text/plain", "data": passage.content},
            "title": f"{passage.title}: {passage.section}",
            "citations": {"enabled": True},
        }
        for passage in passages
    ]


def to_answer(blocks: list[Any], passages: list[Passage]) -> dict[str, Any]:
    """Text with numbered citations, and the list of sources they point at, in first-cited order."""
    segments = []
    order: dict[int, int] = {}
    for block in blocks:
        if getattr(block, "type", None) != "text":
            continue
        refs = []
        for citation in getattr(block, "citations", None) or []:
            index = getattr(citation, "document_index", None)
            if index is None or index >= len(passages):
                continue
            number = order.setdefault(index, len(order) + 1)
            refs.append({"source": number, "quote": getattr(citation, "cited_text", "").strip()})
        segments.append({"text": block.text, "citations": refs})
    sources = []
    for index, number in sorted(order.items(), key=lambda item: item[1]):
        passage = passages[index]
        sources.append({"number": number, "title": passage.title, "section": passage.section,
                        "formType": passage.form_type, "filedAt": passage.filed_at.isoformat(), "url": passage.url})
    return {"segments": segments, "sources": sources}
