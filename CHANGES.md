# QE-RAG System — Changes Reference

---

## #1 — Removed orphaned `spring.jpa.*` properties
**File:** `backend/src/main/resources/application.properties`
**Why:** The 4 `spring.jpa.*` lines were silently ignored — JPA is not on the classpath, only `JdbcTemplate` is used.
**Fix:** Deleted the 4 dead properties and added a clarifying comment.

---

## #2 — Added `Dockerfile` + Docker Compose entry for parser-service
**Files:** `parser-service/Dockerfile` *(new)*, `docker-compose.yml`
**Why:** The Python parser-service had to be started manually; `docker compose up` only started the two databases.
**Fix:** Created a `Dockerfile` (Python 3.11-slim) and added a `parser-service` entry in `docker-compose.yml` with `depends_on` on both databases.

---

## #3 — Fixed Maven output deadlock in `TestRunnerService`
**File:** `backend/.../runner/TestRunnerService.java`
**Why:** `readAllBytes()` and `waitFor()` were called sequentially — if Maven output exceeded the OS buffer, both sides would block forever (deadlock).
**Fix:** A background daemon thread (`maven-output-drainer`) now drains the output stream concurrently while the main thread waits for the process to exit.

---

## #4 — Added missing test files + fixed pre-existing broken tests
**New files:** `NotificationServiceTest`, `PaymentGatewayTest`, `UserTest`, `UserServiceTest`
**Fixed:** `DataRepositoryTest` (rewritten — was accessing private fields illegally), `BankAccountTest` (2 assertions used wrong exception type: `IllegalArgumentException` instead of `IllegalStateException`).
**Why:** 4 sandbox classes had no tests; 2 existing test files had compile/assertion errors.
**Fix:** Wrote correct JUnit 5 + Mockito tests using only the public API and concrete types.

---

## #5 — Added backend unit tests for `TestRunnerService`
**File:** `backend/.../runner/TestRunnerServiceTest.java` *(new)*
**Why:** The backend had zero tests. Critical string-parsing helpers (`extractCodeBlock`, `extractPackageName`, etc.) were completely untested.
**Fix:** 20 unit tests via reflection covering all 5 private helper methods + the `MAX_RETRY_ATTEMPTS` constant.

---

## #6 — Hardened `VectorQueryTool`
**File:** `backend/.../tools/VectorQueryTool.java`
**Why:** No validation on embedding dimensions (a model swap would produce a cryptic SQL error) and no cap on `topK` (LLM could request thousands of results).
**Fix:** Added a 384-dimension assertion and clamped `topK` to a max of 10.

---

## #7 — Clarified `McpToolConfig` as intentional placeholder
**File:** `backend/.../config/McpToolConfig.java`
**Why:** The empty class looked broken or forgotten. Audit confirmed it is intentional — tools are registered via `OllamaConfig` for now.
**Fix:** Updated Javadoc to explicitly state this is a deliberate reservation with a "DO NOT remove" note.

---

## #8 — Fixed hardcoded magic number in `TestGenerationController`
**Files:** `TestGenerationController.java`, `TestRunnerService.java`
**Why:** The controller logged the retry count as the literal `3` — if the constant changed, the log would silently be wrong.
**Fix:** Made `MAX_RETRY_ATTEMPTS` `public static final`; controller now references it directly.

---

## #9 — Added `.env.example` for parser-service
**File:** `parser-service/.env.example` *(new)*
**Why:** The Python service reads 9 environment variables but none were documented — a new developer had to read the source to find them.
**Fix:** Created `.env.example` listing all required variables with their defaults.

---

## #10 — Added `README.md`
**File:** `README.md` *(new)*
**Why:** No developer-facing guide existed — startup order, API calls, and env vars were undocumented.
**Fix:** Created a full `README.md` with prerequisites, 5-step startup sequence, API examples, status codes, env var table, project structure, and troubleshooting.

---

## #11 — Confirmed upsert-on-re-ingest already in place (no code change)
**File:** `parser-service/parser/vectorizer.py`
**Why:** Re-ingesting the same repo risked duplicate rows in `code_embeddings`.
**Fix:** Audit confirmed `_SQL_UPSERT_EMBEDDING` already uses `ON CONFLICT (method_id) DO UPDATE SET ...`. Neo4j uses `MERGE` idempotently. No additional change required; documented here for completeness.

---

## #12 — Fixed CORS wildcard (`allow_origins=["*"]` + `allow_credentials=True`)
**File:** `parser-service/main.py`, `parser-service/.env.example`
**Why:** `allow_origins=["*"]` combined with `allow_credentials=True` is invalid CORS — all modern browsers reject it. It also exposes the API to any origin in a networked deployment.
**Fix:** Allowed origins are now read from the `PARSER_ALLOWED_ORIGINS` environment variable (comma-separated). Default is `http://localhost:3000,http://localhost:8080`. `allow_credentials` was removed (defaults to `False`). New env var documented in `.env.example`.

