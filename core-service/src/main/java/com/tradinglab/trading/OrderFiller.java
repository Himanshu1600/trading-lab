package com.tradinglab.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tradinglab.portfolio.PortfolioService;

import io.micrometer.core.instrument.MeterRegistry;

/** Applies one fill in one transaction: order status, fill record, cash and position together. */
@Service
class OrderFiller {

	private static final String NOT_ENOUGH_CASH = "Not enough cash at the fill price";

	private final OrderRepository orders;
	private final FillRepository fills;
	private final PortfolioService portfolio;
	private final ApplicationEventPublisher events;
	private final MeterRegistry meters;

	OrderFiller(OrderRepository orders, FillRepository fills, PortfolioService portfolio,
			ApplicationEventPublisher events, MeterRegistry meters) {
		this.orders = orders;
		this.fills = fills;
		this.portfolio = portfolio;
		this.events = events;
		this.meters = meters;
	}

	@Transactional
	public void fill(UUID orderId, BigDecimal price) {
		PaperOrder order = orders.findById(orderId).orElseThrow();
		int claimed = orders.transition(orderId, PaperOrder.Status.OPEN, PaperOrder.Status.FILLED, price, null,
				Instant.now());
		if (claimed == 0) {
			return;
		}
		if (order.getSide() == PaperOrder.Side.SELL) {
			portfolio.settleSell(order.getAccountId(), order.getSymbol(), order.getQty(), price, orderId);
		}
		else if (!portfolio.settleBuy(order.getAccountId(), order.getSymbol(), order.getQty(), price,
				order.getReservedCash(), orderId)) {
			orders.transition(orderId, PaperOrder.Status.FILLED, PaperOrder.Status.REJECTED, null, NOT_ENOUGH_CASH,
					Instant.now());
			portfolio.releaseCash(order.getAccountId(), order.getReservedCash(), orderId);
			events.publishEvent(OrderEvent.of(OrderEvent.Kind.REJECTED, order, PaperOrder.Status.REJECTED, null,
					NOT_ENOUGH_CASH));
			meters.counter("trading.rejections").increment();
			return;
		}
		fills.save(new Fill(order, price));
		meters.counter("trading.fills", "side", order.getSide().name()).increment();
		events.publishEvent(OrderEvent.of(OrderEvent.Kind.FILLED, order, PaperOrder.Status.FILLED, price, null));
	}

}
