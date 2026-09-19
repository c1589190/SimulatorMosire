#!/usr/bin/env bash
# M9 T6 变异装置（五轮；九道门禁）。
# 门禁：① 干净世界（每轮先 cp 原件还原 + 比 md5）② 变异体字节不同（md5）③ 白名单推成目标类名
#       ④ 清陈旧 .class / surefire ⑤ COMPILATION ERROR=0 且 Tests run>=1 ⑥ surefire mtime 落轮内
#       ⑦ 红点落被保护断言 ⑧ cp 逐字节还原（绝不 git checkout）⑨ 日志自指 + 先断言聚合 md5 非空
set -uo pipefail

WT="/home/cna/SimulatorMosire/.claude/worktrees/m9t6"
EV="$WT/.superpowers/sdd/2026-09-19-map-perf/t6-evidence"
MUT="$EV/mutants"
ORIG="$MUT/orig"
SRC="$WT/simos-map/src/main/java/io/mosire/simos/map"
LOGS="$EV/logs"
SUMMARY="$EV/mutation-summary.log"

names=(m1 m2 m3 m4 m5)
targets=(
  "block/TerrainBlocks.java"
  "block/TerrainBlocks.java"
  "block/TerrainBlocks.java"
  "change/MapChangeSet.java"
  "GameMap.java"
)
classes=(TerrainBlocks.java TerrainBlocks.java TerrainBlocks.java MapChangeSet.java GameMap.java)
specs=(
  "TerrainBlocksTest#splitProducesAPartition"
  "TerrainBlocksTest#splitProducesAPartition"
  "TerrainBlocksTest#rebuildIsByteIdentical"
  "MapChangeSetTest#betweenDetectsChangedTerrainBlocks"
  "GameMapTest#terrainAtIsDerivedAndMatchesBlocks"
)
expects=(
  "并集 == 全部 hex"
  "块 hexes 总和 == 格总数"
  "两次重建的 BlockId"
  "terrainBlocks 必须进 diff"
  'but was: "plains"'
)

: > "$SUMMARY"
echo "# mutation summary (each md5 written into its own round log too)" >> "$SUMMARY"

