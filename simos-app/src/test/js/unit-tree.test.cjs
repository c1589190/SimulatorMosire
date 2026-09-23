// unit-tree.test.cjs —— 单位编制倒树的纯函数（M7 T5 / spec §六）：buildTree / isBranchPoint；
//   2026-09-23 UI 改造追加：armyOptions / subtreeOf / rootIdOf / clampPanelPosition（unitTree.js）
//   与 stackOffset / stackSpacing（hexgeom.js，同格单位纵向摊开）。
//   2026-09-24 修正 1/2 追加：markerGroups（hexgeom.js，标记按军队根分组）与 clampTreePan
//   （unitTree.js，自由视图平移量夹取）+ 复位控件接线 / 无残留 scrollIntoView 的静态断言。
//   2026-09-24 可用性修复追加：markerScreenVisible / centerViewOn（hexgeom.js，选中单位时"该不该居中、
//   居到哪"）+ UNIT_VISIBLE_MIN_SCALE 的可见性依据 + 定位按钮接线 / 选择收口调用 ensureUnitVisible 的静态断言。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

const {
  buildTree,
  isBranchPoint,
  flatten,
  armyOptions,
  subtreeOf,
  rootIdOf,
  clampPanelPosition,
  clampTreePan,
} = loadWebui("unitTree.js").SimosUnitTree;
const {
  stackOffset,
  stackSpacing,
  markerRadius,
  markerGroups,
  markerScreenVisible,
  centerViewOn,
  worldToScreen,
  UNIT_VISIBLE_MIN_SCALE,
  STACK_MIN_SCREEN_CELL,
  // ★ 2026-09-24 交战格的特殊地图显示（判定 / 布局 / 门控）。
  combatHexes,
  combatSlot,
  combatRowSpacing,
  combatIconOffset,
  combatIconFontSize,
  combatLayoutEnabled,
  COMBAT_SIDE_GAP,
} = loadWebui("hexgeom.js").SimosHexGeom;

const UNITS = [
  { id: "A", name: "甲部", parent: null },
  { id: "B", name: "乙部", parent: "A" },
  { id: "C", name: "丙队", parent: "B" },
  { id: "D", name: "丁队", parent: "B" },
  { id: "E", name: "戊队", parent: "A" },
  { id: "F", name: "己组", parent: "C" },
  { id: "G", name: null, parent: undefined },
  { id: "H", name: "辛队", parent: "ghost" },
];

function byId(roots) {
  return new Map(flatten(roots).map((node) => [node.id, node]));
}

test("buildTree-depth-parent-children-per-node", () => {
  const nodes = byId(buildTree(UNITS));
  const expectDepth = { A: 0, B: 1, C: 2, D: 2, E: 1, F: 3, G: 0, H: 0 };
  const expectChildren = {
    A: ["B", "E"],
    B: ["C", "D"],
    C: ["F"],
    D: [],
    E: [],
    F: [],
    G: [],
    H: [],
  };
  for (const id of Object.keys(expectDepth)) {
    const node = nodes.get(id);
    assert.ok(node, "node " + id + " exists");
    assert.equal(node.depth, expectDepth[id], id + " depth");
    assert.deepEqual(
      node.children.map((c) => c.id).sort(),
      expectChildren[id].slice().sort(),
      id + " children"
    );
  }
});

test("branch-point-is-two-or-more-direct-children-only", () => {
  const nodes = byId(buildTree(UNITS));
  const expect = { A: true, B: true, C: false, D: false, E: false, F: false, G: false, H: false };
  for (const id of Object.keys(expect)) {
    assert.equal(nodes.get(id).branch, expect[id], id + " branch");
    assert.equal(isBranchPoint(nodes.get(id)), expect[id], id + " isBranchPoint");
  }
});

test("branch-teeth-single-vs-double-vs-zero", () => {
  const nodes = byId(buildTree(UNITS));
  assert.equal(isBranchPoint(nodes.get("C")), false);
  assert.equal(isBranchPoint(nodes.get("B")), true);
  assert.equal(isBranchPoint(nodes.get("D")), false);
  assert.equal(isBranchPoint(null), false);
});

test("multi-root-forest-in-input-order", () => {
  const roots = buildTree(UNITS);
  assert.deepEqual(roots.map((r) => r.id), ["A", "G", "H"]);
});

test("missing-or-null-parent-becomes-root", () => {
  const nodes = byId(buildTree(UNITS));
  assert.equal(nodes.get("G").parent, null);
  assert.equal(nodes.get("G").depth, 0);
});

test("dangling-parent-becomes-root-and-is-preserved", () => {
  const nodes = byId(buildTree(UNITS));
  assert.equal(nodes.get("H").parent, "ghost");
  assert.equal(nodes.get("H").depth, 0);
});

test("descendant-count-is-postorder-total", () => {
  const nodes = byId(buildTree(UNITS));
  const expect = { A: 5, B: 3, C: 1, D: 0, E: 0, F: 0, G: 0, H: 0 };
  for (const id of Object.keys(expect)) {
    assert.equal(nodes.get(id).descendantCount, expect[id], id + " descendants");
  }
});

test("empty-input-returns-empty-forest", () => {
  const roots = buildTree([]);
  assert.ok(Array.isArray(roots));
  assert.equal(roots.length, 0);
  assert.equal(buildTree(null).length, 0);
});

test("flatten-returns-depth-first-including-self", () => {
  const ids = flatten(buildTree(UNITS)).map((n) => n.id).sort();
  assert.deepEqual(ids, ["A", "B", "C", "D", "E", "F", "G", "H"]);
});

