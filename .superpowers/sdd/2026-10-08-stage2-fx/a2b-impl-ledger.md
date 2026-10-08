# A2b 实现架构账本：5 处跨币种 1:1 求和收口 + 读口 displayName + F5 端到端

> 责任区：阶段 2 第三批 **A2b**（约束设计书 `docs/superpowers/specs/2026-10-08-currency-exchange-stage2-design.md`
> §3.5/§3.6、§5 I17/I19/I24、§6.1 F5、§7 M1/M7、§8）。
> 写代理：办事子 Agent（一个责任区一个写代理）。只写生产代码、只 `compile`、不写/不跑测试、不 `git commit`。
> 交账时间：2026-10-08。**状态：编译 exit=0；探针 32 条断言全过（exit=0），含 F5 端到端 + M7 五处证据 + 读口。**

---

## 0. 任务板

`team_task_get task-13` 返回 `agent "…" is not a member of an active Agent Team` ⇒ 按任务书 §【第一步】的
例外条款**未 claim、未 complete**，直接按任务书执行（见 §7 交账项 ⑧）。

---

## 1. 关键调查结论（`file:line` → 结论 → 影响）

| # | 证据（开工时实测） | 结论 | 对实现的影响 |
|---|---|---|---|
| 1 | `MarketSettlement.executeTrade`（A2a 落地处，本批在 `:3755` 起）第一句已是异币具名拒；`SellSlot.receiveCurrency`（`:5600` 附近）= 卖方格 `Market.numeraire()` | ① **现金成交腿已闭合**（A2a），复核通过 | 不改语义，只把校验抽成唯一拼写点 |
| 2 | ★★ `MarketSettlement.moneyCreditForBuy:1968-1974`：`sell = pools.bestCashSeller(regionId, commodity)` **不校验币种**；`executeMoneyCredit:2086/:2111` 的 `LOAN_PRINCIPAL` 与货款腿都用 `Map.of(buy.currency, payment)` | ★ **A2a 只堵了现金腿**：信用（借来的钱）那一条付款腿原样可异币静默 1:1 ⇒ ① 其实**未完全闭合** | 新增唯一拼写点 `rejectCurrencyMismatch`，现金腿 + 信用腿共用；信用腿命中即 fail-closed 停做 |
| 3 | `MarketSettlement.executeGoodsCredit:2168`：只有商品腿（`LOAN_PRINCIPAL` = 商品），**无货币腿** | 「借实物」不是货币支付腿 ⇒ I19 不适用于它（但见 §6 发现 1） | 不拦（拦了会改 I19 之外的语义）；如实记账 |
| 4 | `GovBudgetExecutionBridge.treasuryAvailable`（`:1310` 附近）只读 `AvailableStock.available(view, SILVER)`；`ResourceVector.value()` 是无名算式（grain+cloth+silver） | ② 货币腿**本来就只装银**，从未把铜算进预算帽；真正的缺陷是**口径没有名字**、被排除的外币**一个字都不记** | 算式具名 `valueInBookCurrency()` + `bookCurrency()`；国库外币逐币种具名 DEBUG |
| 5 | `GovBudgetExecutionBridge.resourceVectorOf(HouseholdPeriodicAdjustment)`：非银货币腿 ⇒ `contractFailure("unknown-currency")` | ② 的请求侧**已经是具名拒收** | 保留并在类注里写明这是 I24 的"具名拒收"那一臂 |
| 6 | `OperatorSettlement:254-259`（改前）`case DebtUnit.Money ignored -> cashOf(household, householdMoney)`；`cashOf` 把该户**全部币种**求和 | ③ 债务压力把"铜也算成能还银债的钱" | 改成 `case DebtUnit.Money money -> cashOf(household, money.currency(), …)`（**逐币种分别算**） |
| 7 | `DebtUnit.Money(CurrencyId currency)`（`simos-economy-api/.../debt/DebtUnit.java`）**带币种** | ③ 能分别算 ⇒ 不需要声明口径、更不需要拒收 | 直接按该债的币种读现金 |
| 8 | `OperatorSettlement:272`（改前）`cash = cashOf(household, householdMoney)`，只用于 `cash > 0` 的恢复判据（`:341/:350`），并写进 `OperatorCondition.cash`（**已落状态**） | ③ 的第二个求和点：一个"有没有钱"的布尔判据被写成了一个跨币种金额 | 口径 = 该 unit 所在格市场计价币（本币）；说不出本币 ⇒ 记 0 + 具名 DEBUG（不求和兜底）；字段类型与状态形状**不变** |
| 9 | `MerchantSettlement.feeRevenueOf(cycle, principalActor)`（改前 `:341-357`）把 `transfer.money().values()` 全加总；调用点 `:412` 在 `numeraire` 算出**之前** | ④ 商号运费实收跨币种 1:1；且收入/成本口径可能不是同一种钱 | 签名加 `bookCurrency`（本币 = `homeHex` 市场计价币；说不出 ⇒ 规范串最小的实收币种）；调用点提前到 `numeraire` 之后 |
| 10 | `EnterpriseProfitBook.addRevenue/addCost`（改前 `:662-700`）**收了 `numeraire` 参数却没用**，注释自认"跨币种求和的量纲缺口见收口报告" | ⑤ 利润账收入/成本跨币种 1:1，与类注承诺的"取规范串最小的币种"不符 | 预扫逐组织定唯一本币（`bookCurrenciesOf`），只认它；其他币种具名 DEBUG 排除 |
| 11 | `ApiViews.currencyDefViews():4285`（改前）只发 `id`/`scale`；`moneyInstrumentViews():4303` 只发 `id/currency/kind/issuer/redeemer`；两者都走**静态门面** `MoneyVocabulary.allCurrencyDefs()` | 读口：A1 加了 `displayName` 但**读口一直没发** ⇒ 改名（I16/F1）在 GUI/MCP 上看不见；门面是**进程级**的（A1 账本 §4-1 自认） | 补 `displayName`；来源改为世界状态（`EconomyData.currencies()/moneyInstruments()`）；`moneyLayers` 同步改（否则同一份响应里两栏可能来自不同世界） |
| 12 | `MarketTopologyBook.sameNumeraire:206-219`：**同币即同区**（D-027），不同币 ⇒ 退回"城市节点 + tier 半径" | 多计价币世界会**改变市场拓扑装配路径**（不是 bug，是 D-027 的既定回退） | 写进 `economy.SetMarketNumeraire` 的命令面说明与日志 |
| 13 | 小世界 19 格逐格事实（探针实测，`/tmp/a2b-probe/hexdiag.log`）：19 格全有市场、`prices={grain=1,cloth=5,fiber=1,tool=20,iron=10}`、每格 9 个非排除家户行；产业产出集合 = `{cloth,fiber,grain}`（0_0/0_2 另加 `tool`）⇒ **没有任何一格产铁**；世界持有：iron 7 户 / tool 2 户 / cloth 2 户 | F5 的"两计价币世界"要**逼成唯一形态**，铁是最好的靶子（无生产 ⇒ 搬空即无供给） | F5 场景用"全世界的铁只在银计价卖方手里 + 铜计价买方要买铁" |

