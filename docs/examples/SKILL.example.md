---
name: authentication-jwt
description: Stateless JWT auth for Spring Boot. Use when the app needs login, token issuing/validation, or a Spring Security filter chain.
---

# JWT Authentication

## Provides
- `JwtService.issue(userId, roles)` → token
- `JwtService.validate(token)` → Claims
- Auto-registered `SecurityFilterChain` with JWT filter

## Requires
- PostgreSQL (user table); env `JWT_SECRET` (min 32 chars), `JWT_TTL_MINUTES`

## Config (application.yml)
```yaml
mortar:
  auth:
    jwt:
      secret: ${JWT_SECRET}
      ttl-minutes: ${JWT_TTL_MINUTES:60}
```

## Integration
- Add dependency `com.mortar:jwt-auth:1.0.0`
- Do NOT define your own SecurityFilterChain; this module owns it
- Inject `JwtService` in the login controller

## Conflicts
- authentication.oauth2 (mutually exclusive)
