# Nokia Interview Prep -- QE-RAG System

## 60-90 second spoken summary

"This project is a local AI-assisted quality-engineering system that generates and validates JUnit 5 tests for a Java Maven project. I built it because asking an LLM to write tests from a single source file often produces tests that compile poorly or miss surrounding dependencies. Instead, the system first ingests a Java repository using Tree-sitter, extracts classes, methods, and best-effort call relationships, and stores two complementary views of the code: a Neo4j graph for structure and PostgreSQL with pgvector for semantic similarity.

When a user selects a class, a Spring Boot orchestrator runs a staged workflow. A planning agent identifies test cases and mocks, a retrieval agent gathers structural and semantically related code, and a generator agent produces a complete JUnit 5 test class using a local Ollama model. The important final step is validation: the backend writes the test to the correct Maven test directory, runs Maven, and if it fails, feeds the actual build output back to the model for up to two repairs after the first attempt. So the project does not treat generated text as success; it treats a passing build as success. I also added SSE streaming, JaCoCo coverage reporting, an async API, bounded concurrency, Prometheus/Grafana metrics, and a React workbench so it is usable as a small end-to-end system rather than just a prompt demo."

If asked for the shortest possible version: "It is a hybrid-RAG, local-LLM test-generation pipeline whose output is verified by Maven and repaired from real compiler/test feedback."

## Architecture you can explain in one breath

`Java repo -> Tree-sitter parser -> Neo4j structure + pgvector semantic index -> Spring Boot planner/retriever/generator -> generated JUnit test -> Maven execution -> bounded LLM repair loop`

The frontend is a React/TanStack workbench; the parser is FastAPI/Python; orchestration and execution are Spring Boot/Java 17; Neo4j and PostgreSQL/pgvector run in Docker; Ollama runs the LLM locally. Prometheus and Grafana provide observability.

## Four technically interesting decisions

### 1. Hybrid retrieval: graph for code structure, vectors for semantic neighbours

**What the code does:** The ingestion service parses Java files with Tree-sitter. It writes `CLASS` and `METHOD` nodes plus `HAS_METHOD` and `CALLS` edges to Neo4j. It also embeds method bodies into 384-dimensional vectors and stores them in pgvector with an HNSW cosine-similarity index. The retrieval agent can call both `GraphQueryTool` and `VectorQueryTool`.

**Why this design:** A vector search can find code that *sounds* related, such as other validation methods, but cannot reliably answer "what does this specific method call?" A graph gives explicit repository structure and dependencies, but does not find analogous utilities whose names differ. Combining them gives the generator both precise local context and broader examples.

**What it achieves:** Better grounding than sending only one file to the LLM. It can identify likely mocks/dependencies from the graph and retrieve related implementation patterns from vector search.

**Good line to say:** "I used graph retrieval for exact structural questions and vector retrieval for fuzzy semantic questions; they solve different failure modes."

### 2. Execution is the acceptance criterion, with a bounded repair loop

**What the code does:** `TestRunnerService` finds the nearest Maven root from the requested source file, first runs `mvn test-compile`, writes the generated test into `src/test/java` according to its package declaration, and runs only the generated test. On failure it sends the original class, failed test, and complete Maven output to the repair prompt. It allows at most three total attempts and deletes the failed generated file on normal retry exhaustion.

**Why this design:** LLMs are good at producing candidate code, not at guaranteeing compilation or behavioural correctness. Maven gives an objective compiler and test oracle. The retry cap prevents an expensive or infinite agent loop.

**What it achieves:** A concrete definition of success -- generated tests compile and pass, rather than merely looking plausible. The runner drains Maven output on a separate thread to avoid the classic parent/child process deadlock caused by a full stdout buffer.

**Good line to say:** "The LLM is in the proposal loop, while the compiler and tests remain the judge."

### 3. Incremental, idempotent ingestion instead of rebuilding everything each time

**What the code does:** Neo4j writes use `MERGE` and pgvector uses `INSERT ... ON CONFLICT ... DO UPDATE`, so re-ingestion does not blindly duplicate records. The vectorizer computes a SHA-256 hash per source file and skips re-embedding methods from unchanged files. Embeddings are generated in batches of 64, and the SentenceTransformer model is lazy-loaded once per process.

**Why this design:** Embedding is the most expensive part of the ingestion path. During development, repositories are ingested repeatedly but most files usually have not changed.

**What it achieves:** Lower repeated-ingestion cost and stable records across runs, while preserving an update path for changed source files.

