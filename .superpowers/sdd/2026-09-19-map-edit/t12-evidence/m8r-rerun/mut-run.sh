#!/usr/bin/env bash
# M8 T12「还债」变异装置：M8-R 的 m1~m8 在**当前字节**上重跑（锚点按 M8-S 之后的结构重写）。
#   形态取自 t9-evidence/mutants/mut-run.sh（同一套纪律），并补两处本题特有的东西：
#     ① 每轮带 `OUTCOME`（kill / unobservable）——本题**已知会有多个"无判据可观察"的变异体**，
#        装置必须能把"没有判据"（测量到两侧全绿）与"装置坏了"分开，不许混为一谈；
#     ② 锚点行号由变异脚本**现场打印**（ANCHOR_LINE=n，来自真实匹配位置），写进日志——
#        报告里的 `文件:行号` 是**量出来的**，不是推出来的。
#   流程：干净世界核对 → 变异（锚点必须唯一）→ 自证字节（非空且 != 原件）→ node --check
#        → 门禁（run-gate.cjs）→ 演示 e2e（t9 的 run-e2e.sh，--demo，本机唯一可跑的 e2e）
#        → 独立复算 agg_md5 与 e2e 实际加载的字节比对 → 逐字节还原（src + target/classes 两处）
#        → 把本轮跑的是哪份字节 + 装置自己的 md5 写进**日志本身**（形态 6 自指）。
#
# 用法: mut-run.sh <clean|m1|m2|m3|m4|m5|m6|m7|m8> <port>
# 退出码 0 = **预测兑现**（kill 轮：红在对的点名断言上；unobservable 轮：两侧实测全绿）；
#        1 = 预测未兑现 / 装置自身出错。
# ★ 本机化：ROOT 与 NODE_PATH 是按机器的（旧装置 r-evidence/mut-run.sh:8 指着 /home/cna/…，不能原样复用）。
set -u
ROOT=/home/dev/SimulatorMosire/.claude/worktrees/m8t5
BASE="$ROOT/.superpowers/sdd/2026-09-19-map-edit"
EV="$BASE/t12-evidence/m8r-rerun"
T9EV="$BASE/t9-evidence"          # 本机唯一可跑的 e2e 装置（--demo）
WEB="$ROOT/simos-app/src/main/resources/webui"
CLASSES="$ROOT/simos-app/target/classes/webui"
JS="$ROOT/simos-app/src/test/js"
MUT="$EV/mutants"
mkdir -p "$MUT/orig" "$MUT/logs"
# ★ 装置自指：装置被改过之后，"早先几轮的旧证据还成不成立"必须能从日志里读出来，不能靠推导。
SELF_MD5=$(md5sum "$0" | awk '{print $1}')

ID="${1:-}"; PORT="${2:-5861}"

# ── 每轮：目标文件 / 预期红点（两套命名不可互替：门禁 = `not ok <n> - <用例名>`，e2e = `STEP <名>: FAIL`）/ 预期结局 ──
case "$ID" in
  clean) OUTCOME="green";      TARGET="map.js"; EXPECT="" ;;
  m1) OUTCOME="unobservable";  TARGET="map.js"; EXPECT="" ;;
  m2) OUTCOME="unobservable";  TARGET="map.js"; EXPECT="" ;;
  m3) OUTCOME="unobservable";  TARGET="map.js"; EXPECT="" ;;
  m4) OUTCOME="unobservable";  TARGET="map.js"; EXPECT="" ;;
  m5) OUTCOME="unobservable";  TARGET="map.js"; EXPECT="" ;;
  m6) OUTCOME="kill";          TARGET="map.js"; EXPECT="a5-five-modes-left-drag-zero-write" ;;
  m7) OUTCOME="unobservable";  TARGET="map.js"; EXPECT="" ;;
  m8) OUTCOME="unobservable";  TARGET="map.js"; EXPECT="" ;;
  *) echo "usage: mut-run.sh <clean|m1..m8> <port>"; exit 1 ;;
esac
FILE="$WEB/$TARGET"
ORIG="$MUT/orig/$TARGET"

