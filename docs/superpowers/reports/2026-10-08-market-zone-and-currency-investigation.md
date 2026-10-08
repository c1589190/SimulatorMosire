# 只读调查：市场区（market zone）与货币/兑换 现状与缺口（task-1）

> **性质**：只读调查。本文件是本代理唯一写入的产物；未改任何生产代码/测试，未跑 Maven，未 commit。
> **证据基准**：工作树 HEAD `5ae583e1`（`git log -1` 实测；仓库内提交日期为 2026-10-23）。★ 会话时间为 2026-10-08，
> 与仓库内日期标签不一致 —— **如实记**：本报告每一条都是回代码核出来的，与日期标签无关。
> **纪律**：AGENTS §四「台账/计划措辞一律回代码核」；本报告中凡是文档口径与代码不一致处，一律**以代码为准并标注**。
> **用户 2026-10-08 裁定（最高权威，逐字）**：
> ①「先来看经济吧，铸币我暂时不准备做，我准备完善一下市场区设定和货币兑换机制」
> ②「行政区、市场区不能搞混，两者理论上不但可以重叠、包含，甚至可以半包含、部分重合等，因为市场区代表的是使用同一个货币的市场，行政区代表的是一个 GOV 单位直接抽税、管理的地方」

---

## 0. 一句话结论

**「市场区＝同一货币的使用范围」这条裁定，代码里已经有一半承载体**（每格 `Market.numeraire`、`MarketNode/MarketRegion.numeraire` + `receiveWith`、以 `CurrencyId` 为键的余额与发行审计、以及 D-027「同币即同区」的单区选择）；
**但「货币兑换机制」在代码里完全不存在**（全仓 `ExchangeRate` **0 命中**，M1-A / D-023 明文规定「币种之间不许求和、不许折算」），
**且「一个区只用本币」这条不变量没有任何地方守**——不同 `numeraire` 的格可以落进同一个区（`MarketTopologyBook.addNode` 只挡非银锚点，`MarketTopology.of` 的成员归属**不看币种**），
成交时货款腿/运费腿一律用**买方的** `buy.currency`（`MarketSettlement:3562`、`:3581`）付给卖方与承运人，**没有任何币种相等校验**（唯一的币种相等校验在借贷路径 `:1707`）。
⇒ 现状可以「记下两种钱」，但**不能表达「两区不同货币、区内只用本币」**：它会静默地按 1:1 把一种钱付给另一种钱的世界。

---

## 1. 逐条回答 task-1 的 8 问

### Q1. MarketRegion / MarketNode / MarketTopology 的形状、派生、持久化、Codec/ChangeSet

| 类型 | 住处 | 形状（字段/键） | 证据 |
|---|---|---|---|
| `MarketNode` | `simos-economy-api`（契约层） | `(String nodeId, HexCoord anchor, int radiusHex, CurrencyId numeraire, InstrumentId receiveWith)`；5 个组件全部非空、`radiusHex ≥ 0` | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/market/MarketNode.java:26-45` |
| `MarketRegion` | `simos-economy-api` | `(MarketNode node, Set<HexCoord> members)`；`members` 保序不可变（`LinkedHashSet`）、不得为空、含 anchor；对外只读访问器 `anchor()/radiusHex()/numeraire()/receiveWith()` | `.../market/MarketRegion.java:26-59` |
| `MarketTopology` | `simos-economy`（实现层） | 私有构造，字段 = `List<MarketRegion> regions` + `Map<HexCoord,MarketRegion> regionByHex` + 6 个只读函数（moveCost / 道路瓶颈 / 最近节点距离 / 费率对象 / 城市折扣 / 农村惩罚）+ `boolean regional` | `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketTopology.java:88-131` |

**谁派生**：
- `MarketNode` 由**组合根 app** 从「城市（`social.cities` 优先，退回 `map.cities`，再退回 `craft@` 产业格）+ tier 半径 + 该格 `Market.numeraire`」现算（`simos-app/src/main/java/io/mosire/simos/app/time/MarketTopologyBook.java:146-203`、`:239-255`）；
- `MarketRegion` 与逐格归属由 `MarketTopology.of(...)` 现算：半径内**最近节点**、同距按 `nodeId` 字典序；锚格强制入表；没有任何半径覆盖的市场格退化成**单格区**（`MarketTopology.java:370-427`）；
- 单区入口另有一条：`MarketTopology.singleRegion(...)`（`MarketTopology.java:165-231`），锚格 = 规范序第一个 hex、`nodeId = "single-region"`、`radiusHex = 0`、`numeraire` 取锚格 `Market.numeraire()`（`:143-148`、`:209-215`）。

**谁持久化 / 有无 Codec、ChangeSet**：**零持久化**。实测：
- `MarketTopology | MarketRegion | MarketNode` 在 `simos-economy/.../codec/` 与 `.../change/` 下**0 命中**（命令：`grep -rn "MarketTopology\|MarketRegion\|MarketNode" --include=*.java simos-economy/src/main/java/io/mosire/simos/economy/codec simos-economy/src/main/java/io/mosire/simos/economy/change` ⇒ 无输出，rc=1）；
- 持久状态只有「逐格市场」`EconomyData.markets`（第 9 个组件，键 = `HexCoord`，值 = `Market(numeraire, prices)`）与「在途批次」`EconomyData.shipments`（第 10 个组件）（`simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:170-192`）；
- 市场表的变更走 `EconomyChangeSet.markets`（`FieldDelta<Market>`）（`simos-economy/src/main/java/io/mosire/simos/economy/change/EconomyChangeSet.java:117`、`:273`、`:311`）。

⇒ **市场区本身是「读时派生件」，不是状态**：`MarketTopology.java:23-53` 的类注与代码一致（未进 `EconomyData`、无变更集、无 codec）。

### Q2. 市场区与 map 的 Region（行政区）现在是什么关系？

**答：代码上「零关系」。** 既不是一一对应，也不是派生自 map `Region`，生产路径是「同币即同区」的单区；只有币种不一致/无城市权威时才退回「城市节点 + tier 半径最近归属」。逐条证据：

1. map 侧确有行政区类型：`io.mosire.simos.map.region.Region`（`simos-map/src/main/java/io/mosire/simos/map/region/Region.java:18`）与 `RegionMeta(color, tag, description, annexedBy)`（`.../region/RegionMeta.java:8`）；
2. economy/economy-api 的 `src/main` 里对 map `Region` 的引用**只有一处**，且与市场区无关——`EconomyClearRegionHandler`（GM 命令 `economy.ClearRegion`，按行政区清经济数据；`simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyClearRegionHandler.java:43-44`、`:106`）；
3. 市场区的成员归属只看**到锚格的 hex 距离**，不看国界、不看行政区（`MarketTopology.java:370-409`；类注 `:39-41` 明写「不按国界、也不按行政相邻」）；
4. 生产装配的优先级：有城市权威且所有 `Market.numeraire` 相同 ⇒ `singleRegion`（全部有市场的 hex 归一个区）；否则退回城市半径路径（`MarketTopologyBook.java:125-139`）；
5. 测试把这条判据钉住了：同币两城 ⇒ 恰一个区、`nodeId = "single-region"`；不同币 ⇒ 退回城市半径路径（`simos-app/src/test/java/io/mosire/simos/app/time/MarketTopologyBookSingleRegionTest.java:52-68`、`:70-87`）；无城市权威 ⇒ 每格一区（`:89-100`）。

**文档 vs 代码（不一致，如实记）**：
- `D-026`（2026-10-06 用户口述）原文要求「**区的第一性来源 = map 的 `Region`**（GM 可编辑）」，并明确「市场区不再是『城市节点 + radiusHex 的最近归属』」（`docs/superpowers/specs/2026-10-02-architecture-design-source.md:420-437`）；
- 代码实际**没有**这条：没有任何一处把 map `Region` 当市场区的来源；退化的城市半径路径仍在（`MarketTopologyBook.java:146-203`），与 D-026 第 1/6 条的措辞直接不符；
- 盘点文档自己也承认卡在「暂缓」：`docs/superpowers/status/2026-10-23-planned-not-implemented-inventory.md:141`（「跨市场区重设计 D-026 …… **暂缓**：现状仍按 `MarketTopology` 的「城市节点＋半径最近归属」」）。

### Q3. 市场区如何被 app 组装/持久化？重建时机与稳定性？

**组装链**（全部现算、不落盘）：

```
SimulationState ──> MarketTopologyBook.from(state[,两个商人调整量函数])
                      ├─ economy = state.module("economy")            (MarketTopologyBook.java:288-298)
                      ├─ map 切片缺席 ⇒ MarketTopology.singleHex(...)  (:115-118)
                      ├─ markets 空   ⇒ singleHex                      (:119-121)
                      ├─ social cities 非空 且 所有 numeraire 相同 ⇒ singleRegion (:125-137)
                      └─ 否则 ⇒ byCityRadius（城市 tier 半径 / craft@ 退化）         (:138-139, :146-203)
                 ──> EconomyDayStepper(base, session, topology, …)   (EconomyDayStepper.java:104/147)
                 ──> EconomySettlement.settleOneDayInto(…, topology, …) (EconomySettlement.java:749-763)
