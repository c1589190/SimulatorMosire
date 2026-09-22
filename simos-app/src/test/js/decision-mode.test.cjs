// decision-mode.test.cjs —— T7「决策模式（一模式两子页）+ 决策人交互」的护栏。
//
// 三路证据：
//   ① 纯函数（panels.js）：子页状态/可见性（fail-closed）、按类型分组（C15）、
//      国家/单位 ⇒ 决策人解析、待决文本（**不造假**）。
//   ② 纯函数（map.js）：国家 tag ⇒ 区域集合（C12，**集合相等、不是子集**）。
//   ③ 静态：index.html 六个模式按钮 + 决策面板/子页控件/三处挂载点；决策模式只读（无 StartDecision、
//      无命令写）；app.js 的子页可见性委托给 fail-closed 纯函数。
// ★ 故意违规自证（变异靶子）：m1 分组混排/漏桶 ⇒ 分组断言红；m2 国家 tag 只高亮第一个 ⇒ 集合相等红；
//   m3 modes.js 加了模式但 index.html 没加按钮 ⇒ 六模式静态断言红；m4 子页切换失效 ⇒ 可见性断言红。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

const P = loadWebui("panels.js").SimosPanels;
const M = loadWebui("map.js").SimosMap;

// ── 夹具：两个国家 + 两支军队（两支军队指同一个根 ⇒ 检验确定性）──────────────
const MAKERS = [
  { id: "dm-army-z", affiliation: { kind: "army", id: "a-z", rootUnit: "u-root" }, cadence: 5 },
  { id: "dm-nation-b", affiliation: { kind: "nation", id: "n2" }, cadence: 4 },
  { id: "dm-army-a", affiliation: { kind: "army", id: "a-a", rootUnit: "u-root" }, cadence: 3 },
  { id: "dm-nation-a", affiliation: { kind: "nation", id: "n1" }, cadence: 2 },
  { id: "dm-army-other", affiliation: { kind: "army", id: "a-o", rootUnit: "u-other" }, cadence: 1 },
];

const UNITS = [
  { id: "u-root", parent: null },
  { id: "u-child", parent: "u-root" },
  { id: "u-grand", parent: "u-child" },
  { id: "u-other", parent: null },
  { id: "u-lone", parent: null },
];

const REGIONS = [
  { id: "r-b", meta: { tag: "nation:n1" } },
  { id: "r-a", meta: { tag: "nation:n1" } },
  { id: "r-c", meta: { tag: "nation:n2" } },
  { id: "r-d", meta: { tag: "province:x" } },
  { id: "r-e", meta: { tag: null } },
  { id: "r-f", meta: null },
  { id: "r-g", meta: { tag: "nation:" } },
];

test("module-loads", () => {
  assert.equal(typeof P.decisionSubpageVisibility, "function");
  assert.equal(typeof P.decisionMakerGroups, "function");
  assert.equal(typeof P.decisionMakerForUnit, "function");
  assert.equal(typeof M.nationRegionIds, "function");
  assert.equal(typeof M.nationIdsOfRegions, "function");
});

test("subpage-ids-and-labels", () => {
  // M11′ 起 2 → 3 子页（新增「Provider 配置」）—— 语义更新，不是削弱。
  assert.deepEqual(
    P.DECISION_SUBPAGES.map((s) => s.id),
    ["view", "approval", "provider"]
  );
  assert.deepEqual(
    P.DECISION_SUBPAGES.map((s) => s.label),
    ["决策人查看", "审批", "Provider 配置"]
  );
});

test("subpage-state-is-fail-closed", () => {
  // ★ 故意违规：未知/空子页**不得**兜成「决策人查看」——兜了会把"没选"变成"选了 view"。
  for (const bad of ["", "nope", null, undefined, 7, {}]) {
    const state = P.decisionSubpageState(bad);
    assert.equal(state.ok, false, "不该接受 " + JSON.stringify(bad));
    assert.equal(state.id, null);
    assert.equal(state.label, null);
  }
  assert.deepEqual(P.decisionSubpageState("view"), { ok: true, id: "view", label: "决策人查看" });
  assert.deepEqual(P.decisionSubpageState("approval"), { ok: true, id: "approval", label: "审批" });
  assert.deepEqual(P.decisionSubpageState("provider"), {
    ok: true,
    id: "provider",
    label: "Provider 配置",
  });
});

