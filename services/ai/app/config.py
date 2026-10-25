"""运行配置：全部来自环境变量（PRD §15.1 D4/D6）。"""

from __future__ import annotations

import os
from dataclasses import dataclass


def _env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


@dataclass(frozen=True)
class Settings:
    provider: str          # stub | openai
    model: str
    base_url: str
    api_key: str
    temperature: float
    max_tokens: int
    timeout_seconds: float
    internal_token: str
    log_level: str

    @staticmethod
    def load() -> "Settings":
        provider = _env("AI_PROVIDER", "stub").lower()
        if provider not in ("stub", "openai"):
            provider = "stub"
        # 兼容 DashScope 与任意 OpenAI 兼容端点
        # 注意：LLM_BASE_URL 是"模型服务地址"；app 侧的 AI_BASE_URL 指的是本服务，不要混用
        base_url = (
            _env("LLM_BASE_URL")
            or _env("DASHSCOPE_BASE_URL")
            or _env("OPENAI_BASE_URL")
            or "https://dashscope.aliyuncs.com/compatible-mode/v1"
        )
        api_key = _env("DASHSCOPE_API_KEY") or _env("OPENAI_API_KEY")
        return Settings(
            provider=provider,
            model=_env("AI_MODEL", "qwen-plus"),
            base_url=base_url,
            api_key=api_key,
            temperature=float(_env("AI_TEMPERATURE", "0.3")),
            max_tokens=int(_env("AI_MAX_TOKENS", "1000")),
            timeout_seconds=float(_env("AI_TIMEOUT_SECONDS", "18")),
            internal_token=_env("AI_INTERNAL_TOKEN", ""),
            log_level=_env("AI_LOG_LEVEL", "INFO").upper(),
        )


SETTINGS = Settings.load()
