// map-raster-lifecycle.test.cjs —— 底图导入工作区的真实事件/异步生命周期（不是源码扫描）。
//
// 覆盖两项独立评审 P2 的回归：
//   P2-1 图片加载令牌与参数/恢复代次分离：改参数不取消首次/替换图的 Image.onload。
//   P2-2 在途导入锁定工作区与公开操作；成功/拒绝后解锁；旧响应不能污染新上下文。
// 另覆盖：预览冻结参数/branch/tick/revision/writeEpoch、确认前零写、一次完整命令、IndexedDB 恢复不自动应用、清除有效、存储失败降级。
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui } = require("./helpers/webui-loader.cjs");

const MODULE_DIR = path.resolve(
  __dirname,
  "../../../../simos-map/src/main/resources/webui/modules/map"
);
const DRAFT_KEY = "http://test|main|Map1";

const TERRAINS = [
  { key: "ocean", name: "海洋", color: "#1F5FA0", minHeight: 0, maxHeight: 0.3 },
  { key: "plains", name: "平原", color: "#9CCB5B", minHeight: 0.3, maxHeight: 0.45 },
  { key: "mountains", name: "山地", color: "#8C6E4A", minHeight: 0.7, maxHeight: 1.1 },
];

function colorRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
}

function solidPixels(width, height, hex, alpha) {
  const pixels = new Uint8ClampedArray(width * height * 4);
  const c = colorRgb(hex);
  for (let i = 0; i < width * height; i++) {
    pixels[i * 4] = c.r;
    pixels[i * 4 + 1] = c.g;
    pixels[i * 4 + 2] = c.b;
    pixels[i * 4 + 3] = alpha === undefined ? 255 : alpha;
  }
  return pixels;
}

function makeElement(id, kind) {
  const listeners = {};
  const element = {
    id: id || "",
    kind: kind || "element",
    disabled: false,
    hidden: false,
    checked: false,
    value: "",
    className: "",
    files: [],
    open: false,
    style: {},
    attributes: {},
    children: [],
    classList: {
      add() {},
      remove() {},
      contains() {
        return false;
      },
      toggle() {},
    },
    addEventListener(type, fn) {
      (listeners[type] || (listeners[type] = [])).push(fn);
    },
    removeEventListener() {},
    dispatch(type, event) {
      const evt = Object.assign(
        {
          type,
          defaultPrevented: false,
          preventDefault() {
            this.defaultPrevented = true;
          },
        },
        event || {}
      );
      (listeners[type] || []).forEach((fn) => fn(evt));
      return evt;
    },
    listenerCount(type) {
      return (listeners[type] || []).length;
    },
    setAttribute(name, value) {
      this.attributes[name] = value;
      this[name] = value;
    },
    removeAttribute(name) {
      delete this.attributes[name];
      delete this[name];
    },
    getAttribute(name) {
      return this.attributes[name];
    },
    appendChild(child) {
      this.children.push(child);
      return child;
    },
    querySelector() {
      return null;
    },
    getBoundingClientRect() {
      return { left: 0, top: 0, width: 1100, height: 650 };
    },
    scrollIntoView() {},
    showModal() {
      this.open = true;
    },
    close() {
      this.open = false;
    },
  };
  let text = "";
  Object.defineProperty(element, "textContent", {
    get() {
      return text;
    },
    set(value) {
      text = String(value);
      element.children = [];
    },
  });
  if (kind === "canvas") {
    element.width = 1100;
    element.height = 650;
    const context = {
      sourceImage: null,
      drawImage(image) {
        this.sourceImage = image;
      },
      getImageData() {
        return {
          data:
            this.sourceImage && this.sourceImage.pixels
              ? this.sourceImage.pixels
              : new Uint8ClampedArray(0),
        };
      },
      clearRect() {},
      beginPath() {},
      moveTo() {},
      lineTo() {},
      closePath() {},
      fill() {},
      stroke() {},
    };
    element.getContext = () => context;
  }
  return element;
}

