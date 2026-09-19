#!/usr/bin/env bash
# M8 T9 变异装置（前端字节：map.js / panels.js）。
#   把变异体写进 **webui 源** → node --check（语法不过 ⇒ 假红，作废）→ 跑检查器
#   （`gate` = 前端门禁 run-gate.cjs；`e2e` = 真浏览器装置 run-e2e.sh）
#   → 必须**红在点名的断言上** → 从 mutants/orig 逐字节还原（src + target/classes）→ 断言 md5 复原。
#
# ★ 与 Java 变异装置的区别：本任务的 7 个变异体**全在 webui 的 JS** 里（一行 Java 都不碰）⇒ 没有 javac 轮、
#   没有类名白名单（形态 3 在这里的对应物是"还原必须逐字节"+"跑到的字节必须自指"）。
# ★ "这份字节真的被跑了吗"：e2e 轮用 `run-e2e.sh` 同步 src→target/classes 时打印的 `agg_md5=`（整目录聚合）
#   ——本脚本**独立**按同一算法算一遍并比对（形态 6：先断言读到非空，再比对；空串不许当相等）。
#
# 用法: mut-run.sh <m1|m2|m3|m4|m5|m6|m7> <port>
# 退出码 0 = 该变异体被对应护栏杀掉（预期红且红在对的断言上），1 = 存活 / 红错地方 / 装置自身出错。
# ★ 本机化：ROOT 与 NODE_PATH 是按机器的；端口勿用 5817/5818。
set -u
ROOT=/home/dev/SimulatorMosire/.claude/worktrees/m8t5
EV="$ROOT/.superpowers/sdd/2026-09-19-map-edit/t9-evidence"
WEB="$ROOT/simos-app/src/main/resources/webui"
CLASSES="$ROOT/simos-app/target/classes/webui"
JS="$ROOT/simos-app/src/test/js"
MUT="$EV/mutants"
mkdir -p "$MUT/orig" "$MUT/logs"
# ★ 装置自己也自指：装置被改过之后，"早先几轮的旧证据还成不成立"要能从日志里读出来，
#   不能靠推导（"脚本只加了一行"仍是推导）。故每轮把装置自己的 md5 写进日志。
SELF_MD5=$(md5sum "$0" | awk '{print $1}')

ID="${1:-}"; PORT="${2:-5841}"

# ── 每轮的：目标文件 / 检查器 / 预期红点（两个检查器各有各的用例名，必须分开钉）──────
# ★ 这一节踩过一次：m2 首轮把"门禁红点必须点名 e2e 的 STEP 名"当成了判据 ⇒ 两层都真红了，
#   装置却判 SURVIVED（假阴性）。⇒ **先怀疑自己的判定，别先怀疑被测物**：门禁报的是 JS 用例名
#   （`not ok <n> - <test name>`），e2e 报的是 STEP 名，是两套命名，不可互替。
case "$ID" in
  m1) TARGET="map.js";    CHECKERS="e2e";      EXPECT="h2a-highlight-count-equals-regions-length"; GATE_EXPECT="" ;;
  m2) TARGET="panels.js"; CHECKERS="gate e2e"; EXPECT="h3b-total-is-the-union-not-the-summed-hexcounts"; GATE_EXPECT="page-层-shows-the-union-and-never-a-summed-total" ;;
  m3) TARGET="map.js";    CHECKERS="gate e2e"; EXPECT="h4h-focus-wins-both-of-its-overlap-hexes"; GATE_EXPECT="buildRegionHighlightPlan-puts-focus-regions-first" ;;
  #   ★ m3 的预期红点曾是 h2c，首轮实测**红在 h4h**：h2 的焦点是 t9_a/t9_b 两个从属区域、
  #     被淡化的 t9_c 排在色板末尾 ⇒ 不重排也撞不上；h4 的焦点 t9_a 又恰是色板首位 ⇒ 也不可判别。
  #     真正能判别的是新加的 h4b（焦 t9_b，它与 t9_a、t9_c 各叠一格、在色板里居中）。
  #   ★ m4/m5 起初只跑门禁层。补齐 e2e 层的理由：这两条护栏**在浏览器路径上各有对应断言**
  #     （h4c 两档 alpha / h4d 淡色是混合值）——有断言却从没被变异杀过 = 装饰。
  m4) TARGET="map.js";    CHECKERS="gate e2e"; EXPECT="h4c-two-alpha-levels-are-really-rendered"; GATE_EXPECT="buildRegionHighlightPlan-region-edit-without-focus-fades-everything" ;;
  m5) TARGET="map.js";    CHECKERS="gate e2e"; EXPECT="h4d-faded-fill-is-the-independently-computed-mixture"; GATE_EXPECT="buildRegionHighlightPlan-region-edit-without-focus-fades-everything" ;;
  m6) TARGET="map.js";    CHECKERS="gate";     EXPECT=""; GATE_EXPECT="buildRegionHighlightPlan-region-view-without-focus-paints-nothing" ;;
  m7) TARGET="panels.js"; CHECKERS="gate";     EXPECT=""; GATE_EXPECT="regionMembershipSummary-refuses-to-guess-without-hex-lists" ;;
  *) echo "usage: mut-run.sh <m1|m2|m3|m4|m5|m6|m7> <port>"; exit 1 ;;
