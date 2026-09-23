// unitTree.js —— 单位编制**倒树**（M7 T2 骨架 → T5 落地；spec §六 / 判据⑥）。
// ★ 无框架、无构建：组树与渲染全用原生 DOM。数据只来自 /api/units 的 parent 字段（纯前端组树，
//   零后端改动、不加第二份真相）；本文件不含任何写调用。
// ★ 方向：**倒置**——根在下、下级向上生长（spec §六 字面）。分岔点（子数 ≥ 2）加粗放大，
//   点它 ⇒ 展开/收起该分支下的详情（该节点 + 其全部后代）。
// ★ 2026-09-23 UI 改造：编制从**左栏内联**改成**独立可拖动浮层**（#unit-panel）：
//   - 左栏只留按钮 #unit-panel-open；浮层由标题栏（#unit-panel-drag）原生 pointer 事件拖动，
//     位置**夹在视口内**（clampPanelPosition 纯函数）；开合落在按钮 aria-expanded + 浮层 hidden。
//   - 浮层顶部**军队选择**（#unit-army-select，选项 = 森林的根）；树**只渲染选中那一支军队**的子树
//     （subtreeOf 纯函数）——修掉"单看一个单位却摊出多国军队编制"。
//   - 选中单位变化时（applyFocus）若它属于另一支军队 ⇒ **自动切到那支军队**。
// ★ 2026-09-24 修正 2：树视口（#unit-tree-mount）改成**自由视图**——不再靠 overflow/滑条左右拉，
//   内容层 `.tree-canvas` 用 transform: translate(pan.x, pan.y) 平移，鼠标四面八方可拖；平移量由
//   纯函数 clampTreePan **有界**夹取（内容比视口小 ⇒ 居中），渲染/开合/resize 后都用同一函数重夹；
//   `focusUnit` 由旧的 `scrollIntoView`（自由视图下已是死代码）改为"设 pan 让节点可见"；
//   带一个复位按钮 #unit-tree-reset。
// ★ 纯函数（不查 IO、不碰 DOM）⇒ 可在 Node 里用冻结夹具直接断言：buildTree / isBranchPoint /
//   armyOptions / subtreeOf / rootIdOf / clampPanelPosition / clampTreePan。


