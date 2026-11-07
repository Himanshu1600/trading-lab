-- The CHECK constraints are the last line of defence: cash and shares can never go negative.
CREATE TABLE accounts
(
  id            UUID PRIMARY KEY,
  user_id       UUID NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
  cash          NUMERIC(18, 4) NOT NULL CHECK (cash >= 0),
  reserved_cash NUMERIC(18, 4) NOT NULL DEFAULT 0 CHECK (reserved_cash >= 0),
  starting_cash NUMERIC(18, 4) NOT NULL,
  created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Append-only audit of every cash movement.
CREATE TABLE cash_ledger
(
  id         UUID PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
  type       TEXT NOT NULL,
  amount     NUMERIC(18, 4) NOT NULL,
  ref_id     UUID,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX cash_ledger_account_id_idx ON cash_ledger (account_id);

CREATE TABLE positions
(
  account_id   UUID NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
  symbol       TEXT NOT NULL REFERENCES instruments (symbol),
  qty          BIGINT NOT NULL CHECK (qty >= 0),
  reserved_qty BIGINT NOT NULL DEFAULT 0 CHECK (reserved_qty >= 0 AND reserved_qty <= qty),
  avg_cost     NUMERIC(18, 6) NOT NULL,
  realized_pnl NUMERIC(18, 4) NOT NULL DEFAULT 0,
  PRIMARY KEY (account_id, symbol)
);

CREATE TABLE orders
(
  id              UUID PRIMARY KEY,
  account_id      UUID NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
  client_order_id TEXT NOT NULL,
  symbol          TEXT NOT NULL REFERENCES instruments (symbol),
  side            TEXT NOT NULL,
  type            TEXT NOT NULL,
  qty             BIGINT NOT NULL CHECK (qty > 0),
  limit_price     NUMERIC(18, 4),
  reserved_cash   NUMERIC(18, 4) NOT NULL DEFAULT 0,
  status          TEXT NOT NULL,
  avg_fill_price  NUMERIC(18, 4),
  reject_reason   TEXT,
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
  -- A retried request carries the same key, so it can't create a second order.
  UNIQUE (account_id, client_order_id)
);
CREATE INDEX orders_open_idx ON orders (created_at) WHERE status = 'OPEN';
CREATE INDEX orders_account_id_idx ON orders (account_id, created_at DESC);

CREATE TABLE fills
(
  id         UUID PRIMARY KEY,
  order_id   UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
  account_id UUID NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
  symbol     TEXT NOT NULL REFERENCES instruments (symbol),
  side       TEXT NOT NULL,
  qty        BIGINT NOT NULL,
  price      NUMERIC(18, 4) NOT NULL,
  filled_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX fills_account_id_idx ON fills (account_id, filled_at DESC);
