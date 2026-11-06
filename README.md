# 在线代码学习与知识问答平台

面向 Java / Python 程序设计课程的**在线代码学习 + AI 助教**平台：教师按教学班布置作业 → 学生在线写码提交 → 本机判题 → **首次提交后**开放 AI 答疑 / 思路提示 / 正确结果分析 → 另有课程内 AI 知识问答。

- 部署目标：单台阿里云 ECS **2 核 4 GB**（Docker Compose 单机）
- 唯一交付依据：`docs/PRD-在线编程学习与AI助教平台-v0.2.md`（历史技术方案不作为当前实现依据）
- 任务书实施进度与差距说明：`docs/任务书实施情况报告.md`
- 运维手册：`docs/ops/部署手册.md`

## 当前进度

| 里程碑 | 状态 |
|-|-|
| **M1** 仓库骨架 / 中间件编排 / 数据库迁移 / 账号 / 组织模型 / 前端最小页 / 部署脚本 | ✅ 已交付（验收见 `scripts/m1-acceptance.sh`） |
| **M2** 题库 + 用例 + 图片 + 作业 + Monaco 做题页 + 提交判题 + 结果页 | ✅ 已交付（教师布置 2 题 → 学生一过一错 → 结果页信息完整，51 项自动断言全绿） |
| **M3** `ai` 服务 4 条链 + 时间闸门 + 配额 + 不可复制 + 知识条目与问答 | ✅ 已交付（提交前 AI 全 403；提交后答疑/三级思路/分析可用；同级不重复计费；超配额 429；知识问答带引用与不确定态；48 项自动断言全绿） |
| **M4** 管理端组织表单 / 配额用量 / 审计 / AI 回答复核 / 备份恢复 / 上线保障 | 🔄 本地开发与人工走查中；M4-7 流程已设计、代码待实现；用户确认本地验收通过后才部署云端 |
| **V1** 错题本、知识条目采纳、学习统计 | 后置；按 M4 后的真实使用问题立项 |

阶段依据与任务书材料清单见 `docs/PRD-在线编程学习与AI助教平台-v0.2.md` §11；当前执行交接见根目录 `handoff.md`。

## 目录

```
docs/                      任务书、当前 PRD、实现报告、操作与部署手册
deploy/
  docker-compose.dev.yml   仅中间件（本机开发）
  docker-compose.prod.yml  完整栈（服务器）
  db/                      迁移脚本（V1 身份/组织、V2 题库/作业/判题/文件；按文件名升序执行）
  nginx/                   前端静态站 + /api 反代
  rocketmq/                broker 配置
services/
  app/                     Java 21 + Spring Boot 4 单体（M1：账号 + 组织模型）
  ai/                      Python 3.12 + FastAPI + LangChain LCEL（4 条链；本地可用 stub provider）
web/                       Vue 3 + Vite + TS，构建后由 Nginx 托管
scripts/                   deploy.sh（一键部署）、m1-acceptance.sh（验收自验）
```

## 快速开始（本机开发）

```bash
cp .env.example .env        # 必改 POSTGRES_PASSWORD / MINIO_ROOT_PASSWORD / APP_JWT_SECRET
```

### 1. 起中间件

```bash
docker compose -f deploy/docker-compose.dev.yml --env-file .env up -d postgres redis
```

### 2. 起后端（默认 8080）

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/codelab
export SPRING_DATASOURCE_USERNAME=codelab
export SPRING_DATASOURCE_PASSWORD='<.env 里的 POSTGRES_PASSWORD>'
cd services/app && ./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

启动时自动执行 `deploy/db/*.sql`（未执行的版本），并按 `.env` 的 `APP_ADMIN_EMAIL` / `APP_ADMIN_PASSWORD` 幂等创建首个管理员。
健康检查：`curl http://127.0.0.1:8080/healthz`。

### 3. 起前端（默认 5173，`/api` 已代理到 8080）

```bash
cd web && pnpm install && pnpm dev
```

### 4. 服务器部署

```bash
cp .env.example .env && vi .env     # 密钥全部换掉
./scripts/deploy.sh                 # build + up + 等健康检查
```

详细步骤、常见错误、备份恢复见 `docs/ops/部署手册.md`。

## 接口清单

| 分类 | 接口 |
|-|-|
| 认证 | `POST /api/auth/register` `/login` `/refresh` `/logout`、`GET /api/auth/me` |
| 管理端 | `/api/admin/users`（含 CSV 导入）、`/school-classes`、`/courses`、`/teaching-classes`、`/enrollments` |
| 教师端（组织） | `GET /api/teacher/teaching-classes`、`GET /api/teacher/teaching-classes/{id}` |
| 学生端（组织） | `GET /api/student/courses`、`GET /api/student/courses/{id}` |
| 教师端（题库，M2） | `/api/teacher/problems`（增删改查/发布/另存新版本/用例整体替换）、`POST /api/teacher/files` |
| 教师端（作业，M2） | `/api/teacher/assignments`（建/改/发布/进度/学生提交）、`POST /api/teacher/submissions/{id}/rejudge` |
| 学生端（做题，M2） | `/api/student/assignments`、`/api/student/problems/{id}`、`POST /api/student/submissions`、`/api/student/submissions/{id}` |
| 文件 | `GET /api/files/{id}`（题目图片，需登录且有权看该题） |
| AI（S1，M3） | `GET /api/student/problems/{id}/ai/status`、`POST .../ai/explain` `/ai/hint` `/ai/analyze` |
| AI（S2 问答，M3） | `POST /api/student/courses/{courseId}/ai/ask`、`GET .../ai/conversations`、`GET /api/student/ai/conversations/{id}` |
| 知识条目（教师） | `/api/teacher/knowledge`（增改/发布/删除）、`GET /api/teacher/knowledge-qa` |
| ai 服务内部 | `/internal/explain` `/internal/hint` `/internal/analyze` `/internal/chat`（带 `X-Internal-Token`，不对外） |
| 健康 | `GET /healthz` |

