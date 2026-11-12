import copy
import json
import xml.etree.ElementTree as ET
from pathlib import Path
from types import SimpleNamespace

import pytest

from mortar import cli
from mortar.agents.composer import ComposeError, build_user_prompt as composer_prompt, check_path, compose
from mortar.agents.planner import PlanError, plan
from mortar.engine.assembler import assemble
from mortar.engine.blueprint_validator import load_blueprint
from mortar.engine.selector import select
from mortar.llm import LitellmClient, LlmError, ToolResult, Usage
from mortar.pipeline import generate
from mortar.registry.loader import load_registry

FIXTURE = Path(__file__).parent / "fixtures" / "crm-01.blueprint.yaml"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PROMPT = "Build a CRM with contacts, deals, notes, and role-based access"


def planned_blueprint():
    bp = copy.deepcopy(load_blueprint(FIXTURE))
    bp["metadata"].pop("timestamp", None)
    bp["metadata"]["prompt"] = "model rewrote this"
    return bp


class FakeClient:
    model = "fake-model"

    def __init__(self, *responses):
        self.responses = list(responses)
        self.calls = []

    def call_tool(self, system, user, tool):
        self.calls.append((system, user, tool["function"]["name"]))
        return ToolResult(self.responses.pop(0), Usage(100, 50, 0.01))


JAVA = "backend/src/main/java/com/example/crm/"


def composer_files(*paths):
    return {"files": [{"path": p, "content": "class X {}"} for p in paths]}


def test_plan_normalizes_model_owned_fields():
    result = plan(PROMPT, FakeClient({"blueprint": planned_blueprint()}))
    assert result.blueprint["metadata"]["prompt"] == PROMPT
    assert result.blueprint["components"] == [] and result.blueprint["uncovered"] == []
    assert result.attempts == 1


def test_plan_repairs_with_the_validator_messages():
    bad = planned_blueprint()
    bad["capabilities"].append("not.a.capability")
    client = FakeClient({"blueprint": bad}, {"blueprint": planned_blueprint()})
    result = plan(PROMPT, client)
    assert result.attempts == 2 and len(result.usages) == 2
    assert "E_CAPABILITY_UNKNOWN" in client.calls[1][1]
    assert "Your previous blueprint was rejected" not in client.calls[0][1]


def test_plan_gives_up_after_max_attempts():
    with pytest.raises(PlanError):
        plan(PROMPT, FakeClient({}, {}, {}))


def test_planner_prompt_has_no_benchmark_criteria():
    client = FakeClient({"blueprint": planned_blueprint()})
    plan(PROMPT, client)
    assert "acceptance" not in client.calls[0][1].lower()


@pytest.fixture(scope="module")
def registry():
    return load_registry()


def selected(registry, k=None):
    from mortar.engine.coverage import modules_for_k
    active = list(registry) if k is None else modules_for_k(k)[0]
    return select(load_blueprint(FIXTURE), registry, active).blueprint


def test_composer_prompt_uses_skills_not_code(registry):
    text = composer_prompt(selected(registry), registry, {"backend/pom.xml"})
    assert "CrudController" in text and "Do NOT" in text
    assert "package com.mortar" not in text
    assert "/api/contacts" in text and "Application contract" in text
    assert "acceptance" not in text.lower()


def test_composer_prompt_lists_uncovered(registry):
    text = composer_prompt(selected(registry, 2), registry, set())
    assert "authorization.rbac" in text.split("NO module")[1].split("\n")[0]


@pytest.mark.parametrize(
    "path",
    ["/etc/passwd", "../x", f"{JAVA}../../x", "backend\\pom.xml", "backend/pom.xml", "frontend/src/App.tsx",
     "backend/src/main/resources/application.yml", "docker-compose.yml", ""],
)
def test_composer_rejects_bad_paths(registry, path):
    with pytest.raises(ComposeError):
        check_path(path, selected(registry), {"backend/src/main/resources/application.yml", "backend/pom.xml"})


def test_composer_allows_docker_files_only_when_uncovered(registry):
    assert check_path("docker-compose.yml", selected(registry, 2), set()) == "docker-compose.yml"
    assert check_path(f"{JAVA}a/B.java", selected(registry), set()).endswith("B.java")
    assert check_path("backend/src/main/resources/data.sql", selected(registry), set())


def test_compose_returns_files_and_usage(registry):
    client = FakeClient(composer_files(f"{JAVA}Contact.java", f"{JAVA}ContactController.java"))
    result = compose(selected(registry), registry, client, {"backend/pom.xml"})
    assert sorted(result.files) == [f"{JAVA}Contact.java", f"{JAVA}ContactController.java"]
    assert result.usage.input_tokens == 100


@pytest.mark.parametrize("response", [{}, {"files": []}, {"files": [{"path": "a"}]},
                                      composer_files(f"{JAVA}A.java", f"{JAVA}A.java")])
def test_compose_rejects_malformed_output(registry, response):
    with pytest.raises(ComposeError):
        compose(selected(registry), registry, FakeClient(response), set())


