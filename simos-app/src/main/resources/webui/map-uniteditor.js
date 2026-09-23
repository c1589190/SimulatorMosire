// map-uniteditor.js —— 单位移动 / 编辑模式的宿主接线（M12 第五波：自 map.js 顶层整体搬出）。
//
// 这一簇在 map.js 里状态最独立：只读写**共享的 host 对象**（按引用共享）与 active，
// 不碰其它簇的可变状态。成员（原文行号见 map.js 未改动前的 2724-3066）：
//   setEditStatus / selectedUnitId / selectedUnitParentId / updateRouteButtons / renderUnitEditor / resetRoute /
//   appendRoutePoint / submitRoute / handleContextMenu / submitPathRoute / cancelRouteFor /
//   requireSelectedUnit / submitReparent / submitStrength / submitDisband / submitDetachFormation /
//   submitAttachFormation / submitCreate / wireUnitEditor
//
// ★ 引入顺序：hexgeom → hexcolor → regionShape → map.js → renderer.js → map-uniteditor.js。
//   必须在 map.js 之后（window.SimosMapCore 由 map.js 建立）。
// ★ 函数体与 map.js 原文逐字节相同，唯一例外：「active」是 map.js 的可变绑定，取快照会永远是
//   最初的 null ⇒ 唯一一处（appendRoutePoint）改为实时读 core.active
//   （同 renderer.js 对 regionNamesEnabled 的处理）。
// ★ 写路径不变：单位命令仍经 window.SimosApp.writeCommand → /api/command（R8 allowlist）。
// ★ map.js（initHost / workbenchSelect / onStateChange）对本文件经 window.SimosMapUnitEditor
//   惰性调用——与 map.js 调用 window.SimosCreateRenderer 同一手法（本文件在 map.js 之后引入）。

