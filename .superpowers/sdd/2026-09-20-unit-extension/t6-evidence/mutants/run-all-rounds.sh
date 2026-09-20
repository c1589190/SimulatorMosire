#!/usr/bin/env bash
# 顺序跑 T6 的五轮变异（一次只跑一个 Maven：本脚本是串行的）。
# ★ 判据（沿用 T5 装置）：先过十道门禁，再读 rc 与失败清单下"被杀/存活"结论——
#   "被杀"（VOID）既不是红也不是绿，留档不删、改前台重跑。
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
BASE="$HERE/baseline-md5.txt"
SUMMARY="$HERE/rounds-summary.txt"

: > "$SUMMARY"
while read -r label target mutant; do
  [ -z "${label:-}" ] && continue
  case "$label" in \#*) continue ;; esac
  log="$HERE/${label}.log"
  "$HERE/mut-round.sh" "$label" "$target" "$HERE/$mutant" "$log" "$BASE" | tee -a "$SUMMARY"
done <<'ROUNDS'
t6m1 simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java t6m1_UnitOperations.java
t6m2 simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java t6m2_UnitOperations.java
t6m3 simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java t6m3_UnitOperations.java
t6m4 simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java t6m4_UnitOperations.java
t6m5 simos-unit/src/main/java/io/mosire/simos/unit/spi/PlanSparseRouteHandler.java t6m5_PlanSparseRouteHandler.java
t6m6 simos-unit/src/main/java/io/mosire/simos/unit/spi/PlanSparseRouteHandler.java t6m6_PlanSparseRouteHandler.java
t6m7 simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java t6m7_UnitOperations.java
t6m8 simos-unit/src/main/java/io/mosire/simos/unit/spi/PlanSparseRouteHandler.java t6m8_PlanSparseRouteHandler.java
ROUNDS
echo "--- 汇总 ---"
cat "$SUMMARY"
