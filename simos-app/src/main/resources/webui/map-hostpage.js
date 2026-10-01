// map-hostpage.js —— 旧页 /map 的格详情 + 工作台浮层 chrome（M12 第五波：自 map.js 顶层整体搬出）。
//
// 两小簇，都是"页面外壳"：
//   · 旧页 /map（map.html）的格详情：oldPageSelect / loadHex / showHex
//   · 工作台（index.html）浮层控件：bindActive / resetToWorldCenter / wirePanelToggle / wireWorkbenchControls
// 原文行号见 map.js 未改动前的 3068-3235（含小节注释）。
//
// ★ 引入顺序：hexgeom → hexcolor → regionShape → map.js → renderer.js → map-uniteditor.js
//   → map-hostpage.js。必须在 map.js 之后（window.SimosMapCore 由 map.js 建立）。
// ★ 函数体与 map.js 原文逐字节相同，唯一例外是**可变绑定改实时读/写**（同 renderer.js 对
//   regionNamesEnabled 的处理）：
//     · active（6 处）⇒ core.active（实时 getter；取快照会永远是 null）
//     · regionNamesEnabled 读 ⇒ core.regionNamesEnabled；写 ⇒ core.setRegionNamesEnabled(v)
//   （map.js 仍是这两个可变绑定的所有者。）
// ★ map.js 的 initHost 对本文件经 window.SimosMapHostPage 惰性调用（本文件在 map.js 之后引入）。

