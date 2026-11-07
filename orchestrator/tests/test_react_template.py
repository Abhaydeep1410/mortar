"""Static checks of templates/react-admin-v1 that need neither Node nor a browser.

The sample config is what the Assembler would write for the crm-01 benchmark prompt; it must satisfy
the template's schema and agree with that prompt. The frontend Dockerfile shipped by
deployment.docker depends on the template providing a lockfile, a build script and VITE_API_URL.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import jsonschema
import yaml

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = ROOT / "templates" / "react-admin-v1"
SCHEMA = json.loads((TEMPLATE / "config.schema.json").read_text(encoding="utf-8"))
SAMPLE = json.loads((TEMPLATE / "src" / "mortar.config.json").read_text(encoding="utf-8"))
PROMPT = yaml.safe_load((ROOT / "evaluator" / "benchmark" / "prompts" / "crm-01.yaml").read_text(encoding="utf-8"))
PACKAGE = json.loads((TEMPLATE / "package.json").read_text(encoding="utf-8"))


def test_schema_is_a_valid_json_schema():
    jsonschema.Draft202012Validator.check_schema(SCHEMA)


def test_sample_config_satisfies_the_schema():
    jsonschema.validate(SAMPLE, SCHEMA)


def test_sample_config_matches_the_crm_prompt():
    prompt_entities = {e["name"]: e["fields"] for e in PROMPT["entities"]}
    sample_entities = {
        e["name"]: [f if isinstance(f, str) else f["name"] for f in e["fields"]] for e in SAMPLE["entities"]
    }
    assert sample_entities == prompt_entities
    assert SAMPLE["roles"] == PROMPT["roles"]


def test_sample_paths_and_resources_follow_the_contract_naming():
    for entity in SAMPLE["entities"]:
        assert entity["resource"] == entity["name"].lower()
        assert entity["path"] == entity["resource"] + "s"


def test_sample_exports_only_contacts_like_the_prompt():
    exportable = [e["name"] for e in SAMPLE["entities"] if e.get("exportable")]
    assert exportable == ["Contact"]


def test_schema_rejects_bad_configs():
    bad_path = json.loads(json.dumps(SAMPLE))
    bad_path["entities"][0]["path"] = "Con/tacts"
    bad_permission = json.loads(json.dumps(SAMPLE))
    bad_permission["roles"][0]["permissions"] = ["contact:purge"]
    extra_key = json.loads(json.dumps(SAMPLE))
    extra_key["theme"] = "dark"
    for config in (bad_path, bad_permission, extra_key):
        validator = jsonschema.Draft202012Validator(SCHEMA)
        assert list(validator.iter_errors(config)), config


def test_stack_is_pinned_to_the_documented_versions():
    deps = PACKAGE["dependencies"]
    assert deps["react"].startswith("18.")
    assert deps["react-admin"].startswith("5.")
    for name, version in {**deps, **PACKAGE["devDependencies"]}.items():
        assert re.fullmatch(r"\d+\.\d+\.\d+", version), f"{name} is not pinned exactly: {version}"


def test_frontend_dockerfile_contract_is_met():
    lock = json.loads((TEMPLATE / "package-lock.json").read_text(encoding="utf-8"))
    assert lock["lockfileVersion"] >= 3
    assert "build" in PACKAGE["scripts"] and "test" in PACKAGE["scripts"]
    dockerfile = (
        ROOT / "registry" / "deployment.docker" / "module" / "src" / "main" / "resources" / "mortar" / "deployment" / "frontend" / "Dockerfile"
    ).read_text(encoding="utf-8")
    assert "npm run build" in dockerfile and "/build/dist" in dockerfile
    assert "VITE_API_URL" in (TEMPLATE / "src" / "config.ts").read_text(encoding="utf-8")
    assert (TEMPLATE / "index.html").exists()


def test_node_modules_and_dist_are_not_committed():
    ignore = (TEMPLATE / ".gitignore").read_text(encoding="utf-8").split()
    assert {"node_modules/", "dist/"} <= set(ignore)
