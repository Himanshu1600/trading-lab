package com.tradinglab.journal;

import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/journal")
class JournalController {

	private final JournalService journal;

	JournalController(JournalService journal) {
		this.journal = journal;
	}

	/** Statistics from the user's fills, and the coach's notes if there are any. */
	@GetMapping
	Map<String, Object> journal(@AuthenticationPrincipal Jwt jwt) {
		return journal.journal(UUID.fromString(jwt.getSubject()));
	}

	/** Asks the AI coach for notes. They're kept until the next fill, so asking again is free. */
	@PostMapping("/insights")
	Map<String, Object> insights(@AuthenticationPrincipal Jwt jwt) {
		return journal.coach(UUID.fromString(jwt.getSubject()));
	}

}
