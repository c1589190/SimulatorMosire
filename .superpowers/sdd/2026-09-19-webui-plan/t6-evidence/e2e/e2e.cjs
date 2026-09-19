// M7 T6 e2e —— Playwright 驱动真页面（真 ShellMain + M6 导入档），验证区域查看面板（判据④ / R4）。
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

async function api(path) {
  const response = await fetch(BASE + path);
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

function hexToRgb(hex) {
  const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(String(hex).trim());
  return [parseInt(m[1], 16), parseInt(m[2], 16), parseInt(m[3], 16)];
}

function rgbEq(a, b, tol) {
  return Math.abs(a[0] - b[0]) <= tol && Math.abs(a[1] - b[1]) <= tol && Math.abs(a[2] - b[2]) <= tol;
}

function blend(over, under, alpha) {
  return [0, 1, 2].map((i) => Math.round(over[i] * alpha + under[i] * (1 - alpha)));
}

function sameSet(a, b) {
  const x = a.slice().sort();
  const y = b.slice().sort();
  return x.length === y.length && x.every((v, i) => v === y[i]);
}

async function waitMapReady(page, timeout) {
  await page.waitForFunction(
    () => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(),
    null,
    { timeout: timeout || 40000 }
  );
}

async function debug(page) {
  return page.evaluate(() => window.SimosMap.debug());
}

async function pixelAt(page, q, r) {
  return page.evaluate(
    ([qq, rr]) => {
      const c = document.getElementById("canvas");
      const pt = window.SimosMap.screenPointOf(qq, rr);
      const dpr = c.width / c.getBoundingClientRect().width;
      const d = c
        .getContext("2d")
        .getImageData(Math.round(pt.x * dpr), Math.round(pt.y * dpr), 1, 1).data;
      return [d[0], d[1], d[2]];
    },
    [q, r]
  );
}

async function centerOn(page, q, r, scale) {
  await page.evaluate(
    ([qq, rr, sc]) => {
      const rect = document.getElementById("canvas").getBoundingClientRect();
      const cell = window.SimosMap.debug().cellSize;
      const world = window.SimosMap.hexToPixel(qq, rr, cell);
      window.SimosMap.setView({
        scale: sc,
        tx: rect.width / 2 - world.x * sc,
        ty: rect.height / 2 - world.y * sc,
      });
    },
    [q, r, scale]
  );
  await sleep(250);
}

async function domGroups(page) {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll("#region-panel-mount .region-tag")).map((tagButton) => {
      const list = tagButton.nextElementSibling;
      return {
        tag: tagButton.getAttribute("data-tag"),
        ids: Array.from(list.querySelectorAll(".region-item")).map((n) =>
          n.getAttribute("data-region-id")
        ),
      };
    })
  );
}

// 页面内 groupByTag 的冻结夹具（覆盖 null/undefined/空串/纯空白/缺 meta/重复 tag）。
// 经 page.evaluate 传参时 `undefined` 字段会被 JSON 丢弃 ⇒ 正好走「缺 tag」路径。
const NULL_FIXTURE = [
  { id: "r-null", name: "空标签", hexCount: 1, meta: { color: "#111111", tag: null } },
  { id: "r-undef", name: "无 tag", hexCount: 2, meta: { color: null, tag: undefined } },
  { id: "r-blank", name: "空串", hexCount: 3, meta: { color: "#333333", tag: "" } },
  { id: "r-space", name: "空白串", hexCount: 4, meta: { color: "#444444", tag: "   " } },
  { id: "r-b", name: "B 区", hexCount: 5, meta: { color: "#555555", tag: "Beta" } },
  { id: "r-a", name: "A 区", hexCount: 6, meta: { color: "#666666", tag: "Alpha" } },
  { id: "r-a2", name: "A2 区", hexCount: 7, meta: { color: "#777777", tag: "Alpha" } },
  { id: "r-nometa", name: "无 meta", hexCount: 8, meta: null },
];
const NULL_EXPECTED = [
  { tag: "Alpha", ids: ["r-a", "r-a2"] },
  { tag: "Beta", ids: ["r-b"] },
  { tag: "未标注", ids: ["r-blank", "r-nometa", "r-null", "r-space", "r-undef"] },
];

