#!/usr/bin/env bash
# T11C 变异轮装置。★★ 每一轮的产物**自指**：本轮的 baseline_md5 / mutant_md5 / restored_md5 /
# restored_identical / compilation_errors / report_files / 红点断言名 全部写进**日志本身**（形态 6 的教训）。
# ★★ 判据含「靶模块必须有**本轮**报告」：跑前先删该用例的旧报告，跑后要求它存在且 mtime 落在本轮内——
#    否则把「Checkstyle 先拦 ⇒ 0 个报告」读成 SURVIVED（上一轮踩过）。
# 用法: mut-round.sh <round-id> <module> <test-selector> <expect-red-regex> <target-file> <mutator.py> <report-glob>
set -u
ROOT=/home/cna/SimulatorMosire
EVID="$ROOT/.superpowers/sdd/2026-09-22-agent-permission/t11c-evidence"
MUT="$EVID/mutants"
id=$1; module=$2; selector=$3; expect=$4; target=$5; mutator=$6; report_glob=$7
LOG="$MUT/logs/$id.log"; MAVENLOG="$MUT/logs/$id.maven.log"
REPORTS="$ROOT/$module/target/surefire-reports"
mkdir -p "$MUT/logs" "$MUT/orig"
cd "$ROOT" || exit 1

round_start=$(date +%s)
{
echo "=================== T11C 变异轮 $id — $(date -Is) ==================="
echo "round=$id"
echo "target=$target"
echo "module=$module selector=$selector"
echo "expect_red_pattern=$expect"
echo "expect_report_glob=$report_glob"
echo "--- ① 干净世界：原件另存 + 基线 md5 ---"
cp "$target" "$MUT/orig/$id.orig" || { echo "ROUND_VOID copy_failed"; exit 9; }
baseline_md5=$(md5sum "$target" | cut -d' ' -f1)
echo "baseline_md5=$baseline_md5"
echo "--- ② 落变异体（按**目标类名**推入，不按变异体名） ---"
python3 "$mutator" "$target" || { echo "ROUND_VOID mutator_failed"; exit 9; }
mutant_md5=$(md5sum "$target" | cut -d' ' -f1)
echo "mutant_md5=$mutant_md5"
if [ "$mutant_md5" = "$baseline_md5" ]; then
  echo "restored_identical=n/a (VOID)"; echo "ROUND_VOID mutant_identical_to_baseline（变异体没改到字节 ⇒ 本轮无效）"; exit 9
fi
echo "mutant_differs_from_baseline=true"
echo "--- ③ 清掉该用例的旧报告（本轮必须有本轮的） ---"
rm -f "$REPORTS"/*"$report_glob"* 2>/dev/null
echo "removed_stale_report_glob=$report_glob"
echo "--- ④ 跑测试 ---"
./mvnw -pl "$module" -am -Dtest="$selector" -Dsurefire.failIfNoSpecifiedTests=false test \
  > "$MAVENLOG" 2>&1
rc=$?
echo "maven_rc=$rc"
ce=$(grep -c "COMPILATION ERROR" "$MAVENLOG")
echo "compilation_errors=$ce"
report=$(ls "$REPORTS"/*"$report_glob"*.txt 2>/dev/null | head -1)
echo "report_files=${report:-（无）}"
verdict=VOID
if [ "$ce" != "0" ]; then
  echo "void_reason=compilation_error（红的理由是编译，不是断言 ⇒ 本轮作废，不许当 KILLED）"
elif [ -z "$report" ]; then
  echo "void_reason=no_report（『没跑到』≠『没红』：不许读成 SURVIVED）"
else
  fresh=$([ "$report" -nt "$MUT/orig/$id.orig" ] && echo true || echo false)
  echo "report_is_fresh=$fresh"
  line=$(grep -m1 "Tests run:" "$report")
  echo "report_line=$line"
  tests=$(echo "$line" | grep -oE "Tests run: [0-9]+" | grep -oE "[0-9]+")
  failures=$(echo "$line" | grep -oE "Failures: [0-9]+" | grep -oE "[0-9]+")
  errors=$(echo "$line" | grep -oE "Errors: [0-9]+" | grep -oE "[0-9]+")
  echo "tests=$tests failures=$failures errors=$errors"
  hits=$(grep -hcE "$expect" "$report" | head -1)
  hits2=$(grep -cE "$expect" "$MAVENLOG")
  echo "expect_pattern_hits_report=$hits expect_pattern_hits_maven_log=$hits2"
  echo "--- 红点断言名（本题的红是哪几条） ---"
  grep -E "» |<<< (FAILURE|ERROR)!" "$MAVENLOG" | sed 's/^/  red: /' | head -12
  if [ "$tests" != "0" ] && [ "$((failures + errors))" -gt 0 ] && { [ "$hits" -gt 0 ] || [ "$hits2" -gt 0 ]; }; then
    verdict=KILLED
  elif [ "$tests" != "0" ] && [ "$((failures + errors))" -eq 0 ]; then
    verdict=SURVIVED
  elif [ "$tests" = "0" ]; then
    echo "void_reason=tests_run_zero（用例没跑到）"
  else
    echo "void_reason=red_but_pattern_missed（红了，但预期红点正则没命中 ⇒ 本轮作废，改正则后重跑）"
  fi
fi
echo "--- ⑤ 逐字节还原 ---"
cp "$MUT/orig/$id.orig" "$target"
restored_md5=$(md5sum "$target" | cut -d' ' -f1)
echo "restored_md5=$restored_md5"
if [ "$restored_md5" = "$baseline_md5" ]; then echo "restored_identical=true"; else echo "restored_identical=false（必须作废本轮并重跑）"; fi
echo "round_seconds=$(( $(date +%s) - round_start ))"
echo "VERDICT_$id=$verdict"
} 2>&1 | tee -a "$LOG"
