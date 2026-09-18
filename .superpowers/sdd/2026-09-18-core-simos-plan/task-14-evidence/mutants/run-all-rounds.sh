#!/bin/bash
# Task 14 的变异自证。装置是 task-13 的 mut-round.sh（九道门禁逐字沿用，含报告 mtime 门禁与自指补记）。
set -u
E=.superpowers/sdd/2026-09-18-core-simos-plan/task-14-evidence
M=$E/mutants
CORE=simos-core/src/main/java/io/mosire/simos/core
L=$E/logs
mkdir -p $L

run() { # 名字 目标源 目标类 选择器 期望变红的类 说明
  echo "──────── $1 ────────"
  echo "  $6"
  bash $M/mut-round.sh "$2" "$M/$1.$3.java" "-Dtest=$4" "$L/$1.log" "$5"
  rc=$?
  if [ $rc -eq 0 ]; then echo "  ⇒ 杀死"; else echo "  ⇒ !!存活/作废 rc=$rc"; fi
}

run t14-m1 $CORE/timeline/Timeline.java Timeline BranchingEndToEndTest BranchingEndToEndTest "Timeline.head 忽略 branch 参数（计划 m1；打判据一③ 的 b2 head 断言）"
run t14-m2 $CORE/store/Replay.java    Replay   BranchingEndToEndTest BranchingEndToEndTest "Replay 不施加变更集（本任务新增逐值断言的变异体；打判据一① 的两侧名字）"
echo "=== 2 轮结束 ==="
