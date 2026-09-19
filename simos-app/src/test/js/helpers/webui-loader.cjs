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
    URL,
  };
  Object.assign(sandbox, extraGlobals || {});
  win.window = win;
  win.document = documentStub;
  win.console = console;
  win.setTimeout = setTimeout;
  win.performance = performance;
  if (extraGlobals) {
    Object.keys(extraGlobals).forEach((key) => {
      win[key] = extraGlobals[key];
    });
  }
  vm.createContext(sandbox);
  vm.runInContext(source, sandbox, { filename: path.join(WEBUI_DIR, name) });
  return win;
}

module.exports = { loadWebui, readWebui, webuiDir };
