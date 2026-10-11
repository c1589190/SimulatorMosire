# W5.5 实现账本：取缔借粮 + 货币市场（钱是市场里的一个商品）

> 责任区：**W5.5**（约束设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` v3.20 §2 S-12、§2.1、§6.1 W2/W5 行、§7 v3.19/v3.20）。
> 写码 Agent 账本（AGENTS §一.8）：① 关键调查结论 ② 实现架构 ③ 关键判断（推翻过什么） ④ 偏离记录。
> 状态：**生产代码已落地并编译通过（`-DskipTests compile -am` rc=0）；判据 10「不回归」未达成 ⇒ BLOCKED（见 ④-1）**。

---

## ① 关键调查结论（`file:line` → 结论 → 影响）

| # | 落点 | 结论 | 影响 |
|---|---|---|---|
| 1 | `EconomySettlement.java` 4b 段（原 `:2759-2834` 调用点、`:4259` `lendDeficitsPartitioned`、`:6743` `lendDeficitsInHex`、`:6915` `lendableOf`、`:376` `LENDER_SUBSISTENCE_RESERVE_PER_MILLE`） | 同格借粮腿是一整族：并行外壳 + 逐格实现 + 粮口径 `lendableOf` + 常量 + 两条日志（`DEFICIT_LENDING`/`DEFICIT_LENDING_DETAIL`）+ `LendingPartition` 记录 + `SettlementStage.LENDING` 档 + `borrowing` 本地累加器 | 全部删除（§一.11）。`SettlementStage.LENDING` 随删（`CommitOrder` 只用 ordinal 做**相对**排序，删档不动其余相对序） |
| 2 | `DebtCapacityBook.java:348/512/524` | 该书的 `lendableOf` 是**另一份**（自有 private 常量），服务 `capacities()` 的 `pledgeable`/headroom（清算 `EconomyLiquidationSettlement` + 只读诊断 `ApiViews:1479`） | **保留**（任务书点名要核的那条：它仍有调用者，不是只服务借粮腿） |
| 3 | `MarketSettlement.java:3474 requestedMoneyOf`、`:8364 payableMoneyOf` | 二者的"订单预算"是**下单当刻的快照**（`Budget(amountMilli)`）；`payableMoneyOf = min(可花, 预算余额 + 冻结)` | ★★ 借钱发生在**下单之后**（用户裁定的次序）⇒ 不把借款补进预算，借到的钱在现金腿上**结构性不可用**（整条腿空转）。⇒ 新增 `BuySlot.borrowedBudgetTopUpMilli` + `applyMoneyMarketBudgetTopUps`（两处算式各读它一次） |
| 4 | `MarketSettlement.java:4720 readOnlyPlanningRound` | `planningRound` 是**外层表的浅拷**，只服务**下单**段（在货币腿之前） | 货币腿写的是真 `round`，撮合/结算读的也是真 `round` ⇒ 无漂移；卖槽的 `reservationMicro` 只读行（不读账户）⇒ 不受影响 |
| 5 | `EconomyData.marketZones` 可为空、`marketTables` 持久 | **派生形态**（小世界等）没有 `MarketZone` 记录，但**价表是持久的** | ★★ 利率若挂 `MarketZone` ⇒ 那些世界每轮从出厂值重算、永不收敛（实测 120 天 48 轮 `rateChanges=0`）⇒ **改挂 `MarketTable`**（见 ③-1） |
| 6 | `ThreePowersGovBootstrap.java:346 recordIssuances` + `GovWorldBootstrap.java:410` | 前者只给"本 GOV 声明发行"的币种记审计 ⇒ 两省国库各 100,000 毫银**无发行记录**；后者的 `silverEndowmentPayload` 是**同口径先例**（发行主体 = 银的唯一发行者，收款国库写进 reason） | 按同口径补审计腿（任务书 §2） |
| 7 | `EconomyRecordMoneyIssuanceHandler` 寻址（W5-fix6 的 `requireGovernmentAddress`） | 本世界的发行主体是 **GOV 单位** ⇒ 必须走 `govUnitId`（派生 `gov-unit-<id>`）；`governmentId` 只认精确政府 id | ★ 实测踩到：用 `governmentId` ⇒ 具名拒 `government-not-registered` ⇒ **创世整次失败**。已修（见 ③-3） |
| 8 | `SimosToolsTest:985/1095`、`McpServerTest:329-333`、`McpPortTopologyTest` | 工具面计数与清单是**冻结字面量**（GM 桶 164、窄写类 68、`EXTERNAL_UNION_GM_TOOL_NAMES`） | 新增一条 GM 窄写 ⇒ 三处断言失效（**只报不改**，见 ④-3） |

---

## ② 实现架构（实际数据流与调用次序）

```
EconomySettlement.settleOneDayInto
  ├─ 市场轮触发判定 → MarketSettlement.clearOncePerCycle(...)
  │    ① 计划段：逐区并行建参与者/买卖槽（挂商品供给与需求）→ 买槽按结算序重排（W4）
  │    ② ★★ 1d 段（W5.5，本批新增；在 commitFreezes **之前**）：
  │         moneyMarketInput(ctx)  ← 逐区"本期生产花费所需现金"（买盘目标 × 本区当前牌价）
  │              + 逐区参与家户 + 逐区计价币 + 逐区价表（利率/α 的唯一权威）
  │         MoneyMarketSettlement.settle(round, input)
  │              · 逐区两簿：需求 = need − 可用现金；供给 = 现金 − 生活储备 − 自己的 need（≤0 ⇒ 不出借）
  │              · 国库户两侧排除（S-12⑥）
  │              · 配对：借款人按净值名次升序、放贷人按可贷额降序（同额按 id）→ 池空即止（S-12④）
  │              · 逐笔：applyTransfer（唯一写口，LOAN_PRINCIPAL，**只搬钱**）+ DebtContractBook.upsert
  │                （真实债权人、单位 = 本区计价币、利率 = 本轮清算利率、到期 = 下个周期）
  │              · 利率' = adaptiveNextPrice(利率, D, S, α)（与 §2.1 商品价同一套算式）
  │         applyMoneyMarketBudgetTopUps(ctx, moneyRound)   ← 把借款补进（同币）买槽预算
  │    ③ 冻结（commitFreezes）→ 区内/跨区撮合（lane/运力/三层税一道不少）→ 未付价款记债（赊购保留）
  │    ④ adaptPrices 之后：applyMoneyMarketLoanRates(...) 把"下一轮利率"写进交回的价表
  └─ outcome.marketTables() 走**既有**的价表写回（`markets.clear(); putAll(...)`）⇒ 段内下一轮读到新利率
```

- **次序（判据 2）**：挂商品（①）→ 挂钱 + 借钱（②）→ 买商品（③）。证据：同一 tick 的日志序 `MONEY_MARKET_SETTLED`/`MONEY_MARKET_ROUND`/`MONEY_MARKET_RATE_WRITTEN` 早于 `MARKET_DEFERRED_PAYMENTS_SETTLED`/`MARKET_ROUND_STATS_RECORDED`。
- **利率（判据 6）**：`利率' = MarketSettlement.adaptiveNextPrice(利率, D, S, α)`（复用 W2 的 §2.1 唯一实现：缺钱升、钱多降、D=S 不动、下限 0、α=0 冻结）。落点 = `MarketTable.loanRatePerMille`（持久、逐市场），α = `MarketTable.loanRateAlphaPerMille`（GM 端口 `economy.SetMarketZoneLoanRateAlpha`）。
- **放贷池（判据 4）**：`可贷 = max(0, 可用现金 − 生活储备 − 本轮自己的生产花费所需现金)`；生活储备 = `MarketSettlement.LENDER_MONEY_BUFFER_PER_CAPITA_MILLI(=12) × 人口`（D-030 §3.1 的既有人均现金缓冲，正是"生活储备"这个口径的既有拼写点）。
- **借款人无上限（判据 5）**：唯一上限 = 放贷池；池空 ⇒ 记 `MONEY_MARKET_UNFILLED`（DEBUG）并把缺口留给买货那一步的赊购。
- **日志（§一.9）**：新阶段 INFO = `MONEY_MARKET_ROUND`（逐轮汇总）、`MONEY_MARKET_SETTLED`（逐市场，有供需时）、`MONEY_MARKET_RATE_WRITTEN`（利率写回）、`MONEY_MARKET_RATE_CLAMPED`（int 上界夹取）；DEBUG = `MONEY_MARKET_WHY`/`MONEY_MARKET_GOV_SUPPLY_SUPPRESSED`/`MONEY_MARKET_UNFILLED`/`MONEY_MARKET_BUDGET_TOPUP`/`MONEY_MARKET_SKIPPED`；TRACE = `MONEY_MARKET_LOAN`（逐笔）。

---

## ③ 关键判断（为什么这样拆；调查中推翻过什么）

1. **★★ 利率状态从 `MarketZone` 搬到 `MarketTable`（推翻了本批的第一版）**。第一版挂在 `MarketZone`（照 `priceAlphaPerMille` 的形制，8 个构造点、代价最小）。**实测推翻**：小世界（`world-silver` 形态）`marketZones` 为空、区按城市 + tier 半径派生，120 天 48 个市场轮里 `rateChanges=0` —— 利率每轮从出厂 20‰ 重新起算、**永不收敛**（静默死分支）。改成挂在**逐市场持久的价表**上（两种世界形态都有它），实测同一次运行立刻出现 `MONEY_MARKET_RATE_WRITTEN ... single-region=20->21`（day3）…`127->133`（day120），48 轮 48 次写回。
   ★ 副产物：`MarketOutcome` 不再需要第 6 个组件、`EconomyStateBuilder`/`WorkingDayView` 不再需要 `marketZones` 工作副本、`EconomySettlement` 的利率写回块整块删掉 —— 路由回到 W1 既有的"价表随交出物离开本类"那一条。
2. **★★ 借款必须补进买槽预算**（`BuySlot.borrowedBudgetTopUpMilli`）：不改的话"先借钱再买货"在现金腿上不可用（判据 3 的机制性前提，不是精度问题）。
3. **创世审计腿的地址维**：`governmentId`（精确政府 id）vs `govUnitId`（GOV 单位 ⇒ `gov-unit-<id>`）—— 本世界是 GOV 单位形态，用错维 = 创世被拒（实测）。
4. **`withHexes`/`withLegalTender` 的 α 静默重置**：两者原走六参构造器，会把 `priceAlphaPerMille` 打回出厂 50‰（W2 引入 α 时漏的两处，"其余字段原样带过"当时是假的）。本批顺手修成 canonical 构造器 —— **这是一处会改变数值行为的修复**（见 ④-2）。
5. **`FlowRow.newBorrowing` 不喂钱**：它是**毫粮**口径的持久读数，货币腿借的是**钱** ⇒ 换标的会让量纲变成谎话。本批不喂（结算路径恒写 0），需要新口径的读者应从 `debtContracts`（`DebtUnit.Money`）取。
6. **`SettlementStage.LENDING` 删档**：唯一使用点随借粮腿消失；`CommitOrder` 只按 ordinal 做相对排序、无按 ordinal 落盘的东西 ⇒ 删档不改变任何提交序。

