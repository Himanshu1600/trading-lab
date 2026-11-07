package com.tradinglab.trading;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface FillRepository extends JpaRepository<Fill, UUID> {

	List<Fill> findTop100ByAccountIdOrderByFilledAtDesc(UUID accountId);

}
