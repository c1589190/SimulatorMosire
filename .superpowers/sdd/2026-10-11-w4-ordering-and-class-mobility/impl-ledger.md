# W4 实现账本 —— 家户的位置（排序结算 + 阶层滑落）

> 责任区：**W4**（约束设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` **v3.12** §6.1 W4 行 = 原 R5+R7）。
> 写码 Agent：W4 前台单代理；只写生产代码、只到 `compile -am` 过、不写/不跑测试、不 commit（AGENTS §一.5）。
> 编译：`tools/mvn-lock.sh -q spotless:apply` rc=0 → `tools/mvn-lock.sh -DskipTests compile -am` rc=0（16 模块全 SUCCESS）。

---

## 一、关键调查结论（file:line → 结论 → 影响）

| # | 证据（file:line，改动前） | 结论 | 对本批的影响 |
|---|---|---|---|
| 1 | `MarketSettlement.java:1737-1742`（`orderIndex` 赋值）+ `:1784`（`MarketIndexes.build`）+ `:4455`（`matchGroup` 的 `ProportionalSplit.byDenominator`） | 买槽的**唯一序**是"装配序"（hex (q,r) 升序 × 格内订单序）；区内/跨区撮合的买侧都是**按权重比例**分配 ⇒ 把买槽怎么排都**不改变每户拿到的量**（只改 ±1 毫残差） | 用户原话「家户之间的顺序还是得排序的啊」在改动前**结构上无法落地**。W4 必须同时改"序"（买槽排序）与"配给算法"（按序吃满），否则验收判据 ③ 永远给不出样例 |
| 2 | `MarketSettlement.java:3313-3390`（`commitFreezes`）、`:3467-3476`（`refreshSell/BuyFrozen`） | 冻结写**不经 `CommitOrder`**：它直接写 `ctx.round` 的冻结表（协调器活表），成交走 `executeTrade → applyTransfer` 唯一写口 | 任务书"与 `CommitOrder` 一起改"在本批的现实形态 = **写序随买槽序改变**（`commitFreezes` 按 `ctx.buys` 迭代），`CommitOrder` 本身**不需要也不应该改**（它排的是账户意向，与"谁先拿到货"无关）。见 §四-1 |
| 3 | `MarketSettlement.java:3735/3802`（`matchWithinRegions` / `matchWithinRegionsSerial` 读 `ctx.topology.regions()`）、`:4741-4748`（跨区 `buyerHexes` 取 `(q,r)` 升序） | 区序 = **拓扑声明序**，跨区买方格序 = `(q,r)` | "市场区之间按总 GDP 排序"要在这三处一起换（只换一处 ⇒ 同一件事两处拼写漂开） |
| 4 | `MarketRoundStats.java` 类注 + `MarketRoundStatsBook.java:108`（`previousCycleTurnover`） | W1 统计簿已提供**上周期累计名义成交额**，且类注明写"W4 的市场区排序量 = 上周期成交额，S-4/N-1 的防自指输入""排序用名义（turnover），现金栏不参与""跨币不折算，调用方自己说清" | 区序的量**不用新造**：直接 `previousCycleTurnover`，并在代码注释里写明"跨币不折算、原值相加"这条口径（W1 明确要求调用方说清） |
| 5 | `PrimaryModeRanking.java:240-266`（`inventoryMilli`）+ `DebtValuation.java:276-307`（`commonValueMilli` / `commonUnitValue`，量纲 = 毫计价币 ×1000） | 仓里**已有**两把可复用的尺：资产 = 货币 + 商品库存按本格牌价；债务 = Σ 本金 × 单位共同价值。⇔ 没有"含地产/工具的净资产"的价源（`DebtCapacity.PLEDGEABLE_ASSET_POLICY_VALUE_NOT_LANDED = 0`，"价格源未落地"） | 家户排序量 = `inventoryMilli × 1000 − Σ commonValueMilli`（同一把尺，**不新造估值**）；不可定价的债**不硬折**、具名计数（与 `DebtCapacity` 同一条处置）。★ 两个时点共用同一算式（`SettlementOrderBook.valuate`，静态 helper）：**排序**用盘前快照、**滑落速度**用市场轮末活状态 |
| 6 | `EconomyLiquidationSettlement.java:549-634`（改动前：E5b 的"4. 阶层下滑"）、`:287-295`（`ClassDeclinePlan`）、`:1302-1348`（apply）、`:1352-1434`（crisis 信号）、`:1557-1672`（阶梯/位置解析） | 改动前的"阶层下滑"是**三个阈值触发**（核心生产资料被处置 / `DEFAULTED` 且连续压力 ≥ 2 关账周期 / F 持续不足下一轮投入）+ **只在关账日** + 目标 = 同 mode 内"最近严格低位" | 与 C-3 裁定（"速度、逐级、慢慢滑，不是阈值事件"）直接冲突 ⇒ 整段删除（§一.11 旧设计直接删）；阶梯算法**整体搬到** `ClassLadderBook`（唯一拼写点），不留第二份 |
| 7 | `EconomyLiquidationSettlement.java:1630-1672`（`compareRoleRank` / `POSITION_HEIGHT_ORDER` / `relationOrdinal` / `surplusOrdinal`） | "级"的唯一既有语义 = `ProductionRole` 的**角色元组**（relationToMeans → surplusRole）；`legacy` 结构里 poor/middle/rich_peasant **元组完全相同** ⇒ 它们互相**没有高低** | ⇒ "不能跳级"只能按角色元组实现（一次只走"最近严格低位"）；"富农→中农→贫农"这条**社会阶梯在模型里不存在**（要 `ruleExtensions.downwardPositionId` 显式写）。这是 W4 的**最大口径边界**，已具名上报（§五） |
| 8 | `HouseholdClassMembership.java:42/52`（`lastTransitionDay`，构造期 ≥ 0，创世/播种 = 0） | 已有持久字段 = "最近一次阶层变更日"，由 E5b/模式变迁写 | "速度"的积分**不需要新状态**（铁律 5 不触发）：`已过天数 = day − lastTransitionDay`，`每级所需天数 = ⌈轮距 × 每级成本 ÷ 速度⌉` ⇒ 速度越快、等得越短；本类不扩义该字段 |
| 9 | `ModeMigrationPolicy.java:593-604`（`moveable = max(1, 人口 × MIGRATION_PER_MILLE/1000)`）+ `:231-239`（`MigrationMove.transferSpeedPerMille` 只许 10 或 1000） | 生产方式迁移的**基线速度是固定常量 10‰**，与债务/资产无关；速度字段的守卫只认两个具名值 | 按任务书改口径：基线 = `max(10‰, f(债务/资产))`（与 `ClassSlideSettlement` 同一条算式、同一份快照）；守卫放宽到 `[1,1000]` 并把真实速度记进 move。见 §四-2 的取舍 |
| 10 | `MarketSettlement.java:1296-1319`（`triggerFor`）、`:125`（`MARKET_RESTOCK_INTERVAL_DAYS = 5`） | 市场轮 = 每 5 天 / 关账日 / 粮紧张日；"市场轮最末"有明确落点 | 阶层滑落的时点落在 `clearOncePerCycle` 返回之后、`4b 借粮` 之前（§二-3 给了调用链） |
| 11 | 测试树 grep（`*/src/test`）：`CLASS_DECLINE` / `class-decline` / `DOWNWARD_POSITION_EXTENSION_KEY` = **0 命中**；`clearOncePerCycle` 4 处调用都走 4 参重载（未删）；`ModeMigrationPolicyD024Test:437-438/512-513` 断言速度 == 10 | 删除 E5b 的阶层下滑**不动任何既有测试的编译**；但迁移速度断言会因新口径失效 | 报"会让既有测试断言失效的清单"（§三-2）；测试归测试 Agent（§一.5） |

---

## 二、自己的实现架构（改了什么、怎么落地）

### 2-1 新增三个类（`simos-economy/.../time/`，全是纯函数/快照，不落盘）

| 类 | 职责 | 关键点 |
|---|---|---|
| **`SettlementOrderBook`** | **开盘前结算序快照**（S-4/N-1 的唯一拼写点）：家户"资产 − 负债净值"名次 + 区"上周期名义成交额"名次；**并对外提供逐户估值的唯一算式** | `snapshot(day, topology, marketTables, households, debts, accounts, statsView)`；家户序（净值降序 → `HouseholdId` 升序）、区序（上周期名义降序 → 本周期降序 → 区 id 升序）；`empty(day)` = 名次全未知（夹具/退化路径）。★ **公共静态 helper**：`valuate(accounts, marketTables, topology, household, hex, householdDebts)`（资产/负债同一把尺）、`debtsByDebtor(debts)`（索引，规范序）、`record HouseholdValuation`（含 `debtFullyPriced()`）|
| **`ClassLadderBook`** | "户当前位置"与"下一级"的唯一拼写点（**从 E5b 搬来**） | `resolvablePosition` `:64`（有 standing 用 standing，否则旧 stratum 默认位置）；`nearestLower` `:90`（`ruleExtensions.downwardPositionId` 优先 → 同 mode 角色元组的**最近严格低位**）；类注写清"级"的确切含义与已知边界（poor/middle/rich 无高低） |
| **`ClassSlideSettlement`** | **阶层滑落**（S-1 的唯一写口） | 入口 `settleAtMarketRoundEnd` `:101`；`ratioPerMille` `:340`、`speedPerMille` `:355`、`requiredDays` `:368`；写口只有一处 `classMemberships.put` `:221`（只改 `currentPositionId`/`lastTransitionDay`/`reason`，人口/货币/资产一字不动）；常量 `SLIDE_SPEED_PER_MILLE_PER_RATIO = 100`（`:73`）、`SLIDE_MAX_SPEED_PER_MILLE = 1000`、`SLIDE_COST_PER_LEVEL = 1000`（`:79`）、`SLIDE_ROUND_DAYS = MarketSettlement.MARKET_RESTOCK_INTERVAL_DAYS` |

**速度算式（逐值可算；`speed = min(1000, ⌊ratio × 100 ÷ 1000⌋)`、`每级天数 = ⌈5 × 1000 ÷ speed⌉`）**

```
债务/资产 = 0.5 ⇒ ratio=500  ⇒ speed=50  ⇒ 每级 100 天
债务/资产 = 1.0 ⇒ ratio=1000 ⇒ speed=100 ⇒ 每级  50 天
债务/资产 = 2.0 ⇒ ratio=2000 ⇒ speed=200 ⇒ 每级  25 天
债务/资产 ≥ 10  ⇒ speed 打满 1000        ⇒ 每级   5 天（= 每市场轮恰一级，最快档）
```

### 2-2 排序结算（S-4）的落点

| 落点 | file:line（改动后） | 说明 |
|---|---|---|
| 快照构造（**禁自指**的结构保证） | `EconomySettlement.java:2318` | 在 `MarketSettlement.clearOncePerCycle` **之前**构造一次；之后撮合/改价/记账都不回读 ⇒ "当日成交额翻倍不影响排序"由结构保证 |
| 买槽排序（家户序） | `MarketSettlement.java:1781` 调用 → `:3649` 实现 | 排序键 = 区名次 → **家户名次（净值降序）** → hex(q,r) → 买方 actor canonical → 商品 id → 原下标（全序） |
| 区遍历序 | `MarketSettlement.java:10064`（`MatchContext.orderRegions`）+ `:3888`（区内）+ `:4745`（跨区买方格序） | 区名次（上周期名义成交额降序）→ 区 id |
| 买侧配给（谁先拿到稀缺货） | `MarketSettlement.java:3734`（`buyPartsByPriority`）+ 两处调用 `:4612`（区内）/`:5372`（跨区） | 需求 > 供给时**按结算序依次吃满**；供给充足时逐值等于改动前 |
| 冻结写序 | `MarketSettlement.java:3313`（`commitFreezes` 按 `ctx.buys` 迭代） | 买槽重排 ⇒ 冻结的**写序**随之改变（冻结**额**不变：逐 (买方,币种) 组独立按比例） |
| CommitOrder | **未改** | 见 §一-2：市场路径不经 `CommitOrder`（冻结直写活表、成交走 `applyTransfer`）；`CommitOrder` 排的是账户意向，与"谁先拿到货"无关 |

### 2-3 阶层滑落（S-1）的调用链与时点

```
EconomySettlement.settleOneDayInto(...)            （日结算唯一实现）
  └─ 4. 区域市场清算  if (marketTrigger != NONE)     :2192（市场轮闸门）
       ├─ [盘前] SettlementOrderBook.snapshot(...)  :2318
       ├─ MarketSettlement.clearOncePerCycle(...)   :2320（撮合 + 自适应价写回 + 统计簿落盘）
       ├─ … 政府授权成交累加 / MARKET_* 日志 / 统计簿 INFO+DEBUG …
       └─ [市场轮最末] ClassSlideSettlement.settleAtMarketRoundEnd(
              base, session.sheet(), day, householdEconomies,
              debts, accounts, topology, markets)                                            :2702
            ★ 估值读的是**轮末活状态**（含本轮赊购的债、本轮买卖后的货币/库存、轮内写回的新价）
  └─ 4b 借粮 :2710 → 4c 偿还 → 计息 → 5b.5 清算 → 5c 阶层写回 → 流水 → 关账钩子(迁移)