# ── ① 干净世界：src 必须**逐字节**等于 orig 快照（否则"还原"会把工作树打回别的版本）──────────
if [ ! -f "$ORIG" ]; then cp "$FILE" "$ORIG"; fi
if ! cmp -s "$FILE" "$ORIG"; then
  echo "DEVICE FAIL(①): src/$TARGET 与 mutants/orig 不一致（陈旧快照或上轮未还原）"
  echo "  src_md5=$(md5sum "$FILE" | awk '{print $1}') orig_md5=$(md5sum "$ORIG" | awk '{print $1}')"
  exit 1
fi
cp "$ORIG" "$FILE"
ORIG_MD5=$(md5sum "$ORIG" | awk '{print $1}')

# ── ② 变异体（python 就地改；锚点必须唯一；现场打印锚点行号）─────────────────────────────
ANCHOR_LINE="n/a"
MUT_MD5="$ORIG_MD5"
if [ "$ID" != "clean" ]; then
  ANCHOR_LINE=$(python3 - "$FILE" "$ID" <<'PY'
import sys
p, mid = sys.argv[1], sys.argv[2]
s = open(p, encoding='utf-8', newline='').read()
old = new = None

if mid == "m1":
    # 守护：套索建区**重叠允许**。变异：与已有区域相交就拒绝（M8-R m1 的语义，锚点按当前结构挪到空集检查之后）。
    old = '''    if (!hexes || !hexes.length) {
      setRegionEditStatus("套索为空或不闭合（至少 3 个格），未创建。", "warn");
      return null;
    }
'''
    new = old + '''    // MUTANT m1：与已有区域相交就拒绝（"重叠允许"的反向）。
    var __probe = await api.mapHex(hexes[0].q, hexes[0].r, app.target());
    if (((__probe && __probe.regions) || []).length > 0) {
      setRegionEditStatus("禁止重叠：与已有区域相交", "err");
      return null;
    }
'''
elif mid == "m2":
    # 守护：右键**按模式分派**。变异：去掉早退 + 放宽 unit 守卫 ⇒ region-edit 右键仍走 unit.PlanRoute。
    old = '''    var contextMode = app.getState().mode;
    if (contextMode === "region-edit" || contextMode === "map-edit") {
      return true;
    }
'''
    new = ''
elif mid == "m3":
    # 守护：小点只画 focus 区域。变异：clearFocusHexes 空实现（取消选中后焦点集合留存 ⇒ 仍画点）。
    old = '''    function clearFocusHexes() {
      setFocusHexes([], null);
    }'''
    new = '''    function clearFocusHexes() {
      // MUTANT m3：不清焦点 ⇒ 未选中区域仍画点
    }'''
elif mid == "m4":
    # 守护：合并 = 并集。变异：只取临时选区（丢弃已选区域）。
    old = '''    var base = await fetchRegionCached(id);
    var hexes = unionHexes(base.hexes, regionDraftList());
'''
    new = '''    var base = await fetchRegionCached(id);
    var hexes = regionDraftList();
'''
elif mid == "m5":
    # 守护：剔除 = 差集。变异：误用并集。
    old = '''    var base = await fetchRegionCached(id);
    var hexes = differenceHexes(base.hexes, regionDraftList());
'''
    new = '''    var base = await fetchRegionCached(id);
    var hexes = unionHexes(base.hexes, regionDraftList());
'''
elif mid == "m6":
    # 守护：地形编辑左键 = 平移地图（零写）。变异：左键仍刷地形（开始 painting ⇒ 松手落一条命令）。
    old = '''      if (event.button !== 0) {
        return;
      }
      // ★ §七：左键在所有编辑模式统一为"平移地图"（永不误改）；唯一例外是抓住编辑手柄——
'''
    new = '''      if (event.button !== 0) {
        return;
      }
      if (mode === "map-edit") {
        beginPaint(event);
        return;
      }
      // ★ §七：左键在所有编辑模式统一为"平移地图"（永不误改）；唯一例外是抓住编辑手柄——
'''
elif mid == "m7":
    # 守护：Shift+右键 = 逐格画/擦。变异：Shift 分支未实现（落回套索）。
    old = '''          if (event.shiftKey) {
            beginPaint(event);
            return;
          }
'''
    new = '''          /* MUTANT m7: Shift+右键未实现，落回套索 */
'''
elif mid == "m8":
    # 守护：不再有逐格/块边界描边。变异：**按当前树的真实绘制代码**把旧边框层画回来——
    #   当前树的地形 Pass 1 是 `blocks.forEach` + `appendBlockTo` 聚合成 Path2D 后**只 fill 不 stroke**
    #   （map.js:747-764）。这里在同一 forEach 里给**每一块**再建一条自己的 Path2D 并用旧树的 `#0d1015`
    #   细线 stroke（旧树就是这条线，画出"地形块之间的黑色间隔"）；旧 m8 用的 `blocks[].boundaries` 手写环
    #   在当前树已由 `appendBlockTo` 承担 ⇒ 新锚点仍表达"恢复逐块边界描边 + Path2D stroke"。
    old = '''      blocks.forEach(function (block) {
        var color = terrainColor(block.terrain);
        if (!byColor[color]) {
          byColor[color] = new Path2D();
          colors.push(color);
        }
        appendBlockTo(byColor[color], block);
        paintedRingCount += (block.boundaries || []).length;
      });
'''
    new = '''      blocks.forEach(function (block) {
        var color = terrainColor(block.terrain);
        if (!byColor[color]) {
          byColor[color] = new Path2D();
          colors.push(color);
        }
        appendBlockTo(byColor[color], block);
        // MUTANT m8：恢复旧树的**块边界描边层**（每块一条 Path2D + 深色细线）。
        var __mutBorder = new Path2D();
        appendBlockTo(__mutBorder, block);
        targetCtx.strokeStyle = "#0d1015";
        targetCtx.lineWidth = 1 / view.scale;
        targetCtx.stroke(__mutBorder);
        paintedRingCount += (block.boundaries || []).length;
      });
'''
else:
    raise SystemExit("unknown mutation " + mid)

assert s.count(old) == 1, mid + " anchor not unique (count=%d)" % s.count(old)
line = s.count('\n', 0, s.index(old)) + 1
if new == '':
    # m2 的第一处是**删除**，第二处是**放宽守卫**（两处都要改，故这里单独处理）。
    s = s.replace(old, new)
    old2 = '''    if (app.getState().mode !== "unit") {
'''
    assert s.count(old2) == 1, "m2 anchor2 not unique (count=%d)" % s.count(old2)
    s = s.replace(old2, '''    if (app.getState().mode !== "unit" && app.getState().mode !== "region-edit") {
''')
else:
    assert new != old, mid + " mutant text identical to anchor"
    s = s.replace(old, new)
open(p, 'w', encoding='utf-8', newline='').write(s)
print(str(line))
PY
) || { echo "DEVICE FAIL(②): 变异脚本失败（锚点不唯一/找不到）——见上"; cp "$ORIG" "$FILE"; exit 1; }
  MUT_MD5=$(md5sum "$FILE" | awk '{print $1}')
  # ── ③ 变异体必须与原件**字节不同**（先自证落盘的是哪份字节；空 md5 不算不同）──────────────
  if [ -z "$MUT_MD5" ] || [ "$MUT_MD5" = "$ORIG_MD5" ]; then
    echo "DEVICE FAIL(③): mutant 字节与原件相同（或 md5 读到空）"; cp "$ORIG" "$FILE"; exit 1
  fi
  # ── ④ 语法关：读不过 ⇒ 红是"语法错式的红"，不算杀 ────────────────────────────────────
  node --check "$FILE" > "$MUT/logs/$ID-syntax.log" 2>&1
  SYNTAX_RC=$?
  if [ "$SYNTAX_RC" != "0" ]; then
    echo "DEVICE FAIL(④): mutant 不过 node --check (rc=$SYNTAX_RC)"; cp "$ORIG" "$FILE"; exit 1
  fi
