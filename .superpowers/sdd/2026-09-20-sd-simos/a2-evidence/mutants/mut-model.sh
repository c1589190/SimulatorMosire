#!/usr/bin/env bash
# A2 数据模型变异装置（九道门禁）
# 每一轮：干净世界（orig 备份）→ 生成变异体（md5 自证与 orig 不同）→ 白名单推到目标文件名 →
# 清陈旧 .class 与 surefire 报告 → 跑定向用例 → 断言 COMPILATION ERROR=0 且 Tests run≥1 →
# 断言红点落在被保护断言（surefire 报告里出现该测试方法名）→ cp 逐字节还原并断言 md5 == orig →
# 日志自指（把本轮 orig/mutant/restored md5 追加进日志；核对脚本先断言聚合 md5 非空）。
set -u
WT="/home/cna/SimulatorMosire/.claude/worktrees/sda1a2"
SRC="$WT/simos-sd/src/main/java/io/mosire/simos/sd/model"
TESTS="$WT/simos-sd/target/surefire-reports"
EV="$WT/.superpowers/sdd/2026-09-20-sd-simos/a2-evidence"
MUT="$EV/mutants"
LOG="$EV/model-mutants.log"
mkdir -p "$MUT/orig" "$MUT/m1" "$MUT/m2" "$MUT/m3"
: > "$LOG"

md5() { md5sum "$1" | awk '{print $1}'; }

# ---------- 变异体生成（干净世界：从 orig 现取现改） ----------
cp "$SRC/OutcomeOption.java" "$MUT/orig/OutcomeOption.java"
cp "$SRC/OutcomeTable.java"  "$MUT/orig/OutcomeTable.java"
cp "$SRC/CasualtyDelta.java" "$MUT/orig/CasualtyDelta.java"

python3 - "$MUT/orig/OutcomeOption.java" "$MUT/m1/OutcomeOption.java" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8").read()
old = ("    if (weight <= 0) {\n"
       "      throw new IllegalArgumentException(\"weight 必须 > 0: \" + weight);\n"
       "    }\n")
assert text.count(old) == 1, text.count(old)
open(dst, "w", encoding="utf-8").write(text.replace(old, "    // MUTANT: weight > 0 校验已删\n", 1))
PY

python3 - "$MUT/orig/OutcomeTable.java" "$MUT/m2/OutcomeTable.java" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8").read()
old = ("    if (options.isEmpty()) {\n"
       "      throw new IllegalArgumentException(\"OutcomeTable.options 不得为空（N2）\");\n"
       "    }\n")
assert text.count(old) == 1, text.count(old)
open(dst, "w", encoding="utf-8").write(text.replace(old, "    // MUTANT: 空表校验已删\n", 1))
PY

python3 - "$MUT/orig/CasualtyDelta.java" "$MUT/m3/CasualtyDelta.java" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8").read()
old = ("      if (entry.getValue() == null || entry.getValue() >= 0) {\n"
       "        throw new IllegalArgumentException(\n"
       "            \"equipment 的值必须为负: \" + entry.getKey() + \"=\" + entry.getValue());\n"
       "      }\n")
assert text.count(old) == 1, text.count(old)
open(dst, "w", encoding="utf-8").write(text.replace(old, "      // MUTANT: equipment 负值校验已删\n", 1))
PY

