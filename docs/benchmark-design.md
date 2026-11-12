# Mortar Benchmark Design v1

Authoritative detail for `PROJECT_CONTEXT.md` §13–14. If the two disagree, fix this file and the context doc together.

## 1. Question and claims

> At what registry coverage does module-aware composition beat monolithic generation on token cost and first-pass build success, and does a skills-only baseline (no deterministic composer) close the gap?

Claims are limited to: this stack (Java 21, Spring Boot 4.1.x, React Admin 5), this registry (6 modules), these 8 prompts, one pinned model. Nothing else is claimed.

## 2. Strategies

All use the same pinned model, temperature, max output tokens, and repair budget (≤2 repair iterations, each given build/test error output).

| ID | Name | Pipeline | Registry access |
|---|---|---|---|
| A | single-agent | One prompt → whole repo | none |
| B | multi-agent-naive | Planner → Coder (no selector, no assembler) | none |
| C | single-agent-skills | One agent with `SKILL.md` files + module artifacts as tools, free to use or ignore them | skills only |
| O@k | mortar | Planner → deterministic Selector → Composer → Assembler | registry of size k |

O@k is run at **k ∈ {0, 2, 4, 6}**. O@0 is a sanity check: with no modules it should behave like B plus the pipeline overhead.

## 3. Coverage ablation

- Module addition order (pre-registered, primary): `crud.pagination`, `authentication.jwt`, `authorization.rbac`, `audit.logging`, `export.csv`, `deployment.docker`.
  - k=2: crud.pagination, authentication.jwt
  - k=4: + authorization.rbac, audit.logging
  - k=6: all
- Alternate order (order-sensitivity check, run on the 3 pilot prompts only): `deployment.docker`, `export.csv`, `audit.logging`, `authorization.rbac`, `authentication.jwt`, `crud.pagination`.
- Because modules have dependencies (rbac needs an auth capability), a module whose `requires` are unmet in a subset is skipped and its capability is reported *uncovered*. The skip is logged.
- **Coverage** for a prompt at k = fraction of that prompt's required capabilities served by selected modules (see `blueprint-spec.md` §4). Reported alongside k because prompts differ in capability count.

## 4. Controls

- **Same model, params, seeds-by-index.** Seed s ∈ {1,2,3} means "repetition index"; most APIs do not guarantee seeded determinism, so repetitions are independent samples, not reproductions.
- **Planner-variance control:** for each prompt, one Planner run produces a *frozen blueprint* (committed, hashed). The main O@k comparison uses the frozen blueprint so differences between k levels are not Planner noise. A secondary run with a live Planner measures end-to-end behaviour. Baselines A and C receive the prompt only.
- **Environment:** one Docker image for builds (pinned JDK 21, Maven, Node, Playwright); Maven repository cache warmed identically before every run; build time excludes dependency download.
- **Frozen before any O run:** prompts, acceptance criteria, oracles, application contract (§5), metric definitions. Changes after freeze invalidate affected runs and are recorded in `docs/results.md`.
- **No leakage:** oracle tests are never in any prompt or tool the model can read.

## 5. Application contract (appended to every prompt)

Strategies are scored by black-box oracles, so every generated app must satisfy the same externally visible contract. This text is appended verbatim to each prompt for all strategies.

