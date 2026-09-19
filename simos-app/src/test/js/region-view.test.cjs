// region-view.test.cjs —— M8 T9 区域查看模式：**区域高亮的共享路径** + 左栏"从属区域"读数。
//
// 两条护栏（计划 T9）：
//   ① "选区域 ⇒ 其它淡色" 在区域查看与区域编辑里**只有一份实现**（`buildRegionHighlightPlan`），
//      两模式的差别全部收敛成参数；且**焦点区域先入高亮集合**（`setHighlightHexes` 对同一 hex
//      "先者胜" —— T10 在真重叠数据上实测过：其它区域先入时，重叠的焦点格会被淡色盖掉）。
//   ② 左栏"该格从属区域"：每个区域给出**它自己的** `hexCount`，合计是**真并集**（逐 hex 去重）。
//      ★★ 裁定 72.1：绝不把各区域 `hexCount` 求和当并集/总面积（M8-U1：从属是多对多，重叠格会被
//      双重计数）。本文件里 `union !== sum` 的用例就是这条裁定的杀点。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

const M = loadWebui("map.js").SimosMap;
const P = loadWebui("panels.js").SimosPanels;

// 夹具：A/B 重叠于 (1,2)，C 与 B 重叠于 (1,3)——演示世界里恰好 3 格，这个形状可原样搬进 e2e。
const PALETTE = [
  { id: "t9_a", color: "#ff0000", hexes: [{ q: 1, r: 1 }, { q: 1, r: 2 }] },
  { id: "t9_b", color: "#0000ff", hexes: [{ q: 1, r: 2 }, { q: 1, r: 3 }] },
  { id: "t9_c", color: "#00ff00", hexes: [{ q: 1, r: 3 }] },
];

test("buildRegionHighlightPlan-puts-focus-regions-first", () => {
  // ★ 焦点区域（t9_b）在色板里**最后**，被它淡化的 t9_a 在它前面且与它重叠于 (1,2)。
  //   计划保留各区域自己的条目（**去重在渲染层：同一 hex 先者胜**），所以这里钉的是**次序**：
  //   焦点条目必须先出现 —— 否则 (1,2) 会被画成 t9_a 的淡色（T10 实测缺陷的回归位）。
  const plan = M.buildRegionHighlightPlan(PALETTE, ["t9_b"], M.REGION_VIEW_HIGHLIGHT);
  const shared = plan.entries.filter((e) => e.key === "1_2");
  assert.equal(shared.length, 2, "两个区域的重叠格 ⇒ 两条条目（渲染层先者胜，计划不做去重）");
  assert.deepEqual(
    shared[0],
    { key: "1_2", color: "#0000ff", alpha: M.REGION_VIEW_HIGHLIGHT.focusAlpha },
    "先入的必须是**焦点区域**（这一条就是「焦点先入」的断言）"
  );
  assert.deepEqual(shared[1], {
    key: "1_2",
    color: M.fadeRegionColor("#ff0000"),
    alpha: M.REGION_VIEW_HIGHLIGHT.fadeAlpha,
  });
  // 焦点区域自己的格全部原色；被淡化的区域自己的格全部淡色（同色相混合值 + 低透明度）。
  assert.deepEqual(plan.entries.filter((e) => e.color === "#0000ff").map((e) => e.key), ["1_2", "1_3"]);
  assert.deepEqual(
    plan.entries.filter((e) => e.alpha === M.REGION_VIEW_HIGHLIGHT.fadeAlpha).map((e) => [e.key, e.color]),
    [["1_1", M.fadeRegionColor("#ff0000")], ["1_2", M.fadeRegionColor("#ff0000")], ["1_3", M.fadeRegionColor("#00ff00")]]
  );
  assert.equal(plan.entries[0].key, "1_2", "焦点区条目在计划里先出现");
  assert.deepEqual(plan.focus, ["t9_b"]);
  assert.deepEqual(plan.faded, ["t9_a", "t9_c"]);
});

