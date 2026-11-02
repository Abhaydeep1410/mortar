from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest
import yaml
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / "schemas" / "module-metadata.schema.json").read_text(encoding="utf-8"))
FIXTURE = Path(__file__).parent / "fixtures" / "authentication.jwt.metadata.yaml"


@pytest.fixture()
def meta():
    return yaml.safe_load(FIXTURE.read_text(encoding="utf-8"))


def errors(doc):
    return list(Draft202012Validator(SCHEMA).iter_errors(doc))


def test_schema_itself_is_valid():
    Draft202012Validator.check_schema(SCHEMA)


def test_jwt_metadata_from_spec_is_valid(meta):
    assert errors(meta) == []


@pytest.mark.parametrize(
    "mutate",
    [
        lambda m: m.update(version="1.0"),
        lambda m: m.update(category="payments"),
        lambda m: m.update(id="JWT"),
        lambda m: m.pop("spring_boot"),
        lambda m: m.update(provides=[]),
        lambda m: m["provides"].append({}),
        lambda m: m["provides"].append({"interface": "NoPackage"}),
        lambda m: m["requires"].append({"env": ["lowercase_var"]}),
        lambda m: m.update(unexpected_field=True),
    ],
)
def test_invalid_metadata_rejected(meta, mutate):
    broken = copy.deepcopy(meta)
    mutate(broken)
    assert errors(broken), "schema should reject this mutation"
