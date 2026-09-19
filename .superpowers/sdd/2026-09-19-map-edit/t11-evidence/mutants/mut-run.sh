#!/usr/bin/env bash
# M8 T11 变异装置（前端字节）。
#   把变异体写进 **webui 源** → node --check（语法不过 ⇒ 假红，作废）→ 跑 e2e（必须**红在指定断言上**）
#   → 从 mutants/orig 逐字节还原（src + target/classes）→ 断言 md5 复原。
#
# ★ 与 T10 的 Java 变异装置不同：T11 的四个变异体**全在 `webui/map.js`**（一行 Java 都不碰）⇒ 没有 javac 轮，
#   但**"这份字节真的被跑了吗"这一关反而更硬**：`run-e2e.sh` 起服务前把 src→target/classes/webui 逐字节同步
#   并打印 `map_js_md5=`，本脚本断言它 **== 本轮的 mutant_md5**（形态 6：装置的产物要自指）。
#
# 用法: mut-run.sh <m1|m2|m3|m4|m6> <port>
# 退出码 0 = 该变异体被对应护栏杀掉（预期红且红在对的断言上），1 = 存活 / 红错地方 / 装置自身出错。
# ★ 本机化：ROOT 与 NODE_PATH 是按机器的（别处是 /home/cna/...）；端口勿用 5817/5818。
set -u
ROOT=/home/dev/SimulatorMosire/.claude/worktrees/m8t5
EV="$ROOT/.superpowers/sdd/2026-09-19-map-edit/t11-evidence"
WEB="$ROOT/simos-app/src/main/resources/webui"
CLASSES="$ROOT/simos-app/target/classes/webui"
MUT="$EV/mutants"
TARGET="map.js"
mkdir -p "$MUT/orig" "$MUT/logs"

ID="${1:-}"; PORT="${2:-5822}"
FILE="$WEB/$TARGET"
ORIG="$MUT/orig/$TARGET"

# ── ① 干净世界：orig 是首次的快照；每次开跑前 src 必须**逐字节**等于它 ──────────────────
#    否则 orig 是陈旧快照（或上一轮没还原干净）⇒ 还原会把工作树写回旧版本，而"绿/红"都还没有意义。
if [ ! -f "$ORIG" ]; then cp "$FILE" "$ORIG"; fi
if ! cmp -s "$FILE" "$ORIG"; then
  echo "DEVICE FAIL(①): src/$TARGET 与 mutants/orig 不一致（陈旧快照或上轮未还原）"
  echo "  src_md5=$(md5sum "$FILE" | awk '{print $1}') orig_md5=$(md5sum "$ORIG" | awk '{print $1}')"
  exit 1
fi
cp "$ORIG" "$FILE"

case "$ID" in
  m1) EXPECT="e1-unselected-mode-zero-write" ;;          # UI 静默兜 replace（Q2 明令禁止）
  m2) EXPECT="r1-seed-reaches-payload-verbatim" ;;       # seed 输入被忽略
  m3) EXPECT="x1-nonadjacent-jump-zero-write" ;;         # edgeChainEdges 相邻性护栏被删
  m4) EXPECT="r0c-empty-seed-zero-write-with-warning" ;; # parseSeedInput 空串兜 0（自设计：打新纯函数）
  m6) EXPECT="r0a-empty-selection-zero-write-with-warning" ;; # randomizeSelectionState 接受空选区（自设计：打新纯函数）
  *) echo "usage: mut-run.sh <m1|m2|m3|m4|m6> <port>"; exit 1 ;;
esac

case "$ID" in
  m1)
    python3 - "$FILE" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = '''    if (value === "merge" || value === "replace") {
      return { ok: true, mode: value };
    }
    return { ok: false, mode: null };'''
new = '''    if (value === "merge" || value === "replace") {
      return { ok: true, mode: value };
    }
    // MUTANT m1：UI 静默兜一个 replace 默认值（"无默认"那条护栏被拿掉）。
    return { ok: true, mode: "replace" };'''
assert s.count(old) == 1, "m1 anchor not unique"
open(p, 'w').write(s.replace(old, new))
PY
    ;;
  m2)
    python3 - "$FILE" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = '''    var result = await app.writeCommand("map.RandomizeRegion", {
      hexes: selState.hexes,
      seed: seedState.seed,
    });'''
