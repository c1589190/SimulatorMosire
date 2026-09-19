// M9 T2/T4/T5 —— 真档 e2e 断言（去重 / render 隔离修复 / updateLegend 记忆化）。
// 用法: NODE_PATH=<playwright> node t2-e2e.cjs <base-url> <out-json>
// 输出: { assertions:[{id, pass, detail}], pageErrors, requestCounts }
"use strict";

const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const OUT = process.argv[3];
const CHROME =
  process.env.CHROME_PATH ||
  "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
const VW = 1280;
const VH = 800;

const result = {
  base: BASE,
  startedAt: new Date().toISOString(),
  assertions: [],
  pageErrors: [],
  consoleErrors: [],
  requestCounts: {},
  raw: {},
  fatal: null,
};

function assert(id, pass, detail) {
  result.assertions.push({ id, pass: !!pass, detail: String(detail) });
}
function summarize(samples) {
  const s = samples.slice().sort((a, b) => a - b);
  return { p50: +s[Math.floor((s.length - 1) * 0.5)].toFixed(2), max: +s[s.length - 1].toFixed(2) };
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
  const context = await browser.newContext({ viewport: { width: VW, height: VH }, deviceScaleFactor: 1 });
  const page = await context.newPage();
  page.setDefaultTimeout(120000);
  page.on("pageerror", (e) => result.pageErrors.push(String(e && e.message ? e.message : e)));
  page.on("console", (m) => {
    if (m.type() === "error") result.consoleErrors.push(m.text());
  });

  const counts = {};
  page.on("request", (req) => {
    let key;
    try {
      const u = new URL(req.url());
      key = u.pathname + (u.search || "");
    } catch (e) {
      key = req.url();
    }
    counts[key] = (counts[key] || 0) + 1;
  });

  try {
    await page.goto(BASE + "/", { waitUntil: "commit", timeout: 30000 });
    await page.waitForFunction(
      () =>
        window.SimosMap &&
        window.SimosMap.isReady &&
        window.SimosMap.isReady() &&
        (document.getElementById("map-status") || {}).textContent &&
        document.getElementById("map-status").textContent.indexOf("已载入") >= 0,
      null,
      { timeout: 120000, polling: 100 }
    );
    await page.evaluate(() => window.SimosMap.render());

    // ── A：去重 ─────────────────────────────────────────────────────
    result.requestCounts = JSON.parse(JSON.stringify(counts));
    assert(
      "a-overview-no-target-once",
      counts["/api/map/overview?branch=main"] === 1,
      "count=" + counts["/api/map/overview?branch=main"]
    );
    assert(
      "a-overview-rev1-once",
      counts["/api/map/overview?branch=main&revision=1"] === 1,
      "count=" + counts["/api/map/overview?branch=main&revision=1"]
    );
    assert(
      "a-units-no-target-once",
      counts["/api/units?branch=main"] === 1,
      "count=" + counts["/api/units?branch=main"]
    );
    assert(
      "a-units-rev1-once",
      counts["/api/units?branch=main&revision=1"] === 1,
      "count=" + counts["/api/units?branch=main&revision=1"]
    );
    assert("a-state-startup-once", counts["/api/state"] === 1, "count=" + counts["/api/state"]);

    // memo：同 target 再取 ⇒ 不发新请求。
    const before = JSON.parse(JSON.stringify(counts));
    await page.evaluate(async () => {
      await window.SimosApi.cachedMapOverview({ branch: "main", revision: 1 });
      await window.SimosApi.cachedMapOverview({ branch: "main", revision: 1 });
      await window.SimosApi.cachedUnits({ branch: "main", revision: 1 });
      await window.SimosApi.cachedUnits({ branch: "main", revision: 1 });
    });
    const afterMemo = JSON.parse(JSON.stringify(counts));
    const newOverviewMemo =
      (afterMemo["/api/map/overview?branch=main&revision=1"] || 0) -
      (before["/api/map/overview?branch=main&revision=1"] || 0);
    const newUnitsMemo =
      (afterMemo["/api/units?branch=main&revision=1"] || 0) -
      (before["/api/units?branch=main&revision=1"] || 0);
    assert("a-memo-no-new-request", newOverviewMemo === 0 && newUnitsMemo === 0, "overview+" + newOverviewMemo + " units+" + newUnitsMemo);

    // target 变化必须失效：新 revision ⇒ 必发新请求。
    await page.evaluate(async () => {
      try {
        await window.SimosApi.cachedMapOverview({ branch: "main", revision: 2 });
      } catch (e) {
        /* 服务端可能 404；这里只看有没有发请求 */
      }
    });
    const newOverviewTarget =
      (counts["/api/map/overview?branch=main&revision=2"] || 0);
    assert("a-target-invalidates", newOverviewTarget >= 1, "new-revision requests=" + newOverviewTarget);

    // ── B：render 修复 ───────────────────────────────────────────────
    const debug0 = await page.evaluate(() => window.SimosMap.debug());
    result.raw.debug0 = debug0;

    const warm = await page.evaluate(() => {
      const out = [];
      for (let i = 0; i < 7; i++) {
        const t0 = performance.now();
        window.SimosMap.render();
        out.push(performance.now() - t0);
      }
      return out;
    });
    result.raw.warm = summarize(warm);
    assert("b-render-warm-p50-lt33", summarize(warm).p50 < 33, "p50=" + summarize(warm).p50);

    // ★ 冷重建口径：只关缓存，**不改 terrainMode / borderChunk**——测的必须是生产默认的路径，
    //   否则把默认改成 giant 的变异体杀不掉（本装置第一版就犯过，见 mutations 的 B-m4）。
    const cold = await page.evaluate(() => {
      const cfg = window.SimosMap.perfConfig;
      const savedCache = cfg.terrainCacheEnabled;
      const savedLod = cfg.borderMinScreenPx;
      cfg.terrainCacheEnabled = false;
      cfg.borderMinScreenPx = 4;
      const out = [];
      for (let i = 0; i < 7; i++) {
        const t0 = performance.now();
        window.SimosMap.render();
        out.push(performance.now() - t0);
      }
      cfg.terrainCacheEnabled = savedCache;
      cfg.borderMinScreenPx = savedLod;
      return out;
    });
    result.raw.cold = summarize(cold);
    assert("b-render-cold-p50-lt33", summarize(cold).p50 < 33, "p50=" + summarize(cold).p50);

    const tv = await page.evaluate(() => {
      const cfg = window.SimosMap.perfConfig;
      const saved = cfg.terrainCacheEnabled;
      cfg.terrainCacheEnabled = false;
      const r = window.SimosMap.benchTerrainVariants(3);
      cfg.terrainCacheEnabled = saved;
      return r;
    });
    const tvGiant = summarize(tv.giant).p50;
    const tvChunk = summarize(tv.chunked).p50;
    result.raw.terrainVariants = { giant: summarize(tv.giant), chunked: summarize(tv.chunked), perHex: summarize(tv.perHex) };
    assert("b-terrain-giant-path-is-cause", tvGiant > tvChunk * 5, "giant=" + tvGiant + " chunked=" + tvChunk);

    const bv = await page.evaluate(() => window.SimosMap.benchBorderVariants(3));
    const bvGiant = summarize(bv.giant).p50;
    const bvChunk = summarize(bv.chunked64).p50;
    result.raw.borderVariants = { giant: summarize(bv.giant), chunked64: summarize(bv.chunked64), perHex: summarize(bv.perHex) };
    assert("b-border-giant-path-is-cause", bvGiant > bvChunk * 5, "giant=" + bvGiant + " chunked=" + bvChunk);

    // 边框 LOD：适配比例下（半径 < 阈值）渲染不改边框遍数。
    const lodBefore = await page.evaluate(() => {
      window.SimosMap.resetView();
      window.SimosMap.render();
      return window.SimosMap.debug();
    });
    await page.evaluate(() => {
      window.SimosMap.render();
      window.SimosMap.render();
      window.SimosMap.render();
    });
    const lodAfter = await page.evaluate(() => window.SimosMap.debug());
    result.raw.lod = { before: lodBefore.borderDraws, after: lodAfter.borderDraws, borderScreenPx: lodAfter.borderScreenPx, threshold: 4 };
    assert(
      "b-border-lod-skipped-at-fit",
      lodAfter.borderDraws === lodBefore.borderDraws && lodAfter.borderScreenPx < 4,
      "draws " + lodBefore.borderDraws + "->" + lodAfter.borderDraws + " screenPx=" + lodAfter.borderScreenPx.toFixed(2)
    );

    // 地形位图缓存：微小 pan 不重建、只 blit。
    const panCache = await page.evaluate(() => {
      window.SimosMap.resetView();
      window.SimosMap.render();
      const before = window.SimosMap.debug();
      const v = window.SimosMap.currentView();
      window.SimosMap.setView({ scale: v.scale, tx: v.tx + 50, ty: v.ty });
      window.SimosMap.render();
      window.SimosMap.render();
      const after = window.SimosMap.debug();
      window.SimosMap.resetView();
      return { before, after };
    });
    result.raw.panCache = {
      rebuilds: panCache.before.terrainRebuilds + "->" + panCache.after.terrainRebuilds,
      blits: panCache.before.terrainBlits + "->" + panCache.after.terrainBlits,
    };
    assert(
      "b-pan-does-not-rebuild",
      panCache.after.terrainRebuilds === panCache.before.terrainRebuilds &&
        panCache.after.terrainBlits > panCache.before.terrainBlits,
      "rebuilds " + result.raw.panCache.rebuilds + " blits " + result.raw.panCache.blits
    );

    // 数据/尺寸变 ⇒ 必须重建（用 resize 触发 terrainDirty）。
    const resizeDirty = await page.evaluate(() => {
      window.SimosMap.resetView();
      window.SimosMap.render();
      const before = window.SimosMap.debug();
      window.dispatchEvent(new Event("resize"));
      window.SimosMap.render();
      const after = window.SimosMap.debug();
      return { before, after };
    });
    result.raw.resizeDirty = {
      rebuilds: resizeDirty.before.terrainRebuilds + "->" + resizeDirty.after.terrainRebuilds,
    };
    assert(
      "b-resize-rebuilds-terrain",
      resizeDirty.after.terrainRebuilds > resizeDirty.before.terrainRebuilds,
      "rebuilds " + result.raw.resizeDirty.rebuilds
    );

    // ── C：updateLegend 记忆化（旧页 reload 同时触发 setData + setUnits）──
    const page2 = await context.newPage();
    page2.setDefaultTimeout(120000);
    page2.on("pageerror", (e) => result.pageErrors.push("[map] " + String(e && e.message ? e.message : e)));
    await page2.goto(BASE + "/map", { waitUntil: "commit", timeout: 30000 });
    await page2.waitForFunction(() => window.SimosMap && window.SimosMap.isReady(), null, { timeout: 120000, polling: 100 });
    await page2.evaluate(() => window.SimosMap.render());
    const legend0 = await page2.evaluate(() => window.SimosMap.debug());
    await page2.click("#reload");
    await page2.waitForTimeout(1500);
    await page2.evaluate(() => window.SimosMap.render());
    const legend1 = await page2.evaluate(() => window.SimosMap.debug());
    result.raw.legend = { before: legend0.legendScans, after: legend1.legendScans };
    assert(
      "c-legend-memoized-scan-once-per-overview",
      legend1.legendScans === legend0.legendScans + 1,
      "scans " + legend0.legendScans + "->" + legend1.legendScans + "（期望 +1：仅 setData 重扫）"
    );
    await page2.close();
  } catch (e) {
    result.fatal = String(e && e.stack ? e.stack : e);
  }

  result.pageErrorCount = result.pageErrors.length;
  assert("z-zero-pageerror", result.pageErrors.length === 0, "pageErrors=" + result.pageErrors.length);
  result.finishedAt = new Date().toISOString();
  await browser.close().catch(() => {});
  fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
  const failed = result.assertions.filter((a) => !a.pass);
  console.log("=== ASSERTIONS ===");
  result.assertions.forEach((a) => console.log((a.pass ? "PASS " : "FAIL ") + a.id + " | " + a.detail));
  console.log("=== SUMMARY === pass=" + (result.assertions.length - failed.length) + " fail=" + failed.length + " pageErrors=" + result.pageErrors.length);
}

main().catch((e) => {
  console.error("e2e crash:", e);
  process.exit(1);
});
