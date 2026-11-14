// frontend-optimization.test.cjs —— D-036 前端实测回归：窄屏/文档/PNG/editor profile。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");
const { readWebui } = require("./helpers/webui-loader.cjs");

test("region-name-toggle-does-not-wrap-vertically", () => {
  const css = readWebui("styles.css");
  const block = css.slice(css.indexOf(".region-name-toggle {"), css.indexOf("}", css.indexOf(".region-name-toggle {")));
  assert.ok(block.includes("white-space: nowrap"), "区域名开关必须 nowrap");
  assert.ok(block.includes("flex: 0 0 auto"), "区域名开关不得被 flex 压扁");
});

test("map-edit-subtool-labels-do-not-wrap", () => {
  const css = readWebui("styles.css");
  for (const selector of [".map-edit-subtools label {", ".map-edit-tools label {"]) {
    const start = css.indexOf(selector);
    const block = css.slice(start, css.indexOf("}", start));
    assert.ok(block.includes("white-space: nowrap"), selector + " 必须 nowrap");
  }
});

test("narrow-topbar-has-responsive-rules", () => {
  const css = readWebui("styles.css");
  assert.ok(css.includes("@media (max-width: 1450px)"), "必须有小屏顶栏规则");
  assert.ok(css.includes(".mode-current") && css.includes("display: none"), "小屏必须隐藏重复的当前模式 pill");
});

test("png-overlay-is-hidden-outside-map-edit", () => {
  const renderer = readWebui("renderer.js");
  assert.ok(
    renderer.includes('app.getState().mode !== "map-edit"'),
    "PNG 辅助层离开地图编辑后必须隐藏"
  );
});

test("editor-profile-errors-are-labelled-not-generic", () => {
  const panel = readWebui("panel-right.js");
  const notes = readWebui("notifications.js");
  assert.ok(panel.includes("editor profile 未挂载经济"), "经济 404 必须是 editor profile 文案");
  assert.ok(panel.includes("editor profile 未挂载审批"), "审批 404 必须是 editor profile 文案");
  assert.ok(notes.includes("error.status === 404"), "审批通知 404 必须静默隐藏");
});


test("png-alignment-change-invalidates-stale-plan", () => {
  const file = path.resolve(
    __dirname,
    "../../../../simos-map/src/main/resources/webui/modules/map/map-png.js"
  );
  const source = fs.readFileSync(file, "utf8");
  assert.match(
    source,
    /function invalidate\([^)]*\)\s*\{[\s\S]*?plan = null/,
    "参数变化必须清空旧识别计划"
  );
  assert.ok(source.includes('"png-threshold"'), "阈值变化也必须触发布局失效");
});

test("map-edit-gestures-use-hex-only-hit-testing", () => {
  const renderer = readWebui("renderer.js");
  assert.ok(renderer.includes("function hexAt(point)"), "编辑手势必须有独立 hexAt");
  const paint = renderer.slice(renderer.indexOf("function paintAt(point)"), renderer.indexOf("function canvasPoint"));
  const edge = renderer.slice(renderer.indexOf("function edgeAt(point)"), renderer.indexOf("function edgeHitAt"));
  assert.ok(paint.includes("hexAt(point)"), "刷子不得用单位/城市优先的 pickAt");
  assert.ok(edge.includes("hexAt(point)"), "连边不得用单位/城市优先的 pickAt");
  assert.ok(
    renderer.includes("var lassoPick = hexAt(canvasPoint(event));"),
    "套索不得用单位/城市优先的 pickAt"
  );
});

test("same-tick-writeEpoch-is-wired-through-target-and-caches", () => {
  const app = readWebui("app.js");
  const api = readWebui("api.js");
  const map = readWebui("map.js");
  const events = readWebui("events.js");
  const paneldom = readWebui("paneldom.js");
  assert.ok(app.includes("writeEpoch: state.writeEpoch"), "target() 必须带 writeEpoch");
  assert.ok(app.includes("body.writeEpoch"), "refreshState 必须回填 writeEpoch");
  assert.ok(api.includes('target.writeEpoch !== null'), "共享取数缓存键必须带 writeEpoch");
  assert.ok(map.includes("t.writeEpoch"), "地图 targetKey 必须带 writeEpoch");
  assert.ok(events.includes("target.writeEpoch"), "事件 targetKey 必须带 writeEpoch");
  assert.ok(paneldom.includes("targetCacheKey"), "右栏缓存键必须带 writeEpoch");
});

test("same-tick-status-does-not-claim-head-advanced", () => {
  for (const file of ["map-mapeditor.js", "map-regioneditor.js"]) {
    const source = readWebui(file);
    assert.ok(!source.includes("head 已前进"), file + " 不得宣称同 tick 编辑推进了 head");
    assert.ok(source.includes("已写入当前节点"), file + " 应明确写入当前节点");
  }
});

test("cylindrical-map-wrap-is-wired-through-renderer", () => {
  const renderer = readWebui("renderer.js");
  assert.ok(renderer.includes("function setWrap(wrap)"), "renderer 必须接收 map wrap metadata");
  assert.ok(renderer.includes("function wrapWorldX(x)"), "点选/编辑必须先规范跨接缝 x");
  assert.ok(renderer.includes("tiledBlocks"), "圆柱图必须绘制左右地形副本");
  assert.ok(renderer.includes("setWrap: setWrap"), "wrap 必须是 renderer 公开入口");
});
