"""Structural checks for every module under registry/ (docs/module-spec.md).

These run without Java. They keep metadata, SKILL.md, config schema, example config and the Java
source layout consistent with each other. They do NOT prove the Java compiles or passes; that is
the job of `mvn verify` (CI job "java").
"""

from __future__ import annotations

import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest
import yaml
from jsonschema import Draft202012Validator

from check_skill_budget import check_skill
from mortar.engine.vocab import CAPABILITIES, REGISTRY_V1

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "registry"
METADATA_SCHEMA = json.loads((ROOT / "schemas" / "module-metadata.schema.json").read_text(encoding="utf-8"))
MODULES = sorted(p for p in REGISTRY.iterdir() if p.is_dir())
EXPECTED_TOP_LEVEL = {"SKILL.md", "metadata.yaml", "config-schema.json", "module", "tests", "examples"}
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def load_metadata(module: Path) -> dict:
    return yaml.safe_load((module / "metadata.yaml").read_text(encoding="utf-8"))


def test_registry_is_not_empty():
    assert MODULES, "registry/ should contain at least one module from Week 2 onwards"


@pytest.mark.parametrize("module", MODULES, ids=lambda p: p.name)
class TestModule:
    def test_exact_top_level_layout(self, module):
        assert {p.name for p in module.iterdir()} == EXPECTED_TOP_LEVEL

    def test_directory_name_matches_id(self, module):
        assert load_metadata(module)["id"] == module.name

    def test_metadata_valid_against_schema(self, module):
        errors = list(Draft202012Validator(METADATA_SCHEMA).iter_errors(load_metadata(module)))
        assert errors == [], [e.message for e in errors]

    def test_module_is_in_the_v1_registry_and_provides_its_vocabulary_capabilities(self, module):
        meta = load_metadata(module)
        assert meta["id"] in REGISTRY_V1, "add the module to vocab.REGISTRY_V1 (and MODULE_REQUIRES if needed)"
        declared = {p["capability"] for p in meta["provides"] if "capability" in p}
        assert declared == set(REGISTRY_V1[meta["id"]]), "metadata capabilities must match vocab.REGISTRY_V1"
        assert declared <= CAPABILITIES

    def test_skill_md_passes_budget_and_format(self, module):
        report = check_skill(module / "SKILL.md", expected_id=module.name)
        assert report.ok, report.errors

    def test_skill_md_mentions_every_required_env_var_and_conflict(self, module):
        meta = load_metadata(module)
        skill = (module / "SKILL.md").read_text(encoding="utf-8")
        for requirement in meta["requires"]:
            for env in requirement.get("env", []):
                assert env in skill, f"SKILL.md must mention required env var {env}"
        for conflict in meta.get("conflicts_with", []):
            assert conflict in skill, f"SKILL.md must mention conflict {conflict}"

    def test_config_schema_is_valid_and_example_config_conforms(self, module):
        schema = json.loads((module / "config-schema.json").read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        example = yaml.safe_load((module / "examples" / "example-config.yaml").read_text(encoding="utf-8"))
        errors = list(Draft202012Validator(schema).iter_errors(example))
        assert errors == [], [e.message for e in errors]

    def test_config_schema_rejects_unknown_keys(self, module):
        schema = json.loads((module / "config-schema.json").read_text(encoding="utf-8"))
        assert list(Draft202012Validator(schema).iter_errors({"surprise": True}))

    def test_every_provided_interface_exists_as_java_source(self, module):
        for provided in load_metadata(module)["provides"]:
            fqn = provided.get("interface")
            if fqn:
                source = module / "module" / "src" / "main" / "java" / (fqn.replace(".", "/") + ".java")
                assert source.is_file(), f"{fqn} is declared in metadata.yaml but {source} does not exist"

    def test_pom_matches_the_target_stack(self, module):
        pom = ET.parse(module / "module" / "pom.xml").getroot()
        assert pom.findtext("m:groupId", namespaces=POM_NS) == "com.mortar"
        props = pom.find("m:properties", POM_NS)
        assert props.findtext("m:maven.compiler.release", namespaces=POM_NS) == "21"
        boot = props.findtext("m:spring-boot.version", namespaces=POM_NS)
        assert re.fullmatch(r"4\.\d+\.\d+", boot), f"Spring Boot must be on the 4.x line, got {boot}"
        assert pom.findtext("m:build/m:testSourceDirectory", namespaces=POM_NS).endswith("/../tests/java")

    def test_tests_exist_and_use_the_module_package(self, module):
        tests = list((module / "tests" / "java").rglob("*Test.java"))
        assert tests, "a module needs at least one *Test.java"
        for path in (module / "module" / "src").rglob("*.java"):
            assert "com.fasterxml.jackson.databind" not in path.read_text(encoding="utf-8"), \
                f"{path.name}: Boot 4 uses Jackson 3 (tools.jackson); do not import Jackson 2 databind"

    def test_autoconfiguration_imports_name_existing_classes(self, module):
        imports = module / "module" / "src" / "main" / "resources" / "META-INF" / "spring" / \
            "org.springframework.boot.autoconfigure.AutoConfiguration.imports"
        if not imports.exists():
            pytest.skip("module has no auto-configuration")
        for line in imports.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                source = module / "module" / "src" / "main" / "java" / (line.replace(".", "/") + ".java")
                assert source.is_file(), f"{line} is listed in AutoConfiguration.imports but has no source file"
