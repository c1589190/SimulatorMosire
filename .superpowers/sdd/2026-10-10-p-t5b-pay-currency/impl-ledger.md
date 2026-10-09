# P-T5b 买方支付币接到购买决策 + 非本格币折算面 —— 实现架构账本（写码 Agent）

> **依据（约束设计书）**：`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md` §2.1（订单与币种冻结表）、§2.3、§2.6、§3（I-C1/I-C2/I-C5/I-C10）、§5（T4/T5/T6）、§6.2 V-1、§7 Q-x；
> `docs/superpowers/specs/2026-10-09-port-policy-and-zone-efficiency-design.md` §20.1（F-1 用户原话）/§20.3/§20.4（**前置依赖：F-3 要能用得出去**）/§20.5；
> 前两批账本：`.superpowers/sdd/2026-10-10-3c-order-currency/impl-ledger.md` §5.2（6 处非本格币折算面）/§7（`orderCurrencyFor` 接缝）、`.superpowers/sdd/2026-10-10-p-t5-fx-market/impl-ledger.md` §4 偏离 1（P-T5 未接币种面）/§7。
> **基线**：工作树 `1827dbce`（P-T5 之后、工作树干净）。
> **本批范围**：`simos-economy/src/main`。**不做**：P-T1e 币种挂单过滤（接受集恒真）、P-T1d 政府采购优先级、M1–M5、信用跨币、套利现金口径。
> **纪律**：只写生产代码、只到编译过；不写/不改测试；不跑 `test`/`verify`；不 `git commit`；一次一个 Maven；旧档不兼容（§一.11）。

---

## 0. 一句话

F-3 把弱币全换成"最强持有币"之后**必须花得出去**（§20.4 的硬前置）：本批把 **家户买单的支付币 = 该户当轮选定的最强持有币**（F-1 强度序首项）接到
`orderCurrencyFor` 与四个订单构造点，并把**预算 / 可负担量 / 冻结限价 / 运费腿**一律按**买方支付币**经**同一份** `CurrencyValuation` 折算；
**单一币 / 不可比 / 缺省 ⇒ 本格计价币 ⇒ 旧世界逐值不变**（结构性，不是"算出来恰好相等"）；说不出价 ⇒ 具名归因 + 本单不生成，**不**静默 1:1、**不**静默退回本格币。

---

## 1. 关键调查结论（`file:line` 为**本批改动前**的行号，逐条核过）

