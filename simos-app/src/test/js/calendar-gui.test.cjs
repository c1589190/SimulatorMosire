// calendar-gui.test.cjs —— C7b（D-020）：年份系统 GUI 的纯函数 + 两宿主页静态接线。
//
// 依据：设计稿 §八（GUI/D-020）与 §十 第 7 条；被测对象 = C6b 已落地的生产代码
//   `timeline.js`（formatCalendarDate / groupByTick / .tl-date / dateByRevision）、
//   `panels.js` 与 `map-hostpage.js`（有意复制的 hexSeasonText / zoneReadableName，两份都测）。
//
// 纪律：不启服务、不改生产代码；旧后端（无 date/season）路径用纯函数 + 静态证据表达，不编造。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

// 两宿主页的 `app.text` 口径（app.js）：null/undefined ⇒ "—"，其余 String(value)。
// hexSeasonText 只用到这一个宿主方法 ⇒ 其余 SimosApp 面不必注入。
const APP_STUB = { text: (value) => (value === null || value === undefined ? "—" : String(value)) };

const T = loadWebui("timeline.js").SimosTimeline;
const P = loadWebui("panels.js", { SimosApp: APP_STUB }).SimosPanels;
const H = loadWebui("map-hostpage.js", { SimosApp: APP_STUB }).SimosMapHostPage;

// panels.js = 工作台（index.html）；map-hostpage.js = 旧页（map.html）。两份实现各测一遍。
const HOST_PAGES = [
  ["panels.js", P],
  ["map-hostpage.js", H],
];

// ── formatCalendarDate（timeline.js 导出的纯函数；前端不重算历法，只格式化服务端 date 对象）──

test("formatCalendarDate-formats-year-month-day-and-does-not-pad-year", () => {
  assert.equal(typeof T.formatCalendarDate, "function", "formatCalendarDate 必须挂在 window.SimosTimeline 上");
  assert.equal(
    T.formatCalendarDate({ calendar: "julian", year: 1445, month: 5, day: 1, dayOfYear: 121 }),
    "1445-05-01",
    "tick 120 的锚点日期"
  );
  assert.equal(T.formatCalendarDate({ year: 45, month: 5, day: 1 }), "45-05-01", "年按原值输出、不补足 4 位");
  assert.equal(T.formatCalendarDate({ year: 1445, month: 12, day: 9 }), "1445-12-09", "月/日各补两位");
});

test("formatCalendarDate-null-non-object-and-missing-components-return-empty", () => {
  const bad = [
    null,
    undefined,
    1445,
    "1445-05-01",
    [],
    {},
    { year: 1445 },
    { year: 1445, month: 5 },
    { year: null, month: 5, day: 1 },
    { year: 1445, month: undefined, day: 1 },
    { year: 1445, month: 5, day: null },
    { year: "x", month: 5, day: 1 },
  ];
  for (const value of bad) {
    assert.equal(T.formatCalendarDate(value), "", JSON.stringify(value) + " ⇒ 不编造日期串");
  }
});

// ── groupByTick 的日期传播（同 tick 多条 revision 同日；不同日以第一条为准但留痕）──

test("groupByTick-same-tick-same-date-keeps-first-object-and-one-date-string", () => {
  assert.equal(typeof T.groupByTick, "function", "groupByTick 必须导出");
  const day = { calendar: "julian", year: 1445, month: 5, day: 1 };
  const sameDay = { calendar: "julian", year: 1445, month: 5, day: 1 };
  const groups = T.groupByTick([
    { tick: 120, revision: 7, commandType: "unit.PlanRoute", date: day },
    { tick: 120, revision: 8, commandType: "map.SetTerrain", date: sameDay },
  ]);
  assert.equal(groups.length, 1);
  assert.equal(groups[0].nodes.length, 2);
  assert.strictEqual(groups[0].date, day, "同日 ⇒ date 取第一条的**对象**（不覆盖、不新建）");
  assert.deepEqual(groups[0].dates, ["1445-05-01"], "dates 恰好 1 条日期串（同日不重复记）");
});

test("groupByTick-without-date-stays-null-with-empty-dates", () => {
  const groups = T.groupByTick([
    { tick: 9, revision: 1, commandType: "unit.PlanRoute" },
    { tick: 9, revision: 2, commandType: "unit.PlanRoute", date: null },
  ]);
  assert.strictEqual(groups[0].date, null, "旧后端无 date ⇒ null（不编造）");
  assert.deepEqual(groups[0].dates, [], "无 date ⇒ dates 为空");
});

test("groupByTick-mixed-dates-keeps-first-and-lists-every-distinct-string", () => {
  const first = { calendar: "julian", year: 1445, month: 5, day: 1 };
  const second = { calendar: "julian", year: 1445, month: 5, day: 2 };
  const groups = T.groupByTick([
    { tick: 3, revision: 1, commandType: "a.X", date: first },
    { tick: 3, revision: 2, commandType: "a.Y", date: second },
    // 第三条与第一条同日但不是同一个对象 ⇒ dates 按**串去重**，仍只两条。
    { tick: 3, revision: 3, commandType: "a.Z", date: { year: 1445, month: 5, day: 1 } },
  ]);
  assert.strictEqual(groups[0].date, first, "偶发不同日期仍以第一条为准");
  assert.deepEqual(groups[0].dates, ["1445-05-01", "1445-05-02"], "dates 如实列出全部不同日期串（留痕）");
});

