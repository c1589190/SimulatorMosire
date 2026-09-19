// M9 T13/T14 真档 e2e：块多边形渲染 + 拾取（含洞）+ 像素色 + 点选 + 区域高亮。
// 用法: NODE_PATH=<playwright> node t13-e2e.cjs <base-url> <out-json> <screenshot-png>
"use strict";

const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const OUT = process.argv[3];
const SHOT = process.argv[4];
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
  nonGET: [],
  raw: {},
  fatal: null,
};

function assert(id, pass, detail) {
  result.assertions.push({ id, pass: !!pass, detail: String(detail) });
}

function hex2rgb(c) {
  return [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
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
  page.on("console", (m) => {
    if (m.type() === "error") result.consoleErrors.push(m.text());
  });
  page.on("request", (r) => {
    if (r.method() !== "GET") result.nonGET.push(r.method() + " " + r.url());
  });

  await page.goto(BASE + "/", { waitUntil: "commit", timeout: 30000 });
  await page.waitForFunction(() => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(), null, {
    timeout: 60000,
    polling: 100,
  });
  await page.waitForFunction(
    () => {
      const s = document.getElementById("map-status");
      return s && s.textContent && s.textContent.indexOf("已载入") >= 0;
    },
    null,
    { timeout: 60000, polling: 100 }
  );
  await page.waitForTimeout(800);

  const ov = await page.evaluate(async () => {
    const r = await fetch("/api/map/overview");
    return await r.json();
  });
  const colorByTerrain = {};
  (ov.terrainTypes || []).forEach((t) => {
    colorByTerrain[t.key] = t.color;
  });
  result.raw.blockCount = (ov.blocks || []).length;
  result.raw.hexCount = ov.hexCount;
  result.raw.hasHexes = Object.prototype.hasOwnProperty.call(ov, "hexes");
  result.raw.debug = await page.evaluate(() => window.SimosMap.debug());
  assert("no-per-hex-array", result.raw.hasHexes === false, "overview.hasOwnProperty('hexes')=" + result.raw.hasHexes);
  assert(
    "blocks-partition-covers-all",
    result.raw.debug.unmergedCount === 0,
    "hexCount=" + ov.hexCount + " unmergedCount=" + result.raw.debug.unmergedCount
  );

  const samples = [
    [0, 0],
    [-5, -59],
    [-64, 72],
    [18, 53],
    [1, 1],
    [40, -40],
    [-42, -1],
    [-11, 56],
    [69, -78],
    [-68, 3],
  ];
  const rows = [];
  for (const [q, r] of samples) {
    const hex = await page.evaluate(
      async ({ q, r }) => {
        const x = await fetch(`/api/map/hex?q=${q}&r=${r}`);
        return await x.json();
      },
      { q, r }
    );
    const picked = await page.evaluate(
      ({ q, r }) => {
        const pt = window.SimosMap.screenPointOf(q, r);
        return window.SimosMap.hexAtScreen(pt);
      },
      { q, r }
    );
    rows.push({ q, r, expected: hex.terrain, height: hex.height, inMap: picked.inMap, got: picked.terrain });
    assert(
      `pick-${q}_${r}-terrain`,
      picked.inMap && picked.terrain === hex.terrain,
      `expected=${hex.terrain} got=${picked.terrain} inMap=${picked.inMap}`
    );
  }
  result.raw.rows = rows;

  const pixels = await page.evaluate(
    ({ samples }) => {
      const canvas = document.getElementById("canvas");
      const dpr = window.devicePixelRatio || 1;
      const g = canvas.getContext("2d");
      const out = [];
      for (const [q, r] of samples) {
        const pt = window.SimosMap.screenPointOf(q, r);
        const d = g.getImageData(Math.round(pt.x * dpr), Math.round(pt.y * dpr), 1, 1).data;
        out.push({ q, r, rgba: [d[0], d[1], d[2], d[3]] });
      }
      return out;
    },
    { samples }
  );
  result.raw.pixels = pixels;
  for (const p of pixels) {
    const row = rows.find((x) => x.q === p.q && x.r === p.r);
    const exp = hex2rgb(colorByTerrain[row.expected] || "#000000");
    const dist = Math.abs(p.rgba[0] - exp[0]) + Math.abs(p.rgba[1] - exp[1]) + Math.abs(p.rgba[2] - exp[2]);
    assert(`pixel-${p.q}_${p.r}`, dist <= 12, `expected ${row.expected} ${exp} got ${p.rgba} dist=${dist}`);
  }

  // 点选：(0,0) 在视口中心、浮层之外；断言左栏出现地形读数。
  const box = await page.evaluate(() => {
    const c = document.getElementById("canvas");
    const r = c.getBoundingClientRect();
    return { x: r.x, y: r.y };
  });
  const clickPt = await page.evaluate(() => window.SimosMap.screenPointOf(0, 0));
  await page.mouse.click(box.x + clickPt.x, box.y + clickPt.y);
  await page.waitForFunction(
    () => {
      const d = document.getElementById("selection-detail");
      return d && d.textContent && d.textContent.indexOf("plains") >= 0;
    },
    null,
    { timeout: 30000, polling: 100 }
  );
  result.raw.selectionText = await page.evaluate(
    () => (document.getElementById("selection-detail") || {}).textContent || ""
  );
  assert("click-selects-hex", result.raw.selectionText.indexOf("plains") >= 0, result.raw.selectionText.slice(0, 160));

  // 区域：详情端点 + 高亮路径（M7 T4/T6 的面）。
  const regions = ov.regions || [];
  assert("overview-lists-regions", regions.length > 0, "regions=" + regions.length);
  if (regions.length) {
    const regionId = regions[0].id;
    const detail = await page.evaluate(async (id) => {
      const r = await fetch(`/api/map/region/${encodeURIComponent(id)}`);
      return { status: r.status, body: await r.json() };
    }, regionId);
    assert("region-detail-ok", detail.status === 200 && (detail.body.hexes || []).length > 0, JSON.stringify({ id: regionId, status: detail.status, hexes: (detail.body.hexes || []).length }));
    await page.evaluate((id) => window.SimosApp.setHighlightRegions([id]), regionId);
    await page.waitForTimeout(600);
    const hl = await page.evaluate(() => window.SimosMap.debug().highlightHexCount);
    assert("region-highlight-active", hl > 0, "highlightHexCount=" + hl);
  }

  await page.screenshot({ path: SHOT });

  try {
    await browser.close();
  } catch (e) {
    /* ignore */
  }
  result.finishedAt = new Date().toISOString();
  const passCount = result.assertions.filter((a) => a.pass).length;
  result.summary = `${passCount}/${result.assertions.length} PASS`;
  fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
  console.log("summary:", result.summary);
  for (const a of result.assertions) {
    if (!a.pass) console.log("  FAIL", a.id, "|", a.detail);
  }
  console.log("pageErrors:", result.pageErrors.length, "nonGET:", result.nonGET);
  process.exit(passCount === result.assertions.length && result.pageErrors.length === 0 ? 0 : 1);
}

main().catch((e) => {
  result.fatal = String(e && e.stack ? e.stack : e);
  try {
    fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
  } catch (e2) {}
  console.error("fatal:", result.fatal);
  process.exit(1);
});
