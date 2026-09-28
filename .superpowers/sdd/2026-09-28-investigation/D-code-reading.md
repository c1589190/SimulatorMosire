# D-code-reading —— 第 5、6、7、8 题现状调查（只读代码）

> 调查对象：`/home/cna/SimulatorMosire`，分支 `ts/m1`。
> **如实记一处偏差**：派单写 `HEAD=5b6b2be7`，本工作树当前 `HEAD=c8520f34`（`git log -1` 实测）。
> `git diff --stat 5b6b2be7..HEAD` 只有一条 docs 新增（`docs/superpowers/reports/2026-09-28-economy-system-flow-report.md`，237 行），
> **代码零差异** ⇒ 下列所有 `文件:行` 按当前工作树（= 5b6b2be7 代码态）读，代码态同源。
>
> 本文只回答“现状是什么”，不写设计方案。所有结论给 `简称:行`；代码里没有的写“代码里没有”。

## 0. 简称与文件对照（本文全部引用）

| 简称 | 文件 |
|---|---|
| `SEED` | `simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java` |
| `ES` | `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java` |
| `MS` | `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java` |
| `PS` | `simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionSettlement.java` |
| `EDS` | `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyDayStepper.java` |
| `MR` | `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketReadout.java` |
| `MT` | `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketTrigger.java` |
| `CR` | `simos-economy/src/main/java/io/mosire/simos/economy/model/ClassRow.java` |
| `FR` | `simos-economy/src/main/java/io/mosire/simos/economy/model/FlowRow.java` |
| `IND` | `simos-economy/src/main/java/io/mosire/simos/economy/model/Industry.java` |
| `MKT` | `simos-economy/src/main/java/io/mosire/simos/economy/model/Market.java` |
| `REC` | `simos-economy/src/main/java/io/mosire/simos/economy/model/ProductionRecipe.java` |
| `VOC` | `simos-util/src/main/java/io/mosire/simos/util/economy/EconomyVocabulary.java` |
| `PSPLIT` | `simos-util/src/main/java/io/mosire/simos/util/economy/ProportionalSplit.java` |
| `SO` | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/relation/SubsistenceObligation.java` |
| `BUY` | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/market/BuyOrder.java` |
| `BUDGET` | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/market/Budget.java` |
| `PAY` | `simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyPayloads.java` |
| `SEEDH` | `simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomySeedHandler.java` |
| `EDATA` | `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java` |
| `AV` | `simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java` |
| `POPAPP` | `simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java` |
| `POPDYN` | `simos-social/src/main/java/io/mosire/simos/social/population/PopulationDynamics.java` |
| `ASSET` | `simos-actor-api/src/main/java/io/mosire/simos/actor/api/asset/AssetKind.java` |

---

# 第 5 题：`naturalNeeds → effectiveDemand → BuyOrder → 成交` 每一步

## 5.1 一条总判据（先把结论说清）

- `ClassRow.naturalNeeds`（生理需要的字段）**每个结算日都会被覆写**，唯一写入点是 `ES.withDailyNeed`（`ES:4306-4340`），调用点在消费步 `ES.consumeOwnStock`（`ES:2431`）。
- `ClassRow.effectiveDemand`（字段语义：有支付力的那部分需求）**在 main 结算代码里没有任何写入点**：
  - grep 全 main：只有创世写空表 `SEED:1579`、载荷解析 `PAY:718-721`、以及四处 `new ClassRow` 的原样透传 `ES:4338/4354/4368/4387`；读口 `AV:1209`。
  - 也就是说：**真档创世后它恒为空表**；即使有人用 `economy.Seed` 载荷在创世格/新格写它，结算也不会改它。
- `BuyOrder` 是**瞬时对象**：只在市场轮内由 `MS.ordersFor` 生成（`MS:679-748`），不落进 `EconomyData` 的十个组件（组件清单 `EDATA:110-120`），下一轮重新生成。
- **市场生成买量时不读 `naturalNeeds`，也不读 `effectiveDemand`**：家用口径是“人口 × 35 天生活保留”，经营者口径是“产能折出的必要投入”；预算来自 actor 侧货币会话副本。
  ⇒ “只改 `naturalNeeds`/`effectiveDemand` 的自定义需求”对劳动、开工、投入、产出、成交**没有任何生效日**（没有读取路径，不是“晚几天”）。

## 5.2 对象生命周期总表

| 对象/字段 | 语义（代码注释口径） | 谁写、何时写 | 谁读 | 清零/覆写 |
|---|---|---|---|---|
| `ClassRow.naturalNeeds` | 本期自然需求（生存/再生产；粮 + 布，最小计量单位）`CR:53` | 创世：`SEED:1578` 写 `{grain: firstDayRationMilli(pop)}`（**只有粮，没有布**）；每结算日：`ES.withDailyNeed`（`ES:4321-4339`）由 `VOC.dailyNeedsMilli`（`VOC:219-224`）重算，`0` 的项不落键（`ES:4325-4327`） | 消费 `ES:2436/2443`；低粮追加轮判据 `MS:367`；周期粮分母 `ES:4339`；读口 `AV:530/1206`、`MR:227`；社会侧日压力 `POPAPP:329-334` | **结算日先覆写后读**（`ES:2431` 写在 `ES:2436` 读之前）；周期边界不特殊处理（`ES.withCycleNaturalNeed` 原样透传 `ES:4386`）；人口 0 ⇒ 空表 |
| `ClassRow.cycleNaturalNeedMilli` | 本周期累计自然口粮需要（毫粮，粮专用）`CR:55-59` | `withDailyNeed` 逐日加当日粮需求（`ES:4329/4339`）；新周期第一天重置为“当天那一份”（`ES:980-985` → `withCycleNaturalNeed` `ES:4378-4389`） | 读口 `AV:531/566/1208`、`MR:229`；饿死判据的旁证 | 新周期第一天重置；不置 0（`ES:4375-4376`） |
| `ClassRow.effectiveDemand` | 有支付力的那部分需求（字段语义）`CR:54` | 创世空表 `SEED:1579`；`economy.Seed` 载荷可写 `PAY:718-721`；**main 结算零写入**，只在 `ES:4338/4354/4368/4387` 原样透传 | **行为路径零读者**；仅视图 `AV:1209` 发出 | 无清零；若载荷写过则一直留着 |
| `BuyOrder` / `SellOrder` | 本轮订单（瞬态）`BUY:38-46`；`PlannedOrders` 是轮内记录 `MS:285-293` | `MS.ordersFor`（`MS:679-748`）在市场轮内生成；`clearOncePerCycle` 收集成 `BuySlot/SellSlot`（`MS:472-488`） | 撮合 `MS.pairUp`/`executeTrade`、读口 `MS.planOrders`/`MR:210-218` | **不落盘**；轮内对象随轮结束丢弃；下一市场轮按当时状态重生成 |
| 成交/冻结 | 货腿 + 钱腿各一条 `Transfer`，走唯一 applier `ES.applyTransfer` | `MS.executeTrade`（`MS:1186-1352`）；冻结在轮末释放（`MS:827-836`） | `ProductionLedger.transfers`、`MarketReport`（瞬态） | 订单不留存；冻结净额回基值（`MS:369-376` 的注释 + `releaseAllFreezes`） |
| `FlowRow.unmetNeed`（旁证） | 本周期“需求 − 实得”的逐日累加（毫单位，逐商品）`FR:64-69` | 消费步 `ES:2450-2460`；市场/借粮/再吃冲减 `MS.reduceUnmet`、`ES.trimUnmet` | 饿死判据、读口、社会压力 | 新周期第一天整行从 0 重记 `ES:1011-1025` |

## 5.3 创世（第 0 天）

- `firstDayRationMilli(population) = VOC.dailyRationMilli(population, 1)`：`SEED:1692-1702`；`dailyRationMilli` 是累计口粮的逐日差分（`VOC:160-165`）。
- 创世行载荷 `SEED.cohortRow`：
  - `money = 0`（`SEED:1576`）；`debts = []`（`SEED:1577`）；
  - `naturalNeeds = { grain: firstDayRationMilli(population) }`（`SEED:1578`）——**没有 `cloth`**；
  - `effectiveDemand = {}`（`SEED:1579`）；
  - `cycleNaturalNeedMilli` 不写 ⇒ 载荷解析缺省 0（`PAY:712`）。
- 家户商品库存与货币不在这条行里：走 `Seed.householdStocks` / `Seed.householdMoney`（`SEED:507-530`、`SEED:1510-1517`、`SEED:970-977`），落进 actor 侧 `GoodsAccount`。

## 5.4 日结算：写点、读点、次序

`EDS.step(day)`（`EDS:460-490`）转调 `ES.settleOneDay`（完整入口 `ES:624-1054`）。单日次序（`plantingDrawsFirst=true` 出厂默认 `ES:358`）：

1. 到货 `ES.deliverShipments`（`ES:719-720`）。
2. 现扣投入 `ES.drawCycleInputs` + 劳动再分配 `ES.reallocateLabor`（`ES:722-736`；取 false 时移到消费后 `ES:745-759`）。
3. 消费 `ES.consumeOwnStock`（`ES:743`）：
   - 第一件事就是 `withDailyNeed`：`ES:2431`；随后才读 `row.naturalNeeds` 的粮/布（`ES:2436/2443`）；
   - 吃库存、记 `consumed`、差额进 `unmetNeed` 与 `deficitToday`（`ES:2437-2460`）。
   - ⇒ **`naturalNeeds` 是“结算写、读口读”的同源值，且写成即读**；上一结算日之后到本结算日之前，它一直是旧值。
4. 进度 + 劳动 + 周期末收获（`ES:761-842`）；关账条件 `progressed >= cycleDays`（`ES:818-840`）。
5. 市场（`ES:844-881` → `MS.clearOncePerCycle`）。
6. 借粮（`ES:883-908`）、偿还（`ES:910-926`）、饿死（`ES:928-962`）、计息（`ES:964-970`）。
7. 流水组装与周期清零（`ES:972-1026`）。

`naturalNeeds` 的“算”有两处，但只有一处是写入点：
- 口径算法在 `VOC.dailyNeedsMilli(population, day)`（`VOC:202-224`）：`{grain: dailyRationMilli, cloth: dailyClothNeedMilli}`；
- 写点只有 `ES.withDailyNeed`（`ES:4306-4340`，注释自称“唯一写入点”）；
- 旧的“某一天人口 × 整周期配额”在 main 代码里不再使用；周期累计自然需要走 `cycleNaturalNeedMilli`（`CR:55-59`、`ES:4339`）。

## 5.5 周期边界（`progressDays` 翻篇）时两者怎么处理

- 周期清零的判据在“家户供给的任一产业 `progressDays == 0`”：
  - 新周期产业判定 `ES:768-773`；家户集合 `ES:783-797`；流水整行从 0 重记 `ES:1011-1025`（`acc == null` 分支）。
- `naturalNeeds` **不清零、不重置**：周期边界只做两件事——流水 `FlowRow` 从 0 重记、`cycleNaturalNeedMilli` 重置为“当天粮需求”（`ES:975-985`）；`withCycleNaturalNeed` 原样保留 `naturalNeeds`（`ES:4386`）。
- `effectiveDemand` **没有任何周期处理**（无写入点）。
- 另一个重置在收获那一支：`progressDays → 0`、`cycleLaborMilli → 0`、`cycleInputUsedMilli → 空表`（`ES:836-838`），与需求字段无关。

## 5.6 市场：`MS.ordersFor` 的 gap / 预算 / 数量取整

订单生成只在一个市场轮里发生（`MS:455-489`）。`ordersFor`（`MS:679-748`）逐步：

1. 参考价/买限价/卖底价：`MS:683-688`（`MKT.bidPriceOf` / `askPriceOf`：`MKT:87-111`；没定价 ⇒ 本商品不交易）。
2. 逐参与者（家户 + 本格经营者；`MS.participantsFor` `MS:1654-1680`）：
   - 必要投入 `necessary`：`plan.necessaryInputs`（家户的 `participant.industries` 在创世通常为空 —— weave 的 operator 是 `HOUSEHOLD: weave@hex`，与家户 actor 不同 ⇒ `necessary` 通常为 0；经营者 = `capacityScaleOf(industry) × inputPerUnit`，`MS.necessaryInputsOf:1699-1725`）；
   - 生活保留 `life`：家户 = `cumulativeRationMilli(pop, 35)` / `cumulativeClothMilli(pop, 35)`（`MS.householdLifeReserveOf:1727-1738`）；经营者 = `SubsistenceObligation.retentionOf`（`MS:1740-1782`）；
   - `stock`、`frozen`、`available = max(0, stock − frozen)`：`MS:699-701`；
   - **卖单**：`sellable = max(0, stock − frozen − necessary − life)`（`MS:702-714`），按 `bid` 挂 `SellOrder`；
   - **买单目标**：`target = 家户 ? life : necessary`（`MS:717`）；
   - 在途冲减：`incoming = confirmedIncoming(round, actor, commodity, deadline)`，`deadline = day + 35`（`MS:691/718/751-765`）；
   - **缺口**：`gap = max(0, target − available − incoming)`（`MS:719`）；`gap <= 0` ⇒ 不下单（`MS:720-722`）；
   - **预算**：`budget = spendableMoneyOf(...)`（`MS:723`；`MS:1889-1893` = actor 货币余额 − 冻结货币）；`budget <= 0` ⇒ 无有效需求（`MS:724-726` 注释：“没钱的缺口不是有效需求”）；
   - **买得起的量（向下取整）**：`affordable = budget × 1000 / reference`（`MS:730`，整数除法向下取整）；
   - **订单数量**：`quantity = min(gap, affordable)`（`MS:731`）；`quantity <= 0` ⇒ 不下单（`MS:732-734`）；
   - `BuyOrder`：`quantity`、`maxLandedPrice = ask`、`latestArrivalTick = day + 35`、`budget = new Budget(budget, silver)`（`MS:736-745`）。

成交（`MS:906-931`、`MS:1130-1182`、`MS:1186-1352`）：
- 区内：`price = 集散节点格市价`（`MS:865-901`）；`matched = min(demand, supply)`（`MS:924`）；买/卖两边各按权重走 `PSPLIT.byDenominator`（`MS:928-929`）；`pairUp` 逐笔按**当前剩余可付**再复核（`MS:1155-1163`）。
- 跨区：`MS.matchRoute`（`MS:1006-1120`），运力窗口 `MARKET_ROUTE_CAPACITY_MILLI_PER_WINDOW=1e8` × 参考距离 8 / 距离（`MS:139/142/1021-1026`），最多 4 轮；到货日 = `day + travelTicks + round`（`MS:1053`）。
- 付款：`payment = ceilDiv(quantity × unitPrice, 1000)`（`MS:1196`）；运费单独 `ceilDiv(...)`（`MS:1429-1433`）；钱/货各走 `ES.applyTransfer`（`MS:1205-1276`）。
- 订单对象与 `MarketReport` 都**不进持久状态**（`EDATA:110-120` 十组件里没有订单/报告；`MS:285-293` 注释“瞬时”）。

## 5.7 “自定义需求”在现有字段里的对应关系（逐栏）

| 需求范畴 | 现有字段 | 现状 |
|---|---|---|
| 生理需要 `naturalNeeds` | `ClassRow.naturalNeeds` | 存在；**创世可写**（`SEED:1578` / `PAY:718-721`，但真实创世只写粮第 1 天）；**每个结算日被 `withDailyNeed` 覆写**（`ES:2431/4321-4339`）。自定义值最多在两次结算之间被读口看到，无法活过一个结算日；且结算日的覆写发生在任何当日读取之前 |
| 消费偏好 | **代码里没有**：`ClassRow` 组件只有 population / laborMilli / participationPerMille / money / debts / naturalNeeds / effectiveDemand / cycleNaturalNeedMilli（`CR:61-70`）；`ClassSlot` 只有 id/name/laborParticipationPerMille；没有偏好权重、替代弹性、需求曲线字段 |
| 实际采购订单 | `BuyOrder`（`BUY:38-46`） | 存在但**瞬时**：只在 `MS.ordersFor` 里生成（`MS:679-748`）、只在市场轮内存在；每 5 天一轮（`day%5==0`）加条件性追加轮（`day%5==3` 且低粮）重算；没有持久订单表、没有订单 id 的落点（`OrderId` 类型存在但没有状态组件用） |
| 有支付能力的需求 | ① `ClassRow.effectiveDemand`（死字段，main 零写零行为读者）；② `ClassRow.money`（创世 0、main 零写、读口已删行侧货币栏 `AV:570-574`）；③ **真正的预算** = actor 侧货币会话副本（`MS.moneyOf:1848-1856`，由 `OwnershipBooks.loadHouseholdMoney/loadOperatorMoney` 从 `GoodsAccount` 载入） | `effectiveDemand` 与预算**没有算式连接**；市场里的“有支付力”是 `MS:723-731` 现算的 `budget → affordable → quantity`。因此改 `effectiveDemand` **不改变任何买量** |

两个“effective demand”名字不是同一件事：
- `ClassRow.effectiveDemand`：状态字段，当前真档恒空。
- `MR.CommodityReadout.effectiveDemandMilli`：读口字段，= 本轮 `planOrders` 生成的买订单数量之和（`MR:202-218/275`，注释 `MR:470`），是**读时派生量**，不落盘。
- 市场成交用的是同一份 `planOrders`（`MS.planOrders:394-422`；`clearOncePerCycle` 内 `MS:472`），所以读口的“有效需求”与真正下单一致，但它与 `ClassRow.effectiveDemand` 无关。

## 5.8 `naturalNeeds` 的行为读者清单（除读口外）

- 当日消费 `ES:2436/2443`（覆写后立即读）；
- 低粮追加轮的触发判据 `MS.lowGrainStock:358-387`（`stock − frozen < naturalNeeds[grain] × 10`，`MS:367/381`）；
- 社会侧当日满足率 `POPAPP.dailyProvisioning:319-337`（读 `naturalNeeds` 与 `flow.unmetNeed` 的当日增量，`POPAPP:329-334`）；
- 读口：`AV:530`（格级日耗合计）、`AV:1206`（行级）、`MR:227`（区级）。
这些读者看到的 `naturalNeeds` 都是“最近一次结算日写下的那份”；写口与读口同源（`ES:4309` 注释）。

---

# 第 6 题：单位、最大中间乘积、除法与舍入

## 6.1 主要量的单位

| 量 | 单位 | 位置 |
|---|---|---|
| `ClassRow.population` | 人 | `CR:46`；`ES:126` |
| `ClassRow.laborMilli` | 千分劳动/日（未按参与率折算） | `CR:47`；`ES:126-127` |
| `ClassRow.participationPerMille` | 千分数 ∈ [0,1000] | `CR:50/82-84` |
| `ClassRow.money` | 最小币值（毫） | `CR:51`；`FR:15` |
| `ClassRow.naturalNeeds` / `effectiveDemand` | 最小计量单位（毫商品） | `CR:34-35/53-54` |
| `ClassRow.cycleNaturalNeedMilli` | 毫粮 | `CR:55-59` |
| 1 商品单位 | 1000 最小计量单位（粮=毫粮、布=毫匹……） | `VOC:61-62`；别名 `VOC:64-70` |
| `FlowRow.income/consumed/unmetNeed` | 毫单位、逐商品 | `FR:15-18/47-56/64-66` |
| `Industry.cycleDays` / `progressDays` | 天 | `IND:77-79` |
| `Industry.capacityPerUnit` | 每 1 单位规模需要的生产资料：`LAND` 千分亩、其余件；逐值 > 0 | `IND:38/80-81`；`REC:44-45` |
| `Industry.capacity` | 本格该产业产能总量：`LAND` 千分亩、其余件；逐值 ≥ 0；键 ∈ `capacityPerUnit` 的键 | `IND:25-27/82-84` |
| `AssetKind` | 生产资料词表：LAND / CATTLE / TOOL / WORKSHOP / MACHINE / SHIP；土地千分亩、其余件 | `ASSET:5-15` |
| `Industry.inputPerUnit`（= `cycleInputPerUnit` 合计） | 最小计量单位 / 单位规模（粮 ⇒ 毫粮/亩） | `IND:38-45/91-94`；`REC:46-47` |
| `Industry.outputPerUnit` | **商品单位 / 单位规模**（粮 ⇒ 粮/亩）；结算时 ×1000 换毫 | `IND:41/52-53/89-90`；`ES:220-221/3159` |
| `Industry.laborPerUnit` | 千分劳动 / 单位规模 | `IND:88`；`REC:48` |
| `Industry.cycleLaborMilli` | 千分劳动·日（本周期累计实际投入） | `IND:97-98` |
| `Industry.cycleInputUsedMilli` | 毫单位（按商品的本周期实际扣料） | `IND:99-101` |
| `Industry.dailyInputPerUnit` / `dailyLaborPerUnit` | 最小单位/规模/日、千分劳动/规模/日；**本轮零读取点** | `IND:85-87`；`ES:1504-1505` |
| `Market.prices` | 毫计价货币 / 1 商品单位；逐值 > 0 | `MKT:19-28/41-64` |
| 货款公式 | `货款(毫钱) = 数量(毫商品) × 价格 ÷ 1000`（整数向下取整） | `MKT:22-25` |
| `Market.BID_PER_MILLE` / `ASK_PER_MILLE` | 990‰ / 1010‰（只决定挂牌限价，不决定成交价） | `MKT:80-125` |
| `BuyOrder.quantity` | 毫商品，必须 > 0 | `BUY:32/58-60` |
| `BuyOrder.maxLandedPrice` | 毫计价货币 / 商品单位，必须 > 0 | `BUY:33/61-64` |
| `BuyOrder.latestArrivalTick` | 世界日 | `BUY:34` |
| `Budget.amountMilli` | 最小币值 | `BUDGET:7/21` |
| 市场生活保留 | 35 天 = 撮合间隔 5 + 安全库存 30 | `MS:92-106` |
| 市场买订单到货时限 | 35 天 | `MS:108-112` |
| 市场运力 | 1e8 毫商品/窗口（出厂值）；参考距离 8 hex | `MS:134-142` |
| 运费率 / 在途损耗率 | 10‰ 货款价值/hex；5‰/程 | `MS:144-151` |
| 借贷利率 / 信用倍数 / 偿还比例 / 放贷自留 | 20‰/周期；1000‰；200‰；1000‰ | `ES:262-322` |
| 致死率 | 0‰（默认） | `ES:324-345` |
| 饲料 / 折旧 | 0‰ / 30‰ | `ES:243-260` |
| 口粮 / 衣着口径 | 每 120 天 10,000 毫粮/人；每 365 天 1,000 毫布/人 | `VOC:72-90` |
| 劳动土地口径 | 1 标准劳动支持 7 亩 ⇒ 每亩 ⌈1000/7⌉ = 143 千分劳动 | `ES:223-241`；`SEED:163-172` |

## 6.2 除法 / 取整点全表（经济结算管线 + 创世器 + 读口）

> 扫描范围：上表简称的 main 文件（`SEED/ES/MS/PS/EDS/MR/MT/CR/FR/IND/MKT/REC/VOC/PSPLIT/SO/BUY/BUDGET/PAY/AV`），逐个 `/,%` 算术点；
> 不含注释/字符串、不含 actor/goods 账户、codec、social 模块内部（`POPDYN` 的月度除法另见第 8 题）。方向均按 Java 整数运算。

| 位置 | 算式 | 方向 | 备注 |
|---|---|---|---|
| `MS.ceilDiv:1895-1897` | `(numerator + denominator − 1) / denominator` | **向上取整** | 用于：买单纯货款 `MS:822-823`、成交货款 `MS:1196`、读数单位运费 `MS:1334`、跨区运费 `MS:1431-1432`。`numerator + denominator − 1` 本身可溢出（实数范围内不触发） |
| `MS.ceilDivPositive:1538-1542` | `q = n/d; n%d==0 ? q : q+1` | **向上取整** | 避免 `+d−1` 溢出；`totalCostAtMost` 用它 `MS:1481/1495` |
| `MS.exactAffordableUpTo:1515-1517` | `mid = low + (high − low + 1) / 2` | 向下取整 | 二分中点（不是数量舍入口） |
| `MS.affordableQuantity:1443-1455` | `money −= 2; safeMulDiv(money, 1000, unitCost)` | **向下取整** | `money <= 2` ⇒ 0；`safeMulDiv:1526-1536` 用 `multiplyExact`，溢出回退 `Long.MAX_VALUE`（保守） |
| `MS.ordersFor:730` | `budget × 1000 / reference` | **向下取整** | 买单“买得起多少”；再 `min(gap, affordable)` `MS:731` |
| `PSPLIT.byDenominator:53-59` | `product = total × weights[i]`；`parts[i] = product/denominator`；`residues[i] = product%denominator` | **向下取整 + 最大余数残差** | 残差分派 `PSPLIT:69-98`（先人人摊 `remainder/n`，再按余数降序同余下标升序给前 `remainder%n` 名 +1）；**乘积无溢出保护**。main 调用处：`SEED:1778`、`MS:808/928/929/1102/1107`、`ES:1778/2086`、`ES:3939`（经 `ES:1184-1185` 用于出生/死亡按行人口分派） |
| `SO.perLaborDue:128-136` | `laborMilli / 1000 × perLaborMilli` | 先**向下取整**再乘 | `PS.perLabor:638-643` 调的就是这个函数（唯一拼写点） |
| `VOC.cumulativeRationMilli:138-146` | `population × 10000 × days / 120` | **向下取整** | 左结合：先 `population×10000`，再 `×days` |
| `VOC.dailyRationMilli:160-165` | `cumulative(day) − cumulative(day−1)` | 精确差分 | 残差逐日补足；正人口正天数下不为 0 |
| `VOC.cumulativeClothMilli:177-185` | `population × 1000 × days / 365` | **向下取整** | 同上 |
| `VOC.dailyClothNeedMilli:195-200` | `cumulative(day) − cumulative(day−1)` | 精确差分 | 同上 |
| `CR.participationAdjustedLaborMilli:170-179` | `laborMilli × participationPerMille / 1000` | **向下取整** | 三处读者（配额总量/关账劳动/读口），本方法自述唯一拼写点 `CR:138-155` |
| `ES.capacityScaleOf:2208-2216` | `capacity[k] / capacityPerUnit[k]` 逐路取 `min` | **向下取整** | 缺键按 0 读；`capacityPerUnit` 保证非空且 >0 |
| `ES.scaleOf:3753-3772` | 产能路 `capacity[k]/capacityPerUnit[k]`、劳动路 `avgLabor/laborPerUnit`、投入路 `drawn/inputPerUnit[j]`，逐路 floor 后取 `min` | **向下取整** | 收获日规模，`ES:3150` 调用 |
| `ES.usableScaleOf:1793-1802` | 产能路 + 投入可供量路 `available/perUnit`，逐路 floor 后取 `min` | **向下取整** | 现扣投入步的“这次要开多大” |
| `ES.laborNeedOf:2958-2978` | `scale × laborPerUnit`；`scale` 由产能 `/`、投入 `drawn/` 折出 | floor + 乘法 | 劳动那一路刻意不含；`need < 0` 时回退 `Long.MAX_VALUE`（只挡负绕回，`ES:2978`） |
| `ES.harvest:3159` | `scale × outputPerUnit × 1000` | 精确乘 | 毛产；整数无除法 |
| `ES.harvest:3165` | `gross × (0 + 30) / 1000` | **向下取整** | 生产损耗 |
| `ES.harvest:3148` | `cycledLabor / cycleDays` | **向下取整** | 平均每日实际劳动（收获日规模用） |
| `MS.triggerFor:339/345` | `day % MARKET_RESTOCK_INTERVAL_DAYS` | 取模（日程判据） | 只看绝对日相位，不做数量舍入 |
| `ES.deliverShipments:1089` | `quantity × lossPerMille / 1000` | **向下取整** | 到货损耗；与 `MS:1330/1351` 同式 |
| `ES.applyPopulationChange:1194` | `row.laborMilli × remaining / population` | **向下取整** | `population==0` 已在 `ES:1190-1191` 跳过 |
| `ES.scaleLaborOfIndustry:1334`、`scaleLaborOfGroup:1369`、`scaleSupply:1398` | `laborMilli × after / before`（三个函数同式） | **先乘后向下取整** | 人口死亡后按存活比例缩 |
| `ES.lendableOf:2658-2661` | `cumulativeRation(pop, cycleDays) × 1000 / 1000` | **向下取整** | 放贷保留额 |
| `ES.creditLinesOf:2696` | `expected × 1000 / 1000` | **向下取整** | 信用线 |
| `ES.repayDebts:2771` | `earned × 200 / 1000` | **向下取整** | 还款预算 |
| `ES.applyFamine:3822` | `min(1000, cycleUnmet × 1000 / cycleNeed)` | **向下取整** | `cycleNeed==0 ⇒ 0` |
| `ES.applyFamine:3823` | `population × faminePerMille / 1000 × mortality / 1000` | **两次向下取整** | 死亡数 |
| `ES.applyFamine:3829` | `laborMilli × nextPopulation / population` | **向下取整** | 不死除零 |
| `ES.chargeInterest:3875` | `startPrincipal × ratePerMillePerCycle / 1000` | **向下取整** | 并入本金 |
| `ES:241` | `(1000 + 7 − 1) / 7` | **向上取整** | `LABOR_MILLI_PER_MU=143` 的推导 |
| `MS:1024-1026` | `1e8 × 8 / max(1, distance)` | **向下取整** | 每路线每窗口运力 |
| `MS.freightPerUnitOf:1425-1427` | `unitPrice × travelTicks × 10 / 1000` | **向下取整** | 单位运费（读数） |
| `MKT.bidPriceOf:87-90` | `max(1, price × 990 / 1000)` | **向下取整后下限 1** | 卖方底价 |
| `MKT.askPriceOf:104-111` | `(price × 1010 + 999) / 1000`，再 `max(bid+1, ask)` | **向上取整后下限 bid+1** | 买方限价 |
| `MR:254` | `spendable × 1000 / ask` | **向下取整** | “买不起”读口的判定 |
| `MR.matchReadout:389-392` | `BigInteger(Σ qty×(单价+运费)) / BigInteger(Σ qty)` | **向下取整**（大整数） | 读口到货均价；无成交 ⇒ empty |
| `POPAPP.satisfactionPerMille:352-358` | `got × 1000 / need`，封顶 1000 | **向下取整** | 社会侧日满足率（在 app） |
| `AV.grainDiagnosis:699-700` | `grainStock / dailyNeed` | **向下取整** | 读口覆盖天数；`dailyNeed<=0 ⇒ null`（无定义，不是 0） |
| `AV.grainDiagnosis:709-711` | `money × 1000 / price`（参考价与 ask 两栏） | **向下取整** | 读口“账本级购买力上限” |
| `AV.grainDiagnosis:727-732` | `(cycleNeed−unmet) × 1000 / cycleNeed`；`unmet / (10000/120)` | **向下取整**（内层 10000/120=83 也 floor） | 读口满足率 / 未满足人日 |
| `AV.perCapitaLaborMilli:1101-1108` | `laborMilli / population` | **向下取整** | 读口人均劳动（千分劳动/人）；人口 0 ⇒ 0 |
| `SEED.classWeightedParticipationPerMille:337-344` | `Σ(份额 × 参与率) / 1000` | **向下取整** | 阶层加权参与率 |
| `SEED.operatorWageReserveMilli:660-670` | `perCycle × 1200 / 1000` | **向下取整** | 经营者工资周转金 |
| `SEED.plan:793-794` | `人口 / 20`、`人口 / 50` | **向下取整** | 织机数 / 作坊数 |
| `SEED.plan:837` | `ruralDaily × 100 / 1000` | **向下取整** | 家庭纺织配额 |
| `SEED.genesisMoneyMilliPerCapita:953-958` | `10000 × 1 × 1200 / 1000 / 1000` | **两次向下取整** | 每人 12 毫银 |
| `SEED.appendAllocation:1139` | `available × sexWeight / 1000` | **向下取整** | 配额权重 |
| `SEED.landMilliMuOf:1224-1227` | `3100 × 1000 × arablePerMille / 1000` | **向下取整** | 本格千分亩 |
| `SEED.arablePerMilleOf:1602-1604` | `food × 1000 / 3` | **向下取整** | 可耕地系数 |
| `SEED.fiberStockMilli:1302-1307` | `landMilliMu / 1000 × 6 × 1000` | **先向下取整**（亩）再乘 | 农业一周期纤维副产库存 |
| `SEED.rowLaborMilli:1671-1676` | `population × poolLaborMilli / poolCount` | **向下取整** | 每行有效劳动 |
| `SEED.laborMilli(pop):1684-1690` | `Σ ageShare × coef / 1000` 再 `population × perCapita` | **向下取整** | 人均 580‰ |
| `SEED.splitByShares:1734-1743` / `splitProportional:1755-1757` / `split:1764-1780` | 转 `PSPLIT.byDenominator` | 同 `PSPLIT` | `splitByShares` 分母恒 1000，`splitProportional` 分母 Σ权重 |

## 6.3 最大中间乘积：逐个乘法点估上界

`long` 上限 = `9.223372036854775807e18 ≈ 9.22e18`。

真档量级（派单给的口径 + 代码注释里的量级）：
- 人口：`~1.15e7`（`SEED:122` 每格 14,806 人 × 799 格 ≈ 1.18e7；`SEED:157` 记 799 格）。
- 库存：派单给 `1e11 ~ 1e12` 毫；按创世口径实算：每格粮库存 ≈ 14,806 人 × 人均 65 天 × 83.3 毫/天 ≈ 8.0e7 毫（阶层天数 30/60/120/250，`SEED:310-314`），799 格 ≈ 6.4e10 毫；派单上界作“极端/累计”场景用。
- 价格：1 ~ 20 毫/商品单位（`SEED:414-450`：粮 1、布 5、纤维 1、铁 10、工具 20）。
- 劳动：`~1e10` 千分劳动（= 1.15e7 人 × 1000‰；人均默认 580‰ 时约 6.7e9）。
- 每格亩数：3,100 亩（`SEED:127`）；容量规模：farm 3,100；weave `农村人口/20`；craft `城镇人口/50`（`SEED:790-794`）。

| 乘法点（位置） | 算式 | 真档上界估算 | 与 9.22e18 比 | 判定 |
|---|---|---|---|---|
| `PSPLIT:54` `total × weights[i]` | 两因子都可能是“同轮撮合量 / 单主体订单量 / 池总量 / 单家需求” | 若 `total=1e10`、`weight=1e10` ⇒ **1e20**；若各 1e12 ⇒ 1e24 | **超** | **理论上有溢出风险**（详见下行） |
| `MS.matchGroup:928-929` | `matched × weights[i]`（区内） | `matched` 无窗口上限；库存 1e11~1e12 时可达 1e10~1e12；权重同量级 | 可能超 | **有风险**；溢出条件 `matched × weight > 9.22e18`，即两因子都 ≥ ~3.04e9 时即触发 |
| `MS.matchRoute:1102/1107` | `matched × weights[i]`（跨区） | `matched ≤ capacityPerWindow = 8e8`（距离 1）；`weights` = 卖方/买方剩余，可达 1e11~1e12 | `8e8 × 1e12 = 8e20` | **有风险**；单主体单商品剩余 > `9.22e18 / 8e8 ≈ 1.15e10` 毫（≈1.15e7 商品单位）时溢出 |
| `ES.rationContestedInputs:1776-1778` | `PSPLIT.byDenominator(poolStock, needs, totalNeed)`：`poolStock × needs[i]` | 池 = 本格争用商品库存；`needs[i] ≤ usableScale × inputPerUnit`：farm 3,100×8,000=2.48e7；weave 740×30,000=2.22e7；craft 35×60,000=2.1e6 | 池 1e12 × 2.48e7 = 2.48e19 | **极端库存下有风险**（池 > ~3.7e11 毫粮 / ~4.15e11 毫纤维才触发）；真档单格池 ~2e7 ⇒ 4.5e14 安全 |
| `MS.commitFreezes:803-811` | `total × requestedMoney`（冻结预算分配） | `total ≤ 该主体可花货币`；创世货币 12 毫/人 ⇒ 世界 ~1.4e8 毫（`SEED:935-958`） | 1.4e8×1.4e8≈2e16 | 安全 |
| `SEED.splitByShares/splitProportional:1149/1457/1475/1505` | `total × 权重` | 池级：人口 ≤ 1.18e7×450≈5.3e9；纤维 1.86e7×450≈8.4e9；劳动 total ≤ ~8e6、权重 ≤ ~1e7 | ≤~8e13 | 安全 |
| `PS.shareWithTotal:672-674` | `share × own / total` | `share ≤ 本格该商品毛产`：farm 2.077e8（3,100×67×1000）；`own ≤ 本格一周期劳动`：真档每格 ~1.03e9（14,806×580‰×120） | 2.077e8×1.03e9≈2.14e17 | 真档安全（余量 ~43×）；**理论临界**：一格里劳动 > `9.22e18/2.077e8 ≈ 4.44e10` 千分劳动·日 ⇒ 约 37 万人/格（1000‰）或 64 万人/格（580‰） |
| `PS.perMille:662-664` | `amount × rate` | amount ≤ 单产业单商品毛产 2.077e8；rate ≤ 1000 | 2.08e11 | 安全 |
| `ES.harvest:3159` | `scale × output × 1000` | farm 3,100×67×1000=2.077e8；weave ≤740×30×1000=2.22e7；craft ≤35×60×1000=2.1e6 | ≤2.08e8 | 安全；farm 的 `capacity[LAND]` 要到 >~1.38e14 千分亩（scale>1.38e11）才溢出；1e12 级容量（scale 1e9）只到 6.7e13 |
| `ES.laborNeedOf:2977` | `scale × laborPerUnit` | farm 3,100×143=4.43e5；weave/craft 同量级 | ≤4.5e5 | 安全 |
| `ES:1615/1770`、`MS:1721` | `usableScale × inputPerUnit` / `inputPerUnit × scale` | farm 3,100×8,000=2.48e7；weave 740×30,000=2.22e7；craft 35×60,000=2.1e6 | ≤2.5e7 | 安全 |
| `SO.perLaborDue:135` | `labor/1000 × perLaborMilli` | 本格一周期劳动 ≤1.03e9 /1000≈1.03e6；`perLaborMilli` 为制度固定额（量级 ≤1e4） | ≤1.03e10 | 安全（即便世界一格的劳动 1.15e10 ⇒ 1.15e11） |
| `CR:178` | `laborMilli × participationPerMille` | 世界劳动 1.15e10 × 1000 | 1.15e13 | 安全 |
| `VOC:145/184` | `population × 10,000 × days` / `population × 1,000 × days` | 1.15e7×1e4×360=4.14e13；1.15e7×1e3×365=4.2e12 | ≤4.2e13 | 安全（days>~8e4 才溢出） |
| `MS:1196`、`MS:822-823` | `quantity × unitPrice` | 1e12 × 20 = 2e13 | ≤2e13 | 安全 |
| `MS.freightOf:1431-1432` | `quantity × unitPrice × travelTicks × 10` | 1e12×20=2e13；×8=1.6e14；×10=1.6e15 | ≤1.6e15 | 安全；跨区 travelTicks 真档很小；`travelTicks > ~4.6e3` 才溢出 |
| `MS:1334` | `freight × 1000` | freight ≤（2e13×8×10）/1e6≈1.6e9 ⇒ 1.6e12 | ≤1.6e12 | 安全 |
| `MS:381` | `naturalNeeds[grain] × 10` | 单行 1.15e7×83=9.6e8 ⇒ 9.6e9 | ≤9.6e9 | 安全 |
| `ES:3165` | `gross × (0+30)` | 2.077e8×30=6.23e9 | ≤6.2e9 | 安全 |
| `ES:3822` | `cycleUnmet × 1000` | 世界周期需求 ~1.15e11×1000=1.15e14 | ≤1.15e14 | 安全 |
| `ES:3823` | `population × faminePerMille` | 1.15e7×1000=1.15e10 | ≤1.15e10 | 安全 |
| `ES:2696` | `expected × 1000` | expected ≤1.15e11×1000=1.15e14 | ≤1.15e14 | 安全 |
| `ES:2771` | `earned × 200` | earned ≤1.15e11×200=2.3e13 | ≤2.3e13 | 安全 |
| `ES:3875` | `startPrincipal × rate` | 本金若按派单库存上界 1e12×20=2e13 | ≤2e13 | 安全 |
| `ES:1194/3829` | `labor × remaining(population)` | 1e10×1.15e7=1.15e17 | ≤1.15e17 | 安全 |
| `ES:1334/1369/1398` | `labor × after(before)` | 世界配额 1e10×1.15e7=1.15e17 | ≤1.15e17 | 安全 |
| `MS:1330/1351`、`ES:1089` | `quantity × lossPerMille` | 1e12×5=5e12 | ≤5e12 | 安全 |
| `MR:254` | `spendable × 1000` | 货币世界 ~1.4e8×1000=1.4e11 | ≤1.4e11 | 安全 |
| `AV:709-711/727-729` | `money × 1000` / `(cycleNeed−unmet) × 1000` | 货币 1.4e8×1000=1.4e11；周期需求 1.15e11×1000=1.15e14 | ≤1.15e14 | 安全（读口） |
| `SEED:972` | `population × 12 毫` | 1.15e7×12=1.38e8 | ≤1.4e8 | 安全 |
| `SEED:1036/1660` | `count × perCapitaLabor` | 单批次即使 1.15e7×1000=1.15e10 | ≤1.15e10 | 安全 |
| `SEED:1675` | `population × poolLaborMilli` | 世界一池极端：1.15e7×1e10=1.15e17 | ≤1.15e17 | 安全 |
| `SEED:1226` | `3100 × 1000 × 1000` | 3.1e9 | ≤3.1e9 | 安全 |
| `MKT:109` | `price × 1010` | 20×1010=2.02e4 | 安全 | 安全 |
| `MS:1024-1025` | `1e8 × 8` | 8e8 | 安全 | 安全 |
| `MR:385` | `capacityPerWindow × 4` | 8e8×4=3.2e9 | 安全 | 安全 |

**风险点汇总（按严重度）：**

1. `PSPLIT.byDenominator:54` 的 `total × weights[i]`：**唯一同时无溢出保护、又直接吃“库存级”量的点**。main 调用处共 9 个：`SEED:1778`（池级小量）、`MS:808`（货币量级）、`MS:928/929/1102/1107`（库存/订单量级）、`ES:1778`（池级库存）、`ES:2086`（投入需求 × 人口，量级 ≤~3.7e11）、`ES:3939`（经 `ES:1184-1185` 分派出生/死亡人数，量级小）。风险集中在 `MS:928/929`（区内）与 `MS:1102/1107`（跨区）。溢出条件：`total × weights[i] > 9.22e18`。
   - 区内：两因子都 ≥ ~3.04e9（毫商品）即溢出；按派单库存 1e11~1e12，只要单区同轮撮合量与单个订单权重同量级，就到了。
   - 跨区：`matched` 被每窗 8e8 封顶，但另一因子是订单剩余，`> ~1.15e10` 即溢出；派单库存 1e11~1e12 时单主体单商品剩余若集中在一条订单上就会触发。
   - 真档“每格库存 ~8e7、每区 ~1e8~1e9”的量级下，乘积 ~1e16~1e18，**尚未溢出**；所以这是“理论上可达、真档当前多数情形安全”的风险，不是“已在真档发生”。
2. `PS.shareWithTotal:673` 的 `share × own`：真档安全（~2e17，余量 43×）；只有当单格劳动 > ~4.44e10 千分劳动·日（约 37 万~64 万人口挤在一格）时溢出。真档每格 14,806 人。
3. 其余乘法点在派单给的量级下均安全。若把“库存 1e11~1e12”理解为**单主体单商品余额**，则 `MS.matchRoute` 与 `MS.matchGroup` 的 `PSPLIT` 调用是最先溢出的两处；若理解为世界总量（市场按区/按格分片），则当前真档量级下安全。

另外两处“和溢出相邻”的实现：
- `MS.ceilDiv:1895-1896` 的 `numerator + denominator − 1`：当 `numerator` 接近 `Long.MAX_VALUE` 时会先溢出；相邻的 `ceilDivPositive:1539-1542` 刻意避开了这个写法。
- `MS.safeMulDiv:1526-1536` 与 `MS.totalCostAtMost:1473-1497` 用 `multiplyExact` 捕获溢出（前者回退 `Long.MAX_VALUE`，后者返回“付不起”）；`PS.shareWithTotal`、`PS.perMille`、`PSPLIT.byDenominator`、`ES:3159/3165/2696/2771/3875` 等都没有乘法溢出保护。

## 6.4 “先整除再乘”与“小于一个计量单位就归零”的案例

| 位置 | 算式 | 归零条件 | 具体数值例子 |
|---|---|---|---|
| `SO.perLaborDue:135` | `laborMilli/1000 × perLaborMilli` | `laborMilli < 1000` | labor=999、perLabor=10,000 ⇒ **0**（真实应付 9,990 毫）；labor=1,500、perLabor=10,000 ⇒ 10,000（真实 15,000，丢掉 500 千分劳动对应的 5,000 毫） |
| `PS.perMille:662-664` | `amount × rate / 1000` | `amount × rate < 1000` | amount=33、rate=30 ⇒ **0**（真实 0.99）；amount=999、rate=1 ⇒ 0（真实 0.999） |
| `PS.shareWithTotal:672-674` | `share × own / total`（`share` 常常已经是 `perMille` 的结果） | `share × own < total` | share=100、own=1、total=1,000 ⇒ **0**（真实 0.1）；若 `perMille` 已归零，`share=0` ⇒ 无论如何都是 0 |
| `ES.scaleOf:3765-3771`（投入路） | `drawn / inputPerUnit`，再对全部路取 `min` | `drawn < inputPerUnit` | 有 7,999 毫粮种子、每亩需 8,000 毫 ⇒ 投入路 = 0 ⇒ `scale = min(产能 3,100, 劳动路, 0) = 0` ⇒ **整格本周期颗粒无收**，不是“0.999 亩的收成”。`ES.usableScaleOf:1793-1800` 与 `ES.laborNeedOf:2967-2975` 同一口径 |
| `ES.capacityScaleOf:2213` / `ES.scaleOf:3758-3761` | `capacity / capacityPerUnit` | `capacity < capacityPerUnit` | 999 千分亩 ÷ 1,000 千分亩/亩 ⇒ 0 ⇒ 不生产（真档按整亩算，3,100,000/1,000 不触发） |
| `PSPLIT:53-59/69-98` | `total×weight/denominator` + 最大余数残差 | 单项 floor 为 0 且残差没排到它 | total=10、weights=[1,999]、denominator=1,000 ⇒ 商 [0,9]、残差 1，余数 10 vs 990 ⇒ 残差给第二项 ⇒ **[0,10]**；第一项真实份额 0.01 归零。最大余数法只保证 **Σ == total**，不保证每个非零权重都至少 1 |
| `MS.affordableQuantity:1443-1455` | `money − 2` 后 `money×1000/unitCost` | `money ≤ 2`，或 `money×1000 < unitCost` | money=3、unitCost=2,000 ⇒ 1 毫商品；money=1、unitCost=2,000 ⇒ **0** |
| `MKT.bidPriceOf:87-90` | `max(1, price×990/1000)` | 无归零（下限 1） | price=1 ⇒ 990/1000=0 ⇒ bid=**1** |
| `ES.deliverShipments:1089` | `quantity×lossPerMille/1000` | `quantity×5 < 1000` | quantity=199 毫、5‰ ⇒ **0 损耗**（真实 0.995 毫）；quantity=200 ⇒ 1 |
| `ES.harvest:3165` | `gross×30/1000` | `gross < 34` | gross=33 毫 ⇒ 0 损耗（真实 0.99）；gross=34 ⇒ 1 |
| `ES.applyFamine:3823` | `population×faminePerMille/1000 × mortality/1000` | 第二次 floor 归零 | population=10、faminePerMille=1000、mortality=50 ⇒ `10 → 10×50/1000=0` 死亡（真实 0.5 人） |
| `ES.chargeInterest:3875` | `principal×rate/1000` | `principal×20 < 1000` | principal=49、rate=20 ⇒ **0 利息**（真实 0.98）；principal=50 ⇒ 1 |
| `SEED.plan:837` | `ruralDaily×100/1000` | `ruralDaily < 10` | 池日劳动=9 ⇒ **0 纺织配额**（真实 0.9）；=10 ⇒ 1 |
| `SEED.fiberStockMilli:1302-1307` | `landMilliMu/1000 × 6 × 1000` | `landMilliMu < 1000`（不足 1 亩） | 999 千分亩 ⇒ 0 纤维（真实 0.999 亩×6×1000=5,994 毫）；真档可耕地是 1e6 量级，不触发 |
| `CR:178` | `laborMilli×participation/1000` | `laborMilli×participation < 1000` | labor=1、participation=999 ⇒ 0；labor=999、participation=1 ⇒ 0。真档每行人口 ≥1、壮劳力系数 1,000、参与率 ≥100 ⇒ 不触发 |
| `SO.retentionOf:268-273` | `min(requested, promised)` | promised=0（上游 `dueAmount=0`）⇒ retained=0 | 即使 requested=10,000，只要 `perLaborDue` 因 labor<1000 给了 0，可保留额也是 0 |
| `ES.reallocateLabor:2879-2887` | `room = max(0, need−kept)`；`keep=min(allocation, room)` | `need=0`（`laborNeedOf` 因 scale floor 给 0）⇒ 整条配额回池 | 容量 0 或投入路 floor 到 0 时，该产业的劳动配额被全部释放；`left>0` 且无农业 ⇒ 失业、配额消失（`ES:2925-2930`） |
| `VOC:138-200` | 累计口粮/布 floor | `population=0` 或 `days=0` ⇒ 0 | 正人口正天数下，1 人 1 天=83 毫粮 / 2 毫布，不为 0；不存在“每天不足 1 毫”的问题 |

## 6.5 反向案例：ceil 保证“至少 1 毫”

- 成交货款 `MS:1196`：`payment = ceilDiv(quantity × unitPrice, 1000)`。只要 `quantity×unitPrice > 0`，`payment ≥ 1 毫`。
  - `quantity=1 毫商品`、`price=1` ⇒ payment = ⌈0.001⌉ = **1 毫**（真实 0.001 毫）。
  - `quantity=999 毫商品`、`price=1` ⇒ ⌈0.999⌉ = **1 毫**（真实 0.999）。
  - 即：整数网格下“不足 1 毫的货”仍按 1 毫成交；与 `perLaborDue` 等“不足 1 单位就付 0”的方向相反。
- 运费 `MS.freightOf:1429-1432`：`quantity=100`、`unitPrice=1`、`travelTicks=1` ⇒ raw=1,000/1e6=0.001 ⇒ **1 毫**。
- 卖方底价 `MKT:87-90`：price=1 ⇒ `1×990/1000=0`，`max(1,0)=1`；买方限价 `MKT:104-111`：`(1×1010+999)/1000=2`，再 `max(bid+1=2, 2)=2`。`MKT:98-99` 明写这是整数网格的必然（p=1 时价差相对幅度 >1%），成交仍按参考价。
- `MS.affordableQuantity:1443-1449` 的“先减 2 毫边距”也会让小钱包归零（money≤2），与 ceil 的另一侧同属整数网格边界。

---

# 第 7 题：capacity 语义与入口

## 7.1 `Industry.capacity` / `capacityPerUnit` 的键与语义

- 键 = `AssetKind`（`ASSET:5-15`：LAND / CATTLE / TOOL / WORKSHOP / MACHINE / SHIP）；真档创世只用了 **LAND / TOOL / WORKSHOP** 三档（见 7.2）。
- 单位：`LAND` **千分亩**，其余**件**（`IND:25-27`、`IND:38`、`REC:44-45`；`ASSET:5-6`）。
- `capacityPerUnit` 的值 = “每 1 单位规模需要多少该生产资料”，**必须 > 0**，且 `capacityPerUnit` 不得为空（它是“单位规模”的锚）：`IND:197-201/270-273`、`REC:70-76`。
- `capacity` 的值 = 本格该产业的**产能总量**（K3）；**逐值 ≥ 0**（0 是合法产能：沙漠 LAND=0、人口不足一厂的 TOOL=0），键必须是 `capacityPerUnit` 的键的**子集**，缺键按 0 读：`IND:82-84/205-225`；`ES.capacityScaleOf:2208-2216`。
- 语义结论（按代码注释，不做引申）：
  - 它**不是**脱离生产资料的抽象产能：键说清楚“规模以什么计”（农业=亩、织机/作坊=台/座），产能那一路 = `min_k ⌊capacity[k] ÷ capacityPerUnit[k]⌋`（`IND:25-28`、`REC:34-35`、`ES:2208-2216/3758-3761`）。
  - 它也**不是**“各主体持有的产权”——K3 自述把原先散在 `ClassRow.meansOfProduction` 的产能搬到产业上，成为“该格该产业的技术属性”，只有一份总量（`IND:25-28`；`ES:3741-3744`；`ClassRow` 已无 `meansOfProduction`，`PAY:700-704` 见到该键即抛）。类注自述“读它的唯一地方是 `scaleOf`”（`IND:27-28`），实际运行期 `capacityScaleOf` 也读同一字段（`ES:2213`）。
  - `CATTLE / MACHINE / SHIP` 只是词表声明；`SEED` 创世和 `ES.scaleOf` 的运行路径都没有用到它们。

## 7.2 创世给 farm / weave / craft 各写了什么

`SEED.plan` 里每格先算三样（`SEED:790-794`）：

```
landMilliMu  = landMilliMuOf(terrain)                       // 3,100 亩 × 1,000 千分亩/亩 × 地形系数
looms        = populationOf(ruralPool) / 20                 // 整数除法
workshops    = populationOf(urbanPool) / 50                 // 整数除法
```

### farm（农业，恒有；`SEED:1239-1257`）

| 字段 | 值 | 行 |
|---|---|---|
| `capacity` | `{"LAND": landMilliMu}` | `SEED:1248` |
| `capacityPerUnit` | `{"LAND": 1000}` ⇒ 1 单位规模 = 1 亩 | `SEED:1253` |
| `laborPerUnit` | `143`（千分劳动/亩；`ES.LABOR_MILLI_PER_MU`） | `SEED:1254`；`SEED:163-172`；`ES:240-241` |
| `outputPerUnit` | `{"grain": 67, "fiber": 6}`（商品单位/亩；每亩同时出粮与纤维） | `SEED:1255`；`SEED:144/182` |
| `cycleInputPerUnit` | `{"LAND": {"grain": 8000}}`（毫粮/亩，周期第一天现扣） | `SEED:1256`；`SEED:159` |
| `cycleDays` / `progressDays` | 120 / 0 | `SEED:1415-1416`；`SEED:108` |
| 最大规模示例（平原 3,100 亩） | `landMilliMu = 3,100 × 1000 = 3,100,000` ⇒ `capacityScale = 3,100,000/1000 = 3,100` | `SEED:1225-1226`；`ES:2213` |

### weave（农村家庭纺织，仅当该格农村人口 > 0；`SEED:793/797-803/1280-1293`）

| 字段 | 值 | 行 |
|---|---|---|
| `capacity` | `{"TOOL": looms}`，`looms = 农村人口 / 20` ⇒ 20 人一台织机 | `SEED:793`、`SEED:1286` |
| `capacityPerUnit` | `{"TOOL": 1}` ⇒ 1 单位规模 = 1 台织机 | `SEED:1289` |
| `laborPerUnit` | `1000`（千分劳动/台·周期） | `SEED:1290`；`SEED:193` |
| `outputPerUnit` | `{"cloth": 30}`（匹/台·周期，商品单位） | `SEED:1291`；`SEED:196` |
| `cycleInputPerUnit` | `{"TOOL": {"fiber": 30000}}`（毫纤维/台·周期 = 30×1000） | `SEED:1292`；`SEED:215` |
| 最大规模示例 | 14,806 人 ⇒ 740 台 ⇒ `capacityScale=740` | 计算式 |

### craft（城市作坊，仅当该格城镇人口 > 0；`SEED:794/804-807/1351-1374`）

| 字段 | 值 | 行 |
|---|---|---|
| `capacity` | `{"WORKSHOP": workshops}`，`workshops = 城镇人口 / 50` | `SEED:794`、`SEED:1357` |
| `capacityPerUnit` | `{"WORKSHOP": 1}` ⇒ 1 单位规模 = 1 座作坊 | `SEED:1359` |
| `laborPerUnit` | `1000`（千分劳动/座·周期） | `SEED:1360`；`SEED:206` |
| `outputPerUnit` | `{"cloth": 60, "tool": 5}`（商品单位/座·周期） | `SEED:1361-1365`；`SEED:209/212` |
| `cycleInputPerUnit` | `{"WORKSHOP": {"fiber": 60000, "tool": 2000}}`（毫/座·周期；工具自产自用） | `SEED:1369-1373`；`SEED:214-241` |
| 最大规模示例 | 城镇 1,777 人 ⇒ 35 座 ⇒ `capacityScale=35` | 计算式 |

以上三张表都写进同一个 `industry(...)` 构造器（`SEED:1391-1438`）：
`cycleDays/progressDays`（`SEED:1415-1416`）、`capacity`（`SEED:1419`）、`capacityPerUnit`（`SEED:1422`）、
`laborPerUnit`（`SEED:1423`）、`dailyInputPerUnit={}` / `dailyLaborPerUnit=0`（`SEED:1424-1425`）、
`outputPerUnit`（`SEED:1426`）、`cycleInputPerUnit`（`SEED:1430`）、`cycleLaborMilli=0`（`SEED:1432`）、
`cycleInputUsedMilli={}`（`SEED:1434`）、`slots`（`SEED:1401-1408/1436`）、`allocation`（`SEED:1409-1410/1435`）。

## 7.3 经营者能不能选择别的既有配方

**不能（代码里没有这个入口）**：

- 一个 `Industry` 只有**一份**配方，由四个字段拼成：`recipe()` 是纯派生视图（`IND:291-299`），没有“配方列表”“配方 id”“切换配方”字段。
- 配方字段的写点只有两处：
  1. 创世载荷生成（`SEED:1239-1257/1280-1293/1351-1374` → `SEED:1391-1438`）；
  2. `economy.Seed` 载荷解析（`PAY.industry:587-670`；`capacityPerUnit` 必填、`capacity` 可选、`laborPerUnit` 缺省 0、`outputPerUnit`/`cycleInputPerUnit` 可选）。
- 运行期读点：`ES.harvest:3147-3159`、`ES.scaleOf:3753-3772`、`ES.capacityScaleOf:2208-2216`、`ES.usableScaleOf:1793-1802`、`ES.laborNeedOf:2958-2978`、`MS.necessaryInputsOf:1699-1725`、读口 `AV:1028-1043`。
- 运行期写点：**没有**。每日重建产业的 `ES.withCycleState:4392-4417` 把 `capacityPerUnit / capacity / dailyInputPerUnit / dailyLaborPerUnit / laborPerUnit / outputPerUnit / cycleInputPerUnit` **原样透传**（字段顺序见 `ES:4400-4409`）。
- `RecipeId` 类型存在（`simos-economy-api/.../id/RecipeId.java`），但 main 代码里**零引用**：`Industry` 用字段而不引用 `RecipeId`，也没有配方目录/注册表。

## 7.4 扩大产能 / 停业 / 重新开业有没有现成状态入口

| 动作 | 现有入口 | 现状 |
|---|---|---|
| 扩大产能 | `EconomyData.withIndustries(Map)`（`EDATA:473-486`） | 只是通用 copy-with；main 代码里除定义外**零调用点**。不是命令 handler，没有权限/目标面 |
| 扩大产能（命令） | 唯一 economy handler = `SEEDH`（`simos-app/src/main/java/io/mosire/simos/app/Shell.java:439`；`SEEDH.type()="economy.Seed"` `SEEDH:49-51`） | `handle`：`meta` 空 ⇒ 整份播种；`meta` 非空 ⇒ 按格追加，**该格已有产业/阶层行 ⇒ 整份拒绝并点名该格**（`SEEDH:74-80`）；`occupiedHexKeys` 从产业 id + 家户行格键判（`SEEDH:105-115`）⇒ **不能改既有格** |
| 停业 | capacity=0 是合法状态（`IND:30-31/82-84`；`capacityScaleOf` 会给 0，`ES:2213`） | 但**没有任何命令/入口把既有产业的 capacity 改成 0**；只有创世格/新格载荷能写 `capacity` |
| 停业 | `progressDays` | 只有创世载荷默认 0（`SEED:1416`；`PAY:600`）与结算每天 +1/关账归 0（`ES:810/836/841`）；没有“暂停”入口 |
| 重新开业 | `cycleInputUsedMilli` | 只有现扣步写（`ES:1928-1937`）与关账清零（`ES:838`）；没有外部写入口 |
| 重新开业 | `capacityScaleOf` | 包内可见的**只读**静态方法（`ES:2208`），main 无写路径 |
| 劳动重配（开工/停工的劳动侧） | `reallocateLabor` | `private static`，只在 `ES:735/758` 调用；不对外；见第 8 题 |
| 任何“产业状态修改”命令 | —— | **代码里没有**：`Shell.java:402-439` 注册的 economy handler 只有 `EconomySeedHandler` 一条；写工具清单里也没有 economy 专属工具（`WorldgenInitializeTool` 只是把 `economy.Seed` 编进命令批，`WorldgenInitializeTool.java:567`） |

## 7.5 capacity / 配方的读写口清单（供第 8 题用）

- 读：`ES.capacityScaleOf:2208-2216`（市场必要投入 `MS:1713`、现扣调查 `ES:1649`、劳动需要 `ES:2963` 都用它）；
- 读：`ES.scaleOf:3753-3772`（收获日规模）；
- 读：`ES.usableScaleOf:1793-1802`（现扣投入日规模）；
- 读：`MS.necessaryInputsOf:1699-1725`（市场买投入的目标）；
- 写：载荷边缘 `PAY:587-670`；创世 `SEED:1391-1438`；每日透传 `ES.withCycleState:4392-4417`。
- 运行期**没有**命令 handler 能改这些字段（7.4）。

---

# 第 8 题：年中需求冲击的最早生效路径

## 8.1 先把时钟与相位钉死

- `day` = 绝对世界日；一次 `AdvanceTime` 在区间 `(from, to]` 内逐日跑（`EDS.step(day)` `EDS:460-490`；`POPAPP:203` 的循环 `range.from().tick()+1 .. range.to().tick()`）。创世是第 0 天，第一个结算是第 1 天。
- 120 天产业（创世 `progressDays=0`，`SEED:1415-1416`）：
  - 第 d 天结算里 `progressed = progressDays + 1`（`ES:810`）；`progressed >= cycleDays` 时收获并把进度归 0（`ES:818-840`）；
  - 所以**收获日 = 第 120、240、360…天**；**周期第一天（进入结算时 `progressDays==0`）= 第 1、121、241、361…天**（第 120 天白天收获、进度归 0，但现扣/重排已经在收获之前跑过了）。
- 市场轮（`MS.triggerFor:332-350`）：
  - `day % 5 == 0` ⇒ `PERIODIC`（`MS:339-341`）；
  - 否则 `anyCycleClosed` ⇒ `CYCLE_CLOSE`（`MS:342-344`；120 天的收获日都是 5 的倍数，所以这一档在 120 天世界里不改变日期）；
  - 否则 `day % 5 == 3` 且 `lowGrainStock` ⇒ `LOW_GRAIN_STOCK`（`MS:345-347`；判据 `MS.lowGrainStock:358-387`：任一有需求家户的 `(stock−frozen) < naturalNeeds[grain] × 10`）；
  - 其余 `NONE`；`markets.isEmpty()` 直接 `NONE`（`MS:336-338`）。
- 单日次序（`plantingDrawsFirst=true`，`ES:358` 默认）：到货 → 现扣投入 → 劳动再分配 → 消费 → 进度/收获 → 市场 → 借 → 还 → 饿死 → 计息 → 流水（`ES:719-1026`）。
  - **现扣投入与劳动再分配都在当天市场之前**（`ES:722-736` 对 `ES:844-881`；取 false 时 `ES:745-759` 仍在市场之前）。

## 8.2 先回答“只写 naturalNeeds / effectiveDemand 的需求冲击”

按第 5 题：
- `naturalNeeds` 的写点 `ES.withDailyNeed:4321` 在消费步先覆写再读（`ES:2431 → 2436`）；市场低粮判据 `MS:367/381` 读的也是覆写后的值；社会压力 `POPAPP:329-334` 在结算之后读。
  ⇒ 写进去的自定义需求**一个结算日都活不过**，且覆写发生在所有当日读者之前。
- `effectiveDemand` 无行为读者。
- 市场买量来自 `life=人口×35天` / `necessary=产能×配方`（`MS:1699-1738`），预算来自 actor 货币副本（`MS:1848-1856`），两者都不读 `naturalNeeds/effectiveDemand`。
- 因此：**“纯需求写 naturalNeeds/effectiveDemand”的冲击，对①劳动 ②开工/停产 ③投入采购 ④产出变化的生效日 = 不存在（代码里没有读取路径）；对市场成交也无生效日（订单生成不看这两个字段）。**
- 真正被 `naturalNeeds` 影响的行为只有三个：当日消费 `ES:2436/2443`、低粮追加轮触发 `MS:367/381`、社会侧满足率/压力 `POPAPP:329-334`。这三个读者读到的都是“结算刚覆写后的值”，所以自定义写不进去。

下面 8.3–8.5 的时间线，针对的是**现有代码里真的存在写路径/状态变化**的那几类“冲击”（人口/劳动配额、产能/配方、投入库存、产出），不是纯需求字段。

## 8.3 四条路径的实际执行链

### ① 劳动变化

代码里能改“实际用于生产的劳动”的路径有四条，对应不同最早生效日：

1. **月度人口回写**（唯一以人口改配额的运行期路径）：
   - 调用链：`POPAPP:203` 日循环 → 第 `day%30==0` 天结算后（`POPAPP:234`）→ `PopulationDynamics.monthly`（`POPDYN:29/69`：`SETTLEMENT_DAYS=30`）→ `POPAPP:238` `stepper.applyPopulationChange` → `EDS:496-505` → `ES.applyPopulationChange:1134-1218` → 逐行改人口/劳动（`ES:1194-1195`）→ `scaleLaborOfGroup:1355-1381`（缩该批次全部配额）→ `scaleSupply:1384-1401`（缩供给）。
   - 生效：**回写发生在该结算日之后**，下一天 `step` 才读新 `allocations`（`ES:666-668` 工作副本 → `ES:767` `laborByActor` → `ES:808` `laborToday`）。
2. **`reallocateLabor`**：
   - 每天都被调用（`ES:735` 或 `ES:758`），但主体先判“本格是否有产业 `progressDays==0`”，没有就 `continue`（`ES:2844-2856`）⇒ **只在周期第一天真的重排**。
   - 生效链：周期第一天 `drawCycleInputs`（`ES:722`/`746`）先写 `cycleInputUsedMilli`，随后 `reallocateLabor` 读它 + 产能算 `need`（`ES:2857-2869`；`laborNeedOf:2958-2978`）→ 保留/回池/按缺口给（`ES:2870-2931`）→ 当天稍后的 `laborByActor`（`ES:767`）按新配额累计 `cycleLaborMilli`（`ES:808-809`）。⇒ **重排当天生效**，但只在周期第一天。
   - 它只能搬已有配额（`ES:2837-2838`），不新增劳动总量。
3. **饿死后的配额缩放**（默认不触发）：
   - 收获日的 4d 步（`ES:928-962`）→ `applyFamine`（`ES:3804-3832`）→ 若有人死，`scaleLaborOfIndustry:1320-1346`；因为当天 `laborByActor` 已在 `ES:767` 算过，**新配额从次日（收获次日 = 下一个周期第一天）起生效**。默认致死率 0‰（`ES:345`）。
4. **`participationPerMille` / `ClassRow.laborMilli` 变化**：不在运行期写；它们只影响 `CR.participationAdjustedLaborMilli`（`CR:156-179`）与 `laborOfCohort`（`ES:3697-3719`），**不影响 `laborByActor` 的当日产业劳动**（日劳动的唯一来源是 `LaborAllocation.laborMilli`：`ES:767/808`；行 `laborMilli` 只喂关系分配的权重与读口）。⇒ 只改行 laborMilli 不改配额，生产劳动量不变。

⇒ 回答派单括号：`reallocateLabor` **每天都被调用，但只在 `progressDays==0` 的格/日真的重排**；对 120 天同步产业，实际重排日是第 1、121、241、361…天。

### ② 开工 / 停产（scale 由什么决定、何时重算）

规模有三个算法/三个时点：

| 算法 | 位置 | 何时算 | 输入 |
|---|---|---|---|
| `capacityScaleOf` | `ES:2208-2216` | 市场日（`MS.necessaryInputsOf:1713`）、周期第一天现扣调查（`ES:1649`）、劳动需要（`ES:2963`） | `industry.capacity / capacityPerUnit` 逐路 floor 后取 min |
| `usableScaleOf` | `ES:1793-1802` | 周期第一天现扣投入 | `capacityScaleOf` 与“可供量 / inputPerUnit”逐路 min（**不含劳动**） |
| `scaleOf` | `ES:3753-3772` | **收获日**（`ES:3150`） | 产能路 + 平均实际劳动 `cycledLabor/cycleDays`（`ES:3148/3762-3763`）+ 实际扣到的投入路（`ES:3765-3771`），逐路 floor 后 min |

- **停产**（capacity→0）：周期第一天的现扣被跳过（`ES:1649-1652`）；市场日的必要投入目标为 0（`MS:1713-1715`）；收获日 `scaleOf` 产能路给 0（`ES:3758-3761`）⇒ 无毛产（输出循环在 `ES:3160-3162` 跳过）；下一周期第一天的 `laborNeedOf` 也因 `scale<=0` 返回 0（`ES:2974-2975`），配额全部回池（`ES:2879-2887`）。
- **开工**（capacity 从 0 变正）：若变化发生在**周期第一天之后、收获日之前**，该周期第一天的现扣已经跳过（`cycleInputUsedMilli` 空）、劳动也已被放掉；收获日 `scaleOf` 即使产能路变正，投入路 `drawn/perUnit = 0`（`ES:3769-3770`）与劳动路 `avgLabor=0`（`ES:3762-3763`）仍会把 scale 压到 0 ⇒ **当周期无产出**。开工要等**下一个周期第一天**的 `drawCycleInputs` + `reallocateLabor`；首个产出在再下一个收获日。
- **重算时点**汇总：市场日重算“要买多少投入”；周期第一天重算“能扣多少料、要留多少劳动”；收获日重算“实际产多少”。
- 运行期**没有**“停业/开业状态”字段：只有 capacity=0/>0 的表达，且没有命令入口（第 7.4）。

### ③ 投入采购（市场轮 + drawCycleInputs 的相位）

- `drawCycleInputs` 在每个产业的**周期第一天**运行（`ES:1642-1644`），在当天消费与市场**之前**（`ES:722-736` / 取 false 时 `ES:745-759`，市场在 `ES:844-881`）。
- 它按 `usableScaleOf` 决定需求上限，逐商品从 relation 指名的供方账上直接扣（`ES:1820-1938`）：供方自己的经营者账优先（`ES:1848-1858`），再按人口占比 + 最大余数分派到 relation 名下的家户账（`ES:1859-1880`），不足时同层补齐/争用池（`ES:1886-1926`）；实际扣到的量累加进 `cycleInputUsedMilli`（`ES:1928-1937`）。
- **市场买投入**是另一条道：市场日在 `MS.necessaryInputsOf` 里按“产能 × inputPerUnit”算目标（`MS:1699-1725`），`gap = target − available − incoming`（`MS:719`），预算与取整见 5.6。买到的东西落在买方账上（买投入的是经营者 ⇒ 经营者账；家户买口粮/布 ⇒ 家户账；`MS.executeTrade` → `applyTransfer`，`MS:1186-1276`），**不会**立即写 `cycleInputUsedMilli`；它们要等**下一个周期第一天的 `drawCycleInputs`** 从 relation 指名的供方账上被扣出来（经营者自己的账优先，空/不在时代理该产业名下的家户账；`ES:1653-1666/1848-1858`），才计入该周期的投入瓶颈。
- 真档创世的边界：farm/weave 经营者开缸钱包为空、craft 有 4,800 毫工资周转金（`SEED:815-826`；`SEED.operatorWageReserveMilli:643-670`；`SEED:647-650` 自述只有 handicraft 有货币档规则）⇒ 真档能通过市场买投入的经营者只有 craft；farm 的种子、weave 的纤维走周期第一天的 `drawCycleInputs`（经营者账空 ⇒ 实际由该产业名下的家户代理账供给）。

### ④ 产出变化（结算/收获在哪一天）

- 产出只有一条产生路径：`ES.harvest`（`ES:3135-3231`），条件 `progressed >= cycleDays`（`ES:818`），即第 120/240/360…天。毛产 `scale × outputPerUnit × 1000`（`ES:3159`），扣损耗后净产进 `ProductionLedger.outputAccruals`（`ES:3166-3175`）。
- 收获日用的 `recipe()` / `capacity` 是**当天状态**（`ES:3147-3150`），所以：
  - 改 `outputPerUnit`：下一个收获日就生效（若当天状态已改）；
  - 改 `capacity`：下一个收获日生效于 `scaleOf` 的产能路，但要与当天已累计的 `cycleLaborMilli` 与已扣的 `cycleInputUsedMilli` 取 min；
  - 改劳动/投入库存：影响下一周期（见①②③）；
  - 市场成交/到货只搬既有库存，不产生新产出（到货日 `ES.deliverShipments:1070-1106` 只是把在途落回库存）。

## 8.4 X=181 的四条具体时间线

先列 181 之后的相位：

| 事件 | 日期（X=181） | 依据 |
|---|---|---|
| 下一个“低粮追加轮”候选 | **183**（当天市场前置条件满足才算数） | `181%5=1`，`183%5=3`；`MS:345-347`；低粮判据 `MS:358-387` |
| 下一个例行市场轮 | **185** | `MS:339-341`；之后每 5 天（190、195…） |
| 下一个收获日 | **240** | 120 天周期：`120,240,360`；`ES:818-840` |
| 下一个周期第一天 | **241** | 第 240 天收获后进度归 0，但当天现扣/重排已过（`ES:722-736` 在 `ES:818-840` 之前） |
| 下一个月度人口边界 | 第 **210** 天结算后回写，第 **211** 天生效 | `POPAPP:234-238`；`POPDYN:69` |
| 再下一个收获日 | **360** | 241-360 这一周期 |

### ① 劳动变化

- 若走**月度人口回写**：第 211 天生效。路径：`POPAPP:234` `day%30==0`（210）→ `PopulationDynamics.monthly`（`POPDYN:29/69`）→ `EDS:496-505` → `ES:1134-1218`/`ES:1355-1401`；第 211 天 `settleOneDay` 从 `allocations` 工作副本取新劳动（`ES:666-668`→`767`→`808`）。
- 若走 **`reallocateLabor`**（容量/投入变化引起的劳动再配置）：第 **241** 天生效（周期第一天），当天晚些的 `cycleLaborMilli` 累计就用新配额（`ES:735/758`→`767`→`808-809`）。
- 若走**饿死缩放**（默认致死率 0‰）：第 240 天收获后改配额，第 **241** 天首次生效（`ES:928-962`；`ES:345` 默认 0）。
- 纯 `naturalNeeds/effectiveDemand` 写入：**无生效日**（8.2）。

### ② 开工 / 停产

- **停产**（capacity 在 181 变 0）：
  - 第 183 或 185 天市场：`necessaryInputsOf` 的 scale=0 ⇒ 不再下买投入的单（`MS:1713-1715`）；
  - 第 **240** 天收获：产能路 0 ⇒ `scale=0` ⇒ 无毛产（`ES:3758-3761`；`ES:3160-3162`）；
  - 第 **241** 天：现扣跳过（`ES:1649-1652`）、`laborNeedOf`=0 ⇒ 配额全回池（`ES:2879-2887`；`ES:2974-2975`）。
  - ⇒ “停产”的最早可见效果是**下一个市场日**（不再买料）与**下一个收获日**（不产出）；劳动释放要等到**下一个周期第一天**。
- **开工**（capacity 在 181 从 0 变正）：
  - 第 240 天收获：本周期 121-240 既没有 `cycleInputUsedMilli`（121 时 capacity=0 被跳过，`ES:1649-1652`）也没有劳动配额（121 时 `laborNeedOf` 给 0 全放掉，`ES:2974-2975`）⇒ scale 被投入路/劳动路压到 0（`ES:3762-3770`），**没有产出**；真档三类配方都满足“laborPerUnit>0 且 inputPerUnit 非空”（`SEED:1253-1256/1289-1292/1359-1373`），故这一支成立；
  - 第 **241** 天：现扣投入（若供方账有料）+ `reallocateLabor` 配劳动（`ES:722-736`）；第 **360** 天：首个产出。
  - ⇒ “开工”的投入采购可能在第 183/185 就开始（见③），但**首个产出日 = 360**。

### ③ 投入采购

- 第 **183** 天（仅当低粮追加判据成立）或第 **185** 天：市场轮开；`MS.necessaryInputsOf` 按当时 capacity×inputPerUnit 重算买料目标（`MS:1699-1725`），`gap/预算/取整` 见 5.6；成交后料落在经营者账。
- 第 **241** 天：这批料（以及 190、195、…、240 各轮买到的）在 `drawCycleInputs` 里从 relation 指名的供方账被扣出（经营者自己的账优先，空/不在时代理该产业名下的家户账）并写进 `cycleInputUsedMilli`（`ES:1848-1858`；`ES:1928-1937`），成为 241-360 这一周期的投入瓶颈。
- 第 **360** 天：这些投入支撑的产出在收获里出现（`ES:3159`）。
- 结论：**181 之后投入采购的市场轮 = 183（条件性）/185；进入生产投入计量 = 241；变成产出 = 360**。若采购发生在 241 之后，则只能支撑 361-480 周期、第 480 天产出。
- 边界：真档只有 craft 经营者有钱在市场上买料（`SEED:815-826/643-670`）；farm/weave 的投入在 241 由周期第一天的 `drawCycleInputs` 从 relation 指名的供方账扣（经营者自己的账优先，经营者账空/不在时代理该产业名下的家户账；`ES:1653-1666/1848-1858`），不经过“181 之后的市场轮”。

### ④ 产出变化

- 若变化的是**当天状态里收获日会读的字段**（`outputPerUnit`、`capacity` 的下降、或已累计的投入/劳动已经允许）⇒ 第 **240** 天是本周期产出变化的最早生效日（`ES:3147-3159`）。
- 若变化需要新的投入/劳动（capacity 上升、开工、`inputPerUnit` 变化导致 121 时没扣够）⇒ 第 **241** 天重新备料/配劳动，第 **360** 天才是首个产出变化日。
- 其余日期没有产出结算：`ES:818` 是唯一收获判据；市场/到货不产生产出（`ES:1070-1106`；`MS:1186-1352`）。

**合并成一条 X=181 的综合时间线**（若同一冲击同时改 capacity/配方/劳动）：第 181 天冲击 → 第 183（条件性，仅低粮）或 185 天市场重算买料 → 第 210 天结算后月度人口回写 → 第 211 天劳动首次变化 → 第 240 天本周期收获（用当天状态、受 121 时已扣投入与已累计劳动约束）→ 第 241 天下周期现扣投入+劳动重排 → 第 360 天该周期的产出。纯 `naturalNeeds/effectiveDemand` 写入不进入这条链的任何一环（8.2）。

## 8.5 “各类冲击 → 最早生效日”一览（X=181）

| 冲击写的是什么 | 最早生效日 | 执行链/判据 |
|---|---|---|
| `naturalNeeds` / `effectiveDemand`（纯需求字段） | **无（不存在读取路径）** | `ES:2431/4321`（覆写先于读）；`MS:1699-1738`（市场不看这两个字段） |
| 人口/劳动配额（月度回写路径） | **211** | `POPAPP:234-238` → `POPDYN:69` → `EDS:496-505` → `ES:1134-1218/1355-1401` |
| 劳动再配置（容量/投入引起的 `reallocateLabor`） | **241** | `ES:735/758` → `ES:2844-2856`（只在 progressDays==0）→ `ES:2857-2931` |
| 饿死劳动缩放（默认 0‰ 不触发） | **241**（收获日 240 后） | `ES:928-962` → `ES:1320-1346` |
| 停产（capacity→0） | 市场日 **183（条件性）/185** 不再买料；产出 **240**；劳动 **241** | `MS:1713-1715`；`ES:3758-3761/3160-3162`；`ES:1649-1652/2879-2887` |
| 开工（capacity 0→正） | 投入/劳动 **241**；首个产出 **360** | `ES:1649-1652/2974-2975`（240 无产出）；`ES:722-736`（241 备料配劳动）；`ES:3159`（360 产出） |
| 投入采购（市场买料） | 市场轮 **183（条件性）/185**；计入生产 **241**；产出 **360** | `MS:339/345-347/358-387`；`MS:1699-1725`；`ES:1848-1858/1928-1937` |
| `outputPerUnit` 变化 | **240**（本周期收获日就会读当前值） | `ES:3147/3157-3159` |
| `capacity` 下降 | **240** | `ES:3758-3761` |
| `capacity` 上升 | 若投入/劳动已备足 ⇒ **240**；否则 **360** | `ES:3762-3770`（投入/劳动路取 min） |
| 市场价 / 预算 / 库存变化 | 下一个市场日 **183（条件性）/185** | `MS:332-350/679-748` |

---

# 我没做 / 没验证的

- 只读代码，**没有编译、没有跑任何测试、没有起服务、没有跑 Maven**；所有“行号/执行链”都是静态阅读结果。
- **没有实跑验证**任何溢出阈值：第 6.3 的乘法估算是用代码常量与派单给的真档量级手算的，未构造 1e11~1e12 库存的世界去实测 `PSPLIT` 溢出。
- **没有实跑验证**第 8 题四条时间线；其中“第 240 天开工无产出、第 360 天首产”“第 211 天月度劳动生效”都是按 `settleOneDay` 的次序与 `POPAPP` 的日循环推出的执行路径，未经运行时观测。
- **没有 grep 全部模块**的除法/取整点：第 6.2 表只覆盖经济结算管线 + 创世器 + 两个读口/回写器（`6.2` 抬头列的范围）；`simos-social/PopulationDynamics` 的月度除法、actor/goods 账户实现、codec/JSON 序列化未逐点扫。
- **没有核** `RecipeId` / `OrderId` 等 ID 类型在 test 代码里的引用；本文“main 代码零引用”只按 main 源码 grep 结论。
- 没有改 `src/**`、`docs/**`、任何既有台账；没有 `git add/commit`；除本文件外未写任何文件。
- 派单与本树 HEAD 的偏差（派单 5b6b2be7 / 实测 c8520f34）已记在文首；我按当前工作树读码，并核对过 `git diff --stat` 仅一条 docs 新增、代码态同源。
