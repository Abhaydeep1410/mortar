package dev.mortar.oracle.expense;

import static dev.mortar.oracle.harness.Checks.auditEntry;
import static dev.mortar.oracle.harness.Checks.created;
import static dev.mortar.oracle.harness.Checks.hasAudit;
import static dev.mortar.oracle.harness.Checks.ids;
import static dev.mortar.oracle.harness.Checks.list;
import static dev.mortar.oracle.harness.Checks.unique;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mortar.oracle.harness.Api;
import dev.mortar.oracle.harness.OracleBase;
import dev.mortar.oracle.harness.Resp;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * API oracle for benchmark prompt expense-02 (evaluator/benchmark/prompts/expense-02.yaml, criteria expense-02.1 to
 * expense-02.9). Black box: only the application contract (docs/benchmark-design.md section 5) and the seeded users
 * admin, manager and employee. expense-02.10 (UI smoke) is the Playwright oracle.
 */
@Tag("expense-02")
class ExpenseApiOracle extends OracleBase {
    private static final String ADMIN = "admin";
    private static final String MANAGER = "manager";
    private static final String EMPLOYEE = "employee";
    private static final long SPOOFED_ID = 987654L;

    // ---------------------------------------------------------------- helpers

    private static Long seededCategoryId;

    /** The id of a category seeded at first start ("at least two categories exist"). */
    private static synchronized long categoryId() {
        if (seededCategoryId == null) {
            Resp r = list("/api/categories", ADMIN, "size=100");
            assertTrue(r.content().size() >= 2, "at least two categories must exist on first start, found " + r.content().size());
            seededCategoryId = ((Number) r.content().get(0).get("id")).longValue();
        }
        return seededCategoryId;
    }

    private static Map<String, Object> expense(Object amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", "Expense " + unique());
        m.put("amount", amount);
        m.put("categoryId", categoryId());
        m.put("submittedAt", Instant.now().toString());
        return m;
    }

    private static Resp createExpense(String user) {
        return created(Api.post("/api/expenses", Api.as(user), expense(25.5)), "an expense as " + user);
    }

