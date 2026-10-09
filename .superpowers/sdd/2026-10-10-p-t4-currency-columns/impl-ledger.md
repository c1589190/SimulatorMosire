# P-T4「读口 / 会计的币种列」实现架构账本

- 责任区：`simos-economy/src/main/**`、`simos-app/src/main/**`（未改 `simos-economy-api`）
- 约束设计书：`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md`
  §2.5（读口与会计）、§3 **I-C10**、§5.2 **N5**、§6.2 **V-13/V-18**；
  缺陷调查报告 `docs/superpowers/reports/2026-10-10-port-merchant-consistency-check.md` §18
- 纪律：只写生产代码、只到编译过；不写/不改测试；不跑 `test`/`verify`；不 `git commit`（AGENTS §一.5/§一.10）

## 1. 关键调查结论（file:line → 结论 → 影响）

| 证据 | 结论 | 影响 |
|---|---|---|
| `MarketReport.java:385-399`（改前）`Fill` 四栏钱只有量纲注、无币种 | `unitPriceMilli` = **卖方格计价币**（`MarketSettlement:4245 unitPriceOf` 取卖方格参考价、`:5342 ctx.referencePriceOf(sell.regionId, sell.market, …)`）；`goodsPaymentMilli`/`freightMilli` = **买方支付币**（钱腿 `:5039-5045`、`:5056-5064` 都 `Map.of(buy.currency, …)` 铸） | 异币成交时两栏是两种钱，读口无法回答"哪一币" |
| `MarketReadout.java:407`（改前）`unitPriceMilli + freightPerUnitMilli` | **卖方币 + 买方币** 相加 | 到货价读数在异币成交上无定义 |
| `MarketSettlement.java:5900`（改前）`bestAcceptedLandedPrice` 同一行加法 | 同族缺陷（任务书未点名） | `SellerOutcome.bestAcceptedLandedPriceMilli` 同错 |
| `MarketReport` 的 `freightPaidMilli` / `freightUncollectedMilli`（改前 record 组件） | 记录级跨币求和（`MarketSettlement:5072/5074` 逐笔 += ） | 报告级运费读数混币 |
| `MarketReport.regulatedTariffMilli()`（改前） | 逐票税费（**卖方格计价币**）跨区相加 | 税读数的"什么币"答不出（§2.5 税读数） |
| `ApiViews.java:2662-2676`（改前） | 世界/格成交额 `+= fill.goodsPaymentMilli()` 跨币求和 | money 产出参考口径混币 |
| `OperatorSettlement.java:147`（改前）`evidence[3] += fill.goodsPaymentMilli()` → `OperatorCondition.cycleRevenueMilli`（`OperatorCondition.java:64`，**持久**） | 落盘字段混币；`OperatorSettlement:404-406` 的 `lastCycleNet = 混币收入 − 单一本币成本` 也是拿两种钱相减 | 状态机净额读数在多币世界无意义 |
| `EnterpriseProfitBook.addRevenue:730-747` | **先例**：只认本币，其他币具名 DEBUG 排除 | 本批的"具名排除"形态照它 |
| `EconomySettlement.traceTotalMoney`（改前，TRACE/DEBUG 只读） | 家户×币种两层表跨币求和成一个 `long` | 日志读数混币（§2.6 第 11 步"读口/日志：全部带币种"） |
| `MarketSettlement:4268` 既有注 + `settlementUnitPrice:4652` | E 批已把"单价格"的口径钉在 `sell.market.numeraire()`（同币 ⇒ 原价；异币 ⇒ 按估值折算） | 单价币列直接复用这**同一个**拼写点，不新造假设 |
| `CurrencyId`（`simos-economy-api`）+ `EconomyCodec:224/278` 键反序列化器 + `FlowRow.repaidMoney` | 持久形状里已有 `Map<CurrencyId, Long>` 先例 | 新增同类字段不需要新契约类型、不需要改 codec |
| 探针（Jackson 2.22.2，`new ObjectMapper()` 裸 mapper） | 单 String 组件的 record 作 Map 键：写 `{"silver":7}`、读回 `{silver=7}` | Core 的第四台 mapper（`Timeline:115 CHANGESET_MAPPER`）**不需要**补键反序列化器也能读回新形状 |

