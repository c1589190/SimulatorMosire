// M8 T10 e2e —— 区域编辑模式（真档副本、真 ShellMain、真 pointer 事件）。
// 覆盖：a 进区域编辑；b ★重叠正例（画与已有区域重叠的新区域 ⇒ 成功 + regions 列出多个 + 反向证据）；
//       c 其他区域淡色（focus 集合 + 高亮色确实分叉）；d 改已有区域 hex（增/删 + 前后值）；
//       e 删除二次确认（未确认零写 / 确认后恰一条）；f 负例（重复 id / 空选区 / 图外）显示 reason 且不留 revision；
//       g 不退化（region-edit 白名单、T8 拖刷一条命令、M9 块渲染、右键路线/左键取消移动、0 pageerror）；h 截图。
// 用法: NODE_PATH=<playwright> node e2e.cjs <base-url> <out-dir>
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

function hexKey(h) {
  return h.q + "," + h.r;
}

function tickStats(body) {
  const nodes = (body && body.nodes) || [];
  return { nodeCount: nodes.length };
}

// 真 pointer 拖动：在两个格中心之间按住左键划过（PaintEnabled 时渲染器把它当涂抹）。
async function dragHexes(page, canvasBox, from, to, steps) {
  const pts = await page.evaluate(
    ([a, b]) => [window.SimosMap.screenPointOf(a.q, a.r), window.SimosMap.screenPointOf(b.q, b.r)],
    [from, to]
  );
  await page.mouse.move(canvasBox.x + pts[0].x, canvasBox.y + pts[0].y);
  await page.mouse.down();
  await page.mouse.move(canvasBox.x + pts[1].x, canvasBox.y + pts[1].y, { steps: steps || 25 });
  await page.mouse.up();
  return pts;
}

