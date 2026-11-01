# Trading Lab

An AI paper-trading and strategy platform with live US market data, a Java matching engine, a backtesting engine and GenAI features. See [PLAN.md](PLAN.md) for the design and roadmap.

**Educational use only — not investment advice. Paper money only.**

| Folder | What it is |
|---|---|
| `core-service/` | Spring Boot (Java 21, Maven): auth, market data, trading, portfolio, backtests |
| `ai-service/` | FastAPI (Python 3.12, uv): strategy parsing, research copilot, journal coach |
| `web/` | Next.js (TypeScript, Tailwind) |
| `contracts/` | JSON Schemas shared by the services |
| `infra/` | Prometheus, Grafana, OpenTelemetry and Caddy config |

## Prerequisites

JDK 21, Docker Desktop, Node.js LTS and [uv](https://docs.astral.sh/uv/). Maven isn't needed (`core-service` ships the wrapper), but `JAVA_HOME` must point at the JDK. uv installs Python 3.12 by itself.

## Run locally

```sh
cp .env.example .env
docker compose up -d
```

Set `JWT_SIGNING_KEY` in `.env` (`openssl rand -base64 32`); `core-service` won't start without it.

Then start each service in its own terminal:

```sh
cd core-service && ./mvnw spring-boot:run   # http://localhost:8080/actuator/health
cd ai-service && uv run fastapi dev app/main.py --port 8000   # http://localhost:8000/health
cd web && npm install && npm run dev   # http://localhost:3000
```

On Windows PowerShell, use `.\mvnw` instead of `./mvnw`.

Postgres is published on host port **5433**, not 5432, so it doesn't clash with a local PostgreSQL install.

## Test

```sh
cd core-service && ./mvnw verify    # context test needs Docker; it is skipped without it
cd ai-service && uv run pytest && uv run ruff check .
cd web && npm run lint && npx tsc --noEmit && npm run build
```
