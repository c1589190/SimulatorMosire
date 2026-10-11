#!/usr/bin/env bash
# W9e 测试批（2026-10-11）：真世界读数装置（与 W7e/W9d 同一台尺 —— /tmp/w6acc/w6run.py：起服务 → POST /api/advance → 抽 dump）
#
# 世界：small-world（主）0→360 + three-powers（对照）0→360，均**从创世新播**（§一.11：不拿旧档当证据）；
#       另跑 sw 的 3 段 / 逐 tick 两形态（B2 分段等价，与 sw360 三份 dump 比规范化 md5）。
# 端口：1098x（本批专用；不与 103xx / 10961-10967 / 10971-10976 / 5711 重叠）
# store：/tmp/w9e/stores/<tag>（笨重中间产物）；日志与 dump 落本批目录（.superpowers/...，gitignore）
#
# ★ 与本批的 log-keep（比 W9d 更紧，为控制日志体积）：排除 CAPACITY_DEMAND_INSTANCE / HOUSEHOLD_GAP 两个
#   "逐户逐商品逐 tick"的洪泛事件（它们不是本批判据的读数），其余读数事件全留（含 TRACE 的 MIGRATION_MOVE）。
#
# 用法：bash run-worlds.sh <mode>   mode = all | sw | tp | b2
set -u
ROOT=/home/cna/SimulatorMosire
BATCH=$ROOT/.superpowers/sdd/2026-10-11-w9e-test-batch
JAR=$ROOT/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
RUNNER=/tmp/w6acc/w6run.py
CFG=$ROOT/.superpowers/sdd/2026-10-11-haul-supply-investigation/scripts
OUT=$BATCH/logs
DUMPS=$BATCH/dumps
STORES=/tmp/w9e/stores
mkdir -p "$OUT" "$DUMPS" "$STORES"

KEEP='event=(MIGRATION_[A-Z_]*|MODE_MIGRATION[A-Z_]*|MARKET_REPORT|MARKET_FILL|MERCHANT_[A-Z_]*|ONDEMAND_[A-Z_]*|HAUL_[A-Z_]*|LOGISTICS_[A-Z_]*|CAPACITY_(?!DEMAND_INSTANCE)[A-Z_]*|MINT_[A-Z_]*|MONEY_MARKET[A-Z_]*|DEBT_[A-Z_]*|CLASS_[A-Z_]*|SLIDE_[A-Z_]*|STRATUM[A-Z_]*|DAY_START|DAY_END)'

run() { # run <tag> <world> <port> <plan> <store-name> <extras...>
  local tag=$1 world=$2 port=$3 plan=$4 store=$5; shift 5
  echo "=== [$(date +%H:%M:%S)] $tag world=$world port=$port plan=$plan store=$store extras=$* ==="
  python3 "$RUNNER" --jar "$JAR" --tag "$tag" --store "$STORES/$store" \
    --gui-port "$port" --world "$world" --config "$CFG/shell-$([ "$world" = small-world ] && echo sw || echo tp).json" \
    --plan "$BATCH/plans/$plan" --out "$OUT" --dump-dir "$DUMPS" \
    --log-level DEBUG --trace-level TRACE --social-level INFO --app-level INFO \
    --log-keep "$KEEP" "$@"
  echo "=== [$(date +%H:%M:%S)] $tag rc=$? ==="
}

mode="${1:-all}"
want() { [ "$mode" = "all" ] || [ "$mode" = "$1" ]; }

if want sw; then run sw360 small-world 10981 plan-sw-360.json sw360 --keep-store ; fi
if want tp; then run tp360 three-powers 10982 plan-tp-360.json tp360 ; fi
if want b2; then run sw3seg small-world 10983 plan-sw-3seg.json sw3seg ; run swtick small-world 10984 plan-sw-tick.json swtick ; fi
echo "=== [$(date +%H:%M:%S)] MODE=$mode DONE ==="
