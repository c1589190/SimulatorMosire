// events.test.cjs —— 2026-10-02 用户需求：地图 ⚔ 事件浮层（该 hex 当前 tick 战斗详情 + 当前 tick 全部）。
//
// 生产新增：events.js（事件浮层 + provider 注册表；战斗只是第一个 provider）、hexgeom.js 的
// combatIconAt（⚔ 命中几何）、renderer.js 的 pickAt ⚔ 优先命中 + 指针反馈、map.js 的
// workbenchSelect combat 分支 + applyLayerPrefs 的图层联动、index.html/styles.css 的面板与标签。
//
// ★ 判别力：纯函数直接喂冻结夹具；面板用例用 loadWebui 灌入最小假 DOM（真 events.js 逻辑，只替 IO/DOM），
//   打开方式 / 过滤 / 详情展开 / 图层联动 / 取数失败逐条断言；静态用例钉接线与加载顺序。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

// 纯函数命名空间：events.js 顶层对 app/api 缺席是宽容的（顶层不调用），可直接加载。
const E = loadWebui("events.js").SimosEvents;
const G = loadWebui("hexgeom.js").SimosHexGeom;

// ── 冻结夹具 ────────────────────────────────────────────────────────────

const RAW_COMBAT = Object.freeze({
  id: "c-9",
  kind: "battle",
  tick: 12,
  hex: Object.freeze({ q: 3, r: 4 }),
  text: "双方在丘陵遭遇",
  participants: Object.freeze(["u-1", "u-2"]),
});

const RAW_COMBATS = Object.freeze([
  Object.freeze({ id: "c-1", tick: 12, hex: Object.freeze({ q: 3, r: 4 }), text: "甲", kind: "battle" }),
  Object.freeze({ id: "c-2", tick: 12, hex: Object.freeze({ q: 3, r: 4 }), text: "乙", kind: "battle" }),
  Object.freeze({ id: "c-3", tick: 12, hex: Object.freeze({ q: 9, r: 9 }), text: "丙", kind: "battle" }),
]);

/** renderCombatDetail 的冻结记录：1 stage / 2 outcome / 1 selected / 1 条 losses。 */
const DETAIL_RAW = Object.freeze({
  id: "c-77",
  kind: "battle",
  tick: 12,
  hex: Object.freeze({ q: 3, r: 4 }),
  participants: Object.freeze(["u-1", "u-2"]),
  text: "战斗文本",
  stages: Object.freeze([
    Object.freeze({
      name: "第一波",
      resolved: true,
      participants: Object.freeze(["u-1", "u-2"]),
      text: "stage 文本",
      rollSeed: 42,
      selectedOutcomeId: "o-2",
      outcomes: Object.freeze([
        Object.freeze({ id: "o-1", label: "击退", weight: 3 }),
        Object.freeze({ id: "o-2", label: "僵持", weight: 7 }),
      ]),
    }),
  ]),
  losses: Object.freeze([
    Object.freeze({
      stageId: "s-1",
      unit: "u-1",
      manpower: Object.freeze([{ type: "步兵", amount: -100 }]),
      equipment: Object.freeze([{ type: "刀", amount: -2 }]),
    }),
  ]),
});

// ── 最小假 DOM（只替宿主，不替被测逻辑）────────────────────────────────────

function makeNode(tag) {
  const node = {
    tag: tag,
    attrs: {},
    children: [],
    textContent: "",
    hidden: false,
    listeners: [],
    appendChild(child) {
      this.children.push(child);
      return child;
    },
    removeChild(child) {
      const index = this.children.indexOf(child);
      if (index >= 0) this.children.splice(index, 1);
      return child;
    },
    addEventListener(type, fn) {
      this.listeners.push({ type: type, fn: fn });
    },
  };
  Object.defineProperty(node, "firstChild", {
    get() {
      return this.children.length ? this.children[0] : null;
    },
  });
  return node;
}

