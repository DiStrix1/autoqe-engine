# QE-RAG System — Changes Reference

---

## #1 — Removed orphaned `spring.jpa.*` properties
**File:** `backend/src/main/resources/application.properties`
**Fix:** Deleted 4 dead `spring.jpa.*` properties silently ignored since JPA is not on the classpath (only `JdbcTemplate` is used).

---

## #2 — Added `Dockerfile` + Docker Compose entry for parser-service
**Files:** `parser-service/Dockerfile`, `docker-compose.yml`
**Fix:** Created a `Dockerfile` (Python 3.11-slim) and added a `parser-service` entry in `docker-compose.yml` with `depends_on` on both databases.

---

## #3 — Fixed Maven output deadlock in `TestRunnerService`
**File:** `backend/.../runner/TestRunnerService.java`
**Fix:** A background daemon thread (`maven-output-drainer`) now drains the output stream concurrently while the main thread waits for process exit, eliminating the `readAllBytes()` + `waitFor()` deadlock.

---

## #4 — Added missing sandbox tests + fixed broken existing tests
**New files:** `NotificationServiceTest`, `PaymentGatewayTest`, `UserTest`, `UserServiceTest`
**Fixed:** `DataRepositoryTest` (rewritten — was accessing private fields), `BankAccountTest` (wrong exception type: `IllegalArgumentException` → `IllegalStateException`).

---

## #5 — Added backend unit tests for `TestRunnerService`
**File:** `backend/.../runner/TestRunnerServiceTest.java`
**Fix:** 20 unit tests via reflection covering all 5 private helper methods + the `MAX_RETRY_ATTEMPTS` constant.

---

## #6 — Hardened `VectorQueryTool`
**File:** `backend/.../tools/VectorQueryTool.java`
**Fix:** Added 384-dimension assertion; clamped `topK` to a max of 10.

---

## #7 — Clarified `McpToolConfig` as intentional placeholder
**File:** `backend/.../config/McpToolConfig.java`
**Fix:** Updated Javadoc to explicitly state this is a deliberate reservation with a "DO NOT remove" note.

---

## #8 — Exposed `MAX_RETRY_ATTEMPTS` constant
**Files:** `TestGenerationController.java`, `TestRunnerService.java`
**Fix:** Made `MAX_RETRY_ATTEMPTS` `public static final`; controller now references it instead of the hardcoded literal `3`.

---

## #9 — Added `.env.example` for parser-service
**File:** `parser-service/.env.example`
**Fix:** Documents all 9 required environment variables with defaults.

---

## #10 — Added `README.md`
**File:** `README.md`
**Fix:** Full developer guide: prerequisites, 5-step startup sequence, API examples, env var table, project structure, and troubleshooting.

---

## #11 — Confirmed upsert-on-re-ingest (no code change)
**File:** `parser-service/parser/vectorizer.py`
**Note:** `_SQL_UPSERT_EMBEDDING` already uses `ON CONFLICT (method_id) DO UPDATE SET ...`. Neo4j uses `MERGE` idempotently. No change required.

---

## #12 — Fixed CORS wildcard in parser-service
**Files:** `parser-service/main.py`, `parser-service/.env.example`
**Fix:** Origins now read from `PARSER_ALLOWED_ORIGINS` env var. `allow_credentials=True` removed (was invalid alongside `allow_origins=["*"]`).

---

## #13 — Test file cleanup on failed runs
**File:** `backend/.../runner/TestRunnerService.java`
**Fix:** Added `cleanupTestFile(Path)` helper. Failed test files are deleted after `FAILED_AFTER_HEALING` or heal-agent error. Passed tests are kept.

---

## #14 — Raw source file now passed to `RetrievalAgent`
**Files:** `backend/.../agents/RetrievalAgent.java`, `TestGenerationController.java`
**Fix:** Added `fileContent` as a third parameter to `gatherCodeContext()` to ground retrieval directly in source rather than relying on the planner's text summary.

---

## #15 — Path traversal input validation on `targetFilePath`
**Files:** `TestGenerationController.java`, `pom.xml`
**Fix:** Added `spring-boot-starter-validation`. Path normalised with `.toAbsolutePath().normalize()`, checked for `..` sequences and `.java` extension. Invalid paths → HTTP 400 `INVALID_PATH`.

