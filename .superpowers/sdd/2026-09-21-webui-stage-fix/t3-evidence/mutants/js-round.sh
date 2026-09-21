#!/usr/bin/env bash
# js-round.sh —— T3 前端变异轮装置（node 门禁）。九道门禁的 JS 适用子集：
#   ① 干净世界（当前字节 md5 == orig 备份）② 变异体字节确实不同 ③ 推成规范名目标文件
#   ④ 清陈旧产物（N/A：node 直读源）⑤ 记录 `# tests N` 且 N>=1 ⑥ 本轮专属 gate 日志（名含轮次）
#   ⑦ 红点落**被保护断言**（打印 not ok 名 + 期望正则命中）⑧ cp 逐字节还原（绝不 git checkout）
#   ⑨ 日志自指（orig/mutant/restored 三个 md5 写进日志；读取处先断言非空）
set -u
WT=/home/cna/SimulatorMosire/.claude/worktrees/wsf-t3
EV=$WT/.superpowers/sdd/2026-09-21-webui-stage-fix/t3-evidence
ORIG=$EV/mutants/orig
LOGS=$EV/mutants/logs
WEBUI=$WT/simos-app/src/main/resources/webui
JS=$WT/simos-app/src/test/js

ROUND="${1:?usage: js-round.sh <round-id>}"
LOG="$LOGS/${ROUND}.log"
: > "$LOG"
say() { echo "$@" | tee -a "$LOG"; }
req() { [ -n "${1:-}" ] || { say "ABSENT_VALUE abort ($2)"; exit 9; }; }
md5() { md5sum "$1" | cut -d' ' -f1; }

case "$ROUND" in
  t2m5) TGT="$JS/gate-contract.test.cjs" ;;
  *)    TGT="$WEBUI/map.js" ;;
esac
BASE=$(basename "$TGT")
ORIGF="$ORIG/$BASE"

say "===== T3 JS 变异轮 $ROUND ====="
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
# ⑨ 日志自指
{
  echo ""
  echo "===== 装置补记（$ROUND）====="
  echo "target=$BASE"
  echo "orig_md5=$orig"
  echo "mutant_md5=$mut"
  echo "restored_md5=$rest"
  echo "gate_rc=$grc gate_tests=$tn failing=$failing"
} >> "$LOG"
SELF=$(grep -oE 'mutant_md5=[0-9a-f]{32}' "$LOG" | tail -1 | cut -d= -f2)
[ -n "$SELF" ] || { echo "FATAL: 日志自指 md5 读空"; exit 2; }
say "===== end $ROUND self_md5=$SELF ====="