    private static Map<String, Object> approval(long expenseId, String decision) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("expenseId", expenseId);
        m.put("approverId", 1);
        m.put("decision", decision);
        m.put("comment", "Reviewed " + unique());
        m.put("decidedAt", Instant.now().toString());
        return m;
    }

    private static Map<String, Object> category() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Category " + unique());
        m.put("budgetLimit", 1000);
        return m;
    }

    private static String statusOf(long expenseId) {
        Resp r = Api.get("/api/expenses/" + expenseId, Api.as(ADMIN));
        assertEquals(200, r.status(), r.toString());
        return r.str("status");
    }

    // ---------------------------------------------------------------- expense-02.1 authentication

    @Test
    @Tag("expense-02.1")
    void loginWithValidCredentialsReturnsAToken() {
        Resp r = Api.post("/api/auth/login", null, Map.of("username", ADMIN, "password", "Admin#12345"));
        assertEquals(200, r.status(), r.toString());
        assertFalse(r.str("token") == null || r.str("token").isBlank(), "login response must contain a token: " + r);
    }

    @Test
    @Tag("expense-02.1")
    void loginWithWrongPasswordIsRejected() {
        Resp r = Api.post("/api/auth/login", null, Map.of("username", EMPLOYEE, "password", "wrong-password"));
        assertEquals(401, r.status(), r.toString());
    }

    @Test
    @Tag("expense-02.1")
    void protectedEndpointsRequireAToken() {
        assertEquals(401, Api.get("/api/expenses", null).status());
        assertEquals(401, Api.get("/api/categories", null).status());
        assertEquals(401, Api.get("/api/approvals", null).status());
    }

    // ---------------------------------------------------------------- expense-02.2 creating expenses

    @Test
    @Tag("expense-02.2")
    void anAmountThatIsNotPositiveIsRejected() {
        String token = Api.as(EMPLOYEE);
        for (Object amount : List.of(0, -5, -0.01)) {
            Resp r = Api.post("/api/expenses", token, expense(amount));
            assertEquals(400, r.status(), "amount " + amount + " must be rejected with 400, got: " + r);
        }
    }

    @Test
    @Tag("expense-02.2")
    void aNewExpenseIsPendingAndSubmittedByTheCaller() {
        Map<String, Object> spoofed = expense(25.5);
        spoofed.put("submittedBy", SPOOFED_ID);
        Resp first = created(Api.post("/api/expenses", Api.as(EMPLOYEE), spoofed), "an expense");
        Resp second = createExpense(EMPLOYEE);
        Resp byManager = createExpense(MANAGER);

        assertEquals("PENDING", first.str("status"));
        assertEquals("PENDING", byManager.str("status"));
        assertNotEquals(SPOOFED_ID, first.num("submittedBy"), "submittedBy must be the caller, whatever the body says");
        assertEquals(first.num("submittedBy"), second.num("submittedBy"), "the same caller is the same submitter");
        assertNotEquals(first.num("submittedBy"), byManager.num("submittedBy"), "different callers are different submitters");
        assertEquals("PENDING", statusOf(first.num("id")));
    }

    // ---------------------------------------------------------------- expense-02.3 status is not directly editable

    @Test
    @Tag("expense-02.3")
    void aDirectStatusChangeIsIgnoredOrRejected() {
        String token = Api.as(EMPLOYEE);
        long id = createExpense(EMPLOYEE).num("id");
        for (String target : List.of("APPROVED", "REJECTED")) {
            Map<String, Object> body = expense(25.5);
            body.put("status", target);
            Resp r = Api.put("/api/expenses/" + id, token, body);
            assertTrue(r.status() == 200 || r.status() == 400, "a direct status change must be ignored (200) or rejected (400), got: " + r);
            assertEquals("PENDING", statusOf(id), "the status must be unchanged after a direct PUT with status " + target);
        }
    }

    // ---------------------------------------------------------------- expense-02.4 approvals decide expenses

    @Test
    @Tag("expense-02.4")
    void anApprovalSetsTheExpenseStatus() {
        long approved = createExpense(EMPLOYEE).num("id");
        long rejected = createExpense(EMPLOYEE).num("id");
        created(Api.post("/api/approvals", Api.as(MANAGER), approval(approved, "APPROVED")), "an approval");
        created(Api.post("/api/approvals", Api.as(MANAGER), approval(rejected, "REJECTED")), "a rejection");
        assertEquals("APPROVED", statusOf(approved));
        assertEquals("REJECTED", statusOf(rejected));
        assertEquals("APPROVED", Api.get("/api/expenses/" + approved, Api.as(MANAGER)).str("status"));
    }

    // ---------------------------------------------------------------- expense-02.5 one decision per expense

    @Test
    @Tag("expense-02.5")
    void aDecidedExpenseCannotReceiveAnotherApproval() {
        long approved = createExpense(EMPLOYEE).num("id");
        created(Api.post("/api/approvals", Api.as(MANAGER), approval(approved, "APPROVED")), "an approval");
        for (String again : List.of("APPROVED", "REJECTED")) {
            Resp r = Api.post("/api/approvals", Api.as(MANAGER), approval(approved, again));
            assertEquals(409, r.status(), "a second approval (" + again + ") on a decided expense must return 409, got: " + r);
        }
        assertEquals("APPROVED", statusOf(approved), "a rejected second approval must not change the status");

        long rejected = createExpense(EMPLOYEE).num("id");
        created(Api.post("/api/approvals", Api.as(MANAGER), approval(rejected, "REJECTED")), "a rejection");
        assertEquals(409, Api.post("/api/approvals", Api.as(ADMIN), approval(rejected, "APPROVED")).status());
        assertEquals("REJECTED", statusOf(rejected));
    }

    // ---------------------------------------------------------------- expense-02.6 employees see their own expenses

    @Test
    @Tag("expense-02.6")
    void anEmployeeSeesOnlyTheirOwnExpensesWithAnAccurateTotal() {
        long employeeBefore = list("/api/expenses", EMPLOYEE, "size=2").num("totalElements");
        long managerBefore = list("/api/expenses", MANAGER, "size=2").num("totalElements");

        Set<Long> mine = new HashSet<>();
        for (int i = 0; i < 3; i++) mine.add(createExpense(EMPLOYEE).num("id"));
        Set<Long> others = new HashSet<>();
        for (int i = 0; i < 2; i++) others.add(createExpense(MANAGER).num("id"));

        // Read the totals through a page smaller than the result, so a total that merely echoes the page size is caught.
        assertEquals(employeeBefore + 3, list("/api/expenses", EMPLOYEE, "size=2").num("totalElements"), "an employee's totalElements counts only their own expenses");
        Set<Long> seen = ids(list("/api/expenses", EMPLOYEE, "size=100&sort=id,desc"));
        assertTrue(seen.containsAll(mine), "the employee must see their own expenses");
        for (Long other : others) assertFalse(seen.contains(other), "the employee must not see the manager's expense " + other);

        for (String privileged : List.of(MANAGER, ADMIN)) {
            Resp all = list("/api/expenses", privileged, "size=100&sort=id,desc");
            assertEquals(managerBefore + 5, list("/api/expenses", privileged, "size=2").num("totalElements"), privileged + " sees every expense");
            assertTrue(ids(all).containsAll(mine) && ids(all).containsAll(others), privileged + " sees the employee's and the manager's expenses");
        }
    }

    // ---------------------------------------------------------------- expense-02.7 employees cannot approve or manage categories

    @Test
    @Tag("expense-02.7")
    void anEmployeeCannotCreateAnApproval() {
        long id = createExpense(EMPLOYEE).num("id");
        Resp r = Api.post("/api/approvals", Api.as(EMPLOYEE), approval(id, "APPROVED"));
        assertEquals(403, r.status(), r.toString());
        assertEquals("PENDING", statusOf(id), "a forbidden approval must not decide the expense");
    }

    @Test
    @Tag("expense-02.7")
    void anEmployeeCannotCreateOrDeleteCategories() {
        assertEquals(403, Api.post("/api/categories", Api.as(EMPLOYEE), category()).status());
        long id = created(Api.post("/api/categories", Api.as(ADMIN), category()), "a category").num("id");
        assertEquals(403, Api.delete("/api/categories/" + id, Api.as(EMPLOYEE)).status());
        assertEquals(200, Api.get("/api/categories/" + id, Api.as(ADMIN)).status(), "a forbidden delete must not remove the category");
    }

    // ---------------------------------------------------------------- expense-02.8 pagination

    @Test
    @Tag("expense-02.8")
    void expenseListsArePaginatedWithAccurateTotals() {
        long before = list("/api/expenses", ADMIN, "page=0&size=10").num("totalElements");
        for (int i = 0; i < 25; i++) createExpense(MANAGER);

        Resp first = list("/api/expenses", ADMIN, "page=0&size=10");
        assertEquals(before + 25, first.num("totalElements"), "totalElements must count every expense");
        assertEquals(10, first.content().size(), "a full page must hold `size` items");
        assertEquals(0, first.num("page"));
        assertEquals(10, first.num("size"));

        long lastPage = (before + 25 - 1) / 10;
        Resp last = list("/api/expenses", ADMIN, "page=" + lastPage + "&size=10");
        assertEquals(before + 25 - lastPage * 10, last.content().size(), "the last page holds the remainder");

        Set<Long> firstIds = ids(first);
        for (Long id : ids(list("/api/expenses", ADMIN, "page=1&size=10"))) assertFalse(firstIds.contains(id), "pages must not overlap");
    }

    @Test
    @Tag("expense-02.8")
    void sizeAbove100IsRejectedAndSizeUpTo100IsHonored() {
        assertEquals(100, list("/api/expenses", ADMIN, "size=100").num("size"));
        assertEquals(400, Api.get("/api/expenses?size=101", Api.as(ADMIN)).status());
        assertEquals(400, Api.get("/api/expenses?sort=notAField,asc", Api.as(ADMIN)).status());
    }

    // ---------------------------------------------------------------- expense-02.9 audit log

    @Test
    @Tag("expense-02.9")
    void expenseChangesAreAudited() {
        String token = Api.as(EMPLOYEE);
        long id = createExpense(EMPLOYEE).num("id");
        assertEquals(200, Api.put("/api/expenses/" + id, token, expense(30)).status());
        // The delete is done as ADMIN, who always holds every permission, so this criterion does not depend on whether
        // the prompt grants EMPLOYEE a delete permission.
        assertEquals(204, Api.delete("/api/expenses/" + id, Api.as(ADMIN)).status());

        Resp log = Api.get("/api/audit-logs?page=0&size=100", Api.as(ADMIN));
        assertEquals(200, log.status(), log.toString());
        for (String action : List.of("CREATE", "UPDATE", "DELETE")) {
            Map<String, Object> entry = auditEntry(log, "expense", id, action);
            assertTrue(entry != null, "expense " + action + " must be audited");
            assertEquals(action.equals("DELETE") ? ADMIN : EMPLOYEE, entry.get("actor"), "the actor is the authenticated user: " + entry);
            assertFalse(entry.get("timestamp") == null, "every entry has a timestamp: " + entry);
        }
    }

    @Test
    @Tag("expense-02.9")
    void categoryChangesAreAudited() {
        String token = Api.as(ADMIN);
        long id = created(Api.post("/api/categories", token, category()), "a category").num("id");
        assertEquals(200, Api.put("/api/categories/" + id, token, category()).status());
        assertEquals(204, Api.delete("/api/categories/" + id, token).status());

        Resp log = Api.get("/api/audit-logs?page=0&size=100", token);
        for (String action : List.of("CREATE", "UPDATE", "DELETE")) assertTrue(hasAudit(log, "category", id, action), "category " + action + " must be audited");
    }

    @Test
    @Tag("expense-02.9")
    void approvalChangesAreAudited() {
        long expenseId = createExpense(EMPLOYEE).num("id");
        long approvalId = created(Api.post("/api/approvals", Api.as(MANAGER), approval(expenseId, "APPROVED")), "an approval").num("id");
        // An app may refuse to edit or delete a decision; the criterion covers every *successful* change.
        Resp update = Api.put("/api/approvals/" + approvalId, Api.as(MANAGER), approval(expenseId, "APPROVED"));
        Resp delete = Api.delete("/api/approvals/" + approvalId, Api.as(MANAGER));

        Resp log = Api.get("/api/audit-logs?page=0&size=100", Api.as(ADMIN));
        Map<String, Object> create = auditEntry(log, "approval", approvalId, "CREATE");
        assertTrue(create != null, "approval CREATE must be audited");
        assertEquals(MANAGER, create.get("actor"));
        if (update.status() == 200) assertTrue(hasAudit(log, "approval", approvalId, "UPDATE"), "a successful approval update must be audited");
        if (delete.status() == 204) assertTrue(hasAudit(log, "approval", approvalId, "DELETE"), "a successful approval delete must be audited");
    }

    @Test
    @Tag("expense-02.9")
    void onlyAdminsCanReadTheAuditLog() {
        assertEquals(403, Api.get("/api/audit-logs", Api.as(EMPLOYEE)).status());
        assertEquals(403, Api.get("/api/audit-logs", Api.as(MANAGER)).status());
        assertEquals(401, Api.get("/api/audit-logs", null).status());
        assertEquals(200, Api.get("/api/audit-logs", Api.as(ADMIN)).status());
    }
}
