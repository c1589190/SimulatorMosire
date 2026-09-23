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

test("edgeChainEdges-skips-a-non-adjacent-jump-and-keeps-the-anchor", () => {
  // ★ 故意违规：`(1,1) → (1,3)` 距离 2，**不是一条边**。后端 EdgeOperations 只校验两端点在图上、
  //   不校验相邻 ⇒ 少了这条护栏，一个"斜跳"会静默变成世界上的一条边。拖拽态照 GSimulator：**跳过、端点不动**。
  assert.deepEqual(M.edgeChainEdges([{ q: 1, r: 1 }, { q: 1, r: 3 }]), []);
  // 跳之后的**相邻**落点仍从原端点起链（非相邻只是被跳过，不重起）。
  assert.deepEqual(
    M.edgeChainEdges([{ q: 1, r: 1 }, { q: 1, r: 3 }, { q: 1, r: 2 }]),
    ["1_1|1_2"]
  );
  // 全部落在非相邻处 ⇒ 一条都不产出。
  assert.deepEqual(M.edgeChainEdges([{ q: 1, r: 1 }, { q: 1, r: 3 }, { q: 1, r: 4 }]), []);
});

test("edgeChainEdges-same-hex-as-the-anchor-ends-the-path", () => {
  // ★ C3：起点→续点→**同格** ⇒ 结束。"同格" = **当前端点**（GSimulator 的 pathwayStart 会随续点前移）。
  //   (1,2) 出现两次 ⇒ 第二次是同格 ⇒ 结束，后续 (1,3) 不再被连。
  assert.deepEqual(
    M.edgeChainEdges([{ q: 1, r: 1 }, { q: 1, r: 2 }, { q: 1, r: 2 }, { q: 1, r: 3 }]),
    ["1_1|1_2"]
  );
  // 回到**原起点** (1,1) 时它不是当前端点（当前是 (1,2)）⇒ 不算同格：该边已见过（去重），端点前移后再续 (1,0)。
  assert.deepEqual(
    M.edgeChainEdges([{ q: 1, r: 1 }, { q: 1, r: 2 }, { q: 1, r: 1 }, { q: 1, r: 0 }]),
    ["1_1|1_2", "1_0|1_1"]
  );
});

test("edgeChainResult-reports-ending-and-skipped-non-adjacency", () => {
  const result = M.edgeChainResult([{ q: 1, r: 1 }, { q: 1, r: 3 }, { q: 1, r: 2 }, { q: 1, r: 2 }]);
  assert.deepEqual(result.edges, ["1_1|1_2"]);
  assert.equal(result.nonAdjacent, true, "非相邻跳必须记录（宿主据此给可见提示）");
  assert.equal(result.ended, true, "回到端点 ⇒ ended");
  assert.deepEqual(result.path, [
    { q: 1, r: 1 },
    { q: 1, r: 2 },
  ]);
});

test("edgeChainEdges-treats-a-missing-sample-as-no-op", () => {
  // 缺样本（渲染器不会 push，测试直接喂）：跳过、端点不动（既不产出、也不打断）。
  assert.deepEqual(M.edgeChainEdges([{ q: 1, r: 1 }, null, { q: 1, r: 2 }]), ["1_1|1_2"]);
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
  // ★ 源代码级自证：宿主层**只能**通过这些纯函数判断（免得页面上长出第二份判断，
  //   那样变异体能杀掉纯函数却杀不掉页面行为 —— 断言就成了装饰）。
  // ★ M12 第六波：连边/圈选随机化/删边的**宿主写点**已随地图编辑模式搬到 map-mapeditor.js
  //   ⇒ 涉及写载荷的 5 条（edgeModeState(edgeModeValue()) / parseSeedInput( /
  //   randomizeSelectionState( / edgeDeletePlan( / mode: …）与反向 seed 断言改指该文件；
  //   edgeChainResult( / edgeHitAtWorldPoint( 仍是 map.js 的纯函数（后者由 renderer.js 经 core
  //   调用），故这两条留在 map.js 扫描。每条的保护不变。
  const host = readWebui("map-mapeditor.js");
  const pure = readWebui("map.js");
  assert.ok(host.indexOf("edgeModeState(edgeModeValue())") >= 0, "连边提交要走 edgeModeState");
  assert.ok(host.indexOf("parseSeedInput(") >= 0, "seed 要走 parseSeedInput");
  assert.ok(host.indexOf("randomizeSelectionState(") >= 0, "选区要走 randomizeSelectionState");
  assert.ok(pure.indexOf("edgeChainResult(") >= 0, "轨迹要走 edgeChainResult");
  assert.ok(host.indexOf("edgeDeletePlan(") >= 0, "删边要走 edgeDeletePlan");
  assert.ok(pure.indexOf("edgeHitAtWorldPoint(") >= 0, "命中要走 edgeHitAtWorldPoint");
  // 反向：写载荷的 mode **只能**来自 guard / 删边计划，不许硬写。
  assert.ok(/mode:\s*modeState\.mode/.test(host), "连边 mode 来自 edgeModeState");
  assert.ok(/mode:\s*plan\.mode/.test(host), "删边 mode 来自 edgeDeletePlan");
  assert.equal(/seed:\s*0\b/.test(host), false, "不许把 seed 硬写成 0");
});

