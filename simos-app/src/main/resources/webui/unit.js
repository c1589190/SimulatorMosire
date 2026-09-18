// unit.js —— /unit 页专用：列表 + 详情 + 8 条命令表单（M5 T9，spec §8.3/§四）。
// ★ 命令一律经 /api/command 提交；表单只负责把字段装配成 payloadJson 文本。
// ★ payload 字段名逐条对齐 spec §四（unit 命令面补齐表）。

(function () {
  "use strict";

  var app = window.SimosApp;
  var api = window.SimosApi;

  // 8 条命令的字段定义。kind 决定如何装配进 payload。
  //   text  : 纯字符串
  //   int   : 整数
  //   map   : "k=v,k=v" → {k:"v"}（equipment）
  //   coord : "q,r" → {q,r}；空 → null
  //   coords: "q,r;q,r" → [{q,r}...]
  var COMMANDS = [
    {
      type: "unit.CreateUnit",
      help: "新建单位（id 已存在则拒绝）。",
      fields: [
        { name: "id", label: "id", kind: "text", required: true },
        { name: "name", label: "name", kind: "text", required: true },
        { name: "position", label: "position（q,r）", kind: "coord", required: true },
        { name: "member", label: "member", kind: "int", required: true },
        { name: "equipment", label: "equipment（k=v,k=v）", kind: "map" },
        { name: "speed", label: "speed", kind: "int", required: true },
        { name: "mobilityPerMille", label: "mobilityPerMille", kind: "int", required: true },
        { name: "parent", label: "parent（可空）", kind: "text" },
      ],
    },
    {
      type: "unit.ReparentUnit",
      help: "改父（parent 空 = 清根）。",
      fields: [
        { name: "id", label: "id", kind: "text", required: true },
        { name: "parent", label: "parent（空 = 清根）", kind: "text" },
      ],
    },
    {
      type: "unit.SetStrength",
      help: "改兵力与装备。",
      fields: [
        { name: "id", label: "id", kind: "text", required: true },
        { name: "member", label: "member", kind: "int", required: true },
        { name: "equipment", label: "equipment（k=v,k=v）", kind: "map" },
      ],
    },
    {
      type: "unit.PlaceAt",
      help: "直接放置（hex 空 = 撤销位置）。",
      fields: [
        { name: "id", label: "id", kind: "text", required: true },
        { name: "hex", label: "hex（q,r；空 = 撤销位置）", kind: "coord" },
      ],
    },
    {
      type: "unit.PlanRoute",
      help: "规划路线（waypoints 形如 q,r;q,r）。",
      fields: [
        { name: "id", label: "id", kind: "text", required: true },
        { name: "waypoints", label: "waypoints（q,r;q,r）", kind: "coords", required: true },
      ],
    },
    {
      type: "unit.CancelRoute",
      help: "取消路线。",
      fields: [{ name: "id", label: "id", kind: "text", required: true }],
    },
    {
      type: "unit.DisbandUnit",
      help: "解散单位。",
      fields: [{ name: "id", label: "id", kind: "text", required: true }],
    },
    {
      type: "unit.RenameUnit",
      help: "改名。",
      fields: [
        { name: "id", label: "id", kind: "text", required: true },
        { name: "name", label: "name", kind: "text", required: true },
      ],
    },
  ];

  var byType = {};
  COMMANDS.forEach(function (c) {
    byType[c.type] = c;
  });

  var currentType = COMMANDS[0].type;
  var currentUnitId = null;

  // ── 字段装配 ─────────────────────────────────────────────────────

  function parseCoord(raw) {
    if (raw === null || raw === "") {
      return null;
    }
    var parts = String(raw).split(",");
    if (parts.length !== 2) {
      throw new Error("坐标格式应为 q,r：" + raw);
    }
    var q = Number(parts[0].trim());
    var r = Number(parts[1].trim());
    if (!Number.isFinite(q) || !Number.isFinite(r)) {
      throw new Error("坐标必须是整数：" + raw);
    }
    return { q: q, r: r };
  }

  function parseCoords(raw) {
    if (raw === null || raw === "") {
      return [];
    }
    return String(raw)
      .split(";")
      .map(function (piece) {
        return piece.trim();
      })
      .filter(function (piece) {
        return piece.length > 0;
      })
      .map(parseCoord);
  }

  function parseMap(raw) {
    var out = {};
    if (raw === null || raw === "") {
      return out;
    }
    String(raw)
      .split(",")
      .forEach(function (pair) {
        var trimmed = pair.trim();
        if (trimmed === "") {
          return;
        }
        var eq = trimmed.indexOf("=");
        if (eq < 0) {
          throw new Error("装备格式应为 k=v：" + trimmed);
        }
        var key = trimmed.slice(0, eq).trim();
        var value = trimmed.slice(eq + 1).trim();
        var n = Number(value);
        out[key] = value !== "" && Number.isFinite(n) ? n : value;
      });
    return out;
  }

  /** 由表单字段构造 payload 对象。 */
  function buildPayload(type) {
    var def = byType[type];
    var form = app.byId("command-fields");
    var payload = {};
    def.fields.forEach(function (field) {
      var raw = app.fieldValue(form, field.name);
      if (raw === null || raw === undefined) {
        if (field.required) {
          throw new Error("字段 " + field.name + " 必填");
        }
        if (field.kind === "text" || field.kind === "int") {
          payload[field.name] = null;
        }
        return;
      }
      if (field.kind === "int") {
        var n = Number(raw);
        if (!Number.isFinite(n)) {
          throw new Error("字段 " + field.name + " 必须是整数");
        }
        payload[field.name] = n;
      } else if (field.kind === "map") {
        payload[field.name] = parseMap(raw);
      } else if (field.kind === "coord") {
        payload[field.name] = parseCoord(raw);
      } else if (field.kind === "coords") {
        payload[field.name] = parseCoords(raw);
      } else {
        payload[field.name] = raw;
      }
    });
    return payload;
  }

  function refreshEnvelope() {
    var envelope;
    try {
      var payload = buildPayload(currentType);
      envelope = {
        type: currentType,
        payloadJson: JSON.stringify(payload),
        branch: app.byId("branch").value || "main",
        expectedRevision: Number(app.byId("expected-revision").value),
      };
    } catch (e) {
      envelope = { error: e.message };
    }
    app.byId("envelope-json").value = app.json(envelope);
  }

  // ── 命令表单渲染 ─────────────────────────────────────────────────

  function renderFields() {
    var def = byType[currentType];
    var container = app.clear(app.byId("command-fields"));
    def.fields.forEach(function (field) {
      var input = app.el("input", {
        name: field.name,
        type: field.kind === "int" ? "number" : "text",
        placeholder: field.kind === "coords" ? "示例：1,1;1,2" : field.kind === "coord" ? "示例：1,1" : "",
      });
      container.appendChild(app.el("label", { text: field.label + (field.required ? " *" : "") }, [input]));
      input.addEventListener("input", refreshEnvelope);
    });
    container.appendChild(app.el("p", { class: "muted", text: def.help }));
    refreshEnvelope();
  }

  function mountCommandTypes() {
    var select = app.byId("command-type");
    app.clear(select);
    COMMANDS.forEach(function (c) {
      select.appendChild(app.el("option", { value: c.type, text: c.type }));
    });
    select.value = currentType;
    select.addEventListener("change", function (event) {
      currentType = event.target.value;
      renderFields();
    });
  }

  // ── 列表与详情 ───────────────────────────────────────────────────

  function loadUnits() {
    var status = app.byId("units-status");
    app.statusMessage(status, "载入单位列表…", "muted");
    api
      .units()
      .then(function (body) {
        var units = body.units || [];
        var container = app.clear(app.byId("units-table"));
        if (!units.length) {
          container.appendChild(app.el("p", { class: "empty", text: "无单位。" }));
          app.statusMessage(status, "0 个单位。", "ok");
          return;
        }
        var table = app.el("table", null, [
          app.el("thead", null, [
            app.el("tr", null, [
              app.el("th", { text: "id" }),
              app.el("th", { text: "name" }),
              app.el("th", { text: "member" }),
              app.el("th", { text: "position" }),
              app.el("th", { text: "movement" }),
            ]),
          ]),
          app.el(
            "tbody",
            null,
            units.map(function (u) {
              var row = app.el("tr", { "data-id": u.id }, [
                app.el("td", { text: app.text(u.id) }),
                app.el("td", { text: app.text(u.name) }),
                app.el("td", { text: app.text(u.member) }),
                app.el("td", {
                  text: u.position ? u.position.q + "," + u.position.r : "—",
                }),
                app.el("td", { text: u.movement ? "有" : "无" }),
              ]);
              row.addEventListener("click", function () {
                selectUnit(u.id, row);
              });
              return row;
            })
          ),
        ]);
        container.appendChild(table);
        app.statusMessage(status, units.length + " 个单位。", "ok");
      })
      .catch(function (e) {
        app.statusMessage(status, "载入失败：" + e.message, "err");
      });
  }

  function selectUnit(id, row) {
    currentUnitId = id;
    Array.prototype.forEach.call(
      app.byId("units-table").querySelectorAll("tr"),
      function (tr) {
        tr.classList.remove("selected");
      }
    );
    if (row) {
      row.classList.add("selected");
    }
    fillIdFields(id);
    loadDetail(id);
    refreshEnvelope();
  }

  /** 把 id 回填到当前命令表单的 id 字段（若该命令有）。 */
  function fillIdFields(id) {
    var form = app.byId("command-fields");
    var idInput = form.querySelector('[name="id"]');
    if (idInput && !idInput.value) {
      idInput.value = id;
    }
  }

  function loadDetail(id) {
    var status = app.byId("detail-status");
    app.statusMessage(status, "载入 " + id + " …", "muted");
    api
      .unit(id)
      .then(function (body) {
        app.statusMessage(status, "详情：" + body.id, "ok");
        var detail = app.clear(app.byId("detail"));
        [
          ["id", body.id],
          ["name", body.name],
          ["member", body.member],
          ["equipment", JSON.stringify(body.equipment)],
          ["speed", body.speed],
          ["mobilityPerMille", body.mobilityPerMille],
          ["parent", body.parent],
          ["position", body.position ? body.position.q + "," + body.position.r : null],
          ["movement", body.movement ? "有" : "无"],
        ].forEach(function (pair) {
          detail.appendChild(app.el("dt", { text: pair[0] }));
          detail.appendChild(app.el("dd", { text: app.text(pair[1]) }));
        });
      })
      .catch(function (e) {
        app.statusMessage(status, "载入失败：" + e.message, "err");
      });
  }

  // ── 提交 ─────────────────────────────────────────────────────────

  function submit() {
    var status = app.byId("command-status");
    var envelope;
    try {
      envelope = JSON.parse(app.byId("envelope-json").value);
    } catch (e) {
      app.statusMessage(status, "信封 JSON 不合法：" + e.message, "err");
      return;
    }
    if (envelope.error) {
      app.statusMessage(status, "字段装配失败：" + envelope.error, "err");
      return;
    }
    app.statusMessage(status, "提交 " + envelope.type + " …", "muted");
    api
      .submitCommand(envelope)
      .then(function (body) {
        showResult(status, body);
      })
      .catch(function (e) {
        showResult(status, e.body || { result: "rejected", reason: e.message }, e.status);
      });
  }

  function showResult(status, body, httpStatus) {
    var result = body.result;
    if (result === "committed") {
      app.statusMessage(
        status,
        "committed → " + body.ref.branch + "@" + body.ref.revision,
        "ok"
      );
      if (body.ref) {
        app.byId("expected-revision").value = body.ref.revision + 1;
      }
      loadUnits();
    } else if (result === "conflict") {
      app.statusMessage(
        status,
        "conflict（revision 过期）→ 当前 " +
          body.current.branch +
          "@" +
          body.current.revision +
          "；expectedRevision 已更新，可重试",
        "warn"
      );
      if (body.current) {
        app.byId("expected-revision").value = body.current.revision;
      }
    } else {
      app.statusMessage(
        status,
        "rejected" + (httpStatus ? "（HTTP " + httpStatus + "）" : "") + "：" + app.text(body.reason),
        "err"
      );
    }
  }

  function init() {
    app.boot({ title: "单位" });
    mountCommandTypes();
    renderFields();
    app.byId("reload-units").addEventListener("click", loadUnits);
    app.byId("submit-command").addEventListener("click", submit);
    ["branch", "expected-revision"].forEach(function (id) {
      app.byId(id).addEventListener("input", refreshEnvelope);
    });
    loadUnits();
  }

  document.addEventListener("DOMContentLoaded", init);
})();
