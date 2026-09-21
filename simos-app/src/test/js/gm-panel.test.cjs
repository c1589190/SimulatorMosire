// gm-panel.test.cjs —— GM 交互界面（T8）：底栏按钮 + 全屏层，只显示 GM MCP 的工具使用。
//
// 三路证据：
//   ① 纯函数：toolUsageEntries / resultText / formatEntry / renderPlan（含**显式空态**）。
//   ② 动态：真加载 api.js（记录型 fetch）⇒ renderInto 必须恰打一次 GET /api/gm/tool-usage；
//      真加载 gm.js ⇒ mount 接线（点按钮开层并取数、点关闭收层、点刷新重取）。
//   ③ 静态：index.html 的底栏有按钮、有全屏层、**没有对话输入框**；styles.css 的 .gm-overlay 是 fixed + 可点击。
// ★ 故意违规自证：m1 面板写死空（不读服务端）⇒ 动态/渲染断言红；m2 按钮不接 panel ⇒ mount 断言红；
//   m3 .gm-overlay 改 position:absolute ⇒ 静态断言红。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

const G = loadWebui("gm.js").SimosGm;

function fakeEl(tag) {
  const el = {
    tagName: tag,
    children: [],
    className: "",
    textContent: "",
    hidden: true,
    attrs: {},
    listeners: {},
    appendChild(child) {
      this.children.push(child);
      return child;
    },
    removeChild(child) {
      const index = this.children.indexOf(child);
      if (index >= 0) {
        this.children.splice(index, 1);
      }
      return child;
    },
    setAttribute(key, value) {
      this.attrs[key] = String(value);
    },
    getAttribute(key) {
      return this.attrs[key];
    },
    addEventListener(type, fn) {
      this.listeners[type] = this.listeners[type] || [];
      this.listeners[type].push(fn);
    },
    click() {
      (this.listeners.click || []).forEach((fn) => fn());
    },
  };
  Object.defineProperty(el, "firstChild", {
    get() {
      return this.children.length ? this.children[0] : null;
    },
  });
  return el;
}

