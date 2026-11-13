"""Plan -> select -> assemble -> compose -> (validate -> repair, at most twice).

Returns a measurement record per stage. Token counts and cost come only from model responses.
"""

from __future__ import annotations

import json
import time
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from .agents.composer import ComposeError, compose
from .agents.repairer import repair
from .agents.planner import plan
from .engine.assembler import assemble, write_generated
from .engine.blueprint_hash import blueprint_sha256
from .engine.coverage import coverage as coverage_of, modules_for_k
from .engine.run_record import build_run_record
from .engine.reuse import check_reuse, reuse_metrics
from .engine.selector import select
from .engine.vocab import ABLATION_ORDER_PRIMARY
from .events import EventLog
from .llm import LlmClient, Usage
from .validation.validate import ValidationResult
from collections.abc import Callable
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
    run_id: str | None = None,
    validator: Callable[[Path], ValidationResult] | None = None,
    max_repairs: int = 2,
    prompt_id: str = "",
    seed: int = 1,
    strategy: str = "ours",
) -> dict[str, Any]:
    started = time.monotonic()
    if run_id is None:
        run_id = f"{prompt_id}__{strategy}-k{'all' if k is None else k}__s{seed}" if prompt_id else "run"
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

    files = dict(composed.files)
    repair_usages: list[Usage] = []
    validation: ValidationResult | None = None
    first_pass_build: bool | None = None
    iterations = 0
    def check() -> ValidationResult:
        result = validator(out_dir)
        log.emit("validated", build_success=result.build_success, stages=[s.__dict__ for s in result.stages],
                 errors=[e.__dict__ for e in result.errors])
        return result

    if validator is not None:
        validation = check()
        first_pass_build = validation.build_success
        while not validation.build_success and iterations < max_repairs:
            iterations += 1
            try:
                fix = repair(selection.blueprint, client, list(validation.errors), files, set(assembled.files))
            except ComposeError as exc:
                if exc.usage is not None:
                    repair_usages.append(exc.usage)
                log.emit("repair_rejected", iteration=iterations, reason=str(exc))
                continue  # nothing changed on disk, so a rebuild would repeat the same failure
            else:
                repair_usages.append(fix.usage)
                files.update(fix.files)
                write_generated(out_dir, fix.files, log, source="repair")
                log.emit("repaired", iteration=iterations, files=sorted(fix.files), **_totals([fix.usage]))
            validation = check()

    wired = [registry[m] for m in selection.active]
    violations = check_reuse(files, wired)
    reuse = reuse_metrics(files, wired)
    log.emit("reuse_checked", violations=[v.__dict__ for v in violations], **reuse)

    every = (*planned.usages, composed.usage, *repair_usages)
    record = {
        "run_id": run_id,
        "model": client.model,
        "registry_k": k,
        "blueprint_sha256": blueprint_hash,
        "modules": list(selection.active),
        "uncovered": selection.uncovered,
        "tokens": {
            "planning": _totals(planned.usages),
            "generation": _totals([composed.usage]),
            "repair": _totals(repair_usages),
        },
        "cost_usd": sum(u.cost_usd for u in every),
        "files": {"assembled": len(assembled.files), "composed": len(composed.files), "final": len(files)},
        "reuse_violations": len(violations),
        "reuse_violation_details": [v.__dict__ for v in violations],
        "reuse": reuse,
        "validated": validator is not None,
        "build_success": None if validation is None else validation.build_success,
        "first_pass_build": first_pass_build,
        "repair_iterations": iterations,
        "validation_stages": [] if validation is None else [s.__dict__ for s in validation.stages],
        "errors": [] if validation is None else [e.__dict__ for e in validation.errors],
    }
    capabilities = list(planned.blueprint.get("capabilities", []))
    run_record = build_run_record(
        run_id=run_id, prompt_id=prompt_id, strategy=strategy, seed=seed, k=k, model=client.model,
        blueprint_sha256=blueprint_hash, planning=planned.usages, generation=[composed.usage], repair=repair_usages,
        wired=wired, uncovered=selection.uncovered,
        coverage=coverage_of(capabilities, selection.active) if capabilities else 0.0,
        reuse=reuse, violations=len(violations),
        build_success=record["build_success"], first_pass_build=first_pass_build, repair_iterations=iterations,
        generation_time_s=time.monotonic() - started, files_written=len(files),
        errors=[f"{e.kind}: {e.path or ''}: {e.message}" for e in (validation.errors if validation else [])],
    )
    run_record.write(out_dir)  # run.json: the benchmark-design section 6 record, same schema as Baseline A
    (out_dir / "run.detail.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    record["run_record"] = run_record.to_dict()
    return record
