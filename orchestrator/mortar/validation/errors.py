"""Turns raw build output into structured errors for repair prompts and failure-mode counts."""

from __future__ import annotations

import re
from dataclasses import dataclass

MAX_ERRORS = 20
TAIL_LINES = 40

JAVAC = re.compile(r"\[ERROR\]\s+(?P<path>\S+\.java):\[(?P<line>\d+),(?P<col>\d+)\]\s+(?P<message>.*)")
DETAIL = re.compile(r"\[ERROR\]\s{3,}(?P<detail>\S.*)")


@dataclass(frozen=True)
class BuildError:
    kind: str  # java-compile | dependency-resolution | docker-build | startup-health | timeout | tool-missing | build-other
    message: str
    path: str | None = None  # project-relative
    line: int | None = None


def _relative(path: str) -> str:
    """Project-relative path: everything from the `backend/` directory on, else the file as given."""
    index = path.find("backend/")
    return path[index:] if index != -1 else path


def tail(output: str, lines: int = TAIL_LINES) -> str:
    return "\n".join(output.strip().splitlines()[-lines:])


def parse_maven(output: str) -> list[BuildError]:
    errors: list[BuildError] = []
    seen: set[tuple[str, int, str]] = set()
    rows = output.splitlines()
    for index, row in enumerate(rows):
        match = JAVAC.search(row)
        if not match:
            continue
        message = match["message"].strip()
        for follow in rows[index + 1 : index + 3]:
            detail = DETAIL.search(follow)
            if not detail:
                break
            message += " | " + detail["detail"].strip()
        key = (match["path"], int(match["line"]), message)
        if key not in seen:
            seen.add(key)
            errors.append(BuildError("java-compile", message, _relative(match["path"]), int(match["line"])))
    if errors:
        return errors[:MAX_ERRORS]
    if "Could not resolve dependencies" in output or "Could not transfer artifact" in output:
        return [BuildError("dependency-resolution", tail(output, 8))]
    return [BuildError("build-other", tail(output))]


def classify(stage: str, output: str, timed_out: bool = False, returncode: int = 1) -> list[BuildError]:
    """Errors for a failed stage: `maven`, `docker-build`, or `startup`."""
    if timed_out:
        return [BuildError("timeout", f"{stage} exceeded its time limit\n" + tail(output, 10))]
    if returncode == 127:
        return [BuildError("tool-missing", tail(output, 3))]
    if stage == "maven":
        return parse_maven(output)
    if stage == "docker-build":
        return [BuildError("docker-build", tail(output))]
    return [BuildError("startup-health", tail(output))]
