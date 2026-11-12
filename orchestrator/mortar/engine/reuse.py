"""Reuse accounting for one generated app: violations and lines of code.

Violations (`docs/module-spec.md` section 6) are checked on the Java the model wrote, against the
modules that were actually selected: an import of a library a module owns, a type that reuses one of
its public type names, or a second `SecurityFilterChain` bean when `authentication.jwt` is wired in.
LOC follows `docs/benchmark-design.md` section 6: non-blank, non-comment Java, XML and YAML lines.
"""

from __future__ import annotations

import re
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Any

from ..registry.loader import ModuleInfo

COUNTER_VERSION = "loc-1"
LOC_SUFFIXES = {".java", ".xml", ".yml", ".yaml"}
TYPE_DECLARATION = re.compile(r"\b(?:class|interface|enum|record|@interface)\s+([A-Za-z_][A-Za-z0-9_]*)")
IMPORT = re.compile(r"^\s*import\s+(?:static\s+)?([A-Za-z0-9_.*]+)\s*;", re.M)
FILTER_CHAIN_METHOD = re.compile(r"\bSecurityFilterChain\s+[A-Za-z_][A-Za-z0-9_]*\s*\(")


@dataclass(frozen=True)
class Violation:
    kind: str  # owned-import | redefined-type | second-filter-chain
    module_id: str
    path: str
    line: int
    detail: str


def strip_java(source: str, keep_strings: bool = False) -> str:
    """Java with comments removed (and string/char literals blanked unless kept); newlines preserved."""
    out: list[str] = []
    i, n = 0, len(source)
    while i < n:
        two = source[i : i + 2]
        ch = source[i]
        if two == "//":
            while i < n and source[i] != "\n":
                i += 1
        elif two == "/*":
            end = source.find("*/", i + 2)
            end = n if end == -1 else end + 2
            out.append("\n" * source.count("\n", i, end))
            i = end
        elif source.startswith('"""', i):
            end = source.find('"""', i + 3)
            end = n if end == -1 else end + 3
            block = source[i:end]
            out.append(block if keep_strings else '""' + "\n" * block.count("\n"))
            i = end
        elif ch in "\"'":
            j = i + 1
            while j < n and source[j] != ch and source[j] != "\n":
                j += 2 if source[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(source[i:j] if keep_strings else ch + ch)
            i = j
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def _line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def check_reuse(java_files: Mapping[str, str], selected: Iterable[ModuleInfo]) -> list[Violation]:
    """Violations in the model-written Java files, for the modules that are wired in."""
    modules = list(selected)
    owned_types = {
        interface.rsplit(".", 1)[-1]: module.id for module in modules for interface in module.provides_interfaces
    }
    jwt_selected = any(m.id == "authentication.jwt" for m in modules)
    found: list[Violation] = []
    for path, source in sorted(java_files.items()):
        if PurePosixPath(path).suffix != ".java":
            continue
        code = strip_java(source)
        for match in IMPORT.finditer(code):
            imported = match.group(1)
            for module in modules:
                for prefix in module.owns_imports:
                    if imported.startswith(prefix):
                        found.append(Violation("owned-import", module.id, path, _line_of(code, match.start(1)), imported))
        for match in TYPE_DECLARATION.finditer(code):
            name = match.group(1)
            if name in owned_types:
                found.append(Violation("redefined-type", owned_types[name], path, _line_of(code, match.start()), name))
        if jwt_selected:
            for match in FILTER_CHAIN_METHOD.finditer(code):
                found.append(
                    Violation("second-filter-chain", "authentication.jwt", path, _line_of(code, match.start()),
                              "SecurityFilterChain bean")
                )
    return found


def count_loc(path: str, text: str) -> int:
    """Non-blank, non-comment lines of a Java, XML or YAML file; 0 for other file types."""
    suffix = PurePosixPath(path).suffix
    if suffix == ".java":
        lines = strip_java(text, keep_strings=True).splitlines()
    elif suffix == ".xml":
        lines = re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S).splitlines()
    elif suffix in (".yml", ".yaml"):
        lines = [line for line in text.splitlines() if not line.lstrip().startswith("#")]
    else:
        return 0
    return sum(1 for line in lines if line.strip())


def module_loc(module: ModuleInfo) -> int:
    """LOC of what a module ships (`module/`, excluding build output), the same files the Assembler vendors."""
    total = 0
    for path in sorted(module.module_dir.rglob("*")):
        if path.is_file() and path.suffix in LOC_SUFFIXES and "target" not in path.relative_to(module.module_dir).parts:
            total += count_loc(path.name, path.read_text(encoding="utf-8"))
    return total


def reuse_metrics(
    generated_files: Mapping[str, str], selected: Sequence[ModuleInfo]
) -> dict[str, Any]:
    """reused_loc, generated_loc and reuse_rate_loc for one app (the model-written files only)."""
    reused = sum(module_loc(m) for m in selected)
    generated = sum(count_loc(path, text) for path, text in generated_files.items())
    total = reused + generated
    return {
        "counter_version": COUNTER_VERSION,
        "reused_loc": reused,
        "generated_loc": generated,
        "reuse_rate_loc": (reused / total) if total else 0.0,
    }
