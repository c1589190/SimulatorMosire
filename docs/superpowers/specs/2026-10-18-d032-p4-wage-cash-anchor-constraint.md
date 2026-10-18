# D-032 P4 约束设计书：工资现金支付 + 普通债 + U20 工资锚（2026-10-18）

> 状态：控制方工程约束已冻结；P4 分批实现。
> 上位：master dispatch §10、tick contract §3.2 M8/M9、P3 约束设计书。
> 用户原话逐字见 master §17；P4 相关原话见 §9。
> 权威顺序：用户原话 > D-032 U1–U23 > master dispatch > 本文件 > 当前代码。

## §1 目标与非目标

### 1.1 P4 目标

1. 工资第一次支付**只付现金**；现金不足部分成为普通家户对家户的 **WAGE_ARREARS 货币债**；不拿库存抵工资。
2. 删除工资 barter/库存抵扣链（`BarterDiscountPolicy`、`WagePayableInventoryLookup` 在工资/招工路径、barter 读口）。
3. 下一市场轮 M2 自动同池处理工资债；本 tick M8 生成的工资债不进本 tick M2。
4. U20 工资锚：按 `(市场区, IndustryId, ProductionRoleId)` 锚定单位劳动工资；缺工加价、招满降价、跨雇主共享；只在市场轮 M8 之后调价。
5. 本 tick 实际工资 due 不被锚更新追改；M6 锁定雇佣不被锚更新影响。
6. 删除旧 `productionWageDefaults`/`productionWagePostings`，改为单张 `productionWageAnchors`；保留 exact carry。
7. production 定义面 `RolePayment.MoneyWage` 删除 barter 字段；barter 类型/payload/tool schema 退役。

### 1.2 非目标

- P5 的真实 `grain_farm` Seeder/初始土地/初始锚。
- P6 的新测试、变异自证、全量 clean verify。
- 旧档兼容；不做商品本金债/Pledge/库存抵押。

## §2 P4 批次

| 批次 | 目标 | 编译 |
|---|---|---|
| P4a | 工资只现金、普通债、删 barter 结算/读口/inventory skip | economy + app |
| P4b | WageAnchor 状态/Key/Codec/ChangeSet/显式命令+seed | app |
| P4c1 | M3 WageDemand + M8 锚消费 + M3/M6 锚快照 | app |
| P4c2 | 市场日 M8 后 U20 观测/调价 + 锚级 app 读口 | app |
| P4c3 | 删除旧工资表/命令/seed/codec 与 posting fallback；锚为唯一报价权威；组件 36→34 | app |
| P4d | production 定义面 barter 字段/schema 清理 | production + app |

P4a 可先行；P4b/P4c 需要 `WageDemand`；P4d 与 P4a/P4b 串行，不得并行改共享文件。

## §3 工资只现金（P4a）

- 每付款人（payer）一个现金预算：账户余额 − 已冻结；按稳定 `(unit,role,worker)` 顺序支付。
- `due = wagePerLaborMilli × receiptLaborMilli / 1000 + carryBefore`；`carryAfter = due mod 1000`；BigInteger/安全除法，溢出 `wage-quote-overflow` fail-closed。
- 每笔 `paidMoney = min(dueRemaining, cash[currency])`；现金用完为止；0 工资不特判、不生成债。
- 工资转移只带 moneyLegs，`TransferReason.WAGE_PAYMENT`；账户写口仍 `EconomySettlement.applyTransfer`。
- 未付差额立即 `DebtContractBook.upsert(..., DebtUnit.Money(currency), DebtTerms.wageArrearsDefault(), DebtSource.WAGE_ARREARS, arrears, ...)`；0 差额不 upsert。
- payer account 缺失按现有 `wage-payer-account-missing` 业务拒绝；不伪造账户。
- **不得**读商品库存、市场价、barter discount 或生成商品腿。

## §4 barter 删除范围（P4a/P4d）

### 4.1 P4a 结算/读口/招工路径

- `WageSettlement` 删除 `BarterDiscountPolicy`/`BarterReferencePrice`/`MarketTable`/`MarketTopology`/`CommodityId` 依赖、`Obligation.discountPolicy`、`InventoryBudget`、`barterOrder`、`payBarter`、`referencePrice`、`marketOf`、`sumBarterValue`。
- `WageSettlementReadout` 删除 `barterQuantity`/`barterPaidValueMilli`；守恒式 = `due = paidMoney + arrears`。
- `SettlementDayWagePipeline` 删除 `WagePayableInventoryLookupImpl` 构造/传参。
- `SettlementDayProductionPipeline` 删除 wage matching inventory snapshot；`LaborCandidateBook`/`LaborQueuePlanBook` 删除 MONEY_WAGE 的 inventory skip，不得因“库存 lookup 不可用”阻断招工。
- `AppProductionLookups` 删除 `wagePayableInventory` 注入；`WageSettlementReadoutFeed` 仍发布 money/arrears。
- 保留 `InventoryReservationBook` 等其他库存预算使用；不得误删 `AccountInventorySnapshotLookup`。

