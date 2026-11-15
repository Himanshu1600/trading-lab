package com.tradinglab.realtime;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import com.tradinglab.marketdata.MarketQuotes;
import com.tradinglab.marketdata.Quote;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Pushes quotes only for symbols someone is watching, and only when they change. One loop serves
 * every subscriber, so ten tabs on AAPL cost one quote lookup, which the quote cache usually answers.
 */
@Component
class QuoteBroadcaster {

	static final String TOPIC_PREFIX = "/topic/quotes.";

	private final MarketQuotes quotes;
	private final SimpMessagingTemplate messaging;
	private final Counter sent;
	// symbol -> "sessionId/subscriptionId" of everyone subscribed to it
	private final Map<String, Set<String>> subscribers = new ConcurrentHashMap<>();
	private final Map<String, Quote> lastSent = new ConcurrentHashMap<>();

	QuoteBroadcaster(MarketQuotes quotes, SimpMessagingTemplate messaging, MeterRegistry meters) {
		this.quotes = quotes;
		this.messaging = messaging;
		this.sent = meters.counter("realtime.quotes.sent");
	}

	@Scheduled(fixedDelayString = "${realtime.quote-interval}")
	void broadcast() {
		Set<String> watched = subscribers.entrySet()
			.stream()
			.filter(entry -> !entry.getValue().isEmpty())
			.map(Map.Entry::getKey)
			.collect(java.util.stream.Collectors.toSet());
		if (watched.isEmpty()) {
			return;
		}
		quotes.latest(watched).forEach((symbol, quote) -> {
			if (!quote.equals(lastSent.get(symbol))) {
				messaging.convertAndSend(TOPIC_PREFIX + symbol, quote);
				lastSent.put(symbol, quote);
				sent.increment();
			}
		});
	}

	@EventListener
	void onSubscribe(SessionSubscribeEvent event) {
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
		String destination = accessor.getDestination();
		if (destination != null && destination.startsWith(TOPIC_PREFIX)) {
			String symbol = destination.substring(TOPIC_PREFIX.length());
			subscribers.computeIfAbsent(symbol, key -> ConcurrentHashMap.newKeySet()).add(key(accessor));
			// The next loop resends the current quote, so a new subscriber doesn't wait for a price change.
			lastSent.remove(symbol);
		}
	}

	@EventListener
	void onUnsubscribe(SessionUnsubscribeEvent event) {
		String key = key(StompHeaderAccessor.wrap(event.getMessage()));
		subscribers.values().forEach(keys -> keys.remove(key));
	}

	@EventListener
	void onDisconnect(SessionDisconnectEvent event) {
		String prefix = event.getSessionId() + "/";
		subscribers.values().forEach(keys -> keys.removeIf(key -> key.startsWith(prefix)));
	}

	private static String key(StompHeaderAccessor accessor) {
		return accessor.getSessionId() + "/" + accessor.getSubscriptionId();
	}

}
