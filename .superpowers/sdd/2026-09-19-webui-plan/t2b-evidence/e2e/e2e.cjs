// M7b T2 e2e —— 移动可见化：movement 对象（API 三处同形）/ 左栏 MP·成本·ETA / Canvas 路线折线（淡+亮分层）/ advance 后前进。
// 用法: NODE_PATH=<playwright> node e2e.cjs <base-url> <store-dir> <out-dir>
// ★ 退出码：0 = 全部必测步骤 PASS；1 = 有 FAIL（变异轮据此观察红点）。
"use strict";

const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const STORE = process.argv[3];
const OUT = process.argv[4];
const DB = STORE + "/simos.db";

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

async function command(type, payload, expectedRevision) {
  return api(
    "/api/command",
    jsonPost({
      type: type,
      payloadJson: JSON.stringify(payload),
      branch: "main",
      expectedRevision: expectedRevision,
    })
  );
}

function panelRows(page) {
  return page.evaluate(() => {
    const dl = document.getElementById("selection-detail");
    const out = {};
    if (!dl) {
      return out;
    }
    const dts = dl.querySelectorAll("dt");
    const dds = dl.querySelectorAll("dd");
    for (let i = 0; i < dts.length; i++) {
      out[dts[i].textContent] = dds[i] ? dds[i].textContent : "";
    }
    return out;
  });
}

function mapDebug(page) {
  return page.evaluate(() => window.SimosMap.debug());
}

function selectUnit(page, id) {
  return page.evaluate((unitId) => window.SimosApp.setSelection({ kind: "unit", id: unitId }), id);
}

