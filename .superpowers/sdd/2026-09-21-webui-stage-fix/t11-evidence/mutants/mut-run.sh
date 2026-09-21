#!/usr/bin/env bash
# T11 变异装置（九道门禁）：白名单推成目标类名 / md5 自证 / 清陈旧 .class / COMPILATION ERROR=0 /
# 读本名轮日志 / 逐字节还原 / 日志自指。
#
# 用法：mut-run.sh <round> <target-rel> <mutant-rel> <cmd>
#   T11_RESTORE_EXTRA="a b" 可选：额外备份/还原的文件（如被再生覆盖的资源）
set -u
WT=/home/cna/SimulatorMosire/.claude/worktrees/wsf-t11
E=$WT/.superpowers/sdd/2026-09-21-webui-stage-fix/t11-evidence
ROUND=$1; TARGET=$2; MUTANT=$3; CMD=$4
LOG=$E/mutants/logs/$ROUND.log
EXTRA="${T11_RESTORE_EXTRA:-}"
: > "$LOG"

orig=$(md5sum "$WT/$TARGET" | cut -d' ' -f1)
cp "$WT/$TARGET" "$WT/$TARGET.t11orig"
extra_orig=""
for f in $EXTRA; do cp "$WT/$f" "$WT/$f.t11orig"; extra_orig="$extra_orig $f:$(md5sum "$WT/$f" | cut -d' ' -f1)"; done

cp "$WT/$MUTANT" "$WT/$TARGET"
mut=$(md5sum "$WT/$TARGET" | cut -d' ' -f1)
{
  echo "round=$ROUND"
  echo "target=$TARGET"
  echo "cmd=$CMD"
  echo "orig_md5=$orig"
  echo "mutant_md5=$mut"
  [ -n "$extra_orig" ] && echo "extra_orig_md5=$extra_orig"
} | tee -a "$LOG"

if [ "$orig" = "$mut" ]; then
  echo "VOID: mutant identical to orig (not a mutation)" | tee -a "$LOG"
  cp "$WT/$TARGET.t11orig" "$WT/$TARGET"; rm -f "$WT/$TARGET.t11orig"
  for f in $EXTRA; do cp "$WT/$f.t11orig" "$WT/$f"; rm -f "$WT/$f.t11orig"; done
  exit 3
fi

# 清陈旧 .class（形态：target/classes 里的旧类会活到下一轮）
find "$WT" -path "*/target/classes/*" -name "*.class" -newer "$WT/pom.xml" -delete 2>/dev/null || true

( cd "$WT" && bash -c "$CMD" ) > "$LOG.test" 2>&1
rc=$?
comp=$(grep -c "COMPILATION ERROR" "$LOG.test")
printf 'test_rc=%s\ncompilation_error_count=%s\n' "$rc" "$comp" | tee -a "$LOG"
cat "$LOG.test" >> "$LOG"

cp "$WT/$TARGET.t11orig" "$WT/$TARGET"
rm -f "$WT/$TARGET.t11orig"
restored=$(md5sum "$WT/$TARGET" | cut -d' ' -f1)
printf 'restored_md5=%s\n' "$restored" | tee -a "$LOG"
for f in $EXTRA; do
  cp "$WT/$f.t11orig" "$WT/$f"; rm -f "$WT/$f.t11orig"
  printf 'extra_restored=%s:%s\n' "$f" "$(md5sum "$WT/$f" | cut -d' ' -f1)" | tee -a "$LOG"
done

if [ "$comp" != "0" ]; then echo "VOID: compilation error" | tee -a "$LOG"; exit 4; fi
if [ "$restored" != "$orig" ]; then echo "RESTORE-FAIL" | tee -a "$LOG"; exit 5; fi
echo "round_done=OK" | tee -a "$LOG"
