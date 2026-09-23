// hexgeom.js —— 六角网格纯几何（M12 拆分第一步：自 map.js 顶层逐行搬出，函数体一字不动）。
//
// 无 DOM、无 IO、无闭包状态：给定输入即有确定输出，供 map.js 的 createRenderer 与前端门禁
// （node --test 纯函数对拍）共用。**两份宿主页（map.html / index.html）必须在 map.js 之前
// 按顺序引入本文件**——map.js 顶层按名取回（见其文首"取回块"），调用点一字未改。
//
// ★ 本文件只做搬家：常量数值、函数签名、函数体与 map.js 原样一致，未做任何"顺手改动"。

(function () {
  "use strict";

  var MIN_SCALE = 0.03;
  var MAX_SCALE = 12;

  // ★ M8-R：pointy-top 轴向坐标的六邻方向（E/NE/NW/W/SW/SE）。
  //   套索补点（hexLine）、flood fill 的连通判定、边界小点（"有邻格不在区域内"）三处共用。
  var DIR_VECTORS = [
    [1, 0],
    [1, -1],
    [0, -1],
    [-1, 0],
    [-1, 1],
    [0, 1],
  ];

  function clamp(value, lo, hi) {
    return value < lo ? lo : value > hi ? hi : value;
  }

  /** 顶点朝上（pointy-top）的轴向坐标 → 世界像素：与旧 GSimulator hex-math 同型。 */
  function hexToPixel(q, r, size) {
    return {
      x: size * (Math.sqrt(3) * q + (Math.sqrt(3) / 2) * r),
      y: size * (1.5 * r),
    };
  }

  function hexCorners(cx, cy, size) {
    var pts = [];
    for (var i = 0; i < 6; i++) {
      var a = (Math.PI / 180) * (60 * i - 30);
      pts.push({ x: cx + size * Math.cos(a), y: cy + size * Math.sin(a) });
    }
    return pts;
  }

  function cornerKey(x, y) {
    return Math.round(x * 10000) + "," + Math.round(y * 10000);
  }

  function hexRound(fq, fr) {
    var fs = -fq - fr;
    var q = Math.round(fq);
    var r = Math.round(fr);
    var s = Math.round(fs);
    if (Math.abs(q - fq) > Math.abs(r - fr) && Math.abs(q - fq) > Math.abs(s - fs)) {
      q = -r - s;
    } else if (Math.abs(r - fr) > Math.abs(s - fs)) {
      r = -q - s;
    }
    return { q: q, r: r };
  }

  /** 世界像素 → 轴向坐标（size = 世界格边长）。 */
  function pixelToHex(px, py, size) {
    var fq = ((Math.sqrt(3) / 3) * px - (1 / 3) * py) / size;
    var fr = ((2 / 3) * py) / size;
    return hexRound(fq, fr);
  }

  /** 轴向坐标的六邻。 */
  function axialNeighbors(q, r) {
    var out = [];
    for (var i = 0; i < DIR_VECTORS.length; i++) {
      out.push({ q: q + DIR_VECTORS[i][0], r: r + DIR_VECTORS[i][1] });
    }
    return out;
  }

  /** 轴向坐标距离（相邻 ⇔ 1）。 */
  function axialDistance(aq, ar, bq, br) {
    var dq = aq - bq;
    var dr = ar - br;
    return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
  }

  /** 两个轴向坐标之间的直线格列（cube 线性插值 + hexRound 取整）——套索补点用，避免快速拖动漏格。 */
  function hexLine(aq, ar, bq, br) {
    var n = Math.round(axialDistance(aq, ar, bq, br));
    if (n <= 0) {
      return [{ q: aq, r: ar }];
    }
    var out = [];
    for (var i = 0; i <= n; i++) {
      var t = i / n;
      out.push(hexRound(aq + (bq - aq) * t, ar + (br - ar) * t));
    }
    return out;
  }

  /** 世界 → 屏幕（CSS px）：screen = world × scale + t。 */
  function worldToScreen(point, view) {
    return { x: point.x * view.scale + view.tx, y: point.y * view.scale + view.ty };
  }

  /** 屏幕（CSS px）→ 世界：world = (screen − t) / scale。 */
  function screenToWorld(point, view) {
    return { x: (point.x - view.tx) / view.scale, y: (point.y - view.ty) / view.scale };
  }

  /** 以 anchor（屏幕 CSS 坐标）为锚缩放：锚下的世界点保持不动。 */
  function zoomAt(view, anchor, factor, minScale, maxScale) {
    var next = clamp(view.scale * factor, minScale, maxScale);
    var world = screenToWorld(anchor, view);
    return { scale: next, tx: anchor.x - world.x * next, ty: anchor.y - world.y * next };
  }

  /** 让世界包围盒 fit 进 width×height（CSS px），四周留 pad。 */
  function fitView(bounds, width, height, pad) {
    if (!bounds || bounds.maxX < bounds.minX || bounds.maxY < bounds.minY) {
      return { scale: 1, tx: 0, ty: 0 };
    }
    var bw = bounds.maxX - bounds.minX + pad * 2;
    var bh = bounds.maxY - bounds.minY + pad * 2;
    var scale = clamp(Math.min(width / bw, height / bh), MIN_SCALE, MAX_SCALE);
    var cx = (bounds.minX + bounds.maxX) / 2;
    var cy = (bounds.minY + bounds.maxY) / 2;
    return { scale: scale, tx: width / 2 - cx * scale, ty: height / 2 - cy * scale };
  }

  window.SimosHexGeom = {
    MIN_SCALE: MIN_SCALE,
    MAX_SCALE: MAX_SCALE,
    DIR_VECTORS: DIR_VECTORS,
    clamp: clamp,
    hexToPixel: hexToPixel,
    hexCorners: hexCorners,
    cornerKey: cornerKey,
    hexRound: hexRound,
    pixelToHex: pixelToHex,
    axialNeighbors: axialNeighbors,
    axialDistance: axialDistance,
    hexLine: hexLine,
    worldToScreen: worldToScreen,
    screenToWorld: screenToWorld,
    zoomAt: zoomAt,
    fitView: fitView,
  };
})();
