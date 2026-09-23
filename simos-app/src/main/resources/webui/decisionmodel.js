// decisionmodel.js —— 决策侧的纯函数（M12 第三波：自 panels.js 顶层逐行搬出，函数体一字不动）。
//
// 无状态、无 DOM、无 IO：子页状态/可见性、决策人分组与字段投影、闸门（start/run）、跑一轮的
// 投影与进度行、令文投影、provider 表单载荷。**只有纯计算，不碰 DOM、不发请求。**
//
// ★ 引入顺序：decisionmodel.js → panels.js（panels.js 顶层按名取回）。

(function () {
  "use strict";

  var DECISION_SUBPAGES = [
    { id: "view", label: "决策人查看" },
    { id: "approval", label: "审批" },
    { id: "provider", label: "Provider 配置" },
    // ★ B12（2026-09-23）：第 4 页「决策结果」——决策人按 tick 查看不同决策的逐条结局。
    { id: "results", label: "决策结果" },
  ];

  var AFFILIATION_LABELS = { nation: "国家", army: "军队" };

  /** 非空文本或 null（不 trim 值本身之外的加工；空白一律 null）。 */
  function textOrNull(value) {
    if (value === null || value === undefined) {
      return null;
    }
    var text = String(value).trim();
    return text === "" ? null : text;
  }

  /** 值或 `—`（缺值不显示 `undefined`/`null` 字面量）。 */
  function valueOrDash(value) {
    if (value === null || value === undefined || value === "") {
      return "—";
    }
    return String(value);
  }

  /** 三态文案：true/false 之外一律 `—`（"还没查" ≠ "不可解析"）。 */
  function keyConfiguredText(value) {
    if (value === true) {
      return "可解析";
    }
    if (value === false) {
      return "不可解析";
    }
    return "—";
  }

  /** 归属种类 ⇒ 中文（未知种类原样返回，不静默造标签）。 */
  function affiliationKindLabel(kind) {
    if (kind === null || kind === undefined) {
      return "—";
    }
    var key = String(kind);
    return Object.prototype.hasOwnProperty.call(AFFILIATION_LABELS, key) ? AFFILIATION_LABELS[key] : key;
  }

  /** 归属可读文本：`国家：<显示名>（<id>）`；解析不出的字段显式 `—`（不编造）。 */
  function affiliationLabel(affiliation) {
    var aff = affiliation || {};
    var name = aff.displayName === null || aff.displayName === undefined ? "—" : String(aff.displayName);
    var id = aff.id === null || aff.id === undefined ? "—" : String(aff.id);
    return affiliationKindLabel(aff.kind) + "：" + name + "（" + id + "）";
  }

  /**
   * 待决状态文本（★ 三件事模型 ②）：`due` 只有 T9 才算得出来 ⇒ **null/undefined 一律「—」**。
   * 绝不把"还没算"读成"非待决"（那会造出假的待决数据）。
   */
  function pendingStatusText(due) {
    if (due === true) {
      return "待决";
    }
    if (due === false) {
      return "非待决";
    }
    return "—";
  }

  /**
   * 「开始决策」按钮闸门（纯函数，T10）：三件事模型 ④ 只对**本 tick 待决**（T9 的 `due`）的决策人可点。
   *
   * <p>★ `due` 取不到（null/undefined）⇒ **不可点**（"还没算" ≠ "可以点"，同 pendingStatusText 口径）。
   */
  function startDecisionGate(maker) {
    if (!maker || maker.id === null || maker.id === undefined || maker.id === "") {
      return { enabled: false, reason: "未选中决策人" };
    }
    if (maker.due === true) {
      return { enabled: true, reason: "可发起（本 tick 待决）" };
    }
    if (maker.due === false) {
      return { enabled: false, reason: "非待决（本 tick 未到决策周期）" };
    }
    return { enabled: false, reason: "待决状态未知（due 缺失）" };
  }

  /**
   * 右栏分类列表（纯函数）：`[{kind, label, makers:[…]}]`。
   * 组序：**国家 → 军队 → 其它（按 kind 字典序）**；组内按 id 字典序；**总长度 == 输入长度**
   * （未知 kind 归入「其它（kind）」桶，绝不静默丢弃）。
   */
  function decisionMakerGroups(makers) {
    var buckets = {};
    var kinds = [];
    (makers || []).forEach(function (maker) {
      if (!maker || maker.id === null || maker.id === undefined) {
        return;
      }
      var kind =
        maker.affiliation && maker.affiliation.kind !== null && maker.affiliation.kind !== undefined
          ? String(maker.affiliation.kind)
          : "";
      if (!Object.prototype.hasOwnProperty.call(buckets, kind)) {
        buckets[kind] = [];
        kinds.push(kind);
      }
      buckets[kind].push(maker);
    });
    kinds.sort(function (a, b) {
      var rank = function (kind) {
        if (kind === "nation") {
          return 0;
        }
        if (kind === "army") {
          return 1;
        }
        // 具名但未知的种类排第 3 档；**无归属（空 kind）恒排最后**（"没有归属"不是一种归属种类）。
        return kind === "" ? 3 : 2;
      };
      var ra = rank(a);
      var rb = rank(b);
      if (ra !== rb) {
        return ra - rb;
      }
      return a.localeCompare(b);
    });
    return kinds.map(function (kind) {
      var list = buckets[kind].slice().sort(function (a, b) {
        return String(a.id).localeCompare(String(b.id));
      });
      var label = Object.prototype.hasOwnProperty.call(AFFILIATION_LABELS, kind)
        ? AFFILIATION_LABELS[kind]
        : "其它（" + kind + "）";
      return { kind: kind, label: label, makers: list };
    });
  }

  /** 某国家的全部决策人（纯函数）：按 affiliation.kind==="nation" + id 逐值匹配，组内按 id 字典序。 */
  function decisionMakersForNation(makers, nationId) {
    if (nationId === null || nationId === undefined || nationId === "") {
      return [];
    }
    var want = String(nationId);
    return (makers || [])
      .filter(function (maker) {
        return (
          maker &&
          maker.affiliation &&
          maker.affiliation.kind === "nation" &&
          String(maker.affiliation.id) === want
        );
      })
      .sort(function (a, b) {
        return String(a.id).localeCompare(String(b.id));
      });
  }

  /**
   * 某单位的决策人（纯函数）：军队决策人的 `affiliation.rootUnit` 指向**单位树的一个根**；
   * 选中的单位若是该根的**后代**也算"有决策人"（沿 `parent` 链上溯，与单位树同一口径）。
   * 多军队命中同一根时取 id 字典序最小者（确定性）。无命中 ⇒ null（调用方显示"无决策人"）。
   */
  function decisionMakerForUnit(makers, units, unitId) {
    if (unitId === null || unitId === undefined || unitId === "") {
      return null;
    }
    var byId = {};
    (units || []).forEach(function (unit) {
      if (unit && unit.id !== null && unit.id !== undefined) {
        byId[String(unit.id)] = unit;
      }
    });
    var armies = (makers || [])
      .filter(function (maker) {
        return (
          maker &&
          maker.affiliation &&
          maker.affiliation.kind === "army" &&
          maker.affiliation.rootUnit !== null &&
          maker.affiliation.rootUnit !== undefined
        );
      })
      .sort(function (a, b) {
        return String(a.id).localeCompare(String(b.id));
      });
    if (!armies.length) {
      return null;
    }
    var visited = {};
    var cursor = String(unitId);
    while (cursor && !Object.prototype.hasOwnProperty.call(visited, cursor)) {
      visited[cursor] = true;
      for (var i = 0; i < armies.length; i++) {
        if (String(armies[i].affiliation.rootUnit) === cursor) {
          return armies[i];
        }
      }
      var unit = byId[cursor];
      var parent = unit ? unit.parent : null;
      cursor = parent === null || parent === undefined || parent === "" ? null : String(parent);
    }
    return null;
  }

  /** 左栏决策人详情的字段投影（纯函数）：与 `GET /api/sd/decision-makers/{id}` 逐值一致。 */
  function decisionMakerFields(maker) {
    // ★ T9：`viewScope` → `accessLimit`（语义变了：不再是"绝对可见集合"，而是 GM 配的**额外限制**）。
    var limit = maker ? maker.accessLimit : null;
    return {
      id: maker ? maker.id : null,
      affiliation: affiliationLabel(maker ? maker.affiliation : null),
      cadence: maker ? maker.cadence : null,
      allowedTools:
        maker && Array.isArray(maker.allowedTools) && maker.allowedTools.length
          ? maker.allowedTools.join("、")
          : "（无）",
      accessLimit: limit
        ? {
            prefixesByNamespace: prefixSummary(limit.prefixesByNamespace),
            adjudicationDisclosure: limit.adjudicationDisclosure,
            redactedFields:
              Array.isArray(limit.redactedFields) && limit.redactedFields.length
                ? limit.redactedFields.join("、")
                : "（无）",
          }
        : null,
      pending: pendingStatusText(maker ? maker.due : null),
      // ★ 2026-09-23：**最近一次在第几 tick 出的令**（服务端 `lastDirectiveTick` / `ticksSinceLast`，T9 起就是真值）。
      //   从未出过令 ⇒ `—`（"没有基准"与"tick 0 出过令"是两件事，不拿 0 顶替）。
      lastDirectiveTick: valueOrDash(maker ? maker.lastDirectiveTick : null),
      ticksSinceLast: valueOrDash(maker ? maker.ticksSinceLast : null),
      // ★ 会话世代 + 派生出的会话 id（服务端 `conversationGeneration` / `conversationId`，T9 之后的世界事实）：
      //   GM 据此知道"这个人换过几次会话"。缺字段 ⇒ 显式"—"（不编造"第 0 代"）。
      conversationGeneration:
        maker && maker.conversationGeneration !== null && maker.conversationGeneration !== undefined
          ? maker.conversationGeneration
          : "—",
      conversationId:
        maker && maker.conversationId !== null && maker.conversationId !== undefined
          ? maker.conversationId
          : "—",
    };
  }

  /** 前缀图摘要（纯函数）：`{命名空间: 条数}` → `map=2、unit=5`；空/缺 ⇒ `（无额外限制）`。 */
  function prefixSummary(byNamespace) {
    if (!byNamespace || typeof byNamespace !== "object") {
      return "（无额外限制）";
    }
    var parts = Object.keys(byNamespace).sort();
    if (!parts.length) {
      return "（无额外限制）";
    }
    var out = [];
    for (var i = 0; i < parts.length; i += 1) {
      out.push(parts[i] + "=" + byNamespace[parts[i]]);
    }
    return out.join("、");
  }

  /** 子页状态（fail-closed）：未知 id ⇒ `{ok:false, id:null, label:null}`（不兜成第一个子页）。 */
  function decisionSubpageState(id) {
    for (var i = 0; i < DECISION_SUBPAGES.length; i++) {
      if (DECISION_SUBPAGES[i].id === id) {
        return { ok: true, id: DECISION_SUBPAGES[i].id, label: DECISION_SUBPAGES[i].label };
      }
    }
    return { ok: false, id: null, label: null };
  }

  /**
   * 子页可见性（纯函数，无 DOM）：恰一个为 true；未知 id ⇒ **全部 false**（fail-closed，
   * 不把"没选"变成"选了决策人查看"——与 map.js 的 mapEditPanelVisibility 同口径）。
   */
  function decisionSubpageVisibility(id) {
    var state = decisionSubpageState(id);
    return {
      view: state.ok && state.id === "view",
      approval: state.ok && state.id === "approval",
      provider: state.ok && state.id === "provider",
      results: state.ok && state.id === "results",
    };
  }

  /**
   * Provider 表单 → 端点载荷（纯函数，M11′ 配置页）。
   *
   * <p>★ **不造假**：缺 `baseUrl` / `model` / `id` 一律返回 `{ok:false, reason}` —— 绝不填默认值把空表单
   * 变成一条"看起来有效"的 provider（同 providerFields 的口径）。`apiKey` 只在非空时带上（空 = 不改密钥）。
   */
  function providerFormToPayload(form) {
    var src = form || {};
    var id = textOrNull(src.id);
    if (id === null) {
      return { ok: false, reason: "id 不得为空" };
    }
    if (!/^[A-Za-z0-9][A-Za-z0-9_-]*$/.test(id)) {
      return { ok: false, reason: "id 只能以字母/数字开头，其后可含字母/数字/下划线/连字符" };
    }
    var baseUrl = textOrNull(src.baseUrl);
    if (baseUrl === null) {
      return { ok: false, reason: "baseUrl 不得为空（API 根，含 /v1，不含 /chat/completions）" };
    }
    var model = textOrNull(src.model);
    if (model === null) {
      return { ok: false, reason: "model 不得为空" };
    }
    var payload = { id: id, baseUrl: baseUrl, model: model };
    var credentialsRef = textOrNull(src.credentialsRef);
    if (credentialsRef !== null) {
      payload.credentialsRef = credentialsRef;
    }
    if (src.readTimeoutMs !== null && src.readTimeoutMs !== undefined && src.readTimeoutMs !== "") {
      var timeout = Number(src.readTimeoutMs);
      if (!isFinite(timeout) || timeout <= 0) {
        return { ok: false, reason: "readTimeoutMs 必须是正整数毫秒数" };
      }
      payload.readTimeoutMs = timeout;
    }
    var apiKey = textOrNull(src.apiKey);
    if (apiKey !== null) {
      payload.apiKey = apiKey;
    }
    return { ok: true, payload: payload };
  }

  /**
   * Provider 视图 → 展示字段（纯函数，掩码，M11′）。
   *
   * <p>★ **`valid:false` 的坏条目照样列**（带 `errorCode`）——配置页要能看见自己写坏的那条，才谈得上去修它。
   * `keyConfigured` 三态：true / false / `null`（取不到 ⇒ 显 `—`，**不拿 false 顶替**）。
   */
  function providerFields(provider) {
    var p = provider || {};
    if (p.valid === false) {
      return {
        id: p.id === null || p.id === undefined ? "—" : String(p.id),
        valid: false,
        errorCode: p.errorCode === null || p.errorCode === undefined ? "—" : String(p.errorCode),
        rows: [],
      };
    }
    var rows = [
      ["baseUrl", valueOrDash(p.baseUrl)],
      ["model", valueOrDash(p.model)],
      ["protocol", valueOrDash(p.protocol)],
      ["credentialsRef", valueOrDash(p.credentialsRef)],
      ["readTimeoutMs", valueOrDash(p.readTimeoutMs)],
      ["keyConfigured", keyConfiguredText(p.keyConfigured)],
    ];
    return {
      id: p.id === null || p.id === undefined ? "—" : String(p.id),
      valid: true,
      errorCode: null,
      rows: rows,
    };
  }

  /** 决策人绑定 provider 的载荷（纯函数）：两者都非空才 ok。 */
  function providerBindingPayload(decisionMakerId, providerId) {
    var maker = textOrNull(decisionMakerId);
    var provider = textOrNull(providerId);
    if (maker === null) {
      return { ok: false, reason: "未选中决策人" };
    }
    if (provider === null) {
      return { ok: false, reason: "providerId 不得为空" };
    }
    return { ok: true, decisionMakerId: maker, providerId: provider };
  }

  /**
   * 「让它跑一轮」闸门（纯函数）：有目标 + 它**绑了 provider** 才可点。
   *
   * <p>★ 未绑定 ⇒ 不可点：服务端那条路是 fail-closed（未绑定 provider **抛**，绝不落到某个默认 provider），
   * 让用户点了再等出错不如当场说清。`providerId` 取不到（undefined）与空串同判——**不猜"大概绑了"**。
   * ★ **与「开始决策」不同**：那边用户明确要求"随时可点"（due 只作提示），这边是**装配前提**（没绑 provider 必失败）。
   */
  function runDecisionGate(maker) {
    if (!maker || maker.id === null || maker.id === undefined || maker.id === "") {
      return { enabled: false, reason: "未选中决策人" };
    }
    var provider = maker.providerId;
    if (provider === null || provider === undefined || String(provider).trim() === "") {
      return { enabled: false, reason: "未绑定 LLM provider（先到「Provider 配置」子页绑定再跑）" };
    }
    return { enabled: true, reason: "可跑一轮（真 LLM 自行读世界、出令；可能停在等审批）" };
  }

  /**
   * 运行中的状态文案（**秒数取自服务端的 {@code elapsedMs}**，不是本地时钟差）：这是"看起来没卡死"的唯一判据
   * ——秒数在动 ⇒ 这一轮还在跑。
   *
   * <p>★ 取服务端的读数而不是本地 {@code Date.now()} 差：本地差在"请求根本没送到"时也会一直涨（那正是**假装在跑**）。
   */
  function runningText(id, status) {
    var seconds = Math.max(0, Math.round(((status && status.elapsedMs) || 0) / 1000));
    var calls = toolCallCount(status);
    return (
      "正在跑一轮：" + id + "（已 " + seconds + "s）· 第 " + valueOrDash(status && status.llmCalls) +
      " 轮 LLM · 已调 " + calls + " 次工具" +
      (calls ? "（最近 " + lastToolName(status) + "）" : "") +
      "——若它出令（sd.IssueDirective），会在审批栏等审批（右下方通知栏 / 「审批」子页）"
    );
  }

  /** 最近一次工具调用的名字（无 ⇒ `—`；**不编造**）。 */
  function lastToolName(status) {
    var calls = status && Array.isArray(status.toolCalls) ? status.toolCalls : [];
    if (!calls.length) {
      return "—";
    }
    var last = calls[calls.length - 1] || {};
    return valueOrDash(last.tool);
  }

  /**
   * 异步读数 ⇒ **既有轨迹渲染**要的形状（字段同名同形）。
   *
   * <p>★ 为什么要这么一层：轨迹渲染器（{@link runTraceFields}）原本吃的是**同步响应**的形状；异步化之后同样的字段
   * 散在 {@code status} 与 {@code status.result} 两处。**只在这里翻译一次**，渲染器一行不改——若让渲染器两头都认，
   * "两种形状"就会渗进渲染分支里，日后加字段必漏一处。
   */
  function runTraceFromStatus(id, status) {
    var s = status || {};
    var r = s.result || {};
    return {
      decisionMakerId: id,
      conversationId: r.conversationId,
      llmCalls: s.llmCalls,
      abortedByBudget: r.status === "aborted",
      finalText: r.finalText,
      // ★ `ok` ⇒ 没有 reason（渲染器据此走"跑完了"那条），其余一律带上服务端给的理由（**不吞**）。
      reason: r.status && r.status !== "ok" ? valueOrDash(r.reason) : null,
      detail: r.detail,
      toolCalls: s.toolCalls,
      ref: null,
    };
  }

  /**
   * 结局文案（纯函数）：**认服务端给的那一个**（`result.status`），不在前端另算一套。
   *
   * <p>★ `ok` / `aborted` / `failed` 三态各自说清：中止**不是**失败到没有信息（触发事实已落盘、历史也在会话里）。
   */
  function runOutcomeText(status) {
    var s = status || {};
    var r = s.result || {};
    if (!r.status) {
      return "这一轮结束了，但服务端没有给出结局（读数里 result 为空）——以轨迹为准。";
    }
    if (r.status === "aborted") {
      return (
        "已中止：撞上回合预算（llmCalls=" +
        valueOrDash(s.llmCalls) +
        "）；历史已落盘，下一轮可续"
      );
    }
    if (r.status === "failed") {
      return "这一轮没跑成：" + valueOrDash(r.reason) + " —— " + valueOrDash(r.detail);
    }
    return "跑完一轮：llmCalls=" + valueOrDash(s.llmCalls) + "，工具调用 " + toolCallCount(s) + " 次";
  }

  function runOutcomeTone(status) {
    var r = (status && status.result) || {};
    if (r.status === "ok") {
      return "ok";
    }
    return r.status ? "warn" : "muted";
  }

  function toolCallCount(body) {
    var calls = body && Array.isArray(body.toolCalls) ? body.toolCalls : [];
    return calls.length;
  }

  /** 进度读数 ⇒ 行（纯函数）：未跑过 / 正在跑 / 已跑完三种形态**各不相同**（不把"没有记录"画成"0 轮 0 次"）。 */
  function runProgressLines(id, status) {
    var s = status || {};
    var known = s.startedAt !== null && s.startedAt !== undefined;
    if (!known) {
      return {
        empty: true,
        lines: ["还没有 " + id + " 这一轮的记录（服务端重启后这份账会清空——它不落盘）。"],
      };
    }
    var lines = [];
    var seconds = Math.max(0, Math.round((s.elapsedMs || 0) / 1000));
    lines.push(
      "状态：" +
        (s.done === true ? "已结束" : "正在跑") +
        " · 已 " +
        seconds +
        "s · 第 " +
        valueOrDash(s.llmCalls) +
        " 轮 LLM 调用"
    );
    var calls = Array.isArray(s.toolCalls) ? s.toolCalls : [];
    lines.push("工具调用：" + calls.length + " 次");
    calls.forEach(function (call, index) {
      var c = call || {};
      lines.push(
        "  " +
          (index + 1) +
          ". " +
          valueOrDash(c.tool) +
          " · " +
          (c.ok === true ? "OK" : "失败（" + valueOrDash(c.code) + "）")
      );
    });
    if (s.done === true) {
      var r = s.result || {};
      if (r.finalText) {
        lines.push("收尾文本：" + String(r.finalText));
      }
      lines.push("会话：" + valueOrDash(r.conversationId));
    }
    return { empty: false, lines: lines };
  }

  /**
   * 本轮轨迹的字段投影（纯函数，与 `POST /api/sd/run-decision` 的返回体逐字段对应）。
   *
   * <p>★ 缺值一律 `—`（**不拿 0 / false / 空串顶替**）：`llmCalls` 取不到与"一次都没调"是两件事。
   */
  function runTraceFields(body) {
    var b = body || {};
    var calls = Array.isArray(b.toolCalls) ? b.toolCalls : [];
    return {
      decisionMakerId: valueOrDash(b.decisionMakerId),
      conversationId: valueOrDash(b.conversationId),
      llmCalls: b.llmCalls === null || b.llmCalls === undefined ? "—" : String(b.llmCalls),
      abortedByBudget: b.abortedByBudget === true,
      finalText:
        b.finalText === null || b.finalText === undefined
          ? "（本轮没有收尾文本）"
          : String(b.finalText),
      revision: b.ref && b.ref.revision !== undefined ? String(b.ref.revision) : "—",
      // ★ 这一轮**没跑成**时服务端给的两个字段（未绑 provider / 路由查无 / 查无此人 / 撞预算）：
      //   有 reason 就说明**没有轨迹可言**——此时"工具调用 0 次"会被读成"跑得好、只是没调工具"，那是假的。
      reason: b.reason === null || b.reason === undefined ? null : String(b.reason),
      detail: b.detail === null || b.detail === undefined ? "—" : String(b.detail),
      toolCalls: calls.map(function (call) {
        var c = call || {};
        return {
          tool: valueOrDash(c.tool),
          ok: c.ok === true,
          code: valueOrDash(c.code),
          summary: valueOrDash(c.summary),
        };
      }),
    };
  }

  /** 决策记录的字段投影（纯函数，与 `GET /api/sd/directives` 逐字段对应；缺值 `—`，不编造）。 */
  function directiveFields(directive) {
    var d = directive || {};
    var commands = Array.isArray(d.commands) ? d.commands : [];
    var effects = Array.isArray(d.effects) ? d.effects : [];
    return {
      directiveId: valueOrDash(d.directiveId),
      decisionMakerId: valueOrDash(d.decisionMakerId),
      tick: d.tick === null || d.tick === undefined ? "—" : String(d.tick),
      target: d.target === null || d.target === undefined ? "（无目标）" : String(d.target),
      // ★ 执行原文（决心的"理由"）由服务端从 sd INFO 覆盖层取回；取不到 ⇒ 显式说没有，不拿 key 名顶替。
      intentInfo:
        d.intentInfo === null || d.intentInfo === undefined
          ? "（取不到执行原文）"
          : String(d.intentInfo),
      intentInfoKey: valueOrDash(d.intentInfoKey),
      status: valueOrDash(d.status),
      verdict: d.verdict === null || d.verdict === undefined ? "（无判决）" : String(d.verdict),
      effects: effects.length ? effects.join("、") : "（无）",
      commands: commands.map(function (command) {
        var c = command || {};
        return { type: valueOrDash(c.type), payloadJson: valueOrDash(c.payloadJson) };
      }),
    };
  }

  /** 列表项的一行摘要：`tick N · <执行原文首行>`（原文可能很长，只取首行、按字符截断）。 */
  function directiveHeadline(directive) {
    var fields = directiveFields(directive);
    var firstLine = fields.intentInfo.split("\n")[0];
    if (firstLine.length > 60) {
      firstLine = firstLine.slice(0, 60) + "…";
    }
    return "tick " + fields.tick + " · " + firstLine + "（" + fields.commands.length + " 条命令）";
  }

  // ── 决策结果（B12，2026-09-23）：GET /api/sd/decision-results 的纯投影 ──────────
  //
  // ★ `value` 是**字符串化的 JSON**（内容由裁决工具写）⇒ 前端必须 `JSON.parse` 再逐条渲染。
  //   解析不了**不许抛**（一条坏条目不该让整页空白）⇒ 折成 parseError 文本、照常列出。
  // ★ **可见性只由服务端定**：端点只回 `tags` 含 `as` 的条目；`tags` 为空（无主）对谁都不回。
  //   前端**不得**再筛一遍（那就是第二份可见性），这里只做投影。

  /** 查询参数里的非负整数（纯函数）：缺/空 ⇒ 不带；非法 ⇒ error（**不静默当成 0**）。 */
  function intQuery(value, label) {
    if (value === null || value === undefined || String(value).trim() === "") {
      return { present: false };
    }
    var n = Number(value);
    if (!isFinite(n) || Math.floor(n) !== n || n < 0) {
      return { error: label + " 必须是非负整数。" };
    }
    return { present: true, value: n };
  }

  /**
   * 决策结果查询 → 端点路径（纯函数）：
   * `/sd/decision-results?as=…[&tick=N | &fromTick=A&toTick=B][&limit=N]`。
   *
   * <p>★ 窗口的**唯一**防线在这里：默认 limit=20、上限 200——超限**报错**（契约是服务端报错、不截断），
   *   前端先拦是为了让用户当场看见，而不是收到一个语焉不详的 4xx。`tick` 与 `fromTick/toTick` 二选一。
   * <p>★ 缺 `as` ⇒ 拒绝（这是"谁看"的查询，没有 `as` 无从谈起）。
   */
  function decisionResultsRequest(params) {
    var src = params || {};
    var as = textOrNull(src.as);
    if (as === null) {
      return {
        ok: false,
        reason: "未选中决策人：先选一个决策人（地图点选或右栏列表），再看它的决策结果。",
      };
    }
    var tick = intQuery(src.tick, "tick");
    if (tick.error) {
      return { ok: false, reason: tick.error };
    }
    var from = intQuery(src.fromTick, "fromTick");
    if (from.error) {
      return { ok: false, reason: from.error };
    }
    var to = intQuery(src.toTick, "toTick");
    if (to.error) {
      return { ok: false, reason: to.error };
    }
    if (tick.present && (from.present || to.present)) {
      return { ok: false, reason: "tick 与 fromTick/toTick 只能二选一（看一个 tick，还是看一段区间）。" };
    }
    if (from.present !== to.present) {
      return { ok: false, reason: "fromTick 与 toTick 必须成对给出（区间要看两端）。" };
    }
    if (from.present && from.value > to.value) {
      return { ok: false, reason: "fromTick 不得大于 toTick。" };
    }
    var limit = intQuery(src.limit, "limit");
    if (limit.error) {
      return { ok: false, reason: limit.error };
    }
    var limitValue = limit.present ? limit.value : 20;
    if (limitValue < 1) {
      return { ok: false, reason: "limit 必须是正整数（最少 1 条）。" };
    }
    if (limitValue > 200) {
      return { ok: false, reason: "limit 上限是 200（服务端不截断、直接报错，请调小）。" };
    }
    var query = "as=" + encodeURIComponent(as);
    if (tick.present) {
      query += "&tick=" + tick.value;
    } else if (from.present) {
      query += "&fromTick=" + from.value + "&toTick=" + to.value;
    }
    query += "&limit=" + limitValue;
    return { ok: true, path: "/sd/decision-results?" + query };
  }

  /**
   * 条目"涉及的决策人"（纯函数）：优先用 `tags`（服务端的归属），缺 `tags` ⇒ 退回逐条命令的
   * `decisionMakerId`（去重、保序）。**只投影、不筛**。
   */
  function decisionResultMakers(tags, commands) {
    var out = [];
    function push(value) {
      var text = textOrNull(value);
      if (text !== null && out.indexOf(text) < 0) {
        out.push(text);
      }
    }
    (Array.isArray(tags) ? tags : []).forEach(push);
    if (!out.length) {
      (Array.isArray(commands) ? commands : []).forEach(function (c) {
        push(c && c.decisionMakerId);
      });
    }
    return out;
  }

  /** 单条命令的结局（纯函数）：applied ⇒ 已执行；rejected ⇒ 已驳回（拒因照显）；其余如实说"未知"。 */
  function decisionResultCommand(command) {
    var c = command || {};
    var result = textOrNull(c.result);
    var reason = textOrNull(c.reason);
    var outcome;
    if (result === "applied") {
      outcome = "已执行";
    } else if (result === "rejected") {
      outcome = "已驳回";
    } else if (result === null) {
      outcome = "—（后端未给结局）";
    } else {
      outcome = "未知结局：" + result;
    }
    return {
      decisionMakerId: valueOrDash(c.decisionMakerId),
      directiveId: valueOrDash(c.directiveId),
      // ★ 命令类型**不造可读名**：本仓没有 `type → 中文` 的现成映射（决策记录那处也是显原文）⇒ 显示 `type` 原文。
      type: valueOrDash(c.type),
      result: result === null ? "—" : result,
      outcome: outcome,
      // 拒因：后端只可能在 rejected 时给 ⇒ 有就显、没有就 null（不拿空串/占位顶替）。
      reason: reason,
      ref: c.ref === null || c.ref === undefined ? "—" : String(c.ref),
    };
  }

  /** 一条决策结果条目（纯函数，与端点逐字段对应）：`value` 解析失败 ⇒ parseError 非空、commands 为空。 */
  function decisionResultEntry(entry) {
    var e = entry || {};
    var raw = e.value;
    var body = null;
    var parseError = null;
    if (raw === null || raw === undefined || String(raw).trim() === "") {
      parseError = "结果体为空（value 缺失）——无法显示逐条结局。";
    } else {
      try {
        body = JSON.parse(String(raw));
      } catch (err) {
        parseError = "结果体不是合法 JSON：" + (err && err.message ? err.message : String(err));
      }
    }
    var obj = body && typeof body === "object" ? body : {};
    var commands = Array.isArray(obj.commands) ? obj.commands : [];
    var tags = Array.isArray(e.tags) ? e.tags : [];
    var at = e.at || {};
    return {
      tick: e.tick === null || e.tick === undefined ? "—" : String(e.tick),
      id: valueOrDash(e.id),
      tags: tags.map(function (tag) {
        return String(tag);
      }),
      makers: decisionResultMakers(tags, commands),
      atBranch: valueOrDash(at.branch),
      atRevision: at.revision === null || at.revision === undefined ? "—" : String(at.revision),
      resultRevision:
        obj.resultRevision === null || obj.resultRevision === undefined
          ? "—"
          : String(obj.resultRevision),
      commands: commands.map(decisionResultCommand),
      parseError: parseError,
    };
  }

  /**
   * 响应 ⇒ 视图（纯函数）：按 tick 分组（同 tick 归一组；组序 = 首次出现序 ⇒ 服务端给的 tick 降序即"新的在前"）。
   * ★ **不重排**（同 api.directives 口径）：同一快照两次读数必须一致。
   * ★ `note`（服务端的"明确无结果"说明）只在**真的空**时透出，前端不当错误渲染。
   */
  function decisionResultsView(body) {
    var b = body || {};
    var list = Array.isArray(b.results) ? b.results : [];
    var order = [];
    var byTick = {};
    list.forEach(function (entry) {
      var view = decisionResultEntry(entry);
      if (!Object.prototype.hasOwnProperty.call(byTick, view.tick)) {
        byTick[view.tick] = [];
        order.push(view.tick);
      }
      byTick[view.tick].push(view);
    });
    var note = null;
    if (list.length === 0 && b.note !== null && b.note !== undefined && String(b.note).trim() !== "") {
      note = String(b.note);
    }
    return {
      empty: list.length === 0,
      note: note,
      count: list.length,
      groups: order.map(function (tick) {
        return { tick: tick, entries: byTick[tick] };
      }),
    };
  }

  window.SimosDecisionModel = {
    DECISION_SUBPAGES: DECISION_SUBPAGES,
    AFFILIATION_LABELS: AFFILIATION_LABELS,
    textOrNull: textOrNull,
    valueOrDash: valueOrDash,
    keyConfiguredText: keyConfiguredText,
    affiliationKindLabel: affiliationKindLabel,
    affiliationLabel: affiliationLabel,
    pendingStatusText: pendingStatusText,
    startDecisionGate: startDecisionGate,
    decisionMakerGroups: decisionMakerGroups,
    decisionMakersForNation: decisionMakersForNation,
    decisionMakerForUnit: decisionMakerForUnit,
    decisionMakerFields: decisionMakerFields,
    prefixSummary: prefixSummary,
    decisionSubpageState: decisionSubpageState,
    decisionSubpageVisibility: decisionSubpageVisibility,
    providerFormToPayload: providerFormToPayload,
    providerFields: providerFields,
    providerBindingPayload: providerBindingPayload,
    runDecisionGate: runDecisionGate,
    runningText: runningText,
    lastToolName: lastToolName,
    runTraceFromStatus: runTraceFromStatus,
    runOutcomeText: runOutcomeText,
    runOutcomeTone: runOutcomeTone,
    toolCallCount: toolCallCount,
    runProgressLines: runProgressLines,
    runTraceFields: runTraceFields,
    directiveFields: directiveFields,
    directiveHeadline: directiveHeadline,
    // ★ B12：决策结果子页的纯函数（查询窗口 / 逐条结局投影 / 按 tick 分组视图）。
    decisionResultsRequest: decisionResultsRequest,
    decisionResultCommand: decisionResultCommand,
    decisionResultEntry: decisionResultEntry,
    decisionResultsView: decisionResultsView,
  };
})();