for i in "${!names[@]}"; do
  name="${names[$i]}"
  target="${targets[$i]}"
  class="${classes[$i]}"
  spec="${specs[$i]}"
  expect="${expects[$i]}"
  log="$LOGS/${name}.log"
  round_start="$(date +%s)"

  # ⓪ 干净世界：先把三个目标全部还原成原件，再校验（门禁 ①/⑧）
  cp "$ORIG/TerrainBlocks.java" "$SRC/block/TerrainBlocks.java"
  cp "$ORIG/MapChangeSet.java" "$SRC/change/MapChangeSet.java"
  cp "$ORIG/GameMap.java" "$SRC/GameMap.java"

  mutant="$MUT/${name}.${class}"
  target_path="$SRC/$target"
  mutant_md5="$(md5sum "$mutant" | awk '{print $1}')"
  orig_md5="$(md5sum "$target_path" | awk '{print $1}')"
  # 门禁 ⑨ 先断言聚合 md5 非空，再比较
  if [ -z "$mutant_md5" ] || [ -z "$orig_md5" ]; then
    echo "ROUND $name ABORT: md5 读取为空 (mutant='$mutant_md5' orig='$orig_md5')" | tee -a "$SUMMARY"
    continue
  fi

  {
    echo "=== ROUND $name ==="
    echo "spec=$spec"
    echo "orig_md5=$orig_md5"
    echo "mutant_md5=$mutant_md5"
  } > "$log"

  # 门禁 ② 变异体与原件字节不同
  if [ "$mutant_md5" = "$orig_md5" ]; then
    echo "ROUND $name ABORT: 变异体与原件逐字节相同" | tee -a "$log" | tee -a "$SUMMARY"
    continue
  fi

  # 门禁 ③④ 按白名单推成规范类名；清陈旧 .class / surefire
  cp "$mutant" "$target_path"
  pushed_md5="$(md5sum "$target_path" | awk '{print $1}')"
  echo "pushed_md5=$pushed_md5" >> "$log"
  if [ "$pushed_md5" != "$mutant_md5" ]; then
    echo "ROUND $name ABORT: 推送后 md5 与变异体不符" | tee -a "$log" | tee -a "$SUMMARY"
    cp "$ORIG/TerrainBlocks.java" "$SRC/block/TerrainBlocks.java"
    cp "$ORIG/MapChangeSet.java" "$SRC/change/MapChangeSet.java"
    cp "$ORIG/GameMap.java" "$SRC/GameMap.java"
    continue
  fi
  rm -rf "$WT/simos-map/target/classes" "$WT/simos-map/target/test-classes" "$WT/simos-map/target/surefire-reports"

  # 跑本轮（隔离格式/静态分析门禁，只留 javac + surefire）
  ( cd "$WT" && ./mvnw -pl simos-map -am \
      -Dtest="$spec" -Dsurefire.failIfNoSpecifiedTests=false \
      -Dspotless.check.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true \
      test ) >> "$log" 2>&1
  rc=$?
  echo "maven_rc=$rc" >> "$log"

  # 门禁 ⑤ 编译错误 0 且 Tests run>=1
  comp_err="$(grep -c 'COMPILATION ERROR' "$log")"
  tests_run="$(grep -oE 'Tests run: [0-9]+' "$log" | awk '{s+=$3} END{print s+0}')"
  echo "compilation_errors=$comp_err tests_run=$tests_run" >> "$log"
  if [ "$comp_err" != "0" ] || [ "$tests_run" -lt 1 ]; then
    echo "ROUND $name INVALID: comp_err=$comp_err tests_run=$tests_run" | tee -a "$log" | tee -a "$SUMMARY"
    cp "$ORIG/TerrainBlocks.java" "$SRC/block/TerrainBlocks.java"
    cp "$ORIG/MapChangeSet.java" "$SRC/change/MapChangeSet.java"
    cp "$ORIG/GameMap.java" "$SRC/GameMap.java"
    continue
  fi

  # 门禁 ⑥ surefire 报告 mtime 落本轮内
  report="$(ls "$WT"/simos-map/target/surefire-reports/*."${spec%%#*}.txt" 2>/dev/null | head -1)"
  report_ok="no"
  if [ -n "${report:-}" ] && [ -f "$report" ]; then
    rmt="$(date -r "$report" +%s)"
    [ "$rmt" -ge "$round_start" ] && report_ok="yes"
    echo "report_mtime=$rmt round_start=$round_start report_ok=$report_ok" >> "$log"
  else
    echo "report_missing path=$report" >> "$log"
  fi

  # 门禁 ⑦ 红点落被保护断言
  red="$(grep -cE 'Tests run:.*(Failures: [1-9]|Errors: [1-9])' "$log")"
  hit="$(grep -Fc "$expect" "$log")"
  echo "red_lines=$red expect_hits=$hit expect=[$expect]" >> "$log"
  verdict="SURVIVED"
  if [ "$red" -ge 1 ] && [ "$hit" -ge 1 ]; then
    verdict="KILLED"
  fi

  # 门禁 ⑧ cp 逐字节还原（绝不 git checkout）
  cp "$ORIG/TerrainBlocks.java" "$SRC/block/TerrainBlocks.java"
  cp "$ORIG/MapChangeSet.java" "$SRC/change/MapChangeSet.java"
  cp "$ORIG/GameMap.java" "$SRC/GameMap.java"
  restored_md5="$(md5sum "$SRC/$target" | awk '{print $1}')"
  echo "restored_md5=$restored_md5" >> "$log"
  if [ "$restored_md5" != "$orig_md5" ]; then
    verdict="RESTORE-MISMATCH"
  fi
  echo "verdict=$verdict" >> "$log"
  echo "$name verdict=$verdict red=$red expect_hits=$hit comp_err=$comp_err tests_run=$tests_run report_ok=$report_ok mutant_md5=$mutant_md5 restored_md5=$restored_md5" >> "$SUMMARY"
done

# 收尾：确认工作树三个目标都在原件状态
for f in block/TerrainBlocks.java change/MapChangeSet.java GameMap.java; do
  echo "final $(md5sum "$SRC/$f" | awk '{print $1}') $f" >> "$SUMMARY"
done
echo "DONE" >> "$SUMMARY"
cat "$SUMMARY"
