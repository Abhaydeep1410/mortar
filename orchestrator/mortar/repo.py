"""Access to the repo-level `evaluator` package (shared contract, run record) from the orchestrator.

`evaluator/` sits next to `orchestrator/`, so it is only importable when the repo root is on `sys.path`.
pytest adds it (`pythonpath` in pyproject.toml); a plain `python -m mortar.cli ...` run from
`orchestrator/`, the `mortar` command, and CI do not. Import `evaluator` through here, lazily and never at
module top level, so commands that do not need it (`validate`, `select`, `assemble`) never depend on it.
A pip-installed `mortar` outside a checkout still cannot reach it; move the shared helpers into `mortar/`
before any packaging (see docs/PROGRESS.md, known gaps).
"""

from __future__ import annotations

import importlib
import sys
from pathlib import Path
from types import ModuleType

REPO_ROOT = Path(__file__).resolve().parents[2]


def evaluator_common() -> ModuleType:
    """The `evaluator.common` module, adding the repo root to `sys.path` if needed."""
    try:
        return importlib.import_module("evaluator.common")
    except ModuleNotFoundError as exc:
        if exc.name != "evaluator" or not (REPO_ROOT / "evaluator" / "common.py").is_file():
            raise
        if str(REPO_ROOT) not in sys.path:
            sys.path.append(str(REPO_ROOT))
        return importlib.import_module("evaluator.common")
