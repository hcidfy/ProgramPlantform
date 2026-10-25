"""请求/响应模型（PRD §8.1 的结构化输出）。

对外统一用 camelCase（跟 Java 侧 record 字段一致），内部用 snake_case。
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class Wire(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")


class ProblemCtx(Wire):
    title: str = ""
    statement: str = ""
    language: str = "java"
    time_limit_ms: int | None = None
    mem_limit_kb: int | None = None


class SubmissionCtx(Wire):
    id: str = ""
    status: str = ""
    result: str | None = None
    passed_cases: int = 0
    total_cases: int = 0
    source_code: str = ""
    compile_output: str | None = None
    first_fail_case: dict[str, Any] | None = None
    case_summaries: list[dict[str, Any]] = Field(default_factory=list)


class BaseRequest(Wire):
    prompt_template: str = ""
    prompt_version: int = 1
    problem: ProblemCtx
    submission: SubmissionCtx


class ExplainRequest(BaseRequest):
    question: str | None = None


class HintRequest(BaseRequest):
    level: int = 1


class AnalyzeRequest(BaseRequest):
    pass


class ChatEntry(Wire):
    id: str
    title: str = ""
    content_md: str = ""
    tags: list[str] = Field(default_factory=list)


class ChatMessage(Wire):
    role: Literal["user", "assistant"]
    text: str = ""


class ChatRequest(Wire):
    prompt_template: str = ""
    prompt_version: int = 1
    question: str
    entries: list[ChatEntry] = Field(default_factory=list)
    history: list[ChatMessage] = Field(default_factory=list)


class ExplainResult(Wire):
    location: dict[str, Any] = Field(default_factory=lambda: {"line": None})
    cause: str = ""
    why: str = ""
    next_step: str = ""
    key_snippet: str = ""


class HintResult(Wire):
    level: int = 1
    concept: str = ""
    direction: str = ""
    outline: str = ""


class AnalyzeResult(Wire):
    diagnosis: str = ""
    first_fail_case: dict[str, Any] = Field(default_factory=lambda: {"seq": 0, "expected": "", "actual": ""})
    gap: str = ""
    fix_direction: str = ""
    checklist: list[str] = Field(default_factory=list)


class ChatResult(Wire):
    segments: list[str] = Field(default_factory=list)
    citations: list[str] = Field(default_factory=list)
    uncertain: bool = False


class Usage(Wire):
    prompt_tokens: int = 0
    completion_tokens: int = 0


class TaskResponse(Wire):
    task: str
    prompt_version: int = 1
    payload: dict[str, Any] = Field(default_factory=dict)
    usage: Usage = Field(default_factory=Usage)
    degraded: bool = False
    degraded_text: str | None = None