**Good line to say:** "I treated ingestion as a repeatable synchronization operation, not a one-shot import."

### 4. Long-running work is separated from the HTTP request lifecycle

**What the code does:** Besides the synchronous endpoint, the backend has an async submission endpoint and status-polling endpoint. Jobs are placed in a Spring `@Async` thread pool with core size 2, maximum 4, and queue capacity 10. The result is kept in a TTL-evicted in-memory `ConcurrentHashMap`. A separate `GET /api/v1/generate-tests/stream` SSE endpoint pushes pipeline stage events in real time to the React terminal. Micrometer counters record generated, passed, failed, and retry totals. Prometheus scrapes these and Grafana visualises them.

**Why this design:** Local LLM calls plus up to three Maven executions can take minutes, which makes a normal request prone to client/proxy timeouts. Unbounded concurrency would also overwhelm a local Ollama instance.

**What it achieves:** A responsive API, deliberately limited resource use, observable pipeline outcomes, and a simple development-friendly job model.

**Good line to say:** "I decoupled request acceptance from execution and intentionally constrained concurrency because the bottleneck is the local model, not HTTP throughput."

## Skeptical "why X instead of Y?" questions -- strong answers

### Why Neo4j *and* pgvector instead of only one database?

Neo4j captures explicit code relationships such as class membership and calls. pgvector retrieves semantically similar methods even when names and packages differ. A single store could simplify deployment, but I chose the hybrid design because test generation needs both exact dependency context and analogous examples. For a small project I would benchmark whether the extra operational complexity is justified; here it was a deliberate learning and architecture trade-off.

### Why Tree-sitter instead of regex or a full Java compiler API?

Regex breaks quickly on nested syntax, annotations, generics, comments, and formatting. Tree-sitter gives fast, tolerant AST parsing without needing the whole Maven project to compile at ingestion time. A compiler API such as JavaParser or the JDK compiler can provide richer type resolution, and that would be my next step for production-grade call resolution.

### Why a local Ollama model instead of a cloud LLM?

The local model keeps source code on the developer machine, removes per-request API cost, and makes the demo usable without cloud credentials. The trade-off is lower model quality and slower inference. I designed the Maven validation and repair loop partly to compensate for that uncertainty.

### Why separate planner, retriever, and generator agents instead of one prompt?

The separation gives each stage a narrow responsibility: planning identifies coverage and mocking concerns, retrieval grounds the answer in repository evidence, and generation writes code. It also makes failures diagnosable and permits stage-level fallbacks. In this implementation they are sequential LangChain4j services backed by the same configured Ollama model, not independently trained agents; "multi-agent" means role-separated orchestration.

### Why use an HNSW index and cosine similarity?

HNSW gives approximate nearest-neighbour search that scales far better than scanning every embedding, with a practical speed/recall trade-off. The embedding vectors are normalized, so cosine similarity is a natural measure of directional semantic similarity. Exact search would be simpler for a tiny corpus, but the index is meant for larger repositories.

### Why execute generated code at all? Isn't that dangerous?

It is potentially dangerous, so the current runner is suitable for a controlled local development sandbox only. The immediate safety measures are argument-list `ProcessBuilder` commands rather than shell interpolation, Maven-root discovery, file-type validation, and a retry cap. A production design must run builds in a disposable container or VM with an allowlisted repository root, network disabled, CPU/memory/time limits, and a non-privileged user.

### Why an in-memory async job store instead of Redis or a database?

It keeps the prototype small and removes infrastructure from the critical path. The code explicitly uses a bounded, two-hour TTL store. The trade-off is that jobs disappear on restart and cannot be shared across instances. For a deployed system I would use Redis, a database-backed job table, or a real queue such as RabbitMQ/Kafka depending on throughput and durability requirements.

### Why not let the repair loop run until it succeeds?

Because an unbounded LLM loop has unpredictable cost and can repeatedly make the same mistake. Three total attempts gives a useful chance to repair simple import, signature, or assertion mistakes while preserving predictable latency and resource consumption. Persistent failures are returned with the Maven logs so a developer can intervene.

### Why run `test-compile` before writing the generated test?

It separates a pre-existing source/project failure from a failure caused by generated code. Otherwise the system might ask the LLM to "fix" a test when the underlying project is already broken.

### Why use a static import sanitizer after prompting the model correctly?

Prompt constraints reduce errors but do not guarantee syntactically complete output. The small deterministic sanitizer acts as a cheap guardrail for common missing static JUnit/Mockito imports. It is intentionally narrow; it is not a substitute for compilation or a full Java formatter/parser.

