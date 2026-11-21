# Production FastAPI + Full-Duplex WebSocket Backend for ASHA Vaani
# Works natively on Railway, Render, Google Cloud Run, and Fly.io.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_SYSTEM_PYTHON=1 \
    PORT=8000

WORKDIR /app

# Install uv for deterministic locked dependency installation
COPY --from=ghcr.io/astral-sh/uv:0.6 /uv /usr/local/bin/uv

# Copy canonical 12-item NHM danger-sign list expected at /app/docs/danger_signs.json
COPY docs/danger_signs.json /app/docs/danger_signs.json

# Install backend dependencies first for layer caching
COPY backend/pyproject.toml backend/uv.lock /app/backend/
WORKDIR /app/backend
RUN uv sync --frozen --no-dev --no-install-project

# Copy backend application source (never .env or local sqlite db — excluded by .dockerignore)
COPY backend/app /app/backend/app

EXPOSE 8000

CMD ["sh", "-c", "uv run --no-sync uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000} --ws-ping-interval 20 --ws-ping-timeout 20"]