---

## #16 — Micrometer pipeline metrics via Spring Actuator
**Files:** `backend/.../metrics/QeMetrics.java`, `TestGenerationController.java`
**Fix:** Four Micrometer counters: `qe.tests.generated`, `qe.tests.passed`, `qe.tests.failed`, `qe.tests.retry.total`. Exposed at `/actuator/metrics/*`.

---

## #17 — Parser service `GET /status` DB snapshot endpoint
**File:** `parser-service/main.py`
**Fix:** Returns live counts from both PostgreSQL and Neo4j (`pg_method_count`, `neo4j_class_count`, `neo4j_method_count`, `errors`).

---

## #18 — Expanded Mockito heuristic in `ensureStaticImports`
**File:** `backend/.../runner/TestRunnerService.java`
**Fix:** Extended detection to also match `@InjectMocks`, `@Spy`, `@Captor`, `@MockitoBean` to prevent missing `import static org.mockito.Mockito.*` compile errors.

---

## #19 — Configurable LLM temperature
**Files:** `backend/.../config/OllamaConfig.java`, `application.properties`
**Fix:** Temperature injected via `@Value("${langchain4j.ollama.chat-model.temperature:0.0}")` — was hardcoded `0.0`.

---

## #20 — Async job queue for test generation
**Files:** `AsyncTestGenerationService.java`, `AsyncConfig.java`, `TestGenerationJob.java`, `TestGenerationController.java`
**Fix:** `POST /api/v1/generate-tests/async` returns a `jobId` immediately. `GET /api/v1/generate-tests/status/{jobId}` polls progress. `ThreadPoolTaskExecutor` (core=2, max=4, queue=10).

---

## #21 — Conditional MCP activation in `McpToolConfig`
**File:** `backend/.../config/McpToolConfig.java`
**Fix:** Added `qe.mcp.enabled` flag with `@PostConstruct` startup log. Set `true` to opt into protocol-level tool discovery.

---

## #22 — Incremental ingestion with SHA-256 file hashing
**Files:** `parser-service/parser/vectorizer.py`, `parser-service/main.py`
**Fix:** `file_hash VARCHAR(64)` column added via `ALTER TABLE IF NOT EXISTS`. Methods from unchanged files are skipped. `vectors_skipped` included in `IngestResponse`.

---

## #23 — Reverse graph traversal: `getMethodCallers()`
**File:** `backend/.../tools/GraphQueryTool.java`
**Fix:** Added `getMethodCallers(String methodId)` `@Tool` to find inbound `CALLS` — who calls the method under test, critical for generating integration-style mocking context.

---

## #24 — Configurable embedding dimensions
**Files:** `VectorQueryTool.java`, `OllamaConfig.java`, `application.properties`, `.env.example`
**Fix:** `qe.embedding.expected-dimensions=384` in `application.properties`. Both `VectorQueryTool` and `OllamaConfig` inject it via `@Value`. Python side documented in `.env.example`.

---

## #25 — End-to-end integration test scaffold
**File:** `backend/src/test/java/.../integration/TestGenerationIntegrationTest.java`
**Fix:** `@SpringBootTest + @AutoConfigureMockMvc` class with 4 active tests (no external deps): empty body → 400, non-.java path → 4xx, path traversal → 4xx, non-existent file → `FILE_NOT_FOUND`. 2 `@Disabled` stubs for full pipeline.

---

## #26 — AutoQE Studio frontend integration
**Files:** `frontend/src/services/api.ts`, `frontend/src/routes/index.tsx`, `frontend/src/components/autoqe/*`, `TestGenerationController.java`, `parser-service/main.py`
**Fix:** Integrated TanStack Start + React 19 frontend. Built `api.ts` normalization layer (snake_case → camelCase). Wired dynamic BubbleMap, system health polling, and live terminal output. Added `GET /api/v1/health` and `GET /api/v1/file-content` backend endpoints.

---

## #27 — Lovable ingestion pipeline & directives sync
**Files:** `IngestModal.tsx`, `GuidanceBanner.tsx`, `DirectivesPopover.tsx`
**Fix:** Phased visual feedback (Tree-sitter → Neo4j → pgvector) wired into real `ingestRepository()`. Post-ingestion guidance banner with 1-click class focus CTA.

