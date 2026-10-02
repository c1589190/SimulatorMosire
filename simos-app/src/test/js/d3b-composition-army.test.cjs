// d3b-composition-army.test.cjs —— D2/D3b（2026-10-02）前端适配通用 Unit 人力/装备表 + 地图交战层 Army 优先。
//
// 覆盖两类生产改动（webui 生产文件由 D2/D3b 关账，本文件只加判据、不改生产）：
//   · D3b（D-006/R1）：`parseCompositionText`（map.js 纯函数，输入 `类型=整数`）与 `compositionText`（readout.js
//     渲染有序条目表）；工作台 unit 白名单 `unit.SetStrength` → `unit.SetComposition`；index.html 的输入框改名；
//   · D2（D-012）：`armyCombatForRenderer`（map.js 纯函数）与 `api.cachedArmyCombats`（真值来源 =
//     `GET /api/army/combats`，缺省世界当前 tick）；map.js 的交战层必须 **Army 优先、sd 仅空回退**。
//
// ★ 冻结夹具：期望值逐值写死，不拿被测函数自身输出当期望。
"use strict";

const fs = require("node:fs");
const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui, webuiDir } = require("./helpers/webui-loader.cjs");

const M = loadWebui("map.js").SimosMap;
const R = loadWebui("readout.js").SimosReadout;

// ── D3b：parseCompositionText ───────────────────────────────────────────────────────────

test("parseCompositionText-empty-is-an-empty-table", () => {
  assert.deepEqual(M.parseCompositionText(""), []);
  assert.deepEqual(M.parseCompositionText("   \n ; ,  "), []);
  assert.deepEqual(M.parseCompositionText(null), []);
});

test("parseCompositionText-keeps-write-order-across-mixed-separators", () => {
  assert.deepEqual(
    M.parseCompositionText("步兵=100, 骑兵=20；民夫=5\n马=2"),
    [
      { type: "步兵", amount: 100 },
      { type: "骑兵", amount: 20 },
      { type: "民夫", amount: 5 },
      { type: "马", amount: 2 },
    ],
    "顺序 = 写法顺序（有序条目表；Map 语义已删）"
  );
});

test("parseCompositionText-rejects-a-duplicate-type", () => {
  assert.throws(
    () => M.parseCompositionText("步枪=50, 步枪=10"),
    /重复类型/,
    "同一张表同 type 两条会让加/减值有歧义 ⇒ 必须响亮拒"
  );
});

test("parseCompositionText-rejects-negative-or-non-integer-amounts", () => {
  assert.throws(() => M.parseCompositionText("步枪=-1"), /数量 ≥ 0 的整数/);
  assert.throws(() => M.parseCompositionText("步枪=1.5"), /数量 ≥ 0 的整数/);
  assert.throws(() => M.parseCompositionText("步枪="), /数量 ≥ 0 的整数/);
});

test("parseCompositionText-rejects-missing-equals-and-keeps-no-legacy-map-semantics", () => {
  assert.throws(() => M.parseCompositionText("步枪:50"), /缺少「类型=整数」/);
  assert.throws(() => M.parseCompositionText("=50"), /缺少「类型=整数」/);
});

test("compositionText-renders-ordered-entries-and-flags-legacy-shapes", () => {
  assert.equal(R.compositionText([{ type: "步兵", amount: 100 }, { type: "骑兵", amount: 20 }]), "步兵=100；骑兵=20");
  assert.equal(R.compositionText([]), "（空）");
  assert.equal(R.compositionText(null), "（空）");
  assert.equal(R.compositionText(undefined), "（空）");
  assert.equal(
    R.compositionText({ 步枪: 50 }),
    "（未识别表形状）",
    "D-011/R4：不回落旧的 {类型:数量} map 语义"
  );
  assert.equal(R.equipmentText([{ type: "步枪", amount: 50 }]), "步枪=50");
});

// ── D2：Army 交战记录 → renderer 形状 + 取数端点 ────────────────────────────────────────

test("armyCombatForRenderer-maps-id-hex-and-participants-without-inventing-name-stage", () => {
  const record = {
    id: "c-1",
    tick: 7,
    hex: { q: 1, r: 2 },
    participants: ["u-1", "u-2"],
    text: "自然语言过程",
    losses: [{ unit: "u-1" }],
  };
  assert.deepEqual(M.armyCombatForRenderer(record), {
    combatId: "c-1",
    hex: { q: 1, r: 2 },
    participants: ["u-1", "u-2"],
  });
  assert.equal(
    Object.prototype.hasOwnProperty.call(M.armyCombatForRenderer(record), "name"),
    false,
    "Army 记录里没有 name/stage ⇒ 不得编造字段语义"
  );
});

