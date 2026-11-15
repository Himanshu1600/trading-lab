package com.tradinglab.realtime;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.tradinglab.portfolio.PortfolioService;
import com.tradinglab.trading.OrderEvent;

/**
 * Tells the order's owner about every status change, once it has committed. If nobody is connected
 * the message is dropped; the page reloads its orders when it reconnects.
 */
@Component
class OrderBroadcaster {

	static final String QUEUE = "/queue/orders";

	private final PortfolioService portfolio;
	private final SimpMessagingTemplate messaging;

	OrderBroadcaster(PortfolioService portfolio, SimpMessagingTemplate messaging) {
		this.portfolio = portfolio;
		this.messaging = messaging;
	}

	@TransactionalEventListener
	void on(OrderEvent event) {
		messaging.convertAndSendToUser(portfolio.ownerOf(event.accountId()).toString(), QUEUE, event);
	}

}