---

## ④ 偏离记录（与约束设计书/任务书不一致处，逐条给理由与数字）

1. **★ 判据 10「不回归」未达成 ⇒ 状态 BLOCKED（不是 DONE）**。删除同格借粮腿后，**被市场排除的家户（`Unit.households()`：官吏户/军户/国库户）失去唯一的口粮通道**：
   - 实测（`GovGenesisZ6Test`，小世界）：day1 `HOUSEHOLD_GAP_REPORTED householdsWithRationGap=78 rationGapTotalMilli=241298 marketRan=false`（**当天没有市场轮** ⇒ 货币腿不跑；即便跑，这些家户也被 R1/Z7b 排除在订单之外，买不了货）；
   - `GOV_SERVICE_UNDERFED unit=gov-central committedLaborMilli=32000 effectiveLaborMilli=27200 reason=starvation-reduced-household-labor-capped-effective-supply` + `GOV_SERVICE_DESERTION household=hh-unit:gov-central satietyPerMille=850`；
   - 结果：`GovGenesisZ6Test` 5 项里 **3 项红**（效率 1000→850‰、`securityLaborMilli` 16000→13600、120 天后 `GOV_SERVICE` 承诺 32000→16000），**基线（HEAD `4e04ad8c`，同一条命令跑同一类）5/5 绿**，基线 day1 有 **122 条 `DEFICIT_LENDING`**（就是那条被取缔的通道）。
   - 这是**用户裁定（取缔借粮）+ 既有市场排除规则（R1/Z7b）+ "市场轮每 5 天一次"三者叠加**的设计后果，不是实现缺陷；修法至少三条（**都需要用户/控制方裁定**，我没有自行选边）：
     (R1) 给单位户/国库户补**逐日给养**通道（官俸/军俸/国库划拨的规模与优先级 —— 2026-10-23 财政闭环设计的开放项 P4c）；
     (R2) 把 Z7b 的排除**收窄到卖侧**（允许它们买，不允许它们的实物被当余量卖掉）；
     (R3) 接受这种饥饿（D-025 饥荒合法），但把 `GOV_SERVICE_UNDERFED` 的判据与既有测试基线显式重新基线化。
2. **`withHexes`/`withLegalTender` 现在保留 α**（改前会重置成 50‰）：只在 `economy.ReassignZoneHexes` / `economy.SetMarketNumeraire` / 区级官方汇率写入路径上可观察；这是缺陷修复，但**会改变数值行为**（`ReassignZoneHexes` 之后 α 不再是 50）。
3. **`ThreePowersGovBootstrap` 新增 3 条 `GENESIS_ENDOWMENT` 审计**（两省国库的 100,000 毫银 + 若有的其它非本 GOV 币种）：**只加审计、余额一分不动**；但 `moneyIssuances`（累计发行量）变大 ⇒ 若某世界用"累计发行量"当 FX 储备上限（`FxRoundInput.of(...)`），那一路读数会变（本世界的 FX 面未接线 ⇒ 实测无差异）。
4. **利率无上限 + 合同条款是 `int`**：120 天实测 20→133‰（单调升，D ≫ S）。利率继续涨会越过 `int`（`DebtTerms.interestRatePerMillePerCycle`）⇒ 夹到 `Integer.MAX_VALUE` 并发 `MONEY_MARKET_RATE_CLAMPED`（INFO，不静默）。**列为 W6 标定项**（与 §2.1 N-5 的"600 天价格路径"同族）。
5. **不做跨区借贷**（逐市场清算；区序取区结算序）：句面理由是"钱腿只搬钱、不引入未建模的跨区物流 + 跨币折算本批不存在"；代价 = 某市场池空而邻区有闲钱时借不到（退到赊购，方向正确的降级）。
6. **国库"显式授权出借"未建写口**（S-12⑥ 只要求"默认不挂"）：本批把国库户两侧排除 + 给读数（`MONEY_MARKET_GOV_SUPPLY_SUPPRESSED`）；授权命令/载荷形状用户未给 ⇒ 不自行发明（记未完成项）。
7. **`MarketTable` 是本次唯一的持久形状改动**（+2 字段）：`marketTables` 是既有的 `FieldDelta` 组件 ⇒ ChangeSet/Codec 无需改形状（Jackson record 绑定）；三参便捷构造器 = "不表态"的缺省语义（非兼容位）；`withPrices/withPrice/withNumeraire/withHexes` **必须**带过新字段（自适应改价每个市场轮都走 `withPrices`，漏了 ⇒ 利率每轮被重置）。

---

## ⑤ 本批实测读数（自证证据，命令与 mtime）

| 项 | 命令 | 结果 |
|---|---|---|
| 编译（唯一硬底线） | `tools/mvn-lock.sh -q spotless:apply` → `tools/mvn-lock.sh -DskipTests compile -am` | rc=0（16 模块 SUCCESS） |
| 既有经济验收（赊购/债务式清算） | `tools/mvn-lock.sh -Dtest='DebtBasedClearingAcceptanceTest' -Dsurefire.failIfNoSpecifiedTests=false test -am` | **6/6 绿**（报告 mtime 14:24:44，本轮） |
| 小世界 120 天（货币腿真读数） | `-Dtest='GovGenesisZ6Test#oneHundredTwentyDaysAdvanceWithoutContractFailure' …` | 48 个市场轮全部 `MONEY_MARKET_ROUND`（day3 demand=103,787/supply=9,202/lent=9,202/loans=5；day120 demand=6,138,324/supply=2,327）；`MONEY_MARKET_RATE_WRITTEN` 48 条：`20->21`(d3) … `127->133`(d120)；该用例**红 1 项**（见 ④-1 的承诺腰斩） |
| 三国世界 240 天 | `-Dtest='EconomyCycleHealthTest' …` | 创世+两周期跑通（货币守恒断言之前先红在"逐轮成交笔数分布指纹"）：**基线 {17=10,18=1,21=7,22=9,23=5,24=12,31=1,34=1,35=3,36=4,37=8,38=1,39=2,40=13,41=2,44=1,53=8,54=1,55=3,56=2,66=1,131=1} vs 实测 {15=1,21=18,22=5,24=17,25=2,26=2,30=1,37=1,38=1,39=4,40=9,41=7,42=11,43=7,44=3,46=1,48=1,51=1,53=1,66=1,85=1,135=1}**（min 17→15、中位 35→37.5、max 131→135；轮数同为 96）——货币腿让"现金 0 的家户也能买"⇒ 每轮成交笔数上移，**这是本批有意的行为改变，测试需显式重新基线化**（测试批的活） |
| 基线对照（判别实验） | `git stash -u` → 同一条 `GovGenesisZ6Test` → `git stash pop` | 基线 **5/5 绿**、day1 `DEFICIT_LENDING`=122 ⇒ 上面 3 项红**确系本批造成**（§四「回代码核 + 判别实验」） |
| 测试编译面 | 上述两次 `test` 跑（economy + app 两个模块的 testCompile 都参与） | **全仓测试树编译通过**（无编译级破坏；破坏都在断言级，见 ④-1/④-3） |

---

## ⑥ 未完成 / 未验证

1. **判据 10 未达成**（③-1 的三项红）——BLOCKED，等裁定（④-1）。
2. **国库"显式授权出借"命令未建**（S-12⑥ 的后半；用户未给形状）。
3. **`economy.SetMarketZoneLoanRateAlpha` 未跑真调用**（需 GM 工具面探针/会话；本批只做到：命令注册进 `Shell`、窄工具进 GM 桶、catalog 载荷提示、编译通过；工具面计数类测试按 ④-3 需要重新基线化）。
4. **`ThreePowersGovBootstrap` 的 3 条新审计未逐条读回**：只证到"创世不再被拒 + 240 天跑通"（`EconomyCycleHealthTest`）；`mi-*` 读数的逐值核对留给测试批。
5. **未跑**：`verify` / `package` / 全量测试 / 前端门禁以外的门禁（按 §一.5 硬纪律，测试统一留到最后）。
6. **跨区/跨币借贷、利率上界标定、`FlowRow.newBorrowing` 的新口径**：本批明确不做，理由见 ③-5 / ④-4 / ④-5。

---

# W5.5-fix（R2：Z7b 排除收窄到国库户）