async function flush(times) {
  const n = times || 3;
  for (let i = 0; i < n; i++) {
    await new Promise((resolve) => setImmediate(resolve));
  }
}

function createHarness() {
  const elements = {};
  const dataKindNodes = [makeElement("kind-relief"), makeElement("kind-height")];
  dataKindNodes[0].setAttribute("data-raster-kind", "relief");
  dataKindNodes[1].setAttribute("data-raster-kind", "height");
  const imageSpecs = [];
  const images = [];
  let commandCalls = [];
  let commandDeferred = null;
  let stateChanges = [];
  const draft = {
    saved: new Map(),
    saves: [],
    clears: [],
    loads: [],
    saveBehavior: null,
    loadBehavior: null,
  };

  class FakeImage {
    constructor() {
      this.spec = imageSpecs.shift() || { width: 32, height: 32, pixels: solidPixels(32, 32, "#9CCB5B") };
      this.onload = null;
      this.onerror = null;
      this.naturalWidth = 0;
      this.naturalHeight = 0;
      this.width = 0;
      this.height = 0;
      this.pixels = null;
      images.push(this);
    }
    set src(value) {
      this._src = value;
    }
    get src() {
      return this._src;
    }
    resolve() {
      this.naturalWidth = this.width = this.spec.width;
      this.naturalHeight = this.height = this.spec.height;
      this.pixels = this.spec.pixels;
      if (this.onload) this.onload();
    }
    fail() {
      if (this.onerror) this.onerror();
    }
  }

  const document = {
    readyState: "complete",
    addEventListener() {},
    getElementById(id) {
      if (!elements[id]) elements[id] = makeElement(id, id === "raster-canvas" ? "canvas" : "element");
      return elements[id];
    },
    querySelectorAll(selector) {
      return selector === "[data-raster-kind]" ? dataKindNodes.slice() : [];
    },
    querySelector() {
      return null;
    },
    createElement(tag) {
      return makeElement("", tag === "canvas" ? "canvas" : "element");
    },
    createTextNode(text) {
      return { nodeType: 3, textContent: String(text) };
    },
  };

  const appState = {
    branch: "main",
    revision: 2,
    tick: 1,
    writeEpoch: 3,
    mode: "map-edit",
    heads: { main: 2 },
  };
  const app = {
    getState: () => appState,
    target: () => ({ branch: appState.branch, revision: appState.revision }),
    onStateChange(fn) {
      stateChanges.push(fn);
    },
    writeCommand(type, payload) {
      commandCalls.push({ type, payload });
      return new Promise((resolve, reject) => {
        commandDeferred = { resolve, reject };
      });
    },
  };
  let apiHold = false;
  const apiWaiters = [];
  function apiResponse(route) {
    if (route.indexOf("/map/overview") >= 0) {
      return { mapId: "Map1", terrainTypes: TERRAINS };
    }
    if (route.indexOf("/map/hexes") >= 0) {
      return { hexes: [{ q: 0, r: 0 }] };
    }
    throw new Error("unexpected route " + route);
  }
  const api = {
    withTarget: (route) => route,
    getJson(route) {
      let response;
      try {
        response = apiResponse(route);
      } catch (error) {
        return Promise.reject(error);
      }
      if (!apiHold) return Promise.resolve(response);
      return new Promise((resolve) => apiWaiters.push(() => resolve(response)));
    },
  };
  const draftStore = {
    load(key) {
      draft.loads.push(key);
      if (draft.loadBehavior) return draft.loadBehavior(key);
      return Promise.resolve(draft.saved.get(key));
    },
    save(key, value) {
      draft.saves.push({ key, value });
      if (draft.saveBehavior) return draft.saveBehavior(key, value);
      draft.saved.set(key, value);
      return Promise.resolve();
    },
    clear(key) {
      draft.clears.push(key);
      draft.saved.delete(key);
      return Promise.resolve();
    },
  };
  const renderer = {
    overlays: [],
    setPngOverlay(value) {
      this.overlays.push(value);
    },
    setView(value) {
      this.view = value;
    },
  };
  let fetchCalls = 0;
  const win = {
    SimosHexGeom: loadWebui("hexgeom.js").SimosHexGeom,
    SimosApi: api,
    SimosApp: app,
    SimosRasterDraft: draftStore,
    SimosMapCore: { host: { mapEditTool: "image-import" }, active: renderer, BASE_CELL: 22 },
    location: { origin: "http://test" },
  };
  const sandbox = {
    window: win,
    document,
    console,
    Image: FakeImage,
    URL: {
      createObjectURL(blob) {
        return "blob:" + (blob && blob.name ? blob.name : "test");
      },
      revokeObjectURL() {},
    },
    fetch() {
      fetchCalls++;
      return Promise.resolve({ ok: true, blob: () => Promise.resolve({ name: "example.png", size: 1024 }) });
    },
    setTimeout,
    clearTimeout,
  };
  win.window = win;
  win.document = document;
  const editorSelections = [];
  win.SimosMapEditor = {
    selectMapEditTool(tool) {
      editorSelections.push(tool);
    },
  };
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(path.join(MODULE_DIR, "map-raster-core.js"), "utf8"), sandbox);
  vm.runInContext(fs.readFileSync(path.join(MODULE_DIR, "map-png.js"), "utf8"), sandbox, {
    filename: "map-png.js",
  });

  const harness = {
    P: win.SimosMapPng,
    window: win,
    elements,
    images,
    appState,
    app,
    draft,
    renderer,
    editorSelections,
    flush,
    get fetchCalls() {
      return fetchCalls;
    },
    get commandCalls() {
      return commandCalls;
    },
    queueImage(spec) {
      imageSpecs.push(Object.assign({ width: 64, height: 64 }, spec));
    },
    fireStateChange() {
      stateChanges.forEach((fn) => fn());
    },
    resolveCommand(result) {
      commandDeferred.resolve(result);
    },
    rejectCommand(error) {
      commandDeferred.reject(error);
    },
    holdApi() {
      apiHold = true;
    },
    releaseApi() {
      apiHold = false;
      apiWaiters.splice(0).forEach((resolve) => resolve());
    },
    setExistingGrid() {
      elements["raster-grid"].value = "existing";
      elements["raster-kind"].value = "palette";
      elements["png-scale"].value = "1";
      elements["png-offset-x"].value = "-32";
      elements["png-offset-y"].value = "-32";
      elements["png-threshold"].value = "90";
    },
  };
  return harness;
}