- Backend on port **8080**, frontend served on port **3000**, started by `docker compose up` from the repo root. PostgreSQL 16 is provided by the compose file.
- `GET /actuator/health` returns 200 `{"status":"UP"}` when ready.
- **Auth:** `POST /api/auth/login` with `{"username","password"}` returns `{"token"}`; all other `/api/**` endpoints require `Authorization: Bearer <token>` (401 without, 403 when the role lacks permission).
- **Seeded users** (created on first start): `admin/Admin#12345` (ADMIN), plus one user per other role named by the prompt. Username = role name lowercased; password = username with its first letter capitalized, followed by `#12345` (e.g. `sales_rep/Sales_rep#12345`). Each prompt file lists them explicitly in `seeded_users`.
- **REST convention** per entity: base path `/api/<plural-lowercase-entity>` (lowercase the entity name, then: ends in s, x, z, ch or sh -> add `es`; consonant + y -> `ies`; otherwise add `s`; person, child, man, woman are irregular; implemented once in `orchestrator/mortar/engine/naming.py`, e.g. `Category` -> `categories`, `Entry` -> `entries`); `GET` list supports `?page=<0-based>&size=<n>&sort=<field>,<asc|desc>` and returns `{"content":[...],"totalElements":n,"page":p,"size":s}`; `GET /{id}`, `POST`, `PUT /{id}`, `DELETE /{id}`; JSON field names are the camelCase names in the prompt; `id` is a server-generated integer. `POST` returns 201 with the created entity; `PUT` returns 200 with the updated entity; `DELETE` returns 204; an unknown `id` returns 404; `size` above 100 or an unknown `sort` field returns 400; `q=<text>` on the list endpoint matches case-insensitively as plain text.
- Validation failures return 400 with a JSON body containing a `message`.
- **CSV export** (when the prompt requires it): `GET /api/<plural>/export.csv` returns `text/csv`, header row = field names, honoring the caller's read permissions.
- **Audit log** (when the prompt requires it): every successful POST/PUT/DELETE on a non-read-only entity records actor, action, entity, entity id, timestamp; `GET /api/audit-logs?page=&size=` is ADMIN-only (401 without a token, 403 for other roles) and returns the list shape with records `{"id","actor","action","entity","entityId","timestamp"}` newest first: `actor` is the username, `action` is `CREATE`, `UPDATE` or `DELETE`, `entity` is the lowercase singular entity name (for example `contact`), `entityId` is the integer id, `timestamp` is ISO-8601 UTC. `POST` responses carry a `Location` header ending in the new id. Failed or rejected requests are not recorded.
- **Frontend:** React Admin app; login page; one sidebar entry per entity; list pages show a paginated table; create/edit forms for writable entities.

The oracle suite is written against this contract, not against any implementation.

## 6. Metrics

Per run (JSON, one file per run under `evaluator/report/runs/`):

```json
{
  "run_id": "crm-01__ours-k6__s1",
  "prompt_id": "crm-01",
  "strategy": "ours",
  "registry_k": 6,
  "seed": 1,
  "model": "<pinned model id>",
  "blueprint_sha256": "…",
  "tokens": {"planning": 0, "retrieval": 0, "generation": 0, "repair": 0, "input_total": 0, "output_total": 0},
  "cost_usd": 0.0,
  "build_success": false,
  "first_pass_success": false,
  "repair_iterations": 0,
  "oracle": {"passed": 0, "total": 0, "by_criterion": {}},
  "generation_time_s": 0.0,
  "coverage": 0.0,
  "reuse_rate_loc": 0.0,
  "reuse_rate_tokens": 0.0,
  "reused_loc": 0,
  "generated_loc": 0,
  "modules_selected": [],
  "uncovered": [],
  "reuse_violations": 0,
  "errors": []
}
```

Definitions:
- **tokens:** straight from provider usage fields. Each stage bucket (`planning`, `retrieval`, `generation`, `repair`) is the input **plus** output tokens of the model calls made in that stage; `retrieval` covers loading `SKILL.md`/search results. `input_total`/`output_total` split the same tokens by direction because prices differ, so the four buckets always sum to `input_total + output_total` (checked by `RunRecord.check_invariants`). **No estimates**: a run lacking usage data, or whose cost cannot be computed, is marked invalid and re-run, never imputed.
- **cost_usd:** computed from usage with a price table committed in `evaluator/report/pricing.json` at pin time.
- **build_success:** `docker compose build` and `mvn verify` (module and app tests that the strategy itself produced are *not* required to pass) and `docker compose up` reaches `/actuator/health` UP within 120 s.
- **first_pass_success:** build_success **and** oracle pass-rate = 100% **with zero repair iterations** and zero reuse violations. A stricter flag, `first_pass_build`, records build-only first pass.
- **oracle:** hand-written JUnit (API) + Playwright (UI smoke) results; every acceptance criterion maps to ≥1 test; `by_criterion` records pass/fail per criterion ID.
- **coverage:** see `blueprint-spec.md` §4. Baselines A/B: 0. Baseline C: coverage is computed post-hoc from which module artifacts it actually used.
- **reuse_rate_loc** = `reused_module_LOC / (reused_module_LOC + generated_LOC)`; module LOC counts non-blank, non-comment Java/XML/YAML source of modules actually wired in. `generated_LOC` counts the same for model-written files. Reported with its counter version (`loc-1`, `orchestrator/mortar/engine/reuse.py`): module LOC covers every `.java`, `.xml`, `.yml` and `.yaml` file under the module's `module/` directory (including its `pom.xml`, excluding tests and build output); `generated_LOC` covers the same file types among the model-written files, so a Dockerfile or SQL migration counts for neither. Java comments (line and block) are excluded.
- **reuse_rate_tokens** = token-equivalent of reused code divided by that plus generated output tokens, using one fixed tokenizer recorded in the run.
- **reuse_violations:** count from the post-generation checker (`module-spec.md` §6). Any violation clears `first_pass_success`.

