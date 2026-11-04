# Mortar — Project Context v1.1

**Mortar: Agentic Application Composer.** *LLMs plan. Mortar builds.*

North-star document. Everything below is the contract for what we build. If something isn't here, it's out of scope.

**Changes from v1.0:** project named **Mortar** (CLI `mortar`, Java namespace `com.mortar`, blueprint `apiVersion: mortar/v1`); Spring Boot 4.x (3.2 is EOL); registry cut 9 → 6 modules; benchmark cut 10 → 8 prompts; controlled coverage ablation replaces uncontrolled reuse-rate scatter; Baseline C added; Selector made deterministic; multiple seeds with confidence intervals; `SKILL.md` follows the open Agent Skills format; timeline 6 → 9 weeks; unverifiable claims removed from positioning.

---

## 1. One-Liner

A deterministic composition engine with LLM-guided planning that assembles deployable Spring Boot + React internal tools from a registry of tested, reusable modules, and **measures how registry coverage affects token cost and first-pass build success**.

## 2. Elevator Pitch (60 seconds)

AI coding agents regenerate JWT auth, CRUD endpoints, pagination, and audit logs from scratch every time. That's expensive and inconsistent. Mortar flips the model: an LLM plans against a structured Application Blueprint, deterministic code wires in tested modules from a registry, and the LLM generates only the app-specific remainder. We benchmark against three baselines on 8 internal-tool prompts, and we **ablate the registry (0 / 2 / 4 / 6 modules)** to measure the coverage level at which composition beats regeneration.

## 3. Thesis & Research Question

**Thesis:** Deterministic composition of tested modules beats monolithic LLM generation on token cost and build reliability, but only above some registry-coverage threshold, because orchestration overhead is a fixed cost.

**Research question:**
> At what registry coverage does module-aware composition beat monolithic generation on token cost and first-pass build success, and does a skills-only baseline (no deterministic composer) close the gap?

**Why this question:** answerable in ~9 weeks, produces a controlled chart, and any outcome is a finding (including "the threshold is ~0 because the modules are boilerplate" or "Baseline C matches us").

**Honest scope of claims:** results apply to this stack, this registry, these prompts, these models. No generalization beyond that.

## 4. What We're Building (v1 Scope)

| Layer | Deliverable |
|---|---|
| Orchestrator | Python 3.12 CLI: `mortar generate "<prompt>"` |
| LLM agents | 2: **Planner**, **Composer** |
| Deterministic engine | Blueprint validator, **capability→module selector**, module resolver, template renderer, assembler, Docker builder |
| Blueprint | YAML intermediate representation (versioned, schema-validated) |
| Registry | **6** Spring Boot modules + 1 React template, each with `SKILL.md`, `metadata.yaml`, tests |
| MCP server | `search_components()`, `get_skill()`, `get_dependencies()` over the registry |
| Validation | Build → JUnit → Playwright smoke → bounded repair (max 2) |
| Benchmark | 8 prompts × 4 strategy configs (see §13) × 3 seeds, full metrics |
| Output | Deployable app, `results.md`, coverage-ablation chart, demo video |

**Domain:** internal tools (admin dashboards, CRMs, ticketing-lite, inventory, expense trackers). Not e-commerce, social, or LMS. Reason: maximum module reuse.

## 5. What We're NOT Building (v1 Non-Goals)

- ❌ Kafka, Kubernetes, microservices
- ❌ More than 2 LLM agents in the main pipeline
- ❌ Vector search over the registry (SQLite FTS5 is enough)
- ❌ More than 8 benchmark prompts
- ❌ Multi-language codegen (Java/Spring Boot backend, React frontend only)
- ❌ Cloud deploy (Docker Compose is the deploy target)
- ❌ Fine-tuning, RAG over docs, autonomous PR generation
- ❌ LLM-adapted modules (see Phase 2)

**Phase 2 stretch (only after the benchmark is done):**
- LLM Selector variant (ablation of deterministic selection)
- Adapted-vs-vendored modules (the LEGO paper reports adapted primitives outperform unchanged ones; test whether that holds here)
- Rate-limit, standalone actuator/flyway modules, more prompts

