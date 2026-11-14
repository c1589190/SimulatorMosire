// map-reference-tools.test.cjs —— 自由判图工具区的真实异步/DOM 行为（不是源码扫描）。
//
// 覆盖 T0–T4 的前端可判据：
//   ① 没有识别 plan 也能共享参考图、查看概览/局部、创建缺失格；
//   ② 任意选择查看/写地形/连接/擦除；两命令只走宿主写入口 app.writeCommand（不自己 api.submitCommand）；
//   ③ 历史只读/在途重复/坏输入零请求；拒绝/冲突不成功、不自动重试；
//   ④ 换分支/换图/迟到响应不能绑定错来源或把旧成功报成当前节点成功；
//   ⑤ 创建缺失格只提交读取时差集，读取期间变节点/变源/重复点击不提交旧差集；
//   ⑥ 公共 app.js writeCommand 的 Promise 生命周期（换分支/换 revision 后迟到成功与 409 不拉走游标）。
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui } = require("./helpers/webui-loader.cjs");

const WEBUI_DIR = path.resolve(__dirname, "../../../main/resources/webui");
const MODULE_DIR = path.resolve(
  __dirname,
  "../../../../simos-map/src/main/resources/webui/modules/map"
);
const REF_ID = "a".repeat(64);
const REF_ID_2 = "b".repeat(64);

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
  for (let i = 0; i < (times || 3); i++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
}

function makeElement(id, kind) {
  const listeners = {};
  const attributes = {};
  const element = {
    id: id || "",
    kind: kind || "element",
    tagName: (kind || "element").toUpperCase(),
    disabled: false,
    hidden: false,
    checked: false,
    value: "",
    className: "",
    textContent: "",
    src: "",
    open: false,
    style: {},
    files: [],
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
          preventDefault() {},
        },
        event || {}
      );
      (listeners[type] || []).forEach((fn) => fn(evt));
      return evt;
    },
    listenerCount(type) {
      return (listeners[type] || []).length;
    },
    appendChild(child) {
      this.children.push(child);
      return child;
    },
    remove() {},
    setAttribute(name, value) {
      attributes[name] = String(value);
      this[name] = value;
    },
    removeAttribute(name) {
      delete attributes[name];
      delete this[name];
    },
    getAttribute(name) {
      return attributes[name];
    },
    focus() {},
    select() {},
    showModal() {
      this.open = true;
    },
    close() {
      this.open = false;
    },
    getBoundingClientRect() {
      return { left: 0, top: 0, width: 200, height: 200 };
    },
    scrollIntoView() {},
  };
  // textContent="" 必须像浏览器一样清空 options（fillTerrainOptions / fillKindOptions 依赖它）。
  let text = "";
  Object.defineProperty(element, "textContent", {
    get() {
      return text;
    },
    set(value) {
      text = String(value);
      if (text === "") {
        element.children.length = 0;
      }
    },
  });
  return element;
}

