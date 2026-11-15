package com.tradinglab.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.tradinglab.TestcontainersConfiguration;

/**
 * Strategies and backtests through the API, with real Postgres. Prices are written straight into
 * {@code price_bars} and marked freshly synced, so no request goes to Alpaca.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BacktestFlowTests {

	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
	private static final String SYMBOL = "MSFT";

	private static final String CROSSOVER = """
			{"dslVersion": 1, "name": "5/20 crossover", "symbols": ["MSFT"],
			 "entry": {"crossesAbove": [{"indicator": "SMA", "period": 5}, {"indicator": "SMA", "period": 20}]},
			 "exit": {"crossesBelow": [{"indicator": "SMA", "period": 5}, {"indicator": "SMA", "period": 20}]},
			 "risk": {"stopLossPct": 10},
			 "sizing": {"type": "percentOfEquity", "value": 50}}
			""";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	private String token;

	private LocalDate lastDay;

	@BeforeEach
	void setUp() throws Exception {
		token = newUser();
		lastDay = LocalDate.now(NEW_YORK).minusDays(1);
		seedPrices();
	}

	@Test
	void savesAStrategyRunsABacktestAndServesTheResults() throws Exception {
		String strategyId = id(perform(post("/api/v1/strategies").contentType(MediaType.APPLICATION_JSON)
			.content("{\"dsl\": " + CROSSOVER + "}")).andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("5/20 crossover")));

		String runId = id(startRun(strategyId).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("QUEUED")));
		String run = waitForFinish(runId);

		assertThat((String) JsonPath.read(run, "$.status")).isEqualTo("COMPLETED");
		assertThat((Integer) JsonPath.read(run, "$.metrics.strategy.trades")).isPositive();
		assertThat((Integer) JsonPath.read(run, "$.metrics.strategy.tradingDays")).isEqualTo(200);
		assertThat((String) JsonPath.read(run, "$.inputHash")).hasSize(64);
		assertThat((String) JsonPath.read(run, "$.dsl.name")).isEqualTo("5/20 crossover");

		perform(get("/api/v1/backtests/" + runId + "/equity")).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(200))
			.andExpect(jsonPath("$[0].equity").value(100000.0));
		perform(get("/api/v1/backtests/" + runId + "/trades")).andExpect(status().isOk())
			.andExpect(jsonPath("$[0].symbol").value(SYMBOL));
		perform(get("/api/v1/backtests")).andExpect(jsonPath("$[0].id").value(runId));

		// Equal inputs, equal fingerprint: rerunning gives an identical run.
		String rerun = waitForFinish(id(startRun(strategyId)));
		assertThat((String) JsonPath.read(rerun, "$.inputHash")).isEqualTo(JsonPath.read(run, "$.inputHash"));
		assertThat((Object) JsonPath.read(rerun, "$.metrics")).isEqualTo(JsonPath.read(run, "$.metrics"));
	}

	@Test
	void editingAStrategyLeavesOldRunsUnchanged() throws Exception {
		String strategyId = id(perform(post("/api/v1/strategies").contentType(MediaType.APPLICATION_JSON)
			.content("{\"dsl\": " + CROSSOVER + "}")));
		String runId = id(startRun(strategyId));
		waitForFinish(runId);

		perform(put("/api/v1/strategies/" + strategyId).contentType(MediaType.APPLICATION_JSON)
			.content("{\"dsl\": " + CROSSOVER.replace("5/20 crossover", "Renamed") + "}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Renamed"));

		perform(get("/api/v1/backtests/" + runId)).andExpect(jsonPath("$.strategyName").value("5/20 crossover"))
			.andExpect(jsonPath("$.dsl.name").value("5/20 crossover"));
	}

	@Test
	void invalidStrategiesAreRefusedWithTheField() throws Exception {
		perform(post("/api/v1/strategies").contentType(MediaType.APPLICATION_JSON)
			.content("{\"dsl\": " + CROSSOVER.replace("\"period\": 20", "\"period\": 0") + "}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("entry.crossesAbove[1].period"));
		perform(post("/api/v1/strategies/validate").contentType(MediaType.APPLICATION_JSON)
			.content("{\"dsl\": " + CROSSOVER.replace("MSFT", "NOTREAL") + "}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("symbols[0]"));
		perform(post("/api/v1/strategies/validate").contentType(MediaType.APPLICATION_JSON)
			.content("{\"dsl\": " + CROSSOVER + "}")).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
	}

	@Test
	void aRunWithNoPricesFailsWithAReason() throws Exception {
		String strategyId = id(perform(post("/api/v1/strategies").contentType(MediaType.APPLICATION_JSON)
			.content("{\"dsl\": " + CROSSOVER.replace("MSFT", "NFLX") + "}")));
		String run = waitForFinish(id(startRun(strategyId)));
		assertThat((String) JsonPath.read(run, "$.status")).isEqualTo("FAILED");
		assertThat((String) JsonPath.read(run, "$.error")).contains("No price history");
	}

	@Test
	void otherUsersCantSeeStrategiesOrRuns() throws Exception {
		String strategyId = id(perform(post("/api/v1/strategies").contentType(MediaType.APPLICATION_JSON)
			.content("{\"dsl\": " + CROSSOVER + "}")));
		String runId = id(startRun(strategyId));
		waitForFinish(runId);

		String stranger = newUser();
		mvc.perform(get("/api/v1/strategies/" + strategyId).header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/backtests/" + runId).header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger))
			.andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/backtests").header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"strategyId\": \"" + strategyId + "\"}")).andExpect(status().isNotFound());
	}

	@Test
	void finishedRunsCanBeDeleted() throws Exception {
		String strategyId = id(perform(post("/api/v1/strategies").contentType(MediaType.APPLICATION_JSON)
			.content("{\"dsl\": " + CROSSOVER + "}")));
		String runId = id(startRun(strategyId));
		waitForFinish(runId);
		perform(delete("/api/v1/backtests/" + runId)).andExpect(status().isNoContent());
		perform(get("/api/v1/backtests/" + runId)).andExpect(status().isNotFound());
	}

	@Test
	void datesAreChecked() throws Exception {
		String strategyId = id(perform(post("/api/v1/strategies").contentType(MediaType.APPLICATION_JSON)
			.content("{\"dsl\": " + CROSSOVER + "}")));
		perform(post("/api/v1/backtests").contentType(MediaType.APPLICATION_JSON)
			.content("{\"strategyId\": \"%s\", \"from\": \"%s\", \"to\": \"%s\"}".formatted(strategyId, lastDay,
					lastDay.minusDays(10))))
			.andExpect(status().isBadRequest());
		perform(post("/api/v1/backtests").contentType(MediaType.APPLICATION_JSON)
			.content("{\"strategyId\": \"%s\", \"from\": \"%s\"}".formatted(strategyId, lastDay.minusYears(6))))
			.andExpect(status().isBadRequest());
	}

	private ResultActions startRun(String strategyId) throws Exception {
		return perform(post("/api/v1/backtests").contentType(MediaType.APPLICATION_JSON)
			.content("{\"strategyId\": \"%s\", \"from\": \"%s\", \"to\": \"%s\"}".formatted(strategyId,
					lastDay.minusDays(199), lastDay)));
	}

	private String waitForFinish(String runId) throws Exception {
		for (int attempt = 0; attempt < 100; attempt++) {
			String body = perform(get("/api/v1/backtests/" + runId)).andReturn().getResponse().getContentAsString();
			String status = JsonPath.read(body, "$.status");
			if (status.equals("COMPLETED") || status.equals("FAILED")) {
				return body;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("Backtest " + runId + " didn't finish in 10 seconds");
	}

	/**
	 * 260 consecutive days of a slow sine wave, so short and long averages cross several times.
	 * The first 60 are warm-up before the 200-day test window.
	 */
	private void seedPrices() {
		jdbc.sql("DELETE FROM price_bars WHERE symbol = :symbol").param("symbol", SYMBOL).update();
		for (int i = 0; i < 260; i++) {
			LocalDate day = lastDay.minusDays(259 - i);
			double close = 100 + 10 * Math.sin(i / 15.0);
			double open = close - 0.5;
			jdbc.sql("""
					INSERT INTO price_bars (symbol, timeframe, ts, open, high, low, close, volume)
					VALUES (:symbol, '1Day', :ts, :open, :high, :low, :close, 1000)
					""")
				.param("symbol", SYMBOL)
				.param("ts", day.atStartOfDay(NEW_YORK).toOffsetDateTime())
				.param("open", BigDecimal.valueOf(open))
				.param("high", BigDecimal.valueOf(close + 1))
				.param("low", BigDecimal.valueOf(open - 1))
				.param("close", BigDecimal.valueOf(close))
				.update();
		}
		jdbc.sql("""
				INSERT INTO price_bar_syncs (symbol, timeframe, synced_at) VALUES (:symbol, '1Day', now())
				ON CONFLICT (symbol, timeframe) DO UPDATE SET synced_at = now()
				""").param("symbol", SYMBOL).update();
	}

	private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private static String id(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private String newUser() throws Exception {
		String body = mvc
			.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Test User\",\"email\":\"user-%s@example.com\",\"password\":\"correct-horse-battery\"}"
					.formatted(UUID.randomUUID())))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

}
