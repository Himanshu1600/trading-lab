package com.tradinglab.marketdata;

import java.time.Instant;

public interface MarketClock {

	Status status();

	record Status(boolean open, Instant nextOpen, Instant nextClose) {
	}

}
