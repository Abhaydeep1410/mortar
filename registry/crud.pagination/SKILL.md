---
name: crud-pagination
description: Generic REST CRUD with paging, sorting and text search for Spring Boot 4 and JPA. Use when an entity needs list, get, create, update and delete endpoints.
---

# CRUD and Pagination

## Provides
- `CrudController<T, R>`: `GET /` paged list, `GET /{id}`, `POST` 201, `PUT /{id}` 200, `DELETE /{id}` 204; unknown id 404
- List params `page` (from 0), `size`, `sort=field,asc|desc` (repeatable), `q` (case-insensitive contains over search fields); returns `{content,totalElements,page,size}`
- Bad input and validation errors: 400 `{message}`. Unlisted sort fields are rejected
- `CrudSettings.of(sortable, searchable)`, `.asReadOnly()` for 405 on writes; hook `beforeSave(entity, creating)`

## Requires
- A JPA entity with `Long id` and getters and setters, validation annotations, and a repository extending `JpaRepository<T, Long>` and `JpaSpecificationExecutor<T>`
- A datasource (PostgreSQL)

## Config
```yaml
mortar.crud:
  default-page-size: 20
  max-page-size: 100
```

## Integration
- Dependency `com.mortar:crud-pagination:1.0.0`. Per entity: subclass with `@RestController @RequestMapping("/api/<plural>")` and pass the repository, `CrudProperties` and `CrudSettings`.
- Do NOT write list, get, create, update or delete handlers, `Pageable` parsing, paging response classes, search queries or 400/404 handlers; use `beforeSave` for app rules.

## Conflicts
- None
