# RC4-D 任务书：同 key 合并编排、价值/土地腿与 S2 前置接线

> **状态：约束设计书/实现任务书；只写生产代码到编译过，不写/不跑测试。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父设计：v4 R1/R5/§3/§4/§7；v2 S2/S7。
> 依赖：RC4-A/B/C、RC3、RC5 已 compile 绿。
> 本单完成后，R1 才真正有可执行的合并路径；仍不做 RC6/RC7/§8。

## 1. 目标运行位置

合并必须在 **S3 之前**完成，且不得让 S3 把 0 人口壳当活户。

采用**组合根预步进合并**：

```text
App 日循环：
M0 Social
→ applyHouseholdPopulationDeltasOnly(Social 本日变化)
→ updateComposition / recomputeLaborBudgets
→ EconomyDayStepper.mergeSameKeyHouseholds(day)
   ├─ 读 EconomySession / AccountSession / HouseholdMergeBook
   ├─ 执行全部结构/价值/土地/债务/经验/人口 outbox
   └─ 不直接写 Social
→ App drain population outbox
→ MigrationSocialBridge.apply(当前 Social, outbox, day)
→ applyHouseholdPopulationDeltas(net deltas)
→ 用新 Social 重刷 composition / laborBudgets
→ stepper.step(day)  （其 S3 看到合并后的活户）
```

这保证：

- S2 语义仍在 S3 前；
- 合并不发生在 step 内，不会出现 S3 读旧 population/composition 的窗口；
- 不在 economy 行直接写 Social 权威；App 仍通过唯一 outbox 桥回写。

## 2. 当前事实（file:line）

- RC4-A：`HouseholdMergeBook.plan(...)` 已产出同 key 合并计划。
- RC4-B：`ProductionRuntime.rekeyCycleContributions(unit,from,to)` 已存在。
- RC4-C：`HouseholdMergeRebindBook.rebind(...)` 已处理非 LAND assetShares / enterprise / rules / roster。
- RC3：`HouseholdTransferSettlement.apply(...)` 已处理人口 outbox + money/goods/debt/experience/labor shrink。
- `EconomyDayStepper` 已持有 `EconomySession`、`AccountSession`、culture lookup（RC4-A）。
- RC4-C 不改 production unit.operator；必须由本单先/同批调用 `reoperateInstance`。
- RC4-C 未处理 occupancy/reservation/demand/wageCarry/shipment buyer；本单补。
- RC4-C 未处理 LAND；本单走 `LandLedgerState.transfer`。
- RC4-C 未搬 money/goods/debt/experience/population；本单调用 RC3。
- 现有旧迁移 organizer guard 仍保留；本单不要求删除它（R1 合并走新入口；旧 migration 仍可安全拒绝）。

## 3. 冻结编排

新增：

```text
simos-economy/time/HouseholdMergeSettlement.java
```

入口：

```text
static MergeResult mergeAll(
    EconomySession session,
    AccountSession accounts,
    HouseholdCultureKeyLookup cultureLookup,
    long day,
    int ordinalStart)
```

以及单笔（供测试/后续 RC6 复用）：

```text
static MergeResult mergeOne(
    EconomySession session,
    AccountSession accounts,
    HouseholdMergeBook.MergePlan plan,
    long day,
    int ordinal)
```

单笔固定顺序：

```text
0. preflight
1. production operator rebind + cycleContributions rekey
2. RC4-C structural rebind
3. 双向 debt rekey / self-net
4. LAND 全量 transfer（LandBook）
5. occupancy / reservation / demand / wageCarry / shipment buyer / meta/treasury guard
6. RC3 HouseholdTransferSettlement.apply(full source population)
7. source 0 人口壳保留；不写 Social / 不写 HouseholdEconomy population
```

### 3.1 preflight

- source/survivor 必须存在，source != survivor，source population > 0；
- 若 source 是 `Government.treasury()` ⇒ 具名拒绝 `household-merge-government-treasury`；
- source 的 frozenMoney/frozenGoods 必须为空 ⇒ 非空具名拒绝
  `household-merge-frozen-account`（RC3 只搬可用余额；首版不搬冻结）；