| # | 事实（改动前） | 结论 | 影响 |
|---|---|---|---|
| C1 | `MarketSettlement:2035` `orderCurrencyFor(Market)` 恒返回 `market.numeraire()`；类注明写"P-T5 接进来的唯一改动点" | 3c 的接缝**没接**：`payWith` 永远是本格币 ⇒ §20.4 的缺口在（P-T5 账本 §4 偏离 1 已上报） | 本批要闭合的主因 |
| C2 | `:1936/:2091` `orderCurrencyFor(market)`；`:1943/:2023/:2127/:2196` 四处 `new SellOrder/new BuyOrder` 都用同一个 `orderCurrency` | 四个构造点已经**共用**一个局部量 ⇒ 选币只需改 `orderCurrencyFor` 一处（与派单"落点"一致） | 改动面被 3c 收窄成一处 |
| C3 | `:1956` `cashAffordable = budget × 1000 / reference`；`:2093` 同式 | `budget` 是**支付币**毫、`reference` 是**本格计价币**毫 ⇒ 异币时把两种钱直接相除（3c 账本 §5.2 第 1/2 处） | 按支付币折一次 |
| C4 | `:2447` `requestedMoneyOf` = `min(预算, ⌈数量 × order.maxLandedPrice() ÷ 1000⌉)` | 冻结轴 = `(buyer, buy.currency)`，而限价是本格计价币 ⇒ 异币时冻结额量纲错（第 3 处） | 建槽位时折一次（`limitInPayCurrency`） |
| C5 | `:4635` `buy.order.maxLandedPrice() < buyerUnitPrice` | 右侧是 3c 折出来的**支付币**单价 ⇒ 异币时是"本格币 ↔ 支付币"比大小（**3c 的 6 处清单里没有它**） | 一并补上（同一条冻结值） |
| C6 | `:6231` `unitCost = unitPrice + route.freightPerUnit`；`:6283` 运费；`:5677/5690` `freightOf(q, route.freightPerUnit)`；`:5899` `Fill.freightPerUnitMilli`；`:6023/6013` `carrierChargeSplit` 内部 | 单价是支付币、运费是本格计价币 ⇒ 相加/铸腿都是跨币（第 4/5/6 处，且 `:5816` 运费腿铸在 `buy.currency` 上 ⇒ 真会错账） | 全部折成支付币（唯一拼写点 `BuySlot.payAmountOf`） |
| C7 | `:4041` `affordableQuantity(ctx, buys.get(i), price, route)`（`matchGroup`） | 唯一一处把**本格计价币**参考价当"买方支付币单价"传进去的调用点（其余三处传的都是折过的） | 一并补上（第 7 处） |
| C8 | `:1291-1318` `clearOncePerCycle` 单线程建 `rowsByHex` + `currencyValuation`，随后 `:1392-1446` **按区并行**生成订单 | 选币必须在这段并行之前算好（并行段里现算 = 共享可变缓存 = 数据竞争；且同一户每商品都会被问一次） | 用值对象 + 参数传递，**不**挂到 `MarketRound`（避免动 8 处克隆点） |
| C9 | `MarketReadout:241` 走**同一条** `planOrders`（"读到的订单 == 会下的订单"） | 读口不注入 `payChoice` 就会在**买单数量**上与实际漂开 | 读口同源注入（`emitLog=false`） |
| C10 | `FxSettlement:639 hasMultipleCurrencies` / `:469 heldCurrencies` 是 `private`；`:160 strengthOrder` 只被 FX 段内联使用 | "哪种币最强"若两处各写一遍，破平处可能漂开 | 提升为包内可见 + 新增 `HouseholdPurchasingPower.strongest`（唯一拼写点），FX 段改为调用它 |
| C11 | `FxSettlement`/`MarketSettlement:3179/6469/6770` 等匹配侧 `spendableMoneyOf` 已全按 `buy.currency`（3c） | "可花额按币"这一半**已经是现状**，本批只需保证订单币本身变了 | 不改这些调用点 |

---

## 2. 实现架构

### 2.1 新增：`MarketPayChoice`（"该户本轮用哪种币付"的**唯一**拼写点）

`simos-economy/.../time/MarketPayChoice.java`（包内可见、无状态值对象、不进任何持久结构）：

```
of(round, markets, topology, rowsByHex, valuation, emitLog)
  ├─ !FxSettlement.hasMultipleCurrencies(round, markets) ⇒ 空表（单币世界：连价表都不建、一个事件都不发）
  ├─ zoneTables = HouseholdPurchasingPower.zoneTables(markets, topology)
  └─ 逐 hex(q,r 升序) → 逐户（行序）：
       跳过：市场排除户 / 国库户（只按授权下单）/ 无家户行 / naturalNeeds 空 / 可花币 < 2 种
       逐币（FxSettlement.heldCurrencies：可花额>0、币种 id 升序）：F-1 needCostMilli ⇒ 缺价 ⇒ 不参与
       可比币 < 2 ⇒ 不选（计数进 DEBUG）          否则 ⇒ strongest(power)（cost 升序、币种 id 升序）
       TRACE MARKET_PAY_CURRENCY_HOUSEHOLD（逐户）／INFO MARKET_PAY_CURRENCY_ROUND（轮汇总）／DEBUG MARKET_PAY_CURRENCY_PLAN（逐档计数）
payCurrencyFor(household, market)  ⇒ getOrDefault(hh, market.numeraire())      // 缺省回落 = 本格计价币
valueMicroOf(market, currency, hex) ⇒ 同币 ⇒ 1000；否则 CurrencyValuation.valuationMicro(本格币, 该币, hex 所在区)
```

