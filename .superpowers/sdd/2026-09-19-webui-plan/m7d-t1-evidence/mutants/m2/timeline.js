// timeline.js —— 底部线型时间轴（M7 T3，spec §五 / U1；M7b T1 列坐标 + knob + 分岔连线）。
// ★ 无框架、无构建、同源、零依赖（spec §8.1）。
// ★ 只读预览（R1）：拖动/点节点**只**改状态机的 {branch, revision}，不发任何写请求。
// ★ 写只经 window.SimosApi.advance / fork（服务端唯一入口 CoreSimos.submit）。
// ★ 末端判定（U1）是纯函数 isAtTip(state, branchHeads)：只有游标在分支末端时才允许写。
// ★ M7b T1：节点 x 不再靠 flex 流，改为按 revision 算列：x = 左边距 + (列 − 1) × 列宽；
//   非 main 分支 rev1 对齐其 parent 所在列，并画一条垂直分岔连线（.tl-fork-link，**不复用** .timeline-line）。

(function () {
  "use strict";

  var app = window.SimosApp;

  // ── 布局常量（可断言）──────────────────────────────────────────────
  // ★ 列宽固定，e2e 直接读 window.SimosTimeline.COL_WIDTH 做等距断言；label 区作左内边距。

  var COL_WIDTH = 110;
  var LABEL_WIDTH = 72;

  // ── 纯函数（可单测）────────────────────────────────────────────────

  /**
   * 末端判定（U1）：游标是否恰在分支末端。branchHeads = {分支名: head revision}。
   * 分支不存在 / revision 未定 ⇒ false（宁可不写，不误写）。
   */
  function isAtTip(state, branchHeads) {
    if (!state || !branchHeads) {
      return false;
    }
    var head = branchHeads[state.branch];
    if (head === null || head === undefined) {
      return false;
    }
    return Number(state.revision) === Number(head);
  }

  /** 命令短名：取最后一段（"unit.RenameUnit" → "RenameUnit"）。 */
  function shortCommandType(commandType) {
    if (!commandType) {
      return "?";
    }
    var parts = String(commandType).split(".");
    return parts[parts.length - 1];
  }

  /** 自动分支名：在已有名之上取最小的空位 b2/b3…（避开 main 与既有分支）。 */
  function nextBranchName(branches) {
    var used = {};
    (branches || []).forEach(function (name) {
      used[name] = true;
    });
    var n = 2;
    while (used["b" + n]) {
      n++;
    }
    return "b" + n;
  }

  /** 分支表是否等价（键集 + head 值）；轮询每 5s 都来，等价就不必重取节点。 */
  function sameHeads(a, b) {
    var left = a || {};
    var right = b || {};
    var leftKeys = Object.keys(left);
    var rightKeys = Object.keys(right);
    if (leftKeys.length !== rightKeys.length) {
      return false;
    }
    for (var i = 0; i < leftKeys.length; i++) {
      var key = leftKeys[i];
      if (Number(left[key]) !== Number(right[key])) {
        return false;
      }
    }
    return true;
  }

  /** 列 → x（左边距 + (列 − 1) × 列宽）。列从 1 起。 */
  function columnX(column) {
    return LABEL_WIDTH + (Number(column) - 1) * COL_WIDTH;
  }

  /**
   * 某分支某 revision 落在第几列。main = 自身 revision；非 main 分支的 rev1 落在其 parent 节点所在列，
   * 之后逐 revision +1。fork 自 fork 的分支按链递归（带 visited 防环）。
   */
  function columnOf(branch, revision, visited) {
    var rev = Number(revision);
    if (branch === "main") {
      return rev;
    }
    var seen = visited || {};
    if (seen[branch]) {
      return rev;
    }
    seen[branch] = true;
    var list = model.nodes[branch] || [];
    var first = list.length > 0 ? list[0] : null;
    if (!first || !first.parent || first.parent.branch === undefined || first.parent.branch === null) {
      return rev;
    }
    return (
      columnOf(first.parent.branch, Number(first.parent.revision), seen) + (rev - 1)
    );
  }

  /** 节点 x：columnOf(branch, revision) → columnX。 */
  function nodeX(branch, revision) {
    return columnX(columnOf(branch, revision));
  }

  /** 行顺序：main 固定第一条；其余按字典序（后端 /api/state 的 branches 即字典序，前端保持确定性）。 */
  function orderedBranches(branches) {
    var all = branches || [];
    var rest = all
      .filter(function (name) {
        return name !== "main";
      })
      .slice()
      .sort();
    return (all.indexOf("main") >= 0 ? ["main"] : []).concat(rest);
  }

  // ── 模型（服务器视图的本地副本）────────────────────────────────────

  var model = {
    branches: [],
    heads: {},
    nodes: {},
    tickAtHead: {},
    tickByRevision: {},
    loading: false,
    busy: false,
    error: null,
  };

  /** 刷新在途时又来了新的 head ⇒ 记下，等本轮 refresh 结束后**补一轮**（否则停在陈旧模型上直至下次轮询）。 */
  var refreshQueued = false;

  /** 权威分支表：优先状态机（`refreshState` 同步写入，写后立即正确），退化到模型。 */
  function currentHeads(state) {
    var s = state || app.getState();
    if (s && s.heads && Object.keys(s.heads).length > 0) {
      return s.heads;
    }
    return model.heads;
  }

  // ── 取数 ──────────────────────────────────────────────────────────

  /** 重取 /api/state + 每分支 /api/timeline，重建节点模型，再重画。 */
  async function refresh() {
    model.loading = true;
    try {
      var body = await app.refreshState();
      var branches = body.branches || [];
      var heads = body.heads || {};
      var nodes = {};
      var tickAtHead = {};
      var tickByRevision = {};
      for (var i = 0; i < branches.length; i++) {
        var branch = branches[i];
        var timeline = await window.SimosApi.timeline(branch);
        nodes[branch] = timeline.nodes || [];
        tickByRevision[branch] = {};
        var head = Number(heads[branch]);
        for (var j = 0; j < nodes[branch].length; j++) {
          var revision = Number(nodes[branch][j].revision);
          tickByRevision[branch][revision] = nodes[branch][j].tick;
          if (revision === head) {
            tickAtHead[branch] = nodes[branch][j].tick;
          }
        }
      }
      model.branches = branches;
      model.heads = heads;
      model.nodes = nodes;
      model.tickAtHead = tickAtHead;
      model.tickByRevision = tickByRevision;
      model.error = null;
      renderTrack();
    } catch (e) {
      model.error = e.message;
      renderTrack();
    } finally {
      model.loading = false;
      renderCursor(app.getState());
      if (refreshQueued) {
        refreshQueued = false;
        refresh();
      }
    }
  }

  // ── 渲染 ──────────────────────────────────────────────────────────

  function renderTrack() {
    var mount = app.byId("timeline-mount");
    if (!mount) {
      return;
    }
    app.clear(mount);
    if (model.error) {
      mount.appendChild(app.el("span", { class: "empty", text: "时间轴不可用：" + model.error }));
      return;
    }
    if (model.branches.length === 0) {
      mount.appendChild(app.el("span", { class: "empty", text: "无分支" }));
      return;
    }
    var branches = orderedBranches(model.branches);
    var maxColumn = 1;
    branches.forEach(function (branch) {
      (model.nodes[branch] || []).forEach(function (node) {
        maxColumn = Math.max(maxColumn, columnOf(branch, node.revision));
      });
    });
    var trackWidth = LABEL_WIDTH + maxColumn * COL_WIDTH + 20;
    branches.forEach(function (branch) {
      var line = app.el("div", { class: "timeline-line", "data-branch": branch });
      line.style.width = trackWidth + "px";
      line.appendChild(app.el("span", { class: "tl-branch-label", text: branch }));
      var nodes = model.nodes[branch] || [];
      nodes.forEach(function (node) {
        var button = app.el("button", {
          type: "button",
          class: "tl-node",
          "data-branch": branch,
          "data-revision": node.revision,
          title: node.commandType + " · " + app.text(node.initiator),
          text: "rev " + node.revision + " · " + shortCommandType(node.commandType),
        });
        button.style.left = columnX(columnOf(branch, node.revision)) + "px";
        line.appendChild(button);
      });
      mount.appendChild(line);
    });
    mount.appendChild(app.el("div", { class: "tl-knob", hidden: "", "aria-hidden": "true" }));
    renderForkLinks();
  }

  /** 分岔连线：从 parent 节点垂直指向该分支 rev1（两者同列，故是竖线）。用 .tl-fork-link，绝不复用 .timeline-line。 */
  function renderForkLinks() {
    var mount = app.byId("timeline-mount");
    if (!mount) {
      return;
    }
    Array.prototype.forEach.call(mount.querySelectorAll(".tl-fork-link"), function (node) {
      node.remove();
    });
    orderedBranches(model.branches).forEach(function (branch) {
      if (branch === "main") {
        return;
      }
      var list = model.nodes[branch] || [];
      if (list.length === 0) {
        return;
      }
      var first = list[0];
      if (!first || !first.parent) {
        return;
      }
      var parent = mount.querySelector(
        '.timeline-line[data-branch="' +
          first.parent.branch +
          '"] .tl-node[data-revision="' +
          first.parent.revision +
          '"]'
      );
      var child = mount.querySelector(
        '.timeline-line[data-branch="' + branch + '"] .tl-node[data-revision="' + first.revision + '"]'
      );
      if (!parent || !child) {
        return;
      }
      var origin = mountOrigin(mount);
      var parentRect = parent.getBoundingClientRect();
      var childRect = child.getBoundingClientRect();
      var parentX = parentRect.left + parentRect.width / 2 - origin.left + origin.scrollLeft;
      var childX = childRect.left + childRect.width / 2 - origin.left + origin.scrollLeft;
      var parentY = parentRect.top + parentRect.height / 2 - origin.top + origin.scrollTop;
      var childY = childRect.top + childRect.height / 2 - origin.top + origin.scrollTop;
      var link = app.el("div", { class: "tl-fork-link" });
      link.style.left = Math.min(parentX, childX) + "px";
      link.style.top = Math.min(parentY, childY) + "px";
      link.style.height = Math.abs(childY - parentY) + "px";
      mount.appendChild(link);
    });
  }

  /** mount 的内容原点（padding box + 滚动量）：绝对定位子元素的 left/top 就是相对它。 */
  function mountOrigin(mount) {
    var rect = mount.getBoundingClientRect();
    var style = window.getComputedStyle(mount);
    return {
      left: rect.left + (parseFloat(style.borderLeftWidth) || 0),
      top: rect.top + (parseFloat(style.borderTopWidth) || 0),
      scrollLeft: mount.scrollLeft,
      scrollTop: mount.scrollTop,
    };
  }

  /** 只更新"随游标/末端变化"的部分：节点选中态、knob 位置与显隐、两个写按钮的启用态、底栏元信息。 */
  function renderCursor(state) {
    var mount = app.byId("timeline-mount");
    if (mount) {
      Array.prototype.forEach.call(mount.querySelectorAll(".tl-node"), function (node) {
        var active =
          node.getAttribute("data-branch") === state.branch &&
          Number(node.getAttribute("data-revision")) === Number(state.revision);
        node.classList.toggle("active", active);
        node.setAttribute("aria-current", active ? "true" : "false");
      });
      var knob = mount.querySelector(".tl-knob");
      if (knob) {
        var activeNode = mount.querySelector(".tl-node.active");
        if (activeNode) {
          var origin = mountOrigin(mount);
          var rect = activeNode.getBoundingClientRect();
          knob.hidden = false;
          knob.style.left = rect.left + rect.width / 2 - origin.left + origin.scrollLeft + "px";
          knob.style.top = rect.top + rect.height / 2 - origin.top + origin.scrollTop + "px";
          knob.setAttribute("data-branch", activeNode.getAttribute("data-branch"));
          knob.setAttribute("data-revision", activeNode.getAttribute("data-revision"));
        } else {
          knob.hidden = true;
          knob.removeAttribute("data-branch");
          knob.removeAttribute("data-revision");
        }
      }
    }
    var atTip = isAtTip(state, currentHeads(state));
    var create = app.byId("timeline-create");
    var fork = app.byId("timeline-fork");
    if (create) {
      create.disabled = !atTip || model.busy;
    }
    if (fork) {
      fork.disabled = !atTip || model.busy;
    }
    var meta = app.byId("timeline-meta");
    if (meta) {
      var head = currentHeads(state)[state.branch];
      var tickMap = model.tickByRevision[state.branch] || {};
      var tick = tickMap[state.revision];
      if (tick === null || tick === undefined) {
        tick = model.tickAtHead[state.branch];
      }
      meta.textContent =
        "分支 " +
        app.text(state.branch) +
        " · rev " +
        app.text(state.revision) +
        " · head " +
        app.text(head) +
        (tick === null || tick === undefined ? "" : " · tick " + tick);
    }
  }

  function showStatus(message, tone) {
    var node = app.byId("timeline-status");
    if (!node) {
      return;
    }
    node.className = "tl-status " + (tone || "muted");
    node.textContent = message;
  }

  // ── 预览（★ 不写盘，R1）────────────────────────────────────────────

  /** 移动游标：只改状态机，绝不发写请求。 */
  function moveCursor(branch, revision) {
    var state = app.getState();
    if (state.branch !== branch) {
      app.setBranch(branch);
    }
    if (Number(state.revision) !== Number(revision)) {
      app.setRevision(Number(revision));
    }
  }

  function nodeIndexAt(line, clientX) {
    var nodes = line.querySelectorAll(".tl-node");
    var best = -1;
    var bestDistance = Infinity;
    for (var i = 0; i < nodes.length; i++) {
      var rect = nodes[i].getBoundingClientRect();
      var distance = Math.abs(rect.left + rect.width / 2 - clientX);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = i;
      }
    }
    return best;
  }

  function scrubTo(line, clientX) {
    var index = nodeIndexAt(line, clientX);
    if (index < 0) {
      return;
    }
    var node = line.querySelectorAll(".tl-node")[index];
    moveCursor(line.getAttribute("data-branch"), Number(node.getAttribute("data-revision")));
  }

  /** 距指针纵向最近的行（不要求指针仍在行内）：纵向偏离行也继续吸附，配合 setPointerCapture。 */
  function lineNearest(clientY) {
    var mount = app.byId("timeline-mount");
    if (!mount) {
      return null;
    }
    var lines = mount.querySelectorAll(".timeline-line");
    var best = null;
    var bestDistance = Infinity;
    for (var i = 0; i < lines.length; i++) {
      var rect = lines[i].getBoundingClientRect();
      var centerY = rect.top + rect.height / 2;
      var distance = Math.abs(centerY - clientY);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = lines[i];
      }
    }
    return best;
  }

  function dragTo(clientX, clientY) {
    var line = lineNearest(clientY);
    if (line) {
      scrubTo(line, clientX);
    }
  }

  function wireEvents() {
    var mount = app.byId("timeline-mount");
    if (mount) {
      mount.addEventListener("click", function (event) {
        var node = event.target.closest ? event.target.closest(".tl-node") : null;
        if (node) {
          moveCursor(node.getAttribute("data-branch"), Number(node.getAttribute("data-revision")));
        }
      });
      var dragPointer = null;
      mount.addEventListener("pointerdown", function (event) {
        if (event.button !== 0) {
          return;
        }
        var handle =
          event.target.closest && event.target.closest(".timeline-line");
        if (!handle) {
          return;
        }
        dragPointer = event.pointerId;
        try {
          mount.setPointerCapture(event.pointerId);
        } catch (captureError) {
          // 捕获失败也不致命：退化回普通拖动（仍会走下面同一套 dragTo）。
        }
        dragTo(event.clientX, event.clientY);
        event.preventDefault();
      });
      mount.addEventListener("pointermove", function (event) {
        if (dragPointer === null || event.pointerId !== dragPointer) {
          return;
        }
        dragTo(event.clientX, event.clientY);
      });
      var endDrag = function (event) {
        if (dragPointer === null) {
          return;
        }
        if (event && event.pointerId !== undefined && event.pointerId !== dragPointer) {
          return;
        }
        try {
          mount.releasePointerCapture(dragPointer);
        } catch (releaseError) {
          // 已释放或从未捕获：忽略。
        }
        dragPointer = null;
      };
      mount.addEventListener("pointerup", endDrag);
      mount.addEventListener("pointercancel", endDrag);
    }
    var create = app.byId("timeline-create");
    if (create) {
      create.addEventListener("click", onCreate);
    }
    var fork = app.byId("timeline-fork");
    if (fork) {
      fork.addEventListener("click", onFork);
    }
  }

  // ── 写（只在末端；U1）──────────────────────────────────────────────

  function setBusy(busy) {
    model.busy = busy;
    renderCursor(app.getState());
  }

  /** 冲突/拒绝的处理：**不静默重试**——409 提示"末端已移动"并自动重取最新状态。 */
  function handleWriteError(error) {
    if (error && error.status === 409) {
      showStatus("末端已移动，已自动重取最新状态", "warn");
      refresh().catch(function () {});
    } else if (error && error.status === 422) {
      showStatus("被拒：" + (error.message || "非法请求"), "err");
    } else {
      showStatus("写失败：" + ((error && error.message) || error), "err");
    }
  }

  /** 创建节点 = 推进一格时间（POST /api/advance；from=head 的 tick，to=tick+1）。 */
  async function onCreate() {
    var state = app.getState();
    var heads = currentHeads(state);
    if (model.busy || !isAtTip(state, heads)) {
      return;
    }
    var head = heads[state.branch];
    var tick = model.tickAtHead[state.branch];
    if (tick === null || tick === undefined) {
      showStatus("找不到末端 tick，无法推进", "err");
      return;
    }
    setBusy(true);
    try {
      await window.SimosApi.advance(state.branch, head, tick, tick + 1);
      showStatus("已创建节点：推进 tick " + tick + " → " + (tick + 1), "ok");
      await refresh();
      var newHead = model.heads[app.getState().branch];
      if (newHead !== null && newHead !== undefined) {
        app.setRevision(newHead);
      }
    } catch (error) {
      handleWriteError(error);
    } finally {
      setBusy(false);
    }
  }

  /** 分岔 = POST /api/fork（source=当前分支，expectedRevision=head，newBranch=自动名）。 */
  async function onFork() {
    var state = app.getState();
    var heads = currentHeads(state);
    if (model.busy || !isAtTip(state, heads)) {
      return;
    }
    var head = heads[state.branch];
    var newBranch = nextBranchName(model.branches);
    setBusy(true);
    try {
      await window.SimosApi.fork(state.branch, head, newBranch);
      showStatus("已分岔：新分支 " + newBranch, "ok");
      await refresh();
      app.setBranch(newBranch);
      var newHead = model.heads[newBranch];
      if (newHead !== null && newHead !== undefined) {
        app.setRevision(newHead);
      }
    } catch (error) {
      handleWriteError(error);
    } finally {
      setBusy(false);
    }
  }

  // ── 订阅 ──────────────────────────────────────────────────────────

  function onStateChanged(state) {
    var heads = currentHeads(state);
    if (model.loading) {
      if (!sameHeads(heads, model.heads)) {
        refreshQueued = true;
      }
      renderCursor(state);
      return;
    }
    if (!sameHeads(heads, model.heads)) {
      refresh();
      return;
    }
    renderCursor(state);
  }

  function init() {
    var mount = app.byId("timeline-mount");
    if (!mount) {
      return;
    }
    app.clear(mount);
    mount.appendChild(app.el("span", { class: "empty", text: "时间轴加载中…" }));
    wireEvents();
    app.onStateChange(onStateChanged);
    window.addEventListener("resize", function () {
      renderForkLinks();
      renderCursor(app.getState());
    });
    refresh();
  }

  window.SimosTimeline = {
    init: init,
    refresh: refresh,
    isAtTip: isAtTip,
    shortCommandType: shortCommandType,
    nextBranchName: nextBranchName,
    columnX: columnX,
    columnOf: columnOf,
    nodeX: nodeX,
    orderedBranches: orderedBranches,
    COL_WIDTH: COL_WIDTH,
    LABEL_WIDTH: LABEL_WIDTH,
  };
})();
