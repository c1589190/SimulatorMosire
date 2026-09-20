// block-codec.test.cjs —— blocks.js 的**线格式解码**（M9 T11）。
//
// 服务端 overview 的块顶点是**整数标签** [u,w,…]（不省流前的 {"x":…,"y":…} 对象约省 2/3 字节）；
// 本文件钉住 decodeBlocks 是**唯一**的格式转换点：整数标签 → {x,y} 世界坐标（x=u·√3/2, y=w/2），
// 且不污染输入、保留元数据与闭合环。几何正确性再走 terrainAt（点在多边形内）。
"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const { loadWebui } = require("./helpers/webui-loader.cjs");

const BLOCKS = loadWebui("blocks.js").SimosBlocks;
const SQRT3 = Math.sqrt(3);

// 单格 (0,0) 的六个角顶点整数标签（= HexVertex.at((0,0), corner)），闭合（首点补末）。
const HEX00_RING = [1, -1, 1, 1, 0, 2, -1, 1, -1, -1, 0, -2, 1, -1];

test("decodeBlocks-restores-vertex-world-coordinates", () => {
  const decoded = BLOCKS.decodeBlocks([{ id: "p@0_0", terrain: "plains", hexCount: 1, boundaries: [HEX00_RING] }]);
  const ring = decoded[0].boundaries[0];
  assert.deepEqual(ring[0], { x: (SQRT3 / 2) * 1, y: -0.5 });
  assert.deepEqual(ring[1], { x: SQRT3 / 2, y: 0.5 });
  assert.deepEqual(ring[2], { x: 0, y: 1 });
});

test("decodeBlocks-preserves-block-metadata", () => {
  const decoded = BLOCKS.decodeBlocks([{ id: "plains@-5_-59", terrain: "plains", hexCount: 10496, boundaries: [HEX00_RING] }]);
  assert.equal(decoded[0].id, "plains@-5_-59");
  assert.equal(decoded[0].terrain, "plains");
  assert.equal(decoded[0].hexCount, 10496);
});

test("decodeBlocks-keeps-rings-closed-with-same-vertex-count", () => {
  const input = { id: "x", terrain: "ocean", hexCount: 2, boundaries: [HEX00_RING, HEX00_RING] };
  const decoded = BLOCKS.decodeBlocks([input])[0];
  assert.equal(decoded.boundaries.length, 2);
  const ring = decoded.boundaries[0];
  assert.deepEqual(ring[ring.length - 1], ring[0]);
  assert.equal(ring.length, HEX00_RING.length / 2);
});

test("decodeBlocks-does-not-mutate-the-wire-input", () => {
  const input = { id: "x", terrain: "desert", hexCount: 1, boundaries: [HEX00_RING.slice()] };
  BLOCKS.decodeBlocks([input]);
  assert.deepEqual(input.boundaries[0], HEX00_RING);
  assert.equal(input.boundaries[0][0], 1);
});

test("terrainAt-reads-decoded-block-at-hex-center", () => {
  const decoded = BLOCKS.decodeBlocks([{ id: "d@0_0", terrain: "desert", hexCount: 1, boundaries: [HEX00_RING] }]);
  assert.equal(BLOCKS.terrainAt(decoded, 0, 0), "desert");
  assert.equal(BLOCKS.terrainAt(decoded, 9, 9), null);
});

test("countsByTerrain-sums-decoded-hex-counts", () => {
  const decoded = BLOCKS.decodeBlocks([
    { id: "a", terrain: "plains", hexCount: 6, boundaries: [HEX00_RING] },
    { id: "b", terrain: "plains", hexCount: 3, boundaries: [HEX00_RING] },
    { id: "c", terrain: "ocean", hexCount: 2, boundaries: [HEX00_RING] },
  ]);
  assert.deepEqual(BLOCKS.countsByTerrain(decoded), { plains: 9, ocean: 2 });
});
