// timeline.js —— 底部线型时间轴（M7 T3，spec §五 / U1；M7b T1 列坐标 + knob + 分岔连线）。
// ★ 无框架、无构建、同源、零依赖（spec §8.1）。
// ★ 只读预览（R1）：拖动/点节点**只**改状态机的 {branch, revision}，不发任何写请求。
// ★ 写只经 window.SimosApi.advance / fork（服务端唯一入口 CoreSimos.submit）。
// ★ 2026-09-25 §十一（用户裁定，取代 2026-09-24 的"一次恰好一天"）：1 tick = 1 天，Core 的 AdvanceTime
//   **允许一次推进 N 天**（to = from + N，1 ≤ N ≤ 36500）⇒ "推进 N 天"回到**一条命令**（to = from + steps），
//   结算在各模块参与者内部**逐日**完成（前端不再逐日循环）。
// ★ 末端判定（U1）是纯函数 isAtTip(state, branchHeads)：只有游标在分支末端时才允许写。
// ★ M7b T1：节点 x 不再靠 flex 流，改为按列算：x = 左边距 + (列 − 1) × 列宽；
//   非 main 分支首个节点对齐其 parent 所在列，并画一条垂直分岔连线（.tl-fork-link，**不复用** .timeline-line）。
// ★ M7f T1：**一个节点 = 一个 tick**——同 tick 的多条 revision（命令）归并到同一节点（节点显示内联明细），
//   只有 tick 变化才长出新节点；列基准随之从 revision 改为 **tick 序号**（节点在分支内的出现顺序，1 起；
//   **不**直接用 tick 值——demo 从 5 起，直接当列号会留空列）。分岔对齐不变：非 main 分支的**首个 tick 节点**
//   与其 parent 所在 tick 节点同列。★ 铁律 2 不破：每条命令仍是真 revision，只是**视觉按 tick 归并**；
//   点一个 tick 节点 ⇒ 游标落在该 tick 的**最后一个 revision**（面板看到的是该 tick 结束时的状态）。
// ★ B7（用户裁定，2026-09-23）：节点**形态**从"胶囊标签（tick 0 · 9 条命令）"改为
//   "一条线 + 多个小点"，节点的具体信息改为**鼠标悬停（title）**时显示——视觉上不再铺命令明细。
// ★ B18：读数口径随之澄清——悬停文案写成「该 tick 下 N 条命令 / N 条 revision」，
//   **不**写成「tick N 发生了 N 次」（那会被读成"N 次决策落在 tick N"）。

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
   * tick 归并（M7f T1）：把按 revision 升序的节点按 tick 聚成一个 tick 节点。
   * 返回 `[{tick, nodes:[…], firstRevision, lastRevision, commands:[commandType…]}]`（出现顺序）。
   */
  function groupByTick(nodes) {
    var groups = [];
    var index = {};
    (nodes || []).forEach(function (node) {
      var key = String(node.tick);
      var group = index[key];
      if (!group) {
        group = {
          tick: node.tick,
          nodes: [],
          firstRevision: node.revision,
          lastRevision: node.revision,
          commands: [],
        };
        index[key] = group;
        groups.push(group);
      }
      group.nodes.push(node);
      group.lastRevision = node.revision;
      if (Number(node.revision) < Number(group.firstRevision)) {
        group.firstRevision = node.revision;
      }
      group.commands.push(node.commandType);
    });
    return groups;
  }

  /** 某分支 tick 组的下标（0 起，按出现顺序）；未知 tick ⇒ -1。 */
  function tickIndex(tickGroups, tick) {
    var list = tickGroups || [];
    for (var i = 0; i < list.length; i++) {
      if (String(list[i].tick) === String(tick)) {
        return i;
      }
    }
    return -1;
  }

  /** revision → 其所在 tick（model.tickByRevision 查表）；未知 ⇒ null。 */
  function tickOfRevision(branch, revision) {
    var map = (model.tickByRevision && model.tickByRevision[branch]) || {};
    var tick = map[Number(revision)];
    return tick === undefined ? null : tick;
  }

  /**
   * 某分支某 tick 落在第几列（1 起）。main = tick 组在分支内的序号；非 main 分支的**首个 tick 节点**落在其
   * parent 节点所在 tick 列的同一列，之后逐 tick +1。fork 自 fork 的分支按链递归（带 visited 防环）。
   * ★ 列基准是 **tick 序号**（出现顺序），不是 tick 值——demo 从 5 起，用 tick 值会留 4 个空列。
   */
  function columnOfTick(branch, tick, visited) {
    var groups = model.tickGroups[branch] || [];
    var index = tickIndex(groups, tick);
    if (index < 0) {
      return 1;
    }
    if (branch === "main") {
      return index + 1;
    }
    var seen = visited || {};
    if (seen[branch]) {
      return index + 1;
    }
    seen[branch] = true;
    var first = groups.length > 0 ? groups[0] : null;
    var parentRef = first && first.nodes.length > 0 ? first.nodes[0].parent : null;
    if (!parentRef || parentRef.branch === undefined || parentRef.branch === null) {
      return index + 1;
    }
    var parentTick = tickOfRevision(parentRef.branch, Number(parentRef.revision));
    if (parentTick === null) {
      return index + 1;
    }
    return columnOfTick(parentRef.branch, parentTick, seen) + index;
  }

  /** 兼容旧名：revision → 其 tick 所在列。 */
  function columnOf(branch, revision) {
    var tick = tickOfRevision(branch, revision);
    return tick === null ? 1 : columnOfTick(branch, tick);
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
    tickGroups: {},
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

  /** 重取 /api/state + 每分支 /api/timeline，重建节点模型，再重画。force=true 强制作废 state 缓存（写后）。 */
  async function refresh(force) {
    model.loading = true;
    try {
      var body = await app.refreshState(force);
      var branches = body.branches || [];
      var heads = body.heads || {};
      var nodes = {};
      var tickGroups = {};
      var tickAtHead = {};
      var tickByRevision = {};
      for (var i = 0; i < branches.length; i++) {
        var branch = branches[i];
        var timeline = await window.SimosApi.timeline(branch);
        nodes[branch] = timeline.nodes || [];
        tickGroups[branch] = groupByTick(nodes[branch]);
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
      model.tickGroups = tickGroups;
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
        refresh(force);
      }
    }
  }

  // ── 渲染 ──────────────────────────────────────────────────────────

  /**
   * 一个 tick 节点的 DOM（B7 新形态）：线上一个**小点**（.tl-node 本体即圆点），点下方一行极小的 tick 刻度数字，
   * 节点的具体信息（命令名 / rev / initiator 逐条）放 **title**——鼠标悬停即显示（不再内联铺明细）。
   * ★ 一个节点 = 一个 tick：同 tick 的多条命令已在 groupByTick 里归并 ⇒ 这里只画**一个点**，只有 tick 变化才长新点。
   * ★ B18 读数口径：title 与 aria-label 都写成「该 tick 下 N 条命令 / N 条 revision」，避免被读成"N 次决策落在该 tick"。
   * `data-tick` / `data-count` / `data-commands` / `data-first-revision` 供 e2e 断言。
   * ★ `data-revision` = 该 tick 的**最后一个 revision**（游标语义：点它 = 看到该 tick 结束时的状态）。
   */
  function tickNode(branch, group) {
    var count = group.nodes.length;
    // 每条命令各产出一个 revision ⇒ 两者同数；**显式成对给出**是为了让读数无歧义（B18）。
    var revisions = count;
    var readout = "该 tick 下 " + count + " 条命令 / " + revisions + " 条 revision";
    var lines = group.nodes.map(function (node) {
      return "· " + shortCommandType(node.commandType) + " · rev " + node.revision + " · " + app.text(node.initiator);
    });
    var button = app.el("button", {
      type: "button",
      class: "tl-node",
      "data-branch": branch,
      "data-tick": group.tick,
      "data-revision": group.lastRevision,
      "data-first-revision": group.firstRevision,
      "data-count": count,
      "data-commands": group.commands.join(","),
      "aria-label": "tick " + group.tick + " · " + readout,
      title: "tick " + group.tick + " · " + readout + "\n" + lines.join("\n"),
    });
    // 小点本体：点下方的极简刻度数字（只是刻度，不是被退场的"胶囊"明细）。
    button.appendChild(app.el("span", { class: "tl-tick", text: String(group.tick) }));
    button.style.left = columnX(columnOfTick(branch, group.tick)) + "px";
    return button;
  }

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
      (model.tickGroups[branch] || []).forEach(function (group) {
        maxColumn = Math.max(maxColumn, columnOfTick(branch, group.tick));
      });
    });
    var trackWidth = LABEL_WIDTH + maxColumn * COL_WIDTH + 20;
    branches.forEach(function (branch) {
      var line = app.el("div", { class: "timeline-line", "data-branch": branch });
      line.style.width = trackWidth + "px";
      line.appendChild(app.el("span", { class: "tl-branch-label", text: branch }));
      (model.tickGroups[branch] || []).forEach(function (group) {
        line.appendChild(tickNode(branch, group));
      });
      mount.appendChild(line);
    });
    mount.appendChild(app.el("div", { class: "tl-knob", hidden: "", "aria-hidden": "true" }));
    renderForkLinks();
  }

  /** 分岔连线：从 parent 所在 tick 节点垂直指向该分支首个 tick 节点（两者同列，故是竖线）。用 .tl-fork-link，绝不复用 .timeline-line。 */
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
      var groups = model.tickGroups[branch] || [];
      if (groups.length === 0) {
        return;
      }
      var first = groups[0].nodes.length > 0 ? groups[0].nodes[0] : null;
      if (!first || !first.parent) {
        return;
      }
      var parentRevision = Number(first.parent.revision);
      var parentLine = mount.querySelector('.timeline-line[data-branch="' + first.parent.branch + '"]');
      var parent = null;
      if (parentLine) {
        Array.prototype.forEach.call(parentLine.querySelectorAll(".tl-node"), function (candidate) {
          if (
            parent === null &&
            parentRevision >= Number(candidate.getAttribute("data-first-revision")) &&
            parentRevision <= Number(candidate.getAttribute("data-revision"))
          ) {
            parent = candidate;
          }
        });
      }
      var child = mount.querySelector('.timeline-line[data-branch="' + branch + '"] .tl-node');
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
        var first = Number(node.getAttribute("data-first-revision"));
        var last = Number(node.getAttribute("data-revision"));
        var revision = Number(state.revision);
        var active =
          node.getAttribute("data-branch") === state.branch &&
          revision >= first &&
          revision <= last;
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
          event.target.closest &&
          (event.target.closest(".tl-node") ||
            event.target.closest(".tl-knob") ||
            event.target.closest(".timeline-line"));
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

  /** "推进 N 天"输入框的原始文本（trim 后）。 */
  function advanceInputValue() {
    var input = app.byId("timeline-advance-n");
    return input ? String(input.value).trim() : "";
  }

  /** 解析"推进 N 天"：合法（≥1 的整数）⇒ N；否则 null（空 / 非数字 / 小数 / 0 / 负）。 */
  function readAdvanceSteps() {
    var raw = advanceInputValue();
    var n = Number(raw);
    if (raw === "" || !isFinite(n) || Math.floor(n) !== n || n < 1) {
      return null;
    }
    return n;
  }

  /** 推进失败的原因文本（人话）：优先服务端 reason，其次 409「末端已移动」，再次 error.message。 */
  function advanceErrorReason(error) {
    if (!error) {
      return "未知原因";
    }
    var body = error.body;
    if (body && body.reason) {
      return body.reason;
    }
    if (error.status === 409) {
      return "末端已移动";
    }
    return error.message || String(error);
  }

  /**
   * 推进 {@code days} 天（★ 2026-09-25 §十一：Core 允许 **一次 N 天** ⇒ 这里只发**一条**命令，
   * {@code to = fromDay + days}；服务端在各模块内部逐日结算，落一条 revision）。
   *
   * <p>★ 请求是**原子**的：成功 ⇒ {@code advancedDays = days}；失败（被拒/冲突）⇒ 没有"推进了一半"这回事， {@code
   * advancedDays = 0}。`advance` 缺省取 {@code window.SimosApi.advance}（页面路径），测试可注入假实现。
   *
   * <p>返回 `{ok, advancedDays, stoppedAtDay, error}`（形态与 1a 逐日版一致，故 {@link #advanceStatusText} 文案不变）。
   */
  async function advanceByDays(opts) {
    var advance = opts.advance || (window.SimosApi && window.SimosApi.advance);
    if (typeof advance !== "function") {
      return {
        ok: false,
        advancedDays: 0,
        stoppedAtDay: 1,
        error: new Error("本页没有 advance 端点"),
      };
    }
    var body;
    try {
      body = await advance(opts.branch, opts.expectedRevision, opts.fromDay, opts.fromDay + opts.days);
    } catch (error) {
      return { ok: false, advancedDays: 0, stoppedAtDay: 1, error: error };
    }
    var ref = body && body.ref;
    if (!ref || ref.revision === null || ref.revision === undefined) {
      // 取不到新 revision ⇒ 不编造。
      return {
        ok: false,
        advancedDays: 0,
        stoppedAtDay: 1,
        error: new Error("推进返回里没有新 revision"),
      };
    }
    return { ok: true, advancedDays: opts.days, stoppedAtDay: null, error: null };
  }

  /** 推进结局 → 状态栏文案（纯函数，可单测）：成功「已推进 N 天（X → X+N）」；失败「已推进 i 天、在第 i+1 天停下（原因）」。 */
  function advanceStatusText(outcome, fromDay, days) {
    if (outcome.ok) {
      return "已推进 " + days + " 天（" + fromDay + " → " + (fromDay + days) + "）";
    }
    return (
      "已推进 " +
      outcome.advancedDays +
      " 天、在第 " +
      outcome.stoppedAtDay +
      " 天停下（" +
      advanceErrorReason(outcome.error) +
      "）"
    );
  }

  /**
   * 推进 N 天：**一条命令**——POST /api/advance 一次，{@code to = from + N}（§十一：Core 允许一次 N 天，服务端内部逐日结算）。
   * 被拒/冲突 ⇒ 请求是原子的（没有"推进了一半"），状态栏如实报"已推进 0 天"并刷新。
   * ★ 非法 N：明确提示且**不发写**（U1 的末端检查同样先跑）。
   */
  async function onCreate() {
    var state = app.getState();
    var heads = currentHeads(state);
    if (model.busy || !isAtTip(state, heads)) {
      return;
    }
    var days = readAdvanceSteps();
    if (days === null) {
      showStatus("推进天数必须是 ≥1 的整数（当前：" + (advanceInputValue() || "空") + "）", "err");
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
      var outcome = await advanceByDays({
        branch: state.branch,
        expectedRevision: head,
        fromDay: tick,
        days: days,
      });
      showStatus(advanceStatusText(outcome, tick, days), outcome.ok ? "ok" : "err");
      await refresh(true);
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
      await refresh(true);
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
    columnOfTick: columnOfTick,
    nodeX: nodeX,
    groupByTick: groupByTick,
    tickIndex: tickIndex,
    tickOfRevision: tickOfRevision,
    readAdvanceSteps: readAdvanceSteps,
    advanceByDays: advanceByDays,
    advanceStatusText: advanceStatusText,
    orderedBranches: orderedBranches,
    COL_WIDTH: COL_WIDTH,
    LABEL_WIDTH: LABEL_WIDTH,
    // ★ T2 可测性宿主（无行为影响，页面路径不调用）：给列布局/查表注入模型快照，
    //   使 columnOfTick / tickOfRevision 这两个读 model 的纯函数能在门禁内直接断言。
    __setModelForTest: function (tickGroups, tickByRevision) {
      model.tickGroups = tickGroups || {};
      model.tickByRevision = tickByRevision || {};
    },
  };
})();
