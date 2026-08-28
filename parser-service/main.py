"""
main.py — FastAPI entry point for the QE-RAG Parser Service.

Exposes:
  GET  /health            — liveness probe
  GET  /status            — DB row-count snapshot
  POST /ingest            — full parse → graph → vector pipeline

See PROJECT_SPECIFICATION.md Section 4.1 for the endpoint contract.
"""

from __future__ import annotations

import logging
import os
import sys
import time
from typing import Dict, List, Optional

import uvicorn
from fastapi import FastAPI, HTTPException, status
from pydantic import BaseModel, Field

# ---------------------------------------------------------------------------
# Logging — configure before importing sub-modules so their loggers inherit
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
    stream=sys.stdout,
)
logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Sub-module imports (after logging is configured)
# ---------------------------------------------------------------------------
from parser.tree_sitter_parser import parse_repository, ParseResult  # noqa: E402
from parser.graph_builder import build_graph, create_driver  # noqa: E402
from parser.vectorizer import vectorize_and_store, _open_connection  # noqa: E402

import psycopg2  # type: ignore # noqa: E402
from fastapi.middleware.cors import CORSMiddleware

# ---------------------------------------------------------------------------
# CORS configuration
# Read allowed origins from env var; defaults to local dev origins only.
# NEVER use allow_origins=["*"] with allow_credentials=True — browsers reject it.
# ---------------------------------------------------------------------------
_raw_origins: str = os.environ.get(
    "PARSER_ALLOWED_ORIGINS",
    "http://localhost:3000,http://localhost:5173,http://localhost:4173,http://localhost:8080,http://127.0.0.1:3000,http://127.0.0.1:5173,http://127.0.0.1:8080",
)
ALLOWED_ORIGINS: List[str] = [o.strip() for o in _raw_origins.split(",") if o.strip()]

# ---------------------------------------------------------------------------
# FastAPI application
# ---------------------------------------------------------------------------
app = FastAPI(
    title="QE-RAG Parser Service",
    description=(
        "Ingests a local Java codebase, extracts AST nodes via tree-sitter, "
        "populates Neo4j with structural graph data, and stores 384-dimensional "
        "semantic embeddings in PostgreSQL/pgvector."
    ),
    version="1.0.0",
)

# Enable CORS — origins are controlled by PARSER_ALLOWED_ORIGINS env var.
# allow_credentials is intentionally omitted (defaults False) to stay browser-compatible.
app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ---------------------------------------------------------------------------
# Pydantic models
# ---------------------------------------------------------------------------


class IngestRequest(BaseModel):
    """Payload for POST /ingest."""

    repo_path: str = Field(
        ...,
        description="Absolute path to the root of the Java project to ingest.",
        examples=["/path/to/java/project"],
    )


class ParsedMethod(BaseModel):
    name: str
    return_type: str
    signature: str
    method_id: str


class ParsedClass(BaseModel):
    name: str
    full_name: str
    file_path: str
    methods: List[ParsedMethod] = []


class IngestResponse(BaseModel):
    """Success response from POST /ingest."""

    status: str
    classes_parsed: int
    methods_parsed: int
    graph_nodes_created: int
    vectors_inserted: int
    vectors_skipped: int
    elapsed_seconds: float
    classes: Optional[List[ParsedClass]] = None


class StatusResponse(BaseModel):
    """Response from GET /status — DB row-count snapshot."""

    status: str
    pg_method_count: Optional[int]
    neo4j_class_count: Optional[int]
    neo4j_method_count: Optional[int]
    errors: List[str]


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@app.get("/health", tags=["Operations"])
def health_check() -> Dict[str, str]:
    """Liveness probe — returns HTTP 200 when the service is running."""
    return {"status": "ok"}


@app.get(
    "/status",
    response_model=StatusResponse,
    tags=["Operations"],
    summary="DB snapshot",
    description=(
        "Queries both PostgreSQL and Neo4j for live row/node counts. "
        "Returns partial results if one database is unreachable."
    ),
)
def get_db_status() -> StatusResponse:
    """
    Returns a live snapshot of stored data across both databases.
    Partial results are returned if one DB is unavailable.
    """
    pg_count: Optional[int] = None
    neo4j_classes: Optional[int] = None
    neo4j_methods: Optional[int] = None
    errors: List[str] = []

    # --- PostgreSQL ---
    try:
        conn = _open_connection()
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM code_embeddings")
            row = cur.fetchone()
            pg_count = row[0] if row else 0
        conn.close()
    except Exception as exc:  # pylint: disable=broad-except
        errors.append(f"PostgreSQL: {exc}")
        logger.warning("Status check — PostgreSQL unavailable: %s", exc)

    # --- Neo4j ---
    driver = None
    try:
        driver = create_driver()
        with driver.session(database="neo4j") as session:
            neo4j_classes = session.run(
                "MATCH (c:CLASS) RETURN count(c) AS n"
            ).single()["n"]
            neo4j_methods = session.run(
                "MATCH (m:METHOD) RETURN count(m) AS n"
            ).single()["n"]
    except Exception as exc:  # pylint: disable=broad-except
        errors.append(f"Neo4j: {exc}")
        logger.warning("Status check — Neo4j unavailable: %s", exc)
    finally:
        if driver is not None:
            driver.close()

    overall = "ok" if not errors else ("degraded" if (pg_count is not None or neo4j_classes is not None) else "error")
    return StatusResponse(
        status=overall,
        pg_method_count=pg_count,
        neo4j_class_count=neo4j_classes,
        neo4j_method_count=neo4j_methods,
        errors=errors,
    )


