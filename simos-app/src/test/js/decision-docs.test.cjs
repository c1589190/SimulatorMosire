// decision-docs.test.cjs —— Docs 系统（2026-09-23）：文档子页的三个纯函数。
//
// 判据：
//   D1 查询路径**必须**带 as（文档只有"以某决策人视角读"这一种语义，没有 GM 全量口径）；
//      `docId` 与 `limit` 不并用（给了 docId 就是精确取那一篇，服务端此时忽略 limit）；
//   D2 正文解析**尽力而为**：`value` 按约定是 JSON 文本 ⇒ 能解析就投影 title/body/subject，
//      解析不了**不报错、不瞎猜**（parseError + 原文照留）；
//   D3 视图把服务端的 `note`（"明确没有"）原样带出去，不另编一句（否则两端文案会漂）；
//   D4 两轴（指派 / 归属）**都显**：用户得看得出"这篇为什么我看得到"。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui } = require("./helpers/webui-loader.cjs");

const P = loadWebui("panels.js").SimosPanels;

test("docs-request-needs-an-actor", () => {
  // ★ 故意违规：缺 as 时**不许**编一个默认决策人——那是"以谁的视角"的唯一依据。
  for (const bad of [{}, { as: "" }, { as: "   " }, { as: null }]) {
    const req = P.decisionDocsRequest(bad);
    assert.equal(req.ok, false, "不该接受 " + JSON.stringify(bad));
    assert.equal(typeof req.reason, "string");
  }
});

test("docs-request-with-a-doc-id-is-exact-and-carries-no-limit", () => {
  const req = P.decisionDocsRequest({ as: "dm-a", docId: "alpha-brief", limit: "999" });
  assert.equal(req.ok, true);
  assert.equal(req.path, "/sd/decision-docs?as=dm-a&docId=alpha-brief");
  // ★ limit 被忽略：按 id 精确定位不能被截断（服务端也按这条口径）。
  assert.equal(req.path.includes("limit"), false);
});

test("docs-request-escapes-the-actor-and-defaults-the-limit", () => {
  assert.equal(P.decisionDocsRequest({ as: "dm a/b" }).path, "/sd/decision-docs?as=dm%20a%2Fb&limit=20");
  assert.equal(P.decisionDocsRequest({ as: "dm-a", limit: "5" }).path, "/sd/decision-docs?as=dm-a&limit=5");
});

test("docs-request-rejects-an-out-of-range-limit", () => {
  // ★ 契约是"服务端报错、不截断" ⇒ 前端先拦，让用户当场看见而不是收到一个语焉不详的 4xx。
  for (const bad of ["0", "-1", "201", "abc"]) {
    const req = P.decisionDocsRequest({ as: "dm-a", limit: bad });
    assert.equal(req.ok, false, "limit=" + bad + " 应被拒");
  }
});

test("doc-entry-projects-title-body-and-subject", () => {
  const entry = P.decisionDocEntry({
    docId: "alpha-brief",
    id: "sd:doc.alpha-brief#0",
    tick: 3,
    tags: ["dm-a"],
    affiliations: [{ kind: "nation", id: "n1" }],
    key: "doc",
    value: '{"title":"Alpha 简报","body":"第一行","subject":{"kind":"region","id":"r1"}}',
    note: "交接用",
    at: { branch: "main", revision: 7 },
  });

  assert.equal(entry.docId, "alpha-brief");
  assert.equal(entry.title, "Alpha 简报");
  assert.equal(entry.bodyText, "第一行");
  // ★ `region` 不在 AFFILIATION_LABELS 里 ⇒ 显原文（不编中文名——仓里没有那张表的出处）。
  assert.equal(entry.subject, "region：r1");
  assert.equal(entry.note, "交接用");
  assert.equal(entry.parseError, null);
  assert.equal(entry.atBranch, "main");
  assert.equal(entry.atRevision, "7");
});

test("doc-entry-shows-both-visibility-axes", () => {
  const entry = P.decisionDocEntry({
    docId: "d1",
    tags: ["dm-a", "dm-b"],
    affiliations: [{ kind: "nation", id: "n1" }, { kind: "army", id: "a1" }],
    value: "{}",
  });
  // ★ 用户得看得出"这篇为什么我看得到"：两条轴都要显。
  assert.deepEqual(entry.tags, ["dm-a", "dm-b"]);
  assert.deepEqual(entry.affiliations, ["国家：n1", "军队：a1"]);
});

test("doc-entry-keeps-the-raw-text-when-the-value-is-not-json", () => {
  const entry = P.decisionDocEntry({ docId: "d1", value: "手写的一整段设定（不是 JSON）" });

  // ★ 不假装能解析：正文 schema 是**约定不是契约**（SdInfoEntry.value 只保证标量往返）。
  assert.equal(entry.title, null);
  assert.equal(entry.bodyText, null);
  assert.equal(entry.raw, "手写的一整段设定（不是 JSON）");
  assert.equal(typeof entry.parseError, "string");
  assert.equal(entry.parseError.includes("不是合法 JSON"), true);
});

test("doc-entry-handles-a-missing-value-without-throwing", () => {
  for (const value of [null, undefined, "", "   "]) {
    const entry = P.decisionDocEntry({ docId: "d1", value: value });
    assert.equal(entry.parseError, "正文为空（value 缺失）。");
    assert.equal(entry.raw, "");
  }
});

test("docs-view-maps-rows-and-carries-the-server-note", () => {
  const view = P.decisionDocsView({
    docs: [{ docId: "d1", value: "{}" }, { docId: "d2", value: "{}" }],
    count: 2,
  });
  assert.deepEqual(
    view.entries.map((e) => e.docId),
    ["d1", "d2"]
  );
  assert.equal(view.count, 2);
  assert.equal(view.empty, false);
  assert.equal(view.note, null);
});

test("docs-view-is-empty-and-keeps-the-servers-wording", () => {
  const view = P.decisionDocsView({ docs: [], count: 0, note: "没有可查看的文档（这个 docId 不存在，或它对该决策人不可见——两者返回同一个回答）" });

  assert.equal(view.empty, true);
  assert.deepEqual(view.entries, []);
  // ★ 原样带出去：前端另编一句会让两端文案漂。
  assert.equal(view.note.includes("两者返回同一个回答"), true);
});

test("docs-view-tolerates-a-malformed-body", () => {
  const view = P.decisionDocsView(null);
  assert.equal(view.empty, true);
  assert.deepEqual(view.entries, []);
  assert.equal(view.count, 0);
});
