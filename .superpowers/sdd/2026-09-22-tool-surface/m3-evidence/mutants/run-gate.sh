#!/usr/bin/env bash
# M3 关账门禁驱动：跑全量 `./mvnw clean verify`，把 rc 落到文件、并给日志加**自指段**。
#
# 为什么要包一层脚本（本树机制，实测过三次）：本机 nproc=2，全量门禁耗时**压在 Bash 工具 600 s 线附近**，
# 一旦越过，harness 会把进程摘到后台；若此时被 SIGKILL，**rc 就丢了**。包里之后 rc 先落盘，
# 即使调用方被摘/被杀，也能从 rc 文件与日志读到这一轮到底跑没跑完。
set -uo pipefail
WT=/home/dev/SimulatorMosire/.claude/worktrees/ts+m1
EV="$WT/.superpowers/sdd/2026-09-22-tool-surface/m3-evidence"
MUT="$EV/mutants"
ATTEMPT="${1:-2}"
LOG="$EV/logs/clean-verify.attempt$ATTEMPT.log"
RCF="$EV/logs/clean-verify.attempt$ATTEMPT.rc"
md5of() { md5sum "$1" | cut -d' ' -f1; }

{
  echo "════════════════ M3 关账门禁：./mvnw clean verify ════════════════"
  echo "attempt=$ATTEMPT  起跑时刻: $(date '+%F %T')"
  echo "HEAD=$(git -C "$WT" rev-parse HEAD 2>/dev/null || echo '(无 git)')"
  echo "nproc=$(nproc)  baseline.md5 自记 md5=$(md5of "$BASELINE" 2>/dev/null || echo n/a)"
  echo "该轮跑的是这些字节（17 个基线文件的 md5）："
  while read -r base path; do
    echo "  $base  $path"
  done < "$MUT/baseline.md5"
  echo "════════════════════════════════════════════════════════════════════"
} > "$LOG" 2>&1

cd "$WT" || exit 9
./mvnw clean verify >> "$LOG" 2>&1
RC=$?
echo "$RC" > "$RCF"

PREFIX_MD5=$(md5of "$LOG")
{
  echo "════════════════════════════════════════════════════════════════════"
  echo "── 装置自记（本段由 run-gate.sh 追加；log_prefix = 本段之前的字节）──"
  echo "mvn_rc=$RC"
  echo "log_prefix_md5=$PREFIX_MD5"
  echo "driver_md5    =$(md5of "$0")"
  echo "baseline_md5  =$(md5of "$MUT/baseline.md5")"
  echo "结束时刻: $(date '+%F %T')"
} >> "$LOG" 2>&1

echo "attempt=$ATTEMPT mvn_rc=$RC"
echo "log=$LOG"
echo "log_md5=$(md5of "$LOG")"