test("subpage-visibility-is-mutually-exclusive", () => {
  // ★ 故意违规（m4/m8 的杀点）：三个子页**恰一个**可见；未知 ⇒ 三个都不可见。
  const HIDDEN = { view: false, approval: false, provider: false };
  assert.deepEqual(P.decisionSubpageVisibility("view"), {
    view: true,
    approval: false,
    provider: false,
  });
  assert.deepEqual(P.decisionSubpageVisibility("approval"), {
    view: false,
    approval: true,
    provider: false,
  });
  assert.deepEqual(P.decisionSubpageVisibility("provider"), {
    view: false,
    approval: false,
    provider: true,
  });
  for (const bad of ["", "nope", null, undefined, 3]) {
    assert.deepEqual(
      P.decisionSubpageVisibility(bad),
      HIDDEN,
      "未知子页必须三个都隐藏：" + JSON.stringify(bad)
    );
  }
  ["view", "approval", "provider"].forEach((id) => {
    const v = P.decisionSubpageVisibility(id);
    assert.equal(
      Number(v.view) + Number(v.approval) + Number(v.provider),
      1,
      id + " 的子页必须恰一个可见"
    );
  });
});

test("decision-groups-put-nation-then-army-and-sort-by-id", () => {
  // ★ 故意违规（m1 的杀点）：类型分组 + 组序（国家 → 军队）+ 组内 id 字典序。
  const groups = P.decisionMakerGroups(MAKERS);
  assert.deepEqual(
    groups.map((g) => g.kind),
    ["nation", "army"]
  );
  assert.deepEqual(
    groups.map((g) => g.label),
    ["国家", "军队"]
  );
  assert.deepEqual(
    groups[0].makers.map((m) => m.id),
    ["dm-nation-a", "dm-nation-b"]
  );
  assert.deepEqual(
    groups[1].makers.map((m) => m.id),
    ["dm-army-a", "dm-army-other", "dm-army-z"]
  );
  // 长度守恒：分组后总条数 == 输入条数（C15 的"列表长度 = 服务端返回长度"）。
  assert.equal(
    groups.reduce((sum, g) => sum + g.makers.length, 0),
    MAKERS.length
  );
});

test("decision-groups-keep-unknown-kinds-and-preserve-length", () => {
  // ★ 未知归属种类**不得静默丢弃**（丢了会让"列表长度 = 服务端返回长度"这条失真）。
  const mixed = MAKERS.concat([
    { id: "dm-tribe", affiliation: { kind: "tribe", id: "t1" } },
    { id: "dm-none", affiliation: null },
  ]);
  const groups = P.decisionMakerGroups(mixed);
  assert.deepEqual(
    groups.map((g) => g.kind),
    ["nation", "army", "tribe", ""]
  );
  assert.equal(groups[2].label, "其它（tribe）");
  assert.equal(groups[3].label, "其它（）");
  assert.equal(
    groups.reduce((sum, g) => sum + g.makers.length, 0),
    mixed.length,
    "未知种类必须落进桶里、不得消失"
  );
  // 缺 id 的条目跳过（不是分组对象），空输入 ⇒ 空数组（不崩）。
  assert.deepEqual(P.decisionMakerGroups([null, { affiliation: { kind: "nation", id: "n1" } }]), []);
  assert.deepEqual(P.decisionMakerGroups([]), []);
  assert.deepEqual(P.decisionMakerGroups(undefined), []);
});

test("decision-makers-for-nation-matches-exactly", () => {
  assert.deepEqual(
    P.decisionMakersForNation(MAKERS, "n1").map((m) => m.id),
    ["dm-nation-a"]
  );
  assert.deepEqual(
    P.decisionMakersForNation(MAKERS, "n2").map((m) => m.id),
    ["dm-nation-b"]
  );
  // 军队决策人**不**按国家返回（军队归属另算）。
  assert.deepEqual(P.decisionMakersForNation(MAKERS, "a-a"), []);
  for (const bad of ["", null, undefined, "nope"]) {
    assert.deepEqual(P.decisionMakersForNation(MAKERS, bad), [], "不该匹配 " + JSON.stringify(bad));
  }
});