function createHarness(options) {
  const opts = options || {};
  const elements = {};
  const ids = [
    "reference-tools",
    "reference-share",
    "reference-refresh",
    "reference-select",
    "reference-q",
    "reference-r",
    "reference-radius",
    "reference-overlay",
    "reference-view",
    "reference-open-viewer",
    "reference-image",
    "reference-viewer-image",
    "reference-viewer-dialog",
    "reference-viewer-close",
    "reference-paint-cells",
    "reference-paint-terrain",
    "reference-paint-height",
    "reference-paint-apply",
    "reference-connect-hexes",
    "reference-connect-kind",
    "reference-connect-op",
    "reference-connect-apply",
    "reference-create-missing",
    "reference-copy-params",
    "reference-params",
    "reference-status",
    "reference-view-status",
    "reference-default-terrain-note",
    "reference-click-target",
    "png-overlay-controls",
  ];
  ids.forEach((id) => {
    elements[id] = makeElement(id, id === "reference-viewer-dialog" ? "dialog" : "element");
  });
  elements["reference-radius"].value = "4";
  elements["reference-connect-op"].value = "merge";
  elements["reference-click-target"].value = "paint";
  elements["reference-overlay"].checked = true;
  elements["png-overlay-controls"].hidden = false;

  const state = Object.assign(
    {
      mode: "map-edit",
      branch: "main",
      revision: 1,
      tick: 7,
      writeEpoch: 0,
      heads: { main: 1 },
    },
    opts.state || {}
  );
  const host = { mapEditTool: "image-import" };
  const png = Object.assign({ sourceVersion: 1 }, opts.png || {});
  const calls = [];
  const stateListeners = [];
  let getJsonHandler = opts.getJson || (() => ({}));
  let postJsonHandler = opts.postJson || (() => ({}));
  let writeHandler =
    opts.writeCommand ||
    (() => ({
      ok: true,
      body: { ref: { branch: state.branch, revision: state.revision } },
    }));

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
    execCommand() {},
  };
  const win = {
    document: documentStub,
    console,
    navigator: {
      clipboard: {
        writeText() {
          return Promise.resolve();
        },
      },
    },
  };
  const app = {
    getState() {
      return state;
    },
    target() {
      return { branch: state.branch, revision: state.revision, writeEpoch: state.writeEpoch };
    },
    onStateChange(fn) {
      stateListeners.push(fn);
    },
    refreshState() {
      return Promise.resolve();
    },
    writeCommand(type, payload, frozen) {
      calls.push({ kind: "write", type, payload, frozen });
      return writeHandler(type, payload, frozen);
    },
  };
  const api = {
    withTarget(pathname, target) {
      if (!target || target.branch === null || target.branch === undefined) {
        return pathname;
      }
      const sep = pathname.indexOf("?") >= 0 ? "&" : "?";
      let query = "branch=" + encodeURIComponent(target.branch);
      if (target.revision !== null && target.revision !== undefined) {
        query += "&revision=" + encodeURIComponent(target.revision);
      }
      if (target.writeEpoch !== null && target.writeEpoch !== undefined) {
        query += "&writeEpoch=" + encodeURIComponent(target.writeEpoch);
      }
      return pathname + sep + query;
    },
    getJson(pathname) {
      calls.push({ kind: "get", path: pathname });
      return Promise.resolve(getJsonHandler(pathname)).then((value) => value);
    },
    postJson(pathname, body) {
      calls.push({ kind: "post", path: pathname, body });
      return Promise.resolve(postJsonHandler(pathname, body)).then((value) => value);
    },
  };
  const raster = {
    heightOf() {
      return 0.42;
    },
    insideHex() {
      return false;
    },
  };
  win.window = win;
  win.console = console;
  win.SimosApp = app;
  win.SimosApi = api;
  win.SimosRasterCore = raster;
  win.SimosMapPng = Object.assign(
    {
      getReferenceInput() {
        return {
          blob: { size: 1024 },
          fileName: "plain.png",
          width: 320,
          height: 240,
          grid: [
            { q: 1, r: 1, imageX: 40, imageY: 40, imageRadius: 20 },
            { q: 1, r: 2, imageX: 80, imageY: 40, imageRadius: 20 },
            { q: 1, r: 3, imageX: 120, imageY: 40, imageRadius: 20 },
          ],
        };
      },
      blobBase64() {
        return Promise.resolve("UE5H");
      },
      debugState() {
        return { sourceVersion: png.sourceVersion };
      },
    },
    opts.pngOverrides || {}
  );
  win.SimosMapCore = { host };
  win.MutationObserver = undefined;

  vm.createContext(win);
  const source = fs.readFileSync(path.join(MODULE_DIR, "map-reference-tools.js"), "utf8");
  vm.runInContext(source, win, { filename: path.join(MODULE_DIR, "map-reference-tools.js") });

  return {
    P: win.SimosMapReferenceTools,
    elements,
    calls,
    state,
    host,
    png,
    app,
    api,
    stateListeners,
    setGetJson(fn) {
      getJsonHandler = fn;
    },
    setPostJson(fn) {
      postJsonHandler = fn;
    },
    setWrite(fn) {
      writeHandler = fn;
    },
    notifyState() {
      stateListeners.forEach((fn) => fn());
    },
    posts() {
      return calls.filter((call) => call.kind === "post");
    },
    gets() {
      return calls.filter((call) => call.kind === "get");
    },
    writes() {
      return calls.filter((call) => call.kind === "write");
    },
  };
}

const TERRAINS = [
  { key: "ocean", name: "海洋" },
  { key: "plains", name: "平原" },
  { key: "mountains", name: "山地" },
];
const GROUPS = [
  { id: "river", name: "河流" },
  { id: "road", name: "道路" },
];

