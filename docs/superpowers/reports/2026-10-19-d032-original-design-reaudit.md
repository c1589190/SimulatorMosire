# D-032 原始设计复核（2026-10-19，侧线 worktree）

> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> 复核依据：`2026-10-02-architecture-design-source.md` 的 D-032 U1–U23（541–769 行）、2026-10-18 后续逐字修订（设计 §14–§45）、master dispatch §2/§2A、P3/P4 约束书。
> 本报告只做复核，不是新的玩法裁定；不替代 P6 的验收/变异自证。

## 1. 结论

当前**不是“可以进入最终测试”的状态**。P4c3 的代码已编译通过，但 P4d、P5 和 P3 的土地市场接线仍未完成；测试源码也尚未迁移到 P4c3 的新形状。

原始口径中，P4c3 后的工资/债务/锚方向与 2026-10-18 最新裁定一致；旧审计 A1–A5 的已修复/已被取代情况见 §3。复核发现 **2 个实现未达到已定架构口径**，均不需要新的玩法裁定：

1. **P3 的 M3 土地意向缺失**：`ProductionPlanBridge.marketIntents` 只生成 COMMODITY 意向，没有任何 LAND 意向；因此正常 tick 中土地市场没有家户买卖订单，P3 的“土地 intent”验收未满足，P5 的“无地户买地后下一轮组织”也无法成立。
2. **统一市场只有同一入口，没有同一撮合器**：`MarketRound` 有独立的 `landOrders` 列表，`MarketRoundRunner.clearOncePerCycle` 在商品区内/跨区撮合之后调用独立的 `MarketLandMatcher.match(ctx)`。它共享同一轮、预算和冻结生命周期，但不是用户口径的字面“同一订单簿/同一撮合器，土地只是订单变体”。

另有两项**未完成的范围**（不是新裁定）：

- P4d：`RolePayment.MoneyWage(BarterDiscountPolicy)`、`BarterDiscountPolicy`、`BarterReferencePrice` 及其 payload/schema 仍在 production 定义面。
- P5：仍是 `tenancy_*` / `wage_farm` / `family_farm` 多 mode、租佃 rent 默认和阶级槽位映射；尚无单一 `grain_farm`、初始锚和按 hex 的初始土地/锚播种。

## 2. 原始 U1–U23 的当前核对

| 原始口径 | 当前状态 | 说明 |
|---|---|---|
| U1 技术以生产方式可选升级体现 | 部分/有意延后 | production 有定义/角色/经验/实例；升级方案与“科技进步”本轮明确不阻塞农村 tick（2026-10-18 U18）。 |
| U2/U4/U10 家户经验、解锁、主副业由投入自然形成 | 已落地 | `ProductionExperience`、`unlocks`、角色经验增长、不可撤销解锁均在 production；原审计经验/解锁用例仍通过。 |
| U3 迁移经验按人口稀释、财债按比例/相加 | 已落地（旧审计范围） | 旧审计的迁移稀释用例通过；本轮未重跑全部财债迁移组合。 |
| U5 熟练度公式可配置、文化不直接提供生产倍率 | 已落地 | `RestrictedExpression`/`ExperienceTrack.masteryFormula`；文化 aptitude 链已退役。 |
| U8 生产方式/预计实际/状态归 production，经济关系归 economy | 已落地 | P3 使用 `probeRoster`/`execute`；economy 不复制效率公式。 |
| U13/U14 多户名册、角色经验可配、产品份额（含零劳动地主） | 已落地 | 本轮独立探针：`RoleDistribution` 得 400/400/200；同劳动 10、角色系数 1000/500 ⇒ 家户经验 10/5。 |
| U15 直接产品份额、地主可零劳动收剩余 | 已落地 | `RoleDistribution` 不按劳动重切产品份额；`probeRoster` 允许零劳动 organizer。 |
| U16/U19/U20 雇工工资进入选业、可闲置、缺工累加加价/招满缓慢降价 | 已落地到代码 | P4c1/P4c2：WageDemand、EmploymentLock、市场日 M8 后 `WageAnchorBook.applyObservations`；P4c2 纯函数探针已通过。 |
| U21/U23 工资债普通 0‰、还债可用库存、抵物折价可配 | **后续裁定取代** | 2026-10-18 §35–§38：第一次工资只付货币、不拿库存抵工资；未付余额成普通 `WAGE_ARREARS` 货币债；还债阶段才可用库存。P4a 按后者实现，不恢复 U21 抵物。 |
| U22 R2 共享资产不重复占用 | 旧审计已验 | `D4AssetOccupancyBookTest` / `UnifiedBudgetNoDoubleBookingTest` 仍在；P4c3 未改该路径。 |
| 土地市场、土地可交易、新土地本轮不用、债务直接土地抵债 | 部分 | 身份/区级地价/整块转移/新土地 marker/债务直接抵债均在；但正常 tick 无 LAND 意向（见 §3.1）。 |
| 无 Pledge/抵押/担保/特定资产优先受偿 | 已落地 | P2d 删除 Pledge；P4c3 只保留普通货币债与锚。 |
| 统一市场“同一订单簿/同一撮合器” | **未完全达到字面** | 见 §3.2。 |
| 经济 main Java ≤1000 行 | 已达成（现有拆分） | 终态守卫仍属 P6。 |

