#!/usr/bin/env bash
# run-java.sh —— T8 Java 侧变异轮装置（九道门禁的 Java 版，照 T6 同法）。
#   ① 干净世界（pristine 恢复 + md5）② 变异体字节不同 ③ 推到规范名目标文件 ④ 清陈旧 .class + reports
#   ⑤ COMPILATION ERROR=0 ⑥ surefire 报告 mtime 落本轮 ⑦ 红点落**被保护断言**（expect 正则）
#   ⑧ cp 逐字节还原 ⑨ 三个 md5 + 判定追加进日志（自指）。
# 用法: run-java.sh <round> <模块内相对路径> <逗号分隔测试类> <期望失败方法名>
set -u
WT=/home/cna/SimulatorMosire/.claude/worktrees/wsf-t8
EV=$WT/.superpowers/sdd/2026-09-21-webui-stage-fix/t8-evidence/mutants
LOG=$EV/logs
MUTLOG=$LOG/mut-java.log
mkdir -p "$LOG"; touch "$MUTLOG"
md5of() { md5sum "$1" | cut -d' ' -f1; }

round="$1"; rel="$2"; tests="$3"; expect="$4"
file="$WT/$rel"; base=$(basename "$rel")
cp "$EV/pristine/$base" "$file"
orig=$(md5of "$file")
if ! python3 "$EV/py/mutate.py" "$round" "$file" >/dev/null; then
  echo "$round VOID (mutate failed)" | tee -a "$MUTLOG"; cp "$EV/pristine/$base" "$file"; exit 1
fi
mut=$(md5of "$file")
if [ "$orig" = "$mut" ]; then
  echo "$round VOID (mutant==orig)" | tee -a "$MUTLOG"; cp "$EV/pristine/$base" "$file"; exit 1
fi
MODULE="${rel%%/*}"
rm -rf "$WT/$MODULE/target/surefire-reports"
ROUND_START=$(date +%s)
( cd "$WT" && ./mvnw -pl "$MODULE" -am -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false \
    -Dmaven.test.failure.ignore=true test ) > "$LOG/$round.mvn" 2>&1
mrc=$?
cp "$EV/pristine/$base" "$file"
restored=$(md5of "$file")
mce=$(grep -c "COMPILATION ERROR" "$LOG/$round.mvn" || true)
REPORTS=$(find "$WT" -path '*/target/surefire-reports/*.txt' -newermt "@$ROUND_START" 2>/dev/null || true)
FAIL_LINES=""
if [ -n "$REPORTS" ]; then
  FAIL_LINES=$(echo "$REPORTS" | xargs grep -hE "<<< (FAILURE|ERROR)" 2>/dev/null || true)
fi
HIT=0
if [ -n "$FAIL_LINES" ]; then HIT=$(echo "$FAIL_LINES" | grep -cE "$expect" || true); fi
{
  echo "── $round ──"
  echo "file=$base orig_md5=$orig mutant_md5=$mut restored_md5=$restored"
  echo "restored_equals_orig=$([ "$restored" = "$orig" ] && echo true || echo false)"
  echo "mvn_rc=$mrc compilation_error=$mce"
  echo "expected_failing_test=$expect"
  echo "reports_this_round=$(echo "$REPORTS" | grep -c . || true)"
  if [ "$mce" != "0" ]; then
    echo "verdict=VOID (compilation error)"
  elif [ -z "$REPORTS" ]; then
    echo "verdict=VOID (no fresh surefire report ⇒ 没跑到)"
  elif [ "$HIT" -ge 1 ]; then
    echo "verdict=KILLED"
    echo "$FAIL_LINES" | grep -E "$expect" | head -1 | sed 's/^/red_point=/'
  else
    echo "verdict=RED_BUT_WRONG_POINT"
    echo "$FAIL_LINES" | head -5 | sed 's/^/observed=/'
  fi
  echo ""
} | tee -a "$MUTLOG"
