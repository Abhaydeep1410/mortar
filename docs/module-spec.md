# Mortar Module Spec v1

A **module** is a hand-written, independently tested Spring Boot library plus the metadata an agent or the deterministic selector needs to use it without reading its source. Registry modules are never AI-generated.

## 1. Directory layout

Every module lives at `registry/<id>/` (the directory name equals `metadata.yaml#id`) and contains exactly:

```
registry/authentication.jwt/
├── SKILL.md                # AI-readable usage contract (open Agent Skills format)
├── metadata.yaml           # machine-readable; validated by schemas/module-metadata.schema.json
├── config-schema.json      # JSON Schema for the module's config block
├── module/                 # Maven module (pom.xml + src/)
├── tests/                  # module's own tests; must pass in isolation
└── examples/
    └── example-config.yaml # must validate against config-schema.json
```

CI fails if any file is missing, extra top-level files exist, or any validation below fails.

## 2. metadata.yaml

Validated against [`schemas/module-metadata.schema.json`](../schemas/module-metadata.schema.json).

| Field | Meaning |
|---|---|
| `id` | Dotted capability-style ID, e.g. `authentication.jwt`. Unique in the registry. |
| `version` | Semver `MAJOR.MINOR.PATCH`. |
| `category` | One of `authentication`, `authorization`, `observability`, `data`, `security`, `deployment`. |
| `provides` | List of `interface:` (fully-qualified Java type), `capability:` (vocabulary ID), with optional `description`. At least one `capability:` entry is required for the module to be selectable. |
| `requires` | List of `capability:` (must be provided by another selected module or the platform) and `env:` (required environment variables). |
| `spring_boot` / `java` | Supported version ranges. Targets: Spring Boot `4.1.x`, Java 21. |
| `conflicts_with` | Module or capability IDs that cannot coexist (e.g. both own the `SecurityFilterChain`). |
| `tags` | Free-text search keywords for SQLite FTS5. |

### Compatibility rules (enforced by the Selector's `check_compatibility`)
1. Every `requires.capability` is provided by a selected module, or by the platform set (`database.postgresql`).
2. No selected module appears in another selected module's `conflicts_with`.
3. The blueprint's Spring Boot and Java versions satisfy the module's ranges.
4. Every `requires.env` variable is declared in the rendered `docker-compose.yml` / `application.yml` (the Assembler's responsibility; the Selector records the list).
5. Two modules may not provide the same `interface:`.

## 3. SKILL.md

Follows the open Agent Skills format: YAML frontmatter with `name` and `description`, then Markdown. See [`examples/SKILL.example.md`](examples/SKILL.example.md).

- **Frontmatter:** `name` is the module ID with `.` replaced by `-` (`authentication-jwt`), max 64 chars, lowercase letters, digits and hyphens. `description` states what the module does and when to use it, max 1024 chars, no angle brackets.
- **Body budget:** at most **400 tokens**, enforced by `tools/check_skill_budget.py` in CI. The default counter is an approximation (characters ÷ 4); when `tiktoken` is installed it is used instead. The benchmark records the counter used.
- **Required sections, in order:** `Provides`, `Requires`, `Config`, `Integration`, `Conflicts`.
- **Content rule:** the usage contract only. No implementation detail, no prose rationale, no examples longer than a config block.
- **Must contain an explicit "Do NOT" line** for anything the module owns that an LLM might be tempted to re-implement (this feeds the reuse-violation detector).

The Composer loads this file, not the module source. That is the token-efficiency claim the benchmark measures.

## 4. config-schema.json

A JSON Schema (draft 2020-12) describing the module's config block as it appears in `blueprint.components[].config`. `examples/example-config.yaml` must validate against it. The Selector fills config values from blueprint data and validates before accepting.

## 5. module/ and tests/

- `module/` is a standalone Maven module with groupId `com.mortar`, built with Maven 3.9+ against Java 21 and Spring Boot 4.1.x.
- `tests/` contains JUnit 5 tests (Testcontainers for PostgreSQL where needed) that run with `mvn verify` from the module directory with no other module present. Isolation is a CI check.
- Modules are **opinionated wrappers** around Spring Security, Spring Data and similar libraries, not reimplementations.

## 6. Reuse-violation signatures

Each module may declare, in `metadata.yaml` (optional extension, `owns:`), the imports and interfaces generated code must not redefine. The post-generation checker flags a violation when generated Java:
- imports a library package listed in a selected module's `owns.imports`, or
- declares a type with the same simple name as a selected module's `provides.interface`, or
- defines a second `SecurityFilterChain` bean when `authentication.jwt` is selected.

(The `owns:` extension is added to the schema when the checker is built in Week 5.)

## 7. Versioning and change policy

- Modules are immutable once a benchmark run references them: the run log records `id@version` and a content hash of `module/`.
- Any change after the benchmark is frozen requires a new version and a recorded note in `docs/results.md`.

## 8. Registry v1 inventory

`authentication.jwt`, `authorization.rbac`, `audit.logging`, `crud.pagination`, `export.csv`, `deployment.docker` (also provides `database.flyway` baseline and `observability.actuator` health config). Frontend: `templates/react-admin-v1/`.
