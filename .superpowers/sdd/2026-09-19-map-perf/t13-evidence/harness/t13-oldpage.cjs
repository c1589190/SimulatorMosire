// M9 T13：旧调试页 /map（map.html + 共享 map.js + blocks.js）仍可加载、无 pageerror、块已载入、点选可用。
"use strict";
const fs = require("node:fs");
const { chromium } = require("playwright");
const BASE = process.argv[2];
const OUT = process.argv[3];
const CHROME = process.env.CHROME_PATH || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
const result = { base: BASE, assertions: [], pageErrors: [], consoleErrors: [], raw: {}, fatal: null };
function assert(id, pass, detail) { result.assertions.push({ id, pass: !!pass, detail: String(detail) }); }
async function main() {
  const browser = await chromium.launch({ executablePath: CHROME, headless: true, args: ["--no-sandbox", "--force-color-profile=srgb"] });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  page.setDefaultTimeout(60000);
  page.on("pageerror", (e) => result.pageErrors.push(String(e && e.message ? e.message : e)));
  page.on("console", (m) => { if (m.type() === "error") result.consoleErrors.push(m.text()); });
  const resp = await page.goto(BASE + "/map", { waitUntil: "commit", timeout: 30000 });
  result.raw.status = resp.status();
  assert("map-page-200", resp.status() === 200, "status=" + resp.status());
  await page.waitForFunction(() => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(), null, { timeout: 60000, polling: 100 });
  await page.waitForFunction(() => { const s = document.getElementById("map-status"); return s && s.textContent && s.textContent.indexOf("已载入") >= 0; }, null, { timeout: 60000, polling: 100 });
  await page.waitForTimeout(600);
  const dbg = await page.evaluate(() => window.SimosMap.debug());
  result.raw.debug = { blockCount: dbg.blockCount, hexCount: dbg.hexCount, unmergedCount: dbg.unmergedCount };
  assert("old-page-has-blocks", dbg.blockCount === 44 && dbg.hexCount === 19441, JSON.stringify(result.raw.debug));
  // 点一格：旧页 loadHex → /api/map/hex，左栏出现 height（旧页保留 height）
  const pt = await page.evaluate(() => window.SimosMap.screenPointOf(0, 0));
  const box = await page.evaluate(() => { const r = document.getElementById("canvas").getBoundingClientRect(); return { x: r.x, y: r.y }; });
  await page.mouse.click(box.x + pt.x, box.y + pt.y);
  await page.waitForFunction(() => { const d = document.getElementById("hex-detail"); return d && d.textContent && d.textContent.indexOf("terrain") >= 0; }, null, { timeout: 30000, polling: 100 });
  result.raw.hexDetail = await page.evaluate(() => (document.getElementById("hex-detail") || {}).textContent || "");
  assert("old-page-click-reads-hex", result.raw.hexDetail.indexOf("plains") >= 0, result.raw.hexDetail.replace(/\s+/g, " ").slice(0, 160));
  assert("no-page-errors", result.pageErrors.length === 0, JSON.stringify(result.pageErrors));
  await browser.close();
  const passCount = result.assertions.filter((a) => a.pass).length;
  result.summary = `${passCount}/${result.assertions.length} PASS`;
  fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
  console.log(result.summary);
  for (const a of result.assertions) if (!a.pass) console.log("  FAIL", a.id, "|", a.detail);
  process.exit(passCount === result.assertions.length && result.pageErrors.length === 0 ? 0 : 1);
}
main().catch((e) => { result.fatal = String(e && e.stack ? e.stack : e); try { fs.writeFileSync(OUT, JSON.stringify(result, null, 2)); } catch (e2) {} console.error("fatal:", result.fatal); process.exit(1); });