## 2.1 2026-10-19 用户纠正：M6/M7 每 tick 都做

复核初稿曾把“非市场日仍重排生产”列为偏差；用户明确纠正：**第二次生产方式选择（M6）、家户生产与入库（M7）每 tick 都必须做**，不是市场日专属。tick contract §3.3 已据此改为“非市场 tick 只不开市场/不还本金/不产生新市场意向，但仍做 M6 实排 + M7 生产/入库”。本报告不再把逐日 M6/M7 视为缺口；只保留“M3 预期市场意向/雇佣锁的跨 tick 边界需与实现继续对齐”的观察。

## 3. 复核发现的架构/功能缺口

### 3.1 P3 土地意向没有生成（P1，功能断路）

**现场**：`simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionPlanBridge.java` 的 `marketIntents(...)` 只有两个 `new MarketIntent(...)` 构造点，均为 `MarketIntentKind.COMMODITY`；方法末尾明确写着“当前状态没有‘块主明确挂卖’的事实源 ⇒ 不凭空生成自动卖地单”。

**后果**：
- `MarketIntentKind.LAND` 只存在于类型、转换和撮合层，正常 tick 不会产生 LAND 意向；
- 土地市场因此没有家户卖单/买单，`MarketLandMatcher` 只在夹具或显式注入时可见；
- P5“无地户买地后下一轮可组织”无法从正常市场流程发生。

**第二个断点**：`ProductionPlanBridge` 构造 `PositionPlan` 时（P3e 中途版本约 `:563-575`）把 `landQuantityMilliMu` 固定传 `0L`；因此即便有人从 `PositionPlan` 推导土地需求，拿到的也是 0。土地需求必须从 production probe 的 `assetRequired().get(LAND)` 或 `scale × capacityPerUnit[LAND]` 派生，并与当前可用/已占土地比较，才能得到“需要买多少地”。这是当前“生产位置不产生土地需求”的直接代码原因。

**归属**：economy（P3/P5 间新增土地意向形成责任区），不是 production 公式问题。

### 3.2 字面上的“同一订单簿/同一撮合器”未达到（P1，架构偏差）

**现场**：
- `MarketRoundCore` 有独立的 `List<LandOrder> landOrders`；商品订单则走 `ctx.buys`/`ctx.sells`。
- `MarketRoundRunner.clearOncePerCycle` 先 `matchWithinRegions` / `matchAcrossRegions`（商品），再 `match(ctx)`（`MarketLandMatcher`，土地）。
- `MarketLandMatcher` 和 `MarketOrderPlanner.planLandOrders` 是独立类型；`SettlementDayMarketPipeline.appendMarketIntent` 把 LAND intent 转成 `LandOrder`，不是与商品同一订单对象。

**已满足的部分**：同一 `MarketRound`、同一 `clearOncePerCycle` 入口、同一轮冻结生命周期；土地买方读 `spendableMoneyOf`，商品冻结后剩余预算不可双付；同一轮结算。

**未满足的部分**：不是字面上的“同一订单对象/同一撮合器”。按 master §2A.3 的“不得做两个匹配器”，这是一个需要补齐的架构偏差。若实现上采用“同一订单序列 + 同一匹配循环，按订单变体分派金额/产权腿”，可保留商品既有算法而不改变数值；不能只改文档把它说成已满足。

### 3.3 工资需求 target 实际总是走工程默认，未用 roster 计划劳动（P1，数值偏差）

