package com.tradinglab.marketdata;

import org.springframework.stereotype.Service;

/** What other modules may ask about instruments. */
@Service
public class InstrumentCatalog {

	private final InstrumentRepository instruments;

	InstrumentCatalog(InstrumentRepository instruments) {
		this.instruments = instruments;
	}

	public boolean isTradable(String symbol) {
		return instruments.findById(symbol).filter(Instrument::isTradable).isPresent();
	}

}
