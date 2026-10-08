# 阶段 1 实现架构账本：家户统一活动选择器 + 保留价套利（2026-10-08）

> 派单：`task-8`（责任区：阶段 1 全部生产代码，只写代码、只过编译）。
> 约束设计书：`docs/superpowers/specs/2026-10-08-currency-exchange-arbitrage-and-port-design.md`
> （§1 用户原话最高权威、§3.3、§4.1–§4.3.5、§6 不变量、§7.0 判据、§8 负向用例、§9 落点、§12 阶段 1）。
> ★ 控制方 2026-10-08 的**口径更正**（随会话下达，与 §7.0/§12 第 3 条一致）已逐条落实：
> 「不做买低格卖高格；本阶段套利维度 = 家户自有价目表（保留价）vs 市场价的价差」。

---

## 1. 关键调查结论（`file:line` → 结论 → 影响）

| # | 证据 | 结论 | 对本实现的影响 |
|---|---|---|---|
| C1 | `MarketTopologyBook.java:129-146`（`cityAuthority && sameNumeraire ⇒ MarketTopology.singleRegion`）、`MarketTopology.singleRegion`（`single-region`、全部市场格一区） | **生产世界里全部有市场的 hex 属于同一个市场区** | "同一市场区内跨格"= 全世界跨格，没有第二个区可做跨区套利 |
| C2 | `MarketSettlement.java:2545`（`price = ctx.referencePriceOf(regionId, anchorMarket, commodity)`）、`:1181`（`updated.put(member, new Market(current.numeraire(), prices))`）、`:1112-1113` 注释「区价 = 集散节点价，成员格同改」 | **区内所有成交按锚格价、且自适应定价把同一份价表写进该区全部成员格** | **不存在跨格价差** ⇒ 设计书 §12 阶段 1 原文（买低格卖高格）不可实现，控制方口径更正得到代码印证；套利维度只能是"保留价 vs 市价" |
| C3 | `MarketSettlement.java:2928-2941`（卖方按 `landedCostOf = unitCostEstimate + freightPerUnit` 成本分档、逐档配给） | 跨格货物流向已由既有机制承担 | 本阶段**不**新增任何搬货/承运机制（符合派单硬约束） |
| C4 | `MarketSettlement.java:3499-3505`（`HexTradeCost.lossPerMilliBetween` 只在 `!inTransit && sell.hex != buy.hex` 时计损耗） | 单 hex 贸易成本只表达为**实物损耗**、`costMilliPerUnit` 恒 0（`HexTradeCost.java:45`） | 保留价套利不需要它参与算式（无跨格腿） |
| C5 | `MarketSettlement.ordersFor`（`:1331-1345`）：`sellable = max(0, stock − frozen − necessary − life − demandTarget)`，`buyTarget(家户) = life + demandTarget` | 家户的**卖**已经"把生活保留/必要投入/需求目标之外的全部余量挂出去"；家户的**买**只补目标缺口 | ① 套利**加卖**的空间为零（加卖必然侵占 `necessary` ⇒ 违 I14）；② 套利唯一安全且非平凡的方向是**加买**（在目标缺口之上加量） |
| C6 | `MarketSettlement.householdLifeReserveOf`（`:4922-4927`）用 `HouseholdEconomy.expectedNeedMilli(GRAIN, MARKET_LIFE_RESERVE_DAYS=35)`；`MARKET_SAFETY_STOCK_DAYS=30`（`:121-127`） | "生活保留"是**35 天**前瞻、市场安全库存是 **30 天** | 我选 `HOLD_DAYS = 30` 作目标保有量（与市场同一把尺）；并由此**证明** HOLD（留货不卖）分支在当前常量下不可达（见 §3-K3） |
| C7 | `LaborQueueBook.offerOrder()`（`:379-385`）+ `LaborQueueBook.plan`（`:339-380`）+ `LaborQueueSettlement.apply`（`:82-427`） | 既有"按利润率排队 + 依次分配劳动预算"就是 §4.3.1 的排序贪心（`netPerLaborScaled desc → rankModeKey asc → unitId asc`） | **不新造选择器**：把它升级成唯一排序器（`ActivitySelector`），套利作为一种活动与生产进同一个 `offers` 列表 |
| C8 | `EconomyData.java:854-861`（activity 不以 `unit-` 开头 ⇒ 合法，"自由家户劳动/旧档未接线档"）；`SettlementIndex.java:547-586`（非 unit activity 被 `units.containsKey` 挡掉） | **非 unit 的活动词是合法且安全的** | 套利用 `activity:arbitrage:<家户>`（不以 `unit-` 开头、不含 `.`）；无需新状态组件 |
| C9 | `EconomySettlement.java:438` `PLANTING_DRAWS_BEFORE_CONSUMPTION = true`；日序 `:1269 drawCycleInputsPartitioned → :1285 LaborQueueSettlement.apply` | 投入扣减**先于**劳动分配 | 套利快照（劳动分配时刻的可动余额）里**永远看不到**那批生产投入 ⇒ **I14 结构性成立**，不必改 `drawCycleInputs` 的时点（见 §4-D1） |
| C10 | `MarketSettlement.MarketRound`（`:341-540`）的 5 个构造器 + `withCredit`（`:579-600`） | 加字段的最省风险形制 = 既有唯一完整构造器 + 后挂字段 | 套利计划用 `withArbitrage` 注入，**不改任何构造器签名**（既有调用方与测试的编译/行为不动） |
| C11 | `simos-runs/2026-10-08-sw19-baseline/`：`service.log` 有 360 条 `LABOR_QUEUE`（86 户、budgetMilli=36,352,000、allocatedMilli=5,659,666）；dump 里 `mode:handicraft_workshop` × 38、`production-runtime` × 113 | 基线世界（small-world，production-runtime 播种）**确有 modes** ⇒ `LaborQueueSettlement` 分支是活的 | 套利只在**有 modes 的世界**被接线（`base.modes()` 非空分支），基线世界满足；无 modes 的旧档/夹具走 `reallocateLaborPartitioned`（那里没有逐户利润队列，套利无队可入）——**如实记**（见 §6-U1） |
| C12 | 基线价表：grain=1、cloth=5、fiber=1、tool=9、iron=9（毫/商品单位）；`Market.BID_PER_MILLE=990/ASK_PER_MILLE=1010`（`:138/:145`） | 粮价 1 毫时 `bid=1`、`ask=2`（整数网格退化），**任何 ≤1000‰ 的压力在"毫"网格上都会被整除抹平** | 保留价/价差内部一律用**微**（1 毫 = 1000 微）算，只在对外契约处按毫回答（见 §3-K1） |
| C13 | `LaborQueueBook.offer`（`:255-282`）：`outputValue = Σ perScale × bid`（perScale 是**商品单位/规模**）、`inputCost = Σ perScale × ask ÷ 1000`（perScale 是**毫商品/规模**） | 生产的排序键量纲是"毫钱 ÷ 毫小时 × 1e6"，量级 ≈ 1e5（farm：`(34+6)×1 − 8000×2/1000 = 24` 毫钱 ÷ 143 毫小时 ⇒ 167,832） | 套利收益率必须落在同一把尺上（见 §3-K2 与探针读数） |