@app.post(
    "/ingest",
    response_model=IngestResponse,
    status_code=status.HTTP_200_OK,
    tags=["Ingestion"],
    summary="Ingest a Java repository",
    description=(
        "Runs the full ingestion pipeline: parse → build graph → "
        "generate embeddings → store. Returns execution metrics."
    ),
)
def ingest(request: IngestRequest) -> IngestResponse:
    """
    Full ingestion pipeline for a target Java codebase.

    Steps
    -----
    1. Validate that *repo_path* points to an existing directory.
    2. Parse all .java files recursively (excluding /target/ and /test/).
    3. Populate Neo4j with :CLASS / :METHOD nodes and relationships.
    4. Generate 384-dim sentence embeddings and upsert into PostgreSQL.
    5. Return aggregate execution metrics.
    """
    repo_path: str = request.repo_path
    logger.info("POST /ingest — repo_path: '%s'", repo_path)

    # --- Step 1: Validate path ---
    import os as _os
    if not _os.path.isdir(repo_path):
        logger.error("repo_path does not exist or is not a directory: '%s'", repo_path)
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"repo_path is not a valid directory: {repo_path}",
        )

    t_start: float = time.perf_counter()

    # --- Step 2: Parse repository ---
    logger.info("Step 1/3 — Parsing Java files …")
    try:
        parse_result: ParseResult = parse_repository(repo_path)
    except Exception as exc:  # pylint: disable=broad-except
        logger.error("parse_repository FAILED: %s", exc, exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"AST parsing failed: {exc}",
        ) from exc

    classes_parsed: int = len(parse_result.classes)
    methods_parsed: int = sum(len(c.methods) for c in parse_result.classes)
    logger.info(
        "Parsing done — classes: %d, methods: %d, raw call edges: %d",
        classes_parsed, methods_parsed, len(parse_result.calls),
    )

    # --- Step 3: Build Neo4j graph ---
    logger.info("Step 2/3 — Building Neo4j graph …")
    graph_nodes_created: int = 0
    try:
        graph_nodes_created, _edges = build_graph(parse_result)
    except Exception as exc:  # pylint: disable=broad-except
        logger.error("build_graph FAILED: %s", exc, exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Graph build failed: {exc}",
        ) from exc

    logger.info("Graph build done — nodes written: %d", graph_nodes_created)

    # --- Step 4: Generate embeddings and store in PostgreSQL ---
    logger.info("Step 3/3 — Vectorizing and storing embeddings …")
    vectors_inserted: int = 0
    try:
        vectors_inserted = vectorize_and_store(parse_result.classes)
    except Exception as exc:  # pylint: disable=broad-except
        logger.error("vectorize_and_store FAILED: %s", exc, exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Vectorization failed: {exc}",
        ) from exc

    logger.info("Vectorization done — vectors inserted: %d", vectors_inserted)

    elapsed: float = round(time.perf_counter() - t_start, 3)
    logger.info(
        "POST /ingest complete — elapsed: %.3fs, status: SUCCESS", elapsed
    )

    vectors_skipped: int = methods_parsed - vectors_inserted if methods_parsed >= vectors_inserted else 0

    classes_list = [
        ParsedClass(
            name=c.name,
            full_name=c.full_name,
            file_path=c.file_path,
            methods=[
                ParsedMethod(
                    name=m.name,
                    return_type=m.return_type,
                    signature=m.signature,
                    method_id=m.method_id,
                )
                for m in c.methods
            ],
        )
        for c in parse_result.classes
    ]

    return IngestResponse(
        status="SUCCESS",
        classes_parsed=classes_parsed,
        methods_parsed=methods_parsed,
        graph_nodes_created=graph_nodes_created,
        vectors_inserted=vectors_inserted,
        vectors_skipped=vectors_skipped,
        elapsed_seconds=elapsed,
        classes=classes_list,
    )


# ---------------------------------------------------------------------------
# Dev server entry point
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=int(os.environ.get("PORT", "8000")),
        reload=False,
        log_level="info",
    )
