#!/usr/bin/env bash
# M7 T2 变异轮装置——照 M4 Task 17 / T1 的九道门禁骨架。
# 与 T1 的差别：本任务的变异目标是**静态资源**（.js/.html），不是 .java。
#   ⇒ 无「推成目标类名」「清陈旧 .class」两步（测试直接读 src/main/resources/webui/ 的字节）；
#     其余门禁（干净世界 md5 / 变异体字节不同 / COMPILATION ERROR=0 / Tests run≥1 /
#     surefire 报告 mtime 落轮内 / 红点落被保护断言 / cp 逐字节还原 / 日志自指）一条不少。
# 用法: mut-round.sh <round-id> <target-file-rel> <mutant-file-rel> <module> <test-filter>
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m7t2
cd "$ROOT" || exit 2
RID="$1"; TGT="$2"; MUT="$3"; MODULE="$4"; FILTER="$5"
EV=.superpowers/sdd/2026-09-19-webui-plan/t2-evidence
LOG="$EV/logs/$RID.log"
mkdir -p "$EV/logs" "$EV/mutants/orig"
ORIG_BACKUP="$EV/mutants/orig/$(basename "$TGT")"

restore() { cp "$ORIG_BACKUP" "$TGT" 2>/dev/null || true; }
trap restore EXIT

# 门禁 2：干净世界——备份必须与工作树当前字节一致（不一致说明上一轮没还原干净）
ORIG_MD5=$(md5sum "$TGT" | cut -d' ' -f1)
BEFORE=$(md5sum "$TGT" | cut -d' ' -f1)
if [ "$ORIG_MD5" != "$BEFORE" ]; then echo "FATAL: 备份与工作树不一致"; exit 2; fi

# 门禁 3：变异体字节不同（资源按路径推入；无类名映射）
cp "$MUT" "$TGT"
PUSHED=$(md5sum "$TGT" | cut -d' ' -f1)
[ "$PUSHED" != "$ORIG_MD5" ] || { echo "FATAL: 变异体与原件字节相同"; exit 2; }

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