def test_uncovered_capabilities_add_their_libraries_to_the_pom(registry, tmp_path):
    assemble(selected(registry, 0), registry, tmp_path / "p")
    pom = ET.parse(tmp_path / "p/backend/pom.xml").getroot()
    artifacts = [d.find("m:artifactId", NS).text for d in pom.findall(".//m:dependency", NS)]
    assert "spring-boot-starter-security" in artifacts and "nimbus-jose-jwt" in artifacts
    assert artifacts.count("spring-boot-starter-security") == 1
    assert "spring-boot-starter-actuator" in artifacts and "spring-boot-starter-flyway" not in artifacts


def test_pipeline_end_to_end_with_a_fake_model(registry, tmp_path):
    client = FakeClient({"blueprint": planned_blueprint()}, composer_files(f"{JAVA}Contact.java", "docker-compose.yml"))
    out = tmp_path / "app"
    record = generate(PROMPT, out, client, registry, k=2, events_path=tmp_path / "ev.jsonl", run_id="r1")
    assert record["tokens"]["planning"]["input"] == 100 and record["tokens"]["generation"]["calls"] == 1
    assert record["cost_usd"] == pytest.approx(0.02)
    assert record["modules"] == ["crud.pagination", "authentication.jwt"]
    assert (out / f"{JAVA}Contact.java").is_file() and (out / "docker-compose.yml").is_file()
    assert json.loads((out / "run.json").read_text())["built"] is False
    events = [json.loads(line) for line in (tmp_path / "ev.jsonl").read_text().splitlines()]
    assert [e["seq"] for e in events] == list(range(1, len(events) + 1))
    assert events[0]["kind"] == "planned" and events[-1]["kind"] == "reuse_checked"
    assert len({e["blueprint_sha256"] for e in events}) == 1 and events[0]["blueprint_sha256"] == record["blueprint_sha256"]
    assert {e["path"] for e in events if e["kind"] == "file_written"} >= {f"{JAVA}Contact.java"}


def test_same_plan_same_hash_across_k(registry, tmp_path):
    hashes = set()
    for k in (0, 6):
        client = FakeClient({"blueprint": planned_blueprint()}, composer_files(f"{JAVA}A.java"))
        hashes.add(generate(PROMPT, tmp_path / f"k{k}", client, registry, k=k)["blueprint_sha256"])
    assert len(hashes) == 1


def fake_response(usage=True, finish="stop", args='{"files": []}', name="write_files"):
    message = SimpleNamespace(tool_calls=[SimpleNamespace(function=SimpleNamespace(name=name, arguments=args))])
    return SimpleNamespace(
        choices=[SimpleNamespace(finish_reason=finish, message=message)],
        usage=SimpleNamespace(prompt_tokens=7, completion_tokens=3) if usage else None,
    )


def litellm_client(response, cost=lambda r: 0.5):
    return LitellmClient("m", completion=lambda **kw: response, cost=cost)


TOOL = {"type": "function", "function": {"name": "write_files", "parameters": {}}}


def test_litellm_client_returns_usage():
    result = litellm_client(fake_response()).call_tool("s", "u", TOOL)
    assert result.usage == Usage(7, 3, 0.5) and result.arguments == {"files": []}


@pytest.mark.parametrize(
    "response",
    [fake_response(usage=False), fake_response(finish="length"), fake_response(args="{"),
     fake_response(args="[]"), fake_response(name="other")],
)
def test_litellm_client_never_imputes(response):
    with pytest.raises(LlmError):
        litellm_client(response).call_tool("s", "u", TOOL)


def test_litellm_client_fails_when_cost_unknown():
    def broken(_):
        raise KeyError("model not priced")
    with pytest.raises(LlmError, match="cost"):
        litellm_client(fake_response(), broken).call_tool("s", "u", TOOL)


def test_client_requires_a_model():
    with pytest.raises(LlmError):
        LitellmClient("")


def test_cli_generate_needs_a_prompt_and_a_model(tmp_path, monkeypatch, capsys):
    assert cli.main(["generate", "--out", str(tmp_path / "x")]) == 2
    monkeypatch.delenv("MORTAR_MODEL", raising=False)
    assert cli.main(["generate", "--prompt", "p", "--out", str(tmp_path / "x")]) == 1
    assert "MORTAR_MODEL" in capsys.readouterr().err


def test_pipeline_records_reuse_violations(registry, tmp_path):
    bad = {"files": [{"path": f"{JAVA}J.java", "content": "import com.nimbusds.jwt.SignedJWT;\nclass JwtService {}\n"}]}
    client = FakeClient({"blueprint": planned_blueprint()}, bad)
    record = generate(PROMPT, tmp_path / "v", client, registry, k=2)
    assert record["reuse_violations"] == 2
    assert {d["kind"] for d in record["reuse_violation_details"]} == {"owned-import", "redefined-type"}
    assert record["reuse"]["generated_loc"] == 2 and record["reuse"]["reused_loc"] > 0
    k0 = generate(PROMPT, tmp_path / "v0", FakeClient({"blueprint": planned_blueprint()}, bad), registry, k=0)
    assert k0["reuse_violations"] == 0 and k0["reuse"]["reused_loc"] == 0
