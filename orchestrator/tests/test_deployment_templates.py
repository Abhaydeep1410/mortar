"""Static checks of the files shipped by registry/deployment.docker (no Java, no Docker daemon).

They keep the Compose file, Dockerfiles, nginx config, env template, config schema and Flyway
baseline consistent with each other and with the audit.logging entity and the application contract
(ports 8080 and 3000, PostgreSQL 16, health endpoint).
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "registry" / "deployment.docker"
FILES = MODULE / "module" / "src" / "main" / "resources" / "mortar" / "deployment"
COMPOSE = FILES / "docker-compose.yml"
AUDIT_ENTITY = ROOT / "registry" / "audit.logging" / "module" / "src" / "main" / "java" / "com" / "mortar" / "audit" / "AuditLog.java"
MIGRATION = MODULE / "module" / "src" / "main" / "resources" / "db" / "migration" / "V0_1__mortar_audit_log.sql"


@pytest.fixture(scope="module")
def compose() -> dict:
    return yaml.safe_load(COMPOSE.read_text(encoding="utf-8"))


def test_compose_has_the_three_services_with_the_contract_ports(compose):
    services = compose["services"]
    assert set(services) == {"db", "backend", "frontend"}
    assert services["db"]["image"] == "postgres:16"
    assert "8080:8080" in services["backend"]["ports"]
    assert "3000:3000" in services["frontend"]["ports"]
    assert "ports" not in services["db"], "the database must not be published to the host"


def test_startup_order_waits_for_health(compose):
    services = compose["services"]
    assert services["db"]["healthcheck"]["test"][1].startswith("pg_isready")
    assert services["backend"]["depends_on"]["db"]["condition"] == "service_healthy"
    assert "/actuator/health" in " ".join(services["backend"]["healthcheck"]["test"])
    assert services["frontend"]["depends_on"]["backend"]["condition"] == "service_healthy"


def test_no_secret_is_written_into_the_compose_file(compose):
    text = COMPOSE.read_text(encoding="utf-8")
    assert "${POSTGRES_PASSWORD:?" in text, "the database password must be required from .env"
    for line in text.splitlines():
        if re.search(r"(PASSWORD|SECRET)\s*:", line):
            assert "${" in line, f"secret-looking setting must come from the environment: {line.strip()}"


def test_backend_receives_the_settings_the_other_modules_read(compose):
    env = compose["services"]["backend"]["environment"]
    assert env["SPRING_DATASOURCE_URL"].startswith("jdbc:postgresql://db:5432/")
    assert "MORTAR_AUTH_JWT_SECRET" in env, "authentication.jwt binds mortar.auth.jwt.secret"
    assert "MORTAR_AUTH_JWT_ALLOWED_ORIGINS" in env
    assert "http://localhost:3000" in env["MORTAR_AUTH_JWT_ALLOWED_ORIGINS"]


@pytest.mark.parametrize("name", ["backend/Dockerfile", "frontend/Dockerfile"])
def test_dockerfiles_are_multi_stage_and_pinned(name):
    text = (FILES / name).read_text(encoding="utf-8")
    froms = re.findall(r"^FROM\s+(\S+)", text, flags=re.M)
    assert len(froms) >= 2, "multi-stage build expected"
    assert all(":" in image and not image.endswith(":latest") for image in froms), froms


def test_backend_image_runs_as_non_root_on_java_21():
    text = (FILES / "backend" / "Dockerfile").read_text(encoding="utf-8")
    assert "temurin-21" in text and "eclipse-temurin:21-jre" in text
    assert re.search(r"^USER\s+app\s*$", text, flags=re.M)
    assert "curl" in text, "the Compose healthcheck uses curl"


def test_frontend_is_served_on_3000_with_spa_fallback():
    nginx = (FILES / "frontend" / "nginx.conf").read_text(encoding="utf-8")
    assert re.search(r"listen\s+3000;", nginx)
    assert "try_files $uri /index.html" in nginx
    assert "EXPOSE 3000" in (FILES / "frontend" / "Dockerfile").read_text(encoding="utf-8")


def test_env_template_placeholders_are_all_config_schema_properties():
    schema = json.loads((MODULE / "config-schema.json").read_text(encoding="utf-8"))
    template = (FILES / ".env.example.tmpl").read_text(encoding="utf-8")
    placeholders = set(re.findall(r"\{\{(\w+)\}\}", template))
    assert placeholders == set(schema["properties"])
    assert "POSTGRES_PASSWORD=change-me" in template and "JWT_SECRET=" in template
    for variable in ("POSTGRES_PASSWORD", "JWT_SECRET", "ALLOWED_ORIGINS", "PUBLIC_API_URL"):
        assert variable in COMPOSE.read_text(encoding="utf-8")


def test_dockerignore_keeps_secrets_and_build_output_out_of_images():
    lines = (FILES / ".dockerignore").read_text(encoding="utf-8").split()
    assert {".env", "**/target", "**/node_modules"} <= set(lines)


def test_flyway_baseline_matches_the_audit_log_entity():
    entity = AUDIT_ENTITY.read_text(encoding="utf-8")
    sql = MIGRATION.read_text(encoding="utf-8")
    assert re.search(r'@Table\(\s*name\s*=\s*"audit_log"', entity)
    assert "CREATE TABLE audit_log" in sql
    columns = set(re.findall(r'@Column\([^)]*name\s*=\s*"(\w+)"', entity)) | {"id", "actor", "action", "entity"}
    for column in columns:
        assert re.search(rf"^\s+{column}\s", sql, flags=re.M), f"{column} is in AuditLog but not in the migration"
    for index in re.findall(r'@Index\(name\s*=\s*"(\w+)"', entity):
        assert f"INDEX {index} " in sql, f"index {index} missing from the migration"
    assert re.search(r"actor\s+VARCHAR\(255\)\s+NOT NULL", sql)
    assert re.search(r"occurred_at\s+TIMESTAMP\(6\) WITH TIME ZONE NOT NULL", sql)


def test_default_properties_expose_only_health():
    props = (MODULE / "module" / "src" / "main" / "resources" / "mortar-deployment.properties").read_text(encoding="utf-8")
    assert "management.endpoints.web.exposure.include=health" in props
    assert "management.endpoint.health.show-details=never" in props


@pytest.mark.skipif(shutil.which("docker") is None, reason="docker CLI not installed")
def test_docker_compose_config_accepts_the_file(tmp_path):
    env = {**os.environ, "POSTGRES_PASSWORD": "x", "JWT_SECRET": "y" * 40}
    result = subprocess.run(
        ["docker", "compose", "-f", str(COMPOSE), "config", "--quiet"],
        capture_output=True, text=True, env=env, cwd=tmp_path,
    )
    if "unknown shorthand flag" in result.stderr or "is not a docker command" in result.stderr:
        pytest.skip("docker compose plugin not available")
    assert result.returncode == 0, result.stderr


@pytest.mark.skipif(shutil.which("docker") is None, reason="docker CLI not installed")
def test_docker_compose_config_requires_the_database_password(tmp_path):
    env = {k: v for k, v in os.environ.items() if k != "POSTGRES_PASSWORD"}
    result = subprocess.run(
        ["docker", "compose", "-f", str(COMPOSE), "config", "--quiet"],
        capture_output=True, text=True, env=env, cwd=tmp_path,
    )
    if "unknown shorthand flag" in result.stderr or "is not a docker command" in result.stderr:
        pytest.skip("docker compose plugin not available")
    assert result.returncode != 0 and "POSTGRES_PASSWORD" in result.stderr
