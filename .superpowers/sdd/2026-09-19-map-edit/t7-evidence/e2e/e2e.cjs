// M8 T7/T8 e2e —— 五模式框架 + 地图编辑（真档副本、真 ShellMain、真 pointer 事件）。
// 覆盖：a 五模式可切/当前模式可见；b 白名单（常规/区域查看零写 + 地图编辑放行）；c 拖刷一条命令（载荷 5 格 / head +1 /
//       节点数不变 / 命令明细 +1）；d 地形真变 + 离屏位图重建（像素）；e 调色板只列后端词表；f 负例（词表外/图外）不写；
//       g 不退化（右键路线 / 左键取消移动 / 浮层不穿透 / 0 pageerror）；h 截图。
// 用法: NODE_PATH=<playwright> node e2e.cjs <base-url> <out-dir>
"use strict";

const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const OUT = process.argv[3] || ".";
const VW = 1280;
const VH = 800;

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

function tickStats(body) {
  const nodes = (body && body.nodes) || [];
  const ticks = {};
  nodes.forEach((n) => {
    ticks[String(n.tick)] = true;
  });
  return {
    nodeCount: nodes.length,
    tickCount: Object.keys(ticks).length,
    headTick: nodes.length ? String(nodes[nodes.length - 1].tick) : null,
  };
}

