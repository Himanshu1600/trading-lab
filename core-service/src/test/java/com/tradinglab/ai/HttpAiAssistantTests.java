package com.tradinglab.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class HttpAiAssistantTests {

	private static final UUID USER = UUID.randomUUID();

	private final RestClient.Builder builder = RestClient.builder();

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	private final HttpAiAssistant ai = new HttpAiAssistant(builder,
			new AiProperties("http://ai.test", "secret-token", null));

	@Test
	void sendsTheServiceTokenAndReadsTheDraft() {
		server.expect(requestTo("http://ai.test/v1/strategy/parse"))
			.andExpect(header("X-Service-Token", "secret-token"))
			.andExpect(jsonPath("$.prompt").value("buy apple on a golden cross"))
			.andExpect(jsonPath("$.userId").value(USER.toString()))
			.andRespond(withSuccess("""
					{"kind": "strategy", "dsl": {"name": "x"}, "assumptions": ["Used SMA(50) and SMA(200)."]}
					""", MediaType.APPLICATION_JSON));

		AiAssistant.Draft draft = ai.draftStrategy(USER, "buy apple on a golden cross", null, null);

		assertThat(draft.kind()).isEqualTo("strategy");
		assertThat(draft.dsl()).containsEntry("name", "x");
		assertThat(draft.assumptions()).containsExactly("Used SMA(50) and SMA(200).");
		server.verify();
	}

	@Test
	void theAiServicesStatusAndMessagePassThrough() {
		server.expect(requestTo("http://ai.test/v1/backtest/explain"))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
				.body("{\"detail\": \"Daily AI allowance of 200,000 tokens used.\"}"));

		assertThatThrownBy(() -> ai.explainBacktest(USER, Map.of(), Map.of(), Map.of()))
			.isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
				assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
				assertThat(ex.getReason()).isEqualTo("Daily AI allowance of 200,000 tokens used.");
			});
	}

	@Test
	void aTokenMismatchIsReportedAsASetupProblem() {
		server.expect(requestTo("http://ai.test/v1/strategy/parse"))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
				.body("{\"detail\": \"Missing or wrong service token.\"}"));

		assertThatThrownBy(() -> ai.draftStrategy(USER, "x", null, null)).isInstanceOfSatisfying(
				ResponseStatusException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
	}

	@Test
	void withoutATokenAiIsOffAndNothingIsSent() {
		HttpAiAssistant off = new HttpAiAssistant(RestClient.builder(), new AiProperties("http://ai.test", "", null));

		assertThatThrownBy(() -> off.draftStrategy(USER, "x", null, null)).isInstanceOfSatisfying(
				ResponseStatusException.class,
				ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
	}

}
