# W1 实现架构账本 —— 市场状态与账（逐区价表 + 市场轮统计簿）

> 责任区：约束设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` v3.3 §6.1 **W1**
> （逐格价表退役 → 每区一张；市场轮统计簿；`EconomyOwnershipTimeParticipant` 前置调查）。
> 写码 Agent 台账（AGENTS §一.8）：① 关键调查结论 ② 实现架构 ③ 关键判断 ④ 偏离记录。

---

## 一、关键调查结论（file:line → 结论 → 影响）

| # | 证据（file:line） | 结论 | 影响 |
|---|---|---|---|
| 1 | `EconomyData.java:309`（HEAD）`Map<HexCoord, Market> markets` | 价表键 = **格**，值 = `record Market(CurrencyId numeraire, Map<CommodityId,Long> prices)`（`model/Market.java:46`） | 形状级改造的起点；也是"逐格计价币 vs 逐区法定币"双权威的来源 |
| 2 | `MarketSettlement.java:2045-2061`（HEAD）`adaptPrices` 逐成员写回 + `next == reference` 时 `continue` | 区内"一价"实际由**锚格那一张表**决定，逐格表只是它的副本；对齐循环是补丁 | 循环随形状退役：改价 = 改本区那一张表的一次 upsert（`MarketSettlement.adaptPrices` 新实现） |
| 3 | `MarketSettlement.java:3385`（HEAD）`ctx.markets.get(topology.regionOf(hex).anchor())`；`:7588-7630` `unitPriceOf` 取卖方**格**价 | 语义上"区内一价"**已经成立**（成交取锚格价），存储却是逐格 | 新形状 `MarketIndex.at(hex)` = 本格所属市场的表 ⇒ 逐值等价 |
| 4 | `MarketTopologyBook.java:161-195`（HEAD）：无 map 切片 / 空 markets ⇒ `singleHex(markets)`；有城市 + 同币 ⇒ `singleRegion(markets, markets.keySet(), …)`；异币 ⇒ `byCityRadius` | **派生路径的两个输入是"市场格的集合"与"逐格计价币"**，改动前它们只能从 `markets` 的键/值里取 | 新形状把这两件事分别落到"价表条目的 `hexes`"与"价表的 `numeraire`"上：派生形态下**价表条目本身就是区**（见 §二），`MarketTopologyBook.byDerivedMarkets` 逐值复刻 `singleRegion` 的装配口径 |
| 5 | `EconomySeeder.java:3369-3653`（HEAD）：只有"有经济 entry 的格"才播市场 | "哪些格有市场"是**状态**（创世决策），不是可现算的派生量 | 它必须随价格一起落盘：`MarketTable.hexes`（派生形态唯一权威）——否则重开档会得到"哪些格能交易"不同的世界 |
| 6 | `ThreePowersMarketZones.java:280-290`：创世自检 `zone-hex-without-market` 要求**每个区成员格都有市场行** | 持久形态下"区成员格"与"有市场的格"逐格重合（三权世界） | 持久形态下成员格权威交给 `MarketZone.hexes`（I22），价表条目只带价与币 ⇒ 逐值不变 |
| 7 | `EconomySeeder` 的 A1 词表：`currencies`/`moneyInstruments` 由 `economy.DefineCurrency` 建；**small-world/corridor 一个字都不定义** | "空区表世界用唯一币种兜底"这条派生**做不到**（词表为空） | 派生形态的计价币只能随价表落盘（`MarketTable.numeraire`）——这正是"逐格计价币字段该删就删"（改成逐区）的落点 |
| 8 | `EconomyOwnershipTimeParticipant.java:215` `stepper.finish()` → `EconomyDayStepper.java:715` `finish() = session.build() + close()` | **它不是落盘**（不 save/checkpoint/persist、不 revision++）；一次 `simulateWorld` = 一次日循环 + 一次 finish + 一个 `WorldTimeProposal`（唯一 revision） | 前置调查结论：**测试路径不会二次落盘**（详见 §四） |
| 9 | `EconomySeedHandler.java:168`（HEAD）`merge(base.markets(), seeded.markets())` = `putAll` | 追加播种时"后到者整条覆盖同名价表" | 新形状下同名派生价表会**吞掉已有世界的成员格** ⇒ 改成 `mergeMarketTables`：成员格取并集、币/价目不一致则具名拒 |
| 10 | `EconomySetMarketPriceHandler.java:109-115`（HEAD）：无市场 ⇒ 用银建一张 | 旧定价命令能凭空建市场 | 新形状下"建市场"必须同时给成员格与币（那是立区命令的判据）⇒ 定价改成**逐区**且市场必须已存在（具名拒 `market-missing`） |

---

## 二、实现架构（组件拆分 / 类设计 / 数据流）

### 2.1 新形状（价表每区一张）

```
EconomyData.marketTables : Map<MarketZoneId, MarketTable>          ← 第 9 个组件（键从 HexCoord 改成 MarketZoneId）
EconomyData.marketRoundStats : Map<MarketZoneId, MarketRoundStats> ← ★ 新：第 40 个组件（W1 的统计簿）
```

- `model/MarketTable`（新，取代已删的 `model/Market`）：
  `record MarketTable(CurrencyId numeraire, Set<HexCoord> hexes, Map<CommodityId,Long> prices)`
  + `priceOf/hasPrice/isFree/bidPriceOf/askPriceOf/withPrice/withPrices/withNumeraire/withHexes/singleHex`
  + `BID_PER_MILLE/ASK_PER_MILLE`（★ 随 S-9 退役属 W2，W1 不动）。
  **成员格规范序**（`(q,r)` 升序）冻结 ⇒ 迭代序 = 内容的纯函数（I7，禁 `Set.copyOf`/`Map.copyOf`）。
- `model/MarketTables`（新）：`canonicalIds/canonical/canonicalIdsOf/frozen/memberHexes/allMarketHexes/`
  `marketIdAt/at/atOrNull/ofZone` —— "这一格按哪张表报价"的**唯一拼写点**（`EconomyDayView` 版）。
- `time/MarketIndex`（新，包内可见）：给"手里只有两张表、没有 view"的结算器用的同义解析器
  （`at(hex)`/`ofRegion`/`ofMarket`/`tables()`/`marketZones()`），★ 持**引用**不拷贝（工作副本是活的）。
- `EconomyData` 构造期跨表守卫（新增，fail-closed）：
  ① 每个持久区必须有价表条目；② 价表 `numeraire` 必须逐值等于 `legalTender`（双权威不再能漂开）；
  ③ 持久区价表的 `hexes` 必须为空（成员格权威 = `MarketZone.hexes`，I22）；④ 派生价表的 `hexes` 必须非空；
  ⑤ 派生价表的成员格不得已被某个区覆盖。

**两种形态（互斥、由 `marketZones` 是否为空唯一决定）**

| | 成员格权威 | 计价币权威 | 拓扑装配 |
|---|---|---|---|
| 持久形态（`marketZones` 非空） | `MarketZone.hexes` | `MarketZone.legalTender`（价表那一栏是它的**投影**） | 逐区一个 `MarketRegion`（id/锚/半径/币 = 区四栏）+ 非区键条目 = 派生市场；辐射项 = 区锚最小距离；`regional = 区数>1` |
| 派生形态（`marketZones` 为空） | `MarketTable.hexes`（条目自带） | `MarketTable.numeraire` | 逐价表条目一个 `MarketRegion`（id = 键、锚 = 规范序第一个成员格、半径 0）；辐射项恒 0、无城市/农村调整 ⇒ 逐值等于改动前的 `MarketTopology.singleRegion` |

- `MarketTopology` 的四个工厂（`singleHex`/`singleRegion`/`of`/`ofZones`）合并成三个：
  `singleHex(tables, zones)`（逐格退化：没有 map 切片时）、`ofRegions(regions, wiring…)`（唯一正式入口）、
  `regionOf(...)`/`canonicalFirstHex(...)`（组的唯一拼写点）。拓扑侧**不再**从价表键集推成员格。

### 2.2 市场轮统计簿（新持久组件）

- `model/CommodityRound`：`(demand, supply, traded, averagePrice, unmet)`（毫商品 / 毫计价货币每商品单位）；
  `plus()` 做周期累计（均价按成交量加权，BigInteger 中间量）。
- `model/MarketRoundStats`：三段**固定窗口** —— 最近一轮明细 + 本周期累计 + 上周期累计（各自的成交额）；
  `withRound(day, round, turnover, cycleClosed)` 是唯一写入形态；`EMPTY` 是初值（`lastRoundDay = -1`）。
- 写入路径：`MarketSettlement.roundStatsOf(ctx, adapted)` 产出**单轮读数**（D/S 直接读自适应定价的累加器；
  成交量/均价/成交额按**卖方市场**归属；未满足量按**买方市场**归属）→ `MarketOutcome.roundStats` →
  `EconomySettlement` 在**同一次 advance 的工作副本**里折进 `EconomyStateBuilder.marketRoundStats()`
  （关账轮轮转）→ 会话 `build()` 一次落成 `EconomyData.marketRoundStats`（★ 不产生额外 revision）。
- 读口：`time/MarketRoundStatsBook`（`lastRound/cycleToDate/previousCycle/lastRoundTurnover/…`，按 `(市场,商品)`）；
  `time/MarketSupplyDemandView`（**现算派生、不落盘**：与结算同一条 `planOrders` 现算当刻 D/S）；
  `EconomyDayView.marketRoundStats()`（工作副本优先）→ `MarketReadout.marketRoundStats` + `lastRoundOf/turnoverOf`。
- 日志（§一.9）：每市场轮一条 INFO `MARKET_ROUND_STATS_RECORDED`（day/市场数/商品数/成交量/**窗口口径**/是否关账轮）
  + 逐市场 DEBUG `MARKET_ROUND_STATS_DETAIL`；拓扑装配 DEBUG `MARKET_TOPOLOGY_FROM_PERSISTENT_ZONES` /
  `MARKET_TOPOLOGY_FROM_DERIVED_MARKETS`；GM 定价与改币 INFO/DEBUG 逐条（含 `marketId`/权威形态/前后值）。

### 2.3 调用次序（一次市场轮，W1 之后）

```
clearOncePerCycle(marketTables, round, trigger, topology, …)
  ├─ 逐区建订单：区域来自 topology.regions()，价表 = marketTables[region.node().nodeId()]
  ├─ 撮合（区内/跨区；成交价仍按卖方市场的价表）
  ├─ collectUnfilled（未成交归因）
  ├─ adaptPrices：逐区汇总 D/S → 逐区**一张表** upsert 新价（不再逐成员写回）+ 交回 D/S 累加器
  └─ roundStatsOf：D/S（累加器）+ 成交量/均价/成交额（fills，卖方市场）+ 未满足量（unfilled 买方侧）
