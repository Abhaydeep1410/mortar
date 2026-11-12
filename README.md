# Mortar

**LLMs plan. Mortar builds.**

Mortar is a deterministic composition engine with LLM-guided planning. It assembles deployable Spring Boot + React internal tools from a registry of tested modules, generates only the app-specific remainder with an LLM, and **measures how registry coverage affects token cost and first-pass build success**.

> Status: Week 5 of 9. The pipeline (plan, select, assemble, compose, validate, repair) is built and tested with fakes; **it has not yet run against a real model, so there are no benchmark results.** This README gets its results table when real numbers exist. Current state: [`docs/PROGRESS.md`](docs/PROGRESS.md).

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
| Registry: 6 modules (jwt, rbac, audit, pagination, csv, docker) | `registry/` | built, green on CI |
| React admin template | `templates/react-admin-v1/` | built; unit and render tests pass locally, CI job not yet run |
| Selector (deterministic), Assembler, registry loader | `orchestrator/mortar/engine/`, `registry/` | implemented, tested |
| Planner, Composer, repair agent (LLM) | `orchestrator/mortar/agents/` | implemented, tested with a fake model only |
| Validation stage (Maven, Docker, health check) | `orchestrator/mortar/validation/` | implemented, tested with scripted command output only |
| Reuse-violation checker and LOC metrics | `orchestrator/mortar/engine/reuse.py` | implemented, tested |
| MCP server (`search_components`, `get_skill`, `get_dependencies`) | `mcp-server/` | implemented, tested over stdio |
| Baselines B and C, oracles, benchmark runner | | not started |

## Try it

```bash
cd orchestrator
pip install -e ".[dev]"
python -m pytest                                   # full suite

python -m mortar.cli validate tests/fixtures/crm-01.blueprint.yaml
python -m mortar.cli select tests/fixtures/crm-01.blueprint.yaml --k 4     # which modules are wired, what is uncovered
python -m mortar.cli assemble tests/fixtures/crm-01.blueprint.yaml --out ../runs/crm-demo
python ../tools/check_skill_budget.py ../docs/examples/SKILL.example.md

cd ..
python -m evaluator.baselines.single_agent crm-01 --dry-run   # prints the exact prompt Baseline A sends

# real runs (pip install -e "orchestrator[llm]"; set the pinned model first)
export MORTAR_MODEL=<model id>   # plus your provider's API key
python -m evaluator.baselines.single_agent crm-01 --out runs/crm-01__A__s1

# full pipeline: plan -> select -> assemble -> compose; --validate also builds, starts and repairs (needs Maven, Docker)
mortar generate --prompt "Build a CRM with contacts, deals and notes" --out runs/crm-try --k 6 --validate
```

## Limitations (so far)

Small registry (6 modules), one stack, one model, 8 prompts, modules and oracles written by the same person. See `docs/benchmark-design.md` §11.
