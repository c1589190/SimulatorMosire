# 3c 订单可选币 —— 实现架构账本（写码 Agent）

> **依据（约束设计书）**：`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md`
> §2.1（市场层冻结口径）、§2.6（一 tick 次序）、§3（I-C2/I-C9/I-C10）、§5.2（N1/N5）、§6.2 V-1；
> `docs/superpowers/reports/2026-10-10-port-merchant-consistency-check.md` §16.5 / §16.5.1；
> `docs/superpowers/specs/2026-10-09-household-currency-acceptance-ruling.md` §1.4/§3.1（E 批 R1/R3）；
> 口岸设计书 `2026-10-09-port-policy-and-zone-efficiency-design.md` §16.5、§14（P-T1e 币种挂单过滤口径）。
> **基线**：工作树 `4b753495`（P-T1b 之后、未提交任何东西）。
> **本批范围**：机制 + 缺省中性。**不做**：P-T5 民间 FX/自动换汇、P-T1e 币种挂单过滤、P-T1d 采购优先级、M1–M5。

---

## 1. 关键调查结论（file:line 为**本批改动前**的行号，已逐条核过）

| # | 事实（改动前） | 结论 | 影响 |
|---|---|---|---|
| C1 | `MarketSettlement:1418` `new BuySlot(order, buyer, hex, region, market.numeraire())` | 买方支付币 = **本格计价币**，`order.payWith` 零读取者 | 槽位取币必须改从订单 |
| C2 | `MarketSettlement:7540` `this.receiveCurrency = market.numeraire()` | 卖方收款币 = **本格计价币**，`order.receiveWith` 零读取者；`receiveCurrency` 全仓零读取者 | 同上；且它今天**没有消费者** ⇒ 必须是"接受集"口径才不空转 |
| C3 | 四个订单构造点 `:1933 / :2018-2019 / :2102-2103 / :2165` 硬写 `SILVER_SPECIE`（`InstrumentId`） | 订单带的是**工具**身份，而结算/账/估值全是 `CurrencyId` | 两者必须择一：改订单字段为 `CurrencyId`（本批选它） |
| C4 | `:1941 / :2060` `spendableMoneyOf(round, participant, market.numeraire())` | 预算按**本格币**算（与 C1 同源） | 改成按订单币算（缺省同值） |
| C5 | `:4590-4601 / :4650`（改前 `:4266` 一族）`SellSlot priceReference = sells.get(0)` | `matchRoute` 用**一只代表槽位**承担整条路线的"钱的价" ⇒ 隐含"同一路线卖方同币" | 计划 §2.1 要求放宽；改为逐槽位读 |
| C6 | `:5010 / :5077 / :5082` 用 `sell.market.numeraire()` 作"卖方自己的钱" | E 批口径：卖方按**自己的货币**估值（R1/R3），保留价 `reservationMicro` 是**微本格计价币** | 这是**估值锚**，不是收款币 ⇒ 本批**不动**（见 §3 判断 3） |
| C7 | `:2384` 买冻结按 `(buyer, currency)` 分组、`:6090` `payableMoneyOf` 读 `buy.currency` | 冻结/可付已按槽位币种 | 槽位币改为订单值后自动跟随，无需再改 |
| C8 | `MarketRound` 无 `moneyInstruments` 表；`MoneyVocabulary` 是**进程级**静态门面 | 走"把工具表送进轮"做映射会把币种真值变成两处（工具 + 币种） | 本批**不引入**工具→币种映射（见 §3 判断 1） |
| C9 | `:4652/:4719/:4724`（P-T4 后）= 报告里 `Fill.unitCurrency` + `landedUnitPriceMilli` | P-T4 已将异币相加改具名排除（`MarketReport:650-655`） | §16.5.1 的 D-2 已关闭，本批不再动 |
| C10 | `simos-economy/src/test/.../MarketRegulationTest.java:275,280` 引用 `MarketUnfilledReason.REGULATION_QUOTA` | 该档已由 P-T1c 删除 | **本批改动前 `simos-economy` 测试树就不编译**（测试 Agent 的输入，见 §5） |

---

