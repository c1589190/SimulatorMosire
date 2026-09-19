// M7c T1 e2e —— 取消工作台"左键瞬移"：单位模式下左键点格只选中 hex（不发 unit.PlaceAt），移动只由右键（A*→PlanRoute）发起。
// 用法: NODE_PATH=<playwright> node e2e.cjs <base-url> <store-dir> <out-dir>
// ★ 退出码：0 = 全部必测步骤 PASS；1 = 有 FAIL（变异轮据此观察红点）。
"use strict";

const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const STORE = process.argv[3];
const OUT = process.argv[4];

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

function hexDistance(a, b) {
  const dq = a.q - b.q;
  const dr = a.r - b.r;
  return (Math.abs(dq) + Math.abs(dq + dr) + Math.abs(dr)) / 2;
}

function pathText(path) {
  return (path || []).map((h) => "(" + h.q + "," + h.r + ")").join("->");
}

function mapDebug(page) {
  return page.evaluate(() => window.SimosMap.debug());
}

function unitEditDebug(page) {
  return page.evaluate(() => window.SimosMap.unitEditDebug());
}

function stateRevision(page) {
  return page.evaluate(() => window.SimosApp.getState().revision);
}

function bodyMode(page) {
  return page.evaluate(() => document.body.getAttribute("data-mode"));
}

function selection(page) {
  return page.evaluate(() => window.SimosApp.getState().selection);
}

function timelineNodeCount(page, branch) {
  return page.evaluate(
    (b) => document.querySelectorAll('.timeline-line[data-branch="' + b + '"] .tl-node').length,
    branch
  );
}

async function waitMapReady(page) {
  await page.waitForFunction(
    () => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(),
    null,
    { timeout: 40000 }
  );
}

async function openWorkbench(page) {
  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await waitMapReady(page);
  await page.waitForFunction(
    () => document.getElementById("map-status").textContent.indexOf("已载入") >= 0,
    null,
    { timeout: 30000 }
  );
  await sleep(200);
}

/** 在 canvas 上按 (q,r) 点一下（button 缺省左键）。★ 点击前 scrollIntoViewIfNeeded（page.fill 滚动坑同族）。 */
async function clickHex(page, q, r, button) {
  const canvas = page.locator("#canvas");
  await canvas.scrollIntoViewIfNeeded();
  await sleep(100);
  const pt = await page.evaluate(([qq, rr]) => window.SimosMap.screenPointOf(qq, rr), [q, r]);
  const box = await canvas.boundingBox();
  await page.mouse.click(box.x + pt.x, box.y + pt.y, button ? { button } : undefined);
}