esac
FILE="$WEB/$TARGET"
ORIG="$MUT/orig/$TARGET"

# ── ① 干净世界：orig 是首次快照；每次开跑前 src 必须**逐字节**等于它 ──────────────────
if [ ! -f "$ORIG" ]; then cp "$FILE" "$ORIG"; fi
if ! cmp -s "$FILE" "$ORIG"; then
  echo "DEVICE FAIL(①): src/$TARGET 与 mutants/orig 不一致（陈旧快照或上轮未还原）"
  echo "  src_md5=$(md5sum "$FILE" | awk '{print $1}') orig_md5=$(md5sum "$ORIG" | awk '{print $1}')"
  exit 1
fi
cp "$ORIG" "$FILE"

# ── 变异体（python 就地改；锚点必须唯一）──────────────────────────────────────────
case "$ID" in
  m1)
    python3 - "$FILE" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = '''      if (regionIds.length) {
        app.setHighlightRegions(regionIds);'''
new = '''      if (regionIds.length) {
        // MUTANT m1：只高亮**第一个**从属区域（多对多当场退化成一对一）。
        app.setHighlightRegions([regionIds[0]]);'''
assert s.count(old) == 1, "m1 anchor not unique"
open(p, 'w').write(s.replace(old, new))
PY
    ;;
  m2)
    python3 - "$FILE" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = '''    if (summary.complete) {
      total.setAttribute("data-union-count", String(summary.unionCount));
      total.setAttribute("data-shared-hex-count", String(summary.sharedHexCount));
      total.textContent =
        summary.unionCount +'''
new = '''    // MUTANT m2：合计显示**各区域 hexCount 之和**（裁定 72.1 明令禁止的那种读法）。
    var summed = summary.rows.reduce(function (acc, row) {
      return acc + (typeof row.hexCount === "number" ? row.hexCount : 0);
    }, 0);
    if (summary.complete) {
      total.setAttribute("data-union-count", String(summed));
      total.setAttribute("data-shared-hex-count", String(summary.sharedHexCount));
      total.textContent =
        summed +'''
assert s.count(old) == 1, "m2 anchor not unique"
open(p, 'w').write(s.replace(old, new))
PY
    ;;
  m3)
    python3 - "$FILE" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = '''    var ordered = [];
    var rest = [];
    (palette || []).forEach(function (region) {
      if (!region || region.id === null || region.id === undefined) {
        return;
      }
      if (focusList.indexOf(region.id) >= 0) {
        ordered.push(region);
      } else {
        rest.push(region);
      }
    });
    rest.forEach(function (region) {
      ordered.push(region);
    });'''
new = '''    var ordered = [];
    // MUTANT m3：不做"焦点先入"重排 —— setHighlightHexes 对同一 hex 先者胜，
    //   于是与别的区域重叠的焦点格会被淡色盖掉（T10 在真重叠数据上抓到的那个缺陷）。
    (palette || []).forEach(function (region) {
      if (!region || region.id === null || region.id === undefined) {
        return;
      }
      ordered.push(region);
    });'''
assert s.count(old) == 1, "m3 anchor not unique"
open(p, 'w').write(s.replace(old, new))
PY
    ;;
  m4)
    python3 - "$FILE" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = '''        plan.entries.push({ key: h.q + "_" + h.r, color: color, alpha: isFocus ? focusAlpha : fadeAlpha });'''
new = '''        // MUTANT m4：淡色区也拿焦点透明度（"淡"只剩颜色差，透明度档位没了）。
        plan.entries.push({ key: h.q + "_" + h.r, color: color, alpha: focusAlpha });'''
assert s.count(old) == 1, "m4 anchor not unique"
open(p, 'w').write(s.replace(old, new))
PY
    ;;
  m5)
    python3 - "$FILE" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = '''      var color = isFocus ? base : fadeRegionColor(base);'''
new = '''      // MUTANT m5：非焦点区域直接用**原色**（不混合）——"淡"只剩透明度差，色相没淡下去。
      var color = base;'''
