package com.tradinglab.marketdata;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface InstrumentRepository extends JpaRepository<Instrument, String> {

	/** Exact symbol first, then symbols starting with the query, then name matches. */
	@Query("""
			select i from Instrument i
			where i.tradable = true
			  and (i.symbol like :prefix escape '!' or upper(i.name) like :contains escape '!')
			order by case when i.symbol = :exact then 0
			              when i.symbol like :prefix escape '!' then 1
			              else 2 end,
			         i.symbol
			""")
	List<Instrument> search(String exact, String prefix, String contains, Pageable pageable);

}
