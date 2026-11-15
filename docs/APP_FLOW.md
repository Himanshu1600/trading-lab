# How Trading Lab Works

A guide to what the app does today and how a request moves through it. It describes what actually exists, while [PLAN.md](../PLAN.md) describes the full goal. This file is updated with every change, and the [change log](#22-change-log) at the end lists what was added and when.

## Contents

1. [The big picture](#1-the-big-picture)
2. [The vocabulary you need](#2-the-vocabulary-you-need)
3. [What happens at startup](#3-what-happens-at-startup)
4. [Sign-up and login](#4-sign-up-and-login)
5. [Staying logged in](#5-staying-logged-in)
6. [Instruments and search](#6-instruments-and-search)
7. [Watchlists](#7-watchlists)
8. [Live prices](#8-live-prices)
9. [Paper trading](#9-paper-trading)
10. [Strategies, backtesting and AI](#10-strategies-backtesting-and-ai)
11. [Database tables](#11-database-tables)
12. [Where to find things](#12-where-to-find-things)
13. [System architecture](#13-system-architecture)
14. [Concurrency handling](#14-concurrency-handling)
15. [Failure handling](#15-failure-handling)
16. [Security model](#16-security-model)
17. [API reference](#17-api-reference)
18. [Configuration reference](#18-configuration-reference)
19. [Build, tests and CI](#19-build-tests-and-ci)
20. [Scaling limits](#20-scaling-limits)
21. [How each change gets made](#21-how-each-change-gets-made)
22. [Change log](#22-change-log)

---

## 1. The big picture

```text
Browser (Next.js, port 3000)
   │  REST calls with a JWT access token
   │  one STOMP WebSocket (/ws): live quotes and order events
   ▼
core-service (Spring Boot, port 8080)
   ├── Postgres + TimescaleDB (port 5433): users, watchlists, accounts, orders, positions
   ├── Redis (port 6379): latest quotes, shared cache
   └── Alpaca (internet): stock list, live prices and market hours, only if keys are set

ai-service (FastAPI, port 8000): drafts strategies and explains backtests with Claude,
   called only by core-service, with a shared token
```

**What works today:** sign up, log in, stay logged in, log out, search stocks, manage watchlists, see live prices and charts from live 5-minute candles to 5 years of daily history, get prices and order updates pushed live, place paper market, limit, stop and stop-limit orders as DAY or GTC, cancel them, track a portfolio, write strategies as JSON or describe them in plain English for the AI to draft, backtest them against buy-and-hold, get an AI explanation of the results, review a trading journal with AI coaching, and ask questions about a company's SEC filings with cited answers.

**What doesn't exist yet:** chart indicators and replay mode. Everything else in PLAN.md is built; see the change log for what differs from the plan.

---

## 2. The vocabulary you need

| Term | Meaning |
|---|---|
| **Controller** | Receives an HTTP request and returns a response. Holds no business rules. |
| **Service** | Holds the business rules. Controllers call services. |
| **Repository** | Reads and writes the database. Spring writes the code from the interface. |
| **Entity** | A Java class that maps to a database table. |
| **Record** | A short Java class that just carries data, used here for request and response bodies. |
| **Bean** | An object Spring creates and hands to whichever classes need it. |
| **`@Transactional`** | Everything in the method succeeds together, or is undone together. |
| **Migration** | A numbered SQL file. Flyway runs each one once, in order. Never edit one that has already run; add a new one. |
| **JWT** | A signed token that proves who you are. The server can check it without a database lookup. |

Nearly every backend request follows the same path:

```text
Security filter → Controller → Service → Repository → Database
```

---

## 3. What happens at startup

When you run `.\mvnw spring-boot:run`:

1. Spring reads `application.yml`, then your `../.env` file.
2. It checks the `auth` settings. If `JWT_SIGNING_KEY` is missing or shorter than 32 characters, startup stops with a clear error.
3. Flyway runs any new migrations (`V1` to `V6`).
4. Hibernate checks that the tables match the entity classes. It never changes them.
5. The web server starts on port 8080.
6. `InstrumentSeeder` runs. With Alpaca keys, it loads the full stock list. Without them, it keeps the 30-stock starter list and logs that. If Alpaca fails, it logs a warning and the app carries on.
7. `MatchingEngine` starts checking open orders every 3 seconds.

---

## 4. Sign-up and login

### Sign-up, step by step

```text
1. You submit the form on /register             web: auth-form.tsx
2. POST /api/v1/auth/register                   web: auth.tsx, signIn()
3. Checks name, email and password              AuthController.RegisterRequest
4. Trims the name, lowercases the email,
   hashes the password                          AuthService.register()
5. Inserts the user                             users table
6. Creates a refresh token, stores its hash     AuthService.startSession()
7. Creates a 15-minute access token             AuthService.accessToken()
8. Returns the access token in the body and
   the refresh token in a cookie                AuthController.respond()
9. Keeps the access token in memory             web: auth.tsx
```

Login is the same, except that step 4 finds the user and compares the password.

The **name** is a display name shown in the navbar and on the home page. It doesn't need to be unique, and login always uses the email. The access token doesn't contain it: the web app gets it from the login response or from `GET /api/v1/me`.

The **Show/Hide** button on password fields only switches the input between hidden and plain text in your browser. Nothing is sent to the server until you submit. Passwords can never be shown after sign-up, because only a one-way BCrypt hash is stored.

### The two tokens

| | Access token | Refresh token |
|---|---|---|
| **Used for** | Proving who you are on each API call | Getting a new access token |
| **Lifetime** | 15 minutes | 30 days |
| **Format** | Signed JWT | Random text |
| **Kept in the browser** | JavaScript memory | httpOnly cookie, which scripts can't read |
| **Kept on the server** | Not stored | Only its SHA-256 hash |

Two tokens give you a balance. A stolen access token only works briefly, and you still don't have to log in every 15 minutes.

### Security decisions

- **BCrypt hashing:** the real password is never stored, and BCrypt is deliberately slow, which makes guessing expensive.
- **Same error for wrong email and wrong password:** the message "Invalid email or password" doesn't reveal which accounts exist. Login also checks a dummy hash for unknown emails, so timing doesn't reveal it either.
- **Hashed refresh tokens:** a leaked database can't be used to log in as anyone.
- **`SameSite=Strict` cookie:** other websites can't trigger requests that carry your cookie.
- **CORS:** only the address in `WEB_ORIGIN` can call the API from a browser.

### How a protected request is checked

`SecurityConfig` sits in front of every controller:

1. It reads the `Authorization: Bearer <token>` header.
2. It checks the signature, the expiry and the issuer.
3. If any check fails, it returns `401` and your controller never runs.
4. If they pass, the controller receives the token through `@AuthenticationPrincipal Jwt`, and `jwt.getSubject()` is the user's ID.

Public URLs: health checks, Swagger and `POST /api/v1/auth/**`. Everything else needs a token.

---

## 5. Staying logged in

The access token is lost whenever the page reloads, because it lives only in memory. `AuthProvider` in `web/src/lib/auth.tsx` restores it:

```text
1. Page loads and no access token exists
2. POST /api/v1/auth/refresh (the browser attaches the cookie)
3. Server finds the token by its hash and checks it
4. Server revokes it and issues a new pair (rotation)
5. GET /api/v1/me loads your email
```

`apiFetch()` does the same when a call returns `401`: it refreshes once and retries. Every page that calls the API should use `apiFetch()`.

### Rotation and theft detection

Each refresh token works once. All the tokens from one login share a `family_id`.

```text
Normal:  A → used → B → used → C

Theft:   Attacker and victim both hold token A.
         One of them uses it first, which produces B.
         The other then presents A, which is already used.
         The server cancels the whole family, so both are logged out.
```

Two browser tabs can refresh at the same moment by accident. Reuse within 10 seconds (`refresh-reuse-grace`) is refused without cancelling the family.

**Logout** revokes the family and clears the cookie. An access token already issued keeps working until it expires, up to 15 minutes.

---

## 6. Instruments and search

An **instrument** is anything tradable, identified by its symbol, such as `AAPL`.

### Where the list comes from

- **Starter list:** migration `V3` inserts 30 well-known stocks and ETFs, so the app works without Alpaca keys.
- **Full list:** with `ALPACA_API_KEY_ID` and `ALPACA_API_SECRET_KEY` in `.env`, `InstrumentSeeder` calls Alpaca's assets API on each startup. It inserts new symbols and updates existing ones, which is called an upsert.

### Search

`GET /api/v1/instruments?query=app` needs a login and returns at most 20 tradable results, ordered like this:

1. An exact symbol match
2. Symbols that start with the query
3. Company names that contain the query

The `%` and `_` characters are escaped, so they can't act as SQL wildcards.

---

## 7. Watchlists

A user can have many watchlists, and each one holds many symbols. Watchlists live in the `marketdata` module, because they're closely tied to instruments.

| Method and URL | What it does | Success |
|---|---|---|
| `GET /api/v1/watchlists` | Lists your watchlists and their stocks | 200 |
| `POST /api/v1/watchlists` | Creates one (`{"name": "Tech"}`) | 201 |
| `DELETE /api/v1/watchlists/{id}` | Deletes one | 204 |
| `POST /api/v1/watchlists/{id}/items/{symbol}` | Adds a stock | 200 |
| `DELETE /api/v1/watchlists/{id}/items/{symbol}` | Removes a stock | 200 |

### Adding a stock, step by step

```text
1. You click a search result                    web: watchlists/page.tsx
2. POST /api/v1/watchlists/{id}/items/AAPL      web: apiFetch()
3. JWT is checked                               SecurityConfig
4. User ID is read from the token               WatchlistController.userId()
5. Finds the list by ID and user ID together    WatchlistService.find()
6. Checks the symbol exists and is tradable     WatchlistService.addSymbol()
7. Adds the symbol                              watchlist_items table
8. Returns the updated watchlist, and the
   page swaps it into view                      web: replaceList()
```

### Rules worth noticing

- **Ownership comes from the token:** the user ID is never read from the request body, so nobody can claim to be someone else.
- **Other users' lists return 404, not 403:** this avoids confirming that the list exists.
- **Adding a symbol twice is harmless:** the list keeps one copy.
- **Duplicate names return 409**, but two different users can each have a list called "Tech".

### Error format

Errors use the standard Problem Details JSON format:

```json
{ "status": 409, "title": "Conflict", "detail": "You already have a watchlist with this name" }
```

The web app shows the `detail` text.

---

## 8. Live prices

Prices come from Alpaca through one class, `AlpacaMarketData`. The rest of the app only sees two small interfaces, `MarketQuotes` and `MarketClock`, so the tests can swap in a fake market and a future data provider can replace Alpaca.

```text
Page opens
   → GET /api/v1/market/quotes?symbols=AAPL,NVDA        one REST load
   → SUBSCRIBE /topic/quotes.AAPL over the live connection
Every second, QuoteBroadcaster
   → MarketQuotes.latest(every watched symbol)
   → AlpacaMarketData → RedisQuoteCache (2-second expiry) → Alpaca on a miss
   → sends a quote only if it changed since the last one sent
```

The browser falls back to polling every 5 seconds while the live connection is down, and reloads everything once a minute even when it's up, in case a message was missed.

| Endpoint | Returns |
|---|---|
| `GET /api/v1/market/quotes?symbols=...` | Bid, ask, last trade, previous close, and the buy and sell prices below |
| `GET /api/v1/market/clock` | Whether the US market is open, and the next open and close |

### Which price is used

The free Alpaca plan uses the IEX feed, which covers only part of the market, so a bid or ask can be stale.

| Price | Normally | If it's missing or more than 2% from the last trade |
|---|---|---|
| Buy price | The ask | The last trade |
| Sell price | The bid | The last trade |

This rule lives in `Quote`, so the matching engine and the Trade page's estimate always agree.

If Alpaca fails or the keys are missing, the app treats that as "no data". Limit orders can still be placed, market buys are refused and orders wait. Brief outages of the market clock are covered by its last reading (see [Failure handling](#15-failure-handling)).

A clock outage on 2026-11-02 showed why that matters: two connection timeouts of 5 seconds each made the open market look closed, so live updates stopped and orders couldn't fill.

### The quote cache in Redis

`RedisQuoteCache` keeps each quote under `quote:<SYMBOL>` as `bid,ask,last,previousClose`, expiring after 2 seconds. Every `core-service` instance shares it, and the ai-service can read it later. Redis is only a cache: if it's down, reads and writes fall back to an in-memory copy, and the app behaves exactly as before.

Plain text is used instead of JSON so prices keep every digit, with no rounding through floating point.

### The live connection

The browser keeps one WebSocket per tab to `ws://localhost:8080/ws` and speaks STOMP over it, a small text protocol with subscribe and send frames.

```text
Browser                                core-service
CONNECT  Authorization: Bearer <JWT>  → StompAuthInterceptor checks the token
                                        and names the session after the user ID
SUBSCRIBE /topic/quotes.AAPL          → QuoteBroadcaster adds AAPL to the watched set
SUBSCRIBE /user/queue/orders          → Spring routes it to this user only
                                      ← MESSAGE quote for AAPL when it changes
                                      ← MESSAGE order event after each order change commits
```

| Destination | Sent | Body |
|---|---|---|
| `/topic/quotes.<SYMBOL>` | When that quote changes, checked every second | The same JSON as `GET /market/quotes` |
| `/user/queue/backtests` | When one of your backtests completes or fails | `BacktestEvent`: run ID, strategy name, status, total return, error |
| `/user/queue/orders` | After every order change commits | `OrderEvent`: `kind` (PLACED, TRIGGERED, FILLED, REJECTED, CANCELLED, EXPIRED), order ID, symbol, side, type, qty, status, fill price, reason, time |

Rules worth knowing:

- **Authentication:** browsers can't add headers to a WebSocket handshake, so the handshake at `/ws` is public and the token travels in the STOMP `CONNECT` frame. A missing or invalid token closes the connection.
- **Allowed subscriptions:** only `/topic/quotes.*` and the user's own `/user/queue/*`. Anything else, and any `SEND`, closes the connection.
- **Reconnects:** `@stomp/stompjs` reconnects every 3 seconds, asks for a fresh token each time, and resubscribes to everything the page still wants.
- **One loop for everyone:** ten tabs watching AAPL cost one quote lookup per second, which the cache usually answers.
- **Quote changes only:** an unchanged quote isn't sent again. A new subscriber gets the current quote on the next loop.
- **Order events after commit:** `OrderBroadcaster` uses `@TransactionalEventListener`, so a fill that rolls back is never announced.
- **Metric:** `realtime.quotes.sent` at `/actuator/prometheus` counts quote messages.

### Price history and charts

The Trade page has a full-width candlestick chart, with volume underneath. It has two kinds of bars:

| Ranges | Bar size | Shows |
|---|---|---|
| **1D** (the default) and **5D** | 5 minutes | **1D** is the most recent trading day, so on a weekend it still shows Friday. **5D** is the last 7 calendar days |
| **1M, 3M, 6M, 1Y, 5Y** | 1 day | That much history |

```text
Chart asks for GET /api/v1/market/bars?symbol=AAPL&range=1D
   → PriceHistoryService.bars()
      1. Checks the stock exists
      2. Is the stored history for this bar size still fresh?   price_bar_syncs table
         No → asks Alpaca for the missing bars, saves them        price_bars hypertable
      3. Reads the requested range from the database
   → Each bar has `time` (its New York day) and `timestamp` (its start, in Unix seconds)
```

`BarTimeframe` sets how each bar size is fetched and kept fresh:

| Setting | Daily bars | 5-minute bars |
|---|---|---|
| Alpaca feed | **SIP**, the whole US market, so volumes are real | **IEX**, real-time but a small slice of trading |
| How far behind | 16 minutes, because the free plan refuses newer SIP data | None |
| First download | 5 years, about 1,256 bars, in a few seconds | 8 days |
| Refreshed after | 1 hour | 1 minute |
| Re-fetched on refresh | The last 7 days | The last 30 minutes |

The two bar sizes are a trade-off forced by the free plan: daily bars get real volume and 5-minute bars get no delay. On 5-minute bars, volume looks small because IEX handles only a small share of trading. For one day, IEX showed 1.9M shares when the whole market traded 68M.

Other rules for both bar sizes:

- **Speed:** after the first download, ranges load from the database in about 0.1 seconds.
- **Adjustments:** prices are adjusted for splits and dividends, so old prices compare fairly with today's.
- **Extended hours:** 5-minute bars include pre-market and after-hours trading, so a 1D chart can start at 4:00 AM New York time.
- **Paging:** Alpaca returns data in pages, and the client follows them, up to 20.

### Live updates on the chart

While the market is open, the newest candle moves with the live price:

```text
Each pushed quote (the same one shown above the chart)
   → liveBar() works out the current bar's time: today, or the current 5-minute slot
      Same slot as the last bar → stretch its high or low, and set its close to the live price
      New slot                  → start a new candle that opens at the last close
   → the chart's update() redraws just that candle
Every 60 seconds (1D and 5D) the page re-reads bars from the server, which replaces the estimate with Alpaca's real bar
```

The live candle is an estimate. It only sees the quotes pushed once a second, so a brief spike between them can be missed until the next re-read. A **Live** badge shows when this is on. When the market is closed, nothing moves.

### Reading and moving the chart

| Action | How |
|---|---|
| Zoom | Mouse wheel or pinch on the chart, or the **+** and **−** buttons. **Reset** fits everything back in |
| Move | Drag the chart left or right |
| Exact prices | The line above the chart shows open, high, low and close (O H L C) for the candle under the mouse, or the newest candle otherwise |
| Full screen | **Full screen** fills the display. It's hidden where the browser doesn't support it, such as iPhone Safari |
| Times | Intraday times are New York market time, so 9:30 is the opening bell, wherever you are |
| Which stock | The stock in the address (`/trade?symbol=AAPL`) if there is one. Otherwise, the last stock you viewed on this browser, kept in `localStorage`. With neither, a placeholder asks you to search. Picking a stock in the search box changes the address, so the address always matches what's shown |

A background refresh keeps your zoom. The chart only re-fits when you pick a new stock or range.

The chart uses TradingView's Lightweight Charts library, version 5, which requires its small logo on the chart. It draws on a canvas, so it can't use CSS classes. It reads the same colour variables instead, and redraws when your system switches between light and dark mode.

**Why intraday times are shifted:** the library labels every time as if it were UTC. `marketTime()` adds New York's offset to each timestamp first, so the labels read in market time. Daylight saving is handled, because the offset comes from the browser's time-zone data for each bar.

---

## 9. Paper trading

Every user gets a paper account with $100,000, opened automatically the first time it's needed.

| Module | Job |
|---|---|
| `portfolio` | Owns cash and shares |
| `trading` | Owns orders, fills and the matching engine |
| `marketdata` | Provides prices. It knows nothing about trading |

The dependency only goes one way: `trading → portfolio → marketdata`.

### Placing a buy order, step by step

```text
1. You submit the ticket on /trade               web: trade/page.tsx
2. POST /api/v1/orders with an Idempotency-Key   web: apiFetch()
3. Checks the request                            OrderController.PlaceOrderRequest
4. Returns the earlier order if the key repeats  OrderService.place()
5. Checks the stock is tradable                  InstrumentCatalog
6. Works out how much cash to set aside          OrderService.cashToReserve()
7. Works out when a DAY order expires            OrderService.sessionClose()
8. Saves the order as OPEN                       orders table
9. Reserves the cash, or refuses with 422        PortfolioService.reserveCash()
10. Publishes a PLACED OrderEvent                sent to your browser after commit
11. Returns the order                            status 201
```

A sell follows the same steps, but step 9 reserves shares and you can't sell shares you don't own.

### Filling an order

`MatchingEngine.tick()` runs every 3 seconds:

```text
1. Loads every OPEN order
2. Stops if there are none, or the market is closed
3. Gets fresh quotes for those symbols
4. For a stop order not yet triggered: has the last trade reached the stop?
      No  → skip it this round
      Yes → OrderService.trigger() records triggered_at, and it carries on below
5. Asks OrderMatcher whether each order fills, and at what price
6. Calls OrderFiller for each match
```

A second scheduled task, `MatchingEngine.expireDayOrders()`, runs on the same interval whether or not the market is open, and expires DAY orders whose `expires_at` has passed.

`OrderFiller` then does everything in one transaction: it marks the order filled, moves the cash, updates the position, and writes the fill and ledger rows. Either all of that happens or none of it does.

| Order | Fills when | Fills at |
|---|---|---|
| Market buy | The market is open | Buy price + 0.05% slippage |
| Market sell | The market is open | Sell price − 0.05% slippage |
| Limit buy | Buy price ≤ your limit | Buy price |
| Limit sell | Sell price ≥ your limit | Sell price |
| Stop buy | Last trade rises to the stop, then fills like a market buy | Buy price + 0.05% slippage |
| Stop sell | Last trade falls to the stop, then fills like a market sell | Sell price − 0.05% slippage |
| Stop-limit buy | Last trade rises to the stop, then fills like a limit buy | Buy price, if ≤ the limit |
| Stop-limit sell | Last trade falls to the stop, then fills like a limit sell | Sell price, if ≥ the limit |

Stops are triggered by the last trade, not the bid or ask, so a thin IEX quote can't set one off on its own. Once triggered, an order stays triggered: a stop-limit that triggered and then saw the price move back keeps waiting as a limit order.

Cash set aside for buys:

| Type | Reserved |
|---|---|
| Market | Buy price × 1.02 × shares |
| Limit, stop-limit | Limit price × shares |
| Stop | Stop price × 1.02 × shares, because the fill will be near the stop |

If a stop buy gaps far above its stop and the fill would cost more than was reserved, the order becomes `REJECTED`, as with market buys.

Slippage is the small extra cost real market orders pay. It's why a new position usually starts slightly negative.

### Order statuses

```text
OPEN ──> FILLED      a price matched
     ├─> CANCELLED   you cancelled it
     ├─> EXPIRED     a DAY order reached the close of its session
     └─> REJECTED    the price jumped and the cash no longer covered it
```

An order you can't afford is refused straight away, leaving no order behind. `REJECTED` only happens at fill time, because a market or stop order's final price isn't known when it's placed. A stop being triggered isn't a status: the order stays `OPEN` and `triggeredAt` is set.

### Time in force

| Choice | Meaning |
|---|---|
| `GTC`, good till cancelled (the API default) | Stays open until it fills or you cancel it |
| `DAY` (the Trade page default) | Expires at the close of the session it belongs to |

A DAY order placed while the market is open expires at today's close. One placed after hours belongs to the next session, so it expires at that session's close. The close comes from Alpaca's clock (`nextClose`). Without a usable clock reading, it falls back to 4:00 PM New York time on the next weekday, which ignores holidays and early closes.

Expiry releases the cash or shares, just like a cancel, and the order shows "Day order expired at the close".

### How the money stays correct

- **Reservations:** placing a buy sets cash aside, and `buying power = cash − reserved cash`. Market buys reserve 2% extra, because the fill price isn't known yet.
- **One conditional update:** `PortfolioService` spends cash with a single SQL statement that only succeeds if the money is there. If two requests race, the second matches no row and is refused. A test fires 12 simultaneous orders at one account, and exactly the 3 it can afford succeed.
- **Database checks:** `CHECK` constraints reject negative cash or shares, even if the code had a bug.
- **Decimals, not floats:** money uses `BigDecimal` in Java and `NUMERIC` in Postgres, because floats can't store values like 0.10 exactly.
- **Idempotency keys:** the Trade page creates one key per order. If a request is retried, the same key returns the same order, so nothing is bought twice.
- **One status change wins:** order status only changes through `OrderRepository.transition()`, which checks the current status. A fill and a cancel at the same moment can't both succeed.
- **Audit trail:** every cash movement is written to `cash_ledger`.

### Profit and loss

```text
After a buy:   avg cost = (old qty × old avg + new qty × price) / total qty
After a sell:  realized P&L += (price − avg cost) × qty
Unrealized:    (current price − avg cost) × qty
Total value:   cash + value of all holdings
```

### Testing while the market is closed

The market is open 9:30 AM to 4:00 PM New York time on weekdays, which is usually evening in India. Outside those hours, orders wait. To see them fill anyway, add this to `.env` and restart `core-service`:

```text
TRADING_IGNORE_MARKET_HOURS=true
```

Orders then fill at the last traded price. Use it for local testing only.

### Order events and Kafka

Every order change publishes an `OrderEvent`. Inside core-service, `OrderBroadcaster` sends it to the browser. It also goes to the Kafka topic `trading.order-events`, through the transactional outbox:

```text
OrderFiller.fill() transaction
   ├─ order FILLED, cash and position settled, fill row written
   └─ Spring Modulith writes the OrderEvent into event_publication   ← same transaction
COMMIT
   → the outbox thread sends it to Kafka, keyed by account ID
   → its event_publication row is deleted once sent
Kafka down? The row stays. OutboxRelay retries rows older than a minute, every minute,
and anything left at shutdown is resent on the next start.
```

| Rule | Why |
|---|---|
| The event is stored in the same transaction as the change | A crash can't leave a fill without its event, or an event for a fill that rolled back |
| Messages are keyed by account ID | Kafka keeps one key on one partition, so one account's events stay in order |
| One outbox thread (`OrderedEventDelivery`) | Spring delivers events asynchronously, and with virtual threads each got its own thread, so sends overtook each other. The Kafka test caught an account's PLACED events arriving before its first FILLED |
| Delivery is at-least-once | A send can be retried after it actually succeeded, so consumers must ignore repeats |
| `acks=all`, idempotent producer, 5-second send timeout | The broker confirms each message, a retried send is never stored twice, and a Kafka outage fails fast into the outbox instead of blocking |
| The topic has 3 partitions | Up to three consumers in a group can share the work |

Redpanda, a Kafka-compatible broker that's lighter to run, is in `docker-compose.yml` on port 19092. `KAFKA_ENABLED=false` turns Kafka off: events still reach the browser, and the journal refreshes when it's read.

### Trading journal

The Journal page shows statistics from your fills, and an AI coach's notes on them.

```text
FILLED event on Kafka → JournalListener (consumer group core-journal)
   → JournalService.onFill(eventId, accountId), one transaction:
       INSERT event ID into journal_processed_events ON CONFLICT DO NOTHING
         0 rows → already handled, ignore it (counted as journal.events{result=duplicate})
         1 row  → recompute the statistics from every fill and upsert journal_snapshots
GET /journal → if the stored fill count differs from the real one, recompute first
```

Recording the event ID and updating the snapshot share a transaction, so an event can't be marked handled without its update. Recomputing from all fills, instead of adding one fill to running totals, makes every update idempotent and immune to events arriving out of order.

| Statistic | How |
|---|---|
| Closed trade | Each sell, measured against the position's average cost at that moment, the same rule as the portfolio |
| Win rate, average win and loss, largest win and loss | Over closed trades |
| Days held | From the buy that opened the position (from zero shares) to the sell |
| Best and worst stock | Total realized P&L per symbol, shown only if it's a gain (best) or a loss (worst) |

**Get coaching** sends only these statistics to the ai-service, where Claude Haiku 5.5 writes 3 to 5 notes about habits, such as holding losers longer than winners. Every number in the notes is checked against the statistics, as with explanations. The notes are kept until your next fill, so asking again is free. The coach needs at least one closed trade.

Handled event IDs are forgotten after a week by a daily task, since Kafka only redelivers recent messages.

### Differences from PLAN.md

This is a first version, and these parts differ on purpose:

| PLAN.md | Today | Why |
|---|---|---|
| In-memory order book per symbol, with its own thread | One loop checks open orders stored in the database | Prices arrive every few seconds, so a book adds nothing yet. A restart loses nothing either |
| Alpaca's WebSocket stream feeding the engine | The engine and the broadcaster poll Alpaca's REST snapshots, and the browser gets pushes over STOMP | One shared poll fits the free rate limit, and the browser side is already push-based |
| Optimistic locking on positions | Atomic SQL updates | One writer already prevents clashes |

---

## 10. Strategies, backtesting and AI

A strategy is a set of trading rules written as JSON. A backtest replays it over past daily prices and reports how it would have done, next to simply buying and holding the same stocks.

```text
Strategies page                          core-service
Edit JSON → Check   POST /strategies/validate → StrategyParser + tradable symbols → 200 or 400 with the field
          → Save    POST /strategies          → strategies table
          → Run     POST /backtests           → backtest_runs row (QUEUED), answers 202 at once
                                                 │ virtual thread
                                                 ▼
                                    RUNNING → HistoricalPrices.daily() per symbol (may call Alpaca)
                                            → BacktestEngine.run()  (pure, about 13 ms for 5 years)
                                            → one transaction: trades, equity curve, metrics, COMPLETED
                                            → BacktestEvent → /user/queue/backtests
Backtests page ←── live event, or a 2-second poll ── GET /backtests/{id}, /equity, /trades
```

### The strategy format (DSL v1)

```json
{
  "dslVersion": 1,
  "name": "20/50 SMA crossover",
  "symbols": ["AAPL"],
  "entry": { "crossesAbove": [{ "indicator": "SMA", "period": 20 }, { "indicator": "SMA", "period": 50 }] },
  "exit":  { "crossesBelow": [{ "indicator": "SMA", "period": 20 }, { "indicator": "SMA", "period": 50 }] },
  "risk": { "stopLossPct": 5, "takeProfitPct": 15 },
  "sizing": { "type": "percentOfEquity", "value": 100 }
}
```

| Part | Choices |
|---|---|
| Conditions | `crossesAbove`, `crossesBelow`, `gt`, `lt` (each takes two values), and `all`, `any`, `not` to combine conditions |
| Values | `{"price": "close"}` (or `open`, `high`, `low`), a number such as `30`, or an indicator |
| Indicators | `SMA`, `EMA`, `RSI`, `ATR` with a `period`; `MACD` with `fast` (12), `slow` (26), `signal` (9) and `output` = `macd`, `signal` or `histogram`; `BOLLINGER` with `period` (20), `stdDev` (2) and `output` = `upper`, `middle` or `lower` |
| Risk | `stopLossPct` and `takeProfitPct`, as percentages of the entry price |
| Sizing | `percentOfEquity`, `fixedQty` or `fixedCash`, with `maxPositions`. The default splits the equity evenly, one position per symbol |
| Symbols | 1 to 10 tradable stocks. `timeframe` is optional and can only be `1D` |

The format is defined twice, deliberately:

| Where | For | How it's kept in step |
|---|---|---|
| `contracts/strategy-dsl.v1.schema.json` | The ai-service (week 4), and anyone writing strategies | Its `examples` are parsed by `StrategyParserTests` on every build |
| `StrategyParser` in Java | The final check before anything is saved or run | Its rules are the source of truth. The schema's `$comment` lists the few rules JSON Schema can't express |

`StrategyParser` is strict on purpose. Unknown fields are errors, so a typo such as `stopLoss` instead of `stopLossPct` fails loudly instead of being ignored. Every error names its field, such as `entry.all[1].lt[0].period: must be a whole number from 1 to 500`, and the API returns that path as `field` in the 400 response. Other limits: conditions nest at most 8 deep and total at most 50; a strategy with no exit rule must have a stop-loss or a take-profit, or it would never sell; and comparing two constants is refused because it's always true or always false.

The parsed form is Java's sealed interfaces and records: `Condition` permits exactly `CrossesAbove`, `CrossesBelow`, `GreaterThan`, `LessThan`, `All`, `Any` and `Not`, and `Operand` permits `Price`, `Constant` and `Indicator`. The engine evaluates them with pattern-matching `switch`, so adding a new condition type without handling it is a compile error.

### How the engine replays a strategy

`BacktestEngine` runs one loop over trading days. For each day, for each symbol:

1. **Fill yesterday's decisions at today's open**, plus slippage. Deciding at one bar's close and filling at the next bar's open prevents look-ahead bias: the strategy never trades on a price it couldn't have known.
2. **Check stop-loss and take-profit** against today's low and high. A gap past either level fills at the open. If both are touched in the same bar, the stop-loss is assumed to come first, because daily bars don't say which happened first and that's the cautious choice.
3. **Decide at the close:** exit rules for open positions, entry rules for the rest. A match becomes an order for tomorrow's open.

Then equity is marked: cash plus every holding at its latest close.

| Rule | Detail |
|---|---|
| Warm-up | Bars before the start date are loaded so indicators are ready on day one. A 50-day average needs about 80 calendar days before the start |
| Missing values | An indicator without enough data is `NaN`, and every comparison with `NaN` is false, so warm-up bars never trigger anything |
| Crossings | `crossesAbove(a, b)`: `a ≤ b` on the previous bar and `a > b` now |
| Fees and slippage | Basis points of each trade's value. Slippage makes buys dearer and sells cheaper. Take-profits are limit orders, so they get no slippage |
| Sizing | Whole shares only, never more than the cash available |
| End of the test | Open positions are valued at the last close, as if sold there without fees, and listed as "Still open at the end" |
| Benchmark | The starting cash split equally between the symbols, each bought at its first open in the period, no fees, fractional shares |
| Numbers | `double`, not `BigDecimal`. A backtest is a simulation, and doubles make five years of bars take about 13 ms. Results are rounded to 4 decimals when stored. Paper trading, which changes real balances, keeps `BigDecimal` |

Indicators are computed once per distinct indicator, over the whole series, in `Indicators`. RSI and ATR use Wilder's smoothing, EMA starts from the simple average of its first `period` values, and Bollinger bands use the population standard deviation, all as most charting tools do. `IndicatorsTests` checks each against values worked out by hand.

### Metrics

| Metric | Definition |
|---|---|
| Total return | `end equity / start equity − 1` |
| Yearly return (CAGR) | `(end / start)^(252 / trading days) − 1` |
| Sharpe ratio | `mean(daily returns) / stdev(daily returns) × √252`, with a risk-free rate of 0 |
| Worst drop from a peak (max drawdown) | Largest `(peak − equity) / peak` |
| Winning trades | Trades with positive P&L / all trades |
| Profit factor | Gross profit / gross loss. Left empty when there were no losses, rather than shown as infinity |
| Days holding a position (exposure) | Share of days that ended with at least one position |

### Running in the background

| Rule | Why |
|---|---|
| `POST /backtests` saves a `QUEUED` row and answers 202 at once | A run may download years of prices first |
| Each run gets a virtual thread, and at most 4 compute at once (a `Semaphore`) | Cheap to start, and a burst can't exhaust the CPU or database |
| At most 3 unfinished runs per user, otherwise 429 | One user can't starve the others. The count isn't locked, so two requests at the same instant could both pass: a soft limit |
| Prices are loaded before any transaction opens | The same rule as trading: never hold a connection while waiting on the network |
| Results, trades and the equity curve are saved in one transaction with the `COMPLETED` status | A run is never seen half-saved |
| The run stores its own copy of the strategy JSON and settings | Editing or deleting the strategy never changes old results |
| `input_hash` is the SHA-256 of the strategy JSON and settings | Equal inputs give equal results, which `BacktestFlowTests` checks |
| On startup, `QUEUED` and `RUNNING` runs become `FAILED`, "Interrupted by a server restart" | A run lives on a thread, so it can't survive a restart |
| `BacktestEvent` is published in the saving transaction | `BacktestBroadcaster` sends it to `/user/queue/backtests` after commit, so the page never fetches results that aren't there yet |
| Metric `backtest.run` | A Micrometer timer of engine time, at `/actuator/prometheus` |

### Research copilot

The Research page answers questions about a company from its latest SEC filings, with every claim linked to the passage it came from.

```text
POST /api/v1/ai/copilot/ask {symbol, question}   (core-service checks the symbol is tradable)
  → ai-service /v1/copilot/ask
      first question about this stock?
        → SEC EDGAR: ticker → CIK → newest 10-K and 10-Q
        → HTML to text → cut out the sections → 600-word chunks, 80 words overlapping
        → documents and doc_chunks tables
      → full-text search: the 8 chunks that best match the question (ts_rank_cd)
      → Claude Sonnet 5.5, each chunk as its own document block with citations on
      → answer text, each piece with the passages it cites → numbered sources with links
```

| Part | Detail |
|---|---|
| Sections kept | 10-K: Item 1 Business, Item 1A Risk Factors, Item 7 MD&A. 10-Q: Part I Item 2 MD&A, Part II Item 1A Risk Factors |
| Finding a section | Each heading also appears in the table of contents, so the longest span from a heading to the next item's heading is taken: that's the body |
| Search | Postgres full-text search on a generated `tsvector` column with a GIN index, with the question's words OR-ed together. If nothing matches, the start of each section is used, so broad questions still get an answer |
| Citations | Claude's built-in citations return the exact quoted text and which document it came from. The page shows numbered markers; hovering one shows the quote, and the list below links to the filing on sec.gov |
| Safety | Passages go in document blocks, never in the instructions, and the prompt says to ignore instructions inside them. The copilot has no tools, so a hostile filing can't make it do anything |
| No advice | The prompt forbids buy, sell or hold opinions and price predictions |
| EDGAR rules | Requests carry `SEC_USER_AGENT` (an app name and contact email, which SEC requires) and pause 0.15 seconds apart, under SEC's 10 a second. Without a real `SEC_USER_AGENT` the copilot answers 503 |

Why full-text search and not embeddings: it needs no second API key, runs inside the database already in use, and handles questions that share words with the filing well. Vector search with pgvector would find paraphrased questions better, and is the natural next step.

### Pitfalls to keep in mind

- **Survivorship bias:** only today's stock list can be tested, so companies that failed or were delisted are missing, which flatters results.
- **Overfitting:** tuning rules until the past looks good rarely carries forward. Testing on one period and checking on another helps.
- **Adjusted prices:** history is adjusted for splits and dividends, so old prices may never have appeared on a real screen.
- **5 years of history:** the free data plan's limit, so runs can start at most 5 years ago.

The Backtests page shows a short version of these under every result.


### Drafting a strategy from plain English

```text
Strategies page: "Describe a strategy" → POST /api/v1/ai/strategies/draft
  core-service AiController
    1. HttpAiAssistant → POST ai-service /v1/strategy/parse   (X-Service-Token)
         ai-service: token budget check (Redis) → Claude Haiku 5.5, structured output
                     → DraftResponse → to_dsl() → DSL v1 JSON, logged in llm_calls
    2. StrategyService.validate(dsl)  ← the same Java parser as every save
         valid   → {kind: "strategy", dsl, assumptions}
         invalid → back to step 1 once, with the validator's exact error message
         still invalid → {kind: "invalid", dsl, error}: the editor shows it for a manual fix
    a vague request → {kind: "clarification", question}
Nothing is saved: the draft fills the editor, and you check and save it yourself.
```

Why the AI fills a simpler shape than DSL v1: Claude's structured outputs guarantee the answer matches a JSON Schema, but they don't support recursive schemas, and DSL v1 nests conditions to any depth. So the model fills `DraftResponse`, which allows one level of `all`/`any` around plain comparisons, and `to_dsl()` converts it. That covers what people describe in a sentence, and anything deeper can be edited by hand.

| Rule | How it's enforced |
|---|---|
| No investment advice | The system prompt turns "should I buy X" into a question about rules. The `advice_refusal` eval checks it |
| No invented stocks | Every symbol must be tradable, checked in Java. A typo or made-up ticker triggers the repair round |
| The request can't steer the model | The description is wrapped in `<request>` tags and the prompt says it describes trading rules only |
| Defaults are visible | Any number the AI chose for you, such as RSI(14) below 30, is listed under "Assumptions the AI made" |

### Explaining a backtest

**Explain these results** on a finished run calls `POST /api/v1/backtests/{id}/explain`. core-service sends the stored strategy, settings and metrics to the ai-service, which asks Claude Sonnet 5.5 for three short paragraphs.

The model only narrates. Every number comes from the engine, and the ai-service checks that each number in the text appears in the input, allowing rounding and small counts such as "3 cautions". If one doesn't, it asks once more and names the problem; a second failure returns 502 rather than a wrong number. The explanation is stored in `backtest_runs.explanation`, so asking again costs nothing.

### When AI is switched off

The AI features are always in the app; only the keys that run them are optional. Each AI section (Describe a strategy, Explain these results, Coach's notes, Research) shows a badge from `GET /api/v1/ai/status`: **AI ready**, or **AI · not configured** with the button disabled and a note naming the missing setting. core-service answers from its own token and a call to the ai-service's `GET /v1/status`, which reports only whether `ANTHROPIC_API_KEY` and `SEC_USER_AGENT` are set, never their values. If the ai-service can't be reached, the status says so instead of failing.

### The ai-service

| Part | File | Job |
|---|---|---|
| Endpoints | `app/api/routes.py` | `POST /v1/strategy/parse`, `POST /v1/backtest/explain`, each needing `X-Service-Token` |
| LLM wrapper | `app/llm/client.py` | Every Claude call: budget, logging, stop reasons, errors |
| Strategy drafting | `app/strategy/draft.py` | `DraftResponse` and `to_dsl()` |
| Explanations | `app/strategy/explain.py` | The prompt input and the number check |
| Prompts | `prompts/strategy_parse.v1.md`, `prompts/backtest_explain.v1.md` | Versioned files. The version is logged with every call and eval run |
| Evals | `evals/run_evals.py`, `evals/datasets/*.jsonl` | Run by hand, because they cost money |
| Settings | `app/config.py` | Environment variables, with `../.env` filling any gaps |

The LLM wrapper (`AnthropicLlm`) does the same things for every feature:

- **Budget:** each user gets `AI_DAILY_TOKEN_BUDGET` tokens a day (default 200,000), counted in Redis under `llm:tokens:<user>:<date>`. Past it, calls get 429 until midnight UTC.
- **Logging:** one `llm_calls` row per call with model, input, output and cache tokens, estimated cost in US dollars, latency, stop reason, prompt version and any error.
- **Stop reasons:** `refusal` becomes 422 with a friendly message, and `max_tokens` becomes 502. Nothing half-finished is ever returned.
- **Errors:** the SDK's typed exceptions decide the status: authentication, rate limit and connection problems become 503, other API errors 502. The SDK retries 429 and 5xx twice by itself.
- **Prompt caching:** the system prompt goes first with `cache_control`, so repeat calls read it from the cache. `cache_read_tokens` in `llm_calls` shows whether it's working.
- **Refusal fallback:** explanations use the API's server-side fallback (`fallbacks: "default"`), so if a safety classifier declines, the API retries on a suitable model itself.
- **Accounting never blocks:** if Redis or Postgres is down, the call still goes ahead and the problem is logged.

| Feature | Model | Effort | Why |
|---|---|---|---|
| Drafting | `claude-haiku-5-5` | low | Fast and cheap, and structured outputs keep the shape exact |
| Explaining | `claude-sonnet-5-5` | medium | Better writing, still inexpensive |

Both can be changed with `AI_STRATEGY_MODEL` and `AI_EXPLAIN_MODEL`.

### Evals

| Suite | Cases | Measures | Target |
|---|---|---|---|
| `strategy_gen` | 20 | Right kind (strategy or question), then the right symbols, indicators, numbers, risk and sizing | At least 95% kind, 85% fields |
| `advice_refusal` | 10 | "Should I buy X" never becomes a strategy | 100% |
| `explain` | 4 | Every number in the explanation is in the metrics | 100% |

Run them with `uv run python -m evals.run_evals` from `ai-service`. Each run prints a table and saves its scores to `eval_runs` with the prompt version, model and git commit, so a prompt change can be compared with the last one. PLAN.md suggests 50 strategy cases; these 20 are a starting set. **They haven't been run yet**, because that needs an API key and costs money.

---

## 11. Database tables

```text
users ──< refresh_tokens
  ├───< watchlists ──< watchlist_items >── instruments
  │
  ├──── accounts ──< orders ──< fills
  │        ├───< positions
  │        └───< cash_ledger
  ├───< strategies
  └───< backtest_runs ──< backtest_trades
                    └───< backtest_equity

price_bars, price_bar_syncs     stand alone, keyed by symbol

──<  means one-to-many
```

| Table | Holds | Added in |
|---|---|---|
| `event_publication` | Spring Modulith's event log: one row per `OrderEvent` and listener, marked complete once handled | V1 |
| `users` | Name (V4), email, password hash | V2 |
| `refresh_tokens` | Token hash, family, expiry, revocation time | V2 |
| `instruments` | Symbol, name, exchange, tradable flag | V3 |
| `watchlists` | A user's named lists | V3 |
| `watchlist_items` | Which symbols are in which list | V3 |
| `accounts` | One paper account per user: cash, reserved cash, starting cash | V5 |
| `cash_ledger` | Every cash movement | V5 |
| `positions` | Shares held, reserved shares, average cost, realized P&L | V5 |
| `orders` | Every accepted order and its status | V5 |
| `fills` | Each completed trade | V5 |
| `price_bars` | Daily prices: open, high, low, close and volume. A TimescaleDB hypertable | V6 |
| `price_bar_syncs` | When each stock's history was last refreshed | V6 |
| `orders` columns `stop_price`, `triggered_at`, `time_in_force`, `expires_at` | Stop orders and DAY expiry. Older orders became `GTC` | V7 |
| `strategies` | A user's saved strategies: name, the JSON (`jsonb`), DSL version, and the prompt if the AI drafted it | V8 |
| `backtest_runs` | Each run: its own copy of the strategy and settings, `input_hash`, status, metrics (`jsonb`), error and timings | V8 |
| `backtest_trades` | Every simulated trade of a run | V8 |
| `backtest_equity` | One row per trading day: equity, drawdown and the buy-and-hold value | V8 |
| `llm_calls` | Every Claude call: feature, model, prompt version, tokens, cost, latency, stop reason, error. Written by the ai-service | V9 |
| `eval_runs` | Scores from each eval run, with prompt version, model and git commit | V9 |
| `journal_snapshots` | One row per account: statistics, the fill count they cover, and the coach's notes | V10 |
| `journal_processed_events` | Kafka event IDs already handled, kept a week | V10 |
| `documents` | SEC filings fetched for the copilot: ticker, form, date, link | V11 |
| `doc_chunks` | Filing sections in chunks, with a generated full-text `tsvector` and a GIN index | V11 |
| `backtest_runs.explanation` | The stored AI explanation of a run | V9 |

`price_bars` is split into one chunk per year of data. A query for the last month reads only the newest chunk, not all five years. The primary key is `(symbol, timeframe, ts)`, so fetching the same day twice updates the existing row. It deliberately has no foreign key to `instruments`, keeping bulk inserts simple.

Deleting a user also deletes their tokens, watchlists, account, orders, strategies and backtests, through `ON DELETE CASCADE`. Deleting a strategy keeps its backtests: `backtest_runs.strategy_id` becomes null, and the run's own copy of the JSON stays.

To look inside, run this from the project root:

```powershell
docker compose exec timescaledb psql -U tradinglab -d tradinglab
```

---

## 12. Where to find things

### Backend: `core-service/src/main/java/com/tradinglab/`

| File | Job |
|---|---|
| `auth/SecurityConfig` | Public and protected URLs, JWT setup, CORS |
| `auth/AuthController` | Auth endpoints and the refresh cookie |
| `auth/AuthService` | Sign-up, login, refresh and logout rules |
| `auth/AuthProperties` | Auth settings from `application.yml` |
| `auth/User`, `auth/RefreshToken` | Entities |
| `marketdata/InstrumentController` | Stock search |
| `marketdata/AlpacaAssetsClient` | Calls Alpaca |
| `marketdata/InstrumentSeeder` | Loads the stock list at startup |
| `marketdata/WatchlistController` | Watchlist endpoints |
| `marketdata/WatchlistService` | Watchlist rules and ownership checks |
| `marketdata/Instrument`, `marketdata/Watchlist` | Entities |
| `marketdata/Quote` | One stock's prices, and the buy and sell price rule |
| `marketdata/MarketQuotes`, `marketdata/MarketClock` | Interfaces other modules use for prices and hours |
| `marketdata/AlpacaMarketData` | Fetches prices and market hours from Alpaca |
| `marketdata/QuoteCache`, `marketdata/RedisQuoteCache` | The 2-second quote cache, in Redis with an in-memory fallback |
| `marketdata/MarketController` | Price, history and market-hours endpoints |
| `marketdata/PriceHistoryService` | Serves daily history and refreshes it from Alpaca |
| `marketdata/PriceHistorySource`, `marketdata/PriceBar` | The history interface Alpaca implements, and one bar's prices |
| `marketdata/BarTimeframe` | Daily and 5-minute bars, and how each is fetched and refreshed |
| `marketdata/InstrumentCatalog` | Lets other modules check that a symbol is tradable |
| `portfolio/PortfolioService` | Cash, shares, reservations, settlement and P&L |
| `portfolio/PortfolioController` | Account and positions endpoints |
| `trading/OrderController` | Order and fill endpoints |
| `trading/OrderService` | Placing, listing, cancelling, triggering and expiring orders |
| `trading/MatchingEngine` | The 3-second loops: fills open orders, expires DAY orders |
| `trading/OrderMatcher` | Decides if a stop triggers, and if an order fills and at what price |
| `trading/OrderFiller` | Applies one fill in one transaction |
| `trading/OrderEvent` | Published on every order change. The trading module's public API |
| `trading/PaperOrder`, `trading/Fill` | Entities |
| `realtime/WebSocketConfig` | The `/ws` STOMP endpoint and its in-memory broker |
| `realtime/StompAuthInterceptor` | Checks the JWT on CONNECT and limits subscriptions |
| `realtime/QuoteBroadcaster` | Tracks watched symbols and pushes changed quotes every second |
| `realtime/OrderBroadcaster` | Sends each committed `OrderEvent` to the order's owner |
| `realtime/BacktestBroadcaster` | Sends each committed `BacktestEvent` to the run's owner |
| `marketdata/HistoricalPrices` | Daily history for other modules, implemented by `PriceHistoryService` |
| `strategy/Strategy`, `strategy/Condition`, `strategy/Operand` | The parsed strategy: records and sealed interfaces |
| `strategy/StrategyParser` | Turns strategy JSON into a `Strategy`, or throws `InvalidStrategyException` with the field's path |
| `strategy/StrategyService` | Saving, listing, updating and deleting strategies, with validation |
| `strategy/StrategyController` | Strategy endpoints, and 400s that name the field |
| `backtest/Indicators` | SMA, EMA, RSI, ATR, MACD and Bollinger bands over a whole series |
| `backtest/BacktestEngine` | Replays a strategy day by day, and computes the metrics and benchmark |
| `backtest/BacktestService` | Queues runs, runs them on virtual threads, saves results, recovers after a restart |
| `backtest/BacktestController` | Backtest endpoints |
| `backtest/BacktestEvent` | Published when a run completes or fails. The backtest module's public API |
| `ai/AiAssistant` | The ai-service as other modules see it. The ai module's public API |
| `ai/HttpAiAssistant` | Calls the ai-service with the service token and passes its status and message through |
| `ai/AiController` | `POST /ai/strategies/draft`: draft, validate, repair once |
| `ai/AiProperties` | `ai.service-url` and `ai.service-token` |

Migrations are in `core-service/src/main/resources/db/migration/`. The shared strategy schema is `contracts/strategy-dsl.v1.schema.json`.

### Web: `web/src/`

| File | Job |
|---|---|
| `lib/auth.tsx` | Tokens, `apiFetch()`, `useAuth()`, `useRequireAuth()` |
| `lib/market.ts` | Live price and market-status hooks, money formatting |
| `lib/realtime.ts` | The shared STOMP connection: `useLiveQuotes()`, `useOrderEvents()`, `useBacktestEvents()`, `useRealtimeConnected()` |
| `lib/backtests.ts` | Strategy and backtest types, editor templates, percentage formatting |
| `components/equity-chart.tsx` | Equity against buy-and-hold, with a drawdown pane |
| `app/journal/page.tsx` | Trading statistics and the coach's notes, refreshed when a fill arrives |
| `app/research/page.tsx` | Ask about a stock; the answer with numbered citations and links to the filings |
| `e2e/smoke.spec.ts`, `playwright.config.ts` | Browser smoke test: sign up, trade, cancel, save a strategy, backtest |
| `app/strategies/page.tsx` | Strategy list, JSON editor with templates, Check, Save, Delete, and the run form |
| `app/backtests/page.tsx` | Run list, and one run's results at `?run=<id>`: metrics, chart, trades |
| `components/app-header.tsx` | Top navigation bar, shown on every page |
| `components/auth-form.tsx` | Shared login and sign-up form |
| `components/symbol-search.tsx` | Stock search box, used on Watchlists and Trade |
| `components/market-status.tsx` | Market open or closed badge |
| `components/price-chart.tsx` | Candlestick and volume chart: ranges, live candle, zoom, price readout, full screen |
| `app/globals.css` | Colours for light and dark mode, plus shared styles |
| `app/page.tsx` | Home page |
| `app/login`, `app/register` | Auth pages |
| `app/watchlists/page.tsx` | Watchlists with live prices |
| `app/trade/page.tsx` | Order ticket |
| `app/portfolio/page.tsx` | Account totals, positions, open orders, order history |

### How the styling works

All colours are defined once as variables at the top of `app/globals.css`, with one set for light mode and one for dark mode. The browser picks the set from your system setting, so components never name a colour directly. To change the green accent everywhere, edit `--accent`.

Reusable styles live in the same file, so buttons and inputs match on every page:

| Class | Used for |
|---|---|
| `btn` plus `btn-primary`, `btn-secondary` or `btn-ghost` | Buttons, from most to least prominent |
| `btn-danger`, `btn-danger-ghost` | Destructive actions, such as delete and remove |
| `btn-sm` | The smaller button size |
| `input` | Text fields |
| `card` | Bordered white panels |
| `alert` | Error messages |

Everything else uses Tailwind utility classes directly in the component.

### Tests: `core-service/src/test/java/com/tradinglab/`

| File | Covers |
|---|---|
| `auth/AuthFlowTests` | Sign-up, login, rotation, replay, logout, CORS |
| `marketdata/WatchlistFlowTests` | Search, seeding, the watchlist lifecycle, isolation between users |
| `marketdata/AlpacaAssetsClientTests` | The Alpaca stock list request, with a fake server |
| `marketdata/AlpacaMarketDataTests` | Quote, clock and history parsing, paging, caching, failures, and clock outages, using a controllable clock |
| `marketdata/PriceHistoryTests` | Daily and 5-minute history: caching, refreshes, ranges, failures and the hypertable |
| `marketdata/QuoteTests` | The buy and sell price rule, including stale quotes |
| `marketdata/RedisQuoteCacheTests` | Quotes stored in real Redis, their expiry, and exact decimal round trips |
| `trading/OrderMatcherTests` | When stops trigger, when orders fill and at what price, and DAY-order close times |
| `trading/TradingFlowTests` | Orders, stops, stop-limits, DAY expiry, fills, P&L, cancels, retries, races, and isolation between users |
| `realtime/RealtimeTests` | A real STOMP client: quote pushes, private order events, refused connections and subscriptions |
| `strategy/StrategyParserTests` | The schema's examples parse, the tree is right, defaults, and errors name the right field |
| `backtest/IndicatorsTests` | Each indicator against hand-worked values |
| `backtest/BacktestEngineTests` | The golden scenario worked out by hand, stops, targets, gaps, fees, slippage, sizing, determinism, and the 5-year speed check |
| `backtest/BacktestFlowTests` | Strategies and runs through the API: results, editing, errors, missing prices, isolation, deletion, dates |
| `ai/HttpAiAssistantTests` | The service token is sent, the ai-service's errors pass through, and AI is off without a token |
| `ai/AiFlowTests` | Drafts, the repair round, questions, outages, explanations made once and reused, and copilot symbol checks, with a fake ai-service |
| `common/RateLimitTests` | Password guessing cut off per address, reads never limited, the limiter's counting |
| `journal/JournalCalculatorTests` | Statistics against hand-worked trades: average cost, holding time, best and worst stock |
| `trading/KafkaJournalFlowTests` | A real Redpanda broker: fills reach Kafka in order and keyed by account, the journal updates, a repeated message changes nothing, the outbox empties |

The ai-service has its own tests in `ai-service/tests/`: `test_api.py` covers the endpoints with a fake model, and `test_units.py` covers the token budget, cost, the DSL conversion and the number check. Run them with `uv run pytest`.
| `ModularityTests` | Module boundaries |

Run them all with `.\mvnw verify`. The flow tests need Docker.

---

## 13. System architecture

Sections 12 to 19 describe how the system is built today. [PLAN.md](../PLAN.md) describes where it's heading.

### Runtime components

| Component | Technology | Port | Runs as | State |
|---|---|---|---|---|
| `web` | Next.js 16, React 19, TypeScript, Tailwind 4 | 3000 | Node process on your PC | Access token in browser memory only |
| `core-service` | Java 21, Spring Boot 4.1, Spring Modulith | 8080 | JVM process on your PC | Market clock cache, WebSocket sessions |
| `ai-service` | Python 3.12, FastAPI, Anthropic SDK | 8000 | Python process on your PC | None. Reads and writes Redis and Postgres for budgets and logs |
| `timescaledb` | PostgreSQL 17, TimescaleDB, pgvector | 5433 | Docker container | All durable data |
| `redis` | Redis 8 | 6379 | Docker container | Latest quotes, rate-limit counters, AI token budgets |
| `redpanda` | Redpanda (Kafka API) | 19092 | Docker container | The `trading.order-events` topic |
| `prometheus`, `grafana` | Prometheus 3, Grafana 12 | 9090, 3001 | Docker containers, optional profile | Metrics history and the "Trading Lab" dashboard |
| Alpaca | External API | HTTPS | Internet | Stock list, prices, market hours |

For development, `docker-compose.yml` runs only the infrastructure, and the three services run directly on your PC, so code changes don't need image rebuilds. For a server, `docker-compose.prod.yml` runs everything in containers (see [Deployment](#deployment)).

### Architecture style: a modular monolith

`core-service` is one deployable application split into modules with enforced boundaries. Separate microservices would add network calls, deployments and distributed transactions, which one developer doesn't need yet. Here, placing an order and moving cash can share a single database transaction.

Each direct sub-package of `com.tradinglab` is a module. Classes in a module's root package that are `public` form its API, and everything else is private to it. `ModularityTests` fails the build if a module reaches into another's internals, or if modules depend on each other in a cycle.

| Module | Status | Public API | Depends on |
|---|---|---|---|
| `auth` | Built | None. Other modules read the user ID from the JWT | Nothing |
| `marketdata` | Built | `Quote`, `MarketQuotes`, `MarketClock`, `InstrumentCatalog` | Nothing |
| `portfolio` | Built | `PortfolioService` | `marketdata` |
| `trading` | Built | `OrderEvent`, `TradeHistory` | `portfolio`, `marketdata` |
| `realtime` | Built | None | `marketdata`, `portfolio`, `trading`, `backtest` |
| `strategy` | Built | `Strategy`, `Condition`, `Operand`, `StrategyParser`, `StrategyService`, `InvalidStrategyException` | `marketdata` |
| `backtest` | Built | `BacktestEvent` | `strategy`, `marketdata`, `ai` |
| `ai` | Built | `AiAssistant` | `strategy`, `marketdata` |
| `journal` | Built | None | `trading`, `portfolio`, `ai` |
| `common` | Built, open to all | `RateLimiter`; also the rate-limit interceptor, outbox relay and ordered event delivery | Nothing |

`realtime` only listens. Nothing depends on it, so the trading rules work the same with no browser connected.

`auth` being independent has a useful effect: no other module queries `users`. They only need the ID inside the token. The database foreign keys still protect integrity.

### Inside one request

```text
Browser
  │  HTTP + Authorization: Bearer <JWT>
  ▼
Embedded web server: gives the request its own virtual thread
  ▼
Security filters: CORS, then JWT signature, expiry and issuer
  ▼
Spring MVC: matches the URL, parses JSON, runs validation
  ▼
Controller: reads the user ID, calls a service
  ▼
Service: @Transactional boundary, business rules
  ▼
Repository or JdbcClient: one connection from the pool
  ▼
PostgreSQL
```

Errors thrown anywhere on this path become Problem Details JSON, through `spring.mvc.problemdetails.enabled`.

### Key design decisions

| Decision | Why | Trade-off |
|---|---|---|
| Modular monolith | One transaction across modules, one deployment | Scales as one unit |
| One Postgres for everything | Relational data, time-series and vectors in one system, with one backup | Heavy load on one part affects the rest |
| Flyway owns the schema, and Hibernate only validates (`ddl-auto: validate`) | Every database change is reviewed SQL | Each change needs a migration |
| JPA for simple entities, `JdbcClient` in `portfolio` | Money needs exact conditional SQL that JPA hides | Two data-access styles |
| Browser talks only to `core-service` | One place for login and rate limits. Alpaca and Anthropic keys never reach the browser | `core-service` must proxy AI calls later |
| Server polls Alpaca once, browsers get pushes | One poll serves every tab, and fits the free plan | Prices are up to about 3 seconds old |
| Events between modules | `trading` doesn't know who listens, and listeners only hear about committed changes | One more indirection to follow when reading the code |
| Open orders stored in the database | A restart loses nothing, and no recovery code is needed | Each check costs a query |
| Interfaces around Alpaca | Tests use a fake market, and providers can change | Slightly more code |
| LLMs will write text, code does the maths (planned) | LLMs are unreliable at arithmetic | Needs a strategy format that Java can run, which now exists |
| A JSON strategy format, not code | Safe to store and run, easy to validate, and an LLM can produce it reliably | Only what the format can express can be tested |
| Backtests on virtual threads, results by event | The request returns at once, and the page updates itself | Runs don't survive a restart |

### Data ownership

| Data | Owner | Rule |
|---|---|---|
| `users`, `refresh_tokens` | `auth` | Passwords and tokens are stored only as hashes |
| `instruments`, `watchlists`, `watchlist_items` | `marketdata` | `instruments` is written only by migrations and `InstrumentSeeder` |
| `price_bars`, `price_bar_syncs` | `marketdata` | Written only by `PriceHistoryService` |
| `accounts`, `cash_ledger`, `positions` | `portfolio` | All writes go through `PortfolioService` |
| `orders`, `fills` | `trading` | Status changes only through `transition()`, and fills only through `OrderFiller` |
| `strategies` | `strategy` | Every write re-validates the JSON |
| `backtest_runs`, `backtest_trades`, `backtest_equity` | `backtest` | Results are written once, in one transaction, and never changed. Only `explanation` is added later |
| `llm_calls`, `eval_runs` | ai-service | Append-only logs. Flyway in core-service still owns the schema |

All timestamps are stored in UTC, and the browser converts them to your time zone. Money uses `NUMERIC(18, 4)`, and average cost uses `NUMERIC(18, 6)` to reduce rounding drift.

### The AI service today

`ai-service` drafts strategies, explains backtests, coaches from the journal and answers research questions (see [Strategies, backtesting and AI](#10-strategies-backtesting-and-ai) and [Paper trading](#9-paper-trading)). Only core-service calls it, with a shared token; the browser never does.

### Monitoring

core-service publishes metrics at `/actuator/prometheus`. `docker compose --profile observability up -d` starts Prometheus, which reads them every 15 seconds, and Grafana at http://localhost:3001 with a ready-made "Trading Lab" dashboard:

| Panel | Metric |
|---|---|
| Requests per second, p95 and p99 response time, 5xx and 429 rates | `http.server.requests`, with percentile histograms on |
| Fills and rejections per minute | `trading.fills` (by side), `trading.rejections` |
| Quote pushes | `realtime.quotes.sent` |
| Backtest engine time | `backtest.run` |
| Journal events, handled and duplicate | `journal.events` |
| JVM heap, database connections in use and waiting | Built-in JVM and HikariCP metrics |

The AI service's cost and latency are in the `llm_calls` table rather than Prometheus, so they can be summed per user and per feature with SQL.

### Deployment

`docker-compose.prod.yml` runs the whole app on one server:

```text
Internet → Caddy :443 (automatic HTTPS for DOMAIN)
             /api/*, /ws* → core-service:8080
             everything else → web:3000
           Private network: timescaledb, redis, redpanda, ai-service
```

| Choice | Why |
|---|---|
| One domain for web and API | The refresh cookie is `SameSite=Strict`, so it only works when both share a site |
| Only Caddy publishes ports | The database, Redis, Kafka, the AI service and `/actuator` aren't reachable from outside |
| `SERVER_FORWARD_HEADERS_STRATEGY=framework` | Rate limits see the visitor's address from `X-Forwarded-For`, not Caddy's |
| Multi-stage Dockerfiles, non-root users | Small runtime images without build tools, and a compromised process can't write to the image |
| `NEXT_PUBLIC_API_URL` as a build argument | Next.js bakes public variables into the browser code at build time |

The images and Compose file are written and checked with `docker compose config`, but nothing has been deployed to a real server yet.

### Unused pieces

These are installed but do nothing yet, so don't assume otherwise:

- **TimescaleDB extras:** `price_bars` is a hypertable, but continuous aggregates and compression aren't used yet.
- **pgvector:** enabled, but no vector column exists. The copilot uses full-text search.

---

## 14. Concurrency handling

### The threads in the system

| Thread | Does | Runs how many at once |
|---|---|---|
| Virtual thread per HTTP request | Everything a request does | Many |
| Outbox thread (`outbox-1`) | Sends committed events to Kafka, in the order they were handed over | One |
| Kafka consumer thread | `JournalListener`, one partition's messages in order | One per instance, up to 3 per group |
| Daily cleanups | Expired refresh tokens, old journal event IDs | Scheduler thread |
| Scheduled matching task | `MatchingEngine.tick()` | One. `fixedDelay` waits for each run to finish |
| Scheduled expiry task | `MatchingEngine.expireDayOrders()` | One, on the same scheduler thread, so it never overlaps `tick()` |
| Scheduled quote broadcast | `QuoteBroadcaster.broadcast()` | One, every second |
| Backtest runs | `BacktestService.execute()` | One virtual thread per run, at most 4 computing at once |
| WebSocket inbound and outbound channels | STOMP frames in and out | A small pool. Each session's frames stay in order |
| Main thread at startup | `InstrumentSeeder` | One |

**Virtual threads** (`spring.threads.virtual.enabled: true`) are lightweight Java 21 threads. A request waiting on the database or Alpaca frees its underlying OS thread, so waiting is cheap. They don't make the database any faster: at most 10 requests can hold a connection at once, which is the pool's default size, and the rest queue.

### The rule: let Postgres decide

The code uses no Java locks, no `synchronized`, no `SELECT FOR UPDATE` and no `@Version`. It relies on three database guarantees, at Postgres's default `READ COMMITTED` isolation:

1. **Conditional updates.** An `UPDATE ... WHERE <rule>` takes a row lock. A second transaction on that row waits, then **re-checks the `WHERE` against the committed value**. Check and change act as one step.
2. **Unique constraints.** A second insert with the same key waits for the first to finish, then fails if it committed.
3. **`CHECK` constraints.** A final barrier if any logic is wrong.

The tell-tale pattern is that a method's result depends on the number of rows updated: 1 means you won, and 0 means you lost.

### Why not read, check, then write?

This is the classic bug, and the design avoids it.

```text
Unsafe                              Safe, as used here
A reads: buying power $30,000       A: UPDATE ... WHERE cash - reserved >= 30000
B reads: buying power $30,000          → 1 row, holds the row lock
A: enough, so reserves it           B: same UPDATE waits for the lock
B: enough, so reserves it           A commits
Result: $60,000 reserved            B re-checks: 0 → 0 rows → refused
```

### Every race, and what stops it

| Race | Protection | Where |
|---|---|---|
| Two buys spend the same cash | Conditional update on buying power | `PortfolioService.reserveCash()` |
| Two sells use the same shares | Conditional update on `qty - reserved_qty` | `PortfolioService.reserveShares()` |
| A retried or double-clicked order | `UNIQUE (account_id, client_order_id)` | `OrderRepository`, `OrderController.place()` |
| A fill and a cancel at the same moment | Conditional status change: only one finds it `OPEN` | `OrderRepository.transition()` |
| A fill and a DAY expiry at the same moment | The same conditional status change | `OrderService.expire()` |
| A stop triggered twice | `UPDATE ... WHERE triggered_at IS NULL AND status = 'OPEN'` | `OrderRepository.markTriggered()` |
| Paying more than the account holds at fill time | Conditional update on cash | `PortfolioService.settleBuy()` |
| First-use account created twice | `INSERT ... ON CONFLICT DO NOTHING` on unique `user_id` | `PortfolioService.accountFor()` |
| Two buys of one symbol skew the average cost | Single atomic upsert | `PortfolioService.settleBuy()` |
| Two sign-ups with one email | Unique `email` | `AuthService.register()` |
| A refresh token used twice | Conditional revoke | `RefreshTokenRepository.revoke()` |
| Two watchlists with one name | `UNIQUE (user_id, name)` | `WatchlistService.create()` |
| Two tabs refreshing at once | 10-second grace period on the server, one shared promise per tab | `AuthService.refresh()`, `web/src/lib/auth.tsx` |
| Two backtests starting at once past the per-user limit | None: the count isn't locked, so both may start. A soft limit | `BacktestService.start()` |
| Two requests refreshing one stock's history | Both may fetch, and the upsert makes writing the same days twice harmless. One Alpaca call is wasted | `PriceHistoryService.refreshIfStale()` |

### Example: two retries of one order

```text
Request A and B carry the same Idempotency-Key.
Both look it up and find nothing.
A inserts the order.
B's insert waits on the unique index.
A commits.
B's insert fails, its transaction rolls back, and nothing was reserved twice.
OrderController catches the failure and returns A's order to B.
```

That last step, `OrderService.findPlaced()`, used to run in a read-only transaction. It calls `accountFor()`, which runs `INSERT ... ON CONFLICT DO NOTHING`, and Postgres refuses any `INSERT` in a read-only transaction, even one that would do nothing. The loser of the race therefore got a 500 error instead of the order. The concurrent-retry test caught it on 2026-11-02, and it's fixed.

If A was refused and rolled back instead, B's insert simply goes through and B is judged on its own.

### Example: a fill racing a cancel

```text
tick() loads order X as OPEN.
You click Cancel.
Cancel: UPDATE status = CANCELLED WHERE status = OPEN → 1 row, releases the cash.
tick(): UPDATE status = FILLED WHERE status = OPEN → 0 rows, so it stops.
```

The engine works from a list that can already be out of date, and that's safe because `transition()` re-checks at write time. The reverse order works too: the cancel gets 0 rows and returns 409.

### The single writer

Only `MatchingEngine` creates fills, on one thread, one order at a time, and each fill is its own transaction. This gives three benefits:

- Orders fill oldest first, because the list is sorted by `created_at`.
- Settlements never compete with each other.
- A failure on one order doesn't undo the others.

`tick()` itself has no transaction. So it holds no database connection while waiting on Alpaca.

### Never wait on the network inside a transaction

A transaction holds one of the 10 database connections until it ends. If it waited on a slow Alpaca response, that connection would sit idle for up to 30 seconds, and a few slow calls could block every other request. So every network call happens outside a transaction:

| Operation | Order of work |
|---|---|
| Placing an order | Check the stock and price a market buy using Alpaca, **then** open the transaction that saves the order and reserves cash |
| Account and positions | Read the database in one short transaction, **then** fetch prices and value the holdings |
| Matching | `tick()` fetches quotes with no transaction, and each fill opens its own |
| Price history | Fetch from Alpaca first, then save. No transaction is open during the fetch |

These use `TransactionTemplate`, which marks exactly where a transaction begins and ends inside a method. `@Transactional` would wrap the whole method, network call included. A test fails if any price is fetched while a transaction is active.

### Transactions

| Method | Propagation | Meaning |
|---|---|---|
| `OrderService.place()` | `TransactionTemplate` around the save and reservation | Order and reservation commit or roll back together, after pricing |
| `OrderService.cancel()` | `REQUIRED` | Status change and release commit together |
| `OrderFiller.fill()` | `REQUIRED` | The whole fill is one unit |
| `reserveCash`, `settleBuy` and other `PortfolioService` changes | `MANDATORY` | They throw an error if called outside a transaction, so a half-finished money change can't happen by accident |
| `AuthService.refresh()` | `noRollbackFor = AuthException` | Keeps the family revocation even though the request fails |

A refused order throws an exception, so its order row rolls back too. That's why refused orders leave no trace.

### Why deadlocks can't happen today

A deadlock needs two transactions taking the same locks in opposite order.

| Operation | Row locks, in order |
|---|---|
| Place a buy | new order, `accounts` |
| Place a sell | new order, `positions` |
| Cancel a buy | `orders`, `accounts` |
| Cancel a sell | `orders`, `positions` |
| Fill a buy | `orders`, `accounts`, `positions` |
| Fill a sell | `orders`, `positions`, `accounts` |

Placing and cancelling each lock just one shared row. The fills are the only operations taking `accounts` and `positions` together, in opposite orders, and the single writer means two fills never run at once. If a second matching engine ever runs, this stops being true (see [Scaling limits](#20-scaling-limits)).

Inserting an order also takes a weak lock on its `accounts` row for the foreign key. That lock doesn't conflict with balance updates, because they don't change the key column.

### Shared memory

| State | Protection | If two threads collide |
|---|---|---|
| Quote cache | Redis, or a `ConcurrentHashMap` when Redis is down | Both may call Alpaca, wasting one request. The last write wins |
| Watched symbols and last-sent quotes in `QuoteBroadcaster` | `ConcurrentHashMap` and concurrent sets | Subscribe events and the broadcast loop run on different threads. The worst case is one extra or one skipped send, fixed by the next loop |
| Market clock cache and retry time | `volatile` fields, holding immutable values | Same. Harmless |
| Rate-limit counters | Redis `INCR`, atomic across every instance | Two requests can't both take the last slot |
| Controllers and services | Hold no per-request state | Nothing to protect |

Caches aren't locked, because a duplicate fetch is harmless and a lock would make every reader wait on a slow network call.

### Concurrency in the browser

- **Refresh:** concurrent callers share one in-flight promise, because the server rotates the token on each use.
- **Search:** typing waits 250 ms, and a `stale` flag discards older responses that arrive late.
- **Live connection:** one STOMP client per tab, shared by every hook in `lib/realtime.ts`. Each hook adds a listener; the server subscription is dropped when the last listener leaves.
- **Polling:** timers stop when you leave a page. With the live connection up, quotes reload every 60 seconds and the portfolio every 15. Without it, both fall back to every 5 seconds. The market clock reloads every 30.
- **Orders:** the idempotency key lives in a `ref` and is kept if the network fails, so a retry can't buy twice.
- **Chart feedback loop:** moving the live candle makes the chart call its hover callback, which updates React state, and that once moved the candle again in an endless loop. It was caught in a real browser on 2026-11-02 as "Maximum update depth exceeded". Now the hover state only changes when the hovered prices really differ, and the live candle is recalculated only when a new price arrives (`useMemo`).
- **Background chart refresh:** the 60-second re-read keeps the chart that's already showing if it fails, rather than blanking it.

### Pages stay alive after you leave them

The web app turns on Next.js's `cacheComponents`. With it, Next.js doesn't remove a page when you navigate away. It hides it with React's `<Activity>` and brings it back later **with its old state**, for up to 3 recently visited pages. Effects stop while a page is hidden and start again when it's shown, so polling and data loading resume by themselves.

This keeps typed input and scroll position, but it breaks any page that reads the address only once:

```text
Bug, 2026-11-02:
1. Watchlists → Trade AAPL   Trade page created, stock = AAPL, kept in React state
2. Watchlists → Trade NVDA   The address changes to ?symbol=NVDA, but the hidden Trade page
                             comes back with its old state, so it still shows AAPL
```

The fix makes the address the single source of truth:

- **`TradeRoute`** reads the stock and side from the address, and renders `TradeTicket` with `key={address}`. When the address changes, React treats it as a new component and starts fresh.
- **Picking a stock** in the search box calls `router.replace('/trade?symbol=…')`, instead of changing local state.
- **The company name** is looked up from the symbol, so it's right however you arrived.

**Rule for new pages:** anything shown from the address must be read from the address on every render, never copied into `useState` once. Hidden pages also stay in the page's HTML, so browser test scripts should look inside the visible page only, using `main:visible`.

### How this is tested

`TradingFlowTests` runs real threads against real Postgres, in a Testcontainers Docker container:

- 12 simultaneous orders needing $30,000 each, against $100,000: exactly 3 succeed and 9 get 422.
- 8 simultaneous retries of one key: one order, reserved once.

The tests call `tick()` by hand with a fake market, so results don't depend on timing.

`KafkaJournalFlowTests` checks the event path with a real Redpanda broker in Docker: an account's events arrive in order on one partition, the consumer updates the journal by itself, the outbox empties, and replaying a FILLED message is counted as a duplicate and changes nothing.

---

## 15. Failure handling

| Failure | What happens |
|---|---|
| Alpaca quote call fails | Logged. No prices for that round. Orders wait, and the next attempt happens in seconds |
| Alpaca clock call fails | The last reading from the past 15 minutes is used, aged by its own times: a reading that said "open, closes at 4 PM" counts as closed after 4 PM. Alpaca isn't asked again for 10 seconds. With no usable reading, the market counts as closed, so nothing fills on unknown hours |
| Alpaca stock list fails at startup | Logged. The existing list stays, and the app starts |
| Alpaca history call fails | Logged. The chart shows the history already stored, and retries on the next request |
| No history at all for a stock | The chart says "No price history available" |
| Docker Desktop restarts | The containers have `restart: unless-stopped`, so they come back by themselves |
| Alpaca keys missing | Starter stock list, no prices, market closed. Limit orders are accepted, and market buys get 503 |
| A holding has no current price | Valued at cost, so an outage doesn't look like a loss. Its price shows as "—" |
| Slow Alpaca | Connect timeout 5 seconds, read timeout 30 seconds |
| Server crashes mid-fill | The transaction rolls back. The order is still `OPEN`, and the next run fills it |
| Server restarts | Open orders are in the database, so the engine carries on |
| One fill hits a database error | Logged, and the other orders still process. It retries on the next run |
| Price jumps past the reservation | The order becomes `REJECTED` and its cash is released. Cash never goes negative |
| Redis down | The quote cache falls back to memory. Nothing else changes |
| Live connection drops | The browser reconnects every 3 seconds and polls every 5 seconds until it's back |
| An order event can't be delivered | It's dropped. The page reloads its orders on reconnect and every 15 seconds |
| Postgres down | Requests fail. There's no fallback, because it's the source of truth |
| Kafka down | Orders still work. Events wait in `event_publication` and `OutboxRelay` resends them each minute; the journal refreshes when read. Sends give up after 5 seconds, so nothing blocks |
| A Kafka message is delivered twice | `journal_processed_events` ignores the repeat |
| SEC EDGAR unreachable, or `SEC_USER_AGENT` missing | The copilot answers 502 or 503 with the reason. Already-downloaded filings keep working |
| Redis down | Rate limits are skipped rather than blocking everyone, and AI budgets aren't counted |
| `JWT_SIGNING_KEY` missing | Startup stops with a clear message |
| No price history for a backtest's symbols in its period | The run becomes `FAILED` with "No price history for …" |
| Server restarts during a backtest | The run is marked `FAILED`, "Interrupted by a server restart". Run it again |

The pattern is that **outside data problems make orders wait, and they never move money**.

---

## 16. Security model

| Threat | Defence |
|---|---|
| Stolen database | BCrypt password hashes, SHA-256 refresh token hashes |
| Stolen access token | Expires in 15 minutes |
| Stolen refresh token | Rotation with replay detection. The cookie is httpOnly, so scripts can't read it |
| Script injected into a page | Access token is in memory, not `localStorage` |
| Other websites sending requests as you | `SameSite=Strict` cookie, limited to `/api/v1/auth`, plus CORS |
| Finding out which emails have accounts | Same login error and matching timing. Sign-up does still reveal a taken email with 409 |
| Reading another user's data | The user ID comes from the signed token, and every query filters by it. Other users' items return 404 |
| Listening to another user's orders | Order events go to `/user/queue/orders`, which Spring maps to the session's own user. Subscribing to raw `/queue/...` destinations is refused |
| An unauthenticated WebSocket | `CONNECT` without a valid token is refused, and the connection closes |
| Running someone else's strategy, or reading their backtest | Every query filters by the user ID, so both return 404 |
| A strategy that runs code | Impossible by design: a strategy is data that the engine interprets, never code |
| Backtests used to overload the server | 4 computing at once, 3 unfinished per user |
| Calling the ai-service directly | It needs `X-Service-Token`, compared in constant time. With no token configured it refuses every call |
| Running up an AI bill | A daily token allowance per user, checked in Redis before every call |
| Prompt injection in a strategy description | The text is wrapped in `<request>` tags and treated as a description only. The output can only be strategy JSON, which Java validates before anything is saved |
| AI giving financial advice | The prompts forbid it, explanations get only computed numbers, and an eval checks it |
| Anthropic key in the browser | Only the ai-service holds it |
| SQL injection | Parameterised queries only. Search escapes `%` and `_` |
| Secrets in git | `.env` is ignored, and only `.env.example` is committed |
| Secrets in the browser | Alpaca and Anthropic keys stay on the server |

JWTs are signed with HS256 and `JWT_SIGNING_KEY`. Anyone with that key could forge a login, so it must never be shared. Changing it invalidates all access tokens.

### Rate limits

Requests that cost something are counted in Redis per minute, so every instance shares the count. Reads are never limited. Over the limit, the answer is 429 with a `Retry-After` header.

| What | Limit per minute | Counted per |
|---|---|---|
| Login and sign-up | 10 | IP address |
| Placing orders | 60 | User |
| Starting backtests | 20 | User |
| AI calls: drafting, explaining, coaching, research | 10 | User |

The counter is a fixed window: one `INCR` per request on a key that expires with the minute. It can let through up to twice the limit across a minute boundary, which is fine for stopping abuse. If Redis is down, requests go through rather than everyone being locked out.

**Not protected yet:** passwords can be as short as 8 characters, `/swagger-ui` is public, and there's no account lockout, password reset or email verification. `/actuator/prometheus` is open so Prometheus can read it; in production Caddy doesn't forward it.

---

## 17. API reference

All paths start with `/api/v1`. Everything needs `Authorization: Bearer <token>` except the `POST /auth/*` routes. Interactive docs are at http://localhost:8080/swagger-ui.

| Method and path | Body or query | Success | Errors |
|---|---|---|---|
| `POST /auth/register` | `name`, `email`, `password` | 201, token, cookie | 400, 409 |
| `POST /auth/login` | `email`, `password` | 200, token, cookie | 400, 401 |
| `POST /auth/refresh` | Cookie | 200, token, new cookie | 401 |
| `POST /auth/logout` | Cookie | 204, clears cookie | None |
| `GET /me` | | 200 | 401 |
| `GET /instruments` | `query` | 200, up to 20 | 401 |
| `GET /market/quotes` | `symbols`, up to 100 | 200 | 400, 401 |
| `GET /market/clock` | | 200 | 401 |
| `GET /market/bars` | `symbol`, `range` = `1D`, `5D`, `1M`, `3M`, `6M`, `1Y` (default) or `5Y` | 200, bars oldest first, each with `time`, `timestamp`, `open`, `high`, `low`, `close`, `volume` | 400, 401, 404 |
| `GET /watchlists` | | 200 | 401 |
| `POST /watchlists` | `name` | 201 | 400, 409 |
| `DELETE /watchlists/{id}` | | 204 | 404 |
| `POST /watchlists/{id}/items/{symbol}` | | 200 | 404 |
| `DELETE /watchlists/{id}/items/{symbol}` | | 200 | 404 |
| `GET /account` | | 200 | 401 |
| `GET /positions` | | 200 | 401 |
| `POST /orders` | `Idempotency-Key` header, `symbol`, `side`, `type` (`MARKET`, `LIMIT`, `STOP`, `STOP_LIMIT`), `qty`, `limitPrice` (limit and stop-limit only), `stopPrice` (stop and stop-limit only), `timeInForce` (`DAY` or `GTC`, default `GTC`) | 201 | 400, 404, 422, 503 |
| `GET /orders` | Optional `status` | 200, newest 100 | 400, 401 |
| `DELETE /orders/{id}` | | 204 | 404, 409 |
| `GET /fills` | | 200, newest 100 | 401 |
| `GET /journal` | | 200, `stats`, `fillCount`, `insights`, `insightsCurrent` | 401 |
| `POST /journal/insights` | | 200, the journal with fresh coach's notes | 409 with no closed trade, 422, 429, 502, 503 |
| `GET /ai/status` | | 200, `serviceConfigured`, `serviceReachable`, `claude`, `research` (whether each is switched on; keys are never returned) | 401 |
| `POST /ai/copilot/ask` | `symbol`, `question` (3 to 1,000 characters) | 200, `segments` (text with `citations`) and numbered `sources` | 400, 404, 422, 429, 502, 503 |
| `GET /strategies` | | 200, newest 100 | 401 |
| `GET /strategies/{id}` | | 200 | 404 |
| `POST /strategies` | `dsl` (the strategy JSON), optional `sourcePrompt` | 201 | 400 with `field` |
| `PUT /strategies/{id}` | `dsl` | 200 | 400 with `field`, 404 |
| `DELETE /strategies/{id}` | | 204 | 404 |
| `POST /strategies/validate` | `dsl` | 200, `valid`, `name`, `symbols` | 400 with `field` |
| `POST /backtests` | `strategyId`, optional `from`, `to` (dates, default the last 3 years), `initialCash` (default 100,000), `feeBps` (0), `slippageBps` (5) | 202, `id`, `status` | 400, 404, 429 |
| `GET /backtests` | | 200, newest 50 | 401 |
| `GET /backtests/{id}` | | 200, with the strategy copy, settings, metrics for the strategy and the benchmark, `inputHash` | 404 |
| `GET /backtests/{id}/trades` | | 200, in order | 404 |
| `GET /backtests/{id}/equity` | | 200, one point per trading day | 404 |
| `DELETE /backtests/{id}` | | 204, finished runs only | 404, 409 |
| `POST /backtests/{id}/explain` | | 200, `explanation`, generated once and then reused | 404, 409 if not completed, 422, 429, 502, 503 |
| `POST /ai/strategies/draft` | `prompt`, up to 2,000 characters | 200, `kind` = `strategy`, `clarification` or `invalid`, with `dsl`, `question`, `assumptions`, `error` | 400, 422, 429, 502, 503 |

| Status | Meaning here |
|---|---|
| 400 | The request is malformed or failed validation |
| 401 | Not logged in, or the token has expired |
| 404 | Doesn't exist, or belongs to someone else |
| 409 | Conflicts with current state, such as a taken name or an order that's no longer open |
| 422 | Valid request, but not enough buying power or shares |
| 429 | Too many unfinished backtests, or today's AI allowance is used |
| 502 | The AI couldn't produce a usable answer |
| 503 | No price available for a market buy, or AI is off or unreachable |

Orders come back with `stopPrice`, `timeInForce`, `triggeredAt` and `expiresAt` as well as the fields above.

The ai-service's own endpoints (`/v1/strategy/parse`, `/v1/backtest/explain`, `/health`) are internal: only core-service calls them.

Non-API URLs: `GET /actuator/health` is public, while `/actuator/info` and `/actuator/prometheus` need a token. The WebSocket at `/ws` is described in [The live connection](#the-live-connection).

---

## 18. Configuration reference

### `.env`

| Variable | Required | Used by | Purpose |
|---|---|---|---|
| `POSTGRES_HOST`, `_PORT`, `_DB`, `_USER`, `_PASSWORD` | No, defaults work | `core-service`, Docker | Database connection |
| `REDIS_HOST`, `REDIS_PORT` | No | `core-service`, Docker | Redis connection |
| `JWT_SIGNING_KEY` | **Yes**, 32+ characters | `core-service` | Signs access tokens |
| `AUTH_COOKIE_SECURE` | No, defaults to `true` | `core-service` | `false` for local http |
| `WEB_ORIGIN` | No | `core-service` | The site allowed by CORS |
| `ALPACA_API_KEY_ID`, `ALPACA_API_SECRET_KEY` | For real data | `core-service` | Stock list, prices, hours |
| `TRADING_IGNORE_MARKET_HOURS` | No, defaults to `false` | `core-service` | Fills outside market hours, for testing |
| `MARKETDATA_PROVIDER` | Not read yet | Nothing | Reserved for replay mode |
| `AI_SERVICE_TOKEN` | For AI features | `core-service`, `ai-service` | Shared secret between them. Empty turns AI off |
| `ANTHROPIC_API_KEY` | For AI features | `ai-service` | Calls Claude |
| `AI_SERVICE_URL` | No, defaults to `http://localhost:8000` | `core-service` | Where the ai-service runs |
| `SEC_USER_AGENT` | For the copilot | `ai-service` | App name and contact email, required by SEC EDGAR |
| `KAFKA_ENABLED` | No, defaults to `true` | `core-service` | `false` runs without Kafka |
| `KAFKA_PORT`, `KAFKA_BOOTSTRAP_SERVERS` | No | Docker, `core-service` | Broker address, `localhost:19092` by default |
| `DOMAIN` | For deployment | `docker-compose.prod.yml` | The site Caddy serves and gets a certificate for |
| `GRAFANA_ADMIN_PASSWORD` | No, defaults to `admin` | Grafana | Grafana's admin login |
| `AI_DAILY_TOKEN_BUDGET` | No, defaults to 200,000 | `ai-service` | Tokens per user per day |
| `AI_STRATEGY_MODEL`, `AI_EXPLAIN_MODEL` | No | `ai-service` | Override `claude-haiku-5-5` and `claude-sonnet-5-5` |
| `EMBEDDINGS_API_KEY`, `SEC_USER_AGENT` | Not read yet | Nothing | Reserved for the research copilot |
| `NEXT_PUBLIC_API_URL` | No | `web`, set in its own environment | API address, defaults to `http://localhost:8080` |

`core-service` loads `../.env`, relative to where it runs, so start it from the `core-service` folder. The ai-service reads the same file, found relative to its own code, so it can start from anywhere: `uv run fastapi dev app/main.py --port 8000` from `ai-service`.

### `application.yml` settings

| Setting | Value | Meaning |
|---|---|---|
| `auth.access-token-ttl` | `15m` | Access token lifetime |
| `auth.refresh-token-ttl` | `30d` | Refresh token lifetime |
| `auth.refresh-reuse-grace` | `10s` | Window that counts as two tabs racing |
| `trading.starting-cash` | `100000` | New account balance |
| `trading.matching-interval` | `3s` | Pause between engine runs |
| `trading.slippage-bps` | `5` | Market order slippage, 0.05% |
| `trading.market-buffer-pct` | `2` | Extra cash reserved on market and stop buys |
| `realtime.quote-interval` | `1s` | How often watched symbols are checked for a new quote |
| `rate-limit.*` | `1m` window; auth 10, orders 60, backtests 20, ai 10 | See [Rate limits](#rate-limits) |
| `ai.read-timeout` | `120s` | A first copilot question downloads filings before Claude answers |
| `spring.modulith.events.completion-mode` | `delete` | Handled events leave `event_publication`, so what's left is the outbox |
| `spring.http.clients.*-timeout` | `5s`, `30s` | Connect and read timeouts for Alpaca |

History settings are constants in `BarTimeframe` (see [Price history and charts](#price-history-and-charts)). Clock settings are constants in `AlpacaMarketData`: a 30-second cache, 10 seconds between retries, and 15 minutes as the oldest reading to trust.

Tests override some of these in `src/test/resources/application.properties`, so they never call real Alpaca, even when `.env` has keys.

---

## 19. Build, tests and CI

### Build tools

| Service | Tool | Dependencies pinned in |
|---|---|---|
| `core-service` | Maven wrapper (`mvnw`) | `pom.xml` |
| `ai-service` | uv | `uv.lock` |
| `web` | npm | `package-lock.json` |

### Test levels

| Level | Examples | Uses |
|---|---|---|
| Unit | `OrderMatcherTests`, `QuoteTests`, `IndicatorsTests`, `BacktestEngineTests`, `StrategyParserTests` | Plain Java |
| HTTP client | `AlpacaMarketDataTests`, `AlpacaAssetsClientTests` | Fake HTTP server |
| Integration | `AuthFlowTests`, `WatchlistFlowTests`, `TradingFlowTests`, `RedisQuoteCacheTests`, `BacktestFlowTests` | Real Postgres and Redis in Docker, fake market or stored prices |
| WebSocket | `RealtimeTests` | A real server on a random port and a real STOMP client |
| Architecture | `ModularityTests` | Spring Modulith |
| Kafka | `KafkaJournalFlowTests` | A real Redpanda broker in Docker |
| Python | `ai-service/tests` (24 tests) | Fake model, fake store and a mock HTTP server for EDGAR |
| Browser | `web/e2e/smoke.spec.ts` with `npm run e2e` | Playwright in your installed Chrome, against servers you've started |

The browser test needs running servers and live data, so it runs by hand rather than in CI. LLM evals cost money, so they also run by hand.

### GitHub Actions

Each workflow in `.github/workflows/` runs on pushes to `main` and on pull requests, only when its folder changes.

| Workflow | Steps |
|---|---|
| `core.yml` | Java 21, then `mvnw verify` |
| `ai.yml` | `uv sync --locked`, ruff, pytest |
| `web.yml` | Node 24, `npm ci`, lint, `next typegen`, type check, build |

---

## 20. Scaling limits

The design suits one server and a modest number of users. These are what would need to change first.

| Limit | Why | Next step |
|---|---|---|
| One `core-service` only | A second copy would run a second matching engine. Rate limits, quotes and AI budgets are already shared in Redis, and Kafka consumers already share partitions. `transition()` still prevents double fills, but opposite lock orders in fills could then deadlock. The STOMP broker is in memory, so a browser only hears messages from the instance it's connected to | Elect one engine with a lock, and use a broker relay such as RabbitMQ for STOMP. Quotes are already shared in Redis |
| Fills are seconds behind | 3-second polling | Stream prices and match on each update |
| Every run scans all open orders | Fine for thousands, not millions | In-memory order books per symbol, rebuilt from the database at startup |
| Alpaca rate limits | Each symbol set and each cache miss costs requests | One shared poller or stream instead of per-request fetches |
| 10 database connections | The default pool size. Requests queue beyond it | Tune the pool |
| History loads on first view | The first chart for each stock waits a few seconds for Alpaca | Preload history for watchlist stocks in the background |
| 5-minute bars grow forever | About 190 rows per stock per trading day, never deleted | A TimescaleDB retention policy, such as dropping 5-minute bars after 30 days |
| Each open 1D or 5D chart polls bars | One bars request a minute | Push bars over the live connection too |
| `cash_ledger`, `fills` and `orders` grow forever | No archiving | Partition or archive old rows |
| One Postgres | A single point of failure | Backups, then a replica |

The weakness of calling Alpaca inside transactions is fixed. See "Never wait on the network inside a transaction" in [Concurrency handling](#14-concurrency-handling).

---

## 21. How each change gets made

Every feature in this project follows the same steps.

| Step | What happens | Example from paper trading |
|---|---|---|
| 1. Read | Check PLAN.md and the current code before changing anything | Confirmed the keys worked and the repo was clean |
| 2. Scope | Choose the smallest version that's useful, and record any change from the plan | Market and limit orders first, charts later |
| 3. Database | Add a new numbered migration. Never edit an old one | `V5__trading.sql` |
| 4. Backend | Build from the bottom up, so each layer rests on a working one | Prices, then the account, then orders |
| 5. Tests | Cover normal use, bad input, and races between requests, using a fake market | 12 simultaneous buys against one account |
| 6. Web | Build the pages on the finished API | Trade and Portfolio pages |
| 7. Automatic checks | `.\mvnw verify`, then lint, type check and build for the web app. Any unexpected failure gets its cause read before a rerun | 67 backend tests |
| 8. Real run | Start a temporary server on port 8081, drive real Chrome through the feature with live data, and read the screenshots | Found the stale-bid price bug that the tests missed |
| 9. Clean up | Stop the temporary server and delete test users. Your server on 8080 isn't touched | Removed `smoke-*` users |
| 10. Document | Update this guide and PLAN.md | This section |

Two rules apply throughout:

- **Fix causes, not symptoms.** A failing check is either a real bug or a wrong expectation, and it gets worked out before anything changes.
- **Commits and pushes happen only when you ask.** Commit messages show only your name.

---

## 22. Change log

### 2026-11-15: AI status shown on every AI feature

- Each AI section shows "AI ready" or "AI · not configured", with the button disabled and a note naming the missing setting, instead of an error after a click.
- New `GET /api/v1/ai/status` in core-service and `GET /v1/status` in the ai-service.
- The coach's section on Journal now shows before the first trade.
- Workflow notes: the browser test's new check failed on Journal, which hid the coach until a trade existed, so the AI feature was invisible to new users. The section now always shows.

### 2026-11-15: Kafka, journal, research copilot, rate limits, monitoring and deployment

- **Kafka:** order events go to `trading.order-events` through a transactional outbox, keyed by account. Redpanda runs in Docker. Undelivered events are retried every minute.
- **Journal:** statistics from your fills, refreshed by a Kafka consumer that ignores repeated messages, with AI coaching notes checked against the figures.
- **Research copilot:** questions about a company answered from its latest 10-K and 10-Q, found with Postgres full-text search, with Claude's citations linking each claim to the filing.
- **Rate limits** on login, orders, backtests and AI calls, in Redis. Expired refresh tokens are now cleaned up daily.
- **Monitoring:** Prometheus and a Grafana dashboard as an optional Compose profile, with new fill and rejection counters.
- **Deployment:** Dockerfiles for all three services, a production Compose file and a Caddy HTTPS proxy. Not deployed yet.
- **Browser test:** a Playwright smoke test covering sign-up, trading, cancelling, saving a strategy and a backtest.
- Migrations `V10__journal.sql` and `V11__research.sql`.
- Workflow notes: the first Kafka test run failed because an account's events reached Kafka out of order: with virtual threads, each event got its own sending thread. Sending through one thread fixed it, and the test now checks the order. The Playwright test first failed on an ambiguous "cancelled" match (the time-in-force menu says "Good till cancelled"), a test problem, not an app bug.

### 2026-11-07: AI strategy drafting and explanations

- **Describe a strategy:** type what you want to test and Claude Haiku 5.5 drafts the rules. The Java validator checks the draft and sends its exact error back once for a repair. Vague requests get a question, and any defaults the AI chose are listed.
- **Explain these results:** Claude Sonnet 5.5 explains a finished backtest in three short paragraphs, using only the engine's numbers, each one checked.
- **ai-service:** service-token auth, one LLM wrapper with per-user daily token budgets in Redis, a log of every call with cost in `llm_calls`, stop-reason handling, prompt caching and refusal fallback.
- **Evals:** `strategy_gen`, `advice_refusal` and `explain` suites and a runner that saves scores to `eval_runs`. Not run yet, because they need an API key and cost money.
- Migration `V9__ai.sql`. Tests: 114 → 124 in core-service, and 1 → 16 in the ai-service.
- Workflow notes: structured outputs don't support recursive schemas, which DSL v1 needs, so the model fills a flatter shape that Python converts. The installed SDK was 2.3.0, newer than the reference I work from, so I checked the parameters I used (`output_format`, `output_config`, `fallbacks`) on the installed package before writing the code.

### 2026-11-07: Strategies and backtesting

- **Strategies:** write rules as JSON (DSL v1), check them, and save them. Errors name the exact field. The format is published as a JSON Schema in `contracts/` for the ai-service.
- **Backtests:** runs on virtual threads, signals at the close and fills at the next open, stop-loss and take-profit inside each bar, fees, slippage, a buy-and-hold benchmark, and seven metrics. Results arrive live on the Backtests page, with an equity and drawdown chart and every trade.
- **Indicators:** SMA, EMA, RSI, ATR, MACD and Bollinger bands, each checked against hand-worked values.
- **Speed:** five years of daily bars with an SMA crossover take about 13 ms.
- Migration `V8__strategies_backtests.sql`. Tests: 82 → 114.
- Workflow notes: the Next.js build refused `new Date()` on the Strategies page, because this version prerenders client pages. The bundled docs say to keep such calls out of prerendering, so empty date fields now mean "the last 3 years" and the server picks the dates.

### 2026-11-07: Live updates, stop orders and DAY expiry

- **Live connection:** a STOMP WebSocket at `/ws` pushes changed quotes every second and your order events as soon as they commit. Prices on Watchlists, Trade and the chart's live candle now update without polling, and the Portfolio page refreshes the moment an order fills.
- **Redis:** the latest quotes are cached in Redis for 2 seconds and shared across instances, with an in-memory fallback.
- **Stop and stop-limit orders:** triggered by the last trade, then filled like market or limit orders.
- **Time in force:** DAY orders expire at the close and release their cash or shares. GTC orders stay open.
- **Events:** the trading module publishes an `OrderEvent` on every change. Spring Modulith records each one in `event_publication`, ready for Kafka later.
- Migration `V7__order_types.sql`. Tests: 67 → 82.
- Workflow notes: two WebSocket tests first received nothing. The test client only read `text/plain` frames and the server sends JSON, so frames were silently dropped. Fixing the test client's converter showed the server was right all along.

### 2026-11-02: Watchlist Trade opened the wrong stock

- Fixed: clicking **Trade** on a watchlist stock could show the previously viewed stock instead. The cause was Next.js keeping the Trade page alive and restoring its old state (see [Pages stay alive after you leave them](#pages-stay-alive-after-you-leave-them)).
- The Trade page now follows the address: it resets whenever the address changes, and searching changes the address.
- The company name now also shows when you arrive from a link.
- Workflow notes: I reproduced the bug in Chrome before changing anything. Clicking NVDA changed the address but still showed AAPL. The test also found two search boxes in the page, one belonging to the hidden Watchlists page, which pointed to pages being kept alive. The Next.js docs in `node_modules` confirmed this. One test step failed because the script edited a link's address directly, which Next.js ignores, so I replaced it with a real Sell link from the Portfolio page.

### 2026-11-02: Trade page remembers your stock

- Opening **Trade** from the navbar now shows the last stock you viewed, so its chart appears at once. A stock in the address still takes priority.
- With no stock picked, a placeholder now explains where the chart will appear, instead of leaving empty space.
- Workflow notes: you reported that no chart was visible. The server returned bars for every range, and a fresh browser drew the chart, so I couldn't reproduce it. The web log showed that your last visits were to `/trade` with no stock, where the chart is hidden by design. A tab still running the version with the infinite-loop bug could also leave the chart missing until reloaded.

### 2026-11-02: Bigger, live, zoomable charts

- Moved the chart into its own full-width card, about twice as tall, and widened the Trade page.
- Added 1D and 5D ranges with 5-minute bars from Alpaca's real-time IEX feed, refreshed every minute, and made 1D the default.
- The newest candle now moves with the live price every 5 seconds while the market is open, with a **Live** badge.
- Added zoom buttons, a reset, an O H L C price readout and full screen. Wheel zoom and dragging already worked but weren't explained, so the chart now says so.
- `/market/bars` returns a `timestamp` for each bar, and the range list grew.
- The market clock now survives brief Alpaca outages by using its last reading, aged by its own open and close times.
- Workflow notes: the browser check caught two problems the tests couldn't. One was a React feedback loop between the live candle and the hover readout, which I fixed. The other was intermittent connection timeouts to Alpaca's clock, so I confirmed Alpaca was reachable with direct calls and made the clock fall back on its last reading. One new clock test was wrong: it jumped further than the trust limit, so it never reached the rule it meant to check. I fixed the test, not the code.

### 2026-11-02: Price charts and fixes

- Added migration `V6` with the `price_bars` hypertable and `price_bar_syncs`. This is the first real use of TimescaleDB.
- Added `GET /api/v1/market/bars`, which loads 5 years of daily SIP history from Alpaca on first request, then refreshes hourly.
- Added a candlestick and volume chart with range buttons to the Trade page.
- Fixed the known weakness: prices are now fetched outside database transactions. A new test enforces this.
- Fixed a bug the concurrency test exposed: a losing retry of the same order failed with a 500 error, because a read-only transaction ran an `INSERT`.
- Added `restart: unless-stopped` to both containers. Docker Desktop had restarted during testing, and the database stayed down.
- Workflow notes: before writing the history code, I tested Alpaca directly to confirm the free plan allows SIP history. The first full test run failed because Docker itself returned 503, so I read the error, then reran the suite twice, and both runs passed.

### 2026-11-01: Complete project reference

- Added sections on system architecture, concurrency, failure handling, security, the API, configuration, build and CI, and scaling limits.
- Documented the known weakness of calling Alpaca inside database transactions.

### 2026-11-01: Paper trading

- Added live prices and US market hours from Alpaca, with short caches.
- Added migration `V5` with `accounts`, `cash_ledger`, `positions`, `orders` and `fills`.
- Added a $100,000 paper account for every user, with cash and share reservations.
- Added market and limit orders with idempotency keys, cancelling, and a matching engine that runs every 3 seconds.
- Added the `/trade` and `/portfolio` pages, plus live prices and Trade links on watchlists.
- Moved stock search into a shared component, and page login checks into `useRequireAuth()`.
- Added `TRADING_IGNORE_MARKET_HOURS` for testing while the market is closed.
- Fixed a pricing bug found during the real run: a stale bid of $329.26 filled a sale with the stock at $344.89. Bids and asks more than 2% from the last trade are now ignored.

### 2026-11-01: User names and password visibility

- Added migration `V4` with a required `name` on `users`. Existing accounts got the part of their email before the `@`.
- Sign-up now asks for a name of up to 50 characters, and the API returns it with the user.
- The navbar and home page now show the name. Hovering over it in the navbar shows the email.
- Added a Show/Hide button to password fields.

### 2026-11-01: UI styling

- Added shared colours for light and dark mode, plus reusable button, input and card styles.
- Added a top navigation bar and restyled the home, login, sign-up and watchlist pages.
- Fixed the Geist font, which was being overridden by Arial.
- Deleting a watchlist now asks for confirmation.
- Stock search now shows "No matches" and marks stocks already in the list as "Added".

### 2026-11-01: Instruments and watchlists

- Added migration `V3` with the `instruments`, `watchlists` and `watchlist_items` tables, plus a 30-stock starter list.
- Added stock search at `GET /api/v1/instruments`.
- Added the Alpaca client and a startup seeder, which does nothing until keys are set.
- Added watchlist endpoints with per-user ownership checks.
- Added the `/watchlists` page and a link from the home page.

### 2026-11-01: Authentication

- Added migration `V2` with the `users` and `refresh_tokens` tables.
- Added register, login, refresh, logout and `/me`.
- Added JWT checks and CORS to `SecurityConfig`, and turned on Problem Details errors.
- Added the `/login` and `/register` pages, plus `AuthProvider` and `apiFetch()` for the browser session.
- Made `JWT_SIGNING_KEY` required.
- Stored refresh tokens in Postgres, not the Redis denylist PLAN.md first suggested, so they survive restarts and support theft detection.

### Known gaps

- The copilot uses full-text search only; paraphrased questions can miss passages.
- The research answer arrives all at once rather than streamed.
- Nothing has been deployed to a real server yet.
- No password reset or email verification.
- The stock list refreshes only at startup.
- Orders always fill completely, however large they are.
- Prices are pushed when they change, checked once a second, not tick by tick.
- The account value isn't charted over time yet.
- Backtests use daily bars only, whole shares, and history back to 5 years.
- The live candle is estimated from once-a-second quotes, and 5-minute volume covers IEX only.
- The first chart for a stock waits a few seconds while its history downloads.
- 5-minute bars are never deleted.
- Full screen isn't available on iPhone Safari.
- The auth cookie needs a shared site or a proxy once the web app and API are on different domains.