## 2. 实现架构（内部拆分 / 类与方法设计 / 真实数据流与调用次序）

### 2.1 新增（全部在 `io.mosire.simos.economy.time`，逐日瞬态、不进任何状态组件）

| 文件 | 角色 |
|---|---|
| `HouseholdResourceSnapshot.java` | **§4.3.5 的"同一份资源快照"**：`(day, household, market, goods, money, laborMilli)`；保序冻结；`pricedCommodities()` 按商品 id 升序（禁 HashMap 迭代序） |
| `HouseholdActivity.java` | **§4.3.4 的活动契约**：`activityId() / kind() / yieldScaled(快照) / resourceNeed(快照) / execute(分配结果) → Intent`；嵌套 `Kind / Direction / Need / Allocation / Intent / Trade`。★ `execute` 只交回**声明式意向**，不改账户（铁律 2） |
| `ActivitySelector.java` | **§4.3.4 的唯一排序器**：`RankKey(yieldScaled, activityId, tieId)` + `compareKeys` + `rank(...)`；`PER_LABOR_SCALE = 1_000_000` |
| `HouseholdValuationBook.java` | **§3.3 家户价目表（逐 tick 派生、不进状态）**：实现既有 `DebtValuation.HouseholdPriceTable`（形状复用、不新造类型）；内部以**微**存保留价，`priceOf` = 微 ÷ 1000；来源 1（`expectedNeedMilli(c, 30)` 的短缺压力）+ 来源 2（饱和衰减）+ 来源 3 的**可注入口** `TradeHistory`（默认 `NO_TRADE_HISTORY`） |
| `TradeArbitrageActivity.java` | **套利作为一种活动**（§4.1/§4.2/§4.3.3）：`evaluate(户, 行, 价目表, 快照)` → `Evaluation(Optional<活动>, 具名 reason)`；四个上限常量、微刻度价差、闭式深度解、`activityKeyOf(户)` / `isArbitrageActivity` |
| `MarketArbitragePlan.java` | **排序 → 市场阶段的唯一传导物**：`Instruction(户, 方向, 商品, 量, 保留价, 市价, 单位价差, 劳动)` + `Collector` + `of(...)`（同一 (户,商品) 重复 ⇒ 具名抛） |

### 2.2 改动

| 文件 | 改了什么 |
|---|---|
| `EconomyLogSource.java` | 新增来源 `ECONOMY_ARBITRAGE("economy-arbitrage", …)`（AGENTS §一.9 的来源表纪律） |
| `LaborQueueBook.java` | `offerOrder()` 与 `plan()` 改为**委托唯一排序器**（`ActivitySelector.compareKeys` / `ActivitySelector.rank`），新增 `rankKeyOf(Offer)` 投影。**排序语义逐值不变**（`netPerLaborScaled desc → rankModeKey asc → unitId asc`） |
| `LaborQueueSettlement.java` | 新增重载 `apply(..., AccountSession accounts, MarketArbitragePlan.Collector)`（旧签名保留并委托，行为只有一份）；**阶段 0** 建快照 + 派生逐户价目表；**阶段 1** 逐户把套利 `Offer` 加进同一个 `offers` 列表；**阶段 3** 按 granted 执行套利活动并把 `Trade` 意向进收集器；`applyPlan` 支持套利活动键（`HouseholdActors.of(户)` 收劳动、`HouseholdLaborCommitment.idOf` 拼 id）；`removeStaleArbitrageCommitments` 清理无生产候选户的残留套利行；三处日志 |
| `MarketSettlement.java` | `MarketRound` 新增逐轮字段 `arbitrage` + `arbitrage()` + `withArbitrage(plan)`（不改构造器签名）；`ordersFor` 在既有买量之上叠加套利量（TRACE 一条/单） |
| `EconomySettlement.java` | 日序接线：投入/劳动阶段建收集器并把 `accounts` 传进队列；市场轮之前 `arbitrageCollector.toPlan()` → `marketRound.withArbitrage(...)`；新增 `logArbitrageRound`（INFO 汇总 + DEBUG 逐条） |

### 2.3 真实数据流与调用次序（一个世界日的相关片段）

```
deliverShipments(到货)                                     EconomySettlement :1240
drawCycleInputsPartitioned(周期投入扣减)                    :1269   ← 默认预设下在劳动分配之前
└─ LaborQueueSettlement.apply(..., accounts, collector)     :1285/:1318
   ├─ 阶段 0  buildArbitrageSnapshots(可动余额 = 余额−冻结；市场 = 居住格价表)
   │          HouseholdValuationBook.derive(逐户保留价；微刻度)
   ├─ 阶段 1  逐户：生产 offers（既有口径）＋ arbitrageOffer（§4.3.4：同一个列表）
   │          evaluate(户, 行, 价目表, 快照) → Opportunity(方向/商品/量/劳动/收益)
   │          LaborQueueBook.plan → ActivitySelector.rank（收益率 desc → 活动键 asc）
   ├─ 阶段 2  逐 unit 全局封顶（套利活动键逐户唯一 ⇒ 不被别的户封顶）
   └─ 阶段 3  写回配额（套利行 = PRODUCTION 类、actor = HouseholdActors.of(户)）
              execute(granted) → Trade 意向 → collector
consumeOwnStockPartitioned(吃饭)                            :1301
harvestPartitioned(收获)                                    :1524
capitalizeArrears                                           :1591
if (marketTrigger != NONE)                                  :1702
   ├─ marketRound.withCredit(...)                           :1705
   ├─ arbitrageCollector.toPlan() → marketRound.withArbitrage(...)   ← 唯一传导点
   ├─ logArbitrageRound(INFO/DEBUG)                                  ← 谁/方向/量/为什么
   └─ MarketSettlement.clearOncePerCycle
        └─ ordersFor(hex, commodity, 参与者)
             ├─ 既有口径：sellable / buyTarget（**一份都不改** —— I15）
             └─ 套利买盘：quantity += instruction.quantityMilli()（TRACE 一条/单）
        └─ matchGroup / executeTrade / applyTransfer（**唯一写口，零改动**）
```

★ **落点符合设计书 §9**："市场轮之前的套利计划阶段"（`clearOncePerCycle` 之前、`deliverShipments` 之后）。

## 3. 关键判断（为什么这样拆 / 推翻过什么）

- **K1：保留价与价差一律用"微"刻度（1 毫 = 1000 微）**。理由见 C12：真档粮价 = 1 毫，压力项在毫网格上
  `1 + 1×500÷1000 = 1` ⇒ 价差恒 0 ⇒ **套利永远是死分支**。对外契约（`HouseholdPriceTable`，量纲 = 毫）与
  `TradeArbitrageActivity`（微）**同一份存储派生**：`priceOf = reservationMicroOf ÷ 1000`，不是两把尺。
