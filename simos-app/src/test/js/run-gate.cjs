// run-gate.cjs —— 前端单元测试门禁驱动（T2）。
//
// 由 exec-maven-plugin 在 simos-app 的 `test` 阶段调用：调用 `node --test` 跑同目录下全部
// `*.test.cjs`，并把"通过数不达下界"当成失败——`node --test` 在**没有匹配文件**时 rc=0，
// 若只看退出码就会假绿，这里显式解析汇总并卡下界。零 npm 依赖、不联网。
"use strict";

const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const JS_DIR = __dirname;
// ★ 下界＝写作时 node --test 实测的断言数；删/停用任何一条都会跌破它。
//   T11 起 61 → 71（新增 map-edit-tools.test.cjs 的 10 条）；T9 起 71 → 82（新增
//   region-view.test.cjs 的 11 条）；M9 T11 起 82 → 88（新增 block-codec.test.cjs 的 6 条）；
//   unit-ext T10 起 88 → 90（modes.test.cjs 新增白名单派生式断言 2 条）；
//   webui-stage-fix T1 起 90 → 96（新增 notifications.test.cjs 的 6 条）。
//   webui-stage-fix T2 起 96 → 108（新增 map-edit-suboptions.test.cjs 的 12 条）。
//   webui-stage-fix T3 起 108 → 114（map-edit-tools.test.cjs 新增连通性手势/命中/删边/词表 6 条）。
//   webui-stage-fix T7 起 114 → 135（新增 decision-mode.test.cjs 的 20 条 + modes.test.cjs 决策只读 1 条）。
//   webui-stage-fix T8 起 138 → 153（新增 gm-panel.test.cjs 的 15 条：GM 按钮/全屏层/工具使用渲染/空态/无对话输入）。
//   webui-stage-fix T9 起 153 → 159（新增 pending-signal.test.cjs 的 6 条：待决文本不造假 + 列表项/左栏渲染 due 真值 + 列表项由 maker.due 驱动）。
//   与 gate-contract.test.cjs 的 MIN_ASSERTIONS 同值——两层下界各写一个数，**改一处必须改两处**。
const MIN_TESTS = 159;

function discoverTests() {
  return fs
    .readdirSync(JS_DIR)
    .filter((name) => name.endsWith(".test.cjs"))
    .sort()
    .map((name) => path.join(JS_DIR, name));
}

function summaryNumber(output, label) {
  const match = output.match(new RegExp("^# " + label + " (\\d+)", "m"));
  return match ? Number(match[1]) : null;
}

function main() {
  const files = discoverTests();
  if (files.length === 0) {
    console.error("[frontend-gate] 在 " + JS_DIR + " 找不到任何 *.test.cjs —— 拒绝通过（n=0 不算通过）");
    process.exit(2);
  }
  const result = spawnSync(process.execPath, ["--test", "--test-reporter=tap", ...files], {
    encoding: "utf8",
    maxBuffer: 256 * 1024 * 1024,
  });
  const output = (result.stdout || "") + (result.stderr || "");
  process.stdout.write(output);
  if (result.error) {
    console.error("[frontend-gate] 无法运行 node --test：" + result.error.message);
    process.exit(3);
  }
  const tests = summaryNumber(output, "tests");
  const pass = summaryNumber(output, "pass");
  const fail = summaryNumber(output, "fail");
  const skipped = summaryNumber(output, "skipped");
  const todo = summaryNumber(output, "todo");
  if (tests === null || pass === null || fail === null) {
    console.error("[frontend-gate] 解析不到 node --test 汇总 —— 视为失败（no silent green）");
    process.exit(4);
  }
  if (fail > 0 || result.status !== 0) {
    console.error("[frontend-gate] 前端测试失败：tests=" + tests + " pass=" + pass + " fail=" + fail + " rc=" + result.status);
    process.exit(1);
  }
  if (skipped > 0 || todo > 0) {
    console.error("[frontend-gate] 有被跳过/待办的测试：skipped=" + skipped + " todo=" + todo + " —— 门禁不许静默跳过");
    process.exit(6);
  }
  if (tests < MIN_TESTS) {
    console.error("[frontend-gate] 断言数 " + tests + " 低于下界 " + MIN_TESTS + " —— 防假绿下界");
    process.exit(5);
  }
  console.log("[frontend-gate] OK tests=" + tests + " pass=" + pass + " fail=" + fail);
}

main();
