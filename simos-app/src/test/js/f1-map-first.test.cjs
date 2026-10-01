// f1-map-first.test.cjs —— F1（世界视图 / 图层 / 城市 LOD / GOV 归属 / class-first 读数）的纯函数与接线护栏。
//
// 覆盖计划 §3.1（worldmodel 纯函数）、§3.6（class-first 读数分流与决策人 gov 分支）、§3.3（renderer 接线）。
// ★ 一律以当前实现的**真实语义**为准（用户 2026-10-01 网页验收后的修正也在内：gov/govJurisdiction 缺省关、
//   世界 LOD 不画城市、国家名只在 world LOD）。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

const W = loadWebui("worldmodel.js").SimosWorldModel;
const D = loadWebui("decisionmodel.js").SimosDecisionModel;
const P = loadWebui("panels.js").SimosPanels;

const LAYER_KEYS = [
  "nation",
  "regionNames",
  "cities",
  "army",
  "gov",
  "govJurisdiction",
  "decisionMakers",
  "combats",
  "routes",
];

function visibleKeys(layers) {
  return LAYER_KEYS.filter((key) => layers[key] === true).sort();
}

function indexOfOrFail(source, needle, label) {
  const at = source.indexOf(needle);
  assert.ok(at >= 0, "renderer.js 必须含 " + label + "（找不到：" + needle + "）");
  return at;
}

const CITIES = [
  {
    id: "c-api",
    name: "首都城",
    at: { q: 1, r: 1 },
    region: "n-1",
    tier: "City",
    population: 100,
    props: { capital: true },
  },
  {
    id: "c-major",
    name: "大城",
    at: { q: 1, r: 2 },
    region: "n-1",
    tier: "MajorCity",
    population: 80,
    props: {},
  },
  {
    id: "c-city",
    name: "中城",
    at: { q: 1, r: 3 },
    region: "n-1",
    tier: "City",
    population: 60,
    props: {},
  },
  {
    id: "c-town",
    name: "镇",
    at: { q: 2, r: 1 },
    region: "n-1",
    tier: "Town",
    population: 40,
    props: {},
  },
  {
    id: "c-market",
    name: "集",
    at: { q: 2, r: 2 },
    region: "n-1",
    tier: "MarketTown",
    population: 20,
    props: {},
  },
  {
    id: "c-unknown",
    name: "无级",
    at: { q: 2, r: 3 },
    region: null,
    tier: null,
    population: 10,
    props: {},
  },
  // 坏坐标：必须被忽略（不能画到 (0,0) 或 NaN 位置）。
  { id: "c-bad", name: "坏城", at: { q: Number.NaN, r: 0 }, tier: "City", props: {} },
];

test("f1-layer-visibility-keeps-government-layers-off-and-is-fail-closed", () => {
  const defaults = W.layerVisibility(null);
  assert.deepEqual(Object.keys(defaults).sort(), LAYER_KEYS.slice().sort(), "返回值恰有九个图层键");
  assert.equal(defaults.gov, false, "★ 政府单位图层缺省关（用户实测修正）");
  assert.equal(defaults.govJurisdiction, false, "★ 政府辖区图层缺省关（用户实测修正）");
  for (const key of ["nation", "regionNames", "cities", "army", "decisionMakers", "combats", "routes"]) {
    assert.equal(defaults[key], true, "底图/军事/事件类缺省开：" + key);
  }

  const explicit = W.layerVisibility({ gov: true, govJurisdiction: true, nation: false });
  assert.equal(explicit.gov, true, "显式 true 才能打开政府图层");
  assert.equal(explicit.govJurisdiction, true);
  assert.equal(explicit.nation, false, "显式 false 必须覆盖缺省");
  assert.equal(explicit.cities, true, "缺键保持缺省（不因为一次局部覆盖而全关）");

  const junk = W.layerVisibility({ gov: 1, govJurisdiction: "true", nation: 0, unknownLayer: true });
  assert.equal(junk.gov, false, "只有 === true 才打开（1 不是 true）");
  assert.equal(junk.govJurisdiction, false, "字符串 'true' 不是 true");
  assert.equal(junk.nation, false, "0 不是 true");
  assert.equal(Object.prototype.hasOwnProperty.call(junk, "unknownLayer"), false, "未知键不进结果");

  assert.equal(W.DEFAULT_LAYERS.gov, false, "缺省常量本身也被钉住（renderer 与 map.js 共用同一份）");
});