## 6. Architecture (v1)

```
                    USER PROMPT
                         │
                         ▼
         ┌───────────────────────────────┐
         │   ORCHESTRATOR (Python)       │
         │   deterministic control plane │
         └───────────────┬───────────────┘
                         ▼
                  ┌────────────┐
                  │  Planner   │  ← LLM #1
                  └─────┬──────┘
                        ▼
         ┌─────────────────────────────────┐
         │  APPLICATION BLUEPRINT (YAML)   │  ← the contract
         └───────────────┬─────────────────┘
                         ▼
         ┌─────────────────────────────────┐
         │  SELECTOR (deterministic)       │  capability → module,
         │  + compatibility check          │  config filled from blueprint
         └───────────────┬─────────────────┘
                         ▼
                  ┌────────────┐
                  │  Composer  │  ← LLM #2 (custom services + glue only)
                  └─────┬──────┘
                        ▼
         ┌─────────────────────────────────┐
         │   DETERMINISTIC ASSEMBLER       │  no LLM
         │  resolve → render → assemble    │
         └───────────────┬─────────────────┘
                         ▼
              ┌──────────────────────┐
              │  BUILD → TEST        │
              │  → REPAIR (≤2) → RUN │
              └──────────┬───────────┘
                         ▼
                   DEPLOYABLE APP
                   + EVENT LOG (JSONL)
```

**Key principle:** LLMs write the plan and the app-specific code. Deterministic code selects, wires, and assembles tested modules.

**Why the Selector is deterministic:** the Planner already emits capability IDs from a closed vocabulary, so selection over 6 modules is a lookup with an `provides/requires/conflicts_with` check. An LLM here adds cost and noise without adding capability. An LLM-Selector variant is a Phase 2 ablation.

## 7. The Agents and Engine

### Agent 1: Planner (LLM)
- **Input:** natural-language prompt.
- **Output:** complete Blueprint YAML via structured output (JSON schema / function calling). No free text.
- **Does:** infer app type, entities, pages, roles; choose frontend template (react-admin-v1); list required capabilities from the closed vocabulary; declare `custom_services` (the app-specific remainder).
- **Does NOT:** select modules or write code.

### Selector (deterministic)
- **Input:** Blueprint. **Output:** Blueprint with `components:` filled (`module_id`, `version`, `config`).
- For each capability: registry lookup → `check_compatibility` (requires/provides/conflicts, Spring Boot and Java ranges) → fill config from blueprint values.
- Capability with no matching module → recorded as **uncovered**; it becomes generation work for the Composer. (This is how the coverage ablation works.)

### Agent 2: Composer (LLM)
- **Input:** Blueprint with components, plus `SKILL.md` of each selected module (not the implementation).
- **Output:** Java files for `custom_services`/`custom_controllers`, DTOs, entities, wiring config, plus implementations for any uncovered capabilities.
- **Does NOT:** reimplement anything a selected module provides. Enforced post-generation: if generated code imports a library a selected module provides, or redefines a known interface, the run is flagged with a **reuse violation** (logged; the first-pass flag is cleared).

### Deterministic Assembler (no LLM)
1. Resolve module artifacts + versions from registry.
2. Render `pom.xml`, `application.yml`, `docker-compose.yml` from blueprint.
3. Copy the React template; parameterize routes/sidebar from blueprint entities.
4. Assemble generated code + module deps into a buildable repo.
5. Emit event log (JSONL: every decision, every file written).

## 8. Application Blueprint Spec (v1)