**现场**：
- `ProductionPlanBridge` 在生成 `PositionPlan` 时（约 `:887-900`）把 `hiredLaborMilliPerDay` 的两个分支都写成 `0L`：`role.payment() instanceof RolePayment.MoneyWage && participant.household().equals(organizer) ? 0L : 0L`。
- `wageDemandsOf`（`:585-587`）因此总是 `position.hiredLaborMilliPerDay() = 0`，再走 `standardLaborMilliPerCycle / cycleDays` 工程默认。
- M3 的 `organizerWageCost` 同样用 `wageTargetOf(..., wageDemands=空)`（`:545-549`）走工程默认，而不是 roster 的实际计划雇工劳动。

**后果**：工资锚观测的 target、M3 预期工资成本、M8 的工资 due 可能和真实 roster 计划劳动不一致；缺工/招满信号会被工程默认扭曲。这是 P4c1 规格“优先 PositionPlan.hiredLaborMilliPerDay”未真正落地。

### 3.4 零劳动 organizer/产品份额位置在 economy 选择层被跳过（P1，功能断路）

**现场**：`SettlementDayExpectedProductionPipeline:160-165` 和 `SettlementDayActualProductionPipeline:140-143` 都对 `position.selfLaborMilliPerDay() <= 0` 直接跳过。production 的 `probeRoster` 允许零劳动 organizer，但 economy 的贪心选择不会把纯收租/纯组织者位置选中，因而不会产生 `WageDemand`、雇佣锁或生产计划。

**后果**：U15 的“零劳动地主/组织者 + 工资招工”在 production probe 层可行，但在 economy 选择层仍断路；A3/A4 不能算完全关闭。修复需要把“零劳动但正收益的组织者/产品份额位置”作为合法入选位置，同时不消耗本户劳动预算，并给工资目标/雇佣锁留出表达。

### 3.5 多地点工资付款人会被具名拒绝（P1，跨 hex 风险）

**现场**：`WageSettlement` 的 `WagePayerLocationPrecheck` + `requireSingleSettlementLocation` 要求一个 payer 本轮恰有一个 settlement location；只要同一雇主在两个 hex 有生产位置，就会 `wage-payer-location-unsupported`。

**后果**：P5 允许跨 hex 买地/经营；同一家户拥有多个 hex 土地后，M8 工资结算会拒绝整组，跨 hex 本地雇农无法完成工资支付。需要在 P5/P3d 收口前验证并修复为按 location/roster 分组支付。

### 3.6 legacy fallback 判据把“没有正收益位置”误当成“没有 roster”（P1，可能掩盖新链）

**现场**：`EconomyDayPipeline:65-67` 用 `ctx.expectedProductionPlan.byUnit().isEmpty()` 判定 legacy fallback。一个有 roster、但因为缺锚/缺地/收益为负而没有入选 unit 的世界，也会进入旧链 `legacyConsumeAfterBase + driveProduction + settleMarket`。

**后果**：新链的合法“全部位置失败/无业”会被误判成旧世界，旧生产/旧市场链可能掩盖新链问题，甚至绕过两遍编排。判据应改为“生产 roster 是否存在”，而不是“是否选中了 unit”。

### 3.7 Entry 自动建 unit 依赖非空 demands（P1 风险，待 P5 确认）

**现场**：`SettlementDayEntryPipeline.applyEntry` 只有在 `!ctx.base.demands().isEmpty() && !ctx.planPool.isEmpty()` 时才运行 entry；`EconomySeeder` 当前没有播种 `demands`。如果 P5 只播种初始土地/生产单位而不播种 demands，土地买入后的无地户可能没有机会通过 entry 建 unit。

**状态**：这是 P5 Seeder 验收前必须确认的接线假设；不是已证实的缺陷，但会影响“无地户买地后下一轮组织”。

### 3.8 M3 每个 tick 运行的事实（按用户纠正保留）

用户 2026-10-19 明确：**第二次生产方式选择（M6）和家户生产/入库（M7）每 tick 都要做**；当前代码 M3 第一遍也在每个 tick 跑（`EconomyDayPipeline` 的 `planExpectedProduction` 不在 `marketDay` 条件内），M6/M7 也在每个 tick 跑。M6/M7 已按用户纠正定为合法；M3 的跨 tick 雇佣锁/市场意向边界仍需在 P5 前明确，但不应再把“每天做生产”本身当作缺陷。