async function loadFile(harness, name, spec) {
  harness.queueImage(spec);
  const input = harness.elements["png-file"];
  input.files = [{ name, size: 1024 }];
  input.dispatch("change");
  await flush(2);
  const image = harness.images[harness.images.length - 1];
  assert.ok(image, "选择文件后必须创建 Image 解码器");
  image.resolve();
  await flush(4);
  return image;
}

async function openWithDraft(harness, saved) {
  if (saved) harness.draft.saved.set(DRAFT_KEY, saved);
  const opening = harness.P.open();
  await flush(2);
  const image = harness.images[harness.images.length - 1];
  if (image && !image.pixels) image.resolve();
  await flush(4);
  await opening;
}

async function preparePlan(harness, spec) {
  harness.setExistingGrid();
  await loadFile(harness, "source.png", spec || { width: 64, height: 64, pixels: solidPixels(64, 64, "#9CCB5B") });
  await harness.P.preview();
  assert.ok(harness.P.getPlan(), "识别后必须有预览计划");
  harness.elements["raster-review"].dispatch("click");
  assert.equal(harness.elements["raster-confirmation"].hidden, false);
}

test("parameter-change-does-not-cancel-the-pending-new-image-decode", async () => {
  const harness = createHarness();
  harness.queueImage({ width: 48, height: 48, pixels: solidPixels(48, 48, "#9CCB5B") });
  const input = harness.elements["png-file"];
  input.files = [{ name: "big.png", size: 1024 }];
  input.dispatch("change");
  await flush(2);
  const pending = harness.images[harness.images.length - 1];
  assert.ok(pending, "解码器已创建");

  harness.elements["raster-kind"].value = "height";
  harness.elements["raster-kind"].dispatch("input");
  assert.equal(harness.P.getPlan(), null, "参数变化必须使旧计划失效");

  pending.resolve();
  await flush(4);
  assert.equal(harness.P.debugState().hasImage, true, "参数变化不得取消正在载入的新图");
  assert.equal(harness.P.debugState().width, 48);
  assert.equal(harness.elements["raster-file-info"].textContent.includes("48×48"), true);
});

