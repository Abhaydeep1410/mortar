package dev.mortar.oracle.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Self-tests of the harness. They need no server: `mvn test` runs only these by default. */
@Tag("harness")
class HarnessTest {
    @Test
    void pluralizationFollowsTheContract() {
        assertEquals("/api/contacts", Contract.path("Contact"));
        assertEquals("/api/categories", Contract.path("Category"));
        assertEquals("/api/entries", Contract.path("Entry"));
        assertEquals("/api/addresses", Contract.path("Address"));
        assertEquals("/api/boxes", Contract.path("Box"));
        assertEquals("/api/people", Contract.path("Person"));
        assertEquals("/api/keys", Contract.path("Key"));
        assertEquals("/api/auditlogs", Contract.path("AuditLog"));
        assertEquals("/api/stocks", Contract.path("Stock"));
        assertEquals("/api/locations", Contract.path("Location"));
    }

    @Test
    void seededPasswordsFollowTheContract() {
        assertEquals("Admin#12345", Contract.passwordFor("admin"));
        assertEquals("Sales_rep#12345", Contract.passwordFor("sales_rep"));
    }

    @Test
    void jsonRoundTrips() {
        String text = "{\"a\":1,\"b\":[true,null,\"x\\n\\\"y\\\"\"],\"c\":{\"d\":2.5}}";
        Object parsed = Json.parse(text);
        assertEquals(text, Json.write(parsed));
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;
        assertEquals(1L, m.get("a"));
        assertEquals(List.of(true, "x\n\"y\""), ((List<?>) m.get("b")).stream().filter(v -> v != null).toList());
    }

    @Test
    void jsonRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2"));
    }
}