async function centerView(page, q, r, scale) {
  await page.evaluate(
    ([hex, s]) => {
      const M = window.SimosMap;
      const w = M.hexToPixel(hex.q, hex.r, M.BASE_CELL);
      M.setView({ scale: s, tx: window.innerWidth / 2 - w.x * s, ty: window.innerHeight / 2 - w.y * s });
    },
    [{ q: q, r: r }, scale || 1]
  );
  await sleep(180);
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });

  const CREATE_RUN = [
    { q: -18, r: 0 },
    { q: -17, r: 0 },
    { q: -16, r: 0 },
    { q: -15, r: 0 },
    { q: -14, r: 0 },
  ];
  const RM_RUN = [
    { q: -18, r: 0 },
    { q: -17, r: 0 },
    { q: -16, r: 0 },
  ];
  const ADD_RUN = [
    { q: -2, r: 0 },
    { q: -1, r: 0 },
    { q: 0, r: 0 },
  ];
  const ROUTE_FROM = { q: 18, r: -13 };
  const ROUTE_TO = { q: 20, r: -13 };
  const UNIT_ID = "t10-e2e-unit";

  // ── preflight：真档 ──
  const overview0 = await api("/api/map/overview");
  const ov0 = overview0.body || {};
  const regionIds0 = (ov0.regions || []).map((r) => r.id);
  values.preflight = { hexCount: ov0.hexCount, regions: regionIds0, terrainTypes: (ov0.terrainTypes || []).map((t) => t.key) };
  check(
    "pre-real-archive",
    ov0.hexCount === 19441 && regionIds0.indexOf("test_nation") >= 0 && regionIds0.indexOf("test_annex_target") >= 0,
    JSON.stringify(values.preflight)
  );

  const annexBefore = (await api("/api/map/region/test_annex_target")).body || {};
  const nationBefore = (await api("/api/map/region/test_nation")).body || {};
  const annexSetBefore = new Set((annexBefore.hexes || []).map(hexKey));
  const nationSet = new Set((nationBefore.hexes || []).map(hexKey));
  // H_rm ∈ annex（真在区域里）；H_add ∈ nation \ annex（不在 annex 里、但图上有）。
  const H_RM = (annexBefore.hexes || [])[0];
  const H_ADD = (nationBefore.hexes || []).filter((h) => !annexSetBefore.has(hexKey(h)))[0];
  values.before = {
    annexHexCount: annexBefore.hexCount,
    nationHexCount: nationBefore.hexCount,
    H_RM: H_RM,
    H_ADD: H_ADD,
  };
  check("pre-update-candidates", !!H_RM && !!H_ADD, JSON.stringify(values.before));

  const executablePath =
    process.env.PW_CHROMIUM || "/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome";
  const browser = await chromium.launch({ headless: true, executablePath });
  const page = await browser.newPage({ viewport: { width: VW, height: VH } });
  const pageErrors = [];
  page.on("pageerror", (e) => pageErrors.push(String(e)));
  const nonGet = [];
  page.on("request", (req) => {
    if (req.method() !== "GET") {
      const parsed = new URL(req.url());
      nonGet.push({ method: req.method(), path: parsed.pathname, post: req.postData() });
    }
  });

  await page.goto(BASE + "/", { waitUntil: "domcontentloaded" });
  await page.waitForFunction(() => window.SimosMap && window.SimosMap.isReady && window.SimosMap.isReady(), null, {
    timeout: 60000,
  });
  await page.waitForFunction(() => document.getElementById("map-status").textContent.indexOf("已载入") >= 0, null, {
    timeout: 60000,
  });
  await sleep(600);
  let canvasBox = await page.locator("#canvas").boundingBox();

  const m9Baseline = await page.evaluate(() => {
    const d = window.SimosMap.debug();
    return { blockCount: d.blockCount, unmergedCount: d.unmergedCount, unitCount: d.unitCount };
  });
  values.m9Baseline = m9Baseline;
  check(
    "pre-m9-blocks-baseline",
    m9Baseline.blockCount === 44 && m9Baseline.unmergedCount === 0,
    JSON.stringify(m9Baseline)
  );

  // ── a 进入区域编辑模式 ──
  await page.click('#mode-bar button[data-mode="region-edit"]');
  await sleep(300);
  const aState = await page.evaluate(() => ({
    body: document.body.getAttribute("data-mode"),
    cur: document.getElementById("mode-current").textContent,
    regionPanelVisible: document.querySelector('#right-panel [data-modes="region region-edit"]').hidden === false,
    editorVisible: document.querySelector("section.region-editor").hidden === false,
    metaEditorVisible: document.querySelector("section.region-info-editor").hidden === false,
    whitelist: {
      create: window.SimosModes.isWriteAllowed("region-edit", "map.CreateRegion"),
      update: window.SimosModes.isWriteAllowed("region-edit", "map.UpdateRegion"),
      del: window.SimosModes.isWriteAllowed("region-edit", "map.DeleteRegion"),
      terrain: window.SimosModes.isWriteAllowed("region-edit", "map.SetTerrain"),
    },
  }));
  values.a = aState;
  check(
    "a-region-edit-mode",
    aState.body === "region-edit" &&
      aState.cur === "区域编辑" &&
      aState.regionPanelVisible &&
      aState.editorVisible &&
      aState.metaEditorVisible &&
      aState.whitelist.create &&
      aState.whitelist.update &&
      aState.whitelist.del &&
      aState.whitelist.terrain === false,
    JSON.stringify(aState)
  );

  const regionEditNonGetStart = nonGet.length;

  // ── b ★ 重叠正例：画一个与已有区域重叠的新区域 ──
  await page.click("#region-edit-new");
  await sleep(200);
  const bNew = await page.evaluate(() => window.SimosMap.regionEditDebug());
  check(
    "b0-new-draft-cleared-draw-on",
    bNew.draftHexCount === 0 && bNew.drawEnabled === true && bNew.focus === null,
    JSON.stringify(bNew)
  );

  await centerView(page, -16, 0, 1);
  canvasBox = await page.locator("#canvas").boundingBox();
  const createPts = await page.evaluate(
    (run) => run.map((h) => window.SimosMap.screenPointOf(h.q, h.r)),
    CREATE_RUN
  );
  await page.mouse.move(canvasBox.x + createPts[0].x, canvasBox.y + createPts[0].y);
  await page.mouse.down();
  await page.mouse.move(canvasBox.x + createPts[2].x, canvasBox.y + createPts[2].y, { steps: 12 });
  await sleep(120);
  const bMid = await page.evaluate(() => window.SimosMap.debug());
  await page.screenshot({ path: OUT + "/screenshot-region-edit-selection.png" });
  await page.mouse.move(canvasBox.x + createPts[4].x, canvasBox.y + createPts[4].y, { steps: 12 });
  await page.mouse.up();
  await sleep(300);
  const bDraft = await page.evaluate(() => window.SimosMap.regionEditDebug());
  values.bPreview = { midPainting: bMid.painting, midBrush: bMid.brushHexCount, draftHexCount: bDraft.draftHexCount };
  check(
    "b0-draft-preview-during-drag",
    bMid.painting === true && bMid.brushHexCount >= 3 && bDraft.draftHexCount === 5,
    JSON.stringify(values.bPreview)
  );

  await page.fill("#region-create-id", "t10_overlap");
  await page.fill("#region-create-name", "T10 Overlap");
  const headBeforeCreate = await page.evaluate(() => window.SimosApp.getState().revision);
  const tlBeforeCreate = tickStats((await api("/api/timeline?branch=main")).body);
  const nonGetBeforeCreate = nonGet.length;
  const hexBeforeOverlap = (await api("/api/map/hex?q=-18&r=0")).body;
  values.hexRegionsBeforeCreate = hexBeforeOverlap && hexBeforeOverlap.regions;
  await page.click("#region-create-submit");
  await page.waitForFunction((h) => window.SimosApp.getState().revision !== h, headBeforeCreate, { timeout: 20000 }).catch(() => null);
  await sleep(800);

  const createPosts = nonGet.slice(nonGetBeforeCreate).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "map.CreateRegion";
  });
  let createPayload = null;
  if (createPosts.length === 1) {
    createPayload = parse((parse(createPosts[0].post) || {}).payloadJson);
  }
  const headAfterCreate = await page.evaluate(() => window.SimosApp.getState().revision);
  const overview1 = (await api("/api/map/overview")).body || {};
  const regionIds1 = (overview1.regions || []).map((r) => r.id);
  const hexAfterOverlap = (await api("/api/map/hex?q=-18&r=0")).body || {};
  values.create = {
    posts: createPosts.length,
    payload: createPayload,
    headBefore: headBeforeCreate,
    headAfter: headAfterCreate,
    regionIdsAfter: regionIds1,
    hexRegionsAfter: hexAfterOverlap.regions,
  };
  check(
    "b1-overlap-one-command-head-plus-one",
    createPosts.length === 1 &&
      !!createPayload &&
      createPayload.regionId === "t10_overlap" &&
      (createPayload.hexes || []).length === 5 &&
      headAfterCreate === headBeforeCreate + 1 &&
      regionIds1.indexOf("t10_overlap") >= 0,
    JSON.stringify(values.create)
  );
  // ★ 反向证据：前端没有任何"禁止重叠"的拦截 ⇒ 这 5 格**全部留在 draft** 并被服务端接受。
  check(
    "b2-overlap-allows-multi-membership",
    Array.isArray(hexAfterOverlap.regions) &&
      hexAfterOverlap.regions.indexOf("t10_overlap") >= 0 &&
      hexAfterOverlap.regions.indexOf("test_nation") >= 0 &&
      hexAfterOverlap.regions.length >= 2,
    "hex(-18,0).regions=" + JSON.stringify(hexAfterOverlap.regions)
  );
  const bDraftAfter = await page.evaluate(() => window.SimosMap.regionEditDebug());
  check(
    "b3-overlap-draft-kept",
    bDraftAfter.focus === "t10_overlap" && bDraftAfter.draftHexCount === 5,
    JSON.stringify({ focus: bDraftAfter.focus, draftHexCount: bDraftAfter.draftHexCount })
  );

  // ── c 其他区域淡色 ──
  await page.waitForFunction(
    () => {
      const d = window.SimosMap.regionEditDebug();
      return d.focus === "t10_overlap" && d.fadedRegions.length >= 2;
    },
    null,
    { timeout: 8000 }
  ).catch(() => null);
  const cFocus = await page.evaluate(() => {
    const d = window.SimosMap.regionEditDebug();
    const colors = window.SimosMap.debug().highlightColors;
    return {
      focus: d.focus,
      fadedRegions: d.fadedRegions,
      bodyFocus: d.bodyRegionFocus,
      colors: colors,
      alphas: window.SimosMap.debug().highlightAlphas,
      focusAlpha: window.SimosMap.REGION_FOCUS_ALPHA,
      fadeAlpha: window.SimosMap.REGION_FADE_ALPHA,
      fadedNation: window.SimosMap.fadeRegionColor("#d370d6"),
      fadedAnnex: window.SimosMap.fadeRegionColor("#fc6dce"),
    };
  });
  values.fade = cFocus;
  check(
    "c1-fade-focus-set",
    cFocus.focus === "t10_overlap" &&
      cFocus.bodyFocus === "t10_overlap" &&
      cFocus.fadedRegions.indexOf("test_nation") >= 0 &&
      cFocus.fadedRegions.indexOf("test_annex_target") >= 0,
    JSON.stringify({ focus: cFocus.focus, bodyFocus: cFocus.bodyFocus, fadedRegions: cFocus.fadedRegions })
  );
  check(
    "c2-fade-colors-distinct",
    cFocus.colors.indexOf(cFocus.fadedNation) >= 0 &&
      cFocus.colors.indexOf(cFocus.fadedAnnex) >= 0 &&
      cFocus.colors.length >= 2 &&
      cFocus.alphas.indexOf(cFocus.focusAlpha) >= 0 &&
      cFocus.alphas.indexOf(cFocus.fadeAlpha) >= 0,
    JSON.stringify({
      colors: cFocus.colors,
      alphas: cFocus.alphas,
      focusAlpha: cFocus.focusAlpha,
      fadeAlpha: cFocus.fadeAlpha,
      fadedNation: cFocus.fadedNation,
      fadedAnnex: cFocus.fadedAnnex,
    })
  );
  await page.screenshot({ path: OUT + "/screenshot-region-edit-focus.png" });

  // ── e 删除：二次确认（未确认零写 / 确认后恰一条）──
  const deleteEnabled = await page.evaluate(() => {
    const b = document.getElementById("region-delete");
    return !!b && !b.disabled;
  });
  if (!deleteEnabled) {
    check("e1-delete-unconfirmed-zero-write", false, "删除按钮不可用（focus 为空）");
    check("e2-delete-confirmed-one-command", false, "删除按钮不可用（focus 为空）");
  } else {
    const nonGetBeforeDelete = nonGet.length;
    await page.click("#region-delete");
    await sleep(200);
    const eArmed = await page.evaluate(() => ({
      confirmShown: document.getElementById("region-delete-confirm").hidden === false,
      debug: window.SimosMap.regionEditDebug(),
    }));
    const deletePostsArmed = nonGet.slice(nonGetBeforeDelete).filter((r) => {
      const body = parse(r.post) || {};
      return r.path === "/api/command" && body.type === "map.DeleteRegion";
    });
    const cancelVisible = await page.evaluate(() => {
      const c = document.getElementById("region-delete-confirm");
      return !!c && c.hidden === false;
    });
    if (cancelVisible) {
      await page.click("#region-delete-cancel");
      await sleep(200);
    }
    const eCancelled = await page.evaluate(() => ({
      confirmHidden: document.getElementById("region-delete-confirm").hidden === true,
      focus: window.SimosMap.regionEditDebug().focus,
    }));
    const deletePostsCancelled = nonGet.slice(nonGetBeforeDelete).filter((r) => {
      const body = parse(r.post) || {};
      return r.path === "/api/command" && body.type === "map.DeleteRegion";
    });
    values.deleteConfirm = { armed: eArmed, cancelled: eCancelled, postsArmed: deletePostsArmed.length, postsCancelled: deletePostsCancelled.length };
    check(
      "e1-delete-unconfirmed-zero-write",
      eArmed.confirmShown === true &&
        eArmed.debug.deleteArmed === true &&
        deletePostsArmed.length === 0 &&
        eCancelled.confirmHidden === true &&
        eCancelled.focus === "t10_overlap" &&
        deletePostsCancelled.length === 0,
      JSON.stringify(values.deleteConfirm)
    );

    // 变异体（去掉确认）下首次点击会**已发出**删除；等这次写落定，再判断 e2 的前置。
    await page.waitForFunction(() => !window.SimosMap.regionEditDebug().busy, null, { timeout: 8000 }).catch(() => null);
    await sleep(300);
    const deleteEnabled2 = await page.evaluate(() => {
      const b = document.getElementById("region-delete");
      return !!b && !b.disabled;
    });
    if (!deleteEnabled2) {
      check("e2-delete-confirmed-one-command", false, "删除按钮不可用（focus 已在 e1 被清空）");
    } else {
    const headBeforeDelete = await page.evaluate(() => window.SimosApp.getState().revision);
    const nonGetBeforeDeleteYes = nonGet.length;
    await page.click("#region-delete");
    await sleep(150);
    await page.click("#region-delete-yes");
    await page.waitForFunction((h) => window.SimosApp.getState().revision !== h, headBeforeDelete, { timeout: 20000 }).catch(() => null);
    await sleep(700);
    const deletePosts = nonGet.slice(nonGetBeforeDeleteYes).filter((r) => {
      const body = parse(r.post) || {};
      return r.path === "/api/command" && body.type === "map.DeleteRegion";
    });
    const headAfterDelete = await page.evaluate(() => window.SimosApp.getState().revision);
    const overview2 = (await api("/api/map/overview")).body || {};
    const regionIds2 = (overview2.regions || []).map((r) => r.id);
    const hexAfterDelete = (await api("/api/map/hex?q=-18&r=0")).body || {};
    values.deleteDone = {
      posts: deletePosts.length,
      headBefore: headBeforeDelete,
      headAfter: headAfterDelete,
      regionIdsAfter: regionIds2,
      hexRegionsAfter: hexAfterDelete.regions,
      focusAfter: (await page.evaluate(() => window.SimosMap.regionEditDebug().focus)),
    };
    check(
      "e2-delete-confirmed-one-command",
      deletePosts.length === 1 &&
        headAfterDelete === headBeforeDelete + 1 &&
        regionIds2.indexOf("t10_overlap") < 0 &&
        (hexAfterDelete.regions || []).indexOf("t10_overlap") < 0 &&
        values.deleteDone.focusAfter === null,
      JSON.stringify(values.deleteDone)
    );
    }
  }

  // ── d 改已有区域 hex（聚焦右栏区域 ⇒ 增/删 ⇒ 一条 UpdateRegion）──
  await page.waitForSelector('[data-region-id="test_annex_target"]', { timeout: 8000 });
  await page.click('[data-region-id="test_annex_target"]');
  await page.waitForFunction(
    () => window.SimosMap.regionEditDebug().focus === "test_annex_target" && window.SimosMap.regionEditDebug().draftHexCount > 100,
    null,
    { timeout: 8000 }
  ).catch(() => null);
  await sleep(300);
  const dFocus = await page.evaluate(() => window.SimosMap.regionEditDebug());
  check(
    "d0-focus-loads-hexes",
    dFocus.focus === "test_annex_target" && dFocus.draftHexCount === annexBefore.hexCount && dFocus.drawEnabled === true,
    JSON.stringify({ focus: dFocus.focus, draftHexCount: dFocus.draftHexCount, drawEnabled: dFocus.drawEnabled })
  );

  // 清空选区（focus 不变）⇒ 焦点区域强色 / 其它区域淡色的对比不被 draft 叠层遮挡，便于截图取证。
  await page.click("#region-edit-clear");
  await sleep(300);
  const dFade = await page.evaluate(() => {
    const d = window.SimosMap.regionEditDebug();
    return { focus: d.focus, fadedRegions: d.fadedRegions, alphas: window.SimosMap.debug().highlightAlphas };
  });
  values.fadeAfterClear = dFade;
  await page.screenshot({ path: OUT + "/screenshot-region-edit-focus-faded.png" });
  check(
    "d0b-fade-annex-focus",
    dFade.focus === "test_annex_target" &&
      dFade.fadedRegions.indexOf("test_nation") >= 0 &&
      dFade.alphas.indexOf(0.52) >= 0 &&
      dFade.alphas.indexOf(0.13) >= 0,
    JSON.stringify(dFade)
  );
  await page.click("#region-edit-load");
  await page.waitForFunction(
    () => window.SimosMap.regionEditDebug().draftHexCount > 100,
    null,
    { timeout: 8000 }
  ).catch(() => null);
  await sleep(300);

  await page.selectOption("#region-edit-op", "remove");
  await centerView(page, -17, 0, 1);
  canvasBox = await page.locator("#canvas").boundingBox();
  await dragHexes(page, canvasBox, RM_RUN[0], RM_RUN[2], 14);
  await sleep(300);
  const dAfterRemove = await page.evaluate(() => window.SimosMap.regionEditDebug());
  await page.selectOption("#region-edit-op", "add");
  await centerView(page, -1, 0, 1);
  canvasBox = await page.locator("#canvas").boundingBox();
  await dragHexes(page, canvasBox, ADD_RUN[0], ADD_RUN[2], 14);
  await sleep(300);
  const dAfterAdd = await page.evaluate(() => window.SimosMap.regionEditDebug());
  const draftKeys = new Set(dAfterAdd.draftHexes.map(hexKey));
  values.updateDraft = {
    before: annexBefore.hexCount,
    afterRemove: dAfterRemove.draftHexCount,
    afterAdd: dAfterAdd.draftHexCount,
    containsAdd: ADD_RUN.every((h) => draftKeys.has(hexKey(h))),
    containsRm: RM_RUN.some((h) => draftKeys.has(hexKey(h))),
  };
  check(
    "d1-update-draft-add-remove",
    dAfterRemove.draftHexCount === annexBefore.hexCount - 3 &&
      dAfterAdd.draftHexCount === annexBefore.hexCount &&
      ADD_RUN.every((h) => draftKeys.has(hexKey(h))) &&
      RM_RUN.every((h) => !draftKeys.has(hexKey(h))),
    JSON.stringify(values.updateDraft)
  );

  const headBeforeUpdate = await page.evaluate(() => window.SimosApp.getState().revision);
  const nonGetBeforeUpdate = nonGet.length;
  await page.click("#region-update-submit");
  await page.waitForFunction((h) => window.SimosApp.getState().revision !== h, headBeforeUpdate, { timeout: 20000 }).catch(() => null);
  await sleep(700);
  const updatePosts = nonGet.slice(nonGetBeforeUpdate).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "map.UpdateRegion";
  });
  let updatePayload = null;
  if (updatePosts.length === 1) {
    updatePayload = parse((parse(updatePosts[0].post) || {}).payloadJson);
  }
  const headAfterUpdate = await page.evaluate(() => window.SimosApp.getState().revision);
  const annexAfter = (await api("/api/map/region/test_annex_target")).body || {};
  const annexSetAfter = new Set((annexAfter.hexes || []).map(hexKey));
  values.update = {
    posts: updatePosts.length,
    payloadRegionId: updatePayload && updatePayload.regionId,
    payloadHexCount: updatePayload && (updatePayload.hexes || []).length,
    hexCountBefore: annexBefore.hexCount,
    hexCountAfter: annexAfter.hexCount,
    H_RM: hexKey(H_RM),
    H_ADD: hexKey(H_ADD),
    afterContainsAdd: annexSetAfter.has(hexKey(H_ADD)),
    afterContainsRm: annexSetAfter.has(hexKey(H_RM)),
    headBefore: headBeforeUpdate,
    headAfter: headAfterUpdate,
  };
  check(
    "d2-update-one-command-before-after",
    updatePosts.length === 1 &&
      updatePayload &&
      updatePayload.regionId === "test_annex_target" &&
      (updatePayload.hexes || []).length === annexBefore.hexCount &&
      annexAfter.hexCount === annexBefore.hexCount &&
      annexSetAfter.has(hexKey(H_ADD)) &&
      !annexSetAfter.has(hexKey(H_RM)) &&
      headAfterUpdate === headBeforeUpdate + 1,
    JSON.stringify(values.update)
  );

  // ── f 负例：重复 id / 空选区 / 图外 ⇒ 显示 reason、不留 revision ──
  const headBeforeNeg = await page.evaluate(() => window.SimosApp.getState().revision);
  const tlBeforeNeg = tickStats((await api("/api/timeline?branch=main")).body);

  await page.click("#region-edit-new");
  await sleep(200);
  await page.evaluate(() => window.SimosMap.regionPaintForTest([{ q: -18, r: 0 }], "add"));
  await sleep(150);
  await page.fill("#region-create-id", "test_nation");
  await page.fill("#region-create-name", "dup");
  const nonGetBeforeDup = nonGet.length;
  await page.click("#region-create-submit");
  await sleep(700);
  const dupStatus = await page.textContent("#region-edit-status");
  const dupPosts = nonGet.slice(nonGetBeforeDup).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "map.CreateRegion";
  });
  values.negDuplicate = { status: dupStatus, posts: dupPosts.length };

  await page.click("#region-edit-new");
  await sleep(200);
  const emptyDraft = await page.evaluate(() => window.SimosMap.regionEditDebug().draftHexCount);
  await page.fill("#region-create-id", "t10_empty");
  await page.fill("#region-create-name", "empty");
  const nonGetBeforeEmpty = nonGet.length;
  await page.click("#region-create-submit");
  await sleep(700);
  const emptyStatus = await page.textContent("#region-edit-status");
  const emptyPosts = nonGet.slice(nonGetBeforeEmpty).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "map.CreateRegion";
  });
  values.negEmpty = { status: emptyStatus, draft: emptyDraft, posts: emptyPosts.length };

  const offmapRes = await page.evaluate(async () => {
    const r = await window.SimosApp.writeCommand("map.CreateRegion", {
      regionId: "t10_off",
      name: "off",
      hexes: [{ q: 9999, r: 9999 }],
    });
    return { ok: r.ok, kind: r.kind, message: r.message };
  });
  values.negOffmap = offmapRes;

  const headAfterNeg = await page.evaluate(() => window.SimosApp.getState().revision);
  const tlAfterNeg = tickStats((await api("/api/timeline?branch=main")).body);
  check(
    "f1-duplicate-id-rejected-reason",
    dupPosts.length === 1 && dupStatus.indexOf("区域已存在") >= 0,
    JSON.stringify(values.negDuplicate)
  );
  check(
    "f2-empty-selection-rejected-reason",
    emptyDraft === 0 && emptyPosts.length === 1 && emptyStatus.indexOf("hexes 不得为空") >= 0,
    JSON.stringify(values.negEmpty)
  );
  check(
    "f3-offmap-rejected-reason",
    offmapRes.ok === false && offmapRes.kind === "rejected" && (offmapRes.message || "").indexOf("9999_9999") >= 0,
    JSON.stringify(offmapRes)
  );
  check(
    "f4-negatives-no-revision",
    headAfterNeg === headBeforeNeg && tlAfterNeg.nodeCount === tlBeforeNeg.nodeCount,
    JSON.stringify({ headBeforeNeg, headAfterNeg, tlBeforeNeg, tlAfterNeg })
  );

  // ── g1 region-edit 白名单：本阶段所有非 GET 命令只能落在三条区域命令 ──
  const regionEditNonGet = nonGet.slice(regionEditNonGetStart);
  const regionTypes = Array.from(
    new Set(
      regionEditNonGet.map((r) => (parse(r.post) || {}).type).filter((t) => t)
    )
  );
  const allowedTypes = ["map.CreateRegion", "map.UpdateRegion", "map.DeleteRegion"];
  values.regionEditNonGet = { count: regionEditNonGet.length, types: regionTypes };
  check(
    "g1-region-edit-whitelist",
    regionEditNonGet.every((r) => r.path === "/api/command") &&
      regionTypes.every((t) => allowedTypes.indexOf(t) >= 0),
    JSON.stringify(values.regionEditNonGet)
  );

  // ── g2 T8 拖刷不退化：地图编辑模式拖 5 格仍**一条** SetTerrain ──
  await page.click('#mode-bar button[data-mode="map-edit"]');
  await page.waitForSelector("#terrain-palette button[data-terrain]", { timeout: 20000 });
  await sleep(300);
  const palKeys = await page.evaluate(() => window.SimosMap.mapEditDebug().paletteKeys);
  const terrainTarget = palKeys.indexOf("ocean") >= 0 ? "ocean" : palKeys[0];
  await page.click('#terrain-palette button[data-terrain="' + terrainTarget + '"]');
  await sleep(150);
  await centerView(page, -16, 0, 1);
  canvasBox = await page.locator("#canvas").boundingBox();
  const headBeforeBrush = await page.evaluate(() => window.SimosApp.getState().revision);
  const nonGetBeforeBrush = nonGet.length;
  await dragHexes(page, canvasBox, CREATE_RUN[0], CREATE_RUN[4], 25);
  await page.waitForFunction((h) => window.SimosApp.getState().revision !== h, headBeforeBrush, { timeout: 20000 }).catch(() => null);
  await sleep(500);
  const brushPosts = nonGet.slice(nonGetBeforeBrush).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "map.SetTerrain";
  });
  const brushPayload = brushPosts.length === 1 ? parse((parse(brushPosts[0].post) || {}).payloadJson) : null;
  values.mapEditBrush = {
    posts: brushPosts.length,
    hexCount: brushPayload && (brushPayload.hexes || []).length,
    terrain: brushPayload && brushPayload.terrain,
  };
  check(
    "g2-map-edit-brush-not-regressed",
    brushPosts.length === 1 && brushPayload && (brushPayload.hexes || []).length === 5,
    JSON.stringify(values.mapEditBrush)
  );

  // ── g3 M9 块渲染不退化：44 块 / 无未合并余量 / 点选仍准 ──
  await page.click('#mode-bar button[data-mode="view"]');
  await sleep(300);
  const m9 = await page.evaluate(() => {
    const d = window.SimosMap.debug();
    return { blockCount: d.blockCount, unmergedCount: d.unmergedCount };
  });
  await centerView(page, -18, 0, 1);
  canvasBox = await page.locator("#canvas").boundingBox();
  const pickPt = await page.evaluate(() => window.SimosMap.screenPointOf(-18, 0));
  await page.mouse.click(canvasBox.x + pickPt.x, canvasBox.y + pickPt.y);
  await sleep(400);
  const sel = await page.evaluate(() => window.SimosApp.getState().selection);
  values.m9 = { blockCount: m9.blockCount, unmergedCount: m9.unmergedCount, selection: sel };
  check(
    "g3-blocks-not-regressed",
    m9.blockCount > 0 && m9.unmergedCount === 0 && sel && sel.kind === "hex" && sel.q === -18 && sel.r === 0,
    JSON.stringify(values.m9)
  );

  // ── g4 M7 交互不退化：右键寻路 + 左键取消移动 ──
  await page.click('#mode-bar button[data-mode="unit"]');
  await sleep(200);
  const createRes = await page.evaluate(async ([from, id]) => {
    const r = await window.SimosApp.writeCommand("unit.CreateUnit", {
      id: id,
      name: "T10 E2E",
      position: { q: from.q, r: from.r },
      member: 100,
      equipment: {},
      speed: 2,
      mobilityPerMille: 1000,
    });
    return { ok: r.ok, kind: r.kind || null, message: r.message || null };
  }, [ROUTE_FROM, UNIT_ID]);
  await sleep(500);
  await centerView(page, ROUTE_FROM.q, ROUTE_FROM.r, 1);
  canvasBox = await page.locator("#canvas").boundingBox();
  const unitPt = await page.evaluate((h) => window.SimosMap.screenPointOf(h.q, h.r), ROUTE_FROM);
  const toPt = await page.evaluate((h) => window.SimosMap.screenPointOf(h.q, h.r), ROUTE_TO);
  const nonGetBeforeRoute = nonGet.length;
  await page.mouse.click(canvasBox.x + unitPt.x, canvasBox.y + unitPt.y);
  await sleep(200);
  await page.mouse.click(canvasBox.x + toPt.x, canvasBox.y + toPt.y, { button: "right" });
  await sleep(900);
  const routePosts = nonGet.slice(nonGetBeforeRoute).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "unit.PlanRoute";
  });
  const routeCount = await page.evaluate(() => window.SimosMap.debug().routeCount);
  const nonGetBeforeCancel = nonGet.length;
  await page.mouse.click(canvasBox.x + unitPt.x, canvasBox.y + unitPt.y);
  await sleep(800);
  const cancelPosts = nonGet.slice(nonGetBeforeCancel).filter((r) => {
    const body = parse(r.post) || {};
    return r.path === "/api/command" && body.type === "unit.CancelRoute";
  });
  const routeCountAfterCancel = await page.evaluate(() => window.SimosMap.debug().routeCount);
  values.m7 = {
    createRes: createRes,
    routePosts: routePosts.length,
    routeCount: routeCount,
    cancelPosts: cancelPosts.length,
    routeCountAfterCancel: routeCountAfterCancel,
  };
  check(
    "g4-route-not-regressed",
    createRes.ok === true &&
      routePosts.length === 1 &&
      routeCount >= 1 &&
      cancelPosts.length === 1 &&
      routeCountAfterCancel === 0,
    JSON.stringify(values.m7)
  );

  check("g5-no-pageerror", pageErrors.length === 0, JSON.stringify(pageErrors));
  values.pageErrors = pageErrors;
  values.nonGet = nonGet.map((r) => ({ method: r.method, path: r.path, type: (parse(r.post) || {}).type || null }));
  console.log("NONGET " + JSON.stringify(values.nonGet));
  console.log("PAGEERRORS " + JSON.stringify(pageErrors));

  writeJson("result.json", values);
  await browser.close();
  console.log("E2E RESULT: " + (failures.length === 0 ? "ALL PASS" : failures.length + " FAIL " + failures.join(",")));
  process.exit(failures.length === 0 ? 0 : 1);
})().catch((e) => {
  console.log("E2E CRASH: " + e.stack);
  process.exit(2);
});
