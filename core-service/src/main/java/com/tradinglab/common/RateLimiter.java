package com.tradinglab.common;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Counts requests per key in fixed windows, in Redis, so every core-service instance shares the
 * count. {@code INCR} is atomic, so two requests at the same moment can't both take the last slot.
 *
 * <p>A fixed window allows a short burst across a window boundary, up to twice the limit. That's
 * acceptable here: the limits stop abuse, they aren't billing. If Redis is down, requests are let
 * through, because an outage of the limiter shouldn't take the app down with it.
 */
@Component
public class RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

	private final StringRedisTemplate redis;

	RateLimiter(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/**
	 * @return zero if the request may go ahead, otherwise the seconds until the window resets
	 */
	public long secondsUntilAllowed(String key, int limit, Duration window) {
		long windowSeconds = window.toSeconds();
		long now = System.currentTimeMillis() / 1000;
		String windowKey = "rate:" + key + ":" + (now / windowSeconds);
		try {
			Long count = redis.opsForValue().increment(windowKey);
			if (count != null && count == 1) {
				redis.expire(windowKey, window.plusSeconds(1));
			}
			if (count != null && count > limit) {
				return windowSeconds - (now % windowSeconds);
			}
		}
		catch (DataAccessException ex) {
			log.debug("Rate limit skipped, Redis unavailable: {}", ex.getMessage());
		}
		return 0;
	}

}