test("replacing-image-ignores-the-stale-first-decode", async () => {
  const harness = createHarness();
  const input = harness.elements["png-file"];
  harness.queueImage({ width: 16, height: 16, pixels: solidPixels(16, 16, "#E02020") });
  input.files = [{ name: "a.png", size: 1024 }];
  input.dispatch("change");
  await flush(2);
  const first = harness.images[0];

  harness.queueImage({ width: 48, height: 48, pixels: solidPixels(48, 48, "#9CCB5B") });
  input.files = [{ name: "b.png", size: 1024 }];
  input.dispatch("change");
  await flush(2);
  const second = harness.images[1];

  first.resolve();
  await flush(4);
  assert.equal(harness.P.debugState().hasImage, false, "旧图的 onload 不得覆盖新选择");

  second.resolve();
  await flush(4);
  assert.equal(harness.P.debugState().hasImage, true);
  assert.equal(harness.P.debugState().width, 48, "最终图必须来自 B");
});

test("pending-import-locks-dom-and-all-public-mutations-until-success", async () => {
  const harness = createHarness();
  await preparePlan(harness);
  assert.equal(harness.commandCalls.length, 0, "确认前零写入");
  harness.elements["raster-confirm"].checked = true;
  const applying = harness.P.apply();
  assert.equal(harness.commandCalls.length, 1);
  assert.equal(harness.commandCalls[0].type, "map.ImportRaster");

  for (const id of ["png-file", "png-clear", "raster-close", "raster-confirm", "raster-refine"]) {
    assert.equal(harness.elements[id].disabled, true, "锁定时 " + id + " 必须 disabled");
  }
  assert.equal(harness.elements["raster-canvas"].attributes["aria-disabled"], "true");
  assert.equal(harness.elements["raster-dialog"].attributes["aria-busy"], "true");

  await harness.P.clear();
  await harness.P.preview();
  await harness.P.loadExample();
  await harness.P.open();
  assert.equal(harness.P.close(), false);
  harness.P.onToolChange("terrain");
  assert.equal(harness.P.debugState().hasImage, true, "在途时 clear 不得清掉本次图片");
  assert.equal(harness.P.debugState().applying, true);
  assert.equal(harness.fetchCalls, 0, "在途时不得重新载入示例");
  assert.ok(harness.P.getPlan(), "在途时 preview 不得替换计划");

  const cancel = harness.elements["raster-dialog"].dispatch("cancel");
  assert.equal(cancel.defaultPrevented, true, "Esc/关闭必须在在途时被拦截");
  const imageCount = harness.images.length;
  harness.elements["png-file"].files = [{ name: "other.png", size: 1024 }];
  harness.elements["png-file"].dispatch("change");
  assert.equal(harness.images.length, imageCount, "在途时文件 change 不得启动第二次解码");
  assert.equal(harness.elements["png-file"].value, "");

  harness.appState.revision = 3;
  harness.appState.heads.main = 3;
  harness.resolveCommand({ ok: true, body: { ref: { branch: "main", revision: 3 } } });
  await applying;

  assert.equal(harness.P.debugState().applying, false);
  assert.equal(harness.elements["png-file"].disabled, false, "成功后必须解锁");
  assert.equal(harness.elements["png-clear"].disabled, false);
  assert.equal(harness.elements["raster-close"].disabled, false);
  assert.equal(harness.elements["raster-dialog"].attributes["aria-busy"], undefined);
  assert.equal(harness.elements["raster-canvas"].attributes["aria-disabled"], undefined);
  assert.equal(harness.elements["raster-finished"].hidden, false, "成功进入完成态");
  assert.ok(harness.elements["png-status"].textContent.includes("已原子导入"));
  assert.equal(harness.P.getPlan(), null);

  const payload = harness.commandCalls[0].payload;
  assert.equal(payload.mode, "patch");
  assert.equal(payload.cells.length, 1);
  assert.deepEqual(Object.keys(payload.cells[0]).sort(), ["height", "q", "r", "terrain"]);
  assert.equal(harness.commandCalls.length, 1, "只提交一次完整命令");
});

