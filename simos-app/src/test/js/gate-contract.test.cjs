// gate-contract.test.cjs —— 门禁契约自证：测试文件在册、断言数有下界（不许"0 个测试也算通过"）。
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { test } = require("node:test");
const assert = require("node:assert");

const REQUIRED_FILES = [
  "gate-contract.test.cjs",
  "map-geometry.test.cjs",
  "modes.test.cjs",
  "region-boundary.test.cjs",
  "timeline.test.cjs",
  "unit-tree.test.cjs",
  "write-allowlist.test.cjs",
];

// ★ 下界＝写作时的真实断言总数（逐条 test(...)）；删/注释掉任何一条都会跌破它。
const MIN_ASSERTIONS = 61;

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
