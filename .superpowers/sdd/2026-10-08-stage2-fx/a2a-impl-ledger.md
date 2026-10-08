# A2a 实现架构账本：FxOrder + 官方/实际汇率 + 政府外汇窗口 + 币种校验

> 责任区：阶段 2 第二批 **A2a**（约束设计书 `docs/superpowers/specs/2026-10-08-currency-exchange-stage2-design.md`
> §3.2/§3.3/§3.4/§3.5、§5 I17–I21、§6.1 F2/F3/F4/F5、§7 M1/M2/M3/M4/M8、§8）。
> 写代理：办事子 Agent（一个责任区一个写代理）。只写生产代码、只 compile、不写/不跑测试、不 `git commit`。
> 交账时间：2026-10-08。**状态：生产代码编译通过（compile exit=0 / test-compile BUILD SUCCESS）+ 探针 98 条断言全过
> （exit=3）；**F5 的端到端演示记为 BLOCKED（不是通过、也不是回归）** ⇒ 见 §6 BLOCKED-1。**

---

## 1. 关键调查结论（`file:line` → 结论 → 影响）

| # | 证据（file:line，开工时实测） | 结论 | 对实现的影响 |
|---|---|---|---|
| 1 | `MarketSettlement.java:5298-5350` `BuySlot.currency` 取自 `market.numeraire()`；`SellSlot:5353-5420` **没有币种字段**；付款腿 `:3732-3748`（货款）/`:3751-3768`（运费）都是 `Map.of(buy.currency, …)` | 成交路径**零币种相等校验** ⇒ 异币**静默 1:1** | `SellSlot` 补 `receiveCurrency`（= 卖方市场格 numeraire）+ `executeTrade` 第一句就比；不等 ⇒ `MarketUnfilledReason.CURRENCY_MISMATCH` |
| 2 | `MarketSettlement.swift:926-1207`（`clearOncePerCycle`）：商品撮合 → `creditRound` → `collectUnfilled` → `adaptPrices`，全部落账经 `EconomySettlement.applyTransfer`（唯一写口） | 外汇要"同一轮、同一落账口、同一份账户表" | FX 段插在 `creditRound` 之后、`collectUnfilled` 之前；两条腿都走 `applyTransfer` |
| 3 | `EconomySettlement.java:1649-1720`：`MarketRound` 由 `base = session.base()` 装出、`withCredit`/`withArbitrage` 注入逐轮瞬态；`MarketRound.copyForWorker` 是"克隆唯一拼写点"（2026-10-08 丢字段事故） | 新入参必须走 `withXxx` + 逐字段带过克隆 | 新增 `MarketRound.withFx` / `fx()`，并在 `withArbitrage`/`withCredit`/`copyForWorker` 三处显式带过 |
| 4 | `EconomySettlement.java:7808-7900` `applyTransfer`：两端必须解析到**已登记家户**，付方余额不足当场抛 | 政府窗口的两条腿必须都落在**家户账**上；国库 actor 必须能解析成 HouseholdId | `FxSettlement` 先用 `round.householdOfActor()` 解析国库；解析不到 ⇒ 窗口整段不参与 + 具名 `window_inactive` |
| 5 | `MarketReport.java:60-104` 是 16 组件 record；:84-92 用**静态 WeakHashMap + 身份键**存"区级税费"派生读数 | "派生读数不进 record"的既有形制；但**WeakHashMap 的键只被 map 弱引用 ⇒ 条目可被 GC 立即回收** | ★ 我第一版照抄了它存外汇读数 ⇒ 探针实测 `fx()` **恒为空**（FX 明明跑了：`FX_ROUND` 日志在、窗口在）⇒ 改成**第 17 个 record 组件**（保留旧 16 参构造器） |
| 6 | `Government.java:32-39`（6 组件）、`EconomyData` 通用 Jackson 绑定、`EconomyChangeSet.governments` 是 `FieldDelta` | 官方汇率挂 GOV = 改 1 个 record + 1 个组件；Codec/ChangeSet **零改动**（泛型绑定 + FieldDelta 自动覆盖） | 第 7 组件 `officialRates: Map<String, OfficialRate>`（键 = `base|quote`），保留 6 参构造器 ⇒ 既有夹具/测试源码零串改 |
| 7 | `EconomyDefineCurrencyHandler.java:143-154`：手抄 6 个字段重建 `Government` | **新增组件后这类"手抄重建"会静默抹掉它**（A1 的教训同族） | 新增 `Government.withIssuable/withOfficialRate/withTreasury`，把两处重建点改成走它们 |
| 8 | `CatalogTool.PAYLOAD_HINTS` 的构造成员守卫：**已注册命令类型未登记载荷提示 ⇒ `Shell.start` 当场抛**（编译期看不见） | 新命令必须四处同源：handler + Shell registry + PAYLOAD_HINTS +（GM/决策人桶 + 白名单） | `economy.SetOfficialRate` 四处全部落地（不再重复 A1 的 BLOCKED） |
| 9 | `EconomySettlement.java:1713` 起：`EconomyDayStepper` 每个 AdvanceTime 从**当前状态**装 session ⇒ `session.base()` 是当刻状态 | 官方汇率（状态）能被市场轮读到 | `FxRoundInput.of(base.governments(), base.moneyIssuances())` |
| 10 | `MoneyIssuanceRecord` 的类注：`累计发行 = Σ kind.issuance().amount` | "储备上限"有一个可读、可解释的来源 | `R_max = 累计发行量 × 500‰`（具名常量，见 §3 判断 3） |
| 11 | `shell.toolsFor(Role)`/`DecisionCallerFactory.WHITELIST`/`MarketReportFeed.last(mapId, tick)`/`MarketReadoutAssembly.contextFor` 都是**生产读口** | 探针可以走生产读数，不必自己装配 | 探针全程用它们（零 `core.register`） |
| 12 | 小型世界 19 格：**markets=19 / industries=44 / classes=178，19 格全是 silver 计价**（探针实测） | 造"两个计价币"的世界**没有现成命令面**：`SetMarketPrice` 保留 numeraire、`Seed` 拒已占用格 | F5 端到端只能靠 `ClearRegion` 腾格 —— 而它今天**坏了**（见 §6） |

