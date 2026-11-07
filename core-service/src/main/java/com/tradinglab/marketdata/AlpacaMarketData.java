package com.tradinglab.marketdata;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Polls Alpaca over REST with short caches, which keeps several open pages inside the
 * free plan's rate limit. A failed call means "no data": orders simply wait.
 */
@Component
class AlpacaMarketData implements MarketQuotes, MarketClock, PriceHistorySource {

	private static final Logger log = LoggerFactory.getLogger(AlpacaMarketData.class);

	private static final int MAX_BAR_PAGES = 20;
	private static final Duration QUOTE_TTL = Duration.ofSeconds(2);
	private static final Duration CLOCK_TTL = Duration.ofSeconds(30);
	private static final Duration CLOCK_RETRY_DELAY = Duration.ofSeconds(10);
	// Past this age, a remembered clock reading is no longer trusted at all.
	private static final Duration CLOCK_STALE_LIMIT = Duration.ofMinutes(15);
	private static final Status UNKNOWN = new Status(false, null, null);

	private final RestClient tradingClient;
	private final RestClient dataClient;
	private final boolean hasKeys;
	private final java.time.Clock time;
	private final Map<String, Cached<Quote>> quotes = new ConcurrentHashMap<>();
	private volatile Cached<Status> clock;
	private volatile Instant clockRetryAt = Instant.MIN;

	@org.springframework.beans.factory.annotation.Autowired
	AlpacaMarketData(RestClient.Builder builder, AlpacaProperties properties) {
		this(builder, properties, java.time.Clock.systemUTC());
	}

	/** Tests pass a fixed clock, so cache expiry doesn't depend on waiting. */
	AlpacaMarketData(RestClient.Builder builder, AlpacaProperties properties, java.time.Clock time) {
		this.time = time;
		RestClient.Builder authenticated = builder.defaultHeader("APCA-API-KEY-ID", properties.apiKeyId())
			.defaultHeader("APCA-API-SECRET-KEY", properties.apiSecretKey());
		this.tradingClient = authenticated.clone().baseUrl(properties.tradingUrl()).build();
		this.dataClient = authenticated.clone().baseUrl(properties.dataUrl()).build();
		this.hasKeys = properties.hasKeys();
	}

	@Override
	public Map<String, Quote> latest(Collection<String> symbols) {
		Instant now = time.instant();
		Map<String, Quote> result = new HashMap<>();
		List<String> missing = new ArrayList<>();
		for (String symbol : symbols) {
			Cached<Quote> cached = quotes.get(symbol);
			if (cached != null && cached.isFresh(now, QUOTE_TTL)) {
				result.put(symbol, cached.value());
			}
			else {
				missing.add(symbol);
			}
		}
		if (!missing.isEmpty() && hasKeys) {
			fetch(missing).forEach((symbol, quote) -> {
				quotes.put(symbol, new Cached<>(quote, now));
				result.put(symbol, quote);
			});
		}
		return result;
	}

	/**
	 * Asks Alpaca at most every 30 seconds. If Alpaca can't be reached, the last reading is used
	 * instead, aged correctly: "open, closes at 4pm" becomes "closed" after 4pm. Only with no
	 * usable reading does the market count as closed.
	 */
	@Override
	public Status status() {
		Instant now = time.instant();
		Cached<Status> cached = clock;
		if (cached != null && cached.isFresh(now, CLOCK_TTL)) {
			return cached.value();
		}
		if (!hasKeys) {
			return UNKNOWN;
		}
		if (now.isBefore(clockRetryAt)) {
			return lastKnown(cached, now);
		}
		try {
			ClockBody body = tradingClient.get().uri("/v2/clock").retrieve().body(ClockBody.class);
			Status status = new Status(body.open(), parse(body.nextOpen()), parse(body.nextClose()));
			clock = new Cached<>(status, now);
			return status;
		}
		catch (RestClientException ex) {
			log.warn("Could not read the market clock from Alpaca: {}", ex.getMessage());
			clockRetryAt = now.plus(CLOCK_RETRY_DELAY);
			return lastKnown(cached, now);
		}
	}

