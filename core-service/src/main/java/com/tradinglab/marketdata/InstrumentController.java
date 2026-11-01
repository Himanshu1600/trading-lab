package com.tradinglab.marketdata;

import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/instruments")
class InstrumentController {

	private static final int MAX_RESULTS = 20;

	private final InstrumentRepository instruments;

	InstrumentController(InstrumentRepository instruments) {
		this.instruments = instruments;
	}

	@GetMapping
	List<InstrumentResponse> search(@RequestParam(defaultValue = "") String query) {
		String exact = query.trim().toUpperCase(Locale.ROOT);
		if (exact.isEmpty()) {
			return List.of();
		}
		String escaped = exact.replace("!", "!!").replace("%", "!%").replace("_", "!_");
		return instruments.search(exact, escaped + "%", "%" + escaped + "%", PageRequest.of(0, MAX_RESULTS))
			.stream()
			.map(InstrumentResponse::from)
			.toList();
	}

	record InstrumentResponse(String symbol, String name, String exchange) {

		static InstrumentResponse from(Instrument instrument) {
			return new InstrumentResponse(instrument.getSymbol(), instrument.getName(), instrument.getExchange());
		}

	}

}
