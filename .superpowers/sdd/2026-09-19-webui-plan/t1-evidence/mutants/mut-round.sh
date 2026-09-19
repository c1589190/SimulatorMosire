#!/usr/bin/env bash
# M7 T1 变异轮装置——照 M4 Task 17 的九道门禁骨架，ROOT 指向本 worktree，module/test 参数化。
# 用法: mut-round.sh <round-id> <target-file-rel> <mutant-file-rel> <module> <test-filter>
# 九道门禁：干净世界 md5 → 变异体字节不同 → 按白名单推成目标类名 → 删陈旧 .class 逼重编
#       → COMPILATION ERROR=0 且 Tests run≥1 → surefire 报告 mtime 落在本轮 → 显示红点
#       → cp 逐字节还原并核 md5 → 三处 md5 追加进日志本身。
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m7t1
cd "$ROOT" || exit 2
RID="$1"; TGT="$2"; MUT="$3"; MODULE="$4"; FILTER="$5"
EV=.superpowers/sdd/2026-09-19-webui-plan/t1-evidence
LOG="$EV/logs/$RID.log"
mkdir -p "$EV/logs" "$EV/mutants/orig"
ORIG_BACKUP="$EV/mutants/orig/$(basename "$TGT")"

restore() { cp "$ORIG_BACKUP" "$TGT" 2>/dev/null || true; }
trap restore EXIT

# 门禁 2：干净世界——备份必须与工作树当前字节一致（不一致说明上一轮没还原干净）
ORIG_MD5=$(md5sum "$TGT" | cut -d' ' -f1)
BEFORE=$(md5sum "$TGT" | cut -d' ' -f1)
if [ "$ORIG_MD5" != "$BEFORE" ]; then echo "FATAL: 备份与工作树不一致"; exit 2; fi

# 门禁 3：白名单——按目标路径推入，落盘即目标类名（不是按变异文件名，避免"红成编译错误"）
cp "$MUT" "$TGT"
PUSHED=$(md5sum "$TGT" | cut -d' ' -f1)
[ "$PUSHED" != "$ORIG_MD5" ] || { echo "FATAL: 变异体与原件字节相同"; exit 2; }

# 门禁 4：清陈旧 .class
CLASS=$(basename "${TGT%.java}")
find "$MODULE/target/classes" -name "$CLASS.class" -delete 2>/dev/null || true
# 夹具修：surefire 报告按全限定名落盘，用 glob 取该类最新报告；读不到就当场作废
CLS=$(echo "$FILTER" | cut -d'#' -f1)

ROUND_START=$(date +%s)
./mvnw -pl "$MODULE" -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest="$FILTER" test > "$LOG" 2>&1
RC=$?
CE=$(grep -c "COMPILATION ERROR" "$LOG")
TR=$(grep -cE "Tests run: [0-9]+" "$LOG")
REPORT=$(ls -t "$MODULE"/target/surefire-reports/*."$CLS".txt 2>/dev/null | head -1)
RMTIME=$( [ -n "$REPORT" ] && stat -c %Y "$REPORT" || echo 0 )

echo "──── $RID ────"
echo "orig_md5=$ORIG_MD5  mutant_md5=$PUSHED  worktree_before=$BEFORE"
echo "rc=$RC  COMPILATION_ERROR_lines=$CE  Tests_run_lines=$TR"
echo "surefire_report=$REPORT  mtime=$RMTIME  round_start=$ROUND_START"
echo "--- 变红的方法/行 ---"
grep -E "^\[ERROR\]   [A-Za-z]+Test\." "$LOG" | head -6
grep -E "^\[ERROR\] Tests run:" "$LOG" | head -2

# 门禁 8：cp 逐字节还原（绝不 git checkout --）
restore
trap - EXIT
RESTORED=$(md5sum "$TGT" | cut -d' ' -f1)
echo "worktree_restored=$RESTORED"
if [ "$RESTORED" != "$ORIG_MD5" ]; then echo "FATAL: 还原不等于原件"; exit 2; fi

# 门禁 9：装置自指——把本轮推送的字节写进日志本身
{
  echo ""
  echo "===== 装置补记（$RID）：本轮推送的正是以下字节 ====="
  echo "目标: $TGT"
  echo "orig_md5=$ORIG_MD5"
  echo "mutant_md5=$PUSHED"
  echo "worktree_restored=$RESTORED"
} >> "$LOG"

# 门禁 5/6：编译零错、真跑到、报告 mtime 落轮内
if [ "$RC" -eq 0 ] || [ "$CE" -ne 0 ] || [ "$TR" -lt 1 ] || [ "$RMTIME" -lt "$ROUND_START" ] || [ "$RESTORED" != "$ORIG_MD5" ]; then
  echo "⇒ 本轮作废（rc/编译/未跑到/报告 mtime/还原 之一不达标）"; exit 2
fi
echo "⇒ 杀死（红在上面那组方法/行），工作树已逐字节还原"
