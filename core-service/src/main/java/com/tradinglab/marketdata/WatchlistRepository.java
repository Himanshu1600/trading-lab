package com.tradinglab.marketdata;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

// Every lookup includes userId, so one user can never reach another user's list.
interface WatchlistRepository extends JpaRepository<Watchlist, UUID> {

	@EntityGraph(attributePaths = "symbols")
	List<Watchlist> findByUserIdOrderByCreatedAt(UUID userId);

	@EntityGraph(attributePaths = "symbols")
	Optional<Watchlist> findByIdAndUserId(UUID id, UUID userId);

}
