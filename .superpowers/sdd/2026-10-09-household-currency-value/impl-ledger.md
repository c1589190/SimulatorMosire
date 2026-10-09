# E：家户货币估值 + 拆掉异币硬拒 —— 实现架构账本（2026-10-09）

责任区：**E**（唯一权威文档 `docs/superpowers/specs/2026-10-09-household-currency-acceptance-ruling.md` §3.1；
上位 `docs/superpowers/specs/2026-10-09-market-zone-and-gov-money-design.md`）。基线提交 `1bf41015`。
判据：E1–E6 / N1–N3（同上 §3.1）。**结论先行：编译 exit=0；E1/E2/E3/E4/N1/N2/N3 有真实探针证据；
E5 用同一机制的实跑证据替代（造不出"三不管格"，见 §7）；E6 发现并如实记下更早的物流约束（见 §6.6）。**

## 0. 任务板

`team_task_get task-20` ⇒ `Error: agent "…" is not a member of an active Agent Team` ⇒ **未能 claim**（按任务书：
不卡住，照任务书执行）。本账本与最终回复即交付留痕。

## 1. 关键调查结论（file:line，2026-10-09 于 `1bf41015` 核实）

| # | 结论 | 证据 |
|---|---|---|
| 1 | 旧"唯一拼写点"= `rejectCurrencyMismatch`，三条腿均调它；它记 INFO `MARKET_CURRENCY_MISMATCH_REJECTED` 并把买卖两侧置 `CURRENCY_MISMATCH` | `MarketSettlement.java:3956-4021`；调用点 `:2027`(money-credit)、`:2130`(goods-credit)、`:4051`(cash) |
| 2 | 卖方"收款币"= 本格市场计价币；买方"支付币"= 本格市场计价币（两者都是 `market.numeraire()`，成交价按卖方格/区参考价） | `SellSlot` 构造 `:5901`；`BuySlot` 构造 `:1197`；`unitPriceOf :4449`；`matchAcrossRegions :3466`；`matchRoute :3576`；`pairUp :3794` |
| 3 | 三条腿的钱腿：cash = `executeTrade`（`:4035`）、money-credit = `moneyCreditForBuy`/`executeMoneyCredit`（`:1965/:2139`）、goods-credit = `goodsCreditForBuy`（`:2091`，**不铸钱腿**，债务单位 = 商品 `DebtUnit.Commodity`） |
| 4 | 家户保留价算式只活在 `HouseholdValuationBook.derive` 的内层循环里，唯一调用点是**劳动阶段**的套利 | `LaborQueueSettlement.java:203-205`；算式 `HouseholdValuationBook.java:203-227`（旧行号） |
| 5 | "货币价格"的现成来源只有一处：本轮的官方汇率窗口（GOV 级 + 区级覆盖），逐轮瞬态、**不进状态** | `FxRoundInput.of(...)` 装配于 `EconomySettlement.java:1723`；`MarketRound.fx()`；汇率不进状态（I17） |
| 6 | ★ 三区世界创世**零官方汇率**（`three-powers` 没有任何 `SetOfficialRate`）⇒ 起点"世界行情"为空 | `ThreePowersWorld.java` / `ThreePowersGovBootstrap.java` 逐字 grep 零命中 |
| 7 | 撮合域口径：区内 + **直接邻接区**；邻接判据 = 锚距 ≤ 半径和 +1；同一处被索引复用 | `MarketTopology.adjacent :560`；`MarketIndexes.build :2738` 用同一个 `topology.adjacent` |
| 8 | ★★ **跨区（隔区）货腿结构性不可达**：创世商号 tier = PORTER ⇒ 服务半径 **2**，而"服务某 lane"要求商号 home 到**两端**都 ≤ 半径 ⇒ 隔区 lane 没有承运商 ⇒ `allocated = 0` ⇒ `LOGISTICS_CAPACITY` | `EconomySeeder.java:210`（`MERCHANT_TIER = PORTER`）、`MerchantFirm.java:64-70`（半径 2）；`MerchantSettlement.servesLane :646`、`CarrierPool.select :232`、`allocate :286`；`executeTrade` 的 `allocated <= 0 ⇒ markCapacityBlocked` |
| 9 | 旧世界实测噪声规模：`three-powers` 0→60 天共 **75,564** 条 `MARKET_CURRENCY_MISMATCH_REJECTED`（`leg=cash` 全部），日志 25.7 MB | `/tmp/e-probe/oldA.log`、`pfOld2.log`（探针输出见 §6） |

