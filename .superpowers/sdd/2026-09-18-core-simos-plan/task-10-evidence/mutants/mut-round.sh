#!/bin/bash
# Task 8 的变异轮装置。
# 用法: mut-round.sh <规范源路径> <变异体路径> <-Dtest=选择器> <日志路径> <期望变红的用例名>
# 纪律（CLAUDE.md 形态 1）：每一轮都要自证"落盘的确实是与原件字节不同的那份"、"这一轮真的跑了"，
# 跑完回到开跑前的工作树状态（绝不 git checkout -- <file>）。
set -u
SRC="$1"; MUT="$2"; SEL="$3"; LOG="$4"; EXPECT="$5"
EVID=".superpowers/sdd/2026-09-18-core-simos-plan/task-10-evidence"
mkdir -p "$EVID/mutants/orig"
BASE=$(basename "$SRC")
ORIG="$EVID/mutants/orig/$BASE"
[ -f "$ORIG" ] || cp "$SRC" "$ORIG"

export JAVA_HOME="$HOME/.local/opt/jdk-21"
export PATH="$JAVA_HOME/bin:$HOME/.local/opt/maven/bin:$PATH"

m_orig=$(md5sum "$ORIG" | cut -d' ' -f1)
m_mut=$(md5sum "$MUT" | cut -d' ' -f1)
m_now=$(md5sum "$SRC" | cut -d' ' -f1)
echo "== 装置自证：开跑前工作树必须是干净世界 =="
echo "orig=$m_orig"
echo "mutant=$m_mut"
echo "worktree_before=$m_now"
if [ "$m_now" != "$m_orig" ]; then echo "!! 工作树不是原件，作废这一轮"; exit 9; fi
if [ "$m_mut" = "$m_orig" ]; then echo "!! 变异体与原件字节相同 —— javac 编的可能还是原件，这一轮不算数"; exit 9; fi

echo "== 白名单推送：只推 $SRC 这一条路径 =="
cp "$MUT" "$SRC"
echo "worktree_after_push=$(md5sum "$SRC" | cut -d' ' -f1)"

./mvnw -pl simos-core -am "$SEL" -Dsurefire.failIfNoSpecifiedTests=false test > "$LOG" 2>&1
RC=$?

echo "== 装置自证：这一轮真的跑到断言了 =="
echo "rc=$RC"
echo "COMPILATION_ERROR_lines=$(grep -c 'COMPILATION ERROR' "$LOG")"
echo "Tests_run_lines=$(grep -cE 'Tests run: [0-9]+' "$LOG")"

echo "== 期望变红的用例（须落在被保护的那一行上） =="
grep -nE "\[ERROR\] .*${EXPECT}" "$LOG" | head -4
grep -nE "Tests run:.*(Failures: [1-9]|Errors: [1-9])" "$LOG" | head -4

echo "== 回到开跑前的工作树状态 =="
cp "$ORIG" "$SRC"
m_restored=$(md5sum "$SRC" | cut -d' ' -f1)
echo "worktree_restored=$m_restored"
if [ "$m_restored" != "$m_orig" ]; then echo "!! 没有还原干净"; exit 9; fi
echo "== 还原成功 =="