---

## 2. 实现架构

### 2.1 契约（`simos-economy-api/.../api/fx/`，8 个新类型）

| 类型 | 形状 | 要点 |
|---|---|---|
| `FxOrder` | `(owner, hex, side, base, quote, limitPrice, quantity)` | `BUY` = 买 base 付 quote；限价 = 愿付上限（SELL = 愿收下限） |
| `FxSide` | `BUY/SELL` | 方向只回答"谁付出 base" |
| `OfficialRate` | `(base, quote, buyPerMille, sellPerMille)` | 持久状态形状；`keyOf()` = `base\|quote`（币种 id 禁含 `\|` ⇒ 双射）；`midPerMille()` |
| `FxFill` | `(day, hex, regionId, base, quote, baseMilli, quoteMilli, pricePerMille, buyer, seller, venue)` | 一笔成交 = 两条腿的**事实**（读数量，不落盘） |
| `FxVenue` | `HOUSEHOLD / GOV_WINDOW` | 分得清"官方拉动"与"市场自定" |
| `FxRejectReason` | 11 个具名档 | `RESERVE_CAP / RESERVE_EXHAUSTED / NO_COUNTERPARTY / INSUFFICIENT_FUNDS / CURRENCY_MISMATCH / NO_CROSS / INVERTED_QUOTE / NO_FX_FILLS / …` |
| `FxMarketRate` | `(base, quote, hasReading, ratePerMille, sampleCount, …, noReadingReason)` | ★ **无读数时 `ratePerMille()` 当场抛**（I18/M4 在类型上的落点，不许回落官方价） |

★ **量纲（一处如实的口径选择）**：`limitPrice` / `buyPerMille` / `sellPerMille` **统一 per-mille**（每 1000 个 base
最小单位应付的 quote 最小单位数；1000 = 最小单位 1:1）。理由：§3.2 写"quote per base（最小单位）"、§3.3 写
`buyPerMille/sellPerMille`，两处字面量纲在**两个币种 scale 不同**的世界里会分叉；统一到 per-mille 后
`bidP = officialBuy` 是**直接赋值**（§3.4 的原话）。A 阶段两币种 scale 都是 3 ⇒ 三种读法数值相同，不改变任何 A 阶段读数。

### 2.2 持久状态：官方汇率挂 GOV（`Government.officialRates`）

```
权威：Government.officialRates : Map<"base|quote", OfficialRate>   ← 第 7 个组件，随 governments 进 ChangeSet/Codec
写口：economy.SetOfficialRate（GmOnlyCommand）→ EconomySetOfficialRateHandler
      └ 只写 governments 一个组件（探针实测：其余 32 个组件逐值不变）
读口：FxRoundInput.of(governments, moneyIssuances) → 市场轮（逐轮瞬态，不进状态）
```
- 构造期守卫：键 == 值内币对（`OfficialRate.keyOf`）；`base != quote`；两个报价 > 0。
- **激活条件（fail-closed）**：某个币对**没有**官方汇率 ⇒ 没有窗口 ⇒ 没有 FX 市场 ⇒ 整段跳过。
  ⇒ A1 的创世**没定过汇率** ⇒ **老世界一个数都不动**（探针 §2 段逐值断言）。

### 2.3 一轮外汇撮合（`FxSettlement`，插在商品撮合 + 信用之后）

```
① 报价：逐窗口（GOV）算 bidP/askP 与两侧容量
   buyCapacity  = min(上限 − 储备, 国库可付 quote ÷ bidP)    ← 触顶 ⇒ 具名 RESERVE_CAP；付不出 ⇒ INSUFFICIENT_FUNDS
   sellCapacity = 储备                                        ← 见底 ⇒ 具名 RESERVE_EXHAUSTED；容量 ≤ 储备 ⇒ 结构上不可能卖空
   相反报价（bid ≥ ask）⇒ 两侧都停 + 具名 INVERTED_QUOTE（挡"政府自己给自己送钱"的抽水机）
② 建簿：**每个有官方汇率的币对一本**（窗口停做不影响市场的存在）；窗口挂单先（政府是这个市场的挂牌方）
③ 家户单两条规则（都以"官方报价"为锚，**与窗口当轮容量无关**）：
   SELL（外币花不出去）：持有 base 且本地法定币**可花额为零** ⇒ 卖全部 base，限价 = bidP × 900‰
   BUY （余钱换外币）  ：本地法定币可花额 − 该户货币保留额 > 0 ⇒ 按 askP 买 base
   ★ 每户每币对至多一条，且两条规则互斥（不可能既"本地钱为零"又"有余钱"）
④ 撮合：价格-时间优先（买单限价降序、卖单限价升序，同价按 canonical 键）——逐个交叉配对、吃深度
   成交量 = min(双方剩余, 买方付得起, 卖方拿得出)；成交价 = 两限价的**中间价**（`FxPricing`，向下取整）
⑤ 落账：每笔 = **两条腿**（base 腿 卖→买 / quote 腿 买→卖），都走 `EconomySettlement.applyTransfer`
⑥ 残量归因：对面无挂单 ⇒ NO_COUNTERPARTY；有挂单但价不交叉 ⇒ NO_CROSS；部分成交吃尽 ⇒ NO_COUNTERPARTY
```

