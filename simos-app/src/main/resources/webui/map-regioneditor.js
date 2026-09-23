// map-regioneditor.js —— 区域编辑模式的宿主 UI（M12 第六波：自 map.js 顶层整体搬出）。
//
// 成员（原文行号：map.js 未改动前的 1708-2310）：
//   regionDraftList / setRegionEditStatus / renderRegionEditor / suggestRegionId / newRegionDraft /
//   clearRegionDraft / loadFocusIntoDraft / commitRegionPaint / findSameNameRegion / regionIdExists /
//   requestCreateRegion / submitCreateRegionNow / resolveNameConflictCreateNew / resolveNameConflictMerge /
//   cancelNameConflict / submitCreateRegion / submitUpdateRegion / onLassoCommit / onDotDragCommit /
//   unionHexes / differenceHexes / submitRegionMerge / submitRegionSubtract / armRegionDelete /
//   cancelRegionDelete / submitDeleteRegion / onRegionFocusChanged / wireRegionEditor
//
// ★ 引入顺序：… → map.js → map-mapeditor.js → **map-regioneditor.js** → renderer.js → …。
//   本文件在 map.js 之后（window.SimosMapCore 由 map.js 建立）。
// ★ 函数体与 map.js 原文逐字节相同，唯一一类改动：`active` 实时读 `core.active`（见
//   map-mapeditor.js 文件头；active 是 map.js 的可变绑定，取快照恒为 null）。
// ★ 写路径不变：五条命令仍经 window.SimosApp.writeCommand → /api/command（R8 allowlist）。
// ★ 取数（fetchRegionCached / refreshFocusHexes）与高亮（reloadRegionEditHighlight）仍归 map.js
//   （经 core 取回）——它们同时服务地图核心路径，不在本簇里复制。
// ★ map.js 对本文件经 window.SimosMapRegionEditor.* **惰性**调用。