```

* **时点证据**：`CLASS_SLIDE` / `CLASS_SLIDE_ROUND` 与 `CLASS_SLIDE_SETTLED` 三行日志都发生在同一天的 `MARKET_*` 之后、`DEFICIT_*`/借粮之前；且落在 `marketTrigger != NONE` 块内 ⇒ **只在市场轮**发生（不是每天）。
* **"当轮债务规模算出之后"**：市场轮里的赊购债务在 `executeTrade` 内落进 `debtContracts`，本阶段在其后读**同一份工作副本的活状态**（不是盘前快照 —— 这一点在实现中修正过一次：初版误用盘前快照，会漏掉本轮新债，与用户原话"当轮算出这个家户的债务规模后进行"不符）。**两处只差时点、不差口径**（同一 `SettlementOrderBook.valuate`）。
* **不跳级**：每次调用对同一户只 `put` 一次、且只写 `ClassLadderBook.nearestLower` 给出的**最近严格低位**（一级）；调用点一轮一次 ⇒ 结构上不可能一轮连降两级。
* **不写别的东西**：不碰账户/人口/资产/债务；`view.stratum` 的投影仍由既有 5c 在关账日做（一处投影）。

### 2-4 ModeMigrationPolicy（口径改动，见 §四-2）

* `plan()` 在建 `draftsBySource` **之前**建一次同一份快照（`ModeMigrationPolicy.java:392`），逐源户取 `f(债务/资产)`（`:slideSpeedPerMille`）。
* 基线速度：`baselineSpeedPerMille = max(MIGRATION_PER_MILLE, w4SlideSpeedPerMille)`（`:611-615`），并把这个**真实速度**写进 `MigrationMove.transferSpeedPerMille`（`:623`）。
* 守卫放宽为 `[1, 1000]`（`ModeMigrationPolicy.java:234`），理由是速度不再是两个具名值之一。

### 2-5 日志（§一.9；全部具名计数 + 理由，逐户明细 TRACE）

| 事件 | 级别/分类 | 内容 |
|---|---|---|
| `MARKET_ORDER_APPLIED` | INFO / market | 买槽数、参与排序的家户/区数、不可定价债务条数与原始本金、`reason=households-by-net-worth-desc+zones-by-previous-cycle-nominal-turnover-desc` |
| `MARKET_ORDER_WHY` | DEBUG / market | 区序逐区（`rank:zone=上周期名义/本周期`）、两条排序输入的口径串、`snapshot=pre-round(no-current-round-turnover)` |
| `MARKET_ORDER_HOUSEHOLDS` | TRACE / market | 逐户 `rank:household=净值` |
| `CLASS_SLIDE` | INFO / migration | 每次滑落一行：`from`/`to`/`fromLevel`/`toLevel`/`levels=1`/`debtRatioPerMille`/`speedPerMille`/`elapsedDays`/`requiredDays`/资产/负债 |
| `CLASS_SLIDE_ROUND` | INFO / migration | 每轮一行：`evaluated`/`slid`/`skippedNoDebt`/`skippedNoPosition`/`skippedCooldown`/`skippedNoLowerTarget`/`unpricedDebtHouseholds` + 三个标定常量 |
| `CLASS_SLIDE_SPEED` | TRACE / migration | 逐户速度读数与 `decision`（`no-debt`/`no-current-position`/`cooldown`/`no-lower-target:…`/`slid-one-level`） |
| `CLASS_SLIDE_SETTLED` | DEBUG / settlement | 阶段收尾一行（带 `marketTrigger` 与 `at=market-round-end-after-price-writeback`） |

---

## 三、会改变数值行为的清单（逐条具名）+ 受影响字面量 + 测试影响

### 3-1 数值行为变化（**每一条都是有意为之**，W4 判据 ③④ 要求）

1. **稀缺商品的买侧分配从"按需求比例"改为"按结算序依次吃满"**（`buyPartsByPriority`）。⇒ 需求 > 供给时，净值高的家户先拿满，靠后的拿剩余（甚至 0）。**这是用户"家户之间的顺序还是得排序的"的直接落地**，也是验收判据 ③ 的唯一可能来源。
2. **买槽的处理顺序从"装配序（hex (q,r) × 格内插入序）"改为"区名次 → 家户净值名次 → canonical tie-break"**。⇒ 同一 (区,商品) 桶内的买方序列、跨区买方格序都变；冻结的**写序**随之变（额不变）。
3. **区的撮合/回放序从"拓扑声明序"改为"上周期名义成交额降序"**。⇒ 谁先消耗本轮共享运力池、谁先进入账户因果链改变；**跨区预算/口岸闸的分配**在"预算不够全场"时会落到不同的区（`buildPortBudgets` 的计算本身与序无关，但扣减序依赖配对序）。
4. **阶层滑落从"关账日 + 三个阈值事件"改为"市场轮末 + 速度 = f(债务/资产)"**（`ClassSlideSettlement`）。⇒ ① 滑落的**时点**从每 120 天变成每 5 天一次机会；② 触发条件不再是"违约/被清算/投入不足"，而是"债务相对资产够大且距上次变更够久"；③ 无债户**永不滑落**（改动前可能因"核心资产被处置"下滑）。E5b 的阶层下滑支路整段删除。
5. **生产方式迁移（ModeMigrationPolicy）的基线速度从 10‰/周期 改为 `max(10‰, f(债务/资产))`**。⇒ 有债户在关账日的迁移人数变多（净值越低越快，上限 1000‰）；无债户逐值不变。
6. **`MigrationMove.transferSpeedPerMille` 的取值域从 {10, 1000} 扩到 [1, 1000]**（守卫放宽），迁移日志/支付链上都可能读到非 10 的值。

### 3-2 新增/改动的硬编码字面量（标定项；给测试 Agent 当输入）

| 字面量 | 值 | 位置 | 性质 |
|---|---|---|---|
| `SLIDE_SPEED_PER_MILLE_PER_RATIO` | **100** | `ClassSlideSettlement:73` | **政策标定值**（控制方实现口径，非用户裁定；W6 标定首查项） |
| `SLIDE_MAX_SPEED_PER_MILLE` | **1000** | `ClassSlideSettlement:76` | 速度上限（配"一轮最多一级"） |
| `SLIDE_COST_PER_LEVEL` | **1000** | `ClassSlideSettlement:79` | "一级"的成本（‰） |
| `SLIDE_ROUND_DAYS` | **5** | `ClassSlideSettlement:82`（= `MarketSettlement.MARKET_RESTOCK_INTERVAL_DAYS`，同一常量，不复制值） | 积分步长 |
| `ASSET_SCALE_TO_COMMON_VALUE` | **1000** | `SettlementOrderBook:89` | 量纲对齐（毫计价币 → 共同价值刻度），不是标定值 |

### 3-3 会让既有测试断言失效的清单（**只报不改**）

| 测试类 | 失效原因 |
|---|---|
| `simos-economy/.../time/ModeMigrationPolicyD024Test`（`:437-438`、`:512-513`） | 断言 `move.transferSpeedPerMille() == MIGRATION_PER_MILLE(10)`；新口径下基线 = max(10, f(债务/资产))，fixture 户有债时会 > 10 |
| `simos-economy/.../time/ModeMigrationGovServiceFailClosedTest`（`:188`） | 传入/断言 10‰ 的迁移速度（同上，若走生产 plan() 会被新口径改写） |
| `simos-economy/.../market/HomeModeMigrationProbeTest`（`:319`） | 用本地常量 10‰ 反算"可迁人口"（同上） |
| 所有断言**成交笔数/分配量/逐户获得量**的市场类测试（`MarketSettlement*Test`、`PortGateTaxAcceptanceTest`、`EconomyCycleHealthTest`、`Z7MarketExclusionTest`、`MarketSettlementFixtures` 等 31 个引用 `MarketSettlement/EconomySettlement/classStandings` 的测试类） | 买侧配给算法 + 买槽/区序都变 ⇒ 逐笔分配结果变（供给充足时不变，**稀缺时变**） |
| 任何断言"关账日才发生阶层变化 / `CLASS_DECLINE` crisis 信号 / 清算审计里的 class-decline"的测试 | E5b 的阶层下滑支路已删除（测试树里 `CLASS_DECLINE`/`class-decline` 目前 **0 命中**，但 W6/测试批若按旧判据写用例会踩） |
| 任何断言"市场区按 zoneId 升序/拓扑声明序遍历"的测试 | 区序改为上周期名义成交额降序（无统计簿时退化为区 id 升序，**多数夹具世界因此逐值不变**） |

---

## 四、关键判断（为什么这样拆、推翻了什么）

1. **不改 `CommitOrder`，而是把序落在"买槽表 + 冻结写序"上**。调查（§一-2）表明市场轮**根本不经过** `CommitOrder`：冻结直接写活表、成交走 `applyTransfer`。若为了"照任务书字面"去动 `CommitOrder` 的 `canonicalKey`/`partitionIndex`，只会破坏 R1 的并行确定性（`canonicalKey` 是账户分区键，掺进市场名次会让同一账户落进两个分区）。⇒ **按机制的实际形状落地**（AGENTS §四：机制性描述回代码核），并在报告里点名。
2. **排序量不新造估值**：资产用既有的 `PrimaryModeRanking.inventoryMilli`、负债用既有的 `DebtValuation.commonValueMilli`。**推翻过**的一条路：本想给资产加上"生产资料份额折价"（`assetShares`），但仓里对地产/工具**没有价源**（`DebtCapacity.PLEDGEABLE_ASSET_POLICY_VALUE_NOT_LANDED = 0`）⇒ 硬造一个价就是把"排在前面的原因"变成我发明的数。现口径下**不可定价的部分一律具名排除**（`unpricedDebtContracts` / `unpricedHouseholds`）。
3. **"速度"用"每级所需天数"表达，而不是新增进度累加器**。要"慢慢滑"且"逐值可算"，最诚实的做法是让速度决定**冷却时间**：`每级天数 = ⌈轮距 × 1000 ÷ 速度⌉`，锚点用既有的 `lastTransitionDay`。这样①不新增持久字段（设计书 W4 行"铁律 5 = 否"成立）、②不扩义既有字段（它本来就是"最近一次阶层变更日"）、③分段等价（`day` 是绝对日）。**推翻过**的两条路：(a) 新增 `classSlideProgressPerMille` 字段（要连做 Codec+ChangeSet+往返，且与设计书"本批不动形状"冲突）；(b) 纯人口比例迁移（无法满足判据 ④"同一家户最多下滑一级"的字面）。
4. **删掉 E5b 的阶层下滑而不是"留着但改触发"**：留着就等于同一件事有**两个触发权威**（一个是关账日阈值、一个是市场轮速度），而 C-3 裁定明说"不是阈值触发"。删除面已核：测试树对 `CLASS_DECLINE`/`class-decline` **0 引用**，编译全绿。
5. **`ModeMigrationPolicy` 的速度取 `max(旧常量, f(债务/资产))` 而不是纯 f**：纯 f 会让"无债户"速度恒 0 ⇒ 生产方式迁移对绝大多数户**停摆**——那是**另一项设计决定**（会连带关掉 E6a/商户/产业转换的整条流），超出 W4 授权；取 max 是 fail-closed 的保守形态，且**方向与用户原话一致**（债务相对资产越大 ⇒ 越快）。偏离记录见 §五-3。
6. **卖侧不按净值排**：卖侧的服务序是既有的"到货成本升序 → canonical tie-break"（S3/P-T1d 的机制）。把卖方改成按净值排会**撞坏**"最低成本者先卖"这条既有机制，而 W4 判据 ③ 说的是**买方**拿到稀缺货。⇒ 只动买侧，并在代码注释里写明这个边界。

---

## 五、偏离记录（与约束设计书 / 任务书不一致处，逐条具名）

1. **任务书说"与 `CommitOrder` 一起改" ⇒ 本批未改 `CommitOrder`**。理由见 §四-1（市场轮不经它）。**影响面**：无功能缺口（序已落在买槽表与冻结写序上）；但若控制方认为必须让 `CommitOrder` 也体现 W4 名次，那需要先改造"市场轮也走意向提交"这件**结构性**的事（属另开一批）。
2. **阶层滑落与 `ModeMigrationPolicy` 的关系**：任务书把 `ModeMigrationPolicy.plan` 指为"现有实现"。实际上它做的是**生产方式迁移**（人/钱/债在户之间搬），而"阶层下滑"住在 E5b（§一-6）。本批**两条都动**：① 阶层滑落新建 `ClassSlideSettlement`（口径 + 时点按 S-1）；② `ModeMigrationPolicy` 只改**速度口径**（`max(10‰, f(债务/资产))`），**时点仍在关账日**——把它挪到市场轮需要重造"周期利润账"窗口（`EnterpriseProfitBook.CycleAccumulator` 是周期口径），那是**另一批的工作量**，且用户原话的"最后再结算"已由 ① 承担。
3. **"级"的口径（★ 已在报告里上报，请控制方裁定）**：用户说"不能跳级"，但模型里**没有**社会位阶（贫→中→富→地主），只有 `ProductionRole` 的角色元组位阶（§一-7）。本批按**唯一既有的位阶**实现"一次只走最近严格低位"，并把这个边界写进 `ClassLadderBook` 类注与报告。**若控制方要求"富农→中农→贫农"这条阶梯**，需要给位置表写 `ruleExtensions.downwardPositionId`（数据/创世面的事），本批不替用户发明阶梯。
4. **速度斜率的取值（100‰ per 1.0 债务/资产）是控制方实现口径**（同 `S-1` 的"f 是什么"没给公式）：本批按"债务/资产 = 1 ⇒ 每 50 天降一级、≥10 ⇒ 每轮一级（上限）"标定，列 W6 标定首查项。**若与用户预期不符，改一个常量即可**（`ClassSlideSettlement.SLIDE_SPEED_PER_MILLE_PER_RATIO`）。
5. **每轮最多一级 ⇒ 极端负债户会"每轮一级"直到无更低位置**：`speed` 触顶 1000‰ 后 `每级天数 = 5`，与市场轮同频。这是"上限"而不是"典型"：比值 1.0 的户是 50 天一级。**未验证**（无真实 world 读数，见 §六）。
6. **W1 统计簿的"跨币不折算"**：区序把各笔名义成交额按**其计价币原值**相加（W1 明确要求调用方说清口径）。多币世界里这条排序量是"混合量纲"；本批照 W1 的既有账面口径实现，并在 `SettlementOrderBook` 类注具名。若要按单一币种排序，需要统计簿增加逐币明细（另开一批）。

---

## 六、未完成 / 未验证项（如实）

* **未跑任何测试**（§一.5：写码代理只到编译过）⇒ 判据 ①②③④⑤ **一条都没有实测**；§三-3 的测试影响是**静态推断**（按代码与断言文本），不是跑出来的。
* **没有真实 world 读数**（未起 jar、未跑 `advance`）：因此"排序真的改变了谁先拿到货""滑落真的发生了""md5 可复现""Δ=1""耗时劣化"都**未验证**。
* **`git status` 之外的静态审计未做**：没有 grep 全仓确认"没有第二条写 `currentPositionId` 的路径"（已知写口：`EconomyLiquidationSettlement.writeStressCounters` 播种、`EconomyModeTransitionSettlement` 模式变迁、GM 命令面 `EconomySetHouseholdParticipationHandler`、`ModeMigrationSettlement` 新建目标户、本批的 `ClassSlideSettlement`）—— 留待测试/验收批。
* **性能未量**：快照是 O(家户 × 商品 + 债务 + 区) 且**每市场轮一次**；`SettlementOrderBook.snapshot` 在 `ModeMigrationPolicy.plan` 里也建一次（关账日一次）。**未测**耗时。
* **`ClassSlideSettlement` 的 `sheet.classMemberships()` 物化**：每次市场轮都会把 `classStandings` 物化成工作副本（内容相同 ⇒ 不改变终态，但**未测**它对 Revision/内存的影响）。

---

# W4-fix（SpotBugs 四条 EI + 同类自扫）

> 范围：只改 `simos-economy/src/main/**` 的**两个**文件（`SettlementOrderBook.java` / `ClassSlideSettlement.java`）；**未** `git commit`、**未**跑 `test`/`verify`/`package`/`clean`；**未**动任何数值口径 —— §三-1 的数值行为变化清单与 §三-2 的字面量清单**均无新增/变化**（F-6）。

## F-1 门禁现场（父批 `clean verify` 实测，只读引用）

`simos-economy/target/spotbugsXml.xml`（SpotBugs 4.10.4；插件配置见 `pom.xml:218-220` = `effort=More` / `threshold=Low`，`pom.xml:277-283` 绑在 `verify`）里**全模块共 4 条** `EI_EXPOSE_REP`，全部落在 `SettlementOrderBook` 的四个访问器上：`householdsByRank()` / `zonesByRank()` / `zonePreviousCycleTurnover()` / `zoneCycleTurnover()`（XML 记录的 getter 行号 400/405/435/440 = **改动前**源码行）。★ 另外两类（`ClassSlideSettlement` / `ClassLadderBook`）在该 XML 里 **0 条**。

## F-2 修法（照 W1e 先例：不可变包装必须是赋值右侧**最外层**那次调用）

★ 依据（既有先例，非自创）：`MarketReadout.java:116-125` 的 W1e 注释明写"包装写成三元表达式的最外层 SpotBugs 就识别不了 ⇒ `EI_EXPOSE_REP (MarketReadout.marketRoundStats())`"；同模块**已证可用的同形先例**：`MarketTopology.java:170`（`Collections.unmodifiableList(new ArrayList<>(regions))`）+ `:393`（`regions()` 直接返回该字段）而门禁 0 报。

| # | file:line（改后） | 改前 | 改后 |
|---|---|---|---|
| 1 | `SettlementOrderBook.java:104` | `this.householdsByRank = householdsByRank;` | `this.householdsByRank = Collections.unmodifiableList(new ArrayList<>(householdsByRank));` |
| 2 | `SettlementOrderBook.java:121` | `this.zonesByRank = zonesByRank;` | `this.zonesByRank = Collections.unmodifiableList(new ArrayList<>(zonesByRank));` |
| 3 | `SettlementOrderBook.java:127-128` | `this.zonePreviousCycleTurnover = zonePreviousCycleTurnover;` | `this.zonePreviousCycleTurnover =` `Collections.unmodifiableMap(new LinkedHashMap<>(zonePreviousCycleTurnover));` |
| 4 | `SettlementOrderBook.java:129` | `this.zoneCycleTurnover = zoneCycleTurnover;` | `this.zoneCycleTurnover = Collections.unmodifiableMap(new LinkedHashMap<>(zoneCycleTurnover));` |

* **未加 `@SuppressFBWarnings`（本批 0 处抑制）**：改后只读自检已是 0 条（F-5-3）⇒ 加了会踩 B2 的 `US_USELESS_SUPPRESSION_ON_CLASS`。
* **行为不变**：四个字段的**内容、迭代序、判据**一字未改，副本 + 包装只改"可变性"。List 用 `ArrayList` 副本（保序 = 入参插入序）、Map 用 `LinkedHashMap` 副本（**禁 `Map.copyOf`**：I7 要求迭代序 = 内容的纯函数）。调用点既有的 `List.copyOf(households)` / `List.copyOf(zones)` / `Collections.unmodifiableMap(...)`（`:243-255`）**原样保留**（⇒ 同值同序的二次引用，逐值相同）。
* 四个**不进本批**的 Map 字段（`householdNetWorth` / `householdAssets` / `householdDebt` / `householdDebtUnpriced`，`:107-110`）：**没有"整表返回"的访问器**（只有 `householdNetWorthOf` 这类逐键取值），且唯一非空调用方 `snapshot()` 已在传参处包了 `Collections.unmodifiableMap`、`empty()` 传 `Map.of()` ⇒ 不构成暴露面；理由已就地写在 `:105-106`。

## F-3 同类自扫（3 个 W4 新类的**全部**"暴露内部集合"读口，逐个结论）

| 类 / 成员 | 形态 | 结论 |
|---|---|---|
| `SettlementOrderBook.householdsByRank()` `:405` / `zonesByRank()` `:410` | 返回字段 `List` | **本批修**（F-2 表 1/2） |
| `SettlementOrderBook.zonePreviousCycleTurnover()` `:440` / `zoneCycleTurnover()` `:445` | 返回字段 `Map` | **本批修**（F-2 表 3/4） |
| `SettlementOrderBook.householdRank` `:115` / `zoneRank` `:126` | 有字段、**无整表访问器**（`householdRankOf` `:364` / `zoneRankOf` `:370` 返 `int`） | **已冻结**（构造处，改动前既有） |
| `SettlementOrderBook.householdNetWorthOf` / `householdAssetsOf` / `householdDebtOf` / `householdDebtUnpriced` | 逐键取值，返 `Long` / `boolean` | **非暴露面**（见 F-2 末条） |
| `SettlementOrderBook.describeZones()` `:450` / `describeHouseholds()` `:467` | 每次新建 `ArrayList` + `List.copyOf`（`:463` / `:473`） | **已冻结**（改动前既有） |
| `SettlementOrderBook.debtsByDebtor(...)`（`public static`，`:307`） | 返回**新建局部** `LinkedHashMap`（非字段） | **非暴露面**（调用方独占的新实例，不是 internal representation） |
| `ClassSlideSettlement.SlideRound.slides()` | record 组件访问器直接返回入参 `List` | **本批修**：`ClassSlideSettlement.java:477` 加 `slides = List.copyOf(slides);`（前一行仍是既有的 `Objects.requireNonNull`）。唯一构造方已传 `List.copyOf(slides)`（`:323`）⇒ `List.copyOf` 对不可变入参**原样返回**：逐值、开销、迭代序都与改前相同 |
| `ClassSlideSettlement.Slide`（record） | 组件 = `HouseholdId` / `HexCoord` / `ClassPositionId` + 6×`long` | **非暴露面**：无集合组件；三个 ID 类型都是不可变 record（`record HouseholdId(String)` / `record HexCoord(int,int)` / `record ClassPositionId(String)`） |
| `ClassLadderBook.DownwardTarget.target()` | `Optional<ClassPositionId>` + `String` | **非暴露面**（`Optional` 本身不可变；`ClassPositionId` 是不可变 record） |
| `ClassLadderBook` 其余成员 | 全静态纯函数，返回 `Optional` / `int` / `String` | **无状态、无暴露面** |

## F-4 其它高危形态（空指针 / 装箱往返 / `==` 比对象）逐条核查

* **门禁自身**：只读自检 XML 里**除那 4 条外 0 条**（`threshold=Low` ⇒ 连 LOW 都报，F-5-3/4）⇒ 这三类形态在这 3 个新类里**没有被报出**。
* **手工逐条核**（按任务书点名的三类）：
  1. `ClassLadderBook.java:98` 的 `current.ruleExtensions().get(...)` **不会 NPE**：`ProductionRole` 紧凑构造器 `:94-95` 对 null 直接抛 ⇒ 该类注明的契约是"不得为 null（没有规则给空表）"。
  2. `SettlementOrderBook` 两处比较器 lambda 的 `netWorthByHousehold.get(id)` / `previousTurnover.get(id)`：`Long` 拆箱**不会 NPE** —— 键集来自同一张表的 `keySet()`（`:208` / `:219`），构造上必然命中。
  3. **装箱往返**：`householdRankOf` `:364` / `zoneRankOf` `:370` 是"先 `Integer rank = map.get()` 再显式判 `rank == null` 才拆箱"的既有正确形态 ⇒ 无 `BX_*` 隐患。
  4. **`==` 比对象**：3 个类里所有 `==`/`!=` 都是**空判**或**原始类型比较**（逐条已列，无对象同一性比较）；字符串/ID 一律 `.equals(...)`（`ClassLadderBook.java:103` / `:122`）、布尔用 `Boolean.TRUE.equals(...)`（`SettlementOrderBook.java:391`）⇒ **0 处需修**。
  5. `ClassSlideSettlement.requiredDays(Long.MAX_VALUE)` 理论上会整数溢出（`numerator + speed - 1`），但**生产路径不可达**：唯一调用点 `:195` 的实参由 `speedPerMille(...)` 给出且恒 ≤ `SLIDE_MAX_SPEED_PER_MILLE=1000` ⇒ **不改**（改了反而动数值语义）。留作测试批的边界用例提示。

## F-5 自证（只读 SpotBugs，**非门禁等价**）

真命令与 rc（未接管道，rc 直接取）：

1. `tools/mvn-lock.sh -q spotless:apply` → **rc=0**；economy `Spotless.Java is keeping 266 files clean`。★ 判别力自证：手动打乱 `SettlementOrderBook.java` 一行（md5 `78bbcc42…` → `b3104f6b…`）⇒ 再跑 `spotless:apply` ⇒ 被**逐字节改回**（`cmp` 相同、md5 回到 `78bbcc42…`）⇒ 该护栏确实覆盖本文件，且不是增量缓存的假绿。
2. `tools/mvn-lock.sh -DskipTests compile -am` → **rc=0**，16 个模块全 `BUILD SUCCESS`（`EconomySimos 1.696s`；checkstyle 在 validate 阶段同轮通过）。
3. `tools/mvn-lock.sh -pl simos-economy -Dspotbugs.failOnError=false spotbugs:check` → **rc=0**，`BugInstance size is 0` / `Error size is 0`（13.2s）。
4. **判别力自证（变异体）**：把 4 处包装退回裸赋值（md5 `82e40af8…`，`cmp` 证明确实不同字节）⇒ `compile` + 同一 `spotbugs:check` ⇒ **`BugInstance size is 4`，且 4 条 `LongMessage` 与门禁基线**逐条**相同**（脚本比对 `与门禁前基线逐条一致: True`；行号 405/410/440/445 = 改后 getter 行）⇒ 这个"0"不是空过。还原后 md5 回到 `78bbcc42…`。
5. ★★ **证据强度边界（含一次踩坑，如实记）**：
   * 该自检的 aux classpath 走 `~/.m2` 的 SNAPSHOT jar（自检 XML 的 14 条 `AuxClasspathEntry` 全在 `~/.m2/repository/io/mosire/...`），而**门禁那轮**走反应堆内 jar（`/home/cna/SimulatorMosire/simos-*/target/*.jar`）⇒ **不是门禁等价**。★ 但这 4 条 EI 是**类内**判定（getter 返回本类字段 + JDK `List`/`Map` 的可变性），不依赖 aux ⇒ "这 4 条已消失"可靠；更宽的"全模块 0 条"仍受 aux 差异限制。
   * **踩坑（已修正并留痕）**：还原变异体时用 `cp -p` 把源文件 mtime 一并还原成**旧于**变异轮的 class ⇒ Maven 增量编译**跳过**重编 ⇒ 同一 `spotbugs:check` 仍报 4 条（分析的是 `target/classes` 里的**变异体 class**）。这是"分析器的判定不是被分析文件的纯函数"的又一例（AGENTS §三）。处置：`touch` 源文件（**md5 不变** ⇒ 内容一字未改）+ 删掉该 `.class` 强制重编，`javap` 确认构造器里有 **6 处**包装调用（4 处本批 + 2 处既有）后才跑自检 ⇒ 0 条。
   * `simos-economy/target/spotbugsXml.xml`（门禁原始产物）**先备份后还原**：还原后 md5 = `1722ec3c651965f300fc217736b1e5d0`、133536 字节，与备份 `cmp` 相同（**mtime 变新、字节未变** —— 按 AGENTS §三"mtime 不是判据，md5 才是"）。
   * **未跑 `verify`**（任务书禁止）⇒ 门禁等价的绿**没有**由本批实测；3/4 是替代证据。

## F-6 会改变数值行为的清单

**空。** 本批只改字段的**可变性包装**（外加一条 record 组件的防御性副本）：字段内容、迭代序（I7 保序）、判据、日志、命令面、持久形状（铁律 5 不触发）**全部未动**。

## F-7 未完成 / 未验证项

* **未跑任何测试**（任务书禁止 `test`/`verify`）⇒ F-2/F-3 的"行为不变"是**静态论证**；`List.copyOf` / `unmodifiableList` 的逐值等价**无运行时断言**。
* **门禁等价的 SpotBugs 未跑**（只有 aux 走 `~/.m2` 的替代自检，边界见 F-5-5）⇒ "四条 EI 消失"的**门禁确认仍须由控制方在下一轮 `clean verify` 取得**。
* **`ClassSlideSettlement.SlideRound.slides` 的冻结未做变异自证**（本批只对 `SettlementOrderBook` 的 4 条 EI 做了变异体证明）⇒ 它的判别力证据弱于 4 条 EI。
* **下游消费面未实测**：`householdsByRank()` / `zonesByRank()` / `SlideRound.slides()` 的调用方现在读到不可变视图（改动前这三者在各自构造路径上本来也已是 `List.copyOf` 的产物）⇒ 静态上无调用方会写它，但**未跑测试确认**没有人（含测试夹具）依赖可变性。
* **未 `git commit`**（控制方审后提交）；工作树仍只有 W4 批次的 4 个修改 + 3 个新增文件。

---

# W4-fix2（第 3 段市场冻结回归）

> 责任区：修 `EconomyCycleHealthTest.twoFullCyclesInRealThreePowersWorldKeepMoneyConservedAndMarketAlive` 的**真警报**
> （`clean verify` 实测里 app 侧 907 条中**唯一**一条红）。写码 Agent：W4-fix2 前台单代理；**未**改任何测试、
> **未** `git commit`、**未**跑 `verify`/`clean`/`package`；只动 `simos-economy/src/main/**` 的**一个**文件（`MarketSettlement.java`）。
> 编译：`tools/mvn-lock.sh -q spotless:apply` rc=0 → `tools/mvn-lock.sh -DskipTests compile -am` rc=0（16 模块全 SUCCESS）。

## G-1 现场（父批 `clean verify` 的读数，只读引用）

```
io.mosire.simos.app.time.EconomyCycleHealthTest.twoFullCyclesInRealThreePowersWorldKeepMoneyConservedAndMarketAlive
[第 3 段（关账日 day=90，trigger=PERIODIC）：成交笔数必须 > 0 … Expecting actual not to be empty]
```

★ **本条不只"第 3 段"坏**：把 W4 初版的**全部 48 轮**读数摊开看（`-Dsimos.economy.logLevel=DEBUG` 的
`MARKET_REPORT`），**15 轮 `fills=0`（day 13/15/18/63/65/68/70/73/75/78/80/83/85/88/90）**、
**13 轮 `fills=1`（day 8/10/20/25/28/30/43/45/48/50/55/58/60）**，其余 20 轮也都在个位数。
⇒ 段 1/段 2 之所以"过"，是**恰好各剩 1 笔**；第 3 段只是第一个掉到 0 的关账日。测试是"活判据"，
但它在这条回归上**判别力很钝**（`>0` 而已）——真实损伤是"市场从 day 8 起基本冻结"。

## G-2 根因（file:line 证据链）

**结论：W4 的买侧配给把 {@code matched} **预先切完**，切出来的份额是死的；而落账是逐 lane 判的
（自配对 / 币种 / 运力 / 现金腿四关）⇒ 高名次买槽吃不下（lane 不通）的那一份**整批丢掉**，
而唯一能在这条 lane 上成交的同格买槽已经被切成 0、**连 `pairUp` 的内层循环都进不去**。**

| # | 证据（W4 初版 file:line / 实测读数） | 结论 |
|---|---|---|
| 1 | `MarketSettlement.java:3734`（初版）`buyPartsByPriority(matched, weights)`：`parts[i] = min(weights[i], 剩余 matched)`，**逐槽把 matched 切完** | `Σ parts == matched`、且**排在后面的槽份额恒为 0** ⇒ 后位买槽在 `pairUp` 里 `need == 0`，`while (need > 0)` **一次都不进** |
| 2 | `pairUp`（初版 `:5551-5710`）：`for (i…) { need = buyParts[i]; while (need > 0) { … } }`，`sellerIndex/sellLeft` 跨买方**只前进不回退**；lane 不通时 `break`（`:5650` 运力 / `:5695` 现金腿 / `:5702` executeTrade=0） | 一个买方的 `need` 吃不下就**丢**；指针停在"没人能吃的卖槽"上 ⇒ 后面所有买方都在同一处 `break`（它们对同一卖槽既不吃也不推进）。**被丢掉的是整批 `matched`** |
| 3 | ★★ 实测（three-powers，`MARKET_PAIRING_SHORTFALL`，`day=90 commodity=grain region=c-tp-gold`）：`buyers=56 sellers=8 allocatedMilli=12178452 executedMilli=0 topBuyerAllocatedMilli=6347565 topBuyerHousehold=hh--3_1-rural-poor_peasant topBuyerHex=-3,1 topBuyerBlocked=LOGISTICS_CAPACITY firstSellerHex=-3,3 stalledSellerIndex=0 buyersInStalledSellerHex=4 buyersInStalledSellerHexWithZeroPart=4 buyersWithZeroPart=52 stopReason=execute-trade-returned-zero` | 前 4 名按名次划走**全部 12,178,452 毫**；指针**卡在 seller#0**（格 -3,3）；**该格 4 个买槽份额全 0**（唯一能同格成交的 4 户被切成 0）⇒ 该 (区,商品) 组**一笔不成**。同轮 `sellers=1` 的那条更极端：`topBuyerAllocatedMilli=5264394`（**单个买槽拿走全组**）、`executedMilli=0` |
| 4 | 同型读数（`day=3 commodity=grain region=c-tp-copper`）：`buyers=64 sellers=21 allocatedMilli=23114988 executedMilli=225656 lostMilli=22889332`（**99% 被丢**） | 不是 day=90 特有，**每轮都这样**；`lostMilli` 就是"切给吃不下的人"的量 |
| 5 | `MarketSettlement.java:5684-5697`（改后行号；M-A1 的既有机制）：`route == null && !buy.hex.equals(sell.hex)` 且 `!ctx.carrierPool.hasCapacityAt(sell.hex)` ⇒ `blockLaneWithoutCapacity` + `break` | three-powers 里**绝大多数格没有承运运力** ⇒ 跨格 lane 结构上不通，"同格买槽"是这些卖槽**唯一**的出口。把同格买槽切成 0 = 把这些卖槽判死（这解释了 `LOGISTICS_CAPACITY` 为何是最大归因档） |
| 6 | W2-fix 账本 `:814` 的绿轮读数：每段成交 33/24/32/133/39/42/37/129 笔 | 回归确实由 W4 引入；W4 之前每轮几十到上百笔，W4 初版掉到 0~1 笔 |

**为什么"只在第 3 段炸"**：不是第 3 段特殊，而是**W4 初版把每轮的成交压到 0 或 1 的边缘**（见 G-1 的 48 轮分布）；
段 1/段 2 恰好还有 1 笔（`>0` 判据放行），段 3 起连那一笔也没了。⇒ 断言只报"第 3 段"是**判据钝**，不是损伤局部。

**为什么"预分配"看着像"依次吃满"却不是**：`Σ parts == matched` 保证的是"账面上分完了"，而**不是"货交到能收货的人手里"**。
"依次吃满"的正确含义是**先到者先取、取不到的余量留给下一个**；W4 初版写成了"**先到者先把整批划走**"——
一字之差（划走 ≠ 取用），在 lane 会失败的世界里就是整组全灭。

## G-3 修法（只改买侧配给的**落账形态**；买槽序/区序/滑落一律不动）

| # | file:line（改后） | 改前一行 | 改后一行 |
|---|---|---|---|
| 1 | `MarketSettlement.java:3748` | `private static long[] buyPartsByPriority(long matched, long[] weights) { long[] parts = new long[weights.length]; long left = max(0, matched); for (…) parts[i] = min(max(0,weights[i]), left); left -= parts[i]; } return parts;` | `private static long[] buyPartsByPriority(long[] weights) { return weights; }`（**取用权 = 各槽自己的可付需求**；量上限交给卖侧份额 `Σ sellParts = matched` 在 `pairUp` 里封顶） |
| 2 | `:4624`（区内 `matchGroup`） | `long[] buyParts = buyPartsByPriority(matched, weights);` | `long[] buyParts = buyPartsByPriority(weights);` |
| 3 | `:5384`（跨区 lane） | `long[] buyParts = buyPartsByPriority(matched, tierBuyWeights);` | `long[] buyParts = buyPartsByPriority(tierBuyWeights);` |
| 4 | `:5542-5553`（`pairUp` 类注） | "预分配的 `buyParts` 只是上限" | 追加 W4-fix2 段：`buyParts` 是**取用权**、按 `buyers` 列表序依次取用、余量留给下一个 |

* **没动**：`sortBuySlotsByOrdering`（`:1781`，买槽按区名次 → 家户净值名次 → canonical）、
  `MatchContext.orderedRegions`（`:10266`，区序 = 上周期名义成交额降序）、`commitFreezes` 写序、滑落/迁移口径、冻结点、价格。
* **W4 排序语义为什么不变**：谁先拿货仍由 `ctx.buys` 的**次序**决定（`pairUp` 就是按这个列表序取用）；
  需求 > 供给时前面的照样先吃满（它把 `min(自己的可付需求, 剩货)` 全拿走），后面的吃剩下的。
* **与"预分配"的等价性（逐值）**：所有 lane 都通时两者是**同一个贪心** —— 归纳：预分配给第 i 名的
  `min(w_i, matched − Σ_{j<i} part_j)`，正是"按序取用时轮到第 i 名还剩多少"。⇒ 逐槽成交额**逐值相同**，
  差别只在 lane 不通时**不再把余量丢掉**。
* **日志（§一.9）**：新增 DEBUG `MARKET_PAIRING_SHORTFALL`（`:5794` helper，发射点 `:5589` / `:5694` / `:5771`）——
  具名给出 `buyTakingRightsMilli / sellOfferedMilli / executedMilli / lostMilli / stopReason`
  （`all-sellers-consumed-or-skipped` / `seller-hex-without-carrier-capacity` / `cash-leg-of-freight-or-tax-unaffordable` /
  `execute-trade-returned-zero`）+ 卡点卖槽与"该格有多少买槽没有取用权"。
  ★ 该日志**只读**：与本批的**非 DEBUG 轮**逐字节同摘要（见 G-4-3）⇒ "日志不影响状态"有实测。

## G-4 自证

1. **真命令**（任务书允许的那一条，**未加任何开关**）：
   `tools/mvn-lock.sh test -pl simos-app -am -Dtest='EconomyCycleHealthTest' -Dsurefire.failIfNoSpecifiedTests=false`
   ⇒ **rc=0**（未接管道，rc 直取）；`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 44.66 s`；
   `BUILD SUCCESS`；`surefire-reports/TEST-…EconomyCycleHealthTest.xml` mtime `2026-10-11 07:03:28`（本轮内）。
   ★ 修前同一命令：`rc=1`，`Tests run: 1, Failures: 1`（复现了 G-1 的红）。
2. **各段成交笔数（`simos-app/target/economy-cycle-invariant/summary.txt`，md5 `01cae26f167a7e61ee4d2fec36ecd48f`）**：

   | 段 | 1(day30) | 2(day60) | 3(day90) | 4(day120) | 5(day150) | 6(day180) | 7(day210) | 8(day240) |
   |---|---|---|---|---|---|---|---|---|
   | **W4 初版（红）** | **1** | **1** | **0** | — | — | — | — | — |
   | **W4-fix2（绿）** | **21** | **23** | **24** | **131** | 55 | 36 | 39 | 37 |

   ⇒ day30/day60 **确实被本修法改变**（1 → 21 / 1 → 23，与修前**不同**）；`0→120` 的第 2 个关账日（day120）**131 笔**，不再零成交。
   未成交归因里不再有"整组 `algorithm_uncovered` 吃掉一切"的形态（day90 `algorithm_uncovered` 从 227 笔 / 77,589,010 毫 降到
   219 笔 / 40,507,363 毫，且**同轮有 24 笔成交**）。
3. **全 48 轮无一冻结**（最终字节，`-Dsimos.economy.logLevel=DEBUG` 轮）：`fills` 分布 `17×10, 18, 21×7, 22×9, 23×5, 24×12,
   31, 34, 35×3, 36×4, 37×8, 38, 39×2, 40×13, 41×2, 44, 53×8, 54, 55×3, 56×2, 66, 131`，**`fills=0` 的轮次 = 0 个**（修前 15 个）。
   ★ 该 DEBUG 轮与第 1 条的非 DEBUG 轮**摘要 md5 相同**（`01cae26f…`）⇒ 日志不改状态、且世界可复现。
4. **未做**：本批没有新增护栏，故**没有**变异自证；判别力由"真世界 8 段读数 + 48 轮 fills"承担（G-3 的等价性论证是静态的）。

## G-5 会改变数值行为的清单（逐条具名）

1. **稀缺品的买侧落账形态**：份额从"预先切好的量"改为"取用权按结算序依次取用、余量留给下一个"。
   ⇒ ① 成交笔数与逐笔成交量变（day30 1→21 笔等）；② 被 lane 挡下时**不再销毁**那部分供给 ⇒
   库存/货币/债务读数全部随之改变（day120 粮 55,547,984 → 986,993,152 毫；债务本金 5,290,311 → 227,785,517 毫）；
   ③ 供给充足、且所有 lane 都通时**逐值不变**（G-3 的等价性）。
2. **新增只读 DEBUG 日志** `MARKET_PAIRING_SHORTFALL`（不写状态、不进算式、不改判据）。
3. `pairUp` 参数 `buyParts` 的**语义**（同 1；签名与实参个数未变）。
* **没有**新增/修改任何硬编码标定字面量；**没有**动买槽序、区序、冻结写序、滑落速度、迁移速度、价格口径。

## G-6 偏离记录 / 与 W4 账本的关系

1. **这是对 W4 §三-1 第 1 条（"买侧由按比例改为按序依次吃满"）的实现修正，不是设计变更**：
   约束设计书要的是"依次吃满"，W4 初版实现成了"依次划走"。修完仍满足判据③（排前面的先拿满），
   且把"划走吃不下就丢"这条**没人要的副作用**去掉。
2. **W4 账本 §三-3"会让既有测试断言失效的清单"仍然有效**，但失效**原因**从"预分配形态"改为"取用权形态"：
   凡断言"稀缺时逐户分配量/成交笔数"的市场类用例都可能变（供给充足时不变）。
3. **AGENTS §四（机制性描述回代码核）**：W4 账本 §一-1 说"改动前把买槽怎么排都不改变每户拿到的量"——
   那条**在买侧成立**，但**落账侧不成立**（本批的现场证据就是反例）：排序改变"谁先被 lane 判"，因而改变成交量。
4. 未触碰模块边界、权限单调性、五条铁律（买侧配给是纯算式，无状态形状变化 ⇒ 铁律 5 不触发）。

## G-7 未完成 / 未验证项（如实）

* **未跑 `verify`/`clean`**（任务书禁止）⇒ 其余 ~907 条 app 用例与 economy 侧市场用例**没有实测**；
  `MarketSettlement*Test` / `PortGateTaxAcceptanceTest` / `Z7MarketExclusionTest` 等**可能因本修法变红**（G-6-2 的清单），
  由控制方的门禁轮判定。
* **没有与"W4 之前"逐值对照**：要对照得回退 W4（本批禁止）；`21/23/24/131/55/36/39/37` 只能与 W2-fix 账本的
  `33/24/32/133/39/42/37/129` **比量级**（中间还隔着 W3 等批次）⇒ **不声称"恢复原值"**。
* **未做变异自证**（无新护栏）；**未跑任何其它测试类**；**未 `git commit`**。
* 世界里的 `LOGISTICS_CAPACITY`（three-powers 多数格无承运运力 ⇒ 跨格 lane 结构上不通）**未处理**：
  它是 M-A1 的既有机制，不是本批回归的原因，但它是"成交笔数量级只有几十"的**主因**——留给控制方决定是否另开一批。

---

# W4-fix3（NP 空值）

> 责任区：修 W4-fix2 新引入的**唯一**一条 SpotBugs 门禁红（`NP_LOAD_OF_KNOWN_NULL_VALUE`，fail-closed）。
> 写码 Agent：W4-fix3 前台单代理；**只改** `simos-economy/src/main/**` 的 **1 个文件**（`MarketSettlement.java`）；
> **未** `git commit`、**未**跑 `test`/`verify`/`package`/`clean`；**未**动任何数值口径（H-6 = 空）。
>
> ★★ **本批先纠正任务书的一条前提**：任务书说"某条路径上 `buyParts` 是已知 null（`:5561` 一带），`:5694` 是它的传参点" ——
> **实测不成立**。被加载的已知 null 是 **`route`**（局部变量槽 7），主行就是 **`:5694`**（W4-fix2 新增的第二个发射点，
> 落在 `if (route == null && 跨格)` 的 then 块内）。`buyParts` 在任何路径上都不可能为 null（H-1-5）。

## H-1 来路（file:line 证据链，不是猜）

1. 门禁产物 `simos-economy/target/spotbugsXml.xml`（父批 `clean verify`，mtime `07:07:42`；对应 class mtime `07:07:28`）
   逐条解析后**全模块只有 1 条** `BugInstance`：
   * `type = NP_LOAD_OF_KNOWN_NULL_VALUE`（rank 16 / priority 2 / category STYLE）
   * `Method = pairUp(MatchContext, List, long[], List, long[], long, RouteContext)`（`isStatic=true`）
   * `LocalVariable role=LOCAL_VARIABLE_VALUE_OF name=route register=7 pc=788`
   * `SourceLine start=5694 end=5694`（**primary**，唯一一条）
2. 复跑复核（同一命令形，见 H-5-1）控制台逐字：
   `Medium: Load of known null value in io.mosire.simos.economy.time.MarketSettlement.pairUp(…) At MarketSettlement.java:[line 5694] NP_LOAD_OF_KNOWN_NULL_VALUE`
   ⇒ 任务书里的 "line 5561（关联 :5694）" 是**读歪**：改前 `:5561` 是 `long executedTotal = 0L;`，`:5694` 才是**主行**。
3. `javap -c -l` 对**父批编译出的** class（md5 `67809b27…`）取 `pairUp` 行号表：`line 5694: 783`；pc 783 起正是"为
   `logPairingShortfall(…)` 压参数"的 `aload` 序列（783 ctx / 784 buyers / 785 buyParts / 786 sellParts / 788 sellers /
   **789 `aload 7` = route**）⇒ 该行确实是那次调用，SpotBugs 指的槽 7 就是 `route`。
4. **那条 null 路径**：改前 `:5689` = `if (route == null && !buy.hex.equals(sell.hex)) {`，`:5694` 的调用就在这个 then 块内
   ⇒ 走到这里 `route` **必为 null**（IsNullValue 判定"确定 null"），而调用把它当第 6 个实参压栈 = **已知 null 仍被加载/传递**。
   * 同一方法里另外两个发射点 `:5589` / `:5771` **没被报**：它们不在判空分支内（nullness 是两条路径的汇合 ⇒ 不确定）；
     `:5734`/`:5743` 传 `effectiveRoute` 也没被报（`:5712` 的判空在那里同样不是确定 null）。⇒ 检测器的判据是
     "**该 PC 上确定 null** + 该值此前被判过 null"，与本批修法方向一致（H-2/H-3）。
5. **`buyParts` 结构上不可能是 null**（任务书前提的证伪，三条独立证据）：
   * 它是 `pairUp` 的形参，方法体内**只有读、零赋值**（`grep -n buyParts` 的命中全是读）；
   * 两个调用点 `:4626` / `:5389` 传的都是 `buyPartsByPriority(...)` 的返回值，而该方法体就是 `return weights;`
     （`:3748-3750` = W4-fix2 的"取用权"形态）；
   * `weights`（`:4596`）与 `tierBuyWeights`（`:5356`）都是**当场 `new` 出来的数组** ⇒ 非 null。
   ⇒ 若 SpotBugs 真把 `buyParts` 当"已知 null"，只能来自方法内的赋值路径，而那条路径不存在。

## H-2 修法（把"判空"收口成具名、结构上非 null 的读数）

| # | file:line（改后） | 改前 | 改后 |
|---|---|---|---|
| 1 | `MarketSettlement.java:5569` | （无此值；三个发射点各自加载 `route`） | `final String routeKind = routeKindOf(route);`（**在 `:5695` 判空之前**一次算好） |
| 2 | `MarketSettlement.java:5601` / `:5706` / `:5783` | `route,` | `routeKind,`（三个发射点，`:5706` 就是被报的那一处） |
| 3 | `MarketSettlement.java:5802-5807` | （三元内联在日志构造里） | 新增 `routeKindOf(RouteContext)`：`null ⇒ "same-hex"`；`route.immediate ⇒ "intra-region-cross-hex"`；否则 `"cross-region"` |
| 4 | `MarketSettlement.java:5815`（形参）/ `:5878`（发射） | `RouteContext route,` / `route == null ? … : (route.immediate ? … : …)` | `String routeKind,` / `routeKind,` |

被报那一处的**前后各一行**（其余两处是同一替换）：

```
       sellParts,
       sellers,
-      route,          ← 判空分支内加载"已知 null"（SpotBugs 报的就是这一条）
+      routeKind,      ← 具名读数，结构上非 null
       plannedTotal,
```

判空的收口（**前** = 每个发射点各自 `route == null ? …`；**后** = 唯一拼写点）：

```
     long sellLeft = sellers.isEmpty() ? 0L : sellParts[0];
+    // ★★ W4-fix3（fail-closed 修 NP_LOAD_OF_KNOWN_NULL_VALUE）：路线种类读数**在判空之前**一次算好（具名）。
+    //   …（6 行注释，见源文件）
+    final String routeKind = routeKindOf(route);
     // ★★ W4-fix2 诊断（§一.9：关键判据 DEBUG + 具名理由）：…
     long plannedTotal = 0L;
```

```
     return executedTotal;
   }
 
+  /** ★★ W4-fix3：路线种类的唯一拼写点 … */
+  private static String routeKindOf(RouteContext route) {
+    if (route == null) {
+      return "same-hex";
+    }
+    return route.immediate ? "intra-region-cross-hex" : "cross-region";
+  }
+
   /** ★★ W4-fix2：一次配给的"没落账量"具名读数 … */
