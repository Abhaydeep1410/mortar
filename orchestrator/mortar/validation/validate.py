"""Validation stage: does the generated project build, and does the stack come up healthy?

Order (cheapest and most informative first): install the vendored modules, `mvn verify` (test
failures are tolerated, compile errors are not), `docker compose build`, then `docker compose up`
and poll `/actuator/health` until it reports UP or the deadline passes. The stack is always torn
down. Only the last stage needs Docker; the stages stop at the first failure.
"""

from __future__ import annotations

import json
import re
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

from ..events import EventLog
from .errors import BuildError, classify
from .runner import CommandResult, CommandRunner

HEALTH_URL = "http://localhost:8080/actuator/health"
HEALTH_DEADLINE_S = 120.0
HEALTH_INTERVAL_S = 2.0
VALIDATION_JWT_SECRET = "mortar-validation-only-secret-0123456789abcdef"

MAVEN = ("mvn", "-B", "-ntp")
STAGES: tuple[tuple[str, str, tuple[str, ...], float], ...] = (
    ("vendor-install", "maven", (*MAVEN, "-f", "backend/vendor/pom.xml", "-Dmaven.test.skip=true", "install"), 900),
    ("mvn-verify", "maven", (*MAVEN, "-f", "backend/pom.xml", "-Dmaven.test.failure.ignore=true", "verify"), 900),
    ("compose-build", "docker-build", ("docker", "compose", "build"), 1800),
)


@dataclass(frozen=True)
class StageResult:
    name: str
    ok: bool
    seconds: float


@dataclass(frozen=True)
class ValidationResult:
    build_success: bool
    stages: tuple[StageResult, ...]
    errors: tuple[BuildError, ...] = field(default_factory=tuple)


def http_health(url: str = HEALTH_URL) -> bool:
    """True when the endpoint answers 200 with `{"status": "UP"}`."""
    try:
        with urllib.request.urlopen(url, timeout=3) as response:  # noqa: S310 - fixed localhost URL
            return response.status == 200 and json.loads(response.read().decode("utf-8")).get("status") == "UP"
    except (urllib.error.URLError, OSError, ValueError):
        return False


def prepare_env(project_dir: Path) -> None:
    """Create `.env` from `.env.example` with a throwaway JWT secret (validation only)."""
    example, target = project_dir / ".env.example", project_dir / ".env"
    if not example.is_file() or target.exists():
        return
    text = example.read_text(encoding="utf-8")
    if re.search(r"^JWT_SECRET=.*$", text, flags=re.M):
        text = re.sub(r"^JWT_SECRET=.*$", f"JWT_SECRET={VALIDATION_JWT_SECRET}", text, flags=re.M)
    else:
        text += f"\nJWT_SECRET={VALIDATION_JWT_SECRET}\n"
    target.write_text(text, encoding="utf-8")


def validate_project(
    project_dir: Path,
    runner: CommandRunner,
    log: EventLog | None = None,
    health: Callable[[], bool] = http_health,
    clock: Callable[[], float] = time.monotonic,
    sleep: Callable[[float], None] = time.sleep,
    health_deadline_s: float = HEALTH_DEADLINE_S,
) -> ValidationResult:
    stages: list[StageResult] = []

    def record(name: str, ok: bool, started: float) -> None:
        stages.append(StageResult(name, ok, clock() - started))
        if log:
            log.emit("validation_stage", stage=name, ok=ok, seconds=round(clock() - started, 3))

    if not (project_dir / "docker-compose.yml").is_file():
        started = clock()
        record("compose-file", False, started)
        return ValidationResult(False, tuple(stages), (BuildError("docker-build", "no docker-compose.yml in the project"),))

    prepare_env(project_dir)
    for name, kind, args, timeout in STAGES:
        started = clock()
        result: CommandResult = runner.run(args, project_dir, timeout)
        record(name, result.ok, started)
        if not result.ok:
            return ValidationResult(
                False, tuple(stages), tuple(classify(kind, result.output, result.timed_out, result.returncode))
            )

    started = clock()
    try:
        up = runner.run(("docker", "compose", "up", "-d"), project_dir, 600)
        if not up.ok:
            record("compose-up", False, started)
            return ValidationResult(False, tuple(stages), tuple(classify("startup", up.output, up.timed_out, up.returncode)))
        deadline = clock() + health_deadline_s  # the clock starts once `up` has returned
        while True:
            if health():
                record("health", True, started)
                return ValidationResult(True, tuple(stages))
            if clock() >= deadline:
                break
            sleep(HEALTH_INTERVAL_S)
        logs = runner.run(("docker", "compose", "logs", "--no-color", "--tail", "80", "backend"), project_dir, 60)
        record("health", False, started)
        return ValidationResult(
            False, tuple(stages), tuple(classify("startup", logs.output or "health check never reported UP"))
        )
    finally:
        runner.run(("docker", "compose", "down", "-v"), project_dir, 300)