---

## #28 — UI streamlining & live terminal pipeline
**Files:** `TopNav.tsx`, `Workbench.tsx`, `src/routes/index.tsx`
**Fix:** Removed health badges from TopNav. Eliminated redundant "Agent Squad Inspector" tab. Added dedicated Agent Squad section above squad cards. Connected live terminal streaming to ingestion and generation lifecycle events.

---

## #29 — End-to-end custom agent directives & strategy synthesis
**Files:** `TestGenerationRequest.java`, `PlannerAgent.java`, `GeneratorAgent.java`, `DirectivesPopover.tsx`
**Fix:** `directives` and `strategy` fields wired end-to-end: request model → controller → async service → LangChain4j prompt templates. Frontend supports 4 presets and quick-inject chips. Active directives reflected in TopNav pill.

---

## #30 — SSE streaming endpoint for real-time test generation
**Files:** `backend/.../controller/TestGenerationWebSocketController.java`, `frontend/src/services/api.ts`
**Fix:** Added `GET /api/v1/generate-tests/stream` SSE endpoint. `streamTestGeneration()` in `api.ts` subscribes to `progress` and `complete` events, eliminating polling. Frontend terminal updates in real time as pipeline stages progress.

---

## #31 — JaCoCo coverage parsing & reporting
**Files:** `backend/.../runner/JaCoCoReportParser.java`, `TestGenerationPipelineOrchestrator.java`, `TestGenerationResponse.java`
**Fix:** `JaCoCoReportParser` reads the Maven-generated `jacoco.xml` after a passing test run and extracts instruction coverage %. Coverage is included in `TestGenerationResponse` and displayed in `CoverageInspector.tsx`.

---

## #32 — Multi-project workspace store
**Files:** `frontend/src/stores/projectStore.ts`, `ProjectSwitcher.tsx`
**Fix:** `useProjectStore` hook persists named project profiles (name + `repoPath`) in `localStorage`. `ProjectSwitcher` in the command palette allows switching without re-ingesting.

---

## #33 — TypeScript `exactOptionalPropertyTypes` fixes in `api.ts`
**File:** `frontend/src/services/api.ts`
**Fix:**
- Added `vectors_skipped` and `elapsed_seconds` snake_case fallback fields to `RawIngestResponse` (were used in `??` chain but absent from the interface → TS error).
- Replaced `coverage: expr | undefined` assignments with the spread pattern `...(coverage !== undefined && { coverage })` in both `generateTests()` and the SSE `resolve()` to satisfy `exactOptionalPropertyTypes: true`.

---

## #34 — Prometheus + Grafana observability stack
**Files:** `docker-compose.yml`, `monitoring/prometheus/prometheus.yml`, `monitoring/grafana/dashboards/autoqe.json`, `monitoring/grafana/provisioning/`
**Fix:** Added `prometheus` and `grafana` services to Docker Compose. Prometheus scrapes `host.docker.internal:8080/actuator/prometheus` every 15 s. Grafana auto-provisions the `autoqe.json` dashboard showing pipeline pass rate, retry depth, and failure trends. Available at `http://localhost:3001` (admin/admin).

---

## #35 — Configurable async execution pool with backpressure (#B-07)
**Files:** `backend/.../config/AsyncConfig.java`, `application.properties`, `.env.example`
**Fix:** Externalized async thread pool sizing via `qe.async.core-pool-size` (default: 2), `qe.async.max-pool-size` (default: 4), and `qe.async.queue-capacity` (default: 10). Added `ThreadPoolExecutor.CallerRunsPolicy` to apply backpressure rather than dropping tasks under load.

---

## #36 — Structured JSON logging & Logstash encoder (#B-14)
**Files:** `backend/pom.xml`, `backend/src/main/resources/logback-spring.xml`
**Fix:** Added `net.logstash.logback:logstash-logback-encoder:7.4`. Configured `logback-spring.xml` with ANSI-highlighted console logs for dev/test and structured JSON logs (including MDC request IDs and stack traces) for production log aggregators.

---