## 2. 实现架构（落在哪、数据怎么流）

### 2.1 API 面（`simos-economy-api`，订单是**唯一**币种真值）

| 类型 | 改动 |
|---|---|
| `Budget` | `InstrumentId instrument` → **`CurrencyId currency`**；删掉零调用者的 `Budget.silver(...)`（它只是"silver-specie"的第二次拼写） |
| `BuyOrder` | `InstrumentId payWith` → **`CurrencyId payWith`**；构造期不变量仍为 `payWith == budget.currency()`（同一事实不许两处拼写，且它**强制**生成方按订单币算预算） |
| `SellOrder` | `InstrumentId receiveWith` → **`CurrencyId receiveWith`**；语义冻结为"我接受哪种币（接受集）" |
| `MarketUnfilledReason` | 末尾新增 `CURRENCY_NOT_ACCEPTED("currency_not_accepted")`（候选空集的具名归因） |

★ **为什么不改 `MarketNode.receiveWith`（区级 `InstrumentId` 占位）**：它是**区拓扑**字段（`MarketTopology`/`MarketTopologyBook`），
自述"全仓零读取者"，不属订单路径；本批不动它，记在 §5 遗留（P-T1e 收口时可一并退役）。

### 2.2 数据流（订单币的**唯一产生点 → 唯一消费链**）

```
订单生成（自动单 ordersFor / 授权单 planGovMandateOrders）
  orderCurrency = orderCurrencyFor(market)          ← 缺省唯一拼写点（本批恒 = market.numeraire()）
  budget        = spendableMoneyOf(round, participant, orderCurrency)   ← 预算按订单币
  SellOrder(..., receiveWith = orderCurrency)  /  BuyOrder(..., Budget(budget, orderCurrency), payWith = orderCurrency)
        ↓
槽位（BuySlot.currency = order.payWith()；SellSlot.receiveCurrency = order.receiveWith()）  ← 槽位不再回读本格计价币
        ↓
撮合：冻结轴 (buyer,currency) / payableMoneyOf / affordableQuantity / exactAffordableUpTo   （全按 buy.currency，原样）
        ↓
成交裁决 settlementUnitPrice（三条腿唯一拼写点）
  ① 实物信用腿：币种维为空，直接放行
  ② ★ 新增：!acceptsCurrency(sell, buy.currency) ⇒ 具名 CURRENCY_NOT_ACCEPTED + DEBUG + 不落账
  ③ 同币（buy.currency == sell.market.numeraire()）⇒ 原价原样（E3/N2 结构性保证）
  ④ 异币 ⇒ CurrencyValuation 估值折算（锚 = 卖方本格计价币）
        ↓
钱腿 Map.of(buy.currency, payment)（原样）＋ 三层税（P-T1b 口径，原样）＋ 报告/日志带币种（P-T4，原样）
```

### 2.3 文件清单与关键方法

| 文件 | 落点 |
|---|---|
| `simos-economy-api/.../market/{Budget,BuyOrder,SellOrder,MarketUnfilledReason}.java` | 字段类型 + 具名档（见 2.1） |
| `simos-economy/.../time/MarketSettlement.java` | `orderCurrencyFor`（缺省唯一拼写点）；`BuySlot`/`SellSlot` 构造期取币；4 个构造点改用 `orderCurrency`；`acceptsCurrency`（P-T1e 唯一拼写点）；`refuseUnacceptedCurrency`（具名落点）；`worstBuyerUnitPrice`（"单价所属币"逐槽位读口）；`diagnosedBudgetOf`（诊断口径可花额）；两处 DEBUG/INFO 日志加 `sellerAccepts`；删 `SILVER_SPECIE`/两个 import |
| `simos-app/.../gui/ApiViews.java` | 两处**过时文案**："本层市场只收 silver-specie 单一工具（见 MarketSettlement 的硬编码）"已与代码不符 ⇒ 改为 3c 口径（这是**读数归因字符串**，非数值） |

---

## 3. 关键判断（为什么这样拆；推翻过什么）

