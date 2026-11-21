package dev.reklo.oracle.inventory;

import static dev.reklo.oracle.harness.Checks.createdOrOk;
import static dev.reklo.oracle.harness.Checks.ids;
import static dev.reklo.oracle.harness.Checks.list;
import static dev.reklo.oracle.harness.Checks.unique;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.reklo.oracle.harness.Api;
import dev.reklo.oracle.harness.OracleBase;
import dev.reklo.oracle.harness.Resp;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * API oracle for benchmark prompt inventory-03 (evaluator/benchmark/prompts/inventory-03.yaml, criteria inventory-03.1
 * to inventory-03.8). Black box: only the application contract (docs/benchmark-design.md section 5) and the seeded
 * users admin, manager and staff. inventory-03.9 (UI smoke) is the Playwright oracle.
 *
 * The REST path of Stock is /api/stocks (contract naming). Setup creations accept 200 or 201, as inventory-03.2 does.
 */
@Tag("inventory-03")
class InventoryApiOracle extends OracleBase {
    private static final String ADMIN = "admin";
    private static final String MANAGER = "manager";
    private static final String STAFF = "staff";

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> item(String sku, int reorderLevel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sku", sku);
        m.put("name", "Item " + sku);
        m.put("unit", "pcs");
        m.put("reorderLevel", reorderLevel);
        return m;
    }

    private static long createItem(int reorderLevel) {
        return createdOrOk(Api.post("/api/items", Api.as(MANAGER), item("SKU-" + unique(), reorderLevel)), "an item").num("id");
    }

    private static long createLocation(String user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Location " + unique());
        m.put("address", "1 Test Street");
        return createdOrOk(Api.post("/api/locations", Api.as(user), m), "a location").num("id");
    }

    private static Map<String, Object> stock(long itemId, long locationId, Object quantity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemId", itemId);
        m.put("locationId", locationId);
        m.put("quantity", quantity);
        return m;
    }

    private static long createStock(long itemId, long locationId, int quantity) {
        return createdOrOk(Api.post("/api/stocks", Api.as(MANAGER), stock(itemId, locationId, quantity)), "a stock record").num("id");
    }

    /** The items of a low-stock response, which the contract does not shape: a JSON array or a list object with `content`. */
    @SuppressWarnings("unchecked")
    private static Set<Long> lowStockIds(String user) {
        Resp r = Api.get("/api/items/low-stock?size=100", Api.as(user));
        assertEquals(200, r.status(), "GET /api/items/low-stock should return 200, got: " + r);
        Object json = r.json();
        List<Map<String, Object>> rows;
        if (json instanceof List<?> l) rows = (List<Map<String, Object>>) l;
        else if (json instanceof Map<?, ?> m && m.get("content") instanceof List<?> c) rows = (List<Map<String, Object>>) c;
        else throw new AssertionError("low-stock must return an array of items or an object with `content`, got: " + r);
        Set<Long> ids = new HashSet<>();
        for (Map<String, Object> row : rows) ids.add(((Number) row.get("id")).longValue());
        return ids;
    }

    // ---------------------------------------------------------------- inventory-03.1 authentication

    @Test
    @Tag("inventory-03.1")
    void loginWithValidCredentialsReturnsAToken() {
        Resp r = Api.post("/api/auth/login", null, Map.of("username", ADMIN, "password", "Admin#12345"));
        assertEquals(200, r.status(), r.toString());
        assertFalse(r.str("token") == null || r.str("token").isBlank(), "login response must contain a token: " + r);
    }

    @Test
    @Tag("inventory-03.1")
    void loginWithWrongPasswordIsRejected() {
        Resp r = Api.post("/api/auth/login", null, Map.of("username", STAFF, "password", "wrong-password"));
        assertEquals(401, r.status(), r.toString());
    }

    @Test
    @Tag("inventory-03.1")
    void protectedEndpointsRequireAToken() {
        assertEquals(401, Api.get("/api/items", null).status());
        assertEquals(401, Api.get("/api/locations", null).status());
        assertEquals(401, Api.get("/api/stocks", null).status());
    }

    // ---------------------------------------------------------------- inventory-03.2 unique sku

