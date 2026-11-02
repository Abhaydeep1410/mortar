# Mortar: agent entry point

Read this file first. Then read **only** `docs/PROGRESS.md`. Open anything else only when the task needs it.
Do not read `PROJECT_CONTEXT.md` end to end (about 550 lines); use the section index below.

**What it is:** a deterministic composition engine with LLM-guided planning. An LLM plans a YAML blueprint, deterministic code wires tested Spring Boot modules from a registry, an LLM writes only the app-specific remainder. A benchmark measures when this beats regeneration. *LLMs plan. Mortar builds.*

## Where things are

| Need | Read |
|---|---|
| Current status, next task, open questions | `docs/PROGRESS.md` |
| Why a choice was made | `docs/DECISIONS.md` |
| Scope, non-goals, 9-week plan, definition of done | `PROJECT_CONTEXT.md` §3-5, §16-17 |
| Blueprint format and validation error codes | `docs/blueprint-spec.md` |
| Module layout, metadata, SKILL.md rules | `docs/module-spec.md` |
| Benchmark, metrics, stats, app contract | `docs/benchmark-design.md` |
| Capability vocabulary, ablation orders | `orchestrator/mortar/engine/vocab.py` |

Code: `orchestrator/mortar/` (Python engine + CLI), `evaluator/` (baselines, run record), `registry/` (Java modules, Week 2+), `tools/`, `schemas/`.

## Commands

```bash
cd orchestrator && python -m pytest -q          # full suite; must stay green
python -m mortar.cli validate <blueprint.yaml>  # from orchestrator/
python tools/check_skill_budget.py docs/examples/SKILL.example.md registry
python -m evaluator.baselines.single_agent crm-01 --dry-run   # from repo root
```

## Rules that must not be broken

1. **No estimated metrics.** A run missing token usage or cost is marked invalid and re-run, never imputed.
2. **Registry modules and benchmark oracles are hand-written.** Never AI-generated.
3. **Prompts and oracles freeze before any "Ours" run.** After the freeze, change nothing without logging it in `docs/results.md`.
4. **Oracle criteria never reach a model prompt.** Tests enforce this for Baseline A.
5. Target stack is fixed: Java 21, Spring Boot 4.1.x, PostgreSQL 16, React Admin 5.x. Verify versions before Week 2 module work.
6. **Stay inside the non-goals** (`PROJECT_CONTEXT.md` §5). Ideas outside them go in `docs/PROGRESS.md` under "Parked", not into code.
7. Claims need sample size and CI. Never write a percentage without n.

## Working conventions

- Commits: `type(scope): summary` (feat, fix, docs, test, chore) with a body explaining why. One logical change per commit.
- Keep specs and code in sync: if behaviour changes, update the spec in the same commit.
- Before finishing any task: run the test suite, then update `docs/PROGRESS.md` (status, next step) and `docs/DECISIONS.md` if a decision was made.
- State plainly what was **not** verified (for example "compiled but not run", "not run against a real model").
- Keep new docs short. This file stays under about 60 lines.
