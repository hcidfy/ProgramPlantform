"""AI 编排服务（FastAPI + LangChain LCEL）。

只做一件事：把 app 传进来的上下文 + 提示词模板变成一条链，调用模型，
校验成结构化 JSON 返回。配额、时间闸门、落库都在 app 侧（PRD §7.6 / §15）。
"""

__version__ = "0.1.0"
