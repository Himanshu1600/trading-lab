package com.tradinglab.marketdata;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves price history from the database, refreshing it from Alpaca when it's older than its
 * timeframe allows: an hour for daily bars, a minute for five-minute bars. No transaction is held
 * while Alpaca is called.
 */
@Service
class PriceHistoryService {

	private static final Logger log = LoggerFactory.getLogger(PriceHistoryService.class);

	static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

	private static final String UPSERT = """
			INSERT INTO price_bars (symbol, timeframe, ts, open, high, low, close, volume)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (symbol, timeframe, ts) DO UPDATE
			SET open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low,
			    close = EXCLUDED.close, volume = EXCLUDED.volume
			""";

	/** A chart range. A null start means "the most recent trading day in the data". */
	enum Range {

		ONE_DAY("1D", BarTimeframe.FIVE_MINUTES, null),
		FIVE_DAYS("5D", BarTimeframe.FIVE_MINUTES, now -> now.minusDays(7)),
		ONE_MONTH("1M", BarTimeframe.DAILY, now -> now.minusMonths(1)),
		THREE_MONTHS("3M", BarTimeframe.DAILY, now -> now.minusMonths(3)),
		SIX_MONTHS("6M", BarTimeframe.DAILY, now -> now.minusMonths(6)),
		ONE_YEAR("1Y", BarTimeframe.DAILY, now -> now.minusYears(1)),
		FIVE_YEARS("5Y", BarTimeframe.DAILY, now -> now.minusYears(5));

		private final String code;
		final BarTimeframe timeframe;
		private final UnaryOperator<ZonedDateTime> start;

		Range(String code, BarTimeframe timeframe, UnaryOperator<ZonedDateTime> start) {
			this.code = code;
			this.timeframe = timeframe;
			this.start = start;
		}

		static Range from(String code) {
			return Arrays.stream(values())
				.filter(range -> range.code.equalsIgnoreCase(code))
				.findFirst()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Range must be one of 1D, 5D, 1M, 3M, 6M, 1Y or 5Y"));
		}

	}

	private final JdbcClient jdbc;
	private final JdbcTemplate jdbcTemplate;
	private final PriceHistorySource source;
	private final InstrumentCatalog instruments;

	PriceHistoryService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, PriceHistorySource source,
			InstrumentCatalog instruments) {
		this.jdbc = jdbc;
		this.jdbcTemplate = jdbcTemplate;
		this.source = source;
		this.instruments = instruments;
	}

	List<PriceBar> bars(String symbol, Range range) {
		if (!instruments.isTradable(symbol)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown symbol");
		}
		BarTimeframe timeframe = range.timeframe;
		refreshIfStale(symbol, timeframe);
		Optional<Instant> from = (range.start != null)
				? Optional.of(range.start.apply(ZonedDateTime.now(MARKET_ZONE)).toInstant())
				: latestTradingDayStart(symbol, timeframe);
		if (from.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT ts AS time, open, high, low, close, volume FROM price_bars
				WHERE symbol = :symbol AND timeframe = :timeframe AND ts >= :from
				ORDER BY ts
				""")
			.param("symbol", symbol)
			.param("timeframe", timeframe.code)
			.param("from", utc(from.get()))
			.query((rs, row) -> new PriceBar(rs.getObject("time", OffsetDateTime.class).toInstant(),
					rs.getBigDecimal("open"), rs.getBigDecimal("high"), rs.getBigDecimal("low"),
					rs.getBigDecimal("close"), rs.getLong("volume")))
			.list();
	}

	/** Midnight in New York on the day of the newest stored bar, so 1D still shows Friday on a Sunday. */
	private Optional<Instant> latestTradingDayStart(String symbol, BarTimeframe timeframe) {
		return latestBar(symbol, timeframe)
			.map(latest -> latest.toInstant().atZone(MARKET_ZONE).toLocalDate().atStartOfDay(MARKET_ZONE).toInstant());
	}

	private Optional<OffsetDateTime> latestBar(String symbol, BarTimeframe timeframe) {
		return jdbc.sql("SELECT max(ts) FROM price_bars WHERE symbol = :symbol AND timeframe = :timeframe")
			.param("symbol", symbol)
			.param("timeframe", timeframe.code)
			.query(OffsetDateTime.class)
			.optional();
	}

	/**
	 * Two requests may both find the data stale and both fetch it. That only wastes one Alpaca
	 * call: the upsert makes writing the same bars twice harmless.
	 */
	private void refreshIfStale(String symbol, BarTimeframe timeframe) {
		Instant now = Instant.now();
		Optional<OffsetDateTime> syncedAt = jdbc
			.sql("SELECT synced_at FROM price_bar_syncs WHERE symbol = :symbol AND timeframe = :timeframe")
			.param("symbol", symbol)
			.param("timeframe", timeframe.code)
			.query(OffsetDateTime.class)
			.optional();
		if (syncedAt.isPresent() && syncedAt.get().toInstant().plus(timeframe.syncTtl).isAfter(now)) {
			return;
		}
		OffsetDateTime latest = latestBar(symbol, timeframe).orElse(null);
		Instant start = (latest != null) ? latest.toInstant().minus(timeframe.overlap)
				: now.minus(timeframe.initialHistory);
		List<PriceBar> bars;
		try {
			bars = source.bars(symbol, timeframe, start, now.minus(timeframe.delay));
		}
		catch (RestClientException ex) {
			log.warn("Could not load {} price history for {} from Alpaca: {}", timeframe.code, symbol,
					ex.getMessage());
			return;
		}
		// With no keys and nothing stored, skip recording a sync, so adding keys later takes effect at once.
		if (bars.isEmpty() && latest == null) {
			return;
		}
		jdbcTemplate.batchUpdate(UPSERT, bars, 1000, (statement, bar) -> {
			statement.setString(1, symbol);
			statement.setString(2, timeframe.code);
			statement.setObject(3, utc(bar.time()));
			statement.setBigDecimal(4, bar.open());
			statement.setBigDecimal(5, bar.high());
			statement.setBigDecimal(6, bar.low());
			statement.setBigDecimal(7, bar.close());
			statement.setLong(8, bar.volume());
		});
		jdbc.sql("""
				INSERT INTO price_bar_syncs (symbol, timeframe, synced_at) VALUES (:symbol, :timeframe, :now)
				ON CONFLICT (symbol, timeframe) DO UPDATE SET synced_at = EXCLUDED.synced_at
				""")
			.param("symbol", symbol)
			.param("timeframe", timeframe.code)
			.param("now", utc(now))
			.update();
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
