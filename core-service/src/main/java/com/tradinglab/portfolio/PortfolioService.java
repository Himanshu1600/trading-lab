package com.tradinglab.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.tradinglab.marketdata.MarketQuotes;
import com.tradinglab.marketdata.Quote;

/**
 * Owns cash and shares. Every change is one conditional SQL update, so two requests racing
 * can't both spend the same money: the second update matches no row and reports failure.
 */
@Service
@EnableConfigurationProperties(PortfolioProperties.class)
public class PortfolioService {

	private static final int MONEY_SCALE = 4;

	private final JdbcClient jdbc;
	private final MarketQuotes quotes;
	private final PortfolioProperties properties;
	private final TransactionTemplate transaction;

	PortfolioService(JdbcClient jdbc, MarketQuotes quotes, PortfolioProperties properties,
			TransactionTemplate transaction) {
		this.jdbc = jdbc;
		this.quotes = quotes;
		this.properties = properties;
		this.transaction = transaction;
	}

	/** Returns the user's paper account, opening it with the starting cash on first use. */
	@Transactional
	public UUID accountFor(UUID userId) {
		UUID newId = UUID.randomUUID();
		int created = jdbc.sql("""
				INSERT INTO accounts (id, user_id, cash, starting_cash, created_at)
				VALUES (:id, :userId, :cash, :cash, :now)
				ON CONFLICT (user_id) DO NOTHING
				""")
			.param("id", newId)
			.param("userId", userId)
			.param("cash", properties.startingCash())
			.param("now", now())
			.update();
		if (created == 1) {
			ledger(newId, "DEPOSIT", properties.startingCash(), null);
			return newId;
		}
		return jdbc.sql("SELECT id FROM accounts WHERE user_id = :userId")
			.param("userId", userId)
			.query(UUID.class)
			.single();
	}