	private static Status lastKnown(Cached<Status> cached, Instant now) {
		if (cached == null || !cached.isFresh(now, CLOCK_STALE_LIMIT)) {
			return UNKNOWN;
		}
		Status status = cached.value();
		boolean stillOpen = status.open() && status.nextClose() != null && now.isBefore(status.nextClose());
		return new Status(stillOpen, status.nextOpen(), status.nextClose());
	}

	/**
	 * Split-adjusted bars from the timeframe's feed. The free plan allows SIP history only up to
	 * 15 minutes ago, which the caller handles through {@code end}. Failures are thrown, not hidden.
	 */
	@Override
	public List<PriceBar> bars(String symbol, BarTimeframe timeframe, Instant start, Instant end) {
		if (!hasKeys) {
			return List.of();
		}
		List<PriceBar> bars = new ArrayList<>();
		String pageToken = null;
		for (int page = 0; page < MAX_BAR_PAGES; page++) {
			String token = pageToken;
			BarsPage body = dataClient.get().uri(uri -> {
				uri.path("/v2/stocks/{symbol}/bars")
					.queryParam("timeframe", timeframe.code)
					.queryParam("start", start.toString())
					.queryParam("end", end.toString())
					.queryParam("limit", 10000)
					.queryParam("adjustment", "all")
					.queryParam("feed", timeframe.feed)
					.queryParam("sort", "asc");
				if (token != null) {
					uri.queryParam("page_token", token);
				}
				return uri.build(symbol);
			}).retrieve().body(BarsPage.class);
			if (body == null || body.bars() == null) {
				break;
			}
			body.bars().forEach(bar -> bars.add(bar.toPriceBar()));
			pageToken = body.nextPageToken();
			if (pageToken == null || pageToken.isEmpty()) {
				break;
			}
		}
		return bars;
	}

	private Map<String, Quote> fetch(List<String> symbols) {
		Map<String, Quote> fetched = new HashMap<>();
		try {
			Map<String, Snapshot> snapshots = dataClient.get()
				.uri(uri -> uri.path("/v2/stocks/snapshots")
					.queryParam("symbols", String.join(",", symbols))
					.queryParam("feed", "iex")
					.build())
				.retrieve()
				.body(new ParameterizedTypeReference<>() {
				});
			snapshots.forEach((symbol, snapshot) -> {
				Quote quote = snapshot.toQuote(symbol);
				if (quote.markPrice().signum() > 0) {
					fetched.put(symbol, quote);
				}
			});
		}
		catch (RestClientException ex) {
			log.warn("Could not read quotes from Alpaca: {}", ex.getMessage());
		}
		return fetched;
	}

	private static Instant parse(String timestamp) {
		return (timestamp != null) ? OffsetDateTime.parse(timestamp).toInstant() : null;
	}

	private record Cached<T>(T value, Instant at) {

		boolean isFresh(Instant now, Duration ttl) {
			return at.plus(ttl).isAfter(now);
		}

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ClockBody(@JsonProperty("is_open") boolean open, @JsonProperty("next_open") String nextOpen,
			@JsonProperty("next_close") String nextClose) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Snapshot(Trade latestTrade, BidAsk latestQuote, Bar prevDailyBar) {

		Quote toQuote(String symbol) {
			BigDecimal bid = (latestQuote != null) ? orZero(latestQuote.bp()) : BigDecimal.ZERO;
			BigDecimal ask = (latestQuote != null) ? orZero(latestQuote.ap()) : BigDecimal.ZERO;
			BigDecimal last = (latestTrade != null) ? orZero(latestTrade.p()) : BigDecimal.ZERO;
			BigDecimal previousClose = (prevDailyBar != null) ? orZero(prevDailyBar.c()) : BigDecimal.ZERO;
			return new Quote(symbol, bid, ask, last, previousClose);
		}

		private static BigDecimal orZero(BigDecimal value) {
			return (value != null) ? value : BigDecimal.ZERO;
		}

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record BarsPage(List<RawBar> bars, @JsonProperty("next_page_token") String nextPageToken) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RawBar(String t, BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c, long v) {

		PriceBar toPriceBar() {
			return new PriceBar(OffsetDateTime.parse(t).toInstant(), o, h, l, c, v);
		}

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Trade(BigDecimal p) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record BidAsk(BigDecimal ap, BigDecimal bp) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Bar(BigDecimal c) {
	}

}
