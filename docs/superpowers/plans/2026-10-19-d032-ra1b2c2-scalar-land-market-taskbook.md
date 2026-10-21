# D-032 RA1b2c2 标量 LAND 统一市场 · 实现任务书（2026-10-19）

> **状态：可派单。** 前置 RA1b2b/RA1b2c1 已完成：LAND 唯一持久来源是 `landArableSupply + landHoldings`；资产表/占用/企业/名册中不再允许 LAND `AssetShareId`；债务土地腿已标量化。
> 本单把土地订单、撮合、成交、产权转移、本轮取得标记全部切到 `(hex, quantityMilliMu)`；商品+土地仍走同一个 `MarketRound`、同一个 `MarketMatcher.matchAll` 入口。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`；只动本树，不碰主树。

## 1. 父设计锚点

- v2：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` §1 S7、§4.1、§4.3、§5、§8、§10、§11。
- 复核：`docs/superpowers/reports/2026-10-19-d032-standard-agriculture-v2-review-closeout.md` E4/E6/E8/E9/E12。
- 前置台账：
  - `.superpowers/sdd/2026-10-19-d032-ra1b2b-scalar-land-source-cutover/impl-ledger.md`
  - `.superpowers/sdd/2026-10-19-d032-ra1b2c1-scalar-land-debt/impl-ledger.md`
- 当前现场：
  - `MarketIntent.landShare`、`LandOrder.shareId`、`MarketLandBook.LandFill.shareId` 仍按整块 `AssetShareId`。
  - `MarketOrderPlanner.planLandOrders` 强制订单数量 == 份额数量；`MarketMatcher.matchLand` 按 shareId 分组一卖一买；`MarketTradeExecutor.executeLandTrade` 走 `transferLandBlock`。
  - `MarketLandIntentBook` 仍从 `ctx.assetShares` 找 LAND、按整块卖/买，且自动买盘有现金/正收益门。
  - `MarketRound` 只有商品资产工作副本与旧 share 取得集合；RA1b2a 已加数量 map，但本单要让它驱动业务。

## 2. 冻结业务口径

### 2.1 订单形状

```text
LandOrder {
  side: BUY | SELL
  actor: HouseholdId（内部转 ActorRef 仅用于账户/日志）
  hex: HexCoord
  quantityMilliMu: long > 0
  limitPricePerMu: long >= 0   // 0 合法
  payWith: CurrencyId
  maxBudgetMilli: long >= 0     // 已放弃“必须现金够”作为挂单门的旧语义；实际支付仍防双花
}
```

- **不出现** `AssetShareId` / block / sequence / 整块相等。
- 自动订单按 `(actor, LAND, hex, side)` 合并；同 key 多来源只保留一笔，数量累加（`Math.addExact`），限价必须一致或取更严格值并在日志具名；不允许 actor 级全删。
- 卖方数量不得超过当前 `(hex, owner)` 持有量；买方可任意正数量，无供给只是 unmet，不得拒挂。
- 土地市场三态仍由 `MarketTable.landPrice: OptionalLong`：
  - `empty` = 未定价：不生成自动订单、显式订单具名拒、不成交、不改价；
  - `0` = 明确免费：可成交并转移，payment=0；
  - `>0` = 正常区级价，市场成交价使用它并受买卖限价约束。

### 2.2 撮合

- 仍在商品区内/跨区撮合之后，由 `MarketMatcher.matchAll` 内部土地分支执行；公共入口/生命周期只有一个。
- 逐市场区 → 逐 hex → 卖单/买单稳定序；买方按纯库存财富降序 → HouseholdId 升序 → 订单稳定键；卖方按 HouseholdId/订单稳定键。
- 一笔卖单可**部分成交**并跨多个买方；一笔买单可跨多个卖方/hex？本批按同一 hex 的需求，跨 hex 由多个订单表达。双方剩余量按最小量成交。
- 成交价 = 该 hex 所属市场区的 `landPrice`；必须满足卖方最低限价、买方最高限价。
- 成交数量：
  ```text
  q = min(buy.remainingQty, sell.remainingQty,
          buyer/seller 当前可转移持有，必要时现金/债务可承受量)
  payment = floor(q × landPrice / 1000)
  ```
  - 若支付能力不足且 deferred 通道可用（买卖双方都是家户），现金付可用额、余额形成普通货币债，仍可按 q 成交；
  - 若 deferred 不可用，则 q 缩到现金可支付的最大数量；不可成交的原因具名 INFO；
  - 0 价时 payment=0，直接转移。
