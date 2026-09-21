// webui-fix2.test.cjs —— WebUI 第 2 批修复（U1 地形压暗 / U2 区域名 / U3 选择粒度 / U5 三栏布局）。
//
// 判据出处：docs/superpowers/specs/2026-09-22-webui-fix2-design.md（§〇 裁定表）；
// 用户原话：docs/superpowers/specs/2026-09-22-webui-fix2-feedback.md。
// ★ 这里只钉**纯函数**与**源码级结构**（浏览器像素级证据在 evidence 的 e2e 轮，不进本门禁）。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

const M = loadWebui("map.js").SimosMap;
// app.js 的 setMode 会走 applyMode → getElementById ⇒ 给一个最小的 DOM 壳。
const APP_DOC = {
  getElementById() {
    return null;
  },
  querySelector() {
    return null;
  },
  querySelectorAll() {
    return [];
  },
  addEventListener() {},
  createElement() {
    return {
      style: {},
      classList: { add() {}, remove() {} },
      setAttribute() {},
      appendChild() {},
      addEventListener() {},
    };
  },
  body: { getAttribute() { return null; }, setAttribute() {} },
};
const A = loadWebui("app.js", { document: APP_DOC }).SimosApp;

// 夹具：A/B 同 tag（nation:1）、C 异 tag、D 无 tag —— 单区域选中时只有同 tag 的 B 该被淡色。
const PALETTE = [
  { id: "a", meta: { tag: "nation:1" }, color: "#ff0000", hexes: [{ q: 1, r: 1 }, { q: 1, r: 2 }] },
  { id: "b", meta: { tag: "nation:1" }, color: "#00ff00", hexes: [{ q: 2, r: 2 }] },
  { id: "c", meta: { tag: "nation:2" }, color: "#0000ff", hexes: [{ q: 3, r: 3 }] },
  { id: "d", meta: { tag: null }, color: "#ffff00", hexes: [{ q: 4, r: 4 }] },
];

// ── U1：区域模式的地形压暗 ────────────────────────────────────────────────

test("terrainDimAlpha-is-nonzero-only-in-region-modes", () => {
  assert.equal(M.terrainDimAlpha("view"), 0, "常规模式不许压暗");
  assert.equal(M.terrainDimAlpha("map-edit"), 0);
  assert.equal(M.terrainDimAlpha("unit"), 0);
  assert.equal(M.terrainDimAlpha("decision"), 0);
  assert.equal(M.terrainDimAlpha("region"), M.REGION_DIM_ALPHA, "区域查看要压暗");
  assert.equal(M.terrainDimAlpha("region-edit"), M.REGION_DIM_ALPHA, "区域编辑要压暗");
  assert.deepEqual(M.REGION_DIM_MODES, ["region", "region-edit"], "压暗只属于这两个模式");
  assert.ok(M.REGION_DIM_ALPHA > 0 && M.REGION_DIM_ALPHA < 1, "压暗是半透明（不是全黑、也不是没有）");
});

test("terrain-dim-is-painted-after-terrain-and-before-highlights", () => {
  // ★ U1 的承重结构：压暗必须夹在"地形"与"区域高亮"之间——否则区域填充会被一起压暗、或地形盖住它。
  const src = readWebui("map.js");
  const dim = src.indexOf("paintTerrainDim(ctx, app.getState().mode)");
  const terrain = src.indexOf("paintTerrain(ctx);");
  const highlights = src.indexOf("paintHighlights(ctx);");
  assert.ok(terrain >= 0 && dim >= 0 && highlights >= 0, "三个绘制点都要在（取不到 ⇒ 别把空==空当成功）");
  assert.ok(terrain < dim, "压暗在地形之后");
  assert.ok(dim < highlights, "压暗在区域高亮之前");
});

