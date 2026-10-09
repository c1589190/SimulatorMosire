# M-A2 实现架构账本：运力提供者自报价 + 需求汇总 + 按最低价购买 + "运力过来"深度 1 固定附加费

> 责任区：M-A2。权威设计书 = `docs/superpowers/specs/2026-10-09-commodity-freight-and-merchant-ladder-design.md`
> **§15（K-A..K-8）**、`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md` **§2.4 M0b/M0d、§2.6、§3、§5、§6.2 V-25/V-26、§7 Q-24..Q-28**；
> 前置批 = `.superpowers/sdd/2026-10-10-m-a1-capacity-pool/impl-ledger.md`（运力池/议价权/派生算式已在位）。
> 本文件是实现方自己写的**实现架构账本**（AGENTS §一.8），只记决策相关事实。

## 1. 关键调查结论（`file:line` 证据 → 结论 → 影响）

| # | 证据 | 结论 | 影响 |
|---|---|---|---|
| T-1 | `MerchantCapacityPool.java:284-345`（旧 `select(from,to,quantity)`）、`MarketSettlement.java:5918-5919`（唯一调用点）、`:6351-6354`（逐条 `carrierCostPerMille(choice.tier())`） | M-A1 的运力"价格"是公式价（派生 tier 成本），选择键是**议价权占比**，消耗单位是**毫商品** | 三条都要动：价格 → 各户限价；序 → 价升序；消耗 → 数量×距离 |
| T-2 | `MarketSettlement.java:1255-1311`（`clearOncePerCycle` 4 参入口传 `MerchantCapacityPool.empty()`）、`EconomySettlement.java:1803-1808`（生产路径的 4 参 `of`） | 池有两个装配口：夹具/纯状态（空池）与生产（有跑商家户） | 缺省口径挂在**调用方传的报价表**上 ⇒ 夹具逐值不变（I-C2 结构性成立，不靠"算出来恰好相等"） |
| T-3 | `FxSettlement.java:38-56`（民间簿：家户自报价、**逐轮瞬态、不存跨轮状态**）、`FxRoundInput.java:22-27`（逐轮瞬态入参） | 本仓已有"自报价 + 逐轮瞬态"的先例，且用户裁定的"价格优先、同价按既有 canonical 序、不另设特权队列"就是 P-T5 的原话 | 运力单**不新增持久状态**（铁律 5 不动，也不与 H-E"运力不可储存/转卖"冲突） |
| T-4 | `MarketSettlement.java:3591-3600`（池非空 ⇒ 区内撮合改单线程）、`:3914`（worker 回放恒传 `route == null`）、`:3747`（worker 副本持 `empty()` 池） | **所有** `route != null` 的成交都只在协调器路径上发生 | 需求簿可以是 `MatchContext` 的普通可变字段，无跨线程写（与 `taxItems`/`fills` 同纪律） |
| T-5 | `TransportTariff.java:56-79`（探针出厂值 基础 5‰ + 每 hex 5‰、辐射 20‰/hex、道路 −50‰/级）、`MarketTopology.java:767-780` | 路线费率的量级是 **几‰ ~ 几十‰**（3 hex 的 lane ≈ 60‰） | "距离因子" = `1000 + rate` ⇒ 1.06× 量级 ⇒ 与 M-A1 的 1 毫商品=1 运力单位同量级（见 §3 的 J-2 实测推翻） |
| T-6 | `MarketSettlement.java:1495`（轮末 `carrierPool.logRoundSummary`）、`:4336`/`:5126`（候选生成处拦"格无运力"）、`:6243-6244`（V-20 截断量） | 轮末已有 INFO 汇总点；"运力不足 ⇒ 不成交/不计价"已由 M-A1 落实（收缩成交量 + 具名 `LOGISTICS_CAPACITY`），截断量已有读数 | 本批只需**加**需求侧汇总与价格口径，不必新造"不成交"路径 |
| T-7 | `EconomySettlement.java:1801-1808`（池装配位置）、`MarketSettlement.java:4778`/`:4992`（两处 `plannedCarrierCostPerMille`）、`:6364`（`nominalCap = freightOf(…, route.freightPerUnit)`） | 计划单位运费 = 名义上限的来源；它同时被可负担性预判（`affordableQuantity`）使用 | 报价口径下计划值必须取**实际最高限价**，否则限价高于 BOSS 档的部分会被名义上限吃掉（fail-closed 方向仍是"买方不多付"） |

