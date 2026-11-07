"""Loads the module registry (registry/<id>/) into typed records.

The loader reads metadata, the config schema and the Maven coordinates; it never reads Java. The
Selector and Assembler work only from these records.
"""

from __future__ import annotations

import json
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_REGISTRY = REPO_ROOT / "registry"
METADATA_SCHEMA_PATH = REPO_ROOT / "schemas" / "module-metadata.schema.json"
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}


class RegistryError(ValueError):
    """A module under registry/ is missing a file or has invalid metadata."""


@dataclass(frozen=True, eq=False)
class ModuleInfo:
    id: str
    version: str
    display_name: str
    provides_capabilities: frozenset[str]
    provides_interfaces: tuple[str, ...]
    requires_capabilities: frozenset[str]
    requires_env: tuple[str, ...]
    conflicts_with: frozenset[str]
    spring_boot: str
    java: str
    config_schema: dict[str, Any] = field(repr=False)
    path: Path
    group_id: str
    artifact_id: str
    artifact_version: str

    @property
    def module_dir(self) -> Path:
        """The shipped code: pom.xml, src/."""
        return self.path / "module"

    @property
    def skill_path(self) -> Path:
        return self.path / "SKILL.md"

    @property
    def coordinates(self) -> str:
        return f"{self.group_id}:{self.artifact_id}:{self.artifact_version}"


def _maven_coordinates(pom: Path) -> tuple[str, str, str]:
    root = ET.parse(pom).getroot()

    def text(tag: str) -> str:
        node = root.find(f"m:{tag}", POM_NS)
        if node is None or not (node.text or "").strip():
            raise RegistryError(f"{pom}: missing <{tag}>")
        return node.text.strip()

    return text("groupId"), text("artifactId"), text("version")


def load_module(module_dir: Path) -> ModuleInfo:
    metadata_path = module_dir / "metadata.yaml"
    schema_path = module_dir / "config-schema.json"
    pom = module_dir / "module" / "pom.xml"
    for required in (metadata_path, schema_path, pom):
        if not required.is_file():
            raise RegistryError(f"{module_dir.name}: missing {required.relative_to(module_dir)}")

    metadata = yaml.safe_load(metadata_path.read_text(encoding="utf-8"))
    schema = json.loads(METADATA_SCHEMA_PATH.read_text(encoding="utf-8"))
    errors = sorted(Draft202012Validator(schema).iter_errors(metadata), key=lambda e: list(e.path))
    if errors:
        raise RegistryError(f"{module_dir.name}: invalid metadata: {errors[0].message}")
    if metadata["id"] != module_dir.name:
        raise RegistryError(f"{module_dir.name}: metadata id is {metadata['id']!r}")

    group_id, artifact_id, artifact_version = _maven_coordinates(pom)
    provides = metadata["provides"]
    requires = metadata["requires"]
    return ModuleInfo(
        id=metadata["id"],
        version=str(metadata["version"]),
        display_name=metadata["display_name"],
        provides_capabilities=frozenset(p["capability"] for p in provides if "capability" in p),
        provides_interfaces=tuple(p["interface"] for p in provides if "interface" in p),
        requires_capabilities=frozenset(r["capability"] for r in requires if "capability" in r),
        requires_env=tuple(name for r in requires for name in r.get("env", [])),
        conflicts_with=frozenset(metadata.get("conflicts_with", [])),
        spring_boot=metadata["spring_boot"],
        java=metadata["java"],
        config_schema=json.loads(schema_path.read_text(encoding="utf-8")),
        path=module_dir,
        group_id=group_id,
        artifact_id=artifact_id,
        artifact_version=artifact_version,
    )


def load_registry(root: Path | None = None) -> dict[str, ModuleInfo]:
    """All modules under `root` (default: the repository's registry/), keyed by module id."""
    root = root or DEFAULT_REGISTRY
    if not root.is_dir():
        raise RegistryError(f"registry directory not found: {root}")
    modules = [load_module(p) for p in sorted(root.iterdir()) if p.is_dir()]
    return {m.id: m for m in modules}
