CREATE TABLE instruments
(
  symbol   TEXT PRIMARY KEY,
  name     TEXT NOT NULL,
  exchange TEXT NOT NULL,
  tradable BOOLEAN NOT NULL
);

CREATE TABLE watchlists
(
  id         UUID PRIMARY KEY,
  user_id    UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  name       TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  UNIQUE (user_id, name)
);

CREATE TABLE watchlist_items
(
  watchlist_id UUID NOT NULL REFERENCES watchlists (id) ON DELETE CASCADE,
  symbol       TEXT NOT NULL REFERENCES instruments (symbol),
  PRIMARY KEY (watchlist_id, symbol)
);

-- Starter list so the app works before Alpaca keys are set. InstrumentSeeder adds the full list.
INSERT INTO instruments (symbol, name, exchange, tradable) VALUES
  ('AAPL', 'Apple Inc.', 'NASDAQ', TRUE),
  ('MSFT', 'Microsoft Corporation', 'NASDAQ', TRUE),
  ('NVDA', 'NVIDIA Corporation', 'NASDAQ', TRUE),
  ('GOOGL', 'Alphabet Inc. Class A', 'NASDAQ', TRUE),
  ('AMZN', 'Amazon.com, Inc.', 'NASDAQ', TRUE),
  ('META', 'Meta Platforms, Inc.', 'NASDAQ', TRUE),
  ('TSLA', 'Tesla, Inc.', 'NASDAQ', TRUE),
  ('AVGO', 'Broadcom Inc.', 'NASDAQ', TRUE),
  ('AMD', 'Advanced Micro Devices, Inc.', 'NASDAQ', TRUE),
  ('NFLX', 'Netflix, Inc.', 'NASDAQ', TRUE),
  ('COST', 'Costco Wholesale Corporation', 'NASDAQ', TRUE),
  ('ADBE', 'Adobe Inc.', 'NASDAQ', TRUE),
  ('INTC', 'Intel Corporation', 'NASDAQ', TRUE),
  ('QCOM', 'Qualcomm Incorporated', 'NASDAQ', TRUE),
  ('BRK.B', 'Berkshire Hathaway Inc. Class B', 'NYSE', TRUE),
  ('JPM', 'JPMorgan Chase & Co.', 'NYSE', TRUE),
  ('V', 'Visa Inc.', 'NYSE', TRUE),
  ('MA', 'Mastercard Incorporated', 'NYSE', TRUE),
  ('BAC', 'Bank of America Corporation', 'NYSE', TRUE),
  ('GS', 'The Goldman Sachs Group, Inc.', 'NYSE', TRUE),
  ('WMT', 'Walmart Inc.', 'NYSE', TRUE),
  ('HD', 'The Home Depot, Inc.', 'NYSE', TRUE),
  ('KO', 'The Coca-Cola Company', 'NYSE', TRUE),
  ('PG', 'The Procter & Gamble Company', 'NYSE', TRUE),
  ('JNJ', 'Johnson & Johnson', 'NYSE', TRUE),
  ('UNH', 'UnitedHealth Group Incorporated', 'NYSE', TRUE),
  ('XOM', 'Exxon Mobil Corporation', 'NYSE', TRUE),
  ('DIS', 'The Walt Disney Company', 'NYSE', TRUE),
  ('SPY', 'SPDR S&P 500 ETF Trust', 'ARCA', TRUE),
  ('QQQ', 'Invesco QQQ Trust', 'NASDAQ', TRUE);
