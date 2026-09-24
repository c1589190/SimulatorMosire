// readout.test.cjs —— readout.js 移动读数（★ 2026-09-24 日制裁定）。
//
// 裁定：`speed` / `speedAtDeparture` 单位是 **MP/小时**（数值不动），一天 = 24 小时
//   ⇒ **日预算 = speed × 1000 × 24**（毫 MP/天）。`etaTick` 语义不变（值就是"从出发起多少天"）。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui } = require("./helpers/webui-loader.cjs");

const R = loadWebui("readout.js").SimosReadout;

/** 最小在途单位：route 有下一格 ⇒ 参与成本计算；无 SimosBlocks ⇒ 地形取不到（成本不可算，但速率仍可断言）。 */
function movingUnit(overrides) {
  const movement = Object.assign(
    {
      speedAtDeparture: 2,
      mobilityPerMilleAtDeparture: 1000,
      status: "MOVING",
      currentHex: { q: 0, r: 0 },
      nextHex: { q: 1, r: 0 },
      remainingMillis: 0,
      departedAt: { tick: 0 },
      route: { path: [{ q: 0, r: 0 }, { q: 1, r: 0 }] },
    },
    overrides || {}
  );
  return { movement: movement };
}

test("daily-budget-rate-multiplies-speed-by-1000-and-24", () => {
  const readout = R.movementReadout(movingUnit(), null);
  assert.equal(
    readout.budgetPerTickMillis,
    48000,
    "speedAtDeparture=2 ⇒ 2 × 1000 × 24 = 48000 毫 MP/天（旧式漏 ×24 会得 2000）"
  );
});

test("etaTick-stays-days-from-departure-with-the-24h-rate", () => {
  // 每格成本 = 地形成本 500 × 出发机动 1000‰ = 500000 毫；日预算 = 2×1000×24 = 48000 毫/天
  // ⇒ ceil(500000 / 48000) = 11 天 ⇒ etaTick = 0 + 11（**天数口径**，漏 ×24 会得 250）。
  const simosBlocks = { terrainAt: (blocks, q, r) => (q === 1 && r === 0 ? "plain" : null) };
  const R2 = loadWebui("readout.js", { SimosBlocks: simosBlocks }).SimosReadout;
  const overview = {
    blocks: [{ q: 1, r: 0, terrain: "plain" }],
    terrainTypes: [{ key: "plain", moveCost: 500 }],
  };
  const readout = R2.movementReadout(movingUnit(), overview);
  assert.equal(readout.totalCostMillis, 500000);
  assert.equal(readout.etaTick, 11);
});
