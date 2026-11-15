package com.tradinglab.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.tradinglab.marketdata.InstrumentCatalog;
import com.tradinglab.marketdata.MarketClock;
import com.tradinglab.marketdata.MarketQuotes;
import com.tradinglab.marketdata.Quote;
import com.tradinglab.portfolio.PortfolioService;

@Service
@EnableConfigurationProperties(TradingProperties.class)
class OrderService {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
	private static final LocalTime REGULAR_CLOSE = LocalTime.of(16, 0);
	private static final String EXPIRED_AT_CLOSE = "Day order expired at the close";

	private final OrderRepository orders;
	private final FillRepository fills;
	private final PortfolioService portfolio;
	private final InstrumentCatalog instruments;
	private final MarketQuotes quotes;
	private final MarketClock clock;
	private final TradingProperties properties;
	private final TransactionTemplate transaction;
	private final ApplicationEventPublisher events;

	OrderService(OrderRepository orders, FillRepository fills, PortfolioService portfolio,
			InstrumentCatalog instruments, MarketQuotes quotes, MarketClock clock, TradingProperties properties,
			TransactionTemplate transaction, ApplicationEventPublisher events) {
		this.orders = orders;
		this.fills = fills;
		this.portfolio = portfolio;
		this.instruments = instruments;
		this.quotes = quotes;
		this.clock = clock;
		this.properties = properties;
		this.transaction = transaction;
		this.events = events;
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
		PaperOrder.TimeInForce timeInForce = request.timeInForceOrDefault();
		Instant expiresAt = (timeInForce == PaperOrder.TimeInForce.DAY) ? sessionClose(Instant.now()) : null;
		// A refusal throws, which rolls back the order row along with everything else.
		return transaction.execute(status -> {
			PaperOrder order = orders.saveAndFlush(new PaperOrder(accountId, idempotencyKey, symbol, request.side(),
					request.type(), request.qty(), request.limitPrice(), request.stopPrice(), timeInForce, expiresAt,
					reserved));
			if (buy && !portfolio.reserveCash(accountId, reserved, order.getId())) {
				throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Not enough buying power");
			}
			if (!buy && !portfolio.reserveShares(accountId, symbol, request.qty())) {
				throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
						"You don't have enough shares to sell");
			}
			events.publishEvent(OrderEvent.of(OrderEvent.Kind.PLACED, order, PaperOrder.Status.OPEN, null, null));
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
		if (!close(order, PaperOrder.Status.CANCELLED, OrderEvent.Kind.CANCELLED, null)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is no longer open");
		}
	}

	/** Closes a DAY order whose session has ended. Does nothing if it filled or was cancelled first. */
	@Transactional
	public void expire(UUID orderId) {
		orders.findById(orderId)
			.ifPresent(order -> close(order, PaperOrder.Status.EXPIRED, OrderEvent.Kind.EXPIRED, EXPIRED_AT_CLOSE));
	}

	/** Marks a stop order as triggered. From then on it fills like a market or limit order. */
	@Transactional
	public void trigger(PaperOrder order) {
		if (orders.markTriggered(order.getId(), Instant.now()) == 1) {
			events.publishEvent(OrderEvent.of(OrderEvent.Kind.TRIGGERED, order, PaperOrder.Status.OPEN, null, null));
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

	/** Moves an open order to a final status and hands back what it had set aside. False if it wasn't open. */
	private boolean close(PaperOrder order, PaperOrder.Status to, OrderEvent.Kind kind, String reason) {
		int closed = orders.transition(order.getId(), PaperOrder.Status.OPEN, to, null, reason, Instant.now());
		if (closed == 0) {
			return false;
		}
		if (order.getSide() == PaperOrder.Side.BUY) {
			portfolio.releaseCash(order.getAccountId(), order.getReservedCash(), order.getId());
		}
		else {
			portfolio.releaseShares(order.getAccountId(), order.getSymbol(), order.getQty());
		}
		events.publishEvent(OrderEvent.of(kind, order, to, null, reason));
		return true;
	}

	/**
	 * The close of the session a DAY order belongs to: today's close while the market is open, otherwise
	 * the next session's. Without a usable clock reading it falls back to 4 PM New York on the next
	 * weekday, which ignores holidays and early closes.
	 */
	private Instant sessionClose(Instant now) {
		Instant nextClose = clock.status().nextClose();
		return (nextClose != null && nextClose.isAfter(now)) ? nextClose : regularClose(now);
	}

	static Instant regularClose(Instant now) {
		ZonedDateTime close = now.atZone(NEW_YORK).with(REGULAR_CLOSE);
		if (!close.toInstant().isAfter(now)) {
			close = close.plusDays(1);
		}
		while (close.getDayOfWeek() == DayOfWeek.SATURDAY || close.getDayOfWeek() == DayOfWeek.SUNDAY) {
			close = close.plusDays(1);
		}
		return close.toInstant();
	}

	private BigDecimal cashToReserve(String symbol, OrderController.PlaceOrderRequest request) {
		BigDecimal qty = BigDecimal.valueOf(request.qty());
		if (request.type().hasLimitPrice()) {
			return request.limitPrice().multiply(qty).setScale(4, RoundingMode.UP);
		}
		// A stop becomes a market order near its stop price, so the stop price stands in for the quote.
		BigDecimal price = (request.type() == PaperOrder.Type.STOP) ? request.stopPrice()
				: quotes.latest(symbol)
					.map(Quote::buyPrice)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
							"No price is available for this stock right now"));
		BigDecimal buffer = BigDecimal.ONE.add(properties.marketBufferPct().divide(HUNDRED));
		return price.multiply(buffer).multiply(qty).setScale(4, RoundingMode.UP);
	}

}