> 责任区：**W5.5-fix**（控制方裁定 **R2**，全文见设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` v3.21 §7）。
> 前文（W5.5 主体）**一字不改**；本节是它的收口记录（AGENTS §五.4 留痕：追加、不覆盖）。
> 状态：**R2 已按裁定落地、编译绿、单测自证已跑；W5.5 的 BLOCKED 点（判据 10 / `GovGenesisZ6Test` 5/5）仍未达成 —— 但根因被本节的判别实验改写（见 ③-1）⇒ 仍 BLOCKED。**

## ① 关键调查结论（`file:line` → 结论 → 影响）

| # | 落点（最终字节 md5 `9b8e132c…`） | 结论 | 影响 |
|---|---|---|---|
| 1 | `MarketSettlement.java:295` / `:718` | Z7b 的排除判据本体**不是**一处，而是 5 处直接读 `marketExcludedHouseholds()`/`govMandates().isAuthorizationOnly(...)`（`participantsFor` 家户层 + 经营者层、`ordersFor` 第二道防线、`collectGovMandateFills`、`deferralPossible`）+ 2 处旁路（`MarketPayChoice:155`、`FxSettlement:356`）+ 1 处货币腿（`MoneyMarketSettlement:340`） | 收窄 R2 必须先把判据**收成一处**（`HouseholdMarketRole` + `MarketRound.householdMarketRole`），否则"改了一处、漏了四处"会表现为"某些户还在被静默排除" |
| 2 | `EconomySettlement.java:1121`（改前 `unitExclusionsMinusGovernmentTreasuries(...)`） | R1 留下的"单位户集合 − 政府国库户"**是第二个拼法**：它做的分类正是角色判据要做的那一件事 | 删掉它（`EconomySettlement.java:10124` 留了删除理由）；注入集合**原样照收**，分类只在角色判据里发生 |
| 3 | `MarketSettlement.java:9541`（家户层）与 `:9721`（经营者层） | 改前这两层各有一条 `continue` ⇒ 单位户**既不进参与表、也不生任何订单**（也就是 W5.5 台账说的"唯一口粮通道被删后没有市场可走"） | 两层都改成"照常进参与表，角色决定生成哪些订单" |
| 4 | ★★★ **判别实验**（本节最贵的结论） | 把 `householdMarketRole` 的 `BUY_ONLY` 临时变异成 `AUTHORIZATION_ONLY`（= 改前"单位户零订单"语义）后重跑同一测试，`GOV_SERVICE_UNDERFED` 的逐日序列与 R2 **逐值相同**（27200/27840/28480/29120/29760/30400/31040/31680） | **W5.5 台账 ④-1 的诊断（"被市场排除 ⇒ 结构性饥饿"）不成立**：官吏户的口粮通道是**俸禄**（`salaryShortfallMilli=0 stipendShortfallMilli=0`，付足），从第 2 天起就吃饱；R2 对这三条断言**零影响**。真根因见 ③-1 |
| 5 | 俸禄的到账时机（`PopulationEconomyTimeParticipant` 日循环：`HOUSEHOLD_GAP_REPORTED` 在 `PERIODIC_ADJUSTMENT_DAY` **之前**） | 官吏户创世**没有粮**（创世只给国库注入粮，`ThreePowersGovBootstrap:134 TREASURY_GRAIN_MILLI_PER_GOV`），而当日消费（`stepper.step`）发生在俸禄日结**之前** ⇒ 第 1 天整日需求全落空 | 第 1 天 satiety `1000 → 850`（`−150‰` 满额降），此后 `+20‰/日` 回升；但饥饿逃亡率累加到 `ratePerMille=92`（day8）⇒ **2 名成员逃 1 名**，`GOV_SERVICE` 承诺 `32000 → 16000` 永久减半 ⇒ 三条断言全红 |
| 6 | `MARKET_LOW_STOCK_EXTRA_PHASE_DAYS=3` + `day%5==0` 例行轮（`MarketSettlement:199/205`） | **第 1 天结构上不可能有市场轮**（例行轮 day%5==0、低库存追加轮 day%5==3） | R2（买盘）在"当日消费先于俸禄"的世界里**结构上补不上第 1 天**这半格；这是财政闭环/给养时机（P4c）的缺口，不是市场准入 |

## ② 实现架构（唯一判据 + 数据流 + 日志面）

```
MarketRound.householdMarketRole(HouseholdId)        ← ★ 唯一拼写点（MarketSettlement.java:718）
  ├─ govMandates.isAuthorizationOnly(户)  ⇒ AUTHORIZATION_ONLY   （权威 = Government.treasury()；★ 优先，更严的赢）
  ├─ 组合根注入的单位户集合含该户          ⇒ BUY_ONLY            （R2 的收窄点：买盘开、卖盘关）
  └─ 其余                                  ⇒ FULL
消费者（全部改走它，全仓再无第二处 contains/isAuthorizationOnly）：
  participantsFor 家户层 :9541 / 经营者层 :9721     → 角色只影响"生成哪些订单"，不再有 continue 排除
  ordersFor        :2807（分派）+ :2835（buyOnly）  → AUTHORIZATION_ONLY 走既有 planGovMandateOrders；BUY_ONLY 只跳过 `sells.add`
  collectGovMandateFills / deferralPossible         → 判据换成 `!= AUTHORIZATION_ONLY`（逐值等价）
  MarketPayChoice  :155                             → 只有国库户不选币（单位户要"正常付钱"⇒必须选币）
  FxSettlement     :356                             → 非 FULL 一律不挂家户外汇单（= 改前口径，含国库户与单位户）
  MoneyMarketSettlement :340                        → 只有国库户两侧排除（S-12⑥ 一字不动）；单位户作为普通借款人/放贷人