```yaml
# blueprint.yaml
apiVersion: mortar/v1
kind: Application

metadata:
  name: customer-crm
  prompt: "Build a CRM with contacts, deals, notes, and role-based access"
  timestamp: 2026-11-01T10:00:00Z

application:
  type: internal-tool
  domain: crm

frontend:
  template: react-admin-v1
  entities:
    - name: Contact
      fields: [name, email, company, phone]
    - name: Deal
      fields: [title, value, stage, contactId, closeDate]
    - name: Note
      fields: [body, contactId, authorId, createdAt]
  pages:
    - { type: list,   entity: Contact }
    - { type: detail, entity: Contact }
    - { type: list,   entity: Deal }
    - { type: kanban, entity: Deal, groupBy: stage }

backend:
  framework: spring-boot
  javaVersion: 21
  springBootVersion: 4.1.x     # pin exact patch in pyproject lockfile
  basePackage: com.example.crm

database:
  type: postgresql
  migrations: flyway

capabilities:
  - authentication.jwt
  - authorization.rbac
  - audit.logging
  - crud.pagination
  - export.csv
  - deployment.docker

custom_services:
  - name: DealPipelineService
    responsibility: "Move deals between stages, enforce stage transition rules"
  - name: ContactSearchService
    responsibility: "Full-text search over contacts"
  - name: NoteService
    responsibility: "CRUD for notes attached to contacts"

roles:
  - name: ADMIN
    permissions: ["*"]
  - name: SALES_REP
    permissions: ["contact:read", "contact:write", "deal:read", "deal:write", "note:*"]
  - name: VIEWER
    permissions: ["contact:read", "deal:read", "note:read"]

components: []   # filled by Selector
```

**Validation rules:**
- Every `capabilities` entry must be in the closed capability vocabulary. (It need not map to a registry module; unmapped = *uncovered*, see §7. This differs from v1.0 and enables the ablation.)
- Every `custom_services` entry must have a non-empty `responsibility`.
- `roles[].permissions` must come from the known permission vocabulary.

## 9. Module Spec (v1)

Every module lives at `registry/<id>/` and contains exactly:

```
registry/authentication.jwt/
├── SKILL.md                # AI-readable usage contract (open Agent Skills format)
├── metadata.yaml           # machine-readable
├── config-schema.json      # JSON Schema for module config
├── module/                 # actual Maven module
│   ├── pom.xml
│   └── src/
├── tests/                  # module's own tests (must pass independently)
└── examples/
    └── example-config.yaml
```

### metadata.yaml
```yaml
id: authentication.jwt
version: 1.0.0
category: authentication
display_name: JWT Authentication

provides:
  - interface: com.mortar.auth.JwtService
    description: Issue and validate JWT tokens
  - interface: com.mortar.auth.SecurityConfigurer
    description: Spring Security filter chain contribution
  - capability: authentication.jwt

requires:
  - capability: database.postgresql
  - env: [JWT_SECRET, JWT_TTL_MINUTES]

spring_boot: ">=4.0.0 <5.0.0"
java: ">=21"

conflicts_with:
  - authentication.oauth2   # both own the SecurityFilterChain

tags: [auth, jwt, security]
```

### SKILL.md (body ≤ 400 tokens, enforced by CI)

Follows the open Agent Skills format so registry modules also work in Claude Code, Cursor, Copilot, etc.: YAML frontmatter with `name` and `description`, then Markdown.

```markdown
---
name: authentication-jwt
description: Stateless JWT auth for Spring Boot. Use when the app needs login, token issuing/validation, or a Spring Security filter chain.
---

# JWT Authentication

## Provides
- `JwtService.issue(userId, roles)` → token
- `JwtService.validate(token)` → Claims
- Auto-registered `SecurityFilterChain` with JWT filter

## Requires
- PostgreSQL (user table); env `JWT_SECRET` (min 32 chars), `JWT_TTL_MINUTES`

## Config (application.yml)
mortar.auth.jwt.secret: ${JWT_SECRET}
mortar.auth.jwt.ttl-minutes: ${JWT_TTL_MINUTES:60}

## Integration
- Add dependency `com.mortar:jwt-auth:1.0.0`
- Do NOT define your own SecurityFilterChain; this module owns it
- Inject `JwtService` in the login controller

## Conflicts
- authentication.oauth2 (mutually exclusive)
```

