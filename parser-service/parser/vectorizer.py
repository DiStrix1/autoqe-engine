"""
vectorizer.py — Sentence embedding generation and pgvector insertion.

Uses SentenceTransformer('all-MiniLM-L6-v2') to generate 384-dimensional
dense embeddings for each parsed method body and inserts them into the
PostgreSQL code_embeddings table using psycopg2 + pgvector.

See PROJECT_SPECIFICATION.md Sections 3.2 and 4.1 for schema and embedding
model contracts.

Credentials and connection parameters are read exclusively from environment
variables; no credentials are hardcoded.
"""

from __future__ import annotations

import hashlib
import logging
import os
from typing import Dict, List, Optional

import numpy as np
import psycopg2  # type: ignore
from pgvector.psycopg2 import register_vector  # type: ignore
from sentence_transformers import SentenceTransformer  # type: ignore

from parser.tree_sitter_parser import ClassInfo, MethodInfo

# ---------------------------------------------------------------------------
# Logger
# ---------------------------------------------------------------------------
logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Embedding configuration
# ---------------------------------------------------------------------------
_MODEL_NAME: str = "all-MiniLM-L6-v2"
_EMBEDDING_DIM: int = 384

# Lazy-loaded singleton to avoid reloading the model on every call
_MODEL: Optional[SentenceTransformer] = None

# ---------------------------------------------------------------------------
# Connection parameters — read from environment (no hardcoded credentials)
# ---------------------------------------------------------------------------
PG_HOST: str = os.environ.get("PG_HOST", "localhost")
PG_PORT: int = int(os.environ.get("PG_PORT", "5432"))
PG_DB: str = os.environ.get("PG_DB", "code_vectors")
PG_USER: str = os.environ.get("PG_USER", "dev_user")
PG_PASSWORD: str = os.environ.get("PG_PASSWORD", "dev_password")

# ---------------------------------------------------------------------------
# SQL statements
# ---------------------------------------------------------------------------

_SQL_ENSURE_EXTENSIONS = """
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
"""

_SQL_CREATE_TABLE = """
CREATE TABLE IF NOT EXISTS code_embeddings (
    id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    method_id   VARCHAR(255) NOT NULL UNIQUE,
    class_name  VARCHAR(255) NOT NULL,
    method_name VARCHAR(255) NOT NULL,
    file_path   TEXT         NOT NULL,
    method_body TEXT         NOT NULL,
    embedding   VECTOR(384)  NOT NULL,
    file_hash   VARCHAR(64)  DEFAULT NULL
);
"""

_SQL_ADD_HASH_COLUMN = """
ALTER TABLE code_embeddings
    ADD COLUMN IF NOT EXISTS file_hash VARCHAR(64) DEFAULT NULL;
"""

_SQL_CREATE_INDEX = """
CREATE INDEX IF NOT EXISTS code_embeddings_vector_idx
ON code_embeddings
USING hnsw (embedding vector_cosine_ops);
"""

_SQL_GET_STORED_HASH = """
SELECT file_hash FROM code_embeddings WHERE method_id = %s LIMIT 1;
"""

_SQL_UPSERT_EMBEDDING = """
INSERT INTO code_embeddings
    (method_id, class_name, method_name, file_path, method_body, embedding, file_hash)
VALUES
    (%s, %s, %s, %s, %s, %s, %s)
ON CONFLICT (method_id) DO UPDATE
    SET class_name  = EXCLUDED.class_name,
        method_name = EXCLUDED.method_name,
        file_path   = EXCLUDED.file_path,
        method_body = EXCLUDED.method_body,
        embedding   = EXCLUDED.embedding,
        file_hash   = EXCLUDED.file_hash;
"""

# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _get_model() -> SentenceTransformer:
    """Return the shared SentenceTransformer model, loading it on first call."""
    global _MODEL  # pylint: disable=global-statement
    if _MODEL is None:
        logger.info("Loading embedding model '%s' …", _MODEL_NAME)
        _MODEL = SentenceTransformer(_MODEL_NAME)
        logger.info("Embedding model loaded (output dim: %d).", _EMBEDDING_DIM)
    return _MODEL


def _open_connection() -> psycopg2.extensions.connection:
    """
    Open and return a psycopg2 connection with the pgvector type adapter
    registered.  Raises on failure.
    """
    conn = psycopg2.connect(
        host=PG_HOST,
        port=PG_PORT,
        dbname=PG_DB,
        user=PG_USER,
        password=PG_PASSWORD,
    )
    conn.autocommit = False

    # Ensure pgvector extension exists before registering the adapter
    with conn.cursor() as cur:
        cur.execute(_SQL_ENSURE_EXTENSIONS)
    conn.commit()

    register_vector(conn)
    return conn


def _ensure_schema(conn: psycopg2.extensions.connection) -> None:
    """Create the code_embeddings table and HNSW index if they do not exist.
    Also ensures the file_hash column exists on older installs (Improvement #L).
    """
    with conn.cursor() as cur:
        cur.execute(_SQL_CREATE_TABLE)
        cur.execute(_SQL_ADD_HASH_COLUMN)  # idempotent: ADD COLUMN IF NOT EXISTS
        cur.execute(_SQL_CREATE_INDEX)
    conn.commit()
    logger.debug("code_embeddings schema verified / created.")