test("f1-layer-presets-map-to-exact-nine-key-sets", () => {
  const all = W.layerPreset("all");
  assert.deepEqual(visibleKeys(all), LAYER_KEYS.slice().sort(), "all = 九键全开");

  const government = W.layerPreset("government");
  assert.deepEqual(
    visibleKeys(government),
    ["decisionMakers", "gov", "govJurisdiction"].sort(),
    "★ 政府预设 = GOV + 辖区 + 决策人徽标；国家色/名必须关（否则盖住辖区）"
  );
  assert.equal(government.nation, false);
  assert.equal(government.regionNames, false);

  const military = W.layerPreset("military");
  assert.deepEqual(visibleKeys(military), ["army", "combats", "routes"].sort(), "军事预设只看军队/交战/路线");
  assert.equal(military.cities, false, "军事预设不列出的键一律 false（不是 DEFAULT_LAYERS）");

  assert.deepEqual(visibleKeys(W.layerPreset("terrain")), ["nation", "regionNames"].sort());
  assert.deepEqual(visibleKeys(W.layerPreset("cities")), ["cities", "nation", "regionNames"].sort());

  assert.equal(W.layerPreset("nope"), null, "未知 preset fail-closed 返回 null");
  assert.equal(W.layerPreset(undefined), null);
});

test("f1-world-lod-has-no-city-markers-and-region-lod-drops-small-tiers", () => {
  assert.equal(W.lodForScale(0.05), "world");
  assert.equal(W.lodForScale(0.5), "region");
  assert.equal(W.lodForScale(1.5), "close");
  assert.equal(W.nationNamesVisible(0.05), true, "国家名只在 world LOD");
  assert.equal(W.nationNamesVisible(0.5), false, "放大后国名消失，只留城市名");
  assert.equal(W.nationNamesVisible(1.5), false);

  assert.deepEqual(W.cityMarkerPlan(CITIES, 0.05, { cities: true }), [], "★ 世界视图一个城市都不画（用户实测修正）");
  assert.deepEqual(W.cityMarkerPlan(CITIES, 1.5, { cities: false }), [], "cities 图层关 ⇒ 空计划");

  const region = W.cityMarkerPlan(CITIES, 0.5, { cities: true });
  assert.deepEqual(
    region.map((plan) => plan.id),
    ["c-city", "c-major", "c-api"],
    "区域 LOD 只画 City 以上；排序 rank 升序（小的先画、首都最后压顶）、同 rank 按 id"
  );
  assert.deepEqual(region.map((plan) => plan.showLabel), [true, true, true], "区域 LOD 三类都带标签");
  assert.ok(!region.some((plan) => plan.id === "c-bad"), "坏坐标城不进计划");

  const close = W.cityMarkerPlan(CITIES, 1.5, { cities: true });
  assert.deepEqual(
    close.map((plan) => plan.id),
    ["c-unknown", "c-market", "c-town", "c-city", "c-major", "c-api"],
    "近景画全部有效城市（未知等级归 unknown 最小点，排最前）"
  );
  assert.equal(close.find((plan) => plan.id === "c-unknown").showLabel, false, "未知等级只画点、不标签");
  assert.equal(close.find((plan) => plan.id === "c-api").category, "capital", "props.capital 优先于 tier");
  assert.equal(close.find((plan) => plan.id === "c-api").capital, true);
});

