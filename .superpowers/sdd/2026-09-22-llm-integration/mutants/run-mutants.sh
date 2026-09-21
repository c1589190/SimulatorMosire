#!/usr/bin/env bash
# 变异轮：按白名单把一处故意违规写进目标源 → 跑被保护用例 → 逐字节还原并复核 md5。
# 纪律（CLAUDE.md 形态 1）：字节必须真的不同；COMPILATION ERROR 必须为 0；还原后 md5 必须回到原件。
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/llmint
EV="$ROOT/.superpowers/sdd/2026-09-22-llm-integration/mutants"
mkdir -p "$EV/logs"
SUMM="$EV/summary.txt"
: > "$SUMM"
cd "$ROOT"

run_mutant() {
  local label="$1" module="$2" testcls="$3"
  local rel
  rel=$(python3 -c "import sys; sys.path.insert(0,'$EV'); import mutate; print(mutate.MUTATIONS['$label'][0])")
  local target="$ROOT/$rel"
  local bak="/tmp/mut-$label.bak"
  local log="$EV/logs/$label.log"
  local orig mutant after rc comp red verdict restore
  orig=$(md5sum "$target" | cut -d' ' -f1)
  cp "$target" "$bak"
  if ! python3 "$EV/mutate.py" "$label" "$ROOT" > "$EV/logs/$label.patch" 2>&1; then
    echo "$label verdict=VOID anchor-miss" | tee -a "$SUMM"; cp "$bak" "$target"; return
  fi
  mutant=$(md5sum "$target" | cut -d' ' -f1)
  if [ "$orig" = "$mutant" ]; then
    echo "$label verdict=VOID no-byte-change orig=$orig" | tee -a "$SUMM"; cp "$bak" "$target"; return
  fi
  timeout 500 ./mvnw -q -pl "$module" -am -Dtest="$testcls" -Dsurefire.failIfNoSpecifiedTests=false test > "$log" 2>&1
  rc=$?
  comp=$(grep -c 'COMPILATION ERROR' "$log")
  red=$(grep -cE '<<< (FAILURE|ERROR)!|Failures: [1-9]|Errors: [1-9]|BUILD FAILURE' "$log")
  cp "$bak" "$target"
  after=$(md5sum "$target" | cut -d' ' -f1)
  restore=OK; [ "$after" = "$orig" ] || restore=BAD
  if [ "$comp" != "0" ]; then verdict=VOID
  elif [ "$rc" != "0" ] && [ "$red" != "0" ]; then verdict=KILLED
  else verdict=SURVIVED; fi
  echo "$label verdict=$verdict rc=$rc comp=$comp red_hits=$red restore=$restore orig=$orig mutant=$mutant test=$testcls" | tee -a "$SUMM"
}

run_mutant m1-fail-closed-not-found simos-app LlmProviderResolverTest
run_mutant m2-fail-closed-unbound simos-app LlmProviderResolverTest
run_mutant m4-config-degrades simos-sd AdjudicationTest
run_mutant m-schema-not-sent simos-sd AdjudicationTest
run_mutant m6-key-into-routes simos-app AgentLibLlmConfigTest
run_mutant m7-broken-hidden simos-app AgentLibLlmConfigTest
run_mutant m-repo-seed-removed simos-app AgentLibLlmConfigTest
run_mutant m-sampling-max-tokens simos-app AdjudicationEndToEndTest
run_mutant m3-key-value-logged simos-app SimosApiKeySourceTest
