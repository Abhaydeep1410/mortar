"""Stable hash of a blueprint, recorded in every event so runs can prove they shared a blueprint."""

from __future__ import annotations

import datetime as dt
import hashlib
import json
from collections.abc import Mapping
from typing import Any

# Filled by the Selector, so they differ between registry sizes for the same Planner output.
SELECTOR_OUTPUT_KEYS = ("components", "uncovered")


def _plain(value: Any) -> Any:
    if isinstance(value, (dt.datetime, dt.date)):
        return value.isoformat()
    raise TypeError(f"cannot hash value of type {type(value).__name__}")


def canonical_json(blueprint: Mapping[str, Any]) -> str:
    """Planner-owned part of the blueprint as canonical JSON (sorted keys, no whitespace)."""
    planner_part = {k: v for k, v in blueprint.items() if k not in SELECTOR_OUTPUT_KEYS}
    return json.dumps(planner_part, sort_keys=True, separators=(",", ":"), ensure_ascii=False, default=_plain)


def blueprint_sha256(blueprint: Mapping[str, Any]) -> str:
    """SHA-256 of the Planner's output; `components` and `uncovered` are left out."""
    return hashlib.sha256(canonical_json(blueprint).encode("utf-8")).hexdigest()
