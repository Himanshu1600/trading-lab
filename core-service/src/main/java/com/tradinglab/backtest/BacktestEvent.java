package com.tradinglab.backtest;

import java.util.UUID;

/** A backtest finished or failed. Published inside the transaction that records it. */
public record BacktestEvent(UUID runId, UUID userId, String strategyName, String status, Double totalReturnPct,
		String error) {
}
