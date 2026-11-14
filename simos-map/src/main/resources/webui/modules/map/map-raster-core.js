// map-raster-core.js —— 底图转六角格的确定性算法；不访问 DOM 或持久状态。
(function () {
  "use strict";
  var SQRT3 = Math.sqrt(3);
  var MAX_CELLS = 12000;
  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }
  function finite(v, label) {
    var n = Number(v);
    if (!isFinite(n)) throw new Error(label + "必须是有限数");
    return n;
  }
  function parseHexColor(value) {
    var m = /^#([0-9a-f]{6})$/i.exec(String(value || ""));
    if (!m) return null;
    var n = parseInt(m[1], 16);
    return { r: n >> 16 & 255, g: n >> 8 & 255, b: n & 255 };
  }
  function nearestTerrain(rgb, terrains, threshold) {
    var best = null, distance = Infinity;
    (terrains || []).forEach(function (t) {
      var color = parseHexColor(t.color);
      if (!color) return;
      var d = Math.sqrt(Math.pow(rgb.r - color.r, 2) + Math.pow(rgb.g - color.g, 2) + Math.pow(rgb.b - color.b, 2));
      if (d < distance) { distance = d; best = t; }
    });
    return best && distance <= (threshold === undefined ? 90 : threshold) ? { terrain: best, distance: distance } : null;
  }
  function percentile(values, p) {
    if (!values.length) return 0;
    var a = values.slice().sort(function (x, y) { return x - y; });
    return a[Math.min(a.length - 1, Math.floor((a.length - 1) * p))];
  }
  function heightOf(t) {
    return t && isFinite(Number(t.minHeight)) && isFinite(Number(t.maxHeight))
      ? (Number(t.minHeight) + Number(t.maxHeight)) / 2 : 0.4;
  }
  function insideHex(dx, dy, radius) {
    return Math.abs(dx) <= SQRT3 * radius / 2 && Math.abs(dy) <= radius
      && Math.abs(dy) + Math.abs(dx) / SQRT3 <= radius;
  }
  function cropOf(width, height, opts) {
    var left = finite(opts.cropLeft === undefined ? 0 : opts.cropLeft, "裁剪左边");
    var top = finite(opts.cropTop === undefined ? 0 : opts.cropTop, "裁剪上边");
    var right = finite(opts.cropRight === undefined ? 100 : opts.cropRight, "裁剪右边");
    var bottom = finite(opts.cropBottom === undefined ? 100 : opts.cropBottom, "裁剪下边");
    if (left < 0 || top < 0 || right > 100 || bottom > 100 || right <= left || bottom <= top) throw new Error("裁剪范围必须在 0..100%，右/下大于左/上");
    return { x: width * left / 100, y: height * top / 100, width: width * (right - left) / 100, height: height * (bottom - top) / 100 };
  }
  function makeGrid(width, height, opts) {
    var crop = cropOf(width, height, opts);
    var columns = finite(opts.columns === undefined ? 72 : opts.columns, "横向格数");
    var oq = finite(opts.originQ === undefined ? 0 : opts.originQ, "起点 q");
    var or = finite(opts.originR === undefined ? 0 : opts.originR, "起点 r");
    if (Math.floor(columns) !== columns || columns < 12 || columns > 160) throw new Error("横向格数必须是 12..160 的整数");
    if (!Number.isSafeInteger(oq) || !Number.isSafeInteger(or) || Math.abs(oq) > 1000000000 || Math.abs(or) > 1000000000) throw new Error("起点必须是有效整数");
    var radius = crop.width / (SQRT3 * (columns + 0.5));
    var out = [];
    for (var row = 0, y = crop.y + radius; y <= crop.y + crop.height - radius + 0.001; row++, y += 1.5 * radius) {
      for (var col = 0; col < columns; col++) {
        var x = crop.x + SQRT3 * radius * (col + 0.5 + (row % 2) / 2);
        if (x + SQRT3 * radius / 2 > crop.x + crop.width + 0.001) continue;
        out.push({ q: oq + col - Math.floor(row / 2), r: or + row, imageX: x, imageY: y, imageRadius: radius });
        if (out.length > MAX_CELLS) throw new Error("图幅超过 12000 格，请减小横向格数或调整裁剪范围");
      }
    }
    if (!out.length) throw new Error("裁剪区域过窄，无法生成完整六角格");
    return out;
  }
  function existingGrid(hexes, opts) {
    var scale = finite(opts.scale === undefined ? 1 : opts.scale, "底图比例");
    var cell = finite(opts.cell === undefined ? 22 : opts.cell, "格边长");
    var x = finite(opts.offsetX === undefined ? 0 : opts.offsetX, "底图横移");
    var y = finite(opts.offsetY === undefined ? 0 : opts.offsetY, "底图纵移");
    if (scale <= 0 || cell <= 0) throw new Error("底图比例和格边长必须大于 0");
    return (hexes || []).map(function (h) {
      var p = window.SimosHexGeom.hexToPixel(Number(h.q), Number(h.r), cell);
      return { q: Number(h.q), r: Number(h.r), imageX: (p.x - x) / scale, imageY: (p.y - y) / scale, imageRadius: cell / scale };
    });
  }
  function sampleHex(pixels, width, height, hex) {
    var gray = [], red = [], green = [], blue = [], land = [], water = 0, count = 0;
    var radius = hex.imageRadius;
    var step = Math.max(1, Math.floor(radius / 7));
    for (var y = Math.floor(hex.imageY - radius); y <= hex.imageY + radius; y += step) {
      for (var x = Math.floor(hex.imageX - radius); x <= hex.imageX + radius; x += step) {
        if (x < 0 || y < 0 || x >= width || y >= height || !insideHex(x + 0.5 - hex.imageX, y + 0.5 - hex.imageY, radius)) continue;
        var at = (y * width + x) * 4;
        if (pixels[at + 3] < 32) continue;
        var r = pixels[at], g = pixels[at + 1], b = pixels[at + 2];
        var l = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        count++; gray.push(l); red.push(r); green.push(g); blue.push(b);
        if (b - r > 12 && b > g - 8) water++;
        else land.push(l);
      }
    }
    return { count: count, gray: percentile(gray, 0.5), rgb: { r: percentile(red, 0.5), g: percentile(green, 0.5), b: percentile(blue, 0.5) },
      water: count ? water / count : 0, roughness: percentile(land, 0.9) - percentile(land, 0.1), landSamples: land.length };
  }
  function classify(sample, opts, terrains) {
    var byKey = {};
    terrains.forEach(function (t) { byKey[t.key] = t; });
    var mode = opts.kind || "palette";
    var terrain, height, confidence = 1, distance = 0;
    if (mode === "palette") {
      var threshold = finite(opts.threshold === undefined ? 90 : opts.threshold, "颜色阈值");
      if (threshold < 0) throw new Error("颜色阈值不得为负");
      var nearest = nearestTerrain(sample.rgb, terrains, threshold);
      if (!nearest) return null;
      terrain = nearest.terrain; height = heightOf(terrain); distance = nearest.distance;
      confidence = threshold === 0 ? 1 : 1 - distance / Math.max(1, threshold);
    } else if (mode === "relief") {
      var waterThreshold = finite(opts.waterThreshold === undefined ? 0.45 : opts.waterThreshold, "水体占比");
      var sensitivity = finite(opts.sensitivity === undefined ? 1 : opts.sensitivity, "地形敏感度");
      if (waterThreshold <= 0 || waterThreshold > 1 || sensitivity <= 0 || sensitivity > 5) throw new Error("水体占比须在 (0,1]，敏感度须在 (0,5]");
      var rough = sample.roughness * sensitivity;
      var key = sample.water >= waterThreshold ? "ocean" : rough >= 36 ? "mountains" : rough >= 18 ? "low_hills" : "plains";
      terrain = byKey[key];
      if (!terrain) return null;
      height = heightOf(terrain);
      confidence = sample.water >= waterThreshold ? Math.min(1, 0.55 + sample.water - waterThreshold)
        : Math.min(1, Math.min(Math.abs(rough - 18), Math.abs(rough - 36)) / 15 + 0.3);
    } else if (mode === "height") {
      var black = finite(opts.black === undefined ? 0 : opts.black, "黑点");
      var white = finite(opts.white === undefined ? 255 : opts.white, "白点");
      var sea = finite(opts.seaLevel === undefined ? 0.3 : opts.seaLevel, "海陆分界");
      if (black < 0 || white > 255 || white <= black || sea < 0 || sea > 1) throw new Error("黑白点须在 0..255 且白点大于黑点，海陆分界须在 0..1");
      height = clamp((sample.gray - black) / (white - black), 0, 1);
      if (opts.invert) height = 1 - height;
      if (height < sea) terrain = byKey.ocean;
      else {
        var bands = terrains.filter(function (t) { return t.key !== "ocean"; }).slice()
          .sort(function (a, b) { return Number(a.maxHeight) - Number(b.maxHeight); });
        terrain = bands.find(function (t) { return height < Number(t.maxHeight); }) || bands[bands.length - 1];
        if (terrain && terrain.key === "desert") terrain = byKey.plains;
      }
      if (!terrain) return null;
      confidence = Math.min(1, Math.abs(height - sea) * 8 + 0.4);
    } else throw new Error("未知识别模式");
    return { terrain: terrain.key, height: height, confidence: confidence, distance: distance };
  }
  function buildPlan(hexes, pixels, width, height, options, terrains) {
    var opts = options || {};
    if (!width || !height || !pixels || pixels.length !== width * height * 4) throw new Error("图像像素无效");
    var grid = opts.grid === "generate" ? makeGrid(width, height, opts) : existingGrid(hexes, opts);
    var assignments = [], counts = {}, skipped = 0, existing = {}, added = 0, updated = 0;
    (hexes || []).forEach(function (h) { existing[h.q + "_" + h.r] = true; });
    grid.forEach(function (hex) {
      if (hex.imageX < 0 || hex.imageY < 0 || hex.imageX >= width || hex.imageY >= height) { skipped++; return; }
      var sample = sampleHex(pixels, width, height, hex);
      var result = sample.count ? classify(sample, opts, terrains || []) : null;
      if (!result) { skipped++; return; }
      if (assignments.length >= MAX_CELLS) throw new Error("有效计划超过 12000 格");
      var item = Object.assign({}, hex, result, { water: sample.water, roughness: sample.roughness });
      assignments.push(item); counts[item.terrain] = (counts[item.terrain] || 0) + 1;
      if (existing[item.q + "_" + item.r]) updated++; else added++;
    });
    var scale = Number(opts.scale === undefined ? 1 : opts.scale), offsetX = Number(opts.offsetX || 0), offsetY = Number(opts.offsetY || 0);
    if (opts.grid === "generate" && grid.length) {
      scale = (Number(opts.cell) || 22) / grid[0].imageRadius;
      var first = window.SimosHexGeom.hexToPixel(grid[0].q, grid[0].r, Number(opts.cell) || 22);
      offsetX = first.x - grid[0].imageX * scale; offsetY = first.y - grid[0].imageY * scale;
    }
    return { assignments: assignments, counts: counts, skipped: skipped, added: added, updated: updated,
      overlay: { scale: scale, offsetX: offsetX, offsetY: offsetY }, mode: opts.grid === "generate" ? "merge" : "patch" };
  }
  window.SimosRasterCore = { MAX_CELLS: MAX_CELLS, parseHexColor: parseHexColor, nearestTerrain: nearestTerrain,
    sampleHex: sampleHex, makeGrid: makeGrid, buildPlan: buildPlan, heightOf: heightOf, insideHex: insideHex };
})();
