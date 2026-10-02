// region-edit-select.test.cjs —— 用户 2026-10-02 缺陷修复的行为护栏：**区域编辑点选走 regionFocus**。
//
// 生产改动（map.js / renderer.js）三件事：
//   ① region-edit 点格/城市/单位标记 ⇒ selectRegionOfHex ⇒ 顶层区域写 app.setRegionFocus(top)，
//      **不**走区域查看的临时高亮 setHighlightRegions；
//   ② 点到的格不属于任何区域（或 /api/map/hex 读取失败）⇒ 只发 status，**不清**当前编辑目标；
//   ③ renderer 在 region-edit 下光标为 pointer。
//
// ★ 判别力：这些用例驱动的是 `window.SimosMap.selectPickForTest(pick)`（生产新增测试钩子）⇒ 真走
//   workbenchSelect + selectRegionOfHex 的异步取数路径。把生产代码里任一 `mode === "region-edit"`
//   分支删掉/改回旧行为，对应用例当场红（见报告里的变异自证）。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

/** 一个 microtask + 一个宏任务，足够让 selectRegionOfHex 的 await api.mapHex(...) 落定。 */
function flush() {
  return new Promise((resolve) => setImmediate(resolve));
}

/**
 * 假宿主：extraGlobals 会同时挂到 vm sandbox 与 win（webui-loader.cjs 的约定），故 map.js 顶层
 * `var app = window.SimosApp; var api = window.SimosApi;` 拿到的是这里的两份假对象。
 *
 * ★ 2026-10-02 用户报障（点格按 tag 筛选）扩展：
 *   - state 增加 regionTag（缺省 null ⇒ 旧 7 条用例语义不变）；
 *   - api 增加 mapRegion 桩（覆盖"overview 查不到 tag ⇒ fetchRegionCached 兜底"）；
 *   - overviewRegions 通过 loadWebui 返回的 win.SimosMapCore.host 注入（生产就是读这个 host）。
 */
function harness(mode, options) {
  const opts = options || {};
  const calls = [];
  const state = {
    mode: mode,
    regionFocus: opts.regionFocus === undefined ? null : opts.regionFocus,
    regionTag: opts.regionTag === undefined ? null : opts.regionTag,
  };
  const app = {
    getState: () => state,
    setSelection: (selection) => {
      calls.push({ name: "setSelection", args: [selection] });
      state.selection = selection;
    },
    setRegionFocus: (id) => {
      calls.push({ name: "setRegionFocus", args: [id] });
      state.regionFocus = id;
    },
    setRegionTag: (tag) => {
      calls.push({ name: "setRegionTag", args: [tag] });
      state.regionTag = tag;
    },
    setHighlightRegions: (ids, kind) => {
      calls.push({ name: "setHighlightRegions", args: [ids, kind] });
    },
    statusMessage: (node, text, tone) => {
      calls.push({ name: "statusMessage", args: [node, text, tone] });
    },
    byId: (id) => ({ id: id }),
    target: () => ({ branch: "main", revision: 1 }),
    setMode: (next) => {
      calls.push({ name: "setMode", args: [next] });
      state.mode = next;
    },
  };
  const api = {
    mapHex: (q, r, target) => {
      calls.push({ name: "mapHex", args: [q, r, target] });
      if (opts.mapHexError) {
        return Promise.reject(new Error("读取失败-测试"));
      }
      return Promise.resolve({ regions: opts.regions === undefined ? [] : opts.regions });
    },
    mapRegion: (id, target) => {
      calls.push({ name: "mapRegion", args: [id, target] });
      if (opts.mapRegion) {
        return Promise.resolve(opts.mapRegion(id, target));
      }
      return Promise.reject(new Error("mapRegion 未打桩-测试"));
    },
  };
  const unitEditor = {
    selectedUnitId: () => (opts.selectedUnitId === undefined ? null : opts.selectedUnitId),
  };
  const win = loadWebui("map.js", {
    SimosApp: app,
    SimosApi: api,
    SimosMapUnitEditor: unitEditor,
  });
  if (opts.overviewRegions !== undefined) {
    // 生产 map.js 的 regionTagById 读的就是 SimosMapCore.host.overviewRegions。
    win.SimosMapCore.host.overviewRegions = opts.overviewRegions;
  }
  return {
    map: win.SimosMap,
    core: win.SimosMapCore,
    host: win.SimosMapCore.host,
    app: app,
    api: api,
    state: state,
    calls: calls,
    callsOf(name) {
      return calls.filter((entry) => entry.name === name).map((entry) => entry.args);
    },
  };
}