---

## 2. 每处求和点：改前口径 / 改后口径 / 为什么这样改

| # | 位置 | 改前口径 | 改后口径 | 为什么 |
|---|---|---|---|---|
| ① | 市场支付腿 `MarketSettlement` | 现金腿（A2a）已具名拒；**信用腿（借来的钱）原样 `buy.currency` 1:1** | 唯一拼写点 `rejectCurrencyMismatch(...)`（`:3698`）：`sell.receiveCurrency != buy.currency` ⇒ 买卖两侧 `CURRENCY_MISMATCH` + INFO `MARKET_CURRENCY_MISMATCH_REJECTED`（新增 `leg=cash|money-credit`）+ 返回 true ⇒ **不落任何账**；现金腿 `:3763`、信用腿 `:1974` 共用 | 商品面上"钱从买方到卖方"有两条腿、用的是同一个 `buy.currency`，而"卖方要收哪种钱"只有一个来源 ⇒ 校验必须一处；A2a 堵一条漏一条正是本批实测发现的 |
| ② | 预算帽 `GovBudgetExecutionBridge` | 无名 `ResourceVector.value()` = grain+cloth+silver（毫 1:1）；国库外币**既不进算式也不记任何字**（静默排除） | `valueInBookCurrency()`（`:1038`，具名）+ `bookCurrency()`（`:1043`，当场可读本币=银）+ 常量 `BOOK_CURRENCY`（`:109`）；`treasuryAvailable` 逐币种列出被排除的外币并发 DEBUG `GOV_BUDGET_FOREIGN_CURRENCY_EXCLUDED`（`:1353`）；请求侧非本币仍是**具名拒收** `unknown-currency` | 设计书 §3.6 明确"不能分别算的（如预算帽）必须具名拒收或**显式声明单一币种口径并写进注释与日志**"。这里选择后者：cap 是一个标量、没有汇率可折算；且**排除必须是可观测的**（静默排除就是 M7 的形态）。后果 fail-closed：国库只有外币 ⇒ 本币可用 0 ⇒ 类别拿不到额度，不拿外币当本币花 |
| ③ | 债务压力 `OperatorSettlement` | 债务压力：`case DebtUnit.Money ignored -> cashOf(household, householdMoney)` = Σ**全部币种**；条件字段 `cash` 同样是 Σ全部币种 | 债务压力：`case DebtUnit.Money money -> cashOf(household, money.currency(), householdMoney)`（逐币种）；`cash` = 该 unit 所在格市场计价币（本币）余额；说不出本币 ⇒ 0 + DEBUG `OPERATOR_FOREIGN_CURRENCY_EXCLUDED`（`:534`） | `DebtUnit.Money` 自带币种 ⇒ **能分别算就分别算**（设计书第一选择）；`cash` 只被用作 `>0` 的"有没有钱"判据 ⇒ 按本币问才对（外币不能垫本币的下一周期投入）。`OperatorCondition.cash` 的类型/状态形状不变（只改口径） |
| ④ | 商号 `MerchantSettlement` | `feeRevenueOf(cycle, actor)` = Σ该商号全部 CARRIER_FEE 转移的**全部币种**；`numeraire` 在调用点之后才算出来 | `feeRevenueOf(cycle, actor, bookCurrency)`（`:353`）：只计本币腿；其他币种逐笔 DEBUG `MERCHANT_FEE_FOREIGN_CURRENCY_EXCLUDED`（`:401`）；本币 = `homeHex` 市场计价币，说不出 ⇒ 规范串最小的实收币种（`canonicalFeeCurrency:381`，确定性） | 商号的收入/成本/利润是**一个标量**（写回 `MerchantFirm`），一个标量只能有一种币的口径 ⇒ 口径由调用方给出、由方法执行；工资只从该币种账户付（`accountMoney(..., numeraire)`）⇒ `profit = revenue − costPaid` 不再是两种钱相减 |
| ⑤ | 利润账 `EnterpriseProfitBook` | `addRevenue/addCost` 把 money 表里**每个币种**加总（参数 `numeraire` 收了但没用） | 预扫 `bookCurrenciesOf(...)`（`:688`）逐组织定唯一本币（本地市场计价币；说不出 ⇒ 该组织本周期货币腿里规范串最小者）；`addRevenue:730` / `addCost:749` 只认它，其他币种具名 DEBUG `ECONOMY_ENTERPRISE_FOREIGN_CURRENCY_EXCLUDED`（`:769`） | 类注早就承诺"取规范串最小的币种、混合币种不求和"，但代码没做 ⇒ 本批把承诺变成行为；改前"逐条腿各挑各的"会在同一组织身上混出两种钱的读数，故必须**先扫一遍定唯一本币** |