function hexKey(h) {
  return h.q + "," + h.r;
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });

  const RUN = [-18, -17, -16, -15, -14].map((q) => ({ q: q, r: 0 }));
  const ROUTE_FROM = { q: 18, r: -13 };
  const ROUTE_TO = { q: 20, r: -13 };
  const UNIT_ID = "t7-e2e-unit";

  // ── preflight：真档地形词表 / 5 格改前地形 ──
  const overview = await api("/api/map/overview");
  const terrainTypes = (overview.body && overview.body.terrainTypes) || [];
  const terrainKeys = terrainTypes.map((t) => t.key);
  values.overviewTerrainTypes = terrainKeys;
  check(
    "pre-overview-real-archive",
    overview.body && overview.body.hexCount === 19441 && terrainKeys.length >= 1,
    "hexCount=" + (overview.body && overview.body.hexCount) + " terrainTypes=" + JSON.stringify(terrainKeys)
  );
  const beforeTerrains = {};
  for (const h of RUN) {
    const r = await api("/api/map/hex?q=" + h.q + "&r=" + h.r);
    beforeTerrains[hexKey(h)] = r.body && r.body.terrain;
  }
  values.terrainBefore = beforeTerrains;
  const TARGET = terrainKeys.indexOf("ocean") >= 0 ? "ocean" : terrainKeys[0];
  const targetType = terrainTypes.filter((t) => t.key === TARGET)[0];
  check(
    "pre-target-differs",
    Object.keys(beforeTerrains).every((k) => beforeTerrains[k] !== TARGET),
    "target=" + TARGET + " before=" + JSON.stringify(beforeTerrains)
  );

  const executablePath =
    process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
  const browser = await chromium.launch({ headless: true, executablePath });
  const page = await browser.newPage({ viewport: { width: VW, height: VH } });
  const pageErrors = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));
  const nonGet = [];
  const hexGets = [];
  page.on("request", (req) => {
    const parsed = new URL(req.url());
    if (req.method() !== "GET") {
      nonGet.push({ method: req.method(), path: parsed.pathname, post: req.postData() });
    } else if (parsed.pathname === "/api/map/hex") {
      hexGets.push(parsed.search);
    }
  });

  await page.goto(BASE + "/", { waitUntil: "domcontentloaded" });
  await page.waitForFunction(() => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(), null, {
    timeout: 60000,
  });
  await page.waitForFunction(
    () => document.getElementById("map-status").textContent.indexOf("已载入") >= 0,
    null,
    { timeout: 60000 }
  );
  await sleep(600);
  const canvasBox = await page.locator("#canvas").boundingBox();

  // ── a 五模式可切 + 当前模式可见 ──
  const modes = [
    ["常规", "view"],
    ["区域查看", "region"],
    ["地图编辑", "map-edit"],
    ["区域编辑", "region-edit"],
    ["单位移动编辑", "unit"],
  ];
  const aResults = {};
  for (const [label, mode] of modes) {
    await page.click('#mode-bar button[data-mode="' + mode + '"]');
    await sleep(150);
    const cur = await page.textContent("#mode-current");
    const bodyMode = await page.getAttribute("body", "data-mode");
    const pressed = await page.getAttribute('#mode-bar button[data-mode="' + mode + '"]', "aria-pressed");
    aResults[mode] = { cur: cur, bodyMode: bodyMode, pressed: pressed };
    check("a-mode-" + mode, cur === label && bodyMode === mode && pressed === "true", JSON.stringify(aResults[mode]));
  }
  values.modes = aResults;

  // ── a2 切模式清状态（切走再切回干净）──
  await page.click('#mode-bar button[data-mode="region"]');
  await sleep(150);
  await page.evaluate((h) => {
    const M = window.SimosMap;
    const w = M.hexToPixel(h.q, h.r, M.BASE_CELL);
    M.setView({ scale: 1, tx: window.innerWidth / 2 - w.x, ty: window.innerHeight / 2 - w.y });
  }, RUN[0]);
  await sleep(200);
  const p0 = await page.evaluate((h) => window.SimosMap.screenPointOf(h.q, h.r), RUN[0]);
  await page.mouse.click(canvasBox.x + p0.x, canvasBox.y + p0.y);
  await page
    .waitForFunction(() => window.SimosApp.getState().highlightRegions.length >= 1, null, { timeout: 8000 })
    .catch(() => null);
  await sleep(400);
  const beforeSwitch = await page.evaluate(() => ({
    sel: window.SimosApp.getState().selection,
    hl: window.SimosApp.getState().highlightRegions.length,
    debugHl: window.SimosMap.debug().highlightHexCount,
  }));
  await page.click('#mode-bar button[data-mode="map-edit"]');
  await sleep(300);
  const afterSwitch = await page.evaluate(() => ({
    sel: window.SimosApp.getState().selection,
    hl: window.SimosApp.getState().highlightRegions.length,
    debugHl: window.SimosMap.debug().highlightHexCount,
    brush: window.SimosMap.mapEditDebug().brushHexCount,
  }));
  await page.click('#mode-bar button[data-mode="region"]');
  await sleep(300);
  const afterBack = await page.evaluate(() => ({
    sel: window.SimosApp.getState().selection,
    hl: window.SimosApp.getState().highlightRegions.length,
  }));
  values.modeSwitch = { beforeSwitch, afterSwitch, afterBack };
  check(
    "a2-mode-switch-clears-state",
    beforeSwitch.sel &&
      beforeSwitch.sel.kind === "hex" &&
      beforeSwitch.hl >= 1 &&
      beforeSwitch.debugHl >= 1 &&
      afterSwitch.sel === null &&
      afterSwitch.hl === 0 &&
      afterSwitch.debugHl === 0 &&
      afterSwitch.brush === 0 &&
      afterBack.sel === null &&
      afterBack.hl === 0,
    JSON.stringify({ beforeSwitch, afterSwitch, afterBack })
  );

  // ── b 白名单 ──
  const wl = await page.evaluate(() => ({
    view: window.SimosModes.isWriteAllowed("view", "map.SetTerrain"),
    region: window.SimosModes.isWriteAllowed("region", "map.SetTerrain"),
    mapEdit: window.SimosModes.isWriteAllowed("map-edit", "map.SetTerrain"),
    mapEditEdge: window.SimosModes.isWriteAllowed("map-edit", "map.SetEdge"),
    mapEditRnd: window.SimosModes.isWriteAllowed("map-edit", "map.RandomizeRegion"),
    regionEdit: window.SimosModes.isWriteAllowed("region-edit", "map.UpdateRegion"),
    unit: window.SimosModes.isWriteAllowed("unit", "unit.PlanRoute"),
    unknown: window.SimosModes.isWriteAllowed("nope", "map.SetTerrain"),
    empty: window.SimosModes.isWriteAllowed("view", ""),
    labels: window.SimosModes.modeIds().map((id) => window.SimosModes.modeLabel(id)),
  }));
  values.whitelist = wl;
  check(
    "b-whitelist-pure-function",
    wl.view === false &&
      wl.region === false &&
      wl.mapEdit === true &&
      wl.mapEditEdge === true &&
      wl.mapEditRnd === true &&
      wl.regionEdit === true &&
      wl.unit === true &&
      wl.unknown === false &&
      wl.empty === false,
    JSON.stringify(wl)
  );

  const nonGetBeforeReadonly = nonGet.length;
  // 常规模式：真拖 + 直调写命令（都应零写）
  await page.click('#mode-bar button[data-mode="view"]');
  await sleep(150);
  const paletteHiddenView = await page.evaluate(
    () => document.querySelector("section.map-editor").hidden === true
  );
  await page.evaluate(([a, b]) => {
    const M = window.SimosMap;
    const w = M.hexToPixel(a.q, a.r, M.BASE_CELL);
    M.setView({ scale: 1, tx: window.innerWidth / 2 - w.x, ty: window.innerHeight / 2 - w.y });
  }, [RUN[0], RUN[4]]);
  await sleep(200);
  const dragPts = await page.evaluate((run) => run.map((h) => window.SimosMap.screenPointOf(h.q, h.r)), RUN);
  await page.mouse.move(canvasBox.x + dragPts[0].x, canvasBox.y + dragPts[0].y);
  await page.mouse.down();
  await page.mouse.move(canvasBox.x + dragPts[4].x, canvasBox.y + dragPts[4].y, { steps: 25 });
  await page.mouse.up();
  await sleep(200);
  const deniedView = await page.evaluate(async () => {
    const r = await window.SimosApp.writeCommand("map.SetTerrain", { hexes: [{ q: -18, r: 0 }], terrain: "ocean" });
    return { ok: r.ok, kind: r.kind };
  });
  // 区域查看模式：同样
  await page.click('#mode-bar button[data-mode="region"]');
  await sleep(150);
  const paletteHiddenRegion = await page.evaluate(
    () => document.querySelector("section.map-editor").hidden === true
  );
  await page.mouse.move(canvasBox.x + dragPts[0].x, canvasBox.y + dragPts[0].y);
  await page.mouse.down();
  await page.mouse.move(canvasBox.x + dragPts[4].x, canvasBox.y + dragPts[4].y, { steps: 25 });
  await page.mouse.up();
  await sleep(200);
  const deniedRegion = await page.evaluate(async () => {
    const r = await window.SimosApp.writeCommand("map.SetTerrain", { hexes: [{ q: -18, r: 0 }], terrain: "ocean" });
    return { ok: r.ok, kind: r.kind };
  });
  const nonGetReadonly = nonGet.slice(nonGetBeforeReadonly);
  values.readonlyNonGet = nonGetReadonly;
  check(
    "b-readonly-zero-write",
    paletteHiddenView &&
      paletteHiddenRegion &&
      deniedView.ok === false &&
      deniedView.kind === "mode-denied" &&
      deniedRegion.ok === false &&
      deniedRegion.kind === "mode-denied" &&
      nonGetReadonly.length === 0,
    JSON.stringify({ paletteHiddenView, paletteHiddenRegion, deniedView, deniedRegion, nonGetReadonly })
  );

  // ── e 调色板只列后端词表 ──
  await page.click('#mode-bar button[data-mode="map-edit"]');
  await page.waitForSelector("#terrain-palette button[data-terrain]", { timeout: 20000 });
  await sleep(150);
  const palette = await page.evaluate(() => window.SimosMap.mapEditDebug().paletteKeys);
  values.paletteKeys = palette;
  const sameSet =
    palette.length === terrainKeys.length && palette.slice().sort().join(",") === terrainKeys.slice().sort().join(",");
  check(
    "e-palette-only-backend-vocab",
    sameSet && palette.indexOf("forest") < 0 && palette.indexOf("tundra") < 0,
    "palette=" + JSON.stringify(palette) + " backend=" + JSON.stringify(terrainKeys)
  );

  // 选地形 ocean（选中态可见）
  await page.click('#terrain-palette button[data-terrain="' + TARGET + '"]');
  await sleep(120);
  const sel = await page.evaluate(() => ({
    terrain: window.SimosMap.mapEditDebug().selectedTerrain,
    active: document.querySelector('#terrain-palette button[data-terrain="' + window.SimosMap.mapEditDebug().selectedTerrain + '"]').classList.contains("active"),
  }));
  check("e-terrain-selected-visible", sel.terrain === TARGET && sel.active === true, JSON.stringify(sel));

  // ── c 拖刷：一条命令 / 载荷 5 格 / head +1 / 节点数不变 / 明细 +1 ──
  await page.evaluate(([a]) => {
    const M = window.SimosMap;
    const w = M.hexToPixel(a.q, a.r, M.BASE_CELL);
    M.setView({ scale: 1, tx: window.innerWidth / 2 - w.x, ty: window.innerHeight / 2 - w.y });
  }, [RUN[0]]);
  await sleep(200);
  const headBefore = await page.evaluate(() => window.SimosApp.getState().revision);
  const tlBefore = tickStats((await api("/api/timeline?branch=main")).body);
  const rebuildBefore = await page.evaluate(() => window.SimosMap.debug().terrainRebuilds);
  const nonGetBeforeDrag = nonGet.length;
  const pts = await page.evaluate((run) => run.map((h) => window.SimosMap.screenPointOf(h.q, h.r)), RUN);

  await page.mouse.move(canvasBox.x + pts[0].x, canvasBox.y + pts[0].y);
  await page.mouse.down();
  await page.mouse.move(canvasBox.x + pts[2].x, canvasBox.y + pts[2].y, { steps: 12 });
  await sleep(120);
  const midBrush = await page.evaluate(() => window.SimosMap.mapEditDebug());
  await page.screenshot({ path: OUT + "/screenshot-map-edit-brush.png" });
  check(
    "h-brush-preview-visible",
    midBrush.painting === true && midBrush.brushHexCount === 3,
    JSON.stringify(midBrush)
  );
  await page.mouse.move(canvasBox.x + pts[4].x, canvasBox.y + pts[4].y, { steps: 12 });
  await page.mouse.up();

  await page.waitForFunction((h) => window.SimosApp.getState().revision !== h, headBefore, { timeout: 20000 });
  await sleep(600);
  const headAfter = await page.evaluate(() => window.SimosApp.getState().revision);
  const tlAfter = tickStats((await api("/api/timeline?branch=main")).body);
  const dragPosts = nonGet.slice(nonGetBeforeDrag);
  values.dragPosts = dragPosts;
  const setTerrainPosts = dragPosts.filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "map.SetTerrain";
  });
  let payloadHexes = [];
  if (setTerrainPosts.length === 1) {
    const body = parse(setTerrainPosts[0].post) || {};
    const p = parse(body.payloadJson) || {};
    payloadHexes = p.hexes || [];
  }
  values.payloadHexes = payloadHexes;
  check(
    "c-one-command-one-head",
    setTerrainPosts.length === 1 && headAfter === headBefore + 1,
    JSON.stringify({ posts: setTerrainPosts.length, headBefore, headAfter })
  );
  check(
    "c-payload-five-hexes",
    payloadHexes.length === 5 &&
      payloadHexes
        .map(hexKey)
        .sort()
        .join(",") ===
        RUN.map(hexKey)
          .sort()
          .join(",") &&
      (parse(setTerrainPosts.length === 1 ? setTerrainPosts[0].post : "{}") || {}).type === "map.SetTerrain",
    "hexes=" + JSON.stringify(payloadHexes)
  );
  check(
    "c-timeline-node-unchanged-detail-plus-one",
    tlAfter.tickCount === tlBefore.tickCount &&
      tlAfter.nodeCount === tlBefore.nodeCount + 1 &&
      tlAfter.headTick === tlBefore.headTick,
    JSON.stringify({ tlBefore, tlAfter })
  );
  await page
    .waitForFunction(
      (expected) => {
        const node = document.querySelector('.tl-node[data-tick="0"]');
        return !!node && node.getAttribute("data-count") === String(expected);
      },
      tlBefore.nodeCount + 1,
      { timeout: 8000 }
    )
    .catch(() => null);
  const detailCount = await page.evaluate(
    (tick) => {
      const node = document.querySelector('.tl-node[data-tick="' + tick + '"]');
      return node ? node.getAttribute("data-count") : null;
    },
    tlBefore.headTick
  );
  check("c-command-detail-plus-one", detailCount === String(tlBefore.nodeCount + 1), "data-count=" + detailCount);

  // ── d 地形真变 + 离屏位图重建（像素） ──
  const afterTerrains = {};
  for (const h of RUN) {
    const r = await api("/api/map/hex?q=" + h.q + "&r=" + h.r);
    afterTerrains[hexKey(h)] = r.body && r.body.terrain;
  }
  values.terrainAfter = afterTerrains;
  const allChanged = RUN.every((h) => afterTerrains[hexKey(h)] === TARGET);
  check("d-terrain-values-changed", allChanged, JSON.stringify({ before: beforeTerrains, after: afterTerrains }));

  const rebuildAfter = await page.evaluate(() => window.SimosMap.debug().terrainRebuilds);
  const px = await page.evaluate((h) => {
    const M = window.SimosMap;
    const p = M.screenPointOf(h.q, h.r);
    const canvas = document.getElementById("canvas");
    const dpr = window.devicePixelRatio || 1;
    const data = canvas.getContext("2d").getImageData(Math.round(p.x * dpr), Math.round(p.y * dpr), 1, 1).data;
    return [data[0], data[1], data[2]];
  }, RUN[0]);
  const targetRgb = [
    parseInt(targetType.color.slice(1, 3), 16),
    parseInt(targetType.color.slice(3, 5), 16),
    parseInt(targetType.color.slice(5, 7), 16),
  ];
  const pxClose = px.every((v, i) => Math.abs(v - targetRgb[i]) <= 24);
  values.pixel = { sampled: px, expected: targetRgb };
  values.terrainRebuilds = { before: rebuildBefore, after: rebuildAfter };
  check(
    "d-offscreen-bitmap-rebuilt",
    rebuildAfter > rebuildBefore && pxClose,
    JSON.stringify({ rebuildBefore, rebuildAfter, px, targetRgb })
  );
  await page.screenshot({ path: OUT + "/screenshot-map-edit-after.png" });

  // ── 区域信息编辑（M8 T8 #8）：多从属显示 + 只改 meta 走 map.UpdateRegion ──
  await page
    .waitForFunction(
      () => document.getElementById("region-info-detail").textContent.indexOf("test_") >= 0,
      null,
      { timeout: 8000 }
    )
    .catch(() => null);
  const regionInfoText = await page.textContent("#region-info-detail");
  const editorHidden = await page.getAttribute("#region-meta-editor", "hidden");
  check(
    "region-info-multi-regions-visible",
    regionInfoText.indexOf("test_annex_target") >= 0 &&
      regionInfoText.indexOf("test_nation") >= 0 &&
      editorHidden === null,
    JSON.stringify({ regionInfoText, editorHidden })
  );
  const nonGetBeforeMeta = nonGet.length;
  const headBeforeMeta = await page.evaluate(() => window.SimosApp.getState().revision);
  await page.selectOption("#region-meta-target", "test_annex_target");
  await sleep(250);
  await page.fill("#region-meta-color", "#123456");
  const metaBefore = await page.evaluate(() => ({
    busy: window.SimosMap.mapEditDebug().mapEditBusy,
    selectedRegion: document.getElementById("region-meta-target").value,
    colorField: document.getElementById("region-meta-color").value,
    submitExists: !!document.getElementById("region-meta-submit"),
    mode: window.SimosApp.getState().mode,
  }));
  await page.click("#region-meta-submit");
  await sleep(900);
  const metaStatus = await page.textContent("#region-info-status");
  const metaPosts = nonGet.slice(nonGetBeforeMeta).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "map.UpdateRegion";
  });
  let metaPayload = null;
  if (metaPosts.length === 1) {
    const body = parse(metaPosts[0].post) || {};
    metaPayload = parse(body.payloadJson);
  }
  const headAfterMeta = await page.evaluate(() => window.SimosApp.getState().revision);
  const regionAfter = (await api("/api/map/region/test_annex_target")).body;
  check(
    "region-meta-update-one-command",
    metaPosts.length === 1 &&
      !!metaPayload &&
      metaPayload.regionId === "test_annex_target" &&
      !!metaPayload.meta &&
      metaPayload.meta.color === "#123456" &&
      metaPayload.hexes === undefined &&
      headAfterMeta === headBeforeMeta + 1 &&
      regionAfter.meta.color === "#123456",
    JSON.stringify({
      metaPosts: metaPosts.length,
      metaPayload,
      headBeforeMeta,
      headAfterMeta,
      color: regionAfter && regionAfter.meta.color,
      metaBefore,
      metaStatus,
    })
  );

  // ── f 负例：词表外 / 图外 ⇒ 显示拒绝原因、不写 ──
  const headBeforeNeg = await page.evaluate(() => window.SimosApp.getState().revision);
  const tlBeforeNeg = tickStats((await api("/api/timeline?branch=main")).body);
  const negBadTerrain = await page.evaluate(() =>
    window.SimosMap.commitPaintForTest([{ q: -18, r: 0 }], "forest")
  );
  await sleep(300);
  const statusBadTerrain = await page.textContent("#brush-status");
  const negOffMap = await page.evaluate(() =>
    window.SimosMap.commitPaintForTest([{ q: 9999, r: 9999 }], "ocean")
  );
  await sleep(300);
  const statusOffMap = await page.textContent("#brush-status");
  const headAfterNeg = await page.evaluate(() => window.SimosApp.getState().revision);
  const tlAfterNeg = tickStats((await api("/api/timeline?branch=main")).body);
  values.negative = { negBadTerrain, statusBadTerrain, negOffMap, statusOffMap };
  check(
    "f-negative-rejected-with-reason",
    negBadTerrain && negBadTerrain.ok === false && negBadTerrain.kind === "rejected" &&
      statusBadTerrain.indexOf("forest") >= 0 &&
      negOffMap && negOffMap.ok === false && negOffMap.kind === "rejected" &&
      statusOffMap.indexOf("9999") >= 0 &&
      headAfterNeg === headBeforeNeg &&
      tlAfterNeg.nodeCount === tlBeforeNeg.nodeCount,
    JSON.stringify({ negBadTerrain, statusBadTerrain, negOffMap, statusOffMap, headBeforeNeg, headAfterNeg })
  );

  // ── g 不退化：右键路线 / 左键取消移动 / 浮层不穿透 / 0 pageerror ──
  await page.click('#mode-bar button[data-mode="unit"]');
  await sleep(200);
  const createRes = await page.evaluate(async ([from, id]) => {
    const r = await window.SimosApp.writeCommand("unit.CreateUnit", {
      id: id,
      name: "T7 E2E",
      position: { q: from.q, r: from.r },
      member: 100,
      equipment: {},
      speed: 2,
      mobilityPerMille: 1000,
    });
    return { ok: r.ok, kind: r.kind || null, message: r.message || null };
  }, [ROUTE_FROM, UNIT_ID]);
  await sleep(500);
  values.createUnit = createRes;
  check("g-create-unit-for-route", createRes.ok === true, JSON.stringify(createRes));

  await page.evaluate((h) => {
    const M = window.SimosMap;
    const w = M.hexToPixel(h.q, h.r, M.BASE_CELL);
    M.setView({ scale: 1, tx: window.innerWidth / 2 - w.x, ty: window.innerHeight / 2 - w.y });
  }, ROUTE_FROM);
  await sleep(300);
  const unitPt = await page.evaluate((h) => window.SimosMap.screenPointOf(h.q, h.r), ROUTE_FROM);
  const toPt = await page.evaluate((h) => window.SimosMap.screenPointOf(h.q, h.r), ROUTE_TO);
  const nonGetBeforeRoute = nonGet.length;
  await page.mouse.click(canvasBox.x + unitPt.x, canvasBox.y + unitPt.y);
  await sleep(200);
  await page.mouse.click(canvasBox.x + toPt.x, canvasBox.y + toPt.y, { button: "right" });
  await sleep(800);
  const routePosts = nonGet.slice(nonGetBeforeRoute).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "unit.PlanRoute";
  });
  const routeCount = await page.evaluate(() => window.SimosMap.debug().routeCount);
  check(
    "g-rightclick-route-still-works",
    routePosts.length === 1 && routeCount >= 1,
    JSON.stringify({ routePosts: routePosts.length, routeCount })
  );

  const nonGetBeforeCancel = nonGet.length;
  await page.mouse.click(canvasBox.x + unitPt.x, canvasBox.y + unitPt.y);
  await sleep(700);
  const cancelPosts = nonGet.slice(nonGetBeforeCancel).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "unit.CancelRoute";
  });
  const routeCountAfterCancel = await page.evaluate(() => window.SimosMap.debug().routeCount);
  check(
    "g-leftclick-cancel-route-still-works",
    cancelPosts.length === 1 && routeCountAfterCancel === 0,
    JSON.stringify({ cancelPosts: cancelPosts.length, routeCountAfterCancel })
  );

  // 浮层不穿透：点顶栏按钮不该命中底图（无新 map/hex GET、无新写）
  const nonGetBeforeOverlay = nonGet.length;
  const hexGetsBeforeOverlay = hexGets.length;
  await page.click("#view-reset");
  await sleep(300);
  check(
    "g-overlay-not-passthrough",
    nonGet.length === nonGetBeforeOverlay && hexGets.length === hexGetsBeforeOverlay,
    JSON.stringify({ nonGetDelta: nonGet.length - nonGetBeforeOverlay, hexGetDelta: hexGets.length - hexGetsBeforeOverlay })
  );

  check("g-no-pageerror", pageErrors.length === 0, JSON.stringify(pageErrors));
  values.nonGet = nonGet.map((r) => ({ method: r.method, path: r.path }));
  console.log("NONGET " + JSON.stringify(values.nonGet));
  console.log("PAGEERRORS " + JSON.stringify(pageErrors));

  values.pageErrors = pageErrors;
  writeJson("result.json", values);
  await browser.close();
  console.log("E2E RESULT: " + (failures.length === 0 ? "ALL PASS" : failures.length + " FAIL " + failures.join(",")));
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((e) => {
  console.log("E2E CRASH: " + e.stack);
  process.exit(2);
});
