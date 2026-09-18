// t9b-screenshot.mjs —— M5 T9b 证据：四页截图 + /map 单位标记像素核验 + 点击标记读 id/名称。
// 用法: PLAYWRIGHT_MODULE=... PLAYWRIGHT_BROWSER=... node t9b-screenshot.mjs <baseUrl> <outDir>
import { mkdirSync } from "node:fs";
import { resolve, join } from "node:path";

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
  await page.waitForTimeout(700);
  const file = resolve(outDir, p.name + ".png");
  await page.screenshot({ path: file, fullPage: true });
  console.log("SHOT " + p.path + " -> " + file);
}

// /map：单位标记像素核验（#e8503a = rgb(232,80,58)）
await page.goto(baseUrl + "/map", { waitUntil: "networkidle" });
await page.waitForTimeout(900);
const mapStatus = await page.locator("#map-status").textContent();
console.log("MAP_STATUS " + (mapStatus || "").trim());

const marker = await page.evaluate(() => {
  const c = document.getElementById("canvas");
  const ctx = c.getContext("2d");
  const { width, height } = c;
  const d = ctx.getImageData(0, 0, width, height).data;
  let sx = 0;
  let sy = 0;
  let n = 0;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4;
      if (Math.abs(d[i] - 232) < 20 && Math.abs(d[i + 1] - 80) < 20 && Math.abs(d[i + 2] - 58) < 20) {
        sx += x;
        sy += y;
        n++;
      }
    }
  }
  return n ? { x: sx / n, y: sy / n, n, width, height } : null;
});
console.log("MARKER_PIXELS " + (marker ? marker.n : 0));

let clickStatus = "";
if (marker) {
  const rect = await page.evaluate(() => {
    const r = document.getElementById("canvas").getBoundingClientRect();
    return { left: r.left, top: r.top, width: r.width, height: r.height };
  });
  const clientX = rect.left + (marker.x / marker.width) * rect.width;
  const clientY = rect.top + (marker.y / marker.height) * rect.height;

  // 悬停：标记上应出现 id/名称的 tooltip + pointer 光标
  await page.mouse.move(clientX, clientY);
  await page.waitForTimeout(250);
  console.log("HOVER_TITLE " + ((await page.evaluate(() => document.getElementById("canvas").title)) || ""));
  console.log("HOVER_CURSOR " + ((await page.evaluate(() => document.getElementById("canvas").style.cursor)) || ""));

  // 点击：状态区应显示"单位 id：名称"
  await page.mouse.click(clientX, clientY);
  await page.waitForTimeout(600);
  clickStatus = (await page.locator("#hex-status").textContent()) || "";
  console.log("CLICK_STATUS " + clickStatus.trim());
  await page.screenshot({ path: resolve(outDir, "map-unit-selected.png"), fullPage: true });
  console.log("SHOT /map clicked marker -> map-unit-selected.png");
}

// /unit 与 /social 的 JS 真跑核验
await page.goto(baseUrl + "/unit", { waitUntil: "networkidle" });
await page.waitForTimeout(500);
const unitRows = await page.locator("#units-table tbody tr").count();
console.log("UNIT_ROWS " + unitRows);
console.log("UNIT_STATUS " + ((await page.locator("#units-status").textContent()) || "").trim());

await page.goto(baseUrl + "/social?q=1&r=1", { waitUntil: "networkidle" });
await page.waitForTimeout(400);
await page.locator("#query").click();
await page.waitForTimeout(500);
console.log("SOCIAL_STATUS " + ((await page.locator("#query-status").textContent()) || "").trim());

console.log("JS_ERRORS " + errors.length);
for (const e of errors) console.log("JSERR " + e);
console.log("HTTP_FAILURES " + failed.length);
for (const f of failed) console.log("HTTPFAIL " + f);

await browser.close();