## 2. 实现架构（代码怎么把这个世界长出来）

### 2.1 新增 `CurrencyValuation`（`time` 包，逐轮瞬态、不进状态）

```
CurrencyValuation
  quotedMicroPerMilli : Map<CurrencyId,Long>   // 世界行情 V(c) = 微根币/毫 c（根 = 币种 id 升序第一个）
  quoted              : Set<CurrencyId>        // 有报价的币种（人人认得出）
  circulationByRegion : Map<String,Set<CurrencyId>> // 区 → 当地实际流通的币（本区成员格 ∪ 直接邻接区里家户实际持有）

装配：of(FxRoundInput, circulationByRegion)
  逐窗口 OfficialRate：中价 mid = (buyPerMille + sellPerMille)/2（整数下取整）
      1000 毫 base = mid 毫 quote ⇒ 建双向边；从根做一次"币种 id 升序"的确定序搜索，首次访问即定值
  当地流通：circulationByRegion(topology, rowsByHex, householdMoney) —— 逐格取余额 > 0 的币种，再按区并上直接邻接区

取价（唯一公式）：
  valueMicro(numeraire, currency)          // 两边都有报价 ⇒ max(1, V(currency)×1000/V(numeraire))
  valuationMicro(numeraire, currency, reg) // 本币 1:1 / 有报价按报价 / 当地流通 ⇒ 面值 1000 / 否则 0（说不出价）
```

- **无摩擦参数**（R4）：只用中价，不用买卖价差。
- **有界**（R6 算力护栏）：认得出的币 = 有报价 ∪ 当地流通；**不新增任何逐币槽位** ⇒ 文档 §3.1 担心的
  "每格槽数 = 币种总数、全图槽数 × C"在结构上不可能发生（槽数只与商品数有关，与币种数无关）。

### 2.2 `HouseholdValuationBook`：加"钱的价格"这一维（文档 §3.1 的目标形状）

- 新字段 `currencyValueMicro : Map<HouseholdId, Map<CurrencyId, Long>>`（微 numeraire / 毫币；**本币对自己 = 1000 = 面值 1:1**）。
- 键集**有界**且保序冻结：本币 ∪ 本户实际持有的币 ∪ 世界有报价的币（**不是**世界全部币种）。
- `derive(households, snapshots, tradeHistory)` 保留（委托 `CurrencyValuation.none()`），新增 4 参重载做实际装配。
- 保留价算式抽成 `static reservationMicro(market, commodity, household, row, movableStock, history)` ——
  **唯一拼写点**：`derive` 与市场卖方槽位（`SellSlot.reservationMicro`）都调它（旧实现只有 `derive` 一处，
  市场侧要用就必须先把它抽出来）。

### 2.3 `MarketSettlement`：唯一拼写点 + 三条腿接线

```
settlementUnitPrice(ctx,buy,sell,quantity,unitPrice,leg,bookRefusal)   // :3971 起
  leg = goods-credit                      ⇒ 返原价（借实物不经货币：债务单位 = 商品）
  buy.currency == sell.market.numeraire() ⇒ 返原价（本币 1:1 ⇒ 判据恒成立 ⇒ 逐值退回改前：E3/N2）
  否则 v = currencyValuation.valuationMicro(卖方本币, buy.currency, 卖方区)
       v <= 0 ⇒ 不划算（说不出这种钱的价）⇒ 买卖两侧 PRICE_LIMIT + DEBUG why
       买方币单价 = ⌈卖方币单价 × 1000 ÷ v⌉                       // 估值越低 ⇒ 要的该币越多（E2）
       判据复核：payment(买方币) × v × 1000 ≥ 保留价(微) × 数量      // 文档 §3.1 逐字形状
       不成立 ⇒ 不划算（同上，走市场性理由）
```
- 三条腿调用点：`pairUp :3963`（配对前的纸面预判，跳过这一家卖方）、`executeTrade :4340`（现金腿落账）、
  `moneyCreditForBuy :2057`（货币信用腿）、`goodsCreditForBuy :2156`（借实物腿，币种维为空）。
  **全仓 `src/main` 里只有这一处做币种/估值比较**（N3）。