- 同一买方商品+土地共享预算：商品先结算；土地阶段使用商品结算后的可花现金快照，且同一买方/币种在本轮多次土地成交时逐笔扣减剩余预算，不得双花。
- 同一卖方同一 hex 先聚合为一张卖单；若多张卖单仍出现，按稳定序消费剩余持有，超过部分具名拒绝，不得伪造土地。
- 未成交/无对手方订单只进入 unmet/未成交读数，不记债。

### 2.3 产权转移与本轮取得

- 唯一写口：`LandLedgerState.transfer` / `LandBook.apply(LandTransfer)`（或同一校验的唯一 helper）。
- 成交后立即更新本轮工作副本 `landHoldings`：
  - seller 减少 q；0 行删除；
  - buyer 增加 q；
  - 每 hex 总量守恒；
  - 买方 `(hex,buyer)` 的 `landAcquiredQuantityThisRound` 累加 q（下一生产 tick 才可用，当前 tick 生产/市场仍不可见）。
- 买方/卖方必须是已登记活家户；非家户主体不成交。
- 土地不搬运商品、不产生运费/税/Pledge。
- 本轮市场结束后把 round 的 `landHoldings` 工作副本写回 `EconomyStateBuilder`；旧 `landAcquiredThisRound` share 集合本单可以删除或保持空/不再消费，但**数量 map 必须成为唯一生产者/消费者**。

### 2.4 自动意向

- `MarketLandIntentBook` 改从 `landHoldings` 读：
  - 需求：已入选位置 `requiredLand − 当前可用 landHoldings` 的正缺口；无地/少地户可生成买盘，挂单不以当前库存/现金为门；数量为缺口或工程最小整块（若无法确定规模，用该 hex 产业 `capacityPerUnit[LAND]` 的最小一份）。
  - 供给：本轮预期未使用的自有土地；先扣掉已入选生产位置覆盖的 `(hex,owner)` 数量；本轮新取得土地不得自动再卖。
  - 卖方/买方同 actor 不得自成交；同一 actor 同一 hex 不得同时有自动买卖单（净额化或具名跳过）。
  - 0 价市场可生成卖单/买单，但不要以“未定价”生成订单；empty 跳过。
- 原先“无地户必须有正收益且现金够才挂买盘”的自动门删除；正收益/规模可作为数量标定，但不能阻止挂单。实际支付/债务在撮合层处理。
- 本轮新取得通过 `landAcquiredQuantityThisRound` 数量扣减，不能整 key 排除；如有 100 旧持有 + 30 本轮取得 ⇒ 可用 70。

### 2.5 价格与读数

- 下一轮地价仍按市场轮 D/S 与 `MarketPricer.adaptiveNextPrice` 计算；只对 hasLandPrice 市场写；empty 不生成/不改价。
- `LandFill` 改为 `hex/quantityMilliMu/unitPrice/payment/paidCash/deferred/buyer/seller/currency`，不含 shareId。
- INFO：`LAND_ORDER_REJECTED`、`MARKET_LAND_ROUND`；TRACE：逐笔 `LAND_TRADE`。业务拒绝 INFO，契约故障 ERROR 不降级。
- 已有 `MarketReport`/GUI 只读接口尽量保持字段兼容或做最小具名替换；本单不扩全套 GUI，但不得让 360 tick 统计因缺字段而崩。

## 3. 责任区六要素

### 3.1 责任归属

