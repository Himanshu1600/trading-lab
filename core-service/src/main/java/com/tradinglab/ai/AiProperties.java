package com.tradinglab.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the ai-service runs, and the shared token it expects. An empty token turns AI features off.
 * {@code readTimeout} is longer than the default for other calls, because a copilot question may
 * download filings from SEC EDGAR before Claude answers.
 */
@ConfigurationProperties("ai")
record AiProperties(String serviceUrl, String serviceToken, Duration readTimeout) {

	boolean enabled() {
		return serviceToken != null && !serviceToken.isBlank();
	}

}
