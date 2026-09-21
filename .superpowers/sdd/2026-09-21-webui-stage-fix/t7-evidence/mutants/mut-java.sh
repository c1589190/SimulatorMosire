#!/usr/bin/env bash
# mut-java.sh —— T7 的 Java 侧变异轮：证明 WebuiAssetsTest 的"六模式真控件"断言对
# **index.html 模式按钮标签漂移**有判别力（modes.js 有模式、index.html 按钮标签对不上 ⇒ Java 红）。
#
# 注意：JS 侧那条静态断言（index-html-has-six-modes-and-decision-panel）只看"含「决策」"与按钮数，
# 本变异体把按钮文字改成 ASCII「Decide」后 **JS 侧仍绿**（左栏 section 里还有「决策」字样）——
# 正是为了让红点**只**落在 Java 断言上，隔离它的判别力（不靠另一条护栏替它红）。
set -u
WT=/home/cna/SimulatorMosire/.claude/worktrees/wsf-t7
SRC=$WT/simos-app/src/main/resources/webui
EV=$WT/.superpowers/sdd/2026-09-21-webui-stage-fix/t7-evidence/mutants
LOG=$EV/logs
MUTLOG=$LOG/mut-java.log
mkdir -p "$LOG"
: > "$MUTLOG"

base=index.html
file=$SRC/$base
cp "$EV/pristine/$base" "$file"
orig=$(md5sum "$file" | cut -d' ' -f1)

python3 - "$file" <<'PY'
import sys
path = sys.argv[1]
old = 'data-mode="decision" aria-pressed="false">决策</button>'
new = 'data-mode="decision" aria-pressed="false">Decide</button>'
text = open(path, encoding="utf-8").read()
assert text.count(old) == 1, text.count(old)
open(path, "w", encoding="utf-8").write(text.replace(old, new, 1))
PY

mut=$(md5sum "$file" | cut -d' ' -f1)
# 变异体健全性：按钮还在（只换了标签），否则这一轮不是"标签漂移"这个变异体。
grep -q 'data-mode="decision"' "$file" || { echo "VOID: 按钮没了，变异体不对"; cp "$EV/pristine/$base" "$file"; exit 1; }

( cd "$WT" && ./mvnw -pl simos-app -am -Dtest=WebuiAssetsTest -Dsurefire.failIfNoSpecifiedTests=false test ) > "$LOG/t7m11-java.log" 2>&1
rc=$?

cp "$EV/pristine/$base" "$file"
restored=$(md5sum "$file" | cut -d' ' -f1)

{
  echo "── t7m11 (Java guard) ──"
  echo "file=$base orig_md5=$orig mutant_md5=$mut restored_md5=$restored"
  echo "restored_equals_orig=$([ "$restored" = "$orig" ] && echo true || echo false)"
  echo "maven_rc=$rc"
  echo "expected_failing_test=WebuiAssetsTest.allSixModesAreEnabledRealControls"
  if [ "$rc" -ne 0 ] && grep -q "allSixModesAreEnabledRealControls" "$LOG/t7m11-java.log"; then
    echo "verdict=KILLED"
    grep -E "allSixModesAreEnabledRealControls|Tests run:.*Failures" "$LOG/t7m11-java.log" | head -3 | sed 's/^/red_point=/'
  elif [ "$rc" -ne 0 ]; then
    echo "verdict=RED_BUT_WRONG_POINT"
    grep -E "Tests run:.*Failures: [1-9]|ERROR.*Failed" "$LOG/t7m11-java.log" | head -5 | sed 's/^/observed=/'
  else
    echo "verdict=SURVIVED"
  fi
} | tee -a "$MUTLOG"
