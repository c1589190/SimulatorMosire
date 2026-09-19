// M7 T5 树的纯函数自检（证据级，不进 Maven 门禁——本项目没有 JS 测试器）。
// 用法: node tree-check.cjs
// 退出码：0 = 全部断言 PASS；1 = 有 FAIL（变异轮据此观察红点）。
// ★ 冻结夹具：断言里写死期望的父子/深度/分岔点，不拿被测函数自己的输出当期望（否则 m1 自洽假绿）。
"use strict";

const path = require("node:path");

// unitTree.js 是浏览器 IIFE，顶层读 window.SimosApp / window.SimosApi；在 Node 里给个空壳即可
// （buildTree / isBranchPoint 是纯函数，不碰 app/api/DOM）。
global.window = {};
// 可选 argv[2] = 被测 unitTree.js 路径（变异轮观察用）；缺省 = 工作树源文件。
const UNIT_TREE = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.resolve(__dirname, "../../../../simos-app/src/main/resources/webui/unitTree.js");
console.log("TREE-CHECK target: " + UNIT_TREE);
require(UNIT_TREE);
const { buildTree, isBranchPoint, flatten } = global.window.SimosUnitTree;

// ── 冻结夹具（覆盖：三层链 / 分岔点 / 单子非分岔 / 多根 / 缺失 parent / 悬空 parent）──
const UNITS = [
  { id: "A", name: "甲部", parent: null, position: { q: 1, r: 1 } },
  { id: "B", name: "乙部", parent: "A" },
  { id: "C", name: "丙队", parent: "B" },
  { id: "D", name: "丁队", parent: "B" },
  { id: "E", name: "戊队", parent: "A" },
  { id: "F", name: "己组", parent: "C" },
  { id: "G", name: null, parent: undefined }, // 缺失 parent ⇒ 根
  { id: "H", name: "辛队", parent: "ghost" }, // 悬空 parent ⇒ 本任务裁定：当根
];

// 冻结期望（手写，不来自 buildTree）。
const EXPECT_DEPTH = { A: 0, B: 1, C: 2, D: 2, E: 1, F: 3, G: 0, H: 0 };
const EXPECT_PARENT = { A: null, B: "A", C: "B", D: "B", E: "A", F: "C", G: null, H: "ghost" };
const EXPECT_CHILDREN = {
  A: ["B", "E"],
  B: ["C", "D"],
  C: ["F"],
  D: [],
  E: [],
  F: [],
  G: [],
  H: [],
};
const EXPECT_BRANCH = { A: true, B: true, C: false, D: false, E: false, F: false, G: false, H: false };
const EXPECT_DESCENDANTS = { A: 5, B: 3, C: 1, D: 0, E: 0, F: 0, G: 0, H: 0 };
const EXPECT_ROOTS = ["A", "G", "H"];

const failures = [];

function check(name, ok, detail) {
  console.log("ASSERT " + name + ": " + (ok ? "PASS" : "FAIL") + (detail ? " " + detail : ""));
  if (!ok) {
    failures.push(name);
  }
}

function byId(nodes) {
  const map = new Map();
  flatten(nodes).forEach((node) => map.set(node.id, node));
  return map;
}

function main() {
  const roots = buildTree(UNITS);
  const nodes = byId(roots);

  // ① 三层链 A→B→C→F 的父-child 关系与深度**逐节点相等**（R7 核心）
  const parentChildOk = Object.keys(EXPECT_DEPTH).every((id) => {
    const node = nodes.get(id);
    if (!node) {
      return false;
    }
    const children = node.children.map((c) => c.id).sort();
    const expected = EXPECT_CHILDREN[id].slice().sort();
    return (
      node.depth === EXPECT_DEPTH[id] &&
      String(node.parent) === String(EXPECT_PARENT[id]) &&
      JSON.stringify(children) === JSON.stringify(expected)
    );
  });
  check(
    "depth-parent-child-per-node",
    parentChildOk,
    JSON.stringify(
      Array.from(nodes.values()).map((n) => ({
        id: n.id,
        depth: n.depth,
        parent: n.parent,
        children: n.children.map((c) => c.id),
      }))
    )
  );

  // ② 分岔点识别：子数 ≥ 2 为真，< 2（含单子 C、叶 D/E/F）为假
  const branchOk = Object.keys(EXPECT_BRANCH).every(
    (id) => nodes.get(id).branch === EXPECT_BRANCH[id] && isBranchPoint(nodes.get(id)) === EXPECT_BRANCH[id]
  );
  check(
    "branch-point-recognition",
    branchOk,
    JSON.stringify(
      Array.from(nodes.values()).map((n) => ({ id: n.id, children: n.children.length, branch: n.branch }))
    )
  );

  // 判定器边界自证（形态 1）：1 子 ⇒ 非分岔；2 子 ⇒ 分岔；0 子 ⇒ 非分岔
  check(
    "branch-teeth-single-vs-double",
    isBranchPoint(nodes.get("C")) === false &&
      isBranchPoint(nodes.get("B")) === true &&
      isBranchPoint(nodes.get("D")) === false,
    "C(1子)=false B(2子)=true D(0子)=false"
  );

  // ③ 多根并列
  check(
    "multi-root-forest",
    roots.length === EXPECT_ROOTS.length &&
      JSON.stringify(roots.map((r) => r.id)) === JSON.stringify(EXPECT_ROOTS),
    "roots=" + JSON.stringify(roots.map((r) => r.id))
  );

  // ④ parent 缺失/为 null 的单位成为根
  check(
    "missing-or-null-parent-is-root",
    roots.some((r) => r.id === "G") && nodes.get("G").parent === null,
    "G.parent=" + JSON.stringify(nodes.get("G").parent)
  );

  // ⑤ 悬空 parent（指向不存在 id）⇒ 当根，且原 parent 值原样保留
  check(
    "dangling-parent-becomes-root",
    roots.some((r) => r.id === "H") && nodes.get("H").parent === "ghost" && nodes.get("H").depth === 0,
    "H.parent=" + JSON.stringify(nodes.get("H").parent) + " H.depth=" + nodes.get("H").depth
  );

  // ⑥ 后代数（分支详情行数的来源）
  const descOk = Object.keys(EXPECT_DESCENDANTS).every(
    (id) => nodes.get(id).descendantCount === EXPECT_DESCENDANTS[id]
  );
  check(
    "descendant-count",
    descOk,
    JSON.stringify(
      Array.from(nodes.values()).map((n) => ({ id: n.id, descendants: n.descendantCount }))
    )
  );

  // ⑦ 空输入 ⇒ 空森林（不炸）
  check("empty-input", Array.isArray(buildTree([])) && buildTree([]).length === 0, "buildTree([])=[]");
}

main();
console.log("TREE-CHECK RESULT: " + (failures.length === 0 ? "PASS" : "FAIL " + failures.join(",")));
process.exit(failures.length === 0 ? 0 : 1);
