"""One narrow model interface for both agents: force a single tool call, return its arguments and usage.

Usage and cost are mandatory. A response without token counts or a cost raises `LlmError`; nothing is
estimated (CLAUDE.md rule 1).
"""

from __future__ import annotations

import json
import os
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Protocol


class LlmError(RuntimeError):
    """The model call failed or its response cannot be used as a measurement."""


@dataclass(frozen=True)
class Usage:
    input_tokens: int
    output_tokens: int
    cost_usd: float


@dataclass(frozen=True)
class ToolResult:
    arguments: dict[str, Any]
    usage: Usage


class LlmClient(Protocol):
    model: str

    def call_tool(self, system: str, user: str, tool: dict[str, Any]) -> ToolResult: ...


def tool_spec(name: str, description: str, parameters: dict[str, Any]) -> dict[str, Any]:
    return {"type": "function", "function": {"name": name, "description": description, "parameters": parameters}}


def _litellm_completion(**kwargs: Any) -> Any:
    import litellm  # type: ignore

    return litellm.completion(**kwargs)


def _litellm_cost(response: Any) -> float:
    import litellm  # type: ignore

    return float(litellm.completion_cost(completion_response=response))


class LitellmClient:
    """Real model calls through litellm (imported lazily). `completion` and `cost` are injectable."""

    def __init__(
        self,
        model: str,
        temperature: float = 0.0,
        max_output_tokens: int = 32000,
        completion: Callable[..., Any] = _litellm_completion,
        cost: Callable[[Any], float] = _litellm_cost,
    ) -> None:
        if not model:
            raise LlmError("no model configured; set MORTAR_MODEL to the pinned benchmark model id")
        self.model = model
        self._temperature = temperature
        self._max_output_tokens = max_output_tokens
        self._completion = completion
        self._cost = cost

    @classmethod
    def from_env(cls) -> "LitellmClient":
        return cls(
            os.environ.get("MORTAR_MODEL", ""),
            float(os.environ.get("MORTAR_TEMPERATURE", "0.0")),
            int(os.environ.get("MORTAR_MAX_OUTPUT_TOKENS", "32000")),
        )

    def call_tool(self, system: str, user: str, tool: dict[str, Any]) -> ToolResult:
        name = tool["function"]["name"]
        response = self._completion(
            model=self.model,
            messages=[{"role": "system", "content": system}, {"role": "user", "content": user}],
            tools=[tool],
            tool_choice={"type": "function", "function": {"name": name}},
            temperature=self._temperature,
            max_tokens=self._max_output_tokens,
        )
        choice = response.choices[0]
        if getattr(choice, "finish_reason", None) == "length":
            raise LlmError("output truncated by the max output token limit (finish_reason=length)")
        calls = getattr(choice.message, "tool_calls", None) or []
        if len(calls) != 1 or calls[0].function.name != name:
            raise LlmError(f"expected exactly one {name} call, got {len(calls)}")
        try:
            arguments = json.loads(calls[0].function.arguments)
        except json.JSONDecodeError as exc:
            raise LlmError(f"malformed {name} arguments: {exc}") from exc
        if not isinstance(arguments, dict):
            raise LlmError(f"{name} arguments are not a JSON object")
        usage = getattr(response, "usage", None)
        tokens_in = getattr(usage, "prompt_tokens", None)
        tokens_out = getattr(usage, "completion_tokens", None)
        if not isinstance(tokens_in, int) or not isinstance(tokens_out, int):
            raise LlmError("response has no token usage; the run is invalid and must be repeated")
        try:
            cost = self._cost(response)
        except Exception as exc:  # noqa: BLE001 - any failure to price the call invalidates the run
            raise LlmError(f"cannot compute the cost of this call: {exc}") from exc
        return ToolResult(arguments, Usage(tokens_in, tokens_out, cost))