## 2. 实现架构（组件拆分 / 数据流 / 调用次序）

### 2.1 新类型（`simos-economy/src/main/java/io/mosire/simos/economy/time/`）

**`CapacityQuote`（常量 + 算式，唯一拼写点）**

```
GET_READY_SURCHARGE_PER_MILLE = 25           // 具名固定"上门"附加费（深度 1，只加一次；= 既有承运成本的一档步长）
selfQuotedPerMilleOf(tier)     = MarketSettlement.carrierCostPerMille(tier)   // 成本维 × 规模维（tier 由运力派生）
defaultQuotedPerMilleOf(tier)  = selfQuotedPerMilleOf(tier)                   // 具名缺省（= M-A1 同源 ⇒ 缺省中性）
withGetReadyPerMille(quoted)   = quoted + GET_READY_SURCHARGE_PER_MILLE       // "上门"腿的唯一拼写点
```

**`CapacityQuoteBook`（逐轮瞬态报价表；不进 `EconomyData`/变更集/落盘）**

```
empty()                  ⇒ 无报价：限价 = defaultQuotedPerMilleOf(tier)（**不含**上门费）⇒ 缺省口径
selfQuoted()             ⇒ 生产路径：跑商家户逐户自报价（= selfQuoted(Map.of())）
selfQuoted(postedPerMille) ⇒ 逐户运力单（未挂单者取具名缺省）；值域 ≥ 0（负 ⇒ 具名抛）
isPriced() / postedCount() / hasPosted(h) / askPerMilleOf(MerchantCapacity)
   askPerMilleOf = 无报价 ? 缺省限价 : (挂了单 ? 它的限价 : 缺省限价) + 上门附加费
```

**`CapacityDemand`（运力需求口径，唯一拼写点）**

```
workPerGoodPerMille(基础费, 路线费率‰) = max(1, 基础费 × (1000 + 路线费率))     // ‰；数量 × 距离（见 §3 J-2）
workMilliOf(数量, 耗用‰)      = ⌈数量 × 耗用 ÷ 1000⌉                          // 需求量（毫商品·程）
maxGoodsFor(剩余运力, 耗用‰)  = ⌊剩余运力 × 1000 ÷ 耗用⌋                      // 预算还能承接多少商品
workConsumedBy(商品, 耗用‰)   = ⌈商品 × 耗用 ÷ 1000⌉                          // 承接后扣多少（与上一行配对 ⇒ 绝不超发）
GOODS_ONLY_WORK_PER_GOOD_PER_MILLE = 1000                                     // 缺省口径 1:1（M-A1 逐值不变）
```

**`CapacityDemandBook`（一轮一份的需求簿；只协调器写；只读数不改账）**

```
record(家户, 需求格, 区, 商品, 发货格, 请求量, 获服务量, 耗用‰)
  ⇒ 逐需求格 / 逐发货格 / 逐区 / 轮总 四张桶（份数按 (家户,商品) 去重 = "每需求一个商品一份"）
logRoundSummary(day, pool, goodsBlockedByCapacityMilli)
  INFO  CAPACITY_DEMAND_SUPPLY_HEX   逐发货格：需求运力 / 已服务 / 未服务 / 供给预算 / 缺口 / 提供者数 / priced
  INFO  CAPACITY_DEMAND_BUYER_HEX    逐需求格：份数 / 需求量 / 已服务量 / 需求运力 / 已服务运力
  INFO  CAPACITY_DEMAND_REGION       逐区同口径
  INFO  CAPACITY_DEMAND_TOTAL        轮总 + goodsBlockedByCapacityMilli（V-20 既有读数）+ maxAsk + 供给预算
  DEBUG CAPACITY_DEMAND_GAP_WHY      缺口为什么存在（无提供者 / 预算不足或半径外）+ 剩余预算
  TRACE CAPACITY_DEMAND_INSTANCE     逐份需求
```

