package dev.mortar.oracle.reference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mortar.oracle.harness.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A small in-memory implementation of the application contract for prompt crm-01, used only to check
 * the oracles themselves: a correct app must pass every criterion, and each bug below must fail the
 * criterion it targets (see ReferenceCheck in the README). Not part of the benchmark and never given to a model.
 *
 * Run: java -cp target/test-classes dev.mortar.oracle.reference.CrmReferenceServer [port]
 * Bugs: -Dreference.bugs=skipTransitionCheck,trustAuthorId,... (comma separated, see bug(...) calls).
 */
public final class CrmReferenceServer {
    private static final Set<String> BUGS = Set.of(System.getProperty("reference.bugs", "").split(","));
    private static final List<String> STAGES = List.of("LEAD", "QUALIFIED", "PROPOSAL", "WON");
    private static final Map<String, List<String>> FIELDS = Map.of(
            "contacts", List.of("name", "email", "company", "phone"),
            "deals", List.of("title", "value", "stage", "contactId", "closeDate"),
            "notes", List.of("body", "contactId", "createdAt"));
    private static final Map<String, String> SINGULAR = Map.of("contacts", "contact", "deals", "deal", "notes", "note");
    private static final Map<String, Long> USER_IDS = Map.of("admin", 1L, "sales_rep", 2L, "viewer", 3L);
    private static final Map<String, Set<String>> PERMS = Map.of(
            "admin", Set.of("*"),
            "sales_rep", Set.of("contact:read", "contact:write", "contact:export", "deal:read", "deal:write", "note:*"),
            "viewer", Set.of("contact:read", "deal:read", "note:read"));

    private final Map<String, Map<Long, Map<String, Object>>> data = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> ids = new ConcurrentHashMap<>();
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> audit = new ArrayList<>();

