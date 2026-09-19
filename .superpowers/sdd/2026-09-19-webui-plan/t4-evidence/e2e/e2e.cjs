// M7 T4 e2e —— Playwright 驱动真页面，验证 Canvas 升级（缩放/平移/后端色/区域填充/点选联动）。
// 用法: NODE_PATH=<playwright> node e2e.cjs <base-url> <mode: demo|realmap> <store-dir> <out-dir>
// ★ 退出码：0 = 全部必测步骤 PASS；1 = 有 FAIL（变异轮据此观察红点）。
"use strict";

const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const MODE = process.argv[3];
const STORE = process.argv[4];
const OUT = process.argv[5];
const DB = STORE + "/simos.db";
const CHROME =
  process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";

const failures = [];

function check(name, ok, detail) {
  console.log("STEP " + name + ": " + (ok ? "PASS" : "FAIL") + (detail ? " " + detail : ""));
  if (!ok) {
    failures.push(name);
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function rows() {
  const out = execFileSync(
    "python3",
    [
      "-c",
      "import sqlite3,sys;c=sqlite3.connect('file:'+sys.argv[1]+'?mode=ro',uri=True);" +
        "print(c.execute('select count(*) from revisions').fetchone()[0])",
      DB,
    ],
    { encoding: "utf8" }
  );
  return Number(out.trim());
}

async function api(path, opts) {
  const response = await fetch(BASE + path, opts);
  const text = await response.text();
  let body = null;
  try {
    body = JSON.parse(text);
  } catch (e) {
    body = null;
  }
  return { status: response.status, body, text };
}

function jsonPost(body) {
  return {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body),
  };
}

function writeJson(name, value) {
  fs.writeFileSync(OUT + "/" + name, JSON.stringify(value, null, 2));
}

function hexToRgb(hex) {
  const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(String(hex).trim());
  return [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)];
}

function rgbEq(a, b, tol) {
  return Math.abs(a[0] - b[0]) <= tol && Math.abs(a[1] - b[1]) <= tol && Math.abs(a[2] - b[2]) <= tol;
}

function blend(over, under, alpha) {
  return [0, 1, 2].map((i) => Math.round(over[i] * alpha + under[i] * (1 - alpha)));
}

async function waitMapReady(page, timeout) {
  await page.waitForFunction(
    () => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(),
    null,
    { timeout: timeout || 30000 }
  );
}

async function canvasBox(page) {
  return page.locator("#canvas").boundingBox();
}

async function hexScreen(page, q, r) {
  return page.evaluate(([qq, rr]) => window.SimosMap.screenPointOf(qq, rr), [q, r]);
}

async function debug(page) {
  return page.evaluate(() => window.SimosMap.debug());
}

async function clickPoint(page, pt) {
  const box = await canvasBox(page);
  await page.mouse.click(box.x + pt.x, box.y + pt.y);
}

async function clickHex(page, q, r, offsetFrac) {
  const pt = await hexScreen(page, q, r);
  const dbg = await debug(page);
  const off = offsetFrac ? offsetFrac * dbg.cellSize * dbg.scale : 0;
  await clickPoint(page, { x: pt.x + off, y: pt.y });
}

async function selection(page) {
  return page.evaluate(() => window.SimosApp.getState().selection);
}

async function pixelAt(page, q, r) {
  return page.evaluate(
    ([qq, rr]) => {
      const c = document.getElementById("canvas");
      const pt = window.SimosMap.screenPointOf(qq, rr);
      const dpr = c.width / c.getBoundingClientRect().width;
      const d = c
        .getContext("2d")
        .getImageData(Math.round(pt.x * dpr), Math.round(pt.y * dpr), 1, 1).data;
      return [d[0], d[1], d[2]];
    },
    [q, r]
  );
}

// ── demo 世界（判据：颜色来自后端 / 缩放点选 / 平移点选 / 点选联动 + revision）──

