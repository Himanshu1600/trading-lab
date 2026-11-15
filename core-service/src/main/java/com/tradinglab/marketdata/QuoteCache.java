package com.tradinglab.marketdata;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Quotes young enough to reuse instead of asking Alpaca again. */
interface QuoteCache {

	/** Duration a quote stays fresh. Short, because prices move. */
	Duration TTL = Duration.ofSeconds(2);

	/** Only fresh quotes come back; anything missing or stale is left out. */
	Map<String, Quote> fresh(Collection<String> symbols);

	void store(Map<String, Quote> quotes);

	/** Lives in one process. Used by tests, and by {@link RedisQuoteCache} when Redis is down. */
	final class InMemory implements QuoteCache {

		private final Clock clock;
		private final Map<String, Stamped> quotes = new ConcurrentHashMap<>();

		InMemory(Clock clock) {
			this.clock = clock;
		}

		@Override
		public Map<String, Quote> fresh(Collection<String> symbols) {
			Instant now = clock.instant();
			Map<String, Quote> result = new HashMap<>();
			for (String symbol : symbols) {
				Stamped stamped = quotes.get(symbol);
				if (stamped != null && stamped.at().plus(TTL).isAfter(now)) {
					result.put(symbol, stamped.quote());
				}
			}
			return result;
		}

		@Override
		public void store(Map<String, Quote> fetched) {
			Instant now = clock.instant();
			fetched.forEach((symbol, quote) -> quotes.put(symbol, new Stamped(quote, now)));
		}

		private record Stamped(Quote quote, Instant at) {
		}

	}

}
