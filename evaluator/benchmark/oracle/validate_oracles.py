"""Validate every API oracle against its reference server (docs/DECISIONS.md D32, D33).

For each prompt: the correct reference server must pass every criterion, and each injected bug must fail
exactly the criteria listed below (a bug may legitimately fail more than one, for example a wrong
`totalElements`). A mismatch means the oracle is too weak or too strict. Run from this folder:

    python validate_oracles.py              all prompts
    python validate_oracles.py expense-02   one prompt

Needs Maven and JDK 21 on the PATH. Takes about a minute per prompt (one Maven run per case).
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
PORT = 18080

# prompt id -> (reference server class, {bug: criteria it must fail})
CASES: dict[str, tuple[str, dict[str, set[str]]]] = {
    "crm-01": ("CrmReferenceServer", {
        "viewerCanWrite": {"crm-01.2"},
        "totalWrong": {"crm-01.3"},
        "qCaseSensitive": {"crm-01.4"},
        "skipTransitionCheck": {"crm-01.5"},
        "trustAuthorId": {"crm-01.6"},
        "csvOpenToViewer": {"crm-01.7"},
        "noAuditOnDelete": {"crm-01.8"},
    }),
    "expense-02": ("ExpenseReferenceServer", {
        "allowZeroAmount": {"expense-02.2"},
        "trustSubmittedBy": {"expense-02.2"},
        "statusEditable": {"expense-02.3"},
        "approvalNoEffect": {"expense-02.4", "expense-02.5"},
        "noConflictOnSecondApproval": {"expense-02.5"},
        "employeeSeesAll": {"expense-02.6"},
        "employeeCanApprove": {"expense-02.7"},
        "employeeDeletesCategory": {"expense-02.7"},
        "totalWrong": {"expense-02.6", "expense-02.8"},
        "noAuditOnDelete": {"expense-02.9"},
    }),
    "inventory-03": ("InventoryReferenceServer", {
        "lowStockPagedShape": set(),  # a variant, not a bug: the other legitimate low-stock response shape must pass too
        "duplicateSkuAllowed": {"inventory-03.2"},
        "negativeStockOnCreate": {"inventory-03.3"},
        "negativeStockOnUpdate": {"inventory-03.3"},
        "duplicateStockAllowed": {"inventory-03.4"},
        "lowStockSkipsEmpty": {"inventory-03.5"},
        "lowStockInclusive": {"inventory-03.5"},
        "staffCanCreateItem": {"inventory-03.6"},
        "staffCanDeleteItem": {"inventory-03.6"},
        "managerCanDeleteLocation": {"inventory-03.6"},
        "totalWrong": {"inventory-03.7"},
        "csvOpenToStaff": {"inventory-03.8"},
    }),
}


def wait_healthy(seconds: float = 20) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f"http://localhost:{PORT}/actuator/health", timeout=2) as r:
                if r.status == 200:
                    return True
        except OSError:
            time.sleep(0.3)
    return False


def run_case(mvn: str, prompt: str, server: str, bugs: str, label: str) -> dict[str, bool] | None:
    out = HERE / "target" / "validate" / f"{prompt}-{label}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.unlink(missing_ok=True)
    proc = subprocess.Popen(
        ["java", f"-Dreference.bugs={bugs}", "-cp", str(HERE / "target" / "test-classes"), f"dev.mortar.oracle.reference.{server}", str(PORT)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        if not wait_healthy():
            print(f"  {server} did not start")
            return None
        subprocess.run([mvn, "-q", "test", f"-Doracle.groups={prompt}", f"-Dmortar.baseUrl=http://localhost:{PORT}",
                        f"-Dmortar.oracleResults={out}"], cwd=HERE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    finally:
        proc.terminate()
        proc.wait(timeout=10)
    if not out.exists():
        return None
    return json.loads(out.read_text(encoding="utf-8"))["by_criterion"]


def main(argv: list[str]) -> int:
    mvn = shutil.which("mvn")
    if not mvn:
        print("mvn not found on PATH")
        return 2
    wanted = argv or list(CASES)
    unknown = [p for p in wanted if p not in CASES]
    if unknown:
        print(f"unknown prompt(s): {unknown}; known: {list(CASES)}")
        return 2
    print("compiling...")
    if subprocess.run([mvn, "-q", "test-compile"], cwd=HERE).returncode != 0:
        return 2
    failures = 0
    for prompt in wanted:
        server, bugs = CASES[prompt]
        print(f"\n{prompt} ({server})")
        result = run_case(mvn, prompt, server, "", "clean")
        failed = sorted(k for k, v in (result or {}).items() if not v)
        ok = result is not None and not failed
        print(f"  {'ok  ' if ok else 'FAIL'} correct app passes every criterion" + ("" if ok else f" (failed: {failed or 'no results'})"))
        failures += not ok
        for bug, expected in bugs.items():
            result = run_case(mvn, prompt, server, bug, bug)
            failed = {k for k, v in (result or {}).items() if not v}
            ok = result is not None and failed == expected
            print(f"  {'ok  ' if ok else 'FAIL'} {bug}: fails {sorted(failed)}" + ("" if ok else f", expected {sorted(expected)}"))
            failures += not ok
    print("\nall oracles validated" if not failures else f"\n{failures} check(s) failed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