new = '''    var result = await app.writeCommand("map.RandomizeRegion", {
      hexes: selState.hexes,
      seed: 0,
    });'''
assert s.count(old) == 1, "m2 anchor not unique"
open(p, 'w').write(s.replace(old, new))
PY
    ;;
  m3)
    python3 - "$FILE" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = '''      if (prev && isAdjacent(prev, point)) {'''
new = '''      if (prev) {'''
assert s.count(old) == 1, "m3 anchor not unique"
open(p, 'w').write(s.replace(old, new))
PY
    ;;
  m4)
    python3 - "$FILE" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = '''    var text = typeof raw === "string" ? raw.trim() : "";
    if (text === "" || !/^[+-]?[0-9]+$/.test(text)) {
      return { ok: false, seed: null };
    }'''
new = '''    var text = typeof raw === "string" ? raw.trim() : "";
    // MUTANT m4：空 seed 兜 0（"用户没填"被静默当成一个种子）。
    if (text === "") {
      return { ok: true, seed: 0 };
    }
    if (!/^[+-]?[0-9]+$/.test(text)) {
      return { ok: false, seed: null };
    }'''
assert s.count(old) == 1, "m4 anchor not unique"
open(p, 'w').write(s.replace(old, new))
PY
    ;;
  m6)
    python3 - "$FILE" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = '''    if (!hexes.length) {
      return { ok: false, hexes: [] };
    }
    return { ok: true, hexes: hexes };'''
new = '''    // MUTANT m6：空选区被接受（"选区为空就不发写命令"那条护栏被拿掉）。
    return { ok: true, hexes: hexes };'''
assert s.count(old) == 1, "m6 anchor not unique"
open(p, 'w').write(s.replace(old, new))
PY
    ;;
esac

# ── ② 变异体必须与原件**字节不同**（形态 1：先自证落盘的是哪份字节）────────────────────
ORIG_MD5=$(md5sum "$ORIG" | awk '{print $1}')
MUT_MD5=$(md5sum "$FILE" | awk '{print $1}')
echo "mutation=$ID orig_md5=$ORIG_MD5 mutant_md5=$MUT_MD5 file=$TARGET expect_red=$EXPECT"
if [ -z "$MUT_MD5" ] || [ "$MUT_MD5" = "$ORIG_MD5" ]; then
  echo "DEVICE FAIL(②): mutant 字节与原件相同（或 md5 读到空）"; cp "$ORIG" "$FILE"; exit 1
fi

# ── ③ 语法关：读不过 ⇒ 红是"编译错误式的红"，不算杀 ─────────────────────────────────
node --check "$FILE" > "$MUT/logs/$ID-syntax.log" 2>&1
SYNTAX_RC=$?
echo "syntax_rc=$SYNTAX_RC (must be 0)"
if [ "$SYNTAX_RC" != "0" ]; then
  echo "DEVICE FAIL(③): mutant 不过 node --check"; cp "$ORIG" "$FILE"; exit 1
fi

# ── m4/m6 额外：门禁级自证（新增纯函数测试的**故意违规**用例真的会响）──────────────────────
#    把变异体留在盘上跑前端门禁，必须红在对应那条用例上。
#    ★ m4 那一轮（已跑过、已存档）用的是下面这段**原样**的形态；m6 只**增加**点名检查，不改 m4 的判定口径。
GATE_RC="n/a"
GATE_RED=""
if [ "$ID" = "m4" ] || [ "$ID" = "m6" ]; then
  node "$ROOT/simos-app/src/test/js/run-gate.cjs" > "$MUT/logs/$ID-gate.log" 2>&1
  GATE_RC=$?
  echo "gate_rc=$GATE_RC (expect 非 0)"
  grep -m1 -E 'parseSeedInput-does-not-fall-back-to-zero|not ok' "$MUT/logs/$ID-gate.log" | head -3
fi
if [ "$ID" = "m6" ]; then
  # m6 更严一档：必须**点名**红在那条用例上——"门禁红了"本身可能红在别处（红错地方 = 没杀到）。
  GATE_RED=$(grep -m1 "not ok .*randomizeSelectionState-rejects-an-empty-selection" "$MUT/logs/$ID-gate.log" || true)
  echo "gate_red_line=$GATE_RED"
fi