// ── 2026-09-23 UI 改造：军队选择 / 单军队子树 ──────────────────────────────

test("armyOptions-lists-one-option-per-forest-root", () => {
  const options = armyOptions(UNITS);
  assert.deepEqual(options.map((o) => o.id), ["A", "G", "H"], "选项 = 森林的根（输入序）");
  assert.equal(options[0].name, "甲部", "带 name");
  assert.equal(options[1].name, null, "无名根 name 原样（null）");
  assert.deepEqual(armyOptions([]), [], "空输入 ⇒ 无选项");
});

test("subtreeOf-keeps-only-the-chosen-army", () => {
  const armyA = subtreeOf(UNITS, "A");
  const ids = flatten([armyA]).map((n) => n.id).sort();
  assert.deepEqual(ids, ["A", "B", "C", "D", "E", "F"], "A 的子、孙都在");
  assert.equal(ids.includes("G"), false, "不含另一支军队 G 的任何单位");
  assert.equal(ids.includes("H"), false, "不含另一支军队 H 的任何单位");
  assert.equal(armyA.id, "A", "返回的就是该根节点");
});

test("subtreeOf-unknown-or-dangling-root-returns-null-without-throwing", () => {
  assert.equal(subtreeOf(UNITS, "ghost"), null, "悬空父 id 不是根节点 id ⇒ null（不悄悄退回整片森林）");
  assert.equal(subtreeOf(UNITS, "nope"), null, "不存在的 id ⇒ null");
  assert.equal(subtreeOf(UNITS, null), null, "null ⇒ null");
  assert.equal(subtreeOf([], "A"), null, "空森林 ⇒ null");
});

test("rootIdOf-walks-parent-chain-to-the-army-root", () => {
  assert.equal(rootIdOf(UNITS, "F"), "A", "F→C→B→A");
  assert.equal(rootIdOf(UNITS, "A"), "A", "根自身");
  assert.equal(rootIdOf(UNITS, "H"), "H", "悬空父指针视为根");
  assert.equal(rootIdOf(UNITS, "G"), "G", "parent 缺失视为根");
  assert.equal(rootIdOf(UNITS, "Z"), null, "单位不存在 ⇒ null");
});

// ── 2026-09-23 UI 改造：浮层位置夹取 ────────────────────────────────────────

test("clampPanelPosition-pulls-a-target-back-inside-the-viewport", () => {
  const size = { width: 400, height: 600 };
  const viewport = { width: 1000, height: 800 };
  assert.deepEqual(clampPanelPosition({ x: 5000, y: 5000 }, size, viewport), { x: 600, y: 200 });
  assert.deepEqual(clampPanelPosition({ x: -50, y: -5 }, size, viewport), { x: 0, y: 0 });
  assert.deepEqual(
    clampPanelPosition({ x: 300, y: 300 }, { width: 1200, height: 900 }, viewport),
    { x: 0, y: 0 },
    "浮层比视口还大 ⇒ 贴左上角（上界取 0，不为负）"
  );
});

test("clampPanelPosition-passes-through-a-position-already-inside", () => {
  const pos = { x: 100, y: 80 };
  assert.deepEqual(clampPanelPosition(pos, { width: 400, height: 600 }, { width: 1000, height: 800 }), pos);
  assert.deepEqual(
    clampPanelPosition({ x: 600, y: 200 }, { width: 400, height: 600 }, { width: 1000, height: 800 }),
    { x: 600, y: 200 },
    "正好贴边不算越界"
  );
});

// ── 2026-09-23 UI 改造：同格单位纵向摊开（hexgeom.js）────────────────────────
// ★ 门控吃**屏幕上**的格高 `cellSize × view.scale`（`SCREEN`），不是世界 `cellSize`：
//   工作台的 cellSize 恒定（34），随缩放变的是 view.scale —— 只用 cellSize 会让门控永不改变。

const SCREEN = 400; // 够大的屏幕格高 ⇒ 允许摊开

test("stackOffset-is-zero-for-a-single-unit", () => {
  assert.equal(stackOffset(0, 1, 200, SCREEN), 0, "count=1 无可摊开");
  assert.equal(stackSpacing(1, 200, SCREEN), 0);
});

test("stackOffset-symmetric-distinct-around-center-for-three", () => {
  const cellSize = 200;
  const offsets = [0, 1, 2].map((i) => stackOffset(i, 3, cellSize, SCREEN));
  assert.notEqual(offsets[0], offsets[1]);
  assert.notEqual(offsets[1], offsets[2]);
  assert.notEqual(offsets[0], offsets[2]);
  assert.equal(offsets[1], 0, "奇数个 ⇒ 正中一个偏移 0");
  assert.equal(offsets[0], -offsets[2], "关于 0 中心对称");
  assert.equal(offsets[0] + offsets[1] + offsets[2], 0, "偏移和 = 0");
});

test("stackSpacing-keeps-neighbouring-circles-apart", () => {
  const cellSize = 200;
  const spacing = stackSpacing(3, cellSize, SCREEN);
  assert.ok(spacing > 2 * markerRadius(cellSize), "间距 > 两半径和 ⇒ 相邻圆不重叠");
});

/**
 * ★ 真实数据的判据：三国首都各挤着 8~10 个单位。
 * 旧口径要求"整摞落在格内"（`((count−1)/2)·spacing ≤ cellSize`）⇒ count=8 恒为 0 ⇒ **一个都不摊开**，
 * 用户"方便点击"的诉求完全落空。这条钉住"多到放不进格子也照样摊开"。
 */