test("f1-search-index-and-matches-are-deterministic-and-cover-all-kinds", () => {
  const overview = {
    regions: [
      { id: "r-2", name: "第二区", hexCount: 4, label: { q: 2, r: 2 } },
      { id: "r-1", name: "第一区", hexCount: 3, label: { q: 1, r: 1 } },
    ],
  };
  const units = [
    { id: "u-army", name: "第一军", module: { kind: "army" }, status: "MOVING", position: { q: 1, r: 2 } },
    { id: "u-gov", name: "中央政府", module: { kind: "gov" }, position: { q: 1, r: 1 } },
  ];
  const makers = [
    { id: "dm-gov", affiliation: { kind: "gov", id: "u-gov", displayName: "德意志政府" } },
  ];

  const index = W.searchIndex(overview, CITIES, units, makers);
  assert.equal(index.length, 2 + 6 + 2 + 1, "坏坐标城与坏 region 不进索引");
  assert.deepEqual(
    index.map((item) => item.kind),
    ["city", "city", "city", "city", "city", "city", "region", "region", "unit", "unit", "decisionMaker"],
    "索引按 kind 档（city→region→unit→decisionMaker）再 id 排（确定性）"
  );
  assert.equal(index.find((item) => item.id === "u-gov").subtitle.indexOf("GOV"), 0, "GOV 单位在索引里可辨");
  assert.equal(index.find((item) => item.id === "dm-gov").name, "德意志政府");

  assert.deepEqual(W.searchMatches(index, ""), [], "空查询不返回任何项");
  const exact = W.searchMatches(index, "r-1");
  assert.equal(exact[0].id, "r-1", "id 精确匹配排最前");
  const byName = W.searchMatches(index, "第一");
  assert.deepEqual(
    byName.map((item) => item.id).sort(),
    ["r-1", "u-army"],
    "名称命中：区域 + 单位都要有（不丢 kind）"
  );
  assert.equal(W.searchMatches(index, "德意志政府")[0].id, "dm-gov");
  assert.ok(W.searchMatches(index, "首都").some((item) => item.id === "c-api"), "城市名包含也能命中");
});

test("f1-nation-summaries-aggregate-f2-fields-by-nation-tag", () => {
  const regions = [
    {
      id: "r-de-1",
      name: "德意志",
      hexCount: 10,
      meta: { tag: "nation:de" },
      population: 100,
      ruralPopulation: 60,
      urbanPopulation: 40,
      cityCount: 2,
      cityPopulation: 40,
      unitCount: 3,
      govCount: 1,
      grainStock: 1000,
      silverMoney: 50,
      goodsTotal: { grain: 1000, fiber: 5 },
    },
    {
      id: "r-de-2",
      name: "德意志",
      hexCount: 20,
      meta: { tag: "nation:de" },
      population: 200,
      ruralPopulation: 120,
      urbanPopulation: 80,
      cityCount: 3,
      cityPopulation: 80,
      unitCount: 4,
      govCount: 2,
      grainStock: 500,
      silverMoney: 70,
      goodsTotal: { grain: 500, iron: 7 },
    },
    {
      id: "r-other",
      name: "别的",
      hexCount: 99,
      meta: { tag: "province:x" },
      population: 9999,
      goodsTotal: { grain: 9999 },
    },
    {
      id: "r-unknown",
      name: "缺字段",
      hexCount: 1,
      meta: { tag: "nation:fr" },
      population: 7,
      cityCount: 1,
      goodsTotal: null,
    },
  ];
  const makers = [
    { id: "dm-2", affiliation: { kind: "nation", id: "de" } },
    { id: "dm-1", affiliation: { kind: "nation", id: "de" } },
    { id: "dm-3", affiliation: { kind: "gov", id: "g" } },
  ];

  const rows = W.nationSummaries(regions, [], [], makers);
  assert.deepEqual(
    rows.map((row) => row.id),
    ["de", "fr"],
    "只收 meta.tag=nation:<id>；按 id 字典序"
  );
  const de = rows[0];
  assert.equal(de.hexCount, 30);
  assert.equal(de.population, 300);
  assert.equal(de.ruralPopulation, 180);
  assert.equal(de.urbanPopulation, 120);
  assert.equal(de.cityCount, 5);
  assert.equal(de.cityPopulation, 120);
  assert.equal(de.unitCount, 7);
  assert.equal(de.govCount, 3);
  assert.equal(de.grainStock, 1500);
  assert.equal(de.silverMoney, 120);
  assert.deepEqual(de.goodsTotal, { grain: 1500, fiber: 5, iron: 7 }, "goodsTotal 逐商品合并（不跨商品丢键）");
  assert.equal(de.decisionMakerCount, 2);
  assert.equal(de.regionCount, 2);

  const fr = rows[1];
  assert.equal(fr.population, 7);
  assert.equal(fr.ruralPopulation, null, "缺字段 ⇒ null（不拿 0 冒充）");
  assert.equal(fr.grainStock, null);
  assert.equal(fr.goodsTotal, null);
  assert.equal(fr.decisionMakerCount, 0);

  assert.deepEqual(W.nationSummaries([{ id: "r", meta: {} }], [], [], []), [], "没有国家区域 ⇒ 空数组");
});

