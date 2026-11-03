# Mortar

**LLMs plan. Mortar builds.**

Mortar is a deterministic composition engine with LLM-guided planning. It assembles deployable Spring Boot + React internal tools from a registry of tested modules, generates only the app-specific remainder with an LLM, and **measures how registry coverage affects token cost and first-pass build success**.

> Status: Week 1 of 9 (specs, benchmark design, validator, Baseline A). No benchmark results yet; this README gets its results table when real numbers exist.

The full contract is in [`PROJECT_CONTEXT.md`](PROJECT_CONTEXT.md).

## What exists today

| Area | Where | State |
|---|---|---|
| Module spec | [`docs/module-spec.md`](docs/module-spec.md), `schemas/module-metadata.schema.json` | written |
| Blueprint spec | [`docs/blueprint-spec.md`](docs/blueprint-spec.md), `schemas/blueprint.schema.json` | written |
| Benchmark design | [`docs/benchmark-design.md`](docs/benchmark-design.md) | written |
| 8 benchmark prompts + acceptance criteria | `evaluator/benchmark/prompts/` | written |
| Blueprint validator (schema + semantic rules) | `orchestrator/mortar/engine/` | implemented, tested |
| Coverage ablation logic (k = 0/2/4/6) | `orchestrator/mortar/engine/coverage.py` | implemented, tested |
| `SKILL.md` budget checker | `tools/check_skill_budget.py` | implemented, tested |
| Baseline A (single agent) | `evaluator/baselines/single_agent.py` | implemented, tested with a fake model; **not yet run against a real model** |
| Registry module `authentication.jwt` | `registry/authentication.jwt/` | written; **Java not yet compiled** (CI job `java` is the first build) |
| Oracles, other registry modules, pipeline, MCP server | | not started |

## Try it

```bash
cd orchestrator
pip install -e ".[dev]"
python -m pytest                                   # full suite

python -m mortar.cli validate tests/fixtures/crm-01.blueprint.yaml
python ../tools/check_skill_budget.py ../docs/examples/SKILL.example.md

cd ..
python -m evaluator.baselines.single_agent crm-01 --dry-run   # prints the exact prompt Baseline A sends

# real run (pip install -e "orchestrator[llm]"; set the pinned model first)
export MORTAR_MODEL=<model id>   # plus your provider's API key
python -m evaluator.baselines.single_agent crm-01 --out runs/crm-01__A__s1
```

## Limitations (so far)

Small registry (6 modules), one stack, one model, 8 prompts, modules and oracles written by the same person. See `docs/benchmark-design.md` §11.
