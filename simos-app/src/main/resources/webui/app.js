// app.js —— 单页工作台的状态机 + 三页共享的导航、格式化与小工具（M5 T9；M7 T2 改造）。
// ★ 无框架、无构建：只用原生 DOM API，保持同源、零依赖（spec §8.1）。
// ★ M7 T2：本文件持有唯一状态 {mode, branch, revision, selection}；模式切换只改可见性/可用性，
//   不改数据来源。其它文件（panels/timeline/unitTree/map）经 window.SimosApp 读取与订阅。

(function () {
  "use strict";

  var PAGES = [
    { href: "/", label: "首页" },
    { href: "/map", label: "地图" },
    { href: "/unit", label: "单位" },
    { href: "/social", label: "社会" },
  ];

  // ── 状态机（M7 T2）──────────────────────────────────────────────────

  /** 唯一可变状态；模式只影响可见性/可用性（spec §四）。branches/heads 来自 /api/state（M7 T3）。 */
  var state = {
    mode: "view",
    branch: "main",
    revision: null,
    selection: null,
    highlightRegions: [],
    branches: [],
    heads: {},
  };

  var listeners = [];

  function getState() {
    return state;
  }

  function setMode(mode) {
    if (!mode || mode === state.mode) {
      return;
    }
    state.mode = mode;
    applyMode();
    notify();
  }

  function setBranch(branch) {
    state.branch = branch;
    notify();
  }

  function setRevision(revision) {
    state.revision = revision;
    notify();
  }

  function setSelection(selection) {
    state.selection = selection;
    notify();
  }

  /** 当前要高亮的区域集合（M7 T4；区域分组列表归 T6）。map.js 订阅它并懒拉区域 hex。 */
  function setHighlightRegions(ids) {
    state.highlightRegions = Array.isArray(ids) ? ids.slice() : [];
    notify();
  }

  /** 订阅状态变化；返回取消订阅函数。 */
  function onStateChange(listener) {
    listeners.push(listener);
    return function () {
      var index = listeners.indexOf(listener);
      if (index >= 0) {
        listeners.splice(index, 1);
      }
    };
  }

  function notify() {
    listeners.slice().forEach(function (listener) {
      listener(state);
    });
  }

  /** 模式切换的落点：按钮选中态 + [data-modes] 元素的可见性。不碰任何数据。 */
  function applyMode() {
    document.body.setAttribute("data-mode", state.mode);
    var bar = byId("mode-bar");
    if (bar) {
      Array.prototype.forEach.call(bar.querySelectorAll("button[data-mode]"), function (button) {
        if (button.disabled) {
          return;
        }
        var active = button.getAttribute("data-mode") === state.mode;
        button.classList.toggle("active", active);
        button.setAttribute("aria-pressed", active ? "true" : "false");
      });
    }
    Array.prototype.forEach.call(document.querySelectorAll("[data-modes]"), function (node) {
      var modes = node.getAttribute("data-modes").split(/\s+/);
      node.hidden = modes.indexOf(state.mode) === -1;
    });
  }

  /** 接线模式栏按钮；禁用的（归 M8）不接线。 */
  function mountModeBar() {
    var bar = byId("mode-bar");
    if (!bar) {
      return;
    }
    Array.prototype.forEach.call(bar.querySelectorAll("button[data-mode]"), function (button) {
      button.addEventListener("click", function () {
        if (button.disabled) {
          return;
        }
        setMode(button.getAttribute("data-mode"));
      });
    });
    applyMode();
  }

  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (key) {
        if (key === "class") {
          node.className = attrs[key];
        } else if (key === "text") {
          node.textContent = attrs[key];
        } else if (key.indexOf("on") === 0 && typeof attrs[key] === "function") {
          node.addEventListener(key.slice(2), attrs[key]);
        } else {
          node.setAttribute(key, attrs[key]);
        }
      });
    }
    (children || []).forEach(function (child) {
      if (child === null || child === undefined) {
        return;
      }
      node.appendChild(typeof child === "string" ? document.createTextNode(child) : child);
    });
    return node;
  }

  function clear(node) {
    while (node.firstChild) {
      node.removeChild(node.firstChild);
    }
    return node;
  }

  function byId(id) {
    return document.getElementById(id);
  }

  function text(value) {
    return value === null || value === undefined ? "—" : String(value);
  }

  function json(value) {
    return JSON.stringify(value, null, 2);
  }

  /** 渲染顶部导航；标记当前页。 */
  function mountNav() {
    var nav = byId("nav");
    if (!nav) {
      return;
    }
    clear(nav);
    var here = window.location.pathname.replace(/\/+$/, "") || "/";
    PAGES.forEach(function (page) {
      var key = page.href.replace(/\/+$/, "") || "/";
      nav.appendChild(
        el("a", {
          href: page.href,
          class: key === here ? "active" : "",
          text: page.label,
        })
      );
    });
    nav.appendChild(
      el("span", {
        id: "shell-state",
        class: "shell-state",
        text: "状态加载中…",
      })
    );
  }

  /** 顶栏摘要文本：meta 缺省显示"无状态"。写后 refreshState 也会经它刷新，故不只 5s 轮询才更新。 */
  function renderShellState(meta) {
    var node = byId("shell-state");
    if (!node) {
      return;
    }
    if (!meta) {
      node.textContent = "无状态";
      return;
    }
    node.textContent =
      "分支 " + meta.branch + " · rev " + meta.revision + " · tick " + meta.timestamp.tick;
  }

  /** 轮询 /api/state 顶栏摘要（revision / tick）。失败时显示离线。 */
  function pollState() {
    var node = byId("shell-state");
    if (!node || !window.SimosApi) {
      return;
    }
    var refresh = async function () {
      try {
        await refreshState();
      } catch (e) {
        node.textContent = "状态不可用（" + e.message + "）";
      }
    };
    refresh();
    window.setInterval(refresh, 5000);
  }

  /**
   * 把 /api/state 的服务器视图写进状态机（M7 T3）：分支表与各分支 head **永远更新**；游标**只在首次**（revision
   * 尚为 null）时初始化。若每次轮询都回填游标，用户拖到中间节点的预览会被 5s 定时器弹回末端——那让 U1 的"只读预览"不可用。
   */
  function setServerState(branches, heads, meta) {
    state.branches = Array.isArray(branches) ? branches.slice() : [];
    state.heads = heads && typeof heads === "object" ? Object.assign({}, heads) : {};
    if (state.revision === null || state.revision === undefined) {
      var branch = (meta && meta.branch) || state.branch;
      if (state.branches.indexOf(branch) < 0) {
        branch = state.branches.length > 0 ? state.branches[0] : branch;
      }
      state.branch = branch;
      if (state.heads[branch] !== null && state.heads[branch] !== undefined) {
        state.revision = state.heads[branch];
      } else if (meta) {
        state.revision = meta.revision;
      }
    }
    renderShellState(meta);
    notify();
  }

  /** 重取 /api/state 并写回状态机；返回服务器视图（调用方可复用，避免重复请求）。 */
  async function refreshState() {
    var body = await window.SimosApi.state();
    setServerState(body.branches, body.heads, body.meta);
    return body;
  }

  /** 所有面板的只读取数目标（M7 T3 约定；T4~T7 一律传它）。 */
  function target() {
    return { branch: state.branch, revision: state.revision };
  }

  // ── 写命令（M7 T7）────────────────────────────────────────────────
  //
  // ★ 唯一写入口是服务端的 CoreSimos.submit；前端只组信封、打三个 allowlist 端点之一
  //   （/api/command|advance|fork，R8）。本文件只发 submitCommand（/api/command）。

  /**
   * 组一条写命令信封：{type, payloadJson, branch, expectedRevision}。
   * ★ expectedRevision 一律取**当前游标**——过期由服务端 409 挡，前端不猜、不预检。
   */
  function commandEnvelope(type, payload) {
    return {
      type: type,
      payloadJson: JSON.stringify(payload === undefined ? {} : payload),
      branch: state.branch,
      expectedRevision: state.revision,
    };
  }

  /**
   * 提交一条写命令（M7 T7）：
   *
   * <ul>
   *   <li>成功 ⇒ 重取 /api/state 并把游标推进到新 head（写后可见：时间轴 +1、面板切到新 revision）；
   *   <li>409 ⇒ 重取 head 并把游标拉到服务端 `current.revision`（**不静默重试**，spec §五 第 5 步）；
   *   <li>422 ⇒ 原样回服务端 `reason`（MUST DO #6：不得吞掉、不得只 console）。
   * </ul>
   *
   * 返回 {ok:true, body} 或 {ok:false, kind:"conflict"|"rejected"|"error", message, error}——本函数**不碰
   * DOM**，显示位置由调用方决定。
   */
  async function writeCommand(type, payload) {
    try {
      var body = await window.SimosApi.submitCommand(commandEnvelope(type, payload));
      try {
        await refreshState();
      } catch (refreshError) {
        // 提交已成功：刷新失败不该把成功报成失败（body.ref 已是权威新坐标）。
      }
      if (body && body.ref && body.ref.revision !== undefined) {
        setRevision(body.ref.revision);
      }
      return { ok: true, body: body };
    } catch (error) {
      if (error && error.status === 409) {
        var current = error.body && error.body.current ? error.body.current : null;
        try {
          await refreshState();
        } catch (refreshError) {
          // 重取失败：保持旧游标，下次操作仍会 409（不伪造成功）。
        }
        if (current && current.revision !== undefined) {
          setRevision(current.revision);
        }
        return {
          ok: false,
          kind: "conflict",
          message: "末端已移动，已自动重取最新状态",
          error: error,
        };
      }
      if (error && error.status === 422) {
        var reason =
          error.body && error.body.reason ? error.body.reason : "422 " + (error.message || "被拒");
        return { ok: false, kind: "rejected", message: reason, error: error };
      }
      return {
        ok: false,
        kind: "error",
        message: (error && error.message) || String(error),
        error: error,
      };
    }
  }

  /** 待批计数（T6 未接入时 /api/approvals 回 503 —— 优雅显示"未接入"）。 */
  function mountApprovals(containerId) {
    var node = byId(containerId);
    if (!node || !window.SimosApi) {
      return;
    }
    var refresh = async function () {
      try {
        var body = await window.SimosApi.approvals();
        var count = Array.isArray(body) ? body.length : body && body.items ? body.items.length : 0;
        node.textContent = "待批：" + count;
        node.classList.remove("muted");
      } catch (e) {
        node.textContent = "审批未接入（" + e.message + "）";
        node.classList.add("muted");
      }
    };
    refresh();
    window.setInterval(refresh, 5000);
  }

  /** 页面初始化共用入口：nav + state 摘要（+ 可选待批）；工作台页额外接线模式栏与各面板骨架。 */
  function boot(options) {
    var opts = options || {};
    mountNav();
    pollState();
    if (opts.approvals) {
      mountApprovals(opts.approvals);
    }
    if (byId("mode-bar")) {
      mountModeBar();
      if (window.SimosPanels && window.SimosPanels.init) {
        window.SimosPanels.init();
      }
      if (window.SimosUnitTree && window.SimosUnitTree.init) {
        window.SimosUnitTree.init();
      }
      if (window.SimosTimeline && window.SimosTimeline.init) {
        window.SimosTimeline.init();
      }
    }
    document.title = (opts.title ? opts.title + " · " : "") + "Simos Shell";
  }

  /** 一个轻量的消息条；tone ∈ ok | warn | err | muted。 */
  function statusMessage(node, message, tone) {
    if (!node) {
      return;
    }
    node.className = "status " + (tone || "muted");
    node.textContent = message;
  }

  /** 表单字段值读取：空串 → null；数字字段转 Number。 */
  function fieldValue(form, name) {
    var input = form.querySelector('[name="' + name + '"]');
    if (!input) {
      return undefined;
    }
    var raw = (input.value || "").trim();
    if (raw === "") {
      return null;
    }
    return raw;
  }

  function intField(form, name) {
    var raw = fieldValue(form, name);
    if (raw === null || raw === undefined) {
      return null;
    }
    var n = Number(raw);
    return Number.isFinite(n) ? n : null;
  }

  window.SimosApp = {
    el: el,
    clear: clear,
    byId: byId,
    text: text,
    json: json,
    boot: boot,
    mountNav: mountNav,
    mountApprovals: mountApprovals,
    statusMessage: statusMessage,
    fieldValue: fieldValue,
    intField: intField,
    getState: getState,
    setMode: setMode,
    setBranch: setBranch,
    setRevision: setRevision,
    setSelection: setSelection,
    setHighlightRegions: setHighlightRegions,
    onStateChange: onStateChange,
    applyMode: applyMode,
    mountModeBar: mountModeBar,
    target: target,
    refreshState: refreshState,
    setServerState: setServerState,
    commandEnvelope: commandEnvelope,
    writeCommand: writeCommand,
  };
})();
