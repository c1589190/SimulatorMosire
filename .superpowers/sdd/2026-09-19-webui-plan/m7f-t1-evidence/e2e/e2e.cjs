// M7f T1 e2e —— 时间轴按 tick 分组（一个节点 = 一个 tick）+ 推进 N tick。
// 覆盖：a 下路线不新增节点（同 tick 明细 +1）/ b 推进 1 出新 tick 节点 / c 推进 100 tick（tick == 旧+100）/
//       d 非法 N 不发写 / e 分岔对齐（tick 列基准）/ f R1 拖动只读 + R2 仅末端可写 + 零 pageerror /
//       g R8 allowlist（非 GET 清单可打印）。
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

function stateRevision(page) {
  return page.evaluate(() => window.SimosApp.getState().revision);
}

function stateBranch(page) {
  return page.evaluate(() => window.SimosApp.getState().branch);
}

function stateHeads(page) {
  return page.evaluate(() => window.SimosApp.getState().heads);
}

function selection(page) {
  return page.evaluate(() => window.SimosApp.getState().selection);
}

function statusText(page) {
  return page.evaluate(() => {
    const n = document.getElementById("timeline-status");
    return n ? n.textContent : null;
  });
}

function buttonDisabled(page, id) {
  return page.evaluate((i) => {
    const n = document.getElementById(i);
    return n ? n.disabled : null;
  }, id);
}

/** 某分支每个 tick 节点的 data-* 快照（DOM 是断言主对象）。 */
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
        styleLeft: n.style.left,
        centerX: rect.left + rect.width / 2,
        text: n.textContent,
      };
    });
  }, branch);
}

function timelineLineCount(page) {
  return page.evaluate(() => document.querySelectorAll(".timeline-line").length);
}

function forkLinkCount(page) {
  return page.evaluate(() => document.querySelectorAll(".tl-fork-link").length);
}

function activeNodeText(page) {
  return page.evaluate(() => {
    const n = document.querySelector(".tl-node.active");
    return n ? n.textContent : null;
  });
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
  return { ticks, lastTick: nodes.length > 0 ? nodes[nodes.length - 1].tick : null, nodeCount: nodes.length };
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
  await sleep(250);
}

async function clickHex(page, q, r, button) {
  const canvas = page.locator("#canvas");
  await canvas.scrollIntoViewIfNeeded();
  await sleep(80);
  const pt = await page.evaluate(([qq, rr]) => window.SimosMap.screenPointOf(qq, rr), [q, r]);
  const box = await canvas.boundingBox();
  await page.mouse.click(box.x + pt.x, box.y + pt.y, button ? { button } : undefined);
}

async function setAdvanceN(page, value) {
  await page.evaluate((v) => {
    document.getElementById("timeline-advance-n").value = v;
  }, value);
}

async function clickAdvance(page) {
  await page.locator("#timeline-create").click();
}