else
  SYNTAX_RC="n/a"
fi
echo "mutation=$ID outcome=$OUTCOME file=$TARGET anchor_line=$ANCHOR_LINE orig_md5=$ORIG_MD5 mutant_md5=$MUT_MD5 syntax_rc=$SYNTAX_RC"

# ── ⑤ 门禁（纯函数级；与 e2e 是两套命名）──────────────────────────────────────────────
node "$JS/run-gate.cjs" > "$MUT/logs/$ID-gate.log" 2>&1
GATE_RC=$?
GATE_TESTS=$(grep -oE '# tests [0-9]+' "$MUT/logs/$ID-gate.log" | tail -1)
GATE_FAILS=$(grep -oE '# fail [0-9]+' "$MUT/logs/$ID-gate.log" | tail -1)
GATE_RED=$(grep -E "^not ok [0-9]+ - " "$MUT/logs/$ID-gate.log" | head -5 || true)
echo "gate_rc=$GATE_RC ($GATE_TESTS / $GATE_FAILS)"

# ── ⑥ e2e（本机唯一可跑：t9 的 --demo 装置）。rc=2 = **装置崩溃**，既不是红也不是绿 ──────────
AGG_EXPECT=$(cd "$WEB" && find . -type f -print0 | sort -z | xargs -0 md5sum | md5sum | awk '{print $1}')
echo "agg_expect=$AGG_EXPECT (独立复算)"
E2E_RC=""
ATTEMPTS=0
for a in 1 2; do
  ATTEMPTS=$a
  bash "$T9EV/e2e/run-e2e.sh" "$PORT" "$MUT/logs/$ID-out" "$MUT/logs/$ID-server.log" \
    > "$MUT/logs/$ID-e2e.attempt$a.log" 2>&1
  E2E_RC=$?
  echo "e2e_attempt=$a e2e_rc=$E2E_RC"
  [ "$E2E_RC" != "2" ] && break
  echo "  ★ rc=2 = 装置崩溃（不是红、也不是绿）⇒ 按纪律先怀疑装置，重跑一次"
  sleep 3