★ 五处都没有引入任何"伪价格/伪汇率"：**能分别算的分别算（③），算不出来的显式声明单一币种口径并记日志（②④⑤），请求侧非法币种具名拒收（②）**。

---

## 3. 实现架构（本批新增/改动的形状）

### 3.1 新增命令 `economy.SetMarketNumeraire`（F5 的最小前置；A2a 账本 BLOCKED-1 的建议 1）

```
economy.SetMarketNumeraire {q, r, numeraire}
  handler：simos-economy/.../spi/EconomySetMarketNumeraireHandler.java（新文件，implements GmOnlyCommand）
  注册  ：Shell:655（连同 import:85）
  载荷提示：CatalogTool PAYLOAD_HINTS:405（★ 缺则 Shell.start 当场抛「已注册命令类型未登记载荷提示」——A2a §1-8 的教训）
  payloadHint 之外**没有**新工具：GM 走通用 simos.command.submit（与 economy.SetMarketPrice 同形制）
  语义：
    ① 只写 markets 一个组件的**一格**：换 numeraire，prices **逐值原样保留**（价格是"毫计价货币/商品单位"⇒ 数值按新币读）
    ② 不折算任何余额、不折算任何价格（I17：世界没有汇率）—— 写进 INFO 日志的 note 与命令面说明
    ③ 拒因具名：market-missing / unknown-currency（查**世界状态** currencies，不查进程内门面）/ numeraire-unchanged
    ④ INFO MARKET_NUMERAIRE_SET（带 worldNumeraires 集合）+ DEBUG MARKET_NUMERAIRE_CRITERIA
  已知后果（写进类注与 catalog 提示）：世界据此出现两种计价币 ⇒ D-027「同币即同区」失效、拓扑退回城市半径路径；
    任何异币成交尝试一律具名拒（家户要用异币买东西必须先走市场 FX 或政府外汇窗口）
```

### 3.2 唯一拼写点（本批的两处"收口"）

| 拼写点 | 位置 | 谁用 |
|---|---|---|
| `rejectCurrencyMismatch(ctx, buy, sell, quantity, leg)` | `MarketSettlement:3698` | 现金腿 `executeTrade:3763`、信用腿 `moneyCreditForBuy:1974` |
| `logForeignCurrenciesExcluded(...)` | `OperatorSettlement:534` / `GovBudgetExecutionBridge:1353` / `MerchantSettlement:401` / `EnterpriseProfitBook:769` | 各自的单一币种口径 |

> ★ 四个 `logForeignCurrenciesExcluded` **不是**同一个方法：各自的领域对象与日志来源不同（operator-state / daily-loop /
> organization），共用的是**同一条口径与同一套字段**（`bookCurrency` + `excluded` + `note`）。收成一个 util 会越模块边界
> （app 与 economy 各一半），故按领域各留一处、字段名对齐。

### 3.3 数据流

```
读口：EconomyData.currencies()/moneyInstruments()（状态权威）
        └─► ApiViews.currencyDefViews(data)（补 displayName）/ moneyInstrumentViews(data) / moneyLayers(data, …)
            └─► GET /api/economy/hex、GET /api/economy/overview（GUI 与 MCP 读工具同源）

F5 世界：economy.SetMarketNumeraire（GM，真命令）⇒ EconomyData.markets 一格 numeraire=copper
        ⇒ BuySlot.currency=copper（买方格）/ SellSlot.receiveCurrency=silver（卖方格）
        ⇒ executeTrade 第一句 rejectCurrencyMismatch ⇒ 具名拒 + 0 成交量 + 账户未动
```