    @Test
    @Tag("inventory-03.2")
    void aDuplicateSkuIsRejectedWith409() {
        String sku = "SKU-" + unique();
        Resp first = Api.post("/api/items", Api.as(MANAGER), item(sku, 0));
        createdOrOk(first, "an item with a new sku");
        Resp second = Api.post("/api/items", Api.as(MANAGER), item(sku, 0));
        assertEquals(409, second.status(), "a second item with sku " + sku + " must return 409, got: " + second);
        assertEquals(200, Api.get("/api/items/" + first.num("id"), Api.as(MANAGER)).status(), "the first item must be unaffected");
    }

    // ---------------------------------------------------------------- inventory-03.3 quantity never negative

    @Test
    @Tag("inventory-03.3")
    void aNegativeQuantityIsRejectedOnCreate() {
        long itemId = createItem(0);
        long locationId = createLocation(ADMIN);
        Resp r = Api.post("/api/stocks", Api.as(MANAGER), stock(itemId, locationId, -1));
        assertEquals(400, r.status(), "a negative quantity must be rejected with 400, got: " + r);
    }

    @Test
    @Tag("inventory-03.3")
    void aNegativeQuantityIsRejectedOnUpdate() {
        long itemId = createItem(0);
        long locationId = createLocation(ADMIN);
        long stockId = createStock(itemId, locationId, 5);
        Resp r = Api.put("/api/stocks/" + stockId, Api.as(MANAGER), stock(itemId, locationId, -1));
        assertEquals(400, r.status(), "updating to a negative quantity must be rejected with 400, got: " + r);
        assertEquals(5, Api.get("/api/stocks/" + stockId, Api.as(MANAGER)).num("quantity"), "a rejected update must not change the quantity");
    }

    // ---------------------------------------------------------------- inventory-03.4 one stock record per pair

    @Test
    @Tag("inventory-03.4")
    void aSecondStockRecordForTheSamePairIsRejectedWith409() {
        long itemId = createItem(0);
        long locationId = createLocation(ADMIN);
        long otherLocationId = createLocation(ADMIN);
        long otherItemId = createItem(0);
        long first = createStock(itemId, locationId, 5);

        Resp dup = Api.post("/api/stocks", Api.as(MANAGER), stock(itemId, locationId, 7));
        assertEquals(409, dup.status(), "a second stock record for the same item and location must return 409, got: " + dup);
        assertEquals(5, Api.get("/api/stocks/" + first, Api.as(MANAGER)).num("quantity"), "the existing record must be unchanged");

        createStock(itemId, otherLocationId, 1); // same item, other location: fine
        createStock(otherItemId, locationId, 1); // other item, same location: fine
    }

    // ---------------------------------------------------------------- inventory-03.5 low stock

    @Test
    @Tag("inventory-03.5")
    void lowStockListsExactlyTheItemsBelowTheirReorderLevel() {
        long l1 = createLocation(ADMIN);
        long l2 = createLocation(ADMIN);
        long low = createItem(10); // 3 + 4 = 7 < 10
        createStock(low, l1, 3);
        createStock(low, l2, 4);
        long atLevel = createItem(10); // 6 + 4 = 10, not below
        createStock(atLevel, l1, 6);
        createStock(atLevel, l2, 4);
        long plenty = createItem(10); // 20 >= 10
        createStock(plenty, l1, 20);
        long noStock = createItem(5); // no stock records: total 0 < 5
        long noStockNoLevel = createItem(0); // no stock records: total 0, not below 0

        Set<Long> ours = Set.of(low, atLevel, plenty, noStock, noStockNoLevel);
        Set<Long> found = new HashSet<>(lowStockIds(MANAGER));
        found.retainAll(ours);
        assertEquals(Set.of(low, noStock), found, "low-stock must list exactly the items whose summed quantity is below reorderLevel, including items with no stock records");

        createStock(noStock, l1, 5); // now 5, not below 5
        found = new HashSet<>(lowStockIds(MANAGER));
        found.retainAll(ours);
        assertEquals(Set.of(low), found, "an item whose total reaches its reorderLevel leaves the low-stock list");
    }

    // ---------------------------------------------------------------- inventory-03.6 roles

