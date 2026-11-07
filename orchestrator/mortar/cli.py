"""Mortar command-line interface.

`mortar validate` checks a blueprint; `mortar select` runs the deterministic Selector.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import yaml

from .engine.blueprint_validator import errors_only, load_blueprint, validate_blueprint
from .engine.coverage import modules_for_k
from .engine.selector import SelectionError, select
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


def _cmd_select(args: argparse.Namespace) -> int:
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
        selection = select(blueprint, registry, active)
    except (RegistryError, SelectionError, ValueError) as exc:
        print(f"selection failed: {exc}", file=sys.stderr)
        return 1
    print(yaml.safe_dump(selection.blueprint, sort_keys=False, allow_unicode=True))
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
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