test("decision-maker-for-unit-resolves-root-and-descendants", () => {
  // 根单位命中；后代沿 parent 链上溯也算"有决策人"（与单位树同口径）。
  assert.equal(P.decisionMakerForUnit(MAKERS, UNITS, "u-root").id, "dm-army-a");
  assert.equal(P.decisionMakerForUnit(MAKERS, UNITS, "u-child").id, "dm-army-a");
  assert.equal(P.decisionMakerForUnit(MAKERS, UNITS, "u-grand").id, "dm-army-a");
  assert.equal(P.decisionMakerForUnit(MAKERS, UNITS, "u-other").id, "dm-army-other");
  // 无军队关联 / 不存在的单位 / 空 id ⇒ null（调用方显示"无决策人"，不静默空白）。
  assert.equal(P.decisionMakerForUnit(MAKERS, UNITS, "u-lone"), null);
  assert.equal(P.decisionMakerForUnit(MAKERS, UNITS, "nope"), null);
  assert.equal(P.decisionMakerForUnit(MAKERS, UNITS, ""), null);
  assert.equal(P.decisionMakerForUnit([], UNITS, "u-root"), null);
});

test("decision-maker-for-unit-is-deterministic-across-two-armies", () => {
  // 两支军队指同一个根 ⇒ 取 id 字典序最小者（确定性；不得依赖输入顺序）。
  const shuffled = MAKERS.slice().reverse();
  assert.equal(P.decisionMakerForUnit(shuffled, UNITS, "u-root").id, "dm-army-a");
  assert.equal(P.decisionMakerForUnit(MAKERS, UNITS, "u-root").id, "dm-army-a");
});

test("decision-maker-for-unit-survives-a-parent-cycle", () => {
  // 防御性：单位树成环时**不得**死循环（返回 null 或某个命中，必须终止）。
  const cyclic = [
    { id: "c1", parent: "c2" },
    { id: "c2", parent: "c1" },
  ];
  assert.equal(P.decisionMakerForUnit(MAKERS, cyclic, "c1"), null);
});

test("pending-status-text-never-fabricates", () => {
  // ★★ 三件事模型 ②：due 只有 T9 才算得出来 ⇒ null/undefined 一律「—」，**绝不**读成"非待决"。
  assert.equal(P.pendingStatusText(null), "—");
  assert.equal(P.pendingStatusText(undefined), "—");
  assert.equal(P.pendingStatusText("true"), "—");
  assert.equal(P.pendingStatusText(0), "—");
  assert.equal(P.pendingStatusText(true), "待决");
  assert.equal(P.pendingStatusText(false), "非待决");
});

test("affiliation-label-formats-kind-name-and-id", () => {
  assert.equal(
    P.affiliationLabel({ kind: "nation", id: "n1", displayName: "甲国" }),
    "国家：甲国（n1）"
  );
  assert.equal(
    P.affiliationLabel({ kind: "army", id: "a1", displayName: "第一军" }),
    "军队：第一军（a1）"
  );
  // 未知种类原样（不静默造标签）；缺字段显式「—」（不编造）。
  assert.equal(P.affiliationLabel({ kind: "tribe", id: "t1", displayName: "部族" }), "tribe：部族（t1）");
  assert.equal(P.affiliationLabel({ kind: "nation", id: "n1" }), "国家：—（n1）");
  assert.equal(P.affiliationLabel(null), "—：—（—）");
});

const DUE_MAKER = {
  id: "dm-due",
  affiliation: { kind: "army", id: "a1", rootUnit: "u-root" },
  cadence: 2,
  due: true,
};

