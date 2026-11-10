"""The MCP server, driven by a real MCP client: in memory, and over stdio as Claude Code would."""

import asyncio
import json
import sys
from pathlib import Path

import pytest

pytest.importorskip("mcp")
from mcp.client import Client  # noqa: E402
from mcp.client.stdio import StdioServerParameters  # noqa: E402

SERVER_DIR = Path(__file__).resolve().parents[2] / "mcp-server"


def run(coro):
    return asyncio.run(coro)


def test_lists_exactly_the_three_tools():
    sys.path.insert(0, str(SERVER_DIR))
    import server

    async def go():
        async with Client(server.server) as client:
            return sorted(t.name for t in (await client.list_tools()).tools)

    assert run(go()) == ["get_dependencies", "get_skill", "search_components"]


def test_tools_answer_and_errors_carry_a_message():
    sys.path.insert(0, str(SERVER_DIR))
    import server

    async def go():
        async with Client(server.server) as client:
            found = await client.call_tool("search_components", {"query_text": "auth", "limit": 2})
            skill = await client.call_tool("get_skill", {"module_id": "export.csv"})
            deps = await client.call_tool("get_dependencies", {"module_id": "audit.logging"})
            bad = await client.call_tool("get_skill", {"module_id": "nope"})
            return found, skill, deps, bad

    found, skill, deps, bad = run(go())
    assert json.loads(found.content[0].text)["module_id"] == "authentication.jwt"
    assert "Do NOT" in skill.content[0].text
    assert json.loads(deps.content[0].text)["requires_modules"] == ["authentication.jwt"]
    assert bad.is_error and "unknown module 'nope'" in bad.content[0].text


def test_works_over_stdio():
    params = StdioServerParameters(command=sys.executable, args=[str(SERVER_DIR / "server.py")])

    async def go():
        async with Client(params) as client:
            tools = await client.list_tools()
            result = await client.call_tool("search_components", {"query_text": "auth", "limit": 1})
            return len(tools.tools), result

    count, result = run(go())
    assert count == 3 and "authentication.jwt" in result.content[0].text
