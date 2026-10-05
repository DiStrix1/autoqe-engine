# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

**Primary:** Both the QA engineer and the Java developer who owns the code under test — often the same person on a small team. They are technical professionals working locally during an active development or QA session. They are comfortable with terminals, Maven, and JUnit, but want to skip the cognitive overhead of hand-writing tests from scratch.

## Product Purpose

AutoQE Studio is a local-only web interface for an autonomous Quality Engineering pipeline. It lets a developer or QA engineer point the system at a Java codebase, trigger AST ingestion into a hybrid Neo4j + pgvector store, and then request AI-generated JUnit 5 test suites — complete with a self-healing retry loop (up to 3 attempts) that corrects compilation and assertion failures without user intervention.

Success means: the user selects a class, clicks one button, and receives runnable, passing JUnit 5 tests in seconds — without writing a single line of test code.

## Positioning

AutoQE Studio is the only test-generation interface that combines structural code graph traversal (Neo4j method call chains) with semantic vector search (pgvector cosine similarity on method bodies) to give the LLM context-rich prompts — then runs, compiles, and self-heals the output automatically before surfacing it to the user. A test-generation tool that just wraps an LLM with no structural understanding cannot replicate this mechanism.

## Operating Context

- Runs entirely on a developer''s local machine (never deployed externally or hosted publicly).
- Infrastructure started via `docker compose up -d` (Neo4j, pgvector, parser-service, Prometheus, Grafana).
- Local LLM served by Ollama (`llama3:8b` or `phi3`) at `http://localhost:11434`.
- Python FastAPI parser service on port 8000; Spring Boot backend on port 8080; frontend on port 3000 (or 5173).
- Target codebase: a valid Maven project with `pom.xml` and JUnit Jupiter dependencies.
- The typical session: start infra → ingest codebase → select a class → generate tests → review the result.
- Users work in a single browser tab, typically alongside an IDE.

## Capabilities and Constraints

- **Ingestion:** POST `/ingest` to the Python parser; walks `.java` files, extracts AST nodes via `tree-sitter-java`, populates Neo4j and pgvector. Incremental re-ingestion uses SHA-256 batch hash checks to skip unchanged files.
- **Test generation modes:** single class (`POST /api/v1/generate-tests`), batch, and async (SSE + WebSocket for streaming progress).
- **Self-healing engine:** up to 3 retry iterations; on failure, the full Maven stdout/stderr is fed back to the LLM for correction.
- **Observability:** Prometheus (port 9090) + Grafana (port 3001) for pipeline metrics; structured JSON logging with MDC correlation.
- **Security:** API key authentication, Bucket4j rate limiting, path traversal guards on the Spring backend.
- **Target classes in test-sandbox:** `UserService`, `BankAccount`, `MathUtils`, `PaymentProcessor`, `DataRepository` (8 source classes total, 33 methods).
- **Embedding model:** `all-MiniLM-L6-v2`, 384-dimensional output — fixed; must not change without a full schema migration.
- **No external network dependency at runtime** — all inference is local via Ollama.
- **Open decision:** whether a future version supports non-Java languages (not a current priority).

## Brand Commitments

- Product name: **AutoQE Studio** — committed. All other visual choices are open; this is a fresh rebuild with no prior design system to preserve.

## Evidence on Hand

- Full working codebase at `d:/Projects/qe-rag-system/` with all five layers implemented.
- `PROJECT_SPECIFICATION.md` — architectural design document with schema contracts, API contracts, and guardrail rules.
- `README.md` — developer guide with quick-start steps and feature inventory.
- `CHANGES.md` — comprehensive changelog.
- `DEPLOYMENT.md` — production deployment and SSL hardening notes.
- Test-sandbox at `test-sandbox/` — 8 live Java classes used as the generation target.
- No marketing copy, testimonials, pricing, or external customers exist; future UI work must not fabricate them.

## Product Principles

1. **Invisible infrastructure, visible results.** Users should never need to think about Neo4j, pgvector, or Ollama — only about the class they want tested and the tests that come back.
2. **Confidence through transparency.** The self-healing loop and agent progress must be visible in real time so the user always knows what the system is doing and why.
3. **Zero fabrication.** Generated tests must compile and pass before they surface; a failing result should show the exact Maven trace, not hide it.
4. **Local-first, no lock-in.** The entire stack runs on commodity hardware with no cloud dependencies; the product must feel as reliable and fast as a local CLI tool.
5. **Precision over coverage theater.** A small suite of semantically meaningful, structurally aware tests that actually run is worth more than a large number of shallow scaffolded assertions.

## Accessibility & Inclusion

No specific standard mandated. The primary environment is a modern Chromium browser on a developer workstation; WCAG 2.1 AA is a reasonable baseline for focus and color contrast given the technical audience.
