// provider-config.test.cjs —— M11「Provider 配置子页」的护栏。
//
// 三路证据：
//   ① 纯函数（panels.js）：providerFields 掩码投影（密钥只显示引用 + 三态可解析）、
//      providerFormToPayload（缺必填/坏 enum/坏 timeout ⇒ 拒，不造默认）、providerBindingPayload（非空白）。
//   ② 渲染流水线（真 renderProviderConfig + 替身 app/api）：列表逐条掩码、不含密钥值；两个下拉被填充。
//   ③ 静态：index.html 第三子页控件/容器/挂载点/按钮；api.js 导出 provider 读写函数。
// ★ 故意违规自证（变异靶子）：m7 子页未知值兜成 view ⇒ 可见性断言红；m8 缺 baseUrl 读成默认 ⇒ 表单断言红；
//   m5 view 回显密钥值 ⇒ 掩码断言红（若把密钥塞进夹具，列表文本会命中哨兵）。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

const P = loadWebui("panels.js").SimosPanels;

// ── 夹具 ──────────────────────────────────────────────────────────────
const SECRET_SENTINEL = "sk-page-must-never-show-this";

const PROVIDERS = [
  {
    id: "p-openai",
    baseUrl: "https://api.example.com",
    model: "gpt-4o-mini",
    timeoutMs: 30000,
    apiKeyRef: { kind: "ENV", ref: "OPENAI_API_KEY" },
    secretResolvable: true,
  },
  {
    id: "p-file",
    baseUrl: "http://127.0.0.1:8088",
    model: "local",
    timeoutMs: 5000,
    apiKeyRef: { kind: "FILE", ref: "/etc/simos/key" },
    secretResolvable: false,
  },
];

const MAKERS = [{ id: "dm-1" }, { id: "dm-2" }];

test("module-loads-provider-pure-functions", () => {
  assert.equal(typeof P.providerFields, "function");
  assert.equal(typeof P.providerFormToPayload, "function");
  assert.equal(typeof P.providerBindingPayload, "function");
  assert.equal(typeof P.renderProviderConfig, "function");
});

test("subpages-include-provider-third", () => {
  assert.deepEqual(
    P.DECISION_SUBPAGES.map((s) => s.id),
    ["view", "approval", "provider"]
  );
  assert.deepEqual(
    P.DECISION_SUBPAGES.map((s) => s.label),
    ["决策人查看", "审批", "Provider 配置"]
  );
});

test("visibility-has-three-keys-exactly-one-true", () => {
  assert.deepEqual(P.decisionSubpageVisibility("view"), {
    view: true,
    approval: false,
    provider: false,
  });
  assert.deepEqual(P.decisionSubpageVisibility("approval"), {
    view: false,
    approval: true,
    provider: false,
  });
  assert.deepEqual(P.decisionSubpageVisibility("provider"), {
    view: false,
    approval: false,
    provider: true,
  });
  // ★ m7 的杀点：未知子页必须三个全假（不兜成 view）。
  for (const bad of ["", "nope", null, undefined, 7, {}]) {
    assert.deepEqual(
      P.decisionSubpageVisibility(bad),
      { view: false, approval: false, provider: false },
      "未知子页必须全隐藏：" + JSON.stringify(bad)
    );
  }
  ["view", "approval", "provider"].forEach((id) => {
    const v = P.decisionSubpageVisibility(id);
    assert.equal(Number(v.view) + Number(v.approval) + Number(v.provider), 1, id + " 恰一个可见");
  });
});

test("provider-fields-mask-and-tri-state", () => {
  const f = P.providerFields(PROVIDERS[0]);
  assert.equal(f.id, "p-openai");
  assert.equal(f.baseUrl, "https://api.example.com");
  assert.equal(f.model, "gpt-4o-mini");
  assert.equal(f.keyKind, "ENV");
  assert.equal(f.keyRef, "OPENAI_API_KEY");
  assert.equal(f.timeoutMs, "30000");
  assert.equal(f.resolvable, "可解析");

  assert.equal(P.providerFields(PROVIDERS[1]).resolvable, "不可解析");
  // 三态：取不到 => 「未知」，绝不读成 true/false。
  assert.equal(P.providerFields({ id: "x" }).resolvable, "未知");
  assert.equal(P.providerFields({ id: "x" }).baseUrl, "—");
  assert.equal(P.providerFields({ id: "x" }).keyRef, "—");
  assert.equal(P.providerFields(null).id, "—");
});

