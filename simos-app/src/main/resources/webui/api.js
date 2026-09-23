// api.js —— /api 只读+写入的取数层（M5 T9）。
// ★ 同源相对路径：所有请求都打在**本页 origin** 上（spec §8.1：无 CDN、无绝对 URL）。
// ★ 写面唯一：只有 submitCommand/advance/fork 会 POST，且一律打 /api/command|advance|fork
//   （服务端把 initiator 钉成 player:gui，前端不持有身份——身份在服务端，spec §九）。

(function () {
  "use strict";

  // 相对常量：不带前导协议/主机。测试 WebuiAssetsTest 扫描绝对 URL，这里必须保持纯净。
  var API_BASE = "/api";

  /**
   * GET 一个 /api 端点，返回解析后的 JSON。非 2xx 抛错（携带 status + 服务端 error 文本）。
   */
  async function getJson(path) {
    var response = await fetch(API_BASE + path, {
      method: "GET",
      headers: { Accept: "application/json" },
    });
    return parseResponse(response);
  }

  async function postJson(path, body) {
    var response = await fetch(API_BASE + path, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(body),
    });
    return parseResponse(response);
  }

  async function parseResponse(response) {
    var text = await response.text();
    var json = null;
    if (text) {
      try {
        json = JSON.parse(text);
      } catch (e) {
        json = { error: "响应不是合法 JSON: " + text.slice(0, 200) };
      }
    }
    if (!response.ok) {
      var message = json && json.error ? json.error : response.status + " " + response.statusText;
      var err = new Error(message);
      err.status = response.status;
      err.body = json;
      throw err;
    }
    return json;
  }

  // ── 只读端点（spec §8.2）────────────────────────────────────────────
  //
  // ★ M7 T3 取数约定：所有**只读**取数都可带一个目标 {branch, revision}（取自状态机）。带上它 ⇒ 读到该坐标的
  //   只读快照（spec §五 第 2 步）；不传 ⇒ 服务端缺省 head(main)，旧三页（map/unit/social）保持原行为。
  //   T4~T7 的面板一律传 window.SimosApp.target()。

  /** 把 {branch, revision} 拼到路径上（自动选 ? / &）；target 为空 ⇒ 原样返回。 */
  function withTarget(path, target) {
    if (!target || target.branch === null || target.branch === undefined) {
      return path;
    }
    var separator = path.indexOf("?") >= 0 ? "&" : "?";
    var query = "branch=" + encodeURIComponent(target.branch);
    if (target.revision !== null && target.revision !== undefined) {
      query += "&revision=" + encodeURIComponent(target.revision);
    }
    return path + separator + query;
  }

  // ── 共享记忆化取数层（M9 T2，档 0）──────────────────────────────────
  //
  // ★ 键 = `withTarget(path, target)` 的完整 URL：target 变 ⇒ 键变 ⇒ 旧键自然失效，**不做手工失效**。
  //   `panels.js` / `map.js` / `unitTree.js` 共用同一份缓存 ⇒ 同一 URL×target 只发一次请求。
  //   并发同键合流；失败不缓存（下次可重试）；FIFO 淘汰防无界增长。
  var dataCache = Object.create(null);
  var DATA_CACHE_LIMIT = 64;

  function cachedGet(path, target, decode) {
    var url = withTarget(path, target);
    var hit = dataCache[url];
    if (hit) {
      return hit;
    }
    var pending = getJson(url)
      .then(function (body) {
        return decode ? decode(body) : body;
      })
      .catch(function (e) {
        delete dataCache[url];
        throw e;
      });
    dataCache[url] = pending;
    var keys = Object.keys(dataCache);
    if (keys.length > DATA_CACHE_LIMIT) {
      for (var i = 0; i < keys.length - DATA_CACHE_LIMIT; i++) {
        delete dataCache[keys[i]];
      }
    }
    return pending;
  }

  // ★ M9 T11：overview 的块线格式是整数顶点标签（`[u,w,…]`，见 blocks.js）；在**取数层**一次性解码成
  //   {x,y} 多边形，下游（map.js/panels.js）拿到的仍是同一个形状。decode 只跑一次（结果进缓存）。
  function decodeOverview(body) {
    if (body && body.blocks && window.SimosBlocks && window.SimosBlocks.decodeBlocks) {
      body.blocks = window.SimosBlocks.decodeBlocks(body.blocks);
    }
    return body;
  }

  function cachedMapOverview(target) {
    return cachedGet("/map/overview", target, decodeOverview);
  }

  function cachedUnits(target) {
    return cachedGet("/units", target);
  }

  // ★ T7：决策人只读查询面（T5 落地的后端端点）——决策模式左栏/右栏共用，按 target 记忆化。
  function cachedDecisionMakers(target) {
    return cachedGet("/sd/decision-makers", target);
  }

  // ── /api/state 合流（M9 T2）：启动期 pollState 与 timeline 各拉一次 ⇒ 去重。
  //   TTL 只罩"刚刚解析过"的极短窗口（启动同拍）；写命令开头发起 invalidateState()（epoch+1），
  //   使写后的 state() 既不读旧缓存、也不搭上写前已发出的在途请求 ⇒ 必读到新 head。
  var STATE_TTL_MS = 250;
  var stateInFlight = null;
  var stateInFlightEpoch = -1;
  var stateCache = null;
  var stateCachedAt = 0;
  var stateEpoch = 0;

  function state() {
    if (stateInFlight && stateInFlightEpoch === stateEpoch) {
      return stateInFlight;
    }
    if (stateCache && performance.now() - stateCachedAt < STATE_TTL_MS) {
      return Promise.resolve(stateCache);
    }
    var epoch = stateEpoch;
    var pending = getJson("/state").then(
      function (body) {
        if (stateInFlight === pending) {
          stateInFlight = null;
        }
        if (epoch === stateEpoch) {
          stateCache = body;
          stateCachedAt = performance.now();
        }
        return body;
      },
      function (e) {
        if (stateInFlight === pending) {
          stateInFlight = null;
        }
        throw e;
      }
    );
    stateInFlight = pending;
    stateInFlightEpoch = epoch;
    return pending;
  }

  function invalidateState() {
    stateCache = null;
    stateCachedAt = 0;
    stateEpoch += 1;
  }

  /** 时间轴节点清单（M7 T1/T3）：{branch, head, nodes:[…]}。按分支拉，不带 revision。 */
  function timeline(branch) {
    return getJson("/timeline?branch=" + encodeURIComponent(branch));
  }

  function resolve(address, target) {
    return getJson(withTarget("/resolve?address=" + encodeURIComponent(address), target));
  }

  function facets(address, target) {
    return getJson(withTarget("/facets?address=" + encodeURIComponent(address), target));
  }

  function mapOverview(target) {
    return getJson(withTarget("/map/overview", target)).then(decodeOverview);
  }

  function mapHex(q, r, target) {
    return getJson(withTarget("/map/hex?q=" + Number(q) + "&r=" + Number(r), target));
  }

  function units(target) {
    return getJson(withTarget("/units", target));
  }

  function unit(id, target) {
    return getJson(withTarget("/unit/" + encodeURIComponent(id), target));
  }

  /** 决策人列表（T5，只读）：{decisionMakers:[…]}；可带 ?affiliation=nation:<id>|army:<id>。 */
  function decisionMakers(affiliation, target) {
    var path = "/sd/decision-makers";
    if (affiliation) {
      path += "?affiliation=" + encodeURIComponent(affiliation);
    }
    return getJson(withTarget(path, target));
  }

  /** 单个决策人详情（T5，只读）：id / affiliation（含显示名）/ allowedTools / cadence / accessLimit / due。 */
  function decisionMaker(id, target) {
    return getJson(withTarget("/sd/decision-makers/" + encodeURIComponent(id), target));
  }

  /**
   * 决策人**现算可见范围**（只读）：{decisionMakerId,affiliation,branch,revision,visible,namespaces,unparsedPrefixes}。
   *
   * <p>★ 这是"这个决策人此刻看得见什么"的**唯一**数据源（服务端范围函数 ∩ GM 的 accessLimit）。
   * 前端**不得**自己从 overview 推一遍——那份推出来的东西不随 accessLimit 变，看起来对、其实是假的。
   */
  function decisionMakerScope(id, target) {
    return getJson(withTarget("/sd/decision-makers/" + encodeURIComponent(id) + "/scope", target));
  }

  /**
   * **决策记录**（只读）：{directives:[{directiveId,decisionMakerId,tick,target,intentInfoKey,intentInfo,commands,effects,verdict,status}…]}。
   *
   * <p>★ 带 `decisionMakerId` ⇒ 只列那个人的（**未知 id ⇒ 404**，不折成空列表：空列表表示"还没出过令"）；
   * 不带 ⇒ 全部。
   * <p>★ 顺序由服务端定（tick 降序 ⇒ 第一条就是"最近一次"）。前端**不重排**——重排会让"同一快照两次读数一致"这条前提破。
   */
  function directives(decisionMakerId, target) {
    var path = "/sd/directives";
    if (decisionMakerId) {
      path += "?decisionMakerId=" + encodeURIComponent(decisionMakerId);
    }
    return getJson(withTarget(path, target));
  }

  function population(q, r, target) {
    return getJson(withTarget("/social/population?q=" + Number(q) + "&r=" + Number(r), target));
  }

  /** 区域 hex 集合（M7 T4，spec §3.3）：{id,name,meta,hexCount,hexes:[{q,r}…]}。懒拉，点选时才取。 */
  function mapRegion(id, target) {
    return getJson(withTarget("/map/region/" + encodeURIComponent(id), target));
  }

  /**
   * 服务端 A* 寻路（M7b T3，只读 GET）：{reachable, path:[{q,r}…]}。起点由服务端取该单位的有效位置；
   * 返回的 path 逐格相邻、含首尾，可原样当 unit.PlanRoute 的 waypoints。
   */
  function mapPath(unitId, q, r, target) {
    return getJson(
      withTarget(
        "/map/path?unit=" +
          encodeURIComponent(unitId) +
          "&q=" +
          Number(q) +
          "&r=" +
          Number(r),
        target
      )
    );
  }

  // ── 写端点（spec §8.2）；服务端唯一写入口 CoreSimos.submit ─────────────

  /**
   * 信封提交。envelope = {type, payloadJson, branch, expectedRevision}
   * 返回 {result:"committed",ref} | {result:"conflict",current}（非 2xx 抛出，body 里带 result）。
   */
  function submitCommand(envelope) {
    invalidateState();
    return postJson("/command", envelope);
  }

  function advance(branch, expectedRevision, from, to) {
    invalidateState();
    var body = { branch: branch, expectedRevision: expectedRevision, from: from };
    if (to !== null && to !== undefined) {
      body.to = to;
    }
    return postJson("/advance", body);
  }

  function fork(source, expectedRevision, newBranch) {
    invalidateState();
    return postJson("/fork", {
      source: source,
      expectedRevision: expectedRevision,
      newBranch: newBranch,
    });
  }

  /**
   * 「开始决策」（T10，spec §四.5）：打 `POST /api/sd/start-decision`，体 {branch, expectedRevision,
   * decisionMakerId, note?}。命令类型由服务端写死（窄写面 ⇒ 前端不传 type）；用户路径**直接生效**。
   */
  function startDecision(branch, expectedRevision, decisionMakerId, note) {
    invalidateState();
    var body = {
      branch: branch,
      expectedRevision: expectedRevision,
      decisionMakerId: decisionMakerId,
    };
    if (note !== null && note !== undefined && note !== "") {
      body.note = note;
    }
    return postJson("/sd/start-decision", body);
  }

  /**
   * 「让它跑一轮」（窄写）：打 `POST /api/sd/run-decision`，体 {branch, expectedRevision, decisionMakerId}。
   * 命令类型由服务端写死（`sd.RunDecision` ⇒ 前端不传 type）；先落一条触发事实（revision），再让该决策人的 agent
   * 真跑一轮（真 LLM 自行调工具读世界、出令）。
   *
   * <p>★ 返回体 = 提交结局 + **本轮轨迹**（与 GM 侧的 `sd.RunDecision` 窄工具逐字同形）：`llmCalls` / `finalText` /
   * `toolCalls[{tool,ok,code,summary}]` / `abortedByBudget` / `conversationId`。
   * <p>★★ **它可能等很久**：决策人出的令（`sd.IssueDirective`）是敏感写 ⇒ 各进一次**阻塞式**审批（上限 5 分钟）。
   * 调用方必须显示"正在跑 / 可能在等审批"，不能表现得像卡死。
   * <p>★ **轨迹不落盘**（服务端明确取舍）：换 target / 刷新之后这一轮的账就没了——它只存在于**这次响应**里。
   */
  function runDecision(branch, expectedRevision, decisionMakerId) {
    invalidateState();
    return postJson("/sd/run-decision", {
      branch: branch,
      expectedRevision: expectedRevision,
      decisionMakerId: decisionMakerId,
    });
  }

  function approvals() {
    return getJson("/approvals");
  }

  // ── LLM provider 配置（M11′ 对接版）：读写 AgentLib 的 ConfigStore ─────────
  // ★ 这些端点改的是 **app 基础设施配置**（`<store>/agentlib/config.json` 的 `llm.routes.*` / `keys.*`），
  //   不是世界写（不落 revision、不进命令白名单）；决策人绑定那条是**世界写**（走 /sd/set-decision-maker-provider）。

  /** provider 掩码列表（读 AgentLib 配置；坏条目也列出，带 errorCode）。 */
  function llmProviders() {
    return getJson("/llm/providers");
  }

  /** 新增 / 覆盖一条 provider；apiKey 在场 ⇒ 写 `keys.<id>`（值只进配置，绝不进路由）。 */
  function saveLlmProvider(payload) {
    return postJson("/llm/providers", payload);
  }

  function deleteLlmProvider(id) {
    return postJson("/llm/providers/delete", { id: id });
  }

  /** 测试连接：服务端用该 provider 走一次真调用；失败回 200 {ok:false, detail}。 */
  function testLlmProvider(id) {
    return postJson("/llm/providers/test", { id: id });
  }

  /** 决策人绑定 provider（世界写，固定类型 sd.SetDecisionMakerProvider，落 revision）。 */
  function setDecisionMakerProvider(branch, expectedRevision, decisionMakerId, providerId) {
    invalidateState();
    return postJson("/sd/set-decision-maker-provider", {
      branch: branch,
      expectedRevision: expectedRevision,
      decisionMakerId: decisionMakerId,
      providerId: providerId,
    });
  }

  /** GM MCP 工具使用（T8，只读）：{entries:[{tool, ok, code, atEpochMs}…]}，最新在前。 */
  function gmToolUsage() {
    return getJson("/gm/tool-usage");
  }

  /**
   * ★ T7：审批裁决（**不是**命令写）——打 `POST /api/approvals/{id}`，体 {decision:"approve"|"deny", scope, by}。
   * 它是本文件**唯一**的非命令写端点，作为**单独一条**显式列在 write-allowlist.test.cjs 的
   * ALLOWED_APPROVAL_PREFIX（`/api/approvals/`）里；**不进** modes.js 的命令白名单（那只管 Command 类型）。
   */
  function approve(id, decision, scope) {
    return postJson("/approvals/" + encodeURIComponent(id), {
      decision: decision,
      scope: scope || "once",
      by: "gui",
    });
  }

  window.SimosApi = {
    getJson: getJson,
    postJson: postJson,
    withTarget: withTarget,
    state: state,
    timeline: timeline,
    resolve: resolve,
    facets: facets,
    mapOverview: mapOverview,
    mapHex: mapHex,
    mapRegion: mapRegion,
    mapPath: mapPath,
    units: units,
    unit: unit,
    decisionMakers: decisionMakers,
    decisionMaker: decisionMaker,
    decisionMakerScope: decisionMakerScope,
    directives: directives,
    population: population,
    cachedMapOverview: cachedMapOverview,
    cachedUnits: cachedUnits,
    cachedDecisionMakers: cachedDecisionMakers,
    invalidateState: invalidateState,
    submitCommand: submitCommand,
    advance: advance,
    fork: fork,
    startDecision: startDecision,
    runDecision: runDecision,
    approvals: approvals,
    approve: approve,
    gmToolUsage: gmToolUsage,
    llmProviders: llmProviders,
    saveLlmProvider: saveLlmProvider,
    deleteLlmProvider: deleteLlmProvider,
    testLlmProvider: testLlmProvider,
    setDecisionMakerProvider: setDecisionMakerProvider,
  };
})();
