#!/usr/bin/env bash
# W9-fix2（2026-10-11）真世界装置 —— **最终字节轮**（jar md5 ce0bf3ba…；含"向上取整不用 ceilDiv"的防溢出改写）。
# 与 run-tp360.sh 同一台尺/同一 plan/同一 config，只换 tag（tp360fix2b）、store（tp360b）与更紧的 keep。
# 判据：advance 0→360 必须 HTTP 200、无异常、跑到 day 360。
set -u
ROOT=/home/cna/SimulatorMosire
BATCH=$ROOT/.superpowers/sdd/2026-10-11-w9-fix2
JAR=$ROOT/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
RUNNER=/tmp/w6acc/w6run.py
CFG=$ROOT/.superpowers/sdd/2026-10-11-haul-supply-investigation/scripts
mkdir -p "$BATCH/logs" "$BATCH/dumps" /tmp/w9f2/stores

# ★ 比第一轮更紧：踢掉 MERCHANT_CAPACITY_* 一族（本批判据不读它，第一轮 186 万行全是它）。
KEEP='event=(MIGRATION_[A-Z_]*|MODE_MIGRATION[A-Z_]*|MARKET_REPORT|MARKET_FILL|MERCHANT_TRADE_INDUSTRY_CREATED|MERCHANT_HAUL_RUN|ONDEMAND_[A-Z_]*|GUI_REQUEST_FAILED|DAY_START|DAY_END)|\tat |Caused by:|Suppressed:'

echo "=== [$(date +%H:%M:%S)] tp360fix2b world=three-powers port=10991 store=/tmp/w9f2/stores/tp360b ==="
python3 "$RUNNER" --jar "$JAR" --tag tp360fix2b --store /tmp/w9f2/stores/tp360b \
  --gui-port 10991 --mcp-port 10992 --approval-port 10993 \
  --world three-powers --config "$CFG/shell-tp.json" \
  --plan "$BATCH/plan-tp-360.json" --out "$BATCH/logs" --dump-dir "$BATCH/dumps" \
  --log-level DEBUG --trace-level TRACE --social-level INFO --app-level INFO \
  --log-keep "$KEEP"
echo "=== [$(date +%H:%M:%S)] tp360fix2b rc=$? ==="
