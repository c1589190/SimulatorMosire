#!/usr/bin/env bash
# mut-round.sh —— T1 变异轮装置（前端 node 门禁；Java 九道门禁中不适用者逐条注明）。
#
# 用法：mut-round.sh <m1|m2|m3>
# 九道门禁的 JS 适用子集：
#   ① 干净世界（先比当前字节 == pristine 的 md5，不等即 abort）
#   ② 变异体字节确实不同（mutant_md5 != orig_md5）
#   ③ 推成**规范名目标文件**（不是 variant 名 ⇒ 不落"编的是原件/编译错误"两个坑）
#   ④ 清陈旧 .class —— N/A（node 直接读源；本轮无 Maven/编译产物）
#   ⑤ COMPILATION ERROR=0 且 Tests run>=1 —— 断言 node 汇总 `# tests N` 非空且 N>=1
#   ⑥ surefire mtime —— N/A（无 surefire）；改为本轮专属 gate 日志（文件名含轮次）
#   ⑦ 红点落**被保护断言**（打印 not ok 的测试名，逐轮人核）
#   ⑧ cp **逐字节**还原（restored_md5 == orig_md5；绝不用 git checkout）
#   ⑨ 日志自指：orig/mutant/restored 三个 md5 写进本日志；读取处**先断言非空**再下结论
set -u

WT=/home/cna/SimulatorMosire/.claude/worktrees/wsf-t1
EV=$WT/.superpowers/sdd/2026-09-21-webui-stage-fix/t1-evidence
ORIG=$EV/mutants/orig
LOGS=$EV/mutants/logs
WEBUI=$WT/simos-app/src/main/resources/webui

ROUND="${1:?usage: mut-round.sh <m1|m2|m3>}"
LOG="$LOGS/${ROUND}.log"
: > "$LOG"
say() { echo "$@" | tee -a "$LOG"; }
# ⑨ 先断言读到的聚合 md5 非空，再下结论
req() { [ -n "${1:-}" ] || { say "ABSENT_VALUE abort ($2)"; exit 9; }; }
md5() { md5sum "$1" | cut -d' ' -f1; }

case "$ROUND" in
  m1) TGT="$WEBUI/index.html" ;;
  m2) TGT="$WEBUI/notifications.js" ;;
  m3) TGT="$WEBUI/api.js" ;;
  *) echo "unknown round $ROUND"; exit 2 ;;
esac
BASE=$(basename "$TGT")
ORIGF="$ORIG/$BASE"

say "===== T1 变异轮 $ROUND ====="
say "target=$BASE"

# ① 干净世界
cur=$(md5 "$TGT"); req "$cur" cur
orig=$(md5 "$ORIGF"); req "$orig" orig
say "orig_md5=$orig"
say "clean_world_md5=$cur"
[ "$cur" = "$orig" ] || { say "NOT_CLEAN_WORLD abort"; exit 3; }

# ②③ 生成并推到规范名
python3 "$EV/mutants/make-mutant.py" "$ROUND" "$ORIGF" "$TGT" | tee -a "$LOG"
mut=$(md5 "$TGT"); req "$mut" mutant
say "mutant_md5=$mut"
[ "$mut" != "$orig" ] || { say "MUTANT_IDENTICAL abort"; exit 4; }

# ⑤⑥ 跑本轮专属 node 门禁
cd "$WT"
node simos-app/src/test/js/run-gate.cjs > "$LOG.gate" 2>&1
grc=$?
tn=$(grep -m1 '^# tests ' "$LOG.gate" | grep -oE '[0-9]+')
req "$tn" tests
say "gate_rc=$grc"
say "gate_tests=$tn"
[ "$tn" -ge 1 ] || { say "TESTS_ZERO abort"; exit 5; }

# ⑧ 逐字节还原
cp "$ORIGF" "$TGT"
rest=$(md5 "$TGT"); req "$rest" restored
say "restored_md5=$rest"
[ "$rest" = "$orig" ] || { say "RESTORE_MISMATCH abort"; exit 6; }

# 判定 + ⑦ 红点
if [ "$grc" -ne 0 ]; then say "VERDICT=KILLED"; else say "VERDICT=SURVIVED"; fi
say "-- 红点（被保护断言）--"
grep -E '^not ok ' "$LOG.gate" | sed 's/^/  /' | tee -a "$LOG"
failing=$(grep -c '^not ok ' "$LOG.gate"); req "$failing" failing
say "failing_count=$failing"
say "===== end $ROUND ====="
