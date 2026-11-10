"""Mortar command-line interface.

`mortar validate` checks a blueprint; `mortar select` runs the Selector; `mortar assemble` writes the fixed project files.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import yaml

from .engine.assembler import AssemblyError, assemble
from .engine.blueprint_hash import blueprint_sha256
from .engine.blueprint_validator import errors_only, load_blueprint, validate_blueprint
from .engine.coverage import modules_for_k
from .engine.selector import SelectionError, select
from .events import EventLog
from .llm import LitellmClient, LlmError
from .pipeline import generate
from .agents.composer import ComposeError
from .agents.planner import PlanError
from .engine.vocab import ABLATION_ORDER_ALTERNATE, ABLATION_ORDER_PRIMARY
from .registry.loader import RegistryError, load_registry


def _cmd_validate(args: argparse.Namespace) -> int:
    try:
        blueprint = load_blueprint(Path(args.blueprint))
    except (OSError, ValueError) as exc:
        print(f"cannot read blueprint: {exc}", file=sys.stderr)
        return 2
    issues = validate_blueprint(blueprint)
    for issue in issues:
        print(issue)
    failed = errors_only(issues)
    print(f"{len(failed)} error(s), {len(issues) - len(failed)} warning(s)")
    return 1 if failed else 0


def _select_from_args(args: argparse.Namespace):
    """Returns (selection, registry), or an int exit code after printing the reason."""
    try:
        blueprint = load_blueprint(Path(args.blueprint))
    except (OSError, ValueError) as exc:
        print(f"cannot read blueprint: {exc}", file=sys.stderr)
        return 2
    failed = errors_only(validate_blueprint(blueprint))
    if failed:
        for issue in failed:
            print(issue, file=sys.stderr)
        return 1
    order = ABLATION_ORDER_ALTERNATE if args.order == "alternate" else ABLATION_ORDER_PRIMARY
    try:
        registry = load_registry()
        active = list(registry) if args.k is None else modules_for_k(args.k, order)[0]
        return select(blueprint, registry, active), registry
    except (RegistryError, SelectionError, ValueError) as exc:
        print(f"selection failed: {exc}", file=sys.stderr)
        return 1


def _cmd_select(args: argparse.Namespace) -> int:
    picked = _select_from_args(args)
    if isinstance(picked, int):
        return picked
    print(yaml.safe_dump(picked[0].blueprint, sort_keys=False, allow_unicode=True))
    return 0


def _cmd_assemble(args: argparse.Namespace) -> int:
    picked = _select_from_args(args)
    if isinstance(picked, int):
        return picked
    selection, registry = picked
    out = Path(args.out)
    log = EventLog("cli", out.parent / f"{out.name}.events.jsonl", blueprint_sha256(selection.blueprint))
    log.emit("selected", active=list(selection.active), uncovered=selection.uncovered,
             skipped=[list(s) for s in selection.skipped])
    try:
        result = assemble(selection.blueprint, registry, out, log)
    except (AssemblyError, OSError) as exc:
        print(f"assembly failed: {exc}", file=sys.stderr)
        return 1
    print(f"wrote {len(result.files)} files to {out}; uncovered for the Composer: {selection.uncovered}")
    return 0


def _cmd_generate(args: argparse.Namespace) -> int:
    if bool(args.prompt) == bool(args.prompt_file):
        print("give exactly one of --prompt and --prompt-file", file=sys.stderr)
        return 2
    prompt = args.prompt or Path(args.prompt_file).read_text(encoding="utf-8")
    order = ABLATION_ORDER_ALTERNATE if args.order == "alternate" else ABLATION_ORDER_PRIMARY
    out = Path(args.out)
    try:
        record = generate(
            prompt, out, LitellmClient.from_env(), load_registry(), args.k, order,
            events_path=out.parent / f"{out.name}.events.jsonl",
        )
    except (LlmError, PlanError, ComposeError, SelectionError, AssemblyError, RegistryError, OSError) as exc:
        print(f"generation failed: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(record, indent=2))
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="mortar", description="LLMs plan. Mortar builds.")
    sub = parser.add_subparsers(dest="command", required=True)
    validate = sub.add_parser("validate", help="validate a blueprint YAML file")
    validate.add_argument("blueprint", help="path to blueprint.yaml")
    validate.set_defaults(func=_cmd_validate)
    pick = sub.add_parser("select", help="run the Selector and print the completed blueprint")
    pick.add_argument("blueprint", help="path to blueprint.yaml")
    pick.add_argument("--k", type=int, default=None, help="offer only the first k registry modules")
    pick.add_argument("--order", choices=["primary", "alternate"], default="primary")
    pick.set_defaults(func=_cmd_select)
    build = sub.add_parser("assemble", help="select modules and write the fixed project files")
    build.add_argument("blueprint", help="path to blueprint.yaml")
    build.add_argument("--out", required=True, help="output directory (must be empty or new)")
    build.add_argument("--k", type=int, default=None)
    build.add_argument("--order", choices=["primary", "alternate"], default="primary")
    build.set_defaults(func=_cmd_assemble)
    gen = sub.add_parser("generate", help="plan, select, assemble and compose (needs MORTAR_MODEL)")
    gen.add_argument("--prompt", help="the application request")
    gen.add_argument("--prompt-file", help="file holding the application request")
    gen.add_argument("--out", required=True, help="output directory (must be empty or new)")
    gen.add_argument("--k", type=int, default=None)
    gen.add_argument("--order", choices=["primary", "alternate"], default="primary")
    gen.set_defaults(func=_cmd_generate)
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
