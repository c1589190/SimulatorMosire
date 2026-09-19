// M7 T7 e2e —— Playwright 驱动真页面（真 ShellMain --demo），验证单位移动与编辑模式（判据⑤ / R8）。
// 用法: NODE_PATH=<playwright> node e2e.cjs <base-url> <store-dir> <out-dir>
// ★ 退出码：0 = 全部必测步骤 PASS；1 = 有 FAIL（变异轮据此观察红点）。
"use strict";

const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const STORE = process.argv[3];
const OUT = process.argv[4];
const CHROME =
  process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";

// ★ R8：前端允许发出的**全部**非 GET 路径。任何其它路径 ⇒ allowlist 断言红。
const WRITE_ALLOWLIST = ["/api/command", "/api/advance", "/api/fork"];

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

function jsonPost(body) {
  return {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body),
  };
}

function writeJson(name, value) {
  fs.writeFileSync(OUT + "/" + name, JSON.stringify(value, null, 2));
}

async function currentHead() {
  const s = await api("/api/state");
  return s.body.heads.main;
}

function stateRevision(page) {
  return page.evaluate(() => window.SimosApp.getState().revision);
}

function selection(page) {
  return page.evaluate(() => window.SimosApp.getState().selection);
}

function editStatus(page) {
  return page.evaluate(() => {
    const n = document.getElementById("unit-edit-status");
    return n ? n.textContent : "";
  });
}

function timelineNodeCount(page, branch) {
  return page.evaluate(
    (b) => document.querySelectorAll('.timeline-line[data-branch="' + b + '"] .tl-node').length,
    branch
  );
}

async function clickHex(page, q, r) {
  // ★ 表单 fill 会把页面滚下去（canvas box.y 变负），不先滚回来点击就落在视口外。
  const canvas = page.locator("#canvas");
  await canvas.scrollIntoViewIfNeeded();
  await sleep(120);
  const pt = await page.evaluate(([qq, rr]) => window.SimosMap.screenPointOf(qq, rr), [q, r]);
  const box = await canvas.boundingBox();
  await page.mouse.click(box.x + pt.x, box.y + pt.y);
}

async function waitRevisionAbove(page, before, timeout) {
  // ★ 不抛：写失败时后续步骤仍要跑完（否则变异体的红点永远到不了 e 步的 allowlist 断言）。
  await page
    .waitForFunction((b) => window.SimosApp.getState().revision > b, before, {
      timeout: timeout || 8000,
    })
    .catch(() => {});
}

async function safeClick(page, selector) {
  try {
    await page.click(selector, { timeout: 3000 });
    return true;
  } catch (e) {
    console.log("CLICK-SKIP " + selector + " " + String(e.message || e).split("\n")[0]);
    return false;
  }
}

async function waitUnitCount(page, n, timeout) {
  await page
    .waitForFunction((count) => window.SimosMap.debug().unitCount >= count, n, {
      timeout: timeout || 10000,
    })
    .catch(() => {});
}

async function waitSelection(page, id, timeout) {
  await page
    .waitForFunction(
      (want) => {
        const s = window.SimosApp.getState().selection;
        return s && s.kind === "unit" && s.id === want;
      },
      id,
      { timeout: timeout || 8000 }
    )
    .catch(() => {});
}

async function waitStatusContains(page, text, timeout) {
  try {
    await page.waitForFunction(
      (needle) => {
        const n = document.getElementById("unit-edit-status");
        return n && n.textContent.indexOf(needle) >= 0;
      },
      text,
      { timeout: timeout || 10000 }
    );
    return true;
  } catch (e) {
    return false;
  }
}

async function waitMapReady(page, timeout) {
  await page.waitForFunction(
    () => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(),
    null,
    { timeout: timeout || 40000 }
  );
}