test("stackSpacing-still-spreads-when-the-stack-cannot-fit-inside-the-cell", () => {
  const cellSize = 34; // 工作台的 BASE_CELL
  const screenCell = 200; // 放大后屏幕上格高够大
  const spacing = stackSpacing(8, cellSize, screenCell);
  assert.ok(spacing > 0, "8 个单位必须摊开（旧口径在这里返回 0 ⇒ 本断言会红）");
  const offsets = [];
  for (let i = 0; i < 8; i += 1) {
    offsets.push(stackOffset(i, 8, cellSize, screenCell));
  }
  assert.equal(new Set(offsets).size, 8, "8 个单位 8 个互不相同的纵坐标（每个都点得到）");
  // ★ 用容差：count=8 时 `(i−3.5)×spacing` 的浮点和是 1.4e-14 而非精确 0（我第一版写成 === 0，红在这）。
  assert.ok(Math.abs(offsets.reduce((a, b) => a + b, 0)) < 1e-9, "仍关于格心对称");
});

test("stackSpacing-stays-zero-when-the-screen-cell-is-too-small", () => {
  const cellSize = 34;
  const tiny = 5; // 缩得很小：屏幕上格高仅 5px
  const offsets = [0, 1, 2].map((i) => stackOffset(i, 3, cellSize, tiny));
  assert.deepEqual(offsets, [0, 0, 0], "屏幕格太小 ⇒ 保持重叠（几个点会糊成一团）");
  assert.equal(stackSpacing(3, cellSize, tiny), 0);
  assert.ok(stackSpacing(3, cellSize, 24) > 0, "恰好到阈值 ⇒ 摊开");
  assert.equal(stackSpacing(3, cellSize, 23.9), 0, "差一点 ⇒ 不摊开（边界闭合）");
});

// ── 2026-09-24 修正 1：标记按**军队根**分组（hexgeom.js 的 markerGroups）─────────────
// ★ 口径与 unitTree.buildTree / rootIdOf 同源（测试末尾有对拍断言钉住"不另立一套"）。
//   "先按格、再按军队根分组，每组一个标记"——上一轮按**单位**摊开把首都格铺成一长串是错的。

// 首都格：1 个根 + 它的 7 个兵种，全在同一格（三国首都的真实形态）。
const CAPITAL = [{ id: "ROOT", name: "首都军", parent: null, position: { q: 0, r: 0 } }];
for (let i = 1; i <= 7; i += 1) {
  CAPITAL.push({ id: "ROOT-" + i, name: "兵种" + i, parent: "ROOT", position: { q: 0, r: 0 } });
}

// 混合：另有一支分遣队单独在 (9,9)（它的根 ROOT 仍在 (0,0)）＋ 另一国家的军队 B 与 ROOT 同格。
const MIXED = CAPITAL.concat([
  { id: "DET", name: "分遣队", parent: "ROOT", position: { q: 9, r: 9 } },
  { id: "B", name: "乙军", parent: null, position: { q: 0, r: 0 } },
  { id: "B-1", name: "乙军甲队", parent: "B", position: { q: 0, r: 0 } },
]);

test("markerGroups-collapses-a-capital-hex-to-one-army-marker", () => {
  const groups = markerGroups(CAPITAL);
  assert.equal(groups.length, 1, "★ 1 根 + 7 兵种同格 ⇒ 恰好 1 组（旧口径按单位摊开会给出 8）");
  assert.equal(groups[0].rootId, "ROOT", "组 = 该军队根");
  assert.equal(groups[0].leadId, "ROOT", "代表 = 最靠近根的根单位（整支军队）");
  assert.deepEqual(groups[0].at, { q: 0, r: 0 }, "标记的格");
  assert.equal(groups[0].member.length, 8, "member = 编制合计（根 + 7 兵种）");
  assert.equal(groups[0].member[0], "ROOT", "member 按层级升序 ⇒ lead 恒在首位");
});

test("markerGroups-does-not-swallow-a-detachment-alone-in-another-hex", () => {
  const groups = markerGroups(MIXED);
  const det = groups.filter((g) => g.at.q === 9 && g.at.r === 9);
  assert.equal(det.length, 1, "分遣队单独一格 ⇒ 自己一组，不被同军队的其它格合并");
  assert.equal(det[0].rootId, "ROOT", "它仍属 ROOT 这支军队");
  assert.equal(det[0].leadId, "DET", "组里只有它 ⇒ 代表就是它（不能被藏掉）");
  assert.deepEqual(det[0].member, ["DET"]);
  assert.equal(groups.length, 3, "共 3 组：(0,0)×ROOT、(0,0)×B、(9,9)×DET");
});

test("markerGroups-splits-two-armies-in-the-same-hex", () => {
  const groups = markerGroups(MIXED).filter((g) => g.at.q === 0 && g.at.r === 0);
  assert.deepEqual(groups.map((g) => g.rootId), ["ROOT", "B"], "同格两支军队各占一个标记（输入序）");
  assert.deepEqual(groups.map((g) => g.leadId), ["ROOT", "B"]);
  assert.equal(groups[0].member.length, 8, "ROOT 组 = 整支首都军");
  assert.equal(groups[1].member.length, 2, "B 组 = 乙军（根 + 甲队）");
});