### 4.2 P4d production 定义面

- `RolePayment.MoneyWage` 删除 `barterDiscount` 字段；删除 `BarterDiscountPolicy`/`BarterReferencePrice`。
- `ProductionDefinitionPayloads` 删除 `barterDiscount` 解析；`EconomyUpsertIndustryTool` schema/文案同步。
- 旧 payload/seed 带 barter 字段按 Alpha fail-closed/重建。

## §5 普通工资债（P4a）

- 复用 `DebtSource.WAGE_ARREARS` + `DebtTerms.wageArrearsDefault()`（0‰）+ `DebtUnit.Money(currency)`。
- 不在 P4 新增 WageDebt/Pledge/商品本金债。
- M8 后生成的工资债在下一市场轮 M2 由 `DebtFirstSettlement` 同池处理；结构上 M8 在 M2 后，不需要同日排除。
- 新 wage 链不再生成/传递 `sameDayWageDebtIds`；旧退出路径的旧 `DebtRepaymentSettlement` 若仍引用，只做编译所需保留，不参与主 M2。

## §6 U20 工资锚（P4b/P4c）

### 6.1 形状

```java
record WageAnchorKey(MarketZoneId marketZone, IndustryId industry, ProductionRoleId role) {}
record ProductionWageAnchor(
    CurrencyId currency,
    long wagePerLaborMilli,      // >=0; 0 合法
    long shortageStreak,
    long satisfiedStreak,
    long lastAdjustedTick,
    long lastObservedMatchedLaborMilli,
    long lastObservedTargetLaborMilli,
    long lastAdjustmentDelta) {}
```

- 锚键 = `(MarketZoneId, IndustryId, ProductionRoleId)`；同锚跨 unit/雇主共享。
- 锚 currency 必须等于该市场区 legalTender；缺锚 = no quote（不是 0）。
- 锚只能由显式 `economy.SetProductionWageAnchor` / seed `wageAnchors` 创建；P5 给初始值。

### 6.2 M3 WageDemand（P4c）

```java
record WageDemand(
    ProductionUnitId unit,
    MarketZoneId marketZone,
    IndustryId industry,
    ProductionRoleId role,
    long targetLaborMilliPerDay,  // >=0
    CurrencyId currency) {}
```

- M3 对每个入选的 `MONEY_WAGE` 位置生成 WageDemand：
  - 优先 `PositionPlan.hiredLaborMilliPerDay`；
  - 若为 0，用 production role `standardLaborMilliPerCycle / cycleDays` 作为工程默认 target；
  - 不得读 inventory lookup。
- 同锚 target = Σ WageDemand.target；同一 unit/role 不重复累加。
- `target=0` 不生成 WageDemand（没有招工需求）；不重置 streak。

### 6.3 观测/调价

- M8 用 `ProductionReceipt.laborContributionsMilli` 按锚聚合 matched。
- 只有市场轮 M8 之后调价；非市场日不动锚。
- 复用 `WagePricingPolicy`/`ProductionWageBook.adjustedPostings` 的同一 momentum/minStep/延迟降价公式，只把 posting 换成 anchor。
- 缺工：target>0 且 matched<target ⇒ 加价；招满：matched≥target 持续超过 delay ⇒ 降价；不穿 0。
- 本 tick due 使用 M8 开始时的锚快照；锚写回在 M8 完成后，M3/M6 锁与 due 不被追改。
- 同锚多 unit 聚合 target/matched；部分 unit 失败只计实际 matched。

### 6.4 持久/读写

- `EconomyData`：删 `productionWageDefaults` + `productionWagePostings`，新增 `productionWageAnchors`；保留 `wageCarryRemainders`。
- `EconomyChangeSet`/Codec/Invariants/Transforms/DayView/BoundaryView/ReferenceValidation/Payload/Seed 同步。
- 删除旧 `ProductionWageDefault/Posting/WagePostingKey/WageDefaultKey/QuoteSource/DefaultLookup` 主链。
- 新增命令 `economy.SetProductionWageAnchor`，payload 至少 `{marketZone,industry,role,currency,wagePerLaborMilli}`；seed `wageAnchors`。
- 读口：义务级 readout 保留 unit/role/worker/currency/labor/quote/carry/due/paidMoney/arrears/debtId，删 barter；另出锚级 `WageAnchorReadout`。
- app 视图 `AppProductionConsumerPort`/`ApiViews` 同步删 barter、改 anchors。

### 6.5 P4c3 旧工资表/fallback 退役（P4 内收口）

