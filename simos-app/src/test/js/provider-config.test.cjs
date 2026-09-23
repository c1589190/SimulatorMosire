// provider-config.test.cjs —— M11′ Provider 配置页（决策模式第三子页）。
//
// 判据（spec §六/§七 对应项，M11′ 对接版）：
//   C15 三键恰一真、未知值全假（fail-closed）；
//   C16 provider 表单 / 掩码视图 / 绑定载荷三个纯函数**不造假**（缺值不读成默认）；
//   C17 新端点交付清单与 api.js 的导出/写法逐条对表。
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

const P = loadWebui("panels.js").SimosPanels;
const API = loadWebui("api.js").SimosApi;

test("provider-subpage-is-registered-as-the-third-subpage", () => {
  const ids = P.DECISION_SUBPAGES.map((s) => s.id);
  assert.deepEqual(ids, ["view", "approval", "provider"]);
  assert.equal(P.DECISION_SUBPAGES[2].label, "Provider 配置");
});

test("provider-visibility-is-three-key-and-fail-closed", () => {
  // ★ 故意违规（m8 的杀点）：未知值不得兜成 view。
  assert.deepEqual(P.decisionSubpageVisibility("provider"), {
    view: false,
    approval: false,
    provider: true,
  });
  for (const bad of ["", "nope", null, undefined, 0, {}]) {
    assert.deepEqual(
      P.decisionSubpageVisibility(bad),
      { view: false, approval: false, provider: false },
      "未知子页必须三个都隐藏：" + JSON.stringify(bad)
    );
  }
});

test("provider-form-to-payload-rejects-missing-required-fields", () => {
  // ★ 故意违规（m9 的杀点）：缺 baseUrl / model / id 一律拒，**绝不填默认值**。
  assert.equal(P.providerFormToPayload({}).ok, false);
  assert.equal(
    P.providerFormToPayload({ id: "p1", model: "m" }).reason.includes("baseUrl"),
    true
  );
  assert.equal(
    P.providerFormToPayload({ id: "p1", baseUrl: "https://x/v1" }).reason.includes("model"),
    true
  );
  assert.equal(
    P.providerFormToPayload({ baseUrl: "https://x/v1", model: "m" }).reason.includes("id"),
    true
  );
  assert.equal(P.providerFormToPayload({ id: "  ", baseUrl: "a", model: "b" }).ok, false);
});

test("provider-form-to-payload-normalizes-and-omits-empty-optional-fields", () => {
  const result = P.providerFormToPayload({
    id: " deepseek ",
    baseUrl: " https://api.deepseek.com/v1 ",
    model: " deepseek-flash ",
    apiKey: "",
    readTimeoutMs: "",
  });
  assert.equal(result.ok, true);
  assert.deepEqual(result.payload, {
    id: "deepseek",
    baseUrl: "https://api.deepseek.com/v1",
    model: "deepseek-flash",
  });
});

test("provider-form-to-payload-keeps-api-key-out-of-routes-and-rejects-bad-timeout", () => {
  const result = P.providerFormToPayload({
    id: "p1",
    baseUrl: "https://x/v1",
    model: "m",
    apiKey: "sk-secret",
    readTimeoutMs: "120000",
  });
  assert.equal(result.ok, true);
  assert.equal(result.payload.apiKey, "sk-secret", "密钥只经 apiKey 字段交付（服务端写 keys.<id>）");
  assert.equal(result.payload.readTimeoutMs, 120000);
  for (const bad of ["0", "-1", "abc"]) {
    assert.equal(
      P.providerFormToPayload({ id: "p1", baseUrl: "https://x/v1", model: "m", readTimeoutMs: bad }).ok,
      false,
      "非法 readTimeoutMs 必须拒：" + bad
    );
  }
});

test("provider-form-to-payload-sanitizes-provider-id-characters", () => {
  // id 是配置树里的寻址段（llm.routes.<id>）⇒ 与 AgentLib 的 SAFE_NAME 同口径。
  assert.equal(P.providerFormToPayload({ id: "has.dot", baseUrl: "a", model: "b" }).ok, false);
  assert.equal(P.providerFormToPayload({ id: "has/slash", baseUrl: "a", model: "b" }).ok, false);
  assert.equal(P.providerFormToPayload({ id: "-lead", baseUrl: "a", model: "b" }).ok, false);
  assert.equal(P.providerFormToPayload({ id: "ok-id_1", baseUrl: "a", model: "b" }).ok, true);
});

test("provider-fields-masks-and-does-not-fabricate-missing-values", () => {
  const fields = P.providerFields({
    id: "p1",
    valid: true,
    baseUrl: "https://x/v1",
    model: "m",
    protocol: "openai_compatible",
    credentialsRef: "keys.p1",
    readTimeoutMs: 120000,
    keyConfigured: null,
  });
  assert.equal(fields.valid, true);
  const map = {};
  fields.rows.forEach((row) => {
    map[row[0]] = row[1];
  });
  assert.equal(map.baseUrl, "https://x/v1");
  assert.equal(map.keyConfigured, "—", "keyConfigured 取不到 ⇒ 显 —（不拿 false 顶替）");
  assert.equal(map.credentialsRef, "keys.p1");
  // 缺值显 —，不显示 undefined/null 字面量。
  const sparse = P.providerFields({ id: "p2", valid: true, keyConfigured: false });
  const sparseMap = {};
  sparse.rows.forEach((row) => {
    sparseMap[row[0]] = row[1];
  });
  assert.equal(sparseMap.model, "—");
  assert.equal(sparseMap.keyConfigured, "不可解析");
});

