// M7d T1 探针 4 —— C：写后各 tick 显示的刷新时延（0/50/150/300/600/1200ms 采样）。
"use strict";
const { chromium } = require("playwright");
const BASE = process.argv[2];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(fn, timeout) {
  const deadline = Date.now() + (timeout || 8000);
  for (;;) { const v = await fn(); if (v) return v; if (Date.now() > deadline) return null; await sleep(30); }
}
async function waitMapReady(page) {
  await page.waitForFunction(() => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(), null, { timeout: 40000 });
}
async function clickHex(page, q, r, button) {
  const canvas = page.locator("#canvas");
  await canvas.scrollIntoViewIfNeeded();
  await sleep(100);
  const pt = await page.evaluate(([qq, rr]) => window.SimosMap.screenPointOf(qq, rr), [q, r]);
  const box = await canvas.boundingBox();
  await page.mouse.click(box.x + pt.x, box.y + pt.y, button ? { button } : undefined);
}
function sample(page) {
  return page.evaluate(() => ({
    t: Date.now(),
    head: JSON.parse(JSON.stringify(window.SimosApp.getState().heads)),
    rev: window.SimosApp.getState().revision,
    shell: document.getElementById("shell-state").textContent,
    meta: document.getElementById("timeline-meta").textContent,
    nodes: document.querySelectorAll('.timeline-line[data-branch="main"] .tl-node').length,
    createDisabled: document.getElementById("timeline-create").disabled,
  }));
}
async function timelineAfter(page, label, ms) {
  const out = [];
  const t0 = Date.now();
  const deadlines = ms;
  let idx = 0;
  while (idx < deadlines.length) {
    const now = Date.now() - t0;
    if (now >= deadlines[idx]) {
      out.push({ at: now, ...(await sample(page)) });
      idx++;
    } else {
      await sleep(10);
    }
  }
  console.log(label + "=" + JSON.stringify(out));
}
(async () => {
  const executablePath = process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
  const browser = await chromium.launch({ headless: true, executablePath });
  const page = await browser.newPage({ viewport: { width: 1280, height: 2000 } });
  page.on("pageerror", (e) => console.log("PAGEERROR " + String(e)));
  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await waitMapReady(page);
  await sleep(300);
  await page.click('.mode-bar button[data-mode="unit"]');
  await sleep(200);
  await clickHex(page, 1, 1);
  await waitFor(() => page.evaluate(() => { const s = window.SimosApp.getState().selection; return s && s.kind === "unit" ? s : null; }), 5000);
  await sleep(500);
  console.log("BEFORE_ROUTE=" + JSON.stringify(await sample(page)));
  await clickHex(page, 1, 3, "right");
  await timelineAfter(page, "AFTER_ROUTE_SAMPLES", [0, 30, 80, 150, 300, 600, 1200, 2500]);
  const createBefore = await page.evaluate(() => document.getElementById("timeline-create").disabled);
  console.log("CREATE_DISABLED_BEFORE_CLICK=" + createBefore);
  if (!createBefore) {
    await page.click("#timeline-create");
    await timelineAfter(page, "AFTER_CREATE_SAMPLES", [0, 30, 80, 150, 300, 600, 1200, 2500]);
  } else {
    console.log("CREATE DISABLED — 尝试强制点一次观察是否静默失败");
    await page.evaluate(() => document.getElementById("timeline-create").click());
    await timelineAfter(page, "AFTER_FORCE_CREATE_SAMPLES", [0, 100, 400, 1000, 2500]);
  }
  await browser.close();
})().catch((e) => { console.log("FATAL " + String((e && e.stack) || e)); process.exit(1); });
