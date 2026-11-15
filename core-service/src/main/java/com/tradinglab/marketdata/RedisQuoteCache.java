package com.tradinglab.marketdata;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Shares the latest quotes between every core-service instance and the ai-service, under
 * {@code quote:<SYMBOL>} as {@code bid,ask,last,previousClose} with a 2-second expiry. Redis is a
 * cache, never a source of truth: if it's down, a local cache takes over and nothing else changes.
 */
@Component
class RedisQuoteCache implements QuoteCache {

	private static final Logger log = LoggerFactory.getLogger(RedisQuoteCache.class);

	private static final String PREFIX = "quote:";

	private final StringRedisTemplate redis;
	private final QuoteCache.InMemory fallback = new QuoteCache.InMemory(Clock.systemUTC());

	RedisQuoteCache(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public Map<String, Quote> fresh(Collection<String> symbols) {
		List<String> ordered = new ArrayList<>(symbols);
		try {
			List<String> values = redis.opsForValue().multiGet(ordered.stream().map(s -> PREFIX + s).toList());
			Map<String, Quote> result = new HashMap<>();
			for (int i = 0; i < ordered.size(); i++) {
				String value = (values != null) ? values.get(i) : null;
				if (value != null) {
					result.put(ordered.get(i), decode(ordered.get(i), value));
				}
			}
			return result;
		}
		catch (DataAccessException ex) {
			log.debug("Redis unavailable, using the local quote cache: {}", ex.getMessage());
			return fallback.fresh(symbols);
		}
	}

	@Override
	public void store(Map<String, Quote> quotes) {
		fallback.store(quotes);
		try {
			quotes.forEach((symbol, quote) -> redis.opsForValue().set(PREFIX + symbol, encode(quote), TTL));
		}
		catch (DataAccessException ex) {
			log.debug("Redis unavailable, quotes cached locally only: {}", ex.getMessage());
		}
	}

	static String encode(Quote quote) {
		return String.join(",", quote.bid().toPlainString(), quote.ask().toPlainString(),
				quote.last().toPlainString(), quote.previousClose().toPlainString());
	}

	static Quote decode(String symbol, String value) {
		String[] parts = value.split(",");
		return new Quote(symbol, new BigDecimal(parts[0]), new BigDecimal(parts[1]), new BigDecimal(parts[2]),
				new BigDecimal(parts[3]));
	}

}