test("markerGroups-lead-is-the-shallowest-ancestor-present-on-the-hex", () => {
  const units = [
    { id: "R", parent: null, position: { q: 1, r: 1 } },
    { id: "M", parent: "R", position: { q: 5, r: 5 } },
    { id: "N", parent: "M", position: { q: 5, r: 5 } },
  ];
  const at55 = markerGroups(units).filter((g) => g.at.q === 5 && g.at.r === 5);
  assert.equal(at55.length, 1, "同支军队的 M、N 同格 ⇒ 1 组");
  assert.equal(at55[0].rootId, "R", "根仍是别格的 R");
  assert.equal(at55[0].leadId, "M", "代表 = 组内最浅的 M（不是更深的 N）");
  assert.deepEqual(at55[0].member, ["M", "N"], "member 按深度升序");
});

test("markerGroups-ignores-units-without-a-position-but-still-roots-through-them", () => {
  const units = [
    { id: "R", parent: null, position: null }, // 根没有位置（不在任何格上）
    { id: "C", parent: "R", position: { q: 2, r: 2 } },
  ];
  const groups = markerGroups(units);
  assert.equal(groups.length, 1, "只有 C 能落格 ⇒ 1 组");
  assert.equal(groups[0].rootId, "R", "★ 根判定用**全部**单位：R 无位置，C 仍归 R（不是自己当根）");
  assert.equal(groups[0].leadId, "C");
});

test("markerGroups-root-rule-matches-buildTree-rootIdOf", () => {
  // 给 buildTree 的夹具每个单位配一个位置，逐一对拍"组根 == rootIdOf"（同一口径、不另立一套）。
  const units = UNITS.map((u, i) => ({ ...u, position: { q: i % 3, r: Math.floor(i / 3) } }));
  const rootOfUnit = new Map();
  markerGroups(units).forEach((g) => g.member.forEach((id) => rootOfUnit.set(id, g.rootId)));
  units.forEach((u) => {
    assert.equal(rootOfUnit.get(String(u.id)), rootIdOf(units, u.id), u.id + " 的组根 = rootIdOf");
  });
});

test("markerGroups-empty-and-null-input-return-no-markers", () => {
  assert.deepEqual(markerGroups([]), []);
  assert.deepEqual(markerGroups(null), []);
});

// ── 2026-09-24 修正 2：自由视图平移量夹取（unitTree.js 的 clampTreePan）────────────
// 内容比视口大 ⇒ 夹在 [viewport−content, 0]；内容不比视口大 ⇒ **居中**（唯一允许值，拖不走）。

test("clampTreePan-clamps-pan-when-content-overflows-the-viewport", () => {
  const content = { width: 800, height: 600 };
  const viewport = { width: 400, height: 300 };
  assert.deepEqual(
    clampTreePan({ x: -100, y: -50 }, content, viewport),
    { x: -100, y: -50 },
    "范围内的平移原样保留"
  );
  assert.deepEqual(clampTreePan({ x: 50, y: 50 }, content, viewport), { x: 0, y: 0 }, "右/下越界 ⇒ 夹到 0");
  assert.deepEqual(
    clampTreePan({ x: -9999, y: -9999 }, content, viewport),
    { x: -400, y: -300 },
    "左/上越界 ⇒ 夹到 viewport−content（内容至少露一部分）"
  );
});

test("clampTreePan-centers-content-smaller-than-the-viewport", () => {
  const content = { width: 100, height: 80 };
  const viewport = { width: 400, height: 300 };
  assert.deepEqual(
    clampTreePan({ x: 0, y: 0 }, content, viewport),
    { x: 150, y: 110 },
    "内容小 ⇒ 居中 (viewport−content)/2"
  );
  assert.deepEqual(
    clampTreePan({ x: -9999, y: 9999 }, content, viewport),
    { x: 150, y: 110 },
    "内容小时拖不走：忽略传入 pan，恒居中"
  );
});

test("clampTreePan-tolerates-missing-and-non-finite-input", () => {
  const content = { width: 800, height: 600 };
  const viewport = { width: 400, height: 300 };
  assert.deepEqual(clampTreePan(null, content, viewport), { x: 0, y: 0 }, "pan 缺失 ⇒ 按 0 再夹");
  assert.deepEqual(
    clampTreePan({ x: NaN, y: Infinity }, content, viewport),
    { x: 0, y: 0 },
    "非有限数 ⇒ 按 0（不抛、不产生 NaN 的 transform）"
  );
  assert.deepEqual(clampTreePan(undefined, undefined, undefined), { x: 0, y: 0 }, "全缺 ⇒ {0,0}");
});

// ── 2026-09-24 修正 2：复位控件接线 / 无残留死代码（静态扫描）──────────────────────

test("unit-panel-has-a-reset-control-that-is-wired", () => {
  assert.ok(readWebui("index.html").includes('id="unit-tree-reset"'), "index.html 里有复位按钮");
  assert.ok(
    readWebui("unitTree.js").includes('byId("unit-tree-reset")'),
    "unitTree.js 里绑定了复位按钮（resetTreePan）"
  );
});

