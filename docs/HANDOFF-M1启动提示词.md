直接开始 M1，不用等我确认任务卡。

# 任务：接管「在线代码学习与知识问答平台」，执行 M1

你是本项目的**唯一执行方（Agent）**，负责实现开发；人类是总体设计者与验收人。不要重新做架构设计，只按既有 PRD 执行。

## 0. 一句话背景

面向 Java / Python 程序设计课程的在线代码学习与知识问答平台：教师按教学班布置作业 → 学生在线写码提交 → 本机判题 → **首次提交后**开放 AI 答疑 / 思路提示 / 正确结果分析 → 另有课程内 AI 知识问答。目标部署为**单台阿里云 2 核 4 GB**。

## 1. 当前状态

- 代码：**尚无**。工作区只有 `docs/` 两份文档，未 `git init`。
- 文档：PRD v0.1（完备形态，已作废其技术章节）+ **v0.2（唯一交付依据）**。
- 已完成：需求与架构收敛、范围裁剪、2C4G 内存账本、数据模型草案。
- 未开始：任何代码、数据库、部署。

## 2. 必读文件（按顺序，不要通读整个仓库）

1. `docs/PRD-在线编程学习与AI助教平台-v0.2.md` —— **全文必读**（约 530 行），这是唯一交付依据：范围、24 条 FR、两个服务、技术栈、内存账本、数据模型、权限、里程碑。
2. `docs/PRD-在线编程学习与AI助教平台-v0.1.md` —— **只读第 1~3 章（背景/问题）与 §17.4**。它的技术章节（判题沙箱、pgvector 向量检索、10 个微服务、Spring Cloud、CI/CD、可观测）**已全部作废**，照它实现一定走错。
3. `C:\Users\HCQ\Documents\DSH\memory\progress\ProgramPlantform.md` —— 若存在，读它拿上次进度。

读完先输出一段「我的理解 + M1 任务卡清单」，再动手。

## 3. 项目事实（已定，不要重新讨论）

| 项 | 值 |
|-|-|
| 代码位置 | `D:\basic_use\ProgramPlantform`（Windows 开发机 → Linux 服务器部署） |
| 部署目标 | 阿里云 ECS 2 核 4 GB，Docker Compose 单机 |
| 后端 app | Java 21 + Spring Boot 4.0.x，依赖只要 `web / jdbc / validation / security / mail`；**不引入 Spring Cloud、注册中心、网关** |
| 后端 ai | Python 3.12 + FastAPI + LangChain(LCEL)，4 条链：explain / hint / analyze / chat |
| 前端 | Vue 3 + Vite + TypeScript + Monaco Editor，构建后 Nginx 托管 |
| 数据库 | PostgreSQL（`citext`、`pg_trgm`），SQL 脚本按序迁移 |
| 缓存 | Redis，**不持久化**（只存会话、日 token 计数、排队位置） |
| 消息 | RocketMQ 单机（namesrv+broker），只两个 topic：`judge.task`、`notify` |
| 文件 | MinIO（题目图片/附件、每日备份） |
| 进程上限 | `nginx` / `app` / `ai` + 上述中间件；**新增服务或中间件必须先经人类批准** |
| 判题 | 本机受限子进程（低权限 `judge` 用户 + `ulimit` + 超时 + 输出截断），**无容器沙箱** |
| 权限 | 三角色（student/teacher/admin）+ AI token 配额；教师只看自己教学班，学生只看本班 |
| AI 闸门 | 学生对该题**首次提交前所有 AI 接口 403**；提交后开放；AI 输出**不可复制**、不给完整可提交代码 |
| 规模 | ≤150 注册 / ≤500 提交/日 / ≤10 并发 |

## 4. 硬约束（违反即回退任务）

1. **不扩范围**：只做 M1 列出的内容；发现需要新需求 → 停下写提案，不自行加。
2. **不加组件**：不引入 ES、向量库、容器沙箱、网关、注册中心、Redis 持久化、单元测试框架、CI 流水线。允许用一次性脚本自验，但不作为交付物。
3. **先文档后代码**：动接口、表结构、权限规则前，先改 PRD v0.2 并记版本；代码与 PRD 冲突时以 PRD 为准。
4. **不编造**：业务规则/阈值/文案不确定 → 列进「待决策」，不自己拍。
5. **不泄密**：API Key、数据库密码走 `.env`（`.env` 进 `.gitignore`，只提交 `.env.example`）。
6. **不加依赖**：后端每加一个 starter / 前端每加一个包，都要说明为什么不能手写。