- **K2：收益率的归一化 = "朴素版 + 劳动归一"**（§4.3.3 允许实现方选，并**要求写明**）：`净收益 ÷ 该活动消耗的劳动`，
  与 `LaborQueueBook` 的排序键**同一把尺**（`×1_000_000`）。★ 且分子分母都用**微**（`netMicro × 1e6 ÷ laborMicro`）：
  若用毫，小量交易（750 毫商品）折成毫小时 floor 到 0 ⇒ 钳到 1 ⇒ 收益率被**凭空放大 1000 倍**（探针实测：布的小额
  机会压过粮的大额机会）。微刻度比值与毫刻度真实比值恒等 ⇒ 只补精度，不是第二把尺。
- **K3：HOLD（留货不卖）分支本阶段不评估，并给出证明**。`sellable` 已经吃掉 `[生活保留, 生活保留+必要投入+需求目标]`
  之外的全部余量（C5）；套利既不得侵占必要投入（I14）也不得侵占生活保留（I15）⇒ 能"留"的量只落在
  `sellable` **之外** ⇒ 等价于不产生任何变更。方向上更激进的做法（把安全库存卖到更低水位）在基线（价格恒定、
  每轮都触发）会把粮价与口粮供给一起打崩 ⇒ 违 E3，故本阶段**不做**。方向词与落账路径保留（阶段 2 的外汇/跨区价差要用）。
- **K4：选"做哪一笔"看绝对收益（§4.1 ②"取收益最大的那一环"），"谁先分到资源"看收益率（§4.3.1 ③）**。
  这两个判据在设计书里本来就是两步；混用会让家户去做高价商品上的小买卖。探针实测：布（价 5 毫、30 天目标 3000 毫）
  净收益 1 毫钱 vs 粮（价 1 毫、30 天目标 42 万毫）净收益 51 毫钱；按收益率选会选布 ⇒ 360 tick 读数上等于没做。
- **K5：套利活动键逐户唯一（`activity:arbitrage:<户>`），不是一个全局常量**。理由：`LaborQueueSettlement` 阶段 2
  按 `Offer.unitId` 做"逐 unit 全局封顶"；全局共用一个键会让全部家户被误当成**一条共享生产活动**，
  用"某一户的可吸收量"去封顶全体（静默错）。逐户一个键 ⇒ 语义与"每户每轮至多一次"一致。
- **K6：不改 `MarketRound` 的任何构造器签名，用 `withArbitrage` 后挂字段**（C10）。既有调用方/测试的**编译**不动，
  只读计划轮与读口自然拿到 `empty()` ⇒ 逐值退回改前口径。
- **K7：不改 `drawCycleInputs` 的时点，也不改 `rationContestedInputs`**。默认预设下投入扣减先于劳动分配（C9）
  ⇒ 套利快照看不到那批料、I14 结构性成立；`rationContestedInputs` 的争抢结果因此**不变**。
  ★ 这一点**偏离**设计书 §9 末段的"投入扣减后移"与用户裁定 4 的机械落实（见 §4-D1），理由与数值结论写在 D1。
- **K8：推翻过两条路线**：
  ① "另写一套跨格成交/搬运" → 被 C2/C3 否掉（无跨格价差；且派单硬约束禁止）；
  ② "把套利做成**只吃剩余现金**（订单层二次封顶）" → 探针与量纲分析显示：真档现金相对货值极贫瘠，且既有买盘的
     尾段本来就由 `creditRound` 结 ⇒ 订单层再封一道会让套利**恒为 0**（静默死分支）。改为**计划期**用
     `MONEY_CAP_PER_MILLE‰ 可动现金` 封规模（"自有资源"约束落在计划侧），订单侧不再封（见 §4-D3）。
- **K9：日志三级**（AGENTS §一.9）：INFO = `ARBITRAGE_ROUND`（几户/几笔买/总量/总劳动/预期净收益/涉及商品）；
  DEBUG = `ARBITRAGE_OPPORTUNITY`（逐户：方向/商品/量/保留价/市价/价差/净收益/劳动/收益率/短缺与饱和‰）、
  `ARBITRAGE_GRANTED`（排序位次/收益率/实配/结果）、`ARBITRAGE_INSTRUCTION`（进市场的那一条）、
  `LABOR_QUEUE_ARBITRAGE_STALE_REMOVED`；TRACE = `ARBITRAGE_BUY_ORDER`（逐单：量/保留价/市价/价差/参考价）、
  `ARBITRAGE_NO_OPPORTUNITY`（具名 reason）。

## 4. 偏离约束设计书之处及原因（主动记录）

- **D1（最重要）"生产投入扣减改为按分配结果扣"（§12 阶段 1 第 4 条 / 用户裁定 4 / §9 末段）未做机械的时点后移。**
  - 事实：现行日序 `:1269 drawCycleInputsPartitioned → :1285 劳动分配`（`PLANTING_DRAWS_BEFORE_CONSUMPTION = true`，C9）。
  - 为什么不动：`LaborQueueBook.offer` 的"最大可吸收劳动"第二路**就是**已扣进 unit 的 `cycleInputUsedMilli`
    （`maxAbsorbableLaborMilli` 的 `inputScale`）；把投入扣减压到劳动分配之后，这一路就没有输入可读 ⇒
    排序键与规模上限都会失去依据（要么凭空开工、要么全格规模归零）。真要后移，得同时重写 `maxAbsorbable` 的
    投入口径与 `drawCycleInputs` 的三遍式争用（`rationContestedInputs` 的池子/配给），属**另一个责任区**。
  - 数值后果（用户裁定 4 关心的正是这一条）：`rationContestedInputs` 的争抢结果**完全不变**
    （投入仍先扣，池子与配额与改前逐值相同）⇒ 本阶段**没有**引入该处数值漂移。套利看到的是扣完料的余额。
  - 保留 I14：套利只加买、从不加卖；快照取自投入扣减之后 ⇒ 生产投入批次不被侵占（**结构性**成立，不靠断言）。
- **D2（设计书内部不一致，如实记）**：设计书 §7.0 与 §12 阶段 1 第 3 条已于 2026-10-08 改成"保留价 vs 市价"，
  但 §12 末尾的判据表（`:594-600`）**仍是旧文本**（E1 写"同商品在不同格的价差"、E4 写"把价差抹平"）。
  我按 §7.0 + 控制方口径更正实现；该表**未被我这方修改**（`docs/**` 属禁写区）。
- **D3：套利买盘在结算上与既有买盘共用同一条订单（不可避免）**。既有结构下**无法**给单条订单关闭信用通道
  （`BuyOrder` 无该字段，且 `creditRound` 对全部买槽生效；`BuyOrder` 在契约层，不在本责任区可写范围内）。
  ⇒ 取舍：规模上限放在**计划期**（`OPPORTUNITY_CAP_PER_MILLE = 250‰ × 30 天目标保有量` 与
  `MONEY_CAP_PER_MILLE = 200‰ × 可动现金` 取小），订单侧不再二次封顶。
  ★ **数值风险（点名）**：套利订单若被撮合且由信用结，会增加（或重新分配）债务；量级分析见 §5-A，360 tick 读数要盯
  `debtPrincipal`（基线 tick360 = 100,529,398）。