test("unitTree-has-no-leftover-scrollIntoView-dead-code", () => {
  // ★ 只盯**调用**（`.scrollIntoView(`），注释里提到这个词不算死代码。
  assert.equal(
    /\.scrollIntoView\s*\(/.test(readWebui("unitTree.js")),
    false,
    "自由视图下 .scrollIntoView(...) 是死代码，必须删净（focusUnit 改为设 pan 让节点可见）"
  );
});

// ── 2026-09-24 可用性修复：选中单位时居中（hexgeom.js 纯函数 + 定位按钮接线）──────────
// ★ 用户："我没找到单位在哪"：默认 fit 世界 ⇒ 标记是亚像素。markerScreenVisible 判"该不该拉过去"、
//   centerViewOn 算"拉到哪"；两者是唯一判据（与 worldToScreen/screenToWorld 同一符号约定）。

const VP = { width: 1000, height: 800 };
const MARGIN = 24;

test("markerScreenVisible-true-inside-and-false-outside-the-viewport", () => {
  assert.equal(markerScreenVisible({ x: 500, y: 400 }, VP, MARGIN), true, "正中 ⇒ 可见");
  assert.equal(markerScreenVisible({ x: -1, y: 400 }, VP, MARGIN), false, "左出界 ⇒ 不可见");
  assert.equal(markerScreenVisible({ x: VP.width + 1, y: 400 }, VP, MARGIN), false, "右出界 ⇒ 不可见");
  assert.equal(markerScreenVisible({ x: 500, y: -1 }, VP, MARGIN), false, "上出界 ⇒ 不可见");
  assert.equal(markerScreenVisible({ x: 500, y: VP.height + 1 }, VP, MARGIN), false, "下出界 ⇒ 不可见");
});

test("markerScreenVisible-respects-the-margin-band-boundary", () => {
  // ★ margin 是**内缩**语义：点距四边都 ≥ margin 才算"看得舒服"；边界闭合（恰好等于 ⇒ 可见）。
  assert.equal(markerScreenVisible({ x: MARGIN, y: MARGIN }, VP, MARGIN), true, "左上恰在边界 ⇒ 可见");
  assert.equal(
    markerScreenVisible({ x: VP.width - MARGIN, y: VP.height - MARGIN }, VP, MARGIN),
    true,
    "右下恰在边界 ⇒ 可见"
  );
  assert.equal(markerScreenVisible({ x: MARGIN - 1, y: 400 }, VP, MARGIN), false, "比边界更贴左 ⇒ 不可见");
  assert.equal(markerScreenVisible({ x: 500, y: VP.height - MARGIN + 1 }, VP, MARGIN), false, "比边界更贴下 ⇒ 不可见");
  assert.equal(markerScreenVisible({ x: 5, y: 400 }, VP, 0), true, "margin=0 ⇒ 纯视口内（5 ≥ 0）");
});

test("centerViewOn-centers-the-world-point-under-worldToScreen", () => {
  // ★ 用渲染器**同一套** worldToScreen 反解 ⇒ 若 tx/ty 符号写反（screen = world×scale − t）本断言必红。
  const cases = [
    [{ x: 0, y: 0 }, 0.75],
    [{ x: 1234.5, y: -678.25 }, 2],
    [{ x: -99999, y: 88888 }, 0.03],
  ];
  for (const [world, scale] of cases) {
    const next = centerViewOn(world, VP, scale);
    const screen = worldToScreen(world, next);
    assert.ok(Math.abs(screen.x - VP.width / 2) < 1e-9, "世界点 x 落在视口中心");
    assert.ok(Math.abs(screen.y - VP.height / 2) < 1e-9, "世界点 y 落在视口中心");
  }
});

test("centerViewOn-passes-the-scale-through-unchanged", () => {
  assert.equal(centerViewOn({ x: 10, y: 20 }, VP, 0.75).scale, 0.75, "scale 原样透传");
  assert.equal(centerViewOn({ x: 10, y: 20 }, VP, 12).scale, 12, "不夹取，原样透传（含 MAX_SCALE）");
  // 与 fitView 同式：世界原点居中 ⇒ tx=w/2, ty=h/2。
  assert.deepEqual(centerViewOn({ x: 0, y: 0 }, VP, 1), { scale: 1, tx: 500, ty: 400 });
});

test("UNIT_VISIBLE_MIN_SCALE-makes-the-marker-measurably-visible", () => {
  // ★ minScale 的取值依据：标记屏幕半径 = markerRadius(cellSize) × scale 必须肉眼可见（≥4px），
  //   且屏幕格高 ≥ STACK_MIN_SCREEN_CELL ⇒ 同格多军队会摊开。
  const cellSize = 34; // 工作台 BASE_CELL
  const screenRadius = markerRadius(cellSize) * UNIT_VISIBLE_MIN_SCALE;
  assert.ok(screenRadius >= 4, "标记屏幕半径 " + screenRadius + "px ≥ 4 ⇒ 肉眼可见");
  assert.ok(
    cellSize * UNIT_VISIBLE_MIN_SCALE >= STACK_MIN_SCREEN_CELL,
    "屏幕格高 " + cellSize * UNIT_VISIBLE_MIN_SCALE + "px ≥ " + STACK_MIN_SCREEN_CELL + " ⇒ 同格多军队摊开"
  );
});

test("unit-panel-has-a-locate-control-that-is-wired", () => {
  assert.ok(readWebui("index.html").includes('id="unit-locate"'), "index.html 里有「定位到该军队」按钮");
  assert.ok(
    readWebui("unitTree.js").includes('byId("unit-locate")'),
    "unitTree.js 里绑定了定位按钮（locateCurrentArmy）"
  );
});

test("map-select-choke-point-triggers-ensureUnitVisible", () => {
  assert.ok(
    readWebui("map.js").includes("active.ensureUnitVisible("),
    "map.js 的选择收口（选中单位分支）调用了 ensureUnitVisible"
  );
  assert.ok(
    readWebui("renderer.js").includes("ensureUnitVisible: ensureUnitVisible"),
    "renderer.js 导出了 ensureUnitVisible"
  );
  assert.ok(
    readWebui("map-hostpage.js").includes("renderer.ensureUnitVisible"),
    "bindActive 把 ensureUnitVisible 挂到 window.SimosMap"
  );
});

// ── 2026-09-24 交战格的特殊地图显示（hexgeom.js 纯函数 + renderer 接线）────────────────
//
// 用户（tick15）：德国与奥斯特两支军队合到一格、要进入交战 ⇒ 要"交战方各列两边、中间 ⚔"。
// ★ 触发判据 = **同格 ≥2 个不同 rootId**（或任一组 engaged）——因为没有单位处于 ENGAGED（全 RESTING），
//   只认 ENGAGED 的话用户看不出任何变化，这是必须包含"同格多军队"的现实原因。

// ★ 真数据夹具（服务 5811 / 库 /tmp/simos-worldgen-demo，tick15 rev14，2026-09-24 实测）：
//   奥斯特马克侯国-army（根，member 14800）+ 7 兵种 全在 (-31,-76)；
//   德意志第二帝国-army（根，member 25500）也在 (-31,-76)，但它的 9 个兵种还在旧首都 (-39,-71)。
//   ⇒ (-31,-76) 恰好 2 个不同 rootId（两个交战方）；(-39,-71) 只有 1 个 rootId（德意志兵种）。
const AUST_ROOT = "奥斯特马克侯国-army";
const GER_ROOT = "德意志第二帝国-army";
const AUST_TYPES = ["亲卫", "重骑兵", "轻骑兵", "弓弩手", "重步兵", "轻步兵", "辅助兵"];
const GER_TYPES = [
  "重骑兵",
  "轻骑兵",
  "弓弩手",
  "火绳枪手",
  "重步兵",
  "轻步兵",
  "攻城兵",
  "仆从兵",
  "辎重兵",
];
const REAL_UNITS = [{ id: AUST_ROOT, parent: null, status: "RESTING", position: { q: -31, r: -76 } }]
  .concat(
    AUST_TYPES.map((t) => ({
      id: "奥斯特马克侯国-" + t,
      parent: AUST_ROOT,
      status: "RESTING",
      position: { q: -31, r: -76 },
    }))
  )
  .concat([{ id: GER_ROOT, parent: null, status: "RESTING", position: { q: -31, r: -76 } }])
  .concat(
    GER_TYPES.map((t) => ({
      id: "德意志第二帝国-" + t,
      parent: GER_ROOT,
      status: "RESTING",
      position: { q: -39, r: -71 },
    }))
  );

test("combatHexes-real-data-two-armies-sharing-one-hex-counts-two-parties", () => {
  const groups = markerGroups(REAL_UNITS);
  const combat = combatHexes(groups);
  assert.equal(combat["-31_-76"], 2, "(-31,-76) 上 奥斯特马克 + 德意志 两支军队 ⇒ 恰好 2 个交战方");
  assert.deepEqual(Object.keys(combat), ["-31_-76"], "全图只有这一个交战格");
  assert.equal(combat["-39_-71"], undefined, "(-39,-71) 只有德意志一支军队 ⇒ 不是交战格");
  // 顺带钉住分组本身：(-31,-76) 出 2 个标记（两支军队各一），德意志兵种那格出 1 个。
  assert.equal(groups.filter((g) => g.at.q === -31 && g.at.r === -76).length, 2, "同格两支军队 = 两个标记");
  assert.equal(groups.filter((g) => g.at.q === -39 && g.at.r === -71).length, 1, "德意志 9 兵种同格 = 一个标记");
});

test("combatHexes-one-root-with-many-subunits-is-not-a-combat-hex", () => {
  // CAPITAL = 1 个根 + 7 个兵种全在 (0,0)（三国首都的真实形态）⇒ 只有 1 个 rootId ⇒ **不交战**。
  const combat = combatHexes(markerGroups(CAPITAL));
  assert.deepEqual(combat, {}, "同格只有 1 支军队（哪怕带 7 个兵种）⇒ 不是交战格");
});

test("combatHexes-engaged-group-makes-a-single-root-hex-a-combat-hex", () => {
  const units = [
    { id: "R", parent: null, status: "ENGAGED", position: { q: 0, r: 0 } },
    { id: "R-1", parent: "R", status: "RESTING", position: { q: 0, r: 0 } },
    { id: "R-2", parent: "R", status: "RESTING", position: { q: 0, r: 0 } },
  ];
  const groups = markerGroups(units);
  assert.equal(groups.length, 1, "一支军队一个标记");
  assert.equal(groups[0].engaged, true, "组里任一单位 ENGAGED ⇒ 组 engaged=true");
  const combat = combatHexes(groups);
  assert.equal(combat["0_0"], 1, "★ 只有 1 个 rootId 但有 ENGAGED ⇒ 交战格（交战方数=1）");
});

test("markerGroups-engaged-only-reflects-that-hex-and-empty-input-is-safe", () => {
  // 德意志根 ENGAGED 在 (-31,-76)，但它的兵种 RESTING 在 (-39,-71)：
  // engaged 只认**本格**成员 ⇒ 兵种那组不因根 ENGAGED 而变 true（口径见 hexgeom 注释）。
  const units = [
    { id: GER_ROOT, parent: null, status: "ENGAGED", position: { q: -31, r: -76 } },
    { id: "德意志第二帝国-重骑兵", parent: GER_ROOT, status: "RESTING", position: { q: -39, r: -71 } },
  ];
  const groups = markerGroups(units);
  const at31 = groups.find((g) => g.at.q === -31 && g.at.r === -76);
  const at39 = groups.find((g) => g.at.q === -39 && g.at.r === -71);
  assert.equal(at31.engaged, true, "根自己 ENGAGED ⇒ 该格组 engaged");
  assert.equal(at39.engaged, false, "兵种 RESTING 在别格 ⇒ 那组不受根 ENGAGED 影响");
  assert.deepEqual(combatHexes(markerGroups([])), {}, "空输入 ⇒ 无交战格");
  assert.deepEqual(combatHexes(null), {}, "null 输入 ⇒ 无交战格");
  assert.deepEqual(combatHexes([{ at: { q: 0, r: 0 } }]), {}, "缺 rootId 的组被忽略");
});

test("combatSlot-two-parties-one-each-side-on-the-center-row", () => {
  const cellSize = 34; // 工作台 BASE_CELL
  const left = combatSlot(0, 2, cellSize);
  const right = combatSlot(1, 2, cellSize);
  assert.ok(left.x < 0, "index 0 ⇒ 左侧（x<0）");
  assert.ok(right.x > 0, "index 1 ⇒ 右侧（x>0）");
  assert.equal(left.x, -right.x, "两侧 x 符号相反、大小相等");
  assert.equal(left.y, 0, "count=2 左右各 1 个 ⇒ 都在格心中线上（y=0）");
  assert.equal(right.y, 0);
  // ★ x 口径：markerRadius×2 + COMBAT_SIDE_GAP（中间给 ⚔ 让出横向空间）。
  assert.equal(Math.abs(left.x), markerRadius(cellSize) * 2 + COMBAT_SIDE_GAP, "两侧列横坐标口径");
});

test("combatSlot-four-parties-are-two-two-non-overlapping-and-symmetric", () => {
  const cellSize = 34;
  const slots = [0, 1, 2, 3].map((i) => combatSlot(i, 4, cellSize));
  assert.ok(slots[0].x < 0 && slots[1].x < 0, "前 2 个在左侧");
  assert.ok(slots[2].x > 0 && slots[3].x > 0, "后 2 个在右侧");
  assert.equal(slots[0].x, slots[1].x, "同侧共享同一 x（成列）");
  assert.equal(slots[2].x, slots[3].x);
  const rowSpacing = combatRowSpacing(cellSize);
  assert.ok(rowSpacing > 2 * markerRadius(cellSize), "行距 > 两倍标记半径 ⇒ 同侧相邻圆不重叠");
  // 左侧两点相距一个行距（不重叠），右侧同理。
  assert.equal(Math.abs(slots[1].y - slots[0].y), rowSpacing, "左侧行距 = rowSpacing");
  assert.equal(Math.abs(slots[3].y - slots[2].y), rowSpacing, "右侧行距 = rowSpacing");
  // ★ 用容差：浮点和是 1e-14 量级，写成 === 0 会假红。
  assert.ok(Math.abs(slots[0].y + slots[1].y) < 1e-9, "左侧关于格心纵向对称");
  assert.ok(Math.abs(slots[2].y + slots[3].y) < 1e-9, "右侧关于格心纵向对称");
  assert.equal(slots[0].y, slots[2].y, "左右同序号在同一水平带（左第 1 与右第 1 同高）");
  assert.equal(slots[1].y, slots[3].y, "左右同序号在同一水平带（左第 2 与右第 2 同高）");
});

test("combatSlot-odd-count-puts-the-surplus-party-on-the-left", () => {
  const cellSize = 34;
  // count=3 ⇒ leftCount = ceil(3/2) = 2（左 2 右 1）——"哪方在左在右"就是**按顺序对半切**（无阵营模型）。
  assert.ok(combatSlot(0, 3, cellSize).x < 0, "index 0 左");
  assert.ok(combatSlot(1, 3, cellSize).x < 0, "index 1 左");
  assert.ok(combatSlot(2, 3, cellSize).x > 0, "index 2 右（多出的一个落在右侧列）");
  const left = [0, 1].map((i) => combatSlot(i, 3, cellSize).y);
  assert.ok(Math.abs(left[0] + left[1]) < 1e-9, "左侧 2 点关于格心对称");
  assert.equal(combatSlot(2, 3, cellSize).y, 0, "右侧只剩 1 点 ⇒ 落在格心中线");
  assert.deepEqual(combatSlot(0, 0, cellSize), { x: 0, y: 0 }, "count=0 ⇒ 零偏移");
});

test("combatIcon-sits-at-the-hex-center-and-its-font-is-visible-when-layout-is-on", () => {
  assert.deepEqual(combatIconOffset(), { x: 0, y: 0 }, "⚔ 画在格心（偏移 0）");
  const cellSize = 34;
  assert.ok(
    Math.abs(combatIconFontSize(cellSize) - markerRadius(cellSize) * 1.4) < 1e-9,
    "字号 = markerRadius×1.4（下限 10）"
  );
  assert.ok(combatIconFontSize(200) > combatIconFontSize(cellSize), "字号随格大小线性放大");
  // 门控打开的最小缩放（scale = STACK_MIN_SCREEN_CELL / cellSize）下，⚔ 屏幕字号仍 ≥ 10px。
  const minScale = STACK_MIN_SCREEN_CELL / cellSize;
  assert.ok(
    combatIconFontSize(cellSize) * minScale >= 10,
    "门控打开时 ⚔ 屏幕字号 " + (combatIconFontSize(cellSize) * minScale).toFixed(2) + "px ≥ 10px"
  );
});

test("combatLayoutEnabled-gates-the-special-layout-on-screen-cell-height", () => {
  assert.equal(combatLayoutEnabled(34, 24), true, "屏幕格高 = 阈值 ⇒ 启用（边界闭合）");
  assert.equal(combatLayoutEnabled(34, 23.9), false, "屏幕格高 < 阈值 ⇒ 不启用（退化为原纵向摊开/重叠）");
  assert.equal(combatLayoutEnabled(34, 0), false, "缩得太小 ⇒ 不启用");
  assert.equal(combatLayoutEnabled(0, 100), false, "cellSize 非正 ⇒ 不启用");
});

test("renderer-wires-combat-layout-draw-and-hit-without-inventing-coordinates", () => {
  const src = readWebui("renderer.js");
  assert.ok(src.includes("combatHexes(markers)"), "recomputeWorldPixels 用 combatHexes 判交战格");
  assert.ok(
    src.includes("combatLayoutEnabled(cellSize, screenCell)"),
    "★ 门控：屏幕格高不够时不启用特殊布局（同一 screenCell 口径）"
  );
  assert.ok(src.includes("combatSlot(index, list.length, cellSize)"), "交战格用 combatSlot 定位");
  assert.ok(
    src.includes("stackOffset(index, list.length, cellSize, screenCell)"),
    "★ 非交战格仍走原来的纵向摊开（退回路径没被删）"
  );
  assert.ok(src.includes('ctx.fillText("⚔"'), "drawUnits 在格心画 ⚔");
  assert.ok(src.includes("m.combatRowSpacing"), "pickAt 用同一 rowSpacing 收窄命中（不另造坐标）");
  assert.ok(src.includes("ensureUnitVisible: ensureUnitVisible"), "既有导出未被本次改动破坏");
});

// ── 2026-09-24 编队状态：脱离编队 / 加入编队（GUI 控件 + 接线）─────────────────────
// ★ 用户要的"移动时跟随 / 暂时脱离独立作战"两个状态在**命令面**才可达（unit.CreateUnit 省略 position + Attach/Detach）；
//   GUI 只做**接线**与**服务端理由原样透出**——前端不自己判"是否同格"、不造第二份真相。

test("unit-panel-has-detach-and-attach-formation-controls-that-are-wired", () => {
  const html = readWebui("index.html");
  assert.ok(html.includes('id="unit-detach-formation"'), "index.html 有「脱离编队」按钮");
  assert.ok(html.includes('id="unit-attach-formation"'), "index.html 有「加入编队」按钮");
  const src = readWebui("map-uniteditor.js");
  assert.ok(src.includes('bind("unit-detach-formation"'), "map-uniteditor.js 绑定了「脱离编队」");
  assert.ok(src.includes('bind("unit-attach-formation"'), "map-uniteditor.js 绑定了「加入编队」");
  assert.ok(src.includes('"unit.DetachUnit"'), "脱离发 unit.DetachUnit");
  assert.ok(src.includes('"unit.AttachUnit"'), "加入发 unit.AttachUnit");
  const editor = loadWebui("map-uniteditor.js").SimosMapUnitEditor;
  assert.equal(typeof editor.submitDetachFormation, "function", "submitDetachFormation 已导出");
  assert.equal(typeof editor.submitAttachFormation, "function", "submitAttachFormation 已导出");
});

test("attach-formation-button-disabled-follows-the-selected-units-parent", () => {
  // 父来源 = renderer.parentOf（三态：字符串=有父 / null=已知是根 / undefined=尚未载入）。
  assert.ok(
    readWebui("renderer.js").includes("parentOf: parentOf"),
    "renderer.js 导出了 parentOf"
  );
  const src = readWebui("map-uniteditor.js");
  assert.ok(src.includes("core.active.parentOf("), "map-uniteditor.js 经 core.active.parentOf 取父");
  assert.ok(
    src.includes("attachNode.disabled = !id || parentId === null;"),
    "★「加入编队」disabled 联动：未选中、或**已知是根**时禁用（未载入的 undefined 不误判成根）"
  );
  // 未选中时两个新按钮都随既有写法 disabled。
  assert.ok(
    src.includes('"unit-detach-formation"'),
    "脱离编队按钮纳入「未选中即禁用」的联动"
  );
});

test("formation-buttons-pass-through-the-server-reason-without-a-second-truth", () => {
  const src = readWebui("map-uniteditor.js");
  assert.ok(
    src.includes('"unit.AttachUnit", { id: id, parent: parent }'),
    "加入载荷 {id, parent}（父 = 当前父，非前端另算）"
  );
  assert.ok(src.includes('"unit.DetachUnit", { id: id }'), "脱离载荷 {id}");
  // 服务端拒绝（如"不同格"）时 result.message 原样显示；前端不自己判定同格。
  assert.ok(
    src.includes('result.ok ? "已加入编队 " + id + " → " + parent + "（进入跟随）" : result.message'),
    "★ 加入结局：成功给成功语、失败原样透出服务端理由"
  );
  assert.ok(
    src.includes('result.ok ? "已脱离编队 " + id + "（已钉在当前位置，不再跟随父）" : result.message'),
    "★ 脱离结局：失败原样透出服务端理由"
  );
});

