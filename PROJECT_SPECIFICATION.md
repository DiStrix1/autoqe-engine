# SYSTEM SPECIFICATION: Multi-Agent Quality Engineering RAG with Hybrid Vector-Graph Systems

## 1. AGENT ROLE & GLOBAL DIRECTIVE

You are an expert Principal AI Systems Engineer and Enterprise Java/Python Architect operating in a multi-language repository designed to build an autonomous Quality Engineering (QE) platform.

Your task is to generate clean, highly modular, defensively programmed, and fully typed code that strictly adheres to the architecture, database schemas, and API contracts defined in this document.

### Global Guardrails & Non-Negotiable Rules

- **STRICT BOUNDARY SEPARATION:** NEVER mix Python and Java environments. Python scripts belong exclusively in `parser-service/`; Java Spring Boot code belongs exclusively in `backend/`.
- **NO HARDCODED CREDENTIALS:** Database credentials, host addresses, and ports MUST be read from `application.properties` (Java) or environment variables (Python).
- **DEFENSIVE ERROR HANDLING:** All external calls (database queries, network requests, AST parsing, terminal process executions) MUST be wrapped in explicit `try-catch` (Java) or `try-except` (Python) blocks with detailed logging.
- **TYPE SAFETY:** Python code MUST use type hints (`typing` module) and Pydantic models. Java code MUST use Java 17+ strong typing, immutability where applicable, and clean Spring dependency injection.
- **NO ASCII BOX DIAGRAMS:** Do not generate ASCII-art box diagrams or visual flowcharts inside code comments or Markdown outputs. Rely strictly on structured text, bulleted lists, and standard code blocks.

## 2. SYSTEM ARCHITECTURE & DATA FLOW CONTRACT

The system operates across four distinct sequential pipelines:

1. **Ingestion & AST Extraction (Python):** Ingests a local target Java codebase path, parses syntax using `tree-sitter-java`, extracts Abstract Syntax Tree (AST) nodes, generates dense vector embeddings, and populates the hybrid database.
2. **Hybrid Storage Layer:**
   - **Graph Store (Neo4j):** Stores physical structural code relationships (`:CLASS`, `:METHOD`, `:HAS_METHOD`, `:CALLS`).
   - **Vector Store (PostgreSQL + pgvector):** Stores 384-dimensional dense semantic embeddings of method bodies.
3. **Multi-Agent Orchestration (Java Spring Boot + LangChain4j):** Intercepts user testing requests, uses Model Context Protocol (MCP) tools to query Neo4j and pgvector, and synthesizes JUnit 5 test suites.
4. **Programmatic Test Execution & Self-Healing (Java):** Executes generated tests via `ProcessBuilder` (`mvn test`), intercepts failure stack traces, and re-prompts the AI agent to self-heal code up to a hard limit of 3 retry iterations.

## 3. DATABASE SCHEMAS & QUERY CONTRACTS

### 3.1 Neo4j Graph Database Schema

- **Connection Protocol:** Bolt on `bolt://localhost:7687`
- **Database Name:** `neo4j`

**Nodes & Properties:**
- `(:CLASS {id: String, name: String, fullName: String, filePath: String})`
- `(:METHOD {id: String, name: String, returnType: String, signature: String, body: String})`

**Relationships:**
- `(:CLASS)-[:HAS_METHOD]->(:METHOD)`
- `(:METHOD)-[:CALLS]->(:METHOD)`

**Required Cypher Mutation Queries:**

```cypher
// Create Class Node
MERGE (c:CLASS {fullName: $fullName})
SET c.name = $name, c.filePath = $filePath;

// Create Method Node and Link to Class
MERGE (m:METHOD {id: $methodId})
SET m.name = $name, m.returnType = $returnType, m.signature = $signature, m.body = $body
WITH m
MATCH (c:CLASS {fullName: $fullName})
MERGE (c)-[:HAS_METHOD]->(m);

// Create Method Call Relationship
MATCH (caller:METHOD {id: $callerId})
MATCH (callee:METHOD {id: $calleeId})
MERGE (caller)-[:CALLS]->(callee);
```

