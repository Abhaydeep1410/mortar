import json
from pathlib import Path

import pytest

from mortar.agents.composer import ComposeError
from mortar.agents.repairer import build_user_prompt as repair_prompt
from mortar.engine.blueprint_validator import load_blueprint
from mortar.llm import ToolResult, Usage
from mortar.pipeline import generate
from mortar.registry.loader import load_registry
from mortar.validation.errors import BuildError, classify, parse_maven
from mortar.validation.runner import CommandResult
from mortar.validation.validate import (
    VALIDATION_JWT_SECRET,
    ValidationResult,
    prepare_env,
    validate_project,
)

JAVAC = """\
[INFO] Compiling 3 source files
[ERROR] /work/app/backend/src/main/java/com/example/crm/ContactController.java:[12,8] cannot find symbol
[ERROR]   symbol:   class Foo
[ERROR]   location: class com.example.crm.ContactController
[ERROR] /work/app/backend/src/main/java/com/example/crm/ContactController.java:[12,8] cannot find symbol
[ERROR]   symbol:   class Foo
[ERROR]   location: class com.example.crm.ContactController
[ERROR] /work/app/backend/src/main/java/com/example/crm/Deal.java:[3,1] class, interface, enum, or record expected
[ERROR] Failed to execute goal compiler:compile
"""


def test_parse_maven_structured_deduped_and_relative():
    errors = parse_maven(JAVAC)
    assert [(e.path, e.line) for e in errors] == [
        ("backend/src/main/java/com/example/crm/ContactController.java", 12),
        ("backend/src/main/java/com/example/crm/Deal.java", 3),
    ]
    assert errors[0].kind == "java-compile" and "symbol:   class Foo" in errors[0].message


@pytest.mark.parametrize(
    "stage,output,kwargs,kind",
    [("maven", "[ERROR] Could not resolve dependencies for project x", {}, "dependency-resolution"),
     ("maven", "[ERROR] something odd", {}, "build-other"),
     ("maven", "x", {"timed_out": True}, "timeout"),
     ("docker-build", "failed to solve", {}, "docker-build"),
     ("startup", "boom", {}, "startup-health"),
     ("maven", "command not found: mvn", {"returncode": 127}, "tool-missing")],
)
def test_classify(stage, output, kwargs, kind):
    assert classify(stage, output, **kwargs)[0].kind == kind


def test_docker_build_error_keeps_only_the_tail():
    out = "\n".join(f"line {i}" for i in range(500))
    assert classify("docker-build", out)[0].message.splitlines()[-1] == "line 499"
    assert len(classify("docker-build", out)[0].message.splitlines()) == 40


class FakeRunner:
    """Scripted results keyed by the command's first distinguishing words; records every call."""

    def __init__(self, fail=None, outputs=None):
        self.fail = fail or {}
        self.outputs = outputs or {}
        self.calls = []

    def run(self, args, cwd, timeout_s, env=None):
        key = " ".join(a for a in args if not a.startswith("-"))
        self.calls.append(key)
        for marker, result in self.fail.items():
            if marker in " ".join(args):
                return result
        return CommandResult(0, self.outputs.get(key, ""))


def project(tmp_path, compose=True, env_example=True):
    if compose:
        (tmp_path / "docker-compose.yml").write_text("services: {}\n")
    if env_example:
        (tmp_path / ".env.example").write_text("POSTGRES_DB=x\nJWT_SECRET=\n")
    return tmp_path


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


def test_all_stages_pass_and_stack_is_torn_down(tmp_path):
    runner, clock = FakeRunner(), Clock()
    polls = iter([False, False, True])
    result = validate_project(project(tmp_path), runner, health=lambda: next(polls), clock=clock, sleep=clock.sleep)
    assert result.build_success and [s.name for s in result.stages] == [
        "vendor-install", "mvn-verify", "compose-build", "health"]
    assert runner.calls[0].startswith("mvn") and runner.calls[-1] == "docker compose down"
    assert "docker compose up" in runner.calls


def test_maven_failure_stops_before_docker(tmp_path):
    runner = FakeRunner(fail={"backend/pom.xml": CommandResult(1, JAVAC)})
    result = validate_project(project(tmp_path), runner)
    assert not result.build_success and result.errors[0].kind == "java-compile"
    assert not any(c.startswith("docker") for c in runner.calls)
    assert [s.name for s in result.stages] == ["vendor-install", "mvn-verify"]


def test_mvn_verify_tolerates_test_failures_but_vendor_install_skips_tests(tmp_path):
    seen = []

    class Spy(FakeRunner):
        def run(self, args, cwd, timeout_s, env=None):
            seen.append(list(args))
            return CommandResult(0, "")

    clock = Clock()
    validate_project(project(tmp_path), Spy(), health=lambda: True, clock=clock, sleep=clock.sleep)
    assert "-Dmaven.test.failure.ignore=true" in seen[1] and "-Dmaven.test.skip=true" in seen[0]


