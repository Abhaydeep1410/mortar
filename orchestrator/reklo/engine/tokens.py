"""The one tokenizer used for token-equivalent reuse (`reuse_rate_tokens`).

`tiktoken` with `cl100k_base` when installed, otherwise chars/4. The name is recorded in every run so
a report can state which counter produced its numbers; benchmark runs must use the tiktoken counter
(install the `tokens` extra) and the pilot checks that they do.
"""

from __future__ import annotations

import math

TIKTOKEN_NAME = "tiktoken:cl100k_base"
APPROX_NAME = "approx:chars/4"


def count_tokens(text: str) -> tuple[int, str]:
    try:
        import tiktoken  # type: ignore

        return len(tiktoken.get_encoding("cl100k_base").encode(text)), TIKTOKEN_NAME
    except Exception:  # not installed, or the encoding file cannot be fetched offline
        return math.ceil(len(text) / 4), APPROX_NAME
