package com.tradinglab.common;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Requests allowed per window. Auth is counted per IP address, the rest per signed-in user. */
@ConfigurationProperties("rate-limit")
record RateLimitProperties(Duration window, int auth, int orders, int backtests, int ai) {
}
