"""Names derived from blueprint entities. One definition, used by every stage.

The REST path of an entity (`/api/<path>`) is written into the backend modules' config and into the
frontend config, and the benchmark oracles call it directly, so all of them must agree. Keep the
rule here and nowhere else (docs/benchmark-design.md section 5).
"""

from __future__ import annotations

import re
from collections.abc import Mapping, Sequence
from typing import Any

# Words whose plural is not made by the regular rules. Deliberately short: extend it only when a
# benchmark prompt or a user needs a word, and add a test for it.
IRREGULAR_PLURALS: Mapping[str, str] = {
    "person": "people",
    "child": "children",
    "man": "men",
    "woman": "women",
}


def resource_name(entity_name: str) -> str:
    """Lowercase entity name, as used in permissions: `LineItem` -> `lineitem`."""
    return entity_name.lower()


def pluralize(word: str) -> str:
    """Plural of a lowercase word: `category` -> `categories`, `address` -> `addresses`."""
    if word in IRREGULAR_PLURALS:
        return IRREGULAR_PLURALS[word]
    for singular, plural in IRREGULAR_PLURALS.items():
        if word.endswith(singular) and len(word) > len(singular):
            return word[: -len(singular)] + plural
    if re.search(r"(s|x|z|ch|sh)$", word):
        return word + "es"
    if re.search(r"[^aeiou]y$", word):
        return word[:-1] + "ies"
    return word + "s"


def entity_path(entity_name: str) -> str:
    """URL segment under `/api/` for an entity: `Contact` -> `contacts`."""
    return pluralize(resource_name(entity_name))


def entity_table(blueprint: Mapping[str, Any]) -> list[dict[str, Any]]:
    """One row per blueprint entity: name, resource, path, fields, readOnly."""
    return [
        {
            "name": entity["name"],
            "resource": resource_name(entity["name"]),
            "path": entity_path(entity["name"]),
            "fields": list(entity["fields"]),
            "readOnly": bool(entity.get("readOnly", False)),
        }
        for entity in blueprint["frontend"]["entities"]
    ]


def find_path_collisions(entity_names: Sequence[str]) -> list[tuple[str, str]]:
    """Pairs of entities that would share a URL path or a resource name."""
    seen: dict[str, str] = {}
    collisions: list[tuple[str, str]] = []
    for name in entity_names:
        for key in (entity_path(name), resource_name(name)):
            if key in seen and seen[key] != name:
                collisions.append((seen[key], name))
            seen.setdefault(key, name)
    return collisions
