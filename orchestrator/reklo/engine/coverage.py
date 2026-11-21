"""Registry subsetting and coverage computation for the coverage ablation."""

from __future__ import annotations

from collections.abc import Iterable, Sequence

from .vocab import ABLATION_ORDER_PRIMARY, MODULE_REQUIRES, REGISTRY_V1


def modules_for_k(
    k: int, order: Sequence[str] = ABLATION_ORDER_PRIMARY
) -> tuple[list[str], list[str]]:
    """Return (active, skipped) module IDs for a registry of size k.

    The first k modules of `order` are offered. A module whose inter-module
    requirements are not provided by the other active modules is skipped (and
    logged by callers); skipping is repeated until the set is stable.
    """
    if not 0 <= k <= len(order):
        raise ValueError(f"k must be between 0 and {len(order)}, got {k}")
    active = list(order[:k])
    skipped: list[str] = []
    changed = True
    while changed:
        changed = False
        provided = capabilities_provided(active)
        for module_id in list(active):
            needed = MODULE_REQUIRES.get(module_id, frozenset())
            if not needed <= provided:
                active.remove(module_id)
                skipped.append(module_id)
                changed = True
                break
    return active, skipped


def capabilities_provided(module_ids: Iterable[str]) -> frozenset[str]:
    provided: set[str] = set()
    for module_id in module_ids:
        provided |= REGISTRY_V1[module_id]
    return frozenset(provided)


def partition_capabilities(
    capabilities: Sequence[str], active_modules: Iterable[str]
) -> tuple[list[str], list[str]]:
    """Split requested capabilities into (covered, uncovered) for a registry."""
    provided = capabilities_provided(active_modules)
    covered = [c for c in capabilities if c in provided]
    uncovered = [c for c in capabilities if c not in provided]
    return covered, uncovered


def coverage(capabilities: Sequence[str], active_modules: Iterable[str]) -> float:
    """Fraction of requested capabilities served by the active modules."""
    if not capabilities:
        raise ValueError("a blueprint must request at least one capability")
    covered, _ = partition_capabilities(capabilities, active_modules)
    return len(covered) / len(capabilities)
