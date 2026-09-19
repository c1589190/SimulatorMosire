// region-boundary.test.cjs —— regionBoundaryRings 的纯函数版判据（M8-S §九：精确 hex 外缘，无简化）。
//
// ★ 期望值独立推导：一条闭合多边形「顶点数 == 边界边数」。六角网格里
//   boundaryEdges = 6·|S| − 2·(相邻对数)，与 regionBoundaryRings 的实现无关。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui } = require("./helpers/webui-loader.cjs");

const M = loadWebui("map.js").SimosMap;
const SQ3 = Math.sqrt(3);

function k(p) {
  return p.q + "," + p.r;
}

function independentBoundaryEdgeCount(cells) {
  const set = new Set(cells.map(k));
  let pairs = 0;
  for (const c of cells) {
    for (const n of M.axialNeighbors(c.q, c.r)) {
      if (set.has(k(n))) {
        pairs += 1;
      }
    }
  }
  pairs /= 2;
  return 6 * cells.length - 2 * pairs;
}

function totalVertices(rings) {
  return rings.reduce((sum, ring) => sum + ring.length, 0);
}

const ORIGIN_NEIGHBORS = M.axialNeighbors(0, 0);

const CASES = [
  { name: "single", cells: [{ q: 0, r: 0 }], rings: 1, vertices: 6 },
  { name: "pair", cells: [{ q: 0, r: 0 }, { q: 1, r: 0 }], rings: 1, vertices: 10 },
  {
    name: "line3",
    cells: [{ q: 0, r: 0 }, { q: 1, r: 0 }, { q: 2, r: 0 }],
    rings: 1,
    vertices: 14,
  },
  {
    name: "triangle",
    cells: [{ q: 0, r: 0 }, { q: 1, r: 0 }, { q: 0, r: 1 }],
    rings: 1,
    vertices: 12,
  },
  { name: "donut", cells: ORIGIN_NEIGHBORS, rings: 2, vertices: 24 },
];

test("rings-and-vertex-counts-match-independent-derivation", () => {
  for (const c of CASES) {
    const rings = M.regionBoundaryRings(c.cells);
    assert.equal(rings.length, c.rings, c.name + ": ring count");
    assert.equal(totalVertices(rings), c.vertices, c.name + ": total vertices");
    assert.equal(
      totalVertices(rings),
      independentBoundaryEdgeCount(c.cells),
      c.name + ": matches 6|S|−2·pairs"
    );
  }
});

test("donut-has-outer-18-and-inner-hole-6", () => {
  const rings = M.regionBoundaryRings(ORIGIN_NEIGHBORS);
  assert.deepEqual(
    rings.map((r) => r.length).sort((a, b) => a - b),
    [6, 18]
  );
});

test("single-hex-vertices-are-exactly-the-six-corners", () => {
  const rings = M.regionBoundaryRings([{ q: 0, r: 0 }]);
  assert.equal(rings.length, 1);
  assert.deepEqual(rings[0], [
    { x: 0.866, y: -0.5 },
    { x: 0.866, y: 0.5 },
    { x: 0, y: 1 },
    { x: -0.866, y: 0.5 },
    { x: -0.866, y: -0.5 },
    { x: 0, y: -1 },
  ]);
});

test("every-ring-is-closed-with-unit-edges", () => {
  for (const c of CASES) {
    for (const ring of M.regionBoundaryRings(c.cells)) {
      assert.ok(ring.length >= 3, c.name + ": ring length >= 3");
      for (let i = 0; i < ring.length; i++) {
        const a = ring[i];
        const b = ring[(i + 1) % ring.length];
        const dist = Math.hypot(a.x - b.x, a.y - b.y);
        assert.ok(Math.abs(dist - 1) < 1e-3, c.name + ": edge length " + dist);
      }
    }
  }
});

test("vertices-are-distinct-within-a-ring", () => {
  for (const c of CASES) {
    for (const ring of M.regionBoundaryRings(c.cells)) {
      const seen = new Set(ring.map((p) => p.x + ":" + p.y));
      assert.equal(seen.size, ring.length, c.name + ": no repeated vertex");
    }
  }
});

test("vertices-lie-on-the-hex-vertex-lattice", () => {
  for (const c of CASES) {
    for (const ring of M.regionBoundaryRings(c.cells)) {
      for (const p of ring) {
        const u = p.x / (SQ3 / 2);
        const w = p.y / 0.5;
        assert.ok(Math.abs(u - Math.round(u)) < 1e-3, c.name + ": u residual " + (u - Math.round(u)));
        assert.ok(Math.abs(w - Math.round(w)) < 1e-3, c.name + ": w residual " + (w - Math.round(w)));
      }
    }
  }
});

test("vertices-are-not-hex-centers", () => {
  for (const c of CASES) {
    for (const ring of M.regionBoundaryRings(c.cells)) {
      for (const p of ring) {
        const fq = ((SQ3 / 3) * p.x - (1 / 3) * p.y);
        const fr = (2 / 3) * p.y;
        const cq = Math.round(fq);
        const cr = Math.round(fr);
        const dist = Math.hypot(p.x - (SQ3 * cq + (SQ3 / 2) * cr), p.y - 1.5 * cr);
        assert.ok(dist > 0.9, c.name + ": vertex-to-center distance " + dist);
      }
    }
  }
});

test("empty-and-invalid-input-return-no-ring", () => {
  assert.deepEqual(M.regionBoundaryRings([]), []);
  assert.deepEqual(M.regionBoundaryRings(null), []);
  assert.deepEqual(M.regionBoundaryRings([{ q: undefined, r: 0 }]), []);
});

test("deterministic-across-calls", () => {
  const a = M.regionBoundaryRings(ORIGIN_NEIGHBORS);
  const b = M.regionBoundaryRings(ORIGIN_NEIGHBORS);
  assert.deepEqual(a, b);
});
