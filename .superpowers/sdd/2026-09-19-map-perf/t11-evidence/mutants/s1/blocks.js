// blocks.js —— 地形块的**纯几何 + 线格式解码**（M9 T13/T11）：无 DOM、无 IO，供 map.js 与 panels.js 共用。
//
// 服务端 /api/map/overview 的 blocks 是「整数顶点标签」的**闭合带洞多边形**：每条环是 [u0,w0,u1,w1,…]，
// 首顶点补到末尾。整数标签 (u,w) 是格角顶点的**唯一身份**（simos-map 的 HexVertex），世界坐标由
// `x = u·√3/2、y = w/2`（格边长 = 1）精确还原 —— `decodeBlocks` 是**唯一**的格式转换点。
// 本文件回答三件事：**某点落在哪一块**（evenodd，洞被排除）、**某轴向坐标的地形是什么**；以及按地形计数。
// ★ 洞的处理是 evenodd 的必然结果：洞内点在外环内、又在洞环内 ⇒ 穿越数为偶 ⇒ 判为不属于该块。
(function () {
  "use strict";

  var SQRT3 = Math.sqrt(3);

  /** 整数顶点标签 (u,w) → 「格边长 = 1」的世界坐标（与后端 `ApiViews.closedLabelRing` 同式，不再量化）。 */
  function vertexXY(u, w) {
    return { x: u * (SQRT3 / 2), y: w * 0.25 };
  }

  /** 一条紧凑环 [u,w,u,w,…] ⇒ [{x,y}…]；闭合重复点原样保留。 */
  function decodeRing(flat) {
    var out = [];
    for (var i = 0; i + 1 < flat.length; i += 2) {
      out.push(vertexXY(flat[i], flat[i + 1]));
    }
    return out;
  }

  /**
   * 把服务端 `overview.blocks` 的紧凑线格式解成 map.js/panels.js 一直使用的 {boundaries:[[{x,y}…]…]} 形。
   * ★ 不改动输入（返回新对象）；M9 T11 起这是**唯一**的格式转换点。
   */
  function decodeBlocks(blocks) {
    return (blocks || []).map(function (block) {
      return {
        id: block.id,
        terrain: block.terrain,
        hexCount: block.hexCount || 0,
        boundaries: (block.boundaries || []).map(decodeRing),
      };
    });
  }

  function blockBounds(block) {
    var rings = block.boundaries || [];
    var minX = Infinity;
    var minY = Infinity;
    var maxX = -Infinity;
    var maxY = -Infinity;
    for (var r = 0; r < rings.length; r++) {
      for (var i = 0; i < rings[r].length; i++) {
        var p = rings[r][i];
        if (p.x < minX) minX = p.x;
        if (p.y < minY) minY = p.y;
        if (p.x > maxX) maxX = p.x;
        if (p.y > maxY) maxY = p.y;
      }
    }
    return { minX: minX, minY: minY, maxX: maxX, maxY: maxY };
  }

  /** evenodd 射线法：所有环一起算穿越数，洞环因此把洞排除在外。 */
  function pointInBoundaries(rings, x, y) {
    var inside = false;
    for (var r = 0; r < rings.length; r++) {
      var ring = rings[r];
      for (var i = 0, j = ring.length - 1; i < ring.length; j = i++) {
        var xi = ring[i].x;
        var yi = ring[i].y;
        var xj = ring[j].x;
        var yj = ring[j].y;
        if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) {
          inside = !inside;
        }
      }
    }
    return inside;
  }

  /** 点（格边长 = 1 的世界坐标）落在哪一块；不在任何块内 ⇒ null。 */
  function blockAtPoint(blocks, x, y) {
    for (var i = 0; i < blocks.length; i++) {
      var block = blocks[i];
      if (!block.__bounds) {
        block.__bounds = blockBounds(block);
      }
      var b = block.__bounds;
      if (x < b.minX || x > b.maxX || y < b.minY || y > b.maxY) {
        continue;
      }
      if (pointInBoundaries(block.boundaries || [], x, y)) {
        return block;
      }
    }
    return null;
  }

  /** 轴向坐标 (q,r) 的格中心（格边长 = 1）落在哪一块。 */
  function blockAtHex(blocks, q, r) {
    return blockAtPoint(blocks, SQRT3 * q + (SQRT3 / 2) * r, 1.5 * r);
  }

  /** 轴向坐标处的地形 key；不在图上（不在任何块内）⇒ null。 */
  function terrainAt(blocks, q, r) {
    var block = blockAtHex(blocks, q, r);
    return block ? block.terrain : null;
  }

  /** 按地形统计格数（各块 hexCount 之和；块 hexCount 由服务端从权威块给出）。 */
  function countsByTerrain(blocks) {
    var counts = {};
    for (var i = 0; i < (blocks || []).length; i++) {
      var block = blocks[i];
      counts[block.terrain] = (counts[block.terrain] || 0) + (block.hexCount || 0);
    }
    return counts;
  }

  window.SimosBlocks = {
    decodeBlocks: decodeBlocks,
    blockAtPoint: blockAtPoint,
    blockAtHex: blockAtHex,
    terrainAt: terrainAt,
    countsByTerrain: countsByTerrain,
    pointInBoundaries: pointInBoundaries,
  };
})();
