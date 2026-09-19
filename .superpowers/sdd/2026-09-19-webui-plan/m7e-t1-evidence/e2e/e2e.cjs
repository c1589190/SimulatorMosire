// M7e T1 e2e —— 用户交互表：右键空白取消选中（不清路线）/ 左键已选中单位发 unit.CancelRoute / 左键未选中单位只选中 /
// 右键下路线回归；折线对比度修复的像素对照。
// 用法: NODE_PATH=<playwright> node e2e.cjs <base-url> <store-dir> <out-dir>
// ★ 退出码：0 = 全部必测步骤 PASS；1 = 有 FAIL（变异轮据此观察红点）。
"use strict";

const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const STORE = process.argv[3];
const OUT = process.argv[4];

const failures = [];
const values = {};

function check(name, ok, detail) {
  console.log("STEP " + name + ": " + (ok ? "PASS" : "FAIL") + (detail ? " " + detail : ""));
  if (!ok) {
    failures.push(name);
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitFor(fn, timeout) {
  const deadline = Date.now() + (timeout || 8000);
  for (;;) {
    const value = await fn();
    if (value) {
      return value;
    }
    if (Date.now() > deadline) {
      return null;
    }
    await sleep(80);
  }
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

function writeJson(name, value) {
  fs.writeFileSync(OUT + "/" + name, JSON.stringify(value, null, 2));
}

function pathText(path) {
  return (path || []).map((h) => "(" + h.q + "," + h.r + ")").join("->");
}

function mapDebug(page) {
  return page.evaluate(() => window.SimosMap.debug());
}

function stateRevision(page) {
  return page.evaluate(() => window.SimosApp.getState().revision);
}

function bodyMode(page) {
  return page.evaluate(() => document.body.getAttribute("data-mode"));
}

function selection(page) {
  return page.evaluate(() => window.SimosApp.getState().selection);
}

function unitStatusText(page) {
  return page.evaluate(() => {
    const n = document.getElementById("unit-edit-status");
    return n ? n.textContent : null;
  });
}

function timelineNodeCount(page, branch) {
  return page.evaluate(
    (b) => document.querySelectorAll('.timeline-line[data-branch="' + b + '"] .tl-node').length,
    branch
  );
}

async function waitMapReady(page) {
  await page.waitForFunction(
    () => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(),
    null,
    { timeout: 40000 }
  );
}

async function openWorkbench(page) {
  await page.goto(BASE + "/", { waitUntil: "domcontentloaded" });
  await waitMapReady(page);
  await page.waitForFunction(
    () => document.getElementById("map-status").textContent.indexOf("已载入") >= 0,
    null,
    { timeout: 30000 }
  );
  await sleep(250);
}

/** canvas 内 CSS 坐标处点一下（button 缺省左键）。★ 点击前 scrollIntoViewIfNeeded（page.fill 滚动坑同族）。 */
async function clickCanvasPoint(page, point, button) {
  const canvas = page.locator("#canvas");
  await canvas.scrollIntoViewIfNeeded();
  await sleep(80);
  const box = await canvas.boundingBox();
  await page.mouse.click(box.x + point.x, box.y + point.y, button ? { button } : undefined);
}

/** 在 canvas 上按 (q,r) 点一下。 */
async function clickHex(page, q, r, button) {
  const canvas = page.locator("#canvas");
  await canvas.scrollIntoViewIfNeeded();
  await sleep(80);
  const pt = await page.evaluate(([qq, rr]) => window.SimosMap.screenPointOf(qq, rr), [q, r]);
  const box = await canvas.boundingBox();
  await page.mouse.click(box.x + pt.x, box.y + pt.y, button ? { button } : undefined);
}

/** 找一个"图外/无格"的 canvas 内 CSS 点（角点优先），并返回 pick 供自证 inMap=false。 */
async function findBlankPoint(page) {
  return page.evaluate(() => {
    const canvas = document.getElementById("canvas");
    const rect = canvas.getBoundingClientRect();
    const candidates = [
      { x: 4, y: 4 },
      { x: rect.width - 4, y: 4 },
      { x: 4, y: rect.height - 4 },
      { x: rect.width - 4, y: rect.height - 4 },
    ];
    for (const c of candidates) {
      const pick = window.SimosMap.hexAtScreen(c);
      if (pick && pick.inMap === false) {
        return { point: c, pick: pick };
      }
    }
    return null;
  });
}

async function clickMode(page, mode) {
  await page.click('.mode-bar button[data-mode="' + mode + '"]');
  await waitFor(() => bodyMode(page).then((m) => m === mode), 5000);
}

/** 采样 canvas 上一格中心附近 ±half 个 CSS 像素的扫描行（device px），用于像素对照。 */
async function sampleScan(page, q, r, half) {
  return page.evaluate(
    ([qq, rr, h]) => {
      const canvas = document.getElementById("canvas");
      const rect = canvas.getBoundingClientRect();
      const dpr = canvas.width / rect.width;
      const p = window.SimosMap.screenPointOf(qq, rr);
      const ctx = canvas.getContext("2d");
      const rows = [];
      for (let dx = -h; dx <= h; dx++) {
        const px = Math.round((p.x + dx) * dpr);
        const py = Math.round(p.y * dpr);
        const d = ctx.getImageData(px, py, 1, 1).data;
        rows.push([dx, d[0], d[1], d[2], d[3]]);
      }
      return { dpr, point: p, rows };
    },
    [q, r, half]
  );
}

function maxChannelDiff(a, b) {
  return Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2]));
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const executablePath =
    process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
  const browser = await chromium.launch({ headless: true, executablePath });
  const page = await browser.newPage({ viewport: { width: 1600, height: 1100 } });

  const pageErrors = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));

  const requests = [];
  page.on("request", (req) => {
    requests.push({ method: req.method(), url: req.url() });
  });
  const pathResponses = [];
  page.on("response", async (resp) => {
    if (resp.url().indexOf("/api/map/path") >= 0) {
      let body = null;
      try {
        body = await resp.json();
      } catch (e) {
        body = null;
      }
      pathResponses.push({ status: resp.status(), body });
    }
  });

  const nonGet = () =>
    requests
      .filter((r) => r.method !== "GET")
      .map((r) => {
        try {
          return new URL(r.url).pathname;
        } catch (e) {
          return r.url;
        }
      });

  await openWorkbench(page);

  // 底色参考像素：还没画路线时，取 (1,2) 中心的沙漠底色（demo 三格全是 desert）。
  const terrainSample = await sampleScan(page, 1, 2, 0);
  const terrainPx = terrainSample.rows[0].slice(1, 4);
  const overview = (await api("/api/map/overview")).body;
  const desertColor = (overview.terrainTypes || []).filter((t) => t.key === "desert")[0];
  values.pixel = { terrainPx, desertColor: desertColor && desertColor.color };
  console.log("TERRAIN_PX=" + JSON.stringify(terrainPx) + " desertColor=" + JSON.stringify(desertColor && desertColor.color));

  // ── 准备：切单位模式、选中 u-1、右键下一条 3 点路线（为 a 步准备"路线的存在"）──
  await clickMode(page, "unit");
  check("setup-mode-unit", (await bodyMode(page)) === "unit", String(await bodyMode(page)));
  await clickHex(page, 1, 1);
  const sel0 = await waitFor(() => selection(page).then((s) => (s && s.kind === "unit" ? s : null)), 5000);
  check("setup-unit-selected", !!sel0 && sel0.id === "u-1", JSON.stringify(sel0));

  const rev0 = await stateRevision(page);
  await clickHex(page, 1, 3, "right");
  const rev1 = await waitFor(() => stateRevision(page).then((r) => (r > rev0 ? r : null)), 8000);
  const unitSetup = (await api("/api/unit/u-1")).body;
  const routeSetup = unitSetup.movement && unitSetup.movement.route ? unitSetup.movement.route.path : [];
  check("setup-route-planned", rev1 === rev0 + 1 && routeSetup.length === 3, JSON.stringify({ rev0, rev1, routeSetup }));
  values.setup = { rev0, rev1, route: routeSetup };

  // ── a. 右键空白（图外/无格）⇒ 取消选中；非 GET 不增；路线仍在（movement 未清）──
  const blank = await findBlankPoint(page);
  check("a-blank-is-off-map", !!blank && blank.pick.inMap === false, JSON.stringify(blank));
  if (!blank) {
    throw new Error("找不到图外点，a 步无法进行");
  }
  const selBeforeA = await selection(page);
  const nonGetBeforeA = nonGet().length;
  await clickCanvasPoint(page, blank.point, "right");
  await sleep(500);
  const selAfterA = await selection(page);
  const nonGetAfterA = nonGet().length;
  const unitA = (await api("/api/unit/u-1")).body;
  const movementA = unitA.movement;
  check("a-selection-cleared", selBeforeA !== null && selAfterA === null, JSON.stringify({ selBeforeA, selAfterA }));
  check("a-no-write", nonGetAfterA === nonGetBeforeA, JSON.stringify({ nonGetBeforeA, nonGetAfterA }));
  check("a-movement-kept", !!movementA && movementA.route && movementA.route.path.length === 3, JSON.stringify(movementA));
  const dbgA = await mapDebug(page);
  check("a-polyline-kept", dbgA.routeCount === 1 && dbgA.routes[0].totalPoints === 3, JSON.stringify(dbgA.routes));
  values.a = {
    blank,
    selBeforeA,
    selAfterA,
    nonGetBeforeA,
    nonGetAfterA,
    movement: movementA,
    routes: dbgA.routes,
  };

  // ── b. 左键点"已选中单位"⇒ 发 unit.CancelRoute：movement 变 null、head +1、折线消失、左栏刷新 ──
  // 先重新选中（a 已清选中）：第一次左键 = 选中，不发写。
  const nonGetBeforeBSelect = nonGet().length;
  await clickHex(page, 1, 1);
  const selB0 = await waitFor(() => selection(page).then((s) => (s && s.kind === "unit" ? s : null)), 5000);
  const nonGetAfterBSelect = nonGet().length;
  check("b-first-click-selects", !!selB0 && selB0.id === "u-1", JSON.stringify(selB0));
  check("b-first-click-no-write", nonGetAfterBSelect === nonGetBeforeBSelect, JSON.stringify({ nonGetBeforeBSelect, nonGetAfterBSelect }));

  const revBeforeCancel = await stateRevision(page);
  const nodesBeforeCancel = await timelineNodeCount(page, "main");
  await clickHex(page, 1, 1);
  const revAfterCancel = await waitFor(() => stateRevision(page).then((r) => (r > revBeforeCancel ? r : null)), 8000);
  const unitB = (await api("/api/unit/u-1")).body;
  check("b-movement-cleared", unitB.movement === null, JSON.stringify(unitB.movement));
  check("b-head-advanced", revAfterCancel === revBeforeCancel + 1, JSON.stringify({ revBeforeCancel, revAfterCancel }));
  const nodesAfterCancel = await waitFor(
    () => timelineNodeCount(page, "main").then((n) => (n > nodesBeforeCancel ? n : null)),
    8000
  );
  check("b-node-plus-1", nodesAfterCancel === nodesBeforeCancel + 1, JSON.stringify({ nodesBeforeCancel, nodesAfterCancel }));
  const dbgB = await waitFor(() => mapDebug(page).then((d) => (d.routeCount === 0 ? d : null)), 6000);
  check("b-polyline-gone", !!dbgB && dbgB.routeCount === 0, JSON.stringify(dbgB && dbgB.routes));
  const selB = await selection(page);
  check("b-still-selected", !!selB && selB.kind === "unit" && selB.id === "u-1", JSON.stringify(selB));
  const statusB = await waitFor(() => unitStatusText(page).then((t) => (t && t.indexOf("已取消") >= 0 ? t : null)), 5000);
  check("b-status-refreshed", !!statusB, JSON.stringify(statusB));
  values.b = {
    selB0,
    revBeforeCancel,
    revAfterCancel,
    nodesBeforeCancel,
    nodesAfterCancel,
    movement: unitB.movement,
    routeCountAfter: dbgB && dbgB.routeCount,
    status: statusB,
  };
  await page.screenshot({ path: OUT + "/screenshot-leftclick-cancel.png", fullPage: true });

  // ── c. 左键点"未选中"的单位 ⇒ 只选中、不发写（先左键点邻格把选中切走）──
  await clickHex(page, 1, 2);
  const selHex = await waitFor(() => selection(page).then((s) => (s && s.kind === "hex" ? s : null)), 5000);
  check("c-precondition-hex-selected", !!selHex && selHex.q === 1 && selHex.r === 2, JSON.stringify(selHex));
  const nonGetBeforeC = nonGet().length;
  await clickHex(page, 1, 1);
  const selC = await waitFor(() => selection(page).then((s) => (s && s.kind === "unit" ? s : null)), 5000);
  await sleep(500);
  const nonGetAfterC = nonGet().length;
  check("c-selection-unit", !!selC && selC.id === "u-1", JSON.stringify(selC));
  check("c-no-write", nonGetAfterC === nonGetBeforeC, JSON.stringify({ nonGetBeforeC, nonGetAfterC }));
  const unitC = (await api("/api/unit/u-1")).body;
  check("c-movement-still-null", unitC.movement === null, JSON.stringify(unitC.movement));
  values.c = { selHex, selC, nonGetBeforeC, nonGetAfterC, movement: unitC.movement };

  // ── d. 右键点格仍正常下路线（回归）──
  const revBeforeD = await stateRevision(page);
  const nodesBeforeD = await timelineNodeCount(page, "main");
  const beforeD = pathResponses.length;
  await clickHex(page, 1, 3, "right");
  const respD = await waitFor(() => (pathResponses.length > beforeD ? pathResponses[pathResponses.length - 1] : null), 8000);
  const pathD = respD && respD.body ? respD.body.path : [];
  check("d-path-3-points", pathD.length === 3, pathText(pathD));
  const revD = await waitFor(() => stateRevision(page).then((r) => (r > revBeforeD ? r : null)), 8000);
  check("d-head-advanced", revD === revBeforeD + 1, JSON.stringify({ revBeforeD, revD }));
  const unitD = (await api("/api/unit/u-1")).body;
  const routeD = unitD.movement && unitD.movement.route ? unitD.movement.route.path : [];
  check("d-route-3-points", routeD.length === 3, pathText(routeD));
  const nodesD = await waitFor(
    () => timelineNodeCount(page, "main").then((n) => (n > nodesBeforeD ? n : null)),
    8000
  );
  check("d-node-plus-1", nodesD === nodesBeforeD + 1, JSON.stringify({ nodesBeforeD, nodesD }));
  const dbgD = await waitFor(() => mapDebug(page).then((d) => {
    const r = d.routes.filter((x) => x.id === "u-1")[0];
    return r && r.totalPoints === 3 ? r : null;
  }), 8000);
  check("d-polyline-3-points", !!dbgD && dbgD.totalPoints === 3, JSON.stringify(dbgD));
  values.d = { path: pathD, routePath: routeD, route: dbgD, revBeforeD, revD, nodes: { before: nodesBeforeD, after: nodesD } };

  await sleep(300);
  await page.screenshot({ path: OUT + "/screenshot-route-contrast.png", fullPage: true });

  // ── g. 像素对照：路线像素 vs 底色像素（对比度修复的数值证明）──
  const scan = await sampleScan(page, 1, 2, 8);
  let best = null;
  scan.rows.forEach((row) => {
    const rgb = row.slice(1, 4);
    const diff = maxChannelDiff(rgb, terrainPx);
    if (!best || diff > best.diff) {
      best = { dx: row[0], rgb, diff };
    }
  });
  const brightest = scan.rows.reduce((acc, row) => {
    const lum = row[1] + row[2] + row[3];
    return !acc || lum > acc.lum ? { dx: row[0], rgb: row.slice(1, 4), lum } : acc;
  }, null);
  const darkest = scan.rows.reduce((acc, row) => {
    const lum = row[1] + row[2] + row[3];
    return !acc || lum < acc.lum ? { dx: row[0], rgb: row.slice(1, 4), lum } : acc;
  }, null);
  check("g-pixel-contrast", !!best && best.diff > 80, JSON.stringify({ terrainPx, best, brightest, darkest }));
  check(
    "g-color-changed",
    !!dbgD && dbgD.baseColor !== "rgba(255, 214, 130, 0.35)",
    JSON.stringify(dbgD && { baseColor: dbgD.baseColor, outlineColor: dbgD.outlineColor })
  );
  values.pixel = Object.assign(values.pixel, {
    scan: scan.rows,
    best,
    brightest,
    darkest,
    baseColor: dbgD && dbgD.baseColor,
    outlineColor: dbgD && dbgD.outlineColor,
    remainingColor: dbgD && dbgD.remainingColor,
  });
  writeJson("pixel-sample.json", values.pixel);

  // ── e. R8 allowlist：全过程非 GET 清单 ⊆ {/api/command,/api/advance,/api/fork} 且打印 ──
  const list = nonGet();
  const allow = ["/api/command", "/api/advance", "/api/fork"];
  const violations = list.filter((p) => allow.indexOf(p) < 0);
  console.log("NON_GET_LIST=" + JSON.stringify(list));
  check("e-nonget-allowlist", violations.length === 0, JSON.stringify({ list, violations }));
  check("e-command-present", list.indexOf("/api/command") >= 0, JSON.stringify(list));
  values.e = { list, violations };
  writeJson("e-nonget-list.json", list);
  writeJson("a-rightclick-blank.json", values.a);
  writeJson("b-leftclick-cancel.json", values.b);
  writeJson("c-leftclick-select.json", values.c);
  writeJson("d-rightclick-route.json", values.d);

  // ── f. 零 pageerror ──
  check("f-no-page-errors", pageErrors.length === 0, JSON.stringify(pageErrors));

  writeJson("e2e-values.json", values);
  await browser.close();

  if (failures.length) {
    console.log("E2E RESULT: FAIL " + failures.join(","));
    process.exit(1);
  }
  console.log("E2E RESULT: PASS");
  process.exit(0);
})().catch((e) => {
  console.log("E2E RESULT: FAIL fatal " + String((e && e.stack) || e));
  process.exit(1);
});
