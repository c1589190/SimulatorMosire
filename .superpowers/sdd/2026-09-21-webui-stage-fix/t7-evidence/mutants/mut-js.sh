#!/usr/bin/env bash
# mut-js.sh —— T7 前端护栏的变异装置（九道门禁的前端版）。
#
# 每轮：
#   ① 从 pristine/ 恢复目标文件（干净世界）
#   ② 记录 orig_md5
#   ③ 用 python 精确替换生成变异体；断言 mutant_md5 != orig_md5（否则这一轮作废）
#   ④ node --check 断言变异体**能解析**（JS 版的"COMPILATION ERROR=0"；不解析 ⇒ VOID）
#   ⑤ 跑全量 node --test（TAP），记录 rc 与失败用例名
#   ⑥ KILLED 判定 = rc!=0 **且** 期望的受保护用例名出现在 not ok 里
#   ⑦ cp 逐字节还原，断言 restored_md5 == orig_md5
#   ⑧ 把这一轮跑的是哪份字节（三个 md5）**追加进日志本身**（自指）
set -u

WT=/home/cna/SimulatorMosire/.claude/worktrees/wsf-t7
SRC=$WT/simos-app/src/main/resources/webui
JS=$WT/simos-app/src/test/js
EV=$WT/.superpowers/sdd/2026-09-21-webui-stage-fix/t7-evidence/mutants
LOG=$EV/logs
MUTLOG=$LOG/mut-js.log

mkdir -p "$LOG"
: > "$MUTLOG"

md5of() { md5sum "$1" | cut -d' ' -f1; }

# mutate <name> <file> <python-expr-file> <expected-test-name>
mutate() {
  local name="$1" file="$2" pyfile="$3" expect="$4"
  local base out log rc orig mut restored
  base=$(basename "$file")
  cp "$EV/pristine/$base" "$file"
  orig=$(md5of "$file")
  if ! python3 "$pyfile" "$file"; then
    echo "$name VOID (python 替换失败)" | tee -a "$MUTLOG"
    cp "$EV/pristine/$base" "$file"
    return
  fi
  mut=$(md5of "$file")
  if [ "$orig" = "$mut" ]; then
    echo "$name VOID (mutant==orig, md5 未变 ⇒ 替换没生效)" | tee -a "$MUTLOG"
    cp "$EV/pristine/$base" "$file"
    return
  fi
  # JS 版编译检查：语法错误 ⇒ 这一轮作废（不把"没跑到"当"红"）。
  if [[ "$base" == *.js ]] && ! node --check "$file" >/dev/null 2>&1; then
    echo "$name VOID (node --check 失败 ⇒ 变异体不解析)" | tee -a "$MUTLOG"
    cp "$EV/pristine/$base" "$file"
    return
  fi
  out=$(cd "$JS" && node --test --test-reporter=tap ./*.test.cjs 2>&1)
  rc=$?
  log=$LOG/$name.tap
  printf '%s\n' "$out" > "$log"
  cp "$EV/pristine/$base" "$file"
  restored=$(md5of "$file")
  {
    echo "── $name ──"
    echo "file=$base orig_md5=$orig mutant_md5=$mut restored_md5=$restored"
    echo "restored_equals_orig=$([ "$restored" = "$orig" ] && echo true || echo false)"
    echo "node_test_rc=$rc"
    echo "expected_failing_test=$expect"
    if [ "$rc" -ne 0 ] && grep -q "not ok .* $expect" "$log"; then
      echo "verdict=KILLED"
      grep "not ok .* $expect" "$log" | head -1 | sed 's/^/red_point=/'
    elif [ "$rc" -ne 0 ]; then
      echo "verdict=RED_BUT_WRONG_POINT"
      grep "not ok " "$log" | head -5 | sed 's/^/observed=/'
    else
      echo "verdict=SURVIVED"
    fi
    echo ""
  } >> "$MUTLOG"
  echo "$name done"
}

mutate t7m1  "$SRC/panels.js" "$EV/py/t7m1.py" "decision-groups-put-nation-then-army-and-sort-by-id"
mutate t7m2  "$SRC/map.js"    "$EV/py/t7m2.py" "nation-region-ids-are-set-equal-not-subset"
mutate t7m3  "$SRC/index.html" "$EV/py/t7m3.py" "index-html-has-six-modes-and-decision-panel"
mutate t7m4  "$SRC/panels.js" "$EV/py/t7m4.py" "subpage-visibility-is-mutually-exclusive"
mutate t7m5  "$SRC/modes.js"  "$EV/py/t7m5.py" "decision-allows-no-write"
mutate t7m6  "$SRC/panels.js" "$EV/py/t7m6.py" "pending-status-text-never-fabricates"
mutate t7m7  "$SRC/panels.js" "$EV/py/t7m7.py" "decision-maker-for-unit-resolves-root-and-descendants"
mutate t7m8  "$SRC/map.js"    "$EV/py/t7m8.py" "map-js-wires-decision-click-to-nation-highlight"
mutate t7m9  "$SRC/app.js"    "$EV/py/t7m9.py" "panels-js-and-app-js-delegate-subpage-visibility"
mutate t7m10 "$SRC/api.js"    "$EV/py/t7m10.py" "api.js-declares-exactly-the-allowed-write-endpoints"

echo "=== summary ==="
grep -E "^(t7m|.*verdict=)" "$MUTLOG"
