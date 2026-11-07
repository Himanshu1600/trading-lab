package com.tradinglab.marketdata;

import java.time.Instant;
import java.util.List;

/** Where bars come from. Alpaca in the app, a fake in tests. */
interface PriceHistorySource {

	/** Bars from start to end, oldest first. Empty if no data source is configured. */
	List<PriceBar> bars(String symbol, BarTimeframe timeframe, Instant start, Instant end);

}
