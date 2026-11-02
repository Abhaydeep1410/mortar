"""Benchmark prompt validity (docs/benchmark-design.md section 9)."""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from mortar.engine.blueprint_validator import errors_only, validate_blueprint
from mortar.engine.coverage import coverage, modules_for_k
from mortar.engine.vocab import CAPABILITIES, REGISTRY_V1

PROMPT_DIR = Path(__file__).resolve().parents[2] / "evaluator" / "benchmark" / "prompts"
PROMPT_FILES = sorted(PROMPT_DIR.glob("*.yaml"))
EXPECTED_IDS = {
    "crm-01", "expense-02", "inventory-03", "saas-admin-04",
    "ats-05", "asset-06", "vendor-07", "timesheet-08",
}
REQUIRED_KEYS = {
    "id", "title", "domain", "prompt", "entities", "capabilities",
    "roles", "seeded_users", "seed_data", "acceptance_criteria",
}


def load(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def name_forms(name: str) -> set[str]:
    """Singular and plural spellings an entity may take in prose (Category/Categories)."""
    plural = name[:-1] + "ies" if name.endswith("y") else name + "s"
    return {name, plural}


def blueprint_from_prompt(p: dict) -> dict:
    """Minimal blueprint implied by a prompt, to check the prompt against blueprint rules."""
    return {
        "apiVersion": "mortar/v1",
        "kind": "Application",
        "metadata": {"name": p["id"], "prompt": p["prompt"]},
        "application": {"type": "internal-tool", "domain": p["domain"]},
        "frontend": {
            "template": "react-admin-v1",
            "entities": p["entities"],
            "pages": [{"type": "list", "entity": e["name"]} for e in p["entities"]],
        },
        "backend": {"framework": "spring-boot", "javaVersion": 21, "springBootVersion": "4.1.x", "basePackage": "com.example.app"},
        "database": {"type": "postgresql", "migrations": "flyway"},
        "capabilities": p["capabilities"],
        "custom_services": [{"name": "PlaceholderService", "responsibility": "placeholder"}],
        "roles": p["roles"],
        "components": [],
    }


def test_exactly_the_eight_expected_prompts():
    assert {p.stem for p in PROMPT_FILES} == EXPECTED_IDS


@pytest.mark.parametrize("path", PROMPT_FILES, ids=lambda p: p.stem)
class TestPrompt:
    def test_shape_and_filename(self, path):
        p = load(path)
        assert REQUIRED_KEYS <= set(p)
        assert p["id"] == path.stem
        assert p["prompt"].strip()
        assert p["acceptance_criteria"]

    def test_criteria_ids_unique_and_prefixed(self, path):
        p = load(path)
        ids = [c["id"] for c in p["acceptance_criteria"]]
        assert len(ids) == len(set(ids))
        for n, cid in enumerate(ids, start=1):
            assert cid == f"{p['id']}.{n}", "criteria must be numbered consecutively from 1"
        for c in p["acceptance_criteria"]:
            assert c["type"] in {"api", "ui"}
            assert c["text"].strip()

    def test_every_prompt_has_login_criterion_and_a_ui_criterion(self, path):
        p = load(path)
        assert "401" in p["acceptance_criteria"][0]["text"]
        assert any(c["type"] == "ui" for c in p["acceptance_criteria"])

    def test_capabilities_in_vocabulary_and_use_enough_modules(self, path):
        p = load(path)
        assert set(p["capabilities"]) <= CAPABILITIES
        used = {m for m, provided in REGISTRY_V1.items() if provided & set(p["capabilities"])}
        assert len(used) >= 3

    def test_seeded_users_follow_contract(self, path):
        p = load(path)
        role_names = {r["name"] for r in p["roles"]}
        assert {u["role"] for u in p["seeded_users"]} == role_names
        for u in p["seeded_users"]:
            assert u["username"] == u["role"].lower()
            assert u["password"] == u["username"][0].upper() + u["username"][1:] + "#12345"
        assert any(u["username"] == "admin" and u["role"] == "ADMIN" for u in p["seeded_users"])

    def test_implied_blueprint_passes_validation(self, path):
        issues = validate_blueprint(blueprint_from_prompt(load(path)))
        assert errors_only(issues) == [], [str(i) for i in issues]

    def test_entity_names_and_fields_appear_in_prompt_text(self, path):
        p = load(path)
        for e in p["entities"]:
            assert any(form in p["prompt"] for form in name_forms(e["name"])), f"{e['name']} missing from prompt text"
            for f in e["fields"]:
                assert f in p["prompt"], f"field {f} of {e['name']} missing from prompt text"

    def test_audit_prompts_have_audit_criterion_and_vice_versa(self, path):
        p = load(path)
        has_audit_cap = "audit.logging" in p["capabilities"]
        has_audit_crit = any("audit record" in c["text"] for c in p["acceptance_criteria"])
        assert has_audit_cap == has_audit_crit

    def test_csv_prompts_have_csv_criterion_and_vice_versa(self, path):
        p = load(path)
        has_csv_cap = "export.csv" in p["capabilities"]
        has_csv_crit = any("CSV" in c["text"] for c in p["acceptance_criteria"])
        assert has_csv_cap == has_csv_crit

    def test_coverage_is_zero_at_k0_and_one_at_k6(self, path):
        caps = load(path)["capabilities"]
        assert coverage(caps, modules_for_k(0)[0]) == 0.0
        assert coverage(caps, modules_for_k(6)[0]) == 1.0
