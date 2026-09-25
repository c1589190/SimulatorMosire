// economy-panel.test.cjs —— R2a（2026-09-25）：hex 详情面板的「经济」读数段 + 取数层接线。
//
// 两条护栏：
//   ① **读数逐值来自服务端**（`GET /api/economy/hex`，与 MCP 的 `simos.economy.hex` 同一份视图）：
//      `panels.js` 的纯函数 `economyReadoutRows` 只做展示投影，**不做二次解释**（GUI 不造第二份真相）。
//   ② **取数走 `api.cachedEconomyHex`**：面板不自己拼 URL、不自己 fetch（拼错不报错、只是永远读旧/读空）。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui, readWebui } = require("./helpers/webui-loader.cjs");

const P = loadWebui("panels.js").SimosPanels;

/** 服务端一份已激活、一格一产业一阶层行的读数（值都是刻意选的、非平凡数）。 */
const ECONOMY = {
  q: 1,
  r: 1,
  activated: true,
  population: 100,
  laborMilli: 58000,
  landMilliMu: 1000000,
  goods: { grain: 498000 },
  money: 12,
  debtCount: 0,
  debtPrincipal: 0,
  industries: [
    {
      id: "farm@1_1",
      name: "农业",
      regime: "feudal",
      cycleDays: 120,
      progressDays: 33,
      allocation: { kind: "split", meansWeightPerMille: 700, laborWeightPerMille: 300 },
      slots: [{ id: "peasant", name: "贫农", laborParticipationPerMille: 950 }],
      classes: [
        {
          slot: "peasant",
          population: 100,
          laborMilli: 58000,
          participationPerMille: 950,
          landMilliMu: 1000000,
          goods: { grain: 498000 },
          money: 12,
          debts: [],
          naturalNeeds: { grain: 8300 },
          effectiveDemand: {},
        },
      ],
    },
  ],
};

function row(rows, label) {
  const found = rows.find((r) => r.label === label);
  assert.ok(found, "缺少行 " + label + "（实际：" + rows.map((r) => r.label).join("、") + "）");
  return found;
}

test("activated-economy-yields-per-value-rows", () => {
  const rows = P.economyReadoutRows(ECONOMY);

  assert.equal(row(rows, "经济人口").value, 100);
  assert.equal(row(rows, "有效劳动").value, 58000);
  assert.equal(row(rows, "土地").value, "1000000 千分亩");
  assert.equal(row(rows, "库存").value, "grain 498000");
  assert.equal(row(rows, "货币").value, 12);
  assert.equal(row(rows, "负债").value, "无", "无债 ⇒ 显示「无」而不是 0（0 会被读成「有债且为 0」）");
});

test("regime-and-cycle-progress-come-from-the-industry", () => {
  const rows = P.economyReadoutRows(ECONOMY);

  const industry = row(rows, "产业 农业");
  assert.equal(industry.value, "feudal · 周期 33/120", "制度与周期进度逐值来自服务端的产业行");
  assert.match(industry.hint, /R4a/, "提示里必须点明产出/分配是后续增量（本轮不结算）");
});

test("hex-without-industry-says-no-industry", () => {
  const rows = P.economyReadoutRows({
    q: 1,
    r: 2,
    activated: true,
    population: 0,
    laborMilli: 0,
    landMilliMu: 0,
    goods: {},
    money: 0,
    debtCount: 0,
    debtPrincipal: 0,
    industries: [],
  });

  assert.equal(row(rows, "产业").value, "无");
  assert.equal(row(rows, "库存").value, "无", "空商品表 ⇒ 「无」，不是空串");
});

test("inactive-economy-says-not-activated", () => {
  const rows = P.economyReadoutRows({ activated: false, industries: [] });

  assert.equal(rows.length, 1, "未激活时只给一行状态，不铺一屏 0");
  assert.equal(rows[0].value, "未激活");
});

test("missing-payload-degrades-to-no-data", () => {
  assert.equal(P.economyReadoutRows(null)[0].value, "无数据");
  assert.equal(P.economyReadoutRows(undefined)[0].value, "无数据");
});

test("missing-fields-degrade-to-zero-without-throwing", () => {
  // 旧后端 / 部分字段：不抛、不显示 undefined（显示层不制造"看起来有值"的空）。
  const rows = P.economyReadoutRows({ activated: true, industries: [{ id: "farm@0_0" }] });

  assert.equal(row(rows, "经济人口").value, 0);
  assert.equal(row(rows, "土地").value, "0 千分亩");
  assert.equal(row(rows, "产业 farm@0_0").value.indexOf("undefined"), -1);
});

test("api-exposes-cached-economy-hex-hitting-the-endpoint-once", async () => {
  const calls = [];
  const recordingFetch = (url, init) => {
    calls.push({ url: url, method: init && init.method });
    return Promise.resolve({
      ok: true,
      status: 200,
      statusText: "OK",
      text: () => Promise.resolve(JSON.stringify(ECONOMY)),
    });
  };
  const api = loadWebui("api.js", { fetch: recordingFetch }).SimosApi;

  const first = await api.cachedEconomyHex(1, 2, null);
  const second = await api.cachedEconomyHex(1, 2, null);

  assert.equal(first.population, 100);
  assert.deepEqual(
    calls,
    [{ url: "/api/economy/hex?q=1&r=2", method: "GET" }],
    "同一格必须只发一次 GET（第二次走缓存）"
  );
  assert.equal(second.population, 100);
});

test("hex-panel-wires-the-economy-readout", () => {
  const panels = readWebui("panels.js");
  const api = readWebui("api.js");

  assert.match(
    panels,
    /api\s*\.\s*cachedEconomyHex\(/,
    "hex 详情必须经 api.cachedEconomyHex 取数（不自己拼 URL / 不自己 fetch）"
  );
  assert.match(
    panels,
    /economyReadoutRows\(results\[4\]\)/,
    "取回的读数必须经纯投影函数 economyReadoutRows 落 DOM"
  );
  assert.match(api, /cachedEconomyHex:\s*cachedEconomyHex/, "api.js 必须导出 cachedEconomyHex");
  assert.equal(
    (panels.match(/function economyReadoutRows\(/g) || []).length,
    1,
    "投影函数只许有一份实现（两处各写一份 ⇒ 形状漂移且无判据）"
  );
});
