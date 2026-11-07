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

@Entity
@Table(name = "fills")
class Fill {

	@Id
	private UUID id;

	@Column(nullable = false)
	private UUID orderId;

	@Column(nullable = false)
	private UUID accountId;

	@Column(nullable = false)
	private String symbol;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PaperOrder.Side side;

	@Column(nullable = false)
	private long qty;

	@Column(nullable = false)
	private BigDecimal price;

	@Column(nullable = false)
	private Instant filledAt;

	protected Fill() {
	}

	Fill(PaperOrder order, BigDecimal price) {
		this.id = UUID.randomUUID();
		this.orderId = order.getId();
		this.accountId = order.getAccountId();
		this.symbol = order.getSymbol();
		this.side = order.getSide();
		this.qty = order.getQty();
		this.price = price;
		this.filledAt = Instant.now();
	}

	UUID getOrderId() {
		return orderId;
	}

	String getSymbol() {
		return symbol;
	}

	PaperOrder.Side getSide() {
		return side;
	}

	long getQty() {
		return qty;
	}

	BigDecimal getPrice() {
		return price;
	}

	Instant getFilledAt() {
		return filledAt;
	}

}
