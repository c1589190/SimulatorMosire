#!/usr/bin/env bash
# M9 T3 变异装置：每轮恢复干净世界 → 推变异体为规范类名 → md5 自证 → 跑门禁 → 记录红点 → 还原。
# 纪律：清 target/classes 里的旧 .class；断言无 COMPILATION ERROR；日志里自指 orig/mutant/pushed md5。
set -u
ROOT="/home/cna/SimulatorMosire/.claude/worktrees/m9t3"
EV="$ROOT/.superpowers/sdd/2026-09-19-map-perf/t3-evidence"
MUT="$EV/mutants"
LOGS="$EV/logs"
QS_REL="simos-app/src/main/java/io/mosire/simos/app/query/QueryService.java"
AV_REL="simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java"
mkdir -p "$LOGS"

restore() {
  cp "$MUT/orig.QueryService.java" "$ROOT/$QS_REL"
  cp "$MUT/orig.ApiViews.java" "$ROOT/$AV_REL"
}

md5() { md5sum "$1" | awk '{print $1}'; }

run_round() {
  local id="$1" src="$2" target_rel="$3"
  restore
  local orig_md5; orig_md5=$(md5 "$MUT/orig.$(basename "$target_rel")")
  local base_md5; base_md5=$(md5 "$ROOT/$target_rel")
  if [ "$base_md5" != "$orig_md5" ]; then
    echo "$id CLEAN-WORLD-FAIL base=$base_md5 orig=$orig_md5"; return 2
  fi
  cp "$MUT/$src" "$ROOT/$target_rel"
  local preset_md5; preset_md5=$(md5 "$MUT/$src")
  local pushed_md5; pushed_md5=$(md5 "$ROOT/$target_rel")
  if [ "$pushed_md5" != "$preset_md5" ] || [ "$pushed_md5" = "$orig_md5" ]; then
    echo "$id PUSH-FAIL preset=$preset_md5 pushed=$pushed_md5 orig=$orig_md5"; return 2
  fi
  rm -f "$ROOT/simos-app/target/classes/io/mosire/simos/app/query/QueryService"*.class
  rm -f "$ROOT/simos-app/target/classes/io/mosire/simos/app/gui/ApiViews"*.class
  local log="$LOGS/$id.log"
  ( cd "$ROOT" && ./mvnw -q -pl simos-app -am -Dtest=QueryServiceTest,GuiApiTest -Dsurefire.failIfNoSpecifiedTests=false test ) >"$log" 2>&1
  local rc=$?
  local comp; comp=$(grep -c "COMPILATION ERROR" "$log")
  echo "$id self: orig_md5=$orig_md5 mutant_md5=$preset_md5 pushed_md5=$pushed_md5 rc=$rc compilation_errors=$comp"
  if [ "$comp" -ne 0 ]; then echo "$id INVALID (compilation error)"; restore; return 3; fi
  grep -E "^\[ERROR\]   (QueryServiceTest|GuiApiTest)\." "$log" || echo "$id: no red-test lines"
  restore
}

run_round m1 m1.QueryService.java "$QS_REL"
run_round m2 m2.QueryService.java "$QS_REL"
run_round m3 m3.QueryService.java "$QS_REL"
run_round m4 m4.ApiViews.java "$AV_REL"

# 还原后自证干净世界
echo "restored QueryService md5=$(md5 "$ROOT/$QS_REL")"
echo "restored ApiViews md5=$(md5 "$ROOT/$AV_REL")"
echo "orig QueryService md5=$(md5 "$MUT/orig.QueryService.java")"
echo "orig ApiViews md5=$(md5 "$MUT/orig.ApiViews.java")"