## 2. 实现架构（改了哪些形状、数据怎么走）

### 2.1 `MarketReport.Fill`：钱栏各带币种列 + 唯一的加法拼写点

```
quantity
CurrencyId unitCurrency,   long unitPriceMilli        // 卖方格计价币（sell.market.numeraire()）
CurrencyId paymentCurrency, long goodsPaymentMilli    // 买方支付币（buy.currency）
long freightPerUnitMilli,  long freightMilli          // 币 = paymentCurrency（见 freightCurrency()）
+ CurrencyId freightCurrency()      // 具名派生列 = paymentCurrency（运费腿铸在买方支付币上）
+ OptionalLong landedUnitPriceMilli()  // 唯一的"单价 + 单位运费"；异币 ⇒ empty（调用方具名排除）
```

- 三个落点全部改（`MarketSettlement:2808/5104/5160`：现金腿、货币信用腿、跨区在途腿），值一行未动。
- `MarketReadout.matchReadout` 与 `MarketSettlement.bestAcceptedLandedPrice` 都改走
  `landedUnitPriceMilli()` ⇒ 加法只剩一处拼写。

### 2.2 `MarketReport`：记录级金额读数按币分列

- `freightPaidMilli` → `Map<CurrencyId, Long> freightPaidByCurrency`（键 = 该笔买方支付币）。
- `freightUncollectedMilli` → `Map<CurrencyId, Long> freightUncollectedByCurrency`（同上；0 不落键）。
- `regulatedTariffMilli()` → `Map<CurrencyId, Long> regulatedTariffByCurrency()`（按 `Fill.unitCurrency()` 分组；
  税费币种**不另开列** —— 费率出自卖方所在区调控，币 = 卖方格计价币）。
- 构造期防御性拷贝 `copyMoney`：`LinkedHashMap` + `Collections.unmodifiableMap`，拒 null 键/值/负额（I7）。

### 2.3 `MatchContext`（市场轮瞬态）

- `long freightPaidMilli/freightUncollectedMilli` → 两张 `LinkedHashMap<CurrencyId, Long>`，在
  `executeTrade` 铸腿处 `merge(buy.currency, …)`。worker 副本的这两张表照旧丢弃（协调器回放时在真 ctx 上重算）。

### 2.4 `MarketReadout.CommodityMatchReadout`

- `OptionalLong landedPriceMilli` → `Map<CurrencyId, Long> landedPriceByCurrency`（逐币
  `Σ 数量 × (单价 + 单位运费) ÷ Σ 数量`，floor；空表 = 没有成交）。
- `long freightMilli` → `Map<CurrencyId, Long> freightByCurrency`。
- 新增 `long landedPriceExcludedFills`：**单价币 ≠ 运费币**的成交笔数（具名排除，不静默消失）。

### 2.5 `OperatorCondition`（持久形状，直接改，不写兼容层）

```
long lastCycleRevenueMilli  → Map<CurrencyId, Long> lastCycleRevenueByCurrency
long cycleRevenueMilli      → Map<CurrencyId, Long> cycleRevenueByCurrency
+ long cycleRevenueMilliOf(CurrencyId) / lastCycleRevenueMilliOf(CurrencyId)   // "按币问"的唯一算式
+ plusCycleEvidence(..., Map<CurrencyId, Long> revenueByCurrency, ...)          // 逐币 Math.addExact；0 不落键
```

- 构造期守卫：两表非 null、无 null 键/值、无负额；拷贝 = `LinkedHashMap` + `unmodifiableMap`（I7 + SpotBugs）。
- `withLastReason` 逐字带过（只换理由）。
- 旧档：`cycleRevenueMilli` 从数字变对象 ⇒ 旧 JSON 读不回。按 **AGENTS §一.11** 处理：直接改、不写归一/兼容位；
  代价是旧 store 需重新播种（控制方/测试方按"直接重建"处置）。

