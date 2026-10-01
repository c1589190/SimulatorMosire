// f2-heatmap.test.cjs —— F2（人口/粮食/货币热力图 + 区域汇总）纯函数、色标/图例与接线护栏。
//
// 覆盖计划 §3.3（heatmapColorScale / heatmapLegend）、§3.1/§3.2（数据下拉、不透明度、绘制顺序、不可用层不填 0）
// 与 §2.1（前端经 api.cachedHeatmap 取数、按 (metric,target) 记忆化）。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

const W = loadWebui("worldmodel.js").SimosWorldModel;

function cellsOf(values) {
  return values.map((value, index) => ({ q: index, r: -index, value: value }));
}

test("f2-heatmap-metric-table-is-frozen-and-covers-designed-first-batch", () => {
  assert.deepEqual(
    W.HEATMAP_METRICS.map((metric) => metric.id),
    [
      "populationTotal",
      "populationRural",
      "populationUrban",
      "grainStock",
      "grainDailyNeed",
      "grainCoverageDays",
      "grainCycleUnmet",
      "moneySilver",
    ],
    "★ 指标 id/顺序是 index.html 下拉的唯一来源（HTML 不另抄一份）"
  );
  const byId = {};
  W.HEATMAP_METRICS.forEach((metric) => {
    byId[metric.id] = metric;
  });
  assert.equal(byId.populationTotal.unit, "人");
  assert.equal(byId.grainStock.unit, "毫粮");
  assert.equal(byId.grainCoverageDays.unit, "天");
  assert.equal(byId.moneySilver.unit, "毫银");
  assert.match(byId.grainCycleUnmet.help, /unavailable/, "★ 周期缺口整层不可用必须写进词表 help");
  assert.equal(W.HEATMAP_PALETTE.length, 7, "固定 7 色调色板");
  assert.equal(new Set(W.HEATMAP_PALETTE).size, 7, "调色板不得有重复色");
});

test("f2-color-scale-empty-input-is-empty-not-zero-filled", () => {
  const plan = W.heatmapColorScale([], {});
  assert.deepEqual(plan.bins, [], "空输入 ⇒ 空计划");
  assert.deepEqual(plan.stats, { count: 0, min: null, median: null, max: null }, "空输入不得用 0 冒充统计");
  assert.equal(plan.colorOf(0), null, "无 bins ⇒ 任何值都取不到颜色（不着色）");
  assert.equal(plan.colorOf(Number.NaN), null);
  assert.equal(plan.method, "quantile");
  assert.equal(plan.bins.length, 0);

  assert.deepEqual(W.heatmapColorScale(null).bins, [], "null cells 同样空计划、不抛");
  assert.deepEqual(W.heatmapColorScale(undefined).stats, { count: 0, min: null, median: null, max: null });
});

test("f2-color-scale-same-value-collapses-to-one-deterministic-bin", () => {
  const plan = W.heatmapColorScale(cellsOf([5, 5, 5, 5]), {});
  assert.equal(plan.bins.length, 1, "同值不裂成多档、不编造空档");
  assert.equal(plan.bins[0].count, 4, "count 覆盖全部有限格");
  assert.equal(plan.bins[0].min, 5);
  assert.equal(plan.bins[0].max, 5);
  assert.equal(plan.colorOf(5), plan.bins[0].color);
  assert.equal(plan.colorOf(-100), plan.bins[0].color, "低于首档取首色");
  assert.equal(plan.colorOf(1e9), plan.bins[0].color, "高于末档取末色");
  assert.equal(plan.stats.count, 4);
  assert.equal(plan.stats.min, 5);
  assert.equal(plan.stats.median, 5);
  assert.equal(plan.stats.max, 5);

  const again = W.heatmapColorScale(cellsOf([5, 5, 5, 5]), {});
  assert.deepEqual(again.bins, plan.bins, "同输入两次结果深比较一致（确定性）");
  assert.equal(again.colorOf(5), plan.colorOf(5));
});

