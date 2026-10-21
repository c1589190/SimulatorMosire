# RC8c 任务书：DailyDemandBill 与 S7 实际盈余

> **状态：约束设计书/实现任务书；只写生产代码到 `-DskipTests compile` 通过。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父计划：`docs/superpowers/plans/2026-10-20-d032-v4-full-repair-plan.md` §4 RC8c。
> 依赖：RC8a/RC8b/RC8d/RC8e 已 compile 绿，且 360 已到 `OK=360` 基线。
> 父设计：v2 §4.4/§5；v4 §3 S7-A、§5、§11。

---

## 1. S0–S8 锚点

```text
S4 生产日结：
  每户每 tick 生成一张 DailyDemandBill
  - BASE_SUBSISTENCE：Social 当前 naturalNeeds
  - PRODUCTION_DAILY_EXTRA：生产方式每日额外项（有定义才写）
  - PRODUCTION_INPUT：实际生产投入（种子等，当前缺口，不乘 5）
  - 记录 providedFromStock
S7-A 市场：
  - marketPurchaseNeed 在 S4 后、S7 开头派生（同一对象）
  - 卖出 actualSurplus = 当前库存 − 本户同一账单保留项
  - 不再使用 S3 的 expectedProductionPlan.marketIntents()
  - 商品与 LAND 仍走同一订单簿
```

---

## 2. 当前事实（file:line）

- `EconomyDayPipeline.java:103-121`：
  - S3 生成 `expectedProductionPlan.marketIntents()`；
  - S7-A 把这份 S3 intent 传给 `prepareMarketRound`；
- `MarketIntentBook.java:60-140`：
  - 商品 BUY 来自 `inputDemandByOrganizer` + `row.naturalNeeds() * 5`；
  - 商品 SELL 来自 `outputSupplyByOrganizer`，只减 `naturalNeeds*5`，不是同一张账单；
- main 中 `DailyDemandBill` 只有 `PostLandTradeClassCheck` 注释提及，**没有类型/当前 tick 唯一副本**；
- `MarketRoundRunner.java:522-527`：
  - 用 M3 intent 替换同 actor/commodity/side 的 planner 自动订单；
- 现有 `MarketFreezeBook` / `MarketTradeExecutor` 的 stale sell cap 是对旧 intent 的兜底，不应继续作为正常机制。

---

## 3. 目标数据形状（transient，不落盘）

新增 `simos-economy/time/DailyDemandBill.java`（或同包 record）：

```text
DailyDemandBill(
  long day,
  HouseholdId household,
  List<Line> lines)

Line(
  CommodityId commodity,
  DemandSource source,          // BASE_SUBSISTENCE / PRODUCTION_DAILY_EXTRA / PRODUCTION_INPUT / OTHER
  long quantityMilli,           // 本行毛需求（日口径；production input 为当前缺口）
  long providedFromStockMilli,  // S4 已从库存实际提供
  long marketPurchaseNeedMilli,  // S7 派生，只读/工作副本
  long purchasedFromMarketMilli,
  long unmetMilli)
```

- 每户每 tick 恰好一张；
- `quantityMilli >= 0`，`providedFromStock <= quantity`，`marketPurchaseNeed >= 0`；
- 同一 `(household, commodity, source)` 不得重复；
- 不新增 `EconomyData` 持久组件、不进 ChangeSet/Codec；
- `SettlementDayContext` 只放 transient `Map<HouseholdId, DailyDemandBill> dailyDemandBills`（或等价字段）。

---

## 4. S4 账单构建与已提供量

在 S4 生产日结中构建：

### BASE_SUBSISTENCE

- 取 `HouseholdEconomy.naturalNeeds()`（Social 当前注入的逐户日需求）；
- 不重算人口×系数；
- 不读创世 naturalNeeds。

### PRODUCTION_DAILY_EXTRA

- 若 industry/recipe 有每日额外投入定义，用生产定义；
- 当前 32hex 可能为空；空表就是没有该行，不伪造 0 行。

### PRODUCTION_INPUT

- 用 S4 实际生产计划的 `probe.inputRequired()` / 实际投入缺口；
- 只取当前实际未耕种/未投入缺口；
- 不乘 5；
- 不把 S4 已从库存 `providedFromStock` 的部分再次挂买盘。

### providedFromStock

- 消费/投入实际从库存扣减时，在同一账单行累计；
- 不得 S4 扣一次、S7 再扣一次；
- 不额外引入第二套 naturalNeed/消费表。

