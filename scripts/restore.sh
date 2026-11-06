#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] || { echo ".env 不存在" >&2; exit 1; }
command -v docker >/dev/null || { echo "docker 命令不存在" >&2; exit 1; }
command -v gzip >/dev/null || { echo "gzip 命令不存在" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 命令不存在" >&2; exit 1; }

if [ "$#" -ne 2 ]; then
  echo "用法：./scripts/restore.sh <MinIO 对象键> <新建目标数据库名>" >&2
  echo "示例：./scripts/restore.sh backups/codelab-20261102T010203Z.sql.gz codelab_restore_20261102" >&2
  exit 2
fi

object_key="$1"
target_db="$2"
if [[ ! "$target_db" =~ ^[A-Za-z_][A-Za-z0-9_]{0,62}$ ]]; then
  echo "目标数据库名只能由字母、数字和下划线组成，且不能以数字开头" >&2
  exit 2
fi

env_get() { grep -E "^$1=" .env | head -1 | cut -d= -f2-; }
PG_USER="$(env_get POSTGRES_USER || true)"; PG_USER="${PG_USER:-codelab}"
PG_DB="$(env_get POSTGRES_DB || true)"; PG_DB="${PG_DB:-codelab}"
MINIO_BUCKET="$(env_get MINIO_BUCKET || true)"; MINIO_BUCKET="${MINIO_BUCKET:-codelab}"
MINIO_API_PORT="$(env_get MINIO_API_PORT || true)"; MINIO_API_PORT="${MINIO_API_PORT:-9000}"
export MINIO_ROOT_USER="$(env_get MINIO_ROOT_USER || true)"
export MINIO_ROOT_PASSWORD="$(env_get MINIO_ROOT_PASSWORD || true)"
export MINIO_BUCKET
export MINIO_BACKUP_ENDPOINT="$(env_get MINIO_BACKUP_ENDPOINT || true)"
MINIO_BACKUP_ENDPOINT="${MINIO_BACKUP_ENDPOINT:-http://127.0.0.1:${MINIO_API_PORT}}"
export MINIO_BACKUP_ENDPOINT

if [ -z "$MINIO_ROOT_USER" ] || [ -z "$MINIO_ROOT_PASSWORD" ]; then
  echo "MINIO_ROOT_USER / MINIO_ROOT_PASSWORD 未配置" >&2
  exit 1
fi
if [ "$target_db" = "$PG_DB" ]; then
  echo "拒绝恢复到生产数据库 $PG_DB；请指定一个新的临时数据库名" >&2
  exit 2
fi

exists="$(docker exec codelab-pg psql -U "$PG_USER" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='${target_db}'")"
if [ "$exists" = "1" ]; then
  echo "目标数据库 $target_db 已存在；为避免覆盖，恢复已停止" >&2
  exit 1
fi

work_file="$(mktemp "${TMPDIR:-/tmp}/codelab-db-restore.XXXXXX.sql.gz")"
trap 'rm -f "$work_file"' EXIT
python3 scripts/minio_backup.py download --key "$object_key" --file "$work_file"
gzip -t "$work_file"
docker exec codelab-pg createdb -U "$PG_USER" "$target_db"
if ! gzip -dc "$work_file" | docker exec -i codelab-pg psql -v ON_ERROR_STOP=1 -U "$PG_USER" -d "$target_db"; then
  echo "数据库恢复失败；临时数据库 $target_db 保留以便诊断，确认后可手工删除" >&2
  exit 1
fi
echo "Restore complete into new database: $target_db"
