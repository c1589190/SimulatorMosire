// social.js —— /social 页专用：按格人口查询 + 简表（M5 T9，spec §8.3）。
// ★ 只读：仅有 /api/social/population 一条调用。

(function () {
  "use strict";

  var app = window.SimosApp;
  var api = window.SimosApi;

  var history = [];

  async function query() {
    var status = app.byId("query-status");
    var q = Number(app.byId("q").value);
    var r = Number(app.byId("r").value);
    if (!Number.isFinite(q) || !Number.isFinite(r)) {
      app.statusMessage(status, "q/r 必须是整数", "err");
      return;
    }
    app.statusMessage(status, "查询 q=" + q + ", r=" + r + " …", "muted");
    try {
      var body = await api.population(q, r);
      show(body);
      app.statusMessage(status, "q=" + q + ", r=" + r + " 有数据", "ok");
    } catch (e) {
      app.statusMessage(status, "查询失败：" + e.message, "err");
      app.clear(app.byId("result"));
    }
  }

  function show(body) {
    var result = app.clear(app.byId("result"));
    [
      ["q", body.q],
      ["r", body.r],
      ["population", body.population],
      ["at.tick", body.at ? body.at.tick : null],
      ["at.calendarLabel", body.at ? body.at.calendarLabel : null],
    ].forEach(function (pair) {
      result.appendChild(app.el("dt", { text: pair[0] }));
      result.appendChild(app.el("dd", { text: app.text(pair[1]) }));
    });

    history.unshift({
      q: body.q,
      r: body.r,
      population: body.population,
      tick: body.at ? body.at.tick : null,
    });
    history = history.slice(0, 20);
    renderHistory();
  }

  function renderHistory() {
    var container = app.clear(app.byId("history"));
    if (!history.length) {
      container.appendChild(app.el("p", { class: "empty", text: "尚无查询。" }));
      return;
    }
    container.appendChild(
      app.el("table", null, [
        app.el("thead", null, [
          app.el("tr", null, [
            app.el("th", { text: "q" }),
            app.el("th", { text: "r" }),
            app.el("th", { text: "tick" }),
            app.el("th", { text: "population" }),
          ]),
        ]),
        app.el(
          "tbody",
          null,
          history.map(function (item) {
            return app.el("tr", null, [
              app.el("td", { text: app.text(item.q) }),
              app.el("td", { text: app.text(item.r) }),
              app.el("td", { text: app.text(item.tick) }),
              app.el("td", { text: app.text(item.population) }),
            ]);
          })
        ),
      ])
    );
  }

  function init() {
    app.boot({ title: "社会" });
    app.byId("query").addEventListener("click", query);
    ["q", "r"].forEach(function (id) {
      app.byId(id).addEventListener("keydown", function (event) {
        if (event.key === "Enter") {
          query();
        }
      });
    });
    renderHistory();
  }

  document.addEventListener("DOMContentLoaded", init);
})();
