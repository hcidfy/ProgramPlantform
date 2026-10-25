"""输出护栏（FR-AI-04 服务端那一半）。

规则：key_snippet ≤5 行、outline ≤12 行、不返回完整可提交代码。
模型不听话时在这里兜住，而不是指望提示词。
"""

from __future__ import annotations

import re
from typing import Any

MAX_SNIPPET_LINES = 5
MAX_OUTLINE_LINES = 12
MAX_TEXT_CHARS = 800
MAX_SEGMENTS = 4
MAX_SEGMENT_CHARS = 400

CODE_FENCE = re.compile(r"```[a-zA-Z0-9+#-]*\n(.*?)```", re.DOTALL)


def _clamp_lines(text: str, limit: int) -> str:
    if not text:
        return ""
    lines = [ln.rstrip() for ln in str(text).replace("\r\n", "\n").split("\n")]
    if len(lines) > limit:
        lines = lines[:limit]
    return "\n".join(lines)


def _clamp_text(value: Any) -> str:
    text = "" if value is None else str(value)
    text = text.strip()
    if len(text) > MAX_TEXT_CHARS:
        text = text[:MAX_TEXT_CHARS] + "…"
    return text


def _truncate_code_fences(text: str, limit: int = MAX_SNIPPET_LINES) -> str:
    """把回答里超长的代码块压到 limit 行，防止"整段代码"从自由文本里漏出去。"""

    def repl(match: re.Match[str]) -> str:
        body = match.group(1).rstrip("\n")
        lines = body.split("\n")
        if len(lines) <= limit:
            return match.group(0)
        return "```\n" + "\n".join(lines[:limit]) + "\n…（已截断，不提供完整代码）\n```"

    return CODE_FENCE.sub(repl, text)


def sanitize(task: str, payload: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return {}
    clean = dict(payload)
    clean.pop("_meta", None)

    if task == "explain":
        location = clean.get("location") or {}
        line = location.get("line") if isinstance(location, dict) else None
        clean["location"] = {"line": line if isinstance(line, int) else None}
        for key in ("cause", "why", "next_step"):
            clean[key] = _truncate_code_fences(_clamp_text(clean.get(key)))
        clean["key_snippet"] = _clamp_lines(_clamp_text(clean.get("key_snippet")), MAX_SNIPPET_LINES)

    elif task == "hint":
        level = clean.get("level")
        clean["level"] = level if isinstance(level, int) and 1 <= level <= 3 else 1
        for key in ("concept", "direction"):
            clean[key] = _clamp_text(clean.get(key))
        clean["outline"] = _clamp_lines(_clamp_text(clean.get("outline")), MAX_OUTLINE_LINES)

    elif task == "analyze":
        clean["diagnosis"] = _truncate_code_fences(_clamp_text(clean.get("diagnosis")))
        clean["gap"] = _truncate_code_fences(_clamp_text(clean.get("gap")))
        clean["fix_direction"] = _truncate_code_fences(_clamp_text(clean.get("fix_direction")))
        case = clean.get("first_fail_case") or {}
        clean["first_fail_case"] = {
            "seq": case.get("seq") if isinstance(case.get("seq"), int) else 0,
            "expected": _clamp_text(case.get("expected")),
            "actual": _clamp_text(case.get("actual")),
        }
        checklist = clean.get("checklist") or []
        if not isinstance(checklist, list):
            checklist = [checklist]
        clean["checklist"] = [_clamp_text(item) for item in checklist if str(item).strip()][:5]

    elif task == "chat":
        segments = clean.get("segments") or []
        if not isinstance(segments, list):
            segments = [segments]
        normalized = []
        for item in segments[:MAX_SEGMENTS]:
            text = _truncate_code_fences(_clamp_text(item))
            if len(text) > MAX_SEGMENT_CHARS:
                text = text[:MAX_SEGMENT_CHARS] + "…"
            if text:
                normalized.append(text)
        clean["segments"] = normalized or ["这题我暂时答不上来，请把问题说得更具体一些。"]
        citations = clean.get("citations") or []
        if not isinstance(citations, list):
            citations = []
        clean["citations"] = [str(c) for c in citations if c][:5]
        clean["uncertain"] = bool(clean.get("uncertain", False))

    return clean
