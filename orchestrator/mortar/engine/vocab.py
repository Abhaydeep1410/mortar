"""Closed vocabularies for Mortar blueprints.

Single source of truth for capability IDs, capability rules, permission actions,
and the v1 registry inventory. See docs/blueprint-spec.md section 2.
"""

from __future__ import annotations

# Every capability a blueprint may request. A capability in this set but without a
# registry module is valid and becomes "uncovered" work for the Composer.
CAPABILITIES: frozenset[str] = frozenset(
    {
        "authentication.jwt",
        "authentication.oauth2",
        "authorization.rbac",
        "audit.logging",
        "crud.pagination",
        "export.csv",
        "deployment.docker",
        "observability.actuator",
        "database.flyway",
        "security.rate-limit",
    }
)

# Pairs of capabilities that cannot coexist in one blueprint.
CAPABILITY_CONFLICTS: frozenset[frozenset[str]] = frozenset(
    {frozenset({"authentication.jwt", "authentication.oauth2"})}
)

# capability -> acceptable authentication capabilities it needs (any one suffices).
AUTH_CAPABILITIES: frozenset[str] = frozenset({"authentication.jwt", "authentication.oauth2"})
CAPABILITIES_NEEDING_AUTH: frozenset[str] = frozenset({"authorization.rbac", "audit.logging"})

# Permission grammar: "*" or "<entity-lowercase>:<action>".
PERMISSION_ACTIONS: frozenset[str] = frozenset({"read", "write", "delete", "export", "*"})
WRITE_LIKE_ACTIONS: frozenset[str] = frozenset({"write", "delete", "*"})

# v1 registry: module id -> capabilities it provides.
REGISTRY_V1: dict[str, frozenset[str]] = {
    "authentication.jwt": frozenset({"authentication.jwt"}),
    "authorization.rbac": frozenset({"authorization.rbac"}),
    "audit.logging": frozenset({"audit.logging"}),
    "crud.pagination": frozenset({"crud.pagination"}),
    "export.csv": frozenset({"export.csv"}),
    "deployment.docker": frozenset(
        {"deployment.docker", "observability.actuator", "database.flyway"}
    ),
}

# Registry-level requirements between modules (module id -> capabilities needed
# from *other* selected modules). Platform capabilities (database.postgresql) are
# always available and omitted here.
MODULE_REQUIRES: dict[str, frozenset[str]] = {
    "authorization.rbac": frozenset({"authentication.jwt"}),
    "audit.logging": frozenset({"authentication.jwt"}),
}

# Pre-registered coverage-ablation orders (docs/benchmark-design.md section 3).
ABLATION_ORDER_PRIMARY: tuple[str, ...] = (
    "crud.pagination",
    "authentication.jwt",
    "authorization.rbac",
    "audit.logging",
    "export.csv",
    "deployment.docker",
)
ABLATION_ORDER_ALTERNATE: tuple[str, ...] = (
    "deployment.docker",
    "export.csv",
    "audit.logging",
    "authorization.rbac",
    "authentication.jwt",
    "crud.pagination",
)
