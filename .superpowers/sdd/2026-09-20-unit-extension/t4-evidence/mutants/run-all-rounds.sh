#!/usr/bin/env bash
# T4 变异轮驱动：按 manifest.txt（生成器实测产出，不手抄）逐轮调用 mut-round.sh。
# 用法：bash run-all-rounds.sh
# ★ 每轮的 SELF 行（含四个 md5 + verdict/outcome）同时进 stdout 与 logs/rounds-summary.txt。
# ★ 跑完做**收口干净世界核对**：五个目标路径必须与 baseline-md5.txt 逐字节相同。
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../../../.." && pwd)"   # mutants/ → t4-evidence/ → … → 仓根(worktree)
LOGS="$HERE/../logs"
BASELINE="$HERE/baseline-md5.txt"
SEL="UnitOperationsTest,UnitCommandHandlersTest"
mkdir -p "$LOGS"
SUMMARY="$LOGS/rounds-summary.txt"

if [ ! -f "$BASELINE" ]; then
  echo "ABORT: 缺基线文件 $BASELINE（先跑 make-baseline）"; exit 3
fi
if [ ! -f "$HERE/manifest.txt" ]; then
  echo "ABORT: 缺 manifest.txt（先跑 make-mutants.py）"; exit 3
fi

: > "$SUMMARY"
echo "=== T4 变异轮开始 $(date -Iseconds) ROOT=$ROOT SEL=$SEL ===" | tee -a "$SUMMARY"

while IFS=$'\t' read -r mutant target; do
  [ -z "${mutant:-}" ] && continue
  label="${mutant%.java}"
  log="$LOGS/${label}.log"
  line=$(bash "$HERE/mut-round.sh" "$label" "$target" "$HERE/$mutant" "$SEL" "$log" "$BASELINE" 2>&1 | tee -a "$SUMMARY" | grep '^SELF ')
  rc=$?
  if [ -z "$line" ]; then
    echo "ROUND-NOT-RUN $label (装置提前退出 —— 见 stdout)" | tee -a "$SUMMARY"
  fi
done < "$HERE/manifest.txt"

echo "=== 收口干净世界核对 $(date -Iseconds) ===" | tee -a "$SUMMARY"
bad=0
while read -r want path; do
  got=$(md5sum "$ROOT/$path" | awk '{print $1}')
  if [ "$got" = "$want" ]; then
    echo "CLEAN-OK $path $got" | tee -a "$SUMMARY"
  else
    echo "CLEAN-BAD $path want=$want got=$got" | tee -a "$SUMMARY"
    bad=1
  fi
done < "$BASELINE"
echo "clean_world_final=$([ "$bad" -eq 0 ] && echo OK || echo BAD)" | tee -a "$SUMMARY"
echo "=== T4 变异轮结束 $(date -Iseconds) ===" | tee -a "$SUMMARY"
exit "$bad"
