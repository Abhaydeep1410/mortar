"""Blueprint validation: JSON Schema layer, then semantic rules.

Rules and error codes are specified in docs/blueprint-spec.md section 3.
Semantic rules only run once the schema layer passes, because they rely on the
structure the schema guarantees. All errors from a layer are returned together.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator

from .vocab import (
    AUTH_CAPABILITIES,
    CAPABILITIES,
    CAPABILITIES_NEEDING_AUTH,
    CAPABILITY_CONFLICTS,
    PERMISSION_ACTIONS,
    WRITE_LIKE_ACTIONS,
)

SCHEMA_PATH = Path(__file__).resolve().parents[3] / "schemas" / "blueprint.schema.json"


@dataclass(frozen=True)
class Issue:
    code: str
    path: str
    message: str
    severity: str = "error"  # "error" or "warning"

    def __str__(self) -> str:
        return f"[{self.severity}] {self.code} at {self.path or '<root>'}: {self.message}"


def load_blueprint(source: str | Path) -> dict[str, Any]:
    """Load a blueprint from a path or a YAML string."""
    if isinstance(source, Path) or (isinstance(source, str) and "\n" not in source and Path(source).exists()):
        text = Path(source).read_text(encoding="utf-8")
    else:
        text = str(source)
    data = yaml.safe_load(text)
    if not isinstance(data, dict):
        raise ValueError("blueprint must be a YAML mapping")
    return data


def _schema_validator() -> Draft202012Validator:
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


def _path(parts: Any) -> str:
    out = ""
    for part in parts:
        out += f"[{part}]" if isinstance(part, int) else (f".{part}" if out else str(part))
    return out


def validate_schema(blueprint: dict[str, Any]) -> list[Issue]:
    issues = []
    for err in sorted(_schema_validator().iter_errors(blueprint), key=lambda e: list(map(str, e.absolute_path))):
        issues.append(Issue("E_SCHEMA", _path(err.absolute_path), err.message))
    return issues


def validate_semantics(bp: dict[str, Any]) -> list[Issue]:
    """Semantic rules. Assumes the blueprint already passed the schema layer."""
    issues: list[Issue] = []
    entities = bp["frontend"]["entities"]
    entity_by_name: dict[str, dict[str, Any]] = {}
    for i, entity in enumerate(entities):
        if entity["name"] in entity_by_name:
            issues.append(Issue("E_ENTITY_DUPLICATE", f"frontend.entities[{i}].name", f"duplicate entity {entity['name']!r}"))
        entity_by_name.setdefault(entity["name"], entity)

    # Pages
    for i, page in enumerate(bp["frontend"]["pages"]):
        entity = entity_by_name.get(page["entity"])
        if entity is None:
            issues.append(Issue("E_PAGE_ENTITY", f"frontend.pages[{i}].entity", f"unknown entity {page['entity']!r}"))
            continue
        group_by = page.get("groupBy")
        if page["type"] == "kanban":
            if group_by is None:
                issues.append(Issue("E_PAGE_GROUPBY", f"frontend.pages[{i}]", "kanban pages require groupBy"))
            elif group_by not in entity["fields"]:
                issues.append(Issue("E_PAGE_GROUPBY", f"frontend.pages[{i}].groupBy", f"{group_by!r} is not a field of {entity['name']}"))
        elif group_by is not None:
            issues.append(Issue("E_PAGE_GROUPBY", f"frontend.pages[{i}].groupBy", "groupBy is only valid on kanban pages"))

    # Capabilities
    caps = bp["capabilities"]
    for i, cap in enumerate(caps):
        if cap not in CAPABILITIES:
            issues.append(Issue("E_CAPABILITY_UNKNOWN", f"capabilities[{i}]", f"{cap!r} is not in the capability vocabulary"))
    known = [c for c in caps if c in CAPABILITIES]
    for pair in CAPABILITY_CONFLICTS:
        if pair <= set(known):
            a, b = sorted(pair)
            issues.append(Issue("E_CAPABILITY_CONFLICT", "capabilities", f"{a} conflicts with {b}"))
    if not (set(known) & AUTH_CAPABILITIES):
        for cap in known:
            if cap in CAPABILITIES_NEEDING_AUTH:
                issues.append(Issue("E_CAPABILITY_REQUIRES", "capabilities", f"{cap} requires an authentication capability"))

    # Roles and permissions
    seen_roles: set[str] = set()
    readonly_entities = {e["name"].lower() for e in entities if e.get("readOnly")}
    resources = {name.lower() for name in entity_by_name}
    for i, role in enumerate(bp["roles"]):
        if role["name"] in seen_roles:
            issues.append(Issue("E_ROLE_DUPLICATE", f"roles[{i}].name", f"duplicate role {role['name']!r}"))
        seen_roles.add(role["name"])
        for j, perm in enumerate(role["permissions"]):
            where = f"roles[{i}].permissions[{j}]"
            if perm == "*":
                if readonly_entities:
                    issues.append(Issue("E_PERMISSION_READONLY", where, "'*' grants write access to read-only entities; list permissions explicitly"))
                continue
            resource, sep, action = perm.partition(":")
            if not sep or resource not in resources or action not in PERMISSION_ACTIONS:
                issues.append(
                    Issue("E_PERMISSION_FORMAT", where, f"{perm!r} must be '*' or '<entity>:<action>' with a declared entity and action in {sorted(PERMISSION_ACTIONS)}")
                )
                continue
            if resource in readonly_entities and action in WRITE_LIKE_ACTIONS:
                issues.append(Issue("E_PERMISSION_READONLY", where, f"{perm!r} writes to read-only entity {resource!r}"))

    # Services and controllers
    seen_units: set[str] = set()
    for key in ("custom_services", "custom_controllers"):
        for i, unit in enumerate(bp.get(key, [])):
            if unit["name"] in seen_units:
                issues.append(Issue("E_SERVICE_DUPLICATE", f"{key}[{i}].name", f"duplicate name {unit['name']!r}"))
            seen_units.add(unit["name"])

    # Cross-cutting capability rules
    if "export.csv" in known:
        exports = any(
            p == "*" or p.endswith(":export") or p.endswith(":*")
            for role in bp["roles"]
            for p in role["permissions"]
        )
        if not exports:
            issues.append(Issue("E_EXPORT_NEEDS_ENTITY", "roles", "export.csv is requested but no role holds an export permission"))
    if "authorization.rbac" in known and all(r["permissions"] == ["*"] for r in bp["roles"]):
        issues.append(Issue("E_RBAC_NEEDS_ROLES", "roles", "every role holds '*', so RBAC would be vacuous", severity="warning"))

    return issues


def validate_blueprint(bp: dict[str, Any]) -> list[Issue]:
    """Full validation. Returns all issues of the first failing layer."""
    schema_issues = validate_schema(bp)
    if schema_issues:
        return schema_issues
    return validate_semantics(bp)


def errors_only(issues: list[Issue]) -> list[Issue]:
    return [i for i in issues if i.severity == "error"]
