package com.tradinglab.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.tradinglab.TestcontainersConfiguration;

import tools.jackson.databind.json.JsonMapper;

/** Drafting and explaining through the API, with a scripted stand-in for the ai-service. */
@Testcontainers(disabledWithoutDocker = true)
@Import({ TestcontainersConfiguration.class, AiFlowTests.FakeAiConfiguration.class })
@SpringBootTest
@AutoConfigureMockMvc
class AiFlowTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String VALID = """
			{"dslVersion": 1, "name": "Golden cross", "symbols": ["GOOGL"],
			 "entry": {"crossesAbove": [{"indicator": "SMA", "period": 5}, {"indicator": "SMA", "period": 20}]},
			 "exit": {"crossesBelow": [{"indicator": "SMA", "period": 5}, {"indicator": "SMA", "period": 20}]}}
			""";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private FakeAi ai;

	@Autowired
	private JdbcClient jdbc;

	private String token;

	@BeforeEach
	void setUp() throws Exception {
		ai.drafts.clear();
		ai.requests.clear();
		ai.explanations = 0;
		ai.failure = null;
		token = newUser();
	}

	@Test
	void aValidDraftComesBackForReview() throws Exception {
		ai.drafts.add(new AiAssistant.Draft("strategy", null, dsl(VALID), List.of("Used 5 and 20 days.")));

		draft("Buy Google when the 5-day average crosses above the 20-day").andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("strategy"))
			.andExpect(jsonPath("$.dsl.symbols[0]").value("GOOGL"))
			.andExpect(jsonPath("$.assumptions[0]").value("Used 5 and 20 days."));
		// Nothing is saved until the user saves it.
		perform(get("/api/v1/strategies")).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void aRefusedDraftIsSentBackOnceWithTheValidatorsError() throws Exception {
		ai.drafts.add(new AiAssistant.Draft("strategy", null, dsl(VALID.replace("GOOGL", "ZZZZ")), List.of()));
		ai.drafts.add(new AiAssistant.Draft("strategy", null, dsl(VALID), List.of()));

		draft("Buy ZZZZ on a crossover").andExpect(jsonPath("$.kind").value("strategy"))
			.andExpect(jsonPath("$.dsl.symbols[0]").value("GOOGL"));
		assertThat(ai.requests).hasSize(2);
		assertThat(ai.requests.get(1).previousError()).isEqualTo("symbols[0]: ZZZZ isn't a tradable stock");
	}

	@Test
	void aDraftStillInvalidAfterTheRepairIsShownWithItsError() throws Exception {
		Map<String, Object> bad = dsl(VALID.replace("\"period\": 20", "\"period\": 0"));
		ai.drafts.add(new AiAssistant.Draft("strategy", null, bad, List.of()));
		ai.drafts.add(new AiAssistant.Draft("strategy", null, bad, List.of()));

		draft("something odd").andExpect(jsonPath("$.kind").value("invalid"))
			.andExpect(jsonPath("$.error").value("entry.crossesAbove[1].period: must be a whole number from 1 to 500"))
			.andExpect(jsonPath("$.dsl.name").value("Golden cross"));
	}

	@Test
	void aVagueRequestGetsAQuestion() throws Exception {
		ai.drafts.add(new AiAssistant.Draft("clarification", "Which stocks?", null, List.of()));

		draft("make money").andExpect(jsonPath("$.kind").value("clarification"))
			.andExpect(jsonPath("$.question").value("Which stocks?"));
	}

	@Test
	void anAiOutagePassesThroughAsItsStatus() throws Exception {
		ai.failure = new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI features are off.");

		draft("buy apple").andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.detail").value("AI features are off."));
	}

	@Test
	void anExplanationIsGeneratedOnceAndReused() throws Exception {
		seedPrices("GOOGL");
		String strategyId = id(perform(post("/api/v1/strategies").contentType(MediaType.APPLICATION_JSON)
			.content("{\"dsl\": " + VALID + "}")));
		LocalDate last = LocalDate.now(ZoneId.of("America/New_York")).minusDays(1);
		String runId = id(perform(post("/api/v1/backtests").contentType(MediaType.APPLICATION_JSON)
			.content("{\"strategyId\": \"%s\", \"from\": \"%s\", \"to\": \"%s\"}".formatted(strategyId,
					last.minusDays(99), last))));
		waitForFinish(runId);

		perform(post("/api/v1/backtests/" + runId + "/explain")).andExpect(status().isOk())
			.andExpect(jsonPath("$.explanation").value("Explained."));
		perform(post("/api/v1/backtests/" + runId + "/explain")).andExpect(jsonPath("$.explanation").value("Explained."));
		perform(get("/api/v1/backtests/" + runId)).andExpect(jsonPath("$.explanation").value("Explained."));
		assertThat(ai.explanations).isEqualTo(1);
	}

	@Test
	void copilotQuestionsNeedATradableSymbol() throws Exception {
		perform(post("/api/v1/ai/copilot/ask").contentType(MediaType.APPLICATION_JSON)
			.content("{\"symbol\": \"aapl\", \"question\": \"What are the main risks?\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.symbol").value("AAPL"))
			.andExpect(jsonPath("$.segments[0].text").value("Answered."));
		perform(post("/api/v1/ai/copilot/ask").contentType(MediaType.APPLICATION_JSON)
			.content("{\"symbol\": \"ZZZZ\", \"question\": \"What are the main risks?\"}"))
			.andExpect(status().isNotFound());
	}

	private ResultActions draft(String prompt) throws Exception {
		return perform(post("/api/v1/ai/strategies/draft").contentType(MediaType.APPLICATION_JSON)
			.content("{\"prompt\": \"" + prompt + "\"}"));
	}

	private void waitForFinish(String runId) throws Exception {
		for (int attempt = 0; attempt < 100; attempt++) {
			String body = perform(get("/api/v1/backtests/" + runId)).andReturn().getResponse().getContentAsString();
			if (!List.of("QUEUED", "RUNNING").contains(JsonPath.read(body, "$.status"))) {
				assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("COMPLETED");
				return;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("Backtest didn't finish");
	}

	private void seedPrices(String symbol) {
		ZoneId newYork = ZoneId.of("America/New_York");
		LocalDate last = LocalDate.now(newYork).minusDays(1);
		jdbc.sql("DELETE FROM price_bars WHERE symbol = :symbol").param("symbol", symbol).update();
		for (int i = 0; i < 150; i++) {
			double close = 100 + 10 * Math.sin(i / 10.0);
			jdbc.sql("""
					INSERT INTO price_bars (symbol, timeframe, ts, open, high, low, close, volume)
					VALUES (:symbol, '1Day', :ts, :price, :high, :low, :price, 1000)
					""")
				.param("symbol", symbol)
				.param("ts", last.minusDays(149 - i).atStartOfDay(newYork).toOffsetDateTime())
				.param("price", BigDecimal.valueOf(close))
				.param("high", BigDecimal.valueOf(close + 1))
				.param("low", BigDecimal.valueOf(close - 1))
				.update();
		}
		jdbc.sql("""
				INSERT INTO price_bar_syncs (symbol, timeframe, synced_at) VALUES (:symbol, '1Day', now())
				ON CONFLICT (symbol, timeframe) DO UPDATE SET synced_at = now()
				""").param("symbol", symbol).update();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> dsl(String json) {
		return JSON.readValue(json, Map.class);
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
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	record DraftCall(String prompt, Map<String, Object> previousDsl, String previousError) {
	}

	static class FakeAi implements AiAssistant {

		final List<Draft> drafts = new ArrayList<>();
		final List<DraftCall> requests = new ArrayList<>();
		volatile int explanations;
		volatile ResponseStatusException failure;

		@Override
		public Draft draftStrategy(UUID userId, String prompt, Map<String, Object> previousDsl, String previousError) {
			if (failure != null) {
				throw failure;
			}
			requests.add(new DraftCall(prompt, previousDsl, previousError));
			return drafts.remove(0);
		}

		@Override
		public String explainBacktest(UUID userId, Map<String, Object> strategy, Map<String, Object> params,
				Map<String, Object> metrics) {
			explanations++;
			return "Explained.";
		}

		@Override
		public Map<String, Object> askCopilot(UUID userId, String symbol, String question) {
			return Map.of("symbol", symbol, "segments", List.of(Map.of("text", "Answered.", "citations", List.of())),
					"sources", List.of());
		}

		@Override
		public String journalInsights(UUID userId, Map<String, Object> stats) {
			return "Coached.";
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeAiConfiguration {

		@Bean
		@Primary
		FakeAi fakeAi() {
			return new FakeAi();
		}

	}

}