(function () {
  "use strict";

  // ── 从 map.js 暴露的 window.SimosMapCore 取回（本文件在 map.js 之后引入）────────────
  var core = window.SimosMapCore;
  var app = core.app;
  var api = core.api;
  var persistRegionNamesEnabled = core.persistRegionNamesEnabled; // 定义仍在 map.js

  // ── 宿主页面外壳（旧页格详情 + 工作台浮层）[原文：map.js 3068-3235] ────────────────

  // ── 旧页 /map 的格详情（M5 T9 行为保留）────────────────────────────────

  function oldPageSelect(pick) {
    if (pick.kind === "unit") {
      core.active.setSelected({ q: pick.q, r: pick.r });
      loadHex(pick.q, pick.r, "单位 " + app.text(pick.id) + "：" + app.text(pick.name));
      return;
    }
    if (!pick.inMap) {
      app.statusMessage(
        app.byId("hex-status"),
        "该位置无格（q=" + pick.q + ", r=" + pick.r + "）",
        "warn"
      );
      return;
    }
    core.active.setSelected({ q: pick.q, r: pick.r });
    loadHex(pick.q, pick.r);
  }

  async function loadHex(q, r, statusLabel) {
    var status = app.byId("hex-status");
    if (!status) {
      return;
    }
    app.statusMessage(
      status,
      statusLabel || "查询 q=" + q + ", r=" + r + " …",
      statusLabel ? "ok" : "muted"
    );
    try {
      var body = await api.mapHex(q, r, app.target());
      showHex(body, statusLabel);
    } catch (e) {
      app.statusMessage(status, "查询失败：" + e.message, "err");
      app.clear(app.byId("hex-facets"));
    }
  }

  function showHex(body, statusLabel) {
    var detail = app.clear(app.byId("hex-detail"));
    var status = app.byId("hex-status");
    if (!detail || !status) {
      return;
    }
    app.statusMessage(status, statusLabel || "q=" + body.q + ", r=" + body.r, "ok");
    [
      ["q", body.q],
      ["r", body.r],
      ["terrain", body.terrain],
      ["height", body.height],
    ].forEach(function (pair) {
      detail.appendChild(app.el("dt", { text: pair[0] }));
      detail.appendChild(app.el("dd", { text: app.text(pair[1]) }));
    });

    var facetsNode = app.clear(app.byId("hex-facets"));
    if (!facetsNode) {
      return;
    }
    var facets = body.facets || [];
    if (!facets.length) {
      facetsNode.appendChild(app.el("p", { class: "empty", text: "该格无 facet。" }));
      return;
    }
    var table = app.el("table", null, [
      app.el("thead", null, [
        app.el("tr", null, [
          app.el("th", { text: "namespace" }),
          app.el("th", { text: "label" }),
          app.el("th", { text: "type" }),
          app.el("th", { text: "value" }),
        ]),
      ]),
      app.el(
        "tbody",
        null,
        facets.map(function (f) {
          return app.el("tr", null, [
            app.el("td", { text: app.text(f.namespace) }),
            app.el("td", { text: app.text(f.label) }),
            app.el("td", { text: app.text(f.typeName) }),
            app.el("td", { text: app.text(f.value) }),
          ]);
        })
      ),
    ]);
    facetsNode.appendChild(table);
  }

  // ── 启动 ──────────────────────────────────────────────────────────────

  function bindActive(renderer) {
    window.SimosMap.screenPointOf = renderer.screenPointOf;
    window.SimosMap.hexAtScreen = renderer.pickAt;
    window.SimosMap.unitPosition = renderer.positionOf;
    window.SimosMap.render = renderer.render;
    window.SimosMap.debug = renderer.debug;
    window.SimosMap.resetView = renderer.fit;
    // ★ 2026-09-24 可用性修复：选中/定位单位 ⇒ 居中并抬到可见缩放（照 resetView/setView 写法挂出）。
    window.SimosMap.ensureUnitVisible = renderer.ensureUnitVisible;
    // ★ F1：城市定位与图层装载 / 城市位置（搜索与 e2e 共用；旧页无图层抽屉也可直接调）。
    window.SimosMap.ensureCityVisible = renderer.ensureCityVisible;
    window.SimosMap.cityPositionOf = renderer.cityPositionOf;
    window.SimosMap.setCities = renderer.setCities;
    window.SimosMap.setLayerState = renderer.setLayerState;
    window.SimosMap.setNationRegions = renderer.setNationRegions;
    window.SimosMap.benchStages = renderer.benchStages;
    window.SimosMap.benchTerrainVariants = renderer.benchTerrainVariants;
    window.SimosMap.benchChunkSweep = renderer.benchChunkSweep;
    window.SimosMap.perfConfig = renderer.perfConfig;
    window.SimosMap.computeFit = renderer.computeFit;
    window.SimosMap.currentView = renderer.view;
    window.SimosMap.setView = renderer.setView;
    window.SimosMap.terrainColor = renderer.terrainColor;
    window.SimosMap.isReady = renderer.isReady;
  }

  /** "回到世界中心 / 适配视图"（M7g T1）：重新 fit 一次并立刻重画。 */
  function resetToWorldCenter() {
    if (!core.active) {
      return;
    }
    core.active.fit();
    core.active.render();
  }

  /** 折叠/展开一个浮层栏：按钮 aria-pressed + 文案随栏的 hidden 同步（可断言）。 */
  function wirePanelToggle(buttonId, panelId) {
    var button = app.byId(buttonId);
    var panel = app.byId(panelId);
    if (!button || !panel) {
      return;
    }
    var label = panelId === "left-panel" ? "左栏" : "右栏";
    var sync = function () {
      var collapsed = panel.hidden;
      button.setAttribute("aria-pressed", collapsed ? "true" : "false");
      button.textContent = (collapsed ? "展开" : "收起") + label;
    };
    button.addEventListener("click", function () {
      panel.hidden = !panel.hidden;
      sync();
    });
    sync();
  }

  /** 工作台浮层控件接线（M7g T1）：回中心按钮 + 左右栏折叠 + Home 快捷键。 */
  function wireWorkbenchControls() {
    var reset = app.byId("view-reset");
    if (reset) {
      reset.addEventListener("click", resetToWorldCenter);
    }
    wirePanelToggle("panel-toggle-left", "left-panel");
    wirePanelToggle("panel-toggle-right", "right-panel");
    // ★ U2：区域名开关（默认开；本机记忆）——切换后立刻重画。
    var nameToggle = app.byId("region-name-toggle");
    if (nameToggle) {
      nameToggle.checked = core.regionNamesEnabled;
      nameToggle.addEventListener("change", function () {
        core.setRegionNamesEnabled(!!nameToggle.checked);
        persistRegionNamesEnabled(core.regionNamesEnabled);
        core.active.render();
      });
    }
    window.addEventListener("keydown", function (event) {
      var tag = event.target && event.target.tagName ? event.target.tagName : "";
      if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") {
        return;
      }
      if (event.key === "Home") {
        event.preventDefault();
        resetToWorldCenter();
      }
    });
  }

  window.SimosMapHostPage = {
    oldPageSelect: oldPageSelect,
    loadHex: loadHex,
    showHex: showHex,
    bindActive: bindActive,
    resetToWorldCenter: resetToWorldCenter,
    wirePanelToggle: wirePanelToggle,
    wireWorkbenchControls: wireWorkbenchControls,
  };
})();
