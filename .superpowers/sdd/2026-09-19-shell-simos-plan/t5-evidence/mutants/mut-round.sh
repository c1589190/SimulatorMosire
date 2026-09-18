#!/usr/bin/env bash
# T5 变异装置（照 task-17 的九道门禁骨架，ROOT/模块改到 worktree + simos-app）。
# 用法: mut-round.sh <round-id> <target-file-rel> <mutant-file-rel> <test-filter>
# 门禁：干净世界 md5 → 变异体字节不同 → 删陈旧 .class 逼重编 → COMPILATION ERROR=0 且 Tests run≥1
#       → surefire 报告 mtime 落在本轮 → 显示红点 → cp 还原并核 md5 → 三处 md5 追加进日志本身。
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m5t5
cd "$ROOT" || exit 2
RID="$1"; TGT="$2"; MUT="$3"; FILTER="$4"
EV=.superpowers/sdd/2026-09-19-shell-simos-plan/t5-evidence
LOG="$EV/logs/$RID.log"
mkdir -p "$EV/logs" "$EV/mutants/orig"
ORIG_BACKUP="$EV/mutants/orig/$(basename "$TGT")"

restore() { cp "$ORIG_BACKUP" "$TGT" 2>/dev/null || true; }
trap restore EXIT

ORIG_MD5=$(md5sum "$TGT" | cut -d' ' -f1)
BEFORE=$(md5sum "$TGT" | cut -d' ' -f1)
if [ "$ORIG_MD5" != "$BEFORE" ]; then echo "FATAL: 备份与工作树不一致"; exit 2; fi

cp "$MUT" "$TGT"
PUSHED=$(md5sum "$TGT" | cut -d' ' -f1)
[ "$PUSHED" != "$ORIG_MD5" ] || { echo "FATAL: 变异体与原件字节相同"; exit 2; }

CLASS=$(basename "${TGT%.java}")
find simos-app/target/classes -name "$CLASS.class" -delete 2>/dev/null || true
CLS=$(echo "$FILTER" | cut -d'#' -f1)

ROUND_START=$(date +%s)
./mvnw -pl simos-app -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest="$FILTER" test > "$LOG" 2>&1
RC=$?
CE=$(grep -c "COMPILATION ERROR" "$LOG")
TR=$(grep -cE "Tests run: [0-9]+" "$LOG")
REPORT=$(ls -t simos-app/target/surefire-reports/*."$CLS".txt 2>/dev/null | head -1)
RMTIME=$( [ -n "$REPORT" ] && stat -c %Y "$REPORT" || echo 0 )

echo "──── $RID ────"
echo "orig_md5=$ORIG_MD5  mutant_md5=$PUSHED  worktree_before=$BEFORE"
echo "rc=$RC  COMPILATION_ERROR_lines=$CE  Tests_run_lines=$TR"
echo "surefire_report=$REPORT  mtime=$RMTIME  round_start=$ROUND_START"
echo "--- 变红的方法/行 ---"
grep -E "^\[ERROR\]   [A-Za-z]+Test\." "$LOG" | head -6
grep -E "^\[ERROR\] Tests run:" "$LOG" | head -2

restore
trap - EXIT
RESTORED=$(md5sum "$TGT" | cut -d' ' -f1)
echo "worktree_restored=$RESTORED"
if [ "$RESTORED" != "$ORIG_MD5" ]; then echo "FATAL: 还原不等于原件"; exit 2; fi

{
  echo ""
  echo "===== 装置补记（$RID）：本轮推送的正是以下字节 ====="
  echo "目标: $TGT"
  echo "orig_md5=$ORIG_MD5"
  echo "mutant_md5=$PUSHED"
  echo "worktree_restored=$RESTORED"
} >> "$LOG"

if [ "$RC" -eq 0 ] || [ "$CE" -ne 0 ] || [ "$TR" -lt 1 ] || [ "$RMTIME" -lt "$ROUND_START" ] || [ "$RESTORED" != "$ORIG_MD5" ]; then
  echo "⇒ 本轮作废（rc/编译/未跑到/报告 mtime/还原 之一不达标）"; exit 2
fi
echo "⇒ 杀死（红在上面那组方法/行），工作树已逐字节还原"