async function run(page, nonGet) {
  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await waitMapReady(page, 40000);
  await page.waitForFunction(
    () => document.getElementById("map-status").textContent.indexOf("已载入") >= 0,
    null,
    { timeout: 30000 }
  );
  await page.waitForSelector('.timeline-line[data-branch="main"] .tl-node', { timeout: 15000 });
  await page.waitForSelector('.tree-node[data-unit-id="u-1"]', { timeout: 15000 });

  // ── 进入「单位移动与编辑」模式 ──
  await page.click('button[data-mode="unit"]');
  await page.waitForFunction(() => document.body.getAttribute("data-mode") === "unit");
  await page.waitForSelector("#unit-edit-status", { state: "visible", timeout: 8000 });
  check("mode-unit-active", (await page.getAttribute("body", "data-mode")) === "unit", "unit");
  await page.evaluate(() => window.SimosMap.resetView());
  await sleep(300);

  // ── a. 点选式移动：点单位 ⇒ 选中；点目标格 ⇒ unit.PlaceAt ──
  const headBeforeA = await currentHead();
  const nodesBeforeA = await timelineNodeCount(page, "main");
  await clickHex(page, 1, 1);
  await waitSelection(page, "u-1");
  const selA = await selection(page);
  check("a-select-unit", !!selA && selA.kind === "unit" && selA.id === "u-1", JSON.stringify(selA));

  const unitBeforeA = (await api("/api/unit/u-1")).body;
  check(
    "a-position-before",
    !!unitBeforeA.position && unitBeforeA.position.q === 1 && unitBeforeA.position.r === 1,
    JSON.stringify(unitBeforeA.position)
  );

  await clickHex(page, 1, 2);
  await waitRevisionAbove(page, headBeforeA);
  const unitAfterA = (await api("/api/unit/u-1")).body;
  check(
    "a-position-after-target",
    !!unitAfterA.position && unitAfterA.position.q === 1 && unitAfterA.position.r === 2,
    JSON.stringify({ before: unitBeforeA.position, after: unitAfterA.position })
  );
  const headAfterA = await currentHead();
  check("a-head-plus-one", headAfterA === headBeforeA + 1, JSON.stringify({ before: headBeforeA, after: headAfterA }));

  let nodesAfterA = nodesBeforeA;
  try {
    await page.waitForFunction(
      (n) => document.querySelectorAll('.timeline-line[data-branch="main"] .tl-node').length === n,
      nodesBeforeA + 1,
      { timeout: 10000 }
    );
  } catch (e) {
    /* 取实际值让断言自己红 */
  }
  nodesAfterA = await timelineNodeCount(page, "main");
  check(
    "a-timeline-node-plus-one",
    nodesAfterA === nodesBeforeA + 1,
    JSON.stringify({ before: nodesBeforeA, after: nodesAfterA })
  );
  check("a-edit-status", (await editStatus(page)).indexOf("已移动") >= 0, JSON.stringify(await editStatus(page)));
  // ★ 写后可见（MUST DO #5）：地图层也刷到新位置（不只是 API/左栏）
  try {
    await page.waitForFunction(
      () => {
        const p = window.SimosMap.unitPosition && window.SimosMap.unitPosition("u-1");
        return !!p && p.q === 1 && p.r === 2;
      },
      null,
      { timeout: 10000 }
    );
  } catch (e) {
    /* 取实际值让断言自己红 */
  }
  const mapPosA = await page.evaluate(() => window.SimosMap.unitPosition("u-1"));
  check("a-map-reflects-move", !!mapPosA && mapPosA.q === 1 && mapPosA.r === 2, JSON.stringify(mapPosA));
  const shellStateA = await page.evaluate(() => document.getElementById("shell-state").textContent);
  check("a-shell-state-refreshed", shellStateA.indexOf("rev " + headAfterA) >= 0, JSON.stringify(shellStateA));
  await page.screenshot({ path: OUT + "/screenshot-move-after.png" });
  values.a = { headBefore: headBeforeA, headAfter: headAfterA, nodesBefore: nodesBeforeA, nodesAfter: nodesAfterA, positionBefore: unitBeforeA.position, positionAfter: unitAfterA.position, mapPositionAfter: mapPosA };

  // ── a2. 路线式移动：路线模式 ⇒ 逐格相邻点列 ⇒ unit.PlanRoute ──
  await safeClick(page, "#unit-route-toggle");
  await page.waitForFunction(
    () => document.getElementById("unit-route-toggle").textContent.indexOf("开") >= 0,
    null,
    { timeout: 5000 }
  );
  await clickHex(page, 1, 3); // 与 u-1 当前位置 (1,2) 相邻
  const routeDebug = await page.evaluate(() => window.SimosMap.unitEditDebug());
  check(
    "a2-route-append",
    routeDebug.routePath.length === 1 && routeDebug.routePath[0].q === 1 && routeDebug.routePath[0].r === 3,
    JSON.stringify(routeDebug.routePath)
  );
  const headBeforeA2 = await currentHead();
  await safeClick(page, "#unit-route-send");
  await waitRevisionAbove(page, headBeforeA2);
  const u1Route = (await api("/api/unit/u-1")).body;
  check("a2-plan-route", u1Route.movement === true, JSON.stringify({ movement: u1Route.movement }));
  check(
    "a2-head-plus-one",
    (await currentHead()) === headBeforeA2 + 1,
    JSON.stringify({ before: headBeforeA2, after: await currentHead() })
  );
  values.a2 = { routePath: routeDebug.routePath, movement: u1Route.movement };
  await safeClick(page, "#unit-route-toggle"); // 关掉路线模式，后续走点选式移动

  // ── b. 编制改动（CreateUnit / ReparentUnit / SetStrength / DisbandUnit，全部真发）──
  // b1 CreateUnit u-2（无上级）
  await page.fill("#unit-create-id", "u-2");
  await page.fill("#unit-create-name", "第二连");
  await page.fill("#unit-create-q", "1");
  await page.fill("#unit-create-r", "3");
  await page.fill("#unit-create-member", "50");
  await page.fill("#unit-create-equipment", "");
  await page.fill("#unit-create-speed", "1");
  await page.fill("#unit-create-mobility", "1000");
  await page.fill("#unit-create-parent", "");
  const headBeforeB1 = await currentHead();
  await safeClick(page, "#unit-create");
  await waitRevisionAbove(page, headBeforeB1);
  const u2 = (await api("/api/unit/u-2")).body;
  check(
    "b1-create-unit",
    !!u2 && u2.id === "u-2" && !!u2.position && u2.position.q === 1 && u2.position.r === 3 && u2.parent === null,
    JSON.stringify(u2 && { id: u2.id, position: u2.position, parent: u2.parent })
  );
  check("b1-head-plus-one", (await currentHead()) === headBeforeB1 + 1, JSON.stringify({ before: headBeforeB1, after: await currentHead() }));

  // b2 ReparentUnit u-1 → u-2
  await page.fill("#unit-reparent-parent", "u-2");
  const headBeforeB2 = await currentHead();
  await safeClick(page, "#unit-reparent");
  await waitRevisionAbove(page, headBeforeB2);
  const u1b = (await api("/api/unit/u-1")).body;
  check("b2-reparent", u1b.parent === "u-2", JSON.stringify({ parentBefore: null, parentAfter: u1b.parent }));
  check("b2-head-plus-one", (await currentHead()) === headBeforeB2 + 1, JSON.stringify({ before: headBeforeB2, after: await currentHead() }));
  values.b = { createId: "u-2", reparent: { id: "u-1", parentBefore: null, parentAfter: u1b.parent } };

  // b3 SetStrength u-1：member 123、装备 {步枪:7}
  await page.fill("#unit-strength-member", "123");
  await page.fill("#unit-strength-equipment", "步枪=7");
  const headBeforeB3 = await currentHead();
  await safeClick(page, "#unit-strength");
  await waitRevisionAbove(page, headBeforeB3);
  const u1c = (await api("/api/unit/u-1")).body;
  check(
    "b3-set-strength",
    u1c.member === 123 && !!u1c.equipment && u1c.equipment["步枪"] === 7,
    JSON.stringify({ member: u1c.member, equipment: u1c.equipment })
  );
  check("b3-head-plus-one", (await currentHead()) === headBeforeB3 + 1, JSON.stringify({ before: headBeforeB3, after: await currentHead() }));
  values.b.strength = { member: u1c.member, equipment: u1c.equipment };

  // b4 CreateUnit u-3（带 parent=u-2）
  await page.fill("#unit-create-id", "u-3");
  await page.fill("#unit-create-name", "第三队");
  await page.fill("#unit-create-q", "1");
  await page.fill("#unit-create-r", "1");
  await page.fill("#unit-create-member", "20");
  await page.fill("#unit-create-equipment", "火炮=2");
  await page.fill("#unit-create-speed", "2");
  await page.fill("#unit-create-mobility", "1500");
  await page.fill("#unit-create-parent", "u-2");
  const headBeforeB4 = await currentHead();
  await safeClick(page, "#unit-create");
  await waitRevisionAbove(page, headBeforeB4);
  const u3 = (await api("/api/unit/u-3")).body;
  check(
    "b4-create-with-parent",
    !!u3 && u3.parent === "u-2" && !!u3.position && u3.position.q === 1 && u3.position.r === 1,
    JSON.stringify(u3 && { id: u3.id, parent: u3.parent, position: u3.position })
  );
  check("b4-head-plus-one", (await currentHead()) === headBeforeB4 + 1, JSON.stringify({ before: headBeforeB4, after: await currentHead() }));
  values.b.createWithParent = { id: "u-3", parent: u3.parent, position: u3.position };
  await waitUnitCount(page, 3);
  await page.waitForSelector('.tree-node[data-unit-id="u-3"]', { timeout: 8000 }).catch(() => {});
  await page.evaluate(() => window.scrollTo(0, 0));
  await sleep(300);
  await page.screenshot({ path: OUT + "/screenshot-composition.png" });

  // b5 DisbandUnit u-3（先点地图选中它）
  console.log(
    "B5-UNITS " +
      JSON.stringify(
        ((await api("/api/units?branch=main&revision=" + (await stateRevision(page)))).body.units || []).map(
          (u) => ({ id: u.id, pos: u.position })
        )
      )
  );
  await clickHex(page, 1, 1);
  await waitSelection(page, "u-3");
  const selB5 = await selection(page);
  check("b5-select-u3", !!selB5 && selB5.id === "u-3", JSON.stringify(selB5));
  const headBeforeB5 = await currentHead();
  await safeClick(page, "#unit-disband");
  await waitRevisionAbove(page, headBeforeB5);
  const u3Gone = await api("/api/unit/u-3");
  check("b5-disband", u3Gone.status === 404, JSON.stringify({ status: u3Gone.status }));
  check("b5-head-plus-one", (await currentHead()) === headBeforeB5 + 1, JSON.stringify({ before: headBeforeB5, after: await currentHead() }));

  // ── c. 失败必须显式：422 ⇒ 页面显示 reason 原文（改上级到一个不存在的单位）──
  await clickHex(page, 1, 2);
  await waitSelection(page, "u-1");
  const headBeforeC = await currentHead();
  await page.fill("#unit-reparent-parent", "ghost-parent");
  await safeClick(page, "#unit-reparent");
  const sawReason = await waitStatusContains(page, "父单位不存在");
  const statusC = await editStatus(page);
  check(
    "c-422-reason-visible",
    sawReason && statusC.indexOf("父单位不存在: ghost-parent") >= 0,
    JSON.stringify(statusC)
  );
  check(
    "c-422-head-unchanged",
    (await currentHead()) === headBeforeC,
    JSON.stringify({ before: headBeforeC, after: await currentHead() })
  );
  values.c = { reasonShown: statusC, headUnchanged: headBeforeC };

  // ── d. 409：外部推 head 使页面 expectedRevision 过期 ⇒ 再点移动 ⇒ 提示 + 自动重取 ──
  const headBeforeD = await currentHead();
  const timelineD = (await api("/api/timeline?branch=main")).body;
  const nodeD = (timelineD.nodes || []).filter((n) => n.revision === headBeforeD)[0];
  const tickD = nodeD ? nodeD.tick : 5;
  const adv = await api(
    "/api/advance",
    jsonPost({ branch: "main", expectedRevision: headBeforeD, from: tickD, to: tickD + 1 })
  );
  check(
    "d-external-advance",
    adv.status === 200 && !!adv.body && adv.body.result === "committed",
    JSON.stringify(adv.body)
  );
  const headAfterExternal = await currentHead();
  const pageRevBeforeD = await stateRevision(page);
  check(
    "d-page-revision-stale",
    pageRevBeforeD === headBeforeD && headAfterExternal === headBeforeD + 1,
    JSON.stringify({ pageRevBeforeD, headBeforeD, headAfterExternal })
  );
  const unitBeforeD = (await api("/api/unit/u-1")).body;
  await clickHex(page, 1, 1);
  const sawConflict = await waitStatusContains(page, "末端已移动", 12000);
  const statusD = await editStatus(page);
  check("d-409-message", sawConflict && statusD.indexOf("末端已移动") >= 0, JSON.stringify(statusD));
  let pageRevAfterD = await stateRevision(page);
  try {
    await page.waitForFunction((h) => window.SimosApp.getState().revision === h, headAfterExternal, {
      timeout: 8000,
    });
  } catch (e) {
    /* 取实际值让断言自己红 */
  }
  pageRevAfterD = await stateRevision(page);
  check(
    "d-head-refetched",
    pageRevAfterD === headAfterExternal,
    JSON.stringify({ pageRevAfterD, headAfterExternal })
  );
  const unitAfterD = (await api("/api/unit/u-1")).body;
  check(
    "d-409-no-write",
    JSON.stringify(unitAfterD.position) === JSON.stringify(unitBeforeD.position),
    JSON.stringify({ before: unitBeforeD.position, after: unitAfterD.position })
  );
  values.d = { headBeforeD, headAfterExternal, pageRevBeforeD, pageRevAfterD, statusShown: statusD, positionUnchanged: unitAfterD.position };

  // ── e. 写路径 allowlist（R8）：所有非 GET 请求的 path 必须 ∈ {command, advance, fork} ──
  const nonGetPaths = Array.from(new Set(nonGet.map((r) => r.path))).sort();
  check(
    "e-write-allowlist",
    nonGet.length > 0 && nonGet.every((r) => WRITE_ALLOWLIST.indexOf(r.path) >= 0),
    JSON.stringify(nonGetPaths)
  );
  console.log("NON-GET-REQUESTS " + JSON.stringify(nonGet.map((r) => r.method + " " + r.path)));
  writeJson("non-get-requests.json", nonGet);
  values.e = { allowlist: WRITE_ALLOWLIST, observedPaths: nonGetPaths, count: nonGet.length };

  // ── f. 只读模式未被污染：切回「常规查看」后点单位 + 点格 ⇒ 不发任何非 GET ──
  await page.click('button[data-mode="view"]');
  await page.waitForFunction(() => document.body.getAttribute("data-mode") === "view");
  const beforeF = nonGet.length;
  await clickHex(page, 1, 2);
  await sleep(400);
  await clickHex(page, 1, 1);
  await sleep(900);
  const afterF = nonGet.length;
  check(
    "f-readonly-no-write",
    afterF === beforeF,
    JSON.stringify({ before: beforeF, after: afterF, newOnes: nonGet.slice(beforeF) })
  );
  const selF = await selection(page);
  check("f-readonly-selection", !!selF, JSON.stringify(selF));
  values.f = { nonGetBefore: beforeF, nonGetAfter: afterF, selection: selF };

  writeJson("e2e-values.json", values);
}