契约细则（状态码、JWT、refresh cookie、失败锁定、分页）见 PRD **§13**。

## 关键取舍（M2 落地时确定，见 PRD §14.1）

| # | 决策 | 原因 |
|-|-|-|
| D1 | 判题异步先走 PG 队列表 `judge_queue`，RocketMQ 版后置（保留 `JudgeTaskQueue` 接口） | 即 PRD §7.6 预留的降级开关；交付无法验证的 MQ 代码风险更大 |
| D2 | 文件存储抽象 `FileStorage`：`minio`（生产，手写 S3 SigV4）/ `local`（仅开发） | 本机无 Docker/MinIO，用 local 把上传/回显链路跑通 |
| D3 | 判题在 app 容器内执行；运行镜像 JDK 21 + python3；生产用 `runuser -u judge` 降权 | PRD §7.4 要求 javac/python3 与低权限用户 |

操作流程见 **`docs/操作手册.md`**（管理员怎么建班建课导名单、教师怎么建题布置作业看进度、学生怎么做题看结果）。

## 硬约束（来自 PRD v0.2，违反即回退）

1. 只有两个后端服务 `app` + `ai`，**不引入** Spring Cloud / 注册中心 / 网关 / ES / 向量库 / 容器沙箱 / CI。
2. **不扩范围**：只做里程碑列出的内容；发现新需求先写提案。
3. **先文档后代码**：动接口、表结构、权限规则前先改 PRD 并记版本（M1 的契约记为 v0.2.1）。
4. **不编造**：业务规则/阈值/文案不确定 → 进「待决策」。
5. **不泄密**：密钥走 `.env`（只提交 `.env.example`）。
6. **不加依赖**：每加一个 starter / npm 包都要说明为什么不能手写。

### 依赖与理由（对应约束 6）

| 依赖 | 为什么必须有 | 为什么不自己写 |
|-|-|-|
| `spring-boot-starter-web/jdbc/validation/security/mail` | PRD §7.2 明确白名单 | — |
| `postgresql` 驱动 | 连 PG 的唯一办法 | — |
| `bcprov-jdk18on` | `users.password_hash` 要 Argon2id，JDK 无 Argon2 实现 | 自己实现密码学哈希是明确的安全反模式 |
| 前端 `vue` / `vue-router` | PRD 指定 Vue 3 单页 | — |
| 前端 `vite` / `@vitejs/plugin-vue` / `typescript` | 构建期工具，PRD 指定 Vite + TS | — |
| 前端 `monaco-editor` | PRD §7.2 指定 Monaco 作为在线编辑器 | 做不到"手写一个带 Java/Python 高亮的编辑器" |
| 前端 `marked` | 题面是 Markdown（FR-PRB-01） | Markdown 解析规范庞杂，自写会踩一堆边界 |
| Python `fastapi` / `uvicorn` / `langchain` / `langchain-openai` | PRD §7.2 指定 FastAPI + LangChain LCEL | — |
| **没有** 向量库 / embedding 模型 | — | 知识量 <500 条，`pg_trgm` + 标签足够（PRD §8.3 已写明取舍） |
| **没有** JWT 库 | — | 只需 HS256 签发/校验，手写 100 行（`JwtService`） |
| **没有** Flyway | — | 迁移器只需"按文件名执行 + 记录版本"，手写 60 行（`MigrationRunner`） |
| **没有** actuator | PRD 依赖白名单里没有它 | 一个 `/healthz` 查询足够给 compose healthcheck 用 |
| **没有** Pinia | — | Vue `reactive` 一个对象就够存登录态 |
| **没有** MinIO Java SDK | — | 只需 S3 的 PUT/GET，手写 SigV4 签名（`S3SigV4`）比拖进 okhttp+kotlin-stdlib 更合算 |
| **没有** Flyway | — | 迁移器 60 行（`MigrationRunner`） |

## 已确认的默认值（PRD §12 + §13.7）

| # | 问题 | 默认 |
|-|-|-|
| Q1 | AI 是否给完整参考代码 | 不给，只给方向 + ≤5 行片段 |
| Q2 | LLM 供应商 | 阿里云百炼 DashScope 通义千问（OpenAI 兼容端点） |
| Q3 | 语言版本 | Java 21 / Python 3.12 |
| Q4 | 学生能否互看提交 | 不能 |
| Q5 | 域名 | 先用 `http://IP:8080`，不备案 |
| Q6 | 邮件 | 不发邮件，管理员生成初始密码 |
| Q7 | 教师自助注册 | 不能；注册恒为学生，教师账号由管理员创建 |
| Q8 | 首个管理员 | 启动时按 `APP_ADMIN_EMAIL`/`APP_ADMIN_PASSWORD` 幂等创建 |
| Q9 | 登录锁定维度 | 按账号（5 次 / 锁 10 分钟） |
