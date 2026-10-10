#!/usr/bin/env bash
# W1f 探针（**只读**，不是测试、不进 Maven 门禁）：实测"进快照的类型"的 Jackson 线格式属性集，
# 并跑 MarketTable 的裸 mapper 往返 + EconomyCodec 端到端快照往返。
#
# 前置：先跑允许的那条编译命令，让各模块 target/classes 是新的：
#   tools/mvn-lock.sh -DskipTests compile -am
# 用法：
#   bash .superpowers/sdd/2026-10-10-w1-market-zone-price-and-stats/w1f-probe/run-probe.sh
#
# 判据（修好之后应当满足）：
#   - W1f 责任区 SUMMARY：hazards=0  props≠components=0
#   - PART B：含 "empty" 属性 = false；读回成功；逐值相等 = true；旧字节（含 "empty"）读回 = 成功；
#             其它未知字段仍 fail-closed = true
#   - PART C：EconomyCodec 编解码成功；三个"逐值相等 = true"
#   - 责任区外观测：hazards=0（两个 props≠components 是 @JsonProperty 改名的组件，读侧实测接受）
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../../.." && pwd)"
M2="${M2:-$HOME/.m2/repository}"
JACKSON=2.22.2
SLF4J="$(find "$M2/org/slf4j/slf4j-api" -name 'slf4j-api-2*.jar' | sort -V | tail -1)"

CP="$(ls -d "$ROOT"/simos-*/target/classes | tr '\n' ':')"
CP+="$M2/com/fasterxml/jackson/core/jackson-databind/$JACKSON/jackson-databind-$JACKSON.jar:"
CP+="$M2/com/fasterxml/jackson/core/jackson-core/$JACKSON/jackson-core-$JACKSON.jar:"
CP+="$M2/com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22.jar:"
CP+="$M2/com/fasterxml/jackson/datatype/jackson-datatype-jdk8/$JACKSON/jackson-datatype-jdk8-$JACKSON.jar:"
CP+="$SLF4J"

OUT="$(mktemp -d)"
javac -nowarn -cp "$CP" -d "$OUT/classes" "$HERE/W1fProbe.java"
java -cp "$OUT/classes:$CP" W1fProbe
