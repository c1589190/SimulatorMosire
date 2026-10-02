// events.js —— 地图事件浮层（2026-10-02 用户：点 ⚔ 看该 hex 当前 tick 战斗详情，并可切到当前 tick 全部）。
//
// 设计：**provider 注册表**收口"事件"的取数 / 归一 / 详情渲染；战斗只是第一个 provider：
//   registerProvider({id,label,icon,load(target),normalize(raw),renderDetail(event,container)})
//   —— 后续状态事件（饥荒 / 叛乱 / 灾害…）按同一形状接入，面板本身不 switch "战斗"。
//
// 数据链：api.cachedArmyCombats(target)（GET /api/army/combats，服务端缺省 = 世界当前 tick）
//   → load → normalizeCombatEvent → filterEventsForHex → render；全程只用 app.el / textContent，不写 HTML 字符串。
//
// ★ DOM 节点缺席（旧页 / 未挂面板 / 测试壳）一律静默跳过，不抛；测试壳的 document.addEventListener
//   是 no-op，故测试会手动调 init()（脚本在 body 末尾时由 DOMContentLoaded 自初始化）。

(function () {
  "use strict";

  var app = window.SimosApp;
  var api = window.SimosApi;

  var providers = [];
  var scopeHex = null; // null = 当前 tick 全部；{q,r} = 只看该格
  var token = 0; // 防旧响应覆盖新请求
  var lastEvents = [];
  var lastErrors = [];
  var initialized = false;
  var lastTargetKey = "";

  // ── 通用小工具（对 DOM / app 缺席保持宽容）──────────────────────────────

  function domById(id) {
    return app && typeof app.byId === "function" ? app.byId(id) : null;
  }

  function errorMessage(err) {
    if (err === null || err === undefined) return "未知错误";
    if (typeof err.message === "string" && err.message) return err.message;
    return String(err);
  }

  function valueText(value) {
    return value === null || value === undefined ? "—" : String(value);
  }

  /** 单位 id 列表 → 中文顿号连接；空 / 全空 ⇒ "—"。 */
  function idListText(list) {
    if (!Array.isArray(list)) return "—";
    var parts = [];
    for (var i = 0; i < list.length; i++) {
      if (list[i] === null || list[i] === undefined) continue;
      parts.push(String(list[i]));
    }
    return parts.length ? parts.join("、") : "—";
  }

  function clearNode(node) {
    if (!node) return;
    if (app && typeof app.clear === "function") {
      app.clear(node);
      return;
    }
    while (node.firstChild) {
      node.removeChild(node.firstChild);
    }
  }

  // ── 纯函数（导出；无 DOM、无闭包状态，node 门禁可直接断言）────────────────

  /**
   * Army 交战记录（ApiViews.armyCombat 的形状）→ 面板统一事件形状。
   * 缺 id 或 hex 不完整 ⇒ null（不编坐标 / 身份，与 map.js armyCombatForRenderer 同纪律）。
   */
  function normalizeCombatEvent(record) {
    if (!record || record.id === null || record.id === undefined) return null;
    if (
      !record.hex ||
      record.hex.q === null ||
      record.hex.q === undefined ||
      record.hex.r === null ||
      record.hex.r === undefined
    ) {
      return null;
    }
    var id = String(record.id);
    return {
      type: "combat",
      id: id,
      tick: record.tick,
      hex: { q: record.hex.q, r: record.hex.r },
      title: "战斗 " + id,
      summary: record.text || "",
      raw: record,
    };
  }

  /** hex 为 null/undefined ⇒ 返回全部（拷贝）；否则按 e.hex 数值相等过滤。 */
  function filterEventsForHex(events, hex) {
    var list = Array.isArray(events) ? events : [];
    if (hex === null || hex === undefined) return list.slice();
    var q = Number(hex.q);
    var r = Number(hex.r);
    return list.filter(function (event) {
      if (!event || !event.hex) return false;
      return Number(event.hex.q) === q && Number(event.hex.r) === r;
    });
  }

  /** [{type,amount}]（amount 是**有符号**增量）⇒ "类型+1；类型-2"；空 ⇒ "—"。 */
  function formatDelta(entries) {
    var list = Array.isArray(entries) ? entries : [];
    var parts = [];
    for (var i = 0; i < list.length; i++) {
      var entry = list[i];
      if (!entry) continue;
      var amount = Number(entry.amount);
      var amountText = isFinite(amount) ? (amount >= 0 ? "+" : "") + amount : valueText(entry.amount);
      parts.push(valueText(entry.type) + amountText);
    }
    return parts.length ? parts.join("；") : "—";
  }

  /**
   * 结局损失列表 → 一行文本：`"unit：步兵-10 / 弓-2；unit2：— / —"`。
   * losses 为 [{unit,manpower,equipment}]；空/非数组 ⇒ "—"；单个 loss 的 unit 缺失 ⇒ "—"。
   * manpower/equipment 复用 formatDelta（有符号增量）。
   */
  function formatOutcomeLosses(losses) {
    var list = Array.isArray(losses) ? losses : [];
    var parts = [];
    for (var i = 0; i < list.length; i++) {
      var loss = list[i];
      if (!loss) continue;
      parts.push(
        valueText(loss.unit) +
          "：" +
          formatDelta(loss.manpower) +
          " / " +
          formatDelta(loss.equipment)
      );
    }
    return parts.length ? parts.join("；") : "—";
  }

  /** valueText 的"空串也算空"版本：null/undefined/"" ⇒ "—"（纯函数）。 */
  function orDash(value) {
    var text = valueText(value);
    return text === "" ? "—" : text;
  }

  /** 取第一个非 null/undefined/"" 的值；全空 ⇒ undefined（0 / false 是有效值）。 */
  function firstFilled(values) {
    for (var i = 0; i < values.length; i++) {
      var value = values[i];
      if (value !== null && value !== undefined && value !== "") return value;
    }
    return undefined;
  }

  /** 事件与 raw 里任一完整 hex ⇒ 该 hex；都缺 q/r ⇒ null（不编坐标）。 */
  function pickHex(source, raw) {
    var candidates = [source.hex, raw.hex];
    for (var i = 0; i < candidates.length; i++) {
      var hex = candidates[i];
      if (
        hex &&
        typeof hex === "object" &&
        hex.q !== null &&
        hex.q !== undefined &&
        hex.r !== null &&
        hex.r !== undefined
      ) {
        return hex;
      }
    }
    return null;
  }

  /**
   * 阶段 → 纯文本块（combatEventText 内部用；纯函数）：
   *   —— 阶段 名（已判定/未判定）
   *   participants：…
   *   过程：…
   *   结局：
   *     ✓ label（weight w）损失：…
   *   rollSeed：…
   * 选中结局 ✓、未选中 ·；无 outcomes ⇒ 占位行 "  —"。
   */
  function stageText(stage) {
    var value = stage || {};
    var outcomes = Array.isArray(value.outcomes) ? value.outcomes : [];
    var lines = [
      "—— 阶段 " + orDash(value.name) + "（" + (value.resolved ? "已判定" : "未判定") + "）",
      "participants：" + idListText(value.participants),
      "过程：" + orDash(value.text),
      "结局：",
    ];
    if (outcomes.length) {
      for (var i = 0; i < outcomes.length; i++) {
        var outcome = outcomes[i] || {};
        var selected =
          outcome.id === value.selectedOutcomeId || outcome.id === value.selectedOutcome;
        lines.push(
          "  " +
            (selected ? "✓" : "·") +
            " " +
            orDash(outcome.label) +
            "（weight " +
            orDash(outcome.weight) +
            "）损失：" +
            formatOutcomeLosses(outcome.losses)
        );
      }
    } else {
      lines.push("  —");
    }
    lines.push("rollSeed：" + orDash(value.rollSeed));
    return lines.join("\n");
  }

  /**
   * 一条事件 → 纯文本战况（发推演群用）。形状同 normalizeCombatEvent：
   * {type,id,tick,hex,title,summary,raw}；raw 为 Army 交战记录（kind/participants/text/stages/losses…）。
   * 字段顺序固定：标题(id) / kind / tick / hex / participants / 过程 / 阶段 / 结局 / 损失。
   * 空值一律 "—"；无阶段 ⇒ 一行 "—— 阶段：—"；无损失 ⇒ 损失块一行 "—"。
   * 纯函数：不碰 DOM、不读闭包状态（只复用 valueText/idListText/formatDelta/formatOutcomeLosses）。
   */
  function combatEventText(event) {
    var source = event || {};
    var raw = source.raw && typeof source.raw === "object" ? source.raw : {};
    var title = firstFilled([source.title]);
    var id = firstFilled([source.id, raw.id]);
    var hex = pickHex(source, raw);
    var stages = Array.isArray(raw.stages)
      ? raw.stages
      : Array.isArray(source.stages)
        ? source.stages
        : [];
    var losses = Array.isArray(raw.losses)
      ? raw.losses
      : Array.isArray(source.losses)
        ? source.losses
        : [];
    var narrative = firstFilled([raw.text, source.summary, source.text]);
    var participants =
      raw.participants === null || raw.participants === undefined
        ? source.participants
        : raw.participants;

    var blocks = [
      [
        "【" + (title === undefined ? "战斗 " + orDash(id) : orDash(title)) + "】",
        "kind：" + orDash(firstFilled([raw.kind, source.kind])),
        "tick：" + orDash(firstFilled([source.tick, raw.tick])),
        "hex：" + (hex ? "(" + orDash(hex.q) + "," + orDash(hex.r) + ")" : "—"),
        "participants：" + idListText(participants),
        "过程：" + orDash(narrative),
      ].join("\n"),
    ];

    if (stages.length) {
      for (var i = 0; i < stages.length; i++) {
        blocks.push(stageText(stages[i]));
      }
    } else {
      blocks.push("—— 阶段：—");
    }

    var lossLines = ["—— 损失"];
    if (losses.length) {
      for (var j = 0; j < losses.length; j++) {
        var loss = losses[j] || {};
        lossLines.push(
          orDash(loss.stageId) +
            " / " +
            orDash(loss.unit) +
            "：" +
            formatDelta(loss.manpower) +
            " / " +
            formatDelta(loss.equipment)
        );
      }
    } else {
      lossLines.push("—");
    }
    blocks.push(lossLines.join("\n"));
    return blocks.join("\n\n");
  }

  /** 事件数组 → 每条 combatEventText，以空行连接；空数组/非数组 ⇒ ""（纯函数）。 */
  function eventsToText(events) {
    if (!Array.isArray(events) || !events.length) return "";
    var parts = [];
    for (var i = 0; i < events.length; i++) {
      parts.push(combatEventText(events[i]));
    }
    return parts.join("\n\n");
  }

  // ── 剪贴板（宽容：不可用 / 失败 ⇒ false，绝不抛）─────────────────────

  /**
   * 降级复制：临时 <textarea> + document.execCommand("copy")。
   * 无 document / 无 execCommand / body 不可挂 / 任一步失败 ⇒ false。
   */
  function fallbackCopyText(text) {
    if (typeof document === "undefined" || !document) return false;
    if (typeof document.createElement !== "function") return false;
    if (typeof document.execCommand !== "function") return false;
    var area = null;
    try {
      area = document.createElement("textarea");
      area.value = text;
      if (typeof area.setAttribute === "function") area.setAttribute("readonly", "");
      area.style.position = "fixed";
      area.style.top = "-9999px";
      area.style.left = "-9999px";
      var body = document.body;
      if (!body || typeof body.appendChild !== "function") return false;
      body.appendChild(area);
      if (typeof area.focus === "function") area.focus();
      if (typeof area.select === "function") area.select();
      if (typeof area.setSelectionRange === "function") {
        area.setSelectionRange(0, area.value.length);
      }
      try {
        return document.execCommand("copy") === true;
      } catch (execErr) {
        return false;
      }
    } catch (err) {
      return false;
    } finally {
      var parent = area && area.parentNode;
      if (parent && typeof parent.removeChild === "function") {
        try {
          parent.removeChild(area);
        } catch (removeErr) {
          // 清理失败不影响复制结果
        }
      }
    }
  }

  /**
   * 复制文本：优先 navigator.clipboard.writeText；不可用或拒绝 ⇒ textarea + execCommand 降级。
   * 始终返回 Promise<boolean>（全失败 ⇒ false），不抛。
   */
  function copyTextToClipboard(text) {
    var value = text === null || text === undefined ? "" : String(text);
    var clipboard = null;
    try {
      if (typeof navigator !== "undefined" && navigator) clipboard = navigator.clipboard || null;
    } catch (navigatorErr) {
      clipboard = null;
    }
    if (clipboard && typeof clipboard.writeText === "function") {
      try {
        return Promise.resolve(clipboard.writeText(value)).then(
          function () {
            return true;
          },
          function () {
            return fallbackCopyText(value);
          }
        );
      } catch (syncErr) {
        // 同步抛（某些壳）⇒ 走降级
      }
    }
    return Promise.resolve(fallbackCopyText(value));
  }

  var COPY_FEEDBACK_MS = 1200;

  /** 按钮文案短暂反馈（~1.2s 后恢复原文案）；重复点击重置计时，不叠加。 */
  function flashButtonText(button, message) {
    if (!button) return;
    if (button.__simosCopyResetTimer) {
      clearTimeout(button.__simosCopyResetTimer);
    }
    if (button.__simosCopyLabel === undefined) {
      button.__simosCopyLabel =
        button.textContent === null || button.textContent === undefined
          ? ""
          : String(button.textContent);
    }
    button.textContent = message;
    button.__simosCopyResetTimer = setTimeout(function () {
      button.__simosCopyResetTimer = null;
      button.textContent = button.__simosCopyLabel;
    }, COPY_FEEDBACK_MS);
  }

  /** 执行复制并给按钮回显：成功「已复制」/ 失败「复制失败」。 */
  function copyWithFeedback(button, text) {
    var pending;
    try {
      pending = copyTextToClipboard(text);
    } catch (err) {
      flashButtonText(button, "复制失败");
      return;
    }
    if (!pending || typeof pending.then !== "function") {
      flashButtonText(button, pending === true ? "已复制" : "复制失败");
      return;
    }
    pending.then(
      function (ok) {
        flashButtonText(button, ok ? "已复制" : "复制失败");
      },
      function () {
        flashButtonText(button, "复制失败");
      }
    );
  }

  /** 工具栏「复制当前 tick 战况」：无事件 ⇒ 只回显「无事件」，不调剪贴板。 */
  function copyEventsToText(button, events) {
    var list = Array.isArray(events) ? events : [];
    if (!list.length) {
      flashButtonText(button, "无事件");
      return;
    }
    var text;
    try {
      text = eventsToText(list);
    } catch (err) {
      flashButtonText(button, "复制失败");
      return;
    }
    copyWithFeedback(button, text);
  }

  /** 单条详情「📋 复制这条战况」。 */
  function copyOneEventText(button, event) {
    var text;
    try {
      text = combatEventText(event);
    } catch (err) {
      flashButtonText(button, "复制失败");
      return;
    }
    copyWithFeedback(button, text);
  }

  // ── provider 注册表 ─────────────────────────────────────────────────

  var combatProvider = {
    id: "combat",
    label: "战斗",
    icon: "⚔",
    load: function (target) {
      return api.cachedArmyCombats(target).then(function (body) {
        return (body && body.combats) || [];
      });
    },
    normalize: normalizeCombatEvent,
    renderDetail: renderCombatDetail,
  };

  function registerProvider(provider) {
    if (!provider || !provider.id || typeof provider.load !== "function") return null;
    for (var i = 0; i < providers.length; i++) {
      if (providers[i].id === provider.id) {
        providers[i] = provider;
        return provider;
      }
    }
    providers.push(provider);
    return provider;
  }

  function providerOf(type) {
    for (var i = 0; i < providers.length; i++) {
      if (providers[i].id === type) return providers[i];
    }
    return null;
  }

  // ── 面板开关 ────────────────────────────────────────────────────────

  function updateTitle() {
    var title = domById("event-panel-title");
    if (!title) return;
    title.textContent = scopeHex
      ? "事件 · (" + Number(scopeHex.q) + "," + Number(scopeHex.r) + ")"
      : "事件 · 当前 tick";
  }

  function setPanelOpen(open) {
    var panel = domById("event-panel");
    if (!panel) return;
    panel.hidden = !open;
  }

  function isOpen() {
    var panel = domById("event-panel");
    return !!(panel && !panel.hidden);
  }

  function close() {
    token++; // 关掉后，迟到的响应不得再回填
    setPanelOpen(false);
  }

  function openForHex(q, r) {
    scopeHex = { q: Number(q), r: Number(r) };
    setPanelOpen(true);
    updateTitle();
    return loadEvents();
  }

  function openAll() {
    scopeHex = null;
    setPanelOpen(true);
    updateTitle();
    return loadEvents();
  }

  /** 图层联动：combats 关 ⇒ 事件面板也关（"显示 / 关闭"由同一个开关收口）。 */
  function onLayerChange(prefs) {
    if (prefs && prefs.combats === false) close();
  }

  // ── 取数 / 状态 ─────────────────────────────────────────────────────

  function targetKey() {
    if (!app || typeof app.target !== "function") return "";
    var target = app.target() || {};
    return String(target.branch) + "@" + String(target.revision);
  }

  function loadEvents() {
    var myToken = ++token;
    var target = app && typeof app.target === "function" ? app.target() : null;
    var jobs = providers.map(function (provider) {
      var raw;
      try {
        raw = provider.load(target);
      } catch (e) {
        return Promise.resolve({ provider: provider, ok: false, error: e });
      }
      return Promise.resolve(raw).then(
        function (value) {
          return { provider: provider, ok: true, raw: value };
        },
        function (err) {
          return { provider: provider, ok: false, error: err };
        }
      );
    });
    if (!jobs.length) {
      lastEvents = [];
      lastErrors = [];
      render();
      return Promise.resolve([]);
    }
    return Promise.all(jobs).then(
      function (results) {
        if (myToken !== token) return [];
        var events = [];
        var errors = [];
        results.forEach(function (result) {
          if (!result.ok) {
            errors.push(result.error);
            return;
          }
          var list = Array.isArray(result.raw) ? result.raw : [];
          var normalize = result.provider && result.provider.normalize;
          list.forEach(function (record) {
            try {
              var event = normalize ? normalize(record) : record;
              if (event) events.push(event);
            } catch (e) {
              errors.push(e);
            }
          });
        });
        lastEvents = events;
        lastErrors = errors;
        render();
        return events;
      },
      function (err) {
        if (myToken !== token) return [];
        lastEvents = [];
        lastErrors = [err];
        render();
        return [];
      }
    );
  }

  /** 状态变化：读口 target（branch@revision）变了且面板开着 ⇒ 重取当前 tick 事件。 */
  function onState() {
    var key = targetKey();
    if (key === lastTargetKey) return;
    lastTargetKey = key;
    if (isOpen()) loadEvents();
  }

  // ── 渲染 ────────────────────────────────────────────────────────────

  function buildCard(event) {
    var provider = providerOf(event.type);
    var detail = app.el("div", { class: "event-card-detail" });
    // 单格视角（从 ⚔ 点进来）默认展开；当前 tick 全部列表默认收起，点卡头切换。
    detail.hidden = scopeHex === null;
    if (provider && typeof provider.renderDetail === "function") {
      try {
        provider.renderDetail(event, detail);
      } catch (e) {
        clearNode(detail);
        detail.appendChild(
          app.el("p", { class: "event-error", text: "详情渲染失败：" + errorMessage(e) })
        );
      }
    } else {
      detail.appendChild(
        app.el("p", { class: "event-empty muted", text: "未知事件类型：" + valueText(event.type) })
      );
    }
    var head = app.el(
      "button",
      {
        type: "button",
        class: "event-card-head",
        onclick: function () {
          detail.hidden = !detail.hidden;
        },
      },
      [
        app.el("span", {
          class: "event-card-icon",
          text: provider && provider.icon ? provider.icon : "•",
        }),
        app.el("span", { class: "event-card-title", text: valueText(event.title || event.id) }),
        app.el("span", {
          class: "event-card-summary",
          text: event.summary === null || event.summary === undefined ? "" : String(event.summary),
        }),
      ]
    );
    return app.el(
      "div",
      { class: "event-card", "data-event-id": event.id, "data-event-type": event.type },
      [head, detail]
    );
  }

  function render() {
    var mount = domById("event-panel-mount");
    updateTitle();
    if (!mount) return;
    clearNode(mount);

    var events = filterEventsForHex(lastEvents, scopeHex);
    var toolbarChildren = [
      app.el("span", { class: "event-count", text: events.length + " 条事件" }),
    ];
    if (scopeHex) {
      toolbarChildren.push(
        app.el("button", {
          type: "button",
          id: "event-panel-scope-toggle",
          text: "查看当前 tick 全部",
          onclick: openAll,
        })
      );
    }
    // 一键复制**当前渲染的这份 events**（已按 scopeHex 过滤）；无事件 ⇒ 只回显「无事件」。
    var copyAllButton = app.el("button", {
      type: "button",
      id: "event-panel-copy-all",
      text: "复制当前 tick 战况",
    });
    copyAllButton.onclick = function () {
      copyEventsToText(copyAllButton, events);
    };
    toolbarChildren.push(copyAllButton);
    mount.appendChild(app.el("div", { class: "event-toolbar" }, toolbarChildren));
    if (lastErrors.length) {
      mount.appendChild(
        app.el("p", { class: "event-error", text: "读取事件失败：" + errorMessage(lastErrors[0]) })
      );
      // 一个事件都没读到 ⇒ 空态文案会假装"确定没有事件"，这里只报失败原因。
      if (!events.length) return;
    }
    if (!events.length) {
      mount.appendChild(
        app.el("p", {
          class: "event-empty muted",
          text: scopeHex ? "该格无战斗事件" : "当前 tick 无事件",
        })
      );
      return;
    }
    events.forEach(function (event) {
      mount.appendChild(buildCard(event));
    });
  }

  // ── 战斗详情渲染 ────────────────────────────────────────────────────

  function appendKv(list, key, value) {
    list.appendChild(app.el("dt", { text: key }));
    list.appendChild(app.el("dd", { text: value }));
  }

  function renderStage(stage) {
    var outcomes = Array.isArray(stage.outcomes) ? stage.outcomes : [];
    var rows = outcomes.map(function (outcome) {
      var selected =
        outcome.id === stage.selectedOutcomeId || outcome.id === stage.selectedOutcome;
      return app.el("tr", { class: selected ? "selected event-selected" : "" }, [
        app.el("td", { class: "event-outcome-mark", text: selected ? "✓" : "" }),
        app.el("td", { text: valueText(outcome.label) }),
        app.el("td", { text: valueText(outcome.weight) }),
        // outcome.losses 是该结局的**潜在损失**：未判定阶段 top-level losses 为空，不代表没有这部分。
        app.el("td", { class: "event-outcome-losses", text: formatOutcomeLosses(outcome.losses) }),
      ]);
    });
    var table = app.el("table", { class: "event-outcomes" }, [
      app.el("thead", {}, [
        app.el("tr", {}, [
          app.el("th", { text: "选中标记" }),
          app.el("th", { text: "label" }),
          app.el("th", { text: "weight" }),
          app.el("th", { text: "损失" }),
        ]),
      ]),
      app.el("tbody", {}, rows),
    ]);
    return app.el("section", { class: "event-stage" }, [
      app.el("h4", {
        text: "阶段 " + valueText(stage.name) + "（" + (stage.resolved ? "已判定" : "未判定") + "）",
      }),
      app.el("p", {
        class: "event-stage-participants muted",
        text: "participants：" + idListText(stage.participants),
      }),
      app.el("p", { class: "event-stage-text", text: valueText(stage.text) }),
      table,
      app.el("p", { class: "event-roll-seed muted", text: "rollSeed：" + valueText(stage.rollSeed) }),
    ]);
  }

  function renderLosses(losses) {
    var rows = (Array.isArray(losses) ? losses : []).map(function (loss) {
      return app.el("tr", {}, [
        app.el("td", { text: valueText(loss.stageId) }),
        app.el("td", { text: valueText(loss.unit) }),
        app.el("td", { text: formatDelta(loss.manpower) }),
        app.el("td", { text: formatDelta(loss.equipment) }),
      ]);
    });
    if (!rows.length) {
      rows.push(app.el("tr", {}, [app.el("td", { colspan: "4", text: "—" })]));
    }
    return app.el("table", { class: "event-losses" }, [
      app.el("thead", {}, [
        app.el("tr", {}, [
          app.el("th", { text: "stageId" }),
          app.el("th", { text: "unit" }),
          app.el("th", { text: "manpower" }),
          app.el("th", { text: "equipment" }),
        ]),
      ]),
      app.el("tbody", {}, rows),
    ]);
  }

  /** provider.renderDetail：把一条战斗记录的全部字段用 app.el 建成 DOM（只走 textContent，不写 HTML 字符串）。 */
  function renderCombatDetail(event, container) {
    var raw = (event && event.raw) || {};
    clearNode(container);

    // 单条一键复制：文案与战况纯文本由同一个 combatEventText 渲染，避免两处格式漂移。
    var copyButton = app.el("button", {
      type: "button",
      class: "event-copy-one",
      text: "📋 复制这条战况",
    });
    copyButton.onclick = function () {
      copyOneEventText(copyButton, event);
    };
    container.appendChild(copyButton);

    var kv = app.el("dl", { class: "event-kv" }, []);
    appendKv(kv, "id", valueText(raw.id));
    appendKv(kv, "kind", valueText(raw.kind));
    appendKv(kv, "tick", valueText(raw.tick));
    var hex = raw.hex || (event && event.hex);
    appendKv(kv, "hex", hex ? "(" + valueText(hex.q) + "," + valueText(hex.r) + ")" : "—");
    appendKv(kv, "participants", idListText(raw.participants));
    container.appendChild(kv);

    var detailText = app && typeof app.text === "function" ? app.text(raw.text) : valueText(raw.text);
    container.appendChild(app.el("p", { class: "event-text", text: detailText }));

    var stages = Array.isArray(raw.stages) ? raw.stages : [];
    stages.forEach(function (stage) {
      container.appendChild(renderStage(stage));
    });

    container.appendChild(renderLosses(raw.losses));
  }

  // ── 初始化 ──────────────────────────────────────────────────────────

  function init() {
    if (initialized) return;
    initialized = true;
    registerProvider(combatProvider);
    var closeButton = domById("event-panel-close");
    if (closeButton && closeButton.addEventListener) {
      closeButton.addEventListener("click", close);
    }
    var scopeButton = domById("event-panel-scope-toggle");
    if (scopeButton && scopeButton.addEventListener) {
      scopeButton.addEventListener("click", openAll);
    }
    if (app && typeof app.onStateChange === "function") {
      lastTargetKey = targetKey();
      app.onStateChange(onState);
    }
  }

  window.SimosEvents = {
    init: init,
    registerProvider: registerProvider,
    // 纯函数（node 门禁直接断言）
    normalizeCombatEvent: normalizeCombatEvent,
    filterEventsForHex: filterEventsForHex,
    formatDelta: formatDelta,
    formatOutcomeLosses: formatOutcomeLosses,
    combatEventText: combatEventText,
    eventsToText: eventsToText,
    // 剪贴板（宽容：不可用 / 失败 ⇒ Promise<false>，不抛）
    copyTextToClipboard: copyTextToClipboard,
    // 面板
    openForHex: openForHex,
    openAll: openAll,
    close: close,
    isOpen: isOpen,
    onLayerChange: onLayerChange,
    loadEvents: loadEvents,
    render: render,
    onState: onState,
    // 详情渲染（provider 形状的默认实现；单测可直接喂一条 event）
    renderCombatDetail: renderCombatDetail,
  };

  if (typeof document !== "undefined" && document && document.addEventListener) {
    document.addEventListener("DOMContentLoaded", init);
  }
})();