(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: CHROME, args: ["--no-sandbox"] });
  const page = await browser.newPage({ viewport: { width: 1600, height: 1100 } });
  // ★ R8 全过程抓包：非 GET 一律记 path（含方法），供 e/f 步断言。
  const nonGet = [];
  page.on("request", (r) => {
    if (r.method() !== "GET") {
      nonGet.push({ method: r.method(), path: new URL(r.url()).pathname, url: r.url() });
    }
  });
  page.on("pageerror", (err) => {
    console.log("PAGE-ERROR: " + (err && err.message ? err.message : err));
  });
  page.on("response", (r) => {
    if (r.status() >= 400) {
      console.log("HTTP-" + r.status() + " " + r.request().method() + " " + r.url());
    }
  });
  page.on("console", (msg) => {
    if (msg.type() === "warning" || msg.type() === "error") {
      console.log("PAGE-" + msg.type().toUpperCase() + ": " + msg.text());
    }
  });
  try {
    await run(page, nonGet);
  } catch (e) {
    check("exception", false, e && e.stack ? e.stack : String(e));
  }
  await browser.close();
  console.log("E2E RESULT: " + (failures.length === 0 ? "PASS" : "FAIL " + failures.join(",")));
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((e) => {
  console.log("E2E RESULT: FAIL exception " + (e && e.stack ? e.stack : e));
  process.exit(1);
});
