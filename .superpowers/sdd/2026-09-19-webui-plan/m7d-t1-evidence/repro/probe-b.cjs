// M7d T1 探针 2 —— 隔离 B：用超高视口让时间轴可见后，从圆环元素上真按下拖动。
"use strict";
const { chromium } = require("playwright");
const BASE = process.argv[2];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(fn, timeout) {
  const deadline = Date.now() + (timeout || 8000);
  for (;;) { const v = await fn(); if (v) return v; if (Date.now() > deadline) return null; await sleep(80); }
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
(async () => {
  const executablePath = process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
  const browser = await chromium.launch({ headless: true, executablePath });
  const page = await browser.newPage({ viewport: { width: 1280, height: 2200 } });
  page.on("pageerror", (e) => console.log("PAGEERROR " + String(e)));
  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await waitMapReady(page);
  await sleep(300);
  await page.click('.mode-bar button[data-mode="unit"]');
  await sleep(200);
  await clickHex(page, 1, 1);
  await waitFor(() => page.evaluate(() => { const s = window.SimosApp.getState().selection; return s && s.kind === "unit" ? s : null; }), 5000);
  await sleep(500);
  // 下一条路线（制造更多节点）
  await clickHex(page, 1, 3, "right");
  await waitFor(() => page.evaluate(() => document.getElementById("unit-edit-status").textContent.indexOf("已下路线") >= 0), 8000);
  await sleep(600);
  // 创建两个节点
  for (let i = 0; i < 2; i++) {
    const rb = await page.evaluate(() => window.SimosApp.getState().revision);
    await page.click("#timeline-create");
    await waitFor(() => page.evaluate((x) => window.SimosApp.getState().revision > x ? true : null, rb), 8000);
    await sleep(500);
  }
  const knob = await page.evaluate(() => {
    const k = document.querySelector(".tl-knob");
    if (!k) return { exists: false };
    const r = k.getBoundingClientRect();
    const cs = getComputedStyle(k);
    const el = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
    return { exists: true, hidden: k.hidden, x: r.x, y: r.y, w: r.width, h: r.height,
      pe: cs.pointerEvents, z: cs.zIndex, at: el ? el.className : "null",
      rev: k.getAttribute("data-revision") };
  });
  console.log("KNOB=" + JSON.stringify(knob));
  // 先把圆环滚进视口
  await page.locator(".tl-knob").scrollIntoViewIfNeeded();
  await sleep(200);
  const rev0 = await page.evaluate(() => window.SimosApp.getState().revision);
  const kb = await page.locator(".tl-knob").boundingBox();
  console.log("KNOB_AFTER_SCROLL=" + JSON.stringify(kb));
  const cx = kb.x + kb.width / 2, cy = kb.y + kb.height / 2;
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await sleep(100);
  await page.mouse.move(cx - 120, cy, { steps: 6 });
  await sleep(100);
  await page.mouse.move(cx - 240, cy, { steps: 6 });
  await sleep(150);
  await page.mouse.up();
  await sleep(400);
  const rev1 = await page.evaluate(() => window.SimosApp.getState().revision);
  console.log("KNOB_DRAG=" + JSON.stringify({ rev0, rev1, changed: rev0 !== rev1 }));

  // 对照：从行上按下
  const lb = await page.locator('.timeline-line[data-branch="main"]').boundingBox();
  const r2 = await page.evaluate(() => window.SimosApp.getState().revision);
  await page.mouse.move(lb.x + lb.width - 5, lb.y + lb.height / 2);
  await page.mouse.down(); await sleep(100);
  await page.mouse.move(lb.x + 90, lb.y + lb.height / 2, { steps: 8 }); await sleep(120);
  await page.mouse.up(); await sleep(400);
  const r3 = await page.evaluate(() => window.SimosApp.getState().revision);
  console.log("ROW_DRAG=" + JSON.stringify({ r2, r3, changed: r2 !== r3 }));
  await browser.close();
})().catch((e) => { console.log("FATAL " + String((e && e.stack) || e)); process.exit(1); });
