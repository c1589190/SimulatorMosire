# P-T1b 实现架构账本 —— 跨区过境税 + 区内市场税：真收款进国库

日期：2026-10-10　责任区：P-T1b（写码 Agent）　状态：**生产代码完成 + 编译绿**（未跑 test/verify；未提交）
约束设计书：`docs/superpowers/specs/2026-10-09-port-policy-and-zone-efficiency-design.md` §13（+§12/§10–§11）；
`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md` §2.2/§2.5/§2.6/§3（I-C1/I-C2/I-C3/I-C10）/§5（T2/T3/N1/N4）/§6.2（V-2/V-11/V-18）；AGENTS §一.9/§一.11。

## 1. 关键调查结论（file:line → 结论 → 影响）

| # | 证据（file:line） | 结论 | 影响 |
|---|---|---|---|
| 1 | `MarketSettlement:5487`（`executeTrade`；钱腿/运费腿/税腿 `:5620-5700` 铸在 `buy.currency`） | 成交结算只有**两条**调用点：`pairUp:4797`（内 `:4935`）与 `replayRegionOutcome:3712`（内 `:3729`）；钱腿、运费腿都在这里 | 三层税只需装在这一个字面上（§2.6 第 7 步），无第二条绕过路径 |
| 2 | `MarketSettlement:4797`（`pairUp`）+ `:6067`/`:6162`（`affordableQuantity` → `exactAffordableUpTo` → `totalCostAtMost:6100`） | 安全边界是**逐笔精确封顶**（不是预分配的边距）：任何成交前都按"货款+运费 ≤ payable"重算 | 税必须进 `totalCostAtMost`，否则有税时会把家户账扣成负数 ⇒ fail-closed 400（这是本批唯一可能"整轮炸"的接缝） |
| 3 | `EconomySettlement:8196`（`applyTransfer` 两遍：先全量校验后落账）+ `:8326`（`validateApplyTransfer` 的 `balance − amount ≥ frozen` 逐腿判）+ `:8279`（`debitHouseholdMoney`） | 逐腿校验的是"扣完还剩 ≥ 冻结"；冻结 = 绝对表 `baseFrozenMoney + 轴剩余`，而 `payable = 可花 + 本单冻结 ≤ 余额` | `total` 含税 + `exactAffordableUpTo` 含税 ⇒ 逐腿校验恒成立（证明见 §3-4），不必给税单独开口子 |
| 4 | `MarketSettlement:872`（`copyForWorker` 逐字段带过五个 `next.xxx`）+ `:3545-3600`（worker 本区副本） | worker 只带本区家户的账户表，但 `householdOfActor` 是**全局**表；`applyTransfer` 的收款方走 `setMoney`（getOrDefault+put） | 国库户不在 worker 副本里也能落账（钱写到副本、回放时丢弃）；**但必须把税表带过克隆**，否则 worker 与协调器算出的 `frozenRemaining/spentMilli` 漂开 ⇒ `replayRegionOutcome` 具名抛 |
| 5 | `MarketSettlement:3729`（回放逐笔重跑 `executeTrade`）+ `:1546`（报告从**协调器 ctx** 建） | 读数只能在协调器累加；worker 的累加必须丢弃 | `ctx.taxItems` 与 `ctx.fills`/`ledger` 同一条纪律（worker 累加、交回即丢） |
| 6 | `MarketReport:96`（identity 键 WeakHashMap）+ `:354`（`withRegulatedTariff` 最长重载，旧签名 `:302` 委托） | 读件的既有形制 = "record 组件不动 + identity 侧表 + 旧签名委托" | 不新增 record 组件（否则 `MarketReport` 20 个构造点全串改）；税读口走同一条形制 |
| 7 | `GovernmentIds:32`（`ofUnit` = `gov-unit-<govUnitId>`）+ `EconomyData:1721-1755`（跨表守卫：国库须 HOUSEHOLD 且 = `hh-gov-<unitRef>`）+ `EconomySettlement:9140`（`governmentTreasuryHouseholds`，**不按前缀猜**） | "哪个国库收"有权威答案：GOV 单位 id → 政府记录 → `Government.treasury()` | 税表带 `GovernmentShare(governmentId, treasury, weight)`；非 HOUSEHOLD 国库**具名排除**（账户主体只有家户） |
| 8 | `MarketZone:22-27,79`（`legalTender` = 区的唯一货币事实）+ `MarketZoneBook:55-70`（`zones` 规范序） | "收税政府的法定币"在经济侧唯一可判的形态 = **该区法定币** | 税表带 `ZoneTaxTable.legalTender`；折算起点就是它 |
| 9 | `MarketRegulation:20-34,57`（P-T1c 后只剩 `tariffPerUnit`）+ `EconomySettlement:1757`（唯一构造 = 默认空） | 区内税钩子在位但**生产路径恒为空**；`MarketSettlement:5731` 只把它折成读数 | 本批把该钩子接上真收款：**改动本身在生产缺省下零数值影响**，但机制必须成形（否则"三层"永远只有两层） |
| 10 | `OwnershipBooks:63`（`REASONS_NOT_FOLDED`）+ `:75-95`（`fold` 只折 `transfer.goods()` 腿、不折钱）+ `EnterpriseProfitBook:505/700`（按原因分类，非穷尽、无 default 校验） | 新的**纯货币**税腿不会被折进产权账；利润簿不认这三档 = 不重复计 | 货币守恒不受影响；"M1 利润含税"是后续批次（本批不接，见 §4-6） |
| 11 | `PortEnforcementInput:1-60`（P-T1a 刚冻结：表里存 `1000−E`，`isActive()` 是闸的判据） | 把税塞进它会污染 `isActive()` 语义（只设税 ⇒ 管制力全 0 ⇒ 看上去"没注入"） | 另起 `PortTaxInput` + 另开注入口（`EconomyDayStepper.updatePortTax`） |

