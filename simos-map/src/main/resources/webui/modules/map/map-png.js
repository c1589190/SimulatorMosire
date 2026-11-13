// map-png.js —— D-036 P5：PNG 底图对齐 + hex 中心取色 + 地形预览/应用（simos-map 模块自带前端资产）。
//
// 数据面：GET /api/map/hexes（全图坐标）、GET /api/map/overview（地形词表/颜色）；
//         应用时经 SimosApp.writeCommand("map.SetTerrain", {hexes, terrain})，每个地形一组一条命令。
// 该文件由 StaticHandler 从 classpath /webui/modules/map/map-png.js 服务；app 不复制实现。

(function () {
  "use strict";

  var image = null;
  var imagePixels = null;
  var imageWidth = 0;
  var imageHeight = 0;
  var terrainDefs = [];
  var lastPlan = null;

  function byId(id) {
    return document.getElementById(id);
  }

  function status(text, tone) {
    var node = byId("png-status");
    if (!node) return;
    node.textContent = text || "";
    node.className = "status " + (tone || "muted");
  }

  function parseHexColor(value) {
    var text = String(value || "").trim();
    var match = /^#([0-9a-fA-F]{6})$/.exec(text);
    if (!match) return null;
    var n = parseInt(match[1], 16);
    return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
  }

  function colorDistance(a, b) {
    var dr = a.r - b.r;
    var dg = a.g - b.g;
    var db = a.b - b.b;
    return Math.sqrt(dr * dr + dg * dg + db * db);
  }

  /** 在冻结的颜色表里找最近地形；超过 threshold ⇒ null（不把噪声硬塞成地形）。 */
  function nearestTerrain(rgb, terrains, threshold) {
    var best = null;
    var bestDistance = Infinity;
    var list = Array.isArray(terrains) ? terrains : [];
    for (var i = 0; i < list.length; i++) {
      var color = parseHexColor(list[i].color);
      if (!color) continue;
      var distance = colorDistance(rgb, color);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = list[i];
      }
    }
    if (!best || bestDistance > (threshold === undefined ? 90 : threshold)) {
      return null;
    }
    return { terrain: best, distance: bestDistance };
  }

  function averageColor(pixels, width, height, x, y) {
    var r = 0;
    var g = 0;
    var b = 0;
    var count = 0;
    for (var dy = -1; dy <= 1; dy++) {
      for (var dx = -1; dx <= 1; dx++) {
        var px = x + dx;
        var py = y + dy;
        if (px < 0 || py < 0 || px >= width || py >= height) continue;
        var offset = (py * width + px) * 4;
        if (pixels[offset + 3] < 32) continue;
        r += pixels[offset];
        g += pixels[offset + 1];
        b += pixels[offset + 2];
        count += 1;
      }
    }
    if (!count) return null;
    return { r: Math.round(r / count), g: Math.round(g / count), b: Math.round(b / count) };
  }

  /**
   * 纯函数：hex 坐标 + PNG 像素 + 对齐参数 → 地形分配计划。
   *
   * @param hexes [{q,r}…]
   * @param pixels RGBA Uint8ClampedArray
   * @param width 图像宽（px）
   * @param height 图像高（px）
   * @param options {offsetX, offsetY, scale, cell, threshold}
   * @param terrains [{key,name,color}…]
   */
  function buildPlan(hexes, pixels, width, height, options, terrains) {
    var geom = window.SimosHexGeom;
    var opts = options || {};
    var scale = Number(opts.scale);
    if (!isFinite(scale) || scale <= 0) scale = 1;
    var cell = Number(opts.cell);
    if (!isFinite(cell) || cell <= 0) cell = 22;
    var offsetX = Number(opts.offsetX) || 0;
    var offsetY = Number(opts.offsetY) || 0;
    var threshold = Number(opts.threshold);
    if (!isFinite(threshold) || threshold < 0) threshold = 90;
    var assignments = [];
    var counts = {};
    var skipped = 0;
    var list = Array.isArray(hexes) ? hexes : [];
    for (var i = 0; i < list.length; i++) {
      var hex = list[i];
      var world = geom.hexToPixel(Number(hex.q), Number(hex.r), cell);
      var ix = Math.round((world.x - offsetX) / scale);
      var iy = Math.round((world.y - offsetY) / scale);
      if (ix < 0 || iy < 0 || ix >= width || iy >= height) {
        skipped += 1;
        continue;
      }
      var rgb = averageColor(pixels, width, height, ix, iy);
      if (!rgb) {
        skipped += 1;
        continue;
      }
      var nearest = nearestTerrain(rgb, terrains, threshold);
      if (!nearest) {
        skipped += 1;
        continue;
      }
      var key = nearest.terrain.key;
      assignments.push({ q: Number(hex.q), r: Number(hex.r), terrain: key, distance: nearest.distance });
      counts[key] = (counts[key] || 0) + 1;
    }
    return { assignments: assignments, counts: counts, skipped: skipped };
  }

  function readOptions() {
    return {
      offsetX: Number(byId("png-offset-x") && byId("png-offset-x").value) || 0,
      offsetY: Number(byId("png-offset-y") && byId("png-offset-y").value) || 0,
      scale: Number(byId("png-scale") && byId("png-scale").value) || 1,
      opacity: Number(byId("png-opacity") && byId("png-opacity").value),
      cell: window.SimosMapCore && window.SimosMapCore.BASE_CELL ? window.SimosMapCore.BASE_CELL : 22,
      threshold: Number(byId("png-threshold") && byId("png-threshold").value) || 90,
    };
  }

  function renderer() {
    return window.SimosMapCore ? window.SimosMapCore.active : null;
  }

  function updateOverlay() {
    // 对齐参数变了 ⇒ 旧取色计划失效，必须重新预览；否则“应用”会写入旧坐标的分配。
    if (lastPlan) {
      lastPlan = null;
      var applyButton = byId("png-apply");
      if (applyButton) applyButton.disabled = true;
      status("对齐参数已变，请重新按格心取色预览", "warn");
    }
    var active = renderer();
    if (!active || !active.setPngOverlay) {
      status("地图渲染器尚未就绪", "warn");
      return;
    }
    if (!image) {
      active.setPngOverlay(null);
      return;
    }
    var opts = readOptions();
    active.setPngOverlay({
      image: image,
      offsetX: opts.offsetX,
      offsetY: opts.offsetY,
      scale: opts.scale,
      opacity: isFinite(opts.opacity) ? opts.opacity : 0.65,
    });
  }

  function loadFile(file) {
    if (!file) return;
    var url = URL.createObjectURL(file);
    var next = new Image();
    next.onload = function () {
      image = next;
      var canvas = document.createElement("canvas");
      canvas.width = next.naturalWidth || next.width;
      canvas.height = next.naturalHeight || next.height;
      var ctx = canvas.getContext("2d");
      ctx.drawImage(next, 0, 0);
      imageWidth = canvas.width;
      imageHeight = canvas.height;
      imagePixels = ctx.getImageData(0, 0, imageWidth, imageHeight).data;
      lastPlan = null;
      var apply = byId("png-apply");
      if (apply) apply.disabled = true;
      updateOverlay();
      status("已载入 " + imageWidth + "×" + imageHeight + " PNG；调整偏移/缩放后点「按格心取色预览」", "ok");
      URL.revokeObjectURL(url);
    };
    next.onerror = function () {
      status("PNG 载入失败", "err");
      URL.revokeObjectURL(url);
    };
    next.src = url;
  }

  async function preview() {
    if (!imagePixels) {
      status("先载入 PNG", "warn");
      return;
    }
    try {
      var target = window.SimosApp.target();
      var hexBody = await window.SimosApi.getJson(
        window.SimosApi.withTarget("/map/hexes", target)
      );
      var overview = await window.SimosApi.getJson(
        window.SimosApi.withTarget("/map/overview", target)
      );
      terrainDefs = Array.isArray(overview.terrainTypes) ? overview.terrainTypes : [];
      lastPlan = buildPlan(
        hexBody.hexes || [],
        imagePixels,
        imageWidth,
        imageHeight,
        readOptions(),
        terrainDefs
      );
      renderPreview(lastPlan);
      var apply = byId("png-apply");
      if (apply) apply.disabled = lastPlan.assignments.length === 0;
      status(
        "预览：可写入 " +
          lastPlan.assignments.length +
          " 格，跳过 " +
          lastPlan.skipped +
          " 格（阈值 " +
          readOptions().threshold +
          "）",
        lastPlan.assignments.length ? "ok" : "warn"
      );
    } catch (e) {
      status("取色失败：" + e.message, "err");
    }
  }

  function renderPreview(plan) {
    var mount = byId("png-preview");
    if (!mount) return;
    while (mount.firstChild) mount.removeChild(mount.firstChild);
    var counts = plan && plan.counts ? plan.counts : {};
    var keys = Object.keys(counts).sort(function (a, b) {
      return counts[b] - counts[a];
    });
    if (!keys.length) {
      var empty = document.createElement("p");
      empty.className = "muted";
      empty.textContent = "没有格命中地形颜色；调整偏移/缩放/阈值后重试。";
      mount.appendChild(empty);
      return;
    }
    keys.forEach(function (key) {
      var def = terrainDefs.find(function (item) {
        return item.key === key;
      });
      var row = document.createElement("div");
      row.className = "png-preview-row";
      var swatch = document.createElement("span");
      swatch.className = "png-swatch";
      swatch.style.background = def ? def.color : "#555";
      var label = document.createElement("span");
      label.textContent = (def ? def.name : key) + " · " + counts[key] + " 格";
      row.appendChild(swatch);
      row.appendChild(label);
      mount.appendChild(row);
    });
  }

  async function apply() {
    if (!lastPlan || !lastPlan.assignments.length) {
      status("先预览出可写入的格", "warn");
      return;
    }
    var byTerrain = {};
    lastPlan.assignments.forEach(function (item) {
      if (!byTerrain[item.terrain]) byTerrain[item.terrain] = [];
      byTerrain[item.terrain].push({ q: item.q, r: item.r });
    });
    var keys = Object.keys(byTerrain);
    var total = 0;
    for (var i = 0; i < keys.length; i++) {
      var result = await window.SimosApp.writeCommand("map.SetTerrain", {
        hexes: byTerrain[keys[i]],
        terrain: keys[i],
      });
      if (!result || !result.ok) {
        status("应用失败（" + keys[i] + "）：" + (result && result.message ? result.message : "未知错误"), "err");
        return;
      }
      total += byTerrain[keys[i]].length;
    }
    status("已应用 " + total + " 格（" + keys.length + " 条 map.SetTerrain）；底图保留，可继续调整", "ok");
  }

  function clear() {
    image = null;
    imagePixels = null;
    lastPlan = null;
    var active = renderer();
    if (active && active.setPngOverlay) active.setPngOverlay(null);
    var applyButton = byId("png-apply");
    if (applyButton) applyButton.disabled = true;
    var previewMount = byId("png-preview");
    if (previewMount) {
      while (previewMount.firstChild) previewMount.removeChild(previewMount.firstChild);
    }
    status("已清除 PNG 底图", "muted");
  }

  function bind() {
    var file = byId("png-file");
    if (file) {
      file.addEventListener("change", function () {
        loadFile(file.files && file.files[0]);
      });
    }
    ["png-offset-x", "png-offset-y", "png-scale", "png-threshold", "png-opacity"].forEach(function (id) {
      var node = byId(id);
      if (node) node.addEventListener("input", updateOverlay);
    });
    var sample = byId("png-sample");
    if (sample) sample.addEventListener("click", preview);
    var applyButton = byId("png-apply");
    if (applyButton) applyButton.addEventListener("click", apply);
    var clearButton = byId("png-clear");
    if (clearButton) clearButton.addEventListener("click", clear);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bind);
  } else {
    bind();
  }

  window.SimosMapPng = {
    parseHexColor: parseHexColor,
    colorDistance: colorDistance,
    nearestTerrain: nearestTerrain,
    buildPlan: buildPlan,
  };
})();
