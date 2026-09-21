#!/usr/bin/env bash
# mut-java.sh —— T10 Java 变异轮装置（九道门禁的 Java 版）。
#   ① 干净世界（pristine 恢复后 md5 == pristine）② 变异体字节不同 ③ 推到规范名目标文件
#   ④ 清陈旧 .class + surefire-reports（sd 与 app 两处）⑤ COMPILATION ERROR=0 且 Tests run>=1
#   ⑥ surefire 报告 mtime 落本轮 ⑦ 红点落被保护断言（expect 正则）⑧ cp 逐字节还原（绝不 git checkout）
#   ⑨ 日志自指（md5 写进日志；读取处先断言非空）
# 用法: mut-java.sh [round...]（缺省跑全部）
# ★ 基线绿由 final bytes 上的 `clean verify` 保证（..//logs/clean-verify.attempt2.log，rc=0）。
set -u

WT=/home/cna/SimulatorMosire/.claude/worktrees/wsf-t10
EV=$WT/.superpowers/sdd/2026-09-21-webui-stage-fix/t10-evidence/mutants
PRIST=$EV/pristine
LOGS=$EV/logs
ALL_LOG=$LOGS/mut-java.log
mkdir -p "$LOGS"
: > "$ALL_LOG"

APP=simos-app
SD=simos-sd
GUI_REL=$APP/src/main/java/io/mosire/simos/app/gui/GuiServer.java
NARROW_REL=$APP/src/main/java/io/mosire/simos/app/tools/write/AbstractNarrowWriteTool.java
SOURCE_REL=$APP/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java
CATALOG_REL=$APP/src/main/java/io/mosire/simos/app/tools/read/CatalogTool.java
SHELL_REL=$APP/src/main/java/io/mosire/simos/app/Shell.java
HANDLER_REL=$SD/src/main/java/io/mosire/simos/sd/spi/StartDecisionHandler.java

TESTS="StartDecisionEndToEndTest,StartDecisionHandlerTest,McpCoverageTest,McpPortTopologyTest,SimosToolsTest"

md5() { md5sum "$1" | cut -d' ' -f1; }
req() { [ -n "${1:-}" ] || { echo "ABSENT_VALUE abort ($2)"; return 9; }; }

