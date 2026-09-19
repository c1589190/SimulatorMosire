// M7b T3 e2e —— 钢铁雄心式右键移动：点单位 → 右键目标格 → 服务端 A*（/api/map/path）→ unit.PlanRoute → 画线。
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

function hexDistance(a, b) {
  const dq = a.q - b.q;
  const dr = a.r - b.r;
  return (Math.abs(dq) + Math.abs(dq + dr) + Math.abs(dr)) / 2;
}

function isStepwise(path) {
  if (!Array.isArray(path) || path.length < 2) {
    return false;
  }
  for (let i = 1; i < path.length; i++) {
    if (hexDistance(path[i - 1], path[i]) !== 1) {
      return false;
    }
  }
  return true;
}

function pathText(path) {
  return (path || []).map((h) => "(" + h.q + "," + h.r + ")").join("->");
}

function mapDebug(page) {
  return page.evaluate(() => window.SimosMap.debug());
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

/** 在 canvas 上按 (q,r) 点一下（button 缺省左键）。★ click 前 scrollIntoViewIfNeeded（page.fill 滚动坑同族）。 */
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

  // ── a. 切到「单位移动与编辑」、点选 u-1 ──
  await openWorkbench(page);
  await clickMode(page, "unit");
  check("a-mode-unit", (await bodyMode(page)) === "unit", String(await bodyMode(page)));

  await clickHex(page, 1, 1);
  const sel = await waitFor(() => selection(page).then((s) => (s && s.kind === "unit" ? s : null)), 5000);
  check(
    "a-unit-selected",
    !!sel && sel.id === "u-1",
    JSON.stringify(sel)
  );
  if (!sel || sel.id !== "u-1") {
    throw new Error("点选 u-1 失败，后续右键断言无意义");
  }

  const revStart = await stateRevision(page);
  const nodesBefore = await timelineNodeCount(page, "main");

  // ── b. 右键 (1,3) ⇒ /api/map/path 返回逐格相邻的 3 点路径 ──
  const beforeB = pathResponses.length;
  await clickHex(page, 1, 3, "right");
  const respB = await waitFor(() => (pathResponses.length > beforeB ? pathResponses[pathResponses.length - 1] : null), 8000);
  const pathB = respB && respB.body ? respB.body.path : [];
  check("b-path-status-200", !!respB && respB.status === 200, JSON.stringify(respB));
  check("b-reachable-true", !!respB && respB.body && respB.body.reachable === true, JSON.stringify(respB && respB.body));
  check("b-path-3-points", pathB.length === 3, pathText(pathB));
  check("b-path-stepwise", isStepwise(pathB), pathText(pathB));
  check(
    "b-path-endpoints",
    pathB.length === 3 &&
      pathB[0].q === 1 && pathB[0].r === 1 &&
      pathB[2].q === 1 && pathB[2].r === 3,
    pathText(pathB)
  );
  console.log("PATH_B=" + pathText(pathB));
  values.b = { status: respB && respB.status, body: respB && respB.body };

  // ── c. PlanRoute committed、head +1、时间轴 +1、route.path == 3 点、折线 3 点 ──
  const revC = await waitFor(() => stateRevision(page).then((r) => (r > revStart ? r : null)), 8000);
  check("c-head-advanced", revC === revStart + 1, JSON.stringify({ revStart, revC }));

  const unitC = (await api("/api/unit/u-1")).body;
  const routeC = unitC.movement && unitC.movement.route ? unitC.movement.route.path : [];
  check("c-route-path-3", routeC.length === 3, pathText(routeC));
  check(
    "c-route-path-values",
    routeC.length === 3 && routeC[0].r === 1 && routeC[1].r === 2 && routeC[2].r === 3,
    pathText(routeC)
  );
  check("c-head-api", (await api("/api/state")).body.heads.main === revStart + 1, String(revStart + 1));

  const nodesAfterC = await waitFor(
    () => timelineNodeCount(page, "main").then((n) => (n > nodesBefore ? n : null)),
    8000
  );
  check("c-timeline-node-plus-1", nodesAfterC === nodesBefore + 1, JSON.stringify({ nodesBefore, nodesAfterC }));

  const dbgC = await waitFor(() => mapDebug(page).then((d) => {
    const r = d.routes.filter((x) => x.id === "u-1")[0];
    return r && r.totalPoints === 3 ? r : null;
  }), 8000);
  check("c-polyline-3-points", !!dbgC && dbgC.totalPoints === 3, JSON.stringify(dbgC));
  values.c = { revStart, revC, routePath: routeC, nodes: { before: nodesBefore, after: nodesAfterC }, route: dbgC };

  await page.screenshot({ path: OUT + "/screenshot-route-polyline.png", fullPage: true });

  // ── d. 右键 (1,2) ⇒ 替换（不是追加）：route.path 变成新的 2 点 ──
  const revStartD = await stateRevision(page);
  const beforeD = pathResponses.length;
  await clickHex(page, 1, 2, "right");
  const respD = await waitFor(() => (pathResponses.length > beforeD ? pathResponses[pathResponses.length - 1] : null), 8000);
  const pathD = respD && respD.body ? respD.body.path : [];
  check("d-path-2-points", pathD.length === 2, pathText(pathD));

  const revD = await waitFor(() => stateRevision(page).then((r) => (r > revStartD ? r : null)), 8000);
  check("d-head-advanced", revD === revStartD + 1, JSON.stringify({ revStartD, revD }));
  const routeD = (await api("/api/unit/u-1")).body.movement.route.path;
  check(
    "d-route-replaced-not-appended",
    routeD.length === 2 && routeD[0].r === 1 && routeD[1].r === 2,
    pathText(routeD)
  );
  const dbgD = await waitFor(() => mapDebug(page).then((d) => {
    const r = d.routes.filter((x) => x.id === "u-1")[0];
    return r && r.totalPoints === 2 ? r : null;
  }), 8000);
  check("d-polyline-2-points", !!dbgD && dbgD.totalPoints === 2, JSON.stringify(dbgD));
  values.d = { path: pathD, routePath: routeD, route: dbgD };

  // ── e. 右键图外 hex ⇒ reachable:false、无写、UI 显示「不可达」 ──
  const offMap = { q: 2, r: 1 };
  const offMapPt = await page.evaluate(([qq, rr]) => window.SimosMap.screenPointOf(qq, rr), [offMap.q, offMap.r]);
  const offMapPick = await page.evaluate((pt) => window.SimosMap.hexAtScreen(pt), offMapPt);
  check("e-target-is-off-map", offMapPick && offMapPick.inMap === false, JSON.stringify(offMapPick));

  const nonGetBeforeE = nonGet().length;
  const beforeE = pathResponses.length;
  await clickHex(page, offMap.q, offMap.r, "right");
  const respE = await waitFor(() => (pathResponses.length > beforeE ? pathResponses[pathResponses.length - 1] : null), 8000);
  check("e-reachable-false", !!respE && respE.body && respE.body.reachable === false, JSON.stringify(respE && respE.body));
  check("e-path-empty", !!respE && respE.body && Array.isArray(respE.body.path) && respE.body.path.length === 0, JSON.stringify(respE && respE.body));
  await sleep(700);
  const nonGetAfterE = nonGet().length;
  check("e-no-write", nonGetAfterE === nonGetBeforeE, JSON.stringify({ nonGetBeforeE, nonGetAfterE }));
  const statusE = await editStatus(page);
  check("e-ui-unreachable", statusE.indexOf("不可达") >= 0, JSON.stringify(statusE));
  values.e = { response: respE && respE.body, nonGetBeforeE, nonGetAfterE, status: statusE };

  await page.screenshot({ path: OUT + "/screenshot-unreachable.png", fullPage: true });

  // ── f. 切回「常规查看」⇒ 右键不产生任何非 GET 请求（只读模式不被污染）──
  await clickMode(page, "view");
  const nonGetBeforeF = nonGet().length;
  await clickHex(page, 1, 3, "right");
  await sleep(900);
  const nonGetAfterF = nonGet().length;
  check("f-view-mode-no-write", nonGetAfterF === nonGetBeforeF, JSON.stringify({ nonGetBeforeF, nonGetAfterF }));
  check("f-mode-is-view", (await bodyMode(page)) === "view", String(await bodyMode(page)));
  values.f = { nonGetBeforeF, nonGetAfterF, mode: await bodyMode(page) };

  // ── g. R8 allowlist：全过程非 GET 清单 ⊆ {/api/command,/api/advance,/api/fork} ──
  const list = nonGet();
  const allow = ["/api/command", "/api/advance", "/api/fork"];
  const violations = list.filter((p) => allow.indexOf(p) < 0);
  console.log("NON_GET_LIST=" + JSON.stringify(list));
  check("g-nonget-allowlist", violations.length === 0, JSON.stringify({ list, violations }));
  check("g-command-present", list.indexOf("/api/command") >= 0, JSON.stringify(list));
  values.g = { list, violations };
  writeJson("g-nonget-list.json", list);
  writeJson("b-path.json", values.b);
  writeJson("d-replaced-route.json", values.d);
  writeJson("e-unreachable.json", values.e);

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
