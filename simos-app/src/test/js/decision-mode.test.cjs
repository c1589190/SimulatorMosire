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
  assert.equal(h.app.byId("decision-start-status").text, "随时可点 · 可发起（本 tick 待决）");
});

test("render-left-keeps-the-start-button-clickable-when-not-due", async () => {
  // ★★ 2026-09-23 用户裁定：「我在当前回合点开始决策」要**随时可点** ⇒ due 从此只是**提示**，不再 disable。
  //   判别力：把 `button.disabled = false` 改回 `!gate.enabled`，本条即红（due=false / due=null 两个都断言了）。
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
      false,
      "due=" + JSON.stringify(maker.due) + " ⇒ 仍然可点（提示不拦人）"
    );
    // ★ 提示必须**照实说**此刻不是它的窗口（不然用户以为系统在假装待决）。
    assert.match(h.app.byId("decision-start-status").text, /^随时可点 · /);
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
  //   ★ 2026-09-23 起「开始决策」是**两次世界写**（发起 + 跑一轮）⇒ 游标推进两次，末值才是真 head。
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
  await flush();
  assert.deepEqual(
    h.calls.setRevision,
    [5, 7],
    "两次世界写各推进一次游标：发起后 5、跑一轮落盘后 7（末值 = 真 head）"
  );
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

test("a-round-runs-asynchronously-and-the-decision-lands-in-place", async () => {
  // ★★ 2026-09-23 用户要的流程：点「开始决策」⇒ ① 发起 ② **异步**跑一轮（POST 立即返回 + 轮询 run-status）
  //   ⇒ 跑完在**下面就地**渲染该决策人最近一次决策（决心/理由/命令清单）——**左边没有额外的"看结果"按钮**。
  //   判别力：把 `finishAsyncRun` 里的 `loadLatestDecision` 去掉，本条即红（"结果只在状态行里"就是用户抱怨的形态）。
  const directive = {
    directiveId: "d-9",
    decisionMakerId: "dm-due",
    tick: 3,
    target: "sd:combat.c1",
    intentInfo: "守住北面的渡口",
    intentInfoKey: "intent",
    status: "ISSUED",
    verdict: null,
    effects: [],
    commands: [{ type: "unit.PlanRoute", payloadJson: "{}" }],
  };
  const h = renderHarness([DUE_MAKER], { directives: [directive] });
  h.P2.renderDecisionLeft({ mode: "decision", selection: { kind: "unit", id: "u-root" } });
  await flush();
  await flush();

  h.P2.startDecision();
  await flush();
  await flush();
  await flush();

  assert.equal(h.calls.startDecision.length, 1, "① 发起");
  assert.equal(h.calls.run.length, 1, "② 接着真跑一轮（走异步窄端点）");
  assert.equal(h.calls.run[0].decisionMakerId, "dm-due");
  assert.match(h.app.byId("decision-run-status").text, /跑完一轮/, "结局由 run-status 报（不再由那次 POST 返回）");
  assert.ok(
    h.nodes["decision-progress"].children.length > 0,
    "LLM 运行情况那一窗必须真的有行（" + JSON.stringify(h.nodes["decision-progress"].children) + "）"
  );
  const latest = h.nodes["decision-latest"].children.filter(
    (n) => n.attrs && n.attrs["data-latest-directive-id"]
  );
  assert.equal(latest.length, 1, "跑完必须**就地**渲染决策");
  assert.equal(latest[0].attrs["data-latest-directive-id"], "d-9");
  const dl = latest[0].children.find((n) => n.tag === "dl");
  const labels = dl.children.filter((n) => n.tag === "dt").map((n) => n.text);
  assert.ok(
    labels.some((t) => t.indexOf("决心") >= 0),
    "决策全文必须含「决心 / 理由」行：" + JSON.stringify(labels)
  );
  assert.ok(
    labels.some((t) => t.indexOf("命令清单") >= 0),
    "决策全文必须含「命令清单」行：" + JSON.stringify(labels)
  );
});

test("say-and-context-reset-are-two-separate-explicit-actions", async () => {
  // ★★ 用户原话：「允许不管第一轮还是最后一轮都可以额外和决策人对话」+「只有点额外的上下文重置按键才重置」。
  //   本条钉的是**两件事各走各的端点**：say 落会话库（不是世界写），reset 是真命令（落 revision ⇒ 推进游标）。
  const h = renderHarness([DUE_MAKER]);
  h.P2.renderDecisionLeft({ mode: "decision", selection: { kind: "unit", id: "u-root" } });
  await flush();
  await flush();

  h.app.byId("decision-say-text").value = "  守住北面的渡口  ";
  h.P2.say();
  await flush();
  assert.deepEqual(
    h.calls.say,
    [{ decisionMakerId: "dm-due", text: "守住北面的渡口", branch: "main", revision: null }],
    "说的话按原样送出去（两端空白裁掉），带上当前 target 以便服务端查会话世代"
  );
  assert.equal(h.app.byId("decision-say-text").value, "", "发出去之后清空输入框");

  h.P2.resetDecisionContext();
  await flush();
  await flush();
  assert.deepEqual(
    h.calls.reset,
    [{ branch: "main", expectedRevision: null, decisionMakerId: "dm-due" }],
    "重置走**真命令**（窄端点，落 revision）"
  );
  assert.ok(h.calls.setRevision.includes(9), "重置也是世界写 ⇒ 游标必须推进到新 head");
  assert.match(h.app.byId("decision-context-status").text, /空上下文/);
});

test("merged-button-sends-the-text-first-then-starts-and-runs", async () => {
  // ★★ 2026-09-23 用户裁定：「发送」/「让它跑一轮」/「开始决策」**三个按钮合并成一个**。
  //   文本框有内容 ⇒ 点一下 = ① 先 say（进它的会话）→ ② sd.StartDecision → ③ 跑一轮，**顺序固定**。
  //   判别力：去掉 ①、或把顺序换成"先发起再 say"，本条即红——而界面上那两种做法**看不出任何差别**。
  const h = renderHarness([DUE_MAKER]);
  h.P2.renderDecisionLeft({ mode: "decision", selection: { kind: "unit", id: "u-root" } });
  await flush();
  await flush();
  h.app.byId("decision-say-text").value = "  守住北面的渡口  ";
  h.P2.startDecision();
  await flush();
  await flush();
  await flush();
  assert.deepEqual(
    h.calls.say,
    [{ decisionMakerId: "dm-due", text: "守住北面的渡口", branch: "main", revision: null }],
    "① 先把补充指示发进会话（两端空白裁掉）"
  );
  assert.equal(h.calls.startDecision.length, 1, "② 再发起（sd.StartDecision）");
  assert.equal(h.calls.run.length, 1, "③ 最后跑一轮");
  assert.equal(h.app.byId("decision-say-text").value, "", "发送成功 ⇒ 清空文本框");

  // 反例：这句话**发不出去** ⇒ 当场停（不发起、不跑），且文本框里的内容保留。
  const bad = renderHarness([DUE_MAKER], {
    sayResponder: () => Promise.reject(new Error("会话库不可写")),
  });
  bad.P2.renderDecisionLeft({ mode: "decision", selection: { kind: "unit", id: "u-root" } });
  await flush();
  await flush();
  bad.app.byId("decision-say-text").value = "守住北面的渡口";
  bad.P2.startDecision();
  await flush();
  await flush();
  await flush();
  assert.equal(bad.calls.say.length, 1, "① 试过了");
  assert.equal(bad.calls.startDecision.length, 0, "① 失败 ⇒ **不得**接着发起（指示没落进去就发起 = 谎）");
  assert.equal(bad.calls.run.length, 0, "① 失败 ⇒ 不跑这一轮");
  assert.equal(bad.app.byId("decision-say-text").value, "守住北面的渡口", "发失败 ⇒ 文本框内容保留");
  assert.match(bad.app.byId("decision-start-status").text, /已停下/);
});

test("start-decision-posts-even-when-target-is-not-due", async () => {
  // ★★ 2026-09-23 用户裁定：**不再阻断**。这条用例以前断言"非待决 ⇒ 前端不得发出写请求"，现在反过来——
  //   due 只是提示，点了就发（判别力：把 `decideStartDecision` 里那道 due 前置加回去，本条即红）。
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
  assert.deepEqual(
    h.calls.startDecision,
    [{ branch: "main", expectedRevision: null, decisionMakerId: "dm-x" }],
    "非待决 ⇒ 照样发起（提示不拦人）"
  );
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
  const calls = { startDecision: [], setRevision: [], refreshState: 0, say: [], reset: [], run: [] };
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
    // ★ 2026-09-23：跑一轮改成**异步**（POST 立即返回 + 轮询 run-status）⇒ 替身这两条必须成对给出：
    //   只给 runDecision 而不给 runDecisionStatus 会让轮询当场走"没有这个端点"的分支（真实浏览器里不会那样）。
    runDecision: (branch, expectedRevision, decisionMakerId) => {
      calls.run.push({ branch, expectedRevision, decisionMakerId });
      return Promise.resolve({
        result: "committed",
        ref: { branch: "main", revision: 7 },
        running: true,
      });
    },
    // ★ 默认应答"第一次轮询就已完成"：轮询必须**当场停**（留着一个 setInterval 会让 node 进程不退出——
    //   那正是"测试看起来过了、其实挂住"的形态）。
    runDecisionStatus: (id) => {
      if (opts.runDecisionStatusResponder) {
        return opts.runDecisionStatusResponder({ id });
      }
      return Promise.resolve({
        decisionMakerId: id,
        running: false,
        done: true,
        llmCalls: 2,
        toolCalls: [{ tool: "simos.map.hex", ok: true, code: "", summary: "desert" }],
        startedAt: 1,
        elapsedMs: 1200,
        result: { status: "ok", conversationId: "decision-maker:" + id, finalText: "已出令" },
      });
    },
    directives: () => Promise.resolve({ directives: opts.directives || [] }),
    sayToDecisionMaker: (decisionMakerId, text, branch, revision) => {
      calls.say.push({ decisionMakerId, text, branch, revision });
      if (opts.sayResponder) {
        return opts.sayResponder({ call: calls.say.length, decisionMakerId: decisionMakerId, text: text });
      }
      return Promise.resolve({
        decisionMakerId: decisionMakerId,
        conversationId: "decision-maker:" + decisionMakerId,
        length: text.length,
      });
    },
    resetDecisionConversation: (branch, expectedRevision, decisionMakerId) => {
      calls.reset.push({ branch, expectedRevision, decisionMakerId });
      return Promise.resolve({ result: "committed", ref: { branch: "main", revision: 9 } });
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
  // ★★ 2026-09-23 用户裁定（原话：「很烂的交互逻辑」）：radio 组改**横排 tab 条**——
  //   tab 是「切换」不是「单选」⇒ 判别力：把 radio 组改回来，下面两条即红。
  assert.ok(html.includes('role="tablist"'), "子页控件必须是 tab 条（role=tablist）");
  ["view", "approval", "provider"].forEach((id) => {
    assert.ok(html.includes('data-decision-tab="' + id + '"'), "tab 值必须在：" + id);
  });
  assert.equal(html.includes('name="decision-subpage"'), false, "radio 组不得回归（那是表单语义）");
  assert.ok(html.includes('data-decision-subpage="view"'), "子页 A 容器");
  assert.ok(html.includes('data-decision-subpage="approval"'), "子页 B 容器");
  assert.ok(html.includes('data-decision-subpage="provider"'), "子页 C 容器");
  // 三处挂载点（左栏详情 + 右栏列表 + 右栏审批）。
  assert.ok(html.includes('id="decision-maker-detail"'), "左栏决策人详情挂载点");
  assert.ok(html.includes('id="decision-maker-list-mount"'), "右栏分类列表挂载点");
  assert.ok(html.includes('id="decision-approval-mount"'), "右栏审批挂载点");
});

test("index-html-decision-mode-has-start-decision-button", () => {
  const html = readWebui("index.html");
  // ★★ T10（三件事模型 ④）建了这个入口；**2026-09-23 用户裁定改形态**：不再受 due 闸门限制（随时可点）——
  //   故按钮**不再带 disabled**，due 只作提示（判别力：把 disabled 加回去，本条即红）。
  assert.ok(html.includes('id="decision-start"'), "决策模式必须有「开始决策」按钮");
  assert.ok(html.includes("开始决策"), "按钮文案必须是「开始决策」");
  assert.ok(html.includes('id="decision-start-status"'), "必须有发起结果状态行");
  const buttonTag = html.slice(html.indexOf('id="decision-start"'), html.indexOf(">", html.indexOf('id="decision-start"')));
  assert.equal(buttonTag.includes("disabled"), false, "按钮不得再 disabled（due 只是提示，不拦人）");
  // ★ 2026-09-23 新增的三个交互锚点（就地结果 / 说一句话 / 上下文重置 + 可展开的 LLM 运行情况）。
  assert.ok(html.includes('id="decision-latest"'), "必须有一处**就地**渲染决策的挂载点");
  assert.ok(html.includes('id="decision-say-text"'), "必须有跟决策人对话的文本框");
  // ★★ 2026-09-23 用户裁定：「发送」/「让它跑一轮」/「开始决策」**三个按钮合并成一个** ⇒
  //   独立的「发送」与「让它跑一轮」按钮**不得存在**（文本框与运行状态行留着，按钮没了）。
  //   判别力：把任一按钮加回 index.html，本条即红。
  assert.equal(html.includes('id="decision-say-send"'), false, "「发送」按钮已并入「开始决策」");
  assert.equal(html.includes('id="decision-run"'), false, "「让它跑一轮」按钮已并入「开始决策」");
  assert.ok(html.includes('id="decision-run-status"'), "这一轮的进度/结局状态行必须还在");
  assert.ok(html.includes('id="decision-context-reset"'), "必须有「上下文重置」按钮");
  assert.ok(html.includes('id="decision-progress-toggle"'), "必须有「展开 LLM 运行情况」的开关");
  assert.ok(html.includes('id="decision-progress"'), "必须有 LLM 运行情况的容器");
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

// ── 子页 tab 条（2026-09-23 用户裁定：radio 组 → 横排 tab 条）──────────────────
//
// ★★ 本条钉的是**接线本身**：tab 必须真的能切。旧 radio 组从头到尾**没有一个 addEventListener**
//   （圆点点得动、内容一动不动）⇒ 那正是用户说的"很烂的交互逻辑"的物理原因之一。
//   判别力：删掉 app.js 的 `mountDecisionTabs` 接线、或 click 回调里不调 `setDecisionSubpage`，本条即红。

const SUBPAGE_IDS = ["view", "approval", "provider"];

function fakeTab(id) {
  const node = {
    attrs: { "data-decision-tab": id, "aria-selected": id === "view" ? "true" : "false" },
    classes: new Set(),
    listeners: [],
    getAttribute(name) {
      return node.attrs[name] === undefined ? null : node.attrs[name];
    },
    setAttribute(name, value) {
      node.attrs[name] = String(value);
    },
    classList: {
      toggle(name, on) {
        if (on) {
          node.classes.add(name);
        } else {
          node.classes.delete(name);
        }
      },
    },
    addEventListener(type, fn) {
      if (type === "click") {
        node.listeners.push(fn);
      }
    },
    click() {
      node.listeners.slice().forEach((fn) => fn({}));
    },
  };
  return node;
}

function fakePane(id) {
  const node = {
    hidden: false,
    attrs: { "data-decision-subpage": id },
    getAttribute(name) {
      return node.attrs[name] === undefined ? null : node.attrs[name];
    },
  };
  return node;
}

function paneOf(panes, id) {
  return panes.find((pane) => pane.getAttribute("data-decision-subpage") === id);
}

test("tab-click-actually-switches-the-visible-subpage", () => {
  const tabs = SUBPAGE_IDS.map(fakeTab);
  const panes = SUBPAGE_IDS.map(fakePane);
  const bar = {
    querySelectorAll: (selector) => (selector === "[data-decision-tab]" ? tabs : []),
  };
  const doc = {
    getElementById: (id) => (id === "decision-subpages" ? bar : null),
    querySelector: () => null,
    querySelectorAll: (selector) => {
      if (selector === "[data-decision-tab]") {
        return tabs;
      }
      if (selector === "[data-decision-subpage]") {
        return panes;
      }
      return [];
    },
    addEventListener() {},
    createElement: () => fakePane("x"),
    body: { getAttribute: () => null, setAttribute() {} },
  };
  const A2 = loadWebui("app.js", { document: doc, SimosPanels: P }).SimosApp;
  A2.mountDecisionTabs();
  A2.applyDecisionSubpage();
  assert.equal(paneOf(panes, "view").hidden, false, "初始子页（view）必须可见");

  tabs[1].click(); // 点「审批」

  assert.equal(A2.getState().decisionSubpage, "approval", "点 tab ⇒ 状态切到该子页");
  assert.equal(paneOf(panes, "approval").hidden, false, "审批容器必须可见");
  assert.equal(paneOf(panes, "view").hidden, true, "原容器必须隐藏（恰一个可见）");
  assert.equal(paneOf(panes, "provider").hidden, true, "第三容器也隐藏（恰一个可见）");
  // 选中态：`.active` 与模式栏同一套视觉语言；aria-selected 让无障碍也说得清"现在在哪一页"。
  assert.equal(tabs[1].attrs["aria-selected"], "true");
  assert.equal(tabs[0].attrs["aria-selected"], "false");
  assert.equal(tabs[1].classes.has("active"), true, "选中的 tab 必须高亮");
  assert.equal(tabs[0].classes.has("active"), false, "旧的 tab 必须掉高亮");
});
