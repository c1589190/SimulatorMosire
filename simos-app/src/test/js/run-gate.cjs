// run-gate.cjs —— 前端单元测试门禁驱动（T2）。
//
// 由 exec-maven-plugin 在 simos-app 的 `test` 阶段调用：调用 `node --test` 跑同目录下全部
// `*.test.cjs`，并把"通过数不达下界"当成失败——`node --test` 在**没有匹配文件**时 rc=0，
// 若只看退出码就会假绿，这里显式解析汇总并卡下界。零 npm 依赖、不联网。
"use strict";

const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const JS_DIR = __dirname;
// ★ 下界＝写作时 node --test 实测的断言数；删/停用任何一条都会跌破它。
//   T11 起 61 → 71（新增 map-edit-tools.test.cjs 的 10 条）；T9 起 71 → 82（新增
//   region-view.test.cjs 的 11 条）；M9 T11 起 82 → 88（新增 block-codec.test.cjs 的 6 条）；
//   unit-ext T10 起 88 → 90（modes.test.cjs 新增白名单派生式断言 2 条）；
//   webui-stage-fix T1 起 90 → 96（新增 notifications.test.cjs 的 6 条）。
//   webui-stage-fix T2 起 96 → 108（新增 map-edit-suboptions.test.cjs 的 12 条）。
//   webui-stage-fix T3 起 108 → 114（map-edit-tools.test.cjs 新增连通性手势/命中/删边/词表 6 条）。
//   webui-stage-fix T7 起 114 → 135（新增 decision-mode.test.cjs 的 20 条 + modes.test.cjs 决策只读 1 条）。
//   webui-stage-fix T8 起 138 → 153（新增 gm-panel.test.cjs 的 15 条：GM 按钮/全屏层/工具使用渲染/空态/无对话输入）。
//   webui-stage-fix T9 起 153 → 159（新增 pending-signal.test.cjs 的 6 条：待决文本不造假 + 列表项/左栏渲染 due 真值 + 列表项由 maker.due 驱动）。
//   webui-stage-fix T10 起 159 → 164（decision-mode.test.cjs 新增 5 条：「开始决策」闸门按 due + 按钮态 + 发起/拒发；modes.test.cjs「决策只读」改为「恰一条窄写」重命名，条数不变）。
//   webui-fix2 起 164 → 184（新增 webui-fix2.test.cjs 的 20 条：U1 地形压暗 / U2 区域名 / U3 选择粒度 / U5 三栏布局）。
//   webui-fix2 V1/V2 起 184 → 187（U5 宽度断言改写为"稳定可用宽度"；新增 kv 值列不被压成一字、区域名只在区域两模式、绘制按模式门控 3 条）。
//   webui-fix2 V3 起 187 → 188（新增 topRegionId 的定义序末位 / 退回字典序即红 1 条）。
//   M11′ 起 188 → 201（新增 provider-config.test.cjs 的 13 条：provider 子页/掩码/表单/绑定/端点对表）。
//   2026-09-22 起 201 → 204（decision-mode.test.cjs 新增 3 条：开始决策成功推进游标 + 409 重取并自动重试一次 + 不无限重试）。
//   Docs 系统 2026-09-23 起 204 → 222（新增 decision-docs.test.cjs 的 11 条：查询必须带 as / docId 与 limit 不并用 /
//     正文尽力解析、解析不了不报错 / 两轴都显 / 视图原样带服务端的 note；另 decision-mode 与 provider-config 的子页断言
//     改为**从 DECISION_SUBPAGES 派生**——再加子页只需改那一处，不必在断言里抄一遍键名）。
//   与 gate-contract.test.cjs 的 MIN_ASSERTIONS 同值——两层下界各写一个数，**改一处必须改两处**。
//   2026-09-23 UI 改造起 222 → 232（unit-tree.test.cjs 新增 10 条：armyOptions / subtreeOf /
//     rootIdOf / clampPanelPosition / stackOffset（含 stackSpacing）——军队选择、单军队子树、浮层夹取、同格摊开）。
//   2026-09-24 修正 1/2 起 233 → 245（unit-tree.test.cjs 新增 12 条：markerGroups 按军队根分组 7 条 /
//     clampTreePan 自由视图夹取 3 条 / 复位控件接线与无残留 scrollIntoView 死代码 2 条）。
//   2026-09-24 可用性修复起 245 → 252（unit-tree.test.cjs 新增 7 条：markerScreenVisible 视口内/外 + margin
//     边界 2 条 / centerViewOn 与 worldToScreen 对拍 + scale 透传 2 条 / minScale 可见性依据 1 条 /
//     定位按钮接线 1 条 / 选择收口调用 ensureUnitVisible 1 条）。
//   2026-09-24 交战格显示起 252 → 262（unit-tree.test.cjs 新增 10 条：combatHexes 真数据两方/单方/ENGAGED 4 条 +
//     combatSlot 左右分列/四对方不重叠/奇数偏左 3 条 + ⚔ 格心与字号 1 条 + combatLayoutEnabled 门控 1 条 +
//     renderer 接线静态 1 条）。
//   2026-09-24 编队状态起 262 → 265（unit-tree.test.cjs 新增 3 条：脱离/加入编队按钮存在与接线 / 加入按钮 disabled 随"已知是根"/
//     提交载荷与"服务端理由原样透出"；modes.test.cjs 的 unit 白名单只加断言、条数不变）。
//   2026-09-24 标记文字起 265 → 267（unit-tree.test.cjs 新增 2 条：markerLabel「军队名 × N」含超长压缩与无名回落 /
//     renderer 静态接线改用 markerLabel、不再写 leadId 的短 id）。
//   2026-09-24 油漆桶起 267 → 268（map-edit-suboptions.test.cjs 新增 1 条：bucket 走地形线写门（SetTerrain 放行 /
//     SetEdge 拒绝）；另 5 条既有断言随子选项模型扩容（tools 含 bucket、面板可见性多一个 bucket 键、index.html 控件）。
//   2026-09-24 圈选随机化起 268 → 270（map-edit-tools.test.cjs 新增 2 条：randomizeRecipeState 的两侧必填/等值合法
//     9 组边界 + 随机化面板两个地形下拉与"载荷两侧只能来自 guard"的静态扫描）。
//   2026-09-24 区域名按屏幕可见范围起 270 → 271（webui-fix2.test.cjs 新增 1 条：regionNamePlan 的视口裁剪
//     与"x/y 是世界坐标"判别；同一条静态用例改指新接线 —— 绘制循环里不得再出现全表布局调用）。
//   2026-09-24 编制正向化起 271 → 275（unit-tree.test.cjs：根在顶的行为级用例 2 条 + 竖直可拖/两轴独立/
//     preferredTreePan 3 条，其中旧的"小内容钉死居中"1 条按新口径重写）。
//   2026-09-24 日制裁定（1 tick = 1 天）起 279 → 284：timeline.test.cjs 新增 3 条（逐日循环：N=2 恰两次调用 +
//     第 2 天用第 1 天返回的新 revision / to 恒 = from+1 / 失败立即停并报"第 i+1 天停下" / 状态文案）；新增
//     readout.test.cjs 2 条（日预算 ×1000×24 = 48000 / etaTick 天数口径）。
//   2026-09-24 真实交战记录起 284 → 287：unit-tree.test.cjs 新增 3 条（记录格无单位标记 ⇒ 值 ≥1 的字面量 /
//     旧两条推断仍在且与记录叠加 / 记录格与旧计数取较大者）。
//   2026-09-25 R2a（经济生成 + G1 读口）起 287 → 295：新增 economy-panel.test.cjs 的 8 条
//     （见 gate-contract.test.cjs 的 MIN_ASSERTIONS 同款说明）。
//   2026-09-25 R3a（日结算 + 周期收获）起 295 → 296：economy-panel.test.cjs 新增 1 条
//     （粮库存/日耗/本期流水逐值来自服务端 + 缺 flow 降级为「无」）。
//   2026-09-25 §十一（推进允许一次 N 天，撤销前端逐日循环）起 296 → 297：timeline.test.cjs 的"逐日循环"
//     两条改写为"一条命令 to = from + N"，并新增 1 条（返回里没有新 revision ⇒ 不编造成功），合计 +1。
//   2026-10-01 F1/F2 收尾起 297 → 318：新增 f1-map-first.test.cjs（图层/城市 LOD/搜索/国家汇总/GOV 归属/
//     class-first 读数/renderer 接线）与 f2-heatmap.test.cjs（指标词表/色标/图例/不可用层不填 0/数据组接线/
//     api 缓存）；webui-fix2.test.cjs 的过期右栏断言按 F1 语义改写，条数不变。
const MIN_TESTS = 318;

