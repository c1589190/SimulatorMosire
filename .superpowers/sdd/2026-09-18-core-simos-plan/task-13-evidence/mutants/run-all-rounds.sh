#!/bin/bash
# Task 13 的 4 轮变异自证。装置是 task-12 的 mut-round.sh（九道门禁逐字沿用，外加报告 mtime 门禁；见其头部说明）。
set -u
E=.superpowers/sdd/2026-09-18-core-simos-plan/task-13-evidence
M=$E/mutants
CORE=simos-core/src/main/java/io/mosire/simos/core
TL=$CORE/timeline
L=$E/logs
mkdir -p $L

run() { # 名字 目标源 目标类 选择器 期望变红的类 说明
  echo "──────── $1 ────────"
  echo "  $6"
  bash $M/mut-round.sh "$2" "$M/$1.$3.java" "-Dtest=$4" "$L/$1.log" "$5"
  rc=$?
  if [ $rc -eq 0 ]; then echo "  ⇒ 杀死"; else echo "  ⇒ !!存活/作废 rc=$rc"; fi
}

run t13-m1 $CORE/CoreSimos.java  CoreSimos CoreSimosTest CoreSimosTest "分岔点不写 checkpoint（计划 m1；打 R8 的 checkpoint 断言）"
run t13-m2 $TL/Timeline.java     Timeline  CoreSimosTest CoreSimosTest "新分支 revision 从 0 起（计划 m2；打 (b2,1) 断言）"
run t13-m3 $CORE/CoreSimos.java  CoreSimos CoreSimosTest CoreSimosTest "封存后静默忽略注册（本任务新增护栏的变异体）"
run t13-m4 $CORE/CoreSimos.java  CoreSimos CoreSimosTest CoreSimosTest "信封支无条件写 checkpoint（多写文件；打 C19 谓词守卫）"
run t13-m5 $CORE/CoreSimos.java  CoreSimos CoreSimosTest CoreSimosTest "信封支命中 C19 却不写 checkpoint（正向行为被删）"
echo "=== 5 轮结束 ==="
