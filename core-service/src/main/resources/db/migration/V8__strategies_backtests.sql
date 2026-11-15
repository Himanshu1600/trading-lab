-- dsl holds the strategy JSON exactly as validated. source_prompt is set when the AI drafted it.
CREATE TABLE strategies
(
  id            UUID PRIMARY KEY,
  user_id       UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  name          TEXT NOT NULL,
  dsl           JSONB NOT NULL,
  dsl_version   INT NOT NULL,
  source_prompt TEXT,
  created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX strategies_user_id_idx ON strategies (user_id, updated_at DESC);

-- A run keeps its own copy of the strategy, so editing or deleting the strategy never changes
-- old results. input_hash covers the strategy and the settings: equal inputs give equal results.
CREATE TABLE backtest_runs
(
  id            UUID PRIMARY KEY,
  user_id       UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  strategy_id   UUID REFERENCES strategies (id) ON DELETE SET NULL,
  strategy_name TEXT NOT NULL,
  dsl           JSONB NOT NULL,
  params        JSONB NOT NULL,
  input_hash    TEXT NOT NULL,
  status        TEXT NOT NULL,
  metrics       JSONB,
  error         TEXT,
  created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
  started_at    TIMESTAMP WITH TIME ZONE,
  finished_at   TIMESTAMP WITH TIME ZONE,
  duration_ms   BIGINT
);
CREATE INDEX backtest_runs_user_id_idx ON backtest_runs (user_id, created_at DESC);
CREATE INDEX backtest_runs_unfinished_idx ON backtest_runs (status) WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE backtest_trades
(
  run_id      UUID NOT NULL REFERENCES backtest_runs (id) ON DELETE CASCADE,
  seq         INT NOT NULL,
  symbol      TEXT NOT NULL,
  qty         BIGINT NOT NULL,
  entry_date  DATE NOT NULL,
  entry_price NUMERIC(18, 4) NOT NULL,
  exit_date   DATE NOT NULL,
  exit_price  NUMERIC(18, 4) NOT NULL,
  pnl         NUMERIC(18, 4) NOT NULL,
  return_pct  NUMERIC(12, 4) NOT NULL,
  exit_reason TEXT NOT NULL,
  PRIMARY KEY (run_id, seq)
);

-- One row per trading day: the strategy's equity, its drawdown from the peak, and buy-and-hold.
CREATE TABLE backtest_equity
(
  run_id    UUID NOT NULL REFERENCES backtest_runs (id) ON DELETE CASCADE,
  day       DATE NOT NULL,
  equity    NUMERIC(18, 4) NOT NULL,
  drawdown  NUMERIC(10, 6) NOT NULL,
  benchmark NUMERIC(18, 4) NOT NULL,
  PRIMARY KEY (run_id, day)
);
