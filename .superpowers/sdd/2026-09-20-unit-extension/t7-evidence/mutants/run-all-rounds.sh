#!/usr/bin/env bash
# 顺序跑 T7 的七轮变异（一次只跑一个 Maven：本脚本是串行的）。
# ★ 判据（沿用 T5/T6 装置）：先过十道门禁，再读 rc 与失败清单下"被杀/存活"结论——
#   "被杀"（VOID）既不是红也不是绿，留档不删、改前台重跑。
# ★ T7 新增的第一道自保：开跑前钉住"本装置作用于哪棵树"——`git -C "$HERE"` 取脚本所在树的根，
#   与预期工作树不符即拒跑（本仓主检出与 worktree 有同名文件，跑错树 = 在另一棵树上动刀）。
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
EXPECT="$HOME/../dev/SimulatorMosire/.claude/worktrees/uet7"
case "$REPO" in */.claude/worktrees/uet7) ;; *) echo "ABORT: 不在 uet7 工作树内: $REPO"; exit 3 ;; esac
cd "$REPO" || exit 2
BASE="$HERE/baseline-md5.txt"
SUMMARY="$HERE/rounds-summary.txt"

: > "$SUMMARY"
while read -r label target mutant; do
  [ -z "${label:-}" ] && continue
  case "$label" in \#*) continue ;; esac
  log="$HERE/${label}.log"
  "$HERE/mut-round.sh" "$label" "$target" "$HERE/$mutant" "$log" "$BASE" | tee -a "$SUMMARY"
done <<'ROUNDS'
t7m1 simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitTimeParticipant.java t7m1_UnitTimeParticipant.java
t7m2 simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java t7m2_UnitOperations.java
t7m3 simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java t7m3_UnitOperations.java
t7m4 simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitTimeParticipant.java t7m4_UnitTimeParticipant.java
t7m5 simos-unit/src/main/java/io/mosire/simos/unit/UnitState.java t7m5_UnitState.java
t7m6 simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java t7m6_UnitOperations.java
t7m7 simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitTimeParticipant.java t7m7_UnitTimeParticipant.java
ROUNDS
echo "--- 汇总 ---"
cat "$SUMMARY"
