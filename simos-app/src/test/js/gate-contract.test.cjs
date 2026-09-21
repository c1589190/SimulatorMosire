// gate-contract.test.cjs —— 门禁契约自证：测试文件在册、断言数有下界（不许"0 个测试也算通过"）。
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { test } = require("node:test");
const assert = require("node:assert");

const REQUIRED_FILES = [
  "block-codec.test.cjs",
  "gate-contract.test.cjs",
  "map-edit-tools.test.cjs",
  "map-geometry.test.cjs",
  "modes.test.cjs",
  "region-boundary.test.cjs",
  "region-view.test.cjs",
  "timeline.test.cjs",
  "unit-tree.test.cjs",
  "write-allowlist.test.cjs",
];

// ★ 下界＝写作时的真实断言总数（逐条 test(...)）；删/注释掉任何一条都会跌破它。
//   T11 起 61 → 71：新增 map-edit-tools.test.cjs 的 10 条（连通性/随机化的护栏纯函数）。
//   T9 起 71 → 82：新增 region-view.test.cjs 的 11 条（区域查看的共享高亮计划 + 并集读数）。
//   M9 T11 起 82 → 88：新增 block-codec.test.cjs 的 6 条（整数顶点标签解码）。
//   unit-ext T10 起 88 → 90：modes.test.cjs 新增白名单派生式断言 2 条。
//   ★ 与 run-gate.cjs 的 MIN_TESTS 同值，改一处必须改两处。
const MIN_ASSERTIONS = 90;

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