- **D4：`HouseholdValuationBook` 实现了 `DebtValuation.HouseholdPriceTable`，但**没有**把它接进债务偿还路径**
  （`DebtValuation.choosePayment` 仍收到 `null`）。理由：接进去会改变**债务折偿**的估值口径（阶段 1 之外的数值面），
  且 §3.3 只要求它作为**活动输入**一等化。实现接口是为了"形状复用、同一个类型"（后续批次可直接喂进去）。
- **D5：§3.3 第 3 条来源（真实成交校准）是可注入口 + 默认关闭**。跨 tick 成交史 = 一个**状态组件**（Codec + 往返不变式），
  §3.3 P2 明令第一版不许把派生量塞进状态 ⇒ 默认 `NO_TRADE_HISTORY`（逐值确定）。要接就得另提状态组件方案。
- **D6：§4.3.3 的"边际递减"用**平均收益率**口径 + 跨轮收敛**（设计书明确要求"必须在账本里说明为什么不递减也能收敛"）：
  ① 价格冲击按选定量 `q` 摊薄（`DEPTH_IMPACT_PER_MILLE = 50‰`，与 `MarketSettlement.MARKET_ADAPTIVE_ALPHA_PER_MILLE`
  同值），故 `q` 越大平均价差越低 —— 轮内确有下降，但**不是**绑定约束（见下）；
  ② 绑定约束是**三个上限**（机会上限 / 现金上限 / 价差门槛）；
  ③ 收敛靠**跨轮价格上行**：套利买盘进 `ordersFor` 的订单量 ⇒ 进 `adaptPrices` 的需求 ⇒ 下一轮区价上行 ⇒ 价差被侵蚀
  ⇒ 不需要"轮内截断点求解"（用户 §1.6 明确否掉了截断点求解）。
  ★ 可证的边界：`minEdge (=100‰) ≥ DEPTH_IMPACT (=50‰)` ⇒ `depthQuantityLimit` 在本阶段常量下恒返回
  `Long.MAX_VALUE`（深度不绑定）—— 这是我**刻意**选的（宁可用可读的机会上限，也不用一个编造的"订单簿深度"）。
- **D7：套利只接线在 `base.modes()` 非空的分支**（有逐户利润队列的世界）。无 modes 的旧档/夹具走
  `reallocateLaborPartitioned`（那里没有逐户利润队列 ⇒ 套利无队可入）⇒ 那些世界**行为逐值不变**。
- **D8：`HOLD` 方向词保留在契约里但本阶段不产生**（见 K3）。`Direction.HOLD` 仍被
  `EconomySettlement.logArbitrageRound` 的分类统计引用（不是未使用常量）。
- **D9（2026-10-08 返工修正，见 §5-C-2）：克隆路径的"手抄字段"全部收口到
  `MarketRound.copyForWorker` 唯一拼写点**；`readOnlyPlanningRound` 与区副本 `localRound` 不再逐字段手抄。
  随之带来三处**行为等价**的取值变化（`regulation` 由写死 `none()` 改为原样带过；区副本的
  `creditConfig`/`debts`/`marketExcludedHouseholds` 由缺省改为原样带过），依据是上面的调用点审计
  （三者在克隆轮上**都没有读取点**）。★ 这是**刻意**选择"统一带过"而不是"逐点覆盖" —— 逐点覆盖等于保留
  "新字段会被静默丢掉"的结构，正是本次事故的成因。

## 5. 报告模板（派单要求的六项）

### 5-A. 改动文件清单

**新增 6 个**（均在 `simos-economy/src/main/java/io/mosire/simos/economy/time/`）：
`ActivitySelector.java`、`HouseholdActivity.java`、`HouseholdResourceSnapshot.java`、
`HouseholdValuationBook.java`、`MarketArbitragePlan.java`、`TradeArbitrageActivity.java`。

**改动 5 个**（`git diff --stat`）：
`simos-economy/.../economy/EconomyLogSource.java`（+4/−1）、`.../economy/time/EconomySettlement.java`（+121）、
`.../economy/time/LaborQueueBook.java`（+28/−4）、`.../economy/time/LaborQueueSettlement.java`（+402/−4）、
`.../economy/time/MarketSettlement.java`（+96）。
合计 5 files changed, 636 insertions(+), 15 deletions(-)（未计新增 6 文件）。

★ 未碰：任何 `src/test/**`、任何 `pom.xml`、`docs/**`、`.superpowers/**`（除本账本）、任何其他人的文件。

### 5-B. 编译实际命令与结果

```
pgrep -af classworlds.launcher        # 本仓无 Maven 在跑（先确认，AGENTS §一.1）
./mvnw -q -o -DskipTests compile -pl simos-app -am
[compile exit=0]
```
- **未跑** `test` / `verify` / `package`（派单明令；本机有基线服务占用 shaded jar）。
- 途中跑过 `./mvnw -q -o spotless:apply -pl simos-economy,simos-app`（格式化，不产 jar）。
- 迭代期间用 `-pl simos-economy -am`（同样只 compile）。
- ★ **未跑** Checkstyle / SpotBugs / 前端门禁 / 任何测试 —— **按派单，这些留给后续测试代理**。

**一次性数值探针（不进仓库、不进 `src/test`）**：`/tmp/arbprobe/.../ArbProbe.java`，直接调包内
`HouseholdValuationBook.derive` 与 `TradeArbitrageActivity.evaluate`，读数（真档量级：粮价 1 毫、目标 30 天 422,100 毫、
现金 4,800 毫、时间预算 422,000 毫小时）：

| 库存（毫粮） | 保留价（微/毫） | 命中 | 量 | 劳动 | 收益率 | 判据 |
|---|---|---|---|---|---|---|
| 0 | 1500 / 1.5 | grain BUY | 105,525 毫（=105.5 单位） | 105 毫小时 | 489,997 | 短缺 1000‰ |
| 56,430 | 1433 / 1.433 | grain BUY | 105,525 | 105 | 422,999 | 短缺 866‰ |
| 200,000 | 1263 / 1.263 | grain BUY | 105,525 | 105 | 252,992 | 短缺 526‰ |
| 422,100（=目标） | 1000 / 1.0 | cloth BUY | 750 毫（=0.75 单位） | 1 | 2,029,333 | 粮食无价差 ⇒ 换布 |
| 1,000,000 | 600 / 0.6 | cloth BUY | 750 | 1 | 2,029,333 | 粮饱和 ⇒ 换布 |

★ 与既有生产收益率对比（`LaborQueueBook` 口径，farm：`(34+6)×1 − 8000×2÷1000 = 24` 毫钱 ÷ 143 毫小时 × 1e6）：
**167,832** ⇒ 强短缺户的套利（≈2.5e5–4.9e5）排在生产之前、轻度短缺户排在其后 —— **E2 的"套利排在队首、生产排其后"
因此是可观测的**，且套利劳动量（105 毫小时 vs 户预算 422,000）**远不到吃光劳动**（E2 后半条、E3）。

### 5-C. 会改变数值行为的清单（给测试代理当输入）

