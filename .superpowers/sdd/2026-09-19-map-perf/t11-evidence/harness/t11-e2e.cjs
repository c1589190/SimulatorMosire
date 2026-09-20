// M9 T11 e2e + HiDPI：紧凑块线格式 / 视口不触发网络 / 点选 / dpr 锐度与帧率。
// 用法: NODE_PATH=<playwright> node t11-e2e.cjs <base-url> <out-json> [dpr]
"use strict";

const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const OUT = process.argv[3];
const DPR = Number(process.argv[4] || 1);
const CHROME =
  process.env.CHROME_PATH ||
  "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
const VW = 1280;
const VH = 800;

const result = {
  base: BASE,
  dpr: DPR,
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
function median(xs) {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

async function main() {
  // ── 线格式（Node 侧，先于浏览器，避免污染网络记账）──
  const wire1 = await (await fetch(BASE + "/api/map/overview?branch=main&revision=1")).text();
  const wire2 = await (await fetch(BASE + "/api/map/overview?branch=main&revision=1")).text();
  const ov = JSON.parse(wire1);
  const blocks = ov.blocks || [];
  result.raw.wire = {
    bytes: Buffer.byteLength(wire1),
    byteIdentical: wire1 === wire2,
    hasHexes: wire1.includes('"hexes"'),
    hasHeight: wire1.includes('"height"'),
    hasXY: wire1.includes('"x":') || wire1.includes('"y":'),
    blockCount: blocks.length,
    ringCount: blocks.reduce((n, b) => n + (b.boundaries || []).length, 0),
    vertexCount: blocks.reduce((n, b) => n + (b.boundaries || []).reduce((m, r) => m + r.length / 2, 0), 0),
    allInts: blocks.every((b) => (b.boundaries || []).every((r) => r.every(Number.isInteger))),
    hexCount: ov.hexCount,
    colors: Object.fromEntries((ov.terrainTypes || []).map((t) => [t.key, t.color])),
  };
  const w = result.raw.wire;
  assert("wire-no-per-hex-channel", !w.hasHexes && !w.hasHeight, `hexes=${w.hasHexes} height=${w.hasHeight}`);
  assert("wire-no-xy-objects-uv-int-labels", !w.hasXY && w.allInts, `hasXY=${w.hasXY} allInts=${w.allInts}`);
  assert("wire-single-shot-under-150k", w.bytes < 150000, `bytes=${w.bytes}`);
  assert("wire-two-calls-byte-identical", w.byteIdentical, `byteIdentical=${w.byteIdentical}`);

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
    deviceScaleFactor: DPR,
  });
  const page = await context.newPage();
  page.setDefaultTimeout(120000);
  page.on("pageerror", (e) => result.pageErrors.push(String(e && e.message ? e.message : e)));
  page.on("console", (m) => {
    if (m.type() === "error") result.consoleErrors.push(m.text());
  });
  const reqLog = [];
  page.on("request", (r) => {
    const entry = { method: r.method(), url: r.url(), t: Date.now() };
    reqLog.push(entry);
    if (r.method() !== "GET") result.nonGET.push(r.method() + " " + r.url());
  });

  await page.goto(BASE + "/", { waitUntil: "commit", timeout: 30000 });
  await page.waitForFunction(
    () => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(),
    null,
    { timeout: 60000, polling: 100 }
  );
  await page.waitForFunction(
    () => {
      const s = document.getElementById("map-status");
      return s && s.textContent && s.textContent.indexOf("已载入") >= 0;
    },
    null,
    { timeout: 60000, polling: 100 }
  );
  await page.waitForTimeout(800);

  // ── HiDPI：backing store 尺寸 + 内容铺满 + 设备坐标像素对拍 ──
  result.raw.debug = await page.evaluate(() => window.SimosMap.debug());
  assert(
    "blocks-partition-covers-all",
    result.raw.debug.unmergedCount === 0,
    "hexCount=" + w.hexCount + " unmergedCount=" + result.raw.debug.unmergedCount
  );

  const geom = await page.evaluate(() => {
    const c = document.getElementById("canvas");
    const r = c.getBoundingClientRect();
    return { canvasW: c.width, canvasH: c.height, cssW: r.width, cssH: r.height, dpr: window.devicePixelRatio };
  });
  result.raw.geometry = geom;
  assert(
    "hidpi-canvas-backing-scaled",
    Math.abs(geom.canvasW - Math.round(geom.cssW * geom.dpr)) <= 2 &&
      Math.abs(geom.canvasH - Math.round(geom.cssH * geom.dpr)) <= 2,
    JSON.stringify(geom)
  );
  const box = await page.evaluate(() => {
    const r = document.getElementById("canvas").getBoundingClientRect();
    return { x: r.x, y: r.y };
  });

  const samples = [
    [0, 0],
    [0, -1],
    [3, 18],
    [-11, -1],
    [-5, -59],
    [18, 53],
  ];
  const rows = [];
  for (const [q, r] of samples) {
    const hex = await page.evaluate(
      async ({ q, r }) => (await fetch(`/api/map/hex?q=${q}&r=${r}`)).json(),
      { q, r }
    );
    rows.push({ q, r, expected: hex.terrain });
  }
  result.raw.rows = rows;
  const pixels = await page.evaluate(
    ({ rows }) => {
      const canvas = document.getElementById("canvas");
      const dpr = window.devicePixelRatio || 1;
      const g = canvas.getContext("2d");
      return rows.map(({ q, r }) => {
        const pt = window.SimosMap.screenPointOf(q, r);
        const px = Math.round(pt.x * dpr);
        const py = Math.round(pt.y * dpr);
        const inBounds = px >= 0 && py >= 0 && px < canvas.width && py < canvas.height;
        const d = inBounds ? g.getImageData(px, py, 1, 1).data : [0, 0, 0, 0];
        return { q, r, px, py, inBounds, rgba: [d[0], d[1], d[2], d[3]] };
      });
    },
    { rows }
  );
  result.raw.pixels = pixels;
  for (const p of pixels) {
    const row = rows.find((x) => x.q === p.q && x.r === p.r);
    const exp = hex2rgb(w.colors[row.expected] || "#000000");
    const dist =
      Math.abs(p.rgba[0] - exp[0]) + Math.abs(p.rgba[1] - exp[1]) + Math.abs(p.rgba[2] - exp[2]);
    assert(
      `pixel-${p.q}_${p.r}`,
      p.inBounds && dist <= 12,
      `expected ${row.expected} ${exp} got ${p.rgba} dist=${dist} at(${p.px},${p.py})`
    );
  }

  // ── dpr 锐度（在 fit 视图上做，保证两格中心都在视口内）──
  //   方法：把 ocean/plains 相邻两格的屏幕中点放大，再沿两格中心连线量过渡带宽度（设备像素）。
  const pair = { q1: 3, r1: 18, q2: 2, r2: 18, c1: hex2rgb(w.colors.ocean), c2: hex2rgb(w.colors.plains) };
  const fitDist = await page.evaluate(
    ({ q1, r1, q2, r2 }) => {
      const a = window.SimosMap.screenPointOf(q1, r1);
      const b = window.SimosMap.screenPointOf(q2, r2);
      return { a, b, d: Math.hypot(a.x - b.x, a.y - b.y) };
    },
    pair
  );
  result.raw.sharpnessFit = fitDist;
  const mid = { x: (fitDist.a.x + fitDist.b.x) / 2, y: (fitDist.a.y + fitDist.b.y) / 2 };
  await page.mouse.move(box.x + mid.x, box.y + mid.y);
  let pairDist = fitDist.d;
  const wheelSteps = [];
  for (let i = 0; i < 40 && pairDist < 200; i++) {
    await page.mouse.wheel(0, -240);
    await page.waitForTimeout(40);
    pairDist = await page.evaluate(
      ({ q1, r1, q2, r2 }) => {
        const a = window.SimosMap.screenPointOf(q1, r1);
        const b = window.SimosMap.screenPointOf(q2, r2);
        return Math.hypot(a.x - b.x, a.y - b.y);
      },
      pair
    );
    wheelSteps.push(pairDist);
  }
  result.raw.zoomToPair = { fitDist: fitDist.d, finalCssDist: pairDist, wheels: wheelSteps.length };
  await page.waitForTimeout(400);
  const sharp = await page.evaluate((p) => {
    const canvas = document.getElementById("canvas");
    const g = canvas.getContext("2d");
    const dpr = window.devicePixelRatio || 1;
    const a = window.SimosMap.screenPointOf(p.q1, p.r1);
    const b = window.SimosMap.screenPointOf(p.q2, p.r2);
    const x1 = a.x * dpr;
    const y1 = a.y * dpr;
    const x2 = b.x * dpr;
    const y2 = b.y * dpr;
    const N = Math.max(2, Math.round(Math.hypot(x2 - x1, y2 - y1)));
    const cols = [];
    for (let i = 0; i <= N; i++) {
      const t = i / N;
      const x = Math.round(x1 + (x2 - x1) * t);
      const y = Math.round(y1 + (y2 - y1) * t);
      const inB = x >= 0 && y >= 0 && x < canvas.width && y < canvas.height;
      const d = inB ? g.getImageData(x, y, 1, 1).data : [0, 0, 0, 0];
      cols.push([d[0], d[1], d[2]]);
    }
    const near = (c, e, tol) => Math.abs(c[0] - e[0]) + Math.abs(c[1] - e[1]) + Math.abs(c[2] - e[2]) <= tol;
    let lastA = -1;
    let firstB = -1;
    for (let i = 0; i < cols.length; i++) if (near(cols[i], p.c1, 45)) lastA = i;
    for (let i = 0; i < cols.length; i++) {
      if (near(cols[i], p.c2, 45)) {
        firstB = i;
        break;
      }
    }
    let width = null;
    if (lastA >= 0 && firstB > lastA) {
      width = 0;
      for (let i = lastA + 1; i < firstB; i++) width += 1;
    }
    let maxStep = 0;
    for (let i = 1; i < cols.length; i++) {
      const d = Math.hypot(cols[i][0] - cols[i - 1][0], cols[i][1] - cols[i - 1][1], cols[i][2] - cols[i - 1][2]);
      if (d > maxStep) maxStep = d;
    }
    return { dpr, N, lastA, firstB, widthDevice: width, maxStep: Math.round(maxStep), samples: cols };
  }, pair);
  result.raw.sharpness = sharp;
  assert(
    "hidpi-transition-is-sharp",
    sharp.widthDevice !== null && sharp.widthDevice <= 3 * sharp.dpr && sharp.N >= 3,
    `widthDevice=${sharp.widthDevice} N=${sharp.N} dpr=${sharp.dpr} maxStep=${sharp.maxStep}`
  );
  await page.evaluate(() => window.SimosMap.resetView && window.SimosMap.resetView());
  await page.waitForTimeout(400);

  // ── 点选（fit 视图）：断言读到 plains + 记录这一次的请求 ──
  const clickPt = await page.evaluate(() => window.SimosMap.screenPointOf(0, 0));
  const markSelect = Date.now();
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
  assert("click-selects-hex", result.raw.selectionText.indexOf("plains") >= 0, result.raw.selectionText.slice(0, 120));
  result.raw.selectRequests = reqLog
    .filter((e) => e.t >= markSelect && e.url.includes("/api/"))
    .map((e) => e.method + " " + e.url.replace(BASE, ""));

  await page.evaluate(() => window.SimosMap.resetView && window.SimosMap.resetView());
  await page.waitForTimeout(400);

  // ── pan 3s：帧率 + 网络（视口内不触发任何取数）──
  const markPan = Date.now();
  await page.evaluate(() => {
    window.__fr = [];
    window.__frStop = false;
    window.__frLast = performance.now();
    window.__frLoop = (t) => {
      if (window.__frStop) return;
      window.__fr.push(t - window.__frLast);
      window.__frLast = t;
      requestAnimationFrame(window.__frLoop);
    };
    requestAnimationFrame(window.__frLoop);
  });
  const cx = box.x + VW / 2;
  const cy = box.y + VH / 2;
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  const panT0 = Date.now();
  let step = 0;
  while (Date.now() - panT0 < 3000) {
    const a = step * 0.35;
    await page.mouse.move(cx + Math.cos(a) * 160, cy + Math.sin(a) * 100);
    step += 1;
    await page.waitForTimeout(16);
  }
  await page.mouse.up();
  const frames = await page.evaluate(() => {
    window.__frStop = true;
    return window.__fr.slice();
  });
  result.raw.frames = {
    count: frames.length,
    p50: median(frames),
    p95: frames.length ? [...frames].sort((a, b) => a - b)[Math.floor(frames.length * 0.95)] : null,
  };

  // ── 缩放：同样不应触发取数 ──
  await page.mouse.move(cx, cy);
  for (let i = 0; i < 6; i++) {
    await page.mouse.wheel(0, -240);
    await page.waitForTimeout(60);
  }
  await page.waitForTimeout(400);
  const markNet = markPan;
  const mapData = (u) =>
    u.includes("/api/map/") || u.includes("/api/units") || u.includes("/api/unit/") || u.includes("/api/social/");
  result.raw.viewportRequests = reqLog
    .filter((e) => e.t >= markNet && e.url.includes("/api/"))
    .map((e) => e.method + " " + e.url.replace(BASE, ""));
  result.raw.viewportMapDataRequests = reqLog
    .filter((e) => e.t >= markNet && mapData(e.url))
    .map((e) => e.method + " " + e.url.replace(BASE, ""));
  assert(
    "pan-zoom-issue-no-map-data-requests",
    result.raw.viewportMapDataRequests.length === 0,
    "mapDataRequests=" + JSON.stringify(result.raw.viewportMapDataRequests) + " all=" + JSON.stringify(result.raw.viewportRequests)
  );
  assert(
    "pan-frames-at-least-30fps",
    result.raw.frames.count >= 60 && result.raw.frames.p50 !== null && result.raw.frames.p50 <= 33,
    JSON.stringify(result.raw.frames)
  );

  await page.screenshot({ path: OUT.replace(/\.json$/, ".png") });
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
  for (const a of result.assertions) if (!a.pass) console.log("  FAIL", a.id, "|", a.detail);
  console.log("pageErrors:", result.pageErrors.length, "consoleErrors:", result.consoleErrors.length, "nonGET:", result.nonGET);
  console.log("frames:", JSON.stringify(result.raw.frames), "sharpness:", JSON.stringify({ dpr: sharp.dpr, widthDevice: sharp.widthDevice, maxStep: sharp.maxStep, N: sharp.N }));
  console.log("selectRequests:", JSON.stringify(result.raw.selectRequests));
  console.log("viewportRequests:", JSON.stringify(result.raw.viewportRequests));
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
