package com.tradinglab.portfolio;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class PortfolioController {

	private final PortfolioService portfolio;

	PortfolioController(PortfolioService portfolio) {
		this.portfolio = portfolio;
	}

	@GetMapping("/account")
	PortfolioService.AccountSummary account(@AuthenticationPrincipal Jwt jwt) {
		return portfolio.summary(UUID.fromString(jwt.getSubject()));
	}

	@GetMapping("/positions")
	List<PortfolioService.PositionView> positions(@AuthenticationPrincipal Jwt jwt) {
		return portfolio.positions(UUID.fromString(jwt.getSubject()));
	}

}