function overviewBody() {
  return { terrainTypes: TERRAINS, pathwayGroups: GROUPS };
}

function referenceBody(id) {
  return {
    referenceId: id || REF_ID,
    fileName: "plain.png",
    mediaType: "image/png",
    imageWidth: 320,
    imageHeight: 240,
    cellCount: 3,
    gridRange: { minQ: 1, maxQ: 1, minR: 1, maxR: 3 },
  };
}

async function readyHarness(options) {
  const harness = createHarness(options);
  harness.setGetJson((pathname) => {
    if (pathname.indexOf("/map/references") === 0) {
      return { references: [referenceBody()] };
    }
    if (pathname.indexOf("/map/overview") === 0) {
      return overviewBody();
    }
    return {};
  });
  harness.elements["reference-refresh"].dispatch("click");
  await flush(5);
  return harness;
}

// ────────────────────────── ① 无 plan、可共享、参数形状 ──────────────────────────

test("tool-parameter-text-is-directly-callable-and-has-no-payloadJson", async () => {
  const harness = await readyHarness();
  harness.elements["reference-paint-cells"].value = "1,1\n1,3,0.6";
  harness.elements["reference-connect-hexes"].value = "1,1\n1,2";
  harness.P.updateParams();

  const body = JSON.parse(harness.elements["reference-params"].value);
  assert.equal(body.reference.tool, "simos.map.reference");
  assert.equal(body.reference.arguments.branch, "main");
  assert.equal(body.reference.arguments.revision, 1);
  assert.equal(body.paintTerrain.tool, "map.PaintTerrain");
  assert.deepEqual(body.paintTerrain.arguments.cells, [
    { q: 1, r: 1, terrain: "plains" },
    { q: 1, r: 3, terrain: "plains", height: 0.6 },
  ]);
  assert.equal(Object.prototype.hasOwnProperty.call(body.paintTerrain.arguments, "payloadJson"), false);
  assert.equal(body.connectHexes.tool, "map.ConnectHexes");
  assert.equal(body.connectHexes.arguments.kind, "river");
  assert.equal(body.connectHexes.arguments.op, "merge");
  assert.deepEqual(body.connectHexes.arguments.hexes, [
    { q: 1, r: 1 },
    { q: 1, r: 2 },
  ]);
  assert.equal(Object.prototype.hasOwnProperty.call(body.connectHexes.arguments, "payloadJson"), false);
});

test("share-sends-current-image-grid-without-a-recognition-plan", async () => {
  const harness = createHarness({
    postJson: () => ({ reference: referenceBody() }),
  });
  harness.setGetJson((pathname) => {
    if (pathname.indexOf("/map/references") === 0) {
      return { references: [referenceBody()] };
    }
    if (pathname.indexOf("/map/overview") === 0) {
      return overviewBody();
    }
    return {};
  });
  harness.P.share();
  await flush(5);

  const posts = harness.posts();
  assert.equal(posts.length, 1);
  assert.equal(posts[0].path, "/map/references");
  assert.equal(posts[0].body.fileName, "plain.png");
  assert.equal(posts[0].body.imageBase64, "UE5H");
  assert.deepEqual(
    posts[0].body.cells.map((cell) => [cell.q, cell.r]),
    [
      [1, 1],
      [1, 2],
      [1, 3],
    ]
  );
  assert.equal(harness.elements["reference-select"].value, REF_ID);
  assert.ok(harness.elements["reference-status"].textContent.includes("已共享参考图"));
});

test("share-drops-a-late-result-when-the-source-image-changed", async () => {
  const pending = deferred();
  const harness = createHarness({
    postJson: () => {
      throw new Error("源图已变，不允许发送");
    },
    pngOverrides: {
      blobBase64() {
        return pending.promise;
      },
    },
  });
  harness.P.share();
  await flush(1);
  harness.png.sourceVersion = 2;
  pending.resolve("UE5H");
  await flush(5);

  assert.equal(harness.posts().length, 0, "迟到共享不得把旧原图发出去");
  assert.ok(harness.elements["reference-status"].textContent.includes("底图已变化"));
});

