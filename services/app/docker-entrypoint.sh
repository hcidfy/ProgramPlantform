#!/bin/sh
# 容器入口（PRD §7.4 判题最低加固的第 ②③ 步）：
# 具名卷挂到 /data/judge 后属主是 root，判题目录必须交给低权限用户 judge，
# 否则学生代码以 judge 身份跑起来后无法在自己目录里写文件。
set -e

mkdir -p /data/judge /app/logs

if id judge >/dev/null 2>&1; then
  chown -R judge:judge /data/judge 2>/dev/null || true
else
  echo "[entrypoint] 警告：镜像里没有 judge 用户，判题将直跑（仅限开发机）" >&2
fi

# 历史判题目录可能有残留属主，统一交回 judge
find /data/judge -maxdepth 1 -type d ! -user judge -exec chown judge:judge {} + 2>/dev/null || true

exec java $JAVA_TOOL_OPTIONS -jar /app/app.jar "$@"
