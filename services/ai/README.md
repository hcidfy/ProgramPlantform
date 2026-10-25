# services/ai —— AI 编排服务（M3）

Python 3.12 + FastAPI + LangChain（LCEL）。它**只做一件事**：把 app 传进来的
「上下文 + 提示词模板」变成一条链，调模型，校验成结构化 JSON 返回。

配额、时间闸门、落库全在 `app` 侧（PRD §7.6 / §15）：ai 服务不碰数据库、不认识用户。

## 四条链（PRD §8.1）

| 链 | 入口 | 结构化输出 |
|-|-|-|
| `explain` 答疑 | `POST /internal/explain` | `location{line} cause why next_step key_snippet(≤5 行)` |
| `hint` 思路 | `POST /internal/hint` | `level concept direction outline(≤12 行)` |
| `analyze` 正确结果分析 | `POST /internal/analyze` | `diagnosis first_fail_case{seq,expected,actual} gap fix_direction checklist[]` |
| `chat` 知识问答 | `POST /internal/chat` | `segments[] citations[] uncertain` |

同一条链结构（LCEL）：`ChatPromptTemplate | model | 取内容(顺带取 token 用量) → 解析 JSON → 护栏`。
解析失败重试 1 次，仍失败**降级为纯文本**（`degraded=true` + `degradedText`）。

## 两种 provider

| `AI_PROVIDER` | 用途 | 说明 |
|-|-|-|
| `openai` | **生产** | 任意 OpenAI 兼容端点（默认阿里云 DashScope `qwen-plus`），需要 `DASHSCOPE_API_KEY` |
| `stub` | **仅开发** | 用确定性函数代替模型：字段齐全、`key_snippet` ≤5 行、引用 id 真实、隐藏用例不外泄。同一条 LCEL 链，只是把模型换掉 |

没有 API Key 时也能把「闸门 → 调用 → 校验 → 计量 → 渲染」整条链路跑通，这就是 M3 的验收方式。

## 环境变量

| 变量 | 默认 | 说明 |
|-|-|-|
| `AI_PROVIDER` | `stub` | `openai` / `stub` |
| `AI_MODEL` | `qwen-plus` | 模型名 |
| `LLM_BASE_URL` | DashScope 兼容端点 | 模型服务地址（与 app 的 `AI_BASE_URL` 区分开） |
| `DASHSCOPE_API_KEY` / `OPENAI_API_KEY` | 空 | 两种变量名都认 |
| `AI_TEMPERATURE` | `0.3` | PRD §8.1 |
| `AI_MAX_TOKENS` | `1000` | PRD §5.3：输出 ≤1000 |
| `AI_TIMEOUT_SECONDS` | `18` | 整体 ≤20s（PRD §8.4） |
| `AI_INTERNAL_TOKEN` | 空 | 与 app 共享；为空则不校验（**生产必须设**） |

## 本地跑（Windows 无 Docker 时）

```powershell
# 依赖装不进系统环境时，可把 wheel 解包到 .tools/pylibs，再用 PYTHONPATH 跑：
$env:PYTHONPATH="<repo>\.tools\pylibs"
$env:AI_PROVIDER="stub"; $env:AI_INTERNAL_TOKEN="dev-internal-token-change-me"
cd services\ai
python -m uvicorn app.main:app --host 127.0.0.1 --port 8081 --workers 1
```

健康检查：`GET http://127.0.0.1:8081/healthz` → `{"status":"UP","provider":"stub",...}`

## 安全约束

1. 只接受带 `X-Internal-Token` 的调用；compose 里**不映射宿主机端口**。
2. 学生代码 / 题面 / 知识条目 / 历史消息一律当**数据**处理（系统提示词里明确声明）。
3. 输出护栏在 `app/guard.py`：`key_snippet` ≤5 行、`outline` ≤12 行、自由文本里的超长代码块会被截断 ——
   即 FR-AI-04 的服务端那一半（前端不可复制是另一半）。