async function waitPanelContains(page, text, timeout) {
  await page
    .waitForFunction(
      (needle) => {
        const dl = document.getElementById("selection-detail");
        return dl && dl.textContent.indexOf(needle) >= 0;
      },
      text,
      { timeout: timeout || 15000 }
    )
    .catch(() => {});
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

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const executablePath =
    process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
  const browser = await chromium.launch({ headless: true, executablePath });
  const page = await browser.newPage({ viewport: { width: 1600, height: 1100 } });
  const pageErrors = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));

  // ── a. 外部 POST unit.PlanRoute（逐格相邻 (1,1)->(1,2)->(1,3)）──
  const stateA = (await api("/api/state")).body;
  const headA = stateA.heads.main;
  const r1 = await command(
    "unit.PlanRoute",
    {
      id: "u-1",
      waypoints: [
        { q: 1, r: 1 },
        { q: 1, r: 2 },
        { q: 1, r: 3 },
      ],
    },
    headA
  );
  check(
    "a-plan-route-committed",
    r1.status === 200 && r1.body && r1.body.result === "committed" && r1.body.ref.revision === headA + 1,
    JSON.stringify({ status: r1.status, body: r1.body })
  );

  // ── b. movement 是对象（/api/unit/{id} 与 /api/units 同形）──
  const unitB = (await api("/api/unit/u-1")).body;
  const m = unitB.movement;
  check(
    "b-movement-is-object",
    m !== null && typeof m === "object" && !!m.route && Array.isArray(m.route.path),
    JSON.stringify({ movement: m })
  );
  check("b-route-path-3", m.route.path.length === 3, JSON.stringify(m.route.path));
  check(
    "b-route-path-values",
    m.route.path[0].q === 1 &&
      m.route.path[0].r === 1 &&
      m.route.path[1].q === 1 &&
      m.route.path[1].r === 2 &&
      m.route.path[2].r === 3,
    JSON.stringify(m.route.path)
  );
  check("b-departed-at-tick", m.departedAt.tick === 5, JSON.stringify(m.departedAt));
  check("b-speed-at-departure", m.speedAtDeparture === 2, String(m.speedAtDeparture));
  check(
    "b-mobility-at-departure",
    m.mobilityPerMilleAtDeparture === 500,
    String(m.mobilityPerMilleAtDeparture)
  );
  check("b-status-in-transit", m.status === "IN_TRANSIT", String(m.status));
  check(
    "b-current-hex",
    m.currentHex.q === 1 && m.currentHex.r === 1,
    JSON.stringify(m.currentHex)
  );
  check("b-next-hex", m.nextHex.q === 1 && m.nextHex.r === 2, JSON.stringify(m.nextHex));
  check("b-remaining-1500", m.remainingMillis === 1500, String(m.remainingMillis));

  const listB = (await api("/api/units")).body.units;
  const listed = listB.filter((u) => u.id === "u-1")[0];
  check(
    "b-list-same-shape",
    !!listed && listed.movement && listed.movement.route.path.length === 3,
    JSON.stringify(listed ? listed.movement : null)
  );
  values.b = { movement: m };
  writeJson("b-movement-object.json", m);

  // ── 打开工作台（此时 head=rev2，页面首帧即含路线）──
  await openWorkbench(page);
  await selectUnit(page, "u-1");
  await waitPanelContains(page, "预计到达 tick");
  const rowsC = await panelRows(page);
  check(
    "c-panel-step-cost-1500",
    rowsC["路线每格成本（毫 MP）"] === "1500",
    JSON.stringify(rowsC)
  );
  check("c-panel-budget-2000", rowsC["本 tick 预算（毫 MP）"] === "2000", rowsC["本 tick 预算（毫 MP）"]);
  check("c-panel-total-cost-3000", rowsC["路线总成本（毫 MP）"] === "3000", rowsC["路线总成本（毫 MP）"]);
  check("c-panel-status", rowsC["status"] === "IN_TRANSIT", rowsC["status"]);
  check("c-panel-current-hex", rowsC["currentHex"] === "q=1, r=1", rowsC["currentHex"]);
  check("c-panel-next-hex", rowsC["nextHex"] === "q=1, r=2", rowsC["nextHex"]);
  check("c-panel-remaining", rowsC["remainingMillis"] === "1500", rowsC["remainingMillis"]);
  // ETA = departedAt.tick + ceil(总成本/速率) = 5 + ceil(3000/2000) = 7
  check("c-panel-eta-7", rowsC["预计到达 tick"] === "7", rowsC["预计到达 tick"]);
  check("c-panel-speed", rowsC["speed"] === "2", rowsC["speed"]);
  check("c-panel-mobility", rowsC["mobilityPerMille"] === "500", rowsC["mobilityPerMille"]);
  values.c = rowsC;

  // ── d. Canvas 折线：整条淡色 + 未走完亮色（钩子：debug().routes）──
  const dbgD = await mapDebug(page);
  const routeD = dbgD.routes.filter((r) => r.id === "u-1")[0];
  check("d-route-count-1", dbgD.routeCount === 1, String(dbgD.routeCount));
  check(
    "d-polyline-points",
    !!routeD && routeD.totalPoints === 3 && routeD.remainingPoints === 3,
    JSON.stringify(routeD)
  );
  check(
    "d-color-layering",
    !!routeD &&
      routeD.baseColor === "rgba(255, 214, 130, 0.35)" &&
      routeD.remainingColor === "#ffd27a" &&
      routeD.baseColor !== routeD.remainingColor,
    JSON.stringify(routeD ? { base: routeD.baseColor, remaining: routeD.remainingColor } : null)
  );
  check(
    "d-route-state",
    !!routeD && routeD.status === "IN_TRANSIT" && routeD.currentHex.r === 1 && routeD.nextHex.r === 2,
    JSON.stringify(routeD)
  );
  values.d = routeD;
  writeJson("d-route-debug.json", routeD);

  await page.screenshot({ path: OUT + "/screenshot-route-map.png", fullPage: true });
  const left = await page.locator("#left-panel").boundingBox();
  await page.screenshot({
    path: OUT + "/screenshot-movement-panel.png",
    clip: left || undefined,
  });

  // ── e. 推进一格：currentHex 前进、remainingMillis 变化、剩余段变短 ──
  const adv = await api(
    "/api/advance",
    jsonPost({ branch: "main", expectedRevision: headA + 1, from: 5, to: 6 })
  );
  check(
    "e-advance-committed",
    adv.status === 200 && adv.body && adv.body.result === "committed",
    JSON.stringify({ status: adv.status, body: adv.body })
  );

  const unitE = (await api("/api/unit/u-1")).body;
  const me = unitE.movement;
  check(
    "e-current-hex-advanced",
    me.currentHex.q === 1 && me.currentHex.r === 2,
    JSON.stringify(me.currentHex)
  );
  check("e-next-hex-advanced", me.nextHex.q === 1 && me.nextHex.r === 3, JSON.stringify(me.nextHex));
  check("e-remaining-changed", me.remainingMillis === 1000, String(me.remainingMillis));

  // 页面重取到 rev3 后再读面板与折线
  await page.reload({ waitUntil: "networkidle" });
  await waitMapReady(page);
  await selectUnit(page, "u-1");
  await waitPanelContains(page, "预计到达 tick");
  const rowsE = await panelRows(page);
  check("e-panel-current-hex", rowsE["currentHex"] === "q=1, r=2", rowsE["currentHex"]);
  check("e-panel-next-hex", rowsE["nextHex"] === "q=1, r=3", rowsE["nextHex"]);
  check("e-panel-remaining", rowsE["remainingMillis"] === "1000", rowsE["remainingMillis"]);

  const dbgE = await mapDebug(page);
  const routeE = dbgE.routes.filter((r) => r.id === "u-1")[0];
  check(
    "e-polyline-remaining-shorter",
    !!routeE && routeE.totalPoints === 3 && routeE.remainingPoints === 2,
    JSON.stringify(routeE)
  );
  check(
    "e-route-state-advanced",
    !!routeE && routeE.currentHex.r === 2 && routeE.nextHex.r === 3,
    JSON.stringify(routeE)
  );
  values.e = { unit: me, panel: rowsE, route: routeE };
  writeJson("e-after-advance.json", values.e);

  // ── f. 无路线单位：读数为空 / 不画线 / 不报错 ──
  const headE = (await api("/api/state")).body.heads.main;
  const r2 = await command(
    "unit.CreateUnit",
    {
      id: "u-2",
      name: "无路线连",
      position: { q: 1, r: 3 },
      member: 50,
      equipment: {},
      speed: 2,
      mobilityPerMille: 500,
    },
    headE
  );
  check("f-create-unit", r2.status === 200 && r2.body.result === "committed", JSON.stringify(r2.body));

  const u2 = (await api("/api/unit/u-2")).body;
  check("f-api-movement-null", u2.movement === null, JSON.stringify({ movement: u2.movement }));
  const list2 = (await api("/api/units")).body.units.filter((u) => u.id === "u-2")[0];
  check("f-api-list-movement-null", list2.movement === null, JSON.stringify(list2.movement));

  await page.reload({ waitUntil: "networkidle" });
  await waitMapReady(page);
  await selectUnit(page, "u-2");
  await waitPanelContains(page, "无（无在途路线）");
  const rowsF = await panelRows(page);
  check(
    "f-panel-empty-readout",
    rowsF["movement"] === "无（无在途路线）",
    JSON.stringify(rowsF["movement"])
  );
  const dbgF = await mapDebug(page);
  check(
    "f-no-line-for-u2",
    dbgF.routes.filter((r) => r.id === "u-2").length === 0 && dbgF.routeCount === 1,
    JSON.stringify({ routeCount: dbgF.routeCount, ids: dbgF.routes.map((r) => r.id) })
  );
  check(
    "f-u1-line-still-drawn",
    dbgF.routes.filter((r) => r.id === "u-1").length === 1,
    JSON.stringify(dbgF.routes.map((r) => r.id))
  );
  values.f = { apiMovement: u2.movement, panel: rowsF["movement"], routeIds: dbgF.routes.map((r) => r.id) };

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
