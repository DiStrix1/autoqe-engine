# AutoQE Engine — Developer Guide

Multi-Agent Quality Engineering RAG system that automatically generates JUnit 5 tests for a target Java codebase using a hybrid Neo4j graph + pgvector semantic search pipeline, powered by a local Ollama LLM, with an animated web studio (**AutoQE Studio**).

---

## Quick Start with Developer Scripts

Use the included cross-platform CLI scripts for single-command management:

```powershell
# Windows PowerShell
.\scripts\dev.ps1 up          # Start all containers (pgvector, neo4j, parser, prometheus, grafana)
.\scripts\dev.ps1 health      # Verify health probes across all services
.\scripts\dev.ps1 test        # Run backend, sandbox, and frontend verification test suites
```

```bash
# Linux / macOS
./scripts/dev.sh up
./scripts/dev.sh health
./scripts/dev.sh test
```

> For production deployment instructions, SSL termination, and security hardening, see [DEPLOYMENT.md](Documents/DEPLOYMENT.md).

---

## Architecture at a Glance

```
[frontend]       → AutoQE Studio (React/TanStack on Port 3000)
                   • Knowledge Bento & live DB/service health badges
                   • Side-by-Side & Unified Diff Viewer + AST Bubble Map
                   • Agent Squad progress animations & live SSE/WebSocket terminal streaming
                   • Client-side test generation run history store (localStorage)
                   ↓
[parser-service] → FastAPI (Port 8000) → parse Java AST → Neo4j graph + pgvector embeddings
                   • Incremental re-ingestion with batch SHA-256 hash checks
[backend]        → Spring Boot 3.x (Port 8080)
                   • Endpoints: POST /generate-tests, POST /generate-tests/batch, POST /generate-tests/async
                   • PlannerAgent → RetrievalAgent (Neo4j + pgvector) → GeneratorAgent (Ollama/Llama3)
                   • Security: API Key authentication, Bucket4j rate limiting, Path traversal guards
                   • Observability: Structured JSON logging (Logstash), MDC correlation, Micrometer + OTLP tracing
                   • TestRunnerService → write test → mvn test → self-heal up to 3 retries
                   ↓
[monitoring]     → Prometheus (Port 9090) + Grafana (Port 3001) for real-time QE pipeline metrics
[test-sandbox]   → Target Java codebase (com.qe.demo.*) the AI generates tests for
```

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java | 17+ | `java --version` |
| Maven | 3.9+ | or use the included `mvnw` wrapper |
| Python | 3.11+ | for the parser-service |
| Node.js & npm | 18+ / 20+ | for the AutoQE Studio frontend |
| Docker + Docker Compose | latest | for Neo4j, pgvector, Prometheus, Grafana |
| Ollama | latest | [ollama.com](https://ollama.com) — runs the local LLM (`llama3:8b`) |

---

## Step 1 — Start Infrastructure (Docker) & Ollama

```bash
docker compose up -d
```

Starts the container infrastructure:
- **`qe_pgvector`** — PostgreSQL + pgvector on port `5432`
- **`qe_neo4j`** — Neo4j on ports `7474` (browser) and `7687` (bolt)
- **`qe_parser_service`** — Python FastAPI parser on port `8000`
- **`qe_prometheus`** — Prometheus TSDB on port `9090`
- **`qe_grafana`** — Grafana Dashboard on port `3001`

Ensure the local LLM is running in Ollama:
```bash
ollama run llama3
```

---

## Step 2 — Start the Backend (Spring Boot)

```bash
cd backend
./mvnw spring-boot:run
```

> Windows: use `mvnw.cmd spring-boot:run`

The backend starts on **http://localhost:8080**.

Health checks:
```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/v1/health
```

---

## Step 3 — Start the AutoQE Studio Frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend will be available at **http://localhost:3000** (or http://localhost:5173).

Features available in AutoQE Studio:
- **Floating TopNav**: Live pulsing health status badges for DBs (`:7687`/`:5432`), Python (`:8000`), Spring (`:8080`), and Ollama (`:11434`), plus one-click repository ingestion.
- **Knowledge Bento**: Live counter metrics from AST parsing, Cypher relationships, and 384-dim embeddings, with target class selector cards (`UserService`, `BankAccount`, `MathUtils`, `PaymentProcessor`, `DataRepository`).
- **Workbench**: Split code view with real Java source code, generated JUnit 5 test suites, copy button, and interactive AST bubble map.
- **Agent Squad & Self-Healing Stream**: Multi-agent step indicators, automatic before/after repair patch cards, and a collapsible live Maven terminal stream.

---

## Step 4 — Ingest the Target Codebase (Parser Service)

You can click **"Ingest Codebase"** directly in the AutoQE Studio UI, or call the API via cURL:

```bash
curl -X POST http://localhost:8000/ingest \
  -H "Content-Type: application/json" \
  -d "{\"repo_path\": \"D:/Projects/qe-rag-system/test-sandbox\"}"
```

---

## Step 5 — Generate Tests with Self-Healing

Click **"Generate JUnit 5 Tests 🚀"** in AutoQE Studio, or call the backend directly:

```bash
curl -X POST http://localhost:8080/api/v1/generate-tests \
  -H "Content-Type: application/json" \
  -d "{
    \"targetClassName\": \"UserService\",
    \"targetFilePath\": \"D:/Projects/qe-rag-system/test-sandbox/src/main/java/com/qe/demo/UserService.java\"
  }"
```

**Status values:**
| Status | Meaning |
|---|---|
| `PASSED` | Tests generated, compiled, and passed |
| `FAILED_AFTER_HEALING` | Failed after 3 self-healing retry iterations |
| `PRE_COMPILE_FAILED` | Target source doesn't compile |
| `GENERATION_FAILED` | LLM failed to produce code |
| `INVALID_PATH` | File path traversal rejected or not a `.java` file |
| `FILE_NOT_FOUND` | `targetFilePath` does not exist |

---

## Step 6 — Run Sandbox Tests Manually

```bash
cd test-sandbox
./mvnw test
```

---

## Environment Variables

### Parser Service (`parser-service/.env`)
| Variable | Default | Description |
|---|---|---|
| `NEO4J_URI` | `bolt://localhost:7687` | Neo4j Bolt connection URI |
| `NEO4J_USER` | `neo4j` | Neo4j username |
| `NEO4J_PASSWORD` | `dev_password` | Neo4j password |
| `PG_HOST` | `localhost` | PostgreSQL host |
| `PG_PORT` | `5432` | PostgreSQL port |
| `PG_DB` | `code_vectors` | PostgreSQL database name |
| `PG_USER` | `dev_user` | PostgreSQL username |
| `PG_PASSWORD` | `dev_password` | PostgreSQL password |
| `PARSER_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173,...` | Allowed CORS origins |
| `PORT` | `8000` | Parser service port |

### Frontend (`frontend/.env`)
| Variable | Default | Description |
|---|---|---|
| `VITE_PYTHON_API_URL` | `http://localhost:8000` | Python AST parser endpoint |
| `VITE_SPRING_API_URL` | `http://localhost:8080` | Spring Boot backend orchestrator endpoint |

---

## Project Structure

```
autoqe-engine/
├── docker-compose.yml          # Container stack (pgvector, neo4j, parser-service, prometheus, grafana)
├── README.md                   # Primary project overview and quick start
├── Documents/                  # Architecture specs, design tokens, changelog & guides
│   ├── CHANGES.md              # Detailed changelog reference
│   ├── DEPLOYMENT.md           # Production deployment & SSL guide
│   ├── DESIGN.md               # Frontend design tokens & component style system
│   ├── PRODUCT.md              # Product requirements & capabilities
│   └── PROJECT_SPECIFICATION.md# Architectural design document
├── scripts/                    # Automation scripts
│   ├── dev.ps1                 # Windows PowerShell dev CLI
│   ├── dev.sh                  # Unix/macOS Bash dev CLI
│   └── start-ollama.bat        # Windows helper to start Ollama
├── frontend/                   # AutoQE Studio (React 19 + TanStack + Tailwind CSS)
├── backend/                    # Java Spring Boot 3.x — AI Agent Orchestrator
├── parser-service/             # Python FastAPI — AST parsing, Tree-sitter, Neo4j & pgvector ingestion
└── test-sandbox/               # Target Java project for AI test generation
```