function discoverTests() {
  return fs
    .readdirSync(JS_DIR)
    .filter((name) => name.endsWith(".test.cjs"))
    .sort()
    .map((name) => path.join(JS_DIR, name));
}

function summaryNumber(output, label) {
  const match = output.match(new RegExp("^# " + label + " (\\d+)", "m"));
  return match ? Number(match[1]) : null;
}

function main() {
  const files = discoverTests();
  if (files.length === 0) {
    console.error("[frontend-gate] 在 " + JS_DIR + " 找不到任何 *.test.cjs —— 拒绝通过（n=0 不算通过）");
    process.exit(2);
  }
  const result = spawnSync(process.execPath, ["--test", "--test-reporter=tap", ...files], {
    encoding: "utf8",
    maxBuffer: 256 * 1024 * 1024,
  });
  const output = (result.stdout || "") + (result.stderr || "");
  process.stdout.write(output);
  if (result.error) {
    console.error("[frontend-gate] 无法运行 node --test：" + result.error.message);
    process.exit(3);
  }
  const tests = summaryNumber(output, "tests");
  const pass = summaryNumber(output, "pass");
  const fail = summaryNumber(output, "fail");
  const skipped = summaryNumber(output, "skipped");
  const todo = summaryNumber(output, "todo");
  if (tests === null || pass === null || fail === null) {
    console.error("[frontend-gate] 解析不到 node --test 汇总 —— 视为失败（no silent green）");
    process.exit(4);
  }
  if (fail > 0 || result.status !== 0) {
    console.error("[frontend-gate] 前端测试失败：tests=" + tests + " pass=" + pass + " fail=" + fail + " rc=" + result.status);
    process.exit(1);
  }
  if (skipped > 0 || todo > 0) {
    console.error("[frontend-gate] 有被跳过/待办的测试：skipped=" + skipped + " todo=" + todo + " —— 门禁不许静默跳过");
    process.exit(6);
  }
  if (tests < MIN_TESTS) {
    console.error("[frontend-gate] 断言数 " + tests + " 低于下界 " + MIN_TESTS + " —— 防假绿下界");
    process.exit(5);
  }
  console.log("[frontend-gate] OK tests=" + tests + " pass=" + pass + " fail=" + fail);
}

main();
