package com.tradinglab.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class QuoteTests {

	@Test
	void usesBidAndAskNearTheLastTrade() {
		Quote quote = quote("99", "101", "100");

		assertThat(quote.buyPrice()).isEqualByComparingTo("101");
		assertThat(quote.sellPrice()).isEqualByComparingTo("99");
	}

	@Test
	void ignoresABidOrAskFarFromTheLastTrade() {
		// The real case that prompted this: a stale bid of 329.26 with the stock at 344.89.
		Quote quote = quote("329.26", "400", "344.89");

		assertThat(quote.buyPrice()).isEqualByComparingTo("344.89");
		assertThat(quote.sellPrice()).isEqualByComparingTo("344.89");
	}

	@Test
	void keepsPricesExactlyAtTheLimit() {
		Quote quote = quote("98", "102", "100");

		assertThat(quote.buyPrice()).isEqualByComparingTo("102");
		assertThat(quote.sellPrice()).isEqualByComparingTo("98");
	}

	@Test
	void fallsBackToTheLastTradeWhenBidAndAskAreEmpty() {
		Quote quote = quote("0", "0", "50");

		assertThat(quote.buyPrice()).isEqualByComparingTo("50");
		assertThat(quote.sellPrice()).isEqualByComparingTo("50");
	}

	@Test
	void usesBidAndAskWhenThereIsNoLastTrade() {
		Quote quote = quote("99", "101", "0");

		assertThat(quote.buyPrice()).isEqualByComparingTo("101");
		assertThat(quote.sellPrice()).isEqualByComparingTo("99");
		assertThat(quote.markPrice()).isEqualByComparingTo("101");
	}

	private static Quote quote(String bid, String ask, String last) {
		return new Quote("TEST", new BigDecimal(bid), new BigDecimal(ask), new BigDecimal(last), BigDecimal.ZERO);
	}

}
