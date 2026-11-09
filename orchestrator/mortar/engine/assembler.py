"""Deterministic Assembler: a completed blueprint -> the generated project's fixed files.

It writes everything that does not need a model: the application pom and entry class, the Spring
configuration for each selected module, the vendored module sources, the Docker files, and the
React template with its one config file. The Composer later adds only the app-specific code under
`backend/src/main/java/<basePackage>/`. Every file goes through the event log with its SHA-256.
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from xml.sax.saxutils import escape

import yaml
from jsonschema import Draft202012Validator

from ..events import EventLog
from ..registry.loader import REPO_ROOT, ModuleInfo
from .naming import entity_table

TEMPLATE_DIR = REPO_ROOT / "templates" / "react-admin-v1"
FRONTEND_CONFIG_SCHEMA = TEMPLATE_DIR / "config.schema.json"
DEPLOYMENT_FILES = Path("module/src/main/resources/mortar/deployment")
SPRING_BOOT_VERSION = "4.1.1"  # keep equal to the registry module POMs (a test checks)
SKIP_TEMPLATE_DIRS = {"node_modules", "dist"}


class AssemblyError(ValueError):
    """The selection cannot be turned into a project."""


@dataclass(frozen=True)
class AssemblyResult:
    root: Path
    files: dict[str, str]  # relative posix path -> sha256


def render(text: str, values: Mapping[str, str]) -> str:
    """Replace `{{key}}`; a placeholder without a value is an error, never left in place."""
    missing = sorted(set(re.findall(r"\{\{(\w+)\}\}", text)) - set(values))
    if missing:
        raise AssemblyError(f"no value for placeholders: {missing}")
    return re.sub(r"\{\{(\w+)\}\}", lambda m: str(values[m.group(1)]), text)


def _config(selection: Mapping[str, Any], module_id: str) -> dict[str, Any] | None:
    for component in selection["components"]:
        if component["module_id"] == module_id:
            return component["config"]
    return None


def app_pom(blueprint: Mapping[str, Any], registry: Mapping[str, ModuleInfo]) -> str:
    base_package = blueprint["backend"]["basePackage"]
    group_id = ".".join(base_package.split(".")[:-1]) or base_package
    artifact_id = blueprint["metadata"]["name"]
    mortar = "".join(
        "    <dependency>\n"
        f"      <groupId>{registry[c['module_id']].group_id}</groupId>\n"
        f"      <artifactId>{registry[c['module_id']].artifact_id}</artifactId>\n"
        f"      <version>{registry[c['module_id']].artifact_version}</version>\n"
        "    </dependency>\n"
        for c in blueprint["components"]
    )
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>{escape(group_id)}</groupId>
  <artifactId>{escape(artifact_id)}</artifactId>
  <version>0.1.0</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>{int(blueprint["backend"]["javaVersion"])}</maven.compiler.release>
    <maven.compiler.parameters>true</maven.compiler.parameters>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <spring-boot.version>{SPRING_BOOT_VERSION}</spring-boot.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${{spring-boot.version}}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
{mortar}  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <version>${{spring-boot.version}}</version>
        <executions>
          <execution>
            <goals>
              <goal>repackage</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
"""