## 2. 实现架构（代码怎么把这个世界长出来）

```
写侧（法律）  gov.SetPortPolicy ─▶ GovPortPolicy{commodityRules: id→PortRule(入/出限制, 入/出税)}   （P-T1a 已在位，未改）

折 算（组合根）PortRegimeBridge.compute（P-T1b 扩）逐区：
    w_k = 暴露边条数（P-T1a 的同一张表）      Σw = 全区暴露边（**含三不管那一份**）
    ① 区级税率（两个分量各自加权平均）：perUnit=⌊Σ(w_k×从量额)÷Σw⌋、adValorem=⌊Σ(w_k×从价额)÷Σw⌋
    ② 收税政府 = 归属键里"政府记录的国库是 HOUSEHOLD"的那些：GovernmentShare(govId, treasury, w_k)
    ③ 区法定币 = MarketZone.legalTender()
    ⇒ PortTaxInput{ zone → ZoneTaxTable(legalTender, governments, ratesByCommodity) }
    ★ 只要"有政府、有种"就注入（**不**依赖有没有口岸限制）—— 否则区内市场税收不到钱

注 入（组合根）PopulationEconomyTimeParticipant：portRegime.taxActive() ⇒ stepper.updatePortTax(taxInput)
    （与 updatePortEnforcement 并列；闸与税各管各的）⇒ EconomyDayStepper.step → settleOneDayInto(+portTax)
    ⇒ marketRound.withGovMandates().withPortEnforcement().withPortTax()（逐字段带过 + 具名 ERROR 契约守卫）

计 税（经济）  MarketSettlement.executeTrade（唯一计税点调用处）：
    ① 只在跨区在途收口岸税：出口税 = 源区 EXIT 税率、进口税 = 目的区 ENTRY 税率（各一层）
    ② 只在区内即时/区内跨格收区内税：卖方的 MarketRegulation.tariffPerUnit（毫区法定币/单位）
    ③ MarketTaxBook.assess：税基=货款(毫买方币, 不含运费) ⇒ 从量税率折一次 ⇒ 区级总额
                           ⇒ 按 w_k 分摊（floor + 余数给规范序最后一家）
    ④ 钱腿逐条 buyer → treasury（TransferReason 三档具名），钱铸在 buyer.currency
    ⑤ 可负担：totalCostAtMost 内含税 ⇒ 判得起才成交（同一方法 ⇒ 两处不可能漂开）
读 口（P-T4）  ctx.taxItems → MarketReport.taxItems()/taxByCurrency()（按币分列，禁跨币求和）
日 志（§一.9）  INFO: MARKET_TAX_COLLECTED(层×政府×币) / _TOTAL(币) / PORT_TAX_REGIME_INJECTED；
                TRACE: MARKET_TAX_LAYER_CHARGED 逐笔逐层；ERROR: 契约守卫两处；INFO: MARKET_TAX_UNVALUED
```