### 2.2 改动的既有类型

**`MerchantCapacityPool`**

- `of(4 参)` = `of(5 参, CapacityQuoteBook.empty())`（旧签名逐字保留 ⇒ 夹具/纯状态读者不变）；
- `of(5 参)`：条目新增 `askPerMille`；池内序 = **限价升序 → 占比‰ 降序 → 家户 id 升序**（报价口径）/ **占比‰ 降序 → 家户 id 升序**（缺省口径，逐字等于 M-A1）；记 `maxAsk`；
- `Entry`：`remainingMilli` → `remainingWorkMilli`（预算，报价口径 = 毫商品·程 / 缺省口径 = 毫商品）、新增 `allocatedGoodsMilli`/`allocatedWorkMilli`；
- `select(3 参)` = `select(4 参, GOODS_ONLY…)`（旧签名保留）；`select(4 参)`：`maxGoods = ⌊预算×1000÷耗用⌋`、`consumed = ⌈take×耗用÷1000⌉`（缺省口径耗用=1000 ⇒ `maxGoods = 预算`、`consumed = take` ⇒ 与 M-A1 逐值同）；
- 新读数：`isPriced()` / `maxAskPerMille()` / `totalCapacityMilli()` / `householdCountAt(hex)` / `remainingCapacityAt(hex)` / `workPerGoodPerMilleOf(base, rate)`；
- `CarrierChoice` 加 `askPerMille` + `consumedWorkMilli`；`CarrierAllocation` 加 `priced` 标志；
- 日志：`MERCHANT_CAPACITY_HOUSEHOLD`（DEBUG）加 `askPerMille`/`posted`/`priced`；`MERCHANT_CAPACITY_LANE_TRUNCATED`（DEBUG）加耗用系数/工作量/半径外/亚单位残余/priced；`MERCHANT_CAPACITY_POOL_HEX`、`MERCHANT_CAPACITY_POOL`（INFO）加 `allocatedWorkMilli`/`askMin|MaxPerMille`/`priced`/`maxAskPerMille`。

**`MarketSettlement`**

- `plannedCarrierCostPerMille`：池空 ⇒ 25‰（不变）；缺省口径 ⇒ BOSS×25（不变）；报价口径 ⇒ **`maxAskPerMille()`**；
- `executeTrade` 的跨格分支：先算 `workPerGoodPerMille = pool.workPerGoodPerMilleOf(base, rate)`，用它 `select`，并把每一份需求（含全被拦下的那份）记进 `ctx.capacityDemands`；
- `carrierChargeSplit`：单位运费改用 `choice.askPerMille()`（缺省口径逐值同 M-A1）；**报价口径且名义上限未被顶到** ⇒ 逐条按各自限价收（Σ == `effectiveFreightSum` == `collectible`）；否则走 M-A1 的按量比例分摊（缺省口径恒走这一支/两分支等价，且报价口径被顶到时也走它）；
- 轮末新增 `ctx.capacityDemands.logRoundSummary(day, pool, goodsBlockedByCapacity(ctx))`；`MatchContext` 新增 `capacityDemands` 字段；
- `nominalFreight` 局部变量在本批之前就已声明未用（`git show HEAD:…MarketSettlement.java` 同形）——**未动**，如实记。

**`EconomySettlement`**：池装配改用 `CapacityQuoteBook.selfQuoted()`；新增 DEBUG `CAPACITY_QUOTE_BOOK`（带 `day`）。

### 2.3 一 tick 内的次序（本批落点）

```
① 生产/欠租（不动）
② 报价表 + 运力池现算：CapacityQuoteBook.selfQuoted() → MerchantCapacityPool.of(5 参)
   逐户 ask = 自报价(成本 × 规模) + 0（不启用的缺省）/ + 上门费（启用）；DEBUG 报价簿
③ 市场装配 → 区内/跨区撮合（池非空 ⇒ 协调器单线程；不动）
   3a 候选生成处：发货格无运力 ⇒ 具名 LOGISTICS_CAPACITY（M-A1，不动）
   3b 逐笔跨格：workPerGood = pool.workPerGoodPerMilleOf(base, rate) → select（**限价升序买**）
       → executed = min(请求, 分到)；不足 ⇒ 收缩 + V-20 截断（**不成交、不成债、不计价**）
       → 需求簿记一份（请求量 / 获服务量 / 耗用）
   3c 运费：逐条按 choice.askPerMille() 计价；报价口径未被名义上限顶到 ⇒ 逐条按自己限价收
④ 轮末：MERCHANT_CAPACITY_POOL*（INFO）+ CAPACITY_DEMAND_*（INFO/DEBUG）+ TRACE 逐笔（不变）
⑤ 信用轮 / FX 轮（不动；D-030/D-031 唯一上限 = 放贷池）
```

