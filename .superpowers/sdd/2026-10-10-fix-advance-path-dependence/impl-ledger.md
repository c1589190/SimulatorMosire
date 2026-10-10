# 实现架构账本 —— 修 `advance` 路径效应：E5b 计数器拿基态当增量基准

> 责任区：`advance` 路径效应（E5b 连续压力周期计数器）。日期 2026-10-10。
> 权威依据：`.superpowers/sdd/2026-10-10-investigate-advance-path-dependence/impl-ledger.md`
> 交付边界：只写 `simos-economy/src/main/**`；只过编译（**未跑** `test`/`verify`/world —— 验收轮归控制方）。

## 1. 关键调查结论（file:line 证据 → 结论 → 影响）

| # | 证据 | 结论 |
|---|---|---|
| 1 | `EconomyLiquidationSettlement.java:362-375`（原 `:352-353`）`baseClassMemberships = base.classStandings()`；`:449-455`（原 `:428-434`）`previous` 取自该表、`next = previous + 1`；`:1266-1302`（原 `:1140-1161`）落盘写 `nextCount` | `base` = `Context.base()` = `EconomySession.base()` = 本次 `advance` 的 `range.from` 快照（`EconomySettlement.java:1249`、`EconomySession.java:69-71`）⇒ 计数器上限 = advance 调用次数；1 段推进下阈值 2 不可达，整条"连续压力 ⇒ 清算/阶层下滑"支路永不开火 |
| 2 | 同一 advance 内工作表唯一：`EconomySession` 构造时 `new EconomyStateBuilder(base)`（`EconomySession.java:63-67`），日循环 `settleOneDayInto(session, day, …)`（`EconomySettlement.java:963-973`）；工作副本 ⊇ 基态（`EconomyStateBuilder.java:286-296`） | 修法成立：改读**当刻工作副本**即可让"同一段内第 n 次关账看到第 n-1 次的值"，且不新增任何状态/写口 |
| 3 | 写入方在同一段内确实改 `classStandings`：E5b 自身（`writeStressAndDecline`）、E6a（`EconomyModeTransitionSettlement`）、迁移新建户（`ModeMigrationSettlement.java:826`）、5c 只改行不改归属（`EconomySettlement.java:3207-3212`） | "当前归属"在段内会变 ⇒ 段内其余直读基态点同族 |
| 4 | 同族"正确写法"对照物：`OperatorCondition.consecutiveDebtStressCycles` 由 `OperatorSettlement.java` 从工作副本读 prev（`EconomySettlement.java:1329-1330`） | 同一模式两个实现，一个活一个死 —— 判据 |

## 2. 本批实现（落点）

**改动文件 1：`simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyLiquidationSettlement.java`**

1. `plan()`（`:362-375`）：`base.classStandings()` → `context.session().sheet().classMembershipsOrBase()`，
   局部量更名 `classMemberships`；`:449-455`（计数器增量基准）、`:581-582`（下滑前当前位置）、`:1270-1305` 两处播种回退
   全部改用同一份表。`classPositions` 保持读 `base`（静态模板）。
2. `writeStressAndDecline`（`:1266-1305`）：删掉 `context.base().classStandings()` 这条第二真值，种子回退直接用
   **即将写入**的那份 `sheet().classMemberships()`（⊇ 基态 ⇒ 对"该户缺归属"判定逐值等价）。
3. `selectRulePolicy`（`:1081-1094`）`preferredMode(...)`：`base.classStandings()` → 工作副本（"债务人**当前**位置的 mode"）。
4. §一.9 日志：新增 `logStressThresholdFired(...)`（`:745-831`，调用点 `:726-728`）；`LIQUIDATION_PLAN` 增 `cycle=` 字段。
5. 类注新增"计数器的读/写权威"段（`:69-77`）。

**改动文件 2：`simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationSettlement.java`**

6. `pickTargetPosition`（`:845-873`）新增 `classMemberships` 入参，源户当前位置改读工作副本；调用点 `:806-808` 传
   已在本方法作用域内的 `classMemberships`（private static，无公开 API 变更）。

