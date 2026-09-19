// M8-S e2e —— ① 区域边界精确（撤销 RDP）；② 建区重名主动提示（新建同名 / 合并到同名）。
// 真档 19441 格副本 + 真 ShellMain + 真 pointer 事件 + ★ Java BoundaryProbe（判据 14 的权威对拍）。
// 用法: NODE_PATH=<playwright> PROBE_CP=<java-cp> node e2e.cjs <base-url> <out-dir>
"use strict";

const fs = require("node:fs");
const { execFileSync } = require("node:child_process");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const OUT = process.argv[3] || ".";
const PROBE_CP = process.env.PROBE_CP || "";
const VW = 1280;
const VH = 800;
// ★ Java HexDirection.ALL 的枚举序（E,SE,SW,W,NW,NE）—— 逐边判暴露时必须与 Java 同序。
const DIRS = [
  [1, 0],
  [0, 1],
  [-1, 1],
  [-1, 0],
  [0, -1],
  [1, -1],
];
// Java HexVertex 的第 i 个顶点偏移（60°i − 30°）。
const CU = [1, 1, 0, -1, -1, 0];
const CW = [-1, 1, 2, 1, -1, -2];

const failures = [];
const values = {};

function check(name, ok, detail) {
  console.log("STEP " + name + ": " + (ok ? "PASS" : "FAIL") + (detail ? " " + detail : ""));
  if (!ok) failures.push(name);
}
function writeJson(name, value) {
  fs.writeFileSync(OUT + "/" + name, JSON.stringify(value, null, 2));
}
function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
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
  return { status: response.status, text, body: parse(text) };
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
  if (ka.length !== kb.length) return false;
  return ka.every((k) => Object.prototype.hasOwnProperty.call(B, k));
}
function hexRing(center, radius) {
  let q = center.q + radius * DIRS[3][0];
  let r = center.r + radius * DIRS[3][1];
  const out = [];
  for (let d = 0; d < 6; d++) {
    for (let s = 0; s < radius; s++) {
      out.push({ q, r });
      q += DIRS[d][0];
      r += DIRS[d][1];
    }
  }
  return out;
}
function regionHexesFromApi(id) {
  return api("/api/map/region/" + encodeURIComponent(id)).then((r) => (r.body && r.body.hexes) || []);
}
function regionNameFromApi(id) {
  return api("/api/map/region/" + encodeURIComponent(id)).then((r) => (r.body && r.body.name) || "");
}

