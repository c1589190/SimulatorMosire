# 生产缺陷修复任务书：S7-A 执行时按当刻库存封顶卖出

> **状态：T1 gate 红灯暴露的生产缺陷；只写生产代码到编译过，不写/不跑测试。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。

## 1. 现象（T1 报告）

RC0a 把市场移到 S4/S5/S6 之后，但 S3 生成的 sell intent 仍按 S3 当刻库存封顶；执行到 S7-A 时，S4 消费/生产/S6 工资等已经改变了库存/冻结，旧卖单超过当刻可用量，`applyTransfer` 抛：

```text
转移会花掉家户账上已冻结的商品：家户=hh-0_-3-rural-landed_peasant 商品=grain 余额=166203 冻结=6052938 扣减=3162
转移把家户账扣成了负数：家户=hh-0_2-rural-landed_peasant 商品=grain 余额=0 扣减=4348
```

复现：

- `tools/gate.sh full` 中 `EconomyCycleHealthTest.oneSegmentInRealThreePowersWorldKeepsMoneyConservedAndMarketOpen`
- `Z7RemittanceE2ETest.rateFiveHundred...` / `rateZero...`

## 2. 冻结修复

在 `MarketTradeExecutor.executeTrade(...)` **任何账户/冻结/账本写入之前**，按当刻卖方可卖量封顶本笔成交：

```text
stock = stockOf(round, sell.seller, commodity)
frozen = frozenGoodsOf(round, sell.seller, commodity)
ownSellReservation = sell.frozenRemaining
available = max(0, stock - frozen + ownSellReservation)
cappedQuantity = min(requestedQuantity, available)
if cappedQuantity <= 0:
    sell.blocked = MarketUnfilledReason.ALL_RESERVED
    INFO MARKET_SELL_STOCK_EXHAUSTED
    return 0
if cappedQuantity < requestedQuantity:
    INFO MARKET_SELL_STOCK_SHRUNK (requested/capped/stock/frozen/reserved)
    quantity = cappedQuantity
```

- `ownSellReservation` 只表示本卖单自己的冻结；不得把所有冻结都当可卖；
- 必须在 `settlementUnitPrice(...)` / route/运费/税/货腿/钱腿之前完成；
- 后续所有价格/运费/税/成交/退还都使用 capped quantity；未成交部分自然留在槽位；
- 不改变正常情况（capped == requested）的逐值行为；
- 不改变 S3 生成 sell intent 的公式；只修 S7-A 执行时 stale 卖单。

## 3. 允许修改

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketTradeExecutor.java
```

必要时只读/复用 `MarketRosterBook.stockOf/frozenGoodsOf`；**禁止**改 S3 intent、MarketMatcher、账户/转移写口、EconomyData/ChangeSet/Codec、seeder、tests、主树。

## 4. 验收锚点（测试 owner 后补）

- requested=100、current available=60 ⇒ 本笔 executed=60；冻结算 own sell reservation，不被误当不可卖；
- current available=0 ⇒ executed=0，无账户/冻结/账本变化；INFO 具名；
- available=requested ⇒ 逐值行为不变；
- stale sell intent 不再触发 `applyTransfer` 负数/冻结超限异常；
- 不改变 LAND trade / 商品正常路径。

## 5. 编译

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 只写生产代码；不写/不跑 test/test-compile/verify/package；不 commit/push。
- 账本：`.superpowers/sdd/2026-10-20-d032-stale-sell-intent-fix/impl-ledger.md`。
- 报告列：改动位置、封顶公式、日志、编译 rc、未验证项。
