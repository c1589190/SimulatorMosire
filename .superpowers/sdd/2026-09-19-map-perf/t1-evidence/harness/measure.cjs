// M9 T1 —— 大图性能基线实测（纯测量，不改任何产品代码）。
// 用法: NODE_PATH=<playwright> node measure.cjs <base-url> <out-json> <run-label>
// 输出：机器可读 baseline JSON（stdout + 文件）。
//
// 测量口径（都写在报告里）：
//  - firstFrameMs：页面内首个 CanvasRenderingContext2D.fill() 的 performance.now()（以导航为 0）。
//  - firstInteractiveMs：max(firstFrame, readyTs, loadedTs)，三个探针见 INIT_SCRIPT。
//  - pan/zoom 窗口：起止**都在页面内**用 performance.now() 标记（rAF 驱动），
//    避免 CDP evaluate 被主线程阻塞而把窗口拉长；窗口内采样 rAF 间隔。
//  - renderCostMs：直接连续调用 window.SimosMap.render() N 次，取每次耗时（同步绘制成本，非帧间隔）。
//  - requestTimeline：每个响应的 start/end 相对 performance.timeOrigin（导航），来自 Playwright timing()。
"use strict";

const fs = require("node:fs");
const { chromium } = require("playwright");

const BASE = process.argv[2];
const OUT = process.argv[3];
const LABEL = process.argv[4] || "run";

const CHROME =
  process.env.CHROME_PATH ||
  "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";

const VW = 1280;
const VH = 800;
const HARD_TIMEOUT_MS = 300000;
const PAN_MS = 3000;
const ZOOM_MS = 2000;
const RENDER_SAMPLES = 6;

const result = {
  label: LABEL,
  base: BASE,
  viewport: { width: VW, height: VH },
  chrome: CHROME,
  playwrightVersion: require("playwright/package.json").version,
  startedAt: new Date().toISOString(),
  requests: {},
  nonOk: [],
  totals: { apiBytes: 0, staticBytes: 0, allBytes: 0, apiCount: 0, staticCount: 0 },
  firstFillCallMs: null,
  firstFrameMs: null,
  firstInteractiveMs: null,
  interactiveCriterion: null,
  pageErrors: [],
  consoleErrors: [],
  stages: [],
  stagesReached: [],
  pan: null,
  zoom: null,
  renderCost: null,
  requestTimeline: [],
  longTasks: [],
  hung: false,
  hungAtStage: null,
  fatal: null,
};

function log(...args) {
  console.log("[" + LABEL + "]", ...args);
}

function stage(name) {
  result.stages.push({ name, at: Date.now() });
  result.stagesReached.push(name);
  log("stage:", name);
}

function pct(sorted, p) {
  if (!sorted.length) return null;
  const idx = (sorted.length - 1) * p;
  const lo = Math.floor(idx);
  const hi = Math.ceil(idx);
  if (lo === hi) return sorted[lo];
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo);
}

function stats(samples) {
  if (!samples.length) return { frames: 0, p50: null, p95: null, min: null, max: null, mean: null };
  const sorted = samples.slice().sort((a, b) => a - b);
  const mean = samples.reduce((a, b) => a + b, 0) / samples.length;
  return {
    frames: samples.length,
    p50: +pct(sorted, 0.5).toFixed(3),
    p95: +pct(sorted, 0.95).toFixed(3),
    min: +sorted[0].toFixed(3),
    max: +sorted[sorted.length - 1].toFixed(3),
    mean: +mean.toFixed(3),
  };
}

