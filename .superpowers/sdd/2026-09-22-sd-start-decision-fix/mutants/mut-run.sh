#!/usr/bin/env bash
# mut-run.sh —— 两个变异体的自证装置（2026-09-22 sd.StartDecision 去战斗前提）。
#   每轮：干净世界 → 记录 orig md5 → 打补丁 → 断言字节确实不同 → 跑判据 → 红点必须落被保护断言
#         → cp 逐字节还原 → 复核 md5 == orig（并把自证写进日志）。
#   ★ 用法：bash mut-run.sh m1|m2
set -u
WT=/home/cna/SimulatorMosire/.claude/worktrees/sdfix
EV=$WT/.superpowers/sdd/2026-09-22-sd-start-decision-fix
MUT=$EV/mutants
ORIG=$MUT/orig
LOGS=$MUT/logs
mkdir -p "$ORIG" "$LOGS"

SVC=simos-app/src/main/java/io/mosire/simos/app/sd/DecisionAdjudicationService.java
PANELS=simos-app/src/main/resources/webui/panels.js
LOG=$LOGS/$1.log
: > "$LOG"

snap() {  # $1=relpath  $2=tag
  ( cd "$WT" && md5sum "$1" | awk '{print $1}' )
}

restore_and_selfcheck() {  # $1=relpath $2=origfile $3=orig_md5
  cp "$2" "$WT/$1"
  local now; now=$(snap "$1")
  echo "restore=$1 orig_md5=$3 now_md5=$now identical=$([ "$now" = "$3" ] && echo YES || echo NO)" >> "$LOG"
  if [ "$now" != "$3" ]; then echo "FATAL: restore mismatch for $1" >&2; return 1; fi
}

case "$1" in
  m1)
    TGT="$SVC"
    cp "$WT/$TGT" "$ORIG/DecisionAdjudicationService.java"
    ORIG_MD5=$(snap "$TGT")
    python3 - "$WT/$TGT" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
old = '''    if (subject.isEmpty()) {
      // ★ 无战斗 ⇒ 不跳过判决，跑决策人自己的决策断点 D2（战斗只是输入，不是前提）。
      String decisionSubject = "sd:decision-maker." + maker.id().value();
      return runner.run(
          branch,
          baseRevision,
          List.of(Breakpoints.D2),
          Map.of(Breakpoints.D2, decisionSubject),
          Map.of(Breakpoints.D2, decisionBrief(sd, maker)));
    }'''
new = '''    if (subject.isEmpty()) {
      return List.of();
    }'''
assert old in s, "M1 anchor not found"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
    echo "m1 = 把「无战斗 ⇒ 跳过判决」的 gate 加回去（返回空，不调 LLM）" >> "$LOG"
    ;;
  m2)
    TGT="$PANELS"
    cp "$WT/$TGT" "$ORIG/panels.js"
    ORIG_MD5=$(snap "$TGT")
    python3 - "$WT/$TGT" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
old = '''        if (e && e.status === 409 && mayRetry) {
          var current = e.body && e.body.current ? e.body.current : null;
          setStartDecisionStatus("末端已移动（409），重取最新状态后重试…", "warn");
          return Promise.resolve()
            .then(function () {
              return app.refreshState ? app.refreshState(true) : null;
            })
            .catch(function () {
              return null;
            })
            .then(function () {
              var fresh =
                current && current.revision !== undefined
                  ? current.revision
                  : (app.target() || {}).revision;
              if (app.setRevision && fresh !== null && fresh !== undefined) {
                app.setRevision(fresh);
              }
              return attemptStartDecision(target, branch, fresh, false);
            });
        }'''
new = '''        if (e && e.status === 409 && mayRetry) {
          setStartDecisionStatus("末端已移动（409），已重取最新状态", "warn");
          if (app.refreshState) {
            app.refreshState(true);
          }
          return null;
        }'''
assert old in s, "M2 anchor not found"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
    echo "m2 = 409 分支不再重取+重试（只提示，不接重试）" >> "$LOG"
    ;;
  *)
    echo "usage: $0 m1|m2" >&2; exit 2;;
esac

MUT_MD5=$(snap "$TGT")
echo "target=$TGT orig_md5=$ORIG_MD5 mutant_md5=$MUT_MD5 byte_differs=$([ "$ORIG_MD5" != "$MUT_MD5" ] && echo YES || echo NO)" >> "$LOG"

if [ "$1" = "m1" ]; then
  ( cd "$WT" && ./mvnw -pl simos-app -am -Dtest=AdjudicationEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false test ) >> "$LOG" 2>&1
  RC=$?
else
  ( cd "$WT" && node simos-app/src/test/js/run-gate.cjs ) >> "$LOG" 2>&1
  RC=$?
fi
echo "gate_rc=$RC" >> "$LOG"
grep -c "COMPILATION ERROR" "$LOG" | sed 's/^/compilation_errors=/' >> "$LOG"

restore_and_selfcheck "$TGT" "$ORIG/$(basename "$TGT")" "$ORIG_MD5"
echo "verdict=$([ "$RC" -ne 0 ] && echo KILLED || echo SURVIVED)" >> "$LOG"
tail -5 "$LOG"
