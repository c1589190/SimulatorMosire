// M7d T1 e2e —— 用户实测四缺陷的回归断言（A 布局 / B 圆环可拖 / C tick 刷新 / D 语义提示）。
// 用法: NODE_PATH=<playwright> node e2e.cjs <base-url> <store-dir> <out-dir>
// ★ 退出码：0 = 全部必测步骤 PASS；1 = 有 FAIL（变异轮据此观察红点）。
"use strict";

const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const STORE = process.argv[3];
const OUT = process.argv[4];
const VIEWPORT = { width: 1280, height: 800 };

const failures = [];
const values = {};

function check(name, ok, detail) {
  console.log("STEP " + name + ": " + (ok ? "PASS" : "FAIL") + (detail ? " " + detail : ""));
  if (!ok) failures.push(name);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitFor(fn, timeout) {
  const deadline = Date.now() + (timeout || 8000);
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > deadline) return null;
    await sleep(50);
  }
}

async function api(path, opts) {
  const response = await fetch(BASE + path, opts);
  const text = await response.text();
  let body = null;
  try { body = JSON.parse(text); } catch (e) { body = null; }
  return { status: response.status, body, text };
}

function writeJson(name, value) {
  fs.writeFileSync(OUT + "/" + name, JSON.stringify(value, null, 2));
}

async function waitMapReady(page) {
  await page.waitForFunction(
    () => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(),
    null, { timeout: 40000 }
  );
}

async function openWorkbench(page) {
  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await waitMapReady(page);
  await page.waitForFunction(
    () => document.getElementById("map-status").textContent.indexOf("已载入") >= 0,
    null, { timeout: 30000 }
  );
  await sleep(200);
}

async function clickHex(page, q, r, button) {
  const canvas = page.locator("#canvas");
  await canvas.scrollIntoViewIfNeeded();
  await sleep(100);
  const pt = await page.evaluate(([qq, rr]) => window.SimosMap.screenPointOf(qq, rr), [q, r]);
  const box = await canvas.boundingBox();
  await page.mouse.click(box.x + pt.x, box.y + pt.y, button ? { button } : undefined);
}

function stateRevision(page) {
  return page.evaluate(() => window.SimosApp.getState().revision);
}

async function selectUnit(page, id) {
  await clickHex(page, 1, 1);
  const sel = await waitFor(() => page.evaluate((want) => {
    const s = window.SimosApp.getState().selection;
    return s && s.kind === "unit" && s.id === want ? s : null;
  }, id), 5000);
  return sel;
}