test("provider-fields-keeps-broken-entries-visible-with-error-code", () => {
  // ★ 故意违规（m7 的杀点）：写坏的条目**照样列出**（带 errorCode），绝不从列表里抹掉。
  const fields = P.providerFields({ id: "broken", valid: false, errorCode: "E_LLM_CONFIG_MISSING" });
  assert.equal(fields.valid, false);
  assert.equal(fields.errorCode, "E_LLM_CONFIG_MISSING");
  assert.deepEqual(fields.rows, []);
  assert.equal(P.providerFields({ id: "x", valid: false }).errorCode, "—");
});

test("provider-binding-payload-requires-both-fields", () => {
  assert.equal(P.providerBindingPayload("", "p1").ok, false);
  assert.equal(P.providerBindingPayload("dm1", "").reason.includes("providerId"), true);
  const result = P.providerBindingPayload(" dm1 ", " p1 ");
  assert.deepEqual(result, { ok: true, decisionMakerId: "dm1", providerId: "p1" });
});

test("provider-endpoints-are-exported-and-call-the-declared-paths", async () => {
  // C17：5 条新函数导出 + 打出的路径与端点清单逐条对表。
  for (const fn of ["llmProviders", "saveLlmProvider", "deleteLlmProvider", "testLlmProvider", "setDecisionMakerProvider"]) {
    assert.equal(typeof API[fn], "function", fn + " is exported");
  }
  const calls = [];
  const recordingFetch = (url, init) => {
    calls.push({ url, method: (init && init.method) || "GET" });
    return Promise.resolve({
      ok: true,
      status: 200,
      statusText: "OK",
      text: () => Promise.resolve("{}"),
    });
  };
  const api = loadWebui("api.js", { fetch: recordingFetch }).SimosApi;
  await api.llmProviders();
  await api.saveLlmProvider({ id: "p1" });
  await api.deleteLlmProvider("p1");
  await api.testLlmProvider("p1");
  await api.setDecisionMakerProvider("main", 1, "dm1", "p1");
  assert.deepEqual(
    calls.map((c) => c.method + " " + c.url).sort(),
    [
      "GET /api/llm/providers",
      "POST /api/llm/providers",
      "POST /api/llm/providers/delete",
      "POST /api/llm/providers/test",
      "POST /api/sd/set-decision-maker-provider",
    ]
  );
});

test("provider-panel-index-html-has-the-third-subpage-and-form-anchors", () => {
  const html = readWebui("index.html");
  // ★ 2026-09-23 用户裁定：子页选择器由 **radio 组**改成**横排 tab 条** ⇒ 第三个入口的锚点
  //   从 `value="provider"` 改成 `data-decision-tab="provider"`（**语义更新，不是削弱**：
  //   它仍钉住"Provider 配置必须是三个可切换子页之一"，只是承载控件换了形态）。
  assert.ok(html.includes('data-decision-tab="provider"'), "第三个 tab 必须在场");
  assert.ok(html.includes('data-decision-subpage="provider"'), "第三子页容器必须在场");
  for (const anchor of [
    "llm-provider-list",
    "llm-provider-id",
    "llm-provider-baseurl",
    "llm-provider-model",
    "llm-provider-apikey",
    "llm-provider-save",
    "llm-provider-test",
    "llm-binding-form",
    "llm-binding-save",
  ]) {
    assert.ok(html.includes('id="' + anchor + '"'), "缺少锚点：" + anchor);
  }
});

test("provider-panel-js-has-no-second-visibility-implementation", () => {
  // 与 decision-mode.test.cjs 同款：可见性只有一份实现（M12 第三波后位于 decisionmodel.js）。
  const panels = readWebui("panels.js");
  const model = readWebui("decisionmodel.js");
  const app = readWebui("app.js");
  assert.equal((panels.match(/function decisionSubpageVisibility\(/g) || []).length, 0);
  assert.equal((model.match(/function decisionSubpageVisibility\(/g) || []).length, 1);
  assert.equal((app.match(/function decisionSubpageVisibility\(/g) || []).length, 0);
});

test("provider-config-does-not-touch-localstorage-or-fabricate-a-default-provider", () => {
  const source = readWebui("panels.js");
  assert.equal(/localStorage/.test(source), false, "provider 配置不得落 localStorage（配置归服务端）");
  assert.equal((source.match(/providerFormToPayload\(/g) || []).length >= 1, true);
  assert.ok(fs.existsSync(path.join(__dirname, "provider-config.test.cjs")));
});