test("late-reference-list-cannot-overwrite-a-newer-selection", async () => {
  const first = deferred();
  const second = deferred();
  let call = 0;
  const harness = createHarness();
  harness.setGetJson((pathname) => {
    if (pathname.indexOf("/map/references") === 0) {
      call += 1;
      return call === 1 ? first.promise : second.promise;
    }
    if (pathname.indexOf("/map/overview") === 0) {
      return overviewBody();
    }
    return {};
  });
  harness.P.refresh(true);
  await flush(1);
  harness.P.refresh(true);
  await flush(1);
  second.resolve({ references: [referenceBody(REF_ID_2)] });
  await flush(3);
  first.resolve({ references: [referenceBody(REF_ID)] });
  await flush(3);

  assert.equal(harness.elements["reference-select"].value, REF_ID_2);
  const values = harness.elements["reference-select"].children.map((option) => option.value);
  assert.deepEqual(values, [REF_ID_2]);
});

// ────────────────────────── ② 任意查看/写/连接/擦除 ──────────────────────────

test("view-local-passes-frozen-target-and-shows-real-coordinates", async () => {
  const harness = await readyHarness();
  harness.elements["reference-q"].value = "1";
  harness.elements["reference-r"].value = "2";
  harness.elements["reference-radius"].value = "2";
  harness.setGetJson((pathname) => {
    if (pathname.indexOf("/map/reference?") === 0) {
      return {
        referenceId: REF_ID,
        branch: "main",
        revision: 1,
        overview: false,
        radius: 2,
        imageBase64: "UE5H",
        image: { width: 640, height: 480, assetId: "x", byteSize: 3 },
        sourceCrop: { x: 0, y: 0, width: 160, height: 120 },
        cells: [{ q: 1, r: 1, built: true, terrain: "plains", height: 0.4 }],
      };
    }
    return overviewBody();
  });
  harness.P.view();
  await flush(4);

  const get = harness.gets().find((call) => call.path.indexOf("/map/reference?") === 0);
  assert.ok(get.path.includes("referenceId=" + REF_ID));
  assert.ok(get.path.includes("q=1"));
  assert.ok(get.path.includes("r=2"));
  assert.ok(get.path.includes("radius=2"));
  assert.ok(get.path.includes("terrainOverlay=true"));
  assert.ok(get.path.includes("branch=main"));
  assert.ok(get.path.includes("revision=1"));
  assert.ok(get.path.includes("writeEpoch=0"));
  assert.equal(harness.elements["reference-image"].src, "data:image/png;base64,UE5H");
  assert.ok(harness.elements["reference-view-status"].textContent.includes("中心 1,2 半径 2"));
  assert.ok(harness.elements["reference-view-status"].textContent.includes("640×480"));
});

test("view-rejects-half-center-bad-radius-and-bad-integer-input-before-any-request", async () => {
  const harness = await readyHarness();
  const before = harness.gets().length;
  harness.elements["reference-q"].value = "1";
  harness.elements["reference-r"].value = "";
  harness.elements["reference-view"].dispatch("click");
  await flush(2);
  assert.equal(harness.gets().length, before, "q/r 半给不得发请求");
  assert.ok(harness.elements["reference-status"].textContent.includes("必须同时给"));

  harness.elements["reference-q"].value = "1.5";
  harness.elements["reference-r"].value = "2";
  harness.elements["reference-view"].dispatch("click");
  await flush(2);
  assert.equal(harness.gets().length, before, "小数 q/r 不得发请求");

  harness.elements["reference-q"].value = "1";
  harness.elements["reference-r"].value = "2";
  harness.elements["reference-radius"].value = "9";
  harness.elements["reference-view"].dispatch("click");
  await flush(2);
  assert.equal(harness.gets().length, before, "半径越界不得发请求");
  assert.ok(harness.elements["reference-status"].textContent.includes("1..8"));
});

