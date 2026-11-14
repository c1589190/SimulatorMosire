// formation-editor.js —— 单支军队完整编制编辑（模块自有资产，住 simos-army）。
// 一次保存直接调用固定领域工具 simos.army.editFormation；读回用 simos.army.formation（同一棵树 + PNG）。
// 不复制后端计划：JSON 原样交领域工具；载入现有对象时只导出可保存字段（不塞 view-only 字段）。
(function () {
  "use strict";

  var loadGeneration = 0;
  var zoom = 1.0;
  var viewerFit = true;
  var viewerZoom = 1.0;

  function byId(id) {
    return document.getElementById(id);
  }

  function setStatus(message, tone) {
    var node = byId("formation-editor-status");
    if (!node) return;
    node.textContent = message;
    node.className = "status " + (tone || "muted");
  }

  function setResult(value) {
    var node = byId("formation-editor-result");
    if (!node) return;
    node.textContent = typeof value === "string" ? value : JSON.stringify(value, null, 2);
  }

  function currentTarget() {
    return window.SimosApp && typeof window.SimosApp.target === "function" ? window.SimosApp.target() : null;
  }

  function sameTarget(a, b) {
    return !!a && !!b
      && a.branch === b.branch
      && Number(a.revision) === Number(b.revision)
      && (a.writeEpoch === undefined || b.writeEpoch === undefined || Number(a.writeEpoch) === Number(b.writeEpoch));
  }

  function queryFor(target) {
    var params = [];
    if (target && target.branch) {
      params.push("branch=" + encodeURIComponent(target.branch));
      if (target.revision !== null && target.revision !== undefined) {
        params.push("revision=" + encodeURIComponent(target.revision));
      }
    }
    return params.length ? "?" + params.join("&") : "";
  }

  function getJson(path) {
    return fetch(path, { method: "GET", headers: { Accept: "application/json" } }).then(function (response) {
      return response.text().then(function (text) {
        var body = null;
        try { body = text ? JSON.parse(text) : null; } catch (e) { body = { error: text }; }
        if (!response.ok) {
          var error = new Error(body && (body.error || body.message) ? (body.error || body.message) : response.status + " " + response.statusText);
          error.body = body;
          throw error;
        }
        return body;
      });
    });
  }

  function selectedHex() {
    var state = window.SimosApp && typeof window.SimosApp.getState === "function" ? window.SimosApp.getState() : null;
    var selection = state && state.selection;
    if (!selection || selection.kind !== "hex") return null;
    return { q: selection.q, r: selection.r };
  }

  function showImage(response) {
    var image = byId("formation-editor-image");
    if (!image) return;
    var images = response && response.images;
    if (images && images.length && images[0].base64) {
      image.src = "data:" + (images[0].mediaType || "image/png") + ";base64," + images[0].base64;
      image.hidden = false;
      applyZoom();
    } else {
      image.hidden = true;
      image.removeAttribute("src");
    }
  }

  function applyZoom() {
    var image = byId("formation-editor-image");
    if (!image) return;
    image.style.maxWidth = "none";
    image.style.width = Math.max(20, Math.round(zoom * 100)) + "%";
  }

  function changeZoom(delta) {
    zoom = Math.max(0.2, Math.min(4.0, Math.round((zoom + delta) * 10) / 10));
    applyZoom();
    setStatus("编制图缩放 " + Math.round(zoom * 100) + "%。", "muted");
  }

  function refreshList(frozenTarget) {
    var select = byId("formation-editor-select");
    if (!select) return Promise.resolve();
    var generation = ++loadGeneration;
    var frozen = frozenTarget || currentTarget();
    return getJson("/api/editor/formation" + queryFor(frozen))
      .then(function (body) {
        if (generation !== loadGeneration || !sameTarget(frozen, currentTarget())) return;
        var result = (body && body.result) || {};
        var armies = result.armies || [];
        select.textContent = "";
        var blank = document.createElement("option");
        blank.value = "";
        blank.textContent = armies.length ? "（选择军队，共 " + armies.length + " 支）" : "（还没有军队）";
        select.appendChild(blank);
        armies.forEach(function (army) {
          var option = document.createElement("option");
          option.value = army.armyId;
          option.textContent = army.name + " · " + army.armyId + " · " + army.nodeCount + " 节点";
          select.appendChild(option);
        });
        if (armies.length) setStatus("已读回 " + armies.length + " 支军队（同一份 unit/social/army 树）。", "muted");
      })
      .catch(function (e) {
        if (generation === loadGeneration && sameTarget(frozen, currentTarget())) {
          setStatus("读军队列表失败：" + e.message, "warn");
        }
      });
  }

  function equipmentObject(list) {
    var out = {};
    (list || []).forEach(function (entry) {
      if (entry && entry.type !== undefined) out[entry.type] = entry.amount;
    });
    return out;
  }

  function exportNode(id, byIdMap, children) {
    var node = byIdMap[id];
    if (!node) return null;
    return {
      id: node.id,
      name: node.name,
      role: node.role,
      position: node.position || undefined,
      personnel: node.directPersonnel,
      equipment: equipmentObject(node.equipment),
      speed: node.speed,
      mobilityPerMille: node.mobilityPerMille,
      attached: node.attached,
      children: (children[id] || []).map(function (childId) { return exportNode(childId, byIdMap, children); }).filter(Boolean),
    };
  }

  function editJson(view) {
    var byIdMap = {};
    var children = {};
    (view.nodes || []).forEach(function (node) {
      byIdMap[node.id] = node;
      if (node.parentId) {
        if (!children[node.parentId]) children[node.parentId] = [];
        children[node.parentId].push(node.id);
      }
    });
    return {
      mode: "update",
      armyId: view.armyId,
      name: view.name,
      masterGov: view.masterGov || undefined,
      root: exportNode(view.rootUnitId, byIdMap, children),
      reason: "网页载入后编辑",
    };
  }

  function loadArmy(armyId, frozenTarget) {
    if (!armyId) {
      setStatus("先选择一支军队。", "warn");
      return Promise.resolve();
    }
    var generation = ++loadGeneration;
    var frozen = frozenTarget || currentTarget();
    setStatus("载入军队 " + armyId + " …", "muted");
    var url = "/api/editor/formation" + queryFor(frozen)
      + (queryFor(frozen) ? "&" : "?") + "armyId=" + encodeURIComponent(armyId) + "&render=true";
    return getJson(url)
      .then(function (body) {
        if (generation !== loadGeneration || !sameTarget(frozen, currentTarget())) return;
        var result = (body && body.result) || {};
        var view = result.armies && result.armies[0];
        if (!view) throw new Error("读回没有军队视图");
        var json = byId("formation-editor-json");
        if (json) json.value = JSON.stringify(editJson(view), null, 2);
        showImage(body);
        setResult(result);
        setStatus("已载入 " + view.name + "（" + view.nodeCount + " 节点，直接人数 " + view.directPersonnel + "，子树合计 " + view.subtreePersonnel + "）。", "ok");
      })
      .catch(function (e) {
        if (generation === loadGeneration && sameTarget(frozen, currentTarget())) {
          setStatus("载入失败：" + e.message, "warn");
        }
      });
  }

  function newTemplate() {
    var hex = selectedHex();
    var template = {
      mode: "create",
      armyId: "demo-division",
      name: "演示师（虚构编制）",
      root: {
        id: "demo-div",
        name: "第1师",
        role: "DIVISION",
        position: hex || undefined,
        children: [
          {
            id: "demo-bde-1",
            name: "第1旅",
            role: "BRIGADE",
            children: [
              { id: "demo-bn-1", name: "第1营", role: "BATTALION", personnel: 500, equipment: { "步枪": 450, "卡车": 20 } },
              { id: "demo-bn-2", name: "第2营", role: "BATTALION", personnel: 500 },
            ],
          },
        ],
      },
      reason: "网页新建师旅营（自带样例为虚构编制）",
    };
    var json = byId("formation-editor-json");
    if (json) json.value = JSON.stringify(template, null, 2);
    setStatus(hex ? "已建虚构师旅营模板，root 落点 = 当前选中格 (" + hex.q + "," + hex.r + ")。" : "已建虚构师旅营模板；先在图上选中一格，再点“当前格设为 root 落点”。", "muted");
  }

  function setRootPosition() {
    var hex = selectedHex();
    var json = byId("formation-editor-json");
    if (!json) return;
    if (!hex) {
      setStatus("当前没有选中的六角格。", "warn");
      return;
    }
    var payload;
    try { payload = JSON.parse(json.value); } catch (e) { setStatus("JSON 解析失败：" + e.message, "warn"); return; }
    if (!payload.root || typeof payload.root !== "object") {
      setStatus("JSON 里没有 root 对象。", "warn");
      return;
    }
    payload.root.position = hex;
    json.value = JSON.stringify(payload, null, 2);
    setStatus("root 落点已设为 (" + hex.q + "," + hex.r + ")。", "muted");
  }

  function addBattalion() {
    var json = byId("formation-editor-json");
    if (!json) return;
    var payload;
    try { payload = JSON.parse(json.value); } catch (e) { setStatus("JSON 解析失败：" + e.message, "warn"); return; }
    if (!payload.root || typeof payload.root !== "object") {
      setStatus("JSON 里没有 root 对象。", "warn");
      return;
    }
    var used = {};
    (function collect(node) {
      if (!node || typeof node !== "object") return;
      if (node.id) used[node.id] = true;
      (node.children || []).forEach(collect);
    })(payload.root);
    var index = 1;
    while (used["demo-bn-new-" + index]) index++;
    var child = { id: "demo-bn-new-" + index, name: "新营 " + index, role: "BATTALION", personnel: 300, equipment: { "步枪": 300 } };
    var target = payload.root;
    if (payload.root.role && String(payload.root.role).toUpperCase().indexOf("BRIGADE") >= 0 && payload.root.children && payload.root.children[0]) {
      target = payload.root.children[0];
    }
    if (!Array.isArray(target.children)) target.children = [];
    target.children.push(child);
    json.value = JSON.stringify(payload, null, 2);
    setStatus("已在 " + target.name + " 下追加 " + child.id + "（未给坐标，默认 attached 并随父位置）。", "muted");
  }

  // ── 大图浮层：复用编辑器里已经取得的同一张 PNG，不发请求、不重读节点 ─────────────
  function viewerImage() {
    return byId("formation-viewer-image");
  }

  function applyViewerSize() {
    var image = viewerImage();
    var label = byId("formation-viewer-zoom-label");
    if (!image || !image.getAttribute("src")) return;
    image.style.maxWidth = "none";
    image.style.height = "auto";
    if (viewerFit) {
      image.style.width = "100%";
      if (label) label.textContent = "适应窗口";
      return;
    }
    var natural = image.naturalWidth > 0 ? image.naturalWidth : 1000;
    image.style.width = Math.round(natural * viewerZoom) + "px";
    if (label) label.textContent = Math.round(viewerZoom * 100) + "%";
  }

  function openFormationViewer() {
    var editorImage = byId("formation-editor-image");
    var viewer = byId("formation-viewer");
    var image = viewerImage();
    if (!editorImage || !viewer || !image || editorImage.hidden || !editorImage.getAttribute("src")) {
      setStatus("先载入或保存一次编制图，再打开大图。", "warn");
      return;
    }
    image.src = editorImage.getAttribute("src");
    viewerFit = true;
    viewerZoom = 1.0;
    applyViewerSize();
    if (typeof viewer.showModal === "function" && !viewer.open) {
      viewer.showModal();
    } else {
      viewer.setAttribute("open", "open");
    }
  }

  function closeFormationViewer() {
    var viewer = byId("formation-viewer");
    if (!viewer) return;
    if (typeof viewer.close === "function" && viewer.open) {
      viewer.close();
    } else {
      viewer.removeAttribute("open");
    }
  }

  function changeViewerZoom(delta) {
    viewerFit = false;
    viewerZoom = Math.max(0.2, Math.min(4.0, Math.round((viewerZoom + delta) * 10) / 10));
    applyViewerSize();
  }

  function save() {
    var json = byId("formation-editor-json");
    if (!json) return;
    var payload;
    try { payload = JSON.parse(json.value); } catch (e) { setStatus("JSON 解析失败：" + e.message, "warn"); return; }
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      setStatus("顶层必须是 JSON 对象。", "warn");
      return;
    }
    var frozen = currentTarget();
    setStatus("提交中…", "muted");
    window.SimosApp.writeEditorTool("formation", payload, frozen).then(function (outcome) {
      if (!outcome.cursorStillCurrent) {
        // 迟到响应：用户已切走，不改当前节点 DOM；既不改状态行也不读回/换图。
        return;
      }
      if (!outcome.ok) {
        setResult(outcome.error && outcome.error.body ? outcome.error.body : outcome.message);
        setStatus((outcome.kind === "conflict" ? "冲突：" : "未保存：") + outcome.message, "warn");
        return;
      }
      var result = (outcome.body && outcome.body.result) || {};
      setResult(result);
      var revision = result.submission && result.submission.ref ? result.submission.ref.revision : "?";
      setStatus("已保存（revision " + revision + "，节点 " + (result.nodeCount || "?") + "，直接人数 " + (result.directPersonnel || 0) + "）。", "ok");
      var armyId = result.armyId || payload.armyId;
      // ★ 同次提交后目标（新 epoch）用于列表/读回；提交前 frozen 只用于判断迟到。
      var after = outcome.completedTarget || currentTarget();
      refreshList(after).then(function () {
        if (armyId && after && sameTarget(after, currentTarget())) loadArmy(armyId, after);
      });
    });
  }

  function boot() {
    if (!byId("formation-editor")) return;
    var load = byId("formation-editor-load");
    var create = byId("formation-editor-new");
    var position = byId("formation-editor-position");
    var add = byId("formation-editor-add-battalion");
    var saveButton = byId("formation-editor-save");
    var refresh = byId("formation-editor-refresh");
    var select = byId("formation-editor-select");
    var zoomIn = byId("formation-editor-zoom-in");
    var zoomOut = byId("formation-editor-zoom-out");
    var zoomReset = byId("formation-editor-zoom-reset");
    if (zoomIn) zoomIn.addEventListener("click", function () { changeZoom(0.25); });
    if (zoomOut) zoomOut.addEventListener("click", function () { changeZoom(-0.25); });
    if (zoomReset) zoomReset.addEventListener("click", function () { zoom = 1.0; applyZoom(); setStatus("编制图缩放 100%。", "muted"); });
    var viewLarge = byId("formation-editor-view-large");
    var viewerClose = byId("formation-viewer-close");
    var viewerFitButton = byId("formation-viewer-fit");
    var viewerOriginal = byId("formation-viewer-original");
    var viewerZoomIn = byId("formation-viewer-zoom-in");
    var viewerZoomOut = byId("formation-viewer-zoom-out");
    var viewerImageNode = viewerImage();
    if (viewLarge) viewLarge.addEventListener("click", openFormationViewer);
    if (viewerClose) viewerClose.addEventListener("click", closeFormationViewer);
    if (viewerFitButton) viewerFitButton.addEventListener("click", function () { viewerFit = true; applyViewerSize(); });
    if (viewerOriginal) viewerOriginal.addEventListener("click", function () { viewerFit = false; viewerZoom = 1.0; applyViewerSize(); });
    if (viewerZoomIn) viewerZoomIn.addEventListener("click", function () { changeViewerZoom(0.25); });
    if (viewerZoomOut) viewerZoomOut.addEventListener("click", function () { changeViewerZoom(-0.25); });
    if (viewerImageNode) viewerImageNode.addEventListener("load", applyViewerSize);
    if (load) load.addEventListener("click", function () { loadArmy(select ? select.value : null); });
    if (create) create.addEventListener("click", newTemplate);
    if (position) position.addEventListener("click", setRootPosition);
    if (add) add.addEventListener("click", addBattalion);
    if (saveButton) saveButton.addEventListener("click", save);
    if (refresh) refresh.addEventListener("click", function () { refreshList().then(function () { if (select && select.value) loadArmy(select.value); }); });
    if (select) select.addEventListener("change", function () { if (select.value) loadArmy(select.value); });
    refreshList();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