## 3. 关键判断（为什么这样拆）

1. **两个注入字段（闸 / 税）而不是一个**：P-T1a 的 `PortEnforcementInput.isActive()` 是"有没有闸"的判据；把税并进去，"只设税率而两侧全开"的世界会让 `isActive()` 仍是 false ⇒ 税被静默丢（P-T1a 账本 §4-1 已把这条列为待裁定，本批按"缺省各管各的"落）。
2. **区级税率 = 两个分量各自加权平均**（不是把从量/从价合成一个数）：两者量纲不同（毫/单位 vs 千分比），合成必然要引入一个参考价，而 P-T1c 已删掉区级参考价 ⇒ 只能各算各的，在结算处相加。加权平均的分母 = **全区暴露边（含三不管那份）**：没有政府的那一侧交 0，于是"整区无政府 ⇒ 税率 0"是算式的自然结果，不是另一条特判（与"无政府 ⇒ 开放度 1000"同源）。
3. **折算次序只有一次**（V-2）：税基本来就在买方支付币里（不折）；从量税率经**同一份** `CurrencyValuation.valuationMicro(买方币, 法定币, 买方区)` 折**一次**（同币 ⇒ 面值 1000 ⇒ 逐值相同）；从价是无量纲千分比（不折）。**没有**"先把货款折成法定币再折回来"的第二次折算，也没有第二份估值实例（`ctx.currencyValuation()` 是全场唯一一份，见 `MarketSettlement:1207-1235` 的注释）。
4. **逐腿校验恒成立（钱不会被扣成负数）的证明**：设买方余额 B、本币种轴冻结 F、本单冻结 f（`payable = 可花 + f = (B−F) + f ≤ B`）。精确封顶保证 `total ≤ payable ≤ B`；释放 `min(total, f)` 后冻结 `F' = max(0, F − total)` ⇒ 任一条腿扣完的余额 `B − 已扣 ≥ B − total ≥ F'`（因冻结合恒有 `F ≤ B`）⇒ `applyTransfer` 的 `balance − amount ≥ frozen` 逐腿成立。
5. **自转移剔除**：买方（国库户自己买货）恰是某收税政府的国库时，那一份不进分摊（`Transfer` 两端不得相等，fail-closed 契约不放宽）——经济上也对：政府的钱从左口袋到右口袋不是发生额。
6. **说不出法定币的价 ⇒ 该层收 0 + INFO**（不猜 1:1、不拒绝成交）：设计书 §13.2-4 明写"税不影响能不能过"（那是闸的事）；猜一个汇率违反 E 批"说不出价就不成交"的同一条纪律。★ 可负担预判那条路（二分里会被问几十次）**只算不记** ⇒ 日志由真的落账那一处发一条。
7. **`ctx.taxItems` 与 worker 回放同一条纪律**：worker 也跑同一条 `executeTrade`（累加到本地 ctx），交回时丢弃；协调器回放时在全局 ctx 重铸 ⇒ 读数只可能来自协调器那一份（与 `fills`/`ledger` 一致）。
8. **税表区键 fail-closed**：沿用 P-T1a 的接缝守卫（`requirePortZoneKeysAligned`）新增 `requirePortTaxZoneKeysAligned`：税表非空却一个区键都命中不了本轮拓扑 ⇒ 具名 ERROR + 抛。为什么"至少命中一个"就够：税表区键来自 `MarketZoneBook.zones(economy)`，与本轮拓扑同源。

## 4. 偏离记录（与约束设计书不一致处及原因）

