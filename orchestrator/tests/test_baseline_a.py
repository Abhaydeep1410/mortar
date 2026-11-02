"""Baseline A and shared evaluator helpers, tested with a fake model (no API key needed)."""

from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from evaluator.baselines import single_agent
from evaluator.baselines.single_agent import ModelConfig, build_user_prompt, generate, main
from evaluator.common import (
    InvalidRun,
    RunRecord,
    UnsafePath,
    count_loc,
    count_loc_in_files,
    load_contract,
    load_prompt,
    safe_relative_path,
    write_files,
)

CONFIG = ModelConfig(model="fake-model", temperature=0.0, max_output_tokens=1000)


def fake_response(files, prompt_tokens=1200, completion_tokens=3400, finish_reason="tool_calls", usage=True, n_calls=1, name="write_repository"):
    call = SimpleNamespace(function=SimpleNamespace(name=name, arguments=json.dumps({"files": files})))
    message = SimpleNamespace(tool_calls=[call] * n_calls)
    return SimpleNamespace(
        choices=[SimpleNamespace(message=message, finish_reason=finish_reason)],
        usage=SimpleNamespace(prompt_tokens=prompt_tokens, completion_tokens=completion_tokens) if usage else None,
    )


def run(tmp_path, response, cost=0.0123, cost_fn=None):
    return generate(
        "crm-01",
        tmp_path,
        CONFIG,
        seed=2,
        complete=lambda cfg, system, user: response,
        cost_fn=cost_fn or (lambda r: cost),
    )


FILES = [
    {"path": "backend/src/main/java/App.java", "content": "// header\npublic class App {\n\n  int x;\n}\n"},
    {"path": "docker-compose.yml", "content": "# compose\nservices:\n  db:\n    image: postgres:16\n"},
    {"path": "README.md", "content": "not source\nnot counted\n"},
]


def test_happy_path_writes_files_and_run_json(tmp_path):
    rec = run(tmp_path, fake_response(FILES))
    assert rec.valid and rec.errors == []
    assert rec.run_id == "crm-01__A__s2"
    assert rec.files_written == 3
    assert (tmp_path / "app" / "backend/src/main/java/App.java").exists()
    assert rec.tokens["generation"] == 1200 + 3400
    assert rec.tokens["input_total"] == 1200 and rec.tokens["output_total"] == 3400
    assert rec.cost_usd == pytest.approx(0.0123)
    # LOC: App.java = 3 non-blank non-comment lines; compose = 3 lines; README not source
    assert rec.generated_loc == 3 + 3
    saved = json.loads((tmp_path / "run.json").read_text())
    assert saved["strategy"] == "single_agent" and saved["registry_k"] is None
    assert saved["build_success"] is None, "build results belong to the validation harness"


def test_truncated_output_is_invalid_not_retried(tmp_path):
    rec = run(tmp_path, fake_response(FILES, finish_reason="length"))
    assert not rec.valid and "truncated" in rec.errors[0]
    assert rec.files_written == 0
    assert (tmp_path / "run.json").exists(), "invalid runs are still recorded"


def test_missing_usage_is_invalid_no_estimates(tmp_path):
    rec = run(tmp_path, fake_response(FILES, usage=False))
    assert not rec.valid and "no token usage" in rec.errors[0]
    assert rec.tokens["input_total"] == 0


def test_cost_unavailable_is_invalid(tmp_path):
    def boom(_):
        raise RuntimeError("model not in price table")

    rec = run(tmp_path, fake_response(FILES), cost_fn=boom)
    assert not rec.valid and "cost unavailable" in rec.errors[0]
    assert rec.tokens["generation"] == 4600, "tokens are still recorded for diagnosis"


@pytest.mark.parametrize("kwargs, needle", [({"n_calls": 2}, "exactly one"), ({"n_calls": 0}, "exactly one"), ({"name": "other_tool"}, "exactly one")])
def test_wrong_tool_calls_invalid(tmp_path, kwargs, needle):
    rec = run(tmp_path, fake_response(FILES, **kwargs))
    assert not rec.valid and needle in rec.errors[0]