async function run(page) {
  // ── 真数据：overview 的 region 项（含 meta）原样落盘 ──
  const overview = await api("/api/map/overview?branch=main&revision=1");
  const regions = (overview.body && overview.body.regions) || [];
  writeJson("real-regions.json", regions);
  const byId = {};
  regions.forEach((r) => {
    byId[r.id] = r;
  });
  const nation = byId.test_nation;
  const annex = byId.test_annex_target;
  check(
    "real-regions-and-meta",
    regions.length === 2 && !!nation && !!annex && !!nation.meta && !!annex.meta,
    JSON.stringify(regions.map((r) => ({ id: r.id, hexCount: r.hexCount, meta: r.meta })))
  );

  const nationDetail = (await api("/api/map/region/test_nation?branch=main&revision=1")).body;
  const annexDetail = (await api("/api/map/region/test_annex_target?branch=main&revision=1")).body;
  const nationKeys = nationDetail.hexes.map((h) => h.q + "_" + h.r);
  const annexKeys = annexDetail.hexes.map((h) => h.q + "_" + h.r);
  const nationKeySet = new Set(nationKeys);
  const unionCount = new Set(nationKeys.concat(annexKeys)).size;

  // ── 载入工作台 ──
  await page.goto(BASE + "/", { waitUntil: "networkidle" });
  await waitMapReady(page, 40000);
  await page.waitForFunction(
    () => document.getElementById("map-status").textContent.indexOf("已载入") >= 0,
    null,
    { timeout: 30000 }
  );
  await sleep(300);

  // ── 区域查看模式 ⇒ 右栏分组列表 ──
  await page.click('button[data-mode="region"]');
  await page.waitForFunction(() => document.body.getAttribute("data-mode") === "region");
  await page.waitForSelector("#region-panel-mount .region-tag", { timeout: 15000 });

  const dom = await domGroups(page);
  const frozen = [{ tag: "Nation", ids: ["test_annex_target", "test_nation"] }];
  check(
    "group-result",
    JSON.stringify(dom) === JSON.stringify(frozen),
    JSON.stringify(dom)
  );

  const pageGroups = await page.evaluate(
    (input) =>
      window.SimosPanels.groupByTag(input).map((g) => ({
        tag: g.tag,
        ids: g.regions.map((r) => r.id),
      })),
    regions
  );
  check(
    "group-matches-page-pure-function",
    JSON.stringify(dom) === JSON.stringify(pageGroups),
    JSON.stringify(pageGroups)
  );
  check(
    "real-data-has-no-untagged-bucket",
    pageGroups.every((g) => g.tag !== "未标注"),
    JSON.stringify(pageGroups.map((g) => g.tag))
  );

  // ★ m1 红点：真实数据无 null tag ⇒ 未标注桶由页面内纯函数夹具覆盖
  const nullGroups = await page.evaluate(
    (input) =>
      window.SimosPanels.groupByTag(input).map((g) => ({
        tag: g.tag,
        ids: g.regions.map((r) => r.id),
      })),
    NULL_FIXTURE
  );
  check(
    "group-null-bucket-fixture",
    JSON.stringify(nullGroups) === JSON.stringify(NULL_EXPECTED),
    JSON.stringify(nullGroups)
  );

  // ── 点区域：只高亮该区 + 详情 + 填充色 == meta.color ──
  const hex0 = nationDetail.hexes[0];
  await centerOn(page, hex0.q, hex0.r, 4);
  const basePixel = await pixelAt(page, hex0.q, hex0.r);
  await page.click('[data-region-id="test_nation"]');
  await page.waitForFunction(
    (id) => {
      const h = window.SimosApp.getState().highlightRegions;
      return h.length === 1 && h[0] === id;
    },
    "test_nation",
    { timeout: 15000 }
  );
  await page.waitForFunction(
    (n) => window.SimosMap.debug().highlightHexCount === n,
    nationDetail.hexCount,
    { timeout: 15000 }
  );
  await sleep(250);
  const filledPixel = await pixelAt(page, hex0.q, hex0.r);
  const dbgNation = await debug(page);
  const expectedFill = blend(hexToRgb(nation.meta.color), basePixel, 0.42);
  check(
    "region-click-only-this-id",
    JSON.stringify(await page.evaluate(() => window.SimosApp.getState().highlightRegions)) ===
      JSON.stringify(["test_nation"]),
    JSON.stringify(await page.evaluate(() => window.SimosApp.getState().highlightRegions))
  );
  check(
    "region-click-hex-count",
    dbgNation.highlightHexCount === nationDetail.hexCount,
    JSON.stringify({ expected: nationDetail.hexCount, actual: dbgNation.highlightHexCount })
  );
  check(
    "region-fill-color-is-meta-color",
    rgbEq(filledPixel, expectedFill, 3) && sameSet(dbgNation.highlightColors, [nation.meta.color]),
    JSON.stringify({
      basePixel,
      filledPixel,
      expectedFill,
      metaColor: nation.meta.color,
      highlightColors: dbgNation.highlightColors,
    })
  );

  const detailText = await page.evaluate(() => {
    const dl = document.querySelector("#region-panel-mount .region-detail");
    return dl ? dl.textContent : "";
  });
  check(
    "region-detail-fields",
    detailText.indexOf("test_nation") >= 0 &&
      detailText.indexOf("701") >= 0 &&
      detailText.indexOf("Nation") >= 0 &&
      detailText.indexOf(nation.meta.color) >= 0 &&
      detailText.indexOf("annexedBy") >= 0,
    JSON.stringify(detailText)
  );
  await page.screenshot({ path: OUT + "/screenshot-region-panel.png" });

  // ── 点标签：该标签下全部区域一起高亮（集合 == 全部 id；distinct hex == union）──
  // ★ 不在这里用会抛超时的 waitForFunction：变异体只高亮首个区域时，红点必须落在
  //   `tag-click-all-region-ids` 断言本身，而不是被 timeout 异常提前打断。
  await page.click('.region-tag[data-tag="Nation"]');
  await sleep(1200);
  const tagIds = await page.evaluate(() => window.SimosApp.getState().highlightRegions);
  check(
    "tag-click-all-region-ids",
    sameSet(tagIds, ["test_annex_target", "test_nation"]),
    JSON.stringify(tagIds)
  );
  await page
    .waitForFunction((n) => window.SimosMap.debug().highlightHexCount === n, unionCount, {
      timeout: 8000,
    })
    .catch(() => {});
  await sleep(250);
  const dbgTag = await debug(page);
  check(
    "tag-click-distinct-hex-count-is-union",
    dbgTag.highlightHexCount === unionCount,
    JSON.stringify({
      expectedUnion: unionCount,
      actual: dbgTag.highlightHexCount,
      sumOfHexCounts: nation.hexCount + annex.hexCount,
    })
  );
  check(
    "tag-click-both-colors",
    sameSet(dbgTag.highlightColors, [nation.meta.color, annex.meta.color]),
    JSON.stringify({ colors: dbgTag.highlightColors, expected: [nation.meta.color, annex.meta.color] })
  );
  await page.screenshot({ path: OUT + "/screenshot-tag-highlight.png" });

  writeJson("e2e-values.json", {
    basePixel,
    filledPixel,
    expectedFill,
    unionCount,
    sumOfHexCounts: nation.hexCount + annex.hexCount,
    nationColor: nation.meta.color,
    annexColor: annex.meta.color,
    tagHighlightColors: dbgTag.highlightColors,
  });
}

(async () => {
  const browser = await chromium.launch({ executablePath: CHROME, args: ["--no-sandbox"] });
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
  const pageErrors = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));
  try {
    await run(page);
  } catch (e) {
    check("exception", false, String(e && e.stack ? e.stack : e));
  }
  if (pageErrors.length) {
    console.log("PAGE-ERRORS " + JSON.stringify(pageErrors));
  }
  await browser.close();
  console.log("E2E RESULT: " + (failures.length === 0 ? "PASS" : "FAIL"));
  process.exit(failures.length === 0 ? 0 : 1);
})();