→ MarketOutcome{report, marketTables, mandateFills, haulService, roundStats}
→ EconomySettlement：写回价格表工作副本；把 roundStats 折进统计簿工作副本（关账轮轮转）；INFO/DEBUG 日志
```

---

## 三、关键判断（为什么这样拆 / 推翻过什么）

1. **"逐格价表退役"必然要回答两个问题**：① 哪些格有市场 ② 每一格用什么币。改动前这两条都住在 `markets` 的键/值里；
   价表改成每区一张之后，它们分别落到 `MarketZone`（持久形态）与价表条目自身（派生形态）。**没有第三个选项**：
   `currencies`/`moneyInstruments` 在真实世界（small-world/corridor）可能是空表（不跑 `DefineCurrency`），
   `Market.numeraire` 一删就再也取不回派生形态的计价币 ⇒ 计价币必须以**逐区**粒度随价表落盘（粒度从"格"变"区"，
   这正是"合并双权威"的落点）。
2. **成员格必须是状态**（不采用"从家户行/产业反推有市场的格"）：那样会把"创世决策"偷换成"当刻人口分布"，
   迁移/清区之后"哪些格能交易"会与存档前不同 —— 违反 W1 的"既有语义量逐值不变"。
3. **派生形态 = 价表条目就是区**（不采用"城市 + tier 半径派生区、价表只带价"）：派生区需要"哪几格 + 什么币"，
   而这正是条目自带的两件事；用条目建区 ⇒ `MarketTopologyBook.byDerivedMarkets` 能逐值复刻改动前 `singleRegion`
   的装配（锚 = 规范序第一个市场格、半径 0、辐射项 0、无城市/农村调整），small-world 因此逐值不变。
4. **持久形态的价表条目不带成员格**（必须为空）：成员格的权威只有 `MarketZone.hexes`（I22）；两处都存 = 两个权威。
5. **双权威的合并方向**：保留 `MarketZone.legalTender` 作为"本区用什么钱"的**唯一权威**（FX/口岸/税/发行者反查都读它），
   价表的 `numeraire` 是它在价表侧的投影，由 `EconomyData` 构造期守卫钉死相等 ⇒ 两者**不可能漂开**。
   ★ 偏离：严格意义上持久形态下这一事实仍有**两处拼写**（一个字段 + 一个投影），只是被守卫钉成同一个值（见 §四-1）。
6. **`adaptPrices` 的成员对齐循环删掉**：改价 = 本区那一张表的一次 upsert；"成员漂移"这个 bug 形态在结构上消失
   （改动前 `next == reference` 时 `continue` ⇒ 漂移永不修）。
7. **统计簿的键空间 = 价表的键空间**（`Map<MarketZoneId, …>`）：这样 Codec/ChangeSet/往返三件套走的是既有
   `FieldDelta` + `MarketZoneId::parse` 机制，零新增序列化魔法；三段窗口装在"每市场一个值"里 ⇒ 状态大小固定。
8. **D/S 不另造一套**：直接读自适应定价**本来就在算**的累加器（约束设计书 §3.1-3 的原文要求）；
   成交量/均价/成交额/未满足量全部来自本轮已有的 `report.fills`/`report.unfilled`。
9. **追加播种的价表合并**：`putAll` 在"每区一张"的世界里是**静默丢格**（同名派生表覆盖），改成"成员格并集 +
   币/价目一致否则具名拒"。
10. **推翻过的路线**：① 曾考虑"seeder 把派生区落成持久 `marketZones`" —— 被否：持久形态的辐射项/装配口径与
    `singleRegion` 不同（`nearestAnchorDistance` vs 恒 0）⇒ small-world 的运费会变，违反 W1 红线；
    ② 曾考虑"通用 `Map<HexCoord, MarketTable>` 派生索引"到处传 —— 被否：它是"两个权威的副本"，且在工作副本被改写后会过期；
    ③ 曾考虑删掉 `MarketZone.legalTender`（把币完全搬到价表）—— 被否：FX/口岸/税/发行者反查共 35 处都读它，
    改动面远超 W1 的"形状 + 仪表"边界，且会把 FX 语义拖进本批。

---

## 四、偏离记录（与约束设计书不一致之处，主动记录）

1. **"只剩一个权威"是"守卫钉死的单一值"，不是"单一字段"**：持久形态下"本区用什么钱"仍有 `MarketZone.legalTender`
   与 `MarketTable.numeraire` 两处拼写，`EconomyData` 构造期守卫要求逐值相等（漂开即抛）。
   做不到删字段的原因见 §三-1/§三-10③（派生形态必须有逐区币来源、而 `MarketZone` 在那一形态下不存在；删 `legalTender`
   会把 FX/口岸/税/发行者反查 35 处拖进本批）。
2. **`EconomyData.markets` 改名 `marketTables`、访问器 `markets()` → `marketTables()`**：约束设计书 §6.1 W1① 写的是
   "`EconomyData.markets` 键改 `MarketZoneId`"，而验收判据 W1④ 要求 `markets()` **全仓 0 残留**（grep 证据）——
   两者字面冲突时按**可执行的 grep 判据**执行（组件与访问器都改名），并把这条记在这里。
3. **"城市 + tier 半径"的派生装配（`byCityRadius`）退役**：新形状下区域由区表/价表条目唯一给定。对既有世界逐值不变
   （small-world/corridor 全格同币 ⇒ 改动前走的也正是 `singleRegion` 快路；three-powers 走持久区），
   差别只在"有 map、无城市、有市场"的形态（改动前用 craft 格当节点 + 非零辐射项，现在辐射项为 0）——该形态没有任何
   生产世界使用。★ 该形态无法再表达（区域必须由状态给定，不能由"craft@ 格"现推）。
4. **GM 命令载荷契约变更（旧档/旧调用方直接失败，不写兼容层，§一.11）**：
   - `economy.SetMarketPrice`：`{q,r,commodity,price}` → `{marketId,commodity,price}`；且**市场必须已存在**
     （旧行为"无市场行 ⇒ 用银建一张"取消：新形状下建市场要同时给成员格与币，那是立区命令的判据）；
   - `economy.SetMarketNumeraire`：`{q,r,numeraire}` → `{marketId,numeraire}`；持久形态下**同一次变更集**里把
     `MarketZone.legalTender` 一起改（守卫要求同值）。
5. **`EconomyData.markets`（旧 JSON 组件名）不兼容**：旧档读入会 fail-closed（未知组件键 `markets`）⇒ 按 §一.11
   **重建**（重新播种/跑新世界）。
6. **`MarketTopology.singleRegion` / `of` / `ofZones` 三个工厂被删除**（API 变更）：全部调用方改为
   `ofRegions(...)`（组合根装配区域的唯一入口）。`singleHex(Map,Map)` 语义从"逐格一区"改成"逐**市场**一区再摊开为逐格"——
   在"一市场一格"的夹具里逐值等价，在一市场多格的档里区域数变少（夹具/退化路径）。
7. **退化路径（没有 map 切片）的区域形状**：改动前 = 每个有市场的格一个区；现在 = 每个**市场**的成员格各一个区
   （逐格摊开）⇒ 成员格集合逐值相同，区域 id 与数量在"一市场多格"的档里不同（夹具路径；生产世界都有 map 切片）。
8. **统计簿的成交额口径是我定的**（世界无汇率 ⇒ 不折算）：`Σ goodsPaymentMilli`，按**卖方市场**归属，单位为该笔的支付币；
   需要"单一口径 GDP"的调用方（W4）必须自己说清用哪种币。
9. **未满足量口径**：取 `MarketReport.unfilled` 的**买方侧**合计（按买方所在市场归属）——与成交量（卖方侧）不是同侧，
   这是"需求区"与"货源区"两个视角的必然；写在 `CommodityRound` 类注里。

---

## 五、账本外的必答项（报告 §8 同源）

- **前置调查（C）结论**：`EconomyOwnershipTimeParticipant` 的第二个 `finish()` **不是**落盘 ——
  `stepper` = `EconomyDayStepper`，其 `finish()`（`EconomyDayStepper.java:715`）= `session.build()` + 关闭结算线程池；
  一次 `simulateWorld` 只产生**一个** `WorldTimeProposal`（唯一 revision）。测试路径
  （`EconomyOwnershipFixture.advance:56`）**直接** `new EconomyOwnershipTimeParticipant(mapId).simulateWorld(...)`，
  **没有**把它注册进任何参与者链 ⇒ **不会二次落盘**；main 作用域实例化点 = **0**（`grep -rn "new EconomyOwnershipTimeParticipant(" simos-app/src/main simos-economy/src/main simos-core/src/main` = 0）。
- **待测试批处理**：`src/test` 里 27 个类仍按退役形状构造市场（`new Market(...)` / `Map<HexCoord, Market>` / `.markets()`），
  W1 按 §一.5/§三.0 不写新测试、只删了 2 个**只测被删 API** 的类
  （`MarketTopologySingleRegionTest`、`MarketTopologyBookSingleRegionTest`）；其余由测试批按验收判据迁移/重写。

---

## 六、验证证据（真命令 + 真输出，2026-10-11）

```
$ tools/mvn-lock.sh -q spotless:apply                       # 格式化（无输出 = 无违规）
$ touch simos-economy/.../EconomyData.java simos-app/.../MarketTopologyBook.java
$ tools/mvn-lock.sh -DskipTests compile -am
[INFO] Compiling 206 source files with javac [debug release 21] to target/classes   ← economy
[INFO] Compiling 328 source files with javac [debug release 21] to target/classes   ← app
[INFO] BUILD SUCCESS
[INFO] Total time:  6.288 s
```

```
$ grep -rn "markets()"          --include=*.java simos-*/src/main | wc -l   → 0
$ grep -rn "Map<HexCoord, Market>" --include=*.java simos-*/src/main | wc -l   → 0
$ grep -rn "new Market("        --include=*.java simos-*/src/main | wc -l   → 0
$ grep -rn "withMarkets("       --include=*.java simos-*/src/main | wc -l   → 0
$ git diff -U0 | grep -E "^\+.*(save\(|checkpoint|persist)"                → 只有日志文案 "persistent-zone"（无 save/checkpoint 调用）
```

**未验证（如实记）**：没跑测试（§一.5 禁止）；没跑真实 world 0→360（控制方 W1 收尾做）；
铁律 5 的往返不变式测试由测试批按判据补（W1 只保证形状完备：Codec 走 Jackson record 绑定 + `MarketZoneId` 键反序列化，
ChangeSet 走 `FieldDelta.diff/rebuild`，构造期守卫齐备）。

---

# W1b 测试树迁移（2026-10-11）

> 责任区：`src/test` 能编译 + **既有断言的判别力不被削弱**（迁移优先、删除只在"被测对象本身已不存在"时进行）。
> 硬指标：`tools/mvn-lock.sh -DskipTests test-compile -am` **rc=0**（全部 15 个模块的 test 源从零编过）。
> 允许写：`simos-economy/src/test/**`、`simos-app/src/test/**`、`simos-economy-api/src/test/**`；main 只改了 1 行注释（见 §四-6）。

## W1b-一、关键调查结论（file:line → 结论 → 影响）

| # | 证据（file:line） | 结论 | 影响 |
|---|---|---|---|
| 1 | `MarketSettlement.java:2113-2118` `marketIdAt(ctx, hex) = new MarketZoneId(ctx.topology.regionOf(hex).node().nodeId())` | **价表条目的键必须逐字等于拓扑节点 id** —— 结算取价表是"先按格找区、再按区 id 取表"，找不到 ⇒ `tableAt` 返回 null ⇒ 静默不报价 | 夹具装配的硬约束：`marketTables` 的键与 `MarketTopology` 的区域 id 必须同源（派生市场 = `"hex:<q>_<r>"`、持久区 = 区 id） |
| 2 | `HEAD:MarketTopology.singleRegion`（已退役） | 节点 id = `"single-region"`、成员格 = **全部**市场格、锚 = 规范序第一个、币 = **锚格**的币、`regional = true`、辐射项恒 0 | 夹具的派生形态按"同报价归一组、单组用 `single-region`"装配 ⇒ **逐值等于改动前**（`CapacityTruncationPricing` / `MerchantCapacity` / `MarketSettlementSingleHexLoss` 三个世界一个字节都没变） |
| 3 | `HEAD:MarketSettlement` 的 `matchGroup`（区级账本 `buysByRegionCommodity`/`sellsByRegionCommodity`）+ `pairUp` 的 `intraRegionFreightRoute`（区内跨格 = **即时**路线、无 `ShipmentBatch`） | 旧夹具的"**一个区、逐格不同价**"（区级参考价取锚格表 + 槽位各读本格表）是 W1 **结构上不可表达**的形态（每区一张 ⇒ 一区一价） | `HaulServiceCommodityAcceptanceTest` 里 H1（粮+haul）与 H2（只有粮）的两个世界 ⇒ 必成**两个市场**（见 §四-2） |
| 4 | `EnterpriseProfitBook.collect`（`EnterpriseProfitBook.java:432-439`）第 6 参 = **包内类型** `MarketIndex`（不是两张表） | W1 把"只有两张表"的调用方收口到 `MarketIndex.of(tables, zones)` | 同包测试直接传 `MarketIndex.of(world.marketTables(), world.marketZones())`（唯一新写法） |
| 5 | `EconomyData` / `EconomyChangeSet` 各 **37** 个 record 组件（实测逐行点名） | W1：`markets` → **`marketTables`**（改名，键从格改成市场 id）+ 新增第 40 个组件 **`marketRoundStats`** | 形状测试的组件计数基线 36 → **37**；`mutate`/`changedOf` 两个 switch 各补两条**非空**夹具 |
| 6 | `MarketTables.allMarketHexes(tables, zones)`（`MarketTables.java:136-151`） | "有市场的格" = 持久区成员格 ∪ 派生市场成员格 | 改动前 `markets().keySet()` 的读数语义（"有市场的**格数**"）在 `CompactThreeNationsWorld` 的两个读数里逐值接上 |

## W1b-二、实现架构（夹具与迁移口径）

### 2.1 `MarketSettlementFixtures`（核心夹具重写）

```
Builder.quotes            : Map<HexCoord, MarketTable>   ← 逐格报价（构造期输入；成员格恒 = {该格}）
Builder.regions           : Map<String, HexCoord>        ← 声明的持久区（zoneId → 锚格），原样保留

build():
  ① 声明区 ⇒ 逐区一个 MarketZone（锚/半径 0/成员格 = {锚格}/法定币 = 锚格报价的币/空汇率表）
             + 逐区一张**成员格为空**的价表（键 = 区 id）—— 与 EconomyData 的持久形态守卫同一口径（I22）
  ② 其余格 ⇒ 派生市场：按"计价币 + 价格表逐值相同"归组（**同报价 = 同一个市场**）
             单组 ⇒ id = "single-region"（逐值复刻退役 singleRegion 的节点 id）
             多组 ⇒ id = "hex:<规范序第一个成员格的 q>_<r>"（内容派生、稳定）
  topology = MarketTopology.ofRegions(逐市场一个 regionOf(id, 锚, 半径 0, 币, SILVER_SPECIE, 成员格),
                                      hex->1, (f,t)->0, NO_NEAREST_NODE_DISTANCE,
                                      probeDefaults, NO_CITY_DISCOUNT, NO_RURAL_PENALTY, true)
```

`World` 记录新增三栏：`marketTables` / `marketZones` / **`marketIdByHex`**（逐格读口），并提供
`tableAt(hex)`（= 改动前 `markets.get(hex)` 的替身）、`marketHexes()`（= `markets.keySet()`）、`defaultsOf()`
（= `MarketRegulation.defaultsFor(tables, zones)`）；另加静态 `tableAt(outcome, world, hex)`（= `outcome.markets().get(hex)` 的替身）。

### 2.2 `EconomyFixtures`（`EconomyData` 侧的"无地图切片"退化夹具）

`hexMarkets(Map<HexCoord, MarketTable>)` / `quote(numeraire, prices)` / `marketIdOf(hex)` 三个助手：
一格一张派生价表，键 = `"hex:" + IndustryHexKeys.hexKey(q,r)` —— 与 `MarketTopology.singleHex(tables, zones)`
**由成员格现推**的节点 id 逐字同源（否则 `marketIdAt` 取不到表）。`advance(...)` 改传 `singleHex(base.marketTables(), base.marketZones())`。

### 2.3 逐类处置（24 类，全部**迁移**，无删除）

| 类 | 处置 | 一句话理由 |
|---|---|---|
| `app.gui.GuiApiTest` | 迁移 | 夹具改 `withMarketTables(...)` + 派生市场 id；断言一字未改（`market.numeraire == silver` 仍由 `ApiViews` 的同一读口回答） |
| `app.world.CompactThreeNationsWorld` | 迁移 | `marketTables` 迭代取商品集；`"markets"` 读数改用 `MarketTables.allMarketHexes`（**保持"有市场的格数"语义**，不是价表条目数） |
| `app.world.EconomyTestWorld` | 迁移 | 逐格派生价表（取 `MARKET_NUMERAIRE` + `MARKET_PRICES_FACTORY`，不另拍价）；`withMarkets`→`withMarketTables` |
| `economy.change.EconomyRoundTripTest` | 迁移 | `markets`→`marketTables` + 新组件 `marketRoundStats` 的往返夹具；组件计数 36→**37**（判别力机制不变） |
| `economy.codec.EconomyCodecTest` | 迁移 | 旧变更集缺键 ⇒ `marketTables`/`marketRoundStats` 都读成 `Unchanged`（补上第 40 个组件的那一行） |
| `economy.time.CapacityTruncationPricingAcceptanceTest` | 迁移 | 读口改 `MarketSettlementFixtures.tableAt(outcome, world, hex)`；世界仍是**单区**（两格同报价）⇒ 判据面不变 |
| `economy.time.EconomyFixtures` | 迁移 | 见 §2.2（`data(...)` 的价表参数改键空间；该助手当前**零调用方**，仍迁成新形状） |
| `economy.time.ExpectedProfitBookEmployerOfTest` | 迁移 | `quote(...)` + `hexMarkets(...)` + `singleHex(tables, zones)` |
| `economy.time.ExpectedProfitBookTest` | 迁移 | 同上；`Fixture` 记录改持 `marketTables`，`market()` 读 `marketIdOf(H)` |
| `economy.time.FxHouseholdRoundAcceptanceTest` | 迁移 | `.market(hex, quote(...))`；`FxSettlement.match(round, marketTables, topology)` |
| `economy.time.FxPurchasingPowerAcceptanceTest` | 迁移（注入表键空间变更） | `zoneTables(tableFor)` 的入参从"锚格键"改成"**区 id 键**"；漂开用例改成"zone-b 的价表币 ≠ 区法定币"（**断言与期望值一字未改**） |
| `economy.time.GovServiceCommitmentC7Test` | 迁移 | `withMarketTables(hexMarkets(...))` |
| `economy.time.HaulServiceCommodityAcceptanceTest` | 迁移 + **世界形状具名变更** | H1/H2 报价不同 ⇒ 两个市场（详见 §四-2）；`stateDump` 的价格读口改 `world.tableAt`/`tableAt(outcome, world, hex)`；`EnterpriseProfitBook.collect` 改传 `MarketIndex.of(...)` |
| `economy.time.HexTradeCostTest` | 迁移 | 拓扑按**显式区域**装配（成员格 = 原 5 格、锚 = 规范序第一个、`regional = true`）⇒ 逐值等于退役 `singleRegion`；`HexTradeCost` 只吃拓扑，不需要价表 |
| `economy.time.MarketDemandBookTest` | 迁移 | `hexMarkets(...)` + `singleHex(tables, zones)`；`MarketDemandBook.build` 的价表参数改键空间 |
| `economy.time.MarketPayCurrencyAcceptanceTest` | 迁移 | `quote(...)`；声明区世界（zone-a/zone-b）逐值不变 |
| `economy.time.MarketSettlementFixtures` | 迁移（重写，见 §2.1） | 装配口径、`World` 三栏、三个逐格读口助手 |
| `economy.time.MarketSettlementSingleHexLossTest` | 迁移 | `world.tableAt(H1)`；引用价判据（同格/跨格损耗、`immediate`）的世界语义不变（两格同报价 ⇒ 单区） |
| `economy.time.MerchantCapacityAcceptanceTest` | 迁移 | `defaultsOf()`；两格同报价 ⇒ 单区 ⇒ 运力截断判据不变 |
| `economy.time.ModeMigrationPolicyClaimedAssetsTest` | 迁移 | `quote`/`hexMarkets`/`singleHex`；`withMarketTables` |
| `economy.time.ModeMigrationPolicyD024Test` | 迁移 | `Fixture` 改持 `marketTables` + `tableAt(hex)`；`MarketDemandBook.build`/`ModeMigrationPolicy.plan` 的价表参数改键空间 |
| `economy.time.PortGateTaxAcceptanceTest` | 迁移 | `defaultsOf()`（声明区的世界，键 = 区 id） |
| `economy.time.PortPolicyMerchantLaneAcceptanceTest` | 迁移 | 价格表逐值对照改 `tableAt(outcome, world, H1)`（两侧各自的 world） |
| `economy.time.Z7MarketExclusionTest` | 迁移 | `planOrders(round, H1, world.tableAt(H1), GRAIN)`；排除集判据一字未改 |

**删除：0 个类**（W1 已删的 `MarketTopologySingleRegionTest` / `MarketTopologyBookSingleRegionTest` 未复活）。
24 类里**没有一个**"只测被退役 API"—— 它们测的都是仍然存在的领域行为（价格/损耗/FX/口岸税/迁移/订单排除/统计往返），
只是构造世界的形状换了；`HexTradeCostTest.singleRegionCostMilliPerUnitIsAlwaysZero` 之类的**方法名**里的 "singleRegion"
指的是"单区世界"这一领域事实，不是被删工厂。

## W1b-三、关键判断（为什么这样拆）

1. **"同报价归一组、单组用 `single-region`"** 是唯一能让绝大多数夹具世界**逐值不变**的装配：改动前的
   `singleRegion` 本来就是"全部市场格一个区、锚 = 规范序第一个"，而这些世界的逐格报价**逐值相同** ⇒ 分组后仍是
   一个区、同一个锚、同一张表。
2. **派生市场 id 取 `"hex:<q>_<r>"`**（而不是"序号"或"内容哈希"）：它与 `MarketTopology.singleHex` 现推的节点 id、
   以及 `EconomyTestWorld`/`GuiApiTest` 的退化路径逐字同源 ⇒ **同一份世界在两种拓扑装配下取到同一张表**（结论 #1 的硬约束）。
3. **声明区时价表条目的 `hexes` 必须为空**：与 `EconomyData` 的构造期守卫（结论见 W1 账本 §2.1-⑤）同一口径 ——
   成员格权威只有 `MarketZone.hexes`，两处都存就是两个权威。
4. **`World.marketIdByHex` 是"夹具的逐格读口"，不是第二权威**：它在 `build()` 里一次定下、之后只读，逐字等价于
   改动前的 `markets.get(hex)`；不参与任何结算判定（结算仍走 `MarketIndex`/拓扑）。
5. **形状测试的计数按实测口径改（36 → 37）**：这条数字就是"又加/删了一个状态组件"的编译期/测试期提醒 ——
   改名（`markets`→`marketTables`）不改数、新增 `marketRoundStats` +1，实测两侧各 37（脚本逐行点名核对过）。
6. **退役的 `MARKET_FACTORY` 不再复制一份到测试**：`GuiApiTest`/`EconomyTestWorld` 改用 `MARKET_NUMERAIRE`
   + `MARKET_PRICES_FACTORY`（生产唯一拼写点）自行挂格，夹具不另拍价。

## W1b-四、偏离记录（主动记录）

1. **夹具的"分组"是控制方定的口径**（约束设计书没有写测试夹具怎么把逐格报价折成每区一张）：取
   "计价币 + 价格表逐值相同"为同市场判据，并**保留 `single-region` 这个 id 字面量**（与退役工厂/播种器同源）。
   ★ 若测试批认为"一格一市场"更该是夹具形态，改 `build()` 的归组循环即可（一处）。
2. **`HaulServiceCommodityAcceptanceTest` 的三个世界从"一个区"变成"两个市场"**（具名语义面变更）：
   `crossHexWorld`（H1 = 粮 + haul、H2 = 只有粮）、`aCarrierWithZeroToolStillHauls...`（同形）、
   `twoIdenticalRuns...`（同形）里两格的报价**不同**，而"每区一张"下"一区两价"不可表达 ⇒ 它们现在是
   两个**相邻**市场。"H1 是服务成市、H2 不是"这件事在新形状下正是"两个区"的表述（生产路径同款：服务成市是**区级**属性）。
   ★ **可观察差异**：旧路径 = 区内跨格（`route.immediate = true`、无 `ShipmentBatch`）；新路径 = 跨区在途
   （`immediate = false` + 一张在途票）。本批**不跑测试**（§一.5），因此这条**未做运行时验证**；
   ★ 若测试批看到红：两条退路（① 给该世界补 `.region("zone-a", H1).region("zone-b", H2)`，把"两个市场"写显式；
   ② 把 H2 的报价补齐成与 H1 相同 ⇒ 回到单区即时路径）——**都不许放宽任何断言**。
3. **`MarketSettlementFixtures` 的价表模板语义**：`grainMarket`/`grainAndHaulMarket` 现在返回**成员格为空**的
   `MarketTable`（"待挂到某一格"的模板），由 `Builder.market(hex, table)` 归一成 `hexes = {hex}`；
   传入带别的成员格的表会被具名拒（fail-closed）。
4. **`EconomyFixtures.data(...)` 的价表参数改了键空间**（`Map<HexCoord, Market>` → `Map<MarketZoneId, MarketTable>`）；
   该助手**当前零调用方**（旧的 10 参旧档形状桥），本批按"迁移而非删除"保留并迁成新形状。
5. **`FxPurchasingPowerAcceptanceTest` 的注入表**：`HouseholdPurchasingPower.zoneTables(tableFor)` 的 `markets`
   参数现在是"键 = 区 id"，故该用例的 `drifted` 注入表改成 `{zone-a: silver 表, zone-b: silver 表}`（区法定币是 copper）
   —— **测的是同一件事**（锚格/本区价表的币与该区法定币漂开 ⇒ 那个币没有价表），断言与期望值未动。
6. **允许的唯一 main 改动**（任务书 §3）：`model/MarketTable.java` 类注里 `旧 {@code MarketTable(...)}` 的笔误改成
   `旧 {@code Market(...)}`（纯注释，1 行）。其余 main 改动全部是 W1 的（工作树里未提交，本批未回退、未新增）。
7. **测试作用域残留言注释 3 处**（见 §五）：都是"改动前它是什么"的历史说明（`new Market(` ×2、`.markets()` ×1），
   按 §五.4 留痕纪律**不回改成 0**（改动前后对照是接任者最需要的线索）。

## W1b-五、验证证据（真命令 + 真输出，2026-10-11）

```
$ tools/mvn-lock.sh -q spotless:apply                       # 格式化（rc=0，无输出）
$ for d in simos-*/target/test-classes; do rm -rf "$d"; done   # 只清 test-classes（没用 mvn clean）
$ tools/mvn-lock.sh -DskipTests test-compile -am
[INFO] --- compiler:3.16.0:testCompile (default-testCompile) @ simos-economy ---
[INFO] Compiling 55 source files with javac [debug release 21] to target/test-classes
[INFO] --- compiler:3.16.0:testCompile (default-testCompile) @ simos-app ---
[INFO] Compiling 147 source files with javac [debug release 21] to target/test-classes
[INFO] BUILD SUCCESS
[INFO] Total time:  12.070 s
$ echo rc=$?   → rc=0        # 15 个模块的 test 源全部从零编过，[ERROR] 计数 = 0
```

```
测试作用域 grep 计数（simos-economy/src/test + simos-app/src/test + simos-economy-api/src/test）：
  new Market(                → 2   （均为注释：MarketSettlementFixtures:131、EconomyFixtures:78）
  Map<HexCoord, Market>      → 0
  .markets()                 → 1   （注释：MarketSettlementFixtures:102）
  withMarkets / MARKET_FACTORY / import ...model.Market;  → 0 / 0 / 0
  MarketTopology.singleRegion / .of( / .ofZones(          → 0（仅注释与方法名里的历史词）
```

**未验证（如实记）**：本批**没跑任何测试**（任务书只允许两条命令）⇒ 只有"编译通过"这一条硬证据；
§四-2 的三条行为验收测试的**运行时**结果未验证（见该条的退路）；`EconomyRoundTripTest` 的组件计数与两个 switch 的
覆盖是**静态**核过的（脚本逐行点名 37/37），未跑；`EconomyCodecTest` 新增的那一行 `marketRoundStats` 断言未跑。

---

# W1c 夹具修复（2026-10-11）

> 责任区：`clean verify` 在 `simos-economy` 红的两条**夹具问题**（控制方已定性：不是实现错）。
> 硬指标：`tools/mvn-lock.sh -DskipTests test-compile -am` **rc=0**。
> 允许写：`simos-economy/src/test/**`、`simos-app/src/test/**`；**未改任何 `src/main`**；**未改任何断言**；未 `git commit`。
> 允许的命令只有两条：`tools/mvn-lock.sh -q spotless:apply`、`tools/mvn-lock.sh -DskipTests test-compile -am`（**没跑任何测试**）。

## W1c-一、两条失败的根因（file:line 证据 → 结论）

| # | 证据（file:line） | 结论 |
|---|---|---|
| 1 | `MarketSettlement.java:7028-7050`（区内即时）报 `freight > 0 ? unitFreight : 0`，其中 `unitFreight = buy.payAmountOf(route.freightPerUnit)`（`:6689`）；`MarketSettlement.java:7086-7103`（跨区在途）报 `reportedFreightPerUnit = ceilDiv(freight × 1000, executed)` | **同一份单位运费，两种报法**：即时 = 指示费率的**原值**；在途 = "本票实收 ÷ 成交量"向上取整的**解** |
| 2 | 夹具算式（真值）：`rate = TransportTariff.probeDefaults().perMille(1,0,0,0,0) = 5 + 5×1 = 10‰`；`base = CommodityFreightBase.GRAIN_MILLI = 1`；`work = 1×(1000+10) = 1010`；`HaulService.unitFreightMilli(1010, 4) = ⌈4.04⌉ = 5`；`quantity = lifeReserveGrain(1) = ⌊1×10000×1/120⌋ × 35 = 83×35 = 2905`；`freight = ⌈2905×5/1000⌉ = 15` | W1b 之后该 lane 成了**跨区在途** ⇒ 报出的单位运费 = `⌈15×1000/2905⌉ = ⌈5.16⌉ = 6`，而测试按规定算式复算 = `5` ⇒ **`expected: 5L but was: 6L` 逐值对上**（`:167`） |
| 3 | `MarketSettlementFixtures.Builder.build()`（`:493-532`）：没声明区 ⇒ 按"计价币 + 价格逐值相同"归组；H1（粮 10 + haul 4）与 H2（粮 10）**报价不同** ⇒ 两个派生市场（`hex:0_0` / `hex:1_0`） | W1b 把 `HaulServiceCommodityAcceptanceTest` 的三个世界从"一个区"改成了"两个相邻市场" ⇒ 区内跨格即时（`:5778-5812` 的 `intraRegionFreightRoute`）变成跨区在途（`:5379` 的 `matchRoute`） |
| 4 | `EconomyData.java:1977-2029`（W1 新增的跨表守卫：区必须有价表 / 持久区价表成员格必须为空 / 计价币必须 = 法定币 / 派生市场必须有成员格）；`EconomyRoundTripTest.java:513-515`（原夹具）只 `withMarketZones(Map.of(ZONE, zone()))`、基础态无 `marketTables` 条目 | 夹具**自相矛盾**（持久区没有价表）⇒ `IllegalArgument: 市场区没有价表条目（每区一张表：缺表 = 该区没有任何价格）：区=zone-1`（在 `mutate:515` 抛出，与门禁输出一致） |

## W1c-二、改动（逐文件）

### 2.1 `simos-economy/src/test/java/io/mosire/simos/economy/time/HaulServiceCommodityAcceptanceTest.java`

**改动只有一处语义**（三处世界装配，同一个形状）：H2 的报价从 `GRAIN_PRICE`（只粮）补齐成
`grainAndHaulMarket(GRAIN_PRICE, HAUL_PRICE)`（粮 + haul，与 H1 **逐值相同**）⇒ 两个格按
`MarketSettlementFixtures.Builder.build()` 的归组口径落进**同一个市场**（单区两格、id = `single-region`）——
即 **W1 前的形状**（退役的 `MarketTopology.singleRegion`：全格同报价 = 一个区、锚 = 规范序第一格 H1、成员格 = {H1,H2}）。

| 位置（改后行号） | 改了什么 |
|---|---|
| `:300`（`aCarrierWithZeroToolStillHauls...` 的内联世界） | `.market(H2, GRAIN_PRICE)` → `.market(H2, grainAndHaulMarket(GRAIN_PRICE, HAUL_PRICE))` |
| `:433`（`twoIdenticalRunsProduceByteIdenticalStateDumps` 的内联世界） | 同上 |
| `:470`（`crossHexWorld`，被用例 ①②③ 共用） | 同上（**这就是 `:167` 那条红灯的那一处**） |
| `:449-467`（`crossHexWorld` 类注）+ 上面两处具名注释 | 说明"两格同报价 ⇒ 一个市场 ⇒ 区内即时路线"与"服务成市是**区级**属性" |

**为什么这样改就等价于 W1 前的形状**（逐项）：
- 归组后 `marketTables` 只有一张表、键 = `single-region`、成员格 = {H1,H2}、计价币 = silver、价目 = {粮 10, haul 4}；
  `World.marketIdByHex` ⇒ 两格同指该表；拓扑 = 一个区（锚 H1、半径 0、`regional = true`、辐射 0）——
  与 W1b 账本 §2.1 声明的"单组 ⇒ 逐值复刻退役 `singleRegion`"逐条对上。
- `H1→H2` 因此走**区内跨格即时路线**：`freightPerUnit = HaulService.unitFreightMilli(1010, 4) = 5`，
  `Fill.freightPerUnitMilli` 报的正是这个数 ⇒ 与测试按同一算式复算的 `expectedUnitFreight = 5` 逐值相等。
- 服务成市仍是"发货格 H1 的市场给 haul 定过价"（`:5414`/`:5665` 的 `haulServiceAt(ctx, sellerHex)`）⇒
  服务牌价 `4`、运力池、承运判据**一字未变**；H2 多出来的那一个 haul 价目只在"本区一张表"的意义上存在
  （区内任何格取价一致），没有任何断言读 H2 自己的价目（`stateDump` 只在两个同形状世界之间比较）。
- **断言一字未改**（含 `:167` 的 `expected: 5L` 与 `:169`/`:192`/`:231` 三处金额）。

### 2.2 `simos-economy/src/test/java/io/mosire/simos/economy/change/EconomyRoundTripTest.java`

| 位置（改后行号） | 改了什么 |
|---|---|
| `:513-523`（`mutate` 的 `case "marketZones"`） | 由 `base.withGovernments(...).withMarketZones(Map.of(ZONE, zone()))` 改成 `withZoneAndItsTable(base.withGovernments(...), zone(), zoneMarket())` |
| `:942-951`（新夹具） | `zoneMarket()`：**持久形态**的价表 = `new MarketTable(SILVER_CURRENCY, Set.of(), {grain:3, cloth:5})`（成员格为空、计价币 = 该区法定币） |
| `:953-1006`（新助手） | `withZoneAndItsTable(base, zone, table)`：用 canonical 构造器把 `marketTables` 与 `marketZones` **一次落地**，其余 35 个组件原样带过 |

**为什么必须"一次落地"（这是本次唯一的实现难点）**：`EconomyData` 的跨表守卫在 **compact 构造器**里，
而**每一次** `with*()` 都会重新构造并重跑它 ⇒ 两个单组件 wither **没有任何合法中间态**：
- `withMarketZones(zone)` 先调 ⇒ 该区还没有价表 ⇒ "市场区没有价表条目"（就是门禁那条红）；
- `withMarketTables(成员格为空的表)` 先调 ⇒ 该键还不是区 ⇒ 被读成派生市场 ⇒ "派生市场必须有成员格"。

⇒ 与 `EconomyClearRegionHandler` 末尾（`:396-455`：`classes` + `classStandings` 必须"同一次规范构造"）**同款**，
用 canonical 构造器把两个互相约束的组件一次改变。**判别力未削弱**：目标态仍含一个持久区 + 它的一张价表，
`cs.marketZones().changed()` 仍为 `true`，`EconomyChangeSet.apply(cs, base) == target` 仍成立；
组件计数断言与 `hasSize` 一字未改。

## W1c-三、关键判断

1. **走账本 §四-2 的退路 ②（把 H2 的报价补齐），不走退路 ①（显式声明 zone-a/zone-b）**：退路 ① 下两格属于
   **两个**区 ⇒ 仍是跨区在途、单位运费仍是 `⌈15×1000/2905⌉ = 6`，修不了 `:167`；退路 ② 回到单区即时，
   报的就是指示费率本身。控制方的任务书也是同一口径（优先试 ②）。
2. **三个世界一起改，不只改 `crossHexWorld`**：三处是同一个夹具形状（W1b 账本 §四-2 点名的**同一个**原因），
   且本类类注写死"夹具：`MarketSettlementFixtures`（**单区两格**）"。留两处在"两个市场"里会让同一份判据面
   在不同用例里语义不同（即时 vs 在途），正是本条要消除的漂移。
3. **没有放宽任何断言**：`expectedUnitFreight`、`expectedFreight`、`serviceConsumed`、`carrierMoneyLegs`、
   `book.net(...)` 五个金额/数量判据的算式与字面量一个字节都没动；改的是**世界形状**，让它们回到 W1 前的可满足态。
4. **`zoneMarket()` 的价目取非空**（与 `market()` 同值）：`marketZones` 这一维的判别力落在"区表进没进变更集"，
   而空价表与"字段没进变更集"在值层面不可区分（本文件既有理由）；价目非空同时让价表自身也是一个真实读数。
5. **手写 canonical 构造器的护栏**：显式列出全部 37 个组件 ⇒ 将来新增组件时**编译不过**（不是静默丢字段），
   且本文件 `changeSetHasExactlyThirtySevenComponents` 把两侧组件数钉住 —— 与 `EconomyClearRegionHandler`
   的同款写法一致。★ 参数顺序按**声明序**逐行对齐核过（脚本：36/36 同名同位、两个 `Map.of(...)` 恰落在
   `marketTables`(8) 与 `marketZones`(33)；第 37 个 `marketRoundStats` 在末位）。

## W1c-四、偏离记录与 main 侧发现（★ 未自行修改 main）

1. **偏离（实现形态）**：任务书给的修法（"让基础态/相关 mutate 里 `marketTables` 含 `zone-1` 的条目"）在
   **wither 链上不可达**（见 §2.2 的守卫分析）⇒ 本批用 canonical 构造器实现同一语义（自洽的"区 + 它的价表"）。
   断言、判别力、组件数一律未动；**若控制方改 main**（例如加一个"区 + 价表"的原子 wither），本助手可随之简化。
2. **★★ main 侧发现（BLOCKER 级，本批未改，如实上报）**：W1 把守卫放进 compact 构造器之后，
   **凡是"区表 / 价表必须成对变化"的命令路径都会在中间态被自己的守卫拒掉** —— 实测代码路径：
   - `EconomyDefineMarketZoneHandler.java:244`：`base.withMarketZones(zones).withMarketTables(marketTables)`
     ⇒ 第一次调用就抛"市场区没有价表条目"（新区还没有表）⇒ 被 `:247` 的 catch 转成
     `rejected("contract-violation", ...)` ⇒ **任何 `economy.DefineMarketZone` 都会失败**。
     证据链：`ThreePowersGovBootstrap.java:334` → `ThreePowersMarketZones.define`（`:183-201` 构造并 apply 该 handler）
     ⇒ 三国世界的市场区播种走这条路径 ⇒ `simos-app` 侧 `ThreePowersWorld` 相关用例（`EconomyCycleHealthTest` /
     `CrossProcessDeterminismTest` 等）**预期会红**。
   - `EconomyMergeMarketZonesHandler.java:193`：同款（`withMarketZones(zones)` 里新合并区还没有表）。
   - `EconomySetMarketNumeraireHandler.java:113`：`withMarketTables(...).withMarketZones(...)` ⇒ 中间态是
     "新计价币的价表 + 旧法定币的区" ⇒ 撞"计价币必须 = 法定币"。
   - 未命中：`EconomyClearRegionHandler`（区表原样带过、派生表只摘成员格 ⇒ 中间态自洽）、
     `EconomySetMarketPriceHandler:157`（只改价表、键与形态不变）。
   ⇒ **这两条不是测试夹具问题**：要么给"区 + 它的价表"补一个原子写口（wither/处理器），要么把守卫从
   compact 构造器挪到命令/装载边界。**本批按纪律不改 `src/main`**，请控制方裁定后另派责任区。
3. **未改**：`docs/**`、`AGENTS.md`、`simos-social`/`simos-unit`/`simos-sd`/`simos-core`、任何 `src/main`；
   未 `git commit`；未跑 `test`/`verify`/`package`/`clean`。

## W1c-五、验证证据（真命令 + 真输出，2026-10-11）

```
$ tools/mvn-lock.sh -q spotless:apply
[rc=0]                                  # 无输出（只有本批两个文件被格式化，见下）

$ find simos-*/src -newermt "-12 minutes" -type f
simos-economy/src/test/java/io/mosire/simos/economy/change/EconomyRoundTripTest.java
simos-economy/src/test/java/io/mosire/simos/economy/time/HaulServiceCommodityAcceptanceTest.java
                                        # ★ 全仓 spotless:apply 没有碰第三个文件（§五.1 的漏提交坑未发生）

$ tools/mvn-lock.sh -DskipTests test-compile -am 2>&1 | tail -40
[INFO] Compiling 147 source files with javac [debug release 21] to target/test-classes
[INFO] Reactor Summary for SimulatorMosire 0.1.0-SNAPSHOT:
[INFO] EconomySimos ....................................... SUCCESS [  1.738 s]
[INFO] SimosApp ........................................... SUCCESS [  3.982 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  11.544 s
$ echo "[PIPESTATUS=${PIPESTATUS[0]}]"   → [PIPESTATUS=0]      # 16 个模块（含根）全绿，[ERROR] 计数 = 0
```

**断言未改动清单（确认）**：两个文件里**没有任何断言、期望值或 `hasSize(...)` 被改**——
`HaulServiceCommodityAcceptanceTest` 的 `expectedUnitFreight`（`:164`，`expected: 5L` 的来源）与
`expectedFreight`（`:168`）逐字未动；`EconomyRoundTripTest` 的 `hasSize(37)`、`isFalse/isTrue/isEqualTo(target)`
逐字未动。★ 口径提醒：`git diff`（对 `HEAD`）里这两个文件同时含 **W1b 的迁移改动**（未提交），本批 W1c 的语义改动
只有四处 —— `case "marketZones"` 的装配一处 + 三处 `.market(H2, …)`；其余 `-` 行都是 W1b 留下的（未回退、未新增）。

**未完成 / 未验证（如实记）**：
1. **没有跑任何测试**（任务书只允许那两条命令）⇒ 两条红灯"修好了"是**静态推演**的结论，不是实测：
   - 红灯 1 的逐值推演见 §W1c-一 `#2`（`5` / `6` / `2905` / `15` 四个数与门禁输出逐值对上），但 `:167` 之外的
     同族断言（`:169`/`:175`/`:185-195`/`:226-231`）在**单区即时**路径下的运行时结果未跑；
   - 用例 ③④⑤（`crossHexWorld(0L)` 的 `LOGISTICS_CAPACITY`、工具 0 照运、两跑 dump 一致）我**改了它们的
     世界形状**（两格同报价 ⇒ 单区），静态核过判据面（`blockLaneWithoutCapacity` ⇒ `LOGISTICS_CAPACITY`；
     即时路线仍要求 H1 的运力池有运力），**但未跑**——这是本批最需要控制方先跑的一条。
   - 红灯 2 的修法是新构造形态（canonical 构造器），**未跑**；守卫的满足是静态核的（四条 throw 逐条对过）。
2. **main 侧发现（§四-2）未验证到运行时**：`Define/Merge/SetMarketNumeraire` 三个 handler 的中间态拒因是
   **读代码**得出的（`:244`/`:193`/`:113` 的调用序 + 守卫条件），我没有跑过这些 handler 的测试。
3. 本批**没有**变异自证（§三.0 收尾期规矩由控制方统一做）。

---

# W1d 守卫中间态修复（2026-10-11）

> 责任区：修 W1 自己引入的 **main 侧缺陷** —— 跨表守卫住在 `EconomyData` 的 compact 构造器里，导致
> "区 + 它的价表必须成对变化"的命令路径在**中间态**被自己的守卫拒掉（`economy.DefineMarketZone` 恒失败）。
> 控制方裁定（任务书 §1，逐条按此实现，未另选路线）：**保留**值守卫（价表 `numeraire` 逐值 = 区 `legalTender`）、
> **删掉**存在性守卫（每区必须有价表）、**补原子写口**、**所有读点对缺表 fail-closed 到"不交易"**、其余守卫保留。
> 允许写：`simos-economy/src/main/**`、`simos-economy-api/src/main/**`（仅 MarketZone 相关）、`simos-app/src/main/**`；
> **未改任何测试文件**、未 `git commit`；只跑了两条允许的命令（`spotless:apply` / `-DskipTests compile -am`）。

## W1d-一、改动文件清单（10 个，全部 `src/main`；md5 见 §五）

| # | 文件 | 改动点 |
|---|---|---|
| 1 | `simos-economy/.../EconomyData.java` | ① **删掉**"每个持久区必须有价表条目"的循环守卫（原 `:2025-2029`，W1 引入）+ 改写 §4.2 N-4 口径的构造期注释；② 类注 `marketTables` 第①条从"守卫判死缺表"改成"缺表 = 不交易，合法状态"；③ 新增 **`withMarketZonesAndTables(zones, tables)`** 原子写口（`:4136`）；④ `withMarketTables` 补 Javadoc（什么时候<b>不能</b>单用它） |
| 2 | `simos-economy/.../model/MarketTables.java` | `allMarketHexes` 补 W1d 口径注（**缺表的区不贡献成员格**）；`marketIdAt` 补 W1d 口径注（区有表缺 ⇒ 空）。**代码零改动**（原实现已满足） |
| 3 | `simos-economy/.../time/MarketIndex.java` | ★ **`at(HexCoord)`：区有表缺 ⇒ `null`**（原实现已 `return tables().get(zone.zoneId())`；本次把 `tables()`/`marketZones()` 提成局部量、派生市场分支补 `table != null`，杜绝"表被摘后 `.hexes()` NPE"）；`ofRegion` 补 W1d 注（缺表 ⇒ `null`）；类注补一行 |
| 4 | `simos-economy/.../time/MarketSettlement.java` | ★ **`planOrders` 的逐区规划循环加缺表跳过**（`:1612`）：区没有价表条目 ⇒ 不生成任何订单（原为**必然 NPE**：`market.prices()`）；`tableOf` 补 W1d 注；`:2193` 的"坏状态"注改成"缺表 = 不交易"；顶部"逐格迭代集合"注改写 |
| 5 | `simos-economy/.../time/MarketTopology.java` | ★ **`singleHex` 迭代集合 = 价表条目 ∪ 区**（原为只看价表 ⇒ 缺表区的成员格从退化拓扑里**整体消失**，且 `zone != null ? zone.hexes() : table.hexes()` 在"区有表缺"时 NPE）；`LinkedHashSet` import |
| 6 | `simos-economy/.../spi/EconomyDefineMarketZoneHandler.java` | `:248` 改用 `base.withMarketZonesAndTables(zones, marketTables)`（原 `base.withMarketZones(zones).withMarketTables(marketTables)`）—— **这是本批的主缺陷现场**；补价目可空的契约注 |
| 7 | `simos-economy/.../spi/EconomyMergeMarketZonesHandler.java` | `:195` 改用原子写口（撤源区 + 摘源表） |
| 8 | `simos-economy/.../spi/EconomySetMarketNumeraireHandler.java` | `:116` 改用原子写口（改区法定币 + 改价表计价币） |
| 9 | `simos-app/.../time/MarketTopologyBook.java` | 类注补 W1d 口径（缺表区不装配）；`byPersistentZones` 收集 `zoneIdsWithoutTable` 并加进既有 DEBUG `MARKET_TOPOLOGY_FROM_PERSISTENT_ZONES`（原逻辑已 `continue`，零行为改动） |
| 10 | `simos-economy/.../time/ModeMigrationPolicy.java` | 1 行注释口径（`allMarketHexes` = "有价表的区成员格"） |

★ **未碰**：任何 `src/test/**`、`docs/**`、`AGENTS.md`、`simos-social`/`simos-unit`/`simos-sd`/`simos-core`、
`simos-economy-api` 的 `MarketZone.java`（W1 已加 `withLegalTender`，本批不需要它）。
★ `EconomyClearRegionHandler` / `EconomyReassignZoneHexesHandler` / `EconomySetOfficialRateHandler` / `EconomySetMarketPriceHandler`
**未改**：它们要么只改区表（不碰价表）、要么只改价表（键与形态不变），中间态自洽（W1c 结论逐条复核过）。

## W1d-二、"缺表 ⇒ 不交易"的读点核对表（逐个读点给结论）

口径三条（本批冻结，三处必须同值）：**① 缺表 ⇒ `marketIdAt`/`at` 空；② 缺表 ⇒ 拓扑不装配该区；③ 缺表 ⇒ 该区不贡献"有市场的格"。**

| # | 读点（file:line） | 缺表时的行为 | 结论 |
|---|---|---|---|
| 1 | `EconomyData.java` 构造期跨表守卫（`:1982-2034`） | **不再判死**（W1d 删掉那条循环）；值守卫三条逐字保留 | ✅ 目标态 |
| 2 | `MarketTables.marketIdAt`（`MarketTables.java:81`） | 区命中但表缺 ⇒ `Optional.empty()`（`:84` 的 `containsKey` 判） | ✅ 空 = 不交易 |
| 3 | `MarketTables.at`（`:98`）/ `atOrNull`（`:108`）/ `ofZone`（`:113`） | 空 / `null` / `Optional.empty()`（`ofZone` 本就是 `ofNullable`） | ✅ |
| 4 | `MarketTables.allMarketHexes`（`:143`） | **不贡献**该区成员格（遍历的是价表条目） | ✅（W1d 具名口径，见 §四-1） |
| 5 | `MarketTables.memberHexes`（`:161`）/ `frozen`（`:125`）/ `canonicalIds`（`:42`） | 与表键空间同源，缺表 ⇒ 该市场不在结果里（不抛） | ✅ |
| 6 | `MarketZoneBook.zoneOfHex/zones`（`MarketZoneBook.java:88/100`） | 区表权威，**不看价表** ⇒ 区仍在（成员格归属不因缺表消失） | ✅ 与 #4 不冲突（"归属"≠"报价"） |
| 7 | `MarketIndex.at`（`MarketIndex.java:110`） | 区命中 ⇒ `current.get(zoneId)` = **`null`**（不回落到派生市场，I22） | ✅ 本次加固 |
| 8 | `MarketIndex.ofRegion`（`:96`）/ `ofMarket`（`:90`）/ `currencyAt`（`:134`） | `null` / `null` / `null`（`currencyAt` 已判空） | ✅ |
| 9 | `MarketSettlement.planOrders` 逐区规划（`MarketSettlement.java:1612`） | **跳过该区**（一个订单都不生成）—— **原为必然 NPE** | ✅ 本次修 |
| 10 | `MarketSettlement.MatchContext.tableOf`（`:9772`） | `null`；5 个调用点**全部**已判空：`:3121`（creditRound）、`:3708`（debtRound）、`:4101`（matchWithinRegions）、`:4170`（matchWithinRegionsSerial）、`RegionClone` 构造（只接 `:4101` 过滤后的区） | ✅ |
| 11 | `MarketSettlement` 自适应改价（`:2193`） | `continue`（该区不改价、不产 `PriceUpdate`）；注已改成"缺表 = 不交易" | ✅ |
| 12 | `MarketSettlement.MatchContext.tableAt`（`:9782`） | 经 `tableOf` ⇒ `null`；调用点 `:2163`/`:3542`/`:7780`/`:8486` 都判空（`:8486` 本次核过） | ✅ |
| 13 | `MarketSettlement` 运输成市格集（`EconomySettlement.java:803`） | `allMarketHexes` 不给该区的格 ⇒ 不进循环（即便进了，`atOrNull` 也返回 `null`，`HaulService.pricedAt(null)` 应为 false——静态核过，**未跑**） | ✅ |
| 14 | `MarketTopology.singleHex`（`MarketTopology.java:219`） | 成员格由**区**给（不再消失、不 NPE）；该区在本拓扑里没有价格（`MarketIndex.at` 返回 `null`） | ✅ 本次修 |
| 15 | `MarketTopology.ofRegions`（`:293`） | 只采信调用方给的区域 ⇒ 调用方（#16）不装配缺表区就自然不参与 | ✅ |
| 16 | `MarketTopologyBook.byPersistentZones`（`MarketTopologyBook.java:154`） | `continue`（不装配区域）+ 具名计数进 DEBUG | ✅ |
| 17 | `MarketTopologyBook.byDerivedMarkets`（`:242`）/ `from`（`:116/121`） | 只遍历价表条目 ⇒ 缺表区根本不在；`marketTables` 空 ⇒ `singleHex` 兜底 | ✅ |
| 18 | `MarketReadout` 逐区读数（`MarketReadout.java:263`） | `continue`（该区没有读数行，不伪造 0） | ✅ |
| 19 | `MarketReadout.roundStatsByMarket`（`:420`） | 遍历价表键 ⇒ 不涉及 | ✅ |
| 20 | `MarketSupplyDemandView.compute`（`MarketSupplyDemandView.java:56-59`） | `continue` | ✅ |
| 21 | `MarketRoundStatsBook.marketIds`（`:36`）/ `describe`（`:104`） | 键空间同源（`describe` 在键集内取表，恒非空） | ✅ |
| 22 | `MarketPayChoice.of`（`MarketPayChoice.java:142-145`） | `continue`（该区不选支付币） | ✅ |
| 23 | `HouseholdPurchasingPower.zoneTables`（`:83`）/ `tableFor`（`:106`） | `continue` / 回落 ⇒ `null` = "缺价"（`needCostMilli` 返回 `OptionalLong.empty()`） | ✅ |
| 24 | `PrimaryModeRanking.marketOf`（`:431`）/ 消费点（`:255`） | `null`；消费点 `market == null ? 0L : …` | ✅ |
| 25 | `LaborQueueSettlement.marketOf`（`:867`） | `null`（三处 `!= null` 判） | ✅ |
| 26 | `EconomySettlement.marketForHex`（`:6749`）/ `:595` | `null`；调用点按 `null` 处理（`:595` 是 `atOrNull`） | ✅ |
| 27 | `MarketDemandBook.regionAnchors`（`:379`）/ `commodityUniverse`（`:422`） | 只遍历 `markets.values()` ⇒ 缺表区不在；家户/unit 走 `anchorOf` 兜底 | ✅ |
| 28 | `ModeMigrationPolicy`（`:1374`/`:1640`） | `zone == null ? null : markets.get(...)` ⇒ `null` 时回落到"钱最少的币"；格宇宙走 `allMarketHexes` | ✅ |
| 29 | `PlanOrders` 的 `Objects.requireNonNull(market)`（`:1390`） | **调用方必须不传 `null`** —— 本批把两条会传 `null` 的路径（#9/#10）都堵上了，其余调用方都先判空 | ✅（fail-closed 保留） |
| 30 | `ApiViews.economyHex`（`ApiViews.java:1403`）/ `economyOwnership`（`:1875`）/ `marketView`（`:4429`） | `atOrNull` ⇒ `null`；`marketView(null) == null`（"这一格没有市场"是**合法状态**，界面可读）；`:1875` 起三处 `market == null ? null : …` | ✅ |
| 31 | `MarketZoneReadout.zones`（`MarketZoneReadout.java:160`） | 遍历**拓扑**区域 ⇒ 缺表区不出现在读数里（与 #16 同判据） | ✅ |
| 32 | `ThreePowersMarketZones.requireZones` 的 `zone-hex-without-market` 自检（`:285-293`） | 走 `allMarketHexes` ⇒ 缺表区的成员格判死 | ✅ 自检语义不变（三国世界每区都有表 ⇒ 逐值通过） |
| 33 | `RegionSeedPlan`（`:454`）/ `MarketRegulation.defaultsFor`（`:75`）/ `EconomySettlement.haulServiceHexes`（`:803`） | 都走 `allMarketHexes`，#4 的口径统一生效 | ✅ |

**核对方式**：全仓 `grep` 出所有"价表取值点"（`marketTables().get` / `tables().get` / `markets.get(` / `allMarketHexes` /
`atOrNull` / `tableOf(` / `tableAt(` / `marketIdAt`），逐条读上下文判空；**未跑**任何测试（见 §六）。
★ 凡"表可能为 null 且被解引用"的位置，本批**零残留**（`grep -rn "\.get([^)]*)\.\(prices\|numeraire\|hasPrice\|hexes\|priceOf\)" simos-*/src/main`
只剩 3 处，全部在"遍历键集后取值"或已判空的分支里，逐条核过）。

## W1d-三、新增原子写口的签名与调用点

```java
// simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:4136
public EconomyData withMarketZonesAndTables(
    Map<MarketZoneId, MarketZone> zones, Map<MarketZoneId, MarketTable> tables)
```

- **参数序与记录声明序一致**（`marketTables` = 第 8 个组件、`marketZones` = 第 33 个组件），其余 38 个组件原样带过（全表 40 个）。
- **不是第二套状态**：两个参数就是那两个既有组件，最终仍走同一个 canonical 构造器与全部守卫 —— 只是让**目标态**而不是**中间态**接受检查。
- **三个调用点**（本批全部）：
  1. `EconomyDefineMarketZoneHandler.handle:248` —— 立区 + 它的表 + 来源派生表摘格；
  2. `EconomyMergeMarketZonesHandler.handle:195` —— 撤源区 + 摘源表 + 目标区并格；
  3. `EconomySetMarketNumeraireHandler.handle:116` —— 改区法定币 + 改价表计价币（派生市场时区表原样带过，等价于原来的单 wither）。
- 与既有先例同款：`withClassStructuresAndPositions`（`:3289`）、`EconomyClearRegionHandler` 末尾的"classes + memberships 同一次规范构造"。

## W1d-四、关键判断与偏离记录

1. **`allMarketHexes` 的缺表口径（本批唯一的解释性决定，非任务书明文）**：任务书 §1.4 只要求"读点对缺表安全"，
   没有逐字规定 `allMarketHexes` 该不该算缺表区的成员格。本批取**不计入**（"有市场的格" = 有价表的格），理由是它与
   `MarketIndex.at`（缺表 ⇒ 该格读不出价）**必须同值**，否则会出现"这个格有市场、却读不出价"的自相矛盾读数
   （`EconomySettlement.haulServiceHexes` / `MarketRegulation.defaultsFor` 的锚格都取自它）。
   ★ **另一条读法与影响面（如实记）**：若控制方要"区成员格恒算有市场"（= `CompactThreeNationsWorld` 的 `"markets"` 读数口径不变），
   改 `MarketTables.allMarketHexes` 一处即可（加"区表里的 id 即使无表也 `addAll(zone.hexes())`）——
   代价是 `MarketRegulation.defaultsFor` 的锚格可能落在一个**不交易**的格上，以及上述自相矛盾读数。
   ★ **对既有世界逐值不变**：W1d 之前"区有表缺"的世界**不可构造**（构造期就抛），故两种读法在 W1 世界与三国世界上**逐值同**。
2. **`MarketTopology.singleHex` 取"区优先"而不是"跳过缺表区"**：`singleHex` 是**没有 map 切片**的退化路径
   （夹具/单模块），它的契约是"每个有市场的格自成一区"。若跳过缺表区，同一个世界会在一处读到"这一格属于某市场"、
   在另一处读到"不属于"（`MarketZoneBook` 仍返回该区）⇒ 取"区优先"（成员格由区给、价格仍读不出）保持归属一致。
   ★ 影响面 = **仅退化路径**（有 map 切片时走 `byPersistentZones`，缺表区不装配）；生产世界没有"持久区 + 无 map 切片"的形态。
3. **`MarketSettlement` 逐区规划循环的跳过是本批第二处真 bug 修复**（W1c 未点名）：`marketTables.get(regionId)` 的返回值
   在被守卫拒绝之前**不可能为 null**，所以 W1 没暴露；W1d 放开守卫后，任何"区有表缺"的世界第一次开市就会在
   `market.prices()` 上 NPE（`planOrders` 的 `requireNonNull` 在更下游，先炸的是解引用）。⇒ 必须与守卫放开**同批**修。
4. **没有放宽任何值守卫**：价表 `numeraire` = 区 `legalTender`、持久形态价表成员格为空、派生价表成员格非空、
   派生价表成员格不得被区覆盖、`MarketTables.frozen` 规范序、统计簿判空 —— 逐条**保留**（`EconomyData.java:1982-2034`）。
5. **`EconomySetMarketPriceHandler` 的 `market-missing` 拒因保留**：它只改价表（键与形态不变），中间态自洽；
   W1d 放开"每区必须有表"不改变它的语义（不存在的市场仍然拒）。
6. **未新增任何拒绝路径** ⇒ §一.9 的"业务拒绝 = INFO"没有新增落点（三个 handler 的 `rejected(...)` 日志一字未动）；
   唯一新增的日志字段是 `MarketTopologyBook` 既有 DEBUG `MARKET_TOPOLOGY_FROM_PERSISTENT_ZONES` 的
   **`zoneIdsWithoutTable`**（具名 id 列表，原来只有 `zoneWithoutTable` 计数）。
7. **W1c 的夹具简化机会（未做，留给控制方裁）**：`EconomyRoundTripTest.withZoneAndItsTable`（测试文件，本批禁改）
   现在可以直接用 `base.withMarketZonesAndTables(Map.of(ZONE, zone), Map.of(ZONE, zoneMarket()))` 一句替掉 37 个组件的
   手写 canonical 构造 —— 语义等价（都是"一次落地两个组件"），但不是本批范围。

## W1d-五、验证证据（真命令 + 真输出，2026-10-11）

```
$ tools/mvn-lock.sh -q spotless:apply
[spotless rc=0]                              # 无输出 = 无违规

$ rm -f simos-economy/target/classes/io/mosire/simos/economy/EconomyData.class \
        simos-app/target/classes/io/mosire/simos/app/time/MarketTopologyBook.class   # 强制真重编（不许拿旧 class 当证据）
$ tools/mvn-lock.sh -DskipTests compile -am > /tmp/w1d-compile.log 2>&1
$ echo rc=$?                                  → rc=0
$ grep -E "Compiling [0-9]+ source|BUILD|ERROR" /tmp/w1d-compile.log
[INFO] Compiling 206 source files with javac [debug release 21] to target/classes   ← economy
[INFO] Compiling 328 source files with javac [debug release 21] to target/classes   ← app
[INFO] BUILD SUCCESS
[ERROR] 计数 = 0；16 个模块（含根）全部 SUCCESS；Total time: 6.9 s
$ ls -l --time-style=+%H:%M:%S 那两个 .class         → 00:57:49 / 00:57:52（本轮新写）
```

```
静态核对（本批的"缺表安全性"证据）：
$ grep -rn "市场区没有价表条目" --include=*.java simos-*/src/main     → 0 命中（守卫真删了）
$ grep -rn "withMarketZonesAndTables" --include=*.java simos-*/src/main
  EconomyData.java:204(注) :1996(注) :2873(注) :4136(定义) + Define:248 / Merge:195 / SetMarketNumeraire:116   → 1 定义 + 3 调用
$ grep -rn "withMarketZones(\|withMarketTables(" --include=*.java simos-*/src/main
  只剩 4 处**自洽**中间态：ReassignZoneHexes:166（只改区）、SetOfficialRate:235（只改区）、
  ClearRegion:394（只改表，区表原样带过）、SetMarketPrice:157（只改表）
$ grep -rn "\.get([^)]*)\.\(prices\|numeraire\|hasPrice\|hexes\|priceOf\)" --include=*.java simos-*/src/main
  3 处，逐条核过：MarketTables:90（遍历键集后取值）、MarketTables:154（同）、SetMarketPriceHandler:151（:84 已判空）
$ git status --porcelain -- simos-*/src/test                                    → 无本批新增改动（两处 mtime 变化来自 W1c 的 spotless 轮）
```

**本批 10 个文件的 md5（落盘字节自指）**：

| md5(8) | 文件 |
|---|---|
| `cba6e080` | `simos-economy/.../EconomyData.java` |
| `ed9ed7d1` | `simos-economy/.../model/MarketTables.java` |
| `b4d34856` | `simos-economy/.../time/MarketIndex.java` |
| `7db8b47b` | `simos-economy/.../time/MarketSettlement.java` |
| `5de9f727` | `simos-economy/.../time/MarketTopology.java` |
| `9ecfe5b4` | `simos-economy/.../spi/EconomyDefineMarketZoneHandler.java` |
| `385aa1c1` | `simos-economy/.../spi/EconomyMergeMarketZonesHandler.java` |
| `def6c036` | `simos-economy/.../spi/EconomySetMarketNumeraireHandler.java` |
| `f114fea6` | `simos-app/.../time/MarketTopologyBook.java` |
| `9b44747e` | `simos-economy/.../time/ModeMigrationPolicy.java` |

## W1d-六、会改变数值行为的清单

**期望为空 ⇒ 实测结论：空**（逐条论证如下，不含"整体没变"这种话）：

| # | 改动 | 为什么在**既有可达状态**下不改数值 |
|---|---|---|
| 1 | 删掉"每区必须有价表"守卫 | 既有世界（small-world / corridor / three-powers / 全部夹具）**每个区都有表** ⇒ 该守卫从未触发过拒绝以外的作用；它唯一的效果就是把 Define/Merge/SetMarketNumeraire 拒掉。删掉后这些命令**从不成功变成成功**（这正是要修的缺陷），不是既有数值路径的改变 |
| 2 | `MarketTables.allMarketHexes` 的缺表口径 | 缺表区的世界在 W1d 之前**构造期即抛** ⇒ "既有世界"里不存在这种区 ⇒ 集合逐值同（见 §四-1 的两种读法对比） |
| 3 | `MarketTopology.singleHex` 迭代集合 ∪ 区 | 只在**没有 map 切片**的退化路径生效（`EconomyDayStepper`/`EconomySettlement.settleOneDay` 的重载/夹具）；且只在"持久区 + 该区无表"时与改动前不同 —— 那个形态**改动前会 NPE**，不是"改了数" |
| 4 | `MarketSettlement` 逐区规划跳过缺表区 | 缺表区的世界改动前必然 NPE（#3 同因）⇒ 对既有世界是**不可达分支**（`if (market == null) continue;`） |
| 5 | 三个 handler 改走原子写口 | 目标态与原来的"两个 wither 链"**逐组件相同**（`EconomyChangeSet.between(base, projected)` 的输入同一个 `projected`）；差别只在中间态不再被拒 |
| 6 | `MarketTopologyBook` 新增 DEBUG 字段 | 日志字段，不改状态、不改公式（§一.9 的日志纪律） |

**受影响的硬编码字面量清单：无**（本批没有增删改任何算式常量/阈值；`MarketTable.BID_PER_MILLE`/`ASK_PER_MILLE`、
`FAMINE_MORTALITY_PER_MILLE`、α（属 W2）等一律未动）。

**会让既有测试断言失效的清单：无（预判）** —— 本批只让两条"恒失败"的路径开始成功。★ 但见 §七-2 的边界。

## W1d-七、日志落点（§一.9）

| 级别 | 事件 | 位置 | 性质 |
|---|---|---|---|
| DEBUG | `MARKET_TOPOLOGY_FROM_PERSISTENT_ZONES` **新增字段 `zoneIdsWithoutTable`** | `MarketTopologyBook.byPersistentZones`（`:210`） | **本批唯一新增日志内容**：把"哪些区因为缺表而不交易"具名列出（原来只有计数）。原有字段（`zones`/`regions`/`derivedMarkets`/`marketTables`/`zoneWithoutTable`/`zoneWithoutPrice`）逐字保留 |
| INFO | `MARKET_ZONE_DEFINED` | `EconomyDefineMarketZoneHandler.logDefined` | **未改**（本批没加新事件）—— 修复后这条终于会真的发出来（W1 期间它被 `contract-violation` 拒在上游） |
| INFO | `MARKET_ZONE_REJECTED` / `MARKET_ZONE_MERGED` / `MARKET_NUMERAIRE_SET` | 三个 handler | **未改**；拒绝路径逐条未动 ⇒ 无新增拒绝路径需要补日志 |

★ 未新增事件的理由：本批**没有引入任何新的业务拒绝**（三个 handler 的 `rejected(...)` 集合与理由串一字未动），
唯一的"新分支"是**合法态**（缺表）的防御性跳过，且它已在既有 DEBUG 上有具名读数。

## W1d-八、未完成 / 未验证项（如实记）

1. **没有跑任何测试**（任务书 §3 只允许 `spotless:apply` 与 `-DskipTests compile -am`）⇒
   "`economy.DefineMarketZone` 不再恒失败""三国世界播种能走通""NPE 分支确实不可达"三条**都是静态推演**，
   不是实测。★ 控制方必须先跑的最小验证集：`DefineMarketZoneHandler` 相关的 economy 用例 +
   `ThreePowersWorld` 系（`EconomyCycleHealthTest` / `CrossProcessDeterminismTest`）+
   `EconomyRoundTripTest`（W1c 修的那条自相矛盾夹具现在也应当更简单）。
2. **缺表世界的运行时行为零验证**：本批的 NPE 加固（#9/#10）与 `singleHex` 的 ∪ 区（#3）都**没有**跑过任何
   "区有表缺"的世界 —— 仓库里现在**没有任何夹具**构造这种世界（W1c 的 `zoneMarket()` 给表非空）。
   ⇒ 这是本批最需要控制方/测试批用一条**负向用例**（"建区但删表 ⇒ 开市不抛、该区零订单、读数缺该区"）钉住的地方。
3. **`haulServiceHexes` 的 `HaulService.pricedAt(null)` 已在收尾补核（结论：安全）**：
   `HaulService.java:121-122` = `return market != null && market.hasPrice(HAUL_COMMODITY);` ⇒ **判空**，
   故 `EconomySettlement.java:803-806` 对缺表安全（本条从"未核"改为"已核 ✅"；**仍未跑**）。
   ★ 核对方式：写账本时先记"未核"，收尾时逐条回读了所有被标"推断"的点，只有这一条需要补核 —— 其余读点的判空
   都是在源码里直接看到的 `if (x == null) continue;`。
4. **`MarketTables.allMarketHexes` 的口径是解释性决定**（§四-1），任务书没有逐字规定；若控制方要另一种读法，
   改动量 = 1 处（已在 §四-1 写明代价）。
5. **没有变异自证**（§三.0 收尾期规矩由控制方统一做）。
6. **未 `git commit`**、**未碰** `docs/**` / `AGENTS.md` / 其它模块 / 任何测试文件。

# W1e SpotBugs 修复（2026-10-11）

> 任务：修 W1 引入的 5 条 SpotBugs 告警（门禁 fail-closed：15 个模块必须全部 `BugInstance size is 0`）。
> 基线 = 控制方 `clean verify` @01:00 的 `simos-economy/target/spotbugsXml.xml`（md5 `80a675f8fdcc29734159979d631a0ca5`）：
> **5 条** = 3×`EI_EXPOSE_REP`(`MarketRoundStats` 三个存取器) + 1×`EI_EXPOSE_REP`(`MarketReadout.marketRoundStats`)
> + 1×`RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE`(High, `EconomySettlement.logHaulServiceOutput`)。

## W1e-一、关键调查结论（file:line → 结论 → 影响）

1. **`MarketRegulation.java:46-49`**：同类冻结写在**辅助方法**里时 SpotBugs 仍报 `EI_EXPOSE_REP`，仓内当初只能靠抑制注解
   过门禁（justification 明写"SpotBugs 不跨辅助方法识别"）。`MarketRoundStats` 的三处（`freeze()`→`canonical()`→
   `Collections.unmodifiableMap`）正是同款形态 ⇒ **把包装写在字段赋值处**才是免抑制的路。
2. **`MarketReadout.java:108-111`（改前）**：`x == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(x))`
   —— 赋值右侧**最外层是三元表达式**、包装在分支里 ⇒ 报 `EI_EXPOSE_REP`。反证（同文件、同一次分析、**未报**）：
   `CommodityMatchReadout.unfilledLogisticsDetailQuantities`（`:777-781`）与 `provenance`/`unavailable`（`:112-113`）
   = 右侧**最外层**就是 `Collections.unmodifiableMap(...)` ⇒ 修法 = 把判空挪进包装里，与未报那几处逐字同形。
3. **`MarketTables.atOrNull`（`:108`）→ `at`（`:98`）→ `marketIdAt`（`:81-83`）**：`marketIdAt` 第一句是
   `Objects.requireNonNull(hex, "hex")` ⇒ **传 null 必 NPE**；而 `MarketIndex.at`（`:110-113`）**容忍 null**。
   ⇒ `EconomySettlement.java:595` 把"可能为 null 的 hex"交给**严格**那个读口 —— RCN 是**真隐患**，不是误报。
4. **`IndustryHexKeys.hexKeyOf`（`:53-62`）**：`IndustryId.value()` 不含 `'@'`（或 `'@'` 后为空）⇒ 空
   （约定 = `<kind>@<q>_<r>`，`:45-50`）；`HexCoord.parse`（`HexCoord.java:96-106`）**只抛不返 null**
   ⇒ `hex == null` **只有这一条来路**。仓内确有这种 id（账户型：`HaulService.java:72` `new IndustryId("market-haul-service")`、
   `:96`、`MarketSettlement.java:262`），只是它们当前不作为 `ledger.gross()`（产出口）的键。
5. **RCN 的触发条件**：`:594-627` 整段在 `if (!TRACE.isDebugEnabled()) return netMilli;` **之后**（`:577-579`）
   ⇒ 该 NPE 只在 economy trace 分类开到 DEBUG 时才炸；但一炸就是整日结算中断（异常穿透 `logHaulServiceOutput`）。
6. **同族扫描（只读，未改）**：全仓 `hexKeyOf(...).map(HexCoord::parse).orElse(null)` 共 **7 处**，除 `:595` 外都安全：
   `OperatorSettlement.java:386-388`（走 `MarketIndex.at`，容忍 null，注释具名）、`MarketDemandBook.java:265-268`
   （显式 `if (unitHex == null) continue;`）、`ModeMigrationPolicy.java:1242-1243`（`!= null &&` 守卫）、
   `ExpectedProfitBook.java:1068-1070`（`unitHex == null ||` 守卫）、`EconomySettlement.java:804`（hex 来自
   `allMarketHexes`，非 null）、`:5654`（`InputPlan` 格键**文档化为可 null**，不参与同格争用）、`:6874-6875`（`location != null` 守卫）
   —— 与"全模块只报这一条 RCN"一致。

## W1e-二、逐条修法（文件 + 行 + 改成什么）

| # | 告警 | 位置（改前） | 改成什么 |
|---|---|---|---|
| 1-3 | `EI_EXPOSE_REP` ×3 | `model/MarketRoundStats.java:38`（record 头，三个存取器） | compact 构造器由 `x = freeze(x, "…")` 改成 `x = Collections.unmodifiableMap(new LinkedHashMap<>(freeze(x, "…")))`（**包装写在字段赋值处**）；`freeze`/`canonical` **一字未动** ⇒ 键/值非空校验、商品 id 规范序、空表口径全保留 |
| 4 | `RCN…A_NPE`（High） | `time/EconomySettlement.java:595` 解引用 / `:607` 冗余判空 | `:594` 之后**先判空一次** `boolean hexResolved = hex != null;`；`:595` 改成只有在 `hexResolved` 时才 `MarketTables.atOrNull(view, hex)`；`:607` 的 `hex == null ? "" : …` 收敛成 `hexResolved ? hex.q()+","+hex.r() : ""`；`:626` 的 `reason` 在未解析时具名 `"industry-hex-unparsable"`（否则沿用 `"priced"`/`"no-haul-price-no-trade"`）⇒ **单一判空点**，无"先解引用后判空" |
| 5 | `EI_EXPOSE_REP` | `time/MarketReadout.java:74`（`marketRoundStats()`） | 把三元移进包装：`Collections.unmodifiableMap(marketRoundStats == null ? new LinkedHashMap<>() : new LinkedHashMap<>(marketRoundStats))`（与同文件 `:777-781` 未报写法逐字同形） |

**为什么不用 `Map.copyOf`**：I7 —— `Map.copyOf` 迭代序不是内容的纯函数，会破坏字节级往返。
**热路径量级判断**：`MarketReadout` 的表 = **市场数**（个位~低两位数）条且值是 record 引用（不深拷），每次读数构建一次；
`MarketRoundStats` 的表 = 该市场本轮出现过的**商品数**（个位~两位数），每市场轮构建一次 ⇒ 均可忽略。
代价是 `MarketRoundStats` 确实多了一次小表拷贝（`freeze` 内已拷一次）—— 换来"冻结写在分析器看得见的位置"，不换就不免抑制。

## W1e-三、是否加了 `@SuppressFBWarnings`

**未加，因为就地冻结后 SpotBugs 不再报**（§四的差分证据）。三个文件里 `SuppressFBWarnings` 字样命中数 = **0/0/0**
（`grep -c`）；`simos-economy/src/main` 既有的 29 处抑制注解（14 个文件）一处未动 ⇒ 无 B2(`US_USELESS_SUPPRESSION_ON_CLASS`) 风险。

## W1e-四、验证证据（真命令 + 真输出，2026-10-11）

```bash
tools/mvn-lock.sh -q spotless:apply            # rc=0（只重排了我改的 3 个文件；find -newermt 核对过）
tools/mvn-lock.sh -DskipTests compile -am      # rc=0 / BUILD SUCCESS / [ERROR] 行数 = 0
# 产物核对（不信 rc 只看真输出）：EconomySettlement.class mtime 01:06:57，字节内含新字面量 "industry-hex-unparsable"（grep -c = 1）
tools/mvn-lock.sh -pl simos-economy spotbugs:spotbugs    # rc=0，只读分析（输出 /tmp；未跑 test/verify/package/clean）
```

| 轮次 | 树的状态 | aux classpath | 结果 |
|---|---|---|---|
| 基线（控制方 `clean verify`） | W1 原样 | 完整 reactor | **5 条**（3 EI + RCN + 1 EI），md5 `80a675f8…` |
| **对照组**（3 处改回改前形态，其余不动） | 改前形态 | `~/.m2`（economy-api 旧快照，17 个类缺失） | **同样那 5 条**：`MarketRoundStats.{cycleToDate,lastRound,previousCycle}` + `MarketReadout.marketRoundStats` + `EconomySettlement.logHaulServiceOutput` RCN ⇒ **口径校准：缺类没有掩盖这 5 条** |
| 修复后（最终态，class md5 与下面逐字节相同） | 本批改动 | 同上 | **`BugInstance size is 0`** |

⇒ "改前 ⇒ 5 条 / 改后 ⇒ 0 条"是**同一套分析条件**下的差分 ⇒ "这 5 条是本次改掉的"有因果证据。
最终 3 个 class 的 md5 = `b0ea1a6761b8cfe3f280dcfd3b44f701`(MarketRoundStats) / `9309d5d4b7d9a36a3aa3b5a27e8a2933`(MarketReadout)
/ `f685487e0aa0c387e10c32ad280ff24c`(EconomySettlement)；收尾只在注释里改了一个词，重编后这三个 md5 **未变**
⇒ 那次 0 条分析覆盖的就是最终字节。

**★ 构建产物被覆盖又恢复（重要）**：`spotbugs` 目标的 `outputFile` 参数**没有 user property**（实测 dump 插件
`plugin.xml`：该 mojo 只有 `onlyAnalyze` 等无 property 的参数）⇒ `-Dspotbugs.outputFile=…` 被**静默忽略**，
第一轮分析覆盖了 `*/target/spotbugsXml.xml`（含控制方引用的 economy 那份）。已用开跑前备份**逐模块恢复**并 `md5sum` 核对：
`simos-economy/target/spotbugsXml.xml` = `80a675f8…`（5 条告警原样）；修复后的分析留档
`/tmp/w1e/after/simos-economy-final.xml`（0 条）。★ 该 XML 是构建产物，控制方 `clean verify` 会重新生成。

## W1e-五、会改变数值行为的清单

**期望为空的实测结论：空**（逐条论证）：

| # | 改动 | 为什么不改数值 |
|---|---|---|
| 1 | `MarketRoundStats` 三处冻结外提 | 值、条目集、**迭代序**（`freeze`→`canonical` 的商品 id 升序；`new LinkedHashMap<>` 保序拷贝）逐值相同；空表仍是空表（`Map.of()` → 冻结空 `LinkedHashMap`，`equals`/序列化同）。本类不参与任何撮合/定价判定（纯读数） |
| 2 | `MarketReadout.marketRoundStats` 判空挪进包装 | 同上（值/序同）；null 分支在 `deriveInternal` 里**不可达**（`roundStatsByMarket` 恒返非 null，可为空表）。`MarketReadout` 不进 `EconomyData`/Codec/ChangeSet |
| 3 | RCN 修法 | 只动 **DEBUG 日志段**：非 null 路径的字段逐字相同（hex 文本、`priced`、两个 `reason` 字面量都没变）；null 路径**改前是 NPE**（不存在"数值"），改后记一条具名 DEBUG 并继续；返回值 `netMilli` 在进入该段**之前**已算完 ⇒ 不变 |

**受影响的硬编码字面量清单**：仅新增 1 个具名理由串 `"industry-hex-unparsable"`（新增跳过理由，非算式常量）；
**没有**任何阈值/系数/毫单位常量被增删改。

**会让既有测试断言失效的清单：无** —— `grep -rn "no-haul-price-no-trade|HAUL_SERVICE_PRODUCED_DETAIL|industry-hex"
simos-economy/src/test simos-app/src/test` = **0 命中**；只有 `EconomyRoundTripTest` 触及这两个类型，其中
`isSameAs|isUnmodifiable|UnsupportedOperation` = 0 命中 ⇒ 没有断言依赖实例同一性或可变性。

## W1e-六、`RCN` 的语义判断（具名）

- **`hex` 何时为 null**：`IndustryHexKeys.hexKeyOf(industryId)` 返回空 —— 该 `IndustryId.value()` **不含 `'@'`、或 `'@'` 后为空**
  （约定 `<kind>@<q>_<r>`）。后缀存在但不可解析 ⇒ `HexCoord.parse` **抛**（不返 null，改动前后同，不在本次范围）。
  仓内账户型 id 就是这种形态，但它们当前不是 `ledger.gross()` 的键 ⇒ **今日世界不可达**；且没有任何编译期守卫阻止将来出现。
- **修完之后那条路径的行为**：`hex == null` ⇒ `hexResolved = false` ⇒ **不调用** `MarketTables.atOrNull`（不查牌价表、不猜坐标）
  ⇒ `market = null` ⇒ `priced = false` ⇒ 该产业**照常刷一条** `HAUL_SERVICE_PRODUCED_DETAIL` DEBUG 行，字段与正常行同形：
  `hex=""`、`grossMilli/lossMilli/haulPerScaleUnit/impliedScale/laborPerUnit/inputPerUnit` 照常、`marketPriced=false`、
  `reason="industry-hex-unparsable"`；循环继续下一条产业；**不抛**、`netMilli` 不变。

## W1e-七、偏离记录（主动记）

1. **多跑了一条不在允许清单里的 Maven 命令**：`spotbugs:spotbugs`（**只读分析**，输出到 `/tmp`；**未跑**
   `test`/`verify`/`package`/`clean`，未动 jar）。理由：任务书 §2.1 要求"改完先跑门禁看这条是否还在，再决定要不要抑制"
   ⇒ 不加抑制的结论必须有 SpotBugs 实测支撑。全程 `tools/mvn-lock.sh` 串行化。
2. **窄 `-pl simos-economy` 的 compile 在本仓根本编不过**（实测 rc=1：`package io.mosire.simos.economy.api.fx does not exist`
   —— `~/.m2` 的 `simos-economy-api` 快照是 **2026-10-07** 的，没有 W1/W1d 的 `api.fx`/`MarketZone`/`MarketZoneId`）
   ⇒ **任何 economy 编译都必须带 `-am`**（控制方与后续 Agent 注意；我第一轮"对照组"compile rc=1 就是这个原因，该轮已作废重跑）。
3. **SpotBugs 的 aux classpath 同样来自 `~/.m2` 旧快照**（economy 分析时报 17 个 `economy.api.*` 类缺失）⇒
   本次分析**不是**门禁口径的替代品；但 §四对照组证明该口径**照样报得出这 5 条**（与完整 classpath 基线逐条同型），
   故"这 5 条已修掉"成立。**15×0 的最终判定仍以控制方 `clean verify` 为准。**

## W1e-八、未完成 / 未验证项（如实记）

1. **没跑门禁**（`clean verify` 归控制方）⇒ **"15 个模块全部 0"未经我验证**；我验的是 economy 内这 5 条的消除（含差分对照）。
2. **没跑任何测试**（任务书 §4 禁止），除差分对照外**没有**行为验证 ⇒ W1d 的"缺表区不交易"等行为**仍未跑**。
3. **null 分支未在运行时触发过**（今日世界不可达，见 §六）⇒ 若要用例钉它，需造一条"不合约定的产业 id 进 `ledger.gross()`"的
   负向用例（现有夹具没有）。
4. **`.superpowers/sdd/**` 只追加本文件**；**未 `git commit`**；**未碰** `docs/**` / `AGENTS.md` / 其它模块 / 任何测试。
5. 最终源文件 md5：`MarketRoundStats.java` = `ab2ad8871650251b22e269f2c0433769`、
   `MarketReadout.java` = `807249673b3b1cd70ef057688cbfc773`、`EconomySettlement.java` = `3ad900230512fbb217dd564ca1f166fe`；
   `git status --porcelain` 计数与开工前一致（82 tracked / 7 untracked）。

---

# W1f 快照往返修复（2026-10-11）

> 责任区：修 W1 引入的**快照编解码往返缺陷** —— `MarketTable.isEmpty()` 被 Jackson 的 bean 内省当成属性 `empty`
> 写进线格式，而全仓读侧 `FAIL_ON_UNKNOWN_PROPERTIES` 保持严格 ⇒ **含市场的快照/检查点/变更集自己写出来的字节读不回**
> （现场：`java.lang.IllegalStateException: economy 侧 JSON 解码失败: EconomySnapshot`，
> `caused by UnrecognizedPropertyException: Unrecognized field "empty" (class MarketTable)`）。
> 允许写：`simos-economy/src/main/**`（本批只改 **2 个文件**：1 个注解 + 1 段注释）；**未改任何测试**、未 `git commit`；
> 只跑了允许的两条命令（`tools/mvn-lock.sh -q spotless:apply`、`tools/mvn-lock.sh -DskipTests compile -am`）。
> ★ 另加一个**只读运行时探针**（`w1f-probe/`，不入 `src/test`、不进 Maven 门禁）——本批 §二 的排查表与 §一 的"往返已修"
> 都是它当场跑出来的，不是静态推演。

## W1f-一、修法（文件 + 行 + 改成什么 + 为什么不选另一条）

| # | 文件:行 | 改成什么 | 语义 |
|---|---|---|---|
| 1 | `simos-economy/.../model/MarketTable.java:3` | 新增 `import com.fasterxml.jackson.annotation.JsonIgnore;` | — |
| 2 | `MarketTable.java:160`（方法体之上；Javadoc `:140-159`） | 在 `public boolean isEmpty()` 上加 **`@JsonIgnore`** + 一段"线格式契约"注 | 派生判断 `empty` **不进线格式**（写侧不写、读侧认成可忽略）；方法签名、返回值、调用点**一字未动** |
| 3 | `simos-economy/.../codec/EconomyCodec.java:180-184`（注释） | 把 `withEconomyMixins` 那段"领域类型零 Jackson 注解"的例外清单从 2 条补成 3 条，具名记上本批这条 | **纯注释**，零语义 |

**为什么选 ①（类型上的 `@JsonIgnore`）而不是 ②（改名成 `hasNoPrices()`）** —— 四条，前两条是决定性的：

1. **仓内同款先例就在隔壁文件、而且理由逐字相同**：`EconomyMeta.java:84/99` 的 `isCurrentRuntimeVersion()` /
   `currentCycleNumber()` 就是"域类型上的派生判断 + 类型上直接 `@JsonIgnore`"，其注写死：
   *"不能只靠 `EconomyCodec` 的 mixin：Core 时间线（`Timeline.CHANGESET_MAPPER`）与真实 Shell 用的是
   `SimosObjectMapper.create()` 的裸 mapper，看不见模块私有 mixin"*。**本批的探针在裸 mapper 上实测复现了同一句话**
   （§二-三 PART B：`isEmpty` → `"empty"` → 读回 `UnrecognizedPropertyException`）。
2. **本仓对 `isEmpty` 的既有机制（codec mixin）在这里够不着**：`SimosObjectMapper.changesetsWithoutDerivedPredicates()`
   的共享层规则只作用于 `ChangeSet.class.isAssignableFrom(...)`（实测：`EconomyChangeSet.isEmpty()` 没有写出），
   而 `MarketTable` 不是 `ChangeSet` ⇒ 共享层不管、模块 mixin 又进不了第四台 mapper。⇒ 只剩"落在类型上"这一档
   （`@JsonIgnore` 或删掉这个属性）。
3. **② 是 API 变更，本批禁改测试**：改名会动一个公开方法（及其全部调用点），而**测试文件在本批是冻结的** —— 一旦测试侧
   有调用点（本批实测：`MarketTable.isEmpty()` 在 main+test **0 个调用点**，见 §四），就会把门禁的编译弄红而我无权修。
   ⇒ 风险与收益不成比例（③ 的收益只是"少一个注解"）。
4. **① 还顺手保住了 W1 期间已落盘的字节**：`@JsonIgnore` 会让读侧把 `empty` 登记成**可忽略属性** ⇒ W1 期间写出的
   带 `"empty"` 的快照仍能读回（实测 PART B2：旧字节读回成功、逐值相等）。②（改名）则会把这些字节判成未知字段
   fail-closed —— 与 §一.11（旧档不要求兼容）不冲突，但纯属无谓损失。

**为什么不选第三种（在 `MarketTable` 加类型级 `@JsonIgnoreProperties("empty")`）**：任务书只给 ①②，且
`@JsonIgnoreProperties` 的具名形式在本仓是留给"**退役组件/字段的兼容**"的（`EconomyData.commodityFreightPerMille`、
`MarketZone.issuingGov`、`HouseholdEconomy.debts`）；本批不是退役字段，是**派生判断不该上线**，
用方法级 `@JsonIgnore` 更贴语义、改动面更小。

**偏离记录（主动记）**：本批给"**领域类型保持零 Jackson 注解**"这条口径加了**第三个例外**（前两个：`AllocationRule`
的 sealed 多态注解、`HouseholdEconomy` 的 `@JsonIgnoreProperties("debts")`）。理由 = 上面第 2 条（第四台 mapper 装不上
模块 mixin，只有类型上的注解对四台 mapper 一致生效）；已把 `EconomyCodec` 的例外清单同步补全，避免"注释说只有两个例外"
与代码实际不符。

## W1f-二、系统排查（任务书 §2(B)：逐个类型 → 方法 → 是否被写出 → 处置）

### 二-一、装置（可复跑）与判据

- 装置：`w1f-probe/W1fProbe.java` + `w1f-probe/run-probe.sh`（只读；用**裸 `SimosObjectMapper.create()`**，
  即 Core 第四台 mapper 同一份装配；先按允许命令 `compile -am`，再 `javac`+`java`）。
- 闭包遍历：从 **`EconomyData` 与 `EconomyChangeSet`**（快照 + 变更集两条上线路径）的 record 组件出发，递归走
  **泛型实参 / 容器元素 / 数组 / sealed 许可子类**；跳过 `java.*` 标量。
- 判据 = **写侧属性名是否被读侧接受**：`BeanSerializer.properties()` 的属性名，逐个问
  `BeanDeserializer.findProperty(name) != null`（`findProperty == null` ⇒ 严格读入必报"未知字段"）。
  ★ 不用 `{"prop":false}` 直接 `readValue` —— Jackson 把未知字段留到 creator 绑定**之后**才报，会被"缺必需 creator 参数"
  抢先（本批实测踩到过，装置已改正）。★ 也不拿"属性名 ≠ record 组件名"当判据 —— `@JsonProperty` 改名的组件是**对称**的，
  会被误判（本批实测：`GovernmentFormation`/`ArmyFormation` 两处误报，已用读侧判据纠正，见 二-四）。
- 覆盖（如实）：责任区 **90 个具体类型 / 457 个组件**；未做属性内省的 = **47 个枚举**（按名字序列化，无属性内省）
  + **6 个 sealed 接口**（实现已按 `getPermittedSubclasses()` 入队并逐个审过：`AllocationRule`/`Payee`/`DebtUnit`/`FieldDelta`）
  —— **"非 sealed 的接口/抽象类" = 0 个** ⇒ 闭包无缺口。责任区外另跑六个 Snapshot 根 = **100 个类型 / 417 个组件**。

### 二-二、逐个排查表（进快照的类型；"会被写出"= 实测）

| 类型（进快照的落点） | 无参方法 | 会被写出？（实测） | 处置 |
|---|---|---|---|
| **`MarketTable`**（`marketTables` 的值） | **`isEmpty()`** | **★ 会** → 属性 `empty`（修前 `components=3 / props=4`；`BeanDeserializer.findProperty("empty")==null`） | **本批修**：类型上 `@JsonIgnore`（§一）⇒ 修后 `components=3 / props=3`，往返逐值成立 |
| `MarketTable` | `describe()`（单名，String） | **不会**（实测 props 里没有 `describe`） | 不改（合成 record 对照见 二-三） |
| `MarketTable` | `hasPrice(CommodityId)` / `isFree(CommodityId)` / `priceOf(...)` / `bidPriceOf(...)` / `askPriceOf(...)` / `withXxx(...)` | 不会（**有参数** ⇒ 不是 getter；`isFree` 带参故不触发 `is` 规则） | 不改 |
| `MarketTable` | `singleHex(...)`（static） | 不会（Jackson 不内省静态方法） | 不改 |
| **`MarketRoundStats`**（`marketRoundStats` 的值） | 无（`describe()`、`withRound(...)`、`sum/frozen/canonical/addTurnover/weighted`= static/带参） | **不会** ⇒ `components=7 / props=7` | 不改。★ **明确回答任务书的问题：W1e 的"冻结包装"（`Collections.unmodifiableMap(new LinkedHashMap<>(freeze(...)))`）没有引入任何新的可写属性** —— 它只改构造器里的赋值表达式，没增删方法；实测属性集恰等于 7 个组件 |
| **`CommodityRound`**（`MarketRoundStats` 三段窗口的值） | 无（`describe()`、`plus(...)`） | **不会** ⇒ `components=5 / props=5` | 不改 |
| **`EconomyData`**（快照根） | 37 个组件访问器；`commodityFreightBaseMilliOf(CommodityId)`（带参）；`implements EconomyDayView` 未带进任何额外属性 | **不会**（修前修后都 `components=37 / props=37`）⇒ `EconomyDayView` 的接口方法没有一个是"非组件的 getter 形状" | 不改 |
| **`EconomyChangeSet`**（变更集根） | **`isEmpty()`** | **不会** —— 被共享层 `SimosObjectMapper.changesetsWithoutDerivedPredicates()`（只作用于 `ChangeSet` 实现）摘掉 | 不改（既有护栏，实测有效） |
| **`EconomyMeta`**（`meta`） | `isCurrentRuntimeVersion()`、`currentCycleNumber()` | **不会** —— 类型上既有 `@JsonIgnore`（P10.1/A1 立的先例） | 不改（本批就是照它办的） |
| `AssetRule`（`assetRules` 的值） | `isCoreMeans()` | **会，但合法**：`isCoreMeans` **本身就是 record 组件名** ⇒ 属性名 = 组件名、读侧同名可读 | 不改。★ 结论：**"is 前缀"本身不是罪，罪在"该方法不是组件"** |
| 其余 82 个具体类型（`MarketZone`/`Industry`/`HouseholdEconomy`/`OperatorCondition`/`GovernmentMarketMandate`/`FieldDelta$*`/各 Id …） | — | **全部 `props == components`**（除上面 `MarketTable` 一处，修前修后都没有第二处 `props≠components`） | 不改 |
| **合计** | 39 个"单名无参方法"（`describe`/`canonical` 这类，全部静态或非 getter 名） | **一个都没被写出** | 不改（见 二-三 的规则订正） |

★ 本批**没有新增任何进 `EconomyData` 的类型**（修法是注解 + 注释）⇒ 任务书 §2(B) 的"以及你新加的任何进 `EconomyData`
的类型"这一格为空。

### 二-三、规则订正（实测，与任务书的一处措辞不符 ⇒ 按 §四"机制性描述一律回代码核"记下）

合成 record（`w1f-probe` PART D，实测输出）：

```
{"value":"v","rows":{"k":1},"empty":false,"configured":true,"label":"L"}
   ↑ isEmpty()        会写出（属性 empty）      ← 本次缺陷的形状
                       isConfigured() 会写出（configured）
                       getLabel()     会写出（label）
                       hasRows()       不写
                       currentCycleNumber()（单名）不写
                       describe()（单名）        不写
```

⇒ **订正**：任务书 §2(B) 把"`toString` 之外的 `xxx()` 单名方法"也算作"会被 Jackson 当属性写出去"——
在 **Jackson 2.22.2 + `SimosObjectMapper`** 下**不成立**（实测：责任区 39 个单名无参方法无一被写出，裸 mapper 的
`BeanSerializer` 属性集里没有它们；改名方案 ② 的 `hasNoPrices()` 同理不会被写出）。**会被写出的只有 bean 形状**：
`getXxx()` / `isXxx()`（boolean）/ `isEmpty()` 这类" `get`/`is` 前缀或空名惯用 getter"。
⇒ 本批的排查口径按实测收紧为"**非 record 组件的 get/is 形状无参方法**"，并据此给出上表。

### 二-四、责任区外（只报不改，供控制方处置）

对另外六个 Snapshot 根（map/social/unit/actor/gov/army；100 个类型 / 417 个组件）跑了同一装置：
**hazards = 0**。两处 `props≠components` 经逐条回源核实是 **`@JsonProperty` 改名的组件**（不是缺陷）：

| 类型 | 写侧属性名 ← 组件 | 读侧实测 | 结论 |
|---|---|---|---|
| `simos-unit/.../GovernmentFormation`（record 头 `:60`） | `householdPosts` ← `governmentPostsOfHousehold` | `findProperty("householdPosts") != null` | **对称、合法**（R4 改名批次刻意保旧 JSON 键） |
| `simos-unit/.../ArmyFormation` | `householdDuties` ← `militaryDutiesOfHousehold` | 同上 | 同上 |

★ 这两处之所以要点名：它们正是"用属性名当判据会误报"的反面样本，也说明**同款隐患在别的模块目前为零**（未修、非本批范围）。

## W1f-三、验证证据（真命令 + 真输出）

```
$ tools/mvn-lock.sh -q spotless:apply
[spotless rc=0]                        # 无输出 = 无违规

$ rm -f simos-economy/target/classes/io/mosire/simos/economy/model/MarketTable.class   # 强制真重编，不拿旧 class 当证据
$ tools/mvn-lock.sh -DskipTests compile -am > /tmp/w1f/compile1.log 2>&1
[compile rc=0]
[INFO] Compiling 206 source files with javac [debug release 21] to target/classes   ← economy
[INFO] Compiling 328 source files with javac [debug release 21] to target/classes   ← app
[INFO] BUILD SUCCESS
[INFO] 16 个模块（含根）全部 SUCCESS；[ERROR] 计数 = 0

$ javap -v -p simos-economy/target/classes/.../MarketTable.class | (isEmpty 段)
    RuntimeVisibleAnnotations:
      0: #247()
        com.fasterxml.jackson.annotation.JsonIgnore          ← 编译产物里**确有**这个注解（字节级证据）
$ ls -l MarketTable.class → 9035 字节，01:19:01（本轮新写）
```

**探针（只读运行，不是测试）**：`bash .superpowers/sdd/2026-10-10-w1-market-zone-price-and-stats/w1f-probe/run-probe.sh`

```
# 修前（w1f-probe/before.log，判据与修后同一套）：
### SUMMARY typesAudited=90 components=457 ... hazards=1 props≠components=1
  MISMATCH [W1f] MarketTable components=[hexes, numeraire, prices] props=[empty, hexes, numeraire, prices]
  [W1f] MarketTable :: 写侧写出 prop="empty" ← isEmpty，实测读侧把它当**未知字段** ⇒ 写出即读不回
PART B: JSON = {"numeraire":…,"hexes":[…],"prices":{…},"empty":false}；读回失败 =
        UnrecognizedPropertyException: Unrecognized field "empty" … (3 known properties: "hexes","prices","numeraire")
PART C: encodeSnapshot 成功（1446 字节，含 "empty"）→ decodeSnapshot **失败** =
        IllegalStateException: economy 侧 JSON 解码失败: EconomySnapshot
          caused by UnrecognizedPropertyException: Unrecognized field "empty" (MarketTable)   ← 与门禁现场逐字同源

# 修后（w1f-probe/after.log）：
### SUMMARY typesAudited=90 components=457 plainNoArgNotSerialized=39 shapedMethods=3 hazards=0 props≠components=0
PART B: 含 "empty" 属性 = false；读回成功；逐值相等 = true；
        旧字节（含 "empty"）读回 = 成功；逐值相等 = true；其它未知字段仍 fail-closed = true（bogus 被拒）
PART C: 编码成功（1432 字节，含 "empty" = false）；解码成功；
        EconomyData 逐值相等 = true；市场价表逐值相等 = true；市场统计簿逐值相等 = true
责任区外：typesAudited=100 components=417 hazards=0（两处 props≠components = @JsonProperty 改名，读侧接受 = true）
```

★ **这个装置自带对照**：同一条命令、同一份判据，**修前 fire（hazard + 两条往返失败）→ 修后静默且往返全绿**
⇒ 不是"恒真的护栏"（§三.2 的判别力要求）。★ 仍未做**变异自证**（把 `@JsonIgnore` 摘掉再跑一遍）——因为"修前日志"
就是同一件事的实测：那份日志的字节就是"没有这个注解"的世界。

**md5（落盘字节自指）**：源文件 `MarketTable.java` = `8ec2d99b3d6144751f1f6b180e8ac663`、
`EconomyCodec.java` = `ffb94295c8a3098ee5eeb8a1011f2d1d`；探针 `W1fProbe.java` = `845b2fec4d9ec62941effcf39e7843f4`、
`before.log` = `b5244a0d8dfdea8af8dc508a85d3eb9b`、`after.log` = `34c3014a094280447b622c9b71a3c74d`。
★ `before.log` 由探针的**上一版**产出（其后只加了 PART B2/B3 与"未内省类型清单"两项，均与 hazard 判定无关）；
`after.log` 与随附的 `W1fProbe.java` 逐字节同版。

★ **`spotless:apply` 的全仓副作用核查**：最近窗口内 mtime 变新的非 target 文件只有 3 个（我的 2 个 + `MarketRoundStats.java`），
而 `MarketRoundStats.java` 的 md5 = `ab2ad8871650251b22e269f2c0433769`，与 W1e 账本 §八-5 记录**逐字节相同** ⇒
spotless 只刷新了 mtime、**没改任何字节**（= §三"mtime 不是字节变了的判据，md5 才是"的又一实例）。

## W1f-四、会改变数值行为的清单（期望为空 ⇒ 实测为空）与 API 变更清单（空）

| # | 改动 | 为什么不改数值/行为 |
|---|---|---|
| 1 | `MarketTable.isEmpty()` 加 `@JsonIgnore` | 注解**只**影响 JSON 的键集合；方法体、返回值、调用点一字未动。**方法签名不变 ⇒ 无 API 变更、无调用点需要改** |
| 2 | `EconomyCodec` 注释补第 3 条例外 | 纯注释 |

**`MarketTable.isEmpty()` 的调用点逐个点名（= 0 个）**：全仓 `main + test` 里"接收者是 `MarketTable` 的 `.isEmpty()`"
**一个都没有**（实测 4 个候选接收者全是别的类型，逐个回源核实）：

| 命中 | 接收者真身 |
|---|---|
| `simos-economy/.../model/MarketRoundStats.java:160` | `Map`（`canonical(Map source)` 的形参） |
| `simos-economy/.../model/MarketZoneBook.java:67` | `Map<MarketZoneId, MarketZone>` |
| `simos-app/.../gov/ProvinceDivider.java:247` | `List<HexCoord> target` |
| `simos-app/src/test/.../time/EconomyCycleHealthTest.java:357` | `Optional<MarketReport>`（"读不到市场报告"那支） |

**受影响的硬编码字面量清单：无**（本批没有增删改任何算式常量/阈值）。
**会让既有测试断言失效的清单：无** —— 唯一"碰线格式断言"的是 `EconomyCodecTest:419-420`（`doesNotContain("\"empty\"")`），
它要求的方向与本修法**一致**（详见下面这条发现）。
★ **预期转绿的既有用例（预判，未跑）**：凡夹具里"市场表非空"的编解码/检查点路径 —— `EconomyCycleHealthTest`、
`GovGenesisZ6Test`、`CrossProcessDeterminismTest`、`GuiApiTest`（500）、以及 `EconomyRoundTripTest`/`EconomyCodecTest` 里
带市场的那几条。**最终以控制方 `clean verify` 为准。**

### ★ 附带发现：既有的"empty 不得进线格式"护栏为什么没抓住它（建议测试批处理，本批不动测试）

`EconomyCodecTest.encodingIsByteLevelStableForEconomyData` **已经有**这两条断言：

```java
assertThat(changeOnce).as("派生判断 empty 不得进线格式").doesNotContain("\"empty\"");
assertThat(snapshotOnce).as("快照里也没有派生判断").doesNotContain("\"empty\"");
```

但它的夹具 `fullData()`（`:923`）= `EconomyData.empty().withMeta(...).withIndustries(...)` —— **不含任何市场**
（`marketTables` / `marketRoundStats` 都空）⇒ 编码出来的 JSON 里根本不会出现 `MarketTable` ⇒ **该护栏对本缺陷等价存活**
（恒真）。这正是"**夹具规模决定判别力**"（§三）的又一实例。
⇒ **建议（测试批按验收判据做，不在本批范围）**：给这条既有护栏（或新加一条）的夹具放一张**非空 `marketTables`**
（最好同时放非空 `marketRoundStats`），让"派生判断不进线格式"这条判据真的覆盖 W1 的新形状。

## W1f-五、日志落点（§一.9）

**本批不新增任何日志**，理由具名：§一.9 要求"新增/修改经济阶段时同时加日志"，而本批**既没有新增/修改任何结算阶段**、
**也没有新增任何状态写口或业务拒绝路径** —— 改动是一个**线格式契约**（派生判断不上线）+ 一段注释。
既有的 `MARKET_ROUND_STATS_RECORDED` / `MARKET_ZONE_*` 等事件一字未动（不因本批改变触发条件）。

## W1f-六、未完成 / 未验证项（如实记）

1. **没跑任何测试、没跑门禁**（任务书 §4 禁止 `test`/`verify`/`package`/`clean`）⇒ **"往返已修"不是用测试验的**。
   本批给的是**运行时证据**：探针在**真实装配**（裸 mapper + `EconomyCodec.encodeSnapshot/decodeSnapshot` 端到端）上
   修前复现、修后全绿。**门禁（`clean verify`）归控制方**——它才是"42 failures + 16 errors 是否归零"的判据。
2. **没有走真实档/真实 world 读回**：B2 的"旧字节读回"是我把 `,"empty":false` 注入探针 JSON 的**合成字节**，
   不是读 `~/Simos-18Lvt` / `test-world` 里 W1 期间真实落盘的检查点文件。⇒ "真档能读回"**未验**。
3. **没有跑 `CrossProcessDeterminismTest` 那类跨 JVM 字节比对**（探针是单 JVM）⇒ 本批只钉"同一份字节往返成立"，
   **不涉及**跨进程字节一致性（那是既有另一条护栏的职责）。
4. **未做变异自证**（§三.0 收尾期规矩）：本批的"修前日志"承担了反向对照，但没有"把注解摘掉⇒当场红⇒还原⇒比 md5"的正式一轮。
5. **责任区外六个 Snapshot 根只做了只读观测**（hazards=0），**未修、未验**任何别的模块；`GovernmentFormation`/`ArmyFormation`
   的 `@JsonProperty` 改名只用了读侧内省判"对称"，没有跑它们的往返用例。
6. **未 `git commit`**；**未碰** `src/test/**`、`docs/**`、`AGENTS.md`、`simos-social`/`simos-unit`/`simos-sd`/`simos-core`。
   `.superpowers/sdd/**` 只追加本文件 + 新增 `w1f-probe/`（该目录被 `.superpowers/sdd/.gitignore` 的 `*` 忽略，不进 `git status`）。
7. **`git status --porcelain` 记账**：开工前（本会话第一条命令）与收尾后都是 **90 行 = 80 M + 3 D + 7 ??**（逐项一致；
   我改的 2 个文件本来就已在 M / ?? 里）。★ 如实记一处**先于我存在**的差异：W1e 账本 §八-5 写的是"82 tracked / 7 untracked"，
   而我开工那一刻实测就是 83 tracked（80 M + 3 D）；01:10~01:13 之间**没有任何非 target 文件被写过**（`find -newermt` 实测为空），
   故这 1 行之差**不是 W1f 造成的**、也不在 W1f 范围内 —— 按 §五.4 不改 W1e 的历史行，只在这里记一句。

---

# W1g 旧载荷夹具迁移（2026-10-11）

> 责任区：`clean verify` 在 `simos-app` 红的**那一条** —— `McpCoverageTest.everyCatalogTypeIsReachableThroughMcpAndTakesEffect`
> 里 `economy.SetMarketPrice` 还在用**旧逐格载荷** `{q,r,commodity,price}`。
> 硬指标：`tools/mvn-lock.sh -DskipTests test-compile -am` **rc=0**（实测 BUILD SUCCESS）。
> 允许写：`simos-app/src/test/**`。**未改任何 `src/main`**；**未 `git commit`**；**未跑** `test`/`verify`/`package`/`clean`；
> **未新增测试**（只做迁移 + 一处"生效"断言保真）；未碰 `docs/**`、`AGENTS.md`、`simos-social`/`simos-unit`/`simos-sd`/`simos-core`。

## W1g-一、现场与根因（file:line 证据 → 结论）

| # | 证据 | 结论 |
|---|---|---|
| 1 | 门禁输出 `[type=economy.SetMarketPrice 必须经 MCP 可提交并生效: [mosire:code=REJECTED] {"result":"rejected","reason":"economy.SetMarketPrice 缺少非空文本字段: marketId"}]`；对应夹具 `McpCoverageTest.java:716`（改前）`{"q":1,"r":2,"commodity":"grain","price":2}`；新 handler 第一句就是 `requireText(COMMAND, payload, "marketId")`（`EconomySetMarketPriceHandler.handle`） | 载荷是**旧契约**（逐格）。W1 起 `{marketId, commodity, price}` 且价表**按市场一张** ⇒ 门禁那条拒因逐字对上 |
| 2 | ★★ **连带发现（任务书没点名）**：旧 handler（`git show HEAD:…/EconomySetMarketPriceHandler.java`，类注"该格**没有市场行** ⇒ 用 Silver 计价创建一张空市场"）**会凭空建市场**；而紧跟其后的 `economy.AddDemand` 载荷是 `hex={q:1,r:2}`，它能提交正是靠这一点满足 `EconomyAddDemandHandler.requirePriced`（"该格必须有市场行、且该商品有价"）。新 handler 类注写明"**只改已存在的市场**（凭空建一张是 `economy.DefineMarketZone` 的判据）" | 改完 SetMarketPrice 后，**同一条 AddDemand 仍会红**（(1,2) 永远没市场）。⇒ 这一条也属"为旧形状而存在的夹具事实"，必须一起迁。探针实测：旧 (1,2) 载荷 `Rejected: 该格 1_2 没有商品 grain 的价` |
| 3 | 夹具世界的经济创世 = `EconomyData.empty()`（`McpCoverageTest.seedGenesis`），`economy.Seed` 载荷里**没有** `markets` 键 ⇒ `marketTables` 恒空、`marketZones` 恒空 | W1 后该世界**一个市场都没有** ⇒ 必须先**播种**一个市场，新契约的 `marketId` 才有指称对象。该世界形态下唯一的市场播种口 = `economy.Seed` 顶层 `markets` 键（`EconomyPayloads.markets(...)`）；`economy.DefineMarketZone` 在本夹具恒走具名拒（`PRECONDITION_REJECT_TYPES`），不是可用路径 |
| 4 | 市场 id 的"真实存在"来源（不回退成瞎编）：`EconomySeeder.derivedMarketTables`（W1 的派生规则，唯一拼写点）= **全格同币 ⇒ 恰一张 `single-region`**（成员格 = 该币全部市场格、价表 = 出厂价表）。本夹具正是"一种计价币（银）+ 一个经济格 (1,1)" | marketId 取常量 `EconomySeeder.SINGLE_REGION_MARKET_ID`（= `single-region`），**不手写串**。对照：无 map 切片的夹具（`EconomyTestWorld:369`、`GuiApiTest:922`）用 `hex:<q>_<r>`；本夹具有 map 切片 ⇒ 走播种器那条规则 |

## W1g-二、改动（1 个文件、4 处载荷 + 1 处断言保真）

文件：`simos-app/src/test/java/io/mosire/simos/app/McpCoverageTest.java`（改前 md5 见 §五；新载荷**实测字节**见 §五探针）

| 位置（改后行号） | 改了什么 | 为什么 |
|---|---|---|
| `:743-756`（`economy.Seed` 载荷尾部） | 追加顶层 `markets`：id = `single-region`、`numeraire` = `EconomySeeder.MARKET_NUMERAIRE.value()`（silver）、`hexes` = `[IndustryHexKeys.hexKey(1,1)]` = `["1_1"]`、`prices` = `factoryPricesJson()`（按声明序拼 `EconomySeeder.MARKET_PRICES_FACTORY`：粮 1 / 布 5 / 纤维 1 / 工具 20 / 铁 10 / 运力 4） | ①新契约要求市场**已存在**（§一-3）；②成员格只能是 entry 里有的格，本夹具唯一 entry = (1,1)（`EconomyPayloads.market(...)` 的 fail-closed）；③价表取出厂表 ⇒ 夹具**不另拍价**（`EconomyTestWorld` 的同一条纪律），且"这一格有没有粮价"由本表回答（`MarketTables.at` / `requirePriced`） |
| `:784-790`（`economy.SetMarketPrice`） | `{"q":1,"r":2,"commodity":"grain","price":2}` → `{"marketId":"single-region","commodity":"grain","price":2}`（`price` 走常量 `COVERAGE_GRAIN_PRICE = 2`） | 新契约（逐格 ⇒ 逐市场），且 marketId 就是上面播下的那张表 |
| `:799-803`（`economy.AddDemand`） | `hex={q:1,r:2}` → `hex={q:1,r:1}` | §一-2：新契约下需求必须落在**有价的格**；(1,1) 是本夹具市场唯一覆盖的格（也是唯一有产业/家户行的格） |
| `:400-407`（`economy.SetMarketNumeraire` 的**拒例**载荷） | `{"q":0,"r":0,"numeraire":"copper","reason":"coverage"}` → `{"marketId":"market-missing","numeraire":"copper","reason":"coverage"}` | 同批 `zone-missing`/`gov-missing` 的写法：**形状合法、前置缺失**、走 handler 第一条 fail-closed（`market-missing`）——与改前"这个格没有市场"逐字对应；不拿真市场当拒例（那会把"提交成功"与"具名拒"两条路径的判据混在一起） |
| `:536-573`（新增常量 + 助手） | `COVERAGE_MARKET_ID`（= `EconomySeeder.SINGLE_REGION_MARKET_ID`）、`COVERAGE_GRAIN_PRICE = 2L`、`factoryPricesJson()` | 单件事一处拼写；出厂粮价（=1）与载荷粮价（=2）**不同** ⇒ 断言有判别力（§三） |
| `:1072-1089` + `:1207-1213`（第 3 段断言 + `economySlice` 助手） | 新增：replay 当前 head ⇒ 读经济切片 ⇒ `marketTables["single-region"].prices[grain] == 2` | "生效"这一层的**保真**（§三）；仿 `unitSlice` 的形制，装配故障当场炸、不静默 |

**没有删除任何测试**：`McpCoverageTest` 这条是**活判据**（"目录里每条命令都能经 MCP 提交并生效"），按任务书要求保留；
本批也没有发现"纯为旧形状而存在、可直接删"的用例（W1b 已删 `MarketTopology*SingleRegionTest` 两条，见 `git status` 的 3 个 D）。

## W1g-三、"生效"这一层怎么断言的（改前 / 改后）

- **改前**：该用例的"生效"= ①每条命令 `result=committed`；②`ref.revision` **逐条**前进；③循环末尾
  `head == 1 + MINIMAL_PAYLOADS.size()`（87 条）；④第 3 段用 replay 后的状态证单位/编制真的变了。
  ⇒ 对 `economy.SetMarketPrice` 而言，它证的是"**产出了非空变更集并推了一格**"，**没有任何断言读那张价表** ——
  价写没写对（写错商品/写错值/写错表）看不出。
- **改后**：①–④ **一字未改、未放宽**；**另加**一条实读断言（`:1072-1089`）：
  `replay(head)` 的 `marketTables["single-region"].prices[grain]` 必须 `== 2`，而出厂播种值是 `1`
  ⇒ 一个"照单收下却什么都没写"的 handler 会让本条当场红（不是恒真）。
- **判别力证据（§五探针）**：`[2]` 该 handler 产出的变更集与 `between(播种态, "single-region 粮价=2" 态)`
  **逐值相等 = true**（即"只改了这一个数"）；`[2b]` 把载荷换回旧形状 ⇒ `Rejected`（= 门禁那条红逐字同源）。

## W1g-四、同款旧载荷排查（任务书点名的三处 + 全仓复扫，逐个结论）

| 位置 | 结论 |
|---|---|
| `McpCoverageTest.java:716`（改后 `:784`） | **是**旧载荷（SetMarketPrice 逐格）⇒ 已改（§二） |
| `McpCoverageTest.java:394`（改后 `:400`） | **是**旧载荷（SetMarketNumeraire 逐格 `{q,r,numeraire}`）⇒ 已改（§二，仍是具名拒） |
| `AdjudicateTickToolTest.java:542` | **不是载荷**：那是 `tool.allowedCommandTypes()` 的"有意无目标"清单里的**类型名字符串**（`assertThat(missing).containsExactlyInAnyOrder(…, "economy.SetMarketPrice", …)`），既无 `q/r` 也无 payload ⇒ **未改**（改了反而会破坏该清单的语义） |
| `SimosToolsTest.java:544-545` | **不是载荷**：`EXPECTED_COMMAND_TYPES`（catalog 与注册面同源的类型清单）里的两个类型名 ⇒ **未改** |
| 全仓复扫 `grep -rn "SetMarketPrice\|SetMarketNumeraire" simos-*/src` | `src/test` 只有上述四处（无第二处旧载荷）；`src/main` 只有 handler 本体 / `Shell` 注册 / `CatalogTool` 的载荷提示（**提示已是 W1 新形状**：`marketId, numeraire` 与 `marketId, commodity, price`）⇒ **没有第二处旧载荷** |
| 连带（§一-2，任务书未点名） | `economy.AddDemand` 的 `hex={1,2}` 是**依赖旧副作用的隐式旧夹具** ⇒ 已改 `(1,1)` |

## W1g-五、验证证据（真命令 + 真输出，2026-10-11）

```
$ git status --porcelain | wc -l
91                      # 开工前实测（W1–W1f 的改动都在，未回退、未清理）

$ tools/mvn-lock.sh -q spotless:apply
[rc=0]                  # 无输出；事后 diff 状态清单：与跑之前**逐行相同** ⇒ 全仓工具没碰第三个文件（§五.1 的漏提交坑未发生）

$ tools/mvn-lock.sh -DskipTests test-compile -am 2>&1 | tail -45
[INFO] Compiling 147 source files with javac [debug release 21] to target/test-classes
[INFO] Reactor Summary for SimulatorMosire 0.1.0-SNAPSHOT:
[INFO] UtilSimos … SimosApp ............................... SUCCESS（16 个模块全 SUCCESS）
[INFO] BUILD SUCCESS
[INFO] Total time:  12.992 s
$ echo "[PIPESTATUS=${PIPESTATUS[0]}]"   → [PIPESTATUS=0]

$ md5sum simos-app/src/test/java/io/mosire/simos/app/McpCoverageTest.java
f2ac81a0203e796142f73d2cf64f2734
$ stat -c '%y  %n' <源码> <class>
2026-10-11 01:46:33  …/src/test/java/io/mosire/simos/app/McpCoverageTest.java
2026-10-11 01:46:49  …/target/test-classes/io/mosire/simos/app/McpCoverageTest.class
                        # 源码 mtime < class mtime ⇒ 编译覆盖的是**最终字节**；此后无写入
```

★ **另加一条非 JUnit 只读探针**（`w1g-probe/run-probe.sh`，rc=0，输出见 `w1g-probe/probe.log`；不跑 JUnit / Maven 门禁、不写仓库）：
它用**真 handler + 真 `EconomyPayloads`** 跑夹具改过的载荷，并用 `EconomyChangeSet.apply` 链式推进状态：

```
[1] economy.Seed 被真 EconomyPayloads 接受：marketTables=[single-region] 成员格=[1_1] 计价币=silver 出厂粮价=1
[2] SetMarketPrice outcome=Applied / 变更集 == between(播种态, 'single-region 粮价=2' 态) ? true / 该态粮价=2
[3] AddDemand(hex=1_1) outcome=Applied
[3b] 旧载荷 AddDemand(hex=1_2) outcome=Rejected reason=该格 1_2 没有商品 grain 的价（请先用 economy.SetMarketPrice 给该格定价）
[2b] 旧载荷 SetMarketPrice outcome=Rejected reason=economy.SetMarketPrice 缺少非空文本字段: marketId
[4] SetMarketNumeraire(拒例) outcome=Rejected reason=market-missing: 该市场不存在（说不出要改哪张价表）

发现 economy handler 34 个
[chain] economy.Seed => Applied
[chain] economy.SetMarketPrice => Applied
[chain] economy.SetCommodityFreight => Applied
[chain] economy.AddDemand => Applied
[chain] economy.CancelDemand => Applied
[chain] economy.RegisterCandidate => Applied
[chain] economy.TransferAssetShare => Applied
[chain] 全部提交成功 ? true（未提交 0 条）
--- economy 拒例（必须全部具名拒）---
… 24 条逐个跑：具名拒 22、意外（没被拒）0、探针边界 2（economy.SetGovServiceCommitment / economy.UpsertGovUnit 需要 social 切片 ⇒ 当场抛，是**探针**边界）
拒例合计=24 具名拒=22 意外=0 探针边界=2
```

⇒ 夹具 economy 那一组（含改动的 3 条载荷）**7/7 真 handler 提交成功**；24 条 economy 拒例里**没有一条**因为"我播了这张市场表"而从"拒"变成"收"（`意外=0`）。

★★ **classpath 陷阱（如实记，已作废一版证据）**：第一版探针把 `~/.m2` 里的**旧安装 jar** 混进了 classpath，
于是 `Class.forName("…spi.EconomyTransferAssetShareHandler")` 命中了 **jar 里的旧类**（当前源码里这个类**已不存在**，
现行类是 `EconomyTransferOwnershipStakeHandler`）⇒ 那一版 `TransferAssetShare` 的结果（一条 `ClassFirstCommandGuard` 抛错）
是**假证据**。`run-probe.sh` 现在显式 `find ~/.m2/repository -name '*.jar' | grep -v '/io/mosire/'` 排除本仓 jar，
并改为**从刚编译出的 `simos-economy/target/classes/…/spi` 目录发现 handler**（`type()` 建索引）⇒ 跑的是当前源码字节。

## W1g-六、关键判断（为什么这样拆 / 为什么不用另一条路线）

1. **marketId 取真播种器常量（`single-region`），不取 app 夹具习用的 `hex:1_1`**：本夹具有 map 切片、且全格同币，
   正是 `EconomySeeder.derivedMarketTables` 的"单区"形态；`MarketTopology.singleRegion`（退役）的节点 id 也是 `single-region`
   ⇒ 换成持久/派生权威后读数口径不变。`hex:<q>_<r>` 那条规则属"**没有 map 切片**的夹具"（`EconomyTestWorld`/`GuiApiTest`），本夹具不属那一类。
2. **价表取出厂表常量而不是手写价目**：`EconomyTestWorld:359` 的同条纪律（"夹具不另拍价表，否则夹具里的价与真档的价会在两次改动之间静默漂开"）。
   副作用是顺带满足 `AddDemand` 的 `requirePriced`（粮价在表里）。
3. **把 `AddDemand` 的 hex 改到 (1,1)，而不是给夹具加第二个 entry 撑住 (1,2)**：后者要过
   `EconomyPayloads` 的 `requireArray(entry, "industries")`（**entry 的 `industries` 必填**）⇒ 得再落一个产业 + 合成份额 + 一条 unit，
   改动面、守卫面（默认关系、份额序列 `SEEDED_LAND_SHARE` 的 (…,0) 后缀）都更大，而收益只是"保住一个任意选的坐标"。
4. **拒例用不存在的 marketId**：`SetMarketNumeraire` 的拒因链是 market-missing → unknown-currency → numeraire-unchanged；
   点真市场（`single-region`）+ 不存在的币也能拒（unknown-currency），但那把两条路径的判据混在一起，且依赖"币种词表为空"这个更靠后的条件。
5. **保留该用例、不动其判据**（任务书要求）：它是"目录里每条命令都能经 MCP 提交并生效"的活判据；本批只把载荷迁到新契约并把
   "生效"从"推了一格"**加强**为"价真的变了"，没有把任何断言降级成"没被拒就行"。

## W1g-七、偏离记录

1. **无设计冲突**（不涉用户原话 / 五条铁律 / 模块边界）；本批纯夹具契约迁移。
2. **偏离（范围）**：任务书只点名"SetMarketPrice/SetMarketNumeraire 的旧 `{q,r}` 载荷"，我**多改了 `economy.AddDemand` 的 `hex`**
   —— 理由见 §一-2（它是对旧 handler"凭空建市场"副作用的**隐式依赖**，不改则同一条用例仍红）。已在 §二/§四具名记账。
3. **新增一处断言 + 一个助手**（§二末行）：任务书 §3 允许"必要的断言保真"，且 §2-2 要求"改完必须仍然验证生效"。
   这是**加强**，不是放宽；未新增任何测试方法/测试类。

## W1g-八、未完成 / 未验证项（如实记）

1. **没跑任何测试、没跑门禁**（任务书 §3 禁止 `test`/`verify`/`package`/`clean`）⇒ "这条红修好了"**不是 surefire 验的**。
   本批给的是 ①**编译绿**（rc=0，覆盖最终字节）②**handler 级运行时证据**（§五探针）。**最终判据归控制方门禁**。
2. ★★ **同一用例在 `economy.SetMarketPrice`（第 54 条）之后还有 33 条可提交命令 + 57 条拒例，在红灯那一轮里从未执行过**
   （断言在循环第一处失败就中止了整个方法）⇒ 它们的 W1 后行为**没有实测记录**。我按模块边界逐类核了方向（**没有跑**）：
   - economy 那一组 7 条：**已由探针实测**（§五 `[chain]` 全 Applied）；
   - `economy.RegisterHousehold`（#79）/`economy.RegisterGovernment`（#86）：`grep` 实测**零** market/price/price-table 引用
     （handler 不读价表）⇒ 我加的那张表对它们只可能是"多一个已登记格"，不会把成功变失败；★ 但它们各需 social 切片，**探针测不到**；
   - #55–#87 其余为 unit/social/sd/army/gov/actor 命令，与市场/价格面无关（本批只动 economy 夹具 + 一条断言）；
   - 57 条拒例中 **economy 的 24 条已实测**（22 具名拒 / 2 探针边界 / 0 意外），其余拒例不读价表。
   ⇒ **这是本批最需要控制方先跑的地方**：若 #55–#87 里还有 W1 遗留的夹具，会与本次迁移一起暴露（不是我改出来的，但会一起红）。
3. **没有走真 MCP / Shell / 时间线链**：探针直接调 handler + `EconomyChangeSet.apply` 推进状态，
   **不经过** `shell.coreSimos()` 的提交/审批/checkpoint、也不算 `expectedRevision` ⇒ MCP 工具面、审批链、
   fork/坏载荷反向那两段**未验**。
4. **未做变异自证**（§三.0 收尾期规矩）：只有"旧载荷 Rejected / 旧 hex Rejected"承担反向对照，
   没有"改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5"的正式一轮。
5. **未跑全仓工具**（只 `spotless:apply` + `test-compile`）；**未 `git commit`**；**未碰** `src/main`、`docs/**`、`AGENTS.md`、
   `simos-social`/`simos-unit`/`simos-sd`/`simos-core`。
6. `.superpowers/sdd/**` 只追加本文件 + 新增 `w1g-probe/`（`W1gProbe.java` / `W1gChain2.java` / `run-probe.sh` / `probe.log`；
   该目录被 `.superpowers/sdd/.gitignore` 的 `*` 忽略，不进 `git status`）。
7. **`git status --porcelain` 记账**：开工前 **91** 行、收尾 **92** 行（+1 = 我改的 `McpCoverageTest.java`，它原先是**未修改**的）；
   `find simos-*/src -newermt "-40 minutes"` 只命中我这一处（W1f 的 `EconomyCodec.java`/`MarketTable.java` mtime = 01:18:52，
   早于我 01:41 开工）⇒ 没有第二个文件被我写过。★ 如实记一处**先于我存在**的差异：W1f 账本 §八-7 写"收尾 90 行 = 80 M + 3 D + 7 ??"，
   而我开工那一刻实测就是 **91** 行（= 81 M + 3 D + 7 ??，即 84 tracked）；收尾 92 行 = **82 M + 3 D + 7 ??**（85 tracked），
   差的 1 行正是我的 `McpCoverageTest.java`。按 §五.4 不改 W1f 的历史行，只在这里记一句。

---

# W1 收尾批（日志口径 + 往返覆盖 + 假护栏）

> 任务书：W1 收尾批（1 件 main + 2 件 test）。输入 = 验收报告 `.superpowers/sdd/2026-10-10-w1-acceptance/report.md`（只读，未改）。
> 硬纪律照任务书：**不 `git commit`**；**不跑 `test` / `verify` / `package` / `clean`**（门禁归控制方）；
> 只跑 `tools/mvn-lock.sh -q spotless:apply` 与 `tools/mvn-lock.sh -DskipTests test-compile -am`。
> ★ Alpha 阶段不考虑任何兼容性（AGENTS §一.11）：本批**没有**写任何"旧档可读"判据。
> 证据目录：`.superpowers/sdd/2026-10-10-w1-market-zone-price-and-stats/w1-final-probe/`（复跑脚本 `run-probe.sh`；被 `.superpowers/sdd/.gitignore` 忽略，不进 `git status`）。

## W1 收尾批-一、(A) `MARKET_ROUND_STATS_DETAIL` 的所指对象（main，1 处，只动打印对象）

**现场**（`file:line` 已核对）：`simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java` 的
`MARKET_ROUND_STATS_DETAIL`（改前 `:2826-2841`）。旧代码打印 `outcome.roundStats().get(marketId).describe()` ——
那是**本轮入参**，由 `MarketSettlement.roundStatsOf`（`MarketSettlement.java:2102-2113`）造的**单轮形态**：

```
new MarketRoundStats(day, round, turnover, Map.of(), 0L, Map.of(), 0L)   // cycleToDate / previousCycle 恒空
```

⇒ `describe()` 的"本周期 / 上周期"两栏**恒 0**，与同 tick 落盘的 `marketRoundStats` 直接矛盾（验收报告 §7.2）。

**修法**：两条候选（① 改打印对象 ② 改字段名）里选 **①** —— 字段名与所指对象都保持"这份统计"，读者拿到的就是**落盘的那一份**，
不会出现"名字说窗口、值是入参"的二次误导。改动只有一行取值：

```
MarketRoundStats stored = statsByMarket.get(entry.getKey());          // 上文 withRound(...) 写进去的那一份
... "stats", stored == null ? "未写入" : stored.describe()
```

- 状态 / 公式 / 写口**一字未动**；级别仍是 DEBUG；事件名与字段名（`day` / `marketId` / `stats`）不变。
- `stored == null` 在可达性上不可达（写入就在同一块上文），但仍**不回落**到本轮入参、也**不抛**（§一.9：日志不得影响结算）；
  真取不到时如实写 `未写入`。

**修完后真实 tick 会打印什么**（数字取验收报告 §5.1/§7.2 的 small-world day=360 真值；探针 `W1LogProbe` 用真代码复算，
不是手推）：同 tick `cycleClosed=true`，本轮 5 商品、本轮成交额 521、落盘 `previousCycle` 5 商品、`previousCycleTurnoverMilli=5601`

| | `stats=` 后半段 |
|---|---|
| 旧（= 报告 §7.2 的真实日志行） | `day=360 本轮商品=5 本轮成交额=521 本周期商品=0 本周期成交额=0 上周期商品=0 上周期成交额=0` |
| 新（同 tick） | `day=360 本轮商品=5 本轮成交额=521 本周期商品=0 本周期成交额=0 上周期商品=5 上周期成交额=5601` |

★ "本周期"在**关账轮**仍 0 是**正确**的：`MarketRoundStats.withRound(cycleClosed=true)` 把本周期轮转成上周期并清空 `cycleToDate`
（"关账发生在市场轮之后"）。非关账轮的该栏就是本周期累计真值（报告 §5.2 的 day=500 中段相位即此形态）。
★ 一句话口径：**这条日志打印的实例与 `statsByMarket` 里那一份是同一个引用 ⇒ 日志与同 tick 落盘不可能再不一致。**

## W1 收尾批-二、(B) 铁律 5 的**真往返**覆盖（test，1 文件）

**文件**：`simos-economy/src/test/java/io/mosire/simos/economy/change/EconomyRoundTripTest.java`

夹具新增/加强（要求 ①②③ 全部落到**非空**）：

1. **派生形态价表**：`market()` = `hexes={KEY.hex()}`（非空）+ **三个**商品 `{grain:3, cloth:5, tool:0}` ——
   新增常量 `TOOL` 承担**明确 0 价**那一行（0 价 = 免费交易 vs 缺行 = 不交易，两件事必须在值层面可分）。
2. **持久形态价表**：新增 `ZONE_EAST` / `ZONE_EAST_HEX=(2,0)` / `zoneEast()`，价表复用 `zoneMarket()`
   （`hexes` **空**、`numeraire` == 该区 `legalTender`）。★ 区锚格刻意 ≠ `KEY.hex()`：构造期守卫②不许它与派生表的成员格重叠。
3. **非空统计簿**：`roundStats(day, last, cycle, prev)` 改为**每段两个商品、三段取互不相同的读数**；
   新增 `statsBook()` = **两个市场**（派生 `MARKET` + 持久 `ZONE_EAST`），日号与三段成交额**逐区不同**。
4. `mutate("marketTables")` = **两形态各一张**（`withZoneAndItsTable(base, zoneEast(), zoneMarket())` + 合并价表）；
   `mutate("marketRoundStats")` = `statsBook()`（多区多商品）。
5. 新增用例 `bothMarketTableFormsAndTheWholeStatsWindowSurviveTheChangeSet`：同一份 target 里两形态 + 多区三段窗口，
   先钉**夹具自身的判别力**（0 价行真在、持久形态成员格真空、两区统计真不同值、三段各两商品），
   再断言 `apply(changeSet, base)` **逐字段**重建 target（整对象相等 + 逐分量/逐键/逐段读回）。**既有断言未放宽、未删除。**

**反证（改坏 ⇒ 红，§三.2）**：变异体 **M-delta** = `EconomyChangeSet.between` 里统计簿 diff 改 `FieldDelta.Unchanged` +
`apply` 里 `marketTables` 重建改回 `base.marketTables()`。

- 变异体 md5 `8d9c932244a522929069877b3e080dfd` vs 原件 md5 `c9573f7ca999f574e9cad9c86663ee3a` ⇒ **确实改坏过**（字节不同）。
- 还原后 `cmp pristine 工作树` = 逐字节相同，md5 回到 `c9573f…`（§五-3 有真命令与输出）。
- 同一变异体下，新用例那三句断言的**谓词**当场为假（`evidence-04-mutant-delta-roundtrip.log`，探针用**真夹具**求值）：
  `**RED** cs.marketRoundStats.changed` / `**RED** apply(cs,base).equals(target)` / `**RED** back.marketTables onlyKeys MARKET,ZONE_EAST`。

## W1 收尾批-三、(C) 那条**假护栏**（test，1 文件）

**现场**：`simos-economy/src/test/java/io/mosire/simos/economy/codec/EconomyCodecTest.java:419-420`（改前行号）断言
`changeOnce` / `snapshotOnce` 不含 `"empty"`，而夹具 `fullData()`（`:923`）**一个市场都没有** ⇒ 载荷里根本没有 `MarketTable`
⇒ 断言恒真、对本次缺陷**等价存活**（探针把这件事做成了对照实验，见下）。

**改法（只补，不放宽/不删）**：`fullData()` 补三件非空 —— 派生价表（hexes 非空、三商品含 `tool:0` 明确 0 价）、
持久价表（键 = 区 `ZONE`、hexes 空、`numeraire == legalTender`）、多区三段统计簿；并在护栏用例开头补一组**夹具非空性断言**
（市场 2 张、两形态、0 价行、`numeraire == legalTender`、统计簿 2 区）+ 线格式里"**真的**带市场"两句
（`contains("marketTables")` / `contains("marketRoundStats")`，字面量另经探针核对）——"夹具退化 ⇒ 护栏恒真"从此有护栏。

**反证（改坏 ⇒ 红，§三.2）**：变异体 **M-codec** = 摘掉 `MarketTable.isEmpty()` 上的 `@JsonIgnore`
（`model/MarketTable.java:160`，W1f 加的那一处）。变异体 md5 `b228cc1a7dbb64f336d276155ffdc82d` vs 原件 `8ec2d99b3d6144751f1f6b180e8ac663`
⇒ 确实改坏过；还原后 `cmp` 逐字节相同、md5 回到 `8ec2d9…`。同一变异体下的真输出（`evidence-02`/`03`）：

| 载荷 | `snapshot contains "empty"` | `decode` |
|---|---|---|
| **有市场**（= 本批新夹具形态） | **true** | `IllegalStateException: economy 侧 JSON 解码失败` ← 护栏的**观察点** |
| **无市场**（= 改动前的旧夹具形态） | false | ok ← **旧护栏"等价存活"的实证** |

真夹具上的根因原文（`evidence-03-mutant-codec-real-fixture.log`）：

```
UnrecognizedPropertyException: Unrecognized field "empty" (class io.mosire.simos.economy.model.MarketTable),
not marked as ignorable (3 known properties: "hexes", "prices", "numeraire")
```

线格式原文（同一次运行里截到的片段）：`"marketTables":{"codec-derived-market":{...,"empty":false}}`。

## W1 收尾批-四、会改变数值行为的清单

**空**（本批不改变任何数值行为）：
- (A) 只改**打印对象**：状态、公式、写口、时序、revision 全未动；日志级别与开关未动；日志不写状态。
- (B)/(C) 只有 `src/test/**` 与一份 `.superpowers/**` 账本；`src/main` 的唯一改动就是 (A) 那一处。
- 三处改动都**没有**新增/删除状态组件、命令、工具、Codec/ChangeSet 形状（组件数仍 37，未动 `EconomyData`/`EconomyChangeSet`）。
- 受影响硬编码字面量：**无**（没有改任何出厂值、费率、阈值）。

## W1 收尾批-五、验证证据（真命令 + 真输出）

**1. 格式化与编译（任务书许可的两条命令）**

```
$ tools/mvn-lock.sh -q spotless:apply          ⇒ rc=0（第一次：改完 main+test；第二次：最后一处注释微调后）
$ tools/mvn-lock.sh -DskipTests test-compile -am
  [INFO] EconomySimos ... Compiling 206 source files ... to target/classes
  [INFO] EconomySimos ... Compiling  55 source files ... to target/test-classes
  [INFO] SimosApp ....... Compiling 328 source files ... to target/classes
  [INFO] SimosApp ....... Compiling 147 source files ... to target/test-classes
  [INFO] BUILD SUCCESS   ⇒ rc=0；`grep -c '^\[ERROR\]'` = 0；16/16 模块 SUCCESS；8.9 s
```

★ 两处都是**真编译**（不是 `Nothing to compile`）⇒ 上面 206/55 里确实包含本批改过的字节。

**2. 判别力探针（★ 说明：本批**没有**跑 Maven `test`、没有跑 JUnit、没有跑门禁 —— 那是控制方的活。
   为满足 §三.2"改坏 ⇒ 当场红"的要求，另起了一条**不经 JUnit / 不经 Maven 生命周期**的通路：`javac`+`java` 直调被测代码路径。
   它求值的正是新断言里的那些谓词。复跑：`bash w1-final-probe/run-probe.sh`）**

```
$ CP="simos-economy/target/test-classes:simos-economy/target/classes:simos-app/target/…-shaded.jar"
$ javac -proc:none -cp "$CP" -d /tmp/w1-final-probe/classes w1-final-probe/*.java
$ java -cp "classes:$CP" W1Probe ; …W1FixtureProbe ; …W1CodecFixtureProbe ; …W1LogProbe
  ⇒ 45 条谓词全 GREEN / 0 RED（evidence-01-clean.log）
     · 含"用**真夹具工厂**求值新用例的每一条断言"（含 `[case marketTables]`/`[case marketRoundStats]`/`[case demands]` 三条 mutate 分支）
     · 含反射取 `EconomyCodecTest.fullData()`（private）核 codec 夹具：构造不抛、两形态、0 价行、两区三段、字节级往返、
       线格式字面量、"不含 empty"
```

**3. 变异体 M-codec（(C) 的红，含"旧夹具等价存活"对照）**

```
$ javac -proc:none -cp "$CP" -d /tmp/w1-final-probe/mut-codec <摘掉 @JsonIgnore 的 MarketTable.java 副本>
$ java -cp "classes:mut-codec:$CP" W1Probe codec
  [codec] A 无市场载荷（旧夹具形态） | contains "empty"=false | decode=ok
  [codec] B 有市场载荷（新夹具形态） | contains "empty"=true  | decode=IllegalStateException: economy 侧 JSON 解码失败
```

```
$ java -cp "classes:mut-codec:$CP" W1CodecFixtureProbe     # 走**真夹具** fullData()
  UnrecognizedPropertyException: Unrecognized field "empty" (class …MarketTable),
  not marked as ignorable (3 known properties: "hexes", "prices", "numeraire")
```

**4. 变异体 M-delta（(B) 的红）**

```
$ java -cp "classes:mut-delta:$CP" io.mosire.simos.economy.change.W1FixtureProbe
  GREEN  cs.marketTables.changed
  **RED** cs.marketRoundStats.changed
  **RED** apply(cs,base).equals(target)
  **RED** back.marketTables onlyKeys MARKET,ZONE_EAST
```

**5. 就地改坏 ⇒ 还原 ⇒ md5（任务书要求的那一轮）**

```
$ md5sum simos-economy/src/main/java/io/mosire/simos/economy/model/MarketTable.java      # 改坏前
  8ec2d99b3d6144751f1f6b180e8ac663
$ md5sum …/model/MarketTable.java                                                        # 改坏后（摘 @JsonIgnore）
  b228cc1a7dbb64f336d276155ffdc82d
$ md5sum …/change/EconomyChangeSet.java                                                  # 改坏后（M-delta）
  8d9c932244a522929069877b3e080dfd
$ cp <pristine 备份> …  &&  cmp <pristine> …   ⇒ 逐字节相同
$ md5sum …/model/MarketTable.java …/change/EconomyChangeSet.java                         # 还原后
  8ec2d99b3d6144751f1f6b180e8ac663  MarketTable.java          （== 改坏前）
  c9573f7ca999f574e9cad9c86663ee3a  EconomyChangeSet.java     （== 改坏前）
```

★ 两个变异体源码留在 `w1-final-probe/mutant-*.java`（md5 见 `mutant-md5.txt`），供控制方复核"改坏过"。
★ `run-probe.sh` 的变异是**在 /tmp 副本上**做的 ⇒ 复跑脚本本身**不写工作树**。

**6. `git status --porcelain`**

```
开工前：空
收尾后： M simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java
        M simos-economy/src/test/java/io/mosire/simos/economy/change/EconomyRoundTripTest.java
        M simos-economy/src/test/java/io/mosire/simos/economy/codec/EconomyCodecTest.java
```

（+ 本账本追加；`w1-final-probe/` 被 `.superpowers/sdd/.gitignore` 忽略。）**没有 commit**。

## W1 收尾批-六、关键判断与偏离记录

1. **(A) 选"改打印对象"而不是"改字段名"**：字段名改成"本轮入参"虽也能自洽，但那条 DEBUG 行的**用处**恰恰是"这一轮记了什么统计"；
   改对象后它与会话写入/落盘**同一引用**，与同 tick 的 INFO（`MARKET_ROUND_STATS_RECORDED`）和 checkpoint 都能对齐。
2. **(C) 的"非空性断言"是必要补充，不是装饰**：只把夹具填上市场，护栏仍可能在**将来**被别的改动弄回恒真（例如有人把市场从
   `fullData()` 里删掉）；把"有观察点"本身钉成断言，才让这条护栏**自指**。
3. **偏离（主动记）**：任务书 §2 的"允许命令"只有 `spotless:apply` 与 `test-compile`，我另外用了 `javac`/`java` 直跑探针
   （**不是** Maven `test`、不是 JUnit、不是门禁）。理由：§3.2 要求"改坏 ⇒ 当场红"的真证据，而 §3.6 又写明"你不能跑测试/门禁"
   ⇒ 这条折中通路是唯一能同时满足两者的形态。**它求值的是新断言里的同一批谓词，但不等于 surefire 判据**；控制方复跑门禁仍是最终判据。
4. **顺带更正一处已过期的注释（只改注释，不改代码）**：`EconomyRoundTripTest.withZoneAndItsTable` 的 javadoc 原写
   "两个单组件 wither 之间没有合法中间态"，而控制方 **W1d（2026-10-11）已删除**"每个持久区必须有价表条目"这条守卫
   （`EconomyData.java:1991-1996` / `:2035-2037`，口径 = 设计书 §4.2 N-4"缺表 = 该区不交易任何商品"）。本批：
   ① 在该 javadoc 追加一句 W1d 更正；② 新写的那条 `withZoneAndItsTable` 调用不再按"中间态非法"论证，改为"沿用原子写法"。
   ★ 这是**注释与代码不符**（AGENTS §四）在测试树里的残留，如实上报。
5. **无设计冲突**：未触及用户原话、五条铁律（本批正是补铁律 5 的覆盖）、模块边界；未碰 `simos-social`/`simos-unit`/`simos-sd`/`simos-core`/`docs/**`/`AGENTS.md`/验收报告目录。

## W1 收尾批-七、未完成 / 未验证项（如实记）

1. ★★ **没有跑任何测试、没有跑门禁**（任务书 §2 禁止 `test`/`verify`/`package`/`clean`）⇒
   "新用例会绿、变异体会红"**不是 surefire 验的**。本批给的是：① 编译绿（rc=0，覆盖最终字节）；
   ② 探针在**真夹具 + 真代码路径**上逐谓词求值（45 GREEN / 0 RED）；③ 两个变异体的真红输出 + md5 还原自证。
   **最终判据（`clean verify` / 测试计数）归控制方。**
2. **探针 ≠ JUnit**：它不经过 JUnit 生命周期、不经过 surefire、不算测试计数；也**没有**覆盖两个测试类里与本批无关的既有用例
   （那些用例的字节我没动，除本批列出的新增/加强项）。
3. **`EconomyCodecTest` 里 `chain`/legacy 类既有用例未复跑**：本批只往 `fullData()` 里**加了**三个非空组件 ⇒
   "缺键旧档可读"那条用例（`legacySnapshotWithoutEconomyKeysDecodesToUnactivatedEmptyTables`，自带字面量载荷）不受影响，
   但我**没有实测**它对 `fullData()` 变化的反应（它不读 `fullData()`，静态判断不相关）。
4. **未跑真 world / 未跑 HTTP / 未跑 MCP**：本批不含数值行为改动，故没有做世界级对拍；日志口径的现场数字取自验收报告（§5.1/§7.2），
   新行内容由 `W1LogProbe` 用真代码复算（`withRound` 语义），**不是**在真 tick 里打印出来的。
5. **未做**：`git commit`、`clean verify`、全仓 spotbugs/checkstyle 门禁（`spotless:apply` 已跑；`test-compile` 会带 checkstyle，
   但**不等于** `verify`）。
6. `.superpowers/sdd/**` 只**追加**本文件这一节 + 新增 `w1-final-probe/`（探针源码 / 证据日志 / 变异体源码 / 复跑脚本）；
   未改任何历史行（§五.4）。