```

- **日志（§一.9，全部具名）**：
  | 事件 | 级别 | 频度 | 回答什么 |
  |---|---|---|---|
  | `MARKET_HOUSEHOLD_ROSTER` | **INFO** | 每市场轮一条（名册空 ⇒ 一条不发） | **默认级别**就能看见"哪些户只买（`buyOnlyRoster`）、哪些户只按授权（`authorizationOnlyRoster`）"+逐类户数 |
  | `MARKET_HOUSEHOLD_ROSTER_BY_HEX` | DEBUG | 逐 hex（有非 FULL 户才发） | 这一格里有谁、什么角色（与 `MARKET_LIFE_RESERVE` 同族） |
  | `MARKET_HOUSEHOLD_ROLE` | TRACE | 逐户（家户层/经营者层各一处） | **为什么**是这个角色（`reason=unit-household-buys-but-does-not-auto-sell` / `government-treasury-orders-only-by-explicit-authorization`） |
  | `MARKET_HOUSEHOLD_BUY_ORDER` | DEBUG | 逐条单位户买单 | 谁买了什么、多少、什么价、手里多少钱、缺口走不走赊购（`deferralEnabled`） |
  | `MARKET_HOUSEHOLD_SELL_SUPPRESSED` | TRACE | 逐条被挡下的卖单 | "有粮却没卖单"的原因（Z7b 卖出侧守护仍生效的证据） |
  | `MARKET_SUBJECT_UNIT_HOUSEHOLD_WITHOUT_ROW` | WARN | 罕见 | 单位户的 unit 解析到一个**没有经济行**的家户 ⇒ 该 unit 无法当市场主体（具名跳过，不静默、不抛整轮） |
- **名册长度有界**：`ROSTER_LOG_LIMIT=16`（`MarketSettlement.java:9451`），超出写 `(+N more)`，不静默丢。
- **缺省中性**：没有单位户/政府的世界 ⇒ 名册为空 ⇒ **一条日志都不发**；`householdMarketRole` 恒 `FULL` ⇒ 与改前逐值同行为。

## ③ 关键判断（为什么这样拆；判别实验推翻了什么）

1. **★★★ 判别实验推翻了 W5.5 台账 ④-1 的根因判断（本节的核心）**。台账当时把 `GOV_SERVICE_UNDERFED effectiveLaborMilli=27200/32000`、`satietyPerMille=850` 读成"结构性饥饿"，并据此把 BLOCKED 归因于"被市场排除 ⇒ 失去唯一口粮通道"。实测：
   - 官吏户**从第 2 天起就吃饱**（`GOV_SERVICE_UNDERFED` 逐日 27200→…→31680，即 satiety 850→…→990，day13 回到 1000）；它们的口粮通道是**俸禄**（`stipendShortfallMilli=0` 付足），与市场准入无关；
   - 变异体（单位户零订单 = 改前语义）跑出的**逐日序列与 R2 完全相同** ⇒ R2 对这三条断言零影响。
   ⇒ 真根因 = **第 1 天的"消费先于俸禄、且第 1 天没有市场轮"**（①-5/①-6）：整日需求落空 ⇒ `−150‰`；随后的饥饿逃亡率累加到 92‰ ⇒ day8 逃 1 人 ⇒ 承诺腰斩。
2. **判据必须收成一处，否则 R2 会"半生效"**：5 处直接判据 + 3 处旁路里，任何一处漏改都会表现为"某些户还在被静默排除"，而这正是 Z7b/Z7b-fix 反复踩的形态（②的图就是收口后的形态）。
3. **删 `unitExclusionsMinusGovernmentTreasuries` 而不是留着**：国库口径由角色判据**优先**回答（更严的赢，fail-closed）⇒ 摘不摘都是同一个角色；留着就是"谁被排除"的第二个拼写点（§一.11）。
4. **卖侧必须留一条判据**：R2 放开买盘后，单位户的 unit 会被挂到它的参与者上（买盘目标含 unit 的必要投入 = 任务书点名要的"同一个量"）；若不同时挡住卖单，它们的实物库存会立刻被当余量清仓 —— 那正是 Z7b 的根因（run6 day50 中央粮 271,393 → 0）。
5. **`MarketPayChoice` 必须一起放开**：单位户回到买盘后若不参与"最强持有币"选择，它们的买单会退回本格计价币 ⇒ 手里是别的币时被迫走赊购（结构性多欠一笔债），与 R2 的"正常付钱"相悖。
6. **家户外汇单（`FxSettlement`）刻意**不**放开**（比 R2 字面更窄的 fail-closed 选择，见 ④-1）。
7. **经营者层"没有经济行"的处置**：`FULL` 户照旧具名抛（真坏数据，fail-closed 不放宽）；单位户/国库户没有经济行 ⇒ 具名 WARN 跳过 —— 这与改前"它们整个被排除"对它们**逐值相同**，但不再静默。

## ④ 偏离记录（与任务书/设计书不一致处，逐条给理由）

1. **★ 家户外汇单侧仍排除单位户**（R2 字面只说"卖出侧维持原口径"，未点名外汇）：`FxSettlement:356` 保持 `!= FULL ⇒ 不挂家户外汇单`。理由：R2 的落点是**商品市场买盘/口粮通道**；家户外汇单是"弱换强"的自动换币，放开它会把单位户/公家的持币自动重组（"政府拿市场当政策工具"的邻域），超出本次裁定的目标；**更窄 ⇒ fail-closed**，如需放开请裁定（已列 ⑥）。
2. **R2 的一个未点名后果：单位户现在也进货币腿**。`moneyMarketInput` 的参与户来源 = `ctx.participants`（`MarketSettlement:4078`）⇒ 参与表放开后它们同时成为货币腿的借款人/放贷人（国库户仍两侧排除）。这是"按普通家户参与市场"的直接推论，但**任务书没写**、也未单独验证（⑥-4）。
3. **`GovGenesisZ6Test` 的三条断言按任务书 §3 最后一条处置：只报不改**（测试不在授权面）；它们的根因不是 R2，见 ③-1。
4. **`marketExcludedHouseholds` 这个名字没有改**（它现在表示"只买不卖"的单位户集合）：改名会动 `EconomyDayStepper.updateMarketExcludedHouseholds`（app 组合根调用）与 22 参构造器（**测试夹具在用**），而测试树不在本批授权面；语义已在字段注/构造器注/注入点三处写明，判据的拼写点已收进 `householdMarketRole`。
5. **没有自造任何补贴/给养通道**（R1 的活）：第 1 天的缺口如实上报（③-1），未动俸禄时机、未动逃亡规则、未给官吏户加创世存粮。

## ⑤ 实测读数（命令与结果；全部在最终字节上跑）

最终字节：`MarketSettlement.java` md5 `9b8e132c1b446f995687347ad7e278e1`（`spotless:apply` 之后）。

| 项 | 命令 | 结果 |
|---|---|---|
| 格式化 | `tools/mvn-lock.sh -q spotless:apply` | rc=0 |
| 编译（唯一硬底线） | `tools/mvn-lock.sh -DskipTests compile -am` | **rc=0**（16 模块 SUCCESS） |
| ① 回归自证（任务书允许的单条） | `tools/mvn-lock.sh test -pl simos-app -am -Dtest='GovGenesisZ6Test' -Dsurefire.failIfNoSpecifiedTests=false` | **5 跑 3 红**（rc=1；报告 mtime 14:41:05，本轮）：`:239` 1000→**850**、`:380` 16000→**13600**、`:484` 32000→**16000**（三条**与 W5.5 逐值相同** ⇒ R2 对它们零影响，见 ③-1） |
| ② 官吏户口粮读数（同一次运行） | 同上（INFO） | day1 `GOV_SERVICE_UNDERFED committedLaborMilli=32000 effectiveLaborMilli=27200`；逐日 27200/27840/28480/29120/29760/30400/31040/31680（satiety 850→990，+20‰/日）；day13 `satietyPerMille=1000`；day1 `GOV_SERVICE_DESERTION reason=hunger satietyPerMille=850 salaryShortfallMilli=0 stipendShortfallMilli=0 ratePerMille=22`；**day8 `ratePerMille=92 fledCount=1 membersBefore=2`** ⇒ `GOV_SERVICE_COMMITMENT_DESERTION_REDUCED laborBefore=32000 laborAfter=16000` |
| ③ 单位户买入逐户样例 | 同一条测试 + `-Dsimos.economy.logLevel=DEBUG`（★ 该 `-D` 只抬日志级别、不进任何判据：断言结果与上面那次相同） | 120 天 **43 条** `MARKET_HOUSEHOLD_BUY_ORDER`（`hh-unit:gov-central` 20 / `hh-unit:gov-province` 23；cloth 32 / grain 5 / tool 6）。样例 day3：`hh-unit:gov-central commodity=grain hex=0_0 quantityMilli=5207 targetMilli=5845 availableMilli=638 budgetMilli=64 currency=silver referenceMilli=1 deferralEnabled=true role=BUY_ONLY`；`hh-unit:gov-province commodity=grain quantityMilli=5212 availableMilli=633`。**全部现金付清**：0 条 `MARKET_DEFERRED_PAYMENT` 的 debtor 是 `hh-unit:*` |
| ④ 国库户仍零自动单 | 同上（DEBUG） | **720 条** `GOV_MARKET_AUTO_ORDERS_SUPPRESSED`（三个 `hh-gov-*` 各 240 = 48 轮 × 5 商品），字段 `autoBuyOrders=0 autoSellOrders=0`，`wouldBeSellMilli=999028`（清仓被挡住） |
| ⑤ 名册日志 | 同上 | `MARKET_HOUSEHOLD_ROSTER`（INFO）**48 条**、`MARKET_HOUSEHOLD_ROSTER_BY_HEX`（DEBUG）96 条；样例：`day=3 buyOnlyHouseholds=2 buyOnlyRoster=[hh-unit:gov-central,hh-unit:gov-province] authorizationOnlyHouseholds=3 authorizationOnlyRoster=[hh-gov-gov-central,hh-gov-gov-province,hh-gov-world-silver]` |
| ⑥ 卖出侧原口径（既有夹具测试，顺带核） | `tools/mvn-lock.sh test -pl simos-economy -am -Dtest='Z7MarketExclusionTest' -Dsurefire.failIfNoSpecifiedTests=false` | **1/1 绿**（rc=0）：单位户与国库户在"修复口径"下 `sellable=0`、正常户买单逐值不变（★ 断言仍绿，但单位户那一条的**理由已从"被排除"变成"只买不卖"**） |
| ⑦ 判别实验（变异体） | 把 `:725` 的 `BUY_ONLY` 变异为 `AUTHORIZATION_ONLY` → 同一条 `GovGenesisZ6Test` → **还原并比 md5**（`ab557151…` → 变异 `33066d69…` → 还原 `ab557151…` 逐字节相同） | 变异体同样 5 跑 3 红；`GOV_SERVICE_UNDERFED` 逐日序列与 R2 **逐值相同** ⇒ ③-1 的因果结论 |
| ⑧ R2 对世界的可观察差异（R2 vs 变异体，INFO 面） | 两边各一次单条测试的 `MARKET_ROUND_STATS_RECORDED` / `HOUSEHOLD_GAP_REPORTED` 逐日对比 | 48 个市场轮里 **34 轮**有差异，但只有 day3 的 `tradedMilli` 差 5,207（3065115→3070322，= 官吏户那张 grain 单）、其余差在 `cashTurnoverMilli`（+5…+67，现金腿）；缺口读数 day1 相同（78 户/241,298），day38 71→72 户、215,038→215,244（+206），day120 77→78 户、197,764→198,547（+783）⇒ **R2 把极少量的稀缺粮从别的买家转给单位户**（外部性，逐户点名未做） |

## ⑥ 未完成 / 未验证

1. **判据 10 / `GovGenesisZ6Test` 5/5 仍未达成 ⇒ BLOCKED，但根因已改写**：不是市场准入（③-1 的判别实验），而是**第 1 天"消费先于俸禄 + 第 1 天无市场轮"**这半格。可行修法（**都不在 R2 授权面，需控制方/用户裁定**）：
   (a) 俸禄/周期调整挪到当日经济结算**之前**（改日循环次序：`GovDaily.settle` 现读结算后劳动，动了会改 gov 效率语义）；
   (b) 给单位户创世粮（= 新给养通道，R1）；
   (c) 市场轮在低库存时可在任意日追加（改 M0.1 的绝对相位，影响所有世界）；
   (d) 按 R3 显式重新基线化这三条断言（测试批）。
2. **`FxSettlement` 的口径边界**（④-1）：单位户仍不挂家户外汇单 —— 有意为之，待裁定。
3. **单位户进货币腿**（④-2）：代码路径存在（`ctx.participants` 来源），**未单独跑读数**证明它们真的借/贷过。
4. **逐户外部性未点名**（⑤-⑧）：+1 户带缺口 / +783 毫累计缺口具体落在哪一户未查（需 TRACE 的逐户缺口明细）。
5. **未跑**：`verify` / `package` / 全量测试 / 其余单条回归。受 R2 影响、**建议测试批优先复跑**的类（按类名，只报不改）：`GovGenesisZ6Test`（本批 3 红）、`EconomyCycleHealthTest`（三国世界的逐轮成交指纹，W5.5 已需重基线，R2 再叠加参与者）、`Z7HungerTest` / `Z7FleeTest` / `GovAdminSalaryBudgetZ6Test` / `Z7D4BudgetLedgerTest` / `GovToolsZ6Test` / `RaiseUnitToolTest`（都碰 `hh-unit`/俸禄/逃亡面）、`Z7MarketExclusionTest`（本批实测仍绿）。
6. **`GovernmentMarketMandatePlan.authorizationOnlyRoster()`**（`:201`）只为日志/诊断新增，未进任何判据（无行为影响）。

---

# W5.5-fix2（单位户创世粮，新根因）

> 责任区：**W5.5-fix2**（控制方裁定 **(b)**：给"单位户"补创世粮，口径与普通家户一致；全文见设计书
> `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` v3.22 §7，待调查项见
> `docs/superpowers/plans/2026-10-02-open-bugs.md` 的 `INV-2026-10-11-11`）。
> 前文（W5.5 主体、`# W5.5-fix`）**一字不改**；本节是收口记录（AGENTS §五.4 留痕：追加、不覆盖）。
> 状态：**裁定 (b) 已按"同一口径"落地、spotless/compile 绿、单条自证已跑；★ 三条目标断言（效率 / securityLabor / 承诺）
> 全部恢复**；`GovGenesisZ6Test` **5 跑 4 绿 1 红** —— 剩的那条（`:351` 第 1 天人口 4801 vs 4799）**改动前就成立**
> （判别实验见 ③-3），不属本批口径面 ⇒ 本责任区按"三条目标断言恢复"收口，余项记 ⑥。

## ① 关键调查结论（`file:line` → 结论 → 影响）

| # | 落点 | 结论 | 影响 |
|---|---|---|---|
| 1 | **普通家户创世粮的口径**（回代码核）：`EconomySeeder.java:2512` `openingStock(人口, slot, goodsByClass, i)` → `:2525` 第一项 `EconomyVocabulary.cumulativeRationMilli(population, rationDays)`；天数 = `:2740` `rationMilli` → `:2745` `initialRationDays(slot)` = 查 `:678` `INITIAL_RATION_DAYS_BY_CLASS` × `:695` `GENESIS_GRAIN_RESERVE_MULTIPLIER(15)`；槽位表 = {贫农30, 中农60, 富农120, 地主250} ⇒ 天数 {450, 900, 1800, 3750}。落点 = `classRows`（`:2412` 逐阶层 `stocks.put(key, openingStock(...))`），再经 `HouseholdSeeder` 播进 actor 账本 | **口径 = 一张阶层天数表 + 一条累计口粮算式**（不是"每人每天 83 毫"那种乘出来的近似） | 单位户要"同一口径"，就必须走**同一个 `initialRationDays` + 同一张表 + 同一条算式** |
| 2 | **单位户没有阶层槽位**（回代码核）：官吏户经济行 `stratum=official`（`GovWorldBootstrap.java:784`）；军户经济行 `stratum=landless_laborer`（`RaiseUnitPlan.java:511` / `SpawnArmyPlan.java:442`）——**两者都不在 `INITIAL_RATION_DAYS_BY_CLASS` 里** ⇒ `initialRationDays` 对它们 fail-closed（"拒绝臆造"） | "查该户自己的阶层"这条路口**结构上不存在**；而"没有阶层槽位的家户怎么给创世粮"本类**已有唯一先例**：创世流民户走 `initialRationDays(CLASS_IDS[0])`（`EconomySeeder.java:2431`） | 单位户取**最贫一档**（沿用流民户先例）⇒ **不新造常数、不改那张表**（见 ④-1 的偏离记录） |
| 3 | **创世期只有国库被注资**（回代码核）：`GovWorldBootstrap` 的 `actor.AdjustAccounts`（`treasuryInjectionPayload()`，`:331` 那条命令）只注 `hh-gov-<govId>`（粮 1,000,000 / 布 100,000 / 银 100,000 + 中央铜 100,000）；官吏户在这条命令时**只有 `actor.EnsureHouseholdAccount` 建的空账**（`:597`/`:721` 一带） | 官吏户第 1 天**无粮**（账面 0） | 与 W5.5-fix ③-1 的真根因一致：第 1 天需求落空 → satiety −150‰ → 逃亡率累加到 day8 → 逃 1 人 → 承诺腰斩 |
| 4 | **"单位户"的集合定义**（控制方裁定原文：`Unit.households()` 里**非国库**的家户）：本仓只有一处权威判据 —— 国库户由 `EconomyData.governments()` 的 `Government.treasury()` 派生（`GovernmentMarketMandatePlan.java:74-85`：`HouseholdActors.householdOf(government.treasury())`） | 单位户 = 逐 GOV 单位的 `Unit.households()` **减去**该集合 | 本批把它做成**唯一拼写点**（`HouseholdSeeder.unitHouseholdRationPayload`），不手拼 `hh-unit:<govId>` |
| 5 | **逐条创世路径核过（哪条有单位户）**：① `v17levant`（`RichWorld`，缺省"world"）= 签入资源里 `unit.state.units` **0 个**（实测 `python3` 解 `worlds/v17levant.json`：`unit count: 0`）⇒ 无单位户；② `corridor`（`CorridorWorld.java:169`）= 唯一单位 `u-1` 以 `List.<HouseholdId>of()` 创建（创世单位不容纳家户）⇒ 无单位户；③ `small-world`（`SmallWorld.java:318` → `GovWorldBootstrap`）与 ④ `three-powers`（`ThreePowersWorld.java:374` → `ThreePowersGovBootstrap`）= 每个 GOV 的 `Unit.households() = [hh-gov-<id>, hh-unit:<id>]` ⇒ **非国库的那一个 = 官吏户**；⑤ 真 worldgen 工具路径（`WorldgenInitializeTool`/`RegionSeedPlan` → `EconomySeeder`+`HouseholdSeeder`）**不建任何 unit**；⑥ `simos.province.apply`（`ProvinceApplyPlan`）建 GOV unit + `hh-gov-*` 国库户，**不建** `hh-unit:`（`grep hh-unit` 在该文件 0 命中） | **要补的只有 ③④ 两条** | 本批只改这两条命令链；①②⑤⑥ **逐值不变**（一条命令都不发） |

