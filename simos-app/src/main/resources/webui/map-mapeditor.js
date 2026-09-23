// map-mapeditor.js —— 地图编辑模式的宿主 UI（M12 第六波：自 map.js 顶层整体搬出）。
//
// 成员（原文行号：map.js 未改动前的 1015-1706，及 2648-2657 的 edgeKindLabel）：
//   setMapEditStatus / setRegionInfoStatus / appendInfoRow / edgeSummaryText / textOrNull /
//   renderTerrainPalette / selectTerrain / updatePaletteSelection / paletteKeys / commitBrush /
//   setMapEditToolStatus / setEdgeStatus / setRandomizeStatus / setWarning / checkRadio /
//   checkedRadioValue / wireRadioGroup / syncMapEditRadios / renderEdgeKindOptions /
//   setMapEditSubtool / selectMapEditTool / edgeModeValue / renderEdgeControls / commitEdge /
//   commitEdgeDelete / commitRandomizeSelection / renderRandomizeStatus / submitRandomize /
//   refreshRegionInfoNow / clearRegionInfo / renderRegionInfo / fillRegionMetaEditor /
//   loadRegionMeta / submitRegionMeta / wireMapEditor / edgeKindLabel
//
// ★ 引入顺序：hexgeom → hexcolor → regionShape → map.js → **map-mapeditor.js** →
//   map-regioneditor.js → renderer.js → map-uniteditor.js → map-hostpage.js。
//   本文件在 map.js 之后（window.SimosMapCore 由 map.js 建立），且**在 renderer.js 之前**
//   （renderer.js 加载期就要取 core.renderEdgeKindOptions ⇒ 本文件把该成员挂回 core）。
// ★ 函数体与 map.js 原文逐字节相同，唯一一类改动：`active` 是 map.js 的可变绑定（initHost 才
//   赋值），取快照恒为 null ⇒ 搬出后一律实时读 `core.active`（getter，同 renderer.js /
//   map-uniteditor.js 的手法）。语义不变。
// ★ 写路径不变：四条写命令仍经 window.SimosApp.writeCommand → /api/command（R8 allowlist），
//   且每个写点仍先过 `mapEditWriteGate`（纯函数留在 map.js）。
// ★ map.js 对本文件经 window.SimosMapEditor.* **惰性**调用（initHost / reloadOverview /
//   onStateChange）——同 window.SimosMapUnitEditor 的手法。

