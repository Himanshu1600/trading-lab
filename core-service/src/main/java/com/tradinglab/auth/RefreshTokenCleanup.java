package com.tradinglab.auth;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes refresh tokens a day after they expire, once a day, so the table doesn't grow forever.
 * The extra day keeps replay detection working for a token that expired moments ago.
 */
@Component
class RefreshTokenCleanup {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanup.class);

	private final RefreshTokenRepository refreshTokens;

	RefreshTokenCleanup(RefreshTokenRepository refreshTokens) {
		this.refreshTokens = refreshTokens;
	}

	@Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT24H")
	@Transactional
	public void deleteExpired() {
		int deleted = refreshTokens.deleteExpiredBefore(Instant.now().minus(Duration.ofDays(1)));
		if (deleted > 0) {
			log.info("Deleted {} expired refresh tokens", deleted);
		}
	}

}
