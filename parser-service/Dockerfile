# =============================================================================
# parser-service/Dockerfile
# =============================================================================
# Multi-stage Python build for the QE-RAG Parser Service (FastAPI on Uvicorn).
#
# Build:  docker build -t qe-parser-service ./parser-service
# Run:    docker compose up  (see docker-compose.yml)
# =============================================================================

FROM python:3.11-slim AS base

# System dependencies required by psycopg2-binary and sentence-transformers
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        gcc \
        libpq-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install Python dependencies first (layer cached unless requirements change)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application source
COPY . .

# Expose FastAPI port
EXPOSE 8000

# Run with Uvicorn (reload=False for production/container use)
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--log-level", "info"]