1. **订单字段改 `CurrencyId`，而不是"把 `moneyInstruments` 送进轮做映射"。**
   钱在账上、在 `Transfer`、在结算钱腿、在 `CurrencyValuation` 里**全是 `CurrencyId`**；`InstrumentId` 今天**没有任何余额表**
   读它（只有 `BuyOrder` 的构造期守卫读过），映到币种只会造出"工具真值 + 币种真值"两处。
   ⇒ 订单路径只留一处币种真值；工具维仍归 `EconomyData.moneyInstruments`（铸熔/兑现属 M4+）。
   ★ 代价如实记：`Budget.instrument()` 是 M1.1 的公开读口，本批**收窄**为 `currency()`（全仓 main 只有 `BuyOrder` 一处读它，测试零引用）。

2. **不引入"选币策略"抽象（策略接口/注入位）。** 计划 §2.4/§2.3 的选币（购买力比较、弱→强）是 **P-T5**；
   本批只在**订单生成**留一个方法 `orderCurrencyFor(market)`：它是订单币的**唯一产生点**，本批恒返回本格计价币。
   ⇒ "订单不带币 ⇒ 本格计价币"落在**一处**，且**结构上**保证：除它之外没有任何代码能给出订单币。

3. **估值锚 = 卖方的本格计价币，收款币只决定"接受集"（★ 本批最关键的判断）。**
   计划 §2.1 同时冻结了两件事：①"订单可带收/付币"；②"`Market.numeraire` **不动**（一格一张价表、一个尺度）"。
   加上 E 批 R1/R3（家户按**自己的货币**估值、本币对自己 1:1）与 `SellSlot.reservationMicro` 的量纲（**微本格计价币**），
   可推出：**单价与保留价都在本格计价币上**，收款币改口径就会把"保留价"和"单价"的量纲拆开（1:1 静默错账）。
   ⇒ `receiveWith` 的准确语义 = "这张挂单**接受**哪种钱"（用户 2026-10-10 裁定：挂单级禁入/禁出，"有一方不给过就不过"），
   由 `acceptsCurrency` 消费；`settlementUnitPrice`/`currencySettlement` 的锚**照旧**是本格计价币。
   ★ 我曾考虑"收款币 = 卖方的钱（估值锚）"这条路线，**推翻**：它与 §2.1 的价格尺度冻结、E 批 R1/R3、保留价量纲三处冲突。

4. **候选空集的两半分开处置。** 卖方一半（`acceptsCurrency`）= 本批**恒真**（P-T1e 尚未落地，计划 §2.1 冻结"接受集 = 全币"）；
   买方一半（持有/可花）= 既有预算/冻结/信用路径，具名档沿用 `NO_BUDGET`/`NO_LENDABLE_MONEY`，**不新造第二档**。
   卖方一半的具名档 `CURRENCY_NOT_ACCEPTED` 本批**不可产出**（刻意的：一产出就等于给旧世界加新限制，违反 I-C2）；
   P-T1e 填实 `acceptsCurrency` 后，归因/DEBUG/不落账**自动生效**，判定内容与判定后果分写。

5. **`matchRoute` 的合成规则取"最不利"（折算后单价最高），并**逐槽位**读"单价所属币"。**
   改前用 `sells.get(0)` 一只代表槽位；放宽后逐槽位折算，合成取最不利（与 `affordableQuantity` 的"保守少买"同取向），
   说不出价的槽位（`-1`）不参与（否则会把整条路线判死）。
   ★ 已知取舍：真出现"同一路线混合币"的卖方组时，取最不利可能把某买方记为 `PRICE_LIMIT`，而它本可与更便宜的卖方成交
   —— 逐档配对留给 P-T1e/P-T5（改 `worstBuyerUnitPrice` 一处 + 两处调用点）。本批路线上的卖方**恒同格同价表**（`activeSellsAtHex`），
   ⇒ 逐槽位结果逐值相同 ⇒ **取最不利 = 取任一个 = 改前值**。

6. **诊断口径（"为什么不买"）也按订单币读**（`diagnosedBudgetOf`）：有订单按订单币、无订单退回本格计价币、混合币取最大者
   （禁跨币求和，I-C10）。这一项只写读数不写账。

