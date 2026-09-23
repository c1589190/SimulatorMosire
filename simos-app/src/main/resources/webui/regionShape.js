// regionShape.js —— 区域边界环算法（M12 拆分第一步：自 map.js 顶层逐行搬出，函数体一字不动）。
//
// 依赖 hexgeom.js 的 hexToPixel / hexCorners / cornerKey ⇒ **宿主页必须按
// hexgeom.js → hexcolor.js → regionShape.js → map.js 的顺序引入**。
//
// ★ 本文件只做搬家：与 Java `RegionBoundary.of(hexes)` 逐值一致的承诺不变。

(function () {
  "use strict";

  var hexGeom = window.SimosHexGeom;
  var hexToPixel = hexGeom.hexToPixel;
  var hexCorners = hexGeom.hexCorners;
  var cornerKey = hexGeom.cornerKey;

  /**
   * 区域 hex 集合的**边环**（精确的逐 hex 台阶多边形，单位＝「格边长=1」的世界坐标）。
   * 每条格边被两个同区域 hex 共享 ⇒ 内部边成对出现被抵消，剩下的即成环。可能多环（多块/带洞）。
   *
   * <p>★ 顶点坐标与 Java 的 `HexVertex`（`x = u·√3/2, y = w/2`）逐值一致：`hexCorners` 的第 i 个角
   * （`60°i − 30°`）恰对应 `HexVertex.at(hex, i)`，故本函数结果 == `RegionBoundary.of(hexes)`。
   */
  function regionBoundaryRings(hexList) {
    var pointOf = {};
    var edgeList = [];
    var edgeIndex = {};
    (hexList || []).forEach(function (hex) {
      if (!hex || hex.q === undefined || hex.r === undefined) {
        return;
      }
      var center = hexToPixel(hex.q, hex.r, 1);
      var corners = hexCorners(center.x, center.y, 1);
      for (var i = 0; i < 6; i++) {
        var a = corners[i];
        var b = corners[(i + 1) % 6];
        var ka = cornerKey(a.x, a.y);
        var kb = cornerKey(b.x, b.y);
        pointOf[ka] = { x: Math.round(a.x * 10000) / 10000, y: Math.round(a.y * 10000) / 10000 };
        pointOf[kb] = { x: Math.round(b.x * 10000) / 10000, y: Math.round(b.y * 10000) / 10000 };
        var canon = ka < kb ? ka + "|" + kb : kb + "|" + ka;
        if (Object.prototype.hasOwnProperty.call(edgeIndex, canon)) {
          edgeList[edgeIndex[canon]] = null;
          delete edgeIndex[canon];
        } else {
          edgeIndex[canon] = edgeList.length;
          edgeList.push({ a: ka, b: kb });
        }
      }
    });
    var byCorner = {};
    edgeList.forEach(function (edge, index) {
      if (!edge) {
        return;
      }
      (byCorner[edge.a] || (byCorner[edge.a] = [])).push(index);
      (byCorner[edge.b] || (byCorner[edge.b] = [])).push(index);
    });
    var used = {};
    var rings = [];
    edgeList.forEach(function (edge, startIndex) {
      if (!edge || used[startIndex]) {
        return;
      }
      used[startIndex] = true;
      var ringKeys = [edge.a, edge.b];
      var current = edge.b;
      var guard = 0;
      while (guard++ < 200000) {
        var incident = byCorner[current] || [];
        var next = -1;
        for (var i = 0; i < incident.length; i++) {
          if (!used[incident[i]] && edgeList[incident[i]]) {
            next = incident[i];
            break;
          }
        }
        if (next < 0) {
          break;
        }
        used[next] = true;
        var nextEdge = edgeList[next];
        current = nextEdge.a === current ? nextEdge.b : nextEdge.a;
        if (current === ringKeys[0]) {
          break;
        }
        ringKeys.push(current);
      }
      if (ringKeys.length >= 3) {
        rings.push(
          ringKeys.map(function (key) {
            return pointOf[key];
          })
        );
      }
    });
    return rings;
  }

  window.SimosRegionShape = {
    regionBoundaryRings: regionBoundaryRings,
  };
})();
