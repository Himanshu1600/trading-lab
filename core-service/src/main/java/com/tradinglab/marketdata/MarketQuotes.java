package com.tradinglab.marketdata;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface MarketQuotes {

	/** Symbols with no price available are left out of the result. */
	Map<String, Quote> latest(Collection<String> symbols);

	default Optional<Quote> latest(String symbol) {
		return Optional.ofNullable(latest(List.of(symbol)).get(symbol));
	}

}
