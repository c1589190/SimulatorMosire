// gate-contract.test.cjs —— 门禁契约自证：测试文件在册、断言数有下界（不许"0 个测试也算通过"）。
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { test } = require("node:test");
const assert = require("node:assert");

const REQUIRED_FILES = [
  "block-codec.test.cjs",
  "decision-docs.test.cjs",
  "decision-mode.test.cjs",
  "gate-contract.test.cjs",
  "gm-panel.test.cjs",
  "map-edit-suboptions.test.cjs",
  "map-edit-tools.test.cjs",
  "map-geometry.test.cjs",
  "modes.test.cjs",
  "notifications.test.cjs",
  "pending-signal.test.cjs",
  "provider-config.test.cjs",
  "region-boundary.test.cjs",
  "region-view.test.cjs",
  "timeline.test.cjs",
  "unit-tree.test.cjs",
  "webui-fix2.test.cjs",
  "write-allowlist.test.cjs",
];

// ★ 下界＝写作时的真实断言总数（逐条 test(...)）；删/注释掉任何一条都会跌破它。
//   T11 起 61 → 71：新增 map-edit-tools.test.cjs 的 10 条（连通性/随机化的护栏纯函数）。
//   T9 起 71 → 82：新增 region-view.test.cjs 的 11 条（区域查看的共享高亮计划 + 并集读数）。
//   M9 T11 起 82 → 88：新增 block-codec.test.cjs 的 6 条（整数顶点标签解码）。
//   unit-ext T10 起 88 → 90：modes.test.cjs 新增白名单派生式断言 2 条。
//   webui-stage-fix T1 起 90 → 96：新增 notifications.test.cjs 的 6 条。
//   webui-stage-fix T2 起 96 → 108：新增 map-edit-suboptions.test.cjs 的 12 条。
//   webui-stage-fix T3 起 108 → 114：map-edit-tools.test.cjs 新增连通性手势/命中/删边/词表 6 条。
//   webui-stage-fix T7 起 114 → 138：新增 decision-mode.test.cjs 的 23 条（含 3 条 render 流水线夹具）+ modes.test.cjs 的决策只读 1 条。
//   webui-stage-fix T8 起 138 → 153：新增 gm-panel.test.cjs 的 15 条（GM 按钮/全屏层/工具使用渲染/空态/无对话输入）。
//   webui-stage-fix T9 起 153 → 159：新增 pending-signal.test.cjs 的 6 条（待决文本不造假 / 列表项 + 左栏渲染 due 真值 / 列表项由 maker.due 驱动）。
//   webui-stage-fix T10 起 159 → 164：decision-mode.test.cjs 新增 5 条（「开始决策」闸门 / 按钮态 / 发起与拒发）；modes.test.cjs 决策只读 1 条重命名为"恰一条窄写"，条数不变。
//   webui-fix2 起 164 → 184：新增 webui-fix2.test.cjs 的 20 条（U1 压暗 / U2 区域名 / U3 选择粒度 / U5 布局）。
//   webui-fix2 V1/V2 起 184 → 187：U5 宽度断言改写为"稳定可用宽度"；新增 3 条（kv 值列不被压成一字 / 区域名只在区域两模式 / 绘制按模式门控）。
//   webui-fix2 V3 起 187 → 188：新增 1 条（topRegionId 取定义序末位、退回字典序即红）。
//   M11′ 起 188 → 201：新增 provider-config.test.cjs 的 13 条（provider 子页/掩码/表单/绑定/端点对表）。
//   2026-09-22 起 201 → 204：decision-mode.test.cjs 新增 3 条（开始决策成功推进游标 / 409 重取并自动重试一次 / 不无限重试）。
//   Docs 系统 2026-09-23 起 204 → 222：新增 decision-docs.test.cjs 的 11 条，且 decision-mode / provider-config 的子页断言
//     改为从 DECISION_SUBPAGES 派生（加子页不再需要在断言里抄一遍键名）。
//   2026-09-23 UI 改造起 222 → 232：unit-tree.test.cjs 新增 10 条（armyOptions / subtreeOf / rootIdOf /
//     clampPanelPosition / stackOffset（含 stackSpacing））。
//   ★ 与 run-gate.cjs 的 MIN_TESTS 同值，改一处必须改两处。
const MIN_ASSERTIONS = 233;

function testFiles() {
  return fs
    .readdirSync(__dirname)
    .filter((name) => name.endsWith(".test.cjs"))
    .sort();
}

function countTestDeclarations() {
  let total = 0;
  for (const name of testFiles()) {
    const source = fs.readFileSync(path.join(__dirname, name), "utf8");
    // 只数"顶层的 test(...) 声明"；不数 `.test(`（正则/字符串里的方法调用）与注释行。
    total += (source.match(/^\s*test\(/gm) || []).length;
  }
  return total;
}

test("all-required-test-files-are-present", () => {
  assert.deepEqual(testFiles(), REQUIRED_FILES);
});

test("assertion-count-is-not-below-the-frozen-floor", () => {
  const count = countTestDeclarations();
  assert.ok(count >= MIN_ASSERTIONS, "断言数 " + count + " 低于下界 " + MIN_ASSERTIONS);
});
