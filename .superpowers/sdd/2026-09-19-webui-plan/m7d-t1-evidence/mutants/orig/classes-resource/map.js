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
// ★ 只读：本文件不发任何写请求（写只经 /api/command|advance|fork）。

(function () {
  "use strict";

  var app = window.SimosApp;
  var api = window.SimosApi;

  var BASE_CELL = 34; // 世界坐标的基准格边长（px）
  var FALLBACK_COLOR = "#ff00ff"; // 词表外地形的兜底色（品红；刻意不像任何地形）
  var REGION_FALLBACK_COLOR = "#00e5ff"; // 区域色缺失/非法时的兜底色（青色；与地形兜底色不同）
  var ROUTE_BASE_COLOR = "rgba(255, 214, 130, 0.35)"; // 整条路线的淡色层（M7b T2）
  var ROUTE_REMAINING_COLOR = "#ffd27a"; // 未走完的那一段的亮色层
  var ROUTE_BASE_WIDTH = 5;
  var ROUTE_REMAINING_WIDTH = 3;
  var HIGHLIGHT_ALPHA = 0.42; // 区域填充透明度（保留地形可见性）
  var HEX_COLOR_RE = /^#[0-9a-fA-F]{6}$/;
  var regionFallbackWarned = false;
  var MIN_SCALE = 0.03;
  var MAX_SCALE = 12;
  var ZOOM_WHEEL = 0.0016; // 滚轮 deltaY → 缩放指数系数
  var WORKBENCH_CANVAS_HEIGHT = 480;
  var OLD_PAGE_CANVAS_HEIGHT = 620;

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

    var cellSize = opts.cellSize || BASE_CELL;
    var view = { scale: 1, tx: 0, ty: 0 };
    var overview = null;
    var hexes = []; // 预计算世界坐标的格：{q,r,terrain,height,px,py}
    var hexIndex = {}; // "q_r" → hex
    var units = []; // 预计算世界坐标的单位：{id,name,position,px,py}
    var routes = []; // 在途路线（M7b T2）：{id,movement,path:[{q,r}…]}
    var colorByTerrain = {};
    var fallbackWarned = false;
    var selected = null;
    var selectedUnit = null;
    var highlightColorByKey = {};
    var mode = "view";

    var cssW = 800;
    var cssH = isWorkbench ? WORKBENCH_CANVAS_HEIGHT : OLD_PAGE_CANVAS_HEIGHT;
    var dpr = 1;

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

    function hexKey(q, r) {
      return q + "_" + r;
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
      if (!hexes.length) {
        return null;
      }
      var minX = Infinity;
      var minY = Infinity;
      var maxX = -Infinity;
      var maxY = -Infinity;
      hexes.forEach(function (h) {
        minX = Math.min(minX, h.px - cellSize);
        minY = Math.min(minY, h.py - cellSize);
        maxX = Math.max(maxX, h.px + cellSize);
        maxY = Math.max(maxY, h.py + cellSize);
      });
      return { minX: minX, minY: minY, maxX: maxX, maxY: maxY };
    }

    function recomputeWorldPixels() {
      hexes.forEach(function (h) {
        var p = hexToPixel(h.q, h.r, cellSize);
        h.px = p.x;
        h.py = p.y;
      });
      units.forEach(function (u) {
        var p = hexToPixel(u.position.q, u.position.r, cellSize);
        u.px = p.x;
        u.py = p.y;
      });
    }

    function setData(body) {
      overview = body;
      hexes = (body.hexes || []).map(function (h) {
        var p = hexToPixel(h.q, h.r, cellSize);
        return { q: h.q, r: h.r, terrain: h.terrain, height: h.height, px: p.x, py: p.y };
      });
      hexIndex = {};
      hexes.forEach(function (h) {
        hexIndex[hexKey(h.q, h.r)] = h;
      });
      rebuildColors();
      updateLegend();
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
      (entries || []).forEach(function (entry) {
        if (!entry) {
          return;
        }
        var key = typeof entry === "string" ? entry : entry.key;
        if (!key || Object.prototype.hasOwnProperty.call(highlightColorByKey, key)) {
          return;
        }
        highlightColorByKey[key] = typeof entry === "string" ? null : entry.color || null;
      });
      scheduleRender();
    }

    function setMode(next) {
      mode = next || "view";
      updateCursor();
      scheduleRender();
    }

    function fit() {
      view = fitView(worldBounds(), cssW, cssH, 24);
      updateZoomUi();
      scheduleRender();
    }

    function resize() {
      var host = canvas.parentElement;
      if (!host) {
        return;
      }
      var style = window.getComputedStyle(host);
      var padX = (parseFloat(style.paddingLeft) || 0) + (parseFloat(style.paddingRight) || 0);
      var w = Math.max(320, Math.round(host.clientWidth - padX));
      var h = isWorkbench ? WORKBENCH_CANVAS_HEIGHT : OLD_PAGE_CANVAS_HEIGHT;
      dpr = window.devicePixelRatio || 1;
      cssW = w;
      cssH = h;
      canvas.style.width = w + "px";
      canvas.style.height = h + "px";
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
      scheduleRender();
    }

    function addHexPath(cx, cy, size) {
      for (var i = 0; i < 6; i++) {
        var a = (Math.PI / 180) * (60 * i - 30);
        var x = cx + size * Math.cos(a);
        var y = cy + size * Math.sin(a);
        if (i === 0) {
          ctx.moveTo(x, y);
        } else {
          ctx.lineTo(x, y);
        }
      }
      ctx.closePath();
    }

    function visibleHexes() {
      var margin = cellSize * 2;
      var tl = screenToWorld({ x: -margin, y: -margin }, view);
      var br = screenToWorld({ x: cssW + margin, y: cssH + margin }, view);
      var out = [];
      for (var i = 0; i < hexes.length; i++) {
        var h = hexes[i];
        if (h.px < tl.x || h.px > br.x || h.py < tl.y || h.py > br.y) {
          continue;
        }
        out.push(h);
      }
      return out;
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

    /** 在途路线折线（M7b T2）：整条淡色 + 未走完的部分亮色；无路线时不画。 */
    function drawRoutes() {
      routes.forEach(function (route) {
        strokePolyline(
          route.path.map(function (h) {
            return hexToPixel(h.q, h.r, cellSize);
          }),
          ROUTE_BASE_COLOR,
          ROUTE_BASE_WIDTH
        );
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

    function render() {
      if (!overview || cssW <= 0) {
        return;
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, cssW, cssH);
      ctx.setTransform(dpr * view.scale, 0, 0, dpr * view.scale, dpr * view.tx, dpr * view.ty);

      var visible = visibleHexes();
      var radius = cellSize * 0.98;

      // 地形层：按后端色分组，减少 fillStyle 切换。
      var byColor = {};
      visible.forEach(function (h) {
        var color = terrainColor(h.terrain);
        (byColor[color] || (byColor[color] = [])).push(h);
      });
      Object.keys(byColor).forEach(function (color) {
        ctx.beginPath();
        byColor[color].forEach(function (h) {
          addHexPath(h.px, h.py, radius);
        });
        ctx.fillStyle = color;
        ctx.fill();
      });

      // 区域填充层：按 RegionMeta.color 分组，一组一次 fill（半透明，保留地形可见性）。
      var highlightByColor = {};
      visible.forEach(function (h) {
        var color = highlightColorByKey[hexKey(h.q, h.r)];
        if (color) {
          (highlightByColor[color] || (highlightByColor[color] = [])).push(h);
        }
      });
      Object.keys(highlightByColor).forEach(function (color) {
        ctx.beginPath();
        highlightByColor[color].forEach(function (h) {
          addHexPath(h.px, h.py, radius);
        });
        ctx.fillStyle = withAlpha(color, HIGHLIGHT_ALPHA);
        ctx.fill();
      });

      // 格边框：一条路径一次描边。
      ctx.beginPath();
      visible.forEach(function (h) {
        addHexPath(h.px, h.py, radius);
      });
      ctx.strokeStyle = "#0d1015";
      ctx.lineWidth = 1 / view.scale;
      ctx.stroke();

      drawRoutes();
      drawCities();
      drawUnits();

      if (selected) {
        var p = hexToPixel(selected.q, selected.r, cellSize);
        ctx.beginPath();
        addHexPath(p.x, p.y, cellSize * 0.92);
        ctx.strokeStyle = "#4ea1ff";
        ctx.lineWidth = 2 / view.scale;
        ctx.stroke();
      }
      updateZoomUi();
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
      return {
        kind: "hex",
        q: coord.q,
        r: coord.r,
        inMap: !!hexIndex[hexKey(coord.q, coord.r)],
      };
    }

    /** 一个格中心在画布内的 CSS 像素位置（e2e 点选/像素取样的锚）。 */
    function screenPointOf(q, r) {
      return worldToScreen(hexToPixel(q, r, cellSize), view);
    }

    function canvasPoint(event) {
      var rect = canvas.getBoundingClientRect();
      return { x: event.clientX - rect.left, y: event.clientY - rect.top };
    }

    function updateCursor() {
      if (dragging) {
        canvas.style.cursor = "grabbing";
      } else if (mode === "region") {
        canvas.style.cursor = "pointer";
      } else {
        canvas.style.cursor = "grab";
      }
    }

    function onPointerDown(event) {
      if (event.button !== 0) {
        return;
      }
      dragging = true;
      dragMoved = false;
      dragStart = { x: event.clientX, y: event.clientY };
      viewStart = { scale: view.scale, tx: view.tx, ty: view.ty };
      if (canvas.setPointerCapture) {
        try {
          canvas.setPointerCapture(event.pointerId);
        } catch (e) {
          /* 某些环境不支持捕获；不影响拖动 */
        }
      }
      updateCursor();
    }

    function onPointerMove(event) {
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
      canvas.style.cursor =
        pick.kind === "unit" ? "pointer" : mode === "region" ? "pointer" : "grab";
      canvas.title = pick.kind === "unit" ? app.text(pick.name) : "";
    }

    function onPointerUp(event) {
      if (!dragging) {
        return;
      }
      dragging = false;
      if (canvas.releasePointerCapture) {
        try {
          canvas.releasePointerCapture(event.pointerId);
        } catch (e) {
          /* 未捕获时忽略 */
        }
      }
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
      dragging = false;
      updateCursor();
    }

    function updateZoomUi() {
      var node = app.byId("zoom-level");
      if (node) {
        node.textContent = "缩放 " + view.scale.toFixed(2) + "×";
      }
    }

    function updateLegend() {
      var legend = app.byId("legend");
      if (!legend || !overview) {
        return;
      }
      var counts = {};
      overview.hexes.forEach(function (h) {
        counts[h.terrain] = (counts[h.terrain] || 0) + 1;
      });
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

    function debug() {
      return {
        scale: view.scale,
        tx: view.tx,
        ty: view.ty,
        cellSize: cellSize,
        hexCount: hexes.length,
        unitCount: units.length,
        colorByTerrain: Object.assign({}, colorByTerrain),
        fallbackColor: FALLBACK_COLOR,
        fallbackWarned: fallbackWarned,
        highlightHexCount: Object.keys(highlightColorByKey).length,
        highlightColors: distinctColors(),
        regionFallbackColor: REGION_FALLBACK_COLOR,
        regionFallbackWarned: regionFallbackWarned,
        selected: selected ? { q: selected.q, r: selected.r } : null,
        selectedUnit: selectedUnit,
        mode: mode,
        isWorkbench: isWorkbench,
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

    canvas.addEventListener("contextmenu", onContextMenuEvent);
    canvas.addEventListener("pointerdown", onPointerDown);
    canvas.addEventListener("pointermove", onPointerMove);
    canvas.addEventListener("pointerup", onPointerUp);
    canvas.addEventListener("pointercancel", onPointerCancel);
    canvas.addEventListener("wheel", onWheel, { passive: false });

    return {
      canvas: canvas,
      setData: setData,
      setUnits: setUnits,
      setCellSize: setCellSize,
      setSelected: setSelected,
      setSelectedUnit: setSelectedUnit,
      setHighlightHexes: setHighlightHexes,
      setMode: setMode,
      fit: fit,
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
    regionCache: {},
    targetTimer: null,
    routeMode: false,
    routePath: [],
    editBusy: false,
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
      var body = await api.mapOverview(app.target());
      var branch = app.target().branch;
      var needFit = !active.isReady() || host.fittedBranch !== branch;
      active.setData(body);
      await reloadUnits();
      if (needFit) {
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
      var body = await api.units(app.target());
      active.setUnits((body && body.units) || []);
      return null;
    } catch (e) {
      active.setUnits([]);
      return e.message;
    }
  }

  /** 按状态机的 highlightRegions 懒拉区域 hex 集合（spec §3.3：点选时才拉，避免 19441 格爆载荷）。 */
  async function reloadHighlights() {
    var ids = app.getState().highlightRegions || [];
    if (!ids.length) {
      active.setHighlightHexes([]);
      return;
    }
    var entries = [];
    for (var i = 0; i < ids.length; i++) {
      var id = ids[i];
      var cacheKey = regionCacheKey(id);
      if (!host.regionCache[cacheKey]) {
        try {
          host.regionCache[cacheKey] = await api.mapRegion(id, app.target());
        } catch (e) {
          host.regionCache[cacheKey] = { id: id, hexes: [] };
          window.console.warn("[SimosMap] 区域拉取失败：" + id + "：" + e.message);
        }
      }
      var region = host.regionCache[cacheKey];
      var color = regionColor(region.meta);
      (region.hexes || []).forEach(function (h) {
        entries.push({ key: h.q + "_" + h.r, color: color });
      });
    }
    active.setHighlightHexes(entries);
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
      reloadHighlights();
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
      reloadHighlights();
    }
    if (host.lastMode !== state.mode) {
      host.lastMode = state.mode;
      active.setMode(state.mode);
    }
    var sel = state.selection;
    if (sel && sel.kind === "unit") {
      active.setSelectedUnit(sel.id);
      active.setSelected(active.positionOf(sel.id));
    } else {
      active.setSelectedUnit(null);
      active.setSelected(sel && sel.kind === "hex" ? { q: sel.q, r: sel.r } : null);
    }
  }

  function workbenchSelect(pick) {
    var mode = app.getState().mode;
    if (pick.kind === "unit") {
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
    if (mode === "unit" && selectedUnitId() && host.routeMode) {
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
      if (body.region) {
        app.setHighlightRegions([body.region]);
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
    window.SimosMap.currentView = renderer.view;
    window.SimosMap.setView = renderer.setView;
    window.SimosMap.terrainColor = renderer.terrainColor;
    window.SimosMap.isReady = renderer.isReady;
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
    });
    bindActive(active);
    active.resize();
    active.fit();
    active.render();

    if (isWorkbench) {
      app.onStateChange(onStateChange);
      onStateChange(app.getState());
      wireUnitEditor();
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
    ROUTE_BASE_COLOR: ROUTE_BASE_COLOR,
    ROUTE_REMAINING_COLOR: ROUTE_REMAINING_COLOR,
    MIN_SCALE: MIN_SCALE,
    MAX_SCALE: MAX_SCALE,
    clamp: clamp,
    hexToPixel: hexToPixel,
    hexCorners: hexCorners,
    hexRound: hexRound,
    pixelToHex: pixelToHex,
    worldToScreen: worldToScreen,
    screenToWorld: screenToWorld,
    zoomAt: zoomAt,
    fitView: fitView,
    resolveRegionColor: resolveRegionColor,
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
  };

  document.addEventListener("DOMContentLoaded", initHost);
})();