// ── hexSeasonText / zoneReadableName（panels.js 与 map-hostpage.js 各一份）──

test("hexSeasonText-missing-season-is-explicit-on-both-host-pages", () => {
  for (const [label, NS] of HOST_PAGES) {
    assert.equal(typeof NS.hexSeasonText, "function", label + " 必须导出 hexSeasonText");
    assert.equal(NS.hexSeasonText(null), "—（后端未提供季节）", label);
    assert.equal(NS.hexSeasonText(undefined), "—（后端未提供季节）", label);
    assert.equal(NS.hexSeasonText({}), "—（后端未提供季节）", label + "：连 name 都没有 ⇒ 同形，不编造季节名");
  }
});

test("hexSeasonText-fallback-zone-source-uses-north-hemisphere-wording-on-both", () => {
  for (const [label, NS] of HOST_PAGES) {
    assert.equal(
      NS.hexSeasonText({ name: "夏", zoneSource: "fallback" }),
      "夏（未配置分带，按北半球四季）",
      label + "：未配置分带 ⇒ fallback 文案"
    );
  }
});

test("hexSeasonText-full-season-shows-zone-day-range-and-percent-on-both", () => {
  const season = {
    phase: "summer",
    key: "SUMMER",
    name: "夏",
    dayOfSeason: 6,
    daysInSeason: 95,
    // 530‰ = 53%（设计稿 §八 的例值；progressPerMille 是千分比，见下一用例）。
    progressPerMille: 530,
    zone: "NORTH_TEMPERATE",
    zoneSource: "store",
  };
  const expected = "夏（北温带 · 第 6/95 天 · 53%）";
  for (const [label, NS] of HOST_PAGES) {
    const text = NS.hexSeasonText(season);
    assert.equal(text, expected, label + "：逐字等于设计稿 §八 的示例文案");
    assert.match(text, /北温带/, label);
    assert.match(text, /第 6\/95 天/, label);
    assert.match(text, /53%/, label);
    assert.equal(
      NS.hexSeasonText(Object.assign({}, season, { zoneSource: "default" })),
      expected,
      label + "：zoneSource=default 与 store 同显示（只有 fallback 特判）"
    );
  }
});

test("hexSeasonText-rounds-per-mille-to-integer-percent-on-both", () => {
  // ★ progressPerMille 是千分比（ApiViews.progressPerMille = round(progress × 1000)）：
  //   53‰ = 5.3% ⇒ 四舍五入 5%；530‰ = 53%。
  //   （派单样例把 53 写成了期望 53%；按真实口径钉住，差异已写进 C7b 报告，未改生产代码。）
  const base = {
    name: "夏",
    zone: "NORTH_TEMPERATE",
    dayOfSeason: 6,
    daysInSeason: 95,
    zoneSource: "store",
  };
  for (const [label, NS] of HOST_PAGES) {
    assert.equal(
      NS.hexSeasonText(Object.assign({}, base, { progressPerMille: 53 })),
      "夏（北温带 · 第 6/95 天 · 5%）",
      label + "：53‰ ⇒ 5.3% ⇒ 5%"
    );
    assert.equal(
      NS.hexSeasonText(Object.assign({}, base, { progressPerMille: 530 })),
      "夏（北温带 · 第 6/95 天 · 53%）",
      label + "：530‰ ⇒ 53%"
    );
  }
});

test("hexSeasonText-missing-progress-does-not-fabricate-zero-percent-on-both", () => {
  const base = {
    name: "夏",
    zone: "NORTH_TEMPERATE",
    dayOfSeason: 6,
    daysInSeason: 95,
    zoneSource: "store",
  };
  for (const [label, NS] of HOST_PAGES) {
    const missing = NS.hexSeasonText(base);
    const nulled = NS.hexSeasonText(Object.assign({}, base, { progressPerMille: null }));
    assert.equal(missing, "夏（北温带 · 第 6/95 天 · —）", label + "：缺失 ⇒ —");
    assert.equal(nulled, "夏（北温带 · 第 6/95 天 · —）", label + "：null ⇒ —");
    assert.equal(/0%/.test(missing), false, label + "：缺失不许显示成 0%");
    // 真实 0‰ 才显示 0%（与"缺失"区分开）。
    assert.equal(
      NS.hexSeasonText(Object.assign({}, base, { progressPerMille: 0 })),
      "夏（北温带 · 第 6/95 天 · 0%）",
      label + "：真实 0‰"
    );
  }
});