test("start-decision-gate-requires-due", () => {
  // ★★ T10 三件事模型 ④：只在**本 tick 待决**（T9 的 due）时可点；due 取不到 ⇒ 不可点（不造假）。
  assert.equal(P.startDecisionGate(null).enabled, false);
  assert.equal(P.startDecisionGate(undefined).enabled, false);
  assert.equal(P.startDecisionGate({}).enabled, false);
  assert.equal(P.startDecisionGate({ id: "" }).enabled, false);
  assert.equal(P.startDecisionGate({ id: "dm-1", due: false }).enabled, false);
  assert.equal(P.startDecisionGate({ id: "dm-1", due: null }).enabled, false);
  assert.equal(P.startDecisionGate({ id: "dm-1" }).enabled, false);
  assert.equal(P.startDecisionGate({ id: "dm-1", due: true }).enabled, true);
  // ★ 三种"不可点"的理由必须互不相同（不把"未知"读成"非待决"）。
  const reasons = new Set([
    P.startDecisionGate(null).reason,
    P.startDecisionGate({ id: "dm-1", due: false }).reason,
    P.startDecisionGate({ id: "dm-1", due: null }).reason,
  ]);
  assert.equal(reasons.size, 3, "未选中 / 非待决 / 未知 三种理由必须互不相同");
});

test("render-left-gates-the-start-button-on-due", async () => {
  const h = renderHarness([DUE_MAKER]);
  h.P2.renderDecisionLeft({ mode: "decision", selection: { kind: "unit", id: "u-root" } });
  await flush();
  await flush();
  assert.equal(h.nodes["decision-start"].disabled, false, "due=true ⇒ 可点");
  assert.equal(h.P2.startDecisionTargetId(), "dm-due", "目标 = 左栏展示的那个决策人");
  assert.equal(h.app.byId("decision-start-status").text, "可发起（本 tick 待决）");
});

test("render-left-disables-the-start-button-when-not-due", async () => {
  const notDue = {
    id: "dm-x",
    affiliation: { kind: "army", id: "a1", rootUnit: "u-root" },
    cadence: 2,
    due: false,
  };
  const unknown = {
    id: "dm-x",
    affiliation: { kind: "army", id: "a1", rootUnit: "u-root" },
    cadence: 2,
    due: null,
  };
  for (const maker of [notDue, unknown]) {
    const h = renderHarness([maker]);
    h.P2.renderDecisionLeft({ mode: "decision", selection: { kind: "unit", id: "u-root" } });
    await flush();
    await flush();
    assert.equal(
      h.nodes["decision-start"].disabled,
      true,
      "due=" + JSON.stringify(maker.due) + " ⇒ 不可点"
    );
  }
});

test("start-decision-posts-the-target-when-due", async () => {
  const h = renderHarness([DUE_MAKER]);
  h.P2.renderDecisionLeft({ mode: "decision", selection: { kind: "unit", id: "u-root" } });
  await flush();
  await flush();
  h.P2.startDecision();
  assert.deepEqual(h.calls.startDecision, [
    { branch: "main", expectedRevision: null, decisionMakerId: "dm-due" },
  ]);
});

test("start-decision-advances-the-cursor-on-success", async () => {
  // ★ 409 的根因之一：成功分支不推进游标 ⇒ 下一次点击仍带旧 revision。本用例钉住 setRevision 被调用。
  const h = renderHarness([DUE_MAKER], {
    startDecisionResponder: () =>
      Promise.resolve({ result: "committed", ref: { branch: "main", revision: 5 } }),
  });
  h.P2.renderDecisionLeft({ mode: "decision", selection: { kind: "unit", id: "u-root" } });
  await flush();
  await flush();
  h.P2.startDecision();
  await flush();
  await flush();
  assert.deepEqual(h.calls.setRevision, [5], "成功 ⇒ 游标必须推进到新 head");
});

test("start-decision-retries-once-after-409-with-the-fresh-head", async () => {
  // ★ 409（游标过期）⇒ 重取 head、把游标拉到 current.revision，并用它**自动重试一次** ⇒ 点一次就成。
  const h = renderHarness([DUE_MAKER], {
    startDecisionResponder: ({ call }) =>
      call === 1
        ? Promise.reject(conflictError(2))
        : Promise.resolve({ result: "committed", ref: { branch: "main", revision: 3 } }),
  });
  h.P2.renderDecisionLeft({ mode: "decision", selection: { kind: "unit", id: "u-root" } });
  await flush();
  await flush();
  h.P2.startDecision();
  await flush();
  await flush();
  await flush();
  assert.equal(h.calls.startDecision.length, 2, "409 ⇒ 恰自动重试一次");
  assert.equal(h.calls.startDecision[0].expectedRevision, null);
  assert.equal(h.calls.startDecision[1].expectedRevision, 2, "重试必须用服务端 current.revision");
  assert.ok(h.calls.refreshState >= 1, "409 ⇒ 必须重取 head");
  assert.ok(h.calls.setRevision.includes(2), "重试前游标必须拉到 current.revision");
  assert.ok(h.calls.setRevision.includes(3), "重试成功后再推进到新 head");
  assert.match(h.app.byId("decision-start-status").text, /已发起/);
});

