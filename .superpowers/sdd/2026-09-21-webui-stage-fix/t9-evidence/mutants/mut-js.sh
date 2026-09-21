#!/usr/bin/env bash
# mut-js.sh —— T9 前端护栏变异轮（九道门禁的前端版）。
#   ① pristine 恢复（干净世界）② orig_md5 ③ python 精确替换 + 断言 md5 变
#   ④ node --check 断言变异体能解析（JS 版"编译错误=0"）⑤ 跑全量 node --test
#   ⑥ KILLED = rc!=0 且期望受保护用例名出现在 not ok 里 ⑦ cp 逐字节还原 + md5 相等
#   ⑧ 三个 md5 追加进日志本身（自指）
set -u

WT=/home/cna/SimulatorMosire/.claude/worktrees/wsf-t9
SRC=$WT/simos-app/src/main/resources/webui
JS=$WT/simos-app/src/test/js
EV=$WT/.superpowers/sdd/2026-09-21-webui-stage-fix/t9-evidence/mutants
LOG=$EV/logs
MUTLOG=$LOG/mut-js.log
mkdir -p "$LOG"
: > "$MUTLOG"

md5of() { md5sum "$1" | cut -d' ' -f1; }

mutate() {
  local name="$1" file="$2" pyfile="$3" expect="$4"
  local base orig mut restored out rc tap
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
    echo "$name VOID (mutant==orig)" | tee -a "$MUTLOG"
    cp "$EV/pristine/$base" "$file"
    return
  fi
  if [[ "$base" == *.js ]] && ! node --check "$file" >/dev/null 2>&1; then
    echo "$name VOID (node --check 失败)" | tee -a "$MUTLOG"
    cp "$EV/pristine/$base" "$file"
    return
  fi
  out=$(cd "$JS" && node --test --test-reporter=tap ./*.test.cjs 2>&1)
  rc=$?
  tap=$LOG/$name.tap
  printf '%s\n' "$out" > "$tap"
  cp "$EV/pristine/$base" "$file"
  restored=$(md5of "$file")
  {
    echo "── $name ──"
    echo "file=$base orig_md5=$orig mutant_md5=$mut restored_md5=$restored"
    echo "restored_equals_orig=$([ "$restored" = "$orig" ] && echo true || echo false)"
    echo "node_test_rc=$rc"
    echo "expected_failing_test=$expect"
    if [ "$rc" -ne 0 ] && grep -q "not ok .* $expect" "$tap"; then
      echo "verdict=KILLED"
      grep "not ok .* $expect" "$tap" | head -1 | sed 's/^/red_point=/'
    elif [ "$rc" -ne 0 ]; then
      echo "verdict=RED_BUT_WRONG_POINT"
      grep "not ok " "$tap" | head -5 | sed 's/^/observed=/'
    else
      echo "verdict=SURVIVED"
    fi
    echo ""
  } >> "$MUTLOG"
  echo "$name done"
}

mutate t9js-m1 "$SRC/panels.js" "$EV/py/t9js-m1.py" "right-list-renders-pending-from-server-due"
mutate t9js-m2 "$SRC/panels.js" "$EV/py/t9js-m2.py" "pending-status-text-maps-only-real-booleans-and-never-fabricates"

echo "=== summary ==="
grep -E "^(t9js|.*verdict=)" "$MUTLOG"
