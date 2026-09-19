// unitTree.js —— 单位编制**倒树**（M7 T2 骨架 → T5 落地；spec §六 / 判据⑥）。
// ★ 无框架、无构建：组树与渲染全用原生 DOM。数据只来自 /api/units 的 parent 字段（纯前端组树，
//   零后端改动、不加第二份真相）；本文件不含任何写调用。
// ★ 方向：**倒置**——根在下、下级向上生长（spec §六 字面）。分岔点（子数 ≥ 2）加粗放大，
//   点它 ⇒ 展开/收起该分支下的详情（该节点 + 其全部后代）。
// ★ buildTree / isBranchPoint 是**纯函数**（不查 IO、不碰 DOM）⇒ 可在 Node 里用冻结夹具直接断言
//   （证据见 t5-evidence/tree-check.cjs；本项目无 JS 测试器，这是证据级检查而非 CI 护栏）。

(function () {
  "use strict";

  var app = window.SimosApp;
  var api = window.SimosApi;

  var requestToken = 0;
  var lastTargetKey = null;
  var lastUnits = [];

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

  function displayName(node) {
    return node.name === null || node.name === undefined || node.name === "" ? node.id : node.name;
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

  /** 把选中单位在树里高亮并滚入视野（地图点单位联动，判据③/⑤）。 */
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
    if (typeof target.scrollIntoView === "function") {
      target.scrollIntoView({ block: "nearest", inline: "nearest" });
    }
  }

  /** 用扁平 units 渲染整棵倒树（清空后重建）。 */
  function render(units) {
    var mount = app.byId("unit-tree-mount");
    if (!mount) {
      return;
    }
    app.clear(mount);
    var roots = buildTree(units);
    if (!roots.length) {
      mount.appendChild(app.el("p", { class: "empty", text: "暂无单位。" }));
      return;
    }
    var forest = app.el("div", { class: "tree-forest" });
    roots.forEach(function (root) {
      forest.appendChild(subtree(root));
    });
    mount.appendChild(forest);
    applyFocus();
  }

  function applyFocus() {
    var state = app && app.getState ? app.getState() : null;
    var selection = state ? state.selection : null;
    if (selection && selection.kind === "unit") {
      focusUnit(selection.id);
    }
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

  /** 状态变化：目标坐标变了 ⇒ 重取渲染；否则只把选中单位定位到树里（选择变化不重取）。 */
  function onState(state) {
    var key = targetKey();
    if (key !== lastTargetKey) {
      lastTargetKey = key;
      refresh();
      return;
    }
    if (state && state.selection && state.selection.kind === "unit") {
      focusUnit(state.selection.id);
    }
  }

  function init() {
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
    render: render,
    refresh: refresh,
    focusUnit: focusUnit,
    toggleBranch: toggleBranch,
  };
})();
