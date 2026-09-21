// write-allowlist.test.cjs —— 前端写路径 allowlist：只允许 {/api/command,/api/advance,/api/fork}。
//
// 两路证据：
//   ① 静态：扫 api.js 的 postJson 端点 == allowlist；其它 webui 资产不得有写调用。
//   ② 动态：真加载 api.js（记录型 fetch），三个写函数实际打出的 POST 端点逐一对表。
// ★ 故意违规自证：扫描器对注入的未声明端点 /api/evil 必须报违规；若把违规判定改成永真，本文件必红。
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui, webuiDir } = require("./helpers/webui-loader.cjs");

const API_BASE = "/api";
// 命令写面（唯一三条，M5 起不变）。
const ALLOWED = ["/api/advance", "/api/command", "/api/fork"];
// ★ T7：审批裁决面（**非命令写**）——逐条精确列出，唯一一条。
//   决策模式的「批准/驳回」打 POST /api/approvals/{id}；它不走 Command → ChangeSet → Revision，
//   故**不进** modes.js 的命令白名单（那只管命令类型），只在这里显式放行。
const ALLOWED_APPROVAL_PREFIX = "/api/approvals/";
// 扫描器看到的是 **api.js 里的字面量**（`postJson("/approvals/" + …)` ⇒ `/api/approvals/`）
// ⇒ 声明集合是这四条；运行期 URL 由 isAllowedWrite 再要求"前缀 + 非空 id"。
const DECLARED_WRITES = ["/api/advance", "/api/approvals/", "/api/command", "/api/fork"];
const WRITE_FUNCTIONS = ["submitCommand", "advance", "fork", "approve"];

function scanPostEndpoints(source) {
  const out = new Set();
  const re = /postJson\(\s*"([^"]+)"/g;
  let match;
  while ((match = re.exec(source)) !== null) {
    out.add(API_BASE + match[1]);
  }
  return Array.from(out).sort();
}

/** 扫描出的**声明**端点是否在册（逐字相等，无通配）。 */
function violations(endpoints) {
  return endpoints.filter((endpoint) => !DECLARED_WRITES.includes(endpoint)).sort();
}

/** 运行期写 URL 是否放行：命令写**逐字相等**；审批写 = **前缀 + 非空 id**（前缀精确到末尾斜杠）。 */
function isAllowedWrite(url) {
  if (ALLOWED.includes(url)) {
    return true;
  }
  return url.startsWith(ALLOWED_APPROVAL_PREFIX) && url.length > ALLOWED_APPROVAL_PREFIX.length;
}

function webuiJsFiles() {
  return fs
    .readdirSync(webuiDir())
    .filter((name) => name.endsWith(".js"))
    .sort();
}

test("api.js-declares-exactly-the-allowed-write-endpoints", () => {
  const found = scanPostEndpoints(readWebui("api.js"));
  assert.equal(found.length, 4, "扫描必须非空且恰四条（3 命令 + 1 审批）：" + JSON.stringify(found));
  assert.deepEqual(found, DECLARED_WRITES);
  assert.deepEqual(violations(found), []);
});

test("api.js-exports-the-command-and-approval-write-functions", () => {
  const api = loadWebui("api.js").SimosApi;
  for (const fn of WRITE_FUNCTIONS) {
    assert.equal(typeof api[fn], "function", fn + " is exported");
  }
});

test("no-other-webui-asset-has-a-write-call", () => {
  const offenders = [];
  for (const name of webuiJsFiles()) {
    if (name === "api.js") {
      continue;
    }
    const source = readWebui(name);
    if (/postJson\(/.test(source) || /method:\s*["']POST["']/.test(source)) {
      offenders.push(name);
    }
  }
  assert.deepEqual(offenders, []);
});

test("scanner-has-teeth-on-undeclared-endpoint", () => {
  const tampered = readWebui("api.js").replace(
    'postJson("/command", envelope);',
    'postJson("/command", envelope);\n  postJson("/evil", payload);'
  );
  const found = scanPostEndpoints(tampered);
  assert.ok(found.includes("/api/evil"), "未声明端点必须被扫出：" + JSON.stringify(found));
  assert.deepEqual(violations(found), ["/api/evil"]);
  // ★ 审批前缀必须**精确**（不是通配）：无 id、错前缀、多字母都不放行。
  assert.equal(isAllowedWrite("/api/approvals"), false, "无末尾斜杠不放行");
  assert.equal(isAllowedWrite("/api/approvals/"), false, "只有斜杠、没有 id 不放行");
  assert.equal(isAllowedWrite("/api/approvalsx/pm-1"), false, "错前缀不放行");
  assert.equal(isAllowedWrite("/api/approvals/pm-1"), true, "恰是前缀 + 非空 id ⇒ 放行");
});

test("scanner-detects-a-missing-declared-endpoint", () => {
  const tampered = readWebui("api.js").replace('return postJson("/advance", body);', "return null;");
  const found = scanPostEndpoints(tampered);
  assert.equal(found.includes("/api/advance"), false);
  assert.notDeepEqual(found, ALLOWED);
});

test("dynamic-write-functions-hit-only-allowed-endpoints", async () => {
  const calls = [];
  const recordingFetch = (url, init) => {
    calls.push({ url, method: init && init.method });
    return Promise.resolve({
      ok: true,
      status: 200,
      statusText: "OK",
      text: () => Promise.resolve("{}"),
    });
  };
  const api = loadWebui("api.js", { fetch: recordingFetch }).SimosApi;

  await api.submitCommand({ type: "map.SetTerrain", payloadJson: "{}" });
  await api.advance("main", 0, 1, 2);
  await api.fork("main", 0, "b2");
  await api.state();
  await api.timeline("main");
  await api.mapOverview();
  await api.mapHex(1, 2);
  await api.units();
  await api.unit("u-1");
  await api.population(0, 0);
  await api.mapRegion("r1");
  await api.mapPath("u-1", 1, 1);
  await api.resolve("unit:u-1");
  await api.facets("unit:u-1");
  await api.approvals();
  await api.decisionMakers();
  await api.decisionMaker("dm-1");
  await api.approve("pm-1", "approve", "once");

  const posts = calls.filter((c) => c.method === "POST").map((c) => c.url).sort();
  assert.deepEqual(
    posts,
    ["/api/advance", "/api/approvals/pm-1", "/api/command", "/api/fork"],
    "写函数只能打命令 allowlist + 审批那一条"
  );
  for (const url of posts) {
    assert.ok(isAllowedWrite(url), "写 url 必须被 allowlist 放行：" + url);
  }
  const gets = calls.filter((c) => !c.method || c.method === "GET");
  assert.ok(gets.length >= 10, "只读调用应被记录（非空自证）");
  for (const call of gets) {
    assert.ok(call.url.startsWith(API_BASE + "/"), "GET url " + call.url);
  }
});

test("module-path-resolution-is-non-empty", () => {
  assert.ok(fs.existsSync(path.join(webuiDir(), "api.js")));
  assert.ok(webuiJsFiles().length >= 8);
});
