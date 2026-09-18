#!/bin/bash
# Task 12 的 9 轮变异自证。装置是 task-11 的 mut-round.sh（逐字沿用，只换了 EVID 常量）。
set -u
cd /home/dev/SimulatorMosire
E=.superpowers/sdd/2026-09-18-core-simos-plan/task-12-evidence
M=$E/mutants
ADV=simos-core/src/main/java/io/mosire/simos/core/advance
L=$E/logs
mkdir -p $L

run() { # 名字 目标源 目标类 选择器 期望变红的类 说明
  echo "──────── $1 ────────"
  echo "  $6"
  bash $M/mut-round.sh "$ADV/$2" "$M/$1.$3.java" "-Dtest=$4" "$L/$1.log" "$5"
  rc=$?
  if [ $rc -eq 0 ]; then echo "  ⇒ 杀死"; else echo "  ⇒ !!存活/作废 rc=$rc"; fi
}

run t12-m1 TimeProposalResolver.java TimeProposalResolver TimeProposalResolverTest,TimeAdvanceTest TimeProposalResolverTest "③ 只查一个方向（计划 m1）"
run t12-m2 TimeProposalResolver.java TimeProposalResolver TimeProposalResolverTest,TimeAdvanceTest TimeProposalResolverTest "写-写改成记警告并放行（计划 m2）"
run t12-m3 TimeAdvance.java         TimeAdvance         TimeProposalResolverTest,TimeAdvanceTest TimeAdvanceTest         "Validate 去掉第 0 项（计划 m3）"
run t12-m4 AdvanceConflict.java     AdvanceConflict     TimeProposalResolverTest,TimeAdvanceTest TimeProposalResolverTest "冲突地址不排序（计划 m4）"
run t12-m5 AdvanceConflict.java     AdvanceConflict     TimeProposalResolverTest,TimeAdvanceTest TimeProposalResolverTest "namespaces 也排序（Task 12 实测缺陷的复原体）"
run t12-m6 TimeAdvance.java         TimeAdvance         TimeProposalResolverTest,TimeAdvanceTest TimeAdvanceTest         "参与者按注册序而非字典序（C25/裁定 44）"
run t12-m7 TimeAdvance.java         TimeAdvance         TimeProposalResolverTest,TimeAdvanceTest TimeAdvanceTest         "提交失败无条件折成 Conflict（裁定 48 的要害）"
run t12-m8 TimeAdvance.java         TimeAdvance         TimeProposalResolverTest,TimeAdvanceTest TimeAdvanceTest         "checkpoint 丢掉未触碰的模块"
run t12-m9 TimeAdvance.java         TimeAdvance         TimeProposalResolverTest,TimeAdvanceTest TimeAdvanceTest         "checkpoint 信封坐标用 base 的"
echo "=== 9 轮结束 ==="
