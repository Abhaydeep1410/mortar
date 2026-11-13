# Progress

Single source of truth for "where are we". Update at the end of every task. Keep it short; reasoning lives in `docs/DECISIONS.md`, history in `git log`.

**Last updated:** 2026-11-12 · **Current phase:** Week 5 of 9 (validation done, oracles next) · **Python tests:** 420 passed, 0 skipped on the owner's Windows machine before D31 (2026-11-12, Python 3.12, extras: dev, tokens, llm, mcp); D31 adds 10 tests, so expect 430 (re-run and update)

## Plan status (from `PROJECT_CONTEXT.md` §16)

| Week | Focus | Status |
|---|---|---|
| 1 | Specs, 8 prompts, validator, Baseline A | Done. Open: Baseline A has never run against a real model |
| 2 | Registry part 1 (jwt, rbac, pagination) | Done, green on CI |
| 3 | Registry part 2 + React template | Done. Java modules green on CI; the template's CI job has not run yet |
| 4 | Pipeline + MCP server | Code complete (D24-D27): loader, Selector, Assembler, Planner, Composer, `mortar generate`, MCP server |
| 5 | Validation, repair, oracles | **In progress.** Done: reuse checker + LOC (D28), validation stage + bounded repair (D29), first-real-run hardening (D30). Run-record writer and `reuse_rate_tokens` (D31, needs a local re-run). Not started: the 8 oracles, freeze |
| 6 | Baselines B and C, 1-seed pilot, pin model | Not started |
| 7 | Benchmark (168 runs) | Not started |
| 8-9 | Analysis, report, demo, README | Not started |

## The one big risk

**Nothing has run against a real model, real Maven, or real Docker.** Baseline A, `mortar generate` and the validation stage are tested only with fakes and scripted command output. D30 fixed four things found by reading the code (Windows paths, `mvn.cmd` lookup, the health clock, wasted rebuilds), but the first real run will find more. Do this run before writing oracles: oracles frozen against output shapes that later change are expensive to redo.

## What exists and works

- Specs (`docs/*.md`), schemas, 8 benchmark prompts with acceptance criteria, blueprint validator (schema + semantic rules), coverage ablation logic, `SKILL.md` budget checker.
- Registry: 6 modules (`authentication.jwt`, `authorization.rbac`, `audit.logging`, `crud.pagination`, `export.csv`, `deployment.docker`), all green on CI (Boot 4.1.1, 2026-11-04 to 2026-11-07), plus `templates/react-admin-v1` (41 Vitest tests, build and a Playwright run verified in the sandbox against a fake backend).
- Orchestrator: model client (`LitellmClient`), Planner, Selector, Assembler, Composer, Repairer, reuse checker, validation stage, event log (JSONL), `mortar validate | select | assemble | generate`.
- MCP server: `search_components`, `get_skill`, `get_dependencies`, tested over stdio.
- `run.json` per generate run: tokens per stage, cost, modules, uncovered, reuse metrics and violations, `build_success`, `first_pass_build`, `repair_iterations`, validation stages, structured errors.
- CI: Python tests, example blueprint, SKILL.md budgets, `mvn verify` per module, frontend job.

## Known gaps

- Never run for real: Baseline A, `mortar generate`, `--validate`, `docker compose up`, the backend image build of a generated app, the full stack with the React template. Java module results are only ever claimed from CI or a local `mvn verify`; the assistant sandbox cannot reach Maven Central.
- `run.json` (D31) is now the §6 `RunRecord` for `mortar generate` as for Baseline A. `first_pass_success` and `oracle` stay None until the oracle harness exists. `reuse_rate_tokens` needs `tiktoken` for benchmark runs (the record stores which tokenizer was used).
- `mortar.agents.composer` imports `evaluator.common` (shared contract loader). That works from the repo (pytest adds `..` to the path) but not for a pip-installed `mortar`, because `pyproject.toml` packages only `mortar*`. Fine for the benchmark; move the helper into `mortar/` before any packaging.
- Reuse checker is import and name based; a reimplementation under a new name is not caught (D28).
- Token counting uses chars/4 unless `tiktoken` is installed (CI installs it). Pin one counter before the pilot.
- `docs/examples/SKILL.example.md` is older than `registry/authentication.jwt/SKILL.md`; the registry file is authoritative.
- Nothing checks `module/` Java against `metadata.yaml`.
- Name `mortar` not checked for availability on PyPI.
- `docs/results.md` (in the §12 layout) does not exist yet; it is created with the first results.

## Environment notes

- Assistant sandbox has Java 21, Node 22, Docker, but **Maven Central is not reachable** and it has no `pytest` or `jsonschema` installed, so the assistant cannot run the full suite. Never claim a Java module or the full Python suite passes without your own run or CI.
- Owner machine: Windows. Do not put `#` comments on command lines given to `cmd`. Needs JDK 21, Maven 3.9+, Docker Desktop (for validation and Testcontainers), Node 22.
- Repo: https://github.com/Abhaydeep1410/mortar, branch `main`.
- Owner machine setup (verified 2026-11-12): Python 3.12 venv at the repo root (`py -3.12 -m venv .venv`, then `pip install -e ".[dev,tokens,llm,mcp]"` from `orchestrator`; Python 3.11 does not work, `requires-python >=3.12`). Temurin JDK 21.0.13, Maven 3.9.16, Docker Desktop 4.39.0, Node 22.20.0. Verified locally: `mortar select` and `assemble` on the crm-01 fixture, `mvn verify` on `authentication.jwt`. Activate the venv in every new terminal.

## Next steps (in order)

1. **Owner:** apply D31, run `cd orchestrator && python -m pytest -q`, commit, push, check CI, and record the new test count above (was 420 before D31).
2. **First real run** (needs Maven, Docker, JDK 21, an API key, `MORTAR_MODEL`). Start without Docker cost by using `--k 6` and no `--validate`, check that the Planner and Composer produce sane output, then add `--validate`:
   `mortar generate --prompt-file evaluator\benchmark\prompts\crm-01.yaml --out runs\crm-01-try1 --k 6`
   (`--prompt-file` expects plain text, so put the prompt text from the YAML's `prompt` field into a `.txt` file first.) Paste any failure and fix the stage or the prompts.
3. Run Baseline A on `crm-01` for real (closes the Week 1 exit criterion).
4. ~~Run-record writer and `reuse_rate_tokens`~~ Done in D31 (verify locally). The oracle harness fills `first_pass_success` and `oracle`.
5. The 8 oracles (JUnit + RestAssured API suites, Playwright smoke) with a shared harness; then freeze prompts and oracles.
6. Still open from Week 4: the `claude mcp add` check against Claude Code.
