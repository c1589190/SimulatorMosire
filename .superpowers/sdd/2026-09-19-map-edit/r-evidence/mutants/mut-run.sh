#!/usr/bin/env bash
# M8-R 变异装置（≥4 轮）：把变异体写进 webui 源 → 自证字节（md5 非空且 != 原件）→ node --check（语法必须过）
#   → 同步 target/classes/webui（服务端从那里取资源）→ 跑 e2e（期望红）→ 从 mutants/orig **逐字节还原**
#   → 断言 src 与 classes 的 md5 都复原、且 webui 聚合 md5 复原（防 T7 那次"还原打回旧版本"的假红）。
# 用法: mut-run.sh <m1..m5> <port> <store-src>
# 退出码 0 = 该变异体被对应护栏杀掉（预期红）；1 = 未被杀 / 装置自身出错。
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m8r
EV="$ROOT/.superpowers/sdd/2026-09-19-map-edit/r-evidence"
WEB="$ROOT/simos-app/src/main/resources/webui"
MUT="$EV/mutants"
STORE_SRC="${3:-/tmp/m8t1-e2e-store}"
mkdir -p "$MUT/orig" "$MUT/logs"

FILES="map.js app.js modes.js blocks.js panels.js unitTree.js timeline.js api.js index.html styles.css"
AGGREGATE() {
  # 聚合 md5：先逐文件 md5，再对"文件名+md5"清单取一次 md5；先断言清单非空（否则"空==空"恒真）。
  local listing
  listing=$(cd "$1" && for f in $FILES; do printf '%s  %s\n' "$(md5sum "$f" | awk '{print $1}')" "$f"; done)
  if [ -z "$listing" ]; then echo "AGGREGATE-EMPTY"; return 1; fi
  printf '%s\n' "$listing" | md5sum | awk '{print $1}'
}

# ★ 门禁 1：orig 快照必须存在；且**变异前 src 必须逐字节等于 orig**——否则 orig 陈旧，还原会把工作树打回旧版本。
for f in $FILES; do
  if [ ! -f "$MUT/orig/$f" ]; then cp "$WEB/$f" "$MUT/orig/$f"; fi
  if ! cmp -s "$WEB/$f" "$MUT/orig/$f"; then
    echo "DEVICE FAIL: $f 的 src 与 orig 不一致（orig 陈旧）；先刷新 mutants/orig 再跑"
    exit 1
  fi
done
ORIG_AGG=$(AGGREGATE "$MUT/orig")
ORIG_MD5=$(md5sum "$MUT/orig/map.js" | awk '{print $1}')
if [ "$ORIG_AGG" = "AGGREGATE-EMPTY" ] || [ -z "$ORIG_AGG" ]; then echo "DEVICE FAIL: 聚合 md5 为空"; exit 1; fi
echo "orig_aggregate_md5=$ORIG_AGG orig_map.js_md5=$ORIG_MD5"

ID="$1"; PORT="$2"
FILE="$WEB/map.js"

# 先把全部原件放回（上一轮若异常退出留下的变异必须清掉）。
for f in $FILES; do cp "$MUT/orig/$f" "$WEB/$f"; done

case "$ID" in
  m1)
    # 护栏：重叠允许 ⇒ 变异：套索创建前"与已有区域相交就拒绝"。
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''    host.regionEditBusy = true;
    setRegionEditStatus("套索 " + hexes.length + " 格 ⇒ 提交 map.CreateRegion " + id + " …", "muted");
    var result = await app.writeCommand("map.CreateRegion", { regionId: id, name: name, hexes: hexes });'''
new='''    host.regionEditBusy = true;
    var __probe = await api.mapHex(hexes[0].q, hexes[0].r, app.target());
    if (((__probe && __probe.regions) || []).length > 0) {
      host.regionEditBusy = false;
      setRegionEditStatus("禁止重叠：与已有区域相交", "err");
      return null;
    }
    setRegionEditStatus("套索 " + hexes.length + " 格 ⇒ 提交 map.CreateRegion " + id + " …", "muted");
    var result = await app.writeCommand("map.CreateRegion", { regionId: id, name: name, hexes: hexes });'''
assert s.count(old)==1, "m1 anchor not unique"
open(p,'w').write(s.replace(old,new))
PY
    ;;
  m2)
    # 护栏：右键按模式分派 ⇒ 变异：区域编辑的右键仍走 unit.PlanRoute（去掉早退 + 放宽 unit 守卫）。
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old1='''    var contextMode = app.getState().mode;
    if (contextMode === "region-edit" || contextMode === "map-edit") {
      return true;
    }
