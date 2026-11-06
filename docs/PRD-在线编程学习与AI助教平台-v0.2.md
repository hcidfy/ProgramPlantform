# 在线代码学习与知识问答平台 — PRD v0.2（简化版 / 可单人交付）

| 项 | 内容 |
|-|-|
| 版本 | v0.2.14（唯一交付依据；历史决策摘要见 §0 和 §16） |
| 状态 | 基线：M1/M2/M3 已实现并验收；M4 本地开发/人工验收中；未通过本地验收并获用户明确确认前，不部署到云端 |
| 部署基准 | 阿里云 ECS **2 核 4 GB** 单机（Linux），Docker Compose 编排 |
| 交付形态 | **两个后端服务**：`app`（Java/Spring Boot 单体）+ `ai`（Python/LangChain）；前端静态站由 Nginx 托管 |
| 开发方式 | 一人规划 + Agent 执行；不设测试/CI/可观测专章 |
| 读者 | 项目负责人、Agent |

> 本版是**唯一交付依据**：需求、权限、数据模型、契约均以此为准。落地契约见 §13（M1）、§14（M2）、§15（M3）。任务书定义课题目标与论文交付要求；本 PRD 将目标裁剪为可在 2C4G 单机完成的分阶段产品范围。
> 需求来源与范围裁剪结论见 §0；历史版本仅保留在本文件 §16 的版本记录中。

---

## 0. 范围收敛（v0.1 → v0.2）

### 0.1 与 v0.1 的差异（只留结论）

| 内容 | 现状 |
|-|-|
| 助教（TA）角色、批改工作台 | 删除（学院无 TA） |
| 判题沙箱、向量检索、ES、可观测、CI/CD、自动化测试 | 删除：判题改受限子进程、检索改 SQL 关键词、日志按天滚动 |
| 10 个微服务 / LangGraph 多 Agent | 2 个服务（`app` + `ai`）、4 条 LCEL 链 |
| RBAC 权限点矩阵 | 三角色 + AI token 配额 |
| 规模 1,000 注册 / 3,000 提交/日 | ≤150 注册 / ≤500 提交/日 |
| G1「缩短求助耗时」、G3/G5、学情画像 | 删除；G1 改为「帮助用户借助 AI 完成题目」 |

### 0.2 保留且强化的一条新规则

**AI 输出不可复制**（v0.1 无此项）：AI 只讲、不给可粘贴的成品，实现见 §8.5。

---

## 1. 定位、两个服务与设计取舍

### 1.1 平台只提供两个服务

| 服务 | 面向 | 一句话 | 入口 |
|-|-|-|-|
| **S1 答题解疑** | 学生（在题目内） | 把"这道题"做出来：看题 → 写代码 → 提交 → 判题 → AI 解释错误 / 给思路 / 分析为什么不对 | 题目详情页 |
| **S2 AI 知识问答** | 学生（在课程内） | 把"这门课"学明白：任意提问，基于教师维护的课程知识条目回答 | 课程知识页 / 悬浮对话框 |
| 支撑 | 教师、管理员 | 组织教学（班级/课程/作业/题目）、配置 AI 配额、看通过率 | 教师端 / 管理端 |

划分依据很硬：**S1 必须有提交物（代码 + 判题结果）才能答；S2 只靠课程知识 + 问题即可答**。两者上下文来源不同、触发时机不同，所以拆成两个入口，共用同一个 `ai` 服务。

### 1.2 取舍原则（写下来给未来的自己看）

| 原则 | 含义 |
|-|-|
| 单机能跑 | 任何设计先问"2C4G 装得下吗、我看得过来吗" |
| 先闭环后扩展 | MVP 只保证"布置作业 → 学生做出题 → 得到解释"闭合 |
| 用时间闸门代替内容审核 | 不做 AI 内容审核（成本不可持续），改用"首次提交后才开放 AI" |
| PG 是唯一事实源 | Redis 只存可丢数据；MQ 只做削峰；MinIO 只存文件 |
| 规则写死在代码里 | 三角色 + 自己的教学班/本班，不做可配置权限引擎 |
| 砍掉的东西要留接口 | FAQ 采纳率、错题本、错误类型统计等留表字段与 API 位，不提前实现 |

---

## 2. 教学组织结构（本版核心模型）

```
学校班级 school_class        例：2028级 应用数学 2班
   └── 教学班 teaching_class  = school_class × course × 学期 × 任课教师
         例：(应用数学2班, Java程序设计, 2026秋, 张老师)
         └── 作业 assignment：教师发起 → 推送给该教学班全部学生
                └── 题目 problems（可多题、可计分）
学生 enrollment：学生 ∈ school_class；登录后自动看到本班所有 teaching_class
```

- **教师发起作业，一键推给"当前班级 + 当前课程"内的全部学生**；学生首页"待办"即时可见，无需选课、无需 TA 参与。
- 一个学生属于一个行政班（可预留多班，MVP 单班）；一个教学班一个任课教师（可预留多教师，MVP 单教师）。
- 教师只能操作**自己任课的教学班**；学生只能看**自己班级的教学班**。这条规则写死在服务层，不做配置。

---

## 3. 问题与范围

### 3.1 问题处置表

| 原问题 | v0.2 结论 | 承载需求 |
|-|-|-|
| P1 卡点等待长（原依赖 TA） | 不设 TA；由 AI 在提交后即时回答 | FR-AI-01~03 |
| P2 判题反馈不可操作 | MVP 只给：通过/未通过、通过用例数、首个失败用例的输入/期望/实际、编译错误原文 | FR-JDG-02、FR-AI-03 |
| P3（重复答疑 / 学生档案） | **后置**：AI 生成错题本 + 错误类型，放学生个人中心 | 远期，不在 MVP |
| P4（学习记录碎片化） | **移出范围**：不做跨平台整合 | — |
| P5 AI 使用边界 | **换实现**：首次提交前关闭 AI；提交后开放；且 AI 输出不可复制 | FR-AI-00、FR-AI-04 |
| P6 题目与用例手工维护 | 教师端可视化建题 + 用例表 + 图片上传 | FR-PRB-01~04 |
| 通过率（原 P2 细分统计） | **只做**：判题通过提交数 / 班级总提交数 | FR-TCH-03 |

### 3.2 非目标

完整 LMS（直播/考勤/学分）、在线 IDE/调试器、竞赛排名、问答社区（投票/举报/审核）、多语言沙箱矩阵（MVP 仅 Java、Python）、多租户、移动端原生、跨平台数据整合、AI 内容审核、自动评分主观题。

---

## 4. 目标

| ID | 目标 | 可观察结果 | 衡量方式（不追转化） |
|-|-|-|-|
| **G1** | **帮助用户借助 AI 完成题目** | 学生在一次提交后用 AI 解释/思路/正确结果分析，最终自行改出通过代码 | 作业内"通过人数/应交人数"（教师端可见） |
| G2 | 判题与 AI 反馈能指向可修改的位置 | 反馈含行号或首个失败用例的输入/期望/实际 | 教师抽看任意 10 条反馈，能据其改代码 |
| G4（原学情画像） | **后置**：学生错题本 + 错误类型 | 个人中心可见"我做错过的题 + 错误类型" | 远期版本 |

> 不做：转化率、留存、耗时中位数、FAQ 命中率、提示使用率、粘贴率（均需额外埋点与运营，单人不可持续）。G3/G5 统计目标已删除；`ai_feedback` 按 §15.8 用于具体回答的学生评价与教师纠错，不作为统计或采纳率看板。

---

## 5. 角色与权限（三角色 + token 配额）

### 5.1 权限判定只有两条

1. **身份闸门**：`student` / `teacher` / `admin` 三个角色；教师再判"是否是该教学班的任课教师"；学生再判"是否属于该班"。其余规则不实现。
2. **配额闸门**：AI 调用按 **token 消耗**计。学生每日 token 上限、单题 AI 次数上限；教师/管理员可调班级与全局上限。超限返回 429 + "今日 AI 额度用完"。

### 5.2 能力矩阵

| 能力 | 学生 | 教师 | 管理员 |
|-|-|-|-|
| 看本班课程/作业/题目 | ✓ | — | ✓ |
| 看自己任教教学班的课程/作业/题目 | — | ✓ | ✓ |
| 提交代码、看自己提交 | ✓ | — | ✓ |
| 看本班学生提交（含代码） | — | ✓（本人教学班） | ✓ |
| AI：答疑 / 思路 / 正确结果分析（提交后） | ✓（配额内） | ✓ | ✓ |
| AI：知识问答 | ✓ | ✓ | ✓ |
| 建题、编用例、上传图片、布置作业 | — | ✓（本人教学班） | —（管理端提供） |
| 看通过率统计 | 自己 | 本人教学班 | 全部 |
| 账号管理、班级/课程/教学班绑定 | — | — | ✓ |
| AI 配额与总预算配置 | — | 本人教学班上限 | 全局 |

> 管理员没有“以某教师身份调教师端接口”的能力：教师类能力一律在 `/api/admin/**` 提供（§13.5）。

### 5.3 配额与成本口径（避免 token 失控）

| 项 | 默认值 | 调整方 |
|-|-|-|
| 学生日 token 上限 | 30,000 | 管理员全局 / 教师按教学班调 |
| 单题 AI 次数上限 | 10 次（答疑 6 + 思路 3 + 分析 1 的合计口径） | 教师按作业调 |
| 单次请求 `max_tokens` | 输出 ≤ 1,000 | 代码内固定 |
| 超限行为 | 429 + 明确文案；当日不恢复 | — |
| 计量粒度 | 每次调用记录 `prompt_tokens + completion_tokens` 到 `ai_usage` | — |

