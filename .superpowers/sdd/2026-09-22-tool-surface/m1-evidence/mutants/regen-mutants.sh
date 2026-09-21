#!/usr/bin/env bash
# 从**最终字节**（spotless:apply 之后）重新冻结 pristine 并重新生成 6 个变异体。
# 变换与首轮**逐字相同**；每条锚串强制断言命中 1 次；生成后打印 diff 供人眼核对。
set -u
WT=/home/dev/SimulatorMosire/.claude/worktrees/ts+m1
EV=$WT/.superpowers/sdd/2026-09-22-tool-surface/m1-evidence
PRI=$EV/mutants/pristine
WL=$EV/mutants/whitelist
MAIN=$WT/simos-app/src/main/java/io/mosire/simos/app/tools
TEST=$WT/simos-app/src/test/java/io/mosire/simos/app/tools

echo "===== 1) 重新冻结 pristine（从最终字节）====="
cp "$MAIN/SimosToolSource.java"            "$PRI/SimosToolSource.java"
cp "$MAIN/write/MapSetTerrainTool.java"    "$PRI/MapSetTerrainTool.java"
cp "$MAIN/write/AbstractNarrowWriteTool.java" "$PRI/AbstractNarrowWriteTool.java"
cp "$TEST/SimosToolsTest.java"             "$PRI/SimosToolsTest.java"
md5sum "$PRI"/*.java > "$PRI/orig_md5.txt"
cat "$PRI/orig_md5.txt"

# 锚串命中数必须恰为 1（命中 0 = 锚点已漂移，命中多 = 会改错地方）
assert1() { n=$(grep -cF "$2" "$1"); [ "$n" = 1 ] || { echo "★ $3: 锚串命中 $n 次（应为 1）"; exit 1; }; echo "  锚串命中=1 $3"; }

echo "===== 2) 重新生成变异体 ====="

echo "--- m1 SimosToolSource：MapSetTerrainTool 从 addGmWrites 挪到 addExternalWrites ---"
F=$PRI/SimosToolSource.java; OUT=$WL/m1/SimosToolSource.java
assert1 "$F" "    built.add(new MapSetTerrainTool(core, initiator, mapId));" m1-anchor
assert1 "$F" "    built.add(new CommandSubmitTool(core, initiator, mapId));" m1-dest
sed -e '/    built.add(new CommandSubmitTool(core, initiator, mapId));/a\    built.add(new MapSetTerrainTool(core, initiator, mapId));' \
    -e '/^    built.add(new MapSetTerrainTool(core, initiator, mapId));$/d' "$F" > "$OUT"
diff -u "$F" "$OUT" | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)'

echo "--- m2 MapSetTerrainTool：commandType() 返回 map.DeleteRegion（与既有工具撞名）---"
F=$PRI/MapSetTerrainTool.java; OUT=$WL/m2/MapSetTerrainTool.java
assert1 "$F" "    return NAME;" m2-anchor
sed 's|    return NAME;|    return "map.DeleteRegion";|' "$F" > "$OUT"
diff -u "$F" "$OUT" | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)'

echo "--- m2b MapSetTerrainTool：commandType() 返回 unit.RenameUnit（不撞名，只有按名判据能杀）---"
OUT=$WL/m2b/MapSetTerrainTool.java
sed 's|    return NAME;|    return "unit.RenameUnit";|' "$F" > "$OUT"
diff -u "$F" "$OUT" | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)'

echo "--- m3 ★ SimosToolsTest：退回索引切片 subList(9,16) ---"
F=$PRI/SimosToolsTest.java; OUT=$WL/m3/SimosToolsTest.java
assert1 "$F" "    List<String> covered = writeFaceCoveredByTheWriteGate();" m3-anchor
sed 's|    List<String> covered = writeFaceCoveredByTheWriteGate();|    List<String> covered = EXTERNAL_UNION_GM_TOOL_NAMES.subList(9, 16);|' "$F" > "$OUT"
diff -u "$F" "$OUT" | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)'

echo "--- m4 AbstractNarrowWriteTool：AskKind.SENSITIVE → MODE_LIMITED ---"
F=$PRI/AbstractNarrowWriteTool.java; OUT=$WL/m4/AbstractNarrowWriteTool.java
assert1 "$F" "AskKind.SENSITIVE);" m4-anchor
sed 's|AskKind.SENSITIVE);|AskKind.MODE_LIMITED);|' "$F" > "$OUT"
diff -u "$F" "$OUT" | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)'

echo "--- m5 AbstractNarrowWriteTool：拒绝理由换成通用文案（"是哪一层拒的"失去判别力）---"
OUT=$WL/m5/AbstractNarrowWriteTool.java
assert1 "$F" "      return ToolSupport.fold(core.submit(command), id, id);" m5-anchor
sed 's|      return ToolSupport.fold(core.submit(command), id, id);|      ToolResult folded = ToolSupport.fold(core.submit(command), id, id);\n      return folded.success()\n          ? folded\n          : ToolResult.error(folded.code(), "{\\"reason\\":\\"载荷非法\\"}");|' "$F" > "$OUT"
diff -u "$F" "$OUT" | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)'

echo "===== 3) md5 与原件对比（须全部不同）====="
for m in m1:SimosToolSource.java m2:MapSetTerrainTool.java m2b:MapSetTerrainTool.java m3:SimosToolsTest.java m4:AbstractNarrowWriteTool.java m5:AbstractNarrowWriteTool.java; do
  id=${m%%:*}; t=${m##*:}
  a=$(md5sum "$PRI/$t" | cut -d' ' -f1); b=$(md5sum "$WL/$id/$t" | cut -d' ' -f1)
  echo "  $id $t  orig=$a mutant=$b  $([ "$a" != "$b" ] && echo OK || echo '★ 相同：作废')"
done
