"""ai 服务入口：4 条链的内部 HTTP 接口（PRD §15.3 / §15.4）。

安全：只接受带 X-Internal-Token 的调用；compose 里不给 ai 映射宿主机端口。
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse

from app import chains
from app.config import SETTINGS
from app.schemas import (
    AnalyzeRequest,
    ChatRequest,
    ExplainRequest,
    HintRequest,
    TaskResponse,
    Usage,
)

logging.basicConfig(
    level=SETTINGS.log_level,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)
log = logging.getLogger("codelab-ai")

app = FastAPI(title="codelab-ai", version="0.1.0")

# 超时上限：整体 ≤20 s（PRD §8.4），这里留 2 s 余量
OVERALL_TIMEOUT = SETTINGS.timeout_seconds + 2


def _check_token(token: str | None) -> None:
    if SETTINGS.internal_token and token != SETTINGS.internal_token:
        raise HTTPException(status_code=401, detail="invalid internal token")


async def _execute(task: str, request: Any) -> TaskResponse:
    started = time.monotonic()
    try:
        payload, usage, degraded, degraded_text = await asyncio.wait_for(
            asyncio.to_thread(chains.run_task, task, request),
            timeout=OVERALL_TIMEOUT,
        )
    except asyncio.TimeoutError:
        log.warning("task=%s 超时 %.1fs", task, time.monotonic() - started)
        raise HTTPException(status_code=504, detail="AI 超时，先看判题结果")
    except RuntimeError as exc:
        log.error("task=%s 配置错误: %s", task, exc)
        raise HTTPException(status_code=500, detail=str(exc))
    except Exception as exc:  # noqa: BLE001 - 上游模型异常统一转 502
        log.exception("task=%s 失败", task)
        raise HTTPException(status_code=502, detail=f"AI 调用失败: {exc}")

    elapsed = time.monotonic() - started
    log.info(
        "task=%s provider=%s elapsed=%.2fs degraded=%s prompt_tokens=%s completion_tokens=%s",
        task,
        SETTINGS.provider,
        elapsed,
        degraded,
        usage.get("prompt_tokens"),
        usage.get("completion_tokens"),
    )
    return TaskResponse(
        task=task,
        promptVersion=getattr(request, "prompt_version", 1),
        payload=payload,
        usage=Usage(promptTokens=usage.get("prompt_tokens", 0), completionTokens=usage.get("completion_tokens", 0)),
        degraded=degraded,
        degradedText=degraded_text,
    )


@app.get("/healthz")
def healthz() -> dict[str, Any]:
    return {
        "status": "UP",
        "service": "codelab-ai",
        "provider": SETTINGS.provider,
        "model": SETTINGS.model if SETTINGS.provider == "openai" else "stub",
    }


@app.post("/internal/explain", response_model=TaskResponse, response_model_by_alias=True)
async def explain(request: ExplainRequest, x_internal_token: str | None = Header(default=None)) -> TaskResponse:
    _check_token(x_internal_token)
    return await _execute("explain", request)


@app.post("/internal/hint", response_model=TaskResponse, response_model_by_alias=True)
async def hint(request: HintRequest, x_internal_token: str | None = Header(default=None)) -> TaskResponse:
    _check_token(x_internal_token)
    return await _execute("hint", request)


@app.post("/internal/analyze", response_model=TaskResponse, response_model_by_alias=True)
async def analyze(request: AnalyzeRequest, x_internal_token: str | None = Header(default=None)) -> TaskResponse:
    _check_token(x_internal_token)
    return await _execute("analyze", request)


@app.post("/internal/chat", response_model=TaskResponse, response_model_by_alias=True)
async def chat(request: ChatRequest, x_internal_token: str | None = Header(default=None)) -> TaskResponse:
    _check_token(x_internal_token)
    return await _execute("chat", request)


@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException) -> JSONResponse:
    return JSONResponse(status_code=exc.status_code, content={"code": f"AI_{exc.status_code}", "message": exc.detail})