7. **I7（确定性）**：本批新增的两处遍历（`worstBuyerUnitPrice` 取 max、`diagnosedBudgetOf` 取 max）都是**可交换的纯函数**，
   与迭代序无关；**没有**新增任何 `Map`/`Set`，也**没有**用 `Map.copyOf`/`Set.copyOf`。

8. **日志（§一.9）**：新增一条 DEBUG `MARKET_CURRENCY_NOT_ACCEPTED`（本批不可达，P-T1e 的"为什么"）；
   既有 INFO `MARKET_FOREIGN_CURRENCY_FILL`（条件 = 买方支付币 ≠ 本格计价币）与 TRACE `MARKET_FILL`（P-T4 已带
   `unitCurrency`/`paymentCurrency`）**原样承担**"异币读数在 INFO/DEBUG、逐笔在 TRACE"；
   本批只给两条 DEBUG 的 `MARKET_CURRENCY_VALUE_REFUSED`/`MARKET_FOREIGN_CURRENCY_FILL` payload 各加一栏
   `sellerAccepts`（挂单声明的收款币），使"挂单不收支币"与"支币折不回本币"两种拒因可分辨。日志不改任何状态与公式。

---

## 4. "订单不带币 ⇒ 逐值不变"的论证（I-C2 / N1）

逐条对齐"改动前表达式 → 改动后表达式"的**取值**（不是形状）：

| 位置 | 改动前 | 改动后 | 取值 |
|---|---|---|---|
| 买方支付币（槽位） | `market.numeraire()`（:1418） | `order.payWith()` = `orderCurrencyFor(market)` = `market.numeraire()` | **同值** |
| 卖方收款币（槽位） | `market.numeraire()`（:7540） | `order.receiveWith()` = `orderCurrencyFor(market)` = `market.numeraire()` | **同值** |
| 预算（自动单 / 授权单） | `spendableMoneyOf(..., market.numeraire())` | `spendableMoneyOf(..., orderCurrency)` = 同上 | **同值** |
| 订单的 `Budget` | `new Budget(budget, SILVER_SPECIE)` | `new Budget(budget, orderCurrency)` | 构造期守卫都过；`amountMilli` 未变 ⇒ **仅 `currency()` 变，无读者差异** |
| 冻结轴 / 可付额 / 配给 | 已按 `buy.currency` | 同（值同上） | **同值** |
| `matchRoute` 折算 | `convertedBuyerUnitPrice(ctx, buy, sells.get(0), unitPrice)` | `max_selling convertedBuyerUnitPrice(ctx, buy, sell, unitPrice)` | 见 §3 判断 5：路线内卖方同格同价表 ⇒ 逐槽位结果相同 ⇒ **同值**（全组说不出价时两侧同为 `-1`） |
| 空档（无剩余卖方的档） | 不 break，进下一档 | 新增显式 `continue`（同一路径） | **同值**（且该分支理论上不可达：`ordered` 只装本轮开始时 `remaining>0` 的槽位，每档只消耗自己那几只） |
| 诊断可花额 | `spendableMoneyOf(..., market.numeraire())` ×2 | `diagnosedBudgetOf(...)`（有订单按订单币、无订单按本格币） | **同值**（订单币恒 = 本格币）；第二处由"重算同一个表达式"改为"复用同一局部量" ⇒ **同值** |
| 候选裁决 | 无币种闸 | `acceptsCurrency` 恒真 ⇒ 不进分支 | **不触发** |
| 新增枚举档 | — | 只在被产出时出现在读数里 | 本批**不产出**；`unfilledReasonCounts()` 由**实际条目**建表 ⇒ 无零计数的空键 |

⇒ **旧世界（谁都没指定币）逐值不变**；机制的存在只体现在"订单能带币、槽位从订单取、预算按订单币、接受集有判定入口"这四件事上。

---

## 5. 与约束设计书的不一致 / 遗留（如实记）