test("paint-terrain-writes-one-top-level-command-with-frozen-target", async () => {
  const harness = await readyHarness();
  harness.elements["reference-paint-cells"].value = "1,1\n1,3,0.6";
  harness.elements["reference-paint-terrain"].value = "mountains";
  harness.elements["reference-paint-apply"].dispatch("click");
  await flush(4);

  const writes = harness.writes();
  assert.equal(writes.length, 1);
  assert.equal(writes[0].type, "map.PaintTerrain");
  assert.deepEqual(writes[0].payload.cells, [
    { q: 1, r: 1, terrain: "mountains" },
    { q: 1, r: 3, terrain: "mountains", height: 0.6 },
  ]);
  assert.deepEqual(writes[0].frozen, {
    branch: "main",
    revision: 1,
    tick: 7,
    writeEpoch: 0,
  });
  assert.equal(harness.posts().length, 0, "写命令只能走宿主写入口，不能直接 POST /command");
});

test("connect-and-erase-use-kind-and-op-and-refresh-the-view", async () => {
  const harness = await readyHarness();
  harness.elements["reference-connect-hexes"].value = "1,1\n1,2\n1,3";
  harness.elements["reference-connect-kind"].value = "river";
  harness.elements["reference-connect-op"].value = "remove";
  harness.elements["reference-connect-apply"].dispatch("click");
  await flush(4);

  const writes = harness.writes();
  assert.equal(writes.length, 1);
  assert.equal(writes[0].type, "map.ConnectHexes");
  assert.deepEqual(writes[0].payload, {
    kind: "river",
    hexes: [
      { q: 1, r: 1 },
      { q: 1, r: 2 },
      { q: 1, r: 3 },
    ],
    op: "remove",
  });
  assert.ok(harness.elements["reference-status"].textContent.includes("直接改连通性成功"));
});

test("bad-inputs-produce-zero-write-requests", async () => {
  const harness = await readyHarness();
  const apply = harness.elements["reference-paint-apply"];
  const connect = harness.elements["reference-connect-apply"];

  // 词表空 ⇒ 明确禁用且不伪造血表；写按钮被禁用时点也不发。
  harness.setGetJson((pathname) => {
    if (pathname.indexOf("/map/overview") === 0) {
      return { terrainTypes: [], pathwayGroups: [] };
    }
    return { references: [] };
  });
  harness.elements["reference-refresh"].dispatch("click");
  await flush(5);
  apply.dispatch("click");
  connect.dispatch("click");
  await flush(2);
  assert.equal(harness.writes().length, 0);
  assert.ok(harness.elements["reference-paint-terrain"].children.length >= 1);

  // 重新载入词表后：小数坐标 / 越界高度 / 不足两点 / 非法 op 全部零写。
  const healthy = await readyHarness();
  healthy.elements["reference-paint-cells"].value = "1.5,2";
  healthy.elements["reference-paint-apply"].dispatch("click");
  await flush(2);
  healthy.elements["reference-paint-cells"].value = "1,1,1.2";
  healthy.elements["reference-paint-apply"].dispatch("click");
  await flush(2);
  healthy.elements["reference-connect-hexes"].value = "1,1";
  healthy.elements["reference-connect-apply"].dispatch("click");
  await flush(2);
  healthy.elements["reference-connect-kind"].value = "";
  healthy.elements["reference-connect-hexes"].value = "1,1\n1,2";
  healthy.elements["reference-connect-apply"].dispatch("click");
  await flush(2);
  assert.equal(healthy.writes().length, 0);
});

test("history-node-is-read-only-and-writes-nothing", async () => {
  const harness = await readyHarness();
  harness.state.revision = 1;
  harness.state.heads.main = 2; // 同分支历史节点
  harness.elements["reference-paint-cells"].value = "1,1";
  harness.elements["reference-paint-apply"].dispatch("click");
  await flush(2);

  assert.equal(harness.writes().length, 0);
  assert.ok(harness.elements["reference-status"].textContent.includes("不是地图编辑"));
});

test("duplicate-inflight-paint-click-submits-only-once", async () => {
  const pending = deferred();
  const harness = await readyHarness({
    writeCommand: () => pending.promise,
  });
  harness.elements["reference-paint-cells"].value = "1,1";
  harness.elements["reference-paint-apply"].dispatch("click");
  await flush(1);
  harness.elements["reference-paint-apply"].dispatch("click");
  await flush(1);
  assert.equal(harness.writes().length, 1);

  harness.state.revision = 2;
  harness.state.heads.main = 2;
  pending.resolve({ ok: true, body: { ref: { branch: "main", revision: 2 } } });
  await flush(5);
  assert.ok(harness.elements["reference-status"].textContent.includes("成功"));
});