```

**调用点（`src/main` 全量）**：
- `PopulationEconomyTimeParticipant.java:564`（人口-经济推进，同一份拓扑也喂逃亡去向）；
- `EconomyOwnershipTimeParticipant.java:193`（产权/落账推进）；
- `MarketReadoutAssembly.java:72`（逐格读口，GUI/MCP 共用）。

**重建时机与稳定性**：
- 两个 `TimeParticipant` 都是**在进入日循环之前构建一次**，然后整个 range 复用同一个 `stepper`（`PopulationEconomyTimeParticipant.java:564-576`，日循环在 `:606`）⇒ **一次推进（如 120 天）内市场区拓扑是固定的**；
- 读口 `MarketReadoutAssembly.contextFor` 每次查询**重新现算**（`:72`）⇒ 读口看到的是「当前 revision 的市场表 + 当前城市」，与推进期用的那一份可能不同源（同一 revision 内一致；跨 revision 天然重算）；
- 稳定性（可复现性）由构造保证：成员表是内容的纯函数（最近节点 + `nodeId` 字典序 tie-break，`MarketTopology.java:370-378`；单区锚 = 规范序第一个 hex，`:233-242`），集合保序不可变（`:119-120`、`MarketRegion.java:34-38`）。类注声明「一次 360 == 三次 120 同日同轮因此不被拓扑本身破坏」（`:27-29`）。

**重新计算的输入来源**：城市（`social.cities` 权威，退回 `map.cities`，再退回 `craft@`）、`tier` 半径常量表（`MarketTopologyBook.java:75-83`）、逐格地形代价索引（`:272-280`，一次构建）、道路瓶颈（`RoadNetwork.from`）。**没有一处读 `Market.numeraire` 之外的货币输入**（见 Q7）。

### Q4. 多市场区并存支持到什么程度？跨区/在途/承运是否存在？D-026 暂缓后剩了什么？

**结论：机制在（而且是完整的一套），生产世界走不到；但「测试覆盖」几乎为零。**

1. **多区并存是支持的**：`MarketTopology.of(nodes, …)` 支持任意多节点；`regional` 位在「有节点且 built.size() > 1」时为 true（`MarketTopology.java:428-439`）；`adjacent(a,b)` = 两区锚距 ≤ `rA + rB + 1`（`:56-62`、`:474-482`）；`regionOf/contains` 按格取区（`:448-463`）。
2. **跨区撮合存在且真的会执行**：`MarketSettlement` 第 4 步 `matchAcrossRegions(ctx, indexes)`（`:1027-1029`），候选过滤 = 「不同区 ∧ 邻接表命中 ∧ 同商品 ∧ 有活跃槽位」（`:3025-3068`），邻接表只消费 `topology.adjacent`（`:2327-2392`）。
3. **在途/承运是跨 tick 持久状态**：`ShipmentBatch`/`ShipmentAllocation`/`TradeRoute`/`ShipmentId` 全在 `simos-economy-api`，`EconomyData.shipments` 是第 10 个组件（`EconomyData.java:170-192`）；发运时 `loadInTransit`、到货日由 `EconomySettlement` 的到货步销账（`MarketSettlement.java:3543-3547`；`EconomySettlement.java:2929-2931`）；`MarketDemandBook` 也读在途批次（`:286-287`、`:416`）。
4. **「lane」确实存在**，但拼写是 route/RouteContext：区内跨格 ⇒ `immediate = true`（合成「即时但要付运费」的路线，`MarketSettlement.java:3316-3345`）；跨区 ⇒ `immediate = false` ⇒ `inTransit = true`（`:3447-3450`）。
5. **但生产路径走不到跨区**：小世界（全 `silver`）⇒ `sameNumeraire == true` ⇒ `singleRegion`（`MarketTopologyBook.java:128-137`）；单区里 `adjacent` 对同区恒 false（`MarketTopology.java:474-482`）⇒ 跨区候选恒空。这与 D-027「跨市场区暂缓」一致。
6. **聚集（agglomeration）完全没有**：全仓 `src/main` 对 `agglomerat|聚集` 只有 1 处命中，且是注释里的否定句（`MarketSettlement.java:1627`「不跨区、不承运、不聚集」）⇒ D-026 第 2/3 条的「节点由经济聚集自然生成」**零代码**。
7. **测试覆盖（静态检索，未跑 Maven）**：`simos-*/src/test` 里构造拓扑只用 `MarketTopology.singleRegion` / `singleHex`（`grep -rn "MarketTopology\." simos-economy/src/test` 的 20 处命中全是这两者）；`grep -rn "new MarketNode(" simos-*/src/test` = **0 命中**；`grep -rln "TradeRoute" simos-*/src/test` 只命中 `MarketDemandBookTest` 与 `EconomyRoundTripTest`（读/往返，不是跨区撮合）。⇒ **没有任何用例构造多区拓扑、也没有用例断言跨区撮合/跨区在途**。

**D-026 暂缓后「剩了什么」的准确回答**：整条 M2.3/M2.4/M2.5 产线都还在（区域撮合 + 邻接 + TradeRoute ETA/损耗/运力 + ShipmentBatch 在途 + 承运人运费），它们是 2026-09-27 的 `M2-L2`（`git log --oneline -1 6908c455`）落在 D-027（2026-10-06）**之前**的代码；D-027 只把「生产世界切到单区」做实，**没有删除也没有停用**这条产线。另外发现一个**死常量**：`MARKET_CROSS_REGION_SETTLEMENT_IMMEDIATE = true` 只在声明与类注出现，**全仓 0 处代码引用**（`grep -rn "MARKET_CROSS_REGION_SETTLEMENT_IMMEDIATE" --include=*.java .`（排除 `target/`）= 2 行，均在 `MarketSettlement.java:99/189`）。

### Q5. 「货币」现在是什么？CommodityId 里有没有货币商品？余额是什么类型、以什么计价？有没有第二个货币概念？

1. **货币是 `CurrencyId` 身份 + 毫定点整数**：`CurrencyId(String value)`（`simos-economy-api/.../api/id/CurrencyId.java:23-42`）；数量一律**最小币值（毫）的定点整数**，`1 银 = 1000 毫`（`MoneyVocabulary.SILVER_SCALE = 3`，`.../api/money/MoneyVocabulary.java:43-53`）。
2. **`CommodityId` 里没有货币商品**：商品词表 = `grain / cloth / fiber / tool / iron / wood` 六项，**无任何货币类字面量**（`simos-util/src/main/java/io/mosire/simos/util/economy/EconomyVocabulary.java:123-131`；对该文件检索 `silver|coin|currency|money` = **0 命中**）。钱与货是**两个命名空间、两张余额表**（`CurrencyId.java:20-21`；`HouseholdInventory.java:82-85`）。
3. **账户余额类型**：`HouseholdInventory(HouseholdAccountKey key, Map<CommodityId,Long> balances, Map<CurrencyId,Long> money, Map<CommodityId,Long> frozenBalances, Map<CurrencyId,Long> frozenMoney)`，货币余额 `≥ 0`、0 保留、整本覆盖写（`simos-actor/src/main/java/io/mosire/simos/actor/model/HouseholdInventory.java:102-107`、`:44-59`、`:176`）。账户主体自 P2-A §13.3 起**统一为家户**（`AccountPartitionKey(HouseholdId)`），国库 = 政府家户账户（`GovernmentSeigniorage.java:149-153`；`TreasuryWithdrawal.java:128-135`）。
4. **在用的货币只有一种：`silver`**。唯一拼写点 `MoneyVocabulary.SILVER_CURRENCY_ID = "silver"`（`:41`）/ `SILVER_CURRENCY`（`:59`）/ `SILVER_SPECIE`（`:71-77`）；`allCurrencyDefs()` 只返回 `SILVER`（`:87-89`），`allInstruments()` 只返回 `SILVER_SPECIE`（`:96-98`）。全仓 `src/main` 里 `new CurrencyId(<字面量>)` 只有两处按**载荷键**解析（`simos-actor/.../spi/ActorPayloads.java:527`、`:552`），没有任何第二币种的硬编码。
5. **第二个货币概念（维度，不是币种）确实存在**：
   - `InstrumentKind`（`SPECIE / STATE_NOTE / BANK_DEPOSIT`，`.../api/money/InstrumentKind.java:22-55`）与 `MoneyInstrument`（含 `Optional<ActorRef> issuer` / `redeemer`，`:60-98`）：**只有 SPECIE 一种在用，国币/存款因「必须有发行人」的构造期守卫而无法凭空出现**（`MoneyInstrument.java:85-97`；`MoneyVocabulary.java:24-36`）。
   - `MoneyIssuanceKind`（`INITIAL_ENDOWMENT / FISCAL_ISSUE / WITHDRAWAL`，`:14-27`）与 `MoneyIssuanceRecord`（`governmentId/day/cycle/currency/amount/kind/reason`，见 `GovernmentSeigniorage.java:109-118` 的构造）。
   - 读口侧的「货币层级」分类 `privateCirculation / baseMoney / bankDeposits / unclassifiedCurrencies`（`simos-app/.../gui/ApiViews.java:4100-4145`）。
6. **铸币（用户说「暂时不做」的那件事）在代码里的现状**：**有一条约定的「财政发行」路径，没有「铸币生产方式」**。
   - 路径在：`Government implements MoneyAuthority`（`simos-economy/.../model/Government.java:32-39`、`:93-108`），`seignioragePerCycle` 每个产业周期开始日直接把钱记进国库并写 `FISCAL_ISSUE` 审计（`GovernmentSeigniorage.java:20-39`、`:74-147`；日结算接线 `EconomySettlement.java:772`（`MoneyIssuance.syncAuthorities`）、`:784-787`）；另有 `GovernmentDebtIssuance`（周期发债，`:28`、`:67`）与 GM 回笼写口 `TreasuryWithdrawal`（`:18-41`、`:63-171`，本批未接自动路径）。
   - 没有的：`Industry` 的货币产出 / `MintRule` / `mintScale`（与 `HANDOFF-2026-10-23-gov-fiscal-batch.md:116-117` 的 F3 一致）。
   - 默认值：`GOVERNMENT_SEIGNIORAGE_PER_CYCLE_MILLI = 2_000`（毫）/周期、`GOVERNMENT_DEBT_ISSUE_PER_CYCLE_MILLI = 5_000`/周期，**只在「给了政府家户」的 demo 世界生效**；普通多国/多省 seed 的政府是 `issuable = 空集、两个旋钮 = 0` 的创世审计主体（`simos-app/.../world/EconomySeeder.java:759`、`:767`、`:3511-3520`、`:3532-3540`）。
7. **孤儿类型（如实记）**：`MoneyStock`（逐币种流通量/发行/回笼纯读口，`simos-economy/.../time/MoneyStock.java:30-96`）**全仓 0 引用**（命令：`grep -rn "MoneyStock" --include=*.java .`（排除 `target/`）= 只有它自己的 2 行）——**包括测试在内没有任何调用方**，也不被 `EconomyDayStepper` 调用（回答 Lead 追问 4）。

### Q6. 价格与结算：定价在哪一层、什么单位、有没有汇率？

1. **价格是数据、不是公式**：`Market(CurrencyId numeraire, Map<CommodityId,Long> prices)`；类注明写「本类只存价格，不生成、不平滑、不按供需算」（`simos-economy/.../model/Market.java:16-17`、`:46`）。**量纲 = 毫计价货币 / 1 商品单位**（1 商品单位 = 1000 毫商品）：`货款(毫钱) = ⌈数量(毫商品) × 价格 ÷ 1000⌉`（`Market.java:19-25`；实现在 `MarketSettlement.java:3491-3492`、`:1967`）。
2. **定价在哪一层**：出厂价表在组合根 `EconomySeeder`（`MARKET_PRICE_GRAIN/CLOTH/…`，`EconomySeeder.java:1614` 起、`:711-718`），成交价在 economy 的 `MarketSettlement`（区内 = 集散节点格价，`MarketSettlement.java:84`、`:2542-2545`、`:2713-2718`；跨区 = 卖方格参考价的上界，`:3836-3846`）；两侧挂牌限价 `bid/ask` 由 `Market.BID_PER_MILLE = 990` / `ASK_PER_MILLE = 1010` 唯一拼写（`Market.java:104-145`）。自 2026-10-07 起**自适应改价**打开（`MARKET_ADAPTIVE_PRICING_ENABLED = true`，`MarketSettlement.java:203`、`:1084`），只改「区价」且只经 `markets` 这个既有 `FieldDelta` 回写（`:102-106`、`:1181`）。
3. **区级总调控**（D-027 第二层）是**瞬态纯值**：`MarketRegulation(anchor, referencePrices, bidPerMille, askPerMille, quotaPerWindow, tariffPerUnit, open, rules)`，不落盘、不进 `EconomyData`/Codec/ChangeSet，GM 命令面与落盘是后续批次（`MarketSettlement` 同目录 `MarketRegulation.java:16-17`、`:50-58`）。
4. **单 hex 贸易成本**（D-027 第一层）只表达为**实物损耗**，货币侧恒 0：`HexTradeCost.HEX_TRADE_COST_MILLI_PER_UNIT = 0`，`lossPerMille = 2‰ × 距离 × moveCost`、上限 500‰（`HexTradeCost.java:39-50`）。
5. **汇率：完全没有**。逐条零命中命令与结果：
   - `grep -rn "ExchangeRate" --include=*.java .`（排除 `target/`）⇒ **0 命中**；
   - `grep -rn "汇率" --include=*.java simos-*/src` ⇒ **7 命中，全部是「没有汇率」的声明或说明**：`MoneyVocabulary.java:36`、`CurrencyDef.java:24`、`CurrencyId.java:20`、`InstrumentId.java:17`、`Market.java:14`、`EconomySettlement.java:6645`（「不跨币种求和/折换（D-023：不做 FX）」）、`EconomyLiquidationSettlement.java:79`（「货币/其它商品债需要汇率，没有就不折」）；另 `GovBudgetExecutionBridge.java:70` 是**承认在做 1:1**（见 §3-L4）。
   - `MonetaryConversion`（`simos-economy-api/.../api/debt/MonetaryConversion.java:12-18`）**不是汇率**：它是**债务折偿条款**枚举（`NOT_ALLOWED` 默认 / `AT_AGREED_PRICE` / `AT_MARKET_PRICE` 两档留位），类注明写「E4a 没有任何折偿路径；E4b 再把价格口径与币种接进来」。**生产调用点：0 处**（命令：`grep -rn "MonetaryConversion" --include=*.java simos-*/src/main` ⇒ 只有它自己的定义文件）——它与用户说的「两种货币之间的换算」**不是同一件事**。

### Q7. 若「市场区＝同一货币的使用范围」：当前模型能否表达「两个市场区用不同货币、区内只用本币」？

**答：能「存下两种钱」，不能「表达并守住两区各用本币」。** 分两侧说。

**A. 已有的承载体（有代码）**
1. 每格市场自带计价币：`Market.numeraire`（`Market.java:43-50`）——**逐格**可不同；
2. 节点/区自带计价币与收款工具：`MarketNode(nodeId, anchor, radiusHex, numeraire, receiveWith)`、`MarketRegion.numeraire()/receiveWith()`（`MarketNode.java:26-45`、`MarketRegion.java:51-59`）；
3. 账户/发行/审计都以 `CurrencyId` 为键：`HouseholdInventory.money`（`HouseholdInventory.java:105`）、`MoneyIssuanceRecord.currency`（`GovernmentSeigniorage.java:110-118`）、`MoneyStock` 逐币种（`MoneyStock.java:35-45`）；
4. 读口逐币种、不跨币种求和：`ApiViews.moneyTotals`（TreeMap 键 = 币种，`ApiViews.java:4153-4159`）、`moneyLayers`（`:4089`、`:4100-4145`）、`economyHex.numeraire`（`:4331-4343`）；
5. 订单/预算的**工具维**字段已经在契约里：`Budget.instrument`、`BuyOrder.payWith`、`SellOrder.receiveWith`（`simos-economy-api/.../api/market/Budget.java:23`、`BuyOrder.java:46`、`SellOrder.java:42`），且 `BuyOrder` 构造期判 `payWith == budget.instrument()`（`BuyOrder.java:75-80`）；
6. D-027 的「同币即同区」判据确实在代码里：`MarketTopologyBook.sameNumeraire`（`:206-219`）⇒ 全同币走单区（`:128-137`）。

**B. 堵死这条路的地方（关键，逐条给行号）**
1. **成员归属不看币种**：`MarketTopology.of` 的逐格归属只用 hex 距离（`:370-396`），成员表**不校验成员格的 `numeraire` 与锚格一致**。测试甚至把「铜币格落进银币城半径」当作**期望行为**断言下来（`MarketTopologyBookSingleRegionTest.java:70-87`：1 个区、半径 2、非 `single-region`）。
2. **多区路径硬编码只认 silver 锚点**：`MarketTopologyBook.addNode` 对 `!SILVER_CURRENCY.equals(market.numeraire())` **直接 return**（`:246-248`），并给 `receiveWith` 恒填 `SILVER_SPECIE`（`:254`）；`singleRegion` 与退化单格区同样恒填 `SILVER_SPECIE`（`MarketTopology.java:215`、`:423`）。⇒ 非银币种的城市**进不了节点表**，它的格要么被邻居银区吸收（见 1），要么退化成单格区。
3. **结算完全没有币种相等校验**：成交时货款腿与运费腿的币种 = `buy.currency`（`MarketSettlement.java:3562`、`:3581`），而 `buy.currency` 来自**买方所在格**的 `market.numeraire()`（`:977`）。卖方收到的是买方的币；文件里唯一的币种相等判断在**借贷**路径（`:1707` `!candidate.currency.equals(buy.currency)`）⇒ **买货不校验、借钱校验**，口径不一致。
4. **卖方槽位根本没有币种字段**：`SellSlot` 只有 `(order, seller, hex, market, region, regionId, costEstimate, …)`（`:5176-5204`），没有 currency；`BuySlot` 有（`:5121-5154`）。⇒ 「卖方只收本币」在类型上无法表达。
5. **区级成交价来自锚格价目表**（锚格的 numeraire），但买卖预算按各自格的 numeraire 算：区内撮合价 = `anchorMarket` 的价格（`:2542-2545`、`:2713-2718`），买单预算 = 本格 `market.numeraire()` 的余额（`:1352`），挂牌参考价 = 本格价表（`:1298`）。⇒ 一旦成员币种不一致，就是**按 A 币的价、用 B 币 1:1 支付**。
6. **`receiveWith` 是死字段**：`MarketRegion.receiveWith()` / `MarketNode.receiveWith()` 在 `src/main` 里**没有任何调用方**（命令：`grep -rn "receiveWith()\|payWith()\|\.instrument()" --include=*.java simos-*/src/main` ⇒ 只有定义与 `BuyOrder` 内的构造期比较）；订单侧三个字段在构造处被硬编码为 `SILVER_SPECIE`（`MarketSettlement.java:250`、`:1344`、`:1379-1380`），读口如实标注「**接受规则**（谁收哪种工具）仍未实现 ⇒ 本层市场只收 silver-specie 单一工具」（`ApiViews.java:3366-3371`）。
7. **GM 改不动「某格用哪种钱」**：唯一的市场写命令 `economy.SetMarketPrice` **保留** existing `numeraire`，新建市场时**硬编码 silver**（`simos-economy/.../spi/EconomySetMarketPriceHandler.java:44`、`:108-113`）；逐格 `numeraire` 只能在创世载荷里指定（`economy.Seed`，`EconomySeedHandler.java:56` + `EconomyPayloads.java:583-645`，解析在 `:640`）。
8. **出厂世界是单一全局币**：`EconomySeeder.MARKET_NUMERAIRE = RegimeRelations.DEFAULT_CURRENCY`（`EconomySeeder.java:648`，后者 = `MoneyVocabulary.SILVER_CURRENCY`，`simos-economy/.../model/RegimeRelations.java:147`），且**每个有 entry 的格都拿到同一个 `MARKET_FACTORY`**（`EconomySeeder.java:718`、`:3358`）⇒ 真实小世界必然同币、必然单区。
9. **「一个币种一个发行人」是硬不变量**：`MoneyIssuance.register` 对同币种的第二个发行主体**当场抛**（`MoneyIssuance.java:76-83`、`:137-147`）；`Government.authorityOf` 对 `issuable` 外的币种抛（`Government.java:93-102`）；零登记时 `requireIssuerOf` 保留 H4 fail-closed（`MoneyIssuance.java:164-181`）。⇒「同一币种、多个市场区、各自发行」不可表达（这是同币即同区的另一面，本身合理，但要与「多区」设计一起考虑）。
10. **跨币种求和点（量纲缺口）**（详见 §3-L4）：`EnterpriseProfitBook.addRevenue/addCost` 把 `money.values()` 全币种相加成一个 long，注释自认「跨币种求和的量纲缺口见收口报告」（`simos-economy/.../time/EnterpriseProfitBook.java:662-689`）；`MerchantSettlement.feeRevenueOf` 同款（`:341-353`）；`EconomySettlement` 的两处读口/追踪求和（`:503`、`:2795`）。
11. **政府预算把 grain/cloth/silver 按「毫」1:1 折成同一「价值」**：`GovBudgetExecutionBridge` 类注明写（`:70`），并以此定 `capPerCycle/minPerCycle` 分配；同一文件把 `GRAIN/CLOTH/SILVER` 三个常量并列（`:96-100`）⇒ 这是**币种与商品、以及不同商品之间**三向量纲塌成一个数的真实落点。
12. **货币词表侧的守卫禁止「无发行人的国币」**：`MoneyInstrument` 的构造期守卫（`STATE_NOTE`/`BANK_DEPOSIT` 必须有 issuer、`SPECIE` 必须为空，`MoneyInstrument.java:85-97`）+ `MoneyVocabulary` 只登记 `SILVER_SPECIE`（`:71-98`）。⇒ 想给第二个区加「国币」必须先有发行人（`MoneyAuthority` 实现 + 登记），这不是 bug，但决定了「两区不同货币」的最小可行形态（见 §4）。

### Q8. 缺口清单

见 §4（三档表）。

---

## 2. 回答 Lead 追加的货币侧追问（L1–L4）

### L1. `MarketNode` 的 `numeraire` / `receiveWith` 取值来源；有没有路径让两个城市用不同币种？

- **`numeraire` 的唯一取值来源 = 该锚格的 `Market.numeraire`**（三个构造点全同）：
  - `MarketTopology.singleRegion`：锚格 `anchorMarket.numeraire()`（`MarketTopology.java:203-215`）；
  - `MarketTopology.of` 的节点路径：由 `MarketTopologyBook.addNode` 传入 `market.numeraire()`（`MarketTopologyBook.java:242-254`）；
  - `MarketTopology.of` 的退化单格区：`market.numeraire()`（`MarketTopology.java:411-423`）。
- **`receiveWith` 恒为全局常量** `MoneyVocabulary.SILVER_SPECIE.id()`（`MarketTopologyBook.java:254`、`MarketTopology.java:215`、`:423`）⇒ **不随城市/国家/币种变化**。
- **有没有路径让两个城市用不同币种？** 有，但只有「数据入口」这一条，且下游半通：
  1. `Market` 的 record 允许任意 `CurrencyId`（`Market.java:46-50`）；
  2. 创世载荷 `economy.Seed` 的 `markets` 键**逐格带 `numeraire`**（`EconomyPayloads.java:583-645`、`:640`），`EconomySeeder` 自己也是这么写的（`:1518-1521`）；
  3. `MarketTopologyBook` 对「币种不一致」有明确分支：退回城市半径路径（`:125-139`、`:142-145`），且有单测（`MarketTopologyBookSingleRegionTest.java:70-87`）；
  4. **但下游三处断**：非银锚点被 `addNode` 丢弃（`:246-248`）、成员归属不校验币种（`MarketTopology.java:370-396`）、结算不校验币种（`MarketSettlement.java:3562`/`:3581`）。⇒ **能种进去，跑起来会串币**。
  5. 没有任何**派生**路径（不按城市/国家/行政区算币种），也没有 GM 工具能事后改某格的币种（L2）。

### L2. `EconomySeeder` 里 markets 的 `numeraire` 从哪读？完整调用链

```
EconomySeeder.MARKET_NUMERAIRE = RegimeRelations.DEFAULT_CURRENCY            (EconomySeeder.java:648)
   └─ RegimeRelations.DEFAULT_CURRENCY = MoneyVocabulary.SILVER_CURRENCY       (RegimeRelations.java:147)
        └─ MoneyVocabulary.SILVER.currencyId()，id 字面量 "silver" 全仓唯一拼写 (MoneyVocabulary.java:41/53/59)
