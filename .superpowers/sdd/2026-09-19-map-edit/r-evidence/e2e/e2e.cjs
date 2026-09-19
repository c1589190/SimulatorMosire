// M8-R e2e —— 区域编辑器重做（真档副本、真 ShellMain、真 pointer 事件）。
// 覆盖 spec §四 判据：a 右键套索创建（flood fill 独立复核）；b 边界小点（选中才有 / 数量）；
//   c 拖小点增删格（各一例，逐值）；d 合并=并集；e 剔除=差集；f 勾选框已删 + 左键画格；
//   g 右键按模式分派（区域编辑=套索、单位移动编辑=PlanRoute）；h 不退化（重叠/删除确认/焦点淡色/M9/0 pageerror）。
// 用法: NODE_PATH=<playwright> node e2e.cjs <base-url> <out-dir>
"use strict";

const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const OUT = process.argv[3] || ".";
const VW = 1280;
const VH = 800;
const DIRS = [
  [1, 0],
  [1, -1],
  [0, -1],
  [-1, 0],
  [-1, 1],
  [0, 1],
];

const failures = [];
const values = {};

function check(name, ok, detail) {
  console.log("STEP " + name + ": " + (ok ? "PASS" : "FAIL") + (detail ? " " + detail : ""));
  if (!ok) {
    failures.push(name);
  }
}

