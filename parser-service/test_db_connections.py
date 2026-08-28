"""
Module B - Database Connection Verification Script

Verifies Python connectivity to both Neo4j and PostgreSQL (pgvector) Docker
containers running as part of the qe-rag-system infrastructure.

Credentials and connection parameters are read from environment variables with
safe fallbacks matching the values declared in PROJECT_SPECIFICATION.md / docker-compose.yml.

Usage:
    python test_db_connections.py
"""

import os
import sys
import logging

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    stream=sys.stdout,
)
logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Connection parameters  (read from env; fall back to docker-compose defaults)
# ---------------------------------------------------------------------------
NEO4J_URI: str = os.environ.get("NEO4J_URI", "bolt://localhost:7687")
NEO4J_USER: str = os.environ.get("NEO4J_USER", "neo4j")
NEO4J_PASSWORD: str = os.environ.get("NEO4J_PASSWORD", "dev_password")

PG_HOST: str = os.environ.get("PG_HOST", "localhost")
PG_PORT: int = int(os.environ.get("PG_PORT", "5432"))
PG_DB: str = os.environ.get("PG_DB", "code_vectors")
PG_USER: str = os.environ.get("PG_USER", "dev_user")
PG_PASSWORD: str = os.environ.get("PG_PASSWORD", "dev_password")


# ---------------------------------------------------------------------------
# Neo4j sanity-check
# ---------------------------------------------------------------------------
def test_neo4j_connection() -> bool:
    """
    Connect to Neo4j, create a temporary :SanityCheck node, verify it can be
    queried, then delete it.

    Returns True on success, False on any error.
    """
    logger.info("--- Neo4j Connection Test ---")
    driver = None
    try:
        from neo4j import GraphDatabase  # type: ignore

        driver = GraphDatabase.driver(
            NEO4J_URI,
            auth=(NEO4J_USER, NEO4J_PASSWORD),
        )

        # Verify the driver can reach the server
        driver.verify_connectivity()
        logger.info("Neo4j driver connected successfully to %s", NEO4J_URI)

        with driver.session(database="neo4j") as session:
            # --- Create temporary node ---
            session.run(
                "CREATE (n:SanityCheck {status: 'OK', source: 'test_db_connections.py'})"
            )
            logger.info("SanityCheck node created.")

            # --- Query the node back ---
            result = session.run(
                "MATCH (n:SanityCheck {status: 'OK'}) RETURN n.status AS status LIMIT 1"
            )
            record = result.single()
            if record is None:
                raise AssertionError("SanityCheck node was not found after creation.")

            status_value: str = record["status"]
            logger.info("Queried SanityCheck node — status: '%s'", status_value)
            assert status_value == "OK", f"Unexpected status value: {status_value}"

            # --- Clean up ---
            session.run("MATCH (n:SanityCheck) DETACH DELETE n")
            logger.info("SanityCheck node deleted (cleanup complete).")

        logger.info("[PASS] Neo4j test PASSED.\n")
        return True

    except Exception as exc:  # pylint: disable=broad-except
        logger.error("[FAIL] Neo4j test FAILED: %s", exc, exc_info=True)
        return False
    finally:
        if driver is not None:
            driver.close()
            logger.debug("Neo4j driver closed.")


