# D-032 RA1b2c1 标量 LAND 债务清偿 + 纯库存财富 · 实现任务书（2026-10-19）

> **状态：可派单。** 前置 RA1b2b 已完成：LAND 唯一持久来源是 `landArableSupply + landHoldings`，`assetShares` 中 LAND 已无条件非法。本单只改债务清偿和纯库存财富，不改土地市场撮合。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`；只动本树，不碰主树。

## 1. 父设计锚点

- v2：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` §1 S7、§4.1、§5、§8、§10、§11。
- 复核：`docs/superpowers/reports/2026-10-19-d032-standard-agriculture-v2-review-closeout.md` E6–E10。
- RA1b2b 台账：`.superpowers/sdd/2026-10-19-d032-ra1b2b-scalar-land-source-cutover/impl-ledger.md`。
- 当前现场：
  - `DebtFirstSettlement.java` 仍按 `OwnershipStake(asset=LAND)`/`AssetShareId` 整块抵地；0 价仍会整块转地。
  - `PureInventoryWealthBook.java` 仍从 `assetShares` 取 LAND。
  - RA1a 的 `LandBook.apply(LandTransfer)` 与 RA1b2a 的 `LandLedgerState` 已支持 `(hex, owner, quantityMilliMu)` 标量转移。

```text
父序列：S7 市场轮第一步：库存/债务清算（货币 → 库存 → 土地）
本单：把土地腿从 AssetShareId 整块改为 LandHolding 标量；不改市场撮合。
```

## 2. 冻结口径

### 2.1 纯库存财富

```text
wealth(household) = 钱 + 商品库存 + Σ LAND
LAND = floor(quantityMilliMu × landPriceMilliPerMu / 1000)
```

- LAND 来源 = `landHoldings`（owner=household）；按 holding.hex 所属市场区取 `MarketTable.landPrice`。
- `landPrice.empty()` ⇒ 不计入并 DEBUG；`0` ⇒ 计入且价值 0。
- 不扣债务；不含工具/作坊/机器/船。
- 同一户持有多个 hex 时逐条求和，排序按 `LandHoldingKey` 自然序。
- 仍保留现有钱/商品的估值/冻结/无价跳过语义。

### 2.2 债务抵地

在货币、库存两腿之后，若剩余债务 > 0：

- 债务人土地候选 = 该户 `landHoldings` 中 `quantityMilliMu > 0` 的所有 `(hex, owner)`，按 `LandHoldingKey` 自然序。
- 每条地价取该 holding.hex 所属市场区当前 `MarketTable.landPrice`：
  - `empty()` ⇒ 跳过（不转、不减本金）；
  - `0` ⇒ **跳过**（0 价土地不自动用于债务清偿；市场 0 价成交仍合法）；
  - `<0` 不可能（市场表守卫）。
- 正价下：
  - `fullValue = floor(q × price / 1000)`；
  - 若 `fullValue > 0 && fullValue <= remaining` ⇒ 转移全部 q，本金减 fullValue；
  - 若 `fullValue > remaining` ⇒ `neededQty = floor(remaining × 1000 / price)`，只转 `min(q, neededQty)` 的标量数量；`actualValue = floor(neededQty × price / 1000)`，本金减 actualValue。若 `neededQty <= 0` 或 actualValue <=0 ⇒ 跳过该 holding，不得转 0 对价数量。
  - 若 `fullValue == 0`（正价但数量过小取整为 0）⇒ 跳过，不得转地。
- 一笔债务可在同一轮跨多个 holding 抵；不得跨 hex 直接用数量。
- 土地所有权转移走 `LandBook.apply(LandTransfer)` 或同一校验的唯一写口；转移后维护 `landHoldings` 工作副本。
- 本轮取得的每个 `(hex, creditor)` 数量写入 `ctx.landAcquiredQuantityThisRound`（数量累加，不写整 key set）。
- 不得扩出 LAND `AssetShare`、LAND grant、LAND assetSource、LAND rightsStake；RA1b2b 构造不变量会拒绝。
- 债权人全部死亡 ⇒ 债消灭、土地留债务人；债务不足资产 ⇒ 余债继续；不得新增抵押/担保/Pledge。
- 日志：INFO 聚合本轮 land 转移笔数/数量；TRACE 逐笔 `DEBT_FIRST_M2_LAND_TRANSFER`，字段改 `debtor/creditor/hex/quantityMilliMu/price/contractValue/remainingAfter`，不得再写 AssetShareId。