'''
assert s.count(old1)==1, "m2 anchor1 not unique"
s=s.replace(old1,'')
old2='    if (app.getState().mode !== "unit") {'
assert s.count(old2)==1, "m2 anchor2 not unique"
s=s.replace(old2,'    if (app.getState().mode !== "unit" && app.getState().mode !== "region-edit") {')
open(p,'w').write(s)
PY
    ;;
  m3)
    # 护栏：小点只画选中区域 ⇒ 变异：clearFocusHexes 空实现（取消选中后焦点集合留存，仍画点）。
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''    function clearFocusHexes() {
      setFocusHexes([], null);
    }'''
new='''    function clearFocusHexes() {
      // mutant m3: 不清焦点 ⇒ 未选中区域仍画点
    }'''
assert s.count(old)==1, "m3 anchor not unique"
open(p,'w').write(s.replace(old,new))
PY
    ;;
  m4)
    # 护栏：合并=并集 ⇒ 变异：只取临时选区（丢弃已选区域）。
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''    var base = await fetchRegionCached(id);
    var hexes = unionHexes(base.hexes, regionDraftList());
    if (!hexes.length) {
      setRegionEditStatus("并集为空，未发命令。", "warn");'''
new='''    var base = await fetchRegionCached(id);
    var hexes = regionDraftList();
    if (!hexes.length) {
      setRegionEditStatus("并集为空，未发命令。", "warn");'''
assert s.count(old)==1, "m4 anchor not unique"
open(p,'w').write(s.replace(old,new))
PY
    ;;
  m5)
    # 护栏：剔除=差集 ⇒ 变异：误用并集。
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''    var base = await fetchRegionCached(id);
    var hexes = differenceHexes(base.hexes, regionDraftList());
    if (!hexes.length) {
      setRegionEditStatus("差集为空（会清空 " + id + "，服务端拒绝空 hexes），未发命令。", "warn");'''
new='''    var base = await fetchRegionCached(id);
    var hexes = unionHexes(base.hexes, regionDraftList());
    if (!hexes.length) {
      setRegionEditStatus("差集为空（会清空 " + id + "，服务端拒绝空 hexes），未发命令。", "warn");'''
assert s.count(old)==1, "m5 anchor not unique"
open(p,'w').write(s.replace(old,new))
PY
    ;;
  m6)
    # 护栏：地形编辑左键=平移（零写）⇒ 变异：左键仍刷地形（开始 painting）。
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''      if (event.button !== 0) {
        return;
      }
      // ★ §七：左键在所有编辑模式统一为"平移地图"'''
new='''      if (event.button !== 0) {
        return;
      }
      if (mode === "map-edit") {
        beginPaint(event);
        return;
      }
      // ★ §七：左键在所有编辑模式统一为"平移地图"'''
assert s.count(old)==1, "m6 anchor not unique"
open(p,'w').write(s.replace(old,new))
PY
    ;;
  m7)
    # 护栏：Shift+右键=逐格画 ⇒ 变异：Shift 分支未实现（落到套索）。
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''          if (event.shiftKey) {
            beginPaint(event);
            return;
          }
'''
assert s.count(old)==1, "m7 anchor not unique"
open(p,'w').write(s.replace(old,'          /* mutant m7: Shift+右键未实现，落回套索 */\n'))
PY
    ;;
  m8)
    # 护栏：不再有逐格/块边界描边 ⇒ 变异：恢复旧边框层（Path2D 块边界 + 黑线）。
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''      paintHighlights(ctx);
      paintRegionOutlines(ctx);'''
new='''      paintHighlights(ctx);
      var __mutBorder = new Path2D();
      blocks.forEach(function (block) {
        (block.boundaries || []).forEach(function (ring) {
          for (var __i = 0; __i < ring.length; __i++) {
            var __x = ring[__i].x * cellSize;
            var __y = ring[__i].y * cellSize;
            if (__i === 0) { __mutBorder.moveTo(__x, __y); } else { __mutBorder.lineTo(__x, __y); }
          }
          __mutBorder.closePath();
        });
      });
      ctx.strokeStyle = "#0d1015";
      ctx.lineWidth = 1 / view.scale;
      ctx.stroke(__mutBorder);
      paintRegionOutlines(ctx);'''
assert s.count(old)==1, "m8 anchor not unique"
open(p,'w').write(s.replace(old,new))
PY
    ;;
  m9)
    # 护栏：区域边界 RDP 简化 ⇒ 变异：关掉简化（eps=0 ⇒ 回到逐 hex 台阶）。
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='var REGION_OUTLINE_RDP_EPS = 0.5;'
assert s.count(old)==1, "m9 anchor not unique"
open(p,'w').write(s.replace(old,'var REGION_OUTLINE_RDP_EPS = 0;'))
PY
    ;;
  *) echo "unknown mutation $ID"; exit 1 ;;
