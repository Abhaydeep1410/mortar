"""JSONL event log: every decision and every file written by a run.

One JSON object per line. `seq` is gapless from 1, so a missing line is detectable. The blueprint
hash is stamped on every event (docs/blueprint-spec.md section 6).
"""

from __future__ import annotations

import datetime as dt
import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

Clock = Callable[[], dt.datetime]


def utc_now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


class EventLog:
    def __init__(
        self,
        run_id: str,
        path: Path | None = None,
        blueprint_sha256: str | None = None,
        clock: Clock = utc_now,
    ) -> None:
        self.run_id = run_id
        self.blueprint_sha256 = blueprint_sha256
        self.events: list[dict[str, Any]] = []
        self._path = path
        self._clock = clock
        if path is not None:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("", encoding="utf-8")

    def emit(self, kind: str, **fields: Any) -> dict[str, Any]:
        reserved = {"seq", "ts", "run_id", "blueprint_sha256", "kind"} & fields.keys()
        if reserved:
            raise ValueError(f"event fields use reserved names: {sorted(reserved)}")
        event: dict[str, Any] = {
            "seq": len(self.events) + 1,
            "ts": self._clock().astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ"),
            "run_id": self.run_id,
            "blueprint_sha256": self.blueprint_sha256,
            "kind": kind,
            **fields,
        }
        line = json.dumps(event, ensure_ascii=False, allow_nan=False)  # raises on non-JSON values
        if self._path is not None:
            with self._path.open("a", encoding="utf-8") as handle:
                handle.write(line + "\n")
        self.events.append(event)
        return event
