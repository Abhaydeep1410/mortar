package dev.mortar.oracle.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Helpers shared by the per-prompt suites. */
public final class Checks {
    private Checks() {}

    /** A token that is unique per call, lowercase alphanumeric, safe in names, emails and `q=` searches. */
    public static String unique() {
        return "zq" + Long.toString(System.nanoTime(), 36);
    }

    public static Resp created(Resp r, String what) {
        assertEquals(201, r.status(), "creating " + what + " should return 201 Created, got: " + r);
        return r;
    }

    /** GET a list and require 200. `query` has no leading `?`. */
    public static Resp list(String path, String user, String query) {
        Resp r = Api.get(path + (query.isEmpty() ? "" : "?" + query), Api.as(user));
        assertEquals(200, r.status(), "GET " + path + "?" + query + " should return 200, got: " + r);
        return r;
    }

    public static Long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    public static Set<Long> ids(Resp list) {
        Set<Long> ids = new HashSet<>();
        for (Map<String, Object> row : list.content()) ids.add(asLong(row.get("id")));
        return ids;
    }

    /** Does the audit-log page contain a record for this entity (lowercase singular name), id and action? */
    public static boolean hasAudit(Resp log, String entity, long entityId, String action) {
        for (Map<String, Object> e : log.content()) {
            if (entity.equalsIgnoreCase(String.valueOf(e.get("entity"))) && Long.valueOf(entityId).equals(asLong(e.get("entityId"))) && action.equals(e.get("action"))) return true;
        }
        return false;
    }

    /** The audit record for this entity, id and action, or null. */
    public static Map<String, Object> auditEntry(Resp log, String entity, long entityId, String action) {
        for (Map<String, Object> e : log.content()) {
            if (entity.equalsIgnoreCase(String.valueOf(e.get("entity"))) && Long.valueOf(entityId).equals(asLong(e.get("entityId"))) && action.equals(e.get("action"))) return e;
        }
        return null;
    }
}
