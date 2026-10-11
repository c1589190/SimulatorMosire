#!/usr/bin/env bash
# 财富聚集调查 —— 两次长跑（small-world / three-powers，各 0→600 逐日推进）。
# 一次只有一个世界实例（AGENTS §一.1 同族纪律）；端口 10411 / 10421（不复用 5711/5817/5857/5867/5715/5757 与 W6 的 103xx）。
# 产物：store / 逐日读数（含逐户） / 6 个网格日全量 dump / 过滤后的日志 —— 全落本 SDD 目录。
set -euo pipefail
D=/home/cna/SimulatorMosire/.superpowers/sdd/2026-10-11-wealth-concentration
JAR=/home/cna/SimulatorMosire/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
KEEP='event=(TAX_[A-Z_]*|GOV_[A-Z_]*|PERIODIC_ADJUSTMENT_[A-Z_]*|FAMINE_[A-Z_]*|MINT_[A-Z_]*|DEBT_[A-Z_]*|INTEREST|LIQUIDATION|REPAY|HARVEST|MIGRATION_[A-Z_]*|MODE_MIGRATION_[A-Z_]*|CLASS_SLIDE[A-Z_]*|RELIEF[A-Z_]*|TRANSFER[A-Z_]*|REMIT[A-Z_]*|SUBSID[A-Z_]*|WELFARE[A-Z_]*|WAGE[A-Z_]*|SALARY[A-Z_]*|ISSUANCE[A-Z_]*|DAY_START |DAY_END |MARKET |HAUL_[A-Z_]*|HOUSEHOLD_GAP_REPORTED|HOUSEHOLD_VITAL_WRITEBACK|PRODUCTION_MODIFIER_INJECTED|ARBITRAGE_ROUND|MONEY_MARKET_[A-Z_]*|ENGINE)'

cd "$D/scripts"
echo "=== jar md5 ==="; md5sum "$JAR"

echo "=== [1/2] small-world 0->600 逐日（端口 10411） ==="
python3 wr_run.py --jar "$JAR" --tag sw600 --store "$D/stores/sw600" --gui-port 10411 \
  --world small-world --config shell-sw.json --plan plan-sw.json \
  --out "$D/results" --dump-dir "$D/dumps" --daily-out "$D/daily/hh-sw600.jsonl" \
  --log-level DEBUG --trace-level TRACE --social-level DEBUG --app-level DEBUG \
  --prune --log-keep "$KEEP" 2>&1 | tail -40

echo "=== [2/2] three-powers 0->600 逐日（端口 10421） ==="
python3 wr_run.py --jar "$JAR" --tag tp600 --store "$D/stores/tp600" --gui-port 10421 \
  --world three-powers --config shell-tp.json --plan plan-tp.json \
  --out "$D/results" --dump-dir "$D/dumps" --daily-out "$D/daily/hh-tp600.jsonl" \
  --log-level DEBUG --trace-level TRACE --social-level DEBUG --app-level DEBUG \
  --prune --log-keep "$KEEP" 2>&1 | tail -40

echo "=== 收工 ==="
pgrep -af "simos-app-0.1.0-SNAPSHOT-shaded" || echo "(无残留 java)"
ls -la "$D/daily" "$D/dumps"