★ `FxSettlement` **不是第二个 applier**：它一个账户 `put` 都不写，动账只有 `applyTransfer` 一处
（探针逐轮 I20：逐币种总量前后**逐值不变**，5 轮各证一次）。

### 2.4 实际汇率（读数，不落盘，I17）

```
FxRoundResult.marketRate(base, quote, windowN, regionId) = 本轮 FX 成交的成交量加权均价（Σ price×qty / Σ qty）
无成交 ⇒ FxMarketRate.hasReading() = false，ratePerMille() 当场抛
```
★ **"最近 N 笔"的边界（如实记）**：A 阶段是**逐轮**读数（窗口 = min(120, 本轮成交数)）；跨轮滚动窗口需要把成交史写进
状态，与 I17"汇率不进状态"冲突 ⇒ 留待 B（或用户裁定是否放宽 I17）。

### 2.5 币种校验（§3.5 / I19 / F5）

```
SellSlot.receiveCurrency = 卖方所在格 Market.numeraire()   ← 新字段（copy 构造器同步）
executeTrade 第一句：buy.currency != sell.receiveCurrency ⇒
   买卖两侧各置 MarketUnfilledReason.CURRENCY_MISMATCH + INFO 日志 + return 0（账户一字未动）
sellerReason()：CURRENCY_MISMATCH 优先级高于市场性归因（不许被 OUTCOMPETED 掩盖）
```
★ 单币世界里两者恒等 ⇒ **逐值不变**（探针 §2/§5 段：旧世界行为不动）。

### 2.6 数据流总览

```
DM/GM 工具 simos.gov.setFxRate ──► economy.SetOfficialRate ──► Government.officialRates（状态/ChangeSet/Codec）
                                                                        │
EconomySettlement 每轮：FxRoundInput.of(governments, moneyIssuances) ────┘
        │  withFx（逐轮瞬态；不进状态）
        ▼
MarketSettlement.clearOncePerCycle ──► FxSettlement.match
        ├─ GovFxWindow.quote（三项约束 + 具名停做）
        ├─ 家户单（两条规则，官方报价为锚）
        ├─ 撮合（中间价、吃深度）
        └─ 两条腿 ──► EconomySettlement.applyTransfer（唯一写口）
        ▼
MarketReport.fx()（第 17 个组件）──► MarketReportFeed ──► GUI/MCP 读口
EconomyLog.fx()（新 logger 分类）── INFO/DEBUG/TRACE 三级
```

### 2.7 日志（§一.9）

| 级别 | 事件 |
|---|---|
| INFO | `OFFICIAL_RATE_SET`（命令）、`OFFICIAL_RATE_REJECTED`、`FX_ROUND`（本轮汇总）、`FX_REJECTED`（逐笔具名拒）、`FX_WINDOW_BUY_BLOCKED` / `FX_WINDOW_SELL_BLOCKED`、`MARKET_CURRENCY_MISMATCH_REJECTED` |
| DEBUG | `FX_ORDER_PLAN`（逐轮挂单数）、`OFFICIAL_RATE_SET_DETAIL`、`MARKET_FX_WINDOWS` |
| TRACE | `FX_FILL`（逐笔成交两条腿的金额与价） |

★ 新 logger 分类 `io.mosire.simos.economy.fx`（`EconomyLog.fx()`）+ 新来源 `EconomyLogSource.ECONOMY_FX`。

---

## 3. 关键判断（为什么这样拆）

1. **官方汇率挂 GOV、不新开组件**：设计书 §3.3 明写"A：挂在 GOV 上"。代价：`Government` 加第 7 组件 + 一处构造期守卫 +
   4 个调用点（其中 2 个改成 `withIssuable`/原样带过）。收益：Codec/ChangeSet **一行不改**（泛型绑定 + FieldDelta），
   且 B 阶段"迁到市场区"时搬的是同一份数据。
2. **激活条件是"有官方汇率"**：没有政策价就没有锚（家户限价无从派生）⇒ fail-closed 不激活。副作用（刻意的）：A1 创世的
   单币世界**逐值不变**（既有测试的既有行为不被本批改动），直到有人真的定了一条汇率 —— 探针 §2/§5 两段都是这条的证据。
3. **储备上限 `R_max` = 该 GOV 对该币种累计发行量 × 500‰**（`GovFxWindow.DEFAULT_RESERVE_CAP_PER_MILLE`）：
   - 绝对数换个世界/换个精度就没意义（本仓"没人写下来的量纲"教训）；挂在发行量上是**与规模无关且可解释**的政策线；
   - 它**必须能被触到**（判据 F3 要求"触顶停买"真的可达）：探针第 9 段把储备补到恰好 50000 = 上限 ⇒ 具名 `reserve_cap` ✓
   - 推翻过的版本：起初想用 `R_max = 累计发行量`（政府不囤超过自己发出去的量）—— 但"国库余额 + 在外流通量 = 发行量"，
     于是余量恒等于在外流通量 ⇒ **上限永远不可能被真正触到**（探针第 5 段第一轮就暴露了：储备 100000 > 上限 100000）。
4. **成交价取"两个限价的中间价"，不取"被动方限价"**：本仓的撮合是**一轮一批**（订单先生成再撮合），"谁先挂进簿"是实现
   细节而不是市场事实 ⇒ 用被动方定价会让价格依赖插入序（一种不可见的口径）。中间价是双方限价的纯函数（I7 确定性），
   且落在个体理性区间内 ⇒ 双方都不亏。
