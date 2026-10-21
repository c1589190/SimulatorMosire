# D-032 32hex / 360 tick 前几轮模拟异常调查报告

> **状态：只读调查结论，不是实现验收。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> 父设计：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md`
> 证据：当前 12/48 报告、完整 DEBUG 重跑日志、SQLite revisions/checkpoints、旧 8/34 日志。
> **没有修改生产代码、没有跑 Maven/GUI、没有 commit。**

## 0. 用户原话与调查边界

与本轮直接相关的用户原话：

```text
简单修改生产方式的数值预设，再跑一轮360tick
开始调查前几轮模拟发现的问题
刚刚dsh崩溃了，让子Agent恢复工作状态，你也继续
```

本轮调查只回答“为什么前几轮会出现这些现象、哪些是当前实现 bug、哪些是 v2 尚未实现、报表口径哪里会误导”。
**不把“v2 未实现”当成已发生 bug 的唯一解释，也不拿 v2 条目冒充当前验收已过。**

数值范围说明：当前仍是 **120 tick 生产周期**。`GRAIN_OUTPUT_PER_MU=48`、`SEED_MILLI_PER_MU=12,000` 表示
**每 120 天每亩 48 粮 / 12 kg 种子**，不是“每 360 天每年 48 / 12”；若直接按年度历史基准读，会再乘 3。
这是用户要求的“简单预设改动”，不是历史年度农业模型已经实现。

## 1. 基线与可复现性

### 1.1 当前 12/48 跑

- 命令：`SIMOS_MULTITICK_SKIP_BUILD=1 JAVA_OPTS='-Dsimos.economy.logLevel=DEBUG' tools/multitick-sim.sh rural-32hex-360d`
- rc=0；`MULTITICK_SIM_RESULT=PASS cases=1 failed=0`；360/360 `status=OK`；115.8s。
- CSV sha256：`80cf984645ec314ecbc4b9864598392ae1aee651df3103b264b05d07fffaa9aa`
- 完整 DEBUG 日志：`.superpowers/sdd/2026-10-20-d032-rural-360-investigation/evidence/360-rerun-debug.log`
  - sha256：`8744641709108cf524bacc1a4164ca614dc901cadffa069d490c3d50376c2be5`
- 复跑 CSV 与已有 12/48 报告**逐字节相同**，因此下列现象是稳定可复现的当前代码行为，不是一次随机波动。

### 1.2 关键总量

| 指标 | 数值 |
|---|---:|
| tick0 → tick1 人口 | 7,100 → 7,101 |
| tick360 人口 | 5,347 |
| 出生 / 死亡 | 398 / 2,151 |
| 其中饥荒死亡 | **2,116**（纯 base 死亡 35） |
| tick0 总粮 | 576,875,000 毫 |
| tick1 总粮 | 68,806,577 毫 |
| tick1 seedInput | 507,593,550 毫 |
| tick1 日粮 | 474,873 毫 |
| tick360 总粮 | 41,973,998 毫 |
| productionGrainMilli 总产出 | **0** |
| 市场成交笔数（72 个市场日逐轮相加） | 658 |
| marketUnfilled 槽位（逐轮相加） | 27,770 |
| 结束债务本金 / 合同数 | 1,325,756 / 2,299 |
| 土地总量 | 99,200,000 千分亩（全程守恒） |
| 土地成交 | **0** |
| 债务抵地数量 | 34,720,000 千分亩 |
| 结束地价 | 0 |

### 1.3 旧 8/34 报告的时效边界

旧跑日志在 `01:43–01:46`，而 `simos-production/target/classes/.../ProductionRuntimeEngine.class`
的 mtime 是 `01:49:38`。旧跑由 `SIMOS_MULTITICK_SKIP_BUILD=1` 启动，因此**不能证明旧跑与新跑是同一字节码**。
旧跑 tick120 的 9,792,000 只能作为历史现象，暂不能当受控对照。

旧日志显示 `farm@hex` 每 hex `grain=306000 / fiber=54000`：
306,000 = 9 × 34 × 1000，所以旧毛产更像 **32 个 rich farm × finalScale 9 ×34×1000**，
不是“96 个 unit ×3”；`96×3` 只是算术等价。该分解仍是由劳动差、`preserved` 差和 hex 聚合毛产交叉推出的强推断，
不是 receipt 级逐 unit 证据。

## 2. 故障树（按因果顺序）

```text
每 hex 5 个 farm unit：poor/middle/rich/landlord + displaced
        │
        ├─ tick1：M6 劳动编排早于 M7 投入现扣
        │     → 96 个 poor/middle/rich farm 读到 drawn=0
        │     → NO_INPUT / maxAbsorbable=0
        │     → 既有 farm commitment 被删
        │     → 整周期 cycleContributions={}
        │
        ├─ tick1：整周期种子从同 hex 池中预扣
        │     → 128 个 rural non-landless 户全部 grain=0
        │     → 32 landless 各 374,325；5 个 urban 户共 56.83M
        │
        ├─ day3+：128 户 ration gap=1000‰
        │     → Social famine 1000 ppm/tick → 2,116 饥荒死亡
        │
        ├─ day10–50：M2 用土地清偿 landlord 工资债
        │     → 34.72M 千分亩全部转给 32 landless
        │     → 32 landlord farm 失去 LAND scope
        │     → asset-location-scope-missing，progress 冻结在 49
        │     → 唯一有 hired_laborer 的 unit 消失 ⇒ hired/wage=0
        │
        ├─ day5 后 grain 价→0
        │     → M3 maxSpend=0 不生成 grain 买单
        │     → 同时 actor 级替换删掉全部自动买卖单
        │     → 42M 库存没有 grain 卖盘 / 没有 grain 买盘
        │
        └─ 跨 hex 运力=0：即使有买卖双方，大量 redistribution 被拦