---

## 6. 功能需求

> 验收方式统一为"手工点验"：每条 FR 给出可点出来的结果，不再要求自动化测试。

### 6.1 账号与组织（FR-ACC）

| ID | 需求 | 验收 | 异常 |
|-|-|-|-|
| FR-ACC-01 | 学生/教师用邮箱 + 密码注册，管理员可批量导入名单（CSV：邮箱、姓名、角色、班级）生成账号 | 注册后收到验证邮件；导入后能在教师/学生列表看到 | 邮箱重复 → 提示已存在；导入失败行给出原因 |
| FR-ACC-02 | 登录发 access token（2 h）+ refresh token（7 d）；登出即失效 | 刷新页面保持登录；登出后接口 401 | 密码错 5 次锁 10 min |
| FR-ACC-03 | 管理员维护：学校班级、课程、教学班（班级 × 课程 × 教师 × 学期） | 教师端立即看到自己新建的教学班 | 同一班级同一课程重复建 → 拒绝 |
| FR-ACC-04 | 学生属班关系由管理员维护（MVP 单班） | 学生登录后"我的课程"只显示本班教学班 | 无班级学生 → 提示联系管理员 |

### 6.2 教学与作业（FR-TCH）

| ID | 需求 | 验收 | 异常 |
|-|-|-|-|
| FR-TCH-01 | 教师在自己的教学班下创建作业：标题、说明、截止时间、题目列表（含每题分值） | 作业发布后学生首页待办出现该作业 | 未选题 → 不允许发布 |
| FR-TCH-02 | 一键推送：发布即对该教学班全部学生可见（无需学生选课/接受） | 新入班学生在下次登录即看到未截止作业 | 截止后可独立练习；不附作业 ID，提交不计入作业分数；如需计分补交，另行设计并验收 |
| FR-TCH-03 | 教师端看作业进度：应交人数、已提交人数、通过人数、通过率和（通过提交数/总提交数） | 数值与提交列表可对账 | 无提交 → 显示 0 而非空白 |
| FR-TCH-04 | 教师可查看本教学班学生提交（代码 + 判题结果），可手动重判 | 重判生成新记录，旧记录保留 | 非本教学班 → 403 |

### 6.3 题库（FR-PRB）

| ID | 需求 | 验收 | 异常 |
|-|-|-|-|
| FR-PRB-01 | 建题：标题、题面（Markdown + 图片）、语言、难度、时间/内存限制、初始代码模板 | 学生端渲染题面与图片正常 | 单图 > 5 MB → 拒绝 |
| FR-PRB-02 | 用例管理：输入、期望输出、是否隐藏、分值，可排序 | 隐藏用例内容不出现在任何学生可见页面 | — |
| FR-PRB-03 | 图片/附件上传到 MinIO，题面引用对象 URL | 上传后在题面预览可见 | 非图片类型 → 拒绝（按白名单 MIME） |
| FR-PRB-04 | 题目草稿 → 发布；发布后题面改动需"另存为新版本" | 已发布题不可直接改题面 | — |

### 6.4 提交与判题（FR-SUB / FR-JDG）

| ID | 需求 | 验收 | 异常 |
|-|-|-|-|
| FR-SUB-01 | 学生在线编辑（Monaco）或粘贴代码提交；同一题 10 s 内重复提交命中幂等 | 提交后返回 `submission_id`，状态从 `排队→判题中→完成` | 代码 > 64 KB → 413 |
| FR-SUB-02 | 学生查看自己的提交历史与结果 | 列表含题号、语言、状态、通过数、时间；可点开看代码 | — |
| FR-JDG-01 | 判题：按语言在**本机子进程**中编译/运行全部用例（详见 §7.4） | Java、Python 各一道样例题能判出 AC/WA/CE/TLE | 判题进程崩溃 → 标记 `JUDGE_ERROR`，不阻塞其他提交 |
| FR-JDG-02 | 结果呈现：整体状态、通过用例数/总数、首个失败用例的输入与期望、实际输出（截断 500 字符）、编译错误原文（截断 4 KB） | 隐藏用例只显示"第 N 组未通过"，不给内容 | — |
| FR-JDG-03 | 判题并发上限（默认 2），超出排队；前端显示排队位置 | 3 人同时提交时第 3 人看到"排在第 1 位" | 队列积压 > 20 → 提示稍后再试 |

### 6.5 S1 答题解疑（FR-AI-01~05）

| ID | 需求 | 验收 | 异常 |
|-|-|-|-|
| **FR-AI-00** | **时间闸门**：学生对该题**首次提交前**，题目页不出现任何 AI 入口，后端接口一律 403 | 未提交时页面上无 AI 按钮；直接调接口返回 403 + "提交后才能获得 AI 帮助" | — |
| FR-AI-01 | **答疑**：就本次提交/本题提问，返回：错误定位（行号）、原因、下一步怎么做 | 点"问 AI 为什么错"，答案里出现行号与原因 | 无提交记录 → 403（同 FR-AI-00） |
| FR-AI-02 | **思路提示**：三级（L1 概念、L2 算法方向、L3 伪代码骨架），逐级解锁 | 每次只给一级；同一题同一级不重复计费 | 次数用完 → 按钮置灰并说明 |
| FR-AI-03 | **正确结果分析**：基于判题事实解释"为什么没通过、正确结果应该长什么样、差在哪" | 答案引用首个失败用例的期望/实际 | 无失败用例（已 AC）→ 只给"通过评语 + 复杂度/边界提醒" |
| FR-AI-04 | **AI 输出不可复制**（见 §8.5） | AI 区域内 Ctrl+C、右键、选中复制均无效；代码片段以图片形式呈现 | 用户截图/开发者工具 → 无法阻止，产品已明示 |
| FR-AI-05 | 学生可评价 S1/S2 AI 回答；标记"没帮助"时进入教师复核队列。任课教师记录判断并在确有错误或不完整时提交修正；学生可查看复核结论 | 学生提交差评后，具有课程/教学班权限的教师能看到回答、反馈和必要上下文；完成复核后原学生能看到教师结论及修正，原 AI 回答保留 | 越权查看返回 404；一个反馈只允许成功结案一次；复核须记录操作者与时间 |

### 6.6 S2 AI 知识问答（FR-QA）

| ID | 需求 | 验收 | 异常 |
|-|-|-|-|
| FR-QA-01 | 在课程内提问，返回分段回答 + 引用的课程知识条目（可展开看原文） | 有命中时给出分段解释和引用 | 无命中 → 固定提示课程资料未覆盖，不调用模型、不消耗 token |
| FR-QA-02 | 教师维护课程知识条目（Markdown，可打标签），发布后进入检索池 | 新建条目后 1 分钟内可被问答检索到 | 草稿不进检索池 |
| FR-QA-03 | 会话保存与续问（保留最近 5 轮上下文） | 刷新页面后历史仍在；可新建会话 | 会话超 30 轮 → 提示新建 |
| FR-QA-04 | 教师可看本教学班的问答记录（用于发现共性疑问，但不做社区） | 列表可按学生、时间筛 | — |

### 6.7 学生个人中心（FR-ME）

| ID | 需求 | 验收 |
|-|-|-|
| FR-ME-01 | 我的作业：待办/已完成、截止时间、我的通过情况 | 与本班作业一致 |
| FR-ME-02 | 我的提交：按题/时间筛选，看代码与结果 | — |
| FR-ME-03 | **错题本（占位）**：表与入口先建，MVP 列出"未通过的题"，AI 错误类型与统计后置 | 未通过的题出现在列表 |

### 6.8 管理端（FR-ADM）

| ID | 需求 | 验收 |
|-|-|-|
| FR-ADM-01 | 账号、班级、课程、教学班管理；批量导入导出 | 导入报告可下载 |
| FR-ADM-02 | AI 配额：全局日 token 预算、默认学生上限、按教学班覆盖 | 改后新调用立即生效 |
| FR-ADM-03 | 用量看板：当日 token、当日调用次数、按课程聚合、当月估算费用 | 数字与 `ai_usage` 一致 |
| FR-ADM-04 | 操作日志：登录、改配额、看他人代码、重判（只追加） | 可按时/人检索 |

**v0.2.14 组织管理规则**：管理员在「组织管理」下按教师账号、行政班、课程、教学班分页面操作。行政班代表学校真实班级；创建时须从启用教师中选班主任，班级页面合并学生名单导入、已有学生加入/移出与班级查看。教师账号以停用/启用维护并保留历史；班级/课程/教学班提供查看和删除，存在关联历史时拒绝删除，不级联丢弃教学记录。

**v0.2.14 AI 用量口径**：今日卡片为平台所有账号当天已计费模型调用汇总；本月为当月 1 日至今天。按任务/课程的表分别按功能/课程分组。登录、打开页面和缓存命中不扣费。学生每日限额是个人输入与输出 token 合计；学生覆盖 > 教学班覆盖 > 全局默认。单题调用上限只用于做题的 explain/hint/analyze，课程问答只受每日限额。服务端在新请求开始前检查限额，结束后按模型返回用量记账；当前请求不会被日额度中途截断，可能完成后使累计值越过限额，之后拒绝新调用。费用只是按 .env 单价估算。`AI_PROVIDER=stub` 时 token 为按字符数估算的开发近似值，不代表实际模型 token 或费用。专项覆盖列表显示学生姓名邮箱或教学班名称，不显示裸 UUID。

