# Mortar API oracles

Black-box tests that score a generated app against a prompt's acceptance criteria
(`../prompts/<id>.yaml`) using only the application contract (`docs/benchmark-design.md` section 5).
Decisions: D32 and D33 in `docs/DECISIONS.md`. Java 21, JUnit 5, no other dependency.

## Run

```
mvn test
mvn test -Doracle.groups=crm-01 -Dmortar.baseUrl=http://localhost:8080
```

- `mvn test` runs only the harness self-tests (tag `harness`, no server needed).
- With `-Doracle.groups=<prompt-id>` it runs that prompt's suite against a running stack and writes
  `target/oracle-results.json`: `{"passed": n, "total": m, "by_criterion": {"crm-01.1": true, ...}}`.
- `-Dmortar.healthTimeoutS=60` (default) is how long to wait for `/actuator/health`; `-Dmortar.oracleResults=path`
  changes the output file.

## Suites

| Prompt | Class | API criteria | UI criterion |
|---|---|---|---|
| crm-01 | `crm/CrmApiOracle` | crm-01.1 to .8 | crm-01.9 (Playwright, not written) |
| expense-02 | `expense/ExpenseApiOracle` | expense-02.1 to .9 | expense-02.10 (Playwright, not written) |
| inventory-03 | `inventory/InventoryApiOracle` | inventory-03.1 to .8 | inventory-03.9 (Playwright, not written) |

## Layout

- `harness/`: `Api` (HTTP, login, health wait), `Contract` (naming, seeded passwords), `Json`, `Resp`, `Checks`
  (helpers shared by suites), `OracleBase` (waits for the stack), `CriterionListener` (tags to `by_criterion`),
  `HarnessTest`.
- `reference/`: `ContractServer` (generic in-memory app that follows the contract) and one small subclass per
  prompt, used only to validate the oracles.
- `validate_oracles.py`: runs every oracle against its reference server and its injected bugs.

## Rules for writing an oracle

1. Tag the class with the prompt id and every test with its criterion id; one criterion may have several tests.
2. Assert only what the contract or the criterion text says. Anything else is a bug in the oracle.
3. Tests create their own data (unique tokens) and never depend on order or on an empty database.
4. Read totals through a page smaller than the result, so a total that only echoes the page size is caught.
5. Permissions follow the `authorization.rbac` vocabulary: POST and PUT need `write`, DELETE needs `delete`. A criterion
   that is about something else (for example auditing deletes) performs its deletes as ADMIN, so it never depends on how
   a prompt words a delete grant.
6. Where the contract leaves a response shape open (for example a custom endpoint), accept every reasonable shape and
   add a variant to the reference server to prove it.
7. Never put oracle code or text where a model can read it (no leakage).

## Validating an oracle (do this for every new suite)

```
python validate_oracles.py              all prompts
python validate_oracles.py expense-02   one prompt
```

For each prompt it starts the reference server, runs the suite, and requires every criterion to pass; then it
restarts the server with each bug in `-Dreference.bugs=` and requires exactly the listed criteria to fail. To add a
prompt: write `reference/<Prompt>ReferenceServer` (a `ContractServer` subclass with its bugs), write the suite, and add
one entry to `CASES` in `validate_oracles.py`. You can also start a server by hand:
`java -cp target/test-classes dev.mortar.oracle.reference.CrmReferenceServer 8080`.
