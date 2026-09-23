// hexcolor.js —— 颜色相关纯函数与兜底色常量（M12 拆分第一步：自 map.js 顶层逐行搬出）。
//
// 无 DOM、无 IO 依赖：非法/缺失色一律回兜底色并经 warnFn（或 window.console.warn）报一次，不静默。
// **两份宿主页（map.html / index.html）必须在 map.js 之前按顺序引入本文件**。
//
// ★ regionFallbackWarned 是**可变旗标**（"全页只 warn 一次"），不对外取快照；
//   唯一读点（map.js 里 debug 投影）走实时 getter，行为与搬家前一致。

(function () {
  "use strict";

  var FALLBACK_COLOR = "#ff00ff"; // 词表外地形的兜底色（品红；刻意不像任何地形）
  var REGION_FALLBACK_COLOR = "#00e5ff"; // 区域色缺失/非法时的兜底色（青色；与地形兜底色不同）
  var HEX_COLOR_RE = /^#[0-9a-fA-F]{6}$/;
  var regionFallbackWarned = false;

  /** 纯函数：RegionMeta.color 合法（#RRGGBB）则用它，否则回兜底色并经 warnFn 报一次（不静默）。 */
  function resolveRegionColor(meta, warnFn) {
    var color = meta && meta.color;
    if (typeof color === "string" && HEX_COLOR_RE.test(color)) {
      return color;
    }
    if (typeof warnFn === "function") {
      warnFn(color);
    }
    return REGION_FALLBACK_COLOR;
  }

  /** 取区域填充色；非法/缺失时**全页只 warn 一次**（T4 地形色同型纪律）。 */
  function regionColor(meta) {
    return resolveRegionColor(meta, function (raw) {
      if (!regionFallbackWarned) {
        regionFallbackWarned = true;
        window.console.warn(
          "[SimosMap] 区域色非法或缺失（" + JSON.stringify(raw) + "），使用兜底色 " + REGION_FALLBACK_COLOR
        );
      }
    });
  }

  /** #RRGGBB → rgba(r,g,b,alpha)。 */
  function withAlpha(hex, alpha) {
    var r = parseInt(hex.slice(1, 3), 16);
    var g = parseInt(hex.slice(3, 5), 16);
    var b = parseInt(hex.slice(5, 7), 16);
    return "rgba(" + r + ", " + g + ", " + b + ", " + alpha + ")";
  }

  window.SimosHexColor = {
    FALLBACK_COLOR: FALLBACK_COLOR,
    REGION_FALLBACK_COLOR: REGION_FALLBACK_COLOR,
    HEX_COLOR_RE: HEX_COLOR_RE,
    resolveRegionColor: resolveRegionColor,
    regionColor: regionColor,
    withAlpha: withAlpha,
  };
  // 实时读（不要改成值属性：那样会冻结在 false，丢掉"已 warn 过"的状态）。
  Object.defineProperty(window.SimosHexColor, "regionFallbackWarned", {
    enumerable: true,
    get: function () {
      return regionFallbackWarned;
    },
  });
})();
