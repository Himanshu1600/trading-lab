# How Trading Lab Works

A guide to what the app does today and how a request moves through it. It describes what actually exists, while [PLAN.md](../PLAN.md) describes the full goal. This file is updated with every change, and the [change log](#21-change-log) at the end lists what was added and when.

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
10. [Database tables](#10-database-tables)
11. [Where to find things](#11-where-to-find-things)
12. [System architecture](#12-system-architecture)
13. [Concurrency handling](#13-concurrency-handling)
14. [Failure handling](#14-failure-handling)
15. [Security model](#15-security-model)
16. [API reference](#16-api-reference)
17. [Configuration reference](#17-configuration-reference)
18. [Build, tests and CI](#18-build-tests-and-ci)
19. [Scaling limits](#19-scaling-limits)
20. [How each change gets made](#20-how-each-change-gets-made)
21. [Change log](#21-change-log)

---

## 1. The big picture

```text
Browser (Next.js, port 3000)
   │  REST calls with a JWT access token
   ▼
core-service (Spring Boot, port 8080)
   ├── Postgres + TimescaleDB (port 5433): users, watchlists, accounts, orders, positions
   ├── Redis (port 6379): connected, not used yet
   └── Alpaca (internet): stock list, live prices and market hours, only if keys are set

ai-service (FastAPI, port 8000): running, not connected to anything yet
```

**What works today:** sign up, log in, stay logged in, log out, search stocks, manage watchlists, see live prices and charts from live 5-minute candles to 5 years of daily history, place paper market and limit orders, cancel them, and track a portfolio.

**What doesn't exist yet:** streaming chart updates, chart indicators, stop orders, automatic order expiry, streaming prices, replay mode, backtests and every AI feature.

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
Browser polls every 5 seconds
   → GET /api/v1/market/quotes?symbols=AAPL,NVDA
   → MarketController
   → AlpacaMarketData (answers from a 2-second cache, or asks Alpaca)
```

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

If Alpaca fails or the keys are missing, the app treats that as "no data". Limit orders can still be placed, market buys are refused and orders wait. Brief outages of the market clock are covered by its last reading (see [Failure handling](#14-failure-handling)).

A clock outage on 2026-11-02 showed why that matters: two connection timeouts of 5 seconds each made the open market look closed, so live updates stopped and orders couldn't fill.

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
Every 5 seconds the page gets a quote (the same request as the price above the chart)
   → liveBar() works out the current bar's time: today, or the current 5-minute slot
      Same slot as the last bar → stretch its high or low, and set its close to the live price
      New slot                  → start a new candle that opens at the last close
   → the chart's update() redraws just that candle
Every 60 seconds (1D and 5D) the page re-reads bars from the server, which replaces the estimate with Alpaca's real bar
```

The live candle is an estimate. It only sees prices at each 5-second check, so a brief spike between checks can be missed until the next re-read. A **Live** badge shows when this is on. When the market is closed, nothing moves.

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
7. Saves the order as OPEN                       orders table
8. Reserves the cash, or refuses with 422        PortfolioService.reserveCash()
9. Returns the order                             status 201
```

A sell follows the same steps, but step 8 reserves shares and you can't sell shares you don't own.

### Filling an order

`MatchingEngine.tick()` runs every 3 seconds:

```text
1. Loads every OPEN order
2. Stops if there are none, or the market is closed
3. Gets fresh quotes for those symbols
4. Asks OrderMatcher whether each order fills, and at what price
5. Calls OrderFiller for each match
```

`OrderFiller` then does everything in one transaction: it marks the order filled, moves the cash, updates the position, and writes the fill and ledger rows. Either all of that happens or none of it does.

| Order | Fills when | Fills at |
|---|---|---|
| Market buy | The market is open | Buy price + 0.05% slippage |
| Market sell | The market is open | Sell price − 0.05% slippage |
| Limit buy | Buy price ≤ your limit | Buy price |
| Limit sell | Sell price ≥ your limit | Sell price |

Slippage is the small extra cost real market orders pay. It's why a new position usually starts slightly negative.

### Order statuses

```text
OPEN ──> FILLED      a price matched
     ├─> CANCELLED   you cancelled it
     └─> REJECTED    the price jumped and the cash no longer covered it
```

An order you can't afford is refused straight away, leaving no order behind. `REJECTED` only happens at fill time, because a market order's final price isn't known when it's placed.

Orders placed while the market is closed stay `OPEN` until it opens, and every order stays open until it fills or you cancel it.

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

### Differences from PLAN.md

This is a first version, and these parts differ on purpose:

| PLAN.md | Today | Why |
|---|---|---|
| In-memory order book per symbol, with its own thread | One loop checks open orders stored in the database | Prices arrive every few seconds, so a book adds nothing yet. A restart loses nothing either |
| Streaming prices over WebSocket | The browser and the engine poll over REST | Simpler, and it fits the free rate limit |
| Latest quotes in Redis | A short in-memory cache | There's only one server process today |
| Stop and stop-limit orders, DAY expiry | Market and limit orders that stay open until filled or cancelled | Smaller first version |
| Optimistic locking on positions | Atomic SQL updates | One writer already prevents clashes |

---

## 10. Database tables

```text
users ──< refresh_tokens
  ├───< watchlists ──< watchlist_items >── instruments
  │
  └──── accounts ──< orders ──< fills
           ├───< positions
           └───< cash_ledger

price_bars, price_bar_syncs     stand alone, keyed by symbol

──<  means one-to-many
```

| Table | Holds | Added in |
|---|---|---|
| `event_publication` | Spring Modulith's event outbox, unused so far | V1 |
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

`price_bars` is split into one chunk per year of data. A query for the last month reads only the newest chunk, not all five years. The primary key is `(symbol, timeframe, ts)`, so fetching the same day twice updates the existing row. It deliberately has no foreign key to `instruments`, keeping bulk inserts simple.

Deleting a user also deletes their tokens, watchlists, account and orders, through `ON DELETE CASCADE`.

To look inside, run this from the project root:

```powershell
docker compose exec timescaledb psql -U tradinglab -d tradinglab
```

---

## 11. Where to find things

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
| `marketdata/AlpacaMarketData` | Fetches and caches prices and market hours |
| `marketdata/MarketController` | Price, history and market-hours endpoints |
| `marketdata/PriceHistoryService` | Serves daily history and refreshes it from Alpaca |
| `marketdata/PriceHistorySource`, `marketdata/PriceBar` | The history interface Alpaca implements, and one bar's prices |
| `marketdata/BarTimeframe` | Daily and 5-minute bars, and how each is fetched and refreshed |
| `marketdata/InstrumentCatalog` | Lets other modules check that a symbol is tradable |
| `portfolio/PortfolioService` | Cash, shares, reservations, settlement and P&L |
| `portfolio/PortfolioController` | Account and positions endpoints |
| `trading/OrderController` | Order and fill endpoints |
| `trading/OrderService` | Placing, listing and cancelling orders |
| `trading/MatchingEngine` | The 3-second loop over open orders |
| `trading/OrderMatcher` | Decides if an order fills, and at what price |
| `trading/OrderFiller` | Applies one fill in one transaction |
| `trading/PaperOrder`, `trading/Fill` | Entities |

Migrations are in `core-service/src/main/resources/db/migration/`.

### Web: `web/src/`

| File | Job |
|---|---|
| `lib/auth.tsx` | Tokens, `apiFetch()`, `useAuth()`, `useRequireAuth()` |
| `lib/market.ts` | Live price and market-status hooks, money formatting |
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
| `trading/OrderMatcherTests` | When orders fill, and at what price |
| `trading/TradingFlowTests` | Orders, fills, P&L, cancels, retries, races, and isolation between users |
| `ModularityTests` | Module boundaries |

Run them all with `.\mvnw verify`. The flow tests need Docker.

---

## 12. System architecture

Sections 12 to 19 describe how the system is built today. [PLAN.md](../PLAN.md) describes where it's heading.

### Runtime components

| Component | Technology | Port | Runs as | State |
|---|---|---|---|---|
| `web` | Next.js 16, React 19, TypeScript, Tailwind 4 | 3000 | Node process on your PC | Access token in browser memory only |
| `core-service` | Java 21, Spring Boot 4.1, Spring Modulith | 8080 | JVM process on your PC | Short in-memory price caches |
| `ai-service` | Python 3.12, FastAPI | 8000 | Python process on your PC | None. Only `/health` exists |
| `timescaledb` | PostgreSQL 17, TimescaleDB, pgvector | 5433 | Docker container | All durable data |
| `redis` | Redis 8 | 6379 | Docker container | Connected, holds nothing yet |
| Alpaca | External API | HTTPS | Internet | Stock list, prices, market hours |

Only `timescaledb` and `redis` are in `docker-compose.yml`. The three services run directly on your PC, so code changes don't need image rebuilds.

### Architecture style: a modular monolith

`core-service` is one deployable application split into modules with enforced boundaries. Separate microservices would add network calls, deployments and distributed transactions, which one developer doesn't need yet. Here, placing an order and moving cash can share a single database transaction.

Each direct sub-package of `com.tradinglab` is a module. Classes in a module's root package that are `public` form its API, and everything else is private to it. `ModularityTests` fails the build if a module reaches into another's internals, or if modules depend on each other in a cycle.

| Module | Status | Public API | Depends on |
|---|---|---|---|
| `auth` | Built | None. Other modules read the user ID from the JWT | Nothing |
| `marketdata` | Built | `Quote`, `MarketQuotes`, `MarketClock`, `InstrumentCatalog` | Nothing |
| `portfolio` | Built | `PortfolioService` | `marketdata` |
| `trading` | Built | None | `portfolio`, `marketdata` |
| `common` | Empty, shared by all | Planned utilities | Nothing |
| `ai`, `backtest`, `realtime`, `strategy` | Empty | Planned | Planned |

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
| Polling, not streaming | Simple, and fits the free plan | Prices are seconds old |
| Open orders stored in the database | A restart loses nothing, and no recovery code is needed | Each check costs a query |
| Interfaces around Alpaca | Tests use a fake market, and providers can change | Slightly more code |
| LLMs will write text, code does the maths (planned) | LLMs are unreliable at arithmetic | Needs a strategy format that Java can run |

### Data ownership

| Data | Owner | Rule |
|---|---|---|
| `users`, `refresh_tokens` | `auth` | Passwords and tokens are stored only as hashes |
| `instruments`, `watchlists`, `watchlist_items` | `marketdata` | `instruments` is written only by migrations and `InstrumentSeeder` |
| `price_bars`, `price_bar_syncs` | `marketdata` | Written only by `PriceHistoryService` |
| `accounts`, `cash_ledger`, `positions` | `portfolio` | All writes go through `PortfolioService` |
| `orders`, `fills` | `trading` | Status changes only through `transition()`, and fills only through `OrderFiller` |

All timestamps are stored in UTC, and the browser converts them to your time zone. Money uses `NUMERIC(18, 4)`, and average cost uses `NUMERIC(18, 6)` to reduce rounding drift.

### The AI service today

The planned features are strategy parsing, research with citations, and a journal coach. PLAN.md covers them in full. Today, `ai-service` has only a health endpoint, one test and empty feature folders. Nothing calls it, and no AI request is ever made.

### Unused pieces

These are installed but do nothing yet, so don't assume otherwise:

- **Redis:** configured, but nothing reads or writes it.
- **`event_publication`:** the outbox table exists, but no module publishes events.
- **TimescaleDB extras:** `price_bars` is a hypertable, but continuous aggregates and compression aren't used yet.
- **pgvector:** enabled, but no vector column exists.
- **WebSocket starter:** a dependency only, with no endpoints.
- **`infra/`:** empty. Prometheus, Grafana and Caddy aren't set up.

---

## 13. Concurrency handling

### The threads in the system

| Thread | Does | Runs how many at once |
|---|---|---|
| Virtual thread per HTTP request | Everything a request does | Many |
| Scheduled matching task | `MatchingEngine.tick()` | One. `fixedDelay` waits for each run to finish |
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
| Paying more than the account holds at fill time | Conditional update on cash | `PortfolioService.settleBuy()` |
| First-use account created twice | `INSERT ... ON CONFLICT DO NOTHING` on unique `user_id` | `PortfolioService.accountFor()` |
| Two buys of one symbol skew the average cost | Single atomic upsert | `PortfolioService.settleBuy()` |
| Two sign-ups with one email | Unique `email` | `AuthService.register()` |
| A refresh token used twice | Conditional revoke | `RefreshTokenRepository.revoke()` |
| Two watchlists with one name | `UNIQUE (user_id, name)` | `WatchlistService.create()` |
| Two tabs refreshing at once | 10-second grace period on the server, one shared promise per tab | `AuthService.refresh()`, `web/src/lib/auth.tsx` |
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

Placing and cancelling each lock just one shared row. The fills are the only operations taking `accounts` and `positions` together, in opposite orders, and the single writer means two fills never run at once. If a second matching engine ever runs, this stops being true (see [Scaling limits](#19-scaling-limits)).

Inserting an order also takes a weak lock on its `accounts` row for the foreign key. That lock doesn't conflict with balance updates, because they don't change the key column.

### Shared memory

| State | Protection | If two threads collide |
|---|---|---|
| Quote cache | `ConcurrentHashMap` | Both may call Alpaca, wasting one request. The last write wins |
| Market clock cache and retry time | `volatile` fields, holding immutable values | Same. Harmless |
| Controllers and services | Hold no per-request state | Nothing to protect |

Caches aren't locked, because a duplicate fetch is harmless and a lock would make every reader wait on a slow network call.

### Concurrency in the browser

- **Refresh:** concurrent callers share one in-flight promise, because the server rotates the token on each use.
- **Search:** typing waits 250 ms, and a `stale` flag discards older responses that arrive late.
- **Polling:** timers stop when you leave a page. Quotes and the portfolio refresh every 5 seconds, and the market clock every 30.
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

---

## 14. Failure handling

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
| Redis down | Nothing depends on it yet |
| Postgres down | Requests fail. There's no fallback, because it's the source of truth |
| `JWT_SIGNING_KEY` missing | Startup stops with a clear message |

The pattern is that **outside data problems make orders wait, and they never move money**.

---

## 15. Security model

| Threat | Defence |
|---|---|
| Stolen database | BCrypt password hashes, SHA-256 refresh token hashes |
| Stolen access token | Expires in 15 minutes |
| Stolen refresh token | Rotation with replay detection. The cookie is httpOnly, so scripts can't read it |
| Script injected into a page | Access token is in memory, not `localStorage` |
| Other websites sending requests as you | `SameSite=Strict` cookie, limited to `/api/v1/auth`, plus CORS |
| Finding out which emails have accounts | Same login error and matching timing. Sign-up does still reveal a taken email with 409 |
| Reading another user's data | The user ID comes from the signed token, and every query filters by it. Other users' items return 404 |
| SQL injection | Parameterised queries only. Search escapes `%` and `_` |
| Secrets in git | `.env` is ignored, and only `.env.example` is committed |
| Secrets in the browser | Alpaca and Anthropic keys stay on the server |

JWTs are signed with HS256 and `JWT_SIGNING_KEY`. Anyone with that key could forge a login, so it must never be shared. Changing it invalidates all access tokens.

**Not protected yet:** login has no rate limit, so passwords can be guessed repeatedly. Passwords can be as short as 8 characters. `/swagger-ui` is public, and HTTPS isn't set up because nothing is deployed. There's also no account lockout, password reset or email verification.

---

## 16. API reference

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
| `POST /orders` | `Idempotency-Key` header, `symbol`, `side`, `type`, `qty`, `limitPrice` | 201 | 400, 404, 422, 503 |
| `GET /orders` | Optional `status` | 200, newest 100 | 400, 401 |
| `DELETE /orders/{id}` | | 204 | 404, 409 |
| `GET /fills` | | 200, newest 100 | 401 |

| Status | Meaning here |
|---|---|
| 400 | The request is malformed or failed validation |
| 401 | Not logged in, or the token has expired |
| 404 | Doesn't exist, or belongs to someone else |
| 409 | Conflicts with current state, such as a taken name or an order that's no longer open |
| 422 | Valid request, but not enough buying power or shares |
| 503 | No price available for a market buy |

Non-API URLs: `GET /actuator/health` is public, while `/actuator/info` and `/actuator/prometheus` need a token.

---

## 17. Configuration reference

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
| `ANTHROPIC_API_KEY`, `EMBEDDINGS_API_KEY`, `SEC_USER_AGENT`, `AI_SERVICE_TOKEN` | Not read yet | Nothing | Reserved for AI features |
| `NEXT_PUBLIC_API_URL` | No | `web`, set in its own environment | API address, defaults to `http://localhost:8080` |

`core-service` loads `../.env`, relative to where it runs, so start it from the `core-service` folder.

### `application.yml` settings

| Setting | Value | Meaning |
|---|---|---|
| `auth.access-token-ttl` | `15m` | Access token lifetime |
| `auth.refresh-token-ttl` | `30d` | Refresh token lifetime |
| `auth.refresh-reuse-grace` | `10s` | Window that counts as two tabs racing |
| `trading.starting-cash` | `100000` | New account balance |
| `trading.matching-interval` | `3s` | Pause between engine runs |
| `trading.slippage-bps` | `5` | Market order slippage, 0.05% |
| `trading.market-buffer-pct` | `2` | Extra cash reserved on market buys |
| `spring.http.clients.*-timeout` | `5s`, `30s` | Connect and read timeouts for Alpaca |

History settings are constants in `BarTimeframe` (see [Price history and charts](#price-history-and-charts)). Clock settings are constants in `AlpacaMarketData`: a 30-second cache, 10 seconds between retries, and 15 minutes as the oldest reading to trust.

Tests override some of these in `src/test/resources/application.properties`, so they never call real Alpaca, even when `.env` has keys.

---

## 18. Build, tests and CI

### Build tools

| Service | Tool | Dependencies pinned in |
|---|---|---|
| `core-service` | Maven wrapper (`mvnw`) | `pom.xml` |
| `ai-service` | uv | `uv.lock` |
| `web` | npm | `package-lock.json` |

### Test levels

| Level | Examples | Uses |
|---|---|---|
| Unit | `OrderMatcherTests`, `QuoteTests` | Plain Java |
| HTTP client | `AlpacaMarketDataTests`, `AlpacaAssetsClientTests` | Fake HTTP server |
| Integration | `AuthFlowTests`, `WatchlistFlowTests`, `TradingFlowTests` | Real Postgres and Redis in Docker, fake market |
| Architecture | `ModularityTests` | Spring Modulith |
| Real browser | Manual run during development | Chrome, live Alpaca, a temporary server |

The browser checks are scripts kept outside the repo, so they don't run in CI. The web app has no automated tests of its own yet, and `ai-service` has a single health test.

### GitHub Actions

Each workflow in `.github/workflows/` runs on pushes to `main` and on pull requests, only when its folder changes.

| Workflow | Steps |
|---|---|
| `core.yml` | Java 21, then `mvnw verify` |
| `ai.yml` | `uv sync --locked`, ruff, pytest |
| `web.yml` | Node 24, `npm ci`, lint, `next typegen`, type check, build |

---

## 19. Scaling limits

The design suits one server and a modest number of users. These are what would need to change first.

| Limit | Why | Next step |
|---|---|---|
| One `core-service` only | A second copy would run a second matching engine. `transition()` still prevents double fills, but opposite lock orders in fills could then deadlock, and quote caches wouldn't be shared | Elect one engine with a lock, and move quotes to Redis |
| Fills are seconds behind | 3-second polling | Stream prices and match on each update |
| Every run scans all open orders | Fine for thousands, not millions | In-memory order books per symbol, rebuilt from the database at startup |
| Alpaca rate limits | Each symbol set and each cache miss costs requests | One shared poller or stream instead of per-request fetches |
| 10 database connections | The default pool size. Requests queue beyond it | Tune the pool |
| History loads on first view | The first chart for each stock waits a few seconds for Alpaca | Preload history for watchlist stocks in the background |
| 5-minute bars grow forever | About 190 rows per stock per trading day, never deleted | A TimescaleDB retention policy, such as dropping 5-minute bars after 30 days |
| Each open 1D or 5D chart polls | One bars request a minute and one quote request every 5 seconds | Push bars over WebSocket |
| `cash_ledger`, `fills` and `orders` grow forever | No archiving | Partition or archive old rows |
| One Postgres | A single point of failure | Backups, then a replica |

The weakness of calling Alpaca inside transactions is fixed. See "Never wait on the network inside a transaction" in [Concurrency handling](#13-concurrency-handling).

---

## 20. How each change gets made

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

## 21. Change log

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

- No rate limiting on login yet (planned for Week 7).
- Expired refresh tokens aren't deleted from the database yet.
- No password reset or email verification.
- The stock list refreshes only at startup.
- Orders never expire, and stop orders don't exist yet.
- Orders always fill completely, however large they are.
- Prices refresh every few seconds, not tick by tick.
- The account value isn't charted over time yet.
- The live candle is estimated from 5-second price checks, and 5-minute volume covers IEX only.
- The first chart for a stock waits a few seconds while its history downloads.
- 5-minute bars are never deleted.
- Full screen isn't available on iPhone Safari.
- The auth cookie needs a shared site or a proxy once the web app and API are on different domains.
