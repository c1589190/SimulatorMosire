#!/usr/bin/env bash
# W6 标定读数批（2026-10-11）：真世界读数装置。
#
# 与 W6 总验收 / W7e / W9d / W9e / W9f **同一台尺**（本目录 scripts/w6run.py = /tmp/w6acc/w6run.py
# 的逐字副本，只改 OUT/DUMPS/cwd 三处落点）。
#
# 世界：small-world 0→600 与 three-powers 0→600（**均从创世新播**，AGENTS §一.11：不拿旧档当证据）；
#       另 0→360 one-shot 各 3 样本（性能：判"W9f 报 sw 10.650s vs W9e 8.226s"是否真变慢）。
# 端口：10901-10906（109xx，本批专用；不与 103xx / 10961-10998 / 5711 重叠）。
# store：本批 store/<tag>（★ 落 SDD 目录，不放 /tmp）；日志 / dump / 逐日读数同样落本批目录。
#
# ★ 级别：--log-level DEBUG（派单要求）+ --trace-level TRACE（MIGRATION_MOVE / MONEY_MARKET_LOAN
#   两条逐笔读数只在 TRACE 分类下），与 W9f 逐字同口径（其 sw360 = DEBUG+TRACE ⇒ 性能可比）。
# ★ --log-keep：排除已知洪泛事件（CAPACITY_DEMAND_INSTANCE / HOUSEHOLD_GAP / MARKET_FILL 之外的逐户
#   成交行不排——MARKET_FILL 保留，用来核 Σfills == MARKET_FILL 行数）。
#
# 用法：bash run-worlds.sh <mode>   mode = long | perf | all
set -u
ROOT=/home/cna/SimulatorMosire
BATCH=$ROOT/.superpowers/sdd/2026-10-11-w6-calibration
JAR=$ROOT/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
RUNNER=$BATCH/scripts/w6run.py
CFG=$BATCH/scripts
OUT=$BATCH/logs
DUMPS=$BATCH/dumps
STORES=$BATCH/store
mkdir -p "$OUT" "$DUMPS" "$STORES"

KEEP='event=(MIGRATION_[A-Z_]*|MODE_MIGRATION[A-Z_]*|MARKET_REPORT|MARKET_FILL|MARKET_ROUND_START|MARKET_UNFILLED|MINT_[A-Z_]*|MONEY_MARKET[A-Z_]*|CLASS_[A-Z_]*|SLIDE_[A-Z_]*|STRATUM[A-Z_]*|FAMINE_[A-Z_]*|SEED_MINT[A-Z_]*|INDUSTRY_[A-Z_]*|DEBT_[A-Z_]*|HAUL_[A-Z_]*|LOGISTICS_[A-Z_]*|MERCHANT_[A-Z_]*|ONDEMAND_[A-Z_]*|PROFIT_MIGRATION_PLAN|DAY_START|DAY_END|GUI_REQUEST_FAILED)|\tat |Caused by:|Suppressed:'

run() { # run <tag> <world> <port> <plan> <store-name> <daily-out|-> <extras...>
  local tag=$1 world=$2 port=$3 plan=$4 store=$5 daily=$6; shift 6
  local dailyarg=()
  [ "$daily" != "-" ] && dailyarg=(--daily-out "$BATCH/daily/$daily")
  echo "=== [$(date +%H:%M:%S)] $tag world=$world port=$port plan=$plan store=$store daily=$daily extras=$* ==="
  python3 "$RUNNER" --jar "$JAR" --tag "$tag" --store "$STORES/$store" \
    --gui-port "$port" --mcp-port "$((port+1))" --approval-port "$((port+2))" \
    --world "$world" --config "$CFG/shell-$([ "$world" = small-world ] && echo sw || echo tp).json" \
    --plan "$BATCH/plans/$plan" --out "$OUT" --dump-dir "$DUMPS" \
    --log-level DEBUG --trace-level TRACE --social-level DEBUG --social-trace-level INFO \
    --app-level DEBUG --log-keep "$KEEP" "${dailyarg[@]}" "$@"
  echo "=== [$(date +%H:%M:%S)] $tag rc=$? ==="
}

mode="${1:-all}"
want() { [ "$mode" = "all" ] || [ "$mode" = "$1" ]; }

# ── ① 长跑：两世界 0→600，逐日读数（--prune：只留最新 checkpoint，省盘）──────────────────
if want long; then
  run sw600 small-world 10901 plan-sw600.json sw600 daily-sw600.jsonl --prune
  run tp600 three-powers 10902 plan-tp600.json tp600 daily-tp600.jsonl --prune
fi

# ── ② 性能：0→360 one-shot × 3 样本 / 世界（DEBUG+TRACE，与 W9f 同口径；各自独立 store）────
if want perf; then
  for i in 1 2 3; do
    run sw360perf$i small-world 10905 plan-sw360.json sw360perf$i -
    run tp360perf$i three-powers 10908 plan-tp360.json tp360perf$i -
  done
fi
echo "=== [$(date +%H:%M:%S)] MODE=$mode DONE ==="
