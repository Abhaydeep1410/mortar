"""Shared pieces for all benchmark strategies.

- prompt loading and the application contract (single source: docs/benchmark-design.md)
- LOC counting (versioned, so reports can state the counter used)
- safe writing of model-generated files
- the per-run record, with invariants from docs/benchmark-design.md section 6
"""

from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any

import yaml

REPO_ROOT = Path(__file__).resolve().parents[1]
PROMPT_DIR = REPO_ROOT / "evaluator" / "benchmark" / "prompts"
DESIGN_DOC = REPO_ROOT / "docs" / "benchmark-design.md"

LOC_COUNTER_VERSION = "loc-v1"

STACK_REQUIREMENTS = """\
Target stack (mandatory):
- Backend: Java 21, Spring Boot 4.1.x, Maven, PostgreSQL 16 via Spring Data JPA, Flyway migrations.
- Frontend: React 18 + TypeScript + Vite + React Admin 5.x.
- Deployment: Dockerfile(s) and a docker-compose.yml at the repository root; `docker compose up` must start the database, the backend and the frontend.
- The repository must build with `mvn verify` (backend) and `npm run build` (frontend) without manual steps."""


# --------------------------------------------------------------------------- prompts


def load_prompt(prompt_id: str) -> dict[str, Any]:
    path = PROMPT_DIR / f"{prompt_id}.yaml"
    if not path.exists():
        raise FileNotFoundError(f"unknown prompt id {prompt_id!r}; expected a file at {path}")
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def load_contract(doc_path: Path = DESIGN_DOC) -> str:
    """Return the application contract text that is appended verbatim to every prompt.

    Extracted from the bullet lines of section 5 of benchmark-design.md so the doc
    stays the single source of truth.
    """
    text = doc_path.read_text(encoding="utf-8")
    match = re.search(r"^## 5\. Application contract.*?\n(.*?)^## 6\.", text, re.DOTALL | re.MULTILINE)
    if not match:
        raise ValueError("could not find section 5 (Application contract) in benchmark-design.md")
    lines = []
    current: list[str] | None = None
    for line in match.group(1).splitlines():
        if line.startswith("- "):
            current = [line]
            lines.append(current)
        elif current is not None and line.startswith("  ") and line.strip():
            current.append(line)
    bullets = ["\n".join(item) for item in lines]
    if len(bullets) < 8:
        raise ValueError(f"expected at least 8 contract bullets, found {len(bullets)}")
    return "Application contract (your application MUST satisfy all of this):\n" + "\n".join(bullets)


# --------------------------------------------------------------------------- LOC

_SOURCE_SUFFIXES = {".java", ".xml", ".yml", ".yaml", ".ts", ".tsx", ".js", ".jsx", ".css", ".sql", ".properties", ".html"}
_SOURCE_NAMES = {"Dockerfile"}
_COMMENT_PREFIXES = ("//", "/*", "*/", "*", "#", "<!--")


def is_source_file(path: str | Path) -> bool:
    p = Path(path)
    return p.suffix in _SOURCE_SUFFIXES or p.name in _SOURCE_NAMES or p.name.startswith("Dockerfile")


def count_loc(text: str) -> int:
    """Non-blank, non-comment-only lines (heuristic; counter id: LOC_COUNTER_VERSION)."""
    count = 0
    for raw in text.splitlines():
        line = raw.strip()
        if line and not line.startswith(_COMMENT_PREFIXES):
            count += 1
    return count


def count_loc_in_files(files: dict[str, str]) -> int:
    return sum(count_loc(content) for path, content in files.items() if is_source_file(path))


# --------------------------------------------------------------------------- writing files


class UnsafePath(ValueError):
    pass


def safe_relative_path(path: str) -> PurePosixPath:
    """Validate a model-supplied path: relative, no '..', no drive letters. Redundant slashes are normalised."""
    if not isinstance(path, str) or not path.strip():
        raise UnsafePath("empty path")
    p = PurePosixPath(path.replace("\\", "/"))
    if p.is_absolute() or re.match(r"^[A-Za-z]:", path) or ".." in p.parts:
        raise UnsafePath(f"unsafe path {path!r}")
    return p


def write_files(root: Path, files: dict[str, str]) -> list[str]:
    """Write files under root, returning the list of paths written. Raises UnsafePath."""
    root = root.resolve()
    written = []
    for rel, content in files.items():
        target = (root / safe_relative_path(rel)).resolve()
        if root not in target.parents:
            raise UnsafePath(f"path escapes output directory: {rel!r}")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        written.append(str(safe_relative_path(rel)))
    return written


# --------------------------------------------------------------------------- run record

STAGES = ("planning", "retrieval", "generation", "repair")


class InvalidRun(RuntimeError):
    """A run that cannot be used in the benchmark (e.g. missing usage data)."""


@dataclass
class RunRecord:
    run_id: str
    prompt_id: str
    strategy: str
    registry_k: int | None
    seed: int
    model: str
    tokens: dict[str, int] = field(default_factory=lambda: {s: 0 for s in STAGES} | {"input_total": 0, "output_total": 0})
    cost_usd: float | None = None
    cost_source: str | None = None
    generation_time_s: float | None = None
    generated_loc: int = 0
    reused_loc: int = 0
    files_written: int = 0
    modules_selected: list[str] = field(default_factory=list)
    uncovered: list[str] = field(default_factory=list)
    reuse_violations: int = 0
    repair_iterations: int = 0
    loc_counter: str = LOC_COUNTER_VERSION
    valid: bool = True
    errors: list[str] = field(default_factory=list)
    # Filled later by the shared validation harness (Weeks 4-5); None means "not measured yet".
    build_success: bool | None = None
    first_pass_success: bool | None = None
    oracle: dict[str, Any] | None = None

    def add_usage(self, stage: str, input_tokens: int, output_tokens: int) -> None:
        if stage not in STAGES:
            raise ValueError(f"unknown stage {stage!r}")
        if input_tokens < 0 or output_tokens < 0:
            raise InvalidRun("negative token counts")
        self.tokens[stage] += input_tokens + output_tokens
        self.tokens["input_total"] += input_tokens
        self.tokens["output_total"] += output_tokens

    def check_invariants(self) -> None:
        stage_sum = sum(self.tokens[s] for s in STAGES)
        if stage_sum != self.tokens["input_total"] + self.tokens["output_total"]:
            raise InvalidRun("stage token buckets do not sum to input_total + output_total")

    def mark_invalid(self, reason: str) -> None:
        self.valid = False
        self.errors.append(reason)

    def to_dict(self) -> dict[str, Any]:
        self.check_invariants()
        return asdict(self)

    def write(self, directory: Path) -> Path:
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / "run.json"
        path.write_text(json.dumps(self.to_dict(), indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return path
