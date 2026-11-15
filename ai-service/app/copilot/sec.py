"""Fetches a company's latest 10-K and 10-Q from SEC EDGAR and cuts out the sections worth reading.

EDGAR asks every client to identify itself with a User-Agent that includes contact details, and
to stay under 10 requests a second. SEC_USER_AGENT sets the first; a short pause sets the second.
"""

from __future__ import annotations

import re
import time
from dataclasses import dataclass
from html.parser import HTMLParser

import httpx

TICKERS_URL = "https://www.sec.gov/files/company_tickers.json"
SUBMISSIONS_URL = "https://data.sec.gov/submissions/CIK{cik:010d}.json"
ARCHIVE_URL = "https://www.sec.gov/Archives/edgar/data/{cik}/{accession}/{document}"
PAUSE_SECONDS = 0.15

# Each section starts at its "Item" heading and ends at the next item's heading.
SECTIONS = {
    "10-K": [
        ("Item 1. Business", r"item\s*1\.?\s*business", r"item\s*1a\.?\s*risk\s*factors"),
        ("Item 1A. Risk Factors", r"item\s*1a\.?\s*risk\s*factors", r"item\s*1b\.?|item\s*1c\.?|item\s*2\.?\s*properties"),
        ("Item 7. Management's Discussion and Analysis",
         r"item\s*7\.?\s*management", r"item\s*7a\.?|item\s*8\.?\s*financial\s*statements"),
    ],
    "10-Q": [
        ("Part I, Item 2. Management's Discussion and Analysis",
         r"item\s*2\.?\s*management", r"item\s*3\.?\s*quantitative|item\s*4\.?\s*controls"),
        ("Part II, Item 1A. Risk Factors", r"item\s*1a\.?\s*risk\s*factors", r"item\s*2\.?\s*unregistered|item\s*5\.?|item\s*6\.?"),
    ],
}

# Sections longer than this are cut, to bound storage; filings rarely need more to answer questions.
MAX_SECTION_CHARS = 200_000


class SecUnavailable(Exception):
    """EDGAR can't be used: no User-Agent configured, an unknown ticker, or a network failure."""


@dataclass(frozen=True)
class Filing:
    form_type: str
    filed_at: str
    url: str
    title: str
    sections: list[tuple[str, str]]


class _TextExtractor(HTMLParser):
    """HTML to plain text, keeping block breaks so headings stay on their own lines."""

    BLOCKS = frozenset({"p", "div", "br", "tr", "li", "h1", "h2", "h3", "h4", "h5", "h6", "table"})

    def __init__(self) -> None:
        super().__init__()
        self.parts: list[str] = []
        self._skip = 0

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag in ("script", "style", "ix:header"):
            self._skip += 1
        elif tag in self.BLOCKS:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag in ("script", "style", "ix:header"):
            self._skip = max(0, self._skip - 1)
        elif tag in self.BLOCKS:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if not self._skip:
            self.parts.append(data)


def html_to_text(html: str) -> str:
    parser = _TextExtractor()
    parser.feed(html)
    text = "".join(parser.parts).replace("\xa0", " ")
    text = re.sub(r"[ \t\r\f\v]+", " ", text)
    text = re.sub(r"\n\s*\n+", "\n\n", text)
    return text.strip()


def extract_sections(text: str, form_type: str) -> list[tuple[str, str]]:
    """Cuts out each known section. The table of contents repeats every heading, so for each
    section the longest span between its heading and the next one is taken: that's the body."""
    found = []
    for name, start, end in SECTIONS.get(form_type, []):
        best = ""
        for match in re.finditer(start, text, re.IGNORECASE):
            after = text[match.start():]
            stop = re.search(end, after[len(match.group(0)):], re.IGNORECASE)
            body = after[: len(match.group(0)) + stop.start()] if stop else after[:MAX_SECTION_CHARS]
            if len(body) > len(best):
                best = body
        if len(best) > 500:
            found.append((name, best[:MAX_SECTION_CHARS].strip()))
    return found


def chunk(text: str, words_per_chunk: int = 600, overlap: int = 80) -> list[str]:
    """Overlapping windows of words, so a sentence cut at one edge is whole in the next chunk."""
    words = text.split()
    if not words:
        return []
    chunks = []
    step = words_per_chunk - overlap
    for start in range(0, len(words), step):
        chunks.append(" ".join(words[start:start + words_per_chunk]))
        if start + words_per_chunk >= len(words):
            break
    return chunks


class EdgarClient:
    def __init__(self, user_agent: str, client: httpx.Client | None = None) -> None:
        if not user_agent or "example.com" in user_agent:
            raise SecUnavailable(
                "Set SEC_USER_AGENT to your app name and a real contact email, as SEC EDGAR requires."
            )
        self._http = client or httpx.Client(timeout=30, headers={"User-Agent": user_agent})

    def _get(self, url: str) -> httpx.Response:
        time.sleep(PAUSE_SECONDS)
        try:
            response = self._http.get(url)
            response.raise_for_status()
            return response
        except httpx.HTTPError as error:
            raise SecUnavailable(f"SEC EDGAR request failed: {error}") from error

    def cik(self, ticker: str) -> int:
        for company in self._get(TICKERS_URL).json().values():
            if company["ticker"].upper() == ticker.upper():
                return int(company["cik_str"])
        raise SecUnavailable(f"SEC EDGAR has no company with the ticker {ticker}.")

    def latest_filings(self, ticker: str) -> list[Filing]:
        """The newest 10-K and the newest 10-Q, each cut into its main sections."""
        cik = self.cik(ticker)
        recent = self._get(SUBMISSIONS_URL.format(cik=cik)).json()["filings"]["recent"]
        filings = []
        for form_type in ("10-K", "10-Q"):
            for i, form in enumerate(recent["form"]):
                if form != form_type:
                    continue
                url = ARCHIVE_URL.format(cik=cik, accession=recent["accessionNumber"][i].replace("-", ""),
                                         document=recent["primaryDocument"][i])
                text = html_to_text(self._get(url).text)
                filed_at = recent["filingDate"][i]
                filings.append(Filing(form_type, filed_at, url, f"{ticker.upper()} {form_type} filed {filed_at}",
                                      extract_sections(text, form_type)))
                break
        return filings
