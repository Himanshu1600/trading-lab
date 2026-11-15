package com.tradinglab.strategy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.tradinglab.marketdata.InstrumentCatalog;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saved strategies, one list per user. Every save re-validates the JSON and checks that each
 * symbol can be traded, so a stored strategy always parses.
 */
@Service
public class StrategyService {

	private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
	};

	private final JdbcClient jdbc;
	private final JsonMapper json;
	private final InstrumentCatalog instruments;

	StrategyService(JdbcClient jdbc, JsonMapper json, InstrumentCatalog instruments) {
		this.jdbc = jdbc;
		this.json = json;
		this.instruments = instruments;
	}

	/** Parses and checks a strategy without saving it. */
	public Strategy validate(Map<String, Object> dsl) {
		Strategy strategy = StrategyParser.parse(dsl);
		for (int i = 0; i < strategy.symbols().size(); i++) {
			String symbol = strategy.symbols().get(i);
			if (!instruments.isTradable(symbol)) {
				throw new InvalidStrategyException("symbols[" + i + "]", symbol + " isn't a tradable stock");
			}
		}
		return strategy;
	}

	public SavedStrategy create(UUID userId, Map<String, Object> dsl, String sourcePrompt) {
		Strategy strategy = validate(dsl);
		UUID id = UUID.randomUUID();
		OffsetDateTime now = now();
		jdbc.sql("""
				INSERT INTO strategies (id, user_id, name, dsl, dsl_version, source_prompt, created_at, updated_at)
				VALUES (:id, :userId, :name, CAST(:dsl AS jsonb), :version, :prompt, :now, :now)
				""")
			.param("id", id)
			.param("userId", userId)
			.param("name", strategy.name())
			.param("dsl", json.writeValueAsString(dsl))
			.param("version", StrategyParser.DSL_VERSION)
			.param("prompt", sourcePrompt)
			.param("now", now)
			.update();
		return get(userId, id);
	}

	public SavedStrategy update(UUID userId, UUID id, Map<String, Object> dsl) {
		Strategy strategy = validate(dsl);
		int updated = jdbc.sql("""
				UPDATE strategies SET name = :name, dsl = CAST(:dsl AS jsonb), updated_at = :now
				WHERE id = :id AND user_id = :userId
				""")
			.param("id", id)
			.param("userId", userId)
			.param("name", strategy.name())
			.param("dsl", json.writeValueAsString(dsl))
			.param("now", now())
			.update();
		if (updated == 0) {
			throw notFound();
		}
		return get(userId, id);
	}

	public void delete(UUID userId, UUID id) {
		int deleted = jdbc.sql("DELETE FROM strategies WHERE id = :id AND user_id = :userId")
			.param("id", id)
			.param("userId", userId)
			.update();
		if (deleted == 0) {
			throw notFound();
		}
	}

	public List<SavedStrategy> list(UUID userId) {
		return jdbc.sql("""
				SELECT id, name, dsl, source_prompt, created_at, updated_at FROM strategies
				WHERE user_id = :userId ORDER BY updated_at DESC LIMIT 100
				""").param("userId", userId).query(this::row).list();
	}

	/** Another user's strategy is reported as not found, so IDs reveal nothing. */
	public SavedStrategy get(UUID userId, UUID id) {
		return find(userId, id).orElseThrow(StrategyService::notFound);
	}

	public Optional<SavedStrategy> find(UUID userId, UUID id) {
		return jdbc.sql("""
				SELECT id, name, dsl, source_prompt, created_at, updated_at FROM strategies
				WHERE id = :id AND user_id = :userId
				""").param("id", id).param("userId", userId).query(this::row).optional();
	}

	private SavedStrategy row(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		return new SavedStrategy(rs.getObject("id", UUID.class), rs.getString("name"),
				json.readValue(rs.getString("dsl"), JSON_OBJECT), rs.getString("source_prompt"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				rs.getObject("updated_at", OffsetDateTime.class).toInstant());
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "Strategy not found");
	}

	private static OffsetDateTime now() {
		return Instant.now().atOffset(ZoneOffset.UTC);
	}

	/** A stored strategy. {@code dsl} is the JSON as saved; parse it with {@link StrategyParser}. */
	public record SavedStrategy(UUID id, String name, Map<String, Object> dsl, String sourcePrompt, Instant createdAt,
			Instant updatedAt) {

		public Strategy parsed() {
			return StrategyParser.parse(dsl);
		}

	}

}
