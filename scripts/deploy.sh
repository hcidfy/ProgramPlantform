#!/usr/bin/env bash
# M1-7 一键构建 + 重启（服务器上：git pull && ./scripts/deploy.sh）
# 约定：仓库根有 .env（从 .env.example 复制并改掉全部密码）
set -euo pipefail

cd "$(dirname "$0")/.."
COMPOSE=(docker compose -f deploy/docker-compose.prod.yml --env-file .env)

log() { printf '\033[1;34m[deploy]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[deploy]\033[0m %s\n' "$*" >&2; exit 1; }

env_get() { grep -E "^$1=" .env | head -1 | cut -d= -f2- ; }

command -v docker >/dev/null 2>&1 || die "没有找到 docker"
docker compose version >/dev/null 2>&1 || die "没有找到 docker compose 插件（需要 Docker Compose v2）"

[ -f .env ] || die ".env 不存在：先 cp .env.example .env，再改掉里面的全部密码与 JWT 密钥"

# ---- 上线前体检：密钥不能是示例值 ----
JWT_SECRET="$(env_get APP_JWT_SECRET || true)"
if [ -z "${JWT_SECRET}" ]; then die "APP_JWT_SECRET 未设置"; fi
if [ "${#JWT_SECRET}" -lt 32 ]; then die "APP_JWT_SECRET 至少 32 字符（当前 ${#JWT_SECRET}）"; fi
case "$JWT_SECRET" in *change_me*) die "APP_JWT_SECRET 还是示例值，必须替换（openssl rand -base64 48）" ;; esac

if env_get POSTGRES_PASSWORD | grep -q '^change_me'; then die "POSTGRES_PASSWORD 还是示例值，必须替换"; fi
if env_get MINIO_ROOT_PASSWORD | grep -q '^change_me'; then die "MINIO_ROOT_PASSWORD 还是示例值，必须替换"; fi
if env_get APP_ADMIN_PASSWORD | grep -q '^Admin@12345$'; then log "警告：APP_ADMIN_PASSWORD 是默认值，登录后请尽快修改"; fi

# ---- AI provider 专项检查（M3）：假 AI 绝不能悄悄上线 ----
AI_PROVIDER_VALUE="$(env_get AI_PROVIDER || true)"
AI_TOKEN_VALUE="$(env_get AI_INTERNAL_TOKEN || true)"
if [ -z "${AI_TOKEN_VALUE}" ]; then die "AI_INTERNAL_TOKEN 未设置（app↔ai 的内部令牌）"; fi
if [ "${#AI_TOKEN_VALUE}" -lt 16 ]; then die "AI_INTERNAL_TOKEN 太短，至少 16 字符"; fi
case "${AI_TOKEN_VALUE}" in *change_me*|*dev_internal*) die "AI_INTERNAL_TOKEN 还是示例值，必须替换" ;; esac

if [ "${AI_PROVIDER_VALUE}" != "openai" ]; then
  if [ "${ALLOW_STUB_AI:-0}" = "1" ]; then
    log "警告：AI_PROVIDER=${AI_PROVIDER_VALUE:-未设置}（假 AI / stub），仅演示用；已用 ALLOW_STUB_AI=1 显式放行"
  else
    die "AI_PROVIDER=${AI_PROVIDER_VALUE:-未设置}：生产必须是 openai。确需演示请显式执行 ALLOW_STUB_AI=1 ./scripts/deploy.sh"
  fi
else
  if [ -z "$(env_get DASHSCOPE_API_KEY || true)" ]; then die "AI_PROVIDER=openai 但 DASHSCOPE_API_KEY 为空"; fi
fi

log "构建镜像（app + web）"
"${COMPOSE[@]}" build

log "启动 / 更新容器"
"${COMPOSE[@]}" up -d --remove-orphans

log "等待 app 就绪（最长 120s）"
ready=0
for i in $(seq 1 40); do
  if docker exec codelab-app curl -fsS http://127.0.0.1:8080/healthz >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 3
done
if [ "$ready" -ne 1 ]; then
  "${COMPOSE[@]}" logs --tail=100 app
  die "app 在 120s 内没有就绪，请查看上面的 app 日志"
fi
log "app 健康检查通过"

PORT="$(env_get WEB_PORT || true)"
IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
log "完成：http://${IP:-<服务器IP>}:${PORT:-80}"
log "管理员账号：$(env_get APP_ADMIN_EMAIL)（密码见 .env）"
"${COMPOSE[@]}" ps