test("f2-color-scale-long-tail-bins-cover-every-finite-value-with-bounded-colors", () => {
  const values = [1, 2, 3, 4, 5, 6, 7, 8, 9, 100, 1000, 1_000_000];
  const plan = W.heatmapColorScale(cellsOf(values), { method: "quantile", bins: 7 });
  assert.ok(plan.bins.length >= 1 && plan.bins.length <= W.HEATMAP_PALETTE.length, "档数 ∈ [1,7]");
  assert.equal(
    plan.bins.reduce((sum, bin) => sum + bin.count, 0),
    values.length,
    "★ 每格恰进一档（少一格/漏一格 ⇒ 红）"
  );
  for (let i = 0; i < plan.bins.length; i += 1) {
    const bin = plan.bins[i];
    assert.ok(bin.min <= bin.max, "档边界 min ≤ max");
    assert.ok(W.HEATMAP_PALETTE.includes(bin.color), "颜色必须来自固定调色板");
    if (i > 0) {
      assert.ok(plan.bins[i - 1].max < bin.min, "档按值升序且互不重叠（同值不裂档）");
    }
  }
  assert.equal(plan.colorOf(1), plan.bins[0].color);
  assert.equal(plan.colorOf(1_000_000), plan.bins[plan.bins.length - 1].color);
  assert.equal(plan.stats.count, values.length);
  assert.equal(plan.stats.min, 1);
  assert.equal(plan.stats.max, 1_000_000);
  assert.equal(plan.stats.median, 6.5, "12 个值取第 6/7 个中位平均 (6+7)/2");
});

test("f2-color-scale-ignores-non-finite-values-and-fail-closes-bad-options", () => {
  const plan = W.heatmapColorScale(
    [
      { q: 0, r: 0, value: 1 },
      { q: 1, r: 0, value: Number.NaN },
      { q: 2, r: 0, value: Number.POSITIVE_INFINITY },
      { q: 3, r: 0, value: "-3" },
      { q: 4, r: 0, value: null },
      null,
      { q: 5, r: 0, value: 3 },
    ],
    { method: "log", bins: 0 }
  );
  assert.equal(plan.stats.count, 2, "非有限 / 非数值 / 空值不进统计");
  assert.deepEqual(plan.stats, { count: 2, min: 1, median: 2, max: 3 });
  assert.equal(plan.method, "quantile", "未知 method/bins fail-closed 回落分位数默认");
  assert.equal(plan.colorOf(Number.NaN), null);
  assert.equal(plan.colorOf(Number.POSITIVE_INFINITY), null);
  assert.equal(plan.colorOf("1"), null);
  assert.notEqual(plan.colorOf(1), null);
});

test("f2-legend-unavailable-layer-names-its-reason-and-never-says-zero", () => {
  const payload = {
    metric: "grainCycleUnmet",
    label: "粮食·周期缺口",
    unit: "毫粮",
    scope: "本周期累计（旧 MarketReport 口径）；class-first 无逐格来源",
    cells: [],
    stats: { count: 0, min: null, median: null, max: null },
    unavailable: "R3a 起旧市场报告组件已删除；class-first 不产生逐格周期缺口",
    notes: { skippedDailyNeedZero: 3 },
  };
  const legend = W.heatmapLegend(payload, W.heatmapColorScale(payload.cells));

  assert.equal(legend.unavailable, payload.unavailable, "★ 不可用原因必须原样带出（非空）");
  assert.equal(legend.title, "粮食·周期缺口");
  assert.equal(legend.unit, "毫粮");
  assert.deepEqual(legend.lines, [], "不可用层没有档位");
  assert.match(legend.footnote, /不可用：R3a 起旧市场报告组件已删除/);
  assert.match(legend.footnote, /缺失数据格不着色、不填 0。/);
  assert.match(legend.footnote, /count=0，min=—，median=—，max=—/, "空 stats 显示 — 而不是 0");
  assert.match(legend.footnote, /notes：skippedDailyNeedZero=3/);
});

test("f2-legend-carries-bins-stats-notes-and-empty-payload-does-not-throw", () => {
  const cells = cellsOf([1, 1, 2, 5, 9, 100]);
  const payload = {
    metric: "grainStock",
    label: "粮食·库存",
    unit: "毫粮",
    scope: "逐格 actor GoodsAccount 粮余额合计（时点）",
    cells: cells,
    notes: { skippedDailyNeedZero: 0, skippedNoGrainAccount: 0 },
  };
  const scale = W.heatmapColorScale(cells);
  const legend = W.heatmapLegend(payload, scale);

  assert.equal(legend.lines.length, scale.bins.length);
  legend.lines.forEach((line, index) => {
    assert.equal(line.color, scale.bins[index].color);
    assert.equal(line.count, scale.bins[index].count);
    assert.ok(line.label.length > 0, "每档必须有可读边界文本");
  });
  assert.match(legend.footnote, /统计：count=6，min=1，median=/);
  assert.match(legend.footnote, /max=100/);
  assert.match(legend.footnote, /notes：/);

  const empty = W.heatmapLegend(null, null);
  assert.equal(empty.title, "热力图", "空 payload 确定性兜底标题，不抛");
  assert.equal(empty.unit, null);
  assert.equal(empty.scope, null);
  assert.deepEqual(empty.lines, []);
  assert.equal(empty.unavailable, null);
  assert.match(empty.footnote, /缺失数据格不着色、不填 0。/);
});