/** 等到主分支 head 比 before 大（提交可见）。 */
async function waitHeadAfter(page, before) {
  return waitFor(() => stateHeads(page).then((h) => (h.main > before ? h.main : null)), 10000);
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

  // 初始：main 只有创世（rev1，tick 5）⇒ 1 个 tick 节点、1 条命令。
  const initialNodes = await timelineNodes(page, "main");
  const initialTicks = await apiTicks("main");
  check(
    "setup-single-tick-node",
    initialNodes.length === 1 && initialNodes[0].count === 1 && initialNodes[0].tick === 5,
    JSON.stringify({ initialNodes, initialTicks })
  );
  values.setup = { initialNodes, initialTicks };

  // ── 准备：切单位模式、选中 u-1（选中不发写）──
  await page.click('.mode-bar button[data-mode="unit"]');
  await page.waitForFunction(() => document.body.getAttribute("data-mode") === "unit", null, { timeout: 5000 });
  await clickHex(page, 1, 1);
  const sel0 = await waitFor(() => selection(page).then((s) => (s && s.kind === "unit" ? s : null)), 5000);
  check("setup-unit-selected", !!sel0 && sel0.id === "u-1", JSON.stringify(sel0));

  // ── a. 下路线不新增节点：右键前 N 个 tick 节点 ⇒ 右键 committed 后仍 N，且该 tick 明细 +1 ──
  const beforeA = await timelineNodes(page, "main");
  const apiBeforeA = await apiTicks("main");
  const headBeforeA = (await stateHeads(page)).main;
  const nonGetBeforeA = nonGet().length;
  await clickHex(page, 1, 3, "right");
  const headAfterA = await waitFor(() => stateHeads(page).then((h) => (h.main > headBeforeA ? h.main : null)), 10000);
  const afterA = await waitFor(
    () => timelineNodes(page, "main").then((ns) => (ns[0].count > beforeA[0].count ? ns : null)),
    8000
  );
  const apiAfterA = await apiTicks("main");
  const nonGetAfterA = nonGet().length;
  check("a-node-count-unchanged", !!afterA && afterA.length === beforeA.length, JSON.stringify({ before: beforeA.length, after: afterA && afterA.length }));
  check(
    "a-detail-plus-one",
    !!afterA && afterA[0].count === beforeA[0].count + 1 && afterA[0].tick === beforeA[0].tick,
    JSON.stringify({ before: beforeA[0], after: afterA && afterA[0] })
  );
  check(
    "a-command-is-planroute",
    !!afterA && String(afterA[0].commands).indexOf("unit.PlanRoute") >= 0,
    JSON.stringify(afterA && afterA[0].commands)
  );
  check("a-head-advanced", headAfterA === headBeforeA + 1, JSON.stringify({ headBeforeA, headAfterA }));
  check("a-write-issued", nonGetAfterA === nonGetBeforeA + 1, JSON.stringify({ nonGetBeforeA, nonGetAfterA }));
  check(
    "a-api-tick-count-unchanged",
    apiAfterA.ticks.length === apiBeforeA.ticks.length && apiAfterA.lastTick === apiBeforeA.lastTick,
    JSON.stringify({ apiBeforeA: apiBeforeA.ticks, apiAfterA: apiAfterA.ticks })
  );
  values.a = { beforeA, afterA, headBeforeA, headAfterA, apiBeforeA, apiAfterA, domText: afterA && afterA[0].text };
  writeJson("a-route-no-new-node.json", values.a);
  await page.locator("#timeline-bar").screenshot({ path: OUT + "/screenshot-tick-detail.png" });

  // ── b. 推进 1（默认）⇒ 新 tick 节点（节点数 +1），tick == 旧末端 + 1 ──
  await setAdvanceN(page, "1");
  const tipTickB = (await apiTicks("main")).lastTick;
  const nodesBeforeB = (await timelineNodes(page, "main")).length;
  const headBeforeB = (await stateHeads(page)).main;
  const advEnabledB = !(await buttonDisabled(page, "timeline-create"));
  await clickAdvance(page);
  const headAfterB = await waitHeadAfter(page, headBeforeB);
  await waitFor(() => timelineNodes(page, "main").then((ns) => (ns.length > nodesBeforeB ? ns : null)), 8000);
  const nodesAfterB = await timelineNodes(page, "main");
  const apiAfterB = await apiTicks("main");
  check("b-advance-button-enabled-at-tip", advEnabledB, JSON.stringify({ advEnabledB }));
  check("b-node-plus-one", nodesAfterB.length === nodesBeforeB + 1, JSON.stringify({ nodesBeforeB, after: nodesAfterB.length }));
  check(
    "b-new-tick-is-old-plus-1",
    apiAfterB.lastTick === tipTickB + 1 && nodesAfterB[nodesAfterB.length - 1].tick === tipTickB + 1,
    JSON.stringify({ tipTickB, lastTick: apiAfterB.lastTick, dom: nodesAfterB[nodesAfterB.length - 1].tick })
  );
  check("b-head-advanced", headAfterB === headBeforeB + 1, JSON.stringify({ headBeforeB, headAfterB }));
  check(
    "b-new-node-is-advancetime",
    String(nodesAfterB[nodesAfterB.length - 1].commands).indexOf("core.AdvanceTime") >= 0,
    JSON.stringify(nodesAfterB[nodesAfterB.length - 1].commands)
  );
  values.b = { tipTickB, nodesBeforeB, nodesAfterB, apiAfterB, headBeforeB, headAfterB };
  writeJson("b-advance-1.json", values.b);

  // ── c. 推进 N=100 ⇒ 新节点 tick == 旧 tick + 100（/api/timeline 读回核对）、节点数 +1、head +1 ──
  await setAdvanceN(page, "100");
  const tipTickC = (await apiTicks("main")).lastTick;
  const nodesBeforeC = (await timelineNodes(page, "main")).length;
  const headBeforeC = (await stateHeads(page)).main;
  await clickAdvance(page);
  const headAfterC = await waitHeadAfter(page, headBeforeC);
  await waitFor(() => timelineNodes(page, "main").then((ns) => (ns.length > nodesBeforeC ? ns : null)), 8000);
  const nodesAfterC = await timelineNodes(page, "main");
  const apiAfterC = await apiTicks("main");
  check("c-node-plus-one", nodesAfterC.length === nodesBeforeC + 1, JSON.stringify({ nodesBeforeC, after: nodesAfterC.length }));
  check(
    "c-new-tick-is-old-plus-100",
    apiAfterC.lastTick === tipTickC + 100 && nodesAfterC[nodesAfterC.length - 1].tick === tipTickC + 100,
    JSON.stringify({ tipTickC, apiLastTick: apiAfterC.lastTick, domLastTick: nodesAfterC[nodesAfterC.length - 1].tick })
  );
  check("c-head-advanced", headAfterC === headBeforeC + 1, JSON.stringify({ headBeforeC, headAfterC }));
  check("c-status-shows-100", /(^|[^0-9])100([^0-9]|$)/.test(String(await statusText(page))), JSON.stringify(await statusText(page)));
  values.c = { tipTickC, nodesBeforeC, nodesAfterC, apiAfterC, headBeforeC, headAfterC, status: await statusText(page) };
  writeJson("c-advance-100.json", values.c);
  await page.locator("#timeline-bar").screenshot({ path: OUT + "/screenshot-advance-100.png" });

  // ── d. 非法 N（0 / 负 / 非数字）⇒ 明确提示、不发写 ──
  const invalidCases = ["0", "-5", "abc"];
  const invalidResults = [];
  for (const value of invalidCases) {
    await setAdvanceN(page, value);
    const enabled = !(await buttonDisabled(page, "timeline-create"));
    const nonGetBefore = nonGet().length;
    const nodesBefore = (await timelineNodes(page, "main")).length;
    const headBefore = (await stateHeads(page)).main;
    await clickAdvance(page);
    await sleep(450);
    const status = await statusText(page);
    const nonGetAfter = nonGet().length;
    const nodesAfter = (await timelineNodes(page, "main")).length;
    const headAfter = (await stateHeads(page)).main;
    invalidResults.push({
      value,
      enabled,
      status,
      nonGetBefore,
      nonGetAfter,
      nodesBefore,
      nodesAfter,
      headBefore,
      headAfter,
    });
    check(
      "d-invalid-" + value + "-message",
      enabled && !!status && status.indexOf("整数") >= 0,
      JSON.stringify({ value, enabled, status })
    );
    check("d-invalid-" + value + "-no-write", nonGetAfter === nonGetBefore, JSON.stringify({ nonGetBefore, nonGetAfter }));
    check(
      "d-invalid-" + value + "-state-unchanged",
      nodesAfter === nodesBefore && headAfter === headBefore,
      JSON.stringify({ nodesBefore, nodesAfter, headBefore, headAfter })
    );
  }
  values.d = invalidResults;
  writeJson("d-invalid-n.json", invalidResults);
  await setAdvanceN(page, "1");

  // ── e. 分岔对齐仍成立（tick 列基准）：新分支首个 tick 节点与 parent 所在 tick 节点同列 ──
  const tipTickE = (await apiTicks("main")).lastTick;
  await page.locator("#timeline-fork").click();
  const branchE = await waitFor(() => stateBranch(page).then((b) => (b !== "main" ? b : null)), 10000);
  await waitFor(() => timelineNodes(page, branchE || "b2").then((ns) => (ns.length > 0 ? ns : null)), 8000);
  const mainNodesE = await timelineNodes(page, "main");
  const childNodesE = await timelineNodes(page, branchE || "b2");
  const parentNodeE = mainNodesE.filter((n) => n.tick === tipTickE)[0];
  const childFirstE = childNodesE[0];
  const linesE = await timelineLineCount(page);
  const linksE = await forkLinkCount(page);
  check("e-branch-created", !!branchE, JSON.stringify({ branchE }));
  check(
    "e-fork-aligned",
    !!parentNodeE && !!childFirstE && childFirstE.styleLeft === parentNodeE.styleLeft &&
      Math.abs(childFirstE.centerX - parentNodeE.centerX) <= 2,
    JSON.stringify({
      parentTick: tipTickE,
      parentStyleLeft: parentNodeE && parentNodeE.styleLeft,
      childStyleLeft: childFirstE && childFirstE.styleLeft,
      parentCenterX: parentNodeE && parentNodeE.centerX,
      childCenterX: childFirstE && childFirstE.centerX,
    })
  );
  check("e-child-tick-matches-parent", !!childFirstE && childFirstE.tick === tipTickE, JSON.stringify({ tipTickE, childTick: childFirstE && childFirstE.tick }));
  check("e-one-line-per-branch", linesE === 2, JSON.stringify({ linesE }));
  check("e-fork-link-present", linksE >= 1, JSON.stringify({ linksE }));
  values.e = { branchE, tipTickE, mainNodesE, childNodesE, linesE, linksE };
  writeJson("e-fork-alignment.json", values.e);

  // ── f. R2：游标移到非末端 ⇒ 推进/分岔禁用；R1：拖动只读（无写、rev 变）；零 pageerror ──
  const mainHeadF = (await stateHeads(page)).main;
  const firstTickNodeE = mainNodesE[0];
  await page.evaluate(
    (branch) => {
      const n = document.querySelector('.timeline-line[data-branch="' + branch + '"] .tl-node');
      n.click();
    },
    "main"
  );
  const atMiddleF = await waitFor(() => stateBranch(page).then((b) => (b === "main" ? b : null)), 5000);
  const midRevF = await stateRevision(page);
  const disabledMiddleCreate = await buttonDisabled(page, "timeline-create");
  const disabledMiddleFork = await buttonDisabled(page, "timeline-fork");
  check("f-r2-advance-disabled-off-tip", disabledMiddleCreate === true, JSON.stringify({ disabledMiddleCreate, midRevF }));
  check("f-r2-fork-disabled-off-tip", disabledMiddleFork === true, JSON.stringify({ disabledMiddleFork }));
  check(
    "f-tick5-cursor-is-last-revision",
    midRevF === firstTickNodeE.revision,
    JSON.stringify({ midRevF, tick5LastRevision: firstTickNodeE.revision })
  );

  const nonGetBeforeDrag = nonGet().length;
  const knobBox = await page.locator(".tl-knob").boundingBox();
  const tipNodeBox = await page.evaluate(() => {
    const nodes = document.querySelectorAll('.timeline-line[data-branch="main"] .tl-node');
    const n = nodes[nodes.length - 1];
    const r = n.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  });
  check("f-knob-visible", !!knobBox, JSON.stringify(knobBox));
  if (knobBox && tipNodeBox) {
    const cx = knobBox.x + knobBox.width / 2;
    const cy = knobBox.y + knobBox.height / 2;
    await page.mouse.move(cx, cy);
    await page.mouse.down();
    await page.mouse.move(cx + (tipNodeBox.x - cx) / 2, cy, { steps: 6 });
    await page.mouse.move(tipNodeBox.x, cy, { steps: 6 });
    await page.mouse.up();
  }
  const revAfterDrag = await waitFor(() => stateRevision(page).then((r) => (r === mainHeadF ? r : null)), 6000);
  const nonGetAfterDrag = nonGet().length;
  check("f-r1-drag-changes-rev", revAfterDrag === mainHeadF, JSON.stringify({ midRevF, mainHeadF, revAfterDrag }));
  check("f-r1-drag-no-write", nonGetAfterDrag === nonGetBeforeDrag, JSON.stringify({ nonGetBeforeDrag, nonGetAfterDrag }));
  const disabledTipCreate = await buttonDisabled(page, "timeline-create");
  const disabledTipFork = await buttonDisabled(page, "timeline-fork");
  check("f-r2-advance-enabled-at-tip", disabledTipCreate === false, JSON.stringify({ disabledTipCreate }));
  check("f-r2-fork-enabled-at-tip", disabledTipFork === false, JSON.stringify({ disabledTipFork }));
  check("f-active-node-at-tip", (await activeNodeText(page)) !== null, JSON.stringify(await activeNodeText(page)));
  values.f = {
    atMiddleF,
    midRevF,
    tick5LastRevision: firstTickNodeE.revision,
    disabledMiddleCreate,
    disabledMiddleFork,
    revAfterDrag,
    mainHeadF,
    nonGetBeforeDrag,
    nonGetAfterDrag,
    disabledTipCreate,
    disabledTipFork,
  };
  writeJson("f-r1-r2.json", values.f);

  // ── g. R8 allowlist：全过程非 GET 清单 ⊆ {/api/command,/api/advance,/api/fork} 且打印 ──
  const list = nonGet();
  const allow = ["/api/command", "/api/advance", "/api/fork"];
  const violations = list.filter((p) => allow.indexOf(p) < 0);
  console.log("NON_GET_LIST=" + JSON.stringify(list));
  check("g-nonget-allowlist", violations.length === 0, JSON.stringify({ list, violations }));
  check("g-command-present", list.indexOf("/api/command") >= 0, JSON.stringify(list));
  check("g-advance-present", list.indexOf("/api/advance") >= 0, JSON.stringify(list));
  check("g-fork-present", list.indexOf("/api/fork") >= 0, JSON.stringify(list));
  values.g = { list, violations };
  writeJson("g-nonget-list.json", list);

  // ── f2. 零 pageerror ──
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
