-- SEC filings fetched by the ai-service for the research copilot, split into sections and chunks.
CREATE TABLE documents
(
  id         UUID PRIMARY KEY,
  ticker     TEXT NOT NULL,
  source     TEXT NOT NULL,
  form_type  TEXT NOT NULL,
  filed_at   DATE NOT NULL,
  url        TEXT NOT NULL UNIQUE,
  title      TEXT NOT NULL,
  fetched_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX documents_ticker_idx ON documents (ticker, filed_at DESC);

-- tsv is computed by Postgres from content, so search always matches what is stored.
-- Vector search (pgvector) can be added beside it later; full-text search needs no API key.
CREATE TABLE doc_chunks
(
  id          UUID PRIMARY KEY,
  document_id UUID NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
  section     TEXT NOT NULL,
  seq         INT NOT NULL,
  content     TEXT NOT NULL,
  tsv         TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', content)) STORED
);
CREATE INDEX doc_chunks_tsv_idx ON doc_chunks USING GIN (tsv);
CREATE INDEX doc_chunks_document_id_idx ON doc_chunks (document_id, seq);
