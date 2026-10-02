// modes.test.cjs —— 六模式写权限白名单（M8 T7 / spec §三 Q7；T7 加「决策」）。纯函数，无 DOM/IO。
// ★ 冻结夹具：每个模式的允许/拒绝逐条写死，不拿被测函数自身输出当期望。
"use strict";

const fs = require("node:fs");
const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, webuiDir, readWebui } = require("./helpers/webui-loader.cjs");

const M = loadWebui("modes.js").SimosModes;

test("module-loads", () => {
  assert.equal(typeof M.isWriteAllowed, "function");
});

test("mode-ids-order", () => {
  assert.deepEqual(M.modeIds(), ["view", "region", "map-edit", "region-edit", "unit", "decision"]);
});

test("mode-labels", () => {
  assert.equal(M.modeLabel("view"), "常规");
  assert.equal(M.modeLabel("region"), "区域查看");
  assert.equal(M.modeLabel("map-edit"), "地图编辑");
  assert.equal(M.modeLabel("region-edit"), "区域编辑");
  assert.equal(M.modeLabel("unit"), "单位移动编辑");
  assert.equal(M.modeLabel("decision"), "决策");
});

test("mode-label-unknown-passthrough", () => {
  assert.equal(M.modeLabel("nope"), "nope");
});

test("view-allows-no-write", () => {
  assert.deepEqual(M.allowedWrites("view"), []);
  assert.equal(M.isWriteAllowed("view", "map.SetTerrain"), false);
  assert.equal(M.isWriteAllowed("view", "unit.PlanRoute"), false);
});

test("region-allows-no-write", () => {
  assert.deepEqual(M.allowedWrites("region"), []);
  assert.equal(M.isWriteAllowed("region", "map.SetTerrain"), false);
  assert.equal(M.isWriteAllowed("region", "map.UpdateRegion"), false);
});

test("map-edit-allows-exactly-four-writes", () => {
  assert.deepEqual(M.allowedWrites("map-edit"), [
    "map.SetTerrain",
    "map.SetEdge",
    "map.RandomizeRegion",
    "map.UpdateRegion",
  ]);
  assert.equal(M.isWriteAllowed("map-edit", "map.SetTerrain"), true);
  assert.equal(M.isWriteAllowed("map-edit", "map.SetEdge"), true);
  assert.equal(M.isWriteAllowed("map-edit", "map.RandomizeRegion"), true);
  assert.equal(M.isWriteAllowed("map-edit", "map.UpdateRegion"), true);
  assert.equal(M.isWriteAllowed("map-edit", "map.CreateRegion"), false);
  assert.equal(M.isWriteAllowed("map-edit", "unit.PlanRoute"), false);
});

test("region-edit-allows-three-writes", () => {
  assert.deepEqual(M.allowedWrites("region-edit"), [
    "map.CreateRegion",
    "map.UpdateRegion",
    "map.DeleteRegion",
  ]);
  assert.equal(M.isWriteAllowed("region-edit", "map.SetTerrain"), false);
});

test("unit-allows-route-and-editor-writes", () => {
  assert.equal(M.isWriteAllowed("unit", "unit.PlanRoute"), true);
  assert.equal(M.isWriteAllowed("unit", "unit.CancelRoute"), true);
  assert.equal(M.isWriteAllowed("unit", "unit.ReparentUnit"), true);
  // ★ D3b（2026-10-02 / D3a 命令 rename）：整表复写命令是 unit.SetComposition；旧 unit.SetStrength
  //   已按 D-011/R4 删除，白名单不得再放行它（放行 = 工作台发出一条已不存在的命令）。
  assert.equal(M.isWriteAllowed("unit", "unit.SetComposition"), true);
  assert.equal(M.isWriteAllowed("unit", "unit.SetStrength"), false);
  assert.equal(M.isWriteAllowed("unit", "unit.DisbandUnit"), true);
  assert.equal(M.isWriteAllowed("unit", "unit.CreateUnit"), true);
  assert.equal(
    M.isWriteAllowed("unit", "unit.AttachUnit"),
    true,
    "「加入编队」是工作台真写入口（偏移式加入：原地不动 + 进入跟随，不再要求同格）"
  );
  assert.equal(M.isWriteAllowed("unit", "unit.DetachUnit"), true, "「脱离编队」是工作台真写入口");
  assert.equal(M.isWriteAllowed("unit", "map.SetTerrain"), false);
  // ★ 白名单逐条冻结（八条真写入口；名单加/减一条都在这里红）。
  assert.deepEqual(M.allowedWrites("unit"), [
    "unit.PlanRoute",
    "unit.CancelRoute",
    "unit.ReparentUnit",
    "unit.SetComposition",
    "unit.DisbandUnit",
    "unit.CreateUnit",
    "unit.AttachUnit",
    "unit.DetachUnit",
  ]);
});