test("parseEdgeKey-round-trips-and-rejects-malformed-keys", () => {
  assert.deepEqual(M.parseEdgeKey("1_1|1_2"), { a: { q: 1, r: 1 }, b: { q: 1, r: 2 } });
  assert.deepEqual(M.parseEdgeKey("-3_0|1_10"), { a: { q: -3, r: 0 }, b: { q: 1, r: 10 } });
  for (const bad of ["1_1", "1_1|1_2|1_3", "1_1|x_y", "", null, 7]) {
    assert.equal(M.parseEdgeKey(bad), null, "不该接受 " + JSON.stringify(bad));
  }
});

test("edgeHitAtWorldPoint-honours-the-12px-threshold-and-kind", () => {
  // cellSize=1：hex(0,0)→(0,0)，hex(1,0)→(√3,0)≈(1.732,0)，半段中点 ≈(0.866,0)。
  const views = [{ edge: "0_0|1_0", pathways: ["river"] }];
  // 阈值内（距半段约 0.1 < 0.2）⇒ 命中 river。
  const hit = M.edgeHitAtWorldPoint({ x: 0.4, y: 0.1 }, views, 1, 0.2, "river");
  assert.equal(hit.edge, "0_0|1_0");
  assert.equal(hit.kind, "river");
  // ★ 故意违规：阈值外（距 ≈0.5 > 0.2）⇒ 不命中（"点哪儿都删"会让整张图裸奔）。
  assert.equal(M.edgeHitAtWorldPoint({ x: 0.4, y: 0.5 }, views, 1, 0.2, "river"), null);
  // kind 过滤：查 road 而这条边是 river ⇒ 不命中。
  assert.equal(M.edgeHitAtWorldPoint({ x: 0.4, y: 0.1 }, views, 1, 0.2, "road"), null);
  // 空边表 / 缺坐标 ⇒ null。
  assert.equal(M.edgeHitAtWorldPoint({ x: 0, y: 0 }, [], 1, 0.2, "river"), null);
});

test("edgeDeletePlan-replaces-with-the-surviving-set", () => {
  const views = [
    { edge: "1_1|1_2", pathways: ["river"] },
    { edge: "1_2|1_3", pathways: ["river"] },
    { edge: "1_1|1_0", pathways: ["road"] },
  ];
  const plan = M.edgeDeletePlan("river", views, ["1_1|1_2"]);
  assert.equal(plan.ok, true);
  assert.equal(plan.mode, "replace");
  assert.deepEqual(plan.edges, ["1_2|1_3"], "载荷 = 该 kind 的其余边（不含被删的、不含别的 kind）");
  // ★ 删到一条不剩 ⇒ replace 不接受空集 ⇒ 不伪造命令。
  const last = M.edgeDeletePlan("river", views, ["1_1|1_2", "1_2|1_3"]);
  assert.equal(last.ok, false);
  assert.equal(last.reason, "last-edge");
});

test("registered-edge-kinds-default-then-follow-the-server", () => {
  // 未提供（纯前端）⇒ 默认 river/road；canal 未注册 ⇒ 不是合法工具。
  assert.deepEqual(M.registeredEdgeKindList(), ["river", "road"]);
  assert.equal(M.isRegisteredEdgeKind("canal"), false);
  assert.equal(M.mapEditSubtoolOf("canal"), null);
  // 服务端注册 canal ⇒ 它成为候选、归连通性线、能过写门。
  assert.deepEqual(M.setRegisteredEdgeKinds(["river", "road", "canal"]), ["river", "road", "canal"]);
  assert.equal(M.isRegisteredEdgeKind("canal"), true);
  assert.equal(M.mapEditSubtoolOf("canal"), "connectivity");
  assert.deepEqual(M.mapEditSubtoolTools("connectivity"), ["river", "road", "canal"]);
  assert.deepEqual(M.mapEditWriteGate("canal", "map.SetEdge"), {
    ok: true,
    reason: null,
    subtool: "connectivity",
  });
  // 显式空数组 ⇒ 空（fail-closed，不兜默认）。
  assert.deepEqual(M.setRegisteredEdgeKinds([]), []);
  // 复原，避免污染同文件后续用例。
  M.setRegisteredEdgeKinds(["river", "road"]);
});
