// map.js —— 共享六角 Canvas 渲染器（M5 T9 只读；M7 T4 缩放/平移/区域填充/点选联动；M7 T6 区域填充用 RegionMeta.color）。
//
// 两处宿主共用同一份渲染器：
//   · 旧页 /map（map.html）：DOM 有 #canvas / #hex-detail / #hex-facets / #hex-status / #cell-size / #reload
//   · 工作台 /（index.html）：DOM 有 #canvas-mount / #selection-detail / #left-status / #map-status
//
// ★ 地形色一律来自后端 /api/map/overview 的 terrainTypes[].color（T4 删除硬编码 15 色表）。
//   词表外的地形用唯一兜底色 FALLBACK_COLOR 并在控制台记一次——不悄悄回退到某个像地形的颜色。
// ★ 区域填充色一律来自 /api/map/region/{id} 的 meta.color（T6；合法形如 #RRGGBB），非法/缺失用
//   REGION_FALLBACK_COLOR 并在控制台记一次——T4 的固定半透明色已由 T6 收口。
// ★ 所有取数经 window.SimosApi 并带 window.SimosApp.target()（T3 约定，T4 由本文件与 panels.js 收口）。
// ★ 写路径唯一：本文件的写（下路线/取消移动）一律经 window.SimosApp.writeCommand → /api/command（R8 allowlist）。

