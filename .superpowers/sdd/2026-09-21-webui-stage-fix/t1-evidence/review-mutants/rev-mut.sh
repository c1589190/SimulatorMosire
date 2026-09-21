#!/usr/bin/env bash
# 审查方独立变异轮（不依赖实现者的装置）——干净世界 / 字节不同 / 规范名 / node 汇总非空 /
# 红点落被保护断言 / cp 逐字节还原（绝不 git checkout）/ 日志自指 md5。
set -u
WT=/home/cna/SimulatorMosire/.claude/worktrees/wsf-t1
WEBUI=$WT/simos-app/src/main/resources/webui
JS=$WT/simos-app/src/test/js
OUT=/tmp/opencode/t1review
md5() { md5sum "$1" | cut -d' ' -f1; }
LOG=$OUT/rev.log
: > "$LOG"
say() { echo "$@" | tee -a "$LOG"; }

run_one() {
  local id="$1" tgt="$2" mutator="$3"
  local orig; orig=$(md5 "$tgt")
  local cur; cur=$(md5 "$tgt")
  say "===== $id ====="
  say "target=$tgt"
  say "orig_md5=$orig"
  say "clean_world_md5=$cur"
  [ -n "$orig" ] && [ "$orig" = "$cur" ] || { say "NOT_CLEAN_WORLD abort"; return 3; }
  # 备份（字节级）
  cp "$tgt" "$OUT/$id.pristine"
  python3 "$OUT/mutate.py" "$id" "$tgt" || { say "MUTATE_ABORT"; return 4; }
  local mut; mut=$(md5 "$tgt")
  say "mutant_md5=$mut"
  [ -n "$mut" ] && [ "$mut" != "$orig" ] || { say "MUTANT_IDENTICAL abort"; cp "$OUT/$id.pristine" "$tgt"; return 4; }
  ( cd "$WT" && node simos-app/src/test/js/run-gate.cjs > "$OUT/$id.gate" 2>&1 ); local rc=$?
  local tn; tn=$(grep -m1 '^# tests ' "$OUT/$id.gate" | grep -oE '[0-9]+')
  say "gate_rc=$rc"
  say "gate_tests=${tn:-ABSENT}"
  [ -n "${tn:-}" ] || { say "TESTS_ABSENT abort"; cp "$OUT/$id.pristine" "$tgt"; return 5; }
  # 还原（逐字节）
  cp "$OUT/$id.pristine" "$tgt"
  local rest; rest=$(md5 "$tgt")
  say "restored_md5=$rest"
  [ "$rest" = "$orig" ] || { say "RESTORE_MISMATCH abort"; return 6; }
  if [ "$rc" -ne 0 ]; then say "VERDICT=KILLED"; else say "VERDICT=SURVIVED"; fi
  say "-- not ok 行 --"
  grep -E '^not ok ' "$OUT/$id.gate" | sed 's/^/  /' | tee -a "$LOG" || true
  say "-- 门禁末 3 行 --"
  tail -3 "$OUT/$id.gate" | sed 's/^/  /' | tee -a "$LOG"
  say "===== end $id ====="
}

run_one sx1 "$WEBUI/index.html" delNotify
run_one sx2r "$JS/run-gate.cjs" lowerRunGate
run_one sx2c "$JS/gate-contract.test.cjs" lowerContract
run_one sx3 "$JS/notifications.test.cjs" disableOneTest
