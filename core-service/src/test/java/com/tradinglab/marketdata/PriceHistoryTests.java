package com.tradinglab.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.tradinglab.TestcontainersConfiguration;

@Testcontainers(disabledWithoutDocker = true)
@Import({ TestcontainersConfiguration.class, PriceHistoryTests.FakeHistoryConfiguration.class })
@SpringBootTest
@AutoConfigureMockMvc
class PriceHistoryTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private FakeHistory history;

	@Autowired
	private JdbcClient jdbc;

	private String token;

	@BeforeEach
	void setUp() throws Exception {
		history.reset();
		jdbc.sql("DELETE FROM price_bars").update();
		jdbc.sql("DELETE FROM price_bar_syncs").update();
		token = newUser();
	}

	@Test
	void firstRequestLoadsFiveYearsThenServesTheRequestedRange() throws Exception {
		history.days = 6 * 365;

		bars("AAPL", "1M").andExpect(status().isOk());

		assertThat(history.calls).isEqualTo(1);
		assertThat(history.lastTimeframe).isEqualTo(BarTimeframe.DAILY);
		assertThat(Duration.between(history.lastStart, Instant.now()).toDays()).isBetween(1820L, 1835L);
		assertThat(history.lastEnd).isBefore(Instant.now().minus(Duration.ofMinutes(15)));
		int month = count(bars("AAPL", "1M"));
		int year = count(bars("AAPL", "1Y"));
		assertThat(month).isBetween(28, 32);
		assertThat(year).isBetween(364, 367);
	}

	@Test
	void barsAreOldestFirstAndLabelledByNewYorkTradingDay() throws Exception {
		history.days = 10;

		String body = bars("AAPL", "1M").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> times = JsonPath.read(body, "$[*].time");

		assertThat(times).isSorted();
		// Fake bars sit at 04:00 UTC, which is midnight in New York, the way Alpaca stamps daily bars.
		assertThat(LocalDate.parse(times.get(times.size() - 1)))
			.isEqualTo(ZonedDateTime.now(PriceHistoryService.MARKET_ZONE).toLocalDate().minusDays(1));
		bars("AAPL", "1M").andExpect(jsonPath("$[0].open").value(100.0))
			.andExpect(jsonPath("$[0].volume").value(1000));
	}

	@Test
	void freshHistoryIsServedWithoutCallingAlpaca() throws Exception {
		history.days = 30;
		bars("AAPL", "1M");
		bars("AAPL", "3M");
		bars("aapl", "1Y");

		assertThat(history.calls).isEqualTo(1);
	}

	@Test
	void staleHistoryRefetchesOnlyTheLastWeek() throws Exception {
		history.days = 30;
		bars("AAPL", "1M");
		jdbc.sql("UPDATE price_bar_syncs SET synced_at = now() - interval '2 hours'").update();

		bars("AAPL", "1M");

		assertThat(history.calls).isEqualTo(2);
		assertThat(Duration.between(history.lastStart, Instant.now()).toDays()).isBetween(7L, 9L);
	}

	@Test
	void refetchingUpdatesBarsInsteadOfDuplicatingThem() throws Exception {
		history.days = 5;
		int before = count(bars("AAPL", "1M"));
		jdbc.sql("UPDATE price_bar_syncs SET synced_at = now() - interval '2 hours'").update();
		history.close = new BigDecimal("150");

		String body = bars("AAPL", "1M").andReturn().getResponse().getContentAsString();

		assertThat(JsonPath.<List<Object>>read(body, "$").size()).isEqualTo(before);
		assertThat(JsonPath.<Double>read(body, "$[-1].close")).isEqualTo(150.0);
	}

	@Test
	void alpacaFailureServesWhatIsStored() throws Exception {
		history.days = 30;
		int stored = count(bars("AAPL", "1M"));
		jdbc.sql("UPDATE price_bar_syncs SET synced_at = now() - interval '2 hours'").update();
		history.fail = true;

		assertThat(count(bars("AAPL", "1M"))).isEqualTo(stored);
	}

	@Test
	void noDataIsAnEmptyListAndRetriesNextTime() throws Exception {
		history.days = 0;
		bars("AAPL", "1Y").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		bars("AAPL", "1Y");

		assertThat(history.calls).isEqualTo(2);
	}

	@Test
	void oneDayShowsFiveMinuteBarsFromTheLatestTradingDayOnly() throws Exception {
		history.intradayHours = 40;

		String body = bars("AAPL", "1D").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> days = JsonPath.read(body, "$[*].time");
		List<Number> timestamps = JsonPath.read(body, "$[*].timestamp");

		assertThat(history.lastTimeframe).isEqualTo(BarTimeframe.FIVE_MINUTES);
		assertThat(Duration.between(history.lastStart, Instant.now()).toDays()).isEqualTo(8L);
		assertThat(Duration.between(history.lastEnd, Instant.now()).toSeconds()).isLessThan(60);
		assertThat(days).isNotEmpty().allMatch(day -> day.equals(days.get(days.size() - 1)));
		assertThat(timestamps.get(1).longValue() - timestamps.get(0).longValue()).isEqualTo(300);
		assertThat(count(bars("AAPL", "5D"))).isGreaterThan(days.size());
	}

	@Test
	void intradayHistoryRefreshesAfterAMinuteWithHalfAnHourOverlap() throws Exception {
		history.intradayHours = 5;
		bars("AAPL", "1D");
		bars("AAPL", "5D");
		assertThat(history.calls).isEqualTo(1);

		jdbc.sql("UPDATE price_bar_syncs SET synced_at = now() - interval '61 seconds' WHERE timeframe = '5Min'")
			.update();
		bars("AAPL", "1D");

		assertThat(history.calls).isEqualTo(2);
		Instant newest = jdbc.sql("SELECT max(ts) FROM price_bars WHERE timeframe = '5Min'")
			.query(java.time.OffsetDateTime.class)
			.single()
			.toInstant();
		assertThat(Duration.between(history.lastStart, newest).toMinutes()).isEqualTo(30);
	}

	@Test
	void dailyAndIntradayHistoryAreKeptApart() throws Exception {
		history.days = 30;
		history.intradayHours = 5;
		bars("AAPL", "1M");
		bars("AAPL", "1D");

		assertThat(history.calls).isEqualTo(2);
		Integer syncs = jdbc.sql("SELECT count(*) FROM price_bar_syncs WHERE symbol = 'AAPL'")
			.query(Integer.class)
			.single();
		assertThat(syncs).isEqualTo(2);
	}

	@Test
	void rejectsBadInput() throws Exception {
		bars("NOPE123", "1Y").andExpect(status().isNotFound());
		bars("AAPL", "2W").andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/market/bars").param("symbol", "AAPL")).andExpect(status().isUnauthorized());
		assertThat(history.calls).isZero();
	}

	@Test
	void hypertableIsInPlace() {
		Integer hypertables = jdbc
			.sql("SELECT count(*) FROM timescaledb_information.hypertables WHERE hypertable_name = 'price_bars'")
			.query(Integer.class)
			.single();
		assertThat(hypertables).isEqualTo(1);
	}

	private ResultActions bars(String symbol, String range) throws Exception {
		return mvc.perform(get("/api/v1/market/bars").param("symbol", symbol)
			.param("range", range)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private static int count(ResultActions result) throws Exception {
		return JsonPath.<List<Object>>read(result.andReturn().getResponse().getContentAsString(), "$").size();
	}

	private String newUser() throws Exception {
		String email = "user-" + UUID.randomUUID() + "@example.com";
		String body = mvc
			.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Test User\",\"email\":\"%s\",\"password\":\"correct-horse-battery\"}".formatted(email)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	/**
	 * Daily bars: one per calendar day up to yesterday, stamped at midnight New York time, like Alpaca.
	 * Five-minute bars: every five minutes over the last {@code intradayHours}.
	 */
	static class FakeHistory implements PriceHistorySource {

		volatile int days;
		volatile int intradayHours;
		volatile int calls;
		volatile boolean fail;
		volatile BigDecimal close;
		volatile BarTimeframe lastTimeframe;
		volatile Instant lastStart;
		volatile Instant lastEnd;

		void reset() {
			days = 0;
			intradayHours = 0;
			calls = 0;
			fail = false;
			close = new BigDecimal("101");
			lastTimeframe = null;
			lastStart = null;
			lastEnd = null;
		}

		@Override
		public List<PriceBar> bars(String symbol, BarTimeframe timeframe, Instant start, Instant end) {
			calls++;
			lastTimeframe = timeframe;
			lastStart = start;
			lastEnd = end;
			if (fail) {
				throw new ResourceAccessException("Alpaca is down");
			}
			List<Instant> times = new ArrayList<>();
			if (timeframe == BarTimeframe.DAILY) {
				ZonedDateTime today = ZonedDateTime.now(PriceHistoryService.MARKET_ZONE).truncatedTo(ChronoUnit.DAYS);
				for (int i = days; i >= 1; i--) {
					times.add(today.minusDays(i).toInstant());
				}
			}
			else {
				Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);
				for (Instant t = now.minus(Duration.ofHours(intradayHours)); t.isBefore(now); t = t.plusSeconds(300)) {
					times.add(t);
				}
			}
			return times.stream()
				.filter(time -> !time.isBefore(start))
				.map(time -> new PriceBar(time, new BigDecimal("100"), new BigDecimal("102"), new BigDecimal("99"),
						close, 1000))
				.toList();
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeHistoryConfiguration {

		@Bean
		@Primary
		FakeHistory fakeHistory() {
			return new FakeHistory();
		}

	}

}
