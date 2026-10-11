#!/usr/bin/env bash
# PivotOS 生产环境启动模板（随代码库版本化 · v3.1.0 生产化 A3）
#
# 用法：把本文件拷到目标机 $APP_HOME 下即可使用，**不要在里面写任何口令**。
#   必需环境变量（缺一即拒绝启动，避免「连错环境还跑起来」）：
#     PIVOTOS_MYSQL_URL / PIVOTOS_MYSQL_USERNAME / PIVOTOS_MYSQL_PASSWORD
#     PIVOTOS_REDIS_HOST / PIVOTOS_REDIS_PASSWORD
#     PIVOTOS_FIELD_ENCRYPT_KEY（16/24/32 位；不注入会用 git 里明文的开发默认密钥，prod 侧亦会拒启动）
#   建议环境变量：PIVOTOS_LOG_DIR
#                 JAVA_XMS / JAVA_XMX（未设置则不限制堆，等同容器/系统默认）
#
# 与部署机上上一个版本的 start-prod.sh 相比，改了三件事：
#   1. 日志不再「nohup > 单文件」——应用日志由 logback-spring.xml 按 100MB/30 天/5GB 滚动，
#      这里只留 JVM 层面的 stdout/stderr（启动崩溃栈、JMX、中间件告警），每次启动归档一份、
#      并清理 30 天以前的归档；
#   2. 去掉了脚本里的任何真实密钥默认值（密钥一旦写进来就会随脚本被拷到各环境）；
#   3. 启动后轮询新的只读探活端点 /health/live 与 /health/ready 做就绪确认，
#      超时不再被误判为「起来了」——注意：除这两个探针外，其余端点仍可能「HTTP 200 但业务码非 0」。

set -euo pipefail

APP_HOME="${APP_HOME:-/opt/pivotos}"
JAR_NAME="${JAR_NAME:-pivotos-admin-server.jar}"
JAR="$APP_HOME/$JAR_NAME"
LOG_DIR="${PIVOTOS_LOG_DIR:-$APP_HOME/logs}"
CONSOLE_LOG="$LOG_DIR/console.log"
PID_FILE="$LOG_DIR/admin-server.pid"
SERVER_PORT="${SERVER_PORT:-8080}"
START_TIMEOUT_SECONDS="${START_TIMEOUT_SECONDS:-180}"

if [ ! -f "$JAR" ]; then
  echo "❌ 找不到 jar：$JAR（用 JAR_NAME 指定文件名，或检查 APP_HOME）" >&2
  exit 1
fi

# 凭据必须外置：这里是「拒绝启动」而不是「用默认值凑合」——凑合的默认值会把生产指向开发库。
# PIVOTOS_FIELD_ENCRYPT_KEY 同列必需（V3.1-D C3-1）：不注入就会用到 git 里明文的开发默认密钥，
# 字段加密形同虚设；应用侧自 V3.1-D 起也会在 prod 形态直接拒绝启动，这里提前拦，错误信息更直白。
REQUIRED_VARS=(PIVOTOS_MYSQL_URL PIVOTOS_MYSQL_USERNAME PIVOTOS_MYSQL_PASSWORD PIVOTOS_REDIS_HOST PIVOTOS_REDIS_PASSWORD PIVOTOS_FIELD_ENCRYPT_KEY)
MISSING=()
for v in "${REQUIRED_VARS[@]}"; do
  if [ -z "${!v:-}" ]; then
    MISSING+=("$v")
  fi
