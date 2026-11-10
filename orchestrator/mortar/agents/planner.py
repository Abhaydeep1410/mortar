"""Planner (LLM #1): natural-language prompt -> validated blueprint. It never selects modules or writes code."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from ..engine.blueprint_validator import errors_only, validate_blueprint
from ..engine.vocab import CAPABILITIES
from ..llm import LlmClient, Usage, tool_spec
from ..registry.loader import REPO_ROOT

SPEC_PATH = REPO_ROOT / "docs" / "blueprint-spec.md"

SYSTEM_PROMPT = (
    "You are the planning stage of an application generator. You turn a request for an internal "
    "business tool into an Application Blueprint. You respond only by calling submit_blueprint once. "
    "You do not choose software modules and you do not write code."
)

SUBMIT_BLUEPRINT = tool_spec(
    "submit_blueprint",
    "Submit the complete Application Blueprint as a JSON object that follows the spec.",
    {
        "type": "object",
        "properties": {"blueprint": {"type": "object", "description": "The blueprint, in the shape of the spec."}},
        "required": ["blueprint"],
    },
)


class PlanError(RuntimeError):
    """The Planner could not produce a valid blueprint within the allowed attempts."""


@dataclass(frozen=True)
class PlanResult:
    blueprint: dict[str, Any]
    usages: tuple[Usage, ...]

    @property
    def attempts(self) -> int:
        return len(self.usages)


def build_user_prompt(prompt: str, issues: list[str] | None = None) -> str:
    parts = [
        "Blueprint spec (follow it exactly):",
        SPEC_PATH.read_text(encoding="utf-8"),
        "Capabilities you may request (closed list, use only these IDs): " + ", ".join(sorted(CAPABILITIES)),
        "Request a capability only when the application text needs it. Declare the application-specific "
        "logic that no generic module covers as custom_services. Leave `components` and `uncovered` empty.",
        "Application request:",
        prompt.strip(),
    ]
    if issues:
        parts += ["Your previous blueprint was rejected. Fix every problem:", *issues]
    return "\n\n".join(parts)


def _normalize(blueprint: dict[str, Any], prompt: str) -> dict[str, Any]:
    """Fields that are not the model's to decide: format version, the verbatim prompt, Selector output."""
    blueprint = dict(blueprint)
    blueprint["apiVersion"] = "mortar/v1"
    blueprint["kind"] = "Application"
    metadata = dict(blueprint.get("metadata") or {})
    metadata["prompt"] = prompt
    metadata.pop("timestamp", None)
    blueprint["metadata"] = metadata
    blueprint["components"] = []
    blueprint["uncovered"] = []
    return blueprint


def plan(prompt: str, client: LlmClient, max_attempts: int = 3) -> PlanResult:
    issues: list[str] | None = None
    usages: list[Usage] = []
    for _ in range(max_attempts):
        result = client.call_tool(SYSTEM_PROMPT, build_user_prompt(prompt, issues), SUBMIT_BLUEPRINT)
        usages.append(result.usage)
        raw = result.arguments.get("blueprint")
        if not isinstance(raw, dict):
            issues = ["The call must have a `blueprint` object."]
            continue
        blueprint = _normalize(raw, prompt)
        failed = errors_only(validate_blueprint(blueprint))
        if not failed:
            return PlanResult(blueprint, tuple(usages))
        issues = [str(issue) for issue in failed]
    raise PlanError(f"no valid blueprint after {max_attempts} attempts; last problems: {issues}")