- `pairUp`/`matchRoute` 的**可负担量与限价**改用"买方币单价"（否则异币成交会算出买方付不起的货款）；
  **运费不折算**（E6：运费口径一字不动）。
- `SellSlot.reservationMicro` **建槽位时从计划快照冻结**，随副本一起走 ⇒ worker 的区内副本与协调器回放
  （`replayRegionOutcome` 会复算 `executeTrade`）给出同一个答案；顺带把 `SellSlotState` 补上 `blocked`
  回放（拒因也是槽位状态，不带回来读数会把"说不出这个价"误报成 `NO_BUYER/OUTCOMPETED`）。
- `MatchContext.currencyValuation`：**一份实例贯穿本轮**（协调器与各区 worker 副本共用），
  因为"哪些钱在当地流通"依赖本轮**全部**家户的货币账户，而区副本只带走本区家户 ⇒ 各自现算会漂开。
- 日志（§一.9）：`MARKET_FOREIGN_CURRENCY_FILL`（**INFO**：两户/两格/两币/估值/卖买单价/成交比价/量/货款）、
  `MARKET_CURRENCY_VALUE_REFUSED`（**DEBUG**：为什么——说不出价 / 折回本币不够保留价）、
  `MARKET_CURRENCY_VALUATION`（**DEBUG**：本轮行情 + 逐区流通集合）、`MARKET_CROSS_REGION_ROUTE`（**DEBUG**：跨区路线量）。
  旧事件 `MARKET_CURRENCY_MISMATCH_REJECTED` 随语义一并删除（INFO 噪声 −75,564 条/60 天）。

## 3. 关键判断（为什么这样拆 / 否掉了哪条路线）

1. **否掉"只做闸门、不折算价"**（判据照抄、成交价仍按面值 1:1）：那样异币成交价与估值无关，E2"成交价按估值"只能用
   "要价门槛"间接表达。⇒ 选"**折算价 + 判据复核**"，E2 直接用真实成交的买币量证明（§6.2）。
2. **否掉"按区/按 GOV 各取一份汇率"**：会造出多份行情，直接违反 R3/R4。⇒ 只取 `FxRoundInput`（区级覆盖已生效）
   装配的**世界级**行情，且用中价（买卖价差 = 摩擦，R4 禁用）。
3. **否掉"没有报价 ⇒ 不认（拒）"**：三区世界创世零汇率 ⇒ 异币成交会全部失败，直接违反 R1/E1。
   ⇒ "**当地实际流通 ⇒ 面值 1:1**"（R3 的"没做口岸 ⇒ 事实同一个市场区 ⇒ 自然统一汇率"的下界）。
4. **否掉"给买方按持有币种铺多币种买槽"**：那正是文档 §3.1 算力护栏点名的形态（每格槽数 = 币种总数）；
   而且 R1 说的是"**收**"（卖方侧），R2 明说不管家户怎么**花**外币。
5. **否掉"保留价在撮合中现算"**：worker 副本与协调器的库存副本在中途已经不同 ⇒ 回放会具名抛。
   ⇒ 建槽位时冻结。
6. **同币路径做成"结构性短路"而不是"实测碰巧一样"**：`buy.currency == sell.market.numeraire()` ⇒ 不查表、
   不折算、不记日志，判据恒成立 ⇒ E3/N2 是结构保证（§6.3 用 19 格读数 md5 佐证）。

## 4. 偏离记录（与文档 §3.1 不一致处及原因，主动记）

