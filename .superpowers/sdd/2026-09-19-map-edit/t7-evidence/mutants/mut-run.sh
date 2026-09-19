#!/usr/bin/env bash
# M8 T7/T8 变异装置：把变异体写进 webui 源 → node --check（语法必须过，否则"红"是假红）→ 跑对应验证
#   （期望红）→ 从 mutants/orig 逐字节还原 → 断言 md5 复原。
# 用法: mut-run.sh <m1..m5> <port>
# 退出码 0 = 该变异体被对应护栏杀掉（预期红），1 = 未被杀 / 装置自身出错。
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m8t7
EV="$ROOT/.superpowers/sdd/2026-09-19-map-edit/t7-evidence"
WEB="$ROOT/simos-app/src/main/resources/webui"
MUT="$EV/mutants"
mkdir -p "$MUT/orig" "$MUT/logs"
for f in modes.js app.js map.js; do
  if [ ! -f "$MUT/orig/$f" ]; then cp "$WEB/$f" "$MUT/orig/$f"; fi
done

# ★ 装置自证（形态 5）：变异前 src 必须逐字节等于 orig——否则 orig 是陈旧快照，还原会把工作树写回旧版本
#   （本装置第一版用 `cp -n` 建 orig，改过 src 后再跑，m1 还原把 src 打回了旧 modes.js）。
for f in modes.js app.js map.js; do
  if ! cmp -s "$WEB/$f" "$MUT/orig/$f"; then
    echo "DEVICE FAIL: $f 的 src 与 orig 不一致（orig 陈旧）；先刷新 mutants/orig 再跑"
    exit 1
  fi
done

ID="$1"; PORT="$2"
TARGET=""
case "$ID" in
  m1) TARGET="modes.js" ;;
  m2) TARGET="app.js" ;;
  m3|m4|m5) TARGET="map.js" ;;
  *) echo "unknown mutation $ID"; exit 1 ;;
esac
FILE="$WEB/$TARGET"
ORIG_MD5=$(md5sum "$MUT/orig/$TARGET" | awk '{print $1}')
cp "$MUT/orig/$TARGET" "$FILE"

case "$ID" in
  m1)
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='{ id: "region", label: "区域查看", writes: [] }'
new='{ id: "region", label: "区域查看", writes: ["map.SetTerrain"] }'
assert s.count(old)==1, "m1 anchor not unique"
open(p,'w').write(s.replace(old,new))
PY
    ;;
  m2)
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='    state.selection = null;\n    state.highlightRegions = [];\n'
assert s.count(old)==1, "m2 anchor not unique"
open(p,'w').write(s.replace(old,''))
PY
    ;;
  m3)
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''    var result = await app.writeCommand("map.SetTerrain", {
      hexes: hexes,
      terrain: host.brushTerrain,
    });'''
new='''    var result = null;
    for (var mi = 0; mi < hexes.length; mi++) {
      result = await app.writeCommand("map.SetTerrain", {
        hexes: [hexes[mi]],
        terrain: host.brushTerrain,
      });
    }'''
assert s.count(old)==1, "m3 anchor not unique"
open(p,'w').write(s.replace(old,new))
PY
    ;;
  m4)
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='    var list = types || [];'
new='    var list = types || [];\n    list = list.concat([{ key: "forest", name: "森林（硬编码）", color: "#2E7D32" }]);'
assert s.count(old)==1, "m4 anchor not unique"
open(p,'w').write(s.replace(old,new))
PY
    ;;
  m5)
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='      updateLegend();\n      terrainDirty = true;\n      scheduleRender();'
new='      updateLegend();\n      scheduleRender();'
assert s.count(old)==1, "m5 anchor not unique"
open(p,'w').write(s.replace(old,new))
PY
    ;;
esac

MUT_MD5=$(md5sum "$FILE" | awk '{print $1}')
echo "mutation=$ID orig_md5=$ORIG_MD5 mutant_md5=$MUT_MD5 file=$TARGET"
if [ "$MUT_MD5" = "$ORIG_MD5" ]; then echo "DEVICE FAIL: mutant bytes identical"; cp "$MUT/orig/$TARGET" "$FILE"; exit 1; fi
node --check "$FILE" > "$MUT/logs/$ID-syntax.log" 2>&1
SYNTAX_RC=$?
echo "syntax_rc=$SYNTAX_RC (must be 0)"
if [ "$SYNTAX_RC" != "0" ]; then echo "DEVICE FAIL: mutant does not parse"; cp "$MUT/orig/$TARGET" "$FILE"; exit 1; fi

# ★ 服务器从 target/classes/webui 取资源 ⇒ 变异体必须同时推进去，否则 e2e 跑的还是旧字节（形态 1 的"陈旧副本"）。
CLASSES="$ROOT/simos-app/target/classes/webui/$TARGET"
if [ ! -f "$CLASSES" ]; then echo "DEVICE FAIL: $CLASSES 不存在（先 ./mvnw -pl simos-app -am compile）"; cp "$MUT/orig/$TARGET" "$FILE"; exit 1; fi
cp "$FILE" "$CLASSES"
CLASSES_MD5=$(md5sum "$CLASSES" | awk '{print $1}')
echo "classes_md5=$CLASSES_MD5 (must == mutant_md5)"
if [ "$CLASSES_MD5" != "$MUT_MD5" ]; then echo "DEVICE FAIL: target/classes 未同步到变异体"; cp "$MUT/orig/$TARGET" "$FILE"; exit 1; fi

SELF_RC="skip"
if [ "$ID" = "m1" ]; then
  node "$EV/modes-selfcheck.cjs" > "$MUT/logs/$ID-selfcheck.log" 2>&1
  SELF_RC=$?
  echo "selfcheck_rc=$SELF_RC (expect 1)"
fi

bash "$EV/e2e/run-e2e.sh" "$PORT" /tmp/m6-import-verify/test_integration "$MUT/logs/$ID-e2e" "$MUT/logs/$ID-server.log" > "$MUT/logs/$ID-e2e.log" 2>&1
E2E_RC=$?
echo "e2e_rc=$E2E_RC (expect 1)"
grep -m1 "E2E RESULT" "$MUT/logs/$ID-e2e.log" || true

cp "$MUT/orig/$TARGET" "$FILE"
cp "$MUT/orig/$TARGET" "$CLASSES"
RESTORED_MD5=$(md5sum "$FILE" | awk '{print $1}')
RESTORED_CLASSES_MD5=$(md5sum "$CLASSES" | awk '{print $1}')
echo "restored_md5=$RESTORED_MD5 restored_classes_md5=$RESTORED_CLASSES_MD5 orig_md5=$ORIG_MD5"
if [ "$RESTORED_MD5" != "$ORIG_MD5" ] || [ "$RESTORED_CLASSES_MD5" != "$ORIG_MD5" ]; then echo "DEVICE FAIL: restore mismatch"; exit 1; fi

# 期望：m1 selfcheck 红 且 e2e 红；m2~m5 e2e 红
OK=1
[ "$E2E_RC" = "1" ] || OK=0
if [ "$ID" = "m1" ] && [ "$SELF_RC" != "1" ]; then OK=0; fi
if [ "$OK" = "1" ]; then echo "MUTATION $ID: KILLED (expected red observed)"; exit 0; else echo "MUTATION $ID: SURVIVED / unexpected"; exit 1; fi