### 3.2 PostgreSQL + pgvector Schema

- **Connection:** `jdbc:postgresql://localhost:5432/code_vectors`
- **Extension Required:** `vector`

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS code_embeddings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    method_id VARCHAR(255) NOT NULL UNIQUE,
    class_name VARCHAR(255) NOT NULL,
    method_name VARCHAR(255) NOT NULL,
    file_path TEXT NOT NULL,
    method_body TEXT NOT NULL,
    embedding VECTOR(384) NOT NULL
);

CREATE INDEX IF NOT EXISTS code_embeddings_vector_idx
ON code_embeddings
USING hnsw (embedding vector_cosine_ops);
```

## 4. MICROSERVICE SPECIFICATIONS

### 4.1 Python Ingestion Service (`parser-service/`)

- **Framework:** FastAPI, running on Uvicorn (Port 8000).
- **Dependencies:** `fastapi`, `uvicorn`, `tree-sitter>=0.22.0`, `tree-sitter-java>=0.21.0`, `sentence-transformers`, `neo4j`, `psycopg2-binary`, `pgvector`, `pydantic`.
- **Embedding Model:** `all-MiniLM-L6-v2` (Output Dimension: 384).

**Target Endpoint Contract:**

`POST /ingest`

- **Payload:** `{"repo_path": "/absolute/path/to/java/project"}`
- **Response:** `{"status": "SUCCESS", "classes_parsed": int, "methods_parsed": int, "graph_nodes_created": int, "vectors_inserted": int}`

**Implementation Rules for AI Agent:**
1. Walk the target directory recursively and isolate all `.java` files (excluding `/target/` and `/test/` folders).
2. Use `tree-sitter-java` to traverse AST nodes: extract `class_declaration`, `method_declaration`, `method_invocation`.
3. Construct structural queries and populate Neo4j within a single transactional session.
4. Pass raw `method_body` strings to `SentenceTransformer('all-MiniLM-L6-v2')` to generate 384-dim floating-point arrays.
5. Batch insert vector records into PostgreSQL `code_embeddings`.

### 4.2 Java Backend Orchestrator (`backend/`)

- **Framework:** Spring Boot 3.x, Java 17+, Maven build tool.
- **Key Dependencies:** `spring-boot-starter-web`, `langchain4j`, `langchain4j-ollama`, `langchain4j-mcp`.
- **Local AI Endpoint:** Ollama host at `http://localhost:11434` (Model: `llama3:8b` or `phi3`).

**Component Architecture:**
- `McpToolConfig.java`: Registers Model Context Protocol tools allowing the LLM to trigger graph queries and vector searches dynamically.
- `PlannerAgent.java`: Analyzes incoming class test requests and queries Neo4j to build a dependency traversal map.
- `RetrievalAgent.java`: Queries `code_embeddings` using cosine similarity to fetch semantically relevant utility code.
- `GeneratorAgent.java`: Prompts LLM with extracted context and generates clean JUnit 5 test class code.
- `TestRunnerService.java`: Launches Maven process via `ProcessBuilder` to compile and run generated tests.

**Target Endpoint Contract:**

`POST /api/v1/generate-tests`

- **Request JSON:** `{"targetClassName": "UserService", "targetFilePath": "/path/to/UserService.java"}`

**Response JSON Structure:**

```json
{
  "targetClass": "UserService",
  "status": "PASSED",
  "attempts": 2,
  "compilationSuccessful": true,
  "generatedTestCode": "package com.example; ...",
  "executionLogs": "Tests run: 4, Failures: 0, Errors: 0"
}
```

## 5. SELF-HEALING EXECUTION ENGINE RULES

The AI Agent implementing `TestRunnerService.java` MUST follow this exact loop logic:

1. **Pre-Compile Check:** Execute `mvn test-compile` on the target project. If the target source code fails compilation, abort immediately and return an error response.
2. **Write File:** Write generated test code to disk inside the target project's `src/test/java/...` directory matching its package statement.
3. **Execute Command:** Run shell command via Java `ProcessBuilder`:
   `mvn test -Dtest=GeneratedTest`
