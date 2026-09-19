// M8 T1 e2e —— 多从属（区域从属多对多）。
// 覆盖：c1 真实重叠数据下 /api/map/hex 的 regions 数组含 ≥2 个 id 且按字典序；c2 同一 revision 两次逐字节相同；
//       c3 浏览器点击重叠 hex ⇒ 左栏列出 ≥2 个区域。
// 用法: NODE_PATH=<playwright> node e2e.cjs <base-url> <out-dir>
// 退出码：0 = 全部 PASS；1 = 有 FAIL（变异轮据此观察红点）。
"use strict";

const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const OUT = process.argv[3] || ".";
const VW = 1280;
const VH = 800;

const failures = [];
const values = {};

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

(async () => {
  fs.mkdirSync(OUT, { recursive: true });

  // ── 找一对重叠区域（用真实导入数据里已知的两个测试区域）──
  const overview = await api("/api/map/overview");
  const regionIds = ((overview.body && overview.body.regions) || []).map((r) => r.id);
  const leftId = "test_annex_target";
  const rightId = "test_nation";
  check(
    "a-overlap-fixture-present",
    regionIds.indexOf(leftId) >= 0 && regionIds.indexOf(rightId) >= 0,
    JSON.stringify(regionIds)
  );
  const left = await api("/api/map/region/" + encodeURIComponent(leftId));
  const right = await api("/api/map/region/" + encodeURIComponent(rightId));
  const rightSet = new Set(((right.body && right.body.hexes) || []).map((h) => h.q + "," + h.r));
  const overlap = ((left.body && left.body.hexes) || [])
    .map((h) => ({ q: h.q, r: h.r }))
    .filter((h) => rightSet.has(h.q + "," + h.r));
  check("b-overlap-nonempty", overlap.length >= 1, "overlap=" + overlap.length);
  const hex = overlap[0];
  values.hex = hex;

  // ── c1/c2：/api/map/hex 的 regions ──
  const first = await api("/api/map/hex?q=" + hex.q + "&r=" + hex.r);
  const second = await api("/api/map/hex?q=" + hex.q + "&r=" + hex.r);
  const regions = (first.body && first.body.regions) || null;
  check("c1-regions-is-array", Array.isArray(regions), JSON.stringify(first.body));
  const hasTwo = Array.isArray(regions) && regions.length >= 2;
  check("c1-regions-has-at-least-two", hasTwo, "regions=" + JSON.stringify(regions));
  check(
    "c1-regions-lexicographic",
    hasTwo && regions.join("\u0000") === regions.slice().sort().join("\u0000"),
    JSON.stringify(regions)
  );
  check(
    "c2-two-calls-byte-identical",
    first.text.length > 0 && first.text === second.text,
    "len=" + first.text.length + " md5diff=" + (first.text === second.text ? "none" : "yes")
  );
  values.rawJson = first.text;
  console.log("RAW /api/map/hex " + JSON.stringify({ url: "/api/map/hex?q=" + hex.q + "&r=" + hex.r, body: first.text }));

  // ── c3：浏览器点击重叠 hex ⇒ 左栏列出 ≥2 个区域 ──
  const executablePath =
    process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
  const browser = await chromium.launch({ headless: true, executablePath });
  const page = await browser.newPage({ viewport: { width: VW, height: VH } });
  const pageErrors = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));
  const hexRequests = [];
  page.on("request", (req) => {
    if (req.url().indexOf("/api/map/hex") >= 0) {
      const parsed = new URL(req.url());
      hexRequests.push({ method: req.method(), path: parsed.pathname, query: parsed.search });
    }
  });

  await page.goto(BASE + "/", { waitUntil: "domcontentloaded" });
  await page.waitForFunction(() => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(), null, {
    timeout: 40000,
  });
  await page.waitForFunction(() => document.getElementById("map-status").textContent.indexOf("已载入") >= 0, null, {
    timeout: 30000,
  });
  await sleep(400);

  const canvas = page.locator("#canvas");
  await canvas.scrollIntoViewIfNeeded();
  const pt = await page.evaluate(([q, r]) => window.SimosMap.screenPointOf(q, r), [hex.q, hex.r]);
  const box = await canvas.boundingBox();
  const inView =
    pt && box && pt.x >= 0 && pt.y >= 0 && pt.x <= box.width && pt.y <= box.height;
  check("c3-hex-on-screen", !!inView, JSON.stringify({ pt: pt, box: box }));
  await page.mouse.click(box.x + pt.x, box.y + pt.y);
  await page
    .waitForFunction(
      (ids) => {
        const el = document.getElementById("selection-detail");
        const text = el ? el.textContent : "";
        return ids.every((id) => text.indexOf(id) >= 0);
      },
      [leftId, rightId],
      { timeout: 8000 }
    )
    .catch(() => null);

  const detailText = await page.evaluate(() => {
    const el = document.getElementById("selection-detail");
    return el ? el.textContent : "";
  });
  check(
    "c3-panel-lists-two-regions",
    detailText.indexOf(leftId) >= 0 && detailText.indexOf(rightId) >= 0,
    JSON.stringify(detailText)
  );
  check("c3-requested-map-hex", hexRequests.length >= 1, JSON.stringify(hexRequests));
  check("c3-no-pageerror", pageErrors.length === 0, JSON.stringify(pageErrors));

  values.detail = detailText;
  values.hexRequests = hexRequests;
  values.pageErrors = pageErrors;
  writeJson("result.json", values);

  await browser.close();
  console.log("E2E RESULT: " + (failures.length === 0 ? "ALL PASS" : failures.length + " FAIL " + failures.join(",")));
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((e) => {
  console.log("E2E CRASH: " + e.stack);
  process.exit(2);
});
