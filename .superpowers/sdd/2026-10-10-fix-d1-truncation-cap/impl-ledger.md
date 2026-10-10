# 实现架构账本 —— 修 D-1（运力截断量未按本侧余量封顶）+ 清零调用者访问器

责任区：`simos-economy/src/main/**`（唯一写口：`MarketSettlement` / `MerchantCapacityPool`）
纪律：只写生产代码、只过编译（`spotless:apply` + `-DskipTests compile`）；不跑 `test`/`verify`；不 commit；
`src/test/**`、`pom.xml`、`docs/**` 一个字未动。

## 1. 关键调查结论（`file:line` 证据）

| # | 证据 | 结论 |
|---|---|---|
| C-1 | `MarketSettlement.java:6815` 已算 `long blocked = Math.min(buyTotal, sellTotal)`，但**只进 DEBUG 日志**（`:6834-6845`），不进累计 | 修法所需的值本来就在，改两处累计即可（最小修） |
| C-2 | `:6818` `buy.capacityTruncatedMilli += sellTotal`（对侧卖槽整份余量）；`:6827` `sell.capacityTruncatedMilli += buyTotal` | 与 :6794 注释（"min(本侧余量, 对侧余量)，双方各记 30"）矛盾；D-1 的修复点 |
| C-3 | `:1893` `quantity − Math.min(quantity, buy.capacityTruncatedMilli)`（`:1900` 供给侧同形，`sellable` 封顶） | 截断读数的**唯一价格消费口**：读多了就把"挂单量以内、已服务的那份"也剔出去 ⇒ D-1 的价格后果 |
| C-4 | 另外两条累计路径：`:5291`（路线窗口运力用尽 ⇒ `+= buy.remaining`）、`:6781`（`markCapacityBlocked`，`executeTrade` 内） | 同一条 lane 在同一轮里可能**先**由 :5291 记一次、**再**由本方法记一次 ⇒ 见 §4 残留项 |
| C-5 | `:4069-4091` / `:4167` / `:4180`：`capacityTruncatedMilli` 随 `BuySlotState/SellSlotState` 在**区并行**模式下搬运回父上下文 | 不是落盘状态、不进 ChangeSet；只影响本轮 `adaptPrices` 与日志 |
| C-6 | `:1596` `ctx.capacityDemands.logRoundSummary(..., goodsBlockedByCapacity(ctx))`（`:6856` 汇总买槽读数） | 唯一其它消费者 = DEBUG 日志字段 `goodsBlockedByCapacityMilli`（`CapacityDemandBook:268`），无人断言 |
| C-7 | `MerchantCapacityPool.java:888-891` `remainingToolMilliOf`（私字段 `remainingToolMilli` 仍被 :529/:714/:746/:1038 等使用） | 只删公开读口，**不删字段**；同族 `toolBlockedRuns()` 有 3 处命中，保留 |

## 2. 实现（改动清单）

1. `MarketSettlement.blockLaneWithoutCapacity`：买侧 `+= sellTotal` → `+= blocked`（`:6822`），卖侧 `+= buyTotal` → `+= blocked`（`:6831`）；
   两侧各记**同一条车道**的 `min(买方余量合计, 卖方余量合计)`，与 :6794 注释、与日志 `truncatedMilli` 三处同值。
2. 同方法 Javadoc 补两行，写明"记的是车道级 `min(买余合计, 卖余合计)`，不是对侧整份余量"及其 V-20 后果。
3. `MerchantCapacityPool`：删 `remainingToolMilliOf()`（含其 Javadoc）。
4. 无其它改动（`git diff --stat` = 2 文件 / +6 −8）。

## 3. 关键判断

- **为什么用车道级 `min` 而不是逐槽 `min(buy.remaining, sellTotal)`**：裁定书原文指定 `+= Math.min(buyTotal, sellTotal)`；
  且现网两个调用点里 `:5493` 是单元素列表（逐槽与车道级等价），`:4687` 才是多元素（车道级 = 该车道装的量上界）。
  逐槽口径属"净额记账"（§4），超出本责任区，未做。
- **为什么不动 `:5291`/`:6781`**：两者记的是各自路径真正未服务的余量，单看都自洽；问题出在两路径**相加**（§4），
  修它要改记账口径（净额或去重），属另一个裁定。