const INIT_SCRIPT = `
(function () {
  window.__probe = {
    firstFillTs: null,
    firstFillStyle: null,
    firstFillCallCount: 0,
    firstFramePaintedTs: null,
    frames: [],
    readyTs: null,
    loadedTs: null,
    errors: [],
    longTasks: [],
  };
  (function loop(ts) {
    window.__probe.frames.push(ts);
    // ★ 首个 fill 调用之后的第一个 rAF 回调 = 首次真正画到屏幕（render() 同步阻塞 5s，fill 在它内部早段）。
    if (
      window.__probe.firstFillTs !== null &&
      window.__probe.firstFramePaintedTs === null &&
      ts > window.__probe.firstFillTs
    ) {
      window.__probe.firstFramePaintedTs = ts;
    }
    window.requestAnimationFrame(loop);
  })(performance.now());
  try {
    var proto = CanvasRenderingContext2D.prototype;
    var origFill = proto.fill;
    proto.fill = function () {
      window.__probe.firstFillCallCount += 1;
      if (window.__probe.firstFillTs === null) {
        window.__probe.firstFillTs = performance.now();
        window.__probe.firstFillStyle = String(this.fillStyle);
      }
      return origFill.apply(this, arguments);
    };
  } catch (e) {
    window.__probe.errors.push("fill hook failed: " + e.message);
  }
  window.setInterval(function () {
    try {
      if (window.__probe.readyTs === null && window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady()) {
        window.__probe.readyTs = performance.now();
      }
      var st = document.getElementById("map-status");
      if (window.__probe.loadedTs === null && st && st.textContent && st.textContent.indexOf("已载入") >= 0) {
        window.__probe.loadedTs = performance.now();
      }
    } catch (e) {}
  }, 10);
  try {
    if (window.PerformanceObserver && PerformanceObserver.supportedEntryTypes && PerformanceObserver.supportedEntryTypes.indexOf("longtask") >= 0) {
      new PerformanceObserver(function (list) {
        list.getEntries().forEach(function (e) {
          window.__probe.longTasks.push({ start: +e.startTime.toFixed(1), dur: +e.duration.toFixed(1) });
        });
      }).observe({ entryTypes: ["longtask"] });
    }
  } catch (e) {}
  window.addEventListener("error", function (ev) {
    window.__probe.errors.push(String((ev && ev.message) || ev));
  });
})();
`;