test("buildRegionHighlightPlan-highlights-every-owner-of-a-shared-hex", () => {
  // ★ 判据（T9）：重叠格点选 ⇒ 高亮**所有**从属区域，数量 == regions.length（>1）。
  const ids = ["t9_a", "t9_b"];
  const plan = M.buildRegionHighlightPlan(PALETTE, ids, M.REGION_VIEW_HIGHLIGHT);
  assert.deepEqual(plan.focus, ids, "两个从属区域都要被高亮（只高亮第一个 ⇒ 这条红）");
  assert.equal(plan.focus.length, ids.length);
  assert.ok(plan.focus.length > 1, "重叠格必须有 >1 个从属区域");
  // 每个焦点区域都拿原色、都进高亮集合：a 与 b 的 4 条条目全是原色（一个都不许被淡色顶掉）。
  const focusEntries = plan.entries.filter((e) => e.alpha === M.REGION_VIEW_HIGHLIGHT.focusAlpha);
  assert.deepEqual(
    focusEntries.map((e) => [e.key, e.color]).sort(),
    [["1_1", "#ff0000"], ["1_2", "#0000ff"], ["1_2", "#ff0000"], ["1_3", "#0000ff"]]
  );
  assert.equal(new Set(focusEntries.map((e) => e.color)).size, 2, "两个焦点区域各有自己的原色");
  // 未被选中的 t9_c 仍是淡色。
  assert.deepEqual(plan.faded, ["t9_c"]);
  assert.deepEqual(
    plan.entries.filter((e) => e.key === "1_3" && e.alpha === M.REGION_VIEW_HIGHLIGHT.fadeAlpha).length,
    1
  );
});

test("buildRegionHighlightPlan-region-view-without-focus-paints-nothing", () => {
  // 区域查看（M7 语义）：没选中 ⇒ 零填充、零边框（不是"全部涂淡"）。
  const empty = M.buildRegionHighlightPlan(PALETTE, [], M.REGION_VIEW_HIGHLIGHT);
  assert.deepEqual(empty.entries, []);
  assert.deepEqual(empty.outlines, []);
  assert.deepEqual(empty.faded, []);
  assert.equal(empty.focus.length, 0);
  // 状态里说高亮 X、但 X 不在本世界的色板里 ⇒ 同样什么都不画（不许退化成"全都淡色"）。
  const unknown = M.buildRegionHighlightPlan(PALETTE, ["不存在的区域"], M.REGION_VIEW_HIGHLIGHT);
  assert.deepEqual(unknown.entries, []);
  assert.deepEqual(unknown.faded, []);
});