| # | 变化 | 影响面 | 量级（估算/实测） |
|---|---|---|---|
| N1 | **家户买订单量增加**：`ordersFor` 在"目标缺口 + 需求分段"之上加套利量 | 每 5 天一开的**市场轮**；商品市场；CLOTH/GRAIN 等 | 单户单轮 ≤ `25% × 30 天目标保有量`（粮：实测 105,525 毫粮），且 ≤ `20% × 可动现金` 买得起量 |
| N2 | **市场价表（自适应定价）随 N1 变动**：套利量进 `adaptPrices` 的需求 | 全部 hex 的 `Market.prices`（区价） | 0–5%/轮（`MARKET_ADAPTIVE_ALPHA_PER_MILLE = 50` 不变，只是供需比变了） |
| N3 | **新增一条劳动配额行**：`activity:arbitrage:<户>`、`kind = PRODUCTION`、`actor = HouseholdActors.of(户)`、`laborMilli = max(1, ⌊量÷1000⌋)` | `allocations` 表（既有组件，Codec/ChangeSet 不变）；`SettlementIndex.laborByUnit` 多一个键 | 每户每日 0–105 毫小时（户预算 422,000）⇒ ≤0.03% |
| N4 | **生产分到的劳动可能变少**（套利收益率更高时排在前面） | 生产 unit 的 `cycleLaborMilli` ⇒ 收获规模 | ≤105 毫小时/户/日（同上）；E3 要盯总产出 |
| N5 | **债务**：套利订单若由信用结，会改变 `DebtContract` 本金（新增或重新分配） | `debtContracts` 表；`MarketReport.creditFills` | 见 D3 的风险点名；360 tick 要与基线 100,529,398 对比并区分"本就如此" |
| N6 | **`LaborQueueBook.offerOrder()` 的实现换成委托**（语义逐值不变，但不再是"另一份比较器"） | 仅排序实现路径 | 0（`netPerLaborScaled desc → rankModeKey asc → unitId asc` 逐字保留） |
| N7 | 无 modes 的世界：**零变化**（收集器恒空 ⇒ `MarketArbitragePlan.empty()`） | `reallocateLaborPartitioned` 路径 | 0 |
| N8 | 只读计划轮/读口（`MarketSettlement.planOrders`）**零变化**（它们的 `MarketRound` 不注入套利） | 读口 | 0 |

### 5-C-2. ★★ 2026-10-08 验收返工：克隆静默丢字段（已修）

**症状（控制方实测，证据 `/home/cna/simos-runs/2026-10-08-sw19-arb/`）**：新代码 360 tick 与基线**逐值相同**
（12 段 summary + 12 个 dump 字节级一致）；TRACE 全开 30 tick 里 `ARBITRAGE_OPPORTUNITY`/`ARBITRAGE_GRANTED` 各 1977 条、
`ARBITRAGE_ROUND` 有 83 户 / 1,814,400 毫，而 **`ARBITRAGE_BUY_ORDER` = 0 条**。

**根因（一行）**：`MarketRound.arbitrage` 只被 `withArbitrage(...)` 赋过值；而**真正下单用的轮是克隆出来的**，
两条克隆路径各自**手抄**一遍构造参数 ⇒ 新字段被静默丢掉 ⇒ 克隆轮里 `arbitrage` 恒为 `empty()`、
`instructionFor` 永远返回 empty。克隆点：
`readOnlyPlanningRound`（**worker 的只读计划轮 = `ordersFor` 实际用的那一份**）与 `prepareRegion` 的 `localRound`。

**修法（控制方倾向的 ② + 一条判别守卫，两条都做）**：

1. **克隆的唯一拼写点** `MarketRound.copyForWorker(4 张账户表, unmetToday, 账本)` —— 逐字段原样带过本轮的**其余全部
   输入**（含 `regulation` / `creditConfig` / `debts` / `marketExcludedHouseholds` / `index` / **`arbitrage`**），
   只替换调用方显式点名的六样。两个克隆点各减 ~20 行手抄，改调这一个方法 ⇒ **将来再加 `MarketRound` 字段不必改克隆点**。
2. **防复发守卫**（契约/一致性故障不降级，AGENTS §一.9）：`clearOncePerCycle` 在拿到 `planningRound` 后，
   **断言克隆轮与母轮携带同一份套利决定**（比较 `instructions()`，record 结构相等）；不等 ⇒
   具名 `MARKET_ARBITRAGE_PLAN_LOST` **ERROR + `IllegalStateException` fail-closed**（错误文案直接点名
   "克隆必须走 `MarketRound.copyForWorker`"）。另加一条 `MARKET_ARBITRAGE_PLAN_ATTACHED` DEBUG（母轮/克隆轮户数），
   让"计划已挂载"可 grep、与逐单 TRACE 对得上。
3. `withArbitrage` 形制未变（既有无构造器签名改动）。

**自查：克隆点 × 字段（控制方要求的第 2 项）**

`MarketRound` 共 **23 个字段**；脚本逐字段核对 `copyForWorker` 覆盖 = **23/23**（`ledger` 由参数名 `workerLedger` 带入）。
`new MarketRound(` 全仓只剩 **3 处、全在 `MarketRound` 类体内**（`:582` 短构造器委托、`:642` `withArbitrage`、
`:688` `copyForWorker`）⇒ **类外再无手抄克隆**。

| 字段 | 修复前 `readOnlyPlanningRound` | 修复前 `localRound` | 修复后（两处同一方法） |
|---|---|---|---|
| 4 张账户表 + `unmetToday` + `ledger` | 浅拷/换新（**故意不同**） | 只含本区/换新（**故意不同**） | 显式替换（**故意不同**，参数点名） |
| **`arbitrage`** | ❌ **静默丢**（就是本 bug） | ❌ **静默丢** | ✅ 原样带过 |
| `regulation` | ❌ 写死 `none()` | ❌ 缺省 `none()` | ✅ 原样带过（**已审计**：克隆轮无读取点） |
| `creditConfig` / `debts` | ✅ 已带过 | ❌ 缺省 `null`（`creditEnabled=false`） | ✅ 原样带过（**已审计**：克隆轮无读取点） |
| `marketExcludedHouseholds` | ✅ 已带过 | ❌ 缺省空集 | ✅ 原样带过（**已审计**：克隆轮无读取点） |
| 其余 13 个（day/householdEconomies/householdOfActor/industries/units/assetShares/relations/laborCommitments/shipments/operatorConditions/index/householdDemands…） | ✅ 逐字带过 | ✅ 逐字带过 | ✅ 原样带过 |

**"克隆轮无读取点"的调用图证据**（这是"原样带过 = 行为等价"的依据）：
- `round.regulation()`：唯一读取点 `planOrders(round,...)`（`:826`，只被读口 `MarketReadout` 用真实轮调用）
  与 `MatchContext` 的 6 参构造器（`:5489`，只被协调器的真实轮使用 `:957`）；区副本的 `MatchContext` 走 7 参构造器
  并**显式传** `ctx.regulationFor(regionId)`（`:2740` 附近）。
- `creditEnabled()`：`creditRound`（`:1089` 调用）、`collectUnfilled→creditUnfilledReason`（`:1092`/`:4191`）**全部只在协调器的真实 `ctx` 上跑**；
  worker 只跑 `matchGroup`，其 `affordableQuantity`/`payableMoneyOf` **不读信用**。
