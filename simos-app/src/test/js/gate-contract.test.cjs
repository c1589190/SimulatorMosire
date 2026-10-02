// gate-contract.test.cjs —— 门禁契约自证：测试文件在册、断言数有下界（不许"0 个测试也算通过"）。
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { test } = require("node:test");
const assert = require("node:assert");

const REQUIRED_FILES = [
  "block-codec.test.cjs",
  "d3b-composition-army.test.cjs",
  "decision-docs.test.cjs",
  "decision-mode.test.cjs",
  "economy-panel.test.cjs",
  "f1-map-first.test.cjs",
  "f2-heatmap.test.cjs",
  "gate-contract.test.cjs",
  "gm-panel.test.cjs",
  "map-edit-suboptions.test.cjs",
  "map-edit-tools.test.cjs",
  "map-geometry.test.cjs",
  "modes.test.cjs",
  "notifications.test.cjs",
  "pending-signal.test.cjs",
  "provider-config.test.cjs",
  "readout.test.cjs",
  "region-boundary.test.cjs",
  "region-edit-select.test.cjs",
  "region-view.test.cjs",
  "timeline.test.cjs",
  "unit-tree.test.cjs",
  "webui-fix2.test.cjs",
  "write-allowlist.test.cjs",
];

// ★ 下界＝写作时的真实断言总数（逐条 test(...)）；删/注释掉任何一条都会跌破它。
//   T11 起 61 → 71：新增 map-edit-tools.test.cjs 的 10 条（连通性/随机化的护栏纯函数）。
//   T9 起 71 → 82：新增 region-view.test.cjs 的 11 条（区域查看的共享高亮计划 + 并集读数）。
//   M9 T11 起 82 → 88：新增 block-codec.test.cjs 的 6 条（整数顶点标签解码）。
//   unit-ext T10 起 88 → 90：modes.test.cjs 新增白名单派生式断言 2 条。
//   webui-stage-fix T1 起 90 → 96：新增 notifications.test.cjs 的 6 条。
//   webui-stage-fix T2 起 96 → 108：新增 map-edit-suboptions.test.cjs 的 12 条。
//   webui-stage-fix T3 起 108 → 114：map-edit-tools.test.cjs 新增连通性手势/命中/删边/词表 6 条。
//   webui-stage-fix T7 起 114 → 138：新增 decision-mode.test.cjs 的 23 条（含 3 条 render 流水线夹具）+ modes.test.cjs 的决策只读 1 条。
//   webui-stage-fix T8 起 138 → 153：新增 gm-panel.test.cjs 的 15 条（GM 按钮/全屏层/工具使用渲染/空态/无对话输入）。
//   webui-stage-fix T9 起 153 → 159：新增 pending-signal.test.cjs 的 6 条（待决文本不造假 / 列表项 + 左栏渲染 due 真值 / 列表项由 maker.due 驱动）。
//   webui-stage-fix T10 起 159 → 164：decision-mode.test.cjs 新增 5 条（「开始决策」闸门 / 按钮态 / 发起与拒发）；modes.test.cjs 决策只读 1 条重命名为"恰一条窄写"，条数不变。
//   webui-fix2 起 164 → 184：新增 webui-fix2.test.cjs 的 20 条（U1 压暗 / U2 区域名 / U3 选择粒度 / U5 布局）。
//   webui-fix2 V1/V2 起 184 → 187：U5 宽度断言改写为"稳定可用宽度"；新增 3 条（kv 值列不被压成一字 / 区域名只在区域两模式 / 绘制按模式门控）。
//   webui-fix2 V3 起 187 → 188：新增 1 条（topRegionId 取定义序末位、退回字典序即红）。
//   M11′ 起 188 → 201：新增 provider-config.test.cjs 的 13 条（provider 子页/掩码/表单/绑定/端点对表）。
//   2026-09-22 起 201 → 204：decision-mode.test.cjs 新增 3 条（开始决策成功推进游标 / 409 重取并自动重试一次 / 不无限重试）。
//   Docs 系统 2026-09-23 起 204 → 222：新增 decision-docs.test.cjs 的 11 条，且 decision-mode / provider-config 的子页断言
//     改为从 DECISION_SUBPAGES 派生（加子页不再需要在断言里抄一遍键名）。
//   2026-09-23 UI 改造起 222 → 232：unit-tree.test.cjs 新增 10 条（armyOptions / subtreeOf / rootIdOf /
//     clampPanelPosition / stackOffset（含 stackSpacing））。
//   2026-09-24 修正 1/2 起 233 → 245：unit-tree.test.cjs 新增 12 条（markerGroups 按军队根分组 7 条 /
//     clampTreePan 自由视图夹取 3 条 / 复位控件接线与无残留 scrollIntoView 死代码 2 条）。
//   2026-09-24 可用性修复起 245 → 252：unit-tree.test.cjs 新增 7 条（markerScreenVisible 视口内/外 +
//     margin 边界 2 条 / centerViewOn 与 worldToScreen 对拍 + scale 透传 2 条 / minScale 可见性依据 1 条 /
//     定位按钮接线 1 条 / 选择收口调用 ensureUnitVisible 1 条）。
//   2026-09-24 交战格显示起 252 → 262：unit-tree.test.cjs 新增 10 条（combatHexes 真数据两方/单方/ENGAGED 4 条 +
//     combatSlot 左右分列/四对方不重叠/奇数偏左 3 条 + ⚔ 格心与字号 1 条 + combatLayoutEnabled 门控 1 条 +
//     renderer 接线静态 1 条）。
//   2026-09-24 编队状态起 262 → 265：unit-tree.test.cjs 新增 3 条（脱离/加入编队按钮存在与接线 / 加入按钮 disabled 随
//     "已知是根"三态 / 提交载荷与"服务端理由原样透出"）；modes.test.cjs 只加断言、条数不变。
//   2026-09-24 标记文字起 265 → 267：unit-tree.test.cjs 新增 2 条（markerLabel「军队名 × N」/ renderer 静态接线不再写 leadId 短 id）。
//   2026-09-24 油漆桶起 267 → 268：map-edit-suboptions.test.cjs 新增 1 条（bucket 的门控：SetTerrain 放行 / SetEdge 拒绝）。
//   ★ 与 run-gate.cjs 的 MIN_TESTS 同值，改一处必须改两处。
//   2026-09-24 圈选随机化起 268 → 270：map-edit-tools.test.cjs 新增 2 条（randomizeRecipeState / 两个地形下拉与载荷来源）。
//   2026-09-24 区域名按屏幕可见范围起 270 → 271：webui-fix2.test.cjs 新增 1 条（regionNamePlan 的视口裁剪）。
//   2026-09-24 编制正向化起 271 → 275：unit-tree.test.cjs 新增 4 条（根在顶 2 + 竖直可拖/两轴独立/preferredTreePan）。
//   2026-09-24 编制 v2（取消跟随 / 同格成编 / 顶层带动）起 275 → 279：unit-tree.test.cjs 新增 2 条
//     （节点带出 attached/formationRootId/formationSize 与缺字段时的缺省）；map-edit-tools.test.cjs 新增 2 条
//     （isFormationMember 的成员判定与"未知状态不拦"）。
//   2026-09-24 日制裁定（1 tick = 1 天）起 279 → 284：timeline.test.cjs 新增 3 条（逐日循环：N=2 恰两次调用 +
//     第 2 天用第 1 天返回的新 revision / to 恒 = from+1 / 失败立即停并报"第 i+1 天停下" / 状态文案）；新增
//     readout.test.cjs 2 条（日预算 ×1000×24 = 48000 / etaTick 天数口径）。
//   2026-09-24 真实交战记录起 284 → 287：unit-tree.test.cjs 新增 3 条（记录格无单位标记 ⇒ 值 ≥1 /
//     旧两条推断仍在且与记录叠加 / 记录格与旧计数取较大者）。
//   2026-09-25 R2a（经济生成 + G1 读口）起 287 → 295：新增 economy-panel.test.cjs 的 8 条
//     （经济读数逐值投影 / 制度与周期进度 / 无产业 / 未激活 / 无数据 / 缺字段降级 / 取数层接线与缓存 / 面板接线静态扫描）。
//   2026-09-25 R3a（日结算 + 周期收获）起 295 → 296：economy-panel.test.cjs 新增 1 条
//     （粮库存/日耗/本期流水逐值来自服务端 + 缺 flow 降级为「无」）。
//   2026-09-25 §十一（推进允许一次 N 天，撤销前端逐日循环）起 296 → 297：timeline.test.cjs 的"逐日循环"
//     两条改写为"一条命令 to = from + N"，并新增 1 条（返回里没有新 revision ⇒ 不编造成功），合计 +1。
//   2026-10-01 F1/F2 收尾起 297 → 318：新增 f1-map-first.test.cjs 的 11 条（图层缺省/预设/城市 LOD/搜索/
//     国家汇总/GOV 归属/class-first 读数/renderer 接线）与 f2-heatmap.test.cjs 的 10 条（指标词表/色标/图例/
//     不可用层不填 0/数据组接线/api 缓存）；webui-fix2.test.cjs 的「右栏 view 模式 hidden」断言按 F1 语义改写
//     （条数不变：常规模式承载世界总览）。
//   2026-10-02 D2/D3b 起 318 → 330：新增 d3b-composition-army.test.cjs 的 12 条（parseCompositionText 5 +
//     compositionText 1 + armyCombatForRenderer 2 + cachedArmyCombats 端点 1 + Army 优先/sd 空回退 1 +
//     工作台 parseCompositionText 接线 1 + index.html 新输入框 1）。
//   2026-10-02 region-edit 点选修复起 330 → 337：新增 region-edit-select.test.cjs 的 7 条
//     （region-edit 点格/城市/单位 → regionFocus 不 highlight、无区域/取数失败不清 focus、region 查看仍 single、
//     renderer region-edit pointer 静态）。与 run-gate.cjs 的 MIN_TESTS 同值——改一处必须改两处。
//   2026-10-02 编制正方形 + 树视图缩放起 337 → 349：unit-tree.test.cjs 新增 12 条（clampTreeScale 字面量 +
//     导出常量与函数边界一致 2；zoomedTreePan 焦点不变式/pan-focus 容错/非法 oldScale 1 共 3；render 输出
//     translate(...) scale(1) 行为级 1；缩放控件与 wheel 接线/读数初始 100%/复位 title 含缩放归零 3；
//     .unit-panel 两轴同一 min(...) 表达式 1；.tree-canvas transform-origin 0 0 1；zoom-in 点击真接线 1）。
//     与 run-gate.cjs 的 MIN_TESTS 同值——改一处必须改两处。
//   2026-10-02 区域 tag 点选筛选起 349 → 362：webui-fix2.test.cjs 新增 7 条（taggedTopRegionId 无筛选逐值等于
//     topRegionId / 按 tag 取定义序末位且 trim·大小写敏感 / 无匹配与非数组 null / normalizedTag 空白归一化 4 条；
//     setRegionTag 状态 trim·空转 null 与 setMode 同批清 tag 2 条；panel-right 两个点击点的静态接线 1 条）；
//     region-edit-select.test.cjs 新增 6 条（编辑/查看按 tag 取末位、无匹配严格不动 focus/高亮、regionTag=null
//     仍取全图末位、overview 缺 tag 走 api.mapRegion 兜底）。与 run-gate.cjs 的 MIN_TESTS 同值——改一处必须改两处。
const MIN_ASSERTIONS = 362;

function testFiles() {
  return fs
    .readdirSync(__dirname)
    .filter((name) => name.endsWith(".test.cjs"))
    .sort();
}

function countTestDeclarations() {
  let total = 0;
  for (const name of testFiles()) {
    const source = fs.readFileSync(path.join(__dirname, name), "utf8");
    // 只数"顶层的 test(...) 声明"；不数 `.test(`（正则/字符串里的方法调用）与注释行。
    total += (source.match(/^\s*test\(/gm) || []).length;
  }
  return total;
}

test("all-required-test-files-are-present", () => {
  assert.deepEqual(testFiles(), REQUIRED_FILES);
});

test("assertion-count-is-not-below-the-frozen-floor", () => {
  const count = countTestDeclarations();
  assert.ok(count >= MIN_ASSERTIONS, "断言数 " + count + " 低于下界 " + MIN_ASSERTIONS);
});