| # | 偏离 | 原因 |
|---|---|---|
| D1 | `SellSlot.receiveCurrency` 仍是 `market.numeraire()`（非 null），只是语义降级为"本格默认收款币"；**"三不管格无默认"没有落成 null** | `MarketSettlement` 看不见区表（`EconomyData.marketZones` 不在本轮入参里）；且该维在本实现里**没有行为内容**——接受与否一律由估值判据决定（R1 是普适的），置 null 不改任何数值。R6 的"当地实际存在的任意币"落在 §2.1 的 `circulationByRegion` 上（E5 节有实跑证据） |
| D2 | `HouseholdValuationBook.currencyValueMicro` 在本批**没有生产读者** | 结算侧直接读 `CurrencyValuation`（同一份行情、同一个 `valuationMicro` 公式），不在每轮再物化一份逐户表（确定性与成本：物化要走 `derive` 的 O(户×商品) 路径）。该维是文档 §3.1 要求的形状，天然读者是 A 批"家户比价"（`HouseholdPriceTable` 的用武之地） |
| D3 | `goods-credit`（借实物）腿**不再看币种** | 该腿不铸钱腿、债务单位 = 商品（`DebtUnit.Commodity`，`MarketSettlement.java:2273`），"按钱的价判划算"对它没有对象；A2b 那条拒因（"堵一条漏一条"背景下加的）因此退役。三条腿仍走**同一拼写点**（由它统一回答"这条腿要不要看币种"） |
| D4 | 判据在"买卖两侧同一份世界行情"下**恒成立**（折算已含估值）⇒ 它是**防御性**校验；N1 的可观测形态是"估值低 ⇒ 换出来的买币价高 ⇒ 买方预算/限价不过"，归因 `no_budget`/`price_limit` | 文档 N1 的字面是"估值不足以覆盖保留价"；由于折算与判据用同一个 v，该字面形态只在两侧估值表不一致时出现。如实记：本批把 N1 证成"**估值不足 ⇒ 不成交 + 市场性理由**"（§6.5），机制与之同因（估值） |
| D5 | 新增 3 条 **DEBUG** 诊断事件（`MARKET_CURRENCY_VALUATION` / `MARKET_CROSS_REGION_ROUTE` / `MARKET_CURRENCY_VALUE_REFUSED`） | §一.9 要求"为什么"可查；DEBUG 默认关闭，零生产噪声。`MARKET_CROSS_REGION_ROUTE` 是逐路线粒度（本批诊断所需），如需更粗可按轮聚合 |
| D6 | 顺带修了两处**过期文案**（不是新机制）：`EconomySetMarketNumeraireHandler` 的 note/类注（原文写"异币成交将具名拒 currency_mismatch"）、`MarketUnfilledReason.CURRENCY_MISMATCH` 与 `SellSlot.blocked` 的注 | E 批删掉了硬拒语义，留着旧话就是"文档造假"；常量**保留**（旧 revision 读数与 `parse` 仍要认它） |

## 5. 改动清单（文件所有权内，未碰 `src/test/**`、`pom.xml`、`docs/**`）

```
M simos-economy-api/.../api/market/MarketUnfilledReason.java      +5/-2   （仅注：标注该档已退役，常量保留）
M simos-economy/.../spi/EconomySetMarketNumeraireHandler.java     +7/-3   （仅日志 note 与类注：异币不再是拒因）
M simos-economy/.../time/HouseholdValuationBook.java            +148/-25 （currencyValueMicro 维 + 保留价算式抽出 + derive 重载）
M simos-economy/.../time/MarketSettlement.java                  +480/-126（唯一拼写点、三条腿、SellSlot 保留价、行情接线、日志）
A simos-economy/.../time/CurrencyValuation.java                   354 行（新：世界行情 + 当地流通 + 估值裁决）
```

## 6. 探针输出（真跑；`/tmp/e-probe/`，不进仓库、不进 `src/test`）

装置（照 D3 的既有形制）：`tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am` 出 15 个模块的 `target/classes`；
`java -cp "<fresh classes>:<shaded jar>" io.mosire.simos.app.ShellMain --store … --world=<worldId> --gui-port 64xx/65xx`
（**fresh classes 在前，遮住 jar 里的旧字节**；探针全部打在 HTTP 边界之后：`/api/state`、`/api/advance`、
`/api/command`、`/api/economy/hex`；**真 `Shell.start` + 真创世**，无任何 `core.register`）。
"改动前"= `git worktree add /tmp/e-before 1bf41015` + 同一命令编译（旧码 = 控制方口径的基线字节）。

### 6.1 E1：持铜家户从（按银报价/收银的）卖方买粮（此前必拒）

现场制造器 = **真 GM 命令** `economy.SetMarketNumeraire{0,-3 → silver}`（该命令的类注自述就是为 A2a 的
"买持铜 / 卖只收银"BLOCKED-1 造的），铜区城市格此后按银报价收银；买方是持铜家户。

