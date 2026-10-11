#!/usr/bin/env bash
# PivotOS JVM 参数模板（V3.1-G · P1-B3 进程托管）
#
# 用法（**source**，不是执行）：
#     source scripts/ops/jvm-opts.sh
#     pivotos_build_jvm_opts "$LOG_DIR"
#     echo "$JVM_OPTS"
#
# 为什么要单独抽一份文件：
#   systemd 的 EnvironmentFile 只认 KEY=VALUE，**不能 source 脚本**，所以 unit 的
#   ExecStart 指向 run.sh，由 run.sh source 本文件再 exec java；start-prod.sh
#   （裸机 / 1Panel 场景）source 同一份。参数来源收敛到一处，避免两处漂移。
#
# ⚠️ 本项目两条硬要求（出处见《项目记忆 §3》）：
#   1. -Djava.awt.headless=true：不带头盔，Tika/OCR/上传会报 1500
#   2. -Djna.library.path：JNA 找不到本地库同样走不通 OCR

# 拼装 JVM 参数，结果写入全局变量 JVM_OPTS。
# 所有开关都由环境变量驱动，**本文件里不出现任何真实口令**。
pivotos_build_jvm_opts() {
    local log_dir="${1:?用法：pivotos_build_jvm_opts <log_dir>}"

    # ① headless：本项目的硬要求，缺少即 OCR/上传 1500
    JVM_OPTS="-Djava.awt.headless=true"

    # ② 事后复盘三件套：OOM 堆转储 + GC 日志滚动。
    #    没有这两个，线上 OOM / GC 停顿只能靠猜。
    JVM_OPTS="$JVM_OPTS -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=$log_dir"
    JVM_OPTS="$JVM_OPTS -Xlog:gc*:file=$log_dir/gc.log:time,uptime:filecount=10,filesize=50m"

    # ③ 堆：默认**不设** -Xms/-Xmx（等同系统默认），与既有行为保持一致；
    #    生产建议显式注入 JAVA_XMS / JAVA_XMX，否则容器 OOMKill 时机不可控。
    if [ -n "${JAVA_XMS:-}" ]; then
        JVM_OPTS="$JVM_OPTS -Xms${JAVA_XMS}"
    fi
    if [ -n "${JAVA_XMX:-}" ]; then
        JVM_OPTS="$JVM_OPTS -Xmx${JAVA_XMX}"
    fi
    # 容器友好：按容器内存上限取百分比（与 -Xmx 二选一，别同时给）
    if [ -n "${JAVA_MAX_RAM_PERCENTAGE:-}" ]; then
        JVM_OPTS="$JVM_OPTS -XX:MaxRAMPercentage=${JAVA_MAX_RAM_PERCENTAGE}"
    fi

    # ④ OOM 后是否直接退出：配了 Restart=on-failure 时更希望它退出再被拉起，
    #    而不是留在「活着但不可用」的状态。默认不开，避免改变既有行为。
    if [ "${JAVA_EXIT_ON_OOM:-false}" = "true" ]; then
        JVM_OPTS="$JVM_OPTS -XX:+ExitOnOutOfMemoryError"
    fi

    # ⑤ JNA 本地库路径（Linux 常见 /usr/local/lib；macOS 开发机是 /opt/homebrew/lib）
    JVM_OPTS="$JVM_OPTS -Djna.library.path=${JNA_LIBRARY_PATH:-/usr/local/lib}"

    # ⑥ 运维逃生口：部署方想临时加参数就走 JAVA_OPTS，最后追加、可覆盖前面同名项
    if [ -n "${JAVA_OPTS:-}" ]; then
        JVM_OPTS="$JVM_OPTS $JAVA_OPTS"
    fi

    export JVM_OPTS
    return 0
}