(function () {
  "use strict";

  var app = window.SimosApp;
  var api = window.SimosApi;
  // ★ F1：世界视图纯函数（城市 LOD / 搜索索引与匹配 / 国家汇总 / 图层归一）。两宿主页都在本文件之前引入。
  var worldModel = window.SimosWorldModel || null;
  // ★ M12 拆分第一步：纯几何 / 颜色 / 区域边界已搬到兄弟文件（hexgeom.js / hexcolor.js /
  //   regionShape.js，两份宿主页按依赖顺序在本文件之前引入）。此处按名取回 ⇒ **调用点与
  //   window.SimosMap 暴露处一字未改**。regionFallbackWarned 是可变旗标，不取快照（见下）。
  var hexGeom = window.SimosHexGeom;
  var hexColor = window.SimosHexColor;
  var regionShape = window.SimosRegionShape;
  var MIN_SCALE = hexGeom.MIN_SCALE;
  var MAX_SCALE = hexGeom.MAX_SCALE;
  var DIR_VECTORS = hexGeom.DIR_VECTORS;
  var FALLBACK_COLOR = hexColor.FALLBACK_COLOR;
  var REGION_FALLBACK_COLOR = hexColor.REGION_FALLBACK_COLOR;
  var HEX_COLOR_RE = hexColor.HEX_COLOR_RE;
  var clamp = hexGeom.clamp;
  var hexToPixel = hexGeom.hexToPixel;
  var hexCorners = hexGeom.hexCorners;
  var cornerKey = hexGeom.cornerKey;
  var hexRound = hexGeom.hexRound;
  var pixelToHex = hexGeom.pixelToHex;
  var axialNeighbors = hexGeom.axialNeighbors;
  var axialDistance = hexGeom.axialDistance;
  var hexLine = hexGeom.hexLine;
  var worldToScreen = hexGeom.worldToScreen;
  var screenToWorld = hexGeom.screenToWorld;
  var zoomAt = hexGeom.zoomAt;
  var fitView = hexGeom.fitView;
  var regionBoundaryRings = regionShape.regionBoundaryRings;
  var resolveRegionColor = hexColor.resolveRegionColor;
  var regionColor = hexColor.regionColor;
  var withAlpha = hexColor.withAlpha;

  var BASE_CELL = 34; // 世界坐标的基准格边长（px）
  // ★ M7e T1：路线对比度修复——旧值 `rgba(255,214,130,0.35)`（35% 透明黄）叠在沙漠黄上不可辨
  //   （用户"看不出发生了什么"的物理原因）。改为「深色外描边 + 不透明琥珀底 + 近白亮段」三层。
  var ROUTE_OUTLINE_COLOR = "rgba(12, 8, 2, 0.95)"; // 整条路线的深色外描边（保证在沙漠黄上可辨）
  var ROUTE_OUTLINE_WIDTH = 12;
  var ROUTE_BASE_COLOR = "#f59e0b"; // 整条路线的实色底（鲜琥珀橙，与沙漠黄拉开色相）
  var ROUTE_REMAINING_COLOR = "#ffffff"; // 未走完的那一段的亮色层（纯白）
  var ROUTE_BASE_WIDTH = 7;
  var ROUTE_REMAINING_WIDTH = 4;
  var HIGHLIGHT_ALPHA = 0.42; // 区域填充透明度（保留地形可见性）
  var REGION_FOCUS_ALPHA = 0.52; // 区域编辑：焦点区域（正常色、更实）
  var REGION_FADE_ALPHA = 0.13; // 区域编辑：其它区域（淡色＝同色相但更透明）
  // ★ U3：单区域选中（"选中 tag 旗下某个区域"）⇒ 比 tag 全选（0.42）更亮，且淡色只压同 tag 的兄弟。
  var REGION_SINGLE_FOCUS_ALPHA = 0.62;
  // ★ U1：区域查看 / 区域编辑模式下压暗地形底图（让区域填充/边界成为视觉主体）。
  var REGION_DIM_COLOR = "#0a0d12";
  var REGION_DIM_ALPHA = 0.55;
  var REGION_DIM_MODES = ["region", "region-edit"];
  // ★ U2：区域名的最小缩放阈值（世界视图 scale≈0.06 时 97 个标签会堆叠成噪声）。
  var REGION_NAME_MIN_SCALE = 0.25;
  /**
   * ★ 2026-09-24（用户报障）：「区域查看/编辑模式下，**启动就加载图上所有区域名称字段，导致非常卡**，
   * 改成和 hex 一样的**根据屏幕所见范围**渲染」。⇒ 区域名只画屏幕可见范围内的那些（外加这个裕量，
   * 免得边界上的标签在拖动时忽隐忽现）。量级取字号上限（40px）加上描边与拖动的余量。
   */
  var REGION_NAME_VISIBLE_MARGIN = 64;
  // ★ V2：区域名**只在区域查看/区域编辑两个模式**显示（常规模式下用户实测"区域名称也被显示了"）。
  //   开关（regionNamesEnabled）仍保留，但只在两模式内生效。
  var REGION_NAME_MODES = ["region", "region-edit"];
  var REGION_NAMES_STORAGE_KEY = "simos.regionNames.v1";
  // ★ U2：区域名开关（默认开，localStorage 持久化；node 宿主无 localStorage ⇒ typeof 守卫）。
  var regionNamesEnabled = loadRegionNamesEnabled();
  var ZOOM_WHEEL = 0.0016; // 滚轮 deltaY → 缩放指数系数
  var OLD_PAGE_CANVAS_HEIGHT = 620; // 旧页 /map 的固定画布高度（工作台铺满视口，不用固定值）
  var FIT_PAD = 24; // fitView 的世界四周留白（初始适配与"回到世界中心"共用）

  // ★ M9 T4（档 0 止血）+ M9 T13/T14：大图渲染的性能开关。
  //   · terrainMode：M9 T13 起地形唯一来源是**权威块多边形**（"blocks"），不再有逐格通道。
  //   · terrainCacheEnabled：地形层离屏位图缓存（pan 时 drawImage blit；数据变 / zoom 停 / resize 才重建）。
  // ★ M8-R §八：地形块之间**不画线**（旧 borderMinScreenPx/边框层已删）——不同地形只靠颜色区分。
  var perfConfig = {
    terrainMode: "blocks",
    terrainCacheEnabled: true,
  };

  // ★ M8-S §九：区域边界**不做任何简化/平滑**——严格贴着所属六边形最外侧的精确轮廓（RDP 已撤销）。
  var TERRAIN_CACHE_MARGIN = 256; // 地形位图在视口四周多留的 CSS 像素（pan 容差）
  var ZOOM_SETTLE_MS = 180; // 缩放停止后重建地形位图的防抖窗口

  // ── 纯几何（e2e 用 page.evaluate 直接断言；无 DOM、无 IO）────────────────
  //   ★ M12 拆分第一步：clamp / hexToPixel / hexCorners / cornerKey / hexRound / pixelToHex /
  //     axialNeighbors / axialDistance / hexLine / worldToScreen / screenToWorld / zoomAt / fitView
  //     已搬到 hexgeom.js；resolveRegionColor / regionColor / withAlpha 已搬到 hexcolor.js；
  //     regionBoundaryRings 已搬到 regionShape.js。均在文首"取回块"按名取回，调用点未改。

  /** 纯函数：路线中"尚未走完"的那一段（从 currentHex 起；ARRIVED 或找不到 currentHex ⇒ 空）。 */
  function remainingPath(path, currentHex, status) {
    if (!path || path.length < 2 || !currentHex || status === "ARRIVED") {
      return [];
    }
    for (var i = 0; i < path.length; i++) {
      if (path[i].q === currentHex.q && path[i].r === currentHex.r) {
        return path.slice(i);
      }
    }
    return [];
  }

  // ── 渲染器工厂（已搬到 renderer.js）──────────────────────────────────
  //   ★ M12 第二波：createRenderer 整体在 renderer.js，本文件经 window.SimosCreateRenderer
  //     调用；它需要的模块级常量/状态/函数见下方 window.SimosMapCore。

  // ── 宿主接线 ──────────────────────────────────────────────────────────

  var active = null;
  var host = {
    lastTargetKey: null,
    lastHighlights: null,
    lastMode: null,
    fittedBranch: null,
    viewInitialized: false,
    regionCache: {},
    targetTimer: null,
    routeMode: false,
    routePath: [],
    editBusy: false,
    brushTerrain: null,
    paletteSignature: null,
    // ★ 2026-09-24 可用性修复：上次已为其"保证可见"的选中单位 id（选中项换了才重新居中，见 onStateChange）。
    lastEnsuredUnitId: null,
    // ★ F1：上次已为其"保证可见"的选中城市 id（与单位分开记，互不误判"选中项没换"）。
    lastEnsuredCityId: null,
    // ★ F1：世界视图数据（城市/区域汇总/经济总览/决策人）与搜索索引；map.js 是取数收口，面板只读这份投影。
    worldCities: [],
    worldRegionSummaries: [],
    worldEconomyOverview: null,
    worldDecisionMakers: [],
    worldUnits: [],
    searchIndex: [],
    // ★ F1：图层开关的 localStorage 降级内存态（无 localStorage 宿主用；键名与持久化一致）。
    layerMemory: null,
    // ★ F2：热力层持久化降级内存态 / 轮次 token（挡过期响应）/ 最近一次成功计划与原文（透明度拖动复用）。
    heatmapMemory: null,
    heatmapToken: 0,
    heatmapPlan: null,
    heatmapPayload: null,
    heatmapScale: null,
    mapEditBusy: false,
    mapEditTool: "terrain",
    mapEditSubtool: "terrain",
    edgeMode: "",
    randomizeSelection: [],
    regionInfoKey: null,
    regionFocus: null,
    lastRegionFocus: undefined,
    regionDraft: {},
    regionOp: "add",
    regionNameConflict: null,
    regionDeleteArmed: false,
    regionEditBusy: false,
    regionFaded: [],
    overviewRegions: [],
    regionLoadToken: 0,
    regionHighlightToken: 0,
    regionFocusColor: null,
    // ★ 决策人可见范围层：token 挡过期轮次；decisionScope 是最近一次算好的计划（debug/断言用）。
    decisionScopeToken: 0,
    decisionScope: null,
    lastDecisionFocus: undefined,
  };

  // ★ F2：热力层当前选择（模块级可变绑定；持久化键见 HEATMAP_STORAGE_KEY 一节）。
  //   ★ 不透明度统一走**真·千分比**：持久化 150..850（默认 550），滑块刻度 15..85，渲染 opacity = 千分比/1000。
  var heatmapMetric = "";
  var heatmapOpacityPerMille = 550;

  function targetKey() {
    var t = app.target();
    return t.branch + "#" + (t.revision === null || t.revision === undefined ? "head" : t.revision);
  }

  function regionCacheKey(id) {
    return id + "@" + targetKey();
  }

  async function reloadOverview() {
    var status = app.byId("map-status");
    app.statusMessage(status, "载入地图总览…", "muted");
    try {
      var body = await api.cachedMapOverview(app.target());
      var branch = app.target().branch;
      var needFit = !active.isReady() || host.fittedBranch !== branch;
      active.setData(body);
      host.overviewRegions = body.regions || [];
      // ★ M12 第六波：地图编辑/区域编辑两簇的宿主 UI 已搬到 map-mapeditor.js / map-regioneditor.js。
      //   本文件对它们只做**惰性** window.SimosMapEditor.* / window.SimosMapRegionEditor.* 调用。
      window.SimosMapEditor.renderTerrainPalette(body.terrainTypes || []);
      await reloadUnits();
      // ★ F1：世界视图数据（城市 / 区域汇总 / 经济总览 / 决策人 / 国家着色 / 搜索索引）。
      //   三个新端点全部带 target 走共享缓存；任何一路失败只退化为该图层缺数据，不拖垮地图主流程。
      await reloadWorldLayers(body);
      var mode = app.getState().mode;
      if (mode === "map-edit" || mode === "region-edit") {
        window.SimosMapEditor.renderRegionInfo(app.getState().selection);
      }
      if (mode === "region-edit") {
        window.SimosMapRegionEditor.renderRegionEditor();
        reloadRegionEditHighlight();
        refreshFocusHexes();
      }
      if (host.isWorkbench) {
        // ★ M7g T1：只初始适配**一次**（不再每次目标/分支变化都把世界重新塞进视口）；
        //   resize 不重置；用户想回中心时走 #view-reset / Home。旧页保持原行为。
        if (!host.viewInitialized) {
          host.viewInitialized = true;
          active.fit();
        }
      } else if (needFit) {
        host.fittedBranch = branch;
        active.fit();
      }
      active.render();
      app.statusMessage(
        status,
        "已载入 " +
          body.hexCount +
          " 格（mapId=" +
          app.text(body.mapId) +
          "，区域 " +
          (body.regions || []).length +
          "，城市 " +
          active.debug().cityCount +
          "，单位 " +
          active.debug().unitCount +
          "）",
        "ok"
      );
    } catch (e) {
      app.statusMessage(status, "地图载入失败：" + e.message, "err");
    }
  }

  async function reloadUnits() {
    try {
      // ★ 2026-09-24 交战：单位与**真实交战记录**同批载入（renderer 从这一份聚合里读两者）。
      //   交战取数失败**不拖垮单位列表**（各自兜成空：交战只是叠加层，没有它仍能画图）。
      var results = await Promise.all([
        api.cachedUnits(app.target()),
        api.cachedCombats(app.target()).catch(function () {
          return null;
        }),
      ]);
      active.setUnits((results[0] && results[0].units) || []);
      active.setCombats((results[1] && results[1].combats) || []);
      // ★ F1：搜索索引 / 世界总览需要带 module/parent 的单位列表——与 renderer 同一份服务端读数。
      host.worldUnits = (results[0] && results[0].units) || [];
      // ★ F1：GOV 辖区随单位同批重算（切 target / revision 沿本链自动覆盖旧数据）。
      await reloadGovJurisdictions();
      return null;
    } catch (e) {
      active.setUnits([]);
      active.setCombats([]);
      host.worldUnits = [];
      // ★ F1：单位取数失败 ⇒ 辖区必须清空，不能留旧 revision 的覆盖层。
      if (active && active.setGovJurisdictions) {
        active.setGovJurisdictions([]);
      }
      return e.message;
    }
  }

  /**
   * ★ F1：GOV 辖区数据（只在工作台取数；旧 /map 直接清空）。
   *
   * <p>数据源 = {@code host.worldUnits}（即 `/api/units` 的 units）：筛 `module.kind === "gov"` 且
   * `jurisdiction.regions` 非空（regionId → 税率）的 GOV；每个 regionId 走既有 {@link fetchRegionCached}
   * 的同一 target 缓存（**不另写 fetch**），取其 `hexes`，在**同一 GOV 的各辖区并集内**按 `q_r` 去重。
   * 汇总 `[{govId,name,hexes}]` 推给 renderer；失败 / 无 GOV / 辖区全空 ⇒ 空数组（不抛、不编坐标）。
   *
   * <p>renderer 侧会再做一次同口径去重并用 regionBoundaryRings 算精确边界；切换 target / revision 由现有
   * `reloadOverview → reloadUnits` 链自动重算，故这里不另加监听。
   */
  async function reloadGovJurisdictions() {
    if (!host.isWorkbench) {
      if (active && active.setGovJurisdictions) {
        active.setGovJurisdictions([]);
      }
      return;
    }
    var out = [];
    try {
      var govs = (host.worldUnits || []).filter(function (unit) {
        var regions = unit && unit.module && unit.module.kind === "gov"
          ? unit.jurisdiction && unit.jurisdiction.regions
          : null;
        return (
          !!regions &&
          typeof regions === "object" &&
          !Array.isArray(regions) &&
          Object.keys(regions).length > 0
        );
      });
      for (var i = 0; i < govs.length; i += 1) {
        var gov = govs[i];
        var govId = String(gov.id);
        var regionIds = Object.keys(gov.jurisdiction.regions);
        var seen = {};
        var hexes = [];
        for (var j = 0; j < regionIds.length; j += 1) {
          var region = null;
          try {
            region = await fetchRegionCached(regionIds[j]);
          } catch (e) {
            region = null; // 单区失败只丢这一区；其余辖区照常汇总。
          }
          var list = region && Array.isArray(region.hexes) ? region.hexes : [];
          for (var k = 0; k < list.length; k += 1) {
            var hex = list[k];
            if (
              !hex ||
              typeof hex.q !== "number" ||
              typeof hex.r !== "number" ||
              !isFinite(hex.q) ||
              !isFinite(hex.r)
            ) {
              continue;
            }
            var key = hex.q + "_" + hex.r;
            if (Object.prototype.hasOwnProperty.call(seen, key)) {
              continue;
            }
            seen[key] = true;
            hexes.push({ q: hex.q, r: hex.r });
          }
        }
        if (!hexes.length) {
          continue; // 该 GOV 的辖区一格都没读到 ⇒ 不推空壳覆盖层。
        }
        var name =
          gov.name === null || gov.name === undefined || gov.name === ""
            ? govId
            : String(gov.name);
        out.push({ govId: govId, name: name, hexes: hexes });
      }
    } catch (e) {
      out = []; // 兜底：任何意外都清空推给 renderer，不把半成品/旧数据留在图上。
    }
    if (active && active.setGovJurisdictions) {
      active.setGovJurisdictions(out);
    }
  }

  /**
   * ★ F1：世界视图数据取数收口（城市 / 区域汇总 / 经济总览 / 决策人 / 国家着色 / 搜索索引）。
   *
   * <p>★ 三个新端点都经 api 的 **target 记忆化缓存**：本函数与右栏总览、hex 详情可能同时要同一份数据，
   * 共享缓存保证"同一 URL×target 只发一次"。任一路失败只让对应图层缺数据（如实显示），不拖垮地图主流程。
   */
  async function reloadWorldLayers(overviewBody) {
    var results = await Promise.all([
      api.cachedCities(app.target()).catch(function () {
        return null;
      }),
      api.cachedRegionSummaries(app.target()).catch(function () {
        return null;
      }),
      api.cachedEconomyOverview(app.target()).catch(function () {
        return null;
      }),
      api.cachedDecisionMakers(app.target()).catch(function () {
        return null;
      }),
    ]);
    var cities = (results[0] && results[0].cities) || [];
    var regionSummaries = (results[1] && results[1].regions) || [];
    var economyOverview = results[2] || null;
    var makers = (results[3] && results[3].decisionMakers) || [];
    host.worldCities = cities;
    host.worldRegionSummaries = regionSummaries;
    host.worldEconomyOverview = economyOverview;
    host.worldDecisionMakers = makers;
    active.setCities(cities);
    active.setDecisionMakers(makers);
    host.searchIndex =
      worldModel && worldModel.searchIndex
        ? worldModel.searchIndex(overviewBody, cities, host.worldUnits, makers)
        : [];
    // ★ 国家着色只画工作台（旧 /map 保持原渲染行为）；区域 hex 懒拉走既有 mapRegion 缓存。
    if (host.isWorkbench) {
      await reloadNationFills();
    }
    if (window.SimosPanelRight && window.SimosPanelRight.setWorldData) {
      window.SimosPanelRight.setWorldData({
        cities: cities,
        regionSummaries: regionSummaries,
        economyOverview: economyOverview,
        units: host.worldUnits,
        decisionMakers: makers,
      });
    }
  }

  /** ★ F1：国家着色数据（`meta.tag` 以 `nation:` 开头的区域 ⇒ 拉 hex 集合 ⇒ 推给 renderer）。 */
  async function reloadNationFills() {
    var nationRegions = (host.overviewRegions || []).filter(function (region) {
      return nationTagOf(region) !== null;
    });
    var results = await Promise.all(
      nationRegions.map(function (region) {
        return fetchRegionCached(String(region.id)).catch(function () {
          return null;
        });
      })
    );
    active.setNationRegions(
      results.filter(function (region) {
        return !!region;
      })
    );
  }

  /** 按 id 懒拉区域详情并缓存（键含 target）；失败折成空 hex 集合（不整块崩）。 */
  async function fetchRegionCached(id) {
    var cacheKey = regionCacheKey(id);
    var cached = host.regionCache[cacheKey];
    if (!cached) {
      try {
        cached = await api.mapRegion(id, app.target());
      } catch (e) {
        cached = { id: id, hexes: [] };
        window.console.warn("[SimosMap] 区域拉取失败：" + id + "：" + e.message);
      }
      // ★ 先存本地再写回：`host.regionCache = {}` 会在 await 期间被 `scheduleTargetReload` 整份替换，
      //   若直接写 `host.regionCache[key] = await …`，被 await 暂停的是**对旧对象的引用**，写进旧对象后
      //   再读新对象就会拿到 undefined（M8-S 实测的 2 次 pageerror 根因）。
      host.regionCache[cacheKey] = cached;
    }
    return cached;
  }

  /** 把焦点区域的 hex 集合推给渲染器的"边界小点层"（null/失败 ⇒ 不画点，不静默造点）。 */
  async function refreshFocusHexes() {
    if (app.getState().mode !== "region-edit" || !host.regionFocus) {
      host.regionFocusColor = null;
      active.clearFocusHexes();
      return null;
    }
    var id = host.regionFocus;
    var token = ++host.regionLoadToken;
    var region = await fetchRegionCached(id);
    if (token !== host.regionLoadToken || host.regionFocus !== id) {
      return null;
    }
    host.regionFocusColor = regionColor(region.meta);
    active.setFocusHexes(region.hexes || [], host.regionFocusColor);
    return region;
  }

  // ── 区域高亮（M8 T9）：区域查看与区域编辑**共用一条路径** ─────────────────────
  // ★ 要求（计划 T9）：区域查看模式"选区域 ⇒ 其它淡色"，与区域编辑的同类效果**不得各写一份**
  //   （先例 T8：`section.region-info-editor[data-modes="map-edit region-edit"]` 共用同一节 UI）。
  //   两模式的差别全部收敛成**参数**（焦点透明度、无焦点时是否仍淡色），实现只有一处。
  var REGION_VIEW_HIGHLIGHT = {
    focusAlpha: HIGHLIGHT_ALPHA, // 区域查看沿用 M7 的填充透明度（选中区视觉与 M7 一致）
    fadeAlpha: REGION_FADE_ALPHA,
    fadeWhenNoFocus: false, // 无选中 ⇒ 一个填充都不画（M7 语义）
    focusOutlineAlpha: 1,
    fadeOutlineAlpha: 0.45,
  };
  var REGION_EDIT_HIGHLIGHT = {
    focusAlpha: REGION_FOCUS_ALPHA, // T10：焦点区更实
    fadeAlpha: REGION_FADE_ALPHA,
    fadeWhenNoFocus: true, // T10：无焦点 ⇒ 全部淡色
    focusOutlineAlpha: 1,
    fadeOutlineAlpha: 0.45,
  };
  // ★ U3：单区域选中（用户原定计划"点击选中 tag 旗下的一个区域 ⇒ 该区域更亮、淡色只压同 tag 其他区域"）。
  //   与 REGION_VIEW_HIGHLIGHT 的差别只有两处：焦点更亮（0.62 vs 0.42）、淡色范围收窄到同 tag。
  var REGION_SINGLE_HIGHLIGHT = {
    focusAlpha: HIGHLIGHT_ALPHA,
    singleFocusAlpha: REGION_SINGLE_FOCUS_ALPHA,
    fadeAlpha: REGION_FADE_ALPHA,
    fadeWhenNoFocus: false,
    fadeScope: "same-tag",
    focusOutlineAlpha: 1,
    fadeOutlineAlpha: 0.45,
  };

  /** ★ U1 纯函数：区域模式（region / region-edit）⇒ 地形压暗 alpha，其余模式 ⇒ 0。 */
  function terrainDimAlpha(mode) {
    return REGION_DIM_MODES.indexOf(mode) >= 0 ? REGION_DIM_ALPHA : 0;
  }

  /** ★ V2 纯函数：区域名是否可画 —— **开关开着 且 当前模式属于区域两模式**。 */
  function regionNamesVisible(mode) {
    return regionNamesEnabled && REGION_NAME_MODES.indexOf(mode) >= 0;
  }

  /** ★ U2 纯函数：区域名落点（配方照 GSimulator）。无 `label` / `hexCount≤0` ⇒ null。 */
  function regionLabelLayout(region, scale) {
    if (!region || !region.label || region.label.q === undefined || region.label.r === undefined) {
      return null;
    }
    var hexCount = typeof region.hexCount === "number" ? region.hexCount : 0;
    if (hexCount <= 0 || !(scale > 0)) {
      return null;
    }
    var text = region.name === null || region.name === undefined ? region.id : region.name;
    if (text === null || text === undefined || String(text) === "") {
      return null;
    }
    return {
      q: region.label.q,
      r: region.label.r,
      fontSize: Math.max(8, Math.min(40, Math.sqrt(hexCount) * 1.8)) / scale,
      text: String(text),
    };
  }

  /**
   * ★ 2026-09-24：区域名的**可见计划**——只产出**屏幕可见范围内**（外加 {@code margin}）的标签。
   *
   * <p>由来：用户报「区域查看/编辑模式下，启动就加载图上所有区域名称字段，导致非常卡，改成和 hex 一样的
   * 根据屏幕所见范围渲染」。原实现每帧对**全部**区域（真档 252 个）算落点 + 逐条 stroke/fill 文字，与视口无关；
   * 现在按屏幕坐标裁剪，每帧只画屏幕上那几条 ⇒ 帧开销与**可见区域数**成正比，而不是与全图区域数成正比。
   *
   * <p>★ **视口判据写在这里、不复用 {@code markerScreenVisible}**：那个函数的口径是"把视口**内缩** margin"
   * （单位标记要判断"是不是足够靠里 ⇒ 需要重新居中"），而这里要的恰恰相反——**外扩** margin（屏幕上再往外
   * 一点也留着，免得拖动时边界标签忽隐忽现）。两者共用会靠一个负 margin 掩饰，读的人多半会看反。
   *
   * <p>纯函数（无 DOM/IO）：落点配方仍是 {@link #regionLabelLayout}（质心 hex + 字号 ∝√格数 ÷ zoom），
   * 本函数只多做两件事：把落点换成屏幕坐标、按视口裁掉看不到的。
   *
   * @param regions `/api/map/overview` 的 `regions[]`（含 `label` 质心 hex 与 `hexCount`）
   * @param view 当前视图 `{scale,tx,ty}`（世界像素 → 屏幕像素）
   * @param viewport 视口 `{width,height}`（CSS px）
   * @param margin 视口**外**的裕量（px）；`undefined` ⇒ {@link #REGION_NAME_VISIBLE_MARGIN}
   * @param cellSize 每格边长（px，世界尺度；renderer 的当前值）
   * @return `[{text,fontSize,x,y}…]`，`x/y` 是**世界坐标**（调用方已在世界变换里画，不必再换算）
   */
  function regionNamePlan(regions, view, viewport, margin, cellSize) {
    var out = [];
    if (!view || !(view.scale > 0) || !viewport) {
      return out;
    }
    var slack = typeof margin === "number" && margin >= 0 ? margin : REGION_NAME_VISIBLE_MARGIN;
    (regions || []).forEach(function (region) {
      var layout = regionLabelLayout(region, view.scale);
      if (!layout) {
        return;
      }
      var world = hexToPixel(layout.q, layout.r, cellSize);
      var screen = worldToScreen(world, view);
      if (
        screen.x < -slack ||
        screen.y < -slack ||
        screen.x > viewport.width + slack ||
        screen.y > viewport.height + slack
      ) {
        return;
      }
      out.push({ text: layout.text, fontSize: layout.fontSize, x: world.x, y: world.y });
    });
    return out;
  }

  /** 区域的 tag（逐字，null 保留）——调色板条目用 `tag`、overview 条目用 `meta.tag`，两处都读。 */
  function regionTag(region) {
    if (!region) {
      return null;
    }
    var tag = region.tag !== undefined ? region.tag : region.meta ? region.meta.tag : null;
    return tag === undefined ? null : tag;
  }

  function loadRegionNamesEnabled() {
    try {
      if (typeof window !== "undefined" && window.localStorage) {
        var raw = window.localStorage.getItem(REGION_NAMES_STORAGE_KEY);
        if (raw === "0") {
          return false;
        }
        if (raw === "1") {
          return true;
        }
      }
    } catch (e) {
      return true;
    }
    return true;
  }

  function persistRegionNamesEnabled(value) {
    try {
      if (typeof window !== "undefined" && window.localStorage) {
        window.localStorage.setItem(REGION_NAMES_STORAGE_KEY, value ? "1" : "0");
      }
    } catch (e) {
      return;
    }
  }

  /** ★ M12 第五波：map.js 仍是 regionNamesEnabled 这个可变绑定的所有者；工作台浮层（现居
   *   map-hostpage.js）经 window.SimosMapCore.setRegionNamesEnabled 写回，避免取快照。 */
  function setRegionNamesEnabled(value) {
    regionNamesEnabled = !!value;
  }

  // ── 决策模式（T7）：国家 tag ⇒ 区域集合（C12）────────────────────────────────
  //
  // ★ R13（SDSimos spec）："国家区域" = `meta.tag` 以 `nation:` 开头的 Region；tag 值恒为
  //   `nation:<nationId>`（`NationTag.tagFor`）。**国名与区域的对应靠 tag 逐字相等**，不靠 Nation.homeRegion
  //   （后者只是创国时那个区域；一国可以有多个带同 tag 的区域）。
  var NATION_TAG_PREFIX = "nation:";

  /** 区域的 `meta.tag` 若是国家 tag ⇒ 返回该 tag，否则 null（非国家区域 / 无 tag）。 */
  function nationTagOf(region) {
    var tag = region && region.meta ? region.meta.tag : null;
    if (tag === null || tag === undefined) {
      return null;
    }
    var text = String(tag);
    return text.indexOf(NATION_TAG_PREFIX) === 0 ? text : null;
  }

  /**
   * 纯函数（无 DOM/IO）：`tag 逐字相等` 的区域 id 集合（**按 id 字典序**，便于逐值断言）。
   * ★ C12 要的是"集合相等、不是子集"——调用方拿它直接当高亮集合，**不做任何交集/取首**。
   */
  function nationRegionIds(regions, tag) {
    if (tag === null || tag === undefined || tag === "") {
      return [];
    }
    var want = String(tag);
    return (regions || [])
      .filter(function (region) {
        return nationTagOf(region) === want;
      })
      .map(function (region) {
        return String(region.id);
      })
      .sort(function (a, b) {
        return a.localeCompare(b);
      });
  }

  /**
   * 纯函数：给定"某格从属的区域 id 列表" ⇒ 这些区域里出现的**国家 id**（去重 + 字典序）。
   * 一个 hex 可同时属于多个区域（M8-U1）⇒ 可能给出多个国家；都不带国家 tag ⇒ 空数组。
   */
  function nationIdsOfRegions(regions, regionIds) {
    var wanted = {};
    (regionIds || []).forEach(function (id) {
      if (id !== null && id !== undefined) {
        wanted[String(id)] = true;
      }
    });
    var out = {};
    var order = [];
    (regions || []).forEach(function (region) {
      if (!region || !Object.prototype.hasOwnProperty.call(wanted, String(region.id))) {
        return;
      }
      var tag = nationTagOf(region);
      if (tag === null) {
        return;
      }
      var nationId = tag.slice(NATION_TAG_PREFIX.length);
      if (nationId === "" || Object.prototype.hasOwnProperty.call(out, nationId)) {
        return;
      }
      out[nationId] = true;
      order.push(nationId);
    });
    return order.sort(function (a, b) {
      return a.localeCompare(b);
    });
  }

  /** 决策模式：点格 ⇒ 该格从属区域里的国家 tag ⇒ **这些 tag 的全部区域**一起高亮。 */
  async function selectNationOfHex(q, r) {
    var regions = host.overviewRegions || [];
    try {
      var body = await api.mapHex(q, r, app.target());
      var regionIds = Array.isArray(body.regions) ? body.regions : [];
      var tags = [];
      regionIds.forEach(function (id) {
        var region = null;
        for (var i = 0; i < regions.length; i++) {
          if (String(regions[i].id) === String(id)) {
            region = regions[i];
            break;
          }
        }
        var tag = nationTagOf(region);
        if (tag !== null && tags.indexOf(tag) < 0) {
          tags.push(tag);
        }
      });
      if (!tags.length) {
        app.setHighlightRegions([]);
        return;
      }
      var ids = [];
      tags.forEach(function (tag) {
        nationRegionIds(regions, tag).forEach(function (id) {
          if (ids.indexOf(id) < 0) {
            ids.push(id);
          }
        });
      });
      app.setHighlightRegions(
        ids.sort(function (a, b) {
          return a.localeCompare(b);
        }),
        "group"
      );
    } catch (e) {
      app.setHighlightRegions([]);
    }
  }

  /**
   * 纯函数（无 DOM/IO）：`区域色板 + 焦点集合 + 参数` ⇒ 高亮计划。**区域查看与区域编辑共用**。
   *   `palette`  = `[{id, color, hexes}]`（color 已是解析好的 `#RRGGBB`）
   *   `focusIds` = 焦点区域 id（数组；单个 id / null / undefined 也接受）
   *   `opts`     = `{focusAlpha, fadeAlpha, fadeWhenNoFocus, focusOutlineAlpha, fadeOutlineAlpha}`
   * 返回 `{entries, outlines, faded, focus}`：
   *   ★ **焦点区域先入** `entries` —— `setHighlightHexes` 对同一 hex"先者胜"，若其它区域先入，
   *     与别区**重叠的焦点格**会被淡色盖掉（T10 在真重叠数据上实测到的缺陷，本函数是它的唯一修复点）；
   *   `faded` = 被淡化的区域 id（overview 序，可断言）；`focus` = 色板里真正命中的焦点 id；
   *   `focusIds` 为空且 `fadeWhenNoFocus` 为假 ⇒ **空计划**（零填充、零边框）。
   */
  function buildRegionHighlightPlan(palette, focusIds, opts) {
    var options = opts || {};
    var focusAlpha = options.focusAlpha === undefined ? REGION_FOCUS_ALPHA : options.focusAlpha;
    var singleFocusAlpha = options.singleFocusAlpha;
    var fadeAlpha = options.fadeAlpha === undefined ? REGION_FADE_ALPHA : options.fadeAlpha;
    var fadeScope = options.fadeScope === "same-tag" ? "same-tag" : "all";
    var focusOutlineAlpha = options.focusOutlineAlpha === undefined ? 1 : options.focusOutlineAlpha;
    var fadeOutlineAlpha = options.fadeOutlineAlpha === undefined ? 0.45 : options.fadeOutlineAlpha;
    var focusList = [];
    var raw = Array.isArray(focusIds)
      ? focusIds
      : focusIds === null || focusIds === undefined
        ? []
        : [focusIds];
    raw.forEach(function (id) {
      if (id === null || id === undefined || focusList.indexOf(id) >= 0) {
        return;
      }
      focusList.push(id);
    });
    var plan = { entries: [], outlines: [], faded: [], focus: [] };
    var matched = 0;
    var focusTag;
    (palette || []).forEach(function (region) {
      if (region && region.id !== null && region.id !== undefined && focusList.indexOf(region.id) >= 0) {
        matched += 1;
        focusTag = regionTag(region);
      }
    });
    // 无焦点（或焦点 id 在本世界的色板里一个都不存在）且参数要求"无焦点不画" ⇒ 空计划：
    // ★ 宁可**一个填充都不画**，也不把"状态说高亮 X、但 X 不在这个世界"变成"把所有区域都涂淡"。
    if (!matched && !options.fadeWhenNoFocus) {
      return plan;
    }
    // ★ U3：单区域选中 ⇒ 焦点更亮；`fadeScope="same-tag"` ⇒ 淡色只压同 tag 的兄弟，异 tag 区域**不画**。
    var single = focusList.length === 1 && matched === 1 && singleFocusAlpha !== undefined;
    var ordered = [];
    var rest = [];
    (palette || []).forEach(function (region) {
      if (!region || region.id === null || region.id === undefined) {
        return;
      }
      if (focusList.indexOf(region.id) >= 0) {
        ordered.push(region);
      } else {
        rest.push(region);
      }
    });
    rest.forEach(function (region) {
      ordered.push(region);
    });
    ordered.forEach(function (region) {
      var isFocus = focusList.indexOf(region.id) >= 0;
      if (!isFocus && fadeScope === "same-tag" && regionTag(region) !== focusTag) {
        return;
      }
      var base =
        typeof region.color === "string" && HEX_COLOR_RE.test(region.color)
          ? region.color
          : REGION_FALLBACK_COLOR;
      var color = isFocus ? base : fadeRegionColor(base);
      if (isFocus) {
        plan.focus.push(region.id);
      } else {
        plan.faded.push(region.id);
      }
      var alpha = isFocus ? (single ? singleFocusAlpha : focusAlpha) : fadeAlpha;
      (region.hexes || []).forEach(function (h) {
        plan.entries.push({ key: h.q + "_" + h.r, color: color, alpha: alpha });
      });
      // ★ M8-S §九：边框只画区域边界（**精确**的逐 hex 外缘闭合环）；焦点实、其它淡。
      plan.outlines.push({
        id: region.id,
        color: color,
        alpha: isFocus ? focusOutlineAlpha : fadeOutlineAlpha,
        hexes: region.hexes || [],
      });
    });
    return plan;
  }

  /**
   * 区域高亮的**唯一**取数 + 计划路径（M8 T9）。区域查看与区域编辑都走这里：
   * 焦点区域原色、其它区域淡色；两模式的差别只在 `opts`（见上面两个常量）。
   */
  async function reloadRegionHighlight(focusIds, opts) {
    var token = ++host.regionHighlightToken;
    var mode = app.getState().mode;
    var overview;
    try {
      overview = await api.cachedMapOverview(app.target());
    } catch (e) {
      return null;
    }
    var regions = (overview && overview.regions) || [];
    var palette = [];
    for (var i = 0; i < regions.length; i++) {
      var region = regions[i];
      if (!region || region.id === null || region.id === undefined) {
        continue;
      }
      var detail = await fetchRegionCached(region.id);
      palette.push({
        id: region.id,
        color: regionColor(detail.meta),
        tag: regionTag(region),
        hexes: detail.hexes || [],
      });
    }
    // ★ await 期间模式可能已变 ⇒ 丢掉这一轮（否则旧模式的高亮会被画到新模式上）。
    // ★ U3：也丢掉被**更新的高亮请求**取代的轮次 —— 大世界下"tag 全选（N 个区域，多秒）"之后
    //   立刻点单个区域，慢的那轮若晚到会把新选择覆盖回旧态（e2e 实测到的竞态）。
    if (app.getState().mode !== mode || token !== host.regionHighlightToken) {
      return null;
    }
    var plan = buildRegionHighlightPlan(palette, focusIds, opts);
    host.regionFaded = plan.faded;
    active.setHighlightHexes(plan.entries);
    active.setRegionOutlines(plan.outlines);
    return plan;
  }

  /**
   * 区域查看模式（M8 T9）：焦点 = 当前高亮的区域集合（点 hex / 点区域 / 点标签都收敛到
   * `state.highlightRegions`）⇒ 焦点原色、**其它区域淡色**。
   * 无选中 ⇒ 清空且**零请求**（M7 行为不变：按状态机的 highlightRegions 懒拉，避免 19441 格爆载荷）。
   */
  async function reloadHighlights() {
    var state = app.getState();
    var ids = state.highlightRegions || [];
    if (!ids.length) {
      host.regionFaded = [];
      active.setHighlightHexes([]);
      active.setRegionOutlines([]);
      return null;
    }
    // ★ U3：单区域选中 ⇒ 更亮 + 只压同 tag 兄弟；tag 全选 / 多从属格 ⇒ 沿用 M8 T9 的 REGION_VIEW_HIGHLIGHT。
    var opts =
      state.highlightKind === "single" && ids.length === 1
        ? REGION_SINGLE_HIGHLIGHT
        : REGION_VIEW_HIGHLIGHT;
    return reloadRegionHighlight(ids, opts);
  }

  /** 与中性灰混合 ⇒ "淡色"（M8 T10：编辑中其它区域淡出，焦点区域保持原色）。 */
  function fadeRegionColor(hex) {
    var r = parseInt(hex.slice(1, 3), 16);
    var g = parseInt(hex.slice(3, 5), 16);
    var b = parseInt(hex.slice(5, 7), 16);
    var t = 0.68;
    function mix(c) {
      return Math.round(c + (203 - c) * t);
    }
    function part(c) {
      var v = mix(c).toString(16);
      return v.length === 1 ? "0" + v : v;
    }
    return "#" + part(r) + part(g) + part(b);
  }

  /**
   * 区域编辑高亮（M8 T10）：焦点区域**原色**、其它区域**淡色**；选区由渲染器的 draft 层单独画。
   * ★ T9：本函数**不再自写一份**，改为走 `reloadRegionHighlight`（与区域查看同一条路径、
   *   同样的"焦点先入"修复）；两模式的差别只在 `REGION_EDIT_HIGHLIGHT` 这组参数。
   * 同时维护 `body[data-region-focus]` 与 debug 用的 faded 区域集合（可断言）。
   */
  async function reloadRegionEditHighlight() {
    if (app.getState().mode !== "region-edit") {
      return null;
    }
    var focus = host.regionFocus;
    return reloadRegionHighlight(focus ? [focus] : [], REGION_EDIT_HIGHLIGHT);
  }

  // ── 决策人可见范围高亮（只读端点 `/api/sd/decision-makers/{id}/scope`）─────────────
  // ★ 为什么要有这一层：决策人"看得见什么"= 服务端范围函数现算 ∩ GM 的 accessLimit。
  //   旧实现是**前端**按 affiliation 从 overview 自己推（国家的区域全高亮），军队级则什么都不画
  //   —— 那是第二份真相：GM 用 accessLimit 收窄过的范围，界面照样画整片国土。
  // ★ 与本层的写入者（区域高亮）互斥：两处都写 setHighlightHexes（按格键去重、先写者胜）
  //   ⇒ 决策模式下 `highlightRegions` 恒为空（点决策人时清掉，见 panels.js）。
  var DECISION_SCOPE_COLOR = "#3fa9ff";
  // ★ 两级范围的**渲染**分界：按**格数**分，不按 affiliation —— 决定"画出来看不看得清"的是格数。
  //   军队级（视野圈）R=1 是 7 格、R=2 是 19 格；国家级（整片国土）几百到几千格。64 稳稳落在中间。
  var DECISION_SCOPE_SMALL_MAX = 64;
  var DECISION_SCOPE_ALPHA_SMALL = 0.42; // 格少 ⇒ 逐格实心，范围圈看得清
  var DECISION_SCOPE_ALPHA_LARGE = 0.2; // 格多 ⇒ 压淡，否则整片国土盖死地形

  /** 「国家级 / 军队级 / 政府级」的中文标签（纯函数）。 */
  function decisionScopeLevelLabel(kind) {
    if (kind === "nation") {
      return "国家级";
    }
    if (kind === "army") {
      return "军队级";
    }
    // ★ F1：GOV 决策人的可见范围（管辖区）——与服务端 affiliation.kind 同口径。
    if (kind === "gov") {
      return "政府级";
    }
    return kind ? String(kind) : "未知归属";
  }

  /**
   * ★ 纯函数：可见范围响应 ⇒ 地图高亮计划（**无 DOM、无 IO**，可直接单测）。
   *
   * <p>输入是 `GET /api/sd/decision-makers/{id}/scope` 的体。格集**只来自服务端**（`visible.hexes`）——
   * 前端不补、不猜、不从 overview 推。`visible.hexCount` 含图外格，故文案报的是它、而画的是 `hexes`。
   */
  function buildDecisionScopeHighlight(scope) {
    var visible = scope ? scope.visible : null;
    var kind = scope && scope.affiliation ? scope.affiliation.kind : null;
    var level = decisionScopeLevelLabel(kind);
    if (!visible) {
      return {
        entries: [],
        outlines: [],
        hexCount: 0,
        level: level,
        summary: "可见范围：未知（" + level + "）",
      };
    }
    var raw = Array.isArray(visible.hexes) ? visible.hexes : [];
    var hexes = [];
    for (var i = 0; i < raw.length; i += 1) {
      var hex = raw[i];
      if (!hex || hex.length !== 2) {
        continue;
      }
      hexes.push({ q: Number(hex[0]), r: Number(hex[1]) });
    }
    var small = hexes.length <= DECISION_SCOPE_SMALL_MAX;
    var alpha = small ? DECISION_SCOPE_ALPHA_SMALL : DECISION_SCOPE_ALPHA_LARGE;
    var entries = [];
    for (var j = 0; j < hexes.length; j += 1) {
      entries.push({
        key: hexes[j].q + "_" + hexes[j].r,
        color: DECISION_SCOPE_COLOR,
        alpha: alpha,
      });
    }
    var regionCount = Array.isArray(visible.regionIds) ? visible.regionIds.length : 0;
    var count = typeof visible.hexCount === "number" ? visible.hexCount : hexes.length;
    var summary = "可见 " + count + " 格 / " + regionCount + " 区域（" + level + "）";
    var offMap = typeof visible.offMapHexCount === "number" ? visible.offMapHexCount : 0;
    if (offMap > 0) {
      // ★ 图外的格如实说出来：范围内但图上不存在的格，既不画、也不假装没有。
      summary += "，其中 " + offMap + " 格在图上不存在";
    }
    return {
      entries: entries,
      outlines: [
        { id: "decision-scope", color: DECISION_SCOPE_COLOR, alpha: small ? 1 : 0.7, hexes: hexes },
      ],
      hexCount: count,
      level: level,
      summary: summary,
    };
  }

  /** 把摘要写进左栏那一行（跨文件只经这**一个 DOM 锚点**，两边不互相持有状态）。 */
  function publishDecisionScopeSummary(text) {
    var id =
      window.SimosPanels && window.SimosPanels.DECISION_SCOPE_SUMMARY_ID
        ? window.SimosPanels.DECISION_SCOPE_SUMMARY_ID
        : null;
    var node = id ? app.byId(id) : null;
    if (node) {
      node.textContent = text;
    }
  }

  /**
   * 把**最近一次算好的**摘要重新写回那一行（供 panels.js 在重画详情后调用）。
   *
   * <p>★ 为什么需要它：两处都在异步里写 DOM——panels 重画左栏详情时会把那一行重置成"载入中…"，若它晚于本层的
   * 回填落地，摘要就会被退回占位文本且**不再有事件来修**（状态没变 ⇒ 不会再算一次）。两处都写**同一个值** ⇒ 收敛。
   */
  function republishDecisionScopeSummary() {
    publishDecisionScopeSummary(host.decisionScope ? host.decisionScope.summary : "载入中…");
  }

  /**
   * 拉当前聚焦决策人的**现算**可见范围并高亮（决策模式、子页「决策人查看」）。
   *
   * <p>★ 只有本函数写决策范围层；无焦点 ⇒ 清空（清空也走这里，避免"上一轮的高亮挂在屏幕上"）。
   * ★ 过期轮次丢弃（模式/焦点/token 三重叠）：慢的那轮晚到会把新选择覆盖回旧态。
   */
  async function reloadDecisionScopeHighlight() {
    var state = app.getState();
    if (!state || state.mode !== "decision") {
      // ★ 不碰高亮层：区域模式的填充归 reloadHighlights（模式切换时它会清）。
      host.decisionScope = null;
      return null;
    }
    var focus = state.decisionMakerFocus || null;
    if (!focus) {
      host.decisionScope = null;
      active.setHighlightHexes([]);
      active.setRegionOutlines([]);
      publishDecisionScopeSummary("—");
      return null;
    }
    var token = ++host.decisionScopeToken;
    var body;
    try {
      body = await api.decisionMakerScope(focus, app.target());
    } catch (e) {
      if (token !== host.decisionScopeToken) {
        return null;
      }
      host.decisionScope = null;
      publishDecisionScopeSummary("拉取失败：" + e.message);
      return null;
    }
    if (
      app.getState().mode !== "decision" ||
      (app.getState().decisionMakerFocus || null) !== focus ||
      token !== host.decisionScopeToken
    ) {
      return null;
    }
    var plan = buildDecisionScopeHighlight(body);
    host.decisionScope = plan;
    active.setHighlightHexes(plan.entries);
    active.setRegionOutlines(plan.outlines);
    publishDecisionScopeSummary(plan.summary);
    return plan;
  }

  // ── F1：图层抽屉 / 搜索定位 / 世界视图入口 ─────────────────────────────
  //
  // ★ 图层状态持久化键与格式都是世界视图的一部分（localStorage 不可用 ⇒ 内存态，不报错）；
  //   渲染器只认 worldmodel.layerVisibility 归一后的键，未知键不会打开一个图层。
  // ★ 2026-10-01：v1 → v2 —— 用户实测“显示国家名时政府也一起显示”。新缺省把 gov / govJurisdiction
  //   关掉；旧 v1 里用户点开的 gov=true 不能继续生效（否则新缺省被旧存档顶掉）⇒ 换键重来。
  var LAYERS_STORAGE_KEY = "simos.layers.v2";

  function layerPrefsFromDom() {
    var out = {};
    var panel = app.byId("layer-panel");
    if (!panel || !panel.querySelectorAll) {
      return out;
    }
    Array.prototype.forEach.call(panel.querySelectorAll("input[data-layer]"), function (node) {
      var key = node.getAttribute("data-layer");
      if (key) {
        out[key] = !!node.checked;
      }
    });
    return out;
  }

  function loadLayerPrefs() {
    try {
      if (typeof window !== "undefined" && window.localStorage) {
        var raw = window.localStorage.getItem(LAYERS_STORAGE_KEY);
        if (raw) {
          var parsed = JSON.parse(raw);
          if (parsed && typeof parsed === "object") {
            return parsed;
          }
        }
      }
    } catch (e) {
      // 无 localStorage / 坏 JSON ⇒ 用内存态（不静默丢用户当前这一轮的开关）。
    }
    return host.layerMemory;
  }

  function persistLayerPrefs(layers) {
    host.layerMemory = Object.assign({}, layers || {});
    try {
      if (typeof window !== "undefined" && window.localStorage) {
        window.localStorage.setItem(LAYERS_STORAGE_KEY, JSON.stringify(host.layerMemory));
      }
    } catch (e) {
      // 持久化失败只丢"下次打开还记着"这一条，不影响本次绘制。
    }
  }

  /**
   * 把 `layers` 写回抽屉复选框，再把**读回的 DOM 状态**持久化并推给 renderer（只重绘、不重取数）。
   *
   * <p>fail-closed：只在 `layers` 里**存在该键**时写复选框，且只有 `=== true` 才勾（缺键保持 DOM 原样）；
   * 归一化仍由 renderer 侧 worldmodel.layerVisibility 收口。`persistLayerPrefs` 后 `setLayerState` 用的是
   * `layerPrefsFromDom()` 的读数，保证内存态/绘制态/DOM 三者同源。
   */
  function applyLayerPrefs(layers) {
    var panel = app.byId("layer-panel");
    if (panel && panel.querySelectorAll) {
      Array.prototype.forEach.call(panel.querySelectorAll("input[data-layer]"), function (node) {
        var key = node.getAttribute("data-layer");
        if (key && layers && Object.prototype.hasOwnProperty.call(layers, key)) {
          node.checked = layers[key] === true;
        }
      });
    }
    var prefs = layerPrefsFromDom();
    persistLayerPrefs(prefs);
    if (active && active.setLayerState) {
      active.setLayerState(prefs);
    }
  }

  function wireLayerDrawer() {
    var button = app.byId("layer-toggle");
    var panel = app.byId("layer-panel");
    if (!button || !panel) {
      return;
    }
    var stored = loadLayerPrefs();
    applyLayerPrefs(stored);
    button.setAttribute("aria-expanded", panel.hidden ? "false" : "true");
    button.addEventListener("click", function () {
      panel.hidden = !panel.hidden;
      button.setAttribute("aria-expanded", panel.hidden ? "false" : "true");
    });
    if (panel.querySelectorAll) {
      Array.prototype.forEach.call(panel.querySelectorAll("input[data-layer]"), function (node) {
        node.addEventListener("change", function () {
          applyLayerPrefs(layerPrefsFromDom());
        });
      });
      // ★ F1：场景预设按钮。未知 preset / worldmodel 缺席 ⇒ 什么都不做（fail-closed，不猜、不改 DOM）。
      Array.prototype.forEach.call(
        panel.querySelectorAll("button[data-layer-preset]"),
        function (node) {
          node.addEventListener("click", function () {
            var name = node.getAttribute("data-layer-preset");
            if (!worldModel || !worldModel.layerPreset) {
              return;
            }
            var preset = worldModel.layerPreset(name);
            if (preset) {
              applyLayerPrefs(preset);
            }
          });
        }
      );
    }
  }

  // ── F2：热力层（数据组控件 / 取数 / 图例 / 持久化）──────────────────────
  //
  // ★ 叠加语义：一次只画一个指标；不选（metric=""）时与 F1 渲染完全一致。
  // ★ 数据只来自 /api/map/heatmap（经 api.cachedHeatmap，键含 metric×target）；前端不补 0、不重算。
  // ★ 旧页 /map 没有 #heatmap-metric 等 DOM ⇒ wireHeatmapControls 静默跳过，不注册事件、不取数。
  // ★ 不透明度：持久化键 / 字段名 `opacityPerMille` 一律**真·千分比**（150..850，默认 550）；
  //   滑块刻度是百分比（15..85）；落给渲染器严格 `opacity = opacityPerMille / 1000` ⇒ 0.15..0.85，
  //   默认 0.55（与设计 plan §3.1 的“0.15–0.85，默认 0.55”一致）。

  var HEATMAP_STORAGE_KEY = "simos.heatmap.v1";
  var HEATMAP_OPACITY_MIN = 15;
  var HEATMAP_OPACITY_MAX = 85;
  var HEATMAP_OPACITY_DEFAULT = 55;
  var HEATMAP_OPACITY_PERMILLE_MIN = HEATMAP_OPACITY_MIN * 10;
  var HEATMAP_OPACITY_PERMILLE_MAX = HEATMAP_OPACITY_MAX * 10;
  var HEATMAP_OPACITY_PERMILLE_DEFAULT = HEATMAP_OPACITY_DEFAULT * 10;

  /** 指标词表项；未知 id ⇒ null（不猜 label/unit）。 */
  function heatmapMetricInfo(id) {
    var metrics =
      worldModel && Array.isArray(worldModel.HEATMAP_METRICS) ? worldModel.HEATMAP_METRICS : [];
    for (var i = 0; i < metrics.length; i += 1) {
      if (metrics[i] && metrics[i].id === id) {
        return metrics[i];
      }
    }
    return null;
  }

  /** 持久化/下拉值的 fail-closed 归一：只认词表里的 id，未知 ⇒ ""（不显示）。 */
  function normalizeHeatmapMetric(value) {
    return typeof value === "string" && heatmapMetricInfo(value) ? value : "";
  }

  /** 解析数字输入（number / 数字字符串）；解析不出 / 非有限 ⇒ null。 */
  function parseHeatmapNumber(value) {
    var parsed;
    if (typeof value === "number") {
      parsed = value;
    } else if (typeof value === "string" && value.trim() !== "") {
      parsed = Number(value);
    } else {
      return null;
    }
    return typeof parsed === "number" && isFinite(parsed) ? parsed : null;
  }

  /** 不透明度滑块刻度归一：有限数字就地取整并夹到 [15,85]；其余 ⇒ 55。 */
  function normalizeHeatmapOpacitySlider(value) {
    var parsed = parseHeatmapNumber(value);
    if (parsed === null) {
      return HEATMAP_OPACITY_DEFAULT;
    }
    parsed = Math.round(parsed);
    if (parsed < HEATMAP_OPACITY_MIN) {
      return HEATMAP_OPACITY_MIN;
    }
    if (parsed > HEATMAP_OPACITY_MAX) {
      return HEATMAP_OPACITY_MAX;
    }
    return parsed;
  }

  /**
   * 持久化不透明度归一：真·千分比，取到 10 的整数倍并夹到 [150,850]（对应滑块 15..85）；
   * 其余 ⇒ 550。存储与渲染统一走千分比（渲染 opacity = opacityPerMille / 1000）。
   */
  function normalizeHeatmapOpacityPerMille(value) {
    var parsed = parseHeatmapNumber(value);
    if (parsed === null) {
      return HEATMAP_OPACITY_PERMILLE_DEFAULT;
    }
    parsed = Math.round(parsed / 10) * 10;
    if (parsed < HEATMAP_OPACITY_PERMILLE_MIN) {
      return HEATMAP_OPACITY_PERMILLE_MIN;
    }
    if (parsed > HEATMAP_OPACITY_PERMILLE_MAX) {
      return HEATMAP_OPACITY_PERMILLE_MAX;
    }
    return parsed;
  }

  function normalizeHeatmapPrefs(raw) {
    var parsed = raw && typeof raw === "object" ? raw : {};
    return {
      metric: normalizeHeatmapMetric(parsed.metric),
      opacityPerMille: normalizeHeatmapOpacityPerMille(parsed.opacityPerMille),
    };
  }

  function loadHeatmapPrefs() {
    try {
      if (typeof window !== "undefined" && window.localStorage) {
        var raw = window.localStorage.getItem(HEATMAP_STORAGE_KEY);
        if (raw) {
          return normalizeHeatmapPrefs(JSON.parse(raw));
        }
      }
    } catch (e) {
      // 无 localStorage / 坏 JSON ⇒ 用内存态（不把坏缓存当成用户选择）。
    }
    return normalizeHeatmapPrefs(host.heatmapMemory);
  }

  function persistHeatmapPrefs(prefs) {
    host.heatmapMemory = normalizeHeatmapPrefs(prefs);
    try {
      if (typeof window !== "undefined" && window.localStorage) {
        window.localStorage.setItem(HEATMAP_STORAGE_KEY, JSON.stringify(host.heatmapMemory));
      }
    } catch (e) {
      // 持久化失败只丢"下次打开还记着"这一条，不影响本次绘制。
    }
  }

  /** 图例数值短文本：整数原样；小数 4 位；非有限 ⇒ “—”。 */
  function heatmapNumberText(value) {
    if (value === null || value === undefined || typeof value !== "number" || !isFinite(value)) {
      return "—";
    }
    if (Number.isInteger(value)) {
      return String(value);
    }
    return String(Math.round(value * 10000) / 10000);
  }

  function heatmapLegendNode() {
    return app.byId("heatmap-legend");
  }

  function hideHeatmapLegend() {
    var node = heatmapLegendNode();
    if (node) {
      node.hidden = true;
      app.clear(node);
    }
  }

  /**
   * 画热力图例（标题 / 单位 / scope / 分档色块行 / count-min-median-max / 不可用原因 / caveat）。
   * 取值走 worldmodel.heatmapLegend（缺席时同口径兜底）；服务端文本一律经 textContent 落 DOM，不用 innerHTML。
   */
  function renderHeatmapLegend(payload, scalePlan, reasonText) {
    var node = heatmapLegendNode();
    if (!node) {
      return;
    }
    var model;
    if (worldModel && worldModel.heatmapLegend) {
      model = worldModel.heatmapLegend(payload, scalePlan);
    } else {
      var p = payload && typeof payload === "object" ? payload : {};
      model = {
        title: p.label || p.metric || "热力图",
        unit: p.unit === undefined ? null : p.unit,
        scope: p.scope === undefined ? null : p.scope,
        lines: [],
        unavailable: p.unavailable === undefined ? null : p.unavailable,
        footnote: "缺失数据格不着色、不填 0。",
      };
    }
    if (reasonText) {
      model.unavailable = reasonText;
    }
    app.clear(node);
    node.hidden = false;
    node.appendChild(
      app.el("div", { class: "heatmap-legend-title", text: app.text(model.title) })
    );
    if (model.unit !== null && model.unit !== undefined && String(model.unit) !== "") {
      node.appendChild(
        app.el("div", { class: "heatmap-legend-meta", text: "单位：" + model.unit })
      );
    }
    if (model.scope !== null && model.scope !== undefined && String(model.scope) !== "") {
      node.appendChild(
        app.el("div", { class: "heatmap-legend-scope", text: String(model.scope) })
      );
    }
    if (model.lines && model.lines.length) {
      var lines = app.el("div", { class: "heatmap-legend-lines" });
      model.lines.forEach(function (line) {
        var row = app.el("div", { class: "heatmap-legend-line" });
        var swatch = app.el("span", { class: "heatmap-legend-swatch" });
        swatch.style.backgroundColor = line.color || "#000000";
        row.appendChild(swatch);
        row.appendChild(
          app.el("span", {
            class: "heatmap-legend-line-label",
            text: app.text(line.label) + "（" + app.text(line.count) + " 格）",
          })
        );
        lines.appendChild(row);
      });
      node.appendChild(lines);
    }
    if (scalePlan && scalePlan.stats) {
      node.appendChild(
        app.el("div", {
          class: "heatmap-legend-stats",
          text:
            "格 " +
            app.text(scalePlan.stats.count) +
            " · min " +
            heatmapNumberText(scalePlan.stats.min) +
            " · median " +
            heatmapNumberText(scalePlan.stats.median) +
            " · max " +
            heatmapNumberText(scalePlan.stats.max),
        })
      );
    }
    if (
      model.unavailable !== null &&
      model.unavailable !== undefined &&
      String(model.unavailable) !== ""
    ) {
      node.appendChild(
        app.el("div", {
          class: "heatmap-legend-unavailable",
          text: "不可用：" + model.unavailable,
        })
      );
    }
    node.appendChild(
      app.el("div", { class: "heatmap-legend-footnote", text: app.text(model.footnote) })
    );
  }

  /** 清掉旧热力层（换指标 / 失败 / 不可用 / 空数据时必须清，不能留上一指标的颜色）。 */
  function clearHeatmapLayer() {
    host.heatmapPlan = null;
    host.heatmapPayload = null;
    host.heatmapScale = null;
    if (active && active.setHeatmap) {
      active.setHeatmap(null);
    }
  }

  /** 选指标：持久化 + 作废旧轮次 + 清旧层；空值 = 不显示，非空 = 立即重取。 */
  function setHeatmapMetric(value) {
    var metric = normalizeHeatmapMetric(value);
    heatmapMetric = metric;
    var select = app.byId("heatmap-metric");
    if (select && select.value !== metric) {
      select.value = metric;
    }
    persistHeatmapPrefs({ metric: metric, opacityPerMille: heatmapOpacityPerMille });
    host.heatmapToken += 1; // 换指标 / 关闭：旧响应作废，不得覆盖新状态。
    clearHeatmapLayer();
    if (!metric) {
      hideHeatmapLegend();
      return;
    }
    reloadHeatmap();
  }

  /** 拖不透明度：只改持久化 + 当前层透明度并重画（不重新请求；图例按同一份 payload 重画保持 DOM 同源）。 */
  function setHeatmapOpacity(value) {
    var slider = normalizeHeatmapOpacitySlider(value);
    var perMille = slider * 10;
    heatmapOpacityPerMille = perMille;
    var input = app.byId("heatmap-opacity");
    if (input && Number(input.value) !== slider) {
      input.value = String(slider);
    }
    persistHeatmapPrefs({ metric: heatmapMetric, opacityPerMille: perMille });
    if (host.heatmapPlan && active && active.setHeatmap) {
      active.setHeatmap(Object.assign({}, host.heatmapPlan, { opacity: perMille / 1000 }));
      renderHeatmapLegend(host.heatmapPayload, host.heatmapScale);
    }
  }

  /**
   * 取当前指标（带当前 target）并落图：`scalePlan = worldModel.heatmapColorScale(cells)` 给每格配色；
   * 失败 / 空 / 不可用一律清旧层并在图例写明 HTTP / 服务端原因；token 挡换指标/换目标时的过期响应。
   */
  async function reloadHeatmap() {
    if (!app.byId("heatmap-metric")) {
      return; // 旧页 /map 没有数据组 DOM：静默跳过，不取数、不动渲染。
    }
    var metric = heatmapMetric;
    if (!metric) {
      clearHeatmapLayer();
      hideHeatmapLegend();
      return;
    }
    var info = heatmapMetricInfo(metric);
    var label = info && info.label ? info.label : metric;
    var token = (host.heatmapToken += 1);
    var switching = !host.heatmapPlan || host.heatmapPlan.metric !== metric;
    if (switching) {
      clearHeatmapLayer();
      renderHeatmapLegend(
        {
          metric: metric,
          label: label,
          unit: info ? info.unit : null,
          scope: info ? info.help : null,
        },
        null,
        "载入中…"
      );
    }
    try {
      var payload = await api.cachedHeatmap(metric, app.target());
      if (token !== host.heatmapToken || metric !== heatmapMetric) {
        return; // 过期轮次（换指标 / 换目标后的旧响应）一律丢弃。
      }
      var cells = payload && Array.isArray(payload.cells) ? payload.cells : [];
      var scalePlan =
        worldModel && worldModel.heatmapColorScale ? worldModel.heatmapColorScale(cells) : null;
      var unavailable = payload && payload.unavailable !== undefined ? payload.unavailable : null;
      if (unavailable) {
        clearHeatmapLayer();
        renderHeatmapLegend(payload, scalePlan);
        return;
      }
      if (!cells.length) {
        clearHeatmapLayer();
        renderHeatmapLegend(
          payload,
          scalePlan,
          "该指标当前没有可用数据格（缺失格不着色、不填 0）"
        );
        return;
      }
      var colorOf =
        scalePlan && typeof scalePlan.colorOf === "function" ? scalePlan.colorOf : null;
      var colored = [];
      cells.forEach(function (cell) {
        if (!cell) {
          return;
        }
        if (
          typeof cell.q !== "number" ||
          typeof cell.r !== "number" ||
          !isFinite(cell.q) ||
          !isFinite(cell.r)
        ) {
          return;
        }
        if (typeof cell.value !== "number" || !isFinite(cell.value)) {
          return;
        }
        var color =
          typeof cell.color === "string" && cell.color !== ""
            ? cell.color
            : colorOf
              ? colorOf(cell.value)
              : null;
        if (!color) {
          return;
        }
        colored.push({ q: cell.q, r: cell.r, value: cell.value, color: color });
      });
      if (!colored.length) {
        clearHeatmapLayer();
        renderHeatmapLegend(payload, scalePlan, "没有可着色的有效数据格");
        return;
      }
      var plan = {
        metric: payload.metric || metric,
        label: payload.label === undefined || payload.label === null ? label : payload.label,
        unit: payload.unit === undefined ? (info ? info.unit : null) : payload.unit,
        scope: payload.scope === undefined ? (info ? info.help : null) : payload.scope,
        tick: payload.tick === undefined ? null : payload.tick,
        cells: colored,
        scale: scalePlan,
        opacity: heatmapOpacityPerMille / 1000,
        unavailable: null,
      };
      host.heatmapPayload = payload;
      host.heatmapScale = scalePlan;
      host.heatmapPlan = Object.assign({}, plan);
      if (active && active.setHeatmap) {
        active.setHeatmap(plan);
      }
      renderHeatmapLegend(payload, scalePlan);
    } catch (e) {
      if (token !== host.heatmapToken || metric !== heatmapMetric) {
        return;
      }
      clearHeatmapLayer();
      var reason =
        (e && e.status ? "HTTP " + e.status + "：" : "") +
        (e && e.message ? e.message : String(e));
      renderHeatmapLegend(
        {
          metric: metric,
          label: label,
          unit: info ? info.unit : null,
          scope: info ? info.help : null,
        },
        null,
        "加载失败：" + reason
      );
    }
  }

  /** 工作台数据组接线：生成 option、恢复持久化、挂事件。旧页缺 #heatmap-metric ⇒ 立即静默返回。 */
  function wireHeatmapControls() {
    var select = app.byId("heatmap-metric");
    if (!select) {
      return;
    }
    var opacityInput = app.byId("heatmap-opacity");
    var prefs = loadHeatmapPrefs();
    heatmapMetric = prefs.metric;
    heatmapOpacityPerMille = prefs.opacityPerMille;
    app.clear(select);
    select.appendChild(
      app.el("option", { value: "", text: "不显示", title: "关闭热力层，只显示 F1 图层" })
    );
    var metrics =
      worldModel && Array.isArray(worldModel.HEATMAP_METRICS) ? worldModel.HEATMAP_METRICS : [];
    metrics.forEach(function (metric) {
      if (!metric || !metric.id) {
        return;
      }
      select.appendChild(
        app.el("option", {
          value: metric.id,
          text: metric.label || metric.id,
          title: metric.help || "",
        })
      );
    });
    select.value = heatmapMetric;
    if (opacityInput) {
      opacityInput.value = String(heatmapOpacityPerMille / 10);
    }
    select.addEventListener("change", function () {
      setHeatmapMetric(select.value);
    });
    if (opacityInput) {
      opacityInput.addEventListener("input", function () {
        setHeatmapOpacity(opacityInput.value);
      });
    }
    if (heatmapMetric) {
      reloadHeatmap();
    } else {
      clearHeatmapLayer();
      hideHeatmapLegend();
    }
  }

  var SEARCH_KIND_LABELS = {
    city: "城市",
    region: "区域",
    unit: "单位/GOV",
    decisionMaker: "决策人",
  };

  function searchKindLabel(kind) {
    return Object.prototype.hasOwnProperty.call(SEARCH_KIND_LABELS, kind)
      ? SEARCH_KIND_LABELS[kind]
      : String(kind);
  }

  /** 搜索当前索引（worldmodel 缺席 ⇒ 空；绝不从 DOM 反推）。 */
  function currentSearchMatches(query) {
    if (!worldModel || !worldModel.searchMatches) {
      return [];
    }
    return worldModel.searchMatches(host.searchIndex, query);
  }

  /**
   * ★ F1 搜索定位（UI 的唯一入口；也挂到 window.SimosMap 供 e2e/其它页复用）：
   * 城市/单位 ⇒ 选中 + 保证可见；区域 ⇒ 切区域模式并高亮 + 居中；决策人 ⇒ 切决策模式并聚焦。
   * 未知 kind fail-closed ⇒ false（不猜、不改任何状态）。
   */
  function locateSearchResult(item) {
    if (!item || !active) {
      return false;
    }
    var state = app.getState();
    if (item.kind === "city") {
      if (state.mode !== "view") {
        app.setMode("view");
      }
      app.setSelection({ kind: "city", id: item.id });
      if (item.at) {
        active.ensureCityVisible(item.at.q, item.at.r, hexGeom.UNIT_VISIBLE_MIN_SCALE, true);
      }
      return true;
    }
    if (item.kind === "unit") {
      if (state.mode !== "view" && state.mode !== "unit") {
        app.setMode("view");
      }
      app.setSelection({ kind: "unit", id: item.id });
      var unitPos = active.positionOf(item.id);
      if (unitPos) {
        active.ensureUnitVisible(unitPos.q, unitPos.r, hexGeom.UNIT_VISIBLE_MIN_SCALE, true);
      }
      return true;
    }
    if (item.kind === "region") {
      if (state.mode !== "region") {
        app.setMode("region");
      }
      app.setHighlightRegions([String(item.id)], "single");
      if (item.at) {
        active.ensureCityVisible(item.at.q, item.at.r, 0.3, true);
      }
      return true;
    }
    if (item.kind === "decisionMaker") {
      if (state.mode !== "decision") {
        app.setMode("decision");
      }
      app.setDecisionMakerFocus(item.id);
      return true;
    }
    return false;
  }

  function renderSearchResults() {
    var input = app.byId("search-input");
    var mount = app.byId("search-results");
    if (!input || !mount) {
      return;
    }
    var matches = currentSearchMatches(input.value);
    app.clear(mount);
    if (!matches.length) {
      mount.hidden = true;
      return;
    }
    matches.forEach(function (item) {
      var button = app.el("button", {
        type: "button",
        class: "search-result",
        "data-kind": item.kind,
      });
      button.appendChild(
        app.el("span", { class: "search-result-kind", text: searchKindLabel(item.kind) })
      );
      button.appendChild(app.el("span", { class: "search-result-name", text: app.text(item.name) }));
      button.appendChild(app.el("span", { class: "search-result-id", text: app.text(item.id) }));
      button.addEventListener("click", function () {
        locateSearchResult(item);
        mount.hidden = true;
        input.value = item.name || item.id;
      });
      mount.appendChild(button);
    });
    mount.hidden = false;
  }

  function wireSearchBox() {
    var input = app.byId("search-input");
    var mount = app.byId("search-results");
    if (!input || !mount) {
      return;
    }
    input.addEventListener("input", renderSearchResults);
    input.addEventListener("keydown", function (event) {
      if (event.key === "Enter") {
        var matches = currentSearchMatches(input.value);
        if (matches.length) {
          locateSearchResult(matches[0]);
          mount.hidden = true;
          input.value = matches[0].name || matches[0].id;
          if (event.preventDefault) {
            event.preventDefault();
          }
        }
        return;
      }
      if (event.key === "Escape") {
        mount.hidden = true;
      }
    });
    document.addEventListener("click", function (event) {
      if (mount.hidden) {
        return;
      }
      if (event.target === input || (mount.contains && mount.contains(event.target))) {
        return;
      }
      mount.hidden = true;
    });
  }

  /** 目标坐标变了 ⇒ 地图/单位/区域填充全部按新 revision 重取（取数一律带 withTarget）。 */
  function scheduleTargetReload() {
    if (host.targetTimer) {
      window.clearTimeout(host.targetTimer);
    }
    host.targetTimer = window.setTimeout(function () {
      host.targetTimer = null;
      host.regionCache = {};
      reloadOverview();
      // ★ F2：目标变了 ⇒ 热力层按 metric×target 重取（token 在 reloadHeatmap 里挡旧目标响应）。
      if (heatmapMetric) {
        reloadHeatmap();
      }
      if (app.getState().mode === "region-edit") {
        reloadRegionEditHighlight();
      } else {
        reloadHighlights();
      }
      // ★ 范围是**按 revision 现算**的 ⇒ 换了目标就得重取（否则画的是上一版世界的范围）。
      reloadDecisionScopeHighlight();
    }, 120);
  }

  function onStateChange(state) {
    if (!active) {
      return;
    }
    var key = targetKey();
    if (host.lastTargetKey !== key) {
      host.lastTargetKey = key;
      scheduleTargetReload();
    }
    var hl = (state.highlightRegions || []).join(",");
    if (host.lastHighlights !== hl) {
      host.lastHighlights = hl;
      if (state.mode !== "region-edit") {
        reloadHighlights();
      }
    }
    var focus = state.regionFocus || null;
    if (host.lastRegionFocus !== focus) {
      host.lastRegionFocus = focus;
      window.SimosMapRegionEditor.onRegionFocusChanged(focus);
    }
    // ★ 决策人可见范围层：焦点换了就重拉（含"清空焦点 ⇒ 清高亮"）。放在区域高亮分支**之后**：
    //   `reloadHighlights()` 在决策模式下走的是零请求清空路径（同步）⇒ 本层随后写，不会被它擦掉。
    var dmFocus = state.decisionMakerFocus || null;
    if (host.lastDecisionFocus !== dmFocus) {
      host.lastDecisionFocus = dmFocus;
      reloadDecisionScopeHighlight();
    }
    if (host.lastMode !== state.mode) {
      host.lastMode = state.mode;
      active.setBrushHexes([]);
      if (state.mode !== "unit") {
        window.SimosMapUnitEditor.resetRoute();
        host.routeMode = false;
        var routeToggle = app.byId("unit-route-toggle");
        if (routeToggle) {
          routeToggle.textContent = "路线模式：关";
        }
      }
      active.setMode(state.mode);
      if (state.mode === "region-edit") {
        host.regionDraft = {};
        host.regionDeleteArmed = false;
        active.setDraftHexes([]);
        window.SimosMapRegionEditor.renderRegionEditor();
        reloadRegionEditHighlight();
        refreshFocusHexes();
        window.SimosMapEditor.renderRegionInfo(state.selection);
      } else {
        active.setDraftHexes([]);
        active.clearFocusHexes();
        if (state.mode === "map-edit") {
          // ★ T11：切模式会清掉持久选区层 ⇒ 圈选随机化工具下把它的选区恢复显示（选区属于工具状态）。
          if (host.mapEditTool === "randomize") {
            active.setDraftHexes(host.randomizeSelection);
          }
          window.SimosMapEditor.renderRegionInfo(state.selection);
        } else {
          window.SimosMapEditor.clearRegionInfo();
        }
        reloadHighlights();
      }
    }
    var sel = state.selection;
    if (sel && sel.kind === "unit") {
      host.lastEnsuredCityId = null;
      active.setSelectedCity(null);
      active.setSelectedUnit(sel.id);
      var selPos = active.positionOf(sel.id);
      active.setSelected(selPos);
      // ★ 2026-09-24 可用性修复（用户："我没找到单位在哪"）：选中一个单位时，若它不在眼前
      //   （出界）或缩放过小（标记是亚像素），把它居中并抬到可用缩放。这是**任何来源**的选择变化
      //   （地图点选 / 树点选 / 右栏）的唯一收口。
      //   · 只在"选中项换了"时做：否则每一次无关的 state 变化（切模式/换高亮/revision 更新）都会把
      //     用户手动拖走的视图重新拽回该单位。
      //   · 在地图上点一个**本就在眼前**的标记 ⇒ ensureUnitVisible 内部短路，视图纹丝不动。
      if (selPos && host.lastEnsuredUnitId !== sel.id) {
        host.lastEnsuredUnitId = sel.id;
        active.ensureUnitVisible(selPos.q, selPos.r, hexGeom.UNIT_VISIBLE_MIN_SCALE);
      }
    } else if (sel && sel.kind === "city") {
      // ★ F1：城市选中（地图点选 / 搜索定位 / hex 详情里的城市链接）——与单位同一套"保证可见"收口。
      host.lastEnsuredUnitId = null;
      active.setSelectedUnit(null);
      active.setSelectedCity(sel.id);
      var cityPos = active.cityPositionOf(sel.id);
      active.setSelected(cityPos);
      if (cityPos && host.lastEnsuredCityId !== sel.id) {
        host.lastEnsuredCityId = sel.id;
        active.ensureCityVisible(cityPos.q, cityPos.r, hexGeom.UNIT_VISIBLE_MIN_SCALE);
      }
    } else {
      host.lastEnsuredUnitId = null;
      host.lastEnsuredCityId = null;
      active.setSelectedUnit(null);
      active.setSelectedCity(null);
      active.setSelected(sel && sel.kind === "hex" ? { q: sel.q, r: sel.r } : null);
    }
    if (state.mode === "map-edit" || state.mode === "region-edit") {
      window.SimosMapEditor.renderRegionInfo(sel);
    }
  }

  function workbenchSelect(pick) {
    var mode = app.getState().mode;
    var selId = window.SimosMapUnitEditor.selectedUnitId();
    // ★ F1：城市命中优先只领"选中城市"（不抢模式）：左栏切到城市详情，地图居中由 onStateChange 收口。
    if (pick.kind === "city") {
      app.setSelection({ kind: "city", id: pick.id });
      return;
    }
    // ★ M7e T1（用户裁定，§2「一下选中、两下取消」）：左键点**已选中**的单位标记 ⇒ 取消移动；
    //   未选中则只是选中它。不点单位、点已选中单位**当前所在格**同样取消移动。
    if (pick.kind === "unit") {
      if (mode === "unit" && selId === pick.id) {
        window.SimosMapUnitEditor.cancelRouteFor(pick.id);
        return;
      }
      // ★ 第2波 B10（用户 2026-09-23 实测）：工作台里**点地图上的单位 ⇒ 自动切到「单位移动编辑」模式**
      //   （mode id = "unit"，真名见 modes.js）——方便点完直接操作；要常规操作再手动切回「常规」。
      //   ★ 只从**常规**模式切：用户描述的就是"常规模式里点单位 ⇒ 进单位模式，再手动切回常规"。
      //     别的模式点单位各有其义（决策模式点单位是"看它的决策人"；区域/地图编辑模式点单位只是误触），
      //     一律抢模式会把那些行为打断 ⇒ 只在 view 里自动切（要更宽，需先裁）。
      //   ★ 只对**单位**生效：点格/区域的分支（下面）一个字都不动。
      //   ★ 顺序不能反：app.setMode 切模式时会**清空 selection**（见 app.js 的 setMode），
      //     故必须先切模式、再落选中，否则刚选的单位当场被清掉。
      if (mode === "view") {
        app.setMode("unit");
      }
      app.setSelection({ kind: "unit", id: pick.id });
      if (mode === "unit") {
        window.SimosMapUnitEditor.resetRoute();
      }
      return;
    }
    if (!pick.inMap) {
      app.statusMessage(
        app.byId("map-status"),
        "该位置无格（q=" + pick.q + ", r=" + pick.r + "）",
        "warn"
      );
      return;
    }
    if (mode === "unit" && selId) {
      var pos = active.positionOf(selId);
      if (pos && pos.q === pick.q && pos.r === pick.r) {
        window.SimosMapUnitEditor.cancelRouteFor(selId);
        return;
      }
    }
    if (mode === "unit" && selId && host.routeMode) {
      window.SimosMapUnitEditor.appendRoutePoint(pick.q, pick.r);
      return; // 保持单位选中：继续加路线点
    }
    // ★ M7c T1（用户裁定）：左键点格**不再瞬移**（unit.PlaceAt 已从本路径移除）——落到常规 hex 选中；
    //   移动只由右键发起（handleContextMenu → A* → unit.PlanRoute）。
    app.setSelection({ kind: "hex", q: pick.q, r: pick.r });
    if (mode === "region") {
      selectRegionOfHex(pick.q, pick.r);
    } else if (mode === "decision" && decisionViewSubpageActive()) {
      // ★ T7：决策模式（子页「决策人查看」）点格 ⇒ 该格所属**国家区域的全部区域**一起高亮（C12）。
      selectNationOfHex(pick.q, pick.r);
    }
  }

  /**
   * 决策模式是否停在「决策人查看」子页（T7）。判定走 panels.js 的 fail-closed 纯函数
   * （未知子页 ⇒ 两个都 false ⇒ 本函数 false）；panels.js 缺席（旧三页）⇒ false。
   */
  function decisionViewSubpageActive() {
    var state = app.getState();
    if (state.mode !== "decision") {
      return false;
    }
    if (window.SimosPanels && window.SimosPanels.decisionSubpageVisibility) {
      return window.SimosPanels.decisionSubpageVisibility(state.decisionSubpage).view === true;
    }
    return false;
  }

  /**
   * ★ V3 纯函数：拥有该 hex 的**最顶层区域** = 服务端 `regions` 里的**最后一个**（服务端按 `GameMap.regions`
   * 定义序发出，"后定义者在上"）。本函数**只取末位、绝不重排**——退回字典序（如 `.sort()`）会取错区域。
   * 空/非数组 ⇒ null（调用方清高亮）。
   */
  function topRegionId(regionIds) {
    if (!Array.isArray(regionIds) || !regionIds.length) {
      return null;
    }
    return regionIds[regionIds.length - 1];
  }

  /** 区域查看模式：点格 ⇒ 选中**拥有该格的最顶层区域**（V3，取代 U3-2 的"多从属退回 group"）。 */
  async function selectRegionOfHex(q, r) {
    try {
      var body = await api.mapHex(q, r, app.target());
      var regionIds = Array.isArray(body.regions) ? body.regions : [];
      var top = topRegionId(regionIds);
      if (top !== null) {
        // ★ V3：单/多从属一律只取顶层那一个 ⇒ single（更亮 + 只压同 tag）。
        app.setHighlightRegions([top], "single");
      } else {
        app.setHighlightRegions([]);
        app.statusMessage(
          app.byId("map-status"),
          "q=" + q + ", r=" + r + " 不属于任何区域",
          "muted"
        );
      }
    } catch (e) {
      app.setHighlightRegions([]);
    }
  }

  // ★ M12 第六波：地图编辑模式的宿主 UI 已搬到 map-mapeditor.js、区域编辑模式已搬到
  //   map-regioneditor.js（两者都在本文件之后、renderer.js 之前引入）。本文件对它们只做
  //   **惰性** window.SimosMapEditor.* / window.SimosMapRegionEditor.* 调用（同 SimosMapUnitEditor 手法）。

  // ── 单位移动与编辑模式（M7 T7，判据⑤ / R8）────────────────────────────
  //
  // ★ 仅 `mode === "unit"` 时启用：点单位 ⇒ 选中；**右键目标格 ⇒ A* 下路线**（左键点格只切 hex 选中、不瞬移）；
  //   路线模式 ⇒ 左键逐格相邻点列 + 下路线。
  //   其它模式仍是只读（点格只切左栏详情，不发任何非 GET 请求）。
  // ★ 所有写经 window.SimosApp.writeCommand（→ POST /api/command，R8 三个 allowlist 端点之一）。

  function coordText(coord) {
    return "(" + coord.q + "," + coord.r + ")";
  }

  /** 轴向坐标距离（pointy-top）：相邻 ⇔ 距离 1（与 Route 构造期同一口径）。 */
  function hexDistance(a, b) {
    var dq = a.q - b.q;
    var dr = a.r - b.r;
    return (Math.abs(dq) + Math.abs(dq + dr) + Math.abs(dr)) / 2;
  }

  function isAdjacent(a, b) {
    return !!a && !!b && hexDistance(a, b) === 1;
  }

  // ── 地图编辑二级子选项（T2）：纯函数，无 DOM/IO，供 node --test 直接断言 ─────────
  //
  // ★ 两条编辑线（spec §三.1）：**地形 = 属性**（`HexCell.terrain`）、**连通性 = 拓扑**（`GameMap.edges`）。
  //   子选项只是**面板分组 + 写命门控**——不是新模式、不新增本模式的编辑命令（白名单仍在 modes.js）。
  //   `mapEditWriteGate(tool, type)` 是"该工具能否发该写命令"的**唯一判定**：宿主只在它 `ok` 时发命令。

  /** 两条编辑线：id + 中文标签 + 该线的工具集（顺序即面板内工具顺序）。 */
  var MAP_EDIT_SUBTOOLS = [
    { id: "terrain", label: "地形", tools: ["terrain", "randomize", "bucket"] },
    { id: "connectivity", label: "连通性", tools: ["river", "road"] },
  ];

  var MAP_EDIT_SUBTOOL_BY_ID = {};
  MAP_EDIT_SUBTOOLS.forEach(function (entry) {
    MAP_EDIT_SUBTOOL_BY_ID[entry.id] = entry;
  });

  /** 工具 ⇒ 所属编辑线；未知工具 ⇒ `null`（不兜默认，fail-closed）。 */
  var MAP_EDIT_TOOL_SUBTOOL = {};
  MAP_EDIT_SUBTOOLS.forEach(function (entry) {
    entry.tools.forEach(function (tool) {
      MAP_EDIT_TOOL_SUBTOOL[tool] = entry.id;
    });
  });

  /** 子选项的写命令白名单（★ C2 的门控来源）：地形线改地形、连通性线改边，**互不越线**。 */
  var MAP_EDIT_SUBTOOL_WRITES = {
    terrain: ["map.SetTerrain", "map.RandomizeRegion"],
    connectivity: ["map.SetEdge"],
  };

  /** 子选项 id ⇒ `{ok, id, label, tools}`；未知/空 ⇒ `{ok:false}`（fail-closed，不兜「地形」）。 */
  function mapEditSubtoolState(raw) {
    var id = typeof raw === "string" ? raw : "";
    var entry = MAP_EDIT_SUBTOOL_BY_ID[id];
    if (!entry) {
      return { ok: false, id: null, label: null, tools: [] };
    }
    return { ok: true, id: entry.id, label: entry.label, tools: mapEditSubtoolTools(id) };
  }

  /** 编辑线的工具集（浅拷贝快照）；未知线 ⇒ 空数组。连通性线取**已注册组**（默认 river/road）。 */
  function mapEditSubtoolTools(subtool) {
    if (subtool === "connectivity") {
      return registeredEdgeKindList();
    }
    var entry = MAP_EDIT_SUBTOOL_BY_ID[subtool];
    return entry ? entry.tools.slice() : [];
  }

  /** 切到该线时的默认工具；未知线 ⇒ `null`（调用方不得据此发命令）。 */
  function mapEditSubtoolDefaultTool(subtool) {
    var tools = mapEditSubtoolTools(subtool);
    return tools.length ? tools[0] : null;
  }

  /** 工具 ⇒ 所属编辑线（`"terrain"` / `"connectivity"`）；未知工具 ⇒ `null`。★ 自定义 kind 也算连通性。 */
  function mapEditSubtoolOf(tool) {
    if (MAP_EDIT_TOOL_SUBTOOL[tool]) {
      return MAP_EDIT_TOOL_SUBTOOL[tool];
    }
    return isRegisteredEdgeKind(tool) ? "connectivity" : null;
  }

  /**
   * ★ C1：工具 ⇒ 三个面板的可见性（子选项**互斥**的唯一判定；宿主据此设 `hidden`）。
   * 有效工具下「地形」与「连通性」**恰一个**为 true；`randomize` 面板仅圈选随机化时可见、
   * `bucket` 面板仅油漆桶时可见（两者都在地形线内，故 `terrain` 同时为 true）。
   * 未知工具 ⇒ 全 false（fail-closed，不露任何面板）。
   */
  function mapEditPanelVisibility(tool) {
    var subtool = mapEditSubtoolOf(tool);
    return {
      terrain: subtool === "terrain",
      connectivity: subtool === "connectivity",
      randomize: tool === "randomize",
      bucket: tool === "bucket",
    };
  }

  /** ★ C2：该编辑线是否允许发该写命令。未知线 / 空 type / 跨线 ⇒ 拒绝（fail-closed）。 */
  function mapEditWriteAllowed(subtool, type) {
    if (!type) {
      return false;
    }
    var list = MAP_EDIT_SUBTOOL_WRITES[subtool];
    return !!list && list.indexOf(type) >= 0;
  }

  /**
   * ★ 宿主的唯一写门：由**当前工具**推出编辑线，再判该线是否允许该写命令。
   * 未知工具 ⇒ `{ok:false, reason:"unknown-tool"}`；跨线 ⇒ `{ok:false, reason:"wrong-subtool"}`。
   */
  function mapEditWriteGate(tool, type) {
    var subtool = mapEditSubtoolOf(tool);
    if (!subtool) {
      return { ok: false, reason: "unknown-tool", subtool: null };
    }
    if (!mapEditWriteAllowed(subtool, type)) {
      return { ok: false, reason: "wrong-subtool", subtool: subtool };
    }
    return { ok: true, reason: null, subtool: subtool };
  }

  // ── 连通性编辑（M8 T11）：纯函数，无 DOM/IO，供 node --test 直接断言 ──────────────
  //
  // ★ 三条护栏（都在这三个纯函数里，页面只调用它们）：
  //   ① `mode` **无默认**——`#edge-mode` 没显式选 replace/merge 就不产出 mode ⇒ 调用方不发命令（Q2 的 UI 侧）；
  //   ② 只有**相邻**两格才连成边——后端 `EdgeOperations` 只校验两端点在图上、**不校验相邻** ⇒
  //      少了这条护栏，"边"会静默进世界；
  //   ③ seed **不兜 0**——空/非整数一律 not ok，不许把"用户没填"变成"seed=0"。

  /**
   * 一条边的规范键：端点按 `(q,r)` 排序（与 Java `EdgeRef` 同口径，**不是字符串序**）⇒ `"1_1|1_2"`。
   * 自环（同一格）与缺坐标 ⇒ `null`（`EdgeRef` 构造期也拒绝自环）。
   */
  function edgeKeyOf(a, b) {
    if (!a || !b || a.q === undefined || a.r === undefined || b.q === undefined || b.r === undefined) {
      return null;
    }
    if (a.q === b.q && a.r === b.r) {
      return null;
    }
    var first = a;
    var second = b;
    if (a.q > b.q || (a.q === b.q && a.r > b.r)) {
      first = b;
      second = a;
    }
    return first.q + "_" + first.r + "|" + second.q + "_" + second.r;
  }

  /**
   * ★ 右键拖动的**轨迹** → waypoint 状态机结果（照 GSimulator `addPathwayWaypoint`，spec §三.3）：从**起点**
   * 逐格相邻地续点；**回到当前端点所在格 ⇒ 结束**（不再追加）；**非相邻续点 ⇒ 不产出该段、端点不动**（继续等下一个相邻格）。
   *
   * <p>返回 {@code {edges, ended, nonAdjacent, path}}：{@code edges} 是去重后的规范边键（顺序 = 首次出现序）；{@code
   * nonAdjacent} 供宿主给"跳过的段未写入"的可见提示（不静默）。**唯一实现**，{@link edgeChainEdges} 只是它的取边投影。
   */
  function edgeChainResult(points) {
    var edges = [];
    var seen = {};
    var path = [];
    var anchor = null;
    var ended = false;
    var nonAdjacent = false;
    (points || []).forEach(function (point) {
      if (ended || !point || point.q === undefined || point.r === undefined) {
        return;
      }
      if (!anchor) {
        anchor = { q: point.q, r: point.r };
        path.push(anchor);
        return;
      }
      if (point.q === anchor.q && point.r === anchor.r) {
        ended = true; // 同格 ⇒ 结束
        return;
      }
      if (!isAdjacent(anchor, point)) {
        nonAdjacent = true; // 非相邻 ⇒ 跳过（端点不动）
        return;
      }
      var key = edgeKeyOf(anchor, point);
      if (key && !seen[key]) {
        seen[key] = true;
        edges.push(key);
      }
      anchor = { q: point.q, r: point.r };
      path.push(anchor);
    });
    return { edges: edges, ended: ended, nonAdjacent: nonAdjacent, path: path };
  }

  /** 轨迹 → 规范边键列表（{@link edgeChainResult} 的取边投影）。 */
  function edgeChainEdges(points) {
    return edgeChainResult(points).edges;
  }

  /** 解析规范边键 {@code "q_r|q_r"} ⇒ {@code {a:{q,r}, b:{q,r}}}；形态不符 ⇒ `null`。 */
  function parseEdgeKey(key) {
    if (typeof key !== "string") {
      return null;
    }
    var halves = key.split("|");
    if (halves.length !== 2) {
      return null;
    }
    var a = parseHexKey(halves[0]);
    var b = parseHexKey(halves[1]);
    return a && b ? { a: a, b: b } : null;
  }

  function parseHexKey(text) {
    var parts = String(text).split("_");
    if (parts.length !== 2) {
      return null;
    }
    var q = Number(parts[0]);
    var r = Number(parts[1]);
    if (!Number.isInteger(q) || !Number.isInteger(r)) {
      return null;
    }
    return { q: q, r: r };
  }

  /** 点到线段的最短距离（像素/世界坐标同口径）。 */
  function pointToSegmentDistance(px, py, x1, y1, x2, y2) {
    var dx = x2 - x1;
    var dy = y2 - y1;
    var lenSq = dx * dx + dy * dy;
    if (lenSq === 0) {
      return Math.sqrt((px - x1) * (px - x1) + (py - y1) * (py - y1));
    }
    var t = ((px - x1) * dx + (py - y1) * dy) / lenSq;
    t = t < 0 ? 0 : t > 1 ? 1 : t;
    var cx = x1 + t * dx;
    var cy = y1 + t * dy;
    return Math.sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy));
  }

  /**
   * ★ **12px 命中阈值**（spec §三.3-④，照 GSimulator `findSegmentAtPixel`）：世界坐标点 → 该 kind 的入射半段
   * （格心 → 两端格心的中点）最近的那条边。返回 {@code {edge, kind, distance}} 或 `null`。`edgeViews` 形如
   * overview 的 {@code [{edge:"q_r|q_r", pathways:["river"]}…]}。
   */
  function edgeHitAtWorldPoint(worldPoint, edgeViews, size, threshold, kind) {
    if (!worldPoint || !edgeViews || !edgeViews.length) {
      return null;
    }
    var best = null;
    edgeViews.forEach(function (view) {
      if (!view || !view.edge || typeof edgeKeyOf !== "function") {
        return;
      }
      if (kind && (view.pathways || []).indexOf(kind) < 0) {
        return;
      }
      var pair = parseEdgeKey(view.edge);
      if (!pair) {
        return;
      }
      var a = hexToPixel(pair.a.q, pair.a.r, size);
      var b = hexToPixel(pair.b.q, pair.b.r, size);
      var mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
      var d = Math.min(
        pointToSegmentDistance(worldPoint.x, worldPoint.y, a.x, a.y, mid.x, mid.y),
        pointToSegmentDistance(worldPoint.x, worldPoint.y, b.x, b.y, mid.x, mid.y)
      );
      if (d <= threshold && (!best || d < best.distance)) {
        best = { edge: view.edge, kind: (view.pathways || [])[0] || kind || null, distance: d };
      }
    });
    return best;
  }

  /**
   * ★ 左键删边的**唯一写计划**（spec §三.3 / C3）：命令面没有"删单条边"，只能用 `map.SetEdge` 的
   * {@code replace}（整份覆盖该 kind）⇒ 载荷 = **该 kind 的其余边**。删到一条不剩时 `replace` 不接受空集 ⇒
   * `{ok:false, reason:"last-edge"}`（**不伪造命令**，由宿主给可见提示）。
   */
  function edgeDeletePlan(kind, edgeViews, deletedKeys) {
    var removed = {};
    (deletedKeys || []).forEach(function (key) {
      removed[key] = true;
    });
    var surviving = [];
    (edgeViews || []).forEach(function (view) {
      if (!view || !view.edge) {
        return;
      }
      if (kind && (view.pathways || []).indexOf(kind) < 0) {
        return;
      }
      if (!removed[view.edge]) {
        surviving.push(view.edge);
      }
    });
    if (!surviving.length) {
      return { ok: false, reason: "last-edge", kind: kind, edges: [] };
    }
    return { ok: true, reason: null, kind: kind, edges: surviving, mode: "replace" };
  }

  // ── 连通性词表：默认 river/road + 服务端已注册组（spec §三.6）─────────────────
  //
  // ★ 静态的 `MAP_EDIT_SUBTOOLS.tools` 只是**参考默认值**；真正的候选 = 本模块的 `registeredEdgeKinds`，
  //   由 overview.pathwayGroups 在取数后经 setRegisteredEdgeKinds 覆盖。未提供（纯前端测试）⇒ 保持默认。
  var DEFAULT_EDGE_KINDS = ["river", "road"];
  var registeredEdgeKinds = DEFAULT_EDGE_KINDS.slice();

  /** 服务端已注册组 → 候选 kind（去重保序）。★ **未提供**（非数组）⇒ 保持现状；**显式空数组**⇒ 空（fail-closed，不兜默认）。 */
  function setRegisteredEdgeKinds(list) {
    if (!Array.isArray(list)) {
      return registeredEdgeKinds.slice();
    }
    var out = [];
    list.forEach(function (kind) {
      if (typeof kind === "string" && kind && out.indexOf(kind) < 0) {
        out.push(kind);
      }
    });
    registeredEdgeKinds = out;
    return registeredEdgeKinds.slice();
  }

  /** 当前候选 kind（快照）。 */
  function registeredEdgeKindList() {
    return registeredEdgeKinds.slice();
  }

  /** 该 kind 是否已注册（写白名单外的自定义组不在此列）。 */
  function isRegisteredEdgeKind(kind) {
    return registeredEdgeKinds.indexOf(kind) >= 0;
  }

  /**
   * `#edge-mode` 的取值 → 写命令要用的 `mode`。★ **无默认**：只有显式的 `"merge"` / `"replace"` 才 `ok`；
   * 空串、未知值、非字符串一律 `{ok:false}` ⇒ 调用方**不得发命令**（只给可见提示）。
   */
  function edgeModeState(raw) {
    var value = typeof raw === "string" ? raw : "";
    if (value === "merge" || value === "replace") {
      return { ok: true, mode: value };
    }
    return { ok: false, mode: null };
  }

  /**
   * seed 文本 → 整数（Java `long` 语义）。空串/纯空白/非整数/超出 JS 精确整数范围（`|n| > 2^53-1`）⇒
   * `{ok:false}`。★ **不兜 0**："用户没填"不是一个种子。
   */
  function parseSeedInput(raw) {
    var text = typeof raw === "string" ? raw.trim() : "";
    if (text === "" || !/^[+-]?[0-9]+$/.test(text)) {
      return { ok: false, seed: null };
    }
    var value = Number(text);
    if (!Number.isSafeInteger(value)) {
      return { ok: false, seed: null };
    }
    return { ok: true, seed: value };
  }

  /** 圈选随机化的选区 → 写命令的 `hexes`。★ 空选区 `{ok:false}`（命令面也拒绝空选区，前端不发明例）。 */
  function randomizeSelectionState(list) {
    var hexes = [];
    (list || []).forEach(function (hex) {
      if (hex && hex.q !== undefined && hex.r !== undefined) {
        hexes.push({ q: hex.q, r: hex.r });
      }
    });
    if (!hexes.length) {
      return { ok: false, hexes: [] };
    }
    return { ok: true, hexes: hexes };
  }

  /**
   * ★ 圈选随机化的**两种地形**（2026-09-24 用户裁定，「我无法选择两个想要的随机化地形」）。
   *
   * <p>两侧都必须**显式给**：任一为空/空白 ⇒ `{ok:false}`（宿主据此**一条命令都不发**；命令面同样无默认值
   * ⇒ 前端不发明例）。★ `A === B` 是**合法**输入（占比退化 ⇒ 整区同地形），那是"把这一片全换成某种地形"
   * 的正路，不是错误——故选一样的两侧**不**判错。
   *
   * <p>纯函数（无 DOM/IO）：本体供 node --test 直接断言；宿主只负责"不 ok 就不发命令 + 给可见提示"。
   */
  function randomizeRecipeState(a, b) {
    var terrainA = typeof a === "string" ? a.trim() : "";
    var terrainB = typeof b === "string" ? b.trim() : "";
    if (!terrainA || !terrainB) {
      return { ok: false, terrainA: null, terrainB: null };
    }
    return { ok: true, terrainA: terrainA, terrainB: terrainB };
  }

  /**
   * 解析装备文本 `名=整数；名=整数`（分隔符 `;` `；` 或换行）⇒ `{名:整数}`；空串 ⇒ `{}`（清空语义）。
   * 坏输入抛 Error（消息含出错片段），由调用方显示——不静默丢字段。
   */
  function parseEquipmentText(text) {
    var out = {};
    var raw = (text || "").trim();
    if (raw === "") {
      return out;
    }
    raw.split(/[;；\n]/).forEach(function (piece) {
      var item = piece.trim();
      if (item === "") {
        return;
      }
      var eq = item.indexOf("=");
      if (eq <= 0) {
        throw new Error("装备项缺少「名=整数」：" + item);
      }
      var key = item.slice(0, eq).trim();
      var value = Number(item.slice(eq + 1).trim());
      if (!key || !Number.isInteger(value) || value < 0) {
        throw new Error("装备项非法（名非空、值 ≥ 0 的整数）：" + item);
      }
      out[key] = value;
    });
    return out;
  }

  // ── 启动 ──────────────────────────────────────────────────────────────
  //   ★ M12 第五波：单位移动/编辑宿主搬到 map-uniteditor.js、旧页 /map 与工作台浮层 chrome 搬到
   //   map-hostpage.js（均在**本文件之后**引入）。这里经 window.SimosMapUnitEditor /
   //   window.SimosMapHostPage 惰性调用——与 map.js 调用 window.SimosCreateRenderer 同一手法。

  function initHost() {
    var isWorkbench = !!app.byId("mode-bar");
    var canvas = app.byId("canvas");
    if (!canvas) {
      var mount = app.byId("canvas-mount");
      if (!mount) {
        return;
      }
      mount.textContent = "";
      canvas = document.createElement("canvas");
      canvas.id = "canvas";
      mount.appendChild(canvas);
    }
    active = window.SimosCreateRenderer(canvas, {
      isWorkbench: isWorkbench,
      onSelect: isWorkbench ? workbenchSelect : window.SimosMapHostPage.oldPageSelect,
      onContextMenu: isWorkbench ? window.SimosMapUnitEditor.handleContextMenu : undefined,
      // ★ §七：刷写不再由左键触发（左键恒为平移）；右键（区域 Shift+右键 / 地形 / 连边）由回调落一条命令。
      onPaintCommit: isWorkbench
        ? function (hexes) {
            if (app.getState().mode === "region-edit") {
              return window.SimosMapRegionEditor.commitRegionPaint(hexes);
            }
            if (host.mapEditTool === "randomize") {
              return window.SimosMapEditor.commitRandomizeSelection(hexes);
            }
            // ★ 油漆桶（2026-09-24）：只认**第一格**（点一下即整块替换），拖出来的其余格忽略。
            if (host.mapEditTool === "bucket") {
              return window.SimosMapEditor.commitFill(hexes[0]);
            }
            return window.SimosMapEditor.commitBrush(hexes);
          }
        : undefined,
      onEdgeCommit: isWorkbench ? window.SimosMapEditor.commitEdge : undefined,
      onEdgeDeleteCommit: isWorkbench ? window.SimosMapEditor.commitEdgeDelete : undefined,
      onLassoCommit: isWorkbench ? window.SimosMapRegionEditor.onLassoCommit : undefined,
      onDotDragCommit: isWorkbench ? window.SimosMapRegionEditor.onDotDragCommit : undefined,
    });
    host.isWorkbench = isWorkbench;
    window.SimosMapHostPage.bindActive(active);
    active.resize();
    if (!isWorkbench) {
      active.fit();
    }
    active.render();

    if (isWorkbench) {
      window.SimosMapHostPage.wireWorkbenchControls();
      // ★ F1：图层抽屉与搜索框（旧页没有这两个 DOM ⇒ 接线函数内部静默跳过，不报错）。
      wireLayerDrawer();
      wireSearchBox();
      // ★ F2：数据组（热力图指标 + 不透明度；旧页没有该 DOM ⇒ 函数内静默跳过）。
      wireHeatmapControls();
      app.onStateChange(onStateChange);
      onStateChange(app.getState());
      window.SimosMapUnitEditor.wireUnitEditor();
      window.SimosMapEditor.wireMapEditor();
      window.SimosMapRegionEditor.wireRegionEditor();
      app.onStateChange(window.SimosMapUnitEditor.renderUnitEditor);
      window.addEventListener("resize", function () {
        active.resize();
        active.render();
      });
      return;
    }

    // 旧页 /map：保留每格边长输入与重新载入（缩放/平移叠加在其上）。
    app.boot({ title: "地图" });
    var cell = app.byId("cell-size");
    if (cell) {
      cell.addEventListener("change", function (event) {
        var value = Number(event.target.value);
        if (Number.isFinite(value) && value >= 12 && value <= 80) {
          active.setCellSize(value);
          active.fit();
          active.render();
        }
      });
    }
    var reload = app.byId("reload");
    if (reload) {
      reload.addEventListener("click", function () {
        active.setSelected(null);
        app.clear(app.byId("hex-detail"));
        app.clear(app.byId("hex-facets"));
        reloadOverview();
      });
    }
    reloadOverview();
  }

  // ★ M12 第二波：createRenderer 已搬到 renderer.js（本文件之后引入）。它闭包引用的模块级
  //   常量 / 状态 / 函数在此统一挂出，供其取回。**对象按引用共享**（host / perfConfig 的属性改写
  //   对两边可见）；**可变绑定走 getter**（regionNamesEnabled —— 切开关时不取快照）。
  window.SimosMapCore = {
    app: app,
    api: api,
    // ★ M12 第五波：搬出的宿主文件（map-uniteditor.js / map-hostpage.js）需要的纯函数与状态写入口。
    coordText: coordText,
    isAdjacent: isAdjacent,
    parseEquipmentText: parseEquipmentText,
    persistRegionNamesEnabled: persistRegionNamesEnabled,
    setRegionNamesEnabled: setRegionNamesEnabled,
    hexColor: hexColor,
    host: host,
    perfConfig: perfConfig,
    BASE_CELL: BASE_CELL,
    ROUTE_OUTLINE_COLOR: ROUTE_OUTLINE_COLOR,
    ROUTE_OUTLINE_WIDTH: ROUTE_OUTLINE_WIDTH,
    ROUTE_BASE_COLOR: ROUTE_BASE_COLOR,
    ROUTE_BASE_WIDTH: ROUTE_BASE_WIDTH,
    ROUTE_REMAINING_COLOR: ROUTE_REMAINING_COLOR,
    ROUTE_REMAINING_WIDTH: ROUTE_REMAINING_WIDTH,
    HIGHLIGHT_ALPHA: HIGHLIGHT_ALPHA,
    REGION_DIM_COLOR: REGION_DIM_COLOR,
    REGION_NAME_MIN_SCALE: REGION_NAME_MIN_SCALE,
    ZOOM_WHEEL: ZOOM_WHEEL,
    OLD_PAGE_CANVAS_HEIGHT: OLD_PAGE_CANVAS_HEIGHT,
    FIT_PAD: FIT_PAD,
    TERRAIN_CACHE_MARGIN: TERRAIN_CACHE_MARGIN,
    ZOOM_SETTLE_MS: ZOOM_SETTLE_MS,
    remainingPath: remainingPath,
    terrainDimAlpha: terrainDimAlpha,
    regionNamesVisible: regionNamesVisible,
    regionLabelLayout: regionLabelLayout,
    regionNamePlan: regionNamePlan,
    REGION_NAME_VISIBLE_MARGIN: REGION_NAME_VISIBLE_MARGIN,
    mapEditSubtoolOf: mapEditSubtoolOf,
    edgeChainResult: edgeChainResult,
    edgeChainEdges: edgeChainEdges,
    parseEdgeKey: parseEdgeKey,
    edgeHitAtWorldPoint: edgeHitAtWorldPoint,
    setRegisteredEdgeKinds: setRegisteredEdgeKinds,
    // ★ M12 第六波：搬到 map-mapeditor.js / map-regioneditor.js 的两簇宿主 UI 需要的纯函数与
    //   基础设施。renderEdgeKindOptions 不在此列——它由 map-mapeditor.js 挂回本对象（见该文件尾）。
    targetKey: targetKey,
    mapEditWriteGate: mapEditWriteGate,
    mapEditPanelVisibility: mapEditPanelVisibility,
    mapEditSubtoolState: mapEditSubtoolState,
    mapEditSubtoolDefaultTool: mapEditSubtoolDefaultTool,
    edgeModeState: edgeModeState,
    edgeDeletePlan: edgeDeletePlan,
    parseSeedInput: parseSeedInput,
    randomizeSelectionState: randomizeSelectionState,
    randomizeRecipeState: randomizeRecipeState,
    registeredEdgeKindList: registeredEdgeKindList,
    fetchRegionCached: fetchRegionCached,
    refreshFocusHexes: refreshFocusHexes,
    reloadRegionEditHighlight: reloadRegionEditHighlight,
  };
  Object.defineProperty(window.SimosMapCore, "regionNamesEnabled", {
    enumerable: true,
    get: function () {
      return regionNamesEnabled;
    },
  });
  // ★ M12 第五波：active 是可变绑定（initHost 里才赋值），搬出的宿主文件必须实时读 ⇒ getter。
  Object.defineProperty(window.SimosMapCore, "active", {
    enumerable: true,
    get: function () {
      return active;
    },
  });

  window.SimosMap = {
    // 纯几何（e2e 直接断言）
    BASE_CELL: BASE_CELL,
    FALLBACK_COLOR: FALLBACK_COLOR,
    REGION_FALLBACK_COLOR: REGION_FALLBACK_COLOR,
    HIGHLIGHT_ALPHA: HIGHLIGHT_ALPHA,
    REGION_FOCUS_ALPHA: REGION_FOCUS_ALPHA,
    REGION_FADE_ALPHA: REGION_FADE_ALPHA,
    ROUTE_OUTLINE_COLOR: ROUTE_OUTLINE_COLOR,
    ROUTE_BASE_COLOR: ROUTE_BASE_COLOR,
    ROUTE_REMAINING_COLOR: ROUTE_REMAINING_COLOR,
    MIN_SCALE: MIN_SCALE,
    MAX_SCALE: MAX_SCALE,
    clamp: clamp,
    hexToPixel: hexToPixel,
    hexCorners: hexCorners,
    hexRound: hexRound,
    pixelToHex: pixelToHex,
    // ★ T2 可测性宿主（纯函数，无 DOM/IO）：轴向几何供 node --test 直接断言；行为与页面一致。
    axialNeighbors: axialNeighbors,
    axialDistance: axialDistance,
    hexLine: hexLine,
    // ★ T2：精确区域边环。原为 createRenderer 闭包内的纯函数，现上提为顶层纯函数（函数体逐字节不变）
    //   并在此暴露，供门禁内的纯函数测试直接对拍。
    regionBoundaryRings: regionBoundaryRings,
    // ★ T11：连通性编辑的护栏纯函数（无 DOM/IO）——未选 mode 不产出 mode、非相邻不连边、seed 不兜 0。
    edgeKeyOf: edgeKeyOf,
    edgeChainEdges: edgeChainEdges,
    // ★ T3：waypoint 状态机（同格结束 / 非相邻跳过）、12px 命中、左键删边计划、词表（默认+自定义）。
    edgeChainResult: edgeChainResult,
    parseEdgeKey: parseEdgeKey,
    pointToSegmentDistance: pointToSegmentDistance,
    edgeHitAtWorldPoint: edgeHitAtWorldPoint,
    edgeDeletePlan: edgeDeletePlan,
    setRegisteredEdgeKinds: setRegisteredEdgeKinds,
    registeredEdgeKindList: registeredEdgeKindList,
    isRegisteredEdgeKind: isRegisteredEdgeKind,
    edgeModeState: edgeModeState,
    parseSeedInput: parseSeedInput,
    randomizeSelectionState: randomizeSelectionState,
    randomizeRecipeState: randomizeRecipeState,
    // ★ T2：地图编辑二级子选项（地形 / 连通性）的纯函数与写门控（C1/C2）——门禁直接断言。
    MAP_EDIT_SUBTOOLS: MAP_EDIT_SUBTOOLS,
    mapEditSubtoolState: mapEditSubtoolState,
    mapEditSubtoolTools: mapEditSubtoolTools,
    mapEditSubtoolDefaultTool: mapEditSubtoolDefaultTool,
    mapEditSubtoolOf: mapEditSubtoolOf,
    mapEditPanelVisibility: mapEditPanelVisibility,
    mapEditWriteAllowed: mapEditWriteAllowed,
    mapEditWriteGate: mapEditWriteGate,
    worldToScreen: worldToScreen,
    screenToWorld: screenToWorld,
    zoomAt: zoomAt,
    fitView: fitView,
    resolveRegionColor: resolveRegionColor,
    fadeRegionColor: fadeRegionColor,
    // ★ M8 T9：区域高亮计划（纯函数）——区域查看与区域编辑共用；门禁直接对它下断言。
    buildRegionHighlightPlan: buildRegionHighlightPlan,
    // ★ 决策人可见范围的高亮计划（纯函数）：输入是 /scope 的响应体，输出画什么、什么颜色、什么透明度。
    buildDecisionScopeHighlight: buildDecisionScopeHighlight,
    // ★ V3：拥有该 hex 的最顶层区域（定义序末位，纯函数）——门禁直接断言"取末位、不重排"。
    topRegionId: topRegionId,
    // ★ T7：决策模式的国家 tag ⇒ 区域集合（纯函数，C12）——门禁直接断言"集合相等、不是子集"。
    NATION_TAG_PREFIX: NATION_TAG_PREFIX,
    nationTagOf: nationTagOf,
    nationRegionIds: nationRegionIds,
    nationIdsOfRegions: nationIdsOfRegions,
    // 两个模式的参数集（差别只有这么多：焦点透明度、无焦点时是否仍淡色）——两模式**各自独立可断言**。
    REGION_VIEW_HIGHLIGHT: REGION_VIEW_HIGHLIGHT,
    REGION_EDIT_HIGHLIGHT: REGION_EDIT_HIGHLIGHT,
    // ★ U1/U2/U3 的纯函数与常量（门禁直接断言）。
    REGION_SINGLE_HIGHLIGHT: REGION_SINGLE_HIGHLIGHT,
    REGION_DIM_ALPHA: REGION_DIM_ALPHA,
    REGION_DIM_MODES: REGION_DIM_MODES,
    REGION_NAME_MIN_SCALE: REGION_NAME_MIN_SCALE,
    REGION_NAME_MODES: REGION_NAME_MODES,
    terrainDimAlpha: terrainDimAlpha,
    regionNamesVisible: regionNamesVisible,
    regionLabelLayout: regionLabelLayout,
    regionNamePlan: regionNamePlan,
    REGION_NAME_VISIBLE_MARGIN: REGION_NAME_VISIBLE_MARGIN,
    regionTag: regionTag,
    hexDistance: hexDistance,
    isAdjacent: isAdjacent,
    remainingPath: remainingPath,
    parseEquipmentText: parseEquipmentText,
    // 宿主
    init: initHost,
    unitEditDebug: function () {
      return {
        routeMode: host.routeMode,
        routePath: host.routePath.map(function (p) {
          return { q: p.q, r: p.r };
        }),
        editBusy: host.editBusy,
      };
    },
    mapEditDebug: function () {
      var debug = active && active.debug ? active.debug() : {};
      // ★ M12 第六波：这些 DOM 只读投影已搬到 map-mapeditor.js ⇒ 经 window.SimosMapEditor 惰性取。
      var subtool = window.SimosMapEditor.checkedRadioValue("map-edit-subtools", "map-edit-subtool");
      var tool =
        subtool === "connectivity"
          ? window.SimosMapEditor.checkedRadioValue("edge-kind-select", "map-edit-edge-kind")
          : window.SimosMapEditor.checkedRadioValue("terrain-tool-select", "map-edit-terrain-tool");
      var edgeControls = app.byId("edge-controls");
      var randomizeControls = app.byId("randomize-controls");
      var seedNode = app.byId("randomize-seed");
      return {
        mode: app.getState().mode,
        paletteKeys: window.SimosMapEditor.paletteKeys(),
        selectedTerrain: host.brushTerrain,
        brushHexCount: debug.brushHexCount || 0,
        painting: !!debug.painting,
        mapEditBusy: host.mapEditBusy,
        // ★ T11 新增（e2e/调试用；全部是 DOM 的只读投影）：
        tool: tool,
        hostTool: host.mapEditTool,
        // ★ T2 新增：二级子选项（编辑线）的 DOM 投影 + host 记录。
        subtool: subtool,
        hostSubtool: host.mapEditSubtool,
        edgeMode: window.SimosMapEditor.edgeModeValue(),
        edgeControlsVisible: !!edgeControls && edgeControls.hidden === false,
        randomizeControlsVisible: !!randomizeControls && randomizeControls.hidden === false,
        edgeModeWarning: (function () {
          var node = app.byId("edge-mode-warning");
          return node && !node.hidden ? node.textContent : "";
        })(),
        randomizeWarning: (function () {
          var node = app.byId("randomize-warning");
          return node && !node.hidden ? node.textContent : "";
        })(),
        randomizeSeedText: seedNode ? seedNode.value : null,
        // ★ 2026-09-24：随机化的两种地形（来自两个下拉；供 e2e/调试看"到底发了哪两种"）。
        randomizeTerrainA: (function () {
          var node = app.byId("randomize-terrain-a");
          return node ? node.value : null;
        })(),
        randomizeTerrainB: (function () {
          var node = app.byId("randomize-terrain-b");
          return node ? node.value : null;
        })(),
        randomizeSelection: host.randomizeSelection.map(function (hex) {
          return { q: hex.q, r: hex.r };
        }),
      };
    },
    commitPaintForTest: function (hexes, terrain) {
      if (terrain !== undefined) {
        host.brushTerrain = terrain;
      }
      return window.SimosMapEditor.commitBrush(hexes || []);
    },
    regionEditDebug: function () {
      var debug = active && active.debug ? active.debug() : {};
      return {
        mode: app.getState().mode,
        focus: host.regionFocus,
        fadedRegions: host.regionFaded.slice(),
        draftHexCount: debug.draftHexCount || 0,
        draftHexes: debug.draftHexes || [],
        focusHexCount: debug.focusHexCount || 0,
        boundaryDotCount: debug.focusBoundaryCount || 0,
        // ★ M8 T9：与 `regionViewDebug` 同口径的两项 —— 两个模式共用一条高亮路径，
        //   页面上的投影也该**同样可断言**（焦点实 / 其它淡 是两档透明度，逐值可读）。
        highlightHexCount: debug.highlightHexCount || 0,
        highlightAlphas: debug.highlightAlphas || [],
        lassoHexCount: debug.lassoHexCount || 0,
        lassoActive: !!debug.lassoActive,
        dotDragging: !!debug.dotDragging,
        op: host.regionOp,
        deleteArmed: host.regionDeleteArmed,
        busy: host.regionEditBusy,
        bodyRegionFocus: document.body.getAttribute("data-region-focus"),
        nameConflict: host.regionNameConflict
          ? {
              id: host.regionNameConflict.id,
              name: host.regionNameConflict.name,
              existingId: host.regionNameConflict.existingId,
              hexCount: host.regionNameConflict.hexes.length,
            }
          : null,
        overviewRegions: (host.overviewRegions || []).map(function (region) {
          return region.id;
        }),
      };
    },
    /** 区域查看模式的可断言投影（M8 T9）：焦点集合、被淡化的区域、以及实际画出的颜色/透明度。 */
    regionViewDebug: function () {
      var debug = active && active.debug ? active.debug() : {};
      return {
        mode: app.getState().mode,
        bodyMode: document.body.getAttribute("data-mode"),
        highlightRegions: (app.getState().highlightRegions || []).slice(),
        highlightKind: app.getState().highlightKind,
        fadedRegions: (host.regionFaded || []).slice(),
        highlightHexCount: debug.highlightHexCount || 0,
        highlightColors: debug.highlightColors || [],
        highlightAlphas: debug.highlightAlphas || [],
        regionOutlineCount: debug.regionOutlineCount || 0,
        // ★ U1/U3：地形压暗是否生效、以及"焦点是否比 tag 全选更亮"的两档透明度。
        terrainDimAlpha: debug.terrainDimAlpha || 0,
        dimPasses: debug.dimPasses || 0,
        singleFocusAlpha: REGION_SINGLE_HIGHLIGHT.singleFocusAlpha,
        viewFocusAlpha: REGION_VIEW_HIGHLIGHT.focusAlpha,
      };
    },
    /** ★ U2：区域名的可断言投影（开关、是否达缩放阈值、画了几条、每条落点）。 */
    regionNameDebug: function () {
      var debug = active && active.debug ? active.debug() : {};
      var scale = active && active.view ? active.view().scale : 0;
      var state = app.getState();
      return {
        enabled: regionNamesEnabled,
        mode: state.mode,
        visible: regionNamesVisible(state.mode),
        minScale: REGION_NAME_MIN_SCALE,
        scaleOk: scale >= REGION_NAME_MIN_SCALE,
        drawn: debug.regionNameDraws || 0,
        labels: active && active.regionNameLayouts ? active.regionNameLayouts() : [],
      };
    },
    /** ★ U1/U3 取色级证据钩子：读画布上某 CSS 像素（含 dpr）。 */
    pixelAt: function (cssX, cssY) {
      return active && active.pixelAt ? active.pixelAt(cssX, cssY) : null;
    },
    /** 某一格此刻画成什么（`{color, alpha}`；未高亮 ⇒ null）——淡色态逐值可断言（M8 T9）。 */
    regionHighlightAt: function (q, r) {
      var debug = active && active.debug ? active.debug() : null;
      return debug && debug.highlightAt ? debug.highlightAt(q, r) : null;
    },
    /** 决策模式的可断言投影（T7）：高亮集合、视图子页是否生效、当前模式。 */
    decisionModeDebug: function () {
      var state = app.getState();
      return {
        mode: state.mode,
        subpage: state.decisionSubpage,
        viewSubpageActive: decisionViewSubpageActive(),
        highlightRegions: (state.highlightRegions || []).slice(),
        selection: state.selection,
        focus: state.decisionMakerFocus,
        overviewRegionIds: (host.overviewRegions || []).map(function (region) {
          return region.id;
        }),
        overviewNationTags: (host.overviewRegions || [])
          .map(function (region) {
            return nationTagOf(region);
          })
          .filter(function (tag) {
            return tag !== null;
          })
          .sort(),
        // ★ 可见范围层：最近一次算好的计划（画了几格 / 几级 / 摘要原文）——可断言。
        scope: host.decisionScope
          ? {
              level: host.decisionScope.level,
              hexCount: host.decisionScope.hexCount,
              painted: host.decisionScope.entries.length,
              summary: host.decisionScope.summary,
            }
          : null,
      };
    },
    /** 重取当前焦点的可见范围（e2e/调试用；生产路径由状态变化自动触发）。 */
    reloadDecisionScope: function () {
      return reloadDecisionScopeHighlight();
    },
    /** 把最近算好的摘要重新写回「可见范围（现算）」那一行（panels.js 重画详情后调用）。 */
    republishDecisionScopeSummary: republishDecisionScopeSummary,
    // ★ F1：搜索定位入口（搜索框 / e2e 共用同一份实现；UI 不另写一套）。
    locateSearchResult: locateSearchResult,
    // ★ F1：世界视图的只读投影（图层开关 / 世界数据条数 / 搜索索引条数）——验收与门禁可断言。
    worldViewDebug: function () {
      var debug = active && active.debug ? active.debug() : {};
      return {
        cities: host.worldCities.length,
        regionSummaries: host.worldRegionSummaries.length,
        economyOverviewLoaded: !!host.worldEconomyOverview,
        decisionMakers: host.worldDecisionMakers.length,
        units: host.worldUnits.length,
        searchIndexCount: host.searchIndex.length,
        layerState: debug.layerState || null,
        nationRegionCount: debug.nationRegionCount || 0,
        nationNameDraws: debug.nationNameDraws || 0,
        visibleCityCount: debug.visibleCityCount || 0,
        // ★ F1：GOV 辖区条数 / hex 总数——单一真相在 renderer.debug()，这里只是同源转出。
        govJurisdictions: debug.govJurisdictionCount || 0,
        govJurisdictionHexes: debug.govJurisdictionHexCount || 0,
        // ★ F2：热力层只读投影（与 renderer.debug() 同源；未选指标 ⇒ null / 0）。
        heatmapMetric: debug.heatmapMetric || null,
        heatmapCellCount: debug.heatmapCellCount || 0,
      };
    },
    // ★ F1：决策范围等级标签（gov ⇒ 政府级），纯函数、门禁可直接断言。
    decisionScopeLevelLabel: decisionScopeLevelLabel,
    regionPaintForTest: function (hexes, op) {
      if (op) {
        host.regionOp = op === "remove" ? "remove" : "add";
        active.setBrushOp(host.regionOp);
      }
      return window.SimosMapRegionEditor.commitRegionPaint(hexes || []);
    },
    // ★ M8-R：e2e 独立复核用（真套索/flood 与边界点可对拍）。
    hexExists: function (q, r) {
      return !!(active && active.hexExists && active.hexExists(q, r));
    },
    lassoForTest: function (list) {
      return active && active.lassoForTest ? active.lassoForTest(list) : [];
    },
    lassoHexes: function () {
      return active && active.lassoHexes ? active.lassoHexes() : [];
    },
    boundaryDotAtScreen: function (p) {
      return active && active.boundaryDotAt ? active.boundaryDotAt(p) : null;
    },
    focusBoundaryForTest: function () {
      return active && active.focusBoundary ? active.focusBoundary() : [];
    },
    regionOutlineRingsForTest: function () {
      return active && active.regionOutlineRings ? active.regionOutlineRings() : [];
    },
  };

  document.addEventListener("DOMContentLoaded", initHost);
})();