## ② 实现架构（口径 + 落点 + 数据流 + 日志面）

```
【量】EconomySeeder.unitHouseholdRationSlot()   → CLASS_IDS[0]（= poor_peasant；与流民户同一先例）  :2765
      EconomySeeder.unitHouseholdRationDays()   → initialRationDays(slot)  = 30 × 15 = 450 天        :2770
      EconomySeeder.unitHouseholdGenesisGrainMilli(人口) → rationMilli(人口, slot)                  :2786
                                    └─ 与普通家户**逐字同一条算式**：cumulativeRationMilli(人口, 天数)

【户】HouseholdSeeder.unitHouseholdRationPayload(world, state, govUnitIds)                          :350
      ├─ 单位户集合 = Σ(Unit.households() of govUnitIds) − treasuryHouseholds(EconomyData)          :363/:432
      │     ★ 国库口径唯一权威 = Government.treasury()（与 GovernmentMarketMandatePlan 同源）
      ├─ 人口 = SocialData.householdPopulation(户)（Social 权威现取；≤ 0 ⇒ 具名抛，不发 0 值条目）  :368
      ├─ 值 = unitHouseholdGenesisGrainMilli(人口) ⇒ {household, goods:{grain: N}}                   :375
      └─ INFO 逐户一条 GENESIS_UNIT_HOUSEHOLD_RATION（world/household/population/rationSlot/         :405
            rationDays/grainMilli/basis）—— §一.9 的"谁、多少、依据哪个口径"

【落地】两条创世命令链**各自在末尾追加一条** actor.AdjustAccounts（既有各条的相对顺序一字不动）：
  small-world : GovWorldBootstrap.apply  末尾 :358-371  （world=small-world；govUnitIds=[gov-central, gov-province]）
  three-powers: ThreePowersGovBootstrap.apply 末尾 :358-370（world=three-powers；govUnitIds=ordered 序）
  ★ 落到同一个 actor 账本（actor.Seed 早在前面建户建账；本条是"已有账 + 纯正增量 upsert"）
```

- **日志（§一.9，具名、可闭合）**：事件 `GENESIS_UNIT_HOUSEHOLD_RATION`（**INFO**，每户一条），来源表新增
  `AppLogSource.GENESIS_SEED`（`genesis-seed` / SYSTEM / `AppLogSource.java:53`），logger = `AppLog.shell()`。
  实测样例（本批真读数）：`world=small-world household=hh-unit:gov-central population=2 rationSlot=poor_peasant
  rationDays=450 grainMilli=75000 basis=same-algorithm-as-ordinary-household-genesis-ration`。
- **缺省中性**：没有单位户的世界 ⇒ 该命令**根本不会发出**（两条命令链只在各自创世路径里跑）；v17levant / corridor 逐值不变。

## ③ 关键判断（为什么这样拆；判别实验）

1. **"同一口径"的落点选最贫一档，而不是给 `official` 加一行表项**：表项是**新常数**（本批任务书明令"别自造新常数"），
   而流民户先例（`EconomySeeder.java:2431`）**已经在做**"没有阶层槽位的家户 ⇒ 取最贫一档"这件事 ⇒ 沿用它是**零新数值**的方案。
   ★ 副作用：单位户的创世储备（450 天）比其人口规模更宽裕 —— 这是"与普通家户同口径"的直接推论，不是新政策（见 ④-1）。
2. **落点放在"两条创世命令链末尾各追加一条命令"，而不是塞进既有国库注资载荷**：① 既有各条的相对顺序一字不动（W5-fix6 的
   "余额腿 → 审计腿"配对不被插队）；② 语义不混（国库 vs 家户口粮）；③ `actor.AdjustAccounts` 的载荷规则"同一家户只能出现一次"
   （`ActorPayloads.adjustments`）天然要求两份名单分开拼。
3. **★ 判别实验：剩下的那一条红与本批无关（改动前就成立）**。`GovGenesisZ6Test` 5 跑 4 绿 1 红，剩的是
   `:351` `totalSocialPopulation(after) == 4801`，实测 **4799**。判别证据（同一条测试、改动前 / 改动后各一轮）：
   - **改动前**（`/tmp/w55fix2/baseline.log`，HEAD 工作树字节）：`day=1 POPULATION_SETTLE births=1 deaths=2
     famineDeaths=2 remainders=661`；**改动后**（`after.log`）：**逐值相同**（同一行、同一 `remainders=661`、同一
     `firstHousehold=hh--2_0-rural-poor_peasant(gapMilli=5183,needMilli=5183,ratioPerMille=1000)`）。
     ⇒ 4800 + 1 出生 − 2 饿死 = **4799** 在改动前就是这个世界的事实；`4801` 这条期望值**早就失效**，只是那一轮测试在
     `:239`（效率）就红了，**没跑到** `:351`（所以 W5.5/W5.5-fix 两轮都没看见它）。
   - **本批的唯一日 1 足迹**：`householdsWithRationGap` **78 → 76**（少的正是两个官吏户）、`rationGapTotalMilli`
     **241,298 → 240,966**（**Δ = 332 = 2 户 × 166 毫**，恰为 `dailyRationMilli(2,1) × 2`）；**普通家户的缺口逐值未动**。
   - **机制（回代码核，非推演）**：日 1 的普通家户缺口来自**首日播种扣种子**（`SEED_MILLI_PER_MU = 8_000` × `MU_PER_HEX = 3_100`
     ≫ 家户储备量级），这是 `GENESIS_GRAIN_RESERVE_MULTIPLIER`（`:695`，用户 2026-10-23 裁定 30→15）的**压力验证口径**本身；
     叠加 W3 的日 1 饥荒死亡率 ⇒ 2 例死亡。**这与"单位户有没有创世粮"无关**（死亡户是普通户）。
   ⇒ 结论：`4801` 属于 **(d) 改基线** 那一类（测试批），或 **(a)/(c) 时点与相位**那一类（P4c），**都不在本批授权面**（任务书
   明令三者不取）⇒ 记 ⑥，不擅自改。
4. **不许"顺手"把普通家户也补一份**：那会把"日 1 的种子/口粮短缺"掩盖成"永远有粮"，正是任务书禁止的"把结构缺口写成正常读数"。
5. **单位户集合从 `Unit.households()` 现算，而不是手拼 `hh-unit:<govId>`**：手拼是"谁是单位户"的第二个拼写点，且与运行期
   注入经济侧的那个集合（`PopulationEconomyTimeParticipant.unitHouseholdExclusions` = Σ `Unit.households()`）不同源。

## ④ 偏离记录（与任务书/设计书不一致处，逐条给理由）

1. **"同一个量"取最贫一档（450 天）而非某个"官吏专属量"**（任务书说"同一个量/同一套算法"，但没说这是哪一档；代码里
   没有 `official` 的档）。理由：表里没有官吏/军户档；先例（流民户）已经在用最贫一档；任务书禁止自造常数。★ 这是**控制方
   口径的补白**，若控制方要"另设官吏档"，改 `unitHouseholdRationSlot()` 一处即可（但那就是新常数，需裁定）。
2. **没有顺手覆盖"运行期新建的单位户"**（`RaiseUnitPlan` / `SpawnArmyPlan` / `GovSelectExamineesPlan` 等）：它们**不是创世**
   （本批只补创世口径的一致性），且 `RaiseUnitPlan` 自己就有"粮/钱从辖区家户**转移**到新单位户"的通道（`ADJUST_ACCOUNTS_TYPE`，
   `RaiseUnitPlan.java:90-91`）—— 那是**转移**，与本批的**创世禀赋**是两件事，混一起会变成第三个口径。
3. **`GovGenesisZ6Test:351` 只报不改**（测试不在授权面），处置建议见 ③-3 与 ⑥-1。

## ⑤ 实测读数（命令与结果；全部在最终字节上跑）

改动文件 md5（`spotless:apply` 之后）：
`HouseholdSeeder.java 3abb3673…`、`EconomySeeder.java c34e35f5…`、`GovWorldBootstrap.java 0cd49999…`、
`ThreePowersGovBootstrap.java b0f17bbd…`、`AppLogSource.java 708c2d7e…`。