assert s.count(old) == 1, "m5 anchor not unique"
open(p, 'w').write(s.replace(old, new))
PY
    ;;
  m6)
    python3 - "$FILE" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = '''    if (!matched && !options.fadeWhenNoFocus) {
      return plan;
    }
'''
assert s.count(old) == 1, "m6 anchor not unique"
open(p, 'w').write(s.replace(old, ''))
PY
    ;;
  m7)
    python3 - "$FILE" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = '''      unionCount: complete ? unionKeys.length : null,'''
new = '''      // MUTANT m7：取不到 hex 列表时拿**求和**顶替（"不知道"变成"看着像个数"）。
      unionCount: complete ? unionKeys.length : sum,'''
assert s.count(old) == 1, "m7 anchor not unique"
open(p, 'w').write(s.replace(old, new))
PY
    ;;
esac

# ── ② 变异体必须与原件**字节不同**（形态 1：先自证落盘的是哪份字节）────────────────────
ORIG_MD5=$(md5sum "$ORIG" | awk '{print $1}')
MUT_MD5=$(md5sum "$FILE" | awk '{print $1}')
echo "mutation=$ID file=$TARGET checkers=$CHECKERS orig_md5=$ORIG_MD5 mutant_md5=$MUT_MD5 expect_red(e2e)=${EXPECT:-n/a} expect_red(gate)=${GATE_EXPECT:-n/a}"
if [ -z "$MUT_MD5" ] || [ "$MUT_MD5" = "$ORIG_MD5" ]; then
  echo "DEVICE FAIL(②): mutant 字节与原件相同（或 md5 读到空）"; cp "$ORIG" "$FILE"; exit 1
fi

# ── ③ 语法关：读不过 ⇒ 红是"语法错式的红"，不算杀 ────────────────────────────────────
node --check "$FILE" > "$MUT/logs/$ID-syntax.log" 2>&1
SYNTAX_RC=$?
echo "syntax_rc=$SYNTAX_RC (must be 0)"
if [ "$SYNTAX_RC" != "0" ]; then
  echo "DEVICE FAIL(③): mutant 不过 node --check"; cp "$ORIG" "$FILE"; exit 1
fi

# ── ④ 检查器 ──────────────────────────────────────────────────────────────────────
GATE_RC="not-run"
GATE_RED=""
E2E_RC="not-run"
CONSUMED_AGG=""
AGG_EXPECT=""
RED_LINE=""
ALL_RED=""

if [ "$CHECKERS" = "gate" ] || [ "$CHECKERS" = "gate e2e" ]; then
  node "$JS/run-gate.cjs" > "$MUT/logs/$ID-gate.log" 2>&1
  GATE_RC=$?
  echo "gate_rc=$GATE_RC (expect 非 0；rc=0 = 存活)"
  GATE_RED=$(grep -E "^not ok [0-9]+ - " "$MUT/logs/$ID-gate.log" | head -5 || true)
  echo "gate_red_lines:"; echo "$GATE_RED"
  ALL_RED="$GATE_RED"
fi

if [ "$CHECKERS" = "e2e" ] || [ "$CHECKERS" = "gate e2e" ]; then
  # ★ 独立复算"源目录聚合 md5"（与 run-e2e.sh 同一算法），用来核对 e2e 实际加载的字节。
  AGG_EXPECT=$(cd "$WEB" && find . -type f -print0 | sort -z | xargs -0 md5sum | md5sum | awk '{print $1}')
  echo "agg_expect=$AGG_EXPECT (独立复算)"
  bash "$EV/e2e/run-e2e.sh" "$PORT" "$MUT/logs/$ID-e2e" "$MUT/logs/$ID-server.log" > "$MUT/logs/$ID-e2e.log" 2>&1
  E2E_RC=$?
  echo "e2e_rc=$E2E_RC (expect 1；2=装置崩溃，**不算红**)"
  # 形态 6：先断言"我读到了非空"，再拿它比对——否则空串会造出假结论。
  CONSUMED_AGG=$(grep -oE 'agg_md5=[0-9a-f]{32}' "$MUT/logs/$ID-e2e.log" | head -1 | cut -d= -f2)
  echo "consumed_agg=$CONSUMED_AGG (e2e 实际同步/加载的字节)"
  if [ -z "$CONSUMED_AGG" ]; then echo "DEVICE FAIL(④): e2e 日志里读不到 agg_md5（先怀疑自己的读取）"; fi
  if [ -n "$CONSUMED_AGG" ] && [ "$CONSUMED_AGG" != "$AGG_EXPECT" ]; then
    echo "DEVICE FAIL(④): e2e 加载的字节 != 本轮变异体（跑的不是这一轮）"
  fi
  RED_LINE=$(grep -m1 "^STEP $EXPECT: FAIL" "$MUT/logs/$ID-e2e.log" || true)
  echo "expected_red_line=$RED_LINE"
  if [ -z "$RED_LINE" ]; then
    echo "e2e 实际红点（前 10 条）:"; grep -E "^STEP .*: FAIL" "$MUT/logs/$ID-e2e.log" | head -10
  fi
  ALL_RED=$(printf '%s\n%s' "$GATE_RED" "$(grep -E "^STEP .*: FAIL" "$MUT/logs/$ID-e2e.log" | head -5 || true)")
