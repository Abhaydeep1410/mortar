"""Runs external commands. Real runs use subprocess; tests use a fake with scripted results."""

from __future__ import annotations

import subprocess
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol


@dataclass(frozen=True)
class CommandResult:
    returncode: int
    output: str  # stdout and stderr, interleaved
    timed_out: bool = False

    @property
    def ok(self) -> bool:
        return self.returncode == 0 and not self.timed_out


class CommandRunner(Protocol):
    def run(self, args: Sequence[str], cwd: Path, timeout_s: float, env: Mapping[str, str] | None = None) -> CommandResult: ...


class SubprocessRunner:
    def run(self, args: Sequence[str], cwd: Path, timeout_s: float, env: Mapping[str, str] | None = None) -> CommandResult:
        import os

        try:
            done = subprocess.run(
                list(args), cwd=cwd, env={**os.environ, **(env or {})}, capture_output=True, text=True,
                timeout=timeout_s, errors="replace",
            )
        except subprocess.TimeoutExpired as exc:
            partial = exc.stdout if isinstance(exc.stdout, str) else (exc.stdout or b"").decode("utf-8", "replace")
            return CommandResult(-1, partial, timed_out=True)
        except FileNotFoundError as exc:
            return CommandResult(127, f"command not found: {exc.filename}")
        return CommandResult(done.returncode, (done.stdout or "") + (done.stderr or ""))
