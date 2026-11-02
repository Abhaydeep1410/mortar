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
