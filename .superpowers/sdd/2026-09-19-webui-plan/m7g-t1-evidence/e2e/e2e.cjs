// M7g T1 e2e —— 全屏底图 + 浮层控件。
// 覆盖：a 底图铺满视口 / b 浮层在底图之上 / c 面板不穿透 + 面板外可点选 / d 时间轴常驻 + 左栏栏内滚动 + 折叠
//       / e resize 不重置视图 / f "回到世界中心"入口（按钮 + Home） / g 回归（M7e/M7f/M7b/T4） / h R8 allowlist + 零 pageerror。
// 用法: NODE_PATH=<playwright> node e2e.cjs <base-url> <store-dir> <out-dir> [shot-dir] [WxH]
// ★ 退出码：0 = 全部必测步骤 PASS；1 = 有 FAIL（变异轮据此观察红点）。
"use strict";

const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const STORE = process.argv[3];
const OUT = process.argv[4];
const SHOT = process.argv[5] || OUT;
const SIZE = process.argv[6] || "1280x800";
const SIZE_PARTS = SIZE.split("x").map((n) => parseInt(n, 10));
const VW = SIZE_PARTS[0];
const VH = SIZE_PARTS[1];

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

function closeTo(a, b, eps) {
  return Math.abs(a - b) <= (eps === undefined ? 1e-6 : eps);
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

function viewport(page) {
  return page.evaluate(() => ({ w: window.innerWidth, h: window.innerHeight }));
}

function view(page) {
  return page.evaluate(() => window.SimosMap.currentView());
}

function computeFit(page) {
  return page.evaluate(() => window.SimosMap.computeFit());
}

function stateRevision(page) {
  return page.evaluate(() => window.SimosApp.getState().revision);
}

function stateHeads(page) {
  return page.evaluate(() => window.SimosApp.getState().heads);
}

function selection(page) {
  return page.evaluate(() => window.SimosApp.getState().selection);
}

function mapDebug(page) {
  return page.evaluate(() => window.SimosMap.debug());
}

async function hitInfo(page, selector) {
  return page.evaluate((sel) => {
    const el = document.querySelector(sel);
    const canvas = document.getElementById("canvas");
    if (!el) {
      return { found: false };
    }
    const r = el.getBoundingClientRect();
    const x = r.left + r.width / 2;
    const y = r.top + r.height / 2;
    const hit = document.elementFromPoint(x, y);
    return {
      found: true,
      x,
      y,
      hitTag: hit ? hit.tagName : null,
      hitId: hit ? hit.id : null,
      hitInControl: !!(hit && (el === hit || el.contains(hit))),
      hitIsCanvas: hit === canvas,
    };
  }, selector);
}

function elementInfoAt(page, x, y) {
  return page.evaluate(([xx, yy]) => {
    const hit = document.elementFromPoint(xx, yy);
    const canvas = document.getElementById("canvas");
    return { tag: hit ? hit.tagName : null, id: hit ? hit.id : null, isCanvas: hit === canvas };
  }, [x, y]);
}

/** 在中央空带里找一个"面板之外 + 不在任何格上"的底图点（右键空白用）。 */
function findBlankPoint(page) {
  return page.evaluate(() => {
    const canvas = document.getElementById("canvas");
    const w = window.innerWidth;
    const h = window.innerHeight;
    for (let y = 90; y < h - 170; y += 20) {
      for (let x = 340; x < w - 340; x += 20) {
        if (document.elementFromPoint(x, y) !== canvas) {
          continue;
        }
        const pick = window.SimosMap.hexAtScreen({ x, y });
        if (pick && pick.inMap === false) {
          return { x, y };
        }
      }
    }
    return null;
  });
}

function timelineNodes(page, branch) {
  return page.evaluate((b) => {
    const line = document.querySelector('.timeline-line[data-branch="' + b + '"]');
    if (!line) {
      return [];
    }
    return Array.from(line.querySelectorAll(".tl-node")).map((n) => {
      const rect = n.getBoundingClientRect();
      return {
        tick: Number(n.getAttribute("data-tick")),
        revision: Number(n.getAttribute("data-revision")),
        firstRevision: Number(n.getAttribute("data-first-revision")),
        count: Number(n.getAttribute("data-count")),
        commands: n.getAttribute("data-commands"),
        centerX: rect.left + rect.width / 2,
      };
    });
  }, branch);
}

async function apiTicks(branch) {
  const body = (await api("/api/timeline?branch=" + encodeURIComponent(branch))).body;
  const nodes = (body && body.nodes) || [];
  const ticks = [];
  for (const node of nodes) {
    if (ticks.indexOf(node.tick) < 0) {
      ticks.push(node.tick);
    }
  }
  return {
    ticks,
    lastTick: nodes.length > 0 ? nodes[nodes.length - 1].tick : null,
    nodeCount: nodes.length,
  };
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
  await sleep(300);
}

async function clickHex(page, q, r, button) {
  const canvas = page.locator("#canvas");
  await canvas.scrollIntoViewIfNeeded();
  await sleep(60);
  const pt = await page.evaluate(([qq, rr]) => window.SimosMap.screenPointOf(qq, rr), [q, r]);
  const box = await canvas.boundingBox();
  await page.mouse.click(box.x + pt.x, box.y + pt.y, button ? { button } : undefined);
}

async function setAdvanceN(page, value) {
  await page.evaluate((v) => {
    document.getElementById("timeline-advance-n").value = v;
  }, value);
}

async function waitHeadAfter(page, before) {
  return waitFor(() => stateHeads(page).then((h) => (h.main > before ? h.main : null)), 10000);
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  fs.mkdirSync(SHOT, { recursive: true });
  const executablePath =
    process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
  const browser = await chromium.launch({ headless: true, executablePath });
  const page = await browser.newPage({ viewport: { width: VW, height: VH } });

  const pageErrors = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));

  const requests = [];
  page.on("request", (req) => {
    requests.push({ method: req.method(), url: req.url() });
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

  // ── a. 底图铺满整个视口（无边框/圆角盒子感）──
  const vp0 = await viewport(page);
  const cbox = await page.locator("#canvas").boundingBox();
  check(
    "a-canvas-covers-viewport",
    !!cbox &&
      Math.abs(cbox.x) <= 2 &&
      Math.abs(cbox.y) <= 2 &&
      Math.abs(cbox.width - vp0.w) <= 2 &&
      Math.abs(cbox.height - vp0.h) <= 2,
    JSON.stringify({ vp0, cbox })
  );
  const cstyle = await page.evaluate(() => {
    const c = document.getElementById("canvas");
    const m = document.getElementById("canvas-mount");
    const cs = getComputedStyle(c);
    return {
      border: cs.borderTopWidth,
      radius: cs.borderTopLeftRadius,
      mountPos: getComputedStyle(m).position,
      canvasW: c.width,
      canvasH: c.height,
      dpr: window.devicePixelRatio,
    };
  });
  check(
    "a-canvas-no-box",
    cstyle.border === "0px" && cstyle.radius === "0px" && cstyle.mountPos === "fixed",
    JSON.stringify(cstyle)
  );
  check(
    "a-canvas-dpr-sharp",
    Math.abs(cstyle.canvasW - vp0.w * cstyle.dpr) <= 2 &&
      Math.abs(cstyle.canvasH - vp0.h * cstyle.dpr) <= 2,
    JSON.stringify({ canvasW: cstyle.canvasW, expectW: vp0.w * cstyle.dpr, dpr: cstyle.dpr })
  );
  values.a = { vp0, cbox, cstyle };
  writeJson("a-canvas-fullscreen.json", values.a);

  // ── b. 模式栏/时间轴/左栏/右栏均浮在底图之上（命中控件自身，不命中 canvas）──
  const overlays = [
    ["mode-bar", "#mode-bar"],
    ["timeline", "#timeline-bar"],
    ["left-panel", "#left-panel"],
    ["right-panel", "#right-panel"],
  ];
  const bResults = {};
  for (const pair of overlays) {
    const info = await hitInfo(page, pair[1]);
    bResults[pair[0]] = info;
    check(
      "b-" + pair[0] + "-above-canvas",
      info.found && info.hitInControl && !info.hitIsCanvas,
      JSON.stringify(info)
    );
  }
  values.b = bResults;
  writeJson("b-overlays-above.json", values.b);

  // ── c. 面板不穿透 + 面板之外照常点选 ──
  // (1,1) 上有单位 u-1，(1,2) 是纯格 ⇒ 用 (1,2) 证"点底图 = hex 选中"。
  const p12c = await page.evaluate(() => window.SimosMap.screenPointOf(1, 2));
  const outsideInfo = await elementInfoAt(page, p12c.x, p12c.y);
  check("c-outside-is-canvas", outsideInfo.isCanvas, JSON.stringify({ p12c, outsideInfo }));
  await page.mouse.click(p12c.x, p12c.y);
  const selC1 = await waitFor(
    () => selection(page).then((s) => (s && s.kind === "hex" && s.q === 1 && s.r === 2 ? s : null)),
    5000
  );
  check("c-map-outside-selects", !!selC1, JSON.stringify(selC1));

  const nonGetBeforeC = nonGet().length;
  const selBeforeC = await selection(page);
  const h2Info = await hitInfo(page, "#left-panel h2");
  // ★ 用原始鼠标点击（不走 Playwright 的 actionability 检查）：要证的正是"点在面板上会不会穿透到地图"。
  await page.mouse.click(h2Info.x, h2Info.y);
  await sleep(250);
  const selAfterC = await selection(page);
  const nonGetAfterC = nonGet().length;
  check(
    "c-panel-hit-is-control",
    h2Info.found && h2Info.hitInControl && !h2Info.hitIsCanvas,
    JSON.stringify(h2Info)
  );
  check(
    "c-panel-no-passthrough-selection",
    JSON.stringify(selAfterC) === JSON.stringify(selBeforeC),
    JSON.stringify({ selBeforeC, selAfterC })
  );
  check(
    "c-panel-no-passthrough-nowrite",
    nonGetAfterC === nonGetBeforeC,
    JSON.stringify({ nonGetBeforeC, nonGetAfterC })
  );
  values.c = { p12c, outsideInfo, selC1, h2Info, selBeforeC, selAfterC, nonGetBeforeC, nonGetAfterC };
  writeJson("c-panel-passthrough.json", values.c);

  // ── d. 时间轴始终可见 + 左栏栏内滚动 + 折叠可断言 ──
  await page.click('.mode-bar button[data-mode="unit"]');
  await page.waitForFunction(() => document.body.getAttribute("data-mode") === "unit", null, {
    timeout: 5000,
  });
  await clickHex(page, 1, 1);
  const selD = await waitFor(
    () => selection(page).then((s) => (s && s.kind === "unit" && s.id === "u-1" ? s : null)),
    5000
  );
  check("d-unit-selected", !!selD, JSON.stringify(selD));

  const vpD = await viewport(page);
  const barBoxD = await page.locator("#timeline-bar").boundingBox();
  check(
    "d-timeline-visible",
    !!barBoxD &&
      barBoxD.y >= 0 &&
      barBoxD.y + barBoxD.height <= vpD.h + 1 &&
      barBoxD.width > 0 &&
      barBoxD.height > 0,
    JSON.stringify({ vpD, barBoxD })
  );
  const leftScroll = await page.evaluate(() => {
    const p = document.getElementById("left-panel");
    return {
      scrollHeight: p.scrollHeight,
      clientHeight: p.clientHeight,
      overflowY: getComputedStyle(p).overflowY,
    };
  });
  check(
    "d-left-panel-scrolls",
    leftScroll.scrollHeight > leftScroll.clientHeight && leftScroll.overflowY === "auto",
    JSON.stringify(leftScroll)
  );
  await page.evaluate(() => {
    document.getElementById("left-panel").scrollTop = 100000;
  });
  await sleep(150);
  const barBoxD2 = await page.locator("#timeline-bar").boundingBox();
  check(
    "d-timeline-not-pushed",
    !!barBoxD2 && Math.abs(barBoxD2.y - barBoxD.y) <= 1 && Math.abs(barBoxD2.height - barBoxD.height) <= 1,
    JSON.stringify({ barBoxD, barBoxD2 })
  );

  // 截图①：全屏底图 + 全部浮层
  await page.evaluate(() => {
    document.getElementById("left-panel").scrollTop = 0;
  });
  await page.screenshot({ path: SHOT + "/screenshot-fullscreen-all-" + VW + "x" + VH + ".png" });

  await page.click("#panel-toggle-left");
  const leftHidden = await page.evaluate(() => document.getElementById("left-panel").hidden);
  const leftBtn = await page.evaluate(() => {
    const b = document.getElementById("panel-toggle-left");
    return { text: b.textContent, pressed: b.getAttribute("aria-pressed") };
  });
  check("d-left-collapse", leftHidden === true && leftBtn.pressed === "true", JSON.stringify({ leftHidden, leftBtn }));
  const cboxCollapsed = await page.locator("#canvas").boundingBox();
  check(
    "d-canvas-after-collapse",
    !!cboxCollapsed && Math.abs(cboxCollapsed.width - vpD.w) <= 2 && Math.abs(cboxCollapsed.height - vpD.h) <= 2,
    JSON.stringify(cboxCollapsed)
  );
  // 截图②：左栏折叠后
  await page.screenshot({ path: SHOT + "/screenshot-left-collapsed-" + VW + "x" + VH + ".png" });
  await page.click("#panel-toggle-left");
  const leftShown = await page.evaluate(() => document.getElementById("left-panel").hidden === false);
  check("d-left-expand", leftShown, JSON.stringify({ leftShown }));
  values.d = { vpD, barBoxD, barBoxD2, leftScroll, leftHidden, leftBtn, cboxCollapsed, leftShown };
  writeJson("d-timeline-and-panel.json", values.d);

  // ── e. resize 不重置视图 ──
  const anchor = { x: Math.round(vpD.w / 2), y: Math.round(vpD.h / 2) };
  const anchorInfo = await elementInfoAt(page, anchor.x, anchor.y);
  check("e-anchor-is-canvas", anchorInfo.isCanvas, JSON.stringify({ anchor, anchorInfo }));
  const v0 = await view(page);
  await page.mouse.move(anchor.x, anchor.y);
  await page.mouse.wheel(0, -500);
  await sleep(150);
  await page.mouse.move(anchor.x, anchor.y);
  await page.mouse.down();
  await page.mouse.move(anchor.x + 120, anchor.y + 80, { steps: 8 });
  await page.mouse.up();
  await sleep(200);
  const v1 = await view(page);
  check(
    "e-zoom-pan-changed-view",
    !closeTo(v1.scale, v0.scale, 1e-9) || Math.abs(v1.tx - v0.tx) > 1 || Math.abs(v1.ty - v0.ty) > 1,
    JSON.stringify({ v0, v1 })
  );
  await page.setViewportSize({ width: vpD.w - 160, height: vpD.h - 90 });
  await sleep(450);
  const v2 = await view(page);
  check(
    "e-resize-keeps-view",
    closeTo(v2.scale, v1.scale) && closeTo(v2.tx, v1.tx) && closeTo(v2.ty, v1.ty),
    JSON.stringify({ v1, v2 })
  );
  const vpE = await viewport(page);
  const cboxE = await page.locator("#canvas").boundingBox();
  check(
    "e-canvas-covers-after-resize",
    !!cboxE && Math.abs(cboxE.width - vpE.w) <= 2 && Math.abs(cboxE.height - vpE.h) <= 2,
    JSON.stringify({ vpE, cboxE })
  );
  values.e = { v0, v1, v2, vpE, cboxE };
  writeJson("e-resize-no-reset.json", values.e);

  // ── f. "回到世界中心 / 适配视图"入口（按钮 + Home）──
  const expectedF = await computeFit(page);
  const vBeforeReset = await view(page);
  await page.click("#view-reset");
  await sleep(250);
  const vAfterReset = await view(page);
  check(
    "f-reset-to-fit",
    closeTo(vAfterReset.scale, expectedF.scale, 1e-9) &&
      closeTo(vAfterReset.tx, expectedF.tx, 1e-6) &&
      closeTo(vAfterReset.ty, expectedF.ty, 1e-6),
    JSON.stringify({ expectedF, vAfterReset })
  );
  check(
    "f-reset-changed-view",
    !closeTo(vAfterReset.scale, vBeforeReset.scale, 1e-9) ||
      Math.abs(vAfterReset.tx - vBeforeReset.tx) > 1 ||
      Math.abs(vAfterReset.ty - vBeforeReset.ty) > 1,
    JSON.stringify({ vBeforeReset, vAfterReset })
  );
  const vpF = await viewport(page);
  await page.mouse.move(Math.round(vpF.w / 2), Math.round(vpF.h / 2));
  await page.mouse.wheel(0, -300);
  await sleep(150);
  const vZoomF = await view(page);
  await page.keyboard.press("Home");
  await sleep(250);
  const vHomeF = await view(page);
  check(
    "f-home-reset",
    closeTo(vHomeF.scale, expectedF.scale, 1e-9) &&
      closeTo(vHomeF.tx, expectedF.tx, 1e-6) &&
      closeTo(vHomeF.ty, expectedF.ty, 1e-6) &&
      !closeTo(vHomeF.scale, vZoomF.scale, 1e-9),
    JSON.stringify({ expectedF, vZoomF, vHomeF })
  );
  values.f = { expectedF, vBeforeReset, vAfterReset, vZoomF, vHomeF };
  writeJson("f-reset-center.json", values.f);

  // 恢复到初始视口并重新适配
  await page.setViewportSize({ width: vp0.w, height: vp0.h });
  await sleep(350);
  await page.click("#view-reset");
  await sleep(200);

  // ── g. 回归（M7e / M7f / M7b / T4）──
  const waitRouteCount = (n) =>
    waitFor(() => mapDebug(page).then((d) => (d.routeCount === n ? d : null)), 8000);

  // g0：先右键空白清选中（step d 选中的 u-1 仍在 ⇒ 否则下一次左键会直接"取消移动"）
  const blankG = await findBlankPoint(page);
  check("g-blank-point-found", !!blankG, JSON.stringify(blankG));
  if (blankG) {
    await page.mouse.click(blankG.x, blankG.y, { button: "right" });
  }
  await waitFor(() => selection(page).then((s) => (s === null ? true : null)), 4000);
  const selCleared = await selection(page);
  check("g-blank-clears-selection", selCleared === null, JSON.stringify({ selCleared }));
  const nonGetAfterClear = nonGet().length;

  // g1（M7e）：左键点单位 = 选中（不发写）
  await clickHex(page, 1, 1);
  const selG = await waitFor(
    () => selection(page).then((s) => (s && s.kind === "unit" && s.id === "u-1" ? s : null)),
    5000
  );
  const nonGetAfterSelect = nonGet().length;
  check("g-leftclick-selects", !!selG, JSON.stringify(selG));
  check(
    "g-leftclick-select-no-write",
    nonGetAfterSelect === nonGetAfterClear,
    JSON.stringify({ nonGetAfterClear, nonGetAfterSelect })
  );

  // g2（M7e）：右键目标格 ⇒ 下路线（PlanRoute，唯写）
  const headBeforeRoute = (await stateHeads(page)).main;
  await clickHex(page, 1, 3, "right");
  const headAfterRoute = await waitHeadAfter(page, headBeforeRoute);
  const route1 = await waitRouteCount(1);
  check(
    "g-route-created",
    headAfterRoute === headBeforeRoute + 1 && !!route1,
    JSON.stringify({ headBeforeRoute, headAfterRoute, routeCount: route1 && route1.routeCount })
  );

  // g3（M7e）：右键空白 ⇒ 取消选中、保留路线、不发写
  const nonGetBeforeBlank = nonGet().length;
  if (blankG) {
    await page.mouse.click(blankG.x, blankG.y, { button: "right" });
  }
  await sleep(250);
  const selBlank = await selection(page);
  const dbgBlank = await mapDebug(page);
  const nonGetAfterBlank = nonGet().length;
  check("g-rightblank-clears-selection", selBlank === null, JSON.stringify({ selBlank }));
  check("g-rightblank-keeps-route", dbgBlank.routeCount === 1, JSON.stringify({ routeCount: dbgBlank.routeCount }));
  check(
    "g-rightblank-no-write",
    nonGetAfterBlank === nonGetBeforeBlank,
    JSON.stringify({ nonGetBeforeBlank, nonGetAfterBlank })
  );

  // g4（M7e）：左键点单位第一次=选中、第二次=取消移动（发 CancelRoute）
  await clickHex(page, 1, 1);
  const selFirst = await waitFor(
    () => selection(page).then((s) => (s && s.kind === "unit" && s.id === "u-1" ? s : null)),
    5000
  );
  const nonGetAfterFirst = nonGet().length;
  const dbgFirst = await mapDebug(page);
  check("g-leftclick1-selects", !!selFirst, JSON.stringify(selFirst));
  check(
    "g-leftclick1-keeps-route",
    dbgFirst.routeCount === 1 && nonGetAfterFirst === nonGetAfterBlank,
    JSON.stringify({ routeCount: dbgFirst.routeCount, nonGetAfterBlank, nonGetAfterFirst })
  );
  const headBeforeCancel = (await stateHeads(page)).main;
  await clickHex(page, 1, 1);
  const headAfterCancel = await waitHeadAfter(page, headBeforeCancel);
  const routeZero = await waitRouteCount(0);
  check(
    "g-leftclick2-cancels-movement",
    !!routeZero && headAfterCancel === headBeforeCancel + 1,
    JSON.stringify({ routeCount: routeZero && routeZero.routeCount, headBeforeCancel, headAfterCancel })
  );

  // g5（M7f）：下路线不新增节点（同 tick 明细 +1）；推进 N=3 ⇒ tick +3、节点 +1
  const nodesBeforeG5 = await timelineNodes(page, "main");
  const apiBeforeG5 = await apiTicks("main");
  const headBeforeG5 = (await stateHeads(page)).main;
  await clickHex(page, 1, 3, "right");
  await waitHeadAfter(page, headBeforeG5);
  await waitRouteCount(1);
  const nodesAfterG5 = await timelineNodes(page, "main");
  const apiAfterG5 = await apiTicks("main");
  check(
    "g-route-no-new-node",
    nodesAfterG5.length === nodesBeforeG5.length &&
      apiAfterG5.ticks.length === apiBeforeG5.ticks.length &&
      apiAfterG5.lastTick === apiBeforeG5.lastTick,
    JSON.stringify({
      nodesBefore: nodesBeforeG5.length,
      nodesAfter: nodesAfterG5.length,
      apiBefore: apiBeforeG5.ticks,
      apiAfter: apiAfterG5.ticks,
    })
  );
  check(
    "g-route-detail-plus-one",
    nodesAfterG5[0].count === nodesBeforeG5[0].count + 1 && nodesAfterG5[0].tick === nodesBeforeG5[0].tick,
    JSON.stringify({ before: nodesBeforeG5[0], after: nodesAfterG5[0] })
  );
  await setAdvanceN(page, "3");
  const tipG5 = (await apiTicks("main")).lastTick;
  const headBeforeAdv = (await stateHeads(page)).main;
  await page.click("#timeline-create");
  const headAfterAdv = await waitHeadAfter(page, headBeforeAdv);
  await waitFor(
    () => timelineNodes(page, "main").then((ns) => (ns.length > nodesAfterG5.length ? ns : null)),
    8000
  );
  const nodesAfterAdv = await timelineNodes(page, "main");
  const apiAfterAdv = await apiTicks("main");
  check(
    "g-advance-3-tick",
    apiAfterAdv.lastTick === tipG5 + 3 && nodesAfterAdv[nodesAfterAdv.length - 1].tick === tipG5 + 3,
    JSON.stringify({ tipG5, apiLastTick: apiAfterAdv.lastTick, domLastTick: nodesAfterAdv[nodesAfterAdv.length - 1].tick })
  );
  check(
    "g-advance-3-node-plus-one",
    nodesAfterAdv.length === nodesAfterG5.length + 1 && headAfterAdv === headBeforeAdv + 1,
    JSON.stringify({ nodesAfterG5: nodesAfterG5.length, nodesAfterAdv: nodesAfterAdv.length, headBeforeAdv, headAfterAdv })
  );

  // g6（M7b）：knob 可拖（游标从非末端拖回末端）
  await page.evaluate(() => {
    document.querySelector('.timeline-line[data-branch="main"] .tl-node').click();
  });
  await sleep(200);
  const headG6 = (await stateHeads(page)).main;
  const midRevG6 = await stateRevision(page);
  check("g-knob-cursor-moved-off-tip", midRevG6 !== headG6, JSON.stringify({ midRevG6, headG6 }));
  const knobBox = await page.locator(".tl-knob").boundingBox();
  const tipBox = await page.evaluate(() => {
    const nodes = document.querySelectorAll('.timeline-line[data-branch="main"] .tl-node');
    const n = nodes[nodes.length - 1];
    const r = n.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });
  check("g-knob-visible", !!knobBox, JSON.stringify(knobBox));
  if (knobBox && tipBox) {
    const kx = knobBox.x + knobBox.width / 2;
    const ky = knobBox.y + knobBox.height / 2;
    await page.mouse.move(kx, ky);
    await page.mouse.down();
    await page.mouse.move(kx + (tipBox.x - kx) / 2, ky, { steps: 6 });
    await page.mouse.move(tipBox.x, ky, { steps: 6 });
    await page.mouse.up();
  }
  const revBackG6 = await waitFor(() => stateRevision(page).then((r) => (r === headG6 ? r : null)), 6000);
  check("g-knob-drag-to-tip", revBackG6 === headG6, JSON.stringify({ midRevG6, headG6, revBackG6 }));

  // g7（T4）：缩放后点选仍准
  const vpG = await viewport(page);
  const gcx = Math.round(vpG.w / 2);
  const gcy = Math.round(vpG.h / 2);
  const gcenterInfo = await elementInfoAt(page, gcx, gcy);
  check("g-zoom-anchor-is-canvas", gcenterInfo.isCanvas, JSON.stringify(gcenterInfo));
  await page.mouse.move(gcx, gcy);
  await page.mouse.wheel(0, -400);
  await sleep(200);
  const p12 = await page.evaluate(() => window.SimosMap.screenPointOf(1, 2));
  const info12 = await elementInfoAt(page, p12.x, p12.y);
  check("g-t4-target-is-canvas", info12.isCanvas, JSON.stringify({ p12, info12 }));
  await page.mouse.click(p12.x, p12.y);
  const selT4 = await waitFor(
    () => selection(page).then((s) => (s && s.kind === "hex" && s.q === 1 && s.r === 2 ? s : null)),
    5000
  );
  check("g-t4-click-accurate", !!selT4, JSON.stringify({ p12, selT4 }));

  values.g = {
    blankG,
    selCleared,
    nonGetAfterClear,
    selG,
    nonGetAfterSelect,
    headBeforeRoute,
    headAfterRoute,
    route1,
    selBlank,
    dbgBlank,
    nonGetBeforeBlank,
    nonGetAfterBlank,
    headBeforeCancel,
    headAfterCancel,
    routeZero,
    nodesBeforeG5,
    nodesAfterG5,
    apiBeforeG5,
    apiAfterG5,
    tipG5,
    nodesAfterAdv,
    apiAfterAdv,
    midRevG6,
    headG6,
    revBackG6,
    p12,
    selT4,
  };
  writeJson("g-regressions.json", values.g);

  // ── h. R8 allowlist + 零 pageerror ──
  const list = nonGet();
  const allow = ["/api/command", "/api/advance", "/api/fork"];
  const violations = list.filter((p) => allow.indexOf(p) < 0);
  console.log("NON_GET_LIST=" + JSON.stringify(list));
  check("h-nonget-allowlist", violations.length === 0, JSON.stringify({ list, violations }));
  check("h-command-present", list.indexOf("/api/command") >= 0, JSON.stringify(list));
  check("h-no-page-errors", pageErrors.length === 0, JSON.stringify(pageErrors));
  values.h = { list, violations, pageErrors };
  writeJson("h-nonget-list.json", values.h);

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
