package com.tradinglab.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tradinglab.portfolio.PortfolioService;

/** Applies one fill in one transaction: order status, fill record, cash and position together. */
@Service
class OrderFiller {

	private final OrderRepository orders;
	private final FillRepository fills;
	private final PortfolioService portfolio;

	OrderFiller(OrderRepository orders, FillRepository fills, PortfolioService portfolio) {
		this.orders = orders;
		this.fills = fills;
		this.portfolio = portfolio;
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
			orders.transition(orderId, PaperOrder.Status.FILLED, PaperOrder.Status.REJECTED, null,
					"Not enough cash at the fill price", Instant.now());
			portfolio.releaseCash(order.getAccountId(), order.getReservedCash(), orderId);
			return;
		}
		fills.save(new Fill(order, price));
	}

}
