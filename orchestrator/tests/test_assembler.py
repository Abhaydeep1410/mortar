import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest
import yaml

from mortar.engine.assembler import (
    SPRING_BOOT_VERSION,
    AssemblyError,
    assemble,
    frontend_config,
    render,
)
from mortar.engine.blueprint_hash import blueprint_sha256
from mortar.engine.blueprint_validator import load_blueprint
from mortar.engine.coverage import modules_for_k
from mortar.engine.selector import select
from mortar.events import EventLog
from mortar.registry.loader import load_registry

FIXTURE = Path(__file__).parent / "fixtures" / "crm-01.blueprint.yaml"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


@pytest.fixture(scope="module")
def registry():
    return load_registry()


def build(registry, tmp_path, k, name="out"):
    blueprint = select(load_blueprint(FIXTURE), registry, modules_for_k(k)[0]).blueprint
    log = EventLog("run", None, blueprint_sha256(blueprint))
    result = assemble(blueprint, registry, tmp_path / name, log)
    return blueprint, result, log


def test_full_registry_project(registry, tmp_path):
    blueprint, result, log = build(registry, tmp_path, 6)
    for path in [
        "backend/pom.xml",
        "backend/vendor/pom.xml",
        "backend/vendor/jwt-auth/pom.xml",
        "backend/src/main/java/com/example/crm/Application.java",
        "backend/src/main/resources/application.yml",
        "backend/Dockerfile",
        "frontend/Dockerfile",
        "frontend/package-lock.json",
        "frontend/src/mortar.config.json",
        "docker-compose.yml",
        ".env.example",
        "blueprint.yaml",
    ]:
        assert (result.root / path).is_file(), path
    assert not (result.root / "frontend/node_modules").exists()
    assert "{{" not in (result.root / ".env.example").read_text()
    assert "POSTGRES_DB=customer_crm" in (result.root / ".env.example").read_text()


def test_poms_are_wellformed_and_consistent(registry, tmp_path):
    blueprint, result, _ = build(registry, tmp_path, 6)
    pom = ET.parse(result.root / "backend/pom.xml").getroot()
    artifacts = {d.find("m:artifactId", NS).text for d in pom.findall(".//m:dependency", NS)}
    wanted = {registry[c["module_id"]].artifact_id for c in blueprint["components"]}
    assert wanted <= artifacts
    vendor = ET.parse(result.root / "backend/vendor/pom.xml").getroot()
    assert {m.text for m in vendor.findall(".//m:module", NS)} == wanted
    for module in wanted:
        assert (result.root / "backend/vendor" / module / "pom.xml").is_file()
        assert (result.root / "backend/vendor" / module / "src/main").is_dir()
    assert pom.find("m:groupId", NS).text == "com.example"
    assert pom.find("m:artifactId", NS).text == "customer-crm"


def test_pinned_spring_boot_matches_every_module(registry):
    for info in registry.values():
        pom = ET.parse(info.module_dir / "pom.xml").getroot()
        assert pom.find(".//m:properties/m:spring-boot.version", NS).text == SPRING_BOOT_VERSION


def test_application_yml_carries_module_configs(registry, tmp_path):
    blueprint, result, _ = build(registry, tmp_path, 6)
    doc = yaml.safe_load((result.root / "backend/src/main/resources/application.yml").read_text())
    mortar = doc["mortar"]
    assert mortar["auth"]["jwt"]["issuer"] == "customer-crm"
    assert "secret" not in mortar["auth"]["jwt"]
    assert mortar["auth"]["rbac"]["resources"][0] == {"path": "contacts", "name": "contact"}
    assert mortar["audit"]["admin-role"] == "ADMIN"
    assert mortar["crud"]["max-page-size"] == 100
    assert mortar["export"]["csv"]["neutralize-formulas"] is True


def test_k0_has_no_modules_and_no_docker_files(registry, tmp_path):
    _, result, _ = build(registry, tmp_path, 0)
    assert not (result.root / "docker-compose.yml").exists()
    assert not (result.root / "backend/Dockerfile").exists()
    vendor = ET.parse(result.root / "backend/vendor/pom.xml").getroot()
    assert vendor.findall(".//m:module", NS) == []
    doc = yaml.safe_load((result.root / "backend/src/main/resources/application.yml").read_text())
    assert "mortar" not in doc
    cfg = json.loads((result.root / "frontend/src/mortar.config.json").read_text())
    assert all(e["searchable"] is False and e["exportable"] is False for e in cfg["entities"])


def test_k2_has_jwt_and_crud_only(registry, tmp_path):
    _, result, _ = build(registry, tmp_path, 2)
    doc = yaml.safe_load((result.root / "backend/src/main/resources/application.yml").read_text())
    assert set(doc["mortar"]) == {"auth", "crud"} and set(doc["mortar"]["auth"]) == {"jwt"}
    cfg = json.loads((result.root / "frontend/src/mortar.config.json").read_text())
    assert cfg["entities"][0]["searchable"] is True and cfg["entities"][0]["exportable"] is False


def test_frontend_config_matches_backend_paths(registry):
    blueprint = select(load_blueprint(FIXTURE), registry).blueprint
    cfg = frontend_config(blueprint)
    rbac = next(c for c in blueprint["components"] if c["module_id"] == "authorization.rbac")
    assert [e["path"] for e in cfg["entities"]] == [r["path"] for r in rbac["config"]["resources"]]
    assert cfg["kanban"] == [{"entity": "Deal", "groupBy": "stage"}]
    assert cfg["appName"] == "Customer Crm"


def test_deterministic_and_events_complete(registry, tmp_path):
    _, first, log = build(registry, tmp_path, 6, "a")
    _, second, _ = build(registry, tmp_path, 6, "b")
    assert first.files == second.files
    written = [e for e in log.events if e["kind"] == "file_written"]
    assert {e["path"]: e["sha256"] for e in written} == first.files
    assert log.events[-1] == {**log.events[-1], "kind": "assembled", "files": len(first.files)}
    assert len({e["blueprint_sha256"] for e in log.events}) == 1


def test_refuses_non_empty_output(registry, tmp_path):
    blueprint = select(load_blueprint(FIXTURE), registry).blueprint
    (tmp_path / "x").mkdir()
    (tmp_path / "x" / "f").write_text("1")
    with pytest.raises(AssemblyError, match="not empty"):
        assemble(blueprint, registry, tmp_path / "x")


def test_render_rejects_missing_placeholder():
    assert render("a {{b}}", {"b": 1}) == "a 1"
    with pytest.raises(AssemblyError):
        render("a {{c}}", {})


def test_backend_dockerfile_builds_vendored_modules_first(registry):
    text = (registry["deployment.docker"].path / "module/src/main/resources/mortar/deployment/backend/Dockerfile").read_text()
    assert text.index("vendor/pom.xml") < text.index("COPY src")
    assert re.search(r"-Dmaven\.test\.skip=true", text)


def test_cli_assemble(tmp_path, capsys):
    from mortar import cli
    out = tmp_path / "proj"
    assert cli.main(["assemble", str(FIXTURE), "--out", str(out), "--k", "4"]) == 0
    assert "uncovered for the Composer" in capsys.readouterr().out
    events = [json.loads(line) for line in (tmp_path / "proj.events.jsonl").read_text().splitlines()]
    assert events[0]["kind"] == "selected" and events[-1]["kind"] == "assembled"
    assert cli.main(["assemble", str(FIXTURE), "--out", str(out)]) == 1
