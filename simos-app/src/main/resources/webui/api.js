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

  function state() {
    return getJson("/state");
  }

  function resolve(address) {
    return getJson("/resolve?address=" + encodeURIComponent(address));
  }

  function facets(address) {
    return getJson("/facets?address=" + encodeURIComponent(address));
  }

  function mapOverview() {
    return getJson("/map/overview");
  }

  function mapHex(q, r) {
    return getJson("/map/hex?q=" + Number(q) + "&r=" + Number(r));
  }

  function units() {
    return getJson("/units");
  }

  function unit(id) {
    return getJson("/unit/" + encodeURIComponent(id));
  }

  function population(q, r) {
    return getJson("/social/population?q=" + Number(q) + "&r=" + Number(r));
  }

  // ── 写端点（spec §8.2）；服务端唯一写入口 CoreSimos.submit ─────────────

  /**
   * 信封提交。envelope = {type, payloadJson, branch, expectedRevision}
   * 返回 {result:"committed",ref} | {result:"conflict",current}（非 2xx 抛出，body 里带 result）。
   */
  function submitCommand(envelope) {
    return postJson("/command", envelope);
  }

  function advance(branch, expectedRevision, from, to) {
    var body = { branch: branch, expectedRevision: expectedRevision, from: from };
    if (to !== null && to !== undefined) {
      body.to = to;
    }
    return postJson("/advance", body);
  }

  function fork(source, expectedRevision, newBranch) {
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
    state: state,
    resolve: resolve,
    facets: facets,
    mapOverview: mapOverview,
    mapHex: mapHex,
    units: units,
    unit: unit,
    population: population,
    submitCommand: submitCommand,
    advance: advance,
    fork: fork,
    approvals: approvals,
  };
})();
