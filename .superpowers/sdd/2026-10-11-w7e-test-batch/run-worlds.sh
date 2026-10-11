#!/usr/bin/env bash
# W7e 测试批（2026-10-11）：真世界读数装置（复用 W6/W7d 的 /tmp/w6acc/w6run.py —— 起服务 → POST /api/advance → 抽 checkpoint dump）
#
# 世界：small-world（主）× 4 轮（本体 0→689 / B2 三段 / B2 逐 tick / W8 689→700 续跑）+ three-powers（对照）0→660
# 端口：1096x（本批专用；不复用 103xx-10931 与 5711/5817/5857/5867/5715/5757）
# store：/tmp/w7e/stores/<tag>（笨重中间产物）；日志与 dump 落本批目录（.superpowers/...，控制方 git add -f）
#
# 用法：bash run-worlds.sh <mode>   mode = all | gen | main | b2 | tp | perf | w8
set -u
ROOT=/home/cna/SimulatorMosire
BATCH=$ROOT/.superpowers/sdd/2026-10-11-w7e-test-batch
JAR=$ROOT/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
RUNNER=/tmp/w6acc/w6run.py
CFG=$ROOT/.superpowers/sdd/2026-10-11-haul-supply-investigation/scripts
OUT=$BATCH/logs
DUMPS=$BATCH/dumps
STORES=/tmp/w7e/stores
mkdir -p "$OUT" "$DUMPS" "$STORES"

# 只把与本批判据有关的 event 行写进日志档（其余仍然读走 stdout，不阻塞子进程）
KEEP='event=(HAUL_[A-Z_]*|LOGISTICS_[A-Z_]*|MERCHANT_[A-Z_]*|ONDEMAND_[A-Z_]*|MIGRATION_[A-Z_]*|MODE_MIGRATION[A-Z_]*|MARKET_[A-Z_]*|PRIMARY_MODE[A-Z_]*|CAPACITY_[A-Z_]*|MINT_[A-Z_]*|MONEY_MARKET[A-Z_]*|DEBT_[A-Z_]*|HOUSEHOLD_[A-Z_]*|POPULATION_[A-Z_]*|DAY_START|DAY_END)'

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
# ★ 不能用 `case … all)` 里逐支并列：case 只走**第一个**命中的分支（不穿透）⇒ 全部改成 if 守卫。
want() { [ "$mode" = "all" ] || [ "$mode" = "$1" ]; }

if want gen;  then run swgen  small-world 10967 plan-sw-genesis.json swgen --keep-store ; fi
if want main; then run sw360  small-world 10961 plan-sw-oneshot.json sw360 --keep-store ; fi
if want w8;   then run sw700  small-world 10962 plan-sw-w8.json      sw360 --keep-store ; fi
if want b2;   then run sw3seg small-world 10963 plan-sw-3seg.json   sw3seg ; run swtick small-world 10964 plan-sw-tick.json swtick ; fi
if want tp;   then run tp360  three-powers 10965 plan-tp-oneshot.json tp360 ; fi
if want perf; then run swperf small-world 10966 plan-sw-perf.json   swperf --log-level INFO --trace-level INFO ; fi
echo "=== [$(date +%H:%M:%S)] MODE=$mode DONE ==="
