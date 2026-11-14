// nation-editor.js —— 国家编辑（模块自有资产，住 simos-gov）。
// 一次保存走 app.js 的共享 writeEditorTool（冻结 target、模式门控、成功/冲突同一状态机）；
// 读回走固定 HTTP 桥 simos.gov.nations。本文件不写第二套写入状态机。
(function () {
  "use strict";

  var loadGeneration = 0;

  function byId(id) {
    return document.getElementById(id);
  }

  function setStatus(message, tone) {
    var node = byId("nation-editor-status");
    if (!node) return;
    node.textContent = message;
    node.className = "status " + (tone || "muted");
  }

  function setResult(value) {
    var node = byId("nation-editor-result");
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
    var image = byId("nation-editor-image");
    if (!image) return;
    var images = response && response.images;
    if (images && images.length && images[0].base64) {
      image.src = "data:" + (images[0].mediaType || "image/png") + ";base64," + images[0].base64;
      image.hidden = false;
    } else {
      image.hidden = true;
      image.removeAttribute("src");
    }
  }

  function refreshList(frozenTarget) {
    var select = byId("nation-editor-select");
    if (!select) return Promise.resolve();
    var generation = ++loadGeneration;
    var frozen = frozenTarget || currentTarget();
    return getJson("/api/editor/nation" + queryFor(frozen))
      .then(function (body) {
        if (generation !== loadGeneration || !sameTarget(frozen, currentTarget())) return;
        var result = (body && body.result) || {};
        var nations = result.nations || [];
        select.textContent = "";
        var blank = document.createElement("option");
        blank.value = "";
        blank.textContent = nations.length ? "（选择国家，共 " + nations.length + " 个）" : "（还没有国家）";
        select.appendChild(blank);
        nations.forEach(function (nation) {
          var option = document.createElement("option");
          option.value = nation.nationId;
          option.textContent = nation.name + " · " + nation.nationId;
          select.appendChild(option);
        });
        if (nations.length) setStatus("已读回 " + nations.length + " 个国家。", "muted");
      })
      .catch(function (e) {
        if (generation === loadGeneration && sameTarget(frozen, currentTarget())) {
          setStatus("读国家列表失败：" + e.message, "warn");
        }
      });
  }

  function editJson(view) {
    return {
      mode: "update",
      nationId: view.nationId,
      name: view.name,
      color: view.color || "#808080",
      hexes: view.hexes || [],
      territoryMode: "replace",
      overlapPolicy: "reject",
      reason: "网页载入后编辑",
    };
  }

  function loadNation(nationId, frozenTarget) {
    if (!nationId) {
      setStatus("先选择一个国家。", "warn");
      return Promise.resolve();
    }
    var generation = ++loadGeneration;
    var frozen = frozenTarget || currentTarget();
    setStatus("载入国家 " + nationId + " …", "muted");
    var url = "/api/editor/nation" + queryFor(frozen)
      + (queryFor(frozen) ? "&" : "?") + "nationId=" + encodeURIComponent(nationId) + "&render=true";
    return getJson(url)
      .then(function (body) {
        if (generation !== loadGeneration || !sameTarget(frozen, currentTarget())) return;
        var result = (body && body.result) || {};
        var view = result.nations && result.nations[0];
        if (!view) throw new Error("读回没有国家视图");
        var json = byId("nation-editor-json");
        if (json) json.value = JSON.stringify(editJson(view), null, 2);
        showImage(body);
        setResult(result);
        setStatus("已载入 " + view.name + "（hex " + view.hexCount + "，GOV " + (view.govUnitIds || []).length + " 个）。", "ok");
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
      nationId: "demo-nation",
      name: "演示国家（虚构）",
      color: "#3366CC",
      hexes: hex ? [hex] : [],
      territoryMode: "replace",
      overlapPolicy: "reject",
      reason: "网页新建国家（虚构样例）",
    };
    var json = byId("nation-editor-json");
    if (json) json.value = JSON.stringify(template, null, 2);
    setStatus(hex ? "已建空白模板，并把当前选中格 (" + hex.q + "," + hex.r + ") 放进领土。" : "已建空白模板；先在图上选中一格，再点“当前格加入领土”。", "muted");
  }

  function addSelectedHex() {
    var json = byId("nation-editor-json");
    if (!json) return;
    var hex = selectedHex();
    if (!hex) {
      setStatus("当前没有选中的六角格。", "warn");
      return;
    }
    var payload;
    try { payload = JSON.parse(json.value); } catch (e) { setStatus("JSON 解析失败：" + e.message, "warn"); return; }
    if (!Array.isArray(payload.hexes)) payload.hexes = [];
    var exists = payload.hexes.some(function (item) { return item && item.q === hex.q && item.r === hex.r; });
    if (!exists) payload.hexes.push(hex);
    json.value = JSON.stringify(payload, null, 2);
    setStatus("已加入 (" + hex.q + "," + hex.r + ")，共 " + payload.hexes.length + " 格。", "muted");
  }

  function save() {
    var json = byId("nation-editor-json");
    if (!json) return;
    var payload;
    try { payload = JSON.parse(json.value); } catch (e) { setStatus("JSON 解析失败：" + e.message, "warn"); return; }
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      setStatus("顶层必须是 JSON 对象。", "warn");
      return;
    }
    // 冻结本次提交目标；branch/expectedRevision 由 host helper 覆盖，JSON 改不了目标节点。
    var frozen = currentTarget();
    setStatus("提交中…", "muted");
    window.SimosApp.writeEditorTool("nation", payload, frozen).then(function (outcome) {
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
      setStatus("已保存（revision " + revision + "）。", "ok");
      var nationId = result.nationId || payload.nationId;
      // ★ 用 host 返回的**同次提交后目标**（含新 writeEpoch）做列表/读回；不能用提交前 frozen 比较。
      var after = outcome.completedTarget || currentTarget();
      refreshList(after).then(function () {
        if (nationId && after && sameTarget(after, currentTarget())) loadNation(nationId, after);
      });
    });
  }

  function boot() {
    if (!byId("nation-editor")) return;
    var load = byId("nation-editor-load");
    var create = byId("nation-editor-new");
    var add = byId("nation-editor-hex");
    var saveButton = byId("nation-editor-save");
    var refresh = byId("nation-editor-refresh");
    var select = byId("nation-editor-select");
    if (load) load.addEventListener("click", function () { loadNation(select ? select.value : null); });
    if (create) create.addEventListener("click", newTemplate);
    if (add) add.addEventListener("click", addSelectedHex);
    if (saveButton) saveButton.addEventListener("click", save);
    if (refresh) refresh.addEventListener("click", function () { refreshList().then(function () { if (select && select.value) loadNation(select.value); }); });
    if (select) select.addEventListener("change", function () { if (select.value) loadNation(select.value); });
    refreshList();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
