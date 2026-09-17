#!/bin/bash
# 从 pass-2 的 .kept 里提取报告轮次表的字段（干净世界/改前绿/自证/改后红点原文）
ROUNDS_DIR=/root/SimulatorMosire/.superpowers/sdd/2026-09-16-map-simos-plan/task-14-evidence/rounds
for id in m14v-1 m14v-2 m14v-3 m14v-4 m14v-5 m14v-6 m14v-7 m14v-8 m14v-9; do
  f="$ROUNDS_DIR/$id.kept"
  echo "### $id"
  grep -m1 "干净世界 OK" "$f"
  grep -m1 -E "^\[INFO\] Tests run: .*Skipped: 0$" "$f" | sed 's/^/改前: /'
  grep -m1 "自证：实际" "$f"
  grep -m1 "COMPILATION ERROR count" "$f" | sed 's/^/改后: /'
  grep -m1 "跑过的测试类数" "$f" | sed 's/^/改后: /'
  grep -m2 -E "Tests run: 245" "$f" | tail -1 | sed 's/^/改后: /'
  echo "红点："
  sed -n '/^--- 红点/,/^--- surefire/p' "$f" | grep -E "^Regression|^HexCellTest|^MapChangeSetTest|^RoundTrip|^MapGeneratorTest|^RegionIndex" | sed 's/^/  /'
  grep -m1 "红点数 = " "$f" | sed 's/^/  /'
  echo
done
