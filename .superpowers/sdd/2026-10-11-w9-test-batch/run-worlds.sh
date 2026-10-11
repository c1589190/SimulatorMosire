#!/usr/bin/env bash
# W9 测试批（2026-10-11）：真世界读数装置（复用 /tmp/w6acc/w6run.py —— 起服务 → POST /api/advance → 抽 dump）
#
# 世界：small-world（主）0→360 + three-powers（对照）0→360，均**从创世新播**（§一.11：不拿旧档当证据）。
# 端口：1097x（本批专用；不复用 103xx / 10961-10967 / 5711 一族）
# store：/tmp/w9t/stores/<tag>（笨重中间产物）；日志与 dump 落本批目录（.superpowers/...，gitignore）
#
# 用法：bash run-worlds.sh <mode>   mode = all | sw | tp
set -u
ROOT=/home/cna/SimulatorMosire
BATCH=$ROOT/.superpowers/sdd/2026-10-11-w9-test-batch
JAR=$ROOT/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
RUNNER=/tmp/w6acc/w6run.py
CFG=$ROOT/.superpowers/sdd/2026-10-11-haul-supply-investigation/scripts
OUT=$BATCH/logs
DUMPS=$BATCH/dumps
STORES=/tmp/w9t/stores
mkdir -p "$OUT" "$DUMPS" "$STORES"

# 只把与本批判据有关的 event 行写进日志档（其余仍然读走 stdout，不阻塞子进程）
KEEP='event=(HAUL_[A-Z_]*|LOGISTICS_[A-Z_]*|MERCHANT_[A-Z_]*|ONDEMAND_[A-Z_]*|MIGRATION_[A-Z_]*|MODE_MIGRATION[A-Z_]*|MARKET_[A-Z_]*|PRIMARY_MODE[A-Z_]*|CAPACITY_[A-Z_]*|MINT_[A-Z_]*|MONEY_MARKET[A-Z_]*|DEBT_[A-Z_]*|HOUSEHOLD_[A-Z_]*|POPULATION_[A-Z_]*|DAY_START|DAY_END|CLASS_[A-Z_]*|SLIDE_[A-Z_]*|STRATUM[A-Z_]*)'

run() { # run <tag> <world> <port> <plan> <store-name> <extras...>
  local tag=$1 world=$2 port=$3 plan=$4 store=$5; shift 5
  echo "=== [$(date +%H:%M:%S)] $tag world=$world port=$port plan=$plan store=$store extras=$* ==="
  python3 "$RUNNER" --jar "$JAR" --tag "$tag" --store "$STORES/$store" \
    --gui-port "$port" --world "$world" --config "$CFG/shell-$([ "$world" = small-world ] && echo sw || echo tp).json" \
    --plan "$BATCH/plans/$plan" --out "$OUT" --dump-dir "$DUMPS" \
    --log-level DEBUG --trace-level TRACE --social-level INFO --app-level INFO \
    --log-keep "$KEEP" --prune "$@"
  echo "=== [$(date +%H:%M:%S)] $tag rc=$? ==="
}

mode="${1:-all}"
want() { [ "$mode" = "all" ] || [ "$mode" = "$1" ]; }

if want sw; then run sw360 small-world 10971 plan-sw-360.json sw360 --keep-store ; fi
if want tp; then run tp360 three-powers 10972 plan-tp-360.json tp360 ; fi
if want b2; then run sw3seg small-world 10973 plan-sw-3seg.json sw3seg ; run swtick small-world 10974 plan-sw-tick.json swtick ; fi
echo "=== [$(date +%H:%M:%S)] MODE=$mode DONE ==="
