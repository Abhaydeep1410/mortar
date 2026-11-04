# Decisions

Short records of choices that are costly to rediscover. Add one when a decision changes scope, method or interfaces. Format: what, why, consequence. Newest last.

**D1. Spring Boot 4.1.x, Java 21.** Boot 3.2 is out of open-source support; a deliberately EOL stack would look careless. Re-verify the exact patch before Week 2 module work.

**D2. Two LLM agents, deterministic Selector.** The Planner emits capability IDs from a closed vocabulary, so choosing modules is a lookup plus a compatibility check; an LLM would add cost and noise. An LLM-Selector variant is parked as a Phase 2 ablation.

**D3. Controlled coverage ablation instead of a reuse-rate scatter.** Reuse rate is an outcome, not a controlled variable. Running "Ours" at registry sizes k = 0/2/4/6 on the same prompts gives a real x-axis. Orders are pre-registered (`vocab.py`).

**D4. Baseline C (skills only, no deterministic composer).** Without it we cannot tell whether gains come from composition or merely from having skills.

**D5. Registry cut from 9 to 6 modules; prompts from 10 to 8.** Oracle writing is the real cost. `observability.actuator` and `database.flyway` are provided by `deployment.docker`. Rate-limit is Phase 2.

**D6. "Uncovered" capabilities are valid.** A capability in the vocabulary with no registry module is not an error; it becomes generation work. This is what makes the ablation possible. (Changed from the first draft, which rejected them.)

**D7. One pinned model for every strategy; independent repetitions.** Mixing models confounds the comparison. Seeds mean repetition index only, because providers do not guarantee seeded determinism.

**D8. Frozen blueprint for the main comparison.** One Planner run per prompt, hashed and committed, so differences between k levels are not Planner noise. A live-Planner run is secondary.

**D9. Shared application contract.** Every prompt carries the same externally visible API/UI contract (ports, login, REST shape, seeded users) so black-box oracles can score any strategy. Source of truth: `docs/benchmark-design.md` §5; Baseline A loads it from there.

**D10. Invalid runs are re-run, never imputed.** Missing usage data, missing cost, truncated output or unsafe paths mark a run invalid. No estimated metrics anywhere.

**D11. Global `*` permission is rejected when any entity is read-only.** `*` would grant writes to it; roles must list permissions explicitly. Enforced by `E_PERMISSION_READONLY`.

**D12. SKILL.md follows the open Agent Skills format.** Frontmatter `name` (module id with `.` → `-`) and `description`, body ≤ 400 tokens, fixed section order, mandatory "Do NOT" line. Keeps modules usable in other agents and feeds the reuse-violation checker.

**D13. Agent handoff files.** `CLAUDE.md` (entry point, rules, commands), `docs/PROGRESS.md` (status, next steps), this file (why). Purpose: a new agent reads about 150 lines instead of the whole repo.

**D14. The JWT module owns login; the app supplies users.** The module provides the token service, `POST /api/auth/login`, the password encoder and the only `SecurityFilterChain`. The app (Composer) supplies a `UserDetailsService` and seeded users. No database dependency in the module, so it stays testable without PostgreSQL. Trade-off: the Composer still writes the user entity and store.

**D15. Module tests live in `tests/java/`, wired by `testSourceDirectory`.** Keeps the registry layout from `module-spec.md` (`module/` = shipped code, `tests/` = proof) while `mvn verify` still runs from `module/`.

**D16. Spring Boot pinned to 4.1.1 in module POMs.** 4.1.0 (2026-06-10) built green first; 4.1.1 (2026-08-20) was the latest patch found on 2026-11-03. Module POMs import the Boot BOM instead of using a parent.

**D17. The JWT filter is not a Spring bean.** A `Filter` bean is also registered by the servlet container, so it would run twice. It is created inside the `SecurityFilterChain` bean method instead. Extra rules go through the `SecurityConfigurer` hook, which runs before the base rules (`anyRequest().authenticated()` must come last).

**D18. CORS lives in the JWT module for now.** The frontend (port 3000) calls the backend (port 8080) from a browser, so the first module that touches the filter chain must allow it. Origins are configurable (`allowed-origins`). The CORS source is built inside the chain method and is **not a bean**: Spring MVC registers `HandlerMappingIntrospector`, which is itself a `CorsConfigurationSource`, so a bean of that type from this module is skipped by `@ConditionalOnMissingBean` and the chain gets the MVC one, which has no CORS settings for `/api/**` (CI runs 2 and 3: preflight returned 200 with no headers). Revisit when `deployment.docker` exists.

**D19. RBAC works from URL shape, not annotations; modules depend on each other as Maven artifacts.** `authorization.rbac` turns `roles` and a `path -> resource` list into ordered `requestMatchers` rules (GET/HEAD read, POST/PUT/PATCH write, DELETE delete, `GET .../export.csv` export, then a deny-all per resource), added through the JWT module's `SecurityConfigurer` hook. Reason: deterministic, testable without controllers, and the Composer cannot forget an annotation. Trade-off: it only protects the resources listed in config, and `write` does not imply `read`. It depends on `com.mortar:jwt-auth`, so CI uses `mvn install` and relies on directory order (`authentication.jwt` before `authorization.rbac`). The Assembler will have to supply those jars (Week 4).

**D20. `crud.pagination` is a base class the app subclasses, over Spring Data JPA.** `CrudController<T, R>` implements the contract's list, get, create, update and delete once; the app adds a one-line subclass per entity plus the entity and repository. Choices: `Long id`; POST 201, PUT 200, DELETE 204, unknown id 404 (added to `benchmark-design.md` section 5 before oracle freeze); `size` above the maximum and sort fields outside a per-entity whitelist return 400 instead of being clamped or passed to the database; `q` is a case-insensitive plain-text contains with LIKE wildcards escaped; unsorted lists order by `id` so paging is stable. `sort` is read from the raw parameter map because Spring would split `name,desc` into two values. The error advice applies only to `CrudController` subclasses so it cannot change other modules' responses. Trade-off: it needs a JPA datasource, so the module cannot be used on a non-JPA app.

**D21. Audit is a request filter inside the security chain, written synchronously, keyed on URL shape.** `audit.logging` watches `POST /api/<path>`, `PUT` and `DELETE /api/<path>/<id>` for the configured paths and records a row only after a 2xx answer from a signed-in user. It sits after the authorization filter, so rejected requests never reach it. The new id on create is read from the `Location` header (so `crud.pagination` now returns one). Why not `@Audited` annotations or AOP: they require every controller to opt in and would tie the module to `crud.pagination`; a filter works for any controller. Why synchronous (the context doc said async): an async writer can lose records on shutdown and makes black-box oracles poll; at internal-tool volume a single insert is cheap. Known trade-off: the insert happens after the handler, so a failed insert is logged (`AUDIT RECORD LOST`) but the client has already received its 2xx; the change and its audit row are not one transaction. The module adds its package through `@AutoConfigurationPackage` so `AuditLog` is scanned without disabling the app's own repository scanning. Schema: `audit_log` is created by Hibernate in v1; `deployment.docker` will have to own the migration (open item for that module).