---

## 4. 偏离约束设计书之处及原因

| # | 设计书原文 | 实际做法 | 原因 |
|---|---|---|---|
| 1 | §3.6「5 处全部改为逐币种分别算」 | ③ 逐币种分别算；②④⑤ **显式声明单一币种口径 + 日志**（不是分别算） | §3.6 自己给了第二条路（"不能分别算的……必须具名拒收或显式声明'只按本币计'并在注释与日志里写清"）：② 的 cap 是一个标量、④⑤ 的利润是一个标量，没有汇率（I17）就不可能分别算成同一个标量 |
| 2 | §3.6 未提"① 的信用腿" | ① 额外堵了 `moneyCreditForBuy` 的付款腿 | 复核发现 A2a 只堵了现金腿；设计书 §3.5/I19 的语义是"异币支付必须具名拒"，借来的钱也是支付 |
| 3 | §8 表把 A2b 排除在"新增命令面"之外（A2b 允许写 `simos-app/.../app/time/**`；本批还写了 `spi/**`、`Shell`、`CatalogTool`、`gui/ApiViews`） | 新增 `economy.SetMarketNumeraire` + 读口补名 | 任务书 §【本批做什么】2 明确要求改 `ApiViews`；3 明确允许"例如(a)新增 GM 命令 economy.SetMarketNumeraire"。文件所有权清单（任务书 §【文件所有权】）覆盖这三处，且都在 `simos-economy/**` 与 `simos-app/src/main/java/io/mosire/simos/app/**` 之内 |
| 4 | §9-4 / A2a §4-4「实际汇率跨轮滚动窗口留 B」 | 本批未碰（I17 限制仍在） | 任务书 §【明确不做】 |
| 5 | 设计书未提"预算帽价值口径要具名" | 我把 `ResourceVector.value()` **重命名**为 `valueInBookCurrency()` | 只在注释里写口径挡不住"看代码的人以为 1:1 默认"；具名是 I24 在类型上的落点（`GovBudgetExecutionBridge` 无外部调用者，重命名只多改 1 处 `PopulationEconomyTimeParticipant:837`） |

---

## 5. 报告模板（交账）

### 5.1 改动文件（10 个；其中新增 1）

```
新增：
  simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomySetMarketNumeraireHandler.java
修改：
  simos-app/.../app/Shell.java                                   +1 handler 注册（import + 注册）
  simos-app/.../app/tools/read/CatalogTool.java                  +1 命令的 PAYLOAD_HINTS
  simos-app/.../app/gui/ApiViews.java                            currencyDefViews/moneyInstrumentViews 补 displayName + 改读状态；
                                                                 moneyLayers 改读状态；3 处调用点同步
  simos-app/.../app/time/GovBudgetExecutionBridge.java            ★② BOOK_CURRENCY + valueInBookCurrency/bookCurrency
                                                                 + GOV_BUDGET_FOREIGN_CURRENCY_EXCLUDED
  simos-app/.../app/time/PopulationEconomyTimeParticipant.java   value() → valueInBookCurrency()（1 行）
  simos-economy/.../time/OperatorSettlement.java                 ★③ 债务压力逐币种 + cash 本币口径 + 具名排除日志
  simos-economy/.../time/MerchantSettlement.java                 ★④ feeRevenueOf 加 bookCurrency + 具名排除 + 调用点前移
  simos-economy/.../time/EnterpriseProfitBook.java               ★⑤ 逐组织本币预扫 + addRevenue/addCost 单一币种口径
  simos-economy/.../time/MarketSettlement.java                   ★① 唯一拼写点 rejectCurrencyMismatch + 信用腿守卫
```
★ 未碰：任何 `src/test/**`、任何 `pom.xml`、`docs/**`、`.superpowers/**`（只写本账本）。

### 5.2 编译命令与实际结果

| 命令 | 结果 |
|---|---|
| `tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am`（本批**首次**，含未格式化代码） | **exit 0** |
| `tools/mvn-lock.sh -q -o spotless:apply -pl simos-economy-api,simos-economy,simos-app` | exit 0；`git status` 文件集**前后 diff 为空**（没有碰别人文件） |
| `tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am`（格式化后**终局**） | **exit 0** |
| 探针 `/tmp/a2b-probe/A2bProbe.java`（真 Shell、零 register，**同一份字节跑改动前后**） | 改前：**7 条 ✗**（M7 四处 + 读口两处 + F5 命令不存在）；改后：**32 ✓ / 0 ✗ / exit=0** |

★ 未跑 `test`/`verify`/`package`（派单书 §一.5/§三.0；服务在跑、禁覆盖 shaded jar）。**门禁未绿**。
★ 跑 Maven 前每次都 `pgrep -af classworlds.launcher`（本仓无 Maven），并用 `tools/mvn-lock.sh` 串行化。

