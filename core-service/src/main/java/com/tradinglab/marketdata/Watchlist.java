package com.tradinglab.marketdata;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "watchlists")
class Watchlist {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false)
	private UUID userId;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private Instant createdAt;

	@ElementCollection
	@CollectionTable(name = "watchlist_items", joinColumns = @JoinColumn(name = "watchlist_id"))
	@Column(name = "symbol")
	private Set<String> symbols = new HashSet<>();

	protected Watchlist() {
	}

	Watchlist(UUID userId, String name) {
		this.userId = userId;
		this.name = name;
		this.createdAt = Instant.now();
	}

	UUID getId() {
		return id;
	}

	String getName() {
		return name;
	}

	Set<String> getSymbols() {
		return symbols;
	}

}
