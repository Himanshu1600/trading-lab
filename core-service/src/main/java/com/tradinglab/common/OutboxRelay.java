package com.tradinglab.common;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries events that were committed but not delivered, for example because Kafka was down.
 * They wait in {@code event_publication}; once a minute, anything older than a minute is handed
 * to its listener again. Only the listener that failed is retried, not the ones that succeeded.
 */
@Component
@ConditionalOnProperty(name = "spring.modulith.events.externalization.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private static final Duration RETRY_AFTER = Duration.ofMinutes(1);

	private final IncompleteEventPublications publications;

	OutboxRelay(IncompleteEventPublications publications) {
		this.publications = publications;
	}

	@Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
	void resubmit() {
		try {
			publications.resubmitIncompletePublicationsOlderThan(RETRY_AFTER);
		}
		catch (RuntimeException ex) {
			log.warn("Could not resubmit undelivered events: {}", ex.getMessage());
		}
	}

}