4. **Evaluate Exit Code:**
   - If Exit Code == 0: Return `PASSED` payload to controller.
   - If Exit Code != 0: Extract complete stdout and stderr logs containing compilation errors or assertion failure stack traces.
5. **Trigger Repair Prompt:** Send prompt back to `GeneratorAgent`:
   - **System Role:** "You are an expert Java debugger."
   - **User Context:** Original Class Code, Failing Test Code, and Exact Maven Failure Stack Trace Log.
   - **Task:** "Fix the compilation or assertion error in the JUnit 5 test suite. Return ONLY valid Java code inside markdown code fences."
6. **Retry Loop:** Overwrite test file on disk and re-run execution. Repeat up to a **MAXIMUM OF 3 RETRIES**. If attempt 3 fails, return status `FAILED_AFTER_HEALING` with last error trace.

## 6. OPERATIONAL SAFEGUARDS & EDGE-CASE HANDLING

### 6.1 Tree-sitter Dependency Standard (Python)

- MUST use `tree-sitter>=0.22.0` and `tree-sitter-java>=0.21.0`.
- Do NOT use deprecated `Language.build_library()` or shared `.so` object compilation.
- AST parsing initialization MUST use the modern bindings:

```python
import tree_sitter_java as tsjava
from tree_sitter import Language, Parser

JAVA_LANGUAGE = Language(tsjava.language())
parser = Parser(JAVA_LANGUAGE)
```

### 6.2 Ollama Context Window Configuration (Java)

When initializing `OllamaChatModel` in LangChain4j, explicitly set the context window parameter (`numCtx`) to at least 8192 tokens to prevent stack trace truncation during self-healing prompts:

```properties
langchain4j.ollama.chat-model.custom-headers.num_ctx=8192
```

### 6.3 Maven Sandbox Isolation & Classpath Contract

- Target code passed to `TestRunnerService.java` MUST reside inside a valid Maven directory containing a `pom.xml` with `junit-jupiter-api` and `junit-jupiter-engine` dependencies.
- The AI Generator MUST read the target class's package statement and save the generated test inside the matching directory path (e.g., `package com.qe.demo;` -> `src/test/java/com/qe/demo/GeneratedTest.java`).

### 6.4 Database Initialization Order

Python and Java database setup scripts MUST execute table creation using `CREATE EXTENSION IF NOT EXISTS vector;` BEFORE attempting to create tables or HNSW indexes.

## 7. PROJECT DIRECTORY TREE CONTRACT

When generating file operations, ALWAYS maintain this explicit structure:

```
code-qe-agent/
├── docker-compose.yml
├── PROJECT_SPECIFICATION.md
├── .gitignore
├── parser-service/
│   ├── main.py
│   ├── requirements.txt
│   ├── test_db_connections.py
│   └── parser/
│       ├── __init__.py
│       ├── tree_sitter_parser.py
│       ├── graph_builder.py
│       └── vectorizer.py
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/qe/agent/
│       │   │   ├── AgentApplication.java
│       │   │   ├── config/
│       │   │   │   ├── McpToolConfig.java
│       │   │   │   └── OllamaConfig.java
│       │   │   ├── agents/
│       │   │   │   ├── PlannerAgent.java
│       │   │   │   ├── RetrievalAgent.java
│       │   │   │   └── GeneratorAgent.java
│       │   │   ├── runner/
│       │   │   │   └── TestRunnerService.java
│       │   │   └── controller/
│       │   │       └── TestGenerationController.java
│       │   └── resources/
│       │       └── application.properties
└── test-sandbox/
```

## 8. AI AGENT CHECKLIST BEFORE COMMIT

Before completing any coding task, the AI Agent must self-verify:

- [ ] Does Python code execute without missing imports or unhandled exceptions?
- [ ] Does Java code compile using standard Java 17 syntax and Spring Boot 3 conventions?
- [ ] Are vector dimensions consistently defined as 384 across Python generator and SQL tables?
- [ ] Are all database connections properly closed after execution?
- [ ] Is the self-healing loop capped at 3 attempts to prevent infinite runtime recursion?
- [ ] Has source code pre-compilation check been performed before attempting test generation?
