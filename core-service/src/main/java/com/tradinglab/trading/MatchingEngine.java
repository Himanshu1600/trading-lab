package com.tradinglab.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.tradinglab.marketdata.MarketClock;
import com.tradinglab.marketdata.MarketQuotes;
import com.tradinglab.marketdata.Quote;

/**
 * The only writer of fills. One scheduled loop checks every open order against fresh quotes.
 * Open orders live in the database, so a restart loses nothing.
 */
@Component
class MatchingEngine {

	private static final Logger log = LoggerFactory.getLogger(MatchingEngine.class);

	private final OrderRepository orders;
	private final OrderFiller filler;
	private final OrderService orderService;
	private final MarketQuotes quotes;
	private final MarketClock clock;
	private final TradingProperties properties;

	MatchingEngine(OrderRepository orders, OrderFiller filler, OrderService orderService, MarketQuotes quotes,
			MarketClock clock, TradingProperties properties) {
		this.orders = orders;
		this.filler = filler;
		this.orderService = orderService;
		this.quotes = quotes;
		this.clock = clock;
		this.properties = properties;
	}

	@Scheduled(initialDelayString = "${trading.matching-interval}", fixedDelayString = "${trading.matching-interval}")
	void tick() {
		List<PaperOrder> open = orders.findByStatusOrderByCreatedAt(PaperOrder.Status.OPEN);
		if (open.isEmpty() || !(properties.ignoreMarketHours() || clock.status().open())) {
			return;
		}
		Map<String, Quote> latest = quotes.latest(open.stream().map(PaperOrder::getSymbol).distinct().toList());
		for (PaperOrder order : open) {
			Quote quote = latest.get(order.getSymbol());
			if (quote == null) {
				continue;
			}
			if (order.isWaitingForStop()) {
				if (!OrderMatcher.stopTriggered(order.getSide(), order.getStopPrice(), quote)) {
					continue;
				}
				// A triggered stop may fill on this same tick.
				guarded(order, () -> orderService.trigger(order));
			}
			OrderMatcher
				.fillPrice(order.getSide(), order.executionType(), order.getLimitPrice(), quote,
						properties.slippageBps())
				.ifPresent(price -> fill(order, price));
		}
	}

	/** Runs whether or not the market is open, because DAY orders expire after the close. */
	@Scheduled(initialDelayString = "${trading.matching-interval}", fixedDelayString = "${trading.matching-interval}")
	void expireDayOrders() {
		for (PaperOrder order : orders.findByStatusAndExpiresAtLessThanEqual(PaperOrder.Status.OPEN, Instant.now())) {
			guarded(order, () -> orderService.expire(order.getId()));
		}
	}

	private void fill(PaperOrder order, BigDecimal price) {
		guarded(order, () -> filler.fill(order.getId(), price));
	}

	// One bad order must not stop the others.
	private static void guarded(PaperOrder order, Runnable action) {
		try {
			action.run();
		}
		catch (DataAccessException ex) {
			log.error("Could not process order {}", order.getId(), ex);
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableScheduling
	static class SchedulingConfiguration {

	}

}
