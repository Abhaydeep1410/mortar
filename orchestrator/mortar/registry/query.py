"""Read-only questions about the registry, shared by the MCP server and tests.

Everything here is a lookup over `ModuleInfo` records; nothing generates or writes.
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any

from .loader import ModuleInfo


class UnknownModule(KeyError):
    """No module with that id in the registry."""


def _terms(text: str) -> list[str]:
    return [t for t in re.split(r"[^a-z0-9]+", text.lower()) if t]


def _summary(info: ModuleInfo) -> dict[str, Any]:
    return {
        "module_id": info.id,
        "version": info.version,
        "name": info.display_name,
        "description": info.description,
        "provides": sorted(info.provides_capabilities),
        "interfaces": list(info.provides_interfaces),
        "tags": list(info.tags),
    }


def search_components(registry: Mapping[str, ModuleInfo], query: str, limit: int = 10) -> list[dict[str, Any]]:
    """Modules matching the query words, best first. Ties break by module id. A blank query lists all."""
    terms = _terms(query)
    scored: list[tuple[int, str]] = []
    for info in registry.values():
        id_words = set(_terms(info.id))
        tag_words = {w for t in info.tags for w in _terms(t)}
        text_words = set(_terms(" ".join([info.display_name, info.description, info.category, *info.provides_interfaces])))
        def hits(term: str, words: set[str]) -> int:
            if term in words:
                return 2
            return int(len(term) >= 3 and any(w.startswith(term) for w in words))

        score = sum(3 * hits(t, id_words) + 2 * hits(t, tag_words) + hits(t, text_words) for t in terms)
        if score or not terms:
            scored.append((score, info.id))
    scored.sort(key=lambda pair: (-pair[0], pair[1]))
    return [_summary(registry[module_id]) for _, module_id in scored[: max(limit, 0)]]


def get_skill(registry: Mapping[str, ModuleInfo], module_id: str) -> str:
    """The module's SKILL.md: its contract, never its code."""
    if module_id not in registry:
        raise UnknownModule(module_id)
    return registry[module_id].skill_path.read_text(encoding="utf-8")


def _providers(registry: Mapping[str, ModuleInfo], capability: str) -> list[str]:
    return sorted(m.id for m in registry.values() if capability in m.provides_capabilities)


def get_dependencies(registry: Mapping[str, ModuleInfo], module_id: str) -> dict[str, Any]:
    """What a module needs: required capabilities and the modules that provide them (transitively)."""
    if module_id not in registry:
        raise UnknownModule(module_id)
    info = registry[module_id]
    needs = []
    unmet = []
    for capability in sorted(info.requires_capabilities):
        providers = _providers(registry, capability)
        needs.append({"capability": capability, "provided_by": providers})
        if not providers:
            unmet.append(capability)
    closure: list[str] = []
    pending = [module_id]
    while pending:
        current = pending.pop()
        for capability in sorted(registry[current].requires_capabilities):
            for provider in _providers(registry, capability):
                if provider not in closure and provider != module_id:
                    closure.append(provider)
                    pending.append(provider)
    return {
        "module_id": info.id,
        "requires": needs,
        "requires_modules": sorted(closure),
        "unmet_capabilities": unmet,
        "requires_env": list(info.requires_env),
        "conflicts_with": sorted(info.conflicts_with),
        "spring_boot": info.spring_boot,
        "java": info.java,
        "maven": info.coordinates,
    }
