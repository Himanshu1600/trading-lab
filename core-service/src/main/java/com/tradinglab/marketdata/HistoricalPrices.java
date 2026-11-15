package com.tradinglab.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Daily price history for other modules, such as backtesting. */
public interface HistoricalPrices {

	/**
	 * Daily bars from {@code from} to {@code to} inclusive, oldest first, keyed by New York trading
	 * day. Stored history is refreshed from Alpaca first if it's stale. Unknown symbols throw a 404.
	 */
	List<DailyBar> daily(String symbol, LocalDate from, LocalDate to);

	record DailyBar(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
	}

}
