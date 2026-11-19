"""Regression: the CLI must work from `orchestrator/` in a fresh process, as in CI, without pytest's sys.path."""

import os
import subprocess
import sys
from pathlib import Path

ORCHESTRATOR = Path(__file__).resolve().parents[1]


def fresh(*args: str) -> subprocess.CompletedProcess:
    env = {k: v for k, v in os.environ.items() if k != "PYTHONPATH"}
    return subprocess.run([sys.executable, *args], cwd=ORCHESTRATOR, env=env, capture_output=True, text=True, timeout=120)


def test_cli_commands_run_without_the_repo_root_on_the_path():
    result = fresh("-m", "mortar.cli", "validate", "tests/fixtures/crm-01.blueprint.yaml")
    assert result.returncode == 0, result.stderr
    result = fresh("-m", "mortar.cli", "select", "tests/fixtures/crm-01.blueprint.yaml", "--k", "2")
    assert result.returncode == 0, result.stderr


def test_evaluator_is_found_lazily_when_needed():
    code = (
        "import sys\n"
        "assert not any(p.rstrip('/\\\\').endswith('mortar') and 'evaluator' in __import__('os').listdir(p) for p in sys.path if p), sys.path\n"
        "import mortar.pipeline\n"
        "assert 'evaluator' not in sys.modules, 'importing the pipeline must not import evaluator'\n"
        "from mortar.repo import evaluator_common\n"
        "assert evaluator_common().RunRecord and evaluator_common().load_contract()\n"
    )
    result = fresh("-c", code)
    assert result.returncode == 0, result.stderr