async function runDemo(page) {
  // 造 ≥3 revision（bootstrap=1，两次 unit.RenameUnit → 3；名字不同以证明面板随 revision 变）
  let stateBody = null;
  for (let i = 0; i < 60; i++) {
    stateBody = await api("/api/state");
    if (stateBody.body && stateBody.body.heads && stateBody.body.heads.main) {
      break;
    }
    await sleep(250);
  }
  let head = stateBody.body.heads.main;
  for (const name of ["甲", "乙"]) {
    const response = await api(
      "/api/command",
      jsonPost({
        type: "unit.RenameUnit",
        payloadJson: JSON.stringify({ id: "u-1", name }),
        branch: "main",
        expectedRevision: head,
      })
    );
    if (response.status !== 200) {
      check("seed", false, "status=" + response.status + " " + response.text.slice(0, 120));
      return;
    }
    head = response.body.ref.revision;
  }
  check("seed", head >= 3, "head=" + head + " rows=" + rows());

  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await waitMapReady(page, 30000);
  await page.waitForFunction(
    () => document.getElementById("map-status").textContent.indexOf("已载入") >= 0,
    null,
    { timeout: 15000 }
  );
  await sleep(300);

  // a: 地形色 == 后端 terrainTypes[].color（像素取样 + 内部色表双证）
  const overview = await api("/api/map/overview?branch=main&revision=" + head);
  const desert = (overview.body.terrainTypes || []).find((t) => t.key === "desert");
  const dbg0 = await debug(page);
  const pixelDesert = await pixelAt(page, 1, 3);
  check(
    "a-color-from-backend",
    !!desert &&
      rgbEq(pixelDesert, hexToRgb(desert.color), 1) &&
      dbg0.colorByTerrain.desert === desert.color &&
      dbg0.fallbackWarned === false,
    JSON.stringify({
      backend: desert && desert.color,
      pixel: pixelDesert,
      mapColor: dbg0.colorByTerrain.desert,
    })
  );

  // b: 缩放（锚在目标格）后点选仍准
  await page.evaluate(() => window.SimosMap.resetView());
  await sleep(100);
  const p0 = await hexScreen(page, 1, 3);
  const box = await canvasBox(page);
  await page.mouse.move(box.x + p0.x, box.y + p0.y);
  await page.mouse.wheel(0, -700);
  await sleep(250);
  const dbg1 = await debug(page);
  const p1 = await hexScreen(page, 1, 3);
  await clickPoint(page, p1);
  await page
    .waitForFunction(
      () => {
        const s = window.SimosApp.getState().selection;
        return s && s.kind === "hex";
      },
      null,
      { timeout: 5000 }
    )
    .catch(() => {});
  const selB = await selection(page);
  check(
    "b-zoom-select",
    dbg1.scale > 1.5 &&
      Math.hypot(p1.x - p0.x, p1.y - p0.y) < 6 &&
      selB &&
      selB.kind === "hex" &&
      selB.q === 1 &&
      selB.r === 3,
    JSON.stringify({ scale: dbg1.scale, anchorDrift: [p1.x - p0.x, p1.y - p0.y], selB })
  );

  // c: 平移后点选仍准
  await page.evaluate(() => window.SimosMap.resetView());
  await sleep(100);
  const viewBefore = await page.evaluate(() => window.SimosMap.currentView());
  const box2 = await canvasBox(page);
  await page.mouse.move(box2.x + 60, box2.y + 60);
  await page.mouse.down();
  await page.mouse.move(box2.x + 60 + 90, box2.y + 60 + 55, { steps: 8 });
  await page.mouse.up();
  await sleep(200);
  const viewAfter = await page.evaluate(() => window.SimosMap.currentView());
  await clickHex(page, 1, 2, 0);
  await page
    .waitForFunction(
      () => {
        const s = window.SimosApp.getState().selection;
        return s && s.kind === "hex";
      },
      null,
      { timeout: 5000 }
    )
    .catch(() => {});
  const selC = await selection(page);
  check(
    "c-pan-select",
    viewAfter.tx !== viewBefore.tx &&
      selC &&
      selC.kind === "hex" &&
      selC.q === 1 &&
      selC.r === 2,
    JSON.stringify({ before: viewBefore, after: viewAfter, selC })
  );
  await page.screenshot({ path: OUT + "/screenshot-zoom-pan.png" });

  // unit: 点单位标记 ⇒ selection={kind:"unit",id}，左栏出 id/name
  await page.evaluate(() => window.SimosMap.resetView());
  await sleep(100);
  await clickHex(page, 1, 1, 0);
  await page
    .waitForFunction(
      () => {
        const s = window.SimosApp.getState().selection;
        return s && s.kind === "unit";
      },
      null,
      { timeout: 5000 }
    )
    .catch(() => {});
  const selU = await selection(page);
  const unitAtHead = await api("/api/unit/u-1?branch=main&revision=" + head);
  await page
    .waitForFunction(
      (name) => document.getElementById("selection-detail").textContent.indexOf(name) >= 0,
      unitAtHead.body.name,
      { timeout: 5000 }
    )
    .catch(() => {});
  const unitDetail = await page.evaluate(
    () => document.getElementById("selection-detail").textContent
  );
  check(
    "unit-detail",
    selU && selU.kind === "unit" && selU.id === "u-1" && unitDetail.indexOf("u-1") >= 0,
    JSON.stringify({ selU, unitName: unitAtHead.body.name, detail: unitDetail })
  );

  // d: 拖游标到旧 revision(2) ⇒ 请求 URL 含 revision=2，面板读数来自该 revision
  await page.waitForSelector('.timeline-line[data-branch="main"] .tl-node[data-revision="2"]', {
    timeout: 10000,
  });
  const hexRequests = [];
  page.on("request", (r) => {
    if (r.url().indexOf("/api/map/hex") >= 0) {
      hexRequests.push(r.url());
    }
  });
  const nodeBox = async (rev) =>
    page
      .locator('.timeline-line[data-branch="main"] .tl-node[data-revision="' + rev + '"]')
      .boundingBox();
  const b3 = await nodeBox(3);
  const b2 = await nodeBox(2);
  await page.mouse.move(b3.x + b3.width / 2, b3.y + b3.height / 2);
  await page.mouse.down();
  await page.mouse.move(b2.x + b2.width / 2, b2.y + b2.height / 2, { steps: 8 });
  await page.mouse.up();
  await page.waitForFunction(() => window.SimosApp.getState().revision === 2, null, {
    timeout: 5000,
  });
  await sleep(400);
  const rowsBefore = rows();
  await page.evaluate(() => window.SimosMap.resetView());
  hexRequests.length = 0;
  await clickHex(page, 1, 1, 0.6);
  await page
    .waitForFunction(
      () => {
        const s = window.SimosApp.getState().selection;
        return s && s.kind === "hex" && s.q === 1 && s.r === 1;
      },
      null,
      { timeout: 5000 }
    )
    .catch(() => {});
  await page
    .waitForFunction(
      () => document.getElementById("left-status").textContent.indexOf("main@2") >= 0,
      null,
      { timeout: 5000 }
    )
    .catch(() => {});
  await sleep(200);
  const rowsAfter = rows();
  const urls = hexRequests.slice();
  const rev2Hex = await api("/api/map/hex?q=1&r=1&branch=main&revision=2");
  const rev2Units = await api("/api/units?branch=main&revision=2");
  const unitNameAt2 = ((rev2Units.body.units || []).find((u) => u.id === "u-1") || {}).name;
  const detail2 = await page.evaluate(
    () => document.getElementById("selection-detail").textContent
  );
  check(
    "d-url-revision",
    urls.some((u) => u.indexOf("revision=2") >= 0),
    urls.join(" | ")
  );
  check(
    "d-panel-revision",
    detail2.indexOf(String(unitNameAt2)) >= 0 &&
      detail2.indexOf(String(rev2Hex.body.height)) >= 0 &&
      detail2.indexOf(String(rev2Hex.body.region === null ? "—" : rev2Hex.body.region)) >= 0,
    JSON.stringify({ unitNameAt2, height: rev2Hex.body.height, region: rev2Hex.body.region, detail2 })
  );
  check("d-readonly", rowsBefore === rowsAfter, "rows " + rowsBefore + "->" + rowsAfter);
  await page.screenshot({ path: OUT + "/screenshot-hex-selected.png" });
  writeJson("demo-hex-requests.json", urls);

  // 旧页 /map 仍可用（同一份渲染器、同一份后端色表）
  const oldErrors = [];
  page.on("pageerror", (e) => oldErrors.push(String(e)));
  await page.goto(BASE + "/map", { waitUntil: "networkidle" });
  await waitMapReady(page, 20000);
  await page.waitForFunction(
    () => document.getElementById("map-status").textContent.indexOf("已载入") >= 0,
    null,
    { timeout: 20000 }
  );
  const oldPt = await page.evaluate(() => window.SimosMap.screenPointOf(1, 2));
  const oldBox = await page.locator("#canvas").boundingBox();
  await page.mouse.click(oldBox.x + oldPt.x, oldBox.y + oldPt.y);
  await page
    .waitForFunction(() => document.getElementById("hex-detail").textContent.indexOf("terrain") >= 0, null, {
      timeout: 5000,
    })
    .catch(() => {});
  const oldDetail = await page.evaluate(() => document.getElementById("hex-detail").textContent);
  const oldLegend = await page.evaluate(() => document.getElementById("legend").textContent);
  check(
    "old-page-map",
    oldDetail.indexOf("terrain") >= 0 &&
      oldDetail.indexOf("desert") >= 0 &&
      oldLegend.indexOf("desert=") >= 0 &&
      oldErrors.length === 0,
    JSON.stringify({ oldDetail, oldLegend, oldErrors })
  );
}

