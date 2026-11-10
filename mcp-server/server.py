"""Mortar registry as an MCP server: three read-only tools over the module registry.

Run from the repository root:  python mcp-server/server.py
Claude Code:  claude mcp add mortar -- python /path/to/mortar/mcp-server/server.py
Needs `pip install -e "orchestrator[mcp]"`. The server answers from SKILL.md contracts and metadata;
it never returns module source code.
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "orchestrator"))

from mcp.server.mcpserver import MCPServer  # noqa: E402
from mcp.server.mcpserver.exceptions import ToolError  # noqa: E402

from mortar.registry import query  # noqa: E402
from mortar.registry.loader import load_registry  # noqa: E402

REGISTRY = load_registry()

server = MCPServer(
    "mortar-registry",
    instructions=(
        "Tested Spring Boot modules. Call search_components to find a module, get_skill to read how to "
        "use it, get_dependencies to see what it needs. Prefer these modules over writing the code again."
    ),
)


@server.tool()
def search_components(query_text: str, limit: int = 10) -> list[dict[str, Any]]:
    """Find registry modules by keyword (for example "auth", "csv", "docker"). Best match first."""
    return query.search_components(REGISTRY, query_text, limit)


@server.tool()
def get_skill(module_id: str) -> str:
    """Return a module's SKILL.md: what it provides, requires, its config and the integration rules."""
    try:
        return query.get_skill(REGISTRY, module_id)
    except query.UnknownModule:
        raise ToolError(f"unknown module {module_id!r}; known: {', '.join(sorted(REGISTRY))}") from None


@server.tool()
def get_dependencies(module_id: str) -> dict[str, Any]:
    """Return what a module requires (capabilities, modules, environment), conflicts and version ranges."""
    try:
        return query.get_dependencies(REGISTRY, module_id)
    except query.UnknownModule:
        raise ToolError(f"unknown module {module_id!r}; known: {', '.join(sorted(REGISTRY))}") from None


if __name__ == "__main__":
    server.run()
