package com.tradinglab.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.tradinglab.marketdata.Quote;
import com.tradinglab.trading.PaperOrder.Side;
import com.tradinglab.trading.PaperOrder.Type;

class OrderMatcherTests {

	private static final BigDecimal SLIPPAGE = BigDecimal.valueOf(5);

	private final Quote quote = new Quote("AAPL", new BigDecimal("99"), new BigDecimal("100"), new BigDecimal("99.5"),
			BigDecimal.ZERO);

	@Test
	void marketOrdersFillAtTheQuoteWithSlippage() {
		assertThat(price(Side.BUY, Type.MARKET, null)).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("100.05"));
		assertThat(price(Side.SELL, Type.MARKET, null)).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("98.9505"));
	}

	@Test
	void buyLimitFillsOnlyAtOrBelowItsPrice() {
		assertThat(price(Side.BUY, Type.LIMIT, "99.99")).isEmpty();
		assertThat(price(Side.BUY, Type.LIMIT, "100")).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("100"));
		assertThat(price(Side.BUY, Type.LIMIT, "105")).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("100"));
	}

	@Test
	void sellLimitFillsOnlyAtOrAboveItsPrice() {
		assertThat(price(Side.SELL, Type.LIMIT, "99.01")).isEmpty();
		assertThat(price(Side.SELL, Type.LIMIT, "99")).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("99"));
		assertThat(price(Side.SELL, Type.LIMIT, "90")).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("99"));
	}

	@Test
	void noPriceMeansNoFill() {
		Quote empty = new Quote("AAPL", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
		assertThat(OrderMatcher.fillPrice(Side.BUY, Type.MARKET, null, empty, SLIPPAGE)).isEmpty();
	}

	private Optional<BigDecimal> price(Side side, Type type, String limit) {
		return OrderMatcher.fillPrice(side, type, (limit != null) ? new BigDecimal(limit) : null, quote, SLIPPAGE);
	}

}
