// V3 e2e —— 区域查看模式点格 ⇒ 选中「拥有该 hex 的最顶层区域」= 定义序（GameMap.regions 插入序）末位。
// 真 Chromium + 真富世界（--demo：59223 hex / 252 区域）。夹具让**定义序与字典序分叉**：
//   候选 hex 的 owners 按 overview 序号（定义序）排序的末位 ≠ 字典序末位；再给**像素/取色证据**。
// 用法: node e2e.cjs <base-url> <out-dir>
"use strict";

const fs = require("node:fs");
const { chromium } = require(process.env.PW_MODULE || "/home/cna/.npm/_npx/e41f203b7505f1fb/node_modules/playwright");

const BASE = process.argv[2] || "http://127.0.0.1:5871";
const OUT = process.argv[3] || ".";
const CHROME = process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";

const failures = [];
const values = {};
function check(name, ok, detail) {
  console.log("STEP " + name + ": " + (ok ? "PASS" : "FAIL") + (detail === undefined ? "" : " " + JSON.stringify(detail)));
  if (!ok) failures.push(name);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function dist(p, e) {
  return Math.sqrt((p.r - e.r) ** 2 + (p.g - e.g) ** 2 + (p.b - e.b) ** 2);
}

(async () => {
  const browser = await chromium.launch({ executablePath: CHROME, args: ["--no-sandbox"] });
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  page.on("pageerror", (e) => failures.push("pageerror:" + e.message));
  const nonGet = [];
  page.on("request", (req) => {
    if (req.method() !== "GET") {
      const u = new URL(req.url());
      nonGet.push({ method: req.method(), path: u.pathname, post: req.postData() });
    }
  });

  await page.goto(BASE + "/", { waitUntil: "domcontentloaded" });
  await page.waitForFunction(() => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(), null, { timeout: 90000 });
  await sleep(2500);

  const api = async (p) =>
    await page.evaluate(async (url) => {
      const r = await fetch(url);
      let b = null;
      try {
        b = await r.json();
      } catch (e) {}
      return { status: r.status, body: b };
    }, p);

  // 1) overview：数组下标 = GameMap.regions 的插入序（定义序）
  const ov = await api("/api/map/overview");
  const regions = (ov.body && ov.body.regions) || [];
  const rank = {};
  regions.forEach((r, i) => {
    rank[r.id] = i;
  });
  values.overview = { status: ov.status, count: regions.length };

  // 2) 找一个「定义序末位 ≠ 字典序末位」的多从属 hex（真档已知候选；要求真分叉）
  const candidates = [[54, -67], [33, 19], [-99, 64], [29, 16], [-10, -55], [45, -40]];
  let pick = null;
  for (const [q, r] of candidates) {
    const h = await api("/api/map/hex?q=" + q + "&r=" + r);
    if (h.status !== 200 || !Array.isArray(h.body && h.body.regions)) continue;
    const owners = h.body.regions.slice();
    if (owners.length < 2) continue;
    const defSorted = owners.slice().sort((a, b) => rank[a] - rank[b]);
    const lexSorted = owners.slice().sort();
    const defLast = defSorted[defSorted.length - 1];
    const lexLast = lexSorted[lexSorted.length - 1];
    if (defLast === lexLast) continue;
    pick = { q, r, owners, defSorted, lexSorted, defLast, lexLast };
    break;
  }
  values.pick = pick;
  check(
    "a1-found-a-multi-owner-hex-where-definition-and-lexicographic-diverge",
    !!pick,
    pick && { hex: [pick.q, pick.r], owners: pick.owners, defLast: pick.defLast, lexLast: pick.lexLast }
  );
  if (!pick) {
    await page.screenshot({ path: OUT + "/v3-no-diverging-fixture.png" });
    fs.writeFileSync(OUT + "/e2e-values.json", JSON.stringify(values, null, 2));
    fs.writeFileSync(OUT + "/e2e-failures.json", JSON.stringify(failures, null, 2));
    await browser.close();
    console.log("E2E FAILURES: " + JSON.stringify(failures));
    process.exit(1);
  }

  // 3) /api/map/hex 的 regions 本身就是定义序（按 overview 序号排 = API 返回序）
  check(
    "a2-hex-regions-are-in-definition-order",
    JSON.stringify(pick.owners) === JSON.stringify(pick.defSorted),
    { owners: pick.owners, defSorted: pick.defSorted }
  );
  check("a3-definition-last-is-not-lexicographic-last", pick.defLast !== pick.lexLast, {
    defLast: pick.defLast,
    lexLast: pick.lexLast,
  });

  const topDetail = (await api("/api/map/region/" + encodeURIComponent(pick.defLast))).body;
  const wrongDetail = (await api("/api/map/region/" + encodeURIComponent(pick.lexLast))).body;
  values.colors = {
    top: { id: pick.defLast, meta: topDetail && topDetail.meta },
    wrong: { id: pick.lexLast, meta: wrongDetail && wrongDetail.meta },
  };

  // 4) 把该 hex 摆到视口中心、scale=3 ⇒ 可点、可采像素
  const place = async (q, r) =>
    await page.evaluate(
      ([qq, rr]) => {
        const M = window.SimosMap;
        const w = M.hexToPixel(qq, rr, M.BASE_CELL);
        M.setView({ scale: 3, tx: 620 - w.x * 3, ty: 360 - w.y * 3 });
      },
      [q, r]
    );

  // ★ 先在 view 模式采地形（region 模式有 scrim，会污染基线）
  await page.click('#mode-bar button[data-mode="view"]').catch(() => {});
  await place(pick.q, pick.r);
  await sleep(500);
  const posView = await page.evaluate(([q, r]) => window.SimosMap.screenPointOf(q, r), [pick.q, pick.r]);
  const box = await page.locator("#canvas").boundingBox();
  const terrain = await page.evaluate(([x, y]) => window.SimosMap.pixelAt(x, y), [posView.x, posView.y]);
  const hit = await page.evaluate(
    ([x, y]) => {
      const el = document.elementFromPoint(x, y);
      return el ? el.id || el.tagName : "none";
    },
    [box.x + posView.x, box.y + posView.y]
  );
  values.pixels = { posView, box, terrain, hit };
  check("a4-hex-screen-point-is-over-the-canvas", hit === "canvas" || hit === "CANVAS", { hit });

  // 5) 切区域查看模式、关区域名（免得标签污染中心像素）、点格
  await page.click('#mode-bar button[data-mode="region"]');
  await sleep(600);
  const toggle = page.locator("#region-name-toggle");
  if ((await toggle.count()) > 0) {
    await toggle.uncheck().catch(() => {});
    await sleep(300);
  }
  await place(pick.q, pick.r);
  await sleep(500);
  const pos = await page.evaluate(([q, r]) => window.SimosMap.screenPointOf(q, r), [pick.q, pick.r]);
  await page.locator("#canvas").scrollIntoViewIfNeeded();
  await page.mouse.click(box.x + pos.x, box.y + pos.y);
  // ★ 高亮要先把 252 个区域的 hex 列表全拉齐（N+1 的代价）才画得出来 —— 等它真的落到该格（否则读到的是 scrim-only）。
  await page
    .waitForFunction(
      ([q, r]) => {
        const M = window.SimosMap;
        return M.regionViewDebug().highlightRegions.length === 1 && M.regionHighlightAt(q, r) !== null;
      },
      [pick.q, pick.r],
      { timeout: 90000 }
    )
    .catch(() => {});
  await sleep(400);

  const rv = await page.evaluate(
    ([q, r]) => {
      const M = window.SimosMap;
      const d = M.regionViewDebug();
      d.at = M.regionHighlightAt(q, r);
      return d;
    },
    [pick.q, pick.r]
  );
  values.regionView = {
    highlightRegions: rv.highlightRegions,
    highlightKind: rv.highlightKind,
    at: rv.at,
    singleFocusAlpha: rv.singleFocusAlpha,
    fadedRegions: rv.fadedRegions,
  };
  check(
    "b1-click-selects-exactly-the-definition-last-region",
    JSON.stringify(rv.highlightRegions) === JSON.stringify([pick.defLast]) && rv.highlightRegions.length === 1,
    { selected: rv.highlightRegions, expected: [pick.defLast] }
  );
  check(
    "b2-not-the-lexicographic-last-and-not-all-owners",
    rv.highlightRegions.indexOf(pick.lexLast) < 0 && rv.highlightRegions.length < pick.owners.length,
    { selected: rv.highlightRegions, lexLast: pick.lexLast, owners: pick.owners }
  );
  check("b3-kind-is-single", rv.highlightKind === "single", { kind: rv.highlightKind });

  const expectedTopColor = await page.evaluate((meta) => window.SimosMap.resolveRegionColor(meta), topDetail.meta);
  const wrongColor = await page.evaluate((meta) => window.SimosMap.resolveRegionColor(meta), wrongDetail.meta);
  check(
    "b4-highlight-color-is-the-top-region-color-at-single-alpha",
    rv.at && rv.at.color === expectedTopColor && Math.abs(rv.at.alpha - rv.singleFocusAlpha) < 1e-9,
    { at: rv.at, expectedTopColor, singleFocusAlpha: rv.singleFocusAlpha }
  );

  // 6) 像素证明（独立复算）：observed ≈ 0.62·top + 0.38·(0.45·terrain + 0.55·#0a0d12)
  const observed = await page.evaluate(([x, y]) => window.SimosMap.pixelAt(x, y), [pos.x, pos.y]);
  const hex2rgb = (h) => ({ r: parseInt(h.slice(1, 3), 16), g: parseInt(h.slice(3, 5), 16), b: parseInt(h.slice(5, 7), 16) });
  const dimc = { r: 10, g: 13, b: 18 }; // #0a0d12
  const mix = (a, b, t) => ({ r: a.r * t + b.r * (1 - t), g: a.g * t + b.g * (1 - t), b: a.b * t + b.b * (1 - t) });
  const S = mix(dimc, terrain, 0.55); // scrim over terrain
  const a = rv.singleFocusAlpha;
  const pTop = mix(hex2rgb(expectedTopColor), S, a);
  const pWrong = mix(hex2rgb(wrongColor), S, a);
  const dTop = dist(observed, pTop);
  const dWrong = dist(observed, pWrong);
  values.pixelProof = { observed, terrain, singleAlpha: a, expectedTop: pTop, expectedWrong: pWrong, distTop: dTop, distWrong: dWrong, distTopWrong: dist(pTop, pWrong) };
  check("c1-observed-pixel-matches-top-region-not-the-wrong-one", dTop < dWrong && dTop <= 40, values.pixelProof);
  check("c2-pixel-discriminant-is-real", dist(pTop, pWrong) > 20, { distTopWrong: dist(pTop, pWrong) });

  await page.screenshot({ path: OUT + "/v3-click-top-region.png" });

  // 7) 只读：区域查看模式的点格不写盘
  check("d1-map-click-in-region-mode-is-read-only", nonGet.length === 0, { nonGet });

  fs.writeFileSync(OUT + "/e2e-values.json", JSON.stringify(values, null, 2));
  fs.writeFileSync(OUT + "/e2e-failures.json", JSON.stringify(failures, null, 2));
  await browser.close();
  if (failures.length) {
    console.log("E2E FAILURES: " + JSON.stringify(failures));
    process.exit(1);
  }
  console.log("E2E OK");
})().catch((e) => {
  console.error("E2E THREW: " + (e && e.stack ? e.stack : e));
  fs.writeFileSync(OUT + "/e2e-values.json", JSON.stringify(values, null, 2));
  fs.writeFileSync(OUT + "/e2e-failures.json", JSON.stringify(failures.concat(["threw:" + (e && e.message)]), null, 2));
  process.exit(2);
});