5. **家户规则以"官方报价"为锚、不看窗口当轮容量**：容量是"政府这一轮做不做"，报价是政策（状态）。若让家户规则依赖容量，
   窗口一停（触顶/见底）整个外汇市场就消失 —— 而 §4.5 要的恰恰相反（官方只在窗口有量时拉动市场价）。探针第 10 段
   正是这个形态：窗口两侧全停，家户↔家户仍在 975‰ 成交（≠ 官方 1000/1050）。
6. **家户买盘预算 = 可花本币 − 该户货币保留额**（复用商品面的 `moneyReserveOf` 算式，抽成家户版 `moneyReserveOfHousehold`，
   两处逐值同源）：换外币不能挤占口粮钱。★ 探针实测代价：余钱不足时**根本不挂买盘**（不是 bug，是这条预算在起作用）。
7. **外汇读数进 `MarketReport` 的第 17 个组件（不是侧表）**：见 §1 第 5 条 —— 侧表用 WeakHashMap + 身份键，**键只被 map
   弱引用 ⇒ 条目可被 GC 回收**，探针实测 `fx()` 恒空。这是本批最值钱的一次实测发现（同一形制的"区级税费"读数
   `MarketReport.regulatedTariffMilli()` 有同样的隐患，见 §5 发现 2）。
8. **`FxSettlement` 独立成类、不塞进 5730 行的 `MarketSettlement`**：`MarketRound` 的私有字段对同包不同顶层类不可见 ⇒
   给 `MarketRound` 加 7 个**只读访问器**（`householdGoods/Money/Frozen*/householdOfActor/householdEconomies/ledger`），
   不复制表、不暴露 setter（"FX 与商品必须用同一份账户表"，第二份副本就是两本账）。

---

## 4. 偏离约束设计书之处及原因

| # | 设计书原文 | 实际做法 | 原因 |
|---|---|---|---|
| 1 | §3.2「成交价 = quote per base（定点整数，最小单位）」+ §3.3「`buyPerMille/sellPerMille`」 | 统一 per-mille（`FxOrder.limitPrice` 也是 per-mille） | 两处字面量纲在两个币种 scale 不同时会分叉；统一后 `bidP = officialBuy` 是直接赋值（§3.4 原话）。A 阶段 scale 都是 3 ⇒ 读数不变 |
| 2 | §3.4「报价：`bidP = officialBuy`、`askP = officialSell`」 | 报价照旧；**成交价 = 两限价中间价** | 一轮一批的撮合没有"被动方"这个市场事实（§3 判断 4）；官方报价仍是"窗口那一侧的限价" |
| 3 | §3.4「储备上限 `R_max`」未给口径 | `R_max` = 累计发行量 × 500‰（具名常量、GM 可调） | 设计书没给数；绝对数无量纲意义，而这条**能被触到**（F3 需要） |
| 4 | §3.3「实际汇率 = 本区最近 N 笔 FX 成交的加权均价」 | 逐轮读数：本轮成交的加权均价（N = min(120, 本轮笔数)） | 跨轮滚动窗口 = 把成交史写进状态，与 I17"汇率不进状态"冲突 ⇒ 留 B / 待裁定 |
| 5 | §9-3「官方汇率初始值：建议创世给中性 1:1 基准，DM 可改」 | **创世不设任何官方汇率**（激活靠命令） | §9-3 是**待裁定开放点**；创世就设 ⇒ 所有既有小世界场景当轮开始冒 FX 单（行为漂移）。取 fail-closed 的"未定即无外汇面"，把政策价留给 DM 工具 |
| 6 | §3.5「`SellSlot` 补币种维（卖方法定收款币种）」 | 取**卖方所在格** `Market.numeraire()` | 卖方的法定收款币没有第二个来源；单币世界恒等 ⇒ 逐值不变 |
| 7 | §3.2「每户每轮至多一次」 | 每户每**币对**每轮至多一条挂单（两条规则互斥） | 单币对世界里与"每轮一次"逐字等价；多币对世界里"只准挂一对"会让另外几对被静默饿死 |
| 8 | —— （设计书未提） | 新增 `FxRejectReason.INVERTED_QUOTE` 与"相反报价两侧都停" | `bid ≥ ask` 时窗口两侧自成交 = 无风险抽水机（政府自己给自己送钱）；fail-closed 停做 + 具名 |

---

## 5. 调查中推翻过 / 顺手发现

1. **推翻：外汇读数放"派生侧表"**（照 `MarketReport.REGULATED_TARIFF_BY_REPORT` 的形制）。探针第一轮实测：FX 明明跑了
   （`FX_ROUND` 日志 5 条、窗口读数 5 个），`report.fx()` **恒为空** —— 因为 `WeakHashMap` 的键（`IdentityKey`）只被 map
   弱引用，GC 一来条目就没了。改成第 17 个 record 组件后全绿。
2. **同族隐患（既有代码，未改）**：`MarketReport.regulatedTariffMilli()`（D-027 区级税费读数）用的是同一条形制
   （`WeakHashMap` + 身份键 wrapper）⇒ **同一个丢条目的隐患**。本批不改它（属 D-027 的面），报给控制方。
