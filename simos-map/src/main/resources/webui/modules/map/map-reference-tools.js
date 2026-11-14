// map-reference-tools.js —— 底图子功能里的自由判图工具区：共享参考图、查看局部、直接多格地形、直接连接/擦除。
// ★ 无步骤锁：每项能力独立可用、可重复调用；只在“确实缺输入 / 历史只读节点 / 在途重复写”时约束。
// ★ 写入一律用**点击时冻结的 branch/expectedRevision**，不拿最新 revision 静默提交旧差集；拒绝/冲突不自动重试、不报成功。
(function () {
  "use strict";

  var CORE = window.SimosRasterCore;
  var app = window.SimosApp;
  var api = window.SimosApi;
  var references = [];
  var terrains = [];
  var groups = [];
  var selectedId = "";
  var lastView = null;
  var viewToken = 0;
  var referencesToken = 0;
  var overviewToken = 0;
  var referencesLoading = false;
  var overviewLoading = false;
  var busy = false;
  var bound = false;
  var panelSeen = false;

  function byId(id) { return document.getElementById(id); }
  function val(id) { var node = byId(id); return node ? String(node.value == null ? "" : node.value) : ""; }
  function check(id) { return !!(byId(id) && byId(id).checked); }
  function status(text, tone) {
    var node = byId("reference-status");
    if (node) { node.textContent = text || ""; node.className = "status " + (tone || "muted"); }
  }
  function viewStatus(text, tone) {
    var node = byId("reference-view-status");
    if (node) { node.textContent = text || ""; node.className = "status " + (tone || "muted"); }
  }
  function stateNow() { return app && app.getState ? app.getState() : {}; }
  function frozenTarget() {
    var state = stateNow();
    return { branch: state.branch, revision: state.revision, tick: state.tick, writeEpoch: state.writeEpoch };
  }
  function sameTarget(a, b) {
    if (!a || !b) return false;
    return a.branch === b.branch && Number(a.revision) === Number(b.revision)
      && a.tick === b.tick && a.writeEpoch === b.writeEpoch;
  }
  function signature() {
    var frozen = frozenTarget();
    return JSON.stringify([frozen.branch, frozen.revision, frozen.tick, frozen.writeEpoch]);
  }
  function currentSourceVersion() {
    var debug = window.SimosMapPng && window.SimosMapPng.debugState ? window.SimosMapPng.debugState() : null;
    return debug ? debug.sourceVersion : null;
  }
  function isWritable() {
    if (!app || !app.getState) return false;
    var state = stateNow();
    var host = window.SimosMapCore && window.SimosMapCore.host;
    return state.mode === "map-edit" && host && host.mapEditTool === "image-import"
      && state.revision !== null && state.revision !== undefined
      && state.heads && Number(state.heads[state.branch]) === Number(state.revision);
  }
  function clearView() {
    lastView = null;
    ["reference-image", "reference-viewer-image"].forEach(function (id) {
      var image = byId(id);
      if (image) { image.hidden = true; image.removeAttribute("src"); }
    });
    var dialog = byId("reference-viewer-dialog");
    if (dialog && dialog.open && dialog.close) dialog.close();
    updateAvailability();
  }
  function updateAvailability() {
    var writable = isWritable();
    var terrainReady = terrains.length > 0;
    var kindReady = groups.length > 0;
    [["reference-paint-apply", terrainReady], ["reference-create-missing", terrainReady],
      ["reference-connect-apply", kindReady]].forEach(function (entry) {
      var node = byId(entry[0]);
      if (node) node.disabled = busy || !writable || !entry[1];
    });
    ["reference-share", "reference-refresh", "reference-view", "reference-copy-params"].forEach(function (id) {
      var node = byId(id);
      if (node) node.disabled = busy;
    });
    var viewer = byId("reference-open-viewer");
    if (viewer) viewer.disabled = busy || !lastView;
  }
  function setBusy(value) {
    busy = value;
    updateAvailability();
  }
  function line(text) { return String(text == null ? "" : text).split(/\r?\n/); }

  async function loadReferences(force) {
    if (busy && !force) return;
    if (referencesLoading && !force) return;
    referencesLoading = true;
    var token = ++referencesToken;
    try {
      var body = await api.getJson("/map/references");
      if (token !== referencesToken) return;
      var next = body.references || [];
      var select = byId("reference-select");
      if (!select) return;
      var current = selectedId || select.value;
      references = next;
      select.textContent = "";
      if (!references.length) {
        var empty = document.createElement("option");
        empty.value = "";
        empty.textContent = "（暂无参考图：先在底图工作区载入图片，再点“共享当前底图”）";
        select.appendChild(empty);
        if (selectedId) clearView();
        selectedId = "";
      } else {
        references.forEach(function (ref) {
          var option = document.createElement("option");
          option.value = ref.referenceId;
          option.textContent = (ref.fileName || "参考图") + " · " + ref.cellCount + " 格 · "
            + String(ref.referenceId).slice(0, 8);
          select.appendChild(option);
        });
        var nextSelected = references.some(function (ref) { return ref.referenceId === current; })
          ? current : references[0].referenceId;
        if (nextSelected !== selectedId) clearView();
        selectedId = nextSelected;
        select.value = selectedId;
      }
      updateParams();
      updateAvailability();
    } catch (e) {
      if (token === referencesToken) status("参考图列表读取失败：" + e.message, "err");
    } finally {
      referencesLoading = false;
    }
  }

  async function refreshOverview(force) {
    if (overviewLoading && !force) return;
    overviewLoading = true;
    var token = ++overviewToken;
    var frozen = signature();
    try {
      var body = await api.getJson(api.withTarget("/map/overview", frozenTarget()));
      // ★ 迟到的词表响应不得覆盖换分支/换节点后的当前词表。
      if (token !== overviewToken || frozen !== signature()) return;
      terrains = body.terrainTypes || [];
      groups = body.pathwayGroups || [];
      fillTerrainOptions();
      fillKindOptions();
      updateDefaultTerrainNote();
      updateParams();
      updateAvailability();
    } catch (e) {
      if (token === overviewToken) status("地形/通路组词表读取失败：" + e.message, "err");
    } finally {
      overviewLoading = false;
    }
  }

  function fillTerrainOptions() {
    var select = byId("reference-paint-terrain");
    if (!select) return;
    var previous = select.value;
    select.textContent = "";
    if (!terrains.length) {
      var empty = document.createElement("option");
      empty.value = "";
      empty.textContent = "（地形词表未加载或为空）";
      select.appendChild(empty);
      select.value = "";
      return;
    }
    terrains.forEach(function (terrain) {
      var option = document.createElement("option");
      option.value = terrain.key;
      option.textContent = terrain.name || terrain.key;
      select.appendChild(option);
    });
    var preferred = previous;
    if (!terrains.some(function (terrain) { return terrain.key === preferred; })) {
      preferred = terrains.some(function (terrain) { return terrain.key === "plains"; }) ? "plains" : terrains[0].key;
    }
    select.value = preferred;
  }

  function fillKindOptions() {
    var select = byId("reference-connect-kind");
    if (!select) return;
    var previous = select.value;
    var list = (groups || []).filter(function (group) { return group && group.id; });
    select.textContent = "";
    if (!list.length) {
      // ★ 当前 pathwayGroups 是唯一词表：空就明确禁用，不伪造 river/road 候选。
      var empty = document.createElement("option");
      empty.value = "";
      empty.textContent = "（当前地图没有已注册通路组；不能写连通性）";
      select.appendChild(empty);
      select.value = "";
      return;
    }
    list.forEach(function (group) {
      var option = document.createElement("option");
      option.value = group.id;
      option.textContent = (group.name || group.id) + "（" + group.id + "）";
      select.appendChild(option);
    });
    select.value = list.some(function (group) { return group.id === previous; })
      ? previous : list[0].id;
  }

  function updateDefaultTerrainNote() {
    var node = byId("reference-default-terrain-note");
    if (!node) return;
    var key = val("reference-paint-terrain");
    var definition = terrains.find(function (terrain) { return terrain.key === key; });
    if (!key || !definition) {
      node.textContent = "创建缺失格使用的默认地形：尚未加载词表（不会发送）。";
      return;
    }
    node.textContent = "创建缺失格使用的默认地形：" + (definition.name || definition.key)
      + "（" + definition.key + "）；只补当前地图差集里的缺失格，不覆盖已有格。";
  }

  async function share() {
    if (busy) return;
    if (!window.SimosMapPng || !window.SimosMapPng.getReferenceInput) {
      status("底图模块尚未就绪", "err");
      return;
    }
    var input;
    try { input = window.SimosMapPng.getReferenceInput(); }
    catch (e) { status(e.message, "warn"); return; }
    var frozen = frozenTarget();
    var sourceVersion = currentSourceVersion();
    setBusy(true);
    status("正在共享参考图（含 " + input.grid.length + " 格格网）…", "muted");
    try {
      var imageBase64 = await window.SimosMapPng.blobBase64(input.blob);
      if (sourceVersion !== currentSourceVersion()) {
        status("底图已变化，未发送共享；请对当前原图重新共享。", "warn");
        return;
      }
      if (!sameTarget(frozen, frozenTarget())) {
        status("世界节点已变化，未发送共享；请重新点击。", "warn");
        return;
      }
      var body = {
        fileName: input.fileName,
        imageBase64: imageBase64,
        cells: input.grid.map(function (cell) {
          return { q: cell.q, r: cell.r, imageX: cell.imageX, imageY: cell.imageY, imageRadius: cell.imageRadius };
        })
      };
      var result = await api.postJson("/map/references", body);
      var nextId = result && result.reference ? result.reference.referenceId : "";
      if (!nextId) { status("共享返回缺少 referenceId，未切换选择", "err"); return; }
      if (sourceVersion !== currentSourceVersion() || !sameTarget(frozen, frozenTarget())) {
        // 工件已经发布（宿主输入工件，不写世界），但当前上下文已变：不自动把旧结果塞进当前选择。
        status("参考图已发布，但底图/节点已变化；请在列表中手动选择。", "warn");
        await loadReferences(true);
        return;
      }
      selectedId = nextId;
      await loadReferences(true);
      clearView();
      status("已共享参考图 " + String(selectedId).slice(0, 10) + "（" + input.grid.length + " 格）", "ok");
      viewStatus("参考图已共享。点“查看概览/局部”开始读图。", "muted");
    } catch (e) {
      status("共享参考图未成功：" + e.message, "err");
    } finally {
      setBusy(false);
    }
  }

  async function view() {
    var id = selectedId || val("reference-select");
    if (!id) { status("先共享或选择一个参考图", "warn"); return; }
    var rawQ = val("reference-q").trim();
    var rawR = val("reference-r").trim();
    if ((rawQ === "") !== (rawR === "")) { status("中心 q 与 r 必须同时给，或都不给看概览", "warn"); return; }
    var q = rawQ === "" ? null : Number(rawQ);
    var r = rawR === "" ? null : Number(rawR);
    if (q !== null && (!Number.isInteger(q) || !Number.isInteger(r))) {
      status("中心 q/r 必须是整数", "warn");
      return;
    }
    var radius = Number(val("reference-radius"));
    if (!Number.isInteger(radius) || radius < 1 || radius > 8) {
      status("局部半径必须是 1..8 的整数", "warn");
      return;
    }
    var path = "/map/reference?referenceId=" + encodeURIComponent(id);
    if (q !== null) path += "&q=" + q + "&r=" + r;
    path += "&radius=" + radius + "&terrainOverlay=" + (check("reference-overlay") ? "true" : "false");
    var token = ++viewToken;
    var frozen = signature();
    var sourceVersion = currentSourceVersion();
    viewStatus("正在读取参考图视图…", "muted");
    try {
      var body = await api.getJson(api.withTarget(path, frozenTarget()));
      // ★ 代次隔离：迟到的视图不能覆盖换 referenceId / 换分支节点 / 换源图后的当前界面。
      if (token !== viewToken || frozen !== signature() || sourceVersion !== currentSourceVersion()
        || id !== (selectedId || val("reference-select"))) return;
      lastView = body;
      var source = "data:image/png;base64," + body.imageBase64;
      ["reference-image", "reference-viewer-image"].forEach(function (id) {
        var image = byId(id);
        if (image) { image.src = source; image.hidden = false; }
      });
      viewStatus("参考 " + String(body.referenceId).slice(0, 10) + " · " + body.branch + "@" + body.revision
        + " · " + (body.overview ? "概览" : "中心 " + q + "," + r + " 半径 " + radius)
        + " · 输出 " + body.image.width + "×" + body.image.height
        + (body.overview ? "" : "；可点图或点“大图查看”"), "ok");
      updateParams();
      updateAvailability();
    } catch (e) {
      if (token === viewToken) viewStatus("参考图查看未成功：" + e.message, "err");
    }
  }

  function openViewer() {
    var dialog = byId("reference-viewer-dialog");
    var image = byId("reference-viewer-image");
    if (!lastView || !image || !image.getAttribute("src")) {
      status("先查看一张参考图，再打开大图。", "warn");
      return;
    }
    if (dialog && dialog.showModal && !dialog.open) dialog.showModal();
    else if (dialog) dialog.setAttribute("open", "");
  }

  function closeViewer() {
    var dialog = byId("reference-viewer-dialog");
    if (dialog && dialog.close && dialog.open) dialog.close();
    else if (dialog) dialog.removeAttribute("open");
  }

  function pickCell(event, imageId) {
    if (!lastView || lastView.overview) return;
    var image = byId(imageId || "reference-image");
    if (!image) return;
    var rect = image.getBoundingClientRect();
    if (!rect.width || !rect.height || !lastView.image || !lastView.sourceCrop) return;
    var px = (event.clientX - rect.left) * lastView.image.width / rect.width;
    var py = (event.clientY - rect.top) * lastView.image.height / rect.height;
    var sx = lastView.sourceCrop.x + px * lastView.sourceCrop.width / lastView.image.width;
    var sy = lastView.sourceCrop.y + py * lastView.sourceCrop.height / lastView.image.height;
    var target = byId("reference-click-target").value;
    var found = null;
    (lastView.cells || []).some(function (cell) {
      if (CORE && CORE.insideHex(sx - cell.imageX, sy - cell.imageY, cell.imageRadius)) {
        found = cell;
        return true;
      }
      return false;
    });
    if (!found) { status("点击位置不在当前局部图已画出的格内", "warn"); return; }
    var area = byId(target === "connect" ? "reference-connect-hexes" : "reference-paint-cells");
    if (!area) return;
    area.value += (area.value.trim() ? "\n" : "") + found.q + "," + found.r;
    updateParams();
    status("已加入格 " + found.q + "," + found.r + "（" + (target === "connect" ? "连接格链" : "地形选区") + "）", "ok");
  }

  function parseCells(areaId, allowHeight) {
    var out = [];
    line(val(areaId)).forEach(function (raw, index) {
      var text = raw.trim();
      if (!text) return;
      var parts = text.split(/[\s,]+/);
      if (parts.length < 2 || parts.length > (allowHeight ? 3 : 2)) {
        throw new Error("第 " + (index + 1) + " 行格式应为 q,r" + (allowHeight ? "[,height]" : ""));
      }
      var q = Number(parts[0]);
      var r = Number(parts[1]);
      if (!Number.isInteger(q) || !Number.isInteger(r)) {
        throw new Error("第 " + (index + 1) + " 行 q/r 必须是整数");
      }
      var cell = { q: q, r: r };
      if (allowHeight && parts.length === 3) {
        var height = Number(parts[2]);
        if (!isFinite(height) || height < 0 || height > 1) {
          throw new Error("第 " + (index + 1) + " 行 height 必须是 0..1 的有限数");
        }
        cell.height = height;
      }
      out.push(cell);
    });
    return out;
  }

  /** 成功提示前比较实际提交 ref 与当前游标；同 branch 的历史节点也不能冒充“当前目标成功”。 */
  function refMatchesCurrent(ref) {
    if (!ref) return false;
    var current = stateNow();
    return ref.branch === current.branch && Number(ref.revision) === Number(current.revision);
  }

  async function write(type, payload, label, refreshView) {
    if (busy) return;
    if (!isWritable()) {
      status("当前不是地图编辑/底图子功能，或当前节点不是可写 head；不提交。", "warn");
      return;
    }
    var frozen = frozenTarget();
    setBusy(true);
    status("正在提交 " + label + "…", "muted");
    try {
      // ★ 与 app.writeCommand(…, frozen) 之间没有 await：同步捕获同一 branch/revision。
      if (!sameTarget(frozen, frozenTarget())) {
        status("目标节点已变化，未提交；请重新操作。", "warn");
        return;
      }
      var result = await app.writeCommand(type, payload, frozen);
      if (!result || !result.ok) {
        status(label + "未成功：" + (result && result.message ? result.message : "未知错误"), "err");
        return;
      }
      var ref = result.body && result.body.ref;
      if (refMatchesCurrent(ref)) {
        status(label + "成功" + (ref ? "：" + ref.branch + "@" + ref.revision : ""), "ok");
      } else {
        var current = stateNow();
        status(label + "已写入 " + (ref ? ref.branch + "@" + ref.revision : "确认时节点")
          + "；当前游标为 " + current.branch + "@" + current.revision + "，请核对。", "warn");
      }
      await refreshOverview(true);
      if (refreshView) await view();
      updateParams();
    } finally {
      setBusy(false);
    }
  }

  async function paintTerrain() {
    var terrain = val("reference-paint-terrain");
    if (!terrain || !terrains.length) { status("地形词表尚未加载；刷新参考图/词表后再试", "warn"); return; }
    var cells;
    try { cells = parseCells("reference-paint-cells", true); }
    catch (e) { status(e.message, "warn"); return; }
    if (!cells.length) { status("地形选区为空：点图或直接输入 q,r", "warn"); return; }
    var globalHeight = val("reference-paint-height").trim();
    if (globalHeight !== "") {
      var height = Number(globalHeight);
      if (!isFinite(height) || height < 0 || height > 1) { status("整批高度必须是 0..1 的有限数", "warn"); return; }
      cells.forEach(function (cell) { cell.height = height; });
    }
    cells.forEach(function (cell) { cell.terrain = terrain; });
    await write("map.PaintTerrain", { cells: cells }, "直接刷地形", !!lastView);
  }

  async function connectHexes() {
    var kind = val("reference-connect-kind");
    var op = val("reference-connect-op");
    if (!kind || !groups.length) { status("当前地图没有已注册通路组；不能写连通性", "warn"); return; }
    if (!op) { status("先选 op", "warn"); return; }
    var hexes;
    try { hexes = parseCells("reference-connect-hexes", false); }
    catch (e) { status(e.message, "warn"); return; }
    if (hexes.length < 2) { status("连接格链至少需要 2 个格（按顺序相邻）", "warn"); return; }
    await write("map.ConnectHexes", { kind: kind, hexes: hexes, op: op }, "直接改连通性", !!lastView);
  }

  async function createMissing() {
    if (busy) return;
    if (!window.SimosMapPng || !window.SimosMapPng.getReferenceInput) {
      status("底图模块尚未就绪", "err");
      return;
    }
    var terrain = val("reference-paint-terrain");
    if (!terrain || !terrains.length) { status("地形词表尚未加载；不能创建缺失格", "warn"); return; }
    var input;
    try { input = window.SimosMapPng.getReferenceInput(); }
    catch (e) { status(e.message, "warn"); return; }
    // ★ 第一个 await 之前独占，并冻结目标 + 源图；读取差集期间分支/源图变化就不发送。
    var frozen = frozenTarget();
    var sourceVersion = currentSourceVersion();
    setBusy(true);
    status("正在读取当前地图，计算缺失格…", "muted");
    try {
      if (!isWritable()) { status("当前节点不是可写 head；不创建。", "warn"); return; }
      var body = await api.getJson(api.withTarget("/map/hexes", frozen));
      if (!sameTarget(frozen, frozenTarget()) || sourceVersion !== currentSourceVersion()) {
        status("读取期间分支/节点或底图已变化；未发送创建格网。", "warn");
        return;
      }
      var existing = {};
      (body.hexes || []).forEach(function (hex) { existing[hex.q + "_" + hex.r] = true; });
      // ★ 只取当前地图的差集：已有格（含用户改过的格）一律不进入 ImportRaster。
      var missing = input.grid.filter(function (cell) { return !existing[cell.q + "_" + cell.r]; });
      if (!missing.length) { status("当前格网没有缺失格，无需创建。", "ok"); return; }
      if (missing.length > 12000) { status("缺失格超过 12000，拒绝发送", "err"); return; }
      var definition = terrains.find(function (item) { return item.key === terrain; });
      var height = CORE && CORE.heightOf ? CORE.heightOf(definition) : 0.4;
      var cells = missing.map(function (cell) {
        return { q: cell.q, r: cell.r, terrain: terrain, height: height };
      });
      // ★ 与 app.writeCommand(…, frozen) 之间没有 await：同步捕获同一 branch/revision。
      if (!sameTarget(frozen, frozenTarget()) || sourceVersion !== currentSourceVersion()) {
        status("发送前分支/节点或底图已变化；未提交旧差集。", "warn");
        return;
      }
      var result = await app.writeCommand(
        "map.ImportRaster", { mode: "merge", cells: cells }, frozen);
      if (!result || !result.ok) {
        status("创建缺失格未成功：" + (result && result.message ? result.message : "未知错误"), "err");
        return;
      }
      var ref = result.body && result.body.ref;
      if (refMatchesCurrent(ref)) {
        status("创建缺失格成功：" + cells.length + " 格" + (ref ? "，写入 " + ref.branch + "@" + ref.revision : ""), "ok");
      } else {
        var current = stateNow();
        status("创建缺失格已写入 " + (ref ? ref.branch + "@" + ref.revision : "确认时节点")
          + "（" + cells.length + " 格）；当前游标为 " + current.branch + "@" + current.revision + "，请核对。", "warn");
      }
      await refreshOverview(true);
      updateParams();
    } catch (e) {
      status("创建缺失格未成功：" + e.message, "err");
    } finally {
      setBusy(false);
    }
  }

  function safeCells(areaId, allowHeight) {
    try { return parseCells(areaId, allowHeight); }
    catch (e) { return null; }
  }

  function updateParams() {
    var area = byId("reference-params");
    if (!area) return;
    var state = stateNow();
    var id = selectedId || val("reference-select");
    var rawQ = val("reference-q").trim();
    var rawR = val("reference-r").trim();
    var q = rawQ === "" ? null : Number(rawQ);
    var r = rawR === "" ? null : Number(rawR);
    var paint = safeCells("reference-paint-cells", true);
    var connect = safeCells("reference-connect-hexes", false);
    var globalHeight = val("reference-paint-height").trim();
    var referenceArgs = {
      branch: state.branch === undefined ? null : state.branch,
      revision: state.revision === undefined ? null : state.revision,
      radius: Number(val("reference-radius")) || 4,
      terrainOverlay: check("reference-overlay")
    };
    if (id) referenceArgs.referenceId = id;
    if (q !== null && r !== null) { referenceArgs.q = q; referenceArgs.r = r; }
    var paintArgs = {
      branch: state.branch === undefined ? null : state.branch,
      expectedRevision: state.revision === undefined ? null : state.revision,
      cells: paint === null ? [] : paint.map(function (cell) {
        var out = { q: cell.q, r: cell.r, terrain: val("reference-paint-terrain") };
        if (globalHeight !== "") out.height = Number(globalHeight);
        else if (cell.height !== undefined) out.height = cell.height;
        return out;
      })
    };
    var connectArgs = {
      branch: state.branch === undefined ? null : state.branch,
      expectedRevision: state.revision === undefined ? null : state.revision,
      kind: val("reference-connect-kind"),
      op: val("reference-connect-op"),
      hexes: connect === null ? [] : connect.map(function (cell) { return { q: cell.q, r: cell.r }; })
    };
    var payload = {
      reference: { tool: "simos.map.reference", arguments: referenceArgs },
      paintTerrain: { tool: "map.PaintTerrain", arguments: paintArgs },
      connectHexes: { tool: "map.ConnectHexes", arguments: connectArgs }
    };
    if (paint === null) payload.paintTerrain.note = "地形选区格式有误，请先修正";
    if (connect === null) payload.connectHexes.note = "连接格链格式有误，请先修正";
    area.value = JSON.stringify(payload, null, 2);
  }

  async function copyParams() {
    var area = byId("reference-params");
    if (!area) return;
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(area.value);
      } else {
        area.focus();
        area.select();
        document.execCommand("copy");
      }
      status("工具参数已复制（可直接按 tool/arguments 调用）", "ok");
    } catch (e) {
      status("复制失败：请手动选中参数框复制", "warn");
    }
  }

  function panelVisible() {
    var panel = byId("png-overlay-controls");
    return !!panel && !panel.hidden;
  }

  async function onPanelShown() {
    if (!panelVisible()) return;
    updateAvailability();
    await loadReferences();
    await refreshOverview();
    updateAvailability();
  }

  function bind() {
    if (bound || !byId("reference-tools")) return;
    bound = true;
    byId("reference-share").addEventListener("click", share);
    byId("reference-refresh").addEventListener("click", onPanelShown);
    byId("reference-view").addEventListener("click", view);
    byId("reference-open-viewer").addEventListener("click", openViewer);
    byId("reference-viewer-close").addEventListener("click", closeViewer);
    byId("reference-image").addEventListener("click", function (event) { pickCell(event, "reference-image"); });
    byId("reference-viewer-image").addEventListener("click", function (event) { pickCell(event, "reference-viewer-image"); });
    byId("reference-paint-apply").addEventListener("click", paintTerrain);
    byId("reference-connect-apply").addEventListener("click", connectHexes);
    byId("reference-create-missing").addEventListener("click", createMissing);
    byId("reference-copy-params").addEventListener("click", copyParams);
    byId("reference-select").addEventListener("change", function () {
      selectedId = val("reference-select");
      clearView();
      viewStatus("已选择参考图；点“查看概览/局部”。", "muted");
      updateParams();
    });
    ["reference-q", "reference-r", "reference-radius", "reference-overlay", "reference-paint-cells",
      "reference-paint-terrain", "reference-paint-height", "reference-connect-hexes",
      "reference-connect-kind", "reference-connect-op", "reference-click-target"].forEach(function (id) {
      var node = byId(id);
      if (node) node.addEventListener("input", function () {
        if (id === "reference-paint-terrain") updateDefaultTerrainNote();
        updateParams();
      });
    });
    if (app && app.onStateChange) {
      var lastSignature = signature();
      app.onStateChange(function () {
        var next = signature();
        if (next !== lastSignature) {
          lastSignature = next;
          // ★ 旧节点的图不能留在屏幕上冒充当前状态；用户重新点“查看”即可（不自动用旧期望版本提交）。
          clearView();
          viewStatus("世界节点已变化；请重新查看参考图。", "muted");
        }
        updateAvailability();
        updateParams();
      });
    }
    var panel = byId("png-overlay-controls");
    if (panel && panel.tagName && window.MutationObserver) {
      new MutationObserver(function () {
        if (panelVisible() && !panelSeen) {
          panelSeen = true;
          onPanelShown();
        } else if (!panelVisible()) {
          panelSeen = false;
          updateAvailability();
        }
      }).observe(panel, { attributes: true, attributeFilter: ["hidden"] });
      if (panelVisible()) { panelSeen = true; onPanelShown(); }
    }
    updateDefaultTerrainNote();
    updateAvailability();
    updateParams();
  }

  window.SimosMapReferenceTools = {
    refresh: loadReferences,
    view: view,
    share: share,
    updateParams: updateParams,
    debugState: function () {
      return { references: references.length, selectedId: selectedId, busy: busy, hasView: !!lastView,
        terrains: terrains.length, groups: groups.length, referencesLoading: referencesLoading,
        overviewLoading: overviewLoading };
    }
  };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", bind); else bind();
})();
