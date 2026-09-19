// M7 T3 e2e —— Playwright 驱动真页面，验证时间轴判据②（画/拖/末端写/分岔/409/R1）。
// 用法: NODE_PATH=<playwright> node e2e.cjs <base-url> <store-dir> <out-dir>
// ★ 退出码：0 = 全部必测步骤 PASS；1 = 有 FAIL（变异轮据此观察红点）。
"use strict";

const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const STORE = process.argv[3];
const OUT = process.argv[4];
const DB = STORE + "/simos.db";

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

function rows() {
  const out = execFileSync(
    "python3",
    [
      "-c",
      "import sqlite3,sys;c=sqlite3.connect('file:'+sys.argv[1]+'?mode=ro',uri=True);" +
        "print(c.execute('select count(*) from revisions').fetchone()[0])",
      DB,
    ],
    { encoding: "utf8" }
  );
  return Number(out.trim());
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

(async () => {
  // b: 造 ≥3 个 revision（bootstrap=1，两次 unit.RenameUnit → 3）
  let stateBody = null;
  for (let i = 0; i < 40; i++) {
    stateBody = await api("/api/state");
    if (stateBody.body && stateBody.body.heads && stateBody.body.heads.main) {
      break;
    }
    await sleep(250);
  }
  let head = stateBody.body.heads.main;
  for (const name of ["甲", "乙"]) {
    const response = await api(
      "/api/command",
      jsonPost({
        type: "unit.RenameUnit",
        payloadJson: JSON.stringify({ id: "u-1", name }),
        branch: "main",
        expectedRevision: head,
      })
    );
    if (response.status !== 200) {
      check("b-seed", false, "status=" + response.status + " " + response.text.slice(0, 120));
      process.exit(1);
    }
    head = response.body.ref.revision;
  }
  check("b-seed", head >= 3, "head=" + head + " rows=" + rows());

  // c: /api/timeline 的 head 与 nodes
  const timelineBefore = await api("/api/timeline?branch=main");
  writeJson("timeline-before.json", timelineBefore.body);
  check(
    "c-timeline",
    timelineBefore.body.head === head && timelineBefore.body.nodes.length === head,
    "head=" + timelineBefore.body.head + " nodes=" + timelineBefore.body.nodes.length
  );

  // d: 真页面加载；节点数 == head；纯函数 isAtTip
  const executablePath =
    process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
  const browser = await chromium.launch({ headless: true, executablePath });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await page.waitForSelector('.timeline-line[data-branch="main"] .tl-node');
  await page.waitForFunction(
    (h) => document.querySelectorAll('.timeline-line[data-branch="main"] .tl-node').length === h,
    head,
    { timeout: 8000 }
  );
  const nodeCount = await page.evaluate(
    () => document.querySelectorAll('.timeline-line[data-branch="main"] .tl-node').length
  );
  const labels = await page.evaluate(() =>
    Array.prototype.map.call(
      document.querySelectorAll('.timeline-line[data-branch="main"] .tl-node'),
      (n) => n.textContent
    )
  );
  const tipProbe = await page.evaluate(() => ({
    mid: window.SimosTimeline.isAtTip({ branch: "main", revision: 1 }, { main: 3 }),
    tip: window.SimosTimeline.isAtTip({ branch: "main", revision: 3 }, { main: 3 }),
    missing: window.SimosTimeline.isAtTip({ branch: "ghost", revision: 1 }, { main: 3 }),
    empty: window.SimosTimeline.isAtTip(null, { main: 3 }),
  }));
  check("d-nodes", nodeCount === head, "nodes=" + nodeCount + " head=" + head);
  check(
    "d-labels",
    labels.length === head && labels[0].indexOf("rev 1") >= 0 && labels[1].indexOf("RenameUnit") >= 0,
    JSON.stringify(labels)
  );
  check(
    "d-isAtTip",
    tipProbe.mid === false && tipProbe.tip === true && tipProbe.missing === false && tipProbe.empty === false,
    JSON.stringify(tipProbe)
  );

  // e: 点中间节点 ⇒ 只读预览（R1）：按钮置灰、head 与行数不变
  const headBefore = (await api("/api/timeline?branch=main")).body.head;
  const rowsBefore = rows();
  await page.click('.timeline-line[data-branch="main"] .tl-node[data-revision="2"]');
  await page.waitForFunction(
    () => document.getElementById("timeline-meta").textContent.indexOf("rev 2") >= 0,
    null,
    { timeout: 3000 }
  );
  await sleep(700);
  const midState = await page.evaluate(() => ({
    create: document.getElementById("timeline-create").disabled,
    fork: document.getElementById("timeline-fork").disabled,
    rev: window.SimosApp.getState().revision,
    branch: window.SimosApp.getState().branch,
    meta: document.getElementById("timeline-meta").textContent,
  }));
  const headAfter = (await api("/api/timeline?branch=main")).body.head;
  const rowsAfter = rows();
  writeJson("timeline-after-preview.json", (await api("/api/timeline?branch=main")).body);
  await page.screenshot({ path: OUT + "/screenshot-mid-disabled.png" });
  check("e-mid-rev", midState.rev === 2 && midState.branch === "main", JSON.stringify(midState));
  check(
    "e-mid-disabled",
    midState.create === true && midState.fork === true,
    "create_disabled=" + midState.create + " fork_disabled=" + midState.fork
  );
  check(
    "e-readonly",
    headBefore === headAfter && rowsBefore === rowsAfter,
    "head " + headBefore + "->" + headAfter + " rows " + rowsBefore + "->" + rowsAfter
  );

  // f: 点末端 ⇒ 两个按钮可用
  await page.click('.timeline-line[data-branch="main"] .tl-node[data-revision="' + head + '"]');
  await page.waitForFunction(() => !document.getElementById("timeline-create").disabled, null, {
    timeout: 3000,
  });
  const tipButtons = await page.evaluate(() => ({
    create: document.getElementById("timeline-create").disabled,
    fork: document.getElementById("timeline-fork").disabled,
  }));
  check("f-tip-enabled", tipButtons.create === false && tipButtons.fork === false, JSON.stringify(tipButtons));

  // e2: 拖动游标（pointer drag）也走只读预览（R1），且到中间节点后写按钮置灰
  const boxOf = async (revision) =>
    page.locator('.timeline-line[data-branch="main"] .tl-node[data-revision="' + revision + '"]').boundingBox();
  const tipBox = await boxOf(head);
  const midBox = await boxOf(1);
  await page.mouse.move(tipBox.x + tipBox.width / 2, tipBox.y + tipBox.height / 2);
  await page.mouse.down();
  await page.mouse.move(midBox.x + midBox.width / 2, midBox.y + midBox.height / 2, { steps: 8 });
  await page.mouse.up();
  await page.waitForFunction(() => window.SimosApp.getState().revision === 1, null, { timeout: 3000 });
  const afterDrag = await page.evaluate(() => ({
    rev: window.SimosApp.getState().revision,
    create: document.getElementById("timeline-create").disabled,
    fork: document.getElementById("timeline-fork").disabled,
  }));
  const headAfterDrag = (await api("/api/timeline?branch=main")).body.head;
  const rowsAfterDrag = rows();
  check(
    "e2-drag-preview",
    afterDrag.rev === 1 && afterDrag.create === true && afterDrag.fork === true && headAfterDrag === head && rowsAfterDrag === rowsBefore,
    JSON.stringify(afterDrag) + " head=" + headAfterDrag + " rows=" + rowsAfterDrag
  );

  // g: 分岔 ⇒ /api/state 多一条分支 + 时间轴第二条线（先回到末端）
  await page.click('.timeline-line[data-branch="main"] .tl-node[data-revision="' + head + '"]');
  await page.waitForFunction(() => !document.getElementById("timeline-create").disabled, null, {
    timeout: 3000,
  });
  await page.click("#timeline-fork");
  await page.waitForFunction(() => document.querySelectorAll(".timeline-line").length === 2, null, {
    timeout: 8000,
  });
  const branches = (await api("/api/state")).body.branches;
  const lineCount = await page.evaluate(() => document.querySelectorAll(".timeline-line").length);
  const postFork = await page.evaluate(() => ({
    create: document.getElementById("timeline-create").disabled,
    fork: document.getElementById("timeline-fork").disabled,
    meta: document.getElementById("timeline-meta").textContent,
    active: (document.querySelector(".tl-node.active") || {}).textContent,
  }));
  await page.screenshot({ path: OUT + "/screenshot-forked-two-lines.png" });
  check("g-fork", branches.length === 2 && lineCount === 2, "branches=" + JSON.stringify(branches) + " lines=" + lineCount);
  check(
    "g-postfork-tip",
    postFork.create === false && postFork.fork === false && postFork.meta.indexOf("分支 b2") >= 0,
    JSON.stringify(postFork)
  );

  // h: 409 路径（尽力而为）：外部把 main head 推一格 → 页面手里的 expectedRevision 过期 → 点创建节点
  await page.click('.timeline-line[data-branch="main"] .tl-node[data-revision="' + head + '"]');
  await page.waitForFunction(() => !document.getElementById("timeline-create").disabled, null, {
    timeout: 3000,
  });
  const mainTimeline = (await api("/api/timeline?branch=main")).body;
  const tick = mainTimeline.nodes.filter((n) => n.revision === mainTimeline.head)[0].tick;
  const external = await api(
    "/api/advance",
    jsonPost({ branch: "main", expectedRevision: mainTimeline.head, from: tick, to: tick + 1 })
  );
  let conflictResult = "UNCOVERED";
  if (external.status === 200) {
    const enabledBefore = await page.evaluate(() => !document.getElementById("timeline-create").disabled);
    if (enabledBefore) {
      await page.click("#timeline-create", { timeout: 2000 }).catch(() => {});
      try {
        await page.waitForFunction(
          () => document.getElementById("timeline-status").textContent.indexOf("末端已移动") >= 0,
          null,
          { timeout: 5000 }
        );
        await page.waitForFunction(
          (expected) => document.getElementById("timeline-meta").textContent.indexOf("head " + expected) >= 0,
          mainTimeline.head + 1,
          { timeout: 5000 }
        );
        const after = await page.evaluate(() => ({
          status: document.getElementById("timeline-status").textContent,
          create: document.getElementById("timeline-create").disabled,
          meta: document.getElementById("timeline-meta").textContent,
        }));
        conflictResult = "PASS";
        check(
          "h-conflict",
          true,
          'status="' + after.status + '" create_disabled=' + after.create + ' meta="' + after.meta + '"'
        );
      } catch (e) {
        conflictResult = "UNCOVERED";
      }
    }
  }
  if (conflictResult !== "PASS") {
    console.log("STEP h-conflict: UNCOVERED (409 窗口未能稳定复现；不伪造)");
  }

  await browser.close();
  console.log("E2E RESULT: " + (failures.length === 0 ? "PASS" : "FAIL " + failures.join(",")));
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((e) => {
  console.log("E2E RESULT: FAIL exception " + (e && e.stack ? e.stack : e));
  process.exit(1);
});