1. **`CURRENCY_NOT_ACCEPTED` 本批无产出者**（设计上刻意：接受集 = 全币）。已在该档与 `acceptsCurrency` 的注里写明"P-T1e 落地即产出"。
2. **非本格币订单的"跨币种折算面"未做**（P-T5 的活）：若将来 `orderCurrencyFor` 返回非本格币，以下位置仍需**按同一份
   `CurrencyValuation`** 折算（本批只把"取哪种钱"改对，不装假 1:1 折算器）：`ordersFor` 的 `cashAffordable`、
   `planGovMandateOrders` 的 `cashAffordable`、`requestedMoneyOf`（限价 → 冻结额）、`affordableQuantity` 的
   `unitPrice + route.freightPerUnit`（运费是计价货币）、`totalCostAtMost` 的运费腿、`MarketReport.Fill.freightPerUnitMilli`。
   ★ 本批**结构上不可能**出现非本格币订单（唯一产生点是 `orderCurrencyFor`），所以不存在"静默 1:1"的现实路径。
3. **`MarketNode.receiveWith`（区级 `InstrumentId` 占位、零读取者）未退役**：不属订单路径，留给 P-T1e 一并收口
   （它是"币种/工具身份"的第三处拼写）。
4. **`BuyerOutcome.spendableMoneyMilli` 无币种列**：本批诊断口径已按订单币读，但该字段是**单值读数**（P-T4 未给它加币种栏）；
   混合币世界里它是"上界读数"（I-C10 的同族缺口），留给 P-T4 后续或 P-T5。
5. **信用放贷头寸只按出借人的本格计价币挂**（`MarketSettlement:3107` `market.numeraire()`）：买方若用非本格币付款，
   货币信用腿靠 `candidate.currency.equals(buy.currency)` 自然落空（不静默错付）——P-T5 若要让信用跨币，需另裁。
6. **`simos-economy` 测试树在本批之前就已不编译**（`MarketRegulationTest` 引用已删的 `REGULATION_QUOTA`）⇒ 本批无法用
   "既有测试仍绿"当回归网，**开发期没有行为回归保护**（AGENTS §三.0 已接受的对冲）。
7. **本批未跑任何 `test`/`verify`/真实 world**（派单纪律：只到编译过）。行为等价性由 §4 的**逐表达式论证**承担，
   真实数值证据留给测试 Agent / 控制方的总验收。

---

## 6. 门禁证据（真实命令与结果，2026-10-10）

```
① tools/mvn-lock.sh -q spotless:apply                      → exit 0（无输出）
② tools/mvn-lock.sh -DskipTests compile                    → BUILD SUCCESS / 16 模块全 SUCCESS / exit 0
③ rm -rf {simos-economy-api,simos-economy,simos-app}/target/classes
   tools/mvn-lock.sh -DskipTests compile                    → "Compiling 114/76/25/187/28/23/327 source files…" / BUILD SUCCESS / exit 0
```
★ ③ 是**从零重编**（先删 target/classes），用来对冲"增量编译不可全信"（AGENTS §七）：
它证明本批落盘的字节**真的能编译**，不是拿了上一轮的 class 当绿。
★ **未跑**：`test` / `verify` / `package` / 真实 world（派单纪律；见 §5.7）。
★ 影响面：全仓只有本批 6 个 main 文件被改（`git status --porcelain` 逐条核对过，全仓工具跑完没有溢出改动）。

## 7. 给 P-T1e / P-T5 的接缝（唯一拼写点）

| 接什么 | 改哪里（**只此一处**） | 不用动 |
|---|---|---|
| **P-T1e 币种挂单过滤** | `MarketSettlement.acceptsCurrency(SellSlot, CurrencyId)` 的方法体（把 `return true` 换成挂单级禁入/禁出判定） | 调用点（`settlementUnitPrice`，三条腿共用）、归因（`refuseUnacceptedCurrency`）、日志、结算/税 |
| **P-T5 自动选币** | `MarketSettlement.orderCurrencyFor(Market)`（签名届时带 `round`/`participant` 读持币与估值） | 两处订单生成调用点、槽位/预算/冻结/结算全链（已按订单币） |
| **P-T1e 混合币路线的逐档配对** | `MarketSettlement.worstBuyerUnitPrice` 的合成规则（+ 其两处调用点） | 落账裁决（`settlementUnitPrice` 早已逐槽位判） |
