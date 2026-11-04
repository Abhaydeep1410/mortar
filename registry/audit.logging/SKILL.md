---
name: audit-logging
description: Records who created, updated or deleted which entity, and serves the log to admins. Use when changes to data must be traceable to a user.
---

# Audit Logging

## Provides
- Every successful `POST /api/<path>`, `PUT /api/<path>/{id}`, `DELETE /api/<path>/{id}` on a configured path is saved: actor, action `CREATE|UPDATE|DELETE`, entity name, entity id, UTC timestamp. Reads, failures and rejected requests are not
- `GET /api/audit-logs?page=&size=&entity=&entityId=&actor=`, newest first, ADMIN only: `{content,totalElements,page,size}`. No write endpoints
- Table `audit_log`; entity `AuditLog` is found automatically

## Requires
- authentication.jwt (the actor is the signed-in username)
- A JPA datasource (PostgreSQL)
- `POST` must return `201` with a `Location` header ending in the new id; update and delete use the id in the URL

## Config
```yaml
mortar.audit:
  admin-role: ADMIN
  entities:
    - { path: contacts, name: contact }
```

## Integration
- Dependency `com.mortar:audit-logging:1.0.0`. List writable entities only.
- Do NOT write audit entities, repositories, filters, interceptors, `@Audited` annotations, calls to a logger from controllers, or an audit endpoint.
- Do NOT add a `SecurityFilterChain`; the rules arrive through `SecurityConfigurer`.

## Conflicts
- None
