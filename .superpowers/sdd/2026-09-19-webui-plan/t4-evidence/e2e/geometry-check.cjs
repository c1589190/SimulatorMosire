// M7 T4 几何纯函数自检（无浏览器）：pixelToHex 往返 / zoomAt 锚点不变 / worldToScreen 逆。
// 用法: node geometry-check.cjs
// 只 require map.js（stub 掉 window/document），断言纯几何部分——e2e 之外的第二道几何证据。
"use strict";

const path = require("node:path");
const WEBUI = path.resolve(__dirname, "../../../../../simos-app/src/main/resources/webui");

global.window = {
  SimosApp: { text: (v) => String(v) },
  SimosApi: {},
  requestAnimationFrame: () => 0,
  console,
  devicePixelRatio: 1,
  addEventListener: () => {},
};
global.document = { addEventListener: () => {}, getElementById: () => null };

require(path.join(WEBUI, "map.js"));
const M = global.window.SimosMap;

let failures = 0;
function check(name, ok, detail) {
  console.log("GEO " + name + ": " + (ok ? "PASS" : "FAIL") + (detail ? " " + detail : ""));
  if (!ok) failures++;
}

const coords = [
  [0, 0],
  [1, 1],
  [1, 3],
  [-5, -59],
  [3, -7],
  [-12, 4],
  [100, -100],
];
let roundtripOk = true;
for (const [q, r] of coords) {
  for (const size of [34, 12, 80]) {
    const p = M.hexToPixel(q, r, size);
    const back = M.pixelToHex(p.x, p.y, size);
    if (back.q !== q || back.r !== r) {
      roundtripOk = false;
      console.log("  roundtrip fail", q, r, size, JSON.stringify(back));
    }
  }
}
check("pixelToHex-roundtrip", roundtripOk, "7 coords × 3 sizes");

const view0 = { scale: 1.3, tx: 120, ty: -45 };
const anchor = { x: 333, y: 210 };
const before = M.screenToWorld(anchor, view0);
const view1 = M.zoomAt(view0, anchor, 2.5, M.MIN_SCALE, M.MAX_SCALE);
const after = M.screenToWorld(anchor, view1);
const drift = Math.hypot(after.x - before.x, after.y - before.y);
check("zoomAt-anchor-fixed", drift < 1e-9, "drift=" + drift.toExponential(3) + " scale " + view0.scale + "->" + view1.scale);

const s = M.worldToScreen({ x: 17, y: -9 }, view0);
const w = M.screenToWorld(s, view0);
check("screen-world-inverse", Math.abs(w.x - 17) < 1e-9 && Math.abs(w.y + 9) < 1e-9, JSON.stringify(w));

check(
  "zoom-clamped",
  M.zoomAt({ scale: M.MAX_SCALE, tx: 0, ty: 0 }, anchor, 10, M.MIN_SCALE, M.MAX_SCALE).scale === M.MAX_SCALE &&
    M.zoomAt({ scale: M.MIN_SCALE, tx: 0, ty: 0 }, anchor, 0.001, M.MIN_SCALE, M.MAX_SCALE).scale === M.MIN_SCALE,
  "max=" + M.MAX_SCALE + " min=" + M.MIN_SCALE
);

console.log("GEO RESULT: " + (failures === 0 ? "PASS" : "FAIL"));
process.exit(failures === 0 ? 0 : 1);