test("provider-form-requires-id-baseurl-model-keyref", () => {
  const base = { id: "p", baseUrl: "http://x", model: "m", apiKeyRefKind: "ENV", apiKeyRef: "K" };
  assert.equal(P.providerFormToPayload(base).ok, true);
  ["id", "baseUrl", "model", "apiKeyRef"].forEach((field) => {
    const form = Object.assign({}, base);
    form[field] = "   ";
    const result = P.providerFormToPayload(form);
    assert.equal(result.ok, false, field + " 空白必须被拒");
    assert.ok(result.error.includes(field) || result.error.includes("必填"), result.error);
  });
});

test("provider-form-normalizes-key-kind-and-omits-empty-timeout", () => {
  const result = P.providerFormToPayload({
    id: " p ",
    baseUrl: " http://x ",
    model: " m ",
    apiKeyRefKind: "env",
    apiKeyRef: " K ",
    timeoutMs: "",
  });
  assert.equal(result.ok, true);
  assert.deepEqual(result.value, {
    id: "p",
    baseUrl: "http://x",
    model: "m",
    apiKeyRefKind: "ENV",
    apiKeyRef: "K",
  });
  assert.equal(Object.prototype.hasOwnProperty.call(result.value, "timeoutMs"), false);
});

test("provider-form-rejects-bad-key-kind-and-timeout", () => {
  const base = { id: "p", baseUrl: "http://x", model: "m", apiKeyRef: "K" };
  assert.equal(P.providerFormToPayload(Object.assign({}, base, { apiKeyRefKind: "PLAIN" })).ok, false);
  ["abc", "0", "-5", "1.5"].forEach((bad) => {
    const result = P.providerFormToPayload(Object.assign({}, base, { apiKeyRefKind: "ENV", timeoutMs: bad }));
    assert.equal(result.ok, false, "timeoutMs=" + bad + " 必须被拒");
  });
  const good = P.providerFormToPayload(Object.assign({}, base, { apiKeyRefKind: "FILE", timeoutMs: "1500" }));
  assert.equal(good.ok, true);
  assert.equal(good.value.timeoutMs, 1500);
});

test("provider-binding-payload-requires-non-blank", () => {
  assert.deepEqual(P.providerBindingPayload("main", 3, "dm-1", "p-1"), {
    ok: true,
    value: { branch: "main", expectedRevision: 3, decisionMakerId: "dm-1", providerId: "p-1" },
  });
  for (const bad of [["", "p"], ["dm", ""], [null, "p"], ["dm", undefined]]) {
    const result = P.providerBindingPayload("main", 3, bad[0], bad[1]);
    assert.equal(result.ok, false);
  }
});

test("index-html-has-provider-subpage-and-mounts", () => {
  const html = readWebui("index.html");
  assert.ok(html.includes('value="provider"'), "第三子页 radio 值必须在");
  assert.ok(html.includes("Provider 配置"), "第三子页标签必须在");
  assert.ok(html.includes('data-decision-subpage="provider"'), "provider 子页容器必须在");
  assert.ok(html.includes('id="provider-list-mount"'), "provider 列表挂载点必须在");
  assert.ok(html.includes('id="provider-save"'), "保存按钮必须在");
  assert.ok(html.includes('id="provider-bind-submit"'), "绑定按钮必须在");
  assert.ok(html.includes('id="provider-bind-maker"'), "决策人下拉必须在");
  assert.ok(html.includes('id="provider-keykind"'), "密钥引用种类选择必须在");
  // 密钥纪律：页面提示只存引用。
  assert.ok(html.includes("密钥只存引用"), "页面必须说明密钥只存引用");
});

test("api-js-exports-provider-functions", () => {
  const api = loadWebui("api.js").SimosApi;
  ["llmProviders", "saveLlmProvider", "deleteLlmProvider", "testLlmProvider", "setDecisionMakerProvider"].forEach(
    (fn) => assert.equal(typeof api[fn], "function", fn + " is exported")
  );
});

