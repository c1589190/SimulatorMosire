// app.js —— 三页共享的导航、格式化与小工具（M5 T9）。
// ★ 无框架、无构建：只用原生 DOM API，保持同源、零依赖（spec §8.1）。

(function () {
  "use strict";

  var PAGES = [
    { href: "/", label: "首页" },
    { href: "/map", label: "地图" },
    { href: "/unit", label: "单位" },
    { href: "/social", label: "社会" },
  ];

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

  /** 轮询 /api/state 顶栏摘要（revision / tick）。失败时显示离线。 */
  function pollState() {
    var node = byId("shell-state");
    if (!node || !window.SimosApi) {
      return;
    }
    var refresh = async function () {
      try {
        var body = await window.SimosApi.state();
        var meta = body.meta;
        if (!meta) {
          node.textContent = "无状态";
          return;
        }
        node.textContent =
          "分支 " + meta.branch + " · rev " + meta.revision + " · tick " + meta.timestamp.tick;
      } catch (e) {
        node.textContent = "状态不可用（" + e.message + "）";
      }
    };
    refresh();
    window.setInterval(refresh, 5000);
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

  /** 页面初始化共用入口：nav + state 摘要（+ 可选待批）。 */
  function boot(options) {
    var opts = options || {};
    mountNav();
    pollState();
    if (opts.approvals) {
      mountApprovals(opts.approvals);
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
  };
})();
