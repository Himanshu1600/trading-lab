-- One row per account: trading statistics kept up to date by the Kafka consumer, plus the AI's
-- coaching notes. The notes are current only while insights_fill_count equals fill_count.
CREATE TABLE journal_snapshots
(
  account_id          UUID PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
  stats               JSONB NOT NULL,
  fill_count          BIGINT NOT NULL,
  updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
  insights            TEXT,
  insights_fill_count BIGINT,
  insights_at         TIMESTAMP WITH TIME ZONE
);

-- Kafka delivers at least once. Recording each handled event ID lets a repeat be ignored.
CREATE TABLE journal_processed_events
(
  event_id     UUID PRIMARY KEY,
  processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX journal_processed_events_processed_at_idx ON journal_processed_events (processed_at);