function fakeApp() {
  const nodes = {};
  const calls = { clear: 0, target: 0, onStateChange: [] };
  const state = { revision: 1 };
  const app = {
    el(tag, attrs, children) {
      const node = makeNode(tag);
      if (attrs) {
        Object.keys(attrs).forEach((key) => {
          if (key === "text") {
            node.textContent = attrs[key] === null || attrs[key] === undefined ? "" : String(attrs[key]);
          } else if (key === "onclick") {
            node.onclick = attrs[key];
          } else {
            node.attrs[key] = attrs[key];
          }
        });
      }
      (children || []).forEach((child) => node.appendChild(child));
      return node;
    },
    clear(node) {
      calls.clear += 1;
      if (node) node.children = [];
      return node;
    },
    byId(id) {
      if (!nodes[id]) nodes[id] = makeNode("div");
      return nodes[id];
    },
    text(value) {
      return value === null || value === undefined ? "—" : String(value);
    },
    target() {
      calls.target += 1;
      return { branch: "main", revision: state.revision };
    },
    onStateChange(fn) {
      calls.onStateChange.push(fn);
    },
  };
  return { app: app, nodes: nodes, calls: calls, state: state };
}

function panelHarness(options) {
  const opts = options || {};
  const dom = fakeApp();
  const apiCalls = [];
  const api = {
    cachedArmyCombats(target) {
      apiCalls.push(target);
      if (opts.combatsReject) return Promise.reject(new Error(opts.combatsReject));
      return Promise.resolve({ combats: opts.combats === undefined ? RAW_COMBATS : opts.combats });
    },
  };
  const win = loadWebui("events.js", { SimosApp: dom.app, SimosApi: api });
  const events = win.SimosEvents;
  events.init();
  return {
    E: events,
    app: dom.app,
    nodes: dom.nodes,
    calls: dom.calls,
    state: dom.state,
    apiCalls: apiCalls,
    mount: dom.app.byId("event-panel-mount"),
  };
}

function flush() {
  return new Promise((resolve) => setImmediate(resolve));
}

function findAll(node, predicate) {
  const out = [];
  (function walk(current) {
    out.push(current);
    (current.children || []).forEach(walk);
  })(node);
  return out.filter(predicate);
}

function byClass(node, className) {
  return findAll(node, (current) => {
    const value = current.attrs ? current.attrs.class : "";
    return String(value || "")
      .split(/\s+/)
      .indexOf(className) >= 0;
  });
}

// ── 纯函数 ──────────────────────────────────────────────────────────────

test("normalize-combat-event-keeps-frozen-fields-and-raw-identity", () => {
  const event = E.normalizeCombatEvent(RAW_COMBAT);
  assert.equal(event.type, "combat");
  assert.equal(event.id, "c-9");
  assert.equal(event.tick, 12);
  assert.deepEqual(event.hex, { q: 3, r: 4 });
  assert.equal(event.title, "战斗 c-9");
  assert.equal(event.summary, "双方在丘陵遭遇");
  assert.equal(event.raw, RAW_COMBAT, "raw 必须原样引用（renderDetail 依赖记录的全部原字段）");
  assert.notEqual(event.hex, RAW_COMBAT.hex, "hex 是坐标快照，不把 frozen 原对象暴露成可变 hex");
});

test("normalize-combat-event-rejects-missing-id-or-incomplete-hex", () => {
  assert.equal(E.normalizeCombatEvent(null), null);
  assert.equal(E.normalizeCombatEvent(undefined), null);
  assert.equal(E.normalizeCombatEvent({ hex: { q: 1, r: 2 } }), null);
  assert.equal(E.normalizeCombatEvent({ id: null, hex: { q: 1, r: 2 } }), null);
  assert.equal(E.normalizeCombatEvent({ id: "c-1", hex: null }), null);
  assert.equal(E.normalizeCombatEvent({ id: "c-1", hex: { q: 1 } }), null);
  assert.equal(E.normalizeCombatEvent({ id: "c-1", hex: { r: 2 } }), null);
  assert.equal(E.normalizeCombatEvent({ id: "c-1", hex: { q: null, r: 2 } }), null);
});