| 项 | 命令 | 结果 |
|---|---|---|
| ① 格式化 | `tools/mvn-lock.sh -q spotless:apply` | rc=0 |
| ② 编译（唯一硬底线） | `tools/mvn-lock.sh -DskipTests compile -am` | **rc=0**（BUILD SUCCESS，16 模块） |
| ③ **旧值基线**（改前，同一命令同一字节面） | `tools/mvn-lock.sh test -pl simos-app -am -Dtest='GovGenesisZ6Test' -Dsurefire.failIfNoSpecifiedTests=false` | **5 跑 3 红**：`:239` 1000→**850**、`:380` 16000→**13600**、`:484` 32000→**16000**（与 W5.5-fix 台账逐值相同） |
| ④ **新值**（同一命令） | 同上（改动后） | **5 跑 4 绿 1 红**；三条目标断言**全部恢复**：`:239` 效率 **1000**（旧 850）、`:380` securityLabor **16000**（旧 13600）、`:484` 120 天承诺 **32000**（旧 16000）。★ 剩 1 红 = `:351` 人口 4801 vs **4799**（改动前就成立，见 ③-3） |
| ⑤ 日 1 官吏户状态（INFO 真日志，改前 → 改后） | 同③④两轮 | `GOV_SERVICE_UNDERFED` **20 条 → 0 条**；`GOV_SERVICE_DESERTION` **10 条 → 0 条**（改前 day1 `ratePerMille=22 reason=hunger satietyPerMille=850 salaryShortfallMilli=0 stipendShortfallMilli=0`）；`GOV_SERVICE_COMMITMENT_DESERTION` **2 条 → 0 条**（改前 **day8 `ratePerMille=92 fledCount=1 membersBefore=2`** ⇒ 承诺 32000→16000） |
| ⑥ 日 1 供给读数（改前 → 改后） | 同③④两轮 | `GOV_EFFICIENCY_ZERO_SUPPLY … securitySupplyLaborMilli=13600`（day1，改前）→ **16000**（改后）；day10/100/101 改前 8000（逃 1 人后腰斩）→ 改后 **16000** |
| ⑦ 日 1 缺口池（改前 → 改后） | 同③④两轮 | `HOUSEHOLD_GAP_REPORTED householdsWithRationGap=78 → 76`；`rationGapTotalMilli=241298 → 240966`（Δ=332=2×166） |
| ⑧ **④ 单位户创世粮（逐户，small-world 真状态）** | 单文件程序（`java -cp <surefire test classpath> DumpGenesis.java`，走 `SmallWorld.state("Map1")`） | `UNIT gov-central households=[hh-gov-gov-central, hh-unit:gov-central]`：`hh-gov-gov-central balances={grain=1000000, cloth=100000} money={silver=100000, copper=100000} population=0`（**国库：未加口粮**）；`hh-unit:gov-central balances={grain=75000} money={} population=2`；`hh-unit:gov-province balances={grain=75000} population=2`（**改前 = 无 `grain` 键 = 0**，证据：改前 day1 satiety 850 + 该户计入 78 户缺口） |
| ⑨ **④ 单位户创世粮（逐户，three-powers）** | 直接跑子进程装置 `java -cp <同上> CrossProcessDeterminismChild /tmp/… Map1` 后读 `actor.json` | `hh-unit:gov-tp-silver / -gold / -copper` 三户各 `balances={grain":75000}`、`money={}`；INFO 三条 `world=three-powers … grainMilli=75000` |
| ⑩ 日志面自证（§一.9） | 同④/⑨ | `GENESIS_UNIT_HOUSEHOLD_RATION` INFO：small-world 2 条、three-powers 3 条，字段齐（who/much/basis）；`origin=genesis-seed originKind=system` |
| ⑪ 三国创世仍确定性（新命令不掉字节） | `tools/mvn-lock.sh test -pl simos-app -am -Dtest='CrossProcessDeterminismTest' -Dsurefire.failIfNoSpecifiedTests=false` | **1/1 绿**（rc=0；`jvms=4 身份=4/4`，economy/actor/… 10 份产物 sha256 跨 4 JVM 唯一） |
| ⑫ 受影响类的既有回归（单条命令两班） | `… -Dtest='Z7HungerTest,GovToolsZ6Test' …` | **25/25 绿**（rc=0）：`Z7HungerTest 3/3`（含"吃饱户 satiety 表稀疏 + 劳动逐值不变" —— 官吏户 day1 不再有缺口）、`GovToolsZ6Test 22/22` |

★ **没有做**：`verify` / `package` / 全量测试 / 其余单条回归（见 ⑥-3）。

## ⑥ 未完成 / 未验证

1. **`GovGenesisZ6Test` 仍非 5/5（1 红）**：`:351` 第 1 天人口 **4801 vs 4799**（2 例日 1 饥荒死亡，死的是**普通户**）。
   ★ **不是本批根因**（判别实验见 ③-3：改动前同一读数）。可行修法（**都不在本批授权面**）：(d) 测试批按新世界重基线这条期望；
   或 (a)/(c)（P4c 财政闭环那批）；或改 `GENESIS_GRAIN_RESERVE_MULTIPLIER`/种子口径（用户 2026-10-23 已裁定 30→15）。
2. **`EconomyCycleHealthTest`（three-powers 逐轮成交指纹）需重基线**：本批给三国官吏户各 +75,000 毫粮 ⇒ 逐轮成交分布必变
   （它在本批**之前就已红**：`target/surefire-reports` 14:24 那份 `Tests run: 1, Failures: 1`）。本批**未重跑**（该用例单跑 70s、
   XML 近 1GB，磁盘余量 21G —— 有意不跑）。
3. **未跑**：`verify` / `package` / 全量测试。**建议测试批优先复跑**（按类名，只报不改）：`EconomyCycleHealthTest`（重基线）、
   `GovGenesisZ6Test`（第 4 条按 (d) 或按 ③-3 结案）、`Z7FleeTest` / `GovAdminSalaryBudgetZ6Test` / `Z7D4BudgetLedgerTest` /
   `RaiseUnitToolTest` / `Z7MarketExclusionTest`（用合成 `hh-unit-*` 或 bridge 级夹具 ⇒ **预期不受影响，但本轮未实测**）。
4. **"运行期新建的单位户"仍无创世式口粮**（刻意，见 ④-2）：军户在 `RaiseUnitPlan` 走"从辖区家户转移粮/钱"，`grain=0` 的
   征召批次仍会首日断顿 —— 那是 P4c 的时点缺口，不是本批口径面。
5. **`unitHouseholdRationSlot()` 的档位是控制方口径的补白**（见 ④-1）：若控制方要"官吏专属天数档"，需裁定（= 新常数）。

---

# W5.5-fix3（非确定性 + 池空读数）

> **状态**：发现 2（池空读数）**已修 + 已自证**；发现 1（"非确定性"）经判别实验查明 **不是运行期非确定性** ——
> 两个"形态"是**两份不同的编译产物**（B = W5.5-fix2 那 75,000 毫创世粮**没生效**的世界）。
> **改动的 main 文件**：`simos-economy/src/main/java/io/mosire/simos/economy/time/MoneyMarketSettlement.java`
> （md5 `3b7c918ca125535d56df5434d1550b18`，`spotless:apply` 之后）——**唯一**。未 `git commit`。

## ① 复现刻画（逐次读数；A / B / 第三形态计数）

**命令 A（任务书逐字，单类）**：`tools/mvn-lock.sh test -pl simos-app -am -Dtest='EconomyCycleHealthTest' -Dsurefire.failIfNoSpecifiedTests=false`

| 轮 | 时刻 | 读数 | 形态 |
|---|---|---|---|
| run1 | 15:23:02→15:24:27 | 轮次=96 零成交=0 min/中位/max=**15/43.0/140** Σfills=3522 | **A** |
| run2 | 15:24:27→15:25:51 | 同上（逐值相同） | **A** |
| run3 | 15:25:51→15:27:17 | 同上 | **A** |
| run4 | 15:27:17→15:28:43 | 同上 | **A** |
| run5 | 15:28:43→15:30:08 | 同上 | **A** |

★ **run1 与 run2 的完整日志去时间戳后逐行相同**：2 946 958 行中只有 maven 自己的 462 行（计时/组件端口/`duration_ms`）不同 ⇒ A 形态在**字节面**上可复现。

**命令 B（账本 §④-2 的复现命令 = 15:03 出过 B 的那条，改后重跑 3 次）**：
`… -Dtest='SimosToolsTest,McpServerTest,McpPortTopologyTest,McpCoverageTest,GovGenesisZ6Test,EconomyCycleHealthTest' …`
⇒ merged1（15:50:53）/ merged2（15:53:07）/ merged3（15:55:16）**全部 A**（不再现 B）。

**自建探针**（`/tmp/w55fix3`；同一 `ShellConfig`/同一 mapId/同一 8×30 天推进，逐字节等价于用例）：

| 装置 | 世界数 | 结果 |
|---|---|---|
| 同 JVM 内先后两个世界 | 2 | 逐字节相同（settlement 111 198 行、digest `aac9860a04245ff6`） |
| 独立 JVM 采样（settlement+market 采集） | 16 | 全部 A（digest 全等） |
| **可控盐**（见 ②-2） | 12 | 全部 A |
| **identity-hash 位移**（见 ②-3） | 12 | 全部 A |

★★ **合计：A × 56、B × 0、第三形态 × 0。**（另有 **1 次故意的** pre-fix2 class 前置实验，得到 B 族 —— 见 ②-4，不计入。）

**修完后的单一指纹（交给测试批把守卫收回单一形态）**：

```
逐值分布（笔数×轮数）：
15×1, 21×11, 22×8, 23×4, 24×6, 25×5, 26×10, 30×1, 41×1, 43×4, 44×5, 45×24, 46×2, 47×5,
48×1, 49×1, 50×1, 51×1, 56×1, 57×1, 66×1, 92×1, 140×1
min/中位/max = 15 / 43.0 / 140；轮次 = 96；零成交轮次 = 0；Σfills = 3522（== MARKET_FILL 3522 行）
```

## ② 根因（判别实验链）

1. **同 JVM 双世界逐字节相同** ⇒ 只可能是"**每 JVM 常量**"型原因（不是逐对象的 identity hash 型，也不是并发/时序型）。
2. **可控盐实验 —— 排除 `Map.of`/`Set.of`/`Map.copyOf` 盐化迭代序（本仓已具名那族）**：
   用 `sun.misc.Unsafe` 把 `java.util.ImmutableCollections.SALT32L` 钉成 12 个值
   （`0 / 1 / deadbeef / 12345678 / 5a5a5a5a / ffffffff / 80000000 / 7fffffff / abcdef01 / 11111111 / 22222222 / 33333333`），
   **并打印 `Map.of/Set.of` 的迭代序证明补丁真的生效**（`deadbeef ⇒ setOf5=[a,e,d,c,b]`、`ffffffff ⇒ [b,a,e,d,c]`、`abcdef01 ⇒ [a,b,c,d,e]`）
   ⇒ **12 次全部 A**。⇒ 盐化序**不是**本现象的成因。
3. **identity-hash 位移实验 —— 排除枚举 / `Class` 的 `Object.hashCode` 序**：
   在 `main` 第一行起把 identity-hash 序列推进 12 个不同步数（`0/1/2/3/5/8/13/21/34/55/100/1000` 次 `System.identityHashCode(new Object())`）
   ⇒ **12 次全部 A**。