★ **数据流（一处产生 → 全链消费）**：

```
MarketPayChoice.of(...)  ← 单线程、并行下单段之前（clearOncePerCycle:1355 / MarketReadout:222）
   ├─ CurrencyId orderCurrency = orderCurrencyFor(payChoice, participant, market)   ← 唯一产生点
   │     ├─ ordersFor：budget = spendableMoneyOf(round, participant, orderCurrency)
   │     │             referenceInPay = amountInPayCurrency(...)   ⇒ cashAffordable = budget×1000÷referenceInPay
   │     │             SellOrder(..., receiveWith = orderCurrency) / BuyOrder(..., payWith = orderCurrency)
   │     └─ planGovMandateOrders：同一条算式（国库户不在选择面里 ⇒ 恒本格币 ⇒ 逐值不变）
   └─ BuySlot(order, buyer, hex, region, market, payChoice)   ← 建槽位时冻结两个折算值
         payValueMicro      = valueMicroOf(...)                          // 微本格计价币 / 毫支付币
         limitInPayCurrency = 面值⇒原样；否则 ⌈maxLandedPrice×1000÷payValueMicro⌉；说不出价 ⇒ -1
               ↓
   冻结 requestedMoneyOf（读 limitInPayCurrency）
   限价口径比较（matchGroup 价格折算 / 跨区 4635 / 可负担量 / totalCostAtMost / executeTrade / carrierChargeSplit / Fill 读数）
   一律经 payAmountOf(毫本格计价币) → 毫支付币（面值 ⇒ 原样；说不出价 ⇒ -1 = fail-closed）
```

### 2.2 文件清单与关键方法

| 文件 | 改动 |
|---|---|
| `time/MarketPayChoice.java` | **新增**（§2.1；含 3 条事件、`LinkedHashMap` + `Collections.unmodifiableMap`） |
| `time/HouseholdPurchasingPower.java` | 新增 `strongest(Map)`（"谁最强"的唯一拼写点） |
| `time/FxSettlement.java` | `strongest` 收口（删内联循环）；`hasMultipleCurrencies`/`heldCurrencies` 提为包内可见（同一拼写点复用） |
| `time/MarketSettlement.java` | `orderCurrencyFor(payChoice, participant, market)`；`amountInPayCurrency`（新，订单侧唯一折算）；`logPayCurrencyUnvalued`（新，具名归因）；`ordersFor`/`planGovMandateOrders`/`planOrders` 传 `payChoice`；`BuySlot` 两个冻结值 + `payAmountOf`；`requestedMoneyOf`/`matchGroup` 价格/限价比较/`affordableQuantity`/`totalCostAtMost`/`executeTrade`/`carrierChargeSplit`/`Fill` 全部按支付币折算；`logFreightCurrencyUnvalued`（新，第二道 fail-closed） |
| `time/MarketReadout.java` | 读口按**同一条**装配式建 `CurrencyValuation` + `MarketPayChoice`（`emitLog=false`），`planOrders` 走带 `payChoice` 的重载 |

---

## 3. 关键判断（为什么这样拆；调查中推翻过什么）

1. **选币面用"值对象 + 参数"而不是挂到 `MarketRound`。** `MarketRound` 有 8 处克隆/构造点（`withArbitrage`/`withFx`/`withGovMandates`/`withPortEnforcement`/`withPortTax`/`withCredit`/`copyForWorker`），
   加字段就要逐处带过（本类踩过三次"克隆丢字段"）；而本批**不需要**在撮合中途读选币面 —— 订单生成与建槽位都在 `clearOncePerCycle` 的同一段作用域里，
   撮合侧只需要槽位上那两个冻结值。⇒ 零克隆改动、零新持久面。
