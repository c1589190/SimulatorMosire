#!/usr/bin/env bash
# M8 T11 变异装置（**Java 侧读路径**）—— m5 专用：变异 `ApiViews.incidentEdges`。
#
# 与前端装置（mut-run.sh）的差别，以及由此**新增**的三道关：
#   ① 变异的**源文件名 = 目标类名**（ApiViews.java）⇒ "按白名单推成目标类名"天然成立；
#      但 `.class` 是**另一样产物** ⇒ 必须先删陈旧 `.class`，再断言本轮**确实重编了**（形态 1）。
#   ② surefire 报告**也带状态**（M4 Task 11 的教训：`.txt` 留着上一轮的失败）⇒ 开跑前删掉它，
#      跑完**核对 mtime 落在本轮**再读数；不是本轮的就**不读**。
#   ③ 还原只还原**源**——`target/` 下的一切都不还原 ⇒ 还原后**必须重编一次**把干净字节码写回，
#      否则后面任何 e2e 都会跑在**变异体的 `.class`** 上。这是"装置产物污染下一轮"最凶的一种。
#
# 用法: mut-run-java.sh [m5]
# 退出码 0 = 被对应用例杀掉（红在对的用例上）；1 = 存活 / 红错地方 / 装置自身出错。
set -u
ROOT=/home/dev/SimulatorMosire/.claude/worktrees/m8t5
EV="$ROOT/.superpowers/sdd/2026-09-19-map-edit/t11-evidence"
MUT="$EV/mutants"; LOG="$MUT/logs"
SRC="$ROOT/simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java"
CLS_DIR="$ROOT/simos-app/target/classes/io/mosire/simos/app/gui"
REPORT="$ROOT/simos-app/target/surefire-reports/io.mosire.simos.app.gui.MapHexEdgesApiTest.txt"
TARGET="ApiViews.java"; ORIG="$MUT/orig-java/$TARGET"
TEST="MapHexEdgesApiTest"
EXPECT_TEST="mapHexOnASharedHexListsBothIncidentEdgesInEdgeRefOrder"
ID="${1:-m5}"
LOG1="$LOG/$ID-maven.log"; LOG2="$LOG/$ID-restore-compile.log"
MARK="$LOG/.$ID-round-mark"
mkdir -p "$MUT/orig-java" "$LOG"
cd "$ROOT" || exit 1

# ── ① 干净世界：src 必须**逐字节**等于快照（陈旧快照 / 上轮没还原干净都要当场作废）──────
if [ ! -f "$ORIG" ]; then cp "$SRC" "$ORIG"; fi
if ! cmp -s "$SRC" "$ORIG"; then
  echo "DEVICE FAIL(①): src/$TARGET 与 orig-java 快照不一致（陈旧快照或上轮未还原）"
  echo "  src_md5=$(md5sum "$SRC" | awk '{print $1}') orig_md5=$(md5sum "$ORIG" | awk '{print $1}')"
  exit 1
fi
cp "$ORIG" "$SRC"
ORIG_MD5=$(md5sum "$ORIG" | awk '{print $1}')
CLS_FILES=$(ls "$CLS_DIR"/ApiViews*.class 2>/dev/null | wc -l)
if [ -d "$CLS_DIR" ] && [ "$CLS_FILES" -ge 1 ]; then
  CLASS_MD5_BEFORE=$(cat "$CLS_DIR"/ApiViews*.class | md5sum | awk '{print $1}')
else
  CLASS_MD5_BEFORE="(no-classes)"
fi
echo "clean_world src_md5=$ORIG_MD5 class_files=$CLS_FILES classes_md5=$CLASS_MD5_BEFORE"
# ★ M7b 的假绿形状：glob 不匹配 ⇒ `cat` 无输出 ⇒ 聚合读到 `md5("")`=`d41d8cd9…` ⇒ "相等"退化成"空==空"。
#   故先断言**非空**，并在下面只把它当 WARN 用（判定从不依赖它）。
if [ "$CLS_FILES" -eq 0 ]; then
  echo "★ 注：干净世界里没有 ApiViews*.class（从未编译过）⇒ before/after 的聚合 md5 **无意义**，只看 fresh 计数"
fi

# ── ② 推变异体（锚点唯一性由 python 断言，不唯一则当场炸）────────────────────────────
python3 - "$SRC" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = '      if (edge.a().equals(coord) || edge.b().equals(coord)) {'
new = ('      // MUTANT m5：只认 a 端（"入射 = 两端任一"被砍成"只查起点"）。\n'
       '      if (edge.a().equals(coord)) {')
assert s.count(old) == 1, "m5 anchor not unique"
open(p, 'w').write(s.replace(old, new))
PY
MUT_MD5=$(md5sum "$SRC" | awk '{print $1}')
echo "mutation=$ID orig_md5=$ORIG_MD5 mutant_md5=$MUT_MD5 file=$TARGET expect_red_test=$EXPECT_TEST"
if [ -z "$MUT_MD5" ] || [ "$MUT_MD5" = "$ORIG_MD5" ]; then
  echo "DEVICE FAIL(②): mutant 与原件字节相同（或 md5 读到空）"; cp "$ORIG" "$SRC"; exit 1
fi

# ── ③ 清陈旧产物（`.class` 与 surefire 报告都会活到下一轮）+ 打本轮时间戳 ──────────────
rm -f "$CLS_DIR"/ApiViews*.class
rm -f "$REPORT"
: > "$MARK"; sleep 1

