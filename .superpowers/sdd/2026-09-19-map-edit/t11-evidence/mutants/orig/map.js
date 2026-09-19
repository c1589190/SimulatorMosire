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

  var BASE_CELL = 34; // 世界坐标的基准格边长（px）
  var FALLBACK_COLOR = "#ff00ff"; // 词表外地形的兜底色（品红；刻意不像任何地形）
  var REGION_FALLBACK_COLOR = "#00e5ff"; // 区域色缺失/非法时的兜底色（青色；与地形兜底色不同）
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
  var HEX_COLOR_RE = /^#[0-9a-fA-F]{6}$/;
  var regionFallbackWarned = false;
  var MIN_SCALE = 0.03;
  var MAX_SCALE = 12;
  var ZOOM_WHEEL = 0.0016; // 滚轮 deltaY → 缩放指数系数
  var OLD_PAGE_CANVAS_HEIGHT = 620; // 旧页 /map 的固定画布高度（工作台铺满视口，不用固定值）
  var FIT_PAD = 24; // fitView 的世界四周留白（初始适配与"回到世界中心"共用）

  // ★ M8-R：pointy-top 轴向坐标的六邻方向（E/NE/NW/W/SW/SE）。
  //   套索补点（hexLine）、flood fill 的连通判定、边界小点（"有邻格不在区域内"）三处共用。
  var DIR_VECTORS = [
    [1, 0],
    [1, -1],
    [0, -1],
    [-1, 0],
    [-1, 1],
    [0, 1],
  ];

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

  function clamp(value, lo, hi) {
    return value < lo ? lo : value > hi ? hi : value;
  }

  /** 纯函数：RegionMeta.color 合法（#RRGGBB）则用它，否则回兜底色并经 warnFn 报一次（不静默）。 */
  function resolveRegionColor(meta, warnFn) {
    var color = meta && meta.color;
    if (typeof color === "string" && HEX_COLOR_RE.test(color)) {
      return color;
    }
    if (typeof warnFn === "function") {
      warnFn(color);
    }
    return REGION_FALLBACK_COLOR;
  }

  /** 取区域填充色；非法/缺失时**全页只 warn 一次**（T4 地形色同型纪律）。 */
  function regionColor(meta) {
    return resolveRegionColor(meta, function (raw) {
      if (!regionFallbackWarned) {
        regionFallbackWarned = true;
        window.console.warn(
          "[SimosMap] 区域色非法或缺失（" + JSON.stringify(raw) + "），使用兜底色 " + REGION_FALLBACK_COLOR
        );
      }
    });
  }

  /** #RRGGBB → rgba(r,g,b,alpha)。 */
  function withAlpha(hex, alpha) {
    var r = parseInt(hex.slice(1, 3), 16);
    var g = parseInt(hex.slice(3, 5), 16);
    var b = parseInt(hex.slice(5, 7), 16);
    return "rgba(" + r + ", " + g + ", " + b + ", " + alpha + ")";
  }

  /** 顶点朝上（pointy-top）的轴向坐标 → 世界像素：与旧 GSimulator hex-math 同型。 */
  function hexToPixel(q, r, size) {
    return {
      x: size * (Math.sqrt(3) * q + (Math.sqrt(3) / 2) * r),
      y: size * (1.5 * r),
    };
  }

  function hexCorners(cx, cy, size) {
    var pts = [];
    for (var i = 0; i < 6; i++) {
      var a = (Math.PI / 180) * (60 * i - 30);
      pts.push({ x: cx + size * Math.cos(a), y: cy + size * Math.sin(a) });
    }
    return pts;
  }

  function cornerKey(x, y) {
    return Math.round(x * 10000) + "," + Math.round(y * 10000);
  }

  /**
   * 区域 hex 集合的**边环**（精确的逐 hex 台阶多边形，单位＝「格边长=1」的世界坐标）。
   * 每条格边被两个同区域 hex 共享 ⇒ 内部边成对出现被抵消，剩下的即成环。可能多环（多块/带洞）。
   *
   * <p>★ 顶点坐标与 Java 的 `HexVertex`（`x = u·√3/2, y = w/2`）逐值一致：`hexCorners` 的第 i 个角
   * （`60°i − 30°`）恰对应 `HexVertex.at(hex, i)`，故本函数结果 == `RegionBoundary.of(hexes)`。
   */
  function regionBoundaryRings(hexList) {
    var pointOf = {};
    var edgeList = [];
    var edgeIndex = {};
    (hexList || []).forEach(function (hex) {
      if (!hex || hex.q === undefined || hex.r === undefined) {
        return;
      }
      var center = hexToPixel(hex.q, hex.r, 1);
      var corners = hexCorners(center.x, center.y, 1);
      for (var i = 0; i < 6; i++) {
        var a = corners[i];
        var b = corners[(i + 1) % 6];
        var ka = cornerKey(a.x, a.y);
        var kb = cornerKey(b.x, b.y);
        pointOf[ka] = { x: Math.round(a.x * 10000) / 10000, y: Math.round(a.y * 10000) / 10000 };
        pointOf[kb] = { x: Math.round(b.x * 10000) / 10000, y: Math.round(b.y * 10000) / 10000 };
        var canon = ka < kb ? ka + "|" + kb : kb + "|" + ka;
        if (Object.prototype.hasOwnProperty.call(edgeIndex, canon)) {
          edgeList[edgeIndex[canon]] = null;
          delete edgeIndex[canon];
        } else {
          edgeIndex[canon] = edgeList.length;
          edgeList.push({ a: ka, b: kb });
        }
      }
    });
    var byCorner = {};
    edgeList.forEach(function (edge, index) {
      if (!edge) {
        return;
      }
      (byCorner[edge.a] || (byCorner[edge.a] = [])).push(index);
      (byCorner[edge.b] || (byCorner[edge.b] = [])).push(index);
    });
    var used = {};
    var rings = [];
    edgeList.forEach(function (edge, startIndex) {
      if (!edge || used[startIndex]) {
        return;
      }
      used[startIndex] = true;
      var ringKeys = [edge.a, edge.b];
      var current = edge.b;
      var guard = 0;
      while (guard++ < 200000) {
        var incident = byCorner[current] || [];
        var next = -1;
        for (var i = 0; i < incident.length; i++) {
          if (!used[incident[i]] && edgeList[incident[i]]) {
            next = incident[i];
            break;
          }
        }
        if (next < 0) {
          break;
        }
        used[next] = true;
        var nextEdge = edgeList[next];
        current = nextEdge.a === current ? nextEdge.b : nextEdge.a;
        if (current === ringKeys[0]) {
          break;
        }
        ringKeys.push(current);
      }
      if (ringKeys.length >= 3) {
        rings.push(
          ringKeys.map(function (key) {
            return pointOf[key];
          })
        );
      }
    });
    return rings;
  }


  function hexRound(fq, fr) {
    var fs = -fq - fr;
    var q = Math.round(fq);
    var r = Math.round(fr);
    var s = Math.round(fs);
    if (Math.abs(q - fq) > Math.abs(r - fr) && Math.abs(q - fq) > Math.abs(s - fs)) {
      q = -r - s;
    } else if (Math.abs(r - fr) > Math.abs(s - fs)) {
      r = -q - s;
    }
    return { q: q, r: r };
  }

  /** 世界像素 → 轴向坐标（size = 世界格边长）。 */
  function pixelToHex(px, py, size) {
    var fq = ((Math.sqrt(3) / 3) * px - (1 / 3) * py) / size;
    var fr = ((2 / 3) * py) / size;
    return hexRound(fq, fr);
  }

  /** 轴向坐标的六邻。 */
  function axialNeighbors(q, r) {
    var out = [];
    for (var i = 0; i < DIR_VECTORS.length; i++) {
      out.push({ q: q + DIR_VECTORS[i][0], r: r + DIR_VECTORS[i][1] });
    }
    return out;
  }

  /** 轴向坐标距离（相邻 ⇔ 1）。 */
  function axialDistance(aq, ar, bq, br) {
    var dq = aq - bq;
    var dr = ar - br;
    return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
  }

  /** 两个轴向坐标之间的直线格列（cube 线性插值 + hexRound 取整）——套索补点用，避免快速拖动漏格。 */
  function hexLine(aq, ar, bq, br) {
    var n = Math.round(axialDistance(aq, ar, bq, br));
    if (n <= 0) {
      return [{ q: aq, r: ar }];
    }
    var out = [];
    for (var i = 0; i <= n; i++) {
      var t = i / n;
      out.push(hexRound(aq + (bq - aq) * t, ar + (br - ar) * t));
    }
    return out;
  }

  /** 世界 → 屏幕（CSS px）：screen = world × scale + t。 */
  function worldToScreen(point, view) {
    return { x: point.x * view.scale + view.tx, y: point.y * view.scale + view.ty };
  }

  /** 屏幕（CSS px）→ 世界：world = (screen − t) / scale。 */
  function screenToWorld(point, view) {
    return { x: (point.x - view.tx) / view.scale, y: (point.y - view.ty) / view.scale };
  }

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

  /** 以 anchor（屏幕 CSS 坐标）为锚缩放：锚下的世界点保持不动。 */
  function zoomAt(view, anchor, factor, minScale, maxScale) {
    var next = clamp(view.scale * factor, minScale, maxScale);
    var world = screenToWorld(anchor, view);
    return { scale: next, tx: anchor.x - world.x * next, ty: anchor.y - world.y * next };
  }

  /** 让世界包围盒 fit 进 width×height（CSS px），四周留 pad。 */
  function fitView(bounds, width, height, pad) {
    if (!bounds || bounds.maxX < bounds.minX || bounds.maxY < bounds.minY) {
      return { scale: 1, tx: 0, ty: 0 };
    }
    var bw = bounds.maxX - bounds.minX + pad * 2;
    var bh = bounds.maxY - bounds.minY + pad * 2;
    var scale = clamp(Math.min(width / bw, height / bh), MIN_SCALE, MAX_SCALE);
    var cx = (bounds.minX + bounds.maxX) / 2;
    var cy = (bounds.minY + bounds.maxY) / 2;
    return { scale: scale, tx: width / 2 - cx * scale, ty: height / 2 - cy * scale };
  }

  // ── 渲染器工厂 ────────────────────────────────────────────────────────

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
    // ★ M8 T11：连边松手回调（edges = 规范边键列表，path = 原始轨迹 ⇒ 无相邻段时宿主据此给提示）。
    var onEdgeCommit = opts.onEdgeCommit || function () {};

    var cellSize = opts.cellSize || BASE_CELL;
    var view = { scale: 1, tx: 0, ty: 0 };
    var overview = null;
    var blocks = []; // 权威地形块（M9 T13）：{id,terrain,hexCount,boundaries:[环…]}，顶点为「格边长=1」的世界坐标
    var units = []; // 预计算世界坐标的单位：{id,name,position,px,py}
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
    var edgeDrag = null; // {path:[{q,r}…]}：右键拖动连边时的**原始轨迹**（相邻性护栏在 edgeChainEdges 里）

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

    function recomputeWorldPixels() {
      units.forEach(function (u) {
        var p = hexToPixel(u.position.q, u.position.r, cellSize);
        u.px = p.x;
        u.py = p.y;
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
          var p = hexToPixel(u.position.q, u.position.r, cellSize);
          return {
            id: u.id,
            name: u.name,
            position: u.position,
            px: p.x,
            py: p.y,
          };
        });
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
      editTool = next === "river" || next === "road" || next === "randomize" ? next : "terrain";
      edgeDrag = null;
      scheduleRender();
    }

    function computeFit() {
      return fitView(worldBounds(), cssW, cssH, FIT_PAD);
    }

    function fit() {
      view = computeFit();
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

    function drawUnits() {
      var radius = Math.max(6, cellSize * 0.3);
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      units.forEach(function (u) {
        ctx.beginPath();
        ctx.arc(u.px, u.py, radius, 0, Math.PI * 2);
        ctx.fillStyle = "#e8503a";
        ctx.fill();
        ctx.strokeStyle = "#ffffff";
        ctx.lineWidth = 2 / view.scale;
        ctx.stroke();
        if (u.id === selectedUnit) {
          ctx.beginPath();
          ctx.arc(u.px, u.py, radius + 4 / view.scale, 0, Math.PI * 2);
          ctx.strokeStyle = "#4ea1ff";
          ctx.lineWidth = 2.5 / view.scale;
          ctx.stroke();
        }
        ctx.fillStyle = "#ffffff";
        ctx.font = Math.max(9, Math.round(radius)) + "px sans-serif";
        ctx.fillText(shortId(u.id), u.px, u.py);
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
      paintHighlights(ctx);
      paintRegionOutlines(ctx);
      paintDraft(ctx);
      paintLasso(ctx);
      paintBoundaryDots(ctx);
      paintBrush(ctx);
      paintLeftover();

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
      var hitRadius = Math.max(cellSize * 0.36, 10 / view.scale);
      for (var i = units.length - 1; i >= 0; i--) {
        var u = units[i];
        var dx = world.x - u.px;
        var dy = world.y - u.py;
        if (dx * dx + dy * dy <= hitRadius * hitRadius) {
          return {
            kind: "unit",
            id: u.id,
            name: u.name,
            q: u.position.q,
            r: u.position.r,
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
          if (editTool === "river" || editTool === "road") {
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
      // ★ §七：左键在所有编辑模式统一为"平移地图"（永不误改）；唯一例外是抓住编辑手柄——
      //   区域编辑下命中边界小点 ⇒ 拖点（不是平移）。
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
          var path = edgeDrag.path;
          edgeDrag = null;
          var edges = edgeChainEdges(path);
          setBrushHexes([]); // 连边预览只在这一拖里显示；成败都由宿主报状态
          onEdgeCommit(edges, path);
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
        regionFallbackColor: REGION_FALLBACK_COLOR,
        regionFallbackWarned: regionFallbackWarned,
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
        updateZoomUi();
        scheduleRender();
      },
      isReady: function () {
        return !!overview;
      },
    };
  }

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
    regionFocusColor: null,
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

  /** 按状态机的 highlightRegions 懒拉区域 hex 集合（spec §3.3：点选时才拉，避免 19441 格爆载荷）。 */
  async function reloadHighlights() {
    var ids = app.getState().highlightRegions || [];
    if (!ids.length) {
      active.setHighlightHexes([]);
      active.setRegionOutlines([]);
      return;
    }
    var entries = [];
    var outlines = [];
    for (var i = 0; i < ids.length; i++) {
      var region = await fetchRegionCached(ids[i]);
      var color = regionColor(region.meta);
      (region.hexes || []).forEach(function (h) {
        entries.push({ key: h.q + "_" + h.r, color: color });
      });
      outlines.push({ id: ids[i], color: color, alpha: 1, hexes: region.hexes || [] });
    }
    active.setHighlightHexes(entries);
    active.setRegionOutlines(outlines);
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
   * 同时维护 `body[data-region-focus]` 与 debug 用的 faded 区域集合（可断言）。
   */
  async function reloadRegionEditHighlight() {
    if (app.getState().mode !== "region-edit") {
      return;
    }
    var overview;
    try {
      overview = await api.cachedMapOverview(app.target());
    } catch (e) {
      return;
    }
    var regions = (overview && overview.regions) || [];
    var focus = host.regionFocus;
    // ★ 焦点区域**先入**：setHighlightHexes 对同一 hex"先者胜"，否则与别的区域重叠的焦点格会被淡色盖掉。
    var ordered = [];
    regions.forEach(function (region) {
      if (region && region.id === focus) {
        ordered.push(region);
      }
    });
    regions.forEach(function (region) {
      if (region && region.id !== focus) {
        ordered.push(region);
      }
    });
    var entries = [];
    var outlines = [];
    var faded = [];
    for (var i = 0; i < ordered.length; i++) {
      var region = ordered[i];
      if (!region || region.id === null || region.id === undefined) {
        continue;
      }
      var detail = await fetchRegionCached(region.id);
      var base = regionColor(detail.meta);
      var isFocus = region.id === focus;
      var color = isFocus ? base : fadeRegionColor(base);
      var alpha = isFocus ? REGION_FOCUS_ALPHA : REGION_FADE_ALPHA;
      if (!isFocus) {
        faded.push(region.id);
      }
      (detail.hexes || []).forEach(function (h) {
        entries.push({ key: h.q + "_" + h.r, color: color, alpha: alpha });
      });
      // ★ M8-S §九：边框只画区域边界（**精确**的逐 hex 外缘闭合环）；焦点实、其它淡。
      outlines.push({
        id: region.id,
        color: color,
        alpha: isFocus ? 1 : 0.45,
        hexes: detail.hexes || [],
      });
    }
    if (app.getState().mode !== "region-edit") {
      return;
    }
    host.regionFaded = faded;
    active.setHighlightHexes(entries);
    active.setRegionOutlines(outlines);
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
    if (host.lastMode !== state.mode) {
      host.lastMode = state.mode;
      active.setBrushHexes([]);
      if (state.mode !== "unit") {
        resetRoute();
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
    var selId = selectedUnitId();
    // ★ M7e T1（用户裁定，§2「一下选中、两下取消」）：左键点**已选中**的单位标记 ⇒ 取消移动；
    //   未选中则只是选中它。不点单位、点已选中单位**当前所在格**同样取消移动。
    if (pick.kind === "unit") {
      if (mode === "unit" && selId === pick.id) {
        cancelRouteFor(pick.id);
        return;
      }
      app.setSelection({ kind: "unit", id: pick.id });
      if (mode === "unit") {
        resetRoute();
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
        cancelRouteFor(selId);
        return;
      }
    }
    if (mode === "unit" && selId && host.routeMode) {
      appendRoutePoint(pick.q, pick.r);
      return; // 保持单位选中：继续加路线点
    }
    // ★ M7c T1（用户裁定）：左键点格**不再瞬移**（unit.PlaceAt 已从本路径移除）——落到常规 hex 选中；
    //   移动只由右键发起（handleContextMenu → A* → unit.PlanRoute）。
    app.setSelection({ kind: "hex", q: pick.q, r: pick.r });
    if (mode === "region") {
      selectRegionOfHex(pick.q, pick.r);
    }
  }

  /** 区域查看模式：点格 ⇒ 该格所属区域高亮（区域列表/标签分组归 T6）。 */
  async function selectRegionOfHex(q, r) {
    try {
      var body = await api.mapHex(q, r, app.target());
      var regionIds = Array.isArray(body.regions) ? body.regions : [];
      if (regionIds.length) {
        app.setHighlightRegions(regionIds);
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

  /** 选一个地图编辑工具：切换可见控件组 + 重设右键分派（渲染器侧）+ 恢复该工具的选区。 */
  function selectMapEditTool(value) {
    var tool = value === "river" || value === "road" || value === "randomize" ? value : "terrain";
    host.mapEditTool = tool;
    if (active && active.setEditTool) {
      active.setEditTool(tool);
    }
    var terrainControls = app.byId("terrain-tool-controls");
    if (terrainControls) {
      terrainControls.hidden = tool !== "terrain";
    }
    var edgeControls = app.byId("edge-controls");
    if (edgeControls) {
      edgeControls.hidden = tool !== "river" && tool !== "road";
    }
    var randomizeControls = app.byId("randomize-controls");
    if (randomizeControls) {
      randomizeControls.hidden = tool !== "randomize";
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
      setMapEditToolStatus("工具：地形刷 —— 右键拖动涂抹；左键=平移地图。", "muted");
    } else if (tool === "randomize") {
      setMapEditToolStatus("工具：圈选随机化 —— 右键拖动圈选；左键=平移地图。", "muted");
    } else {
      setMapEditToolStatus(
        "工具：" +
          (tool === "river" ? "河流" : "道路") +
          " —— 右键拖动连起相邻两格（一条 map.SetEdge）；左键=平移地图。",
        "muted"
      );
      renderEdgeControls();
    }
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

  /** 连边松手：**先过护栏**（未选 mode ⇒ 一条命令都不发），再一次 app.writeCommand。 */
  async function commitEdge(edges, path) {
    active.setBrushHexes([]);
    if (host.mapEditBusy) {
      return null;
    }
    var modeState = edgeModeState(edgeModeValue());
    if (!modeState.ok) {
      // ★ Q2 的 UI 侧：**不预选、不兜默认**——用户没选就一个字节都不发。
      setWarning("edge-mode-warning", "未选 replace/merge：右键拖动不会发出任何写命令。");
      setEdgeStatus(
        "未选连通性语义 ⇒ **未发出任何写命令**（本次拖动 " +
          edges.length +
          " 条边已丢弃；轨迹 " +
          ((path && path.length) || 0) +
          " 格）。",
        "warn"
      );
      return null;
    }
    if (!edges || !edges.length) {
      setEdgeStatus(
        "非相邻的两格连不成边 ⇒ **未发出任何写命令**（轨迹 " + ((path && path.length) || 0) + " 格）。",
        "warn"
      );
      return null;
    }
    var kind = host.mapEditTool === "road" ? "road" : "river";
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
        "已改 " + kind + " " + edges.length + " 条边（" + modeState.mode + "，一条命令，head 已前进）",
        "ok"
      );
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
    // ★ M8 T11：工具单选组（地形刷 / 河流 / 道路 / 圈选随机化）。
    var tools = app.byId("map-edit-tools");
    if (tools) {
      Array.prototype.forEach.call(
        tools.querySelectorAll('input[name="map-edit-tool"]'),
        function (input) {
          input.addEventListener("change", function () {
            if (input.checked) {
              selectMapEditTool(input.value);
            }
          });
        }
      );
    }
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
    selectMapEditTool(host.mapEditTool);
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
   * 右键拖动的**轨迹** → 规范边键列表：相邻的两点连成一条边，**不相邻 ⇒ 断链重起**（那条不产出）。
   * 返回去重后的数组，顺序 = 首次出现序（一条拖动能连多段 ⇒ 一条 `map.SetEdge`）。
   */
  function edgeChainEdges(points) {
    var out = [];
    var seen = {};
    var prev = null;
    (points || []).forEach(function (point) {
      if (!point || point.q === undefined || point.r === undefined) {
        prev = null;
        return;
      }
      if (prev && isAdjacent(prev, point)) {
        var key = edgeKeyOf(prev, point);
        if (key && !seen[key]) {
          seen[key] = true;
          out.push(key);
        }
      }
      prev = { q: point.q, r: point.r };
    });
    return out;
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

  function setEditStatus(message, tone) {
    app.statusMessage(app.byId("unit-edit-status"), message, tone);
  }

  function selectedUnitId() {
    var selection = app.getState().selection;
    return selection && selection.kind === "unit" ? selection.id : null;
  }

  function updateRouteButtons() {
    var hasUnit = !!selectedUnitId();
    var send = app.byId("unit-route-send");
    var clear = app.byId("unit-route-clear");
    if (send) {
      send.disabled = !hasUnit || host.routePath.length < 1 || host.editBusy;
    }
    if (clear) {
      clear.disabled = host.routePath.length < 1;
    }
  }

  function renderUnitEditor(state) {
    var id = selectedUnitId();
    var selectedNode = app.byId("unit-edit-selected");
    if (selectedNode) {
      selectedNode.textContent = id ? "选中单位：" + id : "未选中单位";
    }
    ["unit-reparent", "unit-strength", "unit-disband", "unit-route-toggle"].forEach(function (buttonId) {
      var node = app.byId(buttonId);
      if (node) {
        node.disabled = !id;
      }
    });
    var routeNode = app.byId("unit-route-preview");
    if (routeNode) {
      routeNode.textContent =
        "路线：" + (host.routePath.length ? host.routePath.map(coordText).join(" → ") : "（未选）");
    }
    updateRouteButtons();
  }

  function resetRoute() {
    host.routePath = [];
    renderUnitEditor(app.getState());
  }

  function appendRoutePoint(q, r) {
    var point = { q: q, r: r };
    var anchor = host.routePath.length
      ? host.routePath[host.routePath.length - 1]
      : active.positionOf(selectedUnitId());
    if (!anchor) {
      setEditStatus("先点选一个单位，再点相邻格下路线。", "warn");
      return;
    }
    if (!isAdjacent(anchor, point)) {
      setEditStatus("路线必须逐格相邻：" + coordText(anchor) + " 与 " + coordText(point) + " 不相邻", "warn");
      return;
    }
    if (host.routePath.some(function (p) {
      return p.q === q && p.r === r;
    })) {
      setEditStatus("路线不得有重复格：" + coordText(point), "warn");
      return;
    }
    host.routePath.push(point);
    setEditStatus("已加路线点 " + coordText(point) + "（共 " + host.routePath.length + " 格）", "muted");
    renderUnitEditor(app.getState());
  }

  /** 路线式移动：waypoints = [单位当前位置, ...逐格点列]（起点必须==当前位置，PlanRoute 域规则）。 */
  async function submitRoute() {
    var id = selectedUnitId();
    if (host.editBusy || !id || host.routePath.length < 1) {
      return null;
    }
    host.editBusy = true;
    setEditStatus("下路线 " + id + " …", "muted");
    var waypoints;
    try {
      var unit = await api.unit(id, app.target());
      if (!unit.position) {
        throw new Error("单位 " + id + " 当前没有位置，无法下路线");
      }
      waypoints = [{ q: unit.position.q, r: unit.position.r }].concat(host.routePath);
    } catch (e) {
      host.editBusy = false;
      setEditStatus("下路线失败：" + (e.message || e), "err");
      return null;
    }
    var result = await app.writeCommand("unit.PlanRoute", { id: id, waypoints: waypoints });
    host.editBusy = false;
    if (result.ok) {
      resetRoute();
      setEditStatus("已下路线 " + id + "（" + (waypoints.length - 1) + " 格）—— 点「创建节点」推进时间，单位才会出发", "ok");
    } else {
      setEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    return result;
  }

  /**
   * 右键寻路移动（M7b T3，S3 的 HoI4 语义）：**仅** `unit` 模式 + 已选中单位时消费右键；服务端 A* 算路后经
   * `app.writeCommand("unit.PlanRoute", …)` 提交（**替换**原路线）。其它模式/未选单位 ⇒ 返回 false（不 preventDefault、
   * 更不发任何写请求——只读模式不得被污染，R8）。
   */
  function handleContextMenu(pick) {
    // ★ §七 判据 7：区域编辑（套索/逐格）与地图编辑（刷地形）的右键由 pointer 事件消费——
    //   这里只抑制原生菜单，绝不落到 unit.PlanRoute，也不改选中态。常规 / 单位移动编辑的右键行为**不变**。
    var contextMode = app.getState().mode;
    if (contextMode === "region-edit" || contextMode === "map-edit") {
      return true;
    }
    // ★ M7e T1（用户原话）：右键点**空白/图外/无格** ⇒ 取消选中；**不发任何写、不清路线**（路线归左键取消）。
    if (!pick || !pick.inMap) {
      app.setSelection(null);
      return true;
    }
    if (app.getState().mode !== "unit") {
      return false;
    }
    var id = selectedUnitId();
    if (!id) {
      return false;
    }
    submitPathRoute(id, pick.q, pick.r);
    return true;
  }

  /** 右键寻路：GET /api/map/path（只读）⇒ reachable 才发 unit.PlanRoute；不可达/已在目标格 ⇒ 明确提示、不发写。 */
  async function submitPathRoute(id, q, r) {
    if (host.editBusy) {
      return null;
    }
    host.editBusy = true;
    setEditStatus("寻路 " + id + " → " + coordText({ q: q, r: r }) + " …", "muted");
    var body;
    try {
      body = await api.mapPath(id, q, r, app.target());
    } catch (e) {
      host.editBusy = false;
      setEditStatus("寻路失败：" + (e.message || e), "err");
      return null;
    }
    var path = (body && body.path) || [];
    if (!body || !body.reachable || path.length < 1) {
      host.editBusy = false;
      setEditStatus("不可达：" + id + " → " + coordText({ q: q, r: r }), "warn");
      return null;
    }
    if (path.length < 2) {
      host.editBusy = false;
      setEditStatus("已在目标格 " + coordText({ q: q, r: r }) + "（未改路线）", "muted");
      return null;
    }
    var result = await app.writeCommand("unit.PlanRoute", { id: id, waypoints: path });
    host.editBusy = false;
    if (result.ok) {
      setEditStatus("已下路线 " + id + "（" + (path.length - 1) + " 格，替换原路线）—— 点「创建节点」推进时间，单位才会出发", "ok");
    } else {
      setEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    return result;
  }

  /** 取消移动（M7e T1）：真命令 `unit.CancelRoute {id}`，经 app.writeCommand（→ /api/command，R8 allowlist）。 */
  async function cancelRouteFor(id) {
    if (!id || host.editBusy) {
      return null;
    }
    host.editBusy = true;
    setEditStatus("取消移动 " + id + " …", "muted");
    var result = await app.writeCommand("unit.CancelRoute", { id: id });
    host.editBusy = false;
    if (result.ok) {
      setEditStatus("已取消 " + id + " 的移动（路线已清）", "ok");
    } else {
      setEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    return result;
  }

  function requireSelectedUnit(actionLabel) {
    var id = selectedUnitId();
    if (!id) {
      setEditStatus("先点选一个单位再" + actionLabel + "。", "warn");
    }
    return id;
  }

  async function submitReparent() {
    var id = requireSelectedUnit("改上级");
    if (!id || host.editBusy) {
      return null;
    }
    var input = app.byId("unit-reparent-parent");
    var raw = input ? input.value.trim() : "";
    host.editBusy = true;
    setEditStatus("改上级 " + id + " …", "muted");
    var result = await app.writeCommand("unit.ReparentUnit", { id: id, parent: raw === "" ? null : raw });
    host.editBusy = false;
    setEditStatus(
      result.ok ? "已改上级 " + id + " → " + (raw === "" ? "（根）" : raw) : result.message,
      result.ok ? "ok" : result.kind === "rejected" ? "err" : "warn"
    );
    return result;
  }

  async function submitStrength() {
    var id = requireSelectedUnit("改编制");
    if (!id || host.editBusy) {
      return null;
    }
    var memberInput = app.byId("unit-strength-member");
    var member = Number(memberInput ? memberInput.value : NaN);
    if (!Number.isInteger(member) || member < 0) {
      setEditStatus("人数必须是 ≥ 0 的整数。", "warn");
      return null;
    }
    var equipment;
    try {
      equipment = parseEquipmentText(app.byId("unit-strength-equipment").value);
    } catch (e) {
      setEditStatus(e.message, "warn");
      return null;
    }
    host.editBusy = true;
    setEditStatus("改编制 " + id + " …", "muted");
    var result = await app.writeCommand("unit.SetStrength", { id: id, member: member, equipment: equipment });
    host.editBusy = false;
    setEditStatus(
      result.ok ? "已改编制 " + id + "（人数 " + member + "）" : result.message,
      result.ok ? "ok" : result.kind === "rejected" ? "err" : "warn"
    );
    return result;
  }

  async function submitDisband() {
    var id = requireSelectedUnit("解散");
    if (!id || host.editBusy) {
      return null;
    }
    host.editBusy = true;
    setEditStatus("解散 " + id + " …", "muted");
    var result = await app.writeCommand("unit.DisbandUnit", { id: id });
    host.editBusy = false;
    if (result.ok) {
      resetRoute();
      app.setSelection(null); // 被解散的单位不再存在，选中态必须清掉（否则下一次点格会拿它当移动目标）
      setEditStatus("已解散 " + id, "ok");
    } else {
      setEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    return result;
  }

  async function submitCreate() {
    if (host.editBusy) {
      return null;
    }
    var id = app.byId("unit-create-id").value.trim();
    var name = app.byId("unit-create-name").value.trim();
    var q = Number(app.byId("unit-create-q").value);
    var r = Number(app.byId("unit-create-r").value);
    var member = Number(app.byId("unit-create-member").value);
    var speed = Number(app.byId("unit-create-speed").value);
    var mobility = Number(app.byId("unit-create-mobility").value);
    var parent = app.byId("unit-create-parent").value.trim();
    if (!id || !name || !Number.isInteger(q) || !Number.isInteger(r)) {
      setEditStatus("新建单位需要 id、名称、整数 q/r。", "warn");
      return null;
    }
    if (
      !Number.isInteger(member) ||
      member < 0 ||
      !Number.isInteger(speed) ||
      speed < 1 ||
      !Number.isInteger(mobility) ||
      mobility < 1
    ) {
      setEditStatus("新建单位：人数 ≥ 0、速度 ≥ 1、机动‰ ≥ 1。", "warn");
      return null;
    }
    var equipment;
    try {
      equipment = parseEquipmentText(app.byId("unit-create-equipment").value);
    } catch (e) {
      setEditStatus(e.message, "warn");
      return null;
    }
    var payload = {
      id: id,
      name: name,
      position: { q: q, r: r },
      member: member,
      equipment: equipment,
      speed: speed,
      mobilityPerMille: mobility,
    };
    if (parent !== "") {
      payload.parent = parent;
    }
    host.editBusy = true;
    setEditStatus("创建 " + id + " …", "muted");
    var result = await app.writeCommand("unit.CreateUnit", payload);
    host.editBusy = false;
    setEditStatus(
      result.ok ? "已创建 " + id : result.message,
      result.ok ? "ok" : result.kind === "rejected" ? "err" : "warn"
    );
    return result;
  }

  function wireUnitEditor() {
    var routeToggle = app.byId("unit-route-toggle");
    if (routeToggle) {
      routeToggle.addEventListener("click", function () {
        host.routeMode = !host.routeMode;
        routeToggle.textContent = "路线模式：" + (host.routeMode ? "开" : "关");
        resetRoute();
        setEditStatus(
          host.routeMode ? "路线模式：依次点相邻格连成路径，再点「下路线」。" : "路线模式已关。",
          "muted"
        );
      });
    }
    var bind = function (buttonId, handler) {
      var node = app.byId(buttonId);
      if (node) {
        node.addEventListener("click", handler);
      }
    };
    bind("unit-route-send", submitRoute);
    bind("unit-route-clear", function () {
      resetRoute();
      setEditStatus("已清除路线点。", "muted");
    });
    bind("unit-reparent", submitReparent);
    bind("unit-strength", submitStrength);
    bind("unit-disband", submitDisband);
    bind("unit-create", submitCreate);
    renderUnitEditor(app.getState());
  }

  // ── 旧页 /map 的格详情（M5 T9 行为保留）────────────────────────────────

  function oldPageSelect(pick) {
    if (pick.kind === "unit") {
      active.setSelected({ q: pick.q, r: pick.r });
      loadHex(pick.q, pick.r, "单位 " + app.text(pick.id) + "：" + app.text(pick.name));
      return;
    }
    if (!pick.inMap) {
      app.statusMessage(
        app.byId("hex-status"),
        "该位置无格（q=" + pick.q + ", r=" + pick.r + "）",
        "warn"
      );
      return;
    }
    active.setSelected({ q: pick.q, r: pick.r });
    loadHex(pick.q, pick.r);
  }

  async function loadHex(q, r, statusLabel) {
    var status = app.byId("hex-status");
    if (!status) {
      return;
    }
    app.statusMessage(
      status,
      statusLabel || "查询 q=" + q + ", r=" + r + " …",
      statusLabel ? "ok" : "muted"
    );
    try {
      var body = await api.mapHex(q, r, app.target());
      showHex(body, statusLabel);
    } catch (e) {
      app.statusMessage(status, "查询失败：" + e.message, "err");
      app.clear(app.byId("hex-facets"));
    }
  }

  function showHex(body, statusLabel) {
    var detail = app.clear(app.byId("hex-detail"));
    var status = app.byId("hex-status");
    if (!detail || !status) {
      return;
    }
    app.statusMessage(status, statusLabel || "q=" + body.q + ", r=" + body.r, "ok");
    [
      ["q", body.q],
      ["r", body.r],
      ["terrain", body.terrain],
      ["height", body.height],
    ].forEach(function (pair) {
      detail.appendChild(app.el("dt", { text: pair[0] }));
      detail.appendChild(app.el("dd", { text: app.text(pair[1]) }));
    });

    var facetsNode = app.clear(app.byId("hex-facets"));
    if (!facetsNode) {
      return;
    }
    var facets = body.facets || [];
    if (!facets.length) {
      facetsNode.appendChild(app.el("p", { class: "empty", text: "该格无 facet。" }));
      return;
    }
    var table = app.el("table", null, [
      app.el("thead", null, [
        app.el("tr", null, [
          app.el("th", { text: "namespace" }),
          app.el("th", { text: "label" }),
          app.el("th", { text: "type" }),
          app.el("th", { text: "value" }),
        ]),
      ]),
      app.el(
        "tbody",
        null,
        facets.map(function (f) {
          return app.el("tr", null, [
            app.el("td", { text: app.text(f.namespace) }),
            app.el("td", { text: app.text(f.label) }),
            app.el("td", { text: app.text(f.typeName) }),
            app.el("td", { text: app.text(f.value) }),
          ]);
        })
      ),
    ]);
    facetsNode.appendChild(table);
  }

  // ── 启动 ──────────────────────────────────────────────────────────────

  function bindActive(renderer) {
    window.SimosMap.screenPointOf = renderer.screenPointOf;
    window.SimosMap.hexAtScreen = renderer.pickAt;
    window.SimosMap.unitPosition = renderer.positionOf;
    window.SimosMap.render = renderer.render;
    window.SimosMap.debug = renderer.debug;
    window.SimosMap.resetView = renderer.fit;
    window.SimosMap.benchStages = renderer.benchStages;
    window.SimosMap.benchTerrainVariants = renderer.benchTerrainVariants;
    window.SimosMap.benchChunkSweep = renderer.benchChunkSweep;
    window.SimosMap.perfConfig = renderer.perfConfig;
    window.SimosMap.computeFit = renderer.computeFit;
    window.SimosMap.currentView = renderer.view;
    window.SimosMap.setView = renderer.setView;
    window.SimosMap.terrainColor = renderer.terrainColor;
    window.SimosMap.isReady = renderer.isReady;
  }

  /** "回到世界中心 / 适配视图"（M7g T1）：重新 fit 一次并立刻重画。 */
  function resetToWorldCenter() {
    if (!active) {
      return;
    }
    active.fit();
    active.render();
  }

  /** 折叠/展开一个浮层栏：按钮 aria-pressed + 文案随栏的 hidden 同步（可断言）。 */
  function wirePanelToggle(buttonId, panelId) {
    var button = app.byId(buttonId);
    var panel = app.byId(panelId);
    if (!button || !panel) {
      return;
    }
    var label = panelId === "left-panel" ? "左栏" : "右栏";
    var sync = function () {
      var collapsed = panel.hidden;
      button.setAttribute("aria-pressed", collapsed ? "true" : "false");
      button.textContent = (collapsed ? "展开" : "收起") + label;
    };
    button.addEventListener("click", function () {
      panel.hidden = !panel.hidden;
      sync();
    });
    sync();
  }

  /** 工作台浮层控件接线（M7g T1）：回中心按钮 + 左右栏折叠 + Home 快捷键。 */
  function wireWorkbenchControls() {
    var reset = app.byId("view-reset");
    if (reset) {
      reset.addEventListener("click", resetToWorldCenter);
    }
    wirePanelToggle("panel-toggle-left", "left-panel");
    wirePanelToggle("panel-toggle-right", "right-panel");
    window.addEventListener("keydown", function (event) {
      var tag = event.target && event.target.tagName ? event.target.tagName : "";
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") {
        return;
      }
      if (event.key === "Home") {
        event.preventDefault();
        resetToWorldCenter();
      }
    });
  }

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
    active = createRenderer(canvas, {
      isWorkbench: isWorkbench,
      onSelect: isWorkbench ? workbenchSelect : oldPageSelect,
      onContextMenu: isWorkbench ? handleContextMenu : undefined,
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
      onLassoCommit: isWorkbench ? onLassoCommit : undefined,
      onDotDragCommit: isWorkbench ? onDotDragCommit : undefined,
    });
    host.isWorkbench = isWorkbench;
    bindActive(active);
    active.resize();
    if (!isWorkbench) {
      active.fit();
    }
    active.render();

    if (isWorkbench) {
      wireWorkbenchControls();
      app.onStateChange(onStateChange);
      onStateChange(app.getState());
      wireUnitEditor();
      wireMapEditor();
      wireRegionEditor();
      app.onStateChange(renderUnitEditor);
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
    edgeModeState: edgeModeState,
    parseSeedInput: parseSeedInput,
    randomizeSelectionState: randomizeSelectionState,
    worldToScreen: worldToScreen,
    screenToWorld: screenToWorld,
    zoomAt: zoomAt,
    fitView: fitView,
    resolveRegionColor: resolveRegionColor,
    fadeRegionColor: fadeRegionColor,
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
      var toolNode = app.byId("map-edit-tools");
      var tool = null;
      if (toolNode) {
        Array.prototype.forEach.call(
          toolNode.querySelectorAll('input[name="map-edit-tool"]'),
          function (input) {
            if (input.checked) {
              tool = input.value;
            }
          }
        );
      }
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
