// docs.js —— D-036 世界文档面板（工作台）。
//
// 数据面：GET /api/docs（按地址读）、GET /api/docs/search（查询）、GET /api/docs/version（历史版本）、
//         POST /api/docs（create / edit / delete）。
// 文档是独立持久数据，不进入 world revision；本文件只做 DOM 与请求编排，不做领域公式。
// 战役/阶段详情通过 window.SimosDocs.openBattle(...) / openStage(...) 打开对应地址。

(function () {
  "use strict";

  var currentMeta = null;

  function byId(id) {
    return document.getElementById(id);
  }

  function setStatus(text, isError) {
    var node = byId("docs-status");
    if (!node) return;
    node.textContent = text || "";
    node.classList.toggle("docs-error", Boolean(isError));
  }

  function clearNode(node) {
    while (node && node.firstChild) {
      node.removeChild(node.firstChild);
    }
  }

  function value(node) {
    return node && node.value !== undefined ? String(node.value) : "";
  }

  function namesValue() {
    return value(byId("docs-names"))
      .split(",")
      .map(function (name) {
        return name.trim();
      })
      .filter(function (name) {
        return name.length > 0;
      });
  }

  function renderVersions(versions) {
    var mount = byId("docs-versions");
    if (!mount) return;
    clearNode(mount);
    var list = Array.isArray(versions) ? versions : [];
    if (!list.length) return;
    list.forEach(function (version) {
      var button = document.createElement("button");
      button.type = "button";
      button.textContent = "v" + version.version;
      if (currentMeta && version.version === currentMeta.version) {
        button.classList.add("current");
      }
      button.title = version.file;
      button.addEventListener("click", function () {
        viewVersion(version.version);
      });
      mount.appendChild(button);
    });
  }

  function renderResults(entries) {
    var mount = byId("docs-results");
    if (!mount) return;
    clearNode(mount);
    var list = Array.isArray(entries) ? entries : [];
    if (!list.length) {
      var empty = document.createElement("p");
      empty.className = "muted";
      empty.textContent = "没有匹配的文档。";
      mount.appendChild(empty);
      return;
    }
    list.forEach(function (entry) {
      var button = document.createElement("button");
      button.type = "button";
      button.className = "docs-result";
      var address = document.createElement("span");
      address.className = "docs-result-address";
      address.textContent = entry.address;
      var title = document.createElement("span");
      title.className = "docs-result-title";
      title.textContent = entry.title + " · v" + entry.version + (entry.deletedAt ? " · 已删除" : "");
      button.appendChild(address);
      button.appendChild(title);
      button.addEventListener("click", function () {
        byId("docs-address").value = entry.address;
        load(entry.address);
      });
      mount.appendChild(button);
    });
  }

  function renderDoc(body) {
    var meta = body && body.meta ? body.meta : null;
    if (!meta) return;
    currentMeta = meta;
    byId("docs-address").value = meta.address || "";
    byId("docs-title").value = meta.title || "";
    byId("docs-names").value = (meta.names || []).join(", ");
    byId("docs-content").value = body.content || "";
    renderVersions(body.versions);
    setStatus("v" + meta.version + " · " + meta.updatedAt + (meta.deletedAt ? " · 已删除" : ""));
  }

  function newDoc() {
    currentMeta = null;
    byId("docs-title").value = "";
    byId("docs-names").value = "";
    byId("docs-content").value = "";
    renderVersions([]);
    setStatus("新文档；填写地址与正文后保存");
  }

  async function load(address) {
    address = String(address || "").trim();
    if (!address) {
      newDoc();
      return;
    }
    try {
      var body = await window.SimosApi.getJson("/docs?address=" + encodeURIComponent(address));
      renderDoc(body);
    } catch (e) {
      if (e.status === 404) {
        currentMeta = null;
        byId("docs-title").value = "";
        byId("docs-names").value = "";
        byId("docs-content").value = "";
        renderVersions([]);
        setStatus("该地址还没有文档；保存即创建");
      } else {
        setStatus("读取失败：" + e.message, true);
      }
    }
  }

  async function save() {
    var address = value(byId("docs-address")).trim();
    if (!address) {
      setStatus("地址不能为空", true);
      return;
    }
    var action = currentMeta && currentMeta.address === address ? "edit" : "create";
    try {
      var body = await window.SimosApi.docsWrite({
        action: action,
        address: address,
        title: value(byId("docs-title")),
        content: value(byId("docs-content")),
        names: namesValue(),
      });
      renderDoc(body);
      setStatus("已保存 · v" + body.meta.version);
      await search(false);
    } catch (e) {
      setStatus("保存失败：" + e.message, true);
    }
  }

  async function remove() {
    if (!currentMeta) {
      setStatus("当前地址没有可删除的文档", true);
      return;
    }
    if (!window.confirm("删除文档 " + currentMeta.address + " ？\n正文会移入 docs/world/.recycle/，历史版本保留。")) {
      return;
    }
    try {
      var body = await window.SimosApi.docsWrite({
        action: "delete",
        address: currentMeta.address,
        deletedDate: new Date().toISOString().slice(0, 10),
      });
      currentMeta = body.meta;
      renderVersions([]);
      setStatus("已删除 · " + body.meta.deletedAt, false);
      await search(false);
    } catch (e) {
      setStatus("删除失败：" + e.message, true);
    }
  }

  async function viewVersion(version) {
    var address = value(byId("docs-address")).trim();
    if (!address) return;
    try {
      var body = await window.SimosApi.getJson(
        "/docs/version?address=" + encodeURIComponent(address) + "&version=" + encodeURIComponent(version)
      );
      byId("docs-content").value = body.content || "";
      setStatus("正在查看 v" + version + "；保存会从这份正文生成新版本", false);
    } catch (e) {
      setStatus("读取版本失败：" + e.message, true);
    }
  }

  async function search(showEmpty) {
    var query = value(byId("docs-search-input")).trim();
    try {
      var body = await window.SimosApi.getJson(
        "/docs/search?q=" + encodeURIComponent(query) + "&includeDeleted=false"
      );
      renderResults(body.entries || []);
      if (showEmpty !== false) {
        setStatus((body.entries || []).length + " 条结果");
      }
    } catch (e) {
      setStatus("查询失败：" + e.message, true);
    }
  }

  function open(address) {
    var panel = byId("docs-panel");
    if (!panel) return;
    panel.hidden = false;
    if (address) {
      byId("docs-address").value = address;
      load(address);
    } else {
      byId("docs-search-input").focus();
      search(false);
    }
  }

  function close() {
    var panel = byId("docs-panel");
    if (panel) panel.hidden = true;
  }

  /** 战役地址：<branch>-<tick>/battle/<id>；tick 来自 /api/state 的当前游标。 */
  function openBattle(combatId) {
    var state = window.SimosApp && window.SimosApp.getState ? window.SimosApp.getState() : null;
    if (!state || state.tick === null || state.tick === undefined) {
      setStatus("当前没有可用 tick，无法定位战役地址", true);
      open();
      return;
    }
    open(state.branch + "-" + state.tick + "/battle/" + combatId);
  }

  function openStage(combatId, stageId) {
    var state = window.SimosApp && window.SimosApp.getState ? window.SimosApp.getState() : null;
    if (!state || state.tick === null || state.tick === undefined) {
      setStatus("当前没有可用 tick，无法定位阶段地址", true);
      open();
      return;
    }
    open(state.branch + "-" + state.tick + "/battle/" + combatId + "/stage/" + stageId);
  }

  function bind() {
    var openButton = byId("docs-open");
    if (openButton) openButton.addEventListener("click", function () { open(); });
    var closeButton = byId("docs-close");
    if (closeButton) closeButton.addEventListener("click", close);
    var searchButton = byId("docs-search-button");
    if (searchButton) searchButton.addEventListener("click", function () { search(true); });
    var searchInput = byId("docs-search-input");
    if (searchInput) {
      searchInput.addEventListener("keydown", function (event) {
        if (event.key === "Enter") {
          event.preventDefault();
          search(true);
        }
      });
    }
    var newButton = byId("docs-new-button");
    if (newButton) {
      newButton.addEventListener("click", function () {
        byId("docs-address").value = "";
        newDoc();
      });
    }
    var addressInput = byId("docs-address");
    if (addressInput) {
      addressInput.addEventListener("change", function () { load(addressInput.value); });
    }
    var saveButton = byId("docs-save");
    if (saveButton) saveButton.addEventListener("click", save);
    var deleteButton = byId("docs-delete");
    if (deleteButton) deleteButton.addEventListener("click", remove);
    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape") {
        var panel = byId("docs-panel");
        if (panel && !panel.hidden) close();
      }
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bind);
  } else {
    bind();
  }

  window.SimosDocs = {
    open: open,
    close: close,
    openAddress: open,
    openBattle: openBattle,
    openStage: openStage,
  };
})();