**Why this works:** the Composer loads ~300 tokens of contract, not the implementation. That's the token-efficiency claim made measurable. (Consistent with the MCP "context bloat" concern: load skills on demand, don't pre-load everything.)

## 10. Registry v1 — The 6 Modules

| # | Module ID | Category | Provides |
|---|---|---|---|
| 1 | `authentication.jwt` | auth | JwtService, SecurityFilterChain |
| 2 | `authorization.rbac` | auth | `@RequiresPermission`, permission evaluator |
| 3 | `audit.logging` | observability | Request-level audit filter, AuditLog entity, `GET /api/audit-logs` (synchronous writer; see D21) |
| 4 | `crud.pagination` | data | Generic `BaseController<T>`, `Pageable` helpers, `BaseRepository` |
| 5 | `export.csv` | data | `@Exportable`, `CsvExportController` |
| 6 | `deployment.docker` | deployment | Multi-stage Dockerfile, docker-compose.yml, Flyway baseline, Actuator health config |

**Plus 1 frontend template:** `templates/react-admin-v1/` — React 18 + TypeScript + Vite + React Admin 5.x, parameterized by blueprint entities.

**All modules are hand-written by us.** No AI generation of registry modules (circular). Each has its own tests that pass independently.

**Framing:** modules are opinionated wrappers around Spring Security, Spring Data, and OpenCSV-style libraries, not reimplementations. The point is a *pre-wired, tested, convention-consistent* integration that an LLM doesn't need to rederive.

## 11. Tech Stack (Exact)

