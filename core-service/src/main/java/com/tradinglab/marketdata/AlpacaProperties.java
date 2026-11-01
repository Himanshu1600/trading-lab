package com.tradinglab.marketdata;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties("alpaca")
record AlpacaProperties(String tradingUrl, String apiKeyId, String apiSecretKey) {

	boolean hasKeys() {
		return StringUtils.hasText(apiKeyId) && StringUtils.hasText(apiSecretKey);
	}

}
