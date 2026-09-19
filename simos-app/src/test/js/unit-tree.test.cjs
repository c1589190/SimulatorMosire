// unit-tree.test.cjs —— 单位编制倒树的纯函数（M7 T5 / spec §六）：buildTree / isBranchPoint。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui } = require("./helpers/webui-loader.cjs");

const { buildTree, isBranchPoint, flatten } = loadWebui("unitTree.js").SimosUnitTree;

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