(function () {
  "use strict";

  var app = window.SimosApp;
  var api = window.SimosApi;

  var requestToken = 0;
  var lastTargetKey = null;
  var lastUnits = [];
  // ★ 2026-09-23：当前查看的**军队（森林的根）**。null ⇒ 下次 render 时按选中单位/第一支决定。
  var selectedRootId = null;
  // 浮层拖动状态与"是否已摆过初始位置"标志（首次打开时贴着左栏右缘）。
  var dragState = null;
  var panelPositioned = false;
  // ★ 2026-09-24 修正 2：编制树改成**自由视图**——树视口（#unit-tree-mount）不滚动，内容层
  //   `.tree-canvas` 用 `transform: translate(pan.x, pan.y)` 平移，可鼠标四面八方拖动。
  var treePan = { x: 0, y: 0 };
  var treeCanvas = null; // 当前内容层（render 时重建）
  var panState = null; // {pointerId,startX,startY,originX,originY,active}：拖动平移状态
  var PAN_DRAG_THRESHOLD = 3; // 屏幕 px：超过才认定为"拖动"（否则保留节点点击）

  function targetKey() {
    var t = app && app.target ? app.target() : { branch: "main", revision: null };
    return t.branch + "@" + (t.revision === null || t.revision === undefined ? "head" : t.revision);
  }

  /**
   * 分岔点：**子节点数 ≥ 2**（spec §六 的"标大"口径）。这是 R7 的分岔点判定器；纯函数。
   *
   * <p>★ 只认直接子节点（不是后代总数）——`A→B→C` 的三层链里 B 只有一个子 C，B **不是**分岔点。
   */
  function isBranchPoint(node) {
    return !!node && node.children.length >= 2;
  }

  /**
   * 由扁平 units 列表组出森林（多根并列）。**纯函数**：不查 IO、不碰 DOM。
   *
   * <p>输入：`[{id, name, parent, …}]`（/api/units 的形状）。输出：根节点数组，每个节点为
   * `{id, name, parent, children, depth, descendantCount, branch}`。`children` 按输入顺序；
   * 根的 `depth = 0`，子 = 父 + 1。
   *
   * <p>★ **孤例口径**：`parent` 缺失 / 为 `null` / 指向**不存在的 id** 的单位一律**当根**（多根并列），
   * 原 `parent` 值**原样保留**在节点上（不篡改数据）。理由：spec §六 说"孤例：`parent` 为空的单位都是根"，
   * 且"前端不做环检测（不造第二份真相）"；一个悬空父指针在领域层本就被拒绝，前端把它当根是**降级显示**，
   * 比抛错让整棵左栏变空白更符合"只读展示"。⇒ **本函数假设输入无环**（领域 `UnitState` 的保证），
   * 不做环检测（spec §六 明文）。
   */
  function buildTree(units) {
    var list = Array.isArray(units) ? units : [];
    var nodes = new Map();
    var order = [];

    list.forEach(function (unit) {
      if (!unit || unit.id === null || unit.id === undefined) {
        return;
      }
      var node = {
        id: String(unit.id),
        name: unit.name === undefined ? null : unit.name,
        parent: unit.parent === undefined ? null : unit.parent,
        children: [],
        depth: 0,
        descendantCount: 0,
        branch: false,
      };
      nodes.set(node.id, node);
      order.push(node);
    });

    var roots = [];
    order.forEach(function (node) {
      var parentId = node.parent === null || node.parent === undefined ? null : String(node.parent);
      var parent = parentId === null ? null : nodes.get(parentId);
      if (parent && parent !== node) {
        parent.children.push(node);
      } else {
        roots.push(node);
      }
    });

    // 深度：自根向下逐层。以 order 长度为上界，环（领域不会给）也不会死循环。
    var queue = roots.slice();
    var steps = 0;
    while (queue.length && steps <= order.length) {
      var current = queue.shift();
      steps += 1;
      current.children.forEach(function (child) {
        child.depth = current.depth + 1;
        queue.push(child);
      });
    }

    // 后代数：后序（自叶向上）。
    function count(node) {
      var total = 0;
      node.children.forEach(function (child) {
        total += 1 + count(child);
      });
      node.descendantCount = total;
      return total;
    }
    roots.forEach(count);

    order.forEach(function (node) {
      node.branch = isBranchPoint(node);
    });
    return roots;
  }

  /** 深度优先展开为扁平列表（含自身）。 */
  function flatten(nodes) {
    var out = [];
    (nodes || []).forEach(function (node) {
      out.push(node);
      out = out.concat(flatten(node.children));
    });
    return out;
  }

  /**
   * 军队选项：单位森林的每个**根** → `[{id, name}…]`（输入顺序 = 根的输入序）。**纯函数**。
   * 根的判定沿用 buildTree 的口径（parent 缺失/null/悬空 ⇒ 当根）。
   */
  function armyOptions(units) {
    return buildTree(units).map(function (root) {
      return { id: root.id, name: root.name };
    });
  }

  /**
   * 取某支军队（根）的**子树节点**；**纯函数**，不抛。
   *
   * <p>口径：`rootId` 必须等于某个**根节点的 id**（`String(rootId)` 后比较）。
   * 不存在 / 是悬空父 id（如孤例 H 的 parent "ghost"——"ghost" 不是任何根节点的 id）/ `null`
   * ⇒ 返回 `null`（调用方渲染"暂无单位"，而不是抛错或偷偷退回整片森林）。
   */
  function subtreeOf(units, rootId) {
    if (rootId === null || rootId === undefined) {
      return null;
    }
    var want = String(rootId);
    var roots = buildTree(units);
    for (var i = 0; i < roots.length; i++) {
      if (roots[i].id === want) {
        return roots[i];
      }
    }
    return null;
  }

  /**
   * 某单位所属**军队的根 id**；找不到该单位 ⇒ `null`。**纯函数**。
   * 自 unitId 沿 parent 链上溯：parent 缺失 / 越出已知单位集（悬空）⇒ 当前节点即根。带环保护（领域无环）。
   */
  function rootIdOf(units, unitId) {
    if (unitId === null || unitId === undefined) {
      return null;
    }
    var list = Array.isArray(units) ? units : [];
    var known = new Map();
    list.forEach(function (unit) {
      if (unit && unit.id !== null && unit.id !== undefined) {
        known.set(String(unit.id), unit);
      }
    });
    var current = String(unitId);
    if (!known.has(current)) {
      return null;
    }
    var steps = 0;
    while (steps <= known.size) {
      var unit = known.get(current);
      var parentId = unit.parent === null || unit.parent === undefined ? null : String(unit.parent);
      if (parentId === null || parentId === current || !known.has(parentId)) {
        return current;
      }
      current = parentId;
      steps += 1;
    }
    return current; // 环保护兜底（领域 UnitState 保证无环）
  }

  function clampNumber(value, lo, hi) {
    var n = Number(value);
    if (!isFinite(n)) {
      return lo;
    }
    return n < lo ? lo : n > hi ? hi : n;
  }

  /**
   * 把浮层位置**夹在视口内**（左上角坐标，CSS px）。**纯函数**，不碰 DOM。
   *
   * <p>上界 = `视口边长 − 浮层对应边长`（浮层比视口还大时上界取 0 ⇒ 贴左上角）。
   * 非有限数按 lo 处理；输入不合法（缺字段）按 0 处理 ⇒ 不抛。
   */
  function clampPanelPosition(position, size, viewport) {
    var pos = position || {};
    var dim = size || {};
    var view = viewport || {};
    var width = dim.width || 0;
    var height = dim.height || 0;
    var maxX = Math.max(0, (view.width || 0) - width);
    var maxY = Math.max(0, (view.height || 0) - height);
    return {
      x: clampNumber(pos.x, 0, maxX),
      y: clampNumber(pos.y, 0, maxY),
    };
  }

  function finiteOr(value, fallback) {
    var n = Number(value);
    return isFinite(n) ? n : fallback;
  }

  /**
   * 平移量的**单轴夹取**。**纯函数**。
   *
   * <p>内容放在 `pan` 处、占据 `[pan, pan + content]`；视口是 `[0, viewport]`。
   * - `content > viewport` ⇒ 允许范围 `[viewport − content, 0]`（负数区间）：两端分别对应
   *   "内容末尾贴视口末尾"与"内容开头贴视口开头"，中间任意位置都能看到内容的一部分。
   * - `content <= viewport`（内容不比视口大）⇒ **居中**：返回唯一值 `(viewport − content) / 2`
   *   （此时**忽略**传入的 pan ⇒ 拖不动，也不会露出"内容外的空白再被拽走"）。这是本函数写明的
   *   "内容小于视口"口径。
   * - 非有限 pan 按 0 处理；`content`/`viewport` 非有限按 0。
   */
  function clampPanAxis(value, content, viewport) {
    var cv = Math.max(0, finiteOr(content, 0));
    var vp = Math.max(0, finiteOr(viewport, 0));
    if (cv <= vp) {
      return (vp - cv) / 2; // 内容不比视口大 ⇒ 居中（唯一允许值）
    }
    var lo = vp - cv; // < 0
    var n = finiteOr(value, 0);
    return n < lo ? lo : n > 0 ? 0 : n;
  }

  /**
   * 把树的**平移量**夹到有界范围内（CSS px 的 `translate` 量）。**纯函数**，不碰 DOM。
   *
   * <p>`clampTreePan(pan, contentSize, viewportSize) -> {x, y}`；两轴各自按 {@link clampPanAxis}
   * 的口径。内容比视口小的那一轴 ⇒ 居中（拖不走）；比视口大的那一轴 ⇒ 夹在
   * `[viewport − content, 0]`（内容始终至少露出一部分，不会整幅被拖出视野）。
   */
  function clampTreePan(pan, contentSize, viewportSize) {
    var p = pan || {};
    var c = contentSize || {};
    var v = viewportSize || {};
    return {
      x: clampPanAxis(p.x, c.width, v.width),
      y: clampPanAxis(p.y, c.height, v.height),
    };
  }

  function displayName(node) {
    return node.name === null || node.name === undefined || node.name === "" ? node.id : node.name;
  }

  /** 选中单位所属的军队根 id；无选中单位 / 找不到该单位 ⇒ null。 */
  function defaultRootId(units) {
    var state = app && app.getState ? app.getState() : null;
    var selection = state ? state.selection : null;
    if (selection && selection.kind === "unit") {
      return rootIdOf(units, selection.id);
    }
    return null;
  }

  function optionExists(options, id) {
    return options.some(function (opt) {
      return opt.id === id;
    });
  }

  /** 军队下拉：选项 = 森林的根；把 value 设到 selectedId（失效则回落第一项）。 */
  function syncArmySelect(options, selectedId) {
    var select = app.byId("unit-army-select");
    if (!select) {
      return;
    }
    app.clear(select);
    options.forEach(function (opt) {
      select.appendChild(
        app.el("option", { value: opt.id, text: displayName(opt) + "（" + opt.id + "）" })
      );
    });
    select.value = optionExists(options, selectedId) ? selectedId : options[0].id;
  }

  /** 一个节点方块：name（无 name 退 id）+ id；分岔点加 branch class。 */
  function nodeBox(node) {
    var box = app.el("div", {
      class: "tree-node" + (node.branch ? " branch" : ""),
      "data-unit-id": node.id,
      "data-depth": node.depth,
      "data-parent": node.parent === null || node.parent === undefined ? "" : node.parent,
      "data-branch": node.branch ? "true" : "false",
      role: "button",
      tabindex: "0",
      title: node.id,
    });
    box.appendChild(app.el("span", { class: "tree-node-name", text: displayName(node) }));
    box.appendChild(app.el("span", { class: "tree-node-id", text: node.id }));
    box.addEventListener("click", function () {
      selectNode(node);
    });
    box.addEventListener("keydown", function (event) {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        selectNode(node);
      }
    });
    return box;
  }

  /** 一个子树的倒置布局：**子行在上、节点在下**（根在下、下级向上生长）。 */
  function subtree(node) {
    var wrap = app.el("div", { class: "tree-subtree" });
    if (node.children.length) {
      var row = app.el("div", { class: "tree-children" });
      node.children.forEach(function (child) {
        row.appendChild(subtree(child));
      });
      wrap.appendChild(row);
    }
    wrap.appendChild(nodeBox(node));
    if (node.branch) {
      wrap.appendChild(branchDetail(node));
    }
    return wrap;
  }

  /** 分岔点的可展开详情：该节点 + 其全部后代，各一行（默认收起）。 */
  function branchDetail(node) {
    var detail = app.el("div", { class: "tree-branch-detail", "data-for": node.id });
    detail.hidden = true;
    detail.appendChild(
      app.el("p", {
        class: "tree-branch-title",
        text: "分支 " + node.id + " · 共 " + (node.descendantCount + 1) + " 个单位",
      })
    );
    flatten([node]).forEach(function (unit) {
      detail.appendChild(
        app.el("div", {
          class: "branch-row",
          "data-unit-id": unit.id,
          "data-depth": unit.depth,
          text: unit.id + " · " + displayName(unit),
        })
      );
    });
    return detail;
  }

  /** 点节点 ⇒ 左栏出该单位详情；分岔点额外展开/收起本分支详情。 */
  function selectNode(node) {
    if (app && app.setSelection) {
      app.setSelection({ kind: "unit", id: node.id });
    }
    if (node.branch) {
      toggleBranch(node.id);
    }
  }

  /** 展开/收起某分岔点的详情区（不存在则忽略）。 */
  function toggleBranch(id) {
    var detail = document.querySelector('.tree-branch-detail[data-for="' + id + '"]');
    if (detail) {
      detail.hidden = !detail.hidden;
    }
    return detail;
  }

  // ── 自由视图：平移量的量取/应用/夹取（纯夹取逻辑在 clampTreePan）────────────────────

  function measureTreeViewport() {
    var mount = app.byId("unit-tree-mount");
    return { width: mount ? mount.clientWidth || 0 : 0, height: mount ? mount.clientHeight || 0 : 0 };
  }

  function measureTreeContent() {
    return treeCanvas
      ? { width: treeCanvas.offsetWidth || 0, height: treeCanvas.offsetHeight || 0 }
      : { width: 0, height: 0 };
  }

  function applyTreePan() {
    if (treeCanvas) {
      treeCanvas.style.transform = "translate(" + treePan.x + "px, " + treePan.y + "px)";
    }
  }

  /** 设置平移量并按**同一纯函数** clampTreePan 夹取（内容/视口尺寸现取）。 */
  function setTreePan(desired) {
    treePan = clampTreePan(desired, measureTreeContent(), measureTreeViewport());
    applyTreePan();
  }

  /** 面板尺寸变化 / 重新渲染之后重新夹取（同一纯函数）——绝不留一个越界的 pan。 */
  function reclampTreePan() {
    if (!treeCanvas) {
      return;
    }
    treePan = clampTreePan(treePan, measureTreeContent(), measureTreeViewport());
    applyTreePan();
  }

  /** 复位平移：即 `{x:0,y:0}` 经 clampTreePan（内容大于视口 ⇒ 左上角对齐；小于 ⇒ 居中）。 */
  function resetTreePan() {
    setTreePan({ x: 0, y: 0 });
  }

  /**
   * 让某节点可见：把 pan 平移一个增量使它落进视口（随后由 setTreePan 夹取）。
   * ★ 2026-09-24 修正 2：取代旧的 `scrollIntoView`——自由视图没有滑条，`scrollIntoView` 已是死代码。
   */
  function revealNode(target) {
    var mount = app.byId("unit-tree-mount");
    if (!mount || !treeCanvas || !target.getBoundingClientRect) {
      return;
    }
    var view = mount.getBoundingClientRect();
    var box = target.getBoundingClientRect();
    var dx = 0;
    var dy = 0;
    if (box.left < view.left) {
      dx = view.left - box.left;
    } else if (box.right > view.right) {
      dx = view.right - box.right;
    }
    if (box.top < view.top) {
      dy = view.top - box.top;
    } else if (box.bottom > view.bottom) {
      dy = view.bottom - box.bottom;
    }
    if (dx !== 0 || dy !== 0) {
      setTreePan({ x: treePan.x + dx, y: treePan.y + dy });
    }
  }

  /** 把选中单位在树里高亮并平移使其可见（地图点单位联动，判据③/⑤）。 */
  function focusUnit(id) {
    if (id === null || id === undefined) {
      return;
    }
    Array.prototype.forEach.call(document.querySelectorAll(".tree-node.selected"), function (node) {
      node.classList.remove("selected");
    });
    var target = document.querySelector('.tree-node[data-unit-id="' + id + '"]');
    if (!target) {
      return;
    }
    target.classList.add("selected");
    revealNode(target);
  }

  /** 用扁平 units 渲染**选中那一支军队**的倒树（清空后重建）。其它军队的单位一个字都不渲染。 */
  function render(units) {
    var mount = app.byId("unit-tree-mount");
    lastUnits = Array.isArray(units) ? units : [];
    if (!mount) {
      return;
    }
    app.clear(mount);
    treeCanvas = null; // 内容层随清空作废（reclampTreePan 会因此静默跳过）
    var options = armyOptions(lastUnits);
    if (!options.length) {
      selectedRootId = null;
      mount.appendChild(app.el("p", { class: "empty", text: "暂无单位。" }));
      return;
    }
    // 默认/失效时重定当前军队：选中单位所属的根 ⇒ 回落第一支（目标数据一换，旧 root 可能已不存在）。
    if (!selectedRootId || !optionExists(options, selectedRootId)) {
      selectedRootId = defaultRootId(lastUnits) || options[0].id;
    }
    syncArmySelect(options, selectedRootId);
    var root = subtreeOf(lastUnits, selectedRootId);
    if (!root) {
      mount.appendChild(app.el("p", { class: "empty", text: "暂无单位。" }));
      return;
    }
    // ★ 2026-09-24 修正 2：树挂在可平移的**内容层** .tree-canvas 上（视口 = mount，overflow:hidden）。
    var forest = app.el("div", { class: "tree-forest" });
    forest.appendChild(subtree(root));
    treeCanvas = app.el("div", { class: "tree-canvas" });
    treeCanvas.appendChild(forest);
    mount.appendChild(treeCanvas);
    // 重新渲染后内容尺寸可能变了 ⇒ 用**同一个纯函数**重夹，绝不留下越界的 pan。
    reclampTreePan();
    highlightSelection();
  }

  /** 高亮当前选中单位（在已渲染的军队子树里；不在则静默——用户手动切到别的军队时不该被弹回来）。 */
  function highlightSelection() {
    var state = app && app.getState ? app.getState() : null;
    var selection = state ? state.selection : null;
    if (selection && selection.kind === "unit") {
      focusUnit(selection.id);
    }
  }

  /** 选中单位变化：属于**另一支军队** ⇒ 自动切过去（在树里定位由 render 末尾的 highlightSelection 完成）。 */
  function applyFocus() {
    var state = app && app.getState ? app.getState() : null;
    var selection = state ? state.selection : null;
    if (!selection || selection.kind !== "unit") {
      return;
    }
    var root = rootIdOf(lastUnits, selection.id);
    if (root && root !== selectedRootId) {
      selectedRootId = root;
      render(lastUnits);
      return;
    }
    focusUnit(selection.id);
  }

  /** 按当前只读目标 {branch, revision} 重取 /api/units 并渲染。 */
  function refresh() {
    if (!api) {
      return;
    }
    var token = ++requestToken;
    api
      .cachedUnits(app.target())
      .then(function (body) {
        if (token !== requestToken) {
          return;
        }
        lastUnits = (body && body.units) || [];
        render(lastUnits);
      })
      .catch(function (e) {
        if (token !== requestToken) {
          return;
        }
        var mount = app.byId("unit-tree-mount");
        if (mount) {
          app.clear(mount);
          mount.appendChild(app.el("p", { class: "empty", text: "单位读取失败：" + e.message }));
        }
      });
  }

  /** 状态变化：目标坐标变了 ⇒ 重取渲染；否则只把选中单位定位到树里（选择变化不重取，但可能切军队）。 */
  function onState(state) {
    var key = targetKey();
    if (key !== lastTargetKey) {
      lastTargetKey = key;
      refresh();
      return;
    }
    if (state && state.selection && state.selection.kind === "unit") {
      applyFocus(); // ★ 经 applyFocus ⇒ 选中单位属另一支军队时自动切过去
    }
  }

  // ── 浮层（#unit-panel）：开合 + 标题栏拖动（位置夹在视口内）────────────────────

  function viewportSize() {
    return {
      width: window.innerWidth || (document.documentElement && document.documentElement.clientWidth) || 1024,
      height: window.innerHeight || (document.documentElement && document.documentElement.clientHeight) || 768,
    };
  }

  function panelSizeOf(panel) {
    return { width: panel.offsetWidth || 0, height: panel.offsetHeight || 0 };
  }

  function applyPanelPosition(panel, position) {
    panel.style.left = position.x + "px";
    panel.style.top = position.y + "px";
  }

  /** 首次打开：贴着左栏右缘（.col-left / #left-panel），并夹在视口内。 */
  function positionPanelInitial(panel) {
    var leftPanel = app.byId("left-panel");
    var rect = leftPanel && leftPanel.getBoundingClientRect ? leftPanel.getBoundingClientRect() : null;
    var desired = { x: rect ? rect.right + 12 : 12, y: rect ? rect.top : 12 };
    applyPanelPosition(panel, clampPanelPosition(desired, panelSizeOf(panel), viewportSize()));
  }

  /** 开/合浮层：同步面板 hidden 与按钮 aria-expanded（两者都可断言）。 */
  function setPanelOpen(open) {
    var panel = app.byId("unit-panel");
    if (!panel) {
      return;
    }
    panel.hidden = !open;
    if (open && !panelPositioned) {
      // ★ 必须在 hidden=false 之后再量尺寸（display:none 时 offsetWidth=0）。
      positionPanelInitial(panel);
      panelPositioned = true;
    }
    if (open) {
      // 打开后视口才有尺寸 ⇒ 用同一纯函数重夹（隐藏期间画面尺寸量不到，pan 可能落在旧边界外）。
      reclampTreePan();
    }
    var button = app.byId("unit-panel-open");
    if (button) {
      button.setAttribute("aria-expanded", open ? "true" : "false");
    }
  }

  /** 只在浮层**内**的 pointerdown（标题栏）才起拖 ⇒ 绝不干扰地图拖动。 */
  function startDrag(event, panel, handle) {
    var rect = panel.getBoundingClientRect();
    dragState = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      originX: rect.left,
      originY: rect.top,
    };
    if (handle.setPointerCapture) {
      handle.setPointerCapture(event.pointerId);
    }
    if (event.preventDefault) {
      event.preventDefault();
    }
  }

  function onDragMove(event) {
    if (!dragState || event.pointerId !== dragState.pointerId) {
      return;
    }
    var panel = app.byId("unit-panel");
    if (!panel) {
      return;
    }
    var desired = {
      x: dragState.originX + (event.clientX - dragState.startX),
      y: dragState.originY + (event.clientY - dragState.startY),
    };
    applyPanelPosition(panel, clampPanelPosition(desired, panelSizeOf(panel), viewportSize()));
  }

  function onDragEnd(event) {
    if (!dragState) {
      return;
    }
    var handle = app.byId("unit-panel-drag");
    if (handle && handle.releasePointerCapture) {
      try {
        handle.releasePointerCapture(dragState.pointerId);
      } catch (e) {
        // 指针已释放 / 捕获不存在 —— 忽略
      }
    }
    dragState = null;
  }

  /**
   * ★ 2026-09-24 修正 2：树视口内的**自由平移**（鼠标四面八方拖动）。
   *
   * <p>只用原生 pointer 事件 + setPointerCapture；**只有在树视口（#unit-tree-mount）内 pointerdown
   * 才起平移**，绝不与标题栏 #unit-panel-drag 的面板拖动互相干扰。拖动超过 {@link PAN_DRAG_THRESHOLD}
   * 才认定为拖动并捕获指针——这样"点节点选中"与"拖动平移"共存（若在 pointerdown 就捕获，节点上的
   * click 会被吞掉、树就点不动了）。
   */
  function wireTreePan() {
    var mount = app.byId("unit-tree-mount");
    if (!mount) {
      return;
    }
    mount.addEventListener("pointerdown", function (event) {
      if (event.button !== undefined && event.button !== 0) {
        return; // 只左键
      }
      panState = {
        pointerId: event.pointerId,
        startX: event.clientX,
        startY: event.clientY,
        originX: treePan.x,
        originY: treePan.y,
        active: false,
      };
    });
    mount.addEventListener("pointermove", function (event) {
      if (!panState || event.pointerId !== panState.pointerId) {
        return;
      }
      if (event.buttons === 0) {
        // 按键已在视口外松开（pointerup 没落到 mount 上，尚未捕获）⇒ 结束，别把之后的悬停当成拖动。
        endPan();
        return;
      }
      var dx = event.clientX - panState.startX;
      var dy = event.clientY - panState.startY;
      if (!panState.active) {
        if (dx * dx + dy * dy < PAN_DRAG_THRESHOLD * PAN_DRAG_THRESHOLD) {
          return; // 还是"点击"，不进入平移
        }
        panState.active = true;
        mount.classList.add("panning");
        if (mount.setPointerCapture) {
          mount.setPointerCapture(panState.pointerId);
        }
      }
      setTreePan({ x: panState.originX + dx, y: panState.originY + dy });
    });
    mount.addEventListener("pointerleave", function () {
      if (panState && !panState.active) {
        panState = null; // 未进入拖动就离开视口 ⇒ 丢弃这次按压
      }
    });
    function endPan() {
      if (!panState) {
        return;
      }
      if (panState.active && mount.releasePointerCapture) {
        try {
          mount.releasePointerCapture(panState.pointerId);
        } catch (e) {
          // 指针已释放 / 捕获不存在 —— 忽略
        }
      }
      panState = null;
      mount.classList.remove("panning");
    }
    mount.addEventListener("pointerup", endPan);
    mount.addEventListener("pointercancel", endPan);
    // 面板宽高都是 min(..., 视口) ⇒ 窗口尺寸变了视口也变，须重夹。
    window.addEventListener("resize", reclampTreePan);
  }

  /** 绑定按钮/关闭/军队选择/复位/拖动（元素缺席 ⇒ 静默跳过：其它宿主页不挂这套 UI）。 */
  function wirePanel() {
    var openButton = app.byId("unit-panel-open");
    if (openButton) {
      openButton.addEventListener("click", function () {
        var panel = app.byId("unit-panel");
        setPanelOpen(!!(panel && panel.hidden));
      });
    }
    var closeButton = app.byId("unit-panel-close");
    if (closeButton) {
      closeButton.addEventListener("click", function () {
        setPanelOpen(false);
      });
    }
    var select = app.byId("unit-army-select");
    if (select) {
      select.addEventListener("change", function () {
        selectedRootId = select.value || null;
        render(lastUnits);
      });
    }
    var handle = app.byId("unit-panel-drag");
    var panel = app.byId("unit-panel");
    if (handle && panel) {
      handle.addEventListener("pointerdown", function (event) {
        if (event.button !== undefined && event.button !== 0) {
          return; // 只左键可拖
        }
        if (event.target && event.target.closest && event.target.closest("button")) {
          return; // 标题栏里的关闭按钮不算拖动把手
        }
        startDrag(event, panel, handle);
      });
      handle.addEventListener("pointermove", onDragMove);
      handle.addEventListener("pointerup", onDragEnd);
      handle.addEventListener("pointercancel", onDragEnd);
    }
    var resetButton = app.byId("unit-tree-reset");
    if (resetButton) {
      resetButton.addEventListener("click", resetTreePan);
    }
    wireTreePan();
  }

  function init() {
    wirePanel();
    lastTargetKey = targetKey();
    refresh();
    if (app && app.onStateChange) {
      app.onStateChange(onState);
    }
  }

  window.SimosUnitTree = {
    init: init,
    buildTree: buildTree,
    isBranchPoint: isBranchPoint,
    flatten: flatten,
    armyOptions: armyOptions,
    subtreeOf: subtreeOf,
    rootIdOf: rootIdOf,
    clampPanelPosition: clampPanelPosition,
    clampTreePan: clampTreePan,
    render: render,
    refresh: refresh,
    focusUnit: focusUnit,
    toggleBranch: toggleBranch,
    setPanelOpen: setPanelOpen,
  };
})();
