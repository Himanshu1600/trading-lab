-- Daily price history. A TimescaleDB hypertable stores rows in time-based chunks,
-- so a date-range query only reads the chunks it needs.
CREATE TABLE price_bars
(
  symbol    TEXT NOT NULL,
  timeframe TEXT NOT NULL,
  ts        TIMESTAMP WITH TIME ZONE NOT NULL,
  open      NUMERIC(18, 4) NOT NULL,
  high      NUMERIC(18, 4) NOT NULL,
  low       NUMERIC(18, 4) NOT NULL,
  close     NUMERIC(18, 4) NOT NULL,
  volume    BIGINT NOT NULL,
  PRIMARY KEY (symbol, timeframe, ts)
);
SELECT create_hypertable('price_bars', by_range('ts', INTERVAL '1 year'));

-- When each symbol's history was last refreshed from Alpaca.
CREATE TABLE price_bar_syncs
(
  symbol    TEXT NOT NULL,
  timeframe TEXT NOT NULL,
  synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (symbol, timeframe)
);
