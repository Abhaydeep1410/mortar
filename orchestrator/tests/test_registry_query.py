import pytest

from mortar.registry import query
from mortar.registry.loader import load_registry


@pytest.fixture(scope="module")
def registry():
    return load_registry()


def ids(results):
    return [r["module_id"] for r in results]


def test_auth_finds_both_authentication_modules_first(registry):
    assert ids(query.search_components(registry, "auth"))[:2] == ["authentication.jwt", "authorization.rbac"]


@pytest.mark.parametrize(
    "text,first",
    [("csv", "export.csv"), ("docker", "deployment.docker"), ("pagination", "crud.pagination"),
     ("audit", "audit.logging"), ("roles", "authorization.rbac"), ("jwt login", "authentication.jwt")],
)
def test_search_best_match(registry, text, first):
    assert ids(query.search_components(registry, text))[0] == first


def test_search_is_deterministic_and_limited(registry):
    assert query.search_components(registry, "") == query.search_components(registry, "")
    assert len(query.search_components(registry, "", limit=2)) == 2
    assert query.search_components(registry, "", limit=0) == []
    assert query.search_components(registry, "zzzzqqq") == []


def test_blank_query_lists_everything(registry):
    assert sorted(ids(query.search_components(registry, "  "))) == sorted(registry)


def test_summary_has_no_code(registry):
    result = query.search_components(registry, "csv")[0]
    assert set(result) == {"module_id", "version", "name", "description", "provides", "interfaces", "tags"}


def test_get_skill_returns_the_skill_file(registry):
    text = query.get_skill(registry, "crud.pagination")
    assert text.startswith("---") and "Do NOT" in text and "package com.mortar" not in text


def test_get_skill_unknown(registry):
    with pytest.raises(query.UnknownModule):
        query.get_skill(registry, "nope")


def test_dependencies_follow_providers(registry):
    deps = query.get_dependencies(registry, "audit.logging")
    assert deps["requires"] == [{"capability": "authentication.jwt", "provided_by": ["authentication.jwt"]}]
    assert deps["requires_modules"] == ["authentication.jwt"]
    assert deps["unmet_capabilities"] == [] and deps["maven"] == "com.mortar:audit-logging:1.0.0"


def test_dependencies_env_and_empty(registry):
    assert query.get_dependencies(registry, "authentication.jwt")["requires_env"] == ["JWT_SECRET"]
    assert query.get_dependencies(registry, "crud.pagination")["requires_modules"] == []


def test_dependencies_unknown(registry):
    with pytest.raises(query.UnknownModule):
        query.get_dependencies(registry, "nope")
