#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] || { echo ".env 不存在" >&2; exit 1; }
command -v docker >/dev/null || { echo "docker 命令不存在" >&2; exit 1; }
command -v gzip >/dev/null || { echo "gzip 命令不存在" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 命令不存在" >&2; exit 1; }

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

work_file="$(mktemp "${TMPDIR:-/tmp}/codelab-db-backup.XXXXXX.sql.gz")"
trap 'rm -f "$work_file"' EXIT
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
object_key="backups/codelab-${stamp}.sql.gz"

echo "Creating PostgreSQL dump..."
docker exec codelab-pg pg_dump -U "$PG_USER" -d "$PG_DB" --no-owner --no-privileges | gzip -c > "$work_file"
python3 scripts/minio_backup.py upload --key "$object_key" --file "$work_file"
python3 scripts/minio_backup.py prune --days 7
echo "Backup complete: s3://${MINIO_BUCKET}/${object_key}"
