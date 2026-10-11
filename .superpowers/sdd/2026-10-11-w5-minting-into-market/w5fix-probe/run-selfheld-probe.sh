#!/usr/bin/env bash
# W5-fix 运行时探针（**只读**，不进仓、不进 Maven 门禁）：BLOCKED-2 的"播种形状（国库钱不冻结）"真日循环。
#
# 前置：各模块 target/classes 是新的（tools/mvn-lock.sh -q -DskipTests compile -am），
#       且 simos-economy/target/test-classes 里有 W5b 编译出的 TestLogCapture / TestSlf4jProvider
#       （它提供 SLF4J provider，用来采集具名 DEBUG 行；探针本身不改任何 src/test 文件）。
# 用法：bash /tmp/w5fix-probe/run-selfheld-probe.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="${ROOT:-/home/cna/SimulatorMosire}"
M2="${M2:-$HOME/.m2/repository}"
JACKSON=2.22.2
SLF4J="$(find "$M2/org/slf4j/slf4j-api" -name 'slf4j-api-2*.jar' ! -name '*-sources.jar' | sort -V | tail -1)"

# ★ 探针自己声明的包与 main 的 time 包同名（要用包私有的 EconomyDayStepper 等）⇒ 必须把探针类放最前，
#   再是 economy test-classes（TestLogCapture provider）与各模块 main classes。
CP="$ROOT/simos-economy/target/test-classes:"
CP+="$(ls -d "$ROOT"/simos-*/target/classes | tr '\n' ':')"
CP+="$M2/com/fasterxml/jackson/core/jackson-databind/$JACKSON/jackson-databind-$JACKSON.jar:"
CP+="$M2/com/fasterxml/jackson/core/jackson-core/$JACKSON/jackson-core-$JACKSON.jar:"
CP+="$M2/com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22.jar:"
CP+="$M2/com/fasterxml/jackson/datatype/jackson-datatype-jdk8/$JACKSON/jackson-datatype-jdk8-$JACKSON.jar:"
CP+="$SLF4J"

OUT="$(mktemp -d)"
javac -nowarn -cp "$CP" -d "$OUT/classes" "$HERE/W5FixSelfHeldProbe.java"
java -cp "$OUT/classes:$CP" io.mosire.simos.economy.time.W5FixSelfHeldProbe
