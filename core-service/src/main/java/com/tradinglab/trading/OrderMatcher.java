package com.tradinglab.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import com.tradinglab.marketdata.Quote;

/** Decides whether an order fills at this quote, and at what price. Pure logic, no database. */
final class OrderMatcher {

	private static final BigDecimal BASIS_POINTS = BigDecimal.valueOf(10_000);

	private OrderMatcher() {
	}

	static Optional<BigDecimal> fillPrice(PaperOrder.Side side, PaperOrder.Type type, BigDecimal limitPrice, Quote quote,
			BigDecimal slippageBps) {
		boolean buy = side == PaperOrder.Side.BUY;
		BigDecimal price = buy ? quote.buyPrice() : quote.sellPrice();
		if (price.signum() <= 0) {
			return Optional.empty();
		}
		if (type == PaperOrder.Type.MARKET) {
			BigDecimal slippage = price.multiply(slippageBps).divide(BASIS_POINTS);
			return Optional.of(money(buy ? price.add(slippage) : price.subtract(slippage)));
		}
		boolean crossed = buy ? price.compareTo(limitPrice) <= 0 : price.compareTo(limitPrice) >= 0;
		return crossed ? Optional.of(money(price)) : Optional.empty();
	}

	private static BigDecimal money(BigDecimal price) {
		return price.setScale(4, RoundingMode.HALF_UP);
	}

}
