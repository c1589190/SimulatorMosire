#!/usr/bin/env bash
# M5 T9 变异装置（m1）——照 core T17 的九道门禁骨架，适配**静态资源**（非 Java，无 .class / 无编译期）。
# 用法: mut-round.sh <round-id> <target-rel> <mutant-rel> <test-filter>
# 门禁：干净世界 md5 → 变异体字节不同 → 删陈旧 surefire 报告逼本轮重写 → Tests run≥1 且 rc≠0
#       → 报告 mtime 落在本轮 → 显示红点（被保护的那条断言）→ cp 还原并核 md5 → 三处 md5 追加进日志本身。
# ★ 关键差别：目标不是 .java ⇒ 不能编译；判定字节由测试**读源码树**（见 WebuiAssetsTest javadoc），
#   故推送后 process-resources 的拷贝时序与本轮结论无关——本装置只需证明"落盘的是与原件字节不同的那份"。
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m5t9
cd "$ROOT" || exit 2
RID="$1"; TGT="$2"; MUT="$3"; FILTER="$4"
EV=.superpowers/sdd/2026-09-19-shell-simos-plan/t9-evidence
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

# 删陈旧 surefire 报告：本项目吃过"拿上次留下的红/绿当本轮结论"的亏（纪律形态 1）
rm -f simos-app/target/surefire-reports/*"$FILTER".txt 2>/dev/null || true
CLS=$(echo "$FILTER" | cut -d'#' -f1)

ROUND_START=$(date +%s)
./mvnw -pl simos-app -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest="$FILTER" test > "$LOG" 2>&1
RC=$?
TR=$(grep -cE "Tests run: [0-9]+" "$LOG")
REPORT=$(ls -t simos-app/target/surefire-reports/*."$CLS".txt 2>/dev/null | head -1)
RMTIME=$( [ -n "$REPORT" ] && stat -c %Y "$REPORT" || echo 0 )

echo "──── $RID ────"
echo "orig_md5=$ORIG_MD5  mutant_md5=$PUSHED  worktree_before=$BEFORE"
echo "rc=$RC  Tests_run_lines=$TR"
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

# 门禁：必须变红（rc≠0）且确实跑到 ≥1 条用例、报告落在本轮、还原成功。
if [ "$RC" -eq 0 ] || [ "$TR" -lt 1 ] || [ "$RMTIME" -lt "$ROUND_START" ] || [ "$RESTORED" != "$ORIG_MD5" ]; then
  echo "⇒ 本轮作废（rc/未跑到/报告 mtime/还原 之一不达标）"; exit 2
fi
echo "⇒ 杀死（红在上面那组方法/行），工作树已逐字节还原"
