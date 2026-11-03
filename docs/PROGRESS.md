# Progress

Single source of truth for "where are we". Update at the end of every task. Keep it short; history lives in `git log`.

**Last updated:** 2026-11-03 · **Current phase:** Week 2 of 9 (registry part 1) · **Python tests:** 184 passing (`cd orchestrator && python -m pytest -q`)

## Plan status (from `PROJECT_CONTEXT.md` §16)

| Week | Focus | Status |
|---|---|---|
| 1 | Specs, 8 prompts, validator, Baseline A | **Done, with one gap:** Baseline A has not run against a real model |
| 2 | Registry part 1: `authentication.jwt`, `authorization.rbac`, `crud.pagination` | **In progress:** `authentication.jwt` green on CI (4.1.0); Boot bump to 4.1.1 awaiting CI; other two not started |
| 3 | Registry part 2 + React template | Not started |
| 4 | Pipeline + MCP server | Not started |
| 5 | Validation, repair, **oracles written and frozen** | Not started |
| 6 | Baselines B and C, 1-seed pilot, pin model | Not started |
| 7 | Benchmark (168 runs) | Not started |
| 8-9 | Analysis, report, demo, README | Not started |

## What exists and works

- Specs: `docs/blueprint-spec.md`, `docs/module-spec.md`, `docs/benchmark-design.md`; schemas in `schemas/`.
- 8 benchmark prompts with acceptance criteria: `evaluator/benchmark/prompts/`.
- Blueprint validator (schema + 12 semantic rules) and `mortar validate` CLI.
- Coverage ablation logic (k = 0/2/4/6, primary and alternate orders): `orchestrator/mortar/engine/coverage.py`.
- `SKILL.md` budget checker: `tools/check_skill_budget.py`.
- Baseline A (`evaluator/baselines/single_agent.py`) with run-record invariants; tested with a fake model.
- CI workflow (`.github/workflows/ci.yml`): Python tests, example blueprint, SKILL.md budgets, and a `java` job that runs `mvn verify` on every `registry/*/module`.
- `registry/authentication.jwt/`: Java source (token service, filter, login controller, auto-configuration), JUnit tests, `metadata.yaml`, `SKILL.md` (about 311 tokens by an upper-bound count), `config-schema.json`, example config.
- `orchestrator/tests/test_registry.py`: checks every module's layout, metadata, SKILL.md, config schema, example config, provided interfaces and POM against the spec (no Java needed).

## Not done / known gaps

- Baseline A never run for real (no API key yet). Exit criterion for Week 1 is open until it is.
- Token counting uses chars/4 unless `tiktoken` is installed (CI installs it). Pin one counter before the benchmark.
- `authentication.jwt` is only verified by CI (run 2: compiles, 24/26 tests pass). The assistant sandbox cannot reach Maven Central, so never claim it passes without a CI or local `mvn verify` result.
- **CI run 4 (2026-11-03): green on Boot 4.1.0** after the CORS fix (source built inline, not a bean, because MVC's `HandlerMappingIntrospector` is already a `CorsConfigurationSource`). Boot bumped to 4.1.1 afterwards; awaiting its CI run.
- `docs/examples/SKILL.example.md` is older than `registry/authentication.jwt/SKILL.md` (it still mentions a PostgreSQL user table). The registry file is authoritative.
- SKILL.md token counts are approximate (tiktoken could not download its vocabulary in the sandbox). Real counts for code-heavy text can exceed chars/4; CI uses tiktoken and will show the real number.
- No oracles, `authorization.rbac`, `crud.pagination`, other modules, pipeline, MCP server, React template, or Baselines B/C.
- Nothing yet checks `module/` Java against `metadata.yaml`; `owns:` field for reuse-violation detection is specified but not in the schema (Week 5).
- Name `mortar` not checked for availability on PyPI.

## Environment notes

- Assistant sandbox has Java 21, Maven 3.9, Node 22, Docker, but **Maven Central is not reachable from it** (checked 2026-11-02). Java modules can be written there but not built or tested. The owner must run `mvn verify` locally and report results; never claim a Java module "passes" without that.
- Owner needs JDK 21 and Maven 3.9+ on the Windows machine (and Docker Desktop later, for Testcontainers).
- Authoring machine is Windows (`cmd`): do not put `#` comments on command lines when giving commands.
- Repo: https://github.com/Abhaydeep1410/mortar, branch `main`.

## Next steps (in order)

1. **Owner:** push the 4.1.1 bump and confirm the `java` job stays green.
2. Then `authorization.rbac` (requires jwt; uses `SecurityConfigurer` or method security), then `crud.pagination`.
4. Add `MODULE_REQUIRES` entries in `vocab.py` for any new module that needs another.

## Open questions for the owner

- Which model and API key will be pinned for the benchmark? (Needed by Week 6; needed sooner to close the Week 1 gap.)
- Confirm GitHub email on commits matches a verified email on the account.

## Parked ideas (outside v1 scope; do not build yet)

- LLM Selector ablation, adapted-vs-vendored modules, rate-limit module (Phase 2, `PROJECT_CONTEXT.md` §5).
- "Mortar Web": a website around the pipeline. Prefer replay mode for public demos; sandbox builds and cap spend for live runs. Keep a `generate(prompt, k) -> events + output_dir` function so a thin API can wrap it later.