run_one() {
  local rid="$1" rel="$2" expect="$3"
  local src="$WT/$rel" base log round_start mrc mce mtn fails hit rest self
  base=$(basename "$rel")
  log="$LOGS/$rid.log"
  : > "$log"
  _body() {
    echo "===== $rid target=$rel expect=$expect ====="
    cp "$PRIST/$base" "$src"
    local orig cur
    orig=$(md5 "$PRIST/$base"); cur=$(md5 "$src")
    req "$orig" orig || return 9; req "$cur" cur || return 9
    echo "orig_md5=$orig clean_world_md5=$cur"
    [ "$cur" = "$orig" ] || { echo "NOT_CLEAN_WORLD abort"; return 3; }
    if ! python3 "$EV/make-mutant.py" "$rid" "$PRIST/$base" "$src" >/dev/null; then
      echo "$rid VOID (make-mutant 非零退出)"; cp "$PRIST/$base" "$src"; return 4
    fi
    local mut
    mut=$(md5 "$src"); req "$mut" mutant || return 9
    echo "mutant_md5=$mut"
    [ "$mut" != "$orig" ] || { echo "$rid VOID (mutant==orig)"; cp "$PRIST/$base" "$src"; return 4; }
    local cls
    cls=$(basename "$rel" .java).class
    find "$WT/$APP/target/classes" "$WT/$SD/target/classes" -name "$cls" -delete 2>/dev/null || true
    rm -rf "$WT/$APP/target/surefire-reports" "$WT/$SD/target/surefire-reports"
    round_start=$(date +%s)
    ( cd "$WT" && ./mvnw -pl "$APP" -am -Dtest="$TESTS" \
        -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.failure.ignore=true test ) \
        > "$log" 2>&1
    mrc=$?
    mce=$(grep -c "COMPILATION ERROR" "$log" || true)
    mtn=$(grep -oE "Tests run: [0-9]+" "$log" | tail -1 | grep -oE '[0-9]+' || true)
    echo "mut_rc=$mrc compilation_error=$mce tests_run=$mtn"
    if [ "$mce" != "0" ]; then echo "$rid VOID (编译错误)"; cp "$PRIST/$base" "$src"; return 2; fi
    if [ -z "$mtn" ] || [ "$mtn" -lt 1 ]; then echo "$rid VOID (没跑到用例)"; cp "$PRIST/$base" "$src"; return 2; fi
    fails=$(find "$WT/$APP/target/surefire-reports" "$WT/$SD/target/surefire-reports" \
        -name '*.txt' -newermt "@$round_start" 2>/dev/null | xargs grep -hE "<<< (FAILURE|ERROR)" 2>/dev/null || true)
    echo "--- 失败原文（本轮 mtime 内的报告）---"; echo "$fails"
    hit=$(echo "$fails" | grep -cE "$expect" || true)
    echo "protected_assertion_hits=$hit"
    if [ "$hit" -ge 1 ]; then echo "VERDICT=KILLED"; else echo "VERDICT=SURVIVED"; fi
    cp "$PRIST/$base" "$src"
    rest=$(md5 "$src")
    echo "restored_md5=$rest"
    [ "$rest" = "$orig" ] || { echo "RESTORE_MISMATCH abort"; return 6; }
    {
      echo ""
      echo "===== 装置补记（$rid）====="
      echo "target=$rel"
      echo "orig_md5=$orig"
      echo "mutant_md5=$mut"
      echo "restored_md5=$rest"
      echo "mut_rc=$mrc hits=$hit verdict=$([ "$hit" -ge 1 ] && echo KILLED || echo SURVIVED)"
    } >> "$log"
    self=$(grep -oE 'mutant_md5=[0-9a-f]{32}' "$log" | tail -1 | cut -d= -f2)
    req "$self" self || return 9
    echo "===== end $rid self_md5=$self verdict=$([ "$hit" -ge 1 ] && echo KILLED || echo SURVIVED) ====="
  }
  _body >> "$ALL_LOG" 2>&1
  echo "[$rid]"
  grep -E "orig_md5=|mutant_md5=|mut_rc=|protected_assertion_hits=|VERDICT=|VOID|abort|MISMATCH" "$log" | tail -8
  grep -E "<<< (FAILURE|ERROR)" "$log" | head -3 || true
}

if [ "$#" -gt 0 ]; then ROUNDS=("$@"); else
  ROUNDS=(t10m1 t10m9 t10m2 t10m3 t10m8 t10m4 t10m7 t10m5 t10m6)
fi

for rid in "${ROUNDS[@]}"; do
  case "$rid" in
    t10m1) run_one "$rid" "$GUI_REL" "guiUserPathCommitsDirectlyWithoutApproval" ;;
    t10m9) run_one "$rid" "$GUI_REL" "guiUserPathCommitsDirectlyWithoutApproval" ;;
    t10m2) run_one "$rid" "$NARROW_REL" "gmAgentPathIsDeniedWithoutApprovalAndCommitsAfterApproval" ;;
    t10m3) run_one "$rid" "$SOURCE_REL" "decisionAgentPortHasNoStartDecisionTool" ;;
    t10m8) run_one "$rid" "$SOURCE_REL" "gmAgentPathIsDeniedWithoutApprovalAndCommitsAfterApproval" ;;
    t10m4) run_one "$rid" "$CATALOG_REL" "guiUserPathCommitsDirectlyWithoutApproval" ;;
    t10m7) run_one "$rid" "$SHELL_REL" "guiUserPathCommitsDirectlyWithoutApproval" ;;
    t10m5) run_one "$rid" "$HANDLER_REL" "startDecisionDoesNotConsumeTheR4DirectiveSlot" ;;
    t10m6) run_one "$rid" "$HANDLER_REL" "rejectsUnknownDecisionMaker" ;;
  esac
done

echo "=== summary ==="
grep -E "verdict=|VOID|abort|MISMATCH" "$ALL_LOG" | tail -40
