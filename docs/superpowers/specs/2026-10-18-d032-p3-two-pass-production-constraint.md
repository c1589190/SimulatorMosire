# D-032 P3 约束设计书：两阶段单家户生产 + 雇佣锁 + roster/probe（2026-10-18）

> 状态：控制方工程约束已冻结；P3 实现尚未开始。
> 上位：master dispatch §9、tick contract §3.1.1/§3.2 M3–M7/§5、P2 约束设计书、农业模板。
> 用户原话逐字见 master §17；P3 相关原话见本文 §13。
> 权威顺序：用户原话 > D-032 U1–U23 > master dispatch > 本文件 > 当前代码。

## §1 目标与非目标

### 1.1 P3 目标

1. 市场日：`M2 债务 → M3 第一遍预期生产编排 → M4 统一市场 → M5 消费 → M6 第二遍实际生产编排 → M7 生产执行 → M8 工资`。
2. 单家户按剩余劳动贪心选择生产位置组合，两遍共用同一 `positionNet`/`positionYieldPerLabor` 公式。
3. M3 锁定雇佣关系（unit/employer/worker/role/labor/wage/currency）；M6 只读，不能换人/改份额/改工资。
4. M6 可重排非雇佣小生产，但不得新增市场订单。
5. M3/M6/M7 都排除 `landAcquiredThisRound` 新土地；卖地/失地位置失败，劳动转无业。
6. production 提供 roster probe 的逐参与户预计产出/效率；economy 消费该只读结果，不复制效率公式。
7. 市场买方排序改用纯库存财富；阶级标签只做结算后投影，不进入生产选择。
8. 跨 hex 自耕拒绝；同 hex 本地雇农可被雇佣；工资现金/欠债细节留给 P4。

### 1.2 非目标

- P4：工资只付货币、普通工资债、U20 工资锚。
- P5：真实 `grain_farm` Seeder、初始土地分布、阶级投影阈值。
- P6：新测试、变异自证、全量 clean verify。
- 不做：生产进步/升级、部分土地成交、跨区土地运费、土地税、旧档兼容。

## §2 P3 批次与可编译边界

| 批次 | 目标 | 主要 owner 文件 |
|---|---|---|
| P3.0 | 市场装配/撮合拆两段 + `MarketIntent` 类型；旧 `settleMarket` 保持行为 | `SettlementDayMarketPipeline`、`MarketIntent` 新类型 |
| P3a | production roster probe 纯增量（不改旧 probe） | `simos-production/contract/**`、`ProductionRuntimeEngine` |
| P3b | 纯库存财富买方排序 + 新土地排除参数 + 市场 M4 接线 | `SettlementOrderBook`、`AssetOccupancy*`、`ProductionProcessBook`、`SettlementDayMarketPipeline` |
| P3c | M3/M6 两遍编排、EmploymentLock、PositionEconomics、A2/A3/A4、土地 intent | `EconomyDayPipeline`、新 `SettlementDayExpectedProductionPipeline`/`SettlementDayActualProductionPipeline`、prospect/labor books、`SettlementDayProductionPipeline` |

同一 `W3` owner 顺序执行；每批编译绿后才进下一批。
P3c 前必须完成 P3.0/P3a/P3b。

## §3 MarketIntent（P3.0 冻结）

```java
enum MarketIntentSide { BUY, SELL }
enum MarketIntentKind { COMMODITY, LAND }

record MarketIntent(
    MarketIntentKind kind,
    MarketIntentSide side,
    ActorRef actor,
    HexCoord location,
    Optional<CommodityId> commodity,
    Optional<AssetShareId> landShare,
    long quantity,             // 商品：毫商品；土地：千分亩，整块
    long limitPrice,           // 商品：毫计价货币/商品单位；土地：毫计价货币/整亩
    CurrencyId payWith,
    long budgetMilli,          // 买侧最大预算；卖侧 0
    int priority,              // 0=基础消费/生产投入；1=其他；2=土地投资
    Optional<ProductionUnitId> sourceUnit)
```

- `COMMODITY` 必须 commodity present、landShare empty；`LAND` 反之。
- 土地 intent 只整块（quantity == share.quantity）；P3 M3 生成的土地 intent 定向 `AssetShareId`。
- M3 只生成 intent，不直接写市场/账户/库存。
- M4 由 `prepareMarketRound(ctx, intents)` 把 intent 转成现有 `BuyOrder/SellOrder/LandOrder`，入**同一个** `MarketRound`；`executeMarketRound(ctx, round)` 调同一 `clearOncePerCycle`。
- 优先级 0/1/2 只影响预算/排序；商品优先级 0 先于 1，土地只吃剩余；不得新造第二套预算。
- 市场成交后：商品入账；土地走 `transferLandBlock`；买地 share id 写入 `ctx.landAcquiredThisRound`。

## §4 市场装配拆分（P3.0）

