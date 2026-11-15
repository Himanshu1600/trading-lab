package com.tradinglab.journal;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.stereotype.Component;

import com.tradinglab.trading.OrderEvent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Reads order events from Kafka and refreshes the journal after each fill.
 *
 * <p>The consumer group is {@code core-journal}. Kafka gives each partition to one member of the
 * group, and events are keyed by account, so one account's fills are always handled in order by
 * one thread, even with several core-service instances running.
 *
 * <p>If handling throws, the offset isn't committed and Spring retries the message, then moves
 * on after its default of ten attempts. A skipped fill isn't lost for good: the journal also
 * refreshes when it's read.
 */
@Component
class JournalListener {

	private static final Logger log = LoggerFactory.getLogger(JournalListener.class);

	private final JournalService journal;
	private final Counter handled;
	private final Counter duplicates;

	JournalListener(JournalService journal, MeterRegistry meters) {
		this.journal = journal;
		this.handled = meters.counter("journal.events", "result", "handled");
		this.duplicates = meters.counter("journal.events", "result", "duplicate");
	}

	@KafkaListener(topics = OrderEvent.TOPIC)
	void on(OrderEvent event) {
		if (event.kind() != OrderEvent.Kind.FILLED) {
			return;
		}
		if (journal.onFill(event.eventId(), event.accountId())) {
			handled.increment();
		}
		else {
			duplicates.increment();
			log.debug("Ignored repeated event {}", event.eventId());
		}
	}

	/**
	 * Created at startup if it's missing. Three partitions let three consumers share the work;
	 * one replica is all a single local broker can hold.
	 */
	@Bean
	NewTopic orderEventsTopic() {
		return TopicBuilder.name(OrderEvent.TOPIC).partitions(3).replicas(1).build();
	}

}