test("start-decision-does-not-retry-beyond-once", async () => {
  // ★ 只允许自动重试一次：重试轮再 409 ⇒ 如实报失败，不得无限循环。
  const h = renderHarness([DUE_MAKER], {
    startDecisionResponder: () => Promise.reject(conflictError(2)),
  });
  h.P2.renderDecisionLeft({ mode: "decision", selection: { kind: "unit", id: "u-root" } });
  await flush();
  await flush();
  h.P2.startDecision();
  await flush();
  await flush();
  await flush();
  assert.equal(h.calls.startDecision.length, 2, "恰两次（首次 + 一次重试），不得更多");
  assert.match(h.app.byId("decision-start-status").text, /发起失败/);
});

test("start-decision-refuses-when-target-is-not-due", async () => {
  const notDue = {
    id: "dm-x",
    affiliation: { kind: "army", id: "a1", rootUnit: "u-root" },
    cadence: 2,
    due: false,
  };
  const h = renderHarness([notDue]);
  h.P2.renderDecisionLeft({ mode: "decision", selection: { kind: "unit", id: "u-root" } });
  await flush();
  await flush();
  h.P2.startDecision();
  assert.deepEqual(h.calls.startDecision, [], "非待决 ⇒ 前端不得发出写请求");
  assert.equal(h.app.byId("decision-start-status").text, "未选中可发起的决策人");
});

test("decision-maker-fields-project-the-server-shape", () => {
  // ★ C13：左栏字段与 GET /api/sd/decision-makers/{id} 逐值一致（这里钉的是字段投影）。
  const maker = {
    id: "dm-1",
    affiliation: { kind: "nation", id: "n1", displayName: "甲国", nationId: "n1", rootUnit: null },
    allowedTools: ["sd.SubmitVerdict", "sd.IssueDirective"],
    cadence: 3,
    // ★ T9：`viewScope` → `accessLimit`（语义变了：不再是"绝对可见集合"，而是 GM 配的**额外限制**）
    accessLimit: {
      prefixesByNamespace: { map: 2, unit: 1 },
      adjudicationDisclosure: "FULL",
      redactedFields: ["position"],
    },
    due: null,
  };
  const fields = P.decisionMakerFields(maker);
  assert.equal(fields.id, "dm-1");
  assert.equal(fields.affiliation, "国家：甲国（n1）");
  assert.equal(fields.cadence, 3);
  assert.equal(fields.allowedTools, "sd.SubmitVerdict、sd.IssueDirective");
  assert.equal(fields.accessLimit.prefixesByNamespace, "map=2、unit=1");
  assert.equal(fields.accessLimit.adjudicationDisclosure, "FULL");
  assert.equal(fields.accessLimit.redactedFields, "position");
  assert.equal(fields.pending, "—", "due=null ⇒ 待决状态必须是「—」（T9 前不造假）");
  // 空白名单 / 缺 accessLimit 的降级显示。
  const bare = P.decisionMakerFields({
    id: "x",
    affiliation: { kind: "army", id: "a1", displayName: "一军" },
    allowedTools: [],
    cadence: 1,
    accessLimit: null,
    due: false,
  });
  assert.equal(bare.allowedTools, "（无）");
  assert.equal(bare.accessLimit, null);
  assert.equal(bare.pending, "非待决");
  // ★ 空前缀图 = **无额外限制**（不是"—"、也不是"0 条"）：两个方向都要能看出来是"没配"还是"配了但空"。
  const unrestricted = P.decisionMakerFields({
    id: "y",
    affiliation: { kind: "nation", id: "n1" },
    allowedTools: [],
    cadence: 1,
    accessLimit: { prefixesByNamespace: {}, adjudicationDisclosure: "WITHHELD", redactedFields: [] },
    due: false,
  });
  assert.equal(unrestricted.accessLimit.prefixesByNamespace, "（无额外限制）");
  assert.equal(unrestricted.accessLimit.redactedFields, "（无）");
});