### 2.6 `OperatorSettlement`

- 证据累加：`long[6]` → `EvidenceAccumulator`（五个 long + `LinkedHashMap<CurrencyId, Long> revenueByCurrency`）；
  逐笔货款按 `fill.paymentCurrency()` 分列累加，**零额不落键**（故 `observed` 判据从 `revenue > 0` 换成
  `!revenueByCurrency.isEmpty()`，两者同真同假）。
- 关账：收入**按币结转**；净额 = `该币收入 − 成本`，该币 = 本币（`bookCurrency`）；说不出本币 ⇒
  `canonicalCurrencyOf(逐币收入)` 兜底（规范串最小的币，照 `EnterpriseProfitBook.bookCurrenciesOf` 的先例），
  并记一条 `OPERATOR_FOREIGN_REVENUE_EXCLUDED` DEBUG（带 `bookCurrencyUsed` / `bookCurrencySource`）。
- 关账后 `cycleRevenueByCurrency = Map.of()`（与旧 `0L` 同义：本周期证据清零）。

### 2.7 读口/日志（`simos-economy` + `simos-app`）

- `EconomySettlement`：`MARKET_FILL` TRACE 逐笔加 `unitCurrency/paymentCurrency/goodsPaymentMilli/freightCurrency`；
  `MARKET` INFO 与 `MARKET_REPORT` DEBUG 的 `regulatedTariffMilli` → `regulatedTariffByCurrency`，并补
  `freightPaidByCurrency` / `freightUncollectedByCurrency`；`traceTotalMoney` → `traceMoneyByCurrency`
  （`REPAY` / `REPAY_DETAIL` / `DAY_END` 三行按币分列），门槛用 `anyPositiveMoney`（与旧"标量和 > 0"同真同假）。
- `ApiViews`：`hexTurnoverMilli`/`worldTurnoverMilli` → `hexTurnoverByCurrency`/`worldTurnoverByCurrency`
  （`sortedCurrencies`，币种 id 升序 = 内容的纯函数）；`lastCycleRevenueMilli`/`cycleRevenueMilli` →
  `lastCycleRevenueByCurrency`/`cycleRevenueByCurrency`；`lastCycleNetMilli` 保留（本币口径，注释写明）。
- `MerchantSettlement` 只改 javadoc 里对已改名读数口的引用（无行为）。

## 3. 关键判断（为什么这样，不那样）

1. **Fill 用 2 个显式币种列 + 1 个具名派生列**，不是 3 个真值列：运费腿的唯一铸点就是 `buy.currency`
   （`MarketSettlement:5063`），再存一个"运费币"就是同一事实的第二处拼写。派生访问器 `freightCurrency()`
   仍然让读口能直接回答"运费是哪一币"。
2. **异币成交在到货价上"具名排除"，不折算也不相加**：世界没有汇率（I17）；折算会造出第二套口径，
   相加则是把两种钱当一种。排除的笔数落成 `landedPriceExcludedFills`（看得见），不静默消失。
3. **持久字段选"按币分列"而不是"具名排除外币"**：§2.5 两种都允许；按币分列不丢信息（V-13/M1 还要把商户利润扩到多币种，
   届时不必再改一次形状）。代价 = 旧档不兼容（§一.11 已裁定不写兼容）。
4. **净额必须单一币种**：`lastCycleCost` 出自 `ProducerCostBook`（单一计价口径），收入是多币 ⇒ 只能拿一种钱减。
   选本币（与同一循环里 `cash` 的本币口径、A2b 的"逐币问"一致）；说不出本币时用
   `EnterpriseProfitBook` 的规范序兜底 —— 这一条是**为 I-C2"单币世界逐值不变"服务的**：夹具/旧世界若没有本地市场，
   兜底仍挑到那唯一的币，净额与改前逐字相同（若这里 fail-closed 记 0，净额会从"收入"变成"−成本"）。
5. **0 不落键**：三处（信用证据累加、`plusCycleEvidence`、未收运费）都用"0 不落键"，才能同时满足
   "没有这种钱 ≠ 这种钱动了 0" 与 "旧 `> 0` 判据逐值不变"。
