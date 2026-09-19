// M8 T11 探针（**不是** e2e）：在 --demo 空库起的真 ShellMain 上直接打 API，量四件事——
//   ① demo 世界的地基（格/边/区域/单位位置、overview 原文长度与 md5）；
//   ② 随机化指纹（seed=7/99/0 的 overview 原文 md5 —— 预期在先 §二 用的就是这三个）；
//   ③ ★ 后端**是否接受非相邻边** `1_1|1_3`（`EdgeOperations` 只校验"两端点在图上"）——
//      这条是 m3（前端 edgeChainEdges 守卫）的**理由本身**，必须实测，不许推导；
//   ④ `mode` 缺字段 ⇒ Rejected（Q2 的后端半边）。
// 用法: NODE_PATH=<playwright> node probe.cjs <base-url> <out-json>
"use strict";

const fs = require("node:fs");
const crypto = require("node:crypto");

const BASE = process.argv[2];
const OUT = process.argv[3] || ".";

function md5(text) {
  return crypto.createHash("md5").update(text, "utf8").digest("hex");
}

async function api(path, init) {
  const response = await fetch(BASE + path, init);
  const text = await response.text();
  let body = null;
  try {
    body = JSON.parse(text);
  } catch (e) {
    body = null;
  }
  return { status: response.status, text: text, body: body };
}

// ★ 信封四件套：`/api/command` 的 `submitReply` 要求 branch + expectedRevision + type + payloadJson
//   （缺 branch/expectedRevision ⇒ textField/longField 抛 ⇒ 400，本装置第一版当场踩中：三条命令全 400）。
async function command(type, payload) {
  const tl = await api("/api/timeline?branch=main");
  const head = tl.body && tl.body.head;
  const response = await api("/api/command", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      branch: "main",
      expectedRevision: head,
      type: type,
      payloadJson: JSON.stringify(payload),
    }),
  });
  return { status: response.status, body: response.body, sentRevision: head };
}

const out = {};

(async () => {
  // ① 地基
  const ov = await api("/api/map/overview");
  out.overview = {
    status: ov.status,
    bytes: ov.text.length,
    md5: md5(ov.text),
    hexCount: ov.body && ov.body.hexCount,
    blockCount: ov.body && (ov.body.blocks || []).length,
    regions: ov.body && (ov.body.regions || []).map((r) => r.id),
    terrainTypes: ov.body && (ov.body.terrainTypes || []).map((t) => t.key),
    hasRevisionField: ov.text.indexOf("revision") >= 0,
  };

  const units = await api("/api/units");
  out.units = (units.body && units.body.units ? units.body.units : []).map((u) => ({
    id: u.id,
    position: u.position,
  }));

  out.hexBefore = {};
  for (const q of [1, 2, 3]) {
    const h = await api("/api/map/hex?q=1&r=" + q);
    out.hexBefore["1_" + q] = { status: h.status, edges: h.body && h.body.edges, terrain: h.body && h.body.terrain };
  }

  // ③ ★ 非相邻边（跳过 (1,2)）+ ④ 缺 mode：先记基线 revision
  const before = await api("/api/timeline?branch=main");
  const headBefore = before.body && before.body.head;

  const nonAdj = await command("map.SetEdge", { kind: "river", edges: ["1_1|1_3"], mode: "merge" });
  out.nonAdjacentEdge = { status: nonAdj.status, result: nonAdj.body && nonAdj.body.result, reason: nonAdj.body && nonAdj.body.reason };

  const afterNonAdj = await api("/api/map/hex?q=1&r=1");
  out.hexAfterNonAdjacent = { edges: afterNonAdj.body && afterNonAdj.body.edges };

  const noMode = await command("map.SetEdge", { kind: "river", edges: ["1_1|1_2"] });
  out.modeMissing = { status: noMode.status, result: noMode.body && noMode.body.result, reason: noMode.body && noMode.body.reason };

  const afterNoMode = await api("/api/map/hex?q=1&r=1");
  out.hexAfterModeMissing = { edges: afterNoMode.body && afterNoMode.body.edges };

  const tl = await api("/api/timeline?branch=main");
  out.headBefore = headBefore;
  out.headAfter = tl.body && tl.body.head;

  // ② 随机化指纹（选区 3 格；overview 原文 md5）
  const selection = [
    { q: 1, r: 1 },
    { q: 1, r: 2 },
    { q: 1, r: 3 },
  ];
  out.randomize = {};
  for (const seed of [7, 7, 99, 0]) {
    const r = await command("map.RandomizeRegion", { hexes: selection, seed: seed });
    const ov2 = await api("/api/map/overview");
    out.randomize["seed" + seed + "_run" + Object.keys(out.randomize).length] = {
      status: r.status,
      result: r.body && r.body.result,
      bytes: ov2.text.length,
      md5: md5(ov2.text),
      blockCount: ov2.body && (ov2.body.blocks || []).length,
      terrains: ov2.body && (ov2.body.blocks || []).map((b) => b.terrain + ":" + b.hexCount),
    };
  }

  // ②b ★ UI 只能选到 **2 格**（`(1,1)` 上有 demo 单位 u-1 ⇒ `pickAt` 返回 kind:"unit" ⇒ `paintAt`/`edgeAt` 都跳过）
  //     ⇒ 指纹必须按 2 格选区重测；顺带扫几个 seed，看 7 与谁分得开（预期在先 §七.1：不判别就换值）。
  const sel2 = [
    { q: 1, r: 2 },
    { q: 1, r: 3 },
  ];
  out.randomizeTwoHex = [];
  for (const seed of [7, 7, 99, 0, 1, 5, 3]) {
    const r = await command("map.RandomizeRegion", { hexes: sel2, seed: seed });
    const ov3 = await api("/api/map/overview");
    out.randomizeTwoHex.push({
      seed: seed,
      status: r.status,
      result: r.body && r.body.result,
      bytes: ov3.text.length,
      md5: md5(ov3.text),
      blockCount: ov3.body && (ov3.body.blocks || []).length,
      terrains: ov3.body && (ov3.body.blocks || []).map((b) => b.terrain + ":" + b.hexCount),
    });
  }

  fs.writeFileSync(OUT, JSON.stringify(out, null, 2));

  console.log(JSON.stringify(out, null, 2));
})().catch((e) => {
  console.error("PROBE CRASH: " + (e && e.stack ? e.stack : e));
  process.exit(2);
});