test("f1-decision-model-labels-and-matches-gov-affiliation", () => {
  assert.equal(D.affiliationKindLabel("gov"), "政府", "★ gov 不再显示成「其它（gov）」");
  assert.equal(D.affiliationKindLabel("nation"), "国家");
  assert.equal(D.affiliationKindLabel("mystery"), "mystery", "未知 kind 原样透出，不静默造标签");
  assert.equal(D.affiliationKindLabel(null), "—");

  const makers = [
    { id: "dm-gov-b", affiliation: { kind: "gov", id: "gov-1" } },
    { id: "dm-gov-a", affiliation: { kind: "gov", id: "gov-1" } },
    { id: "dm-army", affiliation: { kind: "army", rootUnit: "army-root" } },
  ];
  const units = [
    { id: "gov-1", parent: null },
    { id: "army-root", parent: null },
    { id: "army-leaf", parent: "army-root" },
  ];
  assert.equal(D.decisionMakerForUnit(makers, units, "gov-1").id, "dm-gov-a", "GOV 命中多个 ⇒ id 字典序最小");
  assert.equal(D.decisionMakerForUnit(makers, units, "army-leaf").id, "dm-army", "军队沿 parent 上溯仍成立");
  assert.equal(D.decisionMakerForUnit(makers, units, "nope"), null, "无命中 ⇒ null");

  const groups = D.decisionMakerGroups(makers);
  const govGroup = groups.find((group) => group.kind === "gov");
  assert.equal(govGroup.label, "政府", "决策人分组也走同一份 gov 标签");
  assert.deepEqual(
    govGroup.makers.map((maker) => maker.id),
    ["dm-gov-a", "dm-gov-b"],
    "组内按 id 字典序"
  );
});

const CLASS_FIRST_ECONOMY = {
  activated: true,
  population: 0,
  money: 0,
  industries: [],
  goods: { grain: 5 },
  actorMoneyTotal: { silver: 9 },
  grainStock: 7,
  classFirst: {
    available: true,
    householdCount: 12,
    pools: [
      { population: 100, labor: 50, assets: { ownedLand: 1000 }, debtGrainMilli: 7 },
      { population: 200, labor: 80, assets: { ownedLand: 2000 }, debtGrainMilli: 3 },
    ],
    landMarket: { landForSale: 11, leaseSupply: 22, landBalanced: true },
    accounts: { debtGrainMilli: 30, claimGrainMilli: 30, netSum: 0 },
    conservation: {
      grainBalanced: true,
      clothBalanced: false,
      moneyBalanced: true,
      landBalanced: true,
      debtEqualsClaim: true,
      accountNetSum: 0,
    },
  },
};