done
cat "$MUT/logs/$ID-e2e.attempt1.log" > "$MUT/logs/$ID-e2e.log"
if [ "$ATTEMPTS" -gt 1 ]; then
  { echo "--- attempt2（attempt1 rc=2 ⇒ 重跑）---"; cat "$MUT/logs/$ID-e2e.attempt2.log"; } >> "$MUT/logs/$ID-e2e.log"
fi
E2E_PASS=$(grep -cE "^STEP .*: PASS" "$MUT/logs/$ID-e2e.log")
E2E_FAIL=$(grep -cE "^STEP .*: FAIL" "$MUT/logs/$ID-e2e.log")
E2E_RESULT=$(grep -m1 "E2E RESULT" "$MUT/logs/$ID-e2e.log" || echo "(no E2E RESULT line)")
CONSUMED_AGG=$(grep -oE 'agg_md5=[0-9a-f]{32}' "$MUT/logs/$ID-e2e.log" | head -1 | cut -d= -f2)
echo "e2e_rc=$E2E_RC pass=$E2E_PASS fail=$E2E_FAIL $E2E_RESULT"
echo "consumed_agg=$CONSUMED_AGG (e2e 实际加载的字节)"
FAIL_LINES=$(grep -E "^STEP .*: FAIL" "$MUT/logs/$ID-e2e.log" || true)
RED_LINE=""
[ -n "$EXPECT" ] && RED_LINE=$(grep -m1 "^STEP $EXPECT: FAIL" "$MUT/logs/$ID-e2e.log" || true)

# ── ⑦ 逐字节还原（src + target/classes 两处）＋ 断言复原 ────────────────────────────────
cp "$ORIG" "$FILE"
if [ -d "$CLASSES" ]; then cp "$ORIG" "$CLASSES/$TARGET"; fi
RESTORED_MD5=$(md5sum "$FILE" | awk '{print $1}')
RESTORED_CLASSES_MD5=$(md5sum "$CLASSES/$TARGET" | awk '{print $1}')
echo "restored_md5=$RESTORED_MD5 restored_classes_md5=$RESTORED_CLASSES_MD5 orig_md5=$ORIG_MD5"
if [ -z "$RESTORED_MD5" ] || [ "$RESTORED_MD5" != "$ORIG_MD5" ] || [ "$RESTORED_CLASSES_MD5" != "$ORIG_MD5" ]; then
  echo "DEVICE FAIL(⑦): 还原不一致"; exit 1
fi

