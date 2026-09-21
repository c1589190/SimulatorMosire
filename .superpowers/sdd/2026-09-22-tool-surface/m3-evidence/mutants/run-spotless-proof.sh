#!/usr/bin/env bash
# 记录「spotless:apply 只动了注释/空白」这一条结论的**现场留痕**（自指：把两份字节的 md5 写进日志）。
set -uo pipefail
WT=/home/dev/SimulatorMosire/.claude/worktrees/ts+m1
EV="$WT/.superpowers/sdd/2026-09-22-tool-surface/m3-evidence"
MUT="$EV/mutants"
LOG="$EV/logs/spotless-comment-only.attempt1.log"
TARGET="$WT/simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java"
md5of() { md5sum "$1" | cut -d' ' -f1; }

{
  echo "════════ spotless 改动面证明：只动注释/空白 ════════"
  echo "跑的时刻: $(date '+%F %T')"
  echo "checker_md5  = $(md5of "$MUT/strip-compare.py")"
  echo "selftest_md5 = $(md5of "$MUT/strip-compare-selftest.py")"
  echo "被验对象     = $TARGET"
  echo "  pre-spotless md5 = $(md5of "$MUT/pre-spotless/SimosToolsTest.java")"
  echo "  现在         md5 = $(md5of "$TARGET")"
  echo
  echo "──── ① checker 的双侧自证（4 个真样本；只证一侧等于没证）────"
  python3 "$MUT/strip-compare-selftest.py"
  echo
  echo "──── ② 真实比对：pre-spotless vs 现在 ────"
  python3 "$MUT/strip-compare.py" "$MUT/pre-spotless/SimosToolsTest.java" "$TARGET"
  rc=$?
  echo
  echo "真实比对 rc=$rc （0=IDENTICAL，即只动注释/空白）"
} > "$LOG" 2>&1

echo "log: $LOG"
echo "log_md5=$(md5of "$LOG")"
tail -n 12 "$LOG"