### 5.3 ★ 会改变数值行为的清单（给测试代理当输入）

1. **`MarketSettlement` 信用腿（借来的钱）**：以前异币也能"借铜付银"（静默 1:1）；现在 ⇒ 具名拒、该买方本轮不再走货币信用。
   **单币世界逐值不变**；多计价币世界会出现新的 `CURRENCY_MISMATCH` 未成交条目（实测：F5 世界 30 天共 **604** 条
   `MARKET_CURRENCY_MISMATCH_REJECTED`，其中铁 4 条、其余为粮/布）。
2. **`OperatorSettlement` 债务压力**：`debtStress` 从"Σ全部币种 ≥ due"变成"**该债币种**的现金 ≥ due"。
   一户只有铜而债是银 ⇒ 以前不算压力、现在算压力（状态机因此可能提前进 SUSPENDED/INEXISTENT 分支）。
3. **`OperatorSettlement` 的 `OperatorCondition.cash`**：从 Σ全部币种变成**本币余额**（该 unit 所在格市场计价币）。
   单币世界逐值不变；多币世界这个读数会变小（外币被排除）。
4. **`MerchantSettlement` 运费实收**：从 Σ全部币种变成**本币**（`homeHex` 市场计价币）。多币世界 ⇒ `revenue` 可能变小，
   进而 `profit` 与 `capacity`/`ruralPenalty`（±5）随之变化。说不出本币时退回"规范串最小的实收币种"（确定性）。
5. **`EnterpriseProfitBook` 收入/成本**：同上（只认逐组织的唯一本币）。多币世界 ⇒ `revenueMilli/costPaidMilli/netMilli` 变化，
   进而 `Book` 读数与排序；`addCostGoods`/`goodsValue` 的"按本地 ref 价折毫"口径**未动**。
6. **预算帽 `GovBudgetExecutionBridge`**：价值算式**数值不变**（货币腿本来就只装银）⇒ ② 是"具名 + 可观测"的收口，
   但 `ResourceVector.value()` 这个**方法名消失**（改叫 `valueInBookCurrency()`）；多币世界的 `GOV_BUDGET_FOREIGN_CURRENCY_EXCLUDED`
   是新日志（每天每 GOV 至多一条，仅在国库确实持有外币时发）。
7. **读口 `currencyDefs` 多一个键 `displayName`**（GUI `/api/economy/hex`、`/api/economy/overview` 与 MCP 同源视图）；
   `moneyInstruments` 形状不变；`moneyLayers` 的来源从进程内门面改为世界状态（同世界同 revision 下逐值相同）。
8. **新命令类型 `economy.SetMarketNumeraire`**（GmOnly）：命令注册面 +1；**没有**新工具 ⇒ GM/决策人桶工具数不变。
   它**只在被调用时**改变世界（不动存量世界 ⇒ 既有场景逐值不变）。
9. **`MarketSettlement` 的 INFO 日志多一个字段 `leg=cash|money-credit`**（事件名不变）。

### 5.4 ★ 会让既有测试失效的清单（给测试代理）

1. **必红（1 条）**：`SimosToolsTest.catalogCoversEveryCommandHandlerImplementation` ——
   `handlerTypesFromSources()` 扫 `*Handler.java`，本批 +1 ⇒ **`hasSize(128)` 必须改成 129**
   （扫描器认 `return TYPE;` + `String TYPE = "…"` 形态，本 handler 正是该形态，已核对正则 `SimosToolsTest:957-985`）。
   `catalog` 的 `types` 与实现面**仍然逐条相等**（无第二处断言）。
2. **应当仍绿（已逐条核对）**：
   - `SimosToolsTest` 的 GM 桶 `hasSize(153)` / 决策人桶 `hasSize(40)`：本批**不加工具** ⇒ 不变；
   - `SimosToolsTest.everyToolClassOnDiskIsRegisteredInSomeBucket` `hasSize(163)`（工具文件数）⇒ 不变；
   - `CatalogVisibilityTest`：用**合成注册面**（13 条）且断言 `payloadHints.size() == REGISTERED.size()` ⇒ 不变；
   - `GuiApiTest.moneyVocabularyIsPublishedThroughTheEconomyReadout`：只断言 `id/scale/kind/currency/issuer/redeemer`
     的**值**，不排除新键 ⇒ 加 `displayName` 不破它（该用例是 A1 唯一单独跑过的一条）；
   - `MoneyIdentityTest`（`allCurrencyDefs()/allInstruments()` 门面）⇒ 门面未改，仍绿；
   - 单币世界的一切经济读数（②的神态③④⑤在单币世界逐值不变）。
3. **可能红（需核，找不到证据但列出来）**：任何对 `/api/economy/hex`、`/api/economy/overview` 响应**逐字节/逐键**比对的
   用例（`currencyDefs` 多一个键）；任何断言 `MerchantSettlement.feeRevenueOf(cycle, actor)` **两参签名**的用例
   （全仓 `src/test` 搜 `feeRevenueOf` = 0 命中）；任何断言 `ResourceVector.value()` 的用例（`src/test` 搜 `ResourceVector` = 0 命中）。

