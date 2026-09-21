// wsf2 e2e —— U1 地形压暗 / U2 区域名 / U3 选择粒度 / U5 三栏布局，真 Chromium + 取色。
//
// 本机现状：playwright 1.63（npx 缓存）+ Chromium revision 1234 通过 executablePath 驱动（本机实测可用）。
// 判据出处：docs/superpowers/specs/2026-09-22-webui-fix2-design.md。
// 用法: node e2e.cjs <base-url> <out-dir>
"use strict";

const fs = require("node:fs");
const { chromium } = require(process.env.PW_MODULE || "/home/cna/.npm/_npx/e41f203b7505f1fb/node_modules/playwright");

const BASE = process.argv[2] || "http://127.0.0.1:5861";
const OUT = process.argv[3] || ".";
const CHROME =
  process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";

const failures = [];
const values = {};

function check(name, ok, detail) {
  console.log("STEP " + name + ": " + (ok ? "PASS" : "FAIL") + (detail === undefined ? "" : " " + JSON.stringify(detail)));
  if (!ok) failures.push(name);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function nearWhite(p) {
  return p && p.a > 200 && p.r >= 225 && p.g >= 225 && p.b >= 225;
}
function luma(p) {
  return p ? 0.2126 * p.r + 0.7152 * p.g + 0.0722 * p.b : null;
}
function dist(a, b) {
  return Math.sqrt((a.r - b.r) ** 2 + (a.g - b.g) ** 2 + (a.b - b.b) ** 2);
}
function hexToRgb(hex) {
  return { r: parseInt(hex.slice(1, 3), 16), g: parseInt(hex.slice(3, 5), 16), b: parseInt(hex.slice(5, 7), 16) };
}

(async () => {
  const browser = await chromium.launch({ executablePath: CHROME, args: ["--no-sandbox"] });
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  page.on("pageerror", (e) => failures.push("pageerror:" + e.message));

  const nonGet = [];
  page.on("request", (req) => {
    if (req.method() !== "GET") nonGet.push(req.method() + " " + req.url());
  });

  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await page.waitForFunction(
    () => (document.querySelector("#map-status") || {}).textContent && document.querySelector("#map-status").textContent.includes("已载入"),
    { timeout: 60000 }
  );
  await sleep(1200);

  // ── U5：view 模式下右栏必须整体隐藏（不是留一个空卡片），左栏宽度按内容 ──
  const viewLayout = await page.evaluate(() => {
    const rp = document.querySelector("#right-panel");
    const lp = document.querySelector("#left-panel");
    const box = (el) => {
      const r = el.getBoundingClientRect();
      return { w: Math.round(r.width), h: Math.round(r.height), display: getComputedStyle(el).display, hidden: el.hidden, offsetParent: el.offsetParent === null ? null : true };
    };
    return { mode: document.body.getAttribute("data-mode"), right: box(rp), left: box(lp), rightModes: rp.getAttribute("data-modes") };
  });
  values.viewLayout = viewLayout;
  check("u5-view-right-panel-hidden", viewLayout.right.hidden === true && viewLayout.right.display === "none", viewLayout.right);
  check("u5-view-right-panel-no-empty-card", viewLayout.right.offsetParent === null && viewLayout.right.w === 0, { offsetParent: viewLayout.right.offsetParent, w: viewLayout.right.w });
  check("u5-left-panel-content-sized", viewLayout.left.w > 0 && viewLayout.left.w < 300, { leftWidth: viewLayout.left.w });

  // ── U1：同一世界点，view vs region 取色（region 必须更暗）──
  // 选一个"地图内、非面板区"的点，view 模式下不透明（a>200 ⇒ 有地形）。
  let pt = null;
  for (let y = 180; y <= 620 && !pt; y += 40) {
    for (let x = 420; x <= 860 && !pt; x += 40) {
      const p = await page.evaluate(({ x, y }) => window.SimosMap.pixelAt(x, y), { x, y });
      if (p && p.a > 200) pt = { x, y, p };
    }
  }
  check("u1-found-opaque-terrain-pixel", !!pt, pt && { x: pt.x, y: pt.y, p: pt.p });
  if (pt) {
    await page.click('button[data-mode="region"]');
    await sleep(900);
    const regionPix = await page.evaluate(({ x, y }) => window.SimosMap.pixelAt(x, y), pt);
    const dimDebug = await page.evaluate(() => window.SimosMap.regionViewDebug());
    const dimInfo = { terrainDimAlpha: dimDebug.terrainDimAlpha, dimPasses: dimDebug.dimPasses };
    await page.click('button[data-mode="view"]');
    await sleep(600);
    const viewPix = await page.evaluate(({ x, y }) => window.SimosMap.pixelAt(x, y), pt);
    values.u1 = { point: pt, viewPixel: viewPix, regionPixel: regionPix, viewLuma: luma(viewPix), regionLuma: luma(regionPix), debug: dimInfo };
    check(
      "u1-region-mode-terrain-is-darker-pixel",
      regionPix && viewPix && regionPix.r <= viewPix.r && regionPix.g <= viewPix.g && regionPix.b <= viewPix.b && luma(regionPix) < luma(viewPix),
      { viewLuma: luma(viewPix), regionLuma: luma(regionPix) }
    );
    check("u1-dim-layer-actually-painted", dimInfo.dimPasses > 0 && dimInfo.terrainDimAlpha > 0, dimInfo);
  }

  // ── 放大到区域名阈值以上 ──
  await page.click('button[data-mode="region"]').catch(() => {});
  await page.mouse.move(640, 400);
  for (let i = 0; i < 6; i++) {
    await page.mouse.wheel(0, -500);
    await sleep(250);
    const dbg = await page.evaluate(() => window.SimosMap.regionNameDebug());
    if (dbg.scaleOk) break;
  }
  await sleep(500);

  // ── U2：区域名真的画了（条数 > 0，且标签锚点附近有近白像素）──
  const nameDebug = await page.evaluate(() => window.SimosMap.regionNameDebug());
  values.u2 = { enabled: nameDebug.enabled, scaleOk: nameDebug.scaleOk, drawn: nameDebug.drawn, labelCount: nameDebug.labels.length };
  check("u2-region-names-enabled-and-drawn", nameDebug.enabled === true && nameDebug.scaleOk === true && nameDebug.drawn > 0 && nameDebug.labels.length > 0, values.u2);

  // 找一个在视口内、不被面板遮挡的标签，5x5 邻域扫近白
  let labelHit = null;
  for (const label of nameDebug.labels) {
    if (label.screenX < 420 || label.screenX > 850 || label.screenY < 120 || label.screenY > 620) continue;
    let white = 0;
    for (let dy = -3; dy <= 3; dy++) {
      for (let dx = -3; dx <= 3; dx++) {
        const p = await page.evaluate(({ x, y }) => window.SimosMap.pixelAt(x, y), { x: label.screenX + dx, y: label.screenY + dy });
        if (nearWhite(p)) white++;
      }
    }
    if (white > 0) { labelHit = { text: label.text, screenX: Math.round(label.screenX), screenY: Math.round(label.screenY), whitePixels: white }; break; }
  }
  values.u2.labelHit = labelHit;
  check("u2-region-name-renders-near-white-pixels", !!labelHit, labelHit);

  // ── U3：tag 全选（group，0.42 等亮）vs 单区域（single，0.62 更亮 + 只压同 tag）──
  const overview = await page.evaluate(async () => (await fetch("/api/map/overview")).json());
  const nationRegions = overview.regions.filter((r) => r.meta && r.meta.tag === "Nation");
  const otherTagRegions = overview.regions.filter((r) => !(r.meta && r.meta.tag === "Nation"));
  values.u3 = { regionCount: overview.regions.length, nationCount: nationRegions.length, otherTagCount: otherTagRegions.length };

  // 点 tag
  await page.locator('.region-tag[data-tag="Nation"]').first().scrollIntoViewIfNeeded();
  await page.locator('.region-tag[data-tag="Nation"]').first().click();
  await sleep(1500);
  const groupDebug = await page.evaluate(() => window.SimosMap.regionViewDebug());
  check("u3-tag-click-is-group-and-all-equal", groupDebug.highlightKind === "group" && groupDebug.highlightRegions.length === nationRegions.length, {
    kind: groupDebug.highlightKind, n: groupDebug.highlightRegions.length, expected: nationRegions.length,
  });

  // 选一个大区域做单区域对比（质心大概率在区域内 ⇒ 像素对比有意义）。
  // ★ 必须选**标签在视口内、且该像素确实不透明**的区域：否则 pixelAt 落在画布外（a=0），取色对比无从谈起。
  const freshLabels = (await page.evaluate(() => window.SimosMap.regionNameDebug())).labels;
  const byName = new Map(freshLabels.map((l) => [l.text, l]));
  const ranked = nationRegions.slice().sort((a, b) => b.hexCount - a.hexCount);
  let target = null;
  let probeHex = null;
  let probe = null;
  let groupPixel = null;
  let groupAlphaAt = null;
  let groupReady = false;
  for (const region of ranked) {
    const lp = byName.get(region.name);
    if (!lp) continue;
    const candidate = { x: Math.round(lp.screenX), y: Math.round(lp.screenY) + 18 };
    if (candidate.x < 260 || candidate.x > 1020 || candidate.y < 80 || candidate.y > 690) continue;
    const pix = await page.evaluate(({ x, y }) => window.SimosMap.pixelAt(x, y), candidate);
    if (!pix || pix.a < 200) continue;
    const detail = await page.evaluate(async (id) => (await fetch("/api/map/region/" + encodeURIComponent(id))).json(), region.id);
    const hex = detail.hexes[0];
    // group 全选要等 N 个区域 hex 拉完（大世界数秒），等该 hex 真变成 0.42 再取色。
    let at = null;
    for (let i = 0; i < 60; i++) {
      await sleep(500);
      at = await page.evaluate(({ q, r }) => window.SimosMap.regionHighlightAt(q, r), hex);
      if (at && at.alpha === 0.42) { groupReady = true; break; }
    }
    if (!groupReady) continue;
    target = region;
    probeHex = hex;
    probe = candidate;
    groupAlphaAt = at;
    groupPixel = pix;
    break;
  }
  values.u3 = Object.assign(values.u3 || {}, {
    target: target && { id: target.id, name: target.name, hexCount: target.hexCount, probeHex, probe },
    group: { kind: groupDebug.highlightKind, alphaAt: groupAlphaAt, pixel: groupPixel },
  });
  check("u3-group-reload-completed", groupReady && !!target, { groupReady, target: target && target.id });

  let singleReady = false;
  let singleDebug = { highlightKind: null, highlightRegions: [], fadedRegions: [] };
  let singleAlphaAt = null;
  let singlePixel = null;
  if (target) {
    await page.locator('.region-item[data-region-id="' + target.id + '"]').first().scrollIntoViewIfNeeded();
    await page.locator('.region-item[data-region-id="' + target.id + '"]').first().click();
    // ★ 单区域选中会重拉全部区域 hex（N+1，98 个）⇒ 必须等它真的换完再读，否则读到的是上一态的投影。
    for (let i = 0; i < 60; i++) {
      await sleep(500);
      const at = await page.evaluate(({ q, r }) => window.SimosMap.regionHighlightAt(q, r), probeHex);
      if (at && at.alpha === 0.62) { singleReady = true; break; }
    }
    await sleep(400);
    singleDebug = await page.evaluate(() => window.SimosMap.regionViewDebug());
    singleAlphaAt = await page.evaluate(({ q, r }) => window.SimosMap.regionHighlightAt(q, r), probeHex);
    singlePixel = await page.evaluate(({ x, y }) => window.SimosMap.pixelAt(x, y), probe);
  }

  const regionRaw = target ? hexToRgb(target.meta.color) : null;
  values.u3 = Object.assign(values.u3 || {}, {
    single: { kind: singleDebug.highlightKind, faded: singleDebug.fadedRegions.length, alphaAt: singleAlphaAt, pixel: singlePixel },
    singleReady,
  });
  check("u3-single-reload-completed", singleReady, { singleReady });
  check("u3-single-click-is-single", singleDebug.highlightKind === "single" && singleDebug.highlightRegions.length === 1, {
    kind: singleDebug.highlightKind, n: singleDebug.highlightRegions.length,
  });
  check("u3-single-focus-alpha-is-higher-value", groupAlphaAt && singleAlphaAt && groupAlphaAt.alpha === 0.42 && singleAlphaAt.alpha === 0.62, {
    group: groupAlphaAt && groupAlphaAt.alpha, single: singleAlphaAt && singleAlphaAt.alpha,
  });
  check(
    "u3-single-fades-only-same-tag",
    !!target &&
      singleDebug.fadedRegions.indexOf(target.id) < 0 &&
      otherTagRegions.every((r) => singleDebug.fadedRegions.indexOf(r.id) < 0) &&
      singleDebug.fadedRegions.length > 0 &&
      singleDebug.fadedRegions.length <= nationRegions.length - 1,
    { fadedCount: singleDebug.fadedRegions.length, nationCount: nationRegions.length, otherTagIds: otherTagRegions.map((r) => r.id) }
  );
  check(
    "u3-single-region-pixel-is-more-saturated-than-tag",
    !!(target && groupPixel && singlePixel) && dist(singlePixel, regionRaw) < dist(groupPixel, regionRaw),
    { regionColor: target && target.meta.color, groupDist: groupPixel ? Math.round(dist(groupPixel, regionRaw)) : null, singleDist: singlePixel ? Math.round(dist(singlePixel, regionRaw)) : null, groupPixel, singlePixel }
  );

  // ── U5：region 模式右栏可见 ──
  const regionLayout = await page.evaluate(() => {
    const rp = document.querySelector("#right-panel");
    return { mode: document.body.getAttribute("data-mode"), display: getComputedStyle(rp).display, hidden: rp.hidden };
  });
  values.u5Region = regionLayout;
  check("u5-region-right-panel-visible", regionLayout.mode === "region" && regionLayout.hidden === false && regionLayout.display !== "none", regionLayout);

  values.nonGet = nonGet;
  check("e2e-only-get-requests", nonGet.length === 0, nonGet);

  fs.writeFileSync(OUT + "/e2e-values.json", JSON.stringify(values, null, 2));
  await page.screenshot({ path: OUT + "/u3-single-region.png" });
  await browser.close();

  console.log("FAILURES=" + failures.length + " " + JSON.stringify(failures));
  fs.writeFileSync(OUT + "/e2e-failures.json", JSON.stringify(failures, null, 2));
  process.exit(failures.length ? 1 : 0);
})().catch((e) => {
  console.error("E2E ERROR", e.stack || e.message);
  process.exit(2);
});