# ---------------------------------------------------------------------------
# PostgreSQL + pgvector sanity-check
# ---------------------------------------------------------------------------
def test_postgres_connection() -> bool:
    """
    Connect to PostgreSQL, ensure the pgvector extension is loaded, insert a
    dummy 384-dimensional vector into a temporary table, query it back, and
    drop the table.

    Returns True on success, False on any error.
    """
    logger.info("--- PostgreSQL + pgvector Connection Test ---")
    conn = None
    try:
        import psycopg2  # type: ignore
        from pgvector.psycopg2 import register_vector  # type: ignore

        conn = psycopg2.connect(
            host=PG_HOST,
            port=PG_PORT,
            dbname=PG_DB,
            user=PG_USER,
            password=PG_PASSWORD,
        )
        conn.autocommit = False
        logger.info(
            "PostgreSQL connected successfully to %s:%s/%s", PG_HOST, PG_PORT, PG_DB
        )

        with conn.cursor() as cur:
            # --- Ensure extension exists FIRST (vector type must exist before register_vector) ---
            cur.execute("CREATE EXTENSION IF NOT EXISTS vector;")
            conn.commit()
            logger.info("pgvector extension verified (CREATE EXTENSION IF NOT EXISTS vector).")

        # Register the pgvector type adapter AFTER the extension is loaded
        register_vector(conn)

        with conn.cursor() as cur:

            # --- Create a temporary table scoped to this transaction ---
            cur.execute(
                """
                CREATE TEMP TABLE sanity_vectors (
                    id   SERIAL PRIMARY KEY,
                    name TEXT        NOT NULL,
                    vec  VECTOR(384) NOT NULL
                )
                """
            )
            logger.info("Temporary table 'sanity_vectors' created.")

            # --- Build a dummy 384-dim vector (all zeros except index 0 = 1.0) ---
            import numpy as np  # numpy is pulled in by sentence-transformers

            dummy_vector = np.zeros(384, dtype=np.float32)
            dummy_vector[0] = 1.0

            cur.execute(
                "INSERT INTO sanity_vectors (name, vec) VALUES (%s, %s)",
                ("sanity_row", dummy_vector),
            )
            logger.info("Dummy 384-dim vector inserted.")

            # --- Query back ---
            cur.execute("SELECT name, vec FROM sanity_vectors LIMIT 1")
            row = cur.fetchone()
            if row is None:
                raise AssertionError("No row found in sanity_vectors after insert.")

            row_name, row_vec = row
            # pgvector 0.5.0 returns a Vector object — use its own API
            vec_list: list = row_vec.to_list()
            vec_dims: int = row_vec.dimensions()
            logger.info(
                "Queried row — name: '%s', vec[0]: %s, dimensions: %d",
                row_name,
                vec_list[0],
                vec_dims,
            )
            assert row_name == "sanity_row", f"Unexpected name: {row_name}"
            assert vec_dims == 384, f"Expected 384 dims, got {vec_dims}"

            # --- Drop temp table explicitly (also dropped on session end) ---
            cur.execute("DROP TABLE IF EXISTS sanity_vectors")
            logger.info("Temporary table 'sanity_vectors' dropped (cleanup complete).")

        conn.commit()
        logger.info("[PASS] PostgreSQL + pgvector test PASSED.\n")
        return True

    except Exception as exc:  # pylint: disable=broad-except
        logger.error("[FAIL] PostgreSQL test FAILED: %s", exc, exc_info=True)
        if conn is not None:
            try:
                conn.rollback()
            except Exception:  # pylint: disable=broad-except
                pass
        return False
    finally:
        if conn is not None:
            conn.close()
            logger.debug("PostgreSQL connection closed.")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
def main() -> None:
    logger.info("=========================================")
    logger.info("  QE-RAG Database Connection Verifier")
    logger.info("=========================================\n")

    neo4j_ok: bool = test_neo4j_connection()
    postgres_ok: bool = test_postgres_connection()

    logger.info("=========================================")
    logger.info("  Summary")
    logger.info("=========================================")
    logger.info("  Neo4j     : %s", "PASS" if neo4j_ok else "FAIL")
    logger.info("  PostgreSQL: %s", "PASS" if postgres_ok else "FAIL")
    logger.info("=========================================\n")

    if not (neo4j_ok and postgres_ok):
        logger.error("One or more database tests FAILED. See logs above for details.")
        sys.exit(1)

    logger.info("All database tests PASSED. Infrastructure is ready for Module B.")
    sys.exit(0)


if __name__ == "__main__":
    main()
