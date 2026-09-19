#!/usr/bin/env bash
# M8 T10 变异装置：把变异体写进 webui 源 → node --check（语法必须过，否则"红"是假红）
#   → 跑 e2e（期望**指定的**断言红）→ 从 mutants/orig 逐字节还原（src + target/classes）→ 断言 md5 复原。
# 用法: mut-run.sh <m1|m2|m3> <port>
# 退出码 0 = 该变异体被对应护栏杀掉（预期红且红在对的断言上），1 = 未杀 / 红错地方 / 装置自身出错。
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m8t10
EV="$ROOT/.superpowers/sdd/2026-09-19-map-edit/t10-evidence"
WEB="$ROOT/simos-app/src/main/resources/webui"
CLASSES="$ROOT/simos-app/target/classes/webui"
MUT="$EV/mutants"
mkdir -p "$MUT/orig" "$MUT/logs"
for f in modes.js app.js map.js; do
  if [ ! -f "$MUT/orig/$f" ]; then cp "$WEB/$f" "$MUT/orig/$f"; fi
done

# ★ 装置自证（形态 1/5）：变异前 src 必须逐字节等于 orig——否则 orig 是陈旧快照，还原会把工作树写回旧版本。
for f in modes.js app.js map.js; do
  if ! cmp -s "$WEB/$f" "$MUT/orig/$f"; then
    echo "DEVICE FAIL: $f 的 src 与 orig 不一致（orig 陈旧）；先刷新 mutants/orig 再跑"
    exit 1
  fi
done

ID="$1"; PORT="$2"
TARGET="map.js"
EXPECT=""
case "$ID" in
  m1) EXPECT="b1-overlap-one-command-head-plus-one" ;;
  m2) EXPECT="e1-delete-unconfirmed-zero-write" ;;
  m3) EXPECT="c2-fade-colors-distinct" ;;
  *) echo "unknown mutation $ID"; exit 1 ;;
esac
FILE="$WEB/$TARGET"
ORIG_MD5=$(md5sum "$MUT/orig/$TARGET" | awk '{print $1}')
cp "$MUT/orig/$TARGET" "$FILE"

case "$ID" in
  m1)
    # ★ 方向性：给前端加"与已有区域相交就拒绝"⇒ 重叠正例必须红。
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''    host.regionEditBusy = true;
    setRegionEditStatus("提交 map.CreateRegion " + id + "（" + hexes.length + " 格）…", "muted");
    var result = await app.writeCommand("map.CreateRegion", { regionId: id, name: name, hexes: hexes });'''
new='''    var _clash = false;
    Object.keys(host.regionCache).forEach(function (k) {
      var _r = host.regionCache[k];
      (_r.hexes || []).forEach(function (h) {
        if (hexes.some(function (x) { return x.q === h.q && x.r === h.r; })) { _clash = true; }
      });
    });
    if (_clash) {
      setRegionEditStatus("禁止重叠：选区与已有区域相交（前端拦截）", "err");
      return { ok: false, kind: "mode-denied", message: "禁止重叠" };
    }
    host.regionEditBusy = true;
    setRegionEditStatus("提交 map.CreateRegion " + id + "（" + hexes.length + " 格）…", "muted");
    var result = await app.writeCommand("map.CreateRegion", { regionId: id, name: name, hexes: hexes });'''
assert s.count(old)==1, "m1 anchor not unique"
open(p,'w').write(s.replace(old,new))
PY
    ;;
  m2)
    # 去掉二次确认：点「删除目标区域」直接发 DeleteRegion。
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='    bind("region-delete", armRegionDelete);'
new='    bind("region-delete", function () { host.regionDeleteArmed = true; renderRegionEditor(); return submitDeleteRegion(); });'
assert s.count(old)==1, "m2 anchor not unique"
open(p,'w').write(s.replace(old,new))
PY
    ;;
  m3)
    # 不设 focus 差异：所有区域同色（淡色逻辑消失）。
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='      var color = isFocus ? base : fadeRegionColor(base);'
new='      var color = base;'
assert s.count(old)==1, "m3 anchor not unique"
open(p,'w').write(s.replace(old,new))
PY
    ;;
esac

MUT_MD5=$(md5sum "$FILE" | awk '{print $1}')
echo "mutation=$ID orig_md5=$ORIG_MD5 mutant_md5=$MUT_MD5 file=$TARGET expect_red=$EXPECT"
if [ "$MUT_MD5" = "$ORIG_MD5" ]; then echo "DEVICE FAIL: mutant bytes identical"; cp "$MUT/orig/$TARGET" "$FILE"; exit 1; fi
node --check "$FILE" > "$MUT/logs/$ID-syntax.log" 2>&1
SYNTAX_RC=$?
echo "syntax_rc=$SYNTAX_RC (must be 0)"
if [ "$SYNTAX_RC" != "0" ]; then echo "DEVICE FAIL: mutant does not parse"; cp "$MUT/orig/$TARGET" "$FILE"; exit 1; fi

# run-e2e.sh 起服务前会把 src→target/classes/webui 逐字节同步并自证 ⇒ 这里只需保证 src 是变异体。
if [ ! -d "$CLASSES" ]; then echo "DEVICE FAIL: $CLASSES 不存在（先 ./mvnw -pl simos-app -am compile）"; cp "$MUT/orig/$TARGET" "$FILE"; exit 1; fi

bash "$EV/e2e/run-e2e.sh" "$PORT" /tmp/m6-import-verify/test_integration "$MUT/logs/$ID-e2e" "$MUT/logs/$ID-server.log" > "$MUT/logs/$ID-e2e.log" 2>&1
E2E_RC=$?
echo "e2e_rc=$E2E_RC (expect 1)"
RESULT_LINE=$(grep -m1 "E2E RESULT" "$MUT/logs/$ID-e2e.log" || true)
echo "result_line=$RESULT_LINE"

cp "$MUT/orig/$TARGET" "$FILE"
cp "$MUT/orig/$TARGET" "$CLASSES/$TARGET"
RESTORED_MD5=$(md5sum "$FILE" | awk '{print $1}')
RESTORED_CLASSES_MD5=$(md5sum "$CLASSES/$TARGET" | awk '{print $1}')
echo "restored_md5=$RESTORED_MD5 restored_classes_md5=$RESTORED_CLASSES_MD5 orig_md5=$ORIG_MD5"
if [ "$RESTORED_MD5" != "$ORIG_MD5" ] || [ "$RESTORED_CLASSES_MD5" != "$ORIG_MD5" ]; then echo "DEVICE FAIL: restore mismatch"; exit 1; fi

# 期望：e2e 红，且红点里**含指定的断言**（红在对的地方才算杀）。
OK=1
[ "$E2E_RC" = "1" ] || OK=0
if ! echo "$RESULT_LINE" | grep -q "$EXPECT"; then OK=0; echo "DEVICE FAIL: 预期红点 $EXPECT 未出现"; fi
if [ "$OK" = "1" ]; then echo "MUTATION $ID: KILLED (expected red $EXPECT observed)"; exit 0; else echo "MUTATION $ID: SURVIVED / unexpected"; exit 1; fi