def test_compose_build_failure(tmp_path):
    runner = FakeRunner(fail={"compose build": CommandResult(1, "failed to solve: boom")})
    result = validate_project(project(tmp_path), runner)
    assert result.errors[0].kind == "docker-build" and "docker compose up" not in runner.calls


def test_health_never_up_collects_logs_and_tears_down(tmp_path):
    runner = FakeRunner(outputs={"docker compose logs 80 backend": "APPLICATION FAILED TO START"})
    clock = Clock()
    result = validate_project(project(tmp_path), runner, health=lambda: False, clock=clock, sleep=clock.sleep,
                              health_deadline_s=10)
    assert not result.build_success and result.errors[0].kind == "startup-health"
    assert "APPLICATION FAILED" in result.errors[0].message
    assert runner.calls[-1] == "docker compose down" and clock.now >= 10


def test_compose_up_failure_still_tears_down(tmp_path):
    runner = FakeRunner(fail={"up -d": CommandResult(1, "port is already allocated")})
    result = validate_project(project(tmp_path), runner)
    assert result.errors[0].kind == "startup-health" and runner.calls[-1] == "docker compose down"


def test_missing_compose_file_fails_without_running_anything(tmp_path):
    runner = FakeRunner()
    result = validate_project(project(tmp_path, compose=False), runner)
    assert not result.build_success and runner.calls == [] and result.errors[0].kind == "docker-build"


def test_timeout_is_classified(tmp_path):
    runner = FakeRunner(fail={"vendor/pom.xml": CommandResult(-1, "partial", timed_out=True)})
    assert validate_project(project(tmp_path), runner).errors[0].kind == "timeout"


def test_prepare_env_sets_secret_and_keeps_existing_env(tmp_path):
    project(tmp_path)
    prepare_env(tmp_path)
    assert f"JWT_SECRET={VALIDATION_JWT_SECRET}" in (tmp_path / ".env").read_text()
    (tmp_path / ".env").write_text("MINE=1\n")
    prepare_env(tmp_path)
    assert (tmp_path / ".env").read_text() == "MINE=1\n"


# ---- repair loop in the pipeline ----

FIXTURE = Path(__file__).parent / "fixtures" / "crm-01.blueprint.yaml"
JAVA = "backend/src/main/java/com/example/crm/"


class ScriptedClient:
    model = "fake"

    def __init__(self, *responses):
        self.responses = list(responses)
        self.prompts = []

    def call_tool(self, system, user, tool):
        self.prompts.append(user)
        return ToolResult(self.responses.pop(0), Usage(10, 5, 0.01))


def blueprint_response():
    import copy
    bp = copy.deepcopy(load_blueprint(FIXTURE))
    bp["metadata"].pop("timestamp", None)
    return {"blueprint": bp}


def files(**by_name):
    return {"files": [{"path": JAVA + n, "content": c} for n, c in by_name.items()]}


def validator_returning(*results):
    queue = list(results)
    calls = []

    def run(path):
        calls.append(path)
        return queue.pop(0)

    run.calls = calls
    return run


FAIL = ValidationResult(False, (), (BuildError("java-compile", "cannot find symbol", JAVA + "A.java", 3),))
OK = ValidationResult(True, ())


@pytest.fixture(scope="module")
def registry():
    return load_registry()


def test_first_pass_build_needs_no_repair(registry, tmp_path):
    client = ScriptedClient(blueprint_response(), files(**{"A.java": "class A {}"}))
    record = generate("p", tmp_path / "o", client, registry, k=6, validator=validator_returning(OK))
    assert record["first_pass_build"] is True and record["repair_iterations"] == 0
    assert record["tokens"]["repair"]["calls"] == 0 and record["build_success"] is True


def test_repair_overwrites_the_blamed_file_and_counts_cost(registry, tmp_path):
    client = ScriptedClient(blueprint_response(), files(**{"A.java": "class A { broken"}),
                            files(**{"A.java": "class A {}"}))
    validator = validator_returning(FAIL, OK)
    record = generate("p", tmp_path / "o", client, registry, k=6, validator=validator,
                      events_path=tmp_path / "ev.jsonl")
    assert (tmp_path / "o" / JAVA / "A.java").read_text() == "class A {}"
    assert record["first_pass_build"] is False and record["build_success"] is True
    assert record["repair_iterations"] == 1 and record["tokens"]["repair"] == {
        "input": 10, "output": 5, "cost_usd": pytest.approx(0.01), "calls": 1}
    assert record["cost_usd"] == pytest.approx(0.03)
    assert "class A { broken" in client.prompts[2] and "cannot find symbol" in client.prompts[2]
    kinds = [json.loads(line)["kind"] for line in (tmp_path / "ev.jsonl").read_text().splitlines()]
    assert kinds.count("validated") == 2 and "repaired" in kinds