test("nation-region-ids-are-set-equal-not-subset", () => {
  // ★★ C12 的要害：高亮集合 == **该 tag 的全部区域**（逐值相等），不是"第一个"、不是子集。
  assert.deepEqual(M.nationRegionIds(REGIONS, "nation:n1"), ["r-a", "r-b"]);
  assert.deepEqual(M.nationRegionIds(REGIONS, "nation:n2"), ["r-c"]);
  assert.deepEqual(M.nationRegionIds(REGIONS, "nation:n3"), []);
  // 反向自证：只取第一个的实现会给出 ["r-b"]（与断言不等）⇒ 这条有判别力。
  assert.notDeepEqual(M.nationRegionIds(REGIONS, "nation:n1"), ["r-b"]);
  assert.equal(M.nationRegionIds(REGIONS, "nation:n1").length, 2);
});

test("nation-tag-helpers-ignore-non-nation-and-blank-tags", () => {
  assert.equal(M.nationTagOf({ meta: { tag: "nation:n1" } }), "nation:n1");
  assert.equal(M.nationTagOf({ meta: { tag: "province:x" } }), null);
  assert.equal(M.nationTagOf({ meta: { tag: "nation:" } }), "nation:");
  assert.equal(M.nationTagOf({ meta: { tag: null } }), null);
  assert.equal(M.nationTagOf({ meta: null }), null);
  assert.equal(M.nationTagOf(null), null);
  // 空 tag / 空输入 ⇒ 空集合（fail-closed，不返回全部区域）。
  assert.deepEqual(M.nationRegionIds(REGIONS, ""), []);
  assert.deepEqual(M.nationRegionIds(REGIONS, null), []);
  assert.deepEqual(M.nationRegionIds([], "nation:n1"), []);
});

test("nation-ids-of-regions-unions-distinct-nations", () => {
  // 一个 hex 可同时属于多个区域（M8-U1）⇒ 给出全部国家 id（去重 + 字典序）。
  assert.deepEqual(M.nationIdsOfRegions(REGIONS, ["r-c", "r-a", "r-b"]), ["n1", "n2"]);
  assert.deepEqual(M.nationIdsOfRegions(REGIONS, ["r-b"]), ["n1"]);
  // 只有非国家区域 / 空 tag ⇒ 空数组（"没有国家决策人"与"查询坏掉"可区分）。
  assert.deepEqual(M.nationIdsOfRegions(REGIONS, ["r-d", "r-e", "r-f", "r-g"]), []);
  assert.deepEqual(M.nationIdsOfRegions(REGIONS, []), []);
  assert.deepEqual(M.nationIdsOfRegions(REGIONS, ["no-such-region"]), []);
});

// ── 渲染流水线的 node 夹具（C13/C14：真 renderDecisionLeft + 替身 app/api）──────────────
// ★ 只替换 IO 与 DOM 宿主（app/api/document），**不替换被测逻辑**：appendDecisionMakerDetail /
//   decisionMakerForUnit / decisionMakersForNation / nationIdsOfRegions 都是真实现。
function renderHarness(makers, options) {
  const opts = options || {};
  const nodes = {};
  const calls = { startDecision: [], setRevision: [], refreshState: 0 };
  const state = { mode: "decision", selection: null, decisionMakerFocus: null, revision: null };
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
    };
  }
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
      return state;
    },
    setHighlightRegions() {},
    setDecisionMakerFocus() {},
    setSelection() {},
    onStateChange() {},
    setRevision(revision) {
      calls.setRevision.push(revision);
      state.revision = revision;
    },
    refreshState() {
      calls.refreshState += 1;
      return Promise.resolve({});
    },
  };
  const makerList = makers || MAKERS;
  const apiStub = {
    mapHex: () => Promise.resolve({ q: 1, r: 1, regions: ["r-b"] }),
    cachedUnits: () => Promise.resolve({ units: UNITS }),
    cachedDecisionMakers: () => Promise.resolve({ decisionMakers: makerList }),
    decisionMaker: (id) => Promise.resolve(makerList.find((m) => m.id === id)),
    cachedMapOverview: () => Promise.resolve({ regions: REGIONS }),
    approvals: () => Promise.resolve({ pending: [] }),
    approve: () => Promise.resolve({}),
    startDecision: (branch, expectedRevision, decisionMakerId) => {
      calls.startDecision.push({ branch, expectedRevision, decisionMakerId });
      if (opts.startDecisionResponder) {
        return opts.startDecisionResponder({
          call: calls.startDecision.length,
          branch: branch,
          expectedRevision: expectedRevision,
          decisionMakerId: decisionMakerId,
        });
      }
      return Promise.resolve({ result: "committed" });
    },
  };
  const mapStub = { nationIdsOfRegions: M.nationIdsOfRegions, nationRegionIds: M.nationRegionIds };
  const P2 = loadWebui("panels.js", { SimosApp: appStub, SimosApi: apiStub, SimosMap: mapStub })
    .SimosPanels;
  return { P2: P2, nodes: nodes, state: state, app: appStub, calls: calls };
}