你负责把 LAND 市场链从 `AssetShareId` 整块改为 `(hex,quantityMilliMu)` 标量，并让商品+土地仍由同一 `MarketRound`/`MarketMatcher.matchAll` 生命周期处理。

### 3.2 最终能力

- 新世界可以挂标量 LAND 买卖单、部分成交、任意数量转移、0 价成交；
- 无供给只 unmet，不记债；有成交但现金不足可走普通货币债；
- 本轮取得土地下一生产 tick 才可用；
- 不产生任何 LAND `AssetShareId`/block/grant/assetSource/rightsStake；
- `landHoldings` 每 hex 守恒且市场结束后写回同一 revision；
- production 继续只收 `Map<AssetKind,Long>`。

### 3.3 可动范围

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketIntent.java
MarketIntentBook.java
MarketLandOrder.java
MarketLandBook.java
MarketLandIntentBook.java
MarketOrderPlanner.java
MarketMatcher.java
MarketTradeExecutor.java
MarketRoundCore.java
MarketRoundWitherBook.java
MarketRoundRunner.java
MarketSettlement.java
MarketModelBook.java
MarketReport.java
MarketReadout.java
SettlementDayMarketPipeline.java
SettlementDayClosePipeline.java（仅 land 写回）
SettlementDayContext.java（仅 land 工作态）
simos-economy/src/main/java/io/mosire/simos/economy/land/（可新增纯 helper）
.superpowers/sdd/2026-10-19-d032-ra1b2c2-scalar-land-market/
```

禁止：Seeder/App main、债务、production、测试、POM/XML、tools/docs、当前脏文件（EconomySession、LaborSurvivalBook、ProductionInputDrawSettlement、SettlementTickChainLog、app test/tools）。若编译器强制多改一个 main 文件，最小化并具名报告。

### 3.4 交账

`impl-ledger.md` 含 S7 嵌入点、`file:line`、订单/撮合/成交/产权/预算数据流、影响表、偏离、未做/blocker。报告含改动文件、真实编译 rc、数值行为变化、旧测试失效类别、未完成项。

## 4. 验收判据（测试 owner 后续）

1. 订单数量 1/3 可成交，剩余买卖单继续；不再整块相等。
2. 同一买方商品+土地/多笔土地成交共享预算，不双花。
3. 无供给买单只 unmet 不记债；有成交现金不足时形成普通货币债；0 价成交 payment=0。
4. empty 地价不生成自动订单/显式订单具名拒；0 地价合法。
5. 卖方超持拒绝；同 key 自动订单合并；同 actor 同 hex 无自成交。
6. 部分/多笔成交后每 hex landHoldings 守恒；买方 acquisition 数量正确累加。
7. 本轮取得土地在本 tick S3/S4 不可用（数量扣减）；下一 tick 可用。
8. 全链无 LAND AssetShareId/grant/assetSource/rightsStake。
9. 商品+土地仍在同一 MarketRound/同一 matchAll 入口，无第二 matcher/阶段。
10. main 编译绿、production 零改动、ChangeSet 36 组件；360 tick 留最终统一执行。

## 5. 纪律与编译

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 只写生产代码；不写/不改/不跑测试；不跑 test/verify；不 commit/push/merge/stash/checkout；不碰主树。
- 看到别人未提交改动不要接手/还原/格式化。
- 复杂时可先拆成可编译子层（订单形状 → 撮合 → 写回），但同一份台账记录，不换 writer。
- 发现与用户原话/五条铁律冲突 ⇒ 停手报告，不得自裁。

## 6. 用户原话附录（逐字）

```text
为什么土地市场和商品市场是分开的？我的原话多次提到两者是统一的……
LAND在市场上不应该是一个标量的标准商品吗，想扣多少扣多少，为啥要引入块
土地价为0就不能转移了吗？
你的意思是，买完地之后，下一轮生产规划里这个不作为可用于生产的条件？太逆天了
如果一个家户真的在市场tick里买到土地，那直接判之后的生产不直接用就行了，然后下一轮市场轮开始选生产方式的时候，再把对应的库存土地产权介入计算
```
