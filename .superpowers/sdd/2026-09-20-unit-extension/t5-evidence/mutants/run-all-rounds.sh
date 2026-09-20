#!/usr/bin/env bash
# T5 变异轮驱动（照 T4 的同名脚本）：
#   run-all-rounds.sh [START] [END]
# 按 manifest.txt 的**行区间**逐轮串行跑（本机 nproc=2 ⇒ 一次只准有一个 Maven）。
# 每轮：清干净世界由 mut-round.sh 的 ① 道保证；日志落在 logs/<label>.log；滚动汇总落 rounds-summary.txt。
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(git rev-parse --show-toplevel)"
LOGS="$HERE/../logs"
mkdir -p "$LOGS"
MANIFEST="$HERE/manifest.txt"
BASELINE="$HERE/baseline-md5.txt"
SEL="UnitOperationsTest,UnitCommandHandlersTest,UnitCodecTest,UnitTimeParticipantTest"

START="${1:-1}"
END="${2:-9999}"
total=$(wc -l < "$MANIFEST")
[ "$END" -gt "$total" ] && END="$total"

echo "== T5 变异轮：manifest $total 行，本轮跑 $START..$END，选择器=$SEL ==" | tee -a "$HERE/rounds-summary.txt"
i=0
while IFS=$'\t' read -r name target; do
  [ -z "${name:-}" ] && continue
  i=$((i + 1))
  [ "$i" -lt "$START" ] && continue
  [ "$i" -gt "$END" ] && break
  label="${name%.java}"
  log="$LOGS/${label}.log"
  : > "$log"
  bash "$HERE/mut-round.sh" "$label" "$target" "$HERE/$name" "$SEL" "$log" "$BASELINE" | tee -a "$HERE/rounds-summary.txt"
done < "$MANIFEST"

# ── 收尾：干净世界复核（八条靶路径逐个与基线对） ──────────────────────
echo "== 收尾干净世界复核（md5 与 baseline 逐条比） ==" | tee -a "$HERE/rounds-summary.txt"
clean=0
while read -r md5 path; do
  now=$(md5sum "$ROOT/$path" | awk '{print $1}')
  if [ "$now" = "$md5" ]; then
    echo "CLEAN-OK  $path $now" | tee -a "$HERE/rounds-summary.txt"
  else
    echo "CLEAN-DIRTY $path now=$now baseline=$md5" | tee -a "$HERE/rounds-summary.txt"
    clean=1
  fi
done < "$BASELINE"
[ "$clean" = "0" ] && echo "CLEAN-WORLD OK" | tee -a "$HERE/rounds-summary.txt"
exit "$clean"