- `EconomyMeta.planAssetSources` 含 source actor ⇒ 具名拒绝 `household-merge-plan-asset-source`；
- source 非 PRODUCTION 劳动承诺存在且本笔会搬空 ⇒ RC3 已有的 GOV_SERVICE 拒绝可复用；
- LAND 出现在 assetShares 且命中 source ⇒ RC4-C 已拒绝；
- 所有校验完成前不得改任何 work copy。

### 3.2 production rebind

对 `session.productionRuntime().units()` 稳定序（unit id）：

1. 若 `unit.operator() == HouseholdActors.of(source)`：
   - 调 `reoperateInstance(unit.id(), survivorActor, unit.modeKey())`；
   - 必须 `Written`；否则整次失败；unit id/progress/input 不动。
2. 对每个 unit 调 `rekeyCycleContributions(unit.id(), source, survivor)`：
   - `Written/Rejected("instance-unknown")` 的处理：unit 来自同一 runtime snapshot，`instance-unknown` 属契约故障；
   - no-op 允许。
3. 顺序：先 operator rebind 再 cycle rekey；不可反。

### 3.3 RC4-C structural

调用：

```text
HouseholdMergeRebindBook.rebind(
    session.sheet(), session.productionRuntime().definitions().industries(), source, survivor)
```

- 必须返回 `changed` 与 assetIdMapping；
- 若 unit.operator 已 rebound，enterprise/relation organizer 必须与 survivor 对齐；
- RC4-C 失败 ⇒ 整次 advance 失败。

### 3.4 双向 debt rekey / self-net

新增 `HouseholdDebtMergeBook`（可内嵌在 HouseholdMergeSettlement）：

- 遍历全部 `sheet.debtContracts()` 稳定序；
- 把 `debtor/creditor` 中 source 映射为 survivor；
- 映射后 `debtor == creditor` ⇒ 合同本金归零并删除，INFO/ledger self-netted；不落自持合同；
- 映射后两端都不是 source/survivor ⇒ 保持原合同（不需重建 id，因为端点未变）；
- 映射后端点变化 ⇒ 重建 `DebtContract`，新 id 走 `DebtContractId.idOf(...)`；
- 同新 id 已有合同 ⇒ 首版**fail-closed** `household-merge-debt-id-collision`，不静默合并 openedDay/status/terms；
- `householdDebtRefs` 不手写，构造期重建；
- principal/openedDay/lastInterestDay/dueCycle/status/source 原值保留。

### 3.5 LAND 全量转移

- 从 `LandLedgerState.from(sheet.landArableSupply(), sheet.landHoldings())`；
- 对 source 在每 hex 的全部正持有，逐条 `LandTransfer(hex, source, survivor, quantity)`；
- 走 `LandLedgerState.transfer`（唯一 LandBook 校验/写口）；
- 写回 `sheet.landHoldings()`；
- 不写 `landAcquiredQuantityThisRound`（不是市场取得；本 tick 不排除）；
- 失败 ⇒ 整次 advance 失败；每 hex 总量不变。

### 3.6 其余引用

- `AssetOccupancyGrant`：shareId 用 RC4-C mapping；id 走 `AssetOccupancyGrantId` 工厂重建；quantity/interval/status 原值；
- `InventoryReservation`：payer sourceActor→survivorActor；id 重建；unit/location/commodity/quantity/interval 原值；
- `HouseholdDemand`：HOUSEHOLD scope 的 household source→survivor；DemandId opaque 保留；HEX scope 不动；
- `WageCarryKey`：worker source→survivor；同新 key collision 首版 fail-closed（不静默丢 carry）；
- `ShipmentAllocation.buyer` source→survivor（seller 历史保留）；
- `ProductionEnterprise`/rules/roster 已在 RC4-C；
- `ClassShare`/crisisSignals/periodicAdjustments/FlowRow/ClassMembership：source 0 人口壳保留，本单不改；
- `EconomyMeta.planAssetSources` / `Government.treasury` 已 preflight 拒绝。

