// notifications.js —— 右下角通知栏（本阶段 T1）：读 GET /api/approvals 显示待批摘要。
//
// ★ 撤除对象（本任务）：index.html 的右栏审批计数（#approvals-count / <h3>待批</h3>）与 app.js 的
//   mountApprovals —— 后端 /api/approvals 保留（D1 已裁），故 api.js 的 SimosApi.approvals() 原样保留。
// ★ 本元素在 .wb-overlay（pointer-events:none 的浮层机制）内，故样式里必须自行 pointer-events:auto，
//   且 position:fixed 的底距抬到时间轴之上 ⇒ 不占右栏、不挡底栏（M7d/M7g 的布局意图）。
"use strict";

(function () {
  "use strict";

  var DEFAULT_CONTAINER = "notifications";
  var POLL_MS = 5000;

  /** 从 /api/approvals 的响应体取待批条数（兼容 {pending:[…]} / {items:[…]} / 裸数组）。 */
  function pendingCount(body) {
    if (Array.isArray(body)) {
      return body.length;
    }
    if (body && Array.isArray(body.pending)) {
      return body.pending.length;
    }
    if (body && Array.isArray(body.items)) {
      return body.items.length;
    }
    return 0;
  }

  function summaryText(body) {
    return "待批 " + pendingCount(body);
  }

  /**
   * 把一次 /api/approvals 读数渲染进 node，返回待批条数（不可达时返回 null）。
   * node 只要求 textContent / classList / hidden（便于测试注入替身）。
   * ★ 该函数对 api.approvals() 的调用正是"通知栏读 /api/approvals"的可断言点。
   */
  function renderInto(node, api) {
    return Promise.resolve()
      .then(function () {
        return api.approvals();
      })
      .then(function (body) {
        node.textContent = summaryText(body);
        node.hidden = false;
        if (node.classList && node.classList.remove) {
          node.classList.remove("muted");
        }
        return pendingCount(body);
      })
      .catch(function (error) {
        node.textContent = "审批未接入（" + ((error && error.message) || error) + "）";
        node.hidden = false;
        if (node.classList && node.classList.add) {
          node.classList.add("muted");
        }
        return null;
      });
  }

  /** 挂载：读一次 + 每 5s 轮询（与既有轮询节奏一致）。 */
  function mount(containerId) {
    var node = document.getElementById(containerId || DEFAULT_CONTAINER);
    if (!node || !window.SimosApi) {
      return null;
    }
    renderInto(node, window.SimosApi);
    window.setInterval(function () {
      renderInto(node, window.SimosApi);
    }, POLL_MS);
    return node;
  }

  function init() {
    mount(DEFAULT_CONTAINER);
  }

  window.SimosNotifications = {
    pendingCount: pendingCount,
    summaryText: summaryText,
    renderInto: renderInto,
    mount: mount,
    init: init,
  };

  if (typeof document !== "undefined" && document.addEventListener) {
    document.addEventListener("DOMContentLoaded", init);
  }
})();
