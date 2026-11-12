"""Plan -> select -> assemble -> compose. Build, test and repair come in Week 5.

Returns a measurement record per stage. Token counts and cost come only from model responses.
"""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from .agents.composer import compose
from .agents.planner import plan
from .engine.assembler import assemble, write_generated
from .engine.blueprint_hash import blueprint_sha256
from .engine.coverage import modules_for_k
from .engine.reuse import check_reuse, reuse_metrics
from .engine.selector import select
from .engine.vocab import ABLATION_ORDER_PRIMARY
from .events import EventLog
from .llm import LlmClient, Usage
from .registry.loader import ModuleInfo


def _totals(usages: Sequence[Usage]) -> dict[str, Any]:
    return {
        "input": sum(u.input_tokens for u in usages),
        "output": sum(u.output_tokens for u in usages),
        "cost_usd": sum(u.cost_usd for u in usages),
        "calls": len(usages),
    }


def generate(
    prompt: str,
    out_dir: Path,
    client: LlmClient,
    registry: Mapping[str, ModuleInfo],
    k: int | None = None,
    order: Sequence[str] = ABLATION_ORDER_PRIMARY,
    events_path: Path | None = None,
    run_id: str = "run",
) -> dict[str, Any]:
    log = EventLog(run_id, events_path)
    planned = plan(prompt, client)
    blueprint_hash = blueprint_sha256(planned.blueprint)
    log.blueprint_sha256 = blueprint_hash
    log.emit("planned", attempts=planned.attempts, **_totals(planned.usages))

    active = list(registry) if k is None else modules_for_k(k, order)[0]
    selection = select(planned.blueprint, registry, active)
    log.emit("selected", active=list(selection.active), uncovered=selection.uncovered,
             skipped=[list(s) for s in selection.skipped])

    assembled = assemble(selection.blueprint, registry, out_dir, log)
    composed = compose(selection.blueprint, registry, client, set(assembled.files))
    write_generated(out_dir, composed.files, log)
    log.emit("composed", files=len(composed.files), **_totals([composed.usage]))

    wired = [registry[m] for m in selection.active]
    violations = check_reuse(composed.files, wired)
    reuse = reuse_metrics(composed.files, wired)
    log.emit("reuse_checked", violations=[v.__dict__ for v in violations], **reuse)

    record = {
        "run_id": run_id,
        "model": client.model,
        "registry_k": k,
        "blueprint_sha256": blueprint_hash,
        "modules": list(selection.active),
        "uncovered": selection.uncovered,
        "tokens": {"planning": _totals(planned.usages), "generation": _totals([composed.usage])},
        "cost_usd": sum(u.cost_usd for u in (*planned.usages, composed.usage)),
        "files": {"assembled": len(assembled.files), "composed": len(composed.files)},
        "reuse_violations": len(violations),
        "reuse_violation_details": [v.__dict__ for v in violations],
        "reuse": reuse,
        "built": False,
    }
    (out_dir / "run.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    return record
