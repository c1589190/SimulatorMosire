// v1v2 e2e —— V1 左栏宽度/值列不再逐字竖排 + V2 区域名只在区域两模式，真 Chromium + 取色/量宽。
// 用法: node e2e.cjs <base-url> <out-dir>
"use strict";

const fs = require("node:fs");
const { chromium } = require(process.env.PW_MODULE || "/home/cna/.npm/_npx/e41f203b7505f1fb/node_modules/playwright");

const BASE = process.argv[2] || "http://127.0.0.1:5861";
const OUT = process.argv[3] || ".";
const CHROME = process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";

const failures = [];
const values = {};
function check(name, ok, detail) {
  console.log("STEP " + name + ": " + (ok ? "PASS" : "FAIL") + (detail === undefined ? "" : " " + JSON.stringify(detail)));
  if (!ok) failures.push(name);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function nearWhite(p) { return p && p.a > 200 && p.r >= 225 && p.g >= 225 && p.b >= 225; }

(async () => {
  const browser = await chromium.launch({ executablePath: CHROME, args: ["--no-sandbox"] });
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  page.on("pageerror", (e) => failures.push("pageerror:" + e.message));

  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await page.waitForFunction(
    () => (document.querySelector("#map-status") || {}).textContent && document.querySelector("#map-status").textContent.includes("已载入"),
    { timeout: 90000 }
  ).catch(() => {});
  await sleep(2500);

  const measure = () => page.evaluate(() => {
    const box = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      return { w: Math.round(r.width), h: Math.round(r.height), display: cs.display, width: cs.width, minWidth: cs.minWidth, maxWidth: cs.maxWidth, flex: cs.flex };
    };
    const kvEl = document.querySelector("#selection-detail");
    const dds = Array.from(document.querySelectorAll("#selection-detail dd")).map((el) => {
      const r = el.getBoundingClientRect();
      const g = getComputedStyle(el);
      const lh = parseFloat(g.lineHeight) || 16;
      return { text: (el.textContent || "").slice(0, 40), w: Math.round(r.width), lines: Math.round(r.height / lh), wordBreak: g.wordBreak };
    });
    return {
      mode: document.body.getAttribute("data-mode"),
      left: box("#left-panel"), right: box("#right-panel"),
      kvCols: kvEl ? getComputedStyle(kvEl).gridTemplateColumns : null,
      ddCount: dds.length, dds: dds,
      minDdWidth: dds.length ? Math.min.apply(null, dds.map((d) => d.w)) : null,
      maxDdLines: dds.length ? Math.max.apply(null, dds.map((d) => d.lines)) : null,
    };
  });

  // ── 选一个真 hex 填出详情（view 模式）──
  let hit = null;
  outer:
  for (let y = 220; y <= 600; y += 40) {
    for (let x = 420; x <= 880; x += 40) {
      await page.mouse.click(x, y);
      await sleep(150);
      const n = await page.evaluate(() => document.querySelectorAll("#selection-detail dd").length);
      if (n > 0) { hit = { x, y, n }; break outer; }
    }
  }
  values.hexHit = hit;
  check("v1-selected-a-hex-with-details", !!hit, hit);

  const view = await measure();
  values.view = view;
  check("v1-left-panel-usable-width", view.left && view.left.w >= 240, view.left && { w: view.left.w, minWidth: view.left.minWidth });
  check("v1-value-column-not-one-char", view.minDdWidth !== null && view.minDdWidth >= 90, { minDdWidth: view.minDdWidth, kvCols: view.kvCols });
  check("v1-no-vertical-one-char-value", view.maxDdLines !== null && view.maxDdLines <= 6, { maxDdLines: view.maxDdLines, dds: view.dds });
  check("v1-dd-wordbreak-normal", view.dds.length > 0 && view.dds.every((d) => d.wordBreak === "normal"), view.dds.map((d) => d.wordBreak));
  await page.screenshot({ path: OUT + "/v1-left-panel-fixed.png" });

  // ── V2：常规模式不画区域名 ──
  // 先放大到区域名阈值之上，确认"即使在阈值之上，常规模式也不画"
  await page.mouse.move(640, 400);
  for (let i = 0; i < 10; i++) {
    await page.mouse.wheel(0, -500);
    await sleep(250);
    const dbg = await page.evaluate(() => window.SimosMap.regionNameDebug());
    if (dbg.scaleOk) break;
  }
  await sleep(500);
  const viewName = await page.evaluate(() => window.SimosMap.regionNameDebug());
  values.viewName = { mode: viewName.mode, visible: viewName.visible, scaleOk: viewName.scaleOk, drawn: viewName.drawn, labels: viewName.labels.length };
  check("v2-view-mode-names-not-visible", viewName.visible === false && viewName.mode === "view", values.viewName);
  check("v2-view-mode-draws-zero", viewName.drawn === 0 && viewName.labels.length === 0, values.viewName);
  check("v2-view-mode-scale-above-threshold", viewName.scaleOk === true, { scaleOk: viewName.scaleOk });
  await page.screenshot({ path: OUT + "/v2-view-no-region-names.png" });

  // ── V2：区域查看模式画区域名 ──
  await page.locator('button[data-mode="region"]').click();
  await sleep(1200);
  const regionName = await page.evaluate(() => window.SimosMap.regionNameDebug());
  values.regionName = { mode: regionName.mode, visible: regionName.visible, scaleOk: regionName.scaleOk, drawn: regionName.drawn, labels: regionName.labels.length };
  check("v2-region-mode-names-visible", regionName.visible === true && regionName.mode === "region", values.regionName);
  check("v2-region-mode-draws-many", regionName.drawn > 0 && regionName.labels.length > 0, values.regionName);
  await page.screenshot({ path: OUT + "/v2-region-names-shown.png" });

  // 取色：区域模式标签锚点邻域有近白像素；切回常规后同一锚点近白像素归零
  let labelHit = null;
  for (const label of regionName.labels) {
    if (label.screenX < 330 || label.screenX > 915 || label.screenY < 100 || label.screenY > 690) continue;
    let white = 0;
    for (let dy = -5; dy <= 5; dy++) {
      for (let dx = -5; dx <= 5; dx++) {
        const p = await page.evaluate(({ x, y }) => window.SimosMap.pixelAt(x, y), { x: label.screenX + dx, y: label.screenY + dy });
        if (nearWhite(p)) white++;
      }
    }
    if (white > 0 && (!labelHit || white > labelHit.white)) {
      labelHit = { text: label.text, screenX: Math.round(label.screenX), screenY: Math.round(label.screenY), white };
    }
  }
  values.labelHit = labelHit;
  check("v2-region-name-white-pixels", !!labelHit, labelHit);

  if (labelHit) {
    await page.locator('button[data-mode="view"]').click();
    await sleep(900);
    let viewWhite = 0;
    for (let dy = -3; dy <= 3; dy++) {
      for (let dx = -3; dx <= 3; dx++) {
        const p = await page.evaluate(({ x, y }) => window.SimosMap.pixelAt(x, y), { x: labelHit.screenX + dx, y: labelHit.screenY + dy });
        if (nearWhite(p)) viewWhite++;
      }
    }
    values.viewAtLabelAnchor = { white: viewWhite };
    check("v2-view-at-label-anchor-has-no-name-pixels", viewWhite === 0, { regionWhite: labelHit.white, viewWhite: viewWhite });
    const viewName2 = await page.evaluate(() => window.SimosMap.regionNameDebug());
    check("v2-back-to-view-names-gone", viewName2.drawn === 0 && viewName2.labels.length === 0, { drawn: viewName2.drawn, labels: viewName2.labels.length });
  }

  // ── 区域编辑模式也显示（另一区域模式）──
  await page.locator('button[data-mode="region-edit"]').click();
  await sleep(900);
  const reName = await page.evaluate(() => window.SimosMap.regionNameDebug());
  values.regionEditName = { mode: reName.mode, visible: reName.visible, drawn: reName.drawn };
  check("v2-region-edit-mode-names-visible", reName.visible === true && reName.drawn > 0, values.regionEditName);

  // V1：区域编辑模式下左栏仍是可用宽度（用户截图就是这个模式）
  const reLayout = await measure();
  values.regionEditLayout = reLayout;
  check("v1-region-edit-left-panel-usable-width", reLayout.left && reLayout.left.w >= 240, reLayout.left && { w: reLayout.left.w });
  await page.screenshot({ path: OUT + "/v1-region-edit-left-panel.png" });

  fs.writeFileSync(OUT + "/e2e-values.json", JSON.stringify(values, null, 2));
  fs.writeFileSync(OUT + "/e2e-failures.json", JSON.stringify(failures, null, 2));
  console.log("E2E " + (failures.length ? "FAIL" : "PASS") + " failures=" + JSON.stringify(failures));
  await browser.close();
  process.exit(failures.length ? 1 : 0);
})().catch((e) => { console.error("E2E ERROR", e); process.exit(2); });
