package com.tradinglab.backtest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.tradinglab.ai.AiAssistant;
import com.tradinglab.marketdata.HistoricalPrices;
import com.tradinglab.strategy.Condition;
import com.tradinglab.strategy.Operand;
import com.tradinglab.strategy.Strategy;
import com.tradinglab.strategy.StrategyService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs backtests in the background. A request saves a QUEUED run and returns its ID at once; a
 * virtual thread then loads the prices, runs {@link BacktestEngine} and saves the results in one
 * transaction. The owner hears about it through a {@link BacktestEvent}.
 *
 * <p>At most {@value #MAX_RUNNING} runs compute at once, and each user can have at most
 * {@value #MAX_UNFINISHED_PER_USER} unfinished, so one user can't starve the others.
 */
@Service
class BacktestService {

	private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

	static final int MAX_RUNNING = 4;
	static final int MAX_UNFINISHED_PER_USER = 3;
	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
	private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
	};

	private final JdbcClient jdbc;
	private final JdbcTemplate jdbcTemplate;
	private final JsonMapper json;
	private final StrategyService strategies;
	private final HistoricalPrices prices;
	private final AiAssistant ai;
	private final TransactionTemplate transaction;
	private final ApplicationEventPublisher events;
	private final Timer runTimer;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final Semaphore running = new Semaphore(MAX_RUNNING);

	BacktestService(JdbcClient jdbc, JdbcTemplate jdbcTemplate, JsonMapper json, StrategyService strategies,
			HistoricalPrices prices, AiAssistant ai, TransactionTemplate transaction, ApplicationEventPublisher events,
			MeterRegistry meters) {
		this.jdbc = jdbc;
		this.jdbcTemplate = jdbcTemplate;
		this.json = json;
		this.strategies = strategies;
		this.prices = prices;
		this.ai = ai;
		this.transaction = transaction;
		this.events = events;
		this.runTimer = meters.timer("backtest.run");
	}

	/** Settings after defaults are filled in. */
	record Params(List<String> symbols, LocalDate from, LocalDate to, double initialCash, double feeBps,
			double slippageBps) {
	}

	UUID start(UUID userId, BacktestController.RunRequest request) {
		StrategyService.SavedStrategy saved = strategies.get(userId, request.strategyId());
		Strategy strategy = saved.parsed();
		Params params = params(strategy, request);
		int unfinished = jdbc.sql("""
				SELECT count(*) FROM backtest_runs WHERE user_id = :userId AND status IN ('QUEUED', 'RUNNING')
				""").param("userId", userId).query(Integer.class).single();
		if (unfinished >= MAX_UNFINISHED_PER_USER) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
					"You already have " + unfinished + " backtests running. Wait for one to finish.");
		}
		String dslJson = json.writeValueAsString(saved.dsl());
		String paramsJson = json.writeValueAsString(params);
		UUID runId = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO backtest_runs (id, user_id, strategy_id, strategy_name, dsl, params, input_hash, status, created_at)
				VALUES (:id, :userId, :strategyId, :name, CAST(:dsl AS jsonb), CAST(:params AS jsonb), :hash, 'QUEUED', :now)
				""")
			.param("id", runId)
			.param("userId", userId)
			.param("strategyId", saved.id())
			.param("name", strategy.name())
			.param("dsl", dslJson)
			.param("params", paramsJson)
			.param("hash", sha256(dslJson + "\n" + paramsJson))
			.param("now", now())
			.update();
		executor.submit(() -> execute(runId, userId, strategy, params));
		return runId;
	}

	private Params params(Strategy strategy, BacktestController.RunRequest request) {
		LocalDate today = LocalDate.now(NEW_YORK);
		LocalDate to = (request.to() != null) ? request.to() : today;
		LocalDate from = (request.from() != null) ? request.from() : to.minusYears(3);
		if (!from.isBefore(to)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The start date must be before the end date");
		}
		if (to.isAfter(today)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The end date can't be in the future");
		}
		if (from.isBefore(today.minusYears(5))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "History goes back 5 years at most");
		}
		return new Params(strategy.symbols(), from, to, orDefault(request.initialCash(), 100_000),
				orDefault(request.feeBps(), 0), orDefault(request.slippageBps(), 5));
	}

	private void execute(UUID runId, UUID userId, Strategy strategy, Params params) {
		try {
			running.acquire();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return;
		}
		Instant started = Instant.now();
		try {
			jdbc.sql("UPDATE backtest_runs SET status = 'RUNNING', started_at = :now WHERE id = :id")
				.param("id", runId)
				.param("now", now())
				.update();
			// Prices are loaded before any transaction opens, because they may come from Alpaca.
			Map<String, List<BacktestEngine.Bar>> history = new LinkedHashMap<>();
			LocalDate warmUpStart = params.from().minusDays(calendarDaysFor(maxWarmUp(strategy)));
			for (String symbol : params.symbols()) {
				history.put(symbol, prices.daily(symbol, warmUpStart, params.to())
					.stream()
					.map(bar -> new BacktestEngine.Bar(bar.date(), bar.open().doubleValue(), bar.high().doubleValue(),
							bar.low().doubleValue(), bar.close().doubleValue()))
					.toList());
			}
			BacktestEngine.Result result = runTimer.record(() -> BacktestEngine.run(strategy, history,
					new BacktestEngine.Settings(params.from(), params.to(), params.initialCash(), params.feeBps(),
							params.slippageBps())));
			save(runId, userId, strategy.name(), result, started);
		}
		catch (RuntimeException ex) {
			log.info("Backtest {} failed: {}", runId, ex.getMessage());
			fail(runId, userId, strategy.name(), ex, started);
		}
		finally {
			running.release();
		}
	}

	private void save(UUID runId, UUID userId, String name, BacktestEngine.Result result, Instant started) {
		Map<String, Object> metrics = new LinkedHashMap<>();
		metrics.put("strategy", result.metrics());
		metrics.put("benchmark", result.benchmark());
		transaction.executeWithoutResult(status -> {
			List<BacktestEngine.Trade> trades = result.trades();
			List<Integer> positions = java.util.stream.IntStream.range(0, trades.size()).boxed().toList();
			jdbcTemplate.batchUpdate("""
					INSERT INTO backtest_trades (run_id, seq, symbol, qty, entry_date, entry_price, exit_date,
					  exit_price, pnl, return_pct, exit_reason)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""", positions, 1000, (statement, seq) -> {
				BacktestEngine.Trade trade = trades.get(seq);
				statement.setObject(1, runId);
				statement.setInt(2, seq);
				statement.setString(3, trade.symbol());
				statement.setLong(4, trade.qty());
				statement.setDate(5, Date.valueOf(trade.entryDate()));
				statement.setDouble(6, trade.entryPrice());
				statement.setDate(7, Date.valueOf(trade.exitDate()));
				statement.setDouble(8, trade.exitPrice());
				statement.setDouble(9, trade.pnl());
				statement.setDouble(10, trade.returnPct());
				statement.setString(11, trade.exitReason().name());
			});
			jdbcTemplate.batchUpdate("""
					INSERT INTO backtest_equity (run_id, day, equity, drawdown, benchmark) VALUES (?, ?, ?, ?, ?)
					""", result.equity(), 1000, (statement, point) -> {
				statement.setObject(1, runId);
				statement.setDate(2, Date.valueOf(point.date()));
				statement.setDouble(3, point.equity());
				statement.setDouble(4, point.drawdown());
				statement.setDouble(5, point.benchmark());
			});
			finish(runId, "COMPLETED", json.writeValueAsString(metrics), null, started);
			events.publishEvent(new BacktestEvent(runId, userId, name, "COMPLETED",
					result.metrics().totalReturnPct(), null));
		});
	}

	private void fail(UUID runId, UUID userId, String name, RuntimeException ex, Instant started) {
		String message = (ex instanceof ResponseStatusException status) ? status.getReason() : ex.getMessage();
		transaction.executeWithoutResult(status -> {
			finish(runId, "FAILED", null, message, started);
			events.publishEvent(new BacktestEvent(runId, userId, name, "FAILED", null, message));
		});
	}

	private void finish(UUID runId, String status, String metrics, String error, Instant started) {
		Instant now = Instant.now();
		jdbc.sql("""
				UPDATE backtest_runs SET status = :status, metrics = CAST(:metrics AS jsonb), error = :error,
				  finished_at = :now, duration_ms = :duration
				WHERE id = :id
				""")
			.param("id", runId)
			.param("status", status)
			.param("metrics", metrics)
			.param("error", error)
			.param("now", now.atOffset(ZoneOffset.UTC))
			.param("duration", now.toEpochMilli() - started.toEpochMilli())
			.update();
	}

	/** A run can't survive a restart, because it lives on a thread. Unfinished runs are marked failed. */
	@EventListener(ApplicationReadyEvent.class)
	void failInterruptedRuns() {
		int interrupted = jdbc.sql("""
				UPDATE backtest_runs SET status = 'FAILED', error = 'Interrupted by a server restart', finished_at = :now
				WHERE status IN ('QUEUED', 'RUNNING')
				""").param("now", now()).update();
		if (interrupted > 0) {
			log.info("Marked {} interrupted backtests as failed", interrupted);
		}
	}

	@PreDestroy
	void stop() {
		executor.shutdownNow();
	}

	List<Map<String, Object>> list(UUID userId) {
		return jdbc.sql("""
				SELECT id, strategy_id, strategy_name, status, params, metrics, error, created_at, duration_ms
				FROM backtest_runs WHERE user_id = :userId ORDER BY created_at DESC LIMIT 50
				""").param("userId", userId).query((rs, row) -> runRow(rs, false)).list();
	}

	Map<String, Object> get(UUID userId, UUID runId) {
		return jdbc.sql("""
				SELECT id, strategy_id, strategy_name, status, dsl, params, input_hash, metrics, error, created_at,
				  started_at, finished_at, duration_ms, explanation
				FROM backtest_runs WHERE id = :id AND user_id = :userId
				""")
			.param("id", runId)
			.param("userId", userId)
			.query((rs, row) -> runRow(rs, true))
			.optional()
			.orElseThrow(BacktestService::notFound);
	}

	/**
	 * A plain-English explanation of a completed run, written by the ai-service from the stored
	 * metrics. Generated once and kept, so asking again costs nothing.
	 */
	@SuppressWarnings("unchecked")
	Map<String, Object> explain(UUID userId, UUID runId) {
		Map<String, Object> run = get(userId, runId);
		if (run.get("explanation") != null) {
			return Map.of("explanation", run.get("explanation"));
		}
		if (!"COMPLETED".equals(run.get("status"))) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a completed backtest can be explained");
		}
		String explanation = ai.explainBacktest(userId, (Map<String, Object>) run.get("dsl"),
				(Map<String, Object>) run.get("params"), (Map<String, Object>) run.get("metrics"));
		jdbc.sql("UPDATE backtest_runs SET explanation = :text WHERE id = :id AND explanation IS NULL")
			.param("id", runId)
			.param("text", explanation)
			.update();
		return Map.of("explanation", explanation);
	}

	void delete(UUID userId, UUID runId) {
		int deleted = jdbc.sql("""
				DELETE FROM backtest_runs WHERE id = :id AND user_id = :userId AND status IN ('COMPLETED', 'FAILED')
				""").param("id", runId).param("userId", userId).update();
		if (deleted == 0) {
			get(userId, runId);
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A backtest can be deleted once it has finished");
		}
	}

	List<Map<String, Object>> trades(UUID userId, UUID runId) {
		get(userId, runId);
		return jdbc.sql("""
				SELECT symbol, qty, entry_date, entry_price, exit_date, exit_price, pnl, return_pct, exit_reason
				FROM backtest_trades WHERE run_id = :id ORDER BY seq
				""").param("id", runId).query((rs, row) -> {
			Map<String, Object> trade = new LinkedHashMap<>();
			trade.put("symbol", rs.getString("symbol"));
			trade.put("qty", rs.getLong("qty"));
			trade.put("entryDate", rs.getDate("entry_date").toLocalDate());
			trade.put("entryPrice", rs.getBigDecimal("entry_price"));
			trade.put("exitDate", rs.getDate("exit_date").toLocalDate());
			trade.put("exitPrice", rs.getBigDecimal("exit_price"));
			trade.put("pnl", rs.getBigDecimal("pnl"));
			trade.put("returnPct", rs.getBigDecimal("return_pct"));
			trade.put("exitReason", rs.getString("exit_reason"));
			return trade;
		}).list();
	}

	List<Map<String, Object>> equity(UUID userId, UUID runId) {
		get(userId, runId);
		return jdbc.sql("""
				SELECT day, equity, drawdown, benchmark FROM backtest_equity WHERE run_id = :id ORDER BY day
				""").param("id", runId).query((rs, row) -> {
			Map<String, Object> point = new LinkedHashMap<>();
			point.put("date", rs.getDate("day").toLocalDate());
			point.put("equity", rs.getBigDecimal("equity"));
			point.put("drawdown", rs.getBigDecimal("drawdown"));
			point.put("benchmark", rs.getBigDecimal("benchmark"));
			return point;
		}).list();
	}

	private Map<String, Object> runRow(java.sql.ResultSet rs, boolean full) throws java.sql.SQLException {
		Map<String, Object> run = new LinkedHashMap<>();
		run.put("id", rs.getObject("id", UUID.class));
		run.put("strategyId", rs.getObject("strategy_id", UUID.class));
		run.put("strategyName", rs.getString("strategy_name"));
		run.put("status", rs.getString("status"));
		run.put("params", json.readValue(rs.getString("params"), JSON_OBJECT));
		String metrics = rs.getString("metrics");
		run.put("metrics", (metrics != null) ? json.readValue(metrics, JSON_OBJECT) : null);
		run.put("error", rs.getString("error"));
		run.put("createdAt", rs.getObject("created_at", OffsetDateTime.class).toInstant());
		long duration = rs.getLong("duration_ms");
		run.put("durationMs", rs.wasNull() ? null : duration);
		if (full) {
			run.put("dsl", json.readValue(rs.getString("dsl"), JSON_OBJECT));
			run.put("inputHash", rs.getString("input_hash"));
			run.put("explanation", rs.getString("explanation"));
			OffsetDateTime startedAt = rs.getObject("started_at", OffsetDateTime.class);
			OffsetDateTime finishedAt = rs.getObject("finished_at", OffsetDateTime.class);
			run.put("startedAt", (startedAt != null) ? startedAt.toInstant() : null);
			run.put("finishedAt", (finishedAt != null) ? finishedAt.toInstant() : null);
		}
		return run;
	}

	/** The most bars any indicator in the strategy needs before its first value. */
	static int maxWarmUp(Strategy strategy) {
		int warmUp = maxWarmUp(strategy.entry());
		return (strategy.exit() != null) ? Math.max(warmUp, maxWarmUp(strategy.exit())) : warmUp;
	}

	private static int maxWarmUp(Condition condition) {
		return switch (condition) {
			case Condition.CrossesAbove(Operand left, Operand right) -> Math.max(warmUp(left), warmUp(right)) + 1;
			case Condition.CrossesBelow(Operand left, Operand right) -> Math.max(warmUp(left), warmUp(right)) + 1;
			case Condition.GreaterThan(Operand left, Operand right) -> Math.max(warmUp(left), warmUp(right));
			case Condition.LessThan(Operand left, Operand right) -> Math.max(warmUp(left), warmUp(right));
			case Condition.All(List<Condition> all) -> all.stream().mapToInt(BacktestService::maxWarmUp).max().orElse(0);
			case Condition.Any(List<Condition> any) -> any.stream().mapToInt(BacktestService::maxWarmUp).max().orElse(0);
			case Condition.Not(Condition inner) -> maxWarmUp(inner);
		};
	}

	private static int warmUp(Operand operand) {
		return (operand instanceof Operand.Indicator indicator) ? indicator.warmUp() : 0;
	}

	/** Trading days to calendar days, with room for weekends and holidays. */
	static int calendarDaysFor(int tradingDays) {
		return (int) Math.ceil(tradingDays * 7.0 / 5 * 1.1) + 10;
	}

	private static double orDefault(Double value, double fallback) {
		return (value != null) ? value : fallback;
	}

	private static OffsetDateTime now() {
		return Instant.now().atOffset(ZoneOffset.UTC);
	}

	private static String sha256(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "Backtest not found");
	}

}
