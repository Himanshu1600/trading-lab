-- One row per Claude call, written by the ai-service. Powers cost and latency dashboards.
-- user_id has no foreign key: the log outlives deleted users, and the ai-service never reads users.
CREATE TABLE llm_calls
(
  id                 UUID PRIMARY KEY,
  feature            TEXT NOT NULL,
  model              TEXT NOT NULL,
  prompt_version     TEXT NOT NULL,
  user_id            UUID,
  input_tokens       INT NOT NULL,
  output_tokens      INT NOT NULL,
  cache_read_tokens  INT NOT NULL,
  cache_write_tokens INT NOT NULL,
  cost_usd           NUMERIC(12, 6) NOT NULL,
  latency_ms         INT NOT NULL,
  stop_reason        TEXT,
  error              TEXT,
  created_at         TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX llm_calls_created_at_idx ON llm_calls (created_at DESC);
CREATE INDEX llm_calls_user_id_idx ON llm_calls (user_id, created_at DESC);

-- Scores from each run of ai-service/evals/run_evals.py, to track prompt quality over time.
CREATE TABLE eval_runs
(
  id             UUID PRIMARY KEY,
  suite          TEXT NOT NULL,
  prompt_version TEXT NOT NULL,
  model          TEXT NOT NULL,
  git_sha        TEXT,
  metrics        JSONB NOT NULL,
  created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Generated once per run, on request, then reused.
ALTER TABLE backtest_runs ADD COLUMN explanation TEXT;
