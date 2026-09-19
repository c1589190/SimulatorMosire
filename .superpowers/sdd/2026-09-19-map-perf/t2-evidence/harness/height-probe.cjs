// M9 T5 —— 运行时核实 /api/map/overview 的 `height` 是否被前端使用。
// 手法：addInitScript 包装 window.fetch，把 overview 响应里每个 hex 的 height 删掉，
//       再跑一遍首屏 + 点选；若无 pageerror 且功能正常 ⇒ 未使用。
// 对照：同脚本 STRIP=0 跑一遍（不删）作为阴性对照。
// 用法: NODE_PATH=<playwright> STRIP=1 node height-probe.cjs <base-url> <out-json> [label]
"use strict";

const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const OUT = process.argv[3];
const LABEL = process.argv[4] || "probe";
const STRIP = process.env.STRIP !== "0";
const CHROME =
  process.env.CHROME_PATH ||
  "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";

const result = {
  label: LABEL,
  strip: STRIP,
  base: BASE,
  startedAt: new Date().toISOString(),
  strippedCount: 0,
  ready: false,
  pageErrors: [],
  consoleErrors: [],
  pickAtCenter: null,
  selectionAfter: null,
  leftStatus: null,
  legend: null,
  fatal: null,
};

const INIT = `
(function () {
  window.__probe = { stripped: 0, overviewHits: 0 };
  var origFetch = window.fetch;
  window.fetch = function (input, init) {
    var url = typeof input === "string" ? input : (input && input.url) || "";
    var p = origFetch.apply(this, arguments);
    if (url.indexOf("/api/map/overview") >= 0 && ${STRIP}) {
      return p.then(function (resp) {
        return resp.text().then(function (text) {
          try {
            var body = JSON.parse(text);
            window.__probe.overviewHits += 1;
            (body.hexes || []).forEach(function (h) {
              if (Object.prototype.hasOwnProperty.call(h, "height")) {
                delete h.height;
                window.__probe.stripped += 1;
              }
            });
            return new Response(JSON.stringify(body), {
              status: resp.status,
              statusText: resp.statusText,
              headers: resp.headers,
            });
          } catch (e) {
            return new Response(text, { status: resp.status, statusText: resp.statusText, headers: resp.headers });
          }
        });
      });
    }
    return p;
  };
})();
`;

async function main() {
  const browser = await chromium.launch({
    executablePath: CHROME,
    headless: true,
    args: ["--no-sandbox", "--disable-background-timer-throttling", "--force-color-profile=srgb"],
  });
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1 });
  await context.addInitScript(INIT);
  const page = await context.newPage();
  page.setDefaultTimeout(120000);
  page.on("pageerror", (e) => result.pageErrors.push(String(e && e.message ? e.message : e)));
  page.on("console", (m) => {
    if (m.type() === "error") result.consoleErrors.push(m.text());
  });

  try {
    await page.goto(BASE + "/", { waitUntil: "commit", timeout: 30000 });
    await page.waitForFunction(
      () =>
        window.SimosMap &&
        window.SimosMap.isReady &&
        window.SimosMap.isReady() &&
        (document.getElementById("map-status") || {}).textContent &&
        document.getElementById("map-status").textContent.indexOf("已载入") >= 0,
      null,
      { timeout: 120000, polling: 200 }
    );
    result.ready = true;
    result.strippedCount = await page.evaluate(() => window.__probe.stripped);
    result.overviewHits = await page.evaluate(() => window.__probe.overviewHits);

    const box = await page.evaluate(() => {
      const c = document.getElementById("canvas");
      const r = c.getBoundingClientRect();
      return { x: r.x, y: r.y, w: r.width, h: r.height };
    });
    const cx = Math.round(box.x + box.w / 2);
    const cy = Math.round(box.y + box.h / 2);
    await page.mouse.click(cx, cy);
    try {
      await page.waitForFunction(
        () => {
          const d = document.getElementById("selection-detail");
          return d && d.textContent && d.textContent.trim().length > 0;
        },
        null,
        { timeout: 30000, polling: 100 }
      );
    } catch (e) {
      /* 记到 pageErrors 之外，单独看 */
    }
    result.pickAtCenter = await page.evaluate((pt) => window.SimosMap.hexAtScreen(pt), { x: cx, y: cy });
    result.selectionAfter = await page.evaluate(
      () => (document.getElementById("selection-detail") || {}).textContent || ""
    );
    result.leftStatus = await page.evaluate(() => (document.getElementById("left-status") || {}).textContent || "");
    result.legend = await page.evaluate(() => (document.getElementById("legend") || {}).textContent || "");
  } catch (e) {
    result.fatal = String(e && e.stack ? e.stack : e);
  }
  await browser.close().catch(() => {});
  result.finishedAt = new Date().toISOString();
  fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
}

main().catch((e) => {
  console.error("probe crash:", e);
  process.exit(1);
});