EconomySeeder.MARKET_FACTORY = new Market(MARKET_NUMERAIRE, MARKET_PRICES_FACTORY)   (EconomySeeder.java:718)
   └─ 每个有 entry 的格：markets.put(hex, MARKET_FACTORY)                      (EconomySeeder.java:3358)
        └─ 创世载荷写出 {"numeraire": market.numeraire().value(), "prices": …}   (EconomySeeder.java:1518-1521)
             └─ 命令 economy.Seed（EconomySeedHandler.java:56）
                  └─ EconomyPayloads.parse → toData → markets(payload, entries)  (EconomyPayloads.java:294/592-645)
                       └─ new Market(CurrencyId.parse(requireText(node,"numeraire")), prices)  (:640-642)
                            └─ EconomyData.markets（第 9 个组件）                  (EconomyData.java:170-186)
                                 └─ MarketTopologyBook.from(state) 读它            (MarketTopologyBook.java:114/199/209-219)
```

- **不是 worldParams 键**：全仓 `src/main` 里 `worldParams` 与货币/计价币无关（`grep -rn "worldParams" simos-app/src/main simos-economy/src/main` 无货币键命中）；`config/worldgen/*.json`、`config/shell.json`、`run-small-world.sh` 里对 `numeraire|currency|silver` **0 命中**（命令见 §6）。
- ⇒ **真实小世界的计价币是编译期常量**（`MoneyVocabulary.SILVER`），逐格配置虽然从创世起就支持，但**产品路径没有第二处来源**。
- 读侧同样逐格发 `numeraire`（`ApiViews.java:1840-1849`、`:4343`；`EconomySeeder` 载荷 `:1521`）。

### L3. 市场结算是否「按 numeraire 取价、只用本币结算」？有没有把不同币种当同一本账的地方？

**「按 numeraire 取价」= 部分是；「只用本币结算」= 没有守卫。** 逐点：

| 环节 | 代码事实 | file:line |
|---|---|---|
| 挂牌参考价 / 限价 | 取**本格**价表（`effective.hasPrice(market,…)`、`regulatedReference(market,…)`） | `MarketSettlement.java:1295-1300` |
| 买单预算 | 按**买方格** `market.numeraire()` 取可花余额 | `:1352` |
| 买槽币种 | `new BuySlot(…, market.numeraire())`（买方格） | `:977` |
| 区内成交价 | 取**区锚格**价表与锚格调控（`anchorMarket`） | `:2542-2545`、`:2713-2718` |
| 跨区成交价 | `unitPriceOf(ctx, sells, commodity)`（卖方格参考价，取上界） | `:3836-3846` |
| 货款腿 | `Map.of(buy.currency, payment)`：买方 → 卖方 | `:3556-3563` |
| 运费腿 | `Map.of(buy.currency, charge.amountMilli())`：买方 → 承运人 | `:3574-3582` |
| 借贷腿 | **有**币种相等校验（唯一的币种校验） | `:1707` |
| 余额读取 | 逐币种（`moneyOf/spendableMoneyOf(…, currency)`） | `:5034-5062` |
| 读口 | 逐币种、不跨币种求和（`moneyTotals` TreeMap / `moneyLayers`） | `ApiViews.java:4089`、`:4100-4145`、`:4153-4159` |
| 债务估值 | 明文「不做 FX」：非价目表 numeraire 的币种不折算 | `DebtValuation.java:64`、`:243`、`:296-301` |
| 清算 | 只对粮债接线；货币/其它商品债需要汇率，没有就不折 | `EconomyLiquidationSettlement.java:79` |

⇒ **结论**：结算在「取值」一侧是**逐格/逐区按 numeraire 读表**的；但「支付」一侧**只有买方币种一个维度**，卖方/承运人收到什么币由买方决定，**没有任何「卖方本币 == 买方币种」的校验**。所以存在「把不同币种当同一本账」的路径（L4 列表），其中 `MarketSettlement:3562/3581` 是**最危险的一处**（它把 1:1 直接落成账）。

### L4. 全币种求和的落点（汇率/兑换要接的地方）

实测（`grep -rnE "(money|wallet|Money)\w*\.values\(\)" --include=*.java simos-economy/src/main simos-app/src/main simos-actor/src/main simos-gov/src/main`）后逐处判读：

| # | 落点 | 性质 | 证据 |
|---|---|---|---|
| 1 | `MarketSettlement` 货款腿/运费腿只用 `buy.currency` | **真·1:1 跨币种支付（无校验）** | `MarketSettlement.java:3562`、`:3581` |
| 2 | `EnterpriseProfitBook.addRevenue/addCost`：`money.values()` 全币种相加成一个 long | **真·跨币种量纲缺口**（注释自认） | `EnterpriseProfitBook.java:662-675`、`:677-689`（`:671` 注释「跨币种求和的量纲缺口见收口报告」） |
| 3 | `MerchantSettlement.feeRevenueOf`：`transfer.money().values()` 累加 | 跨币种求和（现实里运费单币种） | `MerchantSettlement.java:341-353` |
| 4 | `EconomySettlement.traceTotalMoney`：家户→币种两层表求和 | 仅 DEBUG 追踪读数，不入账 | `EconomySettlement.java:500-508` |
| 5 | `EconomySettlement` 逐 reason 的 `transferMoney` 汇总：`transfer.money().values()` 相加 | 读口/诊断计数，跨币种 | `EconomySettlement.java:2793-2800` |
| 6 | `GovBudgetExecutionBridge`：grain/cloth/silver 按「毫」**1:1** 计入同一「价值」 | **真·币种与商品三向塌成一个数**（明文承认） | `GovBudgetExecutionBridge.java:70`（+ `:91-95` 三个常量） |

**没有**发现跨币种求和的（逐币种正确）：`MoneyStock.circulation/cumulativeIssuance`（`MoneyStock.java:35-96`）、`ApiViews.moneyTotals/moneyLayers`（`ApiViews.java:4089`、`:4153-4159`）、`MoneyIssuance.issuable()`（`MoneyIssuance.java:49-57`）、`AccountSession`/`AccountSnapshot` 的逐币种腿（`AccountSession.java:321`、`EconomySettlement.java:7383`、`:7765-7873`）。

---

## 3. 补充：Lead 点名的其余货币侧目标（快速核账）

| 目标 | 事实 | file:line |
|---|---|---|
| `MoneyAuthority` | **纯接口，零默认实现**；`Government` 是**唯一实现者** | `MoneyAuthority.java:33-49`；`Government.java:39/93-108` |
| `MoneyIssuance` 闸门 | E3 起**可登记**：进程内静态表 + `syncAuthorities`（日结算按当前世界 `governments` 重建）；零登记时 `requireIssuerOf` 保留 H4 fail-closed（当场抛）；**一个币种只许一个发行主体** | `MoneyIssuance.java:30`、`:65-88`、`:125-152`、`:164-181`；接线 `EconomySettlement.java:772` |
| `MoneyIssuanceRecord` / `MoneyIssuanceKind` | `(id, governmentId, day, cycle, currency, amount, kind, reason)`；三档 `INITIAL_ENDOWMENT/FISCAL_ISSUE/WITHDRAWAL`，金额恒正、方向由 kind 表达 | `GovernmentSeigniorage.java:108-121`；`MoneyIssuanceKind.java:14-27` |
| `GovernmentActors` | `GovernmentId ↔ ActorRef` 唯一拼写点（前缀 `gov-`） | `GovernmentActors.java:18-57` |
| `MonetaryConversion` | **债务折偿条款**枚举（不是两种货币的换算）；三档，默认 `NOT_ALLOWED`；**生产调用点 0** | `MonetaryConversion.java:12-18` |
| `MoneyStock` | 纯读口（逐币种流通量/发行/回笼/差额守恒式）；**全仓 0 引用**（含测试）；**不被 `EconomyDayStepper` 调用** | `MoneyStock.java:30-96`（引用检索见 §6） |
| `InstrumentId` / `MoneyInstrument` | 工具身份（不带面额/成色/汇率）；SPECIE/STATE_NOTE/BANK_DEPOSIT 三档守卫；只有 `silver-specie` 在用 | `InstrumentId.java:17`；`MoneyInstrument.java:60-98`；`MoneyVocabulary.java:62-98` |
| `Market` / `MarketNode` 是否已带「本区货币」字段 | **都已带**：`Market.numeraire`（逐格）、`MarketNode.numeraire` + `receiveWith`（逐区） | `Market.java:46`；`MarketNode.java:26-27` |

---

## 4. 缺口三档清单（有代码 / 半成品 / 完全没有）

### A. 有代码（可用、有落点）
1. 逐格计价币与价格表：`Market(numeraire, prices)`（`Market.java:46`）+ 读写口（`ApiViews.java:1840-1849`、`:4331-4343`）——**逐格异币种在数据层是支持的**。
2. 逐区计价币契约：`MarketNode.numeraire` / `MarketRegion.numeraire()`（`MarketNode.java:26-27`、`MarketRegion.java:51-54`）。
3. 同币即同区单区生产路径：`MarketTopology.singleRegion`（`MarketTopology.java:165-231`）+ `MarketTopologyBook.sameNumeraire`（`:206-219`）+ 单测（`MarketTopologyBookSingleRegionTest.java:52-68`）。
4. 以 `CurrencyId` 为键的账户/冻结/发行审计：`HouseholdInventory.java:105-107`、`MoneyStock.java:35-96`、`MoneyIssuanceRecord`（`GovernmentSeigniorage.java:108-121`）。
5. 货币词表 + 工具三档 + 「无发行人不得造国币」守卫：`MoneyVocabulary.java:38-98`、`InstrumentKind.java:22-55`、`MoneyInstrument.java:85-97`。
6. 发行/回笼路径（约定形态）：`Government implements MoneyAuthority`（`Government.java:32-108`）、`MoneyIssuance.register/syncAuthorities/requireIssuerOf`（`MoneyIssuance.java:65-181`）、周期财政发行 `GovernmentSeigniorage`（`:74-147`）、周期发债 `GovernmentDebtIssuance`（`:28`/`:67`）、GM 回笼 `TreasuryWithdrawal`（`:63-171`）。
7. 单区内跨 hex 运输与损耗（D-027 第一层）：`HexTradeCost`（`:1-48`）+ `executeTrade` 的损耗落账（`MarketSettlement.java:3496-3541`）。
8. 区级总调控值类型（D-027 第二层）：`MarketRegulation`（`:15-32`）。
9. 逐币种读口纪律（不跨币种求和）：`ApiViews.java:4089`、`:4100-4145`、`:4153-4159`；`EconomySettlement.java:6645`。
10. 多区机制本体（跨区撮合 + 邻接 + TradeRoute + 在途批次）：`MarketSettlement.java:1027-1029`、`:2327-2392`、`:3025-3068`、`:3316-3345`、`:3424-3613`；`EconomyData.shipments`（`EconomyData.java:170-192`）。

### B. 半成品（有形状/未接线/未守）
1. **订单工具维**：`Budget.instrument` / `BuyOrder.payWith` / `SellOrder.receiveWith` 有字段与构造期互校，但生产构造处硬编码 `SILVER_SPECIE`，结算不读、无「接受规则」（`MarketSettlement.java:250/1344/1379-1380`；`ApiViews.java:3366-3371`）。
2. **`MarketRegion.receiveWith()` / `MarketNode.receiveWith()`**：零调用方（死读口）。
3. **多区路径**：`MarketTopology.of(nodes,…)` + 跨区撮合 + 在途全在，但生产世界走不到（`MarketTopologyBook.java:128-139`），且**无任何多区测试**（§1-Q4 第 7 条）。
4. **`MARKET_CROSS_REGION_SETTLEMENT_IMMEDIATE`**：死常量（`MarketSettlement.java:189`，全仓 0 引用）。
5. **跨区「在途」与「区内即时」的币种口径**：在途只换手货物，钱在发运日结清（`MarketSettlement.java:99-100` 类注；`:3449` 实现），但**币种维度未表达**。
6. **`MoneyStock`**：写好了纯读口与守恒式，**没人调用**（含 `EconomyDayStepper`）。
7. **`MonetaryConversion`**：枚举身份固定，**零接线**（E4b 留位）。
8. **区级市场总调控**：值类型在，**不落盘、无 GM 工具**（`MarketRegulation.java:16-17` 明写）。
9. **`GovernmentDebtIssuance`/`TreasuryWithdrawal`**：写口在，但「自动路径 / GM 工具面」按类注属后续批次（`TreasuryWithdrawal.java:37-40`）。
10. **预算的 1:1「价值」**：`GovBudgetExecutionBridge` 明确用 1:1 兜住「世界没有汇率/价格表」（`:70`）——这是**临时口径**，任何汇率/估值机制落地时这里是必改点。
11. **`EnterpriseProfitBook` 的跨币种求和**：自认量纲缺口（`:662-689`）。
12. **map `Region` 里的 `RegionMeta.tag/annexedBy`**：行政区类型在（`RegionMeta.java:8`），但**市场区一条都不读**（D-026 未实现）。

### C. 完全没有
1. **汇率 / 兑换 / 换算（两种币之间的比价）**：`ExchangeRate` 全仓 0 命中；`汇率` 7 处全是「没有汇率」的声明；`M1-A`/`D-023`/`Market.java:14`/`MoneyVocabulary.java:36`/`EconomySettlement.java:6645` 明文禁止折算。
2. **「一个市场区只能用本币」的不变量**：无任何校验（成员币种 @ `MarketTopology.java:370-396`；成交币种 @ `MarketSettlement.java:3556-3582`）。
3. **市场区的货币维度落持久状态 / Codec / ChangeSet**：`MarketTopology|MarketRegion|MarketNode` 在 codec/change 下 0 命中；`spec §8.2` 也承认「同币多区判定需要组合根明确输入；银行/关税区/不同货币的区分留后续」。
4. **GM 改某格/某区计价币的工具**：`economy.SetMarketPrice` 保留原 numeraire、新建市场硬编码 silver（`EconomySetMarketPriceHandler.java:108-113`）。
5. **按行政区/政府辖区派生市场区**：0 处（D-026 未做）。
6. **经济聚集（agglomeration）与节点自然生成**：0 处（唯一命中是注释 `MarketSettlement.java:1627`）。
7. **多币种的货币词表内容**：`allCurrencyDefs()` 只有 `silver`（`MoneyVocabulary.java:87-89`）；`issuable` 实际只被 seed 填 `{silver}` 或空集（`EconomySeeder.java:3517`、`:3537`）。
8. **跨币种账务的任何形式（兑换所/找换商/外汇市场/多币种预算）**：0 处。

---

## 5. 与用户裁定「市场区＝同一货币使用范围」直接冲突或堵死路的类与不变量

> 判定标准：这些地方**不是实现缺失**，而是**当前形状与不变量会主动把「两区不同货币、区内只用本币」这条路走歪或堵死**。

1. **`MarketSettlement.executeTrade` 的支付腿（最硬的一处）**——`Map.of(buy.currency, payment)` 与 `Map.of(buy.currency, freight)`（`:3562`、`:3581`）。类型上**没有卖方币种这一维**，运行期**没有相等校验**。⇒ 「区内只用本币」在结算层不可表达，且异币场景会**静默按 1:1 支付**（不是抛，是错账）。
2. **`SellSlot` 无币种字段**（`MarketSettlement.java:5176-5204`）——「卖方只收本币」在类型上无处安放；唯一有币种的槽是买方槽（`:5121-5154`）。
3. **`MarketTopology.of` 的成员归属不校验 `numeraire`**（`:370-396`）——同一区可以混币种；`MarketTopologyBookSingleRegionTest.java:70-87` 把「铜币格落进银币城半径」断言成期望行为，**测试层面已经把这条不变量排除掉了**。
4. **`MarketTopologyBook.addNode` 硬跳过非 silver**（`:246-248`）+ `receiveWith` 恒填 `SILVER_SPECIE`（`:254`）——把「币种」与「`silver`」在**节点构造处绑死**；第二币种的区要么被丢弃、要么退化成没有任何承运/邻接语义的单格区。
5. **`MoneyIssuance.register` 的「一币一发行人」不变量**（`:76-83`、`:137-147`）+ `Government.issuable`（`Government.java:93-108`）——「同币多区」在制度上不可表达（这与「同币即同区」自洽，但意味着**跨区一定跨币**，所以第 1~4 条必须补，不能绕开）。
6. **`MoneyInstrument` 的 issuer 守卫**（`MoneyInstrument.java:85-97`）+ `MoneyVocabulary` 只登记银币（`:71-98`）——第二币种若走「国币/存款」形态，**必须先有发行人（`MoneyAuthority` 实现 + 登记）**，否则构造期就抛；若走「金属币」形态，则可以没有发行人（现成的 `SPECIE` 通道）。
7. **`Market.numeraire` 在成交价与支付币之间的错位**：区内成交价取**锚格价表**（`:2542-2545`、`:2713-2718`），预算取**本格余额**（`:1352`）——一旦成员币种不一致，这两个数就不同量纲。
8. **`GovBudgetExecutionBridge` 的 1:1 三向量纲塌缩**（`:70`、`:96-100`）——它把 `grain/cloth/silver` 当同一「价值」，是「兑换机制」落地时**必须先换掉**的既有口径（否则预算侧会继续按 1:1 给跨币世界发钱）。
9. **`EnterpriseProfitBook`/`MerchantSettlement` 的跨币种求和**（`:662-689`、`:341-353`）——利润/商号收益在异币世界会把两种钱加成一个数。
10. **文档层面的一处冲突（需裁定，不是代码 bug）**：`D-026`（`docs/superpowers/specs/2026-10-02-architecture-design-source.md:420-437`）仍写着「市场区的第一性来源 = map 的 `Region`（GM 可编辑）」；而用户 2026-10-08 的裁定 ② 说「行政区、市场区不能搞混……可以半包含、部分重合」。**当前代码两者都没实现**（既没接 map `Region`，也没按币种划区，只按城市半径/单区兜底）⇒ 若下一步要落「市场区＝同一货币使用范围」，**D-026 第 1 条的措辞需要更新**（否则派单会把市场区重新绑回行政区）。这属于 §一.8.1 的「设计与用户原话冲突 ⇒ 上报用户」，不该由实现者静默选边。

---

## 6. 我没核到的（如实记）

1. **没有跑任何构建/测试**（任务禁跑 Maven）。所有「无测试覆盖」的结论都来自**静态检索**（`grep`/`find`），不是 surefire 报告；若存在通过反射/参数化间接构造多区拓扑的用例，我的检索方式看不到。
2. **没有验证运行时行为**：真实小世界（sw19）里 `sameNumeraire` 是否恒 true、`single-region` 是否实际生效，我只核到代码路径与单测，**没有读 run7 的 live 读数/日志**来旁证。
3. **`MoneyIssuanceRecord` 字段序**只从构造点（`GovernmentSeigniorage.java:109-118`）读到，**没有打开该 record 文件逐字段核**，也未核它的 Codec 线格式与旧档兼容分支。
4. **`EconomyCodec` 对 `markets`/`numeraire` 的编解码**：我用「`grep numeraire EconomyCodec.java` = 0 命中」发现了疑点，随后判断它走的是**通用 record 绑定 + 自定义 key deserializer**（`EconomyCodec.java:99-100`、`:230-233`），**没有逐行读完 2000+ 行的 codec** 去确证 `Market.numeraire` 一定在字节层往返（也没有跑往返测试）。
5. **`MoneyIssuance` 静态登记表的跨世界/并发语义**：类注自认「进程级、跨世界隔离如实记」（`MoneyIssuance.java:21-23`），我**没有核**它在 GUI 多会话/多世界并发下的实际表现。
6. **`MarketRegulation` 的 GM 命令面、以及 `quota/tariff` 是否真在结算里生效**：只核到它的值类型与引用点，**没有逐个消费点读完** `MarketSettlement` 5553 行的 regulation 分支。
7. **`GoldBudgetExecutionBridge` 之外的其他政府/军队侧 1:1 折算点**：我只按 `money.values()`/「汇率」两类检索，**没有系统扫描** `simos-gov`/`simos-army`/`simos-unit` 的全部金额与实物混算点。
8. **`GovernmentDebtIssuance` 的完整行为**（借入对象、失败语义）只读了类注与 `target` 行（`:28`、`:67`），**没有读完该类**。
9. **`docs/**` 的历史裁定只读了 D-023~D-031 附近与盘点/交接**，没有把 `docs/superpowers/specs/**` 全部 2026-10-* 文档通读；若更晚的文档已改写 D-026，我可能没看到（但**代码事实不受影响**）。
10. **`simos-gov` 的行政区（GOV 单位辖区）与 map `Region` 的绑定关系**（第 2 问只从市场区一侧核；GOV 侧未展开）。

## 7. 附：关键检索命令原文（便于复核）

```bash
# 汇率零命中
grep -rn "ExchangeRate" --include=*.java . | grep -v '/target/'          # ⇒ 0
grep -rn "汇率" --include=*.java simos-*/src                              # ⇒ 7，全部为「没有汇率」声明/说明
# 拓扑无持久化
grep -rn "MarketTopology\|MarketRegion\|MarketNode" --include=*.java \
  simos-economy/src/main/java/io/mosire/simos/economy/codec \
  simos-economy/src/main/java/io/mosire/simos/economy/change             # ⇒ 无输出
# receiveWith/payWith 死字段
grep -rn "receiveWith()\|payWith()\|\.instrument()" --include=*.java simos-*/src/main
# 孤儿 MoneyStock
grep -rn "MoneyStock" --include=*.java . | grep -v '/target/'             # ⇒ 仅其自身 2 行
# 死常量
grep -rn "MARKET_CROSS_REGION_SETTLEMENT_IMMEDIATE" --include=*.java . | grep -v '/target/'  # ⇒ 2 行（同文件）
# 多区测试覆盖
grep -rn "new MarketNode(" --include=*.java simos-*/src/test              # ⇒ 0
grep -rn "MarketTopology\." --include=*.java simos-economy/src/test       # ⇒ 仅 singleRegion / singleHex
# 真实世界的计价币来源
grep -rn "numeraire\|currency\|silver" config/worldgen/*.json config/shell.json run-small-world.sh  # ⇒ 0
```
