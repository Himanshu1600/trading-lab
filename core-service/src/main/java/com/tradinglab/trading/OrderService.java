package com.tradinglab.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.tradinglab.marketdata.InstrumentCatalog;
import com.tradinglab.marketdata.MarketQuotes;
import com.tradinglab.marketdata.Quote;
import com.tradinglab.portfolio.PortfolioService;

@Service
@EnableConfigurationProperties(TradingProperties.class)
class OrderService {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private final OrderRepository orders;
	private final FillRepository fills;
	private final PortfolioService portfolio;
	private final InstrumentCatalog instruments;
	private final MarketQuotes quotes;
	private final TradingProperties properties;
	private final TransactionTemplate transaction;

	OrderService(OrderRepository orders, FillRepository fills, PortfolioService portfolio,
			InstrumentCatalog instruments, MarketQuotes quotes, TradingProperties properties,
			TransactionTemplate transaction) {
		this.orders = orders;
		this.fills = fills;
		this.portfolio = portfolio;
		this.instruments = instruments;
		this.quotes = quotes;
		this.properties = properties;
		this.transaction = transaction;
	}

	/**
	 * Checks and prices the order first, which may call Alpaca. Only then does it open the
	 * transaction that saves the order and reserves cash or shares, so no connection waits on the network.
	 */
	public PaperOrder place(UUID userId, String idempotencyKey, OrderController.PlaceOrderRequest request) {
		UUID accountId = portfolio.accountFor(userId);
		var previous = orders.findByAccountIdAndClientOrderId(accountId, idempotencyKey);
		if (previous.isPresent()) {
			return previous.get();
		}
		String symbol = request.symbol().trim().toUpperCase(Locale.ROOT);
		if (!instruments.isTradable(symbol)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown symbol");
		}
		boolean buy = request.side() == PaperOrder.Side.BUY;
		BigDecimal reserved = buy ? cashToReserve(symbol, request) : BigDecimal.ZERO;
		// A refusal throws, which rolls back the order row along with everything else.
		return transaction.execute(status -> {
			PaperOrder order = orders.saveAndFlush(new PaperOrder(accountId, idempotencyKey, symbol, request.side(),
					request.type(), request.qty(), request.limitPrice(), reserved));
			if (buy && !portfolio.reserveCash(accountId, reserved, order.getId())) {
				throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Not enough buying power");
			}
			if (!buy && !portfolio.reserveShares(accountId, symbol, request.qty())) {
				throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
						"You don't have enough shares to sell");
			}
			return order;
		});
	}

	/**
	 * After losing a race on the same key, the winner's order is the answer to both requests.
	 * Not read-only: accountFor() runs an INSERT ... ON CONFLICT, which Postgres refuses in a read-only transaction.
	 */
	@Transactional
	public PaperOrder findPlaced(UUID userId, String idempotencyKey) {
		return orders.findByAccountIdAndClientOrderId(portfolio.accountFor(userId), idempotencyKey).orElseThrow();
	}

	@Transactional
	public void cancel(UUID userId, UUID orderId) {
		UUID accountId = portfolio.accountFor(userId);
		PaperOrder order = orders.findByIdAndAccountId(orderId, accountId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
		int cancelled = orders.transition(orderId, PaperOrder.Status.OPEN, PaperOrder.Status.CANCELLED, null, null,
				Instant.now());
		if (cancelled == 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is no longer open");
		}
		if (order.getSide() == PaperOrder.Side.BUY) {
			portfolio.releaseCash(accountId, order.getReservedCash(), orderId);
		}
		else {
			portfolio.releaseShares(accountId, order.getSymbol(), order.getQty());
		}
	}

	@Transactional
	public List<PaperOrder> list(UUID userId, PaperOrder.Status status) {
		UUID accountId = portfolio.accountFor(userId);
		return (status != null) ? orders.findTop100ByAccountIdAndStatusOrderByCreatedAtDesc(accountId, status)
				: orders.findTop100ByAccountIdOrderByCreatedAtDesc(accountId);
	}

	@Transactional
	public List<Fill> fills(UUID userId) {
		return fills.findTop100ByAccountIdOrderByFilledAtDesc(portfolio.accountFor(userId));
	}

	private BigDecimal cashToReserve(String symbol, OrderController.PlaceOrderRequest request) {
		BigDecimal qty = BigDecimal.valueOf(request.qty());
		if (request.type() == PaperOrder.Type.LIMIT) {
			return request.limitPrice().multiply(qty).setScale(4, RoundingMode.UP);
		}
		Quote quote = quotes.latest(symbol)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"No price is available for this stock right now"));
		BigDecimal buffer = BigDecimal.ONE.add(properties.marketBufferPct().divide(HUNDRED));
		return quote.buyPrice().multiply(buffer).multiply(qty).setScale(4, RoundingMode.UP);
	}

}
