package com.tradinglab.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Status only changes through {@link OrderRepository#transition}, so a fill and a cancel can't both win. */
@Entity
@Table(name = "orders")
class PaperOrder {

	enum Side {
		BUY, SELL
	}

	enum Type {
		MARKET, LIMIT
	}

	enum Status {
		OPEN, FILLED, CANCELLED, REJECTED
	}

	@Id
	private UUID id;

	@Column(nullable = false)
	private UUID accountId;

	@Column(nullable = false)
	private String clientOrderId;

	@Column(nullable = false)
	private String symbol;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Side side;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Type type;

	@Column(nullable = false)
	private long qty;

	private BigDecimal limitPrice;

	@Column(nullable = false)
	private BigDecimal reservedCash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	private BigDecimal avgFillPrice;

	private String rejectReason;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	protected PaperOrder() {
	}

	PaperOrder(UUID accountId, String clientOrderId, String symbol, Side side, Type type, long qty,
			BigDecimal limitPrice, BigDecimal reservedCash) {
		this.id = UUID.randomUUID();
		this.accountId = accountId;
		this.clientOrderId = clientOrderId;
		this.symbol = symbol;
		this.side = side;
		this.type = type;
		this.qty = qty;
		this.limitPrice = limitPrice;
		this.reservedCash = reservedCash;
		this.status = Status.OPEN;
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	UUID getId() {
		return id;
	}

	UUID getAccountId() {
		return accountId;
	}

	String getSymbol() {
		return symbol;
	}

	Side getSide() {
		return side;
	}

	Type getType() {
		return type;
	}

	long getQty() {
		return qty;
	}

	BigDecimal getLimitPrice() {
		return limitPrice;
	}

	BigDecimal getReservedCash() {
		return reservedCash;
	}

	Status getStatus() {
		return status;
	}

	BigDecimal getAvgFillPrice() {
		return avgFillPrice;
	}

	String getRejectReason() {
		return rejectReason;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	Instant getUpdatedAt() {
		return updatedAt;
	}

}