    @Test
    @Tag("inventory-03.6")
    void staffCanReadItemsAndWriteStockButNotCreateOrDeleteItems() {
        long itemId = createItem(0);
        long locationId = createLocation(ADMIN);
        String staff = Api.as(STAFF);
        assertEquals(200, Api.get("/api/items", staff).status());
        assertEquals(200, Api.get("/api/items/" + itemId, staff).status());
        Resp stockWrite = Api.post("/api/stocks", staff, stock(itemId, locationId, 3));
        createdOrOk(stockWrite, "stock as STAFF");

        assertEquals(403, Api.post("/api/items", staff, item("SKU-" + unique(), 0)).status(), "STAFF must not create items");
        assertEquals(403, Api.delete("/api/items/" + itemId, staff).status(), "STAFF must not delete items");
        assertEquals(200, Api.get("/api/items/" + itemId, Api.as(MANAGER)).status(), "a forbidden delete must not remove the item");
    }

    @Test
    @Tag("inventory-03.6")
    void managerCannotDeleteLocationsButAdminCan() {
        long locationId = createLocation(ADMIN);
        assertEquals(403, Api.delete("/api/locations/" + locationId, Api.as(MANAGER)).status(), "MANAGER must not delete locations");
        assertEquals(200, Api.get("/api/locations/" + locationId, Api.as(ADMIN)).status(), "a forbidden delete must not remove the location");
        assertEquals(204, Api.delete("/api/locations/" + locationId, Api.as(ADMIN)).status(), "ADMIN can delete a location");
        assertEquals(404, Api.get("/api/locations/" + locationId, Api.as(ADMIN)).status());
    }

    // ---------------------------------------------------------------- inventory-03.7 pagination

    @Test
    @Tag("inventory-03.7")
    void itemListsArePaginatedWithAccurateTotals() {
        long before = list("/api/items", MANAGER, "page=0&size=10").num("totalElements");
        for (int i = 0; i < 25; i++) createItem(0);

        Resp first = list("/api/items", MANAGER, "page=0&size=10");
        assertEquals(before + 25, first.num("totalElements"), "totalElements must count every item");
        assertEquals(10, first.content().size(), "a full page must hold `size` items");
        assertEquals(0, first.num("page"));
        assertEquals(10, first.num("size"));

        long lastPage = (before + 25 - 1) / 10;
        Resp last = list("/api/items", MANAGER, "page=" + lastPage + "&size=10");
        assertEquals(before + 25 - lastPage * 10, last.content().size(), "the last page holds the remainder");

        Set<Long> firstIds = ids(first);
        for (Long id : ids(list("/api/items", MANAGER, "page=1&size=10"))) assertFalse(firstIds.contains(id), "pages must not overlap");
    }

    @Test
    @Tag("inventory-03.7")
    void sizeAbove100IsRejectedAndSizeUpTo100IsHonored() {
        assertEquals(100, list("/api/items", MANAGER, "size=100").num("size"));
        assertEquals(400, Api.get("/api/items?size=101", Api.as(MANAGER)).status());
        assertEquals(400, Api.get("/api/items?sort=notAField,asc", Api.as(MANAGER)).status());
    }

    // ---------------------------------------------------------------- inventory-03.8 CSV export

    @Test
    @Tag("inventory-03.8")
    void managerCanExportItemsAsCsv() {
        String sku = "SKU-" + unique();
        createdOrOk(Api.post("/api/items", Api.as(MANAGER), item(sku, 0)), "an item");
        long total = list("/api/items", MANAGER, "size=100").num("totalElements");

        Resp csv = Api.get("/api/items/export.csv", Api.as(MANAGER));
        assertEquals(200, csv.status(), csv.toString());
        String type = csv.header("Content-Type");
        assertTrue(type != null && type.toLowerCase().startsWith("text/csv"), "Content-Type must be text/csv, was " + type);

        String[] lines = csv.body().strip().split("\\r?\\n");
        List<String> header = List.of(lines[0].split(","));
        for (String column : List.of("sku", "name", "unit", "reorderLevel")) assertTrue(header.contains(column), "header row must name the field '" + column + "': " + lines[0]);
        assertTrue(Set.of("id", "sku", "name", "unit", "reorderLevel").containsAll(header), "header holds only item fields: " + lines[0]);
        assertEquals(total + 1, lines.length, "one row per item plus the header");
        assertTrue(csv.body().contains(sku), "the new item must be in the export");
    }

    @Test
    @Tag("inventory-03.8")
    void staffCannotExport() {
        assertEquals(403, Api.get("/api/items/export.csv", Api.as(STAFF)).status());
    }
}
