package dev.mortar.oracle.harness;

import java.util.List;
import java.util.Map;

/** One HTTP response: status, headers and body, with small JSON accessors for assertions. */
public record Resp(int status, Map<String, List<String>> headers, String body) {

    public String header(String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
        }
        return null;
    }

    public Object json() {
        return Json.parse(body);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> obj() {
        return (Map<String, Object>) json();
    }

    /** The `content` array of a list response, as objects. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> content() {
        return (List<Map<String, Object>>) obj().get("content");
    }

    public long num(String key) {
        Object v = obj().get(key);
        if (!(v instanceof Number n)) throw new AssertionError("expected a number at '" + key + "' but body is: " + this);
        return n.longValue();
    }

    public String str(String key) {
        Object v = obj().get(key);
        return v == null ? null : v.toString();
    }

    @Override
    public String toString() {
        return status + " " + (body == null ? "" : body.length() > 300 ? body.substring(0, 300) + "..." : body);
    }
}
