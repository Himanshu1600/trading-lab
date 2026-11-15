package com.tradinglab.backtest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/backtests")
class BacktestController {

	private final BacktestService backtests;

	BacktestController(BacktestService backtests) {
		this.backtests = backtests;
	}

	/** Starts a run and answers at once. Results arrive on /user/queue/backtests, or by polling GET. */
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	Map<String, Object> start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RunRequest request) {
		return Map.of("id", backtests.start(userId(jwt), request), "status", "QUEUED");
	}

	@GetMapping
	List<Map<String, Object>> list(@AuthenticationPrincipal Jwt jwt) {
		return backtests.list(userId(jwt));
	}

	@GetMapping("/{id}")
	Map<String, Object> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return backtests.get(userId(jwt), id);
	}

	@GetMapping("/{id}/trades")
	List<Map<String, Object>> trades(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return backtests.trades(userId(jwt), id);
	}

	@GetMapping("/{id}/equity")
	List<Map<String, Object>> equity(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return backtests.equity(userId(jwt), id);
	}

	/** Asks the ai-service to explain a completed run. The answer is stored and reused. */
	@PostMapping("/{id}/explain")
	Map<String, Object> explain(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return backtests.explain(userId(jwt), id);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		backtests.delete(userId(jwt), id);
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	/**
	 * Unset fields take defaults: the last 3 years, $100,000, no fees and 5 basis points of slippage.
	 * Fees and slippage are in basis points (5 = 0.05%) of each trade's value.
	 */
	record RunRequest(@NotNull UUID strategyId, LocalDate from, LocalDate to,
			@DecimalMin("1000") @DecimalMax("1000000000") Double initialCash,
			@DecimalMin("0") @DecimalMax("100") Double feeBps, @DecimalMin("0") @DecimalMax("100") Double slippageBps) {
	}

}
