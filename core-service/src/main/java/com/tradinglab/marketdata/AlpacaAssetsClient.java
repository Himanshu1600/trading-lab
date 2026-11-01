package com.tradinglab.marketdata;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Component
@EnableConfigurationProperties(AlpacaProperties.class)
class AlpacaAssetsClient {

	private final RestClient restClient;

	AlpacaAssetsClient(RestClient.Builder builder, AlpacaProperties properties) {
		this.restClient = builder.baseUrl(properties.tradingUrl())
			.defaultHeader("APCA-API-KEY-ID", properties.apiKeyId())
			.defaultHeader("APCA-API-SECRET-KEY", properties.apiSecretKey())
			.build();
	}

	List<Asset> activeUsEquities() {
		return restClient.get()
			.uri("/v2/assets?status=active&asset_class=us_equity")
			.retrieve()
			.body(new ParameterizedTypeReference<>() {
			});
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Asset(String symbol, String name, String exchange, boolean tradable) {
	}

}
