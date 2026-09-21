#!/usr/bin/env bash
# run-js.sh —— T8 前端（JS/CSS/HTML）变异轮装置（九道门禁的前端版）。
#   ① 干净世界（pristine 恢复 + md5）② 变异体字节不同 md5 自证 ③ *.js 过 node --check ④ 跑全量
#   node --test（TAP）⑤ KILLED = rc!=0 且**期望的受保护用例名**在 not ok 里 ⑥ cp 逐字节还原并复核 md5
#   ⑦ 三个 md5 + 判定追加进日志（自指）。
# 用法: run-js.sh <round> <webui-相对路径> <期望失败用例名>
set -u
WT=/home/cna/SimulatorMosire/.claude/worktrees/wsf-t8
SRC=$WT/simos-app/src/main/resources/webui
JS=$WT/simos-app/src/test/js
EV=$WT/.superpowers/sdd/2026-09-21-webui-stage-fix/t8-evidence/mutants
LOG=$EV/logs
MUTLOG=$LOG/mut-js.log
mkdir -p "$LOG"; touch "$MUTLOG"
md5of() { md5sum "$1" | cut -d' ' -f1; }

round="$1"; rel="$2"; expect="$3"
file="$SRC/$rel"; base=$(basename "$rel")
cp "$EV/pristine/$base" "$file"
orig=$(md5of "$file")
if ! python3 "$EV/py/mutate.py" "$round" "$file" >/dev/null; then
  echo "$round VOID (mutate failed)" | tee -a "$MUTLOG"; cp "$EV/pristine/$base" "$file"; exit 1
fi
mut=$(md5of "$file")
if [ "$orig" = "$mut" ]; then
  echo "$round VOID (mutant==orig)" | tee -a "$MUTLOG"; cp "$EV/pristine/$base" "$file"; exit 1
fi
if [[ "$base" == *.js ]] && ! node --check "$file" >/dev/null 2>&1; then
  echo "$round VOID (node --check failed)" | tee -a "$MUTLOG"; cp "$EV/pristine/$base" "$file"; exit 1
fi
out=$(cd "$JS" && node --test --test-reporter=tap ./*.test.cjs 2>&1); rc=$?
log=$LOG/$round.tap; printf '%s\n' "$out" > "$log"
cp "$EV/pristine/$base" "$file"
restored=$(md5of "$file")
{
  echo "── $round ──"
  echo "file=$base orig_md5=$orig mutant_md5=$mut restored_md5=$restored"
  echo "restored_equals_orig=$([ "$restored" = "$orig" ] && echo true || echo false)"
  echo "node_test_rc=$rc"
  echo "expected_failing_test=$expect"
  if [ "$rc" -ne 0 ] && grep -q "not ok .* $expect" "$log"; then
    echo "verdict=KILLED"; grep "not ok .* $expect" "$log" | head -1 | sed 's/^/red_point=/'
  elif [ "$rc" -ne 0 ]; then
    echo "verdict=RED_BUT_WRONG_POINT"; grep "not ok " "$log" | head -5 | sed 's/^/observed=/'
  else
    echo "verdict=SURVIVED"
  fi
  echo ""
} | tee -a "$MUTLOG"