```

## H-3 为什么这条修法是 fail-closed

1. **零抑制、零糊弄**：`@SuppressFBWarnings` 本批 **0 处**、`assert` **0 处**、没有"null 就当空/当 0"的静默降级
   （`route == null` 这个**合法语义**被具名成 `"same-hex"`，而不是被吞掉）。
2. **修的是结构，不是报告**：改后 `pairUp` 内对槽 7（`route`）的加载只剩两处 —— `:5569`（**判空前**，nullness 未定）与
   `:5695`（判空本身）；判空之后**一次都不再加载**（javap 实测，H-5-6）。⇒ 同一条警告**无法复发**，
   除非有人再往判空分支里塞一次裸 `route`。
3. **不改变任何判据**：`:5695` 的守卫、`:5712` 的 `effectiveRoute == null`、运费/税/现金腿口径、买槽序、卖侧比例
   —— 全部原样；改的只是"喂给 DEBUG 日志的那个字符串从哪来"。
4. **日志面零漂移**：三个取值、字段名 `"route"`、发射条件、级别与改前**逐字相同**（同一段三元的搬移）⇒ H-6 为空。

## H-4 自扫（同形点：有没有别的"已知 null 还被加载/传递"）

* **机器扫描（门禁口径）**：父批那份报告里**全模块只有这 1 条**（H-1-1）⇒ 没有第二条会被这个检测器认出的同形点；
  修后全模块 **0 条**（H-5-4）。`NP_NULL_PARAM_DEREF*` / `NP_NULL_ON_SOME_PATH*` 两类也**一条都没有**。
* **手工同形扫描**（W4 碰过的 5 个文件：`MarketSettlement.java` / `EconomySettlement.java` / `SettlementOrderBook.java` /
  `ClassSlideSettlement.java` / `ClassLadderBook.java`）：
  * 粗扫（"某变量被判 `== null` 之后，又在别处被当值加载/传递"）命中 **30 处候选**（MarketSettlement 18 / EconomySettlement 12 / 其余 0）；
  * 收紧到**同形**（"在该变量自己的 `if (v == null) { … }` 块内把它当值加载"）后 ⇒ **0 处**（三个新类各 1~3 处判空、0 同形）；
  * 30 处粗候选逐条复核后**全部是假阳性**，分类（抽检原文）：
    - 守卫式早退（块首 `continue`/`return`/`throw` ⇒ 块后该变量必**非** null）：`MarketSettlement.java:1037-1040`、`:1705-1707`、`:8807-8809`、`EconomySettlement.java:4494-4496`、`:8907-8909`；
    - 块内**先重新赋值**再用：`MarketSettlement.java:7190-7192`（`shipmentId = "sh-" + …` 之后才放进 `batch`）、`:2363-2364`；
    - 用在 `if (v == null) … else` 的 **else** 支（该处 v 已知**非** null）：`EconomySettlement.java:1849-1852`、`:4374-4377`；
    - 我的正则把**注释**里的 `x == null` 当成了判空点：`MarketSettlement.java:5565`/`:5690`（W4 注释）、
      `EconomySettlement.java:5863`（提到 `scarcest == null` 的注释，真正的守卫在 `:5867` 是 `!= null`，且那正是 W2-fix3 修 `BX_UNBOXING_IMMEDIATELY_REBOXED` 的产物）。
  ⇒ **点名并修：0 处**；**点名不改：见 H-8**（那不是"已知 null 加载"，是"取值语义不准"）。

## H-5 自证（真命令与 rc；只读 SpotBugs，**非门禁等价**）

1. **复现轮（修前）**：`tools/mvn-lock.sh -Dspotbugs.failOnError=false spotbugs:check -pl simos-economy` ⇒ **rc=0**
   （`failOnError=false` 下 bug 只报不炸）、`BugInstance size is 1` / `Error size is 0`、逐字 `At MarketSettlement.java:[line 5694]`（11.5 s）。
   XML 副本 `/tmp/w4fix3-spotbugs-PREFIX-rerun.xml`（md5 `196e7275…`）。
2. `tools/mvn-lock.sh -q spotless:apply` ⇒ **rc=0**（`-q` 下日志 0 行）；**未**动其它文件：`git status --porcelain`
   前后逐行相同（仍是 W4 的 4 改 + 3 新增）；它只重排了本批新增的 javadoc（google-java-format 折行，语义未变）。
3. `tools/mvn-lock.sh -DskipTests compile -am` ⇒ **rc=0**、`BUILD SUCCESS`（7.9 s）；
   `target/classes/…/MarketSettlement.class` mtime `07:11:20`、md5 `67809b27…` → `c87c3b29…`
   ⇒ 分析器看到的确实**是另一份字节**（不是"没重编所以假绿"；W4-fix 的 F-5-5 那个 `cp -p` 陷阱本轮**未**踩）。
4. **修后同一自检** ⇒ **rc=0**、`BugInstance size is 0` / `Error size is 0`；XML 解析 `BugInstance 数: 0`（11.4 s）。
   ★ **终像上又跑了一遍**（同一命令，11.6 s）⇒ 仍是 `BugInstance size is 0` / `Error size is 0`；XML 副本
   `/tmp/w4fix3-spotbugs-POSTFIX-final.xml`。★ 两轮修后 class md5 **完全相同**（`c87c3b29…`）——
   两轮之间只改写了**一行注释**（`:5567` 的行号引用措辞，行数不变 ⇒ 行号表不变 ⇒ class 逐字节不变）
   ⇒ 首轮的绿对**终像**逐字节适用。
5. **可比性自证**（防止"分析器看的东西变了"造成假绿）：两轮 XML 的 `ClassStats` **类集合逐键相同、516 个类**，
   唯 `MarketSettlement` 的 size 变化（44715 → 44717，+2 字节 = 本批改动）；两轮 `missing aux classes` 清单**逐条相同**
   （17 个 `io.mosire.simos.economy.api.*`）。⇒ 1 → 0 的差只能归因于本批那 2 个字节。
6. **`javap` 复核（修后 class）**：`pairUp` 内对槽 7 的加载 = `pc 25`（行号表 `line 5569`，即 `routeKindOf(route)`）与
   `pc 713/717`（`line 5694` 赋值 + `line 5695` 判空）；`pc ≥ 719` 之后**再无** `aload 7`。
7. ★ **证据强度边界（如实）**：
   * 该自检**不是门禁等价**：aux classpath 走 `~/.m2` 的旧 SNAPSHOT，17 个 `io.mosire.simos.economy.api.*` 类**缺失**
     （两轮相同）⇒ 与 `clean verify`（反应堆内 jar）不等价；**门禁确认须由控制方下一轮 `clean verify` 取得**。
   * 本条的判定是**类内**（同一方法内局部变量的 nullness 数据流），不依赖 aux ⇒ "这一条已消失"可靠；
     "全模块 0 条"仍受 aux 差异限制。
   * `target/spotbugsXml.xml`（门禁原始产物）**先备份后还原**：还原后 md5 `62b2b10816ef8c064937376a2f642380`、
     126429 字节，`cmp` 与备份**逐字节相同**（mtime 变新、字节未变 —— 按 AGENTS §三"mtime 不是判据，md5 才是"）。
   * 本批**没有**做变异自证：判别力由"修前 1 条 / 修后 0 条"两轮**同一个检测器 + 同一套类集**承担（已内在于 H-5-1/4/5），
     不再另造变异体。
   * 源码 md5 链（`md5sum` 实跑，非推算）：改前 `02c55b37b4e6d947e671670591d0709d`（555730 字节）
     → 首轮修后 `97875458443f55ca2f34f58c0b286ef3`（557257 字节）→ **终像** `8d8107ccaa00fe2383449db94b560b67`（557260 字节）。
     前像**不是**先记录的后像，而是由本批 7 处字面替换**逆向重建**（`/tmp/w4fix3-before.java`），
     与改动前 `read` 到的 `:5553-5560` / `:5689` / `:5694` 文本逐字一致；`diff -u` 显示前像→终像的差异
     **恰为本批 7 处 + 终像那 1 行注释改写**，无其它字节变化。

## H-6 会改变数值行为的清单

**空。** 逐条理由：

* 改动只是把**同一段三元**从日志构造处搬到 `routeKindOf` —— 三个取值、字段名 `"route"`、发射条件
  （`MARKET.isDebugEnabled()` + `executedTotal < ceiling`）、级别（DEBUG）**逐字未变**；
* `routeKindOf` 是纯函数（无状态、无分配 —— 返回字符串字面量）、**不进任何算式**、不写任何状态、不发射日志；
* 唯一执行面变化：`routeKindOf(route)` 现在**每次 `pairUp` 调用求值一次**（改前只在真要发日志时求值）⇒ 一个三元判断，
  无副作用、无可观测差异；
* **没有删掉任何"可能传 null"的分支**：`route == null` 的守卫（`:5695`）、`effectiveRoute == null` 的判据（`:5712`）
  与两个入口的实参全部原样保留；
* 硬编码标定字面量：**无新增/无变化**；买槽序、卖侧比例、取用权、配给、滑落、价格口径**一字未动**。

## H-7 未完成 / 未验证项（如实）

* **未跑 `test`/`verify`/`package`/`clean`**（任务书禁止）⇒ **门禁等价的 SpotBugs 绿与全部测试未实测**。
  ★ 已 grep 确认日志面的测试耦合为 0：`simos-economy/src/test` + `simos-app/src/test` 里对
  `MARKET_PAIRING_SHORTFALL` / `same-hex` / `intra-region-cross-hex` / `cross-region` **零命中** ⇒ 本批不改断言
  （但"没跑"仍是没跑）。
* **未做变异自证**（理由见 H-5-7 末条）。
* **未做日志实证**：没跑 `-Dsimos.economy.logLevel=DEBUG` 轮去比对 `MARKET_PAIRING_SHORTFALL` 的 `route=` 取值与改前逐字相同
  （目前只有静态论证：同一段三元、同一输入）；留给测试批。
* **门禁确认仍待控制方**（下一轮 `clean verify`）。
* **未 `git commit`**；工作树仍只有 W4 批次的 4 个修改 + 3 个新增（本批只动其中 `MarketSettlement.java` 1 个）。

## H-8 点名不改（同族风险，如实记）

1. **`:5706` 那次发射的 `route=` 读数在语义上不准**（W4-fix2 既有形态，**不是**本批引入，本批**不改**）：
   该发射点只在 `:5695` 的"`route == null` **且** 跨格"分支内触发，`stopReason = "seller-hex-without-carrier-capacity"`；
   但传进去的 `route` 是 null ⇒ `routeKindOf` 打出 `route=same-hex`，而这条 lane **不是**同格（恰恰是"跨格 + 发货格没有运力"才被拦）。
   根因是 `"same-hex"` 这个取值把"**没给**预建路线"与"同格"两件事合并成了一个词。
   ⇒ 建议（**未实施**，因为它会新增第 4 个取值 = 日志面口径变更，超出"不改 W4 口径"的本批范围，需控制方裁定）：
   那一处单独传一个具名值（如 `route=cross-hex-no-carrier-capacity`），或把 null 的取值改名为 `no-route`（会影响另外两个发射点）。
2. `route` 是**可空设计**的形参（null = 无运费路线）：本批只把它的"判空"收口，**没有**把可空性改成 sentinel
   （那会动到 `:5695` / `:5712` 的判据与运费口径 —— 正是任务书禁止的"顺手改 W4 数值语义"）。

---

# W4-fix4（route 取值如实）

> 来路：H-8 第 1 条点名的**读数不准**（`seller-hex-without-carrier-capacity` 那次发射打出 `route=same-hex`，而那条 lane 恰恰**不是**同格）。
> **控制方已裁定**（null ⇒ `no-route`；该发射点用具名值 `cross-hex-no-carrier-capacity`；其余取值/字段名/级别/事件名逐字不变）。
> 本批**纯日志**，只动 `MarketSettlement.java` 一个文件。行号一律为**改后**行号（W4-fix3 台账里的 `:5706` 是改前行号 ⇒ 现在是 `:5706` 调用、`:5712` 实参）。

## I-1 改动点（file:line + 前后各一行）

| # | 位置 | 改前 | 改后 |
|---|---|---|---|
| 1 | `MarketSettlement.java:5812`（`routeKindOf` 的 null 分支） | `    if (route == null) {` / `      return "same-hex";` | `    if (route == null) {` / `      return "no-route";` |
| 2 | `MarketSettlement.java:5712`（`seller-hex-without-carrier-capacity` 那次发射的路线实参） | `                sellers,` / `                routeKind,` | `                sellers,` / `                "cross-hex-no-carrier-capacity",` |
| 3 | `MarketSettlement.java:5564-5572`（`pairUp` 头注）与 `:5800-5809`（`routeKindOf` Javadoc） | W4-fix3 的"三个取值…逐字相同"叙述 | 补 W4-fix4 具名说明（**注释级，无执行面**） |

`routeKindOf` 改后取值表（纯函数，`route` 是唯一输入；结果**只**作日志实参）：

| 输入 | 取值 | 与改前 |
|---|---|---|
| `null`（调用方没给预建路线） | `"no-route"` | **改名**（改前 `"same-hex"`） |
| `route.immediate == true` | `"intra-region-cross-hex"` | 逐字不变 |
| `route.immediate == false` | `"cross-region"` | 逐字不变 |

## I-2 三个发射点各自打出的 `route=`（改前 → 改后）与"为什么这样才如实"

三个 `logPairingShortfall(...)` 调用点；字段名仍 `route`、事件名仍 `MARKET_PAIRING_SHORTFALL`、级别仍 DEBUG（裁定第 3 条）。

| # | 发射点 | 触发条件（结构上的位置） | `route=` 改前 → 改后 |
|---|---|---|---|
| A | `:5599`（`stopReason="all-sellers-consumed-or-skipped"`） | 内层循环里卖侧被吃完/跳过 | 实参 `routeKind`：`route==null` 时 `same-hex` **→ `no-route`**；非 null 时 `intra-region-cross-hex` / `cross-region`（**逐字不变**） |
| B | `:5706`（`stopReason="seller-hex-without-carrier-capacity"`） | 只在 `:5699` 的 `route == null && !buy.hex.equals(sell.hex)` 分支内、且 `:5700` `!ctx.carrierPool.hasCapacityAt(sell.hex)` 时 | `same-hex`（**假读数**）**→ `cross-hex-no-carrier-capacity`** |
| C | `:5783`（方法末尾兜底发射） | 走到末尾仍 `executedTotal < ceiling` | 同 A（`same-hex` **→ `no-route`**；另两值不变） |

**为什么这样才如实**：

* **B 是唯一能确知真相的发射点**：`:5699` 刚判过"`route == null` **且** 买卖不同格"、`:5700` 刚判过"发货格无承运运力" ⇒ 这一笔的真相是
  "跨格 + 发货格无运力"。旧值 `same-hex` 不只是含糊，而是**与事实相反**（恰恰不是同格）；改后具名值与 `stopReason` 同族，
  读者一眼能判"为什么被拦"。泛化值 `no-route` 在这里也不够（它只说"没给预建路线"，说不出跨格与运力为何拦下）。
* **A / C 不能从 `route == null` 推出"同格"**：这两处的 null 只证明"调用方没给预建路线"（同格腿确实是这种形态，但跨格腿在
  有运力时会由 `intraRegionFreightRoute` 现场合成 `effectiveRoute`，而**入参 `route` 仍是 null**）。
  ⇒ 改后 `no-route` 只陈述**可证事实**（没给预建路线），不再替读者推断"同格"；非 null 两值逐字不变。
* **字段名不改**（仍 `route`）：读者侧（log4j2 配置、日志消费、既有检索习惯）零漂移；改字段名会把"读数更如实"变成"口径迁移"。

## I-3 自证（真命令 + rc；跑前确认本仓无其它 Maven）

```
$ pgrep -af "classworlds.launcher|surefirebooter" | grep -i SimulatorMosire   # 只有我自己那条 grep 命令本身
$ tools/mvn-lock.sh -q spotless:apply ; echo "[spotless rc=$?]"
[spotless rc=0]
$ tools/mvn-lock.sh -DskipTests compile -am > /tmp/w4fix4-compile.log 2>&1 ; echo "[compile rc=$?]"
[compile rc=0]          # 日志尾部：[INFO] BUILD SUCCESS / Total time: 7.136 s
```

* **真编（非 up-to-date）**：`simos-economy` 段 `Recompiling the module because of changed source code.` +
  `Compiling 211 source files with javac [debug release 21] to target/classes`；
  `MarketSettlement.class` mtime `07:16:09` > 源文件 mtime `07:16:02`（同一轮，`date` = `07:16:17`）。
* `checkstyle:3.6.0:check` ⇒ `You have 0 Checkstyle violations.`；`enforcer` 模块边界 `BannedDependencies passed`。
* **`spotless:apply` 没改别的文件**（§五.1 纪律）：跑前跑后 `git status --porcelain` **逐行相同**
  （4 个 ` M` + 3 个 `??`，都是 W4/W4-fix/fix2/fix3 的既有改动）；`find … -name "*.java" -newermt "-10 minutes"` 只有 `MarketSettlement.java`。
* **日志面测试耦合复查（只读）**：全仓 `*.java` 里带引号的四个取值与 `MARKET_PAIRING_SHORTFALL` —— 除本文件自身外
  **零命中**（`simos-economy/src/test`、`simos-app/src/test`、`simos-app/src/main/resources/log4j2.xml` 均无）。
  ★ `EconomyMigrateHouseholdHandler.java:85` 也有 `"same-hex"`，但那是**另一件事**（`ECONOMY_MIGRATE_HOUSEHOLD_REJECTED` 的
  `reason`，判据是 `view().hex().equals(to)` = **真同格**）⇒ 语义正确、不在本批范围、**未动**。

## I-4 会改变数值行为的清单

**空。** 逐条理由：

* 改动 1 只换 `routeKindOf` 的**返回字面量**；该函数纯函数、无状态、无分配、不发射日志，结果**只**作为 `logPairingShortfall`
  的实参（`:5605` / 传参点 `:5789`）。`logPairingShortfall`（`:5823-5941`）通读确认：只读状态 + 局部计数 +
  `EventLog.channel(MARKET).debug(...)`，**无任何写口**（不改 `buy.blocked`、不改 `remaining`、不改池子）。
* 改动 2 把同一位置的日志实参换成另一个字符串字面量 —— 判据/分支条件（`:5699` / `:5700`）、配额、顺序、算式**一字未动**；
  **没有删/合并任何分支**。
* 改动 3 是注释/Javadoc（无字节码差异）。
* 硬编码标定字面量：**无新增/无变化**；买槽序、卖侧比例、取用权、配给、滑落、价格口径、运力判据全部原样。

## I-5 偏离记录

**无。** 三条裁定逐条落地（null ⇒ `no-route`；B 点具名 `cross-hex-no-carrier-capacity`；A/C 实参、字段名、级别、事件名不变）。
未新增第 5 个取值以外的东西：本批的取值集合 = `no-route` / `intra-region-cross-hex` / `cross-region` / `cross-hex-no-carrier-capacity`（4 个）。

## I-6 未完成 / 未验证项（如实）

* **未跑真日志实证**：没有起 `-Dsimos.economy.logLevel=DEBUG` 的世界去比对 `MARKET_PAIRING_SHORTFALL` 的 `route=` 四种取值
  （目前只有静态论证：取值表 + 三个发射点实参 + `logPairingShortfall` 只读通读）。留给测试批/控制方。
* **未跑 `test`/`verify`/`package`/`clean`**（任务书禁止）⇒ SpotBugs 与全部测试**未实测**；本批**未做变异自证**（纯字面量改动，
  变异只能靠日志实证，见上条）。
* **未 `git commit`**；工作树仍只有 W4 批次的 4 改 + 3 新增（本批只动 `MarketSettlement.java` 1 个）。
* H-8 第 1 条由"点名不改"**转为已实施**（按裁定）——H-8 那一段是历史留痕，不回改。
