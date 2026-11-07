package com.tradinglab.marketdata;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/market")
class MarketController {

	private static final int MAX_SYMBOLS = 100;

	private final MarketQuotes quotes;
	private final MarketClock clock;
	private final PriceHistoryService history;

	MarketController(MarketQuotes quotes, MarketClock clock, PriceHistoryService history) {
		this.quotes = quotes;
		this.clock = clock;
		this.history = history;
	}

	@GetMapping("/quotes")
	List<Quote> quotes(@RequestParam String symbols) {
		List<String> requested = Arrays.stream(symbols.split(","))
			.map(symbol -> symbol.trim().toUpperCase(Locale.ROOT))
			.filter(symbol -> !symbol.isEmpty())
			.distinct()
			.toList();
		if (requested.size() > MAX_SYMBOLS) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ask for at most 100 symbols");
		}
		return List.copyOf(quotes.latest(requested).values());
	}

	@GetMapping("/clock")
	MarketClock.Status clock() {
		return clock.status();
	}

	@GetMapping("/bars")
	List<BarResponse> bars(@RequestParam String symbol, @RequestParam(defaultValue = "1Y") String range) {
		return history
			.bars(symbol.trim().toUpperCase(Locale.ROOT), PriceHistoryService.Range.from(range))
			.stream()
			.map(BarResponse::from)
			.toList();
	}

	/**
	 * {@code time} is the New York trading day, such as 2026-10-30, which labels daily bars.
	 * {@code timestamp} is when the bar starts, in Unix seconds, which places intraday bars.
	 */
	record BarResponse(String time, long timestamp, BigDecimal open, BigDecimal high, BigDecimal low,
			BigDecimal close, long volume) {

		static BarResponse from(PriceBar bar) {
			return new BarResponse(bar.time().atZone(PriceHistoryService.MARKET_ZONE).toLocalDate().toString(),
					bar.time().getEpochSecond(), bar.open(), bar.high(), bar.low(), bar.close(), bar.volume());
		}

	}

}