test("filter-events-for-hex-copies-all-and-matches-string-or-number-coords", () => {
  const source = Object.freeze([
    Object.freeze({ id: "a", hex: Object.freeze({ q: 1, r: 2 }) }),
    Object.freeze({ id: "b", hex: Object.freeze({ q: 3, r: 4 }) }),
    Object.freeze({ id: "c", hex: Object.freeze({ q: 1, r: 2 }) }),
  ]);
  const allNull = E.filterEventsForHex(source, null);
  const allUndefined = E.filterEventsForHex(source, undefined);
  assert.notEqual(allNull, source, "hex=null ⇒ 返回拷贝（引用必须不同）");
  assert.notEqual(allUndefined, source, "hex=undefined ⇒ 返回拷贝（引用必须不同）");
  assert.notEqual(allNull, allUndefined);
  assert.deepEqual(
    allNull.map((event) => event.id),
    ["a", "b", "c"]
  );
  assert.equal(allNull[0], source[0], "全量拷贝只拷数组壳，不复制事件对象");
  assert.deepEqual(
    E.filterEventsForHex(source, { q: "1", r: "2" }).map((event) => event.id),
    ["a", "c"],
    "字符串坐标也要按数值相等匹配"
  );
  assert.deepEqual(
    E.filterEventsForHex(source, { q: 1, r: 2 }).map((event) => event.id),
    ["a", "c"]
  );
  assert.deepEqual(E.filterEventsForHex(source, { q: 9, r: 9 }), []);
  assert.deepEqual(E.filterEventsForHex([], { q: 1, r: 2 }), []);
});

test("format-delta-signs-types-and-empty-fallback", () => {
  assert.equal(E.formatDelta([]), "—");
  assert.equal(E.formatDelta(null), "—");
  assert.equal(
    E.formatDelta([
      { type: "步兵", amount: -100 },
      { type: "骑兵", amount: 20 },
    ]),
    "步兵-100；骑兵+20"
  );
  assert.doesNotThrow(() =>
    E.formatDelta([{ amount: NaN, type: "未知" }, { amount: Infinity, type: "未知2" }, { amount: 5 }])
  );
  assert.equal(E.formatDelta([{ type: null, amount: 0 }]), "—+0", "type 缺失降级为 —，不抛");
});

test("combat-icon-at-hits-cell-center-and-misses-far-points", () => {
  const cellSize = 34;
  const table = { "3_-2": { combatId: "c-1" } };
  const center = G.hexToPixel(3, -2, cellSize);
  const offset = G.combatIconOffset();
  const point = { x: center.x + offset.x, y: center.y + offset.y };
  assert.deepEqual(G.combatIconAt(point, table, cellSize), { q: 3, r: -2, key: "3_-2" });
  assert.equal(G.combatIconAt({ x: point.x + 500, y: point.y }, table, cellSize), null);
  assert.equal(G.combatIconAt({ x: point.x, y: point.y + 500 }, table, cellSize), null);
  assert.ok(G.combatIconAt(point, table, cellSize, 1), "显式半径 1 内仍命中");
  assert.equal(G.combatIconAt({ x: point.x + 2, y: point.y }, table, cellSize, 1), null, "显式半径 1 外不命中");
});