2. **选币在并行段之前算好、之后只读。** 订单生成按市场区并行（`SettlementExecutor`），在 worker 里现算会变成共享可变缓存；且同一户在同一 hex 的每个商品上都会被问一次。
3. **`strongest` 收口成一处。** 用户原话（F-1）"用本格币付的最少的就是购买力最强"同时决定 **P-T5 换成哪种币** 与 **P-T5b 用哪种币付**：
   两处若各写一遍，破平（cost 相等）时可能给出两个答案 ⇒ FX 换出来的币与买单付出去的币不一致。⇒ 两处共用 `HouseholdPurchasingPower.strongest`。
4. **折算锚：订单/冻结/预判侧 = "买方自己的钱"，结算侧 = "卖方格"（3c 不动）。** 订单侧的预算与限价都长在**本格价表**的尺度上（计划 §2.1"一格一张价表、一个尺度"），
   所以它们 ↔ 支付币的价比取**该 hex 所属区**（与 P-T1b 的税腿用 `buy.regionId` 同一条先例）；而"这笔货按什么价成交"仍由 `settlementUnitPrice`
   按**卖方格**折算（3c 冻结口径，本批一个字不改）。★ 残留（如实记）：跨区且两区计价币不同时，货款（卖方锚）与运费（买方锚）用的是两个价 —— 见 §5.3。
5. **运费必须折成支付币，而不是"留在本格币"。** P-T4 已冻结 `Fill.freightCurrency() ≡ paymentCurrency`，且 `:5816` 的运费腿就铸在 `buy.currency` 上
   ⇒ 不折就是"把本格币的运费当成支付币的运费"真错账（不是读数问题）。★ 但**运费金额规则一个字不改**（`freightUnitMilli`/`freightRatePerMille`/`carrierChargeSplit` 的分摊比例全原样），只把量纲换对 —— 这是冻结口径第 7 条"运费腿（买方付）口径不变"的落法。
6. **限价折一次、存槽位（`limitInPayCurrency`），而不是每次比较时现折。** ① 禁两次折算；② worker 与协调器回放必须给出同一个价（与 `SellSlot.reservationMicro` 同一条纪律）；
   ③ 顺带修掉 3c 清单里没列出的 C5（限价 vs `buyerUnitPrice` 的跨币比大小）。
7. **面值（`payValueMicro == 1000`）走"原样返回"，不做乘除。** 同币与"当地流通 ⇒ 面值"两种情形下 `⌈x×1000÷1000⌉ = x` 是恒等式，
   提前返回让"旧世界逐值不变"成为**一条都不算**（也躲开 `buyerUnitPriceFor` 的溢出 fail-closed），而不是"算出来恰好相等"。
8. **读口同源选币（`emitLog=false`）。** 读口与结算共用 `ordersFor`；不注入选币面，多币世界里"看到的订单"与"会下的单"会在买单数量上漂开。
   ★ 读口**不发日志**（每次 API 请求都会跑一遍 `derive`，发 INFO/DEBUG 会按请求数放大 —— 与 `planFor`/`logLifeReserves` 的分工同一条纪律）。
9. **卖方侧一个字不改（冻结第 5 条）。** `acceptsCurrency` 仍恒真；`SellSlot.receiveCurrency` 今天**零行为读者**（只有 3 处日志 payload），
   与订单同源后也只有日志差异。★ 已知接缝（留给 P-T1e 裁定）：卖家 `receiveWith` 现在带的是**卖家自己的最强持有币** —— P-T1e 落地时必须显式裁定
   "卖家是否只收自己最强的那种钱"（若不是，就把卖方一侧拆成第二个方法；`orderCurrencyFor` 就是那个唯一拆分点）。
10. **`cashAffordable` 的"0 价"分支保持 `Long.MAX_VALUE`。** `amountInPayCurrency(0) = 0` ⇒ 与改前 `reference == 0L` 完全同路（免费交易不受预算约束，运费另计）。

---