test("region-edit-hex-pick-sets-region-focus-and-not-highlight", async () => {
  const h = harness("region-edit", { regions: ["r-a", "r-b"] });

  h.map.selectPickForTest({ kind: "hex", q: 3, r: 4, inMap: true });
  await flush();

  assert.deepEqual(h.callsOf("setSelection"), [[{ kind: "hex", q: 3, r: 4 }]], "点格仍要落 hex 选中");
  assert.deepEqual(
    h.callsOf("setRegionFocus"),
    [["r-b"]],
    "区域编辑必须选中**顶层区域**（服务端 regions 数组末位 = 后定义者在上）"
  );
  assert.deepEqual(
    h.callsOf("setHighlightRegions"),
    [],
    "区域编辑的落点是 regionFocus，不得再走区域查看的临时高亮"
  );
});

test("region-edit-hex-without-region-keeps-the-current-focus", async () => {
  const h = harness("region-edit", { regions: [], regionFocus: "r-keep" });

  h.map.selectPickForTest({ kind: "hex", q: 9, r: 9, inMap: true });
  await flush();

  assert.equal(h.state.regionFocus, "r-keep", "点到无区域格不得清掉当前编辑目标");
  assert.deepEqual(h.callsOf("setRegionFocus"), [], "无区域时连 setRegionFocus(null) 都不许发");
  assert.deepEqual(h.callsOf("setHighlightRegions"), []);
  const status = h.callsOf("statusMessage");
  assert.equal(status.length, 1, "必须给一条 status 说明为什么目标没变");
  assert.match(status[0][1], /保持当前编辑目标/);
});

test("region-edit-hex-read-failure-keeps-the-current-focus", async () => {
  const h = harness("region-edit", { mapHexError: true, regionFocus: "r-keep" });

  h.map.selectPickForTest({ kind: "hex", q: 1, r: 2, inMap: true });
  await flush();

  assert.equal(h.state.regionFocus, "r-keep", "取数失败不得清掉当前编辑目标");
  assert.deepEqual(h.callsOf("setRegionFocus"), []);
  assert.deepEqual(h.callsOf("setHighlightRegions"), []);
  const status = h.callsOf("statusMessage");
  assert.equal(status.length, 1);
  assert.match(status[0][1], /保持当前编辑目标/);
  assert.equal(status[0][2], "warn", "读取失败用 warn 档（与无区域格的 muted 区分开）");
});

test("region-view-hex-pick-uses-single-highlight-not-region-focus", async () => {
  const h = harness("region", { regions: ["r-a", "r-b"] });

  h.map.selectPickForTest({ kind: "hex", q: 1, r: 1, inMap: true });
  await flush();

  assert.deepEqual(
    h.callsOf("setHighlightRegions"),
    [[["r-b"], "single"]],
    "区域查看仍取顶层区域做 single 高亮（V3 语义不得被编辑模式改写）"
  );
  assert.deepEqual(h.callsOf("setRegionFocus"), [], "查看模式不得写编辑焦点");
});

