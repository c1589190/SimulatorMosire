#!/usr/bin/env bash
# W9-fix2（2026-10-11）真世界装置：three-powers 0→360（W9e 账本 §4 的同一台尺/w6run.py，只换端口与本批目录）。
#
# 判据：POST /api/advance 0→360 必须 HTTP 200、无异常、跑到 day=360（W9e 时在 day=270 抛 IllegalStateException ⇒ 500）。
# 端口：10991/10992/10993（本批专用；W9e 用 10981-10988，两者不重叠）
# store：/tmp/w9f2/stores/tp360（独立 store，从创世新播；§一.11：不拿旧档当证据）
#
# ★ keep 过滤器（比 W9e 更紧）：迁移/跑商/市场轮 + GUI 失败事件与其异常栈（本批 ② 要看的正是栈）。
set -u
ROOT=/home/cna/SimulatorMosire
BATCH=$ROOT/.superpowers/sdd/2026-10-11-w9-fix2
JAR=$ROOT/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
RUNNER=/tmp/w6acc/w6run.py
CFG=$ROOT/.superpowers/sdd/2026-10-11-haul-supply-investigation/scripts
mkdir -p "$BATCH/logs" "$BATCH/dumps" /tmp/w9f2/stores

KEEP='event=(MIGRATION_[A-Z_]*|MODE_MIGRATION[A-Z_]*|MARKET_REPORT|MARKET_FILL|MERCHANT_[A-Z_]*|ONDEMAND_[A-Z_]*|GUI_REQUEST_FAILED|DAY_START|DAY_END)|\tat |Caused by:|Suppressed:|ERROR'

echo "=== [$(date +%H:%M:%S)] tp360 world=three-powers port=10991 store=/tmp/w9f2/stores/tp360 ==="
python3 "$RUNNER" --jar "$JAR" --tag tp360fix2 --store /tmp/w9f2/stores/tp360 \
  --gui-port 10991 --mcp-port 10992 --approval-port 10993 \
  --world three-powers --config "$CFG/shell-tp.json" \
  --plan "$BATCH/plan-tp-360.json" --out "$BATCH/logs" --dump-dir "$BATCH/dumps" \
  --log-level DEBUG --trace-level TRACE --social-level INFO --app-level INFO \
  --log-keep "$KEEP"
echo "=== [$(date +%H:%M:%S)] tp360fix2 rc=$? ==="
