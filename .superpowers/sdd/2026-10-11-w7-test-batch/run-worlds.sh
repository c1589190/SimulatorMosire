#!/usr/bin/env bash
# W7 测试批：真世界读数装置（复用 W6 的 /tmp/w6acc/w6run.py —— 起服务 → POST /api/advance → 抽 checkpoint dump）
#
# 世界：small-world（主）× 4 轮（本体 0→689 / B2 三段 / B2 逐 tick）+ three-powers（对照）0→660
# 端口：109xx（本批专用；不复用 103xx-108xx 与 5711/5817/5857/5867/5715/5757）
# store：/tmp/w7t/stores/<tag>（笨重中间产物）；日志与 dump 落本批目录（.superpowers/...，控制方 git add -f）
set -u
ROOT=/home/cna/SimulatorMosire
BATCH=$ROOT/.superpowers/sdd/2026-10-11-w7-test-batch
JAR=$ROOT/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
RUNNER=/tmp/w6acc/w6run.py
CFG=$ROOT/.superpowers/sdd/2026-10-11-haul-supply-investigation/scripts
OUT=$BATCH/logs
DUMPS=$BATCH/dumps
STORES=/tmp/w7t/stores
mkdir -p "$OUT" "$DUMPS" "$STORES"

# 只把与本批判据有关的 event 行写进日志档（其余仍然读走 stdout，不阻塞子进程）
KEEP='event=(HAUL_[A-Z_]*|LOGISTICS_[A-Z_]*|MERCHANT_[A-Z_]*|ONDEMAND_[A-Z_]*|MIGRATION_[A-Z_]*|MODE_MIGRATION[A-Z_]*|MARKET_[A-Z_]*|PRIMARY_MODE[A-Z_]*|CAPACITY_[A-Z_]*|MINT_[A-Z_]*|MONEY_MARKET[A-Z_]*|DAY_START|DAY_END)'

run() { # run <tag> <world> <port> <plan>
  local tag=$1 world=$2 port=$3 plan=$4
  echo "=== [$(date +%H:%M:%S)] $tag world=$world port=$port plan=$plan ==="
  python3 "$RUNNER" --jar "$JAR" --tag "$tag" --store "$STORES/$tag" \
    --gui-port "$port" --world "$world" --config "$CFG/shell-$([ "$world" = small-world ] && echo sw || echo tp).json" \
    --plan "$BATCH/plans/$plan" --out "$OUT" --dump-dir "$DUMPS" \
    --log-level DEBUG --trace-level TRACE --social-level INFO --app-level INFO \
    --log-keep "$KEEP" --prune
  echo "=== [$(date +%H:%M:%S)] $tag rc=$? ==="
}

run sw360 small-world 10901 plan-sw-oneshot.json
run sw3seg small-world 10911 plan-sw-3seg.json
run swtick small-world 10921 plan-sw-tick.json
run tp360 three-powers 10931 plan-tp-oneshot.json
echo "=== [$(date +%H:%M:%S)] ALL RUNS DONE ==="