test("rejection-and-conflict-never-report-success-and-never-retry", async () => {
  for (const result of [
    { ok: false, kind: "rejected", message: "未知地形" },
    { ok: false, kind: "conflict", message: "末端已移动" },
  ]) {
    const harness = await readyHarness({ writeCommand: () => result });
    harness.elements["reference-paint-cells"].value = "1,1";
    harness.elements["reference-paint-apply"].dispatch("click");
    await flush(4);
    assert.equal(harness.writes().length, 1, "拒绝/冲突都不得自动重试");
    const text = harness.elements["reference-status"].textContent;
    assert.ok(text.includes("未成功"), text);
    assert.equal(text.startsWith("直接刷地形成功"), false, "拒绝/冲突不得出现成功提示");
  }
});

test("late-success-on-another-cursor-is-not-reported-as-current-success", async () => {
  const pending = deferred();
  const harness = await readyHarness({ writeCommand: () => pending.promise });
  harness.elements["reference-paint-cells"].value = "1,1";
  harness.elements["reference-paint-apply"].dispatch("click");
  await flush(1);
  harness.state.branch = "b";
  harness.state.revision = 1;
  harness.state.heads = { main: 2, b: 1 };
  pending.resolve({ ok: true, body: { ref: { branch: "main", revision: 2 } } });
  await flush(5);

  const text = harness.elements["reference-status"].textContent;
  assert.ok(text.includes("已写入 main@2"), text);
  assert.ok(text.includes("当前游标为 b@1"), text);
  assert.equal(text.includes("成功："), false, "旧节点成功不得冒充当前目标成功");
});

test("refresh-after-write-refetches-overview", async () => {
  const harness = await readyHarness();
  const before = harness
    .gets()
    .filter((call) => call.path.indexOf("/map/overview") === 0).length;
  harness.elements["reference-paint-cells"].value = "1,1";
  harness.state.revision = 2;
  harness.state.heads.main = 2;
  harness.setWrite(() => ({ ok: true, body: { ref: { branch: "main", revision: 2 } } }));
  harness.elements["reference-paint-apply"].dispatch("click");
  await flush(5);
  const after = harness
    .gets()
    .filter((call) => call.path.indexOf("/map/overview") === 0).length;
  assert.ok(after > before, "写后必须刷新地形/通路组词表");
  assert.ok(harness.elements["reference-status"].textContent.includes("成功"));
});

// ────────────────────────── ③ 创建缺失格：差集/冻结/在途 ──────────────────────────

test("create-missing-submits-only-the-missing-diff-of-the-frozen-map", async () => {
  const harness = await readyHarness();
  harness.setGetJson((pathname) => {
    if (pathname.indexOf("/map/hexes") === 0) {
      return {
        count: 1,
        hexes: [{ q: 1, r: 1 }],
      };
    }
    if (pathname.indexOf("/map/overview") === 0) {
      return overviewBody();
    }
    return { references: [referenceBody()] };
  });
  harness.elements["reference-paint-terrain"].value = "mountains";
  harness.elements["reference-create-missing"].dispatch("click");
  await flush(5);

  const writes = harness.writes();
  assert.equal(writes.length, 1);
  assert.equal(writes[0].type, "map.ImportRaster");
  assert.equal(writes[0].payload.mode, "merge");
  assert.deepEqual(writes[0].payload.cells, [
    { q: 1, r: 2, terrain: "mountains", height: 0.42 },
    { q: 1, r: 3, terrain: "mountains", height: 0.42 },
  ]);
  assert.deepEqual(writes[0].frozen, { branch: "main", revision: 1, tick: 7, writeEpoch: 0 });
  assert.ok(harness.elements["reference-status"].textContent.includes("创建缺失格成功"));
});

test("create-missing-never-sends-old-diff-when-branch-or-source-changes-during-read", async () => {
  for (const change of ["branch", "source"]) {
    const pending = deferred();
    const harness = await readyHarness();
    harness.setGetJson((pathname) => {
      if (pathname.indexOf("/map/hexes") === 0) {
        return pending.promise;
      }
      if (pathname.indexOf("/map/overview") === 0) {
        return overviewBody();
      }
      return { references: [referenceBody()] };
    });
    harness.elements["reference-create-missing"].dispatch("click");
    await flush(1);
    if (change === "branch") {
      harness.state.branch = "b";
      harness.state.heads = { main: 1, b: 1 };
    } else {
      harness.png.sourceVersion = 2;
    }
    pending.resolve({ hexes: [{ q: 1, r: 1 }] });
    await flush(5);

    assert.equal(harness.writes().length, 0, change + " 变化后不得提交旧差集");
    assert.ok(harness.elements["reference-status"].textContent.includes("未发送创建格网"));
  }
});