---

## #13 — Test file cleanup on failed runs
**File:** `backend/.../runner/TestRunnerService.java`
**Why:** Every failed generation attempt wrote a `.java` file to the sandbox's `src/test/java/` directory and never deleted it. Stale broken files accumulated across runs and could interfere with subsequent Maven executions.
**Fix:** Added `cleanupTestFile(Path)` helper. The written path is tracked via `AtomicReference<Path>`. On every non-PASSED terminal return (`FAILED_AFTER_HEALING`, self-healing agent error), the test file is deleted. Files are only kept when the run `PASSED`.

---

## #14 — Raw source file now passed to `RetrievalAgent`
**Files:** `backend/.../agents/RetrievalAgent.java`, `TestGenerationController.java`
**Why:** `RetrievalAgent.gatherCodeContext()` previously received only the LLM-generated test plan. The LLM had to infer the class structure entirely from the plan text, making retrieval fragile against inconsistent planner output.
**Fix:** Added `@V("fileContent") String fileContent` as a third parameter to `gatherCodeContext()`. The raw source file is included in the `@UserMessage` prompt as a grounding anchor. The controller passes `fileContent` from the file-read stage.

---

## #15 — Path traversal input validation on `targetFilePath`
**Files:** `TestGenerationController.java`, `pom.xml`
**Why:** `targetFilePath` was used directly in `Paths.get()` and `Files.readString()` with no validation — a caller could pass `../../etc/passwd` or any path outside the intended sandbox.
**Fix:** Added `spring-boot-starter-validation` to `pom.xml`. In the controller, the path is resolved and normalised with `.toAbsolutePath().normalize()` before use. The normalised string is checked for `..` sequences and the `.java` extension. Invalid paths return HTTP 400 with status `INVALID_PATH`. `@Valid` annotation added to both `@RequestBody` parameters.

---

## #16 — Micrometer pipeline metrics via Spring Actuator
**Files:** `backend/.../metrics/QeMetrics.java` *(new)*, `TestGenerationController.java`
**Why:** There was no way to observe pass rate, failure rate, or retry depth without grepping raw log files. Operators had no quantitative dashboard for pipeline health.
**Fix:** Created `QeMetrics` `@Component` registering four counters: `qe.tests.generated`, `qe.tests.passed`, `qe.tests.failed`, `qe.tests.retry.total`. The controller calls `qeMetrics.recordGeneration(status, attempts)` after every pipeline completion. Metrics are accessible at `GET /actuator/metrics/qe.tests.generated` (etc.).

---

## #17 — Parser service `GET /status` DB snapshot endpoint
**File:** `parser-service/main.py`
**Why:** Beyond `/health`, there was no way to check how many classes/methods were stored, or whether both databases were reachable, without directly querying them.
**Fix:** Added `GET /status` endpoint that queries `SELECT COUNT(*) FROM code_embeddings` from PostgreSQL and `MATCH (c:CLASS)` / `MATCH (m:METHOD)` counts from Neo4j. Returns partial results when one DB is unavailable, with an `errors` list. Response model: `{status, pg_method_count, neo4j_class_count, neo4j_method_count, errors}`.

---

## #18 — Expanded Mockito heuristic in `ensureStaticImports`
**File:** `backend/.../runner/TestRunnerService.java`
**Why:** The Mockito detection checked only for `"Mockito"` or `"@Mock"` literals. Generated code using `@InjectMocks`, `@Spy`, `@Captor`, or `@MockitoBean` (Spring Boot 3 annotation) without the other markers would not get the `import static org.mockito.Mockito.*` injection, causing compile errors.
**Fix:** Extended the boolean `containsMockito` check to also match `@InjectMocks`, `@Spy`, `@Captor`, and `@MockitoBean`.

---

## #19 — Configurable LLM temperature
**Files:** `backend/.../config/OllamaConfig.java`, `backend/src/main/resources/application.properties`
**Why:** `temperature(0.0)` was hardcoded in `OllamaChatModel.builder()`. Experimenting with different temperature values (e.g., slightly higher for more creative test strategies) required recompiling.
**Fix:** Added `@Value("${langchain4j.ollama.chat-model.temperature:0.0}")` field. The property already existed in `application.properties` (line 40) but was not wired into the builder. Now wired — default of `0.0` preserves the original deterministic behaviour.

---