3. **`EconomyClearRegionHandler` 的悬空引用（既有缺陷；★ 已当场修，2 行）**：清任一行政区都会被 `EconomyData` 构造期守卫拒：
   `商号指名的生产组织不存在（组织表已提供 ⇒ fail-closed）：商号=org-merchant-…`。
   **根因**：`with*` 链里 `withProductionEnterprises(enterprises)` 排在商号之前，而每个 `with*` 都会重跑构造期守卫，
   守卫只判"商号 → 组织"一个方向 ⇒ "组织已摘、商号还在"的中间态当场 fail-closed。
   **修法**（`EconomyClearRegionHandler`，AGENTS §五.3"修复比描述还短就当场修"）：在 `.withProductionEnterprises(...)`
   **之前**插一行 `.withMerchantFirms(merchantFirms)`（先摘商号、后摘组织，两个中间态都合法）。
   **实测**：修前两个行政区都拒；修后 `capital-province` 与 `small-world` 都能提交（探针打印"清区=small-world；腾空格 = 17 格"）。
4. **`ClearRegion` 之后的推进会被跨切片一致性守卫 fail-closed（既有语义，未改）**：清掉一个**有人口**的行政区后，
   Social 侧家户仍在、经济行被删 ⇒ 下一 tick 抛
   `IllegalStateException: 时间预算指向不存在的家户（协调器投影必须与经济行同键）：hh--1_-1-rural-landless_laborer-displaced`
   （`EconomySettlement.applyLaborBudgetsInto:3751`）；政府家户另有一条 `GOV_HOUSEHOLD_WIRING`（可用
   `economy.RegisterHousehold` 回补绕过，探针已实现）。⇒ **`ClearRegion` 只适合"清完再整区重播"的既有语义**，
   不适合"腾一格来改它的计价币"。
4. **小型世界没有"空白格"**：19 格 = 19 张市场表（全 silver）+ 44 个产业 + 178 条阶层行 ⇒ 没有任何"未占用且有市场"的格。
   `economy.Seed` 的"已占用 ⇒ 拒"因此挡死了"改某格计价币"的播种路线；`economy.SetMarketPrice` 保留 numeraire。
   ⇒ 今天**没有**设置逐格计价币的命令面（B2 的市场区持久化会需要它）。
5. **家户买盘会因为"货币保留额"而消失**（探针第 6 段的实测）：把全世界的银（26 万毫）给一户穷户，它仍然一单不挂 ——
   因为它的日自然需求按市价折算后超过了这笔钱。这条不是 bug，但**场景构造必须显式备足口粮**（探针因此给 H_buy 搬了
   "日需求 ×10"的实物）。
6. **窗口买侧会被"国库没钱"挡住**（探针第 8 段实测）：储备在上限之下但国库 quote 为 0 ⇒ `buyBlocked = INSUFFICIENT_FUNDS`
   而不是 `RESERVE_CAP`。这一条**救了一次假红**：第一版把"没成交"读成"触顶判据失败"，实际是场景没给国库留钱。

---

## 6. BLOCKED / 未完成 / 未验证

> 依 AGENTS §一.10：做不到 ⇒ **BLOCKED，不是 DONE**；给"为什么做不到 + 最小额外范围 + 已实现的最强 fail-closed 降级"。

### BLOCKED-1：F5 的**端到端**演示（异币商品成交具名拒）—— 探针实测的精确卡点

- **已落地**（代码层，全部可读）：`SellSlot.receiveCurrency`（= 卖方市场格 `Market.numeraire()`）+
  `executeTrade` **第一句**的币种相等校验 + 双档具名 `MarketUnfilledReason.CURRENCY_MISMATCH` +
  `sellerReason` 优先级 + INFO 日志 `MARKET_CURRENCY_MISMATCH_REJECTED`。
- **探针已经把"两个计价币"的世界造出来了**（全是真命令，`/tmp/a2a-probe/final.log`）：
  `economy.ClearRegion`（★ 修好之后）→ 腾空 17 格 → `economy.Seed` 在 `-2_0` 播**铜计价**粮市、在 `-2_1` 播**银计价**粮市
  → 两个格同属市场区 `c-small-capital`（成员 `[-2_0, -2_1, 0_0]`，探针打印）→ `social.CreateHousehold` +
  `actor.EnsureHouseholdAccount` + `actor.AdjustAccounts` + `economy.RegisterHousehold` + `economy.AddDemand`
  造出"持铜、银为零、有粮需求的买方"（`hh-fx5-buyer`）与"持粮、银计价的卖方"（`hh-fx5-seller`）。
- **卡在哪**：推进那一 tick 被**既有跨切片一致性守卫** fail-closed：
  `IllegalStateException: 时间预算指向不存在的家户（协调器投影必须与经济行同键）：hh--1_-1-rural-landless_laborer-displaced`
  —— 清区删了 164 户的经济行，而 Social 侧家户仍在；另有一条 `GOV_HOUSEHOLD_WIRING`（政府家户缺行 ⇒ 整个推进拒，
  探针已用 `economy.RegisterHousehold` 回补绕过）。
- **为什么这不是本批的实现缺陷**：两条守卫都是既有的、刻意 fail-closed 的跨切片契约；
  `ClearRegion` 的既有语义本就是"整区清空 + 整区重播"（重划流程），不是"腾一格改计价币"。
- **最小解锁面**（二选一，都很小）：
  1. **新增一条 GM 命令 `economy.SetMarketNumeraire`**（`{q,r,numeraire}`，GmOnlyCommand + 词表守卫，只写 `markets`
     一格的 numeraire）—— 有了它，探针**不需要清区**，直接在现成的格上换计价币即可（一行命令）。
     ★ 这条命令面 B2（市场区持久化）本来也要面对"法定币是谁"的问题，不算凭空发明。
  2. 或等 **B 阶段的三币世界**（3 区 3 币是构造出来的，天然满足 F5 的世界前提）。