async function clickMode(page, mode) {
  await page.click('.mode-bar button[data-mode="' + mode + '"]');
  await waitFor(() => bodyMode(page).then((m) => m === mode), 5000);
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const executablePath =
    process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
  const browser = await chromium.launch({ headless: true, executablePath });
  const page = await browser.newPage({ viewport: { width: 1600, height: 1100 } });

  const pageErrors = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));

  const requests = [];
  page.on("request", (req) => {
    requests.push({ method: req.method(), url: req.url() });
  });
  const pathResponses = [];
  page.on("response", async (resp) => {
    if (resp.url().indexOf("/api/map/path") >= 0) {
      let body = null;
      try {
        body = await resp.json();
      } catch (e) {
        body = null;
      }
      pathResponses.push({ status: resp.status(), body });
    }
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

  // ── a. 切到「单位移动与编辑」、点选 u-1；记层 position 与 head ──
  await openWorkbench(page);
  await clickMode(page, "unit");
  check("a-mode-unit", (await bodyMode(page)) === "unit", String(await bodyMode(page)));

  await clickHex(page, 1, 1);
  const sel = await waitFor(() => selection(page).then((s) => (s && s.kind === "unit" ? s : null)), 5000);
  check("a-unit-selected", !!sel && sel.id === "u-1", JSON.stringify(sel));
  if (!sel || sel.id !== "u-1") {
    throw new Error("点选 u-1 失败，后续断言无意义");
  }

  const unitStart = (await api("/api/unit/u-1")).body;
  const posA = unitStart.position;
  const revStart = await stateRevision(page);
  const headA = (await api("/api/state")).body.heads.main;
  const nodesBefore = await timelineNodeCount(page, "main");
  check("a-position-known", !!posA && posA.q === 1 && posA.r === 1, JSON.stringify(posA));
  values.a = { position: posA, revStart, headA, nodesBefore };

  // ── b. ★ 左键点目标格 (1,2) ⇒ 不发任何写、position/head/revision 均不变、选择变成 hex ──
  const nonGetBeforeB = nonGet().length;
  await clickHex(page, 1, 2);
  await sleep(900);
  const nonGetAfterB = nonGet().length;
  check("b-no-write", nonGetAfterB === nonGetBeforeB, JSON.stringify({ nonGetBeforeB, nonGetAfterB }));

  const unitB = (await api("/api/unit/u-1")).body;
  check(
    "b-position-unchanged",
    !!unitB.position && unitB.position.q === posA.q && unitB.position.r === posA.r,
    JSON.stringify({ before: posA, after: unitB.position })
  );
  const revB = await stateRevision(page);
  const headB = (await api("/api/state")).body.heads.main;
  check("b-head-unchanged", revB === revStart && headB === headA, JSON.stringify({ revStart, revB, headA, headB }));
  const nodesB = await timelineNodeCount(page, "main");
  check("b-no-new-revision", nodesB === nodesBefore, JSON.stringify({ nodesBefore, nodesB }));

  const selB = await waitFor(() => selection(page).then((s) => (s && s.kind === "hex" ? s : null)), 4000);
  check(
    "b-selection-hex",
    !!selB && selB.q === 1 && selB.r === 2 && selB.kind === "hex",
    JSON.stringify(selB)
  );
  check("b-not-teleported", !(unitB.position && unitB.position.r === 2), JSON.stringify(unitB.position));
  values.b = { nonGetBeforeB, nonGetAfterB, position: unitB.position, revB, headB, nodesB, selection: selB };

  await page.screenshot({ path: OUT + "/screenshot-leftclick-select.png", fullPage: true });

  // ── c. 右键 (1,3) ⇒ 重新点选 u-1（b 步选择已切到 hex）后，右键仍正常下路线 ──
  await clickHex(page, 1, 1);
  const selC0 = await waitFor(() => selection(page).then((s) => (s && s.kind === "unit" ? s : null)), 5000);
  check("c-reselect-u1", !!selC0 && selC0.id === "u-1", JSON.stringify(selC0));

  const revC0 = await stateRevision(page);
  const nodesC0 = await timelineNodeCount(page, "main");
  const beforeC = pathResponses.length;
  await clickHex(page, 1, 3, "right");
  const respC = await waitFor(() => (pathResponses.length > beforeC ? pathResponses[pathResponses.length - 1] : null), 8000);
  const pathC = respC && respC.body ? respC.body.path : [];
  check("c-path-status-200", !!respC && respC.status === 200, JSON.stringify(respC));
  check("c-reachable-true", !!respC && respC.body && respC.body.reachable === true, JSON.stringify(respC && respC.body));
  check("c-path-3-points", pathC.length === 3, pathText(pathC));
  check(
    "c-path-endpoints",
    pathC.length === 3 &&
      pathC[0].q === 1 && pathC[0].r === 1 &&
      pathC[2].q === 1 && pathC[2].r === 3,
    pathText(pathC)
  );
  console.log("PATH_C=" + pathText(pathC));

  const revC = await waitFor(() => stateRevision(page).then((r) => (r > revC0 ? r : null)), 8000);
  check("c-head-advanced", revC === revC0 + 1, JSON.stringify({ revC0, revC }));
  const unitC = (await api("/api/unit/u-1")).body;
  const routeC = unitC.movement && unitC.movement.route ? unitC.movement.route.path : [];
  check("c-route-path-3", routeC.length === 3, pathText(routeC));
  check(
    "c-route-path-values",
    routeC.length === 3 && routeC[0].r === 1 && routeC[1].r === 2 && routeC[2].r === 3,
    pathText(routeC)
  );
  const nodesC = await waitFor(
    () => timelineNodeCount(page, "main").then((n) => (n > nodesC0 ? n : null)),
    8000
  );
  check("c-timeline-node-plus-1", nodesC === nodesC0 + 1, JSON.stringify({ nodesC0, nodesC }));
  const dbgC = await waitFor(() => mapDebug(page).then((d) => {
    const r = d.routes.filter((x) => x.id === "u-1")[0];
    return r && r.totalPoints === 3 ? r : null;
  }), 8000);
  check("c-polyline-3-points", !!dbgC && dbgC.totalPoints === 3, JSON.stringify(dbgC));
  values.c = { path: pathC, routePath: routeC, route: dbgC, revC0, revC, nodes: { before: nodesC0, after: nodesC } };

  await page.screenshot({ path: OUT + "/screenshot-rightclick-route.png", fullPage: true });

  // ── d. 路线模式下左键仍能加点（未被误伤）──
  const nonGetBeforeD = nonGet().length;
  await page.click("#unit-route-toggle");
  await waitFor(() => unitEditDebug(page).then((d) => d.routeMode === true), 4000);
  await clickHex(page, 1, 2);
  const dbgD = await waitFor(() => unitEditDebug(page).then((d) => {
    return d.routePath && d.routePath.length === 1 ? d : null;
  }), 4000);
  check("d-route-mode-on", !!dbgD, JSON.stringify(await unitEditDebug(page)));
  check(
    "d-leftclick-added-point",
    !!dbgD && dbgD.routePath[0].q === 1 && dbgD.routePath[0].r === 2,
    JSON.stringify(dbgD)
  );
  const selD = await selection(page);
  check("d-unit-still-selected", !!selD && selD.kind === "unit" && selD.id === "u-1", JSON.stringify(selD));
  const nonGetAfterD = nonGet().length;
  check("d-no-write", nonGetAfterD === nonGetBeforeD, JSON.stringify({ nonGetBeforeD, nonGetAfterD }));
  values.d = { routePath: dbgD && dbgD.routePath, selection: selD, nonGetBeforeD, nonGetAfterD };

  // 关掉路线模式，避免影响后续
  await page.click("#unit-route-toggle");
  await waitFor(() => unitEditDebug(page).then((d) => d.routeMode === false), 4000);

  // ── e. R8 allowlist：全过程非 GET 清单 ⊆ {/api/command,/api/advance,/api/fork} 且打印 ──
  const list = nonGet();
  const allow = ["/api/command", "/api/advance", "/api/fork"];
  const violations = list.filter((p) => allow.indexOf(p) < 0);
  console.log("NON_GET_LIST=" + JSON.stringify(list));
  check("e-nonget-allowlist", violations.length === 0, JSON.stringify({ list, violations }));
  check("e-command-present", list.indexOf("/api/command") >= 0, JSON.stringify(list));
  check("e-no-placeat-via-leftclick", list.length === 1, JSON.stringify(list));
  values.e = { list, violations };
  writeJson("e-nonget-list.json", list);
  writeJson("b-leftclick.json", values.b);
  writeJson("c-rightclick.json", values.c);
  writeJson("d-route-mode.json", values.d);

  // ── f. 零 pageerror ──
  check("f-no-page-errors", pageErrors.length === 0, JSON.stringify(pageErrors));

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
