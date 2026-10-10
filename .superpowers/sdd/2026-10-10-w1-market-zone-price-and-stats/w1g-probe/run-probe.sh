#!/usr/bin/env bash
# W1g 只读探针：用**真 handler** 跑夹具改过的 economy 载荷（不跑 JUnit / Maven 门禁 / 不写仓库）。
# ★ classpath 必须排除 ~/.m2 里的 io/mosire/*.jar —— 那些是**旧安装**（实测：EconomyTransferAssetShareHandler
#   这个类在当前源码里已不存在，却还在 jar 里 ⇒ 混进 classpath 会让探针跑到旧字节上，结果是假证据）。
set -euo pipefail
cd "$(dirname "$0")/../../../.."   # 回仓根
REPO=$(pwd)
CP="$REPO/simos-app/target/test-classes:$(ls -d "$REPO"/*/target/classes | tr '\n' ':')$(find ~/.m2/repository -name '*.jar' | grep -v '/io/mosire/' | tr '\n' ':')"
OUT=$(mktemp -d)
javac -nowarn -cp "$CP" -d "$OUT" "$REPO/.superpowers/sdd/2026-10-10-w1-market-zone-price-and-stats/w1g-probe/W1gProbe.java" \
                                  "$REPO/.superpowers/sdd/2026-10-10-w1-market-zone-price-and-stats/w1g-probe/W1gChain2.java"
java -cp "$OUT:$CP" io.mosire.simos.economy.spi.W1gProbe
java -cp "$OUT:$CP" io.mosire.simos.economy.spi.W1gChain2 "$REPO/simos-economy/target/classes/io/mosire/simos/economy/spi"