6. **加法收口**：`Fill.landedUnitPriceMilli()` 是全仓唯一允许出现"单价 + 单位运费"的地方；
   两个调用方（读口、卖方最优到货价）都改走它。
7. **确定性（I7）**：所有逐币表 = `LinkedHashMap` + `Collections.unmodifiableMap`；**零** `Map.copyOf`。
   表序 = 逐笔发生序（报告本身是 canonical 序的纯函数）；进 JSON 的键按币种 id 升序排（内容的纯函数）。
8. **`Map<CurrencyId, Long>` 不需要新契约类型**：`economy-api` 的 `CurrencyId` 已在，codec 键反序列化器已在，
   `FlowRow.repaidMoney` 是同形先例；并用探针确认 Core 的第四台 mapper 裸 `ObjectMapper` 也能写/读这种键。

## 4. 偏离记录（与约束设计书不一致处及原因）

| # | 偏离 | 原因 |
|---|---|---|
| D-1 | 任务书写"每条成交要能回答单价币/实付币/运费币"，实现是 **2 列 + 1 派生列** | 判断 1：运费币恒等于实付币，写第三个真值列 = 第二处拼写 |
| D-2 | 计划 §2.5 的**税读数**还要求"进哪个国库" | 本批只做币种列（本批税**只记读数、不搬钱**、收款方未定）；国库归属 = P-T1 的活。已在 `regulatedTariffByCurrency()` javadoc 具名 |
| D-3 | 任务书点名 4 处 + 税读数；实际多修 4 处**同族**（`MarketSettlement:5900`、报告级两个运费读数、`regulatedTariffMilli`、`traceTotalMoney`） | I-C10/N5 是"任何金额求和读数按币分列"；不改这 4 处，N5 的负向守卫在多币世界仍会红 |
| D-4 | `MarketReport` 的 A2a"旧形状兼容构造器"（16 参、`fx = none()`）**保留** | 它是**源码便利重载**（不是落盘形状归一/兼容位，不在 §一.11 管的三件里）；删它只会多破既有夹具，收益为零 |
| D-5 | `Fill.unitCurrency` 取 `sell.market.numeraire()` | 与 E 批 `settlementUnitPrice:4652` 的既有假设**逐字同源**（"单价格"就按卖方市场币解释）。多币区（同区成员格不同币）是 `matchRoute` 单币假设的一部分，3c 放宽时要复核（见 §7） |

## 5. 命令与结果（真实命令、真实输出）

```
$ tools/mvn-lock.sh -q spotless:apply
[spotless exit=0]                       # 无输出（-q）

$ rm -rf simos-economy/target/classes simos-app/target/classes
$ tools/mvn-lock.sh -DskipTests compile
[INFO] Compiling 184 source files with javac [debug release 21] to target/classes   # EconomySimos
[INFO] Compiling 327 source files with javac [debug release 21] to target/classes   # SimosApp
[INFO] You have 0 Checkstyle violations.   # ×16 模块
[INFO] BUILD SUCCESS
[INFO] Total time:  8.004 s
[compile exit=0]
```

- 未跑 `test` / `verify` / `package`（纪律）；**未**跑 `test-compile`（下面 §8 的第 5 条因此是静态清点，不是实测）。
- 交付时工作树改动文件（`git status --porcelain`）：
  `simos-economy/src/main/java/io/mosire/simos/economy/model/OperatorCondition.java`、
  `simos-economy/.../time/{MarketReport,MarketSettlement,MarketReadout,OperatorSettlement,EconomySettlement,EconomyEntrySettlement,MerchantSettlement}.java`、
  `simos-app/.../gui/ApiViews.java`、本账本。
  ★ `docs/superpowers/status/2026-10-10-resume-points-and-backlog.md` 在**开工前**就是 ` M`（mtime 04:29:52，
  早于本批第一笔改动 04:31），**不是本批改动**，我没有碰 `docs/**`。

