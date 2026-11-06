---
name: deployment-docker
description: Docker Compose deployment, Flyway baseline and health endpoint for a Mortar app. Use when the app must start with docker compose up.
---

# Docker Deployment

## Provides
- Files copied to the repo root by the assembler: `docker-compose.yml`, `backend/Dockerfile`, `frontend/Dockerfile`, `frontend/nginx.conf`, `.dockerignore`, `.env.example`
- Services `db` (postgres:16), `backend` (8080), `frontend` (3000); backend waits for a healthy db
- Flyway baseline `V0_1__mortar_audit_log.sql` creates `audit_log`
- `GET /actuator/health` returns `{"status":"UP"}`, nothing else exposed
- Hibernate `ddl-auto=update` creates the app's own tables

## Requires
- Docker with Compose; Spring Boot app in `backend/`, React app in `frontend/`
- Env `POSTGRES_PASSWORD`; `JWT_SECRET` when authentication.jwt is used

## Config
```yaml
appName: customer-crm
databaseName: customer_crm
databaseUser: crm
```

## Integration
- Dependency `com.mortar:docker-deployment:1.0.0`. Own migrations go in `db/migration` as `V1__...` or higher.
- Do NOT write a Dockerfile, docker-compose.yml, nginx config, Actuator or Flyway setup, or an `audit_log` migration.

## Conflicts
- None