```
COMMAND economy.SetMarketNumeraire rev=3 {"result":"committed",...revision 4}
19:23:08.376 INFO economy.market - event=MARKET_FOREIGN_CURRENCY_FILL day=10 commodity=grain
  buyer=HOUSEHOLD:hh--3_0-rural-landless_laborer-displaced seller=HOUSEHOLD:hh-0_-3-urban-landlord
  buyerHex=-3_0 sellerHex=0_-3 buyerPays=copper sellerOwn=silver valueMicroPerMilli=1000
  sellerUnitPriceMilli=1 buyerUnitPriceMilli=1 impliedPerMille=1000 quantityMilli=2301 paymentMilli=3
```
- 交换前后 `day 10..14` 共 **67 笔**异币成交（旧码在同一世界同一窗口是"必拒"：见 §6.6 的 75,564 条）。
- ★ 这一窗口 `quotedCurrencies=0`（世界还没有任何官方汇率）⇒ 放行的依据正是 **R6 的"当地实际存在 ⇒ 收"**：
  `MARKET_CURRENCY_VALUATION day=10 quotedCurrencies=0 circulation={c-tp-copper=[copper,gold,silver], c-tp-gold=[...], c-tp-silver=[...]}`

### 6.2 E2：成交价按估值（估值越低要的铜越多）

真 GM 命令 `economy.SetOfficialRate{marketZoneId:c-tp-copper, base:copper, quote:silver, buy:400, sell:600}`
⇒ 中价 500‰ ⇒ 1 毫铜 = 0.5 毫银 ⇒ 卖方眼里铜的估值 = 500。

```
COMMAND economy.SetOfficialRate rev=5 {"result":"committed",...revision 6}
19:23:17.649 INFO economy.market - event=MARKET_FOREIGN_CURRENCY_FILL day=15 commodity=grain
  buyer=HOUSEHOLD:hh--3_0-rural-landlord seller=HOUSEHOLD:hh-0_-3-urban-landlord
  buyerHex=-3_0 sellerHex=0_-3 buyerPays=copper sellerOwn=silver valueMicroPerMilli=500
  sellerUnitPriceMilli=1 buyerUnitPriceMilli=2 impliedPerMille=2000 quantityMilli=2373 paymentMilli=5
```
| 窗口 | 世界行情 | 买方付的铜单价 | 成交比价 | 该窗口异币成交 |
|---|---|---|---|---|
| day 10–14 | 零报价 ⇒ 面值 | 1 | 1000‰ | 67 笔 |
| day 15–20 | 中价 500‰ | **2** | **2000‰** | 86 笔 |
| day 21–26 | 中价 100‰ | **10** | 10000‰ | 1 笔（预算不足，见 N1） |

⇒ "估值越低、要的该币越多"以**真实成交的买币单价**（1→2→10）成立。

### 6.3 E3 / N2：同币交易逐值不变（单币旧世界逐值不变）

`small-world`（单币、无区表）0→30→60 天，旧码与新码各起一个真实实例，**逐格**取 `/api/economy/hex`（19 格：

`(-2,0)…(2,0)`）后整体 md5：

```
NEW digest: 4b92fa3ca1577a61f38aa26e3e07d969
OLD digest: 4b92fa3ca1577a61f38aa26e3e07d969
IDENTICAL: True
⇒ 19 格经济读数（人口/劳动/库存/货币/市场价表/债务/读数块）逐字节相同（含逐日市场汇总里的 fills=…/reasons=…）
```
结构性原因：同币路径在 `settlementUnitPrice` 里**第一件事就返回原价**（不查行情、不折算、不记日志）。

### 6.4 E4：同一时点、任意两币比价在各地一致

- day 10（面值行情）：**15 个不同买方格**（`-3_0 / -2_0 / -1_-1 / 0_-2 / 1_-2 / 2_-3 / 3_-3 …`）买同一卖方的粮，
  `buyerPays=copper sellerOwn=silver`，全部 `impliedPerMille=1000`。
- day 15（中价 500‰）：≥12 个不同买方格，全部 `impliedPerMille=2000`。
- ⇒ "世界只有一种行情"：同一币对在同一时点的成交比价在各地**逐值一致**（构造上由世界行情表保证，探针在真实成交上复核）。

### 6.5 N1：估值不足 ⇒ 不成交 + 归因是市场性理由（不是"币种不符"）