**v0.2.14 课程知识问答无命中策略**：检索不到已发布且命中的课程条目时，返回固定的未覆盖提示，不调用模型、不计 token，并保存会话。检索命中时将条目作为回答依据；引用以回答时内容为准。教师可直接修改已发布条目；修改会被审计且后续检索立即使用新内容，历史对话不改写。删除仍限草稿。

**v0.2.14 逾期练习**：截止后的作业继续提供题目练习入口，但入口不附带作业 ID；产生的判题提交作为独立练习，不进入该作业进度与分数。未过期入口仍按作业提交处理。

---

## 7. 技术架构与部署（2C4G 单机）

### 7.1 进程拓扑

```mermaid
flowchart TB
  subgraph ECS[阿里云 ECS 2C4G / Docker Compose]
    NG[Nginx<br/>静态站 + 反向代理]
    APP[app<br/>Spring Boot 单体<br/>账号/教学/题库/提交/判题/问答/配额]
    AI[ai<br/>Python FastAPI + LangChain<br/>4 条链]
    PG[(PostgreSQL<br/>唯一事实源)]
    RD[(Redis<br/>会话/配额计数/队列位置<br/>不持久化)]
    MQ[(RocketMQ<br/>namesrv+broker<br/>judge.task / notify)]
    MO[(MinIO<br/>题目图片/附件)]
    EXE[judge 子进程<br/>java / python3<br/>ulimit + 超时]
  end
  U[浏览器] --> NG
  NG --> APP
  NG --> AI
  APP --> PG
  APP --> RD
  APP --> MQ
  APP --> MO
  APP --> EXE
  AI --> PG
  AI --> RD
  APP -- 内部 HTTP /ai --> AI
```

**只有两个后端服务**：`app`（业务 + 判题调度 + 配额校验）与 `ai`（LLM 编排）。判题不是独立服务，是 `app` 内的有界线程池 + 子进程。

### 7.2 技术栈（最小依赖）

| 层 | 选型 | 说明 |
|-|-|-|
| 前端 | Vue 3 + Vite + TypeScript + Monaco Editor（单页应用，构建后由 Nginx 托管） | 6 个学生页 + 5 个教师页 + 3 个管理页 |
| 后端 | Java 21 + Spring Boot（4.0.x 基线）+ `web`、`jdbc`、`validation`、`security`、`mail` | **不引入** Spring Cloud / 注册中心 / 网关（Nginx 足够） |
| AI | Python 3.12 + FastAPI + LangChain（LCEL）+ 阿里 DashScope（通义千问）或任意 OpenAI 兼容端点 | 4 条链，无工具循环、无向量库 |
| 数据库 | PostgreSQL（citext、pg_trgm） | 迁移用手工 SQL 脚本按序执行（Flyway 可选） |
| 缓存 | Redis（无持久化：不配 RDB/AOF） | 丢了只影响会话与计数，重新登录即可 |
| 消息 | RocketMQ 单机（namesrv + broker，堆 -Xmx512m） | 只用两个 topic；**保留 PG 队列降级开关**（§7.6） |
| 文件 | MinIO | 题目图片/附件、导出文件 |
| 部署 | Docker Compose + 一键脚本 `deploy.sh` | 无 CI/CD，手动 `git pull && ./deploy.sh` |

### 7.3 内存账本（2C4G 的硬约束）

| 组件 | 预算 | 手段 |
|-|-|-|
| 系统 + Docker 守护进程 | ≤ 400 MB | 精简系统 |
| `app`（Spring Boot） | ≤ 900 MB | `-Xmx640m -Xss512k`，不引入 Cloud 全家桶 |
| `ai`（Python + LLM 客户端） | ≤ 300 MB | 单 worker；不加载 embedding 模型 |
| PostgreSQL | ≤ 450 MB | `shared_buffers=256MB`、`work_mem=8MB`、定期清理 |
| Redis | ≤ 100 MB | `maxmemory 64mb` + `allkeys-lru` |
| RocketMQ | ≤ 700 MB | 单 master、`-Xms256m -Xmx512m`、关闭不必要的日志 |
| MinIO | ≤ 200 MB | 单盘单节点 |
| Nginx + 余量 | ≤ 400 MB | 静态文件 gzip |
| **合计** | **≈ 3.4 GB** | 留 600 MB 峰值余量 |

### 7.4 判题实现（受限子进程）

在 `app` 容器内以低权限用户 `judge` 执行：`javac` / `python3` 编译运行，`bash -c 'ulimit -t/-u/-f; exec ...'` 限时限流，超时 = 题目时限 × 2 后杀进程树，输出截断 64 KB，单提交 ≤ 60 s、≤ 20 个用例，判题目录保留 24 h。

**这不是沙箱**：无容器、无 namespace，可读同机可读文件、可发网络。仅适用校内实名场景；开公网前必须换容器隔离（§10）。逐项参数与验收见 §14.6。

### 7.5 三个中间件各自只干一件事

| 组件 | 存什么 | 不存什么 | 丢了会怎样 |
|-|-|-|-|
| PostgreSQL | 全部业务事实：账号、班级、作业、题目、用例、提交、判题结果、AI 会话与用量、知识条目 | — | 系统不可用（每日 `pg_dump` 到 MinIO） |
| Redis | 登录会话、AI 日 token 计数、判题队列位置、限流计数 | 不存业务数据、不配持久化 | 用户重新登录；计数归零（可接受） |
| RocketMQ | 消息中转：`judge.task`（提交后异步判题）、`notify`（作业推送/判题完成通知） | 不做数据存储 | 在途消息丢失 → 前端显示"排队中"，可手动重提 |
| MinIO | 题目图片/附件、导出文件、每日备份 | 不存业务数据 | 图片 404，补传即可 |

### 7.6 简化后的运行逻辑（一次提交发生了什么）

```
学生点提交
 → app 校验（登录/班级/作业未截止/代码大小/配额）→ 写 submissions(status=queued)
 → 投 judge.task(submission_id)
 → 判题线程池取任务（并发 ≤ 2）→ 更新 status=judging
 → 逐用例跑子进程 → 写 judge_case_results
 → 汇总：accepted / wrong_answer / compile_error / runtime_error / timeout
 → 更新 status=done + 投 notify（前端轮询或 SSE 收到完成）
 → 学生点"问 AI" → app 校验（有提交 + 配额）→ 调 ai → 落 ai_messages/ai_usage → 返回
```

**当前实现（D1）**：判题任务走 PG 表 `judge_queue`（`FOR UPDATE SKIP LOCKED`）+ `JudgeTaskQueue` 接口；RocketMQ 版（`judge.task` / `notify`）保留编排，待 broker 可测时切换，业务代码零改动。

---

## 8. AI 设计（LangChain，无向量库）

### 8.1 四条链（LCEL，均为单轮/少轮，无工具调用）

| 链 | 输入 | 输出（结构化） | 触发 |
|-|-|-|-|
| `explain` 答疑 | 题面摘要 + 学生代码 + 判题结果 + 学生提问（可选） | `{ location{line}, cause, why, next_step, key_snippet(≤5行) }` | 提交后点按钮 |
| `hint` 思路 | 题面 + 已用提示等级 + 学生当前代码 | `{ level, concept, direction, outline(伪代码) }` | 提交后逐级解锁 |
| `analyze` 正确结果分析 | 判题结果（首个失败用例 期望/实际）+ 学生代码 + 题面 | `{ diagnosis, first_fail_case{seq,expected,actual}, gap, fix_direction, checklist[] }` | 提交后点按钮 |
| `chat` 知识问答 | 课程知识条目检索结果（Top 5）+ 最近 5 轮会话 + 问题 | `{ segments[], citations[{entry_id,title}], uncertain:bool }` | 课程知识页提问 |

统一要求：输出 JSON、可用 Pydantic 校验；校验失败重试 1 次，仍失败降级为纯文本；模型 `temperature=0.3`，`max_tokens ≤ 1000`。

### 8.2 时间闸门（本版最重要的产品规则）

| 状态 | AI 可用性 |
|-|-|
| 未提交 | **全部 AI 接口 403**；题目页不渲染 AI 区域；提示"提交后可与 AI 讨论你的代码" |
| 已提交（无论对错） | 答疑、思路、分析全部可用（受配额） |
| 已通过 | 仍可答疑（讲解）与知识问答；思路提示关闭（已无意义） |

理由：把 AI 变成"对答案的复盘工具"而不是"代写工具"，且**零审核成本**（不需要人看、不需要模型判抄）。

### 8.3 无向量检索的"知识问答"怎么做

1. 教师为课程维护知识条目（Markdown，带标签），存 `knowledge_entries`。
2. 提问时做两步检索（都在 PG 内）：
   - 关键词命中：`title/content ILIKE '%kw%'`，配合 `pg_trgm` 的 `similarity()` 排序；
   - 标签匹配：从问题里抽取预先登记的关键词表（如 `equals`、`重载`、`ArrayList`）。
3. 无命中时固定提示“课程资料暂未覆盖”，不调用模型；有命中时取 Top 5 条目拼进 prompt，要求模型**只在给定条目范围内作答，并标注引用**。
4. 质量兜底：条目 < 5 条时，问答退化为"通用解释 + 提示教师补充资料"。

