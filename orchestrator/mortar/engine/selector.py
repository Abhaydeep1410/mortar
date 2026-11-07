"""Deterministic Selector: blueprint capabilities -> registry modules + their configs.

No model is involved. Given a blueprint and a registry it partitions the requested capabilities into
`components` (served by a module) and `uncovered` (left for the Composer), and fills each module's
config from blueprint values. Same input, same output.
"""

from __future__ import annotations

import copy
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any

from jsonschema import Draft202012Validator

from ..registry.loader import ModuleInfo
from .naming import entity_table, find_path_collisions
from .vocab import ABLATION_ORDER_PRIMARY, CAPABILITY_CONFLICTS


class SelectionError(ValueError):
    """The blueprint cannot be served by the registry (conflict, version, or bad config)."""


@dataclass(frozen=True)
class Selection:
    blueprint: dict[str, Any]
    active: tuple[str, ...]
    skipped: tuple[tuple[str, str], ...] = field(default_factory=tuple)  # (module id, reason)

    @property
    def components(self) -> list[dict[str, Any]]:
        return self.blueprint["components"]

    @property
    def uncovered(self) -> list[str]:
        return self.blueprint["uncovered"]


def _version_tuple(text: str) -> tuple[int, ...]:
    parts = re.findall(r"\d+|x", text)
    return tuple(0 if p == "x" else int(p) for p in parts) or (0,)


def _satisfies(version: str, constraint: str) -> bool:
    """Space-separated comparators: `>=4.0.0 <5.0.0`. `4.1.x` counts as 4.1.0."""
    have = _version_tuple(version)
    for comparator in constraint.split():
        match = re.fullmatch(r"(>=|<=|>|<|==)?(.+)", comparator)
        op, want = match.group(1) or "==", _version_tuple(match.group(2))
        size = max(len(have), len(want))
        a, b = have + (0,) * (size - len(have)), want + (0,) * (size - len(want))
        if not {">=": a >= b, "<=": a <= b, ">": a > b, "<": a < b, "==": a == b}[op]:
            return False
    return True


def _admin_role(roles: Sequence[Mapping[str, Any]]) -> str:
    holders = [r["name"] for r in roles if "*" in r["permissions"]]
    if not holders:
        raise SelectionError("audit.logging needs a role with permission '*' to read the audit log")
    return "ADMIN" if "ADMIN" in holders else holders[0]


def _config_for(module_id: str, blueprint: Mapping[str, Any]) -> dict[str, Any]:
    name = blueprint["metadata"]["name"]
    table = entity_table(blueprint)
    refs = [{"path": e["path"], "name": e["resource"]} for e in table]
    if module_id == "authentication.jwt":
        return {
            "jwtSecretEnv": "JWT_SECRET",
            "ttlMinutes": 60,
            "issuer": name,
            "allowedOrigins": ["http://localhost:3000"],
        }
    if module_id == "authorization.rbac":
        roles = [{"name": r["name"], "permissions": list(r["permissions"])} for r in blueprint["roles"]]
        return {"resources": refs, "roles": roles}
    if module_id == "audit.logging":
        writable = [{"path": e["path"], "name": e["resource"]} for e in table if not e["readOnly"]]
        if not writable:
            raise SelectionError("audit.logging requested but every entity is read-only")
        return {"entities": writable, "adminRole": _admin_role(blueprint["roles"])}
    if module_id == "crud.pagination":
        return {"defaultPageSize": 20, "maxPageSize": 100}
    if module_id == "export.csv":
        return {"batchSize": 500, "neutralizeFormulas": True}
    if module_id == "deployment.docker":
        return {"appName": name, "databaseName": name.replace("-", "_"), "databaseUser": "app"}
    return {}


def _depth(module_id: str, registry: Mapping[str, ModuleInfo], by_cap: Mapping[str, str]) -> int:
    needs = [by_cap[c] for c in registry[module_id].requires_capabilities if c in by_cap]
    return 0 if not needs else 1 + max(_depth(n, registry, by_cap) for n in needs)


def select(
    blueprint: Mapping[str, Any],
    registry: Mapping[str, ModuleInfo],
    active: Sequence[str] | None = None,
) -> Selection:
    """Fill `components` and `uncovered`. `active` restricts the registry (coverage ablation)."""
    result = copy.deepcopy(dict(blueprint))
    capabilities: list[str] = list(result["capabilities"])

    for pair in CAPABILITY_CONFLICTS:
        if pair <= set(capabilities):
            raise SelectionError(f"conflicting capabilities: {', '.join(sorted(pair))}")
    names = [e["name"] for e in result["frontend"]["entities"]]
    if find_path_collisions(names):
        raise SelectionError(f"entities share a URL path: {find_path_collisions(names)}")

    offered = list(registry) if active is None else list(active)
    unknown = [m for m in offered if m not in registry]
    if unknown:
        raise SelectionError(f"unknown modules in active set: {unknown}")

    boot = str(result["backend"]["springBootVersion"])
    java = str(result["backend"]["javaVersion"])
    skipped: list[tuple[str, str]] = []
    candidates: list[str] = []
    for module_id in offered:
        info = registry[module_id]
        if not _satisfies(boot, info.spring_boot):
            skipped.append((module_id, f"spring boot {boot} outside {info.spring_boot}"))
        elif not _satisfies(java, info.java):
            skipped.append((module_id, f"java {java} outside {info.java}"))
        elif info.provides_capabilities & set(capabilities):
            candidates.append(module_id)

    # Skip modules whose required capabilities no other candidate provides; repeat until stable.
    changed = True
    while changed:
        changed = False
        provided = {c for m in candidates for c in registry[m].provides_capabilities}
        for module_id in candidates:
            missing = registry[module_id].requires_capabilities - provided
            if missing:
                candidates.remove(module_id)
                skipped.append((module_id, f"requires {', '.join(sorted(missing))}"))
                changed = True
                break

    by_cap = {c: m for m in candidates for c in registry[m].provides_capabilities}
    for module_id in candidates:
        for other in candidates:
            if other in registry[module_id].conflicts_with:
                raise SelectionError(f"modules conflict: {module_id} and {other}")

    order = {m: i for i, m in enumerate(ABLATION_ORDER_PRIMARY)}
    candidates.sort(key=lambda m: (_depth(m, registry, by_cap), order.get(m, len(order)), m))

    components = []
    for module_id in candidates:
        info = registry[module_id]
        config = _config_for(module_id, result)
        errors = sorted(Draft202012Validator(info.config_schema).iter_errors(config), key=str)
        if errors:
            raise SelectionError(f"{module_id}: invalid config: {errors[0].message}")
        components.append({"module_id": module_id, "version": info.version, "config": config})

    result["components"] = components
    result["uncovered"] = [c for c in capabilities if c not in by_cap]
    return Selection(result, tuple(candidates), tuple(skipped))
