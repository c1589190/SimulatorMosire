// panels.js —— 左栏详情（M7 T2 骨架 → T4 接真读数 → T5 补全判据③：hex 加"人口"、单位补全字段）/ 右栏区域面板（M7 T6：按 RegionMeta.tag 分组）。
// ★ 无框架、无构建：原生 DOM；所有只读取数经 window.SimosApi 并带 window.SimosApp.target()
//   （T3 约定在 T4 由本文件收口：面板值随游标的 {branch, revision} 变化）。
// ★ 本文件不含任何写调用。
// ★ M7 T6：右栏 = 区域查看模式（`data-modes="region"` 控制可见性）；`groupByTag` 是**纯函数**
//   （不查 IO、不碰 DOM），分组规则：`meta.tag` 的 null/空白一律归入「未标注」桶，该桶排最后。
//   点区域 ⇒ `setHighlightRegions([id])` 只高亮该区 + 出详情；点标签 ⇒ 该标签下全部区域一起高亮。

(function () {
  "use strict";

  var app = window.SimosApp;
  var api = window.SimosApi;
  // ★ M12 第三波：无状态纯函数已搬出（readout.js / decisionmodel.js，index.html 在本文件之前引入）。
  //   此处按名取回 ⇒ **调用点与 window.SimosPanels 导出对象一字未改**。
  var readout = window.SimosReadout;
  var decisionModel = window.SimosDecisionModel;
  // ★ M12 第四波：共享 DOM 叶子（paneldom.js）与右栏（panel-right.js）已搬出，index.html 均在本文件之前引入。
  //   同样按名取回 ⇒ **调用点与 window.SimosPanels 导出对象一字未改**。
  var panelDom = window.SimosPanelDom;
  var loadOverview = panelDom.loadOverview;
  var targetLabel = panelDom.targetLabel;
  var appendRow = panelDom.appendRow;
  var appendLiveRow = panelDom.appendLiveRow;
  var valueOf = panelDom.valueOf;
  var renderRight = window.SimosPanelRight.renderRight;
  var UNTAGGED_LABEL = readout.UNTAGGED_LABEL;
  var IMPASSABLE_MOVE_COST = readout.IMPASSABLE_MOVE_COST;
  var DECISION_SUBPAGES = decisionModel.DECISION_SUBPAGES;
  var AFFILIATION_LABELS = decisionModel.AFFILIATION_LABELS;
  var normalizeTag = readout.normalizeTag;
  var groupByTag = readout.groupByTag;
  var regionMembershipSummary = readout.regionMembershipSummary;
  var millisToMpText = readout.millisToMpText;
  var perMilleToRateText = readout.perMilleToRateText;
  var equipmentText = readout.equipmentText;
  var hexLabel = readout.hexLabel;
  var movementReadout = readout.movementReadout;
  var unitTreeSectionVisible = readout.unitTreeSectionVisible;
  var textOrNull = decisionModel.textOrNull;
  var valueOrDash = decisionModel.valueOrDash;
  var keyConfiguredText = decisionModel.keyConfiguredText;
  var affiliationKindLabel = decisionModel.affiliationKindLabel;
  var affiliationLabel = decisionModel.affiliationLabel;
  var pendingStatusText = decisionModel.pendingStatusText;
  var startDecisionGate = decisionModel.startDecisionGate;
  var decisionMakerGroups = decisionModel.decisionMakerGroups;
  var decisionMakersForNation = decisionModel.decisionMakersForNation;
  var decisionMakerForUnit = decisionModel.decisionMakerForUnit;
  var decisionMakerFields = decisionModel.decisionMakerFields;
  var prefixSummary = decisionModel.prefixSummary;
  var decisionSubpageState = decisionModel.decisionSubpageState;
  var decisionSubpageVisibility = decisionModel.decisionSubpageVisibility;
  var providerFormToPayload = decisionModel.providerFormToPayload;
  var providerFields = decisionModel.providerFields;
  var providerBindingPayload = decisionModel.providerBindingPayload;
  var runDecisionGate = decisionModel.runDecisionGate;
  var runningText = decisionModel.runningText;
  var lastToolName = decisionModel.lastToolName;
  var runTraceFromStatus = decisionModel.runTraceFromStatus;
  var runOutcomeText = decisionModel.runOutcomeText;
  var runOutcomeTone = decisionModel.runOutcomeTone;
  var toolCallCount = decisionModel.toolCallCount;
  var runProgressLines = decisionModel.runProgressLines;
  var runTraceFields = decisionModel.runTraceFields;
  var directiveFields = decisionModel.directiveFields;
  var directiveHeadline = decisionModel.directiveHeadline;

  var requestToken = 0;
  var lastKey = null;

  /**
   * T9：把从属区域读数写进左栏（每个区域自己的 hexCount + **并集**合计）。
   *
   * ★★ B16（2026-09-23 用户实测）：**实现口径不进主栏** —— 主栏只留结论（「N 格（M 个区域）」），
   * 口径（并集 / 逐 hex 去重 / 重叠格只计一次）一律移进 `title=` tooltip。
   * ★ 口径本身**一个字没改**：`region-view.test.cjs` 的
   * `page-层-shows-the-union-and-never-a-summed-total` 仍钉着「写进 DOM 的必须是并集、求和值绝不进 DOM」。
   */
  function appendRegionMembership(detail, summary) {
    detail.appendChild(
      app.el("dt", {
        text: "从属区域",
        title: "每个区域各自显示它自己的格数；区域之间不合并、不求和（一个格可以同时属于多个区域）。",
      })
    );
    var box = app.el("dd", {
      class: "region-membership",
      id: "hex-region-membership",
      "data-region-count": String(summary.rows.length),
    });
    summary.rows.forEach(function (row) {
      var known = row.hexCount !== null && row.hexCount !== undefined;
      var line = app.el("div", { class: "region-membership-row" });
      line.setAttribute("data-region-id", row.id);
      line.appendChild(app.el("span", { class: "region-membership-name", text: app.text(row.name) }));
      line.appendChild(
        app.el("span", {
          class: "region-membership-hexcount",
          "data-hexcount": known ? String(row.hexCount) : "",
          text: known ? row.hexCount + " 格" : "—",
        })
      );
      box.appendChild(line);
    });
    detail.appendChild(box);
    detail.appendChild(
      app.el("dt", {
        text: "合计",
        title: "所有从属区域的并集：逐 hex 去重，重叠格（同时属于多个区域的格）只计一次。",
      })
    );
    var total = app.el("dd", {
      class: "region-membership-total",
      id: "hex-region-union",
      "data-metric": "union",
    });
    if (summary.complete) {
      total.setAttribute("data-union-count", String(summary.unionCount));
      total.setAttribute("data-shared-hex-count", String(summary.sharedHexCount));
      total.textContent = summary.unionCount + " 格（" + summary.rows.length + " 个区域）";
      total.setAttribute(
        "title",
        "并集：逐 hex 去重，重叠 " +
          summary.sharedHexCount +
          " 格只计一次（不是各区域格数之和）。"
      );
    } else {
      total.setAttribute("data-union-count", "");
      total.setAttribute("data-shared-hex-count", "");
      total.textContent = "—（有区域读不到格列表）";
      total.setAttribute(
        "title",
        "有区域读不到它的格列表 ⇒ 算不出并集，这里不显示数字（不拿各区域格数之和顶替）。"
      );
    }
    detail.appendChild(total);
  }

  /** 拉各区域详情后写读数（失败 ⇒ 该区域 hexes=null ⇒ 合计显示"不知道"，不静默求和）。 */
  function renderRegionMembership(detail, regionIds, token) {
    if (!regionIds.length) {
      return;
    }
    Promise.all(
      regionIds.map(function (id) {
        return api.mapRegion(id, app.target()).catch(function (e) {
          window.console.warn("[SimosPanels] 区域读取失败：" + id + "：" + e.message);
          return { id: id, name: id, hexes: null };
        });
      })
    ).then(function (regions) {
      if (token !== requestToken) {
        return;
      }
      appendRegionMembership(detail, regionMembershipSummary(regions));
    });
  }

  /**
   * 在左栏 `<dl>` 里起一个**带标题的分节**（第2波 B9/B17：把「这个单位是什么」与「在途移动」分开）。
   *
   * <p>结构 = `dt(标题)` + `dd > dl.kv`（内容放进**嵌套** dl）：保持 `dl > dt + dd` 的合法结构，
   * 不走"只有 dt、没有 dd"那种非法写法。标题与正文都在 styles.css 里占满整行
   * （`grid-column: 1 / -1`）⇒ 左栏看起来就是"小节标题 + 该节的一组键值"。
   */
  function appendRowGroup(detail, title, fill) {
    var body = app.el("dd", { class: "kv-group-body" });
    var inner = app.el("dl", { class: "kv kv-nested" });
    fill(inner);
    detail.appendChild(app.el("dt", { class: "kv-group-title", text: title }));
    detail.appendChild(body);
    body.appendChild(inner);
  }

  /**
   * 把移动读数逐行写进左栏（无路线 ⇒ 一行「无」）。
   *
   * ★★ B15（2026-09-23 用户实测）：**内部单位不进 DOM** —— 毫 MP ⇒ `"N MP"`、‰ ⇒ `"N×（N‰）"`；
   * 「那 1000 是怎么来的」这类口径一律进 `title=`。用户看到的就该是他能直接拿来推演的数，
   * 不该自己在脑子里除 1000（原样印 4000 就是内部单位泄漏）。
   */
  function appendMovementRows(detail, unit, overview) {
    var readout = movementReadout(unit, overview);
    if (!readout) {
      appendRow(detail, "movement", "无（无在途路线）");
      return;
    }
    appendRow(detail, "movement", "有");
    appendRow(detail, "路线格数", readout.pathLength);
    appendRow(
      detail,
      "本 tick 预算",
      millisToMpText(readout.budgetPerTickMillis),
      "本 tick 可用的移动点数 = 出发速度 × 1 tick（领域内部是毫 MP 定点，这里已换算成 MP）。"
    );
    appendRow(
      detail,
      "路线每格成本",
      millisToMpText(readout.stepCostMillis),
      "进入下一格要花的移动点数 = 该格地形成本 × 出发机动。"
    );
    appendRow(
      detail,
      "路线总成本",
      millisToMpText(readout.totalCostMillis),
      "整条路线各格成本之和（逐格相加，不含起点那一格）。"
    );
    appendRow(detail, "status", readout.status);
    appendRow(detail, "currentHex", readout.currentHex ? hexLabel(readout.currentHex) : "—");
    appendRow(detail, "nextHex", readout.nextHex ? hexLabel(readout.nextHex) : "—");
    appendRow(
      detail,
      "进入下一格还需",
      millisToMpText(readout.remainingMillis),
      "走完当前这一格还欠的移动点数（欠清才进下一格；已抵达 / 需重规划 ⇒ 空）。"
    );
    appendRow(
      detail,
      "预计到达 tick",
      readout.etaTick === null ? "—（需重规划）" : readout.etaTick
    );
    appendRow(detail, "出发 tick", readout.departedAtTick);
    appendRow(
      detail,
      "出发速度",
      readout.speedAtDeparture + " MP/tick",
      "出发那一刻冻结的速度：每 tick 能用的移动点数（在途改状态不回溯）。"
    );
    appendRow(
      detail,
      "出发机动",
      perMilleToRateText(readout.mobilityPerMilleAtDeparture),
      "出发那一刻冻结的移动成本倍率：每格成本 = 地形成本 × 该值 ÷ 1000 ⇒ 1000‰ = 1.0×（越大走得越慢）。"
    );
  }

  /** 把编制那节的可见性落到 DOM（节点缺席 ⇒ 静默跳过：旧三页 / 别的宿主页不挂它）。 */
  function applyUnitTreeSection(visible) {
    var node = app.byId("unit-tree-section");
    if (node) {
      node.hidden = !visible;
    }
  }

  function renderHex(selection, token) {
    var status = app.byId("left-status");
    var detail = app.clear(app.byId("selection-detail"));
    app.statusMessage(status, "查询 " + hexLabel(selection) + "（" + targetLabel() + "）…", "muted");
    // 人口序列可能不存在（/api/social/population 404）⇒ 折成 null，不让整条详情失败（判据③"人口"）。
    Promise.all([
      api.mapHex(selection.q, selection.r, app.target()),
      api.cachedUnits(app.target()),
      api.population(selection.q, selection.r, app.target()).catch(function () {
        return null;
      }),
    ])
      .then(function (results) {
        if (token !== requestToken) {
          return;
        }
        var hex = results[0];
        var unitsHere = (results[1].units || []).filter(function (u) {
          return u.position && u.position.q === selection.q && u.position.r === selection.r;
        });
        var population = results[2];
        // ★ B8：这一格到底有没有单位，**就在这里**算出来 ⇒ 编制那节的可见性同处落地（同一份口径，不在别处再判一次）。
        applyUnitTreeSection(unitTreeSectionVisible(selection, unitsHere));
        var terrainText = hex.terrain;
        if (hex.terrainType && hex.terrainType.name) {
          terrainText = hex.terrain + "（" + hex.terrainType.name + "）";
        }
        appendRow(detail, "q", hex.q);
        appendRow(detail, "r", hex.r);
        appendRow(detail, "terrain", terrainText);
        appendRow(detail, "height", hex.height);
        var regionIds = Array.isArray(hex.regions) ? hex.regions : [];
        appendRow(detail, "regions", regionIds.length ? regionIds.join("、") : "无区域");
        // ★ M8 T9：每个区域给出**它自己的** hexCount，合计是**真并集**（不是求和 —— 裁定 72.1）。
        renderRegionMembership(detail, regionIds, token);
        appendRow(
          detail,
          "该处单位",
          unitsHere.length
            ? unitsHere
                .map(function (u) {
                  return u.id + " " + app.text(u.name);
                })
                .join("；")
            : "无"
        );
        appendRow(
          detail,
          "人口",
          population && population.population !== null && population.population !== undefined
            ? population.population
            : "无人口数据",
          "该格的人口数（社会模块的数据）；这一版世界没接入人口序列时显示「无人口数据」。"
        );
        app.statusMessage(status, hexLabel(hex) + " · " + targetLabel(), "ok");
      })
      .catch(function (e) {
        if (token !== requestToken) {
          return;
        }
        app.statusMessage(status, "查询失败：" + e.message, "err");
      });
  }

  function renderUnit(selection, token) {
    var status = app.byId("left-status");
    var detail = app.clear(app.byId("selection-detail"));
    app.statusMessage(status, "查询单位 " + selection.id + "（" + targetLabel() + "）…", "muted");
    Promise.all([
      api.unit(selection.id, app.target()),
      loadOverview().catch(function () {
        return null;
      }),
    ])
      .then(function (results) {
        if (token !== requestToken) {
          return;
        }
        var unit = results[0];
        var overview = results[1];
        // ★ 第2波 B9/B17（用户 2026-09-23 实测「鬼知道这个单位有啥项目」）：首屏**先"这个单位是什么"**，
        //   移动信息（预计到达 / 出发 tick / 出发速度…）归入「在途移动」分节、**不再压在最上面**；
        //   编辑表单在下面各自的 `<section>`（index.html）里 ⇒ 本函数只负责"先展示"。
        //   ★ 下属 = 紧随其后的「编制」那一节（独立 DOM section），这里不重复渲染。
        appendRowGroup(detail, "单位", function (dl) {
          appendRow(dl, "id", unit.id);
          appendRow(dl, "name", unit.name);
          appendRow(dl, "人数", unit.member);
          appendRow(dl, "装备", equipmentText(unit.equipment));
          // ★ B15：这两行也是"内部单位"（`speed` 是 MP/tick、`mobilityPerMille` 是 ‰ 定点）⇒ 同处换算。
          appendRow(
            dl,
            "速度",
            unit.speed + " MP/tick",
            "每 tick 能用的移动点数（与「本 tick 预算」同一口径）。"
          );
          appendRow(
            dl,
            "机动",
            perMilleToRateText(unit.mobilityPerMille),
            "移动成本倍率（‰ 定点：1000‰ = 1.0×；每格成本 = 地形成本 × 该值 ÷ 1000）。"
          );
          appendRow(dl, "位置", unit.position ? hexLabel(unit.position) : "—");
          appendRow(
            dl,
            "上级",
            unit.parent === null || unit.parent === undefined ? "—" : unit.parent
          );
        });
        appendRowGroup(detail, "在途移动", function (dl) {
          appendMovementRows(dl, unit, overview);
        });
        app.statusMessage(status, "单位 " + unit.id + " · " + targetLabel(), "ok");
      })
      .catch(function (e) {
        if (token !== requestToken) {
          return;
        }
        app.statusMessage(status, "查询失败：" + e.message, "err");
      });
  }

  /** 左栏渲染；选择或目标坐标变化时才重取（模式/高亮变化不触发重取）。 */
  function renderLeft(state) {
    var status = app.byId("left-status");
    var detail = app.byId("selection-detail");
    if (!status || !detail) {
      return;
    }
    var selection = state ? state.selection : null;
    var key = JSON.stringify([selection, app.target()]);
    if (key === lastKey) {
      return;
    }
    lastKey = key;
    var token = ++requestToken;
    if (!selection) {
      app.clear(detail);
      app.statusMessage(status, "点选地图或单位以查看详情。", "muted");
      // ★ B8：什么都没选 ⇒ 没有可矛盾的对象，编制树是主要的单位入口，照常显示。
      applyUnitTreeSection(true);
      return;
    }
    if (selection.kind === "hex") {
      // ★ B8：hex 分支的可见性**故意不在这里判** —— 要先知道"这格到底有没有单位"，而那是 renderHex
      //   取数之后才知道的（在那里落地）。在此之前保持原状，免得"取数中先隐藏、拿到单位又弹回来"那种无谓的闪。
      renderHex(selection, token);
    } else if (selection.kind === "unit") {
      // ★ B8：选中单位 ⇒ 立刻显示（编制树里正要高亮它，没有什么可矛盾的，不必等取数）。
      applyUnitTreeSection(true);
      renderUnit(selection, token);
    } else {
      app.clear(detail);
      app.statusMessage(status, "未知选择类型：" + app.text(selection.kind), "warn");
      applyUnitTreeSection(true);
    }
  }

  // ═══ 决策模式（T7）：一个模式两个子页 + 决策人交互 ═══════════════════════════
  //
  // ★★ 三件事模型（spec §四.1）在本文件里的边界：**只做"显示"**——
  //   ② 「本 tick 哪些决策人理论上有待决事项」是 **T9** 才有的服务端计算 ⇒ 本文件**不造待决数据**：
  //       `due` 取不到（null / undefined）就显示「—」（pendingStatusText），绝不拿 false 顶替。
  //   ④ 「开始决策」按钮是 **T10** 的入口 ⇒ 本文件**不实现它的行为**，也不放该按钮。
  //   ⑤ 决策人 Agent 的异步出令与 ⑥ 落 revision 都不在本模式内发生。
  //   ⇒ 本模式**只读**（modes.js 的 decision.writes 恒为 []）；唯一的写是审批子页的
  //     `POST /api/approvals/{id}`（审批裁决，非命令写，单独列在 api.js / write-allowlist 里）。

  /**
   * 「可见范围（现算）」那一行的 **DOM 锚点 id**（**唯一拼写点**）。
   *
   * <p>★ 为什么要跨文件：这一行的**内容**来自只读端点 `/api/sd/decision-makers/{id}/scope`，而拉它并画高亮的是
   * `map.js`（可见范围高亮与区域高亮共用同一层渲染）。两个文件只经这一个 id 交接，不互相持有状态、也不各拉一次
   * ——各拉一次就是"同一件事两份实现"的开端。
   */
  var DECISION_SCOPE_SUMMARY_ID = "decision-scope-summary";

  // ── 决策模式左栏渲染 ────────────────────────────────────────────────

  var decisionLeftToken = 0;
  var decisionLeftKey = null;

  function setDecisionViewStatus(message, tone) {
    app.statusMessage(app.byId("decision-view-status"), message, tone);
  }

  /**
   * 右栏「决策人」子页顶部的**选中态说明**（第2波 B11）：说明当前选中项为何"没有决策人"、点了会怎样。
   *
   * <p>★ 用户原话「右边栏也没有对应显示」——左栏说了"无决策人"，右栏却只列全部决策人、看不出"你选中的这个没有"。
   * 无说明 ⇒ 收起（`hidden`）。节点缺席（旧页 / 别的宿主）⇒ 静默跳过。
   */
  function setDecisionSelectionNote(text) {
    var node = app.byId("decision-selection-note");
    if (!node) {
      return;
    }
    node.hidden = !text;
    node.textContent = text || "";
  }

  // ── 「开始决策」入口（T10，三件事模型 ④；2026-09-23 按用户裁定改造）────────────
  // ★ 目标 = 左栏当前展示的那个决策人（右栏点选 / 单位解析出的单个；国家多决策人时无单一目标）。
  // ★ 写路径 = api.startDecision（POST /api/sd/start-decision 窄端点，服务端写死 sd.StartDecision）——
  //   本文件**不发通用命令写**（通用写只归 app 的 writeCommand）；审批裁决那条写另有其处（decideApproval）。
  //
  // ★★ **due 不再是闸门，只是提示**（用户 2026-09-23：「我在当前回合点开始决策」要随时可点）：
  //   `startDecisionGate` 仍然算出理由（"非待决（本 tick 未到决策周期）"这类），但**不拦**——按钮不 disabled、
  //   点了就发；理由只显示在状态行里，让用户看得见"此刻本来不是它的决策窗口"。
  //
  // ★★ **点一次 = （有补充指示时先发它）+ 发起 + 真跑一轮**（用户要的"跑完在下面生成当前 tick 这个
  //   决策人的完整决策"）：
  //   ① 文本框非空 ⇒ 先把那句话作为一条 user 消息发进它的会话（POST …/say，append-only、不改世界）；
  //   ② `sd.StartDecision`（原样保留：它是"开始一次决策"的世界事实，判决链挂在它后面）；
  //   ③ 接着起一轮**异步**的 `sd.RunDecision`（POST 立即返回；进度与结局靠 run-status 轮询，见下一节）。
  //   ★★ 2026-09-23（用户裁定）：「发送」/「让它跑一轮」/「开始决策」**三个按钮合并成这一个**——
  //   故 `#decision-run` 与 `#decision-say-send` 两个按钮已从 index.html 退场（**端点一个都没动**）。
  //   再点一次 = 再跑一轮（用户原话：「视为不满意，让 llm 重新决策」）——**上下文沿用**，
  //   唯一的清空手段是「上下文重置」（decideResetDecisionContext）。
  var startDecisionTarget = null;
  var lastStartGateStamp = null;

  function setStartDecisionStatus(message, tone) {
    app.statusMessage(app.byId("decision-start-status"), message, tone);
  }

  /**
   * 没选中决策人时按钮的提示语（B14）。
   *
   * <p>★ 与 `index.html` 里 `#decision-start-status` 的**初始文案逐字相同**：初始态显示的这一句，
   * 和 `init()` 之后 `updateStartDecisionControl()` 落到实处的那一句必须是同一个 —— 两处各写一份，
   * 界面上就会出现"脚本没跑起来时说 A、跑起来说 B"这种只有肉眼才看得出的错位。
   */
  var NO_TARGET_HINT = "先选一个决策人：在地图上点选国家区域，或点选有决策人的单位，再点这里。";

  function updateStartDecisionControl() {
    var button = app.byId("decision-start");
    var gate = startDecisionGate(startDecisionTarget);
    var hasTarget = !!(
      startDecisionTarget &&
      startDecisionTarget.id !== null &&
      startDecisionTarget.id !== undefined &&
      startDecisionTarget.id !== ""
    );
    var targetId = hasTarget ? String(startDecisionTarget.id) : "";
    if (button) {
      // ★★ B14（2026-09-23 用户实测）：**没目标 ⇒ 禁用** —— 禁用与提示语必须是同一件事的两面
      //   （原状是"按钮可点 + 状态行写『随时可点 · 未选中决策人』"，点下去只打印一句警告 = **假的可点**）。
      // ★ 有目标 ⇒ **永远可点**（2026-09-23 用户裁定「我在当前回合点开始决策」要随时可点）：
      //   `due` 只是提示、**不拦人** —— 这一条没变，见下面那句状态文案。
      button.disabled = !hasTarget;
      button.setAttribute("data-decision-target", targetId);
    }
    var stamp = targetId + "|" + gate.reason;
    if (stamp !== lastStartGateStamp) {
      lastStartGateStamp = stamp;
      // ★ 有目标时的文案从"能不能点"改成"此刻是什么时机"：门禁不再是权限，是提示（"随时可点 · 非待决（…）"）。
      setStartDecisionStatus(
        hasTarget ? "随时可点 · " + gate.reason : NO_TARGET_HINT,
        gate.enabled ? "ok" : "muted"
      );
    }
  }

  /**
   * 设定两个窄写的当前目标（「开始决策」/「让它跑一轮」）——**同一个目标**，一处设两处生效。
   *
   * <p>★ 合成一处而不是各设一份：两者若各持有自己的目标，界面上就会出现"按钮 A 指向甲、按钮 B 指向乙"而**没有任何症状**。
   */
  function setStartDecisionTarget(maker) {
    startDecisionTarget = maker || null;
    setRunDecisionTarget(maker);
    updateStartDecisionControl();
  }

  /**
   * **409（游标过期）的公用重试**：重取状态、把游标拉到服务端 {@code current.revision}，**自动重试一次**。
   *
   * <p>★ 抽成一处而不是每个写各写一遍：三个入口（发起 / 跑一轮 / 重置）的 409 语义完全一样，各写一遍就会有一条
   * 漏掉 {@code setRevision} 而**没有任何症状**（下一次点击继续带旧 revision）。
   *
   * @param run 收到**新鲜 revision** 之后要重跑的那一步；返回 {@code null} 表示"不该重试"（调用方照常走失败分支）
   */
  function retryOnConflict(e, mayRetry, run) {
    if (!mayRetry || !e || e.status !== 409) {
      return null;
    }
    var current = e.body && e.body.current ? e.body.current : null;
    return Promise.resolve()
      .then(function () {
        return app.refreshState ? app.refreshState(true) : null;
      })
      .catch(function () {
        return null;
      })
      .then(function () {
        var fresh =
          current && current.revision !== undefined
            ? current.revision
            : (app.target() || {}).revision;
        if (app.setRevision && fresh !== null && fresh !== undefined) {
          app.setRevision(fresh);
        }
        return run(fresh);
      });
  }

  /**
   * 点「开始决策」：**不看 due**（用户要随时可点），但有目标才发得出去。
   *
   * <p>★★ 2026-09-23（用户裁定）：**三个按钮合并成一个**（「发送」/「让它跑一轮」/「开始决策」）。
   * 一次点击按**固定顺序**做三件事，两种情形：
   *
   * <ul>
   *   <li>文本框**空** ⇒ 与合并前逐字一致：`sd.StartDecision`（发起）→ 异步跑一轮；
   *   <li>文本框**有内容** ⇒ ① 先把这句作为一条 user 消息发进它的会话，**成功之后**才 ② 发起、③ 跑一轮。
   * </ul>
   *
   * <p>★ 顺序不能反：这一轮读的是**会话**（以及挂在发起后面的判决链）——话没落进去就发起，
   * "带着补充指示跑一轮"当场变成谎，而界面上**看不出任何差别**。
   * ★ ① 失败 ⇒ **当场停**（不接着 ②③）：把用户写的指示静默丢掉比不跑更坏；文本框里的内容保留着（不清空）。
   */
  function decideStartDecision() {
    var target = startDecisionTarget;
    if (!target || target.id === null || target.id === undefined || target.id === "") {
      setStartDecisionStatus("未选中决策人：先在地图上点选国家区域，或点选有决策人的单位。", "warn");
      return;
    }
    var at = app.target() || {};
    var gate = startDecisionGate(target);
    if (!decisionSayText()) {
      setStartDecisionStatus("发起决策 " + target.id + "…（" + gate.reason + "）", "muted");
      attemptStartDecision(target, at.branch, at.revision, true);
      return;
    }
    // 情形 B：先发那句话（第 ① 步），成了才发起。
    setStartDecisionStatus(
      "发起决策 " + target.id + "…（先发文本框里的补充指示；" + gate.reason + "）",
      "muted"
    );
    sendDecisionSay().then(function (sent) {
      if (!sent || !sent.ok) {
        setStartDecisionStatus(
          "已停下：补充指示没发出去（" +
            ((sent && sent.reason) || "未知原因") +
            "），这一轮没有发起——文本框里的内容还在。",
          "err"
        );
        return null;
      }
      // ★ 重取游标：say 那一跳可能已经刷新过状态（游标过期时），用**当前**的 branch/revision 才不撞 409。
      var fresh = app.target() || {};
      return attemptStartDecision(target, fresh.branch, fresh.revision, true);
    });
  }

  /**
   * 发起一次「开始决策」；收到 409（游标过期）⇒ 重取 head、用服务端 {@code current.revision} **自动重试一次**
   * ⇒ 用户点一次即可成功。成功后**接着**起一轮异步的「跑一轮」（见 {@link startAsyncRun}）。
   *
   * @param mayRetry 只允许自动重试一次（防冲突循环）；重试轮不再重试
   */
  function attemptStartDecision(target, branch, revision, mayRetry) {
    return api
      .startDecision(branch, revision, target.id)
      .then(function (body) {
        if (body && body.ref && body.ref.revision !== undefined && app.setRevision) {
          app.setRevision(body.ref.revision);
        }
        setStartDecisionStatus(
          "已发起：" + target.id + "（" + ((body && body.result) || "committed") + "）——接着让它跑一轮…",
          "ok"
        );
        if (app.refreshState) {
          app.refreshState(true);
        }
        // ★ 第 ② 步：真跑一轮（**异步**）。用**刚落盘的新 head** 当 expectedRevision——用旧 revision 必撞 409。
        var nextRevision =
          body && body.ref && body.ref.revision !== undefined ? body.ref.revision : revision;
        var nextBranch = (body && body.ref && body.ref.branch) || branch;
        return startAsyncRun(target, nextBranch, nextRevision, true);
      })
      .catch(function (e) {
        var retried = retryOnConflict(e, mayRetry, function (fresh) {
          setStartDecisionStatus("末端已移动（409），重取最新状态后重试…", "warn");
          return attemptStartDecision(target, branch, fresh, false);
        });
        if (retried) {
          return retried;
        }
        var reason = e && e.body && e.body.reason ? e.body.reason : (e && e.message) || String(e);
        setStartDecisionStatus("发起失败：" + reason, "err");
        return null;
      });
  }

  // ── 「让它跑一轮」（sd.RunDecision，2026-09-23）─────────────────────────
  //
  // ★★ **它补的是哪一处空白**：让某个决策人**真跑一轮**（真 LLM 自行读世界、出令）此前只有 GM 的 MCP 窄工具
  //   （sd.RunDecision）做得到 ⇒ 界面上**点不出来**。本入口把它接进工作台：写仍走**窄端点**
  //   POST /api/sd/run-decision（服务端写死命令类型，前端不传 type）。
  //
  // ★★ **2026-09-23 起服务端那条变成异步**（用户要的"状态标识 + 可展开进度窗"）：POST **立即返回**，
  //   这一轮在服务端后台跑；进度与结局由 GET …/run-status **轮询**取得。
  //   为什么必须异步：这一轮里决策人若出令（sd.IssueDirective 是敏感写），那次工具调用要**阻塞式**等审批
  //   （上限 = 壳的 APPROVAL_TIMEOUT）⇒ 让 HTTP 请求停在那里，界面除了"卡死"没有别的表现。
  //
  // ★★ **轨迹只显示当次那一份**（服务端明确取舍：轨迹是过程观测、不是世界事实 ⇒ 不落盘）。
  //   它现在同样只在**本次会话的轮询结果**里——刷新或换时间点后不再复现（读数里有一行明说这件事）。
  var runDecisionTarget = null;
  var lastRunGateStamp = null;
  var asyncRunTimer = null;
  var asyncRunTargetId = null;
  var asyncRunStatus = null;

  /** 起跑前"最新一条令"的 id（跑完用来判"这一轮到底有没有产出新的令"；见 drawLatestDecision）。 */
  var latestDirectiveIdBeforeRun = null;

  function setRunDecisionStatus(message, tone) {
    app.statusMessage(app.byId("decision-run-status"), message, tone);
  }

  function stopAsyncRunPolling() {
    if (asyncRunTimer) {
      clearInterval(asyncRunTimer);
      asyncRunTimer = null;
    }
  }

  function updateRunDecisionControl() {
    var button = app.byId("decision-run");
    var gate = runDecisionGate(runDecisionTarget);
    var targetId = runDecisionTarget ? String(runDecisionTarget.id) : "";
    if (button) {
      button.disabled = !gate.enabled;
      button.setAttribute("data-decision-target", targetId);
    }
    var stamp = targetId + "|" + gate.enabled + "|" + gate.reason;
    if (stamp !== lastRunGateStamp) {
      lastRunGateStamp = stamp;
      // ★ 正在跑的时候不让闸门文案把它盖掉（否则"已 Ns"会被门禁原因刷掉 ⇒ 看起来又像卡死）。
      if (!asyncRunTimer) {
        setRunDecisionStatus(gate.reason, gate.enabled ? "ok" : "muted");
      }
    }
  }

  function setRunDecisionTarget(maker) {
    runDecisionTarget = maker || null;
    updateRunDecisionControl();
  }

  function decideRunDecision() {
    var target = runDecisionTarget;
    if (!target || !runDecisionGate(target).enabled) {
      setRunDecisionStatus("未选中可跑的决策人", "warn");
      return;
    }
    var at = app.target() || {};
    startAsyncRun(target, at.branch, at.revision, true);
  }

  /**
   * **起跑一轮**（异步）：POST 窄端点（**立即返回**）⇒ 成功就开始轮询 run-status；409 ⇒ 重取 head 后自动重试一次。
   *
   * <p>★ 两个入口（「开始决策」的第 ② 步、「让它跑一轮」）共用它：**同一份**起跑逻辑，免得两处各写一遍
   * 而其中一处漏掉 409/轮询。
   */
  function startAsyncRun(target, branch, revision, mayRetry) {
    clearRunTrace();
    stopAsyncRunPolling();
    asyncRunTargetId = String(target.id);
    asyncRunStatus = null;
    // ★ 记下"起跑前的最新一条令"：跑完才能说清"这一轮**有没有**产出新的令"——
    //   若这一轮没出令而界面照样渲染一条旧的，用户会把它读成"这一轮的决定"（**看起来完全正常**的谎）。
    latestDirectiveIdBeforeRun =
      loadedDirectives && loadedDirectives.length
        ? directiveFields(loadedDirectives[0]).directiveId
        : null;
    setRunDecisionStatus("正在跑一轮：" + asyncRunTargetId + "（已 0s）——已发起，等第一个进度…", "muted");
    return api
      .runDecision(branch, revision, target.id)
      .then(function (body) {
        if (body && body.ref && body.ref.revision !== undefined && app.setRevision) {
          app.setRevision(body.ref.revision);
        }
        if (body && body.running === false) {
          // ★ 服务端未接入运行流（只落了触发事实）⇒ 如实说，**不进入轮询**（轮询会一直读到"没有记录"）。
          setRunDecisionStatus(
            "触发已落盘，但这一轮没有跑：" + valueOrDash(body.note || "运行流未接入"),
            "warn"
          );
          return body;
        }
        startAsyncRunPolling(asyncRunTargetId);
        if (app.refreshState) {
          app.refreshState(true);
        }
        return body;
      })
      .catch(function (e) {
        var retried = retryOnConflict(e, mayRetry, function (fresh) {
          setRunDecisionStatus("末端已移动（409），重取最新状态后重试…", "warn");
          return startAsyncRun(target, branch, fresh, false);
        });
        if (retried) {
          return retried;
        }
        stopAsyncRunPolling();
        var reason = e && e.body && e.body.reason ? e.body.reason : (e && e.message) || String(e);
        setRunDecisionStatus("跑一轮失败：" + reason, "err");
        return null;
      });
  }

  /** 开始轮询（每 1s 一次；**立刻**先取一次，不等第一个间隔）。 */
  function startAsyncRunPolling(id) {
    stopAsyncRunPolling();
    asyncRunTargetId = id;
    asyncRunTimer = setInterval(function () {
      pollAsyncRun(id);
    }, 1000);
    pollAsyncRun(id);
  }

  /** 取一次读数：刷进度窗与状态行；跑完 ⇒ 停轮询、收尾（渲染轨迹 + 就地渲染决策）。 */
  function pollAsyncRun(id) {
    if (typeof api.runDecisionStatus !== "function") {
      stopAsyncRunPolling();
      setRunDecisionStatus("本页的 api.js 没有 run-status —— 无法知道这一轮跑到哪儿了", "warn");
      return;
    }
    api
      .runDecisionStatus(id, app.target())
      .then(function (status) {
        if (asyncRunTargetId !== id) {
          return; // 目标已换 ⇒ 这次读数作废（不拿甲的状态去画乙）
        }
        asyncRunStatus = status || null;
        renderRunProgress(id, status);
        var known = status && status.startedAt !== null && status.startedAt !== undefined;
        if (!known) {
          stopAsyncRunPolling();
          setRunDecisionStatus(
            "服务端没有 " + id + " 这一轮的记录（可能刚重启过）——这一轮的结果无从得知。",
            "warn"
          );
          return;
        }
        if (status.done === true) {
          stopAsyncRunPolling();
          finishAsyncRun(id, status);
          return;
        }
        setRunDecisionStatus(runningText(id, status), "muted");
      })
      .catch(function (e) {
        if (asyncRunTargetId !== id) {
          return;
        }
        stopAsyncRunPolling();
        renderRunProgress(id, null);
        setRunDecisionStatus("读取运行状态失败：" + ((e && e.message) || e), "err");
      });
  }

  /** 收尾：状态行说清结局、轨迹按既有渲染画一份、**就地**渲染该决策人最近一次决策。 */
  function finishAsyncRun(id, status) {
    setRunDecisionStatus(runOutcomeText(status), runOutcomeTone(status));
    renderRunTrace(runTraceFromStatus(id, status));
    loadLatestDecision(id);
    if (app.refreshState) {
      app.refreshState(true);
    }
    // ★ 真出过令 ⇒ 决策记录多了一条，当场重取（否则用户得自己刷新才看得见）。
    loadDecisionDirectives([id]);
  }

  // ── LLM 运行情况（可展开，准实时）────────────────────────────────────
  //
  // ★★ **进度口径 = 服务端的两个真实观察点**（每轮 LLM 结束 / 每次工具调用结束），**不是逐字流式**（用户已选
  //   "先做准实时进度"）。故这里画的每一条都对应服务端真的发生过的一件事，没有"心跳"这种编出来的行。

  var progressExpanded = false;

  function setProgressExpanded(open) {
    progressExpanded = !!open;
    var body = app.byId("decision-progress");
    var button = app.byId("decision-progress-toggle");
    if (body) {
      body.hidden = !progressExpanded;
    }
    if (button) {
      if (button.setAttribute) {
        button.setAttribute("aria-expanded", progressExpanded ? "true" : "false");
      }
      button.textContent = progressExpanded ? "收起 LLM 运行情况" : "展开 LLM 运行情况";
    }
    renderRunProgress(asyncRunTargetId, asyncRunStatus);
  }

  /** 画进度窗（未展开时也画——展开的那一刻即刻可见，不必等下一次轮询）。 */
  function renderRunProgress(id, status) {
    var mount = app.byId("decision-progress");
    if (!mount) {
      return;
    }
    var plan = runProgressLines(id || "（未选中）", status);
    app.clear(mount);
    plan.lines.forEach(function (line) {
      mount.appendChild(
        app.el("div", { class: "progress-line" + (plan.empty ? " empty" : ""), text: line })
      );
    });
  }

  // ── 本次运行后的决策（**就地**渲染，没有"看结果"按钮）────────────────────
  //
  // ★★ 用户 2026-09-23：「跑完在下面生成**当前 tick** 这个决策人的完整决策（决心/理由/命令清单）；
  //   **左边没有额外的生成结果按钮**」⇒ 结果直接画在这条路径的下方，不另设入口。
  // ★ 取的是 `GET /api/sd/directives?decisionMakerId=<id>` 的**最新一条**（服务端按 tick 降序、同 tick 按 id 字典序）。
  //   跑一轮若真出了令，那一条就是它这一轮落的（模型的 tick 现在被校验为**不得记在未来**）。
  function loadLatestDecision(makerId) {
    var mount = app.byId("decision-latest");
    if (!mount) {
      return;
    }
    if (typeof api.directives !== "function") {
      app.clear(mount);
      mount.appendChild(app.el("p", { class: "empty", text: "决策记录端点未接入（本页的 api.js 里没有 directives）" }));
      return;
    }
    app.clear(mount);
    mount.appendChild(app.el("p", { class: "empty", text: "载入本次运行后的决策…" }));
    api
      .directives(makerId, app.target())
      .then(function (body) {
        drawLatestDecision(mount, ((body && body.directives) || [])[0]);
      })
      .catch(function (e) {
        app.clear(mount);
        mount.appendChild(
          app.el("p", { class: "empty", text: "决策载入失败：" + ((e && e.message) || e) })
        );
      });
  }

  /** 画"最新一条决策"的全文（决心 / 理由 / 命令清单）；没有 ⇒ 明说"这一轮没有产出决策"。 */
  function drawLatestDecision(mount, directive) {
    app.clear(mount);
    if (!directive) {
      mount.appendChild(
        app.el("p", {
          class: "empty",
          text: "这个决策人还没有出过令（这一轮它可能没决定要做什么）——已出过的令见下方「决策记录」。",
        })
      );
      return;
    }
    var fields = directiveFields(directive);
    var box = app.el("div", { class: "latest-decision" });
    box.setAttribute("data-latest-directive-id", fields.directiveId);
    box.appendChild(
      app.el("div", {
        class: "latest-head",
        text: "最近一次决策 · tick " + fields.tick + " · " + fields.directiveId + " · " + fields.status,
      })
    );
    if (latestDirectiveIdBeforeRun !== null && fields.directiveId === latestDirectiveIdBeforeRun) {
      // ★★ **这一轮没产出新的令**：绝不能把旧的那条摆在这里假装是它的产出（用户要的是"本次运行的结果"）。
      box.appendChild(
        app.el("p", {
          class: "status warn latest-stale",
          text:
            "★ 这一轮**没有产出新的令**（服务端报的轨迹见上）——下面是它最近一次决策，不是这一轮的产出。",
        })
      );
    }
    var dl = app.el("dl", { class: "kv latest-detail" });
    appendRow(dl, "决心 / 理由（intentInfo）", fields.intentInfo);
    appendRow(dl, "目标", fields.target);
    appendRow(dl, "判决", fields.verdict);
    appendRow(dl, "效果", fields.effects);
    appendRow(dl, "命令清单（" + fields.commands.length + "）", fields.commands.length ? "" : "（无）");
    fields.commands.forEach(function (command, index) {
      appendRow(dl, "  " + (index + 1) + ". " + command.type, command.payloadJson);
    });
    box.appendChild(dl);
    mount.appendChild(box);
  }

  // ── 跟决策人说一句（文本框，2026-09-23）──────────────────────────────
  //
  // ★★ 用户原话：「还要做一个文本框在开始决策下面，允许**不管是第一轮还是最后一轮**，都可以额外和决策人进行对话」
  //   ⇒ 内容作为**一条 user 消息**追加进它的会话（服务端 `POST …/say`），下一轮它就看得见。
  // ★ 第一轮之前也能用：服务端会**先补身份消息**再落这句（顺序恒为 system → user），否则模型会收到一段没有
  //   system 的上下文（那样它不知道自己是谁——与现场那次 HTTP 400 同一族的病）。
  function setSayStatus(message, tone) {
    app.statusMessage(app.byId("decision-say-status"), message, tone);
  }

  /** 文本框里的补充指示（**两端空白裁掉**；无文本框 / 空 ⇒ 空串）。合并后的「开始决策」据此判走哪条情形。 */
  function decisionSayText() {
    var node = app.byId("decision-say-text");
    return node && node.value !== undefined && node.value !== null ? String(node.value).trim() : "";
  }

  /** 清空文本框（那句话真的送出去之后才清）。 */
  function clearSayText() {
    var node = app.byId("decision-say-text");
    if (node) {
      node.value = "";
    }
  }

  /**
   * 把文本框里的那句话发出去（**唯一实现**）：内容作为**一条 user 消息**进该决策人的会话。
   *
   * <p>★ 返回值是 `{ok, body}` / `{ok:false, reason}` 而不是"只写状态行"：合并后的「开始决策」要按
   * "这一句到底发出去没有"决定**要不要接着发起**（见 {@link decideStartDecision}）——只写状态行的话，
   * 调用方只能靠读 DOM 判，那是把判定建在渲染上。
   * ★ 与 `SimosPanels.say()`（e2e/调试入口）共用它，免得两处各写一份而其中一处漏掉空文本守卫。
   */
  function sendDecisionSay() {
    var target = startDecisionTarget;
    if (!target || target.id === null || target.id === undefined || target.id === "") {
      setSayStatus("未选中决策人：先点选一个国家区域或有决策人的单位。", "warn");
      return Promise.resolve({ ok: false, reason: "未选中决策人" });
    }
    var text = decisionSayText();
    if (!text) {
      setSayStatus("文本框是空的（没有补充指示要发）。", "warn");
      return Promise.resolve({ ok: false, reason: "文本框是空的" });
    }
    if (typeof api.sayToDecisionMaker !== "function") {
      setSayStatus("本页的 api.js 没有 sayToDecisionMaker（端点未接入）", "warn");
      return Promise.resolve({ ok: false, reason: "端点未接入" });
    }
    var at = app.target() || {};
    setSayStatus("发送中…", "muted");
    return api
      .sayToDecisionMaker(target.id, text, at.branch, at.revision)
      .then(function (body) {
        clearSayText();
        setSayStatus(
          "已发送（落进会话 " +
            valueOrDash(body && body.conversationId) +
            "，共 " +
            valueOrDash(body && body.length) +
            " 字）——下一轮它就看得见。",
          "ok"
        );
        return { ok: true, body: body };
      })
      .catch(function (e) {
        var reason = e && e.body && e.body.error ? e.body.error : (e && e.message) || String(e);
        setSayStatus("发送失败：" + reason, "err");
        return { ok: false, reason: reason };
      });
  }

  // ── 上下文重置（2026-09-23）────────────────────────────────────────
  //
  // ★★ 用户原话：「重新决策（已有决策的情况下开始决策），llm 的上下文是**沿用**而不是重置的；
  //   **只有点额外的上下文重置按键才重置**」⇒ 重跑路径**绝不**碰会话；清空只在这一个按钮上。
  // ★ 走**既有**的 `sd.ResetDecisionMakerConversation`（真命令、落 revision）：会话世代 +1 ⇒ 下一轮落到另一段
  //   新会话、从空上下文开始（首轮会重新注入身份消息）；**旧会话一条字节都不动**（可审计）。
  function setContextStatus(message, tone) {
    app.statusMessage(app.byId("decision-context-status"), message, tone);
  }

  function decideResetDecisionContext() {
    var target = startDecisionTarget;
    if (!target || target.id === null || target.id === undefined || target.id === "") {
      setContextStatus("未选中决策人：先点选一个国家区域或有决策人的单位。", "warn");
      return;
    }
    var at = app.target() || {};
    setContextStatus("正在重置 " + target.id + " 的会话上下文…", "muted");
    attemptResetDecisionContext(target, at.branch, at.revision, true);
  }

  function attemptResetDecisionContext(target, branch, revision, mayRetry) {
    return api
      .resetDecisionConversation(branch, revision, target.id)
      .then(function (body) {
        if (body && body.ref && body.ref.revision !== undefined && app.setRevision) {
          app.setRevision(body.ref.revision);
        }
        setContextStatus(
          "已重置：" + target.id + " 的下一轮从**空上下文**开始（旧会话不删）。左栏「会话世代 / 会话 id」会随后刷新。",
          "ok"
        );
        // ★ 左栏详情里的"会话世代 / 会话 id"是**世界事实** ⇒ 必须重取（不重取就会显示上一段的 id）。
        decisionLeftKey = null;
        renderDecisionLeft(app.getState());
        if (app.refreshState) {
          app.refreshState(true);
        }
        return body;
      })
      .catch(function (e) {
        var retried = retryOnConflict(e, mayRetry, function (fresh) {
          setContextStatus("末端已移动（409），重取最新状态后重试…", "warn");
          return attemptResetDecisionContext(target, branch, fresh, false);
        });
        if (retried) {
          return retried;
        }
        var reason = e && e.body && e.body.reason ? e.body.reason : (e && e.message) || String(e);
        setContextStatus("重置失败：" + reason, "err");
        return null;
      });
  }

  function clearRunTrace() {
    var mount = app.byId("decision-run-trace");
    if (mount) {
      app.clear(mount);
    }
  }

  /** 画本轮轨迹（`toolCalls` 逐个 + 收尾文本 + llmCalls）——**只画当次返回的那一份**。 */
  function renderRunTrace(body) {
    var mount = app.byId("decision-run-trace");
    if (!mount) {
      return;
    }
    var fields = runTraceFields(body);
    app.clear(mount);
    var head = app.el("div", { class: "run-trace-head" });
    head.setAttribute("data-decision-maker-id", fields.decisionMakerId);
    head.appendChild(
      app.el("span", { class: "run-trace-title", text: "本轮轨迹（决策人 " + fields.decisionMakerId + "）" })
    );
    head.appendChild(
      app.el("span", {
        class: "run-trace-meta",
        text:
          "llmCalls=" +
          fields.llmCalls +
          " · 落 " +
          fields.revision +
          " · 会话 " +
          fields.conversationId +
          (fields.abortedByBudget ? " · 因预算中止" : ""),
      })
    );
    mount.appendChild(head);
    // ★★ **"没跑成"与"跑了但没调工具"必须分得开**（都是空轨迹，含义相反）：前者有 reason（服务端如实报的
    //   失败码），后者才是真的空跑。折成同一句话会让"未绑定 provider"看起来像"跑得很顺"。
    if (fields.reason) {
      mount.appendChild(
        app.el("p", {
          class: "empty run-trace-failed",
          text: "这一轮没跑成：" + fields.reason + " —— " + fields.detail + "（没有轨迹可看）",
        })
      );
      appendRunTraceNote(mount);
      return;
    }
    if (!fields.toolCalls.length) {
      mount.appendChild(app.el("p", { class: "empty", text: "这一轮没有调用任何工具。" }));
    }
    fields.toolCalls.forEach(function (call, index) {
      var row = app.el("div", { class: "run-trace-call" + (call.ok ? " ok" : " failed") });
      row.setAttribute("data-tool", call.tool);
      row.appendChild(
        app.el("span", { class: "run-trace-tool", text: index + 1 + ". " + call.tool })
      );
      row.appendChild(
        app.el("span", {
          class: "run-trace-ok",
          text: call.ok ? "OK" : "失败" + (call.code === "—" ? "" : "（" + call.code + "）"),
        })
      );
      row.appendChild(app.el("pre", { class: "run-trace-summary", text: call.summary }));
      mount.appendChild(row);
    });
    mount.appendChild(app.el("pre", { class: "run-trace-final", text: fields.finalText }));
    appendRunTraceNote(mount);
  }

  /** 读数里明说这件取舍：轨迹**不落盘** ⇒ 换 target / 刷新之后它就不在了（成功/失败两条路都带上）。 */
  function appendRunTraceNote(mount) {
    mount.appendChild(
      app.el("p", {
        class: "status muted run-trace-note",
        text: "轨迹只在这一次响应里（不落盘、不进 revision）——刷新或换时间点后不再复现；决策本身落的令见下方「决策记录」。",
      })
    );
  }

  // ── 决策记录（GET /api/sd/directives，2026-09-23）──────────────────────

  var decisionDirectivesToken = 0;
  var expandedDirectiveId = null;

  /** 点一条 ⇒ 展开/收起它的详情（决心/理由/命令清单）。 */
  function toggleDirective(id) {
    expandedDirectiveId = expandedDirectiveId === id ? null : id;
    drawDirectiveList();
  }

  var loadedDirectives = [];

  function drawDirectiveList() {
    var mount = app.byId("decision-directives");
    if (!mount) {
      return;
    }
    var directives = loadedDirectives || [];
    app.clear(mount);
    if (!directives.length) {
      mount.appendChild(app.el("p", { class: "empty", text: "这个决策人还没有出过令。" }));
      return;
    }
    var list = app.el("div", { class: "directive-list" });
    directives.forEach(function (directive) {
      var fields = directiveFields(directive);
      var item = app.el("div", { class: "directive-item" });
      item.setAttribute("data-directive-id", fields.directiveId);
      var head = app.el("button", { type: "button", class: "directive-head" });
      head.setAttribute("data-directive-tick", fields.tick);
      head.appendChild(app.el("span", { class: "directive-id", text: fields.directiveId }));
      head.appendChild(
        app.el("span", { class: "directive-headline", text: directiveHeadline(directive) })
      );
      head.addEventListener("click", function () {
        toggleDirective(fields.directiveId);
      });
      item.appendChild(head);
      if (expandedDirectiveId === fields.directiveId) {
        var detail = app.el("dl", { class: "kv directive-detail" });
        appendRow(detail, "决心 / 理由（intentInfo）", fields.intentInfo);
        appendRow(detail, "意图 INFO key", fields.intentInfoKey);
        appendRow(detail, "目标", fields.target);
        appendRow(detail, "状态", fields.status);
        appendRow(detail, "判决", fields.verdict);
        appendRow(detail, "效果", fields.effects);
        appendRow(
          detail,
          "命令清单（" + fields.commands.length + "）",
          fields.commands.length ? "" : "（无）"
        );
        fields.commands.forEach(function (command, index) {
          appendRow(detail, "  " + (index + 1) + ". " + command.type, command.payloadJson);
        });
        item.appendChild(detail);
      }
      list.appendChild(item);
    });
    mount.appendChild(list);
  }

  /**
   * 载入这些决策人的决策记录（0 个 ⇒ 提示；1 个 ⇒ 拉它的；多个 ⇒ **不猜哪一个**，让用户从右栏点选）。
   *
   * <p>★ 未知 id ⇒ 服务端 404（**不是空列表**）：那说明这个快照里没有这个人 —— 如实显示，不折成"还没出过令"。
   */
  function loadDecisionDirectives(makerIds) {
    var mount = app.byId("decision-directives");
    if (!mount) {
      return;
    }
    var ids = Array.isArray(makerIds) ? makerIds.filter(Boolean) : [];
    var token = ++decisionDirectivesToken;
    if (ids.length !== 1) {
      loadedDirectives = [];
      expandedDirectiveId = null;
      app.clear(mount);
      mount.appendChild(
        app.el("p", {
          class: "empty",
          text: ids.length
            ? "本格有 " + ids.length + " 个决策人；从右栏点选一个查看它的决策记录。"
            : "选中决策人后可看它的决策记录（出过的令：决心 / 理由 / 命令清单）。",
        })
      );
      return;
    }
    var makerId = String(ids[0]);
    loadedDirectives = [];
    expandedDirectiveId = null;
    app.clear(mount);
    // ★ **api 口不在就什么都不做**（与上面 SimosMap 那几处同口径的存在性判）：本页可能被旧版/嵌入方的
    //   api.js 驱动，那时光是"没有这个端点"，不是"这个人没有决策记录"——两者必须可区分（后者才显示空态）。
    if (typeof api.directives !== "function") {
      mount.appendChild(
        app.el("p", { class: "empty", text: "决策记录端点未接入（本页的 api.js 里没有 directives）" })
      );
      return;
    }
    mount.appendChild(app.el("p", { class: "empty", text: "载入决策记录 " + makerId + "…" }));
    api
      .directives(makerId, app.target())
      .then(function (body) {
        if (token !== decisionDirectivesToken) {
          return;
        }
        loadedDirectives = (body && body.directives) || [];
        drawDirectiveList();
      })
      .catch(function (e) {
        if (token !== decisionDirectivesToken) {
          return;
        }
        loadedDirectives = [];
        app.clear(mount);
        mount.appendChild(app.el("p", { class: "empty", text: "决策记录载入失败：" + e.message }));
      });
  }

  /** 把一个决策人的详情写进容器（`data-decision-maker-id` 供 e2e/断言锚定）。 */
  function appendDecisionMakerDetail(container, maker, note) {
    var fields = decisionMakerFields(maker);
    var dl = app.el("dl", { class: "kv decision-maker" });
    dl.setAttribute("data-decision-maker-id", String(fields.id));
    appendRow(dl, "id", fields.id);
    appendRow(dl, "归属", fields.affiliation);
    appendRow(dl, "cadence（decisionCadenceTicks）", fields.cadence);
    appendRow(dl, "allowedTools", fields.allowedTools);
    if (fields.accessLimit) {
      appendRow(dl, "accessLimit.prefixesByNamespace", fields.accessLimit.prefixesByNamespace);
      appendRow(dl, "accessLimit.adjudicationDisclosure", fields.accessLimit.adjudicationDisclosure);
      appendRow(dl, "accessLimit.redactedFields", fields.accessLimit.redactedFields);
    } else {
      appendRow(dl, "accessLimit", "—");
    }
    appendRow(dl, "会话世代", fields.conversationGeneration);
    appendRow(dl, "会话 id", fields.conversationId);
    appendRow(dl, "绑定的 provider", maker && maker.providerId ? maker.providerId : "（未绑定）");
    appendRow(dl, "待决状态", fields.pending);
    // ★ 2026-09-23：这一行的**内容**（决心/理由/命令清单）在下方「决策记录」里——本行只报"最近一次在第几 tick"。
    appendRow(dl, "最近一次出令的 tick", fields.lastDirectiveTick);
    appendRow(dl, "距上次出令（tick）", fields.ticksSinceLast);
    // ★ 这一行由 map.js 回填（它才是拉 `/scope` 的那个文件）：内容 = "可见 N 格 / M 区域（国家级|军队级）"。
    appendLiveRow(dl, "可见范围（现算）", DECISION_SCOPE_SUMMARY_ID, "载入中…");
    if (note) {
      appendRow(dl, "来源", note);
    }
    container.appendChild(dl);
  }

  /**
   * 无决策人时的显式文案（C14：不静默空白）。
   *
   * <p>★ 第2波 B11（用户 2026-09-23 实测「我都不知道点了会发生什么」）：除了"没有决策人"，
   * 还要**明说接下来会怎样**（`hint`，可选）——例如"「开始决策」对它不可用、本版不会自动创建决策人"。
   * `reason` 仍是**第一个直接子节点**（既有断言 `texts.includes("无决策人")` 逐字钉着它）。
   */
  function appendNoDecisionMaker(container, reason, hint) {
    container.appendChild(app.el("p", { class: "empty decision-empty", text: reason }));
    if (hint) {
      container.appendChild(app.el("p", { class: "decision-empty-hint muted", text: hint }));
    }
  }

  function renderDecisionMakers(container, makers, note) {
    if (!makers.length) {
      appendNoDecisionMaker(container, "无决策人");
      return;
    }
    makers.forEach(function (maker) {
      appendDecisionMakerDetail(container, maker, note);
    });
  }

  /** 决策模式左栏：焦点决策人 > 地图选中的 hex / 单位；都没有 ⇒ 提示。 */
  function renderDecisionLeft(state) {
    if (!state || state.mode !== "decision") {
      return;
    }
    var status = app.byId("decision-view-status");
    var container = app.byId("decision-maker-detail");
    if (!status || !container) {
      return;
    }
    var focus = state.decisionMakerFocus || null;
    var selection = state.selection || null;
    var key = JSON.stringify([focus, selection, app.target()]);
    if (key === decisionLeftKey) {
      return;
    }
    decisionLeftKey = key;
    var token = ++decisionLeftToken;
    app.clear(container);
    // ★ 第2波 B11：右栏的"选中态说明"每轮先清掉，只有"选中项确实没有决策人"的分支才重新写。
    setDecisionSelectionNote(null);

    if (focus) {
      setDecisionViewStatus("查询决策人 " + focus + "…", "muted");
      api
        .decisionMaker(focus, app.target())
        .then(function (maker) {
          if (token !== decisionLeftToken) {
            return;
          }
          app.clear(container);
          appendDecisionMakerDetail(container, maker, "右栏列表");
          // ★ 重画把「可见范围」那一行重置成了占位文本 ⇒ 让 map.js 把最近算好的摘要写回来
          //   （两处写同一个值，谁后落地都收敛）。
          if (window.SimosMap && window.SimosMap.republishDecisionScopeSummary) {
            window.SimosMap.republishDecisionScopeSummary();
          }
          setStartDecisionTarget(maker);
          loadDecisionDirectives([maker.id]);
          setDecisionViewStatus("决策人 " + maker.id + " · " + targetLabel(), "ok");
        })
        .catch(function (e) {
          if (token !== decisionLeftToken) {
            return;
          }
          setStartDecisionTarget(null);
          loadDecisionDirectives([]);
          setDecisionViewStatus("决策人查询失败：" + e.message, "err");
        });
      return;
    }

    if (!selection) {
      setStartDecisionTarget(null);
      loadDecisionDirectives([]);
      setDecisionViewStatus("点选地图上的国家区域或有决策人的单位，查看其决策人信息。", "muted");
      return;
    }

    if (selection.kind === "hex") {
      setDecisionViewStatus("查询 " + hexLabel(selection) + " 的国家决策人…", "muted");
      Promise.all([
        api.mapHex(selection.q, selection.r, app.target()),
        loadOverview().catch(function () {
          return { regions: [] };
        }),
        api.cachedDecisionMakers(app.target()).catch(function () {
          return { decisionMakers: [] };
        }),
      ]).then(function (results) {
        if (token !== decisionLeftToken) {
          return;
        }
        var hex = results[0];
        var regions = results[1].regions || [];
        var makers = results[2].decisionMakers || [];
        var regionIds = Array.isArray(hex.regions) ? hex.regions : [];
        var nationIds =
          window.SimosMap && window.SimosMap.nationIdsOfRegions
            ? window.SimosMap.nationIdsOfRegions(regions, regionIds)
            : [];
        app.clear(container);
        if (!nationIds.length) {
          appendNoDecisionMaker(
            container,
            "该格不属于任何国家区域（无国家决策人）",
            "该格不在任何国家区域内 ⇒ 没有国家决策人可发起决策。请点选属于某国的格，或改选一个单位。"
          );
          setStartDecisionTarget(null);
          loadDecisionDirectives([]);
          setDecisionViewStatus(hexLabel(hex) + " · 无国家区域", "muted");
          setDecisionSelectionNote(
            "选中 " + hexLabel(hex) + "：它不属于任何国家区域 —— 没有国家决策人可发起决策。"
          );
          return;
        }
        var all = [];
        nationIds.forEach(function (nationId) {
          all = all.concat(decisionMakersForNation(makers, nationId));
        });
        if (!all.length) {
          // ★ 第2波 B11：国家区域在、但没有决策人 ⇒ 同样把"点了会怎样"说清（这里也不自动建）。
          appendNoDecisionMaker(
            container,
            "无决策人",
            "该格所属的国家区域（" +
              nationIds.join("、") +
              "）没有决策人 ⇒ 不能发起决策。本版不会自动创建决策人；请先为该国家配置决策人。"
          );
          setStartDecisionTarget(null);
          loadDecisionDirectives([]);
          setDecisionViewStatus(hexLabel(hex) + " · 无决策人（不能发起决策）", "warn");
          setDecisionSelectionNote(
            "选中 " +
              hexLabel(hex) +
              "：所属国家区域（" +
              nationIds.join("、") +
              "）没有决策人 —— 不能发起决策。"
          );
          return;
        }
        renderDecisionMakers(container, all, "国家区域 " + nationIds.join("、"));
        // 多个国家决策人 ⇒ 无单一发起目标（不猜"哪一个"）。
        setStartDecisionTarget(all.length === 1 ? all[0] : null);
        loadDecisionDirectives(
          all.map(function (maker) {
            return maker.id;
          })
        );
        setDecisionViewStatus(hexLabel(hex) + " · " + targetLabel(), "ok");
      });
      return;
    }

    if (selection.kind === "unit") {
      setDecisionViewStatus("查询单位 " + selection.id + " 的决策人…", "muted");
      Promise.all([
        api.cachedUnits(app.target()).catch(function () {
          return { units: [] };
        }),
        api.cachedDecisionMakers(app.target()).catch(function () {
          return { decisionMakers: [] };
        }),
      ]).then(function (results) {
        if (token !== decisionLeftToken) {
          return;
        }
        var units = results[0].units || [];
        var makers = results[1].decisionMakers || [];
        var maker = decisionMakerForUnit(makers, units, selection.id);
        app.clear(container);
        if (!maker) {
          // ★ 第2波 B11（用户 2026-09-23 实测「我都不知道点了会发生什么 / 右边栏也没有对应显示」）：
          //   把"点了会怎样 + 下一步"说清楚；**不做**自动创建决策人（那是一次隐式写操作，与"没明说就不许写"冲突）。
          appendNoDecisionMaker(
            container,
            "无决策人",
            "该单位没有决策人 ⇒ 不能对它发起决策：左栏「开始决策」会保持禁用，点它不会有任何反应。" +
              "本版不会自动创建决策人（那是一次隐式写）。要发起决策，请改选一个有决策人的单位，或点选国家区域。"
          );
          setStartDecisionTarget(null);
          loadDecisionDirectives([]);
          setDecisionViewStatus("单位 " + selection.id + " · 无决策人（不能对它发起决策）", "warn");
          setDecisionSelectionNote(
            "选中单位 " +
              selection.id +
              "：没有决策人 —— 不能对它发起决策（左栏「开始决策」保持禁用）。本版不会自动创建决策人。"
          );
          return;
        }
        appendDecisionMakerDetail(container, maker, "单位 " + selection.id);
        setStartDecisionTarget(maker);
        loadDecisionDirectives([maker.id]);
        setDecisionViewStatus("单位 " + selection.id + " · " + targetLabel(), "ok");
      });
      return;
    }

    setStartDecisionTarget(null);
    setDecisionViewStatus("未知选择类型：" + app.text(selection.kind), "warn");
  }

  // ── 决策模式右栏：分类列表（子页 A）+ 审批（子页 B）────────────────────

  var decisionRightToken = 0;
  var decisionRightKey = null;
  var decisionRightMakers = null;
  var decisionApprovalToken = 0;
  var decisionApprovalKey = null;
  var lastDecisionSubpage = null;

  /**
   * 右栏列表点一个决策人 ⇒ 聚焦它，**并清掉区域高亮**（可见范围的高亮改由 map.js 按 `/scope` 现算画）。
   *
   * <p>★★ **旧实现是第二份真相**：国家决策人按 `affiliation.id` 在**前端**从 overview 重算"该 tag 的全部区域"
   * 并整片高亮；军队决策人则只选中根单位、**什么都不画**。两者都不随 GM 的 `accessLimit` 变——GM 把某人的可见范围
   * 收窄到一个区域之后，界面照样画整片国土：**看起来对、其实是假的**（且不会报错）。
   *
   * <p>★ 军队级不再改选中单位（`setSelection` 会清掉 focus，两者互斥）：聚焦决策人后，它的根单位由左栏
   * `affiliation.rootUnit` 显示、位置由可见范围高亮覆盖——即"它看得见哪一圈"正是用户要看的东西。
   */
  function selectDecisionMakerFromList(maker) {
    app.setHighlightRegions([]);
    app.setDecisionMakerFocus(maker.id);
  }

  function drawDecisionList(state) {
    var mount = app.byId("decision-maker-list-mount");
    if (!mount) {
      return;
    }
    app.clear(mount);
    var makers = decisionRightMakers || [];
    if (!makers.length) {
      mount.appendChild(app.el("p", { class: "empty", text: "该快照没有决策人。" }));
      return;
    }
    var groups = decisionMakerGroups(makers);
    var focus = state.decisionMakerFocus || null;
    groups.forEach(function (group) {
      var section = app.el("div", { class: "decision-group" });
      section.setAttribute("data-affiliation-kind", group.kind);
      var header = app.el("div", { class: "decision-group-head" });
      header.appendChild(app.el("span", { class: "decision-group-name", text: group.label }));
      header.appendChild(app.el("span", { class: "decision-group-count", text: String(group.makers.length) }));
      section.appendChild(header);
      var list = app.el("div", { class: "decision-list" });
      group.makers.forEach(function (maker) {
        var item = app.el("button", {
          type: "button",
          class: "decision-maker-item" + (maker.id === focus ? " selected" : ""),
        });
        item.setAttribute("data-decision-maker-id", String(maker.id));
        item.setAttribute("data-affiliation-kind", group.kind);
        item.appendChild(
          app.el("span", {
            class: "decision-maker-name",
            text: app.text(maker.affiliation ? maker.affiliation.displayName : maker.id),
          })
        );
        item.appendChild(app.el("span", { class: "decision-maker-id", text: String(maker.id) }));
        item.appendChild(
          app.el("span", { class: "decision-maker-pending", text: pendingStatusText(maker.due) })
        );
        item.addEventListener("click", function () {
          selectDecisionMakerFromList(maker);
        });
        list.appendChild(item);
      });
      section.appendChild(list);
      mount.appendChild(section);
    });
  }

  /** 右栏子页 A：全部决策人按类型分类排列（C15）。 */
  function renderDecisionRight(state) {
    var mount = app.byId("decision-maker-list-mount");
    if (!mount) {
      return;
    }
    var key = targetLabel();
    if (key === decisionRightKey) {
      if (decisionRightMakers !== null) {
        drawDecisionList(state);
      }
      return;
    }
    decisionRightKey = key;
    var token = ++decisionRightToken;
    app.clear(mount);
    mount.appendChild(app.el("p", { class: "empty", text: "载入决策人…（" + key + "）" }));
    Promise.all([
      api.cachedDecisionMakers(app.target()),
      loadOverview().catch(function () {
        return { regions: [] };
      }),
    ])
      .then(function (results) {
        if (token !== decisionRightToken) {
          return;
        }
        decisionRightMakers = results[0].decisionMakers || [];
        drawDecisionList(state);
      })
      .catch(function (e) {
        if (token !== decisionRightToken) {
          return;
        }
        decisionRightMakers = null;
        app.clear(mount);
        mount.appendChild(app.el("p", { class: "empty", text: "决策人载入失败：" + e.message }));
      });
  }

  function setDecisionApprovalStatus(message, tone) {
    app.statusMessage(app.byId("decision-approval-status"), message, tone);
  }

  function drawApprovalList(pending) {
    var mount = app.byId("decision-approval-mount");
    if (!mount) {
      return;
    }
    app.clear(mount);
    if (!pending.length) {
      mount.appendChild(app.el("p", { class: "empty", text: "无待批。" }));
      return;
    }
    pending.forEach(function (item) {
      var id = item && item.id !== null && item.id !== undefined ? String(item.id) : "";
      var row = app.el("div", { class: "approval-item" });
      row.setAttribute("data-approval-id", id);
      row.appendChild(app.el("div", { class: "approval-tool", text: app.text(item ? item.tool : null) }));
      row.appendChild(
        app.el("div", { class: "approval-summary", text: app.text(item ? item.summary : null) })
      );
      var actions = app.el("div", { class: "approval-actions" });
      [["approve", "批准"], ["deny", "驳回"]].forEach(function (pair) {
        var button = app.el("button", { type: "button", class: "approval-" + pair[0], text: pair[1] });
        button.setAttribute("data-decision", pair[0]);
        button.disabled = !id;
        button.addEventListener("click", function () {
          decideApproval(id, pair[0]);
        });
        actions.appendChild(button);
      });
      row.appendChild(actions);
      mount.appendChild(row);
    });
  }

  /** 审批裁决：打 POST /api/approvals/{id}（非命令写），成功后重取待批列表。 */
  function decideApproval(id, decision) {
    if (!id) {
      return;
    }
    setDecisionApprovalStatus("提交「" + decision + "」…", "muted");
    api
      .approve(id, decision, "once")
      .then(function () {
        setDecisionApprovalStatus("已提交：" + decision + " " + id, "ok");
        renderDecisionApproval(true);
      })
      .catch(function (e) {
        setDecisionApprovalStatus("审批提交失败：" + e.message, "err");
      });
  }

  /** 右栏子页 B：待批列表（读 GET /api/approvals；force ⇒ 忽略同 key 缓存）。 */
  function renderDecisionApproval(force) {
    var mount = app.byId("decision-approval-mount");
    if (!mount) {
      return;
    }
    var key = "approvals";
    if (!force && key === decisionApprovalKey) {
      return;
    }
    decisionApprovalKey = key;
    var token = ++decisionApprovalToken;
    api
      .approvals()
      .then(function (body) {
        if (token !== decisionApprovalToken) {
          return;
        }
        var pending = Array.isArray(body) ? body : body && Array.isArray(body.pending) ? body.pending : [];
        drawApprovalList(pending);
      })
      .catch(function (e) {
        if (token !== decisionApprovalToken) {
          return;
        }
        app.clear(mount);
        mount.appendChild(app.el("p", { class: "empty", text: "审批未接入：" + e.message }));
      });
  }

  /** 决策模式总入口：只在 decision 模式渲染（左栏详情 + 右栏列表/审批/provider）。 */
  function renderDecision(state) {
    if (!state || state.mode !== "decision") {
      lastDecisionSubpage = null;
      return;
    }
    var subpage = decisionSubpageVisibility(state.decisionSubpage);
    // 切进审批子页时强制重取（否则同 key 缓存会让"刚提交的审批"看起来没变化）。
    var entered = state.decisionSubpage !== lastDecisionSubpage;
    lastDecisionSubpage = state.decisionSubpage;
    if (subpage.view) {
      renderDecisionLeft(state);
      renderDecisionRight(state);
    } else if (subpage.approval) {
      renderDecisionApproval(entered);
    } else if (subpage.provider) {
      renderLlmProviders(entered);
    }
  }

  // ── Provider 配置子页（M11′）：读写 AgentLib 的 ConfigStore ───────────────
  var lastLlmProvidersLoaded = false;

  function setLlmProviderStatus(message, tone) {
    app.statusMessage(app.byId("llm-provider-status"), message, tone);
  }

  /** 拉列表并渲染（掩码视图；坏条目带错误码照样列）。 */
  function renderLlmProviders(force) {
    var mount = app.byId("llm-provider-list");
    if (!mount) {
      return;
    }
    if (!force && lastLlmProvidersLoaded) {
      return;
    }
    lastLlmProvidersLoaded = true;
    api
      .llmProviders()
      .then(function (body) {
        mount.textContent = "";
        var providers = (body && body.providers) || [];
        if (providers.length === 0) {
          mount.appendChild(app.el("p", { class: "empty", text: "还没有配置任何 provider。" }));
          return;
        }
        providers.forEach(function (provider) {
          var fields = providerFields(provider);
          var row = app.el("div", { class: "llm-provider-row" });
          row.setAttribute("data-provider-id", fields.id);
          row.setAttribute("data-provider-valid", fields.valid ? "true" : "false");
          row.appendChild(
            app.el("strong", { text: fields.id + (fields.valid ? "" : "（配置不完整）") })
          );
          if (!fields.valid) {
            row.appendChild(app.el("span", { class: "muted", text: " " + fields.errorCode }));
          } else {
            var dl = app.el("dl", { class: "kv llm-provider-kv" });
            fields.rows.forEach(function (pair) {
              dl.appendChild(app.el("dt", { text: pair[0] }));
              dl.appendChild(app.el("dd", { text: pair[1] }));
            });
            row.appendChild(dl);
          }
          mount.appendChild(row);
        });
      })
      .catch(function (e) {
        mount.textContent = "";
        mount.appendChild(app.el("p", { class: "empty", text: "provider 载入失败：" + e.message }));
      });
  }

  /** 保存 provider：表单 → 载荷（纯函数校验）→ POST；成功后重取列表。 */
  function saveLlmProviderFromForm() {
    var result = providerFormToPayload({
      id: valueOf("llm-provider-id"),
      baseUrl: valueOf("llm-provider-baseurl"),
      model: valueOf("llm-provider-model"),
      apiKey: valueOf("llm-provider-apikey"),
      readTimeoutMs: valueOf("llm-provider-timeout"),
    });
    if (!result.ok) {
      setLlmProviderStatus("保存失败：" + result.reason, "err");
      return;
    }
    setLlmProviderStatus("保存中 " + result.payload.id + "…", "muted");
    api
      .saveLlmProvider(result.payload)
      .then(function () {
        setLlmProviderStatus("已保存：" + result.payload.id, "ok");
        renderLlmProviders(true);
      })
      .catch(function (e) {
        setLlmProviderStatus("保存失败：" + (e && e.message ? e.message : String(e)), "err");
      });
  }

  /** 测试连接：用列表当前选中/表单的 id 打一次真调用（服务端），回 ok/detail。 */
  function testLlmProviderFromForm() {
    var id = valueOrDash(valueOf("llm-provider-id"));
    if (id === "—") {
      setLlmProviderStatus("测试连接：先在 id 里填一个 provider", "warn");
      return;
    }
    setLlmProviderStatus("测试连接 " + id + "…", "muted");
    api
      .testLlmProvider(id)
      .then(function (body) {
        var ok = body && body.ok === true;
        setLlmProviderStatus(
          (ok ? "连接成功：" : "连接失败：") + ((body && body.detail) || ""),
          ok ? "ok" : "err"
        );
      })
      .catch(function (e) {
        setLlmProviderStatus("测试连接失败：" + (e && e.message ? e.message : String(e)), "err");
      });
  }

  /** 决策人绑定 provider（世界写，落 revision）。 */
  function saveDecisionMakerProviderFromForm() {
    var result = providerBindingPayload(valueOf("llm-binding-maker"), valueOf("llm-binding-provider"));
    if (!result.ok) {
      setLlmProviderStatus("绑定失败：" + result.reason, "err");
      return;
    }
    var at = app.target() || {};
    setLlmProviderStatus("绑定 " + result.decisionMakerId + " → " + result.providerId + "…", "muted");
    api
      .setDecisionMakerProvider(at.branch, at.revision, result.decisionMakerId, result.providerId)
      .then(function () {
        setLlmProviderStatus(
          "已绑定：" + result.decisionMakerId + " → " + result.providerId + "（落 revision）",
          "ok"
        );
        if (app.refreshState) {
          app.refreshState(true);
        }
      })
      .catch(function (e) {
        if (e && e.status === 409) {
          setLlmProviderStatus("末端已移动（409），已重取最新状态", "warn");
          if (app.refreshState) {
            app.refreshState(true);
          }
          return;
        }
        var reason = e && e.body && e.body.reason ? e.body.reason : (e && e.message) || String(e);
        setLlmProviderStatus("绑定失败：" + reason, "err");
      });
  }

  /** 工作台初始化：订阅状态并渲染左栏真读数 + 右栏区域分组。 */
  function init() {
    // ★★ 2026-09-23（用户裁定）：决策模式只剩**一个**写按钮（`#decision-start`，合并了原来的
    //   「发送」与「让它跑一轮」）⇒ 这里只接它一根线。`decideRunDecision` / `sendDecisionSay`
    //   仍在（分别由「开始决策」的第 ③① 步调用，并作为 e2e/调试入口挂在 SimosPanels 上）。
    var startButton = app.byId("decision-start");
    if (startButton && startButton.addEventListener) {
      startButton.addEventListener("click", decideStartDecision);
    }
    var resetButton = app.byId("decision-context-reset");
    if (resetButton && resetButton.addEventListener) {
      resetButton.addEventListener("click", decideResetDecisionContext);
    }
    var progressToggle = app.byId("decision-progress-toggle");
    if (progressToggle && progressToggle.addEventListener) {
      progressToggle.addEventListener("click", function () {
        setProgressExpanded(!progressExpanded);
      });
    }
    var providerSave = app.byId("llm-provider-save");
    if (providerSave && providerSave.addEventListener) {
      providerSave.addEventListener("click", saveLlmProviderFromForm);
    }
    var providerTest = app.byId("llm-provider-test");
    if (providerTest && providerTest.addEventListener) {
      providerTest.addEventListener("click", testLlmProviderFromForm);
    }
    var bindingSave = app.byId("llm-binding-save");
    if (bindingSave && bindingSave.addEventListener) {
      bindingSave.addEventListener("click", saveDecisionMakerProviderFromForm);
    }
    updateStartDecisionControl();
    updateRunDecisionControl();
    renderRight(app.getState());
    renderLeft(app.getState());
    renderDecision(app.getState());
    app.onStateChange(function (state) {
      renderLeft(state);
      renderRight(state);
      renderDecision(state);
    });
  }

  window.SimosPanels = {
    init: init,
    renderLeft: renderLeft,
    renderRight: renderRight,
    renderDecision: renderDecision,
    renderDecisionLeft: renderDecisionLeft,
    renderDecisionRight: renderDecisionRight,
    groupByTag: groupByTag,
    normalizeTag: normalizeTag,
    movementReadout: movementReadout,
    // ★ B15/B16：左栏读数的单位换算与口径投影（纯函数）——「内部单位不进 DOM」由它们承重。
    millisToMpText: millisToMpText,
    perMilleToRateText: perMilleToRateText,
    // ★ B8：编制那节的可见性（纯函数）——「无单位 hex 不得挂着编制树」由它承重。
    unitTreeSectionVisible: unitTreeSectionVisible,
    // ★ M8 T9：左栏"从属区域"读数（纯函数）——门禁直接对它下断言（并集 ≠ 求和）。
    regionMembershipSummary: regionMembershipSummary,
    UNTAGGED_LABEL: UNTAGGED_LABEL,
    // ★ T7：决策模式的纯函数（门禁直接断言；无 DOM/IO）——子页、分类分组、国家/单位解析、待决文本。
    DECISION_SUBPAGES: DECISION_SUBPAGES,
    DECISION_SCOPE_SUMMARY_ID: DECISION_SCOPE_SUMMARY_ID,
    decisionSubpageState: decisionSubpageState,
    decisionSubpageVisibility: decisionSubpageVisibility,
    // ★ M11′：Provider 配置页的纯函数（表单 → 载荷 / 掩码视图 / 绑定载荷）。
    providerFormToPayload: providerFormToPayload,
    providerFields: providerFields,
    providerBindingPayload: providerBindingPayload,
    affiliationKindLabel: affiliationKindLabel,
    affiliationLabel: affiliationLabel,
    pendingStatusText: pendingStatusText,
    // ★ T10：「开始决策」闸门（纯函数）+ 当前目标（e2e/调试用）。
    startDecisionGate: startDecisionGate,
    startDecision: decideStartDecision,
    startDecisionTargetId: function () {
      return startDecisionTarget ? String(startDecisionTarget.id) : null;
    },
    // ★ 2026-09-23：「让它跑一轮」（sd.RunDecision）+ 决策记录（/api/sd/directives）的纯函数投影。
    runDecisionGate: runDecisionGate,
    runDecision: decideRunDecision,
    runDecisionTargetId: function () {
      return runDecisionTarget ? String(runDecisionTarget.id) : null;
    },
    runOutcomeText: runOutcomeText,
    runTraceFields: runTraceFields,
    // ★ 2026-09-23：异步那一轮的读数投影（e2e/调试用）+ 三个新入口的纯函数部分。
    runProgressLines: runProgressLines,
    runTraceFromStatus: runTraceFromStatus,
    runningText: runningText,
    lastToolName: lastToolName,
    say: sendDecisionSay,
    resetDecisionContext: decideResetDecisionContext,
    asyncRunTargetId: function () {
      return asyncRunTargetId;
    },
    directiveFields: directiveFields,
    directiveHeadline: directiveHeadline,
    decisionMakerGroups: decisionMakerGroups,
    decisionMakersForNation: decisionMakersForNation,
    decisionMakerForUnit: decisionMakerForUnit,
    decisionMakerFields: decisionMakerFields,
    /** 决策模式只读投影（e2e/调试）：已载入的决策人数、分组数、聚焦 id、子页可见性。 */
    decisionDebug: function () {
      var state = app.getState();
      var groups = decisionMakerGroups(decisionRightMakers || []);
      return {
        mode: state.mode,
        subpage: state.decisionSubpage,
        subpageVisibility: decisionSubpageVisibility(state.decisionSubpage),
        focus: state.decisionMakerFocus,
        makerCount: (decisionRightMakers || []).length,
        groupKinds: groups.map(function (group) {
          return group.kind;
        }),
        groupCounts: groups.map(function (group) {
          return group.makers.length;
        }),
        nationRegionCount: (app.getState().highlightRegions || []).length,
      };
    },
  };
})();
