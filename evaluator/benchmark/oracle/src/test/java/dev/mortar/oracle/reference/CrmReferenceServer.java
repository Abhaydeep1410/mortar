package dev.mortar.oracle.reference;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reference app for prompt crm-01 (see ContractServer). Run:
 * java -cp target/test-classes dev.mortar.oracle.reference.CrmReferenceServer [port]
 * Bugs (-Dreference.bugs=): skipTransitionCheck (crm-01.5), trustAuthorId (01.6), noAuditOnDelete (01.8),
 * viewerCanWrite (01.2), csvOpenToViewer (01.7), qCaseSensitive (01.4), totalWrong (01.3).
 */
public final class CrmReferenceServer extends ContractServer {
    private static final List<String> STAGES = List.of("LEAD", "QUALIFIED", "PROPOSAL", "WON");

    public static void main(String[] args) throws IOException {
        new CrmReferenceServer().start(portFrom(args));
    }

    @Override
    protected List<Entity> entities() {
        return List.of(
                Entity.of("contact", "contacts", List.of("name", "email", "company", "phone"), Set.of("name", "email", "company", "phone"))
                        .withCsv().searching(List.of("name", "email", "company")),
                Entity.of("deal", "deals", List.of("title", "value", "stage", "contactId", "closeDate"), Set.of("title", "value", "stage", "contactId", "closeDate")),
                Entity.of("note", "notes", List.of("body", "contactId", "authorId", "createdAt"), Set.of("body", "contactId")));
    }

    @Override
    protected Map<String, Long> userIds() {
        return Map.of("admin", 1L, "sales_rep", 2L, "viewer", 3L);
    }

    @Override
    protected Map<String, Set<String>> permissions() {
        return Map.of(
                "admin", Set.of("*"),
                "sales_rep", Set.of("contact:read", "contact:write", "contact:export", "deal:read", "deal:write", "note:*"),
                "viewer", Set.of("contact:read", "deal:read", "note:read"));
    }

    @Override
    protected boolean allowed(String user, String entity, String action) {
        if (bug("viewerCanWrite") && user.equals("viewer") && action.equals("write")) return true;
        if (bug("csvOpenToViewer") && action.equals("export")) return allowed(user, entity, "read");
        return super.allowed(user, entity, action);
    }

    @Override
    protected void validate(Entity e, Map<String, Object> in, Map<String, Object> current, String user) {
        if (!e.name().equals("deal")) return;
        String next = String.valueOf(in.get("stage"));
        if (!STAGES.contains(next) && !next.equals("LOST")) throw new Bad(400, "unknown stage");
        if (current == null || bug("skipTransitionCheck")) return;
        String now = String.valueOf(current.get("stage"));
        if (now.equals(next)) return;
        boolean open = STAGES.contains(now) && !now.equals("WON");
        boolean forward = open && STAGES.indexOf(next) == STAGES.indexOf(now) + 1;
        boolean lost = open && next.equals("LOST");
        if (!(forward || lost)) throw new Bad(400, "invalid stage transition " + now + " -> " + next);
    }

    @Override
    protected void prepare(Entity e, Map<String, Object> row, Map<String, Object> in, String user) {
        if (!e.name().equals("note")) return;
        row.put("authorId", bug("trustAuthorId") && in.get("authorId") != null ? in.get("authorId") : userId(user));
        row.put("createdAt", Instant.now().toString());
    }
}