## 6. 会改变数值行为的清单（逐条：哪个读数变了、怎么变）

★ 口径：**单币世界逐值不变**；下列"变了"全部发生在**多币**（异币成交/多区不同币）世界。

| # | 读数/字段 | 改前 | 改后 |
|---|---|---|---|
| V1 | `OperatorCondition.lastCycleNetMilli` | 混币收入 − 成本 | **本币**收入 − 成本（说不出本币 ⇒ 规范串最小的币兜底） |
| V2 | `OperatorCondition.lastCycleRevenueByCurrency`（旧 `lastCycleRevenueMilli`） | 一个 long = Σ全部币 | 逐币表（值不变，形状变；单币 ⇒ 一项同值） |
| V3 | `OperatorCondition.cycleRevenueByCurrency`（旧 `cycleRevenueMilli`） | 一个 long = Σ全部币 | 逐币表（同上） |
| V4 | `MarketReport.regulatedTariffByCurrency`（旧 `regulatedTariffMilli`） | 跨区 Σ | 逐币 Σ（单币 ⇒ 一项同值） |
| V5 | `MarketReport.freightPaidByCurrency` / `freightUncollectedByCurrency` | 跨币 Σ | 逐币 Σ（单币 ⇒ 一项同值；未收 0 不落键） |
| V6 | `CommodityMatchReadout.landedPriceByCurrency`（旧 `landedPriceMilli`） | 跨币加权 | 逐币加权；异币成交被排除（`landedPriceExcludedFills` 计数） |
| V7 | `CommodityMatchReadout.freightByCurrency`（旧 `freightMilli`） | 跨币 Σ | 逐币 Σ |
| V8 | `SellerOutcome.bestAcceptedLandedPriceMilli`（内部 → TRACE 日志） | 可能把异币成交的"单价 + 运费"算进最低价 | 异币成交不参与（单币世界不变） |
| V9 | `ApiViews` 世界/格成交额 JSON | `worldTurnoverMilli`/`hexTurnoverMilli` 标量 | `worldTurnoverByCurrency`/`hexTurnoverByCurrency` 逐币对象（单币 ⇒ 一项同值，**键名变了**） |
| V10 | `ApiViews` 经营者状态 JSON | `lastCycleRevenueMilli`/`cycleRevenueMilli` 标量 | `...ByCurrency` 逐币对象（键名变了；`lastCycleNetMilli` 仍在 = 本币口径） |
| V11 | TRACE `MARKET_FILL` | 无币种 | 加 `unitCurrency`/`paymentCurrency`/`goodsPaymentMilli`/`freightCurrency`/`freightPerUnitMilli` |
| V12 | TRACE `REPAY`/`REPAY_DETAIL`/`DAY_END`、INFO `MARKET`、DEBUG `MARKET_REPORT` | `repaidMoneyMilli` / `regulatedTariffMilli` 标量 | `...ByCurrency` 逐币对象（+ 运费两张表）；键名变了 |
| V13 | 新增 DEBUG `OPERATOR_FOREIGN_REVENUE_EXCLUDED` | 无 | 本币口径下被排除的逐币收入（只在 `LOG.isDebugEnabled()` 且有非本币收入/说不出本币时发） |

- **不改**：任何余额、任何转移腿、任何成交价/量、任何阈值常量、任何排序键 → 钱货守恒与单币世界逐值不变。
- 影响到的硬编码字面量：**生产代码零个**（未动任何常量）；受影响的字面量在**测试侧**（下节）。

## 7. 会让既有测试失效的清单（给测试 Agent 的输入；我**没有**改测试）

静态清点（`grep` 全 `src/test`，非实测编译）：

**A. 会编译不过（4 个文件）**

