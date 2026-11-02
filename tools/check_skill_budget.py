#!/usr/bin/env python3
"""Check registry SKILL.md files against docs/module-spec.md section 3.

Usage: check_skill_budget.py PATH [PATH ...]
Each PATH is a SKILL.md file or a directory searched for SKILL.md files.
Exit status is 1 if any file fails.

Token counting uses tiktoken (cl100k_base) when installed, otherwise
ceil(characters / 4). The counter used is printed so results are reproducible.
"""

from __future__ import annotations

import math
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

import yaml

BODY_TOKEN_BUDGET = 400
REQUIRED_SECTIONS = ("Provides", "Requires", "Config", "Integration", "Conflicts")
NAME_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
FRONTMATTER_RE = re.compile(r"\A---\n(.*?)\n---\n(.*)\Z", re.DOTALL)


def count_tokens(text: str) -> tuple[int, str]:
    try:
        import tiktoken  # type: ignore

        return len(tiktoken.get_encoding("cl100k_base").encode(text)), "tiktoken:cl100k_base"
    except Exception:
        return math.ceil(len(text) / 4), "approx:chars/4"


@dataclass
class SkillReport:
    path: Path
    tokens: int = 0
    counter: str = ""
    errors: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.errors


def check_skill(path: Path, expected_id: str | None = None) -> SkillReport:
    report = SkillReport(path=path)
    text = path.read_text(encoding="utf-8")
    match = FRONTMATTER_RE.match(text)
    if not match:
        report.errors.append("missing YAML frontmatter (--- block at top of file)")
        return report
    try:
        meta = yaml.safe_load(match.group(1)) or {}
    except yaml.YAMLError as exc:
        report.errors.append(f"frontmatter is not valid YAML: {exc}")
        return report
    body = match.group(2)

    name = meta.get("name")
    description = meta.get("description")
    if not isinstance(name, str) or not name:
        report.errors.append("frontmatter needs a non-empty 'name'")
    else:
        if len(name) > 64 or not NAME_RE.match(name):
            report.errors.append(f"name {name!r} must be lowercase letters, digits and single hyphens, max 64 chars")
        if expected_id and name != expected_id.replace(".", "-"):
            report.errors.append(f"name {name!r} must equal module id with '.' replaced by '-' ({expected_id.replace('.', '-')!r})")
    if not isinstance(description, str) or not description.strip():
        report.errors.append("frontmatter needs a non-empty 'description'")
    else:
        if len(description) > 1024:
            report.errors.append("description exceeds 1024 characters")
        if "<" in description or ">" in description:
            report.errors.append("description must not contain angle brackets")

    headings = [h.strip() for h in re.findall(r"^##\s+(.+)$", body, re.MULTILINE)]
    positions = []
    for section in REQUIRED_SECTIONS:
        idx = next((i for i, h in enumerate(headings) if h.startswith(section)), None)
        if idx is None:
            report.errors.append(f"missing required section '## {section}'")
        else:
            positions.append(idx)
    if positions != sorted(positions):
        report.errors.append("required sections are out of order (expected " + ", ".join(REQUIRED_SECTIONS) + ")")

    if "Do NOT" not in body:
        report.errors.append("body needs an explicit 'Do NOT' line for anything the module owns")

    report.tokens, report.counter = count_tokens(body)
    if report.tokens > BODY_TOKEN_BUDGET:
        report.errors.append(f"body is {report.tokens} tokens ({report.counter}); budget is {BODY_TOKEN_BUDGET}")
    return report


def _find(paths: list[str]) -> list[Path]:
    found: list[Path] = []
    for raw in paths:
        p = Path(raw)
        found.extend(sorted(p.rglob("SKILL.md")) if p.is_dir() else [p])
    return found


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    files = _find(argv)
    if not files:
        print("no SKILL.md files found")
        return 2
    failed = 0
    for path in files:
        report = check_skill(path)
        status = "ok " if report.ok else "FAIL"
        print(f"{status} {path} ({report.tokens} tokens, {report.counter})")
        for err in report.errors:
            print(f"     - {err}")
        failed += 0 if report.ok else 1
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