test("decision-allows-exactly-start-decision", () => {
  // ★★ T10（spec §四.2，D5 已裁"新命令"）：决策模式的命令写；2026-09-23 起**三条**
  //   （+ sd.RunDecision「让它跑一轮」走 POST /api/sd/run-decision、+ sd.ResetDecisionMakerConversation
  //   「上下文重置」走 POST /api/sd/reset-decision-maker-conversation）。
  //   审批的「批准/驳回」打 POST /api/approvals/{id}（审批裁决，非命令写）⇒ **不进**本表。
  assert.deepEqual(M.allowedWrites("decision"), [
    "sd.StartDecision",
    "sd.RunDecision",
    "sd.ResetDecisionMakerConversation",
  ]);
  assert.equal(M.isWriteAllowed("decision", "sd.StartDecision"), true);
  assert.equal(
    M.isWriteAllowed("decision", "sd.ResetDecisionMakerConversation"),
    true,
    "「上下文重置」是工作台真发出的写（窄端点，命令类型服务端写死）"
  );
  for (const type of [
    "sd.IssueDirective",
    "sd.SubmitVerdict",
    "sd.SetDecisionMakerAccess",
    "map.SetTerrain",
    "unit.PlanRoute",
  ]) {
    assert.equal(M.isWriteAllowed("decision", type), false, "决策模式不得允许 " + type);
  }
});

test("fail-closed-on-unknown-mode-and-empty-type", () => {
  assert.equal(M.isWriteAllowed("nope", "map.SetTerrain"), false);
  assert.equal(M.isWriteAllowed("view", ""), false);
  assert.equal(M.isWriteAllowed("view", null), false);
  assert.equal(M.isWriteAllowed("view", undefined), false);
  assert.equal(M.isWriteAllowed(undefined, "map.SetTerrain"), false);
  assert.equal(M.isWriteAllowed(null, "map.SetTerrain"), false);
});

test("allowedWrites-returns-a-snapshot", () => {
  const copy = M.allowedWrites("map-edit");
  copy.push("unit.CreateUnit");
  assert.equal(M.allowedWrites("map-edit").includes("unit.CreateUnit"), false);
  assert.equal(M.isWriteAllowed("map-edit", "unit.CreateUnit"), false);
});

// ── T10-i：白名单必须覆盖**工作台实际发出的**全部写命令（静态扫描的派生式断言）──────
// 依据（只读实测）：工作台唯一写路径是 app.writeCommand（app.js），map.js 里的写都经它；
// 本扫描从 webui/*.js 抽出全部 `writeCommand("<type>"` 字面量，逐条断言"至少一个模式放行"。
// ⇒ 新增一条工作台写命令却忘了加白名单 ⇒ 这里红（把 isWriteAllowed 的 fail-closed 静默拒提前暴露）。
// ★ 扫描的是**源码原文**（含注释）：注释里提到一条 writeCommand 调用会被当成真调用——这是 fail-closed
//   方向的误报（宁可多报），当前 0 例。
test("workbench-write-calls-are-all-whitelisted", () => {
  const calls = [];
  for (const name of fs.readdirSync(webuiDir()).filter((n) => n.endsWith(".js"))) {
    const source = readWebui(name);
    const re = /writeCommand\(\s*"([^"]+)"/g;
    let match;
    while ((match = re.exec(source)) !== null) {
      calls.push({ file: name, type: match[1] });
    }
  }
  assert.ok(
    calls.length > 0,
    "没扫到任何 writeCommand 字面量 —— 先怀疑扫描器（路径/正则），别先怀疑白名单"
  );
  const allowed = new Set();
  M.modeIds().forEach((id) => M.allowedWrites(id).forEach((type) => allowed.add(type)));
  const denied = calls.filter((call) => !allowed.has(call.type));
  assert.deepEqual(
    denied,
    [],
    "工作台写命令未进任何模式的 writes（fail-closed 会静默拒）: " + JSON.stringify(denied)
  );
});

test("whitelist-entries-are-well-formed-command-types", () => {
  const shape = /^[a-z][a-z0-9-]*\.[A-Z][A-Za-z0-9]*$/;
  M.modeIds().forEach((id) => {
    M.allowedWrites(id).forEach((type) => {
      assert.ok(shape.test(type), id + " 的白名单项形状不合法: " + type);
    });
  });
});
