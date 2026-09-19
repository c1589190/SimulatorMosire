#!/usr/bin/env bash
# M9 T13/T14 变异装置（干净世界 + 自指字节）。
# 每轮：还原原件 → 断言与原件 md5 一致 → 推入变异体 → 断言字节不同 → 跑目标 → 断言红点命中 →
#        断言无编译错误 → 还原原件 → 断言 md5 复原。日志每轮自指"这一轮跑的是哪份字节"。
# 用法: mut-round.sh <m1|m2|m3|m4|m5>
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m9t13
EV="$ROOT/.superpowers/sdd/2026-09-19-map-perf/t13-evidence"
MUT="$EV/mutants"; ORIG="$MUT/orig"; LOGS="$EV/logs"
ID="${1:?need mutation id}"
LOG="$LOGS/mut-$ID.log"
mkdir -p "$LOGS"
md5f() { md5sum "$1" | awk '{print $1}'; }
cd "$ROOT" || exit 2

case "$ID" in
  m1) SRC="simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java"; O="$ORIG/ApiViews.java"; M="$MUT/m1.ApiViews.java"; MODE=maven; PAT="外圈块带一个洞环" ;;
  m2) SRC="simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java"; O="$ORIG/ApiViews.java"; M="$MUT/m2.ApiViews.java"; MODE=maven; PAT="BlockId 全序" ;;
  m2b) SRC="simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java"; O="$ORIG/ApiViews.java"; M="$MUT/m2b.ApiViews.java"; MODE=maven; PAT="逐字节相同" ;;
  m4) SRC="simos-map/src/main/java/io/mosire/simos/map/block/TerrainBlocks.java"; O="$ORIG/TerrainBlocks.java"; M="$MUT/m4.TerrainBlocks.java"; MODE=maven; PAT="2 块" ;;
  m3) SRC="simos-app/target/classes/webui/map.js"; O="$ORIG/map.js"; M="$MUT/m3.map.js"; MODE=e2e; PAT="pixel--68_3" ;;
  m5) SRC="simos-app/target/classes/webui/blocks.js"; O="$ORIG/blocks.js"; M="$MUT/m5.blocks.js"; MODE=e2e; PAT="pick--42_-1" ;;
  *) echo "unknown id $ID"; exit 2 ;;
esac

{
  echo "=== mutation $ID ==="
  echo "src=$SRC"
  echo "orig_md5_expected=$(md5f "$O")"
  echo "mutant_md5=$(md5f "$M")"

  # 1) 干净世界：先把原件还原到目标，并断言其 md5 == 原件 md5
  cp "$O" "$SRC"
  was=$(md5f "$SRC")
  echo "after_restore_md5=$was"
  if [ "$was" != "$(md5f "$O")" ]; then echo "VERDICT=VOID(restore-failed)"; exit 0; fi

  # 2) 推入变异体并断言字节确实不同
  cp "$M" "$SRC"
  now=$(md5f "$SRC")
  echo "pushed_md5=$now"
  if [ "$now" = "$was" ]; then echo "VERDICT=VOID(mutant-identical)"; exit 0; fi

  # 3) 跑目标
  if [ "$MODE" = maven ]; then
    ./mvnw -q -pl simos-app -am -Dtest=MapOverviewBlocksTest -Dsurefire.failIfNoSpecifiedTests=false \
      -Dcheckstyle.skip=true -Dspotless.check.skip=true test > "$LOG" 2>&1
  else
    NODE_PATH=/home/cna/.npm/_npx/e41f203b7505f1fb/node_modules \
      node "$EV/harness/t13-e2e.cjs" http://127.0.0.1:5821 "$LOGS/mut-$ID-e2e.json" "$LOGS/mut-$ID.png" > "$LOG" 2>&1
  fi
  rc=$?
  echo "target_rc=$rc"

  # 4) 门禁：无编译错误；红点命中
  comp=$(grep -c "COMPILATION ERROR" "$LOG" || true)
  echo "compilation_errors=$comp"
  hits=$(grep -c "$PAT" "$LOG" || true)
  echo "red_pattern='$PAT' hits=$hits"
  fail=$(grep -cE "FAIL|Failures: [1-9]" "$LOG" || true)
  echo "failure_lines=$fail"

  # 5) 还原并断言 md5 复原
  cp "$O" "$SRC"
  restored=$(md5f "$SRC")
  echo "restored_md5=$restored"

  if [ "$comp" != "0" ]; then
    echo "VERDICT=VOID(compilation-error)"
  elif [ "$rc" = "0" ] || [ "$hits" = "0" ]; then
    echo "VERDICT=SURVIVED"
  elif [ "$restored" != "$(md5f "$O")" ]; then
    echo "VERDICT=VOID(restore-md5-mismatch)"
  else
    echo "VERDICT=KILLED"
  fi
} | tee "$LOG.summary"