## 4. "缺省 / 单一币 / 不可比 ⇒ 逐值不变"的论证（I-C2）

逐条对齐"改动前表达式 → 改动后表达式"的**取值**（不是形状）：

| 位置 | 改动前 | 改动后 | 取值 |
|---|---|---|---|
| 订单币 | `market.numeraire()` | `payChoice.payCurrencyFor(hh, market)` | 单币世界**在建表之前早退** ⇒ 空表 ⇒ `getOrDefault` 回落 = `market.numeraire()` ⇒ **同值**（结构性的，不是巧合） |
| 预算 | `spendableMoneyOf(round, p, numeraire)` | `spendableMoneyOf(round, p, orderCurrency)` | **同值**（同上） |
| 可负担量 | `budget×1000÷reference` | `budget×1000÷referenceInPay`，`amountInPayCurrency` 同币 ⇒ 原样 | **同值**（且 `referenceInPay == 0 ⟺ reference == 0`，`Market.prices` 值 ≥ 0 有构造期守卫） |
| 冻结额 | `⌈q × maxLandedPrice ÷ 1000⌉` | `⌈q × limitInPayCurrency ÷ 1000⌉`，面值 ⇒ 原样 | **同值**（一条乘除都不做） |
| 限价比较 | `maxLandedPrice < buyerUnitPrice` | `limitInPayCurrency < buyerUnitPrice` | **同值** |
| `matchGroup` 价格 | `affordableQuantity(ctx, buy, price, route)` | `payAmountOf(price)` = `price` ⇒ 同参 | **同值** |
| 运费腿（3 处） | `unitPrice + route.freightPerUnit` / `freightOf(q, route.freightPerUnit)` / `Fill.freightPerUnitMilli = route.freightPerUnit` | `payAmountOf(route.freightPerUnit)` = 原样 | **同值** |
| `carrierChargeSplit` | 逐条 `freightUnitMilli(...)` 与 `route.freightPerUnit` | 逐条 `payAmountOf(同式)` = 原样 | **同值**（自承运判据 `buyerActor` → `buy.buyer.actor` 是同一事实） |
| 新增事件 | — | 单币世界**一条都不发**（早退在建表之前） | **日志面也逐字不变** |

⇒ **旧世界（单一币 / 没有可比的第二种币 / 没挂币种面）逐值不变**；机制只体现在"订单能带非本格币 + 预算/限价/运费按它折算 + 说不出价具名拒"三件事上。

---

## 5. 6 处折算面逐条（含本批一并补的 3 处）与未补项

| # | 3c 账本 §5.2 的面 | 本批 | 落点 |
|---|---|---|---|
| 1 | `ordersFor` 的 `cashAffordable` | ✅ **补** | `amountInPayCurrency`（`ordersFor`） |
| 2 | `planGovMandateOrders` 的 `cashAffordable` | ✅ **补** | 同一条算式（国库户恒本格币 ⇒ 逐值不变） |
| 3 | `requestedMoneyOf`（限价 → 冻结额） | ✅ **补** | `BuySlot.limitInPayCurrency`（建槽位时折一次） |
| 4 | `affordableQuantity` 的运费腿 | ✅ **补** | `BuySlot.payAmountOf` |
| 5 | `totalCostAtMost` 的运费腿 | ✅ **补** | 同上（与 `executeTrade` 同一拼写点 ⇒ 判得起 == 真的扣） |
| 6 | `MarketReport.Fill.freightPerUnitMilli` | ✅ **补** | `executeTrade` 的 `unitFreight`（与 `freightCurrency ≡ paymentCurrency` 同币） |
| 7 | （3c 未列）限价 vs `buyerUnitPrice` 跨币比大小 `:4635` | ✅ **一并补** | `limitInPayCurrency` |
| 8 | （3c 未列）`matchGroup` 把本格价当支付币单价 `:4041` | ✅ **一并补** | `payAmountOf(price)` |
| 9 | （3c 未列）`executeTrade` 名义/未收运费与 `carrierChargeSplit` 的分摊额 | ✅ **一并补** | `payAmountOf`（腿铸在 `buy.currency` 上，不折就是真错账） |
| — | `TradeArbitrageActivity:295` `snapshot.moneyOf(numeraire)` 的套利现金上限 | ❌ **未补** | 它是**劳动阶段**的计划量上限（另一个责任区），不是支付折算；见 §6.4 |
| — | `MarketReadout:294` `BuyerOutcome.spendableMoneyMilli`（单一本格币读数） | ❌ **未补** | P-T4 的已知缺口（3c 账本 §5.4 已记），本批不扩大 |
| — | 信用腿跨币（`MarketSettlement:3179` 一族按 `candidate.currency` 自然落空） | ❌ **未补** | 3c 账本 §5.5 已记：非本格币买方本轮**只能现金买**（无货币信用）；本批不改 |