test("region-edit-city-pick-selects-city-and-still-sets-region-focus", async () => {
  const h = harness("region-edit", { regions: ["r-city-owned"] });

  h.map.selectPickForTest({ kind: "city", id: "c-1", q: 1, r: 1, inMap: true });
  await flush();

  assert.deepEqual(h.callsOf("setSelection"), [[{ kind: "city", id: "c-1" }]]);
  assert.deepEqual(
    h.callsOf("setRegionFocus"),
    [["r-city-owned"]],
    "城市标记也落在某格上：region-edit 下仍要选中该格所属区域"
  );
  const names = h.calls.map((entry) => entry.name);
  assert.ok(
    names.indexOf("setSelection") < names.indexOf("setRegionFocus"),
    "顺序：先落城市选中，再由异步取数补区域焦点"
  );
});

test("region-edit-unit-pick-selects-unit-and-still-sets-region-focus", async () => {
  const h = harness("region-edit", { regions: ["r-unit-owned"] });

  h.map.selectPickForTest({ kind: "unit", id: "u-7", q: 2, r: 2, inMap: true });
  await flush();

  assert.deepEqual(h.callsOf("setSelection"), [[{ kind: "unit", id: "u-7" }]]);
  assert.deepEqual(
    h.callsOf("setRegionFocus"),
    [["r-unit-owned"]],
    "单位标记同理：region-edit 下点单位也要选中所在区域"
  );
  assert.deepEqual(h.callsOf("setHighlightRegions"), []);
});

// ── 2026-10-02 用户报障：点格必须按"当前选中 tag"取该 tag 的定义序末位 ──────────────
//
// 数据夹具：全图末位 = r-admin2（province），Nation 末位 = r-nation ⇒ 两者刻意分叉。
const TAGGED_REGIONS = ["r-admin", "r-nation", "r-admin2"];
const TAGGED_OVERVIEW = [
  { id: "r-admin", meta: { tag: "province" } },
  { id: "r-nation", meta: { tag: "Nation" } },
  { id: "r-admin2", meta: { tag: "province" } },
];

test("region-edit-hex-pick-honours-region-tag-filter", async () => {
  const h = harness("region-edit", {
    regionTag: "Nation",
    regions: TAGGED_REGIONS,
    overviewRegions: TAGGED_OVERVIEW,
  });

  h.map.selectPickForTest({ kind: "hex", q: 3, r: 4, inMap: true });
  await flush();

  assert.deepEqual(
    h.callsOf("setRegionFocus"),
    [["r-nation"]],
    "★ activeTag=Nation ⇒ 取该 tag 的定义序末位 r-nation（不是全图末位 r-admin2）"
  );
  assert.ok(
    !h.callsOf("setRegionFocus").some((args) => args[0] === "r-admin2"),
    "全图末位 r-admin2 不得被选中（用户报障原形）"
  );
  assert.deepEqual(h.callsOf("setHighlightRegions"), [], "region-edit 落点仍是 regionFocus，不走临时高亮");
  // overview 覆盖了全部三个 id ⇒ 不许做多余的详情兜底。
  assert.deepEqual(h.callsOf("mapRegion"), [], "overview 能解析出 tag ⇒ 不拉详情");
});

test("region-view-hex-pick-honours-region-tag-filter", async () => {
  const h = harness("region", {
    regionTag: "Nation",
    regions: TAGGED_REGIONS,
    overviewRegions: TAGGED_OVERVIEW,
  });

  h.map.selectPickForTest({ kind: "hex", q: 1, r: 1, inMap: true });
  await flush();

  assert.deepEqual(
    h.callsOf("setHighlightRegions"),
    [[["r-nation"], "single"]],
    "★ 查看模式也取该 tag 的末位 r-nation（single），不是 r-admin2"
  );
  assert.deepEqual(h.callsOf("setRegionFocus"), [], "查看模式不得写编辑焦点");
});

