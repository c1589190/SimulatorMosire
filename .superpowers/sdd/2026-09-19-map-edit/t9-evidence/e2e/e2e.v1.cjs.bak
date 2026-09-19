// M8 T11 e2e —— 地图编辑的连通性（河流/道路）+ 圈选随机化（真 ShellMain --demo、真浏览器、真 pointer 事件）。
//
// 阶段：
//   A  preflight（**零写**）：工具选择器结构 / 无预选 / 旧"归 T11"痕迹清除 / 五模式左键 0 写；
//   B  e1 未选 mode + 右键连边 ⇒ **0 写**（Q2 的 UI 侧；m1 的杀点）；
//   C  e2 merge 连边 ⇒ **恰 1 条** map.SetEdge（载荷 mode 原样 = merge）；s2 merge road ⇒ river 仍在（★）；
//      s3 replace river **[E1]**（payload 里**不含** E2）⇒ 全图该 kind 整份覆盖：E2 的 river 消失、road 一字不动
//      （★ E2 放进 payload 测不到这条——replace 会把它加回来，见 logs/t11-expectations-corrections.md）；
//   D  x1 非相邻跳（(1,2) → 绕图外 → (1,3)）⇒ **0 写**（前端的相邻性护栏；m3 的杀点）；
//   E  randomize：圈选 0 写 / 空 seed 0 写 / 同 seed 逐字节相同 / 换 seed 不同 / 回到原 seed 逐字节相同（m2 的杀点）；
//   F  g2 地形刷未被抢走：切回地形刷 + 右键拖 ⇒ **恰 1 条** map.SetTerrain。
//
// ★ 本机没有真档 ⇒ 全程跑在 `--demo` 空库起的演示世界上（3 格走廊、0 边、无区域）。
//   报告里必须写明这一条；**不对真档作任何断言**。
// ★ 演示世界 (1,1) 上有单位 u-1 ⇒ `pickAt` 对它是 kind:"unit" ⇒ `paintAt`/`edgeAt` **都跳过**
//   ⇒ 指针能碰到的只有 (1,2)/(1,3)（"UI 只能选 2 格"是实测事实，不是选择）。
// 用法: NODE_PATH=<playwright> node e2e.cjs <base-url> <out-dir>
"use strict";

const fs = require("node:fs");
const crypto = require("node:crypto");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const OUT = process.argv[3] || ".";
const VW = 1280;
const VH = 800;
const PW_CHROMIUM =
  process.env.PW_CHROMIUM || "/home/dev/.cache/ms-playwright/chromium-1244/chrome-linux64/chrome";

const failures = [];
const values = {};
const nonGet = [];
const phases = {};

function md5(text) {
  return crypto.createHash("md5").update(text, "utf8").digest("hex");
}

function check(name, ok, detail) {
  console.log("STEP " + name + ": " + (ok ? "PASS" : "FAIL") + (detail ? " " + detail : ""));
  if (!ok) {
    failures.push(name);
  }
}

function writeJson(name, value) {
  fs.writeFileSync(OUT + "/" + name, JSON.stringify(value, null, 2));
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function parse(text) {
  try {
    return JSON.parse(text);
  } catch (e) {
    return null;
  }
}

async function api(path) {
  const response = await fetch(BASE + path);
  const text = await response.text();
  return { status: response.status, text: text, body: parse(text) };
}

function markPhase(name) {
  phases[name] = nonGet.length;
}

function since(phase) {
  return nonGet.slice(phases[phase] || 0);
}

function commandPosts(phase, type) {
  return since(phase).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && (type ? body.type === type : true);
  });
}