test("armyCombatForRenderer-defaults-participants-and-filters-missing-hex", () => {
  assert.deepEqual(M.armyCombatForRenderer({ id: "c-2", hex: { q: 0, r: 0 } }), {
    combatId: "c-2",
    hex: { q: 0, r: 0 },
    participants: [],
  });
  assert.equal(M.armyCombatForRenderer(null), null);
  assert.equal(M.armyCombatForRenderer({ id: "c-3" }), null, "缺 hex ⇒ null（不编坐标）");
  assert.equal(M.armyCombatForRenderer({ id: "c-4", hex: { q: 1 } }), null, "hex 不完整 ⇒ null");
});

test("api-cachedArmyCombats-hits-the-relative-current-tick-endpoint-once", async () => {
  const calls = [];
  const recordingFetch = (url, init) => {
    calls.push({ url: url, method: init && init.method });
    return Promise.resolve({
      ok: true,
      status: 200,
      statusText: "OK",
      text: () => Promise.resolve(JSON.stringify({ combats: [{ id: "c-1" }] })),
    });
  };
  const api = loadWebui("api.js", { fetch: recordingFetch }).SimosApi;

  const first = await api.cachedArmyCombats(null);
  const second = await api.cachedArmyCombats(null);

  assert.deepEqual(first, { combats: [{ id: "c-1" }] });
  assert.deepEqual(second, { combats: [{ id: "c-1" }] });
  assert.deepEqual(
    calls,
    [{ url: "/api/army/combats", method: "GET" }],
    "同一 target 只发一次 GET；不传 tick（服务端缺省 = 世界当前 tick）"
  );
});

test("map-combat-layer-treats-army-as-truth-and-falls-back-to-sd-only-when-army-is-empty", () => {
  const map = readWebui("map.js");

  assert.match(
    map,
    /api\s*\.\s*cachedArmyCombats\(app\.target\(\)\)/,
    "交战层真值来源必须取 Army 记录"
  );
  assert.match(
    map,
    /armyCombats\.length\s*>\s*0\s*\?\s*armyCombats\.map\(armyCombatForRenderer\)/,
    "Army 非空 ⇒ 只能走 Army→renderer 映射"
  );
  assert.match(map, /:\s*sdCombats/, "Army 为空才允许回退旧 sd 列表（老世界兼容）");
  assert.ok(
    map.indexOf("armyCombatForRenderer: armyCombatForRenderer") >= 0,
    "纯函数必须挂出（门禁/e2e 直接断言）"
  );
});

// ── D3b：工作台/宿主页接线（静态） ────────────────────────────────────────────────────────

test("workbench-unit-editor-uses-parseCompositionText-and-sends-both-tables", () => {
  const editor = readWebui("map-uniteditor.js");
  const map = readWebui("map.js");

  assert.equal(
    /parseEquipmentText/.test(map + editor),
    false,
    "旧 parseEquipmentText 语义已删（D-011/R4 不背双轨）"
  );
  assert.match(
    editor,
    /parseCompositionText\(app\.byId\("unit-strength-manpower"\)\.value\)/,
    "改编制：人力走 parseCompositionText"
  );
  assert.match(
    editor,
    /manpower:\s*manpower,\s*equipment:\s*equipment,/,
    "改编制载荷必须同时给 manpower/equipment 两张有序表"
  );
  assert.match(
    editor,
    /app\.writeCommand\("unit\.SetComposition"/,
    "改编制命令必须换成 unit.SetComposition（旧 unit.SetStrength 已删）"
  );
  assert.match(
    editor,
    /parseCompositionText\(app\.byId\("unit-create-manpower"\)\.value\)/,
    "建单位：人力走 parseCompositionText"
  );
});

test("index-html-exposes-manpower-and-equipment-inputs-without-legacy-member-fields", () => {
  const html = fs.readFileSync(require("node:path").join(webuiDir(), "index.html"), "utf8");

  for (const id of [
    "unit-strength-manpower",
    "unit-strength-equipment",
    "unit-create-manpower",
    "unit-create-equipment",
  ]) {
    assert.ok(html.includes('id="' + id + '"'), "index.html 必须有输入框 " + id);
  }
  for (const legacy of ["unit-strength-member", "unit-create-member"]) {
    assert.equal(html.includes('id="' + legacy + '"'), false, "旧 member 输入框必须删除：" + legacy);
  }
});