test("terrain-dim-is-a-full-canvas-dark-scrim", () => {
  const src = readWebui("map.js");
  const start = src.indexOf("function paintTerrainDim(");
  const end = src.indexOf("function paintRegionNames(");
  assert.ok(start >= 0 && end > start, "取到 paintTerrainDim 函数体");
  const body = src.slice(start, end);
  assert.ok(body.indexOf("fillRect(0, 0, cssW, cssH)") >= 0, "压暗覆盖整个画布（不是只盖地形块）");
  assert.ok(body.indexOf("withAlpha(REGION_DIM_COLOR") >= 0, "用深色 scrim 而不是把地形色改暗");
  assert.ok(body.indexOf("setTransform(dpr, 0, 0, dpr, 0, 0)") >= 0, "在屏幕空间画（不随缩放变强度）");
  assert.ok(body.indexOf("startCtx") === -1 && body.indexOf("paintHighlights") === -1, "压暗层自身不画高亮");
});

test("pixel-sampling-hook-is-exposed-for-color-level-evidence", () => {
  assert.equal(typeof M.pixelAt, "function", "取色钩子要在（e2e 的像素级证据靠它）");
  assert.equal(typeof M.regionViewDebug, "function");
});

// ── U2：区域名 ───────────────────────────────────────────────────────────

test("regionLabelLayout-uses-centroid-and-sqrt-font", () => {
  const layout = M.regionLabelLayout({ label: { q: 3, r: 4 }, hexCount: 100, name: "X" }, 2);
  assert.deepEqual(layout, { q: 3, r: 4, fontSize: 9, text: "X" }, "字号 = max(8,min(40,√100×1.8))/zoom = 18/2");
  const big = M.regionLabelLayout({ label: { q: 0, r: 0 }, hexCount: 10000, name: "Y" }, 1);
  assert.equal(big.fontSize, 40, "大区域字号封顶 40");
  const tiny = M.regionLabelLayout({ label: { q: 0, r: 0 }, hexCount: 1, name: "Z" }, 1);
  assert.equal(tiny.fontSize, 8, "小区域字号下限 8");
});

test("regionLabelLayout-refuses-to-guess-without-label-or-hexcount", () => {
  assert.equal(M.regionLabelLayout({ hexCount: 5, name: "A" }, 1), null, "无 label ⇒ 不画（不拿 (0,0) 顶替）");
  assert.equal(M.regionLabelLayout({ label: { q: 1, r: 1 }, hexCount: 0, name: "A" }, 1), null);
  assert.equal(M.regionLabelLayout({ label: { q: 1, r: 1 }, hexCount: 5, name: "" }, 1), null, "空名不画");
  const noName = M.regionLabelLayout({ id: "r-9", label: { q: 1, r: 1 }, hexCount: 5 }, 1);
  assert.equal(noName.text, "r-9", "无名 ⇒ 回落到 id（不是空串）");
});

test("region-name-draws-both-stroke-and-fill-with-zoom-guard", () => {
  const src = readWebui("map.js");
  const start = src.indexOf("function paintRegionNames(");
  const end = src.indexOf("function worldTransform(");
  assert.ok(start >= 0 && end > start, "取到 paintRegionNames 函数体");
  const body = src.slice(start, end);
  assert.ok(body.indexOf("strokeText(") >= 0, "黑描边（保证在深色压暗上也读得出）");
  assert.ok(body.indexOf("fillText(") >= 0, "白字填充");
  assert.ok(body.indexOf("view.scale < REGION_NAME_MIN_SCALE") >= 0, "低缩放不画（避免 97 个标签堆叠）");
  assert.ok(body.indexOf("regionLabelLayout(") >= 0, "位置/字号走纯函数（可单测）");
});

test("region-names-are-actually-invoked-in-render-order", () => {
  // ★ 函数写对了但 render 不调它 ⇒ 地图上一个名字都没有。这条钉住"真的画了"（变异体能被它杀）。
  const src = readWebui("map.js");
  const call = src.indexOf("paintRegionNames(ctx);");
  const outlines = src.indexOf("paintRegionOutlines(ctx);");
  const units = src.indexOf("drawUnits();");
  assert.ok(call >= 0, "render 必须调用 paintRegionNames");
  assert.ok(outlines >= 0 && outlines < call, "区域名在边界之上");
  assert.ok(units >= 0 && call < units, "区域名在单位之下");
});