P4c2 已完成锚观测/调价与读口；P4c3 只做**删除旧权威与 fallback**，不重开 P4a–P4c2 语义：

1. **状态形状只留锚**：从 `EconomyData`/`EconomyChangeSet`/`EconomyCodec`/`EconomyDataProductionInvariants`/`EconomyDataTransforms`/`EconomyDayView`/`EconomyBoundaryView`/`WorkingDayView`/`EconomyStateBuilder`/`HouseholdReferenceValidation` 删除 `productionWageDefaults` 与 `productionWagePostings`；保留唯一 `productionWageAnchors` 与 `wageCarryRemainders`。组件数 36→34。
2. **命令/seed 退役**：删除 `economy.SetProductionWageDefault` / `economy.SetProductionWagePosting` handler、Shell 注册、CatalogTool 文案与 `wageDefaults[]`/`wagePostings[]` seed/payload 解析；只保留 `economy.SetProductionWageAnchor` 与 `wageAnchors[]`。
3. **模型/读口退役**：删除 `ProductionWageDefault`、`ProductionWagePosting`、`WageDefaultKey`、`WagePostingKey`、`ProductionWageDefaultLookup`、`WageQuoteSource` 及旧 unavailable 报价适配器。`ProductionWageQuote` 可保留为“锚价值对象”；`ProductionWageBook` 只保留 carry/due 与 anchor/posting 共用的 `adjustPrice` 纯函数，不再存 posting/default 表，不再提供旧 `adjustedPostings`。
4. **锚唯一报价权威**：`ProductionPlanBridge.resolveWageQuote` 删除 `fallbackQuoteLookup`/`ANCHOR_FALLBACK` 分支；锚键/锚缺失返回具名 `wage-anchor-missing` 或 `wage-quote-missing:*`，**不得按 0 兜底**。M8 缺锚仍是 ERROR 契约故障、整批不提交；M3/M6 缺锚按位置不可行具名拒绝。
5. **组合根去旧注入**：`EconomySession`/`EconomyDayStepper`/`AppProductionLookups` 删除 `wageQuoteLookup` 注入；app 两个日推进参与者不再传 `wageQuotes()`；`AppProductionConsumerPort`/`ApiViews` 删除旧 posting 视图，保留锚级列表。
6. **不越界**：不删 `ProductionWageAnchor`/`WageAnchorBook`/`WageDemand`/U20 调价；不改工资现金/欠薪债/债务排序；不改 production `RolePayment` barter（P4d）；不改 Seeder 默认锚值（P5）。
7. **验收**：economy/app 编译通过；全树 `grep` 无旧表/旧命令/旧 fallback 主链引用；往返/命令/seed 测试迁移由 T1 在 P4c3 后统一做。

## §7 失败/原子性

- 锚/进位/债务写都在同一 `EconomySession` 工作表，`finish()` 一次构造完整 `EconomyData` → 单 revision。
- 缺锚/键不一致/币种不一致/溢出 ⇒ ERROR fail-closed，整批不提交。
- `WageSettlementReadoutFeed` 发布应在成功提交边界；若当前仍在 close 前，P4 记录为读口风险，P6 可迁移。

## §8 验收边界

P4 代码级：
- 工资链无库存读写/barter 字段/读口/inventory lookup 注入；只出现 money `WAGE_PAYMENT`。
- 未付差额只形成一条 WAGE_ARREARS money 债，0‰；次日 M2 同池。
- 锚键/共享/0/缺锚/市场日调价/M6 锁不变/本 tick due 不变。
- 状态/ChangeSet/Codec/命令/seed 编译通过。

P6：
- 部分/零现金、库存零变化、债 source/terms/金额、次日 M2 同池。
- U20 多 tick 加价/降价/延迟/共享/非市场日不变/本 tick 不追改。
- roundtrip/codec/命令清单/旧 barter 测试迁移/全量 verify/变异自证。

## §9 用户原话附录（逐字，P4 相关）

```text
我记得我做过裁定，工资是需要算的吧？
6、我很好奇价格是根据什么算的……对应位置的工资在单个市场区被锚定，劳动力多需求少劳动力价格下降，反之上升
1 全局取代，如果雇佣方账上没钱支付给受雇方，那就是不支付，不算欠薪债
直接划走，都到这个份上了，有债的出多了也只能受着了
1 不行，不抵押库存
2 用完为止
3 因此也没有抵押问题
我想了一下，要不还是把账面欠薪债加回来吧
2 抵押是什么？如果你说的是还债的话，定一个还债顺序就可以解决了，那就是先还给有钱的——没钱的没能力组织催收
工资债就是一般债务，家户到家户的，和其他债务一同计算，允许使用库存，我说不允许是不让你在第一次付工资的环节直接上库存
```
