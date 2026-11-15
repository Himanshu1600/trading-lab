package com.tradinglab.ai;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Calls the ai-service over HTTP with the shared service token. The ai-service's own status and
 * message pass through to the browser, so "daily allowance used" reads the same everywhere.
 */
@Component
@EnableConfigurationProperties(AiProperties.class)
class HttpAiAssistant implements AiAssistant {

	private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
	};

	private final RestClient client;
	private final AiProperties properties;

	HttpAiAssistant(RestClient.Builder builder, AiProperties properties) {
		this.properties = properties;
		RestClient.Builder configured = builder.clone();
		if (properties.readTimeout() != null) {
			SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
			factory.setConnectTimeout(Duration.ofSeconds(5));
			factory.setReadTimeout(properties.readTimeout());
			configured.requestFactory(factory);
		}
		this.client = configured
			.baseUrl(properties.serviceUrl())
			.defaultHeader("X-Service-Token", (properties.serviceToken() != null) ? properties.serviceToken() : "")
			.build();
	}

	@Override
	@SuppressWarnings("unchecked")
	public Draft draftStrategy(UUID userId, String prompt, Map<String, Object> previousDsl, String previousError) {
		Map<String, Object> body = new HashMap<>();
		body.put("prompt", prompt);
		body.put("userId", userId.toString());
		body.put("previousDsl", previousDsl);
		body.put("previousError", previousError);
		Map<String, Object> response = post("/v1/strategy/parse", body);
		return new Draft((String) response.get("kind"), (String) response.get("question"),
				(Map<String, Object>) response.get("dsl"),
				(List<String>) response.getOrDefault("assumptions", List.of()));
	}

	@Override
	public String explainBacktest(UUID userId, Map<String, Object> strategy, Map<String, Object> params,
			Map<String, Object> metrics) {
		Map<String, Object> response = post("/v1/backtest/explain",
				Map.of("userId", userId.toString(), "strategy", strategy, "params", params, "metrics", metrics));
		return (String) response.get("explanation");
	}

	@Override
	public String journalInsights(UUID userId, Map<String, Object> stats) {
		Map<String, Object> response = post("/v1/journal/insights",
				Map.of("userId", userId.toString(), "stats", stats));
		return (String) response.get("insights");
	}

	@Override
	public Map<String, Object> askCopilot(UUID userId, String symbol, String question) {
		return post("/v1/copilot/ask", Map.of("userId", userId.toString(), "symbol", symbol, "question", question));
	}

	private Map<String, Object> post(String path, Map<String, Object> body) {
		if (!properties.enabled()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"AI features are off. Set AI_SERVICE_TOKEN and ANTHROPIC_API_KEY to turn them on.");
		}
		try {
			return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JSON_OBJECT);
		}
		catch (RestClientResponseException ex) {
			Map<?, ?> problem = ex.getResponseBodyAs(Map.class);
			Object detail = (problem != null) ? problem.get("detail") : null;
			HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
			// A 401 here means the two services disagree on the token: a setup problem, not the user's.
			if (status == null || status == HttpStatus.UNAUTHORIZED) {
				status = HttpStatus.BAD_GATEWAY;
			}
			throw new ResponseStatusException(status,
					(detail instanceof String text) ? text : "The AI service returned an error.");
		}
		catch (RestClientException ex) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The AI service can't be reached.");
		}
	}

}
