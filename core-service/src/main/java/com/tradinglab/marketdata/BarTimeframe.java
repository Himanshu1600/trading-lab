package com.tradinglab.marketdata;

import java.time.Duration;

/**
 * A bar size, and how its history is fetched and kept fresh.
 *
 * <p>Daily bars use SIP, the full-market feed, which the free plan only allows up to 15 minutes ago.
 * Five-minute bars use IEX, which is real-time but covers only part of the market's volume.
 */
enum BarTimeframe {

	DAILY("1Day", "sip", Duration.ofHours(1), Duration.ofDays(5 * 366), Duration.ofDays(7), Duration.ofMinutes(16)),
	FIVE_MINUTES("5Min", "iex", Duration.ofMinutes(1), Duration.ofDays(8), Duration.ofMinutes(30), Duration.ZERO);

	/** Alpaca's name for it, also stored in {@code price_bars.timeframe}. */
	final String code;

	final String feed;

	/** How long stored history counts as fresh. */
	final Duration syncTtl;

	/** How far back the first download goes. */
	final Duration initialHistory;

	/** How much is re-fetched on refresh, to pick up corrections and the bar in progress. */
	final Duration overlap;

	/** How far behind now requests must stop. */
	final Duration delay;

	BarTimeframe(String code, String feed, Duration syncTtl, Duration initialHistory, Duration overlap,
			Duration delay) {
		this.code = code;
		this.feed = feed;
		this.syncTtl = syncTtl;
		this.initialHistory = initialHistory;
		this.overlap = overlap;
		this.delay = delay;
	}

}
