#!/usr/bin/env bash
# 证明 `spotless:apply` 只改了**注释行**（代码面零改动）：
# 逐文件对 pre-format 存档做 diff，逐条判「被改的行是否为注释行」；出现一条非注释改动即报红。
# 用法: bash check-format-only-comments.sh
set -u
EV=/home/dev/SimulatorMosire/.claude/worktrees/ts+m1/.superpowers/sdd/2026-09-22-tool-surface/m1-evidence
WT=/home/dev/SimulatorMosire/.claude/worktrees/ts+m1
PRE=$EV/mutants/preformat
APP=$WT/simos-app/src

PAIRS="
SimosToolSource.java|$APP/main/java/io/mosire/simos/app/tools/SimosToolSource.java
MapSetTerrainTool.java|$APP/main/java/io/mosire/simos/app/tools/write/MapSetTerrainTool.java
AbstractNarrowWriteTool.java|$APP/main/java/io/mosire/simos/app/tools/write/AbstractNarrowWriteTool.java
SimosToolsTest.java|$APP/test/java/io/mosire/simos/app/tools/SimosToolsTest.java
McpServerTest.java|$APP/test/java/io/mosire/simos/app/McpServerTest.java
McpPortTopologyTest.java|$APP/test/java/io/mosire/simos/app/McpPortTopologyTest.java
MapSetEdgeTool.java|$APP/main/java/io/mosire/simos/app/tools/write/MapSetEdgeTool.java
MapCreateRegionTool.java|$APP/main/java/io/mosire/simos/app/tools/write/MapCreateRegionTool.java
MapUpdateRegionTool.java|$APP/main/java/io/mosire/simos/app/tools/write/MapUpdateRegionTool.java
MapDeleteRegionTool.java|$APP/main/java/io/mosire/simos/app/tools/write/MapDeleteRegionTool.java
MapRandomizeRegionTool.java|$APP/main/java/io/mosire/simos/app/tools/write/MapRandomizeRegionTool.java
MapRegisterPathwayGroupTool.java|$APP/main/java/io/mosire/simos/app/tools/write/MapRegisterPathwayGroupTool.java
"

total_changed=0; total_comment=0; total_code=0
echo "文件 / 改动行数 / 注释行 / 非注释行"
while IFS='|' read -r b d; do
  [ -n "$b" ] || continue
  p1=$PRE/$b; p2=$PRE/renamed-hyph/$b
  if [ -f "$p1" ]; then p=$p1; elif [ -f "$p2" ]; then p=$p2; else echo "  ★ 存档缺失: $b"; total_code=$((total_code+1)); continue; fi
  diff -u "$p" "$d" | awk '/^[+-]/ && !/^\+\+\+/ && !/^---/' > /tmp/fmt-changed.$$
  n=$(grep -c . /tmp/fmt-changed.$$ || true)
  c=$(grep -cE '^[+-][[:space:]]*(\*|/\*|//|$)' /tmp/fmt-changed.$$ || true)
  k=$((n - c))
  total_changed=$((total_changed+n)); total_comment=$((total_comment+c)); total_code=$((total_code+k))
  printf "  %-34s 改动=%-4s 注释=%-4s 非注释=%s\n" "$b" "$n" "$c" "$k"
  if [ "$k" != 0 ]; then echo "      ★★ 非注释改动逐条："; grep -vE '^[+-][[:space:]]*(\*|/\*|//|$)' /tmp/fmt-changed.$$ | sed 's/^/        /'; fi
  rm -f /tmp/fmt-changed.$$
done <<< "$PAIRS"
echo "合计：改动行=$total_changed  注释行=$total_comment  非注释行=$total_code"
if [ "$total_code" = 0 ]; then echo "结论：spotless 只改注释 ⇒ 代码面零改动（证据不作废）"; else echo "结论：★★ 有非注释改动 ⇒ 必须按「改动被测文件」重跑相关轮"; exit 1; fi
