// map.js —— /map 页专用：只读六角 Canvas 渲染 + 点击查格（M5 T9）。
// ★ 数据只来自 /api/map/overview 与 /api/map/hex；本页**无任何写调用**（spec §8.3 只读）。

(function () {
  "use strict";

  var app = window.SimosApp;
  var api = window.SimosApi;

  var TERRAIN_COLORS = {
    ocean: "#1d3f66",
    sea: "#22507f",
    coast: "#2f6b96",
    lake: "#1f5a70",
    river: "#2b7f9e",
    plain: "#4f7a3a",
    grassland: "#57853d",
    steppe: "#7d8a44",
    forest: "#2f5c33",
    hills: "#6b6a3c",
    mountain: "#6a665e",
    desert: "#a08a52",
    tundra: "#7d8188",
    snow: "#c6d0da",
    swamp: "#40573f",
  };

  var canvas;
  var ctx;
  var cellSize = 34;
  var overview = null;
  var hexIndex = null; // "q_r" → hex
  var units = []; // /api/units 的列表（含 effectivePosition）；map:overview 不含单位位置
  var selected = null;

  // 顶点朝上（pointy-top）的轴向坐标 → 像素：与旧 GSimulator hex-math 同型。
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

  function pixelToHex(px, py, size) {
    var fq = ((Math.sqrt(3) / 3) * px - (1 / 3) * py) / size;
    var fr = ((2 / 3) * py) / size;
    return hexRound(fq, fr);
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

  function drawPolygon(pts, color, stroke) {
    ctx.beginPath();
    pts.forEach(function (p, i) {
      if (i === 0) {
        ctx.moveTo(p.x, p.y);
      } else {
        ctx.lineTo(p.x, p.y);
      }
    });
    ctx.closePath();
    if (color) {
      ctx.fillStyle = color;
      ctx.fill();
    }
    if (stroke) {
      ctx.strokeStyle = stroke;
      ctx.lineWidth = 1;
      ctx.stroke();
    }
  }

  function terrainColor(terrain) {
    return TERRAIN_COLORS[terrain] || "#3a4653";
  }

  /** 由地图形状算出整体偏移，让内容居中。 */
  function computeLayout() {
    var minX = Infinity;
    var minY = Infinity;
    var maxX = -Infinity;
    var maxY = -Infinity;
    overview.hexes.forEach(function (hex) {
      var p = hexToPixel(hex.q, hex.r, cellSize);
      minX = Math.min(minX, p.x - cellSize);
      minY = Math.min(minY, p.y - cellSize);
      maxX = Math.max(maxX, p.x + cellSize);
      maxY = Math.max(maxY, p.y + cellSize);
    });
    if (!overview.hexes.length) {
      return { offsetX: 0, offsetY: 0 };
    }
    var pad = 12;
    return {
      offsetX: pad - minX,
      offsetY: pad - minY,
      width: maxX - minX + pad * 2,
      height: maxY - minY + pad * 2,
    };
  }

  function render() {
    if (!overview) {
      return;
    }
    var layout = computeLayout();
    canvas.width = Math.max(320, Math.ceil(layout.width || canvas.width));
    canvas.height = Math.max(240, Math.ceil(layout.height || canvas.height));

    // 地形层
    overview.hexes.forEach(function (hex) {
      var p = hexToPixel(hex.q, hex.r, cellSize);
      var cx = p.x + layout.offsetX;
      var cy = p.y + layout.offsetY;
      drawPolygon(hexCorners(cx, cy, cellSize * 0.98), terrainColor(hex.terrain), "#0d1015");
    });

    // 城市标记（小方块）
    (overview.cities || []).forEach(function (city) {
      if (!city.at) {
        return;
      }
      var p = hexToPixel(city.at.q, city.at.r, cellSize);
      var cx = p.x + layout.offsetX + cellSize * 0.42;
      var cy = p.y + layout.offsetY - cellSize * 0.42;
      ctx.fillStyle = "#f0d27a";
      ctx.fillRect(cx - 4, cy - 4, 8, 8);
      ctx.strokeStyle = "#0d1015";
      ctx.strokeRect(cx - 4, cy - 4, 8, 8);
    });

    // 单位标记层（T9b）：数据来自 /api/units 的 effectivePosition，叠加在地形之上。
    drawUnits(layout);

    // 选中高亮
    if (selected) {
      var sp = hexToPixel(selected.q, selected.r, cellSize);
      drawPolygon(
        hexCorners(sp.x + layout.offsetX, sp.y + layout.offsetY, cellSize * 0.92),
        null,
        "#4ea1ff"
      );
    }
    drawLegend();
  }

  /** 单位在画布坐标（含 layout 偏移）里的圆心；无位置 ⇒ null。 */
  function unitCenter(unit, layout) {
    if (!unit || !unit.position) {
      return null;
    }
    var p = hexToPixel(unit.position.q, unit.position.r, cellSize);
    return { x: p.x + layout.offsetX, y: p.y + layout.offsetY };
  }

  /** 画单位标记：实心圆 + 白环 + id 短标签。 */
  function drawUnits(layout) {
    var radius = Math.max(6, cellSize * 0.3);
    units.forEach(function (unit) {
      var center = unitCenter(unit, layout);
      if (!center) {
        return;
      }
      ctx.beginPath();
      ctx.arc(center.x, center.y, radius, 0, Math.PI * 2);
      ctx.fillStyle = "#e8503a";
      ctx.fill();
      ctx.strokeStyle = "#ffffff";
      ctx.lineWidth = 2;
      ctx.stroke();
      ctx.fillStyle = "#ffffff";
      ctx.font = Math.max(9, Math.round(radius)) + "px sans-serif";
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      ctx.fillText(shortId(unit.id), center.x, center.y);
    });
  }

  /** 圆形标记上的短标签：取 id 的前 4 个字符。 */
  function shortId(id) {
    var text = app.text(id);
    return text.length > 4 ? text.slice(0, 4) : text;
  }

  /** 命中哪个单位标记（画布坐标，不含 layout 偏移）。后画的在上，故从后往前找。 */
  function unitHitAt(px, py) {
    var radius = Math.max(8, cellSize * 0.36);
    for (var i = units.length - 1; i >= 0; i--) {
      if (!units[i].position) {
        continue;
      }
      var p = hexToPixel(units[i].position.q, units[i].position.r, cellSize);
      var dx = px - p.x;
      var dy = py - p.y;
      if (dx * dx + dy * dy <= radius * radius) {
        return units[i];
      }
    }
    return null;
  }

  /** 鼠标事件 → 画布坐标（已减去 layout 偏移），与 hexToPixel/pixelToHex 同一坐标系。 */
  function pickPoint(event, layout) {
    var rect = canvas.getBoundingClientRect();
    var scaleX = canvas.width / rect.width;
    var scaleY = canvas.height / rect.height;
    return {
      px: (event.clientX - rect.left) * scaleX - layout.offsetX,
      py: (event.clientY - rect.top) * scaleY - layout.offsetY,
    };
  }

  function drawLegend() {
    var legend = app.byId("legend");
    if (!legend || !overview) {
      return;
    }
    var types = overview.terrainTypes || Object.keys(TERRAIN_COLORS);
    var counts = {};
    overview.hexes.forEach(function (h) {
      counts[h.terrain] = (counts[h.terrain] || 0) + 1;
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

  function hexKey(q, r) {
    return q + "_" + r;
  }

  function pickHex(event) {
    if (!overview) {
      return;
    }
    var layout = computeLayout();
    var point = pickPoint(event, layout);
    // 单位标记优先于格点：点在标记上就选中它所在的格并显示 id/name。
    var hit = unitHitAt(point.px, point.py);
    if (hit) {
      selected = { q: hit.position.q, r: hit.position.r };
      render();
      var label = "单位 " + app.text(hit.id) + "：" + app.text(hit.name);
      app.statusMessage(app.byId("hex-status"), label, "ok");
      loadHex(hit.position.q, hit.position.r, label);
      return;
    }
    var coord = pixelToHex(point.px, point.py, cellSize);
    if (!hexIndex[hexKey(coord.q, coord.r)]) {
      app.statusMessage(app.byId("hex-status"), "该位置无格（q=" + coord.q + ", r=" + coord.r + "）", "warn");
      return;
    }
    selected = coord;
    render();
    loadHex(coord.q, coord.r);
  }

  /**
   * 查一个格的详情。{@code statusLabel} 非空时把它作为状态文字（点单位标记时用来保留
   * "单位 id：名称"，否则会被 "q=x, r=y" 覆盖）；失败仍显示错误。
   */
  async function loadHex(q, r, statusLabel) {
    var status = app.byId("hex-status");
    app.statusMessage(status, statusLabel || "查询 q=" + q + ", r=" + r + " …", statusLabel ? "ok" : "muted");
    try {
      var body = await api.mapHex(q, r);
      showHex(body, statusLabel);
    } catch (e) {
      app.statusMessage(status, "查询失败：" + e.message, "err");
      app.clear(app.byId("hex-facets"));
    }
  }

  function showHex(body, statusLabel) {
    var detail = app.clear(app.byId("hex-detail"));
    var status = app.byId("hex-status");
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

  /** 拉单位列表（T9b）：/api/units 的 effectivePosition 是标记层的数据源；失败不致命，返回错误消息。 */
  async function loadUnits() {
    try {
      var body = await api.units();
      units = (body && body.units) || [];
      return null;
    } catch (e) {
      units = [];
      return e.message;
    }
  }

  async function loadOverview() {
    var status = app.byId("map-status");
    app.statusMessage(status, "载入地图总览…", "muted");
    try {
      overview = await api.mapOverview();
      hexIndex = {};
      overview.hexes.forEach(function (h) {
        hexIndex[hexKey(h.q, h.r)] = h;
      });
      var unitError = await loadUnits();
      render();
      if (unitError) {
        app.statusMessage(
          status,
          "已载入 " + overview.hexCount + " 格，但单位载入失败：" + unitError,
          "warn"
        );
      } else {
        app.statusMessage(
          status,
          "已载入 " +
            overview.hexCount +
            " 格（mapId=" +
            app.text(overview.mapId) +
            "，单位 " +
            units.length +
            "）",
          "ok"
        );
      }
    } catch (e) {
      app.statusMessage(status, "地图载入失败：" + e.message, "err");
    }
  }

  function init() {
    app.boot({ title: "地图" });
    canvas = app.byId("canvas");
    ctx = canvas.getContext("2d");
    canvas.addEventListener("click", pickHex);
    canvas.addEventListener("mousemove", function (event) {
      if (!overview) {
        return;
      }
      var layout = computeLayout();
      var point = pickPoint(event, layout);
      var hit = unitHitAt(point.px, point.py);
      canvas.style.cursor = hit ? "pointer" : "default";
      canvas.title = hit ? app.text(hit.id) + " · " + app.text(hit.name) : "";
    });

    app.byId("cell-size").addEventListener("change", function (event) {
      var value = Number(event.target.value);
      if (Number.isFinite(value) && value >= 12 && value <= 80) {
        cellSize = value;
        render();
      }
    });
    app.byId("reload").addEventListener("click", function () {
      selected = null;
      app.clear(app.byId("hex-detail"));
      app.clear(app.byId("hex-facets"));
      loadOverview();
    });

    loadOverview();
  }

  document.addEventListener("DOMContentLoaded", init);
})();
