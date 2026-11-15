# Trading Lab

A paper-trading and strategy research platform: live US market data, a Java order-matching engine with lock-free concurrency control, a deterministic backtesting engine, Kafka event streaming, and Claude-powered features for drafting strategies, explaining results, coaching and researching SEC filings with citations.

**Educational use only. Paper money only. Not investment advice.**

## What it does

| Area | Features |
|---|---|
| Trading | Market, limit, stop and stop-limit orders; DAY and GTC time in force; idempotent order placement; a $100,000 paper account with positions and P&L |
| Market data | Live Alpaca quotes pushed over WebSocket (STOMP); candlestick charts from 5-minute to 5-year bars stored in TimescaleDB |
| Strategies | A JSON strategy language (DSL v1) with SMA, EMA, RSI, ATR, MACD and Bollinger bands, a shared JSON Schema, and a strict validator |
| Backtesting | Next-bar-open execution (no look-ahead), stop-loss and take-profit, fees, slippage, seven metrics against buy-and-hold, an equity and drawdown chart |
| AI (Claude) | Plain English to strategy with structured outputs and a validator repair loop; backtest explanations with every number verified; a trading-journal coach; a research copilot over SEC 10-K and 10-Q filings with native citations |
| Events | A transactional outbox (Spring Modulith) publishing order events to Kafka (Redpanda), keyed by account, with an idempotent consumer |
| Operations | Redis-backed rate limits, per-user AI token budgets, Prometheus metrics with a Grafana dashboard, Docker images and a Caddy HTTPS setup |

## Architecture

```text
                 Browser (Next.js 16, React 19, TypeScript)
                    │ REST + JWT          │ STOMP WebSocket (/ws)
                    ▼                     ▼
 ┌──────────────── core-service (Spring Boot 4, Java 21, modular monolith) ───────────────┐
 │ auth · marketdata · trading · portfolio · strategy · backtest · journal · ai · realtime │
 │ virtual threads · Flyway · Spring Modulith (module boundaries + event outbox)          │
 └───────┬──────────────┬──────────────┬────────────────┬──────────────────┬──────────────┘
         │              │              │                │                  │ X-Service-Token
         ▼              ▼              ▼                ▼                  ▼
  PostgreSQL 17    Redis 8         Redpanda         Alpaca API      ai-service (FastAPI)
  + TimescaleDB    quotes cache,   (Kafka API)      quotes, bars,     Claude Haiku 5.5 / Sonnet 5.5
  source of truth  rate limits,    order events     market clock      SEC EDGAR filings, evals
                   AI budgets      → journal                          budgets in Redis, logs in Postgres
```

Highlights worth reading in the code:

- **Concurrency without locks.** Cash and share reservations are single conditional `UPDATE`s, order status changes are compare-and-set, and `CHECK` constraints are the last line of defence. A test fires 12 simultaneous $30,000 orders at a $100,000 account and exactly 3 succeed. See `PortfolioService` and `OrderRepository.transition()`.
- **Exactly-once order placement.** An `Idempotency-Key` header backed by a unique index; concurrent retries of one key reserve cash once.
- **No look-ahead in backtests.** Rules are checked at a bar's close and fill at the next bar's open. Five years of daily bars run in about **13 ms**.
- **The LLM never does the maths.** Every number in an AI explanation or coaching note is checked against the engine's figures, and the model is asked again once if one isn't there.
- **Outbox to Kafka.** An order change and its event commit together in one transaction; Kafka delivery follows, retried until it succeeds, and the consumer ignores duplicates by event ID.

The full guide, covering request flows, every table and endpoint, concurrency, failure handling, security and scaling limits, is [docs/APP_FLOW.md](docs/APP_FLOW.md). The original design and roadmap are in [PLAN.md](PLAN.md).

| Folder | What it is |
|---|---|
| `core-service/` | Spring Boot (Java 21, Maven) |
| `ai-service/` | FastAPI (Python 3.12, uv) |
| `web/` | Next.js (TypeScript, Tailwind) |
| `contracts/` | JSON Schemas shared by the services |
| `infra/` | Prometheus, Grafana and Caddy configuration |

## Run locally

Prerequisites: JDK 21, Docker Desktop, Node.js 24 and [uv](https://docs.astral.sh/uv/). Maven isn't needed (`core-service` ships the wrapper).

```sh
cp .env.example .env          # then set JWT_SIGNING_KEY: openssl rand -base64 32
docker compose up -d          # Postgres + TimescaleDB, Redis, Redpanda
```

Start each service in its own terminal:

```sh
cd core-service && ./mvnw spring-boot:run                       # http://localhost:8080
cd ai-service && uv run fastapi dev app/main.py --port 8000     # optional: AI features
cd web && npm install && npm run dev                            # http://localhost:3000
```

On Windows PowerShell, use `.\mvnw`. Postgres is on host port **5433** so it doesn't clash with a local install.

Optional settings in `.env`:

| Setting | Turns on |
|---|---|
| `ALPACA_API_KEY_ID`, `ALPACA_API_SECRET_KEY` | Live prices and history (a free Alpaca paper account) |
| `AI_SERVICE_TOKEN` (any long random string) and `ANTHROPIC_API_KEY` | Strategy drafting, explanations, coaching and research |
| `SEC_USER_AGENT` (`"App name you@example.com"`, as SEC requires) | The research copilot's filing downloads |
| `KAFKA_ENABLED=false` | Runs without Redpanda; events stay inside core-service |

Monitoring: `docker compose --profile observability up -d`, then Grafana at http://localhost:3001 (dashboard "Trading Lab") and Prometheus at http://localhost:9090.

## Test

```sh
cd core-service && ./mvnw verify          # unit, integration (Testcontainers), WebSocket, Kafka, modularity
cd ai-service && uv run pytest && uv run ruff check .
cd web && npm run lint && npx tsc --noEmit && npm run build
cd web && npm run e2e                     # browser smoke test against running servers
cd ai-service && uv run python -m evals.run_evals   # LLM evals: real API calls, costs money
```

## Deploy

`docker-compose.prod.yml` runs everything on one server behind Caddy, which gets the HTTPS certificate by itself. Set `DOMAIN`, `JWT_SIGNING_KEY` and `POSTGRES_PASSWORD` in `.env`, point the domain's DNS at the server, then:

```sh
docker compose -f docker-compose.prod.yml --env-file .env up -d --build
```

Only ports 80 and 443 are published. The database, Redis, Kafka and metrics stay on the private network.

## Known limits

- One core-service instance: the matching engine is a single writer, and the STOMP broker is in memory. Scaling out needs leader election and a broker relay (see APP_FLOW.md, "Scaling limits").
- Backtests use daily bars, whole shares and up to 5 years of history, and today's stock list (survivorship bias).
- The research copilot uses Postgres full-text search; vector search with embeddings would improve recall on paraphrased questions.
- The LLM evals are written but need an API key to run, so no baseline scores are recorded yet.