## 3. 关键判断（为什么这样拆 / 为什么不走另一条路线 / 推翻过什么）

- **J-1 报价"逐轮瞬态"而不是新增持久组件**：T-3 的先例（FX 民间簿）+ H-E（运力不可储存转卖）+ 本批"不新增持久状态"的边界。代价具名：本批**没有**"某户在某一轮挂了/撤了运力单"的状态面；"未挂单"= 调用方未提供报价表（夹具/纯状态读者）或报价表里没有该户（`selfQuoted(Map)` 的逐户覆写口，留给后续命令面/政策面）。若控制方要"生产世界里逐户可挂可撤"，那需要新增运力单簿 + 命令面 = **另一批**（会触碰铁律 5 的 Codec/ChangeSet/往返不变式）。
- **J-2 ★ 需求量纲：先按"钱的运费口径"落，实测后推翻，改成"基础费 × 距离因子"**（本批最重要的一次自我推翻）：
  ① 初版 `workPerGoodPerMille = freightUnitMilli(base, rate, 承运成本=0)`（即复用整条钱算式，只删承运成本项）。**推演数字**：粮/纤维 基础费 = 1、3 hex 的 lane 费率 ≈ 60‰ ⇒ 该值 = `max(1, ⌈1×1060×1000÷1e6⌉)` = **2‰** ⇒ 一份 1,000 毫商品的运输只吃 **2** 运力，658,000 毫商品/轮的池能承运 **3.29 亿毫商品** ⇒ **运力不再是硬约束**，"供给不足 ⇒ 不成交"（K-4/Q-27）在真档里几乎永不触发 = 把 M-A1 的运力约束静默删掉。
  ② 改成 `max(1, 基础费 × (1000 + 路线费率))`（既有运费算式的**物理维**：基础费 × 距离因子；去掉钱维 = 承运成本因子与 ÷1e6 的钱换算/取整）⇒ 粮 3 hex ≈ **1060‰**（1.06×）、布 ≈ 2.1×、工具 ≈ 3.2× ⇒ **重货/远路吃更多运力**，且与 M-A1 的"1 毫商品 = 1 运力单位"同量级 ⇒ 池仍是硬约束。
  ③ 为什么算"沿用既有运费口径"：用的是同一张 `commodityFreightBaseMilli` 状态表（含具名缺省分档）与同一个 `MarketTopology.freightPerMilleBetween`（距离/辐射/道路），单位是千分比（"毫商品·程 / 毫商品"），**没新造量纲**。K-5 括号里的"承运成本"是**钱的腿**的量（谁报价），进 `freightUnitMilli` 的第三项，不进物理需求量。
