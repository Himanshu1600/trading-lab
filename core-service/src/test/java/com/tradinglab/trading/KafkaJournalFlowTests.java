package com.tradinglab.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;

import com.jayway.jsonpath.JsonPath;
import com.tradinglab.TestcontainersConfiguration;
import com.tradinglab.ai.AiAssistant;
import com.tradinglab.marketdata.MarketClock;
import com.tradinglab.marketdata.MarketQuotes;
import com.tradinglab.marketdata.Quote;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The whole event path with a real broker: a fill commits, the outbox sends it to Kafka keyed by
 * account, the journal's consumer refreshes the statistics, and a repeated message is ignored.
 */
@Testcontainers(disabledWithoutDocker = true)
@Import({ TestcontainersConfiguration.class, KafkaJournalFlowTests.Configuration.class })
@SpringBootTest(properties = { "spring.modulith.events.externalization.enabled=true",
		"spring.kafka.listener.auto-startup=true", "spring.kafka.admin.auto-create=true" })
@AutoConfigureMockMvc
class KafkaJournalFlowTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private MatchingEngine engine;

	@Autowired
	private FakeMarket market;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private KafkaConnectionDetails kafka;

	@Autowired
	private KafkaTemplate<Object, Object> kafkaTemplate;

	@Autowired
	private MeterRegistry meters;

	@Test
	void fillsReachKafkaAndRefreshTheJournal() throws Exception {
		String token = newUser();
		market.price("AAPL", "100");
		order(token, "BUY");
		engine.tick();
		market.price("AAPL", "120");
		order(token, "SELL");
		engine.tick();

		UUID accountId = jdbc.sql("SELECT account_id FROM orders ORDER BY created_at DESC LIMIT 1")
			.query(UUID.class)
			.single();

		// Read the topic with a separate consumer group, as any other service could.
		List<ConsumerRecord<String, String>> records = readTopic(accountId, 4);
		assertThat(records).extracting(ConsumerRecord::key).containsOnly(accountId.toString());
		assertThat(records).extracting(record -> (String) JsonPath.read(record.value(), "$.kind"))
			.containsExactly("PLACED", "FILLED", "PLACED", "FILLED");
		// One key, one partition: that's what keeps an account's events in order.
		assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.getFirst().partition());

		// The consumer refreshed the snapshot by itself; nobody has opened the journal yet.
		await(() -> jdbc.sql("SELECT fill_count FROM journal_snapshots WHERE account_id = :id")
			.param("id", accountId)
			.query(Long.class)
			.optional()
			.orElse(0L) == 2);
		// Handled events leave the outbox.
		await(() -> jdbc.sql("SELECT count(*) FROM event_publication").query(Long.class).single() == 0);

		mvc.perform(get("/api/v1/journal").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stats.closedTrades").value(1))
			.andExpect(jsonPath("$.stats.winRatePct").value(100.0))
			.andExpect(jsonPath("$.insightsCurrent").value(false));
		mvc.perform(post("/api/v1/journal/insights").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.insights").value("Coached."))
			.andExpect(jsonPath("$.insightsCurrent").value(true));

		// Kafka may deliver a message twice. Sending a fill again must change nothing.
		ConsumerRecord<String, String> fill = records.get(1);
		double duplicatesBefore = duplicates();
		kafkaTemplate.send(OrderEvent.TOPIC, fill.key(), fill.value().getBytes()).get();
		await(() -> duplicates() == duplicatesBefore + 1);
		assertThat(jdbc.sql("SELECT fill_count FROM journal_snapshots WHERE account_id = :id")
			.param("id", accountId)
			.query(Long.class)
			.single()).isEqualTo(2);
	}

	private double duplicates() {
		return meters.counter("journal.events", "result", "duplicate").count();
	}

	private List<ConsumerRecord<String, String>> readTopic(UUID accountId, int expected) {
		Map<String, Object> config = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
				String.join(",", kafka.getBootstrapServers()), ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest", ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
				StringDeserializer.class, ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		List<ConsumerRecord<String, String>> found = new ArrayList<>();
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
			consumer.subscribe(List.of(OrderEvent.TOPIC));
			Instant deadline = Instant.now().plusSeconds(30);
			while (found.size() < expected && Instant.now().isBefore(deadline)) {
				consumer.poll(Duration.ofMillis(500)).forEach(record -> {
					if (accountId.toString().equals(record.key())) {
						found.add(record);
					}
				});
			}
		}
		return found;
	}

	private static void await(Supplier<Boolean> condition) throws InterruptedException {
		Instant deadline = Instant.now().plusSeconds(30);
		while (!condition.get()) {
			if (Instant.now().isAfter(deadline)) {
				throw new AssertionError("Condition not met within 30 seconds");
			}
			Thread.sleep(200);
		}
	}

	private void order(String token, String side) throws Exception {
		mvc.perform(post("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"symbol\":\"AAPL\",\"side\":\"%s\",\"type\":\"MARKET\",\"qty\":10}".formatted(side)))
			.andExpect(status().isCreated());
	}

	private String newUser() throws Exception {
		String body = mvc
			.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Test User\",\"email\":\"user-%s@example.com\",\"password\":\"correct-horse-battery\"}"
					.formatted(UUID.randomUUID())))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	static class FakeMarket implements MarketQuotes, MarketClock {

		private final Map<String, Quote> quotes = new ConcurrentHashMap<>();

		void price(String symbol, String price) {
			BigDecimal value = new BigDecimal(price);
			quotes.put(symbol, new Quote(symbol, value, value, value, value));
		}

		@Override
		public Map<String, Quote> latest(Collection<String> symbols) {
			Map<String, Quote> result = new HashMap<>();
			symbols.stream().filter(quotes::containsKey).forEach(symbol -> result.put(symbol, quotes.get(symbol)));
			return result;
		}

		@Override
		public Status status() {
			return new Status(true, Instant.EPOCH, Instant.EPOCH);
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Configuration {

		@Bean
		@ServiceConnection
		RedpandaContainer redpanda() {
			return new RedpandaContainer("docker.redpanda.com/redpandadata/redpanda:v26.2.7");
		}

		@Bean
		@Primary
		FakeMarket fakeMarket() {
			return new FakeMarket();
		}

		@Bean
		@Primary
		AiAssistant fakeAi() {
			return new AiAssistant() {

				@Override
				public Draft draftStrategy(UUID userId, String prompt, Map<String, Object> previousDsl,
						String previousError) {
					throw new UnsupportedOperationException();
				}

				@Override
				public String explainBacktest(UUID userId, Map<String, Object> strategy, Map<String, Object> params,
						Map<String, Object> metrics) {
					throw new UnsupportedOperationException();
				}

				@Override
				public Map<String, Object> askCopilot(UUID userId, String symbol, String question) {
			return Map.of("symbol", symbol, "segments", List.of(Map.of("text", "Answered.", "citations", List.of())),
					"sources", List.of());
		}

		@Override
		public String journalInsights(UUID userId, Map<String, Object> stats) {
					return "Coached.";
				}

			};
		}

	}

}