def vendor_pom(blueprint: Mapping[str, Any], registry: Mapping[str, ModuleInfo]) -> str:
    modules = "".join(
        f"    <module>{registry[c['module_id']].artifact_id}</module>\n" for c in blueprint["components"]
    )
    block = f"  <modules>\n{modules}  </modules>\n" if modules else ""
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.mortar</groupId>
  <artifactId>vendor</artifactId>
  <version>1.0.0</version>
  <packaging>pom</packaging>
{block}</project>
"""


def application_yml(blueprint: Mapping[str, Any]) -> str:
    """Spring settings for the selected modules, from their (already validated) configs."""
    mortar: dict[str, Any] = {}
    if cfg := _config(blueprint, "authentication.jwt"):
        jwt: dict[str, Any] = {
            "ttl-minutes": cfg["ttlMinutes"],
            "issuer": cfg["issuer"],
            "allowed-origins": cfg["allowedOrigins"],
        }
        if cfg["jwtSecretEnv"] != "JWT_SECRET":  # compose maps JWT_SECRET itself
            jwt["secret"] = "${" + cfg["jwtSecretEnv"] + ":}"
        mortar.setdefault("auth", {})["jwt"] = jwt
    if cfg := _config(blueprint, "authorization.rbac"):
        mortar.setdefault("auth", {})["rbac"] = {"roles": cfg["roles"], "resources": cfg["resources"]}
    if cfg := _config(blueprint, "audit.logging"):
        mortar["audit"] = {"entities": cfg["entities"], "admin-role": cfg["adminRole"]}
    if cfg := _config(blueprint, "crud.pagination"):
        mortar["crud"] = {
            "default-page-size": cfg["defaultPageSize"],
            "max-page-size": cfg["maxPageSize"],
        }
    if cfg := _config(blueprint, "export.csv"):
        mortar["export"] = {
            "csv": {"batch-size": cfg["batchSize"], "neutralize-formulas": cfg["neutralizeFormulas"]}
        }
    doc: dict[str, Any] = {"spring": {"application": {"name": blueprint["metadata"]["name"]}}}
    if mortar:
        doc["mortar"] = mortar
    return "# Generated by Mortar from the blueprint. Environment variables override these values.\n" + yaml.safe_dump(
        doc, sort_keys=False, default_flow_style=False
    )


def application_class(base_package: str) -> str:
    return (
        f"package {base_package};\n\n"
        "import org.springframework.boot.SpringApplication;\n"
        "import org.springframework.boot.autoconfigure.SpringBootApplication;\n\n"
        "@SpringBootApplication\n"
        "public class Application {\n"
        "  public static void main(String[] args) {\n"
        "    SpringApplication.run(Application.class, args);\n"
        "  }\n"
        "}\n"
    )


def frontend_config(blueprint: Mapping[str, Any]) -> dict[str, Any]:
    provided = {c["module_id"] for c in blueprint["components"]}
    searchable = "crud.pagination" in provided
    exportable = "export.csv" in provided
    entities = []
    for row in entity_table(blueprint):
        entity = {k: row[k] for k in ("name", "path", "resource", "fields")}
        if row["readOnly"]:
            entity["readOnly"] = True
        entity["searchable"] = searchable
        entity["exportable"] = exportable
        entities.append(entity)
    kanban = [
        {"entity": p["entity"], "groupBy": p["groupBy"]}
        for p in blueprint["frontend"]["pages"]
        if p["type"] == "kanban"
    ]
    config: dict[str, Any] = {
        "appName": blueprint["metadata"]["name"].replace("-", " ").title(),
        "entities": entities,
        "roles": [
            {"name": r["name"], "permissions": list(r["permissions"])} for r in blueprint["roles"]
        ],
    }
    if kanban:
        config["kanban"] = kanban
    schema = json.loads(FRONTEND_CONFIG_SCHEMA.read_text(encoding="utf-8"))
    errors = sorted(Draft202012Validator(schema).iter_errors(config), key=str)
    if errors:
        raise AssemblyError(f"frontend config invalid: {errors[0].message}")
    return config


class _Writer:
    def __init__(self, root: Path, log: EventLog | None) -> None:
        self.root = root
        self.log = log
        self.files: dict[str, str] = {}

    def write(self, relative: str, data: bytes, source: str) -> None:
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        digest = hashlib.sha256(data).hexdigest()
        self.files[relative] = digest
        if self.log:
            self.log.emit("file_written", path=relative, sha256=digest, source=source)

    def text(self, relative: str, text: str, source: str) -> None:
        self.write(relative, text.encode("utf-8"), source)

    def copy_tree(self, src: Path, dest: str, source: str, skip: frozenset[str] = frozenset()) -> None:
        for path in sorted(src.rglob("*")):
            parts = path.relative_to(src).parts
            if path.is_dir() or skip & set(parts):
                continue
            self.write(f"{dest}/{path.relative_to(src).as_posix()}", path.read_bytes(), source)


def assemble(
    blueprint: Mapping[str, Any],
    registry: Mapping[str, ModuleInfo],
    out_dir: Path,
    log: EventLog | None = None,
    template_dir: Path = TEMPLATE_DIR,
) -> AssemblyResult:
    """Write the generated project into `out_dir` (must not exist or be empty)."""
    if out_dir.exists() and any(out_dir.iterdir()):
        raise AssemblyError(f"output directory is not empty: {out_dir}")
    out_dir.mkdir(parents=True, exist_ok=True)
    w = _Writer(out_dir, log)

    base_package = blueprint["backend"]["basePackage"]
    w.text("backend/pom.xml", app_pom(blueprint, registry), "rendered")
    w.text("backend/src/main/resources/application.yml", application_yml(blueprint), "rendered")
    w.text(
        f"backend/src/main/java/{base_package.replace('.', '/')}/Application.java",
        application_class(base_package),
        "rendered",
    )

    w.text("backend/vendor/pom.xml", vendor_pom(blueprint, registry), "rendered")
    for component in blueprint["components"]:
        info = registry[component["module_id"]]
        w.copy_tree(info.module_dir, f"backend/vendor/{info.artifact_id}", f"module:{info.id}",
                    skip=frozenset({"target"}))

    w.copy_tree(template_dir, "frontend", "template:react-admin-v1", skip=frozenset(SKIP_TEMPLATE_DIRS))
    w.text(
        "frontend/src/mortar.config.json",
        json.dumps(frontend_config(blueprint), indent=2) + "\n",
        "rendered",
    )

    docker = _config(blueprint, "deployment.docker")
    if docker is not None:
        files = registry["deployment.docker"].path / DEPLOYMENT_FILES
        for name in ("docker-compose.yml", ".dockerignore"):
            w.write(name, (files / name).read_bytes(), "module:deployment.docker")
        w.write("backend/.dockerignore", (files / ".dockerignore").read_bytes(), "module:deployment.docker")
        w.write("frontend/.dockerignore", (files / ".dockerignore").read_bytes(), "module:deployment.docker")
        w.write("backend/Dockerfile", (files / "backend/Dockerfile").read_bytes(), "module:deployment.docker")
        for name in ("Dockerfile", "nginx.conf"):
            w.write(f"frontend/{name}", (files / "frontend" / name).read_bytes(), "module:deployment.docker")
        env = render((files / ".env.example.tmpl").read_text(encoding="utf-8"), docker)
        w.text(".env.example", env, "module:deployment.docker")

    w.text("blueprint.yaml", yaml.safe_dump(dict(blueprint), sort_keys=False, allow_unicode=True), "rendered")
    if log:
        log.emit("assembled", files=len(w.files))
    return AssemblyResult(out_dir, w.files)
