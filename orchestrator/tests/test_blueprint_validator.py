from __future__ import annotations

import copy
from pathlib import Path

import pytest

from mortar.cli import main as cli_main
from mortar.engine.blueprint_validator import (
    errors_only,
    load_blueprint,
    validate_blueprint,
)

FIXTURE = Path(__file__).parent / "fixtures" / "crm-01.blueprint.yaml"


@pytest.fixture()
def bp():
    return load_blueprint(FIXTURE)


def codes(issues):
    return {i.code for i in issues}


def test_valid_blueprint_has_no_issues(bp):
    assert validate_blueprint(bp) == []


def test_load_blueprint_from_yaml_string():
    data = load_blueprint(FIXTURE.read_text(encoding="utf-8"))
    assert data["apiVersion"] == "mortar/v1"


def test_spring_boot_3_is_rejected_by_schema(bp):
    bp["backend"]["springBootVersion"] = "3.2.x"
    assert codes(validate_blueprint(bp)) == {"E_SCHEMA"}


def test_wrong_api_version_is_rejected(bp):
    bp["apiVersion"] = "composer/v1"
    issues = validate_blueprint(bp)
    assert codes(issues) == {"E_SCHEMA"}
    assert any(i.path == "apiVersion" for i in issues)


def test_whitespace_only_responsibility_rejected(bp):
    bp["custom_services"][0]["responsibility"] = "   "
    assert "E_SCHEMA" in codes(validate_blueprint(bp))


def test_empty_responsibility_rejected(bp):
    bp["custom_services"][0]["responsibility"] = ""
    assert "E_SCHEMA" in codes(validate_blueprint(bp))


def test_all_schema_errors_returned_together(bp):
    bp["backend"]["javaVersion"] = 17
    bp["database"]["type"] = "mysql"
    paths = {i.path for i in validate_blueprint(bp)}
    assert {"backend.javaVersion", "database.type"} <= paths


def test_unknown_capability(bp):
    bp["capabilities"].append("magic.wand")
    assert "E_CAPABILITY_UNKNOWN" in codes(validate_blueprint(bp))


def test_known_capability_without_module_is_valid(bp):
    bp["capabilities"].append("security.rate-limit")
    assert validate_blueprint(bp) == []


def test_capability_conflict(bp):
    bp["capabilities"].append("authentication.oauth2")
    assert "E_CAPABILITY_CONFLICT" in codes(validate_blueprint(bp))


def test_rbac_and_audit_need_authentication(bp):
    bp["capabilities"].remove("authentication.jwt")
    issues = validate_blueprint(bp)
    assert sum(i.code == "E_CAPABILITY_REQUIRES" for i in issues) == 2


def test_oauth2_satisfies_auth_requirement(bp):
    bp["capabilities"].remove("authentication.jwt")
    bp["capabilities"].append("authentication.oauth2")
    assert "E_CAPABILITY_REQUIRES" not in codes(validate_blueprint(bp))


def test_duplicate_entity(bp):
    bp["frontend"]["entities"].append(copy.deepcopy(bp["frontend"]["entities"][0]))
    assert "E_ENTITY_DUPLICATE" in codes(validate_blueprint(bp))


def test_page_references_unknown_entity(bp):
    bp["frontend"]["pages"].append({"type": "list", "entity": "Ghost"})
    assert "E_PAGE_ENTITY" in codes(validate_blueprint(bp))


def test_kanban_requires_groupby(bp):
    del bp["frontend"]["pages"][3]["groupBy"]
    assert "E_PAGE_GROUPBY" in codes(validate_blueprint(bp))


def test_kanban_groupby_must_be_field(bp):
    bp["frontend"]["pages"][3]["groupBy"] = "nonexistent"
    assert "E_PAGE_GROUPBY" in codes(validate_blueprint(bp))


def test_groupby_on_list_page_invalid(bp):
    bp["frontend"]["pages"][0]["groupBy"] = "name"
    assert "E_PAGE_GROUPBY" in codes(validate_blueprint(bp))


@pytest.mark.parametrize(
    "perm",
    ["contacts:read", "contact:fly", "contact", "ghost:read", "CONTACT:read", ":read"],
)
def test_bad_permission_format(bp, perm):
    bp["roles"][2]["permissions"].append(perm)
    assert "E_PERMISSION_FORMAT" in codes(validate_blueprint(bp))


def test_duplicate_role(bp):
    bp["roles"].append(copy.deepcopy(bp["roles"][2]))
    assert "E_ROLE_DUPLICATE" in codes(validate_blueprint(bp))


def test_duplicate_service_across_services_and_controllers(bp):
    bp["custom_controllers"] = [{"name": "NoteService", "responsibility": "x"}]
    # name pattern requires Controller suffix, so this is a schema error instead
    assert "E_SCHEMA" in codes(validate_blueprint(bp))
    bp["custom_controllers"] = []
    bp["custom_services"].append(copy.deepcopy(bp["custom_services"][0]))
    assert "E_SERVICE_DUPLICATE" in codes(validate_blueprint(bp))


def test_export_requires_an_export_permission(bp):
    bp["roles"] = [
        {"name": "ADMIN", "permissions": ["contact:read"]},
        {"name": "VIEWER", "permissions": ["deal:read"]},
    ]
    assert "E_EXPORT_NEEDS_ENTITY" in codes(validate_blueprint(bp))


def test_vacuous_rbac_is_only_a_warning(bp):
    bp["roles"] = [{"name": "ADMIN", "permissions": ["*"]}]
    issues = validate_blueprint(bp)
    assert codes(issues) == {"E_RBAC_NEEDS_ROLES"}
    assert errors_only(issues) == []


def _readonly_bp(bp):
    bp["frontend"]["entities"].append({"name": "Invoice", "fields": ["amount"], "readOnly": True})
    return bp


def test_readonly_entity_rejects_write_permission(bp):
    bp = _readonly_bp(bp)
    bp["roles"][1]["permissions"].append("invoice:write")
    assert "E_PERMISSION_READONLY" in codes(validate_blueprint(bp))


def test_readonly_entity_rejects_wildcard_action(bp):
    bp = _readonly_bp(bp)
    bp["roles"][1]["permissions"].append("invoice:*")
    assert "E_PERMISSION_READONLY" in codes(validate_blueprint(bp))


def test_global_star_rejected_when_readonly_entity_exists(bp):
    bp = _readonly_bp(bp)
    assert "E_PERMISSION_READONLY" in codes(validate_blueprint(bp))  # ADMIN has "*"


def test_readonly_entity_allows_read(bp):
    bp = _readonly_bp(bp)
    bp["roles"][0]["permissions"] = ["contact:*", "deal:*", "note:*", "invoice:read"]
    assert validate_blueprint(bp) == []


def test_cli_validate_ok_and_failure(tmp_path, capsys):
    assert cli_main(["validate", str(FIXTURE)]) == 0
    broken = tmp_path / "broken.yaml"
    broken.write_text(FIXTURE.read_text(encoding="utf-8").replace("4.1.x", "3.2.x"), encoding="utf-8")
    assert cli_main(["validate", str(broken)]) == 1
    assert cli_main(["validate", str(tmp_path / "missing.yaml")]) == 2
    out = capsys.readouterr()
    assert "E_SCHEMA" in out.out
