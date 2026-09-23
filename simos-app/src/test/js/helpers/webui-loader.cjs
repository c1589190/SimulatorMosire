// webui-loader.cjs —— 在 node 里加载浏览器 IIFE 资产的最小宿主（T2 前端门禁）。
//
// 这些资产是 `(function(){ ... window.SimosX = {...} })()` 形态：顶层只读 window/document，
// 真正的业务（fetch/DOM 事件）都在函数体内、加载时不执行。这里用一个 vm 上下文把 window/document
// 之类的最小壳塞进去，即可安全取到挂在 window 上的纯函数命名空间。
//
// 路径一律由 __dirname 推出（不依赖 Maven/surefire 的工作目录）⇒ 主树与 git worktree 都成立。
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const WEBUI_DIR = path.resolve(__dirname, "../../../main/resources/webui");

// ★ M12 拆分第一步：map.js 的纯几何 / 颜色 / 区域边界已搬到兄弟文件（hexgeom / hexcolor /
//   regionShape）。浏览器里由宿主页按依赖顺序引入；node 宿主必须把这几份也灌进**同一沙箱**，
//   否则 map.js 顶层的 `window.SimosHexGeom` 取到 undefined ⇒ 顶层"取回块"直接炸。
//   这是"*.test.cjs 断言文件一字节不动"与"门禁仍绿"之间的唯一桥：只动本宿主壳。
//   ★ 新增 map.js 依赖的兄弟文件时，务必同步改这张表（顺序＝引入顺序）。
const BUNDLE_DEPS = {
  "map.js": ["hexgeom.js", "hexcolor.js", "regionShape.js"],
  // ★ M12 第五波：单位编辑宿主（map-uniteditor.js）与旧页/工作台 chrome（map-hostpage.js）自 map.js
  //   顶层搬出，浏览器里排在 map.js 之后（取 map.js 建立的 window.SimosMapCore）。map.js 对它们只做
  //   **惰性** window.SimosMapXxx 调用（同 window.SimosCreateRenderer 手法）⇒ 二者**不能**列进
  //   "map.js" 的依赖（否则会先于 map.js 执行、取不到 SimosMapCore）。反过来若 node 宿主直接
  //   loadWebui 这两份，则须先灌 map.js 及其依赖。
  "map-uniteditor.js": ["hexgeom.js", "hexcolor.js", "regionShape.js", "map.js"],
  "map-hostpage.js": ["hexgeom.js", "hexcolor.js", "regionShape.js", "map.js"],
  // ★ M12 第六波：地图编辑模式宿主（map-mapeditor.js）与区域编辑模式宿主（map-regioneditor.js）自
  //   map.js 顶层搬出，浏览器里排在 map.js 之后、renderer.js 之前（取 map.js 建立的
  //   window.SimosMapCore；map-mapeditor.js 还要赶在 renderer.js 之前把 core.renderEdgeKindOptions
  //   挂回）。同 map-uniteditor.js：它们**不能**列进 "map.js" 的依赖（会先于 map.js 执行、取不到
  //   SimosMapCore）；反过来 node 宿主若要直接 loadWebui 这两份，则须先灌 map.js 及其依赖。
  "map-mapeditor.js": ["hexgeom.js", "hexcolor.js", "regionShape.js", "map.js"],
  "map-regioneditor.js": ["hexgeom.js", "hexcolor.js", "regionShape.js", "map.js"],
  // ★ M12 第四波：paneldom.js（共享 DOM 叶子）与 panel-right.js（右栏）已从 panels.js 拆出；
  //   顺序＝引入顺序（paneldom 依赖 readout.formatValue ⇒ readout 必须最前）。
  "panels.js": ["readout.js", "decisionmodel.js", "paneldom.js", "panel-right.js"],
};

/** 取 webui 源码目录（判定用，测试里也会读它做静态扫描）。 */
function webuiDir() {
  return WEBUI_DIR;
}

/** 只读一个 webui 资产的 UTF-8 源码。 */
function readWebui(name) {
  return fs.readFileSync(path.join(WEBUI_DIR, name), "utf8");
}

/**
 * 加载一个 webui IIFE 资产并返回其 window 命名空间对象。
 * extraGlobals 覆盖/补充注入的全局（如记录型 fetch）。
 */
function loadWebui(name, extraGlobals) {
  const source = readWebui(name);
  const win = {};
  const documentStub = {
    addEventListener() {},
    querySelector() {
      return null;
    },
    querySelectorAll() {
      return [];
    },
    createElement() {
      return {
        style: {},
        classList: { add() {}, remove() {}, contains() { return false; } },
        setAttribute() {},
        appendChild() {},
        addEventListener() {},
        remove() {},
      };
    },
    body: { getAttribute() { return null; }, setAttribute() {}, classList: { add() {}, remove() {} } },
  };
  const sandbox = {
    window: win,
    document: documentStub,
    console,
    performance,
    fetch: () => Promise.reject(new Error("fetch not stubbed")),
    setTimeout,
    clearTimeout,
    // ★ 2026-09-23：轮询（`setInterval`/`clearInterval`）进了面板逻辑（决策那一轮改成异步 + 轮询 run-status）
    //   ⇒ 沙箱必须提供与浏览器同一套定时器面。**不给的后果不是"测试变红"，是 `setInterval is not defined`
    //   把功能整条打断**（而真实浏览器里一切正常）——宿主壳与运行时差一个全局，是最难发现的那种假红/假绿。
    setInterval,
    clearInterval,
    URL,
  };
  Object.assign(sandbox, extraGlobals || {});
  win.window = win;
  win.document = documentStub;
  win.console = console;
  win.setTimeout = setTimeout;
  win.setInterval = setInterval;
  win.clearInterval = clearInterval;
  win.performance = performance;
  if (extraGlobals) {
    Object.keys(extraGlobals).forEach((key) => {
      win[key] = extraGlobals[key];
    });
  }
  vm.createContext(sandbox);
  (BUNDLE_DEPS[name] || []).forEach((dep) => {
    vm.runInContext(readWebui(dep), sandbox, { filename: path.join(WEBUI_DIR, dep) });
  });
  vm.runInContext(source, sandbox, { filename: path.join(WEBUI_DIR, name) });
  return win;
}

module.exports = { loadWebui, readWebui, webuiDir };
