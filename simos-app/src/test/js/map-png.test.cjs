// map-png.test.cjs —— D-036 P5：PNG 取色纯函数（module 资产 simos-map/webui/modules/map/map-png.js）。
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui } = require("./helpers/webui-loader.cjs");

const MODULE_FILE = path.resolve(
  __dirname,
  "../../../../simos-map/src/main/resources/webui/modules/map/map-png.js"
);
const MANIFEST_FILE = path.resolve(
  __dirname,
  "../../../../simos-map/src/main/resources/webui/modules/map/manifest.json"
);

function loadPngModule() {
  const geom = loadWebui("hexgeom.js").SimosHexGeom;
  const win = {};
  const doc = {
    readyState: "complete",
    getElementById() {
      return null;
    },
    addEventListener() {},
    createElement() {
      return {
        width: 0,
        height: 0,
        getContext() {
          return { drawImage() {}, getImageData() { return { data: new Uint8ClampedArray(0) }; } };
        },
      };
    },
  };
  const sandbox = {
    window: win,
    document: doc,
    console,
    URL: { createObjectURL() { return "blob:test"; }, revokeObjectURL() {} },
  };
  win.window = win;
  win.document = doc;
  win.SimosHexGeom = geom;
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(path.join(path.dirname(MODULE_FILE), "map-raster-core.js"), "utf8"), sandbox);
  vm.runInContext(fs.readFileSync(MODULE_FILE, "utf8"), sandbox, { filename: MODULE_FILE });
  return win.SimosMapPng;
}

const P = loadPngModule();
const TERRAINS = [
  { key: "ocean", name: "海洋", color: "#1F5FA0" },
  { key: "plains", name: "平原", color: "#9CCB5B" },
  { key: "desert", name: "沙漠", color: "#E7C86E" },
];

function solidPixels(width, height, hex) {
  const pixels = new Uint8ClampedArray(width * height * 4);
  const n = parseInt(hex.slice(1), 16);
  for (let i = 0; i < width * height; i++) {
    pixels[i * 4] = (n >> 16) & 255;
    pixels[i * 4 + 1] = (n >> 8) & 255;
    pixels[i * 4 + 2] = n & 255;
    pixels[i * 4 + 3] = 255;
  }
  return pixels;
}

test("parseHexColor-accepts-six-digit-and-rejects-others", () => {
  assert.deepEqual(P.parseHexColor("#9CCB5B"), { r: 156, g: 203, b: 91 });
  assert.equal(P.parseHexColor("9CCB5B"), null);
  assert.equal(P.parseHexColor("#FFF"), null);
  assert.equal(P.parseHexColor(""), null);
});

test("nearestTerrain-picks-nearest-and-honors-threshold", () => {
  const exact = P.nearestTerrain({ r: 156, g: 203, b: 91 }, TERRAINS, 10);
  assert.equal(exact.terrain.key, "plains");
  const far = P.nearestTerrain({ r: 255, g: 0, b: 255 }, TERRAINS, 10);
  assert.equal(far, null);
});

test("buildPlan-preserves-palette-compatibility-with-area-sampling", () => {
  const plan = P.buildPlan(
    [{ q: 0, r: 0 }],
    solidPixels(12, 12, "#9CCB5B"),
    12,
    12,
    { offsetX: 0, offsetY: 0, scale: 1, cell: 22, threshold: 20 },
    TERRAINS
  );
  assert.equal(plan.assignments.length, 1);
  assert.equal(plan.assignments[0].terrain, "plains");
  assert.equal(plan.counts.plains, 1);
  assert.equal(plan.skipped, 0);
});

test("buildPlan-skips-hex-outside-image", () => {
  const plan = P.buildPlan(
    [{ q: 1, r: 0 }],
    solidPixels(4, 4, "#9CCB5B"),
    4,
    4,
    { offsetX: 0, offsetY: 0, scale: 1, cell: 22, threshold: 20 },
    TERRAINS
  );
  assert.equal(plan.assignments.length, 0);
  assert.equal(plan.skipped, 1);
});

test("module-manifest-lists-map-png-asset", () => {
  const manifest = JSON.parse(fs.readFileSync(MANIFEST_FILE, "utf8"));
  assert.equal(manifest.webuiId, "map");
  assert.ok(manifest.assets.includes("map-png.js"));
});
