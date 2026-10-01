// renderer.js —— 共享六角 Canvas 渲染器工厂（M12 第二波：自 map.js 顶层整体搬出）。
//
// createRenderer 原占 ~1815 行，是 map.js 拆分前最大的一块。本文件只做搬家：
// **函数体与 map.js 原文逐字节相同**（唯一例外见下），它闭包引用的模块级常量/状态/函数
// 由 map.js 暴露的 window.SimosMapCore 提供。
//
// ★ 引入顺序：hexgeom.js → hexcolor.js → regionShape.js → map.js → **renderer.js**。
//   renderer.js 必须排在 map.js 之后（SimosMapCore 由 map.js 建立）。
// ★ 唯一非逐字节处：debug 投影里的 regionNamesEnabled 是 map.js 的可变绑定，改成实时读
//   window.SimosMapCore.regionNamesEnabled（否则取到的会是最初的快照，行为会变）。

(function () {
  "use strict";

  // ── 从兄弟文件 / 宿主取回（本文件在 map.js 之后引入；见宿主页 <script> 顺序）────────
  //   ★ 纯函数来自 hexgeom.js / hexcolor.js / regionShape.js；模块级常量/状态/函数来自 map.js
  //     暴露的 window.SimosMapCore。取回后，下方 createRenderer 的函数体与搬家前逐字节相同。
  var hexGeom = window.SimosHexGeom;
  var hexColor = window.SimosHexColor;
  var regionShape = window.SimosRegionShape;
  var core = window.SimosMapCore;
  // ★ F1：世界视图纯函数（城市 LOD / 国家名 / 图层归一）。两宿主页都在 renderer.js 之前引入；
  //   节点门禁若只加载 renderer 也不炸（下面的降级常量给"全开 + 全城市简单计划"）。
  var worldModel = window.SimosWorldModel || null;

  // hexgeom.js
  var MIN_SCALE = hexGeom.MIN_SCALE;
  var MAX_SCALE = hexGeom.MAX_SCALE;
  var DIR_VECTORS = hexGeom.DIR_VECTORS;
  var hexToPixel = hexGeom.hexToPixel;
  var pixelToHex = hexGeom.pixelToHex;
  var axialNeighbors = hexGeom.axialNeighbors;
  var hexLine = hexGeom.hexLine;
  var worldToScreen = hexGeom.worldToScreen;
  var screenToWorld = hexGeom.screenToWorld;
  var zoomAt = hexGeom.zoomAt;
  var fitView = hexGeom.fitView;
  // ★ 2026-09-24 可用性修复：选中/定位单位时判断"眼见为实"并居中（纯函数在 hexgeom.js）。
  var markerScreenVisible = hexGeom.markerScreenVisible;
  var centerViewOn = hexGeom.centerViewOn;
  // ★ 2026-09-23 同格重叠单位纵向摊开：间距/半径口径来自 hexgeom（与 drawUnits 的圆点半径同源）
  var markerRadius = hexGeom.markerRadius;
  var stackSpacing = hexGeom.stackSpacing;
  var stackOffset = hexGeom.stackOffset;
  // ★ 2026-09-24：标记文字「军队名 × N」的纯函数（不再写 leadId 的短 id）。
  var markerLabel = hexGeom.markerLabel;
  // ★ 2026-09-24 修正 1：标记按**军队**分组（同格、同军队根 ⇒ 一个标记）；纯函数在 hexgeom.js
  //   （两份宿主页都引它 ⇒ map.html 不会因缺 unitTree.js 而少一个函数）。
  var markerGroups = hexGeom.markerGroups;
  // ★ 2026-09-24 交战格的特殊地图显示（判定 / 布局 / 门控；纯函数在 hexgeom.js）。
  var combatHexes = hexGeom.combatHexes;
  var combatLayoutEnabled = hexGeom.combatLayoutEnabled;
  var combatSlot = hexGeom.combatSlot;
  var combatIconOffset = hexGeom.combatIconOffset;
  var combatIconFontSize = hexGeom.combatIconFontSize;
  var combatRowSpacing = hexGeom.combatRowSpacing;
  // hexcolor.js
  var FALLBACK_COLOR = hexColor.FALLBACK_COLOR;
  var REGION_FALLBACK_COLOR = hexColor.REGION_FALLBACK_COLOR;
  var regionColor = hexColor.regionColor;
  var withAlpha = hexColor.withAlpha;
  // regionShape.js
  var regionBoundaryRings = regionShape.regionBoundaryRings;

  // ★ F1：GOV 辖区覆盖层的统一配色（填充 + 精确边界 stroke；选中态优先用 accent 蓝）。
  //   2026-10-01 可见性修正：青绿提亮为 #2ee6c8、填充/线宽同步上调（默认世界缩放下 1.2px 实线几乎不可见）。
  var GOV_JURISDICTION_COLOR = "#2ee6c8";
  var GOV_JURISDICTION_SELECTED_COLOR = "#4ea1ff";
  var GOV_JURISDICTION_FILL_ALPHA = 0.24;
  var GOV_JURISDICTION_SELECTED_FILL_ALPHA = 0.38;
  var GOV_JURISDICTION_STROKE_PX = 2.0;
  var GOV_JURISDICTION_SELECTED_STROKE_PX = 3.0;

  // map.js（window.SimosMapCore）
  var app = core.app;
  var api = core.api;
  var host = core.host;
  var perfConfig = core.perfConfig;
  var BASE_CELL = core.BASE_CELL;
  var ROUTE_OUTLINE_COLOR = core.ROUTE_OUTLINE_COLOR;
  var ROUTE_OUTLINE_WIDTH = core.ROUTE_OUTLINE_WIDTH;
  var ROUTE_BASE_COLOR = core.ROUTE_BASE_COLOR;
  var ROUTE_BASE_WIDTH = core.ROUTE_BASE_WIDTH;
  var ROUTE_REMAINING_COLOR = core.ROUTE_REMAINING_COLOR;
  var ROUTE_REMAINING_WIDTH = core.ROUTE_REMAINING_WIDTH;
  var HIGHLIGHT_ALPHA = core.HIGHLIGHT_ALPHA;
  var REGION_DIM_COLOR = core.REGION_DIM_COLOR;
  var REGION_NAME_MIN_SCALE = core.REGION_NAME_MIN_SCALE;
  var ZOOM_WHEEL = core.ZOOM_WHEEL;
  var OLD_PAGE_CANVAS_HEIGHT = core.OLD_PAGE_CANVAS_HEIGHT;
  var FIT_PAD = core.FIT_PAD;
  var TERRAIN_CACHE_MARGIN = core.TERRAIN_CACHE_MARGIN;
  var ZOOM_SETTLE_MS = core.ZOOM_SETTLE_MS;
  var remainingPath = core.remainingPath;
  var terrainDimAlpha = core.terrainDimAlpha;
  var regionNamesVisible = core.regionNamesVisible;
  var regionNamePlan = core.regionNamePlan;
  var REGION_NAME_VISIBLE_MARGIN = core.REGION_NAME_VISIBLE_MARGIN;
  var renderEdgeKindOptions = core.renderEdgeKindOptions;
  var mapEditSubtoolOf = core.mapEditSubtoolOf;
  var edgeChainResult = core.edgeChainResult;
  var edgeChainEdges = core.edgeChainEdges;
  var parseEdgeKey = core.parseEdgeKey;
  var edgeHitAtWorldPoint = core.edgeHitAtWorldPoint;
  var setRegisteredEdgeKinds = core.setRegisteredEdgeKinds;


  function createRenderer(canvas, options) {
    var opts = options || {};
    var ctx = canvas.getContext("2d");
    var isWorkbench = !!opts.isWorkbench;
    var onSelect = opts.onSelect || function () {};
    // ★ 右键回调（M7b T3）：返回 true = 已消费（preventDefault，抑制原生菜单）；返回 false = 放行。
    var onContextMenu = opts.onContextMenu || function () {
      return false;
    };
    var onPaintCommit = opts.onPaintCommit || function () {};
    // ★ M8-R §七 统一按键模型：左键=平移；右键按模式分派（region-edit=套索 / map-edit=刷地形 /
    //   常规·单位=宿主 contextmenu 的 PlanRoute）；Shift+右键（region-edit）=逐格画擦。
    var onLassoCommit = opts.onLassoCommit || function () {};
    var onDotDragCommit = opts.onDotDragCommit || function () {};
    // ★ M8 T11：连边松手回调（chain = edgeChainResult 的 {edges,ended,nonAdjacent,path}）。
    var onEdgeCommit = opts.onEdgeCommit || function () {};
    // ★ T3：左键删边松手回调（kind + 被删边键列表 ⇒ 宿主合成一条 replace）。
    var onEdgeDeleteCommit = opts.onEdgeDeleteCommit || function () {};

    var cellSize = opts.cellSize || BASE_CELL;
    var view = { scale: 1, tx: 0, ty: 0 };
    var overview = null;
    var blocks = []; // 权威地形块（M9 T13）：{id,terrain,hexCount,boundaries:[环…]}，顶点为「格边长=1」的世界坐标
    var units = []; // 单位全表：{id,name,parent,position}（只读数；标记坐标在 markers 上）
    var unitById = {}; // String(id) → 单位（pickAt 取回 leadId 的名字）
    // ★ 2026-09-24 修正 1：**标记**=按军队分组的绘制单位，{rootId,leadId,at,member,px,py,stackSpacing}。
    //   一个首都格只有 1 个标记（整支军队），不再把根 + 各兵种铺成一长串。
    // ★ 2026-09-24 交战：交战格上的标记额外带 {combat:true, combatCount, combatRowSpacing}，
    //   并被 combatSlot 定位到格心左右两列（见 recomputeWorldPixels）。
    var markers = [];
    // ★ 2026-09-24 交战：**真实记录的**交战格（`{"q_r": {…}}`，来自 `GET /api/sd/combats`）。
    //   这是判定"哪格在交战"的**真值来源**；旧的两条推断（同格多军队 / ENGAGED）在 combatHexes 里兜底。
    var realCombatHexes = {};
    var routes = []; // 在途路线（M7b T2）：{id,movement,path:[{q,r}…]}
    // ★ F1：social 侧城市（`/api/social/cities`）。标记位置与 LOD 在 drawCities/cityMarkerPlanOf 里现算。
    var cities = [];
    var cityById = {}; // String(id) → 城市（搜索定位 / pickAt 的名字）
    // ★ F1：图层开关（集中归一，未知键不打开；见 worldmodel.layerVisibility）。
    var layerState = defaultLayerState();
    // ★ F1：国家着色面（区域详情 hex 集合 → 已解析色 + 已算边界环）；仅工作台开启（旧 /map 保持原渲染行为）。
    var nationRegions = [];
    // ★ F1：GOV 辖区覆盖层（每项 {govId,name,hexes,rings,color}，见 setGovJurisdictions）；仅工作台、图层开启时画。
    var govJurisdictions = [];
    // ★ F2：当前热力层（归一化副本；null = 不画。只影响绘制，不参与取数）。
    var heatmap = null;
    // ★ F1：决策人（军队 rootUnit / GOV 单位 id 匹配 ⇒ 在标记上画金色徽标；决策人图层开关控制）。
    var decisionMakers = [];
    var selectedCity = null;
    var colorByTerrain = {};
    var fallbackWarned = false;
    var selected = null;
    var selectedUnit = null;
    var highlightColorByKey = {};
    var highlightAlphaByKey = {};
    var mode = "view";
    var brushKeys = {};
    var draftKeys = {};
    var brushOp = "add";
    var painting = false;
    // ★ M8 T11：地图编辑的**工具**（terrain / river / road / randomize）。右键按当前工具分派——
    //   与 §七 统一按键模型同构：左键恒为平移，右键的**动作**由工具决定。
    var editTool = "terrain";
    var edgeDrag = null; // {path:[{q,r}…]}：右键拖动连边时的**原始轨迹**（相邻性护栏在 edgeChainResult 里）
    // ★ T3：左键拖动删边（GSimulator 同款）。{kind, deleted:{边键:true}}——松手时合成**一条** replace。
    var edgeDeleteDrag = null;
    var EDGE_HIT_THRESHOLD_PX = 12; // ★ spec §三.3-④ / GSimulator findSegmentAtPixel 的 12px 命中阈值（屏幕像素）

    // ★ M8-R 区域编辑：套索（右拖）+ 焦点区域边界小点（左拖增删格）。
    var lassoKeys = {};
    var lassoOrder = [];
    var lassoActive = false;
    var focusKeys = {};
    var focusColorHex = null;
    var focusBoundary = [];
    var focusBoundaryDirty = true;
    var dotDrag = null;
    var dotDragDirty = false;

    var cssW = 800;
    var cssH = OLD_PAGE_CANVAS_HEIGHT; // 工作台的尺寸在 resize() 里按视口覆盖
    var dpr = 1;

    var terrainCanvas = null; // 地形层离屏位图（perfConfig.terrainCacheEnabled 时启用）
    var terrainCtx = null;
    var terrainCache = null; // {scale, tx, ty}：位图对应的视图；null = 无缓存
    var terrainDirty = true; // 数据/尺寸变 ⇒ 必须重建位图
    var terrainSettleTimer = null;
    var terrainRebuilds = 0; // 地形位图重建次数（断言 pan 不重建 / 数据变必重建）
    var terrainBlits = 0; // 地形位图 blit 次数
    var legendScans = 0; // updateLegend 的 counts 重建次数（断言记忆化）
    var paintedRingCount = 0; // Pass 1 累计追加的环数（丢洞/"逐格"退化时红）
    var pass2Draws = 0; // Pass 2（未合并余量）绘制次数：P6 全部建块 ⇒ 恒 0
    var blockVertexCount = 0; // 全部块环的顶点数（数据变时更新）
    var unmergedCount = 0; // hexCount − Σ 块 hexCount：> 0 ⇒ 服务端块没覆盖全图（缺陷）
    var regionOutlines = []; // {id,color,alpha,rings}：区域边界（精确的逐 hex 外缘轮廓，未简化）
    var outlineVertexCount = 0; // 全部区域边界环的顶点数（精确值；不应被任何简化压低）
    var dimPasses = 0; // ★ U1：地形压暗层绘制次数（区域模式应 > 0，常规应恒 0）
    var regionNameDraws = 0; // ★ U2：区域名实际绘制条数（可断言 0 / >0）
    var nationNameDraws = 0; // ★ F1：世界视图国家名实际绘制条数（与区域名分开计数，互不污染既有断言）

    var rafId = null;
    var dragging = false;
    var dragMoved = false;
    var dragStart = null;
    var viewStart = null;

    function scheduleRender() {
      if (rafId !== null) {
        return;
      }
      rafId = window.requestAnimationFrame(function () {
        rafId = null;
        render();
      });
    }

    /** 图层缺省（worldmodel 缺席时的兜底；正常路径走 worldmodel.layerVisibility）。 */
    function defaultLayerState() {
      if (worldModel && worldModel.DEFAULT_LAYERS) {
        return worldModel.layerVisibility(null);
      }
      return {
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
    }

    function normalizeLayerState(layers) {
      if (worldModel && worldModel.layerVisibility) {
        return worldModel.layerVisibility(layers);
      }
      var out = defaultLayerState();
      if (layers && typeof layers === "object") {
        Object.keys(out).forEach(function (key) {
          if (Object.prototype.hasOwnProperty.call(layers, key)) {
            // 与 worldmodel.layerVisibility 同口径：键在场时只认显式 true，未知值一律关闭（fail-closed）。
            out[key] = layers[key] === true;
          }
        });
      }
      return out;
    }

    /** 当前缩放/图层下的城市标记计划；worldmodel 缺席时降级为"全城市、无 LOD"（fail-open 仅此兜底）。 */
    function cityMarkerPlanOf() {
      if (worldModel && worldModel.cityMarkerPlan) {
        return worldModel.cityMarkerPlan(cities, view.scale, layerState);
      }
      return cities.map(function (city) {
        return {
          kind: "city",
          id: String(city.id),
          name: city.name || "",
          at: { q: city.at.q, r: city.at.r },
          region: city.region,
          tier: city.tier,
          population: city.population,
          category: "unknown",
          capital: false,
          showLabel: false,
          radiusFactor: 0.09,
          rank: 0,
        };
      });
    }

    // ★ F1 可见性：各类别的**屏幕空间半径下限**（CSS px）。full map fit 时 scale≈0.06，
    //   纯世界半径（cellSize×factor≈3~9）只有 0.18~0.55 px ⇒ 标记不可见。
    var CITY_MIN_SCREEN_PX = {
      capital: 4.5,
      major: 4,
      city: 3.5,
      town: 3,
      marketTown: 2.5,
      unknown: 2.5,
    };

    /** 屏幕尺寸 → 世界尺寸；view.scale 非正/非有限 ⇒ 0（fail-closed，不除 0 / 不放大）。 */
    function screenSizeToWorld(px) {
      return typeof view.scale === "number" && isFinite(view.scale) && view.scale > 0
        ? px / view.scale
        : 0;
    }

    /**
     * 城市标记世界半径 = max(世界半径, 类别屏幕下限 / view.scale)。
     * fit 全图（scale≈0.06）时保证 capital≥4.5px、major≥4px…；scale 非正/非有限时退回世界半径。
     */
    function cityRadiusOf(item) {
      var factor = item && typeof item.radiusFactor === "number" ? item.radiusFactor : 0.09;
      var worldRadius = Math.max(3, cellSize * factor);
      var minScreenPx = CITY_MIN_SCREEN_PX[item && item.category] || 2.5;
      return Math.max(worldRadius, screenSizeToWorld(minScreenPx));
    }

    function rebuildColors() {
      colorByTerrain = {};
      (overview.terrainTypes || []).forEach(function (type) {
        if (type && type.key) {
          colorByTerrain[type.key] = type.color;
        }
      });
    }

    /** 后端权威色；词表外 ⇒ 唯一兜底色 + 控制台记一次（不静默回退成地形色）。 */
    function terrainColor(terrain) {
      var color = colorByTerrain[terrain];
      if (color) {
        return color;
      }
      if (!fallbackWarned) {
        fallbackWarned = true;
        window.console.warn(
          "[SimosMap] 地形「" + terrain + "」不在后端词表中，使用兜底色 " + FALLBACK_COLOR
        );
      }
      return FALLBACK_COLOR;
    }

    function worldBounds() {
      if (!blocks.length) {
        return null;
      }
      var minX = Infinity;
      var minY = Infinity;
      var maxX = -Infinity;
      var maxY = -Infinity;
      blocks.forEach(function (block) {
        (block.boundaries || []).forEach(function (ring) {
          ring.forEach(function (point) {
            var x = point.x * cellSize;
            var y = point.y * cellSize;
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
          });
        });
      });
      return { minX: minX, minY: minY, maxX: maxX, maxY: maxY };
    }

    /**
     * ★ F1 修复：该格是否有**真实交战记录**。`setCombats` 收成 `{"q_r": …}` 对象表，`combatHexes`
     * 的第二参也允许数组形状；这里两种都认（只判 key，不编坐标）。
     */
    function hasRealCombatAt(key, trueHexes) {
      if (!trueHexes) {
        return false;
      }
      if (Array.isArray(trueHexes)) {
        for (var i = 0; i < trueHexes.length; i += 1) {
          var entry = trueHexes[i];
          if (!entry) {
            continue;
          }
          var at = entry.hex || entry.at || (entry.q !== undefined && entry.r !== undefined ? entry : null);
          if (at && at.q !== undefined && at.r !== undefined && at.q + "_" + at.r === key) {
            return true;
          }
        }
        return false;
      }
      return Object.prototype.hasOwnProperty.call(trueHexes, key);
    }

    /**
     * 预计算**标记**的世界像素位置（px/py）。
     * 1) 先由 `markerGroups(units)`（hexgeom 纯函数）把单位**按格、再按军队根**分组 ⇒ 每组一个标记；
     *    ——首都格（根 + 各兵种同格）只出 1 个标记（代表 = 根），分遣队单独一格不会被藏掉。
     * 2) **交战格**（`combatHexes`：同格 ≥2 个不同 rootId，或任一组 engaged）上的标记改用
     *    `combatSlot` 定位 —— 交战方各列两边、中间留给 ⚔；**代替**原来的纵向摊开。
     *    门控 `combatLayoutEnabled`：屏幕格高不够时**不**启用特殊布局，退回下面的纵向摊开/重叠。
     * 3) 非交战格：同格不同军队**纵向摊开**（stackOffset；格太小则保持重叠）。
     *    摊开间距记在 `marker.stackSpacing`（0=未摊开），供 pickAt 收缩命中半径。
     * ★ F1 修复（用户实测"同格军队、GOV 重合"）：
     *    · `combatHexes` 的旧口径把 GOV 与军队同格（≥2 rootId）误判成交战 ⇒ 调用后**后过滤**：
     *      真交战记录优先；否则只统计**非 GOV** 标记的不同 rootId（≥2 或有非 GOV `engaged`）才保留。
     *    · 非交战布局里，该格若有**可见城市标记**（`cityMarkerPlanOf()`，与绘制/点选同一份计划），
     *      整组单位沿 y 向下让开一段**按半径实算**的距离（城市半径 + 单位半径 + 屏幕 4px 间隙 +
     *      摊开总跨度的上半跨度）⇒ 最上面的单位顶边也在城市下边缘之下，不再压住城市星标。
     * ★ 只改 markers 的 px/py ⇒ pickAt / drawUnits 自动跟随（不另造一份坐标）。
     */
    function recomputeWorldPixels() {
      markers = markerGroups(units);
      // ★ 2026-09-24 交战：真实交战格 ∪ 旧两条推断（第二参为真值来源；无记录时退回旧口径）。
      var combat = combatHexes(markers, realCombatHexes); // "q_r" → 交战方数（仅交战格有键）
      var groups = {}; // "q_r" → 该格的全部**标记**（保持首次出现顺序 ⇒ index 稳定）
      var keys = [];
      markers.forEach(function (m) {
        var key = m.at.q + "_" + m.at.r;
        if (!groups[key]) {
          groups[key] = [];
          keys.push(key);
        }
        groups[key].push(m);
      });
      // ★ F1 修复：GOV 不是交战方 —— 后过滤 combatHexes 的旧口径推断结果。
      //   真记录格无条件保留；其余格只在**非 GOV 标记**里仍有 ≥2 支不同 rootId、或有非 GOV engaged 时才保留。
      Object.keys(combat).forEach(function (key) {
        if (hasRealCombatAt(key, realCombatHexes)) {
          return; // 记录在案的格必须保留（由 combatHexes 第二参保证）
        }
        var list = groups[key] || [];
        var nonGovRoots = {};
        var nonGovRootCount = 0;
        var nonGovEngaged = false;
        list.forEach(function (m) {
          if (markerIsGov(m)) {
            return; // GOV 标记不算交战方（哪怕它是某格唯一的"第二方"）
          }
          var rootId = String(m.rootId);
          if (!Object.prototype.hasOwnProperty.call(nonGovRoots, rootId)) {
            nonGovRoots[rootId] = true;
            nonGovRootCount += 1;
          }
          if (m.engaged === true) {
            nonGovEngaged = true;
          }
        });
        if (nonGovRootCount < 2 && !nonGovEngaged) {
          delete combat[key]; // GOV 单独 / GOV+army 但军队未交战 ⇒ 不是交战格
        }
      });
      // ★ 摊开/交战布局的门控都吃**屏幕上**的格高（cellSize 是世界单位且在**工作台恒定**，
      //   随缩放变的是 view.scale）⇒ 必须相乘，否则缩放永远不会改变"摊不摊开/交不交战布局"。
      var screenCell = cellSize * view.scale;
      var combatOn = combatLayoutEnabled(cellSize, screenCell);
      // ★ F1 修复：只认**当前可见**的城市标记（来自 cityMarkerPlanOf，与 drawCities/pickAt 同源）。
      //   值从 true 改成**城市 item 本身**：让位距离必须算城市半径（类别/radiusFactor），不能再用固定 clearance。
      var cityByKey = {};
      cityMarkerPlanOf().forEach(function (city) {
        cityByKey[city.at.q + "_" + city.at.r] = city;
      });
      keys.forEach(function (key) {
        var list = groups[key];
        var parties = combat[key] || 0;
        var useCombat = parties > 0 && combatOn;
        // ★ 交战布局：交战方各列两边（顺序对半切，无阵营模型），中间格心留给 ⚔。
        if (useCombat) {
          var rowSpacing = combatRowSpacing(cellSize);
          list.forEach(function (m, index) {
            var p = hexToPixel(m.at.q, m.at.r, cellSize);
            var off = combatSlot(index, list.length, cellSize);
            m.px = p.x + off.x;
            m.py = p.y + off.y;
            m.stackSpacing = 0; // 横向布局，不再纵向摊开
            m.combat = true;
            m.combatCount = list.length;
            m.combatRowSpacing = rowSpacing; // pickAt 用它收窄命中（同一口径，不另造）
          });
          return;
        }
        // ★ 普通布局：同格多军队纵向摊开（格太小则重叠）；该格有可见城市 ⇒ 整组再向下让开城市标记。
        //   让位距离按真实半径实算（见上方函数注释）：
        //   最上面的单位中心 = cityClearance − halfSpan ⇒ 顶边 = cityRadius + gapWorld，
        //   正好落在城市下边缘之下至少 4 CSS px（gapWorld = 4 / view.scale；scale 异常时退化为 4 世界单位）。
        var cityItem = cityByKey[key] || null;
        var cityRadius = cityItem ? cityRadiusOf(cityItem) : 0;
        var unitRadius = Math.max(markerRadius(cellSize), screenSizeToWorld(3.5));
        var spacing = stackSpacing(list.length, cellSize, screenCell);
        var halfSpan = (spacing * (list.length - 1)) / 2;
        var gapWorld =
          view.scale > 0 && isFinite(view.scale) ? 4 / view.scale : 4;
        var cityClearance = cityItem
          ? cityRadius + unitRadius + gapWorld + halfSpan
          : 0;
        list.forEach(function (m, index) {
          var p = hexToPixel(m.at.q, m.at.r, cellSize);
          m.px = p.x;
          m.py = p.y + cityClearance + stackOffset(index, list.length, cellSize, screenCell);
          m.stackSpacing = spacing;
          m.combat = false;
          m.combatCount = 0;
          m.combatRowSpacing = 0;
        });
      });
    }

    function countBlockVertices() {
      var total = 0;
      blocks.forEach(function (block) {
        (block.boundaries || []).forEach(function (ring) {
          total += ring.length;
        });
      });
      return total;
    }

    function blockRingCounts() {
      var out = {};
      blocks.forEach(function (block) {
        out[block.id] = (block.boundaries || []).length;
      });
      return out;
    }

    function setData(body) {
      overview = body;
      // ★ T3：连通性候选 kind 与全图边表都来自 overview（服务端权威，不硬编码）。
      host.overviewEdges = Array.isArray(body.edges) ? body.edges : [];
      host.overviewGroups = Array.isArray(body.pathwayGroups) ? body.pathwayGroups : [];
      setRegisteredEdgeKinds(
        Array.isArray(body.pathwayGroups)
          ? body.pathwayGroups.map(function (group) {
              return group && group.id;
            })
          : null
      );
      renderEdgeKindOptions();
      blocks = (body.blocks || []).map(function (b) {
        return {
          id: b.id,
          terrain: b.terrain,
          hexCount: b.hexCount || 0,
          boundaries: b.boundaries || [],
        };
      });
      blockVertexCount = countBlockVertices();
      var covered = 0;
      blocks.forEach(function (b) {
        covered += b.hexCount;
      });
      unmergedCount = Math.max(0, (body.hexCount || 0) - covered);
      if (unmergedCount > 0) {
        // ★ P6 全部建块 ⇒ 余量必须为 0；不为 0 说明服务端块没覆盖全图（缺陷，不静默）。
        window.console.warn(
          "[SimosMap] 块未覆盖全图：hexCount=" +
            body.hexCount +
            "，块内合计=" +
            covered +
            "，余量=" +
            unmergedCount
        );
      }
      rebuildColors();
      updateLegend();
      terrainDirty = true;
      scheduleRender();
    }

    function setUnits(list) {
      routes = (list || [])
        .filter(function (u) {
          return u && u.movement && u.movement.route && (u.movement.route.path || []).length >= 2;
        })
        .map(function (u) {
          return {
            id: u.id,
            movement: u.movement,
            path: u.movement.route.path.map(function (h) {
              return { q: h.q, r: h.r };
            }),
          };
        });
      units = (list || [])
        .filter(function (u) {
          return u && u.position;
        })
        .map(function (u) {
          return {
            id: u.id,
            name: u.name,
            // ★ 2026-09-24 修正 1：必须保留 parent —— markerGroups 靠它判"军队根"（与 buildTree 同口径）。
            parent: u.parent === undefined ? null : u.parent,
            position: u.position,
            // ★ F1：保留服务端已发出的编制/身份/状态读数（markerGroups 只读 id/parent/position/status，
            //   多带字段不改变它的 pure 行为；module/jurisdiction 等保持"键缺席"语义，不拿 null 冒充）。
            member: u.member,
            status: u.status === undefined ? null : u.status,
            module: u.module,
            jurisdiction: u.jurisdiction,
            attached: u.attached,
            formationRootId: u.formationRootId,
            formationSize: u.formationSize,
            formationSpeed: u.formationSpeed,
            combat: u.combat,
          };
        });
      unitById = {};
      units.forEach(function (u) {
        if (u.id !== null && u.id !== undefined) {
          unitById[String(u.id)] = u;
        }
      });
      // ★ 2026-09-23：装载即按当前 cellSize 摊开（此后只在 setCellSize 时才算）；修正 1 起摊开对象=军队组。
      recomputeWorldPixels();
      if (selectedUnit) {
        selected = positionOf(selectedUnit);
      }
      updateLegend();
      scheduleRender();
    }

    /**
     * ★ 2026-09-24 交战：装载 **真实交战记录**（`GET /api/sd/combats` 的 `combats` 数组），翻成
     * `combatHexes` 的第二参 `{"q_r": {combatId,name,stage,stageName,outcome,participants}}`。
     *
     * <p>★ **记录在案的格必须画成交战格**（哪怕那格此刻一个单位标记都没有）——由 `combatHexes` 的第二参
     * 保证；这里只做形状转换，缺 `hex` 的条目跳过（不编坐标）。与 `setUnits` 同款：装载即重算 + 重绘。
     */
    function setCombats(list) {
      var table = {};
      (Array.isArray(list) ? list : []).forEach(function (c) {
        if (!c || !c.hex || c.hex.q === undefined || c.hex.r === undefined) {
          return;
        }
        table[c.hex.q + "_" + c.hex.r] = {
          combatId: c.combatId,
          name: c.name,
          stage: c.currentStage,
          stageName: c.currentStageName,
          outcome: c.selectedOutcome,
          participants: c.participants || [],
        };
      });
      realCombatHexes = table;
      recomputeWorldPixels();
      scheduleRender();
    }

    /** ★ F1：装载社交城市列表；只做形状过滤（缺 id/at 的条目跳过，不编坐标），装载即重绘。 */
    function setCities(list) {
      cities = (Array.isArray(list) ? list : [])
        .filter(function (city) {
          return (
            city &&
            city.id !== null &&
            city.id !== undefined &&
            city.at &&
            typeof city.at.q === "number" &&
            typeof city.at.r === "number"
          );
        })
        .map(function (city) {
          return {
            id: String(city.id),
            name: city.name === undefined ? "" : city.name,
            at: { q: city.at.q, r: city.at.r },
            region: city.region === undefined ? null : city.region,
            tier: city.tier === undefined ? null : city.tier,
            population: city.population,
            props: city.props,
          };
        });
      cityById = {};
      cities.forEach(function (city) {
        cityById[city.id] = city;
      });
      if (selectedCity && !cityById[selectedCity]) {
        selectedCity = null;
      }
      updateLegend(); // 图例含城市数：装载城市后同步刷新（overview 未载入时 updateLegend 自己返回）。
      // ★ F1 修复：城市标记计划参与"单位在可见城市格向下让位"的坐标重算 ⇒ 装载后立即重算，不等下一次缩放。
      recomputeWorldPixels();
      scheduleRender();
    }

    /**
     * ★ F1：国家着色面（工作台）。每项 = `/api/map/region/{id}` 的体（含 `hexes` + `meta.color`）；
     * 边界环只算一次（精确 RegionBoundary 环），颜色走 hexcolor 的同一套解析（非法/缺失回兜底色）。
     */
    function setNationRegions(list) {
      nationRegions = (Array.isArray(list) ? list : [])
        .filter(function (region) {
          return (
            region &&
            region.id !== null &&
            region.id !== undefined &&
            Array.isArray(region.hexes) &&
            region.hexes.length > 0
          );
        })
        .map(function (region) {
          return {
            id: String(region.id),
            name: region.name === undefined ? null : region.name,
            color: regionColor(region.meta),
            rings: regionBoundaryRings(region.hexes),
          };
        });
      scheduleRender();
    }

    /**
     * ★ F1：GOV 辖区覆盖层（工作台专用）。每项 = `{govId,name,hexes,rings,color}`：
     * · hexes 先过滤成有限 `q/r`、规整成 `{q,r}`，并在**同一 GOV 的辖区并集内**按 `q_r` 去重；
     * · 边界环用现有 `regionBoundaryRings(hexes)` 对去重后的完整并集**一次性**精确计算（不简化、不逐格）；
     * · 颜色统一 {@link GOV_JURISDICTION_COLOR}；空数组 = 清空（切 target / 失败时必须显式推空）。
     *
     * <p>非法项（无 govId / hexes 不是数组 / 过滤后无有效 hex）整项丢弃，不拿 0 或占位坐标冒充。
     */
    function setGovJurisdictions(list) {
      govJurisdictions = (Array.isArray(list) ? list : [])
        .filter(function (item) {
          return (
            item &&
            item.govId !== null &&
            item.govId !== undefined &&
            Array.isArray(item.hexes)
          );
        })
        .map(function (item) {
          var seen = {};
          var hexes = [];
          item.hexes.forEach(function (hex) {
            if (
              !hex ||
              typeof hex.q !== "number" ||
              typeof hex.r !== "number" ||
              !isFinite(hex.q) ||
              !isFinite(hex.r)
            ) {
              return;
            }
            var key = hex.q + "_" + hex.r;
            if (Object.prototype.hasOwnProperty.call(seen, key)) {
              return;
            }
            seen[key] = true;
            hexes.push({ q: hex.q, r: hex.r });
          });
          if (!hexes.length) {
            return null;
          }
          return {
            govId: String(item.govId),
            name: item.name === null || item.name === undefined ? "" : String(item.name),
            hexes: hexes,
            rings: regionBoundaryRings(hexes),
            color: GOV_JURISDICTION_COLOR,
          };
        })
        .filter(function (item) {
          return !!item;
        });
      scheduleRender();
    }

    /**
     * ★ F2：装载热力层（`plan = {metric,label,unit,scope,tick,cells,scale,opacity,unavailable}`）。
     *
     * <p>空 plan / 无 metric / 无 cells ⇒ `heatmap = null`（清层）。否则存归一化副本：非法 `q/r/value` 格
     * 丢弃（不画错格、不补 0）；cells 无 `color` 时用 `plan.scale.colorOf(value)` 补齐，仍无色的格丢弃；
     * `opacity` 夹到 [0,1]（非有限 ⇒ 0.55）。装载只改本层状态并 `scheduleRender()`，不触发任何取数。
     */
    function setHeatmap(plan) {
      if (!plan || typeof plan !== "object") {
        heatmap = null;
        scheduleRender();
        return;
      }
      var metric =
        plan.metric === null || plan.metric === undefined ? "" : String(plan.metric);
      var cells = Array.isArray(plan.cells) ? plan.cells : null;
      if (metric === "" || !cells || !cells.length) {
        heatmap = null;
        scheduleRender();
        return;
      }
      var opacity =
        typeof plan.opacity === "number" && isFinite(plan.opacity) ? plan.opacity : 0.55;
      if (opacity < 0) {
        opacity = 0;
      } else if (opacity > 1) {
        opacity = 1;
      }
      var colorOf =
        plan.scale && typeof plan.scale.colorOf === "function" ? plan.scale.colorOf : null;
      var normalized = [];
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
          return; // 无 color 的格跳过（不猜色、不拿黑块冒充）。
        }
        normalized.push({ q: cell.q, r: cell.r, value: cell.value, color: color });
      });
      if (!normalized.length) {
        heatmap = null;
        scheduleRender();
        return;
      }
      heatmap = {
        metric: metric,
        label: plan.label === undefined ? null : plan.label,
        unit: plan.unit === undefined ? null : plan.unit,
        scope: plan.scope === undefined ? null : plan.scope,
        tick: plan.tick === undefined ? null : plan.tick,
        opacity: opacity,
        unavailable: plan.unavailable === undefined ? null : plan.unavailable,
        cells: normalized,
      };
      scheduleRender();
    }

    /** ★ F1：决策人列表（只取匹配用的 id/affiliation）；徽标属绘制层，装载即重绘。 */
    function setDecisionMakers(list) {
      decisionMakers = (Array.isArray(list) ? list : [])
        .filter(function (maker) {
          return maker && maker.id !== null && maker.id !== undefined;
        })
        .map(function (maker) {
          return { id: String(maker.id), affiliation: maker.affiliation || {} };
        });
      scheduleRender();
    }

    /** ★ F1：图层开关（未知键不打开；只影响绘制，不触发任何取数）。 */
    function setLayerState(layers) {
      layerState = normalizeLayerState(layers);
      // ★ F1 修复：城市图层开关会改变"可见城市标记"集合（单位让位判据）⇒ 单位坐标必须跟着重算。
      recomputeWorldPixels();
      scheduleRender();
    }

    /** ★ F1：城市当前所在格；未载入 ⇒ null（搜索定位 / 选中态共用）。 */
    function cityPositionOf(id) {
      var city = cityById[String(id)];
      return city ? { q: city.at.q, r: city.at.r } : null;
    }

    /** ★ F1：选中城市的绘制高亮（null 清空）。 */
    function setSelectedCity(id) {
      selectedCity = id === null || id === undefined || id === "" ? null : String(id);
      scheduleRender();
    }

    /** 单位当前渲染位置（M7 T7 路线锚点）；未载入 ⇒ null。 */
    function positionOf(id) {
      for (var i = 0; i < units.length; i++) {
        if (units[i].id === id) {
          return { q: units[i].position.q, r: units[i].position.r };
        }
      }
      return null;
    }

    /**
     * 单位在头时刻的**父 id**（"加入编队"按钮的父来源；与 buildTree / markerGroups 同口径，直接读服务端给的 `parent` 原值）。
     * 三种返回值语义分明（调用方据此决定按钮态）：
     *   · 字符串 ⇒ 有父（可用作 unit.AttachUnit 的 parent）；
     *   · `null` ⇒ **已知它是根**（无父）⇒ "加入编队"无父可加入，按钮应禁用；
     *   · `undefined` ⇒ 该单位尚未载入（列表还没回来 / 它无有效位置被过滤掉）⇒ **不据此禁用**（否则会把"未知"误判成"根"）。
     */
    function parentOf(id) {
      if (id === null || id === undefined) {
        return undefined;
      }
      var unit = unitById[String(id)];
      if (!unit) {
        return undefined;
      }
      return unit.parent === undefined ? null : unit.parent;
    }

    /**
     * 编制视图（**编制 v2 / 2026-09-24**）：`{attached, rootId, size, speed}`；未载入 ⇒ `undefined`。
     *
     * ★ 服务端已把三件事算好（`ApiViews.unit` 与 MCP 读口同源）：`attached`（我是不是跟别人一起走）、
     *   `formationRootId`（这一支的顶层 —— 该对它下令移动）、`formationSize`/`formationSpeed`（这一支多大、一起走多快）。
     *   前端不重算这些（重算就是第二份真相）。
     */
    function formationOf(id) {
      if (id === null || id === undefined) {
        return undefined;
      }
      var unit = unitById[String(id)];
      if (!unit) {
        return undefined;
      }
      return {
        attached: unit.attached === true,
        rootId: unit.formationRootId === undefined ? null : unit.formationRootId,
        size: unit.formationSize === undefined ? 1 : unit.formationSize,
        speed: unit.formationSpeed === undefined ? unit.speed : unit.formationSpeed,
      };
    }

    function setCellSize(size) {
      cellSize = size;
      recomputeWorldPixels();
      terrainDirty = true;
      scheduleRender();
    }

    function setSelected(sel) {
      selected = sel || null;
      scheduleRender();
    }

    /** 选中单位高亮（M7 T7）：在单位圆点外再描一圈，与 hex 选中环并存。 */
    function setSelectedUnit(id) {
      selectedUnit = id || null;
      scheduleRender();
    }

    /**
     * 高亮集合：entries = `[{key,color}…]`（T6 起带区域色）。同一个 hex 归属多个高亮区域时
     * **先者胜**（调用方按 id 升序给，重叠归属因此可复现）；色值一律是已解析的 `#RRGGBB`。
     */
    function setHighlightHexes(entries) {
      highlightColorByKey = {};
      highlightAlphaByKey = {};
      (entries || []).forEach(function (entry) {
        if (!entry) {
          return;
        }
        var key = typeof entry === "string" ? entry : entry.key;
        if (!key || Object.prototype.hasOwnProperty.call(highlightColorByKey, key)) {
          return;
        }
        highlightColorByKey[key] = typeof entry === "string" ? null : entry.color || null;
        highlightAlphaByKey[key] =
          typeof entry === "string" || entry.alpha === undefined ? null : entry.alpha;
      });
      scheduleRender();
    }

    function setMode(next) {
      mode = next || "view";
      updateCursor();
      scheduleRender();
    }

    /** 设置地图编辑的当前工具（未知值 ⇒ 回到 terrain）；只影响右键分派，左键恒为平移。 */
    function setEditTool(next) {
      // ★ T3：合法 = 地形组 + 已注册连通性组（自定义 kind 也算）；其它落到 terrain（fail-closed）。
      editTool = mapEditSubtoolOf(next) ? next : "terrain";
      edgeDrag = null;
      scheduleRender();
    }

    function computeFit() {
      return fitView(worldBounds(), cssW, cssH, FIT_PAD);
    }

    function fit() {
      view = computeFit();
      recomputeWorldPixels(); // 摊开门控吃 view.scale，改缩放即须重算 px/py
      updateZoomUi();
      scheduleRender();
    }

    // ★ 2026-09-24 可用性修复：单位标记"已可见"的边界余量（CSS px）。点须距四边 ≥ 此值才算看得舒服；
    //   贴边/出界 ⇒ 视为不可见并把它居中。取 24 ≈ 一枚标记在 UNIT_VISIBLE_MIN_SCALE 下的屏幕直径
    //   （2×10.2×0.75≈15.3）再留些余量。
    var MARKER_VISIBLE_MARGIN = 24;

    /**
     * ★ 2026-09-24 可用性修复（用户："我没找到单位在哪"）：选中/定位一个单位时**保证它可见**。
     *
     * <p>默认视图是"fit 整个世界"（59223 格）⇒ `view.scale` 极小 ⇒ 标记半径（世界约 10.2）在屏幕上是
     * **亚像素**，肉眼看不见，也没有"跳到某单位"的入口。本方法把该格居中并抬到可用缩放。
     *
     * <p>**短路**（`force` 为假时）：标记已可见（{@link markerScreenVisible}，留 {@link MARKER_VISIBLE_MARGIN}）
     * 且 `view.scale ≥ minScale` ⇒ **什么都不做、返回 false**。这是为了"在地图上点一个本来就在眼前的
     * 标记 ⇒ 视图纹丝不动"（否则会把用户拽一下）。**出界**或**缩放过小** ⇒ 才
     * `view = centerViewOn(该格世界像素, 视口CSS尺寸, max(view.scale, minScale))`。
     *
     * <p>`force = true`（显式"定位到该军队"按钮）⇒ 跳过短路，无条件居中（呼应明确动作）。
     *
     * @param q,r     该单位所在格（轴向坐标）
     * @param minScale 抬到的下限 `view.scale`；非有限按 0（即只居中、不放大）
     * @param force    是否强制居中（无视"已可见"）
     * @return boolean 是否真的改了视图
     */
    function ensureUnitVisible(q, r, minScale, force) {
      var world = hexToPixel(q, r, cellSize);
      var viewport = { width: cssW, height: cssH };
      var wanted = typeof minScale === "number" && isFinite(minScale) ? minScale : 0;
      if (!force) {
        var screen = worldToScreen(world, view);
        if (view.scale >= wanted && markerScreenVisible(screen, viewport, MARKER_VISIBLE_MARGIN)) {
          return false; // 已可见且缩放够 ⇒ 不碰视图（地图点选已可见标记不发生任何位移）
        }
      }
      // scale 用 max(现有, 下限)：已放得更大就保持，不缩小。
      view = centerViewOn(world, viewport, Math.max(view.scale, wanted));
      recomputeWorldPixels(); // 摊开门控吃 view.scale，改缩放即须重算 px/py
      updateZoomUi();
      scheduleRender();
      return true;
    }

    /**
     * ★ F1：城市定位（搜索"定位"入口）。语义与 {@link #ensureUnitVisible} 逐条相同（已可见且缩放够 ⇒
     * 短路不碰视图；否则居中并把 scale 抬到 max(现有, minScale)），只是锚点换成城市所在格。
     */
    function ensureCityVisible(q, r, minScale, force) {
      var world = hexToPixel(q, r, cellSize);
      var viewport = { width: cssW, height: cssH };
      var wanted = typeof minScale === "number" && isFinite(minScale) ? minScale : 0;
      if (!force) {
        var screen = worldToScreen(world, view);
        if (view.scale >= wanted && markerScreenVisible(screen, viewport, MARKER_VISIBLE_MARGIN)) {
          return false;
        }
      }
      view = centerViewOn(world, viewport, Math.max(view.scale, wanted));
      recomputeWorldPixels();
      updateZoomUi();
      scheduleRender();
      return true;
    }

    function resize() {
      var host = canvas.parentElement;
      if (!host) {
        return;
      }
      var w;
      var h;
      if (isWorkbench) {
        // ★ M7g T1：底图铺满整个视口——尺寸取视口，不再取父容器宽度 + 固定高度。
        w = Math.max(320, Math.round(window.innerWidth || document.documentElement.clientWidth || 1024));
        h = Math.max(240, Math.round(window.innerHeight || document.documentElement.clientHeight || 768));
      } else {
        var style = window.getComputedStyle(host);
        var padX = (parseFloat(style.paddingLeft) || 0) + (parseFloat(style.paddingRight) || 0);
        w = Math.max(320, Math.round(host.clientWidth - padX));
        h = OLD_PAGE_CANVAS_HEIGHT;
      }
      dpr = window.devicePixelRatio || 1;
      cssW = w;
      cssH = h;
      canvas.style.width = w + "px";
      canvas.style.height = h + "px";
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
      if (perfConfig.terrainCacheEnabled) {
        ensureTerrainCanvas();
        terrainDirty = true;
      }
      scheduleRender();
    }

    function addHexPath(targetCtx, cx, cy, size) {
      for (var i = 0; i < 6; i++) {
        var a = (Math.PI / 180) * (60 * i - 30);
        var x = cx + size * Math.cos(a);
        var y = cy + size * Math.sin(a);
        if (i === 0) {
          targetCtx.moveTo(x, y);
        } else {
          targetCtx.lineTo(x, y);
        }
      }
      targetCtx.closePath();
    }

    function strokePolyline(points, color, width) {
      if (points.length < 2) {
        return;
      }
      ctx.beginPath();
      ctx.moveTo(points[0].x, points[0].y);
      for (var i = 1; i < points.length; i++) {
        ctx.lineTo(points[i].x, points[i].y);
      }
      ctx.strokeStyle = color;
      ctx.lineWidth = width / view.scale;
      ctx.lineJoin = "round";
      ctx.lineCap = "round";
      ctx.stroke();
    }

    /** 在途路线折线（M7b T2；M7e T1 加深色外描边）：描边 + 整条实色 + 未走完亮色；无路线时不画。 */
    function drawRoutes() {
      if (!layerState.routes) {
        return; // ★ F1：路线图层关闭 ⇒ 只影响绘制，不碰数据。
      }
      routes.forEach(function (route) {
        var points = route.path.map(function (h) {
          return hexToPixel(h.q, h.r, cellSize);
        });
        strokePolyline(points, ROUTE_OUTLINE_COLOR, ROUTE_OUTLINE_WIDTH);
        strokePolyline(points, ROUTE_BASE_COLOR, ROUTE_BASE_WIDTH);
        var remaining = remainingPath(route.path, route.movement.currentHex, route.movement.status);
        strokePolyline(
          remaining.map(function (h) {
            return hexToPixel(h.q, h.r, cellSize);
          }),
          ROUTE_REMAINING_COLOR,
          ROUTE_REMAINING_WIDTH
        );
      });
    }

    /**
     * 连通性边（T3）：按组别色画**半格线**（格心 → 两端格心的中点），与 GSimulator 的渲染同型——用户能**看见**
     * 自己画/要删的边（否则 12px 命中与左键删边是"看不见的元素"）。组色取自 overview.pathwayGroups。
     */
    function drawEdges() {
      var edges = (overview && overview.edges) || [];
      if (!edges.length) {
        return;
      }
      var byKind = {};
      ((overview && overview.pathwayGroups) || []).forEach(function (group) {
        if (group && group.id) {
          byKind[group.id] = { color: group.color, visible: group.visible !== false };
        }
      });
      var width = Math.max(2, cellSize * 0.12);
      ctx.lineCap = "round";
      edges.forEach(function (view) {
        var pair = parseEdgeKey(view.edge);
        if (!pair) {
          return;
        }
        var a = hexToPixel(pair.a.q, pair.a.r, cellSize);
        var b = hexToPixel(pair.b.q, pair.b.r, cellSize);
        var mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
        (view.pathways || []).forEach(function (kind) {
          var meta = byKind[kind];
          if (meta && meta.visible === false) {
            return;
          }
          ctx.strokeStyle = (meta && meta.color) || "#8B7355";
          ctx.lineWidth = width;
          ctx.beginPath();
          ctx.moveTo(a.x, a.y);
          ctx.lineTo(mid.x, mid.y);
          ctx.moveTo(b.x, b.y);
          ctx.lineTo(mid.x, mid.y);
          ctx.stroke();
        });
      });
    }

    /** 五角星路径（首都 / MajorCity 星标）。 */
    function traceStar(cx, cy, outer, inner) {
      ctx.beginPath();
      for (var i = 0; i < 10; i += 1) {
        var angle = -Math.PI / 2 + (Math.PI / 5) * i;
        var radius = i % 2 === 0 ? outer : inner;
        var x = cx + Math.cos(angle) * radius;
        var y = cy + Math.sin(angle) * radius;
        if (i === 0) {
          ctx.moveTo(x, y);
        } else {
          ctx.lineTo(x, y);
        }
      }
      ctx.closePath();
    }

    /** 单个城市标记的形状（按 LOD 类别分级）：首都/MajorCity 星标、City 圆环、Town 小圆、其余小点。 */
    function drawCityShape(item, p, radius) {
      if (item.category === "capital" || item.category === "major") {
        var outer = item.category === "capital" ? radius : radius * 0.9;
        ctx.fillStyle = item.category === "capital" ? "#ffd76a" : "#f0b04a";
        traceStar(p.x, p.y, outer, outer * 0.45);
        ctx.fill();
        ctx.strokeStyle = "#0d1015";
        ctx.lineWidth = 1.5 / view.scale;
        ctx.stroke();
        if (item.category === "capital") {
          ctx.beginPath();
          ctx.arc(p.x, p.y, outer + 2 / view.scale, 0, Math.PI * 2);
          ctx.strokeStyle = "#fff3c4";
          ctx.lineWidth = 1.2 / view.scale;
          ctx.stroke();
        }
        return;
      }
      if (item.category === "city") {
        ctx.beginPath();
        ctx.arc(p.x, p.y, radius, 0, Math.PI * 2);
        ctx.fillStyle = "rgba(30, 40, 55, 0.75)";
        ctx.fill();
        ctx.strokeStyle = "#9fd0ff";
        ctx.lineWidth = 2 / view.scale;
        ctx.stroke();
        return;
      }
      // town / marketTown / unknown：小实心点；未知等级更小更灰（fail-closed，不冒充等级）。
      var fill =
        item.category === "town"
          ? "#cfe6ff"
          : item.category === "marketTown"
            ? "#9fb6cc"
            : "#7f8c9b";
      ctx.beginPath();
      ctx.arc(p.x, p.y, radius, 0, Math.PI * 2);
      ctx.fillStyle = fill;
      ctx.fill();
      ctx.strokeStyle = "#0d1015";
      ctx.lineWidth = 1 / view.scale;
      ctx.stroke();
    }

    /**
     * ★ F1：城市图层。数据来自 {@link setCities}（social 侧城市）；LOD / 标签 / 图层开关走
     * {@code worldmodel.cityMarkerPlan} 的同一份计划（pickAt 也只认它 ⇒"看得见才点得到"）。
     */
    function drawCities() {
      if (!layerState.cities) {
        return;
      }
      var plan = cityMarkerPlanOf();
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      plan.forEach(function (item) {
        var p = hexToPixel(item.at.q, item.at.r, cellSize);
        var radius = cityRadiusOf(item);
        drawCityShape(item, p, radius);
        if (selectedCity !== null && selectedCity === item.id) {
          ctx.beginPath();
          ctx.arc(p.x, p.y, radius + 4 / view.scale, 0, Math.PI * 2);
          ctx.strokeStyle = "#4ea1ff";
          ctx.lineWidth = 2.5 / view.scale;
          ctx.stroke();
        }
        if (item.showLabel && item.name) {
          var fontPx = Math.max(10, Math.min(18, radius * 1.6));
          ctx.font = fontPx / view.scale + "px sans-serif";
          ctx.strokeStyle = "rgba(0, 0, 0, 0.85)";
          ctx.lineWidth = 3 / view.scale;
          var labelY = p.y - radius - 8 / view.scale;
          ctx.strokeText(item.name, p.x, labelY);
          ctx.fillStyle = "#ffffff";
          ctx.fillText(item.name, p.x, labelY);
        }
      });
    }

    /**
     * ★ 2026-09-24：标记文字显示的**军队名**——由 `rootId`（军队根）派生：优先根单位的 `name`；
     * 根不在已载入的 `units` 里（无有效位置被过滤 / 悬空）⇒ 回落到 `rootId` 文本。
     *
     * <p>★ 为什么不再用 `leadId`：旧文字写的是"该军队在本格最靠上的单位"的短 id，代表单位一走，
     * 名字就滚到下一个兵种 ⇒ 看着像单位换了身份。军队名是整支军队的稳定标识，不随代表单位漂移。
     */
    function armyNameOf(rootId) {
      var root = unitById[String(rootId)];
      return root && root.name !== null && root.name !== undefined && root.name !== ""
        ? root.name
        : app.text(rootId);
    }

    /**
     * ★ 2026-09-24 修正 1：组内**任一**单位被选中 ⇒ 高亮该组标记
     * （选中的是某个兵种时，它所属军队的那个标记也亮）。
     */
    function isMarkerSelected(marker) {
      if (selectedUnit === null || selectedUnit === undefined) {
        return false;
      }
      return marker.member.indexOf(String(selectedUnit)) >= 0;
    }

    /** ★ F1：该标记里是否有 GOV 单位（root 或任一成员 module.kind === "gov"）⇒ 画菱形/ GOV 徽标。 */
    function markerIsGov(m) {
      for (var i = 0; i < m.member.length; i += 1) {
        var unit = unitById[m.member[i]];
        if (unit && unit.module && unit.module.kind === "gov") {
          return true;
        }
      }
      return false;
    }

    /** ★ F1：该标记是否有决策人（army：affiliation.rootUnit == 军队根；gov：affiliation.id 是标记成员）。 */
    function markerHasDecisionMaker(m) {
      for (var i = 0; i < decisionMakers.length; i += 1) {
        var affiliation = decisionMakers[i].affiliation || {};
        if (
          affiliation.kind === "gov" &&
          affiliation.id !== null &&
          affiliation.id !== undefined &&
          m.member.indexOf(String(affiliation.id)) >= 0
        ) {
          return true;
        }
        if (
          affiliation.kind === "army" &&
          affiliation.rootUnit !== null &&
          affiliation.rootUnit !== undefined &&
          String(affiliation.rootUnit) === String(m.rootId)
        ) {
          return true;
        }
      }
      return false;
    }

    /**
     * ★ 2026-09-24 修正 1：画的是**军队标记**（markers）。
     * 组内**任一**单位被选中 ⇒ 高亮该组标记（选中的是某个兵种时，它所属军队的那个标记也亮）。
     * ★ 2026-09-24 交战：交战方的圆改用**琥珀色加粗描边**与普通（白描边）标记区分，
     * 并在每个交战格的**格心**画一次 ⚔（不是每个标记画一次）。
     * ★ F1：GOV 单位（module.kind === "gov"）画成菱形 + 徽标；决策人图层在其标记上添金色点；
     * 军队/GOV 与交战/路线三个图层都只影响绘制（数据不重取）。
     */
    function drawUnits() {
      // ★ F1 可见性：单位标记半径同样加屏幕空间下限（fit 全图时 markerRadius 世界半径只有亚像素）；
      //   选中外圈 / 决策人徽标 / 交战描边都基于这个 radius 绘制。scale 异常时退回世界半径。
      var radius = Math.max(markerRadius(cellSize), screenSizeToWorld(3.5));
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      // ★ F1 修复：**军队**标签只在**非 world LOD** 画 —— 世界视图不画军队标签，避免与国名/城市混在一起。
      // ★ 2026-10-01 可见性修正：GOV 标签不再跟着军队一刀切 —— 只要 gov 图层开着（循环入口已门控），
      //   世界视图也画「政府：<名>」；GOV 标记数量少，不会糊。
      var armyLabelsVisible =
        worldModel && worldModel.lodForScale
          ? worldModel.lodForScale(view.scale) !== "world"
          : typeof view.scale === "number" && isFinite(view.scale) && view.scale >= 0.22;
      var combatIcons = {}; // "q_r" → 格心世界坐标（每格只画一个 ⚔）
      // ★ 2026-09-24 交战：**真实记录的格**先占位 —— 哪怕该格一个单位标记都没有，也必须画成交战格。
      //   与标记路径同一门控（屏幕格高不够时不启用交战显示，退化为普通视图；口径见 combatLayoutEnabled）。
      if (layerState.combats && combatLayoutEnabled(cellSize, cellSize * view.scale)) {
        Object.keys(realCombatHexes).forEach(function (key) {
          var parts = key.split("_");
          var q = Number(parts[0]);
          var r = Number(parts[1]);
          if (isFinite(q) && isFinite(r)) {
            combatIcons[key] = hexToPixel(q, r, cellSize);
          }
        });
      }
      markers.forEach(function (m) {
        var gov = markerIsGov(m);
        // ★ F1：GOV / army 各自图层门控（取代旧的 units 一刀切门控；pickAt 用同一判据过滤命中）。
        if (gov ? !layerState.gov : !layerState.army) {
          return;
        }
        if (gov) {
          // GOV：菱形（与 army 的红圆一眼可分）。
          ctx.beginPath();
          ctx.moveTo(m.px, m.py - radius);
          ctx.lineTo(m.px + radius, m.py);
          ctx.lineTo(m.px, m.py + radius);
          ctx.lineTo(m.px - radius, m.py);
          ctx.closePath();
          ctx.fillStyle = GOV_JURISDICTION_COLOR;
          ctx.fill();
        } else {
          ctx.beginPath();
          ctx.arc(m.px, m.py, radius, 0, Math.PI * 2);
          ctx.fillStyle = "#e8503a";
          ctx.fill();
        }
        // ★ 交战方：琥珀色加粗描边（仅交战图层开着时）；普通标记：白描边（保持原样）。
        var combatStroke = layerState.combats && m.combat;
        ctx.strokeStyle = combatStroke ? "#ffd54a" : "#ffffff";
        ctx.lineWidth = (combatStroke ? 3 : 2) / view.scale;
        ctx.stroke();
        if (isMarkerSelected(m)) {
          ctx.beginPath();
          ctx.arc(m.px, m.py, radius + 4 / view.scale, 0, Math.PI * 2);
          ctx.strokeStyle = "#4ea1ff";
          ctx.lineWidth = 2.5 / view.scale;
          ctx.stroke();
        }
        // ★ F1：决策人徽标（金色小点）—— 只在该图层开着时画。
        if (layerState.decisionMakers && markerHasDecisionMaker(m)) {
          var badge = Math.max(2.5, radius * 0.3);
          ctx.beginPath();
          ctx.arc(m.px + radius * 0.85, m.py - radius * 0.85, badge, 0, Math.PI * 2);
          ctx.fillStyle = "#ffd76a";
          ctx.fill();
          ctx.strokeStyle = "#0d1015";
          ctx.lineWidth = 1 / view.scale;
          ctx.stroke();
        }
        // ★ F1 可见性修正：GOV 只要图层开着（上面的门控已保证）就在世界视图显示标签；
        //   军队仍按 LOD 门控（world 档不画）。gov/military 预设由此自动表现。
        var unitLabelsVisible = gov ? layerState.gov : armyLabelsVisible;
        // ★ F1 修复：标签画在标记**下方**（textBaseline="top"），字号取屏幕恒定口径
        //   `max(9, 11 / view.scale)`（世界坐标），黑描边白字、居中；军队在 world LOD 不画（见 armyLabelsVisible）。
        if (unitLabelsVisible) {
          var labelFontWorld =
            typeof view.scale === "number" && isFinite(view.scale) && view.scale > 0
              ? Math.max(9, 11 / view.scale)
              : 11;
          var labelY =
            typeof view.scale === "number" && isFinite(view.scale) && view.scale > 0
              ? m.py + radius + 2 / view.scale
              : m.py + radius + 2;
          var rootName = armyNameOf(m.rootId);
          // ★ F1：GOV / army 标签前缀区分；GOV 取根单位名（>12 字符截断加省略号，不显示 ×N）。
          var unitLabel = gov
            ? "政府：" + (rootName.length > 12 ? rootName.slice(0, 12) + "…" : rootName)
            : "军：" + markerLabel(rootName, m.member.length);
          ctx.textAlign = "center";
          ctx.textBaseline = "top";
          ctx.font = labelFontWorld + "px sans-serif";
          ctx.strokeStyle = "rgba(0, 0, 0, 0.85)";
          ctx.lineWidth = 3 / view.scale;
          ctx.strokeText(unitLabel, m.px, labelY);
          ctx.fillStyle = "#ffffff";
          ctx.fillText(unitLabel, m.px, labelY);
        }
        if (layerState.combats && m.combat) {
          var key = m.at.q + "_" + m.at.r;
          if (!combatIcons[key]) {
            combatIcons[key] = hexToPixel(m.at.q, m.at.r, cellSize);
          }
        }
      });
      // ★ 交战格：格心画一次 ⚔（在标记之上，颜色用亮黄，与红/白标记区分得开）。
      //   标记标签把 textBaseline 改成了 top ⇒ 画 ⚔ 前恢复 middle，图标仍以格心为中心。
      ctx.textBaseline = "middle";
      var iconKeys = Object.keys(combatIcons);
      if (iconKeys.length > 0) {
        var iconOff = combatIconOffset();
        ctx.fillStyle = "#ffcf33";
        ctx.font = combatIconFontSize(cellSize) + "px sans-serif";
        iconKeys.forEach(function (key) {
          var c = combatIcons[key];
          ctx.fillText("⚔", c.x + iconOff.x, c.y + iconOff.y);
        });
      }
    }

    /** 把一块的全部环（外环 + 洞环）追加进 Path2D；坐标由「格边长=1」乘 cellSize。 */
    function appendBlockTo(path, block) {
      var rings = block.boundaries || [];
      for (var r = 0; r < rings.length; r++) {
        var ring = rings[r];
        for (var i = 0; i < ring.length; i++) {
          var x = ring[i].x * cellSize;
          var y = ring[i].y * cellSize;
          if (i === 0) {
            path.moveTo(x, y);
          } else {
            path.lineTo(x, y);
          }
        }
        path.closePath();
      }
    }

    /** Pass 1（M9 T14）：画全部块多边形，**同色聚合成一条 Path2D**，`evenodd` 让洞被排除；不再逐格。 */
    function paintTerrain(targetCtx) {
      var byColor = {};
      var colors = [];
      blocks.forEach(function (block) {
        var color = terrainColor(block.terrain);
        if (!byColor[color]) {
          byColor[color] = new Path2D();
          colors.push(color);
        }
        appendBlockTo(byColor[color], block);
        paintedRingCount += (block.boundaries || []).length;
      });
      colors.forEach(function (color) {
        targetCtx.fillStyle = color;
        targetCtx.fill(byColor[color], "evenodd");
      });
    }

    /**
     * ★ F1：**国家着色**（世界视图）——把国家的区域 hex 集合按精确边界环填充一次（半透明），
     * 压在地形之上、其它高亮之下。只画工作台（旧 /map 保持原渲染行为）；图层关掉即不画，数据不重取。
     */
    function paintNationFills(targetCtx) {
      if (!isWorkbench || !layerState.nation || !nationRegions.length) {
        return;
      }
      nationRegions.forEach(function (region) {
        if (!region.rings.length) {
          return;
        }
        var path = new Path2D();
        region.rings.forEach(function (ring) {
          for (var i = 0; i < ring.length; i += 1) {
            var x = ring[i].x * cellSize;
            var y = ring[i].y * cellSize;
            if (i === 0) {
              path.moveTo(x, y);
            } else {
              path.lineTo(x, y);
            }
          }
          path.closePath();
        });
        targetCtx.fillStyle = withAlpha(region.color, 0.32);
        targetCtx.fill(path, "evenodd");
      });
    }

    /**
     * ★ F1：**GOV 辖区覆盖层**（workbench 专用）。每项用预算好的精确 rings 画一次 Path2D：
     * 填充 alpha 未选中 ≈0.24 / 选中 ≈0.38（选中色用 {@link GOV_JURISDICTION_SELECTED_COLOR}，
     * 未选中用 {@link GOV_JURISDICTION_COLOR}），再按屏幕线宽描边界（选中 3.0px 实线、未选中 2.0px 虚线）。
     * 未选中虚线用世界坐标 [6/scale, 4/scale]，屏幕恒定为 6px 实 / 4px 空；scale 非正/非有限时退回 [6,4]
     * （不除 0、不产生 NaN）。函数末尾恢复 setLineDash([])，避免污染后续描边。
     *
     * <p>门控：非工作台 / 图层关 / 无数据 ⇒ 直接 return；屏幕线宽 = px / view.scale，走
     * {@link screenSizeToWorld} 的同一守卫（scale 非正/非有限 ⇒ 0，不除 0；宽度无效时只填不描）。
     */
    function paintGovJurisdictions(targetCtx) {
      if (!isWorkbench || !layerState.govJurisdiction || !govJurisdictions.length) {
        return;
      }
      var strokeWidth = screenSizeToWorld(GOV_JURISDICTION_STROKE_PX);
      var selectedStrokeWidth = screenSizeToWorld(GOV_JURISDICTION_SELECTED_STROKE_PX);
      // ★ F1 可见性修正：未选中 = 屏幕恒定虚线（世界坐标按 scale 折算）；scale 异常时退回 [6,4]（绝不 NaN）。
      var dashPattern =
        typeof view.scale === "number" && isFinite(view.scale) && view.scale > 0
          ? [6 / view.scale, 4 / view.scale]
          : [6, 4];
      govJurisdictions.forEach(function (item) {
        if (!item.rings.length) {
          return;
        }
        var selected =
          selectedUnit !== null && selectedUnit !== undefined && String(selectedUnit) === item.govId;
        var path = new Path2D();
        item.rings.forEach(function (ring) {
          for (var i = 0; i < ring.length; i += 1) {
            var x = ring[i].x * cellSize;
            var y = ring[i].y * cellSize;
            if (i === 0) {
              path.moveTo(x, y);
            } else {
              path.lineTo(x, y);
            }
          }
          path.closePath();
        });
        // ★ 选中 = 实线；未选中 = 虚线。setLineDash 在 fill/stroke 之间保持，结束时统一清空。
        targetCtx.setLineDash(selected ? [] : dashPattern);
        targetCtx.fillStyle = withAlpha(
          selected ? GOV_JURISDICTION_SELECTED_COLOR : GOV_JURISDICTION_COLOR,
          selected ? GOV_JURISDICTION_SELECTED_FILL_ALPHA : GOV_JURISDICTION_FILL_ALPHA
        );
        targetCtx.fill(path, "evenodd");
        var width = selected ? selectedStrokeWidth : strokeWidth;
        if (typeof width === "number" && isFinite(width) && width > 0) {
          targetCtx.strokeStyle = selected
            ? GOV_JURISDICTION_SELECTED_COLOR
            : GOV_JURISDICTION_COLOR;
          targetCtx.lineWidth = width;
          targetCtx.stroke(path);
        }
      });
      targetCtx.setLineDash([]);
    }

    /**
     * ★ F2：热力层（工作台/旧页共用同一条绘制路径，但只有宿主推了非空 plan 才有内容）。
     *
     * <p>逐格画满格六边形（`addHexPath`，radius = cellSize）并 `fill`；整体用 `save()/restore()` 套
     * `globalAlpha = heatmap.opacity`，绘制结束恢复 alpha。**缺失格不画、不补 0；无 color 的格跳过**。
     * 画面顺序由 {@link render} 固定：GOV 辖区之后、地形压暗/区域高亮/城市/单位/标签之前。
     */
    function drawHeatmap(targetCtx) {
      if (!heatmap || !heatmap.cells.length) {
        return;
      }
      targetCtx.save();
      targetCtx.globalAlpha = heatmap.opacity;
      heatmap.cells.forEach(function (cell) {
        if (!cell.color) {
          return;
        }
        var p = hexToPixel(cell.q, cell.r, cellSize);
        targetCtx.beginPath();
        addHexPath(targetCtx, p.x, p.y, cellSize);
        targetCtx.fillStyle = cell.color;
        targetCtx.fill();
      });
      targetCtx.restore();
    }

    /**
     * ★ U1：区域查看 / 区域编辑模式下压暗地形底图（全画布深色 scrim，屏幕空间画、不随缩放变强度）。
     *
     * <p>插入点是**地形之后、高亮之前** —— 否则区域填充会被一起压暗、或地形盖住压暗层。
     */
    function paintTerrainDim(targetCtx, mode) {
      var alpha = terrainDimAlpha(mode);
      if (alpha <= 0) {
        return;
      }
      targetCtx.setTransform(dpr, 0, 0, dpr, 0, 0);
      targetCtx.fillStyle = withAlpha(REGION_DIM_COLOR, alpha);
      targetCtx.fillRect(0, 0, cssW, cssH);
      worldTransform();
      dimPasses += 1;
    }

    /**
     * ★ U2：区域名（配方照 GSimulator `render.js:348-364`）：质心居中、字号 ∝√格数 ÷ zoom、
     * 黑描边白字。位置来自 `overview.regions[].label`（服务端质心 hex）。
     *
     * <p>★★ 2026-09-24（用户报障「启动就加载所有区域名，非常卡，改成和 hex 一样按屏幕所见范围渲染」）：
     * 逐帧只画**屏幕可见范围内**的标签 —— 落点与裁剪走纯函数 {@code regionNamePlan}（map.js，可单测），
     * 本函数只负责把计划画出来。原来这里是"遍历全部区域、逐条 stroke/fill"。
     */
    function paintRegionNames(targetCtx) {
      regionNameDraws = 0;
      // ★ F1：区域名图层开关（默认开）与既有"区域名"复选框**同时**满足才画；关掉图层不影响既有开关语义。
      if (!layerState.regionNames || !regionNamesVisible(mode) || view.scale < REGION_NAME_MIN_SCALE) {
        return;
      }
      var plan = regionNamePlan(
        host.overviewRegions || [],
        view,
        { width: cssW, height: cssH },
        REGION_NAME_VISIBLE_MARGIN,
        cellSize
      );
      if (!plan.length) {
        return;
      }
      // ★ 与位置无关的 ctx 状态只设一次（原实现每条都重设一遍）。
      targetCtx.textAlign = "center";
      targetCtx.textBaseline = "middle";
      targetCtx.lineWidth = 3 / view.scale;
      targetCtx.strokeStyle = "rgba(0, 0, 0, 0.85)";
      targetCtx.fillStyle = "#ffffff";
      plan.forEach(function (label) {
        targetCtx.font = "bold " + label.fontSize + "px sans-serif";
        targetCtx.strokeText(label.text, label.x, label.y);
        targetCtx.fillText(label.text, label.x, label.y);
      });
      regionNameDraws = plan.length;
    }

    /**
     * ★ F1：世界视图里的**国家名**（区域名图层的 world LOD 子集）。区域模式仍走 {@link #paintRegionNames}
     * 的既有全区域计划；本函数只在 view 模式、且缩放达到 {@code NATION_LABEL_MIN_SCALE} 时画三国的国名，
     * 使"世界视图能看见国名"不需要先切到区域模式。计数独立成 {@code nationNameDraws}，不污染既有区域名断言。
     */
    function paintNationNames(targetCtx) {
      nationNameDraws = 0;
      if (!layerState.regionNames || !isWorkbench || mode !== "view") {
        return;
      }
      // ★ F1 修复：国家名只在**世界级 LOD** 显示（worldmodel.nationNamesVisible 与城市 LOD 同一判据）；
      //   缩放大到区域/近景后国家名消失，只留城市名。
      var nationsVisible =
        worldModel && worldModel.nationNamesVisible
          ? worldModel.nationNamesVisible(view.scale)
          : typeof view.scale === "number" && isFinite(view.scale) && view.scale > 0 && view.scale < 0.22;
      if (!nationsVisible) {
        return;
      }
      var plan = worldModel && worldModel.nationLabelPlan
        ? worldModel.nationLabelPlan((overview && overview.regions) || [], view.scale)
        : [];
      if (!plan.length) {
        return;
      }
      targetCtx.textAlign = "center";
      targetCtx.textBaseline = "middle";
      targetCtx.lineWidth = 3 / view.scale;
      targetCtx.strokeStyle = "rgba(0, 0, 0, 0.85)";
      targetCtx.fillStyle = "#ffffff";
      plan.forEach(function (label) {
        var world = hexToPixel(label.q, label.r, cellSize);
        targetCtx.font = "bold " + label.fontSize + "px sans-serif";
        targetCtx.strokeText(label.text, world.x, world.y);
        targetCtx.fillText(label.text, world.x, world.y);
      });
      nationNameDraws = plan.length;
    }

    /**
     * 区域高亮：直接迭代高亮集合（不再扫全表）；区域 hex 来自 /api/map/region/{id}（按需拉取）。
     * ★ M8-R §八：填充半径 = cellSize（满格）——旧值 0.98 会在每个 hex 之间留缝，用户见到的
     *   "区域中每一个 hex 都有边框"就是这些**填充缝**（不是 stroke）。
     */
    function paintHighlights(targetCtx) {
      var radius = cellSize;
      var highlightByColor = {};
      Object.keys(highlightColorByKey).forEach(function (key) {
        var color = highlightColorByKey[key];
        if (!color) {
          return;
        }
        var alpha = highlightAlphaByKey[key];
        var groupKey = color + "|" + (alpha === null || alpha === undefined ? "" : alpha);
        var parts = key.split("_");
        var point = hexToPixel(Number(parts[0]), Number(parts[1]), cellSize);
        var group = highlightByColor[groupKey] || (highlightByColor[groupKey] = { color: color, alpha: alpha, points: [] });
        group.points.push(point);
      });
      Object.keys(highlightByColor).forEach(function (groupKey) {
        var group = highlightByColor[groupKey];
        targetCtx.beginPath();
        group.points.forEach(function (point) {
          addHexPath(targetCtx, point.x, point.y, radius);
        });
        var alpha = group.alpha === null || group.alpha === undefined ? HIGHLIGHT_ALPHA : group.alpha;
        targetCtx.fillStyle = withAlpha(group.color, alpha);
        targetCtx.fill();
      });
    }

    /** 拖刷预览（M8 T8；M8-R §八 去描边）：只铺半透明色，**不逐格 stroke**（不再有逐 hex 边框）。 */
    function paintBrush(targetCtx) {
      var keys = Object.keys(brushKeys);
      if (!keys.length) {
        return;
      }
      targetCtx.beginPath();
      keys.forEach(function (key) {
        var hex = brushKeys[key];
        var point = hexToPixel(hex.q, hex.r, cellSize);
        addHexPath(targetCtx, point.x, point.y, cellSize);
      });
      var removing = brushOp === "remove";
      targetCtx.fillStyle = removing ? "rgba(255, 90, 90, 0.35)" : "rgba(78, 161, 255, 0.35)";
      targetCtx.fill();
    }

    /** 区域编辑的临时选区（M8-R §八 去描边）：只铺半透明色，**不逐格 stroke**。 */
    function paintDraft(targetCtx) {
      var keys = Object.keys(draftKeys);
      if (!keys.length) {
        return;
      }
      targetCtx.beginPath();
      keys.forEach(function (key) {
        var hex = draftKeys[key];
        var point = hexToPixel(hex.q, hex.r, cellSize);
        addHexPath(targetCtx, point.x, point.y, cellSize);
      });
      targetCtx.fillStyle = "rgba(78, 161, 255, 0.3)";
      targetCtx.fill();
    }

    /** 套索预览（M8-R）：品红折线 + 半透明填格（照 GSimulator renderProvincePreview）。 */
    function paintLasso(targetCtx) {
      if (!lassoOrder.length) {
        return;
      }
      targetCtx.beginPath();
      lassoOrder.forEach(function (point, index) {
        var pixel = hexToPixel(point.q, point.r, cellSize);
        if (index === 0) {
          targetCtx.moveTo(pixel.x, pixel.y);
        } else {
          targetCtx.lineTo(pixel.x, pixel.y);
        }
      });
      targetCtx.closePath();
      targetCtx.strokeStyle = "#ff00ff";
      targetCtx.lineWidth = 2 / view.scale;
      targetCtx.stroke();
      if (lassoOrder.length >= 3) {
        targetCtx.fillStyle = "rgba(255, 0, 255, 0.12)";
        targetCtx.fill();
      }
    }

    /** 焦点区域边界小点（M8-R）：只对**正在编辑**的区域画，点在**边界 hex 中心**（照 render.js:335-346）。 */
    function paintBoundaryDots(targetCtx) {
      if (mode !== "region-edit" || !Object.keys(focusKeys).length) {
        return;
      }
      var boundary = focusBoundaryList();
      if (!boundary.length) {
        return;
      }
      var radius = Math.max(3, 6 / view.scale);
      targetCtx.beginPath();
      boundary.forEach(function (point) {
        var pixel = hexToPixel(point.q, point.r, cellSize);
        targetCtx.moveTo(pixel.x + radius, pixel.y);
        targetCtx.arc(pixel.x, pixel.y, radius, 0, Math.PI * 2);
      });
      targetCtx.fillStyle = focusColorHex || "#ffffff";
      targetCtx.fill();
      targetCtx.strokeStyle = "rgba(10, 12, 16, 0.9)";
      targetCtx.lineWidth = 1.5 / view.scale;
      targetCtx.stroke();
    }
    /**
     * 设置要描边的区域（每项 {id,color,alpha,hexes}）：直接用 regionBoundaryRings 的**精确环**。
     *
     * <p>★ M8-S §九：**不做 RDP/平滑/抽稀**——渲染顶点必须逐值等于 `Region.boundary`（即 `RegionBoundary.of(hexes)`）
     * 的顶点，严格贴着所属六边形最外侧。此处只把环的顶点总数记进 debug 供断言。
     */
    function setRegionOutlines(list) {
      var total = 0;
      regionOutlines = (list || []).map(function (entry) {
        var rings = regionBoundaryRings(entry.hexes || []);
        rings.forEach(function (ring) {
          total += ring.length;
        });
        return {
          id: entry.id,
          color: entry.color || REGION_FALLBACK_COLOR,
          alpha: entry.alpha === undefined || entry.alpha === null ? 1 : entry.alpha,
          rings: rings,
        };
      });
      outlineVertexCount = total;
      scheduleRender();
    }

    /** 区域边界描边（M8-S §九）：只画区域边界、**精确的**闭合环；**绝无逐格 stroke、绝无简化**。 */
    function paintRegionOutlines(targetCtx) {
      if (!regionOutlines.length) {
        return;
      }
      regionOutlines.forEach(function (outline) {
        if (!outline.rings.length) {
          return;
        }
        targetCtx.beginPath();
        outline.rings.forEach(function (ring) {
          for (var i = 0; i < ring.length; i++) {
            var x = ring[i].x * cellSize;
            var y = ring[i].y * cellSize;
            if (i === 0) {
              targetCtx.moveTo(x, y);
            } else {
              targetCtx.lineTo(x, y);
            }
          }
          targetCtx.closePath();
        });
        targetCtx.strokeStyle = withAlpha(outline.color, outline.alpha);
        targetCtx.lineWidth = 2 / view.scale;
        targetCtx.stroke();
      });
    }

    function worldTransform() {
      ctx.setTransform(dpr * view.scale, 0, 0, dpr * view.scale, dpr * view.tx, dpr * view.ty);
    }

    /** Pass 2：未合并余量。P6 全部建块 ⇒ 余量恒 0（服务端 `GameMap` 分割不变式保证），故不画；>0 时 setData 已 warn。 */
    function paintLeftover() {
      if (unmergedCount > 0) {
        pass2Draws += 1;
      }
    }

    function render() {
      if (!overview || cssW <= 0) {
        return;
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, cssW, cssH);
      worldTransform();

      if (perfConfig.terrainCacheEnabled) {
        updateTerrainCache();
        blitTerrainCache();
      } else {
        paintTerrain(ctx);
      }
      // ★ F1：国家着色（地形之上、其它高亮之下；只画工作台，见图层开关）。
      paintNationFills(ctx);
      // ★ F1：GOV 辖区覆盖层（国家着色之上、地形压暗/高亮之下；只画工作台，见图层开关）。
      paintGovJurisdictions(ctx);
      // ★ F2：热力层（地形/国家/GOV 之后，地形压暗/高亮/城市/单位/标签之前；不盖住标记与标签）。
      drawHeatmap(ctx);
      // ★ U1：区域模式先把地形压暗（屏幕空间全覆盖），再叠区域填充/边界 ⇒ 区域成为视觉主体。
      paintTerrainDim(ctx, app.getState().mode);
      paintHighlights(ctx);
      paintRegionOutlines(ctx);
      // ★ U2：区域名压在填充/边界之上、单位之下。
      paintRegionNames(ctx);
      // ★ F1：世界视图的国家名（与区域名分开计数，互不影响既有断言）。
      paintNationNames(ctx);
      paintDraft(ctx);
      paintLasso(ctx);
      paintBoundaryDots(ctx);
      paintBrush(ctx);
      paintLeftover();

      drawEdges();
      drawRoutes();
      drawCities();
      drawUnits();

      if (selected) {
        var p = hexToPixel(selected.q, selected.r, cellSize);
        worldTransform();
        ctx.beginPath();
        addHexPath(ctx, p.x, p.y, cellSize * 0.92);
        ctx.strokeStyle = "#4ea1ff";
        ctx.lineWidth = 2 / view.scale;
        ctx.stroke();
      }
      updateZoomUi();
    }

    function updateTerrainCache() {
      if (!terrainCanvas || !terrainCache || terrainDirty) {
        rebuildTerrainCanvas();
        return;
      }
      if (Math.abs(view.scale - terrainCache.scale) >= 1e-9) {
        scheduleTerrainSettle();
        return;
      }
      if (
        Math.abs(view.tx - terrainCache.tx) > TERRAIN_CACHE_MARGIN ||
        Math.abs(view.ty - terrainCache.ty) > TERRAIN_CACHE_MARGIN
      ) {
        rebuildTerrainCanvas();
      }
    }

    function ensureTerrainCanvas() {
      var w = cssW + TERRAIN_CACHE_MARGIN * 2;
      var h = cssH + TERRAIN_CACHE_MARGIN * 2;
      if (!terrainCanvas) {
        terrainCanvas = document.createElement("canvas");
        terrainCtx = terrainCanvas.getContext("2d");
      }
      if (terrainCanvas.width !== Math.round(w * dpr) || terrainCanvas.height !== Math.round(h * dpr)) {
        terrainCanvas.width = Math.round(w * dpr);
        terrainCanvas.height = Math.round(h * dpr);
      }
    }

    function rebuildTerrainCanvas() {
      ensureTerrainCanvas();
      if (!terrainCtx) {
        return;
      }
      var w = cssW + TERRAIN_CACHE_MARGIN * 2;
      var h = cssH + TERRAIN_CACHE_MARGIN * 2;
      terrainCtx.setTransform(1, 0, 0, 1, 0, 0);
      terrainCtx.clearRect(0, 0, w * dpr, h * dpr);
      terrainCtx.setTransform(
        dpr * view.scale,
        0,
        0,
        dpr * view.scale,
        dpr * (view.tx + TERRAIN_CACHE_MARGIN),
        dpr * (view.ty + TERRAIN_CACHE_MARGIN)
      );
      paintTerrain(terrainCtx);      terrainCache = { scale: view.scale, tx: view.tx, ty: view.ty };
      terrainDirty = false;
      terrainRebuilds += 1;
    }

    function blitTerrainCache() {
      if (!terrainCanvas || !terrainCache) {
        return;
      }
      terrainBlits += 1;
      var ratio = view.scale / terrainCache.scale;
      var w = cssW + TERRAIN_CACHE_MARGIN * 2;
      var h = cssH + TERRAIN_CACHE_MARGIN * 2;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.drawImage(
        terrainCanvas,
        0,
        0,
        terrainCanvas.width,
        terrainCanvas.height,
        view.tx - (TERRAIN_CACHE_MARGIN + terrainCache.tx) * ratio,
        view.ty - (TERRAIN_CACHE_MARGIN + terrainCache.ty) * ratio,
        w * ratio,
        h * ratio
      );
      worldTransform();
    }

    function scheduleTerrainSettle() {
      if (terrainSettleTimer !== null) {
        window.clearTimeout(terrainSettleTimer);
      }
      terrainSettleTimer = window.setTimeout(function () {
        terrainSettleTimer = null;
        if (!perfConfig.terrainCacheEnabled || !overview) {
          return;
        }
        rebuildTerrainCanvas();
        scheduleRender();
      }, ZOOM_SETTLE_MS);
    }

    /** 屏幕 CSS 坐标 → 世界 → 命中（单位优先于格）。★ m2 保护的是这里的世界换算。 */
    function pickAt(point) {
      var world = screenToWorld(point, view);
      var baseHitRadius = Math.max(cellSize * 0.36, 10 / view.scale);
      // ★ 2026-09-24 修正 1：命中**军队标记**，返回其**代表单位**（leadId）的 id ——
      //   点首都格里的兵种标记，选中的是整支军队的代表（根），与标记画的是同一个单位。
      for (var i = markers.length - 1; i >= 0; i--) {
        var m = markers[i];
        // ★ F1：单位命中按 kind 过滤 —— GOV 标记只在 gov 图层开时参与，army 标记只在 army 图层开时参与
        //   （与 drawUnits 的绘制门控同口径："看得见才点得到"）；城市命中仍只看 cities 图层。
        if (markerIsGov(m) ? !layerState.gov : !layerState.army) {
          continue;
        }
        // ★ 2026-09-23：摊开的标记命中半径收缩到**不超过半间距** ⇒ 不误伤纵向相邻的邻居；
        //   未摊开（stackSpacing=0，含大小格下的重叠态）保持原口径。
        // ★ 2026-09-24 交战：交战格上的标记用**同一套 combatSlot 坐标**（m.px/m.py），命中半径
        //   同样收窄到 ≤ 半行距 ⇒ 不误伤**同侧纵向**邻居。★ 两侧列相距 2×(2r+gap)≈ 2×baseHitRadius
        //   以上 ⇒ 不会误伤**另一侧**的邻居（见报告里的数值）。
        var hitRadius = baseHitRadius;
        if (m.combat) {
          hitRadius = Math.min(baseHitRadius, (m.combatRowSpacing || 0) / 2);
        } else if (m.stackSpacing > 0) {
          hitRadius = Math.min(baseHitRadius, m.stackSpacing / 2);
        }
        var dx = world.x - m.px;
        var dy = world.y - m.py;
        if (dx * dx + dy * dy <= hitRadius * hitRadius) {
          var lead = unitById[m.leadId];
          return {
            kind: "unit",
            id: m.leadId,
            name: lead ? lead.name : null,
            q: m.at.q,
            r: m.at.r,
            inMap: true,
          };
        }
      }
      // ★ F1：单位标记之后、hex 之前命中**城市标记**（同一格有单位时单位优先，保持"点单位"直觉）。
      //   只在城市图层开着时命中（"看得见才点得到"）；命中半径与绘制半径同源（避免"画得大、点不到"）。
      if (layerState.cities) {
        var cityPlan = cityMarkerPlanOf();
        for (var c = cityPlan.length - 1; c >= 0; c -= 1) {
          var city = cityPlan[c];
          var cp = hexToPixel(city.at.q, city.at.r, cellSize);
          var cityHitRadius = Math.max(cityRadiusOf(city), 6 / view.scale);
          var cdx = world.x - cp.x;
          var cdy = world.y - cp.y;
          if (cdx * cdx + cdy * cdy <= cityHitRadius * cityHitRadius) {
            return {
              kind: "city",
              id: city.id,
              name: city.name || null,
              q: city.at.q,
              r: city.at.r,
              inMap: true,
            };
          }
        }
      }
      var coord = pixelToHex(world.x, world.y, cellSize);
      var block =
        window.SimosBlocks && window.SimosBlocks.blockAtPoint
          ? window.SimosBlocks.blockAtPoint(blocks, world.x / cellSize, world.y / cellSize)
          : null;
      return {
        kind: "hex",
        q: coord.q,
        r: coord.r,
        inMap: !!block,
        terrain: block ? block.terrain : null,
      };
    }

    /** 一个格中心在画布内的 CSS 像素位置（e2e 点选/像素取样的锚）。 */
    function screenPointOf(q, r) {
      return worldToScreen(hexToPixel(q, r, cellSize), view);
    }

    function brushList() {
      var out = [];
      Object.keys(brushKeys).forEach(function (key) {
        out.push(brushKeys[key]);
      });
      return out;
    }

    function setBrushHexes(list) {
      brushKeys = {};
      (list || []).forEach(function (hex) {
        if (hex && hex.q !== undefined && hex.r !== undefined) {
          brushKeys[hex.q + "_" + hex.r] = { q: hex.q, r: hex.r };
        }
      });
      scheduleRender();
    }

    function setBrushOp(op) {
      brushOp = op === "remove" ? "remove" : "add";
      scheduleRender();
    }

    function setDraftHexes(list) {
      draftKeys = {};
      (list || []).forEach(function (hex) {
        if (hex && hex.q !== undefined && hex.r !== undefined) {
          draftKeys[hex.q + "_" + hex.r] = { q: hex.q, r: hex.r };
        }
      });
      scheduleRender();
    }

    function draftList() {
      var out = [];
      Object.keys(draftKeys).forEach(function (key) {
        out.push(draftKeys[key]);
      });
      return out;
    }

    function keyOf(q, r) {
      return q + "_" + r;
    }

    function hasKey(map, key) {
      return Object.prototype.hasOwnProperty.call(map, key);
    }

    /** 该轴向坐标在图上是否有格（M9 起唯一权威是块多边形；套索/flood/拖点共用）。 */
    function hexExists(q, r) {
      return !!(window.SimosBlocks && window.SimosBlocks.blockAtHex(blocks, q, r));
    }

    function pushLasso(q, r) {
      var key = keyOf(q, r);
      if (!hasKey(lassoKeys, key)) {
        lassoKeys[key] = { q: q, r: r };
      }
      lassoOrder.push({ q: q, r: r });
    }

    /** 收集一个套索点：与上一点之间用 hexLine 补齐（快速拖动不漏格）。 */
    function addLassoPoint(q, r) {
      if (lassoOrder.length) {
        var last = lassoOrder[lassoOrder.length - 1];
        if (last.q === q && last.r === r) {
          return;
        }
        var line = hexLine(last.q, last.r, q, r);
        for (var i = 1; i < line.length; i++) {
          pushLasso(line[i].q, line[i].r);
        }
      } else {
        pushLasso(q, r);
      }
      scheduleRender();
    }

    function resetLasso() {
      lassoKeys = {};
      lassoOrder = [];
      lassoActive = false;
      scheduleRender();
    }

    /** 环上找 seed：从质心向外逐环找一个**不在套索墙内**且**图上有格**的格（照 GSimulator finishProvinceLasso）。 */
    function findLassoSeed(wallKeys, cq, cr) {
      if (!hasKey(wallKeys, keyOf(cq, cr)) && hexExists(cq, cr)) {
        return { q: cq, r: cr };
      }
      for (var radius = 1; radius < 200; radius++) {
        var qq = cq + radius * DIR_VECTORS[4][0];
        var rr = cr + radius * DIR_VECTORS[4][1];
        for (var d = 0; d < 6; d++) {
          for (var s = 0; s < radius; s++) {
            if (!hasKey(wallKeys, keyOf(qq, rr)) && hexExists(qq, rr)) {
              return { q: qq, r: rr };
            }
            qq += DIR_VECTORS[d][0];
            rr += DIR_VECTORS[d][1];
          }
        }
      }
      return null;
    }

    /** wallKeys 为套索墙的格集合 ⇒ 返回「flood fill 内部 ∪ 套索墙」的 hex 列表（GSimulator 同型）。 */
    function floodFillFromWall(wallKeys) {
      var wallList = Object.keys(wallKeys);
      if (wallList.length < 3) {
        return [];
      }
      var sumQ = 0;
      var sumR = 0;
      wallList.forEach(function (key) {
        sumQ += wallKeys[key].q;
        sumR += wallKeys[key].r;
      });
      var seed = findLassoSeed(
        wallKeys,
        Math.round(sumQ / wallList.length),
        Math.round(sumR / wallList.length)
      );
      if (!seed) {
        return [];
      }
      var interior = {};
      var visited = {};
      var queue = [seed];
      while (queue.length) {
        var cur = queue.pop();
        var ck = keyOf(cur.q, cur.r);
        if (visited[ck]) {
          continue;
        }
        visited[ck] = true;
        if (hasKey(wallKeys, ck)) {
          continue;
        }
        interior[ck] = { q: cur.q, r: cur.r };
        var neighbors = axialNeighbors(cur.q, cur.r);
        for (var i = 0; i < neighbors.length; i++) {
          var nk = keyOf(neighbors[i].q, neighbors[i].r);
          if (!visited[nk] && !hasKey(wallKeys, nk) && hexExists(neighbors[i].q, neighbors[i].r)) {
            queue.push(neighbors[i]);
          }
        }
      }
      var out = [];
      var seen = {};
      Object.keys(interior).forEach(function (key) {
        if (!seen[key]) {
          seen[key] = true;
          out.push(interior[key]);
        }
      });
      wallList.forEach(function (key) {
        if (!seen[key]) {
          seen[key] = true;
          out.push(wallKeys[key]);
        }
      });
      return out;
    }

    function finishLasso() {
      var result = floodFillFromWall(lassoKeys);
      resetLasso();
      return result;
    }

    function lassoHexList() {
      return Object.keys(lassoKeys).map(function (key) {
        return lassoKeys[key];
      });
    }

    function recomputeFocusBoundary() {
      focusBoundary = [];
      Object.keys(focusKeys).forEach(function (key) {
        var point = focusKeys[key];
        var neighbors = axialNeighbors(point.q, point.r);
        for (var i = 0; i < neighbors.length; i++) {
          if (!hasKey(focusKeys, keyOf(neighbors[i].q, neighbors[i].r))) {
            focusBoundary.push(point);
            return;
          }
        }
      });
      focusBoundaryDirty = false;
    }

    function focusBoundaryList() {
      if (focusBoundaryDirty) {
        recomputeFocusBoundary();
      }
      return focusBoundary;
    }

    function focusHexList() {
      var out = [];
      Object.keys(focusKeys).forEach(function (key) {
        out.push(focusKeys[key]);
      });
      return out;
    }

    /** 设置"编辑中区域"的 hex 集合（边界小点与拖点增删都基于它）；空表 ⇒ 不画点。 */
    function setFocusHexes(list, color) {
      focusKeys = {};
      (list || []).forEach(function (hex) {
        if (hex && hex.q !== undefined && hex.r !== undefined) {
          focusKeys[keyOf(hex.q, hex.r)] = { q: hex.q, r: hex.r };
        }
      });
      focusColorHex = color || null;
      focusBoundaryDirty = true;
      scheduleRender();
    }

    function clearFocusHexes() {
      setFocusHexes([], null);
    }

    /** 命中正在编辑区域的**边界小点**（格中心）⇒ 返回该格；否则 null。 */
    function boundaryDotAt(point) {
      var pick = pickAt(point);
      if (pick.kind !== "hex" || !pick.inMap) {
        return null;
      }
      var key = keyOf(pick.q, pick.r);
      if (!hasKey(focusKeys, key)) {
        return null;
      }
      var boundary = focusBoundaryList();
      for (var i = 0; i < boundary.length; i++) {
        if (boundary[i].q === pick.q && boundary[i].r === pick.r) {
          return { q: pick.q, r: pick.r };
        }
      }
      return null;
    }

    /** 拖点：到**区域外且有格**的相邻格 ⇒ 增格；到**区域内**的格（非原点）⇒ 删旧格（照 GSimulator events.js:63-79）。 */
    function moveDotTo(point) {
      var pick = pickAt(point);
      if (pick.kind !== "hex" || !pick.inMap) {
        return;
      }
      var newKey = keyOf(pick.q, pick.r);
      var oldKey = keyOf(dotDrag.q, dotDrag.r);
      if (newKey === oldKey) {
        return;
      }
      if (!hasKey(focusKeys, newKey) && hexExists(pick.q, pick.r)) {
        focusKeys[newKey] = { q: pick.q, r: pick.r };
      } else if (hasKey(focusKeys, newKey)) {
        delete focusKeys[oldKey];
      } else {
        return;
      }
      dotDrag = { q: pick.q, r: pick.r };
      dotDragDirty = true;
      focusBoundaryDirty = true;
      scheduleRender();
    }

    /** 结束拖点：返回变更后的 hex 列表（未变更 ⇒ 空表，调用方据此不发写）。 */
    function flushDotDrag() {
      dotDrag = null;
      if (!dotDragDirty) {
        return [];
      }
      dotDragDirty = false;
      return focusHexList();
    }

    /** 拖刷：把当前指针下的格并入预览集合（去重）；图外/非格不动。 */
    function paintAt(point) {
      var pick = pickAt(point);
      if (pick && pick.kind === "hex" && pick.inMap) {
        brushKeys[pick.q + "_" + pick.r] = { q: pick.q, r: pick.r };
        scheduleRender();
      }
    }

    function canvasPoint(event) {
      var rect = canvas.getBoundingClientRect();
      return { x: event.clientX - rect.left, y: event.clientY - rect.top };
    }

    function capturePointer(pointerId) {
      if (canvas.setPointerCapture) {
        try {
          canvas.setPointerCapture(pointerId);
        } catch (e) {
          /* 某些环境不支持捕获；不影响操作 */
        }
      }
    }

    function releasePointer(pointerId) {
      if (canvas.releasePointerCapture) {
        try {
          canvas.releasePointerCapture(pointerId);
        } catch (e) {
          /* 未捕获时忽略 */
        }
      }
    }

    function updateCursor() {
      if (lassoActive) {
        canvas.style.cursor = "crosshair";
      } else if (dragging || dotDrag) {
        canvas.style.cursor = "grabbing";
      } else if (mode === "region") {
        canvas.style.cursor = "pointer";
      } else {
        canvas.style.cursor = "grab";
      }
    }

    /** 开始一次"画/擦"（地形刷 / 圈选随机化选区 / 区域 Shift+右键逐格）：收集预览格，松手由 onPaintCommit 落一条命令。 */
    function beginPaint(event) {
      painting = true;
      capturePointer(event.pointerId);
      paintAt(canvasPoint(event));
      updateCursor();
    }

    /**
     * 开始一次**连边**（M8 T11：河流/道路）：只记录右键拖动的**原始轨迹**，
     * 相邻性判断与去重留给纯函数 {@link edgeChainEdges}（松手时算）——护栏只有一处实现。
     */
    function beginEdgeDrag(event) {
      painting = true;
      edgeDrag = { path: [] };
      capturePointer(event.pointerId);
      edgeAt(canvasPoint(event));
      updateCursor();
    }

    /** 把当前指针下的格并入连边轨迹（去重相邻重复点）；图外/非格不动。 */
    function edgeAt(point) {
      var pick = pickAt(point);
      if (!pick || pick.kind !== "hex" || !pick.inMap) {
        return;
      }
      var path = edgeDrag.path;
      var last = path.length ? path[path.length - 1] : null;
      if (last && last.q === pick.q && last.r === pick.r) {
        return;
      }
      path.push({ q: pick.q, r: pick.r });
      brushKeys[pick.q + "_" + pick.r] = { q: pick.q, r: pick.r };
      scheduleRender();
    }

    /** 当前指针下的连通性边（**只考虑当前工具那个 kind**）；屏幕像素 → 世界坐标，阈值 12px / scale。 */
    function edgeHitAt(point) {
      if (!overview || !overview.edges || !overview.edges.length) {
        return null;
      }
      var world = { x: (point.x - view.tx) / view.scale, y: (point.y - view.ty) / view.scale };
      return edgeHitAtWorldPoint(
        world,
        overview.edges,
        cellSize,
        EDGE_HIT_THRESHOLD_PX / view.scale,
        editTool
      );
    }

    /** 开始一次**删边**（左键命中一条边）：与连边同款只记轨迹、松手合成一条 replace。 */
    function beginEdgeDelete(event, hit) {
      painting = true;
      edgeDeleteDrag = { kind: editTool, deleted: {}, count: 0 };
      edgeDeleteAt(hit);
      capturePointer(event.pointerId);
      updateCursor();
    }

    function edgeDeleteAt(hit) {
      if (!hit || !hit.edge || !edgeDeleteDrag) {
        return;
      }
      if (edgeDeleteDrag.deleted[hit.edge]) {
        return;
      }
      edgeDeleteDrag.deleted[hit.edge] = true;
      edgeDeleteDrag.count += 1;
      scheduleRender();
    }

    function onPointerDown(event) {
      // ★ §七 右键按模式分派：区域编辑=套索（Shift+右键=逐格画擦）；地图编辑=**按工具**（地形刷/连边/圈选）；
      //   常规 / 单位移动编辑 ⇒ 放行给 contextmenu 宿主（unit.PlanRoute，不许变）。
      if (event.button === 2) {
        if (mode === "region-edit") {
          event.preventDefault();
          if (event.shiftKey) {
            beginPaint(event);
            return;
          }
          lassoActive = true;
          lassoKeys = {};
          lassoOrder = [];
          capturePointer(event.pointerId);
          var lassoPick = pickAt(canvasPoint(event));
          if (lassoPick && lassoPick.kind === "hex" && lassoPick.inMap) {
            addLassoPoint(lassoPick.q, lassoPick.r);
          }
          updateCursor();
          scheduleRender();
          return;
        }
        if (mode === "map-edit") {
          event.preventDefault();
          if (mapEditSubtoolOf(editTool) === "connectivity") {
            beginEdgeDrag(event);
            return;
          }
          beginPaint(event);
        }
        return;
      }
      if (event.button !== 0) {
        return;
      }
      // ★ §七：左键在所有编辑模式统一为"平移地图"（永不误改）；两处例外——区域编辑的边界小点、
      //   以及 T3 的连通性编辑线左键命中一条边 ⇒ **删边**（照 GSimulator）。
      if (mode === "region-edit") {
        var dot = boundaryDotAt(canvasPoint(event));
        if (dot) {
          dotDrag = dot;
          dotDragDirty = false;
          capturePointer(event.pointerId);
          updateCursor();
          return;
        }
      }
      if (mode === "map-edit" && mapEditSubtoolOf(editTool) === "connectivity") {
        var hit = edgeHitAt(canvasPoint(event));
        if (hit) {
          event.preventDefault();
          beginEdgeDelete(event, hit);
          return;
        }
      }
      dragging = true;
      dragMoved = false;
      dragStart = { x: event.clientX, y: event.clientY };
      viewStart = { scale: view.scale, tx: view.tx, ty: view.ty };
      capturePointer(event.pointerId);
      updateCursor();
    }

    function onPointerMove(event) {
      if (lassoActive) {
        var lassoPick = pickAt(canvasPoint(event));
        if (lassoPick && lassoPick.kind === "hex" && lassoPick.inMap) {
          addLassoPoint(lassoPick.q, lassoPick.r);
        }
        return;
      }
      if (dotDrag) {
        moveDotTo(canvasPoint(event));
        return;
      }
      if (painting) {
        if (edgeDrag) {
          edgeAt(canvasPoint(event));
        } else if (edgeDeleteDrag) {
          edgeDeleteAt(edgeHitAt(canvasPoint(event)));
        } else {
          paintAt(canvasPoint(event));
        }
        return;
      }
      if (dragging) {
        var dx = event.clientX - dragStart.x;
        var dy = event.clientY - dragStart.y;
        if (Math.abs(dx) + Math.abs(dy) > 3) {
          dragMoved = true;
        }
        view = { scale: viewStart.scale, tx: viewStart.tx + dx, ty: viewStart.ty + dy };
        scheduleRender();
        return;
      }
      if (!overview) {
        return;
      }
      var pick = pickAt(canvasPoint(event));
      canvas.style.cursor = pick.kind === "unit" ? "pointer" : mode === "region" ? "pointer" : "grab";
      canvas.title = pick.kind === "unit" ? app.text(pick.name) : "";
    }

    function onPointerUp(event) {
      if (lassoActive) {
        var lassoHexes = finishLasso();
        releasePointer(event.pointerId);
        updateCursor();
        if (lassoHexes.length) {
          onLassoCommit(lassoHexes);
        }
        return;
      }
      if (dotDrag) {
        var dragList = flushDotDrag();
        releasePointer(event.pointerId);
        updateCursor();
        if (dragList.length) {
          onDotDragCommit(dragList);
        }
        return;
      }
      if (painting) {
        painting = false;
        releasePointer(event.pointerId);
        updateCursor();
        if (edgeDrag) {
          var chain = edgeChainResult(edgeDrag.path);
          edgeDrag = null;
          setBrushHexes([]); // 连边预览只在这一拖里显示；成败都由宿主报状态
          onEdgeCommit(chain);
          return;
        }
        if (edgeDeleteDrag) {
          var deleteDrag = edgeDeleteDrag;
          edgeDeleteDrag = null;
          setBrushHexes([]);
          onEdgeDeleteCommit(deleteDrag.kind, Object.keys(deleteDrag.deleted));
          return;
        }
        var painted = brushList();
        if (painted.length) {
          onPaintCommit(painted);
        }
        return;
      }
      if (!dragging) {
        return;
      }
      dragging = false;
      releasePointer(event.pointerId);
      updateCursor();
      if (!dragMoved) {
        onSelect(pickAt(canvasPoint(event)));
      }
    }

    function onWheel(event) {
      event.preventDefault();
      var factor = Math.exp(-event.deltaY * ZOOM_WHEEL);
      view = zoomAt(view, canvasPoint(event), factor, MIN_SCALE, MAX_SCALE);
      // ★ 摊开门控吃 view.scale ⇒ 缩放后必须重算 px/py（否则"放大到格子够大才摊开"永不生效）。
      recomputeWorldPixels();
      updateZoomUi();
      scheduleRender();
    }

    function onPointerCancel() {
      if (lassoActive) {
        resetLasso();
      }
      if (dotDrag) {
        // 指针被系统取消：把已拖动出来的结果落定（丢弃会让屏幕与服务端不一致）。
        var cancelList = flushDotDrag();
        if (cancelList.length) {
          onDotDragCommit(cancelList);
        }
      }
      dragging = false;
      painting = false;
      edgeDrag = null; // 连边被打断 ⇒ 丢弃这一拖（宁可少一条边，也不落半条）
      edgeDeleteDrag = null; // 删边被打断 ⇒ 丢弃这一拖（不落半次删除）
      updateCursor();
    }

    function updateZoomUi() {
      var node = app.byId("zoom-level");
      if (node) {
        node.textContent = "缩放 " + view.scale.toFixed(2) + "×";
      }
    }

    var legendOverviewRef = null;
    var legendCounts = null;

    function terrainCounts() {
      if (legendOverviewRef === overview && legendCounts) {
        return legendCounts;
      }
      var counts = window.SimosBlocks.countsByTerrain(overview.blocks || []);
      legendOverviewRef = overview;
      legendCounts = counts;
      legendScans += 1;
      return counts;
    }

    function updateLegend() {
      var legend = app.byId("legend");
      if (!legend || !overview) {
        return;
      }
      var counts = terrainCounts();
      var types = (overview.terrainTypes || []).map(function (t) {
        return t && t.key ? t.key : t;
      });
      legend.textContent =
        "共 " +
        overview.hexCount +
        " 格 · " +
        (overview.regions ? overview.regions.length : 0) +
        " 区域 · " +
        cities.length +
        " 城市 · 单位 " +
        units.length +
        " · " +
        types
          .map(function (t) {
            return t + "=" + (counts[t] || 0);
          })
          .join("  ");
    }

    /** 当前高亮实际用到的区域色（去重，首次出现序）。 */
    function distinctColors() {
      var colors = [];
      Object.keys(highlightColorByKey).forEach(function (key) {
        var color = highlightColorByKey[key];
        if (color && colors.indexOf(color) < 0) {
          colors.push(color);
        }
      });
      return colors;
    }

    /** 当前高亮实际用到的透明度（去重，升序）；区域编辑下应有"焦点实 / 其它淡"两档。 */
    function distinctAlphas() {
      var alphas = [];
      Object.keys(highlightAlphaByKey).forEach(function (key) {
        var raw = highlightAlphaByKey[key];
        var value = raw === null || raw === undefined ? HIGHLIGHT_ALPHA : raw;
        if (alphas.indexOf(value) < 0) {
          alphas.push(value);
        }
      });
      return alphas.sort(function (a, b) {
        return a - b;
      });
    }

    function debug() {
      return {
        scale: view.scale,
        tx: view.tx,
        ty: view.ty,
        cellSize: cellSize,
        hexCount: overview ? overview.hexCount : 0,
        blockCount: blocks.length,
        blockVertexCount: blockVertexCount,
        blockRingCounts: blockRingCounts(),
        unmergedCount: unmergedCount,
        paintedRingCount: paintedRingCount,
        pass2Draws: pass2Draws,
        unitCount: units.length,
        // ★ F1：城市 / 图层 / 国家着色 / 决策人徽标的只读投影（验收与门禁可直接断言）。
        cityCount: cities.length,
        visibleCityCount: cityMarkerPlanOf().length,
        selectedCity: selectedCity,
        layerState: Object.assign({}, layerState),
        nationRegionCount: nationRegions.length,
        nationNameDraws: nationNameDraws,
        decisionMakerCount: decisionMakers.length,
        // ★ F1：GOV 辖区覆盖层条数与 hex 总数（逐项 hexes.length 求和；供验收/门禁断言）。
        govJurisdictionCount: govJurisdictions.length,
        govJurisdictionHexCount: govJurisdictions.reduce(function (sum, item) {
          return sum + item.hexes.length;
        }, 0),
        // ★ F2：热力层只读投影（无选中指标 ⇒ metric=null、count=0；供 worldViewDebug 同源转出）。
        heatmapMetric: heatmap ? heatmap.metric : null,
        heatmapCellCount: heatmap ? heatmap.cells.length : 0,
        colorByTerrain: Object.assign({}, colorByTerrain),
        fallbackColor: FALLBACK_COLOR,
        fallbackWarned: fallbackWarned,
        highlightHexCount: Object.keys(highlightColorByKey).length,
        highlightColors: distinctColors(),
        highlightAlphas: distinctAlphas(),
        // ★ M8 T9：按格取"这一格此刻画成什么"（颜色 + 透明度）——"淡色态"因此可**逐值断言**，
        //   不必采样像素，也不必把整张高亮表塞进 e2e 载荷。未高亮 ⇒ null。
        highlightAt: function (q, r) {
          var key = q + "_" + r;
          if (!Object.prototype.hasOwnProperty.call(highlightColorByKey, key)) {
            return null;
          }
          var raw = highlightAlphaByKey[key];
          return {
            color: highlightColorByKey[key],
            alpha: raw === null || raw === undefined ? HIGHLIGHT_ALPHA : raw,
          };
        },
        regionFallbackColor: REGION_FALLBACK_COLOR,
        // ★ M12：旗标已随 regionColor 搬到 hexcolor.js —— 走实时读，不取快照。
        regionFallbackWarned: hexColor.regionFallbackWarned,
        // ★ U1/U2：区域模式的地形压暗次数、区域名绘制条数（可断言"真的画了 / 真的没画"）。
        terrainDimAlpha: terrainDimAlpha(mode),
        dimPasses: dimPasses,
        // ★ M12：regionNamesEnabled 是可变绑定（宿主页切换开关时改写）⇒ 走 core 实时读，不取快照。
        regionNamesEnabled: window.SimosMapCore.regionNamesEnabled,
        regionNameDraws: regionNameDraws,
        selected: selected ? { q: selected.q, r: selected.r } : null,
        selectedUnit: selectedUnit,
        mode: mode,
        brushHexCount: Object.keys(brushKeys).length,
        brushOp: brushOp,
        draftHexCount: Object.keys(draftKeys).length,
        draftHexes: draftList(),
        painting: painting,
        lassoHexCount: Object.keys(lassoKeys).length,
        lassoOrderCount: lassoOrder.length,
        lassoActive: lassoActive,
        focusHexCount: Object.keys(focusKeys).length,
        focusBoundaryCount: focusBoundaryList().length,
        dotDragging: !!dotDrag,
        isWorkbench: isWorkbench,
        terrainRebuilds: terrainRebuilds,
        terrainBlits: terrainBlits,
        legendScans: legendScans,
        regionOutlineCount: regionOutlines.length,
        outlineRingCount: regionOutlines.reduce(function (sum, outline) {
          return sum + outline.rings.length;
        }, 0),
        outlineVertexCount: outlineVertexCount,
        terrainMode: perfConfig.terrainMode,
        routeCount: routes.length,
        routes: routes.map(function (route) {
          return {
            id: route.id,
            totalPoints: route.path.length,
            remainingPoints: remainingPath(
              route.path,
              route.movement.currentHex,
              route.movement.status
            ).length,
            status: route.movement.status,
            currentHex: route.movement.currentHex,
            nextHex: route.movement.nextHex,
            outlineColor: ROUTE_OUTLINE_COLOR,
            baseColor: ROUTE_BASE_COLOR,
            remainingColor: ROUTE_REMAINING_COLOR,
          };
        }),
      };
    }

    /** 右键：交给宿主判定是否消费（模式守卫在宿主里，T3 的只读保护点）。 */
    function onContextMenuEvent(event) {
      if (!overview) {
        return;
      }
      if (onContextMenu(pickAt(canvasPoint(event))) === true) {
        event.preventDefault();
      }
    }

    // ── 微基准（M9 T4 隔离实验；e2e/harness 用 page.evaluate 调用）──────────
    //   各段分别计时，返回原始毫秒样本数组（调用方算 p50）。不改渲染行为。

    function benchStages(n) {
      if (!overview) {
        return null;
      }
      var out = {
        terrain: [],
        highlights: [],
        borders: [],
        cities: [],
        units: [],
        total: [],
      };
      for (var i = 0; i < n; i++) {
        worldTransform();
        var t0 = performance.now();
        paintTerrain(ctx);
        out.terrain.push(performance.now() - t0);
        worldTransform();
        t0 = performance.now();
        paintHighlights(ctx);
        out.highlights.push(performance.now() - t0);
        worldTransform();
        t0 = performance.now();
        paintBorders(ctx);
        out.borders.push(performance.now() - t0);
        worldTransform();
        t0 = performance.now();
        drawCities();
        out.cities.push(performance.now() - t0);
        worldTransform();
        t0 = performance.now();
        drawUnits();
        out.units.push(performance.now() - t0);
        t0 = performance.now();
        render();
        out.total.push(performance.now() - t0);
      }
      return out;
    }

    function benchTerrainVariants(n) {
      if (!overview) {
        return null;
      }
      var out = { blocks: [] };
      for (var i = 0; i < n; i++) {
        worldTransform();
        var t0 = performance.now();
        paintTerrain(ctx);
        out.blocks.push(performance.now() - t0);
      }
      return out;
    }

    function benchChunkSweep(n) {
      return benchTerrainVariants(n);
    }

    canvas.addEventListener("contextmenu", onContextMenuEvent);
    canvas.addEventListener("pointerdown", onPointerDown);
    canvas.addEventListener("pointermove", onPointerMove);
    canvas.addEventListener("pointerup", onPointerUp);
    canvas.addEventListener("pointercancel", onPointerCancel);
    canvas.addEventListener("wheel", onWheel, { passive: false });

    return {
      benchStages: benchStages,
      benchTerrainVariants: benchTerrainVariants,
      benchChunkSweep: benchChunkSweep,
      perfConfig: perfConfig,
      canvas: canvas,
      setData: setData,
      setUnits: setUnits,
      setCombats: setCombats,
      // ★ F1：城市 / 图层 / 国家着色 / 决策人徽标的装载入口。
      setCities: setCities,
      setLayerState: setLayerState,
      setNationRegions: setNationRegions,
      // ★ F1：GOV 辖区覆盖层（切 target / 取数失败时调用方推空数组清空）。
      setGovJurisdictions: setGovJurisdictions,
      // ★ F2：热力层装载入口（null / 空 cells ⇒ 清层；只重绘，不取数）。
      setHeatmap: setHeatmap,
      setDecisionMakers: setDecisionMakers,
      setSelectedCity: setSelectedCity,
      cityPositionOf: cityPositionOf,
      setCellSize: setCellSize,
      setSelected: setSelected,
      setSelectedUnit: setSelectedUnit,
      setHighlightHexes: setHighlightHexes,
      setBrushHexes: setBrushHexes,
      brushHexes: brushList,
      setBrushOp: setBrushOp,
      setDraftHexes: setDraftHexes,
      draftHexes: draftList,
      setRegionOutlines: setRegionOutlines,
      setFocusHexes: setFocusHexes,
      regionOutlineRings: function () {
        return regionOutlines.map(function (outline) {
          return { id: outline.id, rings: outline.rings };
        });
      },
      clearFocusHexes: clearFocusHexes,
      focusHexes: focusHexList,
      focusBoundary: focusBoundaryList,
      boundaryDotAt: boundaryDotAt,
      lassoHexes: lassoHexList,
      lassoForTest: function (list) {
        var wall = {};
        (list || []).forEach(function (hex) {
          if (hex && hex.q !== undefined && hex.r !== undefined) {
            wall[keyOf(hex.q, hex.r)] = { q: hex.q, r: hex.r };
          }
        });
        return floodFillFromWall(wall);
      },
      hexExists: hexExists,
      setMode: setMode,
      setEditTool: setEditTool,
      fit: fit,
      computeFit: computeFit,
      // ★ 2026-09-24 可用性修复：选中/定位单位时把它居中并抬到可见缩放（见方法注释）。
      ensureUnitVisible: ensureUnitVisible,
      // ★ F1：城市/锚点定位（搜索定位入口；城市标记与单位标记的可见性口径同源）。
      ensureCityVisible: ensureCityVisible,
      resize: resize,
      render: render,
      pickAt: pickAt,
      screenPointOf: screenPointOf,
      positionOf: positionOf,
      parentOf: parentOf,
      formationOf: formationOf,
      terrainColor: terrainColor,
      debug: debug,
      view: function () {
        return { scale: view.scale, tx: view.tx, ty: view.ty };
      },
      setView: function (next) {
        view = { scale: next.scale, tx: next.tx, ty: next.ty };
        recomputeWorldPixels(); // 同上：scale 变了就要重算摊开
        updateZoomUi();
        scheduleRender();
      },
      isReady: function () {
        return !!overview;
      },
      /** ★ U1/U3 取色级证据钩子：读画布上某 CSS 像素（含 dpr）。 */
      pixelAt: function (cssX, cssY) {
        if (!canvas || !ctx) {
          return null;
        }
        var data = ctx.getImageData(Math.round(cssX * dpr), Math.round(cssY * dpr), 1, 1).data;
        return { r: data[0], g: data[1], b: data[2], a: data[3] };
      },
      /**
       * ★ U2：当前应画的区域名及其屏幕落点（只读投影，供 `regionNameDebug` 取色）。
       *
       * <p>★ 2026-09-24：与**绘制同源**（同一个 {@code regionNamePlan} 的可见性裁剪）——否则调试里数的条数
       * 与屏幕上真正画出来的会分叉（那正是"投影不是证据"的形态）。
       */
      regionNameLayouts: function () {
        if (
          !layerState.regionNames ||
          !regionNamesVisible(mode) ||
          view.scale < REGION_NAME_MIN_SCALE
        ) {
          return [];
        }
        var plan = regionNamePlan(
          host.overviewRegions || [],
          view,
          { width: cssW, height: cssH },
          REGION_NAME_VISIBLE_MARGIN,
          cellSize
        );
        return plan.map(function (label) {
          var screen = worldToScreen({ x: label.x, y: label.y }, view);
          return { text: label.text, screenX: screen.x, screenY: screen.y };
        });
      },
    };
  }

  window.SimosCreateRenderer = createRenderer;
})();
