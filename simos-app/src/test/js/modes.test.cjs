// modes.test.cjs —— 五模式写权限白名单（M8 T7 / spec §三 Q7）。纯函数，无 DOM/IO。
// ★ 冻结夹具：每个模式的允许/拒绝逐条写死，不拿被测函数自身输出当期望。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui } = require("./helpers/webui-loader.cjs");

const M = loadWebui("modes.js").SimosModes;

test("module-loads", () => {
  assert.equal(typeof M.isWriteAllowed, "function");
});

test("mode-ids-order", () => {
  assert.deepEqual(M.modeIds(), ["view", "region", "map-edit", "region-edit", "unit"]);
});

test("mode-labels", () => {
  assert.equal(M.modeLabel("view"), "常规");
  assert.equal(M.modeLabel("region"), "区域查看");
  assert.equal(M.modeLabel("map-edit"), "地图编辑");
  assert.equal(M.modeLabel("region-edit"), "区域编辑");
  assert.equal(M.modeLabel("unit"), "单位移动编辑");
});

test("mode-label-unknown-passthrough", () => {
  assert.equal(M.modeLabel("nope"), "nope");
});

test("view-allows-no-write", () => {
  assert.deepEqual(M.allowedWrites("view"), []);
  assert.equal(M.isWriteAllowed("view", "map.SetTerrain"), false);
  assert.equal(M.isWriteAllowed("view", "unit.PlanRoute"), false);
});

test("region-allows-no-write", () => {
  assert.deepEqual(M.allowedWrites("region"), []);
  assert.equal(M.isWriteAllowed("region", "map.SetTerrain"), false);
  assert.equal(M.isWriteAllowed("region", "map.UpdateRegion"), false);
});

test("map-edit-allows-exactly-four-writes", () => {
  assert.deepEqual(M.allowedWrites("map-edit"), [
    "map.SetTerrain",
    "map.SetEdge",
    "map.RandomizeRegion",
    "map.UpdateRegion",
  ]);
  assert.equal(M.isWriteAllowed("map-edit", "map.SetTerrain"), true);
  assert.equal(M.isWriteAllowed("map-edit", "map.SetEdge"), true);
  assert.equal(M.isWriteAllowed("map-edit", "map.RandomizeRegion"), true);
  assert.equal(M.isWriteAllowed("map-edit", "map.UpdateRegion"), true);
  assert.equal(M.isWriteAllowed("map-edit", "map.CreateRegion"), false);
  assert.equal(M.isWriteAllowed("map-edit", "unit.PlanRoute"), false);
});

test("region-edit-allows-three-writes", () => {
  assert.deepEqual(M.allowedWrites("region-edit"), [
    "map.CreateRegion",
    "map.UpdateRegion",
    "map.DeleteRegion",
  ]);
  assert.equal(M.isWriteAllowed("region-edit", "map.SetTerrain"), false);
});

test("unit-allows-route-and-editor-writes", () => {
  assert.equal(M.isWriteAllowed("unit", "unit.PlanRoute"), true);
  assert.equal(M.isWriteAllowed("unit", "unit.CancelRoute"), true);
  assert.equal(M.isWriteAllowed("unit", "unit.ReparentUnit"), true);
  assert.equal(M.isWriteAllowed("unit", "unit.SetStrength"), true);
  assert.equal(M.isWriteAllowed("unit", "unit.DisbandUnit"), true);
  assert.equal(M.isWriteAllowed("unit", "unit.CreateUnit"), true);
  assert.equal(M.isWriteAllowed("unit", "map.SetTerrain"), false);
});

test("fail-closed-on-unknown-mode-and-empty-type", () => {
  assert.equal(M.isWriteAllowed("nope", "map.SetTerrain"), false);
  assert.equal(M.isWriteAllowed("view", ""), false);
  assert.equal(M.isWriteAllowed("view", null), false);
  assert.equal(M.isWriteAllowed("view", undefined), false);
  assert.equal(M.isWriteAllowed(undefined, "map.SetTerrain"), false);
  assert.equal(M.isWriteAllowed(null, "map.SetTerrain"), false);
});

test("allowedWrites-returns-a-snapshot", () => {
  const copy = M.allowedWrites("map-edit");
  copy.push("unit.CreateUnit");
  assert.equal(M.allowedWrites("map-edit").includes("unit.CreateUnit"), false);
  assert.equal(M.isWriteAllowed("map-edit", "unit.CreateUnit"), false);
});
