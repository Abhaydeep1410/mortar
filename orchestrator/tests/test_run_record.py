import json

import pytest

from evaluator.common import InvalidRun, RunRecord
from mortar import cli
from mortar.engine.run_record import reuse_rate_tokens, reused_tokens
from mortar.engine.tokens import APPROX_NAME, count_tokens
from mortar.pipeline import generate
from mortar.registry.loader import load_registry

from test_agents import JAVA, PROMPT, FakeClient, composer_files, planned_blueprint

SECTION6_KEYS = {
    "run_id", "prompt_id", "strategy", "registry_k", "seed", "model", "blueprint_sha256", "tokens", "cost_usd",
    "build_success", "first_pass_success", "repair_iterations", "oracle", "generation_time_s", "coverage",
    "reuse_rate_loc", "reuse_rate_tokens", "reused_loc", "generated_loc", "modules_selected", "uncovered",
    "reuse_violations", "errors",
}


@pytest.fixture(scope="module")
def registry():
    return load_registry()


def run(registry, tmp_path, k=2, **kwargs):
    client = FakeClient({"blueprint": planned_blueprint()}, composer_files(f"{JAVA}Contact.java"))
    return generate(PROMPT, tmp_path / "app", client, registry, k=k, prompt_id="crm-01", seed=2, **kwargs)


def test_run_json_is_the_section_6_record(registry, tmp_path):
    record = run(registry, tmp_path)
    saved = json.loads((tmp_path / "app" / "run.json").read_text())
    assert SECTION6_KEYS <= set(saved)
    assert saved == record["run_record"]
    assert (saved["run_id"], saved["prompt_id"], saved["strategy"], saved["registry_k"], saved["seed"]) == (
        "crm-01__ours-k2__s2", "crm-01", "ours", 2, 2)
    assert saved["model"] == "fake-model" and saved["blueprint_sha256"] == record["blueprint_sha256"]
    assert saved["modules_selected"] == ["crud.pagination", "authentication.jwt"]
    assert saved["first_pass_success"] is None and saved["oracle"] is None  # filled by the oracle harness
    assert saved["generation_time_s"] >= 0 and saved["valid"] is True
    assert (tmp_path / "app" / "run.detail.json").is_file()


def test_tokens_use_provider_usage_only_and_balance(registry, tmp_path):
    tokens = run(registry, tmp_path)["run_record"]["tokens"]
    # planner 100/50 + composer 100/50, no repair, no retrieval in this pipeline
    assert tokens == {"planning": 150, "retrieval": 0, "generation": 150, "repair": 0, "input_total": 200, "output_total": 100}
    assert sum(tokens[s] for s in ("planning", "retrieval", "generation", "repair")) == 300


def test_coverage_follows_k(registry, tmp_path):
    caps = len(planned_blueprint()["capabilities"])
    assert caps == 6
    assert run(registry, tmp_path / "a", k=2)["run_record"]["coverage"] == pytest.approx(2 / 6)
    assert run(registry, tmp_path / "b", k=0)["run_record"]["coverage"] == 0.0
    assert run(registry, tmp_path / "c", k=6)["run_record"]["coverage"] == 1.0


def test_reuse_rates_are_consistent_with_their_inputs(registry, tmp_path):
    rec = run(registry, tmp_path)["run_record"]
    wired = [registry[m] for m in rec["modules_selected"]]
    reused, tokenizer = reused_tokens(wired)
    assert rec["tokenizer"] == tokenizer
    assert rec["reuse_rate_tokens"] == pytest.approx(reused / (reused + 50))  # composer output tokens only
    assert rec["reuse_rate_loc"] == pytest.approx(rec["reused_loc"] / (rec["reused_loc"] + rec["generated_loc"]))
    assert 0 < rec["reuse_rate_tokens"] < 1


def test_k0_reuses_nothing(registry, tmp_path):
    rec = run(registry, tmp_path, k=0)["run_record"]
    assert rec["reuse_rate_tokens"] == 0.0 and rec["reuse_rate_loc"] == 0.0 and rec["modules_selected"] == []


def test_reuse_rate_tokens_edge_cases():
    assert reuse_rate_tokens(0, 0) == 0.0
    assert reuse_rate_tokens(75, 25) == 0.75


def test_count_tokens_names_the_counter():
    count, name = count_tokens("hello world")
    assert count > 0 and name.startswith(("tiktoken:", APPROX_NAME))


def test_run_id_defaults_and_explicit_override(registry, tmp_path):
    client = lambda: FakeClient({"blueprint": planned_blueprint()}, composer_files(f"{JAVA}Contact.java"))
    assert generate(PROMPT, tmp_path / "x", client(), registry, k=2)["run_record"]["run_id"] == "run"
    assert generate(PROMPT, tmp_path / "y", client(), registry, k=None, prompt_id="p", seed=3)["run_record"]["run_id"] == "p__ours-kall__s3"
    assert generate(PROMPT, tmp_path / "z", client(), registry, k=2, run_id="mine")["run_record"]["run_id"] == "mine"


def test_cli_passes_prompt_id_and_seed(tmp_path, monkeypatch, registry):
    seen = {}

    def fake_generate(*args, **kwargs):
        seen.update(kwargs)
        return {}

    monkeypatch.setattr(cli, "generate", fake_generate)
    monkeypatch.setattr(cli.LitellmClient, "from_env", classmethod(lambda cls: FakeClient()))
    assert cli.main(["generate", "--prompt", "p", "--out", str(tmp_path / "o"), "--prompt-id", "crm-01", "--seed", "3"]) == 0
    assert seen["prompt_id"] == "crm-01" and seen["seed"] == 3


def test_invariant_violation_is_an_invalid_run():
    record = RunRecord("r", "p", "ours", 2, 1, "m")
    record.tokens["planning"] = 5  # bucket without matching totals
    with pytest.raises(InvalidRun):
        record.check_invariants()