- `marketExcludedHouseholds()`：3 个读取点全在**订单/参与者生成**（`:1436`/`:4835`/`:4936`）⇒ 走 planning round（改前就已带过）。

**交付前自证（控制方硬要求：不许再让"静默空计划"过去）**
一次性探针 `/tmp/arbprobe2/.../CloneProbe.java`（不进仓库、不进 `src/test`；用反射调**真实的私有** `readOnlyPlanningRound`）：

```
母轮无计划 买量 = 492450        ← 35 天生活保留缺口
母轮有计划 买量 = 542450        ← +50,000（指令量）
克隆轮(有计划) 携带计划户数 = 1 ; instructionFor 命中 = true
克隆轮(无计划) 携带计划户数 = 0
克隆轮(有计划) 买量 = 542450    ← ★ 修复前这一条是 492450（= 与基线逐值相同的根因）
克隆轮(无计划) 买量 = 492450
PROBE-PASS 克隆路径带着套利指令，ordersFor 读到了
变异后（克隆丢计划）买量 = 492450（应回到 492450 = 修复前症状）
变异后守卫判据（应为 true ⇒ 守卫会 ERROR + fail-closed）= true
GUARD-MUTATION-PASS 守卫有判别力（抹掉克隆字段 ⇒ 读数回到旧值且守卫判真）
```
⇒ ① 克隆路径确实把指令带到了 `ordersFor`（量 +50,000 逐值可验）；② **变异自证**：把克隆轮的 `arbitrage`
抹回 `empty()`（= 复现修复前那个坏世界）⇒ 读数回到 492,450 **且**新守卫判真 ⇒ 守卫具备判别力（不是装饰）。

**返工后编译**：
```
./mvnw -q -o spotless:apply -pl simos-economy     # [exit=0]
./mvnw -q -o -DskipTests compile -pl simos-app -am # [compile exit=0]
```
`git diff --stat`：MarketSettlement 由 +96 变 **+244/−49**（本次返工净增约 +148 行，含守卫与审计注释）。

### 5-C-3. ★ 2026-10-08 缺陷 2 修复：日志价格字段量纲名不符实 + floor 掩盖（task-9）

**事实（控制方定位，我已复核）**：`Opportunity.reservationPrice()/marketPrice()` 返回**微**、`edgePerUnit()` 返回**毫**，
三者在日志里都叫 `…Milli`；`Math.max(1L, …)` 地板把"0.6 毫 / 0.49 毫"显示成 **1**（看起来像零价差）；
`EconomySettlement:7735` 是"毫商品 × 毫/单位 = 毫²"却累进 `totalNetMicro` 并当 `totalExpectedNetMilli` 打出。

**量纲修法（统一到"微"，字段名自带刻度）**

| 位置 | 改前 | 改后 |
|---|---|---|
| `TradeArbitrageActivity.Opportunity` | `reservationMilli()` = `max(1, micro÷1000)`、`marketMilli()` = 同款、`edgeMilli()` = 同款 | **删掉前两个**（直接用记录的微分量）；`marketMilli()` → **`marketPriceMilli()` 去地板**并附"整除无损"证明（`marketMicro` 恒 = 参考价×1000）；`edgeMilli` 删除（改用 `edgeMicro`）；`netMilli()` **去地板**（可证死：evaluate 已具名跳过 `netMicro < 1000`） |
| `HouseholdActivity.Trade` | 组件 `reservationPrice/marketPrice/edgePerUnit`（毫，且靠地板满足 `>0`） | 组件改名 **`reservationMicro/marketMicro/edgeMicro`**（微；三者天然 > 0 ⇒ `>0` 不变式不再需要任何地板）；新增记录级量纲 javadoc |
| `MarketArbitragePlan.Instruction` | `reservationPrice/marketPrice/edgePerUnit` | **`reservationMicro/marketMicro/edgeMicro`** + 记录级量纲注释（毫/微分开写明） |
| `LaborQueueSettlement`（`ARBITRAGE_OPPORTUNITY` DEBUG） | 字段名 `reservationMilli/marketMilli/edgeMilli` | **`reservationMicro/marketMicro/edgeMicro`** |
| `MarketSettlement`（`ARBITRAGE_BUY_ORDER` TRACE） | 同上 | 同上 |
| `EconomySettlement`（`ARBITRAGE_INSTRUCTION` DEBUG + 轮级累加） | 同上；`totalNetMicro += 量(毫商品) × 毫/单位` | 同上；**`totalNetMicro += 量(毫商品) × edgeMicro(微/单位) ÷ 1000(毫商品/单位)` = 微 numeraire** ⇒ 变量名与算式同名同尺；`totalExpectedNetMilli = totalNetMicro ÷ MICRO_PER_MILLI`（**不是** `MILLI_PER_GRAIN` —— 两者数值同为 1000 但量纲完全不同，混用正是本缺陷的类型） |

**"行为逐值不变"的证据（两层）**

1. **静态：三个价格字段只进日志。** 逐点核对（`grep` 全仓）：
   - 只读消费它们的只有 → `LaborQueueSettlement` 的 `Trade→Instruction` 搬运、`MarketSettlement.ordersFor` 的 TRACE、
     `EconomySettlement.logArbitrageRound` 的 DEBUG 与轮级累加；
   - 订单侧只取 `instruction.quantityMilli()`（`MarketSettlement.ordersFor`）；判定侧只取 `quantityMilli/laborMilli/netMilli/netPerLaborScaled`；
   - 唯一两处**算式**用到的毫刻度是 `resourceNeed()` 与 `execute()` 的现金换算，它们改用的是新 `marketPriceMilli()`
     （**整除无损**：`marketMicro = 参考价×1000`，参考价 ≥ 1 ⇒ 无余数、无地板）⇒ 逐值相同；
   - `netMilli()` 去地板：可证死（evaluate 构造 Opportunity 之前已具名跳过 `netMicro < 1000`）⇒ 逐值相同。
2. **探针：改名前后逐值对拍。** `/tmp/dimsprobe/.../DimsProbe`（不进仓库/不进 `src/test`）打印 5 个库存场景下
   `Opportunity` 全部读数 + `resourceNeed().moneyMilli` + `execute()` 的成交量：
   **`diff before.txt after.txt` = 空**（字节相同）⇒ 活动对外读数与两个算式结果**逐值不变**。
   （对拍基线 = 改前代码的输出，含 grain 场景 `q=105525 / reservationMicro=1500 / edgeMicro=490 / netMilli=51 /
   yieldScaled=489997 / need.money=105 / execQ=35000`。）
   ★ `CloneProbe` 复跑仍 `PROBE-PASS` + `GUARD-MUTATION-PASS`（上轮修复未被破坏）。

**日志侧的真值证据（`/tmp/logprobe/.../LogProbe`，slf4j-simple 真发射）**

