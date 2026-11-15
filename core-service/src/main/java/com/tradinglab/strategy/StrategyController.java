package com.tradinglab.strategy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/strategies")
class StrategyController {

	private final StrategyService strategies;

	StrategyController(StrategyService strategies) {
		this.strategies = strategies;
	}

	@GetMapping
	List<StrategyService.SavedStrategy> list(@AuthenticationPrincipal Jwt jwt) {
		return strategies.list(userId(jwt));
	}

	@GetMapping("/{id}")
	StrategyService.SavedStrategy get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return strategies.get(userId(jwt), id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	StrategyService.SavedStrategy create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SaveRequest request) {
		return strategies.create(userId(jwt), request.dsl(), request.sourcePrompt());
	}

	@PutMapping("/{id}")
	StrategyService.SavedStrategy update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody SaveRequest request) {
		return strategies.update(userId(jwt), id, request.dsl());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		strategies.delete(userId(jwt), id);
	}

	/** Checks a strategy without saving it, for the editor's "Check" button. */
	@PostMapping("/validate")
	Map<String, Object> validate(@Valid @RequestBody SaveRequest request) {
		Strategy strategy = strategies.validate(request.dsl());
		return Map.of("valid", true, "name", strategy.name(), "symbols", strategy.symbols());
	}

	/** 400 with the field path in {@code field}, so the editor can point at the problem. */
	@ExceptionHandler(InvalidStrategyException.class)
	ProblemDetail invalid(InvalidStrategyException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Invalid strategy");
		problem.setProperty("field", ex.path());
		return problem;
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	record SaveRequest(@NotNull Map<String, Object> dsl, String sourcePrompt) {
	}

}