---

## 6. 不改变 / 未做 / 遗留（如实记）

1. **卖方侧不分道**（冻结第 5 条）：`acceptsCurrency` 恒真、钱腿仍铸买方支付币 ⇒ 与 3c 逐字相同（见 §3 判断 9 的 P-T1e 接缝）。
2. **金额规则一个字不改**（冻结第 7 条）：单价尺度、三层税、运费费率/分摊比例、冻结/释放口径全部原样；本批只改"用哪种币付"。
3. ★ **跨区 + 两区计价币不同时，货款与运费各按自己的尺度折算**（买方锚 vs 卖方锚，见 §3 判断 4）。同一区（绝大多数字面）两锚相同；
   跨区异尺度的残留留给 P-T1e/后续批（要统一必须动 3c 的 `currencySettlement` 锚，那超出本批冻结范围）。
4. **套利买盘的现金上限仍按本格计价币读**（`TradeArbitrageActivity:295`）：本批**不改**它；后果 = 有外币的户套利量上限与它的支付能力可能不一致（撮合/冻结仍按真实余额封顶 ⇒ **不会负余额**，只是量偏保守/偏大）。
5. **异币支付会多走一道 E 批的"保留价"判据**（`settlementUnitPrice`：同币直接放行，异币要 `买方付出 × 我对该币的估值 ≥ 保留价 × 数量`）。
   本批把"最强持有币"接到订单后，多币世界里这一分支**从不可达变为可达** ⇒ 卖方缺货（`NECESSITY_PREMIUM`）时可能拒收外币买方（具名 `PRICE_LIMIT` + `MARKET_CURRENCY_VALUE_REFUSED`）。
   这是 E 批（3c）的冻结判据、不是本批新造的规则，但它**是**本批引入的可达路径 ⇒ 测试 Agent 必须覆盖（见 §7.1-4）。
6. **卖单的 `receiveWith` 现在带"卖家自己的最强持有币"**：该字段今天零行为读者 ⇒ 数值无影响；P-T1e 落地前必须裁定语义（§3 判断 9）。
7. **未跑任何 `test`/`verify`/真实 world**（派单纪律）：行为等价性由 §4 的逐表达式论证承担；真实数值证据留给测试 Agent / 控制方总验收。
8. **本批未新增任何持久状态、未触碰铁律 5**（选币逐轮瞬态、订单仍不落盘）。

---

## 7. 给测试 Agent 的输入

### 7.1 会改变数值行为的清单

1. **多币世界 + 家户持 ≥2 种"可比"币（各币法定区锚格价表都有它的需求篮子）+ 有市场** ⇒ 该户本轮的**买单支付币** = F-1 最强币（不再恒为本格币）；
   随之改变：冻结币种、卖方实收币种、三道税的钱腿币种、运费腿币种。
