"""The research copilot with a fake model, a fake store and a fake EDGAR: no network, no database."""

from __future__ import annotations

from datetime import date
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient

from app.api import routes
from app.config import settings
from app.copilot import sec
from app.copilot.store import Passage
from app.main import app

TOKEN = "test-service-token"
client = TestClient(app)

PASSAGES = [
    Passage("AAPL 10-K filed 2025-10-31", "Item 1A. Risk Factors", "https://sec.example/10k", "10-K",
            date(2025, 10, 31), "The Company depends on component suppliers in Asia."),
    Passage("AAPL 10-K filed 2025-10-31", "Item 7. Management's Discussion and Analysis", "https://sec.example/10k",
            "10-K", date(2025, 10, 31), "Services revenue grew because of the App Store."),
]


class FakeLlm:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    def cited(self, **kwargs: Any) -> list[Any]:
        self.calls.append(kwargs)
        citation = SimpleNamespace(document_index=1, cited_text="Services revenue grew because of the App Store.")
        return [
            SimpleNamespace(type="thinking", thinking=""),
            SimpleNamespace(type="text", text="Services revenue grew. ", citations=[citation]),
            SimpleNamespace(type="text", text="That's all the filings say.", citations=None),
        ]


class FakeStore:
    def __init__(self) -> None:
        self.saved: list[str] = []
        self.documents = False

    def has_documents(self, ticker: str) -> bool:
        return self.documents

    def save(self, ticker: str, filing: sec.Filing) -> int:
        self.saved.append(filing.title)
        self.documents = True
        return 3

    def search(self, ticker: str, question: str, limit: int) -> list[Passage]:
        return PASSAGES[:limit]


class FakeEdgar:
    def latest_filings(self, ticker: str) -> list[sec.Filing]:
        return [sec.Filing("10-K", "2025-10-31", "https://sec.example/10k", f"{ticker} 10-K filed 2025-10-31", [])]


@pytest.fixture
def fakes(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("AI_SERVICE_TOKEN", TOKEN)
    settings.cache_clear()
    llm, store = FakeLlm(), FakeStore()
    app.dependency_overrides[routes.llm_dependency] = lambda: llm
    app.dependency_overrides[routes.store_dependency] = lambda: store
    app.dependency_overrides[routes.edgar_dependency] = lambda: FakeEdgar()
    yield llm, store
    app.dependency_overrides.clear()
    settings.cache_clear()


def ask(question: str = "How is services revenue doing?"):
    return client.post("/v1/copilot/ask", json={"symbol": "aapl", "question": question, "userId": "u1"},
                       headers={"X-Service-Token": TOKEN})


def test_the_first_question_fetches_the_filings_then_answers_with_sources(fakes) -> None:
    llm, store = fakes

    body = ask().json()

    assert store.saved == ["AAPL 10-K filed 2025-10-31"]
    assert body["symbol"] == "AAPL"
    assert body["segments"] == [
        {"text": "Services revenue grew. ",
         "citations": [{"source": 1, "quote": "Services revenue grew because of the App Store."}]},
        {"text": "That's all the filings say.", "citations": []},
    ]
    # Only the passage that was cited is listed, numbered in the order it was first cited.
    assert body["sources"] == [{"number": 1, "title": "AAPL 10-K filed 2025-10-31",
                                "section": "Item 7. Management's Discussion and Analysis", "formType": "10-K",
                                "filedAt": "2025-10-31", "url": "https://sec.example/10k"}]
    documents = llm.calls[0]["documents"]
    assert len(documents) == 2
    assert documents[0]["citations"] == {"enabled": True}
    assert documents[0]["title"] == "AAPL 10-K filed 2025-10-31: Item 1A. Risk Factors"
    assert llm.calls[0]["model"] == "claude-sonnet-5-5"


def test_later_questions_reuse_the_stored_filings(fakes) -> None:
    _, store = fakes
    store.documents = True
    ask()
    assert store.saved == []


def test_without_a_sec_user_agent_the_copilot_is_off() -> None:
    with pytest.raises(sec.SecUnavailable):
        sec.EdgarClient("")
    with pytest.raises(sec.SecUnavailable):
        sec.EdgarClient("Trading Lab your-email@example.com")


def test_sections_are_cut_from_the_body_not_the_table_of_contents() -> None:
    body = "We sell phones. " * 100
    risks = "Supply chains could fail. " * 100
    text = (
        "Table of contents\nItem 1. Business 3\nItem 1A. Risk Factors 9\nItem 1B. Unresolved 20\n\n"
        f"Item 1. Business\n{body}\nItem 1A. Risk Factors\n{risks}\nItem 1B. Unresolved Staff Comments\nNone."
    )

    sections = dict(sec.extract_sections(text, "10-K"))

    assert sections["Item 1. Business"].startswith("Item 1. Business\nWe sell phones.")
    assert "Supply chains" not in sections["Item 1. Business"]
    assert sections["Item 1A. Risk Factors"].endswith("Supply chains could fail.")


def test_html_becomes_text_without_scripts() -> None:
    html = "<html><style>p{}</style><p>Item 1.&nbsp;Business</p><div>We sell <b>phones</b>.</div><script>x()</script>"
    assert sec.html_to_text(html) == "Item 1. Business\n\nWe sell phones."


def test_chunks_overlap_so_no_sentence_is_lost() -> None:
    words = " ".join(f"w{i}" for i in range(1000))
    chunks = sec.chunk(words, words_per_chunk=600, overlap=80)
    assert len(chunks) == 2
    assert chunks[0].split()[-80:] == chunks[1].split()[:80]
    assert chunks[1].split()[-1] == "w999"


def test_edgar_looks_up_the_company_and_its_latest_filings() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["User-Agent"] == "Trading Lab test@tradinglab.dev"
        if request.url.path.endswith("company_tickers.json"):
            return httpx.Response(200, json={"0": {"cik_str": 320193, "ticker": "AAPL"}})
        if "submissions" in request.url.path:
            return httpx.Response(200, json={"filings": {"recent": {
                "form": ["8-K", "10-Q", "10-K"], "accessionNumber": ["1", "0000320193-25-000079", "0000320193-25-000073"],
                "primaryDocument": ["a.htm", "q.htm", "k.htm"], "filingDate": ["2025-11-01", "2025-08-01", "2025-10-31"]}}})
        return httpx.Response(200, text="<p>Item 1. Business</p><p>" + "We sell phones. " * 100 + "</p>")

    transport = httpx.Client(transport=httpx.MockTransport(handler),
                             headers={"User-Agent": "Trading Lab test@tradinglab.dev"})
    sec.PAUSE_SECONDS = 0
    filings = sec.EdgarClient("Trading Lab test@tradinglab.dev", transport).latest_filings("aapl")

    assert [f.title for f in filings] == ["AAPL 10-K filed 2025-10-31", "AAPL 10-Q filed 2025-08-01"]
    assert filings[0].url == "https://www.sec.gov/Archives/edgar/data/320193/000032019325000073/k.htm"
    assert filings[0].sections[0][0] == "Item 1. Business"