```
DEBUG ... ARBITRAGE_OPPORTUNITY ... quantityMilli=105525 reservationMicro=1500 marketMicro=1000 edgeMicro=490 netMilli=51 ...
INFO  ... ARBITRAGE_ROUND       ... totalQuantityMilli=316575 totalLaborMilli=315 totalExpectedNetMilli=123 commodities=grain
DEBUG ... ARBITRAGE_INSTRUCTION ... reservationMicro=1500 marketMicro=1000 edgeMicro=490 ...
TRACE ... ARBITRAGE_BUY_ORDER   ... orderQuantityMilli=542450 arbitrageQuantityMilli=50000 reservationMicro=1500 marketMicro=1000 edgeMicro=490
[probe] 真值 Σ(量×微价差)÷1000÷1000 = 123 毫
[probe] 旧式（毫×毫）Σ(量×max(1,微价差÷1000))÷1000 = 316 毫  ← 修复前的假报值
```
⇒ ① 旧日志把"1.5 毫 / 1.0 毫 / 0.49 毫"显示成 `1/1/1`（看起来零价差），新日志显示 `1500/1000/490` 微；
② 轮级 `totalExpectedNetMilli` 从**假报 316** 变成**真值 123**（= 逐户 `netMilli` 之和，量纲自洽）。

**★★ 探针当场抓到我在本次改名中引入的一个新真错（值得记成纪律）**：把
`"reservationMilli", <value>` 改成 `"reservationMicro", <value>` 时我留下了**悬空的旧键**
⇒ `LogEvent.of` 收到 **奇数** 个 key/value ⇒ **运行时抛** `IllegalArgumentException`（编译期查不出来！）。
它只在**日志真正发射**时炸 ⇒ 谁开 `-Dsimos.economy.logLevel=DEBUG` 谁整个结算日崩。
`LogProbe`（带 slf4j-simple binding 把四条发射路径都真跑一遍）当场抓到并已修（账本 §6 记）。
★ 纪律推论：**"日志改动编译过"完全不等于"日志行合法"** —— 校验在运行时，必须用带 binding 的探针
把每条受影响的发射路径至少真跑一次（本次四条：`ARBITRAGE_OPPORTUNITY` / `ARBITRAGE_ROUND` /
`ARBITRAGE_INSTRUCTION` / `ARBITRAGE_BUY_ORDER`）。

**本缺陷修复对 360 tick 读数的影响（给控制方的独立复核口径）**：**世界状态读数应当逐值不变**
（summary/dump 与 post-fix 卷逐值相同），**只有日志文本变**：`ARBITRAGE_*` 三类事件里
`reservationMicro/marketMicro/edgeMicro` 改名 + 数值改为真实微刻度、`totalExpectedNetMilli` 由假报变真值。
⇒ 控制方"两卷读数逐值相同"的预期只对**世界状态**成立；**日志字段名与那一个汇总值会变（这正是修复目标）**。

### 5-D. 受影响的硬编码字面量（新增具名常量，全部可调）

| 常量 | 值 | 位置 | 依据 |
|---|---|---|---|
| `HouseholdValuationBook.HOLD_DAYS` | 30 | 保留价的目标保有天数 | 与 `MarketSettlement.MARKET_SAFETY_STOCK_DAYS` 同尺 |
| `HouseholdValuationBook.NECESSITY_PREMIUM_PER_MILLE` | 500 | 完全短缺时保留价 ×1.5 | 生活保留的边际价值必须显著高于市价（防 N12"先卖再挨饿"），又不能无上限（E1"不是一次吃光"） |
| `HouseholdValuationBook.SATURATION_DISCOUNT_PER_MILLE` | 400 | 饱和时保留价 ×0.6 | "多出来的那一单位对自己不值市场价" |
| `HouseholdValuationBook.HISTORY_WEIGHT_PER_MILLE` | 300 | 成交史权重 | 默认无历史（D5）⇒ 当前不生效 |
| `HouseholdValuationBook.MICRO_PER_MILLI` | 1000 | 微刻度 | C12：毫网格会抹平价差 |
| `TradeArbitrageActivity.TRADE_LABOR_MILLI_PER_GOOD_UNIT` | 1 | 每商品单位交易劳动（毫小时） | 务农每粮 ≈4.2 毫小时 ⇒ 交易约为其 1/4，符合用户 §1.5 |
| `TradeArbitrageActivity.OPPORTUNITY_CAP_PER_MILLE` | 250 | 单轮机会上限（目标保有量的 25%） | E1"不是被某户一次吃光" |
| `TradeArbitrageActivity.MONEY_CAP_PER_MILLE` | 200 | 单轮现金上限（可动现金的 20%） | 现金要留给生产采购与还债（D3） |
| `TradeArbitrageActivity.MIN_EDGE_PER_MILLE` | 100 | 最小价差门槛（市价的 10%） | ① 毫网格噪声；② 必须高于 `Market.ASK_PER_MILLE−1000 = 10‰` 的限价带 |
| `TradeArbitrageActivity.DEPTH_IMPACT_PER_MILLE` | 50 | 价格冲击上界 | 与 `MarketSettlement.MARKET_ADAPTIVE_ALPHA_PER_MILLE` 同值（D6） |
| `TradeArbitrageActivity.ACTIVITY_ID` | `"activity:arbitrage"` | 活动词 | C8：不以 `unit-` 开头 ⇒ 合法非 unit 活动 |
| `ActivitySelector.PER_LABOR_SCALE` | 1_000_000 | 收益率刻度 | 与 `LaborQueueBook.PER_LABOR_SCALE` 同值（同一把尺） |

### 5-E. 会让既有测试断言失效的清单（★ 我没跑测试，这里是**静态推断**，供测试代理核对）

| 风险 | 会碰到什么测试 | 为什么 | 建议处置 |
|---|---|---|---|
| R1 | **`LaborQueueSettlement` 的测试**（凡断言 `allocations` 条数/内容/`Σ` 的） | 每户每日新增一条套利配额行（N3）；无机会时 grant 0 ⇒ **不留行**，有机会时留一行 | 断言里加"忽略 `activity:arbitrage:*`"或按 activity 过滤；**不许**为迁就实现改断言（AGENTS §一.8） |
| R2 | **`LaborQueueBook` 的测试**（凡断言排序/`offerOrder()`/`plan()` 决策序的） | 语义逐值不变（K/R6），但比较器实现换成委托 | 预期**不需要改**；若红应查是否我改错了语义 |
| R3 | **`MarketSettlement` 的测试**（凡用 `planOrders`/`clearOncePerCycle` + 断言买订单量/成交量的） | 只读路径与旧构造器**零变化**（K6/N8）；只有显式 `withArbitrage` 的轮才变 | 预期**不需要改**；新行为要新用例（另行派单） |
| R4 | **`EconomySettlement` 的日用结算测试**（凡断言收获/库存/价格的） | N1/N2/N4/N5：默认预设 + 有 modes 的世界里行为会变 | 需要"基线 vs 改造"的对照读数（E1–E7），不是旧断言 |
| R5 | **`EconomyData` 构造期守卫测试**（`Σ PRODUCTION ≤ 预算`、activity 悬空引用） | 套利行走 `activipty` 非 unit 分支（C8）；`Σ` 由排序器保证 ≤ 预算（`plan` 的 `remaining` 机制） | 预期**不需要改**；若出现 `alloc-activity:arbitrage:...` 撞 GOV_SERVICE 的具名抛，那是真 bug，报回来 |
| R6 | **Codec/往返不变式测试** | **无新状态组件**（套利行落在既有 `allocations` 组件） | 预期**不需要改** |
| R7 | 前端门禁 / 读口契约测试 | 未触碰读口与前端 | 预期**不需要改** |

