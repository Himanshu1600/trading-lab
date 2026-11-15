package com.tradinglab.ai;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ai-service, as other modules see it. Every method can throw a
 * {@code ResponseStatusException} carrying the ai-service's status and message: 503 when AI is
 * off or unreachable, 429 when the user's daily allowance is used, 422 when the model declined,
 * and 502 when it couldn't produce a usable answer.
 */
public interface AiAssistant {

	/**
	 * Turns a description into strategy JSON, or a question back. {@code previousDsl} and
	 * {@code previousError} ask for a repair of a draft the validator refused.
	 */
	Draft draftStrategy(UUID userId, String prompt, Map<String, Object> previousDsl, String previousError);

	/** A short explanation of a finished backtest, using only the numbers given. */
	String explainBacktest(UUID userId, Map<String, Object> strategy, Map<String, Object> params,
			Map<String, Object> metrics);

	/** Short observations about trading behaviour, written from the journal's statistics only. */
	String journalInsights(UUID userId, Map<String, Object> stats);

	/**
	 * Answers a question about a company from its latest SEC filings, with numbered citations:
	 * {@code segments} (text with the sources it relies on) and {@code sources} (filing, section, link).
	 */
	Map<String, Object> askCopilot(UUID userId, String symbol, String question);

	/** What's switched on. Never throws: an unreachable ai-service just reports as off. */
	default Status status() {
		return new Status(true, true, true, true);
	}

	/**
	 * @param serviceConfigured AI_SERVICE_TOKEN is set in core-service
	 * @param serviceReachable the ai-service answered
	 * @param claude the ai-service has an Anthropic API key
	 * @param research the copilot can also reach SEC EDGAR (SEC_USER_AGENT is set)
	 */
	record Status(boolean serviceConfigured, boolean serviceReachable, boolean claude, boolean research) {
	}

	/** {@code kind} is "strategy" (with {@code dsl}) or "clarification" (with {@code question}). */
	record Draft(String kind, String question, Map<String, Object> dsl, List<String> assumptions) {
	}

}
