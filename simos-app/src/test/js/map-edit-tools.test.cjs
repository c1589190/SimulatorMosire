// map-edit-tools.test.cjs —— M8 T11 连通性编辑（河流/道路）与圈选随机化的**护栏纯函数**。
//
// 这四个函数是"前端不发命令"这件事的唯一实现处：页面（`map.js` 的宿主层）只调用它们、不重写判断。
// 每条护栏都配了**故意违规**的用例（喂进它必须挡住的那种输入），证明它真的会响——
// 没有这个的护栏等于装饰（纪律：护栏必须自证）。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

const M = loadWebui("map.js").SimosMap;

test("edgeKeyOf-is-canonical-by-q-then-r", () => {
  // 两个方向 ⇒ 同一个键（与 Java EdgeRef 构造期的换序同口径）。
  assert.equal(M.edgeKeyOf({ q: 1, r: 2 }, { q: 1, r: 1 }), "1_1|1_2");
  assert.equal(M.edgeKeyOf({ q: 1, r: 1 }, { q: 1, r: 2 }), "1_1|1_2");
  // ★ 规范序是 (q,r) 而不是字符串序：`1_10` 的字符串序在 `1_2` 之后，规范序在它之前。
  assert.equal(M.edgeKeyOf({ q: 1, r: 10 }, { q: 1, r: 2 }), "1_2|1_10");
});

test("edgeKeyOf-rejects-self-loops-and-incomplete-points", () => {
  assert.equal(M.edgeKeyOf({ q: 3, r: 3 }, { q: 3, r: 3 }), null);
  assert.equal(M.edgeKeyOf({ q: 1, r: 1 }, { r: 2 }), null);
  assert.equal(M.edgeKeyOf(null, { q: 1, r: 2 }), null);
});

test("edgeChainEdges-connects-an-adjacent-run", () => {
  const got = M.edgeChainEdges([
    { q: 1, r: 1 },
    { q: 1, r: 2 },
    { q: 1, r: 3 },
  ]);
  assert.deepEqual(got, ["1_1|1_2", "1_2|1_3"]);
});

test("edgeChainEdges-dedupes-a-repeated-pair", () => {
  // 来回拖：同一条边只出现一次（Java 侧 edges 是 Set，前端也不该重复发）。
  const got = M.edgeChainEdges([
    { q: 1, r: 1 },
    { q: 1, r: 2 },
    { q: 1, r: 1 },
  ]);
  assert.deepEqual(got, ["1_1|1_2"]);
});

test("edgeChainEdges-drops-a-non-adjacent-jump", () => {
  // ★ 故意违规：`(1,1) → (1,3)` 距离 2，**不是一条边**。后端 EdgeOperations 只校验两端点在图上、
  //   不校验相邻 ⇒ 少了这条护栏，一个"斜跳"会静默变成世界上的一条边。
  assert.deepEqual(M.edgeChainEdges([{ q: 1, r: 1 }, { q: 1, r: 3 }]), []);
  // 跳之后的轨迹**从落点重新起链**，不把跳格与后续格连起来。
  assert.deepEqual(
    M.edgeChainEdges([{ q: 1, r: 1 }, { q: 1, r: 3 }, { q: 1, r: 4 }]),
    ["1_3|1_4"]
  );
});

test("edgeChainEdges-breaks-the-chain-on-a-gap", () => {
  // 图外的取样点（渲染器不会 push，这里按"断链"语义钉住）：缺口两侧不相邻 ⇒ 不产出。
  assert.deepEqual(M.edgeChainEdges([{ q: 1, r: 1 }, null, { q: 1, r: 2 }]), []);
  assert.deepEqual(M.edgeChainEdges([]), []);
  assert.deepEqual(M.edgeChainEdges([{ q: 1, r: 1 }]), []);
});

