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
  // ★ 2026-09-23 同格重叠单位纵向摊开：间距/半径口径来自 hexgeom（与 drawUnits 的圆点半径同源）
  var markerRadius = hexGeom.markerRadius;
  var stackSpacing = hexGeom.stackSpacing;
  var stackOffset = hexGeom.stackOffset;
  // ★ 2026-09-24 修正 1：标记按**军队**分组（同格、同军队根 ⇒ 一个标记）；纯函数在 hexgeom.js
  //   （两份宿主页都引它 ⇒ map.html 不会因缺 unitTree.js 而少一个函数）。
  var markerGroups = hexGeom.markerGroups;
  // hexcolor.js
  var FALLBACK_COLOR = hexColor.FALLBACK_COLOR;
  var REGION_FALLBACK_COLOR = hexColor.REGION_FALLBACK_COLOR;
  var regionColor = hexColor.regionColor;
  var withAlpha = hexColor.withAlpha;
  // regionShape.js
  var regionBoundaryRings = regionShape.regionBoundaryRings;
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
  var regionLabelLayout = core.regionLabelLayout;
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
    var markers = [];
    var routes = []; // 在途路线（M7b T2）：{id,movement,path:[{q,r}…]}
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
     * 预计算**标记**的世界像素位置（px/py）。★ 2026-09-24 修正 1：
     * 1) 先由 `markerGroups(units)`（hexgeom 纯函数）把单位**按格、再按军队根**分组 ⇒ 每组一个标记；
     *    ——首都格（根 + 各兵种同格）只出 1 个标记（代表 = 根），分遣队单独一格不会被藏掉。
     * 2) 再把**同格的不同军队组**纵向摊开（stackOffset；格太小则保持重叠），避免选中时重叠。
     * 摊开间距记在 `marker.stackSpacing`（0=未摊开），供 pickAt 收缩命中半径。
     * ★ 只改 markers 的 px/py ⇒ pickAt / drawUnits 自动跟随（不另造一份坐标）。
     */
    function recomputeWorldPixels() {
      markers = markerGroups(units);
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
      keys.forEach(function (key) {
        var list = groups[key];
        // ★ 摊开门控吃**屏幕上**的格高（cellSize 是世界单位且在**工作台恒定**，随缩放变的是 view.scale）
        //   ⇒ 必须相乘，否则缩放永远不会改变"摊不摊开"。作用对象是**军队组**（list.length = 该格的组数）。
        var screenCell = cellSize * view.scale;
        var spacing = stackSpacing(list.length, cellSize, screenCell);
        list.forEach(function (m, index) {
          var p = hexToPixel(m.at.q, m.at.r, cellSize);
          m.px = p.x;
          m.py = p.y + stackOffset(index, list.length, cellSize, screenCell);
          m.stackSpacing = spacing;
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

    /** 单位当前渲染位置（M7 T7 路线锚点）；未载入 ⇒ null。 */
    function positionOf(id) {
      for (var i = 0; i < units.length; i++) {
        if (units[i].id === id) {
          return { q: units[i].position.q, r: units[i].position.r };
        }
      }
      return null;
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

    function drawCities() {
      (overview.cities || []).forEach(function (city) {
        if (!city.at) {
          return;
        }
        var p = hexToPixel(city.at.q, city.at.r, cellSize);
        var cx = p.x + cellSize * 0.42;
        var cy = p.y - cellSize * 0.42;
        ctx.fillStyle = "#f0d27a";
        ctx.fillRect(cx - 4, cy - 4, 8, 8);
        ctx.strokeStyle = "#0d1015";
        ctx.lineWidth = 1 / view.scale;
        ctx.strokeRect(cx - 4, cy - 4, 8, 8);
      });
    }

    function shortId(id) {
      var text = app.text(id);
      return text.length > 4 ? text.slice(0, 4) : text;
    }

    /**
     * ★ 2026-09-24 修正 1：画的是**军队标记**（markers），不是逐个单位。
     * 组内**任一**单位被选中 ⇒ 高亮该组标记（选中的是某个兵种时，它所属军队的那个标记也亮）。
     */
    function isMarkerSelected(marker) {
      if (selectedUnit === null || selectedUnit === undefined) {
        return false;
      }
      return marker.member.indexOf(String(selectedUnit)) >= 0;
    }

    function drawUnits() {
      var radius = markerRadius(cellSize);
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      markers.forEach(function (m) {
        ctx.beginPath();
        ctx.arc(m.px, m.py, radius, 0, Math.PI * 2);
        ctx.fillStyle = "#e8503a";
        ctx.fill();
        ctx.strokeStyle = "#ffffff";
        ctx.lineWidth = 2 / view.scale;
        ctx.stroke();
        if (isMarkerSelected(m)) {
          ctx.beginPath();
          ctx.arc(m.px, m.py, radius + 4 / view.scale, 0, Math.PI * 2);
          ctx.strokeStyle = "#4ea1ff";
          ctx.lineWidth = 2.5 / view.scale;
          ctx.stroke();
        }
        ctx.fillStyle = "#ffffff";
        ctx.font = Math.max(9, Math.round(radius)) + "px sans-serif";
        ctx.fillText(shortId(m.leadId), m.px, m.py);
      });
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
     */
    function paintRegionNames(targetCtx) {
      regionNameDraws = 0;
      if (!regionNamesVisible(mode) || view.scale < REGION_NAME_MIN_SCALE) {
        return;
      }
      var drawn = 0;
      (host.overviewRegions || []).forEach(function (region) {
        var layout = regionLabelLayout(region, view.scale);
        if (!layout) {
          return;
        }
        var point = hexToPixel(layout.q, layout.r, cellSize);
        targetCtx.font = "bold " + layout.fontSize + "px sans-serif";
        targetCtx.textAlign = "center";
        targetCtx.textBaseline = "middle";
        targetCtx.lineWidth = 3 / view.scale;
        targetCtx.strokeStyle = "rgba(0, 0, 0, 0.85)";
        targetCtx.strokeText(layout.text, point.x, point.y);
        targetCtx.fillStyle = "#ffffff";
        targetCtx.fillText(layout.text, point.x, point.y);
        drawn += 1;
      });
      regionNameDraws = drawn;
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
      // ★ U1：区域模式先把地形压暗（屏幕空间全覆盖），再叠区域填充/边界 ⇒ 区域成为视觉主体。
      paintTerrainDim(ctx, app.getState().mode);
      paintHighlights(ctx);
      paintRegionOutlines(ctx);
      // ★ U2：区域名压在填充/边界之上、单位之下。
      paintRegionNames(ctx);
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
        // ★ 2026-09-23：摊开的标记命中半径收缩到**不超过半间距** ⇒ 不误伤纵向相邻的邻居；
        //   未摊开（stackSpacing=0，含大小格下的重叠态）保持原口径。
        var hitRadius = m.stackSpacing > 0 ? Math.min(baseHitRadius, m.stackSpacing / 2) : baseHitRadius;
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
        " 区域 · 单位 " +
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
      resize: resize,
      render: render,
      pickAt: pickAt,
      screenPointOf: screenPointOf,
      positionOf: positionOf,
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
      /** ★ U2：当前应画的区域名及其屏幕落点（只读投影，供 `regionNameDebug` 取色）。 */
      regionNameLayouts: function () {
        var out = [];
        if (!regionNamesVisible(mode) || view.scale < REGION_NAME_MIN_SCALE) {
          return out;
        }
        (host.overviewRegions || []).forEach(function (region) {
          var layout = regionLabelLayout(region, view.scale);
          if (!layout) {
            return;
          }
          var screen = worldToScreen(hexToPixel(layout.q, layout.r, cellSize), view);
          out.push({ text: layout.text, screenX: screen.x, screenY: screen.y });
        });
        return out;
      },
    };
  }

  window.SimosCreateRenderer = createRenderer;
})();
