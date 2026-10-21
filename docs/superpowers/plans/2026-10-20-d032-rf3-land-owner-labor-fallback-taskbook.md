# D-032 RF3 任务书：土地取得者的 farm unit 动态劳动回退

> **父计划：** `docs/superpowers/plans/2026-10-20-d032-rural-360-fix-plan.md` §3 F3
> **父目标：** v2 S2 进入/迁移/组织变化 + S3 生产位置选择；LAND `(hex, quantityMilliMu)` 归具体家户。
> **状态：** 可派实现；只写生产代码到编译过，不写/不跑测试。

## 1. 现场

RF1–RF2e 后 360 tick 已全 `OK`，三次收获，但仍有：

- 32 个 landless / 新地主户在债务抵地后持有 LAND（合计约 34M milliMu）；
- 它们的 farm unit 是创世时保留的 0 地 unit，`ProductionRoster.organizer.plannedLaborMilliPerDay = 0`；
- `ProductionPlanBridge.probeRosterOf` 按 roster 的 0 计划劳动做 `RoleDistribution`，产品份额为 0，
  `evaluateRoster` 位置净收益为负/0，因此 M3/M6 不选它；
- `LaborCandidateBook.candidateSlotsOf` 对 `plannedLaborMilliPerDay == 0` 的 PRODUCT_SHARE participant 直接丢弃，
  即使它已持有土地，也不会生成劳动 slot；
- 结果：landless 新地主有地但无劳动计划，原 landlord 无地 unit 仍被 `asset-location-scope-missing` 拒绝；
  `PRODUCTION_REJECTED` 仍约 42–46/tick，后续农业周期只靠部分 farm 支撑。

## 2. 目标能力

在**不新增持久状态**、不改 roster 历史快照的前提下，为当前旧 per-household farm unit 做动态回退：

- 一个 land-based unit 的 organizer participant 若 `plannedLaborMilliPerDay == 0`、但：
  - 该 unit 的 operator 当前有可用 LAND 占用；且
  - 该户当前 `laborMilli > 0`；
  则本次 M3/M6 试算/位置经济使用该户当前可用劳动作为有效计划劳动；
- 同一 fallback 必须让 `RoleDistribution` 给该 organizer 正产品份额，不再因为 planned=0 得 0 份额；
- `LaborCandidateBook.candidateSlotsOf` 必须为这种 organizer 生成 slot，让 M6 实际 labor grant 能发生；
- 实际 labor 仍由 `LaborQueueBook` 用当刻预算封顶，不得把 fallback 当作第二份劳动权威；
- 不持久化改写 roster；不新增 ChangeSet/Codec 组件。

## 3. 约束设计

### 3.1 动态 helper

在 `ProductionPlanBridge` 增加 package-private helper（名称可调整）：

```java
static long effectivePlannedLaborMilliPerDay(
    ProductionProcess unit,
    IndustryDefinitionView industry,
    ProductionParticipant participant,
    Map<HouseholdId, HouseholdEconomy> householdEconomies,
    SettlementIndex index)
```

规则：

- `participant.plannedLaborMilliPerDay() > 0` ⇒ 原值；
- 否则仅当：
  - `participant.organizer()`；
  - `ProductionPlanBridge.isLandBased(industry)`；
  - `index.assetAllocationOf(unit.id()).getOrDefault(AssetKind.LAND, 0L) > 0`；
  - 该 participant 家户存在且 `laborMilli() > 0`；
  才返回 `household.laborMilli()`（沿用毫小时/日量纲）；否则返回 0。
- 不做随机、时间、Map 迭代序；同输入同结果。
- 不修改 roster 行；helper 只是本次试算/排队的有效值。

### 3.2 三个读点必须同源

1. `ProductionPlanBridge.probeRosterOf` 的 `ProductionRosterProbeParticipant` cycle labor 使用 helper 值 × `cycleDays`；
2. `ProductionPlanBridge.evaluateRoster` 的 `plannedByParticipant` 使用 helper 值 × `cycleDays`；
3. `LaborCandidateBook.candidateSlotsOf` 的 PRODUCT_SHARE participant 过滤条件允许
   `planned > 0 || effectiveFallback > 0`，且只对 land-based operator / 有 LAND 占用 / 户劳动>0 的情况放行。
   helper 不能在三处各写第三份公式；若 `LaborCandidateBook` 拿不到 `SettlementIndex` 的 asset allocation，
   可传入一个已算好的 fallback map/predicate，但必须与 helper 同源/同顺序。
4. `ProductionRoster` 的 `plannedLaborByHousehold()` 仍只用于历史快照/全局上限读数；不得把 fallback 写回状态。

### 3.3 不在本批做

- 不实现完整 v2 单 hex 标准农业聚合；
- 不改 `LandProductionProjection` 为第二权威；
- 不删除 0 地 unit；
- 不改 craft/weave 的关系/roster 种子；
- 不实现土地买卖/租佃/抵押；
- 不通过把 `plannedLabor` 写成持久值来“修数据”。

## 4. 允许修改文件

- `simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionPlanBridge.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/LaborCandidateBook.java`
- 仅编译必需时：同目录最小相邻文件（先报告）

禁止：测试、EconomyData/ChangeSet/Codec、production 定义、app seeder、主树、GUI/服务。

## 5. 日志

- 新增 DEBUG 事件 `LAND_OWNER_LABOR_FALLBACK`（或等价）：
  `unit`、`household`、`landAllocation`、`plannedRosterLabor`、`effectiveLabor`、`reason=land-owner-zero-planned-labor`；
- 同一 unit/户每 tick 只打一条，避免逐位置刷屏。

## 6. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
```

真实 rc=0。报告改动文件/方法、三处读点是否同源、数值行为变化、受影响硬编码/既有断言、未完成；
账本 `.superpowers/sdd/2026-10-20-d032-rf3-land-owner-labor-fallback/impl-ledger.md`。

## 7. 验收锚点（测试 owner 后补）

- 债务抵地后 landless 户持有 LAND 且 `laborMilli>0` ⇒ M3 出现其 farm 位置；
- 其 `RoleDistribution` 产品份额 > 0，不只是试算可行；
- M6 `LABOR_QUEUE_PLAN` 对该 farm 出现 `GRANTED`；
- 其 farm unit 不再因 roster planned=0 被排除；旧 landlord 无地 unit 仍应被具名拒绝；
- 不新增持久状态，roster 原值不变。