done
if [ ${#MISSING[@]} -gt 0 ]; then
  echo "❌ 缺少必需环境变量：${MISSING[*]}" >&2
  echo "   凭据一律由部署环境注入，禁止写进脚本或 yml。" >&2
  exit 1
fi

# 长度由应用侧 FieldEncryptCrypto 强校验（16/24/32 位）；这里只做非空与长度的提前自检，
# 免得起服到一半才抛 IllegalStateException。
if [ -n "${PIVOTOS_FIELD_ENCRYPT_KEY:-}" ]; then
  KEY_LEN=${#PIVOTOS_FIELD_ENCRYPT_KEY}
  if [ "$KEY_LEN" -ne 16 ] && [ "$KEY_LEN" -ne 24 ] && [ "$KEY_LEN" -ne 32 ]; then
    echo "❌ PIVOTOS_FIELD_ENCRYPT_KEY 长度必须为 16/24/32 位（当前 $KEY_LEN 位）" >&2
    exit 1
  fi
fi

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "❌ 已在运行（PID=$(cat "$PID_FILE")），先执行 stop-prod.sh" >&2
  exit 1
fi

mkdir -p "$LOG_DIR"

# 归档上一次的控制台输出，并清理过期归档
if [ -f "$CONSOLE_LOG" ]; then
  mv "$CONSOLE_LOG" "$LOG_DIR/console-$(date +%Y%m%d-%H%M%S).log"
fi
find "$LOG_DIR" -maxdepth 1 -name 'console-*.log' -mtime +30 -delete 2>/dev/null || true

# JVM 参数：headless 是本项目的硬要求（Tika/OCR 否则报 1500，出处见《项目记忆 §3》）；
# OOM 转储与 GC 日志定向到 $LOG_DIR（缺 -XX 参数就无法事后复盘，评估 O7）。
HEAP_OPTS=""
[ -n "${JAVA_XMS:-}" ] && HEAP_OPTS="$HEAP_OPTS -Xms${JAVA_XMS}"
[ -n "${JAVA_XMX:-}" ] && HEAP_OPTS="$HEAP_OPTS -Xmx${JAVA_XMX}"
JVM_BASE_OPTS="-Djava.awt.headless=true \
 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=$LOG_DIR \
 -Xlog:gc*:file=$LOG_DIR/gc.log:time,uptime:filecount=10,filesize=50m"
# shellcheck disable=SC2086
JAVA_RUN="${JAVA_HOME:+$JAVA_HOME/bin/}java"

echo "启动 PivotOS（prod）..."
echo "  JAR     : $JAR"
echo "  PORT    : $SERVER_PORT"
echo "  APP LOG : $LOG_DIR/$(basename "$JAR" .jar).log"
echo "  CONSOLE : $CONSOLE_LOG"

cd "$APP_HOME"
nohup $JAVA_RUN \
  $JVM_BASE_OPTS $HEAP_OPTS ${JAVA_OPTS:-} \
  -Djna.library.path="${JNA_LIBRARY_PATH:-/usr/local/lib}" \
  -jar "$JAR" \
  --spring.profiles.active=prod \
  --server.port="$SERVER_PORT" \
  >> "$CONSOLE_LOG" 2>&1 &

PID=$!
echo "$PID" > "$PID_FILE"
echo "已提交启动，PID=$PID"

# ===== 就绪确认：轮询只读探活端点（超时即判定失败，退出码非 0 交给部署编排处理）=====
probe() {
  curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$SERVER_PORT$1" 2>/dev/null || echo "000"
}

echo "等待就绪（最多 ${START_TIMEOUT_SECONDS}s）..."
DEADLINE=$(( $(date +%s) + START_TIMEOUT_SECONDS ))
READY=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  sleep 3
  if [ "$(probe /health/live)" = "200" ]; then
    READY_CODE="$(probe /health/ready)"
    if [ "$READY_CODE" = "200" ]; then
      READY="ok"
      break
    fi
    if [ "$READY_CODE" = "503" ]; then
      READY="down"
      break
    fi
  fi
done

case "$READY" in
  ok)
    echo "✅ 已就绪：/health/live=200 /health/ready=200"
    ;;
  down)
    echo "❌ 进程已响应但依赖未就绪：/health/ready=503（关键依赖不可用）" >&2
    echo "   详情（需登录）：curl -H 'Authorization: <token>' http://127.0.0.1:$SERVER_PORT/health/ready" >&2
    tail -n 50 "$CONSOLE_LOG" >&2 || true
    exit 1
    ;;
  *)
    echo "❌ 启动超时：${START_TIMEOUT_SECONDS}s 内 /health/live 未返回 200" >&2
    tail -n 100 "$CONSOLE_LOG" >&2 || true
    exit 1
    ;;
esac

echo "查看应用日志：tail -f $LOG_DIR/$(basename "$JAR" .jar).log"