- **J-3 缺省中性靠"报价表空"结构性成立**：`empty()` 那一支不加上门费、分配序用 M-A1 的原式、`select` 的耗用系数 = 1000（1:1，恒等式）⇒ "无报价 ⇒ 逐值不变"是**几条恒等式**，不是"算出来恰好相等"。夹具世界（`MarketSettlementFixtures` 的 4 参入口 + 空池）根本走不到新分支。
- **J-4 "价格优先、同价按既有 canonical 序"**：Q-25 = 价格管选择、议价权管稀缺分配。实现为 `(ask↑, share↓, householdId↑)`；不新增"特权队列"（P-T5 同一句话）。★ 后果如实记：所有提供者同为 PORTER 档时（小城镇常态）限价相同 ⇒ 序退回议价权序 = 与 M-A1 同序；限价只在不同规模档之间分档（25/50/100 + 25）。
- **J-5 报价口径下"逐条按自己的限价收"只在名义上限未被顶到时启用**：Σ 恒 == `collectible` ⇒ I-C1 守恒式不变；被顶到时退回 M-A1 的按量比例分摊（买方永不多付）。缺省口径**不进**这一支（`allocation.priced()` 门），这样"无报价 ⇒ 逐值不变"不受取整边界影响。
- **J-6 需求汇总不做"先囤总量再分发"**：运力不可储存/转卖（H-E）⇒ 汇总只能是**供需读数与缺口归因**（逐发货格：需求 vs 预算 vs 缺口），买卖仍逐 lane 现买现用。K-A 的"每需求一个商品一份"落成 `(家户, 商品)` 去重计数；K-B 的"汇总"落成逐需求格/逐发货格/逐区/轮总四张桶。
- **J-7 报价是"自报价"而不是全市场一个公式价**（K-6）：限价逐户取，来自该户的**成本（派生承运成本算式）× 规模（tier 由它本轮运力派生）**；`CapacityQuoteBook.selfQuoted(Map)` 的逐户覆写口把"自报价"这件事做成可被外部挂单的数据形状（本批暂无生产调用方）。
- **J-8 深度 1 + 固定附加费**（Q-24）：上门腿 = `CapacityQuote.GET_READY_SURCHARGE_PER_MILLE = 25`（具名常量，与既有承运成本一档步长同值），**只加一次**、不随距离/批次变化、不再递归。★ 数值可见性如实记：因为它对所有提供者**同加一个常数**，不改变限价排序；在毫钱取整（`max(1, ⌈…⌉)`）下多数 lane 的**实收金额**也不变（例：粮 3 hex：25‰ 档 1.0865→ ceil 2；50‰ 档 1.113 → ceil 2）。它的可见作用有两条：① 把"上门"这一腿在算式里具名计入；② 抬高 `plannedCarrierCostPerMille`（实际最高限价）⇒ 名义上限与可负担性预判随之变化（买方预算略保守）。
- **J-9 需求簿只在协调器写**（T-4）：唯一写入点是 `executeTrade` 的跨格分支（`route != null`），它只在"池非空 ⇒ 串行撮合"路径上到达；worker 本地副本上的累加交回时丢弃（与 `taxItems`/`fills` 同纪律）。候选生成处的"格无运力"拦下**不**记需求（那个量是撮合前的买卖余量之和，记了会与逐笔请求重复计数）——它的货物侧由 `goodsBlockedByCapacityMilli`（V-20 读数）覆盖。

## 4. 偏离记录（与约束设计书/派单书不一致之处，主动记录）

| # | 偏离 | 原因 |
|---|---|---|
| D-1 | **"提供者自报价"落成"生产路径逐户自报价（逐轮瞬态）"，没有"逐户挂/撤单"的状态面** | 派单书冻结"不新增持久状态"路线 + FX 民间簿先例（T-3）；逐户覆写口 `selfQuoted(Map)` 已留。若要真挂单簿 ⇒ 另一批（触铁律 5） |
| D-2 | **汇总（K-B）是读数/缺口，不是配额表** | H-E"运力不可储存、不可转卖"⇒ 不能先囤后分；买卖逐 lane（`select`） |
| D-3 | **"未运走的部分不成债"读作"该笔承运不成交/不计价/不铸运费债"** | 既有信用轮（D-030/D-031）对"未满足的货物需求"的放贷是新契约明写保留的语义（freeze 5）；本批不新造第二套债务机制（V-26），也不改既有 `unmetToday` 口径 |
| D-4 | **`CapacityDemand` 的口径取"基础费 × 距离因子"，不是字面的"基础费 × 费率 × 承运成本"** | 后者的数值是**钱**（毫钱/商品单位）⇒ 运力池不再是硬约束（J-2 的实测推演）；K-5 的"单位 = 毫商品·程"只有物理维说得通 |
| D-5 | **报价口径下 `plannedCarrierCostPerMille` 取"实际最高限价"** | 它是名义上限与可负担性预判的共同来源（T-7）；不取它，含上门费的限价会被名义上限截断（静默失真） |

## 5. 实施记录

### 5.1 改动文件（相对 `2488b3e1`）

