"""stub provider 的确定性回答（仅开发用，PRD §15.1 D4）。

目的不是"像模型"，而是**保持契约一致**：字段齐全、key_snippet ≤5 行、
引用 id 真实、隐藏用例内容不外泄。这样没有 API Key 也能把链路跑通并做验收。
"""

from __future__ import annotations

import re
from typing import Any

LINE_PATTERN = re.compile(r"(?:Main\.java|main\.py)[:\s]+(\d+)")


def _first_error_line(compile_output: str | None) -> int | None:
    if not compile_output:
        return None
    match = LINE_PATTERN.search(compile_output)
    if match:
        try:
            return int(match.group(1))
        except ValueError:
            return None
    return None


def _snippet_around(source: str, line: int | None, limit: int = 3) -> str:
    lines = [ln for ln in (source or "").splitlines() if ln.strip()]
    if not lines:
        return ""
    if line is None or line < 1 or line > len(lines):
        picked = lines[-limit:]
    else:
        start = max(0, line - 1 - limit // 2)
        picked = lines[start:start + limit]
    return "\n".join(picked[:limit])


def _result_label(result: str | None) -> str:
    return {
        "accepted": "全部用例通过",
        "wrong_answer": "输出与期望不符",
        "compile_error": "编译不通过",
        "runtime_error": "运行时报错退出",
        "timeout": "超时",
        None: "未知状态",
    }.get(result, result or "未知状态")


def explain(request: Any) -> dict[str, Any]:
    sub = request.submission
    problem = request.problem
    line = _first_error_line(sub.compile_output)
    case = sub.first_fail_case or {}
    seq = case.get("seq") or 1
    result = sub.result

    if result == "compile_error":
        cause = "编译阶段就失败了，代码还没有机会运行"
        why = (sub.compile_output or "").strip().splitlines()[0] if (sub.compile_output or "").strip() else "编译输出为空"
        next_step = "先按编译器提示的行号改语法/类型错误，再重新提交"
    elif result == "wrong_answer":
        cause = f"第 {seq} 组用例的输出和期望不一致"
        why = f"期望输出 `{case.get('expected', '')}`，实际输出 `{case.get('actual', '')}`"
        next_step = "对照该组用例的输入，先手算一遍正确结果，再检查你的计算/输出格式"
    elif result == "timeout":
        cause = "程序在时限内没有跑完"
        why = "可能是死循环，或算法复杂度太高"
        next_step = "先检查循环终止条件；再评估数据规模，必要时换更优算法"
    elif result == "runtime_error":
        cause = "程序非正常退出（退出码非 0）"
        why = "常见原因是数组越界、空指针、除零、读取输入格式不符"
        next_step = "用题目给的输入在本地跑一遍，看异常栈的第一行"
    else:
        cause = "这道题已经通过"
        why = "判题结果显示全部用例通过"
        next_step = "可以继续看复杂度与边界情况（例如空输入、极小/极大值）"

    return {
        "location": {"line": line},
        "cause": cause,
        "why": why,
        "next_step": next_step,
        "key_snippet": _snippet_around(sub.source_code, line),
        "_meta": {"problem": problem.title, "language": problem.language},
    }


def hint(request: Any) -> dict[str, Any]:
    level = max(1, min(int(request.level or 1), 3))
    problem = request.problem
    if level == 1:
        body = {
            "concept": f"先把「{problem.title}」拆成读入 → 计算 → 输出三段，确认读入格式与题目完全一致。",
            "direction": "只关注概念：你的输入解析方式对不对？变量类型够不够大？",
            "outline": "1. 读入\n2. 计算\n3. 输出",
        }
    elif level == 2:
        body = {
            "concept": "这一级的重点是选对算法方向",
            "direction": "先想清楚最坏情况下要做多少次运算；能用一次遍历解决就不要嵌套循环。",
            "outline": "1. 读入数据到合适结构\n2. 单次遍历累加/统计\n3. 按题目格式输出",
        }
    else:
        body = {
            "concept": "最后一级给你伪代码骨架，自己补成可运行代码",
            "direction": "按骨架填空，不要照抄任何现成程序",
            "outline": "read a, b\nresult = a + b   # 换成题目真正要求的计算\nprint(result)",
        }
    body["level"] = level
    body["_meta"] = {"problem": problem.title, "level": level}
    return body


def analyze(request: Any) -> dict[str, Any]:
    sub = request.submission
    result = sub.result
    case = sub.first_fail_case or {}
    seq = case.get("seq") or 0
    expected = case.get("expected", "")
    actual = case.get("actual", "")
    if result == "accepted":
        diagnosis = "全部用例通过，没有失败用例"
        gap = "无差距；可以关注复杂度与边界输入"
        fix = "保持现状，建议再自测空输入与极值"
        case = {"seq": 0, "expected": "", "actual": ""}
    elif result == "compile_error":
        diagnosis = "编译失败，属于语法/类型问题，不是算法问题"
        gap = "代码目前无法运行"
        fix = "逐条修编译错误；先让程序能编译通过再谈算法"
    else:
        diagnosis = f"程序能运行，但第 {seq} 组用例输出不对"
        gap = f"期望 `{expected}`，实际 `{actual}`" if expected or actual else "输出与期望不符"
        fix = "用该组输入手算正确结果，逐步打印中间变量定位偏差"
    return {
        "diagnosis": diagnosis,
        "first_fail_case": {"seq": seq, "expected": expected, "actual": actual},
        "gap": gap,
        "fix_direction": fix,
        "checklist": [
            "用首个失败用例的输入手动跑一遍你的逻辑",
            "检查输出格式（多余空格/换行/大小写）",
            "检查边界情况（空输入、单元素、最大值）",
        ],
        "_meta": {"result": result},
    }


def chat(request: Any) -> dict[str, Any]:
    entries = request.entries
    question = request.question
    if not entries:
        return {
            "segments": [
                f"课程资料里没有覆盖「{question}」这个问题。",
                "以下是通用解释：这类问题通常要先弄清语法规则与运行时的实际行为，建议再对照教材对应章节。",
            ],
            "citations": [],
            "uncertain": True,
            "_meta": {"entries": 0},
        }
    segments = []
    for entry in entries[:2]:
        snippet = (entry.content_md or "").replace("\n", " ").strip()
        if len(snippet) > 120:
            snippet = snippet[:120] + "…"
        segments.append(f"{entry.title}：{snippet}")
    segments.append("以上依据课程知识条目作答；条目没覆盖的部分请以教材为准。")
    return {
        "segments": segments,
        "citations": [e.id for e in entries[:2]],
        "uncertain": False,
        "_meta": {"entries": len(entries)},
    }


GENERATORS = {
    "explain": explain,
    "hint": hint,
    "analyze": analyze,
    "chat": chat,
}