function writeJson(name, value) {
  fs.writeFileSync(OUT + "/" + name, JSON.stringify(value, null, 2));
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function parse(text) {
  try {
    return JSON.parse(text);
  } catch (e) {
    return null;
  }
}

async function api(path) {
  const response = await fetch(BASE + path);
  const text = await response.text();
  return { status: response.status, text: text, body: parse(text) };
}

function hk(h) {
  return h.q + "," + h.r;
}

function setOf(list) {
  const set = {};
  (list || []).forEach((h) => {
    set[hk(h)] = { q: h.q, r: h.r };
  });
  return set;
}

function listOf(set) {
  return Object.keys(set).map((k) => set[k]);
}

function sameSet(a, b) {
  const A = setOf(a);
  const B = setOf(b);
  const ka = Object.keys(A);
  const kb = Object.keys(B);
  if (ka.length !== kb.length) {
    return false;
  }
  return ka.every((k) => Object.prototype.hasOwnProperty.call(B, k));
}

function boundaryOf(cells) {
  const set = setOf(cells);
  return (cells || []).filter((c) =>
    DIRS.some((d) => !Object.prototype.hasOwnProperty.call(set, c.q + d[0] + "," + (c.r + d[1])))
  );
}

function hexRing(center, radius) {
  let q = center.q + radius * DIRS[4][0];
  let r = center.r + radius * DIRS[4][1];
  const out = [];
  for (let d = 0; d < 6; d++) {
    for (let s = 0; s < radius; s++) {
      out.push({ q: q, r: r });
      q += DIRS[d][0];
      r += DIRS[d][1];
    }
  }
  return out;
}

async function centerView(page, q, r, scale) {
  await page.evaluate(
    ([hex, s]) => {
      const M = window.SimosMap;
      const w = M.hexToPixel(hex.q, hex.r, M.BASE_CELL);
      M.setView({ scale: s, tx: window.innerWidth / 2 - w.x * s, ty: window.innerHeight / 2 - w.y * s });
    },
    [{ q: q, r: r }, scale || 1]
  );
  await sleep(180);
}

async function screenPoints(page, points) {
  return page.evaluate((list) => list.map((h) => window.SimosMap.screenPointOf(h.q, h.r)), points);
}

// 左键拖动（平移）：从 (from→to) 屏幕点拖 30 步。返回是否发生位移由调用方读 currentView 判断。
async function leftDrag(page, box, from, to) {
  await page.mouse.move(box.x + from.x, box.y + from.y);
  await page.mouse.down();
  await page.mouse.move(box.x + to.x, box.y + to.y, { steps: 30 });
  await page.mouse.up();
  await sleep(150);
}

// ★ 独立 flood（e2e 自己实现，不复用 map.js 的 finishLasso）——输入套索墙，输出 内部∪墙。
async function independentFlood(page, wallCells) {
  return page.evaluate((cells) => {
    const KEY = (q, r) => q + "," + r;
    const D = [
      [1, 0],
      [1, -1],
      [0, -1],
      [-1, 0],
      [-1, 1],
      [0, 1],
    ];
    const wall = {};
    cells.forEach((c) => {
      wall[KEY(c.q, c.r)] = true;
    });
    const exists = (q, r) => window.SimosMap.hexExists(q, r);
    let sq = 0;
    let sr = 0;
    cells.forEach((c) => {
      sq += c.q;
      sr += c.r;
    });
    const cq = Math.round(sq / cells.length);
    const cr = Math.round(sr / cells.length);
    function seedPick() {
      if (!wall[KEY(cq, cr)] && exists(cq, cr)) return { q: cq, r: cr };
      for (let rad = 1; rad < 200; rad++) {
        let qq = cq + rad * D[4][0];
        let rr = cr + rad * D[4][1];
        for (let d = 0; d < 6; d++) {
          for (let s = 0; s < rad; s++) {
            if (!wall[KEY(qq, rr)] && exists(qq, rr)) return { q: qq, r: rr };
            qq += D[d][0];
            rr += D[d][1];
          }
        }
      }
      return null;
    }
    const seed = seedPick();
    if (!seed) return [];
    const interior = {};
    const visited = {};
    const queue = [seed];
    while (queue.length) {
      const cur = queue.pop();
      const ck = KEY(cur.q, cur.r);
      if (visited[ck]) continue;
      visited[ck] = true;
      if (wall[ck]) continue;
      interior[ck] = cur;
      for (const d of D) {
        const nq = cur.q + d[0];
        const nr = cur.r + d[1];
        const nk = KEY(nq, nr);
        if (!visited[nk] && !wall[nk] && exists(nq, nr)) queue.push({ q: nq, r: nr });
      }
    }
    const out = {};
    Object.keys(interior).forEach((k) => {
      out[k] = interior[k];
    });
    cells.forEach((c) => {
      out[KEY(c.q, c.r)] = { q: c.q, r: c.r };
    });
    return Object.keys(out).map((k) => out[k]);
  }, wallCells);
}

function regionHexesFromApi(id) {
  return api("/api/map/region/" + encodeURIComponent(id)).then((r) => (r.body && r.body.hexes) || []);
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });

  // ── preflight：真档 ──
  const overview0 = (await api("/api/map/overview")).body || {};
  const regionIds0 = (overview0.regions || []).map((r) => r.id);
  values.preflight = {
    hexCount: overview0.hexCount,
    regions: regionIds0,
    blockCount: (overview0.blocks || []).length,
  };
  check(
    "pre-real-archive",
    overview0.hexCount === 19441 &&
      regionIds0.indexOf("test_nation") >= 0 &&
      regionIds0.indexOf("test_annex_target") >= 0,
    JSON.stringify(values.preflight)
  );

  const executablePath =
    process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
  const browser = await chromium.launch({ headless: true, executablePath });
  const page = await browser.newPage({ viewport: { width: VW, height: VH } });
  const pageErrors = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));
  const nonGet = [];
  const allRequests = [];
  page.on("request", (req) => {
    const parsed = new URL(req.url());
    allRequests.push({ method: req.method(), path: parsed.pathname });
    if (req.method() !== "GET") {
      nonGet.push({ method: req.method(), path: parsed.pathname, post: req.postData() });
    }
  });
  const phaseStarts = {};
  function markPhase(name) {
    phaseStarts[name] = nonGet.length;
  }

  // ★ §八 判据 11 的外部探针：包住 CanvasRenderingContext2D.stroke，统计每次 render 的
  //   "最大 lineTo 数 / 单次 stroke"（逐格 stroke ⇒ 6×N）以及 **Path2D 描边次数**（旧块边界层）。
  await page.addInitScript(() => {
    const P = CanvasRenderingContext2D.prototype;
    const origBegin = P.beginPath;
    const origLine = P.lineTo;
    const origStroke = P.stroke;
    let lineTos = 0;
    let maxLine = 0;
    let strokes = 0;
    let path2dStrokes = 0;
    P.beginPath = function () {
      lineTos = 0;
      return origBegin.apply(this, arguments);
    };
    P.lineTo = function () {
      lineTos += 1;
      return origLine.apply(this, arguments);
    };
    P.stroke = function (pathArg) {
      strokes += 1;
      if (lineTos > maxLine) {
        maxLine = lineTos;
      }
      if (typeof Path2D !== "undefined" && pathArg instanceof Path2D) {
        path2dStrokes += 1;
      }
      return origStroke.apply(this, arguments);
    };
    window.__strokeStats = () => ({ strokes: strokes, maxLineToPerStroke: maxLine, path2dStrokes: path2dStrokes });
    window.__strokeReset = () => {
      strokes = 0;
      maxLine = 0;
      path2dStrokes = 0;
      lineTos = 0;
    };
  });

  await page.goto(BASE + "/", { waitUntil: "domcontentloaded" });
  await page.waitForFunction(() => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(), null, {
    timeout: 60000,
  });
  await page.waitForFunction(() => document.getElementById("map-status").textContent.indexOf("已载入") >= 0, null, {
    timeout: 60000,
  });
  await sleep(600);

  const m9Baseline = await page.evaluate(() => {
    const d = window.SimosMap.debug();
    return { blockCount: d.blockCount, unmergedCount: d.unmergedCount };
  });
  values.m9Baseline = m9Baseline;
  check("pre-m9-blocks", m9Baseline.blockCount === 44 && m9Baseline.unmergedCount === 0, JSON.stringify(m9Baseline));

  // ── a 进入区域编辑 + f 勾选框已删 ──
  await page.click('#mode-bar button[data-mode="region-edit"]');
  await sleep(300);
  const domAudit = await page.evaluate(() => {
    const section = document.querySelector("section.region-editor");
    return {
      mode: document.body.getAttribute("data-mode"),
      drawTogglePresent: !!document.getElementById("region-edit-draw"),
      checkboxesInSection: section ? section.querySelectorAll('input[type="checkbox"]').length : -1,
      whitelist: {
        create: window.SimosModes.isWriteAllowed("region-edit", "map.CreateRegion"),
        update: window.SimosModes.isWriteAllowed("region-edit", "map.UpdateRegion"),
        del: window.SimosModes.isWriteAllowed("region-edit", "map.DeleteRegion"),
        terrain: window.SimosModes.isWriteAllowed("region-edit", "map.SetTerrain"),
      },
    };
  });
  values.domAudit = domAudit;
  check(
    "f1-no-draw-toggle-no-checkbox",
    domAudit.mode === "region-edit" &&
      domAudit.drawTogglePresent === false &&
      domAudit.checkboxesInSection === 0,
    JSON.stringify(domAudit)
  );
  check(
    "f2-region-edit-whitelist",
    domAudit.whitelist.create && domAudit.whitelist.update && domAudit.whitelist.del && !domAudit.whitelist.terrain,
    JSON.stringify(domAudit.whitelist)
  );

  // ── a ★ 右键套索创建 ──
  const LASSO_R = 2;
  const lassoCenter = await page.evaluate((radius) => {
    const D = [
      [1, 0],
      [1, -1],
      [0, -1],
      [-1, 0],
      [-1, 1],
      [0, 1],
    ];
    const ex = (q, r) => window.SimosMap.hexExists(q, r);
    function ring(c, R) {
      let q = c.q + R * D[4][0];
      let r = c.r + R * D[4][1];
      const o = [];
      for (let d = 0; d < 6; d++) {
        for (let s = 0; s < R; s++) {
          o.push({ q: q, r: r });
          q += D[d][0];
          r += D[d][1];
        }
      }
      return o;
    }
    const cands = [
      [-16, 0],
      [-18, 0],
      [-20, 0],
      [-14, 0],
      [0, 0],
    ];
    for (const c of cands) {
      if (ring({ q: c[0], r: c[1] }, radius).every((p) => ex(p.q, p.r))) return { q: c[0], r: c[1] };
    }
    return null;
  }, LASSO_R);
  check("a0-lasso-center-found", !!lassoCenter, JSON.stringify(lassoCenter));

  await page.click("#region-edit-new");
  await sleep(150);
  await page.fill("#region-create-id", "m8r_lasso");
  await page.fill("#region-create-name", "M8R Lasso");
  await centerView(page, lassoCenter.q, lassoCenter.r, 1);
  let canvasBox = await page.locator("#canvas").boundingBox();

  const ring = hexRing(lassoCenter, LASSO_R);
  const ringPts = await screenPoints(page, ring);
  const headBeforeCreate = await page.evaluate(() => window.SimosApp.getState().revision);
  markPhase("A-lasso-create");
  await page.mouse.move(canvasBox.x + ringPts[0].x, canvasBox.y + ringPts[0].y);
  await page.mouse.down({ button: "right" });
  for (let i = 1; i < ringPts.length; i++) {
    await page.mouse.move(canvasBox.x + ringPts[i].x, canvasBox.y + ringPts[i].y, { steps: 1 });
    await sleep(15);
  }
  const wallCells = await page.evaluate(() => window.SimosMap.lassoHexes());
  const lassoActiveMid = await page.evaluate(() => window.SimosMap.regionEditDebug().lassoActive);
  await page.screenshot({ path: OUT + "/screenshot-lasso-inprogress.png" });
  await page.mouse.up({ button: "right" });
  await page
    .waitForFunction((h) => window.SimosApp.getState().revision !== h, headBeforeCreate, { timeout: 20000 })
    .catch(() => null);
  await sleep(900);

  const expectedLasso = await independentFlood(page, wallCells);
  const createPosts = nonGet.slice(phaseStarts["A-lasso-create"]).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "map.CreateRegion";
  });
  const createPayload = createPosts.length === 1 ? parse((parse(createPosts[0].post) || {}).payloadJson) : null;
  const headAfterCreate = await page.evaluate(() => window.SimosApp.getState().revision);
  const apiLasso = await regionHexesFromApi("m8r_lasso");
  values.lasso = {
    center: lassoCenter,
    ringCount: ring.length,
    wallCount: wallCells.length,
    lassoActiveMid: lassoActiveMid,
    expectedCount: expectedLasso.length,
    payloadCount: createPayload && (createPayload.hexes || []).length,
    payloadMatchesIndependentFlood: createPayload ? sameSet(createPayload.hexes, expectedLasso) : false,
    apiHexCount: apiLasso.length,
    apiMatchesPayload: createPayload ? sameSet(apiLasso, createPayload.hexes) : false,
    headBefore: headBeforeCreate,
    headAfter: headAfterCreate,
    posts: createPosts.length,
    regionId: createPayload && createPayload.regionId,
  };
  check(
    "a1-lasso-one-command-head-plus-one",
    createPosts.length === 1 &&
      createPayload &&
      createPayload.regionId === "m8r_lasso" &&
      headAfterCreate === headBeforeCreate + 1,
    JSON.stringify(values.lasso)
  );
  check(
    "a2-lasso-hexes-equal-independent-flood",
    createPayload && createPayload.hexes && sameSet(createPayload.hexes, expectedLasso),
    "payload=" + values.lasso.payloadCount + " independent=" + expectedLasso.length
  );
  check("a3-lasso-api-matches-payload", sameSet(apiLasso, createPayload ? createPayload.hexes : []), "api=" + apiLasso.length);

  // ── ★ 重叠允许（方向性护栏：不得有"禁止重叠"）──
  const overlapHex = await api("/api/map/hex?q=" + lassoCenter.q + "&r=" + lassoCenter.r);
  const overlapRegions = (overlapHex.body && overlapHex.body.regions) || [];
  values.overlap = { q: lassoCenter.q, r: lassoCenter.r, regions: overlapRegions };
  check(
    "h1-overlap-allowed",
    overlapRegions.indexOf("m8r_lasso") >= 0 && overlapRegions.indexOf("test_nation") >= 0 && overlapRegions.length >= 2,
    JSON.stringify(values.overlap)
  );

  // 套索创建失败（例如"禁止重叠"变异）⇒ 后续依赖 m8r_lasso 的步骤无法进行：如实记为 FAIL 并收尾，
  // 不让 TypeError 把断言失败伪装成"装置崩溃"（rc=2）。
  if (apiLasso.length === 0) {
    [
      "b1-dots-match-boundary",
      "c1-dot-drag-add-one-update",
      "c3-dot-drag-remove-one-update",
      "d1-merge-equals-union",
      "e1-exclude-equals-difference",
      "g1-region-edit-right-is-lasso",
      "h2-delete-confirm-two-step",
      "h3-focus-fade-colors",
      "h4-m9-blocks-and-pick",
    ].forEach((name) => check(name, false, "skipped: m8r_lasso 未创建"));
    values.nonGetGrouped = [];
    values.pageErrors = pageErrors;
    writeJson("result.json", values);
    await browser.close();
    console.log("E2E RESULT: " + failures.length + " FAIL " + failures.join(","));
    process.exit(1);
  }

  // ── b 边界小点（选中才有；数量 == 边界 hex 数）──
  await page
    .waitForFunction(() => window.SimosMap.regionEditDebug().focus === "m8r_lasso", null, { timeout: 8000 })
    .catch(() => null);
  await sleep(400);
  const dots = await page.evaluate(() => window.SimosMap.regionEditDebug());
  const expectedBoundary = boundaryOf(apiLasso);
  values.dots = {
    focus: dots.focus,
    focusHexCount: dots.focusHexCount,
    boundaryDotCount: dots.boundaryDotCount,
    expectedBoundary: expectedBoundary.length,
    apiHexCount: apiLasso.length,
  };
  check(
    "b1-dots-match-boundary",
    dots.focus === "m8r_lasso" &&
      dots.focusHexCount === apiLasso.length &&
      dots.boundaryDotCount === expectedBoundary.length,
    JSON.stringify(values.dots)
  );
  await centerView(page, lassoCenter.q, lassoCenter.r, 2.4);
  await page.screenshot({ path: OUT + "/screenshot-region-dots.png" });

  // b2 未选中区域 ⇒ 无点
  await page.evaluate(() => window.SimosApp.setRegionFocus(null));
  await sleep(300);
  const dotsCleared = await page.evaluate(() => window.SimosMap.regionEditDebug());
  check(
    "b2-no-dots-when-unselected",
    dotsCleared.focus === null && dotsCleared.boundaryDotCount === 0 && dotsCleared.focusHexCount === 0,
    JSON.stringify({ focus: dotsCleared.focus, dots: dotsCleared.boundaryDotCount })
  );
  // 重新选中
  await page.waitForSelector('[data-region-id="m8r_lasso"]', { timeout: 8000 });
  await page.click('[data-region-id="m8r_lasso"]');
  await page
    .waitForFunction(
      () => window.SimosMap.regionEditDebug().focus === "m8r_lasso" && window.SimosMap.regionEditDebug().boundaryDotCount > 0,
      null,
      { timeout: 8000 }
    )
    .catch(() => null);
  await sleep(300);

  // ── c 拖小点：增格（到区域外）──
  const baseC = await regionHexesFromApi("m8r_lasso");
  const addTarget = await page.evaluate((cells) => {
    const D = [
      [1, 0],
      [1, -1],
      [0, -1],
      [-1, 0],
      [-1, 1],
      [0, 1],
    ];
    const set = {};
    cells.forEach((c) => {
      set[c.q + "," + c.r] = true;
    });
    const ex = (q, r) => window.SimosMap.hexExists(q, r);
    for (const c of cells) {
      let isBnd = false;
      for (const d of D) if (!set[c.q + d[0] + "," + (c.r + d[1])]) isBnd = true;
      if (!isBnd) continue;
      for (const d of D) {
        const q = c.q + d[0];
        const r = c.r + d[1];
        if (!set[q + "," + r] && ex(q, r)) return { from: c, to: { q: q, r: r } };
      }
    }
    return null;
  }, baseC);
  check("c0-add-target-found", !!addTarget, JSON.stringify(addTarget));

  await centerView(page, addTarget.from.q, addTarget.from.r, 2.4);
  canvasBox = await page.locator("#canvas").boundingBox();
  const addPts = await screenPoints(page, [addTarget.from, addTarget.to]);
  const headBeforeAdd = await page.evaluate(() => window.SimosApp.getState().revision);
  markPhase("C-dot-add");
  await page.mouse.move(canvasBox.x + addPts[0].x, canvasBox.y + addPts[0].y);
  await page.mouse.down();
  await page.mouse.move(canvasBox.x + addPts[1].x, canvasBox.y + addPts[1].y, { steps: 1 });
  await sleep(60);
  await page.mouse.up();
  await page
    .waitForFunction((h) => window.SimosApp.getState().revision !== h, headBeforeAdd, { timeout: 20000 })
    .catch(() => null);
  await sleep(800);
  const addPosts = nonGet.slice(phaseStarts["C-dot-add"]).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "map.UpdateRegion";
  });
  const addPayload = addPosts.length === 1 ? parse((parse(addPosts[0].post) || {}).payloadJson) : null;
  const expectedAdd = setOf(baseC);
  expectedAdd[hk(addTarget.to)] = addTarget.to;
  values.dotAdd = {
    from: addTarget.from,
    to: addTarget.to,
    posts: addPosts.length,
    beforeCount: baseC.length,
    payloadCount: addPayload && (addPayload.hexes || []).length,
    expectedCount: listOf(expectedAdd).length,
    matches: addPayload ? sameSet(addPayload.hexes, listOf(expectedAdd)) : false,
    headBefore: headBeforeAdd,
    headAfter: await page.evaluate(() => window.SimosApp.getState().revision),
  };
  check(
    "c1-dot-drag-add-one-update",
    addPosts.length === 1 && addPayload && addPayload.regionId === "m8r_lasso" && sameSet(addPayload.hexes, listOf(expectedAdd)),
    JSON.stringify(values.dotAdd)
  );

  // ── c 拖小点：删格（到区域内）──
  await page
    .waitForFunction(
      (n) => window.SimosMap.regionEditDebug().focusHexCount === n,
      listOf(expectedAdd).length,
      { timeout: 8000 }
    )
    .catch(() => null);
  await sleep(300);
  const baseC2 = await regionHexesFromApi("m8r_lasso");
  const rmTarget = await page.evaluate((cells) => {
    const D = [
      [1, 0],
      [1, -1],
      [0, -1],
      [-1, 0],
      [-1, 1],
      [0, 1],
    ];
    const set = {};
    cells.forEach((c) => {
      set[c.q + "," + c.r] = true;
    });
    for (const c of cells) {
      let isBnd = false;
      for (const d of D) if (!set[c.q + d[0] + "," + (c.r + d[1])]) isBnd = true;
      if (!isBnd) continue;
      for (const d of D) {
        const q = c.q + d[0];
        const r = c.r + d[1];
        if (set[q + "," + r]) return { from: c, to: { q: q, r: r } };
      }
    }
    return null;
  }, baseC2);
  check("c2-remove-target-found", !!rmTarget, JSON.stringify(rmTarget));

  await centerView(page, rmTarget.from.q, rmTarget.from.r, 2.4);
  canvasBox = await page.locator("#canvas").boundingBox();
  const rmPts = await screenPoints(page, [rmTarget.from, rmTarget.to]);
  const headBeforeRm = await page.evaluate(() => window.SimosApp.getState().revision);
  markPhase("C-dot-remove");
  await page.mouse.move(canvasBox.x + rmPts[0].x, canvasBox.y + rmPts[0].y);
  await page.mouse.down();
  await sleep(50);
  const rmProbeDown = await page.evaluate(
    (p) => ({
      pick: window.SimosMap.hexAtScreen(p),
      dot: window.SimosMap.boundaryDotAtScreen(p),
      dotDragging: window.SimosMap.regionEditDebug().dotDragging,
    }),
    rmPts[0]
  );
  await page.mouse.move(canvasBox.x + rmPts[1].x, canvasBox.y + rmPts[1].y, { steps: 1 });
  await sleep(50);
  const rmProbeMove = await page.evaluate(
    (p) => ({
      pick: window.SimosMap.hexAtScreen(p),
      dotDragging: window.SimosMap.regionEditDebug().dotDragging,
    }),
    rmPts[1]
  );
  await page.mouse.up();
  await page
    .waitForFunction((h) => window.SimosApp.getState().revision !== h, headBeforeRm, { timeout: 20000 })
    .catch(() => null);
  await sleep(800);
  const rmPosts = nonGet.slice(phaseStarts["C-dot-remove"]).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "map.UpdateRegion";
  });
  const rmPayload = rmPosts.length === 1 ? parse((parse(rmPosts[0].post) || {}).payloadJson) : null;
  const expectedRm = setOf(baseC2);
  delete expectedRm[hk(rmTarget.from)];
  values.dotRemove = {
    from: rmTarget.from,
    to: rmTarget.to,
    posts: rmPosts.length,
    beforeCount: baseC2.length,
    payloadCount: rmPayload && (rmPayload.hexes || []).length,
    expectedCount: listOf(expectedRm).length,
    matches: rmPayload ? sameSet(rmPayload.hexes, listOf(expectedRm)) : false,
    removed: hk(rmTarget.from),
    probeDown: rmProbeDown,
    probeMove: rmProbeMove,
  };
  check(
    "c3-dot-drag-remove-one-update",
    rmPosts.length === 1 && rmPayload && sameSet(rmPayload.hexes, listOf(expectedRm)),
    JSON.stringify(values.dotRemove)
  );

  // ── d 合并 = 并集 ──
  await sleep(300);
  const baseMerge = await regionHexesFromApi("m8r_lasso");
  const mergeTemp = await page.evaluate((cells) => {
    const D = [
      [1, 0],
      [1, -1],
      [0, -1],
      [-1, 0],
      [-1, 1],
      [0, 1],
    ];
    const set = {};
    cells.forEach((c) => {
      set[c.q + "," + c.r] = true;
    });
    const ex = (q, r) => window.SimosMap.hexExists(q, r);
    const out = [];
    const seen = {};
    for (const c of cells) {
      for (const d of D) {
        const q = c.q + d[0];
        const r = c.r + d[1];
        if (!set[q + "," + r] && ex(q, r) && !seen[q + "," + r]) {
          seen[q + "," + r] = true;
          out.push({ q: q, r: r });
        }
        if (out.length >= 3) return out;
      }
    }
    return out;
  }, baseMerge);
  check("d0-merge-temp-found", mergeTemp.length === 3, JSON.stringify(mergeTemp));
  await page.evaluate((cells) => window.SimosMap.regionPaintForTest(cells, "add"), mergeTemp);
  await sleep(200);
  const headBeforeMerge = await page.evaluate(() => window.SimosApp.getState().revision);
  markPhase("D-merge");
  await page.click("#region-merge");
  await page
    .waitForFunction((h) => window.SimosApp.getState().revision !== h, headBeforeMerge, { timeout: 20000 })
    .catch(() => null);
  await sleep(800);
  const mergePosts = nonGet.slice(phaseStarts["D-merge"]).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "map.UpdateRegion";
  });
  const mergePayload = mergePosts.length === 1 ? parse((parse(mergePosts[0].post) || {}).payloadJson) : null;
  const unionExpected = setOf(baseMerge);
  mergeTemp.forEach((h) => {
    unionExpected[hk(h)] = h;
  });
  values.merge = {
    temp: mergeTemp,
    posts: mergePosts.length,
    beforeCount: baseMerge.length,
    payloadCount: mergePayload && (mergePayload.hexes || []).length,
    unionCount: listOf(unionExpected).length,
    matchesUnion: mergePayload ? sameSet(mergePayload.hexes, listOf(unionExpected)) : false,
  };
  check(
    "d1-merge-equals-union",
    mergePosts.length === 1 && mergePayload && sameSet(mergePayload.hexes, listOf(unionExpected)),
    JSON.stringify(values.merge)
  );

  // ── e 剔除 = 差集 ──
  await sleep(300);
  const baseExclude = await regionHexesFromApi("m8r_lasso");
  const excludeTemp = baseExclude.slice(0, 3);
  await page.evaluate((cells) => window.SimosMap.regionPaintForTest(cells, "add"), excludeTemp);
  await sleep(200);
  const headBeforeExclude = await page.evaluate(() => window.SimosApp.getState().revision);
  markPhase("E-exclude");
  await page.click("#region-exclude");
  await page
    .waitForFunction((h) => window.SimosApp.getState().revision !== h, headBeforeExclude, { timeout: 20000 })
    .catch(() => null);
  await sleep(800);
  const excludePosts = nonGet.slice(phaseStarts["E-exclude"]).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "map.UpdateRegion";
  });
  const excludePayload = excludePosts.length === 1 ? parse((parse(excludePosts[0].post) || {}).payloadJson) : null;
  const excludeExpected = setOf(baseExclude);
  excludeTemp.forEach((h) => {
    delete excludeExpected[hk(h)];
  });
  values.exclude = {
    temp: excludeTemp,
    posts: excludePosts.length,
    beforeCount: baseExclude.length,
    payloadCount: excludePayload && (excludePayload.hexes || []).length,
    diffCount: listOf(excludeExpected).length,
    matchesDifference: excludePayload ? sameSet(excludePayload.hexes, listOf(excludeExpected)) : false,
  };
  check(
    "e1-exclude-equals-difference",
    excludePosts.length === 1 && excludePayload && sameSet(excludePayload.hexes, listOf(excludeExpected)),
    JSON.stringify(values.exclude)
  );
  await centerView(page, lassoCenter.q, lassoCenter.r, 2.4);
  await page.screenshot({ path: OUT + "/screenshot-after-merge-exclude.png" });

  // ── f3 左键仍能画格（临时选区）+ 用选区创建一条命令 ──
  const paintCells = await page.evaluate((cells) => {
    const D = [
      [1, 0],
      [1, -1],
      [0, -1],
      [-1, 0],
      [-1, 1],
      [0, 1],
    ];
    const set = {};
    cells.forEach((c) => {
      set[c.q + "," + c.r] = true;
    });
    const ex = (q, r) => window.SimosMap.hexExists(q, r);
    const out = [];
    for (const c of cells) {
      for (const d of D) {
        const q = c.q + d[0];
        const r = c.r + d[1];
        if (!set[q + "," + r] && ex(q, r)) out.push({ q: q, r: r });
        if (out.length >= 2) return out;
      }
    }
    return out;
  }, await regionHexesFromApi("m8r_lasso"));
  await centerView(page, paintCells[0].q, paintCells[0].r, 2.4);
  canvasBox = await page.locator("#canvas").boundingBox();
  const draftBefore = await page.evaluate(() => window.SimosMap.regionEditDebug().draftHexCount);
  markPhase("F-leftpaint");
  const paintPts = await screenPoints(page, paintCells);
  await page.keyboard.down("Shift");
  await page.mouse.move(canvasBox.x + paintPts[0].x, canvasBox.y + paintPts[0].y);
  await page.mouse.down({ button: "right" });
  await page.mouse.move(canvasBox.x + paintPts[1].x, canvasBox.y + paintPts[1].y, { steps: 8 });
  await page.mouse.up({ button: "right" });
  await page.keyboard.up("Shift");
  await sleep(400);
  const draftAfter = await page.evaluate(() => window.SimosMap.regionEditDebug().draftHexCount);
  const paintWrites = nonGet.slice(phaseStarts["F-leftpaint"]).filter((r) => r.path === "/api/command").length;
  values.shiftPaint = { draftBefore: draftBefore, draftAfter: draftAfter, writesDuringPaint: paintWrites };
  check(
    "f3-shift-right-paints-draft-no-write",
    draftAfter > draftBefore && paintWrites === 0,
    JSON.stringify(values.shiftPaint)
  );
  await page.fill("#region-create-id", "m8r_btn");
  await page.fill("#region-create-name", "M8R Button");
  const headBeforeBtn = await page.evaluate(() => window.SimosApp.getState().revision);
  markPhase("F-create-by-button");
  await page.click("#region-create-submit");
  await page
    .waitForFunction((h) => window.SimosApp.getState().revision !== h, headBeforeBtn, { timeout: 20000 })
    .catch(() => null);
  await sleep(700);
  const btnPosts = nonGet.slice(phaseStarts["F-create-by-button"]).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "map.CreateRegion";
  });
  values.createByButton = { posts: btnPosts.length, draft: draftAfter };
  check("f4-draft-create-one-command", btnPosts.length === 1, JSON.stringify(values.createByButton));

  // ── g 右键按模式分派 ──
  // g1 区域编辑：再次右键拖动 ⇒ 只 CreateRegion，不 PlanRoute（上面 a 已证 CreateRegion；此处取分派计数）
  const regionPhaseWrites = nonGet
    .slice(phaseStarts["A-lasso-create"])
    .map((r) => (parse(r.post) || {}).type)
    .filter(Boolean);
  values.dispatchRegionEdit = {
    types: Array.from(new Set(regionPhaseWrites)),
    hasPlanRoute: regionPhaseWrites.indexOf("unit.PlanRoute") >= 0,
  };
  check(
    "g1-region-edit-right-is-lasso",
    regionPhaseWrites.indexOf("map.CreateRegion") >= 0 && !values.dispatchRegionEdit.hasPlanRoute,
    JSON.stringify(values.dispatchRegionEdit)
  );

  // g2 单位移动编辑：右键 ⇒ unit.PlanRoute（非 GET 清单打印）
  await page.click('#mode-bar button[data-mode="unit"]');
  await sleep(250);
  const routeFrom = { q: -16, r: 0 };
  const routeTo = { q: -14, r: 0 };
  const createUnitRes = await page.evaluate(async ([from]) => {
    const r = await window.SimosApp.writeCommand("unit.CreateUnit", {
      id: "m8r-unit",
      name: "M8R Unit",
      position: { q: from.q, r: from.r },
      member: 100,
      equipment: {},
      speed: 2,
      mobilityPerMille: 1000,
    });
    return { ok: r.ok, kind: r.kind || null, message: r.message || null };
  }, [routeFrom]);
  await sleep(600);
  await centerView(page, routeFrom.q, routeFrom.r, 2.4);
  canvasBox = await page.locator("#canvas").boundingBox();
  const unitPt = await page.evaluate((h) => window.SimosMap.screenPointOf(h.q, h.r), routeFrom);
  const toPt = await page.evaluate((h) => window.SimosMap.screenPointOf(h.q, h.r), routeTo);
  markPhase("G-unit-route");
  await page.mouse.click(canvasBox.x + unitPt.x, canvasBox.y + unitPt.y);
  await sleep(200);
  await page.mouse.click(canvasBox.x + toPt.x, canvasBox.y + toPt.y, { button: "right" });
  await sleep(1000);
  const unitPhase = nonGet.slice(phaseStarts["G-unit-route"]);
  const routePosts = unitPhase.filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "unit.PlanRoute";
  });
  const unitCreatePosts = unitPhase.filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "map.CreateRegion";
  });
  const routeCount = await page.evaluate(() => window.SimosMap.debug().routeCount);
  values.dispatchUnit = {
    createUnit: createUnitRes,
    planRoutePosts: routePosts.length,
    createRegionPosts: unitCreatePosts.length,
    routeCount: routeCount,
  };
  check(
    "g2-unit-mode-right-is-planroute",
    createUnitRes.ok === true && routePosts.length === 1 && unitCreatePosts.length === 0 && routeCount >= 1,
    JSON.stringify(values.dispatchUnit)
  );

  // g3 区域编辑：**即使有选中单位**，右键也必须走套索（不得落到 unit.PlanRoute）——m2 的杀点。
  await page.click('#mode-bar button[data-mode="region-edit"]');
  await sleep(250);
  await page.evaluate(() => window.SimosApp.setSelection({ kind: "unit", id: "m8r-unit" }));
  await sleep(150);
  await centerView(page, routeFrom.q, routeFrom.r, 2.4);
  canvasBox = await page.locator("#canvas").boundingBox();
  const g3Pt = await page.evaluate((h) => window.SimosMap.screenPointOf(h.q, h.r), { q: routeFrom.q, r: routeFrom.r });
  const routeCountBeforeG3 = await page.evaluate(() => window.SimosMap.debug().routeCount);
  const reqIdxBeforeG3 = allRequests.length;
  markPhase("G3-region-edit-right-with-unit");
  await page.mouse.click(canvasBox.x + g3Pt.x, canvasBox.y + g3Pt.y, { button: "right" });
  await sleep(700);
  const g3Phase = nonGet.slice(phaseStarts["G3-region-edit-right-with-unit"]);
  const g3PlanRoute = g3Phase.filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "unit.PlanRoute";
  });
  // ★ 判别力：即使 T7 白名单会挡住 unit.PlanRoute 的 POST，`submitPathRoute` 仍会先发只读
  //   `GET /api/map/path`——用它抓"右键误落到 PlanRoute 分支"（m2 的杀点）。
  const g3MapPath = allRequests.slice(reqIdxBeforeG3).filter((r) => r.path === "/api/map/path").length;
  values.dispatchRegionEditWithUnit = {
    planRoutePosts: g3PlanRoute.length,
    mapPathGets: g3MapPath,
    routeCountBefore: routeCountBeforeG3,
    routeCountAfter: await page.evaluate(() => window.SimosMap.debug().routeCount),
  };
  check(
    "g3-region-edit-right-is-not-planroute",
    g3PlanRoute.length === 0 &&
      g3MapPath === 0 &&
      values.dispatchRegionEditWithUnit.routeCountAfter === values.dispatchRegionEditWithUnit.routeCountBefore,
    JSON.stringify(values.dispatchRegionEditWithUnit)
  );
  await page.evaluate(() => window.SimosApp.setSelection(null));

  // ── h 不退化：删除二次确认 / 焦点淡色 / M9 / 0 pageerror ──
  await page.click('#mode-bar button[data-mode="region-edit"]');
  await sleep(250);
  // ★ 前序若失败（如 m7 让 Shift+右键不再画格 ⇒ m8r_btn 没建成），这里如实记 FAIL 而不是让 waitForSelector 崩掉 e2e。
  const btnRegionVisible = await page
    .waitForSelector('[data-region-id="m8r_btn"]', { timeout: 8000 })
    .then(() => true)
    .catch(() => false);
  if (!btnRegionVisible) {
    check("h2-delete-confirm-two-step", false, "m8r_btn 未创建（前序失败），删除确认未测");
  } else {
    await page.click('[data-region-id="m8r_btn"]');
    await sleep(600);
    markPhase("H-delete");
    await page.click("#region-delete");
    await sleep(200);
    const armed = await page.evaluate(() => ({
      confirmShown: document.getElementById("region-delete-confirm").hidden === false,
      armed: window.SimosMap.regionEditDebug().deleteArmed,
    }));
    const armedWrites = nonGet.slice(phaseStarts["H-delete"]).filter((r) => {
      const body = parse(r.post) || {};
      return r.path === "/api/command" && body.type === "map.DeleteRegion";
    });
    await page.click("#region-delete-yes");
    await sleep(900);
    const delWrites = nonGet.slice(phaseStarts["H-delete"]).filter((r) => {
      const body = parse(r.post) || {};
      return r.path === "/api/command" && body.type === "map.DeleteRegion";
    });
    values.deleteConfirm = { armed: armed, armedWrites: armedWrites.length, confirmedWrites: delWrites.length };
    check(
      "h2-delete-confirm-two-step",
      armed.confirmShown === true && armed.armed === true && armedWrites.length === 0 && delWrites.length === 1,
      JSON.stringify(values.deleteConfirm)
    );
  }

  // 焦点淡色（m8r_lasso 为焦点，其它区域淡色）
  await page.waitForSelector('[data-region-id="m8r_lasso"]', { timeout: 8000 });
  await page.click('[data-region-id="m8r_lasso"]');
  await sleep(600);
  const fade = await page.evaluate(() => ({
    faded: window.SimosMap.regionEditDebug().fadedRegions,
    alphas: window.SimosMap.debug().highlightAlphas,
    focusAlpha: window.SimosMap.REGION_FOCUS_ALPHA,
    fadeAlpha: window.SimosMap.REGION_FADE_ALPHA,
  }));
  values.fade = fade;
  check(
    "h3-focus-fade-colors",
    fade.faded.length >= 1 && fade.alphas.indexOf(fade.focusAlpha) >= 0 && fade.alphas.indexOf(fade.fadeAlpha) >= 0,
    JSON.stringify(fade)
  );

  // M9 块渲染 + 点选准（避开 g2 那个单位所在的格）
  const pickHex = apiLasso.find((h) => !(h.q === routeFrom.q && h.r === routeFrom.r)) || apiLasso[0];
  await page.click('#mode-bar button[data-mode="view"]');
  await sleep(300);
  await centerView(page, pickHex.q, pickHex.r, 1);
  canvasBox = await page.locator("#canvas").boundingBox();
  const pickPt = await page.evaluate((h) => window.SimosMap.screenPointOf(h.q, h.r), pickHex);
  await page.mouse.click(canvasBox.x + pickPt.x, canvasBox.y + pickPt.y);
  await sleep(400);
  const m9 = await page.evaluate(() => ({
    debug: window.SimosMap.debug(),
    selection: window.SimosApp.getState().selection,
  }));
  values.m9 = {
    blockCount: m9.debug.blockCount,
    unmergedCount: m9.debug.unmergedCount,
    pickHex: pickHex,
    selection: m9.selection,
  };
  check(
    "h4-m9-blocks-and-pick",
    m9.debug.blockCount === 44 &&
      m9.debug.unmergedCount === 0 &&
      m9.selection &&
      m9.selection.kind === "hex" &&
      m9.selection.q === pickHex.q &&
      m9.selection.r === pickHex.r,
    JSON.stringify(values.m9)
  );

  check("h5-no-pageerror", pageErrors.length === 0, JSON.stringify(pageErrors));
  values.pageErrors = pageErrors;

  // ═══════════════ §七 统一按键模型（判据 9 / 10）═══════════════

  // ⑨ 地形编辑：左键拖动 ⇒ 零写 + 视图确实平移
  await page.click('#mode-bar button[data-mode="map-edit"]');
  await page.waitForSelector("#terrain-palette button[data-terrain]", { timeout: 20000 });
  await sleep(250);
  const palKeys2 = await page.evaluate(() => window.SimosMap.mapEditDebug().paletteKeys);
  const terrainTarget2 = palKeys2.indexOf("ocean") >= 0 ? "ocean" : palKeys2[0];
  await page.click('#terrain-palette button[data-terrain="' + terrainTarget2 + '"]');
  await sleep(150);
  await centerView(page, 8, -8, 1);
  canvasBox = await page.locator("#canvas").boundingBox();
  const viewBefore9 = await page.evaluate(() => window.SimosMap.currentView());
  markPhase("J9-terrain-left-pan");
  await leftDrag(page, canvasBox, { x: 640, y: 400 }, { x: 760, y: 480 });
  const viewAfter9 = await page.evaluate(() => window.SimosMap.currentView());
  const writes9 = nonGet.slice(phaseStarts["J9-terrain-left-pan"]).filter((r) => r.path === "/api/command").length;
  const panMoved9 = viewAfter9.tx !== viewBefore9.tx || viewAfter9.ty !== viewBefore9.ty;
  values.terrainLeftPan = { before: viewBefore9, after: viewAfter9, moved: panMoved9, writes: writes9 };
  check("k1-terrain-left-pans-zero-write", panMoved9 && writes9 === 0, JSON.stringify(values.terrainLeftPan));

  // ⑨b 地形编辑：右键拖动 ⇒ 恰 1 条 map.SetTerrain（多 hex）
  await centerView(page, 8, -8, 1);
  canvasBox = await page.locator("#canvas").boundingBox();
  const brushRun = await page.evaluate(() =>
    [
      { q: 8, r: -8 },
      { q: 9, r: -8 },
      { q: 10, r: -8 },
      { q: 11, r: -8 },
      { q: 8, r: -7 },
      { q: 8, r: -9 },
    ].filter((c) => window.SimosMap.hexExists(c.q, c.r))
  );
  const brushPts = await screenPoints(page, brushRun);
  const headBeforeBrush2 = await page.evaluate(() => window.SimosApp.getState().revision);
  markPhase("J9b-terrain-right-brush");
  await page.mouse.move(canvasBox.x + brushPts[0].x, canvasBox.y + brushPts[0].y);
  await page.mouse.down({ button: "right" });
  for (let i = 1; i < brushPts.length; i++) {
    await page.mouse.move(canvasBox.x + brushPts[i].x, canvasBox.y + brushPts[i].y, { steps: 1 });
    await sleep(20);
  }
  await page.mouse.up({ button: "right" });
  await page
    .waitForFunction((h) => window.SimosApp.getState().revision !== h, headBeforeBrush2, { timeout: 20000 })
    .catch(() => null);
  await sleep(700);
  const brushPosts2 = nonGet.slice(phaseStarts["J9b-terrain-right-brush"]).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "map.SetTerrain";
  });
  const brushPayload2 = brushPosts2.length === 1 ? parse((parse(brushPosts2[0].post) || {}).payloadJson) : null;
  values.terrainRightBrush = {
    posts: brushPosts2.length,
    hexCount: brushPayload2 && (brushPayload2.hexes || []).length,
    terrain: brushPayload2 && brushPayload2.terrain,
    headBefore: headBeforeBrush2,
    headAfter: await page.evaluate(() => window.SimosApp.getState().revision),
  };
  check(
    "k2-terrain-right-one-command",
    brushPosts2.length === 1 && brushPayload2 && (brushPayload2.hexes || []).length >= 3 && brushPayload2.terrain === terrainTarget2,
    JSON.stringify(values.terrainRightBrush)
  );

  // ⑩ 所有模式左键拖动 ⇒ 无非 GET（打印清单）
  const leftPanModes = ["view", "region", "map-edit", "region-edit", "unit"];
  const perModeLeft = [];
  for (const m of leftPanModes) {
    await page.click('#mode-bar button[data-mode="' + m + '"]');
    await sleep(200);
    await centerView(page, 0, 0, 1);
    canvasBox = await page.locator("#canvas").boundingBox();
    const before = await page.evaluate(() => window.SimosMap.currentView());
    const idx = nonGet.length;
    await leftDrag(page, canvasBox, { x: 640, y: 400 }, { x: 720, y: 460 });
    const after = await page.evaluate(() => window.SimosMap.currentView());
    const deltas = nonGet.slice(idx);
    perModeLeft.push({
      mode: m,
      txChanged: after.tx !== before.tx,
      tyChanged: after.ty !== before.ty,
      nonGet: deltas.map((r) => ({ method: r.method, path: r.path, type: (parse(r.post) || {}).type || null })),
    });
  }
  values.leftPanAllModes = perModeLeft;
  console.log("LEFT_PAN_ALL_MODES " + JSON.stringify(perModeLeft));
  check(
    "k3-left-drag-no-nonget-all-modes",
    perModeLeft.every((entry) => entry.nonGet.length === 0),
    JSON.stringify(perModeLeft.map((e) => ({ mode: e.mode, nonGet: e.nonGet.length })))
  );

  // ⑩b 区域编辑：Shift+右键逐格画 ⇒ 一条 UpdateRegion 改一格，且不落套索
  await page.click('#mode-bar button[data-mode="region-edit"]');
  await sleep(250);
  await page.waitForSelector('[data-region-id="m8r_lasso"]', { timeout: 8000 });
  await page.click('[data-region-id="m8r_lasso"]');
  await page
    .waitForFunction(
      () => window.SimosMap.regionEditDebug().focus === "m8r_lasso" && window.SimosMap.regionEditDebug().focusHexCount > 0,
      null,
      { timeout: 8000 }
    )
    .catch(() => null);
  await sleep(400);
  await page.click("#region-edit-clear");
  await sleep(200);
  const baseShift = await regionHexesFromApi("m8r_lasso");
  const shiftCell = await page.evaluate((cells) => {
    const D = [
      [1, 0],
      [1, -1],
      [0, -1],
      [-1, 0],
      [-1, 1],
      [0, 1],
    ];
    const set = {};
    cells.forEach((c) => {
      set[c.q + "," + c.r] = true;
    });
    const ex = (q, r) => window.SimosMap.hexExists(q, r);
    for (const c of cells) {
      for (const d of D) {
        const q = c.q + d[0];
        const r = c.r + d[1];
        if (!set[q + "," + r] && ex(q, r)) return { q: q, r: r };
      }
    }
    return null;
  }, baseShift);
  await centerView(page, shiftCell.q, shiftCell.r, 2.4);
  canvasBox = await page.locator("#canvas").boundingBox();
  const shiftPt = await page.evaluate((h) => window.SimosMap.screenPointOf(h.q, h.r), shiftCell);
  markPhase("J10b-shift-paint");
  await page.keyboard.down("Shift");
  await page.mouse.move(canvasBox.x + shiftPt.x, canvasBox.y + shiftPt.y);
  await page.mouse.down({ button: "right" });
  await page.mouse.up({ button: "right" });
  await page.keyboard.up("Shift");
  await sleep(300);
  const afterShiftPaint = await page.evaluate(() => window.SimosMap.regionEditDebug());
  const shiftPaintWrites = nonGet.slice(phaseStarts["J10b-shift-paint"]).filter((r) => r.path === "/api/command");
  values.shiftPerHex = {
    cell: shiftCell,
    draftCount: afterShiftPaint.draftHexCount,
    lassoActive: afterShiftPaint.lassoActive,
    createRegionPosts: shiftPaintWrites.filter((r) => (parse(r.post) || {}).type === "map.CreateRegion").length,
    writes: shiftPaintWrites.length,
  };
  check(
    "k4-shift-right-per-hex-no-lasso",
    afterShiftPaint.draftHexCount === 1 && !afterShiftPaint.lassoActive && shiftPaintWrites.length === 0,
    JSON.stringify(values.shiftPerHex)
  );
  const headBeforeShiftMerge = await page.evaluate(() => window.SimosApp.getState().revision);
  markPhase("J10b-shift-merge");
  await page.click("#region-merge");
  await page
    .waitForFunction((h) => window.SimosApp.getState().revision !== h, headBeforeShiftMerge, { timeout: 20000 })
    .catch(() => null);
  await sleep(800);
  const shiftMergePosts = nonGet.slice(phaseStarts["J10b-shift-merge"]).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "map.UpdateRegion";
  });
  const shiftMergePayload = shiftMergePosts.length === 1 ? parse((parse(shiftMergePosts[0].post) || {}).payloadJson) : null;
  const shiftExpected = setOf(baseShift);
  shiftExpected[hk(shiftCell)] = shiftCell;
  values.shiftPerHexMerge = {
    posts: shiftMergePosts.length,
    payloadCount: shiftMergePayload && (shiftMergePayload.hexes || []).length,
    expectedCount: listOf(shiftExpected).length,
    matchesSingleCellAdd: shiftMergePayload ? sameSet(shiftMergePayload.hexes, listOf(shiftExpected)) : false,
  };
  check(
    "k5-shift-right-merge-one-update",
    shiftMergePosts.length === 1 && shiftMergePayload && sameSet(shiftMergePayload.hexes, listOf(shiftExpected)),
    JSON.stringify(values.shiftPerHexMerge)
  );

  // ═══════════════ §八 边框与区域边界（判据 11 / 12 / 13）═══════════════

  // ⑫ 区域边界被简化（顶点数显著下降），且仍闭合、仍包住区域 hex 集合
  const outlineDebug = await page.evaluate(() => window.SimosMap.debug());
  const outlineRings = await page.evaluate(() => window.SimosMap.regionOutlineRingsForTest());
  const focusRings = ((outlineRings || []).find((o) => o.id === "m8r_lasso") || {}).rings || [];
  const apiLassoNow = await regionHexesFromApi("m8r_lasso");
  function pointInRings(rings, x, y) {
    let inside = false;
    rings.forEach((ring) => {
      for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
        const xi = ring[i].x;
        const yi = ring[i].y;
        const xj = ring[j].x;
        const yj = ring[j].y;
        if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) {
          inside = !inside;
        }
      }
    });
    return inside;
  }
  const SQRT3 = Math.sqrt(3);
  const enclosed = apiLassoNow.every((h) => pointInRings(focusRings, SQRT3 * h.q + (SQRT3 / 2) * h.r, 1.5 * h.r));
  values.outline = {
    verticesBefore: outlineDebug.outlineVerticesBefore,
    verticesAfter: outlineDebug.outlineVerticesAfter,
    ringCount: outlineDebug.outlineRingCount,
    focusRingCount: focusRings.length,
    enclosed: enclosed,
    regionHexCount: apiLassoNow.length,
  };
  check(
    "l1-outline-simplified-and-enclosing",
    outlineDebug.outlineVerticesBefore > 0 &&
      outlineDebug.outlineVerticesAfter > 0 &&
      outlineDebug.outlineVerticesAfter < outlineDebug.outlineVerticesBefore * 0.75 &&
      focusRings.length >= 1 &&
      enclosed,
    JSON.stringify(values.outline)
  );

  // ⑪ 渲染路径不再有逐格 stroke / Path2D 块边界描边
  const strokeStats = await page.evaluate(() => {
    window.__strokeReset();
    window.SimosMap.render();
    return window.__strokeStats();
  });
  values.strokeProbe = strokeStats;
  check(
    "l2-no-per-hex-or-path2d-stroke",
    strokeStats.path2dStrokes === 0 && strokeStats.maxLineToPerStroke <= 300,
    JSON.stringify(strokeStats)
  );

  // ⑬ 相邻不同地形块之间没有黑线（像素采样）
  await page.click('#mode-bar button[data-mode="view"]');
  await sleep(250);
  let terrainPair = null;
  for (let dq = -3; dq <= 3 && !terrainPair; dq++) {
    for (let dr = -3; dr <= 3 && !terrainPair; dr++) {
      const c = { q: lassoCenter.q + dq, r: lassoCenter.r + dr };
      const body = (await api("/api/map/hex?q=" + c.q + "&r=" + c.r)).body;
      if (!body || !body.terrain) {
        continue;
      }
      for (const d of DIRS) {
        const n = { q: c.q + d[0], r: c.r + d[1] };
        const nb = (await api("/api/map/hex?q=" + n.q + "&r=" + n.r)).body;
        if (nb && nb.terrain && nb.terrain !== body.terrain) {
          terrainPair = { a: c, b: n, ta: body.terrain, tb: nb.terrain };
          break;
        }
      }
    }
  }
  let terrainMinLum = null;
  if (terrainPair) {
    const mid = {
      q: Math.round((terrainPair.a.q + terrainPair.b.q) / 2),
      r: Math.round((terrainPair.a.r + terrainPair.b.r) / 2),
    };
    await centerView(page, mid.q, mid.r, 4);
    await sleep(250);
    const pts = await screenPoints(page, [terrainPair.a, terrainPair.b]);
    terrainMinLum = await page.evaluate(
      (pp) => {
        const c = document.getElementById("canvas");
        const ctx = c.getContext("2d");
        const dpr = window.devicePixelRatio || 1;
        const mx = (pp[0].x + pp[1].x) / 2;
        const my = (pp[0].y + pp[1].y) / 2;
        let min = 999;
        for (let dx = -8; dx <= 8; dx++) {
          for (let dy = -8; dy <= 8; dy++) {
            const px = Math.round((mx + dx) * dpr);
            const py = Math.round((my + dy) * dpr);
            if (px < 0 || py < 0 || px >= c.width || py >= c.height) {
              continue;
            }
            const d = ctx.getImageData(px, py, 1, 1).data;
            const lum = 0.2126 * d[0] + 0.7152 * d[1] + 0.0722 * d[2];
            if (lum < min) {
              min = lum;
            }
          }
        }
        return min;
      },
      pts
    );
  }
  values.terrainBorder = { pair: terrainPair, minLuminance: terrainMinLum };
  check(
    "l3-no-black-line-between-terrain-blocks",
    !!terrainPair && terrainMinLum !== null && terrainMinLum > 40,
    JSON.stringify(values.terrainBorder)
  );

  // ── 非 GET 清单（按模式/阶段分组）──
  const phases = Object.keys(phaseStarts).sort((a, b) => phaseStarts[a] - phaseStarts[b]);
  const grouped = phases.map((name, i) => {
    const end = i + 1 < phases.length ? phaseStarts[phases[i + 1]] : nonGet.length;
    const slice = nonGet.slice(phaseStarts[name], end);
    return {
      phase: name,
      types: slice.map((r) => (parse(r.post) || {}).type || "?"),
      paths: slice.map((r) => r.path),
    };
  });
  values.nonGetGrouped = grouped;
  console.log("NONGET_GROUPED " + JSON.stringify(grouped));
  console.log("PAGEERRORS " + JSON.stringify(pageErrors));

  writeJson("result.json", values);
  await browser.close();
  console.log("E2E RESULT: " + (failures.length === 0 ? "ALL PASS" : failures.length + " FAIL " + failures.join(",")));
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((e) => {
  console.log("E2E CRASH: " + e.stack);
  process.exit(2);
});