| 类别 | 文件 | 处置 |
|---|---|---|
| 新增 | `time/CapacityQuote.java`（78 行） | 自报价算式 + 具名缺省 + `GET_READY_SURCHARGE_PER_MILLE`（深度 1） |
| 新增 | `time/CapacityQuoteBook.java`（120 行） | 逐轮瞬态报价表：`empty()`（缺省口径）/ `selfQuoted()`（生产）/ `selfQuoted(Map)`（逐户运力单） |
| 新增 | `time/CapacityDemand.java`（111 行） | 运力需求口径（数量 × 距离）+ 四个换算（单一拼写点） |
| 新增 | `time/CapacityDemandBook.java`（约 310 行） | 每需求一份 + 逐格/逐区/轮总汇总 + 缺口 + INFO/DEBUG/TRACE |
| 改 | `time/MerchantCapacityPool.java`（+308/-67） | 报价、价格升序、工作量口径消耗、读数、日志 |
| 改 | `time/MarketSettlement.java`（+116） | 计划上限、`select` 传耗用、运费逐条按限价、需求记账、轮末汇总、`MatchContext` 字段 |
| 改 | `time/EconomySettlement.java`（+7/-1） | 生产路径装配自报价表 + DEBUG 报价簿 |

### 5.2 命令与结果（两条，本仓锁；只到编译过 —— 派单纪律）

```
$ tools/mvn-lock.sh -q spotless:apply                → exit=0
$ tools/mvn-lock.sh -DskipTests compile              → exit=0（16 模块全 SUCCESS，9s）
```

### 5.3 会改变数值行为的清单（含缺省中性论证）

| # | 变化 | 缺省中性（无报价：`CapacityQuoteBook.empty()`） |
|---|---|---|
| 1 | 运力消耗单位：毫商品 → **毫商品·程**（`数量 × 基础费 × (1000+费率) ÷ 1000`，报价口径） | 耗用系数 = 1000 ⇒ `maxGoods = 预算`、`consumed = take`（恒等式）⇒ 逐值同 M-A1 |
| 2 | 承运选择序：**限价升序**（PORTER 50‰ < SELF_EMPLOYED 75‰ < BOSS 125‰）→ 同价按议价权 | 序原式（占比降序 → 家户 id 升序）⇒ 逐值同 M-A1 |
| 3 | 单位运费：承运成本项换成该户限价（含 25‰ 上门） | 缺省限价 == `carrierCostPerMille(tier)` ⇒ 逐值同 M-A1 |
| 4 | 运费分摊：报价口径且名义上限未顶到时**逐条按各自限价收**（不再按量比例分摊） | `allocation.priced()==false` ⇒ 恒走 M-A1 分支 |
| 5 | `plannedCarrierCostPerMille` = 实际最高限价（影响 `route.freightPerUnit`、名义上限、可负担量预判） | 池空 ⇒ 25‰；池非空且无报价 ⇒ BOSS×25 ⇒ 逐值同 M-A1 |
| 6 | 新增日志（`CAPACITY_QUOTE_BOOK`/`CAPACITY_DEMAND_*`/`MERCHANT_CAPACITY_*` 新字段） | 池空且无需求 ⇒ 需求簿**不打任何行**（`byBuyerHex` 空 + 池空 ⇒ 直接 return） |
| 7 | 无 | 任何**状态**形状（`EconomyData`/`ActorData`/`SocialData`）—— 本批零持久状态改动；零新命令/GM 工具；零测试改动 |

### 5.4 报价与附加费的具名常量（一行）

```
CapacityQuote.GET_READY_SURCHARGE_PER_MILLE = 25‰（上门腿，深度 1，只加一次）；
自报价 = MarketSettlement.carrierCostPerMille(tier) = districtUse(PORTER 1/SELF_EMPLOYED 2/BOSS 4) ×
MARKET_FREIGHT_CARRIER_COST_PER_MILLE_PER_TIER_STEP(25)；具名缺省 = 同式；耗用系数 = max(1, 基础费 × (1000+费率‰))。
```

### 5.5 会让既有测试失效的清单（**静态核对，未跑** —— 派单纪律只到编译）

