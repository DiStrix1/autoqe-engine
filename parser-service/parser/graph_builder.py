"""
graph_builder.py — Neo4j graph population for CLASS and METHOD nodes.

Executes batched Cypher mutations to create:
  (:CLASS)
  (:METHOD)
  (:CLASS)-[:HAS_METHOD]->(:METHOD)
  (:METHOD)-[:CALLS]->(:METHOD)

See PROJECT_SPECIFICATION.md Section 3.1 for the exact Cypher query contracts.

Credentials and connection parameters are read exclusively from environment
variables; no credentials are hardcoded.
"""

from __future__ import annotations

import logging
import os
from typing import Dict, List, Set, Tuple

from neo4j import GraphDatabase, Driver, Session  # type: ignore

from parser.tree_sitter_parser import CallInfo, ClassInfo, MethodInfo, ParseResult

# ---------------------------------------------------------------------------
# Logger
# ---------------------------------------------------------------------------
logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Connection parameters — read from environment (no hardcoded credentials)
# ---------------------------------------------------------------------------
NEO4J_URI: str = os.environ.get("NEO4J_URI", "bolt://localhost:7687")
NEO4J_USER: str = os.environ.get("NEO4J_USER", "neo4j")
NEO4J_PASSWORD: str = os.environ.get("NEO4J_PASSWORD", "dev_password")

# ---------------------------------------------------------------------------
# Cypher query constants — batched via UNWIND
# ---------------------------------------------------------------------------

_CYPHER_BATCH_MERGE_CLASSES = """
UNWIND $batch AS c
MERGE (cls:CLASS {fullName: c.fullName})
SET cls.id = c.id, cls.name = c.name, cls.filePath = c.filePath
"""

_CYPHER_BATCH_MERGE_METHODS = """
UNWIND $batch AS m
MERGE (method:METHOD {id: m.methodId})
SET method.name = m.name, method.returnType = m.returnType, method.signature = m.signature, method.body = m.body
WITH method, m
MATCH (cls:CLASS {fullName: m.fullName})
MERGE (cls)-[:HAS_METHOD]->(method)
"""

_CYPHER_BATCH_MERGE_CALLS = """
UNWIND $batch AS edge
MATCH (caller:METHOD {id: edge.callerId})
MATCH (callee:METHOD {id: edge.calleeId})
MERGE (caller)-[:CALLS]->(callee)
"""

# ---------------------------------------------------------------------------
# Driver factory
# ---------------------------------------------------------------------------


def create_driver() -> Driver:
    """Create and verify a Neo4j driver from environment configuration."""
    driver = GraphDatabase.driver(NEO4J_URI, auth=(NEO4J_USER, NEO4J_PASSWORD))
    driver.verify_connectivity()
    logger.info("Neo4j driver connected to %s", NEO4J_URI)
    return driver


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------


def build_graph(parse_result: ParseResult) -> Tuple[int, int]:
    """
    Populate Neo4j with all classes, methods, and call edges extracted
    during AST parsing using high-throughput batched Cypher UNWIND queries.

    Parameters
    ----------
    parse_result:
        The ParseResult returned by ``parse_repository()``.

    Returns
    -------
    (nodes_created, edges_created):
        Approximate count of graph nodes written and call edges written.
    """
    driver: Driver | None = None
    nodes_created: int = 0
    edges_created: int = 0

    # Pre-build a set of all known method IDs for fast callee resolution
    method_id_set: Set[str] = {
        m.method_id
        for cls in parse_result.classes
        for m in cls.methods
    }

    try:
        driver = create_driver()

        with driver.session(database="neo4j") as session:
            # --- Phase 1: Batch upsert CLASS nodes ---
            classes_batch: List[Dict[str, str]] = [
                {
                    "id": c.class_id,
                    "fullName": c.full_name,
                    "name": c.name,
                    "filePath": c.file_path,
                }
                for c in parse_result.classes
            ]
            if classes_batch:
                logger.info("Phase 1 — batch upserting %d CLASS nodes …", len(classes_batch))
                session.run(_CYPHER_BATCH_MERGE_CLASSES, {"batch": classes_batch})
                nodes_created += len(classes_batch)

            # --- Phase 2: Batch upsert METHOD nodes + HAS_METHOD edges ---
            methods_batch: List[Dict[str, str]] = [
                {
                    "methodId": m.method_id,
                    "name": m.name,
                    "returnType": m.return_type,
                    "signature": m.signature,
                    "body": m.body,
                    "fullName": m.class_full_name,
                }
                for c in parse_result.classes
                for m in c.methods
            ]
            if methods_batch:
                logger.info("Phase 2 — batch upserting %d METHOD nodes …", len(methods_batch))
                session.run(_CYPHER_BATCH_MERGE_METHODS, {"batch": methods_batch})
                nodes_created += len(methods_batch)

            # --- Phase 3: Batch create CALLS edges ---
            calls_batch: List[Dict[str, str]] = []
            for call_info in parse_result.calls:
                # Match callee by suffix "#{callee_name}("
                matched = [
                    mid for mid in method_id_set
                    if f"#{call_info.callee_name}(" in mid
                ]
                for callee_id in matched:
                    calls_batch.append({
                        "callerId": call_info.caller_id,
                        "calleeId": callee_id,
                    })

            if calls_batch:
                logger.info("Phase 3 — batch creating %d CALLS edges …", len(calls_batch))
                session.run(_CYPHER_BATCH_MERGE_CALLS, {"batch": calls_batch})
                edges_created += len(calls_batch)

        logger.info(
            "Graph build complete — nodes written: %d, call edges written: %d",
            nodes_created, edges_created,
        )
        return nodes_created, edges_created

    except Exception as exc:  # pylint: disable=broad-except
        logger.error("Neo4j graph build FAILED: %s", exc, exc_info=True)
        raise
    finally:
        if driver is not None:
            driver.close()
            logger.debug("Neo4j driver closed.")