test("panels-provider-section-never-echoes-secret-values-and-uses-narrow-ends", () => {
  const source = readWebui("panels.js");
  assert.equal(source.includes("apiKeyRef.value"), false, "不得投影密钥值");
  assert.ok(source.includes("setDecisionMakerProvider("), "绑定必须经 api.setDecisionMakerProvider");
  assert.equal(source.includes("writeCommand("), false, "panels.js 不得发通用命令写");
  // ★ 子页 radio 必须被接线（否则配置页点不到）；接线函数只对 checked 的触发切换（fail-closed）。
  const app = readWebui("app.js");
  assert.ok(app.includes("mountDecisionSubpages"), "app.js 必须接线决策子页 radio");
  assert.ok(app.includes('input[name="decision-subpage"]'), "接线必须匹配子页 radio 选择器");
});

// ── 渲染流水线夹具（真 renderProviderConfig；替身 app/api）───────────────
function renderHarness(providers, makers) {
  const nodes = {};
  const calls = { saved: [], deleted: [], tested: [], bound: [] };
  const state = { mode: "decision", decisionSubpage: "provider" };
  function fakeNode(tag) {
    return {
      tag: tag,
      attrs: {},
      children: [],
      text: "",
      value: "",
      disabled: false,
      setAttribute(k, v) {
        this.attrs[k] = v;
      },
      appendChild(c) {
        this.children.push(c);
      },
      addEventListener() {},
    };
  }
  const appStub = {
    el(tag, attrs, children) {
      const node = fakeNode(tag);
      if (attrs) {
        Object.keys(attrs).forEach((k) => {
          if (k === "text") {
            node.text = attrs[k];
          } else {
            node.attrs[k] = attrs[k];
          }
        });
      }
      (children || []).forEach((c) => node.appendChild(c));
      return node;
    },
    clear(node) {
      node.children = [];
      return node;
    },
    byId(id) {
      if (!nodes[id]) {
        nodes[id] = fakeNode("div");
      }
      return nodes[id];
    },
    statusMessage(node, message, tone) {
      node.text = message;
      node.tone = tone;
    },
    text(value) {
      return value === null || value === undefined ? "—" : String(value);
    },
    target() {
      return { branch: "main", revision: null };
    },
    getState() {
      return state;
    },
    invalidateState() {},
    onStateChange() {},
  };
  const apiStub = {
    llmProviders: () => Promise.resolve({ providers: providers }),
    cachedDecisionMakers: () => Promise.resolve({ decisionMakers: makers }),
    saveLlmProvider: (p) => {
      calls.saved.push(p);
      return Promise.resolve({ provider: p });
    },
    deleteLlmProvider: (id) => {
      calls.deleted.push(id);
      return Promise.resolve({ deleted: true });
    },
    testLlmProvider: (id) => {
      calls.tested.push(id);
      return Promise.resolve({ ok: true, detail: "ok" });
    },
    setDecisionMakerProvider: (branch, rev, dm, p) => {
      calls.bound.push({ branch, rev, dm, p });
      return Promise.resolve({ result: "committed" });
    },
  };
  const P2 = loadWebui("panels.js", { SimosApp: appStub, SimosApi: apiStub }).SimosPanels;
  return { P2: P2, nodes: nodes, app: appStub, calls: calls, state: state };
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

function flattenText(node) {
  let out = node.text || "";
  (node.children || []).forEach((c) => {
    out += " " + flattenText(c);
  });
  return out;
}

test("render-provider-list-masks-secret-and-fills-selects", async () => {
  const h = renderHarness(PROVIDERS, MAKERS);
  h.P2.renderProviderConfig(h.state, true);
  await flush();
  await flush();
  const rows = h.nodes["provider-list-mount"].children.filter((n) => n.attrs["data-provider-id"]);
  assert.equal(rows.length, 2, "两条 provider 各一行");
  const text = rows.map(flattenText).join(" | ");
  assert.ok(text.includes("ENV:OPENAI_API_KEY"), text);
  assert.ok(text.includes("可解析"), text);
  assert.ok(text.includes("不可解析"), text);
  assert.equal(text.includes(SECRET_SENTINEL), false, "列表不得出现任何密钥值");
  // 两个下拉被填充（含占位 + 每条）。
  assert.equal(h.nodes["provider-bind-maker"].children.length, MAKERS.length + 1);
  assert.equal(h.nodes["provider-bind-provider"].children.length, PROVIDERS.length + 1);
});

test("render-provider-list-empty-state-is-explicit", async () => {
  const h = renderHarness([], []);
  h.P2.renderProviderConfig(h.state, true);
  await flush();
  await flush();
  const text = flattenText(h.nodes["provider-list-mount"]);
  assert.ok(text.includes("尚未配置 provider"), text);
});
