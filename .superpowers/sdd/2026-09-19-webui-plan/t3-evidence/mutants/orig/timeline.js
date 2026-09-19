// timeline.js —— 底部线型时间轴（M7 T3，spec §五 / U1）。
// ★ 无框架、无构建、同源、零依赖（spec §8.1）。
// ★ 只读预览（R1）：拖动/点节点**只**改状态机的 {branch, revision}，不发任何写请求。
// ★ 写只经 window.SimosApi.advance / fork（服务端唯一入口 CoreSimos.submit）。
// ★ 末端判定（U1）是纯函数 isAtTip(state, branchHeads)：只有游标在分支末端时才允许写。

(function () {
  "use strict";

  var app = window.SimosApp;

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

  // ── 模型（服务器视图的本地副本）────────────────────────────────────

  var model = {
    branches: [],
    heads: {},
    nodes: {},
    tickAtHead: {},
    loading: false,
    busy: false,
    error: null,
  };

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
      for (var i = 0; i < branches.length; i++) {
        var branch = branches[i];
        var timeline = await window.SimosApi.timeline(branch);
        nodes[branch] = timeline.nodes || [];
        var head = Number(heads[branch]);
        for (var j = 0; j < nodes[branch].length; j++) {
          if (Number(nodes[branch][j].revision) === head) {
            tickAtHead[branch] = nodes[branch][j].tick;
          }
        }
      }
      model.branches = branches;
      model.heads = heads;
      model.nodes = nodes;
      model.tickAtHead = tickAtHead;
      model.error = null;
      renderTrack();
    } catch (e) {
      model.error = e.message;
      renderTrack();
    } finally {
      model.loading = false;
      renderCursor(app.getState());
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
    model.branches.forEach(function (branch) {
      var line = app.el("div", { class: "timeline-line", "data-branch": branch });
      line.appendChild(app.el("span", { class: "tl-branch-label", text: branch }));
      var nodes = model.nodes[branch] || [];
      nodes.forEach(function (node) {
        line.appendChild(
          app.el("button", {
            type: "button",
            class: "tl-node",
            "data-branch": branch,
            "data-revision": node.revision,
            title: node.commandType + " · " + app.text(node.initiator),
            text: "rev " + node.revision + " · " + shortCommandType(node.commandType),
          })
        );
      });
      mount.appendChild(line);
    });
  }

  /** 只更新"随游标/末端变化"的部分：节点选中态、两个写按钮的启用态、底栏元信息。 */
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
    }
    var atTip = isAtTip(state, model.heads);
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
      var head = model.heads[state.branch];
      var tick = model.tickAtHead[state.branch];
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

  function wireEvents() {
    var mount = app.byId("timeline-mount");
    if (mount) {
      mount.addEventListener("click", function (event) {
        var node = event.target.closest ? event.target.closest(".tl-node") : null;
        if (node) {
          moveCursor(node.getAttribute("data-branch"), Number(node.getAttribute("data-revision")));
        }
      });
      var dragging = false;
      mount.addEventListener("pointerdown", function (event) {
        var line = event.target.closest ? event.target.closest(".timeline-line") : null;
        if (!line) {
          return;
        }
        dragging = true;
        scrubTo(line, event.clientX);
      });
      mount.addEventListener("pointermove", function (event) {
        if (!dragging) {
          return;
        }
        var line = event.target.closest ? event.target.closest(".timeline-line") : null;
        if (line) {
          scrubTo(line, event.clientX);
        }
      });
      var endDrag = function () {
        dragging = false;
      };
      mount.addEventListener("pointerup", endDrag);
      mount.addEventListener("pointercancel", endDrag);
      mount.addEventListener("pointerleave", endDrag);
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
    if (model.busy || !isAtTip(state, model.heads)) {
      return;
    }
    var head = model.heads[state.branch];
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
    if (model.busy || !isAtTip(state, model.heads)) {
      return;
    }
    var head = model.heads[state.branch];
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
    if (model.loading) {
      renderCursor(state);
      return;
    }
    if (!sameHeads(state.heads, model.heads)) {
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
    refresh();
  }

  window.SimosTimeline = {
    init: init,
    refresh: refresh,
    isAtTip: isAtTip,
    shortCommandType: shortCommandType,
    nextBranchName: nextBranchName,
  };
})();