- **缺省中性**：无运力/无截断（`blockLane` 不被调用，或 `blocked == 0`）⇒ 两处累计与改前逐值相同；
  单一买方/单一卖方且未被部分服务的常见情形下 `min == 对侧余量`（供给 ≤ 需求时）⇒ 逐值不变。
- **I7 确定性**：只把两个局部变量换成同一函数已算出的 `blocked`，不引入新遍历/时钟/随机 ⇒ 逐值可复现。

## 4. ★ 残留项（未改，供控制方裁定 —— 影响测试批的 9,823 期望）

最小修**不足以**让 D-1 记录里的价格从 9,500 回到 9,823：场景中买槽在第二次调用前已由 `:5291` 记过 `1,905`
（路线窗口运力用尽），本方法再记 `min(1,905, 3,000) = 1,905` ⇒ 累计 **3,810 > 2,905** ⇒ `:1893` 仍剔光 demand
⇒ 价格仍 9,500（9,823 对应的统计是 demand = 1,905、supply = 4,000，需要"净未服务量"口径）。
⇒ 这是 `:5291` 与 `:6822` 对**同一份未服务量**的重复记账（D-1b），修它要选：净额记账 / 两路径去重 / 只由一条路径记。
**本账本不给结论**，请控制方裁定后再派；`9,823` 的红用例在此之前写会必红。

## 5. 会变数值行为的清单（给测试 Agent）

- 变的**只有读数**：`buy/sell.capacityTruncatedMilli`、以及 DEBUG 日志 `goodsBlockedByCapacityMilli`。
- 变的**条件**（静态推导，未执行）：该 lane 被 `blockLane` 拦下 **且本槽已被部分服务**（`remaining < 挂单量`）
  且 `min(买余合计, 卖余合计) < 该槽统计量` —— 只有这时 `:1893` 的 `min(quantity, trunc)` 结果才变。
- **既有断言值：一处都不变**（推导）：
  - `CapacityTruncationPricingAcceptanceTest` 两案：①`world(-1L)` 买 2,905/卖 4,000 ⇒ 买读数 4,000→2,905，但 `quantity=2,905` 已封顶 ⇒ demand 仍 0、supply 仍 1,095 ⇒ **9,500 不变**（`:111-122`）；
    ②卖 10,000 扣同格 2,905 后 7,095 / 跨买 2,905 ⇒ 买读数 7,095→2,905，仍在 `quantity` 之上、卖侧读数两版同为 2,905 ⇒ (2,905, 7,095) ⇒ **9,790 不变**（`:209-214`），四值互异断言（`:205-207`）不变；9,921（`:126`）由夹具算式派生，与该读数无关。
  - `MerchantCapacityAcceptanceTest.endToEndFillIsCappedByTheHexCapacityPool`（唯一的部分运力案）：只断言 capacity 2,500（`:239`）、成交笔数/量（`:240-246`）、`LOGISTICS_CAPACITY` 具名（`:247-249`）；截断读数不进成交与具名，且该文件无价格断言 ⇒ 不变。
  - 其余带 carrier 的测试（`PortPolicyMerchantLaneAcceptanceTest` / `PortGateTaxAcceptanceTest` / `MarketSettlementSingleHexLossTest`）运力 ≫ 需求 ⇒ 无截断；全测试树除上述文件无 `capacityTruncatedMilli|CapacityTruncation` 命中，也无 `goodsBlockedByCapacityMilli` 命中。

## 6. 未做 / 未验证（如实记）

1. **未跑任何 `test`/`verify`**（派单纪律）⇒ §5 的"断言值不变"是**静态推导**，不是实测；执行者是测试 Agent。
2. §4 的残留项未修、**未执行验证**（推导链：D-1 记录 `1,905 + 3,000 = 4,905` 的算式与 `:5291`/`:6818` 逐值吻合）。
3. 多买槽车道下每槽各记一份车道级 `blocked` 的**逐槽超记**未修（单买槽场景无此形态）。
4. 未做：`git commit`、任何文档/测试改动（`docs/**`、`.superpowers/**` 除本账本）。