## #37 — Distributed tracing & MDC correlation filter (#B-14, #B-15)
**Files:** `backend/.../config/MdcFilter.java`, `backend/pom.xml`, `application.properties`
**Fix:** Added `MdcFilter` (`OncePerRequestFilter` at `Ordered.HIGHEST_PRECEDENCE`) which extracts or generates a `UUID` request ID, populates SLF4J MDC with `requestId` and `traceId`, and returns the `X-Request-ID` header in responses. Added `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-otlp` dependencies with configurable OTLP exporter endpoint.

---

## #38 — Dual security filter chain configuration
**File:** `backend/.../config/SecurityConfig.java`
**Fix:** Created explicit `permitAllFilterChain` when `qe.security.enabled=false` (disabling CSRF and permitting all requests) alongside `apiKeyFilterChain` when `qe.security.enabled=true`. Fixed Spring Boot default behavior where having `spring-boot-starter-security` on classpath caused `403 Forbidden` on tests and local dev POST requests.

---

## #39 — Batch hash lookups for incremental ingestion (#P-01)
**File:** `parser-service/parser/vectorizer.py`
**Fix:** Replaced loop of individual `SELECT file_hash WHERE method_id = %s` queries with a single batch `SELECT method_id, file_hash WHERE method_id = ANY(%s)` query. Eliminates N+1 database roundtrips during incremental re-ingestion.

---

## #40 — React Error Boundary with recovery UI (#F-07)
**Files:** `frontend/src/components/ErrorBoundary.tsx`, `frontend/src/routes/__root.tsx`, `frontend/vite.config.ts`
**Fix:** Implemented a React class `ErrorBoundary` with reset capabilities, reload action, and collapsible stack trace inspection. Wrapped `RootComponent` in `__root.tsx`. Updated `vite.config.ts` to use native Rolldown minification for production bundles.

---

## #41 — Multi-class batch test generation endpoint (#B-08)
**Files:** `backend/.../model/BatchTestGenerationRequest.java`, `BatchTestGenerationResponse.java`, `TestGenerationController.java`, `TestGenerationIntegrationTest.java`
**Fix:** Created `POST /api/v1/generate-tests/batch` allowing bulk test generation across multiple classes in a single request. Validates input lists, runs pipeline sequentially, and aggregates overall results with duration metrics.

---

## #42 — Dry-run test synthesis mode (#B-09)
**Files:** `backend/.../model/TestGenerationRequest.java`, `TestStatus.java`, `TestGenerationPipelineOrchestrator.java`
**Fix:** Added `dryRun: boolean` to test generation requests and `DRY_RUN` to `TestStatus`. When enabled, synthesizes complete JUnit 5 test classes without disk I/O or Maven sandbox execution, ideal for previewing generated tests or dry-run validation.

---

## #43 — Assertion style & library configuration (#B-10)
**Files:** `backend/.../model/TestGenerationRequest.java`, `TestGenerationPipelineOrchestrator.java`
**Fix:** Added `assertionLibrary` option supporting `JUNIT5` (default), `ASSERTJ`, and `HAMCREST`. The orchestrator dynamically crafts GeneratorAgent prompt directives to use fluent assertions (`assertThat`) while preserving project import rules.

---

## #44 — Test generation run history persistence (#F-04)
**Files:** `frontend/src/stores/historyStore.ts`, `frontend/src/hooks/useTestGeneration.ts`
**Fix:** Implemented `useHistoryStore` persisting test generation runs, status, generated code, execution logs, and coverage in `localStorage` under `autoqe.test_history` with automated FIFO capping (max 50 runs).

---

## #45 — Side-by-side and unified diff viewer (#F-05)
**Files:** `frontend/src/components/autoqe/DiffViewer.tsx`, `Workbench.tsx`, `frontend/src/services/api.ts`
**Fix:** Built `DiffViewer` component featuring dual viewing modes ("Side-by-Side" and "Unified"), addition/deletion counters, and line numbering. Integrated as a first-class tab in `Workbench.tsx` and added `generateTestsBatch()` client method in `api.ts`.

---

## #46 — Cross-platform developer management CLI scripts (#D-02)
**Files:** `scripts/dev.ps1`, `scripts/dev.sh`
**Fix:** Created unified CLI management scripts for Windows PowerShell and Linux/macOS Bash supporting commands `up`, `down`, `restart`, `status`, `health`, and `test`. Enables single-command management of all background services and quick verification of system health.