test("region-edit-tag-without-matching-region-keeps-current-focus", async () => {
  const h = harness("region-edit", {
    regionTag: "Nation",
    regions: ["r-admin"],
    overviewRegions: [{ id: "r-admin", meta: { tag: "province" } }],
    regionFocus: "r-keep",
  });

  h.map.selectPickForTest({ kind: "hex", q: 7, r: 8, inMap: true });
  await flush();

  assert.equal(h.state.regionFocus, "r-keep", "★ 严格无匹配不得清掉当前编辑目标");
  assert.deepEqual(h.callsOf("setRegionFocus"), [], "连 setRegionFocus(null) 都不许发");
  assert.deepEqual(h.callsOf("setHighlightRegions"), [], "严格口径下也不许落到查看高亮");
  const status = h.callsOf("statusMessage");
  assert.equal(status.length, 1, "必须给一条 status 说明为什么选中不变");
  assert.match(status[0][1], /Nation/, "status 要点名缺失的 tag");
  assert.match(status[0][1], /保持不变/, "status 要说明当前选中保持不变");
});

test("region-view-tag-without-matching-region-does-not-clear-highlight", async () => {
  const h = harness("region", {
    regionTag: "Nation",
    regions: ["r-admin"],
    overviewRegions: [{ id: "r-admin", meta: { tag: "province" } }],
  });

  h.map.selectPickForTest({ kind: "hex", q: 7, r: 8, inMap: true });
  await flush();

  // 旧路径（top=null）会 setHighlightRegions([]) 清高亮；新严格口径必须整条 return、不碰选中。
  assert.deepEqual(h.callsOf("setHighlightRegions"), [], "★ 严格无匹配不得清当前高亮");
  assert.deepEqual(h.callsOf("setRegionFocus"), []);
  const status = h.callsOf("statusMessage");
  assert.equal(status.length, 1);
  assert.match(status[0][1], /Nation/);
  assert.match(status[0][1], /保持不变/);
});

test("region-tag-null-still-picks-the-global-last-region", async () => {
  const h = harness("region", {
    regionTag: null,
    regions: TAGGED_REGIONS,
    overviewRegions: TAGGED_OVERVIEW,
  });

  h.map.selectPickForTest({ kind: "hex", q: 2, r: 2, inMap: true });
  await flush();

  assert.deepEqual(
    h.callsOf("setHighlightRegions"),
    [[["r-admin2"], "single"]],
    "★ regionTag=null 时仍取全图定义序末位 r-admin2（V3 旧行为不被破坏）"
  );
  assert.deepEqual(h.callsOf("setRegionFocus"), []);
  assert.deepEqual(h.callsOf("mapRegion"), [], "无 tag 筛选时不做兜底 IO");
});

test("region-tag-falls-back-to-region-detail-when-overview-lacks-the-tag", async () => {
  const h = harness("region", {
    regionTag: "Nation",
    regions: ["r-admin", "r-nation"],
    overviewRegions: [{ id: "r-admin", meta: { tag: "province" } }], // r-nation 缺失 ⇒ 走 fetchRegionCached
    mapRegion: (id) => ({ id: id, meta: { tag: "Nation" } }),
  });

  h.map.selectPickForTest({ kind: "hex", q: 5, r: 5, inMap: true });
  await flush();
  await flush();

  assert.deepEqual(
    h.callsOf("mapRegion"),
    [["r-nation", { branch: "main", revision: 1 }]],
    "overview 查不到 tag ⇒ 逐 id 拉详情兜底（只拉缺的那个）"
  );
  assert.deepEqual(
    h.callsOf("setHighlightRegions"),
    [[["r-nation"], "single"]],
    "兜底拿到 tag=Nation 后仍按该 tag 选中末位"
  );
  assert.deepEqual(h.callsOf("setRegionFocus"), []);
});

test("renderer-shows-pointer-cursor-for-region-edit-mode", () => {
  const source = readWebui("renderer.js");
  const cursorLine = source
    .split("\n")
    .find((line) => line.includes('pick.kind === "unit"') && line.includes('mode === "region"'));
  assert.ok(cursorLine, "renderer 的光标分支必须仍按 mode 区分 pointer/grab");
  assert.ok(
    cursorLine.includes('"region-edit"'),
    "region-edit 必须也在 pointer 档（否则编辑态悬停仍是 grab）"
  );
  assert.ok(cursorLine.includes('"pointer" : "grab"'), "pointer/grab 的落点必须保留");
});
