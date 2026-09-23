// unit-tree.test.cjs —— 单位编制倒树的纯函数（M7 T5 / spec §六）：buildTree / isBranchPoint；
//   2026-09-23 UI 改造追加：armyOptions / subtreeOf / rootIdOf / clampPanelPosition（unitTree.js）
//   与 stackOffset / stackSpacing（hexgeom.js，同格单位纵向摊开）。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui } = require("./helpers/webui-loader.cjs");

const { buildTree, isBranchPoint, flatten, armyOptions, subtreeOf, rootIdOf, clampPanelPosition } =
  loadWebui("unitTree.js").SimosUnitTree;
const { stackOffset, stackSpacing, markerRadius } = loadWebui("hexgeom.js").SimosHexGeom;

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