    private static boolean bug(String name) {
        return BUGS.contains(name);
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        CrmReferenceServer app = new CrmReferenceServer();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", ex -> {
            try {
                app.handle(ex);
            } catch (Bad b) {
                send(ex, b.status, Map.of("message", b.getMessage()));
            } catch (RuntimeException e) {
                send(ex, 500, Map.of("message", String.valueOf(e)));
            }
        });
        server.start();
        System.out.println("reference CRM on :" + port + " bugs=" + BUGS);
    }

    private static final class Bad extends RuntimeException {
        private static final long serialVersionUID = 1L;

        final int status;

        Bad(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        Map<String, String> query = query(ex.getRequestURI().getRawQuery());

        if (path.equals("/actuator/health")) {
            send(ex, 200, Map.of("status", "UP"));
            return;
        }
        if (path.equals("/api/auth/login") && method.equals("POST")) {
            Map<String, Object> body = body(ex);
            String user = String.valueOf(body.get("username"));
            if (!USER_IDS.containsKey(user) || !String.valueOf(body.get("password")).equals(Character.toUpperCase(user.charAt(0)) + user.substring(1) + "#12345")) {
                throw new Bad(401, "bad credentials");
            }
            String token = UUID.randomUUID().toString();
            sessions.put(token, user);
            send(ex, 200, Map.of("token", token));
            return;
        }
        if (!path.startsWith("/api/")) throw new Bad(404, "not found");

        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String user = auth != null && auth.startsWith("Bearer ") ? sessions.get(auth.substring(7)) : null;
        if (user == null) throw new Bad(401, "authentication required");

        String[] parts = path.substring(5).split("/");
        String resource = parts[0];

        if (resource.equals("audit-logs")) {
            if (!user.equals("admin")) throw new Bad(403, "forbidden");
            List<Map<String, Object>> newestFirst;
            synchronized (audit) {
                newestFirst = new ArrayList<>(audit);
            }
            java.util.Collections.reverse(newestFirst);
            send(ex, 200, page(newestFirst, query));
            return;
        }
        if (!FIELDS.containsKey(resource)) throw new Bad(404, "unknown resource");
        String entity = SINGULAR.get(resource);

        if (parts.length == 2 && parts[1].equals("export.csv") && resource.equals("contacts") && method.equals("GET")) {
            require(user, entity, bug("csvOpenToViewer") ? "read" : "export");
            StringBuilder csv = new StringBuilder(String.join(",", FIELDS.get(resource))).append("\r\n");
            for (Map<String, Object> row : sorted(resource, null, null)) {
                List<String> cells = new ArrayList<>();
                for (String f : FIELDS.get(resource)) cells.add(csvCell(row.get(f)));
                csv.append(String.join(",", cells)).append("\r\n");
            }
            ex.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
            raw(ex, 200, csv.toString());
            return;
        }

        if (parts.length == 1) {
            if (method.equals("GET")) {
                require(user, entity, "read");
                send(ex, 200, page(sorted(resource, query.get("q"), query.get("sort")), query));
            } else if (method.equals("POST")) {
                require(user, entity, "write");
                Map<String, Object> in = body(ex);
                validate(resource, in, null);
                long id = ids.computeIfAbsent(resource, r -> new AtomicLong()).incrementAndGet();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id);
                for (String f : FIELDS.get(resource)) row.put(f, in.get(f));
                if (resource.equals("notes")) {
                    row.put("authorId", bug("trustAuthorId") && in.get("authorId") != null ? in.get("authorId") : USER_IDS.get(user));
                    row.put("createdAt", Instant.now().toString());
                }
                data.computeIfAbsent(resource, r -> new ConcurrentHashMap<>()).put(id, row);
                record(user, "CREATE", entity, id);
                ex.getResponseHeaders().set("Location", "/api/" + resource + "/" + id);
                send(ex, 201, row);
            } else throw new Bad(405, "method not allowed");
            return;
        }

        long id;
        try {
            id = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new Bad(404, "not found");
        }
        Map<Long, Map<String, Object>> table = data.computeIfAbsent(resource, r -> new ConcurrentHashMap<>());
        switch (method) {
            case "GET" -> {
                require(user, entity, "read");
                send(ex, 200, existing(table, id));
            }
            case "PUT" -> {
                require(user, entity, "write");
                Map<String, Object> row = existing(table, id);
                Map<String, Object> in = body(ex);
                validate(resource, in, row);
                for (String f : FIELDS.get(resource)) if (!f.equals("createdAt")) row.put(f, in.get(f));
                record(user, "UPDATE", entity, id);
                send(ex, 200, row);
            }
            case "DELETE" -> {
                require(user, entity, "write");
                existing(table, id);
                table.remove(id);
                if (!bug("noAuditOnDelete")) record(user, "DELETE", entity, id);
                raw(ex, 204, null);
            }
            default -> throw new Bad(405, "method not allowed");
        }
    }

    private static Map<String, Object> existing(Map<Long, Map<String, Object>> table, long id) {
        Map<String, Object> row = table.get(id);
        if (row == null) throw new Bad(404, "not found");
        return row;
    }

    private void require(String user, String entity, String action) {
        if (bug("viewerCanWrite") && user.equals("viewer") && action.equals("write")) return;
        Set<String> p = PERMS.get(user);
        if (p.contains("*") || p.contains(entity + ":*") || p.contains(entity + ":" + action)) return;
        throw new Bad(403, "forbidden");
    }

    private void validate(String resource, Map<String, Object> in, Map<String, Object> current) {
        if (resource.equals("deals")) {
            String next = String.valueOf(in.get("stage"));
            if (!STAGES.contains(next) && !next.equals("LOST")) throw new Bad(400, "unknown stage");
            if (current == null || bug("skipTransitionCheck")) return;
            String now = String.valueOf(current.get("stage"));
            if (now.equals(next)) return;
            boolean open = STAGES.indexOf(now) >= 0 && !now.equals("WON");
            boolean forward = open && STAGES.indexOf(next) == STAGES.indexOf(now) + 1;
            boolean lost = open && next.equals("LOST");
            if (!(forward || lost)) throw new Bad(400, "invalid stage transition " + now + " -> " + next);
        }
    }

    private void record(String user, String action, String entity, long entityId) {
        Map<String, Object> e = new LinkedHashMap<>();
        synchronized (audit) {
            e.put("id", (long) audit.size() + 1);
            e.put("actor", user);
            e.put("action", action);
            e.put("entity", entity);
            e.put("entityId", entityId);
            e.put("timestamp", Instant.now().toString());
            audit.add(e);
        }
    }

    private List<Map<String, Object>> sorted(String resource, String q, String sort) {
        List<Map<String, Object>> rows = new ArrayList<>(data.getOrDefault(resource, Map.of()).values());
        if (q != null && !q.isEmpty()) {
            String needle = bug("qCaseSensitive") ? q : q.toLowerCase();
            List<String> fields = resource.equals("contacts") ? List.of("name", "email", "company") : FIELDS.get(resource);
            rows.removeIf(r -> fields.stream().noneMatch(f -> {
                String v = String.valueOf(r.get(f));
                return (bug("qCaseSensitive") ? v : v.toLowerCase()).contains(needle);
            }));
        }
        String field = "id";
        boolean desc = false;
        if (sort != null && !sort.isEmpty()) {
            String[] sp = sort.split(",");
            field = sp[0];
            desc = sp.length > 1 && sp[1].equalsIgnoreCase("desc");
            if (!field.equals("id") && !FIELDS.get(resource).contains(field)) throw new Bad(400, "unknown sort field " + field);
        }
        final String key = field;
        Comparator<Map<String, Object>> cmp = Comparator.comparing(r -> String.valueOf(r.get(key)), String.CASE_INSENSITIVE_ORDER);
        if (key.equals("id")) cmp = Comparator.comparingLong(r -> (Long) r.get("id"));
        rows.sort(desc ? cmp.reversed() : cmp);
        return rows;
    }

    private Map<String, Object> page(List<Map<String, Object>> rows, Map<String, String> q) {
        int size = q.containsKey("size") ? Integer.parseInt(q.get("size")) : 20;
        int page = q.containsKey("page") ? Integer.parseInt(q.get("page")) : 0;
        if (size > 100 || size < 1 || page < 0) throw new Bad(400, "invalid paging");
        int from = Math.min(page * size, rows.size());
        int to = Math.min(from + size, rows.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", new ArrayList<>(rows.subList(from, to)));
        out.put("totalElements", (long) (bug("totalWrong") ? to - from : rows.size()));
        out.put("page", (long) page);
        out.put("size", (long) size);
        return out;
    }

    private static String csvCell(Object v) {
        String s = v == null ? "" : v.toString();
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            Object parsed = Json.parse(text);
            if (parsed instanceof Map<?, ?> m) return (Map<String, Object>) m;
        } catch (IllegalArgumentException ignored) {
            // fall through
        }
        throw new Bad(400, "invalid JSON body");
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) m.put(java.net.URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8), java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void send(HttpExchange ex, int status, Object json) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        raw(ex, status, Json.write(json));
    }

    private static void raw(HttpExchange ex, int status, String text) throws IOException {
        byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, text == null ? -1 : bytes.length);
        if (text != null) ex.getResponseBody().write(bytes);
        ex.close();
    }
}