(function () {
  "use strict";

  // ── 从 map.js 暴露的 window.SimosMapCore 取回（本文件在 map.js 之后引入）────────────
  var core = window.SimosMapCore;
  var app = core.app;
  var host = core.host; // 对象按引用共享：host.regionDraft / regionFocus … 的改写对 map.js 可见
  var fetchRegionCached = core.fetchRegionCached; // 仍在 map.js（区域详情缓存，按 target）
  var refreshFocusHexes = core.refreshFocusHexes; // 仍在 map.js（焦点 hex ⇒ 边界小点层）
  var reloadRegionEditHighlight = core.reloadRegionEditHighlight; // 仍在 map.js（共享高亮路径）
  // ★ active 是可变绑定：函数体里出现的一律写作 core.active（见文件头）。

  // ── 区域编辑模式（M8 T10）：绘新区域 / 改已有区域 hex / 删除（二次确认）────────
  //
  // ★ M8-U1：重叠是**正常状态**，前端绝不加"禁止重叠"的校验或提示（那是缺陷不是贴心）。
  // ★ 写路径唯一：三条命令都经 app.writeCommand（白名单在 app.js 的 writeCommand 把关）。
  // ★ 选区（draft）由渲染器的持久选区层画（松手后仍显示）；拖动中的预览复用 T8 刷子机制。

  function regionDraftList() {
    return Object.keys(host.regionDraft).map(function (key) {
      return host.regionDraft[key];
    });
  }

  function setRegionEditStatus(message, tone) {
    app.statusMessage(app.byId("region-edit-status"), message, tone);
  }

  function renderRegionEditor() {
    var draftNode = app.byId("region-edit-draft");
    if (draftNode) {
      draftNode.textContent = "临时选区：" + regionDraftList().length + " 格";
    }
    var focusNode = app.byId("region-edit-focus");
    if (focusNode) {
      focusNode.textContent = host.regionFocus || "未选中";
    }
    var delName = app.byId("region-delete-name");
    if (delName) {
      delName.textContent = host.regionFocus || "—";
    }
    var opSelect = app.byId("region-edit-op");
    if (opSelect) {
      opSelect.value = host.regionOp;
    }
    var confirm = app.byId("region-delete-confirm");
    if (confirm) {
      confirm.hidden = !host.regionDeleteArmed;
    }
    var updateBtn = app.byId("region-update-submit");
    if (updateBtn) {
      updateBtn.disabled = !host.regionFocus || host.regionEditBusy;
    }
    var loadBtn = app.byId("region-edit-load");
    if (loadBtn) {
      loadBtn.disabled = !host.regionFocus;
    }
    var mergeBtn = app.byId("region-merge");
    if (mergeBtn) {
      mergeBtn.disabled = !host.regionFocus || host.regionEditBusy;
    }
    var excludeBtn = app.byId("region-exclude");
    if (excludeBtn) {
      excludeBtn.disabled = !host.regionFocus || host.regionEditBusy;
    }
    var deleteBtn = app.byId("region-delete");
    if (deleteBtn) {
      deleteBtn.disabled = !host.regionFocus;
    }
    var conflict = app.byId("region-name-conflict");
    if (conflict) {
      conflict.hidden = !host.regionNameConflict;
      if (host.regionNameConflict) {
        var conflictMsg = app.byId("region-name-conflict-msg");
        if (conflictMsg) {
          conflictMsg.textContent =
            "名称「" +
            host.regionNameConflict.name +
            "」已存在（regionId=" +
            host.regionNameConflict.existingId +
            "）——请选择：";
        }
      }
    }
  }

  /** 新区域 id 的**建议值**（可改）：取未被占用的 `region-<n>`（Q3：RegionId 由调用方给）。 */
  function suggestRegionId() {
    var used = {};
    (host.overviewRegions || []).forEach(function (region) {
      if (region && region.id !== undefined && region.id !== null) {
        used[String(region.id)] = true;
      }
    });
    for (var n = 1; n < 10000; n++) {
      var candidate = "region-" + n;
      if (!used[candidate]) {
        return candidate;
      }
    }
    return "region-new";
  }

  function newRegionDraft() {
    if (app.getState().mode !== "region-edit") {
      return;
    }
    host.regionDraft = {};
    host.regionOp = "add";
    host.regionDeleteArmed = false;
    app.setRegionFocus(null);
    core.active.setBrushOp(host.regionOp);
    core.active.setDraftHexes([]);
    core.active.clearFocusHexes();
    var idInput = app.byId("region-create-id");
    if (idInput && !idInput.value.trim()) {
      idInput.value = suggestRegionId();
    }
    var nameInput = app.byId("region-create-name");
    if (nameInput && !nameInput.value.trim()) {
      nameInput.value = "新区域";
    }
    renderRegionEditor();
    reloadRegionEditHighlight();
    setRegionEditStatus(
      "★ 右键拖动=套索创建（flood fill 内部）；Shift+右键拖动=逐格画/擦（临时选区，供合并/剔除）；左键拖动=平移。重叠不报错。",
      "muted"
    );
  }

  function clearRegionDraft() {
    host.regionDraft = {};
    core.active.setDraftHexes([]);
    renderRegionEditor();
    setRegionEditStatus("选区已清空。", "muted");
  }

  async function loadFocusIntoDraft() {
    if (!host.regionFocus) {
      setRegionEditStatus("先在右栏选一个区域。", "warn");
      return null;
    }
    var region = await fetchRegionCached(host.regionFocus);
    host.regionDraft = {};
    (region.hexes || []).forEach(function (h) {
      host.regionDraft[h.q + "_" + h.r] = { q: h.q, r: h.r };
    });
    core.active.setDraftHexes(regionDraftList());
    renderRegionEditor();
    setRegionEditStatus("已载入 " + host.regionFocus + " 的 " + regionDraftList().length + " 格到选区。", "muted");
    return regionDraftList();
  }

  /** Shift+右键逐格画/擦松手：把涂抹的格按当前操作并入/移出**临时选区**（**不发写**，选区只是编辑草稿）。 */
  function commitRegionPaint(painted) {
    if (app.getState().mode !== "region-edit") {
      return null;
    }
    var op = host.regionOp;
    (painted || []).forEach(function (h) {
      if (!h || h.q === undefined || h.r === undefined) {
        return;
      }
      var key = h.q + "_" + h.r;
      if (op === "remove") {
        delete host.regionDraft[key];
      } else {
        host.regionDraft[key] = { q: h.q, r: h.r };
      }
    });
    core.active.setBrushHexes([]);
    core.active.setDraftHexes(regionDraftList());
    var last = painted && painted.length ? painted[painted.length - 1] : null;
    if (last) {
      app.setSelection({ kind: "hex", q: last.q, r: last.r });
    }
    renderRegionEditor();
    setRegionEditStatus(
      (op === "remove" ? "已移除 " : "已加入 ") +
        (painted ? painted.length : 0) +
        " 格（选区共 " +
        regionDraftList().length +
        " 格）",
      "muted"
    );
    return { ok: true, draftCount: regionDraftList().length };
  }

  /** 在左栏已有的区域名里找 trim 后精确同名的区域；返回 overview 条目或 null。 */
  function findSameNameRegion(name) {
    var wanted = String(name || "").trim();
    if (!wanted) {
      return null;
    }
    var found = null;
    (host.overviewRegions || []).forEach(function (region) {
      if (found || !region) {
        return;
      }
      var other = String(region.name === undefined || region.name === null ? "" : region.name).trim();
      if (other === wanted) {
        found = region;
      }
    });
    return found;
  }

  /** 某个 regionId 是否已被占用（用于「新建同名区域」时换一个不同的 id）。 */
  function regionIdExists(id) {
    var used = false;
    (host.overviewRegions || []).forEach(function (region) {
      if (region && String(region.id) === String(id)) {
        used = true;
      }
    });
    return used;
  }

  /**
   * ★ M8-S §9.2：建区前**先查同名**（左栏 overview 的已有区域名）。同名 ⇒ 挂起并弹二选一，
   * **不静默新建、不静默合并**；只有用户点选后才发**恰一条**命令。
   */
  async function requestCreateRegion(id, name, hexes) {
    var same = findSameNameRegion(name);
    if (same) {
      host.regionNameConflict = {
        id: id,
        name: name,
        hexes: hexes.slice(),
        existingId: String(same.id),
        existingName: String(same.name),
      };
      renderRegionEditor();
      setRegionEditStatus(
        "名称「" + name + "」已存在（regionId=" + same.id + "）：请选择「新建同名区域」或「合并到同名已有区域」。未发任何命令。",
        "warn"
      );
      return { ok: false, pending: true };
    }
    return submitCreateRegionNow(id, name, hexes);
  }

  /** 真正落一条 map.CreateRegion（不含同名检测）。 */
  async function submitCreateRegionNow(id, name, hexes) {
    if (host.regionEditBusy) {
      return null;
    }
    host.regionEditBusy = true;
    host.regionNameConflict = null;
    setRegionEditStatus("提交 map.CreateRegion " + id + "（" + hexes.length + " 格）…", "muted");
    var result = await app.writeCommand("map.CreateRegion", { regionId: id, name: name, hexes: hexes });
    host.regionEditBusy = false;
    if (result.ok) {
      setRegionEditStatus("已创建 " + id + "（" + hexes.length + " 格，重叠允许）—— head 已前进", "ok");
      app.setRegionFocus(id);
    } else {
      setRegionEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    renderRegionEditor();
    return result;
  }

  /** 重名提示选 (i)：**新建同名区域**——用不同的 id 发**恰 1 条** map.CreateRegion（同 name）。 */
  async function resolveNameConflictCreateNew() {
    var pending = host.regionNameConflict;
    if (!pending || host.regionEditBusy) {
      return null;
    }
    var id = pending.id;
    if (!id || regionIdExists(id)) {
      id = suggestRegionId();
      var idInput = app.byId("region-create-id");
      if (idInput) {
        idInput.value = id;
      }
    }
    return submitCreateRegionNow(id, pending.name, pending.hexes);
  }

  /** 重名提示选 (ii)：**合并到同名已有区域**——恰 1 条 map.UpdateRegion{hexes: 已有 ∪ 新建}。 */
  async function resolveNameConflictMerge() {
    var pending = host.regionNameConflict;
    if (!pending || host.regionEditBusy) {
      return null;
    }
    host.regionEditBusy = true;
    host.regionNameConflict = null;
    setRegionEditStatus("合并到同名区域 " + pending.existingId + " …", "muted");
    var existing = await fetchRegionCached(pending.existingId);
    var hexes = unionHexes(existing.hexes || [], pending.hexes);
    var result = await app.writeCommand("map.UpdateRegion", { regionId: pending.existingId, hexes: hexes });
    host.regionEditBusy = false;
    if (result.ok) {
      host.regionDraft = {};
      core.active.setDraftHexes([]);
      host.regionCache = {};
      setRegionEditStatus(
        "已把 " + pending.hexes.length + " 格并入 " + pending.existingId + "（并集共 " + hexes.length + " 格）—— head 已前进",
        "ok"
      );
      app.setRegionFocus(pending.existingId);
      await refreshFocusHexes();
      reloadRegionEditHighlight();
    } else {
      setRegionEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    renderRegionEditor();
    return result;
  }

  /** 重名提示取消：清挂起，**零写**。 */
  function cancelNameConflict() {
    host.regionNameConflict = null;
    renderRegionEditor();
    setRegionEditStatus("已取消（未发任何命令）。", "muted");
  }

  /** 新建区域：一条 map.CreateRegion{regionId,name,hexes}。★ 同名先弹二选一（§9.2）。 */
  async function submitCreateRegion() {
    var idNode = app.byId("region-create-id");
    var nameNode = app.byId("region-create-name");
    var id = idNode ? idNode.value.trim() : "";
    var name = nameNode ? nameNode.value.trim() : "";
    var hexes = regionDraftList();
    if (!id) {
      setRegionEditStatus("请填写 regionId（Q3：由调用方指定，可改建议值）。", "warn");
      return null;
    }
    if (!name) {
      setRegionEditStatus("请填写区域名称。", "warn");
      return null;
    }
    return requestCreateRegion(id, name, hexes);
  }

  /** 改已有区域 hex 集合：一条 map.UpdateRegion{regionId,hexes}（meta 编辑器走 T8 的路径，不在此重写）。 */
  async function submitUpdateRegion() {
    if (host.regionEditBusy) {
      return null;
    }
    var id = host.regionFocus;
    if (!id) {
      setRegionEditStatus("先在右栏选一个区域（或在「新建区域」后改）。", "warn");
      return null;
    }
    var hexes = regionDraftList();
    host.regionEditBusy = true;
    setRegionEditStatus("提交 map.UpdateRegion " + id + "（hex 集合 " + hexes.length + " 格）…", "muted");
    var result = await app.writeCommand("map.UpdateRegion", { regionId: id, hexes: hexes });
    host.regionEditBusy = false;
    if (result.ok) {
      setRegionEditStatus("已更新 " + id + " 的 hex 集合（" + hexes.length + " 格）—— head 已前进", "ok");
      host.regionCache = {};
      await refreshFocusHexes();
    } else {
      setRegionEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    renderRegionEditor();
    return result;
  }

  /**
   * ★ M8-R 判据 1：右键拖动套索 ⇒ 一条 map.CreateRegion。hexes = 客户端 flood fill 结果
   * （内部 ∪ 套索墙）；regionId/name 取创建表单（空则用可改的建议值）。**重叠不报错**。
   */
  async function onLassoCommit(hexes) {
    if (app.getState().mode !== "region-edit" || host.regionEditBusy) {
      return null;
    }
    if (!hexes || !hexes.length) {
      setRegionEditStatus("套索为空或不闭合（至少 3 个格），未创建。", "warn");
      return null;
    }
    var idInput = app.byId("region-create-id");
    var nameInput = app.byId("region-create-name");
    var id = idInput ? idInput.value.trim() : "";
    var name = nameInput ? nameInput.value.trim() : "";
    if (!id) {
      id = suggestRegionId();
      if (idInput) {
        idInput.value = id;
      }
    }
    if (!name) {
      name = "新区域";
      if (nameInput) {
        nameInput.value = name;
      }
    }
    // ★ §9.2：同名先弹二选一（不静默新建/合并）；不同名则落一条 CreateRegion。
    return requestCreateRegion(id, name, hexes);
  }

  /** ★ M8-R 判据 3：拖边界小点 ⇒ 一条 map.UpdateRegion，hex 集合即拖动后的焦点集合。 */
  async function onDotDragCommit(hexes) {
    if (app.getState().mode !== "region-edit" || host.regionEditBusy) {
      return null;
    }
    var id = host.regionFocus;
    if (!id) {
      return null;
    }
    if (!hexes || !hexes.length) {
      setRegionEditStatus("该拖动会让 " + id + " 变空，已阻止（未发命令）。", "warn");
      return null;
    }
    host.regionEditBusy = true;
    setRegionEditStatus("小点拖动 ⇒ 提交 map.UpdateRegion " + id + "（" + hexes.length + " 格）…", "muted");
    var result = await app.writeCommand("map.UpdateRegion", { regionId: id, hexes: hexes });
    host.regionEditBusy = false;
    if (result.ok) {
      setRegionEditStatus("已更新 " + id + "（" + hexes.length + " 格）—— head 已前进", "ok");
    } else {
      setRegionEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    host.regionCache = {};
    await refreshFocusHexes();
    reloadRegionEditHighlight();
    renderRegionEditor();
    return result;
  }

  function unionHexes(base, extra) {
    var out = {};
    (base || []).forEach(function (h) {
      out[h.q + "_" + h.r] = { q: h.q, r: h.r };
    });
    (extra || []).forEach(function (h) {
      out[h.q + "_" + h.r] = { q: h.q, r: h.r };
    });
    return Object.keys(out).map(function (key) {
      return out[key];
    });
  }

  function differenceHexes(base, remove) {
    var drop = {};
    (remove || []).forEach(function (h) {
      drop[h.q + "_" + h.r] = true;
    });
    return (base || []).filter(function (h) {
      return !drop[h.q + "_" + h.r];
    });
  }

  /** ★ M8-R 判据 4：合并 = 临时选区 ∪ 焦点区域 ⇒ 一条 map.UpdateRegion{hexes: union}。 */
  async function submitRegionMerge() {
    if (host.regionEditBusy) {
      return null;
    }
    var id = host.regionFocus;
    if (!id) {
      setRegionEditStatus("先在右栏选一个区域。", "warn");
      return null;
    }
    var base = await fetchRegionCached(id);
    var hexes = unionHexes(base.hexes, regionDraftList());
    if (!hexes.length) {
      setRegionEditStatus("并集为空，未发命令。", "warn");
      return null;
    }
    host.regionEditBusy = true;
    setRegionEditStatus("合并 " + id + " ∪ 临时选区（" + hexes.length + " 格）…", "muted");
    var result = await app.writeCommand("map.UpdateRegion", { regionId: id, hexes: hexes });
    host.regionEditBusy = false;
    if (result.ok) {
      host.regionDraft = {};
      core.active.setDraftHexes([]);
      host.regionCache = {};
      setRegionEditStatus("已合并 " + id + "（" + hexes.length + " 格）—— head 已前进", "ok");
      await refreshFocusHexes();
      reloadRegionEditHighlight();
    } else {
      setRegionEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    renderRegionEditor();
    return result;
  }

  /** ★ M8-R 判据 5：剔除 = 焦点区域 − 临时选区 ⇒ 一条 map.UpdateRegion{hexes: difference}。 */
  async function submitRegionSubtract() {
    if (host.regionEditBusy) {
      return null;
    }
    var id = host.regionFocus;
    if (!id) {
      setRegionEditStatus("先在右栏选一个区域。", "warn");
      return null;
    }
    var base = await fetchRegionCached(id);
    var hexes = differenceHexes(base.hexes, regionDraftList());
    if (!hexes.length) {
      setRegionEditStatus("差集为空（会清空 " + id + "，服务端拒绝空 hexes），未发命令。", "warn");
      return null;
    }
    host.regionEditBusy = true;
    setRegionEditStatus("剔除 " + id + " − 临时选区（余 " + hexes.length + " 格）…", "muted");
    var result = await app.writeCommand("map.UpdateRegion", { regionId: id, hexes: hexes });
    host.regionEditBusy = false;
    if (result.ok) {
      host.regionDraft = {};
      core.active.setDraftHexes([]);
      host.regionCache = {};
      setRegionEditStatus("已剔除 " + id + "（余 " + hexes.length + " 格）—— head 已前进", "ok");
      await refreshFocusHexes();
      reloadRegionEditHighlight();
    } else {
      setRegionEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    renderRegionEditor();
    return result;
  }

  function armRegionDelete() {
    if (!host.regionFocus) {
      setRegionEditStatus("先在右栏选一个区域。", "warn");
      return;
    }
    host.regionDeleteArmed = true;
    renderRegionEditor();
    setRegionEditStatus("删除不可撤销：点「确认删除」才真正发出 map.DeleteRegion。", "warn");
  }

  function cancelRegionDelete() {
    host.regionDeleteArmed = false;
    renderRegionEditor();
    setRegionEditStatus("已取消删除。", "muted");
  }

  /** 删除区域：只有**二次确认后**才发一条 map.DeleteRegion（未确认前零写）。 */
  async function submitDeleteRegion() {
    var id = host.regionFocus;
    if (!id || host.regionEditBusy || !host.regionDeleteArmed) {
      return null;
    }
    host.regionDeleteArmed = false;
    host.regionEditBusy = true;
    renderRegionEditor();
    setRegionEditStatus("提交 map.DeleteRegion " + id + " …", "muted");
    var result = await app.writeCommand("map.DeleteRegion", { regionId: id });
    host.regionEditBusy = false;
    if (result.ok) {
      setRegionEditStatus("已删除 " + id + " —— head 已前进", "ok");
      host.regionDraft = {};
      core.active.setDraftHexes([]);
      app.setRegionFocus(null);
    } else {
      setRegionEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    renderRegionEditor();
    return result;
  }

  /**
   * 目标区域变化：把它的 hex 推给"边界小点层"（仅选中区域画点），并刷新 meta 编辑器与淡色高亮。
   * ★ 临时选区（draft）**不**自动载入区域 hex——选区是给"合并/剔除"用的独立草稿，
   *   载入整份区域会让并集恒等于原区域（要整份替换走「把目标区域 hex 载入选区」）。
   */
  function onRegionFocusChanged(focus) {
    host.regionFocus = focus || null;
    host.regionDeleteArmed = false;
    host.regionInfoKey = null;
    renderRegionEditor();
    if (app.getState().mode !== "region-edit") {
      return;
    }
    host.regionDraft = {};
    core.active.setDraftHexes([]);
    if (!host.regionFocus) {
      core.active.clearFocusHexes();
      reloadRegionEditHighlight();
      setRegionEditStatus("未选中区域：点「新建区域」或从右栏选一个已有区域。", "muted");
      return;
    }
    refreshFocusHexes().then(function (region) {
      if (!region || host.regionFocus !== focus) {
        return;
      }
      setRegionEditStatus(
        "已选中 " + focus + "（" + (region.hexes || []).length + " 格）：边界小点可拖动增删；右键拖动=套索，Shift+右键拖动=逐格画擦，左键拖动=平移。",
        "muted"
      );
    });
    reloadRegionEditHighlight();
  }

  function wireRegionEditor() {
    var bind = function (id, handler) {
      var node = app.byId(id);
      if (node) {
        node.addEventListener("click", handler);
      }
    };
    bind("region-edit-new", newRegionDraft);
    bind("region-edit-clear", clearRegionDraft);
    bind("region-edit-load", loadFocusIntoDraft);
    bind("region-create-submit", submitCreateRegion);
    bind("region-name-conflict-new", resolveNameConflictCreateNew);
    bind("region-name-conflict-merge", resolveNameConflictMerge);
    bind("region-name-conflict-cancel", cancelNameConflict);
    bind("region-update-submit", submitUpdateRegion);
    bind("region-merge", submitRegionMerge);
    bind("region-exclude", submitRegionSubtract);
    bind("region-delete", armRegionDelete);
    bind("region-delete-yes", submitDeleteRegion);
    bind("region-delete-cancel", cancelRegionDelete);
    var opSelect = app.byId("region-edit-op");
    if (opSelect) {
      opSelect.addEventListener("change", function () {
        host.regionOp = opSelect.value === "remove" ? "remove" : "add";
        core.active.setBrushOp(host.regionOp);
        renderRegionEditor();
      });
    }
  }

  window.SimosMapRegionEditor = {
    // map.js（initHost / reloadOverview / onStateChange）惰性调用这些入口。
    renderRegionEditor: renderRegionEditor,
    onRegionFocusChanged: onRegionFocusChanged,
    commitRegionPaint: commitRegionPaint,
    onLassoCommit: onLassoCommit,
    onDotDragCommit: onDotDragCommit,
    wireRegionEditor: wireRegionEditor,
  };
})();