test("combat-icon-at-skips-unparsable-keys-and-tolerates-missing-input", () => {
  const cellSize = 34;
  const center = G.hexToPixel(3, -2, cellSize);
  const offset = G.combatIconOffset();
  const point = { x: center.x + offset.x, y: center.y + offset.y };
  const table = { x_y: {}, "": {}, "3_-2": { combatId: "c-1" }, oops_1: {} };
  assert.deepEqual(G.combatIconAt(point, table, cellSize), { q: 3, r: -2, key: "3_-2" });
  assert.equal(G.combatIconAt(point, { x_y: {}, "": {}, oops_1: {} }, cellSize), null);
  assert.equal(G.combatIconAt(null, table, cellSize), null);
  assert.equal(G.combatIconAt(point, null, cellSize), null);
  assert.equal(G.combatIconAt(undefined, undefined, cellSize), null);
  assert.equal(G.combatIconAt({ x: 100, y: 100 }, { "3_-2": {} }, cellSize), null);
});

// ── 面板行为 ────────────────────────────────────────────────────────────

test("open-for-hex-shows-only-that-cell-and-expands-its-details", async () => {
  const h = panelHarness();
  await h.E.openForHex(3, 4);
  assert.equal(h.E.isOpen(), true);
  assert.equal(h.nodes["event-panel"].hidden, false);
  assert.equal(h.nodes["event-panel-title"].textContent, "事件 · (3,4)");
  assert.equal(h.calls.onStateChange.length, 1, "init 必须注册 onStateChange（状态刷新时重取）");
  assert.deepEqual(h.apiCalls, [{ branch: "main", revision: 1 }], "load 必须收到 app.target()");
  const toolbar = byClass(h.mount, "event-toolbar")[0];
  assert.ok(toolbar, "mount 里必须有 event-toolbar");
  assert.equal(byClass(toolbar, "event-count")[0].textContent, "2 条事件");
  const cards = byClass(h.mount, "event-card");
  assert.deepEqual(
    cards.map((card) => card.attrs["data-event-id"]),
    ["c-1", "c-2"]
  );
  assert.deepEqual(
    cards.map((card) => card.attrs["data-event-type"]),
    ["combat", "combat"]
  );
  for (const card of cards) {
    assert.equal(byClass(card, "event-card-detail")[0].hidden, false, "范围视角详情默认展开");
  }
  const toggles = findAll(h.mount, (node) => node.attrs.id === "event-panel-scope-toggle");
  assert.equal(toggles.length, 1, "范围视角必须提供「查看当前 tick 全部」按钮");
  assert.equal(toggles[0].textContent, "查看当前 tick 全部");
});

test("open-all-shows-current-tick-cards-collapsed-without-scope-toggle", async () => {
  const h = panelHarness();
  await h.E.openAll();
  assert.equal(h.nodes["event-panel-title"].textContent, "事件 · 当前 tick");
  assert.equal(byClass(h.mount, "event-toolbar").length, 1);
  assert.equal(byClass(h.mount, "event-count")[0].textContent, "3 条事件");
  assert.equal(findAll(h.mount, (node) => node.attrs.id === "event-panel-scope-toggle").length, 0);
  const cards = byClass(h.mount, "event-card");
  assert.deepEqual(
    cards.map((card) => card.attrs["data-event-id"]),
    ["c-1", "c-2", "c-3"]
  );
  for (const card of cards) {
    assert.equal(byClass(card, "event-card-detail")[0].hidden, true, "全量列表默认收起");
  }
});

test("open-for-hex-without-match-shows-empty-state-and-not-other-cards", async () => {
  const h = panelHarness();
  await h.E.openForHex(8, 8);
  assert.equal(byClass(h.mount, "event-card").length, 0);
  assert.equal(byClass(h.mount, "event-count")[0].textContent, "0 条事件");
  const empty = byClass(h.mount, "event-empty")[0];
  assert.ok(empty, "无匹配必须有空态文案");
  assert.equal(empty.textContent, "该格无战斗事件");
});