- **已实现的最强 fail-closed 降级**：校验在**成交前第一句**、异币组合**成交量 0、账户一字未动**、拒因具名并进读数与日志
  —— "演示不了" ≠ "拒绝不了"；缺的只是"一个双计价币的夹具"。
- **本判据的验证建议**：交**测试代理**在 `io.mosire.simos.economy.time` 包内做一次单元级验证（该包能直接构造
  `MarketRound`/`SellSlot` 场景，成本远低于造一个双计价币世界）。

### 未完成（按设计书 §8 明确不在本批）

- **A2b**：5 处跨币种 1:1 求和收口（预算帽/债务压力/商号/利润账/市场支付腿）、`ApiViews` 读口补 `displayName` —— 一个字没碰。
- **B**：市场区持久化、3 区 3 政府世界、行政区互斥守卫、GOV 总疆域 → hex、跨区套利 —— 一个字没碰。
- 商品采购窗口、口岸/关税、铸币生产方式 —— 按 §10 不做。

### 未验证（如实记）

- **未跑** `test` / `verify` / `package`（派单书 §一.5/§三.0；服务在跑、禁覆盖 shaded jar）。**门禁未绿**。
- 既有测试只验到**源码仍能编译**（`test-compile` BUILD SUCCESS）；**行为回归没跑**。
- 跨轮"最近 N 笔"的滚动窗口（需要状态）—— 未实现（见 §4-4）。
- 多币对同时活跃（两本以上 FX 簿）—— 代码按币对分簿，但只在单币对上实测。
- 政府窗口的"吞吐上限"（设计书 §4.4 的 `surrenderPerMille` / 结汇强制）—— 属 B/阶段 3，未实现。
- 探针只在 small-world（19 hex / 178 账户）上跑；`cache` 语义（`MARKET_FX_WINDOWS` 只在 DEBUG）未单独验。

---

## 7. 报告数据（交账）

### 7.1 改动文件

**新增（12）**：
```
simos-economy-api/.../api/fx/{FxSide,FxOrder,OfficialRate,FxFill,FxVenue,FxRejectReason,FxMarketRate}.java
simos-economy/.../time/{FxPricing,GovFxWindow,FxRoundInput,FxRoundResult,FxSettlement}.java
simos-economy/.../spi/EconomySetOfficialRateHandler.java
simos-app/.../tools/write/GovSetFxRateTool.java
```
★ 上面把 api/fx 的 7 个文件写成一行；实际是 7 个文件。

**修改（14）**：
```
simos-economy-api/.../api/market/MarketUnfilledReason.java     + CURRENCY_MISMATCH("currency_mismatch")
simos-economy-api/.../api/transfer/TransferReason.java         + FX_TRADE / GOV_FX_WINDOW
simos-economy/.../EconomyLog.java                              + FX_LOGGER_NAME + fx()
simos-economy/.../EconomyLogSource.java                        + ECONOMY_FX
simos-economy/.../model/Government.java                        + officialRates（第 7 组件）+ withIssuable/withOfficialRate/withTreasury
simos-economy/.../spi/EconomyDefineCurrencyHandler.java        重建 Government 改走 withIssuable（不手抄字段）
simos-economy/.../spi/EconomyRegisterGovernmentHandler.java    重登记原样带过 officialRates
simos-economy/.../spi/EconomyClearRegionHandler.java           ★ 顺手修既有缺陷：先摘商号再摘组织（2 行，见 §5.3）
simos-economy/.../time/MarketSettlement.java                   withFx/fx + 克隆带过 + SellSlot.receiveCurrency + 异币具名拒
                                                               + FX 段 + 报告接 fx + 家户为键的余额/保留额助手 + MarketRound 只读访问器
simos-economy/.../time/MarketReport.java                       + 第 17 组件 fx + 旧 16 参构造器 + withRegulatedTariff 带 fx 重载
                                                               − 删掉第一版的 WeakHashMap 侧表
simos-economy/.../time/EconomySettlement.java                  + withFx(FxRoundInput.of(governments, moneyIssuances))
simos-app/.../Shell.java                                       + new EconomySetOfficialRateHandler()
simos-app/.../access/DecisionCallerFactory.java                + GovSetFxRateTool.NAME（白名单）
simos-app/.../tools/read/CatalogTool.java                      + "economy.SetOfficialRate" 载荷提示（缺则 Shell.start 起不来）
simos-app/.../tools/SimosToolSource.java                       + GM 桶 + 决策人桶各注册一条
```

### 7.2 编译命令与实际结果

| 命令 | 结果 |
|---|---|
| `./mvnw -q -o -DskipTests compile -pl simos-economy -am` | **exit 0** |
| `./mvnw -q -o -DskipTests compile -pl simos-app -am`（派单书指定命令） | **exit 0** |
| `./mvnw -o -DskipTests test-compile -pl simos-app -am`（不跑测试，只验既有测试源码还能编译） | **BUILD SUCCESS** |
| `./mvnw -q -o spotless:apply -pl simos-economy-api,simos-economy,simos-app` | exit 0（`git status` 只列本批 26 个文件，未碰别人文件） |
| 探针 `/tmp/a2a-probe/A2aProbe.java`（真 Shell、零 register） | **98 条 ✓ / 0 条 ✗ / 1 项 BLOCKED**，`exit=3`（= 可断言项全过、有未验证项，不是『全绿』） |

★ 未跑 `package`（服务在跑、禁覆盖 shaded jar）；未跑 `test`/`verify`。

### 7.3 ★ 会改变数值行为的清单（给测试代理当输入）

