package dev.reklo.oracle.reference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.reklo.oracle.harness.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generic in-memory implementation of the application contract (docs/benchmark-design.md section 5), used only
 * to validate the oracles: a per-prompt subclass supplies entities, users, permissions and the prompt's own rules
 * through hooks. Never part of the benchmark and never given to a model.
 *
 * Permissions follow the authorization.rbac module: GET needs `read`, POST/PUT `write`, DELETE `delete`, CSV `export`;
 * `*` and `<entity>:*` grant everything.
 *
 * Bugs: -Dreference.bugs=a,b,c switches on deliberate faults (each subclass documents its own); `bug(name)`.
 */
public abstract class ContractServer {
    private static final Set<String> BUGS = Set.of(System.getProperty("reference.bugs", "").split(","));

    protected static boolean bug(String name) {
        return BUGS.contains(name);
    }

    /** An error response; thrown from hooks. */
    public static final class Bad extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;

        public Bad(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** One entity: `fields` are everything returned, `writable` the subset a client may set. */
    protected record Entity(String name, String plural, List<String> fields, Set<String> writable, boolean csv, List<String> searchFields) {
        static Entity of(String name, String plural, List<String> fields, Set<String> writable) {
            return new Entity(name, plural, fields, writable, false, fields);
        }

        Entity withCsv() {
            return new Entity(name, plural, fields, writable, true, searchFields);
        }

        Entity searching(List<String> search) {
            return new Entity(name, plural, fields, writable, csv, search);
        }
    }

    // ---- what a subclass provides
    protected abstract List<Entity> entities();

    protected abstract Map<String, Long> userIds();

    protected abstract Map<String, Set<String>> permissions();

    protected void seed() {}

    protected void validate(Entity e, Map<String, Object> in, Map<String, Object> current, String user) {}

    protected void prepare(Entity e, Map<String, Object> row, Map<String, Object> in, String user) {}

    protected void afterCreate(Entity e, Map<String, Object> row, String user) {}

    protected boolean visible(Entity e, Map<String, Object> row, String user) {
        return true;
    }

    /** A prompt-specific endpoint under /api (path without the `/api/` prefix); null means not handled. */
    protected Object custom(String method, String path, String user, Map<String, String> query) {
        return null;
    }

    /** All rows of a table, for custom endpoints. */
    protected final List<Map<String, Object>> all(String plural) {
        return new ArrayList<>(data.getOrDefault(plural, Map.of()).values());
    }

    protected final void check(String user, String entity, String action) {
        if (!allowed(user, entity, action)) throw new Bad(403, "forbidden");
    }

    // ---- state and helpers for subclasses
    private final Map<String, Map<Long, Map<String, Object>>> data = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> ids = new ConcurrentHashMap<>();
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> audit = new ArrayList<>();
    private Map<String, Entity> byPlural;

    protected final long insert(String plural, Map<String, Object> row) {
        long id = ids.computeIfAbsent(plural, r -> new AtomicLong()).incrementAndGet();
        row.put("id", id);
        data.computeIfAbsent(plural, r -> new ConcurrentHashMap<>()).put(id, row);
        return id;
    }

    protected final Map<String, Object> find(String plural, long id) {
        return data.getOrDefault(plural, Map.of()).get(id);
    }

    protected static long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        throw new Bad(400, "expected a number but got " + o);
    }

    protected final long userId(String user) {
        return userIds().get(user);
    }

