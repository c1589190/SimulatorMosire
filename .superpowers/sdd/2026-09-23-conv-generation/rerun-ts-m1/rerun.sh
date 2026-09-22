#!/usr/bin/env bash
# **裁定 42 的连带重跑**：ts/m1 的 6 个变异轮的靶文件正是 DecisionAgentRunner.java，而本任务改了它
# ⇒ 那 6 轮的旧证据已经不对应现在的字节，必须**在新字节上重新派生**。
#
# ★ **不复用它们脚本里的 baseline**：那个 baseline 是**改动前**的字节，跑一次就会把本任务的改动覆盖掉
#   （它的第一行就是 `cp "$BASE" "$TARGET"`）。⇒ 本脚本用自己的 baseline（= 现在的字节），
#   只复用它们的**变异体定义**（`ts-m1-evidence/mutate.py`，锚点逐条核过仍在）。
set -u

EVID="$(cd "$(dirname "$0")" && pwd)"
PREV="/home/cna/SimulatorMosire/.superpowers/sdd/2026-09-22-agent-permission/ts-m1-evidence"
ROOT="/home/cna/SimulatorMosire"
TARGET_REL="simos-app/src/main/java/io/mosire/simos/app/decision/DecisionAgentRunner.java"
TARGET="$ROOT/$TARGET_REL"
BASE="$EVID/DecisionAgentRunner.java.baseline-2026-09-23"
LOGS="$EVID/logs"
# ★ 判据与当轮**逐字相同**（只有这样才是"同一轮在新字节上的复现"，不是换一套判据去凑红）
TESTS="DecisionAgentRunnerTest,RunDecisionEndToEndTest"

mkdir -p "$LOGS"
cd "$ROOT" || exit 1

md5of() { md5sum "$1" | cut -d' ' -f1; }

[ -f "$BASE" ] || cp "$TARGET" "$BASE"
baseline_md5=$(md5of "$BASE")

for round in "$@"; do
  log="$LOGS/$round.log"
  : > "$log"

  cp "$BASE" "$TARGET"
  start_md5=$(md5of "$TARGET")
  if [ "$start_md5" != "$baseline_md5" ]; then
    echo "[$round] VOID 恢复失败 start=$start_md5 baseline=$baseline_md5" | tee -a "$log"
    continue
  fi

  if ! python3 "$PREV/mutate.py" "$round" "$TARGET" 2>>"$log"; then
    cp "$BASE" "$TARGET"
    echo "[$round] VOID 变异体未应用（锚点不唯一——本任务的改动动了它们的地基）" | tee -a "$log"
    continue
  fi
  mutant_md5=$(md5of "$TARGET")

  rm -rf simos-app/target/classes simos-app/target/test-classes simos-app/target/surefire-reports

  if [ "$mutant_md5" = "$baseline_md5" ]; then
    cp "$BASE" "$TARGET"
    echo "[$round] VOID 变异后字节与基线相同" | tee -a "$log"
    continue
  fi

  ./mvnw -pl simos-app -am -Dtest="$TESTS" -Dsurefire.failIfNoSpecifiedTests=false test >"$log" 2>&1
  rc=$?

  compilation_errors=$(grep -c 'COMPILATION ERROR' "$log")
  report_files=$(find simos-app/target/surefire-reports -name '*.txt' 2>/dev/null | wc -l)
  reds=$(grep -oE '^\[ERROR\]   [A-Za-z]+\.[A-Za-z]+:[0-9]+' "$log" | sed 's/^\[ERROR\]   //' | sort -u)

  cp "$BASE" "$TARGET"
  restored_md5=$(md5of "$TARGET")
  if [ "$restored_md5" = "$baseline_md5" ]; then restored_identical=true; else restored_identical=false; fi

  {
    echo "===== rerun(裁定42) round=$round ====="
    echo "target=$TARGET_REL"
    echo "tests=$TESTS"
    echo "baseline_md5=$baseline_md5"
    echo "mutant_md5=$mutant_md5"
    echo "restored_md5=$restored_md5"
    echo "restored_identical=$restored_identical"
    echo "compilation_errors=$compilation_errors"
    echo "report_files=$report_files"
    echo "maven_rc=$rc"
    echo "red_count=$(printf '%s\n' "$reds" | grep -c .)"
    echo "red_targets:"
    printf '%s\n' "$reds" | sed 's/^/  - /'
  } >> "$log"

  if [ "$compilation_errors" != "0" ] || [ "$report_files" = "0" ] || [ "$restored_identical" != "true" ]; then
    echo "[$round] VOID（compilation_errors=$compilation_errors report_files=$report_files restored_identical=$restored_identical）"
  elif [ "$rc" = "0" ]; then
    echo "[$round] SURVIVED（判据全绿，变异体存活）"
  else
    echo "[$round] KILLED（rc=$rc，红点 $(printf '%s\n' "$reds" | grep -c .) 处）"
  fi
done