test("zoneReadableName-maps-known-zones-and-passes-unknown-through-on-both", () => {
  for (const [label, NS] of HOST_PAGES) {
    assert.equal(typeof NS.zoneReadableName, "function", label + " 必须导出 zoneReadableName");
    assert.equal(NS.zoneReadableName("NORTH_TEMPERATE"), "北温带", label);
    assert.equal(NS.zoneReadableName("TROPICS"), "赤道附近", label);
    assert.equal(NS.zoneReadableName("SOUTH_TEMPERATE"), "南温带", label);
    assert.equal(NS.zoneReadableName("ARCTIC_CIRCLE"), "ARCTIC_CIRCLE", label + "：未知 zone 原样，不编造中文名");
    assert.equal(NS.zoneReadableName(null), "—", label + "：缺失 zone 走 app.text ⇒ —");
    assert.equal(
      NS.hexSeasonText({
        name: "夏",
        zone: "ARCTIC_CIRCLE",
        dayOfSeason: 3,
        daysInSeason: 80,
        progressPerMille: 100,
        zoneSource: "store",
      }),
      "夏（ARCTIC_CIRCLE · 第 3/80 天 · 10%）",
      label + "：未知 zone 在季节行里原样出现"
    );
  }
});

// ── 两宿主页的复制必须逐字一致（静态；注释可不同）──

/** 从源码里切出一个函数的完整文本（该文件风格：函数体以恰好两空格缩进的 `}` 收尾）。 */
function extractFunction(source, signature) {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, "找不到 " + signature);
  const end = source.indexOf("\n  }", start);
  assert.notEqual(end, -1, signature + " 找不到函数结尾");
  return source.slice(start, end + "\n  }".length);
}

/** 剥掉块注释与行注释（保持其余字节原样，只去掉行尾空白）。 */
function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/\/\/[^\n]*/g, "")
    .replace(/[ \t]+$/gm, "");
}

test("the-two-copies-of-hexSeasonText-and-zoneReadableName-are-identical-after-comment-strip", () => {
  const panelsSource = readWebui("panels.js");
  const hostSource = readWebui("map-hostpage.js");
  for (const signature of ["function hexSeasonText(season) {", "function zoneReadableName(zone) {"]) {
    assert.equal(
      stripComments(extractFunction(panelsSource, signature)),
      stripComments(extractFunction(hostSource, signature)),
      signature + "：panels.js 与 map-hostpage.js 两份必须逐字一致（只允许注释不同）"
    );
  }
});

// ── 静态接线：日期行 / 季节行必须真的接到渲染路径上 ──

test("timeline-static-wiring-renders-tl-date-only-when-server-sent-a-date", () => {
  const source = readWebui("timeline.js");
  assert.ok(source.includes("tl-date"), "timeline.js 必须渲染 .tl-date");
  assert.ok(source.includes("dateByRevision"), "timeline.js 必须有 dateByRevision 表");
  assert.match(source, /dateByRevision\[branch\]\[revision\]\s*=\s*nodes\[branch\]\[j\]\.date\s*\|\|\s*null/, "revision → date 直接取服务端字段，缺失为 null");
  assert.match(source, /formatCalendarDate:\s*formatCalendarDate/, "formatCalendarDate 必须导出");
  const guard = source.indexOf("if (dateText) {");
  const appendAt = source.indexOf('class: "tl-date"');
  assert.ok(guard >= 0 && appendAt > guard, ".tl-date 的追加在 if (dateText) 守卫内（旧后端无日期不渲染日期行）");
});

test("panels-static-wiring-renders-season-row-from-api-season-field", () => {
  const source = readWebui("panels.js");
  assert.ok(source.includes("hexSeasonText(hex.season)"), "renderHex 必须把 hex.season 交给 hexSeasonText");
  assert.ok(source.includes('"季节"'), "工作台 hex 详情必须有「季节」行");
});

test("map-hostpage-static-wiring-renders-season-row-from-api-season-field", () => {
  const source = readWebui("map-hostpage.js");
  assert.ok(source.includes("hexSeasonText(body.season)"), "showHex 必须把 body.season 交给 hexSeasonText");
  assert.ok(source.includes('"季节"'), "旧页 hex 详情必须有「季节」行");
});

// ── 旧后端（无 date/season）降级：不编造（纯函数 + 接线证据，无人造服务/夹具）──

test("old-backend-without-date-or-season-degrades-without-fabrication", () => {
  assert.equal(T.formatCalendarDate(null), "", "无日期分量 ⇒ 空串");
  const groups = T.groupByTick([{ tick: 1, revision: 1, commandType: "unit.PlanRoute" }]);
  assert.strictEqual(groups[0].date, null);
  assert.deepEqual(groups[0].dates, []);
  for (const [label, NS] of HOST_PAGES) {
    assert.equal(NS.hexSeasonText(undefined), "—（后端未提供季节）", label + "：season 缺失 ⇒ 明示未提供");
  }
  // 接线证据：两页都是把（可能缺失的）season 字段直接交给纯函数 ⇒ undefined 路径就是上面的明示文案；
  // 时间线的日期行由 if (dateText) 守卫 ⇒ 空串不会渲染出空 `.tl-date`。
  assert.ok(readWebui("panels.js").includes("hexSeasonText(hex.season)"));
  assert.ok(readWebui("map-hostpage.js").includes("hexSeasonText(body.season)"));
});
