#!/bin/bash
# 
#
# 用法: mut-round.sh <规范源路径> <变异体路径> <-Dtest=选择器> <日志路径> <期望变红的用例类名>
#
# 与 Task 12 那版的差别（本机实测所迫，非风格）：
#   1. **不 export JAVA_HOME**——本机没有 $HOME/.local/opt/jdk-21，export 会让 mvnw 直接起不来；
#      本机 java/mvnw 已跑 Java 21（实测 21.0.12），plain ./mvnw 即可。
#   2. 加一道**报告 mtime 门禁**（CLAUDE.md 形态 1 第五例）：开跑前清掉目标 surefire 报告，
#      跑完断言报告存在且 mtime 落在本轮之内——否则读到的是**上一轮留下的**红/绿。
#
# 纪律（CLAUDE.md 形态 1）：
#   1. 开跑前工作树必须是干净世界（md5 == 原件快照），否则作废这一轮；
#   2. 变异体必须与原件**字节不同**；
#   3. 按规范类名推送（变异体内容里的类名就是目标类，不是变异体文件名）；
#   4. 强制断言 COMPILATION ERROR 为 0（不为 0 就当场作废）；
#   5. 强制断言这一轮真的跑到了用例（Tests run 行 >= 1）；
#   6. 期望变红的那个类必须真的红；
#   7. 跑完回到开跑前的工作树状态（**绝不** git checkout -- <file>）。
set -u

SRC="$1"; MUT="$2"; SEL="$3"; LOG="$4"; EXPECT="$5"
EVID=".superpowers/sdd/2026-09-18-core-simos-plan/task-14-evidence"
BASE=$(basename "$SRC")
ORIG="$EVID/mutants/orig/$BASE"
REPORT="simos-core/target/surefire-reports/io.mosire.simos.core.$EXPECT.txt"

[ -f "$ORIG" ] || { echo "!! 缺原件快照: $ORIG"; exit 9; }

m_orig=$(md5sum "$ORIG" | cut -d' ' -f1)
m_mut=$(md5sum "$MUT"  | cut -d' ' -f1)
m_now=$(md5sum "$SRC"  | cut -d' ' -f1)

echo "== 门禁 1：开跑前必须是干净世界 =="
echo "orig=$m_orig  mutant=$m_mut  worktree_before=$m_now"
[ "$m_now" = "$m_orig" ] || { echo "!! 工作树不是原件，作废这一轮"; exit 9; }

echo "== 门禁 2：变异体必须与原件字节不同 =="
[ "$m_mut" != "$m_orig" ] || { echo "!! 变异体与原件字节相同——这一轮不算数"; exit 9; }

# ★ mtime 门禁的准备：清掉目标报告，让"读到的报告"只可能是本轮产出的
rm -f "$REPORT"

echo "== 白名单推送：只推 $BASE 这一条路径 =="
cp "$MUT" "$SRC"
echo "worktree_after_push=$(md5sum "$SRC" | cut -d' ' -f1)"

START=$(date +%s)
./mvnw -pl simos-core -am "$SEL" -Dsurefire.failIfNoSpecifiedTests=false test > "$LOG" 2>&1
RC=$?

# ★ 装置补记：把三处 md5 追加进日志，让每份证据**自指**（Task 12 第六例）。
{
  echo
  echo "===== 装置补记：本轮推送的正是以下字节 ====="
  echo "目标: $SRC"
  echo "orig_md5=$m_orig"
  echo "mutant_md5=$m_mut"
  echo "worktree_after_push=$(md5sum "$SRC" | cut -d' ' -f1)"
} >> "$LOG"

n_comp=$(grep -c 'COMPILATION ERROR' "$LOG")
n_run=$(grep -cE 'Tests run: [0-9]+' "$LOG")
echo "== 门禁 3/4：真的编过、真的跑到了 =="
echo "rc=$RC  COMPILATION_ERROR_lines=$n_comp  Tests_run_lines=$n_run"
[ "$n_comp" = "0" ] || { echo "!! 编都没编过 ⇒ 红的理由不是断言，作废这一轮"; cp "$ORIG" "$SRC"; exit 9; }
[ "$n_run" -ge 1 ] || { echo "!! 一条用例都没跑到 ⇒ 这不叫红，作废这一轮"; cp "$ORIG" "$SRC"; exit 9; }

echo "== 门禁 4b：本轮 surefire 报告必须存在且 mtime 落在本轮内 =="
if [ ! -f "$REPORT" ]; then echo "!! 缺本轮报告: $REPORT"; cp "$ORIG" "$SRC"; exit 9; fi
rep_mtime=$(stat -c %Y "$REPORT")
echo "report=$REPORT  mtime=$rep_mtime  round_start=$START"
[ "$rep_mtime" -ge "$START" ] || { echo "!! 报告 mtime 早于本轮开跑 ⇒ 读到的是陈旧报告，作废"; cp "$ORIG" "$SRC"; exit 9; }

echo "== 门禁 5：期望变红的类必须真的红 =="
hit=$(grep -E 'Tests run:.*(Failures: [1-9]|Errors: [1-9])' "$LOG" | grep -c "$EXPECT")
echo "命中 $EXPECT 的变红行数=$hit"
grep -E 'Tests run:.*(Failures: [1-9]|Errors: [1-9])' "$LOG" | sed 's/^/   /'
echo "--- 变红的用例方法 ---"
grep -E '^\[ERROR\]   [A-Za-z].*[:：]' "$LOG" | head -6 | sed 's/^/   /'

echo "== 回到开跑前的工作树状态 =="
cp "$ORIG" "$SRC"
m_restored=$(md5sum "$SRC" | cut -d' ' -f1)
echo "worktree_restored=$m_restored"
[ "$m_restored" = "$m_orig" ] || { echo "!! 没有还原干净"; exit 9; }

[ "$hit" -ge 1 ] || { echo "!! $EXPECT 没红 —— 这一轮是【存活】的变异体"; exit 8; }
echo "== 通过：变异被杀，工作树已还原 =="