def test_repair_is_bounded_and_failure_is_recorded(registry, tmp_path):
    client = ScriptedClient(blueprint_response(), files(**{"A.java": "x"}),
                            files(**{"A.java": "y"}), files(**{"A.java": "z"}))
    validator = validator_returning(FAIL, FAIL, FAIL)
    record = generate("p", tmp_path / "o", client, registry, k=6, validator=validator)
    assert record["repair_iterations"] == 2 and len(validator.calls) == 3 and client.responses == []
    assert record["build_success"] is False and record["errors"][0]["kind"] == "java-compile"


def test_rejected_repair_still_costs_an_iteration(registry, tmp_path):
    bad = {"files": [{"path": "backend/pom.xml", "content": "<project/>"}]}
    client = ScriptedClient(blueprint_response(), files(**{"A.java": "x"}), bad, bad)
    record = generate("p", tmp_path / "o", client, registry, k=6, validator=validator_returning(FAIL, FAIL, FAIL),
                      events_path=tmp_path / "ev.jsonl")
    assert record["repair_iterations"] == 2 and record["tokens"]["repair"]["calls"] == 2
    assert (tmp_path / "o" / "backend/pom.xml").read_text() != "<project/>"
    assert "repair_rejected" in (tmp_path / "ev.jsonl").read_text()


def test_repair_may_not_touch_assembler_files_or_reuse_check_sees_final_code(registry, tmp_path):
    fix = files(**{"A.java": "import com.nimbusds.jwt.SignedJWT;\nclass A {}\n"})
    client = ScriptedClient(blueprint_response(), files(**{"A.java": "x"}), fix)
    record = generate("p", tmp_path / "o", client, registry, k=6, validator=validator_returning(FAIL, OK))
    assert record["reuse_violations"] == 1


def test_repair_prompt_shows_only_blamed_composer_files():
    bp = load_blueprint(FIXTURE)
    errors = [BuildError("java-compile", "bad", JAVA + "A.java", 2), BuildError("java-compile", "bad", "backend/pom.xml", 9)]
    text = repair_prompt(bp, errors, {JAVA + "A.java": "class A {}", JAVA + "B.java": "class B {}"}, {"backend/pom.xml"})
    assert "File " + JAVA + "A.java" in text and "class B {}" not in text and "backend/pom.xml" in text


def test_compose_error_carries_usage():
    err = ComposeError("x", Usage(1, 2, 0.3))
    assert err.usage.cost_usd == 0.3 and ComposeError("y").usage is None


# ---- first-real-run hardening (Windows paths, executable lookup, health clock, rejected repair) ----

WINDOWS_JAVAC = (
    "[ERROR] C:\\Users\\Abhay Deep\\runs\\crm\\backend\\src\\main\\java\\com\\example\\crm\\Deal.java:[7,3] "
    "cannot find symbol\n[ERROR]   symbol:   class Foo\n"
)


def test_parse_maven_handles_windows_paths_with_spaces():
    (error,) = parse_maven(WINDOWS_JAVAC)
    assert error.path == "backend/src/main/java/com/example/crm/Deal.java" and error.line == 7


def test_subprocess_runner_resolves_the_executable_through_path(monkeypatch, tmp_path):
    import sys

    from mortar.validation import runner as runner_module

    monkeypatch.setattr(runner_module.shutil, "which", lambda name: sys.executable if name == "fake-mvn" else None)
    result = runner_module.SubprocessRunner().run(["fake-mvn", "-c", "print('hi')"], tmp_path, 30)
    assert result.ok and result.output.strip() == "hi"
    missing = runner_module.SubprocessRunner().run(["definitely-not-installed-xyz"], tmp_path, 30)
    assert missing.returncode == 127


def test_health_deadline_starts_after_compose_up_returns(tmp_path):
    clock = Clock()

    class SlowUp(FakeRunner):
        def run(self, args, cwd, timeout_s, env=None):
            if "up" in args:
                clock.now += 100  # a slow `docker compose up -d`
            return super().run(args, cwd, timeout_s, env)

    polls = iter([False, False, True])
    result = validate_project(project(tmp_path), SlowUp(), health=lambda: next(polls), clock=clock,
                              sleep=clock.sleep, health_deadline_s=10)
    assert result.build_success


def test_rejected_repair_is_not_revalidated(registry, tmp_path):
    bad = {"files": [{"path": "backend/pom.xml", "content": "<project/>"}]}
    client = ScriptedClient(blueprint_response(), files(**{"A.java": "x"}), bad, bad)
    validator = validator_returning(FAIL)
    record = generate("p", tmp_path / "o", client, registry, k=6, validator=validator)
    assert len(validator.calls) == 1 and record["repair_iterations"] == 2 and record["build_success"] is False
