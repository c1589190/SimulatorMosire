#!/usr/bin/env bash
# java-round.sh —— T4 Java 变异轮装置。九道门禁：
#   ① 干净世界（源 md5 == orig 备份）② 变异体字节不同 ③ 推到**规范名目标文件**（文件名即类名）
#   ④ 清陈旧 .class + surefire-reports ⑤ COMPILATION ERROR=0 且 Tests run>=1（**基线也证跑到了**）
#   ⑥ surefire 报告 mtime 落本轮 ⑦ 红点落被保护断言（expect 正则）⑧ cp 逐字节还原（绝不 git checkout）
#   ⑨ 日志自指（md5 写进日志；读取处先断言非空）
# 用法: java-round.sh <round-id> <target-relpath> <modules> <tests> <expect-regex>
set -u
WT=/home/cna/SimulatorMosire/.claude/worktrees/wsf-t4
EV=$WT/.superpowers/sdd/2026-09-21-webui-stage-fix/t4-evidence
ORIG=$EV/mutants/orig
LOGS=$EV/mutants/logs
RID="${1:?}"; TGT_REL="${2:?}"; MODULES="${3:?}"; TESTS="${4:?}"; EXPECT_RE="${5:?}"
SRC="$WT/$TGT_REL"
BASE=$(basename "$TGT_REL")
ORIGF="$ORIG/$BASE"
LOG="$LOGS/${RID}.log"
: > "$LOG"
exec > >(tee -a "$LOG") 2>&1
say() { echo "$@"; }
req() { [ -n "${1:-}" ] || { say "ABSENT_VALUE abort ($2)"; exit 9; }; }
md5() { md5sum "$1" | cut -d' ' -f1; }

restore() { cp "$ORIGF" "$SRC" 2>/dev/null || true; }
trap restore EXIT

say "===== T4 Java 变异轮 $RID ====="
say "target=$TGT_REL modules=$MODULES tests=$TESTS expect=$EXPECT_RE"

[ -f "$ORIGF" ] || { say "FATAL: 缺原件备份"; exit 2; }
cur=$(md5 "$SRC"); orig=$(md5 "$ORIGF")
req "$cur" cur; req "$orig" orig
say "orig_md5=$orig"; say "clean_world_md5=$cur"
[ "$cur" = "$orig" ] || { say "NOT_CLEAN_WORLD abort"; exit 3; }

run_mvn() {
  local extra="$1" out="$2"
  ( cd "$WT" && ./mvnw -pl "$MODULES" -am -Dtest="$TESTS" -Dsurefire.failIfNoSpecifiedTests=false \
      $extra test ) > "$out" 2>&1
  echo $?
}

say "--- 干净世界基线（期望绿）---"
brc=$(run_mvn "" "$LOG.clean")
bce=$(grep -c "COMPILATION ERROR" "$LOG.clean" || true)
btn=$(grep -oE "Tests run: [0-9]+" "$LOG.clean" | tail -1 | grep -oE '[0-9]+' || true)
say "baseline_rc=$brc baseline_compilation_error=$bce baseline_tests=$btn"
[ "$brc" = "0" ] || { say "FATAL: 基线不绿（作废本轮）"; exit 2; }
[ "$bce" = "0" ] || { say "FATAL: 基线编译错误（作废本轮）"; exit 2; }
[ -n "$btn" ] && [ "$btn" -ge 1 ] || { say "FATAL: 基线没跑到用例（作废本轮）"; exit 2; }

# ②③ 生成 + 推到规范名
case "$RID" in
  t4r-t9m1 | t4r-t9m2 | t4r-t9m3)
    python3 "$EV/mutants/make-mutants-t9.py" >/dev/null
    cp "$EV/mutants/${RID#t4r-}_Shell.java" "$SRC" ;;
  *)
    python3 "$EV/mutants/make-mutant.py" "$RID" "$ORIGF" "$SRC" ;;
esac
mut=$(md5 "$SRC"); req "$mut" mutant
say "mutant_md5=$mut"
[ "$mut" != "$orig" ] || { say "MUTANT_IDENTICAL abort"; exit 4; }

# ④ 清陈旧 .class / reports
MODULE="${TGT_REL%%/*}"
CLASS_REL="$(echo "$TGT_REL" | sed -E 's#^[^/]+/src/main/java/##; s#\.java$#.class#')"
find "$WT/$MODULE/target/classes" -name "$(basename "$CLASS_REL")" -delete 2>/dev/null || true
rm -rf "$WT/$MODULE/target/surefire-reports"
say "cleared_class=$CLASS_REL"

ROUND_START=$(date +%s)
# ⑤⑥ 变异轮（带 failure.ignore：让多模块都跑到）
mrc=$(run_mvn "-Dmaven.test.failure.ignore=true" "$LOG.mut")
cat "$LOG.mut" >> "$LOG"
mce=$(grep -c "COMPILATION ERROR" "$LOG.mut" || true)
mtn=$(grep -oE "Tests run: [0-9]+" "$LOG.mut" | tail -1 | grep -oE '[0-9]+' || true)
say "mut_rc=$mrc mut_compilation_error=$mce mut_tests=$mtn"
[ "$mce" = "0" ] || { say "FATAL: 变异轮编译错误（作废本轮）"; restore; trap - EXIT; exit 2; }
[ -n "$mtn" ] && [ "$mtn" -ge 1 ] || { say "FATAL: 变异轮没跑到用例（作废本轮）"; restore; trap - EXIT; exit 2; }

REPORTS=$(find "$WT" -path '*/target/surefire-reports/*.txt' -newermt "@$ROUND_START" 2>/dev/null || true)
say "--- 本轮 surefire 报告（mtime 落轮内）---"
say "$REPORTS"
FAIL_LINES=""
if [ -n "$REPORTS" ]; then
  FAIL_LINES=$(echo "$REPORTS" | xargs grep -hE "<<< (FAILURE|ERROR)" 2>/dev/null || true)
fi
say "--- 失败方法（原文）---"
[ -n "$FAIL_LINES" ] || { say "FATAL: 没有本轮失败原文（作废本轮）"; restore; trap - EXIT; exit 2; }
say "$FAIL_LINES"
HIT=$(echo "$FAIL_LINES" | grep -cE "$EXPECT_RE" || true)
say "protected_assertion_hits=$HIT"
[ "$HIT" -ge 1 ] || { say "FATAL: 红点未落在被保护断言（作废本轮）"; restore; trap - EXIT; exit 2; }
# ★ mrc 不可作判据：变异轮带 -Dmaven.test.failure.ignore=true ⇒ 测试红了 rc 仍为 0（基线不加它）。
#   判据 = 红点是否落在被保护断言（基线已证绿）。
if [ "$HIT" -ge 1 ]; then say "VERDICT=KILLED"; else say "VERDICT=SURVIVED"; fi

restore; trap - EXIT
rest=$(md5 "$SRC"); req "$rest" restored
say "restored_md5=$rest"
[ "$rest" = "$orig" ] || { say "RESTORE_MISMATCH abort"; exit 6; }

{
  echo ""
  echo "===== 装置补记（$RID）====="
  echo "target=$TGT_REL"
  echo "orig_md5=$orig"
  echo "mutant_md5=$mut"
  echo "restored_md5=$rest"
  echo "mut_rc=$mrc hits=$HIT"
} >> "$LOG"
SELF=$(grep -oE 'mutant_md5=[0-9a-f]{32}' "$LOG" | tail -1 | cut -d= -f2)
[ -n "$SELF" ] || { echo "FATAL: 日志自指 md5 读空"; exit 2; }
say "===== end $RID self_md5=$SELF ====="