test("close-and-combats-layer-off-both-hide-the-panel", async () => {
  const h = panelHarness();
  await h.E.openAll();
  h.E.onLayerChange({ combats: true });
  assert.equal(h.E.isOpen(), true, "combats 开不关面板");
  h.E.onLayerChange({ combats: false });
  assert.equal(h.E.isOpen(), false, "combats 关必须联动关闭");
  await h.E.openAll();
  h.E.onLayerChange({ routes: false });
  assert.equal(h.E.isOpen(), true, "别的图层变化不关");
  h.E.close();
  assert.equal(h.E.isOpen(), false, "close 必须收起");
  await h.E.openAll();
  const closeButton = h.nodes["event-panel-close"];
  const click = closeButton.listeners.filter((listener) => listener.type === "click")[0];
  assert.ok(click, "init 必须给 #event-panel-close 绑 click");
  click.fn();
  assert.equal(h.E.isOpen(), false, "关闭按钮必须真接线");
});

test("provider-load-rejection-renders-error-without-throwing", async () => {
  const h = panelHarness({ combatsReject: "后端 500" });
  await assert.doesNotReject(() => h.E.openAll());
  const error = byClass(h.mount, "event-error")[0];
  assert.ok(error, "取数失败必须可见（不许静默空态）");
  assert.match(error.textContent, /读取事件失败/);
  assert.match(error.textContent, /后端 500/);
  assert.equal(byClass(h.mount, "event-card").length, 0);
});

test("register-provider-feeds-a-non-combat-event-into-the-panel", async () => {
  const h = panelHarness({ combats: [] });
  const customRaw = Object.freeze([
    Object.freeze({ id: "f-1", at: Object.freeze({ q: 5, r: 6 }), note: "粮仓见底" }),
  ]);
  h.E.registerProvider({
    id: "famine",
    icon: "🌾",
    load: () => Promise.resolve(customRaw),
    normalize: (raw) =>
      raw
        ? {
            type: "famine",
            id: String(raw.id),
            tick: 9,
            hex: { q: raw.at.q, r: raw.at.r },
            title: "饥荒 " + raw.id,
            summary: raw.note,
            raw: raw,
          }
        : null,
    renderDetail: (event, container) => {
      container.appendChild(h.app.el("p", { class: "event-custom-detail", text: "自定义详情 " + event.id }));
    },
  });
  await h.E.openForHex(5, 6);
  const cards = byClass(h.mount, "event-card");
  assert.equal(cards.length, 1);
  assert.equal(cards[0].attrs["data-event-type"], "famine");
  assert.equal(byClass(cards[0], "event-card-icon")[0].textContent, "🌾");
  assert.equal(byClass(cards[0], "event-card-detail")[0].hidden, false);
  assert.equal(byClass(cards[0], "event-custom-detail")[0].textContent, "自定义详情 f-1");
});

test("on-state-reloads-only-when-open-and-target-revision-changed", async () => {
  const h = panelHarness();
  await h.E.openAll();
  assert.equal(h.apiCalls.length, 1);
  h.E.onState();
  assert.equal(h.apiCalls.length, 1, "target 未变不得重复取数");
  h.state.revision = 2;
  h.E.onState();
  await flush();
  assert.equal(h.apiCalls.length, 2, "target 变了且面板开着 ⇒ 重取");
  assert.equal(h.nodes["event-panel-title"].textContent, "事件 · 当前 tick");
  h.E.close();
  h.state.revision = 3;
  h.E.onState();
  assert.equal(h.apiCalls.length, 2, "面板关着时状态变化不取数");
});

// ── 战斗详情渲染 ────────────────────────────────────────────────────────