esac

# ★ 门禁 2：变异体必须与原件**字节不同**（否则 javac/node 编的是原件，"红"无意义）。
MUT_MD5=$(md5sum "$FILE" | awk '{print $1}')
if [ -z "$MUT_MD5" ] || [ "$MUT_MD5" = "$ORIG_MD5" ]; then echo "DEVICE FAIL: mutant bytes identical/empty"; exit 1; fi
echo "mutation=$ID orig_md5=$ORIG_MD5 mutant_md5=$MUT_MD5 file=map.js"

# ★ 门禁 3：语法必须过（否则"红"是编译/解析错误，不是行为差异）。
node --check "$FILE" > "$MUT/logs/$ID-syntax.log" 2>&1
SYNTAX_RC=$?
echo "syntax_rc=$SYNTAX_RC (must be 0)"
if [ "$SYNTAX_RC" != "0" ]; then echo "DEVICE FAIL: mutant does not parse"; exit 1; fi

# ★ 门禁 4：服务端从 target/classes/webui 取资源 ⇒ 变异体必须同步过去。
CLASSES="$ROOT/simos-app/target/classes/webui/map.js"
if [ ! -f "$CLASSES" ]; then echo "DEVICE FAIL: $CLASSES 不存在（先 ./mvnw -pl simos-app -am compile）"; exit 1; fi
cp "$FILE" "$CLASSES"
CLASSES_MD5=$(md5sum "$CLASSES" | awk '{print $1}')
echo "classes_md5=$CLASSES_MD5 (must == mutant_md5)"
if [ "$CLASSES_MD5" != "$MUT_MD5" ]; then echo "DEVICE FAIL: target/classes 未同步到变异体"; exit 1; fi

# 装置自指（形态 6）：把本轮跑的是哪份字节写进日志本身。
{
  echo "=== mutation $ID ==="
  echo "orig_md5=$ORIG_MD5"
  echo "mutant_md5=$MUT_MD5"
  echo "classes_md5=$CLASSES_MD5"
  echo "orig_aggregate_md5=$ORIG_AGG"
} >> "$MUT/runs.log"

# ★ 门禁 5：跑 e2e（期望红）。run-e2e 自己会再同步 classes 并校验 src==classes。
mkdir -p "$MUT/logs/$ID-e2e"
bash "$EV/e2e/run-e2e.sh" "$PORT" "$STORE_SRC" "$MUT/logs/$ID-e2e" "$MUT/logs/$ID-server.log" > "$MUT/logs/$ID-e2e.log" 2>&1
E2E_RC=$?
echo "e2e_rc=$E2E_RC (expect 1)"
grep -m1 "E2E RESULT" "$MUT/logs/$ID-e2e.log" || true

# ★ 门禁 6：逐字节还原（src + classes）。
for f in $FILES; do cp "$MUT/orig/$f" "$WEB/$f"; done
cp "$MUT/orig/map.js" "$CLASSES"
RESTORED_MD5=$(md5sum "$FILE" | awk '{print $1}')
RESTORED_CLASSES_MD5=$(md5sum "$CLASSES" | awk '{print $1}')
RESTORED_AGG=$(AGGREGATE "$MUT/orig")
echo "restored_md5=$RESTORED_MD5 restored_classes_md5=$RESTORED_CLASSES_MD5 orig_md5=$ORIG_MD5"
echo "restored_aggregate_md5=$RESTORED_AGG orig_aggregate_md5=$ORIG_AGG"
if [ "$RESTORED_MD5" != "$ORIG_MD5" ] || [ "$RESTORED_CLASSES_MD5" != "$ORIG_MD5" ]; then echo "DEVICE FAIL: restore mismatch"; exit 1; fi
# ★ 门禁 7：聚合 md5 必须非空且与原件一致（防"打回旧版本"的假红）。
if [ -z "$RESTORED_AGG" ] || [ "$RESTORED_AGG" = "AGGREGATE-EMPTY" ] || [ "$RESTORED_AGG" != "$ORIG_AGG" ]; then
  echo "DEVICE FAIL: aggregate md5 mismatch/empty"; exit 1
fi

# ★ 门禁 8/9：期望红。
if [ "$E2E_RC" = "1" ]; then echo "MUTATION $ID: KILLED (expected red observed)"; exit 0; fi
echo "MUTATION $ID: SURVIVED / unexpected"; exit 1