function fakeDoc(map) {
  return {
    createElement: (tag) => fakeEl(tag),
    getElementById: (id) => (map && map[id]) || null,
  };
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

test("module-loads", () => {
  assert.equal(typeof G.toolUsageEntries, "function");
  assert.equal(typeof G.renderPlan, "function");
  assert.equal(typeof G.renderInto, "function");
  assert.equal(typeof G.setOpen, "function");
  assert.equal(typeof G.mount, "function");
});

test("toolUsageEntries-reads-two-response-shapes", () => {
  assert.deepEqual(G.toolUsageEntries({ entries: [{ tool: "a" }] }), [{ tool: "a" }]);
  assert.deepEqual(G.toolUsageEntries([{ tool: "b" }]), [{ tool: "b" }]);
  assert.deepEqual(G.toolUsageEntries({}), []);
  assert.deepEqual(G.toolUsageEntries(null), []);
  assert.deepEqual(G.toolUsageEntries({ entries: "nope" }), []);
});

test("resultText-marks-success-and-failure", () => {
  assert.equal(G.resultText({ ok: true }), "成功");
  assert.equal(G.resultText({ ok: false, code: "BAD_REQUEST" }), "失败（BAD_REQUEST）");
  assert.equal(G.resultText({ ok: false }), "失败（ERROR）");
});

test("formatEntry-includes-tool-and-result", () => {
  const line = G.formatEntry({ tool: "sd.IssueDirective", ok: true, atEpochMs: 0 });
  assert.ok(line.includes("sd.IssueDirective"));
  assert.ok(line.includes("成功"));
  assert.ok(line.includes("·"));
});

test("renderPlan-empty-state-is-explicit", () => {
  const plan = G.renderPlan({ entries: [] });
  assert.equal(plan.count, 0);
  assert.equal(plan.empty, true);
  assert.deepEqual(plan.lines, []);
  assert.ok(plan.emptyText.length > 0, "空态必须有明确文案（不静默空白）");
});

test("renderPlan-keeps-server-order-and-flags", () => {
  const plan = G.renderPlan({
    entries: [
      { tool: "simos.command.submit", ok: true, code: null, atEpochMs: 1000 },
      { tool: "simos.map.hex", ok: false, code: "BAD_REQUEST", atEpochMs: 2000 },
    ],
  });
  assert.equal(plan.count, 2);
  assert.equal(plan.empty, false);
  assert.equal(plan.lines[0].tool, "simos.command.submit");
  assert.equal(plan.lines[0].ok, true);
  assert.equal(plan.lines[1].ok, false);
  assert.ok(plan.lines[1].text.includes("BAD_REQUEST"));
});

test("renderInto-reads-exactly-one-GET-api-gm-tool-usage", async () => {
  const calls = [];
  const recordingFetch = (url, init) => {
    calls.push({ url, method: init && init.method });
    return Promise.resolve({
      ok: true,
      status: 200,
      statusText: "OK",
      text: () =>
        Promise.resolve(
          JSON.stringify({ entries: [{ tool: "simos.command.catalog", ok: true, atEpochMs: 1 }] })
        ),
    });
  };
  const api = loadWebui("api.js", { fetch: recordingFetch }).SimosApi;
  const body = fakeEl("div");
  const status = fakeEl("span");

  const count = await G.renderInto(body, status, api, fakeDoc());

  assert.deepEqual(
    calls,
    [{ url: "/api/gm/tool-usage", method: "GET" }],
    "面板必须恰打一次 GET /api/gm/tool-usage"
  );
  assert.equal(count, 1);
});

test("renderInto-renders-server-entries-from-the-response", async () => {
  const api = {
    gmToolUsage: () =>
      Promise.resolve({
        entries: [
          { tool: "sd.IssueDirective", ok: true, code: null, atEpochMs: 5 },
          { tool: "simos.advance", ok: false, code: "CONFLICT", atEpochMs: 6 },
        ],
      }),
  };
  const body = fakeEl("div");
  const status = fakeEl("span");

  const count = await G.renderInto(body, status, api, fakeDoc());

  assert.equal(count, 2);
  assert.equal(body.children.length, 1);
  assert.equal(body.children[0].tagName, "ul");
  assert.equal(body.children[0].children.length, 2);
  assert.equal(body.children[0].children[0].textContent.includes("sd.IssueDirective"), true);
  assert.ok(body.children[0].children[1].className.includes("gm-usage-failed"));
  assert.ok(status.textContent.includes("2"));
});

test("renderInto-shows-empty-state-when-server-has-none", async () => {
  const api = { gmToolUsage: () => Promise.resolve({ entries: [] }) };
  const body = fakeEl("div");
  const status = fakeEl("span");

  const count = await G.renderInto(body, status, api, fakeDoc());

  assert.equal(count, 0);
  assert.equal(body.children.length, 1);
  assert.equal(body.children[0].tagName, "p");
  assert.equal(body.children[0].textContent, G.renderPlan({}).emptyText);
});

test("renderInto-degrades-when-endpoint-unavailable", async () => {
  const api = { gmToolUsage: () => Promise.reject(new Error("404 not found")) };
  const body = fakeEl("div");
  const status = fakeEl("span");

  const count = await G.renderInto(body, status, api, fakeDoc());

  assert.equal(count, null);
  assert.equal(body.children.length, 1);
  assert.ok(body.children[0].textContent.includes("不可达"));
  assert.equal(status.textContent, "不可达");
});

test("setOpen-toggles-overlay-and-aria-expanded", () => {
  const overlay = fakeEl("section");
  const button = fakeEl("button");
  overlay.hidden = true;

  assert.equal(G.isOpen(overlay), false);
  assert.equal(G.setOpen(overlay, button, true), true);
  assert.equal(overlay.hidden, false);
  assert.equal(button.getAttribute("aria-expanded"), "true");
  assert.equal(G.isOpen(overlay), true);

  assert.equal(G.setOpen(overlay, button, false), false);
  assert.equal(overlay.hidden, true);
  assert.equal(button.getAttribute("aria-expanded"), "false");
});

test("mount-wires-open-close-and-refresh", async () => {
  const calls = [];
  const api = {
    gmToolUsage: () => {
      calls.push("gm");
      return Promise.resolve({ entries: [] });
    },
  };
  const gm = loadWebui("gm.js", { SimosApi: api }).SimosGm;
  const elements = {
    "gm-overlay": fakeEl("section"),
    "gm-open": fakeEl("button"),
    "gm-tool-usage": fakeEl("div"),
    "gm-status": fakeEl("span"),
    "gm-close": fakeEl("button"),
    "gm-refresh": fakeEl("button"),
  };
  elements["gm-overlay"].hidden = true;

  const handle = gm.mount(fakeDoc(elements));
  assert.ok(handle, "mount 必须接上线（缺元素则返回 null）");

  elements["gm-open"].click();
  assert.equal(elements["gm-overlay"].hidden, false, "点底栏按钮必须打开全屏层");
  assert.equal(elements["gm-open"].getAttribute("aria-expanded"), "true");
  await settle();
  assert.equal(calls.length, 1, "打开后必须取一次工具使用");

  elements["gm-close"].click();
  assert.equal(elements["gm-overlay"].hidden, true, "点关闭必须收起、恢复原视图");
  assert.equal(elements["gm-open"].getAttribute("aria-expanded"), "false");

  elements["gm-refresh"].click();
  await settle();
  assert.equal(calls.length, 2, "刷新必须重取");
});

test("index.html-has-gm-button-in-timeline-bar-and-fullscreen-overlay", () => {
  const html = readWebui("index.html");
  const footerStart = html.indexOf('id="timeline-bar"');
  const footerEnd = html.indexOf("</footer>", footerStart);
  assert.ok(footerStart >= 0 && footerEnd > footerStart, "必须有底栏 #timeline-bar");
  const footer = html.slice(footerStart, footerEnd);
  assert.ok(footer.includes('id="gm-open"'), "GM 按钮必须落在底栏时间轴那条（#timeline-bar）里");
  assert.ok(footer.includes("GM 界面"), "按钮文案");
  assert.ok(html.includes('id="gm-overlay"'), "必须有全屏层");
  assert.ok(html.includes('id="gm-tool-usage"'), "全屏层必须有工具使用容器");
  assert.ok(html.includes('src="gm.js"'), "index.html 必须引用 gm.js");
});

test("gm-overlay-is-fixed-hidden-by-default-and-clickable", () => {
  const html = readWebui("index.html");
  const tagStart = html.indexOf('<section id="gm-overlay"');
  const tagEnd = html.indexOf(">", tagStart);
  assert.ok(tagStart >= 0, "全屏层必须存在");
  assert.ok(html.slice(tagStart, tagEnd).includes("hidden"), "全屏层默认 hidden（不挡底栏）");

  const css = readWebui("styles.css");
  const ruleStart = css.indexOf(".gm-overlay {");
  assert.ok(ruleStart >= 0, "styles.css 必须有 .gm-overlay 规则");
  const bodyStart = css.indexOf("{", ruleStart);
  const bodyEnd = css.indexOf("}", bodyStart);
  const rule = css.slice(bodyStart + 1, bodyEnd);
  assert.match(rule, /position:\s*fixed/, "必须 fixed（不参与文档流 ⇒ 不把底栏挤出视口）");
  assert.match(rule, /pointer-events:\s*auto/, "必须 auto（.wb-overlay 是 none ⇒ 不设点不到）");
});

test("gm-overlay-has-no-chat-input", () => {
  const html = readWebui("index.html");
  const start = html.indexOf('id="gm-overlay"');
  const end = html.indexOf("</section>", start);
  const overlay = html.slice(start, end);
  assert.equal(/<input\b/i.test(overlay), false, "一阶段不做对话：不得有输入框");
  assert.equal(/<textarea\b/i.test(overlay), false, "一阶段不做对话：不得有多行输入");
  assert.equal(/contenteditable/i.test(overlay), false, "一阶段不做对话：不得有可编辑区");
});