test("render-combat-detail-builds-kv-selected-outcome-and-loss-deltas", () => {
  const h = panelHarness({ combats: [] });
  const container = h.app.el("div", { class: "event-card-detail" });
  h.E.renderCombatDetail({ type: "combat", id: "c-77", raw: DETAIL_RAW }, container);
  const kv = byClass(container, "event-kv")[0];
  assert.ok(kv, "必须有 event-kv");
  const pairs = {};
  for (let i = 0; i < kv.children.length; i += 2) {
    pairs[String(kv.children[i].textContent)] = String(kv.children[i + 1].textContent);
  }
  assert.deepEqual(pairs, {
    id: "c-77",
    kind: "battle",
    tick: "12",
    hex: "(3,4)",
    participants: "u-1、u-2",
  });
  assert.equal(byClass(container, "event-text")[0].textContent, "战斗文本");

  const outcomeTable = byClass(container, "event-outcomes")[0];
  assert.ok(outcomeTable, "必须有 outcomes 表");
  const outcomeRows = findAll(outcomeTable, (node) => node.tag === "tbody")[0].children;
  assert.equal(outcomeRows.length, 2);
  const selectedRow = outcomeRows.filter((row) => String(row.attrs.class).indexOf("event-selected") >= 0)[0];
  assert.ok(selectedRow, "选中 outcome 行必须带 event-selected");
  assert.deepEqual(
    selectedRow.children.map((cell) => cell.textContent),
    ["✓", "僵持", "7"],
    "选中行 = selectedOutcomeId 指向的 outcome"
  );
  const unselected = outcomeRows.filter((row) => String(row.attrs.class).indexOf("event-selected") < 0)[0];
  assert.deepEqual(
    unselected.children.map((cell) => cell.textContent),
    ["", "击退", "3"]
  );

  const lossRows = findAll(byClass(container, "event-losses")[0], (node) => node.tag === "tbody")[0].children;
  assert.equal(lossRows.length, 1);
  const lossCells = lossRows[0].children.map((cell) => cell.textContent);
  assert.deepEqual(lossCells, [
    "s-1",
    "u-1",
    h.E.formatDelta(DETAIL_RAW.losses[0].manpower),
    h.E.formatDelta(DETAIL_RAW.losses[0].equipment),
  ]);
  assert.equal(lossCells[2], "步兵-100");
  assert.equal(lossCells[3], "刀-2");
});

test("render-combat-detail-uses-app-el-only-and-handles-empty-losses", () => {
  const h = panelHarness({ combats: [] });
  const container = h.app.el("div", {});
  const raw = {
    id: "c-88",
    stages: [
      {
        name: "第二波",
        resolved: false,
        selectedOutcome: "o-2",
        outcomes: [{ id: "o-2", label: "退却", weight: 5 }],
      },
    ],
  };
  h.E.renderCombatDetail({ type: "combat", id: "c-88", raw: raw }, container);
  assert.ok(byClass(container, "event-selected")[0], "selectedOutcome（非 Id 字段）也要标选中");
  const lossRows = findAll(byClass(container, "event-losses")[0], (node) => node.tag === "tbody")[0].children;
  assert.equal(lossRows.length, 1);
  assert.equal(lossRows[0].children[0].attrs.colspan, "4");
  assert.equal(lossRows[0].children[0].textContent, "—", "无 losses ⇒ 占位 —");
  assert.equal(byClass(container, "event-kv")[0].children[9].textContent, "—", "participants 缺失 ⇒ —");

  const source = readWebui("events.js");
  assert.ok(!source.includes(".innerHTML"), "events.js 不得写 .innerHTML（只用 app.el / textContent）");
  assert.ok(!/\binnerHTML\b/.test(source), "连裸 innerHTML 也不得出现");
});

// ── renderer / map / 页面静态接线 ────────────────────────────────────────

test("renderer-pick-at-prioritises-combat-icon-before-marker-loop", () => {
  const source = readWebui("renderer.js");
  const pickStart = source.indexOf("function pickAt(point)");
  assert.ok(pickStart >= 0, "renderer 必须有 pickAt");
  const hitAt = source.indexOf("combatIconAt(world, realCombatHexes", pickStart);
  const markerLoopAt = source.indexOf("for (var i = markers.length - 1; i >= 0; i--)", pickStart);
  assert.ok(hitAt > pickStart, "pickAt 必须调用 combatIconAt(world, realCombatHexes…)");
  assert.ok(markerLoopAt > pickStart, "marker 循环仍在 pickAt 内");
  assert.ok(hitAt < markerLoopAt, "★ ⚔ 命中必须排在 marker 循环之前（否则被单位标记抢走）");
  assert.ok(source.includes('kind: "combat"'), 'pick 结果必须带 kind: "combat"');
});

