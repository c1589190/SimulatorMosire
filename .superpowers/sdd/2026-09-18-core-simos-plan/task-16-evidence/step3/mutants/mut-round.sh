#!/bin/bash
# M4 Task 16 Step 3 变异轮装置（自 task-14-evidence/mutants/mut-round.sh 移植）
#
# 用法: mut-round.sh <变异体路径> <日志路径>
#
# 本任务的两个变异体都作用在同一个目标类 UnitTimeParticipant（simos-unit 的 main 源码），
# 而期望变红的是 simos-core 的 RealmEffectEndToEndTest —— 故固定：
#   SRC  = simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitTimeParticipant.java
#   SEL  = -Dtest=RealmEffectEndToEndTest
#   EXPECT = RealmEffectEndToEndTest
#
# 纪律（CLAUDE.md 形态 1）：
#   1. 开跑前工作树必须是干净世界（md5 == 原件快照），否则作废这一轮；
#   2. 变异体必须与原件**字节不同**；
#   3. 按规范类名推送（变异体内容里的类名就是目标类，不是变异体文件名）；
#   4. 推入前清掉目标 .class，逼 Maven 真重编（陈旧 .class 会活到下一轮）；
#   5. 强制断言 COMPILATION ERROR 为 0（不为 0 就当场作废）；
#   6. 强制断言这一轮真的跑到了用例（Tests run 行 >= 1）；
#   7. surefire 报告必须存在且 mtime 落在本轮之内（不许读陈旧报告）；
#   8. 期望变红的类必须真的红；
#   9. 跑完回到开跑前的字节（**绝不** git checkout -- <file>）。
set -u

MUT="$1"; LOG="$2"
SRC="simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitTimeParticipant.java"
EVID=".superpowers/sdd/2026-09-18-core-simos-plan/task-16-evidence/step3"
ORIG="$EVID/mutants/orig/UnitTimeParticipant.java"
CLS="simos-unit/target/classes/io/mosire/simos/unit/spi/UnitTimeParticipant.class"
REPORT="simos-core/target/surefire-reports/io.mosire.simos.core.RealmEffectEndToEndTest.txt"
EXPECT="RealmEffectEndToEndTest"

[ -f "$ORIG" ] || { echo "!! 缺原件快照: $ORIG"; exit 9; }
[ -f "$MUT" ]  || { echo "!! 缺变异体: $MUT"; exit 9; }

m_orig=$(md5sum "$ORIG" | cut -d' ' -f1)
m_mut=$(md5sum "$MUT"  | cut -d' ' -f1)
m_now=$(md5sum "$SRC"  | cut -d' ' -f1)

echo "== 门禁 1：开跑前必须是干净世界 =="
echo "orig=$m_orig  mutant=$m_mut  worktree_before=$m_now"
[ "$m_now" = "$m_orig" ] || { echo "!! 工作树不是原件，作废这一轮"; exit 9; }

echo "== 门禁 2：变异体必须与原件字节不同 =="
[ "$m_mut" != "$m_orig" ] || { echo "!! 变异体与原件字节相同——这一轮不算数"; exit 9; }

# mtime 门禁的准备：清掉目标报告与旧 .class
rm -f "$REPORT" "$CLS"

echo "== 白名单推送：只推 $SRC 这一条路径 =="
cp "$MUT" "$SRC"
m_pushed=$(md5sum "$SRC" | cut -d' ' -f1)
echo "worktree_after_push=$m_pushed"
[ "$m_pushed" = "$m_mut" ] || { echo "!! 推送后的字节 != 变异体，作废"; cp "$ORIG" "$SRC"; exit 9; }

START=$(date +%s)
./mvnw -pl simos-core -am -Dtest=RealmEffectEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false test > "$LOG" 2>&1
RC=$?

# ★ 装置补记：把本轮的三处 md5 追加进日志，让每份证据**自指**（Task 12 第六例）。
{
  echo
  echo "===== 装置补记：本轮推送的正是以下字节 ====="
  echo "目标: $SRC"
  echo "orig_md5=$m_orig"
  echo "mutant_md5=$m_mut"
  echo "worktree_after_push=$m_pushed"
} >> "$LOG"

n_comp=$(grep -c 'COMPILATION ERROR' "$LOG")
n_run=$(grep -cE 'Tests run: [0-9]+' "$LOG")
echo "== 门禁 3/4：真的编过、真的跑到了 =="
echo "rc=$RC  COMPILATION_ERROR_lines=$n_comp  Tests_run_lines=$n_run"
if [ "$n_comp" != "0" ]; then echo "!! 编都没编过 ⇒ 红的理由不是断言，作废这一轮"; cp "$ORIG" "$SRC"; exit 9; fi
if [ "$n_run" -lt 1 ]; then echo "!! 一条用例都没跑到 ⇒ 这不叫红，作废这一轮"; cp "$ORIG" "$SRC"; exit 9; fi

echo "== 门禁 4b：本轮 surefire 报告必须存在且 mtime 落在本轮内 =="
if [ ! -f "$REPORT" ]; then echo "!! 缺本轮报告: $REPORT"; cp "$ORIG" "$SRC"; exit 9; fi
rep_mtime=$(stat -c %Y "$REPORT")
echo "report=$REPORT  mtime=$rep_mtime  round_start=$START"
if [ "$rep_mtime" -lt "$START" ]; then echo "!! 报告 mtime 早于本轮开跑 ⇒ 读到的是陈旧报告，作废"; cp "$ORIG" "$SRC"; exit 9; fi

# 目标类 .class 必须晚于本轮开跑（证明 simos-unit 真的重编了）
if [ -f "$CLS" ]; then
  cls_mtime=$(stat -c %Y "$CLS")
  echo "target_class=$CLS  mtime=$cls_mtime"
  [ "$cls_mtime" -ge "$START" ] || { echo "!! 目标 .class 旧于本轮 ⇒ 没重编，作废"; cp "$ORIG" "$SRC"; exit 9; }
else
  echo "!! 目标 .class 不存在 ⇒ 编译未产出它，作废"; cp "$ORIG" "$SRC"; exit 9
fi

echo "== 门禁 5：期望变红的类必须真的红 =="
hit=$(grep -E 'Tests run:.*(Failures: [1-9]|Errors: [1-9])' "$LOG" | grep -c "$EXPECT")
echo "命中 $EXPECT 的变红行数=$hit"
grep -E 'Tests run:.*(Failures: [1-9]|Errors: [1-9])' "$LOG" | sed 's/^/   /'
echo "--- 变红的用例方法 ---"
grep -E '^\[ERROR\]   [A-Za-z].*[:：]' "$LOG" | head -8 | sed 's/^/   /'

echo "== 回到开跑前的工作树状态 =="
cp "$ORIG" "$SRC"
m_restored=$(md5sum "$SRC" | cut -d' ' -f1)
echo "worktree_restored=$m_restored"
[ "$m_restored" = "$m_orig" ] || { echo "!! 没有还原干净"; exit 9; }

if [ "$hit" -lt 1 ]; then echo "!! $EXPECT 没红 —— 这一轮是【存活】的变异体"; exit 8; fi
echo "== 通过：变异被杀，工作树已还原 =="