> 取舍：召回靠关键词，问法差异大时会漏。这是删掉向量库的代价，用"教师把常见问题写成条目"来补，成本更低也更可控。

### 8.4 上下文与 token 控制

| 措施 | 做法 |
|-|-|
| 只传必要片段 | 答疑/分析只传失败行 ±40 行；题面只传摘要（前 800 字 + 限制条件） |
| 提示词落库 | `prompt_templates(task, version, body, status)`，改提示词不动代码 |
| 结果缓存 | 键 = `(task, submission_id, level, prompt_version)`，TTL 30 min，学生可强制刷新 1 次 |
| 计量 | 每次调用写 `ai_usage(user_id, task, prompt_tokens, completion_tokens, cost_cents, date)` |
| 超时与降级 | 首 token ≤ 3 s、整体 ≤ 20 s；超时给"AI 忙，先看判题结果" |

### 8.5 "AI 内容不可复制"的实现（前端约束 + 服务端约束）

| 层 | 做法 | 说明 |
|-|-|-|
| CSS | AI 区域 `user-select:none`，禁用右键菜单 | 拦截常规选择 |
| 事件 | 拦截 `copy`/`cut`/`contextmenu`/`Ctrl+C`/`Cmd+C`/`Ctrl+A` | 拦截快捷键与剪贴板 |
| 渲染 | 回答**分段成卡片**逐段渲染，不提供"整段 markdown"；关键代码片段用 **Canvas 绘制文本**（DOM 里没有可复制文本） | 让复制成本变高 |
| 服务端 | 结构化输出中 `key_snippet` 长度 ≤ 5 行；不返回"完整可提交代码"字段 | 从源头不给成品 |
| 明示局限 | 截图、OCR、开发者工具无法阻止；产品定位为"提高抄袭成本"，不是"绝对防复制" | 写进 UI 提示与本文档 |

### 8.6 不做（本版明确排除）

学生画像、错题本自动归类、错误类型统计、FAQ 采纳与命中率、AI 内容审核、抄袭检测、多轮工具调用 Agent、模型微调、语音输入。

---

## 9. 数据模型（PG，最小集）

> 本节是表清单索引。**实际 DDL 以 `deploy/db/V1__init.sql` ~ `V3__m3_ai.sql` 为准**（字段增补见 §13.6 / §14.7 / §15.6）。

| 表 | 关键字段 | 约束/索引 |
|-|-|-|
| `users` | id, email(citext), display_name, role(student/teacher/admin), status, created_at | email 唯一 |
| `auth_sessions` | id, user_id, refresh_hash, device, expires_at, revoked_at | `(user_id)` |
| `school_classes` | id, name(28级应用数学2班), grade_year, major | name 唯一 |
| `courses` | id, code, name, description | code 唯一 |
| `teaching_classes` | id, class_id, course_id, teacher_id, term, status | `(class_id, course_id, term)` 唯一 |
| `enrollments` | id, student_id, class_id | `(student_id)` 唯一（MVP 单班） |
| `assignments` | id, teaching_class_id, title, description, deadline, status, created_by | `(teaching_class_id, deadline)` |
| `assignment_problems` | assignment_id, problem_id, seq, score | 主键 (assignment_id, problem_id) |
| `problems` | id, course_id, title, statement_md, language, difficulty, time_limit_ms, mem_limit_kb, status, version, created_by | `(course_id, status)` |
| `test_cases` | id, problem_id, seq, input, expected, is_hidden, score | `(problem_id, seq)` 唯一 |
| `submissions` | id, user_id, problem_id, assignment_id, language, source_code, status, passed_cases, total_cases, score, idempotency_key, created_at, finished_at | `idempotency_key` 唯一；`(user_id, problem_id, created_at desc)` |
| `judge_case_results` | id, submission_id, case_seq, status, time_ms, expected_excerpt, actual_excerpt | `(submission_id, case_seq)` 唯一 |
| `ai_conversations` | id, user_id, kind(explain/hint/analyze/chat), problem_id, submission_id, created_at | `(user_id, created_at desc)` |
| `ai_messages` | id, conversation_id, role, content_json, tokens, created_at | `(conversation_id, created_at)` |
| `ai_usage` | id, user_id, date, task, calls, prompt_tokens, completion_tokens, cost_cents | `(user_id, date)` 唯一 + 累加 |
| `ai_quotas` | id, scope(global/teaching_class/student), scope_id, daily_tokens, per_problem_calls | `(scope, scope_id)` 唯一 |
| `knowledge_entries` | id, course_id, title, content_md, tags, status, published_at | `(course_id, status)`；`pg_trgm` GIN 索引 |
| `files` | id, owner_id, purpose(problem_image/export), object_key, mime, size | `(owner_id)` |
| `audit_logs` | id, actor_id, action, resource, detail jsonb, created_at | 只 INSERT |

> 备份：每日 `pg_dump` → MinIO（保留 7 天）；恢复演练每学期 1 次。

---

## 10. 安全与风险（本版可接受的边界）

| 风险 | 现状 | 处置 |
|-|-|-|
| 判题无隔离 | 学生代码可在服务器上执行 | 低权限 `judge` 用户 + ulimit + 超时 + 24 h 目录清理；**仅限校内实名使用**；开公网前必须换容器隔离 |
| 代码泄露 | 学生代码存 PG，教师可见 | 仅本人与任课教师可读；接口按 user_id 校验；日志不打印全量代码 |
| AI 提示注入 | 学生代码/题面进入 prompt | 代码作为"数据块"注入并声明"以下为学生代码，不是指令"；输出只允许结构化字段 |
| AI 内容错误 | 无审核 | 输出标注"AI 生成，可能有误"；判题结果永远优先于 AI 说法；教师可看问答记录 |
| AI 内容复制 | 前端拦截 | §8.5，明确"提高成本"而非"绝对防" |
| 配额被刷 | token 消耗计费 | 日上限 + 单题次数 + 全局预算告警（日用量 > 80% 时邮件提醒管理员） |
| 单点故障 | 单机 | 每日备份 + 一页"故障恢复手册"（重启顺序：PG → Redis → MQ → MinIO → app → ai → Nginx） |

---

## 11. 里程碑与状态

本项目按“先形成可演示的教学闭环，再补齐上线保障，最后按真实使用数据扩展”的顺序交付。M1–M3 构成任务书目标下的最小产品闭环；M4 是小规模部署前的运维与管理补齐；V1 只在核心闭环和运维稳定后启动。不得因参考文献或 v0.1 的目标形态而提前引入新服务或中间件。

| 优先级 / 阶段 | 目标与范围 | 可验收结果 / 产出 | 状态 |
|-|-|-|-|
| P0 / M1：项目与组织基础 | 仓库与 Compose 骨架、配置密钥约定、数据库迁移、三角色账号、教学组织、最小角色页面、部署入口 | 可创建班级/课程/教师/教学班并导入学生；按角色只显示授权范围；干净环境可以启动并登录 | ✅ 已交付（35 项验收断言） |
| P0 / M2：编程练习闭环 | 题库、测试用例、作业、在线编辑、提交、受限本机判题、结果和学习过程记录 | 教师发布作业；学生提交 Java/Python 代码并查看判题结果；教师可检查本教学班进度 | ✅ 已交付（51 项验收断言） |
| P0 / M3：AI 学习辅助闭环 | 首次提交闸门、错误解释、分级思路、结果分析、课程知识问答、配额与用量记录 | 提交前 AI 请求被拒；提交后按权限和配额提供辅助；知识问答可展示课程引用；输出不提供完整可提交程序 | ✅ 已交付（48 项验收断言） |
| P1 / M4：管理与上线保障 | 管理端组织表单与 CSV 导入、配额/用量看板、审计日志查询、AI 回答评价与教师复核修正、每日备份与恢复、故障手册、上线检查清单 | 管理员能通过界面完成组织维护；用量可与数据库对账；被学生标记的 AI 回答可由任课教师复核并向反馈学生展示结论/修正；备份可恢复 | 🔄 进行中：M4-1 至 M4-4 已实现，待 Docker 环境验收；M4-5/6 文档已编写、待现场核对；M4-7 工作流待实现 |
| P2 / V1：使用后优化 | 错题本与错误类型、FAQ/知识条目采纳、学习统计与学情视图 | 每项扩展先以 M4 后收集到的真实教学问题形成需求和验收口径，再单独更新 PRD | 后置，未承诺排期 |

### 11.1 任务书要求的设计与论文材料

以下是任务书单列的课题文档/论文交付物，**不属于 M4 平台功能或软件上线门槛**。需要时在独立文档工作中整理，毕业设计定稿时按学校模板核对：

| 材料 | 依据 / 覆盖内容 | 交付位置 |
|-|-|-|
| 系统总体架构图 | B/S、Vue/Nginx、Java app、Python ai、PostgreSQL/Redis/RocketMQ/MinIO、受限判题边界 | `docs/design/`（待整理） |
| 数据模型与核心表说明 | 用户、教学组织、题目、作业、提交/判题、AI 会话/用量/反馈等；以 V1–V5 SQL 为准 | PRD §9、§13–§15 与 `deploy/db/` |
| 关键数据流图 | 登录与组织授权、提交判题、学习记录形成 | `docs/design/`（待整理） |
| 模型调用流程图 | AI 时间闸门、上下文、内部鉴权、配额/计量、结构化输出和引用 | `docs/design/`（待整理） |
| 页面截图与代码示例 | 管理员/教师/学生关键流程及代表性实现 | `docs/thesis/`（待整理，截图须来自实际运行版本） |
| 接口说明 | 认证、组织、题库/作业/判题、AI、知识库接口及状态码/权限 | PRD §13–§15；可另导出接口索引 |
| 论文引用与格式 | 任务书参考文献、学校模板、图表编号与引用格式 | 论文交付物；按学校模板核对 |

