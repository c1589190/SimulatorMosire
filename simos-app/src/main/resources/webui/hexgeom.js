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

  /**
   * 单位标记半径（世界像素）——renderer.drawUnits 画红圈、stackOffset 算摊开间距**共用同一口径**。
   * ★ 两处若各写一遍，摊开间距就会与实际圆点大小脱钩（画得下/画不下会撒谎）。
   */
  function markerRadius(cellSize) {
    return Math.max(6, (cellSize || 0) * 0.3);
  }

  var STACK_GAP = 2; // 相邻标记圆之间留的世界像素空隙（>0 ⇒ 不粘连可点）

  /**
   * 屏幕上格高（CSS px）小于此值 ⇒ **不摊开**。用户口径"格子较大时才纵向排列"的落实点：
   * 工作台的 `cellSize` 是**世界**单位且恒定（34），随缩放变的是 `view.scale`，
   * 故门控必须吃 `cellSize × view.scale`（屏幕上真实格高），否则缩放永远不会改变行为。
   */
  var STACK_MIN_SCREEN_CELL = 24;

  /**
   * ★ 2026-09-24 可用性修复：选中/定位一个单位时，"抬到"的最小缩放（`view.scale`，屏px/世界px）。
   *
   * <p>依据（工作台的 `cellSize` 恒为 34，即 map.js 的 BASE_CELL）：
   * - `markerRadius(34) = max(6, 34×0.3) = 10.2` 世界像素 ⇒ 标记屏幕半径 = `10.2 × scale`；
   * - 肉眼"看得见"的半径下限取 3~4 CSS px ⇒ `scale ≥ 0.39`；
   * - 又要求同格多军队能**摊开**（`cellSize × scale ≥ STACK_MIN_SCREEN_CELL=24`）⇒ `scale ≥ 24/34 ≈ 0.706`。
   *
   * <p>取 **0.75**：标记屏幕半径 ≈ 7.65 px（约为下限的两倍，清楚可辨），屏幕格高 = 34×0.75 = 25.5
   * ≥ 24 ⇒ 摊开门控打开，同格的多支军队会分开、每个都点得到。默认"fit 整个世界"时 scale≈0.04~0.08，
   * 标记是亚像素 ⇒ 正是"我没找到单位在哪"的物理原因，本阈值就是要把它抬到看得见的档位。
   */
  var UNIT_VISIBLE_MIN_SCALE = 0.75;

  /**
   * 同格 count 个单位的**纵向摊开间距**（世界像素）；无需摊开 / 屏幕格太小 ⇒ 0。**纯函数**。
   *
   * <p>★ **刻意不再要求"整摞落在格内"**（2026-09-23 改）：真实数据里三国首都各挤着 8~10 个单位，
   * 而"最外侧圆心 ≤ cellSize"要求 `((count−1)/2)·spacing ≤ cellSize`，count=8 时恒不成立 ⇒ 间距恒 0
   * ⇒ **一个都不摊开**，用户"方便点击查看"的诉求完全落空。所以：间距只保证**相邻圆不重叠**，
   * 允许整摞纵向伸出格子（点得到比"待在格子里"重要）。
   *
   * @param screenCellSize 屏幕上格高 = `cellSize × view.scale`；小于 {@link #STACK_MIN_SCREEN_CELL} ⇒ 0
   */
  function stackSpacing(count, cellSize, screenCellSize) {
    if (!(count > 1) || !(cellSize > 0)) {
      return 0;
    }
    if (!(screenCellSize >= STACK_MIN_SCREEN_CELL)) {
      return 0; // 缩得太小：几个点会糊成一团，不如保持重叠
    }
    return markerRadius(cellSize) * 2 + STACK_GAP; // > 两半径和 ⇒ 相邻圆不重叠
  }

  /**
   * 同格 N 个单位**纵向摊开**的偏移（世界像素，只给 y；x 不动）。**纯函数**。
   *
   * <p>口径：`count <= 1` 或屏幕格太小（见 {@link #stackSpacing}）⇒ 恒 0；否则以格心为中心
   * **对称**分布 `offset(i) = (i − (count−1)/2) × spacing`：count=3 ⇒ {−spacing, 0, +spacing}
   * （互不相同、关于 0 对称）。
   */
  function stackOffset(index, count, cellSize, screenCellSize) {
    var spacing = stackSpacing(count, cellSize, screenCellSize);
    if (spacing === 0) {
      return 0;
    }
    return (index - (count - 1) / 2) * spacing;
  }

  /**
   * 单位标记的**分组**：同格的单位再按「军队根」分组，**每组只出一个标记**。**纯函数**。
   *
   * <p>★ 2026-09-24 修正 1（上一轮按**单位**摊开是错的）：根单位本身已经是"整支军队"
   * （`member` 就是编制合计）⇒ 把它的组成元素也铺开会把首都格铺成长长一串。
   *
   * <p>口径（三条）：
   * <ol>
   *   <li>**根的判定与 `unitTree.js` 的 `buildTree` 同一口径**：`parent` 缺失 / 为 `null` /
   *       指向**不存在的 id**（悬空）⇒ 该单位就是根；否则沿 parent 链上溯到根（带环保护）。
   *       ★ 判定用的"已知集"是传入的**全部**单位（含没有 `position` 的）——一个根即使不在任何格上，
   *       也不会让它的子孙被误当根。这一点与 buildTree 逐字一致（测试里与 rootIdOf 对拍钉住）。</li>
   *   <li>**先按格分组，再按根分组**：每个 (格, 根) 出一组。这样"不同国家 / 不同父系的军队"在
   *       同格各占一个标记（可纵向摊开、选中不重叠），而同一支军队的根 + 各兵种**合成一个**标记。</li>
   *   <li>每组 **代表（lead）= 组内"最靠近根"的单位**（层级最浅；同层取输入序在前者）。
   *       首都格（根 + 各兵种同格）⇒ 代表就是根；分遣队单独在一格（它的根在别处）⇒ 组里只有它
   *       ⇒ 代表是它，**不会被藏掉**。</li>
   * </ol>
   *
   * <p>返回 `[{rootId, leadId, at:{q,r}, member:[…]}]`，顺序 = **首次出现的输入序**（先格的顺序，
   * 格内再按根的首次出现序）。`member` = **该组在本格出现的单位 id**，按层级升序、同层输入序
   * （lead 恒在首位）——首都格时它就等于该军队的编制合计（根 + 各兵种）。
   *
   * <p>没有 `position`（或缺 q/r）的单位**不产生标记**（落不了格），但**仍计入已知集**参与第 1 条
   * 的根判定；`id` 缺失的单位整体忽略。
   *
   * <p>★ **本文件不 `require` unitTree.js**：两份宿主页里 `hexgeom.js` 都在 `unitTree.js`（index）
   * 或干脆没有它（map.html 不引 unitTree.js）的情况下被引入，跨文件复用会把加载顺序变成语义。
   * 故这里复写同一**规则**，并用 `unit-tree.test.cjs` 里"markerGroups 与 rootIdOf 对拍"的断言
   * 钉住"同一口径、不另立一套"。
   */
  function markerGroups(units) {
    var list = Array.isArray(units) ? units : [];
    var known = new Map();
    list.forEach(function (u) {
      if (u && u.id !== null && u.id !== undefined) {
        known.set(String(u.id), u);
      }
    });

    function parentIdOf(u) {
      return u.parent === null || u.parent === undefined ? null : String(u.parent);
    }

    function rootIdOfId(startId) {
      var current = startId;
      var steps = 0;
      while (steps <= known.size) {
        var u = known.get(current);
        if (!u) {
          return current;
        }
        var pid = parentIdOf(u);
        if (pid === null || pid === current || !known.has(pid)) {
          return current;
        }
        current = pid;
        steps += 1;
      }
      return current; // 环保护兜底（领域 UnitState 保证无环）
    }

    function depthOfId(startId, rootId) {
      var current = startId;
      var depth = 0;
      var steps = 0;
      while (current !== rootId && steps <= known.size) {
        var u = known.get(current);
        if (!u) {
          break;
        }
        var pid = parentIdOf(u);
        if (pid === null || pid === current) {
          break;
        }
        current = pid;
        depth += 1;
        steps += 1;
      }
      return depth;
    }

    var groups = new Map(); // "q_r|rootId" → {rootId, at, entries:[{id,depth,index}]}
    var order = [];
    list.forEach(function (u, index) {
      if (!u || u.id === null || u.id === undefined || !u.position) {
        return;
      }
      if (u.position.q === undefined || u.position.r === undefined) {
        return;
      }
      var id = String(u.id);
      var rootId = rootIdOfId(id);
      var key = u.position.q + "_" + u.position.r + "|" + rootId;
      var group = groups.get(key);
      if (!group) {
        group = { rootId: rootId, at: { q: u.position.q, r: u.position.r }, entries: [] };
        groups.set(key, group);
        order.push(group);
      }
      group.entries.push({ id: id, depth: depthOfId(id, rootId), index: index });
    });

    return order.map(function (group) {
      group.entries.sort(function (a, b) {
        return a.depth - b.depth || a.index - b.index;
      });
      return {
        rootId: group.rootId,
        leadId: group.entries[0].id,
        at: { q: group.at.q, r: group.at.r },
        member: group.entries.map(function (entry) {
          return entry.id;
        }),
      };
    });
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

  /**
   * 一个屏幕点（CSS px）是否**可见**：落在视口内、且距四边都 ≥ margin。**纯函数**。
   *
   * <p>口径（与 {@link #worldToScreen} 的输出同系 —— 传入的就是它的结果）：
   * - `viewport` = 画布的 **CSS 像素**尺寸 `{width, height}`（与 renderer 的 `cssW/cssH` 同口径）；
   * - `margin` ≥ 0 表示"至少要离边这么远才算看得舒服"（**内缩**语义）：命中区间是
   *   `[margin, width−margin] × [margin, height−margin]`，**边界闭合**（恰好等于 margin ⇒ 可见）；
   * - `margin` 缺省/非有限按 0（= 纯视口内）；点缺省/非有限 ⇒ false（fail-closed：当作不可见，交由调用方居中）。
   *
   * <p>★ 用**内缩**而非"把视口撑大 margin"：后者会把"刚好在屏幕外一点点"也算可见 ⇒ 反而不再居中，
   * 与"出界就居中"的诉求相反。
   */
  function markerScreenVisible(screenPoint, viewport, margin) {
    if (!screenPoint || !viewport) {
      return false;
    }
    var x = screenPoint.x;
    var y = screenPoint.y;
    if (!isFinite(x) || !isFinite(y)) {
      return false;
    }
    var m = typeof margin === "number" && isFinite(margin) ? margin : 0;
    return x >= m && x <= viewport.width - m && y >= m && y <= viewport.height - m;
  }

  /**
   * 把世界点**居中**到视口，返回新的视图 `{scale, tx, ty}`（`scale` 原样透传，不夹取）。**纯函数**。
   *
   * <p>★ 符号口径与 {@link #worldToScreen}（`screen = world × scale + t`）**逐字一致**：
   * 要求该世界点的屏幕落点 = 视口中心 ⇒
   * `tx = viewport.width/2 − worldPoint.x × scale`、`ty = viewport.height/2 − worldPoint.y × scale`。
   * 这与 {@link #fitView} 居中包围盒用的是同一式子（`tx = w/2 − cx·scale`）——**不另立一套符号**。
   * `viewport` 是 CSS 像素 ⇒ `tx/ty` 也是 CSS 像素。
   *
   * <p>反解恒等式（测试据此钉住符号）：`worldToScreen(worldPoint, centerViewOn(worldPoint, vp, s))`
   * 必等于 `{x: vp.width/2, y: vp.height/2}`。
   */
  function centerViewOn(worldPoint, viewport, scale) {
    var vp = viewport || { width: 0, height: 0 };
    var p = worldPoint || { x: 0, y: 0 };
    return {
      scale: scale,
      tx: vp.width / 2 - p.x * scale,
      ty: vp.height / 2 - p.y * scale,
    };
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
    markerScreenVisible: markerScreenVisible,
    centerViewOn: centerViewOn,
    markerRadius: markerRadius,
    stackSpacing: stackSpacing,
    stackOffset: stackOffset,
    markerGroups: markerGroups,
    STACK_MIN_SCREEN_CELL: STACK_MIN_SCREEN_CELL,
    UNIT_VISIBLE_MIN_SCALE: UNIT_VISIBLE_MIN_SCALE,
  };
})();