// ── 真地图（19441 格）：区域填充 + 缩放/平移/点选冒烟 + 耗时 ─────────────────

async function runRealmap(page) {
  const tLoad0 = Date.now();
  await page.goto(BASE + "/", { waitUntil: "domcontentloaded" });
  await waitMapReady(page, 180000);
  await page.waitForFunction(
    () => document.getElementById("map-status").textContent.indexOf("已载入") >= 0,
    null,
    { timeout: 180000 }
  );
  const loadMs = Date.now() - tLoad0;
  await sleep(8000);

  const timing = await page.evaluate(async () => {
    const samples = [];
    for (let i = 0; i < 2; i++) {
      const t0 = performance.now();
      const response = await fetch("/api/map/overview?branch=main&revision=1");
      const buf = await response.arrayBuffer();
      const t1 = performance.now();
      const text = new TextDecoder().decode(buf);
      const t2 = performance.now();
      const body = JSON.parse(text);
      const t3 = performance.now();
      samples.push({
        transferMs: t1 - t0,
        decodeMs: t2 - t1,
        parseMs: t3 - t2,
        totalMs: t3 - t0,
        bytes: buf.byteLength,
        hexCount: body.hexCount,
      });
    }
    const t4 = performance.now();
    window.SimosMap.render();
    const t5 = performance.now();
    const s0 = performance.now();
    const stateResp = await fetch("/api/state");
    await stateResp.text();
    const s1 = performance.now();
    return { samples, renderMs: t5 - t4, stateMs: s1 - s0, hexCount: samples[0].hexCount };
  });
  check(
    "realmap-load",
    timing.hexCount === 19441,
    JSON.stringify({
      loadMs,
      fetchMs: timing.samples.map((s) => Math.round(s.totalMs)),
      renderMs: Math.round(timing.renderMs),
    stateFetchMs: Math.round(timing.stateMs),
    })
  );

  const regionSource = await api("/api/map/region/test_nation?branch=main&revision=1");
  const regionHex = regionSource.body.hexes[0];
  // 区域可重叠：以 /api/map/hex 的权威 region 为准（此处该 hex 实归 test_annex_target）。
  const hexDetail = await api(
    "/api/map/hex?q=" + regionHex.q + "&r=" + regionHex.r + "&branch=main&revision=1"
  );
  const regionId = hexDetail.body.region;
  const region = await api("/api/map/region/" + encodeURIComponent(regionId) + "?branch=main&revision=1");
  await page.evaluate(() => window.SimosMap.resetView());
  await sleep(150);
  const box = await canvasBox(page);
  let pt = await hexScreen(page, regionHex.q, regionHex.r);
  await page.mouse.move(box.x + pt.x, box.y + pt.y);
  for (let i = 0; i < 8; i++) {
    const dbg = await debug(page);
    if (dbg.scale >= 1.5) {
      break;
    }
    await page.mouse.wheel(0, -700);
    await sleep(80);
  }
  await sleep(250);
  const dbgZoom = await debug(page);
  pt = await hexScreen(page, regionHex.q, regionHex.r);
  const colorA = await pixelAt(page, regionHex.q, regionHex.r);

  await page.click('button[data-mode="region"]');
  await page.waitForFunction(() => document.body.getAttribute("data-mode") === "region");
  await clickPoint(page, pt);
  await page.waitForFunction(
    (id) => window.SimosApp.getState().highlightRegions.indexOf(id) >= 0,
    regionId,
    { timeout: 15000 }
  );
  await page.waitForFunction(
    (n) => window.SimosMap.debug().highlightHexCount === n,
    region.body.hexes.length,
    { timeout: 15000 }
  );
  await sleep(250);
  const colorB = await pixelAt(page, regionHex.q, regionHex.r);
  const expectedFill = blend(hexToRgb("#ffd250"), colorA, 0.42);
  check(
    "e-region-fill",
    colorA[0] !== colorB[0] || colorA[1] !== colorB[1] || colorA[2] !== colorB[2],
    JSON.stringify({ colorA, colorB })
  );
  check(
    "e-region-fill-color",
    rgbEq(colorB, expectedFill, 3),
    JSON.stringify({ colorB, expectedFill })
  );
  check(
    "e-region-count",
    dbgZoom.scale >= 1.5 && (await debug(page)).highlightHexCount === region.body.hexes.length,
    JSON.stringify({
      regionId,
      scale: dbgZoom.scale,
      expected: region.body.hexes.length,
      actual: (await debug(page)).highlightHexCount,
    })
  );
  await page.screenshot({ path: OUT + "/screenshot-region-fill.png" });

  // 缩放/平移/点选冒烟 + 耗时
  await page.evaluate(() => window.SimosMap.resetView());
  await sleep(150);
  const c = await canvasBox(page);
  await page.mouse.move(c.x + c.width / 2, c.y + c.height / 2);
  for (let i = 0; i < 4; i++) {
    await page.mouse.wheel(0, -700);
    await sleep(60);
  }
  await sleep(200);
  const tPan0 = Date.now();
  await page.mouse.move(c.x + c.width / 2, c.y + c.height / 2);
  await page.mouse.down();
  await page.mouse.move(c.x + c.width / 2 + 120, c.y + c.height / 2 + 80, { steps: 10 });
  await page.mouse.up();
  await sleep(250);
  const panMs = Date.now() - tPan0;
  await page.mouse.click(c.x + c.width / 2, c.y + c.height / 2);
  let smokeSel = null;
  try {
    await page.waitForFunction(
      () => {
        const s = window.SimosApp.getState().selection;
        return s && s.kind === "hex";
      },
      null,
      { timeout: 5000 }
    );
    smokeSel = await selection(page);
  } catch (e) {
    smokeSel = null;
  }
  check(
    "realmap-pan-select",
    !!smokeSel && smokeSel.kind === "hex",
    JSON.stringify({ panMs, smokeSel })
  );
  writeJson("realmap-timing.json", {
    loadMs,
    overviewSamples: timing.samples.map((s) => ({
      transferMs: Math.round(s.transferMs),
      decodeMs: Math.round(s.decodeMs),
      parseMs: Math.round(s.parseMs),
      totalMs: Math.round(s.totalMs),
      bytes: s.bytes,
    })),
    renderMs: Math.round(timing.renderMs),
    stateFetchMs: Math.round(timing.stateMs),
    panMs,
    scaleAfterZoom: dbgZoom.scale,
    regionId,
    regionHexCount: region.body.hexes.length,
    highlightedHexCount: (await debug(page)).highlightHexCount,
    regionHex,
  });
}

(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: CHROME });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  page.on("console", (msg) => {
    if (msg.type() === "warning" || msg.type() === "error") {
      console.log("PAGE-" + msg.type().toUpperCase() + ": " + msg.text());
    }
  });
  page.on("pageerror", (err) => {
    console.log("PAGE-ERROR: " + (err && err.message ? err.message : err));
  });
  try {
    if (MODE === "demo") {
      await runDemo(page);
    } else {
      await runRealmap(page);
    }
  } catch (e) {
    check("exception", false, e && e.stack ? e.stack : String(e));
  }
  await browser.close();
  console.log("E2E RESULT: " + (failures.length === 0 ? "PASS" : "FAIL " + failures.join(",")));
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((e) => {
  console.log("E2E RESULT: FAIL exception " + (e && e.stack ? e.stack : e));
  process.exit(1);
});
