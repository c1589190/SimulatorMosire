// gm.js —— 底栏「GM 界面」按钮 + 全屏 GM 交互界面（本阶段 T8，spec §七.4 C22）。
//
// ★ 一阶段范围：**只显示 GM MCP 的工具使用**（工具名 + 结果），**不做对话**（无输入框）。
// ★ 数据源 = GET /api/gm/tool-usage（服务端 RecordingToolSource 在 GM 口每条工具执行后留的痕）；
//   取不到就显示**明确空态**，绝不编造记录。
// ★ 浮层复用 M7g 的全屏机制：元素在 .wb-overlay（pointer-events:none）内 ⇒ 本层必须自行 auto，
//   否则点不到；position:fixed + 默认 hidden ⇒ 不参与文档流、不把底栏时间轴挤出视口。
"use strict";

(function () {
  "use strict";

  var DEFAULT_BUTTON = "gm-open";
  var DEFAULT_OVERLAY = "gm-overlay";
  var DEFAULT_BODY = "gm-tool-usage";
  var DEFAULT_STATUS = "gm-status";
  var DEFAULT_CLOSE = "gm-close";
  var DEFAULT_REFRESH = "gm-refresh";

  var EMPTY_TEXT = "暂无 GM MCP 工具调用记录。";

  /** 从 /api/gm/tool-usage 的响应体取记录数组（兼容 {entries:[…]} / 裸数组）；坏体 ⇒ 空数组。 */
  function toolUsageEntries(body) {
    if (Array.isArray(body)) {
      return body;
    }
    if (body && Array.isArray(body.entries)) {
      return body.entries;
    }
    return [];
  }

  /** 结果文本：成功 / 失败（错误码）。code 缺省按 ERROR 显示（不静默成"成功"）。 */
  function resultText(entry) {
    if (entry && entry.ok === true) {
      return "成功";
    }
    var code = entry && entry.code ? entry.code : "ERROR";
    return "失败（" + code + "）";
  }

  function timeText(entry) {
    var at = entry && typeof entry.atEpochMs === "number" ? entry.atEpochMs : null;
    if (at === null) {
      return "—";
    }
    var date = new Date(at);
    return isNaN(date.getTime()) ? "—" : date.toLocaleTimeString();
  }

  /** 一条记录 ⇒ 一行文本（工具名 · 结果 · 时刻）。纯函数。 */
  function formatEntry(entry) {
    var tool = entry && entry.tool ? entry.tool : "（未知工具）";
    return tool + " · " + resultText(entry) + " · " + timeText(entry);
  }

  /** 服务端响应 ⇒ 渲染计划（纯函数）：条数 + 行 + **显式空态**文本。 */
  function renderPlan(body) {
    var entries = toolUsageEntries(body);
    return {
      count: entries.length,
      empty: entries.length === 0,
      lines: entries.map(function (entry) {
        return {
          tool: entry && entry.tool ? entry.tool : "",
          ok: !!(entry && entry.ok === true),
          text: formatEntry(entry),
        };
      }),
      emptyText: EMPTY_TEXT,
    };
  }

  function clear(node) {
    if (!node) {
      return node;
    }
    while (node.firstChild) {
      node.removeChild(node.firstChild);
    }
    return node;
  }

  /**
   * 把一次 /api/gm/tool-usage 读数渲染进 bodyNode，状态行写 statusNode。返回条数；端点不可达 ⇒ null。
   * doc 可注入（测试用替身文档），缺省 = 全局 document。
   */
  function renderInto(bodyNode, statusNode, api, doc) {
    var d = doc || document;
    return Promise.resolve()
      .then(function () {
        return api.gmToolUsage();
      })
      .then(function (body) {
        var plan = renderPlan(body);
        clear(bodyNode);
        if (plan.empty) {
          var empty = d.createElement("p");
          empty.className = "muted";
          empty.textContent = plan.emptyText;
          bodyNode.appendChild(empty);
        } else {
          var list = d.createElement("ul");
          list.className = "gm-usage-list";
          plan.lines.forEach(function (line) {
            var item = d.createElement("li");
            item.className = "gm-usage-item" + (line.ok ? "" : " gm-usage-failed");
            item.textContent = line.text;
            list.appendChild(item);
          });
          bodyNode.appendChild(list);
        }
        if (statusNode) {
          statusNode.textContent = "共 " + plan.count + " 条 · GET /api/gm/tool-usage";
        }
        return plan.count;
      })
      .catch(function (error) {
        clear(bodyNode);
        var failed = d.createElement("p");
        failed.className = "status warn";
        failed.textContent = "GM 工具使用不可达（" + ((error && error.message) || error) + "）";
        bodyNode.appendChild(failed);
        if (statusNode) {
          statusNode.textContent = "不可达";
        }
        return null;
      });
  }

  /** 全屏层是否打开（hidden=false 即打开）。 */
  function isOpen(overlay) {
    return !!(overlay && !overlay.hidden);
  }

  /** 打开/关闭：overlay.hidden 与按钮 aria-expanded 同步；返回打开后的状态。 */
  function setOpen(overlay, button, open) {
    var next = !!open;
    if (overlay) {
      overlay.hidden = !next;
    }
    if (button && button.setAttribute) {
      button.setAttribute("aria-expanded", next ? "true" : "false");
    }
    return next;
  }

  /** 接线：底栏按钮打开并立即取数、关闭按钮收起、刷新按钮重取。返回节点句柄（测试用）。 */
  function mount(doc) {
    var d = doc || document;
    var byId = function (id) {
      return d.getElementById ? d.getElementById(id) : null;
    };
    var overlay = byId(DEFAULT_OVERLAY);
    var button = byId(DEFAULT_BUTTON);
    var body = byId(DEFAULT_BODY);
    if (!overlay || !button || !body) {
      return null;
    }
    var status = byId(DEFAULT_STATUS);
    var close = byId(DEFAULT_CLOSE);
    var refresh = byId(DEFAULT_REFRESH);

    var refreshNow = function () {
      if (window.SimosApi) {
        renderInto(body, status, window.SimosApi, d);
      }
    };
    button.addEventListener("click", function () {
      setOpen(overlay, button, true);
      refreshNow();
    });
    if (close) {
      close.addEventListener("click", function () {
        setOpen(overlay, button, false);
      });
    }
    if (refresh) {
      refresh.addEventListener("click", refreshNow);
    }
    return { overlay: overlay, button: button, body: body, refresh: refreshNow };
  }

  function init() {
    mount(document);
  }

  window.SimosGm = {
    toolUsageEntries: toolUsageEntries,
    resultText: resultText,
    formatEntry: formatEntry,
    renderPlan: renderPlan,
    renderInto: renderInto,
    isOpen: isOpen,
    setOpen: setOpen,
    mount: mount,
    init: init,
  };

  if (typeof document !== "undefined" && document.addEventListener) {
    document.addEventListener("DOMContentLoaded", init);
  }
})();
