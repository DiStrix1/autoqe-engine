"""
tree_sitter_parser.py — Java AST extraction via tree-sitter.

Uses the modern tree-sitter >= 0.22.0 and tree-sitter-java >= 0.21.0 bindings.
Recursively parses a target Java codebase directory, extracting:
  - Classes  (name, full package name, file path)
  - Methods  (name, return type, signature, raw body text)
  - Method-to-method call invocations within each file

See PROJECT_SPECIFICATION.md Sections 4.1 and 6.1 for the API contract.
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional, Tuple

import tree_sitter_java as tsjava  # tree-sitter-java >= 0.21.0
from tree_sitter import Language, Node, Parser  # tree-sitter >= 0.22.0

# ---------------------------------------------------------------------------
# Logger
# ---------------------------------------------------------------------------
logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Tree-sitter language initialisation  (Section 6.1 — modern bindings)
# ---------------------------------------------------------------------------
JAVA_LANGUAGE: Language = Language(tsjava.language())
_PARSER: Parser = Parser(JAVA_LANGUAGE)

# ---------------------------------------------------------------------------
# Data-transfer objects
# ---------------------------------------------------------------------------


@dataclass
class MethodInfo:
    """Represents a parsed Java method."""

    method_id: str          # "<full_class_name>#<method_name>(<param_types>)"
    class_full_name: str    # e.g. "com.example.UserService"
    name: str               # simple method name
    return_type: str        # e.g. "void", "String", "List<User>"
    signature: str          # full textual signature line
    body: str               # raw method body source text
    file_path: str          # absolute path of the containing .java file


@dataclass
class ClassInfo:
    """Represents a parsed Java class."""

    class_id: str           # same as full_name for uniqueness
    name: str               # simple class name
    full_name: str          # package-qualified name, e.g. "com.example.UserService"
    file_path: str          # absolute path of the .java file
    methods: List[MethodInfo] = field(default_factory=list)


@dataclass
class CallInfo:
    """Represents a resolved method-call edge."""

    caller_id: str          # method_id of the calling method
    callee_name: str        # simple name used at the call-site (best-effort)


@dataclass
class ParseResult:
    """Aggregate result returned by parse_repository()."""

    classes: List[ClassInfo] = field(default_factory=list)
    calls: List[CallInfo] = field(default_factory=list)


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _node_text(node: Node, source: bytes) -> str:
    """Return the UTF-8 decoded text span covered by *node*."""
    return source[node.start_byte:node.end_byte].decode("utf-8", errors="replace")


def _find_children_by_type(node: Node, node_type: str) -> List[Node]:
    """Return all immediate children of *node* whose type matches *node_type*."""
    return [child for child in node.children if child.type == node_type]


def _find_first_child_by_type(node: Node, node_type: str) -> Optional[Node]:
    """Return the first immediate child of *node* with the given type, or None."""
    for child in node.children:
        if child.type == node_type:
            return child
    return None


def _extract_package_name(tree_root: Node, source: bytes) -> str:
    """
    Walk the top-level children looking for a package_declaration node.
    Returns the package string (e.g. 'com.example') or '' if absent.
    """
    for child in tree_root.children:
        if child.type == "package_declaration":
            # package_declaration  →  'package'  <scoped_identifier / identifier>  ';'
            for sub in child.children:
                if sub.type in ("scoped_identifier", "identifier"):
                    return _node_text(sub, source)
    return ""


def _extract_return_type(method_node: Node, source: bytes) -> str:
    """
    Extract the return-type text from a method_declaration node.
    Tree-sitter Java AST places the type annotation directly among the
    method_declaration children, before the method name / parameters.
    """
    # Type nodes that can appear as the return type
    type_node_types = {
        "void_type",
        "integral_type",
        "floating_point_type",
        "boolean_type",
        "array_type",
        "generic_type",
        "scoped_type_identifier",
        "type_identifier",
    }
    for child in method_node.children:
        if child.type in type_node_types:
            return _node_text(child, source)
    return "unknown"


def _extract_parameters_text(method_node: Node, source: bytes) -> str:
    """Return the raw text of the formal_parameters node, e.g. '(String id, int count)'."""
    params_node = _find_first_child_by_type(method_node, "formal_parameters")
    if params_node:
        return _node_text(params_node, source)
    return "()"


def _extract_param_types(method_node: Node, source: bytes) -> str:
    """
    Return a comma-separated string of bare parameter types for use in method_id.
    e.g. 'String,int'  from  (String id, int count)
    """
    params_node = _find_first_child_by_type(method_node, "formal_parameters")
    if not params_node:
        return ""
    type_strings: List[str] = []
    for child in params_node.children:
        if child.type == "formal_parameter":
            # formal_parameter children: modifiers?, type, variable_declarator_id
            for sub in child.children:
                if sub.type not in ("modifiers", "variable_declarator_id", ","):
                    type_strings.append(_node_text(sub, source).strip())
                    break
    return ",".join(type_strings)


def _build_method_id(class_full_name: str, method_name: str, param_types: str) -> str:
    """Construct a stable, unique method identifier string."""
    return f"{class_full_name}#{method_name}({param_types})"


def _collect_method_invocations(
    method_body_node: Node,
    source: bytes,
) -> List[str]:
    """
    Recursively walk *method_body_node* to collect all method_invocation nodes,
    returning a list of simple callee names (best-effort; no full resolution).
    """
    callee_names: List[str] = []
    _walk_for_invocations(method_body_node, source, callee_names)
    return callee_names


def _walk_for_invocations(node: Node, source: bytes, accumulator: List[str]) -> None:
    """DFS walk over an AST sub-tree collecting method_invocation names."""
    if node.type == "method_invocation":
        # method_invocation: [object '.'] methodName arguments
        # The method name is an 'identifier' child; we want the last one before '('
        name_node: Optional[Node] = None
        for child in node.children:
            if child.type == "identifier":
                name_node = child
        if name_node:
            accumulator.append(_node_text(name_node, source))
    for child in node.children:
        _walk_for_invocations(child, source, accumulator)


# ---------------------------------------------------------------------------
# Per-file parser
# ---------------------------------------------------------------------------

def _parse_java_file(file_path: str) -> Tuple[List[ClassInfo], List[CallInfo]]:
    """
    Parse a single .java file and return:
      - A list of ClassInfo objects (each containing its MethodInfo list).
      - A list of CallInfo edges discovered in the file.

    All exceptions are caught and logged; an empty result is returned on error.
    """
    classes: List[ClassInfo] = []
    calls: List[CallInfo] = []

    try:
        source_bytes = Path(file_path).read_bytes()
    except OSError as exc:
        logger.error("Cannot read file '%s': %s", file_path, exc)
        return classes, calls

    try:
        tree = _PARSER.parse(source_bytes)
    except Exception as exc:  # pylint: disable=broad-except
        logger.error("tree-sitter failed to parse '%s': %s", file_path, exc)
        return classes, calls

    root = tree.root_node
    package_name: str = _extract_package_name(root, source_bytes)

    # Walk top-level class_declaration nodes
    for node in root.children:
        if node.type == "class_declaration":
            class_info, file_calls = _parse_class_node(
                node, source_bytes, package_name, file_path
            )
            if class_info:
                classes.append(class_info)
                calls.extend(file_calls)

    return classes, calls


def _parse_class_node(
    class_node: Node,
    source: bytes,
    package_name: str,
    file_path: str,
) -> Tuple[Optional[ClassInfo], List[CallInfo]]:
    """
    Extract a ClassInfo (with nested MethodInfos) from a class_declaration AST node.
    Returns (None, []) if the class name cannot be resolved.
    """
    calls: List[CallInfo] = []

    # Locate the class name identifier
    name_node = _find_first_child_by_type(class_node, "identifier")
    if name_node is None:
        logger.warning("class_declaration in '%s' has no identifier child; skipping.", file_path)
        return None, calls

    class_name: str = _node_text(name_node, source)
    full_name: str = f"{package_name}.{class_name}" if package_name else class_name

    class_info = ClassInfo(
        class_id=full_name,
        name=class_name,
        full_name=full_name,
        file_path=file_path,
    )

    # Walk the class_body for method_declaration nodes
    class_body = _find_first_child_by_type(class_node, "class_body")
    if class_body is None:
        return class_info, calls

    for child in class_body.children:
        if child.type == "method_declaration":
            method_info, method_calls = _parse_method_node(
                child, source, full_name, file_path
            )
            if method_info:
                class_info.methods.append(method_info)
                calls.extend(method_calls)

    return class_info, calls


def _parse_method_node(
    method_node: Node,
    source: bytes,
    class_full_name: str,
    file_path: str,
) -> Tuple[Optional[MethodInfo], List[CallInfo]]:
    """
    Extract a MethodInfo from a method_declaration AST node, plus any
    intra-file CallInfo edges from its body.

    Returns (None, []) if the method name cannot be resolved.
    """
    calls: List[CallInfo] = []

    name_node = _find_first_child_by_type(method_node, "identifier")
    if name_node is None:
        return None, calls

    method_name: str = _node_text(name_node, source)
    return_type: str = _extract_return_type(method_node, source)
    params_text: str = _extract_parameters_text(method_node, source)
    param_types: str = _extract_param_types(method_node, source)

    signature: str = f"{return_type} {method_name}{params_text}"
    method_id: str = _build_method_id(class_full_name, method_name, param_types)

    # Extract raw body text
    body_node = _find_first_child_by_type(method_node, "block")
    body_text: str = _node_text(body_node, source) if body_node else ""

    method_info = MethodInfo(
        method_id=method_id,
        class_full_name=class_full_name,
        name=method_name,
        return_type=return_type,
        signature=signature,
        body=body_text,
        file_path=file_path,
    )

    # Collect method invocations within the body
    if body_node:
        callee_names = _collect_method_invocations(body_node, source)
        for callee_name in callee_names:
            calls.append(CallInfo(caller_id=method_id, callee_name=callee_name))

    return method_info, calls


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

# Directories to exclude during recursive walk (case-insensitive segment match)
_EXCLUDED_DIRS: frozenset[str] = frozenset({"target", "test", ".git", ".mvn", "node_modules"})


def _is_excluded(path: Path) -> bool:
    """Return True if any segment of *path* matches an excluded directory name."""
    return any(part.lower() in _EXCLUDED_DIRS for part in path.parts)


def parse_repository(repo_path: str) -> ParseResult:
    """
    Recursively walk *repo_path*, parse every .java file (excluding /target/
    and /test/ subtrees), and return a ParseResult aggregating all extracted
    ClassInfo and CallInfo objects.

    Parameters
    ----------
    repo_path:
        Absolute or relative path to the root of the Java project directory.

    Returns
    -------
    ParseResult
        Contains `classes` (List[ClassInfo]) and `calls` (List[CallInfo]).
    """
    root = Path(repo_path).resolve()
    if not root.is_dir():
        raise ValueError(f"repo_path is not a valid directory: {repo_path}")

    result = ParseResult()
    java_files_found: int = 0

    logger.info("Starting repository parse at: %s", root)

    for java_file in root.rglob("*.java"):
        if _is_excluded(java_file.relative_to(root)):
            logger.debug("Skipping excluded path: %s", java_file)
            continue

        java_files_found += 1
        logger.debug("Parsing: %s", java_file)

        try:
            file_classes, file_calls = _parse_java_file(str(java_file))
            result.classes.extend(file_classes)
            result.calls.extend(file_calls)
        except Exception as exc:  # pylint: disable=broad-except
            logger.error("Unexpected error processing '%s': %s", java_file, exc, exc_info=True)

    total_methods = sum(len(c.methods) for c in result.classes)
    logger.info(
        "Parse complete — files scanned: %d, classes: %d, methods: %d, call edges: %d",
        java_files_found,
        len(result.classes),
        total_methods,
        len(result.calls),
    )
    return result
