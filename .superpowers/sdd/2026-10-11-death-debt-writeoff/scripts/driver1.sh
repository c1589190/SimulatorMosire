#!/usr/bin/env bash
# 户绝⇒债务是否消除 调查 —— 两个真世界逐日 0→600（small-world / three-powers）。
# 一次一个世界实例；每个世界收工由装置对**该次 PID** 发 SIGTERM（装置内 proc.send_signal，不用 pkill -f）。
set -u
D=/home/cna/SimulatorMosire/.superpowers/sdd/2026-10-11-death-debt-writeoff
JAR=/home/cna/SimulatorMosire/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
KEEP='event=(DEBT_FORGIVE|POPULATION_CHANGE_APPLIED|HOUSEHOLD_VITAL_WRITEBACK|DEBT_REPAY_SELF_HELD_SKIPPED|HOUSEHOLD_POPULATION_DELTA_REJECTED|MIGRATION_DEBT_SELF_NETTED|DEBT_INTEREST_SELF_HELD_SKIPPED)'
echo "=== jar md5: $(md5sum "$JAR" | cut -d' ' -f1)"
echo "=== start $(date -Is)"

run() { # tag world port config plan
  echo "--- [$1] 起 $(date -Is)"
  python3 "$D/scripts/dd_run.py" \
    --jar "$JAR" --tag "$1" --store "$D/stores/$1" --gui-port "$3" \
    --world "$2" --config "$D/scripts/$4" --plan "$D/scripts/$5" \
    --out "$D/results" --dump-dir "$D/dumps" \
    --daily-out "$D/daily/$1.agg.jsonl.gz" \
    --hh-out "$D/daily/$1.hh.jsonl.gz" \
    --debt-out "$D/daily/$1.debt.jsonl.gz" \
    --cp-out "$D/daily/$1.cp.jsonl" \
    --log-level DEBUG --log-keep "$KEEP"
  echo "--- [$1] rc=$? $(date -Is)"
}

run sw600 small-world  10511 shell-sw.json plan-sw600.json
run tp600 three-powers 10521 shell-tp.json plan-tp600.json

echo "=== done $(date -Is)"
