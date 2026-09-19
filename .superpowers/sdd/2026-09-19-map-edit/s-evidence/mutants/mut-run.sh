#!/usr/bin/env bash
# M8-S 变异装置（m10/m11/m12）：把变异体写进 webui 源 → 自证字节（md5 非空且 != 原件）→ node --check（语法必须过）
#   → 同步 target/classes/webui（服务端从那里取资源）→ 跑 e2e（期望红）→ 从 mutants/orig **逐字节还原**
#   → 断言 src 与 classes 的 md5 都复原、且 webui 聚合 md5 复原（防"还原打回旧版本"的假红）。
# 用法: mut-run.sh <m10|m11|m12> <port> <store-src>
# 退出码 0 = 该变异体被对应护栏杀掉（预期红）；1 = 未被杀 / 装置自身出错。
set -u
ROOT=/home/cna/SimulatorMosire/.claude/worktrees/m8s
EV="$ROOT/.superpowers/sdd/2026-09-19-map-edit/s-evidence"
WEB="$ROOT/simos-app/src/main/resources/webui"
MUT="$EV/mutants"
STORE_SRC="${3:-/tmp/m8t1-e2e-store}"
mkdir -p "$MUT/orig" "$MUT/logs"

FILES="map.js app.js modes.js blocks.js panels.js unitTree.js timeline.js api.js index.html styles.css"
AGGREGATE() {
  local listing
  listing=$(cd "$1" && for f in $FILES; do printf '%s  %s\n' "$(md5sum "$f" | awk '{print $1}')" "$f"; done)
  if [ -z "$listing" ]; then echo "AGGREGATE-EMPTY"; return 1; fi
  printf '%s\n' "$listing" | md5sum | awk '{print $1}'
}

# ★ 门禁 1：orig 快照存在；变异前 src 必须逐字节等于 orig。
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

for f in $FILES; do cp "$MUT/orig/$f" "$WEB/$f"; done

case "$ID" in
  m10)
    # 护栏：边界精确（不简化）⇒ 变异：渲染重新引入简化（隔点抽稀，等价于 RDP 的"压掉顶点"）。
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''        var rings = regionBoundaryRings(entry.hexes || []);'''
new='''        var rings = regionBoundaryRings(entry.hexes || []).map(function (r) {
          return r.filter(function (_, i) { return i % 2 === 0; });
        });'''
assert s.count(old)==1, "m10 anchor not unique"
open(p,'w').write(s.replace(old,new))
PY
    ;;
  m11)
    # 护栏：重名主动提示 ⇒ 变异：去掉重名检测（直接提交，不弹二选一）。
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''    var same = findSameNameRegion(name);'''
new='''    var same = null; // mutant m11: 去掉重名检测，直接提交'''
assert s.count(old)==1, "m11 anchor not unique"
open(p,'w').write(s.replace(old,new))
PY
    ;;
  m12)
    # 护栏：合并=并集（重名路径）⇒ 变异：只取新 hex（覆盖，丢原 hex）。
    python3 - "$FILE" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''    var hexes = unionHexes(existing.hexes || [], pending.hexes);'''
new='''    var hexes = pending.hexes.slice(); // mutant m12: 覆盖而非并集'''
assert s.count(old)==1, "m12 anchor not unique"
open(p,'w').write(s.replace(old,new))
PY
    ;;
  *) echo "unknown mutation $ID"; exit 1 ;;
esac

# ★ 门禁 2：变异体必须与原件字节不同。
MUT_MD5=$(md5sum "$FILE" | awk '{print $1}')
if [ -z "$MUT_MD5" ] || [ "$MUT_MD5" = "$ORIG_MD5" ]; then echo "DEVICE FAIL: mutant bytes identical/empty"; exit 1; fi
echo "mutation=$ID orig_md5=$ORIG_MD5 mutant_md5=$MUT_MD5 file=map.js"

# ★ 门禁 3：语法必须过。
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

# ★ 门禁 5：跑 e2e（期望红）。
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
# ★ 门禁 7：聚合 md5 非空且复原。
if [ -z "$RESTORED_AGG" ] || [ "$RESTORED_AGG" = "AGGREGATE-EMPTY" ] || [ "$RESTORED_AGG" != "$ORIG_AGG" ]; then
  echo "DEVICE FAIL: aggregate md5 mismatch/empty"; exit 1
fi

# ★ 门禁 8/9：期望红。
if [ "$E2E_RC" = "1" ]; then echo "MUTATION $ID: KILLED (expected red observed)"; exit 0; fi
echo "MUTATION $ID: SURVIVED / unexpected"; exit 1
