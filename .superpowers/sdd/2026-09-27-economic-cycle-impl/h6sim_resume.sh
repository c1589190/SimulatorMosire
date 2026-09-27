#!/usr/bin/env bash
# H6 关账模拟（基线批）：**断点续跑** —— 服务被杀之后从 store 里已有的 tick 接着推。
set -uo pipefail
cd "$(dirname "$0")"
ADV=../2026-09-26-year-one-simulation/v3curve_advance.py
for t in "$@"; do
  echo "===== advance -> $t ($(date +%H:%M:%S)) ====="
  /usr/bin/time -f "  advance 耗时 %E" python3 "$ADV" "$t" 2>&1 | tail -8
  echo "===== dump tick$t ($(date +%H:%M:%S)) ====="
  python3 h6sim_dump.py "tick$t" 2>&1 | tail -6
done
echo "===== 续跑结束 ($(date +%H:%M:%S)) ====="