### 5.5 未完成 / 未验证 / BLOCKED

1. **未跑** `test`/`verify`/`package` ⇒ 门禁未绿；上述"应当仍绿"是**静态核对**，不是跑出来的。
2. **B 阶段**（3 区 3 政府、市场区持久化、跨区套利、行政区互斥、GOV 总疆域）与口岸/关税/商品采购窗口/铸币生产方式：
   按设计书 §10 与任务书 §【明确不做】一个字没碰。
3. **`economy.SetMarketNumeraire` 不是 B2 的市场区持久化**：它只改"某一格的计价币"，**不**引入 `zoneId/法定币/官方汇率`
   的持久组件（那是 B2）。它改完之后世界会出现两种计价币 ⇒ **D-027 的同币即同区失效**是已知且刻意的后果（日志具名）。
4. **③ 的端到端日志**只在**关账日**才发（`OperatorSettlement` 只在关账轮更新经营者条件）：探针因此推进到第 30 天
   （实测 `MARKET_SCHEDULE day=30 anyCycleClosed=true`）才拿到 1 条 `OPERATOR_FOREIGN_CURRENCY_EXCLUDED`。
   非关账日跑这段日志是 0 条 —— **不是没实现，是没到关账轮**。
5. **未验证**：跨轮实际汇率滚动窗口（I17 限制，属 B/待裁定）；多币对同时活跃的 FX 簿；`GovFxWindow` 吞吐上限（属 B/阶段 3）。
6. **BLOCKED：无**。A2a 的 BLOCKED-1（F5 端到端）在本批**已解锁并真跑通过**（见 §6）。

---

## 6. 调查中推翻过 / 顺手发现（本批最值钱的三条）

1. ★★ **「借实物」使"异币买不到货"不成立（新发现，未改）**：F5 场景实测 —— 铜计价买方的**现金腿**被具名拒（0 笔铁的现金成交、
   铜一分未动），但它仍通过 `executeGoodsCredit`（**无货币腿**的商品信用）从银计价卖方手里"借"到了铁（`CreditFill unit=Commodity[iron]`，
   第 5 天 400 毫，另有第 3 天一笔更大的）。⇒ **I19 只覆盖支付腿**；"异币家户因此买不到东西"这句话**不成立**（它可以欠实物债）。
   本批**不拦**（拦了会改 I19 之外的语义、且商品信用本身合法）；**报给控制方**：若要"异币不能取得商品"，那是另一条裁定
   （选项：商品信用的债权人必须是本币区、或明写"借实物不受币种约束"）。
2. ★★ **A2a 的 ① 只堵了现金腿**（见 §1 第 2 条）：这是靠"复核是否已闭合"才发现的，**不是**靠读 A2a 账本（它自认已闭合）。
   已按 fail-closed 补上信用腿（`moneyCreditForBuy`），并留下唯一拼写点。
3. ★ **F5 的"逼成唯一形态"配方**（可复用）：小世界里"改一格计价币"**不足以**造出可判定的场景 ——
   ① 该格的本地家户会在市场轮**新生产**商品（实测：清空 -2_0 的 16,250,000 粮后，铜计价买方立刻与**同格铜计价卖方**合法成交）；
   ② 信用（借实物）会让异币买方照样拿到货。
   ⇒ 判定可用的配方是：**选一个"全世界没有任何产业产出"的商品（小世界里 = 铁）**，把它全部搬到"只收银"的卖方手里，
   让铜计价买方对它挂需求 ⇒ 该买方的每一次买盘都**只能**撞上银计价卖方。探针 `A2bProbe.f5` 即此配方。
4. ★ **一天可能有多个市场轮**（`MarketTrigger`：NONE/PERIODIC/CYCLE_CLOSE/LOW_GRAIN_STOCK），而 `MarketReportFeed` 只留
   **最后一轮**：探针实测第 3 天因低库存开轮、第 5 天例行开轮；若按"推进到市场日"读读数，会把上一轮的成交漏掉。
   ⇒ 探针的断言窗口改成"先推进到第 4 天，再只推进一个 tick"，保证报告与账户差分的窗口一致。

---

## 7. 探针（自证；`/tmp`，不进仓库、不进 `src/test`）

- 源码：`/tmp/a2b-probe/A2bProbe.java`（真 `Shell.start` + 真创世 + 真命令，**零 `core.register`**；对"签名会变"的四处走反射，
  故**同一份字节**在改动前后都能跑）。
- 世界诊断（只读，选格用）：`/tmp/a2b-probe/HexDiag.java` / `hexdiag.log`。
- 输出：**改前** `/tmp/a2b-probe/before.log`（7 条 ✗）；**改后终局** `/tmp/a2b-probe/final.log`（32 ✓ / 0 ✗，exit=0）。
- 运行命令：
  ```bash
  CP="$(ls -d $PWD/simos-*/target/classes | tr '\n' ':')$(cat /tmp/a2b-probe/cp-third.txt)"
  javac -nowarn -cp "$CP" -d /tmp/a2b-probe/out /tmp/a2b-probe/A2bProbe.java
  java -Dsimos.app.logLevel=DEBUG -Dsimos.economy.logLevel=DEBUG \
       -cp "/tmp/a2b-probe/out:$CP" io.mosire.simos.app.A2bProbe
  ```