function displaySnapshot(page) {
  return page.evaluate(() => ({
    revision: window.SimosApp.getState().revision,
    head: window.SimosApp.getState().heads.main,
    shell: document.getElementById("shell-state").textContent,
    meta: document.getElementById("timeline-meta").textContent,
    createDisabled: document.getElementById("timeline-create").disabled,
    nodes: document.querySelectorAll('.timeline-line[data-branch="main"] .tl-node').length,
    nodeLabels: Array.prototype.map.call(
      document.querySelectorAll('.timeline-line[data-branch="main"] .tl-node'),
      (n) => n.textContent
    ),
  }));
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const executablePath =
    process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
  const browser = await chromium.launch({ headless: true, executablePath });
  const page = await browser.newPage({ viewport: VIEWPORT });

  const pageErrors = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));

  // ── 准备：单位模式 + 选中 u-1，右键下路线（左栏拉到最长）──
  await openWorkbench(page);
  await page.click('.mode-bar button[data-mode="unit"]');
  await sleep(200);
  const sel = await selectUnit(page, "u-1");
  check("prep-unit-selected", !!sel && sel.id === "u-1", JSON.stringify(sel));
  if (!sel) throw new Error("点选 u-1 失败，后续断言无意义");

  await clickHex(page, 1, 3, "right");
  await waitFor(() => page.evaluate(() =>
    document.getElementById("unit-edit-status").textContent.indexOf("已下路线") >= 0), 8000);
  await sleep(300);

  // ══ A. 视口高度布局：左栏再长，底栏时间轴始终在视口内 ══
  const layout = await page.evaluate(() => {
    const bar = document.getElementById("timeline-bar").getBoundingClientRect();
    const left = document.getElementById("left-panel");
    const detail = document.getElementById("selection-detail");
    return {
      viewportH: window.innerHeight,
      docScrollHeight: document.documentElement.scrollHeight,
      barTop: bar.top, barBottom: bar.bottom,
      leftClientH: left.clientHeight, leftScrollH: left.scrollHeight,
      detailRows: detail.querySelectorAll("dt").length,
    };
  });
  check(
    "a-timeline-in-viewport",
    layout.barTop >= 0 && layout.barBottom <= layout.viewportH + 0.5,
    JSON.stringify(layout)
  );
  check("a-no-page-scroll", layout.docScrollHeight <= layout.viewportH + 1, JSON.stringify(layout));
  check(
    "a-left-column-scrolls",
    layout.leftScrollH > layout.leftClientH && layout.detailRows >= 10,
    JSON.stringify(layout)
  );
  values.a = layout;
  await page.screenshot({ path: OUT + "/screenshot-A-longcontent-timeline-visible.png" });

  // ══ D. 语义可理解性：右键成功提示 + 创建节点文案 + 节点短命令名 ══
  const hint = await page.evaluate(() => document.getElementById("unit-edit-status").textContent);
  check(
    "d-route-hint",
    hint.indexOf("创建节点") >= 0 && hint.indexOf("推进时间") >= 0,
    JSON.stringify(hint)
  );
  const createTitle = await page.evaluate(() =>
    document.getElementById("timeline-create").getAttribute("title"));
  check(
    "d-create-title",
    !!createTitle && createTitle.indexOf("推进时间") >= 0,
    JSON.stringify(createTitle)
  );
  values.d = { hint, createTitle };
  await page.locator("#unit-edit-status").scrollIntoViewIfNeeded();
  await sleep(150);
  await page.screenshot({ path: OUT + "/screenshot-D-route-hint.png" });

  // ══ C. tick 显示随"创建节点"刷新（顶栏 / 底栏 / 节点数），且创建按钮不静默禁用 ══
  const cBefore = await displaySnapshot(page);
  const cRounds = [];
  for (let i = 1; i <= 2; i++) {
    const revBefore = await stateRevision(page);
    const apiBefore = (await api("/api/state")).body;
    await page.click("#timeline-create");
    const revAfter = await waitFor(
      () => stateRevision(page).then((r) => (r > revBefore ? r : null)), 8000
    );
    // 新 head 的 tick 必须在本轮内出现在顶栏、底栏，且按钮回到可用（不静默禁用）
    const shell = await waitFor(() => page.evaluate(() => {
      const t = document.getElementById("shell-state").textContent;
      return document.getElementById("timeline-create").disabled === false && t ? t : null;
    }), 3000);
    await sleep(50);
    const snap = await displaySnapshot(page);
    const apiAfter = (await api("/api/state")).body;
    const apiRevs = (await api("/api/timeline?branch=main")).body.nodes;
    const newTick = apiAfter.meta.timestamp.tick;
    check(
      "c-tick-advances-r" + i,
      revAfter === revBefore + 1 &&
        apiAfter.heads.main === revAfter &&
        snap.shell.indexOf("tick " + newTick) >= 0 &&
        snap.meta.indexOf("tick " + newTick) >= 0,
      JSON.stringify({ revBefore, revAfter, newTick, shell: snap.shell, meta: snap.meta })
    );
    check(
      "c-create-not-stuck-disabled-r" + i,
      snap.createDisabled === false,
      JSON.stringify({ createDisabled: snap.createDisabled, shell })
    );
    check(
      "c-node-count-r" + i,
      snap.nodes === apiRevs.length && snap.head === revAfter,
      JSON.stringify({ domNodes: snap.nodes, apiNodes: apiRevs.length })
    );
    cRounds.push({ revBefore, revAfter, newTick, apiBeforeTick: apiBefore.meta.timestamp.tick, snap });
  }
  values.c = { before: cBefore, rounds: cRounds };
  const allLabels = cRounds[cRounds.length - 1].snap.nodeLabels.join(" | ");
  check(
    "c-node-labels-distinguish-commands",
    allLabels.indexOf("PlanRoute") >= 0 && allLabels.indexOf("AdvanceTime") >= 0,
    JSON.stringify(allLabels)
  );
  await page.screenshot({ path: OUT + "/screenshot-C-after-creates.png" });

  // ══ B. 圆环（.tl-knob）在视口内且**从圆环元素上按下拖动**能改 rev ══
  const knob = await page.evaluate(() => {
    const k = document.querySelector(".tl-knob");
    if (!k) return { exists: false };
    const r = k.getBoundingClientRect();
    const center = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
    const cs = getComputedStyle(k);
    return {
      exists: true, hidden: k.hidden,
      x: r.x, y: r.y, w: r.width, h: r.height,
      pointerEvents: cs.pointerEvents,
      inViewport: r.top >= 0 && r.bottom <= window.innerHeight + 0.5,
      elementAtCenter: center ? center.className : "null",
      dataRevision: k.getAttribute("data-revision"),
    };
  });
  check("b-knob-visible", knob.exists && !knob.hidden && knob.w >= 8 && knob.h >= 8, JSON.stringify(knob));
  check("b-knob-in-viewport", knob.inViewport === true, JSON.stringify(knob));
  check(
    "b-knob-hit-target",
    typeof knob.elementAtCenter === "string" && knob.elementAtCenter.indexOf("tl-knob") >= 0,
    JSON.stringify(knob)
  );

  const revB0 = await stateRevision(page);
  const kb = await page.locator(".tl-knob").boundingBox();
  const cx = kb.x + kb.width / 2;
  const cy = kb.y + kb.height / 2;
  await page.screenshot({ path: OUT + "/screenshot-B-knob-before.png" });
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await sleep(80);
  await page.mouse.move(cx - 120, cy, { steps: 6 });
  await sleep(80);
  await page.mouse.move(cx - 240, cy, { steps: 6 });
  await sleep(120);
  await page.mouse.up();
  await sleep(300);
  const revB1 = await stateRevision(page);
  check("b-knob-drag-changes-rev", revB1 < revB0, JSON.stringify({ revB0, revB1 }));
  const afterDrag = await displaySnapshot(page);
  check("b-offtip-disables-create", afterDrag.createDisabled === true, JSON.stringify(afterDrag));
  values.b = { knob, revB0, revB1, afterDrag };
  await page.screenshot({ path: OUT + "/screenshot-B-knob-after.png" });

  // ══ 零 pageerror ══
  check("z-no-page-errors", pageErrors.length === 0, JSON.stringify(pageErrors));

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
