package com.tradinglab.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AlpacaMarketDataTests {

	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	private final AlpacaMarketData market = new AlpacaMarketData(builder,
			new AlpacaProperties("https://trading.test", "https://data.test", "key-id", "secret"));

	@Test
	void parsesSnapshotsAndCachesThemBriefly() {
		server.expect(once(), requestTo("https://data.test/v2/stocks/snapshots?symbols=AAPL,CLOSED,NONE&feed=iex"))
			.andExpect(header("APCA-API-KEY-ID", "key-id"))
			.andRespond(withSuccess("""
					{"AAPL":{"latestTrade":{"p":190.5,"s":10},"latestQuote":{"ap":190.6,"bp":190.4},
					         "prevDailyBar":{"c":188.0}},
					 "CLOSED":{"latestTrade":{"p":50.0},"latestQuote":{"ap":0,"bp":0}},
					 "NONE":{}}
					""", MediaType.APPLICATION_JSON));

		Map<String, Quote> quotes = market.latest(List.of("AAPL", "CLOSED", "NONE"));

		assertThat(quotes).containsOnlyKeys("AAPL", "CLOSED");
		Quote apple = quotes.get("AAPL");
		assertThat(apple.buyPrice()).isEqualByComparingTo("190.6");
		assertThat(apple.sellPrice()).isEqualByComparingTo("190.4");
		assertThat(apple.previousClose()).isEqualByComparingTo("188.0");
		assertThat(quotes.get("CLOSED").buyPrice()).isEqualByComparingTo("50.0");
		assertThat(quotes.get("CLOSED").sellPrice()).isEqualByComparingTo("50.0");

		assertThat(market.latest("AAPL")).isPresent();
		server.verify();
	}

	@Test
	void failedQuoteCallReturnsNothing() {
		server.expect(requestTo("https://data.test/v2/stocks/snapshots?symbols=AAPL&feed=iex"))
			.andRespond(withServerError());

		assertThat(market.latest("AAPL")).isEmpty();
	}

	@Test
	void followsBarPagesUntilTheLast() {
		Instant start = Instant.parse("2021-11-01T00:00:00Z");
		Instant end = Instant.parse("2026-11-02T14:00:00Z");
		String firstPage = "https://data.test/v2/stocks/BRK.B/bars?timeframe=1Day&start=2021-11-01T00:00:00Z"
				+ "&end=2026-11-02T14:00:00Z&limit=10000&adjustment=all&feed=sip&sort=asc";
		server.expect(requestTo(firstPage)).andRespond(withSuccess("""
				{"bars":[{"t":"2021-11-01T04:00:00Z","o":1,"h":2,"l":0.5,"c":1.5,"v":100}],"next_page_token":"abc"}
				""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(firstPage + "&page_token=abc")).andRespond(withSuccess("""
				{"bars":[{"t":"2021-11-02T04:00:00Z","o":1.5,"h":3,"l":1,"c":2.5,"v":200}],"next_page_token":null}
				""", MediaType.APPLICATION_JSON));

		List<PriceBar> bars = market.bars("BRK.B", BarTimeframe.DAILY, start, end);

		assertThat(bars).extracting(PriceBar::volume).containsExactly(100L, 200L);
		assertThat(bars.get(1).time()).isEqualTo(Instant.parse("2021-11-02T04:00:00Z"));
		assertThat(bars.get(1).close()).isEqualByComparingTo("2.5");
		server.verify();
	}

	@Test
	void fiveMinuteBarsUseTheRealTimeFeed() {
		server.expect(requestTo("https://data.test/v2/stocks/AAPL/bars?timeframe=5Min&start=2026-11-02T14:30:00Z"
				+ "&end=2026-11-02T15:00:00Z&limit=10000&adjustment=all&feed=iex&sort=asc"))
			.andRespond(withSuccess("""
					{"bars":[{"t":"2026-11-02T14:30:00Z","o":1,"h":2,"l":0.5,"c":1.5,"v":100}]}
					""", MediaType.APPLICATION_JSON));

		assertThat(market.bars("AAPL", BarTimeframe.FIVE_MINUTES, Instant.parse("2026-11-02T14:30:00Z"),
				Instant.parse("2026-11-02T15:00:00Z")))
			.hasSize(1);
		server.verify();
	}

	@Test
	void readsTheClock() {
		server.expect(requestTo("https://trading.test/v2/clock")).andRespond(withSuccess("""
				{"timestamp":"2026-11-02T10:00:00-05:00","is_open":true,
				 "next_open":"2026-11-03T09:30:00-05:00","next_close":"2026-11-02T16:00:00-05:00"}
				""", MediaType.APPLICATION_JSON));

		MarketClock.Status status = market.status();

		assertThat(status.open()).isTrue();
		assertThat(status.nextOpen()).isEqualTo(Instant.parse("2026-11-03T14:30:00Z"));
	}

	@Test
	void treatsAFailedClockAsClosed() {
		server.expect(requestTo("https://trading.test/v2/clock")).andRespond(withServerError());

		assertThat(market.status().open()).isFalse();
	}

	@Test
	void aBriefClockOutageKeepsTheLastReadingUntilItsCloseTime() {
		RestClient.Builder clockBuilder = RestClient.builder();
		MockRestServiceServer clockServer = MockRestServiceServer.bindTo(clockBuilder).build();
		// Ten minutes before the 4pm New York close, which is 21:00 UTC.
		MutableClock now = new MutableClock(Instant.parse("2026-11-02T20:50:00Z"));
		AlpacaMarketData timed = new AlpacaMarketData(clockBuilder,
				new AlpacaProperties("https://trading.test", "https://data.test", "key-id", "secret"), now);
		clockServer.expect(once(), requestTo("https://trading.test/v2/clock")).andRespond(withSuccess("""
				{"is_open":true,"next_open":"2026-11-03T09:30:00-05:00","next_close":"2026-11-02T16:00:00-05:00"}
				""", MediaType.APPLICATION_JSON));
		clockServer.expect(once(), requestTo("https://trading.test/v2/clock")).andRespond(withServerError());
		clockServer.expect(once(), requestTo("https://trading.test/v2/clock")).andRespond(withServerError());

		assertThat(timed.status().open()).isTrue();

		// The cache expires, Alpaca fails, and the last reading still says open.
		now.advance(Duration.ofSeconds(31));
		assertThat(timed.status().open()).isTrue();

		// Within the 10-second retry delay, Alpaca isn't asked at all.
		now.advance(Duration.ofSeconds(5));
		assertThat(timed.status().open()).isTrue();

		// Past the close it reported, Alpaca fails again, and the stale "open" turns into "closed".
		now.set(Instant.parse("2026-11-02T21:00:01Z"));
		assertThat(timed.status().open()).isFalse();
		clockServer.verify();
	}

	@Test
	void anOldClockReadingIsNotTrustedAtAll() {
		RestClient.Builder clockBuilder = RestClient.builder();
		MockRestServiceServer clockServer = MockRestServiceServer.bindTo(clockBuilder).build();
		MutableClock now = new MutableClock(Instant.parse("2026-11-02T15:00:00Z"));
		AlpacaMarketData timed = new AlpacaMarketData(clockBuilder,
				new AlpacaProperties("https://trading.test", "https://data.test", "key-id", "secret"), now);
		clockServer.expect(once(), requestTo("https://trading.test/v2/clock")).andRespond(withSuccess("""
				{"is_open":true,"next_open":"2026-11-03T09:30:00-05:00","next_close":"2026-11-02T16:00:00-05:00"}
				""", MediaType.APPLICATION_JSON));
		clockServer.expect(once(), requestTo("https://trading.test/v2/clock")).andRespond(withServerError());

		assertThat(timed.status().open()).isTrue();
		now.advance(Duration.ofMinutes(16));
		assertThat(timed.status().open()).isFalse();
		clockServer.verify();
	}

	/** A clock the test can move forward. */
	static final class MutableClock extends java.time.Clock {

		private volatile Instant instant;

		MutableClock(Instant instant) {
			this.instant = instant;
		}

		void advance(Duration duration) {
			instant = instant.plus(duration);
		}

		void set(Instant to) {
			instant = to;
		}

		@Override
		public java.time.ZoneId getZone() {
			return java.time.ZoneOffset.UTC;
		}

		@Override
		public java.time.Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}

	}

	@Test
	void missingKeysMeanNoData() {
		AlpacaMarketData noKeys = new AlpacaMarketData(RestClient.builder(),
				new AlpacaProperties("https://trading.test", "https://data.test", "", ""));

		assertThat(noKeys.latest(List.of("AAPL"))).isEmpty();
		assertThat(noKeys.status().open()).isFalse();
		assertThat(new Quote("X", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO).markPrice())
			.isEqualByComparingTo("1");
	}

}