test("renderer-combat-pick-is-gated-by-combats-layer-and-layout", () => {
  const source = readWebui("renderer.js");
  const pickStart = source.indexOf("function pickAt(point)");
  const markerLoopAt = source.indexOf("for (var i = markers.length - 1; i >= 0; i--)", pickStart);
  const pickBody = source.slice(pickStart, markerLoopAt);
  assert.ok(
    pickBody.includes("layerState.combats && combatLayoutEnabled(cellSize, cellSize * view.scale)"),
    "★ 门控必须与 drawUnits 同一判据（图层开 + 交战布局启用）"
  );
  const gateAt = pickBody.indexOf("layerState.combats && combatLayoutEnabled");
  const callAt = pickBody.indexOf("combatIconAt(world, realCombatHexes");
  assert.ok(gateAt >= 0 && callAt > gateAt, "先判门控，再判 ⚔ 命中");
  assert.ok(source.includes('pick.kind === "combat" ? "战斗"'), "指针 title 对 combat 显示「战斗」");
});

test("map-apply-layer-prefs-links-the-combats-layer-to-the-events-panel", () => {
  const source = readWebui("map.js");
  const start = source.indexOf("function applyLayerPrefs");
  const end = source.indexOf("function wireLayerDrawer", start);
  assert.ok(start >= 0 && end > start, "map.js 必须有 applyLayerPrefs 与后续的 wireLayerDrawer");
  const body = source.slice(start, end);
  assert.ok(body.includes("window.SimosEvents"), "applyLayerPrefs 必须把图层变化转给事件面板");
  assert.ok(/onLayerChange\s*\(\s*prefs\s*\)/.test(body), "必须把图层偏好原样传给 onLayerChange");
});

test("index-html-event-panel-elements-and-script-order", () => {
  const html = readWebui("index.html");
  assert.ok(html.includes('id="event-panel"'), "必须有 #event-panel");
  assert.ok(html.includes('id="event-panel-close"'), "必须有 #event-panel-close");
  assert.ok(html.includes('id="event-panel-mount"'), "必须有 #event-panel-mount");
  const eventsScriptAt = html.indexOf('<script src="events.js">');
  const unitTreeScriptAt = html.indexOf('<script src="unitTree.js">');
  assert.ok(eventsScriptAt > 0, "index.html 必须引入 events.js");
  assert.ok(unitTreeScriptAt > 0, "index.html 必须引入 unitTree.js");
  assert.ok(eventsScriptAt > unitTreeScriptAt, "events.js 的 <script> 必须在 unitTree.js 之后");
});

test("index-html-combats-layer-label-is-battle-events-and-old-label-is-gone", () => {
  const html = readWebui("index.html");
  const line = html.split("\n").filter((text) => text.includes('data-layer="combats"'))[0];
  assert.ok(line, '图层抽屉必须有 data-layer="combats"');
  assert.ok(line.includes("战斗事件"), "combats 图层标签必须是「战斗事件」");
  assert.ok(!/>\s*交战\s*</.test(html), "图层抽屉里不得再出现「交战」标签");
});

test("styles-css-adds-event-panel-and-explicit-hidden-rule", () => {
  const css = readWebui("styles.css");
  assert.ok(/\.event-panel\s*\{/.test(css), "必须有 .event-panel 样式");
  assert.ok(/\.event-panel\[hidden\]\s*\{/.test(css), "必须显式声明 .event-panel[hidden]（防 display 规则盖掉 hidden）");
});