	/** Sets cash aside for an open buy. Returns false if the account can't cover it. */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean reserveCash(UUID accountId, BigDecimal amount, UUID orderId) {
		int updated = jdbc.sql("""
				UPDATE accounts SET reserved_cash = reserved_cash + :amount
				WHERE id = :id AND cash - reserved_cash >= :amount
				""")
			.param("amount", amount)
			.param("id", accountId)
			.update();
		if (updated == 0) {
			return false;
		}
		ledger(accountId, "RESERVE", amount.negate(), orderId);
		return true;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void releaseCash(UUID accountId, BigDecimal amount, UUID orderId) {
		jdbc.sql("UPDATE accounts SET reserved_cash = reserved_cash - :amount WHERE id = :id")
			.param("amount", amount)
			.param("id", accountId)
			.update();
		ledger(accountId, "RELEASE", amount, orderId);
	}

	/** Sets shares aside for an open sell. Returns false if they aren't free to sell. */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean reserveShares(UUID accountId, String symbol, long qty) {
		return jdbc.sql("""
				UPDATE positions SET reserved_qty = reserved_qty + :qty
				WHERE account_id = :id AND symbol = :symbol AND qty - reserved_qty >= :qty
				""")
			.param("qty", qty)
			.param("id", accountId)
			.param("symbol", symbol)
			.update() == 1;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void releaseShares(UUID accountId, String symbol, long qty) {
		jdbc.sql("UPDATE positions SET reserved_qty = reserved_qty - :qty WHERE account_id = :id AND symbol = :symbol")
			.param("qty", qty)
			.param("id", accountId)
			.param("symbol", symbol)
			.update();
	}

	/**
	 * Pays for a filled buy and adds the shares. Returns false, changing nothing, if the price
	 * moved so far that the account can't pay, even using this order's own reservation.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean settleBuy(UUID accountId, String symbol, long qty, BigDecimal price, BigDecimal reserved,
			UUID orderId) {
		BigDecimal cost = price.multiply(BigDecimal.valueOf(qty)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
		int paid = jdbc.sql("""
				UPDATE accounts SET cash = cash - :cost, reserved_cash = reserved_cash - :reserved
				WHERE id = :id AND cash - :cost >= reserved_cash - :reserved
				""")
			.param("cost", cost)
			.param("reserved", reserved)
			.param("id", accountId)
			.update();
		if (paid == 0) {
			return false;
		}
		jdbc.sql("""
				INSERT INTO positions (account_id, symbol, qty, avg_cost) VALUES (:id, :symbol, :qty, :price)
				ON CONFLICT (account_id, symbol) DO UPDATE
				SET avg_cost = (positions.qty * positions.avg_cost + EXCLUDED.qty * EXCLUDED.avg_cost)
				               / (positions.qty + EXCLUDED.qty),
				    qty = positions.qty + EXCLUDED.qty
				""")
			.param("id", accountId)
			.param("symbol", symbol)
			.param("qty", qty)
			.param("price", price)
			.update();
		ledger(accountId, "BUY", cost.negate(), orderId);
		return true;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void settleSell(UUID accountId, String symbol, long qty, BigDecimal price, UUID orderId) {
		BigDecimal proceeds = price.multiply(BigDecimal.valueOf(qty)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
		jdbc.sql("""
				UPDATE positions
				SET realized_pnl = realized_pnl + (:price - avg_cost) * :qty,
				    reserved_qty = reserved_qty - :qty,
				    qty = qty - :qty
				WHERE account_id = :id AND symbol = :symbol
				""")
			.param("price", price)
			.param("qty", qty)
			.param("id", accountId)
			.param("symbol", symbol)
			.update();
		jdbc.sql("UPDATE accounts SET cash = cash + :proceeds WHERE id = :id")
			.param("proceeds", proceeds)
			.param("id", accountId)
			.update();
		ledger(accountId, "SELL", proceeds, orderId);
	}

	/** Reads the database in one transaction, then fetches prices after it ends, so no connection waits on Alpaca. */
	public AccountSummary summary(UUID userId) {
		Holdings holdings = transaction.execute(status -> {
			UUID accountId = accountFor(userId);
			AccountRow row = jdbc.sql("SELECT cash, reserved_cash, starting_cash FROM accounts WHERE id = :id")
				.param("id", accountId)
				.query(AccountRow.class)
				.single();
			return new Holdings(row, positionRows(accountId));
		});
		AccountRow account = holdings.account();
		List<PositionRow> rows = holdings.positions();
		List<PositionView> positions = value(rows);
		BigDecimal holdingsValue = positions.stream()
			.map(PositionView::marketValue)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal unrealized = positions.stream()
			.map(PositionView::unrealizedPnl)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal realized = rows.stream().map(PositionRow::realizedPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
		return new AccountSummary(account.cash(), account.reservedCash(), account.cash().subtract(account.reservedCash()),
				holdingsValue, account.cash().add(holdingsValue), account.startingCash(), realized, unrealized);
	}

	public List<PositionView> positions(UUID userId) {
		return value(transaction.execute(status -> positionRows(accountFor(userId))));
	}

	private List<PositionRow> positionRows(UUID accountId) {
		return jdbc.sql("""
				SELECT symbol, qty, reserved_qty, avg_cost, realized_pnl
				FROM positions WHERE account_id = :id ORDER BY symbol
				""")
			.param("id", accountId)
			.query(PositionRow.class)
			.list();
	}

	/** Holdings with no current price are valued at cost, so a data outage doesn't look like a loss. */
	private List<PositionView> value(List<PositionRow> rows) {
		List<PositionRow> held = rows.stream().filter(row -> row.qty() > 0).toList();
		Map<String, Quote> latest = quotes.latest(held.stream().map(PositionRow::symbol).toList());
		return held.stream().map(row -> {
			Quote quote = latest.get(row.symbol());
			BigDecimal last = (quote != null) ? quote.markPrice() : null;
			BigDecimal mark = (last != null) ? last : row.avgCost();
			BigDecimal qty = BigDecimal.valueOf(row.qty());
			BigDecimal marketValue = mark.multiply(qty).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
			BigDecimal unrealized = mark.subtract(row.avgCost()).multiply(qty).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
			return new PositionView(row.symbol(), row.qty(), row.qty() - row.reservedQty(), row.avgCost(), last,
					marketValue, unrealized, row.realizedPnl());
		}).toList();
	}

	private void ledger(UUID accountId, String type, BigDecimal amount, UUID orderId) {
		jdbc.sql("""
				INSERT INTO cash_ledger (id, account_id, type, amount, ref_id, created_at)
				VALUES (:id, :accountId, :type, :amount, :refId, :now)
				""")
			.param("id", UUID.randomUUID())
			.param("accountId", accountId)
			.param("type", type)
			.param("amount", amount)
			.param("refId", orderId)
			.param("now", now())
			.update();
	}

	private static OffsetDateTime now() {
		return Instant.now().atOffset(ZoneOffset.UTC);
	}

	public record AccountSummary(BigDecimal cash, BigDecimal reservedCash, BigDecimal buyingPower, BigDecimal holdingsValue,
			BigDecimal equity, BigDecimal startingCash, BigDecimal realizedPnl, BigDecimal unrealizedPnl) {
	}

	public record PositionView(String symbol, long qty, long availableQty, BigDecimal avgCost, BigDecimal last,
			BigDecimal marketValue, BigDecimal unrealizedPnl, BigDecimal realizedPnl) {
	}

	record AccountRow(BigDecimal cash, BigDecimal reservedCash, BigDecimal startingCash) {
	}

	private record Holdings(AccountRow account, List<PositionRow> positions) {
	}

	record PositionRow(String symbol, long qty, long reservedQty, BigDecimal avgCost, BigDecimal realizedPnl) {
	}

}