- `SettlementDayMarketPipeline` 拆：
  - `prepareMarketRound(ctx, List<MarketIntent>) -> MarketRound`：只构造/注入订单，不撮合、不落账；
  - `executeMarketRound(ctx, MarketRound) -> MarketOutcome/void`：只撮合/结算/写回；
  - 旧 `settleMarket(ctx)` 保留为 `execute(prepare(ctx, List.of()))` 或等价，确保未接 P3 的测试/路径行为不变。
- `MarketRound` 的 landOrders 与 commodity buys/sells 同属一个 round；`clearOncePerCycle` 仍是唯一撮合入口（P2f 已实现）。
- M3 生成 intents 后，M4 必须用同一批 intents；M4 不得重新计算生产计划。
- `MarketRound`/MarketIntent 不得落 `EconomyData`/ChangeSet/Codec；只活一次 step。

## §5 生产模型与收益公式（P3c 冻结）

### 5.1 候选与两阶段计划

```java
record PositionEconomics(
    long expectedIncomeMilli,
    long directInputCostMilli,
    long wageCostMilli,
    long laborMilli) {
  long positionNetMilli() = expectedIncome - directInputCost - wageCost;
  long positionYieldPerLaborScaled() =
      floorDiv(positionNetMilli * 1_000_000, max(1, laborMilli));
}
```

- 同一 `PositionEconomics` 纯函数用于 M3/M6；第二轮只换输入事实，不换公式。
- M3 保存所有正收益候选（不仅主位置），供 M6 在剩余劳动内重排。
- 单家户算法：主位置 = 预期净收益最高且可行；剩余劳动继续选下一个正收益位置；直到劳动用完/无可行/无正收益。
- 就业组织者与受雇者都按自己的净收益排序；不得按 HouseholdId 硬填。

### 5.2 生产位置/雇佣

```java
record EmploymentLock(
    ProductionUnitId unit,
    HouseholdId employer,
    HouseholdId worker,
    ProductionRoleId role,
    long laborMilliPerDay,   // > 0
    long wagePerLaborMilli,  // >= 0
    CurrencyId currency) {}
```

- M3 生成 `List<EmploymentLock>`；M6 只读，不得改单位/雇主/雇工/角色/劳动/工资/币种。
- 锁失败只标记 position failed；不得重绑/换人/改份额。
- 锁定但 M7 未接受/业务拒绝的劳动不计工资；工资只按 `ProductionReceipt` 实际接受劳动产生（P4 再接现金/欠债）。
- 非雇佣小生产在 M6 只能在 M3 正收益候选集合内重排；M6 不产生 MarketIntent。

### 5.3 生产投入时点

- M4 市场买入的投入在成交后入库存。
- M5 只做基础消费，不现扣生产投入。
- M6 读 M4/M5 后实际库存/土地/劳动。
- M7 执行生产时才现扣 `inputPerUnit`/周期投入，产出/入库与生产执行同一环节；不得在 M5 前预扣。

### 5.4 阶级标签

- `SocialClassId`/`stratum`/`HouseholdClassRule` 只允许在 M8 之后做投影/读数。
- 生产候选/排序/雇佣/土地选择不得读取 class/stratum/旧 landlord/middle/poor 标签。
- `ClassSlideSettlement` 可在 M8 后或 close 阶段执行；不得让 M3/M6 读其输出。

## §6 新土地排除（P3b/P3c）

- 唯一判据：`SettlementDayContext.landAcquiredThisRound` + `share.asset()==LAND`。
- 不直接过滤 `assetShares` 表；在资产占用/生产请求的 scope 供给匹配中排除，避免 ACTIVE grant 引用被误报 share-missing。
- 新增 `AssetOccupancyRequest/Allocator` 的 `excludedShareIds` 或等价 allowed 判据；默认空集 ⇒ 行为与 P2 逐值相同。
- M3/M6/M7 共用同一 `landAllowed(shareId)` helper；M7 的 `ProductionDayRequest.Step.assetOccupancy` 只取过滤后 allocation。
- 新买土地仍可被出售/抵债？M2 在 M4 前，所以 M4 新买土地不会本轮再进 M2；P3 只排除生产使用，不排除产权记账。

## §7 卖地中断/失地/无业

- M6 逐 position 重新校验：share 存在、owner/operator 仍是组织者、location 匹配、share 不在新土地 marker。
- 失地/卖地 ⇒ position failed；不允许用其他土地替换；自有劳动不写 commitment；锁定雇工保持 failed 记录，不转给其他雇主。
- 失败劳动记 `UNALLOCATED_LABOR`/`POSITION_LAND_LOST` 类日志，不新增持久 UNEMPLOYED 枚举。
- 任何 M6/M7 失败不得留下半条 roster/commitment/账户/资产 grant。

## §8 production roster probe（P3a 冻结）

新增 `simos-production/contract`：