# 聚合 md5（先断言非空，再使用）
AGG=$(md5sum "$MUT"/m1/*.java "$MUT"/m2/*.java "$MUT"/m3/*.java | md5sum | awk '{print $1}')
if [ -z "$AGG" ] || [ "$AGG" = "d41d8cd98f00b204e9800998ecf8427e" ]; then
  echo "ABORT: 聚合 md5 读到空串/空聚合: '$AGG'" | tee -a "$LOG"; exit 9
fi
echo "aggregate_mutant_md5=$AGG" | tee -a "$LOG"

run_round() {
  local name="$1" cls="$2" method="$3" orig="$4" mutant="$5"
  local orig_md5 mutant_md5 pushed_md5 restored_md5
  orig_md5=$(md5 "$orig"); mutant_md5=$(md5 "$mutant")
  if [ "$mutant_md5" = "$orig_md5" ]; then
    echo "ROUND $name ABORT: mutant identical to orig" | tee -a "$LOG"; return 2
  fi
  cp "$mutant" "$SRC/$cls.java"
  pushed_md5=$(md5 "$SRC/$cls.java")
  if [ "$pushed_md5" != "$mutant_md5" ]; then
    echo "ROUND $name ABORT: push mismatch" | tee -a "$LOG"; return 2
  fi
  # 清陈旧 .class 与 surefire 报告
  rm -f "$WT/simos-sd/target/classes/io/mosire/simos/sd/model/$cls.class"
  rm -f "$TESTS"/*SdModelTest*
  local mark="$MUT/$name/.mark"; touch "$mark"
  local rlog="$MUT/$name/run.log"
  ( cd "$WT" && ./mvnw -pl simos-sd -am -Dtest=SdModelTest -Dsurefire.failIfNoSpecifiedTests=false test ) > "$rlog" 2>&1
  local rc=$?
  # 还原（逐字节 cp，绝不 git checkout --）
  cp "$MUT/orig/$cls.java" "$SRC/$cls.java"
  restored_md5=$(md5 "$SRC/$cls.java")
  local report="$TESTS/io.mosire.simos.sd.model.SdModelTest.txt"
  local compile_err tests_run red_hit report_fresh report_md5
  compile_err=$(grep -c "COMPILATION ERROR" "$rlog")
  tests_run=$(grep -c "Tests run:" "$rlog")
  red_hit=0
  report_fresh=no
  report_md5=none
  if [ -f "$report" ]; then report_md5=$(md5 "$report"); fi
  if [ -f "$report" ] && [ "$report" -nt "$mark" ]; then report_fresh=yes; fi
  if [ -f "$report" ] && grep -q "$method" "$report"; then red_hit=1; fi
  {
    echo "ROUND $name cls=$cls method=$method rc=$rc"
    echo "ROUND $name orig_md5=$orig_md5 mutant_md5=$mutant_md5 pushed_md5=$pushed_md5 restored_md5=$restored_md5"
    echo "ROUND $name report_md5=$report_md5 report_fresh=$report_fresh COMPILATION_ERROR=$compile_err Tests_run_lines=$tests_run red_on_protected=$red_hit"
  } | tee -a "$LOG"
  local fail=0
  [ "$restored_md5" = "$orig_md5" ] || { echo "ROUND $name FAIL: restore mismatch" | tee -a "$LOG"; fail=1; }
  [ "$rc" -ne 0 ] || { echo "ROUND $name FAIL: test did not fail" | tee -a "$LOG"; fail=1; }
  [ "$compile_err" -eq 0 ] || { echo "ROUND $name FAIL: compilation error (round void)" | tee -a "$LOG"; fail=1; }
  [ "$tests_run" -ge 1 ] || { echo "ROUND $name FAIL: tests never ran" | tee -a "$LOG"; fail=1; }
  [ "$report_fresh" = "yes" ] || { echo "ROUND $name FAIL: surefire report not from this round" | tee -a "$LOG"; fail=1; }
  [ "$red_hit" -eq 1 ] || { echo "ROUND $name FAIL: red is not the protected assertion" | tee -a "$LOG"; fail=1; }
  if [ "$fail" -eq 0 ]; then
    echo "ROUND $name OK: red on $method, restored byte-exact" | tee -a "$LOG"
  fi
  return "$fail"
}

run_round m1 OutcomeOption outcomeOptionRejectsNonPositiveWeight "$MUT/orig/OutcomeOption.java" "$MUT/m1/OutcomeOption.java"
run_round m2 OutcomeTable  outcomeTableRejectsEmptyOptions      "$MUT/orig/OutcomeTable.java"  "$MUT/m2/OutcomeTable.java"
run_round m3 CasualtyDelta casualtyDeltaRejectsNonNegativeEquipment "$MUT/orig/CasualtyDelta.java" "$MUT/m3/CasualtyDelta.java"

echo "== post-restore md5 ==" | tee -a "$LOG"
echo "post_OutcomeOption_md5=$(md5 "$SRC/OutcomeOption.java")" | tee -a "$LOG"
echo "post_OutcomeTable_md5=$(md5 "$SRC/OutcomeTable.java")" | tee -a "$LOG"
echo "post_CasualtyDelta_md5=$(md5 "$SRC/CasualtyDelta.java")" | tee -a "$LOG"
