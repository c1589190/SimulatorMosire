// pending-signal.test.cjs —— T9「建议/待决信号」前端护栏。
//
// ★ 背景：待决信号的**计算**在服务端（D7 公式：tick − 最近一次 Directive 的 tick ≥ cadence；首次恒 due），
//   前端**只做显示**。T7 已把展示位留好（`pendingStatusText(maker.due)`），T9 把服务端真值接上。
//   本文件钉住三件事：① 待决文本**不造假**（非布尔一律「—」）；② 服务端 `due` 真值进右栏列表项与左栏详情；
//   ③ 列表项的待决文本**确实由服务端的 `maker.due` 驱动**（不是写死）。
//
// ★ 故意违规（变异靶子）：把列表项的 `pendingStatusText(maker.due)` 换成固定文案 ⇒ 渲染断言红；
//   把 `pendingStatusText` 的非布尔分支改成返回"非待决" ⇒ 不造假断言红。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

const P = loadWebui("panels.js").SimosPanels;
const M = loadWebui("map.js").SimosMap;

// ── 夹具：三条决策人，due 分别 true / false / 缺（未算）──────────────────────
const MAKERS = [
  {
    id: "dm-due",
    affiliation: { kind: "nation", id: "n1", displayName: "甲国" },
    allowedTools: [],
    cadence: 2,
    accessLimit: null,
    due: true,
    lastDirectiveTick: 7,
    ticksSinceLast: 2,
  },
  {
    id: "dm-waiting",
    affiliation: { kind: "army", id: "a1", displayName: "第一军", rootUnit: "u-1" },
    allowedTools: [],
    cadence: 5,
    accessLimit: null,
    due: false,
    lastDirectiveTick: 7,
    ticksSinceLast: 2,
  },
  {
    id: "dm-unknown",
    affiliation: { kind: "nation", id: "n2", displayName: "乙国" },
    allowedTools: [],
    cadence: 3,
    accessLimit: null,
    due: null,
    lastDirectiveTick: null,
    ticksSinceLast: null,
  },
];

const REGIONS = [
  { id: "r-nation", meta: { tag: "nation:n1" } },
  { id: "r-other", meta: { tag: "province:x" } },
];

test("module-exports-the-pending-helpers", () => {
  assert.equal(typeof P.pendingStatusText, "function");
  assert.equal(typeof P.decisionMakerFields, "function");
  assert.equal(typeof P.renderDecisionRight, "function");
  assert.equal(typeof P.renderDecisionLeft, "function");
});

test("pending-status-text-maps-only-real-booleans-and-never-fabricates", () => {
  // ★ 服务端未算出（null/undefined）或类型不对 ⇒ 「—」，**绝不**读成"非待决"。
  assert.equal(P.pendingStatusText(true), "待决");
  assert.equal(P.pendingStatusText(false), "非待决");
  for (const bad of [null, undefined, "true", "false", 0, 1, {}]) {
    assert.equal(P.pendingStatusText(bad), "—", "不该把 " + JSON.stringify(bad) + " 当布尔");
  }
});

test("decision-maker-fields-project-due-into-pending", () => {
  assert.equal(P.decisionMakerFields(MAKERS[0]).pending, "待决");
  assert.equal(P.decisionMakerFields(MAKERS[1]).pending, "非待决");
  assert.equal(P.decisionMakerFields(MAKERS[2]).pending, "—");
  assert.equal(P.decisionMakerFields(null).pending, "—");
});

// ── 渲染流水线夹具（只替换 IO 与 DOM 宿主，**不替换被测逻辑**）─────────────────
function fakeNode(tag) {
  return {
    tag: tag,
    attrs: {},
    children: [],
    text: "",
    setAttribute(k, v) {
      this.attrs[k] = v;
    },
    appendChild(c) {
      this.children.push(c);
    },
    addEventListener() {},
  };
}

