package com.tradinglab.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AlpacaAssetsClientTests {

	@Test
	void sendsKeysAndParsesAssets() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		AlpacaAssetsClient client = new AlpacaAssetsClient(builder,
				new AlpacaProperties("https://alpaca.test", "https://data.test", "key-id", "secret"));
		server.expect(requestTo("https://alpaca.test/v2/assets?status=active&asset_class=us_equity"))
			.andExpect(header("APCA-API-KEY-ID", "key-id"))
			.andExpect(header("APCA-API-SECRET-KEY", "secret"))
			.andRespond(withSuccess("""
					[{"id":"1","class":"us_equity","exchange":"NASDAQ","symbol":"AAPL",
					  "name":"Apple Inc.","status":"active","tradable":true}]
					""", MediaType.APPLICATION_JSON));

		List<AlpacaAssetsClient.Asset> assets = client.activeUsEquities();

		assertThat(assets).containsExactly(new AlpacaAssetsClient.Asset("AAPL", "Apple Inc.", "NASDAQ", true));
		server.verify();
	}

}