test("create-missing-double-click-only-reads-and-submits-once", async () => {
  const pending = deferred();
  const harness = await readyHarness();
  harness.setGetJson((pathname) => {
    if (pathname.indexOf("/map/hexes") === 0) {
      return pending.promise;
    }
    if (pathname.indexOf("/map/overview") === 0) {
      return overviewBody();
    }
    return { references: [referenceBody()] };
  });
  harness.elements["reference-create-missing"].dispatch("click");
  await flush(1);
  harness.elements["reference-create-missing"].dispatch("click");
  await flush(1);
  assert.equal(
    harness.gets().filter((call) => call.path.indexOf("/map/hexes") === 0).length,
    1,
    "在途重复点击不得并发读取"
  );
  pending.resolve({ hexes: [] });
  await flush(5);
  assert.equal(harness.writes().length, 1);
});

test("create-missing-refuses-to-send-while-world-target-changed-before-write", async () => {
  const pending = deferred();
  const harness = await readyHarness();
  harness.setGetJson((pathname) => {
    if (pathname.indexOf("/map/hexes") === 0) {
      // 读取返回时（同一个响应）目标已变化：发送前的第二次校验必须拒发。
      return pending.promise.then((body) => {
        harness.state.revision = 9;
        harness.state.heads.main = 9;
        return body;
      });
    }
    if (pathname.indexOf("/map/overview") === 0) {
      return overviewBody();
    }
    return { references: [referenceBody()] };
  });
  harness.elements["reference-create-missing"].dispatch("click");
  await flush(1);
  pending.resolve({ hexes: [] });
  await flush(5);
  assert.equal(harness.writes().length, 0, "发送前目标变化必须拒发，而不是拿新 revision 承接旧差集");
});

// ────────────────────────── ④ 前端两命令写门（modes + map 子工具） ──────────────────────────

test("front-end-write-gates-allow-both-new-commands-only-in-map-edit-image-import", () => {
  const M = loadWebui("modes.js").SimosModes;
  assert.equal(M.isWriteAllowed("map-edit", "map.PaintTerrain"), true);
  assert.equal(M.isWriteAllowed("map-edit", "map.ConnectHexes"), true);
  for (const mode of ["view", "region", "region-edit", "unit", "battle", "decision"]) {
    assert.equal(M.isWriteAllowed(mode, "map.PaintTerrain"), false, mode + " 不得写 PaintTerrain");
    assert.equal(M.isWriteAllowed(mode, "map.ConnectHexes"), false, mode + " 不得写 ConnectHexes");
  }
  const map = loadWebui("map.js").SimosMap;
  for (const type of ["map.PaintTerrain", "map.ConnectHexes"]) {
    assert.equal(map.mapEditWriteGate("image-import", type).ok, true, type);
    assert.equal(map.mapEditWriteGate("terrain", type).ok, false, type);
    assert.equal(map.mapEditWriteGate("connectivity", type).ok, false, type);
  }
});

test("module-uses-the-single-host-write-entry-not-api-submitCommand", () => {
  const source = fs.readFileSync(path.join(MODULE_DIR, "map-reference-tools.js"), "utf8");
  assert.ok(source.includes("app.writeCommand("));
  assert.equal(source.includes("api.submitCommand"), false, "不得绕过宿主写入口");
  assert.equal(source.includes("app()"), false, "app 是对象，不是函数");
  assert.equal((source.match(/function createMissing\(/g) || []).length, 1);
});

// ────────────────────────── ⑤ 公共 app.js writeCommand 生命周期 ──────────────────────────

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
  const submitCalls = [];
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
    submitCommand(envelope) {
      submitCalls.push(envelope);
      return opts.submit(envelope);
    },
  };
  const app = win.SimosApp;
  app.setServerState(
    stateBody.branches,
    stateBody.heads,
    stateBody.meta,
    stateBody.writeEpoch
  );
  return {
    app,
    stateCalls,
    submitCalls,
    setStateBody(next) {
      stateBody = next;
    },
  };
}

