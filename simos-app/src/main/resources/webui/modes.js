// modes.js —— 2+1 模式 + 写权限白名单（D-036）。★ 纯函数模块：无 DOM、无 IO、无 fetch。
//
// ★★ 模式栏 = 常规 / 地图编辑 / 单位编辑 / 战役；区域编辑并入地图编辑，决策模式暂缓（legacy 仅供内部/测试）。
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

  // ── 六模式（顺序即模式栏顺序）────────────────────────────────────────
  //
  // writes 里的 type 必须与 simos-map / simos-unit 注册的 `namespace.Command` 逐字一致
  // （M4 裁定 37：构造期校验）。地图编辑已把 T11 的 map.SetEdge / map.RandomizeRegion 按表**列全**
  // （spec §三），本单 UI 只实现 SetTerrain，另两条在 UI 里置灰（未实现），但白名单已按表放行。
  var MODES = [
    { id: "view", label: "常规", writes: [] },
    {
      id: "map-edit",
      label: "地图编辑",
      // D-036 2+1：区域编辑并入地图编辑。地图/区域/连通/地形/区域元数据的写面都在这里；
      // region-edit 作为内部旧模式保留（legacy），不再出现在模式栏。
      writes: [
        "map.SetTerrain",
        "map.ImportRaster",
        "map.PaintTerrain",
        "map.ConnectHexes",
        "map.SetEdge",
        "map.RandomizeRegion",
        "map.UpdateRegion",
        "map.CreateRegion",
        "map.DeleteRegion",
      ],
    },
    {
      id: "unit",
      label: "单位编辑",
      writes: [
        "unit.PlanRoute",
        "unit.CancelRoute",
        "unit.ReparentUnit",
        "unit.SetComposition",
        "unit.DisbandUnit",
        "unit.CreateUnit",
        "unit.AttachUnit",
        "unit.DetachUnit",
      ],
    },
    {
      id: "battle",
      label: "战役",
      // D-036 的 +1：战役/阶段是可独立编辑的地址；当前 GUI 先接通战报查看与文档入口，
      // 下面三条命令白名单为阶段追加/判定/记录保留（后续阶段编辑器直接使用）。
      writes: ["army.RecordCombat", "army.AppendCombatStage", "army.ResolveCombatStage"],
    },
  ];

  // 旧模式只保留给内部子流程/测试调用，不再出现在模式栏：
  // region（区域查看）、region-edit（区域编辑子页）、decision（决策人查看/审批，暂缓）。
  var LEGACY_MODES = [
    { id: "region", label: "区域查看", writes: [] },
    {
      id: "region-edit",
      label: "区域编辑",
      writes: ["map.CreateRegion", "map.UpdateRegion", "map.DeleteRegion"],
    },
    {
      id: "decision",
      label: "决策",
      writes: ["ga.StartDecision", "ga.RunDecision", "ga.ResetDecisionMakerConversation"],
    },
  ];

  var BY_ID = {};
  MODES.concat(LEGACY_MODES).forEach(function (mode) {
    BY_ID[mode.id] = mode;
  });

  /** 模式栏 id（当前 4 个：常规 / 地图编辑 / 单位编辑 / 战役）。 */
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
