// t9-screenshot.mjs —— 用 playwright 对三页截图（M5 T9 证据）。用法: node t9-screenshot.mjs <baseUrl> <outDir>
// ★ 本文件是一次性证据工具，不入库交付（见 t9-report.md 的证据索引说明）。
import { mkdirSync } from "node:fs";
import { resolve, join } from "node:path";

// playwright 只存在于 npx 缓存，不是本项目依赖（无 npm）⇒ 按 PLAYWRIGHT_MODULE 或 npx 缓存路径解析。
const playwrightDir = process.env.PLAYWRIGHT_MODULE;
if (!playwrightDir) {
  throw new Error("需设 PLAYWRIGHT_MODULE 指向 playwright 包目录");
}
const { chromium } = await import(join(playwrightDir, "index.mjs"));

const baseUrl = process.argv[2];
const outDir = resolve(process.argv[3]);
mkdirSync(outDir, { recursive: true });

const pages = [
  { path: "/", name: "index" },
  { path: "/map", name: "map" },
  { path: "/unit", name: "unit" },
  { path: "/social", name: "social" },
];

const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.PLAYWRIGHT_BROWSER || undefined,
});
const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const page = await context.newPage();
const errors = [];
const failed = [];
page.on("pageerror", (e) => errors.push("pageerror: " + e.message));
page.on("requestfailed", (r) => failed.push(r.url() + " :: " + (r.failure() || {}).errorText));
page.on("response", (r) => {
  if (r.status() >= 400) {
    failed.push("HTTP " + r.status() + " " + r.url());
  }
});
page.on("console", (m) => {
  if (m.type() === "error") errors.push("console.error: " + m.text());
});

for (const p of pages) {
  await page.goto(baseUrl + p.path, { waitUntil: "networkidle", timeout: 15000 });
  // 地图页等 canvas 绘制完成
  await page.waitForTimeout(600);
  const file = resolve(outDir, p.name + ".png");
  await page.screenshot({ path: file, fullPage: true });
  console.log("SHOT " + p.path + " -> " + file);
}

// 额外：在 /unit 页选一个单位 + 在 /social 页查询一次，验证 JS 真跑起来
await page.goto(baseUrl + "/unit", { waitUntil: "networkidle" });
await page.waitForTimeout(400);
const unitRows = await page.locator("#units-table tbody tr").count();
console.log("UNIT_ROWS " + unitRows);
if (unitRows > 0) {
  await page.locator("#units-table tbody tr").first().click();
  await page.waitForTimeout(500);
  await page.screenshot({ path: resolve(outDir, "unit-detail.png"), fullPage: true });
  console.log("SHOT /unit detail -> unit-detail.png");
}
const unitStatus = await page.locator("#units-status").textContent();
console.log("UNIT_STATUS " + (unitStatus || "").trim());

await page.goto(baseUrl + "/social?q=1&r=1", { waitUntil: "networkidle" });
await page.waitForTimeout(300);
await page.locator("#query").click();
await page.waitForTimeout(500);
await page.screenshot({ path: resolve(outDir, "social-query.png"), fullPage: true });
console.log("SHOT /social query -> social-query.png");
console.log("SOCIAL_STATUS " + ((await page.locator("#query-status").textContent()) || "").trim());

await page.goto(baseUrl + "/map", { waitUntil: "networkidle" });
await page.waitForTimeout(700);
const mapStatus = await page.locator("#map-status").textContent();
console.log("MAP_STATUS " + (mapStatus || "").trim());
await page.screenshot({ path: resolve(outDir, "map-loaded.png"), fullPage: true });
console.log("SHOT /map loaded -> map-loaded.png");

console.log("JS_ERRORS " + errors.length);
for (const e of errors) console.log("JSERR " + e);
console.log("HTTP_FAILURES " + failed.length);
for (const f of failed) console.log("HTTPFAIL " + f);

await browser.close();
