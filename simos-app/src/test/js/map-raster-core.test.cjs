// map-raster-core.test.cjs —— 底图识别纯算法的真实行为（整格采样 / 三识别 / 网格 / 边界拒绝）。
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui } = require("./helpers/webui-loader.cjs");

const CORE_FILE = path.resolve(
  __dirname,
  "../../../../simos-map/src/main/resources/webui/modules/map/map-raster-core.js"
);

function loadCore() {
  const geom = loadWebui("hexgeom.js").SimosHexGeom;
  const win = { SimosHexGeom: geom };
  const sandbox = { window: win, console };
  win.window = win;
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(CORE_FILE, "utf8"), sandbox, { filename: CORE_FILE });
  return win.SimosRasterCore;
}

const CORE = loadCore();
const TERRAINS = [
  { key: "ocean", name: "海洋", color: "#1F5FA0", minHeight: 0, maxHeight: 0.3 },
  { key: "plains", name: "平原", color: "#9CCB5B", minHeight: 0.3, maxHeight: 0.45 },
  { key: "desert", name: "沙漠", color: "#E7C86E", minHeight: 0.38, maxHeight: 0.55 },
  { key: "low_hills", name: "丘陵", color: "#B8A24A", minHeight: 0.45, maxHeight: 0.7 },
  { key: "mountains", name: "山地", color: "#8C6E4A", minHeight: 0.7, maxHeight: 1.1 },
];

function hexToRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
}

function solidPixels(width, height, hex, alpha) {
  const pixels = new Uint8ClampedArray(width * height * 4);
  const c = hexToRgb(hex);
  for (let i = 0; i < width * height; i++) {
    pixels[i * 4] = c.r;
    pixels[i * 4 + 1] = c.g;
    pixels[i * 4 + 2] = c.b;
    pixels[i * 4 + 3] = alpha === undefined ? 255 : alpha;
  }
  return pixels;
}

function put(pixels, width, x, y, hex, alpha) {
  const c = hexToRgb(hex);
  const at = (Math.floor(y) * width + Math.floor(x)) * 4;
  pixels[at] = c.r;
  pixels[at + 1] = c.g;
  pixels[at + 2] = c.b;
  pixels[at + 3] = alpha === undefined ? 255 : alpha;
}

// ★ 独立重写的 point-in-hex：不调用被测的 insideHex，避免“用实现证明实现”。
function inside(dx, dy, radius) {
  const sqrt3 = Math.sqrt(3);
  return (
    Math.abs(dx) <= (sqrt3 * radius) / 2 &&
    Math.abs(dy) <= radius &&
    Math.abs(dy) + Math.abs(dx) / sqrt3 <= radius
  );
}

function existingPlan(width, height, centerX, centerY, radius, pixels, options, terrains) {
  return CORE.buildPlan(
    [{ q: 0, r: 0 }],
    pixels,
    width,
    height,
    Object.assign(
      {
        grid: "existing",
        scale: 1,
        cell: radius,
        offsetX: -centerX,
        offsetY: -centerY,
      },
      options || {}
    ),
    terrains || TERRAINS
  );
}

test("sampleHex-covers-the-whole-hex-not-just-the-center-3x3", () => {
  const width = 81;
  const height = 81;
  const radius = 30;
  const pixels = solidPixels(width, height, "#9CCB5B");
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      if (inside(x + 0.5 - 40, y + 0.5 - 40, radius)) {
        put(pixels, width, x, y, "#E02020");
      }
    }
  }
  // 只有正中心 3×3 是绿色：只取中心的采样器会得到绿色。
  for (let y = 39; y <= 41; y++) {
    for (let x = 39; x <= 41; x++) put(pixels, width, x, y, "#9CCB5B");
  }
  const sample = CORE.sampleHex(pixels, width, height, {
    imageX: 40,
    imageY: 40,
    imageRadius: radius,
  });
  assert.ok(sample.count > 0, "必须在六角内部采到像素");
  assert.ok(sample.rgb.r > 180 && sample.rgb.g < 120, "中位色必须来自占多数的边缘区域");
  assert.equal(sample.water, 0);
});

test("sampleHex-skips-transparent-and-out-of-image-pixels", () => {
  const transparent = solidPixels(21, 21, "#9CCB5B", 0);
  const sample = CORE.sampleHex(transparent, 21, 21, {
    imageX: 10,
    imageY: 10,
    imageRadius: 12,
  });
  assert.equal(sample.count, 0, "全透明图不得产生可分类样本");
  const outside = CORE.sampleHex(transparent, 21, 21, {
    imageX: -100,
    imageY: -100,
    imageRadius: 5,
  });
  assert.equal(outside.count, 0, "图外格不得产生样本");
});

