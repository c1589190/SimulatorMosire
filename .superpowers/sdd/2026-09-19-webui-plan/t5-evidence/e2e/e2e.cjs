// M7 T5 e2e —— Playwright 驱动真页面（真 ShellMain --demo），验证左栏详情 + 单位编制倒树（判据③/⑥）。
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

const failures = [];

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

async function waitMapReady(page, timeout) {
  await page.waitForFunction(
    () => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(),
    null,
    { timeout: timeout || 30000 }
  );
}

async function selection(page) {
  return page.evaluate(() => window.SimosApp.getState().selection);
}

async function domNodes(page) {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll("#unit-tree-mount .tree-node")).map((n) => ({
      id: n.getAttribute("data-unit-id"),
      depth: Number(n.getAttribute("data-depth")),
      parent: n.getAttribute("data-parent") || null,
      branch: n.getAttribute("data-branch") === "true",
    }))
  );
}

function byId(list) {
  const map = {};
  list.forEach((n) => {
    map[n.id] = n;
  });
  return map;
}

// 冻结期望（手写；不来自页面里跑的 buildTree，否则 m1 自洽假绿）。
const EXPECT = {
  "u-1": { depth: 0, parent: null, branch: false },
  "t5-a": { depth: 0, parent: null, branch: true },
  "t5-b": { depth: 1, parent: "t5-a", branch: true },
  "t5-c": { depth: 2, parent: "t5-b", branch: false },
  "t5-d": { depth: 2, parent: "t5-b", branch: false },
  "t5-e": { depth: 1, parent: "t5-a", branch: false },
  "t5-f": { depth: 3, parent: "t5-c", branch: false },
};

const SEED = [
  { id: "t5-a", name: "甲部", position: { q: 2, r: 2 }, member: 100, equipment: { infantry: 1 }, speed: 1, mobilityPerMille: 1000, parent: null },
  { id: "t5-b", name: "乙部", position: { q: 2, r: 3 }, member: 80, equipment: { infantry: 1 }, speed: 1, mobilityPerMille: 1000, parent: "t5-a" },
  { id: "t5-c", name: "丙队", position: { q: 2, r: 4 }, member: 50, equipment: { cavalry: 1 }, speed: 2, mobilityPerMille: 1500, parent: "t5-b" },
  { id: "t5-d", name: "丁队", position: { q: 3, r: 2 }, member: 40, equipment: { artillery: 2 }, speed: 1, mobilityPerMille: 800, parent: "t5-b" },
  { id: "t5-e", name: "戊队", position: { q: 3, r: 3 }, member: 30, equipment: {}, speed: 1, mobilityPerMille: 900, parent: "t5-a" },
  { id: "t5-f", name: "己组", position: { q: 2, r: 5 }, member: 20, equipment: { scout: 1 }, speed: 3, mobilityPerMille: 2000, parent: "t5-c" },
];