function rowOf(rows, label) {
  const found = rows.find((row) => row.label === label);
  assert.ok(found, "缺行 " + label + "（实际：" + rows.map((row) => row.label).join("、") + "）");
  return found;
}

test("f1-class-first-readout-uses-authoritative-rows-and-never-old-zero-projection", () => {
  const rows = P.economyReadoutRows(CLASS_FIRST_ECONOMY);

  assert.equal(rows.find((row) => row.label === "经济人口"), undefined, "★ class-first 可用时不得再显示旧人口 0");
  assert.equal(rows.find((row) => row.label === "货币"), undefined, "★ 不得再显示旧 money 0");
  assert.equal(rows.find((row) => row.label === "产业"), undefined, "★ 不得把空 industries 当「无」真相");
  assert.equal(rows.find((row) => row.label === "日耗"), undefined, "旧日耗行不再出现");

  assert.equal(rowOf(rows, "世界级阶层池").value, "2 池 · 家户账户 12");
  assert.equal(
    rowOf(rows, "世界级池合计").value,
    "人口 300 · 劳动 130 · 土地 3000 千分亩 · 债务 10 毫粮"
  );
  assert.equal(rowOf(rows, "土地市场").value, "待售 11 · 出租供给 22 · 土地守恒 平衡");
  assert.equal(rowOf(rows, "世界级账户").value, "债务 30 毫粮 · 债权 30 毫粮 · 净额 0");
  assert.match(rowOf(rows, "世界级守恒").value, /粮 平衡 · 布 不平衡 · 货币 平衡/);
  assert.equal(rowOf(rows, "本格库存").value, "grain 5");
  assert.equal(rowOf(rows, "本格货币").value, "silver 9");
  assert.equal(rowOf(rows, "本格粮库存").value, 7);
  assert.match(rowOf(rows, "世界级阶层池").hint, /世界级|没有国家维/);
});

test("f1-class-first-missing-fields-degrade-to-named-unavailable-not-zero", () => {
  const rows = P.economyReadoutRows({ activated: true, classFirst: { available: true } });
  assert.equal(rowOf(rows, "世界级阶层池").value, "—（不可得：classFirst.pools 缺失）");
  assert.equal(rowOf(rows, "土地市场").value, "—（不可得：classFirst.landMarket 缺失）");
  assert.equal(rowOf(rows, "世界级账户").value, "—（不可得：classFirst.accounts 缺失）");
  assert.equal(rowOf(rows, "世界级守恒").value, "—（不可得：classFirst.conservation 缺失）");
  assert.match(rowOf(rows, "本格库存").value, /不可得/);
  assert.match(rowOf(rows, "本格货币").value, /不可得/);
  assert.equal(rowOf(rows, "本格粮库存").value, 0, "本格粮库存缺字段沿用旧口径 0（真实读数位）");
});