test("edgeModeState-has-no-default", () => {
  // ★★ 故意违规（m1 的杀点）：`#edge-mode` 没显式选时**不许**兜出任何 mode——
  //    "默认 replace" 会让这条红，而它守的正是 Q2 的"必须显式"。
  const unset = M.edgeModeState("");
  assert.equal(unset.ok, false);
  assert.equal(unset.mode, null);
  // 显式选了才 ok，且**原样**返回（不改写大小写、不加词）。
  assert.deepEqual(M.edgeModeState("merge"), { ok: true, mode: "merge" });
  assert.deepEqual(M.edgeModeState("replace"), { ok: true, mode: "replace" });
  // 未知值/非字符串一律拒绝（fail-closed）。
  for (const bad of ["MERGE", "Replace", "replace ", "merge ", "overwrite", null, undefined, 7]) {
    assert.equal(M.edgeModeState(bad).ok, false, "不该接受 " + String(bad));
  }
});

test("parseSeedInput-does-not-fall-back-to-zero", () => {
  // ★★ 故意违规（m2 的杀点其一）：空输入**不是** seed=0——"用户没填"不是一个种子。
  const empty = M.parseSeedInput("");
  assert.equal(empty.ok, false);
  assert.equal(empty.seed, null);
  assert.equal(M.parseSeedInput("   ").ok, false);
  // 合法整数原样解析（含负号与前后空白）。
  assert.deepEqual(M.parseSeedInput("7"), { ok: true, seed: 7 });
  assert.deepEqual(M.parseSeedInput(" 7 "), { ok: true, seed: 7 });
  assert.deepEqual(M.parseSeedInput("-3"), { ok: true, seed: -3 });
  assert.deepEqual(M.parseSeedInput("0"), { ok: true, seed: 0 });
  // 非整数形态：小数、科学计数、十六进制、中文数字一律拒绝（不静默截断）。
  for (const bad of ["7.5", "1e3", "0x10", "七", "abc", "1,000"]) {
    assert.equal(M.parseSeedInput(bad).ok, false, "不该接受 " + JSON.stringify(bad));
  }
  // ★ JS 的精确整数边界（Java long 更大，但前端不许把**不精确**的数发出去当种子）。
  assert.equal(M.parseSeedInput("9007199254740991").ok, true);
  assert.equal(M.parseSeedInput("9007199254740992").ok, false);
});

test("randomizeSelectionState-rejects-an-empty-selection", () => {
  // ★ 故意违规：空选区不该产出载荷（命令面同样拒绝空 hexes，前端不发明例）。
  assert.equal(M.randomizeSelectionState([]).ok, false);
  assert.equal(M.randomizeSelectionState(null).ok, false);
  assert.equal(M.randomizeSelectionState([{ q: 1, r: 1 }]).ok, true);
  assert.deepEqual(M.randomizeSelectionState([{ q: 1, r: 1 }, { q: 1, r: 2 }]).hexes, [
    { q: 1, r: 1 },
    { q: 1, r: 2 },
  ]);
  // 缺坐标的项被剔除；全缺 ⇒ 空 ⇒ 拒绝。
  assert.deepEqual(M.randomizeSelectionState([{ q: 1, r: 1 }, { r: 9 }]).hexes, [{ q: 1, r: 1 }]);
  assert.equal(M.randomizeSelectionState([{ r: 9 }]).ok, false);
});

test("page-层-uses-the-guards-instead-of-re-implementing-them", () => {
  // ★ 源代码级自证：宿主层**只能**通过这四个纯函数判断（免得页面上长出第二份判断，
  //   那样变异体能杀掉纯函数却杀不掉页面行为 —— 断言就成了装饰）。
  const source = readWebui("map.js");
  assert.ok(source.indexOf("edgeModeState(edgeModeValue())") >= 0, "连边提交要走 edgeModeState");
  assert.ok(source.indexOf("parseSeedInput(") >= 0, "seed 要走 parseSeedInput");
  assert.ok(source.indexOf("randomizeSelectionState(") >= 0, "选区要走 randomizeSelectionState");
  assert.ok(source.indexOf("edgeChainEdges(") >= 0, "轨迹要走 edgeChainEdges");
  // 反向：页面**不许**自己写"默认 replace"或"空 seed 当 0"。
  assert.equal(/mode:\s*["']replace["']/.test(source), false, "不许硬写 mode=replace");
  assert.equal(/seed:\s*0\b/.test(source), false, "不许把 seed 硬写成 0");
});
