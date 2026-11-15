package com.tradinglab.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.modulith.events.Externalized;

/**
 * Published inside the transaction that changes an order. Listeners that use
 * {@code @TransactionalEventListener} only see it once that change has committed.
 *
 * <p>It is also sent to the Kafka topic {@value #TOPIC}, keyed by account, so one account's events
 * stay in order on one partition. Spring Modulith stores the event in {@code event_publication}
 * in the same transaction and sends it after commit (the transactional outbox pattern): an order
 * change and its event can't be separated by a crash. Delivery is at-least-once, so consumers use
 * {@code eventId} to ignore a repeat.
 */
@Externalized(OrderEvent.TOPIC + "::#{#this.accountId().toString()}")
public record OrderEvent(UUID eventId, Kind kind, UUID orderId, UUID accountId, String symbol, String side,
		String type, long qty, String status, BigDecimal fillPrice, String reason, Instant at) {

	public static final String TOPIC = "trading.order-events";

	public enum Kind {
		PLACED, TRIGGERED, FILLED, REJECTED, CANCELLED, EXPIRED
	}

	static OrderEvent of(Kind kind, PaperOrder order, PaperOrder.Status status, BigDecimal fillPrice, String reason) {
		return new OrderEvent(UUID.randomUUID(), kind, order.getId(), order.getAccountId(), order.getSymbol(),
				order.getSide().name(), order.getType().name(), order.getQty(), status.name(), fillPrice, reason,
				Instant.now());
	}

}
