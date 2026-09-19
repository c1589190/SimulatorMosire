// panels.js —— 左栏详情（M7 T2 骨架 → T4 接真读数 → T5 补全判据③：hex 加"人口"、单位补全字段）/ 右栏区域面板（占位，分组列表归 T6）。
// ★ 无框架、无构建：原生 DOM；所有只读取数经 window.SimosApi 并带 window.SimosApp.target()
//   （T3 约定在 T4 由本文件收口：面板值随游标的 {branch, revision} 变化）。
// ★ 本文件不含任何写调用。

(function () {
  "use strict";

  var app = window.SimosApp;
  var api = window.SimosApi;

  var requestToken = 0;
  var lastKey = null;

  function targetLabel() {
    var t = app.target();
    return t.branch + "@" + (t.revision === null || t.revision === undefined ? "head" : t.revision);
  }

  function appendRow(detail, label, value) {
    detail.appendChild(app.el("dt", { text: label }));
    detail.appendChild(app.el("dd", { text: app.text(formatValue(value)) }));
  }

  /** 只读读数里的浮点数去掉二进制尾巴（0.6000000000000001 → 0.6）；整数/文本原样。 */
  function formatValue(value) {
    if (typeof value === "number" && Number.isFinite(value) && !Number.isInteger(value)) {
      return Number(value.toFixed(3));
    }
    return value;
  }

  /** 装备表渲染成 `键=值；…`（空表显示"（空）"）。 */
  function equipmentText(equipment) {
    if (!equipment) {
      return "（空）";
    }
    var keys = Object.keys(equipment);
    if (!keys.length) {
      return "（空）";
    }
    return keys
      .map(function (key) {
        return key + "=" + equipment[key];
      })
      .join("；");
  }

  function hexLabel(coord) {
    return "q=" + coord.q + ", r=" + coord.r;
  }

  function renderHex(selection, token) {
    var status = app.byId("left-status");
    var detail = app.clear(app.byId("selection-detail"));
    app.statusMessage(status, "查询 " + hexLabel(selection) + "（" + targetLabel() + "）…", "muted");
    // 人口序列可能不存在（/api/social/population 404）⇒ 折成 null，不让整条详情失败（判据③"人口"）。
    Promise.all([
      api.mapHex(selection.q, selection.r, app.target()),
      api.units(app.target()),
      api.population(selection.q, selection.r, app.target()).catch(function () {
        return null;
      }),
    ])
      .then(function (results) {
        if (token !== requestToken) {
          return;
        }
        var hex = results[0];
        var unitsHere = (results[1].units || []).filter(function (u) {
          return u.position && u.position.q === selection.q && u.position.r === selection.r;
        });
        var population = results[2];
        var terrainText = hex.terrain;
        if (hex.terrainType && hex.terrainType.name) {
          terrainText = hex.terrain + "（" + hex.terrainType.name + "）";
        }
        appendRow(detail, "q", hex.q);
        appendRow(detail, "r", hex.r);
        appendRow(detail, "terrain", terrainText);
        appendRow(detail, "height", hex.height);
        appendRow(detail, "region", hex.region === null || hex.region === undefined ? "—" : hex.region);
        appendRow(
          detail,
          "该处单位",
          unitsHere.length
            ? unitsHere
                .map(function (u) {
                  return u.id + " " + app.text(u.name);
                })
                .join("；")
            : "无"
        );
        appendRow(
          detail,
          "人口",
          population && population.population !== null && population.population !== undefined
            ? population.population
            : "无序列"
        );
        app.statusMessage(status, hexLabel(hex) + " · " + targetLabel(), "ok");
      })
      .catch(function (e) {
        if (token !== requestToken) {
          return;
        }
        app.statusMessage(status, "查询失败：" + e.message, "err");
      });
  }

  function renderUnit(selection, token) {
    var status = app.byId("left-status");
    var detail = app.clear(app.byId("selection-detail"));
    app.statusMessage(status, "查询单位 " + selection.id + "（" + targetLabel() + "）…", "muted");
    api
      .unit(selection.id, app.target())
      .then(function (unit) {
        if (token !== requestToken) {
          return;
        }
        appendRow(detail, "id", unit.id);
        appendRow(detail, "name", unit.name);
        appendRow(detail, "parent", unit.parent === null || unit.parent === undefined ? "—" : unit.parent);
        appendRow(detail, "position", unit.position ? hexLabel(unit.position) : "—");
        appendRow(detail, "member", unit.member);
        appendRow(detail, "equipment", equipmentText(unit.equipment));
        appendRow(detail, "speed", unit.speed);
        appendRow(detail, "mobilityPerMille", unit.mobilityPerMille);
        appendRow(detail, "movement", unit.movement ? "true" : "false");
        app.statusMessage(status, "单位 " + unit.id + " · " + targetLabel(), "ok");
      })
      .catch(function (e) {
        if (token !== requestToken) {
          return;
        }
        app.statusMessage(status, "查询失败：" + e.message, "err");
      });
  }

  /** 左栏渲染；选择或目标坐标变化时才重取（模式/高亮变化不触发重取）。 */
  function renderLeft(state) {
    var status = app.byId("left-status");
    var detail = app.byId("selection-detail");
    if (!status || !detail) {
      return;
    }
    var selection = state ? state.selection : null;
    var key = JSON.stringify([selection, app.target()]);
    if (key === lastKey) {
      return;
    }
    lastKey = key;
    var token = ++requestToken;
    if (!selection) {
      app.clear(detail);
      app.statusMessage(status, "点选地图或单位以查看详情。", "muted");
      return;
    }
    if (selection.kind === "hex") {
      renderHex(selection, token);
    } else if (selection.kind === "unit") {
      renderUnit(selection, token);
    } else {
      app.clear(detail);
      app.statusMessage(status, "未知选择类型：" + app.text(selection.kind), "warn");
    }
  }

  function renderRight() {
    var mount = app.byId("region-panel-mount");
    if (!mount) {
      return;
    }
    app.clear(mount);
    mount.appendChild(app.el("p", { class: "empty", text: "区域分组与列表在 T6 接入。" }));
  }

  /** 工作台初始化：订阅状态并渲染左栏真读数；右栏仍是 T6 占位。 */
  function init() {
    renderRight();
    renderLeft(app.getState());
    app.onStateChange(function (state) {
      renderLeft(state);
    });
  }

  window.SimosPanels = {
    init: init,
    renderLeft: renderLeft,
    renderRight: renderRight,
  };
})();
