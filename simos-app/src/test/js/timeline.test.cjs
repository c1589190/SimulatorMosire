// timeline.test.cjs —— 时间轴纯函数（M7b 列布局 / M7f tick 分组）。
//
// ★ 列布局读模块内 model；用 __setModelForTest 注入冻结快照后直接断言（不改页面行为）。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui } = require("./helpers/webui-loader.cjs");

const T = loadWebui("timeline.js").SimosTimeline;

const TICK_GROUPS = {
  main: [
    { tick: 5, nodes: [{ parent: null }] },
    { tick: 6, nodes: [{ parent: null }] },
    { tick: 7, nodes: [{ parent: null }] },
  ],
  b2: [
    { tick: 8, nodes: [{ parent: { branch: "main", revision: 2 } }] },
    { tick: 9, nodes: [{ parent: { branch: "main", revision: 3 } }] },
  ],
};
const TICK_BY_REVISION = { main: { 1: 5, 2: 6, 3: 7 }, b2: { 4: 8, 5: 9 } };

test("layout-constants-are-frozen", () => {
  assert.equal(T.COL_WIDTH, 110);
  assert.equal(T.LABEL_WIDTH, 72);
});

test("columnX-is-left-margin-plus-column-minus-one-times-width", () => {
  assert.equal(T.columnX(1), 72);
  assert.equal(T.columnX(2), 182);
  assert.equal(T.columnX(3), 292);
  assert.equal(T.columnX(4), 402);
});

test("main-branch-columns-follow-tick-order", () => {
  T.__setModelForTest(TICK_GROUPS, TICK_BY_REVISION);
  assert.equal(T.columnOfTick("main", 5), 1);
  assert.equal(T.columnOfTick("main", 6), 2);
  assert.equal(T.columnOfTick("main", 7), 3);
});

test("forked-branch-first-tick-aligns-with-parent-column", () => {
  T.__setModelForTest(TICK_GROUPS, TICK_BY_REVISION);
  assert.equal(T.columnOfTick("b2", 8), 2);
  assert.equal(T.columnOfTick("b2", 9), 3);
});

test("unknown-tick-falls-back-to-first-column", () => {
  T.__setModelForTest(TICK_GROUPS, TICK_BY_REVISION);
  assert.equal(T.columnOfTick("main", 999), 1);
  assert.equal(T.columnOfTick("no-such-branch", 5), 1);
});

test("tickOfRevision-reads-the-injected-table", () => {
  T.__setModelForTest(TICK_GROUPS, TICK_BY_REVISION);
  assert.equal(T.tickOfRevision("main", 2), 6);
  assert.equal(T.tickOfRevision("b2", 5), 9);
  assert.equal(T.tickOfRevision("main", 999), null);
  assert.equal(T.tickOfRevision("no-such-branch", 1), null);
});

test("nodeX-composes-columnOf-with-columnX", () => {
  T.__setModelForTest(TICK_GROUPS, TICK_BY_REVISION);
  assert.equal(T.nodeX("main", 1), T.columnX(1));
  assert.equal(T.nodeX("main", 3), T.columnX(3));
  assert.equal(T.nodeX("b2", 4), T.columnX(2));
});

test("groupByTick-merges-same-tick-into-one-node", () => {
  const nodes = [
    { tick: 6, revision: 1, commandType: "unit.PlanRoute" },
    { tick: 6, revision: 2, commandType: "map.SetTerrain" },
    { tick: 7, revision: 3, commandType: "map.UpdateRegion" },
  ];
  const groups = T.groupByTick(nodes);
  assert.equal(groups.length, 2);
  assert.equal(groups[0].tick, 6);
  assert.equal(groups[0].nodes.length, 2);
  assert.equal(groups[0].firstRevision, 1);
  assert.equal(groups[0].lastRevision, 2);
  assert.deepEqual(groups[0].commands, ["unit.PlanRoute", "map.SetTerrain"]);
  assert.equal(groups[1].tick, 7);
  assert.equal(groups[1].nodes.length, 1);
});

test("groupByTick-empty-input", () => {
  assert.deepEqual(T.groupByTick([]), []);
  assert.deepEqual(T.groupByTick(null), []);
});

test("isAtTip-only-true-at-branch-head", () => {
  assert.equal(T.isAtTip({ branch: "main", revision: 3 }, { main: 3 }), true);
  assert.equal(T.isAtTip({ branch: "main", revision: 2 }, { main: 3 }), false);
  assert.equal(T.isAtTip({ branch: "b2", revision: 1 }, { main: 3 }), false);
  assert.equal(T.isAtTip(null, { main: 3 }), false);
  assert.equal(T.isAtTip({ branch: "main", revision: 3 }, null), false);
});

