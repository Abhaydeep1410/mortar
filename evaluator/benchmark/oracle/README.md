# Mortar API oracles

Black-box tests that score a generated app against a prompt's acceptance criteria
(`../prompts/<id>.yaml`) using only the application contract (`docs/benchmark-design.md` section 5).
Decisions: D32 in `docs/DECISIONS.md`. Java 21, JUnit 5, no other dependency.

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

## Layout

- `harness/`: `Api` (HTTP, login, health wait), `Contract` (naming, seeded passwords), `Json`, `Resp`,
  `OracleBase` (waits for the stack), `CriterionListener` (tags to `by_criterion`), `HarnessTest`.
- `crm/CrmApiOracle`: criteria crm-01.1 to crm-01.8. crm-01.9 is the Playwright UI oracle (not written yet).
- `reference/CrmReferenceServer`: an in-memory app that follows the contract, for validating the oracle itself.

## Rules for writing an oracle

1. Tag the class with the prompt id and every test with its criterion id; one criterion may have several tests.
2. Assert only what the contract or the criterion text says. Anything else is a bug in the oracle.
3. Tests create their own data (unique tokens) and never depend on order or on an empty database.
4. Never put oracle code or text where a model can read it (no leakage).

## Validating an oracle (do this for every new suite)

Start the reference server, run the suite, expect every criterion to pass; then start it with each bug in
`-Dreference.bugs=` and expect exactly the targeted criterion to fail:

```
mvn test-compile
java -cp target/test-classes dev.mortar.oracle.reference.CrmReferenceServer 8080
mvn test -Doracle.groups=crm-01
```

Bugs for crm-01: `skipTransitionCheck` (01.5), `trustAuthorId` (01.6), `noAuditOnDelete` (01.8),
`viewerCanWrite` (01.2), `csvOpenToViewer` (01.7), `qCaseSensitive` (01.4), `totalWrong` (01.3).