## 3. 责任区六要素

### 3.1 责任归属

你负责 `PureInventoryWealthBook` 与 `DebtFirstSettlement` 的 LAND 标量化，以及所有主代码调用点签名同步。你不负责市场土地订单/撮合。

### 3.2 最终能力

- 新世界债务清偿可从 `landHoldings` 任意数量抵地，正价、empty、0 价三种语义正确；0 价不转地。
- 债权人按纯库存财富排序仍正确包含土地价值。
- 没有 LAND `assetShares`/grant/assetSource/rightsStake 再被生成。
- 债务清偿后 `landHoldings` 每 hex 总量守恒、账户/债务写回同一 revision。

### 3.3 可动范围

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/PureInventoryWealthBook.java
simos-economy/src/main/java/io/mosire/simos/economy/time/DebtFirstSettlement.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayDebtPipeline.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayExpectedProductionPipeline.java
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketRoundRunner.java
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketBuyerOrdering.java（仅签名/构造）
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketMatcher.java（仅 snapshot 输入，如编译器要求）
simos-economy/src/main/java/io/mosire/simos/economy/land/（可新增纯 helper）
.superpowers/sdd/2026-10-19-d032-ra1b2c1-scalar-land-debt/
```

禁止：市场土地订单/撮合、Seeder、App/GUI、测试、POM/XML、tools/docs、`simos-production/**`、当前脏文件（EconomySession、LaborSurvivalBook、ProductionInputDrawSettlement、SettlementTickChainLog、app test/tools）。

### 3.4 交账

`impl-ledger.md` 含 S7 嵌入点、`file:line`、纯财富/债务地腿数据流、影响表、偏离、未做/blocker。报告含改动文件、编译 rc、数值行为变化、旧测试失效、未完成项。

## 4. 验收判据（测试 owner 后续）

1. 同 hex/多 hex 持有：正价债务按 neededQty 部分转移，余债与剩余持有量精确。
2. fullValue == remaining、fullValue < remaining、fullValue > remaining 三支。
3. landPrice empty ⇒ 不转、余债不变；0 ⇒ 不转、余债不变；正价可转。
4. 0 取整数量/0 价值 ⇒ 跳过。
5. 纯库存财富：多 hex LAND 按各自市场区地价求和；empty 不计、0 计 0；不扣债。
6. 债务排序用纯财富仍含土地；无 LAND assetShares 依赖。
7. 抵地后 creditor 的 acquired 数量 map 正确累加；`assetShares`/grant/assetSource/rightsStake LAND 仍为 0。
8. 债权人全灭/无资产/资产不足边界不变。
9. main 编译绿，production 零改动，ChangeSet 组件仍 36。
10. 市场土地订单/成交本单未改，行为留 RA1b2c2。

## 5. 纪律与编译

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 只写生产代码；不写/不改/不跑测试；不跑 test/verify；不 commit/push/merge/stash/checkout；不碰主树。
- 看到别人未提交改动不要接手/还原/格式化。
- 发现与用户原话/五条铁律冲突 ⇒ 停手报告，不得自裁。

## 6. 用户原话附录（逐字）

```text
我来说一下土地的数据结构吧，作为商品的土地记录在库存中，实际上写的是，什么hex的土地，例如一个家户有0,0的土地X亩，0,1的土地Y亩，在还债的时候如果排到土地，也是还具体hex的土地，实际可以通过罗列穷举推算出每一个hex固定的耕地量
土地价为0就不能转移了吗？
工资债就是一般债务，家户到家户的，和其他债务一同计算，允许使用库存，我说不允许是不让你在第一次付工资的环节直接上库存
2 抵押是什么？如果你说的是还债的话，定一个还债顺序就可以解决了，那就是先还给有钱的——没钱的没能力组织催收
```
