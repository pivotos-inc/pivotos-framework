#!/usr/bin/env bash
# PivotOS 生产环境启动模板（随代码库版本化 · v3.1.0 生产化 A3，V3.1-G B3 改造）
#
# 用法：把 scripts/ops/ 整个目录拷到目标机 $APP_HOME 下即可使用，**不要在里面写任何口令**。
#     APP_HOME=/opt/pivotos PIVOTOS_PROFILE=prod /opt/pivotos/start-prod.sh
#     /opt/pivotos/start-prod.sh --skip-preflight     # 跳过前置检查（后果自负）
#     /opt/pivotos/start-prod.sh --port=8081
#
# 必需环境变量（缺一即拒绝启动，避免「连错环境还跑起来」）：
#     PIVOTOS_MYSQL_URL / PIVOTOS_MYSQL_USERNAME / PIVOTOS_MYSQL_PASSWORD
#     PIVOTOS_REDIS_HOST / PIVOTOS_REDIS_PASSWORD
#     PIVOTOS_FIELD_ENCRYPT_KEY（16/24/32 位；不注入会用 git 里明文的开发默认密钥，prod 侧亦会拒启动）
# 建议环境变量：PIVOTOS_LOG_DIR、JAVA_XMS / JAVA_XMX（未设置则不限制堆）
#
# 与部署机上上一个版本的 start-prod.sh 相比，改了四件事：
#   1. 日志不再「nohup > 单文件」——应用日志由 logback-spring.xml 按 100MB/30 天/5GB 滚动，
#      这里只留 JVM 层面的 stdout/stderr（启动崩溃栈、JMX、中间件告警），每次启动归档一份、
#      并清理 30 天以前的归档；
#   2. 去掉了脚本里的任何真实密钥默认值（密钥一旦写进来就会随脚本被拷到各环境）；
#   3. 启动后轮询新的只读探活端点 /health/live 与 /health/ready 做就绪确认，
#      超时不再被误判为「起来了」——注意：除这两个探针外，其余端点仍可能「HTTP 200 但业务码非 0」；
#   4. （V3.1-G）JVM 参数下沉到 jvm-opts.sh（与 systemd 场景共用同一份），
#      并新增 preflight.py 前置检查：端口占用 / 必需环境变量 / jar / JDK 版本 / 数据源连通。
#
# ⚠️ 裸机场景用本脚本；如果用 systemd，请用 unit 的 ExecStart=/opt/pivotos/run.sh
#    （前台 exec java，systemd 的信号与退出码语义才正确）。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---- 参数 ----
SKIP_PREFLIGHT=0
PORT_ARG=""
for arg in "$@"; do
    case "$arg" in
        --skip-preflight) SKIP_PREFLIGHT=1 ;;
        --port=*)         PORT_ARG="${arg#--port=}" ;;
        -h|--help)        sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "❌ 未知参数：$arg" >&2; exit 2 ;;
    esac
done

APP_HOME="${APP_HOME:-/opt/pivotos}"
JAR_NAME="${JAR_NAME:-pivotos-admin-server.jar}"
JAR="$APP_HOME/$JAR_NAME"
LOG_DIR="${PIVOTOS_LOG_DIR:-$APP_HOME/logs}"
CONSOLE_LOG="$LOG_DIR/console.log"
PID_FILE="$LOG_DIR/admin-server.pid"
SERVER_PORT="${PORT_ARG:-${SERVER_PORT:-8080}}"
START_TIMEOUT_SECONDS="${START_TIMEOUT_SECONDS:-180}"
PROFILE="${PIVOTOS_PROFILE:-prod}"

# ---- 前置检查（V3.1-G · B3）----
# 把「启动后才发现」的问题提前到 JVM 起来之前，且一次性报全。
# 数据源连通默认只告警不阻断（网络抖动时不该让整台机器起不来），要硬阻断加 --strict。
if [ "$SKIP_PREFLIGHT" -eq 0 ]; then
    if ! command -v python3 >/dev/null 2>&1; then
        echo "⚠️  未找到 python3，跳过前置检查（如需检查请安装 python3，或用 --skip-preflight 静默）" >&2
    else
        python3 "$SCRIPT_DIR/preflight.py" \
            --profile="$PROFILE" \
            --port="$SERVER_PORT" \
            --jar="$JAR" \
            --log-dir="$LOG_DIR" || {
            echo "❌ 前置检查未通过，已中止启动（确认风险后可加 --skip-preflight）" >&2
            exit 1
        }
    fi
fi

# 凭据必须外置：这里是「拒绝启动」而不是「用默认值凑合」——凑合的默认值会把生产指向开发库。
if [ "$PROFILE" = "prod" ]; then
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

# JVM 参数统一由 jvm-opts.sh 拼装（与 systemd 场景共用同一份，见该文件头部说明）。
# 必带项：-Djava.awt.headless=true（否则 OCR/上传报 1500）、OOM 堆转储、GC 日志、JNA 路径。
# shellcheck source=/dev/null
source "$SCRIPT_DIR/jvm-opts.sh"
pivotos_build_jvm_opts "$LOG_DIR"

JAVA_RUN="${JAVA_HOME:+$JAVA_HOME/bin/}java"

echo "启动 PivotOS（${PROFILE}）..."
echo "  JAR     : $JAR"
echo "  PORT    : $SERVER_PORT"
echo "  APP LOG : $LOG_DIR/$(basename "$JAR" .jar).log"
echo "  CONSOLE : $CONSOLE_LOG"
echo "  JVM     : $JVM_OPTS"

cd "$APP_HOME"

# shellcheck disable=SC2086  # JVM_OPTS 必须分词展开
nohup $JAVA_RUN \
    $JVM_OPTS \
    -jar "$JAR" \
    --spring.profiles.active="$PROFILE" \
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