test("buildRegionHighlightPlan-region-edit-without-focus-fades-everything", () => {
  // 区域编辑（T10 语义，重建后必须**逐值不变**）：无焦点 ⇒ 全部区域淡色（这就是"其它区域淡色"）。
  const plan = M.buildRegionHighlightPlan(PALETTE, [], M.REGION_EDIT_HIGHLIGHT);
  assert.deepEqual(plan.faded, ["t9_a", "t9_b", "t9_c"]);
  assert.equal(plan.entries.length, 5, "3 个区域共 5 条 hex 归属（重叠格算两次的是**条目**，并集仍是 3）");
  assert.equal(plan.entries.every((e) => e.alpha === M.REGION_EDIT_HIGHLIGHT.fadeAlpha), true);
  assert.equal(plan.entries.every((e) => e.alpha !== M.REGION_EDIT_HIGHLIGHT.focusAlpha), true);
  assert.equal(
    plan.entries.every((e) => /^#[0-9a-f]{6}$/.test(e.color)),
    true,
    "淡色必须是 #RRGGBB 混合值（不是把 alpha 调低就完事）"
  );
  // 焦点版：焦点原色更实、其它淡；边框同样一实一淡。
  const focused = M.buildRegionHighlightPlan(PALETTE, ["t9_a"], M.REGION_EDIT_HIGHLIGHT);
  assert.deepEqual(focused.focus, ["t9_a"]);
  assert.deepEqual(focused.faded, ["t9_b", "t9_c"]);
  assert.equal(focused.outlines[0].alpha, M.REGION_EDIT_HIGHLIGHT.focusOutlineAlpha);
  assert.equal(focused.outlines[1].alpha, M.REGION_EDIT_HIGHLIGHT.fadeOutlineAlpha);
  assert.equal(focused.outlines[0].color, "#ff0000");
  assert.equal(focused.outlines[1].color, M.fadeRegionColor("#0000ff"));
});

test("the-two-modes-share-one-plan-and-differ-only-in-parameters", () => {
  // ★ 两模式**各自独立可断言**：参数集存在、不同（焦点透明度 0.42 vs 0.52、无焦点时行为相反），
  //   而计划函数是同一个 —— "复制一份改一改"是这次要消灭的东西。
  assert.equal(typeof M.buildRegionHighlightPlan, "function");
  assert.notDeepEqual(M.REGION_VIEW_HIGHLIGHT, M.REGION_EDIT_HIGHLIGHT);
  assert.equal(M.REGION_VIEW_HIGHLIGHT.focusAlpha, 0.42);
  assert.equal(M.REGION_EDIT_HIGHLIGHT.focusAlpha, 0.52);
  assert.equal(M.REGION_VIEW_HIGHLIGHT.fadeAlpha, M.REGION_EDIT_HIGHLIGHT.fadeAlpha);
  assert.equal(M.REGION_VIEW_HIGHLIGHT.fadeWhenNoFocus, false);
  assert.equal(M.REGION_EDIT_HIGHLIGHT.fadeWhenNoFocus, true);
});

test("page-层-uses-the-shared-plan-for-both-modes", () => {
  // ★ 源代码级自证：两个模式的宿主入口**都**经过 `reloadRegionHighlight`（不许各写一份）。
  //   否则变异体能杀掉纯函数、却杀不掉页面行为 —— 断言就成了装饰（T11 的同型纪律）。
  const source = readWebui("map.js");
  assert.ok(source.indexOf("reloadRegionHighlight(ids, REGION_VIEW_HIGHLIGHT)") >= 0, "区域查看要走共享路径");
  assert.ok(
    source.indexOf("reloadRegionHighlight(focus ? [focus] : [], REGION_EDIT_HIGHLIGHT)") >= 0,
    "区域编辑要走共享路径"
  );
  // 反向：不许留下第二份"焦点区自己算颜色/透明度"的实现。
  assert.equal(
    (source.match(/fadeRegionColor\(base\)/g) || []).length,
    1,
    "淡色只在共享计划里算一次（多出来就是又复制了一份）"
  );
  // 区域编辑原来的内联实现（`region.id === focus` 那句排序）必须已经不在了。
  assert.equal(source.indexOf("if (region && region.id === focus)"), -1, "区域编辑不得再自写一份焦点排序");
});

test("regionMembershipSummary-counts-each-region-itself-and-unions-the-hexes", () => {
  // ★★ 裁定 72.1 的杀点：A(2 格) + B(2 格) + C(1 格) = 求和 5，**并集只有 3**（(1,2)、(1,3) 各被两区共有）。
  const summary = P.regionMembershipSummary([
    { id: "t9_a", name: "A", hexCount: 2, hexes: [{ q: 1, r: 1 }, { q: 1, r: 2 }] },
    { id: "t9_b", name: "B", hexCount: 2, hexes: [{ q: 1, r: 2 }, { q: 1, r: 3 }] },
    { id: "t9_c", name: "C", hexCount: 1, hexes: [{ q: 1, r: 3 }] },
  ]);
  assert.deepEqual(
    summary.rows.map((r) => [r.id, r.hexCount]),
    [["t9_a", 2], ["t9_b", 2], ["t9_c", 1]],
    "每个区域报**它自己的** hexCount（不合并不改写）"
  );
  assert.equal(summary.unionCount, 3, "合计 = 真并集（逐 hex 去重）");
  assert.equal(summary.hexCountSum, 5, "各区域 hexCount 之和（5）");
  assert.notEqual(summary.unionCount, summary.hexCountSum, "★ 重叠时并集必然不等于求和");
  assert.equal(summary.sharedHexCount, 2, "被两个及以上区域共有的格数");
  assert.equal(summary.complete, true);
});

test("regionMembershipSummary-keeps-three-owners-as-one-hex", () => {
  // M8-U1：一个 hex 可以同时属于**多个**区域（>2 也是正常的）⇒ 并集里仍然只算一格。
  const summary = P.regionMembershipSummary([
    { id: "r1", hexCount: 1, hexes: [{ q: 5, r: 5 }] },
    { id: "r2", hexCount: 1, hexes: [{ q: 5, r: 5 }] },
    { id: "r3", hexCount: 1, hexes: [{ q: 5, r: 5 }] },
  ]);
  assert.equal(summary.unionCount, 1);
  assert.equal(summary.hexCountSum, 3);
  assert.equal(summary.sharedHexCount, 1);
});

test("regionMembershipSummary-refuses-to-guess-without-hex-lists", () => {
  // 拉不到某个区域的 hex 列表 ⇒ **不给数字**（宁可说"不知道"，也不拿求和顶替）。
  const partial = P.regionMembershipSummary([
    { id: "t9_a", hexCount: 2, hexes: [{ q: 1, r: 1 }, { q: 1, r: 2 }] },
    { id: "t9_b", hexCount: 2, hexes: null },
  ]);
  assert.equal(partial.complete, false);
  assert.equal(partial.unionCount, null);
  assert.equal(partial.sharedHexCount, null);
  assert.equal(partial.rows[1].hexCount, 2, "拿不到 hex 列表时，它自己的 hexCount 仍照报");
  // 空输入不是崩溃、也不是"0 格并集"（没有区域 ⇒ 没有合计可言）。
  const none = P.regionMembershipSummary([]);
  assert.deepEqual(none.rows, []);
  assert.equal(none.unionCount, 0);
  assert.equal(none.hexCountSum, 0);
  assert.equal(none.complete, true);
});

test("page-层-shows-the-union-and-never-a-summed-total", () => {
  // ★ 源代码级自证：左栏读数走这个纯函数，且 DOM 里**只**写并集；反向禁止出现求和读数。
  const source = readWebui("panels.js");
  assert.ok(source.indexOf("regionMembershipSummary(regions)") >= 0, "左栏要走 regionMembershipSummary");
  assert.ok(source.indexOf('"data-metric": "union"') >= 0, "合计行要自报口径（union）");
  // 反向：**写 DOM 的那个函数**里不许出现求和值（纯函数里算出求和、只为核对"和 ≠ 并集"是可以的）。
  const writer = source.slice(
    source.indexOf("function appendRegionMembership"),
    source.indexOf("function renderRegionMembership")
  );
  assert.ok(writer.length > 0, "取不到 appendRegionMembership 的源码 —— 断言不许变成空==空");
  assert.equal(writer.indexOf("hexCountSum"), -1, "求和值不许进 DOM（裁定 72.1）");
  assert.ok(writer.indexOf("unionCount") >= 0, "写进 DOM 的必须是并集");
  assert.equal(writer.indexOf("总面积"), -1, "写 DOM 的地方不许出现「总面积」这种把求和当面积的措辞");
});

test("debug-hooks-for-the-region-view-mode-are-exposed", () => {
  // e2e 的断言全靠这两个只读投影（淡色态不靠肉眼）：少了它们，e2e 就只能"看起来淡了"。
  assert.equal(typeof M.regionViewDebug, "function");
  assert.equal(typeof M.regionHighlightAt, "function");
  assert.equal(typeof P.regionMembershipSummary, "function");
});