## 7. Statistics

- **Design:** 8 prompts × 7 configs {A, B, C, O@0, O@2, O@4, O@6} × 3 repetitions = **168 runs**. Pilot (1 repetition, 3 prompts: `crm-01`, `inventory-03`, `vendor-07`) precedes the full run and sets the cost cap.
- **Estimates:** per config, mean tokens and cost with 95% **bootstrap CIs** (10,000 resamples, resampling prompts then repetitions within prompt). First-pass success reported as a proportion with Wilson 95% intervals; n is stated next to every proportion.
- **Comparisons:** paired by prompt. Report mean paired difference in tokens (O@k − best baseline) with a bootstrap CI. No claim is made when the CI crosses zero.
- **Crossover:** the smallest k at which the upper CI bound of O@k's mean total tokens is below the lower CI bound of the best baseline's mean. If no k qualifies, the report states "no crossover observed in k ≤ 6" and shows the data.
- **Power caveat (stated in README):** with 8 prompts and 3 repetitions only large effects are detectable; null results are inconclusive, not evidence of equivalence.

## 8. Budget gate

`run.py` takes `--max-cost-usd` and aborts before a run if projected cumulative cost would exceed it. Projection = mean cost per run from completed runs × remaining runs. The pilot sets the cap with 25% headroom for repairs. Partial results are retained and flagged incomplete.

## 9. Prompts

Eight prompt files in `evaluator/benchmark/prompts/` (YAML). Each has `prompt` (user-visible text), `entities`, `capabilities`, `roles`, `seeded_users`, and `acceptance_criteria` (ID, text, test type). Criteria use IDs of the form `<prompt-id>.<n>`; oracle tests reference these IDs.

| # | ID | Domain | Entities | Capabilities |
|---|---|---|---|---|
| 1 | crm-01 | CRM | Contact, Deal, Note | jwt, rbac, audit, crud, csv, docker |
| 2 | expense-02 | Expense tracker | Expense, Category, Approval | jwt, rbac, audit, crud, docker |
| 3 | inventory-03 | Inventory | Item, Stock, Location | jwt, rbac, crud, csv, docker |
| 4 | saas-admin-04 | SaaS admin | User, Plan, Invoice (read-only) | jwt, rbac, audit, crud, docker |
| 5 | ats-05 | Applicant tracking | Candidate, Stage, Interview | jwt, rbac, audit, crud, docker |
| 6 | asset-06 | IT assets | Asset, Assignment, Owner | jwt, rbac, audit, crud, csv, docker |
| 7 | vendor-07 | Vendor directory | Vendor, Contract, Contact | jwt, rbac, crud, docker |
| 8 | timesheet-08 | Timesheets | Project, Timesheet, Entry | jwt, rbac, crud, csv, docker |

Validity checks (run by `tests/test_prompts.py`): each prompt file validates, every criterion ID is unique and prefixed with the prompt ID, every prompt's capabilities are in the vocabulary, and every prompt uses ≥ 3 of the 6 registry modules.

## 10. Oracle policy

- Written by hand, by a person, from the acceptance criteria and application contract, **before** any Mortar run (Week 5 freeze).
- API oracle: JUnit 5 + RestAssured against the running compose stack. UI oracle: Playwright smoke (login, list renders, create flow for one writable entity).
- Each test is tagged with its criterion ID so `by_criterion` can be computed mechanically.
- A shared oracle harness handles login/seeding; per-prompt suites hold only the criteria.

## 11. Reporting rules

- `results.md` includes the raw per-run table, the aggregate table with CIs, the ablation chart, failure-mode counts (build error classes, oracle criteria most often failed), and a limitations section.
- No metric is estimated. If a run is invalid (missing usage data, infra failure), it is reported as invalid and re-run, not imputed.
- Limitations include: small registry, one stack, one model, 8 prompts, modules and oracles written by the same person, prompts chosen for high module overlap (so reuse results are an upper bound for this domain).
