// panel-right.js —— 右栏区域面板（M7 T6：按 RegionMeta.tag 分组）。（M12 第四波：自 panels.js 整体搬出）
// ★ 无框架、无构建：原生 DOM；所有只读取数经 window.SimosApi 并带 window.SimosApp.target()
//   （面板值随游标的 {branch, revision} 变化）。
// ★ 本文件不含任何写调用（唯一的世界外动作是 `app.setHighlightRegions` / `app.setRegionFocus` 这类选择态写）。
// ★ 右栏 = 区域查看模式（`data-modes="region"` 控制可见性）；点区域 ⇒ `setHighlightRegions([id])` 只高亮该区
//   + 出详情；点标签 ⇒ 该标签下全部区域一起高亮。
// ★ 模块级状态（rightToken / rightKey / rightRegions / selectedRegion）随三个渲染函数一起搬来 ⇒ **独占**，
//   只被本文件的 renderRight / drawRight / regionDetail 读写。
// ★ 加载顺序：paneldom.js → panel-right.js → panels.js（本文件只依赖 paneldom + app/api/readout）。

(function () {
  "use strict";

  var app = window.SimosApp;
  var groupByTag = window.SimosReadout.groupByTag;
  var loadOverview = window.SimosPanelDom.loadOverview;
  var targetLabel = window.SimosPanelDom.targetLabel;
  var appendRow = window.SimosPanelDom.appendRow;

  // ── 右栏区域分组（M7 T6）────────────────────────────────────────────
  var rightToken = 0;
  var rightKey = null;
  var rightRegions = null;
  var selectedRegion = null;

  function regionDetail(container) {
    var dl = app.el("dl", { class: "kv region-detail", id: "region-detail" });
    if (!selectedRegion) {
      appendRow(dl, "提示", "点区域看详情；点标签高亮该标签下全部区域。");
      container.appendChild(dl);
      return;
    }
    var meta = selectedRegion.meta || {};
    appendRow(dl, "name", selectedRegion.name || selectedRegion.id);
    appendRow(dl, "id", selectedRegion.id);
    appendRow(dl, "hexCount", selectedRegion.hexCount);
    appendRow(dl, "color", meta.color);
    appendRow(dl, "tag", meta.tag);
    appendRow(dl, "description", meta.description);
    appendRow(dl, "annexedBy", meta.annexedBy);
    container.appendChild(dl);
  }

  /** 用缓存的 regions + 当前 state 重画右栏（分组、高亮选中态、详情）。 */
  function drawRight(state) {
    var mount = app.byId("region-panel-mount");
    if (!mount) {
      return;
    }
    app.clear(mount);
    var groups = groupByTag(rightRegions || []);
    if (!groups.length) {
      mount.appendChild(app.el("p", { class: "empty", text: "该快照无区域。" }));
      return;
    }
    var highlighted = state.highlightRegions || [];
    var highlightSet = {};
    highlighted.forEach(function (id) {
      highlightSet[id] = true;
    });
    // ★ M8 T10：区域编辑模式下"当前目标区域"（focus）也算选中态（它由 map.js 渲染正常色/其它淡色）。
    var regionFocus = state.regionFocus || null;
    groups.forEach(function (group) {
      var ids = group.regions.map(function (region) {
        return region.id;
      });
      var allActive =
        ids.length > 0 &&
        highlighted.length === ids.length &&
        ids.every(function (id) {
          return highlightSet[id];
        });
      var tagButton = app.el("button", {
        type: "button",
        class: "region-tag" + (allActive ? " active" : ""),
        "data-tag": group.tag,
        title: "高亮「" + group.tag + "」下全部 " + ids.length + " 个区域",
      });
      tagButton.appendChild(app.el("span", { class: "region-tag-name", text: group.tag }));
      tagButton.appendChild(app.el("span", { class: "region-tag-count", text: String(ids.length) }));
      tagButton.addEventListener("click", function () {
        selectedRegion = null;
        // ★ U3：点 tag ⇒ 该 tag 下所有区域**等亮度**（group）。
        app.setHighlightRegions(ids.slice(), "group");
      });
      mount.appendChild(tagButton);

      var list = app.el("div", { class: "region-list" });
      group.regions.forEach(function (region) {
        var isSelected = !!highlightSet[region.id] || region.id === regionFocus;
        var item = app.el("button", {
          type: "button",
          class: "region-item" + (isSelected ? " selected" : ""),
          "data-region-id": region.id,
        });
        item.appendChild(
          app.el("span", { class: "region-name", text: app.text(region.name || region.id) })
        );
        item.appendChild(
          app.el("span", { class: "region-hexcount", text: app.text(region.hexCount) + " 格" })
        );
        item.addEventListener("click", function () {
          selectedRegion = region;
          if (app.getState().mode === "region-edit") {
            app.setRegionFocus(region.id);
          } else {
            // ★ U3：点单个区域 ⇒ 该区域更亮、同 tag 其他区域淡色（single）。
            app.setHighlightRegions([region.id], "single");
          }
        });
        list.appendChild(item);
      });
      mount.appendChild(list);
    });
    regionDetail(mount);
  }

  /** 右栏渲染：目标 {branch,revision} 变化才重取 overview；高亮/选择变化只重画（不重取）。 */
  function renderRight(state) {
    var mount = app.byId("region-panel-mount");
    if (!mount) {
      return;
    }
    state = state || app.getState();
    var key = targetLabel();
    if (key === rightKey) {
      if (rightRegions !== null) {
        drawRight(state);
      }
      return;
    }
    rightKey = key;
    var token = ++rightToken;
    app.clear(mount);
    mount.appendChild(app.el("p", { class: "empty", text: "载入区域…（" + key + "）" }));
    loadOverview()
      .then(function (body) {
        if (token !== rightToken) {
          return;
        }
        rightRegions = body.regions || [];
        drawRight(state);
      })
      .catch(function (e) {
        if (token !== rightToken) {
          return;
        }
        rightRegions = null;
        app.clear(mount);
        mount.appendChild(app.el("p", { class: "empty", text: "区域载入失败：" + e.message }));
      });
  }

  window.SimosPanelRight = {
    renderRight: renderRight,
  };
})();
