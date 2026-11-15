-- Stop orders wait for the last trade to cross stop_price; triggered_at records that moment,
-- so a stop-limit keeps acting as a limit order even if the price moves back.
ALTER TABLE orders ADD COLUMN stop_price NUMERIC(18, 4);
ALTER TABLE orders ADD COLUMN triggered_at TIMESTAMP WITH TIME ZONE;

-- DAY orders expire at the close of the session they were placed for. Existing orders were GTC.
ALTER TABLE orders ADD COLUMN time_in_force TEXT NOT NULL DEFAULT 'GTC';
ALTER TABLE orders ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX orders_expiry_idx ON orders (expires_at) WHERE status = 'OPEN' AND expires_at IS NOT NULL;
