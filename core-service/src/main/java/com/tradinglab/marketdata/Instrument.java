package com.tradinglab.marketdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Read-only here: rows come from the V3 migration and {@link InstrumentSeeder}. */
@Entity
@Table(name = "instruments")
class Instrument {

	@Id
	private String symbol;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String exchange;

	@Column(nullable = false)
	private boolean tradable;

	protected Instrument() {
	}

	String getSymbol() {
		return symbol;
	}

	String getName() {
		return name;
	}

	String getExchange() {
		return exchange;
	}

	boolean isTradable() {
		return tradable;
	}

}
