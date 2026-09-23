// unit-tree.test.cjs —— 单位编制倒树的纯函数（M7 T5 / spec §六）：buildTree / isBranchPoint；
//   2026-09-23 UI 改造追加：armyOptions / subtreeOf / rootIdOf / clampPanelPosition（unitTree.js）
//   与 stackOffset / stackSpacing（hexgeom.js，同格单位纵向摊开）。
//   2026-09-24 修正 1/2 追加：markerGroups（hexgeom.js，标记按军队根分组）与 clampTreePan
//   （unitTree.js，自由视图平移量夹取）+ 复位控件接线 / 无残留 scrollIntoView 的静态断言。
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
const { stackOffset, stackSpacing, markerRadius, markerGroups } = loadWebui("hexgeom.js").SimosHexGeom;

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