(function () {
  "use strict";

  // ── 从 map.js 暴露的 window.SimosMapCore 取回（本文件在 map.js 之后引入）────────────
  var core = window.SimosMapCore;
  var app = core.app;
  var api = core.api;
  var host = core.host; // 对象按引用共享：host.routeMode / routePath / editBusy 的改写对 map.js 可见
  var coordText = core.coordText; // 纯函数，仍在 map.js
  var isAdjacent = core.isAdjacent; // 纯函数，仍在 map.js
  var parseEquipmentText = core.parseEquipmentText; // 纯函数，仍在 map.js

  // ── 单位移动与编辑模式（M7 T7，判据⑤ / R8）[原文：map.js 2724-3066] ─────────────────

  function setEditStatus(message, tone) {
    app.statusMessage(app.byId("unit-edit-status"), message, tone);
  }

  function selectedUnitId() {
    var selection = app.getState().selection;
    return selection && selection.kind === "unit" ? selection.id : null;
  }

  /**
   * 选中单位在头时刻的父 id（"加入编队"的父来源）。返回值三态见 renderer.parentOf：
   * 字符串 = 有父、`null` = 已知是根、`undefined` = 尚未载入。缺 renderer/parentOf 时一律 undefined（当作"未知"，不禁用）。
   */
  function selectedUnitParentId() {
    var id = selectedUnitId();
    if (!id || !core.active || typeof core.active.parentOf !== "function") {
      return undefined;
    }
    return core.active.parentOf(id);
  }

  function updateRouteButtons() {
    var hasUnit = !!selectedUnitId();
    var send = app.byId("unit-route-send");
    var clear = app.byId("unit-route-clear");
    if (send) {
      send.disabled = !hasUnit || host.routePath.length < 1 || host.editBusy;
    }
    if (clear) {
      clear.disabled = host.routePath.length < 1;
    }
  }

  function renderUnitEditor(state) {
    var id = selectedUnitId();
    var selectedNode = app.byId("unit-edit-selected");
    if (selectedNode) {
      selectedNode.textContent = id ? "选中单位：" + id : "未选中单位";
    }
    ["unit-reparent", "unit-strength", "unit-disband", "unit-route-toggle", "unit-detach-formation"].forEach(function (buttonId) {
      var node = app.byId(buttonId);
      if (node) {
        node.disabled = !id;
      }
    });
    // ★「加入编队」多一个前提：需要一个父。`parentId === null` = 已知它是根（无父可加入）⇒ 禁用并说明；
    //   `undefined` = 该单位尚未载入 ⇒ 不禁用（不把"未知"当"根"），点下去由服务端/点击时校验兜底。
    var attachNode = app.byId("unit-attach-formation");
    if (attachNode) {
      var parentId = id ? selectedUnitParentId() : null;
      attachNode.disabled = !id || parentId === null;
      attachNode.title = parentId === null ? "该单位已是根单位：没有可加入的父" : "";
    }
    var routeNode = app.byId("unit-route-preview");
    if (routeNode) {
      routeNode.textContent =
        "路线：" + (host.routePath.length ? host.routePath.map(coordText).join(" → ") : "（未选）");
    }
    updateRouteButtons();
  }

  function resetRoute() {
    host.routePath = [];
    renderUnitEditor(app.getState());
  }

  function appendRoutePoint(q, r) {
    var point = { q: q, r: r };
    var anchor = host.routePath.length
      ? host.routePath[host.routePath.length - 1]
      : core.active.positionOf(selectedUnitId());
    if (!anchor) {
      setEditStatus("先点选一个单位，再点相邻格下路线。", "warn");
      return;
    }
    if (!isAdjacent(anchor, point)) {
      setEditStatus("路线必须逐格相邻：" + coordText(anchor) + " 与 " + coordText(point) + " 不相邻", "warn");
      return;
    }
    if (host.routePath.some(function (p) {
      return p.q === q && p.r === r;
    })) {
      setEditStatus("路线不得有重复格：" + coordText(point), "warn");
      return;
    }
    host.routePath.push(point);
    setEditStatus("已加路线点 " + coordText(point) + "（共 " + host.routePath.length + " 格）", "muted");
    renderUnitEditor(app.getState());
  }

  /** 路线式移动：waypoints = [单位当前位置, ...逐格点列]（起点必须==当前位置，PlanRoute 域规则）。 */
  async function submitRoute() {
    var id = selectedUnitId();
    if (host.editBusy || !id || host.routePath.length < 1) {
      return null;
    }
    host.editBusy = true;
    setEditStatus("下路线 " + id + " …", "muted");
    var waypoints;
    try {
      var unit = await api.unit(id, app.target());
      if (!unit.position) {
        throw new Error("单位 " + id + " 当前没有位置，无法下路线");
      }
      waypoints = [{ q: unit.position.q, r: unit.position.r }].concat(host.routePath);
    } catch (e) {
      host.editBusy = false;
      setEditStatus("下路线失败：" + (e.message || e), "err");
      return null;
    }
    var result = await app.writeCommand("unit.PlanRoute", { id: id, waypoints: waypoints });
    host.editBusy = false;
    if (result.ok) {
      resetRoute();
      setEditStatus("已下路线 " + id + "（" + (waypoints.length - 1) + " 格）—— 点「创建节点」推进时间，单位才会出发", "ok");
    } else {
      setEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    return result;
  }

  /**
   * 右键寻路移动（M7b T3，S3 的 HoI4 语义）：**仅** `unit` 模式 + 已选中单位时消费右键；服务端 A* 算路后经
   * `app.writeCommand("unit.PlanRoute", …)` 提交（**替换**原路线）。其它模式/未选单位 ⇒ 返回 false（不 preventDefault、
   * 更不发任何写请求——只读模式不得被污染，R8）。
   */
  function handleContextMenu(pick) {
    // ★ §七 判据 7：区域编辑（套索/逐格）与地图编辑（刷地形）的右键由 pointer 事件消费——
    //   这里只抑制原生菜单，绝不落到 unit.PlanRoute，也不改选中态。常规 / 单位移动编辑的右键行为**不变**。
    var contextMode = app.getState().mode;
    if (contextMode === "region-edit" || contextMode === "map-edit") {
      return true;
    }
    // ★ M7e T1（用户原话）：右键点**空白/图外/无格** ⇒ 取消选中；**不发任何写、不清路线**（路线归左键取消）。
    if (!pick || !pick.inMap) {
      app.setSelection(null);
      return true;
    }
    if (app.getState().mode !== "unit") {
      return false;
    }
    var id = selectedUnitId();
    if (!id) {
      return false;
    }
    submitPathRoute(id, pick.q, pick.r);
    return true;
  }

  /** 右键寻路：GET /api/map/path（只读）⇒ reachable 才发 unit.PlanRoute；不可达/已在目标格 ⇒ 明确提示、不发写。 */
  async function submitPathRoute(id, q, r) {
    if (host.editBusy) {
      return null;
    }
    host.editBusy = true;
    setEditStatus("寻路 " + id + " → " + coordText({ q: q, r: r }) + " …", "muted");
    var body;
    try {
      body = await api.mapPath(id, q, r, app.target());
    } catch (e) {
      host.editBusy = false;
      setEditStatus("寻路失败：" + (e.message || e), "err");
      return null;
    }
    var path = (body && body.path) || [];
    if (!body || !body.reachable || path.length < 1) {
      host.editBusy = false;
      setEditStatus("不可达：" + id + " → " + coordText({ q: q, r: r }), "warn");
      return null;
    }
    if (path.length < 2) {
      host.editBusy = false;
      setEditStatus("已在目标格 " + coordText({ q: q, r: r }) + "（未改路线）", "muted");
      return null;
    }
    var result = await app.writeCommand("unit.PlanRoute", { id: id, waypoints: path });
    host.editBusy = false;
    if (result.ok) {
      setEditStatus("已下路线 " + id + "（" + (path.length - 1) + " 格，替换原路线）—— 点「创建节点」推进时间，单位才会出发", "ok");
    } else {
      setEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    return result;
  }

  /** 取消移动（M7e T1）：真命令 `unit.CancelRoute {id}`，经 app.writeCommand（→ /api/command，R8 allowlist）。 */
  async function cancelRouteFor(id) {
    if (!id || host.editBusy) {
      return null;
    }
    host.editBusy = true;
    setEditStatus("取消移动 " + id + " …", "muted");
    var result = await app.writeCommand("unit.CancelRoute", { id: id });
    host.editBusy = false;
    if (result.ok) {
      setEditStatus("已取消 " + id + " 的移动（路线已清）", "ok");
    } else {
      setEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    return result;
  }

  function requireSelectedUnit(actionLabel) {
    var id = selectedUnitId();
    if (!id) {
      setEditStatus("先点选一个单位再" + actionLabel + "。", "warn");
    }
    return id;
  }

  async function submitReparent() {
    var id = requireSelectedUnit("改上级");
    if (!id || host.editBusy) {
      return null;
    }
    var input = app.byId("unit-reparent-parent");
    var raw = input ? input.value.trim() : "";
    host.editBusy = true;
    setEditStatus("改上级 " + id + " …", "muted");
    var result = await app.writeCommand("unit.ReparentUnit", { id: id, parent: raw === "" ? null : raw });
    host.editBusy = false;
    setEditStatus(
      result.ok ? "已改上级 " + id + " → " + (raw === "" ? "（根）" : raw) : result.message,
      result.ok ? "ok" : result.kind === "rejected" ? "err" : "warn"
    );
    return result;
  }

  async function submitStrength() {
    var id = requireSelectedUnit("改编制");
    if (!id || host.editBusy) {
      return null;
    }
    var memberInput = app.byId("unit-strength-member");
    var member = Number(memberInput ? memberInput.value : NaN);
    if (!Number.isInteger(member) || member < 0) {
      setEditStatus("人数必须是 ≥ 0 的整数。", "warn");
      return null;
    }
    var equipment;
    try {
      equipment = parseEquipmentText(app.byId("unit-strength-equipment").value);
    } catch (e) {
      setEditStatus(e.message, "warn");
      return null;
    }
    host.editBusy = true;
    setEditStatus("改编制 " + id + " …", "muted");
    var result = await app.writeCommand("unit.SetStrength", { id: id, member: member, equipment: equipment });
    host.editBusy = false;
    setEditStatus(
      result.ok ? "已改编制 " + id + "（人数 " + member + "）" : result.message,
      result.ok ? "ok" : result.kind === "rejected" ? "err" : "warn"
    );
    return result;
  }

  async function submitDisband() {
    var id = requireSelectedUnit("解散");
    if (!id || host.editBusy) {
      return null;
    }
    host.editBusy = true;
    setEditStatus("解散 " + id + " …", "muted");
    var result = await app.writeCommand("unit.DisbandUnit", { id: id });
    host.editBusy = false;
    if (result.ok) {
      resetRoute();
      app.setSelection(null); // 被解散的单位不再存在，选中态必须清掉（否则下一次点格会拿它当移动目标）
      setEditStatus("已解散 " + id, "ok");
    } else {
      setEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    return result;
  }

  /**
   * 脱离编队（`unit.DetachUnit {id}`）：只作用于选中单位（只节点，不级联——与命令同口径）。服务端会把它**钉在当前位置**，
   * 之后不再跟随父。拒绝理由（如"已是根单位"）**原样**显示，前端不另造一份真相。
   */
  async function submitDetachFormation() {
    var id = requireSelectedUnit("脱离编队");
    if (!id || host.editBusy) {
      return null;
    }
    host.editBusy = true;
    setEditStatus("脱离编队 " + id + " …", "muted");
    var result = await app.writeCommand("unit.DetachUnit", { id: id });
    host.editBusy = false;
    setEditStatus(
      result.ok ? "已脱离编队 " + id + "（已钉在当前位置，不再跟随父）" : result.message,
      result.ok ? "ok" : result.kind === "rejected" ? "err" : "warn"
    );
    return result;
  }

  /**
   * 加入编队（`unit.AttachUnit {id, parent}`）：父 = 选中单位的**当前父**（读 `api.unit` 的 `parent`，不缓存、不造第二份真相）。
   * 已是根 ⇒ 无父可加入，明确提示、不发写。★ 服务端按**偏移式加入**处理（原地不动 + 进入跟随，**不再要求同格**）——前端**不**自己判位置，
   * 失败时把服务端给的理由**原样**显示（那会变成第二份真相）。
   */
  async function submitAttachFormation() {
    var id = requireSelectedUnit("加入编队");
    if (!id || host.editBusy) {
      return null;
    }
    host.editBusy = true;
    setEditStatus("加入编队 " + id + " …", "muted");
    var parent;
    try {
      var unit = await api.unit(id, app.target());
      parent = unit && unit.parent ? unit.parent : null;
    } catch (e) {
      host.editBusy = false;
      setEditStatus("加入编队失败：" + (e.message || e), "err");
      return null;
    }
    if (!parent) {
      host.editBusy = false;
      setEditStatus("单位 " + id + " 已是根单位：没有可加入的父（先给它一个上级再试）。", "warn");
      return null;
    }
    var result = await app.writeCommand("unit.AttachUnit", { id: id, parent: parent });
    host.editBusy = false;
    setEditStatus(
      result.ok ? "已加入编队 " + id + " → " + parent + "（进入跟随）" : result.message,
      result.ok ? "ok" : result.kind === "rejected" ? "err" : "warn"
    );
    return result;
  }

  async function submitCreate() {
    if (host.editBusy) {
      return null;
    }
    var id = app.byId("unit-create-id").value.trim();
    var name = app.byId("unit-create-name").value.trim();
    var q = Number(app.byId("unit-create-q").value);
    var r = Number(app.byId("unit-create-r").value);
    var member = Number(app.byId("unit-create-member").value);
    var speed = Number(app.byId("unit-create-speed").value);
    var mobility = Number(app.byId("unit-create-mobility").value);
    var parent = app.byId("unit-create-parent").value.trim();
    if (!id || !name || !Number.isInteger(q) || !Number.isInteger(r)) {
      setEditStatus("新建单位需要 id、名称、整数 q/r。", "warn");
      return null;
    }
    if (
      !Number.isInteger(member) ||
      member < 0 ||
      !Number.isInteger(speed) ||
      speed < 1 ||
      !Number.isInteger(mobility) ||
      mobility < 1
    ) {
      setEditStatus("新建单位：人数 ≥ 0、速度 ≥ 1、机动‰ ≥ 1。", "warn");
      return null;
    }
    var equipment;
    try {
      equipment = parseEquipmentText(app.byId("unit-create-equipment").value);
    } catch (e) {
      setEditStatus(e.message, "warn");
      return null;
    }
    var payload = {
      id: id,
      name: name,
      position: { q: q, r: r },
      member: member,
      equipment: equipment,
      speed: speed,
      mobilityPerMille: mobility,
    };
    if (parent !== "") {
      payload.parent = parent;
    }
    host.editBusy = true;
    setEditStatus("创建 " + id + " …", "muted");
    var result = await app.writeCommand("unit.CreateUnit", payload);
    host.editBusy = false;
    setEditStatus(
      result.ok ? "已创建 " + id : result.message,
      result.ok ? "ok" : result.kind === "rejected" ? "err" : "warn"
    );
    return result;
  }

  function wireUnitEditor() {
    var routeToggle = app.byId("unit-route-toggle");
    if (routeToggle) {
      routeToggle.addEventListener("click", function () {
        host.routeMode = !host.routeMode;
        routeToggle.textContent = "路线模式：" + (host.routeMode ? "开" : "关");
        resetRoute();
        setEditStatus(
          host.routeMode ? "路线模式：依次点相邻格连成路径，再点「下路线」。" : "路线模式已关。",
          "muted"
        );
      });
    }
    var bind = function (buttonId, handler) {
      var node = app.byId(buttonId);
      if (node) {
        node.addEventListener("click", handler);
      }
    };
    bind("unit-route-send", submitRoute);
    bind("unit-route-clear", function () {
      resetRoute();
      setEditStatus("已清除路线点。", "muted");
    });
    bind("unit-reparent", submitReparent);
    bind("unit-strength", submitStrength);
    bind("unit-disband", submitDisband);
    bind("unit-detach-formation", submitDetachFormation);
    bind("unit-attach-formation", submitAttachFormation);
    bind("unit-create", submitCreate);
    renderUnitEditor(app.getState());
  }

  window.SimosMapUnitEditor = {
    setEditStatus: setEditStatus,
    selectedUnitId: selectedUnitId,
    selectedUnitParentId: selectedUnitParentId,
    updateRouteButtons: updateRouteButtons,
    renderUnitEditor: renderUnitEditor,
    resetRoute: resetRoute,
    appendRoutePoint: appendRoutePoint,
    submitRoute: submitRoute,
    handleContextMenu: handleContextMenu,
    submitPathRoute: submitPathRoute,
    cancelRouteFor: cancelRouteFor,
    requireSelectedUnit: requireSelectedUnit,
    submitReparent: submitReparent,
    submitStrength: submitStrength,
    submitDisband: submitDisband,
    submitDetachFormation: submitDetachFormation,
    submitAttachFormation: submitAttachFormation,
    submitCreate: submitCreate,
    wireUnitEditor: wireUnitEditor,
  };
})();
