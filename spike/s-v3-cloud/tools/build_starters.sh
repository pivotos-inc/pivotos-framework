#!/usr/bin/env bash
# S133 V3 spike —— Cloud Starter 原型构建脚本
#
# 说明
# ----
# 1. spike 资产 **不进 Maven reactor**（八字还没一撇的东西不污染生产 pom），因此这里不用 mvn：
#    直接以「已构建好的 admin-server fat jar」解压后的 classes + lib 作为编译 classpath。
# 2. 产出 5 个自治 jar（含 AutoConfiguration.imports），运行时通过 classpath 组合决定形态；
#    这本身就是「可裁剪性」的物理证据 —— 形态切换 = classpath 组合切换。
#
# 用法：cd pivotos-framework && bash spike/s-v3-cloud/tools/build_starters.sh
# 注：本机 shell 的 rm 被 IDE shim 接管且不可靠，脚本内一律用 find -delete / 覆盖式写 完成清理。
set -uo pipefail

FW_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SPIKE_DIR="$FW_ROOT/spike/s-v3-cloud"
APP_JAR="$FW_ROOT/pivotos-admin-server/target/pivotos-admin-server.jar"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home}"

WORK="${SPIKE_DIR}/build"
OUT="${WORK}/jars"
EXPLODED="${WORK}/exploded-app"
MODULES=(cloud-openfeign cloud-nacos cloud-gateway cloud-sentinel cloud-seata)

if [ ! -f "$APP_JAR" ]; then
  echo "[FATAL] 未找到 $APP_JAR，请先执行 mvn clean package -DskipTests" >&2
  exit 1
fi

mkdir -p "$OUT" "$WORK"

echo "[1/3] 解压 fat jar 构建 classpath（幂等：已解压则复用）"
if [ ! -d "$EXPLODED/BOOT-INF" ]; then
  mkdir -p "$EXPLODED"
  unzip -qo "$APP_JAR" -d "$EXPLODED"
fi
CP="$EXPLODED/BOOT-INF/classes:$EXPLODED/BOOT-INF/lib/*"

echo "[2/3] 逐个模块编译并按模块产出 jar（编译顺序即依赖顺序：openfeign 优先）"
EXTRA=""
for m in "${MODULES[@]}"; do
  classes="$WORK/classes/$m"
  mkdir -p "$classes"
  # javac 的 @argfile 要求「含空格的路径必须带引号」，故统一加双引号
  find "$SPIKE_DIR/starters/$m/src/main/java" -name "*.java" | sed 's/.*/"&"/' > "$WORK/sources-$m.txt"
  "$JAVA_HOME/bin/javac" -nowarn -encoding UTF-8 -cp "$CP$EXTRA" -d "$classes" @"$WORK/sources-$m.txt"
  if [ -d "$SPIKE_DIR/starters/$m/src/main/resources" ]; then
    cp -R "$SPIKE_DIR/starters/$m/src/main/resources/." "$classes/"
  fi
  (cd "$classes" && "$JAVA_HOME/bin/jar" cf "$OUT/pivotos-starter-$m.jar" .)
  EXTRA="$EXTRA:$OUT/pivotos-starter-$m.jar"
  echo "        built pivotos-starter-$m.jar"
done

echo "[3/3] 校验每个 jar 都登记了 AutoConfiguration.imports"
for m in "${MODULES[@]}"; do
  entry=$(unzip -p "$OUT/pivotos-starter-$m.jar" META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)
  echo "        $m -> $entry"
done

ls -l "$OUT"
echo "[DONE] 五个 Starter 已产出：$OUT"