| 类别 | 文件 | 原因 |
|---|---|---|
| 编译：无 | —— | 全仓 `src/test` 对 `MerchantCapacity*`/`CarrierChoice`/`CarrierAllocation` 的引用 = **0 命中**（`grep` 实测）；旧签名 `of(4 参)`/`select(3 参)` 逐字保留 ⇒ 无编译破坏 |
| 行为：无（夹具世界无跑商家户） | `MarketSettlementFixtures` 及其使用方（`MarketRegulationTest`/`HexTradeCostTest`/`MarketTopologySingleRegionTest`/`MarketSettlementSingleHexLossTest`/`Z7MarketExclusionTest`） | 4 参入口传 `MerchantCapacityPool.empty()` ⇒ 池空 ⇒ 跨格不成交（M-A1 已如此）+ 本批新分支不可达 |
| 行为：可能变（有跑商家户 + 跨格成交的世界） | `CompactThreeNationsWorld`（app）、`ProductionRuntimeSeedSmokeTest`（app）、`HomeModeMigrationProbeTest`/`ProbeEconomy`（economy，含 MERCHANT 字面） | 若这些世界真的跑到跨格撮合：运力消耗按数量×距离 ⇒ 成交/运费/在途量变；`ProductionRuntimeSeedSmokeTest` 只读 `economyPayload()` 不断言运力 ⇒ **大概率不受影响**（未实测） |
| 不受影响（核对过） | `MarketSettlementSingleHexLossTest`、`TransportTariffP4Test`、`MarketDemandBookTest`、`EconomyRoundTripTest`（M-A1 已因 `merchantFirms` 退役变红，本批不新增） | 同 hex 成交 `route == null` ⇒ 不走运力；费率/派单公式未改；本批零持久状态 ⇒ 往返不变式维度不变 |

★ 测试侧口径（留给测试 Agent）：T1 报价值（改 `MerchantCapacityPool.of` 的第 5 参：`selfQuoted(Map.of(h → 0))` 应让该户排序最前且单位运费最低）；T2 限价升序（两个不同 tier 的提供者 ⇒ 低价者先被吃）；T3 需求汇总（跨格成交后 `CAPACITY_DEMAND_TOTAL` 的份数 = 去重 (家户,商品) 数）；T4 缺口（把某格预算压低 ⇒ `unservedWorkMilli > 0` 且买槽 `LOGISTICS_CAPACITY`）；T5 缺省中性（空报价表 vs M-A1 逐值对照）；T6 深度 1（`ask = 自报价 + 25`，**只加一次**、与 lane 距离无关）；T7 I7（两跑逐值；1/4/8 线程：池非空恒串行）。

## 6. 未完成 / 未验证（如实记）

- **未跑任何测试/长跑**（派单纪律：只到编译过）⇒ 报价序、缺口、需求汇总的真实数值与日志形态**均未实测**；§5.5 的"行为：可能变"一栏是**静态推断**，不是实测结论。
- **未做**（按冻结范围）：tool 门槛与一次性扣减、纯商号免运费 / 顺便跑商独立计运费分流与利润算式（M-C）；主业副业排序（M-D）；币种挂单过滤（P-T1e）；政府采购优先级（P-T1d）；卖家赊购（Q-26 待裁）。
- **未提供**：运力单的挂/撤状态面与命令面（J-1/D-1）；`selfQuoted(Map)` 的逐户覆写口在本批**没有生产调用方**（只有 `selfQuoted()` 这条自报价路径被 `EconomySettlement` 调用）。
- **未修**（如实记，不扩大改动）：`MarketSettlement.executeTrade` 的 `nominalFreight` 局部变量在本批之前就声明未用；`MerchantCapacityPool` 装配期 DEBUG `MERCHANT_CAPACITY_HOUSEHOLD` 不带 `day`（M-A1 遗留；本批新增的装配期汇总 `CAPACITY_QUOTE_BOOK` 已挪到 `EconomySettlement` 并带 `day`）。
- **待控制方裁定**：D-1（报价是否要状态面）与 D-2（汇总是否要成为配额表）两条是**解释性口径**，本批按冻结书最保守的读法落地；若控制方要更强的形态，属另一批。
