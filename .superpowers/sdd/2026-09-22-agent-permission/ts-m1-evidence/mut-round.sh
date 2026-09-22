#!/usr/bin/env bash
# TS/M1 变异装置：每轮把 DecisionAgentRunner.java 改成变异体、跑判据、逐字节还原。
#
# 纪律（按本仓「护栏必须自证」的形态清单）：
#   ① 每轮**先恢复干净世界**（从基线副本还原 + 比 md5），再落变异；
#   ② 变异后的字节必须 != 基线（否则整轮作废——"没改却说改了"）；
#   ③ `COMPILATION ERROR` 必须为 0，不为 0 判 VOID（"编译不过"不等于"红"）；
#   ④ `report_files=0` 判 VOID（"没跑到"不等于"没红"）；
#   ⑤ 还原后再比一次 md5（restored_identical）；
#   ⑥ 每轮的 baseline_md5 / mutant_md5 / restored_md5 / restored_identical / compilation_errors /
#      report_files 与**红点断言名**都写进本轮日志本身（留痕自指，见形态 6）。
set -u

EVID="$(cd "$(dirname "$0")" && pwd)"
ROOT="/home/cna/SimulatorMosire"
TARGET_REL="simos-app/src/main/java/io/mosire/simos/app/decision/DecisionAgentRunner.java"
TARGET="$ROOT/$TARGET_REL"
BASE="$EVID/DecisionAgentRunner.java.baseline"
LOGS="$EVID/mutants"
TESTS="DecisionAgentRunnerTest,RunDecisionEndToEndTest"

mkdir -p "$LOGS"
cd "$ROOT" || exit 1

md5of() { md5sum "$1" | cut -d' ' -f1; }

# 基线：第一次跑时把**修好之后**的字节留档（还原一律以它为源，绝不用 git checkout——修复还没提交）
if [ ! -f "$BASE" ]; then
  cp "$TARGET" "$BASE"
fi
baseline_md5=$(md5of "$BASE")

for round in "$@"; do
  log="$LOGS/$round.log"
  : > "$log"

  # ① 干净世界
  cp "$BASE" "$TARGET"
  start_md5=$(md5of "$TARGET")
  if [ "$start_md5" != "$baseline_md5" ]; then
    echo "[$round] VOID 恢复失败 start=$start_md5 baseline=$baseline_md5" | tee -a "$log"
    continue
  fi

  # ② 落变异
  if ! python3 "$EVID/mutate.py" "$round" "$TARGET" 2>>"$log"; then
    cp "$BASE" "$TARGET"
    echo "[$round] VOID 变异体未应用（锚点不唯一）" | tee -a "$log"
    continue
  fi
  mutant_md5=$(md5of "$TARGET")

  # 先清掉上一轮的一切产物（陈旧 .class / 陈旧 surefire 报告都不许活到这一轮）
  rm -rf simos-app/target/classes simos-app/target/surefire-reports

  if [ "$mutant_md5" = "$baseline_md5" ]; then
    cp "$BASE" "$TARGET"
    echo "[$round] VOID 变异后字节与基线相同（等于没改）" | tee -a "$log"
    continue
  fi

  # ③ 跑判据
  ./mvnw -pl simos-app -am -Dtest="$TESTS" -Dsurefire.failIfNoSpecifiedTests=false test >"$log" 2>&1
  rc=$?

  compilation_errors=$(grep -c 'COMPILATION ERROR' "$log")
  report_files=$(find simos-app/target/surefire-reports -name '*.txt' 2>/dev/null | wc -l)
  reds=$(grep -oE '^\[ERROR\]   [A-Za-z]+\.[A-Za-z]+:[0-9]+' "$log" | sed 's/^\[ERROR\]   //' | sort -u)
  tests_run=$(grep -oE 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+' "$log" | tail -1)

  # ④ 还原 + 复核
  cp "$BASE" "$TARGET"
  restored_md5=$(md5of "$TARGET")
  if [ "$restored_md5" = "$baseline_md5" ]; then restored_identical=true; else restored_identical=false; fi

  {
    echo "===== round=$round ====="
    echo "baseline_md5=$baseline_md5"
    echo "mutant_md5=$mutant_md5"
    echo "restored_md5=$restored_md5"
    echo "restored_identical=$restored_identical"
    echo "compilation_errors=$compilation_errors"
    echo "report_files=$report_files"
    echo "maven_rc=$rc"
    echo "last_surefire_summary=${tests_run:-<无>}"
    echo "red_count=$(printf '%s\n' "$reds" | grep -c . )"
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
