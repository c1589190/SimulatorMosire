// map-edit-suboptions.test.cjs —— T2「地图编辑下挂二级子选项（地形 / 连通性）」的护栏。
//
// 两路证据：
//   ① 纯函数：编辑线（`mapEditSubtoolState`/`mapEditSubtoolOf`/`mapEditSubtoolDefaultTool`）
//      与写门控（`mapEditWriteAllowed`/`mapEditWriteGate`）——**地形线不许发 SetEdge、连通性线不许发 SetTerrain**（C2）。
//   ② 静态：`index.html` 里的子选项控件与面板分组、模式数仍 5（子选项**不是**新模式）；
//      `map.js` 的每个写点都过 `mapEditWriteGate`，且白名单/写门各只有一处实现。
// ★ 故意违规自证：m1 让两个子面板同时可见（可见性断言红）；m2 让地形线仍放行 `map.SetEdge`（C2 断言红）。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

const M = loadWebui("map.js").SimosMap;

test("module-loads", () => {
  assert.equal(typeof M.mapEditSubtoolState, "function");
  assert.equal(typeof M.mapEditSubtoolTools, "function");
  assert.equal(typeof M.mapEditSubtoolDefaultTool, "function");
  assert.equal(typeof M.mapEditSubtoolOf, "function");
  assert.equal(typeof M.mapEditWriteAllowed, "function");
  assert.equal(typeof M.mapEditWriteGate, "function");
});

test("subtool-ids-and-labels", () => {
  assert.deepEqual(
    M.MAP_EDIT_SUBTOOLS.map((s) => s.id),
    ["terrain", "connectivity"]
  );
  assert.deepEqual(
    M.MAP_EDIT_SUBTOOLS.map((s) => s.label),
    ["地形", "连通性"]
  );
});

test("subtool-state-is-fail-closed", () => {
  // ★ 故意违规：未知/空子选项**不得**兜成「地形」——兜了会把"没选"变成"选了地形"。
  for (const bad of ["", "nope", null, undefined, 7, {}]) {
    const state = M.mapEditSubtoolState(bad);
    assert.equal(state.ok, false, "不该接受 " + JSON.stringify(bad));
    assert.equal(state.id, null);
    assert.deepEqual(state.tools, []);
  }
  const terrain = M.mapEditSubtoolState("terrain");
  assert.equal(terrain.ok, true);
  assert.equal(terrain.id, "terrain");
  assert.equal(terrain.label, "地形");
  assert.deepEqual(terrain.tools, ["terrain", "randomize"]);
  assert.deepEqual(M.mapEditSubtoolState("connectivity").tools, ["river", "road"]);
});

test("subtool-tools-return-a-snapshot-and-default-tool", () => {
  const copy = M.mapEditSubtoolTools("terrain");
  copy.push("river");
  assert.deepEqual(M.mapEditSubtoolTools("terrain"), ["terrain", "randomize"]);
  assert.deepEqual(M.mapEditSubtoolTools("connectivity"), ["river", "road"]);
  assert.deepEqual(M.mapEditSubtoolTools("nope"), []);
  assert.equal(M.mapEditSubtoolDefaultTool("terrain"), "terrain");
  assert.equal(M.mapEditSubtoolDefaultTool("connectivity"), "river");
  assert.equal(M.mapEditSubtoolDefaultTool("nope"), null);
});

test("mapEditSubtoolOf-maps-tools-and-rejects-unknown", () => {
  assert.equal(M.mapEditSubtoolOf("terrain"), "terrain");
  assert.equal(M.mapEditSubtoolOf("randomize"), "terrain");
  assert.equal(M.mapEditSubtoolOf("river"), "connectivity");
  assert.equal(M.mapEditSubtoolOf("road"), "connectivity");
  // ★ 未知工具 ⇒ null（不兜默认）。
  for (const bad of ["", "canal", null, undefined, 3]) {
    assert.equal(M.mapEditSubtoolOf(bad), null, "不该接受 " + JSON.stringify(bad));
  }
});