# ── ④ 编译 + 只跑这一条用例（-am 会把 -Dtest 带到每个模块 ⇒ 必须配 failIfNoSpecifiedTests）──
./mvnw -pl simos-app -am -Dtest="$TEST" -Dsurefire.failIfNoSpecifiedTests=false test > "$LOG1" 2>&1
MVN_RC=$?
CE=$(grep -c "COMPILATION ERROR" "$LOG1")
echo "mvn_rc=$MVN_RC compilation_error_lines=$CE (CE 必须为 0，否则这一轮作废)"

# ── ⑤ "本轮的字节真的被编译/跑了吗"（形态 1 + 形态 5）──────────────────────────────
FRESH=0
if [ -d "$CLS_DIR" ]; then FRESH=$(find "$CLS_DIR" -name 'ApiViews*.class' -newer "$MARK" | wc -l); fi
echo "fresh_class_files=$FRESH (>=1 才算本轮真的重编了)"
REPORT_IS_THIS_ROUND=0
if [ -f "$REPORT" ] && [ "$REPORT" -nt "$MARK" ]; then REPORT_IS_THIS_ROUND=1; fi
echo "surefire_report=$REPORT is_this_round=$REPORT_IS_THIS_ROUND"
if [ "$REPORT_IS_THIS_ROUND" = "1" ]; then
  echo "--- surefire 原文（前 30 行）---"
  sed -n '1,30p' "$REPORT"
else
  echo "（报告缺失或不是本轮产物 ⇒ **不读它的数字**；先怀疑自己的读取，别先怀疑被测物）"
fi
echo "--- Maven 日志里的用例结果与失败明细 ---"
grep -E "Tests run:.*Failures:|<<< FAILURE!|^\[ERROR\]   " "$LOG1" | head -20

# ── ⑥ 逐字节还原（源）＋ **重编**把干净字节码写回 ──────────────────────────────────
cp "$ORIG" "$SRC"
RESTORED_MD5=$(md5sum "$SRC" | awk '{print $1}')
echo "restored_md5=$RESTORED_MD5 orig_md5=$ORIG_MD5"
if [ -z "$RESTORED_MD5" ] || [ "$RESTORED_MD5" != "$ORIG_MD5" ]; then
  echo "DEVICE FAIL(⑥): 源还原不一致"; exit 1
fi
./mvnw -q -pl simos-app -am -DskipTests compile > "$LOG2" 2>&1
RC2=$?
CLS_FILES_AFTER=$(ls "$CLS_DIR"/ApiViews*.class 2>/dev/null | wc -l)
if [ "$CLS_FILES_AFTER" -ge 1 ]; then
  CLASS_MD5_AFTER=$(cat "$CLS_DIR"/ApiViews*.class | md5sum | awk '{print $1}')
else
  CLASS_MD5_AFTER="(no-classes)"
fi
echo "restore_compile_rc=$RC2 class_files_after=$CLS_FILES_AFTER classes_md5_after=$CLASS_MD5_AFTER classes_md5_before=$CLASS_MD5_BEFORE"
if [ "$CLS_FILES_AFTER" -eq 0 ]; then
  echo "★★ DEVICE FAIL(⑥b)：还原后**一个 .class 都没有** ⇒ 干净字节码没写回来，后面任何 e2e 都会跑在缺类的树上"
  exit 1
fi
if [ "$CLASS_MD5_AFTER" != "$CLASS_MD5_BEFORE" ]; then
  echo "★ WARN：重编后的 .class 聚合 md5 与干净世界不同（javac 非确定性 or 类集变了）——只提示，判定从不依赖它"
fi

# ── 装置自指（形态 6）：这一轮跑的是哪份字节，写进日志本身 ───────────────────────────
{
  echo "--- device self-record ($ID) ---"
  echo "orig_md5=$ORIG_MD5 mutant_md5=$MUT_MD5 restored_md5=$RESTORED_MD5"
  echo "classes_md5_before=$CLASS_MD5_BEFORE classes_md5_after=$CLASS_MD5_AFTER restore_compile_rc=$RC2"
  echo "mvn_rc=$MVN_RC compilation_error_lines=$CE fresh_class_files=$FRESH report_this_round=$REPORT_IS_THIS_ROUND"
  echo "expect_red_test=$EXPECT_TEST"
} >> "$LOG1"

# ── ⑦ 判定 ────────────────────────────────────────────────────────────────
OK=1
[ "$MVN_RC" != "0" ] || { OK=0; echo "NOT-KILLED: mvn_rc=0（用例全绿 ⇒ 变异体存活）"; }
[ "$CE" = "0" ] || { OK=0; echo "ROUND VOID: 有 $CE 行 COMPILATION ERROR ⇒ 那是编译错误式的红，不算杀"; }
[ "$FRESH" -ge 1 ] || { OK=0; echo "ROUND VOID: .class 没被本轮重编（跑的可能不是这份字节）"; }
[ "$REPORT_IS_THIS_ROUND" = "1" ] || { OK=0; echo "ROUND VOID: surefire 报告不是本轮产物 ⇒ 不给结论"; }
if [ "$REPORT_IS_THIS_ROUND" = "1" ]; then
  grep -qE "Tests run: [0-9]+, Failures: [1-9]" "$REPORT" || { OK=0; echo "NOT-KILLED: 报告里没有 Failures>=1"; }
fi
RED_LINE=$(grep -m1 -E "^\[ERROR\]   $TEST\.$EXPECT_TEST" "$LOG1" || true)
echo "red_line=$RED_LINE"
[ -n "$RED_LINE" ] || { OK=0; echo "NOT-KILLED: 预期红点 $EXPECT_TEST 未出现在失败明细里（红在别处 = 没杀到）"; }
if [ "$OK" = "1" ]; then
  echo "MUTATION $ID: KILLED (expected red $EXPECT_TEST observed)"
  exit 0
fi
echo "MUTATION $ID: SURVIVED / unexpected"
exit 1
