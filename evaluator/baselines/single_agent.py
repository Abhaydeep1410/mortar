#!/usr/bin/env python3
"""Baseline A: a single agent, one large prompt, generates the whole app.

Usage:
  python -m evaluator.baselines.single_agent crm-01 --out runs/crm-01__A__s1 --seed 1
  python -m evaluator.baselines.single_agent crm-01 --dry-run        # no API call

Environment (identical for every strategy in a benchmark):
  MORTAR_MODEL              model id understood by litellm (required unless --dry-run)
  MORTAR_TEMPERATURE        default 0.0
  MORTAR_MAX_OUTPUT_TOKENS  default 32000

The agent returns the whole repository in one tool call. Repair iterations are not
done here: the shared validation harness (Weeks 4-5) builds and tests the output and
feeds errors back through the same strategy. A response cut off by the output limit
is recorded as a failed generation, not retried.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from evaluator.common import (
    STACK_REQUIREMENTS,
    InvalidRun,
    RunRecord,
    UnsafePath,
    count_loc_in_files,
    load_contract,
    load_prompt,
    safe_relative_path,
    write_files,
)

SYSTEM_PROMPT = (
    "You are a senior full-stack engineer. You deliver complete, buildable repositories. "
    "You respond only by calling the write_repository tool exactly once with every file of the repository."
)

WRITE_REPOSITORY_TOOL = {
    "type": "function",
    "function": {
        "name": "write_repository",
        "description": "Write every file of the application repository.",
        "parameters": {
            "type": "object",
            "properties": {
                "files": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "path": {"type": "string", "description": "Relative path from the repository root"},
                            "content": {"type": "string"},
                        },
                        "required": ["path", "content"],
                    },
                }
            },
            "required": ["files"],
        },
    },
}


@dataclass(frozen=True)
class ModelConfig:
    model: str
    temperature: float = 0.0
    max_output_tokens: int = 32000

    @classmethod
    def from_env(cls, require_model: bool = True) -> "ModelConfig":
        model = os.environ.get("MORTAR_MODEL", "")
        if require_model and not model:
            raise SystemExit("MORTAR_MODEL is not set; export the pinned benchmark model id (or use --dry-run).")
        return cls(
            model=model or "<unset>",
            temperature=float(os.environ.get("MORTAR_TEMPERATURE", "0.0")),
            max_output_tokens=int(os.environ.get("MORTAR_MAX_OUTPUT_TOKENS", "32000")),
        )


def build_user_prompt(spec: dict[str, Any]) -> str:
    """The exact text Baseline A receives: the task, the stack, and the shared contract."""
    return "\n\n".join(
        [
            "Build this application.",
            spec["prompt"].strip(),
            STACK_REQUIREMENTS,
            load_contract(),
            "Deliver the complete repository (backend, frontend, Dockerfiles, docker-compose.yml, "
            "database migrations, seed data) by calling write_repository once.",
        ]
    )


def litellm_complete(config: ModelConfig, system: str, user: str) -> Any:
    """Real model call. Imported lazily so tests and dry runs need no litellm."""
    import litellm  # type: ignore

    return litellm.completion(
        model=config.model,
        messages=[{"role": "system", "content": system}, {"role": "user", "content": user}],
        tools=[WRITE_REPOSITORY_TOOL],
        tool_choice={"type": "function", "function": {"name": "write_repository"}},
        temperature=config.temperature,
        max_tokens=config.max_output_tokens,
    )


def default_cost(response: Any) -> float:
    import litellm  # type: ignore

    return float(litellm.completion_cost(completion_response=response))


def parse_files(response: Any) -> dict[str, str]:
    """Extract {path: content} from the forced tool call. Raises InvalidRun on malformed output."""
    choice = response.choices[0]
    if getattr(choice, "finish_reason", None) == "length":
        raise InvalidRun("output truncated by the max output token limit (finish_reason=length)")
    calls = getattr(choice.message, "tool_calls", None) or []
    if len(calls) != 1 or calls[0].function.name != "write_repository":
        raise InvalidRun(f"expected exactly one write_repository call, got {len(calls)}")
    try:
        payload = json.loads(calls[0].function.arguments)
        files = {f["path"]: f["content"] for f in payload["files"]}
    except (json.JSONDecodeError, KeyError, TypeError) as exc:
        raise InvalidRun(f"malformed write_repository arguments: {exc}") from exc
    if not files:
        raise InvalidRun("model returned no files")
    for path in files:
        safe_relative_path(path)  # raises UnsafePath (a ValueError) if unsafe
    return files


def generate(
    prompt_id: str,
    out_dir: Path,
    config: ModelConfig,
    seed: int = 1,
    complete: Callable[[ModelConfig, str, str], Any] = litellm_complete,
    cost_fn: Callable[[Any], float] = default_cost,
) -> RunRecord:
    """Run Baseline A on one prompt and write the app plus run.json under out_dir."""
    spec = load_prompt(prompt_id)
    record = RunRecord(
        run_id=f"{prompt_id}__A__s{seed}",
        prompt_id=prompt_id,
        strategy="single_agent",
        registry_k=None,
        seed=seed,
        model=config.model,
    )
    user_prompt = build_user_prompt(spec)
    started = time.monotonic()
    try:
        response = complete(config, SYSTEM_PROMPT, user_prompt)
        record.generation_time_s = round(time.monotonic() - started, 3)

        usage = getattr(response, "usage", None)
        if usage is None or getattr(usage, "prompt_tokens", None) is None or getattr(usage, "completion_tokens", None) is None:
            raise InvalidRun("provider returned no token usage; run cannot be used (no estimates allowed)")
        record.add_usage("generation", int(usage.prompt_tokens), int(usage.completion_tokens))

        try:
            record.cost_usd = cost_fn(response)
            record.cost_source = "litellm.completion_cost"
        except Exception as exc:  # cost missing makes the run invalid for cost analysis
            raise InvalidRun(f"cost unavailable: {exc}") from exc

        files = parse_files(response)
        record.files_written = len(write_files(out_dir / "app", files))
        record.generated_loc = count_loc_in_files(files)
    except (InvalidRun, UnsafePath) as exc:
        record.generation_time_s = record.generation_time_s or round(time.monotonic() - started, 3)
        record.mark_invalid(str(exc))
    record.write(out_dir)
    return record


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Baseline A: single-agent whole-app generation")
    parser.add_argument("prompt_id", help="e.g. crm-01")
    parser.add_argument("--out", type=Path, help="output directory (default: runs/<run_id>)")
    parser.add_argument("--seed", type=int, default=1, help="repetition index")
    parser.add_argument("--dry-run", action="store_true", help="print the assembled prompt and exit without calling a model")
    args = parser.parse_args(argv)

    spec = load_prompt(args.prompt_id)
    if args.dry_run:
        print("=== SYSTEM ===")
        print(SYSTEM_PROMPT)
        print("=== USER ===")
        print(build_user_prompt(spec))
        print(f"\n[dry run] user prompt length: {len(build_user_prompt(spec))} characters; no model was called.")
        return 0

    config = ModelConfig.from_env()
    out_dir = args.out or Path("runs") / f"{args.prompt_id}__A__s{args.seed}"
    record = generate(args.prompt_id, out_dir, config, seed=args.seed)
    status = "ok" if record.valid else "INVALID: " + "; ".join(record.errors)
    print(f"{record.run_id}: {status} | tokens in/out {record.tokens['input_total']}/{record.tokens['output_total']} "
          f"| files {record.files_written} | generated LOC {record.generated_loc} | cost {record.cost_usd}")
    return 0 if record.valid else 1


if __name__ == "__main__":
    sys.exit(main())