# ── ⑧ 形态 6 自指：把这一轮跑的是哪份字节写进**日志本身**，不只打在终端 ──────────────────
{
  echo "--- device self-record ($ID) ---"
  echo "file=$TARGET anchor_line=$ANCHOR_LINE outcome=$OUTCOME"
  echo "orig_md5=$ORIG_MD5 mutant_md5=$MUT_MD5 agg_expect=$AGG_EXPECT consumed_agg=$CONSUMED_AGG restored_md5=$RESTORED_MD5 restored_classes_md5=$RESTORED_CLASSES_MD5"
  echo "harness_mut_run_md5=$SELF_MD5"
  echo "harness_e2e_cjs_md5=$(md5sum "$T9EV/e2e/e2e.cjs" | awk '{print $1}') harness_run_e2e_sh_md5=$(md5sum "$T9EV/e2e/run-e2e.sh" | awk '{print $1}')"
  echo "syntax_rc=$SYNTAX_RC gate_rc=$GATE_RC gate_tests=$GATE_TESTS gate_fails=$GATE_FAILS e2e_rc=$E2E_RC e2e_attempts=$ATTEMPTS e2e_pass=$E2E_PASS e2e_fail=$E2E_FAIL"
  echo "expected_red(e2e)=${EXPECT:-n/a} expected_red_line=$RED_LINE"
  echo "gate_red_lines:"; printf '%s\n' "$GATE_RED"
  echo "e2e_fail_lines:"; printf '%s\n' "$FAIL_LINES"
} >> "$MUT/logs/$ID-e2e.log"
{
  echo "=== round $ID ($(date -u +%FT%TZ)) ==="
  echo "outcome=$OUTCOME anchor_line=$ANCHOR_LINE orig_md5=$ORIG_MD5 mutant_md5=$MUT_MD5 consumed_agg=$CONSUMED_AGG restored_md5=$RESTORED_MD5 restored_classes_md5=$RESTORED_CLASSES_MD5"
  echo "harness_mut_run_md5=$SELF_MD5 syntax_rc=$SYNTAX_RC gate_rc=$GATE_RC e2e_rc=$E2E_RC e2e_pass=$E2E_PASS e2e_fail=$E2E_FAIL expected_red=${EXPECT:-n/a} red_line=$RED_LINE"
} >> "$MUT/logs/runs.log"

# ── ⑨ 判定：预测兑现才算 0 ────────────────────────────────────────────────────────────
OK=1
# 消费字节必须自证：读到非空、且等于本轮变异体（防"跑的不是这一轮"）
if [ -z "$CONSUMED_AGG" ] || [ "$CONSUMED_AGG" != "$AGG_EXPECT" ]; then
  OK=0; echo "DEVICE FAIL(⑥): consumed_agg 空或 != 本轮字节（先怀疑自己的读取）"
fi
case "$OUTCOME" in
  green)
    [ "$GATE_RC" = "0" ] || { OK=0; echo "NOT-GREEN: 干净世界门禁红了（gate_rc=$GATE_RC）"; }
    [ "$E2E_RC" = "0" ] || { OK=0; echo "NOT-GREEN: 干净世界 e2e rc=$E2E_RC（只有 0 算全绿）"; }
    [ "$E2E_FAIL" = "0" ] || { OK=0; echo "NOT-GREEN: 干净世界有 STEP FAIL"; }
    ;;
  kill)
    [ "$E2E_RC" = "1" ] || { OK=0; echo "NOT-KILLED: e2e_rc=$E2E_RC（只有 1 才算红；2=装置崩溃）"; }
    [ -n "$RED_LINE" ] || { OK=0; echo "NOT-KILLED: 预期红点 $EXPECT 未出现（红在别处 = 没杀到）"; }
    ;;
  unobservable)
    # ★ 本轮的"存活"必须是**测量**出来的全绿，不是推导出来的"大概没人管"。
    [ "$GATE_RC" = "0" ] || { OK=0; echo "UNEXPECTED-RED: 本以为无判据，门禁却红了（gate_rc=$GATE_RC）"; }
    [ "$E2E_RC" = "0" ] || { OK=0; echo "UNEXPECTED-RED: 本以为无判据，e2e 却 rc=$E2E_RC fail=$E2E_FAIL"; }
    [ "$E2E_FAIL" = "0" ] || { OK=0; echo "UNEXPECTED-RED: 本以为无判据，e2e 却有 STEP FAIL"; }
    ;;
esac
if [ "$OK" = "1" ]; then
  case "$OUTCOME" in
    green)        echo "ROUND $ID: GREEN (gate rc=0 $GATE_TESTS/$GATE_FAILS; e2e rc=0 pass=$E2E_PASS fail=0)" ;;
    kill)         echo "ROUND $ID: KILLED (e2e_red=$RED_LINE)" ;;
    unobservable) echo "ROUND $ID: SURVIVED-UNOBSERVABLE (gate rc=0; e2e rc=0 pass=$E2E_PASS fail=0 ⇒ 两侧实测全绿，无判据)" ;;
  esac
  exit 0
fi
echo "ROUND $ID: 预测未兑现 / 装置异常"
exit 1
