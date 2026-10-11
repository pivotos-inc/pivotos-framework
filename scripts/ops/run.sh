#!/usr/bin/env bash
# PivotOS 前台运行包装（V3.1-G · P1-B3 进程托管）
#
# 给 systemd 用：unit 的 ExecStart 指向本脚本。
# 为什么不让 unit 直接 ExecStart=java：
#   systemd 的 EnvironmentFile 只认 KEY=VALUE，没法做「按环境拼参数」这类逻辑；
#   而 headless / OOM 转储 / GC 日志 / JNA 路径这些是**必带项**，散在 unit 里迟早漏。
#   统一由 run.sh source jvm-opts.sh 后 exec java：
#     · exec 替换进程 → java 就是 unit 的主进程，systemd 的信号与退出码语义完全正确
#     · 不留中间 shell 进程，Restart / TimeoutStopSec 判定干净
#
# 裸机 / 1Panel 场景不要用本脚本，用 start-prod.sh（nohup 后台 + PID 文件 + 就绪轮询）。
#
# 用法：把 scripts/ops/ 整个目录拷到目标机 $APP_HOME 下，再：
#     APP_HOME=/opt/pivotos PIVOTOS_PROFILE=prod /opt/pivotos/run.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="${APP_HOME:-$(dirname "$SCRIPT_DIR")}"
JAR_NAME="${JAR_NAME:-pivotos-admin-server.jar}"
JAR="$APP_HOME/$JAR_NAME"
LOG_DIR="${PIVOTOS_LOG_DIR:-$APP_HOME/logs}"
SERVER_PORT="${SERVER_PORT:-8080}"
PROFILE="${PIVOTOS_PROFILE:-prod}"

if [ ! -f "$JAR" ]; then
    echo "❌ 找不到 jar：${JAR}（用 JAR_NAME 指定文件名，或检查 APP_HOME）" >&2
    exit 1
fi

mkdir -p "$LOG_DIR"

# shellcheck source=/dev/null
source "$SCRIPT_DIR/jvm-opts.sh"
pivotos_build_jvm_opts "$LOG_DIR"

echo "[run] APP_HOME=$APP_HOME  JAR=$JAR  PORT=$SERVER_PORT  PROFILE=$PROFILE  LOG_DIR=$LOG_DIR"
echo "[run] JVM_OPTS=$JVM_OPTS"

cd "$APP_HOME"

# shellcheck disable=SC2086  # JVM_OPTS 必须分词展开
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" \
    $JVM_OPTS \
    -jar "$JAR" \
    --spring.profiles.active="$PROFILE" \
    --server.port="$SERVER_PORT"
