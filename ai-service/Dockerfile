FROM python:3.12-slim
COPY --from=ghcr.io/astral-sh/uv:0.13.6 /uv /usr/local/bin/uv
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project
COPY app app
COPY prompts prompts
USER app
EXPOSE 8000
CMD ["/app/.venv/bin/fastapi", "run", "app/main.py", "--host", "0.0.0.0", "--port", "8000"]