test("write-allowed-is-per-subtool-and-cross-line-is-denied", () => {
  // ★★ C2 的要害：两条线**互不越线**。
  assert.equal(M.mapEditWriteAllowed("terrain", "map.SetTerrain"), true);
  assert.equal(M.mapEditWriteAllowed("terrain", "map.RandomizeRegion"), true);
  assert.equal(M.mapEditWriteAllowed("terrain", "map.SetEdge"), false);
  assert.equal(M.mapEditWriteAllowed("connectivity", "map.SetEdge"), true);
  assert.equal(M.mapEditWriteAllowed("connectivity", "map.SetTerrain"), false);
  assert.equal(M.mapEditWriteAllowed("connectivity", "map.RandomizeRegion"), false);
  // 未知线 / 空 type ⇒ 拒绝（fail-closed）。
  assert.equal(M.mapEditWriteAllowed("nope", "map.SetEdge"), false);
  assert.equal(M.mapEditWriteAllowed("terrain", ""), false);
  assert.equal(M.mapEditWriteAllowed("terrain", null), false);
});

test("write-gate-resolves-tool-then-subtool", () => {
  assert.deepEqual(M.mapEditWriteGate("terrain", "map.SetTerrain"), {
    ok: true,
    reason: null,
    subtool: "terrain",
  });
  assert.deepEqual(M.mapEditWriteGate("randomize", "map.RandomizeRegion"), {
    ok: true,
    reason: null,
    subtool: "terrain",
  });
  assert.deepEqual(M.mapEditWriteGate("river", "map.SetEdge"), {
    ok: true,
    reason: null,
    subtool: "connectivity",
  });
  // ★ 故意违规（m2 的杀点）：地形线发连通性写 ⇒ 必须被拒，且 reason 指明是"跨线"。
  assert.deepEqual(M.mapEditWriteGate("terrain", "map.SetEdge"), {
    ok: false,
    reason: "wrong-subtool",
    subtool: "terrain",
  });
  assert.equal(M.mapEditWriteGate("road", "map.SetTerrain").ok, false);
  // 未知工具 ⇒ unknown-tool（不是 wrong-subtool）。
  assert.deepEqual(M.mapEditWriteGate("canal", "map.SetEdge"), {
    ok: false,
    reason: "unknown-tool",
    subtool: null,
  });
});

test("panel-visibility-is-mutually-exclusive", () => {
  // ★ 故意违规（m1 的杀点）：地形与连通性面板**恰一个**可见。
  assert.deepEqual(M.mapEditPanelVisibility("terrain"), {
    terrain: true,
    connectivity: false,
    randomize: false,
  });
  assert.deepEqual(M.mapEditPanelVisibility("randomize"), {
    terrain: true,
    connectivity: false,
    randomize: true,
  });
  assert.deepEqual(M.mapEditPanelVisibility("river"), {
    terrain: false,
    connectivity: true,
    randomize: false,
  });
  assert.deepEqual(M.mapEditPanelVisibility("road"), {
    terrain: false,
    connectivity: true,
    randomize: false,
  });
  ["terrain", "randomize", "river", "road"].forEach((tool) => {
    const v = M.mapEditPanelVisibility(tool);
    assert.equal(Number(v.terrain) + Number(v.connectivity), 1, tool + " 的编辑线面板必须恰一个可见");
  });
  // 未知工具 ⇒ 三者全 false（fail-closed，不露任何面板）。
  assert.deepEqual(M.mapEditPanelVisibility("canal"), {
    terrain: false,
    connectivity: false,
    randomize: false,
  });
});