test("stale-preview-response-cannot-re-arm-apply-after-a-parameter-change", async () => {
  const harness = createHarness();
  harness.setExistingGrid();
  await loadFile(harness, "source.png", {
    width: 64,
    height: 64,
    pixels: solidPixels(64, 64, "#9CCB5B"),
  });
  harness.holdApi();
  const previewing = harness.P.preview();
  await flush(2);
  harness.elements["raster-kind"].value = "height";
  harness.elements["raster-kind"].dispatch("input");
  harness.releaseApi();
  await previewing;
  assert.equal(harness.P.getPlan(), null, "旧预览响应不得在参数变化后恢复计划");
  assert.equal(harness.elements["png-apply"].disabled, true);
});

test("non-writable-context-cannot-submit-and-invalidates-the-plan", async () => {
  for (const mutate of [
    (h) => {
      h.appState.mode = "normal";
    },
    (h) => {
      h.appState.heads = { main: 99 };
    },
    (h) => {
      h.window.SimosMapCore.host.mapEditTool = "terrain";
    },
  ]) {
    const harness = createHarness();
    await preparePlan(harness);
    mutate({ appState: harness.appState, window: harness.window });
    harness.elements["raster-confirm"].checked = true;
    await harness.P.apply();
    assert.equal(harness.commandCalls.length, 0, "非 map-edit/历史节点/非导入子工具不得写");
    assert.equal(harness.P.getPlan(), null);
    assert.ok(harness.elements["png-status"].textContent.includes("目标节点或预览已变化"));
  }
});

test("successful-import-refine-returns-to-the-terrain-brush-and-focuses-the-view", async () => {
  const harness = createHarness();
  await preparePlan(harness);
  harness.elements["raster-confirm"].checked = true;
  const applying = harness.P.apply();
  harness.appState.revision = 3;
  harness.appState.heads.main = 3;
  harness.resolveCommand({ ok: true, body: { ref: { branch: "main", revision: 3 } } });
  await applying;

  harness.elements["raster-refine"].dispatch("click");
  assert.deepEqual(harness.editorSelections, ["terrain"], "完成后必须回到地形刷");
  assert.equal(harness.elements["raster-dialog"].open, false, "进入修整前关闭工作区");
  assert.ok(harness.renderer.view, "主图视图必须对准导入图幅");
  assert.ok(harness.renderer.view.scale > 0);
});

test("example-load-resets-to-relief-generate-and-reports-the-example-name", async () => {
  const harness = createHarness();
  harness.queueImage({ width: 40, height: 30, pixels: solidPixels(40, 30, "#1F5FA0") });
  harness.elements["raster-kind"].value = "palette";
  harness.elements["raster-grid"].value = "existing";
  const loading = harness.P.loadExample();
  await flush(2);
  const image = harness.images[harness.images.length - 1];
  image.resolve();
  await loading;
  assert.equal(harness.fetchCalls, 1);
  assert.equal(harness.P.debugState().hasImage, true);
  assert.equal(harness.P.debugState().fileName, "非洲之角示例");
  assert.equal(harness.elements["raster-kind"].value, "relief");
  assert.equal(harness.elements["raster-grid"].value, "generate");
});

