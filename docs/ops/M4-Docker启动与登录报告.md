# M4 Docker 启动与登录报告

| 项目 | 记录 |
|-|-|
| 日期 | 2026-11-02 |
| 环境 | 本地 Docker Desktop / `deploy/docker-compose.prod.yml` |
| 结果 | 曾按要求停止并保留持久卷；本轮为账号走查重新启动，当前运行 |

## 启动记录

本轮使用现有 Compose 项目和已有数据卷启动，没有执行 `down -v`，没有删除数据库或 MinIO 数据。

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file .env up -d --build
```

启动期间发现 `.env` 使用的 Chainguard MinIO 镜像不含 `curl`，而 Compose 健康检查执行 `curl`，导致 MinIO 无法变为 healthy，依赖它的 app/web 无法启动。已将健康检查改为镜像自带的 `mc ready local`，验证 MinIO 变为 healthy 后，app 和 web 均成功启动。

启动结果：

- 8 个容器均进入运行状态；PostgreSQL、Redis、AI、MinIO 和 app 显示 healthy，RocketMQ 与 web 正常运行。
- app 的 `/healthz` 返回 `{"status":"UP","service":"codelab-app","db":"UP"}`。
- 数据库迁移日志显示本次执行 `V4__m4_admin_ai_review.sql`；数据库记录现有 V1 至 V4 迁移。
- 前端映射端口为 `8088`，登录入口为 `http://127.0.0.1:8088/login`。
- AI 服务当前配置为 `stub`，是开发用模拟回答，不代表生产模型已接通。

本轮重新启动后，数据库中确认有管理员 `admin@example.edu`、教师 `m3teacher223017@example.edu` 和学生 `m3a223017@example.edu`；教师与学生属于同一既有教学班 `M3验收班223017`。当前服务保持运行，方便用户进行本地走查。

## 登录失败问题复查与修复

用户在登录页看到“请求失败”后，检查发现 `/login` 与 `/healthz` 正常，但浏览器来源 `http://127.0.0.1:8088` 请求 `/api/auth/login` 返回 `403 Invalid CORS request`。原因是 `.env` 原白名单仅包含 Vite 的 5173 来源，且 Compose 未把 `APP_CORS_ORIGINS` 注入 app；无 `Origin` 的 PowerShell 请求因此成功，而浏览器请求被 CORS 拒绝。

已将 `localhost:8088` 与 `127.0.0.1:8088` 加入本地白名单，并在 Compose app 环境中传递 `APP_CORS_ORIGINS`；只重建了 app 容器，未重启 Docker Desktop、未删除数据卷。修复后同源空登录体返回参数校验 422（不再是 CORS 403），`/healthz` 正常。管理员、教师、学生三组凭据各自通过同一浏览器 Origin 完成 API 登录（200），随后立即登出（204）。

## 登录说明

1. 需要登录时，先在仓库根目录执行上方启动命令。
2. 打开 `http://127.0.0.1:8088/login`。
3. 管理员账号和密码分别读取根目录 `.env` 中的 `APP_ADMIN_EMAIL` 与 `APP_ADMIN_PASSWORD`。应用启动时会按配置幂等创建首个管理员；本报告不保存密码。
4. 教师和学生账号由管理员在组织管理页面创建或通过名单导入；具体登录凭据以创建/导入时设置的初始密码为准。
5. 首次登录后应按部署要求更改示例密码。若忘记管理员密码，使用受控的本地 `.env` 配置重置流程，不要把密码写入报告、日志或截图。

## 验证边界

本轮确认了 Docker 服务启动、app 健康端点和 V4 迁移状态。浏览器打开过登录页，但没有提交登录表单；因此尚未验证具体管理员凭据，也未完成管理员、教师、学生的登录及业务流程验收。

## 前次停止记录

按用户要求执行：

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file .env stop
```

停止后 8 个容器均为 Exited。`stop` 保留 Compose 容器及 `codelab_pgdata`、`codelab_miniodata`、`codelab_appjudge` 数据卷；再次启动可继续使用现有数据。
