"""Smoke test for the model endpoint, using the same litellm calls Mortar makes. Costs a few tokens.

Usage (cmd, venv active):
  set MORTAR_MODEL=openai/sensenova-v6-8-flash-lite
  set OPENAI_API_BASE=https://token.sensenova.ai/v1
  set OPENAI_API_KEY=<your key>
  python smoke_model.py
"""
import json
import os
import sys

import litellm

model = os.environ.get("MORTAR_MODEL", "")
if not model:
    sys.exit("set MORTAR_MODEL first")

print(f"model: {model}  base: {os.environ.get('OPENAI_API_BASE') or os.environ.get('OPENAI_BASE_URL')}")

# 1. plain call
r = litellm.completion(model=model, messages=[{"role": "user", "content": "Say hello in five words."}], max_tokens=50)
print("1. plain call ok:", repr(r.choices[0].message.content))
print("   usage:", r.usage.prompt_tokens, "in /", r.usage.completion_tokens, "out")

# 2. forced tool call, the way Planner/Composer call the model
tool = {"type": "function", "function": {"name": "report", "description": "Report a colour.",
        "parameters": {"type": "object", "properties": {"colour": {"type": "string"}}, "required": ["colour"]}}}
try:
    r = litellm.completion(model=model, messages=[{"role": "user", "content": "What colour is the sky? Use the tool."}],
                           tools=[tool], tool_choice={"type": "function", "function": {"name": "report"}}, max_tokens=200)
    calls = r.choices[0].message.tool_calls or []
    print("2. forced tool call:", "OK" if len(calls) == 1 else f"FAILED, got {len(calls)} tool calls",
          json.dumps([c.function.arguments for c in calls]))
except Exception as exc:
    print("2. forced tool call FAILED:", type(exc).__name__, str(exc)[:300])

# 3. cost: Mortar refuses to run without a cost
try:
    print("3. litellm cost ok:", litellm.completion_cost(completion_response=r))
except Exception as exc:
    print("3. litellm cannot price this model (expected for a custom endpoint):", str(exc)[:200])
