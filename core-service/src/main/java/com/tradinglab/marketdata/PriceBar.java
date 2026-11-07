package com.tradinglab.marketdata;

import java.math.BigDecimal;
import java.time.Instant;

/** One day of trading for one symbol: open, high, low and close prices, and shares traded. */
record PriceBar(Instant time, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
}