### 3.7 RC3 价值/人口腿

- targetMode = survivor class standing 的 current position 的 mode；
- targetHex = survivor household view hex；
- population = source current population；
- targetPopulationBefore = survivor current population；
- reason = `HOUSEHOLD_MERGE`；
- `targetIsNew=false`；
- 调 `HouseholdTransferSettlement.apply(...)`：
  - 因 debt 已先重键，RC3 的 debtTake 应为 0；
  - money/goods/experience/outbox/labor shrink 由 RC3 负责；
  - 不修改 HouseholdEconomy population/laborMilli。
- App 在 mergeAll 返回后 drain outbox，并用 `MigrationSocialBridge` 把全部人口转给 survivor；source Social 户保留 0 人口壳。

## 4. App / Stepper 接线

### 4.1 EconomyDayStepper

新增：

```text
public HouseholdMergeSettlement.MergeResult mergeSameKeyHouseholds(long day)
```

- 调 `HouseholdMergeBook.plan(session.dayView(), cultureLookup)`（或当前 sheet 视图）；
- 对每个 plan 调 mergeOne；
- 返回总结果（plans、pop transfers、changed）；
- 不调用 `step`、不写 Social、不调用 `build`。

### 4.2 PopulationEconomyTimeParticipant

在 `stepper.applyHouseholdPopulationDeltasOnly(vital.populationDeltas())` 与
`recomputeLaborBudgets` 之后、`stepper.step(day)` 之前：

```text
merge = stepper.mergeSameKeyHouseholds(day);
if (!merge.populationTransfers().isEmpty()) {
    currentSocial = MigrationSocialBridge.apply(currentSocial, transfers, day);
    stepper.applyHouseholdPopulationDeltas(netPopulationDeltas(transfers));
    stepper.updateComposition(compositionOf(currentSocial));
    stepper.recomputeLaborBudgets(laborBudgetsOf(currentSocial, day));
    // naturalNeeds 可沿用本日已算结果；若必须按合并后人口重算，在同处刷新
}
```

- 该路径与 step 后的 migration bridge 共用同一 Social 桥；不得写第二套人口回写。
- 若 merge 失败 ⇒ 整次 advance 失败，不进入 step。
- 只在 merge 实际产生人口转移时做 Social 桥；空结果不得重复写。
- 不得把 merge outbox 留到 step 后再桥（否则 S3 读旧投影）。

### 4.3 旧 organizer guard

- `ModeMigrationSettlement`/`MigrationPopulationMove` 的旧 organizer guard **本单不删除**；
- 新合并入口自行完成 rebind，不走旧 guard；
- 删除旧 guard 留给后续统一迁移收口。

## 5. 必须达到的行为

1. 同 key 2 户合并后 source Social/economy 壳人口 0、survivor 人口增加；
2. S3 在 merge 后看到 survivor 为唯一活户，source 0 人口壳不被当作生产候选；
3. LAND 全量并入 survivor，每 hex 总量守恒；
4. money/goods/debt/experience 按 RC3 规则随人走，source 壳不丢债；
5. production unit.operator/enterprise.organizer/relation.operator/roster organizer 全为 survivor；
6. cycleContributions source→survivor 精确相加；
7. source 与 survivor 互为债权债务的合同自债净额消失，无 debtor==creditor rows；
8. occupancy/reservation/demand/wageCarry 不悬空；
9. source 0 人口壳 + FlowRow/ClassMembership 保留；无 Social 户删除；
10. 不修改 ChangeSet/Codec 组件形状；不写 DB；不 commit。