**零新增状态组件、零新增写口**：只把既有只读口 `EconomyStateBuilder.classMembershipsOrBase()`（`:294-296`）接上。

## 3. 审计点逐条结论

| 点 | 判定 | 理由 |
|---|---|---|
| `EconomyLiquidationSettlement:352-353`（planner 计数器基准） | **改** | 就是根因（§1-1） |
| `EconomyLiquidationSettlement:1137-1138`（写口播种回退） | **改** | 同表两条真值；工作副本 ⊇ 基态 ⇒ 逐值等价，只收敛权威 |
| `EconomyLiquidationSettlement:958`（`preferredMode`） | **改** | 语义是"债务人**当前**位置的 mode"；段内 E5b/E6a/迁移会改 `currentPositionId` |
| `ModeMigrationSettlement:857`（`pickTargetPosition` 源户位置） | **改** | 同上"当前"语义；工作副本已在调用方作用域内、private static ⇒ 本地改，无 API/测试编译风险 |
| `ExpectedProfitBook:974`（`currentPositionOf`） | **保留（本批不修，作 OPEN 上报）** | 是**同族反模式**，但不是本地可换的读点：`prospect(EconomyData base, …)` 的 `base` 同时供 `base.classes()`（`:244` 人口/居住地，段内每日变）与 `base.classStandings()`。只把归属换成当刻值 = 同一天出现"当前位置 + 段首人口"的**混合日视图**（本仓明令禁止的双真相）。正解 = 让日循环里的派生查询接一份**当日状态视图**（或拆参），属调查账本 §3.2"次源"责任区 |
| `MerchantCapacityPool:566/584/634`（静态纯查询） | **保留（同上，作 OPEN 上报）** | 同上：`hexCapacityMilli/memberCapacityMilli/sharePerMilleAsProviderAt` 以 `EconomyData` 为参，同时读 `base.classStandings()`（`:584/:634`）与 `base.classes()`（`:588`）；调用方 `ModeMigrationPolicy:673/1274`、`PrimaryModeRanking:286` 传的是 `ModeMigrationPolicy.plan(base=段首,…)` 的 `base`。改需跨文件改签名（`simos-economy`+测试 5 文件引用）⇒ 超出"只写 main、不改测试"的边界 |

★ **次源锚点（本批的重要副产物，属假设但有 file:line 支撑，未实测）**：调查账本 §3.2 没追到的"首个关账日主业排序读数变化"
的候选就是**迁移/排序路径整条拿 `EconomyData base`（段首快照）当日工作副本用**：
`ModeMigrationPolicy.plan(base, …)`（调用点 `EconomySettlement.java:3417-3433`，`base` = `:1249` 的 `session.base()`）→
`ExpectedProfitBook.prospect(base,…)`（`ModeMigrationPolicy:471-473/781-783`）、`hasMerchantCapacityAt(base,…)`（`:775-777`）、
`bargainingPowerPerMille(base,…)`（`:625/634/653/674`）、`PrimaryModeRanking.merchantGateReason(base,…)`（`:805`）、
`base.industries()`（9 处）。**若控制方要一次拿下 ⓐⓑ（全等），这是下一个责任区。**

## 4. 会改变数值行为的清单（给测试代理当输入）

- **从"永不开火"变成开火**：单段推进（一次 `advance` 内含 ≥2 个关账日）下 `classStanding.consecutiveDebtStressCycles`
  可达 ≥2 ⇒ 触发合同清算（`:508-512`）、阶层下滑（`:579-639`）、`DEBT_EXPLOSION` 的 accumulation 支路（`:686-688`）、
  hex 危机信号（`writeCrisisSignals`）、以及随之下游的 `classes[].debts/credits/assetShares/industries[].units[].condition`。
- **同段内逐关账累加**：同一 advance 内第 n 次关账现在读得到第 n-1 次的值（原：恒 `段首值 + 1`）。
- **`preferredMode`（`:1081`）**：段内位置变过的债务人现在按**当刻**位置选资产规则/清算政策 ⇒ `degradedFrom` 读数与
  实际选中的 rule/policy 可能变。
