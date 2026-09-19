// M9 T13：panels.js movementReadout 现走 blocks → 断言左栏"每格成本/预算/预计到达 tick"是真数值（非"—"）。
// 用 public API 规划路线（避开本机既有的点击不稳定），再选中单位触发左栏重渲染。
// 用法: node t13-panels.cjs <base-url> <out-json>
"use strict";
const fs = require("node:fs");
const { chromium } = require("playwright");
const BASE = process.argv[2];
const OUT = process.argv[3];
const CHROME = process.env.CHROME_PATH || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
const result = { base: BASE, assertions: [], pageErrors: [], consoleErrors: [], raw: {}, fatal: null };
function assert(id, pass, detail) {
  result.assertions.push({ id, pass: !!pass, detail: String(detail) });
}
async function main() {
  const browser = await chromium.launch({ executablePath: CHROME, headless: true, args: ["--no-sandbox", "--force-color-profile=srgb"] });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  page.setDefaultTimeout(60000);
  page.on("pageerror", (e) => result.pageErrors.push(String(e && e.message ? e.message : e)));
  page.on("console", (m) => { if (m.type() === "error") result.consoleErrors.push(m.text()); });
  await page.goto(BASE + "/", { waitUntil: "commit", timeout: 30000 });
  await page.waitForFunction(() => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(), null, { timeout: 60000, polling: 100 });
  await page.waitForFunction(() => { const s = document.getElementById("map-status"); return s && s.textContent && s.textContent.indexOf("已载入") >= 0; }, null, { timeout: 60000, polling: 100 });
  await page.waitForTimeout(500);

  const planned = await page.evaluate(async () => {
    const t = window.SimosApp.target();
    const qs = new URLSearchParams({ unit: "u-1", q: "1", r: "3", branch: t.branch });
    if (t.revision !== null && t.revision !== undefined) qs.set("revision", String(t.revision));
    const path = await (await fetch("/api/map/path?" + qs.toString())).json();
    if (!path.reachable || !path.path || path.path.length < 2) {
      return { error: "unreachable", path };
    }
    const res = await window.SimosApp.writeCommand("unit.PlanRoute", { id: "u-1", waypoints: path.path });
    return { ok: res && res.ok, result: res, pathLen: path.path.length };
  });
  result.raw.planned = planned;
  assert("route-planned", planned.ok === true, JSON.stringify(planned).slice(0, 200));

  await page.evaluate(() => window.SimosApp.setSelection({ kind: "unit", id: "u-1" }));
  await page.waitForFunction(() => {
    const d = document.getElementById("selection-detail");
    return d && d.textContent && d.textContent.indexOf("每格成本") >= 0;
  }, null, { timeout: 30000, polling: 100 });

  const text = await page.evaluate(() => (document.getElementById("selection-detail") || {}).textContent || "");
  result.raw.panel = text.replace(/\s+/g, " ").slice(0, 600);
  assert("panel-step-cost-numeric", /每格成本（毫 MP）[^a-zA-Z0-9]*[0-9]/.test(text), result.raw.panel);
  assert("panel-budget-numeric", /本 tick 预算（毫 MP）[^a-zA-Z0-9]*[0-9]/.test(text), result.raw.panel);
  assert("panel-total-cost-numeric", /路线总成本（毫 MP）[^a-zA-Z0-9]*[0-9]/.test(text), result.raw.panel);
  assert("panel-eta-tick", /预计到达 tick[^a-zA-Z0-9]*[0-9]/.test(text), result.raw.panel);
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