    public final void start(int port) throws IOException {
        byPlural = new LinkedHashMap<>();
        for (Entity e : entities()) byPlural.put(e.plural(), e);
        seed();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", ex -> {
            try {
                handle(ex);
            } catch (Bad b) {
                send(ex, b.status, Map.of("message", String.valueOf(b.getMessage())));
            } catch (RuntimeException e) {
                send(ex, 500, Map.of("message", String.valueOf(e)));
            }
        });
        server.start();
        System.out.println(getClass().getSimpleName() + " on :" + port + " bugs=" + BUGS);
    }

    protected static int portFrom(String[] args) {
        return args.length > 0 ? Integer.parseInt(args[0]) : 8080;
    }

    // ---- request handling
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
            if (!userIds().containsKey(user) || !String.valueOf(body.get("password")).equals(Character.toUpperCase(user.charAt(0)) + user.substring(1) + "#12345")) {
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
            Collections.reverse(newestFirst);
            send(ex, 200, page(newestFirst, query));
            return;
        }
        Object special = custom(method, path.substring(5), user, query);
        if (special != null) {
            send(ex, 200, special);
            return;
        }
        Entity e = byPlural.get(resource);
        if (e == null) throw new Bad(404, "unknown resource");

        if (parts.length == 2 && parts[1].equals("export.csv") && e.csv() && method.equals("GET")) {
            require(user, e, "export");
            StringBuilder csv = new StringBuilder(String.join(",", e.fields())).append("\r\n");
            for (Map<String, Object> row : rows(e, user, null, null)) {
                List<String> cells = new ArrayList<>();
                for (String f : e.fields()) cells.add(csvCell(row.get(f)));
                csv.append(String.join(",", cells)).append("\r\n");
            }
            ex.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
            raw(ex, 200, csv.toString());
            return;
        }

        if (parts.length == 1) {
            if (method.equals("GET")) {
                require(user, e, "read");
                send(ex, 200, page(rows(e, user, query.get("q"), query.get("sort")), query));
            } else if (method.equals("POST")) {
                require(user, e, "write");
                Map<String, Object> in = body(ex);
                validate(e, in, null, user);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", null);
                for (String f : e.fields()) row.put(f, e.writable().contains(f) ? in.get(f) : null);
                prepare(e, row, in, user);
                long id = insert(e.plural(), row);
                afterCreate(e, row, user);
                record(user, "CREATE", e.name(), id);
                ex.getResponseHeaders().set("Location", "/api/" + e.plural() + "/" + id);
                send(ex, 201, row);
            } else throw new Bad(405, "method not allowed");
            return;
        }

        long id;
        try {
            id = Long.parseLong(parts[1]);
        } catch (NumberFormatException nfe) {
            throw new Bad(404, "not found");
        }
        switch (method) {
            case "GET" -> {
                require(user, e, "read");
                send(ex, 200, existing(e, id, user));
            }
            case "PUT" -> {
                require(user, e, "write");
                Map<String, Object> row = existing(e, id, user);
                Map<String, Object> in = body(ex);
                validate(e, in, row, user);
                for (String f : e.writable()) row.put(f, in.get(f));
                update(e, row, in, user);
                record(user, "UPDATE", e.name(), id);
                send(ex, 200, row);
            }
            case "DELETE" -> {
                require(user, e, "delete");
                existing(e, id, user);
                data.get(e.plural()).remove(id);
                if (!bug("noAuditOnDelete") || !e.name().equals(auditBugEntity())) record(user, "DELETE", e.name(), id);
                raw(ex, 204, null);
            }
            default -> throw new Bad(405, "method not allowed");
        }
    }

    /** Hook after a PUT copied the writable fields (a subclass bug may copy more). */
    protected void update(Entity e, Map<String, Object> row, Map<String, Object> in, String user) {}

    /** The entity whose deletes the `noAuditOnDelete` bug leaves unaudited. */
    protected String auditBugEntity() {
        return "contact";
    }

    private Map<String, Object> existing(Entity e, long id, String user) {
        Map<String, Object> row = find(e.plural(), id);
        if (row == null || !visible(e, row, user)) throw new Bad(404, "not found");
        return row;
    }

    protected boolean allowed(String user, String entity, String action) {
        Set<String> p = permissions().get(user);
        return p.contains("*") || p.contains(entity + ":*") || p.contains(entity + ":" + action);
    }

    private void require(String user, Entity e, String action) {
        if (!allowed(user, e.name(), action)) throw new Bad(403, "forbidden");
    }

    private void record(String user, String action, String entity, long entityId) {
        Map<String, Object> rec = new LinkedHashMap<>();
        synchronized (audit) {
            rec.put("id", (long) audit.size() + 1);
            rec.put("actor", user);
            rec.put("action", action);
            rec.put("entity", entity);
            rec.put("entityId", entityId);
            rec.put("timestamp", Instant.now().toString());
            audit.add(rec);
        }
    }

    private List<Map<String, Object>> rows(Entity e, String user, String q, String sort) {
        List<Map<String, Object>> rows = new ArrayList<>(data.getOrDefault(e.plural(), Map.of()).values());
        rows.removeIf(r -> !visible(e, r, user));
        if (q != null && !q.isEmpty()) {
            boolean cs = bug("qCaseSensitive");
            String needle = cs ? q : q.toLowerCase();
            rows.removeIf(r -> e.searchFields().stream().noneMatch(f -> {
                String v = String.valueOf(r.get(f));
                return (cs ? v : v.toLowerCase()).contains(needle);
            }));
        }
        String field = "id";
        boolean desc = false;
        if (sort != null && !sort.isEmpty()) {
            String[] sp = sort.split(",");
            field = sp[0];
            desc = sp.length > 1 && sp[1].equalsIgnoreCase("desc");
            if (!field.equals("id") && !e.fields().contains(field)) throw new Bad(400, "unknown sort field " + field);
        }
        final String key = field;
        Comparator<Map<String, Object>> cmp = key.equals("id")
                ? Comparator.comparingLong(r -> (Long) r.get("id"))
                : Comparator.comparing(r -> String.valueOf(r.get(key)), String.CASE_INSENSITIVE_ORDER);
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
            if (Json.parse(text) instanceof Map<?, ?> m) return (Map<String, Object>) m;
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
            if (i > 0) m.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
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
