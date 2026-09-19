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