fi

# ── ⑤ 逐字节还原（src + target/classes 两处）＋ 断言复原 ──────────────────────────────
cp "$ORIG" "$FILE"
if [ -d "$CLASSES" ]; then cp "$ORIG" "$CLASSES/$TARGET"; fi
RESTORED_MD5=$(md5sum "$FILE" | awk '{print $1}')
RESTORED_CLASSES_MD5=$(md5sum "$CLASSES/$TARGET" | awk '{print $1}')
echo "restored_md5=$RESTORED_MD5 restored_classes_md5=$RESTORED_CLASSES_MD5 orig_md5=$ORIG_MD5"
if [ -z "$RESTORED_MD5" ] || [ "$RESTORED_MD5" != "$ORIG_MD5" ] || [ "$RESTORED_CLASSES_MD5" != "$ORIG_MD5" ]; then
  echo "DEVICE FAIL(⑤): 还原不一致"; exit 1
fi
# ★ 装置自指（形态 6）：把这一轮跑的是哪份字节写进**日志本身**，不只打在终端。
{
  echo "--- device self-record ($ID) ---"
  echo "file=$TARGET orig_md5=$ORIG_MD5 mutant_md5=$MUT_MD5 agg_expect=$AGG_EXPECT consumed_agg=$CONSUMED_AGG restored_md5=$RESTORED_MD5"
  echo "harness_mut_run_md5=$SELF_MD5"
  echo "syntax_rc=$SYNTAX_RC gate_rc=$GATE_RC e2e_rc=$E2E_RC expect_red(e2e)=${EXPECT:-n/a} expect_red(gate)=${GATE_EXPECT:-n/a}"
  echo "expected_red_line=$RED_LINE"
} >> "$MUT/logs/$ID-e2e.log"

# ── ⑥ 判定 ────────────────────────────────────────────────────────────────────────
OK=1
case "$CHECKERS" in
  gate)      [ "$GATE_RC" != "0" ] && [ "$GATE_RC" != "not-run" ] || { OK=0; echo "NOT-KILLED: 门禁没红（gate_rc=$GATE_RC）"; } ;;
  e2e)       [ "$E2E_RC" = "1" ] || { OK=0; echo "NOT-KILLED: e2e_rc=$E2E_RC（只有 1 才算红）"; } ;;
  "gate e2e") [ "$GATE_RC" != "0" ] && [ "$GATE_RC" != "not-run" ] || { OK=0; echo "NOT-KILLED: 门禁没红"; }
             [ "$E2E_RC" = "1" ] || { OK=0; echo "NOT-KILLED: e2e_rc=$E2E_RC（只有 1 才算红）"; } ;;
esac
if [ "$CHECKERS" != "gate" ]; then
  if [ -z "$CONSUMED_AGG" ] || [ "$CONSUMED_AGG" != "$AGG_EXPECT" ]; then OK=0; echo "NOT-KILLED/DEVICE: 消费字节与变异体不一致"; fi
  if [ -z "$RED_LINE" ]; then OK=0; echo "NOT-KILLED: 预期红点 $EXPECT 未出现（红在别处 = 没杀到）"; fi
fi
if [ "$CHECKERS" != "e2e" ]; then
  # 门禁红点必须**点名**到预期用例（两套命名不可互替：门禁 = JS 用例名，e2e = STEP 名）。
  if ! printf '%s' "$GATE_RED" | grep -q "$GATE_EXPECT"; then OK=0; echo "NOT-KILLED: 门禁红点没点名 $GATE_EXPECT"; fi
fi
if [ "$OK" = "1" ]; then
  echo "MUTATION $ID: KILLED (e2e_red=${EXPECT:-n/a}; gate_red=${GATE_EXPECT:-n/a}; checkers=$CHECKERS)"
  exit 0
fi
echo "MUTATION $ID: SURVIVED / unexpected"
exit 1