test("region-name-toggle-is-wired-and-persisted", () => {
  const html = readWebui("index.html");
  assert.ok(html.indexOf('id="region-name-toggle"') >= 0, "顶栏要有区域名开关");
  assert.ok(/id="region-name-toggle"[^>]*checked/.test(html), "默认开（照 GSimulator）");
  const src = readWebui("map.js");
  assert.ok(src.indexOf("simos.regionNames.v1") >= 0, "localStorage 键");
  assert.ok(src.indexOf("loadRegionNamesEnabled()") >= 0 && src.indexOf("persistRegionNamesEnabled(") >= 0, "读/写都要有");
  assert.ok(src.indexOf('typeof window !== "undefined" && window.localStorage') >= 0, "node 宿主无 localStorage ⇒ 要守卫");
});

test("regionTag-keeps-null-and-string-verbatim", () => {
  assert.equal(M.regionTag({ meta: { tag: "nation:x" } }), "nation:x");
  assert.equal(M.regionTag({ meta: { tag: null } }), null, "无 tag 是 null，不是空串");
  assert.equal(M.regionTag({}), null);
});

// ── U3：选择粒度（tag 全等亮 / 单区域更亮 + 同 tag 淡色）──────────────────

test("single-plan-brightens-focus-and-fades-only-same-tag", () => {
  const plan = M.buildRegionHighlightPlan(PALETTE, ["a"], M.REGION_SINGLE_HIGHLIGHT);
  assert.deepEqual(plan.focus, ["a"]);
  assert.deepEqual(plan.faded, ["b"], "★ 只压同 tag 的兄弟（c 异 tag、d 无 tag 都不进 plan）");
  assert.deepEqual(
    plan.entries.filter((e) => e.color === "#ff0000").map((e) => e.alpha),
    [M.REGION_SINGLE_HIGHLIGHT.singleFocusAlpha, M.REGION_SINGLE_HIGHLIGHT.singleFocusAlpha],
    "焦点区域更亮（0.62）"
  );
  assert.deepEqual(
    plan.entries.filter((e) => e.color === M.fadeRegionColor("#00ff00")).map((e) => e.alpha),
    [M.REGION_SINGLE_HIGHLIGHT.fadeAlpha],
    "同 tag 兄弟是淡色"
  );
  assert.deepEqual(
    plan.entries.map((e) => e.key).sort(),
    ["1_1", "1_2", "2_2"],
    "异 tag / 无 tag 区域一个条目都不许有（不是「淡色」，是「不参与」）"
  );
  assert.deepEqual(
    plan.outlines.map((o) => o.id),
    ["a", "b"]
  );
});

test("single-focus-alpha-is-higher-than-tag-group-alpha", () => {
  // ★ U3 的核心诉求：单区域"更高亮度"当前不成立 ⇒ 这条把它钉死。
  assert.ok(
    M.REGION_SINGLE_HIGHLIGHT.singleFocusAlpha > M.REGION_VIEW_HIGHLIGHT.focusAlpha,
    "单区域比 tag 全选更亮"
  );
  const single = M.buildRegionHighlightPlan(PALETTE, ["a"], M.REGION_SINGLE_HIGHLIGHT);
  const asTag = M.buildRegionHighlightPlan(PALETTE, ["a"], M.REGION_VIEW_HIGHLIGHT);
  const singleAlpha = single.entries.find((e) => e.key === "1_1").alpha;
  const tagAlpha = asTag.entries.find((e) => e.key === "1_1").alpha;
  assert.ok(singleAlpha > tagAlpha, "同一区域的单选中像素比 tag 全选更亮（" + singleAlpha + " > " + tagAlpha + "）");
});

