package dev.reklo.oracle.crm;

import static dev.reklo.oracle.harness.Checks.asLong;
import static dev.reklo.oracle.harness.Checks.created;
import static dev.reklo.oracle.harness.Checks.hasAudit;
import static dev.reklo.oracle.harness.Checks.list;
import static dev.reklo.oracle.harness.Checks.unique;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.reklo.oracle.harness.Api;
import dev.reklo.oracle.harness.OracleBase;
import dev.reklo.oracle.harness.Resp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * API oracle for benchmark prompt crm-01 (evaluator/benchmark/prompts/crm-01.yaml, criteria crm-01.1 to crm-01.8).
 * Black box: it uses only the application contract (docs/benchmark-design.md section 5) and the seeded users
 * admin, sales_rep and viewer. crm-01.9 (UI smoke) is the Playwright oracle.
 */
@Tag("crm-01")
class CrmApiOracle extends OracleBase {
    private static final String ADMIN = "admin";
    private static final String REP = "sales_rep";
    private static final String VIEWER = "viewer";

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> contact(String name, String email, String company, String phone) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("email", email);
        m.put("company", company);
        m.put("phone", phone);
        return m;
    }

    private static Map<String, Object> contact() {
        String u = unique();
        return contact("Oracle " + u, u + "@example.com", "Acme " + u, "555-0100");
    }

    private static Map<String, Object> deal(long contactId, String stage) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", "Deal " + unique());
        m.put("value", 1000);
        m.put("stage", stage);
        m.put("contactId", contactId);
        m.put("closeDate", "2026-12-31");
        return m;
    }

    private static Map<String, Object> note(long contactId, Object authorId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("body", "Note " + unique());
        m.put("contactId", contactId);
        m.put("authorId", authorId);
        return m;
    }

    private static long createContact(String user) {
        return created(Api.post("/api/contacts", Api.as(user), contact()), "a contact").num("id");
    }

    private static long createDeal(String user, long contactId, String stage) {
        return created(Api.post("/api/deals", Api.as(user), deal(contactId, stage)), "a deal").num("id");
    }

    /** PUT a deal with every field unchanged except its stage. */
    private static Resp stageChange(long dealId, long contactId, String title, String stage) {
        Map<String, Object> body = deal(contactId, stage);
        body.put("title", title);
        return Api.put("/api/deals/" + dealId, Api.as(REP), body);
    }

    // ---------------------------------------------------------------- crm-01.1 authentication

    @Test
    @Tag("crm-01.1")
    void loginWithValidCredentialsReturnsAToken() {
        Resp r = Api.post("/api/auth/login", null, Map.of("username", ADMIN, "password", "Admin#12345"));
        assertEquals(200, r.status(), r.toString());
        assertFalse(r.str("token") == null || r.str("token").isBlank(), "login response must contain a token: " + r);
    }

    @Test
    @Tag("crm-01.1")
    void loginWithWrongPasswordIsRejected() {
        Resp r = Api.post("/api/auth/login", null, Map.of("username", ADMIN, "password", "wrong-password"));
        assertEquals(401, r.status(), r.toString());
    }

    @Test
    @Tag("crm-01.1")
    void protectedEndpointsRequireAToken() {
        assertEquals(401, Api.get("/api/contacts", null).status());
        assertEquals(401, Api.get("/api/deals", null).status());
        assertEquals(401, Api.get("/api/notes", null).status());
    }

    // ---------------------------------------------------------------- crm-01.2 roles on contacts

    @Test
    @Tag("crm-01.2")
    void salesRepCanCreateReadUpdateAndDeleteContacts() {
        String token = Api.as(REP);
        Map<String, Object> body = contact();
        Resp c = created(Api.post("/api/contacts", token, body), "a contact");
        long id = c.num("id");
        assertEquals(body.get("email"), c.str("email"));

        Resp read = Api.get("/api/contacts/" + id, token);
        assertEquals(200, read.status(), read.toString());
        assertEquals(body.get("name"), read.str("name"));

        body.put("company", "Changed Co");
        Resp updated = Api.put("/api/contacts/" + id, token, body);
        assertEquals(200, updated.status(), updated.toString());
        assertEquals("Changed Co", updated.str("company"));
        assertEquals("Changed Co", Api.get("/api/contacts/" + id, token).str("company"));

        assertEquals(204, Api.delete("/api/contacts/" + id, token).status());
        assertEquals(404, Api.get("/api/contacts/" + id, token).status());
    }

    @Test
    @Tag("crm-01.2")
    void viewerCanReadButNotWriteContacts() {
        long id = createContact(ADMIN);
        String viewer = Api.as(VIEWER);
        assertEquals(200, Api.get("/api/contacts/" + id, viewer).status());
        assertEquals(200, Api.get("/api/contacts", viewer).status());
        assertEquals(403, Api.post("/api/contacts", viewer, contact()).status());
        assertEquals(403, Api.put("/api/contacts/" + id, viewer, contact()).status());
        assertEquals(403, Api.delete("/api/contacts/" + id, viewer).status());
        assertEquals(200, Api.get("/api/contacts/" + id, Api.as(ADMIN)).status(), "a forbidden delete must not remove the contact");
    }

    // ---------------------------------------------------------------- crm-01.3 pagination

    @Test
    @Tag("crm-01.3")
    void listsArePaginatedWithAccurateTotals() {
        String token = Api.as(REP);
        long before = list("/api/contacts", REP, "page=0&size=10").num("totalElements");
        for (int i = 0; i < 25; i++) created(Api.post("/api/contacts", token, contact()), "contact " + i);

        Resp first = list("/api/contacts", REP, "page=0&size=10");
        assertEquals(before + 25, first.num("totalElements"), "totalElements must count every contact");
        assertEquals(10, first.content().size(), "a full page must hold `size` items");
        assertEquals(0, first.num("page"));
        assertEquals(10, first.num("size"));

        long lastPage = (before + 25 - 1) / 10;
        Resp last = list("/api/contacts", REP, "page=" + lastPage + "&size=10");
        assertEquals(before + 25 - lastPage * 10, last.content().size(), "the last page holds the remainder");

        Set<Object> firstIds = new HashSet<>();
        first.content().forEach(c -> firstIds.add(c.get("id")));
        list("/api/contacts", REP, "page=1&size=10").content().forEach(c -> assertFalse(firstIds.contains(c.get("id")), "pages must not overlap"));
    }

    @Test
    @Tag("crm-01.3")
    void sizeAbove100IsRejectedAndSizeUpTo100IsHonored() {
        String token = Api.as(REP);
        assertEquals(100, list("/api/contacts", REP, "size=100").num("size"));
        assertEquals(400, Api.get("/api/contacts?size=101", token).status());
        assertEquals(400, Api.get("/api/contacts?sort=notAField,asc", token).status());
    }

    @Test
    @Tag("crm-01.3")
    void listsCanBeSortedByAField() {
        String token = Api.as(REP);
        String t = unique();
        for (String suffix : List.of("b", "c", "a")) {
            created(Api.post("/api/contacts", token, contact("Sorted " + t + "-" + suffix, t + suffix + "@example.com", "Co", "1")), "contact " + suffix);
        }
        List<String> asc = new ArrayList<>();
        list("/api/contacts", REP, "q=" + t + "&sort=name,asc").content().forEach(c -> asc.add(c.get("name").toString()));
        List<String> desc = new ArrayList<>();
        list("/api/contacts", REP, "q=" + t + "&sort=name,desc").content().forEach(c -> desc.add(c.get("name").toString()));
        assertEquals(List.of("Sorted " + t + "-a", "Sorted " + t + "-b", "Sorted " + t + "-c"), asc);
        assertEquals(List.of("Sorted " + t + "-c", "Sorted " + t + "-b", "Sorted " + t + "-a"), desc);
    }

    // ---------------------------------------------------------------- crm-01.4 search

    @Test
    @Tag("crm-01.4")
    void searchMatchesNameEmailOrCompanyCaseInsensitively() {
        String token = Api.as(REP);
        String t = unique();
        long byName = created(Api.post("/api/contacts", token, contact("Name " + t.toUpperCase(), "byname" + t.length() + "@example.com", "Co1", "1")), "a").num("id");
        long byEmail = created(Api.post("/api/contacts", token, contact("Plain One", t + "@example.com", "Co2", "2")), "b").num("id");
        long byCompany = created(Api.post("/api/contacts", token, contact("Plain Two", "two" + t.length() + "@example.com", "Company " + t, "3")), "c").num("id");
        long other = created(Api.post("/api/contacts", token, contact("Plain Three", "three@example.com", "Co4", "4")), "d").num("id");

        for (String q : List.of(t, t.toUpperCase())) {
            Resp r = list("/api/contacts", REP, "size=100&q=" + q);
            Set<Long> ids = new HashSet<>();
            r.content().forEach(c -> ids.add(((Number) c.get("id")).longValue()));
            assertEquals(Set.of(byName, byEmail, byCompany), ids, "q=" + q + " must match exactly the contacts whose name, email or company contain it");
            assertFalse(ids.contains(other));
            assertEquals(3, r.num("totalElements"), "totalElements must count the matches, not all contacts");
        }
        assertEquals(0, list("/api/contacts", REP, "q=" + t + "nomatch").content().size());
    }

    // ---------------------------------------------------------------- crm-01.5 deal stage rules

    @Test
    @Tag("crm-01.5")
    void aDealMovesForwardOneStageAtATime() {
        long contactId = createContact(REP);
        Map<String, Object> d = deal(contactId, "LEAD");
        String title = d.get("title").toString();
        long id = created(Api.post("/api/deals", Api.as(REP), d), "a deal").num("id");
        assertEquals("LEAD", Api.get("/api/deals/" + id, Api.as(REP)).str("stage"));

        assertEquals(400, stageChange(id, contactId, title, "PROPOSAL").status(), "LEAD -> PROPOSAL skips a stage");
        assertEquals(400, stageChange(id, contactId, title, "WON").status(), "LEAD -> WON skips stages");
        assertEquals("LEAD", Api.get("/api/deals/" + id, Api.as(REP)).str("stage"), "a rejected move must not change the stage");

        Resp q = stageChange(id, contactId, title, "QUALIFIED");
        assertEquals(200, q.status(), q.toString());
        assertEquals("QUALIFIED", q.str("stage"));
        assertEquals(400, stageChange(id, contactId, title, "LEAD").status(), "moving backwards is not allowed");
        assertEquals(400, stageChange(id, contactId, title, "WON").status(), "QUALIFIED -> WON skips PROPOSAL");
        assertEquals(200, stageChange(id, contactId, title, "PROPOSAL").status());
        assertEquals(200, stageChange(id, contactId, title, "WON").status());
        assertEquals("WON", Api.get("/api/deals/" + id, Api.as(REP)).str("stage"));
    }

    @Test
    @Tag("crm-01.5")
    void anOpenDealCanBeLostFromAnyOpenStage() {
        long contactId = createContact(REP);
        for (String from : List.of("LEAD", "QUALIFIED", "PROPOSAL")) {
            Map<String, Object> d = deal(contactId, "LEAD");
            String title = d.get("title").toString();
            long id = created(Api.post("/api/deals", Api.as(REP), d), "a deal").num("id");
            if (!from.equals("LEAD")) assertEquals(200, stageChange(id, contactId, title, "QUALIFIED").status());
            if (from.equals("PROPOSAL")) assertEquals(200, stageChange(id, contactId, title, "PROPOSAL").status());
            Resp lost = stageChange(id, contactId, title, "LOST");
            assertEquals(200, lost.status(), "LOST from " + from + ": " + lost);
            assertEquals("LOST", lost.str("stage"));
        }
    }

    @Test
    @Tag("crm-01.5")
    void wonAndLostDealsAreFinal() {
        long contactId = createContact(REP);
        Map<String, Object> won = deal(contactId, "LEAD");
        String wonTitle = won.get("title").toString();
        long wonId = created(Api.post("/api/deals", Api.as(REP), won), "a deal").num("id");
        for (String s : List.of("QUALIFIED", "PROPOSAL", "WON")) assertEquals(200, stageChange(wonId, contactId, wonTitle, s).status());
        for (String s : List.of("LEAD", "QUALIFIED", "PROPOSAL", "LOST")) assertEquals(400, stageChange(wonId, contactId, wonTitle, s).status(), "a WON deal cannot move to " + s);

        Map<String, Object> lost = deal(contactId, "LEAD");
        String lostTitle = lost.get("title").toString();
        long lostId = created(Api.post("/api/deals", Api.as(REP), lost), "a deal").num("id");
        assertEquals(200, stageChange(lostId, contactId, lostTitle, "LOST").status());
        for (String s : List.of("LEAD", "QUALIFIED", "PROPOSAL", "WON")) assertEquals(400, stageChange(lostId, contactId, lostTitle, s).status(), "a LOST deal cannot move to " + s);
        assertEquals("LOST", Api.get("/api/deals/" + lostId, Api.as(REP)).str("stage"));
    }

    // ---------------------------------------------------------------- crm-01.6 note authorship

    @Test
    @Tag("crm-01.6")
    void aNoteAuthorIsTheAuthenticatedUserNotTheRequestBody() {
        long contactId = createContact(ADMIN);
        Resp first = created(Api.post("/api/notes", Api.as(REP), note(contactId, 987654)), "a note");
        Resp second = created(Api.post("/api/notes", Api.as(REP), note(contactId, 123456)), "a second note");
        Resp byAdmin = created(Api.post("/api/notes", Api.as(ADMIN), note(contactId, 987654)), "an admin note");

        assertNotEquals(987654L, first.num("authorId"), "authorId must come from the token, not the request body");
        assertNotEquals(123456L, second.num("authorId"));
        assertEquals(first.num("authorId"), second.num("authorId"), "the same user is the same author whatever the body says");
        assertNotEquals(first.num("authorId"), byAdmin.num("authorId"), "different users are different authors");
        assertEquals(first.num("authorId"), Api.get("/api/notes/" + first.num("id"), Api.as(REP)).num("authorId"), "the stored author is what is returned");
    }

    // ---------------------------------------------------------------- crm-01.7 CSV export

    @Test
    @Tag("crm-01.7")
    void salesRepCanExportContactsAsCsv() {
        String t = unique();
        created(Api.post("/api/contacts", Api.as(REP), contact("Export " + t, t + "@example.com", "Co", "5")), "a contact");
        long total = list("/api/contacts", REP, "size=100").num("totalElements");

        Resp csv = Api.get("/api/contacts/export.csv", Api.as(REP));
        assertEquals(200, csv.status(), csv.toString());
        String type = csv.header("Content-Type");
        assertTrue(type != null && type.toLowerCase().startsWith("text/csv"), "Content-Type must be text/csv, was " + type);

        String[] lines = csv.body().strip().split("\\r?\\n");
        List<String> header = List.of(lines[0].split(","));
        for (String column : List.of("name", "email", "company", "phone")) assertTrue(header.contains(column), "header row must name the field '" + column + "': " + lines[0]);
        assertTrue(Set.of("id", "name", "email", "company", "phone").containsAll(header), "header holds only contact fields: " + lines[0]);
        assertEquals(total + 1, lines.length, "one row per contact plus the header");
        assertTrue(csv.body().contains(t + "@example.com"), "the new contact must be in the export");
    }

    @Test
    @Tag("crm-01.7")
    void viewerCannotExport() {
        assertEquals(403, Api.get("/api/contacts/export.csv", Api.as(VIEWER)).status());
    }

    // ---------------------------------------------------------------- crm-01.8 audit log

    @Test
    @Tag("crm-01.8")
    void changesAreAuditedWithActorEntityAndAction() {
        String token = Api.as(REP);
        long contactId = createContact(REP);
        long dealId = createDeal(REP, contactId, "LEAD");
        long noteId = created(Api.post("/api/notes", token, note(contactId, 1)), "a note").num("id");
        assertEquals(200, Api.put("/api/contacts/" + contactId, token, contact()).status());
        Map<String, Object> editedNote = note(contactId, 1);
        assertEquals(200, Api.put("/api/notes/" + noteId, token, editedNote).status());
        Map<String, Object> movedDeal = deal(contactId, "QUALIFIED");
        assertEquals(200, Api.put("/api/deals/" + dealId, token, movedDeal).status());
        // Deletes are done as ADMIN, who always holds every permission, so this criterion does not depend on how the
        // prompt words the SALES_REP delete grant (crm-01.2 covers that).
        String admin = Api.as(ADMIN);
        assertEquals(204, Api.delete("/api/notes/" + noteId, admin).status());
        assertEquals(204, Api.delete("/api/deals/" + dealId, admin).status());
        assertEquals(204, Api.delete("/api/contacts/" + contactId, admin).status());

        Resp log = Api.get("/api/audit-logs?page=0&size=100", Api.as(ADMIN));
        assertEquals(200, log.status(), log.toString());
        assertTrue(hasAudit(log, "contact", contactId, "CREATE"), "contact create must be audited");
        assertTrue(hasAudit(log, "contact", contactId, "UPDATE"), "contact update must be audited");
        assertTrue(hasAudit(log, "contact", contactId, "DELETE"), "contact delete must be audited");
        for (String action : List.of("CREATE", "UPDATE", "DELETE")) {
            assertTrue(hasAudit(log, "deal", dealId, action), "deal " + action + " must be audited");
            assertTrue(hasAudit(log, "note", noteId, action), "note " + action + " must be audited");
        }
        for (Map<String, Object> e : log.content()) {
            if (Long.valueOf(contactId).equals(asLong(e.get("entityId"))) && "contact".equals(String.valueOf(e.get("entity")).toLowerCase())) {
                String expectedActor = "DELETE".equals(e.get("action")) ? ADMIN : REP;
                assertEquals(expectedActor, e.get("actor"), "the actor is the authenticated user: " + e);
                assertFalse(e.get("timestamp") == null, "every entry has a timestamp: " + e);
            }
        }
    }

    @Test
    @Tag("crm-01.8")
    void onlyAdminsCanReadTheAuditLog() {
        assertEquals(403, Api.get("/api/audit-logs", Api.as(REP)).status());
        assertEquals(403, Api.get("/api/audit-logs", Api.as(VIEWER)).status());
        assertEquals(401, Api.get("/api/audit-logs", null).status());
        assertEquals(200, Api.get("/api/audit-logs", Api.as(ADMIN)).status());
    }

    @Test
    @Tag("crm-01.8")
    void theAuditLogListsNewestFirst() {
        long first = createContact(REP);
        long second = createContact(REP);
        List<Map<String, Object>> entries = Api.get("/api/audit-logs?page=0&size=100", Api.as(ADMIN)).content();
        int firstAt = -1, secondAt = -1;
        for (int i = 0; i < entries.size(); i++) {
            Map<String, Object> e = entries.get(i);
            if (!"CREATE".equals(e.get("action"))) continue;
            if (Long.valueOf(first).equals(asLong(e.get("entityId"))) && "contact".equals(String.valueOf(e.get("entity")).toLowerCase())) firstAt = i;
            if (Long.valueOf(second).equals(asLong(e.get("entityId"))) && "contact".equals(String.valueOf(e.get("entity")).toLowerCase())) secondAt = i;
        }
        assertTrue(firstAt >= 0 && secondAt >= 0, "both creations must be on the first page of the log");
        assertTrue(secondAt < firstAt, "the newer entry must come first");
    }
}
