# services/ai

AI 编排服务（Python 3.12 + FastAPI + LangChain LCEL），**M3 开始实现**，本目录暂时为空占位。

M3 计划内容（见 PRD §8）：

- 4 条链：`explain`（答疑）/ `hint`（三级思路）/ `analyze`（正确结果分析）/ `chat`（课程知识问答）
- 时间闸门：学生对该题首次提交前，所有 AI 接口由 `app` 直接 403，不经过本服务
- 无向量库：知识问答用 PG `pg_trgm` 关键词/近似匹配取 Top 5 条目拼 prompt
- 计量：每次调用写 `ai_usage(prompt_tokens, completion_tokens)`，配额由 `app` 校验

部署方式：与 `app` 同机，`deploy/docker-compose.prod.yml` 增加一个 `ai` 服务（内存预算 300 MB，见 PRD §7.3），
`app` 通过内部 HTTP 调用 `ai`。