test("index-html-has-suboption-control-and-grouped-panels", () => {
  const html = readWebui("index.html");
  assert.ok(html.includes('id="map-edit-subtools"'), "子选项控件必须在 index.html 里");
  assert.ok(html.includes('name="map-edit-subtool"'), "子选项用 radio group");
  assert.ok(html.includes('value="terrain"') && html.includes('value="connectivity"'), "两个子选项值都在");
  // 面板按子选项分组：地形容器带 terrain 工具组；连通性容器带 kind 组。
  assert.ok(html.includes('id="terrain-tool-select"'), "地形线内有工具组");
  assert.ok(html.includes('name="map-edit-terrain-tool"'), "地形工具 radio 组");
  assert.ok(html.includes('id="edge-kind-select"'), "连通性线内有 kind 组");
  assert.ok(html.includes('name="map-edit-edge-kind"'), "连通性 kind radio 组");
  // ★ randomize 控件并入「地形」容器：它出现在 terrain-tool-controls 与 edge-controls 之间。
  const terrainAt = html.indexOf('id="terrain-tool-controls"');
  const randomizeAt = html.indexOf('id="randomize-controls"');
  const edgeAt = html.indexOf('id="edge-controls"');
  assert.ok(terrainAt >= 0 && randomizeAt > terrainAt && edgeAt > randomizeAt, "randomize 控件必须在「地形」容器内");
});

test("suboptions-are-not-new-modes", () => {
  const html = readWebui("index.html");
  // 模式栏 6 个按钮（T7 起加了「决策」）；子选项**不是**新模式——按 data-mode 计数。
  const modes = html.match(/class="mode[^"]*" data-mode="/g) || [];
  assert.equal(modes.length, 6, "模式栏必须 6 个按钮（子选项不是新模式）");
  assert.equal(/data-mode="(terrain|connectivity)"/.test(html), false, "子选项不得变成新的 data-mode");
});

test("map-js-gates-every-map-write-with-the-matching-type", () => {
  // ★ M12 第六波：地图编辑模式的宿主 UI（含全部 map-edit 写点）已搬到 map-mapeditor.js ⇒
  //   本扫描随宿主层改指该文件（保护不变：每个地图写点都过 mapEditWriteGate 且命令类型逐字匹配）。
  const source = readWebui("map-mapeditor.js");
  // 三个写点各自的门控（命令类型逐字匹配）= C2 的静态半边。
  assert.ok(source.includes('mapEditWriteGate(host.mapEditTool, "map.SetTerrain")'));
  assert.ok(source.includes('mapEditWriteGate(host.mapEditTool, "map.RandomizeRegion")'));
  assert.ok(source.includes('mapEditWriteGate(host.mapEditTool, "map.SetEdge")'));
  // 被门控的写命令确实存在（否则上面的断言可能在守卫一段死代码）。
  assert.ok(source.includes('writeCommand("map.SetTerrain"'));
  assert.ok(source.includes('writeCommand("map.RandomizeRegion"'));
  assert.ok(source.includes('writeCommand("map.SetEdge"'));
});

test("page-delegates-to-the-single-guard-implementation", () => {
  // ★ M12 第六波：写门/白名单这两个**纯函数**留在 map.js（唯一实现处的扫描对象不变）；
  //   而「页面必须委托它们、不得自己重写判断」的调用点已随地图编辑宿主搬到 map-mapeditor.js
  //   ⇒ 那三条 .includes 随宿主层改指该文件（保护不变：页面层必须出现这些调用）。
  const source = readWebui("map.js");
  assert.equal(
    (source.match(/function mapEditWriteAllowed\(/g) || []).length,
    1,
    "写命令白名单只能有一处实现"
  );
  assert.equal((source.match(/function mapEditWriteGate\(/g) || []).length, 1, "写门只能有一处实现");
  const hostLayer = readWebui("map-mapeditor.js");
  assert.ok(hostLayer.includes("setMapEditSubtool("), "子选项切换必须走 setMapEditSubtool");
  assert.ok(hostLayer.includes("mapEditSubtoolOf("), "工具归属必须走 mapEditSubtoolOf");
  assert.ok(hostLayer.includes("mapEditPanelVisibility("), "面板可见性必须走 mapEditPanelVisibility");
});