test("relief-uses-water-ratio-and-local-texture-not-center-color", () => {
  const blue = solidPixels(81, 81, "#1F5FA0");
  const water = existingPlan(81, 81, 40, 40, 30, blue, { kind: "relief" });
  assert.equal(water.assignments[0].terrain, "ocean");
  assert.equal(water.assignments[0].height, 0.15, "水体高度取地形带中点");

  const smooth = solidPixels(81, 81, "#9CCB5B");
  const plains = existingPlan(81, 81, 40, 40, 30, smooth, { kind: "relief" });
  assert.equal(plains.assignments[0].terrain, "plains");
  assert.equal(plains.assignments[0].roughness, 0);

  const rough = solidPixels(81, 81, "#9CCB5B");
  for (let y = 0; y < 81; y++) {
    for (let x = 0; x < 81; x++) {
      if (inside(x + 0.5 - 40, y + 0.5 - 40, 30)) {
        put(rough, 81, x, y, (x * 37 + y * 17) % 101 < 50 ? "#202020" : "#E8E8E8");
      }
    }
  }
  const mountains = existingPlan(81, 81, 40, 40, 30, rough, { kind: "relief" });
  assert.equal(mountains.assignments[0].terrain, "mountains");
  assert.ok(mountains.assignments[0].roughness >= 36);
});

test("relief-thin-blue-river-does-not-paint-the-whole-hex-ocean", () => {
  const width = 81;
  const height = 81;
  const pixels = solidPixels(width, height, "#9CCB5B");
  for (let y = 8; y < 72; y++) {
    for (let x = 39; x <= 41; x++) put(pixels, width, x, y, "#1F5FA0");
  }
  const plan = existingPlan(width, height, 40, 40, 30, pixels, {
    kind: "relief",
    waterThreshold: 0.45,
  });
  assert.equal(plan.assignments.length, 1);
  assert.equal(plan.assignments[0].terrain, "plains");
  assert.ok(plan.assignments[0].water < 0.45, "细河占比必须低于水体阈值");
});

test("height-mode-inverts-and-calibrates-black-white-points", () => {
  const mid = solidPixels(41, 41, "#333333");
  const low = existingPlan(41, 41, 20, 20, 18, mid, {
    kind: "height",
    black: 0,
    white: 255,
    seaLevel: 0.3,
  });
  assert.equal(low.assignments[0].terrain, "ocean");
  assert.ok(Math.abs(low.assignments[0].height - 0.2) < 1e-9);

  const inverted = existingPlan(41, 41, 20, 20, 18, mid, {
    kind: "height",
    black: 0,
    white: 255,
    seaLevel: 0.3,
    invert: true,
  });
  assert.ok(Math.abs(inverted.assignments[0].height - 0.8) < 1e-9);
  assert.equal(inverted.assignments[0].terrain, "mountains");

  const calibrated = solidPixels(41, 41, "#808080");
  const calibratedPlan = existingPlan(41, 41, 20, 20, 18, calibrated, {
    kind: "height",
    black: 64,
    white: 192,
    seaLevel: 0.3,
  });
  assert.ok(Math.abs(calibratedPlan.assignments[0].height - 0.5) < 1e-9);
  assert.equal(calibratedPlan.assignments[0].terrain, "plains", "高度落在沙漠带也不得自动判沙漠");
});

test("height-mode-does-not-infer-desert-from-elevation-and-rejects-bad-calibration", () => {
  const gray = solidPixels(41, 41, "#808080");
  const plan = existingPlan(41, 41, 20, 20, 18, gray, {
    kind: "height",
    black: 0,
    white: 255,
    seaLevel: 0.3,
  });
  assert.notEqual(plan.assignments[0].terrain, "desert", "高度不能自动断定沙漠");
  assert.equal(plan.assignments[0].terrain, "plains");

  assert.throws(
    () =>
      existingPlan(41, 41, 20, 20, 18, gray, {
        kind: "height",
        black: 200,
        white: 100,
      }),
    /黑白点/
  );
  assert.throws(
    () =>
      existingPlan(41, 41, 20, 20, 18, gray, {
        kind: "height",
        seaLevel: 1.2,
      }),
    /海陆分界/
  );
});

test("palette-threshold-zero-never-falls-back-to-a-default-terrain", () => {
  const far = solidPixels(41, 41, "#123456");
  const rejected = existingPlan(41, 41, 20, 20, 18, far, {
    kind: "palette",
    threshold: 0,
  });
  assert.equal(rejected.assignments.length, 0);
  assert.equal(rejected.skipped, 1);

  const exact = solidPixels(41, 41, "#9CCB5B");
  const accepted = existingPlan(41, 41, 20, 20, 18, exact, {
    kind: "palette",
    threshold: 0,
  });
  assert.equal(accepted.assignments.length, 1);
  assert.equal(accepted.assignments[0].terrain, "plains");
  assert.equal(accepted.assignments[0].distance, 0);

  assert.throws(
    () =>
      existingPlan(41, 41, 20, 20, 18, exact, {
        kind: "palette",
        threshold: -1,
      }),
    /颜色阈值/
  );
});

