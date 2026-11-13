"""Build the per-run record (`docs/benchmark-design.md` section 6) from what the pipeline measured.

Same shape and invariants as Baseline A's record (`evaluator.common.RunRecord`), so analysis reads one
schema. Fields that need the oracles (`first_pass_success`, `oracle`) stay None here; the oracle harness
fills them. Nothing is estimated: token counts and cost come from provider usage only.

reuse_rate_tokens = reused_tokens / (reused_tokens + generated_output_tokens), where
- reused_tokens is the tokenizer's count over the files of every wired-in module (the same files as
  reused_loc), and
- generated_output_tokens is the provider-reported output tokens of the Composer and Repairer calls
  (the model-written code). Planner output is the blueprint, not code, so it is excluded.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from typing import Any

from evaluator.common import RunRecord

from ..llm import Usage
from ..registry.loader import ModuleInfo
from .reuse import module_sources
from .tokens import count_tokens


def reused_tokens(modules: Sequence[ModuleInfo], counter: Callable[[str], tuple[int, str]] = count_tokens) -> tuple[int, str]:
    total, name = 0, counter("")[1]
    for module in modules:
        for _, text in module_sources(module):
            count, name = counter(text)
            total += count
    return total, name


def reuse_rate_tokens(reused: int, generated_output: int) -> float:
    total = reused + generated_output
    return reused / total if total else 0.0


def build_run_record(
    *,
    run_id: str,
    prompt_id: str,
    strategy: str,
    seed: int,
    k: int | None,
    model: str,
    blueprint_sha256: str,
    planning: Sequence[Usage],
    generation: Sequence[Usage],
    repair: Sequence[Usage],
    wired: Sequence[ModuleInfo],
    uncovered: Sequence[str],
    coverage: float,
    reuse: Mapping[str, Any],
    violations: int,
    build_success: bool | None,
    first_pass_build: bool | None,
    repair_iterations: int,
    generation_time_s: float,
    files_written: int,
    errors: Sequence[str],
    counter: Callable[[str], tuple[int, str]] = count_tokens,
) -> RunRecord:
    record = RunRecord(run_id=run_id, prompt_id=prompt_id, strategy=strategy, registry_k=k, seed=seed, model=model)
    for stage, usages in (("planning", planning), ("generation", generation), ("repair", repair)):
        for usage in usages:
            record.add_usage(stage, usage.input_tokens, usage.output_tokens)
    record.cost_usd = sum(u.cost_usd for u in (*planning, *generation, *repair))
    record.cost_source = "provider usage via litellm"
    record.generation_time_s = round(generation_time_s, 3)
    record.blueprint_sha256 = blueprint_sha256
    record.modules_selected = [m.id for m in wired]
    record.uncovered = list(uncovered)
    record.coverage = coverage
    record.reused_loc = reuse["reused_loc"]
    record.generated_loc = reuse["generated_loc"]
    record.reuse_rate_loc = reuse["reuse_rate_loc"]
    record.loc_counter = reuse["counter_version"]
    record.reuse_violations = violations
    record.files_written = files_written
    record.build_success = build_success
    record.first_pass_build = first_pass_build
    record.repair_iterations = repair_iterations
    record.errors = list(errors)
    reused, tokenizer = reused_tokens(wired, counter)
    generated_output = sum(u.output_tokens for u in (*generation, *repair))
    record.reuse_rate_tokens = reuse_rate_tokens(reused, generated_output)
    record.tokenizer = tokenizer
    record.check_invariants()
    return record
