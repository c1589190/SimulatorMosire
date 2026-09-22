#!/usr/bin/env bash
# 会话世代变异装置（多目标）：每轮把**某个目标文件**改成变异体、跑判据、逐字节还原。
#
# 纪律（按本仓「护栏必须自证」的形态清单）：
#   ① 每轮**先恢复干净世界**（全部目标文件从基线副本还原 + 比 md5），再落变异；
#   ② 变异后的字节必须 != 基线（否则整轮作废——"没改却说改了"）；
#   ③ `COMPILATION ERROR` 必须为 0，不为 0 判 VOID（"编译不过"不等于"红"）；
#   ④ `report_files=0` 判 VOID（"没跑到"不等于"没红"）；
#   ⑤ 还原后再比一次 md5（restored_identical）；
#   ⑥ 每轮的 baseline_md5 / mutant_md5 / restored_md5 / restored_identical / compilation_errors /
#      report_files / maven_rc 与**红点断言名**都写进本轮日志本身（留痕自指，见形态 6）。
set -u

EVID="$(cd "$(dirname "$0")" && pwd)"
ROOT="/home/cna/SimulatorMosire"
LOGS="$EVID/mutants"
BASE_DIR="$EVID/baseline"

APP_MAIN="simos-app/src/main/java/io/mosire/simos/app"
SD_MAIN="simos-sd/src/main/java/io/mosire/simos/sd"

# 轮次 → 「目标相对路径 | 模块 | 判据（-Dtest 的逗号串）」
spec_for() {
  case "$1" in
    m1 | m3) echo "$APP_MAIN/decision/DecisionAgentRunner.java|simos-app|ResetConversationEndToEndTest,DecisionAgentRunnerTest,RunDecisionEndToEndTest" ;;
    m6) echo "$APP_MAIN/decision/DecisionAgentService.java|simos-app|ResetConversationEndToEndTest,DecisionAgentRunnerTest,RunDecisionEndToEndTest" ;;
    m2 | m4 | m7) echo "$SD_MAIN/spi/ResetDecisionMakerConversationHandler.java|simos-sd|ResetDecisionMakerConversationHandlerTest,SetDecisionMakerProviderHandlerTest" ;;
    m5) echo "$SD_MAIN/spi/SetDecisionMakerAccessHandler.java|simos-sd|SetDecisionMakerAccessHandlerTest" ;;
    *) echo "" ;;
  esac
}

# 全部目标文件的基线（第一次跑时把**改好之后**的字节留档；还原一律以它为源，绝不用 git checkout——改动还没提交）
TARGETS=(
  "$APP_MAIN/decision/DecisionAgentRunner.java"
  "$APP_MAIN/decision/DecisionAgentService.java"
  "$SD_MAIN/spi/ResetDecisionMakerConversationHandler.java"
  "$SD_MAIN/spi/SetDecisionMakerAccessHandler.java"
)

mkdir -p "$LOGS" "$BASE_DIR"
cd "$ROOT" || exit 1

md5of() { md5sum "$1" | cut -d' ' -f1; }

for rel in "${TARGETS[@]}"; do
  b="$BASE_DIR/$(basename "$rel").baseline"
  [ -f "$b" ] || cp "$ROOT/$rel" "$b"
done

restore_all() {
  for rel in "${TARGETS[@]}"; do
    cp "$BASE_DIR/$(basename "$rel").baseline" "$ROOT/$rel"
  done
}

for round in "$@"; do
  log="$LOGS/$round.log"
  : > "$log"

  spec="$(spec_for "$round")"
  if [ -z "$spec" ]; then
    echo "[$round] VOID 未登记的轮次" | tee -a "$log"
    continue
  fi
  IFS='|' read -r target_rel module tests <<<"$spec"
  target="$ROOT/$target_rel"
  baseline="$BASE_DIR/$(basename "$target_rel").baseline"
  baseline_md5=$(md5of "$baseline")

  # ① 干净世界（**全部**目标文件一起还原：上一轮若还原失败也不会漏到这一轮）
  restore_all
  start_md5=$(md5of "$target")
  if [ "$start_md5" != "$baseline_md5" ]; then
    echo "[$round] VOID 恢复失败 start=$start_md5 baseline=$baseline_md5" | tee -a "$log"
    continue
  fi

  # ② 落变异
  if ! python3 "$EVID/mutate.py" "$round" "$target" 2>>"$log"; then
    restore_all
    echo "[$round] VOID 变异体未应用（锚点不唯一）" | tee -a "$log"
    continue
  fi
  mutant_md5=$(md5of "$target")

  # 先清掉上一轮的一切产物（陈旧 .class / 陈旧 surefire 报告都不许活到这一轮）
  rm -rf "$module/target/classes" "$module/target/test-classes" "$module/target/surefire-reports"

  if [ "$mutant_md5" = "$baseline_md5" ]; then
    restore_all
    echo "[$round] VOID 变异后字节与基线相同（等于没改）" | tee -a "$log"
    continue
  fi

  # ③ 跑判据
  ./mvnw -pl "$module" -am -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false test >"$log" 2>&1
  rc=$?

  compilation_errors=$(grep -c 'COMPILATION ERROR' "$log")
  report_files=$(find "$module/target/surefire-reports" -name '*.txt' 2>/dev/null | wc -l)
  reds=$(grep -oE '^\[ERROR\]   [A-Za-z]+\.[A-Za-z]+:[0-9]+' "$log" | sed 's/^\[ERROR\]   //' | sort -u)
  tests_run=$(grep -oE 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+' "$log" | tail -1)

  # ④ 还原 + 复核
  restore_all
  restored_md5=$(md5of "$target")
  if [ "$restored_md5" = "$baseline_md5" ]; then restored_identical=true; else restored_identical=false; fi

  {
    echo "===== round=$round ====="
    echo "target=$target_rel"
    echo "module=$module"
    echo "tests=$tests"
    echo "baseline_md5=$baseline_md5"
    echo "mutant_md5=$mutant_md5"
    echo "restored_md5=$restored_md5"
    echo "restored_identical=$restored_identical"
    echo "compilation_errors=$compilation_errors"
    echo "report_files=$report_files"
    echo "maven_rc=$rc"
    echo "last_surefire_summary=${tests_run:-<无>}"
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