async function main() {
  const wallStart = Date.now();
  const browser = await chromium.launch({
    executablePath: CHROME,
    headless: true,
    args: [
      "--no-sandbox",
      "--disable-background-timer-throttling",
      "--disable-renderer-backgrounding",
      "--disable-backgrounding-occluded-windows",
      "--force-color-profile=srgb",
    ],
  });
  const context = await browser.newContext({
    viewport: { width: VW, height: VH },
    deviceScaleFactor: 1,
  });
  const page = await context.newPage();
  page.setDefaultTimeout(60000);

  const requests = {};
  const responsePromises = [];

  const keyOf = (url) => {
    try {
      const u = new URL(url);
      return u.pathname + (u.search || "");
    } catch (e) {
      return url;
    }
  };

  page.on("request", (req) => {
    const key = keyOf(req.url());
    if (!requests[key]) requests[key] = { count: 0, method: req.method(), resourceType: req.resourceType() };
    requests[key].count += 1;
  });

  page.on("response", (resp) => {
    const key = keyOf(resp.url());
    const headers = resp.headers();
    const req = resp.request();
    let timing = null;
    try {
      timing = req.timing();
    } catch (e) {}
    const p = (async () => {
      let bodyBytes = null;
      try {
        const buf = await resp.body();
        bodyBytes = buf ? buf.length : null;
      } catch (e) {
        bodyBytes = null;
      }
      return { key, url: resp.url(), headers, bodyBytes, status: resp.status(), timing };
    })();
    responsePromises.push(p);
  });

  page.on("pageerror", (err) => {
    result.pageErrors.push(String(err && err.message ? err.message : err));
  });
  page.on("console", (msg) => {
    if (msg.type() === "error") {
      result.consoleErrors.push(msg.text());
    }
  });

  await context.addInitScript(INIT_SCRIPT);

  let hung = false;
  let hungAtStage = null;
  let fatal = null;

  try {
    stage("goto-start");
    await page.goto(BASE + "/", { waitUntil: "commit", timeout: 30000 });
    stage("goto-commit");

    const timeOrigin = await page.evaluate(() => performance.timeOrigin);
    result.timeOriginEpochMs = timeOrigin;

    const readyDeadline = Date.now() + 150000;
    let probe = null;
    while (Date.now() < readyDeadline) {
      let snapshot;
      try {
        snapshot = await Promise.race([
          page.evaluate(() => ({
            firstFillTs: window.__probe ? window.__probe.firstFillTs : null,
            firstFramePaintedTs: window.__probe ? window.__probe.firstFramePaintedTs : null,
            firstFillCallCount: window.__probe ? window.__probe.firstFillCallCount : null,
            readyTs: window.__probe ? window.__probe.readyTs : null,
            loadedTs: window.__probe ? window.__probe.loadedTs : null,
            hasMap: !!window.SimosMap,
            isReady: !!(window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady()),
            statusText: (document.getElementById("map-status") || {}).textContent || null,
            shellState: (document.getElementById("shell-state") || {}).textContent || null,
            frameCount: window.__probe ? window.__probe.frames.length : 0,
          })),
          new Promise((_, rej) => setTimeout(() => rej(new Error("evaluate-timeout")), 15000)),
        ]);
      } catch (e) {
        log("evaluate did not return within 15s (main thread busy):", e.message);
        snapshot = null;
      }
      if (snapshot) probe = snapshot;
      if (snapshot && snapshot.firstFillTs !== null && snapshot.loadedTs !== null) {
        break;
      }
      await new Promise((r) => setTimeout(r, 200));
    }

    result.firstFillCallMs = probe && probe.firstFillTs !== null ? +probe.firstFillTs.toFixed(1) : null;
    result.firstFrameMs =
      probe && probe.firstFramePaintedTs !== null ? +probe.firstFramePaintedTs.toFixed(1) : null;
    result.firstFillCallCount = probe ? probe.firstFillCallCount : null;
    result.readyTs = probe && probe.readyTs !== null ? +probe.readyTs.toFixed(1) : null;
    result.loadedTs = probe && probe.loadedTs !== null ? +probe.loadedTs.toFixed(1) : null;
    result.probeSnapshot = probe || null;
    result.interactiveCriterion =
      "SimosMap.isReady()===true 且 #map-status 文本含「已载入」（= overview+units 已取回并完成一次绘制）；取 firstFramePainted/readyTs/loadedTs 三者中最晚者";
    const candidates = [result.firstFrameMs, result.readyTs, result.loadedTs].filter((v) => v !== null);
    result.firstInteractiveMs = candidates.length ? +Math.max(...candidates).toFixed(1) : null;
    stage("ready");

    try {
      await page.waitForLoadState("load", { timeout: 20000 });
    } catch (e) {
      log("load state not reached within 20s");
    }
    stage("load");

    const box = await page.evaluate(() => {
      const c = document.getElementById("canvas");
      if (!c) return null;
      const r = c.getBoundingClientRect();
      return { x: r.x, y: r.y, w: r.width, h: r.height };
    });
    const cx = box ? Math.round(box.x + box.w / 2) : Math.round(VW / 2);
    const cy = box ? Math.round(box.y + box.h / 2) : Math.round(VH / 2);
    result.canvasBox = box;

    // ── 同步绘制成本（直接调 render()，不经 rAF） ──────────────────────
    try {
      const renderSamples = await page.evaluate((n) => {
        const out = [];
        for (let i = 0; i < n; i++) {
          const t0 = performance.now();
          window.SimosMap.render();
          out.push(performance.now() - t0);
        }
        return out;
      }, RENDER_SAMPLES);
      result.renderCost = stats(renderSamples);
      result.renderCost.samples = renderSamples.map((v) => +v.toFixed(2));
    } catch (e) {
      result.renderCost = { error: String(e.message) };
    }

    // 放大到能看见的格数很少时再测 render()——区分 O(19441) 扫描与 O(可见格) 绘制。
    try {
      const zoomedRender = await page.evaluate((n) => {
        const d = window.SimosMap.debug();
        window.SimosMap.setView({ scale: 3, tx: 0, ty: 0 });
        const out = [];
        for (let i = 0; i < n; i++) {
          const t0 = performance.now();
          window.SimosMap.render();
          out.push(performance.now() - t0);
        }
        const after = window.SimosMap.debug();
        return { samples: out, beforeScale: d.scale, afterScale: after.scale };
      }, RENDER_SAMPLES);
      result.renderCostZoomed = {
        stats: stats(zoomedRender.samples),
        samples: zoomedRender.samples.map((v) => +v.toFixed(2)),
        scale: zoomedRender.afterScale,
      };
      await page.evaluate(() => {
        if (window.SimosMap && window.SimosMap.resetView) window.SimosMap.resetView();
      });
    } catch (e) {
      result.renderCostZoomed = { error: String(e.message) };
    }

    // ── pan：连续拖动 3 秒（窗口起止都在页面内标记） ────────────────────
    await page.evaluate((ms) => {
      window.__probe.frames.length = 0;
      var start = performance.now();
      window.__panWindow = { start: start, end: null, stopAt: start + ms, frames: [] };
      (function w() {
        var now = performance.now();
        window.__panWindow.frames.push(now);
        if (now >= window.__panWindow.stopAt) {
          window.__panWindow.end = now;
          return;
        }
        window.requestAnimationFrame(w);
      })();
    }, PAN_MS);
    await page.mouse.move(cx, cy);
    await page.mouse.down();
    stage("pan-start");
    const panStart = Date.now();
    let step = 0;
    while (Date.now() - panStart < PAN_MS) {
      step += 1;
      const dx = Math.round(40 * Math.sin(step / 4));
      const dy = Math.round(40 * Math.cos(step / 4));
      await page.mouse.move(cx + dx, cy + dy, { steps: 1 });
      await new Promise((r) => setTimeout(r, 25));
    }
    await page.mouse.up();
    await page.waitForFunction(() => window.__panWindow && window.__panWindow.end !== null, null, {
      timeout: 60000,
      polling: 50,
    });
    stage("pan-end");

    const panData = await page.evaluate(() => {
      const w = window.__panWindow;
      const frames = w.frames;
      const deltas = [];
      for (let i = 1; i < frames.length; i++) deltas.push(frames[i] - frames[i - 1]);
      return {
        windowMs: +(w.end - w.start).toFixed(1),
        frames: frames.length,
        deltas,
        scale: window.SimosMap && window.SimosMap.debug ? window.SimosMap.debug().scale : null,
      };
    });
    result.pan = stats(panData.deltas);
    result.pan.framesInWindow = panData.frames;
    result.pan.windowMs = panData.windowMs;
    result.pan.scale = panData.scale;
    result.pan.deltas = panData.deltas.map((d) => +d.toFixed(1));

    await page.evaluate(() => {
      if (window.SimosMap && window.SimosMap.resetView) window.SimosMap.resetView();
    });
    await new Promise((r) => setTimeout(r, 500));

    // ── zoom：滚轮缩放 2 秒 ────────────────────────────────────────────
    await page.evaluate((ms) => {
      window.__probe.frames.length = 0;
      var start = performance.now();
      window.__zoomWindow = { start: start, end: null, stopAt: start + ms, frames: [] };
      (function w() {
        var now = performance.now();
        window.__zoomWindow.frames.push(now);
        if (now >= window.__zoomWindow.stopAt) {
          window.__zoomWindow.end = now;
          return;
        }
        window.requestAnimationFrame(w);
      })();
    }, ZOOM_MS);
    await page.mouse.move(cx, cy);
    stage("zoom-start");
    const zoomStart = Date.now();
    let zstep = 0;
    while (Date.now() - zoomStart < ZOOM_MS) {
      zstep += 1;
      const delta = zstep % 40 < 20 ? -120 : 120;
      await page.mouse.wheel(0, delta);
      await new Promise((r) => setTimeout(r, 20));
    }
    await page.waitForFunction(() => window.__zoomWindow && window.__zoomWindow.end !== null, null, {
      timeout: 60000,
      polling: 50,
    });
    stage("zoom-end");
    const zoomData = await page.evaluate(() => {
      const w = window.__zoomWindow;
      const frames = w.frames;
      const deltas = [];
      for (let i = 1; i < frames.length; i++) deltas.push(frames[i] - frames[i - 1]);
      return {
        windowMs: +(w.end - w.start).toFixed(1),
        frames: frames.length,
        deltas,
        scale: window.SimosMap && window.SimosMap.debug ? window.SimosMap.debug().scale : null,
      };
    });
    result.zoom = stats(zoomData.deltas);
    result.zoom.framesInWindow = zoomData.frames;
    result.zoom.windowMs = zoomData.windowMs;
    result.zoom.scale = zoomData.scale;
    result.zoom.deltas = zoomData.deltas.map((d) => +d.toFixed(1));

    // ── 面板交互健全性（功能性验证，非计时） ────────────────────────────
    stage("interactive-check");
    try {
      await page.evaluate(() => {
        if (window.SimosMap && window.SimosMap.resetView) window.SimosMap.resetView();
      });
      await new Promise((r) => setTimeout(r, 800));
      const target = await page.evaluate((pt) => {
        const pick = window.SimosMap.hexAtScreen ? window.SimosMap.hexAtScreen(pt) : null;
        return pick;
      }, { x: cx, y: cy });
      result.interactiveFunctional = { pickAtCenter: target };
      const before = await page.evaluate(
        () => (document.getElementById("selection-detail") || {}).textContent || ""
      );
      await page.mouse.click(cx, cy);
      // 等左栏真正渲染出详情（或超时），避免固定 sleep 撞上渲染卡顿而误判"没反应"。
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
        log("interactive-check: selection detail did not populate within 30s");
      }
      const after = await page.evaluate(
        () => (document.getElementById("selection-detail") || {}).textContent || ""
      );
      const status = await page.evaluate(
        () => (document.getElementById("left-status") || {}).textContent || ""
      );
      result.interactiveFunctional.beforeLen = before.length;
      result.interactiveFunctional.afterLen = after.length;
      result.interactiveFunctional.changed = before !== after;
      result.interactiveFunctional.afterSnippet = after.slice(0, 200);
      result.interactiveFunctional.leftStatus = status;
    } catch (e) {
      result.interactiveFunctional = { error: String(e.message) };
    }

    try {
      result.longTasks = await page.evaluate(() => (window.__probe.longTasks || []).slice());
    } catch (e) {}

    stage("done");
  } catch (e) {
    fatal = String(e && e.stack ? e.stack : e);
    hungAtStage = result.stagesReached[result.stagesReached.length - 1] || null;
    hung = true;
    log("FATAL/hung at stage:", hungAtStage, e.message);
    try {
      result.longTasks = await page.evaluate(() => (window.__probe.longTasks || []).slice());
    } catch (e2) {}
  }

  try {
    const responses = await Promise.all(responsePromises.map((p) => p.catch(() => null)));
    for (const r of responses) {
      if (!r) continue;
      if (!requests[r.key]) requests[r.key] = { count: 0, method: "?", resourceType: "?" };
      const rec = requests[r.key];
      rec.status = r.status;
      rec.contentLengthHeader = r.headers["content-length"] || null;
      rec.contentEncoding = r.headers["content-encoding"] || null;
      rec.bodyBytes = r.bodyBytes;
      rec.contentType = r.headers["content-type"] || null;
      if (r.status < 200 || r.status >= 300) {
        result.nonOk.push({ key: r.key, status: r.status });
      }
      if (r.status >= 200 && r.status < 300) {
        const bytes = r.bodyBytes !== null ? r.bodyBytes : Number(r.headers["content-length"] || 0);
        const isApi = r.key.indexOf("/api/") === 0;
        if (isApi) {
          result.totals.apiBytes += bytes;
          result.totals.apiCount += 1;
        } else {
          result.totals.staticBytes += bytes;
          result.totals.staticCount += 1;
        }
      }
      // 相对导航的时间线（★ Playwright timing()：startTime 是 epoch ms，
      // 其余字段（含 responseEnd）都是相对 startTime 的毫秒偏移）。
      if (r.timing && typeof r.timing.startTime === "number" && result.timeOriginEpochMs) {
        result.requestTimeline.push({
          key: r.key,
          status: r.status,
          bytes: r.bodyBytes,
          startMs: +(r.timing.startTime - result.timeOriginEpochMs).toFixed(1),
          endMs: +(r.timing.startTime + r.timing.responseEnd - result.timeOriginEpochMs).toFixed(1),
          durationMs: +r.timing.responseEnd.toFixed(1),
        });
      }
    }
  } catch (e) {
    result.responseCollectError = String(e.message);
  }
  result.totals.allBytes = result.totals.apiBytes + result.totals.staticBytes;
  result.requests = requests;
  result.requestTimeline.sort((a, b) => a.startMs - b.startMs);

  result.hung = hung;
  result.hungAtStage = hungAtStage;
  result.fatal = fatal;
  result.wallMs = Date.now() - wallStart;
  result.finishedAt = new Date().toISOString();

  await browser.close().catch(() => {});

  const json = JSON.stringify(result, null, 2);
  fs.writeFileSync(OUT, json);
  console.log("=== BASELINE JSON ===");
  console.log(json);
}

const timer = setTimeout(() => {
  console.error("[" + LABEL + "] HARD TIMEOUT " + HARD_TIMEOUT_MS + "ms — 进程自杀（结果未落盘）");
  process.exit(3);
}, HARD_TIMEOUT_MS);

main()
  .then(() => {
    clearTimeout(timer);
    process.exit(0);
  })
  .catch((e) => {
    clearTimeout(timer);
    console.error("[" + LABEL + "] harness crash:", e);
    try {
      result.fatal = String(e && e.stack ? e.stack : e);
      fs.writeFileSync(OUT, JSON.stringify(result, null, 2));
    } catch (e2) {}
    process.exit(1);
  });