test("image-file-over-32mb-or-over-32m-pixels-is-rejected-without-a-draft", async () => {
  const harness = createHarness();
  const input = harness.elements["png-file"];
  input.files = [{ name: "huge.png", size: 33 * 1024 * 1024 }];
  input.dispatch("change");
  await flush(3);
  assert.equal(harness.P.debugState().hasImage, false);
  assert.ok(harness.elements["png-status"].textContent.includes("32MB"));

  harness.queueImage({ width: 6000, height: 6000, pixels: new Uint8ClampedArray(4) });
  input.files = [{ name: "huge-pixels.png", size: 1024 }];
  input.dispatch("change");
  await flush(2);
  harness.images[harness.images.length - 1].resolve();
  await flush(4);
  assert.equal(harness.P.debugState().hasImage, false);
  assert.ok(harness.elements["png-status"].textContent.includes("3200 万像素"));
});

test("rejected-import-unlocks-keeps-the-draft-pending-and-never-claims-success", async () => {
  const harness = createHarness();
  await preparePlan(harness);
  harness.elements["raster-confirm"].checked = true;
  const applying = harness.P.apply();
  harness.resolveCommand({ ok: false, message: "重复坐标" });
  await applying;

  assert.equal(harness.P.debugState().applying, false);
  assert.equal(harness.elements["png-file"].disabled, false, "拒绝后必须解锁");
  assert.equal(harness.elements["raster-dialog"].attributes["aria-busy"], undefined);
  assert.equal(harness.elements["raster-finished"].hidden, true, "拒绝不得进入完成态");
  assert.equal(harness.elements["png-apply"].disabled, true);
  assert.ok(harness.elements["png-status"].textContent.includes("导入未成功"));
  assert.ok(harness.elements["png-status"].textContent.includes("重复坐标"));
  assert.ok(harness.P.getPlan(), "拒绝后保留待审草稿，不自动重试");
  assert.equal(harness.commandCalls.length, 1);
});

test("stale-import-response-cannot-mark-a-new-context-complete", async () => {
  const harness = createHarness();
  await preparePlan(harness);
  harness.elements["raster-confirm"].checked = true;
  const applying = harness.P.apply();

  harness.appState.branch = "other";
  harness.appState.revision = 2;
  harness.appState.tick = 1;
  harness.appState.heads = { other: 2 };
  harness.fireStateChange();
  harness.resolveCommand({ ok: true, body: { ref: { branch: "main", revision: 3 } } });
  await applying;

  assert.equal(harness.P.debugState().applying, false);
  assert.equal(harness.elements["raster-finished"].hidden, true, "旧响应不得把新上下文标成完成");
  assert.ok(harness.elements["png-status"].textContent.includes("当前目标已变化"));
  assert.equal(harness.P.debugState().hasImage, false, "分支变化后在途结果不得留下旧图");
  assert.equal(harness.P.getPlan(), null);
});

test("parameter-branch-tick-revision-writeEpoch-each-invalidate-the-frozen-preview", async () => {
  const harness = createHarness();
  await preparePlan(harness);
  harness.elements["raster-kind"].value = "height";
  harness.elements["raster-kind"].dispatch("input");
  assert.equal(harness.P.getPlan(), null, "参数变化必须失效");
  assert.ok(harness.elements["png-status"].textContent.includes("参数已变化"));

  await preparePlan(harness);
  harness.appState.tick = 2;
  harness.fireStateChange();
  assert.equal(harness.P.getPlan(), null, "tick 变化必须失效");

  await preparePlan(harness);
  harness.appState.revision = 3;
  harness.appState.heads.main = 3;
  harness.fireStateChange();
  assert.equal(harness.P.getPlan(), null, "revision 变化必须失效");

  await preparePlan(harness);
  harness.appState.writeEpoch = 4;
  harness.fireStateChange();
  assert.equal(harness.P.getPlan(), null, "writeEpoch 变化必须失效");

  await preparePlan(harness);
  harness.appState.branch = "other";
  harness.appState.heads = { other: 2 };
  harness.fireStateChange();
  assert.equal(harness.P.getPlan(), null, "branch 变化必须失效");
  assert.equal(harness.P.debugState().hasImage, false, "分支变化重置旧图/草稿上下文");
});

