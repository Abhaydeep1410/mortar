"""Mortar command-line interface.

Week 1 ships `mortar validate`. `mortar generate` arrives with the pipeline (Week 4).
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .engine.blueprint_validator import errors_only, load_blueprint, validate_blueprint


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


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="mortar", description="LLMs plan. Mortar builds.")
    sub = parser.add_subparsers(dest="command", required=True)
    validate = sub.add_parser("validate", help="validate a blueprint YAML file")
    validate.add_argument("blueprint", help="path to blueprint.yaml")
    validate.set_defaults(func=_cmd_validate)
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