2. **旧世界（单一币 / 户只持一种可花币 / 篮子缺价 ⇒ 可比币 < 2）⇒ 逐值不变**（§4 的九行论证；含"一条事件都不发"）。
3. **读口的供给/有效需求**：多币世界下按最强币折算（`MarketReadout` 同源注入）⇒ 与改前的读数不同。
4. **异币支付走 E 批保留价判据**（§6.5）：可复现"卖方缺货 ⇒ 拒收外币买方"（归因 `PRICE_LIMIT`，DEBUG `MARKET_CURRENCY_VALUE_REFUSED`）。
5. **日志**：新增 INFO `MARKET_PAY_CURRENCY_ROUND`（每轮一次、单币世界不发）、DEBUG `MARKET_PAY_CURRENCY_PLAN` / `MARKET_PAY_CURRENCY_UNVALUED`（不可达守卫）、
   TRACE `MARKET_PAY_CURRENCY_HOUSEHOLD`、DEBUG `MARKET_FREIGHT_CURRENCY_UNVALUED`（不可达守卫）；既有 INFO `MARKET_FOREIGN_CURRENCY_FILL` 因此**变得可达**。
6. **无官方报价的多币世界**：`CurrencyValuation` 对"当地实际流通"的币取**面值 1000** ⇒ 异币支付的价格/运输折算为 1:1（R3 的既有锚，不是本批新造的 1:1）。

### 7.2 受影响硬编码字面量

**本批新增 0 个、修改 0 个**。仅复用既有常量：`EconomySettlement.MILLI_PER_GRAIN`（1000，`requestedMoneyOf` 原式）、
`CurrencyValuation.faceValueMicro()` = `HouseholdValuationBook.MICRO_PER_MILLI`（1000，"面值 ⇒ 原样"的判据）、
`buyerUnitPriceFor` 内部的同一常量、`freightOf`/`totalCostAtMost` 的 `1000L`（原样）。`HouseholdPurchasingPower.PER_MILLE` 未动。

### 7.3 会让既有测试失效的清单

1. **编译面：本批不新增任何编译失效点。** 旧 `planOrders(round, hex, market, commodity)` 与
   `planOrders(..., rowsByHex)` 两条重载**原样保留**且语义仍是"本格计价币"（`MarketPayChoice.none()`）⇒ 既有测试（如 `Z7MarketExclusionTest:56/58`）
   编译与行为都不变。`MarketPayChoice`/`BuySlot`/`carrierChargeSplit` 全是包内/私有面。
2. **`simos-economy` 测试树在本批之前就已不编译**（`MarketRegulationTest:275/280` 引用已删的 `MarketUnfilledReason.REGULATION_QUOTA`，
   P-T1c 遗留）⇒ 本批无法用"既有测试仍绿"当回归网（开发期没有行为回归保护，§三.0 已接受的对冲）。
3. **断言面（多币世界）**：凡构造"两个市场计价币 / 两区法定币不同 / 家户持两种币"的夹具，其订单币种、冻结额、成交币种、运费读数的既有断言都可能失效；
   已知可能受影响的测试文件（grep `new CurrencyId(` / `legalTender`）：`EconomyRoundTripTest`、`MarketRegulationTest`、`ExpectedProfitBook*Test`、
   `GovServiceCommitmentC7Test`、`ModeMigrationPolicy*Test`、`RegimeRelationsTest`、`EconomyCodecTest`、`MarketTopologyBookSingleRegionTest`。
4. **判据面（本批判据）**：T6（买方指定支付币 ⇒ 卖方收到的就是该币）现在有真实落点；T4/T5 的 FX 循环与 §20.4 的"换完花得出去"可端到端复现
   （前提 = §P-T5 账本 §7.9 的两条：至少两种币、各有法定区且锚格有该户篮子的定价）。

---

## 8. 门禁证据（真实命令与结果，2026-10-10）