### 11.2 阶段执行规则

1. **先盘点再改动**：以当前代码、SQL 迁移和真机验收证据为完成依据；已交付阶段不重复搭建。
2. **最小需求优先**：先完成 P0 教学闭环；P1 仅补齐管理与部署可靠性；P2 需有实际使用证据再立项。
3. **契约先行**：任何接口、表结构、权限或业务口径变动，先更新本 PRD 的契约和版本记录，再实现。
4. **部署约束**：继续以 2 核 4 GiB 单机为基准；引入新服务、中间件、依赖或改变已定架构前，先提出影响与替代方案。
5. **本地先行发布门槛**：M4 功能先在本地 Docker 环境构建、启动并由用户按管理员/教师/学生角色人工走查；记录问题并修复后重新走查。只有用户明确确认本地验收通过，才可准备云端发布；未获单独授权不得上传、部署或迁移云端数据。

### 11.3 任务书要求对照与待补齐项

| 任务书要求 | 当前覆盖 | 后续处理 |
|-|-|-|
| 多角色权限、判题结果、学习过程记录 | M1–M3 已实现并按既有验收清单验证 | M4 继续以教学班边界、数据库记录和对账能力验收 |
| 受限环境安全执行代码 | 当前为 app 容器内 `judge` 低权限子进程和资源限制，**没有容器/namespace 隔离** | 限定校内实名使用；若要开放公网或不可信用户，先评估独立隔离方案并更新 PRD/部署预算，不在当前 2C4G 方案内暗中承诺 |
| AI 输出可约束、可复核，并由人工评价修正 | 输出受结构化字段、上下文与判题事实约束；§15.8 规定教师复核流程 | 输出约束已实现；人工评价、修正、复核工作流按 M4 实现 |
| 架构图、数据流、模型流程、截图、代码示例、接口说明、规范引用 | PRD/SQL/API 已覆盖事实来源，成套论文材料尚待整理 | 依 §11.1 补齐，截图以实际版本为准，参考文献和格式依学校模板核对 |

### 11.4 M4 配额与用量看板契约

- 管理员接口：`GET /api/admin/ai/quotas` 返回全局默认值和已配置覆盖（含可读 `targetName`）；`PUT /api/admin/ai/quotas` 请求 `{scope:global|teaching_class|student, scopeId?, dailyTokens, perProblemCalls}`，按 `(scope,scopeId)` 新建或更新，global 的 `scopeId` 必须为空。teaching_class/student 目标必须存在且角色正确。
- 用量接口：`GET /api/admin/ai/usage` 返回今日总调用/token、当月总调用/token、按 task 聚合、按课程聚合；分日口径以应用数据库 `current_date` 为准。原始使用量与 `ai_usage` 对账；课程维度从 `ai_calls` 中题目的 course 及新增 `course_id` 归属汇总。
- 估算费用：从 `APP_AI_INPUT_PRICE_CENTS_PER_MILLION` / `APP_AI_OUTPUT_PRICE_CENTS_PER_MILLION` 读取输入/输出 token 单价（分/百万 token）；按当月实际 token 数估算，未配置正数单价时 API 标记 `costEstimateAvailable=false`，界面显示“未配置单价”，不把 0 元冒充实际账单。
- 修改配额写审计 `admin.ai_quota_update`，只允许 admin；更新立即作用于下一次 AI 调用。scope 优先级遵循 §15.5。

### 11.5 M4 操作日志检索契约

- `GET /api/admin/audit-logs?q=&action=&from=&to=&page=&size=` 仅 admin 可用；`q` 按操作者姓名/邮箱模糊匹配，`action` 按动作名匹配，时间范围按左闭右开 `[from,to)`，分页最大 100 条。
- 返回 `items[{id,actorId,actorName,actorEmail,action,resource,detail,ip,createdAt}]`、`total/page/size`。无操作者（例如匿名登录失败）的日志仍可按 action/时间查到。
- 查询只读，不返回密码、token、源代码正文或问答正文。需要核对“教师查看学生代码/问答”时检索 `teacher.view_code`、`teacher.view_qa`；M4-7 另有 AI 回答复核审计动作。

### 11.6 M4 备份与恢复契约

- `scripts/backup.sh` 在宿主机调用 `docker exec codelab-pg pg_dump`，压缩后经 MinIO S3 API 上传至 `backups/`，保留最近 7 天对象；凭证仅从 `.env` 读取。脚本可手工运行，也可由服务器 cron 每日调用，不新增常驻容器。
- `scripts/restore.sh <对象键> <目标数据库>` 下载备份并恢复到新建数据库；目标名须符合 PostgreSQL 标识符规则，禁止与生产数据库同名，已有目标库时拒绝覆盖。恢复演练以临时库进行。
- S3 客户端使用 Python 标准库实现 SigV4，不新增依赖；失败时非零退出并保留可定位的错误信息，不打印密码/密钥。

---

## 12. 决策状态

**已定（不再讨论）**：Q1 AI 不给完整代码（已实现，§8.5 / §15.3）；Q3 Java 21 / Python 3.12；Q4 学生不可互看；Q6 不发邮件，管理员建号发初始密码（§13.1）。

**仍开放**：

| # | 问题 | 影响 | 默认 |
|-|-|-|-|
| Q2 | 生产模型供应商与数据合规（是否用 DashScope、能否出校） | `ai` 上线 | DashScope `qwen-plus` |
| Q5 | 域名与 HTTPS（阿里云需备案） | 部署与 cookie `Secure` | 先 `http://IP:8080` |

---

## 13. M1 接口契约（v0.2.1 追加）

> 本节是 M1 落地前补的契约，正文 §5/§6/§9 仍是依据；本节只把"正文没写细的部分"钉死，避免实现时自由发挥。冲突时以正文为准，并回头改正文。

### 13.1 认证与会话（FR-ACC-01/02）

| 方法 | 路径 | 谁 | 请求 | 响应 |
|-|-|-|-|-|
| POST | `/api/auth/register` | 匿名 | `{email, displayName, password}` | `201 {id,email,displayName,role,status}` |
| POST | `/api/auth/login` | 匿名 | `{email, password}` | `200 {accessToken, tokenType, expiresIn, user}` + `Set-Cookie: refresh_token` |
| POST | `/api/auth/refresh` | 持 refresh cookie | 空 | `200 {accessToken, tokenType, expiresIn}`（refresh 轮换） |
| POST | `/api/auth/logout` | 登录态 | 空 | `204`（撤销当前会话 + 清 cookie） |
| GET | `/api/auth/me` | 登录态 | — | `200 {id,email,displayName,role}` |

- 注册固定落 `role=student`、`status=active`（FR-ACC-01 的邮件验证与 Q6 一并后置）；教师/管理员账号由管理员创建。
- 密码：8~64 字符，须同时含字母与数字；Argon2id 摘要。
- 失败锁定：同一账号连续 5 次密码错 → 锁 10 分钟（`users.failed_login_attempts` / `users.locked_until`）。
- access token：**手写 HS256 JWT**，claims `sub(用户id)/role/sid(会话id)/iat/exp/typ=access`，有效期 2 h，签名密钥 `APP_JWT_SECRET`。
- refresh token：32 字节随机值 base64url，**HTTP-only Cookie**（`SameSite=Strict`、`Path=/api/auth`、7 d）；库内只存其 SHA-256 摘要（`auth_sessions.refresh_hash`）。
- 登出语义：撤销 `sid` 对应会话（`revoked_at` 置位），access token 随之在后端校验时失效。

### 13.2 管理端接口（FR-ACC-03/04，全 admin）

| 方法 | 路径 | 说明 |
|-|-|-|
| GET | `/api/admin/users?role=&q=&page=&size=` | 账号列表（不含 password_hash） |
| POST | `/api/admin/users` | `{email,displayName,role,initialPassword}` 建单账号 |
| POST | `/api/admin/users/import` | `text/csv`：`email,display_name,role,class_name,password`（role 默认 student；password 可省略并生成初始密码；接受 UTF-8 BOM）→ `{created, createdUsers[{line,email,role,initialPassword}], failed[{line,reason}]}` |
| GET/POST | `/api/admin/school-classes` | `{name, gradeYear, major, homeroomTeacherId}`；班主任必须为启用教师 |
| GET/POST | `/api/admin/courses` | `{code, name, description}` |
| GET/POST | `/api/admin/teaching-classes` | `{classId, courseId, teacherId, term}`；`(classId,courseId,term)` 重复 → 409 |
| GET/POST | `/api/admin/enrollments` | `{studentId, classId}`；一人一班，改绑即更新 |
| GET | `/api/admin/teaching-classes` | 全部教学班（含班级/课程/教师名） |
| DELETE | `/api/admin/school-classes/{id}` | 下面还有教学班或学生 → 409 `CLASS_IN_USE` |
| DELETE | `/api/admin/courses/{id}` | 下面还有教学班 → 409 `COURSE_IN_USE` |
| DELETE | `/api/admin/teaching-classes/{id}` | 有作业时 409，避免丢失教学历史；无作业才允许删除 |
| PUT | `/api/admin/users/{id}/status` | `{status:active|suspended}`；非 admin 账号启停，保留历史 |
| DELETE | `/api/admin/enrollments/{studentId}` | 退班；该学生本来没有班级 → 404 |

