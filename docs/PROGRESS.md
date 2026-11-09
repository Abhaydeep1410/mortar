# Progress

Single source of truth for "where are we". Update at the end of every task. Keep it short; history lives in `git log`.

**Last updated:** 2026-11-09 · **Current phase:** Week 4 of 9 (pipeline + MCP server) · **Python tests:** 318 passing (`cd orchestrator && python -m pytest -q`)

## Plan status (from `PROJECT_CONTEXT.md` §16)

| Week | Focus | Status |
|---|---|---|
| 1 | Specs, 8 prompts, validator, Baseline A | **Done, with one gap:** Baseline A has not run against a real model |
| 2 | Registry part 1: `authentication.jwt`, `authorization.rbac`, `crud.pagination` | **Done:** all three green on CI (Boot 4.1.1) |
| 3 | Registry part 2 + React template | **Done:** all 6 modules and the React template. Java modules green on CI; the template is verified here (41 tests, build, real-browser run) but its CI job has not run yet |
| 4 | Pipeline + MCP server | **In progress:** registry loader, naming, Selector, event log, `mortar select` done (D24). Assembler and `mortar assemble` done (D25; compose config valid, generated frontend builds and passes its tests). Next: Planner/Composer agents, MCP server |
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
- **`authentication.jwt` is green on CI (Boot 4.1.1, 26 tests).** The CORS bug was that MVC's `HandlerMappingIntrospector` is already a `CorsConfigurationSource`, so the CORS source is now built inline, not a bean (D18).
- **`authentication.jwt` and `authorization.rbac` are green on CI (2026-11-04, Boot 4.1.1).** RBAC needed `@PathVariable("id")` in its test controller because module POMs do not use the Boot parent (`-parameters` is off); `crud.pagination` sets `maven.compiler.parameters` for the same reason. CI runs `mvn install` per module so rbac can resolve `com.mortar:jwt-auth` (D19).
- **`crud.pagination` is green on CI (2026-11-04).** JPA on H2 with Hibernate 7 and Spring Data JPA 4 worked first time.
- **`audit.logging` is green on CI (2026-11-05).** A first run failed only because CI built modules alphabetically and `audit.logging` sorted before `authentication.jwt`; CI now builds jwt first (D19).
- **`export.csv` is green on CI (2026-11-06).**
- **`deployment.docker` is green on CI (2026-11-07).** Not yet run anywhere: `docker compose up` and both image builds. The backend image cannot build a generated app until the Assembler vendors the `com.mortar:*` module jars into `backend/` (D22).
- **`templates/react-admin-v1` verified in the sandbox, not yet on CI:** `npm ci`, `npm run build`, 41 Vitest tests (unit tests plus full-app render tests against a mocked backend) and a Playwright run of the built app against a fake backend that follows the API contract (login, sidebar, 25-row paginated table, admin sees Create and Export, viewer does not). Never run against the real Spring backend: the first full-stack run happens when the Assembler exists (Week 4). `npm audit` reports 0 production vulnerabilities only because of an `overrides` entry for `decode-uri-component` (D23).
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

1. **Owner:** push the Assembler commits and check CI is green (the Dockerfile in `deployment.docker` changed; Python tests cover it).
2. Planner and Composer agents (need an API key and a pinned model). The Composer works only under `backend/src/main/java/<basePackage>/` and the `uncovered` list.
3. MCP server. Then Week 5: build, test and repair stage (first real check that a generated backend compiles and images build).
