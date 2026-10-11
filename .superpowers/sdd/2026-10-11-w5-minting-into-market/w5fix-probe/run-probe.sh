#!/usr/bin/env bash
# W5-fix 探针（**只读**，不进仓、不进 Maven 门禁）：实测"进快照/变更集的类型"的 Jackson 线格式属性集，
# 并跑 MoneyOutput 裸往返 + EconomyCodec 端到端快照往返（含 Industry.moneyOutputPerUnit）。
#
# 前置：先跑允许的那条编译命令，让各模块 target/classes 是新的：
#   tools/mvn-lock.sh -q -DskipTests compile -am
# 用法：
#   bash /tmp/w5fix-probe/run-probe.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="${ROOT:-/home/cna/SimulatorMosire}"
M2="${M2:-$HOME/.m2/repository}"
JACKSON=2.22.2
SLF4J="$(find "$M2/org/slf4j/slf4j-api" -name 'slf4j-api-2*.jar' ! -name '*-sources.jar' | sort -V | tail -1)"

CP="$(ls -d "$ROOT"/simos-*/target/classes | tr '\n' ':')"
CP+="$M2/com/fasterxml/jackson/core/jackson-databind/$JACKSON/jackson-databind-$JACKSON.jar:"
CP+="$M2/com/fasterxml/jackson/core/jackson-core/$JACKSON/jackson-core-$JACKSON.jar:"
CP+="$M2/com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22.jar:"
CP+="$M2/com/fasterxml/jackson/datatype/jackson-datatype-jdk8/$JACKSON/jackson-datatype-jdk8-$JACKSON.jar:"
CP+="$SLF4J"

OUT="$(mktemp -d)"
javac -nowarn -cp "$CP" -d "$OUT/classes" "$HERE/W5FixProbe.java"
java -cp "$OUT/classes:$CP" W5FixProbe
