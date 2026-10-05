# Production Deployment & Architecture Guide

This guide describes how to deploy, configure, secure, and monitor the **QE-RAG System** in production environments.

---

## 1. System Architecture

```mermaid
graph TD
    Client["Browser / Enterprise Client"] -->|HTTPS :443| Nginx["Nginx Reverse Proxy & SSL Termination<br/>(HSTS, CSP, Permissions-Policy, Rate Limit)"]
    
    subgraph Frontend Tier
        Nginx -->|/ (HTTP)| WebApp["AutoQE Studio (Vite/React SSR)<br/>Port 3000"]
    end

    subgraph Orchestration & API Tier
        Nginx -->|/api/** (HTTP + SSE / WS)| SpringBoot["Spring Boot Orchestrator<br/>Port 8080<br/>• API Key Auth (SecurityConfig)<br/>• Bucket4j Rate Limiting<br/>• MDC Correlation Filter<br/>• Async Thread Pool (CallerRunsPolicy)<br/>• LangChain4j Multi-Agent Pipeline"]
        Nginx -->|/parser/** (HTTP)| ParserSvc["Python Parser Service<br/>Port 8000 (FastAPI + Tree-Sitter)<br/>• AST & Method Call Graph Extraction<br/>• Incremental Ingestion (Batch Hashing)<br/>• Sentence-Transformers (all-MiniLM-L6-v2)"]
    end

    subgraph Data & Vector Storage
        SpringBoot -->|Bolt :7687| Neo4j[("Neo4j 5 Graph DB<br/>• Class/Method Nodes<br/>• HAS_METHOD & CALLS Edges")]
        ParserSvc -->|Bolt :7687| Neo4j
        SpringBoot -->|JDBC :5432| PgVector[("PostgreSQL 16 + pgvector<br/>• 384-dim HNSW Cosine Index<br/>• SHA-256 Incremental Hashes")]
        ParserSvc -->|psycopg2 :5432| PgVector
    end

    subgraph Local LLM Inference
        SpringBoot -->|REST :11434| Ollama["Ollama LLM Engine<br/>• Llama 3: 8B<br/>• num_ctx=8192 (Prompt Context)"]
    end

    subgraph Observability Stack
        Prometheus["Prometheus TSDB<br/>Port 9090"] -->|Scrape :8080/actuator/prometheus| SpringBoot
        Grafana["Grafana Dashboard<br/>Port 3001"] -->|Query| Prometheus
        SpringBoot -->|OTLP Traces :4318| OtelCollector["OpenTelemetry Collector / APM"]
    end
```

---

## 2. Production Pre-Flight Checklist

Before deploying to production, verify each item:

| Category | Requirement | Production Setting |
|---|---|---|
| **Security** | API Key Authentication | Set `qe.security.enabled=true` and provide `QE_SECURITY_API_KEYS` |
| **Security** | CORS Origins | Set `QE_CORS_ALLOWED_ORIGINS` to your real domain (e.g. `https://autoqe.yourcompany.com`) |
| **Security** | Path Traversal Guard | Set `qe.sandbox.allowed-root` to the absolute path of your workspace/sandbox |
| **Security** | Database Passwords | Set strong secrets for `POSTGRES_PASSWORD` and `NEO4J_PASSWORD` in `.env` |
| **Security** | Grafana Admin | Set `GRAFANA_ADMIN_PASSWORD` (never leave default `admin`) |
| **Security** | Port Scoping | Verify all container ports in `docker-compose.yml` bind to `127.0.0.1` |
| **Reliability** | Async Pool | Tune `qe.async.core-pool-size` and `qe.async.max-pool-size` to host CPU cores |
| **Reliability** | Rate Limiting | Configure `qe.ratelimit.capacity` based on expected traffic volume |
| **Observability** | Structured Logging | Activate Spring profile `prod` (`SPRING_PROFILES_ACTIVE=prod`) for JSON logging |
| **Observability** | Prometheus / Grafana | Provision `monitoring/prometheus/` and `monitoring/grafana/` volumes |

---

## 3. Environment Configuration

Create a production `.env` file in the project root:

```bash
# Generate strong secrets
POSTGRES_PASSWORD=$(openssl rand -hex 32)
NEO4J_PASSWORD=$(openssl rand -hex 32)
GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 16)
QE_SECURITY_API_KEYS=$(openssl rand -hex 32)

# Domain & CORS
QE_CORS_ALLOWED_ORIGINS=https://autoqe.yourcompany.com
PARSER_ALLOWED_ORIGINS=https://autoqe.yourcompany.com

# Async & Rate Limiting
QE_ASYNC_CORE_POOL_SIZE=4
QE_ASYNC_MAX_POOL_SIZE=8
QE_ASYNC_QUEUE_CAPACITY=25

# Observability
SPRING_PROFILES_ACTIVE=prod
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318/v1/traces
```

---

## 4. Reverse Proxy & SSL Configuration (Nginx)

Place [autoqe.conf](file:///d:/Projects/qe-rag-system/nginx/autoqe.conf) in `/etc/nginx/conf.d/` with your SSL certificates:

```nginx
server {
    listen 443 ssl http2;
    server_name autoqe.yourcompany.com;

    ssl_certificate /etc/letsencrypt/live/autoqe.yourcompany.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/autoqe.yourcompany.com/privkey.pem;

    # Security Headers
    add_header Strict-Transport-Security "max-age=63072000; includeSubDomains; preload" always;
    add_header X-Frame-Options "DENY" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;

    # Frontend
    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }

    # Backend API & SSE Streaming
    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 900s;
    }

    # WebSocket
    location /ws {
        proxy_pass http://127.0.0.1:8080/ws;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }
}
```

---

## 5. Backup & Disaster Recovery

### PostgreSQL / pgvector Backup
```bash
# Automated daily backup of embeddings database
docker exec -t qe_pgvector pg_dump -U dev_user -d code_vectors -F c -b -v -f /tmp/code_vectors.dump
docker cp qe_pgvector:/tmp/code_vectors.dump ./backups/code_vectors_$(date +%Y%m%d).dump
```

### Neo4j Graph Backup
```bash
# APOC graph export
docker exec -t qe_neo4j cypher-shell -u neo4j -p "$NEO4J_PASSWORD" \
  "CALL apoc.export.json.all('/data/graph_backup.json', {useTypes:true});"
```

---

## 6. Service Health & Diagnostics

Run the health check utility anytime:

```powershell
# Windows
.\scripts\dev.ps1 health

# Linux / macOS
./scripts/dev.sh health
```

Actuator endpoints available for load balancers:
- **Liveness Probe**: `GET /actuator/health/liveness` (HTTP 200)
- **Readiness Probe**: `GET /actuator/health/readiness` (HTTP 200)
- **Metrics**: `GET /actuator/prometheus` (Prometheus text format)
