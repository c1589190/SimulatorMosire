// notifications.test.cjs —— 右下角通知栏（T1）：读 GET /api/approvals，撤右栏审批计数。
//
// 两路证据：
//   ① 静态：index.html 存在通知栏元素且已撤掉右栏审批计数（#approvals-count / <h3>待批</h3>）；app.js 无 mountApprovals。
//   ② 动态：真加载 api.js（记录型 fetch）+ notifications.js，renderInto 必须恰打一次 GET /api/approvals。
// ★ 故意违规自证：m1 把审批块加回 index.html ⇒ 静态断言红；m2 通知栏不读 /api/approvals ⇒ 动态 fetch 记录断言红。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

const N = loadWebui("notifications.js").SimosNotifications;

function fakeNode() {
  return {
    textContent: "",
    hidden: true,
    classList: {
      _set: new Set(),
      add(name) {
        this._set.add(name);
      },
      remove(name) {
        this._set.delete(name);
      },
      contains(name) {
        return this._set.has(name);
      },
    },
  };
}

test("module-loads", () => {
  assert.equal(typeof N.renderInto, "function");
  assert.equal(typeof N.mount, "function");
  assert.equal(typeof N.pendingCount, "function");
});

test("pendingCount-reads-the-three-response-shapes", () => {
  assert.equal(N.pendingCount({ pending: [{}, {}] }), 2);
  assert.equal(N.pendingCount({ items: [{}] }), 1);
  assert.equal(N.pendingCount([{}, {}, {}]), 3);
  assert.equal(N.pendingCount({}), 0);
  assert.equal(N.pendingCount(null), 0);
});

test("summaryText-shows-the-count", () => {
  assert.equal(N.summaryText({ pending: [] }), "待批 0");
  assert.equal(N.summaryText({ pending: [{}, {}] }), "待批 2");
});

test("renderInto-reads-exactly-one-GET-api-approvals", async () => {
  const calls = [];
  const recordingFetch = (url, init) => {
    calls.push({ url, method: init && init.method });
    return Promise.resolve({
      ok: true,
      status: 200,
      statusText: "OK",
      text: () => Promise.resolve(JSON.stringify({ pending: [{ id: "a" }, { id: "b" }] })),
    });
  };
  const api = loadWebui("api.js", { fetch: recordingFetch }).SimosApi;
  const node = fakeNode();

  const count = await N.renderInto(node, api);

  assert.deepEqual(calls, [{ url: "/api/approvals", method: "GET" }], "通知栏必须恰打一次 GET /api/approvals");
  assert.equal(count, 2);
  assert.equal(node.textContent, "待批 2");
  assert.equal(node.hidden, false, "读到后必须显示（撤掉 hidden）");
});

test("renderInto-degrades-when-approvals-unavailable", async () => {
  const failingApi = { approvals: () => Promise.reject(new Error("503 未接入")) };
  const node = fakeNode();

  const count = await N.renderInto(node, failingApi);

  assert.equal(count, null);
  assert.match(node.textContent, /审批未接入/);
  assert.equal(node.hidden, false, "不可达也应显示（否则通知栏永远不可见）");
  assert.ok(node.classList.contains("muted"));
});

test("index.html-has-notification-element-and-no-right-panel-approvals", () => {
  const html = readWebui("index.html");
  const appJs = readWebui("app.js");
  assert.ok(html.includes('id="notifications"'), "右下角通知栏元素必须在 index.html 里");
  assert.ok(html.includes("notify-bar"), "通知栏应带 notify-bar class");
  assert.ok(html.includes('src="notifications.js"'), "index.html 必须引用 notifications.js");
  assert.equal(html.includes("approvals-count"), false, "右栏审批计数必须撤掉");
  assert.equal(html.includes("待批</h3>"), false, "右栏「待批」标题必须撤掉");
  assert.equal(html.includes("approvals:"), false, "boot 不得再传 approvals 参数");
  assert.equal(appJs.includes("mountApprovals"), false, "app.js 的 mountApprovals 必须撤掉");
});