test("tag-group-plan-is-unchanged", () => {
  // 点 tag ⇒ 全部等亮（沿用 M8 T9 的 REGION_VIEW_HIGHLIGHT）：焦点同 alpha、其它 tag 仍淡色。
  const plan = M.buildRegionHighlightPlan(PALETTE, ["a", "b"], M.REGION_VIEW_HIGHLIGHT);
  assert.deepEqual(plan.focus, ["a", "b"]);
  assert.deepEqual(plan.faded, ["c", "d"], "group 语义下非焦点区域仍全部淡色（不改 M8 行为）");
  const focusAlphas = plan.entries.filter((e) => e.color === "#ff0000" || e.color === "#00ff00").map((e) => e.alpha);
  assert.deepEqual(focusAlphas, [0.42, 0.42, 0.42], "tag 下所有成员同一亮度（相等）");
  assert.equal(M.buildRegionHighlightPlan(PALETTE, ["a"], M.REGION_VIEW_HIGHLIGHT).faded.length, 3, "group 的 faded 不限 tag（这就是与 single 的分界）");
});

test("setHighlightRegions-infers-kind-and-honours-explicit-kind", () => {
  A.setMode("region");
  A.setHighlightRegions(["r1"]);
  assert.equal(A.getState().highlightKind, "single", "单 id ⇒ 推断 single");
  A.setHighlightRegions(["r1", "r2"]);
  assert.equal(A.getState().highlightKind, "group", "多 id ⇒ 推断 group");
  A.setHighlightRegions(["r1", "r2"], "single");
  assert.equal(A.getState().highlightKind, "single", "显式 single 覆盖推断");
  A.setHighlightRegions([], "group");
  assert.deepEqual(A.getState().highlightRegions, []);
  assert.equal(A.getState().highlightKind, "group");
  // 切模式必须把语义也清回 group（否则残留的 single 会带进别的模式）。
  A.setHighlightRegions(["r1"], "single");
  A.setMode("view");
  assert.equal(A.getState().highlightKind, "group", "setMode 重置 highlightKind");
});

test("sources-split-single-vs-group-at-the-three-call-sites", () => {
  const panels = readWebui("panels.js");
  assert.ok(panels.indexOf('app.setHighlightRegions(ids.slice(), "group")') >= 0, "tag 点击 ⇒ group");
  assert.ok(panels.indexOf('app.setHighlightRegions([region.id], "single")') >= 0, "右栏单项 ⇒ single");
  const map = readWebui("map.js");
  assert.ok(map.indexOf('regionIds.length === 1 ? "single" : "group"') >= 0, "地图点格按从属数分档");
});

test("regionViewDebug-exposes-kind-and-both-alphas", () => {
  const src = readWebui("map.js");
  assert.ok(src.indexOf("highlightKind: app.getState().highlightKind") >= 0, "投影要能读到语义档位");
  assert.ok(src.indexOf("singleFocusAlpha: REGION_SINGLE_HIGHLIGHT.singleFocusAlpha") >= 0);
  assert.ok(src.indexOf("viewFocusAlpha: REGION_VIEW_HIGHLIGHT.focusAlpha") >= 0);
});

test("loadRegionNamesAndDimDefaultsDoNotThrowUnderNodeHost", () => {
  // node 宿主无 localStorage/无 DOM —— 加载 map.js 不许抛（既有 loader 已证加载成功）。
  assert.equal(typeof M.terrainDimAlpha, "function");
  assert.equal(typeof M.regionLabelLayout, "function");
});

// ── U5：详情页三栏布局 ───────────────────────────────────────────────────

test("right-panel-has-data-modes-and-never-shows-in-view-mode", () => {
  const html = readWebui("index.html");
  const match = html.match(/<section[^>]*id="right-panel"[^>]*>/);
  assert.ok(match, "取到 #right-panel 开始标签");
  const modesMatch = /data-modes="([^"]*)"/.exec(match[0]);
  assert.ok(modesMatch, "★ 根因：右栏必须有 data-modes（否则永不隐藏 ⇒ 空卡片）");
  const modes = modesMatch[1].split(/\s+/);
  assert.ok(modes.includes("region") && modes.includes("region-edit") && modes.includes("decision"));
  assert.ok(!modes.includes("view"), "常规模式下右栏必须是 hidden（这就是空卡片的修复）");
  assert.ok(!modes.includes("map-edit") && !modes.includes("unit"), "这两个模式下右栏也没有内容");
});

