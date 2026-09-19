#!/usr/bin/env bash
# 由**当前磁盘上的原件**逐行派生变异体（保证最小 diff、可审计），落到证据目录。
# 每处替换都先断言匹配次数恰为 1（形态：装置的产物必须自证）。
set -eu
ROOT=/home/dev/SimulatorMosire/.claude/worktrees/m8t5
EV="$ROOT/.superpowers/sdd/2026-09-19-map-edit/t5t6-evidence"
mkdir -p "$EV/mutants/orig"

sub_once() { # <file> <from> <to> <label>
  local f="$1" from="$2" to="$3" label="$4"
  local n
  n=$(grep -Fc -- "$from" "$f" || true)
  if [ "$n" != "1" ]; then echo "FATAL[$label]: 锚点匹配 $n 次（应为 1）"; exit 2; fi
  perl -0pi -e "s/\Q$from\E/$to/" "$f"
  echo "[$label] 替换 1 处 OK"
}

# ── 原件备份（干净世界的参照）──────────────────────────────────────────────
for t in simos-map/src/main/java/io/mosire/simos/map/spi/SetEdgeHandler.java \
         simos-map/src/main/java/io/mosire/simos/map/ops/EdgeOperations.java \
         simos-map/src/main/java/io/mosire/simos/map/ops/RandomizeOperations.java \
         simos-map/src/main/java/io/mosire/simos/map/generate/RegionRandomizer.java ; do
  cp "$ROOT/$t" "$EV/mutants/orig/$(basename "$t")"
done

# ── T5 m1：mode 给默认值 replace（缺字段不再报错）────────────────────────────
M="$EV/mutants/m1.SetEdgeHandler.java"; cp "$EV/mutants/orig/SetEdgeHandler.java" "$M"
sub_once "$M" \
  'String mode = MapPayloads.requireText(payload, "mode");' \
  'JsonNode modeNode = payload.get("mode");
      String mode = modeNode == null || !modeNode.isTextual() ? "replace" : modeNode.asText();' \
  "T5-m1"

# ── T5 m2：merge 也整份覆盖（既有 tag 丢失）──────────────────────────────────
M="$EV/mutants/m2.EdgeOperations.java"; cp "$EV/mutants/orig/EdgeOperations.java" "$M"
sub_once "$M" \
  'if (REPLACE.equals(operation)) {' \
  'if (REPLACE.equals(operation) || MERGE.equals(operation)) {' \
  "T5-m2"

# ── T6 m1：忽略 seed（固定 0L）──────────────────────────────────────────────
M="$EV/mutants/m3.RandomizeOperations.java"; cp "$EV/mutants/orig/RandomizeOperations.java" "$M"
sub_once "$M" \
  'RegionRandomizer.randomize(base, hexes, TERRAIN_A, TERRAIN_B, RATIO_A, seed);' \
  'RegionRandomizer.randomize(base, hexes, TERRAIN_A, TERRAIN_B, RATIO_A, 0L);' \
  "T6-m1"

# ── T6 m2：Set 迭代序当输入序（去掉 sorted）─────────────────────────────────
M="$EV/mutants/m4.RegionRandomizer.java"; cp "$EV/mutants/orig/RegionRandomizer.java" "$M"
sub_once "$M" \
  'map, hexes.stream().sorted().toList(), terrainA, terrainB, ratioA, new Random(seed));' \
  'map, hexes.stream().toList(), terrainA, terrainB, ratioA, new Random(seed));' \
  "T6-m2"

echo "--- 变异体清单 ---"
for m in "$EV"/mutants/m*.java; do
  echo "$(basename "$m") md5=$(md5sum "$m" | cut -d' ' -f1)"
done
echo "--- 差异（对原件）---"
for m in "$EV"/mutants/m*.java; do
  b="$(basename "$m")"; cls="${b#*.}"
  echo "### $b"
  diff "$EV/mutants/orig/$cls" "$m" || true
done