| Layer | Choice | Version |
|---|---|---|
| Orchestrator language | Python | 3.12 |
| Package manager | uv | latest, lockfile committed |
| LLM access | `litellm` (provider-agnostic) | pinned |
| Benchmark model | **One fixed model for all strategies**, recorded in every run's log | pinned at start of Week 5 |
| Structured output | JSON schema / function calling | native |
| Registry index | SQLite + FTS5 | stdlib |
| MCP server | `mcp` Python SDK | pinned |
| Target backend | Java 21 + Spring Boot | **4.1.x** (4.0 GA'd Nov 2025; 3.2 is EOL) |
| Build | Maven | 3.9+ |
| Target frontend | React 18 + TypeScript + Vite | pinned |
| Frontend UI lib | React Admin | 5.x |
| DB | PostgreSQL | 16 |
| Testing (backend) | JUnit 5 + Testcontainers | pinned |
| Testing (frontend) | Playwright | pinned |
| Deploy | Docker + docker-compose | latest |
| Eval harness | Python + matplotlib + tabulate + numpy (bootstrap CIs) | pinned |
| Logging | Structured JSONL | stdlib |

**Why one fixed model:** mixing models across strategies confounds the comparison. A second model is an optional replication in Phase 2.

**Version-check rule:** verify framework versions and support status again before Week 2 starts; they move.

## 12. Repo Structure

```
mortar/
├── README.md                  # chart + results table front and center
├── PROJECT_CONTEXT.md         # this document
├── docs/
│   ├── module-spec.md
│   ├── blueprint-spec.md
│   ├── benchmark-design.md
│   └── results.md
├── orchestrator/              # Python
│   ├── mortar/
│   │   ├── agents/
│   │   │   ├── planner.py
│   │   │   └── composer.py
│   │   ├── engine/
│   │   │   ├── blueprint_validator.py
│   │   │   ├── selector.py        # deterministic
│   │   │   ├── module_resolver.py
│   │   │   ├── renderer.py
│   │   │   └── assembler.py
│   │   ├── registry/
│   │   │   ├── index.py           # SQLite + FTS5
│   │   │   └── loader.py
│   │   ├── validation/
│   │   │   ├── build.py
│   │   │   ├── test.py
│   │   │   ├── smoke.py
│   │   │   └── repair.py
│   │   ├── events.py              # JSONL event log
│   │   └── cli.py
│   └── pyproject.toml
├── registry/                  # 6 modules
├── templates/react-admin-v1/
├── mcp-server/server.py
├── evaluator/
│   ├── benchmark/
│   │   ├── prompts/           # 8 prompts
│   │   ├── oracle/            # hand-written tests per prompt
│   │   └── run.py
│   ├── baselines/
│   │   ├── single_agent.py        # Baseline A
│   │   ├── multi_agent_naive.py   # Baseline B
│   │   └── single_agent_skills.py # Baseline C
│   ├── stats/bootstrap.py
│   └── report/{results.md, coverage_ablation.png}
└── .github/workflows/ci.yml
```

## 13. Metrics & Evaluation Design

### Strategies

| Strategy | Description | What it isolates |
|---|---|---|
| **Baseline A** | Single agent, one large prompt, generates whole app | Monolithic generation |
| **Baseline B** | Planner + Coder, **no registry** | Multi-agent overhead without reuse |
| **Baseline C** | Single agent given the **same `SKILL.md` files** and module artifacts as tools, but **no deterministic selector/assembler** | Whether the gain comes from *composition* or just from *having skills* |
| **Ours** | Planner + deterministic Selector + Composer + Assembler + registry | Full system |

All four use the **same model, same temperature, same repair budget (≤2)**.

### Controlled coverage ablation (replaces uncontrolled reuse scatter)

"Ours" is run at registry sizes **k ∈ {0, 2, 4, 6}** modules on the *same* prompts. Module subsets are chosen by a fixed, pre-registered order (and one alternate order to check order sensitivity). Capabilities with no module become generation work (§7).

- **x-axis:** *coverage* = fraction of a prompt's required capabilities served by selected modules (controlled by k).
- **Also reported:** realized reuse rate (below), as a secondary axis.

### Seeds & statistics

- **3 seeds per cell** (5 if budget allows on a subset). Report mean and **95% bootstrap CI**. No claim is made from a difference smaller than its CI.
- Run count: 8 prompts × (A, B, C, Ours@{0,2,4,6}) = 7 configs × 3 seeds = **168 runs**.
- **Budget gate:** run a 1-seed pilot first, compute total projected cost, and set a hard cost cap in `run.py` before the full benchmark.
- **First-pass success** is a binary per run; report it as a rate with Wilson/bootstrap intervals, and say plainly that n is small.

### Metrics per run

```python
{
  "prompt_id": "crm-01",
  "strategy": "ours",
  "registry_k": 6,
  "seed": 1,
  "model": "<pinned model id>",
  "tokens": {"planning": 1200, "retrieval": 800, "generation": 8400, "repair": 2100},
  "cost_usd": 0.0234,
  "build_success": True,
  "tests_passed": 47,
  "tests_total": 50,
  "first_pass_success": True,
  "repair_iterations": 0,
  "generation_time_s": 142,
  "coverage": 0.83,
  "reuse_rate_loc": 0.72,
  "reuse_rate_tokens": 0.61,
  "reused_loc": 4820,
  "generated_loc": 1870,
  "modules_selected": ["authentication.jwt", "authorization.rbac"],
  "reuse_violations": 0
}
```

### Reuse metrics (two, to avoid inflation)

```
reuse_rate_loc    = reused_module_LOC / (reused_module_LOC + generated_LOC)
reuse_rate_tokens = tokens_of_reused_code_equivalent / (that + generated_tokens)
```

`reused_module_LOC` counts only source lines of modules actually wired into this app. LOC is easily inflated by one large module, so the token-weighted version is reported alongside it and the README states which one each claim uses.

### Charts

1. **Coverage ablation:** x = coverage (k = 0/2/4/6), y = total tokens (and separately cost), one line each for A, B, C, Ours, with CI bands. The crossover, if any, is where Ours drops below the best baseline, and it may be at ≈0, which is a result.
2. **First-pass success vs coverage**, same layout.
3. **Per-prompt table** of all metrics (in `results.md`).

## 14. Benchmark Dataset — 8 Prompts

All internal-tool. Each prompt has: (a) prose description, (b) hand-written acceptance criteria, (c) hand-written JUnit + Playwright tests (the oracle, **never AI-generated**).

| # | ID | Domain | Key entities | Capabilities exercised |
|---|---|---|---|---|
| 1 | crm-01 | Customer CRM | Contact, Deal, Note | auth, rbac, audit, crud, csv |
| 2 | expense-02 | Expense tracker | Expense, Category, Approval | auth, rbac, audit, crud |
| 3 | inventory-03 | Inventory | Item, Stock, Location | auth, rbac, crud, csv |
| 4 | saas-admin-04 | SaaS admin | User, Plan, Invoice (read-only) | auth, rbac, audit, crud |
| 5 | ats-05 | Applicant tracking | Candidate, Stage, Interview | auth, rbac, crud, audit |
| 6 | asset-06 | IT asset registry | Asset, Assignment, Owner | auth, rbac, crud, csv, audit |
| 7 | vendor-07 | Vendor directory | Vendor, Contract, Contact | auth, rbac, crud |
| 8 | timesheet-08 | Timesheets | Project, Timesheet, Entry | auth, rbac, crud, csv |

**Why 8:** every prompt uses ≥3 of the 6 modules, and oracle-writing (JUnit + Playwright by hand) is the real cost. Two prompts from v1.0 that depended on rate-limiting were dropped with that module.

**Prompt freeze rule:** prompts and oracles are frozen *before* any "Ours" run. No tuning prompts after seeing results.

## 15. Deliverables

| # | Artifact | Purpose |
|---|---|---|
| 1 | Working CLI (`mortar generate "..."`) | Demo |
| 2 | 8 prompts × 7 configs × 3 seeds benchmark, full metrics | Evidence |
| 3 | `results.md` with metrics table + CIs | Resume link |
| 4 | `coverage_ablation.png` | The story |
| 5 | 60-second demo video (baseline vs. ours, side by side) | LinkedIn / GitHub |
| 6 | MCP server usable from Claude Code / Cursor | Ecosystem credibility |
| 7 | Registry modules usable as standard Agent Skills in other agents | Ecosystem credibility |
| 8 | Structured event log for one run (JSONL) | Governance / observability story |
| 9 | README: chart + results table + architecture diagram | Recruiter entry point |

## 16. Nine-Week Plan

| Week | Focus | Exit criterion |
|---|---|---|
| 1 | Specs + Baseline A | `module-spec.md`, `blueprint-spec.md`, `benchmark-design.md`, 8 prompts + acceptance criteria, Baseline A runs on `crm-01` |
| 2 | Registry (part 1) | `authentication.jwt`, `authorization.rbac`, `crud.pagination` built, each with passing tests + `SKILL.md` ≤400 tokens |
| 3 | Registry (part 2) + template | `audit.logging`, `export.csv`, `deployment.docker`, React template; CI validates metadata + skill budget |
| 4 | Pipeline + MCP | Planner → Selector → Composer → Assembler → buildable app on 3 prompts; MCP server live |
| 5 | Validation + repair + oracles | Docker build + JUnit + Playwright smoke + bounded repair; reuse-violation detector; **all 8 oracles written and frozen** |
| 6 | Baselines B & C + pilot | All strategies runnable; 1-seed pilot; cost projection; model pinned |
| 7 | Benchmark | 168 runs complete, all metrics captured, JSONL logs stored |
| 8 | Analysis | Bootstrap CIs, charts, failure-mode analysis, limitations |
| 9 | Report + polish | `results.md`, demo video, blog post, README final |

**Cut order if behind schedule:** (1) seeds 3 → 2 on non-pilot prompts, (2) drop `export.csv` and its dependent prompts' CSV criteria, (3) drop Baseline B (keep C). Never cut oracles or CIs.

## 17. Success Criteria (Definition of Done)

Done when all are true:
- ✅ 6 modules build and pass their own tests in isolation
- ✅ `mortar generate` produces a working app for all 8 prompts (at k = 6)
- ✅ All benchmark runs completed, all metrics populated (no TBDs)
- ✅ Coverage-ablation chart exists with CIs and is interpretable, even if the answer is "no crossover" or "crossover ≈ 0"
- ✅ Baseline C result reported, whatever it shows
- ✅ MCP server responds to `search_components("auth")` from Claude Code
- ✅ Registry `SKILL.md` files validate against the open Agent Skills spec
- ✅ README has real numbers with sample sizes and intervals
- ✅ Limitations documented (at minimum: small registry, one stack, one model, 8 prompts, modules authored by the same person who wrote the benchmark)

Not done if:
- ❌ Any metric is estimated rather than measured
- ❌ The benchmark uses AI-generated tests
- ❌ Prompts or oracles were changed after seeing results
- ❌ The only evidence is a demo video
- ❌ The README states a percentage without n and a CI

## 18. Risks & Mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Crossover is trivially ≈0 (modules are boilerplate) | Medium | Report it honestly; add Baseline C and token-weighted reuse so the result is still informative |
| Baseline C matches or beats Ours | Medium | That is a finding; report it. Differentiator becomes determinism/reliability (variance, reuse violations), not tokens |
| Run cost blows up (168 runs + repairs) | Medium | Pilot + hard cost cap; cut order in §16 |
| Oracle-writing takes longer than modules | High | Frozen by end of Week 5; prompts reduced to 8 |
| Composition conflicts eat weeks | High | provides/requires matching from day 1; repair capped at 2 |
| LLM duplicates module functionality | Medium | Post-generation reuse-violation check |
| Benchmark overfits to our own modules | Medium | Hand-written oracles, frozen prompts, limitation stated in README |
| Framework versions drift mid-project | Low | Pin everything; recheck at Week 2 |
| Scope creep (MCP extras, Kafka, K8s) | High | Non-goals list is the fence |

## 19. Positioning (Resume + Narrative)

**Framing:** "Deterministic composition with LLM-guided planning," backed by a **controlled benchmark** that reports results with confidence intervals. The benchmark and ablation are the differentiator, not the multi-agent label.

**Adjacent work to read and cite (read before citing; verify claims against the papers):**
- **Code Primitives / LEGO** (arXiv 2610.09079): reusable executable components with interface contracts and tests, 522-task benchmark. Closest prior work. It reports adapted primitives outperform unchanged ones, which challenges our vendored-module design. Our scoped contribution: a controlled coverage ablation on a fixed internal-tool domain, plus a skills-only baseline. Do **not** claim a gap until the paper's cost/coverage analysis has been read in full.
- **Blueprint First, Model Second** (arXiv 2508.02721): expert-defined blueprint executed by a deterministic engine, LLM for bounded sub-tasks.
- **Agent Skills** (open standard, donated to the Agentic AI Foundation, Oct 2026): our `SKILL.md` format.
- **MCP**: registry-as-MCP; cite current ecosystem numbers from a dated source, not the late-2025 figures.

**Resume line (fill after Week 7, with n and CIs):**
> Built a deterministic composition engine with LLM-guided planning that assembles Spring Boot + React internal tools from a tested module registry. Benchmarked against three baselines (8 prompts × 3 seeds, controlled registry-coverage ablation): X% [CI] lower token cost and first-pass build success Y% → Z% at full coverage; coverage threshold for break-even: W. Open-sourced the registry as MCP server and standard Agent Skills.

**Keywords this project legitimately earns:** Spring Boot 4, Java 21, MCP, Agent Skills (SKILL.md), LLM evaluation / ablation study, deterministic orchestration, structured outputs, Testcontainers, Playwright, Docker.

## 20. What We Build First (Next Session)

Week 1, in this order:
1. `docs/module-spec.md`: JSON schema + `SKILL.md` template + CI token budget
2. `docs/blueprint-spec.md`: YAML schema + validation rules (incl. *uncovered* capability semantics)
3. `docs/benchmark-design.md`: 8 prompts + acceptance criteria + metric definitions + ablation orders + seed/CI plan + prompt-freeze rule
4. `evaluator/baselines/single_agent.py`: Baseline A running on `crm-01`
