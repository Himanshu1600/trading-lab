package com.tradinglab.marketdata;

import java.util.List;
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

import com.tradinglab.marketdata.WatchlistService.WatchlistResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/watchlists")
class WatchlistController {

	private final WatchlistService watchlistService;

	WatchlistController(WatchlistService watchlistService) {
		this.watchlistService = watchlistService;
	}

	@GetMapping
	List<WatchlistResponse> list(@AuthenticationPrincipal Jwt jwt) {
		return watchlistService.list(userId(jwt));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	WatchlistResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest request) {
		return watchlistService.create(userId(jwt), request.name());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		watchlistService.delete(userId(jwt), id);
	}

	@PostMapping("/{id}/items/{symbol}")
	WatchlistResponse addSymbol(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable String symbol) {
		return watchlistService.addSymbol(userId(jwt), id, symbol);
	}

	@DeleteMapping("/{id}/items/{symbol}")
	WatchlistResponse removeSymbol(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@PathVariable String symbol) {
		return watchlistService.removeSymbol(userId(jwt), id, symbol);
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	record CreateRequest(@NotBlank @Size(max = 50) String name) {
	}

}
