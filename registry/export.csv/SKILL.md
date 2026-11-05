---
name: export-csv
description: CSV download of an entity list for Spring Boot 4 and JPA. Use when users need to export a table as a CSV file.
---

# CSV Export

## Provides
- `CsvExportController<T, R>`: `GET <base>/export.csv` returns `text/csv` as an attachment; header row = the listed column names, then one row per entity ordered by `id`
- RFC 4180 quoting and CRLF; read in batches, so large tables are not loaded at once
- Cells starting with `= + - @` get a leading `'` so spreadsheets do not run them as formulas
- No permission checks of its own; `authorization.rbac` guards the path with `export`

## Requires
- A JPA entity with `Long id` and a getter for every column, and a `JpaRepository<T, Long>`
- A datasource (PostgreSQL)

## Config
```yaml
mortar.export.csv:
  batch-size: 500
  neutralize-formulas: true
```

## Integration
- Dependency `com.mortar:csv-export:1.0.0`. Per entity: subclass with the same `@RestController @RequestMapping("/api/<plural>")` as its CRUD controller and call `super(repo, props, List.of("id", "name"), "<plural>.csv")`.
- Do NOT write CSV escaping or writers, an export handler, or a streaming loop; list only columns that are safe to export.
- Columns must be property names of the entity; an unknown name fails at startup.

## Conflicts
- None