把铜的估值压到中价 100‰（同一笔粮要 10 倍铜）：

```
COMMAND economy.SetOfficialRate{…buy:90,sell:110} rev=7 → committed
day 21..26 异币成交 = 1 笔（day 25：valueMicroPerMilli=100 buyerUnitPriceMilli=10 —— 仍然"要的铜更多"）
市场汇总：reasons={NO_SELLER=78, NO_BUDGET=7, LOGISTICS_CAPACITY=13, NO_BUYER=89, UNSOLD_SELF_USABLE=81, NO_LENDABLE_GOODS=115}
全日志 currency_mismatch / MARKET_CURRENCY_MISMATCH 命中 = 1（且那一处是 GM 命令自己的说明文本，本批已改掉）
```
⇒ 估值不足时大量需求不成交，归因落在 **`no_budget`**（市场性理由），`currency_mismatch` **零产生**。

### 6.6 E6：跨区贸易保留（ETA / 运力 / 运费不变）+ 一个如实发现的更早约束

- 代码面：本批**没有**任何 hunk 触及路线/ETA/运力/运费算式（`RouteContext`、`freightUnitMilli`、
  `MarketTopology.freightPerMilleBetween`、`CarrierPool`、`travelTicks`、`lossPerMille` 全未改）；
  改的只有"限价/可负担量用**买方币**单价"与"承运选择之前的那道路径"。
- 运行面：跨区路线照旧被逐条尝试（新 DEBUG 证据 `MARKET_CROSS_REGION_ROUTE`：`demand=397440 supply=225989 …`），
  与旧码被拒的是**同一条路线、同一批量**（旧码拒绝样本 `sellerHex=-3_1 buyerHex=-3_0 day=3 quantity=11000`）。
- ★★ **如实发现（不是本批引入）**：`three-powers` 的**隔区**货腿在旧码里被"异币硬拒"掩盖——拒发生在**承运选择之前**；
  去掉硬拒后暴露出更早的约束：创世商号 tier = **PORTER**（服务半径 2），"服务 lane"要求商号 home 距**两端**都 ≤ 半径
  ⇒ 隔区 lane 没有承运商 ⇒ `allocated=0` ⇒ `LOGISTICS_CAPACITY`（实测：新码 60 天里跨区路线 `used=0`、
  异币成交 0；同区跨格 lane 照常成交）。**本批不改它**（属物流参数/口岸面，R4 明令不预设），记为 BLOCKED-相邻发现。

### 6.7 性能对照（§12 纪律：分段推进，不单步）

`advance{from,to}` 一次推进 **30 天**，各起干净世界（`three-powers` / `small-world`），墙钟含日志写入：

| 世界 | 运行 | 0→30 | 30→60 | ms/天（段1 / 段2） | 日志体积（60 天） |
|---|---|---|---|---|---|
| three-powers | **旧码** #1 / #2 | 2003 / 2046 ms | 1337 / 1313 ms | 66.8 / 68.2 · 44.6 / 43.8 | 25.7 MB（含 75,564 条硬拒 INFO） |
| three-powers | **新码** #1 / #2 | 1755 / 1838 ms | 1164 / 1208 ms | 58.5 / 61.3 · 38.8 / 40.3 | **0.59 MB** |
| small-world | 旧码 / 新码 | 1211 / 1205 ms | 789 / 759 ms | 40.4 / 40.2 · 26.3 / 25.3 | ≈0.44 MB 两边同量级 |
| three-powers | 旧码硬拒噪声 | — | — | — | `MARKET_CURRENCY_MISMATCH_REJECTED` = **75,564 条**（新码 **0** 条） |

★ 诚实边界：本批新增的逐轮工作量是 O(户)（流通集合 + 逐户估值键集）与逐笔 O(1)（估值查表 + 折算）；
但上表的"旧→新"差值**主要是旧码 25.7 MB 日志 I/O 的消失**，不是纯算法对照（两次运行都开 INFO）。

### 6.8 N3：不许新增第二套币种比较