### 13.3 教师端接口（FR-ACC-03）

| 方法 | 路径 | 说明 |
|-|-|-|
| GET | `/api/teacher/teaching-classes` | 仅本人任课；含班级名/课程名/学期/学生数 |
| GET | `/api/teacher/teaching-classes/{id}` | 详情 + 学生名单；**非本人 → 404** |

### 13.4 学生端接口（FR-ACC-04）

| 方法 | 路径 | 说明 |
|-|-|-|
| GET | `/api/student/courses` | 本班全部教学班（课程名、教师名、学期）；无班级 → 空列表 + `needClass=true` |
| GET | `/api/student/courses/{id}` | 详情；**非本班 → 404** |

### 13.5 统一约定

- 错误体：`{"code":"SOME_CODE","message":"中文可读"}`。
- 状态码：`401` 未登录/令牌失效；`403` 身份不允许；`404` 资源不存在**或不属于你**（不泄露存在性）；`409` 冲突（邮箱已存在、教学班重复）；`422` 字段校验失败；`429` 配额超限（M3）。
- 列表分页：`page`（从 1 开始）+ `size`（默认 20，上限 100），响应 `{items,total,page,size}`。
- 租户隔离写在服务层（不做配置）：教师按 `teaching_classes.teacher_id = 当前用户` 过滤，学生按 `enrollments.class_id = 我的班级` 过滤。
- **角色与入口对应**（M1 落地时的确定项）：`/api/teacher/**` 仅 `teacher` 角色可调，`/api/admin/**` 仅 `admin`；管理员需要看"全部教学班 + 班级名单"时走 `/api/admin/teaching-classes`、`/api/admin/enrollments`，**不具备"以某位教师身份调教师端接口"的能力**（要替某教师操作，就把该管理员设为该教学班的任课教师）。这与 §5.2 的"管理员 ✓"一致：能力在管理端提供，不共用教师端入口。
- 审计：登录成功/失败、管理员建改删、后续"查看他人代码/重判"一律写 `audit_logs`（只追加）。

### 13.6 M1 表结构增补（相对 §9）

| 表 | 增补 |
|-|-|
| `users` | `last_login_at timestamptz`、`failed_login_attempts int`、`locked_until timestamptz`（支撑失败锁定） |
| `auth_sessions` | `created_at timestamptz`（`device` 存 UA 摘要） |
| `enrollments` | `created_at timestamptz` |
| `audit_logs` | `detail jsonb`、`ip inet`（`resource` 存 `类型:id`） |
| `schema_migrations` | 新增内部表 `(version text pk, applied_at timestamptz)`，由应用启动时的迁移器维护；`deploy/db/*.sql` 按文件名升序执行，已执行版本跳过（满足"可重复执行"） |

### 13.7 本版新增待决策

| # | 问题 | 默认（已按此实现） |
|-|-|-|
| Q7 | 教师能否自助注册成 `teacher`？ | **不能**：注册恒为 student；教师账号由管理员创建 |
| Q8 | 首个管理员怎么来？ | 启动时按 `APP_ADMIN_EMAIL` / `APP_ADMIN_PASSWORD` 幂等创建（不存在才建） |
| Q9 | 登录锁定按账号还是按 IP？ | 按账号（IP 维度留到有网关限流时做） |

---

## 14. M2 接口契约（v0.2.2 追加）

> M2 = 题库 + 作业 + 学生做题 + 提交判题 + 结果页（PRD §11）。本节只写正文没写细的部分；冲突以正文为准。

### 14.1 三处落地决策（已按此实现）

| # | 决策 | 理由（可行性） |
|-|-|-|
| **D1** | 判题异步**先走 PG 队列表 `judge_queue`**（`FOR UPDATE SKIP LOCKED`），RocketMQ 版等有 broker 可测时再换 | 即 §7.6 预留的降级开关。本机无 Docker → MQ 无法验证，交付未验证的 MQ 代码是更大的风险。已按 §7.6 抽 `JudgeTaskQueue` 接口，换实现业务代码零改动；`judge.task`/`notify` 两个 topic 的定义与 compose 编排保留 |
| **D2** | 文件存储走 `FileStorage` 接口：`minio`（生产，手写 S3 SigV4，不引 SDK）+ `local`（**仅开发**，写本地目录） | MinIO 官方已停止分发免费单机二进制、Docker Hub 在本机不可达 → 无法本地起 MinIO 验证；用同一接口的 local 实现把上传/展示链路跑通，MinIO 实现交付但**首部署必须实测一次**（手册给了命令） |
| **D3** | 判题在 **`app` 容器内**执行，运行镜像从 JRE 换 **JDK 21 + python3**；生产以 `runuser -u judge` 降权运行（`APP_JUDGE_RUN_AS_USER=judge`），开发机走直跑分支 | §7.4 要求 javac/python3 与低权限用户；同机同容器最省内存，符合 2C4G 预算 |

### 14.2 题库（教师，scope = 自己任课教学班的课程）

| 方法 | 路径 | 说明 |
|-|-|-|
| GET | `/api/teacher/problems?courseId=&status=&q=` | 仅限自己任课课程；`courseId` 越权 → 404 |
| POST | `/api/teacher/problems` | `{courseId,title,statementMd,language,difficulty,timeLimitMs,memLimitKb,initialCode}` → draft |
| GET | `/api/teacher/problems/{id}` | 含用例（教师可见隐藏用例） |
| PUT | `/api/teacher/problems/{id}` | **仅 draft 可改**；已发布 → 409 `PROBLEM_LOCKED` |
| POST | `/api/teacher/problems/{id}/publish` | draft → published；无用例 → 422 |
| POST | `/api/teacher/problems/{id}/new-version` | 已发布题改题面的唯一路径：复制为 draft，`version+1`，`previous_version_id` 指向旧题 |
| DELETE | `/api/teacher/problems/{id}` | 仅 draft |
| GET/PUT | `/api/teacher/problems/{id}/test-cases` | `PUT` **整体替换**（按数组顺序写 seq 1..n，事务内先删后插） |

用例字段：`{seq,input,expected,isHidden,score}`；用例数 ≤ 20（§7.4）。

### 14.3 文件（题目图片）

| 方法 | 路径 | 说明 |
|-|-|-|
| POST | `/api/teacher/files` | `multipart/form-data`：`file` + `problemId`；白名单 MIME `image/png|jpeg|gif|webp`；单文件 ≤ 5 MB（超限 413） |
| GET | `/api/files/{id}` | 图片回显：文件所有者（教师）或"该题对学生可见"时可读，否则 404。题面里写 `![](/api/files/{id})` |

### 14.4 作业（教师）

| 方法 | 路径 | 说明 |
|-|-|-|
| POST | `/api/teacher/assignments` | `{teachingClassId,title,description,deadline,problems:[{problemId,score}]}` → draft；非本人教学班 → 404 |
| PUT | `/api/teacher/assignments/{id}` | draft 可改 |
| POST | `/api/teacher/assignments/{id}/publish` | draft → published（一键推送，学生立即可见） |
| GET | `/api/teacher/assignments?teachingClassId=` | 列表 + 进度摘要 |
| GET | `/api/teacher/assignments/{id}` | 详情：题目列表 + `{expectedCount,submittedCount,passedCount,passRate,throughoutRate}` |
| GET | `/api/teacher/assignments/{id}/submissions?problemId=` | 本教学班学生的提交（**含代码**，写审计 `teacher.view_code`） |
| POST | `/api/teacher/submissions/{id}/rejudge` | 复制为新提交（`rejudge_of`）重新排队，旧记录保留 |

进度口径（FR-TCH-03）：`expectedCount` = 该教学班学生数；`submittedCount` = 交过至少一次的人数；`passedCount` = 该作业全部题目都 AC 的人数；`passRate` = passed/expected；`throughoutRate` = 通过提交数 ÷ 总提交数（PRD §3.1 只做这一个百分比）。

### 14.5 学生（做题 / 提交 / 我的）

| 方法 | 路径 | 说明 |
|-|-|-|
| GET | `/api/student/assignments` | 本班已发布作业 + 每题我的状态（`not_started/submitted/passed`） |
| GET | `/api/student/assignments/{id}` | 作业详情 + 题目列表（非本班 → 404） |
| GET | `/api/student/problems/{id}` | 题面 + 初始代码 + 限制；**隐藏用例内容永不下发**；非本班可见 → 404 |
| POST | `/api/student/submissions` | `{problemId,assignmentId?,language,sourceCode}`；源码 > 64 KB → 413；同题 10 s 内重复 → 返回既有提交（幂等） |
| GET | `/api/student/submissions/{id}` | 状态/结果；`queued` 时给 `queuePosition`；含代码（仅本人） |
| GET | `/api/student/submissions?problemId=&assignmentId=&page=&size=` | 我的提交历史 |

提交状态机：`queued → judging → done`（结果 `accepted/wrong_answer/compile_error/runtime_error/timeout`）或 `judge_error`。
结果展示口径（FR-JDG-02）：`passedCases/totalCases/score`、首个失败用例的 `input/expected/actual`（各截断 500 字符）、`compileOutput`（截断 4 KB）；**隐藏用例只显示"第 N 组未通过"**。

