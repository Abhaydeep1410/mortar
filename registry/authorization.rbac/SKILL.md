---
name: authorization-rbac
description: Role and permission checks on /api/** for Spring Boot 4. Use when roles must restrict read, write, delete or CSV export per entity.
---

# RBAC Authorization

## Provides
- Rules per mapped resource: GET/HEAD need `read`; POST/PUT/PATCH need `write`; DELETE needs `delete`; `GET /api/<path>/export.csv` needs `export`. No permission: 403; no token: 401
- Permissions: `*`, `<resource>:*`, `<resource>:<action>`. Exact: `write` does not imply `read`
- `PermissionPolicy.allows(roles, resource, Action)` and `rolesFor(resource, Action)` for use in code
- Paths not listed under `resources` need only a valid token

## Requires
- authentication.jwt (adds its rules through `SecurityConfigurer`)
- Every entity URL listed in `resources`, or it is not protected

## Config
```yaml
mortar.auth.rbac:
  resources:
    - { path: contacts, name: contact }
  roles:
    - { name: ADMIN, permissions: ["*"] }
    - { name: VIEWER, permissions: ["contact:read"] }
```

## Integration
- Dependency `com.mortar:rbac-authorization:1.0.0`. Roles are the names the `UserDetailsService` assigns.
- Do NOT write `@PreAuthorize`, role checks in controllers, a `SecurityFilterChain`, or permission parsing.
- Controllers serve `/api/<path>`, `/api/<path>/{id}` and `/api/<path>/export.csv` only.

## Conflicts
- None