4. **★ B 的来历（决定性）**：B 与本台账 **`:92`** 记的**W5.5-fix2 之前**那次读数**逐值相同**
   （`15×1,21×18,22×5,24×17,25×2,26×2,30×1,37×1,38×1,39×4,40×9,41×7,42×11,43×7,44×3,46×1,48×1,51×1,53×1,66×1,85×1,135×1`）；
   而 fix2 的三个源文件与当时的 md5 **逐字节相同**（`ThreePowersGovBootstrap b0f17bbd…`、`HouseholdSeeder 3abb3673…`、`EconomySeeder c34e35f5…`）。
   **判别实验**：把 **HEAD 版（pre-fix2）`ThreePowersGovBootstrap.class` 放在 classpath 最前**（其余全为当前字节）⇒
   分布**变成 B 族**（digest `912dbe7e14bef441`、min **15** / max **135** / day120 = **135**、21 个取值；与 B 的差只有 3 处：
   `37/46/66` ↔ `38/47/69` —— 那是 W5.5 的国库审计腿与 R2 的差）。
   ⇒ **B = "fix2 的创世粮没生效"的世界**。在源码逐字节相同的前提下，这只可能是 **`target/classes` 陈旧**：
   `maven-compiler-plugin` 默认增量判据 = 源 mtime vs 类 mtime ⇒ 被**别的并发 Maven 进程**刷新过的 `.class`
   会让本轮**跳过编译**（AGENTS §一.1 / §一.4 点名的那条纪律）。
   ⇒ **不是"同一份字节给出两个结果"**，因此 main 侧**没有可修点**；两形态守卫应收回单一形态（测试面，不在本批授权面）。
5. **旁证（同族排查收口）**：`TimeProposalResolver:141` 把逐参与者的 `Map.of(...)` 变更集并进 **`TreeMap`**（namespace 字典序）
   ⇒ 落盘 `changeset_json` 的键序与盐无关。实测：三个不同盐的 world（240 天 / 9 条 revision）的 `changeset_json`
   **逐行 sha256 相同**（`rev1 a7730471e63d … rev9 9d02e7527c1a`）。
6. `CrossProcessDeterminismTest` **1/1 绿**（`jvms=4 身份=4/4`；economy/map/social/unit/sd/actor/gov/army/checkpoint/seed-changeset 十份产物 sha256 跨 4 JVM 唯一）。

## ③ 发现 2 的修法（池空 ⇒ 缺口读数对称）

**落点（唯一文件 `MoneyMarketSettlement`）**：

| # | 落点 | 改前 | 改后 |
|---|---|---|---|
| 1 | `settleZone` 配对段入口（`:391`） | `if (demandTotal > 0L && supplyTotal > 0L) { … }`（池空 ⇒ 整段跳过） | `if (demandTotal > 0L && supplyTotal <= 0L) { …unmet=Σ缺口 + 逐户 logUnfilled(pool-empty)… } else if (demandTotal > 0L) { …原配对… }` |
| 2 | 借款人序（`:519` `borrowerOrder(...)`，**唯一拼写点**） | 池非空那条路内联排序 | 抽成方法，**两条路共用同一把尺**（结算序名次 → 稳定 `HouseholdId`） |
| 3 | 缺口发射（`:535` `logUnfilled(...)`，**唯一发射点**） | 内联 `EventLog…`（只有 pool-exhausted 一条归因） | 抽成方法，归因作参数 |
| 4 | 归因常量（`:225` / `:228`） | 字面量内联 `"pool-exhausted（…）"` | `POOL_EXHAUSTED_REASON` / `POOL_EMPTY_REASON = "pool-empty（本区放贷池一分钱都没有：没有任何家户有闲钱；缺口退到未付价款记债）"` |

★ **数值逐值不变**：`unmetMilli` 只进 DEBUG 读数（`ZoneRound.unmetMilli → logZone` 的 `MONEY_MARKET_WHY`），
不参与利率（仍按 demand/supply 自适应）、金额、状态、债权、变更集。

**实测读数**（探针 `/tmp/w55fix3/econprobe/PoolEmptyProbe.java`，复刻 `MoneyMarketSettlementTest` 的池空夹具）：

| 放贷人现金 | demand | supply | lent | **unmet** | `MONEY_MARKET_UNFILLED` |
|---|---|---|---|---|---|
| **0（池全空）** | 30 | 0 | 0 | 改前 **0** → 改后 **30** | 改前**一行不发** → 改后 `borrowedMilli=0 unmetMilli=30 reason=pool-empty（…）` |
| 20（池非空不够） | 30 | 20 | 20 | 10 | `borrowedMilli=20 unmetMilli=10 reason=pool-exhausted（…）` |
| 200（池够） | 30 | 200 | 30 | 0 | 不发（无缺口） |

★ 自洽恒等式 **`unmetMilli == demandMilli − lentMilli`** 三行逐值成立；两条归因**互斥且具名**。

## ④ 自证（命令与结果，全部真跑）

| 项 | 命令 | 结果 |
|---|---|---|
| ① 经济模块用例 | `tools/mvn-lock.sh test -pl simos-economy -am -Dtest='MoneyMarketSettlementTest' …` | rc=0，**7/7 绿** |
| ② 池空单例（既有断言） | `… -Dtest='MoneyMarketSettlementTest#emptyPoolFallsBackToDeferredPaymentToTheSeller' …` | rc=0，**1/1 绿** |
| ③ 合并复现命令 ×3 | `… -Dtest='SimosToolsTest,McpServerTest,McpPortTopologyTest,McpCoverageTest,GovGenesisZ6Test,EconomyCycleHealthTest' …` | **3/3 A** |
| ④ 单类命令 ×5（修完后） | `… -Dtest='EconomyCycleHealthTest' …` | **5/5 A**，逐值相同（见 ① 指纹） |
| ⑤ 跨进程字节 | `… -Dtest='CrossProcessDeterminismTest' …` | **1/1 绿**（4 JVM × 10 产物唯一） |
| ⑥ 格式化后合并复验 | `-q spotless:apply` 后 `… -Dtest='MoneyMarketSettlementTest,CrossProcessDeterminismTest,EconomyCycleHealthTest' …` | rc=0：**7/7 + 1/1 + 1/1 绿**，240 天指纹**逐值不变** |

## ⑤ 会改变数值行为的清单

**无。** 改动只影响两处**读数**：`ZoneRound.unmetMilli`（DEBUG）与新增/复用的 `MONEY_MARKET_UNFILLED` 行。
实测证据：240 天逐轮成交分布、轮次数（96）、零成交轮次（0）、Σfills（3522）在修前修后**逐值相同**。

## ⑥ 会让既有测试失效的清单（按类名，只报不改）

**无。** `MoneyMarketSettlementTest` 7/7 绿 —— 其 `emptyPoolFallsBackToDeferredPaymentToTheSeller` **只**钉 `MONEY_MARKET_WHY`
的 `demandMilli/supplyMilli/lentMilli/loans/borrowers`，**没有**钉 `unmetMilli=0`、也没有钉"UNFILLED 不发"，
故本次修复不与其冲突（`:562` 的注释里那句"池空时读 0、该行也不发"已成为历史描述，**建议测试批在该用例里补一条正向读数** ——
`unmetMilli == demand − lent`，并在注释里改掉那句过期描述）。
★ 建议测试批同时把 `EconomyCycleHealthTest` 的两形态守卫**收回单一形态**（指纹见 ①）。

## ⑦ 未完成 / 未验证

1. **未跑**：`verify` / `package` / 全量测试（本批只跑 `test`）；**未跑**真 `-Dsimos.*` 属性轮。
2. **未做**：把"两形态守卫收回单一形态"、以及在池空用例里补正向读数（`src/test/**` **不在授权面** ⇒ 只报不改）。
3. **未当场复现 15:03 的 B**：本批 56 轮全部 A。B 的成因链是**证据 + 判别实验**推出来的
   （B 逐值 == pre-fix2 读数；fix2 源 md5 与当时逐字节相同；把 pre-fix2 `ThreePowersGovBootstrap.class` 前置即得 B 族），
   **不是**当场抓住的现场（那个现场已随时间消失：并发 Maven 的 `target/classes` 覆盖不可回溯）。
4. **未做**：并发 Maven 抢 `target/` 的现场重放（本仓纪律不允许两轮 Maven 并行，§一.1）。
5. **未验证**：`/tmp` 里的判别装置（可控盐 / identity 位移 / classpath 前置）**不进仓**，故它们本身没有常驻护栏；
   若控制方要"把盐/身份序这件事钉成常驻判据"，那是**新装置**（不在本批授权面）。

---

# W5.5-fix4（删孤儿 trimUnmet + 借粮残留自扫）

**范围**：只改 `simos-economy/src/main/**`（含 1 行 `AccountIntentBuffer` 注释）；未碰 `src/test/**`、其他模块、`docs/**`、`AGENTS.md`；未 `git commit`；未跑 `test`/`verify`/`package`/`clean`。

## ① 无调用者证据（trimUnmet）

| # | 证据 | 命令 | 结果 |
|---|---|---|---|
| 1 | 三个 main 面零读者 | `grep -rn "trimUnmet" simos-economy/src/main simos-app/src/main simos-economy-api/src/main` | **仅 1 命中**：`EconomySettlement.java:6585` 声明行本身 |
| 2 | 测试面零读者 | `grep -rn "trimUnmet" --include=*.java simos-*/src/test` | **rc=1（0 命中）** |
| 3 | 无反射调用面 | `grep -rn "getDeclaredMethod\|getMethod(\|setAccessible" --include=*.java simos-economy/src/main simos-app/src/main` | 0 命中（本模块不用反射调私有方法） |
| 4 | 「曾是活的」取证 | `git grep -n trimUnmet HEAD -- simos-economy/src/main` | HEAD `:6851` / `:6883` 两处调用点，**都在被删的借粮腿里**；声明在 `:7084` ⇒ 孤儿是 W5.5 删腿造成，不是历来如此 |
| 5 | 非代码命中 | 全仓任意扩展名 | 只剩 `.superpowers/sdd/**` 的历史台账（§五.4 留痕，不改）与 `2026-10-11-w5-minting-into-market/w5fix-probe/EconomySettlement.java.fixed`（**探针副本，不参与编译**） |

**结论**：确认无任何调用者 ⇒ 按 §一.11 整段删除。

## ② 删除落点（file:line + 行数）

