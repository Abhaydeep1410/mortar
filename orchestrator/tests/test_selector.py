import copy
import json
from pathlib import Path

import pytest
import yaml

from mortar import cli
from mortar.engine.blueprint_hash import blueprint_sha256
from mortar.engine.blueprint_validator import errors_only, load_blueprint, validate_blueprint
from mortar.engine.coverage import modules_for_k, partition_capabilities
from mortar.engine.naming import entity_path, find_path_collisions, pluralize
from mortar.engine.selector import SelectionError, _satisfies, select
from mortar.events import EventLog
from mortar.registry.loader import load_registry

FIXTURE = Path(__file__).parent / "fixtures" / "crm-01.blueprint.yaml"


@pytest.fixture(scope="module")
def registry():
    return load_registry()


@pytest.fixture()
def blueprint():
    return load_blueprint(FIXTURE)


@pytest.mark.parametrize(
    "word,plural",
    [("contact", "contacts"), ("category", "categories"), ("day", "days"), ("address", "addresses"),
     ("box", "boxes"), ("person", "people"), ("child", "children"), ("batch", "batches")],
)
def test_pluralize(word, plural):
    assert pluralize(word) == plural


def test_entity_path_lowercases():
    assert entity_path("LineItem") == "lineitems"


def test_path_collisions_detected():
    assert find_path_collisions(["Box", "Boxes"]) == [("Box", "Boxes")]
    assert find_path_collisions(["Contact", "Deal"]) == []


def test_registry_loads_all_six(registry):
    assert len(registry) == 6
    assert registry["authentication.jwt"].coordinates == "com.mortar:jwt-auth:1.0.0"


@pytest.mark.parametrize("k", [0, 2, 4, 6])
@pytest.mark.parametrize("order", ["primary", "alternate"])
def test_partition_matches_coverage(blueprint, registry, k, order):
    from mortar.engine.vocab import ABLATION_ORDER_ALTERNATE, ABLATION_ORDER_PRIMARY
    active, _ = modules_for_k(k, ABLATION_ORDER_ALTERNATE if order == "alternate" else ABLATION_ORDER_PRIMARY)
    sel = select(blueprint, registry, active)
    covered, uncovered = partition_capabilities(blueprint["capabilities"], active)
    assert sel.uncovered == uncovered
    assert {c["module_id"] for c in sel.components} == set(active)
    assert errors_only(validate_blueprint(sel.blueprint)) == []


def test_full_registry_configs(blueprint, registry):
    sel = select(blueprint, registry)
    configs = {c["module_id"]: c["config"] for c in sel.components}
    assert configs["authentication.jwt"]["issuer"] == "customer-crm"
    assert configs["authorization.rbac"]["resources"][0] == {"path": "contacts", "name": "contact"}
    assert configs["audit.logging"]["adminRole"] == "ADMIN"
    assert configs["deployment.docker"]["databaseName"] == "customer_crm"


def test_dependencies_come_first(blueprint, registry):
    ids = [c["module_id"] for c in select(blueprint, registry).components]
    assert ids.index("authentication.jwt") < ids.index("authorization.rbac")
    assert ids.index("authentication.jwt") < ids.index("audit.logging")


def test_deterministic(blueprint, registry):
    assert select(blueprint, registry).blueprint == select(copy.deepcopy(blueprint), registry).blueprint


def test_input_not_mutated(blueprint, registry):
    before = copy.deepcopy(blueprint)
    select(blueprint, registry)
    assert blueprint == before


def test_module_with_missing_dependency_skipped(blueprint, registry):
    sel = select(blueprint, registry, ["authorization.rbac", "crud.pagination"])
    assert [c["module_id"] for c in sel.components] == ["crud.pagination"]
    assert sel.skipped == (("authorization.rbac", "requires authentication.jwt"),)
    assert "authorization.rbac" in sel.uncovered


def test_conflicting_capabilities_rejected(blueprint, registry):
    blueprint["capabilities"] += ["authentication.oauth2"]
    with pytest.raises(SelectionError, match="conflicting"):
        select(blueprint, registry)


def test_audit_needs_admin_role(blueprint, registry):
    blueprint["roles"] = [{"name": "VIEWER", "permissions": ["contact:read"]}]
    with pytest.raises(SelectionError, match="'\\*'"):
        select(blueprint, registry)


def test_spring_boot_out_of_range_skips_module(blueprint, registry):
    blueprint["backend"]["springBootVersion"] = "5.0.x"
    sel = select(blueprint, registry)
    assert sel.components == []
    assert len(sel.skipped) == 6


def test_unknown_active_module(blueprint, registry):
    with pytest.raises(SelectionError, match="unknown"):
        select(blueprint, registry, ["nope"])


@pytest.mark.parametrize(
    "version,constraint,ok",
    [("4.1.x", ">=4.0.0 <5.0.0", True), ("5.0.0", ">=4.0.0 <5.0.0", False), ("21", ">=21", True), ("17", ">=21", False)],
)
def test_satisfies(version, constraint, ok):
    assert _satisfies(version, constraint) is ok


def test_hash_ignores_selector_output(blueprint, registry):
    assert blueprint_sha256(blueprint) == blueprint_sha256(select(blueprint, registry).blueprint)
    assert blueprint_sha256(blueprint) == blueprint_sha256(select(blueprint, registry, []).blueprint)


def test_hash_changes_with_content(blueprint):
    other = copy.deepcopy(blueprint)
    other["metadata"]["name"] = "other-crm"
    assert blueprint_sha256(blueprint) != blueprint_sha256(other)


def test_event_log_sequence_and_hash(tmp_path):
    ticks = iter(range(3))
    import datetime as dt
    clock = lambda: dt.datetime(2026, 1, 1, tzinfo=dt.timezone.utc) + dt.timedelta(seconds=next(ticks))
    log = EventLog("run1", tmp_path / "events.jsonl", "abc", clock)
    log.emit("select", modules=["a"])
    log.emit("write", path="x")
    lines = [json.loads(line) for line in (tmp_path / "events.jsonl").read_text().splitlines()]
    assert [e["seq"] for e in lines] == [1, 2]
    assert {e["blueprint_sha256"] for e in lines} == {"abc"}
    assert lines[1]["ts"] == "2026-01-01T00:00:01.000000Z"


def test_event_log_rejects_reserved_and_non_json(tmp_path):
    log = EventLog("r")
    with pytest.raises(ValueError):
        log.emit("x", seq=5)
    with pytest.raises(TypeError):
        log.emit("x", bad=object())
    assert log.events == []


def test_cli_select(capsys):
    assert cli.main(["select", str(FIXTURE), "--k", "2"]) == 0
    out = yaml.safe_load(capsys.readouterr().out)
    assert [c["module_id"] for c in out["components"]] == ["crud.pagination", "authentication.jwt"]
    assert "deployment.docker" in out["uncovered"]


def test_cli_select_invalid_k(capsys):
    assert cli.main(["select", str(FIXTURE), "--k", "9"]) == 1


def test_every_benchmark_entity_gets_a_distinct_sensible_path():
    import glob
    root = Path(__file__).resolve().parents[2] / "evaluator" / "benchmark" / "prompts"
    expected = {"Category": "categories", "Entry": "entries", "Stock": "stocks", "Owner": "owners"}
    for path in sorted(root.glob("*.yaml")):
        names = [e["name"] for e in yaml.safe_load(path.read_text())["entities"]]
        assert find_path_collisions(names) == []
        for name in names:
            if name in expected:
                assert entity_path(name) == expected[name]