### 14.6 判题执行（对齐 §7.4）

| 项 | 值 |
|-|-|
| 工作目录 | `APP_JUDGE_DIR`（生产 `/data/judge`）+ `/{submissionId}/`；结束后保留 24 h，由后台清理任务删除 |
| 编译 | `javac Main.java`，超时 10 s；Python 不编译 |
| 运行 | 外层 `bash -c 'ulimit -t <秒> -u 64 -f 2048 [-v <KB>]; exec <cmd>'`；超时 = 题目时限 × 2 后杀进程树 |
| Java | `java -Xmx{mem_limit_kb/1024}M -cp . Main`（**不加 `-v`**，JVM 预留虚拟内存会起不来） |
| Python | `python3 main.py`，**额外加 `-v`** |
| 截断 | 单用例输出 64 KB、编译错误 4 KB、实际输出回显 500 字符 |
| 并发 | 2（`APP_JUDGE_CONCURRENCY`）；队列积压 > 20 → 提交返回 429 `JUDGE_BUSY` |
| 崩溃 | 判题进程异常/超时未收敛 → `judge_error`，不影响其他提交 |

### 14.7 表结构增补（相对 §9）

| 表 | 增补/说明 |
|-|-|
| `problems` | `initial_code`、`previous_version_id`（新版本链）、`published_at` |
| `submissions` | `result`（终态）、`compile_output`、`rejudge_of` |
| `judge_case_results` | 增加 `is_hidden`（隐藏用例只下发"第 N 组"）、`exit_code` |
| `judge_queue` | 新增：`(submission_id unique, status, attempts, enqueued_at, started_at, finished_at)` —— D1 的 PG 队列 |
| `files` | 新增 `problem_id`、`original_name`、`size_bytes`、`purpose`；对象键 `problem/{problemId}/{uuid}.{ext}` |

---

## 15. M3 接口契约（v0.2.3 追加）

> M3 = `ai` 服务 4 条链 + 时间闸门 + 配额 + 不可复制 + 知识条目与问答（PRD §11）。冲突以正文为准。

### 15.1 三处落地决策（已按此实现）

| # | 决策 | 理由（可行性） |
|-|-|-|
| **D4** | `ai` 服务两种 provider：`openai`（DashScope/任意 OpenAI 兼容端点，**生产**）与 `stub`（**仅开发**：按提示词返回确定性结构化结果） | 本地没有 `DASHSCOPE_API_KEY`，又必须把"闸门→调用→校验→计量→渲染"整条链路跑通；stub 只是把模型换成确定性函数，链条与生产一致（同一条 LCEL） |
| **D5** | AI 结果缓存放 **app 进程内**（TTL 30 min，键 `task + submission_id + level + prompt_version`），不新增 Redis 依赖 | 缓存是可丢数据（PRD §7.5）；单实例部署，进程内 Map 够用；重启只影响"白省一次调用" |
| **D6** | `app → ai` 走内部 HTTP，带 `X-Internal-Token`；`ai` **不对外暴露端口**（仅 compose 内网） | ai 调用的上下文里有学生代码与题面，不能给公网直连；token 由 `.env` 生成 |

### 15.2 时间闸门与"能不能用 AI"（FR-AI-00）

`GET /api/student/problems/{problemId}/ai/status` →

```json
{ "hasSubmission": true, "passed": false, "canUseAi": true, "reason": null,
  "hintLevelsAvailable": 3, "hintLevelsUnlocked": 1,
  "perProblemCalls": 2, "perProblemCallLimit": 10,
  "dailyTokens": 500, "dailyTokenLimit": 30000 }
```

| 状态 | 规则（PRD §8.2） |
|-|-|
| 该题**没有任何提交** | `canUseAi=false`，所有 S1 AI 接口 → **403 `AI_GATE_CLOSED`**，前端不渲染任何 AI 入口 |
| 已提交（无论对错） | 答疑 / 思路（逐级）/ 分析 可用（受配额） |
| 已通过 | 思路提示关闭（`hintLevelsAvailable=0`）；答疑与知识问答仍可用 |
| 配额用尽 | `canUseAi=false`，原因写明；接口 → **429 `AI_QUOTA_EXCEEDED`** |

### 15.3 S1 三条接口（答疑 / 思路 / 分析）

| 方法 | 路径 | 请求 | 响应字段（PRD §8.1） |
|-|-|-|-|
| POST | `/api/student/problems/{problemId}/ai/explain` | `{submissionId?, question?, forceRefresh?}` | `location{line} cause why nextStep keySnippet` |
| POST | `/api/student/problems/{problemId}/ai/hint` | `{submissionId?, level, forceRefresh?}` | `level concept direction outline` |
| POST | `/api/student/problems/{problemId}/ai/analyze` | `{submissionId?, forceRefresh?}` | `diagnosis firstFailCase{seq,expected,actual} gap fixDirection checklist[]` |

统一返回外层：`{ task, promptVersion, payload{...}, cached, usage{promptTokens,completionTokens}, remaining{perProblemCalls,dailyTokens} }`

- `submissionId` 省略 → 取该题最近一次提交；不属于本人/该题 → 404。
- **计费口径**：缓存命中不计费；**思路同一题同一级只计一次**（第二次直接复用已存回答，不再调模型）；`forceRefresh` 每个提交每 task 只允许 1 次。
- **FR-AI-04 服务端硬约束**：`keySnippet` **≤5 行**、`outline` ≤12 行，任何字段**不得包含完整可提交代码**（ai 侧截断，app 侧二次校验）。
- 超时（>20 s）/上游异常 → 502 `AI_UNAVAILABLE`，文案"AI 忙，先看判题结果"。

### 15.4 S2 知识问答

| 角色 | 接口 | 说明 |
|-|-|-|
| 教师 | `GET/POST /api/teacher/knowledge?courseId=` | 知识条目列表/新建（`{courseId,title,contentMd,tags[]}`） |
| 教师 | `PUT /api/teacher/knowledge/{id}`、`POST /{id}/publish`、`DELETE /{id}` | 只有 draft 可改/删；发布后进检索池 |
| 学生 | `POST /api/student/courses/{courseId}/ai/ask` | `{question, conversationId?}` → `{conversationId, segments[], citations[{entryId,title,contentMd}], uncertain, usage}` |
| 学生 | `GET /api/student/courses/{courseId}/ai/conversations`、`GET /api/student/ai/conversations/{id}` | 会话列表 / 最近 20 条消息（保留最近 5 轮上下文给模型） |
| 教师 | `GET /api/teacher/knowledge-qa?teachingClassId=&studentId=&page=&size=` | 本教学班学生的问答记录（要做审计 `teacher.view_qa`） |

检索（PRD §8.3，无向量库）：`pg_trgm similarity()` + `title/content ILIKE` + 标签命中，取 **Top 5**（仅 `published`、限本课程）；
无命中 → `noEntries=true`、`uncertain=true`，返回固定的课程资料未覆盖提示，不调用模型、不计 token；仍保存会话记录。会话超 30 轮提示新建。

### 15.5 配额与计量（PRD §5.3 / §8.4）

| 项 | 默认 | 解析顺序 |
|-|-|-|
| 学生日 token 上限 | 30,000 | `ai_quotas(scope=student, scope_id=用户)` → `teaching_class` → `global` → 默认值 |
| 单题 AI 次数上限 | 10（答疑 6 + 思路 3 + 分析 1 的合计口径） | 同上 |
| 单次 `max_tokens` | 1,000 | 代码内固定 |
| 计量 | 每次**实际调用**写 `ai_calls` 明细 + `ai_usage(user,date,task)` 累加 | — |
| 超限 | 429 + 明确文案，当日不恢复 | — |

### 15.6 表结构（V3 新增 8 张）

| 表 | 关键字段 |
|-|-|
| `ai_conversations` | `user_id, kind(explain/hint/analyze/chat), course_id, problem_id, submission_id, level, created_at` |
| `ai_messages` | `conversation_id, role(user/assistant), content jsonb, text, tokens, created_at` |
| `ai_usage` | `user_id, date, task, calls, prompt_tokens, completion_tokens, cost_cents`；唯一 `(user_id,date,task)` |
| `ai_quotas` | `scope(global/teaching_class/student), scope_id, daily_tokens, per_problem_calls`；唯一 `(scope,scope_id)` |
| `ai_calls` | 调用流水：`user_id, problem_id, kind, level, submission_id, prompt_tokens, completion_tokens, charged, cached` |
| `knowledge_entries` | `course_id, title, content_md, tags text[], status(draft/published), published_at, created_by` + `pg_trgm` GIN |
| `prompt_templates` | `task, version, body, status(active/archived)`；V3 灌入 explain/hint/analyze/chat v1 |
| `ai_feedback` | 学生对单条 assistant 消息的评分/反馈、待复核状态和时间；字段及状态见 §15.8 |
| `ai_feedback_reviews` | 教师复核结论、修正说明、审核人和时间；追加式记录，详见 §15.8 |

### 15.7 本版新增待决策

| # | 问题 | 默认（已按此实现） |
|-|-|-|
| Q10 | 教师错题本/FAQ 采纳 | 仍后置（V1）；AI 回答反馈与纠错按 §15.8 纳入 M4，二者不混为 FAQ 采纳 |
| Q11 | 是否允许教师自定义提示词 | 表已就绪（`prompt_templates`），**暂不开放编辑入口**，改文案直接改库 |

