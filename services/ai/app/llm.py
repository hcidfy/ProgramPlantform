"""模型工厂：openai（DashScope 兼容端点）/ stub（仅开发，PRD §15.1 D4）。"""

from __future__ import annotations

import contextvars
import json
from typing import Any

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage
from langchain_core.outputs import ChatGeneration, ChatResult

from app import stub
from app.config import SETTINGS

CURRENT_TASK: contextvars.ContextVar[str | None] = contextvars.ContextVar("ai_task", default=None)
CURRENT_REQUEST: contextvars.ContextVar[Any] = contextvars.ContextVar("ai_request", default=None)


class StubChatModel(BaseChatModel):
    """确定性的假模型：同一条链、同样的输出契约，只是把"模型"换成函数。"""

    @property
    def _llm_type(self) -> str:
        return "stub"

    def _generate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: Any = None,
        **kwargs: Any,
    ) -> ChatResult:
        task = CURRENT_TASK.get() or "explain"
        request = CURRENT_REQUEST.get()
        generator = stub.GENERATORS.get(task)
        payload = generator(request) if (generator and request is not None) else {"error": "缺少上下文"}
        text = json.dumps(payload, ensure_ascii=False)
        prompt_chars = sum(len(str(getattr(m, "content", ""))) for m in messages)
        prompt_tokens = max(1, prompt_chars // 4)
        completion_tokens = max(1, len(text) // 4)
        message = AIMessage(
            content=text,
            usage_metadata={
                "input_tokens": prompt_tokens,
                "output_tokens": completion_tokens,
                "total_tokens": prompt_tokens + completion_tokens,
            },
        )
        return ChatResult(generations=[ChatGeneration(message=message)])


def build_model() -> BaseChatModel:
    if SETTINGS.provider != "openai":
        return StubChatModel()

    if not SETTINGS.api_key:
        raise RuntimeError("AI_PROVIDER=openai 但未配置 DASHSCOPE_API_KEY / OPENAI_API_KEY")

    from langchain_openai import ChatOpenAI

    # langchain-openai 的参数名在不同小版本上会变，按模型实际字段过滤，避免 TypeError
    candidate = {
        "model": SETTINGS.model,
        "base_url": SETTINGS.base_url,
        "api_key": SETTINGS.api_key,
        "temperature": SETTINGS.temperature,
        "max_tokens": SETTINGS.max_tokens,
        "timeout": SETTINGS.timeout_seconds,
        "max_retries": 0,
    }
    fields = set(getattr(ChatOpenAI, "model_fields", {}).keys())
    kwargs = {key: value for key, value in candidate.items() if key in fields}
    return ChatOpenAI(**kwargs)
