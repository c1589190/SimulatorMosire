#!/usr/bin/env bash
# M5 T8 变异装置（照 M4 task-17 九道门禁骨架，ROOT 改本工作树，模块 simos-app）。
# 用法: mut-round.sh <round-id> <target-file-rel> <mutant-file-rel> <test-filter>
# 九道门禁：
#  ① 干净世界（原件备份 md5 == 工作树 md5）② 变异体字节不同 ③ 按**目标类名**白名单推送
#  ④ 删陈旧 .class 逼重编 + 删陈旧 surefire 报告逼本轮重写 ⑤ COMPILATION ERROR=0 且 Tests run≥1
#  ⑥ surefire 报告 mtime 落在本轮 ⑦ 红（rc≠0）且红点落在被保护断言（终端打印出方法/行）
#  ⑧ cp 还原（不用 git checkout）并核 md5 ⑨ 本轮三处 md5 追加进日志本身
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m5t8
cd "$ROOT" || exit 2
RID="$1"; TGT="$2"; MUT="$3"; FILTER="$4"
EV=.superpowers/sdd/2026-09-19-shell-simos-plan/t8-evidence
LOG="$EV/logs/$RID.log"
mkdir -p "$EV/logs" "$EV/mutants/orig"
ORIG_BACKUP="$EV/mutants/orig/$(basename "$TGT")"
[ -f "$ORIG_BACKUP" ] || { echo "FATAL: 缺原件备份 $ORIG_BACKUP"; exit 2; }

restore() { cp "$ORIG_BACKUP" "$TGT" 2>/dev/null || true; }
trap restore EXIT

# ① 干净世界：备份是权威原件，工作树此刻必须与它逐字节相同
ORIG_MD5=$(md5sum "$ORIG_BACKUP" | cut -d' ' -f1)
BEFORE=$(md5sum "$TGT" | cut -d' ' -f1)
if [ "$ORIG_MD5" != "$BEFORE" ]; then
  echo "FATAL: 工作树与原件备份不一致（脏世界）: orig=$ORIG_MD5 before=$BEFORE"; exit 2
fi

# ② + ③ 按目标类名推送变异体
cp "$MUT" "$TGT"
PUSHED=$(md5sum "$TGT" | cut -d' ' -f1)
[ "$PUSHED" != "$ORIG_MD5" ] || { echo "FATAL: 变异体与原件字节相同"; exit 2; }

CLASS=$(basename "${TGT%.java}")
CLS=$(echo "$FILTER" | cut -d'#' -f1)
# ④ 干净世界（产物面）：删陈旧 class 逼重编；删陈旧报告逼本轮重写（surefire 报告不还原，会留旧结论）
find simos-app/target/classes -name "$CLASS.class" -delete 2>/dev/null || true
rm -f simos-app/target/surefire-reports/*"$CLS"*.txt 2>/dev/null || true

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

# ⑧ 还原并核 md5
restore
trap - EXIT
RESTORED=$(md5sum "$TGT" | cut -d' ' -f1)
echo "worktree_restored=$RESTORED"
if [ "$RESTORED" != "$ORIG_MD5" ]; then echo "FATAL: 还原不等于原件"; exit 2; fi

# ⑨ 装置补记：本轮推送的字节写进日志本身（装置自指）
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
