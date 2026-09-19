// M7 T6 区域分组纯函数自检（无浏览器）：groupByTag 的桶/排序/未标注归一 + resolveRegionColor 的兜底。
// 用法: node group-check.cjs
// 只 require panels.js / map.js（stub 掉 window/document），断言纯函数部分——e2e 之外的第二道证据。
"use strict";

const path = require("node:path");
const WEBUI = path.resolve(__dirname, "../../../../simos-app/src/main/resources/webui");

global.window = {
  SimosApp: { text: (v) => String(v) },
  SimosApi: {},
  requestAnimationFrame: () => 0,
  console,
  devicePixelRatio: 1,
  addEventListener: () => {},
};
global.document = { addEventListener: () => {}, getElementById: () => null };

require(path.join(WEBUI, "panels.js"));
require(path.join(WEBUI, "map.js"));
const P = global.window.SimosPanels;
const M = global.window.SimosMap;

let failures = 0;
function check(name, ok, detail) {
  console.log("GRP " + name + ": " + (ok ? "PASS" : "FAIL") + (detail ? " " + detail : ""));
  if (!ok) failures++;
}
function ids(group) {
  return group.regions.map((r) => r.id);
}

// ── 冻结夹具：覆盖 null / undefined / 空串 / 纯空白 / 缺 meta / 重复 tag / 非法 id ──
const FIXTURE = [
  { id: "r-null", name: "空标签", hexCount: 1, meta: { color: "#111111", tag: null } },
  { id: "r-undef", name: "无 tag", hexCount: 2, meta: { color: null, tag: undefined } },
  { id: "r-blank", name: "空串", hexCount: 3, meta: { color: "#333333", tag: "" } },
  { id: "r-space", name: "空白串", hexCount: 4, meta: { color: "#444444", tag: "   " } },
  { id: "r-b", name: "B 区", hexCount: 5, meta: { color: "#555555", tag: "Beta" } },
  { id: "r-a", name: "A 区", hexCount: 6, meta: { color: "#666666", tag: "Alpha" } },
  { id: "r-a2", name: "A2 区", hexCount: 7, meta: { color: "#777777", tag: "Alpha" } },
  { id: "r-nometa", name: "无 meta", hexCount: 8, meta: null },
  { id: null, name: "坏记录", hexCount: 9, meta: { tag: "Alpha" } },
];
const ORDER_BEFORE = FIXTURE.map((r) => r.id);
const groups = P.groupByTag(FIXTURE);
const byTag = {};
groups.forEach((g) => {
  byTag[g.tag] = g;
});

check(
  "tags-order-untagged-last",
  JSON.stringify(groups.map((g) => g.tag)) === JSON.stringify(["Alpha", "Beta", P.UNTAGGED_LABEL]),
  JSON.stringify(groups.map((g) => g.tag))
);
check(
  "alpha-bucket-sorted-by-id",
  JSON.stringify(ids(byTag.Alpha)) === JSON.stringify(["r-a", "r-a2"]),
  JSON.stringify(ids(byTag.Alpha))
);
check("beta-bucket", JSON.stringify(ids(byTag.Beta)) === JSON.stringify(["r-b"]), JSON.stringify(ids(byTag.Beta)));
check(
  "untagged-bucket-covers-null-undefined-blank-space-missing-meta",
  JSON.stringify(ids(byTag[P.UNTAGGED_LABEL])) ===
    JSON.stringify(["r-blank", "r-nometa", "r-null", "r-space", "r-undef"]),
  JSON.stringify(ids(byTag[P.UNTAGGED_LABEL]))
);
check(
  "invalid-id-excluded",
  groups.every((g) => g.regions.every((r) => r.id !== null && r.id !== undefined)),
  JSON.stringify(groups.map((g) => ids(g)))
);
check("input-not-mutated", JSON.stringify(FIXTURE.map((r) => r.id)) === JSON.stringify(ORDER_BEFORE), "order stable");
check("empty-input", JSON.stringify(P.groupByTag([])) === "[]", JSON.stringify(P.groupByTag([])));
check("null-input", JSON.stringify(P.groupByTag(null)) === "[]", JSON.stringify(P.groupByTag(null)));

check(
  "normalizeTag",
  P.normalizeTag(null) === P.UNTAGGED_LABEL &&
    P.normalizeTag(undefined) === P.UNTAGGED_LABEL &&
    P.normalizeTag("") === P.UNTAGGED_LABEL &&
    P.normalizeTag("   ") === P.UNTAGGED_LABEL &&
    P.normalizeTag(" Nation ") === "Nation",
  JSON.stringify([P.normalizeTag(null), P.normalizeTag("  "), P.normalizeTag(" Nation ")])
);

// 输入顺序打乱（Beta 在前、未标注在中间）仍须 Alpha → Beta → 未标注
const shuffled = P.groupByTag([
  { id: "s-space", meta: { tag: " " } },
  { id: "s-b", meta: { tag: "Beta" } },
  { id: "s-a", meta: { tag: "Alpha" } },
]);
check(
  "order-independent",
  JSON.stringify(shuffled.map((g) => g.tag)) === JSON.stringify(["Alpha", "Beta", P.UNTAGGED_LABEL]),
  JSON.stringify(shuffled.map((g) => g.tag))
);

// ── 区域色解析（T4 偏离项 #1 的兜底分支；真实数据两色都合法，故 e2e 覆盖不到）──
check("region-color-valid", M.resolveRegionColor({ color: "#fc6dce" }) === "#fc6dce", "#fc6dce");
let warned = 0;
const fallback = M.resolveRegionColor({ color: "red" }, () => {
  warned++;
});
check(
  "region-color-invalid-falls-back-and-warns",
  fallback === M.REGION_FALLBACK_COLOR && warned === 1,
  JSON.stringify({ fallback, warned, expected: M.REGION_FALLBACK_COLOR })
);
let warnedNull = 0;
const fallbackNull = M.resolveRegionColor(null, () => {
  warnedNull++;
});
check(
  "region-color-missing-meta-falls-back",
  fallbackNull === M.REGION_FALLBACK_COLOR && warnedNull === 1,
  JSON.stringify({ fallbackNull, warnedNull })
);
check(
  "region-color-shorthand-rejected",
  M.resolveRegionColor({ color: "#fff" }) === M.REGION_FALLBACK_COLOR,
  "#fff must not pass #RRGGBB"
);

console.log("GRP RESULT: " + (failures === 0 ? "PASS" : "FAIL"));
process.exit(failures === 0 ? 0 : 1);