## 6. 允许修改的文件

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/HouseholdMergeSettlement.java（新增）
simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyDayStepper.java（只加 merge 入口）
simos-economy/src/main/java/io/mosire/simos/economy/time/HouseholdDebtMergeBook.java（可新增）
simos-economy/src/main/java/io/mosire/simos/economy/time/HouseholdTransferSettlement.java（只允许修复/复用，不改既有语义）
simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyStateBuilder.java（仅补 demands 会话工作副本与 build 带出；不改组件形状）
simos-economy/src/main/java/io/mosire/simos/economy/time/WorkingDayView.java（demands 改读工作副本）
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayEntryPipeline.java（demand 读点改 dayView.demands）
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayMarketPipeline.java（demand 读点改 dayView.demands）
simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java（merge 前置 bridge 接线）
```

**RC4-D 控制方补充裁定（2026-10-20）**：

- `EconomyStateBuilder` 当前没有 `demands` 工作副本，导致 merge 无法在同一次 advance 内重键 HOUSEHOLD 需求。
  允许补一个懒物化 `LinkedHashMap<DemandId, HouseholdDemand>` 工作副本，`build()` 用工作副本/基态；
  **不得新增/删除 EconomyData 持久组件，不改 ChangeSet/Codec 形状，不另造需求权威**。
- 校验原子性按“**session 原子**”执行：所有可前置的 preflight 必须在写前完成；RC4-C 之后的派生 id 碰撞
  允许在写后 fail-closed，但必须在同一 `EconomySession` 内失败退出，不能 `build()`/提案/落 revision。
  App 整次 advance 失败会丢弃 session；不得留下同 revision 半状态。

必要时只读调用 RC4-C/ProductionRuntime；**禁止**改 EconomyData/ChangeSet/Codec 组件、production/culture 契约、seeder、tests、主树；不删除旧 organizer guard。

## 7. 验收锚点（测试 owner 后补）

正例：

1. 2 个同 key 户（population 100/80）在 step 前合并 ⇒ source Social/economy 0、survivor 180，S3 只见到 survivor；
2. LAND 两户同 hex 合并后 total 不变；source 0 持有行；
3. money/goods 守恒；debt 自债净额；旧 source 壳无残留债；
4. production 两 unit rebind 到 survivor 后 enterprise/relation/roster/phase 全部一致；cycle contributions 相加；
5. occupancy/reservation/demand/wageCarry/shipment buyer 无悬空；
6. 0 人口壳保留；FlowRow/ClassMembership 不删。

负例：

- merge 后 S3 仍把 source 当活户 ⇒ 红；
- LAND 直接改 map/总量变化 ⇒ 红；
- source 壳残留正本金自债/debtor==creditor ⇒ 红；
- enterprise/relation/roster/unit.operator 任一半状态 ⇒ 红；
- frozen account/meta plan asset source/government treasury 未拒绝 ⇒ 红；
- 合并失败后 Social 已部分迁移/economy 已部分写 ⇒ 红。

## 8. 编译与交账

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-production,simos-economy,simos-app -am
```

- 只写生产代码；不写/不跑 test/test-compile/verify/package；不 commit/push。
- 账本：`.superpowers/sdd/2026-10-20-d032-rc4d-merge-orchestration/impl-ledger.md`。
- 报告列：改动文件、编译 rc、merge 顺序、App 预步进 bridge、LAND/debt/extra refs、旧 guard 保留理由、可能失效测试断言、未验证项。

## 9. 本责任区不做

- 不删除旧 migration organizer guard；
- 不做 RC6/S7-B、RC7/flow table、§8 整组织交易；
- 不做 culture 传播/同化；
- 不改 LAND 市场/债务公式/市场/工资；
- 不写/不跑测试。

## 10. 用户原话附录（逐字）

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md` §1.2，2026-10-20。

```text
R1：如果一个hex上有多个同一文化属性、同一生产方式和对应阶级性的家户，只是库存和人口不同，那么这两个家户应当合并，目前的有地农民家户早已脱离地主语义了，事实上是小农和地主的集合体——地主导致的灾难性阶级分化事实上也能表示，就是单个hex的有地农民人口极少土地极多，大部分人口都只能接受雇佣
R5：是的，随着人走
```
