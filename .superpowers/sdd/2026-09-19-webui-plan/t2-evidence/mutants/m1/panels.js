// panels.js —— 左栏详情 / 右栏区域面板的骨架（M7 T2）。
// ★ 无框架、无构建：只挂载占位与订阅状态，真正的详情/分组渲染在 T5/T6 落地。
// ★ 数据只来自 window.SimosApi 的只读端点；本文件不含任何写调用。

(function () {
  "use strict";

  var app = window.SimosApp;

  function renderLeft(selection) {
    var status = app.byId("left-status");
    if (!status) {
      return;
    }
    status.textContent = selection ? "已选中（详情在 T5 接入）。" : "点选地图或单位以查看详情。";
  }

  function renderRight() {
    var mount = app.byId("region-panel-mount");
    if (!mount) {
      return;
    }
    app.clear(mount);
    mount.appendChild(app.el("p", { class: "empty", text: "区域分组与列表在 T6 接入。" }));
  }

  /** 工作台初始化：挂载两栏占位，并订阅状态以刷新左栏提示。 */
  function init() {
    renderLeft(null);
    renderRight();
    app.onStateChange(function (state) {
      renderLeft(state.selection);
    });
  }

  window.SimosPanels = {
    init: init,
    renderLeft: renderLeft,
    renderRight: renderRight,
  };
})();

// 变异 m1：注入 CDN 绝对 URL（R6 资产纪律，期望判定器红）
var CDN = "https://cdn.example.com/x.js";