---

## #47 — GitHub Actions continuous integration workflow (#D-01)
**File:** `.github/workflows/ci.yml`
**Fix:** Added multi-job CI workflow running in parallel:
- `backend-orchestrator`: JDK 17, `mvn test-compile test`
- `test-sandbox`: JDK 17, `mvn test jacoco:report` (validating all 92 unit tests)
- `frontend-studio`: Node 20, `npm ci`, `npm run build`
- `parser-service`: Python 3.11, dependency install, bytecode compilation

---

## #48 — Pre-commit hook configuration for developer hygiene (#D-03)
**File:** `.pre-commit-config.yaml`
**Fix:** Configured automated pre-commit checks for trailing whitespace, end-of-file fixers, YAML/JSON validation, merge conflict detection, and large file size limits.

---

## #49 — Comprehensive production deployment & operations guide (#E-01)
**File:** `DEPLOYMENT.md`
**Fix:** Authored a complete production deployment architecture guide featuring an end-to-end Mermaid diagram, production pre-flight security checklist, Nginx SSL reverse-proxy configurations, environment variable hardening, and database backup procedures.

---

## #50 — Comprehensive Security Audit & Hardening Remediation
**Files:** `SecurityConfig.java`, `JavaSourceSanitizer.java`, `TestRunnerService.java`, `WorkspacePathTranslator.java`, `RateLimitInterceptor.java`, `GlobalExceptionHandler.java`, `parser-service/main.py`, `LandingPage.tsx`, `WebSocketConfig.java`, `nginx/autoqe.conf`
**Fix:**
- **Constant-time API Key Auth & SecurityContext**: Fixed broken authentication filter when `qe.security.enabled=true` by setting authenticated `SecurityContextHolder` principal and using `MessageDigest.isEqual` to eliminate timing side-channel attacks.
- **Command & Argument Injection Elimination**: Enforced strict Java identifier validation (`^[a-zA-Z_$][a-zA-Z0-9_$]*$`) on `testClassName` in `TestRunnerService.runTest` prior to Windows `cmd /c` Maven invocation; enforced path containment on test file writes.
- **LLM Sandbox Escape Guardrails**: Expanded `JavaSourceSanitizer.DANGEROUS_PATTERNS` to catch `Process`, `ClassLoader`, `MethodHandles`, `InitialContext` (JNDI), `ScriptEngine`, `java.lang.instrument`, and dangerous internal reflections before saving or running generated tests.
- **Path Traversal & Directory Escape Prevention**: Hardened `WorkspacePathTranslator` to reject traversal characters (`..`, `\0`) and normalize all container and relative paths against `hostRoot`. Added handlers in `GlobalExceptionHandler` for `IllegalArgumentException` (400) and `SecurityException` (403).
- **Rate Limiter Memory Exhaustion Protection**: Capped `RateLimitInterceptor` tracked IP map at 10,000 entries with automatic cache pruning, and sanitized `X-Forwarded-For` client IP resolution against header and log injection.
- **Parser Service Ingestion Hardening**: Hardened `parser-service/main.py` `/ingest` to reject traversal sequences, sensitive root filesystems (`/`, `C:\`), and restrict paths within configured `WORKSPACE_ROOT`.
- **Frontend XSS Elimination**: Replaced `dangerouslySetInnerHTML` in `LandingPage.tsx` with safe standard React JSX.
- **Nginx & WebSocket Hardening**: Sanitized multiline CSP headers in `nginx/autoqe.conf`, added reverse proxy upgrade headers for WebSockets, and bound `WebSocketConfig` origin patterns dynamically to configured CORS origins.

---

## Build Verification

| Module | Command | Result |
|--------|---------|--------|
| Frontend | `npm run build` | ✅ 0 errors (client + SSR bundle) |
| Backend | `.\mvnw.cmd test-compile` | ✅ BUILD SUCCESS (37 source files) |
| Sandbox | `.\mvnw.cmd test-compile` | ✅ BUILD SUCCESS |
| Backend tests | `.\mvnw.cmd test` | ✅ 38 tests, 0 failures |
| Sandbox tests | `.\mvnw.cmd test` | ✅ 92 tests, 0 failures |