function renderHarness() {
  const nodes = {};
  const appStub = {
    el(tag, attrs, children) {
      const node = fakeNode(tag);
      if (attrs) {
        Object.keys(attrs).forEach((k) => {
          if (k === "text") {
            node.text = attrs[k];
          } else {
            node.attrs[k] = attrs[k];
          }
        });
      }
      (children || []).forEach((c) => node.appendChild(c));
      return node;
    },
    clear(node) {
      node.children = [];
      return node;
    },
    byId(id) {
      if (!nodes[id]) {
        nodes[id] = fakeNode("div");
      }
      return nodes[id];
    },
    statusMessage(node, message, tone) {
      node.text = message;
      node.tone = tone;
    },
    text(value) {
      return value === null || value === undefined ? "—" : String(value);
    },
    target() {
      return { branch: "main", revision: null };
    },
    getState() {
      return { mode: "decision", selection: null, decisionMakerFocus: null, decisionSubpage: "view" };
    },
    setHighlightRegions() {},
    setDecisionMakerFocus() {},
    setSelection() {},
    onStateChange() {},
  };
  const apiStub = {
    mapHex: () => Promise.resolve({ q: 1, r: 1, regions: ["r-nation"] }),
    cachedDecisionMakers: () => Promise.resolve({ decisionMakers: MAKERS }),
    cachedMapOverview: () => Promise.resolve({ regions: REGIONS }),
    cachedUnits: () => Promise.resolve({ units: [] }),
  };
  const P2 = loadWebui("panels.js", {
    SimosApp: appStub,
    SimosApi: apiStub,
    SimosMap: { nationIdsOfRegions: M.nationIdsOfRegions, nationRegionIds: M.nationRegionIds },
  }).SimosPanels;
  return { P2: P2, nodes: nodes };
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

function collect(node, predicate, out) {
  if (!node || !node.children) {
    return out;
  }
  if (predicate(node)) {
    out.push(node);
  }
  node.children.forEach((child) => collect(child, predicate, out));
  return out;
}

test("right-list-renders-pending-from-server-due", async () => {
  const h = renderHarness();
  h.P2.renderDecisionRight({
    mode: "decision",
    decisionSubpage: "view",
    decisionMakerFocus: null,
  });
  await flush();
  await flush();
  const items = collect(
    h.nodes["decision-maker-list-mount"],
    (n) => n.attrs && n.attrs.class === "decision-maker-item",
    []
  );
  assert.equal(items.length, 3, "三个决策人 ⇒ 三个列表项");
  const byId = {};
  items.forEach((item) => {
    const badge = item.children.find(
      (c) => c.attrs && c.attrs.class === "decision-maker-pending"
    );
    assert.ok(badge, "每个列表项必须带待决徽标：" + item.attrs["data-decision-maker-id"]);
    byId[item.attrs["data-decision-maker-id"]] = badge.text;
  });
  assert.equal(byId["dm-due"], "待决");
  assert.equal(byId["dm-waiting"], "非待决");
  assert.equal(byId["dm-unknown"], "—", "due=null ⇒ 「—」（未算不造假）");
});

test("left-detail-shows-pending-from-server-due", async () => {
  const h = renderHarness();
  h.P2.renderDecisionLeft({ mode: "decision", selection: { kind: "hex", q: 1, r: 1 } });
  await flush();
  await flush();
  const detail = collect(
    h.nodes["decision-maker-detail"],
    (n) => n.attrs && n.attrs["data-decision-maker-id"] === "dm-due",
    []
  );
  assert.equal(detail.length, 1, "r-nation 带 nation:n1 tag ⇒ 左栏应出现 dm-due");
  const pending = collect(detail[0], (n) => n.tag === "dd" && n.text === "待决", []);
  assert.equal(pending.length, 1, "左栏「待决状态」必须显示服务端 due=true ⇒ 待决");
});

test("panels-list-item-wires-server-due-into-pending-text", () => {
  const source = readWebui("panels.js");
  // ★ 列表项的待决文本必须**由服务端的 maker.due 驱动**（恰好一处；换成固定文案 ⇒ 本断言红）。
  assert.equal(
    (source.match(/pendingStatusText\(maker\.due\)/g) || []).length,
    1,
    "列表项必须以 maker.due 驱动待决文本"
  );
  assert.ok(
    source.includes('class: "decision-maker-pending"'),
    "决策人列表项必须有待决徽标元素"
  );
});
