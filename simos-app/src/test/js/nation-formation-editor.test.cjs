// nation-formation-editor.test.cjs —— 国家/完整编制网页编辑器的真实 DOM/异步行为。
//
// 覆盖：
//  ① 载入现有对象导出的 JSON 可直接提交（不塞 view-only 字段）；
//  ② 保存走宿主唯一入口 writeEditorTool（冻结 target / dryRun=false / 同次 completedTarget 读回）；
//  ③ 迟到响应/切换节点不把旧结果塞进新节点；
//  ④ 失败不自动载入/不伪造成功；
//  ⑤ 编制树导出保留 omitted 字段、不带 directPersonnel/subtreePersonnel 等读回字段；
//  ⑥ 编制 DOM 只用 textContent（不 innerHTML 注入）；
//  ⑦ 大图浮层复用同一张 PNG，fit/原图/缩放/关闭 + 原生 dialog Esc。
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

const WEBUI_DIR = path.resolve(__dirname, "../../../main/resources/webui");
const GOV_DIR = path.resolve(
  __dirname,
  "../../../../simos-gov/src/main/resources/webui/modules/gov"
);
const ARMY_DIR = path.resolve(
  __dirname,
  "../../../../simos-army/src/main/resources/webui/modules/army"
);

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

async function flush(times) {
  for (let i = 0; i < (times || 4); i++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
}

function makeElement(id, tag) {
  const listeners = {};
  const attributes = {};
  const element = {
    id: id || "",
    tagName: (tag || "div").toUpperCase(),
    hidden: false,
    open: false,
    value: "",
    textContent: "",
    className: "",
    src: "",
    naturalWidth: 1692,
    naturalHeight: 490,
    style: {},
    children: [],
    classList: { add() {}, remove() {}, contains() { return false; }, toggle() {} },
    addEventListener(type, fn) {
      (listeners[type] || (listeners[type] = [])).push(fn);
    },
    dispatch(type) {
      (listeners[type] || []).forEach((fn) => fn({ type, preventDefault() {} }));
    },
    appendChild(child) {
      this.children.push(child);
      return child;
    },
    remove() {},
    setAttribute(name, value) {
      attributes[name] = String(value);
      if (name === "src") this.src = String(value);
    },
    removeAttribute(name) {
      delete attributes[name];
      if (name === "src") this.src = "";
    },
    getAttribute(name) {
      return attributes[name];
    },
    showModal() {
      this.open = true;
    },
    close() {
      this.open = false;
    },
  };
  Object.defineProperty(element, "src", {
    get() {
      return attributes.src || "";
    },
    set(value) {
      attributes.src = String(value);
    },
  });
  let text = "";
  Object.defineProperty(element, "textContent", {
    get() {
      return text;
    },
    set(value) {
      text = String(value);
      if (text === "") element.children.length = 0;
    },
  });
  return element;
}

function makeElementMap(ids) {
  const elements = {};
  ids.forEach((id) => {
    elements[id] = makeElement(id, id.indexOf("viewer") >= 0 ? "dialog" : "div");
  });
  return elements;
}

function makeHarness(kind, options) {
  const opts = options || {};
  const nation = kind === "nation";
  const ids = nation
    ? [
        "nation-editor",
        "nation-editor-select",
        "nation-editor-load",
        "nation-editor-new",
        "nation-editor-hex",
        "nation-editor-refresh",
        "nation-editor-save",
        "nation-editor-json",
        "nation-editor-status",
        "nation-editor-image",
        "nation-editor-result",
      ]
    : [
        "formation-editor",
        "formation-editor-select",
        "formation-editor-load",
        "formation-editor-new",
        "formation-editor-position",
        "formation-editor-add-battalion",
        "formation-editor-refresh",
        "formation-editor-save",
        "formation-editor-json",
        "formation-editor-status",
        "formation-editor-image",
        "formation-editor-result",
        "formation-editor-zoom-in",
        "formation-editor-zoom-out",
        "formation-editor-zoom-reset",
        "formation-editor-view-large",
        "formation-viewer",
        "formation-viewer-image",
        "formation-viewer-close",
        "formation-viewer-fit",
        "formation-viewer-original",
        "formation-viewer-zoom-in",
        "formation-viewer-zoom-out",
        "formation-viewer-zoom-label",
      ];
  const elements = makeElementMap(ids);
  const state = Object.assign(
    {
      mode: "map-edit",
      branch: "main",
      revision: 1,
      tick: 7,
      writeEpoch: 0,
      selection: { kind: "hex", q: 2, r: 0 },
    },
    opts.state || {}
  );
  const calls = { get: [], write: [] };
  let listBody = opts.listBody || {
    result: nation
      ? { nations: [{ nationId: "demo-nation", name: "演示国", hexCount: 1 }] }
      : { armies: [{ armyId: "demo-army", name: "演示师", nodeCount: 1 }] },
  };
  let detailBody = opts.detailBody || {};
  let writeHandler =
    opts.writeEditorTool ||
    (() => Promise.resolve({ ok: true, cursorStillCurrent: true, completedTarget: null, body: {} }));
  let fetchHandler = opts.fetch || null;

  const documentStub = {
    readyState: "complete",
    getElementById(id) {
      return elements[id] || null;
    },
    createElement(tag) {
      return makeElement("", tag);
    },
    addEventListener() {},
    querySelector() {
      return null;
    },
    querySelectorAll() {
      return [];
    },
  };
  const win = {
    document: documentStub,
    console,
    window: null,
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
  };
  win.window = win;
  win.SimosApp = {
    getState() {
      return state;
    },
    target() {
      return { branch: state.branch, revision: state.revision, writeEpoch: state.writeEpoch };
    },
    writeEditorTool(kindName, payload, frozen) {
      calls.write.push({ kind: kindName, payload, frozen });
      return writeHandler(kindName, payload, frozen, state);
    },
  };
  win.fetch = (pathname) => {
    calls.get.push(pathname);
    if (fetchHandler) return fetchHandler(pathname, state);
    const isDetail =
      (nation && pathname.indexOf("nationId=") >= 0) ||
      (!nation && pathname.indexOf("armyId=") >= 0);
    return Promise.resolve({
      ok: true,
      status: 200,
      statusText: "OK",
      text: () => Promise.resolve(JSON.stringify(isDetail ? detailBody : listBody)),
    });
  };
  vm.createContext(win);
  const sourcePath = path.join(nation ? GOV_DIR : ARMY_DIR, nation ? "nation-editor.js" : "formation-editor.js");
  vm.runInContext(fs.readFileSync(sourcePath, "utf8"), win, { filename: sourcePath });
  return {
    elements,
    state,
    calls,
    setListBody(body) {
      listBody = body;
    },
    setDetailBody(body) {
      detailBody = body;
    },
    setWrite(fn) {
      writeHandler = fn;
    },
    setFetch(fn) {
      fetchHandler = fn;
    },
  };
}

function nationDetail() {
  return {
    result: {
      nations: [
        {
          nationId: "demo-nation",
          name: "演示国",
          color: "#3366CC",
          hexes: [{ q: 0, r: 0 }, { q: 1, r: 0 }],
          hexCount: 2,
          regionIds: ["nation-home-demo-nation"],
          govUnitIds: ["demo-gov"],
          homeRegionId: "nation-home-demo-nation",
          adminBudgetPerTick: 0,
          colorConflict: false,
        },
      ],
      render: { assetId: "a".repeat(64), summary: "国家图" },
    },
    images: [{ mediaType: "image/png", base64: "UE5H" }],
  };
}

function formationDetail() {
  return {
    result: {
      armies: [
        {
          armyId: "demo-army",
          name: "演示师",
          masterGov: "demo-gov",
          rootUnitId: "div",
          nodeCount: 3,
          directPersonnel: 60,
          subtreePersonnel: 560,
          nodes: [
            {
              id: "div",
              name: "第1师",
              role: "DIVISION",
              masterGov: "demo-gov",
              parentId: null,
              depth: 1,
              attached: true,
              position: { q: 2, r: 0 },
              directPersonnel: 60,
              subtreePersonnel: 560,
              equipment: [{ type: "步枪", amount: 20 }],
              speed: 1,
              mobilityPerMille: 1000,
              childIds: ["bde"],
            },
            {
              id: "bde",
              name: "第1旅",
              role: "BRIGADE",
              masterGov: "demo-gov",
              parentId: "div",
              depth: 2,
              attached: true,
              position: { q: 2, r: 0 },
              directPersonnel: 0,
              subtreePersonnel: 500,
              equipment: [],
              speed: 1,
              mobilityPerMille: 1000,
              childIds: ["bn"],
            },
            {
              id: "bn",
              name: "第1营",
              role: "BATTALION",
              masterGov: "demo-gov",
              parentId: "bde",
              depth: 3,
              attached: true,
              position: { q: 2, r: 0 },
              directPersonnel: 500,
              subtreePersonnel: 500,
              equipment: [{ type: "步枪", amount: 450 }],
              speed: 2,
              mobilityPerMille: 900,
              childIds: [],
            },
          ],
        },
      ],
      render: { assetId: "b".repeat(64), summary: "3 节点" },
    },
    images: [{ mediaType: "image/png", base64: "UE5H" }],
  };
}

async function readyNation(options) {
  const harness = makeHarness("nation", options);
  harness.setDetailBody((options && options.detailBody) || nationDetail());
  await flush(2);
  return harness;
}

async function readyFormation(options) {
  const harness = makeHarness("formation", options);
  harness.setDetailBody((options && options.detailBody) || formationDetail());
  await flush(2);
  return harness;
}

async function loadNation(harness) {
  harness.elements["nation-editor-select"].value = "demo-nation";
  harness.elements["nation-editor-load"].dispatch("click");
  await flush(6);
}

async function loadFormation(harness) {
  harness.elements["formation-editor-select"].value = "demo-army";
  harness.elements["formation-editor-load"].dispatch("click");
  await flush(6);
}

// ────────────────────────── ① 载入导出的 JSON 可直接提交 ──────────────────────────

test("nation-load-exports-a-directly-submittable-update-json", async () => {
  const harness = await readyNation();
  await loadNation(harness);
  const payload = JSON.parse(harness.elements["nation-editor-json"].value);
  assert.deepEqual(Object.keys(payload).sort(), [
    "color",
    "hexes",
    "mode",
    "name",
    "nationId",
    "overlapPolicy",
    "reason",
    "territoryMode",
  ]);
  assert.equal(payload.mode, "update");
  assert.equal(payload.nationId, "demo-nation");
  assert.deepEqual(payload.hexes, [{ q: 0, r: 0 }, { q: 1, r: 0 }]);
  for (const viewOnly of ["submission", "render", "govUnitIds", "hexCount", "regionIds", "colorConflict"]) {
    assert.equal(Object.prototype.hasOwnProperty.call(payload, viewOnly), false, viewOnly + " 不得进入编辑 JSON");
  }
  assert.equal(harness.elements["nation-editor-image"].src, "data:image/png;base64,UE5H");
});

test("formation-load-exports-tree-without-view-only-fields", async () => {
  const harness = await readyFormation();
  await loadFormation(harness);
  const payload = JSON.parse(harness.elements["formation-editor-json"].value);
  assert.equal(payload.mode, "update");
  assert.equal(payload.armyId, "demo-army");
  assert.equal(payload.root.id, "div");
  assert.deepEqual(Object.keys(payload.root).sort(), [
    "attached",
    "children",
    "equipment",
    "id",
    "mobilityPerMille",
    "name",
    "personnel",
    "position",
    "role",
    "speed",
  ]);
  assert.equal(payload.root.children[0].children[0].id, "bn");
  for (const viewOnly of ["directPersonnel", "subtreePersonnel", "depth", "parentId", "childIds", "masterGov"]) {
    assert.equal(
      Object.prototype.hasOwnProperty.call(payload.root, viewOnly),
      false,
      "root 不得含 " + viewOnly
    );
  }
});

// ────────────────────────── ② 保存走共享入口 + 同次目标读回 ──────────────────────────

test("nation-save-uses-shared-editor-write-and-reads-back-completed-target", async () => {
  const harness = await readyNation();
  await loadNation(harness);
  let resolveWrite;
  const pending = new Promise((resolve) => {
    resolveWrite = resolve;
  });
  harness.setWrite(() => pending);
  harness.elements["nation-editor-save"].dispatch("click");
  await flush(2);
  assert.equal(harness.calls.write.length, 1);
  const call = harness.calls.write[0];
  assert.equal(call.kind, "nation");
  assert.equal(call.frozen.branch, "main");
  assert.equal(call.frozen.revision, 1);
  assert.equal(call.payload.mode, "update");

  harness.state.revision = 2;
  harness.state.writeEpoch = 1;
  resolveWrite({
    ok: true,
    cursorStillCurrent: true,
    completedTarget: { branch: "main", revision: 2, writeEpoch: 1 },
    body: {
      result: {
        nationId: "demo-nation",
        submission: { ref: { branch: "main", revision: 2 } },
      },
    },
  });
  await flush(8);
  const nationStatus = harness.elements["nation-editor-status"].textContent;
  assert.ok(nationStatus.indexOf("已保存") >= 0 || nationStatus.indexOf("已载入") >= 0, nationStatus);
  const readbacks = harness.calls.get.filter((path) => path.indexOf("nationId=demo-nation") >= 0);
  assert.ok(readbacks.length >= 1, "保存成功后必须自动读回");
  assert.ok(
    readbacks[readbacks.length - 1].indexOf("revision=2") >= 0,
    "读回必须使用同次提交后的新 revision：" + readbacks[readbacks.length - 1]
  );
  assert.equal(JSON.parse(harness.elements["nation-editor-json"].value).mode, "update");
});

test("formation-save-uses-shared-editor-write-and-reads-back-completed-target", async () => {
  const harness = await readyFormation();
  await loadFormation(harness);
  harness.setWrite(() =>
    Promise.resolve({
      ok: true,
      cursorStillCurrent: true,
      completedTarget: { branch: "main", revision: 2, writeEpoch: 1 },
      body: {
        result: {
          armyId: "demo-army",
          nodeCount: 3,
          directPersonnel: 60,
          submission: { ref: { branch: "main", revision: 2 } },
        },
      },
    })
  );
  harness.state.revision = 2;
  harness.state.writeEpoch = 1;
  harness.elements["formation-editor-save"].dispatch("click");
  await flush(8);
  assert.equal(harness.calls.write.length, 1);
  const formationStatus = harness.elements["formation-editor-status"].textContent;
  assert.ok(formationStatus.indexOf("已保存") >= 0 || formationStatus.indexOf("已载入") >= 0, formationStatus);
  const readbacks = harness.calls.get.filter((path) => path.indexOf("armyId=demo-army") >= 0);
  assert.ok(readbacks.length >= 1);
  assert.ok(readbacks[readbacks.length - 1].indexOf("revision=2") >= 0);
});

// ────────────────────────── ③ 迟到响应/节点切换 ──────────────────────────

test("late-nation-save-response-does-not-write-into-the-new-node", async () => {
  const harness = await readyNation();
  await loadNation(harness);
  const before = harness.elements["nation-editor-json"].value;
  let resolveWrite;
  const pending = new Promise((resolve) => {
    resolveWrite = resolve;
  });
  harness.setWrite(() => pending);
  harness.elements["nation-editor-save"].dispatch("click");
  await flush(2);
  harness.state.branch = "other";
  harness.state.revision = 9;
  resolveWrite({
    ok: true,
    cursorStillCurrent: false,
    completedTarget: null,
    body: { result: { nationId: "demo-nation", submission: { ref: { branch: "main", revision: 2 } } } },
  });
  await flush(6);
  assert.equal(harness.elements["nation-editor-json"].value, before, "迟到响应不得改写新节点 JSON");
  assert.equal(
    harness.calls.get.filter((path) => path.indexOf("revision=2") >= 0).length,
    0,
    "迟到响应不得用旧 revision 读回"
  );
});

test("late-formation-save-response-does-not-write-into-the-new-node", async () => {
  const harness = await readyFormation();
  await loadFormation(harness);
  const before = harness.elements["formation-editor-json"].value;
  let resolveWrite;
  const pending = new Promise((resolve) => {
    resolveWrite = resolve;
  });
  harness.setWrite(() => pending);
  harness.elements["formation-editor-save"].dispatch("click");
  await flush(2);
  harness.state.branch = "other";
  harness.state.revision = 9;
  resolveWrite({
    ok: true,
    cursorStillCurrent: false,
    completedTarget: null,
    body: { result: { armyId: "demo-army", submission: { ref: { branch: "main", revision: 2 } } } },
  });
  await flush(6);
  assert.equal(harness.elements["formation-editor-json"].value, before);
  assert.equal(harness.calls.get.filter((path) => path.indexOf("revision=2") >= 0).length, 0);
});

test("editor-failure-does-not-auto-load-or-claim-success", async () => {
  for (const kind of ["nation", "formation"]) {
    const harness = kind === "nation" ? await readyNation() : await readyFormation();
    if (kind === "nation") await loadNation(harness);
    else await loadFormation(harness);
    harness.setWrite(() =>
      Promise.resolve({
        ok: false,
        kind: "rejected",
        message: "服务端拒绝",
        cursorStillCurrent: true,
        completedTarget: { branch: "main", revision: 1, writeEpoch: 0 },
        error: { status: 422, body: { message: "服务端拒绝" } },
      })
    );
    const detailReadsBefore = harness.calls.get.filter(
      (path) => path.indexOf(kind === "nation" ? "nationId=" : "armyId=") >= 0
    ).length;
    harness.elements[kind + "-editor-save"].dispatch("click");
    await flush(6);
    const status = harness.elements[kind + "-editor-status"].textContent;
    assert.ok(status.indexOf("未保存") >= 0 || status.indexOf("冲突") >= 0, status);
    assert.equal(status.indexOf("已保存"), -1);
    const detailReadsAfter = harness.calls.get.filter(
      (path) => path.indexOf(kind === "nation" ? "nationId=" : "armyId=") >= 0
    ).length;
    assert.equal(detailReadsAfter, detailReadsBefore, "失败后不得自动读回");
  }
});

// ────────────────────────── ④ 编制 DOM 安全 + 大图 ──────────────────────────

test("formation-editor-source-uses-textContent-and-never-innerHTML", () => {
  const source = fs.readFileSync(path.join(ARMY_DIR, "formation-editor.js"), "utf8");
  assert.equal(source.indexOf(".innerHTML"), -1, "不得用 innerHTML 注入");
  assert.ok(source.indexOf("textContent") >= 0);
  assert.ok(source.indexOf("createElement") >= 0);
});

test("formation-large-view-reuses-same-png-and-supports-fit-original-zoom-close", async () => {
  const harness = await readyFormation();
  await loadFormation(harness);
  const editorImage = harness.elements["formation-editor-image"];
  assert.equal(editorImage.src, "data:image/png;base64,UE5H");
  const viewer = harness.elements["formation-viewer"];
  const viewerImage = harness.elements["formation-viewer-image"];

  harness.elements["formation-editor-view-large"].dispatch("click");
  assert.equal(viewer.open, true, "大图 dialog 必须打开");
  assert.equal(viewerImage.src, editorImage.src, "大图必须复用同一张 PNG，不重读节点");
  assert.equal(harness.elements["formation-viewer-zoom-label"].textContent, "适应窗口");
  assert.equal(viewerImage.style.width, "100%");

  harness.elements["formation-viewer-original"].dispatch("click");
  assert.equal(viewerImage.style.width, "1692px", "原图尺寸取 naturalWidth");
  assert.equal(harness.elements["formation-viewer-zoom-label"].textContent, "100%");

  harness.elements["formation-viewer-zoom-in"].dispatch("click");
  assert.equal(viewerImage.style.width, "2200px", "±25% 步进经一位小数取整后的实际宽度");
  assert.equal(harness.elements["formation-viewer-zoom-label"].textContent, "130%");

  harness.elements["formation-viewer-fit"].dispatch("click");
  assert.equal(viewerImage.style.width, "100%");
  assert.equal(harness.elements["formation-viewer-zoom-label"].textContent, "适应窗口");

  harness.elements["formation-viewer-close"].dispatch("click");
  assert.equal(viewer.open, false, "关闭按钮关闭 dialog");
});

test("both-host-pages-wire-large-view-and-native-dialog-escape", () => {
  for (const page of ["index.html", "map.html"]) {
    const html = readWebui(page);
    assert.ok(html.indexOf('id="formation-editor-view-large"') >= 0, page + " 大图按钮");
    assert.ok(html.indexOf('<dialog id="formation-viewer"') >= 0, page + " 必须是原生 dialog（Esc 由浏览器关闭）");
    assert.ok(html.indexOf('id="formation-viewer-close"') >= 0, page + " 关闭按钮");
    assert.ok(html.indexOf('id="formation-viewer-fit"') >= 0, page + " 适应窗口");
    assert.ok(html.indexOf('id="formation-viewer-original"') >= 0, page + " 原图尺寸");
    assert.ok(html.indexOf('id="formation-viewer-zoom-in"') >= 0, page + " 放大");
    assert.ok(html.indexOf("/webui/modules/army/formation-editor.js") >= 0, page + " 模块脚本");
    assert.ok(html.indexOf("/webui/modules/gov/nation-editor.js") >= 0, page + " 国家模块脚本");
  }
});

// ────────────────────────── ⑤ 宿主 writeEditorTool：冻结/强制/迟到/失败 ──────────────────────────

function createAppHarness(options) {
  const opts = options || {};
  const documentStub = {
    getElementById() {
      return null;
    },
    querySelector() {
      return null;
    },
    querySelectorAll() {
      return [];
    },
    createElement() {
      return { style: {}, appendChild() {}, addEventListener() {}, setAttribute() {} };
    },
    addEventListener() {},
    body: { classList: { add() {}, remove() {} }, setAttribute() {} },
  };
  const win = loadWebui("app.js", { document: documentStub });
  const stateCalls = [];
  const editorCalls = [];
  let stateBody = opts.state || {
    branches: ["main"],
    heads: { main: 1 },
    meta: { branch: "main", revision: 1, timestamp: { tick: 7 } },
    writeEpoch: 0,
  };
  win.SimosModes = {
    isWriteAllowed() {
      return true;
    },
    modeLabel(mode) {
      return mode;
    },
  };
  win.SimosApi = {
    state() {
      stateCalls.push({});
      return Promise.resolve(stateBody);
    },
    invalidateState() {},
    postEditor(kind, body) {
      editorCalls.push({ kind, body });
      return opts.postEditor(kind, body);
    },
  };
  const app = win.SimosApp;
  app.setServerState(stateBody.branches, stateBody.heads, stateBody.meta, stateBody.writeEpoch);
  return {
    app,
    stateCalls,
    editorCalls,
    setStateBody(next) {
      stateBody = next;
    },
  };
}

test("host-writeEditorTool-forces-frozen-branch-revision-and-dryRun-false", async () => {
  const harness = createAppHarness({
    postEditor: () =>
      Promise.resolve({
        ok: true,
        result: { submission: { ref: { branch: "main", revision: 2 } }, nationId: "n" },
      }),
  });
  harness.setStateBody({
    branches: ["main"],
    heads: { main: 2 },
    meta: { branch: "main", revision: 2, timestamp: { tick: 7 } },
    writeEpoch: 1,
  });
  harness.app.setMode("map-edit");
  const result = await harness.app.writeEditorTool(
    "nation",
    { mode: "create", branch: "evil", expectedRevision: 999, dryRun: true },
    { branch: "main", revision: 1, writeEpoch: 0 }
  );
  assert.equal(harness.editorCalls.length, 1);
  const body = harness.editorCalls[0].body;
  assert.equal(body.branch, "main", "JSON 不得改目标 branch");
  assert.equal(body.expectedRevision, 1, "JSON 不得改目标 revision");
  assert.equal(body.dryRun, false, "网页保存必须真写");
  assert.equal(result.ok, true);
  assert.equal(result.cursorStillCurrent, true);
  assert.deepEqual(result.completedTarget, { branch: "main", revision: 2, writeEpoch: 1 });
  assert.equal(harness.app.getState().revision, 2);
});

test("host-writeEditorTool-late-response-does-not-move-cursor", async () => {
  const pending = deferred();
  const harness = createAppHarness({ postEditor: () => pending.promise });
  harness.app.setMode("map-edit");
  const call = harness.app.writeEditorTool(
    "formation",
    { mode: "update" },
    { branch: "main", revision: 1, writeEpoch: 0 }
  );
  await flush(1);
  harness.app.setBranch("other");
  harness.app.setRevision(9);
  pending.resolve({ ok: true, result: { submission: { ref: { branch: "main", revision: 2 } } } });
  const result = await call;
  assert.equal(result.ok, true);
  assert.equal(result.cursorStillCurrent, false);
  assert.equal(result.completedTarget, null);
  assert.equal(harness.app.getState().branch, "other");
  assert.equal(harness.app.getState().revision, 9);
});

test("host-writeEditorTool-rejects-mode-and-bad-target-before-any-request", async () => {
  const harness = createAppHarness({ postEditor: () => Promise.reject(new Error("must not call")) });
  harness.app.setMode("view");
  const denied = await harness.app.writeEditorTool("nation", {}, { branch: "main", revision: 1 });
  assert.equal(denied.ok, false);
  assert.equal(denied.kind, "mode-denied");
  assert.equal(harness.editorCalls.length, 0);

  harness.app.setMode("map-edit");
  const badTarget = await harness.app.writeEditorTool(
    "formation",
    {},
    { branch: "main", revision: null, writeEpoch: 0 }
  );
  assert.equal(badTarget.ok, false);
  assert.equal(badTarget.kind, "bad-target");
  assert.equal(harness.editorCalls.length, 0);
});

test("host-writeEditorTool-422-keeps-cursor-and-does-not-retry", async () => {
  const harness = createAppHarness({
    postEditor: () => Promise.reject({ status: 422, body: { message: "拒绝理由" } }),
  });
  harness.app.setMode("map-edit");
  const result = await harness.app.writeEditorTool(
    "nation",
    { mode: "create" },
    { branch: "main", revision: 1, writeEpoch: 0 }
  );
  assert.equal(result.ok, false);
  assert.equal(result.kind, "rejected");
  assert.equal(result.message, "拒绝理由");
  assert.equal(result.cursorStillCurrent, true);
  assert.equal(harness.app.getState().revision, 1);
  assert.equal(harness.editorCalls.length, 1);
});

test("host-writeEditorTool-409-advances-to-real-conflict-head-without-replay", async () => {
  const harness = createAppHarness({
    postEditor: () =>
      Promise.reject({
        status: 409,
        body: { result: { submission: { current: { branch: "main", revision: 5 } } } },
      }),
  });
  harness.setStateBody({
    branches: ["main"],
    heads: { main: 5 },
    meta: { branch: "main", revision: 5, timestamp: { tick: 7 } },
    writeEpoch: 1,
  });
  harness.app.setMode("map-edit");
  const result = await harness.app.writeEditorTool(
    "formation",
    { mode: "update" },
    { branch: "main", revision: 1, writeEpoch: 0 }
  );
  assert.equal(result.ok, false);
  assert.equal(result.kind, "conflict");
  assert.equal(harness.app.getState().revision, 5, "409 必须拉到真实 head");
  assert.equal(harness.editorCalls.length, 1, "不得自动重试");
});
