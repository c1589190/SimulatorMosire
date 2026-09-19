// timeline.js —— 底部时间轴的骨架（M7 T2）。
// ★ 无框架、无构建：画节点 / 拖动游标 / 末端写与分岔在 T3 落地（U1）。
// ★ 只读：本文件不含任何写调用；写仍只经 POST /api/command|advance|fork。

(function () {
  "use strict";

  var app = window.SimosApp;

  function renderMeta(state) {
    var node = app.byId("timeline-meta");
    if (!node) {
      return;
    }
    node.textContent = "分支 " + app.text(state.branch) + " · rev " + app.text(state.revision);
  }

  function init() {
    var mount = app.byId("timeline-mount");
    if (mount) {
      app.clear(mount);
      mount.appendChild(app.el("span", { class: "empty", text: "时间轴节点在 T3 接入。" }));
    }
    app.onStateChange(renderMeta);
    renderMeta(app.getState());
  }

  window.SimosTimeline = {
    init: init,
    renderMeta: renderMeta,
  };
})();