test("writeCommand-normal-success-refreshes-and-advances-the-cursor", async () => {
  const harness = createAppHarness({
    submit: () =>
      Promise.resolve({
        result: "committed",
        ref: { branch: "main", revision: 2 },
      }),
  });
  harness.setStateBody({
    branches: ["main"],
    heads: { main: 2 },
    meta: { branch: "main", revision: 2, timestamp: { tick: 7 } },
    writeEpoch: 1,
  });
  const result = await harness.app.writeCommand("map.PaintTerrain", { cells: [] });
  assert.equal(result.ok, true);
  assert.equal(harness.app.getState().revision, 2);
  assert.equal(harness.stateCalls.length >= 1, true, "成功后必须刷新 state");
  assert.equal(harness.submitCalls.length, 1);
});

test("writeCommand-late-success-after-branch-switch-does-not-move-the-cursor", async () => {
  const pending = deferred();
  const harness = createAppHarness({ submit: () => pending.promise });
  const call = harness.app.writeCommand("map.PaintTerrain", { cells: [] });
  await flush(1);
  harness.app.setBranch("b");
  harness.app.setRevision(1);
  pending.resolve({ result: "committed", ref: { branch: "main", revision: 2 } });
  const result = await call;

  assert.equal(result.ok, true);
  assert.equal(harness.app.getState().branch, "b");
  assert.equal(harness.app.getState().revision, 1, "迟到成功不得把游标拉回旧分支");
});

test("writeCommand-late-success-after-history-node-change-keeps-the-history-cursor", async () => {
  const pending = deferred();
  const harness = createAppHarness({ submit: () => pending.promise });
  const call = harness.app.writeCommand("map.ConnectHexes", { kind: "river" });
  await flush(1);
  harness.app.setRevision(0); // 同分支历史节点
  pending.resolve({ result: "committed", ref: { branch: "main", revision: 2 } });
  const result = await call;

  assert.equal(result.ok, true);
  assert.equal(harness.app.getState().revision, 0);
});

test("writeCommand-normal-409-refreshes-and-advances-to-server-head-without-retry", async () => {
  const harness = createAppHarness({
    submit: () => {
      const error = new Error("conflict");
      error.status = 409;
      error.body = { current: { branch: "main", revision: 3 } };
      return Promise.reject(error);
    },
  });
  harness.setStateBody({
    branches: ["main"],
    heads: { main: 3 },
    meta: { branch: "main", revision: 3, timestamp: { tick: 7 } },
    writeEpoch: 2,
  });
  const result = await harness.app.writeCommand("map.PaintTerrain", { cells: [] });
  assert.equal(result.ok, false);
  assert.equal(result.kind, "conflict");
  assert.equal(harness.app.getState().revision, 3);
  assert.equal(harness.submitCalls.length, 1, "409 不得自动重试");
});

test("writeCommand-409-after-branch-switch-does-not-pull-the-new-branch", async () => {
  const pending = deferred();
  const harness = createAppHarness({ submit: () => pending.promise });
  const call = harness.app.writeCommand("map.PaintTerrain", { cells: [] });
  await flush(1);
  harness.app.setBranch("b");
  harness.app.setRevision(1);
  const error = new Error("conflict");
  error.status = 409;
  error.body = { current: { branch: "main", revision: 3 } };
  pending.reject(error);
  const result = await call;

  assert.equal(result.kind, "conflict");
  assert.equal(harness.app.getState().branch, "b");
  assert.equal(harness.app.getState().revision, 1);
  assert.equal(harness.submitCalls.length, 1);
});

test("writeCommand-422-reports-the-server-reason-and-does-not-retry", async () => {
  const harness = createAppHarness({
    submit: () => {
      const error = new Error("rejected");
      error.status = 422;
      error.body = { reason: "未知地形" };
      return Promise.reject(error);
    },
  });
  const result = await harness.app.writeCommand("map.PaintTerrain", { cells: [] });
  assert.equal(result.ok, false);
  assert.equal(result.kind, "rejected");
  assert.equal(result.message, "未知地形");
  assert.equal(harness.submitCalls.length, 1);
});