### 3.9 市场日基础消费被按生产周期 120 天准备，违反 5 tick 口径（P0，数值断路）

**现场**：
- `ProductionPlanBridge.marketIntents`（`:988`）先取 `cycleDays = max(ctx.cycleDaysByHousehold)`；`:1005-1015` 又用该 `cycleDays` 乘 `row.naturalNeeds()` 生成基础消费买盘，`:1031-1037` 又用它计算可售剩余的自留量。
- `SettlementDayProductionPipeline` 的 `cycleDaysByHousehold` 是**生产周期**（Seeder 的 `IndustrySeeder.CYCLE_DAYS = 120`），不是市场轮间隔。
- `MarketConstants.MARKET_RESTOCK_INTERVAL_DAYS = 5` 才应是“到下一市场轮的基础消费”天数。

**后果**：新链市场日会给家户准备 120 天的口粮/自然需求，而不是 5 tick；这正好重新引入了 P2a 已删除的长期生活储备，并且会挤压生产投入/土地购买的预算。P2a 只删了旧 `MarketSettlement` 的 35 天储备，P3 的新 `MarketIntent` 路径又把它以 120 天形式带回来了。P6 必须加判别用例：市场日基础消费买盘总量应只覆盖到下一市场轮（5 tick），不是生产周期。

### 3.10 M6 仍会把“实际不可行”的雇佣锁写进劳动承诺（P1，原子性/劳动错配）

**现场**：`SettlementDayActualProductionPipeline` 先在 `:75-87` 把通过 unit/roster/预算检查的锁放进 `validLocks`；随后 `:103-113` 在实际位置 `positionNet <= 0` 或 `fact == null` 时只 `continue` 并记 `locked-position-infeasible-after-market`；但 `:155` 仍执行 `materializeLocks(ctx, validLocks, rosters)`，`:158` 也把整个 `validLocks` 放进 `ActualProductionPlan`。

**后果**：一个本轮实际不可行、没有被选入 `chosenFacts` 的雇佣关系，仍会写入劳动承诺/实际计划；可能出现“计划里没这个位置，但劳动承诺里有这笔工”的错配。应先把锁过滤成实际可行的集合，再 materialize，并让 `ActualProductionPlan.locks` 与之一致。

### 3.11 全链路日志目前是阶段计数，不是逐户链路（P2，可观测性）

**现场**：
- `ProductionPlanBridge.logExpectedPlan` 的 `"households"` 实际是 `chosen.map(unit).distinct().count()`（数的是 unit，不是家户）；
- `D032_P3_EXPECTED_PLAN` 只有 positions/households/locks/intents/wageDemands 汇总，没有逐户位置/收益/雇佣/意向；
- `D032_P3_ACTUAL_REPLAN`、`D032_P3_EXECUTED` 也主要是计数；
- M8 逐义务 readout 有 `WageSettlementReadout`，但没有一条把 M3→M6→M7→M8 串到同一家户/同一 unit 的 tick 链日志。

**后果**：小世界跑起来后，无法只靠日志回答“这户为什么没种地/为什么没被雇/为什么工资没付/为什么没买到粮”。P6 前应补一个 INFO 级别的 `D032_TICK_CHAIN` 汇总 + DEBUG 逐户链路（或等价），并修正 `households` 计数口径。日志不得代替测试，但必须能支撑真实链路诊断。

### 3.12 P3 土地质量的表达仍是隐含的

`OwnershipStake` 没有独立 `qualityPerMille`；当前 `IndustrySeeder`/`LandOwnershipSeeder` 用每 hex 的 `muPerHex × 地形系数` 把质量折进可分配面积/数量。这样能保证“每 hex 最大农业规模不超过可耕土地×质量系数”的总量约束，但**没有单独的 quality 字段**。若“质量”只作为有效面积系数，当前可实现；若需要独立质量读数/市场估值因子，需在 P5 明确。此项是工程建模选择，不改变用户已定的“价格按市场区一个价”。

## 4. 旧审计 A1–A5 的当前结论

