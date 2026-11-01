# How Trading Lab Works

A guide to what the app does today and how a request moves through it. It describes what actually exists, while [PLAN.md](../PLAN.md) describes the full goal. This file is updated with every change, and the [change log](#10-change-log) at the end lists what was added and when.

## Contents

1. [The big picture](#1-the-big-picture)
2. [The vocabulary you need](#2-the-vocabulary-you-need)
3. [What happens at startup](#3-what-happens-at-startup)
4. [Sign-up and login](#4-sign-up-and-login)
5. [Staying logged in](#5-staying-logged-in)
6. [Instruments and search](#6-instruments-and-search)
7. [Watchlists](#7-watchlists)
8. [Database tables](#8-database-tables)
9. [Where to find things](#9-where-to-find-things)
10. [Change log](#10-change-log)

---

## 1. The big picture

```text
Browser (Next.js, port 3000)
   │  REST calls with a JWT access token
   ▼
core-service (Spring Boot, port 8080)
   ├── Postgres + TimescaleDB (port 5433): users, tokens, instruments, watchlists
   ├── Redis (port 6379): connected, not used yet
   └── Alpaca (internet): stock list, only if keys are set

ai-service (FastAPI, port 8000): running, not connected to anything yet
```

**What works today:** sign up, log in, stay logged in, log out, search stocks, and manage watchlists.

**What doesn't exist yet:** prices, charts, orders, portfolio, backtests and every AI feature.

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
3. Flyway runs any new migrations (`V1` to `V4`).
4. Hibernate checks that the tables match the entity classes. It never changes them.
5. The web server starts on port 8080.
6. `InstrumentSeeder` runs. With Alpaca keys, it loads the full stock list. Without them, it keeps the 30-stock starter list and logs that. If Alpaca fails, it logs a warning and the app carries on.

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

## 8. Database tables

```text
users ──< refresh_tokens
  │
  └───< watchlists ──< watchlist_items >── instruments

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

Deleting a user also deletes their tokens and watchlists, through `ON DELETE CASCADE`.

To look inside, run this from the project root:

```powershell
docker compose exec timescaledb psql -U tradinglab -d tradinglab
```

---

## 9. Where to find things

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

Migrations are in `core-service/src/main/resources/db/migration/`.

### Web: `web/src/`

| File | Job |
|---|---|
| `lib/auth.tsx` | Tokens, `apiFetch()`, `useAuth()` |
| `components/app-header.tsx` | Top navigation bar, shown on every page |
| `components/auth-form.tsx` | Shared login and sign-up form |
| `app/globals.css` | Colours for light and dark mode, plus shared styles |
| `app/page.tsx` | Home page |
| `app/login`, `app/register` | Auth pages |
| `app/watchlists/page.tsx` | Watchlist page and stock search |

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
| `marketdata/AlpacaAssetsClientTests` | The Alpaca request and response, with a fake server |
| `ModularityTests` | Module boundaries |

Run them all with `.\mvnw verify`. The flow tests need Docker.

---

## 10. Change log

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
- The auth cookie needs a shared site or a proxy once the web app and API are on different domains.
