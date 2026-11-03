---
name: authentication-jwt
description: Stateless JWT login and request authentication for Spring Boot 4. Use when the app needs login, bearer tokens, or a Spring Security filter chain.
---

# JWT Authentication

## Provides
- `POST /api/auth/login` `{username,password}` → `{token}`; 401 bad credentials, 400 missing fields
- `JwtService.issue(userId, roles)` → token; `validate(token)` → `Claims` (subject, roles, issuedAt, expiresAt)
- The only `SecurityFilterChain`: stateless, CSRF off, CORS for `allowed-origins`; login and `/actuator/health` open; everything else 401 without a valid Bearer token
- Beans: delegating `PasswordEncoder` (`{bcrypt}`), `AuthenticationManager`
- `SecurityConfigurer` hook: declare a bean to add `HttpSecurity` rules; runs before the base rules

## Requires
- A `UserDetailsService` bean from the app, roles set with `.roles("ADMIN")`
- Env `JWT_SECRET`, at least 32 bytes

## Config
```yaml
mortar.auth.jwt:
  secret: ${JWT_SECRET}
  ttl-minutes: 60
  issuer: my-app
  allowed-origins: [http://localhost:3000]
```

## Integration
- Dependency `com.mortar:jwt-auth:1.0.0`. Token roles become `ROLE_<name>` authorities.
- Do NOT define a `SecurityFilterChain`, login endpoint, JWT parsing, token filter or `PasswordEncoder`; use `SecurityConfigurer` for extra rules.
- Encode seeded passwords with the injected `PasswordEncoder`.

## Conflicts
- authentication.oauth2 (mutually exclusive)