test("indexeddb-restore-returns-image-and-corrections-but-never-an-applicable-plan", async () => {
  const harness = createHarness();
  harness.queueImage({ width: 40, height: 30, pixels: solidPixels(40, 30, "#9CCB5B") });
  await openWithDraft(harness, {
    version: 1,
    blob: { name: "restored.png", size: 1024 },
    fileName: "restored.png",
    parameters: { "raster-kind": "height", "raster-columns": "24" },
    corrections: { "0_0": "plains" },
  });
  const debug = harness.P.debugState();
  assert.equal(debug.hasImage, true);
  assert.equal(debug.fileName, "restored.png");
  assert.equal(debug.plan, null, "恢复不得保存可直接提交的旧计划");
  assert.equal(harness.elements["png-apply"].disabled, true, "恢复后必须重新识别才能应用");
  assert.equal(harness.elements["raster-columns"].value, "24");
  assert.equal(harness.elements["raster-kind"].value, "height");
  assert.deepEqual(debug.overrides, { "0_0": "plains" });
  assert.ok(harness.elements["raster-draft-status"].textContent.includes("已恢复本机草稿"));
});

test("async-draft-restore-cannot-override-a-newer-image-selection", async () => {
  const harness = createHarness();
  harness.draft.saved.set(DRAFT_KEY, {
    version: 1,
    blob: { name: "restored.png", size: 1024 },
    fileName: "restored.png",
    parameters: {},
    corrections: {},
  });
  harness.queueImage({ width: 16, height: 16, pixels: solidPixels(16, 16, "#E02020") });
  const opening = harness.P.open();
  await flush(2);
  const restoring = harness.images[0];
  assert.ok(restoring, "恢复路径已开始解码旧草稿");

  harness.queueImage({ width: 48, height: 48, pixels: solidPixels(48, 48, "#9CCB5B") });
  harness.elements["png-file"].files = [{ name: "new.png", size: 1024 }];
  harness.elements["png-file"].dispatch("change");
  await flush(2);
  const newer = harness.images[1];
  assert.ok(newer, "新选择已开始解码");

  restoring.resolve();
  await flush(4);
  assert.equal(harness.P.debugState().hasImage, false, "旧草稿恢复不得覆盖新选择");
  newer.resolve();
  await flush(4);
  await opening;
  assert.equal(harness.P.debugState().fileName, "new.png");
  assert.equal(harness.P.debugState().width, 48);
  assert.equal(harness.P.debugState().plan, null, "新选择也必须重新识别");
});

test("clear-removes-the-image-and-the-matching-local-draft", async () => {
  const harness = createHarness();
  harness.queueImage({ width: 40, height: 30, pixels: solidPixels(40, 30, "#9CCB5B") });
  await openWithDraft(harness, {
    version: 1,
    blob: { name: "restored.png", size: 1024 },
    fileName: "restored.png",
    parameters: {},
    corrections: {},
  });
  await harness.P.clear();
  assert.deepEqual(harness.draft.clears, [DRAFT_KEY]);
  assert.equal(harness.P.debugState().hasImage, false);
  assert.equal(
    harness.elements["raster-file-info"].textContent.includes("自动识别在浏览器本地处理，不上传原图"),
    true
  );
  assert.equal(
    harness.elements["raster-file-info"].textContent.includes("上传到本机世界服务"),
    true,
    "共享参考图会传当前原图到本机世界服务，披露不得再写「不上传原图」了事"
  );
  assert.ok(harness.elements["png-status"].textContent.includes("已清除底图和草稿"));
});

test("draft-save-failure-degrades-to-this-session-only-without-claiming-saved", async () => {
  const harness = createHarness();
  harness.draft.saveBehavior = () => Promise.reject(new Error("quota"));
  await openWithDraft(harness, null);
  await loadFile(harness, "big.png", { width: 40, height: 30, pixels: solidPixels(40, 30, "#9CCB5B") });
  await new Promise((resolve) => setTimeout(resolve, 350));
  assert.ok(
    harness.elements["raster-draft-status"].textContent.includes("仅本次有效"),
    "存储失败必须明确降级，不得谎报已保存"
  );
});