test("buildPlan-skips-transparent-hex-and-unknown-relief-terrain-instead-of-defaulting", () => {
  const transparent = solidPixels(41, 41, "#9CCB5B", 0);
  const skipped = existingPlan(41, 41, 20, 20, 18, transparent, { kind: "relief" });
  assert.equal(skipped.assignments.length, 0);
  assert.equal(skipped.skipped, 1);

  const smooth = solidPixels(41, 41, "#9CCB5B");
  const missingPlains = existingPlan(
    41,
    41,
    20,
    20,
    18,
    smooth,
    { kind: "relief" },
    [
      { key: "ocean", color: "#1F5FA0", minHeight: 0, maxHeight: 0.3 },
      { key: "mountains", color: "#8C6E4A", minHeight: 0.7, maxHeight: 1.1 },
    ]
  );
  assert.equal(missingPlains.assignments.length, 0, "词表缺该地形时必须跳过，不能兜默认");
  assert.equal(missingPlains.skipped, 1);
});

test("makeGrid-generates-pointy-top-axial-coordinates-and-honors-origin-crop", () => {
  const full = CORE.makeGrid(720, 360, { columns: 72 });
  assert.ok(full.length > 0);
  assert.deepEqual({ q: full[0].q, r: full[0].r }, { q: 0, r: 0 });
  const firstRow = full.filter((h) => h.r === 0);
  assert.equal(firstRow.length, 72);
  assert.deepEqual(
    firstRow.map((h) => h.q),
    Array.from({ length: 72 }, (_, i) => i)
  );
  const secondRow = full.filter((h) => h.r === 1);
  assert.equal(secondRow[0].q, 0, "row=1 的 floor(1/2)=0，q 不平移");
  const thirdRow = full.filter((h) => h.r === 2);
  assert.equal(thirdRow[0].q, -1, "row=2 按 floor(row/2)=1 平移 q");
  assert.ok(full.every((h) => Number.isInteger(h.q) && Number.isInteger(h.r)));

  const origin = CORE.makeGrid(720, 360, {
    columns: 24,
    originQ: 10,
    originR: -5,
  });
  assert.equal(origin[0].q, 10);
  assert.equal(origin[0].r, -5);

  const cropped = CORE.makeGrid(720, 360, {
    columns: 24,
    cropLeft: 25,
    cropRight: 75,
    cropTop: 25,
    cropBottom: 75,
  });
  assert.ok(cropped.length > 0);
  assert.ok(cropped.every((h) => h.imageX >= 180 - 0.001));
  assert.ok(cropped.every((h) => h.imageY >= 90 - 0.001));
});

test("makeGrid-rejects-bad-columns-crop-origin-and-more-than-12000-cells", () => {
  assert.equal(CORE.MAX_CELLS, 12000);
  for (const columns of [11, 161, 12.5, 0, -3, NaN]) {
    assert.throws(() => CORE.makeGrid(720, 360, { columns }), /横向格数/);
  }
  assert.throws(
    () => CORE.makeGrid(720, 360, { columns: 24, cropLeft: 60, cropRight: 40 }),
    /裁剪范围/
  );
  assert.throws(
    () => CORE.makeGrid(720, 360, { columns: 24, cropTop: -1 }),
    /裁剪范围/
  );
  assert.throws(
    () => CORE.makeGrid(720, 360, { columns: 24, originQ: 1.5 }),
    /起点/
  );
  assert.throws(
    () => CORE.makeGrid(2000, 1200, { columns: 160 }),
    /12000/
  );
});

test("existingGrid-maps-world-pixels-through-offset-and-scale", () => {
  const image = solidPixels(100, 60, "#1F5FA0");
  for (let y = 0; y < 60; y++) {
    for (let x = 50; x < 100; x++) put(image, 100, x, y, "#9CCB5B");
  }
  const mapped = CORE.buildPlan(
    [{ q: 0, r: 0 }],
    image,
    100,
    60,
    { grid: "existing", scale: 1, cell: 10, offsetX: -80, offsetY: -20 },
    TERRAINS
  );
  assert.equal(mapped.mode, "patch");
  assert.deepEqual(mapped.overlay, { scale: 1, offsetX: -80, offsetY: -20 });
  assert.equal(mapped.assignments.length, 1);
  assert.equal(
    mapped.assignments[0].terrain,
    "plains",
    "offset 必须把世界原点映射到图片右侧：映射错了会采到左半的海洋"
  );
});

test("buildPlan-reports-mode-added-updated-and-overlay", () => {
  const image = solidPixels(180, 90, "#9CCB5B");
  const generated = CORE.buildPlan(
    [],
    image,
    180,
    90,
    { grid: "generate", columns: 12 },
    TERRAINS
  );
  assert.equal(generated.mode, "merge");
  assert.ok(generated.assignments.length > 0);
  assert.equal(generated.updated, 0);
  assert.equal(generated.added, generated.assignments.length);
  assert.equal(
    Object.values(generated.counts).reduce((a, b) => a + b, 0),
    generated.assignments.length
  );
  assert.ok(generated.overlay.scale > 0);

  const existing = existingPlan(41, 41, 20, 20, 18, solidPixels(41, 41, "#9CCB5B"));
  assert.equal(existing.mode, "patch");
  assert.equal(existing.added, 0);
  assert.equal(existing.updated, 1);

  assert.throws(
    () => CORE.buildPlan([], image.slice(0, 10), 180, 90, { grid: "generate", columns: 12 }, TERRAINS),
    /图像像素无效/
  );
});
