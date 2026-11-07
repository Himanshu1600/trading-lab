package com.tradinglab.trading;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("trading")
record TradingProperties(Duration matchingInterval, BigDecimal slippageBps, BigDecimal marketBufferPct,
		boolean ignoreMarketHours) {
}