# ── ④⑤ e2e：装置必须**读到本轮字节**，且红在指定断言上 ────────────────────────────────
bash "$EV/e2e/run-e2e.sh" "$PORT" "$MUT/logs/$ID-e2e" "$MUT/logs/$ID-server.log" > "$MUT/logs/$ID-e2e.log" 2>&1
E2E_RC=$?
echo "e2e_rc=$E2E_RC (expect 1；2=装置崩溃，**不算红**)"

# ★ 形态 6：先断言"我读到了非空"，再拿它比对——否则空串会造出假结论。
CONSUMED_MD5=$(grep -oE 'map_js_md5=[0-9a-f]{32}' "$MUT/logs/$ID-e2e.log" | head -1 | cut -d= -f2)
echo "consumed_md5=$CONSUMED_MD5 (e2e 实际加载的字节)"
if [ -z "$CONSUMED_MD5" ]; then echo "DEVICE FAIL(④): e2e 日志里读不到 map_js_md5（先怀疑自己的读取）"; fi
if [ -n "$CONSUMED_MD5" ] && [ "$CONSUMED_MD5" != "$MUT_MD5" ]; then
  echo "DEVICE FAIL(④): e2e 加载的字节 != 本轮变异体（跑的不是这一轮）"
fi
RESULT_LINE=$(grep -m1 "E2E RESULT" "$MUT/logs/$ID-e2e.log" || true)
echo "result_line=$RESULT_LINE"
RED_LINE=$(grep -m1 "^STEP $EXPECT: FAIL" "$MUT/logs/$ID-e2e.log" || true)
echo "red_line=$RED_LINE"

# ── ⑥ 逐字节还原（src + target/classes 两处都要）＋ 断言复原 ──────────────────────────
cp "$ORIG" "$FILE"
if [ -d "$CLASSES" ]; then cp "$ORIG" "$CLASSES/$TARGET"; fi
RESTORED_MD5=$(md5sum "$FILE" | awk '{print $1}')
RESTORED_CLASSES_MD5=$(md5sum "$CLASSES/$TARGET" | awk '{print $1}')
echo "restored_md5=$RESTORED_MD5 restored_classes_md5=$RESTORED_CLASSES_MD5 orig_md5=$ORIG_MD5"
if [ -z "$RESTORED_MD5" ] || [ "$RESTORED_MD5" != "$ORIG_MD5" ] || [ "$RESTORED_CLASSES_MD5" != "$ORIG_MD5" ]; then
  echo "DEVICE FAIL(⑥): 还原不一致"; exit 1
fi
# ★ 装置自指（形态 6）：把这一轮跑的是哪份字节写进**日志本身**，不只打在终端。
{
  echo "--- device self-record ($ID) ---"
  echo "orig_md5=$ORIG_MD5 mutant_md5=$MUT_MD5 consumed_md5=$CONSUMED_MD5 restored_md5=$RESTORED_MD5"
  echo "syntax_rc=$SYNTAX_RC gate_rc=$GATE_RC gate_red_line=$GATE_RED e2e_rc=$E2E_RC expect_red=$EXPECT"
} >> "$MUT/logs/$ID-e2e.log"

# ── ⑦ 判定：rc 必须是 1（2 = 装置崩了，不是红）；红点里必须**含**指定断言 ─────────────
OK=1
[ "$E2E_RC" = "1" ] || { OK=0; echo "NOT-KILLED: e2e_rc=$E2E_RC（只有 1 才算红）"; }
if [ -n "$CONSUMED_MD5" ] && [ "$CONSUMED_MD5" != "$MUT_MD5" ]; then OK=0; fi
if [ -z "$RED_LINE" ]; then OK=0; echo "NOT-KILLED: 预期红点 $EXPECT 未出现（红在别处 = 没杀到）"; fi
if { [ "$ID" = "m4" ] || [ "$ID" = "m6" ]; } && [ "$GATE_RC" = "0" ]; then OK=0; echo "NOT-KILLED: $ID 在前端门禁里没红（新纯函数测试是装饰）"; fi
if [ "$ID" = "m6" ] && [ -z "$GATE_RED" ]; then OK=0; echo "NOT-KILLED: m6 的门禁红点没点名到 randomizeSelectionState（红在别处 = 没杀到）"; fi
if [ "$OK" = "1" ]; then
  echo "MUTATION $ID: KILLED (expected red $EXPECT observed)"
  exit 0
fi
echo "MUTATION $ID: SURVIVED / unexpected"
exit 1
