// M9 T4 —— render() 隔离微基准（真档、真 Chromium、适配比例）。
// 用法: NODE_PATH=<playwright> node micro.cjs <base-url> <out-json> [label]
// 产出：各段原始毫秒样本（调用方算 p50）+ 巨路径/分块/逐格三档对比。
"use strict";

const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const OUT = process.argv[3];
const LABEL = process.argv[4] || "micro";
const CHROME =
  process.env.CHROME_PATH ||
  "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
const VW = 1280;
const VH = 800;
const N = 7;
const NV = 5;

const result = {
  label: LABEL,
  base: BASE,
  viewport: { width: VW, height: VH },
  startedAt: new Date().toISOString(),
  ready: false,
  debug: null,
  stages: null,
  variants: null,
  renderAfter: null,
  pageErrors: [],
  fatal: null,
};

function pct(sorted, p) {
  if (!sorted.length) return null;
  const idx = (sorted.length - 1) * p;
  const lo = Math.floor(idx);
  const hi = Math.ceil(idx);
  if (lo === hi) return sorted[lo];
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo);
}
function summarize(samples) {
  const s = samples.slice().sort((a, b) => a - b);
  const round = (v) => (v === null ? null : +v.toFixed(2));
  return {
    n: samples.length,
    p50: round(pct(s, 0.5)),
    p95: round(pct(s, 0.95)),
    min: round(s[0]),
    max: round(s[s.length - 1]),
    mean: round(s.reduce((a, b) => a + b, 0) / s.length),
    samples: samples.map((v) => +v.toFixed(2)),
  };
}

async function main() {
  const browser = await chromium.launch({
    executablePath: CHROME,
    headless: true,
    args: [
      "--no-sandbox",
      "--disable-background-timer-throttling",
      "--disable-renderer-backgrounding",
      "--disable-backgrounding-occluded-windows",
      "--force-color-profile=srgb",
    ],
  });
  const context = await browser.newContext({
    viewport: { width: VW, height: VH },
    deviceScaleFactor: 1,
  });
  const page = await context.newPage();
  page.setDefaultTimeout(120000);
  page.on("pageerror", (e) => result.pageErrors.push(String(e && e.message ? e.message : e)));

  try {
    await page.goto(BASE + "/", { waitUntil: "commit", timeout: 30000 });
    // 等首次绘制完成（可交互）。
    await page.waitForFunction(
      () =>
        window.SimosMap &&
        window.SimosMap.isReady &&
        window.SimosMap.isReady() &&
        (document.getElementById("map-status") || {}).textContent &&
        document.getElementById("map-status").textContent.indexOf("已载入") >= 0,
      null,
      { timeout: 120000, polling: 200 }
    );
    result.ready = true;
    // 稳定一帧。
    await page.evaluate(() => window.SimosMap.render());

    result.debug = await page.evaluate(() => window.SimosMap.debug());

    // 生产默认配置（chunked + LOD + 位图缓存）下的 render()。
    const renderDefault = await page.evaluate((n) => {
      const out = [];
      for (let i = 0; i < n; i++) {
        const t0 = performance.now();
        window.SimosMap.render();
        out.push(performance.now() - t0);
      }
      return out;
    }, N);
    result.renderDefault = summarize(renderDefault);
    result.debugDefault = await page.evaluate(() => window.SimosMap.debug());

    // 隔离配置 = 原行为：巨路径 + 边框恒绘 + 无位图缓存。
    const stages = await page.evaluate((n) => {
      const cfg = window.SimosMap.perfConfig;
      cfg.terrainMode = "giant";
      cfg.borderMinScreenPx = 0;
      cfg.terrainCacheEnabled = false;
      return window.SimosMap.benchStages(n);
    }, N);
    result.stages = {};
    Object.keys(stages).forEach((k) => {
      result.stages[k] = summarize(stages[k]);
    });

    const variants = await page.evaluate((n) => {
      const cfg = window.SimosMap.perfConfig;
      cfg.terrainCacheEnabled = false;
      return window.SimosMap.benchTerrainVariants(n);
    }, NV);
    result.variants = {};
    Object.keys(variants).forEach((k) => {
      result.variants[k] = summarize(variants[k]);
    });

    // 分块（生产）配置下的各段计时（cache 关，边框恒绘，便于与 isolation 表逐段对比）。
    const stagesChunked = await page.evaluate((n) => {
      const cfg = window.SimosMap.perfConfig;
      cfg.terrainMode = "chunked";
      cfg.borderMinScreenPx = 0;
      cfg.terrainCacheEnabled = false;
      return window.SimosMap.benchStages(n);
    }, N);
    result.stagesChunked = {};
    Object.keys(stagesChunked).forEach((k) => {
      result.stagesChunked[k] = summarize(stagesChunked[k]);
    });

    // 生产配置但 cache 关（= 一次冷重建的真实成本：chunked + LOD 生效）。
    const renderNoCache = await page.evaluate((n) => {
      const cfg = window.SimosMap.perfConfig;
      cfg.terrainMode = "chunked";
      cfg.borderMinScreenPx = 4;
      cfg.terrainCacheEnabled = false;
      const out = [];
      for (let i = 0; i < n; i++) {
        const t0 = performance.now();
        window.SimosMap.render();
        out.push(performance.now() - t0);
      }
      return out;
    }, N);
    result.renderNoCache = summarize(renderNoCache);
    result.debugNoCache = await page.evaluate(() => window.SimosMap.debug());

    const borders = await page.evaluate((n) => window.SimosMap.benchBorderVariants(n), NV);
    result.borderVariants = {};
    Object.keys(borders).forEach((k) => {
      result.borderVariants[k] = summarize(borders[k]);
    });

    const sweep = await page.evaluate((n) => window.SimosMap.benchChunkSweep(n), NV);
    result.chunkSweep = {};
    Object.keys(sweep).forEach((k) => {
      result.chunkSweep[k] = summarize(sweep[k]);
    });

    // 回到默认配置后的 render() 总成本（缓存关闭时 = 原始）。
    const after = await page.evaluate((n) => {
      window.SimosMap.perfConfig.terrainCacheEnabled = false;
      window.SimosMap.perfConfig.terrainMode = "giant";
      window.SimosMap.perfConfig.borderMinScreenPx = 0;
      const out = [];
      for (let i = 0; i < n; i++) {
        const t0 = performance.now();
        window.SimosMap.render();
        out.push(performance.now() - t0);
      }
      return out;
    }, N);
    result.renderAfter = summarize(after);
  } catch (e) {
    result.fatal = String(e && e.stack ? e.stack : e);
  }

  await browser.close().catch(() => {});
  result.finishedAt = new Date().toISOString();
  fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
  console.log("=== MICRO JSON ===");
  console.log(JSON.stringify(result, null, 2));
}

main().catch((e) => {
  console.error("micro crash:", e);
  try {
    fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
  } catch (e2) {}
  process.exit(1);
});