## #20 — Async job queue for long-running test generation
**Files:** `backend/.../runner/AsyncTestGenerationService.java` *(new)*, `backend/.../config/AsyncConfig.java` *(new)*, `backend/.../model/TestGenerationJob.java` *(new)*, `TestGenerationController.java`
**Why:** `POST /api/v1/generate-tests` is synchronous and blocks the HTTP thread for the duration of the full pipeline (potentially 3–10+ minutes with Ollama retries). HTTP clients time out and there is no way to query progress.
**Fix:** Added two new endpoints:
- `POST /api/v1/generate-tests/async` — submits a job, returns `{"jobId": "..."}` immediately.
- `GET /api/v1/generate-tests/status/{jobId}` — returns the current `TestGenerationJob` snapshot.

`AsyncTestGenerationService` runs the full pipeline via `@Async("qeTaskExecutor")`. `AsyncConfig` provides a `ThreadPoolTaskExecutor` (core=2, max=4, queue=10). Jobs are stored in a `ConcurrentHashMap` (replace with a persistent store for production).

---

## #21 — Conditional MCP tool provider in `McpToolConfig`
**File:** `backend/.../config/McpToolConfig.java`
**Why:** The class was an acknowledged placeholder but had no activation path — enabling real MCP required code changes.
**Fix:** Added `@Value("${qe.mcp.enabled:false}")` property. On startup, a `@PostConstruct` method logs whether MCP is active or static tool registration is in effect. Set `qe.mcp.enabled=true` in `application.properties` to opt in to protocol-level tool discovery when the `McpToolProvider` is wired.

---

## #22 — Incremental ingestion with SHA-256 file hashing
**Files:** `parser-service/parser/vectorizer.py`, `parser-service/main.py`
**Why:** Every `/ingest` call re-embedded all `.java` files in the target repo, even unchanged ones. For large codebases this meant minutes of unnecessary GPU/CPU work on every re-ingest.
**Fix:** Added `file_hash VARCHAR(64)` column to `code_embeddings` (via `ALTER TABLE IF NOT EXISTS` for zero-downtime upgrade). Before embedding, `vectorizer.py` computes `SHA-256` of each source file and skips methods whose stored hash matches. The `IngestResponse` now includes a `vectors_skipped` field. File hashes are stored alongside embeddings and updated on change.

---

## #23 — Reverse graph traversal: `getMethodCallers()`
**File:** `backend/.../tools/GraphQueryTool.java`
**Why:** `GraphQueryTool` only exposed outbound `CALLS` traversal (what a method calls). There was no way for the retrieval agent to find which methods depend on the method under test — critical context for generating integration-style tests that need to mock those callers.
**Fix:** Added `getMethodCallers(String methodId)` `@Tool` method that runs `MATCH (caller:METHOD)-[:CALLS]->(m:METHOD {id: $id}) RETURN caller.*`. Returns a list of all callers within the parsed corpus. Gracefully reports methods not found as entry points.

---

## #24 — Configurable embedding model dimensions
**Files:** `backend/.../tools/VectorQueryTool.java`, `backend/.../config/OllamaConfig.java`, `backend/src/main/resources/application.properties`, `parser-service/.env.example`
**Why:** The 384-dimension constant was hardcoded in `VectorQueryTool` (assertion) and `OllamaConfig` (log). Swapping to a different embedding model required code changes in two places, and there was no coordinated documentation that both services must be updated together.
**Fix:** Added `qe.embedding.expected-dimensions=384` to `application.properties`. `VectorQueryTool` injects this via `@Value` and uses it in the dimension assertion. `OllamaConfig` logs it at startup. Python side documented via `EMBEDDING_DIM=384` in `.env.example` with a warning that both must stay in sync.

---

## #25 — End-to-end integration test scaffold
**File:** `backend/src/test/java/com/qe/agent/integration/TestGenerationIntegrationTest.java` *(new)*
**Why:** All 71 existing tests were unit tests using mocks. No test exercised the full HTTP → validation → pipeline path. Path traversal and file-type rejection behaviour was untested.
**Fix:** Added `@SpringBootTest + @AutoConfigureMockMvc` integration test class with:
- **4 active tests** (no external dependencies): empty body → 400, non-.java path → 4xx, path traversal `../../` → 4xx, non-existent `.java` → `FILE_NOT_FOUND`.
- **2 `@Disabled` stubs** for full pipeline tests (require running Neo4j, PostgreSQL, Ollama).

---