def _compute_file_hash(file_path: str) -> str:
    """Return the SHA-256 hex digest of the file at *file_path*.
    Returns an empty string if the file cannot be read."""
    try:
        with open(file_path, "rb") as fh:
            return hashlib.sha256(fh.read()).hexdigest()
    except OSError:
        return ""


def _collect_methods(classes: List[ClassInfo]) -> List[MethodInfo]:
    """Flatten all methods from all classes into a single ordered list."""
    methods: List[MethodInfo] = []
    for cls in classes:
        methods.extend(cls.methods)
    return methods


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


def vectorize_and_store(classes: List[ClassInfo]) -> int:
    """
    Generate 384-dimensional embeddings for every method body in *classes*
    and upsert the records into the PostgreSQL code_embeddings table.

    Uses SHA-256 file hashing (Improvement #L) to skip re-embedding methods
    whose source file has not changed since the last ingest run.

    Parameters
    ----------
    classes:
        List of ClassInfo objects produced by ``parse_repository()``.

    Returns
    -------
    int
        Number of embedding rows successfully inserted / updated
        (excludes skipped unchanged files).

    Raises
    ------
    Exception
        Re-raises database connection or embedding model failures so the
        FastAPI endpoint can return a 500 response.
    """
    conn: Optional[psycopg2.extensions.connection] = None
    vectors_inserted: int = 0
    vectors_skipped: int = 0

    try:
        methods = _collect_methods(classes)
        if not methods:
            logger.warning("No methods found; nothing to vectorize.")
            return 0

        logger.info("Vectorizing %d method bodies …", len(methods))

        # --- Connect early to check stored file hashes before embedding ---
        conn = _open_connection()
        _ensure_schema(conn)

        # Build a cache: file_path -> current SHA-256 hash
        file_hash_cache: Dict[str, str] = {}
        def _get_file_hash(fp: str) -> str:
            if fp not in file_hash_cache:
                file_hash_cache[fp] = _compute_file_hash(fp)
            return file_hash_cache[fp]

        # Identify which methods can be skipped (file unchanged)
        methods_to_embed: List[MethodInfo] = []
        for m in methods:
            current_hash = _get_file_hash(m.file_path)
            if current_hash:
                with conn.cursor() as cur:
                    cur.execute(_SQL_GET_STORED_HASH, (m.method_id,))
                    row = cur.fetchone()
                    stored_hash = row[0] if row else None
                if stored_hash == current_hash:
                    vectors_skipped += 1
                    logger.debug("Skipping unchanged method '%s' (hash match).", m.method_id)
                    continue
            methods_to_embed.append(m)

        if not methods_to_embed:
            logger.info("All %d methods unchanged — nothing to re-embed.", vectors_skipped)
            return 0

        logger.info(
            "Embedding %d methods (%d skipped as unchanged).",
            len(methods_to_embed), vectors_skipped,
        )

        # --- Generate embeddings in batch (more efficient than one-by-one) ---
        model = _get_model()
        bodies: List[str] = [m.body if m.body else m.signature for m in methods_to_embed]

        try:
            raw_embeddings = model.encode(
                bodies,
                batch_size=64,
                show_progress_bar=False,
                convert_to_numpy=True,
                normalize_embeddings=True,
            )
        except Exception as exc:  # pylint: disable=broad-except
            logger.error("Embedding model encode FAILED: %s", exc, exc_info=True)
            raise

        # Validate output dimension
        if raw_embeddings.shape[1] != _EMBEDDING_DIM:
            raise ValueError(
                f"Model returned {raw_embeddings.shape[1]}-dim embeddings; "
                f"expected {_EMBEDDING_DIM}."
            )
        logger.info("Embeddings generated — shape: %s", raw_embeddings.shape)

        with conn.cursor() as cur:
            for idx, method_info in enumerate(methods_to_embed):
                try:
                    embedding_vec: np.ndarray = raw_embeddings[idx].astype(np.float32)
                    current_hash = _get_file_hash(method_info.file_path)
                    cur.execute(
                        _SQL_UPSERT_EMBEDDING,
                        (
                            method_info.method_id,
                            method_info.class_full_name,
                            method_info.name,
                            method_info.file_path,
                            method_info.body,
                            embedding_vec,
                            current_hash or None,
                        ),
                    )
                    vectors_inserted += 1
                except Exception as exc:  # pylint: disable=broad-except
                    logger.error(
                        "Failed to insert embedding for method '%s': %s",
                        method_info.method_id, exc, exc_info=True,
                    )
                    conn.rollback()
                    _ensure_schema(conn)
                    continue

        conn.commit()
        logger.info(
            "Vectorization complete — %d inserted/updated, %d skipped (unchanged).",
            vectors_inserted, vectors_skipped,
        )
        return vectors_inserted

    except Exception as exc:  # pylint: disable=broad-except
        logger.error("vectorize_and_store FAILED: %s", exc, exc_info=True)
        if conn is not None:
            try:
                conn.rollback()
            except Exception:  # pylint: disable=broad-except
                pass
        raise
    finally:
        if conn is not None:
            conn.close()
            logger.debug("PostgreSQL connection closed.")