```
① tools/mvn-lock.sh -q spotless:apply                      → exit 0（无输出）
② tools/mvn-lock.sh -DskipTests compile                    → BUILD SUCCESS / 16 模块全 SUCCESS / exit 0
③ rm -rf {simos-economy,simos-app}/target/classes
   tools/mvn-lock.sh -q spotless:apply && tools/mvn-lock.sh -DskipTests compile
   → "Compiling 189 source files …"(economy) + "Compiling 327 source files …"(app) / 16 模块全 SUCCESS / BUILD SUCCESS / exit 0
   （★ 189 = P-T5 之后的 188 + 本批新增 1 ⇒ 新文件确实进了编译单元，不是拿了上一轮 class 当绿）
④ 中间轮的真实报错（逐字，非推演）：MarketSettlement.java:[6238,42] cannot find symbol variable buyerActor
   （carrierChargeSplit 的形参从 ActorRef buyerActor 改成 BuySlot buy 后，函数体内第二处使用点漏改）⇒ 修好即绿。
⑤ 同一条 compile 命令里 **checkstyle（pom 绑在 validate 阶段）逐模块 0 违规**：16 个模块全 "You have 0 Checkstyle violations."（实测 grep）。
   ⇒ spotless + checkstyle + 编译三道都在本批跑过并全绿（**未跑** spotbugs / surefire / 前端门禁 —— 那些属 `verify`）。
```

★ **未跑**：`test` / `verify` / `package` / 真实 world（派单纪律：写码 Agent 只到编译过）。
★ 影响面（§五.1 核对）：`git status --porcelain` = **5 个文件**（4 改 1 新，全在 `simos-economy/src/main`）；
`git diff --stat` = `FxSettlement 20`、`HouseholdPurchasingPower 21`、`MarketReadout 15`、`MarketSettlement 394`，新增 `MarketPayChoice.java`（315 行）
—— **无一处溢出到 `src/test` / `pom.xml` / 别的模块**（spotless 全仓跑过三轮；`git status` 无其他脏文件）。

---

## 9. 与派单七条冻结口径的对照

| # | 冻结口径 | 落点 |
|---|---|---|
| 1 | 买单支付币 = 该户当轮最强持有币；缺省/单一币/不可比 ⇒ 本格币 | `MarketPayChoice.of` + `payCurrencyFor`（早退门 = `hasMultipleCurrencies`） |
| 2 | 落点 = `orderCurrencyFor` + 四个订单构造点；`payWith` 与 `Budget.currency` 同币 | `orderCurrencyFor(payChoice, participant, market)`（唯一产生点）；四处构造点共用同一局部量；`new Budget(budget, orderCurrency)` 原样 |
| 3 | 预算/可负担量/冻结/运费按买方支付币、经同一份估值折算；禁两次折算/禁不同实例/禁把本格币可花额当买方币 | `amountInPayCurrency` + `BuySlot.payAmountOf`（两个唯一拼写点）；估值实例来自 `clearOncePerCycle:1313` 的同一份 |
| 4 | 不可成交 ⇒ 具名归因，不得静默 1:1 或静默退回本格币 | 订单侧 `MARKET_PAY_CURRENCY_UNVALUED` + 本单不生成；结算侧既有 `PRICE_LIMIT`/`NO_BUDGET`/`MARKET_CURRENCY_VALUE_REFUSED`；运费第二道 `MARKET_FREIGHT_CURRENCY_UNVALUED` |
| 5 | 卖方侧不分道；接受集恒真；收货币 = 买方支付币 | `acceptsCurrency` 未动；钱腿仍 `Map.of(buy.currency, payment)` |
| 6 | 确定性 I7；保序表用 `LinkedHashMap` + `Collections.unmodifiableMap`；禁 `Map.copyOf`/`Set.copyOf` | `MarketPayChoice`（逐户选币是**内容**函数，与迭代序无关；遍历序 = hex (q,r) → 行序）；新增代码零 `copyOf` |
| 7 | 守恒：货币腿/税腿/运费腿口径不变，只改"用哪种币付" | 唯一写口 `applyTransfer`、三层税 `taxesFor`、运费费率/分摊比例全部原样；只把运费量纲换成支付币（P-T4 已冻结 `freightCurrency ≡ paymentCurrency`） |
