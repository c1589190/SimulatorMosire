#!/usr/bin/env bash
# 干净世界核对器：逐条比 baseline.md5，并打印（不许把「读到空」当「相等」——形态 5/6 的教训）。
set -uo pipefail
WT=/home/dev/SimulatorMosire/.claude/worktrees/ts+m1
MUT="$WT/.superpowers/sdd/2026-09-22-tool-surface/m3-evidence/mutants"
BASELINE="$MUT/baseline.md5"
bad=0
n=0
while read -r base path; do
  [ -n "$base" ] || { echo "拒收：baseline.md5 读到空行"; exit 2; }
  now=$(md5sum "$WT/$path" | cut -d' ' -f1)
  [ -n "$now" ] || { echo "拒收：读不到 $path 的 md5"; exit 2; }
  n=$((n + 1))
  if [ "$now" != "$base" ]; then
    echo "DIFF $path"
    echo "     baseline=$base"
    echo "     now     =$now"
    bad=$((bad + 1))
  fi
done < "$BASELINE"
echo "核对 $n 个文件，$bad 个与基线不同"
# 规范名之外的 .java（m3 的孤立文件必须已被清掉）
extra=0
for f in "$WT/simos-app/src/main/java/io/mosire/simos/app/tools/write"/*.java; do
  base=$(basename "$f")
  grep -qxF "$base" "$MUT/write-whitelist.txt" || { echo "EXTRA tools/write/$base"; extra=$((extra + 1)); }
done
echo "write/ 规范名之外的 .java: $extra 个"
echo "baseline.md5 自记 md5 = $(md5sum "$BASELINE" | cut -d' ' -f1)"
[ "$bad" = "0" ] && [ "$extra" = "0" ] && echo "干净世界: OK"
