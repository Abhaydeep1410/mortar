# Mortar Blueprint Spec v1

The **Application Blueprint** is the contract between every stage of Mortar. The Planner writes it, the Selector annotates it, the Composer and Assembler read it. It is YAML, versioned with `apiVersion: mortar/v1`, and validated by [`schemas/blueprint.schema.json`](../schemas/blueprint.schema.json) plus the semantic rules below. Implementation: `orchestrator/mortar/engine/blueprint_validator.py`.

## 1. Shape

```yaml
apiVersion: mortar/v1
kind: Application

metadata:
  name: customer-crm              # lowercase, digits, hyphens
  prompt: "<original user prompt>"
  timestamp: 2026-11-01T10:00:00Z # optional

application:
  type: internal-tool             # only value in v1
  domain: crm

frontend:
  template: react-admin-v1        # only value in v1
  entities:
    - name: Contact               # PascalCase
      fields: [name, email, company, phone]   # camelCase, unique
    - name: Invoice
      fields: [number, amount]
      readOnly: true              # optional; no write permissions allowed
  pages:
    - { type: list,   entity: Contact }
    - { type: detail, entity: Contact }
    - { type: kanban, entity: Deal, groupBy: stage }   # groupBy must be an entity field

backend:
  framework: spring-boot
  javaVersion: 21
  springBootVersion: 4.1.x        # 4.<minor>.x or 4.<minor>.<patch>
  basePackage: com.example.crm

database:
  type: postgresql
  migrations: flyway

capabilities:                     # closed vocabulary, see §2
  - authentication.jwt
  - authorization.rbac

custom_services:                  # the app-specific remainder
  - name: DealPipelineService     # PascalCase, ends with "Service"
    responsibility: "Move deals between stages, enforce stage transition rules"
custom_controllers: []            # optional, names end with "Controller"

roles:
  - name: ADMIN                   # UPPER_SNAKE_CASE
    permissions: ["*"]
  - name: SALES_REP
    permissions: ["contact:read", "contact:write", "note:*"]

components: []                    # filled by the Selector
uncovered: []                     # filled by the Selector (§4)
```

## 2. Closed capability vocabulary

| Capability | Meaning | Registry module (v1) |
|---|---|---|
| `authentication.jwt` | Stateless JWT login | `authentication.jwt` |
| `authentication.oauth2` | OAuth2 login | none (always *uncovered*; conflicts with JWT) |
| `authorization.rbac` | Role/permission checks | `authorization.rbac` |
| `audit.logging` | Audit trail of writes | `audit.logging` |
| `crud.pagination` | Generic CRUD + paging | `crud.pagination` |
| `export.csv` | CSV export | `export.csv` |
| `deployment.docker` | Dockerfile + compose | `deployment.docker` |
| `observability.actuator` | Health/metrics endpoints | `deployment.docker` |
| `database.flyway` | Migrations baseline | `deployment.docker` |
| `security.rate-limit` | Request throttling | none in v1 (Phase 2) |

The vocabulary lives in `orchestrator/mortar/engine/vocab.py` and is the single source of truth. A capability outside this list is a validation error. A capability *inside* the list but with no registry module is valid and becomes **uncovered** work for the Composer. This is what makes the coverage ablation possible.

## 3. Validation rules

**Schema layer** (JSON Schema): required fields, enums, name patterns, `custom_services[].responsibility` non-empty and not whitespace-only, `springBootVersion` on the 4.x line, `javaVersion` 21.

**Semantic layer** (Python):

| Code | Rule |
|---|---|
| `E_CAPABILITY_UNKNOWN` | Every `capabilities` entry is in the vocabulary. |
| `E_CAPABILITY_CONFLICT` | No two capabilities conflict (`authentication.jwt` ⟂ `authentication.oauth2`). |
| `E_CAPABILITY_REQUIRES` | `authorization.rbac` requires an authentication capability; `audit.logging` requires an authentication capability (to record the actor). |
| `E_ENTITY_DUPLICATE` | Entity names are unique. |
| `E_PAGE_ENTITY` | Every `pages[].entity` is a declared entity. |
| `E_PAGE_GROUPBY` | `kanban` pages require `groupBy`, which must be a field of the entity. `groupBy` is invalid on other page types. |
| `E_PERMISSION_FORMAT` | Each permission is `*` or `<resource>:<action>`, where `<resource>` is a declared entity name lowercased and `<action>` ∈ {`read`, `write`, `delete`, `export`, `*`}. |
| `E_PERMISSION_READONLY` | A role may not hold `write`, `delete` or `<entity>:*` on a `readOnly` entity. The global `*` is also rejected whenever any entity is `readOnly`, because it would grant writes to it; list permissions explicitly instead. |
| `E_ROLE_DUPLICATE` | Role names are unique. |
| `E_SERVICE_DUPLICATE` | `custom_services` and `custom_controllers` names are unique across both lists. |
| `E_EXPORT_NEEDS_ENTITY` | `export.csv` selected ⇒ at least one role holds an `export` or `*` permission. |
| `E_RBAC_NEEDS_ROLES` | `authorization.rbac` selected ⇒ at least one role has fewer than all permissions (otherwise RBAC is vacuous). Warning only. |

Issues are structured: `{code, path, message, severity}`. The validator runs in two layers: all schema errors (`E_SCHEMA`) are returned together; the semantic rules only run once the schema layer passes, since they rely on the structure it guarantees. Each layer reports everything it finds, so the Planner's repair loop gets a complete list per pass.

> **Change from v1.0 context doc:** "Every capability must map to ≥1 registry module" is removed. Unmapped capabilities are *uncovered*, not invalid.

## 4. Selector output

The Selector (deterministic) fills:

```yaml
components:
  - module_id: authentication.jwt
    version: 1.0.0
    config:
      jwtSecretEnv: JWT_SECRET
      ttlMinutes: 60
uncovered:                        # capabilities with no module in the active registry
  - export.csv
```

`components` and `uncovered` partition `capabilities` for a given registry. In the coverage ablation (registry size k ∈ {0, 2, 4, 6}) the same blueprint yields different partitions. Coverage for a run is `1 − len(uncovered)/len(capabilities)`; capabilities satisfied by a shared module count as covered.

## 5. Planner contract

- Output via structured output (JSON schema derived from `blueprint.schema.json`). No free text.
- The Planner leaves `components: []` and `uncovered: []`.
- On validation failure the orchestrator sends the structured error list back once (counts toward the planning token budget). A second failure fails the run and is logged.

## 6. Determinism notes

- Field order in emitted YAML is fixed (the order in §1) so blueprint diffs and hashes are stable.
- The blueprint's SHA-256 is recorded in every event log entry downstream, which lets the benchmark prove all strategies at k=6 were given identical blueprints when a fixed blueprint is used (Planner-variance control, see `benchmark-design.md` §4).