### 7.1 改前 vs 改后（同一份探针字节；逐条对照）

| 判据 | 改前读数（`before.log`） | 改后读数（`final.log`） |
|---|---|---|
| **M7-②** 预算帽口径 | `value=true valueInBookCurrency=false bookCurrency=(无)` ⇒ ✗「改前只有未命名口径 `value()`=60」 | `value=false valueInBookCurrency=true bookCurrency=silver` ⇒ ✓ 60；日志 `GOV_BUDGET_FOREIGN_CURRENCY_EXCLUDED … excluded={copper=40000}` ×30 |
| **M7-⑤** 利润账 | `{silver:100, copper:50}, 本币=silver ⇒ revenue=**150**`（Σ全部币种）✗ | ⇒ revenue=**100** ✓；日志 `ECONOMY_ENTERPRISE_FOREIGN_CURRENCY_EXCLUDED … excludedCurrency=copper amount=50` |
| **M7-③** 债务压力 | 只有 2 参 `cashOf`：`Σ全部币种 = 1000`（银债 500 判"还得起"）✗ | `cashOf(silver)=0` / `cashOf(copper)=1000` ✓；第 30 天关账轮日志 `OPERATOR_FOREIGN_CURRENCY_EXCLUDED … bookCurrency=silver excluded={copper=37000}` |
| **M7-④** 商号 | 2 参 `feeRevenueOf`：`Σ = 150` ✗ | ⇒ **100** ✓；日志 `MERCHANT_FEE_FOREIGN_CURRENCY_EXCLUDED … excludedCurrency=copper amount=50` |
| **读口** | `currencyDefs":[{"id":"silver","scale":3},{"id":"copper","scale":3}]`（无 displayName）✗ | `[{"id":"silver","scale":3,"displayName":"银"},{"id":"copper","scale":3,"displayName":"铜"}]` ✓（真 HTTP `GET /api/economy/overview` 200） |
| **F5** | `economy.SetMarketNumeraire` 未注册 ⇒ 场景建不起来（A2a BLOCKED-1） | 见 §7.2 |

### 7.2 F5 端到端（终局 `final.log`，逐字摘录）

```
  ── F5 端到端：同区两计价币 ⇒ 异币商品成交具名拒 ──
      [F5] 铜计价格=-2_0（粮价 1）银计价格=-2_1（粮价 1）同区=c-small-capital 成员数=16
  ✓ F5 场景：真命令 economy.SetMarketNumeraire 把一格改成铜计价，且与一个银计价格同区
  ✓ F5 场景：两计价币并存且价格表逐值保留
  ✓ F5 场景：银计价卖方建好（真命令 CreateHousehold/EnsureHouseholdAccount/RegisterHousehold）
      [F5] 清空世界上的可卖iron：搬走 200000（来源 7 户）残留 0
  ✓ F5 场景前置：全世界的铁都在银计价卖方手里（来源 7 户，搬走 200000；格上残留 0）
  ✓ F5 场景前置：铁在铜计价格上有价（否则买方根本不会挂单）
      [F5] F5 铜计价买方 搬入 60000 copper（来自 hh-gov-gov-central）
  ✓ F5 场景：铜计价买方建好且持铜
  ✓ F5 场景：给铜计价买方一条铁需求（真命令 economy.AddDemand）
      [F5] 买方铜=60000 银=0 铁=0；卖方铁=200000；买方格=-2_0（铜计价）卖方格=-2_1（银计价）
  ✓ F5 场景：推进到第 4 天（下一个 tick = 第 5 天例行轮；断言窗口 = 一个 tick）
  ✓ 推进到第 5 天（实际 5）
        买方信用成交（无货币腿）CreditFill[hex=-2_1, commodity=iron, borrower=HOUSEHOLD:hh-a2b-buyer-copper,
            lenderOrSeller=HOUSEHOLD:hh-a2b-seller-silver, quantityMilli=400, unit=Commodity[commodity=iron], …]
      [F5] 第 5 天（trigger=PERIODIC）：成交 9 笔；未成交原因分布={currency_mismatch#sell=2, …}
        具名拒 Unfilled[actor=…hh--2_0-rural-landless_laborer-displaced, buyerSide=false, commodity=grain,
            quantity=289414, reason=CURRENCY_MISMATCH, hex=-2_0]
        具名拒 Unfilled[actor=…hh-a2b-seller-silver, buyerSide=false, commodity=iron,
            quantity=99600, reason=CURRENCY_MISMATCH, hex=-2_1]
        本轮铁的现金成交笔数=0；给该买方的借实物（铁）=400
  ✓ F5-①：异币商品成交 ⇒ 具名拒 currency_mismatch（I19/M1/M7-①）
  ✓ F5-②：被拒的正是我建的「只收银」卖方（buyerSide=false，commodity=iron，hex=-2_1）
        卖方槽读数=[SellerOutcome[roundDay=5, actor=HOUSEHOLD:hh-a2b-seller-silver, …, commodity=iron,
            offeredQty=100000, filledQty=400, unfilledQty=99600, unitPriceMilli=10, …,
            unfilledReason=Optional[CURRENCY_MISMATCH], …]]
  ✓ F5-③：卖方槽读数具名 currency_mismatch（不是静默不成交）
  ✓ F5-④：付款腿未落账 —— 该买方**铜余额逐值未动**（异币支付若静默 1:1，这里会少钱）
  ✓ F5-⑤：本轮**全世界零笔铁的现金成交**（没有任何人用钱买到铁）
  ✓ F5-⑥：该买方本 tick 的铁增量 ∈ (0, 借实物量]（399 ≤ 400，差额 = 该笔借实物的在途损耗）⇒ 商品腿也不是现金成交来的
  ✓ F5 负向对照：同一轮市场确有成交（不是整轮空转 ⇒ 拒绝不是『什么都没发生』）
      [F5] 买方钱袋（前/后）= {copper=60000} → {copper=60000}
  ✓ F5：该 tick 逐币种总量守恒（拒绝不动账，也不造钱）
  ✓ 8 负向对照：未注册命令类型被拒
  ✓ 8 负向对照没有落 revision
== 全部断言通过 ==
```

