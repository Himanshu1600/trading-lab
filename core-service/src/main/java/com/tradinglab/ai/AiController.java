package com.tradinglab.ai;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.tradinglab.marketdata.InstrumentCatalog;
import com.tradinglab.strategy.InvalidStrategyException;
import com.tradinglab.strategy.StrategyService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/ai")
class AiController {

	/** The first draft, plus one repair if the validator refuses it. */
	static final int MAX_ATTEMPTS = 2;

	private final AiAssistant ai;
	private final StrategyService strategies;
	private final InstrumentCatalog instruments;

	AiController(AiAssistant ai, StrategyService strategies, InstrumentCatalog instruments) {
		this.ai = ai;
		this.strategies = strategies;
		this.instruments = instruments;
	}

	/**
	 * Answers a question about a stock from its SEC filings, with citations. The first question
	 * about a stock downloads its latest 10-K and 10-Q, so it takes longer.
	 */
	@PostMapping("/copilot/ask")
	Map<String, Object> ask(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AskRequest request) {
		String symbol = request.symbol().trim().toUpperCase(Locale.ROOT);
		if (!instruments.isTradable(symbol)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown symbol");
		}
		return ai.askCopilot(UUID.fromString(jwt.getSubject()), symbol, request.question().trim());
	}

	/**
	 * Drafts a strategy from a description. Nothing is saved: the draft goes into the editor, where
	 * the user reviews it. A draft that still fails validation after a repair comes back as
	 * {@code kind: "invalid"} with the error, so the user can fix the last detail by hand.
	 */
	@PostMapping("/strategies/draft")
	Map<String, Object> draft(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DraftRequest request) {
		UUID userId = UUID.fromString(jwt.getSubject());
		Map<String, Object> previousDsl = null;
		String previousError = null;
		AiAssistant.Draft draft = null;
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			draft = ai.draftStrategy(userId, request.prompt(), previousDsl, previousError);
			if (!"strategy".equals(draft.kind()) || draft.dsl() == null) {
				return response("clarification", draft, null);
			}
			try {
				strategies.validate(draft.dsl());
				return response("strategy", draft, null);
			}
			catch (InvalidStrategyException ex) {
				previousDsl = draft.dsl();
				previousError = ex.getMessage();
			}
		}
		return response("invalid", draft, previousError);
	}

	private static Map<String, Object> response(String kind, AiAssistant.Draft draft, String error) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("kind", kind);
		body.put("question", draft.question());
		body.put("dsl", draft.dsl());
		body.put("assumptions", draft.assumptions());
		body.put("error", error);
		return body;
	}

	record AskRequest(@NotBlank @Size(max = 10) String symbol, @NotBlank @Size(min = 3, max = 1000) String question) {
	}

	record DraftRequest(@NotBlank @Size(max = 2000) String prompt) {
	}

}
