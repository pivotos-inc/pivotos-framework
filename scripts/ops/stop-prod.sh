#!/usr/bin/env bash
# PivotOS 生产环境停止脚本（随代码库版本化 · v3.1.0 生产化 A3）
#
# 口径：先 SIGTERM 让 Spring 走一遍停机流程（默认 immediate；若在 yml 配了
# server.shutdown=graceful 则会等宽限期），超时 SIGKILL。**不搞 kill -9 一把梭**——
# 那样会丢掉在途请求与「停机期间正在写的」数据（定时任务、AI 流式输出尤其明显）。

set -euo pipefail

APP_HOME="${APP_HOME:-/opt/pivotos}"
LOG_DIR="${PIVOTOS_LOG_DIR:-$APP_HOME/logs}"
PID_FILE="$LOG_DIR/admin-server.pid"
TERM_TIMEOUT_SECONDS="${TERM_TIMEOUT_SECONDS:-30}"

if [ ! -f "$PID_FILE" ]; then
  echo "ℹ️  没有 PID 文件（$PID_FILE），进程可能未由 start-prod.sh 启动"
  exit 0
fi

PID="$(cat "$PID_FILE")"
if ! kill -0 "$PID" 2>/dev/null; then
  echo "ℹ️  PID=$PID 已不存在，清理 PID 文件"
  rm -f "$PID_FILE"
  exit 0
fi

echo "停止 PivotOS（PID=$PID）..."
kill -TERM "$PID" || true

DEADLINE=$(( $(date +%s) + TERM_TIMEOUT_SECONDS ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  if ! kill -0 "$PID" 2>/dev/null; then
    rm -f "$PID_FILE"
    echo "✅ 已正常停止（TERM 内退出）"
    exit 0
  fi
  sleep 1
done

echo "⚠️  ${TERM_TIMEOUT_SECONDS}s 内未退出，转 SIGKILL" >&2
kill -KILL "$PID" || true
sleep 2
rm -f "$PID_FILE"
echo "已强制停止（kill -9）"
