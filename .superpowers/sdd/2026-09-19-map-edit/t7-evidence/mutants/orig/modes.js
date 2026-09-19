// modes.js —— 五模式 + 写权限白名单（M8 T7）。★ 纯函数模块：无 DOM、无 IO、无 fetch。
//
// ★★ 五模式（spec §三，Q7）：常规 / 区域查看 / 地图编辑 / 区域编辑 / 单位移动编辑。
//   `isWriteAllowed(mode, type)` 是**唯一**的允许判定：输入"模式 + 命令 type" ⇒ 允许/拒绝。
//   调用点（app.js 的 writeCommand）在**发请求之前**用它把关 ⇒ 非该模式的写命令**前端不得发出**。
//
// ★ 白名单是硬约束（spec §三）：服务端另有 CommandBus 的统一校验（词表/存在性/权限），
//   模式只是**前端**概念（防误操作），不是安全边界。
//
// ★ 为什么要有 `// ` 这道宽度：本文件被 WebuiAssetsTest 扫描"无绝对 URL / 无协议相对 URL"，
//   故注释一律用「斜杠斜杠 + 空格或非 ASCII」，绝不出现 `//` 紧跟 ASCII 字母数字的形态。
(function () {
  "use strict";

  // ── 五模式（顺序即模式栏顺序）────────────────────────────────────────
  //
  // writes 里的 type 必须与 simos-map / simos-unit 注册的 `namespace.Command` 逐字一致
  // （M4 裁定 37：构造期校验）。地图编辑已把 T11 的 map.SetEdge / map.RandomizeRegion 按表**列全**
  // （spec §三），本单 UI 只实现 SetTerrain，另两条在 UI 里置灰（未实现），但白名单已按表放行。
  var MODES = [
    { id: "view", label: "常规", writes: [] },
    { id: "region", label: "区域查看", writes: [] },
    {
      id: "map-edit",
      label: "地图编辑",
      // ★ spec §三 的写列只列了 SetTerrain/SetEdge/RandomizeRegion，但同一行的 UI 列明写「区域信息编辑」，
      //   且本单 MUST DO #8 要求该面板改 RegionMeta 走 map.UpdateRegion ⇒ 写列漏了这一条。按 UI 列补上
      //   （只改 meta，不动 hexes；区域内容编辑仍归 region-edit）。
      writes: ["map.SetTerrain", "map.SetEdge", "map.RandomizeRegion", "map.UpdateRegion"],
    },
    {
      id: "region-edit",
      label: "区域编辑",
      writes: ["map.CreateRegion", "map.UpdateRegion", "map.DeleteRegion"],
    },
    {
      id: "unit",
      label: "单位移动编辑",
      // ★ spec §三 只列了 PlanRoute / CancelRoute；但 M7 T7 起"单位移动与编辑"面板**已经**有
      //   ReparentUnit / SetStrength / DisbandUnit / CreateUnit 四个真写入口。把它们排除会让既有能力
      //   当场退化（MUST NOT「不破坏既有能力」）⇒ 本表按**源码**的实际写面列全，并在报告里记为与 spec 的分歧。
      writes: [
        "unit.PlanRoute",
        "unit.CancelRoute",
        "unit.ReparentUnit",
        "unit.SetStrength",
        "unit.DisbandUnit",
        "unit.CreateUnit",
      ],
    },
  ];

  var BY_ID = {};
  MODES.forEach(function (mode) {
    BY_ID[mode.id] = mode;
  });

  /** 五模式 id（模式栏顺序）。 */
  function modeIds() {
    return MODES.map(function (mode) {
      return mode.id;
    });
  }

  /** 模式 id ⇒ 中文标签；未知 id 原样返回（不静默造标签）。 */
  function modeLabel(id) {
    return BY_ID[id] ? BY_ID[id].label : String(id);
  }

  /** 模式的**写命令白名单**（浅拷贝：调用方拿到的是快照，改不动模块内部）。未知模式 ⇒ 空表。 */
  function allowedWrites(mode) {
    var entry = BY_ID[mode];
    return entry ? entry.writes.slice() : [];
  }

  /** ★ 唯一判定：该模式是否允许发该写命令。未知模式 / 空 type ⇒ 拒绝（默认关，fail-closed）。 */
  function isWriteAllowed(mode, type) {
    if (!type) {
      return false;
    }
    return allowedWrites(mode).indexOf(type) >= 0;
  }

  window.SimosModes = {
    MODES: MODES,
    modeIds: modeIds,
    modeLabel: modeLabel,
    allowedWrites: allowedWrites,
    isWriteAllowed: isWriteAllowed,
  };
})();