(function () {
  "use strict";

  // ── 从 map.js 暴露的 window.SimosMapCore 取回（本文件在 map.js 之后引入）────────────
  var core = window.SimosMapCore;
  var app = core.app;
  var api = core.api;
  var host = core.host; // 对象按引用共享：host.brushTerrain / mapEditTool … 的改写对 map.js 可见
  var targetKey = core.targetKey; // 纯函数（含当前 target），仍在 map.js
  var mapEditWriteGate = core.mapEditWriteGate; // 纯函数，仍在 map.js
  var mapEditSubtoolOf = core.mapEditSubtoolOf;
  var mapEditPanelVisibility = core.mapEditPanelVisibility;
  var mapEditSubtoolState = core.mapEditSubtoolState;
  var mapEditSubtoolDefaultTool = core.mapEditSubtoolDefaultTool;
  var edgeModeState = core.edgeModeState;
  var edgeDeletePlan = core.edgeDeletePlan;
  var parseSeedInput = core.parseSeedInput;
  var randomizeSelectionState = core.randomizeSelectionState;
  var registeredEdgeKindList = core.registeredEdgeKindList;
  // ★ active 是可变绑定：函数体里出现的一律写作 core.active（见文件头）。

  // ── 地图编辑模式（M8 T7 框架 + T8 调色板/拖刷/区域信息）──────────────────
  //
  // ★ 写路径唯一：全部经 app.writeCommand（模式白名单在 app.js 的 writeCommand 里把关：map-edit 允许
  //   map.SetTerrain / SetEdge / RandomizeRegion；本单只实现 SetTerrain，另两条 UI 置灰）。
  // ★ 拖刷语义（Q5）：一次拖动收集**去重后的 hex 集合**，松手发**一条** map.SetTerrain（不是每格一条）。

  function setMapEditStatus(message, tone) {
    app.statusMessage(app.byId("brush-status"), message, tone);
  }

  function setRegionInfoStatus(message, tone) {
    app.statusMessage(app.byId("region-info-status"), message, tone);
  }

  function appendInfoRow(detail, label, value) {
    detail.appendChild(app.el("dt", { text: label }));
    detail.appendChild(
      app.el("dd", { text: value === null || value === undefined ? "—" : String(value) })
    );
  }

  /** `/api/map/hex` 的 `edges`（入射边 + 其 pathway 标注键）⇒ 一行可读文本；无入射边 ⇒ `"无"`。 */
  function edgeSummaryText(edges) {
    var list = Array.isArray(edges) ? edges : [];
    if (!list.length) {
      return "无";
    }
    return list
      .map(function (edge) {
        var pathways = Array.isArray(edge.pathways) ? edge.pathways : [];
        return edge.edge + (pathways.length ? "[" + pathways.join(",") + "]" : "[]");
      })
      .join("；");
  }

  function textOrNull(id) {
    var node = app.byId(id);
    var raw = node ? node.value || "" : "";
    raw = raw.trim();
    return raw === "" ? null : raw;
  }

  /** 地形调色板：**只列** /api/map/overview 的 terrainTypes（后端权威词表，绝不硬编码）。 */
  function renderTerrainPalette(types) {
    var mount = app.byId("terrain-palette");
    if (!mount) {
      return;
    }
    var list = types || [];
    var signature = list
      .map(function (type) {
        return type.key;
      })
      .join(",");
    if (signature !== host.paletteSignature) {
      host.paletteSignature = signature;
      app.clear(mount);
      list.forEach(function (type) {
        var button = app.el("button", {
          type: "button",
          class: "terrain-swatch",
          "data-terrain": type.key,
          title: app.text(type.name) + "（" + type.key + "）",
        });
        var dot = app.el("span", { class: "terrain-swatch-dot" });
        dot.style.backgroundColor = type.color;
        button.appendChild(dot);
        button.appendChild(app.el("span", { class: "terrain-swatch-label", text: type.key }));
        button.addEventListener("click", function () {
          selectTerrain(type.key);
        });
        mount.appendChild(button);
      });
      if (signature.indexOf(host.brushTerrain || "") < 0) {
        host.brushTerrain = null;
      }
    }
    updatePaletteSelection();
    app.statusMessage(
      app.byId("terrain-palette-status"),
      "词表 " + list.length + " 类：" + (signature || "（空）"),
      "muted"
    );
  }

  function selectTerrain(key) {
    if (host.brushTerrain === key) {
      host.brushTerrain = null;
      updatePaletteSelection();
      setMapEditStatus("已取消地形选择：左键恢复为平移/选中。", "muted");
      return;
    }
    host.brushTerrain = key;
    updatePaletteSelection();
    setMapEditStatus("已选地形 " + key + "：右键在地图上拖动涂抹（多格 ⇒ 一条 map.SetTerrain）；左键=平移。", "ok");
  }

  function updatePaletteSelection() {
    var mount = app.byId("terrain-palette");
    if (!mount) {
      return;
    }
    Array.prototype.forEach.call(mount.querySelectorAll("button[data-terrain]"), function (button) {
      button.classList.toggle("active", button.getAttribute("data-terrain") === host.brushTerrain);
    });
  }

  /** 计划要画的地形（e2e/调试用；null = 未选）。 */
  function paletteKeys() {
    var mount = app.byId("terrain-palette");
    if (!mount) {
      return [];
    }
    return Array.prototype.map.call(mount.querySelectorAll("button[data-terrain]"), function (button) {
      return button.getAttribute("data-terrain");
    });
  }

  /** 拖刷松手回调：把去重后的 hex 集合发**一条** map.SetTerrain；错例原文显示、不重试。 */
  async function commitBrush(hexes) {
    if (host.mapEditBusy) {
      return null;
    }
    if (!mapEditWriteGate(host.mapEditTool, "map.SetTerrain").ok) {
      setMapEditStatus("当前不是「地形」编辑线 ⇒ **未发出任何写命令**。", "warn");
      core.active.setBrushHexes([]);
      return null;
    }
    if (!host.brushTerrain) {
      setMapEditStatus("先选一种地形再涂抹。", "warn");
      core.active.setBrushHexes([]);
      return null;
    }
    if (!hexes || !hexes.length) {
      return null;
    }
    host.mapEditBusy = true;
    setMapEditStatus(
      "提交 map.SetTerrain：" + hexes.length + " 格 → " + host.brushTerrain + " …",
      "muted"
    );
    var result = await app.writeCommand("map.SetTerrain", {
      hexes: hexes,
      terrain: host.brushTerrain,
    });
    host.mapEditBusy = false;
    core.active.setBrushHexes([]);
    var last = hexes[hexes.length - 1];
    if (last) {
      app.setSelection({ kind: "hex", q: last.q, r: last.r });
    }
    if (result.ok) {
      setMapEditStatus(
        "已改 " + hexes.length + " 格为 " + host.brushTerrain + "（一条命令，head 已前进）",
        "ok"
      );
    } else {
      setMapEditStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    return result;
  }

  // ── 连通性（河流/道路）+ 圈选随机化（M8 T11）───────────────────────────────
  //
  // ★ 按键模型不变（M8-R 用户裁定）：左键恒为平移；右键按**当前工具**分派。
  // ★ 三条护栏都在纯函数里（`edgeChainEdges` / `edgeModeState` / `parseSeedInput` /
  //   `randomizeSelectionState`，均留在 map.js），宿主只负责"不 ok 就不发命令 + 给可见提示"。

  function setMapEditToolStatus(message, tone) {
    app.statusMessage(app.byId("map-edit-tool-status"), message, tone);
  }

  function setEdgeStatus(message, tone) {
    app.statusMessage(app.byId("edge-status"), message, tone);
  }

  function setRandomizeStatus(message, tone) {
    app.statusMessage(app.byId("randomize-status"), message, tone);
  }

  /** 显示/隐藏一条**可见提示**（护栏不 ok 时用；空文本 ⇒ 隐藏）。 */
  function setWarning(id, message) {
    var node = app.byId(id);
    if (!node) {
      return;
    }
    node.textContent = message || "";
    node.hidden = !message;
  }

  /** 把一个 radio 组里 value 匹配的那个置为 checked（找不到就什么都不做——不静默改别的）。 */
  function checkRadio(groupId, name, value) {
    var group = app.byId(groupId);
    if (!group) {
      return;
    }
    Array.prototype.forEach.call(group.querySelectorAll('input[name="' + name + '"]'), function (input) {
      input.checked = input.value === value;
    });
  }

  /** 读一个 radio 组里被选中的值；无组/无选中 ⇒ `null`。 */
  function checkedRadioValue(groupId, name) {
    var group = app.byId(groupId);
    var value = null;
    if (group) {
      Array.prototype.forEach.call(group.querySelectorAll('input[name="' + name + '"]'), function (input) {
        if (input.checked) {
          value = input.value;
        }
      });
    }
    return value;
  }

  /** 给一个 radio 组挂 change 监听：仅在选中时回调该值（照既有工具组的写法）。 */
  function wireRadioGroup(groupId, name, onPick) {
    var group = app.byId(groupId);
    if (!group) {
      return;
    }
    Array.prototype.forEach.call(group.querySelectorAll('input[name="' + name + '"]'), function (input) {
      input.addEventListener("change", function () {
        if (input.checked) {
          onPick(input.value);
        }
      });
    });
  }

  /** 把两个 radio 组（子选项 / 线内工具）都同步到 host 状态。 */
  function syncMapEditRadios() {
    checkRadio("map-edit-subtools", "map-edit-subtool", host.mapEditSubtool);
    checkRadio("terrain-tool-select", "map-edit-terrain-tool", host.mapEditTool);
    checkRadio("edge-kind-select", "map-edit-edge-kind", host.mapEditTool);
  }

  /**
   * ★ T3：依据**已注册组**重建 `#edge-kind-select` 的候选（服务端 overview.pathwayGroups 权威；默认 river/road）。
   * 重建后必须**重新挂** change 监听（innerHTML 换掉旧节点 ⇒ 旧监听一并消失）。标签取组的 `name`。
   */
  function renderEdgeKindOptions() {
    var mount = app.byId("edge-kind-select");
    if (!mount) {
      return;
    }
    var kinds = registeredEdgeKindList();
    var labelOf = {};
    (host.overviewGroups || []).forEach(function (group) {
      if (group && group.id) {
        labelOf[group.id] = group.name || group.id;
      }
    });
    mount.textContent = "";
    kinds.forEach(function (kind) {
      var label = document.createElement("label");
      var input = document.createElement("input");
      input.type = "radio";
      input.name = "map-edit-edge-kind";
      input.value = kind;
      label.appendChild(input);
      label.appendChild(document.createTextNode(" " + (labelOf[kind] || kind)));
      mount.appendChild(label);
    });
    wireRadioGroup("edge-kind-select", "map-edit-edge-kind", function (value) {
      selectMapEditTool(value);
    });
    checkRadio("edge-kind-select", "map-edit-edge-kind", host.mapEditTool);
  }

  /** 选一条编辑线（地形 / 连通性）：未知值 ⇒ 落到「地形」；线内工具跨线 ⇒ 落到该线默认工具。 */
  function setMapEditSubtool(value) {
    var subtool = mapEditSubtoolState(value).ok ? value : "terrain";
    host.mapEditSubtool = subtool;
    var tool = host.mapEditTool;
    if (mapEditSubtoolOf(tool) !== subtool) {
      tool = mapEditSubtoolDefaultTool(subtool);
    }
    selectMapEditTool(tool);
  }

  /** 选本线内的一个工具：切换可见控件组 + 重设右键分派（渲染器侧）+ 恢复该工具的选区。 */
  function selectMapEditTool(value) {
    // ★ T3：合法工具 = 地形组（terrain/randomize）+ **已注册的连通性组**（默认 river/road，自定义 canal …）；
    //   其它一律落到 terrain（fail-closed，不把未知串当工具）。
    var tool = mapEditSubtoolOf(value) ? value : "terrain";
    host.mapEditTool = tool;
    host.mapEditSubtool = mapEditSubtoolOf(tool) || "terrain";
    if (core.active && core.active.setEditTool) {
      core.active.setEditTool(tool);
    }
    var panels = mapEditPanelVisibility(tool);
    var terrainControls = app.byId("terrain-tool-controls");
    if (terrainControls) {
      terrainControls.hidden = !panels.terrain;
    }
    var edgeControls = app.byId("edge-controls");
    if (edgeControls) {
      edgeControls.hidden = !panels.connectivity;
    }
    var randomizeControls = app.byId("randomize-controls");
    if (randomizeControls) {
      randomizeControls.hidden = !panels.randomize;
    }
    setWarning("edge-mode-warning", "");
    setWarning("randomize-warning", "");
    if (tool === "randomize") {
      core.active.setBrushHexes([]);
      core.active.setDraftHexes(host.randomizeSelection);
      renderRandomizeStatus();
    } else {
      core.active.setDraftHexes([]);
      core.active.setBrushHexes([]);
    }
    if (tool === "terrain") {
      setMapEditToolStatus("编辑线：地形（地形刷）—— 右键拖动涂抹；左键=平移地图。", "muted");
    } else if (tool === "randomize") {
      setMapEditToolStatus("编辑线：地形（圈选随机化）—— 右键拖动圈选；左键=平移地图。", "muted");
    } else {
      setMapEditToolStatus(
        "编辑线：连通性（" +
          edgeKindLabel(tool) +
          "）—— 右键拖动连起相邻两格（一条 map.SetEdge）；左键点/拖命中边即删；空白处左键=平移地图。",
        "muted"
      );
      renderEdgeControls();
    }
    syncMapEditRadios();
  }

  /** `#edge-mode` 的当前值（空串 = 未选）。 */
  function edgeModeValue() {
    var node = app.byId("edge-mode");
    return node && typeof node.value === "string" ? node.value : "";
  }

  /** 依据 `#edge-mode` 的当前值刷新提示（★ 未选时**必须**有可见提示）。 */
  function renderEdgeControls() {
    var state = edgeModeState(edgeModeValue());
    if (state.ok) {
      setWarning("edge-mode-warning", "");
      setEdgeStatus("语义 " + state.mode + "：右键拖动连边。", "muted");
    } else {
      setWarning("edge-mode-warning", "未选 replace/merge：右键拖动不会发出任何写命令。");
      setEdgeStatus("先在上面选 replace 或 merge。", "warn");
    }
  }

  /** 连边松手：**先过护栏**（未选 mode / 非连通性线 ⇒ 一条命令都不发），再一次 app.writeCommand。 */
  async function commitEdge(chain) {
    core.active.setBrushHexes([]);
    if (host.mapEditBusy) {
      return null;
    }
    if (!mapEditWriteGate(host.mapEditTool, "map.SetEdge").ok) {
      setEdgeStatus("当前不是「连通性」编辑线 ⇒ **未发出任何写命令**。", "warn");
      return null;
    }
    var edges = (chain && chain.edges) || [];
    var path = (chain && chain.path) || [];
    var kind = host.mapEditTool; // 已是已注册组（selectMapEditTool 的 fail-closed 保证）
    var modeState = edgeModeState(edgeModeValue());
    if (!modeState.ok) {
      // ★ Q2 的 UI 侧：**不预选、不兜默认**——用户没选就一个字节都不发。
      setWarning("edge-mode-warning", "未选 replace/merge：右键拖动不会发出任何写命令。");
      setEdgeStatus(
        "未选连通性语义 ⇒ **未发出任何写命令**（本次拖动 " +
          edges.length +
          " 条边已丢弃；轨迹 " +
          path.length +
          " 格）。",
        "warn"
      );
      return null;
    }
    if (!edges.length) {
      setEdgeStatus(
        "非相邻/缺格的两格连不成边 ⇒ **未发出任何写命令**（轨迹 " + path.length + " 格）。",
        "warn"
      );
      return null;
    }
    host.mapEditBusy = true;
    setEdgeStatus("提交 map.SetEdge：" + kind + " × " + edges.length + " 条（" + modeState.mode + "）…", "muted");
    var result = await app.writeCommand("map.SetEdge", {
      kind: kind,
      edges: edges,
      mode: modeState.mode,
    });
    host.mapEditBusy = false;
    if (result.ok) {
      setEdgeStatus(
        "已改 " +
          kind +
          " " +
          edges.length +
          " 条边（" +
          modeState.mode +
          "，一条命令，head 已前进）" +
          (chain && chain.nonAdjacent ? "；非相邻/缺格段已跳过" : ""),
        "ok"
      );
      await refreshRegionInfoNow();
    } else {
      setEdgeStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    return result;
  }

  /**
   * ★ 左键删边（T3）：把"该 kind 的其余边"作为 {@code replace} 载荷发一条 map.SetEdge（命令面没有删单条边的命令）。
   * 删到一条不剩 ⇒ **不伪造命令**、给可见提示（{@code edgeDeletePlan} 的 `last-edge`）。
   */
  async function commitEdgeDelete(kind, deletedKeys) {
    if (host.mapEditBusy) {
      return null;
    }
    if (!mapEditWriteGate(kind, "map.SetEdge").ok) {
      setEdgeStatus("当前不是「连通性」编辑线 ⇒ **未发出任何写命令**。", "warn");
      return null;
    }
    var plan = edgeDeletePlan(kind, host.overviewEdges, deletedKeys);
    if (!plan.ok) {
      setEdgeStatus(
        "无法删除最后一条 " + kind + "：命令面 replace 不接受空集 ⇒ **未发出任何写命令**。",
        "warn"
      );
      return null;
    }
    host.mapEditBusy = true;
    setEdgeStatus("删除 " + kind + " " + (deletedKeys || []).length + " 条边（replace 其余 " + plan.edges.length + " 条）…", "muted");
    var result = await app.writeCommand("map.SetEdge", {
      kind: plan.kind,
      edges: plan.edges,
      mode: plan.mode,
    });
    host.mapEditBusy = false;
    if (result.ok) {
      setEdgeStatus("已删 " + (deletedKeys || []).length + " 条 " + kind + "（一条命令，head 已前进）", "ok");
      await refreshRegionInfoNow();
    } else {
      setEdgeStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    return result;
  }

  /** 圈选松手：选区**持久显示**、**不发命令**（命令由「执行随机化」按钮发）。 */
  function commitRandomizeSelection(hexes) {
    var state = randomizeSelectionState(hexes);
    host.randomizeSelection = state.hexes;
    core.active.setBrushHexes([]);
    core.active.setDraftHexes(host.randomizeSelection);
    setWarning("randomize-warning", "");
    renderRandomizeStatus();
    return null;
  }

  function renderRandomizeStatus() {
    var count = host.randomizeSelection.length;
    var node = app.byId("randomize-seed");
    var seedState = parseSeedInput(node ? node.value : "");
    setRandomizeStatus(
      "选区 " + count + " 格；seed " + (seedState.ok ? seedState.seed : "（未填/非法）") + "。",
      count && seedState.ok ? "ok" : "muted"
    );
  }

  /** 「执行随机化」：**先过两条护栏**（空选区 / seed 非整数 ⇒ 一条命令都不发），再一次 writeCommand。 */
  async function submitRandomize() {
    if (host.mapEditBusy) {
      return null;
    }
    if (!mapEditWriteGate(host.mapEditTool, "map.RandomizeRegion").ok) {
      setWarning("randomize-warning", "当前不是「地形」编辑线 ⇒ **未发出任何写命令**。");
      setRandomizeStatus("不在「地形」编辑线 ⇒ 未发出任何写命令。", "warn");
      return null;
    }
    var selState = randomizeSelectionState(host.randomizeSelection);
    if (!selState.ok) {
      setWarning("randomize-warning", "选区为空：先在图上右键拖动圈选，**没有发出任何写命令**。");
      setRandomizeStatus("选区为空 ⇒ 未发出任何写命令。", "warn");
      return null;
    }
    var seedNode = app.byId("randomize-seed");
    var seedState = parseSeedInput(seedNode ? seedNode.value : "");
    if (!seedState.ok) {
      setWarning("randomize-warning", "seed 必须是整数（Java long）：**没有发出任何写命令**。");
      setRandomizeStatus("seed 非法 ⇒ 未发出任何写命令。", "warn");
      return null;
    }
    setWarning("randomize-warning", "");
    host.mapEditBusy = true;
    setRandomizeStatus(
      "提交 map.RandomizeRegion：" + selState.hexes.length + " 格，seed " + seedState.seed + " …",
      "muted"
    );
    var result = await app.writeCommand("map.RandomizeRegion", {
      hexes: selState.hexes,
      seed: seedState.seed,
    });
    host.mapEditBusy = false;
    if (result.ok) {
      setRandomizeStatus(
        "已随机化 " + selState.hexes.length + " 格（seed " + seedState.seed + "，一条命令，head 已前进）",
        "ok"
      );
      await refreshRegionInfoNow();
    } else {
      setRandomizeStatus(result.message, result.kind === "rejected" ? "err" : "warn");
    }
    return result;
  }

  /** 写命令落地后立刻重取一次区域信息面板（否则缓存键不变、面板停留在旧值）。 */
  async function refreshRegionInfoNow() {
    var selection = app.getState().selection;
    if (!selection || selection.kind !== "hex") {
      return;
    }
    host.regionInfoKey = null;
    renderRegionInfo(selection);
  }

  function clearRegionInfo() {
    host.regionInfoKey = null;
    app.clear(app.byId("region-info-detail"));
    var editor = app.byId("region-meta-editor");
    if (editor) {
      editor.hidden = true;
    }
  }

  /** 区域信息面板（M8 T8；T10 起 map-edit 与 region-edit 共用）：显示所选 hex 的多值 regions 与地形。 */
  function renderRegionInfo(selection) {
    if (!app.byId("region-info-detail")) {
      return;
    }
    var mode = app.getState().mode;
    if (mode !== "map-edit" && mode !== "region-edit") {
      return;
    }
    if (!selection || selection.kind !== "hex") {
      clearRegionInfo();
      if (mode === "region-edit" && host.regionFocus) {
        fillRegionMetaEditor([host.regionFocus]);
        setRegionInfoStatus("区域编辑目标：" + host.regionFocus, "muted");
      } else {
        setRegionInfoStatus("点选一个格子查看其所属区域与地形。", "muted");
      }
      return;
    }
    var key = selection.q + "," + selection.r + "@" + targetKey();
    if (key === host.regionInfoKey) {
      return;
    }
    host.regionInfoKey = key;
    setRegionInfoStatus("查询 (" + selection.q + "," + selection.r + ") …", "muted");
    api
      .mapHex(selection.q, selection.r, app.target())
      .then(function (hex) {
        if (key !== host.regionInfoKey) {
          return;
        }
        var detail = app.clear(app.byId("region-info-detail"));
        var terrainText = hex.terrain;
        if (hex.terrainType && hex.terrainType.name) {
          terrainText = hex.terrain + "（" + hex.terrainType.name + "）";
        }
        appendInfoRow(detail, "q", hex.q);
        appendInfoRow(detail, "r", hex.r);
        appendInfoRow(detail, "terrain", terrainText);
        var regions = Array.isArray(hex.regions) ? hex.regions : [];
        appendInfoRow(detail, "regions", regions.length ? regions.join("、") : "无区域");
        appendInfoRow(detail, "连通性", edgeSummaryText(hex.edges));
        fillRegionMetaEditor(regions);
        setRegionInfoStatus(
          "(" + hex.q + "," + hex.r + ") · " + regions.length + " 个区域",
          "ok"
        );
      })
      .catch(function (e) {
        if (key !== host.regionInfoKey) {
          return;
        }
        clearRegionInfo();
        setRegionInfoStatus("查询失败：" + e.message, "err");
      });
  }

  function fillRegionMetaEditor(regionIds) {
    var editor = app.byId("region-meta-editor");
    var select = app.byId("region-meta-target");
    if (!editor || !select) {
      return;
    }
    if (!regionIds.length) {
      editor.hidden = true;
      return;
    }
    editor.hidden = false;
    var previous = select.value;
    app.clear(select);
    regionIds.forEach(function (id) {
      select.appendChild(app.el("option", { value: id, text: id }));
    });
    select.value = regionIds.indexOf(previous) >= 0 ? previous : regionIds[0];
    loadRegionMeta(select.value);
  }

  async function loadRegionMeta(id) {
    if (!id) {
      return;
    }
    try {
      var region = await api.mapRegion(id, app.target());
      var meta = region.meta || {};
      app.byId("region-meta-color").value = meta.color || "";
      app.byId("region-meta-tag").value = meta.tag || "";
      app.byId("region-meta-description").value = meta.description || "";
      app.byId("region-meta-annexedby").value = meta.annexedBy || "";
    } catch (e) {
      setRegionInfoStatus("区域元数据载入失败：" + e.message, "warn");
    }
  }

  /** 只改 meta（不带 hexes）⇒ map.UpdateRegion；区域内容不动。 */
  async function submitRegionMeta() {
    if (host.mapEditBusy) {
      return null;
    }
    var select = app.byId("region-meta-target");
    var id = select ? select.value : "";
    if (!id) {
      setRegionInfoStatus("先选一个区域。", "warn");
      return null;
    }
    var meta = {
      color: textOrNull("region-meta-color"),
      tag: textOrNull("region-meta-tag"),
      description: textOrNull("region-meta-description"),
      annexedBy: textOrNull("region-meta-annexedby"),
    };
    host.mapEditBusy = true;
    setRegionInfoStatus("提交元数据 " + id + " …", "muted");
    var result = await app.writeCommand("map.UpdateRegion", { regionId: id, meta: meta });
    host.mapEditBusy = false;
    setRegionInfoStatus(
      result.ok ? "已更新 " + id + " 的元数据" : result.message,
      result.ok ? "ok" : result.kind === "rejected" ? "err" : "warn"
    );
    return result;
  }

  function wireMapEditor() {
    var submit = app.byId("region-meta-submit");
    if (submit) {
      submit.addEventListener("click", submitRegionMeta);
    }
    var select = app.byId("region-meta-target");
    if (select) {
      select.addEventListener("change", function () {
        loadRegionMeta(select.value);
      });
    }
    // ★ T2：三个单选组——编辑线（地形/连通性）、地形线内工具、连通性线内类型。
    wireRadioGroup("map-edit-subtools", "map-edit-subtool", function (value) {
      setMapEditSubtool(value);
    });
    wireRadioGroup("terrain-tool-select", "map-edit-terrain-tool", function (value) {
      selectMapEditTool(value);
    });
    wireRadioGroup("edge-kind-select", "map-edit-edge-kind", function (value) {
      selectMapEditTool(value);
    });
    // ★ replace/merge 选择器：**初始为空串（无预选）**，切换只刷新提示与 host 记录。
    var modeNode = app.byId("edge-mode");
    if (modeNode) {
      modeNode.addEventListener("change", function () {
        host.edgeMode = modeNode.value;
        renderEdgeControls();
      });
    }
    var seedNode = app.byId("randomize-seed");
    if (seedNode) {
      seedNode.addEventListener("input", renderRandomizeStatus);
    }
    var randomizeSubmit = app.byId("randomize-submit");
    if (randomizeSubmit) {
      randomizeSubmit.addEventListener("click", submitRandomize);
    }
    setMapEditSubtool(host.mapEditSubtool);
  }

  /** kind 的可见标签：优先用服务端组名，回退内置中文，再回退原串（不静默成空）。 */
  function edgeKindLabel(kind) {
    var groups = host.overviewGroups || [];
    for (var i = 0; i < groups.length; i++) {
      if (groups[i] && groups[i].id === kind) {
        return groups[i].name || kind;
      }
    }
    return kind === "river" ? "河流" : kind === "road" ? "道路" : kind;
  }

  window.SimosMapEditor = {
    // map.js（initHost / reloadOverview / onStateChange + SimosMap 调试投影）惰性调用这些入口。
    setMapEditStatus: setMapEditStatus,
    setRegionInfoStatus: setRegionInfoStatus,
    renderTerrainPalette: renderTerrainPalette,
    selectTerrain: selectTerrain,
    paletteKeys: paletteKeys,
    checkedRadioValue: checkedRadioValue,
    edgeModeValue: edgeModeValue,
    commitBrush: commitBrush,
    commitEdge: commitEdge,
    commitEdgeDelete: commitEdgeDelete,
    commitRandomizeSelection: commitRandomizeSelection,
    renderRandomizeStatus: renderRandomizeStatus,
    refreshRegionInfoNow: refreshRegionInfoNow,
    clearRegionInfo: clearRegionInfo,
    renderRegionInfo: renderRegionInfo,
    wireMapEditor: wireMapEditor,
  };
  // ★ renderer.js 在**加载期**做 `var renderEdgeKindOptions = core.renderEdgeKindOptions` 取快照 ⇒
  //   该成员必须由本文件挂回 core（本文件排在 renderer.js 之前）。
  window.SimosMapCore.renderEdgeKindOptions = renderEdgeKindOptions;
})();