def test_empty_file_list_invalid(tmp_path):
    rec = run(tmp_path, fake_response([]))
    assert not rec.valid and "no files" in rec.errors[0]


@pytest.mark.parametrize("bad", ["../escape.txt", "/etc/passwd", "a/../../b", "C:/x", "", "..\\win.txt"])
def test_unsafe_paths_rejected_and_nothing_written(tmp_path, bad):
    rec = run(tmp_path, fake_response([{"path": "ok.txt", "content": "x"}, {"path": bad, "content": "y"}]))
    assert not rec.valid
    assert not (tmp_path / "app" / "ok.txt").exists(), "validation happens before any file is written"
    assert not (tmp_path.parent / "escape.txt").exists()


def test_malformed_arguments_invalid(tmp_path):
    resp = fake_response(FILES)
    resp.choices[0].message.tool_calls[0].function.arguments = "{not json"
    rec = run(tmp_path, resp)
    assert not rec.valid and "malformed" in rec.errors[0]


def test_user_prompt_contains_task_stack_and_contract():
    spec = load_prompt("crm-01")
    text = build_user_prompt(spec)
    assert spec["prompt"].strip() in text
    assert "Spring Boot 4.1.x" in text
    assert "POST /api/auth/login" in text
    assert "Admin#12345" in text
    assert "acceptance" not in text.lower(), "oracle criteria must never reach the model"
    for criterion in spec["acceptance_criteria"]:
        assert criterion["text"] not in text


def test_contract_is_extracted_from_design_doc_and_identical_for_every_prompt():
    contract = load_contract()
    assert contract.count("\n- ") >= 8
    for pid in ("crm-01", "vendor-07"):
        assert contract in build_user_prompt(load_prompt(pid))


def test_dry_run_makes_no_model_call(capsys, monkeypatch):
    monkeypatch.setattr(single_agent, "litellm_complete", lambda *a, **k: pytest.fail("model was called"))
    assert main(["crm-01", "--dry-run"]) == 0
    out = capsys.readouterr().out
    assert "=== USER ===" in out and "no model was called" in out


def test_main_requires_model_env(monkeypatch):
    monkeypatch.delenv("MORTAR_MODEL", raising=False)
    with pytest.raises(SystemExit) as exc:
        main(["crm-01"])
    assert "MORTAR_MODEL" in str(exc.value)


def test_unknown_prompt_id():
    with pytest.raises(FileNotFoundError):
        load_prompt("nope-99")


# ------------------------------------------------------------------ shared helpers


def test_count_loc():
    assert count_loc("a\n\n// c\n/* x */\n * y\n# z\n<!-- h -->\nb\n") == 2
    assert count_loc_in_files({"A.java": "x\ny\n", "notes.md": "ignored\n", "Dockerfile": "FROM x\n"}) == 3


def test_safe_relative_path_and_write_files(tmp_path):
    assert str(safe_relative_path("a/b.txt")) == "a/b.txt"
    with pytest.raises(UnsafePath):
        safe_relative_path("../x")
    write_files(tmp_path, {"x/y.txt": "hi"})
    assert (tmp_path / "x" / "y.txt").read_text() == "hi"


def test_record_invariants():
    rec = RunRecord("r", "crm-01", "single_agent", None, 1, "m")
    rec.add_usage("planning", 10, 5)
    rec.add_usage("generation", 100, 50)
    rec.check_invariants()
    assert rec.tokens["input_total"] == 110 and rec.tokens["output_total"] == 55
    rec.tokens["generation"] += 1
    with pytest.raises(InvalidRun):
        rec.check_invariants()
    with pytest.raises(ValueError):
        rec.add_usage("bogus", 1, 1)
    with pytest.raises(InvalidRun):
        RunRecord("r", "p", "s", None, 1, "m").add_usage("repair", -1, 0)