## Weak spots: answer candidly, then give the next improvement

### The `CALLS` graph is best-effort, not compiler-accurate

The parser stores a callee's simple name and graph construction matches method IDs by name suffix. This can connect the wrong method when two classes share a method name or methods are overloaded. Say: "I chose a lightweight AST-level approximation to keep ingestion independent of compilation. I would replace it with symbol/type resolution using JavaParser plus symbol solver, JDT, or compiler APIs before claiming precise dependency analysis."

### The two embedding implementations may not be perfectly interoperable

Python ingests with `sentence-transformers/all-MiniLM-L6-v2`; Java queries with LangChain4j's quantized ONNX MiniLM implementation. They have the same advertised 384 dimensions, which avoids a schema error, but matching dimension alone does not prove their vector spaces are identical. Say: "The dimensions are validated, but I would validate retrieval quality empirically and, for production, use one exact model artifact/runtime for both indexing and querying, or expose embedding behind one service."

### Path validation does not confine work to an allowed sandbox root

The code normalizes a path and checks for `.java`; the normalised path is also checked for `..` sequences, but it does not assert that the resolved path starts within a configured allowed root. Any readable absolute Java file can still be read. Say: "The current validation protects against malformed input but is not a complete authorization boundary. The correct fix is `resolvedPath.startsWith(configuredAllowedRoot)` plus real-path/symlink checks, then containerize execution."

### Maven execution has no hard timeout or full isolation

`ProcessBuilder.waitFor()` can wait indefinitely; generated tests can run arbitrary test code. Say: "The output drain prevents I/O deadlock, but it does not solve runaway execution. I would use `waitFor(timeout)`, forcibly terminate on expiry, and run in a resource-limited disposable container."

### "MCP" is not actually implemented

`McpToolConfig` is explicitly a placeholder. The tools are registered directly through LangChain4j `AiServices.builder().tools(...)`; setting `qe.mcp.enabled=true` only logs that activation is pending. Say: "The project has an MCP-ready extension point and dependency, but the current implementation uses static in-process tool registration. I would not claim a working MCP server/client integration."

### Coverage is per-run only, not persisted historically

`JaCoCoReportParser` extracts instruction coverage from the Maven-generated `jacoco.xml` and includes it in the response, but there is no `test_runs` table storing coverage over time. Say: "Coverage is genuinely measured per run. The next step is persisting each run so I can show trends -- pass rate and coverage improvement across self-healing attempts."

## Resume-claim accuracy check

- Safe to claim: "Built a hybrid graph + vector RAG prototype for Java test generation."
- Safe to claim: "Used Tree-sitter, Neo4j, PostgreSQL/pgvector, Spring Boot, FastAPI, React, Docker, and local Ollama."
- Safe to claim: "Implemented Maven-based validation and a bounded self-healing repair loop."
- Safe to claim: "Added async job submission/polling, bounded concurrency, and Micrometer counters."
- Safe to claim: "Wired real-time SSE streaming from the Spring Boot pipeline to the React terminal."
- Safe to claim: "Integrated JaCoCo XML parsing to surface per-run instruction coverage."
- Safe to claim: "Added Prometheus and Grafana observability stack via Docker Compose."
- Avoid saying "MCP-powered": tools use static LangChain4j in-process registration; MCP is a documented future hook.
- Avoid saying "accurate call graph" or "full dependency analysis": call edges are name-based best-effort AST approximations.
- Avoid saying "production secure" or "sandboxed execution": Maven runs on the host; paths are not confined to a configured sandbox root.
- Avoid saying "fully tested end-to-end": 2 integration tests require external services and are @Disabled.
- Be precise about test counts: sandbox has 88 passing tests; these are existing/manual tests, not AI-generated output from a measured run.

## Evidence (current state)

| Module   | Command              | Result                                       |
|----------|----------------------|----------------------------------------------|
| Backend  | `mvnw.cmd test`      | 22 tests, 0 failures, 2 skipped (external)  |
| Sandbox  | `mvnw.cmd test`      | 88 tests, 0 failures                         |
| Frontend | `npm run build`      | 0 errors                                     |

## Final interview posture

Lead with the validated engineering decisions, not buzzwords. Describe this as a well-structured local prototype with real verification (Maven oracle, JaCoCo, SSE streaming, Prometheus/Grafana). When a limitation is raised, acknowledge it directly, explain the trade-off, and name the concrete production fix. That reads as engineering judgment, not defensiveness.