test("f2-map-wires-data-controls-and-unavailable-layer-clears-cells", () => {
  const map = readWebui("map.js");
  const index = readWebui("index.html");

  assert.ok(index.includes('id="heatmap-metric"'), "图层抽屉必须有数据指标下拉");
  assert.ok(index.includes('id="heatmap-opacity"'), "必须有热力图不透明度滑块");
  assert.match(index, /id="heatmap-opacity"[^>]*min="15"[^>]*max="85"/, "滑块范围 15..85（千分比 150..850）");
  assert.ok(index.includes('id="heatmap-legend"'), "必须有热力图图例 HUD");

  assert.ok(map.includes("worldModel.HEATMAP_METRICS"), "下拉选项必须由 worldmodel 词表生成（不在 HTML 另抄）");
  assert.ok(map.includes("api.cachedHeatmap(metric, app.target())"), "取数必须走 api.cachedHeatmap 且带当前 target");
  assert.ok(map.includes("active.setHeatmap(plan)"), "取到数据必须推进 renderer.setHeatmap");
  assert.ok(map.includes("if (unavailable)"), "不可用层必须先短路：不得把 cells 当可用数据画");
  const unavailableAt = map.indexOf("if (unavailable)");
  const unavailableBody = map.slice(unavailableAt, unavailableAt + 220);
  assert.match(unavailableBody, /clearHeatmapLayer\(\)/, "不可用层必须清旧层（不保留上一指标的颜色）");
  assert.match(unavailableBody, /renderHeatmapLegend\(payload, scalePlan\)/, "不可用层必须把服务端原因交给图例");
  assert.ok(
    map.indexOf("opacityPerMille") >= 0 && map.includes("/ 1000"),
    "不透明度落给渲染器必须是千分比 / 1000"
  );
});

test("f2-api-cached-heatmap-dedupes-same-metric-and-keys-by-metric", async () => {
  const calls = [];
  const recordingFetch = (url, init) => {
    calls.push({ url: url, method: init && init.method });
    return Promise.resolve({
      ok: true,
      status: 200,
      statusText: "OK",
      text: () => Promise.resolve(JSON.stringify({ metric: "x", cells: [] })),
    });
  };
  const api = loadWebui("api.js", { fetch: recordingFetch }).SimosApi;

  await api.cachedHeatmap("populationTotal", null);
  await api.cachedHeatmap("populationTotal", null);
  await api.cachedHeatmap("grainStock", null);

  assert.deepEqual(
    calls.map((call) => call.url),
    ["/api/map/heatmap?metric=populationTotal", "/api/map/heatmap?metric=grainStock"],
    "同一 (metric,target) 只发一次；换指标必须重新取数"
  );
  assert.ok(
    calls.every((call) => call.method === "GET"),
    "热力图只读：必须都是 GET"
  );
});

test("f2-api-cities-and-region-summaries-use-relative-read-only-paths", async () => {
  const calls = [];
  const recordingFetch = (url, init) => {
    calls.push({ url: url, method: init && init.method });
    return Promise.resolve({
      ok: true,
      status: 200,
      statusText: "OK",
      text: () => Promise.resolve(JSON.stringify({ cities: [], regions: [] })),
    });
  };
  const api = loadWebui("api.js", { fetch: recordingFetch }).SimosApi;

  await api.cities("德意志 第二帝国", null);
  await api.cities(null, null);
  await api.cachedRegionSummaries(null);

  assert.deepEqual(
    calls.map((call) => call.url),
    [
      "/api/social/cities?region=%E5%BE%B7%E6%84%8F%E5%BF%97%20%E7%AC%AC%E4%BA%8C%E5%B8%9D%E5%9B%BD",
      "/api/social/cities",
      "/api/map/regions/summary",
    ],
    "region 参数必须 URL 编码；端点都是同源相对路径"
  );
  for (const call of calls) {
    assert.ok(call.url.indexOf("http://") < 0 && call.url.indexOf("https://") < 0, "不得出现绝对 URL");
    assert.ok(call.url.indexOf("//") < 0, "不得出现协议相对 URL");
  }
});
