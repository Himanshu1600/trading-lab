package com.tradinglab.trading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface OrderRepository extends JpaRepository<PaperOrder, UUID> {

	Optional<PaperOrder> findByAccountIdAndClientOrderId(UUID accountId, String clientOrderId);

	Optional<PaperOrder> findByIdAndAccountId(UUID id, UUID accountId);

	List<PaperOrder> findTop100ByAccountIdOrderByCreatedAtDesc(UUID accountId);

	List<PaperOrder> findTop100ByAccountIdAndStatusOrderByCreatedAtDesc(UUID accountId, PaperOrder.Status status);

	List<PaperOrder> findByStatusOrderByCreatedAt(PaperOrder.Status status);

	List<PaperOrder> findByStatusAndExpiresAtLessThanEqual(PaperOrder.Status status, Instant now);

	/** Records that a stop order's price was reached. Returns 0 if it already triggered or is no longer open. */
	@Modifying(clearAutomatically = true)
	@Query("""
			update PaperOrder o set o.triggeredAt = :now, o.updatedAt = :now
			where o.id = :id and o.status = com.tradinglab.trading.PaperOrder.Status.OPEN and o.triggeredAt is null
			""")
	int markTriggered(UUID id, Instant now);

	/** Returns 0 if the order already left the expected status, so only one caller can act on it. */
	@Modifying(clearAutomatically = true)
	@Query("""
			update PaperOrder o
			set o.status = :to, o.avgFillPrice = :avgFillPrice, o.rejectReason = :rejectReason, o.updatedAt = :now
			where o.id = :id and o.status = :from
			""")
	int transition(UUID id, PaperOrder.Status from, PaperOrder.Status to, BigDecimal avgFillPrice, String rejectReason,
			Instant now);

}
