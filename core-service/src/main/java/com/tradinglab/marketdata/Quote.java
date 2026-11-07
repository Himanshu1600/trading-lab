package com.tradinglab.marketdata;

import java.math.BigDecimal;
import java.math.MathContext;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Latest prices for one symbol. A price Alpaca didn't send is zero, never null.
 *
 * <p>The free IEX feed is thin, so a bid or ask can sit far from the real price, especially
 * outside market hours. One that strays too far from the last trade is ignored.
 */
public record Quote(String symbol, BigDecimal bid, BigDecimal ask, BigDecimal last, BigDecimal previousClose) {

	private static final BigDecimal MAX_DISTANCE_FROM_LAST = new BigDecimal("0.02");

	/** What a buyer pays. */
	@JsonProperty
	public BigDecimal buyPrice() {
		return isUsable(ask) ? ask : last;
	}

	/** What a seller receives. */
	@JsonProperty
	public BigDecimal sellPrice() {
		return isUsable(bid) ? bid : last;
	}

	/** The best single price for valuing a holding. */
	public BigDecimal markPrice() {
		return (last.signum() > 0) ? last : buyPrice();
	}

	private boolean isUsable(BigDecimal price) {
		if (price.signum() <= 0) {
			return false;
		}
		if (last.signum() <= 0) {
			return true;
		}
		BigDecimal distance = price.subtract(last).abs().divide(last, MathContext.DECIMAL64);
		return distance.compareTo(MAX_DISTANCE_FROM_LAST) <= 0;
	}

}