// ── 权威边界（JS 复刻 Java RegionBoundary.of）：返回规范化后的环标签串数组 ──
function labelKey(u, w) {
  return u + ":" + w;
}
function canonicalizeLabels(ring) {
  const arr = ring.slice();
  let min = 0;
  for (let i = 1; i < arr.length; i++) {
    const a = arr[i].split(":").map(Number);
    const b = arr[min].split(":").map(Number);
    if (a[0] < b[0] || (a[0] === b[0] && a[1] < b[1])) min = i;
  }
  const rot = [];
  for (let i = 0; i < arr.length; i++) rot.push(arr[(min + i) % arr.length]);
  const rev = [rot[0]];
  for (let i = rot.length - 1; i > 0; i--) rev.push(rot[i]);
  const comp = (x, y) => {
    for (let i = 0; i < x.length; i++) {
      const a = x[i].split(":").map(Number);
      const b = y[i].split(":").map(Number);
      if (a[0] !== b[0]) return a[0] - b[0];
      if (a[1] !== b[1]) return a[1] - b[1];
    }
    return x.length - y.length;
  };
  return comp(rot, rev) <= 0 ? rot : rev;
}
function authoritativeRingLabels(cells) {
  const set = setOf(cells);
  const adj = new Map();
  const push = (a, b) => {
    if (!adj.has(a)) adj.set(a, []);
    if (!adj.has(b)) adj.set(b, []);
    adj.get(a).push(b);
    adj.get(b).push(a);
  };
  (cells || []).forEach((c) => {
    for (let d = 0; d < 6; d++) {
      const nq = c.q + DIRS[d][0];
      const nr = c.r + DIRS[d][1];
      if (Object.prototype.hasOwnProperty.call(set, nq + "," + nr)) continue;
      const a = labelKey(2 * c.q + c.r + CU[d], 3 * c.r + CW[d]);
      const b = labelKey(2 * c.q + c.r + CU[(d + 1) % 6], 3 * c.r + CW[(d + 1) % 6]);
      push(a, b);
    }
  });
  const rings = [];
  const visited = new Set();
  for (const start of adj.keys()) {
    if (visited.has(start)) continue;
    const ring = [];
    let prev = null;
    let cur = start;
    let guard = 0;
    do {
      ring.push(cur);
      visited.add(cur);
      const ns = adj.get(cur);
      const next = ns[0] === prev ? ns[1] : ns[0];
      prev = cur;
      cur = next;
    } while (cur !== start && guard++ < 5000000);
    if (ring.length >= 3) rings.push(canonicalizeLabels(ring));
  }
  return rings;
}
// Java 权威探针：同一组 hex ⇒ RegionBoundary.of 的环标签。
function javaRingLabels(cells) {
  if (!PROBE_CP) return null;
  const args = cells.map((c) => c.q + "," + c.r);
  const raw = execFileSync("java", ["-cp", PROBE_CP, "BoundaryProbe"].concat(args), {
    encoding: "utf8",
    maxBuffer: 256 * 1024 * 1024,
  }).trim();
  if (!raw) return [];
  return raw.split(";").map((ring) => canonicalizeLabels(ring.split(",")));
}
// 渲染环（世界坐标 size=1）⇒ (u,w) 标签 ⇒ 残留 + 到最近格心距离。
function renderRingLabels(rings) {
  const SQ3 = Math.sqrt(3);
  let maxResidual = 0;
  let minCenterDist = Infinity;
  const labels = [];
  (rings || []).forEach((ring) => {
    const arr = [];
    ring.forEach((p) => {
      const u = p.x / (SQ3 / 2);
      const w = p.y / 0.5;
      const ur = Math.round(u);
      const wr = Math.round(w);
      maxResidual = Math.max(maxResidual, Math.abs(u - ur), Math.abs(w - wr));
      arr.push(labelKey(ur, wr));
      // 该世界点 → 最近格心
      const fq = ((SQ3 / 3) * p.x - (1 / 3) * p.y);
      const fr = (2 / 3) * p.y;
      const cq = Math.round(fq);
      const cr = Math.round(fr);
      const cx = SQ3 * cq + (SQ3 / 2) * cr;
      const cy = 1.5 * cr;
      const dist = Math.hypot(p.x - cx, p.y - cy);
      if (dist < minCenterDist) minCenterDist = dist;
    });
    labels.push(canonicalizeLabels(arr));
  });
  return { labels, maxResidual, minCenterDist };
}
function labelMultisetEqual(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  const A = a.map((r) => r.join(",")).sort();
  const B = b.map((r) => r.join(",")).sort();
  return A.every((v, i) => v === B[i]);
}
function totalVertices(ringLabels) {
  return (ringLabels || []).reduce((s, r) => s + r.length, 0);
}