---

## 5. S7 派生与订单

在 `MarketIntentBook`（或新 `DailyDemandBillMarketIntentBook`）中：

### BUY

对每个账单行：

```text
base/extra:
  need = quantityMilli × MARKET_RESTOCK_INTERVAL_DAYS
  marketPurchaseNeed = max(0, need − currentGoods)
production input:
  marketPurchaseNeed = max(0, quantityMilli − currentGoods)
```

- 仍然 **不以现金/库存作为挂单门**（RF1）：需求全额进 intent；现金不足由成交层记普通债；
- 不得把 S4 已提供部分再次挂成买盘；
- 不读 S3 expected intents。

### SELL

对每户/商品：

```text
reserve = base/extra 的 5 tick 预留 + production input 当前预留
actualSurplus = max(0, currentGoods − reserve)
```

- 用 S4/S5 后的当前库存；
- 不得把未来产出当现货；
- 同一户同一商品不得同时形成无净额自买自卖；
- 仍按 `(actor, commodity, side)` 合并，不能 actor 级整删。

### LAND

- 保留 `MarketLandIntentBook` 的标量 LAND 语义；
- 如可复用 S3 chosenFacts，则保留；若必须用实际计划，显式把 actual position facts 存入 transient，不新增持久组件；
- 不要改 LAND 价格/供给公式。

---

## 6. S7 调用点

- `EconomyDayPipeline` 在 S7-A 不再使用：
  `ctx.expectedProductionPlan.marketIntents()`
- 改为：
  - S4/S7 之间已填 `ctx.dailyDemandBills`；
  - S7 `prepareMarketRound` 从账单生成商品 intents（+ LAND intents）；
  - `MarketRoundRunner` 仍只执行一份统一订单簿。

可以保留旧 `MarketIntentBook.marketIntents` 作为 legacy 未调用路径，但新主路径必须走账单。

---

## 7. 允许修改文件

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/DailyDemandBill.java（新增）
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayContext.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayProductionPipeline.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayActualProductionPipeline.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayMarketPipeline.java
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketIntentBook.java
simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyDayPipeline.java
simos-economy/src/main/java/io/mosire/simos/economy/time/ConsumptionSettlement.java（仅若实际提供量必须从此返回）
simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionInputDrawSettlement.java（仅若实际投入提供量必须从此返回）
```

不改 `EconomyData`/ChangeSet/Codec 持久形状，不改 LAND/价格/债务/工资公式，不改测试。
禁止 test/test-compile/verify/package、commit/push、主树改动。

---

## 8. 验收锚点

### 正例

1. main 中存在唯一 `DailyDemandBill` 类型，每个活户每 tick 恰好一份；
2. 每行 source/quantity/provided/marketPurchaseNeed 可读，且 S4 已提供量不在 S7 再买；
3. S7 BUY/SELL 来自账单 + S4 后当前库存，不再来自 `expectedProductionPlan.marketIntents()`；
4. 基础/额外项乘 5，生产投入不乘 5；
5. 实际盈余不超过当前库存，且保留本户账单需求；
6. 360 仍 `status=OK=360`，无负库存/自买自卖/冻结异常；
7. `MARKET_DEFERRED_PAYMENT`/`MARKET_SELF_MATCH_SKIPPED` 的结构性异常下降（不要求 0）。

### 负例

- 每户多张账单/行情路径各建一份 ⇒ 红；
- S4 已提供量再次挂买 ⇒ 红；
- 生产投入乘 5 ⇒ 红；
- S7 仍使用 S3 expected intent ⇒ 红；
- 卖未来产出/库存为负 ⇒ 红；
- 为修复账单改 LAND 价格/供给 ⇒ 红。

---

## 9. 编译与交账

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 账本落 `.superpowers/sdd/2026-10-20-d032-rc8c-daily-demand-bill/impl-ledger.md`；
- 报告列：账单字段/构建时点、S4 provided 来源、S7 buy/sell 公式、旧 intent 是否废弃、未验证项。

---

## 10. 用户原话附录

```text
2我认为是主要问题，单个家户的实际总提供劳动力、实际总口粮需求等，都是要根据据家户的人员规模（甚至根据Social的设计，还得分年龄段）来算的，这是不是没做？
```

```text
理论上应该按V4架构完整实现
```

```text
还有其他裁定吗，没有的话把修复计划落盘，然后开始修复，直到能重跑360tick
```