```
$ grep -n "settlementUnitPrice(" MarketSettlement.java | grep -v "private static long"
2057:          settlementUnitPrice(ctx, buy, sell, buy.remaining, price, LEG_MONEY_CREDIT, true);   // 货币信用腿
2156:      settlementUnitPrice(ctx, buy, sell, quantity, 0L, LEG_GOODS_CREDIT, true);              // 借实物腿
3963:        long buyerUnitPrice = settlementUnitPrice(ctx, buy, sell, quantity, price, LEG_CASH, true); // 现金腿（配对预判）
4340:        settlementUnitPrice(ctx, buy, sell, quantity, unitPrice, LEG_CASH, true);               // 现金腿（落账）
$ grep -rn "CURRENCY_MISMATCH" --include=*.java simos-*/src/main | grep -v economy-api
→ 5 处，全部是注释/文档（无一处产生该档）
$ grep -rn "MARKET_CURRENCY_MISMATCH_REJECTED" --include=*.java simos-*/src/main | wc -l → 1（仅本批的"删掉了什么"注）
```

## 7. 未完成 / 未验证 / BLOCKED（如实记）

1. **未跑 `test`/`verify`/`package`**（任务书禁止）：本批只跑 `tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am`（exit=0，多轮）。
   既有测试是否因**行为**变化而红**未验证**；静态扫描：`src/test` 对 `CURRENCY_MISMATCH`/`rejectCurrencyMismatch`/
   `MARKET_CURRENCY`/`receiveCurrency` 命中 **0**、对 `CurrencyValuation`/`HouseholdValuationBook` 命中 **0**、
   `new MarketRound(` 命中 **0** ⇒ 按名引用不会失效。
2. **未跑 `spotless:apply/check`**（任务书只允许那一条 Maven 命令）：代码按 google-java-format 手写（2 空格缩进、
   100 列），但**格式门禁未验**——关账前请控制方跑一次 `spotless:apply` 再复核 diff。
3. **E5（三不管格任意币都能成交）未能在真实世界里造出"三不管格"**：世界注册表里的 4 个世界，`three-powers` 的区表
   **覆盖全图**，其余世界单币；GM 命令面只有 `ReassignZoneHexes`（只能换区，且禁止把源区搬空）与 `MergeMarketZones`
   （只能合并）⇒ 没有"把某格移出区表"的写口。**替代证据（同一机制）**：day 10 的 67 笔异币成交发生在
   `quotedCurrencies=0` 的世界里 ⇒ 放行依据就是 R6 的"**当地实际存在的币种集合**"（`circulationByRegion`）；
   如实记 D1：本实现里"无默认收款币"没有行为内容。
4. **E6 的隔区货腿**：见 §6.6 —— 结构性受"商号服务半径 2"限制（旧码被硬拒掩盖），本批不改，记为 BLOCKED-相邻发现。
   本批给的是同区跨格（有运费/损耗）的真实异币成交 + 跨区路线量诊断 + 代码面"未触碰"清单。
5. **汇率多跳链是整数下取整**（`V(c)` 为 long），未做有理数精算；同一币对**多份冲突报价**取"确定序搜索首次访问"那一条
   （A 批的冲突 INFO 未做，文档 §4.4 属 A 批）。
6. **N1 的"估值不足以覆盖保留价"**：见 D4 —— 本批以"估值低 ⇒ 买币价高 ⇒ 预算/限价不过（`no_budget`）"证成，
   判据本身实现在唯一拼写点里（防御性）。
7. `HouseholdValuationBook.currencyValueMicro` 无生产读者（D2）⇒ A 批"家户比价"接上后才闭环。

## 8. 探针留痕（文件在 /tmp，一次性，不进仓库）

```
/tmp/e-probe/{drive.py,env.sh}                 驱动（真实 HTTP 边界）
/tmp/e-before/                                 git worktree @ 1bf41015（旧码编译产物，仅作对照）
/tmp/e-probe/oldA.log   pfOld2.log             旧码 three-powers 0→60（75,564 条硬拒；性能对照）
/tmp/e-probe/newA.log   pfNew2.log             新码 three-powers 0→60（0 条；性能对照）
/tmp/e-probe/e1.log                            新码 + SetMarketNumeraire + 两档 SetOfficialRate（E1/E2/E4/N1 全部证据）
/tmp/e-probe/swNew.log  swOld.log              small-world 旧/新 0→60（N2/E3 对照）
/tmp/e-probe/{newDbg,dbg2,dbg3,dbg4}.log       DEBUG 诊断轮（MARKET_CURRENCY_VALUATION / 跨区路线 / 拒因）
```