日志证据（同一次运行）：

```
event=MARKET_NUMERAIRE_SET origin=economy-command q=-2 r=0 from=silver to=copper prices=5
    worldNumeraires=[copper, silver] note=不折算任何余额与价格（世界无汇率）；异币成交将具名拒 currency_mismatch
event=MARKET_CURRENCY_MISMATCH_REJECTED … day=5 leg=cash reason=currency_mismatch commodity=iron
    buyerPays=copper sellerReceives=silver buyer=HOUSEHOLD:hh-a2b-buyer-copper seller=HOUSEHOLD:hh-a2b-seller-silver quantity=400
event=MARKET_CURRENCY_MISMATCH_REJECTED … day=5 leg=money-credit … （同一对，信用腿也被拦）
event=OPERATOR_FOREIGN_CURRENCY_EXCLUDED day=30 household=hh-gov-gov-central unit=unit-office@0_0-… industry=office@0_0
    bookCurrency=silver excluded={copper=37000} note=本币现金 = 单一币种口径：其他币种既不相加也不折算（世界无汇率）
event=GOV_BUDGET_FOREIGN_CURRENCY_EXCLUDED ×30（逐日）bookCurrency=silver excluded={copper=40000}
event=ECONOMY_ENTERPRISE_FOREIGN_CURRENCY_EXCLUDED ledger=revenue bookCurrency=silver excludedCurrency=copper amount=50
event=MERCHANT_FEE_FOREIGN_CURRENCY_EXCLUDED bookCurrency=silver excludedCurrency=copper amount=50
```
★ 负向对照三条：① 未注册命令类型被拒 + 零 revision；② 同一轮市场确有 9 笔现金成交（拒绝不是"整轮空转"）；
③ 异币拒的读数具名（`Unfilled.reason=CURRENCY_MISMATCH` / `SellerOutcome.unfilledReason=CURRENCY_MISMATCH`），不是静默少成交。

### 7.3 与任务书交付要求的对表

| 任务书要求 | 本批证据 |
|---|---|
| F5：多计价币世界里那笔异币商品成交**被具名拒**、账户逐值未动（含负向对照） | ✓ F5-①…⑥ + 三条负向对照（§7.2）。★ 如实边界：**付款腿**（铜）逐值未动、全世界零笔铁的现金成交；该买方**另经借实物**（无货币腿）拿到铁 ⇒ 见 §6 发现 1 |
| M7：5 处中 ≥3 处给"分别算/具名拒/显式单一币种口径"的可观测证据（日志或读口；改前 vs 改后各一次） | ✓ 5/5：① F5 报告+日志；② 30 条 `GOV_BUDGET_FOREIGN_CURRENCY_EXCLUDED`；③ 逐币种 `cashOf` 读数 + 第 30 天 `OPERATOR_FOREIGN_CURRENCY_EXCLUDED`；④⑤ 各 1 条具名排除日志。改前/改后两轮读数逐条对照见 §7.1 |
| 读口：真 API 输出里能看到显示名 | ✓ 真 HTTP `GET /api/economy/overview` ⇒ 200，`currencyDefs` 带 `displayName=银/铜`；`ApiViews.economyOverview` 同源视图逐条一致 |
| 一次性探针走真实生产装配（真 Shell + 真创世 + 真审批链；不许自己 `core.register`） | ✓ 全程零 `register`；写面 = `Shell.start` 装出来的 `coreSimos()` + GM 工具面；F5 的每一条都是真命令（`economy.SetMarketNumeraire` / `social.CreateHousehold` / `actor.EnsureHouseholdAccount` / `actor.AdjustAccounts` / `economy.RegisterHousehold` / `economy.AddDemand`） |

### 7.4 与约束设计书不一致处

见 §4（5 条）与 §5.5（未完成项）。