1. **只有在"某 GOV 定了某币对官方汇率"之后才有外汇面**：A1 创世的单币世界**逐值不变**（探针 §2/§5 逐值断言）。
   一旦定了汇率（DM/GM 命令），该世界立刻出现：政府外汇窗口挂单、家户 FX 挂单、FX 成交（两条腿）、
   铜/银在国库与家户间流动。⇒ 任何"定过汇率后再跑 tick"的场景，账户余额与商品成交集合都会与本批之前**不同**。
2. **`Government` 多第 7 组件 `officialRates`**（默认空表）：按组件计数/逐组件快照比对的用例要 +1；
   `EconomyData.governments` 的逐值比较在定过汇率后会变。
3. **`MarketReport` 多第 17 组件 `fx`**（默认 `FxRoundResult.none()`）：按组件数/`equals`/`toString` 比报告形状的用例要 +1。
   ★ `MarketReport.withRegulatedTariff(...)` 的**旧 17 参签名保留**（委托 fx=none）⇒ 调用点零串改。
4. **`MarketUnfilledReason` 多一档 `CURRENCY_MISMATCH`**、**`TransferReason` 多两档 `FX_TRADE`/`GOV_FX_WINDOW`**：
   `switch` 穷举这两个枚举的用例会编译失败/行为变化。
5. **`Government` 的官方汇率表在"重登记政府"时原样带过**（此前没有这个字段）：`RegisterGovernment` 的幂等语义不变。
6. **`SellSlot.receiveCurrency`** = 卖方格的 `Market.numeraire()`：单币世界恒等于 `buy.currency` ⇒ **逐值不变**；
   多币世界会把此前"静默 1:1"的成交变成具名拒（成交量 0）。
7. **新命令类型 `economy.SetOfficialRate`** ⇒ 命令面计数 +1（`SimosToolsTest`/`McpCoverageTest`/`McpServerTest`/
   `McpPortTopologyTest`/`AdjudicateTickToolTest` 之类的硬编码面计数，与 A1 的 131/156/43/90 同族，见 §7.5）。
8. **新工具 `simos.gov.setFxRate`** ⇒ GM 桶 +1、决策人桶 +1、`WHITELIST` +1（三者同源，`DecisionCallerFactoryTest`
   的"白名单 == 决策人桶"断言**仍然成立**，但计数型断言 +1）。
9. **新日志分类** `io.mosire.simos.economy.fx`（继承 `io.mosire.simos.economy` 的 INFO 开关）⇒ 按 logger 名白名单/计数
   的用例可能要看一眼；`log4j2.xml` **未改**（子 logger 天然继承）。

### 7.4 受影响的硬编码字面量

| 字面量 | 位置 | 说明 |
|---|---|---|
| `1000L` / `100L` / `120` | `FxSettlement.FX_MIN_LOT_BASE_MILLI=1000` / `FX_SELL_DISTRESS_PER_MILLE=100` / `FX_RATE_WINDOW_FILLS=120` | 三个**具名 GM 可调默认值**（最小手 / 家户卖汇折价 / 采样窗口） |
| `500L` | `GovFxWindow.DEFAULT_RESERVE_CAP_PER_MILLE = 500` | 储备上限口径（发行量 × 500‰），具名 GM 可调默认值 |
| `1000L`（per-mille 基准） | `FxPricing` / `GovFxWindow.quotePerMille` / `FxRoundInput`（发行量折算） | per-mille 定点基准，写在各自身处、注释里标了量纲 |
| `100000`（探针载荷） | `/tmp/a2a-probe/A2aProbe.java`（粮价 1,000,000 用于 F5 场景） | 只在探针里，不进口仓库 |
| 未新增 | 任何 `new CurrencyId("silver"/"copper")` / `CommodityId("…")` | 全部走 `MoneyVocabulary.SILVER_*`/`COPPER_*` 常量（护栏仍 0 命中就地拼写） |

### 7.5 ★ 会让既有测试断言失效的清单（给测试代理）

1. **计数型（必红，预期）**：A1 已经把它们从 128/153/40 改成 131/156/43（`SimosToolsTest` 的 catalog/GM 桶/决策人桶、
   `McpCoverageTest`+`McpServerTest`+`McpPortTopologyTest` 的 156、`AdjudicateTickToolTest` 的 90）。本批**再加一个命令类型
   与一个工具** ⇒ 这五处再 +1（catalog 132 / GM 桶 157 / 决策人桶 44 / MCP 157 / Adjudicate 91）。
   **正确处置**：按新数改期望（不是把新命令/工具删掉）。
2. **`DecisionCallerFactoryTest.theWhitelistIsExactlyTheReadToolsPlusTheTwoDecisionWrites`**：断言"白名单 == 决策人桶"——
   本批**两处同源都加了** `GovSetFxRateTool.NAME` ⇒ **应当仍绿**（若计数被写死则 +1）。
3. **枚举穷举**：任何对 `MarketUnfilledReason` / `TransferReason` 做 `switch` 穷举或按 `values().length` 计数的用例
   ⇒ `CURRENCY_MISMATCH`(+1) / `FX_TRADE`,`GOV_FX_WINDOW`(+2)。
4. **组件计数**：`EconomyRoundTripTest` 一类"逐组件往返"的用例会自动覆盖 `Government.officialRates`（泛型 FieldDelta），
   但任何**按 `EconomyData`/`MarketReport` 组件数**写死数字的断言要 +1（`fx`；`Government` 是嵌套 record，不在组件表里）。