## #26 — AutoQE Studio Frontend Integration & Real-Time API Connections
**Repository / Files:** `frontend/` *(cloned from https://github.com/DiStrix1/autoqe-studio-sparkle.git)*, `frontend/src/services/api.ts`, `frontend/src/routes/index.tsx`, `frontend/src/components/autoqe/*`, `backend/.../controller/TestGenerationController.java`, `parser-service/main.py`, `.gitignore`, `README.md`
**Why:** The AutoQE Studio single-page claymorphic interface needed to be integrated with both the Spring Boot orchestrator and the Python AST parser service.
**Fix:**
- **Cloned Frontend**: Integrated the TanStack Start + React 19 + Tailwind CSS frontend into `frontend/`.
- **API Normalization Layer**: Built `api.ts` to map FastAPI snake_case metrics (`classes_parsed`, `methods_parsed`, `graph_nodes_created`, `vectors_inserted`) and adapt Spring Boot's `TestGenerationResponse` (`generatedTestCode`, `executionLogs`, `attempts`, `status`).
- **Live System Health Polling**: Implemented status pill indicators in `TopNav.tsx` for `:7687`/`:5432` (DBs), `:8000` (Python), `:8080` (Spring Boot), and `:11434` (Ollama).
- **Dynamic AST Bubble Map & Real Code**: Connected `BubbleMap.tsx` to dynamically arrange method nodes around the selected class, and mapped `test-sandbox` classes (`UserService`, `BankAccount`, `MathUtils`, `PaymentProcessor`, `DataRepository`) in package `com.qe.demo`.
- **New Backend Endpoints**: Added `GET /api/v1/health` and `GET /api/v1/file-content` to `TestGenerationController.java`.
- **FastAPI Metadata & CORS**: Extended `POST /ingest` in `main.py` to return parsed class details and broadened allowed CORS origins for local frontend dev servers.

---

## Test Results
```
Frontend: npm run build — 0 errors (built in 2.01s)
Backend: .\mvnw.cmd test-compile — BUILD SUCCESS
Sandbox: .\mvnw.cmd test-compile — BUILD SUCCESS
```

---

# Change 27: Lovable Ingestion Pipeline & Directives Sync
- **Selective Sync**: Extracted newly created components (`IngestModal.tsx`, `GuidanceBanner.tsx`, `DirectivesPopover.tsx`) from remote branch without overriding local health checks, dynamic AST classes, or agent progress bars.
- **Ingestion Modal Integration**: Wired phased visual feedback (Tree-sitter AST -> Neo4j Graph -> Vector Embeddings -> pgvector Index) into the real `ingestRepository()` execution.
- **Post-Ingestion Guidance**: Added `GuidanceBanner` with celebratory callout and 1-click CTA to focus the workbench on the first ingested class.
- **Custom Directives & Strategy**: Added `DirectivesPopover` in `TopNav` supporting strategies (`Standard JUnit 5`, `Edge-Case Stress`, `Mock-Heavy Isolation`) and custom agent directives piped to Spring Boot test generation API.
# Change 28: UI Streamlining, Health Badges Removal & Live Terminal Execution Pipeline
- **Removed Top Navigation Health Badges**: Removed `DBs`, `Python`, `Spring`, `Ollama` health status pills and refresh button from `TopNav.tsx` for a clean, distraction-free top bar.
- **Removed Duplicate Workbench Tab**: Eliminated the redundant "Agent Squad Inspector" tab from `Workbench.tsx`, keeping `Code & Test Split View` and `Interactive Bubble Map`.
- **Dedicated Agent Squad Inspector Section**: Added the dedicated section header & subtitle (`Agent Squad Inspector — 4 Autonomous RAG & Self-Healing Agents`) directly above the 4 squad cards.
- **Live Reactive Terminal Streaming**: Connected live phased ingestion logs and test generation lifecycle outputs into `terminalLines` in `src/routes/index.tsx`.
- **Professional Offline Mode**: Added professional alert guidance banner without emojis and accurate demo counters (`9 Classes`, `33 Methods`, `41 Links`).
# Change 29: End-to-End Custom Agent Directives & Strategic Test Synthesis
- **Backend Spring Boot & LangChain4j**:
  - Extended `TestGenerationRequest` with `directives` and `strategy` parameters.
  - Updated `PlannerAgent` and `GeneratorAgent` `@UserMessage` prompt templates to inject user-specified testing directives and strategies directly into Ollama context.
  - Forwarded directives in `TestGenerationController` and `AsyncTestGenerationService`.
- **Frontend Directives Engine**:
  - Implemented 4 strategic presets in `DirectivesPopover.tsx` (`Edge-Case Stress`, `Mock Isolation`, `Parameterized Tests`, `Strict Contract Guards`).
  - Added quick-injection helper chips (`+ Verify Mock Invocations`, `+ Boundary & Zero Values`, `+ Negative Inputs`, etc.).
  - Added active pill indicator in top navigation (`Directives (Strategy)`) with pulsing status indicator.
  - Wired live terminal streaming of active directives during test generation.
- **Verification**: `.\mvnw.cmd test-compile` (BUILD SUCCESS) & `npm run build` (0 errors).