1. **区内市场税只在现金腿真收**；货币信用腿（`executeMoneyCredit:2666`）仍只记读数。原因：该腿借的本金**恰等于货款**，要连税一起借就得改借贷池的可借额口径（`moneyCreditForBuy` 的 `amount/quantity` 反解），超出本责任区的落点（任务书落点只点名 `executeTrade` 的货款腿）。⇒ 记 **未完成项**（§6）。
2. **币种维规则的税不进商品税表**：`policy.currencyRules()` 的 entryTax/exitTax 只计入 `taxedClasses` 读数。原因：币种手续费是 P-T5 的"挂单禁入/禁出 + 手续费"，税基不是货值 ⇒ 本批不替 P-T5 定口径（设计书 §14.3/§14.7）。
3. **区级税率的加权平均在组合根算，分摊在结算处算**：前者只需权重与政策（无价），后者需要成交价与量（ad valorem 的税基）⇒ 拆开是必然，不是"两处口径"（分摊用的权重与平均用的权重是同一份 `GovernmentShare.exposureWeight`）。
4. **`PortTaxInput` 在"有政府的有区世界"每天都注入**（哪怕全 0 税率）：区内税的税率在经济侧（`MarketRegulation`），组合根看不见 ⇒ 不注入就等于那一层永远收不到钱。代价：每天一次 `withPortTax` + 契约守卫（全 0 ⇒ 一个数不动、一行日志不发）。
5. **`PortRegimeBridge` 里另有两条具名 INFO**（`GOV_PORT_TAX_NO_GOVERNMENT_RECORD` / `GOV_PORT_TAX_TREASURY_NOT_HOUSEHOLD`）：前者=暴露边归属查无政府记录，后者=国库是 GOVERNMENT actor（收不了钱，账户主体只有家户）⇒ 排除该 government 的份额并可见，不静默按 `hh-gov-` 前缀造一个家户。
6. **M1 利润算式未接三档税**（`EnterpriseProfitBook` 按原因分类，新档不在任何分支）⇒ 税不进任何利润/成本读数。这是后续批次（M1）的活；本批只保证"钱真的进了国库"且不重复计。

## 5. 手工验证（**不是仓内测试**，测试文件一个都没写/没改）

| 装置（/tmp/pt1b，不落仓） | 验的是什么 | 结果 |
|---|---|---|
| `TaxCheck`（同包，直调 `MarketTaxBook.assess`） | ① 从量 10×1000=10；② 从价 100‰×1000=100；③ 两分量相加 110；④ 分摊 101 → 33/68（floor+余数）且 Σ=101；⑤ 无政府 ⇒ 0；⑥ 买方=国库 ⇒ 自转移剔除；⑦ 异币无报价 ⇒ unvalued 且不收；⑧ 1 gold=2 silver ⇒ 从量 10→20；⑨ 税率 0 ⇒ 空；⑩ 负税率具名拒；⑪ 表序保序冻结 | **ALL CHECKS PASSED（15/15）** |
| 变异体 A（把余数给最后一家删掉，改成各自 floor） | 分摊的判别力 | 当场红：`apportion 33/68 actual=33/67`、`conserves actual=100`（Σ≠101） |
| 变异体 B（从量税率跳过折算，直接用原额） | 折算的判别力 | 当场红：`converted per-unit 20 actual=10` |
| 编译门禁 | 全仓 16 模块 | `tools/mvn-lock.sh -q spotless:apply` rc=0；`rm -rf {economy-api,economy,app}/target/classes` + `tools/mvn-lock.sh -DskipTests compile` ⇒ **BUILD SUCCESS 16/16，8.472s**（真重编，不是增量空转） |

## 6. 没做 / 没验证（如实）

- **没跑** `test` / `verify` / `test-compile`（本批纪律）⇒ 既有测试既没编译核过、也没跑过（只有静态符号审计，见报告 ⑤）。
- **没有真实世界读数**：没有构造"多区 + 税率非 0"的世界跑一天看国库户余额增量与日志（判据 T2/T3 的实测留给测试 Agent / G3 复测）。
- **没验**：worker 分区 + 回放路径在**有税**时的 `frozenRemaining/spentMilli` 一致性（静态论证：`copyForWorker` 带过 `portTax` ⇒ 两侧同值；未实测）。
- **没验**：多政府在真实世界里的分摊落账（只在装置里逐值验过 33/68=101）。
- **没验**：`requirePortTaxZoneKeysAligned` 的触发路径（要先构造两套键）、`MARKET_PORT_TAX_CONTRACT` 守卫（要先构造丢字段）。
- **未收**：货币信用腿的区内税（§4-1）；币种维税（§4-2）。
- **未接**：M1 利润里的三层税（§4-6）。