test("f1-class-first-row-projection-is-shared-not-duplicated", () => {
  assert.equal(typeof P.classFirstReadoutRows, "function", "class-first 投影必须导出，门禁才能对拍");
  assert.deepEqual(
    P.classFirstReadoutRows(CLASS_FIRST_ECONOMY, CLASS_FIRST_ECONOMY.classFirst),
    P.economyReadoutRows(CLASS_FIRST_ECONOMY),
    "economyReadoutRows 的 class-first 分支必须就是这一份实现（不复制规则）"
  );
  assert.equal(
    (readWebui("panels.js").match(/function classFirstReadoutRows\s*\(/g) || []).length,
    1,
    "class-first 投影只许有一份实现"
  );
});

test("f1-renderer-wires-city-lod-gov-layers-and-heatmap-order", () => {
  const src = readWebui("renderer.js");

  assert.ok(src.includes("setCities: setCities"), "renderer 必须导出 setCities");
  assert.ok(src.includes("setLayerState: setLayerState"), "renderer 必须导出 setLayerState");
  assert.ok(src.includes("setHeatmap: setHeatmap"), "renderer 必须导出 setHeatmap");
  assert.ok(src.includes("ensureCityVisible: ensureCityVisible"), "renderer 必须导出 ensureCityVisible");
  assert.ok(src.includes("ensureUnitVisible: ensureUnitVisible"), "ensureUnitVisible 不得回归消失");
  assert.ok(src.includes("cityMarkerPlanOf"), "cityMarkerPlan 必须由 renderer 接线（不是另写 LOD 表）");
  assert.ok(
    src.includes("gov ? !layerState.gov : !layerState.army"),
    "单位绘制必须按 GOV/军队各自图层门控"
  );
  assert.ok(
    src.includes("!isWorkbench || !layerState.govJurisdiction || !govJurisdictions.length"),
    "GOV 辖区绘制必须受 govJurisdiction 与工作台形态门控"
  );

  const renderStart = src.indexOf("function render()");
  assert.ok(renderStart >= 0, "取到 render 函数");
  const renderBody = src.slice(renderStart, renderStart + 2600);
  const order = [
    indexOfOrFail(renderBody, "paintGovJurisdictions(ctx);", "GOV 辖区"),
    indexOfOrFail(renderBody, "drawHeatmap(ctx);", "热力层"),
    indexOfOrFail(renderBody, "paintTerrainDim(ctx,", "地形压暗"),
    indexOfOrFail(renderBody, "drawCities();", "城市"),
    indexOfOrFail(renderBody, "drawUnits();", "单位"),
  ];
  for (let i = 1; i < order.length; i += 1) {
    assert.ok(order[i - 1] < order[i], "绘制顺序必须 GOV 辖区 → 热力 → 压暗 → 城市 → 单位");
  }
});

test("f1-map-wires-world-overview-layer-drawer-and-gov-layer-key", () => {
  const map = readWebui("map.js");
  const api = readWebui("api.js");

  assert.ok(map.includes('"simos.layers.v2"'), "★ localStorage 图层键必须是 v2（旧 v1 的 gov=true 不能顶掉新缺省）");
  assert.ok(!map.includes('"simos.layers.v1"'), "不得再写旧键 v1");
  assert.ok(map.includes("cachedCities"), "世界视图必须拉 /api/social/cities");
  assert.ok(map.includes("cachedRegionSummaries"), "世界视图必须拉区域汇总");
  assert.ok(map.includes("cachedEconomyOverview"), "世界视图必须拉世界经济总览");
  assert.ok(map.includes("active.setCities(cities)"), "城市数据必须推进渲染器");
  assert.ok(
    map.includes("window.SimosPanelRight.setWorldData"),
    "右栏世界总览必须由 map.js 推同一份数据（不各拉一份）"
  );
  const panelRight = readWebui("panel-right.js");
  assert.ok(panelRight.includes("renderWorldOverview"), "右栏必须实现世界总览渲染");
  assert.ok(
    panelRight.includes("state.mode === \"view\" || state.mode === \"region\"") &&
      panelRight.includes("!state.selection"),
    "世界总览只在 view/region 且无选中时显示（有选中让位）"
  );
  assert.ok(api.includes("cachedCities:"), "api.js 必须导出 cachedCities");
  assert.ok(api.includes("cachedRegionSummaries:"), "api.js 必须导出 cachedRegionSummaries");
  assert.ok(api.includes("cachedEconomyOverview:"), "api.js 必须导出 cachedEconomyOverview");

  const index = readWebui("index.html");
  assert.ok(index.includes('id="layer-panel"'), "工作台必须有图层抽屉");
  assert.ok(index.includes('data-layer="gov"'), "图层抽屉必须有 gov 开关");
  assert.ok(index.includes('data-layer="govJurisdiction"'), "图层抽屉必须有 govJurisdiction 开关");
});