function payloadOf(post) {
  const outer = parse(post.post) || {};
  return parse(outer.payloadJson);
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });

  // ── 装置自证：起服务前必须是非 GET 为空、页面能起来 ────────────────────────────
  const boot = await api("/");
  check("a0-page-served", boot.status === 200 && boot.text.indexOf('id="map-edit-tools"') >= 0, "status=" + boot.status + " bytes=" + boot.text.length);

  const browser = await chromium.launch({ headless: true, executablePath: PW_CHROMIUM });
  const page = await browser.newPage({ viewport: { width: VW, height: VH } });
  const pageErrors = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));
  page.on("request", (req) => {
    if (req.method() !== "GET") {
      const parsed = new URL(req.url());
      nonGet.push({ method: req.method(), path: parsed.pathname, post: req.postData() });
    }
  });

  await page.goto(BASE + "/", { waitUntil: "domcontentloaded" });
  await page.waitForFunction(() => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(), null, { timeout: 60000 });
  await sleep(400);

  async function canvasBox() {
    return await page.locator("#canvas").boundingBox();
  }

  /** 视口坐标（#canvas 是全屏浮层底图 ⇒ 其 boundingBox 就是视口）。 */
  async function recenter() {
    await page.evaluate(
      ([q, r, s, cx, cy]) => {
        const M = window.SimosMap;
        const w = M.hexToPixel(q, r, M.BASE_CELL);
        M.setView({ scale: s, tx: cx - w.x * s, ty: cy - w.y * s });
      },
      [1, 2, 3.5, 620, 360]
    );
    await sleep(200);
  }

  async function pointOf(q, r) {
    return await page.evaluate(([a, b]) => window.SimosMap.screenPointOf(a, b), [q, r]);
  }

  /** 指针下的元素是不是画布（这一条防的是"浮层挡住 ⇒ 点击静默无效"，M7 T7 的坑）。 */
  async function hitIsCanvas(x, y) {
    return await page.evaluate(
      ([px, py]) => {
        const el = document.elementFromPoint(px, py);
        return el ? el.id || el.tagName : "none";
      },
      [x, y]
    );
  }

  /** 右键拖动连边/选区的轨迹：逐个 waypoint 用 steps:1（**只派发端点**）⇒ 轨迹 = 我喂进去的那串格。 */
  async function rightDrag(points, box) {
    await page.mouse.move(box.x + points[0].x, box.y + points[0].y);
    await page.mouse.down({ button: "right" });
    for (let i = 1; i < points.length; i++) {
      await page.mouse.move(box.x + points[i].x, box.y + points[i].y, { steps: 1 });
      await sleep(30);
    }
    await page.mouse.up({ button: "right" });
    await sleep(400);
  }

  async function leftDrag(box, from, to) {
    await page.mouse.move(box.x + from.x, box.y + from.y);
    await page.mouse.down({ button: "left" });
    await page.mouse.move(box.x + to.x, box.y + to.y, { steps: 8 });
    await page.mouse.up({ button: "left" });
    await sleep(250);
  }

  async function setTool(value) {
    await page.check('input[name="map-edit-tool"][value="' + value + '"]');
    await sleep(250);
  }

  async function setMode(value) {
    await page.selectOption("#edge-mode", value);
    await sleep(200);
  }

  async function debug() {
    return await page.evaluate(() => window.SimosMap.mapEditDebug());
  }

  /** 某个节点的可见文本（状态行是"这条护栏真的跑了"的证据，比布尔更抗造假）。 */
  async function textOf(id) {
    return await page.evaluate((nodeId) => {
      const n = document.getElementById(nodeId);
      return n ? n.textContent : null;
    }, id);
  }

  async function head() {
    return await page.evaluate(() => window.SimosApp.getState().revision);
  }

  /** 一次写之后：等 head 前进（失败就如实睡过去，不当断言）。 */
  async function waitHeadChange(before) {
    await page
      .waitForFunction((h) => window.SimosApp.getState().revision !== h, before, { timeout: 15000 })
      .catch(() => null);
    await sleep(600);
  }

  // ══════════════ A preflight（零写） ══════════════
  await page.click('#mode-bar button[data-mode="map-edit"]');
  await sleep(400);
  await recenter();
  let box = await canvasBox();

  const a1 = await debug();
  const toolCount = await page.evaluate(
    () => document.querySelectorAll('#map-edit-tools input[name="map-edit-tool"]').length
  );
  const toolValues = await page.evaluate(() =>
    Array.prototype.map.call(
      document.querySelectorAll('#map-edit-tools input[name="map-edit-tool"]'),
      (i) => i.value
    )
  );
  values.tools = { count: toolCount, values: toolValues, checked: a1.tool, hostTool: a1.hostTool };
  check(
    "a1-tool-selector-has-four-tools",
    toolCount === 4 && JSON.stringify(toolValues) === JSON.stringify(["terrain", "river", "road", "randomize"]),
    JSON.stringify(values.tools)
  );

  values.defaults = {
    tool: a1.tool,
    edgeControlsVisible: a1.edgeControlsVisible,
    randomizeControlsVisible: a1.randomizeControlsVisible,
    edgeMode: a1.edgeMode,
    seedText: a1.randomizeSeedText,
  };
  check(
    "a2-default-terrain-and-params-hidden",
    a1.tool === "terrain" && a1.edgeControlsVisible === false && a1.randomizeControlsVisible === false,
    JSON.stringify(values.defaults)
  );
  check("a3-edge-mode-empty-no-preselect", a1.edgeMode === "", "edgeMode=" + JSON.stringify(a1.edgeMode));

  // ★ "痕迹清除"以**服务端发出来的 HTML 原文**为准，不看活 DOM —— 活 DOM 里时间轴会自己注入
  //   置灰按钮（M7 判据②），拿它数"置灰按钮=0"会量到无关的东西。
  const traces = {
    servedHasDataPending: boot.text.indexOf("data-pending") >= 0,
    servedHasGuiT11: boot.text.indexOf("归 T11") >= 0,
  };
  values.pendingTraces = traces;
  check(
    "a4-old-t11-placeholders-gone",
    traces.servedHasDataPending === false && traces.servedHasGuiT11 === false && toolCount === 4,
    JSON.stringify(traces)
  );

  // ★ 五模式左键拖动 ⇒ 每条 0 写（M8-R 硬约束）
  const perModeLeft = [];
  for (const m of ["view", "region", "map-edit", "region-edit", "unit"]) {
    await page.click('#mode-bar button[data-mode="' + m + '"]');
    await sleep(250);
    await recenter();
    const b = await canvasBox();
    const before = await page.evaluate(() => window.SimosMap.currentView());
    const idx = nonGet.length;
    await leftDrag(b, { x: 620, y: 360 }, { x: 700, y: 420 });
    const after = await page.evaluate(() => window.SimosMap.currentView());
    perModeLeft.push({
      mode: m,
      moved: after.tx !== before.tx || after.ty !== before.ty,
      nonGet: nonGet.slice(idx).map((r) => r.method + " " + r.path),
    });
  }
  values.leftPanAllModes = perModeLeft;
  console.log("LEFT_PAN_ALL_MODES " + JSON.stringify(perModeLeft));
  check(
    "a5-five-modes-left-drag-zero-write",
    perModeLeft.length === 5 && perModeLeft.every((r) => r.nonGet.length === 0),
    JSON.stringify(perModeLeft.map((r) => r.mode + ":" + r.nonGet.length))
  );

  await page.click('#mode-bar button[data-mode="map-edit"]');
  await sleep(300);
  await recenter();
  box = await canvasBox();
  const boxSane = box.x >= 0 && box.y >= 0 && box.width > 0 && box.height > 0 && box.y + box.height <= VH + 1;
  values.canvasBox = { box: box, viewport: { w: VW, h: VH } };
  check("a6-canvas-box-in-viewport", boxSane, JSON.stringify(values.canvasBox));

  // 三个取样点必须hit到画布（否则后面的拖动是静默无效的）
  const p12 = await pointOf(1, 2);
  const p13 = await pointOf(1, 3);
  const hits = {
    at12: await hitIsCanvas(box.x + p12.x, box.y + p12.y),
    at13: await hitIsCanvas(box.x + p13.x, box.y + p13.y),
  };
  values.pointHits = hits;
  check("a7-hex-centers-hit-canvas", hits.at12 === "canvas" && hits.at13 === "canvas", JSON.stringify(hits));
  check("a8-preflight-zero-write", nonGet.filter((r) => r.path === "/api/command").length === 0, "commands=" + nonGet.filter((r) => r.path === "/api/command").length);

  // ══════════════ B e1：未选 mode + 右键连边 ⇒ 0 写 ══════════════
  await setTool("river");
  const b1 = await debug();
  values.riverTool = b1;
  check(
    "b0-river-tool-shows-edge-controls",
    b1.edgeControlsVisible === true && b1.randomizeControlsVisible === false && b1.edgeMode === "" && b1.hostTool === "river",
    JSON.stringify({ vis: b1.edgeControlsVisible, rnd: b1.randomizeControlsVisible, mode: b1.edgeMode, host: b1.hostTool })
  );

  await recenter();
  box = await canvasBox();
  markPhase("e1");
  await rightDrag([p12, await pointOf(1, 3)], box);
  const e1Posts = commandPosts("e1");
  const e1Debug = await debug();
  // ★ 判别点：`#edge-mode-warning` 在**选中河流工具时就已经亮**（renderEdgeControls），
  //   所以它证明不了"这一拖被拦了"。真正只由 `commitEdge` 的**未选分支**写出的，是这行状态文案。
  const e1Status = await textOf("edge-status");
  values.e1 = { posts: e1Posts.length, warning: e1Debug.edgeModeWarning, status: e1Status };
  check(
    "e1-unselected-mode-zero-write",
    e1Posts.length === 0 && e1Debug.edgeModeWarning.length > 0 && e1Status.indexOf("未选连通性语义") >= 0,
    "posts=" +
      e1Posts.length +
      " warning=" +
      JSON.stringify(e1Debug.edgeModeWarning) +
      " status=" +
      JSON.stringify(e1Status)
  );

  // ══════════════ C e2/s2/s3：merge / replace ══════════════
  await setMode("merge");
  const c0 = await debug();
  check("c0-merge-selected", c0.edgeMode === "merge" && c0.edgeModeWarning === "", "edgeMode=" + c0.edgeMode + " warning=" + JSON.stringify(c0.edgeModeWarning));

  await recenter();
  box = await canvasBox();
  const headBeforeEdge = await head();
  markPhase("e2");
  await rightDrag([p12, await pointOf(1, 3)], box);
  await waitHeadChange(headBeforeEdge);
  const e2Posts = commandPosts("e2", "map.SetEdge");
  const e2Payload = e2Posts.length === 1 ? payloadOf(e2Posts[0]) : null;
  const hex12AfterE2 = await api("/api/map/hex?q=1&r=2");
  values.e2 = {
    posts: e2Posts.length,
    payload: e2Payload,
    headBefore: headBeforeEdge,
    headAfter: await head(),
    hex12Edges: hex12AfterE2.body && hex12AfterE2.body.edges,
    status: (await debug()).edgeControlsVisible,
  };
  check(
    "e2-merge-one-command-and-river-present",
    e2Posts.length === 1 &&
      e2Payload &&
      e2Payload.kind === "river" &&
      e2Payload.mode === "merge" &&
      JSON.stringify(e2Payload.edges) === JSON.stringify(["1_2|1_3"]) &&
      hex12AfterE2.body &&
      hex12AfterE2.body.edges.length === 1 &&
      JSON.stringify(hex12AfterE2.body.edges[0].pathways) === JSON.stringify(["river"]),
    JSON.stringify(values.e2)
  );

  // s2：同一格、同一 kind 组 —— ★ merge road 之后 river **仍在**（T5 的教训：同 kind 才算判别）
  await setTool("road");
  await setMode("merge");
  await recenter();
  box = await canvasBox();
  const headBeforeRoad = await head();
  markPhase("s2");
  await rightDrag([p12, await pointOf(1, 3)], box);
  await waitHeadChange(headBeforeRoad);
  const s2Posts = commandPosts("s2", "map.SetEdge");
  const s2Payload = s2Posts.length === 1 ? payloadOf(s2Posts[0]) : null;
  const hex12AfterS2 = await api("/api/map/hex?q=1&r=2");
  const hex11AfterS2 = await api("/api/map/hex?q=1&r=1");
  values.s2 = {
    posts: s2Posts.length,
    payload: s2Payload,
    hex12Edges: hex12AfterS2.body && hex12AfterS2.body.edges,
    hex11Edges: hex11AfterS2.body && hex11AfterS2.body.edges,
  };
  check(
    "s2-merge-keeps-existing-river",
    s2Posts.length === 1 &&
      s2Payload &&
      s2Payload.kind === "road" &&
      s2Payload.mode === "merge" &&
      hex12AfterS2.body &&
      JSON.stringify(hex12AfterS2.body.edges[0].pathways) === JSON.stringify(["river", "road"]),
    JSON.stringify(values.s2)
  );

  // s3：replace river **[E1]** ⇒ 该 kind 整份覆盖。★ 判别的关键：命令的边集里**不含 E2**，
  //   于是"payload 之外的边同样会被摘掉该 kind"这条语义才可测（见 EdgeOperations 类注释）。
  //   ★ E2 放进 payload 是不行的：replace 摘完又会按 payload 加回来 ⇒ 结果仍是 river+road，
  //   那样这条断言在 merge 实现下也会绿（干净轮已实测到这个失败，修正件在 logs/t11-expectations-corrections.md）。
  //   ★ E1 只能靠**偏心取样**碰到：(1,1) 的格心被单位 u-1 占着 ⇒ pickAt 判 kind:"unit"。
  await setTool("river");
  await setMode("replace");
  await recenter();
  box = await canvasBox();
  const e1Start = await page.evaluate(() => {
    const M = window.SimosMap;
    const c = M.hexToPixel(1, 1, M.BASE_CELL);
    const w = { x: c.x - 21.65, y: c.y + 12.5 }; // 垂直走廊方向偏 25 世界单位（让开单位、仍在格内）
    const v = M.currentView();
    return { x: w.x * v.scale + v.tx, y: w.y * v.scale + v.ty };
  });
  const e1Pick = await page.evaluate(([x, y]) => window.SimosMap.hexAtScreen({ x: x, y: y }), [e1Start.x, e1Start.y]);
  check(
    "s3a-replace-start-is-hex-1-1",
    e1Pick && e1Pick.kind === "hex" && e1Pick.q === 1 && e1Pick.r === 1 && e1Pick.inMap === true,
    JSON.stringify(e1Pick)
  );
  const p12c = await pointOf(1, 2);
  const headBeforeReplace = await head();
  markPhase("s3");
  await rightDrag([e1Start, p12c], box);
  await waitHeadChange(headBeforeReplace);
  const s3Posts = commandPosts("s3", "map.SetEdge");
  const s3Payload = s3Posts.length === 1 ? payloadOf(s3Posts[0]) : null;
  const hex12AfterS3 = await api("/api/map/hex?q=1&r=2");
  const hex11AfterS3 = await api("/api/map/hex?q=1&r=1");
  const norm = (edges) =>
    JSON.stringify((edges || []).map((e) => e.edge + "[" + (e.pathways || []).join(",") + "]").sort());
  /** 某条边在某格上的 pathways（★ 判别点单独取出来，别只藏在整清单比对里）。 */
  const pathwaysOf = (edges, key) => {
    const hit = (edges || []).filter((e) => e.edge === key)[0];
    return hit ? JSON.stringify(hit.pathways) : "MISSING";
  };
  values.s3 = {
    posts: s3Posts.length,
    payload: s3Payload,
    hex12Edges: hex12AfterS3.body && hex12AfterS3.body.edges,
    hex11Edges: hex11AfterS3.body && hex11AfterS3.body.edges,
    hex12Norm: norm(hex12AfterS3.body && hex12AfterS3.body.edges),
    hex11Norm: norm(hex11AfterS3.body && hex11AfterS3.body.edges),
    // ★ `1_1|1_2` **同时接在 (1,2) 上**（hex 的 edges 是**全部关联边**）⇒ hex(1,2) 的清单本就有两项。
    e2PathwaysAfterReplace: pathwaysOf(hex12AfterS3.body && hex12AfterS3.body.edges, "1_2|1_3"),
    e1PathwaysOn12: pathwaysOf(hex12AfterS3.body && hex12AfterS3.body.edges, "1_1|1_2"),
    e1PathwaysOn11: pathwaysOf(hex11AfterS3.body && hex11AfterS3.body.edges, "1_1|1_2"),
    status: await textOf("edge-status"),
  };
  // 判别点拆成两条：① 被 replace 覆盖掉的那条边（★ merge 实现下必红）；② 新边落在 payload 指定的边上。
  check(
    "s3b-replace-stripped-the-kind-off-the-other-edge",
    values.s3.e2PathwaysAfterReplace === JSON.stringify(["road"]),
    "hex(1,2) 上 1_2|1_3 的 pathways=" + values.s3.e2PathwaysAfterReplace + "（road 必须一字不动、river 必须没了）"
  );
  check(
    "s3-replace-strips-that-kind-across-the-map",
    s3Posts.length === 1 &&
      s3Payload &&
      s3Payload.mode === "replace" &&
      JSON.stringify(s3Payload.edges) === JSON.stringify(["1_1|1_2"]) &&
      values.s3.hex12Norm === JSON.stringify(["1_1|1_2[river]", "1_2|1_3[road]"]) &&
      values.s3.hex11Norm === JSON.stringify(["1_1|1_2[river]"]) &&
      values.s3.e1PathwaysOn12 === JSON.stringify(["river"]) &&
      values.s3.e1PathwaysOn11 === JSON.stringify(["river"]) &&
      values.s3.status.indexOf("已改 river 1 条边（replace") >= 0,
    JSON.stringify(values.s3)
  );

  // ══════════════ D x1：非相邻跳 ⇒ 0 写 ══════════════
  // ★ (1,2)→(1,3) 是相邻的；唯一非相邻对是 (1,1)-(1,3)，而 (1,1) 上有单位 u-1 ⇒ 只能**偏心取样**：
  //   A 点取在 (1,1) 内、离格心 25 世界单位（单位命中半径 12.24 ⇒ 让开），然后**绕图外**再落到 (1,3)。
  await setTool("river");
  await setMode("merge");
  await recenter();
  box = await canvasBox();
  const jumpA = await page.evaluate(() => {
    const M = window.SimosMap;
    const c = M.hexToPixel(1, 1, M.BASE_CELL);
    const w = { x: c.x - 21.65, y: c.y + 12.5 }; // 垂直走廊方向偏移 25 世界单位
    const v = M.currentView();
    return { x: w.x * v.scale + v.tx, y: w.y * v.scale + v.ty };
  });
  const jumpHit = await hitIsCanvas(box.x + jumpA.x, box.y + jumpA.y);
  const jumpPick = await page.evaluate(([x, y]) => window.SimosMap.hexAtScreen({ x: x, y: y }), [jumpA.x, jumpA.y]);
  const detour = { x: 1120, y: 120 };
  const detourPick = await page.evaluate(([x, y]) => window.SimosMap.hexAtScreen({ x: x, y: y }), [detour.x, detour.y]);
  const p13b = await pointOf(1, 3);
  values.x1Setup = { jumpHit: jumpHit, jumpPick: jumpPick, detourPick: detourPick, a: jumpA, c: p13b };
  check(
    "x1a-jump-start-is-hex-1-1-and-detour-is-offmap",
    jumpPick && jumpPick.kind === "hex" && jumpPick.q === 1 && jumpPick.r === 1 && jumpPick.inMap === true && detourPick && detourPick.inMap === false,
    JSON.stringify(values.x1Setup)
  );

  markPhase("x1");
  await rightDrag([jumpA, detour, p13b], box);
  const x1Posts = commandPosts("x1");
  const x1Debug = await debug();
  const x1Status = await textOf("edge-status");
  values.x1 = { posts: x1Posts.length, status: x1Status, warning: x1Debug.edgeModeWarning };
  check(
    "x1-nonadjacent-jump-zero-write",
    x1Posts.length === 0 && x1Status.indexOf("非相邻") >= 0,
    "posts=" + x1Posts.length + " status=" + JSON.stringify(x1Status)
  );

  // ══════════════ E randomize ══════════════
  await setTool("randomize");
  const r0 = await debug();
  check(
    "r0-randomize-controls-visible",
    r0.randomizeControlsVisible === true && r0.edgeControlsVisible === false && r0.randomizeSeedText === "",
    JSON.stringify({ rnd: r0.randomizeControlsVisible, edge: r0.edgeControlsVisible, seed: r0.randomizeSeedText })
  );

  // ★ m6 的杀点：**空选区** + **合法 seed** ⇒ 0 写 + 可见提示。
  // ★ seed 必须合法：submitRandomize 里选区守卫在 seed 守卫之前（map.js:2623~2635），
  //   seed 若留空则守卫二先开枪，m6 变异体会被掩盖 ⇒ 这条断言就成了装饰。
  // 此步在 r0b（右键圈选）之前，故 randomizeSelection 仍是初值 []。
  await page.fill("#randomize-seed", "7");
  await sleep(150);
  markPhase("emptyselection");
  await page.click("#randomize-submit");
  await sleep(500);
  const emptySelPosts = commandPosts("emptyselection");
  const emptySelDebug = await debug();
  values.emptySelection = {
    posts: emptySelPosts.length,
    selection: emptySelDebug.randomizeSelection,
    warning: emptySelDebug.randomizeWarning,
    status: await page.evaluate(() => document.getElementById("randomize-status").textContent),
    seed: emptySelDebug.randomizeSeedText,
  };
  check(
    "r0a-empty-selection-zero-write-with-warning",
    emptySelPosts.length === 0 &&
      emptySelDebug.randomizeSelection.length === 0 &&
      emptySelDebug.randomizeWarning.indexOf("选区为空") >= 0,
    JSON.stringify(values.emptySelection)
  );

  await recenter();
  box = await canvasBox();
  markPhase("sel");
  await rightDrag([p12, await pointOf(1, 3)], box);
  const selPosts = commandPosts("sel");
  const selDebug = await debug();
  values.selection = { posts: selPosts.length, selection: selDebug.randomizeSelection };
  check(
    "r0b-selection-is-zero-write-and-persists",
    selPosts.length === 0 && selDebug.randomizeSelection.length === 2,
    "posts=" + selPosts.length + " selection=" + JSON.stringify(selDebug.randomizeSelection)
  );

  // 空 seed ⇒ 0 写 + 可见提示
  await page.fill("#randomize-seed", "");
  await sleep(150);
  markPhase("emptyseed");
  await page.click("#randomize-submit");
  await sleep(500);
  const emptyPosts = commandPosts("emptyseed");
  const emptyDebug = await debug();
  values.emptySeed = { posts: emptyPosts.length, warning: emptyDebug.randomizeWarning, status: await page.evaluate(() => document.getElementById("randomize-status").textContent) };
  check(
    "r0c-empty-seed-zero-write-with-warning",
    emptyPosts.length === 0 && emptyDebug.randomizeWarning.indexOf("seed") >= 0,
    JSON.stringify(values.emptySeed)
  );

  const ovPristine = await api("/api/map/overview");

  async function runRandomize(seedText) {
    await page.fill("#randomize-seed", seedText);
    await sleep(120);
    await recenter();
    const b = await canvasBox();
    const before = await head();
    const phase = "rnd" + seedText + "_" + nonGet.length;
    markPhase(phase);
    await page.click("#randomize-submit");
    await waitHeadChange(before);
    const posts = commandPosts(phase, "map.RandomizeRegion");
    const ov = await api("/api/map/overview");
    return { seedText: seedText, posts: posts.length, payload: posts.length === 1 ? payloadOf(posts[0]) : null, bytes: ov.text.length, md5: md5(ov.text), text: ov.text };
  }

  const r1 = await runRandomize("7");
  const r2 = await runRandomize("7");
  const r3 = await runRandomize("99");
  const r4 = await runRandomize("7");
  values.randomize = {
    pristineMd5: md5(ovPristine.text),
    pristineBytes: ovPristine.text.length,
    r1: { posts: r1.posts, payload: r1.payload, md5: r1.md5, bytes: r1.bytes },
    r2: { posts: r2.posts, payload: r2.payload, md5: r2.md5, bytes: r2.bytes },
    r3: { posts: r3.posts, payload: r3.payload, md5: r3.md5, bytes: r3.bytes },
    r4: { posts: r4.posts, payload: r4.payload, md5: r4.md5, bytes: r4.bytes },
  };
  console.log("RANDOMIZE " + JSON.stringify(values.randomize));

  const selSet = JSON.stringify([{ q: 1, r: 2 }, { q: 1, r: 3 }]);
  check(
    "r1-seed-reaches-payload-verbatim",
    r1.posts === 1 && r1.payload && r1.payload.seed === 7 && JSON.stringify(r1.payload.hexes) === selSet,
    JSON.stringify(values.randomize.r1)
  );
  check(
    "r2-same-seed-twice-byte-identical",
    r2.posts === 1 && r2.md5 === r1.md5,
    "r1=" + r1.md5 + " r2=" + r2.md5
  );
  check(
    "r3-other-seed-differs",
    r3.posts === 1 && r3.payload && r3.payload.seed === 99 && r3.md5 !== r2.md5,
    "r2=" + r2.md5 + " r3=" + r3.md5
  );
  check(
    "r4-back-to-first-seed-reproduces-bytes",
    r4.posts === 1 && r4.payload && r4.payload.seed === 7 && r4.md5 === r1.md5 && r4.md5 !== r3.md5,
    "r1=" + r1.md5 + " r3=" + r3.md5 + " r4=" + r4.md5
  );

  // ══════════════ F g2：地形刷未被抢走 ══════════════
  await setTool("terrain");
  const f0 = await debug();
  check(
    "f0-terrain-tool-restored",
    f0.tool === "terrain" && f0.hostTool === "terrain" && f0.edgeControlsVisible === false && f0.randomizeControlsVisible === false,
    JSON.stringify({ tool: f0.tool, host: f0.hostTool, edge: f0.edgeControlsVisible, rnd: f0.randomizeControlsVisible })
  );
  const palette = await page.evaluate(() =>
    Array.prototype.map.call(document.querySelectorAll("#terrain-palette button[data-terrain]"), (b) => b.getAttribute("data-terrain"))
  );
  const targetTerrain = palette.indexOf("plains") >= 0 ? "plains" : palette[0];
  await page.click('#terrain-palette button[data-terrain="' + targetTerrain + '"]');
  await sleep(200);
  await recenter();
  box = await canvasBox();
  const headBeforeBrush = await head();
  markPhase("g2");
  await rightDrag([p12, await pointOf(1, 3)], box);
  await waitHeadChange(headBeforeBrush);
  const g2Posts = commandPosts("g2", "map.SetTerrain");
  const g2Payload = g2Posts.length === 1 ? payloadOf(g2Posts[0]) : null;
  values.g2 = {
    palette: palette,
    target: targetTerrain,
    posts: g2Posts.length,
    payload: g2Payload,
    headBefore: headBeforeBrush,
    headAfter: await head(),
  };
  check(
    "g2-brush-not-stolen",
    g2Posts.length === 1 && g2Payload && g2Payload.terrain === targetTerrain && (g2Payload.hexes || []).length === 2,
    JSON.stringify(values.g2)
  );

  // 区域信息面板的"连通性"行（读写路径的 DOM 侧证明）
  await recenter();
  box = await canvasBox();
  await page.mouse.click(box.x + p12.x, box.y + p12.y);
  await sleep(900);
  const connectivityRow = await page.evaluate(() => {
    const nodes = document.querySelectorAll("#region-info-editor, .region-info-editor, #hex-info, #left-panel");
    const all = Array.prototype.map.call(document.querySelectorAll("div,p,span,li,td"), (n) => n.textContent || "");
    return all.filter((t) => t.indexOf("连通性") >= 0).slice(0, 3);
  });
  values.connectivityRow = connectivityRow;
  check(
    "g3-region-info-shows-connectivity",
    connectivityRow.some((t) => t.indexOf("连通性") >= 0 && t.indexOf("1_2|1_3") >= 0),
    JSON.stringify(connectivityRow)
  );

  // 截图
  await page.screenshot({ path: OUT + "/t11-final.png" });
  await setTool("river");
  await page.screenshot({ path: OUT + "/t11-tool-river.png" });
  await setTool("randomize");
  await page.screenshot({ path: OUT + "/t11-tool-randomize.png" });
  await setTool("terrain");

  // ── 收尾：非 GET 全清单 + 0 pageerror ──
  console.log("NONGET_ALL " + JSON.stringify(nonGet.map((r) => r.method + " " + r.path + " " + ((parse(r.post) || {}).type || ""))));
  values.nonGet = nonGet.map((r) => ({ method: r.method, path: r.path, type: (parse(r.post) || {}).type || null }));
  values.pageErrors = pageErrors;
  writeJson("result.json", values);
  check("z0-no-pageerror", pageErrors.length === 0, JSON.stringify(pageErrors));

  await browser.close();
  console.log(failures.length === 0 ? "E2E RESULT: ALL PASS" : "E2E RESULT: FAILURES " + JSON.stringify(failures));
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((e) => {
  console.error("E2E CRASH: " + (e && e.stack ? e.stack : e));
  process.exit(2);
});