- **`pickTargetPosition`（`ModeMigrationSettlement:845`）**：源户段内位置变过、且计划未带 `primaryPositionId` 时，
  新建目标户的位置可能换档（`relationToMeans/surplusRole` 同档优先）。
- **日志面**：新增 INFO/DEBUG 行（见 §5）——只影响输出，不影响状态。
- **逐日 advance / 每段恰好 1 个关账日**的世界：`base` 已含上次关账的值 ⇒ **逐值不变**（这一点让 app 侧大多数
  30 天分段用例的期望值不动）。

## 5. 新增日志（§一.9）

- `DEBT_STRESS_BRANCH_FIRED`（**INFO**，每个"有户达阈值"的关账日一行）：`day cycle householdsAtThreshold threshold
  thresholdLiquidations classDeclines classDeclineMigrations`。
- `DEBT_STRESS_THRESHOLD_FIRED`（**DEBUG**，逐户、稳定 `HouseholdId` 序）：`day cycle household stressCycles
  threshold thresholdLiquidations classDecline classDeclineMigrated classDeclineReason`。
  ⇒ 齐了控制方要的三件事：第几个关账周期 / 压力累计到几 / 触发了什么。
- `LIQUIDATION_PLAN`（既有 DEBUG）增 `cycle=`，与上面按 `cycle` 对齐。

## 6. 可能受影响的既有测试断言（**扫描结论，未跑 test**）

判据：只有"同一 `EconomySession`（= 同一次 advance / 同一个 stepper）内出现 ≥2 个关账日"或"同日内 E6a/E5b/迁移先改过
`currentPositionId`"才会变值；逐日 advance 的世界逐值不变。

- 高风险：`simos-app/src/test/java/io/mosire/simos/app/time/EconomyCycleHealthTest.java`（真三国世界 240 天、8×30 天段，
  逐关账日断言人口/粮/布/债/货币守恒与市场存活）——只要某段内含 2 个关账日或段内发生模式变迁，读数会变。
- 中/低：`simos-app/.../world/ProductionRuntimeSeedSmokeTest.java`、`HaulServiceCommoditySeedTest.java`（种子/配置冒烟，
  未见多日 advance 循环）；`MarketTopologyBookSingleRegionTest.java`。
- 日志面：`simos-app/.../logging/EconomyLoggingTest.java`（新增事件；该用例用"已激活空经济推 1 天"⇒ 无户达阈值，预期不受影响；
  但若有用例断言"某档下再无其它 event=" 需复核）。
- 无影响（已核）：`HomeModeMigrationProbeTest`（自建 `World/Mode` 探针，不走 `EconomySettlement`）、
  `ProductionEfficiencySettlementTest`（推 1 天）、`EconomyRoundTripTest`（往返夹具，`consecutiveDebtStressCycles=0` 字面量）。
- 现树中 `RealTwelve*3650` / `SevenHex*3650` / `RichWorldTest` 等长跑类**已不在本树**（仅 `.claude/worktrees/llmsd/` 一份副本，不参与本树构建）。

## 7. 未完成 / 未验证（如实）

1. **ⓐⓑⓒ 三条验收判据未实测**（本批只到"编译过"；不跑 test/verify/world）。预测：ⓒ（单段可达 ≥2）由本批修法直接兑现；
   **ⓐ（`0→60` 一参 vs `0→30→60` md5 全等）与 ⓑ（1/3/12/360 段四份 dump 全等）预计仍不通过** —— §3 的次源（迁移/排序路径
   整条读段首 `base`）未修，属下一个责任区。
2. **未做变异自证**（改坏 ⇒ 红 ⇒ 还原）——按纪律留到测试轮。
3. **未跑其它世界/长跑**；未验证 120 天周期世界、`three-powers`、GM-FX 补丁世界。
4. 保留的 3 个同族点（`ExpectedProfitBook:974`、`MerchantCapacityPool:566/584/634`）**未修**，见 §3 理由与最小修法方向。
5. `isActive(base)`（`:132`，实现 `:129-137`）仍读段首 `base.classStandings()` —— 它是"本世界是否接线了 E5b 表"的**闸门**，不是逐日读数；
   留待接线面统一裁决时一并定夺（本批不动）。
