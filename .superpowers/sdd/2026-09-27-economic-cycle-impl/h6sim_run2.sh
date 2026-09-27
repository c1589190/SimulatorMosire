#!/usr/bin/env bash
# H6 关账模拟：逐周期推进 + 每个关账日落一次原始读数（★ 推进可覆盖、原始读数不可复得）。
set -uo pipefail
cd "$(dirname "$0")"
ADV=../2026-09-26-year-one-simulation/v3curve_advance.py
for t in 120 240 360 480 600; do
  echo "===== advance -> $t ($(date +%H:%M:%S)) ====="
  /usr/bin/time -f "  advance 耗时 %E" python3 "$ADV" "$t" 2>&1 | tail -12
  echo "===== dump r2$t ($(date +%H:%M:%S)) ====="
  python3 h6sim_dump.py "r2$t" 2>&1 | tail -6
done
echo "===== 全部关账日读数完成 ($(date +%H:%M:%S)) ====="