### 5-F. 未完成 / 未验证 / BLOCKED 项

- **未跑测试、未跑 verify/gate**（派单明令）⇒ 本账本的"数值影响"与"测试影响"两节**全部是静态推断 + 一次性探针**，
  **不是**门禁结论。
- **360 tick 的 E1–E7 读数未跑**（需要停/起服务与 360 tick 真档，属控制方验收环节）。★ 若读数显示
  `ARBITRAGE_ROUND` 的 `totalQuantityMilli` 长期为 0 或 ≤0.1% 库存，**调参点**（按顺序）：
  `OPPORTUNITY_CAP_PER_MILLE` → `MIN_EDGE_PER_MILLE` → `MONEY_CAP_PER_MILLE`；**不要**改结构。
- **D1（投入扣减时点后移）未做**：本阶段按"结构性满足 I14/I15"实现，机械落实留给下一个责任区（理由与代价见 D1）。
- **HOLD 方向未做实**（K3，附证明）：阶段 2 的外汇/跨区价差需要它，届时必须**同时**给出 I15 的合规口径。
- **D8/D7 的边界**：无 modes 的世界套利不参与；纯消费户（无生产候选）仅清理残留套利行、不参与套利
  （`LaborQueueSettlement` 的 `candidates.isEmpty()` 早退）。★ 这两条是**范围边界**，不是失败。
- **BLOCKED（不是 DONE）的唯一一条**：**设计书原文的"同一市场区内买低格卖高格"在代码里不可实现**
  （C1/C2 实测：单区 + 逐格同价 + 区内成交按锚格价）⇒ 已按控制方 2026-10-08 的口径更正改为"保留价 vs 市价"，
  并**没有**另造搬货/承运机制（派单硬约束）。此条按 §一.8 三级处置上报，等控制方确认口径更正已生效。
- **未验证项（如实列）**：① 收益率的跨活动可比性只在探针量级上核过（1e5 vs 1e5），没有真档 360 tick 证据；
  ② `MIN_EDGE` 与 `DEPTH_IMPACT` 的关系（D6 的可证边界）只有算术证明，没有实测；③ 债务影响只有量级论证（N5）；
  ④ Checkstyle/SpotBugs 未跑，未知是否有新告警；⑤ `spotless:apply` 已跑但**未再跑 verify** ⇒ 不能声称格式门禁绿。
- **★ 2026-10-08 缺陷 2 修复（task-9）后仍未跑的**：① 360 tick 复跑（控制方重建 jar 后做）——
  预期"世界状态读数逐值不变、日志文本变"（见 §5-C-3 末段）；② 未跑 verify/gate/测试（同上，留给测试代理）。
- **★★ 2026-10-08 返工后仍未跑的**：① **端到端 360 tick 复跑**（必须由控制方重建 jar 后做；本次我只做到
  "编译过 + 克隆路径探针 PROBE-PASS + 守卫变异自证"）；② 真实世界里的 `ARBITRAGE_BUY_ORDER` 计数（应 > 0，
  且与 `ARBITRAGE_ROUND` 的 `totalQuantityMilli` 同量级）——**这是本次返工最该先看的两条读数**；
  ③ 修复前的 360 tick 读数（`/home/cna/simos-runs/2026-10-08-sw19-arb/`）在修复后**必须重跑**，
  旧读数（与基线逐值相同）不能作为阶段 1 的结论。

---

## 6. 关账前自检（本轮最终状态，供控制方复核）

- **最终命令**（`pgrep -af classworlds.launcher` 确认本仓无 Maven 在跑之后）：
  ```
  ./mvnw -q -o spotless:apply -pl simos-economy,simos-app     # [spotless exit=0]
  ./mvnw -q -o -DskipTests compile -pl simos-app -am          # [compile exit=0]
  ```
- `git status --porcelain` 的改动面 = **只有本责任区的 5 个改动文件 + 6 个新文件**（外加控制方自己的
  `docs/**` 未跟踪文件）；**零测试文件、零 pom、零 docs**。
- 新文件行数：`ActivitySelector` 100、`HouseholdActivity` 193、`HouseholdResourceSnapshot` 108、
  `HouseholdValuationBook` 272、`MarketArbitragePlan` 145、`TradeArbitrageActivity` 516（合计 1,334 行）。
- `TradeArbitrageActivity.java` md5 = `b02a211f774ada56678f17c959da9daf`、
  `HouseholdValuationBook.java` md5 = `e7a323c5ad1f7ebfe8d3807bd36f2fb5`（本账本的数值结论对应这两份字节）。
- **★★ 2026-10-08 返工（控制方验收发现）**：克隆静默丢字段 ⇒ 套利零效果（详见 §5-C-2）。
  修复 = `MarketRound.copyForWorker` 唯一克隆拼写点 + `MARKET_ARBITRAGE_PLAN_LOST` 具名守卫；
  自证 = `CloneProbe` 的 `PROBE-PASS`（克隆路径 +50,000 逐值可验）与 `GUARD-MUTATION-PASS`（变异自证）。
  ★ 教训（写下来，别再犯）：**"编译过 + 单测/探针在非克隆路径上验过" ≠ "功能生效"** ——
  这次我的探针只验了 `evaluate` 的数值，**没有验"计划能不能走到订单生成"**；而真实下单用的是**克隆轮**。
  以后凡"新字段要穿过克隆/快照/委派边界"的改动，**探针必须打在边界之后的那一侧**（这里 = `planOrders(克隆轮)`）。
- **★ 2026-10-08 缺陷 2 修复中探针抓到的新真错**：日志改名时留下悬空旧键 ⇒ `LogEvent.of` 收到奇数个
  key/value ⇒ **运行时抛**（编译期查不出），只在日志真发射时炸。`LogProbe` 用 slf4j-simple 把四条发射路径
  真跑一遍后当场抓到并已修。★ 纪律：**日志改动必须真跑发射路径**；"编译过"不构成日志行合法的任何证据。
- **本账本写作过程中修掉的两个真错**（如实记，都是"会让整套机制静默失效"的那一类）：
  1. `cashAffordable` 少了 `MILLI_PER_GRAIN` 因子 ⇒ 现金买得起量小 1000 倍 ⇒ 粮价 1 毫时 `netMilli` 取整为 0
     ⇒ **套利恒不成立**（具名 `NO_DEPTH`/跳过）。修正后实测 `q = 105,525 毫粮`。
  2. 收益率分母用毫小时并 floor 到 1 ⇒ 小量交易收益率被放大 1000 倍 ⇒ **选品被高价小量商品垄断**
     （布 0.75 单位压过粮 105 单位）。改为微刻度精确比值 + 选品改用绝对收益（§4.1 ② 原文）。
