package com.tradinglab.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.tradinglab.TestcontainersConfiguration;

@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RedisQuoteCacheTests {

	@Autowired
	private RedisQuoteCache cache;

	@Test
	void storedQuotesComeBackUntilTheyExpire() throws Exception {
		Quote apple = new Quote("AAPL", new BigDecimal("189.10"), new BigDecimal("189.20"), new BigDecimal("189.15"),
				new BigDecimal("187.00"));
		cache.store(Map.of("AAPL", apple));

		assertThat(cache.fresh(List.of("AAPL", "MSFT"))).containsOnlyKeys("AAPL").containsEntry("AAPL", apple);

		Thread.sleep(QuoteCache.TTL.plusMillis(300).toMillis());
		assertThat(cache.fresh(List.of("AAPL"))).isEmpty();
	}

	@Test
	void encodingKeepsEveryPriceExactly() {
		Quote quote = new Quote("X", new BigDecimal("0.0001"), new BigDecimal("12345.6789"), BigDecimal.ZERO,
				new BigDecimal("1E+2"));
		assertThat(RedisQuoteCache.decode("X", RedisQuoteCache.encode(quote)).previousClose())
			.isEqualByComparingTo("100");
		assertThat(RedisQuoteCache.decode("X", RedisQuoteCache.encode(quote)).ask()).isEqualByComparingTo("12345.6789");
	}

}