/** 造一个 {status:409, body:{current:{revision}}} 的冲突错误（与 GuiServer/ApiViews.conflict 同形）。 */
function conflictError(revision) {
  const err = new Error("conflict");
  err.status = 409;
  err.body = { result: "conflict", current: { branch: "main", revision: revision } };
  return err;
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

function detailOf(harness) {
  return harness.app.byId("decision-maker-detail").children;
}

test("render-left-shows-army-decision-maker-for-a-descendant-unit", async () => {
  // ★ C14：选中**有决策人的单位**（含后代）⇒ 左栏出现该军队决策人（真 renderDecisionLeft）。
  const h = renderHarness();
  h.P2.renderDecisionLeft({ mode: "decision", selection: { kind: "unit", id: "u-child" } });
  await flush();
  await flush();
  const dls = detailOf(h).filter((n) => n.attrs && n.attrs["data-decision-maker-id"]);
  assert.equal(dls.length, 1, "必须恰一个决策人详情块");
  assert.equal(dls[0].attrs["data-decision-maker-id"], "dm-army-a");
  const labels = dls[0].children.filter((n) => n.tag === "dt").map((n) => n.text);
  assert.ok(labels.includes("归属"), "详情必须含「归属」行");
  assert.ok(labels.includes("待决状态"), "详情必须含「待决状态」行");
  const pending = dls[0].children.find((n) => n.tag === "dd" && n.text === "—");
  assert.ok(pending, "due=null ⇒ 待决状态显示「—」（不造假）");
});

test("render-left-shows-no-decision-maker-for-an-unrelated-unit", async () => {
  // ★ C14：选中**无决策人的单位** ⇒ 明确显示"无决策人"（不静默空白）。
  const h = renderHarness();
  h.P2.renderDecisionLeft({ mode: "decision", selection: { kind: "unit", id: "u-lone" } });
  await flush();
  await flush();
  const texts = detailOf(h).map((n) => n.text);
  assert.ok(texts.includes("无决策人"), "无决策人必须显式显示：" + JSON.stringify(texts));
  assert.equal(h.app.byId("decision-view-status").text.includes("无决策人"), true);
});

test("render-left-shows-nation-decision-maker-for-a-nation-region-hex", async () => {
  // ★ C13：点中国家区域内的格 ⇒ 左栏出现该国决策人（真 renderDecisionLeft + 真 tag 解析）。
  const h = renderHarness();
  h.P2.renderDecisionLeft({ mode: "decision", selection: { kind: "hex", q: 1, r: 1 } });
  await flush();
  await flush();
  const dls = detailOf(h).filter((n) => n.attrs && n.attrs["data-decision-maker-id"]);
  assert.equal(dls.length, 1, "r-nation 带 nation:n1 tag ⇒ 应出现 dm-nation-a");
  assert.equal(dls[0].attrs["data-decision-maker-id"], "dm-nation-a");
});

test("index-html-has-six-modes-and-decision-panel", () => {
  const html = readWebui("index.html");
  // ★ 故意违规（m3 的杀点）：modes.js 有第六个模式 ⇒ 模式栏必须也有第六个按钮。
  const buttons = html.match(/class="mode[^"]*" data-mode="/g) || [];
  assert.equal(buttons.length, 6, "模式栏必须 6 个按钮");
  assert.ok(html.includes('data-mode="decision"'), "决策模式按钮必须在");
  assert.ok(html.includes("决策"), "决策模式标签必须在");
  // 左栏决策面板 + 子页控件。
  assert.ok(html.includes('data-modes="decision"'), "必须有 data-modes=decision 的面板");
  assert.ok(html.includes('id="decision-subpages"'), "子页控件必须在");
  assert.ok(html.includes('name="decision-subpage"'), "子页用 radio group");
  assert.ok(html.includes('value="view"') && html.includes('value="approval"'), "两个子页值都在");
  assert.ok(html.includes('data-decision-subpage="view"'), "子页 A 容器");
  assert.ok(html.includes('data-decision-subpage="approval"'), "子页 B 容器");
  // 三处挂载点（左栏详情 + 右栏列表 + 右栏审批）。
  assert.ok(html.includes('id="decision-maker-detail"'), "左栏决策人详情挂载点");
  assert.ok(html.includes('id="decision-maker-list-mount"'), "右栏分类列表挂载点");
  assert.ok(html.includes('id="decision-approval-mount"'), "右栏审批挂载点");
});

