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
      renderTerrainPalette(body.terrainTypes || []);
      await reloadUnits();
      var mode = app.getState().mode;
      if (mode === "map-edit" || mode === "region-edit") {
        renderRegionInfo(app.getState().selection);
      }
      if (mode === "region-edit") {
        renderRegionEditor();
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
      var body = await api.cachedUnits(app.target());
      active.setUnits((body && body.units) || []);
      return null;
    } catch (e) {
      active.setUnits([]);
      return e.message;
    }
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

  /** 「国家级 / 军队级」的中文标签（纯函数）。 */
  function decisionScopeLevelLabel(kind) {
    if (kind === "nation") {
      return "国家级";
    }
    if (kind === "army") {
      return "军队级";
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


  /** 目标坐标变了 ⇒ 地图/单位/区域填充全部按新 revision 重取（取数一律带 withTarget）。 */
  function scheduleTargetReload() {
    if (host.targetTimer) {
      window.clearTimeout(host.targetTimer);
    }
    host.targetTimer = window.setTimeout(function () {
      host.targetTimer = null;
      host.regionCache = {};
      reloadOverview();
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
      onRegionFocusChanged(focus);
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
        renderRegionEditor();
        reloadRegionEditHighlight();
        refreshFocusHexes();
        renderRegionInfo(state.selection);
      } else {
        active.setDraftHexes([]);
        active.clearFocusHexes();
        if (state.mode === "map-edit") {
          // ★ T11：切模式会清掉持久选区层 ⇒ 圈选随机化工具下把它的选区恢复显示（选区属于工具状态）。
          if (host.mapEditTool === "randomize") {
            active.setDraftHexes(host.randomizeSelection);
          }
          renderRegionInfo(state.selection);
        } else {
          clearRegionInfo();
        }
        reloadHighlights();
      }
    }
    var sel = state.selection;
    if (sel && sel.kind === "unit") {
      active.setSelectedUnit(sel.id);
      active.setSelected(active.positionOf(sel.id));
    } else {
      active.setSelectedUnit(null);
      active.setSelected(sel && sel.kind === "hex" ? { q: sel.q, r: sel.r } : null);
    }
    if (state.mode === "map-edit" || state.mode === "region-edit") {
      renderRegionInfo(sel);
    }
  }

  function workbenchSelect(pick) {
    var mode = app.getState().mode;
    var selId = window.SimosMapUnitEditor.selectedUnitId();
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

  // ── 地图编辑模式（M8 T7 框架 + T8 调色板/拖刷/区域信息）──────────────────
  //
  // ★ 写路径唯一：全部经 app.writeCommand（模式白名单在 app.js 的 writeCommand 里把关：map-edit 允许
  //   map.SetTerrain / SetEdge / RandomizeRegion；本单只实现 SetTerrain，另两条 UI 置灰）。
  // ★ 拖刷语义（Q5）：一次拖动收集**去重后的 hex 集合**，松手发**一条** map.SetTerrain（不是每格一条）。

  function setMapEditStatus(message, tone) {
    app.statusMessage(app.byId("brush-status"), message, tone);
  }

  function setRegionInfoStatus(message, tone) {
    app.statusMessage(app.byId("region-info-status"), message, tone);
  }

  function appendInfoRow(detail, label, value) {
    detail.appendChild(app.el("dt", { text: label }));
    detail.appendChild(
      app.el("dd", { text: value === null || value === undefined ? "—" : String(value) })
    );
  }

  /** `/api/map/hex` 的 `edges`（入射边 + 其 pathway 标注键）⇒ 一行可读文本；无入射边 ⇒ `"无"`。 */
  function edgeSummaryText(edges) {
    var list = Array.isArray(edges) ? edges : [];
    if (!list.length) {
      return "无";
    }
    return list
      .map(function (edge) {
        var pathways = Array.isArray(edge.pathways) ? edge.pathways : [];
        return edge.edge + (pathways.length ? "[" + pathways.join(",") + "]" : "[]");
      })
      .join("；");
  }

  function textOrNull(id) {
    var node = app.byId(id);
    var raw = node ? node.value || "" : "";
    raw = raw.trim();
    return raw === "" ? null : raw;
  }

  /** 地形调色板：**只列** /api/map/overview 的 terrainTypes（后端权威词表，绝不硬编码）。 */
  function renderTerrainPalette(types) {
    var mount = app.byId("terrain-palette");
    if (!mount) {
      return;
    }
    var list = types || [];
    var signature = list
      .map(function (type) {
        return type.key;
      })
      .join(",");
    if (signature !== host.paletteSignature) {
      host.paletteSignature = signature;
      app.clear(mount);
      list.forEach(function (type) {
        var button = app.el("button", {
          type: "button",
          class: "terrain-swatch",
          "data-terrain": type.key,
          title: app.text(type.name) + "（" + type.key + "）",
        });
        var dot = app.el("span", { class: "terrain-swatch-dot" });
        dot.style.backgroundColor = type.color;
        button.appendChild(dot);
        button.appendChild(app.el("span", { class: "terrain-swatch-label", text: type.key }));
        button.addEventListener("click", function () {
          selectTerrain(type.key);
        });
        mount.appendChild(button);
      });
      if (signature.indexOf(host.brushTerrain || "") < 0) {
        host.brushTerrain = null;
      }
    }
    updatePaletteSelection();
    app.statusMessage(
      app.byId("terrain-palette-status"),
      "词表 " + list.length + " 类：" + (signature || "（空）"),
      "muted"
    );
  }

  function selectTerrain(key) {
    if (host.brushTerrain === key) {
      host.brushTerrain = null;
      updatePaletteSelection();
      setMapEditStatus("已取消地形选择：左键恢复为平移/选中。", "muted");
      return;
    }
    host.brushTerrain = key;
    updatePaletteSelection();
    setMapEditStatus("已选地形 " + key + "：右键在地图上拖动涂抹（多格 ⇒ 一条 map.SetTerrain）；左键=平移。", "ok");
  }

  function updatePaletteSelection() {
    var mount = app.byId("terrain-palette");
    if (!mount) {
      return;
    }
    Array.prototype.forEach.call(mount.querySelectorAll("button[data-terrain]"), function (button) {
      button.classList.toggle("active", button.getAttribute("data-terrain") === host.brushTerrain);
    });
  }

  /** 计划要画的地形（e2e/调试用；null = 未选）。 */
  function paletteKeys() {
    var mount = app.byId("terrain-palette");
    if (!mount) {
      return [];
    }
    return Array.prototype.map.call(mount.querySelectorAll("button[data-terrain]"), function (button) {
      return button.getAttribute("data-terrain");
    });
  }

  /** 拖刷松手回调：把去重后的 hex 集合发**一条** map.SetTerrain；错例原文显示、不重试。 */
  async function commitBrush(hexes) {
    if (host.mapEditBusy) {
      return null;
    }
    if (!mapEditWriteGate(host.mapEditTool, "map.SetTerrain").ok) {
      setMapEditStatus("当前不是「地形」编辑线 ⇒ **未发出任何写命令**。", "warn");
      active.setBrushHexes([]);
      return null;
    }
    if (!host.brushTerrain) {
      setMapEditStatus("先选一种地形再涂抹。", "warn");
      active.setBrushHexes([]);
      return null;
    }
    if (!hexes || !hexes.length) {
      return null;
    }
    host.mapEditBusy = true;
    setMapEditStatus(
      "提交 map.SetTerrain：" + hexes.length + " 格 → " + host.brushTerrain + " …",
      "muted"
    );
    var result = await app.writeCommand("map.SetTerrain", {
      hexes: hexes,
      terrain: host.brushTerrain,
    });
    host.mapEditBusy = false;
    active.setBrushHexes([]);
    var last = hexes[hexes.length - 1];
    if (last) {
      app.setSelection({ kind: "hex", q: last.q, r: last.r });
    }
    if (result.ok) {
      setMapEditStatus(
        "已改 " + hexes.length + " 格为 " + host.brushTerrain + "（一条命令，head 已前进）",
        "ok"
      );
    } else {
      setMapEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    return result;
  }

  // ── 连通性（河流/道路）+ 圈选随机化（M8 T11）───────────────────────────────
  //
  // ★ 按键模型不变（M8-R 用户裁定）：左键恒为平移；右键按**当前工具**分派。
  // ★ 三条护栏都在纯函数里（`edgeChainEdges` / `edgeModeState` / `parseSeedInput` /
  //   `randomizeSelectionState`，见文件顶部），宿主只负责"不 ok 就不发命令 + 给可见提示"。

  function setMapEditToolStatus(message, tone) {
    app.statusMessage(app.byId("map-edit-tool-status"), message, tone);
  }

  function setEdgeStatus(message, tone) {
    app.statusMessage(app.byId("edge-status"), message, tone);
  }

  function setRandomizeStatus(message, tone) {
    app.statusMessage(app.byId("randomize-status"), message, tone);
  }

  /** 显示/隐藏一条**可见提示**（护栏不 ok 时用；空文本 ⇒ 隐藏）。 */
  function setWarning(id, message) {
    var node = app.byId(id);
    if (!node) {
      return;
    }
    node.textContent = message || "";
    node.hidden = !message;
  }

  /** 把一个 radio 组里 value 匹配的那个置为 checked（找不到就什么都不做——不静默改别的）。 */
  function checkRadio(groupId, name, value) {
    var group = app.byId(groupId);
    if (!group) {
      return;
    }
    Array.prototype.forEach.call(group.querySelectorAll('input[name="' + name + '"]'), function (input) {
      input.checked = input.value === value;
    });
  }

  /** 读一个 radio 组里被选中的值；无组/无选中 ⇒ `null`。 */
  function checkedRadioValue(groupId, name) {
    var group = app.byId(groupId);
    var value = null;
    if (group) {
      Array.prototype.forEach.call(group.querySelectorAll('input[name="' + name + '"]'), function (input) {
        if (input.checked) {
          value = input.value;
        }
      });
    }
    return value;
  }

  /** 给一个 radio 组挂 change 监听：仅在选中时回调该值（照既有工具组的写法）。 */
  function wireRadioGroup(groupId, name, onPick) {
    var group = app.byId(groupId);
    if (!group) {
      return;
    }
    Array.prototype.forEach.call(group.querySelectorAll('input[name="' + name + '"]'), function (input) {
      input.addEventListener("change", function () {
        if (input.checked) {
          onPick(input.value);
        }
      });
    });
  }

  /** 把两个 radio 组（子选项 / 线内工具）都同步到 host 状态。 */
  function syncMapEditRadios() {
    checkRadio("map-edit-subtools", "map-edit-subtool", host.mapEditSubtool);
    checkRadio("terrain-tool-select", "map-edit-terrain-tool", host.mapEditTool);
    checkRadio("edge-kind-select", "map-edit-edge-kind", host.mapEditTool);
  }

  /**
   * ★ T3：依据**已注册组**重建 `#edge-kind-select` 的候选（服务端 overview.pathwayGroups 权威；默认 river/road）。
   * 重建后必须**重新挂** change 监听（innerHTML 换掉旧节点 ⇒ 旧监听一并消失）。标签取组的 `name`。
   */
  function renderEdgeKindOptions() {
    var mount = app.byId("edge-kind-select");
    if (!mount) {
      return;
    }
    var kinds = registeredEdgeKindList();
    var labelOf = {};
    (host.overviewGroups || []).forEach(function (group) {
      if (group && group.id) {
        labelOf[group.id] = group.name || group.id;
      }
    });
    mount.textContent = "";
    kinds.forEach(function (kind) {
      var label = document.createElement("label");
      var input = document.createElement("input");
      input.type = "radio";
      input.name = "map-edit-edge-kind";
      input.value = kind;
      label.appendChild(input);
      label.appendChild(document.createTextNode(" " + (labelOf[kind] || kind)));
      mount.appendChild(label);
    });
    wireRadioGroup("edge-kind-select", "map-edit-edge-kind", function (value) {
      selectMapEditTool(value);
    });
    checkRadio("edge-kind-select", "map-edit-edge-kind", host.mapEditTool);
  }

  /** 选一条编辑线（地形 / 连通性）：未知值 ⇒ 落到「地形」；线内工具跨线 ⇒ 落到该线默认工具。 */
  function setMapEditSubtool(value) {
    var subtool = mapEditSubtoolState(value).ok ? value : "terrain";
    host.mapEditSubtool = subtool;
    var tool = host.mapEditTool;
    if (mapEditSubtoolOf(tool) !== subtool) {
      tool = mapEditSubtoolDefaultTool(subtool);
    }
    selectMapEditTool(tool);
  }

  /** 选本线内的一个工具：切换可见控件组 + 重设右键分派（渲染器侧）+ 恢复该工具的选区。 */
  function selectMapEditTool(value) {
    // ★ T3：合法工具 = 地形组（terrain/randomize）+ **已注册的连通性组**（默认 river/road，自定义 canal …）；
    //   其它一律落到 terrain（fail-closed，不把未知串当工具）。
    var tool = mapEditSubtoolOf(value) ? value : "terrain";
    host.mapEditTool = tool;
    host.mapEditSubtool = mapEditSubtoolOf(tool) || "terrain";
    if (active && active.setEditTool) {
      active.setEditTool(tool);
    }
    var panels = mapEditPanelVisibility(tool);
    var terrainControls = app.byId("terrain-tool-controls");
    if (terrainControls) {
      terrainControls.hidden = !panels.terrain;
    }
    var edgeControls = app.byId("edge-controls");
    if (edgeControls) {
      edgeControls.hidden = !panels.connectivity;
    }
    var randomizeControls = app.byId("randomize-controls");
    if (randomizeControls) {
      randomizeControls.hidden = !panels.randomize;
    }
    setWarning("edge-mode-warning", "");
    setWarning("randomize-warning", "");
    if (tool === "randomize") {
      active.setBrushHexes([]);
      active.setDraftHexes(host.randomizeSelection);
      renderRandomizeStatus();
    } else {
      active.setDraftHexes([]);
      active.setBrushHexes([]);
    }
    if (tool === "terrain") {
      setMapEditToolStatus("编辑线：地形（地形刷）—— 右键拖动涂抹；左键=平移地图。", "muted");
    } else if (tool === "randomize") {
      setMapEditToolStatus("编辑线：地形（圈选随机化）—— 右键拖动圈选；左键=平移地图。", "muted");
    } else {
      setMapEditToolStatus(
        "编辑线：连通性（" +
          edgeKindLabel(tool) +
          "）—— 右键拖动连起相邻两格（一条 map.SetEdge）；左键点/拖命中边即删；空白处左键=平移地图。",
        "muted"
      );
      renderEdgeControls();
    }
    syncMapEditRadios();
  }

  /** `#edge-mode` 的当前值（空串 = 未选）。 */
  function edgeModeValue() {
    var node = app.byId("edge-mode");
    return node && typeof node.value === "string" ? node.value : "";
  }

  /** 依据 `#edge-mode` 的当前值刷新提示（★ 未选时**必须**有可见提示）。 */
  function renderEdgeControls() {
    var state = edgeModeState(edgeModeValue());
    if (state.ok) {
      setWarning("edge-mode-warning", "");
      setEdgeStatus("语义 " + state.mode + "：右键拖动连边。", "muted");
    } else {
      setWarning("edge-mode-warning", "未选 replace/merge：右键拖动不会发出任何写命令。");
      setEdgeStatus("先在上面选 replace 或 merge。", "warn");
    }
  }

  /** 连边松手：**先过护栏**（未选 mode / 非连通性线 ⇒ 一条命令都不发），再一次 app.writeCommand。 */
  async function commitEdge(chain) {
    active.setBrushHexes([]);
    if (host.mapEditBusy) {
      return null;
    }
    if (!mapEditWriteGate(host.mapEditTool, "map.SetEdge").ok) {
      setEdgeStatus("当前不是「连通性」编辑线 ⇒ **未发出任何写命令**。", "warn");
      return null;
    }
    var edges = (chain && chain.edges) || [];
    var path = (chain && chain.path) || [];
    var kind = host.mapEditTool; // 已是已注册组（selectMapEditTool 的 fail-closed 保证）
    var modeState = edgeModeState(edgeModeValue());
    if (!modeState.ok) {
      // ★ Q2 的 UI 侧：**不预选、不兜默认**——用户没选就一个字节都不发。
      setWarning("edge-mode-warning", "未选 replace/merge：右键拖动不会发出任何写命令。");
      setEdgeStatus(
        "未选连通性语义 ⇒ **未发出任何写命令**（本次拖动 " +
          edges.length +
          " 条边已丢弃；轨迹 " +
          path.length +
          " 格）。",
        "warn"
      );
      return null;
    }
    if (!edges.length) {
      setEdgeStatus(
        "非相邻/缺格的两格连不成边 ⇒ **未发出任何写命令**（轨迹 " + path.length + " 格）。",
        "warn"
      );
      return null;
    }
    host.mapEditBusy = true;
    setEdgeStatus("提交 map.SetEdge：" + kind + " × " + edges.length + " 条（" + modeState.mode + "）…", "muted");
    var result = await app.writeCommand("map.SetEdge", {
      kind: kind,
      edges: edges,
      mode: modeState.mode,
    });
    host.mapEditBusy = false;
    if (result.ok) {
      setEdgeStatus(
        "已改 " +
          kind +
          " " +
          edges.length +
          " 条边（" +
          modeState.mode +
          "，一条命令，head 已前进）" +
          (chain && chain.nonAdjacent ? "；非相邻/缺格段已跳过" : ""),
        "ok"
      );
      await refreshRegionInfoNow();
    } else {
      setEdgeStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    return result;
  }

  /**
   * ★ 左键删边（T3）：把"该 kind 的其余边"作为 {@code replace} 载荷发一条 map.SetEdge（命令面没有删单条边的命令）。
   * 删到一条不剩 ⇒ **不伪造命令**、给可见提示（{@code edgeDeletePlan} 的 `last-edge`）。
   */
  async function commitEdgeDelete(kind, deletedKeys) {
    if (host.mapEditBusy) {
      return null;
    }
    if (!mapEditWriteGate(kind, "map.SetEdge").ok) {
      setEdgeStatus("当前不是「连通性」编辑线 ⇒ **未发出任何写命令**。", "warn");
      return null;
    }
    var plan = edgeDeletePlan(kind, host.overviewEdges, deletedKeys);
    if (!plan.ok) {
      setEdgeStatus(
        "无法删除最后一条 " + kind + "：命令面 replace 不接受空集 ⇒ **未发出任何写命令**。",
        "warn"
      );
      return null;
    }
    host.mapEditBusy = true;
    setEdgeStatus("删除 " + kind + " " + (deletedKeys || []).length + " 条边（replace 其余 " + plan.edges.length + " 条）…", "muted");
    var result = await app.writeCommand("map.SetEdge", {
      kind: plan.kind,
      edges: plan.edges,
      mode: plan.mode,
    });
    host.mapEditBusy = false;
    if (result.ok) {
      setEdgeStatus("已删 " + (deletedKeys || []).length + " 条 " + kind + "（一条命令，head 已前进）", "ok");
      await refreshRegionInfoNow();
    } else {
      setEdgeStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    return result;
  }

  /** 圈选松手：选区**持久显示**、**不发命令**（命令由「执行随机化」按钮发）。 */
  function commitRandomizeSelection(hexes) {
    var state = randomizeSelectionState(hexes);
    host.randomizeSelection = state.hexes;
    active.setBrushHexes([]);
    active.setDraftHexes(host.randomizeSelection);
    setWarning("randomize-warning", "");
    renderRandomizeStatus();
    return null;
  }

  function renderRandomizeStatus() {
    var count = host.randomizeSelection.length;
    var node = app.byId("randomize-seed");
    var seedState = parseSeedInput(node ? node.value : "");
    setRandomizeStatus(
      "选区 " + count + " 格；seed " + (seedState.ok ? seedState.seed : "（未填/非法）") + "。",
      count && seedState.ok ? "ok" : "muted"
    );
  }

  /** 「执行随机化」：**先过两条护栏**（空选区 / seed 非整数 ⇒ 一条命令都不发），再一次 writeCommand。 */
  async function submitRandomize() {
    if (host.mapEditBusy) {
      return null;
    }
    if (!mapEditWriteGate(host.mapEditTool, "map.RandomizeRegion").ok) {
      setWarning("randomize-warning", "当前不是「地形」编辑线 ⇒ **未发出任何写命令**。");
      setRandomizeStatus("不在「地形」编辑线 ⇒ 未发出任何写命令。", "warn");
      return null;
    }
    var selState = randomizeSelectionState(host.randomizeSelection);
    if (!selState.ok) {
      setWarning("randomize-warning", "选区为空：先在图上右键拖动圈选，**没有发出任何写命令**。");
      setRandomizeStatus("选区为空 ⇒ 未发出任何写命令。", "warn");
      return null;
    }
    var seedNode = app.byId("randomize-seed");
    var seedState = parseSeedInput(seedNode ? seedNode.value : "");
    if (!seedState.ok) {
      setWarning("randomize-warning", "seed 必须是整数（Java long）：**没有发出任何写命令**。");
      setRandomizeStatus("seed 非法 ⇒ 未发出任何写命令。", "warn");
      return null;
    }
    setWarning("randomize-warning", "");
    host.mapEditBusy = true;
    setRandomizeStatus(
      "提交 map.RandomizeRegion：" + selState.hexes.length + " 格，seed " + seedState.seed + " …",
      "muted"
    );
    var result = await app.writeCommand("map.RandomizeRegion", {
      hexes: selState.hexes,
      seed: seedState.seed,
    });
    host.mapEditBusy = false;
    if (result.ok) {
      setRandomizeStatus(
        "已随机化 " + selState.hexes.length + " 格（seed " + seedState.seed + "，一条命令，head 已前进）",
        "ok"
      );
      await refreshRegionInfoNow();
    } else {
      setRandomizeStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    return result;
  }

  /** 写命令落地后立刻重取一次区域信息面板（否则缓存键不变、面板停留在旧值）。 */
  async function refreshRegionInfoNow() {
    var selection = app.getState().selection;
    if (!selection || selection.kind !== "hex") {
      return;
    }
    host.regionInfoKey = null;
    renderRegionInfo(selection);
  }

  function clearRegionInfo() {
    host.regionInfoKey = null;
    app.clear(app.byId("region-info-detail"));
    var editor = app.byId("region-meta-editor");
    if (editor) {
      editor.hidden = true;
    }
  }

  /** 区域信息面板（M8 T8；T10 起 map-edit 与 region-edit 共用）：显示所选 hex 的多值 regions 与地形。 */
  function renderRegionInfo(selection) {
    if (!app.byId("region-info-detail")) {
      return;
    }
    var mode = app.getState().mode;
    if (mode !== "map-edit" && mode !== "region-edit") {
      return;
    }
    if (!selection || selection.kind !== "hex") {
      clearRegionInfo();
      if (mode === "region-edit" && host.regionFocus) {
        fillRegionMetaEditor([host.regionFocus]);
        setRegionInfoStatus("区域编辑目标：" + host.regionFocus, "muted");
      } else {
        setRegionInfoStatus("点选一个格子查看其所属区域与地形。", "muted");
      }
      return;
    }
    var key = selection.q + "," + selection.r + "@" + targetKey();
    if (key === host.regionInfoKey) {
      return;
    }
    host.regionInfoKey = key;
    setRegionInfoStatus("查询 (" + selection.q + "," + selection.r + ") …", "muted");
    api
      .mapHex(selection.q, selection.r, app.target())
      .then(function (hex) {
        if (key !== host.regionInfoKey) {
          return;
        }
        var detail = app.clear(app.byId("region-info-detail"));
        var terrainText = hex.terrain;
        if (hex.terrainType && hex.terrainType.name) {
          terrainText = hex.terrain + "（" + hex.terrainType.name + "）";
        }
        appendInfoRow(detail, "q", hex.q);
        appendInfoRow(detail, "r", hex.r);
        appendInfoRow(detail, "terrain", terrainText);
        var regions = Array.isArray(hex.regions) ? hex.regions : [];
        appendInfoRow(detail, "regions", regions.length ? regions.join("、") : "无区域");
        appendInfoRow(detail, "连通性", edgeSummaryText(hex.edges));
        fillRegionMetaEditor(regions);
        setRegionInfoStatus(
          "(" + hex.q + "," + hex.r + ") · " + regions.length + " 个区域",
          "ok"
        );
      })
      .catch(function (e) {
        if (key !== host.regionInfoKey) {
          return;
        }
        clearRegionInfo();
        setRegionInfoStatus("查询失败：" + e.message, "err");
      });
  }

  function fillRegionMetaEditor(regionIds) {
    var editor = app.byId("region-meta-editor");
    var select = app.byId("region-meta-target");
    if (!editor || !select) {
      return;
    }
    if (!regionIds.length) {
      editor.hidden = true;
      return;
    }
    editor.hidden = false;
    var previous = select.value;
    app.clear(select);
    regionIds.forEach(function (id) {
      select.appendChild(app.el("option", { value: id, text: id }));
    });
    select.value = regionIds.indexOf(previous) >= 0 ? previous : regionIds[0];
    loadRegionMeta(select.value);
  }

  async function loadRegionMeta(id) {
    if (!id) {
      return;
    }
    try {
      var region = await api.mapRegion(id, app.target());
      var meta = region.meta || {};
      app.byId("region-meta-color").value = meta.color || "";
      app.byId("region-meta-tag").value = meta.tag || "";
      app.byId("region-meta-description").value = meta.description || "";
      app.byId("region-meta-annexedby").value = meta.annexedBy || "";
    } catch (e) {
      setRegionInfoStatus("区域元数据载入失败：" + e.message, "warn");
    }
  }

  /** 只改 meta（不带 hexes）⇒ map.UpdateRegion；区域内容不动。 */
  async function submitRegionMeta() {
    if (host.mapEditBusy) {
      return null;
    }
    var select = app.byId("region-meta-target");
    var id = select ? select.value : "";
    if (!id) {
      setRegionInfoStatus("先选一个区域。", "warn");
      return null;
    }
    var meta = {
      color: textOrNull("region-meta-color"),
      tag: textOrNull("region-meta-tag"),
      description: textOrNull("region-meta-description"),
      annexedBy: textOrNull("region-meta-annexedby"),
    };
    host.mapEditBusy = true;
    setRegionInfoStatus("提交元数据 " + id + " …", "muted");
    var result = await app.writeCommand("map.UpdateRegion", { regionId: id, meta: meta });
    host.mapEditBusy = false;
    setRegionInfoStatus(
      result.ok ? "已更新 " + id + " 的元数据" : result.message,
      result.ok ? "ok" : result.kind === "rejected" ? "err" : "warn"
    );
    return result;
  }

  function wireMapEditor() {
    var submit = app.byId("region-meta-submit");
    if (submit) {
      submit.addEventListener("click", submitRegionMeta);
    }
    var select = app.byId("region-meta-target");
    if (select) {
      select.addEventListener("change", function () {
        loadRegionMeta(select.value);
      });
    }
    // ★ T2：三个单选组——编辑线（地形/连通性）、地形线内工具、连通性线内类型。
    wireRadioGroup("map-edit-subtools", "map-edit-subtool", function (value) {
      setMapEditSubtool(value);
    });
    wireRadioGroup("terrain-tool-select", "map-edit-terrain-tool", function (value) {
      selectMapEditTool(value);
    });
    wireRadioGroup("edge-kind-select", "map-edit-edge-kind", function (value) {
      selectMapEditTool(value);
    });
    // ★ replace/merge 选择器：**初始为空串（无预选）**，切换只刷新提示与 host 记录。
    var modeNode = app.byId("edge-mode");
    if (modeNode) {
      modeNode.addEventListener("change", function () {
        host.edgeMode = modeNode.value;
        renderEdgeControls();
      });
    }
    var seedNode = app.byId("randomize-seed");
    if (seedNode) {
      seedNode.addEventListener("input", renderRandomizeStatus);
    }
    var randomizeSubmit = app.byId("randomize-submit");
    if (randomizeSubmit) {
      randomizeSubmit.addEventListener("click", submitRandomize);
    }
    setMapEditSubtool(host.mapEditSubtool);
  }

  // ── 区域编辑模式（M8 T10）：绘新区域 / 改已有区域 hex / 删除（二次确认）────────
  //
  // ★ M8-U1：重叠是**正常状态**，前端绝不加"禁止重叠"的校验或提示（那是缺陷不是贴心）。
  // ★ 写路径唯一：三条命令都经 app.writeCommand（白名单在 app.js 的 writeCommand 把关）。
  // ★ 选区（draft）由渲染器的持久选区层画（松手后仍显示）；拖动中的预览复用 T8 刷子机制。

  function regionDraftList() {
    return Object.keys(host.regionDraft).map(function (key) {
      return host.regionDraft[key];
    });
  }

  function setRegionEditStatus(message, tone) {
    app.statusMessage(app.byId("region-edit-status"), message, tone);
  }

  function renderRegionEditor() {
    var draftNode = app.byId("region-edit-draft");
    if (draftNode) {
      draftNode.textContent = "临时选区：" + regionDraftList().length + " 格";
    }
    var focusNode = app.byId("region-edit-focus");
    if (focusNode) {
      focusNode.textContent = host.regionFocus || "未选中";
    }
    var delName = app.byId("region-delete-name");
    if (delName) {
      delName.textContent = host.regionFocus || "—";
    }
    var opSelect = app.byId("region-edit-op");
    if (opSelect) {
      opSelect.value = host.regionOp;
    }
    var confirm = app.byId("region-delete-confirm");
    if (confirm) {
      confirm.hidden = !host.regionDeleteArmed;
    }
    var updateBtn = app.byId("region-update-submit");
    if (updateBtn) {
      updateBtn.disabled = !host.regionFocus || host.regionEditBusy;
    }
    var loadBtn = app.byId("region-edit-load");
    if (loadBtn) {
      loadBtn.disabled = !host.regionFocus;
    }
    var mergeBtn = app.byId("region-merge");
    if (mergeBtn) {
      mergeBtn.disabled = !host.regionFocus || host.regionEditBusy;
    }
    var excludeBtn = app.byId("region-exclude");
    if (excludeBtn) {
      excludeBtn.disabled = !host.regionFocus || host.regionEditBusy;
    }
    var deleteBtn = app.byId("region-delete");
    if (deleteBtn) {
      deleteBtn.disabled = !host.regionFocus;
    }
    var conflict = app.byId("region-name-conflict");
    if (conflict) {
      conflict.hidden = !host.regionNameConflict;
      if (host.regionNameConflict) {
        var conflictMsg = app.byId("region-name-conflict-msg");
        if (conflictMsg) {
          conflictMsg.textContent =
            "名称「" +
            host.regionNameConflict.name +
            "」已存在（regionId=" +
            host.regionNameConflict.existingId +
            "）——请选择：";
        }
      }
    }
  }

  /** 新区域 id 的**建议值**（可改）：取未被占用的 `region-<n>`（Q3：RegionId 由调用方给）。 */
  function suggestRegionId() {
    var used = {};
    (host.overviewRegions || []).forEach(function (region) {
      if (region && region.id !== undefined && region.id !== null) {
        used[String(region.id)] = true;
      }
    });
    for (var n = 1; n < 10000; n++) {
      var candidate = "region-" + n;
      if (!used[candidate]) {
        return candidate;
      }
    }
    return "region-new";
  }

  function newRegionDraft() {
    if (app.getState().mode !== "region-edit") {
      return;
    }
    host.regionDraft = {};
    host.regionOp = "add";
    host.regionDeleteArmed = false;
    app.setRegionFocus(null);
    active.setBrushOp(host.regionOp);
    active.setDraftHexes([]);
    active.clearFocusHexes();
    var idInput = app.byId("region-create-id");
    if (idInput && !idInput.value.trim()) {
      idInput.value = suggestRegionId();
    }
    var nameInput = app.byId("region-create-name");
    if (nameInput && !nameInput.value.trim()) {
      nameInput.value = "新区域";
    }
    renderRegionEditor();
    reloadRegionEditHighlight();
    setRegionEditStatus(
      "★ 右键拖动=套索创建（flood fill 内部）；Shift+右键拖动=逐格画/擦（临时选区，供合并/剔除）；左键拖动=平移。重叠不报错。",
      "muted"
    );
  }

  function clearRegionDraft() {
    host.regionDraft = {};
    active.setDraftHexes([]);
    renderRegionEditor();
    setRegionEditStatus("选区已清空。", "muted");
  }

  async function loadFocusIntoDraft() {
    if (!host.regionFocus) {
      setRegionEditStatus("先在右栏选一个区域。", "warn");
      return null;
    }
    var region = await fetchRegionCached(host.regionFocus);
    host.regionDraft = {};
    (region.hexes || []).forEach(function (h) {
      host.regionDraft[h.q + "_" + h.r] = { q: h.q, r: h.r };
    });
    active.setDraftHexes(regionDraftList());
    renderRegionEditor();
    setRegionEditStatus("已载入 " + host.regionFocus + " 的 " + regionDraftList().length + " 格到选区。", "muted");
    return regionDraftList();
  }

  /** Shift+右键逐格画/擦松手：把涂抹的格按当前操作并入/移出**临时选区**（**不发写**，选区只是编辑草稿）。 */
  function commitRegionPaint(painted) {
    if (app.getState().mode !== "region-edit") {
      return null;
    }
    var op = host.regionOp;
    (painted || []).forEach(function (h) {
      if (!h || h.q === undefined || h.r === undefined) {
        return;
      }
      var key = h.q + "_" + h.r;
      if (op === "remove") {
        delete host.regionDraft[key];
      } else {
        host.regionDraft[key] = { q: h.q, r: h.r };
      }
    });
    active.setBrushHexes([]);
    active.setDraftHexes(regionDraftList());
    var last = painted && painted.length ? painted[painted.length - 1] : null;
    if (last) {
      app.setSelection({ kind: "hex", q: last.q, r: last.r });
    }
    renderRegionEditor();
    setRegionEditStatus(
      (op === "remove" ? "已移除 " : "已加入 ") +
        (painted ? painted.length : 0) +
        " 格（选区共 " +
        regionDraftList().length +
        " 格）",
      "muted"
    );
    return { ok: true, draftCount: regionDraftList().length };
  }

  /** 在左栏已有的区域名里找 trim 后精确同名的区域；返回 overview 条目或 null。 */
  function findSameNameRegion(name) {
    var wanted = String(name || "").trim();
    if (!wanted) {
      return null;
    }
    var found = null;
    (host.overviewRegions || []).forEach(function (region) {
      if (found || !region) {
        return;
      }
      var other = String(region.name === undefined || region.name === null ? "" : region.name).trim();
      if (other === wanted) {
        found = region;
      }
    });
    return found;
  }

  /** 某个 regionId 是否已被占用（用于「新建同名区域」时换一个不同的 id）。 */
  function regionIdExists(id) {
    var used = false;
    (host.overviewRegions || []).forEach(function (region) {
      if (region && String(region.id) === String(id)) {
        used = true;
      }
    });
    return used;
  }

  /**
   * ★ M8-S §9.2：建区前**先查同名**（左栏 overview 的已有区域名）。同名 ⇒ 挂起并弹二选一，
   * **不静默新建、不静默合并**；只有用户点选后才发**恰一条**命令。
   */
  async function requestCreateRegion(id, name, hexes) {
    var same = findSameNameRegion(name);
    if (same) {
      host.regionNameConflict = {
        id: id,
        name: name,
        hexes: hexes.slice(),
        existingId: String(same.id),
        existingName: String(same.name),
      };
      renderRegionEditor();
      setRegionEditStatus(
        "名称「" + name + "」已存在（regionId=" + same.id + "）：请选择「新建同名区域」或「合并到同名已有区域」。未发任何命令。",
        "warn"
      );
      return { ok: false, pending: true };
    }
    return submitCreateRegionNow(id, name, hexes);
  }

  /** 真正落一条 map.CreateRegion（不含同名检测）。 */
  async function submitCreateRegionNow(id, name, hexes) {
    if (host.regionEditBusy) {
      return null;
    }
    host.regionEditBusy = true;
    host.regionNameConflict = null;
    setRegionEditStatus("提交 map.CreateRegion " + id + "（" + hexes.length + " 格）…", "muted");
    var result = await app.writeCommand("map.CreateRegion", { regionId: id, name: name, hexes: hexes });
    host.regionEditBusy = false;
    if (result.ok) {
      setRegionEditStatus("已创建 " + id + "（" + hexes.length + " 格，重叠允许）—— head 已前进", "ok");
      app.setRegionFocus(id);
    } else {
      setRegionEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    renderRegionEditor();
    return result;
  }

  /** 重名提示选 (i)：**新建同名区域**——用不同的 id 发**恰 1 条** map.CreateRegion（同 name）。 */
  async function resolveNameConflictCreateNew() {
    var pending = host.regionNameConflict;
    if (!pending || host.regionEditBusy) {
      return null;
    }
    var id = pending.id;
    if (!id || regionIdExists(id)) {
      id = suggestRegionId();
      var idInput = app.byId("region-create-id");
      if (idInput) {
        idInput.value = id;
      }
    }
    return submitCreateRegionNow(id, pending.name, pending.hexes);
  }

  /** 重名提示选 (ii)：**合并到同名已有区域**——恰 1 条 map.UpdateRegion{hexes: 已有 ∪ 新建}。 */
  async function resolveNameConflictMerge() {
    var pending = host.regionNameConflict;
    if (!pending || host.regionEditBusy) {
      return null;
    }
    host.regionEditBusy = true;
    host.regionNameConflict = null;
    setRegionEditStatus("合并到同名区域 " + pending.existingId + " …", "muted");
    var existing = await fetchRegionCached(pending.existingId);
    var hexes = unionHexes(existing.hexes || [], pending.hexes);
    var result = await app.writeCommand("map.UpdateRegion", { regionId: pending.existingId, hexes: hexes });
    host.regionEditBusy = false;
    if (result.ok) {
      host.regionDraft = {};
      active.setDraftHexes([]);
      host.regionCache = {};
      setRegionEditStatus(
        "已把 " + pending.hexes.length + " 格并入 " + pending.existingId + "（并集共 " + hexes.length + " 格）—— head 已前进",
        "ok"
      );
      app.setRegionFocus(pending.existingId);
      await refreshFocusHexes();
      reloadRegionEditHighlight();
    } else {
      setRegionEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    renderRegionEditor();
    return result;
  }

  /** 重名提示取消：清挂起，**零写**。 */
  function cancelNameConflict() {
    host.regionNameConflict = null;
    renderRegionEditor();
    setRegionEditStatus("已取消（未发任何命令）。", "muted");
  }

  /** 新建区域：一条 map.CreateRegion{regionId,name,hexes}。★ 同名先弹二选一（§9.2）。 */
  async function submitCreateRegion() {
    var idNode = app.byId("region-create-id");
    var nameNode = app.byId("region-create-name");
    var id = idNode ? idNode.value.trim() : "";
    var name = nameNode ? nameNode.value.trim() : "";
    var hexes = regionDraftList();
    if (!id) {
      setRegionEditStatus("请填写 regionId（Q3：由调用方指定，可改建议值）。", "warn");
      return null;
    }
    if (!name) {
      setRegionEditStatus("请填写区域名称。", "warn");
      return null;
    }
    return requestCreateRegion(id, name, hexes);
  }

  /** 改已有区域 hex 集合：一条 map.UpdateRegion{regionId,hexes}（meta 编辑器走 T8 的路径，不在此重写）。 */
  async function submitUpdateRegion() {
    if (host.regionEditBusy) {
      return null;
    }
    var id = host.regionFocus;
    if (!id) {
      setRegionEditStatus("先在右栏选一个区域（或在「新建区域」后改）。", "warn");
      return null;
    }
    var hexes = regionDraftList();
    host.regionEditBusy = true;
    setRegionEditStatus("提交 map.UpdateRegion " + id + "（hex 集合 " + hexes.length + " 格）…", "muted");
    var result = await app.writeCommand("map.UpdateRegion", { regionId: id, hexes: hexes });
    host.regionEditBusy = false;
    if (result.ok) {
      setRegionEditStatus("已更新 " + id + " 的 hex 集合（" + hexes.length + " 格）—— head 已前进", "ok");
      host.regionCache = {};
      await refreshFocusHexes();
    } else {
      setRegionEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    renderRegionEditor();
    return result;
  }

  /**
   * ★ M8-R 判据 1：右键拖动套索 ⇒ 一条 map.CreateRegion。hexes = 客户端 flood fill 结果
   * （内部 ∪ 套索墙）；regionId/name 取创建表单（空则用可改的建议值）。**重叠不报错**。
   */
  async function onLassoCommit(hexes) {
    if (app.getState().mode !== "region-edit" || host.regionEditBusy) {
      return null;
    }
    if (!hexes || !hexes.length) {
      setRegionEditStatus("套索为空或不闭合（至少 3 个格），未创建。", "warn");
      return null;
    }
    var idInput = app.byId("region-create-id");
    var nameInput = app.byId("region-create-name");
    var id = idInput ? idInput.value.trim() : "";
    var name = nameInput ? nameInput.value.trim() : "";
    if (!id) {
      id = suggestRegionId();
      if (idInput) {
        idInput.value = id;
      }
    }
    if (!name) {
      name = "新区域";
      if (nameInput) {
        nameInput.value = name;
      }
    }
    // ★ §9.2：同名先弹二选一（不静默新建/合并）；不同名则落一条 CreateRegion。
    return requestCreateRegion(id, name, hexes);
  }

  /** ★ M8-R 判据 3：拖边界小点 ⇒ 一条 map.UpdateRegion，hex 集合即拖动后的焦点集合。 */
  async function onDotDragCommit(hexes) {
    if (app.getState().mode !== "region-edit" || host.regionEditBusy) {
      return null;
    }
    var id = host.regionFocus;
    if (!id) {
      return null;
    }
    if (!hexes || !hexes.length) {
      setRegionEditStatus("该拖动会让 " + id + " 变空，已阻止（未发命令）。", "warn");
      return null;
    }
    host.regionEditBusy = true;
    setRegionEditStatus("小点拖动 ⇒ 提交 map.UpdateRegion " + id + "（" + hexes.length + " 格）…", "muted");
    var result = await app.writeCommand("map.UpdateRegion", { regionId: id, hexes: hexes });
    host.regionEditBusy = false;
    if (result.ok) {
      setRegionEditStatus("已更新 " + id + "（" + hexes.length + " 格）—— head 已前进", "ok");
    } else {
      setRegionEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    host.regionCache = {};
    await refreshFocusHexes();
    reloadRegionEditHighlight();
    renderRegionEditor();
    return result;
  }

  function unionHexes(base, extra) {
    var out = {};
    (base || []).forEach(function (h) {
      out[h.q + "_" + h.r] = { q: h.q, r: h.r };
    });
    (extra || []).forEach(function (h) {
      out[h.q + "_" + h.r] = { q: h.q, r: h.r };
    });
    return Object.keys(out).map(function (key) {
      return out[key];
    });
  }

  function differenceHexes(base, remove) {
    var drop = {};
    (remove || []).forEach(function (h) {
      drop[h.q + "_" + h.r] = true;
    });
    return (base || []).filter(function (h) {
      return !drop[h.q + "_" + h.r];
    });
  }

  /** ★ M8-R 判据 4：合并 = 临时选区 ∪ 焦点区域 ⇒ 一条 map.UpdateRegion{hexes: union}。 */
  async function submitRegionMerge() {
    if (host.regionEditBusy) {
      return null;
    }
    var id = host.regionFocus;
    if (!id) {
      setRegionEditStatus("先在右栏选一个区域。", "warn");
      return null;
    }
    var base = await fetchRegionCached(id);
    var hexes = unionHexes(base.hexes, regionDraftList());
    if (!hexes.length) {
      setRegionEditStatus("并集为空，未发命令。", "warn");
      return null;
    }
    host.regionEditBusy = true;
    setRegionEditStatus("合并 " + id + " ∪ 临时选区（" + hexes.length + " 格）…", "muted");
    var result = await app.writeCommand("map.UpdateRegion", { regionId: id, hexes: hexes });
    host.regionEditBusy = false;
    if (result.ok) {
      host.regionDraft = {};
      active.setDraftHexes([]);
      host.regionCache = {};
      setRegionEditStatus("已合并 " + id + "（" + hexes.length + " 格）—— head 已前进", "ok");
      await refreshFocusHexes();
      reloadRegionEditHighlight();
    } else {
      setRegionEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    renderRegionEditor();
    return result;
  }

  /** ★ M8-R 判据 5：剔除 = 焦点区域 − 临时选区 ⇒ 一条 map.UpdateRegion{hexes: difference}。 */
  async function submitRegionSubtract() {
    if (host.regionEditBusy) {
      return null;
    }
    var id = host.regionFocus;
    if (!id) {
      setRegionEditStatus("先在右栏选一个区域。", "warn");
      return null;
    }
    var base = await fetchRegionCached(id);
    var hexes = differenceHexes(base.hexes, regionDraftList());
    if (!hexes.length) {
      setRegionEditStatus("差集为空（会清空 " + id + "，服务端拒绝空 hexes），未发命令。", "warn");
      return null;
    }
    host.regionEditBusy = true;
    setRegionEditStatus("剔除 " + id + " − 临时选区（余 " + hexes.length + " 格）…", "muted");
    var result = await app.writeCommand("map.UpdateRegion", { regionId: id, hexes: hexes });
    host.regionEditBusy = false;
    if (result.ok) {
      host.regionDraft = {};
      active.setDraftHexes([]);
      host.regionCache = {};
      setRegionEditStatus("已剔除 " + id + "（余 " + hexes.length + " 格）—— head 已前进", "ok");
      await refreshFocusHexes();
      reloadRegionEditHighlight();
    } else {
      setRegionEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    renderRegionEditor();
    return result;
  }

  function armRegionDelete() {
    if (!host.regionFocus) {
      setRegionEditStatus("先在右栏选一个区域。", "warn");
      return;
    }
    host.regionDeleteArmed = true;
    renderRegionEditor();
    setRegionEditStatus("删除不可撤销：点「确认删除」才真正发出 map.DeleteRegion。", "warn");
  }

  function cancelRegionDelete() {
    host.regionDeleteArmed = false;
    renderRegionEditor();
    setRegionEditStatus("已取消删除。", "muted");
  }

  /** 删除区域：只有**二次确认后**才发一条 map.DeleteRegion（未确认前零写）。 */
  async function submitDeleteRegion() {
    var id = host.regionFocus;
    if (!id || host.regionEditBusy || !host.regionDeleteArmed) {
      return null;
    }
    host.regionDeleteArmed = false;
    host.regionEditBusy = true;
    renderRegionEditor();
    setRegionEditStatus("提交 map.DeleteRegion " + id + " …", "muted");
    var result = await app.writeCommand("map.DeleteRegion", { regionId: id });
    host.regionEditBusy = false;
    if (result.ok) {
      setRegionEditStatus("已删除 " + id + " —— head 已前进", "ok");
      host.regionDraft = {};
      active.setDraftHexes([]);
      app.setRegionFocus(null);
    } else {
      setRegionEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    renderRegionEditor();
    return result;
  }

  /**
   * 目标区域变化：把它的 hex 推给"边界小点层"（仅选中区域画点），并刷新 meta 编辑器与淡色高亮。
   * ★ 临时选区（draft）**不**自动载入区域 hex——选区是给"合并/剔除"用的独立草稿，
   *   载入整份区域会让并集恒等于原区域（要整份替换走「把目标区域 hex 载入选区」）。
   */
  function onRegionFocusChanged(focus) {
    host.regionFocus = focus || null;
    host.regionDeleteArmed = false;
    host.regionInfoKey = null;
    renderRegionEditor();
    if (app.getState().mode !== "region-edit") {
      return;
    }
    host.regionDraft = {};
    active.setDraftHexes([]);
    if (!host.regionFocus) {
      active.clearFocusHexes();
      reloadRegionEditHighlight();
      setRegionEditStatus("未选中区域：点「新建区域」或从右栏选一个已有区域。", "muted");
      return;
    }
    refreshFocusHexes().then(function (region) {
      if (!region || host.regionFocus !== focus) {
        return;
      }
      setRegionEditStatus(
        "已选中 " + focus + "（" + (region.hexes || []).length + " 格）：边界小点可拖动增删；右键拖动=套索，Shift+右键拖动=逐格画擦，左键拖动=平移。",
        "muted"
      );
    });
    reloadRegionEditHighlight();
  }

  function wireRegionEditor() {
    var bind = function (id, handler) {
      var node = app.byId(id);
      if (node) {
        node.addEventListener("click", handler);
      }
    };
    bind("region-edit-new", newRegionDraft);
    bind("region-edit-clear", clearRegionDraft);
    bind("region-edit-load", loadFocusIntoDraft);
    bind("region-create-submit", submitCreateRegion);
    bind("region-name-conflict-new", resolveNameConflictCreateNew);
    bind("region-name-conflict-merge", resolveNameConflictMerge);
    bind("region-name-conflict-cancel", cancelNameConflict);
    bind("region-update-submit", submitUpdateRegion);
    bind("region-merge", submitRegionMerge);
    bind("region-exclude", submitRegionSubtract);
    bind("region-delete", armRegionDelete);
    bind("region-delete-yes", submitDeleteRegion);
    bind("region-delete-cancel", cancelRegionDelete);
    var opSelect = app.byId("region-edit-op");
    if (opSelect) {
      opSelect.addEventListener("change", function () {
        host.regionOp = opSelect.value === "remove" ? "remove" : "add";
        active.setBrushOp(host.regionOp);
        renderRegionEditor();
      });
    }
  }

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
    { id: "terrain", label: "地形", tools: ["terrain", "randomize"] },
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
   * 有效工具下「地形」与「连通性」**恰一个**为 true；`randomize` 面板仅圈选随机化时可见。
   * 未知工具 ⇒ 三者全 false（fail-closed，不露任何面板）。
   */
  function mapEditPanelVisibility(tool) {
    var subtool = mapEditSubtoolOf(tool);
    return {
      terrain: subtool === "terrain",
      connectivity: subtool === "connectivity",
      randomize: tool === "randomize",
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

  /** kind 的可见标签：优先用服务端组名，回退内置中文，再回退原串（不静默成空）。 */
  function edgeKindLabel(kind) {
    var groups = host.overviewGroups || [];
    for (var i = 0; i < groups.length; i++) {
      if (groups[i] && groups[i].id === kind) {
        return groups[i].name || kind;
      }
    }
    return kind === "river" ? "河流" : kind === "road" ? "道路" : kind;
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
              return commitRegionPaint(hexes);
            }
            if (host.mapEditTool === "randomize") {
              return commitRandomizeSelection(hexes);
            }
            return commitBrush(hexes);
          }
        : undefined,
      onEdgeCommit: isWorkbench ? commitEdge : undefined,
      onEdgeDeleteCommit: isWorkbench ? commitEdgeDelete : undefined,
      onLassoCommit: isWorkbench ? onLassoCommit : undefined,
      onDotDragCommit: isWorkbench ? onDotDragCommit : undefined,
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
      app.onStateChange(onStateChange);
      onStateChange(app.getState());
      window.SimosMapUnitEditor.wireUnitEditor();
      wireMapEditor();
      wireRegionEditor();
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
    renderEdgeKindOptions: renderEdgeKindOptions,
    mapEditSubtoolOf: mapEditSubtoolOf,
    edgeChainResult: edgeChainResult,
    edgeChainEdges: edgeChainEdges,
    parseEdgeKey: parseEdgeKey,
    edgeHitAtWorldPoint: edgeHitAtWorldPoint,
    setRegisteredEdgeKinds: setRegisteredEdgeKinds,
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
      var subtool = checkedRadioValue("map-edit-subtools", "map-edit-subtool");
      var tool =
        subtool === "connectivity"
          ? checkedRadioValue("edge-kind-select", "map-edit-edge-kind")
          : checkedRadioValue("terrain-tool-select", "map-edit-terrain-tool");
      var edgeControls = app.byId("edge-controls");
      var randomizeControls = app.byId("randomize-controls");
      var seedNode = app.byId("randomize-seed");
      return {
        mode: app.getState().mode,
        paletteKeys: paletteKeys(),
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
        edgeMode: edgeModeValue(),
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
        randomizeSelection: host.randomizeSelection.map(function (hex) {
          return { q: hex.q, r: hex.r };
        }),
      };
    },
    commitPaintForTest: function (hexes, terrain) {
      if (terrain !== undefined) {
        host.brushTerrain = terrain;
      }
      return commitBrush(hexes || []);
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
    regionPaintForTest: function (hexes, op) {
      if (op) {
        host.regionOp = op === "remove" ? "remove" : "add";
        active.setBrushOp(host.regionOp);
      }
      return commitRegionPaint(hexes || []);
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