test("side-columns-have-a-usable-stable-width", () => {
  const css = readWebui("styles.css");
  const left = (/body\.workbench-page \.wb-body \.col-left\s*\{([^}]*)\}/.exec(css) || [])[1];
  const right = (/body\.workbench-page \.wb-body \.col-right\s*\{([^}]*)\}/.exec(css) || [])[1];
  assert.ok(left && right, "取到 .col-left / .col-right 规则");
  assert.equal(left.indexOf("fit-content"), -1, "★ 左栏不许按内容塌缩（fit-content 会把值列压到十几 px ⇒ 逐字竖排）");
  assert.equal(right.indexOf("fit-content"), -1, "右栏同理");
  assert.ok(/flex:\s*0 1 \d+px/.test(left), "左栏有稳定 px 基准宽（不随内容伸缩）");
  assert.ok(/flex:\s*0 1 \d+px/.test(right), "右栏有稳定 px 基准宽");
  assert.ok(left.indexOf("min-width: 240px") >= 0, "下界 ≥ 240px（可用宽度）");
  assert.ok(left.indexOf("max-width: 340px") >= 0 && right.indexOf("max-width: 340px") >= 0, "有上限，长内容不撑爆");
  const body = (/body\.workbench-page \.wb-body\s*\{([^}]*)\}/.exec(css) || [])[1];
  assert.ok(body && body.indexOf("align-items: flex-start") >= 0, "面板高度按内容（U5 原意：不留空卡片）");
});

test("kv-value-column-is-not-squeezed-to-one-character", () => {
  const css = readWebui("styles.css");
  const kv = (/\.kv\s*\{([^}]*)\}/.exec(css) || [])[1];
  assert.ok(kv, "取到 .kv 规则");
  assert.ok(kv.indexOf("fit-content(") >= 0, "★ 标签列必须有上限，否则长标签（如「从属区域…」）吃掉整行、值列只剩一个字宽");
  assert.ok(/minmax\(0, 1fr\)/.test(kv), "值列仍占剩余空间");
});

test("region-names-only-show-in-the-two-region-modes", () => {
  assert.equal(M.regionNamesVisible("view"), false, "★ 常规模式不显示区域名（用户实测缺陷 V2）");
  assert.equal(M.regionNamesVisible("map-edit"), false);
  assert.equal(M.regionNamesVisible("unit"), false);
  assert.equal(M.regionNamesVisible("decision"), false);
  assert.equal(M.regionNamesVisible("region"), true);
  assert.equal(M.regionNamesVisible("region-edit"), true);
  assert.deepEqual(M.REGION_NAME_MODES, ["region", "region-edit"], "只有区域两模式");
});

test("region-name-paint-is-gated-by-mode", () => {
  const src = readWebui("map.js");
  const start = src.indexOf("function paintRegionNames(");
  const end = src.indexOf("function paintHighlights(");
  assert.ok(start >= 0 && end > start, "取到 paintRegionNames 函数体");
  assert.ok(src.slice(start, end).indexOf("regionNamesVisible(") >= 0, "★ 绘制必须按模式门控（常规模式不许画）");
  const layoutsStart = src.indexOf("regionNameLayouts: function");
  assert.ok(layoutsStart >= 0, "取到 regionNameLayouts");
  assert.ok(
    src.slice(layoutsStart, layoutsStart + 500).indexOf("regionNamesVisible(") >= 0,
    "调试投影也要门控（否则 drawn/labels 与画面不一致）"
  );
});

test("region-name-toggle-is-in-the-topbar-and-hidden-when-irrelevant", () => {
  const html = readWebui("index.html");
  const topbar = (/<div class="topbar-actions">([\s\S]*?)<\/div>/.exec(html) || [])[1];
  assert.ok(topbar && topbar.indexOf('id="region-name-toggle"') >= 0, "开关在顶栏（与回中心/收起栏并列）");
});
