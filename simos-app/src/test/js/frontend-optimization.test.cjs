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
  assert.ok(source.includes("lastPlan = null"), "参数变化必须清空旧取色计划");
  assert.ok(source.includes('"png-threshold"'), "阈值变化也必须触发布局失效");
});
