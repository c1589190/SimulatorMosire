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

  function cachedGet(path, target) {
    var url = withTarget(path, target);
    var hit = dataCache[url];
    if (hit) {
      return hit;
    }
    var pending = getJson(url).catch(function (e) {
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

  function cachedMapOverview(target) {
    return cachedGet("/map/overview", target);
  }

  function cachedUnits(target) {
    return cachedGet("/units", target);
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
    return getJson(withTarget("/map/overview", target));
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

  function approvals() {
    return getJson("/approvals");
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
    population: population,
    cachedMapOverview: cachedMapOverview,
    cachedUnits: cachedUnits,
    invalidateState: invalidateState,
    submitCommand: submitCommand,
    advance: advance,
    fork: fork,
    approvals: approvals,
  };
})();
