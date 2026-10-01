// worldmodel.js —— F1 世界视图的**纯函数**模型（城市 LOD / 搜索索引与匹配 / 国家汇总 / 九键图层与场景预设）。
//
// ★ 无 DOM、无 IO、无 fetch：本文件只做确定性计算，门禁可直接对拍。
// ★ 未知值一律 fail-closed：未知图层键不进入结果；未知城市等级按"最小点、不标签"处理；匹配/排序全部确定。
// ★ 加载顺序：index.html / map.html 都必须在 panels.js 与 renderer.js 之前引入本文件
//   （renderer.js 取 window.SimosWorldModel 的 LOD 计划与图层归一；panels/map 取索引、匹配与预设）。

(function () {
  "use strict";

  // ── 图层 ──────────────────────────────────────────────────────────────

  /** 九个图层键（F1 计划 §3.5 的分组开关）；顺序固定 = 抽屉里的显示顺序。 */
  var LAYER_KEYS = [
    "nation", // 国家着色
    "regionNames", // 区域名（含世界视图的国家名）
    "cities", // 城市
    "army", // 军队
    "gov", // 政府单位
    "govJurisdiction", // 政府辖区
    "decisionMakers", // 决策人徽标
    "combats", // 交战
    "routes", // 路线
  ];

  /**
   * 缺省：底图/聚落/军事/事件默认开；**政府相关默认关**（`gov` / `govJurisdiction`）——
   * 用户 2026-10-01 实测“显示国家名时具体的政府也一起显示”⇒ 政府信息只在政府图层/预设里出现。
   * 未知键不出现、未知值保持缺省（fail-closed）。
   */
  var DEFAULT_LAYERS = {
    nation: true,
    regionNames: true,
    cities: true,
    army: true,
    gov: false,
    govJurisdiction: false,
    decisionMakers: true,
    combats: true,
    routes: true,
  };

  /**
   * 归一化图层开关：只认 {@link LAYER_KEYS} 里的键。缺键 ⇒ {@link DEFAULT_LAYERS}（注意 `govJurisdiction`
   * 缺省为 `false`，坏输入不能把它打开）；键在场时**只有显式 `true` 才打开**，`false` 与任何未知值一律关闭
   * （fail-closed：坏输入不会把图层打开）。返回新对象（调用方不得直接改传入对象）。
   */
  function layerVisibility(layers) {
    var out = {};
    LAYER_KEYS.forEach(function (key) {
      out[key] = DEFAULT_LAYERS[key];
    });
    if (!layers || typeof layers !== "object") {
      return out;
    }
    LAYER_KEYS.forEach(function (key) {
      if (!Object.prototype.hasOwnProperty.call(layers, key)) {
        return;
      }
      out[key] = layers[key] === true;
    });
    return out;
  }

  /**
   * 场景预设表：`layerPreset` 按 {@link LAYER_KEYS} 展开，表中未列出的键一律 `false`
   * （不是 DEFAULT_LAYERS）；`military` 显式列出 `nation:false` / `regionNames:false` 以写明语义（取值只看 `=== true`）。
   */
  var LAYER_PRESETS = {
    all: {
      nation: true,
      regionNames: true,
      cities: true,
      army: true,
      gov: true,
      govJurisdiction: true,
      decisionMakers: true,
      combats: true,
      routes: true,
    },
    terrain: {
      nation: true,
      regionNames: true,
    },
    military: {
      nation: false,
      regionNames: false,
      army: true,
      combats: true,
      routes: true,
    },
    government: {
      // ★ F1 可见性修正：政府视图 = 地形底图 + GOV 标记 + 辖区 + 决策人徽标。
      //   显式关国家色与国家名，避免国家填充/国名盖住 GOV 辖区（其余键未列出 ⇒ false）。
      nation: false,
      regionNames: false,
      gov: true,
      govJurisdiction: true,
      decisionMakers: true,
    },
    cities: {
      nation: true,
      regionNames: true,
      cities: true,
    },
  };

  /**
   * 场景预设：未知 `name` ⇒ `null`（不抛，调用方 fail-closed）；已知名 ⇒ 归一化后的 9 键图层对象
   * （缺省键按 `false`，不是 DEFAULT_LAYERS 全开）。实现：预设表只放 true 键，逐 {@link LAYER_KEYS}
   * 生成 `out[key] = preset[key] === true`，最后过一遍 {@link layerVisibility} 归一。
   */
  function layerPreset(name) {
    if (!Object.prototype.hasOwnProperty.call(LAYER_PRESETS, name)) {
      return null;
    }
    var preset = LAYER_PRESETS[name] || {};
    var out = {};
    LAYER_KEYS.forEach(function (key) {
      out[key] = preset[key] === true;
    });
    return layerVisibility(out);
  }

  // ── LOD ───────────────────────────────────────────────────────────────

  // 世界级上限 / 区域级上限（scale 越小越远）。世界 fit 的 scale 约 0.05~0.1，
  // 区域编辑/单位模式下会放大到 0.25 以上 ⇒ region/close 两档能覆盖"看得清细节"的常用区间。
  var LOD_WORLD_MAX_SCALE = 0.22;
  var LOD_REGION_MAX_SCALE = 0.9;

  /** 缩放 ⇒ LOD 档：`world` / `region` / `close`；非有限或 ≤0 ⇒ `world`（fail-closed）。 */
  function lodForScale(scale) {
    if (typeof scale !== "number" || !isFinite(scale) || scale <= 0) {
      return "world";
    }
    if (scale < LOD_WORLD_MAX_SCALE) {
      return "world";
    }
    if (scale < LOD_REGION_MAX_SCALE) {
      return "region";
    }
    return "close";
  }

  /**
   * ★ 2026-10-01 F1 修复：国家名是否可见 —— **只在世界级 LOD** 显示（缩放大到区域/近景后国家名消失，
   * 只留城市名）。独立成纯函数，便于渲染器与门禁共用同一判据（避免在 renderer 里另写一套阈值）。
   */
  function nationNamesVisible(scale) {
    return lodForScale(scale) === "world";
  }

  // ── 城市标记计划 ──────────────────────────────────────────────────────

  var TIER_ORDER = ["MarketTown", "Town", "City", "MajorCity"];

  /** 已知等级 ⇒ 0..3；未知/缺失 ⇒ -1（fail-closed，不冒充任何等级）。 */
  function tierRank(tier) {
    return TIER_ORDER.indexOf(tier);
  }

  /** 首都判定：props 显式标记优先；worldgen 的 justification 里写有"首都"（`★首都…`）也算。 */
  function isCapitalCity(city) {
    var props = (city && city.props) || {};
    if (props.capital === true || props.isCapital === true) {
      return true;
    }
    return typeof props.justification === "string" && props.justification.indexOf("首都") >= 0;
  }

  /**
   * 城市类别：`capital` / `major` / `city` / `town` / `marketTown` / `unknown`。
   * 首都优先于等级（= 计划"首都星标优先"）；未知等级归 `unknown`（只画最小点）。
   */
  function cityCategory(city) {
    if (isCapitalCity(city)) {
      return "capital";
    }
    if (city && city.tier === "MajorCity") {
      return "major";
    }
    if (city && city.tier === "City") {
      return "city";
    }
    if (city && city.tier === "Town") {
      return "town";
    }
    if (city && city.tier === "MarketTown") {
      return "marketTown";
    }
    return "unknown";
  }

  var CATEGORY_RANK = {
    unknown: 0,
    marketTown: 1,
    town: 2,
    city: 3,
    major: 4,
    capital: 5,
  };

  // 世界半径系数（渲染器乘 cellSize）与各 LOD 的可见/标签档。
  var CATEGORY_RADIUS = {
    capital: 0.26,
    major: 0.22,
    city: 0.17,
    town: 0.12,
    marketTown: 0.09,
    unknown: 0.09,
  };

  // 各 LOD 画哪些类别、哪些带文字标签（计划 §3.3 的"世界级 / 区域级 / 近景"配方）。
  // ★ 2026-10-01 F1 修复（用户实测）：**世界级（scale < 0.22）一个城市都不画** —— 画面整体缩小时
  //   城市标记会挤成一团，用户看到的城市应只在区域级/近景出现。world 的每一类都显式 false（不是省略键），
  //   任何类别在 world 级都会 fail-closed 地不进入计划。区域级/近景仍按原 tier 表。
  var MARKERS_BY_LOD = {
    world: {
      capital: false,
      major: false,
      city: false,
      town: false,
      marketTown: false,
      unknown: false,
    },
    region: { capital: true, major: true, city: true },
    close: { capital: true, major: true, city: true, town: true, marketTown: true, unknown: true },
  };
  var LABELS_BY_LOD = {
    world: { capital: true },
    region: { capital: true, major: true, city: true },
    close: {
      capital: true,
      major: true,
      city: true,
      town: true,
      marketTown: true,
      unknown: false,
    },
  };

  function validCity(city) {
    return (
      city &&
      city.id !== null &&
      city.id !== undefined &&
      city.at &&
      typeof city.at.q === "number" &&
      typeof city.at.r === "number" &&
      isFinite(city.at.q) &&
      isFinite(city.at.r)
    );
  }

  /**
   * 城市标记 / 标签的 LOD 计划（纯函数）。
   *
   * @param cities `/api/social/cities` 的 `cities[]`
   * @param scale 当前渲染缩放
   * @param layers 图层开关（经 {@link layerVisibility} 归一；cities=false ⇒ 空计划）
   * @return `[{kind:"city", id, name, at:{q,r}, region, tier, population, category, capital,
   *     showLabel, radiusFactor, rank}…]`，按 rank 升序、再 id 字典序（小的先画，首都最后压在最上层）。
   */
  function cityMarkerPlan(cities, scale, layers) {
    var state = layerVisibility(layers);
    if (!state.cities) {
      return [];
    }
    var lod = lodForScale(scale);
    var markerAllowed = MARKERS_BY_LOD[lod];
    var labelAllowed = LABELS_BY_LOD[lod];
    var out = [];
    (Array.isArray(cities) ? cities : []).forEach(function (city) {
      if (!validCity(city)) {
        return;
      }
      var category = cityCategory(city);
      if (!markerAllowed[category]) {
        return;
      }
      out.push({
        kind: "city",
        id: String(city.id),
        name: city.name === null || city.name === undefined ? "" : String(city.name),
        at: { q: city.at.q, r: city.at.r },
        region: city.region === undefined ? null : city.region,
        tier: city.tier === undefined ? null : city.tier,
        population: city.population,
        category: category,
        capital: category === "capital",
        showLabel: !!labelAllowed[category],
        radiusFactor: CATEGORY_RADIUS[category],
        rank: CATEGORY_RANK[category],
      });
    });
    out.sort(function (a, b) {
      return a.rank - b.rank || a.id.localeCompare(b.id);
    });
    return out;
  }

  /** 世界视图里的**国家名**计划（区域名图层的 world LOD 子集；区域名在区域模式下仍走 map.js 的既有计划）。 */
  var NATION_LABEL_MIN_SCALE = 0.03;
  var NATION_TAG_PREFIX = "nation:";

  function isNationRegion(region) {
    var tag = region && region.meta ? region.meta.tag : null;
    return typeof tag === "string" && tag.indexOf(NATION_TAG_PREFIX) === 0;
  }

  /**
   * 国家名标签计划（纯函数）：只收录 `meta.tag` 以 `nation:` 开头、有质心落点、`hexCount>0` 的区域；
   * scale 低于 {@link NATION_LABEL_MIN_SCALE} ⇒ 空计划（太远不画，避免噪声）。
   * 字号配方与 map.js 的 `regionLabelLayout` 同源：`max(8, min(40, √hexCount × 1.8)) / scale`。
   */
  function nationLabelPlan(regions, scale) {
    if (typeof scale !== "number" || !isFinite(scale) || scale < NATION_LABEL_MIN_SCALE) {
      return [];
    }
    var out = [];
    (Array.isArray(regions) ? regions : []).forEach(function (region) {
      if (!isNationRegion(region) || !region.label || !(region.hexCount > 0)) {
        return;
      }
      var name = region.name === null || region.name === undefined ? region.id : region.name;
      if (name === null || name === undefined || String(name) === "") {
        return;
      }
      out.push({
        id: String(region.id),
        text: String(name),
        q: region.label.q,
        r: region.label.r,
        fontSize: Math.max(8, Math.min(40, Math.sqrt(region.hexCount) * 1.8)) / scale,
      });
    });
    out.sort(function (a, b) {
      return a.id.localeCompare(b.id);
    });
    return out;
  }

  // ── 搜索索引与匹配 ────────────────────────────────────────────────────

  var KIND_RANK = { city: 0, region: 1, unit: 2, decisionMaker: 3 };

  function kindRank(kind) {
    return Object.prototype.hasOwnProperty.call(KIND_RANK, kind) ? KIND_RANK[kind] : 9;
  }

  function textOf(value) {
    return value === null || value === undefined ? "" : String(value);
  }

  /**
   * 搜索索引（纯函数）：把区域（map overview.regions）、城市、单位、决策人拉平成同一种项。
   * 项形：`{kind,id,name,subtitle,at:{q,r}|null}`；排序按 kind 档、再 id 字典序（确定性）。
   */
  function searchIndex(overview, cities, units, decisionMakers) {
    var out = [];
    ((overview && overview.regions) || []).forEach(function (region) {
      if (!region || region.id === null || region.id === undefined) {
        return;
      }
      out.push({
        kind: "region",
        id: String(region.id),
        name: textOf(region.name || region.id),
        subtitle: "区域 · " + textOf(region.hexCount) + " 格",
        at: region.label && region.label.q !== undefined ? { q: region.label.q, r: region.label.r } : null,
      });
    });
    (Array.isArray(cities) ? cities : []).forEach(function (city) {
      if (!validCity(city)) {
        return;
      }
      out.push({
        kind: "city",
        id: String(city.id),
        name: textOf(city.name),
        subtitle:
          "城市 · " +
          textOf(city.tier === null || city.tier === undefined ? "未标等级" : city.tier) +
          (city.region ? " · " + textOf(city.region) : ""),
        at: { q: city.at.q, r: city.at.r },
      });
    });
    (Array.isArray(units) ? units : []).forEach(function (unit) {
      if (!unit || unit.id === null || unit.id === undefined) {
        return;
      }
      var isGov = !!(unit.module && unit.module.kind === "gov");
      out.push({
        kind: "unit",
        id: String(unit.id),
        name: textOf(unit.name || unit.id),
        subtitle: (isGov ? "GOV" : "军队") + (unit.status ? " · " + textOf(unit.status) : ""),
        at:
          unit.position && typeof unit.position.q === "number"
            ? { q: unit.position.q, r: unit.position.r }
            : null,
      });
    });
    (Array.isArray(decisionMakers) ? decisionMakers : []).forEach(function (maker) {
      if (!maker || maker.id === null || maker.id === undefined) {
        return;
      }
      var affiliation = maker.affiliation || {};
      var label =
        affiliation.displayName === null || affiliation.displayName === undefined
          ? textOf(maker.id)
          : textOf(affiliation.displayName);
      out.push({
        kind: "decisionMaker",
        id: String(maker.id),
        name: label,
        subtitle: "决策人 · " + textOf(affiliation.kind) + "：" + textOf(affiliation.id),
        at: null,
      });
    });
    out.sort(function (a, b) {
      return kindRank(a.kind) - kindRank(b.kind) || a.id.localeCompare(b.id);
    });
    return out;
  }

  var SEARCH_LIMIT = 20;

  /** 单项匹配名次：数字越小越优先；不命中 ⇒ -1。id 精确 > name 精确 > id 前缀 > name 前缀 > 包含。 */
  function matchRank(item, query) {
    if (!item) {
      return -1;
    }
    var id = textOf(item.id).toLowerCase();
    var name = textOf(item.name).toLowerCase();
    if (id === query) {
      return 0;
    }
    if (name === query) {
      return 1;
    }
    if (id.indexOf(query) === 0) {
      return 2;
    }
    if (name.indexOf(query) === 0) {
      return 3;
    }
    if (id.indexOf(query) >= 0) {
      return 4;
    }
    if (name.indexOf(query) >= 0) {
      return 5;
    }
    return -1;
  }

  /**
   * 搜索匹配（纯函数）：空查询 ⇒ 空数组；排序 = 匹配名次 → kind 档 → id 字典序；上限 {@link SEARCH_LIMIT} 条。
   * 只返回项本身（调用方拿 `kind/id/at` 定位）。
   */
  function searchMatches(index, query) {
    var q = query === null || query === undefined ? "" : String(query).trim().toLowerCase();
    if (q === "") {
      return [];
    }
    var hits = [];
    (Array.isArray(index) ? index : []).forEach(function (item) {
      var rank = matchRank(item, q);
      if (rank >= 0) {
        hits.push({ item: item, rank: rank });
      }
    });
    hits.sort(function (a, b) {
      return (
        a.rank - b.rank ||
        kindRank(a.item.kind) - kindRank(b.item.kind) ||
        textOf(a.item.id).localeCompare(textOf(b.item.id))
      );
    });
    return hits.slice(0, SEARCH_LIMIT).map(function (hit) {
      return hit.item;
    });
  }

  // ── F2：热力图（指标词表 / 分位数色标 / 图例模型；全部纯函数）────────────
  //
  // ★ 与后端 `GET /api/map/heatmap` 的契约同源：id/label/unit/help 的顺序固定；API 取数层不另设白名单。
  // ★ 未知输入一律 fail-closed：非法 method / bins 归一为分位数默认 7 档；非有限 value 不入统计、不着色。

  /** 8 个热力指标的**固定顺序**词表（`index.html` 的 option 由 map.js 从本表生成，不在 HTML 里另抄一份）。 */
  var HEATMAP_METRICS = [
    {
      id: "populationTotal",
      label: "人口·总",
      unit: "人",
      help: "批次口径（有批次的格）；时点快照",
    },
    {
      id: "populationRural",
      label: "人口·农村",
      unit: "人",
      help: "批次口径（有批次的格）；时点快照",
    },
    {
      id: "populationUrban",
      label: "人口·城市",
      unit: "人",
      help: "批次口径（有批次的格）；时点快照",
    },
    {
      id: "grainStock",
      label: "粮食·库存",
      unit: "毫粮",
      help: "逐格 actor GoodsAccount 粮余额合计（时点）",
    },
    {
      id: "grainDailyNeed",
      label: "粮食·日耗",
      unit: "毫粮/日",
      help: "最近一次结算日 naturalNeeds；与 economyHex.grainDailyConsumption 同源",
    },
    {
      id: "grainCoverageDays",
      label: "粮食·覆盖天数",
      unit: "天",
      help: "派生 grainStock ÷ grainDailyNeed（仅 dailyNeed>0）",
    },
    {
      id: "grainCycleUnmet",
      label: "粮食·周期缺口",
      unit: "毫粮",
      help: "整层不可用：unavailable 非空字符串、cells=[]（不要填 0；UI 显示原因）",
    },
    {
      id: "moneySilver",
      label: "货币·银",
      unit: "毫银",
      help: "逐格 actor GoodsAccount silver 余额合计（时点）",
    },
  ];

  /** 固定 7 色顺序调色板（低值 → 高值）。 */
  var HEATMAP_PALETTE = [
    "#1f2c56",
    "#28527a",
    "#2f8f9d",
    "#5bbf7a",
    "#b7d84b",
    "#f2c14e",
    "#e4572e",
  ];

  var HEATMAP_DEFAULT_BINS = 7;

  /** 数值 → 图例短文本：整数原样；小数保留 4 位（去掉尾零）；非有限 ⇒ “—”。 */
  function heatmapNumberText(value) {
    if (typeof value !== "number" || !isFinite(value)) {
      return "—";
    }
    if (Number.isInteger(value)) {
      return String(value);
    }
    return String(Math.round(value * 10000) / 10000);
  }

  /** 任意 notes 值 → 短文本（数字走 heatmapNumberText，其余 String；null/undefined ⇒ “—”）。 */
  function heatmapValueText(value) {
    if (value === null || value === undefined) {
      return "—";
    }
    if (typeof value === "number") {
      return heatmapNumberText(value);
    }
    return String(value);
  }

  /** notes（对象 / 数组 / 标量）→ 确定性的单行文本；无内容 ⇒ 空串。 */
  function heatmapNotesText(notes) {
    if (notes === null || notes === undefined) {
      return "";
    }
    if (Array.isArray(notes)) {
      return notes.map(heatmapValueText).join("；");
    }
    if (typeof notes === "object") {
      var keys = Object.keys(notes);
      if (!keys.length) {
        return "";
      }
      return keys
        .map(function (key) {
          return key + "=" + heatmapValueText(notes[key]);
        })
        .join("；");
    }
    return heatmapValueText(notes);
  }

  /**
   * 归一化色标选项（fail-closed，不抛）：`method` 只认 `quantile`、`bins` 只认 1..7 的整数；
   * 未知 method / 非法 bins 一律回落分位数默认 7 档。返回 `{method,bins}`。
   */
  function normalizeHeatmapOptions(options) {
    var opts = options && typeof options === "object" ? options : {};
    var methodOk = opts.method === undefined || opts.method === "quantile";
    var binsOk =
      typeof opts.bins === "number" &&
      isFinite(opts.bins) &&
      Math.floor(opts.bins) === opts.bins &&
      opts.bins >= 1 &&
      opts.bins <= HEATMAP_PALETTE.length;
    if (!methodOk || !binsOk) {
      return { method: "quantile", bins: HEATMAP_DEFAULT_BINS };
    }
    return { method: "quantile", bins: opts.bins };
  }

  /** 只取有限数值 value（`cells[].value`）；非有限 / 非数值一律不计入统计。 */
  function heatmapFiniteValues(cells) {
    var values = [];
    (Array.isArray(cells) ? cells : []).forEach(function (cell) {
      if (!cell) {
        return;
      }
      var value = cell.value;
      if (typeof value === "number" && isFinite(value)) {
        values.push(value);
      }
    });
    return values;
  }

  /** 统计：count/min/median/max；空输入 ⇒ min/median/max 为 null（不拿 0 冒充）。 */
  function heatmapStats(values) {
    var count = values.length;
    if (!count) {
      return { count: 0, min: null, median: null, max: null };
    }
    var sorted = values.slice().sort(function (a, b) {
      return a - b;
    });
    var median =
      count % 2 === 1
        ? sorted[(count - 1) / 2]
        : (sorted[count / 2 - 1] + sorted[count / 2]) / 2;
    return { count: count, min: sorted[0], median: median, max: sorted[count - 1] };
  }

  /**
   * 分位数分档（确定性）：先对排序后的有限值按**相同值**合并成 run，再把 run 列表均分成
   * `min(bins, run 数)` 组 ⇒ 同值永不裂成多档、也不编造空档。档边界用真实值（min/max 是数值）；
   * 颜色沿固定调色板按序铺开（最少 2 档时首档/末档一定命中调色板两端）。
   */
  function heatmapQuantileBins(values, binCount) {
    var sorted = values.slice().sort(function (a, b) {
      return a - b;
    });
    if (!sorted.length) {
      return [];
    }
    var runs = [];
    sorted.forEach(function (value) {
      var last = runs.length ? runs[runs.length - 1] : null;
      if (last && last.max === value) {
        last.count += 1;
      } else {
        runs.push({ min: value, max: value, count: 1 });
      }
    });
    var groupCount = Math.min(binCount, runs.length);
    var bins = [];
    var start = 0;
    for (var i = 0; i < groupCount; i += 1) {
      var end = i === groupCount - 1
        ? runs.length
        : Math.round(((i + 1) * runs.length) / groupCount);
      var bin = { min: runs[start].min, max: runs[end - 1].max, color: null, count: 0 };
      for (var j = start; j < end; j += 1) {
        bin.count += runs[j].count;
      }
      var colorIndex = groupCount <= 1
        ? 0
        : Math.round((i * (HEATMAP_PALETTE.length - 1)) / (groupCount - 1));
      bin.color = HEATMAP_PALETTE[colorIndex];
      bins.push(bin);
      start = end;
    }
    return bins;
  }

  /**
   * 热力图色标（纯函数）：返回 `{method,bins,colorOf,stats}`。
   *
   * <p>`bins` 每项 `{min,max,color,count}`（按值升序、同值不裂档、不编造空档，count 之和 == 参与统计格数）；
   * `colorOf(value)` 对非有限值 / 空 bins 恒 `null`，低于首档取首色、高于末档取末色，其余命中所在档；
   * 同一输入两次调用结果深比较一致。
   */
  function heatmapColorScale(cells, options) {
    var normalized = normalizeHeatmapOptions(options);
    var values = heatmapFiniteValues(cells);
    var bins = values.length ? heatmapQuantileBins(values, normalized.bins) : [];
    function colorOf(value) {
      if (typeof value !== "number" || !isFinite(value) || !bins.length) {
        return null;
      }
      for (var i = 0; i < bins.length; i += 1) {
        if (value <= bins[i].max) {
          return bins[i].color;
        }
      }
      return bins[bins.length - 1].color;
    }
    return {
      method: normalized.method,
      bins: bins,
      colorOf: colorOf,
      stats: heatmapStats(values),
    };
  }

  /** 由色标计划取色（第 4 项导出；`plan.colorOf` 缺席 ⇒ null，不猜颜色）。 */
  function heatmapColorFor(plan, value) {
    return plan && typeof plan.colorOf === "function" ? plan.colorOf(value) : null;
  }

  /** 档位文本：单值 ⇒ “123”；区间 ⇒ “1–10”。 */
  function heatmapRangeLabel(min, max) {
    var minText = heatmapNumberText(min);
    var maxText = heatmapNumberText(max);
    return minText === maxText ? minText : minText + "–" + maxText;
  }

  /**
   * 图例模型（纯函数、不碰 DOM）：返回 `{title,unit,scope,lines,unavailable,footnote}`。
   *
   * <p>`lines` 来自 `scalePlan.bins`（label = 数值区间/单值，count 随行）；`footnote` 含 count/min/median/max、
   * notes 文本、`unavailable` 原因（在场时）与“缺失数据格不着色、不填 0”的 caveat。空 payload / 空计划也确定性返回，不抛。
   */
  function heatmapLegend(payload, scalePlan) {
    var p = payload && typeof payload === "object" ? payload : {};
    var plan = scalePlan && typeof scalePlan === "object" ? scalePlan : {};
    var stats = plan.stats && typeof plan.stats === "object" ? plan.stats : null;
    var count = stats && typeof stats.count === "number" ? stats.count : 0;
    var lines = (Array.isArray(plan.bins) ? plan.bins : []).map(function (bin) {
      return {
        color: bin.color,
        label: heatmapRangeLabel(bin.min, bin.max),
        count: typeof bin.count === "number" ? bin.count : 0,
      };
    });
    var parts = [
      "统计：count=" +
        count +
        "，min=" +
        heatmapNumberText(stats ? stats.min : null) +
        "，median=" +
        heatmapNumberText(stats ? stats.median : null) +
        "，max=" +
        heatmapNumberText(stats ? stats.max : null),
    ];
    var notesText = heatmapNotesText(p.notes);
    if (notesText) {
      parts.push("notes：" + notesText);
    }
    var unavailable = p.unavailable === undefined ? null : p.unavailable;
    if (unavailable !== null && unavailable !== undefined && String(unavailable) !== "") {
      parts.push("不可用：" + unavailable);
    }
    parts.push("缺失数据格不着色、不填 0。");
    return {
      title: String(p.label || p.metric || "热力图"),
      unit: p.unit === undefined ? null : p.unit,
      scope: p.scope === undefined ? null : p.scope,
      lines: lines,
      unavailable: unavailable,
      footnote: parts.join("；"),
    };
  }

  // ── 国家汇总（世界总览的三国卡片）────────────────────────────────────

  function sumGoodsTotal(rows) {
    var totals = {};
    var known = false;
    rows.forEach(function (row) {
      var goods = row && row.goodsTotal;
      if (!goods || typeof goods !== "object" || Array.isArray(goods)) {
        return;
      }
      Object.keys(goods).forEach(function (id) {
        var value = goods[id];
        if (typeof value === "number" && isFinite(value)) {
          totals[id] = (totals[id] || 0) + value;
          known = true;
        }
      });
    });
    return known ? totals : null;
  }

  function sumKnown(rows, pick) {
    var total = 0;
    var known = false;
    rows.forEach(function (row) {
      var value = pick(row);
      if (typeof value === "number" && isFinite(value)) {
        total += value;
        known = true;
      }
    });
    return known ? total : null;
  }

  /**
   * 国家汇总（纯函数）：把区域汇总按 `meta.tag = nation:<id>` 归并成卡片。
   *
   * <p>★ 输入 `regions` 是 `/api/map/regions/summary` 的 `regions[]`；`cities` 仅用于"区域汇总缺席时按城市
   * region 补城市计数"的降级路径（有汇总时以汇总为准，不重复计）。人口/单位允许多区域重叠重复计入 —— 与后端
   * `population` 的"区域格集求和"口径一致，卡片 hint 会写明。
   *
   * @return `[{id,name,hexCount,population,ruralPopulation,urbanPopulation,cityCount,cityPopulation,
   *     unitCount,govCount,grainStock,silverMoney,goodsTotal,regionCount,decisionMakerCount}…]`，按 id 字典序；
   *     无国家区域 ⇒ 空数组。新增字段缺数据 ⇒ `null`（不拿 0 冒充）；`goodsTotal` 为“商品 id → 合计”。
   */
  function nationSummaries(regions, cities, units, makers) {
    var rows = Array.isArray(regions) ? regions : [];
    var byNation = {};
    var order = [];
    rows.forEach(function (region) {
      if (!isNationRegion(region)) {
        return;
      }
      var id = String(region.meta.tag).slice(NATION_TAG_PREFIX.length);
      if (id === "") {
        return;
      }
      if (!Object.prototype.hasOwnProperty.call(byNation, id)) {
        byNation[id] = [];
        order.push(id);
      }
      byNation[id].push(region);
    });
    order.sort(function (a, b) {
      return a.localeCompare(b);
    });
    var cityCountFallback = {};
    (Array.isArray(cities) ? cities : []).forEach(function (city) {
      if (!validCity(city) || city.region === null || city.region === undefined) {
        return;
      }
      cityCountFallback[String(city.region)] = (cityCountFallback[String(city.region)] || 0) + 1;
    });
    var makerCount = {};
    (Array.isArray(makers) ? makers : []).forEach(function (maker) {
      var affiliation = (maker && maker.affiliation) || {};
      if (affiliation.kind !== "nation" || affiliation.id === null || affiliation.id === undefined) {
        return;
      }
      var id = String(affiliation.id);
      makerCount[id] = (makerCount[id] || 0) + 1;
    });
    return order.map(function (id) {
      var nationRegions = byNation[id];
      var name = nationRegions[0].name === null || nationRegions[0].name === undefined
        ? id
        : nationRegions[0].name;
      var cityCount =
        sumKnown(nationRegions, function (region) {
          return region.cityCount;
        }) || 0;
      if (!nationRegions.some(function (region) {
        return typeof region.cityCount === "number";
      })) {
        nationRegions.forEach(function (region) {
          cityCount += cityCountFallback[String(region.id)] || 0;
        });
      }
      return {
        id: id,
        name: String(name),
        hexCount: sumKnown(nationRegions, function (region) {
          return region.hexCount;
        }),
        population: sumKnown(nationRegions, function (region) {
          return region.population;
        }),
        // ★ F2：区域汇总新增的逐格口径字段（同一 meta.tag=nation:<id> 的区域求和；缺 ⇒ null）。
        ruralPopulation: sumKnown(nationRegions, function (region) {
          return region.ruralPopulation;
        }),
        urbanPopulation: sumKnown(nationRegions, function (region) {
          return region.urbanPopulation;
        }),
        cityCount: cityCount,
        cityPopulation: sumKnown(nationRegions, function (region) {
          return region.cityPopulation;
        }),
        unitCount: sumKnown(nationRegions, function (region) {
          return region.unitCount;
        }),
        govCount: sumKnown(nationRegions, function (region) {
          return region.govCount;
        }),
        grainStock: sumKnown(nationRegions, function (region) {
          return region.grainStock;
        }),
        silverMoney: sumKnown(nationRegions, function (region) {
          return region.silverMoney;
        }),
        goodsTotal: sumGoodsTotal(nationRegions),
        regionCount: nationRegions.length,
        decisionMakerCount: makerCount[id] || 0,
      };
    });
  }

  window.SimosWorldModel = {
    LAYER_KEYS: LAYER_KEYS,
    DEFAULT_LAYERS: DEFAULT_LAYERS,
    LOD_WORLD_MAX_SCALE: LOD_WORLD_MAX_SCALE,
    LOD_REGION_MAX_SCALE: LOD_REGION_MAX_SCALE,
    NATION_LABEL_MIN_SCALE: NATION_LABEL_MIN_SCALE,
    NATION_TAG_PREFIX: NATION_TAG_PREFIX,
    SEARCH_LIMIT: SEARCH_LIMIT,
    layerVisibility: layerVisibility,
    layerPreset: layerPreset,
    lodForScale: lodForScale,
    nationNamesVisible: nationNamesVisible,
    tierRank: tierRank,
    cityCategory: cityCategory,
    isCapitalCity: isCapitalCity,
    cityMarkerPlan: cityMarkerPlan,
    nationLabelPlan: nationLabelPlan,
    searchIndex: searchIndex,
    searchMatches: searchMatches,
    nationSummaries: nationSummaries,
    // ★ F2：热力图词表 / 色标 / 图例 / 取色（纯函数；API 层不另设指标白名单）。
    HEATMAP_METRICS: HEATMAP_METRICS,
    HEATMAP_PALETTE: HEATMAP_PALETTE,
    heatmapColorScale: heatmapColorScale,
    heatmapLegend: heatmapLegend,
    heatmapColorFor: heatmapColorFor,
  };
})();
