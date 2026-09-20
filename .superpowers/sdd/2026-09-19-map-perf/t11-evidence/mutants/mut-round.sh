#!/usr/bin/env bash
# M9 T11 变异轮装置 —— 九道门禁（Java / JS-gate / JS-e2e 三种载体）。
#
# 用法: mut-round.sh <round-id> <j1|j2|s1|s2>
#   j1  Java  ApiViews.closedLabelRing 丢掉闭合重复点        (杀点: 环首尾同点（u 闭合）)
#   j2  Java  ApiViews.closedLabelRing 交换 u/w              (杀点: 顶点恰是 HexVertex.at…的整数标签)
#   s1  JS    blocks.js vertexXY 的 y 尺度错(0.25)           (杀点: decodeBlocks-restores-vertex-world-coordinates via node gate)
#   s2  JS    map.js resize 硬编码 dpr=1                     (杀点: hidpi-canvas-backing-scaled via dpr=2 e2e)
#
# 九道门禁: ①干净世界 md5 ②变异体字节确实不同 ③按白名单推成目标名 ④清陈旧 .class
#          ⑤COMPILATION ERROR=0 且 Tests run>=1 ⑥surefire 报告 mtime 本轮内
#          ⑦红点落在被保护的那条断言上 ⑧cp 逐字节还原(绝不 git checkout) ⑨日志自指且核对先断言非空
set -u
WT=/home/cna/SimulatorMosire/.claude/worktrees/m9t11
EV="$WT/.superpowers/sdd/2026-09-19-map-perf/t11-evidence"
ROUND="${1:?round id}"
KIND="${2:?kind}"
LOG="$EV/logs/mut-$ROUND.log"
RUNLOG="$EV/logs/mut-$ROUND.run.log"
: > "$LOG"

log() { echo "$@" | tee -a "$LOG"; }
die() { log "PHASE FAIL: $*"; log "SELF round=$ROUND kind=$KIND restored_md5=$(md5sum "$TARGET" 2>/dev/null | awk '{print $1}') end=$(date +%s)"; exit 1; }

case "$KIND" in
  j1|j2) TARGET_REL="simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java"; ORIG="$EV/mutants/orig/ApiViews.java"; MUTANT="$EV/mutants/$KIND/ApiViews.java"; MODE=java; EXPECT="${KIND}";;
  s1)    TARGET_REL="simos-app/src/main/resources/webui/blocks.js"; ORIG="$EV/mutants/orig/blocks.js"; MUTANT="$EV/mutants/s1/blocks.js"; MODE=js-gate; EXPECT="decodeBlocks-restores-vertex-world-coordinates";;
  s2)    TARGET_REL="simos-app/src/main/resources/webui/map.js"; ORIG="$EV/mutants/orig/map.js"; MUTANT="$EV/mutants/s2/map.js"; MODE=js-e2e; EXPECT="hidpi-canvas-backing-scaled";;
  *) echo "unknown kind: $KIND" >&2; exit 2;;
esac
TARGET="$WT/$TARGET_REL"
START=$(date +%s)

restore() {
  cp "$ORIG" "$TARGET"
  if [ "$MODE" = js-e2e ]; then cp "$ORIG" "$WT/simos-app/target/classes/webui/map.js"; fi
}

# 门禁① 干净世界：目标文件当前字节 == 原件
orig_md5="$(md5sum "$ORIG" | awk '{print $1}')"
cur_md5="$(md5sum "$TARGET" | awk '{print $1}')"
[ -n "$cur_md5" ] || die "clean-world: 读到空 md5"
[ "$cur_md5" = "$orig_md5" ] || die "clean-world: target=$cur_md5 != orig=$orig_md5"

# 门禁② 变异体字节确实不同
mut_md5="$(md5sum "$MUTANT" | awk '{print $1}')"
[ -n "$mut_md5" ] || die "mutant: 读到空 md5"
[ "$mut_md5" != "$orig_md5" ] || die "mutant==orig 字节（$mut_md5）"
log "SELF round=$ROUND kind=$KIND mode=$MODE orig_md5=$orig_md5 mutant_md5=$mut_md5 start=$START"

