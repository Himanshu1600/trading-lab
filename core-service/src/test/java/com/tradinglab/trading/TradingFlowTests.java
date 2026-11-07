package com.tradinglab.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.tradinglab.TestcontainersConfiguration;
import com.tradinglab.marketdata.MarketClock;
import com.tradinglab.marketdata.MarketQuotes;
import com.tradinglab.marketdata.Quote;

@Testcontainers(disabledWithoutDocker = true)
@Import({ TestcontainersConfiguration.class, TradingFlowTests.FakeMarketConfiguration.class })
@SpringBootTest
@AutoConfigureMockMvc
class TradingFlowTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private MatchingEngine engine;

	@Autowired
	private FakeMarket market;

	@Autowired
	private JdbcClient jdbc;

	private String token;

	@BeforeEach
	void setUp() throws Exception {
		market.reset();
		market.price("AAPL", "100");
		market.open = true;
		token = newUser();
	}

	// tick() scans every account, so one test's open orders must not fill during the next test.
	@AfterEach
	void cancelLeftovers() {
		jdbc.sql("UPDATE orders SET status = 'CANCELLED' WHERE status = 'OPEN'").update();
	}

	@Test
	void newAccountStartsWithPaperCash() throws Exception {
		perform(get("/api/v1/account"), token).andExpect(status().isOk())
			.andExpect(jsonPath("$.cash").value(100000.0))
			.andExpect(jsonPath("$.buyingPower").value(100000.0))
			.andExpect(jsonPath("$.equity").value(100000.0));
	}

	@Test
	void marketBuyFillsOnTheNextTickAndUpdatesEverything() throws Exception {
		place("BUY", "MARKET", 10, null).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN"));

		// 10 x 100 x 1.02 set aside until the fill.
		perform(get("/api/v1/account"), token).andExpect(jsonPath("$.reservedCash").value(1020.0))
			.andExpect(jsonPath("$.buyingPower").value(98980.0));

		engine.tick();

		perform(get("/api/v1/orders"), token).andExpect(jsonPath("$[0].status").value("FILLED"))
			.andExpect(jsonPath("$[0].avgFillPrice").value(100.05));
		perform(get("/api/v1/positions"), token).andExpect(jsonPath("$[0].symbol").value("AAPL"))
			.andExpect(jsonPath("$[0].qty").value(10))
			.andExpect(jsonPath("$[0].avgCost").value(100.05));
		perform(get("/api/v1/fills"), token).andExpect(jsonPath("$[0].qty").value(10));
		perform(get("/api/v1/account"), token).andExpect(jsonPath("$.cash").value(98999.5))
			.andExpect(jsonPath("$.reservedCash").value(0.0));
	}

	@Test
	void limitBuyWaitsForThePriceThenFills() throws Exception {
		place("BUY", "LIMIT", 5, "90").andExpect(status().isCreated());

		engine.tick();
		perform(get("/api/v1/orders"), token).andExpect(jsonPath("$[0].status").value("OPEN"));

		market.price("AAPL", "89");
		engine.tick();
		perform(get("/api/v1/orders"), token).andExpect(jsonPath("$[0].status").value("FILLED"))
			.andExpect(jsonPath("$[0].avgFillPrice").value(89.0));
		perform(get("/api/v1/account"), token).andExpect(jsonPath("$.cash").value(99555.0));
	}

	@Test
	void ordersWaitWhileTheMarketIsClosed() throws Exception {
		market.open = false;
		place("BUY", "MARKET", 1, null).andExpect(status().isCreated());

		engine.tick();
		perform(get("/api/v1/orders"), token).andExpect(jsonPath("$[0].status").value("OPEN"));

		market.open = true;
		engine.tick();
		perform(get("/api/v1/orders"), token).andExpect(jsonPath("$[0].status").value("FILLED"));
	}

	@Test
	void sellRealizesProfitAndAveragesCost() throws Exception {
		buyFilled(10, "100");
		buyFilled(10, "120");
		perform(get("/api/v1/positions"), token).andExpect(jsonPath("$[0].qty").value(20))
			.andExpect(jsonPath("$[0].avgCost").value(110.0));

		market.price("AAPL", "130");
		place("SELL", "LIMIT", 5, "125").andExpect(status().isCreated());
		perform(get("/api/v1/positions"), token).andExpect(jsonPath("$[0].availableQty").value(15));
		engine.tick();

		perform(get("/api/v1/positions"), token).andExpect(jsonPath("$[0].qty").value(15))
			.andExpect(jsonPath("$[0].availableQty").value(15))
			.andExpect(jsonPath("$[0].realizedPnl").value(100.0))
			.andExpect(jsonPath("$[0].unrealizedPnl").value(300.0));
		// 100000 - 1000 - 1200 + 650 cash, plus 15 shares at 130.
		perform(get("/api/v1/account"), token).andExpect(jsonPath("$.cash").value(98450.0))
			.andExpect(jsonPath("$.equity").value(100400.0))
			.andExpect(jsonPath("$.realizedPnl").value(100.0));
	}

	@Test
	void sellingAllSharesKeepsTheRealizedProfit() throws Exception {
		buyFilled(2, "100");
		market.price("AAPL", "110");
		place("SELL", "LIMIT", 2, "110").andExpect(status().isCreated());
		engine.tick();

		perform(get("/api/v1/positions"), token).andExpect(jsonPath("$.length()").value(0));
		perform(get("/api/v1/account"), token).andExpect(jsonPath("$.realizedPnl").value(20.0));
	}

	@Test
	void cancelReturnsReservedCashAndShares() throws Exception {
		String buyId = id(place("BUY", "LIMIT", 10, "50"));
		perform(get("/api/v1/account"), token).andExpect(jsonPath("$.reservedCash").value(500.0));
		perform(delete("/api/v1/orders/" + buyId), token).andExpect(status().isNoContent());
		perform(get("/api/v1/account"), token).andExpect(jsonPath("$.reservedCash").value(0.0));

		buyFilled(3, "100");
		String sellId = id(place("SELL", "LIMIT", 3, "500"));
		perform(get("/api/v1/positions"), token).andExpect(jsonPath("$[0].availableQty").value(0));
		perform(delete("/api/v1/orders/" + sellId), token).andExpect(status().isNoContent());
		perform(get("/api/v1/positions"), token).andExpect(jsonPath("$[0].availableQty").value(3));

		perform(delete("/api/v1/orders/" + sellId), token).andExpect(status().isConflict());
	}

	@Test
	void cancelledOrderNeverFills() throws Exception {
		String id = id(place("BUY", "MARKET", 1, null));
		perform(delete("/api/v1/orders/" + id), token).andExpect(status().isNoContent());
		engine.tick();
		perform(get("/api/v1/positions"), token).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void rejectsOrdersThatCantBeCovered() throws Exception {
		place("BUY", "LIMIT", 1001, "100").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value("Not enough buying power"));
		place("SELL", "MARKET", 1, null).andExpect(status().isUnprocessableContent());

		buyFilled(5, "100");
		place("SELL", "LIMIT", 4, "500").andExpect(status().isCreated());
		place("SELL", "LIMIT", 2, "500").andExpect(status().isUnprocessableContent());

		// Refused orders roll back completely, leaving only the filled buy and the accepted sell.
		perform(get("/api/v1/orders"), token).andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	void rejectsInvalidRequests() throws Exception {
		place("BUY", "LIMIT", 1, null).andExpect(status().isBadRequest());
		place("BUY", "MARKET", 1, "100").andExpect(status().isBadRequest());
		place("BUY", "MARKET", 0, null).andExpect(status().isBadRequest());
		place("BUY", "LIMIT", 1, "-5").andExpect(status().isBadRequest());
		place("HOLD", "MARKET", 1, null).andExpect(status().isBadRequest());
		order(token, "NOPE123", "BUY", "MARKET", 1, null, UUID.randomUUID().toString())
			.andExpect(status().isNotFound());
		perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
			.content("{\"symbol\":\"AAPL\",\"side\":\"BUY\",\"type\":\"MARKET\",\"qty\":1}"), token)
			.andExpect(status().isBadRequest());
	}

	@Test
	void marketBuyWithoutAPriceIsRefused() throws Exception {
		market.reset();
		place("BUY", "MARKET", 1, null).andExpect(status().isServiceUnavailable());
		place("BUY", "LIMIT", 1, "100").andExpect(status().isCreated());
	}

	@Test
	void repeatingAnIdempotencyKeyCreatesOneOrder() throws Exception {
		String key = UUID.randomUUID().toString();
		String first = id(order(token, "AAPL", "BUY", "LIMIT", 1, "50", key));
		String second = id(order(token, "AAPL", "BUY", "LIMIT", 1, "50", key));

		assertThat(second).isEqualTo(first);
		perform(get("/api/v1/orders"), token).andExpect(jsonPath("$.length()").value(1));
		perform(get("/api/v1/account"), token).andExpect(jsonPath("$.reservedCash").value(50.0));
	}

	@Test
	void concurrentRetriesOfOneKeyCreateOneOrder() throws Exception {
		String key = UUID.randomUUID().toString();
		runConcurrently(8, () -> order(token, "AAPL", "BUY", "LIMIT", 1, "50", key).andReturn().getResponse().getStatus());

		perform(get("/api/v1/orders"), token).andExpect(jsonPath("$.length()").value(1));
		perform(get("/api/v1/account"), token).andExpect(jsonPath("$.reservedCash").value(50.0));
	}

	@Test
	void concurrentBuysCanNeverOverspend() throws Exception {
		List<Integer> statuses = runConcurrently(12, () -> place("BUY", "LIMIT", 300, "100").andReturn()
			.getResponse()
			.getStatus());

		// Each order needs 30,000 of the 100,000, so exactly three can succeed.
		assertThat(statuses).filteredOn(code -> code == 201).hasSize(3);
		assertThat(statuses).filteredOn(code -> code == 422).hasSize(9);
		perform(get("/api/v1/account"), token).andExpect(jsonPath("$.reservedCash").value(90000.0))
			.andExpect(jsonPath("$.buyingPower").value(10000.0));
	}

	@Test
	void priceJumpBeyondTheBufferRejectsInsteadOfOverdrawing() throws Exception {
		place("BUY", "LIMIT", 980, "100").andExpect(status().isCreated());
		engine.tick();

		// 2,000 of cash left. Reserving 1,020 passes, but the 10 shares then cost 3,000.
		place("BUY", "MARKET", 10, null).andExpect(status().isCreated());
		market.price("AAPL", "300");
		engine.tick();

		perform(get("/api/v1/orders"), token).andExpect(jsonPath("$[0].status").value("REJECTED"))
			.andExpect(jsonPath("$[0].rejectReason").value("Not enough cash at the fill price"));
		perform(get("/api/v1/account"), token).andExpect(jsonPath("$.cash").value(2000.0))
			.andExpect(jsonPath("$.reservedCash").value(0.0));
		assertThat(ledgerTotal()).isEqualByComparingTo("2000");
	}

	@Test
	void usersCantSeeOrCancelEachOthersOrders() throws Exception {
		String id = id(place("BUY", "LIMIT", 1, "50"));
		String stranger = newUser();

		perform(get("/api/v1/orders"), stranger).andExpect(jsonPath("$.length()").value(0));
		perform(delete("/api/v1/orders/" + id), stranger).andExpect(status().isNotFound());
		perform(get("/api/v1/orders"), token).andExpect(jsonPath("$[0].status").value("OPEN"));
	}

	@Test
	void marketEndpointsReturnQuotesAndClock() throws Exception {
		perform(get("/api/v1/market/quotes").param("symbols", "aapl,MSFT"), token).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].symbol").value("AAPL"));
		perform(get("/api/v1/market/clock"), token).andExpect(jsonPath("$.open").value(true));
		mvc.perform(get("/api/v1/account")).andExpect(status().isUnauthorized());
	}

	private void buyFilled(int qty, String price) throws Exception {
		market.price("AAPL", price);
		place("BUY", "LIMIT", qty, price).andExpect(status().isCreated());
		engine.tick();
	}

	// Sum of real cash movements for this test's account: the deposit, buys and sells.
	private BigDecimal ledgerTotal() {
		return jdbc.sql("""
				SELECT sum(amount) FROM cash_ledger
				WHERE type IN ('DEPOSIT', 'BUY', 'SELL')
				  AND account_id = (SELECT account_id FROM orders ORDER BY created_at DESC LIMIT 1)
				""").query(BigDecimal.class).single();
	}

	private List<Integer> runConcurrently(int count, Callable<Integer> task) throws Exception {
		try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				futures.add(executor.submit(task));
			}
			List<Integer> results = new ArrayList<>();
			for (Future<Integer> future : futures) {
				results.add(future.get());
			}
			return results;
		}
	}

	private ResultActions place(String side, String type, long qty, String limitPrice) throws Exception {
		return order(token, "AAPL", side, type, qty, limitPrice, UUID.randomUUID().toString());
	}

	private ResultActions order(String user, String symbol, String side, String type, long qty, String limitPrice,
			String key) throws Exception {
		String limit = (limitPrice != null) ? ",\"limitPrice\":" + limitPrice : "";
		String body = "{\"symbol\":\"%s\",\"side\":\"%s\",\"type\":\"%s\",\"qty\":%d%s}".formatted(symbol, side, type, qty,
				limit);
		return perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", key)
			.content(body), user);
	}

	private ResultActions perform(MockHttpServletRequestBuilder request, String user) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user));
	}

	private static String id(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private String newUser() throws Exception {
		String email = "user-" + UUID.randomUUID() + "@example.com";
		String body = mvc
			.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Test User\",\"email\":\"%s\",\"password\":\"correct-horse-battery\"}".formatted(email)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	@Test
	void pricesAreNeverFetchedInsideADatabaseTransaction() throws Exception {
		place("BUY", "MARKET", 1, null).andExpect(status().isCreated());
		engine.tick();
		perform(get("/api/v1/account"), token).andExpect(status().isOk());
		perform(get("/api/v1/positions"), token).andExpect(status().isOk());

		assertThat(market.quoteCalls).isPositive();
		assertThat(market.callsInsideTransaction).isZero();
	}

	/** Replaces Alpaca. Each symbol's bid, ask and last all sit at one price, so the arithmetic stays readable. */
	static class FakeMarket implements MarketQuotes, MarketClock {

		final Map<String, Quote> quotes = new ConcurrentHashMap<>();
		volatile boolean open = true;
		volatile int quoteCalls;
		volatile int callsInsideTransaction;

		void reset() {
			quotes.clear();
			quoteCalls = 0;
			callsInsideTransaction = 0;
		}

		void price(String symbol, String price) {
			BigDecimal value = new BigDecimal(price);
			quotes.put(symbol, new Quote(symbol, value, value, value, value));
		}

		@Override
		public Map<String, Quote> latest(Collection<String> symbols) {
			quoteCalls++;
			if (TransactionSynchronizationManager.isActualTransactionActive()) {
				callsInsideTransaction++;
			}
			Map<String, Quote> result = new HashMap<>();
			symbols.stream().filter(quotes::containsKey).forEach(symbol -> result.put(symbol, quotes.get(symbol)));
			return result;
		}

		@Override
		public Status status() {
			return new Status(open, Instant.EPOCH, Instant.EPOCH);
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeMarketConfiguration {

		@Bean
		@Primary
		FakeMarket fakeMarket() {
			return new FakeMarket();
		}

	}

}