### 15.8 AI 回答评价、教师复核与修正（M4 新增契约）

#### 目标与范围

对学生收到的每条 S1（explain/hint/analyze）或 S2（chat）AI 回答提供评价入口。该流程用于发现和纠正具体回答的问题；教师的修正作为该条回答的补充说明展示给提交反馈的学生，**不覆盖原 AI 回答、不自动修改提示词、不作为全课程知识库条目**。全班复用的知识沉淀属于 V1 的 FAQ/知识条目采纳范围。

#### 学生流程

1. 每条 AI 回答下显示“有帮助 / 没帮助”。同一学生对同一条 assistant 消息只能提交一次评价。
2. 选择“有帮助”后记录评价并结束流程，不进入教师待办。
3. 选择“没帮助”时必须选择原因：`incorrect`（事实/结论错误）、`incomplete`（关键内容缺失）、`unclear`（表达不清）、`off_topic`（答非所问）、`other`；可填写不超过 500 字的补充说明。提交后状态为 `pending`。
4. 学生可查看自己提交的反馈状态。教师结案后，学生可查看复核分类、教师说明及修正内容；仍可继续追问 AI，但修改后的说明不会被当成新的 AI 对话上下文。

#### 教师流程与状态

1. 教师在“AI 回答复核”页按本人任教教学班查看待复核反馈，可按状态、课程/教学班筛选。
2. 复核详情展示学生反馈、原始 AI 回答、任务类型、时间、课程/题目，以及完成判断所需的判题摘要或知识库引用。学生完整源代码默认不展示；确需看代码时仍须通过现有教师代码查看入口并记 `teacher.view_code` 审计。
3. 教师选择结论：`correct`（回答正确）、`partially_correct`（部分正确）、`incorrect`（回答错误）、`unverifiable`（现有信息无法验证）。填写复核说明；`partially_correct`/`incorrect` 必须填写面向学生的修正内容（≤2000 字）；`unverifiable` 必须说明无法验证的原因。
4. 提交后状态转为 `reviewed`，写入不可变复核记录，并审计 `teacher.review_ai_feedback`。结案后不允许覆盖原记录；如需再评，新增复核记录并保留历史，最新记录作为学生当前展示结果。
5. 教师只能处理与本人教学班有范围关系的反馈：学生属于该教学班行政班，且 AI 会话对应课程/题目属于该教学班课程。所有查询与提交均在服务端重复校验；越权资源返回 404。

#### 接口契约

| 角色 | 方法与路径 | 用途 / 请求 |
|-|-|-|
| 学生 | `POST /api/student/ai/messages/{messageId}/feedback` | `{useful, reason?, comment?}`；仅可评价本人会话内的 assistant 消息；成功返回 `{feedbackId,status}` |
| 学生 | `GET /api/student/ai/feedback?page=&size=` | 查询本人反馈与状态；教师结论/修正仅在复核完成后返回 |
| 教师 | `GET /api/teacher/ai-feedback?teachingClassId=&status=&page=&size=` | 查询本人教学班范围内的反馈；默认只返回 `pending` |
| 教师 | `GET /api/teacher/ai-feedback/{feedbackId}?teachingClassId=` | 查看原回答、学生反馈及最小必要上下文；访问写入审计 |
| 教师 | `POST /api/teacher/ai-feedback/{feedbackId}/review` | `{teachingClassId, verdict, explanation, correction?}`；只允许 `pending` 状态结案，第二次并发提交返回 409 |

新增 AI 回答接口需返回已落库的 assistant `messageId`，前端用该 ID 提交评价。统一校验：只有 `useful=false` 可带 `reason/comment` 并进入复核；`comment` ≤500 字，`explanation` ≤1000 字，`correction` ≤2000 字；不接受学生自报角色或教学班作为权限依据。

#### 数据与审计

- V4 对 `ai_feedback` 增补 `reason`、`comment`、`status`、`updated_at`，保留 `message_id` 与 `user_id` 作为反馈主体，增加 `(message_id,user_id)` 唯一约束；历史空反馈不进入待办。
- 新增 `ai_feedback_reviews`：`id, feedback_id, reviewer_id, verdict, explanation, correction, created_at`。每次教师复核追加一条，不覆盖旧结论；同一反馈的结案操作使用事务和状态条件保证仅一人成功。
- 允许结论：`correct/partially_correct/incorrect/unverifiable`；反馈状态：`pending/reviewed/not_required`。
- 新增审计动作 `student.rate_ai_answer`、`teacher.view_ai_feedback`、`teacher.review_ai_feedback`。日志仅记录反馈/消息/教学班标识、结论和操作者，不记录完整代码、密钥或原始私密问答正文。
- 学生只读本人反馈；任课教师只读本人教学班反馈；管理员可以按 M4 审计权限查看操作日志，但不默认获得问答正文读取权限。

---

## 16. 版本记录

| 版本 | 日期 | 变更 |
|-|-|-|
| v0.1 | 2026-10-25 | 完备目标形态：六域职能、40+ FR、10 微服务、沙箱、向量检索、全链路可观测、CI/CD |
| v0.2 | 2026-10-25 | 按"2C4G 单机 + 单人开发"重构：删 TA/沙箱/向量/可观测/CI/测试/社区；两服务；三角色 + token 配额；两服务定位（答题解疑 / AI 知识问答）；AI 时间闸门 + 不可复制；G1 改为"帮助用户借助 AI 完成题目"；规模降至 ≤150 注册 / ≤500 提交/日，并给出 2C4G 内存账本 |
| v0.2.1 | 2026-10-26 | **M1 落地契约**（新增 §13）：认证/管理端/教师端/学生端接口与状态码约定、JWT 与 refresh cookie 细则、失败锁定、`users`+`auth_sessions` 字段增补、`schema_migrations` 迁移器、新增待决策 Q7~Q9（教师不自助注册 / 管理员由 env 幂等创建 / 锁定按账号） |
| v0.2.2 | 2026-10-26 | **M2 落地契约**（新增 §14）：题库/用例/图片/作业/提交/判题接口与口径；三处落地决策 D1（判题先走 PG 队列，MQ 后置）/ D2（FileStorage 双实现，local 仅开发）/ D3（运行镜像换 JDK+python3、判题降权用户）；`judge_queue`/`files` 新表与若干字段增补 |
| v0.2.3 | 2026-10-26 | **M3 落地契约**（新增 §15）：时间闸门与 ai/status、三条 S1 接口与计费口径、S2 知识问答与检索、配额解析、V3 八张表；新增决策 D4（stub provider 仅开发）/ D5（结果缓存进程内）/ D6（内部 token、ai 不对外暴露）与 Q10~Q11 |
| v0.2.4 | 2026-11-02 | 文档瘦身（无功能变更）：删 v0.1→v0.2 变更表（并入 §0.1）、§9 示例 DDL（以 `deploy/db/*.sql` 为准）、§7.4 重复判题细节（并入 §14.6）；里程碑改状态表；开放问题拆「已定/仍开放」；修正 FR-STAT-01→FR-TCH-03、管理员教师类能力归属（§5.2 脚注） |
| v0.2.5 | 2026-11-02 | 依任务书统一分期与优先级：M1–M3 定义为 P0 最小教学闭环、M4 定义为 P1 上线保障、V1 定义为 P2 数据驱动扩展；补充论文/设计材料清单和阶段执行规则；M4 状态统一为规划中 |
| v0.2.6 | 2026-11-02 | 将任务书要求的 AI 人工评价修正细化为 FR-AI-05 和 §15.8：学生评价、教师复核、修正展示、教学班授权、追加式记录、接口与 V4 数据契约；明确课题文档材料独立于 M4 软件范围 |
| v0.2.7 | 2026-11-02 | M4-1 管理端组织表单实现：行政班、课程、教师、教学班创建和学生 CSV 导入复用现有管理接口；待运行验收 |
| v0.2.8 | 2026-11-02 | 新增 M4-2 配额与用量契约及管理页：新增课程维度调用归属和费用估算配置；待 Docker 环境验收 |
| v0.2.9 | 2026-11-02 | 新增 M4-3 管理员审计日志检索契约及页面；待 Docker 环境验收 |
| v0.2.10 | 2026-11-02 | 新增 M4-4 PostgreSQL→MinIO 备份/临时库恢复契约与脚本；补充 M4-5 故障手册、M4-6 上线检查清单 |
| v0.2.11 | 2026-11-02 | 修正 Chainguard MinIO 镜像不含 curl 导致 Compose 永远等待 MinIO 健康的启动阻塞；改用镜像自带 `mc ready` 检查 |
| v0.2.12 | 2026-11-02 | 明确 M4 本地 Docker 启动和管理员/教师/学生人工走查为云端部署前置门槛；只有用户确认本地验收通过后才进入云端发布 |
| v0.2.13 | 2026-11-02 | 修复 Compose app 未注入 `APP_CORS_ORIGINS`、导致 Nginx 同源登录 POST 被 Spring CORS 返回 403 的问题；本地白名单包含 127.0.0.1/localhost 的 8088 来源 |
| v0.2.14 | 2026-11-02 | 组织管理拆分为教师/行政班/课程/教学班页面；行政班新增班主任关系与名单维护；停用账号保留历史、保护教学班历史；改进 AI 用量解释与专项目标名称；课程问答无命中时不调用模型；发布知识条目允许编辑；逾期作业入口改为不计入作业成绩的独立练习 |
