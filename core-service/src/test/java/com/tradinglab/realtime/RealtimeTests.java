package com.tradinglab.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.tradinglab.TestcontainersConfiguration;
import com.tradinglab.marketdata.MarketClock;
import com.tradinglab.marketdata.MarketQuotes;
import com.tradinglab.marketdata.Quote;

@Testcontainers(disabledWithoutDocker = true)
@Import({ TestcontainersConfiguration.class, RealtimeTests.FakeMarketConfiguration.class })
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeTests {

	@LocalServerPort
	private int port;

	private WebSocketStompClient stomp;

	private StompSession session;

	@BeforeEach
	void setUp() {
		stomp = new WebSocketStompClient(new StandardWebSocketClient());
		// Frames arrive as application/json; read them as raw text and parse with JsonPath.
		stomp.setMessageConverter(new StringMessageConverter() {
			{
				addSupportedMimeTypes(MimeTypeUtils.APPLICATION_JSON);
			}
		});
	}

	@AfterEach
	void tearDown() {
		if (session != null && session.isConnected()) {
			session.disconnect();
		}
		stomp.stop();
	}

	@Test
	void subscribersReceiveTheQuoteForTheirSymbol() throws Exception {
		session = connect(newUser());
		BlockingQueue<String> quotes = subscribe("/topic/quotes.AAPL");

		String quote = quotes.poll(5, TimeUnit.SECONDS);
		assertThat(quote).isNotNull();
		assertThat((String) JsonPath.read(quote, "$.symbol")).isEqualTo("AAPL");
		assertThat(((Number) JsonPath.read(quote, "$.last")).doubleValue()).isEqualTo(100.0);
	}

	@Test
	void usersReceiveTheirOwnOrderEventsOnly() throws Exception {
		String alice = newUser();
		String bob = newUser();
		session = connect(alice);
		BlockingQueue<String> aliceOrders = subscribe("/user/queue/orders");
		StompSession bobSession = connect(bob);
		BlockingQueue<String> bobOrders = new LinkedBlockingQueue<>();
		bobSession.subscribe("/user/queue/orders", collector(bobOrders));
		Thread.sleep(300);

		placeLimitBuy(alice);

		String event = aliceOrders.poll(5, TimeUnit.SECONDS);
		assertThat(event).isNotNull();
		assertThat((String) JsonPath.read(event, "$.kind")).isEqualTo("PLACED");
		assertThat((String) JsonPath.read(event, "$.symbol")).isEqualTo("AAPL");
		assertThat(bobOrders.poll(500, TimeUnit.MILLISECONDS)).isNull();
		bobSession.disconnect();
	}

	@Test
	void connectingWithoutATokenIsRefused() {
		assertThatThrownBy(() -> stomp
			.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), new StompHeaders(),
					new StompSessionHandlerAdapter() {
					})
			.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
	}

	@Test
	void subscribingToAnotherDestinationIsRefused() throws Exception {
		session = connect(newUser());
		BlockingQueue<String> other = subscribe("/queue/orders-user123");
		assertThat(other.poll(500, TimeUnit.MILLISECONDS)).isNull();
		Thread.sleep(300);
		assertThat(session.isConnected()).isFalse();
	}

	private StompSession connect(String token) throws Exception {
		StompHeaders headers = new StompHeaders();
		headers.add("Authorization", "Bearer " + token);
		return stomp
			.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers,
					new StompSessionHandlerAdapter() {
					})
			.get(5, TimeUnit.SECONDS);
	}

	private BlockingQueue<String> subscribe(String destination) {
		BlockingQueue<String> queue = new LinkedBlockingQueue<>();
		session.subscribe(destination, collector(queue));
		return queue;
	}

	private static StompFrameHandler collector(BlockingQueue<String> queue) {
		return new StompFrameHandler() {

			@Override
			public Type getPayloadType(StompHeaders headers) {
				return String.class;
			}

			@Override
			public void handleFrame(StompHeaders headers, Object payload) {
				queue.add((String) payload);
			}

		};
	}

	private RestClient api() {
		return RestClient.create("http://localhost:" + port);
	}

	private void placeLimitBuy(String token) {
		api().post()
			.uri("/api/v1/orders")
			.header("Authorization", "Bearer " + token)
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.body("{\"symbol\":\"AAPL\",\"side\":\"BUY\",\"type\":\"LIMIT\",\"qty\":1,\"limitPrice\":50}")
			.retrieve()
			.toBodilessEntity();
	}

	private String newUser() {
		String body = api().post()
			.uri("/api/v1/auth/register")
			.contentType(MediaType.APPLICATION_JSON)
			.body("{\"name\":\"Test User\",\"email\":\"user-%s@example.com\",\"password\":\"correct-horse-battery\"}"
				.formatted(UUID.randomUUID()))
			.retrieve()
			.body(String.class);
		return JsonPath.read(body, "$.accessToken");
	}

	static class FakeMarket implements MarketQuotes, MarketClock {

		@Override
		public Map<String, Quote> latest(Collection<String> symbols) {
			Map<String, Quote> result = new HashMap<>();
			BigDecimal price = new BigDecimal("100");
			symbols.forEach(symbol -> result.put(symbol, new Quote(symbol, price, price, price, price)));
			return result;
		}

		@Override
		public Status status() {
			return new Status(false, Instant.EPOCH, Instant.EPOCH);
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