test("index-html-decision-mode-has-start-decision-button", () => {
  const html = readWebui("index.html");
  // ★ T10（三件事模型 ④）：决策模式左栏有「开始决策」入口；按钮**默认 disabled**（只在 due 为真时可点）。
  assert.ok(html.includes('id="decision-start"'), "决策模式必须有「开始决策」按钮");
  assert.ok(html.includes("开始决策"), "按钮文案必须是「开始决策」");
  assert.ok(html.includes('id="decision-start-status"'), "必须有发起结果状态行");
  const buttonTag = html.slice(html.indexOf('id="decision-start"'), html.indexOf(">", html.indexOf('id="decision-start"')));
  assert.ok(buttonTag.includes("disabled"), "按钮必须默认 disabled（due 闸门未过时不可点）");
  // 撤掉的右栏审批计数不得回归（T1 的成果）。
  assert.equal(html.includes("approvals-count"), false, "右栏审批计数不得回归");
});

test("map-js-wires-decision-click-to-nation-highlight", () => {
  const source = readWebui("map.js");
  assert.ok(source.includes("selectNationOfHex(pick.q, pick.r)"), "决策模式点格必须走 selectNationOfHex");
  assert.ok(source.includes("decisionViewSubpageActive()"), "点格只在「决策人查看」子页生效");
  assert.ok(source.includes("nationRegionIds("), "高亮集合必须由 nationRegionIds 算出");
  // 实现只有一处（不得在别处再写一份"取第一个国家区域"）。
  assert.equal((source.match(/function nationRegionIds\(/g) || []).length, 1);
  assert.equal((source.match(/function selectNationOfHex\(/g) || []).length, 1);
});

test("panels-js-and-app-js-delegate-subpage-visibility", () => {
  const panels = readWebui("panels.js");
  const app = readWebui("app.js");
  // ★ fail-closed 的判定只有一份实现（panels.js），app.js 必须委托它（不得自己写一套）。
  assert.equal((panels.match(/function decisionSubpageVisibility\(/g) || []).length, 1);
  assert.ok(app.includes("decisionSubpageVisibility("), "app.js 必须委托纯函数判子页可见性");
  assert.equal((app.match(/function decisionSubpageVisibility\(/g) || []).length, 0, "app.js 不得自写一份");
  // 决策模式面板不得引入**通用命令写**（那条仍是 app.writeCommand 的专属）。
  assert.equal(panels.includes("writeCommand("), false, "panels.js 不得发通用命令写");
  // T10 起「开始决策」由决策面板经 api.startDecision（窄端点）发出；命令类型由服务端写死（前端不传 type）。
  assert.match(panels, /api\s*\.\s*startDecision\(/, "panels.js 必须经 api.startDecision 发起");
  assert.ok(panels.includes('addEventListener("click", decideStartDecision)'), "按钮必须绑定发起动作");
  assert.equal(app.includes("sd.StartDecision"), false, "app.js 仍不得含 StartDecision（窄端点专用）");
});
