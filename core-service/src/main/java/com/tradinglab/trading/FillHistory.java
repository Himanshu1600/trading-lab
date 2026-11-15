package com.tradinglab.trading;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class FillHistory implements TradeHistory {

	private final FillRepository fills;

	FillHistory(FillRepository fills) {
		this.fills = fills;
	}

	@Override
	@Transactional(readOnly = true)
	public List<FillRecord> fills(UUID accountId) {
		return fills.findByAccountIdOrderByFilledAt(accountId)
			.stream()
			.map(fill -> new FillRecord(fill.getSymbol(), fill.getSide() == PaperOrder.Side.BUY, fill.getQty(),
					fill.getPrice(), fill.getFilledAt()))
			.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public long fillCount(UUID accountId) {
		return fills.countByAccountId(accountId);
	}

}
