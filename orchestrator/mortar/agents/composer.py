"""Composer (LLM #2): writes only the app-specific remainder, using each selected module's SKILL.md.

It never sees module implementations, only their contracts. Where it may write is enforced here, not
requested in the prompt, so a model cannot overwrite what the Assembler produced.
"""

from __future__ import annotations

import re
from collections.abc import Collection, Mapping
from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Any

import yaml

from ..engine.naming import entity_table
from ..llm import LlmClient, Usage, tool_spec
from ..registry.loader import ModuleInfo, REPO_ROOT

DESIGN_DOC = REPO_ROOT / "docs" / "benchmark-design.md"

STACK = (
    "Target stack: Java 21, Spring Boot 4.1.x, Maven, PostgreSQL 16 via Spring Data JPA. "
    "The project skeleton already exists; you add the application code."
)

# Extra files an uncovered capability may need (the Assembler writes them when a module covers it).
DOCKER_FILES = frozenset(
    {"docker-compose.yml", ".env.example", ".dockerignore", "backend/Dockerfile", "backend/.dockerignore",
     "frontend/Dockerfile", "frontend/nginx.conf", "frontend/.dockerignore"}
)

SYSTEM_PROMPT = (
    "You are a senior Java engineer completing a Spring Boot application whose skeleton and reusable "
    "modules are already in place. You respond only by calling write_files once. You write only the "
    "application-specific code. Never re-implement what a listed module provides."
)

WRITE_FILES = tool_spec(
    "write_files",
    "Write the application-specific files.",
    {
        "type": "object",
        "properties": {
            "files": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {"path": {"type": "string"}, "content": {"type": "string"}},
                    "required": ["path", "content"],
                },
            }
        },
        "required": ["files"],
    },
)


class ComposeError(ValueError):
    """The Composer's output is unusable (bad paths, collisions, empty)."""


@dataclass(frozen=True)
class ComposeResult:
    files: dict[str, str]
    usage: Usage


def contract_text() -> str:
    """Section 5 of benchmark-design.md, as the other strategies receive it."""
    from evaluator.common import load_contract  # repo-level helper shared with Baseline A

    return load_contract(DESIGN_DOC)


def allowed_prefixes(blueprint: Mapping[str, Any]) -> tuple[str, ...]:
    package = blueprint["backend"]["basePackage"].replace(".", "/")
    return (f"backend/src/main/java/{package}/", "backend/src/main/resources/")


def check_path(path: str, blueprint: Mapping[str, Any], existing: Collection[str]) -> str:
    pure = PurePosixPath(path)
    if not path or "\\" in path or pure.is_absolute() or ".." in pure.parts or "\x00" in path:
        raise ComposeError(f"unsafe path: {path!r}")
    normalized = pure.as_posix()
    if normalized in existing:
        raise ComposeError(f"{normalized} already exists; the Assembler owns it")
    if normalized.startswith(allowed_prefixes(blueprint)):
        return normalized
    uncovered = set(blueprint["uncovered"])
    if normalized in DOCKER_FILES and "deployment.docker" in uncovered:
        return normalized
    raise ComposeError(f"path outside the Composer's allowed locations: {normalized}")


def build_user_prompt(blueprint: Mapping[str, Any], registry: Mapping[str, ModuleInfo], existing: Collection[str]) -> str:
    selected = [c["module_id"] for c in blueprint["components"]]
    skills = "\n\n".join(registry[m].skill_path.read_text(encoding="utf-8").strip() for m in selected)
    entities = "\n".join(
        f"- {e['name']}: path /api/{e['path']}, permission resource `{e['resource']}`, fields {e['fields']}"
        + (", read-only" if e["readOnly"] else "")
        for e in entity_table(blueprint)
    )
    shown = {k: v for k, v in blueprint.items() if k not in ("components", "uncovered")}
    shown["selected_modules"] = [
        {"module_id": c["module_id"], "config": c["config"]} for c in blueprint["components"]
    ]
    uncovered = blueprint["uncovered"]
    return "\n\n".join(
        [
            "Write the remaining application code for this blueprint.",
            "Blueprint:\n" + yaml.safe_dump(shown, sort_keys=False, allow_unicode=True),
            "Entities and URL paths (use exactly these):\n" + entities,
            STACK,
            "Selected modules already wired in. Use them as their contracts say:\n\n" + (skills or "(none)"),
            "Capabilities with NO module (you must implement them yourself, with Spring Boot idioms): "
            + (", ".join(uncovered) if uncovered else "none"),
            f"Files that already exist (never write these): {', '.join(sorted(existing))}",
            f"You may write only under `{allowed_prefixes(blueprint)[0]}` and `{allowed_prefixes(blueprint)[1]}` "
            "(no application.yml there: it exists), "
            + ("plus the Docker files named in the blueprint's uncovered deployment capability. "
               if "deployment.docker" in uncovered else "")
            + "Implement every entity (JPA class, repository, controller), every custom service, the user "
            "store with the seeded users, and anything the contract requires that a selected module does not provide.",
            contract_text(),
        ]
    )


def compose(
    blueprint: Mapping[str, Any],
    registry: Mapping[str, ModuleInfo],
    client: LlmClient,
    existing: Collection[str],
) -> ComposeResult:
    result = client.call_tool(SYSTEM_PROMPT, build_user_prompt(blueprint, registry, existing), WRITE_FILES)
    entries = result.arguments.get("files")
    if not isinstance(entries, list) or not entries:
        raise ComposeError("the Composer returned no files")
    files: dict[str, str] = {}
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("path"), str) or not isinstance(entry.get("content"), str):
            raise ComposeError("each file needs a string path and content")
        path = check_path(entry["path"], blueprint, existing)
        if path in files:
            raise ComposeError(f"duplicate path in output: {path}")
        files[path] = entry["content"]
    return ComposeResult(files, result.usage)
