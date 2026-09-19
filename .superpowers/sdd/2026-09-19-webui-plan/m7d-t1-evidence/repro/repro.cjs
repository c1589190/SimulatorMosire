// M7d T1 复现探针 —— 逐条定位 A（布局）/ B（圆环拖不动）/ C（tick 不刷新）/ D（语义提示）。
// 用法: NODE_PATH=<playwright> node repro.cjs <base-url> <out-dir>
"use strict";

const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const OUT = process.argv[3];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitFor(fn, timeout) {
  const deadline = Date.now() + (timeout || 8000);
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > deadline) return null;
    await sleep(80);
  }
}

async function api(path, opts) {
  const response = await fetch(BASE + path, opts);
  const text = await response.text();
  let body = null;
  try { body = JSON.parse(text); } catch (e) { body = null; }
  return { status: response.status, body, text };
}

async function waitMapReady(page) {
  await page.waitForFunction(
    () => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(),
    null, { timeout: 40000 }
  );
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
  fs.mkdirSync(OUT, { recursive: true });
  const executablePath =
    process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
  const browser = await chromium.launch({ headless: true, executablePath });
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  page.on("pageerror", (e) => console.log("PAGEERROR " + String(e)));

  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await waitMapReady(page);
  await page.waitForFunction(
    () => document.getElementById("map-status").textContent.indexOf("已载入") >= 0,
    null, { timeout: 30000 }
  );
  await sleep(200);

  // 单位模式 + 选中 u-1（左栏变长）
  await page.click('.mode-bar button[data-mode="unit"]');
  await sleep(200);
  await clickHex(page, 1, 1);
  await waitFor(() => page.evaluate(() => {
    const s = window.SimosApp.getState().selection;
    return s && s.kind === "unit" ? s : null;
  }), 5000);
  await sleep(600);

  // ── A. 布局 ──
  const layout = await page.evaluate(() => {
    const bar = document.getElementById("timeline-bar");
    const r = bar.getBoundingClientRect();
    const left = document.getElementById("left-panel").getBoundingClientRect();
    return {
      viewportH: window.innerHeight,
      scrollHeight: document.documentElement.scrollHeight,
      scrollY: window.scrollY,
      barTop: r.top, barBottom: r.bottom, barH: r.height,
      leftH: left.height,
      leftScrollH: document.getElementById("left-panel").scrollHeight,
    };
  });
  console.log("A_LAYOUT=" + JSON.stringify(layout));
  await page.screenshot({ path: OUT + "/repro-A-layout.png", fullPage: false });

  // ── D. 右键下路线后的提示 ──
  await clickHex(page, 1, 3, "right");
  await waitFor(() => page.evaluate(() =>
    document.getElementById("unit-edit-status").textContent.indexOf("已下路线") >= 0), 8000);
  const hint = await page.evaluate(() => document.getElementById("unit-edit-status").textContent);
  console.log("D_HINT=" + JSON.stringify(hint));
  const metaAfterRoute = await page.evaluate(() => document.getElementById("timeline-meta").textContent);
  console.log("D_TIMELINE_META=" + JSON.stringify(metaAfterRoute));

  // ── C. tick 显示是否随"创建节点"刷新 ──
  async function snapshot(label) {
    const st = (await api("/api/state")).body;
    const tl = (await api("/api/timeline?branch=main")).body;
    const dom = await page.evaluate(() => ({
      shellState: document.getElementById("shell-state").textContent,
      timelineMeta: document.getElementById("timeline-meta").textContent,
      leftStatus: document.getElementById("left-status").textContent,
      detail: document.getElementById("selection-detail").textContent,
      state: JSON.parse(JSON.stringify(window.SimosApp.getState())),
      nodes: document.querySelectorAll('.timeline-line[data-branch="main"] .tl-node').length,
    }));
    const revs = (tl.nodes || []).map((n) => n.revision + ":" + n.tick);
    return {
      label,
      apiHead: st.heads.main,
      apiMetaTick: st.meta && st.meta.timestamp ? st.meta.timestamp.tick : null,
      apiMetaRev: st.meta ? st.meta.revision : null,
      apiRevs: revs,
      nodeCount: dom.nodes,
      stateRevision: dom.state.revision,
      shellState: dom.shellState,
      timelineMeta: dom.timelineMeta,
      leftStatus: dom.leftStatus,
      detail: dom.detail,
    };
  }

  const c0 = await snapshot("before-create");
  console.log("C_BEFORE=" + JSON.stringify(c0));

  for (let i = 1; i <= 2; i++) {
    const revBefore = await page.evaluate(() => window.SimosApp.getState().revision);
    await page.click("#timeline-create");
    const revAfter = await waitFor(() => page.evaluate((rb) =>
      window.SimosApp.getState().revision > rb ? window.SimosApp.getState().revision : null, revBefore), 8000);
    await sleep(900);
    const s = await snapshot("after-create-" + i);
    console.log("C_AFTER" + i + "=" + JSON.stringify(s));
    await page.screenshot({ path: OUT + "/repro-C-create-" + i + ".png", fullPage: false });
  }

  // ── B. 圆环拖动 ──
  const knobBox = await page.evaluate(() => {
    const k = document.querySelector(".tl-knob");
    if (!k) return { exists: false };
    const r = k.getBoundingClientRect();
    const cs = getComputedStyle(k);
    return {
      exists: true, hidden: k.hidden, x: r.x, y: r.y, w: r.width, h: r.height,
      pointerEvents: cs.pointerEvents, zIndex: cs.zIndex,
      dataBranch: k.getAttribute("data-branch"), dataRevision: k.getAttribute("data-revision"),
      elementAtCenter: (function () {
        const el = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
        return el ? el.className + "|" + el.tagName : "null";
      })(),
    };
  });
  console.log("B_KNOB=" + JSON.stringify(knobBox));

  const revB0 = await page.evaluate(() => window.SimosApp.getState().revision);
  if (knobBox.exists && knobBox.w > 0) {
    const cx = knobBox.x + knobBox.w / 2;
    const cy = knobBox.y + knobBox.h / 2;
    await page.mouse.move(cx, cy);
    await page.mouse.down();
    await sleep(80);
    await page.mouse.move(cx - 130, cy, { steps: 8 });
    await sleep(80);
    await page.mouse.move(cx - 260, cy, { steps: 8 });
    await sleep(120);
    await page.mouse.up();
    await sleep(500);
  }
  const revB1 = await page.evaluate(() => window.SimosApp.getState().revision);
  console.log("B_DRAG=" + JSON.stringify({ revB0, revB1, changed: revB0 !== revB1 }));
  await page.screenshot({ path: OUT + "/repro-B-knob.png", fullPage: false });

  // ── B2. 从"行"上按下（对照）：验证行能拖动 ──
  const lineBox = await page.evaluate(() => {
    const l = document.querySelector('.timeline-line[data-branch="main"]');
    const r = l.getBoundingClientRect();
    return { x: r.x, y: r.y, w: r.width, h: r.height };
  });
  const revL0 = await page.evaluate(() => window.SimosApp.getState().revision);
  await page.mouse.move(lineBox.x + lineBox.w - 10, lineBox.y + lineBox.h / 2);
  await page.mouse.down();
  await sleep(80);
  await page.mouse.move(lineBox.x + 100, lineBox.y + lineBox.h / 2, { steps: 10 });
  await sleep(120);
  await page.mouse.up();
  await sleep(400);
  const revL1 = await page.evaluate(() => window.SimosApp.getState().revision);
  console.log("B2_ROW_DRAG=" + JSON.stringify({ revL0, revL1, changed: revL0 !== revL1 }));

  fs.writeFileSync(OUT + "/repro-values.json", JSON.stringify({
    layout, hint, c0, knobBox,
  }, null, 2));
  await browser.close();
  process.exit(0);
})().catch((e) => {
  console.log("REPRO FATAL " + String((e && e.stack) || e));
  process.exit(1);
});