async function centerView(page, q, r, scale) {
  await page.evaluate(
    ([hex, s]) => {
      const M = window.SimosMap;
      const w = M.hexToPixel(hex.q, hex.r, M.BASE_CELL);
      M.setView({ scale: s, tx: window.innerWidth / 2 - w.x * s, ty: window.innerHeight / 2 - w.y * s });
    },
    [{ q, r }, scale || 1]
  );
  await sleep(180);
}
async function screenPoints(page, points) {
  return page.evaluate((list) => list.map((h) => window.SimosMap.screenPointOf(h.q, h.r)), points);
}
async function leftDrag(page, box, from, to) {
  await page.mouse.move(box.x + from.x, box.y + from.y);
  await page.mouse.down();
  await page.mouse.move(box.x + to.x, box.y + to.y, { steps: 30 });
  await page.mouse.up();
  await sleep(150);
}
async function independentFlood(page, wallCells) {
  return page.evaluate((cells) => {
    const KEY = (q, r) => q + "," + r;
    const D = [
      [1, 0],
      [0, 1],
      [-1, 1],
      [-1, 0],
      [0, -1],
      [1, -1],
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
        let qq = cq + rad * D[3][0];
        let rr = cr + rad * D[3][1];
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
// 找 n 个该区域之外、且真实存在的相邻格（供"新建同名区域"的草稿）。
async function disjointHexes(page, existing, n) {
  return page.evaluate(
    ([cells, count]) => {
      const D = [
        [1, 0],
        [0, 1],
        [-1, 1],
        [-1, 0],
        [0, -1],
        [1, -1],
      ];
      const set = {};
      cells.forEach((c) => {
        set[c.q + "," + c.r] = true;
      });
      const out = [];
      const seen = {};
      for (const c of cells) {
        for (const d of D) {
          const q = c.q + d[0];
          const r = c.r + d[1];
          const k = q + "," + r;
          if (!set[k] && !seen[k] && window.SimosMap.hexExists(q, r)) {
            seen[k] = true;
            out.push({ q, r });
            if (out.length >= count) return out;
          }
        }
      }
      return out;
    },
    [existing, n]
  );
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });

  const overview0 = (await api("/api/map/overview")).body || {};
  const regions0 = overview0.regions || [];
  const regionIds0 = regions0.map((r) => r.id);
  values.preflight = { hexCount: overview0.hexCount, regions: regionIds0, blockCount: (overview0.blocks || []).length };
  check(
    "pre-real-archive",
    overview0.hexCount === 19441 && regionIds0.indexOf("test_nation") >= 0 && regionIds0.indexOf("test_annex_target") >= 0,
    JSON.stringify(values.preflight)
  );

  const executablePath =
    process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
  const browser = await chromium.launch({ headless: true, executablePath });
  const page = await browser.newPage({ viewport: { width: VW, height: VH } });
  const pageErrors = [];
  page.on("pageerror", (e) => pageErrors.push(String(e && e.stack ? e.stack : e)));
  const nonGet = [];
  page.on("request", (req) => {
    const parsed = new URL(req.url());
    if (req.method() !== "GET") nonGet.push({ method: req.method(), path: parsed.pathname, post: req.postData() });
  });
  const phaseStarts = {};
  function markPhase(name) {
    phaseStarts[name] = nonGet.length;
  }

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

  await page.click('#mode-bar button[data-mode="region-edit"]');
  await sleep(300);

  // ── 回归：右键套索创建（唯一名，不触发重名）⇒ 恰 1 条 CreateRegion ──
  const LASSO_R = 2;
  const lassoCenter = await page.evaluate((radius) => {
    const D = [
      [1, 0],
      [0, 1],
      [-1, 1],
      [-1, 0],
      [0, -1],
      [1, -1],
    ];
    const ex = (q, r) => window.SimosMap.hexExists(q, r);
    function ring(c, R) {
      let q = c.q + R * D[3][0];
      let r = c.r + R * D[3][1];
      const o = [];
      for (let d = 0; d < 6; d++) {
        for (let s = 0; s < R; s++) {
          o.push({ q, r });
          q += D[d][0];
          r += D[d][1];
        }
      }
      return o;
    }
    for (const c of [[-16, 0], [-18, 0], [-20, 0], [-14, 0], [0, 0]]) {
      if (ring({ q: c[0], r: c[1] }, radius).every((p) => ex(p.q, p.r))) return { q: c[0], r: c[1] };
    }
    return null;
  }, LASSO_R);
  check("a0-lasso-center-found", !!lassoCenter, JSON.stringify(lassoCenter));

  await page.click("#region-edit-new");
  await sleep(150);
  await page.fill("#region-create-id", "m8s_lasso");
  await page.fill("#region-create-name", "M8S Lasso Unique");
  await centerView(page, lassoCenter.q, lassoCenter.r, 1);
  let canvasBox = await page.locator("#canvas").boundingBox();
  const ring = hexRing(lassoCenter, LASSO_R);
  const ringPts = await screenPoints(page, ring);
  const headBeforeLasso = await page.evaluate(() => window.SimosApp.getState().revision);
  markPhase("A-lasso-create");
  await page.mouse.move(canvasBox.x + ringPts[0].x, canvasBox.y + ringPts[0].y);
  await page.mouse.down({ button: "right" });
  for (let i = 1; i < ringPts.length; i++) {
    await page.mouse.move(canvasBox.x + ringPts[i].x, canvasBox.y + ringPts[i].y, { steps: 1 });
    await sleep(15);
  }
  const wallCells = await page.evaluate(() => window.SimosMap.lassoHexes());
  await page.mouse.up({ button: "right" });
  await page
    .waitForFunction((h) => window.SimosApp.getState().revision !== h, headBeforeLasso, { timeout: 20000 })
    .catch(() => null);
  await sleep(900);
  const expectedLasso = await independentFlood(page, wallCells);
  const lassoPosts = nonGet.slice(phaseStarts["A-lasso-create"]).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "map.CreateRegion";
  });
  const lassoPayload = lassoPosts.length === 1 ? parse((parse(lassoPosts[0].post) || {}).payloadJson) : null;
  values.lasso = {
    center: lassoCenter,
    expectedCount: expectedLasso.length,
    posts: lassoPosts.length,
    payloadCount: lassoPayload && (lassoPayload.hexes || []).length,
    matchesIndependentFlood: lassoPayload ? sameSet(lassoPayload.hexes, expectedLasso) : false,
  };
  check(
    "a1-lasso-one-create",
    lassoPosts.length === 1 && lassoPayload && lassoPayload.regionId === "m8s_lasso" && sameSet(lassoPayload.hexes, expectedLasso),
    JSON.stringify(values.lasso)
  );

  // ── 判据 14：渲染边界 == 权威 Region.boundary（Java 探针 + JS 复刻）；顶点落在 hex 顶点格点上、非格心 ──
  const c14Targets = ["test_nation", "m8s_lasso"];
  for (const target of c14Targets) {
    await page.evaluate((id) => window.SimosApp.setRegionFocus(id), target);
    await page.waitForFunction(
      (id) =>
        window.SimosMap.regionOutlineRingsForTest().some((o) => o.id === id && o.rings.length > 0),
      target,
      { timeout: 10000 }
    ).catch(() => null);
    await sleep(400);
    const cells = await regionHexesFromApi(target);
    const outline = await page.evaluate(
      (id) => {
        const all = window.SimosMap.regionOutlineRingsForTest();
        const hit = all.filter((o) => o.id === id);
        return hit.length ? hit[0].rings : [];
      },
      target
    );
    const render = renderRingLabels(outline);
    const jsAuth = authoritativeRingLabels(cells);
    const javaAuth = javaRingLabels(cells);
    const renderVertices = totalVertices(render.labels);
    const jsVertices = totalVertices(jsAuth);
    const javaVertices = javaAuth ? totalVertices(javaAuth) : null;
    values["c14_" + target] = {
      hexCount: cells.length,
      ringCount: render.labels.length,
      renderVertices,
      jsReplicatedVertices: jsVertices,
      javaVertices,
      renderEqualsJs: labelMultisetEqual(render.labels, jsAuth),
      renderEqualsJava: javaAuth ? labelMultisetEqual(render.labels, javaAuth) : null,
      maxLatticeResidual: render.maxResidual,
      minVertexToHexCenterDist: render.minCenterDist,
      sampleRenderLabels: render.labels[0] ? render.labels[0].slice(0, 6) : [],
      sampleJavaLabels: javaAuth && javaAuth[0] ? javaAuth[0].slice(0, 6) : [],
    };
    const v = values["c14_" + target];
    writeJson("c14-debug-" + target + ".json", {
      renderRings: render.labels,
      jsRings: jsAuth,
      javaRings: javaAuth,
      renderSortedVertices: render.labels.flat().slice().sort(),
      jsSortedVertices: jsAuth.flat().slice().sort(),
    });
    check("c14-" + target + "-render-equals-js-authoritative", v.renderEqualsJs, JSON.stringify(v));
    if (javaAuth) {
      check("c14-" + target + "-render-equals-java-authoritative", v.renderEqualsJava, JSON.stringify(v));
    }
    check(
      "c14-" + target + "-vertices-on-hex-corners-not-centers",
      v.maxLatticeResidual < 1e-3 && v.minVertexToHexCenterDist > 0.9 && v.minVertexToHexCenterDist < 1.1,
      JSON.stringify({ residual: v.maxLatticeResidual, dist: v.minVertexToHexCenterDist })
    );
  }

  // 边界视觉截图：聚焦 19 格的 lasso 区、放大到能看清"逐 hex 外缘折线"（判据 14 的视觉旁证）。
  const lassoHexesVisual = await regionHexesFromApi("m8s_lasso");
  if (lassoHexesVisual.length) {
    await page.evaluate(() => window.SimosApp.setRegionFocus("m8s_lasso"));
    await sleep(300);
    await centerView(page, lassoCenter.q, lassoCenter.r, 5.5);
    await sleep(350);
    await page.screenshot({ path: OUT + "/screenshot-exact-boundary.png" });
    await centerView(page, lassoCenter.q, lassoCenter.r, 10);
    await sleep(350);
    await page.screenshot({ path: OUT + "/screenshot-exact-boundary-closeup.png" });
  }

  // ── 判据 15(i)：重名 ⇒ 弹二选一；选「新建同名区域」⇒ 恰 1 条 CreateRegion（同 name、不同 id）──
  const nationName = await regionNameFromApi("test_nation");
  const nationHexesForDup = await regionHexesFromApi("test_nation");
  const dupNewHexes = await disjointHexes(page, nationHexesForDup, 3);
  check("c15i0-disjoint-hexes-found", nationName.length > 0 && dupNewHexes.length === 3, JSON.stringify({ nationName, dupNewHexes }));
  await page.click("#region-edit-new");
  await sleep(120);
  await page.fill("#region-create-id", "m8s_dup_new");
  await page.fill("#region-create-name", nationName);
  await page.evaluate((cells) => window.SimosMap.regionPaintForTest(cells, "add"), dupNewHexes);
  await sleep(120);
  const headBeforeDupI = await page.evaluate(() => window.SimosApp.getState().revision);
  const nonGetBeforeDupI = nonGet.length;
  markPhase("B-dup-prompt-i");
  await page.click("#region-create-submit");
  await sleep(500);
  const promptI = await page.evaluate(() => ({
    debug: window.SimosMap.regionEditDebug().nameConflict,
    visible: !document.getElementById("region-name-conflict").hidden,
    msg: document.getElementById("region-name-conflict-msg").textContent,
    head: window.SimosApp.getState().revision,
  }));
  const writesAtPromptI = nonGet.slice(nonGetBeforeDupI).filter((r) => r.path === "/api/command").length;
  values.dupPromptI = { prompt: promptI, writesAtPrompt: writesAtPromptI };
  check(
    "c15i1-prompt-shown-no-write-yet",
    promptI.visible &&
      promptI.debug &&
      promptI.debug.existingId === "test_nation" &&
      promptI.debug.name === nationName &&
      promptI.head === headBeforeDupI &&
      writesAtPromptI === 0,
    JSON.stringify(values.dupPromptI)
  );
  if (promptI.visible) {
    await page.screenshot({ path: OUT + "/screenshot-dup-prompt.png" });
    markPhase("B-dup-new");
    await page.click("#region-name-conflict-new");
    await page
      .waitForFunction((h) => window.SimosApp.getState().revision !== h, headBeforeDupI, { timeout: 20000 })
      .catch(() => null);
    await sleep(800);
    const dupNewPosts = nonGet.slice(phaseStarts["B-dup-new"]).filter((r) => {
      const body = parse(r.post) || {};
      return r.path === "/api/command";
    });
    const dupNewPayload = dupNewPosts.length === 1 ? parse((parse(dupNewPosts[0].post) || {}).payloadJson) : null;
    const dupNewType = dupNewPosts.length === 1 ? (parse(dupNewPosts[0].post) || {}).type : null;
    const dupNewApi = await api("/api/map/region/m8s_dup_new");
    values.dupNew = {
      posts: dupNewPosts.length,
      type: dupNewType,
      payload: dupNewPayload,
      apiName: dupNewApi.body && dupNewApi.body.name,
      apiHexCount: dupNewApi.body && dupNewApi.body.hexCount,
    };
    check(
      "c15i2-create-same-name-different-id",
      dupNewPosts.length === 1 &&
        dupNewType === "map.CreateRegion" &&
        dupNewPayload &&
        dupNewPayload.regionId === "m8s_dup_new" &&
        dupNewPayload.name === nationName &&
        dupNewApi.body &&
        dupNewApi.body.name === nationName &&
        dupNewApi.body.hexCount === dupNewHexes.length,
      JSON.stringify(values.dupNew)
    );
  } else {
    // 容错：重名检测被去掉（如 m11）⇒ 不点隐藏按钮、不崩溃，如实记 FAIL。
    check("c15i2-create-same-name-different-id", false, "skipped: 未弹重名提示（直接提交）");
  }

  // ── 判据 15(ii)：重名 ⇒ 弹二选一；选「合并到同名已有区域」⇒ 恰 1 条 UpdateRegion（并集逐值）──
  const annexName = await regionNameFromApi("test_annex_target");
  const annexHexesBefore = await regionHexesFromApi("test_annex_target");
  const annexNewHexes = await disjointHexes(page, annexHexesBefore, 3);
  check(
    "c15ii0-disjoint-hexes-found",
    annexName.length > 0 && annexNewHexes.length === 3,
    JSON.stringify({ annexName, beforeCount: annexHexesBefore.length, annexNewHexes })
  );
  await page.click("#region-edit-new");
  await sleep(120);
  await page.fill("#region-create-id", "m8s_dup_merge");
  await page.fill("#region-create-name", annexName);
  await page.evaluate((cells) => window.SimosMap.regionPaintForTest(cells, "add"), annexNewHexes);
  await sleep(120);
  const headBeforeMerge = await page.evaluate(() => window.SimosApp.getState().revision);
  const nonGetBeforeMerge = nonGet.length;
  await page.click("#region-create-submit");
  await sleep(500);
  const promptII = await page.evaluate(() => ({
    debug: window.SimosMap.regionEditDebug().nameConflict,
    visible: !document.getElementById("region-name-conflict").hidden,
    head: window.SimosApp.getState().revision,
  }));
  const writesAtPromptII = nonGet.slice(nonGetBeforeMerge).filter((r) => r.path === "/api/command").length;
  values.dupPromptII = { prompt: promptII, writesAtPrompt: writesAtPromptII };
  check(
    "c15ii1-prompt-shown-no-write-yet",
    promptII.visible &&
      promptII.debug &&
      promptII.debug.existingId === "test_annex_target" &&
      promptII.debug.name === annexName &&
      promptII.head === headBeforeMerge &&
      writesAtPromptII === 0,
    JSON.stringify(values.dupPromptII)
  );
  if (!promptII.visible) {
    check("c15ii2-merge-update-union-value-exact", false, "skipped: 未弹重名提示（直接提交）");
  } else {
  markPhase("C-dup-merge");
  await page.click("#region-name-conflict-merge");
  await page
    .waitForFunction((h) => window.SimosApp.getState().revision !== h, headBeforeMerge, { timeout: 20000 })
    .catch(() => null);
  await sleep(800);
  const mergePosts = nonGet.slice(phaseStarts["C-dup-merge"]).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command";
  });
  const mergePayload = mergePosts.length === 1 ? parse((parse(mergePosts[0].post) || {}).payloadJson) : null;
  const mergeType = mergePosts.length === 1 ? (parse(mergePosts[0].post) || {}).type : null;
  const unionSet = setOf(annexHexesBefore);
  annexNewHexes.forEach((h) => {
    unionSet[hk(h)] = h;
  });
  const unionList = listOf(unionSet);
  const payloadHexes = mergePayload ? mergePayload.hexes || [] : [];
  const annexStillPresent = annexHexesBefore.every((h) => Object.prototype.hasOwnProperty.call(setOf(payloadHexes), hk(h)));
  values.dupMerge = {
    posts: mergePosts.length,
    type: mergeType,
    regionId: mergePayload && mergePayload.regionId,
    beforeCount: annexHexesBefore.length,
    newCount: annexNewHexes.length,
    payloadCount: payloadHexes.length,
    unionCount: unionList.length,
    payloadEqualsUnion: mergePayload ? sameSet(payloadHexes, unionList) : false,
    allOriginalHexesRetained: annexStillPresent,
    headBefore: headBeforeMerge,
    headAfter: await page.evaluate(() => window.SimosApp.getState().revision),
  };
  check(
    "c15ii2-merge-update-union-value-exact",
    mergePosts.length === 1 &&
      mergeType === "map.UpdateRegion" &&
      mergePayload &&
      mergePayload.regionId === "test_annex_target" &&
      sameSet(payloadHexes, unionList) &&
      annexStillPresent,
    JSON.stringify(values.dupMerge)
  );
  }

  // ── 回归：边界小点只对 focus 区域（未选中 ⇒ 0）──
  await page.evaluate(() => window.SimosApp.setRegionFocus("m8s_lasso"));
  await page
    .waitForFunction(() => window.SimosMap.regionEditDebug().boundaryDotCount > 0, null, { timeout: 8000 })
    .catch(() => null);
  const dotsFocused = await page.evaluate(() => window.SimosMap.regionEditDebug().boundaryDotCount);
  await page.evaluate(() => window.SimosApp.setRegionFocus(null));
  await sleep(300);
  const dotsUnfocused = await page.evaluate(() => window.SimosMap.regionEditDebug().boundaryDotCount);
  values.dots = { focused: dotsFocused, unfocused: dotsUnfocused };
  check("r-dots-only-when-focused", dotsFocused > 0 && dotsUnfocused === 0, JSON.stringify(values.dots));

  // ── 回归：合并 = 并集 / 剔除 = 差集（按钮路径，逐值）──
  await page.evaluate(() => window.SimosApp.setRegionFocus("m8s_lasso"));
  await sleep(300);
  const baseMergeR = await regionHexesFromApi("m8s_lasso");
  const mergeTempR = await disjointHexes(page, baseMergeR, 3);
  await page.evaluate((cells) => window.SimosMap.regionPaintForTest(cells, "add"), mergeTempR);
  await sleep(120);
  const headBeforeMergeR = await page.evaluate(() => window.SimosApp.getState().revision);
  markPhase("R-merge");
  await page.click("#region-merge");
  await page
    .waitForFunction((h) => window.SimosApp.getState().revision !== h, headBeforeMergeR, { timeout: 20000 })
    .catch(() => null);
  await sleep(700);
  const mergePostsR = nonGet.slice(phaseStarts["R-merge"]).filter((r) => (parse(r.post) || {}).type === "map.UpdateRegion");
  const mergePayloadR = mergePostsR.length === 1 ? parse((parse(mergePostsR[0].post) || {}).payloadJson) : null;
  const unionR = setOf(baseMergeR);
  mergeTempR.forEach((h) => {
    unionR[hk(h)] = h;
  });
  values.mergeR = {
    posts: mergePostsR.length,
    payloadCount: mergePayloadR && (mergePayloadR.hexes || []).length,
    unionCount: listOf(unionR).length,
    matchesUnion: mergePayloadR ? sameSet(mergePayloadR.hexes, listOf(unionR)) : false,
  };
  check("r-merge-equals-union", mergePostsR.length === 1 && values.mergeR.matchesUnion, JSON.stringify(values.mergeR));

  const baseExcludeR = await regionHexesFromApi("m8s_lasso");
  const excludeTempR = baseExcludeR.slice(0, 3);
  await page.evaluate((cells) => window.SimosMap.regionPaintForTest(cells, "add"), excludeTempR);
  await sleep(120);
  const headBeforeExcludeR = await page.evaluate(() => window.SimosApp.getState().revision);
  markPhase("R-exclude");
  await page.click("#region-exclude");
  await page
    .waitForFunction((h) => window.SimosApp.getState().revision !== h, headBeforeExcludeR, { timeout: 20000 })
    .catch(() => null);
  await sleep(700);
  const excludePostsR = nonGet.slice(phaseStarts["R-exclude"]).filter((r) => (parse(r.post) || {}).type === "map.UpdateRegion");
  const excludePayloadR = excludePostsR.length === 1 ? parse((parse(excludePostsR[0].post) || {}).payloadJson) : null;
  const diffR = setOf(baseExcludeR);
  excludeTempR.forEach((h) => {
    delete diffR[hk(h)];
  });
  values.excludeR = {
    posts: excludePostsR.length,
    payloadCount: excludePayloadR && (excludePayloadR.hexes || []).length,
    diffCount: listOf(diffR).length,
    matchesDifference: excludePayloadR ? sameSet(excludePayloadR.hexes, listOf(diffR)) : false,
  };
  check(
    "r-exclude-equals-difference",
    excludePostsR.length === 1 && values.excludeR.matchesDifference,
    JSON.stringify(values.excludeR)
  );

  // ── 回归：M9 点选仍准（hexAtScreen → 同一 hex）──
  const pickCheck = await page.evaluate(() => {
    const p = window.SimosMap.screenPointOf(-16, 0);
    const hit = window.SimosMap.hexAtScreen(p);
    return { p, hit };
  });
  values.pick = pickCheck;
  check("r-m9-pick-accurate", pickCheck.hit && pickCheck.hit.q === -16 && pickCheck.hit.r === 0, JSON.stringify(pickCheck));

  // ── 回归：左键拖动 = 平移、零写（区域编辑模式）──
  await page.evaluate(() => window.SimosApp.setRegionFocus(null));
  await centerView(page, lassoCenter.q, lassoCenter.r, 1);
  canvasBox = await page.locator("#canvas").boundingBox();
  const viewBefore = await page.evaluate(() => window.SimosMap.currentView());
  const nonGetBeforePan = nonGet.length;
  await leftDrag(page, canvasBox, { x: 400, y: 300 }, { x: 520, y: 380 });
  const viewAfter = await page.evaluate(() => window.SimosMap.currentView());
  const panWrites = nonGet.slice(nonGetBeforePan).filter((r) => r.path === "/api/command").length;
  values.leftPan = { viewBefore, viewAfter, writes: panWrites };
  check(
    "r-left-drag-pans-zero-write",
    viewAfter.tx !== viewBefore.tx && viewAfter.ty !== viewBefore.ty && panWrites === 0,
    JSON.stringify(values.leftPan)
  );

  // ── 回归：M9 块渲染仍准 ──
  const m9After = await page.evaluate(() => {
    const d = window.SimosMap.debug();
    return { blockCount: d.blockCount, unmergedCount: d.unmergedCount };
  });
  values.m9After = m9After;
  check("r-m9-blocks-still-44", m9After.blockCount === 44 && m9After.unmergedCount === 0, JSON.stringify(m9After));
  check("r-no-pageerror", pageErrors.length === 0, JSON.stringify(pageErrors));

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
