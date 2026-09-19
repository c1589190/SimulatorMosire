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

    var cellSize = opts.cellSize || BASE_CELL;
    var view = { scale: 1, tx: 0, ty: 0 };
    var overview = null;
    var hexes = []; // 预计算世界坐标的格：{q,r,terrain,height,px,py}
    var hexIndex = {}; // "q_r" → hex
    var units = []; // 预计算世界坐标的单位：{id,name,position,px,py}
    var colorByTerrain = {};
    var fallbackWarned = false;
    var selected = null;
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
      updateLegend();
      scheduleRender();
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
        mode: mode,
        isWorkbench: isWorkbench,
      };
    }

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
      setHighlightHexes: setHighlightHexes,
      setMode: setMode,
      fit: fit,
      resize: resize,
      render: render,
      pickAt: pickAt,
      screenPointOf: screenPointOf,
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
    active.setSelected(sel && sel.kind === "hex" ? { q: sel.q, r: sel.r } : null);
  }

  function workbenchSelect(pick) {
    if (pick.kind === "unit") {
      app.setSelection({ kind: "unit", id: pick.id });
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
    app.setSelection({ kind: "hex", q: pick.q, r: pick.r });
    if (app.getState().mode === "region") {
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
    });
    bindActive(active);
    active.resize();
    active.fit();
    active.render();

    if (isWorkbench) {
      app.onStateChange(onStateChange);
      onStateChange(app.getState());
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
    // 宿主
    init: initHost,
  };

  document.addEventListener("DOMContentLoaded", initHost);
})();
