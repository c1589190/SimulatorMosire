// paneldom.js —— panels.js / panel-right.js 共享的**叶子工具层**（M12 第四波）。
// ★ 无框架、无构建：原生 DOM；只经 window.SimosApp / window.SimosReadout / window.SimosApi。
// ★ 这里只放**被多处调用**的叶子：函数体逐字节照搬自 panels.js，调用点一个字没改
//   （消费方在文首按名取回 ⇒ `window.SimosPanels` 导出对象不动）。
// ★ 必须在消费方（panels.js / panel-right.js）之前加载：index.html 与 node 门禁宿主
//   （test/js/helpers/webui-loader.cjs 的 BUNDLE_DEPS）都要同步；readout.js 也必须在本文件之前引入
//   （appendRow 的取值口径走 readout.formatValue）。

(function () {
  "use strict";

  var app = window.SimosApp;
  var api = window.SimosApi;
  var formatValue = window.SimosReadout.formatValue;

  // ── 地图总览共享缓存（M7b T2 → M9 T2 移入 api.js 的共享记忆化层）──
  //   左栏 ETA / 右栏区域 / map.js 渲染共用同一份按 target 记忆化的缓存（同一 URL×target 只发一次）。

  /** 取当前目标的地图总览（M9 T2：与 map.js 同走 SimosApi.cachedMapOverview）。 */
  function loadOverview() {
    return api.cachedMapOverview(app.target());
  }

  function targetLabel() {
    var t = app.target();
    return t.branch + "@" + (t.revision === null || t.revision === undefined ? "head" : t.revision);
  }

  /**
   * 追加一行读数。`hint`（可选）= 该行的**口径说明**，写进 `title=`（B16：口径不进主栏，进 tooltip）。
   */
  function appendRow(detail, label, value, hint) {
    var dt = app.el("dt", { text: label });
    var dd = app.el("dd", { text: app.text(formatValue(value)) });
    if (hint) {
      dt.setAttribute("title", hint);
      dd.setAttribute("title", hint);
    }
    detail.appendChild(dt);
    detail.appendChild(dd);
  }

  /** 追加一行**由别的文件回填**的读数（带 id 锚点）：跨文件只经这一个 DOM 锚点，不互相持有状态。 */
  function appendLiveRow(detail, label, id, text) {
    detail.appendChild(app.el("dt", { text: label }));
    var dd = app.el("dd", { text: text });
    dd.setAttribute("id", id);
    detail.appendChild(dd);
  }

  /** 表单字段读值（缺节点 ⇒ ""，不抛）。 */
  function valueOf(id) {
    var node = app.byId(id);
    return node && node.value !== undefined ? node.value : "";
  }

  window.SimosPanelDom = {
    loadOverview: loadOverview,
    targetLabel: targetLabel,
    appendRow: appendRow,
    appendLiveRow: appendLiveRow,
    valueOf: valueOf,
  };
})();
