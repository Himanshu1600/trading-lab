"""Filings and their chunks in Postgres, and full-text retrieval over them."""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import date
from typing import Protocol

from app.copilot import sec


@dataclass(frozen=True)
class Passage:
    title: str
    section: str
    url: str
    form_type: str
    filed_at: date
    content: str


class Store(Protocol):
    def has_documents(self, ticker: str) -> bool: ...

    def save(self, ticker: str, filing: sec.Filing) -> int: ...

    def search(self, ticker: str, question: str, limit: int) -> list[Passage]: ...


class PostgresStore:
    def __init__(self, database_url: str) -> None:
        self._database_url = database_url

    def _connect(self):
        import psycopg

        return psycopg.connect(self._database_url, connect_timeout=5)

    def has_documents(self, ticker: str) -> bool:
        with self._connect() as connection:
            row = connection.execute("SELECT 1 FROM documents WHERE ticker = %s LIMIT 1", (ticker,)).fetchone()
            return row is not None

    def save(self, ticker: str, filing: sec.Filing) -> int:
        """Replaces any earlier copy of the same filing. Returns the number of chunks stored."""
        count = 0
        with self._connect() as connection, connection.transaction():
            connection.execute("DELETE FROM documents WHERE url = %s", (filing.url,))
            document_id = uuid.uuid4()
            connection.execute(
                """INSERT INTO documents (id, ticker, source, form_type, filed_at, url, title, fetched_at)
                   VALUES (%s, %s, 'SEC', %s, %s, %s, %s, now())""",
                (document_id, ticker, filing.form_type, filing.filed_at, filing.url, filing.title),
            )
            for section, text in filing.sections:
                for seq, content in enumerate(sec.chunk(text)):
                    connection.execute(
                        "INSERT INTO doc_chunks (id, document_id, section, seq, content) VALUES (%s, %s, %s, %s, %s)",
                        (uuid.uuid4(), document_id, section, seq, content),
                    )
                    count += 1
        return count

    def search(self, ticker: str, question: str, limit: int) -> list[Passage]:
        """Ranks chunks by how well they match the question's words (ts_rank_cd), newest filing
        first on ties. If no word matches, falls back to the start of each section, so a broad
        question ("what does this company do?") still gets an answer."""
        query = """
            SELECT d.title, c.section, d.url, d.form_type, d.filed_at, c.content
            FROM doc_chunks c JOIN documents d ON d.id = c.document_id
            WHERE d.ticker = %(ticker)s AND c.tsv @@ websearch_to_tsquery('english', %(q)s)
            ORDER BY ts_rank_cd(c.tsv, websearch_to_tsquery('english', %(q)s)) DESC, d.filed_at DESC
            LIMIT %(limit)s
        """
        fallback = """
            SELECT d.title, c.section, d.url, d.form_type, d.filed_at, c.content
            FROM doc_chunks c JOIN documents d ON d.id = c.document_id
            WHERE d.ticker = %(ticker)s AND c.seq < 2
            ORDER BY d.filed_at DESC, c.section, c.seq
            LIMIT %(limit)s
        """
        # Questions are often phrased as sentences; OR-ing their words finds more than AND-ing them.
        words = [w for w in question.replace('"', " ").split() if len(w) > 2]
        params = {"ticker": ticker, "q": " or ".join(words) or question, "limit": limit}
        with self._connect() as connection:
            rows = connection.execute(query, params).fetchall() or connection.execute(fallback, params).fetchall()
        return [Passage(*row) for row in rows]
