"""Repair (the Composer again, with build errors): rewrite only the files the build blamed.

Same output tool and the same path rules as the Composer, so a repair can overwrite the Composer's
own files but never what the Assembler wrote.
"""

from __future__ import annotations

from collections.abc import Collection, Mapping
from typing import Any

from ..engine.naming import entity_table
from ..llm import LlmClient
from ..validation.errors import BuildError
from .composer import SYSTEM_PROMPT, WRITE_FILES, ComposeResult, allowed_prefixes, contract_text, parse_files

MAX_ERROR_CHARS = 6000
MAX_FILE_CHARS = 12000


def build_user_prompt(
    blueprint: Mapping[str, Any],
    errors: list[BuildError],
    written: Mapping[str, str],
    existing: Collection[str],
) -> str:
    digest = "\n".join(
        f"- [{e.kind}] {e.path or '-'}{':' + str(e.line) if e.line else ''} {e.message}" for e in errors
    )[:MAX_ERROR_CHARS]
    blamed = [p for p in dict.fromkeys(e.path for e in errors if e.path) if p in written]
    shown = "\n\n".join(f"File {p}:\n{written[p][:MAX_FILE_CHARS]}" for p in blamed) or "(no listed file is yours)"
    entities = "\n".join(f"- {e['name']}: /api/{e['path']}" for e in entity_table(blueprint))
    return "\n\n".join(
        [
            "The project you wrote did not build or start. Fix it. Write only the files that need to change "
            "(full file contents), under the same path rules as before.",
            "Build errors:\n" + digest,
            "Your files named in the errors:\n" + shown,
            "All files you wrote: " + ", ".join(sorted(written)),
            f"Files that exist and are not yours (never write these): {', '.join(sorted(existing))}",
            f"You may write only under `{allowed_prefixes(blueprint)[0]}` and `{allowed_prefixes(blueprint)[1]}`.",
            "Entities and URL paths:\n" + entities,
            contract_text(),
        ]
    )


def repair(
    blueprint: Mapping[str, Any],
    client: LlmClient,
    errors: list[BuildError],
    written: Mapping[str, str],
    existing: Collection[str],
) -> ComposeResult:
    result = client.call_tool(SYSTEM_PROMPT, build_user_prompt(blueprint, errors, written, existing), WRITE_FILES)
    return parse_files(result, blueprint, existing)
