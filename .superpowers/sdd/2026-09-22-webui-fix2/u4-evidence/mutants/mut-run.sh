#!/usr/bin/env bash
# U4 变异装置（九道门禁）：白名单推成目标名 / md5 自证 / 清陈旧 .class / COMPILATION ERROR=0 /
# 逐字节还原 / 日志自指（把本轮的 orig_md5/mutant_md5/restored_md5 写进日志本身）。
#
# 用法：mut-run.sh <round> <target-rel> <mutant-rel> "<judge-cmd>"
#   judge-cmd 的工作目录 = worktree 根；退出码非 0 视为"被杀"。
set -u
WT=/home/cna/SimulatorMosire/.claude/worktrees/wsf2-u4
E=$WT/.superpowers/sdd/2026-09-22-webui-fix2/u4-evidence
ROUND=$1; TARGET=$2; MUTANT=$3; CMD=$4
LOG=$E/mutants/logs/$ROUND.log
: > "$LOG"

orig=$(md5sum "$WT/$TARGET" | cut -d' ' -f1)
cp "$WT/$TARGET" "$WT/$TARGET.u4orig"
cp "$WT/$MUTANT" "$WT/$TARGET"
mut=$(md5sum "$WT/$TARGET" | cut -d' ' -f1)
{
  echo "round=$ROUND"
  echo "target=$TARGET"
  echo "mutant=$MUTANT"
  echo "cmd=$CMD"
  echo "orig_md5=$orig"
  echo "mutant_md5=$mut"
} | tee -a "$LOG"

if [ "$orig" = "$mut" ]; then
  echo "VOID: mutant identical to orig (not a mutation)" | tee -a "$LOG"
  cp "$WT/$TARGET.u4orig" "$WT/$TARGET"; rm -f "$WT/$TARGET.u4orig"
  exit 3
fi

# 清陈旧 .class（形态：target/classes 里的旧类会活到下一轮）
find "$WT" -path "*/target/classes/*" -name "*.class" -delete 2>/dev/null || true
# 清陈旧复制过去的资源（形态：target/classes 里的 v17levant.json 可能是上一轮的）
find "$WT" -path "*/target/classes/worlds/v17levant.json" -delete 2>/dev/null || true

( cd "$WT" && bash -c "$CMD" ) > "$LOG.test" 2>&1
rc=$?
comp=$(grep -c "COMPILATION ERROR" "$LOG.test")
printf 'judge_rc=%s\ncompilation_error_count=%s\n' "$rc" "$comp" | tee -a "$LOG"
cat "$LOG.test" >> "$LOG"

cp "$WT/$TARGET.u4orig" "$WT/$TARGET"
rm -f "$WT/$TARGET.u4orig"
restored=$(md5sum "$WT/$TARGET" | cut -d' ' -f1)
printf 'restored_md5=%s\n' "$restored" | tee -a "$LOG"

if [ "$comp" != "0" ]; then echo "VOID: compilation error" | tee -a "$LOG"; exit 4; fi
if [ "$restored" != "$orig" ]; then echo "RESTORE-FAIL" | tee -a "$LOG"; exit 5; fi
if [ "$rc" = "0" ]; then echo "SURVIVED" | tee -a "$LOG"; else echo "KILLED" | tee -a "$LOG"; fi
echo "round_done=OK" | tee -a "$LOG"