```java
record ProductionRosterProbeParticipant(
    HouseholdId household, ProductionRoleId role, long plannedLaborMilliPerCycle) {}

record ProductionRosterProbeRequest(
    IndustryId plan,
    HouseholdId organizer,
    List<ProductionRosterProbeParticipant> participants,
    Map<HouseholdId, HouseholdProductionConditions> conditions,
    Map<AssetKind, Long> assetOccupancy,
    Optional<ProductionEfficiencyState> carry,
    Map<HouseholdId, ExternalAttributeFacts> participantAttributes) {}

record ProductionRosterProbeResult(
    IndustryId plan, boolean feasible, String reason,
    long baseScale, long outputScale,
    long laborRequiredMilliPerDay,
    Map<AssetKind, Long> assetRequired,
    Map<CommodityId, Long> inputRequired,
    Map<CommodityId, Long> expectedOutput,
    Map<CurrencyId, Long> expectedMint,
    long efficiencyScalePerMille,
    Map<ProductionParticipantKey, Long> participantEfficiencyPerMille) {}
```

- 必须复用 `ProductionEfficiency.evaluate`/`efficiencyScaleFor` 的同一公式；economy 不复制效率公式。
- conditions 按户去重聚合投入/资产；participants 只表达角色与劳动拆分。
- 零劳动 organizer + posting：用 `targetLaborMilliPerDay` 作为需求边界，不得塞假 worker（A3）。
- PRODUCT_SHARE 选业用 `rosterIncomes` 的本户“产品份额值 + 工资应收 − 工资应付 − 投入负担”，不再用整 unit 收益（A4）。
- 旧单户 probe 入口保持不变；P3a 只新增 roster probe。

## §9 A2/A3/A4 验收

- A2：同一 roster 的预计 per-participant efficiency 与 execute 逐参与户 efficiency 一致；不得 organizer-only probe 冒充全员。
- A3：零劳动 organizer + 工资 posting 能形成可行 probe，不误判 labor-short。
- A4：PRODUCT_SHARE 候选排序用本户收益，不再用整 unit 收益。

## §10 纯库存财富（P3b）

- M2 后冻结 `PureInventoryWealthBook.snapshot`，按 wealth desc → HouseholdId asc。
- M4 市场买方 rank 改用同一纯财富快照；旧 `SettlementOrderBook` 净值/债务估值只保留给阶级滑落。
- 不扣债务、不含工具/作坊/机器/船；土地 empty 不计、0 计 0。

## §11 跨 hex 与本地雇农

- operator 自耕劳动：`share.location == household.view().hex()`；否则 `cross-hex-self-labor` 业务拒绝。
- hired_laborer：worker 居住 hex 必须等于土地 hex；`worker-not-on-land-hex` 具名拒绝。仅同市场区不够。
- 跨 hex 无本地雇农 ⇒ position 不可行；不得让所有者自耕补位。

## §12 日志、原子性与验收边界

- INFO：`D032_P3_EXPECTED_PLAN`、`D032_P3_EMPLOYMENT_LOCKED`、`D032_P3_ACTUAL_REPLAN`、`D032_P3_POSITION_FAILED`、`D032_P3_LAND_FILTERED`、`D032_P3_EXECUTED`。
- DEBUG：拒绝 reason、候选收益/劳动、marker 命中；TRACE：逐 lock/position/commodity。
- M3 不写状态；M6/M7 的 roster/labor/account/production ChangeSet 同 revision；失败零半提交。
- P3 可独立验：日序、两轮公式一致、锁不可变、marker 排除、跨 hex、卖地失败、roster probe A2/A3/A4、纯财富 rank。
- P3 不得声称：真实 `grain_farm` Seeder、工资现金/欠债链、阶级阈值、全仓 clean verify。

## §13 用户原话附录（逐字，P3 相关）

```text
1、Social先，饿死了还做买卖？
3、这里每个家户可以选择的，也从单纯的生产方式，扩展为了，生产方式允许的多种生产位置；能选多少个具体的生产位置，取决于理论上干完这个主业，还有多少空下来的劳动力，然后把空下来的劳动力继续去计算，算到没法继续算，也就是贪心
4、接3，在代码层面应该是，首先判断一下如果担任生产主导者位置并且这个生产方式允许主导者雇佣其他家户，那么让家户给出对于雇佣的劳动力对应工资，允许从0开始报——报0/低价的必然结果是没人来，那么下一市场tick招工，让这个家户逐步提升工资，直到有人来；如果有人来了，就开始慢慢降工资，降到人开始走，人走了，规模不满，继续加，处于一个动态平衡；
5、是这样没错，在Simos，单个家户的生产和生活不分开计算
我本来寻思可能有什么问题，但是我转念一想，如果单个家户真的在市场tick里买到土地，那直接判之后的生产不直接用就行了，然后下一轮市场轮开始选生产方式的时候，再把对应的库存土地产权介入计算，这样就可以了；
1 对，本轮只算上一轮入库的实际库存，下一轮单个家户拿到多少土地下一轮算
1、我的意思是整个债务清算在市场轮最开始做就行，这样土地租约虽然没有清算（其实是家户买土地，然后负债），但是效果也达成了
2、雇佣关系不允许，选择了就必须按指定份额参与雇佣关系的那一部分，包括雇佣方和受雇方；其他小生产部分允许，可以根据当前实际库存（土地、劳动力等），选择利润率最高的生产方式参加、算生产产出
2 直接划走，都到这个份上了，有债的出多了也只能受着了
```
