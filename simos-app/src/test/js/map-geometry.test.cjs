// map-geometry.test.cjs —— map.js 的轴向几何纯函数（M8-R 套索/flood 共用；M7 起累积）。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui } = require("./helpers/webui-loader.cjs");

const M = loadWebui("map.js").SimosMap;

function key(p) {
  return p.q + "," + p.r;
}

test("axialNeighbors-six-exact-vectors", () => {
  const got = M.axialNeighbors(0, 0).map(key);
  assert.deepEqual(got, ["1,0", "1,-1", "0,-1", "-1,0", "-1,1", "0,1"]);
});

test("axialNeighbors-offset-by-origin", () => {
  const got = M.axialNeighbors(3, -2).map(key);
  assert.deepEqual(got, ["4,-2", "4,-3", "3,-3", "2,-2", "2,-1", "3,-1"]);
});

test("axialNeighbors-are-distinct-and-at-distance-one", () => {
  const list = M.axialNeighbors(-5, 7);
  assert.equal(list.length, 6);
  assert.equal(new Set(list.map(key)).size, 6);
  for (const n of list) {
    assert.equal(M.axialDistance(-5, 7, n.q, n.r), 1);
  }
});

test("axialDistance-frozen-values", () => {
  assert.equal(M.axialDistance(0, 0, 0, 0), 0);
  assert.equal(M.axialDistance(0, 0, 1, 0), 1);
  assert.equal(M.axialDistance(0, 0, 1, -1), 1);
  assert.equal(M.axialDistance(0, 0, 3, 0), 3);
  assert.equal(M.axialDistance(0, 0, 0, -4), 4);
  assert.equal(M.axialDistance(2, 3, -1, -1), 7);
  assert.equal(M.axialDistance(-1, -1, 2, 3), 7);
});

const LINE_CASES = [
  [0, 0, 3, 0],
  [0, 0, 2, -2],
  [0, 0, 2, 1],
  [3, 0, 0, 0],
  [0, 0, 0, 0],
  [-2, 3, 4, -1],
  [5, -5, -5, 5],
  [1, 2, 1, -3],
];

test("hexLine-endpoints-are-exact", () => {
  for (const [aq, ar, bq, br] of LINE_CASES) {
    const line = M.hexLine(aq, ar, bq, br);
    assert.deepEqual(line[0], { q: aq, r: ar });
    assert.deepEqual(line[line.length - 1], { q: bq, r: br });
  }
});

test("hexLine-length-is-distance-plus-one", () => {
  for (const [aq, ar, bq, br] of LINE_CASES) {
    const n = M.axialDistance(aq, ar, bq, br);
    assert.equal(M.hexLine(aq, ar, bq, br).length, n + 1);
  }
});

test("hexLine-has-no-duplicates-and-no-gaps", () => {
  for (const [aq, ar, bq, br] of LINE_CASES) {
    const line = M.hexLine(aq, ar, bq, br);
    const keys = line.map(key);
    assert.equal(new Set(keys).size, keys.length, "no duplicate cells in " + keys.join(" "));
    for (let i = 1; i < line.length; i++) {
      const step = M.axialDistance(line[i - 1].q, line[i - 1].r, line[i].q, line[i].r);
      assert.equal(step, 1, "gap at " + i + " in " + keys.join(" "));
    }
  }
});

test("hexLine-long-diagonal-is-dense", () => {
  const line = M.hexLine(0, 0, 10, -4);
  assert.equal(M.axialDistance(0, 0, 10, -4), 10);
  assert.equal(line.length, 11);
  assert.equal(new Set(line.map(key)).size, 11);
});

test("hexLine-single-cell-returns-fresh-single", () => {
  const a = M.hexLine(4, -2, 4, -2);
  assert.deepEqual(a, [{ q: 4, r: -2 }]);
  const b = M.hexLine(4, -2, 4, -2);
  assert.notEqual(a, b);
  assert.notEqual(a[0], b[0]);
});