## 5. 待确认 → 默认值（先按默认做，不要因此阻塞）

| # | 问题 | 默认 |
|-|-|-|
| Q1 | AI 是否给完整参考代码 | **不给**，只给方向 + ≤5 行片段 |
| Q2 | LLM 供应商 | 阿里云百炼 DashScope 通义千问（OpenAI 兼容端点） |
| Q3 | 语言版本 | Java 21 / Python 3.12 |
| Q4 | 学生能否互看提交 | 不能 |
| Q5 | 域名 | 先用 `http://IP:8080`，不备案 |
| Q6 | 邮件 | 不发邮件，管理员生成初始密码 |

## 6. 本轮任务：M1（约一周量，按序做，可分批交付）

| 编号 | 任务 | 关键产出 |
|-|-|-|
| M1-1 | 仓库骨架 | `git init`、`.gitignore`、`README.md`、目录：`deploy/ services/app services/ai web/ docs/ scripts/` |
| M1-2 | 中间件编排 | `deploy/docker-compose.dev.yml` + `prod.yml`：postgres / redis / rocketmq / minio，按 PRD §7.3 设 `mem_limit`；`.env.example` |
| M1-3 | 数据库迁移 | `deploy/db/V1__init.sql`：users、auth_sessions、school_classes、courses、teaching_classes、enrollments、audit_logs；**可重复执行** |
| M1-4 | 账号 | 注册（MVP 直接 `active`，邮件验证后置）、登录、JWT（access 2h / refresh 7d）、登出 |
| M1-5 | 组织模型 | 班级 / 课程 / 教学班 / 入班 的增删查；教师只能看自己教学班，学生只能看本班教学班 |
| M1-6 | 前端最小页 | 登录页、学生「我的课程」、教师「我的教学班」（可空状态，不追求样式） |
| M1-7 | 部署 | `scripts/deploy.sh`（构建+重启）、`docs/ops/部署手册.md`（含启动顺序与常见错误） |

**M1 验收（必须能当面对我演示）**

1. 建「2028级应用数学2班」×「Java程序设计」× 张老师 = 一条教学班记录。
2. 新建/导入 5 个学生入班。
3. 学生登录 → 「我的课程」显示 Java 程序设计（张老师）。
4. 教师登录 → 只看到自己的教学班，看不到别人的。
5. 学生把 URL 里的 id 换成别的教学班 → 403 或 404（不泄露存在性）。
6. 干净机器上 `docker compose up -d` + `./scripts/deploy.sh` → 3 分钟内可登录。

## 7. 工作方式

- **第一轮**：读文档 → 输出 M1 任务卡清单（每张卡：改哪些文件 / 接口契约 / 验收方式 / 风险）→ 等我确认；我若说「直接开始」就跳过确认。
- **每批结束**：① 变更文件清单 ② 3 条以内的验证命令 ③ 未解决 / 需我决策的点 ④ 下一轮建议。
- **每轮收尾**：更新 `docs/` 相关章节与版本记录；用 memory-progress 更新 `progress/ProgramPlantform.md`。
- 中文回复，结论先行，代码细节放文件里，别在对话里贴大段代码。

## 8. 踩坑提示（能省你半小时）

- **旧方案陷阱**：看到「沙箱」「pgvector」「Spring Cloud」「微服务拆分」「CI」→ 那都是 v0.1 已删内容，不要实现。
- `ulimit -v` **不能**用于 Java（JVM 预留虚拟内存直接起不来），Java 用 `-Xmx`；只有 Python 才加 `-v`。
- RocketMQ 在 4 GB 机器上是最吃内存的组件：起不来先降 `-Xmx256m`；仍不行走 PRD §7.6 的 PG 队列降级开关（**需我批准**）。
- 判题必须是低权限用户 `judge` 跑，工作目录 `/data/judge/{submission_id}/`，24 h 清理。
- 记忆库在工作区**之外**（`C:\Users\HCQ\Documents\DSH\memory`），写进度需一次沙箱提权。
- 本工作区当前**没有 git 仓库**，`Test-Path .git` 为 False 属正常。

## 9. 交付格式（每轮结尾固定四段）

```
① 变更文件：…
② 怎么验：…（≤3 条命令）
③ 要你定：…（无则写"无"）
④ 下一步：…
```

现在开始：先读 §2 的三个文件，然后给我 M1 任务卡清单。
