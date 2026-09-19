// unitTree.js —— 单位编制倒树的骨架（M7 T2）。
// ★ 无框架、无构建：组树与倒置渲染在 T5 落地（根在下、分岔点加粗放大）。
// ★ 数据只来自 /api/units 的 parent 字段；本文件不含任何写调用。

(function () {
  "use strict";

  var app = window.SimosApp;

  /** 由扁平 units 列表组出父子树。T5 实现：此处只给空骨架，返回 []。 */
  function buildTree(units) {
    if (!units || !units.length) {
      return [];
    }
    return [];
  }

  function render(units) {
    var mount = app.byId("unit-tree-mount");
    if (!mount) {
      return;
    }
    app.clear(mount);
    var roots = buildTree(units);
    if (!roots.length) {
      mount.appendChild(app.el("p", { class: "empty", text: "单位倒树在 T5 接入。" }));
    }
  }

  function init() {
    render([]);
  }

  window.SimosUnitTree = {
    init: init,
    buildTree: buildTree,
    render: render,
  };
})();
