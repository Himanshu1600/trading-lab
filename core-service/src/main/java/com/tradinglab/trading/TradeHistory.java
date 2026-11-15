package com.tradinglab.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An account's completed trades, for other modules such as the journal. Read-only. */
public interface TradeHistory {

	/** Every fill for the account, oldest first. */
	List<FillRecord> fills(UUID accountId);

	long fillCount(UUID accountId);

	record FillRecord(String symbol, boolean buy, long qty, BigDecimal price, Instant filledAt) {
	}

}
