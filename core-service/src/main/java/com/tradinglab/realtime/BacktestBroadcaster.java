package com.tradinglab.realtime;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.tradinglab.backtest.BacktestEvent;

/** Tells a user their backtest finished, once its results are committed and readable. */
@Component
class BacktestBroadcaster {

	static final String QUEUE = "/queue/backtests";

	private final SimpMessagingTemplate messaging;

	BacktestBroadcaster(SimpMessagingTemplate messaging) {
		this.messaging = messaging;
	}

	@TransactionalEventListener
	void on(BacktestEvent event) {
		messaging.convertAndSendToUser(event.userId().toString(), QUEUE, event);
	}

}
