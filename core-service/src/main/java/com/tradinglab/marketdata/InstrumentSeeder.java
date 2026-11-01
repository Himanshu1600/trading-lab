package com.tradinglab.marketdata;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;

/** Refreshes instruments from Alpaca at startup. Failures only log: the existing rows still work. */
@Component
class InstrumentSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(InstrumentSeeder.class);

	private static final String UPSERT = """
			INSERT INTO instruments (symbol, name, exchange, tradable) VALUES (?, ?, ?, ?)
			ON CONFLICT (symbol) DO UPDATE
			SET name = EXCLUDED.name, exchange = EXCLUDED.exchange, tradable = EXCLUDED.tradable
			""";

	private final AlpacaAssetsClient client;
	private final AlpacaProperties properties;
	private final JdbcTemplate jdbc;

	InstrumentSeeder(AlpacaAssetsClient client, AlpacaProperties properties, JdbcTemplate jdbc) {
		this.client = client;
		this.properties = properties;
		this.jdbc = jdbc;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.hasKeys()) {
			log.info("Alpaca keys not set, keeping the starter instrument list");
			return;
		}
		try {
			log.info("Seeded {} instruments from Alpaca", seed());
		}
		catch (RestClientException ex) {
			log.warn("Could not refresh instruments from Alpaca: {}", ex.getMessage());
		}
	}

	int seed() {
		List<AlpacaAssetsClient.Asset> assets = client.activeUsEquities();
		jdbc.batchUpdate(UPSERT, assets, 1000, (statement, asset) -> {
			statement.setString(1, asset.symbol());
			// Alpaca leaves the name blank on some assets.
			statement.setString(2, StringUtils.hasText(asset.name()) ? asset.name() : asset.symbol());
			statement.setString(3, asset.exchange());
			statement.setBoolean(4, asset.tradable());
		});
		return assets.size();
	}

}
