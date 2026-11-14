// app.js —— 单页工作台的状态机 + 三页共享的导航、格式化与小工具（M5 T9；M7 T2 改造）。
// ★ 无框架、无构建：只用原生 DOM API，保持同源、零依赖（spec §8.1）。
// ★ M7 T2：本文件持有唯一状态 {mode, branch, revision, selection}；模式切换只改可见性/可用性，
//   不改数据来源。其它文件（panels/timeline/unitTree/map）经 window.SimosApp 读取与订阅。

(function () {
  "use strict";

  var PAGES = [
    { href: "/", label: "首页" },
    { href: "/map", label: "地图" },
    { href: "/unit", label: "单位" },
    { href: "/social", label: "社会" },
  ];

  // ── 状态机（M7 T2）──────────────────────────────────────────────────

  /** 唯一可变状态；模式只影响可见性/可用性（spec §四）。branches/heads 来自 /api/state（M7 T3）。 */
  var state = {
    mode: "view",
    branch: "main",
    revision: null,
    // D-036：同一 tick 原地更新时 revision 不变；写代次让 target/缓存失效。
    writeEpoch: 0,
    // D-036：当前状态游标的 tick（文档地址 treeXXX-tickXXX 的 tick 部分）。
    tick: null,
    selection: null,
    highlightRegions: [],
    // ★ U3：高亮语义 —— "group"（tag 全选 / 多从属格，全部等亮）| "single"（单个区域，更亮 + 只压同 tag 兄弟）。
    highlightKind: "group",
    // ★ M8 T10：区域编辑的**当前目标区域**（focus）。null = 未选；编辑中它正常色、其它区域淡色。
    //   与 highlightRegions 分开：那个是"区域查看"的临时高亮，这个是编辑会话的目标（可删除/改 hex）。
    regionFocus: null,
    // ★ 2026-10-02 用户报障：区域查看/编辑时，地图点格应只在该 tag 覆盖的区域里取"最后覆盖"。
    //   null = 未选 tag（无筛选，沿用 V3 全图最顶层）。
    regionTag: null,
    // ★ T7：决策模式的**子页**（`view`=决策人查看 / `approval`=审批）与**列表聚焦的决策人**。
    //   focus 只由右栏列表点击设置；地图点选（setSelection）会清掉它 ⇒ 两种来源互斥、左栏只显示一个。
    decisionSubpage: "view",
    decisionMakerFocus: null,
    branches: [],
    heads: {},
  };

  var listeners = [];

  function getState() {
    return state;
  }

  function setMode(mode) {
    if (!mode || mode === state.mode) {
      return;
    }
    state.mode = mode;
    // ★ M8 T7：切模式必须清掉**不该跨模式留存**的临时态（选中 / 临时高亮）——
    //   否则"区域查看"留下的区域高亮会带进"地图编辑"。map.js 另在 onStateChange 里监听 mode
    //   变化清它自己的刷子/路线态（跨文件：各自只清自己持有的状态）。
    state.selection = null;
    state.highlightRegions = [];
    state.highlightKind = "group";
    state.regionFocus = null;
    state.regionTag = null;
    state.decisionMakerFocus = null;
    applyMode();
    notify();
  }

  function setBranch(branch) {
    state.branch = branch;
    notify();
  }

  function setRevision(revision) {
    state.revision = revision;
    notify();
  }

  function setSelection(selection) {
    state.selection = selection;
    // ★ T7：地图点选与右栏决策人聚焦互斥 —— 点了地图，左栏就该按地图上的选择重算。
    state.decisionMakerFocus = null;
    notify();
  }

  /** 当前要高亮的区域集合（M7 T4；区域分组列表归 T6）。map.js 订阅它并懒拉区域 hex。 */
  function setHighlightRegions(ids, kind) {
    state.highlightRegions = Array.isArray(ids) ? ids.slice() : [];
    // ★ U3：第二参可选；缺省按"是否恰一个区域"推断（tag 全选恒显式传 group）。
    var list = state.highlightRegions;
    if (kind === "single" || kind === "group") {
      state.highlightKind = kind;
    } else {
      state.highlightKind = list.length === 1 ? "single" : "group";
    }
    notify();
  }

  /** 区域编辑的当前目标区域（M8 T10）；null 清空。map.js 据此渲染"正常色 / 其它淡色"。 */
  function setRegionFocus(id) {
    var next = id === undefined || id === null || id === "" ? null : String(id);
    if (next === state.regionFocus) {
      return;
    }
    state.regionFocus = next;
    applyMode();
    notify();
  }

  /**
   * ★ 2026-10-02 用户报障：右栏点 tag / 区域时记录的"当前筛选 tag"（trim 后空 ⇒ null）。
   * map.js 点格据此只在**该 tag** 覆盖的区域里取"最后覆盖"；null = 未选 tag（沿用 V3 全图末位）。
   */
  function setRegionTag(tag) {
    var next = tag === undefined || tag === null ? null : String(tag).trim();
    if (next === "") {
      next = null;
    }
    if (next === state.regionTag) {
      return;
    }
    state.regionTag = next;
    notify();
  }

  /**
   * 决策模式子页（T7）：`view`=决策人查看 / `approval`=审批。未知值不在此拒（可见性由纯函数
   * decisionSubpageVisibility fail-closed 决定 ⇒ 未知值 = 两个子页都不显示，不静默兜成 view）。
   */
  function setDecisionSubpage(id) {
    var next = id === undefined || id === null ? "" : String(id);
    if (next === state.decisionSubpage) {
      return;
    }
    state.decisionSubpage = next;
    applyDecisionSubpage();
    notify();
  }

  /** 右栏列表聚焦的决策人（T7）；null 清空。聚焦时清掉地图选择 ⇒ 左栏显示该决策人。 */
  function setDecisionMakerFocus(id) {
    var next = id === undefined || id === null || id === "" ? null : String(id);
    if (next === state.decisionMakerFocus) {
      return;
    }
    state.decisionMakerFocus = next;
    if (next !== null) {
      state.selection = null;
    }
    notify();
  }

  /** 订阅状态变化；返回取消订阅函数。 */
  function onStateChange(listener) {
    listeners.push(listener);
    return function () {
      var index = listeners.indexOf(listener);
      if (index >= 0) {
        listeners.splice(index, 1);
      }
    };
  }

  function notify() {
    listeners.slice().forEach(function (listener) {
      listener(state);
    });
  }

  /** 模式切换的落点：按钮选中态 + [data-modes] 元素的可见性。不碰任何数据。 */
  function applyMode() {
    document.body.setAttribute("data-mode", state.mode);
    document.body.setAttribute("data-region-focus", state.regionFocus || "");
    var current = byId("mode-current");
    if (current && window.SimosModes) {
      current.textContent = window.SimosModes.modeLabel(state.mode);
    }
    var bar = byId("mode-bar");
    if (bar) {
      Array.prototype.forEach.call(bar.querySelectorAll("button[data-mode]"), function (button) {
        if (button.disabled) {
          return;
        }
        var active = button.getAttribute("data-mode") === state.mode;
        button.classList.toggle("active", active);
        button.setAttribute("aria-pressed", active ? "true" : "false");
      });
    }
    Array.prototype.forEach.call(document.querySelectorAll("[data-modes]"), function (node) {
      var modes = node.getAttribute("data-modes").split(/\s+/);
      node.hidden = modes.indexOf(state.mode) === -1;
    });
    applyDecisionSubpage();
  }

  /**
   * 决策模式子页可见性（T7）：`[data-decision-subpage]` 节点恰一个可见（左栏详情 + 右栏列表同源切换）。
   * ★ fail-closed：判定走 panels.js 的纯函数 `decisionSubpageVisibility`（未知子页 ⇒ 三个都不显示）；
   *   该纯函数缺席（旧三页不加载 panels.js）或未知值 ⇒ **全部 hidden**，绝不兜成"显示第一个"。
   *
   * <p>★ 2026-09-23：tab 条（`[data-decision-tab]`）的选中态与上面**同源**——两处各判一次的话，
   * "内容切了、高亮没切"会在界面上留下**没有任何症状**的错位。
   */
  function applyDecisionSubpage() {
    var visibility =
      window.SimosPanels && window.SimosPanels.decisionSubpageVisibility
        ? window.SimosPanels.decisionSubpageVisibility(state.decisionSubpage)
        : null;
    Array.prototype.forEach.call(
      document.querySelectorAll("[data-decision-subpage]"),
      function (node) {
        var key = node.getAttribute("data-decision-subpage");
        node.hidden = !(visibility && visibility[key]);
      }
    );
    Array.prototype.forEach.call(document.querySelectorAll("[data-decision-tab]"), function (tab) {
      var key = tab.getAttribute("data-decision-tab");
      // 未知子页 ⇒ 一个都不选中（与三容器全隐藏一致，不静默兜成第一个）。
      var active = !!(visibility && visibility[key]);
      tab.classList.toggle("active", active);
      tab.setAttribute("aria-selected", active ? "true" : "false");
    });
  }

  /** 接线决策子页 tab 条（点一下 = 切子页，落点仍是 setDecisionSubpage ⇒ applyDecisionSubpage）。 */
  function mountDecisionTabs() {
    var bar = byId("decision-subpages");
    if (!bar || !bar.querySelectorAll) {
      return;
    }
    Array.prototype.forEach.call(bar.querySelectorAll("[data-decision-tab]"), function (tab) {
      tab.addEventListener("click", function () {
        setDecisionSubpage(tab.getAttribute("data-decision-tab"));
      });
    });
  }

  /** 接线模式栏按钮；禁用的（归 M8）不接线。 */
  function mountModeBar() {
    var bar = byId("mode-bar");
    if (!bar) {
      return;
    }
    Array.prototype.forEach.call(bar.querySelectorAll("button[data-mode]"), function (button) {
      button.addEventListener("click", function () {
        if (button.disabled) {
          return;
        }
        setMode(button.getAttribute("data-mode"));
      });
    });
    applyMode();
  }

  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (key) {
        if (key === "class") {
          node.className = attrs[key];
        } else if (key === "text") {
          node.textContent = attrs[key];
        } else if (key.indexOf("on") === 0 && typeof attrs[key] === "function") {
          node.addEventListener(key.slice(2), attrs[key]);
        } else {
          node.setAttribute(key, attrs[key]);
        }
      });
    }
    (children || []).forEach(function (child) {
      if (child === null || child === undefined) {
        return;
      }
      node.appendChild(typeof child === "string" ? document.createTextNode(child) : child);
    });
    return node;
  }

  function clear(node) {
    while (node.firstChild) {
      node.removeChild(node.firstChild);
    }
    return node;
  }

  function byId(id) {
    return document.getElementById(id);
  }

  function text(value) {
    return value === null || value === undefined ? "—" : String(value);
  }

  function json(value) {
    return JSON.stringify(value, null, 2);
  }

  /** 渲染顶部导航；标记当前页。 */
  function mountNav() {
    var nav = byId("nav");
    if (!nav) {
      return;
    }
    clear(nav);
    var here = window.location.pathname.replace(/\/+$/, "") || "/";
    PAGES.forEach(function (page) {
      var key = page.href.replace(/\/+$/, "") || "/";
      nav.appendChild(
        el("a", {
          href: page.href,
          class: key === here ? "active" : "",
          text: page.label,
        })
      );
    });
    nav.appendChild(
      el("span", {
        id: "shell-state",
        class: "shell-state",
        text: "状态加载中…",
      })
    );
  }

  /** 顶栏摘要文本：meta 缺省显示"无状态"。写后 refreshState 也会经它刷新，故不只 5s 轮询才更新。 */
  function renderShellState(meta) {
    var node = byId("shell-state");
    if (!node) {
      return;
    }
    if (!meta) {
      node.textContent = "无状态";
      return;
    }
    node.textContent =
      "分支 " + meta.branch + " · rev " + meta.revision + " · tick " + meta.timestamp.tick;
  }

  /** 轮询 /api/state 顶栏摘要（revision / tick）。失败时显示离线。 */
  function pollState() {
    var node = byId("shell-state");
    if (!node || !window.SimosApi) {
      return;
    }
    var refresh = async function () {
      try {
        await refreshState();
      } catch (e) {
        node.textContent = "状态不可用（" + e.message + "）";
      }
    };
    refresh();
    window.setInterval(refresh, 5000);
  }

  /**
   * 把 /api/state 的服务器视图写进状态机（M7 T3）：分支表与各分支 head **永远更新**；游标**只在首次**（revision
   * 尚为 null）时初始化。若每次轮询都回填游标，用户拖到中间节点的预览会被 5s 定时器弹回末端——那让 U1 的"只读预览"不可用。
   */
  function setServerState(branches, heads, meta, writeEpoch) {
    state.branches = Array.isArray(branches) ? branches.slice() : [];
    state.heads = heads && typeof heads === "object" ? Object.assign({}, heads) : {};
    if (typeof writeEpoch === "number" && isFinite(writeEpoch)) {
      state.writeEpoch = writeEpoch;
    }
    if (meta && meta.timestamp && meta.timestamp.tick !== undefined) {
      state.tick = meta.timestamp.tick;
    }
    if (state.revision === null || state.revision === undefined) {
      var branch = (meta && meta.branch) || state.branch;
      if (state.branches.indexOf(branch) < 0) {
        branch = state.branches.length > 0 ? state.branches[0] : branch;
      }
      state.branch = branch;
      if (state.heads[branch] !== null && state.heads[branch] !== undefined) {
        state.revision = state.heads[branch];
      } else if (meta) {
        state.revision = meta.revision;
      }
    }
    renderShellState(meta);
    notify();
  }

  /**
   * 重取 /api/state 并写回状态机；返回服务器视图（调用方可复用，避免重复请求）。
   * force=true 时先失效 TTL 缓存（写命令后必须读到新 head，M9 T2）。
   */
  async function refreshState(force) {
    if (force && window.SimosApi.invalidateState) {
      window.SimosApi.invalidateState();
    }
    var body = await window.SimosApi.state();
    setServerState(body.branches, body.heads, body.meta, body.writeEpoch);
    return body;
  }

  /** 所有面板的只读取数目标（M7 T3 约定；T4~T7 一律传它）。 */
  function target() {
    return { branch: state.branch, revision: state.revision, writeEpoch: state.writeEpoch };
  }

  // ── 写命令（M7 T7）────────────────────────────────────────────────
  //
  // ★ 唯一写入口是服务端的 CoreSimos.submit；前端只组信封、打三个 allowlist 端点之一
  //   （/api/command|advance|fork，R8）。本文件只发 submitCommand（/api/command）。

  /**
   * 组一条写命令信封：{type, payloadJson, branch, expectedRevision}。
   * ★ expectedRevision 一律取**当前游标**——过期由服务端 409 挡，前端不猜、不预检。
   * ★ 第三参可选 `frozenTarget`（{branch,revision}）：工具在“检查目标未变”之后同步传入，
   *   信封与提交前游标捕获都取它，避免检查与组信封之间被 await 插入新状态。
   */
  function commandEnvelope(type, payload, frozenTarget) {
    var target = frozenTarget && frozenTarget.branch !== undefined ? frozenTarget : state;
    return {
      type: type,
      payloadJson: JSON.stringify(payload === undefined ? {} : payload),
      branch: target.branch,
      expectedRevision: target.revision,
    };
  }

  /**
   * 提交一条写命令（M7 T7）：
   *
   * <ul>
   *   <li>成功 ⇒ 重取 /api/state 并把游标推进到新 head（写后可见：时间轴 +1、面板切到新 revision）；
   *   <li>409 ⇒ 重取 head 并把游标拉到服务端 `current.revision`（**不静默重试**，spec §五 第 5 步）；
   *   <li>422 ⇒ 原样回服务端 `reason`（MUST DO #6：不得吞掉、不得只 console）。
   * </ul>
   *
   * 返回 {ok:true, body} 或 {ok:false, kind:"conflict"|"rejected"|"error", message, error}——本函数**不碰
   * DOM**，显示位置由调用方决定。
   *
   * ★ 第三参可选 `frozenTarget`（{branch,revision}）：工具在同步校验目标未变后传入，信封使用冻结坐标；
   *   旧两参调用行为完全不变（取当前游标）。
   * ★ 提交前捕获 branch/revision；成功与 409 只在**当前游标仍等于提交前游标**时才 setRevision。
   *   `refreshState` 可以更新 heads/writeEpoch，但不抢当前 revision——用户切走的分支或同分支历史节点
   *   不能被迟到的提交响应拉回。
   */
  /**
   * 一条写提交的共同收尾：成功/冲突只在**当前游标仍是提交前冻结坐标**时刷新 state/writeEpoch 并推进 revision；
   * 用户已切走的分支/节点不会被迟到的响应拉回。写命令与编辑器工具共用这一段，不各写一套状态机。
   */
  async function runWriteSubmission(perform, submission, conflictCurrent, submittedEpoch) {
    var submittedBranch = submission.branch;
    var submittedRevision = submission.revision;
    function stillAtSubmittedBranchRevision() {
      return state.branch === submittedBranch
        && Number(state.revision) === Number(submittedRevision);
    }
    function epochMatches(epoch) {
      return epoch === undefined || epoch === null || Number(state.writeEpoch) === Number(epoch);
    }
    /**
     * 提交返回后、刷新前：branch/revision 必须仍是提交前冻结值；编辑器还要求 writeEpoch 仍是冻结值。
     * 返回 null = 用户已切走（不刷新、不推进游标）；否则尝试 refreshState，并回报刷新是否成功。
     */
    async function refreshBeforeMovingCursor() {
      if (!stillAtSubmittedBranchRevision() || !epochMatches(submittedEpoch)) {
        return null;
      }
      try {
        return { refreshed: true, body: await refreshState(true) };
      } catch (refreshError) {
        return { refreshed: false, body: null };
      }
    }
    /**
     * 刷新后：仍用 branch/revision 防用户切走；writeEpoch 改比**这次刷新返回的** epoch（不是提交前冻结值），
     * 否则 refreshState 更新 writeEpoch 后第二次比较永远不等，游标就永远推进不到新 revision。
     * 刷新失败时只有旧 writeCommand（无 submittedEpoch）保持旧语义；编辑器不拿旧 epoch 蒙混推进。
     */
    function canMoveCursorAfterRefresh(refreshed) {
      if (!stillAtSubmittedBranchRevision()) {
        return false;
      }
      if (refreshed && refreshed.refreshed) {
        var refreshEpoch =
          refreshed.body && refreshed.body.writeEpoch !== undefined
            ? refreshed.body.writeEpoch
            : state.writeEpoch;
        return epochMatches(refreshEpoch);
      }
      return submittedEpoch === undefined || submittedEpoch === null;
    }

    function refOf(body) {
      var ref = body && body.ref;
      if (!ref && body && body.result && body.result.submission) {
        ref = body.result.submission.ref;
      }
      return ref;
    }

    try {
      var body = await perform();
      var refreshed = await refreshBeforeMovingCursor();
      var moved = !!(refreshed && canMoveCursorAfterRefresh(refreshed));
      if (moved) {
        var ref = refOf(body);
        if (ref && ref.revision !== undefined
          && (ref.branch === undefined || ref.branch === submittedBranch)) {
          setRevision(ref.revision);
        }
      }
      return {
        ok: true,
        body: body,
        cursorStillCurrent: moved,
        completedTarget: moved ? target() : null,
      };
    } catch (error) {
      if (error && error.status === 409) {
        var current = conflictCurrent(error);
        var refreshed = await refreshBeforeMovingCursor();
        var stillCurrent = !!(refreshed && canMoveCursorAfterRefresh(refreshed));
        if (stillCurrent
          && current && current.revision !== undefined
          && (current.branch === undefined || current.branch === submittedBranch)) {
          setRevision(current.revision);
        }
        return {
          ok: false,
          kind: "conflict",
          message: "末端已移动，已自动重取最新状态",
          error: error,
          cursorStillCurrent: stillCurrent,
          completedTarget: stillCurrent ? target() : null,
        };
      }
      if (error && (error.status === 400 || error.status === 422)) {
        var bodyError = error.body || {};
        var reason =
          bodyError.reason
          || (bodyError.result && bodyError.result.reason)
          || bodyError.message
          || (error.status === 400 ? "BAD_REQUEST" : "REJECTED");
        var stillCurrent = stillAtSubmittedBranchRevision() && epochMatches(submittedEpoch);
        return {
          ok: false,
          kind: "rejected",
          message: reason,
          error: error,
          cursorStillCurrent: stillCurrent,
          completedTarget: stillCurrent ? target() : null,
        };
      }
      var stillCurrent = stillAtSubmittedBranchRevision() && epochMatches(submittedEpoch);
      return {
        ok: false,
        kind: "error",
        message: (error && error.message) || String(error),
        error: error,
        cursorStillCurrent: stillCurrent,
        completedTarget: stillCurrent ? target() : null,
      };
    }
  }

  async function writeCommand(type, payload, frozenTarget) {
    if (window.SimosModes && !window.SimosModes.isWriteAllowed(state.mode, type)) {
      return {
        ok: false,
        kind: "mode-denied",
        message: "当前模式「" + window.SimosModes.modeLabel(state.mode) + "」不允许写命令 " + type,
      };
    }
    // 同步捕获：与 commandEnvelope 使用同一份坐标，中间没有 await。
    var submission = frozenTarget && frozenTarget.branch !== undefined ? frozenTarget : state;
    return runWriteSubmission(
      function () { return window.SimosApi.submitCommand(commandEnvelope(type, payload, submission)); },
      submission,
      function (error) { return error.body && error.body.current ? error.body.current : null; },
      undefined);
  }

  /** 编辑器（国家/军队）允许的现有工作模式。 */
  function editorModeAllowed(kind) {
    if (kind !== "nation" && kind !== "formation") {
      return false;
    }
    return state.mode === "map-edit" || state.mode === "region-edit" || state.mode === "unit";
  }

  /**
   * 网页编辑器固定工具的共享写入口：冻结当前 {branch,revision,writeEpoch}，branch/expectedRevision 一律由冻结
   * target 覆盖（JSON 不能偷偷指向别的节点）；成功只在目标仍当前时刷新 state/writeEpoch。
   * kind = nation|formation；payload 是领域工具的完整 JSON（dryRun 被强制为 false）。
   */
  async function writeEditorTool(kind, payload, frozenTarget) {
    if (!editorModeAllowed(kind)) {
      return {
        ok: false,
        kind: "mode-denied",
        message: "当前模式「" + window.SimosModes.modeLabel(state.mode) + "」不允许" + (kind === "nation" ? "国家" : "编制") + "编辑",
        // 同步即时拒绝：目标没有在 await 期间变化，模块应显示提示；不是迟到响应。
        cursorStillCurrent: true,
        completedTarget: target(),
      };
    }
    var submission = frozenTarget && frozenTarget.branch !== undefined ? frozenTarget : target();
    if (submission.branch === null || submission.branch === undefined
      || submission.revision === null || submission.revision === undefined) {
      return {
        ok: false,
        kind: "bad-target",
        message: "当前没有冻结的 branch/revision，无法保存；请先载入世界状态",
        // 同步即时拒绝：没有可读回目标，但必须让模块显示失败；不是迟到响应。
        cursorStillCurrent: true,
        completedTarget: null,
      };
    }
    var body = Object.assign({}, payload === undefined ? {} : payload);
    body.branch = submission.branch;
    body.expectedRevision = submission.revision;
    body.dryRun = false;
    return runWriteSubmission(
      function () { return window.SimosApi.postEditor(kind, body); },
      submission,
      function (error) {
        var parsed = error && error.body;
        return parsed && parsed.result && parsed.result.submission
          ? parsed.result.submission.current
          : null;
      },
      submission.writeEpoch);
  }

  /** 页面初始化共用入口：nav + state 摘要；工作台页额外接线模式栏与各面板骨架。 */
  function boot(options) {
    var opts = options || {};
    mountNav();
    pollState();
    if (byId("mode-bar")) {
      mountModeBar();
      mountDecisionTabs();
      if (window.SimosPanels && window.SimosPanels.init) {
        window.SimosPanels.init();
      }
      if (window.SimosUnitTree && window.SimosUnitTree.init) {
        window.SimosUnitTree.init();
      }
      if (window.SimosTimeline && window.SimosTimeline.init) {
        window.SimosTimeline.init();
      }
    }
    document.title = (opts.title ? opts.title + " · " : "") + "Simos Shell";
  }

  /** 一个轻量的消息条；tone ∈ ok | warn | err | muted。 */
  function statusMessage(node, message, tone) {
    if (!node) {
      return;
    }
    node.className = "status " + (tone || "muted");
    node.textContent = message;
  }

  /** 表单字段值读取：空串 → null；数字字段转 Number。 */
  function fieldValue(form, name) {
    var input = form.querySelector('[name="' + name + '"]');
    if (!input) {
      return undefined;
    }
    var raw = (input.value || "").trim();
    if (raw === "") {
      return null;
    }
    return raw;
  }

  function intField(form, name) {
    var raw = fieldValue(form, name);
    if (raw === null || raw === undefined) {
      return null;
    }
    var n = Number(raw);
    return Number.isFinite(n) ? n : null;
  }

  window.SimosApp = {
    el: el,
    clear: clear,
    byId: byId,
    text: text,
    json: json,
    boot: boot,
    mountNav: mountNav,
    statusMessage: statusMessage,
    fieldValue: fieldValue,
    intField: intField,
    getState: getState,
    setMode: setMode,
    setBranch: setBranch,
    setRevision: setRevision,
    setSelection: setSelection,
    setHighlightRegions: setHighlightRegions,
    setRegionFocus: setRegionFocus,
    setRegionTag: setRegionTag,
    setDecisionSubpage: setDecisionSubpage,
    setDecisionMakerFocus: setDecisionMakerFocus,
    onStateChange: onStateChange,
    applyMode: applyMode,
    applyDecisionSubpage: applyDecisionSubpage,
    mountModeBar: mountModeBar,
    mountDecisionTabs: mountDecisionTabs,
    target: target,
    refreshState: refreshState,
    setServerState: setServerState,
    commandEnvelope: commandEnvelope,
    writeCommand: writeCommand,
    writeEditorTool: writeEditorTool,
  };
})();