5. **可能红（需核）**：任何断言 `MarketReport` `equals`/`toString` 逐字文本的用例（多了 `fx=…` 一节）；
   任何对创世后 dump/快照做**逐字节**比对的用例（本批创世链没变 ⇒ 应当不受影响，但 `Government` 的线格式多了 `officialRates` 键）。
6. **应当仍绿（已用 test-compile + 设计核对）**：所有既有 `Government` 构造点（旧 4/5/6 参构造器保留）、
   `MarketReport` 的旧构造点、`MarketReport.withRegulatedTariff` 旧签名、单币世界的全部经济读数（无官方汇率 ⇒ 无外汇面）。

### 7.6 探针（自证；`/tmp`，不进仓库、不进 `src/test`）

源码：`/tmp/a2a-probe/A2aProbe.java`（真 `Shell.start` + 真创世 + 真 DM 审批链 + 真命令，**零 `core.register`**）。
输出：`/tmp/a2a-probe/final.log`（下方为摘录；`== 全部断言通过 ==` 之外只剩 F5 场景构造那 4 条）。

```
== A2a 生产装配探针（真 Shell / 零手动 register）==  [exit=3]
   store=/tmp/a2a-probe-store… world=small-world
  ✓ 0 真创世入口 ShellMain.seedGenesisIfEmpty ⇒ true；词表 = silver + copper
  ✓ 0 创世**没有**任何官方汇率（⇒ A2a 落地后老世界一个数都不动）
  ✓ 1 决策人桶/GM 桶含 simos.gov.setFxRate；WHITELIST == 决策人桶；MCP 注册表认得
  ✓ 2 第 5 天真开了一轮市场；没有官方汇率 ⇒ 本轮外汇面为空（fills/windows/rejections 全空）
  ✓ 2 无成交 ⇒ 实际汇率 = 无读数（M4）；具名原因 = no_fx_fills
  ✓ 3 决策人路径 setFxRate 真提交成功（待批 → GM 点头 → 恰 +1 revision）
  ✓ 3 官方汇率进状态（1000/1050，从 store 读回）；只动 governments 一个组件
  ✓ 4 负向对照：同值再设 ⇒ 具名拒 rate-unchanged；没落 revision
  ✓ 5 第 10 天：窗口读数在场；储备 100000 ≥ 上限 50000 ⇒ 买侧容量 0 且具名 reserve_cap
  ✓ 5 窗口拉动的成交价 = 官方卖价 1050‰（§4.5：官方只在窗口有量时拉动市场价）
  ✓ 5 M4 硬检查：无读数访问器当场抛（不许把官方价当市场价用）
  ✓ 6 场景构造（actor.AdjustAccounts 只搬钱不造钱）I20 逐币种守恒
  ✓ 7 F2-②：政府卖出（家户拿本币买到外币）有真实成交（venue=gov_window，价 1050‰）
  ✓ 7 F2-② 成交记在 GOV_WINDOW 场所；买方铜增加、国库铜减少
  ✓ 8 F2-①：政府买入（持外币无本币户换成能花的钱）有真实成交（venue=gov_window）
  ✓ 8 F2-① 成交记在 GOV_WINDOW 场所；卖方铜减少、国库铜增加
  ✓ 9 F3：储备触顶 ⇒ 买侧容量 0 且具名 reserve_cap
  ✓ 9 F3：家户卖单没有对手盘 ⇒ 具名拒 no_counterparty（不是静默不成交）
  ✓ 10 F3：储备见底 ⇒ 卖侧容量 0 且具名 reserve_exhausted（不许卖空）
  ✓ 10 无卖空：国库铜余额非负
  ✓ 10 F4：家户↔家户成交确实发生（venue=household）
      [F4] 官方 buy/sell = 1000/1050‰，实际（成交量加权）= 975‰（样本 3 笔）
  ✓ 10 F4：实际汇率 ≠ 官方买价 1000‰；≠ 官方卖价 1050‰
  ✓ 2/5/6/7/8/9/10 I20：逐币种总量守恒（7 次真提交前后逐值不变）
  ✓ 11 F5 场景：清区成功（economy.ClearRegion，★ 修 bug 后）；两计价币并存（copper@-2_0 / silver@-2_1）
  ✓ 11 F5 场景：铜计价买方（铜钱 + 粮需求）与银计价卖方（持粮）都建好
  ⚠ F5 端到端：推进被既有跨切片守卫 fail-closed（时间预算指向不存在的家户）⇒ BLOCKED（不记为通过）
  ✓ 12 负向对照：未注册命令类型被拒；没落 revision
== 可断言项全部通过；BLOCKED 见上（exit=3）==
```
（注：上面对"逐条相同"的 I20/接线断言做了合并；完整 98 条逐条输出见 `/tmp/a2a-probe/final.log`。）

**日志证据**（同一次运行，log4j2 控制台）：
```
event=OFFICIAL_RATE_SET ×1        event=OFFICIAL_RATE_REJECTED ×1（rate-unchanged）
event=FX_ROUND ×5                 event=FX_WINDOW_BUY_BLOCKED ×3（reason=reserve_cap / insufficient_funds）
event=FX_WINDOW_SELL_BLOCKED ×1（reason=reserve_exhausted）
event=FX_REJECTED ×11（no_counterparty / no_cross / reserve_cap）
```
★ **负向对照**有三条，证明"拒绝不是什么都没发生"：① 同值再设汇率 ⇒ `rate-unchanged` 具名拒 + head 不动；
② 库存触顶那轮：家户卖单**具名** `no_counterparty`、账户逐值不变（不是"静默少成交"）；
③ 未注册命令类型被拒 + 零 revision。

### 7.7 与约束设计书不一致处

见 §4（8 条）与 §6（BLOCKED-1）。