test("shortCommandType-takes-last-segment", () => {
  assert.equal(T.shortCommandType("unit.RenameUnit"), "RenameUnit");
  assert.equal(T.shortCommandType("map.SetTerrain"), "SetTerrain");
  assert.equal(T.shortCommandType(""), "?");
  assert.equal(T.shortCommandType(null), "?");
});

test("nextBranchName-picks-smallest-free-b-name", () => {
  assert.equal(T.nextBranchName(["main"]), "b2");
  assert.equal(T.nextBranchName(["main", "b2"]), "b3");
  assert.equal(T.nextBranchName(["main", "b2", "b3"]), "b4");
  assert.equal(T.nextBranchName(["b2", "b3"]), "b4");
});

test("orderedBranches-puts-main-first-then-lexicographic", () => {
  assert.deepEqual(T.orderedBranches(["b2", "main", "b1"]), ["main", "b1", "b2"]);
  assert.deepEqual(T.orderedBranches(["b2"]), ["b2"]);
  assert.deepEqual(T.orderedBranches([]), []);
});

test("tickIndex-finds-position", () => {
  const groups = [{ tick: 5 }, { tick: 6 }];
  assert.equal(T.tickIndex(groups, 5), 0);
  assert.equal(T.tickIndex(groups, 6), 1);
  assert.equal(T.tickIndex(groups, 9), -1);
});

// ── 逐日推进（★ 2026-09-24 日制裁定：1 tick = 1 天，Core 的 AdvanceTime 一次恰好一天）──────
//   "推进 N 天" = 逐日循环提交 N 次；每次 to = from + 1、expectedRevision 用**上一条**返回的新 revision。

/** 假 SimosApi.advance：记录每次调用，并按 responder(call, n) 作答（n 从 1 起）。 */
function advanceRecorder(responder) {
  const calls = [];
  const api = {
    advance(branch, expectedRevision, from, to) {
      const call = { branch, expectedRevision, from, to };
      calls.push(call);
      return responder(call, calls.length);
    },
  };
  return { api, calls };
}

test("advanceByDays-submits-one-day-per-call-and-chains-the-new-revision", async () => {
  const rec = advanceRecorder((call, n) =>
    Promise.resolve({ result: "committed", ref: { branch: "main", revision: 10 + n } })
  );
  const T2 = loadWebui("timeline.js", { SimosApi: rec.api }).SimosTimeline;
  const outcome = await T2.advanceByDays({
    branch: "main",
    expectedRevision: 9,
    fromDay: 5,
    days: 2,
  });
  assert.equal(rec.calls.length, 2, "N=2 ⇒ 恰好两次 advance 调用（逐日提交，不是一条 to=tick+2）");
  assert.equal(rec.calls[0].expectedRevision, 9, "第 1 天用当前 head");
  assert.equal(rec.calls[1].expectedRevision, 11, "第 2 天必须用第 1 天返回的新 revision（10+1）");
  rec.calls.forEach((c) => assert.equal(c.to, c.from + 1, "to 恒 = from + 1（每天恰好一天）"));
  assert.deepEqual(
    rec.calls.map((c) => c.from),
    [5, 6],
    "from 逐日推进"
  );
  assert.deepEqual(outcome, { ok: true, advancedDays: 2, stoppedAtDay: null, error: null });
});

test("advanceByDays-stops-immediately-and-reports-partial-progress", async () => {
  const rec = advanceRecorder((call, n) =>
    n === 1
      ? Promise.resolve({ result: "committed", ref: { branch: "main", revision: 10 } })
      : Promise.reject(Object.assign(new Error("被拒：区间不是恰好一天"), { status: 422 }))
  );
  const T2 = loadWebui("timeline.js", { SimosApi: rec.api }).SimosTimeline;
  const outcome = await T2.advanceByDays({
    branch: "main",
    expectedRevision: 9,
    fromDay: 5,
    days: 3,
  });
  assert.equal(rec.calls.length, 2, "第 2 天失败 ⇒ 立即停，第 3 天绝不再发");
  assert.equal(outcome.ok, false);
  assert.equal(outcome.advancedDays, 1, "只承认已提交的 1 天，不得报成全部 3 天");
  assert.equal(outcome.stoppedAtDay, 2, "停在第 2 天");
  const text = T2.advanceStatusText(outcome, 5, 3);
  assert.match(text, /已推进 1 天/);
  assert.match(text, /第 2 天停下/);
});

test("advanceStatusText-reports-server-reason-and-success-range", () => {
  const fail = {
    ok: false,
    advancedDays: 0,
    stoppedAtDay: 1,
    error: { body: { result: "rejected", reason: "区间不是恰好一天" } },
  };
  assert.equal(T.advanceStatusText(fail, 5, 2), "已推进 0 天、在第 1 天停下（区间不是恰好一天）");
  assert.equal(
    T.advanceStatusText({ ok: true, advancedDays: 2, stoppedAtDay: null, error: null }, 5, 2),
    "已推进 2 天（5 → 7）"
  );
});