```

## 3. P0：生产链根因

### 3.1 M6 劳动编排早于 M7 投入现扣，96 个 farm 被误判 `NO_INPUT`

当前主日序（`simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyDayPipeline.java:107-115`）：

```text
M5 settleConsumption
M6 planActualProduction / planActualLabor
M7 drawActualInputs / driveProduction / closeAndHarvest
M8 settleWageAndCapitalize
```

`LaborQueueBook.offer()` 在 `simos-economy/.../LaborQueueBook.java:294` 读取
`cycleInputUsedMilliOf(session, unit)`；`cycleInputUsedMilliOf` 在 `:750-768` 于“本日尚未现扣”时回退到实例旧值。
tick1 实例旧值为 0，因此：

- 新跑 day1 恰有 **96 条** farm `LABOR_QUEUE_PLAN`（poor/middle/rich）：
  `NO_INPUT:grain:drawn=0:perScale=12000:market-priced-but-purchase-after-allocation`；
- `maxAbsorb=0`，`granted=0`，`outcome=IDLE_MAX_ABSORBABLE_ZERO`；
- 日志时间 `LABOR_QUEUE_PLAN 02:15:02.281` 早于 `PRODUCTION_INPUT_DELTA_RECORDED 02:15:02.303`，直接证明顺序反了。

进一步，`LaborQueueBook.isPreservedByQueue()`（`:887-895`）不保留 `NO_INPUT`；
`LaborQueueWritebackBook.applyPlan()`（`:47-90`）对进入 decisions 的 `(unit, role)` **先删除旧 commitment**，
只有 `grant>0` 才重新写回。默认 `PREFER_IDLE_WHEN_SUBSISTENCE_UNMET` 下 `LaborQueueBook.plan()`（`:560-573`）
对所有 offer（包括正收益 offer）也可给 `granted=0`，于是预置 farm 劳动被清空且永不恢复。

结果：96 个 farm 在 DB rev120（tick119）显示 `progressDays=119`、`cycleInputUsedMilli.grain>0`、
`cycleContributions={}`。周期末 `ProductionEfficiency` 的劳动通道为 0：

- `ProductionEfficiency.java:177-193`：周期劳动→日均劳动；
- `:210-217`：三通道取 min，劳动通道=0；
- `:227-236`：`finalScale=0`；
- `ProductionRuntimeEngine.java:1321-1337` 用 `finalScale` 求毛产；
- `:1621-1633` `scale<=0` 直接返回空 gross；
- `ProductionRunSettlement.java:639-640` 无净产直接 return。

### 3.2 48/12 的作用是“让 rich 进入候选”，不是输出公式归零

新旧 day1 对照：

| 指标 | 旧 34/8000 | 新 48/12000 |
|---|---:|---:|
| `D032_P3_EXPECTED_PLAN positions` | 98 | 130 |
| rich farm `expectedPositions` | 0 | 1 |
| day1 `NO_INPUT:grain` farm 数 | 64 | 96 |
| rich `preserved` | 13,685 | 1,369 |

正收益门槛在 `ProductionPlanBridge.java:543-557`（`positionNetMilli() <= 0 ⇒ continue`）。
旧 34 时 rich farm 不进 allowedSlots，旧 commitment 被保留；新 48 时 rich farm 进入 allowedSlots，
随后撞上 M6/M7 的 `NO_INPUT`，commitment 被删除，因此农场产出归零。

**严格结论**：已核实的是“48/12 联合参数使 rich farm 从 0 个候选变为 1 个候选”；
“48 单变量是唯一原因”仍待四格实验（34/8000、48/8000、34/12000、48/12000）隔离。
12 kg 同时抬高投入成本，方向相反，不能把它单独归因于 48。

### 3.3 种子整周期预扣 + 同 hex 共享池，清零 128 户

当前 `ProductionInputSettlement.java:309-371` 只在 `progressDays==0` 按计划规模调查整周期投入，
不检查该 unit 是否被 M3/M6 实际选中、是否有实际劳动/实际播种面积。
`ProductionInputDrawSettlement.java:293-390` 再用 `plan.limitOf(usableScale)` 取
`min(stock, ask)`，没有保留本户日粮的上限。

tick0 → tick1 的账逐毫闭合：

```text
576,875,000 − 68,806,577 = 508,068,423
seedInput 507,593,550 + 日粮 474,873 = 508,068,423
```

tick1 后：

| 类别 | 户数 | tick0 粮 | tick1 后粮 |
|---|---:|---:|---:|
| poor | 32 | 96,000,000 | 0 |
| middle | 32 | 168,000,000 | 0 |
| rich | 32 | 144,000,000 | 0 |
| landlord | 32 | 100,000,000 | 0 |
| landless | 32 | 12,000,000 | 11,978,400（每户 374,325） |
| 有人的 urban | 5 | 56,875,000 | 56,828,177 |

128 个农村非无地户全部清零；32 个 landless 和 5 个 urban 保留粮食。
landlord nominal seed 177.66M **大于**其自身初始 100M，只能由同 hex 其他户补足，证明当前不是“每户只扣自己账”，
而是 `ProductionInputSettlement.java:597-657` 的 `rationContestedInputs` + `ProductionInputDrawSettlement.java:340-377`
的 `sharedKeys` 跨户口径；这与 v2 P0-06/C27/C28 冲突。

这直接导致 day2 起 128 户 ration gap 1000‰；Social 的 famine 率为 1000 ppm/tick：

- `FamineMortality.java:80` 线性饥荒率；
- `HouseholdBook.java:939-955` `baseDeathPpm + famineRate` 合成；
- 全期 famineDeaths=2,116，纯 base=35。

### 3.4 土地抵债后 land→unit→labor 组织脱节

`DEBT_FIRST_M2_ROUND` 9 个市场日共转出 **34,720,000 千分亩**（正好原 landlord 土地总量的 35% 权重 × 99.2M），
day50 后 landlord 土地为 0，landless 持有 34.72M。`landPrice` 从 10 每市场日 −1 至 0。

更精确的 unit 事实（修正“新 landowner 无 unit”）：

- `LandOwnershipSeeder.java:76-93` 创世就给每个 displaced 户一个 **0 地 farm unit**；
- day50 没有 displaced unit 被拒，仍有 `receipts=1`；
- 真正的缺口是该 unit 的 roster `plannedLabor=0`，其劳动原先只挂在 landlord 的 `hired_laborer` 角色上；
- M3/M6 没有在土地产权转移后重新排产/重建组织。

所以是：

```text
land → asset scope 接上了新 owner 的 unit；
labor/organization 没接上。
```

同时，32 个 landlord farm 在 LAND scope 消失后每 tick 被 `ProductionRunSettlement.java:319-323` 拒绝，
原因 `asset-location-scope-missing:<hex>`；progress 永远停在 49，永不收获。
`LandProductionProjection` 会从 `landHoldings` 合成瞬态 LAND 份额（`SettlementIndex.java:268-282`），
所以不是“投影完全没接”；问题是当前 per-household unit 的 synthetic LAND 只来自该 unit operator 自己名下的地，
operator 失去全部土地后自然没有 scope。v2 需要 S2/S3 在下一生产 tick 重新组织，当前尚未实现。

## 4. P0：市场订单根因

### 4.1 0 价时 grain 买单消失

`MarketIntentBook.java:123-133`：

```java
long remainingBudget = moneyOf(...);
for (commodity ...) {
  if (!market.hasPrice(commodity) || remainingBudget <= 0L) continue;
  long maxSpend = min(remainingBudget, quantity * ask / 1000);
  if (maxSpend <= 0L) continue;
  ...
}
```

day5 后 grain 价自适应降到 0；`ask=0` ⇒ `maxSpend=0` ⇒ 96 个有缺口的户没有 grain 买单。
他们仍有 cloth 等买需（tick9 复算 101 条 cloth 买单，与日志 `MARKET_INTENTS_APPENDED commodityBuys=101` 吻合）。

### 4.2 actor 级自动订单替换删掉其他商品腿

`MarketRoundRunner.java:522-527,761-799` 对“有任何 M3 商品意向的 actor”删除**该 actor 全部**自动买卖单：

```java
ctx.buys.removeIf(slot -> intentActors.contains(slot.order.requester()));
ctx.sells.removeIf(slot -> intentActors.contains(slot.order.supplier()));
```

这些 101 个 actor 因 cloth 单进入 `intentActors`，其自动 grain 买单也被删；M3 又因 0 价不补 grain 单。
day10 因此 `grain demandMilli=0, supplyMilli=9,777,790, fills=0`。

正确 0 价路径本来存在：`MarketOrderPlanner.java:302-319` 的
`referenceInPay == 0 ⇒ cashAffordable = Long.MAX_VALUE`；被 M3 显式 intent + actor 级替换绕坏。

### 4.3 实际库存盈余没有成为卖盘

`MarketIntentBook.java:88-112` 的商品卖盘只从 `outputSupplyByOrganizer`（入选生产位置的预期产出）生成，
并以 `onHand - ownNeed` 封顶；没有为“有库存但本期无生产位置”的家户生成实际盈余卖盘。
`MarketRoundRunner` 又删除 270+ 自动卖单、只补 2–3 条 M3 商品卖单：

- day50：`removedAutoSells=258`，`commoditySells=2`；
- day290：`removedAutoSells=271`，`commoditySells=3`；
- tick290 grainStock=46,251,049，但 `marketRoundStats.lastRound.grain.supplyMilli=0`；
- 库存集中：middle 26.3M / rich 11.3M / poor 5.1M / landless 3.65M / landlord 0。

这是“总库存 42M 但没有卖盘”的直接实现缺口，也对应 v2 S7“卖本轮生产后的实际剩余盈余”未实现。

### 4.4 土地买盘路径在 M3 恒死

`MarketLandIntentBook.java:317-337` 的 `minimumLandCapacityPerUnit()` 第一行读 `ctx.hexToIndustries`；
该字段唯一赋值在 `SettlementDayProductionPipeline.java:401`（`closeAndHarvest`，M7），
但 M3 `EconomyDayPipeline.java:57-63` 生成土地意向时它仍是 `null`，因此：

- `collectLandlessBuyDrafts` 的最小地块买盘恒为 0；
- 360 tick `D032_P3_LAND_INTENTS` 全部 `landBuys=0`；
- `LAND_ORDER_REJECTED` 9,440 条全部 `SELL/NO_BUYER`；
- 卖侧 `MarketLandIntentBook.java:203-237` 又把每个持有 key 的 `unused=usable-required` 全部挂出；
  day5 `supply=98,208,000 / total=99,200,000`（99%）。

价格公式按 D=0、S>0 每市场日 −1，是正常的整数步进；0 价合法，若 D>S 可从 0 回到 1。
主因是买盘死路 + 卖盘几乎全部 + v2 S3/E9 未实现，而不是 pricer 单独坏了。

### 4.5 运力全 0

`MERCHANT_CAPACITY_POOL` day5：64 户 merchant，`capacityMilli=0`，`householdsWithDeliverableService=0`；
`MerchantCapacityCore.java:108-141` 在 haul 有价时把 capacity 等同于“haul 服务货可用量”，而无人持有 haul；
`MarketPairingBook.java:174-205` 在总运力 0 时直接拦跨 hex lane。

这是 v2 P1-05 未实现。注意分清：

- day5 grain 其实全部成交（unmet=0），`NO_ADJACENT_SUPPLY=124` 主要是 cloth 等其它商品；
- day125/245 才是 grain supply 14–15M、却被 `LOGISTICS_CAPACITY=282/277` 卡住 redistribution 的明确例子。

## 5. P1：劳动/工资/债务结论

### 5.1 工资算法本身符合目标口径

`WageSettlement.java:516-565` 只从 `ProductionReceipt.laborContributionsMilli` 取 MoneyWage 义务；
payer = roster organizer；`ProductionWageBook.java:135-166` 按实际劳动算 due；
`WageSettlement.java:321-351` 只从 spendableMoney 付现金，未付立即 `DebtContractBook.upsert(WAGE_ARREARS, 0‰)`。
day1 有 32 个真实雇佣对，每 hex 5,160 labor，quote 20，due=103，paid=103；
day2 paid=17 / arrears=86；day3+ paid=0；全程 wagePaid=3,840、wageArrears=141,600。
没有发现重复开工资合同或同 tick 双记；tick50 后 due=0 是 landlord unit 被拒后没有 hired_laborer contribution 的结果。

### 5.2 productionLabor 分段下降不是工资造成

- day1 746,488；
- day2–8 610,392（landlord budget 开始受限）；
- day9–14 446,200（landlord budget=0）；
- day15–49 214,072（其余地户 budget=0）；
- day50 起 48,952（landlord unit 全被拒）；
- 之后继续降到 8,333（到 tick360）。

`SocialData.java:774-794` 的劳动预算 = 基础劳动 × satietyPerMille ÷ 1000；`HOUSEHOLD_LABOR_CAPPED` 显示 budget=0。
所以是“断粮 → satiety=0 → 劳动预算=0 → commitment 缩/删”的上游闭环，不是 WageSettlement。

### 5.3 后段债务主要是市场赊购 + 货币市场贷款

tick180 债务 363,074 中工资未偿仅 3,131；tick300 1,298,290 中工资仅 602。
day140 增量 +188,069 = `MARKET_DEFERRED_PAYMENT` 176,343 + money-market lent 13,082 − M2 repaid 1,356。
day245 同类。利息仅关账日计：day240 6,452、day360 27,711，远小于 1.3M。

`debtContractCount` 不是“一个工资义务一个合同”：合同 id 含
`(debtor, creditor, currency, terms)`，货币市场利率变化会为同一对家户另立合同；
工资合同固定 32 条。因此 2,299 不能读成 2,299 笔工资债。

## 6. 报表口径（很容易误判）

| 列 | 真实口径 |
|---|---|
| `grainStockMilli` | 全 actor 账户加总，不代表任何一户可获得；tick360 41.97M 中 255 户 grain=0 |
| `seedInputMilli` | `ProductionReceipt.consumedInputs` 求和；关账 receipt 是**周期累计**；tick120 的 329.9M 不是当日新扣，且漏掉 32 个 rejected landlord 已扣的 177.66M |
| `productionGrainMilli` | 当前 tick receipt 毛产求和；全 360 tick 为 0 |
| `productionLaborMilli` | 当前 tick receipt 劳动贡献求和，不是总劳动 |
| `marketTrades` | 单市场轮 `MarketReport.fills().size()`，笔数；不是商品数量/户数 |
| `marketUnfilled` | 单市场轮未成交槽位数，买卖混合、按商品可多条；不是累计/数量/户数；72 日求和 27,770 存在重复 |
| `landTradedMilliMu` | 每轮 `LAND_TRADE_SETTLED` 数量；全程 0 是真实成交 0 |
| `landDebtTransferredMilliMu` | 每债务轮转移的**千分亩数量**，不是钱；9 轮合计 34.72M，名义价值约 129,169 毫银 |
| `landPriceMilliPerMu` | tick 末时点价；M2 土地清偿用盘前价，day50 的 0 不矛盾 |
| `landTotalMilliMu` | 时点库存，99.2M 守恒总量，不是异常 |
| `debtContractCount` | 合同版本数（creditor×rate terms），不是义务/工资债笔数 |

`marketUnfilled=423`（day360）的实际组成是
`NO_ADJACENT_SUPPLY=155 + NO_BUYER=96 + INPUT_SHORTFALL=140 + UNSOLD_SELF_USABLE=32`，
不是 423 个饥民；实际未满足粮食数量应看 `marketRoundStats.lastRound.grain.unmetMilli=13,545,870`。

## 7. 问题分类

### 7.1 当前实现 bug

1. **M6/M7 日序**：劳动编排先于投入现扣，`drawn=0` 误判 `NO_INPUT`；随后 `LaborQueueWritebackBook` 删除 commitment。
2. **整周期种子预扣 + 同 hex 跨户池**：无实际劳动/实际播种面积；128 户被清零。
3. **0 价买盘消失**：`MarketIntentBook` 在 ask=0 时 `maxSpend=0` 直接跳过。
4. **actor 级自动订单删除**：有任一 M3 商品意向就删掉该 actor 的所有自动商品腿。
5. **实际库存盈余不生成卖盘**：商品卖盘只来自生产位置预期产出，库存集中在少数户时没有 grain 卖盘。
6. **土地买盘路径在 M3 恒死**：`ctx.hexToIndustries` 的赋值晚于 M3。
7. **土地卖盘几乎全部**：把 `usable-required` 直接当卖单，没有“明确不用于生产/主动卖地”决策。
8. **landlord unit 失去土地后没有退役/重建**：progress 冻结在 49；新 landless owner 有 unit 但 plannedLabor=0、无组织重排。
9. **跨 hex 运力全 0**：仍依赖 haul 服务货，未实现 v2 简单运力函数。
10. **工资/债务读数口径**：debtContractCount 版本数、seed 累计值、marketUnfilled 槽位数容易被当成业务真值。

### 7.2 v2 尚未实现

1. 没有 `DailyDemandBill` 单一对象；仍是 `HouseholdEconomy.naturalNeeds` + `ConsumptionSettlement` +
   `SurvivalSocialFacts` + 市场 5 日派生 + GUI 读口多套表。
2. v2 S3 单 hex 标准农业聚合、动态 hiredLand 位置、实际播种面积 `sownLandByOwner`、逐日 `B_day/B_cycle` 未实现。
3. v2 S7 统一需求账单/实际盈余卖盘/商品+土地同簿语义只部分实现。
4. v2 P1-05 简单确定性运力函数未实现。
5. v2 E9 土地买方需求由 S3 实际缺口派生、卖方只出明确未用地/主动卖地未实现。
6. v2 S8 统一温饱事实回传与“市场买入不回填本 tick S4”未实现；当前市场买到的货当天就进入 M5 消费。

## 8. 建议修复顺序（不先调 48/12）

### 第 1 步：止血生产链（必须先做）

- 修正 M6/M7 顺序或让 labor queue 读“本日预期/当刻可用的投入”，不能再把 `drawn=0` 当 `NO_INPUT`；
- 只有实际劳动/实际播种面积才能扣种子；不得按整周期计划规模预扣；
- 拆掉同 hex 跨户种子池；
- 保留每户日粮，任何种子/生产投入不能把家庭粮清零。

**判据**：

- day1 不再出现“有种子库存的 farm 因 `drawn=0` 被判 NO_INPUT”；
- 96 个 farm 的 `cycleContributions` 非空；
- tick120 farm `productionGrainMilli > 0`，且输出能按 finalScale 解释；
- tick1 后 128 个农户不再全部 grain=0。

### 第 2 步：土地取得后的生产组织重排

- M2 抵地/土地买卖后，下一生产 tick 为 new owner 建立/重绑 farm unit、roster 与 plannedLabor；
- 旧 landlord 若不再有地，显式退役/关闭 unit，不能每 tick `asset-location-scope-missing` 冻结；
- 落实 v2 S2“进入/迁移/组织变化”和 S3“实际土地参与生产”。

**判据**：

- day50 后 landless 新地主进入 M3 有正收益 farm 位置；
- landlord unit 不再永久停在 progress=49；
- `PRODUCTION_REJECTED` 归零/只剩合法业务拒绝；
- landBuys 由 S3 实际缺口派生，landSells 不再 99% 全量。

### 第 3 步：市场订单语义

- `MarketIntentBook` 0 价也生成需求；不以现金/库存作为挂单门；
- `MarketRoundRunner` 只替换同一 `(actor, commodity, side)` 的自动腿，不整 actor 删除；
- 按实际库存盈余生成卖盘；
- 修 `ctx.hexToIndustries` 在 M3 的初始化；
- 运力改成 v2 简单确定性函数，不以 haul 服务货为唯一来源。

**判据**：

- grain ask=0 且有缺口时仍生成买单；
- 有 42M 库存时有可解释的 grain 卖盘；
- 商品/土地同一订单簿能看到非零成交；
- 0 价合法，债务清算 0 价不冲本金（当前债务侧已实现）。

### 第 4 步：债务/工资读数与信用边界

- 工资算法暂不需要先改；
- 保留“只现金、未付成 0‰ 普通债”，但要按 `DebtSource` 拆出工资债/市场赊购/货币市场贷款；
- 明确 `debtContractCount` 是合同版本数，不直接当义务数；
- 在无生产/无收入环境下限制或明确普通市场赊购/货币市场借款的边界，避免债务读数淹没真实工资链。

### 第 5 步：统一测试与参数隔离

- 四格参数矩阵：34/8000、48/8000、34/12000、48/12000；
- `SURVIVAL_IDLE_POLICY=0` 对照；
- `drawActualInputs` 提前/让 labor queue 读预期投入的对照；
- 之后跑统一测试、`clean verify`、360 tick 复跑。

## 9. 仍需验证 / 未做

1. 用当前字节码 clean 重跑 8/34 对照（不能污染当前未提交代码；旧跑字节码 provenance 未 100% 证明）。
2. 四格参数隔离，确认 48 与 12 各自的影响。
3. `SURVIVAL_IDLE_POLICY=0` 对照，确认 idle `granted=0` 是直接断点还是仅放大器。
4. 逐 unit receipt gross/consumed 审计；当前 32×9 分解仍是强推断。
5. 逐户 Social `satiety` / 劳动预算直接日志（当前由 `HOUSEHOLD_LABOR_CAPPED` 与 SocialData 代码推得）。
6. 完整 `clean verify` / `tools/gate.sh`；本轮未跑。
7. 真实世界长跑与 GUI/MCP 未启动；未做任何提交。

## 10. 结论一句话

**前几轮不是“农业产出数值不够”，而是生产、种子、土地组织、市场订单四条链同时断路：**
`M6/M7 时序把 96 个农场劳动清零；整周期跨户种子池把 128 户粮清零；债务抵地让 32 个 landlord unit 失去土地且新地主没有劳动组织；0 价/actor 级替换/库存不出卖盘让市场无法把余粮送回饥户。`
在这个闭环修好之前，继续调 48/12 或亩产数值不会得到可解释的农业结果。