async function run(page) {
  // ── seed：用既有 unit.CreateUnit 造 A→B→C→F 链 + B 分岔点（两子 C/D），全经 /api/command ──
  let stateBody = null;
  for (let i = 0; i < 60; i++) {
    stateBody = await api("/api/state");
    if (stateBody.body && stateBody.body.heads && stateBody.body.heads.main) {
      break;
    }
    await sleep(250);
  }
  let head = stateBody.body.heads.main;
  const head0 = head;
  for (const payload of SEED) {
    const response = await api(
      "/api/command",
      jsonPost({
        type: "unit.CreateUnit",
        payloadJson: JSON.stringify(payload),
        branch: "main",
        expectedRevision: head,
      })
    );
    if (response.status !== 200 || !response.body || !response.body.ref) {
      check("seed", false, payload.id + " status=" + response.status + " " + response.text.slice(0, 160));
      return;
    }
    head = response.body.ref.revision;
  }
  check("seed", head === head0 + SEED.length, "head " + head0 + "->" + head);

  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await waitMapReady(page, 30000);
  await page.waitForFunction(
    () => document.getElementById("map-status").textContent.indexOf("已载入") >= 0,
    null,
    { timeout: 15000 }
  );
  await page.waitForSelector('.tree-node[data-unit-id="t5-a"]', { timeout: 10000 });
  await sleep(300);

  // ── ① 树结构：DOM 的父子/深度 == buildTree 的期望，且**逐节点**等于冻结期望 ──
  const unitsBody = await api("/api/units?branch=main&revision=" + head);
  const built = await page.evaluate((units) => {
    const flat = window.SimosUnitTree.flatten(window.SimosUnitTree.buildTree(units));
    return flat.map((n) => ({
      id: n.id,
      depth: n.depth,
      parent: n.parent === null || n.parent === undefined ? null : String(n.parent),
      branch: n.branch,
    }));
  }, unitsBody.body.units);
  const dom = await domNodes(page);
  const builtMap = byId(built);
  const domMap = byId(dom);
  const idsMatch =
    Object.keys(builtMap).length === Object.keys(domMap).length &&
    Object.keys(builtMap).every((id) => domMap[id]);
  const perNodeOk = Object.keys(EXPECT).every((id) => {
    const e = EXPECT[id];
    const d = domMap[id];
    const b = builtMap[id];
    return (
      d &&
      b &&
      d.depth === e.depth &&
      d.parent === e.parent &&
      b.depth === e.depth &&
      b.parent === e.parent
    );
  });
  check(
    "tree-structure",
    idsMatch && perNodeOk,
    JSON.stringify({ dom: dom.sort((a, b) => a.id.localeCompare(b.id)), builtCount: built.length })
  );
  writeJson("tree-dom.json", dom);
  writeJson("tree-built.json", built);

  // ── ② 分岔点识别：子数 ≥ 2 为真、< 2 为假（含单子 t5-c、叶 t5-d/t5-e/t5-f）──
  const branchOk = Object.keys(EXPECT).every(
    (id) => domMap[id] && domMap[id].branch === EXPECT[id].branch && builtMap[id].branch === EXPECT[id].branch
  );
  check(
    "branch-recognition",
    branchOk,
    JSON.stringify(
      Object.keys(EXPECT).map((id) => ({
        id,
        dom: domMap[id] && domMap[id].branch,
        built: builtMap[id] && builtMap[id].branch,
        expected: EXPECT[id].branch,
      }))
    )
  );

  // ── ③ 分岔点视觉加粗放大：t5-b（分岔）vs t5-c（单子非分岔）──
  const styles = await page.evaluate(() => {
    const b = document.querySelector('.tree-node[data-unit-id="t5-b"]');
    const l = document.querySelector('.tree-node[data-unit-id="t5-c"]');
    const sb = getComputedStyle(b);
    const sl = getComputedStyle(l);
    return {
      branch: { weight: Number(sb.fontWeight), size: parseFloat(sb.fontSize) },
      leaf: { weight: Number(sl.fontWeight), size: parseFloat(sl.fontSize) },
    };
  });
  check(
    "branch-visual",
    styles.branch.weight >= 700 && styles.branch.size > styles.leaf.size + 1,
    JSON.stringify(styles)
  );
  await page.screenshot({ path: OUT + "/screenshot-tree.png" });

  // ── ④ 点分岔点 ⇒ 该分支展开（可见行数 0 → 4：t5-b + t5-c/t5-d/t5-f）──
  const detailState = () =>
    page.evaluate(() => {
      const d = document.querySelector('.tree-branch-detail[data-for="t5-b"]');
      const rows = Array.from(d.querySelectorAll(".branch-row"));
      return {
        hidden: d.hidden,
        rows: rows.length,
        visibleRows: rows.filter((r) => r.offsetParent !== null).length,
        rowIds: rows.map((r) => r.getAttribute("data-unit-id")),
      };
    });
  const beforeExpand = await detailState();
  await page.click('.tree-node[data-unit-id="t5-b"]');
  await sleep(250);
  const afterExpand = await detailState();
  check(
    "branch-expand",
    beforeExpand.hidden === true &&
      beforeExpand.visibleRows === 0 &&
      afterExpand.hidden === false &&
      afterExpand.visibleRows === 4 &&
      afterExpand.rows === 4 &&
      JSON.stringify(afterExpand.rowIds.sort()) === JSON.stringify(["t5-b", "t5-c", "t5-d", "t5-f"]),
    JSON.stringify({ beforeExpand, afterExpand })
  );
  await page.screenshot({ path: OUT + "/screenshot-branch-expanded.png" });

  // ── ⑤ 点叶子 ⇒ 左栏详情 == /api/unit/{id}（判据③ 单位详情完整性）──
  await page.click('.tree-node[data-unit-id="t5-f"]');
  await page
    .waitForFunction(
      () => document.getElementById("selection-detail").textContent.indexOf("t5-f") >= 0,
      null,
      { timeout: 5000 }
    )
    .catch(() => {});
  await sleep(200);
  const detailText = await page.evaluate(
    () => document.getElementById("selection-detail").textContent
  );
  const unitF = await api("/api/unit/t5-f?branch=main&revision=" + head);
  const uf = unitF.body;
  const equipText = Object.keys(uf.equipment)
    .map((k) => k + "=" + uf.equipment[k])
    .join("；");
  const detailOk =
    detailText.indexOf(uf.id) >= 0 &&
    detailText.indexOf(uf.name) >= 0 &&
    detailText.indexOf(String(uf.member)) >= 0 &&
    detailText.indexOf(String(uf.speed)) >= 0 &&
    detailText.indexOf(String(uf.mobilityPerMille)) >= 0 &&
    detailText.indexOf(equipText) >= 0 &&
    detailText.indexOf(uf.parent) >= 0 &&
    detailText.indexOf("q=" + uf.position.q + ", r=" + uf.position.r) >= 0 &&
    detailText.indexOf(String(uf.movement)) >= 0;
  check(
    "leaf-detail",
    detailOk,
    JSON.stringify({ detailText, api: uf, equipText })
  );

  // ── ⑥ 地图点单位 ⇒ 左栏切单位详情 + 树定位到该节点（判据③/⑤）──
  await page.evaluate(() => window.SimosMap.resetView());
  await sleep(150);
  const pt = await page.evaluate(() => window.SimosMap.screenPointOf(1, 1));
  const box = await page.locator("#canvas").boundingBox();
  await page.mouse.click(box.x + pt.x, box.y + pt.y);
  await page
    .waitForFunction(
      () => {
        const s = window.SimosApp.getState().selection;
        return s && s.kind === "unit" && s.id === "u-1";
      },
      null,
      { timeout: 5000 }
    )
    .catch(() => {});
  const selMap = await selection(page);
  await page
    .waitForFunction(
      () => document.getElementById("selection-detail").textContent.indexOf("u-1") >= 0,
      null,
      { timeout: 5000 }
    )
    .catch(() => {});
  await sleep(150);
  const selectedInTree = await page.evaluate(() => {
    const n = document.querySelector('.tree-node[data-unit-id="u-1"]');
    return !!n && n.classList.contains("selected");
  });
  check(
    "map-unit-link",
    !!selMap && selMap.kind === "unit" && selMap.id === "u-1" && selectedInTree,
    JSON.stringify({ selMap, selectedInTree })
  );

  // ── ⑦ 只读目标仍带 ?branch=&revision=：拖游标到旧 revision(1) ⇒ 树/详情来自旧 revision ──
  await page.waitForSelector(
    '.timeline-line[data-branch="main"] .tl-node[data-revision="' + head + '"]',
    { timeout: 10000 }
  );
  const unitReqs = [];
  page.on("request", (r) => {
    if (r.url().indexOf("/api/units") >= 0) {
      unitReqs.push(r.url());
    }
  });
  // ★ 游标移动用时间轴的 **click 处理器**（点节点 = scrubTo），不用固定坐标拖拽：
  //   本页时间轴在**文档流底部**，左栏树随 revision 变矮会让它上移（T5 实测：拖拽过程中
  //   line y 791→892，固定 y 的 pointermove 会滑出轨道 ⇒ pointerleave 终止拖拽，只到 rev6）。
  //   T3 的 e2e 已单独覆盖拖拽（R1/R2）；T5 这一步要证的是"游标到旧 revision ⇒ 面板带 revision"。
  await page.click('.timeline-line[data-branch="main"] .tl-node[data-revision="1"]');
  await page.waitForFunction(() => window.SimosApp.getState().revision === 1, null, {
    timeout: 5000,
  });
  await page
    .waitForFunction(
      () => document.querySelectorAll("#unit-tree-mount .tree-node").length === 1,
      null,
      { timeout: 5000 }
    )
    .catch(() => {});
  await sleep(300);
  const treeAt1 = await page.evaluate(() =>
    Array.from(document.querySelectorAll("#unit-tree-mount .tree-node")).map((n) =>
      n.getAttribute("data-unit-id")
    )
  );
  check(
    "revision-target-tree",
    JSON.stringify(treeAt1) === JSON.stringify(["u-1"]) &&
      unitReqs.some((u) => u.indexOf("revision=1") >= 0),
    JSON.stringify({ treeAt1, unitReqs: unitReqs.slice() })
  );

  const unitReqs2 = [];
  page.on("request", (r) => {
    if (r.url().indexOf("/api/unit/u-1") >= 0) {
      unitReqs2.push(r.url());
    }
  });
  // 先把选择切到 hex（真点地图）⇒ 再点单位会**真的重取**（否则 selection 未变，panels 的 key 去重不发请求）。
  await page.evaluate(() => window.SimosMap.resetView());
  await sleep(150);
  const hexPt = await page.evaluate(() => window.SimosMap.screenPointOf(1, 2));
  const canvas = await page.locator("#canvas").boundingBox();
  await page.mouse.click(canvas.x + hexPt.x, canvas.y + hexPt.y);
  await page
    .waitForFunction(
      () => {
        const s = window.SimosApp.getState().selection;
        return s && s.kind === "hex";
      },
      null,
      { timeout: 5000 }
    )
    .catch(() => {});
  await page.click('.tree-node[data-unit-id="u-1"]');
  await page
    .waitForFunction(
      () => document.getElementById("left-status").textContent.indexOf("main@1") >= 0,
      null,
      { timeout: 5000 }
    )
    .catch(() => {});
  await sleep(200);
  const detailAt1 = await page.evaluate(
    () => document.getElementById("selection-detail").textContent
  );
  check(
    "revision-target-detail",
    unitReqs2.some((u) => u.indexOf("/api/unit/u-1") >= 0 && u.indexOf("revision=1") >= 0) &&
      detailAt1.indexOf("u-1") >= 0 &&
      (await page.evaluate(() => document.getElementById("left-status").textContent)).indexOf(
        "main@1"
      ) >= 0,
    JSON.stringify({
      unitReqs2,
      detailAt1,
      status: await page.evaluate(() => document.getElementById("left-status").textContent),
    })
  );
  writeJson("revision-unit-requests.json", unitReqs2);
}

(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: CHROME });
  // ★ 1920 宽：时间轴 7 个节点（bootstrap + 6 次 CreateUnit）在窄视口下会横向溢出，
  //   末端节点被裁掉 ⇒ 拖拽起点落在轨道外、pointerdown 不落在 .timeline-line 上（T5 实测过一次）。
  const page = await browser.newPage({ viewport: { width: 1920, height: 1000 } });
  page.on("console", (msg) => {
    if (msg.type() === "warning" || msg.type() === "error") {
      console.log("PAGE-" + msg.type().toUpperCase() + ": " + msg.text());
    }
  });
  page.on("pageerror", (err) => {
    console.log("PAGE-ERROR: " + (err && err.message ? err.message : err));
  });
  try {
    await run(page);
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