| 文件:行 | 原因 | 需要的改法（测试方定） |
|---|---|---|
| `simos-economy/src/test/java/io/mosire/simos/economy/time/MarketDemandBookTest.java:99` | `new MarketReport(…)` 第 7/8 参 `0L, 0L` → 现为 `Map<CurrencyId, Long>` | 改 `Map.of(), Map.of()` |
| `simos-economy/src/test/java/io/mosire/simos/economy/change/EconomyRoundTripTest.java:726` | `new OperatorCondition(…)` 两处 `0L`（`lastCycleRevenueMilli`/`cycleRevenueMilli`）→ 现为逐币表 | 改 `Map.of()`；★ 该类是铁律 5 的往返不变式测试，新形状必须继续往返 |
| `simos-economy/src/test/java/io/mosire/simos/economy/time/MarketRegulationTest.java:170,171,172,201,309,313` | `freightPaidMilli()` / `freightUncollectedMilli()` / `regulatedTariffMilli()` 三个访问器已改名 | 断言从 `isZero()` 改 `isEmpty()`（单币世界仍只有一项/空表） |
| `simos-economy/src/test/java/io/mosire/simos/economy/time/MarketSettlementSingleHexLossTest.java:180,181` | 同上两个运费访问器 | 同上 |

**B. 编译得过、但语义/断言要复核**

- 任何"读 `Fill` 字段做算式"的测试：`Fill.unitPriceMilli()` / `goodsPaymentMilli()` / `freightMilli()` 访问器仍在，
  但**异币成交下它们不再是同一种钱**；单币夹具不受影响。
- `MarketReport` 逐笔读数的测试（`MarketRegulationTest:232,266,307`、`MarketSettlementSingleHexLossTest:45`）：
  只读 `quantity()`/`fills()` ⇒ 不变。
- 任何断言 API JSON 的用例：全 `src/test` 实测 `grep` 对 `hexTurnoverMilli|worldTurnoverMilli|lastCycleRevenueMilli|cycleRevenueMilli|repaidMoneyMilli|moneyOutputReference` = **0 命中**，
  前端 `webui/**` 对同一批键也 **0 命中** ⇒ 只有 Java 侧那 4 个文件受影响。
- 旧 `OperatorCondition` 落盘 JSON（`cycleRevenueMilli` 是数字）**读不回**：旧夹具/旧 store 需重建（§一.11），
  这不是断言失效而是**档案不兼容**。

## 8. 未完成 / 未验证（如实）

1. **没跑测试**（纪律）：上面第 7 节的清单是 `grep` 静态清点，**不是** `test-compile` 实测；可能还有间接编译错（如泛型推断）。
2. **没跑真实 world 长跑**（AGENTS §七：行为验证走真实 world + 读日志）："单币世界逐值不变"目前是**静态论证**
   （逐笔算式未改 / 表只有一项 / 0 不落键），没有运行时 md5 或读数对照。
3. **多币区（同区成员格不同币）的 `Fill.unitCurrency` 假设**未证（D-5）：它继承 E 批 `settlementUnitPrice` 的
   单币假设；3c 放宽 `matchRoute` 时必须在同一批复核这一列。
4. **税读数的"进哪个国库"没做**（D-2，P-T1 的活）；按商品分层的逐票税费也只到"逐币合计"，
   没有对外暴露"逐票税费"访问器（没有消费者就不加 API 面）。
   ★ 同类边界：`MarketReport.SellerOutcome` 的 `unitPriceMilli`/`unitCostEstimateMilli` **没有**显式币种列 ——
   它们的币 = 卖方格计价币（`sell.market.numeraire()`，与 `bestAcceptedLandedPriceMilli` 同一个），
   而这两个数**不是求和读数**（§2.5 只要求"成交报告"带币种列）；本批不改该形状以免再扩大测试面，
   记在这里备 P-T1/3c 复核。`SellerOutcome.freightPerUnitMilli` 是硬编码 0（`MarketSettlement:5864`）。
5. `MarketReport` 的 A2a 便利构造器保留（D-4）；若控制方判定它是"兼容层"，删除是独立的一小步（会多破一处测试）。
6. 未验证的边界：`OperatorCondition` 新形状在 **Core 第四台 mapper** 上的往返是**探针级**结论（裸 mapper 能读写
   `Map<CurrencyId, Long>`），没有跑真实的 `Timeline.readChangeSet` 路径。