# 门禁③④ 按白名单推成目标名 + 清陈旧 .class
cp "$MUTANT" "$TARGET"
pushed_md5="$(md5sum "$TARGET" | awk '{print $1}')"
[ "$pushed_md5" = "$mut_md5" ] || die "push: $pushed_md5 != mutant $mut_md5"
if [ "$MODE" = java ]; then rm -f "$WT"/simos-app/target/classes/io/mosire/simos/app/gui/ApiViews*.class; fi
if [ "$MODE" = js-e2e ]; then cp "$MUTANT" "$WT/simos-app/target/classes/webui/map.js"; fi
log "SELF pushed_md5=$pushed_md5 target=$TARGET_REL"

# 跑目标
set +e
case "$MODE" in
  java)
    ( cd "$WT" && ./mvnw -pl simos-app -am test -Dtest=MapOverviewBlocksTest -Dsurefire.failIfNoSpecifiedTests=false ) >"$RUNLOG" 2>&1
    rc=$? ;;
  js-gate)
    ( cd "$WT" && node simos-app/src/test/js/run-gate.cjs ) >"$RUNLOG" 2>&1
    rc=$? ;;
  js-e2e)
    ( cd "$EV" && NODE_PATH=/home/cna/.npm/_npx/e41f203b7505f1fb/node_modules node harness/t11-e2e.cjs http://127.0.0.1:5832 "$EV/logs/mut-$ROUND-e2e.json" 2 ) >"$RUNLOG" 2>&1
    rc=$? ;;
esac
set -e
cat "$RUNLOG" >> "$LOG"
log "SELF runner_rc=$rc"

# 门禁⑤ 无编译错误
comp=$(grep -c "COMPILATION ERROR" "$LOG" || true)
[ "$comp" = "0" ] || { restore; die "COMPILATION ERROR=$comp（本轮作废）"; }

# 门禁⑥ 报告 mtime 在本轮内 + Tests run>=1（Java 用 surefire；JS 无 surefire 报告，改判产物非空）
if [ "$MODE" = java ]; then
  SR="$WT/simos-app/target/surefire-reports/io.mosire.simos.app.gui.MapOverviewBlocksTest.txt"
  [ -f "$SR" ] || { restore; die "无 surefire 报告"; }
  mtime=$(stat -c %Y "$SR")
  [ "$mtime" -ge "$START" ] || { restore; die "surefire 报告 mtime=$mtime 早于本轮 start=$START（陈旧）"; }
  tests=$(grep -oE "Tests run: [0-9]+" "$SR" | head -1 | grep -oE "[0-9]+")
  [ -n "$tests" ] && [ "$tests" -ge 1 ] || { restore; die "Tests run=$tests 不满足 >=1"; }
  failures=$(grep -oE "Failures: [0-9]+" "$SR" | head -1 | grep -oE "[0-9]+")
  log "SELF surefire mtime=$mtime(start=$START) Tests=$tests Failures=$failures"
  [ "$failures" -ge 1 ] || { restore; die "预期红但 Failures=$failures"; }
else
  grep -qE "(# tests [0-9]+|summary: )" "$LOG" || { restore; die "无测试汇总（可能压根没跑到）"; }
fi

# 门禁⑦ 红点落在被保护的那条断言上
case "$KIND" in
  j1) grep -q "环首尾同点（u 闭合）" "$LOG" || { restore; die "红点不是 j1 的闭合断言"; };;
  j2) grep -q "顶点恰是 HexVertex.at" "$LOG" || { restore; die "红点不是 j2 的标签断言"; };;
  s1) grep -q "$EXPECT" "$LOG" || { restore; die "红点不是 s1 的坐标断言"; };;
  s2) python3 - "$EV/logs/mut-$ROUND-e2e.json" "$EXPECT" <<'PY' || { restore; die "红点不是 s2 的 dpr 断言"; }
import json,sys
d=json.load(open(sys.argv[1]))
bad=[a['id'] for a in d['assertions'] if not a['pass']]
assert sys.argv[2] in bad, "failing="+repr(bad)
print("failing assertions:", bad)
PY
  ;;
esac

# 门禁⑧ 逐字节还原（cp，绝不 git checkout）
restore
restored_md5="$(md5sum "$TARGET" | awk '{print $1}')"
[ "$restored_md5" = "$orig_md5" ] || die "还原失败: $restored_md5 != $orig_md5"

# 门禁⑨ 自指留痕（本轮字节 md5 写进日志）
log "SELF RESULT round=$ROUND kind=$KIND KILLED orig_md5=$orig_md5 mutant_md5=$mut_md5 restored_md5=$restored_md5 runner_rc=$rc end=$(date +%s)"
echo "ROUND $ROUND ($KIND): KILLED (rc=$rc)" | tee -a "$LOG"