| # | 落点 | 原行号 | 删/改 | 内容 |
|---|---|---|---|---|
| 1 | `simos-economy/.../time/EconomySettlement.java` | 原 `:6579-6603` | **删 25 行** | `trimUnmet` 的 javadoc（6）+ 方法体（18）+ 尾随空行（1）；删除后 `:6580` 起直接是 `E1：退出经营者…` 的 javadoc（10394 → 10369 行） |
| 2 | `simos-economy/.../time/DebtContractBook.java` | 原 `:523-568` | **删 46 行** | `absorb`（javadoc 9 + 方法 17 + 空行 1）+ `subset`（javadoc 6 + 方法 12 + 空行 1）；两者 javadoc 自述"**只服务…并行借粮分区**" |
| 3 | 同上 | 原 `:29`（类注 `<pre>` 表） | 删 1 行 | `absorb 并行分区结果的落回口：…` 条目（类注同步清理措辞） |
| 4 | 同上 | `:13` / `:17`（import） | 删 2 行 | `java.util.LinkedHashMap` / `java.util.function.Predicate` 变成零读者（唯一读者是 `subset`） |
| 5 | 同上 | 原 `:38` 附近（`dueCycle` 覆盖规则） | 改 1 处 | 括注"（借粮路径的最新一笔到期）"随借粮腿退役失效 ⇒ 删括注（规则本身不变） |
| 6 | `simos-economy/.../time/AccountIntentBuffer.java` | `:53` | 改 1 行 | 分区阶段清单"（消费/投入/收获/**借粮**那类按 hex 分区的阶段）"⇒ 删"借粮"（W5.5 已改同款 `:108`，漏了这一处） |
| 7 | `simos-economy/.../time/EconomySettlement.java` | 原 `:1646-1648` | 改 3 行 | 死符号引用 `lendDeficits`（已被删的方法名）+ 悬空指针"借是最后手段，见 4b"（4b 已整段退役）⇒ 改为只述"市场自留" |

`DebtContractBook` 646 → 597 行。删后全仓 `trimUnmet` / `DebtContractBook.absorb` / `DebtContractBook.subset` 的 `--include=*.java` 检索 **rc=1（0 命中）**。

## ③ 自扫结果（整模块 329 个 main 文件，索引式签名扫描 + **逐个人工复核**）

方法：复用 `/tmp/orphan_scan3.py`（v3：private 成员只算本文件读者、字段按裸标识符读）的副本，目标面 = `simos-economy/src/main/java` + `simos-economy-api/src/main/java` **全部 329 个文件**、1891 个候选成员、31 条告警；参考面 = 本仓全部 `simos-*/src/**`（测试标 `[T]`）。**31 条全部人工复核**（扫描器有已知假阳性：方法引用 `X::m` 被当"读"丢弃、单字符串字面量实参被算成 arity 0），逐条结论如下。

### ③-A 确认为孤儿且**只服务借粮腿** ⇒ 已删（3 项）

| 成员 | 原位置 | 证据 |
|---|---|---|
| `EconomySettlement.trimUnmet/4` | `:6585` | 见 ① |
| `DebtContractBook.absorb/2` | `:532` | 现全仓 0 引用；HEAD `EconomySettlement:4378` 的唯一调用点位于 `PartitionPlan.of(SettlementStage.LENDING, …)` / `LendingPartition` 的**借粮并行分区**里；javadoc 自述"只服务…并行借粮分区" |
| `DebtContractBook.subset/2` | `:556` | 同上，HEAD 调用点 `:4325`（`subset(debts, contract -> keys.contains(contract.debtor()))`），W5.5 删腿后零读者 |

### ③-B 真孤儿但**与借粮腿无关**（多数 HEAD 即零读者，非本批造成）⇒ 只报不改（7 项）

`EconomySettlement.withdrawFromTreasury/7 :9242`（国库取款；HEAD 即 0 调用者）、`AccountIntentBuffer.forCoordinator/4 :140`（**冻结写机制**的唯一"允许冻结"工厂；`freezeGoods/freezeMoney` 本身仍被 `BufferedAccountTables:131/179` 调用，是活机制，删它会牵动 `FreezeIntent`/协调器语义 ⇒ 需 owner 裁定）、`HouseholdRouting.actorsOf/1 :199`、`LaborSourcePolicy.isLaborPay/1 :75` 与 `sourceOf/1 :80`、`HouseholdClassRule.classifyDetailed/9 :152`（`:134` 的 `:143` 三参重载才是活的那个）、`EconomyEnterpriseSettlement.organize/12 :221`（javadoc 自述"保留给既有调用方/用例"，实测两者皆无；`:269` 的 13 参重载是生产路径）、`MarketRoundStats.weighted/4 :286`。

**公开常量零读者**（SpotBugs 不报 public；属契约/vocabulary 面，不属借粮腿）：`PortRule.RESTRICTION_FULL_PER_MILLE :53`、`MoneyVocabulary.COPPER_SPECIE :128`、`DefaultProductionModes.ROLE_PRINCIPAL :139 / ROLE_LABORER :154 / ROLE_DESTITUTE :157`、`EconomyMeta.RUNTIME_VERSION_SEVEN_HEX_V1 :41`。

### ③-C 扫描器假阳性（复核为**活代码**，不动）（5 项 + 3 项测试夹具）

`EconomyCodec.mergeDebtContracts :1110`（活：`:918`/`:1001` 的 `EconomyCodec::mergeDebtContracts` 方法引用）、`MerchantCapacityPool.laneBlockedReason :1018`（活：`:988` 唯一调用）、`AccountIntentBuffer.requireCoordinatorFreeze :309`（活：`:252`/`:279`，实参是单字符串字面量 ⇒ 被算成 arity 0）、`EconomyChangeSet.householdId :501`（活：`:388`/`:390`/`:405` 方法引用）、`EconomyAuthorizeGovMarketOrderHandler.reject/3 :274`（活：`:120`…`:263` 多处）；`serialVersionUID ×3`（`EconomyGovServiceCommitments:145`/`EconomyGovUnitUpserts:180`/`EconomyIndustryUpserts:116`）由 JVM 序列化反射读 ⇒ 非孤儿。
`TEST-ONLY`（测试夹具读者，不删）：`MarketSettlement.planOrders/4 :1471`、`MarketSettlement.clearOncePerCycle/4 :1534` 与 `/7 :1590`、`ModeMigrationPolicy.claimedOwnershipStakes/1 :1730`。

### ③-D 真孤儿但**不属借粮腿**、且删/接线需裁定 ⇒ 只报不改（1 项）

`FxSettlement.FX_RATE_WINDOW_FILLS :103`（包内 `static final int`，**全仓 0 读者**）—— 属 W5.5 **外汇腿**而非借粮腿；其 javadoc 声称"实际汇率采样窗口 = 120 = 一个产业周期的成交数"，而代码里的采样另有取法 ⇒ 删掉或接上线都改变读数口径，交 owner 定。

### ③-E 注释里的过期引用

**已改（4 处，见 ②-5/6/7）**：死符号名 `lendDeficits`、悬空阶段指针"见 4b"、已删阶段"借粮"混在活清单里、`absorb` 的类注条目、`dueCycle` 的借粮括注。

**只报不改（25 处）**——它们是"当前日序/流程"的散文描述在借粮腿退役后未同步，**不是死代码**；且 W5.5 自己的体例是"留 `★★★ W5.5…已整段退役` 墓碑"，批量改写 25 处散文会淹没本批 3 处删除的 diff，口径（改写成现状 / 补墓碑 / 保留留痕）需 owner 定：
`EconomySettlement:156 / 313 / 834 / 936 / 1133 / 1183 / 1574 / 1578 / 1583 / 2001-2002 / 2679 / 6349 / 6461 / 6471 / 6482 / 6540 / 9568 / 9725 / 10153`；
`EconomyHouseholdGap:12`、`DebtCapacityBook:40`、`SettlementIndex:51`、`ClassSlideSettlement:61`、`EconomyLiquidationSettlement:48`、`DebtCapacity:202`、`ProductionLedger:563`；
模块外：`simos-app/src/main/resources/webui/panels.js:516`（"v1 只在同格借粮时产生"）。
★ 已确认为**准确历史留痕、不动**的墓碑：`EconomySettlement:109-110 / 1391 / 1411 / 2054 / 2727-2730 / 3183 / 3220 / 3432 / 6376-6377`、`SettlementStage:41-42`、`TransferReason:42`、`MarketSettlement:291`。
★ `DebtCapacityBook.lendableOf` **仍在用**（定义 `:512`、读点 `:352`），`MarketOrderKind.LENDING` 是**货币腿**的挂单档、活的 —— 二者都不是借粮腿残骸。

## ④ 真实命令与 rc（全部真跑，最终字节上）

| # | 命令 | rc | 结果 |
|---|---|---|---|
| 1 | `tools/mvn-lock.sh -q spotless:apply` | **0** | 只动了本批 3 个文件（`find simos-*/src -name '*.java' -newermt '-6 minutes'` 逐条核过） |
| 2 | `tools/mvn-lock.sh -DskipTests compile -am` | **0** | Reactor **16/16 SUCCESS**（`EconomySimos 2.341s`、`SimosApp 2.171s`） |
| 3 | `tools/mvn-lock.sh -Dspotbugs.failOnError=false spotbugs:check -pl simos-economy` | **0** | `BugInstance size is 0` / `No errors/warnings found`；新 xml 里 `<BugInstance` = 0、`UPM_UNCALLED_PRIVATE_METHOD` = 0 |
| 4 | 备份→还原：`cp -p target/spotbugsXml.xml /tmp/spotbugsXml.w55fix4.bak` → 跑 ③ → `cp -p` 还原 | 0 | md5 **跑前 = 跑后 = `954c241c0f36af91bf4ad6fb2761ffb1`**（还原后 UPM 条目数 2，与原件一致） |

★ **非门禁等价（如实标注）**：③ 是 `spotbugs:check` **直调**，不跑生命周期（分析的是第 2 步 `compile` 落下的 `target/classes`），且本次 aux classpath 实际解析到 `~/.m2/repository/io/mosire/simos-actor-api/0.1.0-SNAPSHOT/…` 这类**旧 SNAPSHOT jar** ⇒ 它只能证明"本模块 private 未调用方法已清零"，**不能替代 `clean verify` 的门禁**。

## ⑤ 会改变数值行为的清单

**空。** 本批是纯删死代码（3 个零读者成员）+ 4 处注释措辞 + 2 个失效 import；不触碰任何公式、阈值、字面量、执行路径。

## ⑥ 未完成 / 未验证

1. **未跑** `test` / `verify` / `package` / `clean`（本批授权面不含），故"删掉的方法没有被反射/测试间接使用"由静态检索（①-1/2/3）保证，**没有**运行期证据。
2. **未做变异自证**：本批无判据可变异（纯删），按 §三 如实记"无可变异项"。
3. **未删的孤儿**见 ③-B / ③-D（7 项 + 6 个公开常量 + `FX_RATE_WINDOW_FILLS`）—— 需 owner 决定是否另开批。
4. **未同步的散文漂移**见 ③-E（25 处，含模块外 `panels.js:516`）。
5. SpotBugs 自证**非门禁等价**（见 ④ 末注）；真门禁 `clean verify` 未跑（纪律禁止）。
