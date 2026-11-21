package dev.reklo.oracle.reference;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reference app for prompt inventory-03 (see ContractServer). Run:
 * java -cp target/test-classes dev.reklo.oracle.reference.InventoryReferenceServer [port]
 * Bugs (-Dreference.bugs=): duplicateSkuAllowed (03.2), negativeStockOnCreate (03.3), negativeStockOnUpdate (03.3),
 * duplicateStockAllowed (03.4), lowStockSkipsEmpty (03.5), lowStockInclusive (03.5), staffCanCreateItem (03.6),
 * staffCanDeleteItem (03.6), managerCanDeleteLocation (03.6), totalWrong (03.7), csvOpenToStaff (03.8).
 * Variant (must still pass everything): lowStockPagedShape returns the low-stock items as {"content": [...]}.
 */
public final class InventoryReferenceServer extends ContractServer {
    public static void main(String[] args) throws IOException {
        new InventoryReferenceServer().start(portFrom(args));
    }

    @Override
    protected List<Entity> entities() {
        return List.of(
                Entity.of("item", "items", List.of("sku", "name", "unit", "reorderLevel"), Set.of("sku", "name", "unit", "reorderLevel")).withCsv(),
                Entity.of("location", "locations", List.of("name", "address"), Set.of("name", "address")),
                Entity.of("stock", "stocks", List.of("itemId", "locationId", "quantity"), Set.of("itemId", "locationId", "quantity")).withCsv());
    }

    @Override
    protected Map<String, Long> userIds() {
        return Map.of("admin", 1L, "manager", 2L, "staff", 3L);
    }

    @Override
    protected Map<String, Set<String>> permissions() {
        return Map.of(
                "admin", Set.of("*"),
                "manager", Set.of("item:*", "stock:*", "location:read", "location:write"),
                "staff", Set.of("item:read", "stock:read", "stock:write", "location:read"));
    }

    @Override
    protected boolean allowed(String user, String entity, String action) {
        if (bug("staffCanCreateItem") && user.equals("staff") && entity.equals("item") && action.equals("write")) return true;
        if (bug("staffCanDeleteItem") && user.equals("staff") && entity.equals("item") && action.equals("delete")) return true;
        if (bug("managerCanDeleteLocation") && user.equals("manager") && entity.equals("location") && action.equals("delete")) return true;
        if (bug("csvOpenToStaff") && action.equals("export")) return allowed(user, entity, "read");
        return super.allowed(user, entity, action);
    }

    @Override
    protected void validate(Entity e, Map<String, Object> in, Map<String, Object> current, String user) {
        if (e.name().equals("item")) {
            for (Map<String, Object> other : all("items")) {
                boolean same = String.valueOf(other.get("sku")).equals(String.valueOf(in.get("sku")));
                boolean self = current != null && other.get("id").equals(current.get("id"));
                if (same && !self && !bug("duplicateSkuAllowed")) throw new Bad(409, "duplicate sku");
            }
        } else if (e.name().equals("stock")) {
            if (!(in.get("quantity") instanceof Number q)) throw new Bad(400, "quantity is required");
            boolean skip = current == null ? bug("negativeStockOnCreate") : bug("negativeStockOnUpdate");
            if (q.doubleValue() < 0 && !skip) throw new Bad(400, "quantity may not be negative");
            for (Map<String, Object> other : all("stocks")) {
                boolean same = String.valueOf(other.get("itemId")).equals(String.valueOf(in.get("itemId")))
                        && String.valueOf(other.get("locationId")).equals(String.valueOf(in.get("locationId")));
                boolean self = current != null && other.get("id").equals(current.get("id"));
                if (same && !self && !bug("duplicateStockAllowed")) throw new Bad(409, "stock already exists for this item and location");
            }
        }
    }

    @Override
    protected Object custom(String method, String path, String user, Map<String, String> query) {
        if (!method.equals("GET") || !path.equals("items/low-stock")) return null;
        check(user, "item", "read");
        List<Map<String, Object>> low = new ArrayList<>();
        for (Map<String, Object> item : all("items")) {
            double total = 0;
            boolean hasStock = false;
            for (Map<String, Object> s : all("stocks")) {
                if (s.get("itemId") instanceof Number n && n.longValue() == asLong(item.get("id"))) {
                    total += ((Number) s.get("quantity")).doubleValue();
                    hasStock = true;
                }
            }
            if (!hasStock && bug("lowStockSkipsEmpty")) continue;
            double reorder = ((Number) item.get("reorderLevel")).doubleValue();
            if (bug("lowStockInclusive") ? total <= reorder : total < reorder) low.add(item);
        }
        return bug("lowStockPagedShape") ? Map.of("content", low, "totalElements", (long) low.size(), "page", 0L, "size", 100L) : low;
    }
}