| 项 | 现状 | 证据 |
|---|---|---|
| A1 U20 调价未进日循环 | **已修复** | P4c2：市场日 M8 后 `SettlementDayWagePipeline` 聚合 target/matched → `WageAnchorBook.applyObservations`；P4c2 纯函数探针通过；P4c3 保留。 |
| A2 预计用组织者经验、实际用出工者经验 | **新 roster 路径已修复** | 独立探针 `probeRoster`：同条件同劳动 H1 经验 0 / H2 经验 1000，expectedScale=150、actualScale=150；旧单户 `probe` 仍只代表一户，未用于新 roster 期望路径。 |
| A3 零劳动 organizer + 招工被判 labor-short | **production probe 已支持；economy M3 仍未接通** | 独立探针 `probeRoster` 对零劳动 organizer + wage target 1000 返回 feasible=true；但 `SettlementDayExpectedProductionPipeline:160-165` 对 `selfLaborMilliPerDay() <= 0` 直接 `continue`，纯组织者位置不会入选，也就不会生成 WageDemand/雇佣锁。A3 需要 economy 选择层修复。 |
| A4 产品分成角色仍按整池收益排序 | **逐户估值已接线，但零劳动产品份额位置被选择层跳过** | `evaluateRoster` 先 `RoleDistribution.allocate`，再按 `outputValueByHousehold` + 应收 − 应付 − 投入负担生成逐户 `PositionFacts`；但 `SettlementDayExpectedProductionPipeline:160-165` 对 `selfLaborMilliPerDay() <= 0` 跳过，零劳动地主/纯产品份额位置不能独立入选。 |
| A5 抵物按初始库存而非逐笔余额重排 | **已被后续工资口径取代** | 2026-10-18 §35 起工资不再抵物；P4a 删除 wage inventory lookup/barter。A5 不再是本链验收项；债务阶段仍有独立的库存/资产处置排序，需在 P6 验证。 |

## 5. P4c3 代码状态

- 生产代码主链旧标识符/旧命令/旧 fallback 扫描为 **0**；`productionWageAnchors` + `wageCarryRemainders` 是仅存工资状态，`EconomyData`/`EconomyChangeSet` 为 **34 个组件**（36−2，非任务书原写的 35）。
- `tools/mvn-lock.sh -pl simos-economy -am -DskipTests compile` → BUILD SUCCESS，rc=0。
- `tools/mvn-lock.sh -pl simos-app -am -DskipTests compile` → BUILD SUCCESS，rc=0。
- 唯一非执行残留：`simos-production/.../ProductionRosterProbeRequest.java` 的旧 `ProductionWagePosting` Javadoc 文字（不属于 P4c3 允许文件；P4d/文档收口时清）。
- **未迁移测试**：`EconomyRoundTripTest`、`EconomyCodecTest`、`W3WageSettlementContractTest`、`W3WageSettlementTickIntegrationTest`、`W4SurvivalTickIntegrationTest`、`R2AssetBudgetReadoutTest`、`McpCoverageTest`、`SimosToolsTest` 仍引用旧类型/旧 seed；当前 testCompile 预期红，不能宣称测试阶段通过。

## 6. 需要用户裁定的事项

**没有新的玩法裁定需要用户现在做。** 上述 §3.1/§3.2 是已定口径的实现缺口，按用户已说的“同一订单簿/同一撮合器”“土地可买卖”“无地户可买地”补齐即可。P5 的数值（初始土地、初始工资锚、质量系数）按用户 2026-10-18 已授权的工程标定处理。

如果用户希望把“土地质量”从有效面积系数提升为独立字段/读数，或希望土地买卖采用不同于“未用土地自动挂牌、按预期利润/现金买地”的规则，才需要新的玩法裁定；在此之前不擅自新增机制。

## 7. 下一步（按责任区，不并行）

1. **P3d**：补 M3 LAND 意向形成（卖出未用/失配土地、按预期利润与现金生成整块买单），并把统一市场收口到“同一订单序列 + 同一撮合入口”的字面形态；保持商品既有算法与数值。
2. **P4d**：删除 production `RolePayment.MoneyWage.barterDiscount`、`BarterDiscountPolicy`、`BarterReferencePrice`、payload/schema/readout 字段；旧 payload fail-closed。
3. **T1**：迁移 P4c3/P4d/P3d 后的测试夹具与命令/组件计数；跑 economy/app 定向与全量 `clean test`。
4. **P5**：单一 `grain_farm`、Seeder 初始土地/锚、删除 tenancy/rent/阶级 mode 映射；随后重新跑原始设计验收集。
5. **P6**：独立测试、变异自证、文件大小守卫、`clean verify`。
