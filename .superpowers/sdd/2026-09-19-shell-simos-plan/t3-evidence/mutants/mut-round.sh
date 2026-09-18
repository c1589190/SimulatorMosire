#!/usr/bin/env bash
# M5 T3 变异自证装置 —— 照 M4 task-17 的九道门禁骨架，适配 simos-app（两个目标：QueryService.java / Shell.java）。
# 用法: mut-round.sh <round-id> <target-file-rel-to-ROOT> <mutant-file-rel-to-ROOT> <test-filter> [test-class]
# 九道门禁：干净世界 md5 → 变异体字节不同 → 删陈旧 .class 与旧 surefire 报告 → COMPILATION ERROR=0 且 Tests run≥1
#          → surefire 报告 mtime 落在本轮 → 显示红点 → cp 还原并核 md5 → 三处 md5 追加进日志本身。
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m5t3
cd "$ROOT" || exit 2
RID="$1"; TGT="$2"; MUT="$3"; FILTER="$4"
CLS="${5:-QueryServiceTest}"
EV=.superpowers/sdd/2026-09-19-shell-simos-plan/t3-evidence
LOG="$EV/logs/$RID.log"
ORIG_DIR="$EV/mutants/orig"
mkdir -p "$EV/logs" "$ORIG_DIR"
ORIG_BACKUP="$ORIG_DIR/$(basename "$TGT")"
TARGET_CLASS=$(basename "${TGT%.java}")

# 门禁 1（干净世界）：备份不存在则从工作树取一份；存在则必须与工作树逐字节相同。
#   不变量：每一轮开跑前工作树 = 原件（上一轮若还原失败，这里当场作废）。
if [ ! -f "$ORIG_BACKUP" ]; then cp "$TGT" "$ORIG_BACKUP"; fi
ORIG_MD5=$(md5sum "$ORIG_BACKUP" | cut -d' ' -f1)
BEFORE=$(md5sum "$TGT" | cut -d' ' -f1)
if [ "$ORIG_MD5" != "$BEFORE" ]; then
  echo "FATAL: 备份($ORIG_MD5) 与工作树($BEFORE) 不一致 —— 世界不干净，本轮作废"; exit 2
fi

restore() { cp "$ORIG_BACKUP" "$TGT" 2>/dev/null || true; }
trap restore EXIT

# 门禁 2（字节不同）：把变异体**按目标类名**推到目标路径（白名单形式的落地）。
cp "$MUT" "$TGT"
PUSHED=$(md5sum "$TGT" | cut -d' ' -f1)
[ "$PUSHED" != "$ORIG_MD5" ] || { echo "FATAL: 变异体与原件字节相同"; exit 2; }

# 门禁 3（逼重编 + 清旧证据）：删掉规范名下的旧 .class 与旧的 surefire 报告，杜绝陈旧字节/陈旧红绿活到本轮。
find simos-app/target/classes -name "$TARGET_CLASS*.class" -delete 2>/dev/null || true
rm -f simos-app/target/surefire-reports/*."$CLS".txt 2>/dev/null || true

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

# 门禁 9（日志自指）：把本轮推送的字节与还原结果追加进日志本身。
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
