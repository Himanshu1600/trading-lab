package com.tradinglab.journal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.tradinglab.ai.AiAssistant;
import com.tradinglab.portfolio.PortfolioService;
import com.tradinglab.trading.TradeHistory;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps one statistics snapshot per account. The Kafka consumer refreshes it after each fill. A
 * read also refreshes it if the fill count has moved on, so the journal is right even when Kafka
 * is behind, down or switched off.
 */
@Service
class JournalService {

	private static final Logger log = LoggerFactory.getLogger(JournalService.class);

	private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
	};

	private final JdbcClient jdbc;
	private final JsonMapper json;
	private final TradeHistory trades;
	private final PortfolioService portfolio;
	private final AiAssistant ai;

	JournalService(JdbcClient jdbc, JsonMapper json, TradeHistory trades, PortfolioService portfolio, AiAssistant ai) {
		this.jdbc = jdbc;
		this.json = json;
		this.trades = trades;
		this.portfolio = portfolio;
		this.ai = ai;
	}

	/**
	 * Handles one fill event from Kafka. Recording the event ID and refreshing the snapshot share
	 * a transaction, so a crash between them can't mark an event handled that wasn't.
	 *
	 * @return false if this event was already handled
	 */
	@Transactional
	public boolean onFill(UUID eventId, UUID accountId) {
		int firstTime = jdbc.sql("""
				INSERT INTO journal_processed_events (event_id, processed_at) VALUES (:id, :now)
				ON CONFLICT (event_id) DO NOTHING
				""").param("id", eventId).param("now", now()).update();
		if (firstTime == 0) {
			return false;
		}
		refresh(accountId);
		return true;
	}

	@Transactional
	public Map<String, Object> journal(UUID userId) {
		UUID accountId = portfolio.accountFor(userId);
		long fillCount = trades.fillCount(accountId);
		Long stored = jdbc.sql("SELECT fill_count FROM journal_snapshots WHERE account_id = :id")
			.param("id", accountId)
			.query(Long.class)
			.optional()
			.orElse(null);
		if (stored == null || stored != fillCount) {
			refresh(accountId);
		}
		return read(accountId);
	}

	/** Asks the AI coach for notes on the current statistics, and keeps them until the next fill. */
	public Map<String, Object> coach(UUID userId) {
		Map<String, Object> journal = journal(userId);
		@SuppressWarnings("unchecked")
		Map<String, Object> stats = (Map<String, Object>) journal.get("stats");
		if (((Number) stats.get("closedTrades")).intValue() == 0) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"The coach needs at least one completed trade: buy a stock, then sell it.");
		}
		if (Boolean.TRUE.equals(journal.get("insightsCurrent"))) {
			return journal;
		}
		// The AI call happens outside any transaction; only the short update below holds a connection.
		String insights = ai.journalInsights(userId, stats);
		jdbc.sql("""
				UPDATE journal_snapshots SET insights = :insights, insights_fill_count = :count, insights_at = :now
				WHERE account_id = :id
				""")
			.param("id", portfolio.accountFor(userId))
			.param("insights", insights)
			.param("count", ((Number) journal.get("fillCount")).longValue())
			.param("now", now())
			.update();
		return journal(userId);
	}

	/** Recomputes from the fills. Running it twice gives the same row, so a repeat is harmless. */
	private void refresh(UUID accountId) {
		var fills = trades.fills(accountId);
		jdbc.sql("""
				INSERT INTO journal_snapshots (account_id, stats, fill_count, updated_at)
				VALUES (:id, CAST(:stats AS jsonb), :count, :now)
				ON CONFLICT (account_id) DO UPDATE
				SET stats = EXCLUDED.stats, fill_count = EXCLUDED.fill_count, updated_at = EXCLUDED.updated_at
				""")
			.param("id", accountId)
			.param("stats", json.writeValueAsString(JournalCalculator.stats(fills)))
			.param("count", fills.size())
			.param("now", now())
			.update();
	}

	private Map<String, Object> read(UUID accountId) {
		return jdbc.sql("""
				SELECT stats, fill_count, updated_at, insights, insights_fill_count, insights_at
				FROM journal_snapshots WHERE account_id = :id
				""").param("id", accountId).query((rs, row) -> {
			Map<String, Object> journal = new LinkedHashMap<>();
			long fillCount = rs.getLong("fill_count");
			journal.put("stats", json.readValue(rs.getString("stats"), JSON_OBJECT));
			journal.put("fillCount", fillCount);
			journal.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class).toInstant());
			journal.put("insights", rs.getString("insights"));
			long insightsFor = rs.getLong("insights_fill_count");
			journal.put("insightsCurrent", !rs.wasNull() && insightsFor == fillCount);
			OffsetDateTime insightsAt = rs.getObject("insights_at", OffsetDateTime.class);
			journal.put("insightsAt", (insightsAt != null) ? insightsAt.toInstant() : null);
			return journal;
		}).single();
	}

	/** Event IDs only matter while Kafka might redeliver them, so a week is plenty. */
	@Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT24H")
	void forgetOldEvents() {
		int deleted = jdbc.sql("DELETE FROM journal_processed_events WHERE processed_at < :cutoff")
			.param("cutoff", Instant.now().minusSeconds(7 * 24 * 3600L).atOffset(ZoneOffset.UTC))
			.update();
		if (deleted > 0) {
			log.info("Forgot {} handled journal events", deleted);
		}
	}

	private static OffsetDateTime now() {
		return Instant.now().atOffset(ZoneOffset.UTC);
	}

}
