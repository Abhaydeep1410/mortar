package dev.mortar.oracle.reference;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reference app for prompt expense-02 (see ContractServer). Run:
 * java -cp target/test-classes dev.mortar.oracle.reference.ExpenseReferenceServer [port]
 * Bugs (-Dreference.bugs=): allowZeroAmount (02.2), trustSubmittedBy (02.2), statusEditable (02.3), approvalNoEffect (02.4),
 * noConflictOnSecondApproval (02.5), employeeSeesAll (02.6), employeeCanApprove (02.7), employeeDeletesCategory (02.7),
 * totalWrong (02.8), noAuditOnDelete (02.9, categories).
 */
public final class ExpenseReferenceServer extends ContractServer {
    public static void main(String[] args) throws IOException {
        new ExpenseReferenceServer().start(portFrom(args));
    }

    @Override
    protected List<Entity> entities() {
        return List.of(
                Entity.of("expense", "expenses", List.of("title", "amount", "categoryId", "submittedBy", "status", "submittedAt"), Set.of("title", "amount", "categoryId")),
                Entity.of("category", "categories", List.of("name", "budgetLimit"), Set.of("name", "budgetLimit")),
                Entity.of("approval", "approvals", List.of("expenseId", "approverId", "decision", "comment", "decidedAt"), Set.of("expenseId", "decision", "comment")));
    }

    @Override
    protected Map<String, Long> userIds() {
        return Map.of("admin", 1L, "manager", 2L, "employee", 3L);
    }

    @Override
    protected Map<String, Set<String>> permissions() {
        return Map.of(
                "admin", Set.of("*"),
                "manager", Set.of("expense:read", "expense:write", "category:read", "approval:read", "approval:write"),
                "employee", Set.of("expense:read", "expense:write", "category:read"));
    }

    @Override
    protected boolean allowed(String user, String entity, String action) {
        if (bug("employeeCanApprove") && user.equals("employee") && entity.equals("approval")) return true;
        if (bug("employeeDeletesCategory") && user.equals("employee") && entity.equals("category") && (action.equals("write") || action.equals("delete"))) return true;
        return super.allowed(user, entity, action);
    }

    @Override
    protected String auditBugEntity() {
        return "category";
    }

    @Override
    protected void seed() {
        for (String name : List.of("Travel", "Equipment")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("budgetLimit", 5000);
            insert("categories", row);
        }
    }

    @Override
    protected void validate(Entity e, Map<String, Object> in, Map<String, Object> current, String user) {
        if (e.name().equals("expense")) {
            if (!(in.get("amount") instanceof Number n) || (n.doubleValue() <= 0 && !bug("allowZeroAmount"))) throw new Bad(400, "amount must be greater than zero");
        } else if (e.name().equals("approval") && current == null) {
            String decision = String.valueOf(in.get("decision"));
            if (!decision.equals("APPROVED") && !decision.equals("REJECTED")) throw new Bad(400, "decision must be APPROVED or REJECTED");
            Map<String, Object> expense = in.get("expenseId") instanceof Number id ? find("expenses", id.longValue()) : null;
            if (expense == null) throw new Bad(400, "unknown expense");
            if (!"PENDING".equals(expense.get("status")) && !bug("noConflictOnSecondApproval")) throw new Bad(409, "expense already decided");
        }
    }

    @Override
    protected void prepare(Entity e, Map<String, Object> row, Map<String, Object> in, String user) {
        if (e.name().equals("expense")) {
            row.put("status", "PENDING");
            row.put("submittedBy", bug("trustSubmittedBy") && in.get("submittedBy") != null ? in.get("submittedBy") : userId(user));
            row.put("submittedAt", Instant.now().toString());
        } else if (e.name().equals("approval")) {
            row.put("approverId", userId(user));
            row.put("decidedAt", Instant.now().toString());
        }
    }

    @Override
    protected void afterCreate(Entity e, Map<String, Object> row, String user) {
        if (e.name().equals("approval") && !bug("approvalNoEffect")) {
            find("expenses", asLong(row.get("expenseId"))).put("status", row.get("decision"));
        }
    }

    @Override
    protected void update(Entity e, Map<String, Object> row, Map<String, Object> in, String user) {
        if (e.name().equals("expense") && bug("statusEditable") && in.get("status") != null) row.put("status", in.get("status"));
    }

    @Override
    protected boolean visible(Entity e, Map<String, Object> row, String user) {
        if (e.name().equals("expense") && user.equals("employee") && !bug("employeeSeesAll")) return userId(user) == asLong(row.get("submittedBy"));
        return true;
    }
}
