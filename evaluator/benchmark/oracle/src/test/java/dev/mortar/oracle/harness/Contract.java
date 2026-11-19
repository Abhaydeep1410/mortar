package dev.mortar.oracle.harness;

import java.util.Map;

/** The application contract (docs/benchmark-design.md section 5) as code. Mirrors orchestrator/mortar/engine/naming.py. */
public final class Contract {
    private static final Map<String, String> IRREGULAR = Map.of("person", "people", "child", "children", "man", "men", "woman", "women");

    private Contract() {}

    public static String pluralize(String word) {
        if (IRREGULAR.containsKey(word)) return IRREGULAR.get(word);
        for (Map.Entry<String, String> e : IRREGULAR.entrySet()) {
            if (word.endsWith(e.getKey()) && word.length() > e.getKey().length()) {
                return word.substring(0, word.length() - e.getKey().length()) + e.getValue();
            }
        }
        if (word.matches(".*(s|x|z|ch|sh)$")) return word + "es";
        if (word.matches(".*[^aeiou]y$")) return word.substring(0, word.length() - 1) + "ies";
        return word + "s";
    }

    /** `Contact` -> `/api/contacts`. */
    public static String path(String entityName) {
        return "/api/" + pluralize(entityName.toLowerCase());
    }

    /** Seeded password: the username with its first letter capitalized, then `#12345`. */
    public static String passwordFor(String username) {
        return Character.toUpperCase(username.charAt(0)) + username.substring(1) + "#12345";
    }
}
