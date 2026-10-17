# D-032 D3 工资链与劳动匹配约束设计书（§17，2026-10-17）

> **性质：设计分册，不是实现派单书。** 本文只冻结“工资链与劳动匹配必须长成什么样”，**不实现、不编译、不跑测试、不提交、不修 R2、不声称工资链已实现**。W1–W4 的实现派单必须另写，W5 测试由独立测试 owner 负责。
>
> **对应重规划**：`docs/superpowers/plans/2026-10-17-d032-production-line-replan.md` §4 Phase D 的 **D3**、Phase W 的 **W1–W5**。D3 落盘前只允许做设计调查；不得以本文代替实现证据。
>
> **输入与效力（从高到低）**：
> 1. 用户设计原稿 D-032/U15–U21（2026-10-17，逐字见 §0.1）；D2 §1 已逐字收录，D3 与其对照一致；
> 2. D2 合作生产与分配约束设计书：`docs/superpowers/specs/2026-10-17-d032-cooperative-production-design.md`（重点 §0/§1/§5/§6/§8.4/§10.2/§11/§12/§13/§14）；
> 3. D1 共享生产定义形状冻结：`docs/superpowers/specs/2026-10-17-d032-production-definition-v2-freeze.md`（含 D2 更正注）；
> 4. 工资裁定与历史提案，只取未被取代部分：`2026-10-17-d032-wage-settlement-simplification-decision.md`、`2026-10-17-d032-employer-posted-wage-redesign.md`、`2026-10-17-d032-tick-wage-feedback-proposal.md`（§9/U20、§11/U21 为准）、`2026-10-17-d032-distribution-wage-offer-proposal.md`、`2026-10-17-d032-preselection-wage-feasibility-analysis.md`；
> 5. 当前代码事实（§1 全部回代码核过，行号为本文落盘时侧线工作树 `0128b0cc` 实测）。
>
> **边界**：D3 只冻结工资报价状态、U20 调价、劳动匹配/闲置、工资前置、直付/抵物/成债、tick 时序与验收判据。D4/Phase A 的资产地点/占用期/统一占用（R2）仍归 D4；生产定义、角色经验、产品份额归 D1/D2/production。**本文不修改 D1/D2/status/Java/XML/POM/测试，不触碰主树。**

## §0 口径溯源与状态

### 0.1 用户原话附录（逐字，2026-10-17）

> 来源：`docs/superpowers/specs/2026-10-02-architecture-design-source.md` D-032 节；与 D2 §1 逐字一致。下列均为用户实际口述日 2026-10-17；控制方解释不混入引文。

> **U15**：「不是，就是直接的产品分配需求——666，地主来收租子，你和地主说，你没下地干活，你不许拿，你看地主抽不抽你就完了；以及好像没有考虑现金结算问题，使用什么东西支付生产方式也要定义，例如佃农制就是直接分粮食，雇农制则是在农业生产方式的最底下加一档雇农，然后给作为生产组织者的地主加上随着雇农投入劳动力扩大而扩大的货币结算分成，例如雇农提供n劳动力给m当地市场区货币，没有对应货币再用地主库存里其他东西按顺序结，这个具体结算价格应该让地主自主报价、跟着市场区，问题是这里如何实现地主自主报价？思考调查一下」

> **U16**：「1 这个估算有算法吗？ 2 我不建议搞这么复杂，最好是算出来劳动价格之后直接给——是否选择进行雇农生产是家户自己的事，给钱是地主的事 3 因此也不用算这么复杂，工资库存给不起直接计成债务就行，有库存就给库存，不设置额外的土地生产资料，被用作再生产的生产资料可以视为权益了，不算做可变卖活动库存」

> **U17**：「地主支付这个环节应该算成地主的生产方式选择——家户在选择生产方式并成为地主之前，必须先根据对应生产方式，算在选用的时候，本轮能雇到人的愿给出劳动力价格，然后再把这个价格计入生产预估花费，算出这个家户选什么生产方式；因此口径应当改成不亏本的最低价——要不是能剥削谁雇雇农啊？这样的体系自洽吗？在经济景气、经济不景气的时候这样的机制分别会产生什么效果」

> **U18**：「最低工资在市场逻辑下为什么不能报0或者很低呢，流民/无产者家户有什么办法吗，它必须选一个生产方式，入不敷出是必然，地主是出价的那一方，重新设计一下」

> **U19**：「从0开始报价那完蛋了，但是我想不到其他好办法；这样，给家户同时加一个允许不从业的设定，如果本tick地主家户报价为0，那么无产者家户算了算，发现没收入还得投入劳动力，如果换不到第二tick的口粮，就算不想罢工也无法投入生产，必须要倒闭有能力选地主的家户加工资，这里就是一个动态的市场计算博弈，博弈随着tick增加而进行，有办法自恰吗？」

> **U20**：「对，可以给一个在招不到人的时候非常快乃至累加的加价速度，招到人之后缓慢增大的降价速度，还有其他需要裁决的问题吗？」

> **U21**：「1 不影响 2 不允许，地主优先给钱，没钱了再按库存最多的东西抵」

**控制方说明（非用户原话，不能冒充裁定）**：U15 中“货币结算分成”按 D2 已落盘更正理解为**雇主的工资支出义务**，不是地主收入；U16/U21 的支付顺序按原文“先对应货币 → 可支付活动库存按数量从多到少 → 剩余成债”；U18/U19 允许零/负收益工作成为候选、允许不从业，但不得虚构免费闲置或维生保障。

### 0.2 裁定状态表

#### A. 用户已定（不得由 D3 改写）

| # | 口径 | 出处 | D3 处置 |
|---|---|---|---|
| U15 | 工资以**当地市场区货币**计；生产方式必须定义支付物（产品份额/工资形态） | U15（2026-10-17） | 引用；`quote.currency == unit 所在市场区 legalTender` |
| U15+更正 | **工资是地主/雇主的支出义务**，不是地主收入；地主产品份额不以劳动为条件 | U15 + D2 §1/重规划 §1.1 已落盘更正 | 作为 D3-I 与 W1 的硬前提 |
| U16 | 算定劳动价格后**直接计薪/支付**；是否从事雇农生产由家户选择 | U16 | W1/W3 直接结算，不建多轮议价 |
| U16 | 工资库存不足直接**成债**；有库存就给库存 | U16 | W3 货币→库存→债务 |
| U16 | **再生产权益/已投入资料不算可变卖活动库存**，不抵工资 | U16、U21 | W3 可支付库存排除规则 |
| U17 | 工资费用**前置进入雇主选业成本**；先算本轮可招劳动价格，再计预估花费 | U17 | W1 修改 `Prospect` 成本 |
| U18 | 地主是**出价方**，可报 0/很低；不得隐含正工资/不亏本底线 | U18 | posting 允许 `wagePerLaborMilli=0` |
| U19 | 家户**允许不从业**；闲置是明确候选；报价/选择/支付按 tick 推进 | U19 | `allowIdle` + idle 候选 |
| U20 | 缺工时**加价快且可累加加速**；招到人后**降价缓慢、幅度渐增** | U20 | §3 U20 公式（参数为工程默认） |
| U21 | 欠薪记录**不影响**下一 tick 择业；**禁止预付** | U21 | W2/W3 禁止把历史兑付率/黑名单/预付作为择业输入 |
| U21 | 付款顺序：**对应货币 → 可支付活动库存按数量从多到少 → 剩余成债** | U21 | §6 逐字实现 |
| U21 | 库存最多按**数量**，不改成总价值最高/口粮优先/自行配置 | U21 + `tick-wage-feedback-proposal.md` §11.3 | 稳定排序：初始可用数量降序、同量 `CommodityId` 升序 |
| U16/U21 | 再生产权益/已投入资料不进入支付候选 | U16、U21 | 与 D4 占用读口配合；D3 不修 R2 |

#### B. 控制方工程默认（明确标注，用户可随时推翻；不是用户裁定）

| # | 工程默认 | 理由/边界 |
|---|---|---|
| E1 | **逐 tick 结算**：每个生产日按本日实际劳动计 due 并支付/抵物/成债 | U19/U20 的逐 tick 演进；不是把整周期工资留到关账日 |
| E2 | 报价是**持久状态**，按 `(ProductionUnitId, ProductionRoleId)` 存 `ProductionWagePosting` | U19 的反馈需要跨 tick 记忆；放 `EconomyData` 第 33 个组件 |
| E3 | U20 调价公式、动量上限、最小步长、降价延迟/加速均为**可配置参数**；§3.6 给建议值 | U20 只定方向，未指定初值/系数；建议值待仿真标定 |
| E4 | 抵物估值用当地 `MarketTable.bidPriceOf`（卖方限价），不是 ask、不是 1:1、不是任意价 | U21 只说“按库存最多”；估值口径是工程默认 |
| E5 | 工资债 `DebtTerms.wageArrearsDefault()`：**0‰ 利率** + 现有 `AFTER_REPAYMENT_ON_CLOSE`/`AVAILABLE_SURPLUS_SHARE`/`NOT_ALLOWED`/`MARK_DEFAULTED`，期限空 | 不得套 legacy 20‰；用户未指定利率，取 0 为工程默认 |
| E6 | 同一付款人（organizer household）跨 unit/role/worker **共享一份货币+库存预算** | 防止同一笔余额/库存被两条义务各付一次 |
| E7 | `allowIdle` 缺行默认 **true**（新世界/seed 未显式配置时允许不从业） | U19 要求闲置是明确候选；默认值可在 seed/命令覆盖，属工程默认 |
| E8 | idle 排序：收益率同为 0/负时 idle 优先；activity id 用稳定前缀 `idle:` < `unit:`，不靠 Map 序 | §4.4；若某工作有额外可计价收益，其 yield 必须 >0 才会排在 idle 前 |
| E9 | 自动招募（MONEY_WAGE 角色）首期**只限本地市场区**（同 `MarketZoneId`） | W2 最小可行；跨区走既有迁移机制，不在 W2 自动招募 |
| E10 | 角色目标/unit cap **两级封顶**：先角色 target，再 unit 总劳动 cap；多角色按稳定角色 id 序消耗共享 cap | §4.6；单 wage 角色 unit 时退化为 `min(target, maxAbsorbableNonLabor)` |
| E11 | 旧 `FIXED_MONEY_WAGE` 只允许在 C3 seed 中**显式换算**成 quote（`quoteSource=GENERATED`）；运行时不得回读固定额 | U19 不得静默从 0 开始；固定额只能是一次性显式种子转换 |
| E12 | 同一天新生成的工资债，`repayDebts` **同日不再偿还**（day-local 排除集） | 防止“先成债、同 tick 又拿同一笔余额付一次”；见 §6.5 |
| E13 | 多义务付款的稳定序 = `(unit, role, household)` 的规范 id 升序 | 保证确定性与可复现；不沿用 Map 插入序 |

#### C. 开放项（不得替用户裁定）

| # | 开放项 | 当前处理 |
|---|---|---|
| O1 | U20 参数的具体数值、加价/降价是否收敛、是否盈利 | 只给工程建议值；不作收敛/盈利承诺；待仿真标定 |
| O2 | `allowIdle` 的默认值是否应为 true/false | D3 工程默认 true；用户可推翻，改成 false 不返工契约 |
| O3 | 是否把消费/口粮结算移到工资结算之后，以支持同 tick 吃饱 | D3 只写清现状：当前消费在 `executeDay` 之前；工资在之后，不声称同 tick 已吃饱 |
| O4 | 跨市场区自动招募、工资竞价、劳动迁移 | W2 不做；归既有迁移/后续分册 |
| O5 | 旧 `FIXED_MONEY_WAGE` 非名册路径是否删除、何时删除 | D3 只管名册方案；旧路径仍具名存在，不冒充已修 |
| O6 | 抵物按 bid 还是其他估值、是否允许地主自定义抵物折价 | D3 选 bid 为工程默认；用户可改 |
| O7 | 多角色共享 unit cap 的优先序（角色 id 升序）是否公平 | D3 选稳定 id 序作最小可行；不是用户裁定 |
| O8 | 工资债的到期周期、违约处置、是否计息 | D3 取 0 利率、`dueCycle=当前周期+1` 建议；用户可改 |

### 0.3 D3 对 D2 的最小修正

D2 已冻结合作生产/名册/产品份额；D3 发现以下**必须补的最小形状**。**不直接改 D2 文件**，由后续 D1/D2 文件 owner 在 C0/W 批次同步落盘；D3 以其为准。

1. **`ProductionReceipt` 必须能给出“本次 tick 逐 `(household, role)` 实际劳动”。**
   - D2 §6.1 已把 `cycleContributions` 改为 `Map<ProductionParticipantKey, CycleContribution>`，但 `laborContributionsMilli` 仍允许是按户合计。D3 的 due 必须按本次 tick 逐角色实际劳动计算，**不能用本周期累计、不能用计划劳动、不能靠 delta 猜**。
   - 最小修正：`ProductionReceipt.laborContributionsMilli` 改为 `Map<ProductionParticipantKey, Long>`（本次 tick；只含 >0 项）；按户合计只作为派生方法 `laborByHouseholdMilli()`。`ProductionProcess.cycleContributions` 继续按 D2 的角色累计，二者同源、不互相替代。
2. **MONEY_WAGE 角色不得被 D2 的“每角色至少一个 participant”绑死。**
   - D2 §5.2/§7.1/§10.1 要求每个定义角色至少一个 participant、名册固定；但 W2 的开放匹配需要“岗位需求”而非伪造 worker。
   - 最小修正：`PRODUCT_SHARE` 角色仍必须有 roster participant；`MONEY_WAGE` 角色的需求权威改为 `ProductionWagePosting.targetLaborMilliPerDay`。`ProductionRoster` 可以没有 MONEY_WAGE participant；实际 worker 每 tick 由 allocations/receipt 决定。**不得伪造 placeholder household，不得建第二套“名册 worker”权威。**
3. **`candidateHouseholdsOf` 的候选面要按角色类型分流。**
   - D2 §4.3 说它必须读 `ProductionRoster`；这只适用于 `PRODUCT_SHARE` 角色。
   - 最小修正：`MONEY_WAGE` 角色的候选集由 posting + 本地市场区 + 资格/账户/劳动力规则产生，不读 roster；`laborSources` 仍只是已确认 roster 的派生摘要，**不得当作工资工人的全集**。
4. **D2 §8.2 的工资应收公式对 MONEY_WAGE 角色要改用 posting 目标。**
   - `plannedLaborMilliPerDay × cycleDays` 不能作为工资岗位的需求；工资预计应收/应付使用 `posting.targetLaborMilliPerDay` 与 `maxAbsorbableNonLaborForRole` 的预计值，实际仍以 receipt 为准。
5. **D2 §7.3 的“角色资格失败 ⇒ 整个实例本日拒绝”只适用于 roster 确认的 participant。**
   - 对 W2 的 MONEY_WAGE 自动候选，单个家户不合格是**候选 skip**（具名），不是整 unit 故障；无候选只形成 shortage，并驱动 U20 观察。roster 内 PRODUCT_SHARE participant 的资格失败仍按 D2 整实例拒绝。

> **结论**：D3 对 D2 的修正都是“名册 vs 开放工资岗位”的边界补丁，不改变 D2 的产品份额、经验、资格、组织者主体规则。

### 0.4 冲突检查

本次未发现 D3 设计与用户原话/五条铁律/模块边界直接冲突。若实现方发现冲突，必须按派单纪律**立即上报控制方**，由控制方转问用户；不得自行改写用户口径或把工程默认冒充裁定。

## §1 现状事实（回代码核过）

> 行号为侧线工作树 `0128b0cc` 的当前值；实现派单前应重核。结论只描述“现在是什么”，不是“应该是什么”。

| # | 位置（`file:line`） | 已核事实 | 对 D3 的影响 |
|---|---|---|---|
| 1 | `simos-economy/src/main/java/io/mosire/simos/economy/time/LaborQueueBook.java:107` | `MIN_NET_PER_LABOR_SCALED = 1L`；注释明确“预期 ≤ 0 不参与利润队列” | 与 U18/U19“零/负收益也是候选”冲突；W2 删除该预过滤 |
| 2 | 同上 `:120-150` | `Offer` 是 unit 级读数；`queueable()` = `maxAbsorbableLaborMilli > 0 && netPerLaborScaled >= MIN_NET_PER_LABOR_SCALED` | W2 要改成角色级 offer，并去掉经济阈值 |
| 3 | 同上 `:479-515` | `plan(household, budget, preserved, offers)` 逐 offer 排序后填预算；`IDLE_MAX_ABSORBABLE_ZERO` / `IDLE_EXPECTED_NON_POSITIVE` / `IDLE_BUDGET_EXHAUSTED` 三分支 | `IDLE_EXPECTED_NON_POSITIVE` 必须被显式 idle 候选替代；不能继续暗判“非正收益=空闲” |
| 4 | 同上 `:745-756` | `isPreservedByQueue` 只保留 `LABOR_FREE`/`NO_RECIPE_OUTPUT`/`outputPriced=false` | 它可作为“无价不预过滤”的既有例外保留；但不得再承担经济盈利门槛 |
| 5 | `simos-economy/src/main/java/io/mosire/simos/economy/time/ActivitySelector.java:36-70` | `PER_LABOR_SCALE=1_000_000`；`RankKey(yieldScaled, activityId, tieId)`；`compareKeys` 用 `Long.compare` 支持带符号 yield | **排序器本身支持负/零收益**；真正拦截在 `LaborQueueBook` 的过滤，不在比较器 |
| 6 | 同上 `:78-99` | `rank` 稳定排序，三级键全定；调用方必须按稳定 id 构建 list | W2 必须复用同一排序器，不得另写第二套角色排序 |
| 7 | `simos-economy/src/main/java/io/mosire/simos/economy/time/ExpectedProfitBook.java:619-721` | `wageProspect` 从旧 `CompensationRule` 读工资；`:672` 对 `FIXED_MONEY_WAGE` 直接加 `rule.fixedAmount()`；`:700` 在 `income == 0` 时记 `NO_WAGE_RULE` | 现状把“缺工资规则”和“明确 0 工资”混在一起；W1 必须按 quote 重写，并区分 missing/zero |
| 8 | 同上 `:402-466`、`:568-580` | `productionProspect` 用 `probe` 求物理规模；`:568-580` 调 `obligationsOf(..., includeWages=false)`；`laborCostMilli` 只含生存折算，工资未入雇主候选成本 | W1 必须把全部应付工资加入雇主成本；不能因现金不足消失 |
| 9 | 同上 `:1079-1210` | `obligationsOf` 的 `FIXED_MONEY_WAGE` 仍按 `rule.fixedAmount()` 计；`FIXED_MONEY_RENT` 同样固定额；分成按池估算 | W1 对名册方案改读 posting quote；不得回读旧固定额 |
| 10 | `simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionSettlement.java:389-432` | `settleMoneyRule`：`due = moneyDue(rule)`、`available = availableMoney − paidMoney`、`paidNow = min(due, available)`；`paidNow == 0` 不铸转移；欠额**只进读数** | 现状工资是每周期固定额、无库存抵付、欠款不落债；W3 必须替换为逐 tick 按实际劳动 due |
| 11 | 同上 `:504-530` | `availableOf` 只有 `FIXED_IN_KIND_RENT` 会读到家户库存；其余实物档只看本 unit 产出 | W3 的工资库存抵付是**新链**，不能复用这条租腿的“三级链”冒充已完成 |
| 12 | `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java:7125-7215` | `capitalizeArrears` 读 `ProductionLedger.Arrear`，在 `:7205` 固定用 `DebtTerms.legacyDefault()`（20‰/周期） | 工资债不得走这条 20‰ 资本化；W3 用 `wageArrearsDefault()` 0‰ 直接 upsert，且不得二次资本化 |
| 13 | 同上 `:2735-2755` | `repayDebts` 只在 `anyCycleClosed` 时运行；`repayDebts` 入口 `:7328` | 新工资债若同日落入还款轮会被再付一次；需 day-local 排除（§6.5） |
| 14 | 同上 `:7417-7421`、`:7610-7626` | 还款介质序 = `DebtValuation.estimatedUnitValueMilli` 升序（估值最低优先），同值 canonical 升序 | 与 U21 的工资付款顺序不同；**不得复用还款介质序作为工资顺序** |
| 15 | 同上 `:8623-8715` | `harvest` 仍在周期关账时调 `ProductionSettlement.settle`（`:8697`），工资/地租走旧 relation 腿 | 名册方案必须由 W3 新链替代；旧路径只服务非名册/旧规则，不能双记 |
| 16 | `simos-economy/src/main/java/io/mosire/simos/economy/time/AccountSession.java:28-43` | 唯一账户表；主体=家户；余额/冻结四张活表；唯一写口是 `commit(Collection)` | W3 只能经 `AccountSession`/`applyTransfer` 落账，不得直接改 Map |
| 17 | 同上 `:266-282` | `commit` 排序后 `TransferIntent → EconomySettlement.applyTransfer`，`AccountDelta`/`FreezeIntent` 校验 | 工资转移必须走这条；工资债不移动库存，只 upsert |
| 18 | `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java:9177-9235` | `applyTransfer` 是唯一转移写口；两遍式（先校验后落账）；付方余额/冻结不足当场抛 | W3 的货币/商品腿必须走它；不得另写 setStock |
| 19 | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/market/MarketZone.java:95,131` | `legalTender` 是市场区的唯一货币事实，不得为 null | quote 币种必须等于 unit 所在地 `legalTender` |
| 20 | `simos-economy/src/main/java/io/mosire/simos/economy/model/MarketTable.java:181,269,281` | `hasPrice` 区分“未定价”和“明确 0 价”；`bidPriceOf`/`askPriceOf` 未定价或 0 价都返回 0 | 抵物必须先用 `hasPrice` + `bidPriceOf`；缺价/0 价 skip，不得 1:1 |
| 21 | `simos-production/src/main/java/io/mosire/simos/production/contract/ProductionReceipt.java:51-67` | `laborContributionsMilli` 是 `Map<HouseholdId, Long>`（本次按户）；`cycleContributions` 是 `Map<HouseholdId, CycleContribution>`（本周期按户） | D2 已改 cycle 为 participant；D3 还要求本次 tick 逐角色，故 §0.3 修正 1 |
| 22 | `simos-production/src/main/java/io/mosire/simos/production/contract/ProductionProcess.java:75-82,178-203` | 实例的 `cycleContributions` 按户；`cycleLaborByHousehold()` 是派生合计 | D2 改 participant 后，W3 读 receipt 的 tick 明细，不读实例累计 |
| 23 | `simos-production/src/main/java/io/mosire/simos/production/contract/CycleContribution.java:50-51` | `CycleContribution(household, laborMilli, efficiencyWeightedLaborMilli)` 无 role | D2 加 role；D3 仍要求另有本次 tick 的 participant 明细 |
| 24 | `simos-production/src/main/java/io/mosire/simos/production/contract/ProductionDayRequest.java:68-75`、`ProductionExecutionRequest.java:44-51` | 执行请求/日步的 `laborContributionsMilli` 是 `Map<HouseholdId, Long>` | D2 改为 `List<ParticipantContribution>`；W1/W2 的匹配结果必须能落到角色分配 |
| 25 | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/labor/HouseholdLaborCommitment.java:52-60,149-162` | `activity` 是 unit id；id 为 `alloc-<unit>-<group>-<household>`，无 role | D2 加 `productionRole` 并改 id；D3 的逐角色分配依赖它 |
| 26 | `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:310-397` | 当前 **31 个 record 组件**（逐行计数；历史注释里的 40 是旧编号） | D2 加第 32 `productionRosters`；D3 加第 33/34/35（§2.6） |
| 27 | 同上 `:818-890` | `allocations` 构造期检查：键=id、家户存在、`Σ PRODUCTION ≤ max(0, 时间预算 − min(Σ GOV_SERVICE, 预算))` | W2 写回 allocations 后仍受此不变量；同一户多角色总劳动不得超预算 |
| 28 | `simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java:652` | 日循环 `for (day = from+1; day <= to; day++)` | W4 的插入点必须在这条循环内 |
| 29 | 同上 `:664-668` | ① Social 投影：`updateComposition`、`recomputeLaborBudgets`、`updateNaturalNeeds` | 工资匹配前的劳动预算来自 Social 投影 |
| 30 | 同上 `:681` | ② `productionRuntime.beginTick(day)`，每 tick 恰一次，先衰减经验再冻结本 tick 条件 | W2 的 posting 快照/U20 观察必须与它同一 tick 身份 |
| 31 | 同上 `:687` | ② `stepper.step(day)` 返回 `ProductionLedger` | W3 的工资结算应在 `stepper.step` 内部、`driveProductionDay` 之后 |
| 32 | 同上 `:692-738` | ③ 缺口→`FamineMortalityBridge`；④ 断顿→饱食度回写 | 工资到账若在消费之后，只能影响下一 tick 的饱食/劳动能力；不得宣称同 tick 吃饱 |
| 33 | 同上 `:790-795` | ⑤ `HouseholdBook.settleOneTick`（含饥荒死亡）；⑥ 回写人口 delta + 新 Social 投影 | W4 的 Social 反馈必须接在这条既有链上，不另建第二套 |
| 34 | `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java:1602-1613` | **当前消费（`consumeOwnStockPartitioned`）在生产 `driveProductionDay` 之前** | 这是 D3 必须如实写的时序事实：工资在 executeDay 后结算，无法改善同 tick 已发生的消费 |
| 35 | 同上 `:1676-1677`、`:4222-4284` | `driveProductionDay` → `runtime.executeDay`，回执含本次/周期贡献 | W3 的 due 读 receipt 的本次 tick participant 明细 |
| 36 | 同上 `:1981-1995`、`:2050-2070` | 关账/欠款资本化之后进入市场轮 | W3 工资支付和同日排除必须在市场/还债之前接好 |
| 37 | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/debt/DebtTerms.java:49,78-91` | `LEGACY_INTEREST_RATE_PER_MILLE_PER_CYCLE=20`；`legacyDefault()` 固定 20‰ | 工资债不得复用；新增 `wageArrearsDefault()` 0‰ |
| 38 | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/transfer/TransferReason.java:28-60` | 现有原因含 `RELATION_PAYMENT` 等，没有工资专用档 | W3 新增 `WAGE_PAYMENT`；读账必须能与关系付款分开 |
| 39 | `simos-economy/src/main/java/io/mosire/simos/economy/time/DebtContractBook.java:101-150` | `upsert` 按 `(debtor, creditor, DebtUnit, DebtTerms)` 派生 id；同 id 累加本金 | 同币种工资债可合并；不同 terms/币种不合并 |
| 40 | `simos-economy/src/main/java/io/mosire/simos/economy/model/MarketZoneBook.java:88-110` | `zoneOfHex` 可从家户/单位所在格解析市场区 | W2/W3 用同一入口解析 `legalTender` 与本地价表 |
| 41 | `simos-economy/src/test/java/io/mosire/simos/economy/change/EconomyRoundTripTest.java:549` | 当前断言 `EconomyChangeSet` 有 31 个 record 组件 | D2 加 32、D3 加 33/34/35 后由 **W5 独立测试 owner** 更新，不由实现 owner 顺手改 |

### 1.1 现状结论（逐条）

1. **工资不是“按劳动”的**：`ExpectedProfitBook.wageProspect` 从旧规则取 `fixedAmount`，`ProductionSettlement.settleMoneyRule` 的 due 也是 `fixedAmount`；没有 quote、没有逐 tick 实际劳动、没有库存抵付、欠款不落债。
2. **非正收益被硬过滤**：`LaborQueueBook` 的 `MIN_NET_PER_LABOR_SCALED=1` 与 `queueable()`/`plan()` 分支把零/负收益直接判空闲；`ActivitySelector` 本身支持带符号 yield，真正拦截在过滤逻辑。
3. **选择器已经是唯一排序器**：`ActivitySelector.compareKeys` 的三级键与 `rank` 稳定排序可直接复用；W2 不得另写角色排序。
4. **账户/转移写口已经收口**：`AccountSession.commit` 与 `EconomySettlement.applyTransfer` 是唯一写口；W3 只能经它们落账。
5. **工资债资本化参数错误**：`capitalizeArrears` 固定 `DebtTerms.legacyDefault()` 20‰，不能用于工资债。
6. **harvest 仍是旧 relation 工资腿**：`harvest` 调 `ProductionSettlement.settle`；名册方案若继续走它会与 D2 角色分配双记。
7. **生产回执缺少本次 tick 逐角色劳动**：现状 `laborContributionsMilli` 按户、`CycleContribution` 无 role；D2 的 cycle 角色化还不够，D3 必须补本次 tick participant 明细（§0.3 修正 1）。
8. **日循环的消费先于生产**：`consumeOwnStockPartitioned` 在 `driveProductionDay` 之前；工资在 `executeDay` 后结算，只能为下一 tick 的口粮/消费提供资源，或在本 tick 后续市场轮中买到东西但**不能在已完成的消费里被吃掉**。

## §2 状态与契约

### 2.1 量纲与名词（沿用 D2）

| 名称 | 单位/含义 |
|---|---|
| `laborMilli` | 毫小时/日（千分之一小时/日）；D2 已统一，旧“千分劳动·日”注释作废 |
| `targetLaborMilliPerDay` | 毫小时/日；工资岗位的日目标劳动 |
| `wagePerLaborMilli` | 毫货币 / 1000 毫小时 = 毫货币/小时；工资 = `wagePerLaborMilli × laborMilli / 1000` |
| `dueMilli` | 毫货币；本次 tick 某 `(unit, role, worker)` 的应付 |
| `carry` | 0..999 的分子余数；按 `(unit, role, worker)` 持久 |
| `maxAbsorbableNonLabor` | 非劳动约束（产能/资产/投入）允许该 unit 吸收的最大劳动，毫小时/日 |
| `effectiveTarget` | `min(targetLaborMilliPerDay, maxAbsorbableNonLaborForRole)`，毫小时/日 |
| `matchedLabor` | 本 tick 实际匹配到该 `(unit, role)` 的劳动，毫小时/日 |

### 2.2 `ProductionWagePosting`（持久报价状态）

新增值类型（建议放 `simos-economy/.../model/ProductionWagePosting.java`）：

```java
record WagePostingKey(ProductionUnitId unit, ProductionRoleId role) {}

record ProductionWagePosting(
    ProductionUnitId unit,
    ProductionRoleId role,
    long targetLaborMilliPerDay,
    CurrencyId currency,
    long wagePerLaborMilli,
    WageQuoteSource quoteSource,
    long shortageStreak,
    long satisfiedStreak,
    long lastAdjustedTick,
    long lastAdjustmentDelta,
    long lastObservedMatchedLabor,
    long lastObservedEffectiveTarget,
    long lastMaxAbsorbable) {}

enum WageQuoteSource { SEED, EXPLICIT, GENERATED }
```

字段语义：

- `unit`/`role`：稳定身份；键 == 值内 `(unit, role)`；同一 `(unit, role)` 至多一条 posting。
- `targetLaborMilliPerDay ≥ 0`：地主本 tick 希望吸收的日劳动；`0` 合法且不调价。
- `currency`：必须等于 unit 所在市场区 `legalTender`；不做跨币种换算。
- `wagePerLaborMilli ≥ 0`：**0 是合法报价**（U18），不是缺报价；缺报价用 `Optional.empty()`。
- `quoteSource`：`SEED`（`economy.Seed.wagePostings`）、`EXPLICIT`（`economy.SetProductionWagePosting`）、`GENERATED`（仅 C3 从旧 `FIXED_MONEY_WAGE` 显式换算或后续明确标注的自动生成，不得是运行时静默兜底）。
- 所有权：posting/carry/allowIdle 状态归 economy；production 不持久化、不读写这些表，只从执行请求拿 allocations/receipt。
- `shortageStreak`/`satisfiedStreak ≥ 0`：连续缺工/连续满足的 tick 计数；见 §3。
- `lastAdjustedTick ≥ 0`：最近一次实际改价的 world day；无改价可为 0。
- `lastAdjustmentDelta`：最近一次改价的带符号差（毫货币/小时；负=降价）；无改价 0。
- `lastObservedMatchedLabor`/`lastObservedEffectiveTarget`/`lastMaxAbsorbable`：上一 tick 末的观测值；只用于读数和下一次调价，不参与当期 due。
- 构造期校验：`targetLaborMilliPerDay ≥ 0`、`wagePerLaborMilli ≥ 0`、streak/观测值 ≥ 0、`currency != null`、`quoteSource != null`；不做跨切片 legalTender 校验（由 `EconomyReferenceValidation`/命令层做）。

### 2.3 `ProductionWageQuoteLookup`

沿用 D2 §6.5 的最小读口：

```java
record ProductionWageQuote(CurrencyId currency, long wagePerLaborMilli) {}

interface ProductionWageQuoteLookup {
  Optional<ProductionWageQuote> quoteFor(ProductionUnitId unit, ProductionRoleId role);
}
```

硬语义：

- **缺报价 = `Optional.empty()`**；不得当 0，不得用 `Map.getOrDefault(..., 0)`。
- **显式 0 = `Optional.of(new ProductionWageQuote(currency, 0))`**；`wage-quote-zero` 是合法读数标记，不是失败。
- C2 默认实现永远 `empty()`；W2 的真实实现从 `productionWagePostings` 读取；W1 只通过该读口取值，不直接读 Map。
- 读口必须返回**本 tick 锁定的 quote 快照**；U20 调价只写下一 tick 的 posting，不改当前快照。

### 2.4 逐户工资进位余数

新增持久状态（第 34 组件）：

```java
record WageCarryKey(
    ProductionUnitId unit, ProductionRoleId role, HouseholdId worker) {}
```

值域 `0..999`（毫货币分子余数）。语义：

```text
num   = quote.wagePerLaborMilli × tickLaborMilli + carry
due   = num / 1000
carry = num % 1000
```

- `tickLaborMilli` 是本次 tick 该 `(unit, role, worker)` 的**实际劳动**（毫小时），不是本周期累计。
- 必须用精确乘加（`BigInteger` 或 `Math.multiplyExact` + carry 检查）；溢出 ⇒ `wage-quote-overflow`，fail-closed，不得静默截断。
- 同一 worker 在不同 `(unit, role)` 的 carry 分开；不同 worker 不共享 carry。
- carry 只在 due 计算时读写；支付/抵物/成债不改变 carry。
- 例：quote=1、每 tick 劳动 500mh ⇒ tick1 `num=500`、due=0、carry=500；tick2 `num=1000`、due=1、carry=0。两天共付 1 毫货币，不因逐 tick floor 永久丢 1 毫。

### 2.5 家户 `allowIdle`

新增持久状态（第 35 组件）：

```java
record HouseholdLaborChoice(HouseholdId household, boolean allowIdle) {}
```

- 键 == 值内 `household`；每户至多一行。
- **缺行默认 `allowIdle = true`**（工程默认 E7；用户可推翻）。`allowIdle=false` 表示家户不把 idle 作为候选，零/负收益工作仍可被选择（U18）。
- 命令：`economy.SetHouseholdLaborChoice`，载荷 `{ "household": "hh-...", "allowIdle": true|false }`；**整行替换**，不做 patch。
- seed：`economy.Seed.householdLaborChoices: [ {household, allowIdle}, ... ]`；未给的户走缺行默认。
- ChangeSet/codec：`FieldDelta<HouseholdLaborChoice> householdLaborChoices`；JSON 逐字段往返；未知键/重复键按本仓 Alpha 口径 fail-closed。
- 读口：`EconomyDayView`/`EconomyBoundaryView` 暴露 `Map<HouseholdId, HouseholdLaborChoice> householdLaborChoices()`；app/GM 只读。
- `allowIdle` 只影响**本户**的候选集；不改变其他户、不改变 unit cap、不生成免费食物/收入。

### 2.6 `EconomyData` / ChangeSet / Codec / Snapshot / 读口 / 命令 / Seed

在 D2 第 32 个组件 `productionRosters` 之后新增：

| 组件号 | 名称 | 类型 | 说明 |
|---|---|---|---|
| 33 | `productionWagePostings` | `Map<WagePostingKey, ProductionWagePosting>` | 工资岗位持久状态；空表合法（没有 posting） |
| 34 | `wageCarryRemainders` | `Map<WageCarryKey, Long>` | 逐户进位余数；值 0..999；空表=全 0 |
| 35 | `householdLaborChoices` | `Map<HouseholdId, HouseholdLaborChoice>` | `allowIdle`；空表=缺行默认 |

必须同步的面（W2 的实现 owner 按此清单落形，W5 测试 owner 另测）：

1. `EconomyData` 新增三个 record 组件；构造期检查键==值内 id、carry ∈ [0,999]、非 null。
2. `EconomyChangeSet` 新增三个 `FieldDelta`；`between`/`apply`/`applyTo`/`isEmpty` 全接。
3. `EconomyCodec` 读写三组件；同一版本写出的 JSON 必须逐字段往返；缺键/未知键按 Alpha 口径 fail-closed。
4. `EconomySnapshot`/`EconomySnapshots` 自动携带 `EconomyData`；若实现有显式字段复制清单，必须补三组件。
5. `EconomyDayView` 增加只读访问器（`productionWagePostings()`、`wageCarryRemainders()`、`householdLaborChoices()`）；`EconomyData` 直接实现，`WorkingDayView` 工作副本优先。
6. `EconomyBoundaryView` 增加只读访问器，供 app/GM/readout 使用。
7. `EconomyReferenceValidation` 增加 posting/roster/unit/role/currency/legalTender 跨片校验。
8. 命令：`economy.SetProductionWagePosting`、`economy.SetHouseholdLaborChoice`；seed：`economy.Seed.wagePostings`、`economy.Seed.householdLaborChoices`。
9. `ProductionWageQuoteLookup` 的真实实现从 posting 表读；不得另建第二份 quote 表。
10. `EconomyRoundTripTest` 的组件计数/JSON 用例由 **W5 独立测试 owner** 更新；实现 owner 不改断言。

### 2.6.1 `economy.SetProductionRoster` / `economy.Seed.productionRosters` 与 D3 的衔接

D3 不改 D2 的名册载荷形状，只把工资岗位的边界接上：

- `economy.SetProductionRoster` 仍是**整表替换**（D2 §5.4）：`participants: []` 清空该 unit 名册；不做 patch；`plan` 由 production 实例读出，不由载荷给。
- `roster-locked` 语义不变：production 实例 `progressDays > 0` 时不得替换名册；名册周期内不变，保证产品份额/贡献可复算。
- **工资角色例外**：`MONEY_WAGE` 角色可以没有 roster participant；其需求由 `ProductionWagePosting.targetLaborMilliPerDay` 表达，实际 worker 由每 tick allocations/receipt 决定。`SetProductionRoster` 不得为工资角色伪造 placeholder household，也不得把工资工人写进第二份“名册 worker”权威。
- `economy.Seed.productionRosters` 仍只写已确认 participant；工资 posting 由 `economy.Seed.wagePostings` 另表给（§2.8），两者同批写入。
- 名册的 `plannedLaborMilliPerDay` 对 `PRODUCT_SHARE` 角色仍是计划劳动；对 `MONEY_WAGE` 角色不代替 posting target。目标/名册改动都只影响后续 tick，不追溯已发生工资或已分配产品份额。
- 若实现同时收到 roster 的工资 participant 与 posting，**不得**据此发两份工资/两份 cap：posting 是需求权威，allocations/receipt 是实际劳动权威；roster 的工资 participant 只能作为显式确认/历史读数，不得创造第二个工人集合。

### 2.7 `economy.SetProductionWagePosting` 载荷与语义

```json
{
  "unit": "unit-farm@0_0-hh-1",
  "postings": [
    {
      "role": "hired-hand",
      "targetLaborMilliPerDay": 5000,
      "currency": "silver",
      "wagePerLaborMilli": 1000,
      "quoteSource": "explicit"
    }
  ]
}
```

语义：

- **整表替换**该 unit 的 posting 集合；`postings: []` = 清空该 unit 的工资岗位。不做 patch。
- `unit` 必须存在；`role` 必须存在于 plan 的 `roles` 且 `payment == MONEY_WAGE`。
- `targetLaborMilliPerDay ≥ 0`；`wagePerLaborMilli ≥ 0`；`currency` 若给出必须等于 unit 所在市场区 `legalTender`；若省略，则从 legalTender 派生（但 `wagePerLaborMilli` 仍必须显式）。
- **初始 quote 必须显式**：每个 posting 必须给 `wagePerLaborMilli`（或 seed 的 `initialWagePerLaborMilli`）；缺 ⇒ `Rejected("wage-quote-missing")`，零变更。允许显式 0，但不得静默从 0 开始。
- **目标改动只影响后续 tick**：命令写入的是下一 tick 起生效的 `targetLaborMilliPerDay` 和 `wagePerLaborMilli`；已完成的 tick 的 due 不追溯。
- **锁定期语义**：
  - `SetProductionWagePosting` 不接收 `shortageStreak`/`satisfiedStreak`/`lastAdjustedTick`/`lastObserved*` 等历史字段；若载荷试图写这些字段 ⇒ `Rejected("wage-posting-locked")`。
  - 若显式改 `wagePerLaborMilli`：清空两侧 streak（`shortageStreak=0`、`satisfiedStreak=0`），`lastAdjustedTick = 命令日`，`lastAdjustmentDelta = 新-旧`，`quoteSource=EXPLICIT`。
  - 若只改 `targetLaborMilliPerDay`：保留 quote 与 streak，下一次 tick 末由 U20 观察新目标。
  - 若该 unit 的 production 周期已开始（`progressDays > 0`），允许改 posting（工资不影响产品份额复算），但不得改 roster；目标改动只作用于后续 tick。
- 业务拒绝（INFO、零变更）：`unit-unknown`、`wage-posting-role-unknown`、`wage-role-not-money`、`wage-target-negative`、`wage-currency-mismatch`、`wage-market-zone-missing`、`wage-quote-missing`、`wage-posting-locked`、`wage-quote-overflow`。
- 成功：`HandlerOutcome.Applied(EconomyChangeSet)`，INFO 记录 unit/role/target/currency/wage/source/replaced。

### 2.8 `economy.Seed.wagePostings`

`economy.Seed` 新增顶层键：

```json
"wagePostings": [
  {
    "unit": "unit-farm@0_0-hh-1",
    "role": "hired-hand",
    "targetLaborMilliPerDay": 5000,
    "currency": "silver",
    "initialWagePerLaborMilli": 1000
  }
]
```

规则：

1. 先确保 production 实例存在（同批 production seed/创建请求）；再写 posting。
2. 每个 MONEY_WAGE 角色在 seed 中**必须显式给 `initialWagePerLaborMilli`**（可为 0）；缺 ⇒ 创世失败 `seed-wage-quote-missing:<unit>:<role>`，不静默补 0。
3. `currency` 可省略，省略时取 unit 所在市场区 `legalTender`；给出且不一致 ⇒ `wage-currency-mismatch`。
4. `quoteSource=SEED`；seed 不允许写 U20 历史字段。
5. 旧 `FIXED_MONEY_WAGE` → quote 的换算只允许在 C3 创世适配器里显式做，标记 `quoteSource=GENERATED` 并记 INFO；运行时不得回读固定额。换算建议（工程默认）：
   `initialWagePerLaborMilli = floor(fixedAmount × 1000 / standardLaborMilliPerCycle)`；`standardLaborMilliPerCycle <= 0` ⇒ `wage-quote-missing`，不得猜。

### 2.9 币种与工资债条款

- **币种**：`posting.currency` 必须等于 unit 所在市场区 `legalTender`（`MarketZoneBook.zoneOfHex` → `MarketZone.legalTender`）。不一致 ⇒ `wage-currency-mismatch`，不做跨币种换算；不得用 ask/bid 或官方汇率折成另一种币。
- **工资债条款**（工程默认 E5）：新增工厂

```java
DebtTerms.wageArrearsDefault() = new DebtTerms(
    0,
    InterestTiming.AFTER_REPAYMENT_ON_CLOSE,
    RepaymentRule.AVAILABLE_SURPLUS_SHARE,
    MonetaryConversion.NOT_ALLOWED,
    DefaultRemedy.MARK_DEFAULTED,
    OptionalLong.empty(),
    OptionalLong.empty())
```

- 工资债单位 = `DebtUnit.Money(currency)`；不得用 `DebtUnit.Commodity` 记货币工资欠款。
- 不得套 `DebtTerms.legacyDefault()`（20‰）；若实现拿到不支持/不一致的 terms ⇒ `wage-debt-terms-unsupported`，fail-closed。
- 同日新债的排除机制见 §6.5；`capitalizeArrears` 不得再对 W3 的工资债做一次资本化。

## §3 U20 调价

### 3.1 时序

- **tick 末观测**：本 tick 的匹配/生产/工资结算完成后，用本 tick 实际 `matchedLabor` 与 `effectiveTarget` 观察。
- **下一 tick 生效**：调整后的 `wagePerLaborMilli` 写入 posting，作为下一 tick 的锁价；本 tick 已发生的 due 不追溯。
- 当前 tick 的 quote 快照在匹配前锁定；W3 只能用该快照，不得在结算时重读被调价后的 posting。

### 3.2 观测值与有效目标

对每个 `(unit, role)` posting：

```text
maxAbsorbableNonLaborForRole
  = 共享 unit cap 按稳定角色 id 序分配给该角色的上限（§4.6）
effectiveTarget
  = min(posting.targetLaborMilliPerDay, maxAbsorbableNonLaborForRole)
matchedLabor
  = 本 tick 实际匹配到该 (unit, role) 的劳动（毫小时/日）
```

- `effectiveTarget == 0` ⇒ **不调价**；quote 不变；两侧 streak 清零；`lastAdjustmentDelta=0`；仍更新 `lastObserved*`。
- 非劳动约束（原料/资格/产能）导致 `maxAbsorbableNonLaborForRole=0` ⇒ 不得误判缺工/加价；这是“岗位不可开工”，不是工资不足。

### 3.3 缺工分支（加价快、可累加）

```text
shortage = max(0, effectiveTarget - matchedLabor)
if shortage > 0:
    shortagePerMille = shortage × 1000 / effectiveTarget        // 整数 floor；范围 1..1000
    shortageStreak += 1
    satisfiedStreak = 0
    momentum = min(increaseMomentumCapTicks, shortageStreak)
    raw = floor(wagePerLaborMilli × increaseRatePerMille × shortagePerMille / 1_000_000)
    step = max(minStepMilliPerLabor, raw) × momentum
    step = min(step, maxStepMilliPerLabor)
    wage' = saturatingAdd(wagePerLaborMilli, step)
```

- `minStepMilliPerLabor ≥ 1`：零薪也能离开零（U19/U20）；不能只用 `wage × (1+rate)`。
- `momentum` 累加加价；`increaseMomentumCapTicks` 防止无限加速。
- `shortagePerMille` 用整数 floor，但至少 1（有缺口就 ≥1‰），以免小缺口永远被 floor 成 0。
- 加价只影响后续 tick；不保证涨价后盈利或一定招到人。

### 3.4 招满分支（降价慢、幅度渐增）

```text
if matchedLabor >= effectiveTarget and effectiveTarget > 0:
    shortageStreak = 0
    satisfiedStreak += 1
    if satisfiedStreak >= decreaseDelayTicks:
        rate = min(
            decreaseRateCapPerMille,
            decreaseInitialRatePerMille
              + (satisfiedStreak - decreaseDelayTicks) × decreaseRateAccelPerMille)
        step = max(1, floor(wagePerLaborMilli × rate / 1000))
        wage' = max(0, wagePerLaborMilli - step)
    else:
        wage' = wagePerLaborMilli
```

- 连续满足 `decreaseDelayTicks` 后才开始降；刚招满不立即砍薪。
- 降价率从 `decreaseInitialRatePerMille` 起，随 `satisfiedStreak` 增加，封顶 `decreaseRateCapPerMille`。
- 每次至少降 1 毫货币/小时（若 wage>0），不穿零。
- 招满清加价动量（`shortageStreak=0`），避免历史缺工继续驱动无条件涨价。

### 3.5 状态重置/衰减

- **重新缺工**：立即清 `satisfiedStreak=0`，进入加价分支；不得保留“已满足”记忆。
- **招满**：清 `shortageStreak=0`；加价动量自然归零。
- **target=0**：两侧 streak 清零，不调价。
- **显式命令改价**：两侧 streak 清零（§2.7）。
- 每次观测都写 `lastObservedMatchedLabor`、`lastObservedEffectiveTarget`、`lastMaxAbsorbable`；只有实际改价才写 `lastAdjustedTick`/`lastAdjustmentDelta`。
- 无“全局工资记忆/信用折扣”；欠薪历史不进入调价输入（U21）。

### 3.6 建议参数（工程默认，非用户裁定）

| 参数 | 建议值 | 说明 |
|---|---|---|
| `increaseRatePerMille` | 100（满缺工 10%/tick） | 缺工比例乘子；可配置 |
| `increaseMomentumCapTicks` | 8 | 加价动量上限 |
| `minStepMilliPerLabor` | 1 | 零薪离开零的最小步长 |
| `maxStepMilliPerLabor` | 200 | 单 tick 绝对步长上限（防跳变） |
| `decreaseDelayTicks` | 3 | 连续满足多少 tick 后才降价 |
| `decreaseInitialRatePerMille` | 10（1%） | 初始降价率 |
| `decreaseRateAccelPerMille` | 5 | 每多满足一 tick 的加速 |
| `decreaseRateCapPerMille` | 100（10%） | 降价率上限 |

这些值只作首版工程建议，待仿真标定；用户可推翻，不需要另立契约。

### 3.7 溢出与不确定性

- 所有乘法用 `BigInteger`/`Math.multiplyExact`；调整计算用 saturating clamp 到 `Long.MAX_VALUE`，再受 `maxStepMilliPerLabor` 限制。
- 如果 posting quote 或 due 计算结果超出可表示范围 ⇒ `wage-quote-overflow`，fail-closed（ERROR），不得静默截断。
- **不保证**工资收敛、地主盈利、充分就业或任何均衡；允许地主缩规模/退出、家户闲置。

## §4 劳动匹配与闲置

### 4.1 复用现有链路，不建第二套排序器

W2 **必须**接现有：

- `LaborQueueSettlement.apply`：阶段 1 逐户排队、阶段 2 逐 unit 全局封顶、阶段 3 写回 allocations（现状见 `LaborQueueSettlement.java:182,367,437,834`）。
- `LaborQueueBook.plan`：逐户预算/保留量/offer 决策。
- `ActivitySelector`：唯一排序器（收益率 desc → activityId asc → tieId asc）。

禁止：

- 另写“工资市场排序器”“地主招工排序器”；
- 用 `HashMap` 迭代序作为并列规则；
- 用 `MIN_NET_PER_LABOR_SCALED` 或任何非正收益阈值做经济预过滤。

### 4.2 角色级 offer

把 unit 级 `Offer` 改为**角色级** offer（unit × role）：

- 键：`(ProductionUnitId, ProductionRoleId)`；同一 unit 可有多条 offer。
- 候选面：
  - `PRODUCT_SHARE` 角色：D2 roster 中 `plannedLaborMilliPerDay > 0` 的 participant；
  - `MONEY_WAGE` 角色：posting + 本地市场区 + 资格/账户规则（§4.5），不读 roster worker。
- `maxAbsorbableLaborMilli` = 该角色本 tick 可吸收量，受两级 cap（§4.6）约束。
- `netPerLaborScaled` = 该角色每单位劳动的预期净收益（W1/W2 共用同一估值口径）。
- 排序键投影到 `ActivitySelector.RankKey`：
  - `activityId = "unit:" + unit.value() + "/role:" + role.value()`；
  - `tieId = unit.value() + "/" + role.value()`；
  - `yieldScaled` = 角色净收益刻度。
- 同一家户的多个角色 offer 共用该户预算；阶段 1 的 `plan` 已按家户预算处理，W2 不得按角色各发一份预算。

### 4.3 删除非正收益预过滤

- 删除 `LaborQueueBook.MIN_NET_PER_LABOR_SCALED` 常量及其所有引用。
- `Offer.queueable()` 改为“仅 `maxAbsorbableLaborMilli > 0`”（最多再保留物理/资格可行性，不保留经济盈利门槛）。
- `plan()` 删除 `IDLE_EXPECTED_NON_POSITIVE` 分支；负/零收益 offer 保留在队列中，按 `ActivitySelector` 的带符号 yield 排序。
- `isPreservedByQueue` 继续只保留 `LABOR_FREE`/`NO_RECIPE_OUTPUT`/`outputPriced=false`；它不是经济排序门槛。
- 负收益被选中时如实记 `GRANTED_NEGATIVE`/`GRANTED_ZERO` 读数，不得静默当空闲。

### 4.4 idle 显式候选

- 当 `allowIdle=true` 时，每户 offer 列表加入一条 idle：
  - `activityId = "idle:" + household.value()`；
  - `yieldScaled = 0`；
  - `maxAbsorbableLaborMilli = 0`；`grantedLaborMilli = 0`；
  - `outcome = "IDLE_CHOSEN"`。
- 因为 `"idle:" < "unit:"` 字典序，且 idle yield=0：
  - 正收益工作排在 idle 前（先工作）；
  - 零收益且无额外可计价收益的工作排在 idle 后（idle 胜）；
  - 负收益工作排在 idle 后（idle 胜）。
- `plan()` 遇到 idle 候选时，标记该户“已选择闲置”并停止给后续更低 yield 的工作授予劳动；但**不得取消**已保留的 GOV_SERVICE 整额/既有不排队配额。
- `allowIdle=false`：不加入 idle；零/负收益工作仍可被选择（U18）。
- idle 不产生工资、产品份额、经验、免费食物或救济；仍照常承担生活需求/缺口/人口后果。

> 边界：如果某“零工资”工作有额外可计价收益（如 W1 估值中的产品份额/实物），其 offer yield 必须 >0，才会排在 idle 前；W2 不把未定价的经验/身份收益偷算成货币收益。

### 4.5 匹配候选与稳定拒绝/跳过语义

MONEY_WAGE 角色的自动候选必须同时满足：

1. **同一市场区**：`MarketZoneBook.zoneOfHex(household.location)` 与 unit 所在 zone 相同；否则 `wage-worker-location-mismatch`，skip。
2. **角色资格通过**：按 D2 `roles[].conditions`；不通过 ⇒ `role-qualification:<role>:<household>:<reason>`，skip。
3. **organizer 本户不得给自己的 MONEY_WAGE 角色打工**：`household == organizer` ⇒ `wage-organizer-self-employment`，skip。
4. **账户存在**：`AccountSession` 有该 household 的账户；否则 `wage-worker-account-missing`，skip。
5. **劳动量 > 0**：候选本 tick 可分配劳动 >0；否则 skip。
6. **缺报价/币种错误/角色未知**：posting 级错误，见 §4.8；不得把缺报价当 0。

稳定语义：

- **候选级不合格 = skip**：不使整个 unit 失败；记录具名 reason；无候选只形成 shortage，驱动 U20 观察。
- **roster 内 PRODUCT_SHARE participant 的资格失败 = 整个实例本日业务拒绝**（D2 §7.3 不变）。
- **posting 级持久态错误**（缺 quote、币种不等于 legalTender、role 不存在、role 不是 MONEY_WAGE、target<0）**不得 skip 成 0**：命令/seed 边界 `Rejected`；运行期发现 ⇒ ERROR 契约故障，整候选 revision 拒绝。
- **同一户多角色**：允许；阶段 1 的预算按户共享，实际总劳动 ≤ 家户当日 `HouseholdEconomy.laborMilli`（以及既有 PRODUCTION/GOV_SERVICE 不变量）。

### 4.6 角色目标/unit cap 两级封顶

同一 unit 的所有角色共享一个 `maxAbsorbableNonLabor`（由产能/资产/投入决定），不能产品份额角色和工资角色各发一份 cap。

最小可行处理：

1. **角色目标 cap**：每个角色先受 `targetLaborMilliPerDay`（工资）或 roster planned labor（产品份额）限制。
2. **unit cap**：所有角色 desired 总和超过 unit `maxAbsorbableNonLabor` 时，按稳定规则缩：
   - 先按角色 id 升序分配剩余 unit cap：`roleCap = min(roleTargetRemaining, unitRemainingCap)`，`unitRemainingCap -= roleCap`；
   - 阶段 2 再按现有“逐户比例缩/最大余数”在同一角色内缩到 `roleCap`；
   - 所有并列破平用 `(roleId, householdId, unitId)` 升序。
3. 单 wage 角色 unit：`roleCap` 逐值等于 `min(target, maxAbsorbableNonLabor)`；U20 的 `effectiveTarget` 即此值。
4. 多角色 unit：U20 的 `maxAbsorbableNonLaborForRole` 取上述稳定分配后的 `roleCap`，不得拿 unit 全 cap 重复给每个角色。

> 工程默认 O7：角色 id 升序优先是“最小可行/确定性”选择，不声称公平；用户可提出别的分配规则。

### 4.7 W2 首期能力边界

**能做的**：

- 持久化 `productionWagePostings`/`wageCarryRemainders`/`householdLaborChoices`；
- 读取/校验 quote，执行 U20 调价（只写下一 tick）；
- 角色级 offer + idle 候选 + 去除非正收益过滤；
- 按家户预算、角色目标、unit cap 写回 allocations；
- 产出具名 readout/日志；缺报价、无账户、跨区等稳定拒绝/跳过。

**不能做的**：

- 工资直付/抵物/成债（W3）；
- 在不接 W3 的情况下执行含 MONEY_WAGE 角色的生产：必须 `wage-settlement-not-implemented`（D2 §8.4 边界）；
- 跨市场区自动招募/工资竞价/劳动迁移；
- 产品份额分配（D2 C2 已做）；
- 资产占用/R2（D4）；
- 自动招募 PRODUCT_SHARE 角色（仍由 D2 roster 显式建立）。

**自动招募范围**：W2 首期只限**同一 `MarketZoneId`**的本地家户；跨区候选一律 `wage-worker-location-mismatch`，由既有迁移机制另案处理。

### 4.8 匹配失败/跳过词表（与 §8.1 合并使用）

- 候选 skip：`wage-worker-location-mismatch`、`wage-worker-account-missing`、`wage-organizer-self-employment`、`role-qualification:<role>:<household>:<reason>`。
- posting/命令错误：`wage-quote-missing`、`wage-currency-mismatch`、`wage-posting-role-unknown`、`wage-role-not-money`、`wage-target-negative`、`wage-market-zone-missing`、`wage-posting-locked`、`wage-quote-overflow`。
- W2 边界：`wage-settlement-not-implemented`。
- 所有拒绝必须带稳定 reason；不得用异常消息代替 reason。

## §5 工资前置（W1）

### 5.1 雇主候选：全部应付工资进入成本

对每个候选经营单位：

```text
expectedLaborMilli(role)
  = min(posting.targetLaborMilliPerDay, maxAbsorbableNonLaborForRole)

expectedWageCostMilli
  = Σ_{role ∈ MONEY_WAGE} floor(
        quote(unit, role).wagePerLaborMilli × expectedLaborMilli(role) / 1000)
```

规则：

- **全部应付工资**都进成本；不得只计当前现金能付的部分，不得因现金不足让工资成本消失。
- `expectedLaborMilli(role)` 用 posting target 与共享 unit cap；**预计值不替代实际匹配**；实际 due 仍按 receipt 的本次 tick 实际劳动。
- 多 wage 角色按 §4.6 的稳定角色 id 序消耗共享 unit cap，不重复给每个角色整份 cap。
- 缺 quote 的 wage 角色：候选不可估价 ⇒ `wage-quote-missing`（命令/seed 已拒绝；运行期持久态错误按 §4.5 处理）；不得按 0 计。
- 显式 0 quote：`expectedWageCost=0`，但候选仍保留 `WAGE_QUOTE_ZERO` 读数；不因此生成“缺工资规则”。

### 5.2 雇工候选收入

对每个可成为 MONEY_WAGE worker 的家户：

```text
expectedWorkableLaborMilli
  = min(该户本 tick 可分配劳动,
        posting.targetLaborMilliPerDay,
        该角色在共享 unit cap 下的容量)
wageIncomeMilli
  = floor(quote.wagePerLaborMilli × expectedWorkableLaborMilli / 1000)
```

- 不能再用 `FIXED_MONEY_WAGE.fixedAmount`。
- 显式 0 quote ⇒ income=0，仍可作为候选（若 allowIdle=false 或该工作有额外收益）；缺 quote ⇒ `wage-quote-missing`，不得当 0。
- 预计 income 不是“承诺已到手”；实际支付/欠薪仍按 W3。
- 历史欠薪/兑付率不得进入该 income 或候选排序（U21）。

### 5.3 产品份额、经验与工资池

- 产品份额、经验、身份/资格收益**不抵工资**；工资是雇主独立货币/库存/债务义务。
- 雇主自己的产品份额**不能当工资池**；W1 不得把其他 participant 的产品份额或整份毛产估成可付工资的现金。
- 工资成本不得因现金不足而消失；现金不足在 W3 变成债务，不是 W1 的负成本/折扣。
- 预计产品份额/预计工资应收仍按 D2 §8.2 分户计算；D3 不重写产品份额公式。

### 5.4 W1 要改的 `Prospect` 字段/理由词（最小）

现状 `Prospect`（`ExpectedProfitBook.java:133-152`）已有 `laborCostMilli`、`rentMilli`、`netMilli`、`laborNeedMilli`、`reason`。W1 最小改动：

1. **新增 `long expectedWageCostMilli`**（或等价字段）：仅 MONEY_WAGE 角色的全部预计应付；`netMilli = revenue − inputCost − subsistenceLaborCost − expectedWageCostMilli − rentMilli`。
2. **保留 `laborCostMilli` 语义为“生存/非工资劳动折算”**，或明确把它拆成 `subsistenceLaborCostMilli + expectedWageCostMilli`；不得再把工资和生存折算混成一个无法解释的数。
3. **wageProspect**：把 `income` 改为 quote 收入（§5.2），不再读 `FIXED_MONEY_WAGE.fixedAmount`；`netMilli` 仍是该户预计工资收入。
4. **理由词**：
   - 删除/替换 `NO_WAGE_RULE` 的模糊语义；缺 quote ⇒ `WAGE_QUOTE_MISSING`；显式 0 ⇒ `WAGE_QUOTE_ZERO`；正常 ⇒ 可保留 `WAGE_POSITION`。
   - 新增 `WAGE_INCOME_ESTIMATE`（W1 预计值标记）；`WAGE_SHARE_ESTIMATE` 旧近似不再用于工资岗位的产品分成。
   - 雇主候选新增 `WAGE_COST_INCLUDED` 或等价读数，证明工资成本已入 net。
5. **不要**在 W1 里建第二套工资支付/债务；W1 只做预计收入/成本与排序输入。

## §6 U21 结算（W3）

### 6.1 due 按本次 tick 逐角色实际劳动

对每个 `(unit, role, worker)`：

```text
tickLaborMilli = receipt.laborContributionsMilli[(worker, role)]   // 本次 tick，不是周期累计
quote = 本 tick 锁定的 ProductionWageQuote
num = quote.wagePerLaborMilli × tickLaborMilli + carryBefore
due = num / 1000
carryAfter = num % 1000
```

- 只对 `MONEY_WAGE` 角色、实际劳动 >0 的 `(worker, role)` 计 due。
- 不得用 roster planned labor、本周期累计、最后一天劳动或“预计劳动”替代。
- 同一户同 tick 多角色：分别计 due；同一付款人/worker 的付款义务按稳定序处理；carry 按 `(unit, role, worker)` 分开。
- `quote` 必须在匹配前锁定；W3 不得在支付时重读被 U20 改写后的 quote。
- 精确乘加/进位见 §2.4；溢出 ⇒ `wage-quote-overflow`。

### 6.2 付款人、义务序与共享预算

- **付款人 = organizer household**（D2 唯一 organizer）；不得从 `ProductionEnterprise` 另猜，不得由 cohort 虚拟收款。
- 同一 organizer 跨 unit/role/worker 的义务按 `(unit, role, household)` 规范 id 升序处理。
- **同一付款人只有一份货币+库存预算**：所有义务共享；不得每个 unit/role/worker 各复制一份余额/库存。
- 义务表先算全部 due，再按稳定序逐条支付；后续义务看到的是前面已扣减后的共享预算。
- 同一 worker 的多个义务可以合并成一条转移（若实现方便），但 readout 必须按 `(unit, role, household)` 拆分；不得合并到无法归因。

### 6.3 支付顺序（U21）

对每个付款人的共享预算，逐义务按以下顺序：

1. **当地对应货币**：
   - 可用量 = 该币种 `余额 − 冻结`（不含其他币种；不发钞、不透支、发行人也不因工资缺口铸币）。
   - `paidMoney = min(dueRemaining, availableMoney)`。
   - 不得把其他币种按汇率折进来；不得用 `MoneyIssuance` 补工资缺口。
2. **可支付活动库存**：
   - 按**初始可用数量降序**、同量 `CommodityId` 升序（稳定序；不是价值序）。
   - 可用量 = 该商品在结算地点的活动库存 − 冻结 − 已投入生产/占用的数量 − 再生产权益/已投入资料；不得含 D4 未授权的占用。
   - 每件商品按 §6.4 折价支付；不足继续下一商品。
3. **剩余成债**：
   - `arrears = dueRemaining`（W3 只把最后未付差额记债，不得把已抵物部分再记一次）。
   - `DebtUnit.Money(currency)` + `DebtTerms.wageArrearsDefault()`；经 `DebtContractBook.upsert` 落合同。
   - 多 unit/role 同币种、同 debtor/creditor 的工资债可合并为同一连续合同（`DebtContractId.idOf` 同 id 累加本金）；不同币种/terms 不合并。

禁止：

- 预付：不得在劳动前先发工资/口粮/生成冲抵；不得借普通交易入口（`MARKET_TRADE`/`RELATION_PAYMENT` 等）绕出等价预付。
- 同一笔库存双付；不得先记全部欠薪再把抵物重复记成付款。
- 用再生产权益/已投入资料/下一周期种子抵工资。
- 用兑付率/黑名单/信用折扣影响下一 tick 择业（U21）。

### 6.4 抵物折价公式

对每个可支付商品 `c`：

```text
bid = market.bidPriceOf(c)                    // 当地市场卖方限价；毫货币/商品单位
if market == null or !market.hasPrice(c) or bid <= 0:
    skip c with "wage-barter-price-missing:<commodity>"; continue
if 商品在结算地点不可交付（账户/地点/冻结/占用不允许）:
    skip c with "wage-barter-not-deliverable"; continue

availableQ = 该商品初始可支付量（毫商品单位，扣冻结/投入/权益后）
qWanted = floor(remainingDue × 1000 / bid)    // 毫商品单位
qPay = min(availableQ, qWanted)
paidValue = floor(qPay × bid / 1000)          // 毫货币；≤ remainingDue
```

- 缺价/0 价/不可交付 ⇒ **skip，不得 1:1、不得任意价、不得算已清偿**。
- `paidValue ≤ remainingDue`；若因整数除法有零头，剩余继续下一商品或成债；不得超付。
- 初始可用数量排序在支付前拍一次快照；后续商品被前面用掉不改变排序。
- 库存腿与货币腿可放在同一条 `Transfer`（`WAGE_PAYMENT`），但 readout 必须拆出 money/barter/arrears。

### 6.5 工资债条款与同日排除

- 新建工资债：`DebtUnit.Money(currency)` + `DebtTerms.wageArrearsDefault()`（0‰）；`openedDay=day`；`dueCycle=当前周期+1`（工程默认 O8）。
- 多 unit/role/worker 同币种工资债合并：`DebtContractBook.upsert` 同 `(debtor, creditor, DebtUnit.Money(currency), terms)` 会累加本金。
- **同日不双付**（E12）：
  - W3 在当日返回 `WageSettlementResult.newWageDebtIds`（transient day-local `Set<DebtContractId>`）。
  - 同日 `repayDebts` 在构造 `owed` 列表时排除这些 id；若本会选中则记 INFO `wage-debt-same-day-repay-blocked`（worker、creditor、debt id、amount）。
  - 排除集只活当天，不落持久态；次日工资债可正常进入还款轮。
  - `capitalizeArrears` 不得对 W3 工资债再资本化（W3 直接 upsert，不把 wage arrear 塞进旧 `ProductionLedger.Arrear`）。
- 工资债本金 = 未付差额；抵物已付部分已从 dueRemaining 扣掉，不得再次减本金。`DebtContractBook.upsert` 要求本金增量 >0，`arrears=0` 时不得调用。
- `wage-debt-terms-unsupported`：terms/币种/单位不符合 `wageArrearsDefault()` 时 fail-closed。

### 6.6 转移、原因与账户写口

- 新增 `TransferReason.WAGE_PAYMENT("wage_payment")`；不得复用 `RELATION_PAYMENT` 冒充工资（读账要能分开工资 vs 分成/地租）。
- 一条 `Transfer` 可同时带货币腿与商品腿；`from=organizer actor`、`to=worker household actor`、`location=unit 结算地点`；`from != to`（organizer 自雇已在匹配阶段排除）。
- 所有账户落账只经：
  - `AccountSession.commit(Collection<? extends OrderedAccountIntent>)`（唯一批提交），或
  - `EconomySettlement.applyTransfer(...)`（唯一转移写口）。
- **不得直接改 `householdGoods`/`householdMoney` Map**；不得绕过冻结/余额校验；不得用 `AccountDelta` 手工造工资转账（工资必须是 `Transfer` 语义，读账可归因）。
- 转移失败（余额/冻结/端点缺失）⇒ 整批 fail-closed，不落半截 revision；不得降级为“工资已付”。

### 6.7 `WageSettlementReadout`（拆钱/物/债）

每个 `(unit, role, worker)` 至少输出：

```text
unit, role, worker, payer, currency
tickLaborMilli, quote, carryBefore, carryAfter
dueMilli
paidMoneyMilli
barter: Map<CommodityId, Long> qPay; Map<CommodityId, Long> paidValueMilli
arrearsMilli
debtContractId (optional)
reasons (stable words)
```

派生/守恒：

```text
dueMilli == paidMoneyMilli + Σ paidValueMilli + arrearsMilli
```

- 读数必须和转移/债务合同同源；不得只报“已支付”或只报“欠款”。
- 逐日汇总日志：payer、unit、roles、workers、due、money、barter、arrears、blockedSameDayDebt。
- 同日新债排除读数：`wage-debt-same-day-repay-blocked`。

### 6.8 欠薪不影响择业

- W1/W2 的 worker 候选收入/排序**不得**读取历史欠薪、兑付率、黑名单、信用折扣；只看当前 quote 与可得实物/工资形态。
- 欠薪仍是真实债权/债务，影响库存/需求/后续劳动能力按既有机制发生；不得因为“已经欠薪”而禁止地主再雇人或家户再应募。
- 不得用“兑付率折价”把欠薪合同当成已兑现收入；也不得用它生成新的拒绝条件。

## §7 tick 时序

### 7.1 顺序表

| 步 | 动作 | 现有落点/新增落点 | 说明 |
|---|---|---|---|
| 1 | Social 投影 | `PopulationEconomyTimeParticipant.java:664-668` | composition/laborBudget/naturalNeeds 进经济会话 |
| 2 | `production.beginTick(day)` | 同文件 `:681` | 每 tick 一次；经验衰减/条件冻结 |
| 3 | posting/roster 校验 + 本 tick quote 快照 | W2 新增（`EconomySettlement.step` 前段或 `beginTick` 后） | 锁定本 tick quote；缺 quote/币种错误 fail-closed |
| 4 | 匹配/择业（角色 offer + idle） | `LaborQueueSettlement.apply`；W2 改角色级 | 阶段 1 逐户排队、阶段 2 两级 cap、阶段 3 写 allocations |
| 5 | 统一分配（劳动/资产/投入） | 现有 `EconomySettlement` 劳动/投入段；D4 接资产 | 同一个 unit 的角色共享 unit cap |
| 6 | 抽料 | 现有 `drawCycleInputsPartitioned` | 与 D2/D4 既有输入链一致 |
| 7 | 生产 `executeDay` | `EconomySettlement.java:1676-1677` → `:4222-4284` | 回执含本次 tick participant 明细（D3 修正 1） |
| 8 | 工资结算/抵物/成债 | W3 新增，位于 `driveProductionDay` 后、市场轮/还债前 | 付款人共享预算；`WAGE_PAYMENT`；同日债排除 |
| 9 | 市场/生活/债务/缺口 | 现有市场轮、消费、`repayDebts`、报缺口段 | 工资到账发生在本步之前；消费已发生时只能影响下一 tick |
| 10 | Social 回写 | `PopulationEconomyTimeParticipant.java:738-795` | 缺口→饱食度→人口；工资事实只经既有 outbox/桥 |
| 11 | 下一 tick 调价生效 | W2 U20 在 tick 末写 posting；下一 tick 步 3 读取 | 不追溯本 tick due |

### 7.2 插入到 `EconomySettlement.step`

- `EconomyDayStepper.step(day)`（`:427`）调 `EconomySettlement.settleOneDayInto`；后者内部顺序是：劳动分配 → 消费 → 抽料 → `driveProductionDay`（`:1676`）→ 关账/收获（`:1900+`）→ `capitalizeArrears`（`:1981`）→ 市场轮（`:2050+`）→ 借/还债 → 报缺口。
- **W3 工资结算插在 `driveProductionDay` 之后、市场轮/`repayDebts` 之前**；这样：
  - 工人本 tick 的工资/实物在后续市场轮之前到账；
  - 工资债在当天进入债务表，但同日不会被 `repayDebts` 再付（§6.5）；
  - 不得把工资结算放在 `capitalizeArrears` 之后又被旧还款轮重复处理。
- **不得把工资结算放到 `consumeOwnStockPartitioned` 之前**冒充“同 tick 吃饱”：现状消费在 `:1602-1613`，早于 `driveProductionDay`；D3 不重排消费。若未来要同 tick 吃饱，必须另立时序设计并由用户确认（O3）。

### 7.3 消费时序的如实说明

当前一手事实：

- `consumeOwnStockPartitioned` 在 `EconomySettlement.java:1602-1613`；
- `driveProductionDay` 在 `:1676-1677`；
- 工资结算在其后。

因此：

- 本 tick 的工资/实物**不能**进入本 tick 已经发生的口粮消费；
- 若本 tick 后续有市场轮，工人可用新到账的钱/物在市场上买粮，但**是否买到、是否够吃、是否被市场轮触发**取决于市场调度，不能宣称“同 tick 已吃饱”；
- 工资对劳动能力/饱食度的正向影响一般从下一 tick 的 Social 投影开始（U19 的跨 tick 反馈）；
- 负向事实（没收到钱粮、口粮缺口、饱食度下降）仍按既有链照常发生，不得用“预计工资”抵消。

### 7.4 U20 与 tick 时序

- 本 tick 的 quote 快照在步 3 锁定；步 8 的 due 只用该快照。
- 本 tick 实际 `matchedLabor`/`effectiveTarget` 在步 4/7 已知；U20 在步 8 之后（tick 末）观察并写下一 tick posting。
- `lastAdjustedTick` 记录观察发生的 day；下一 tick 的步 3 读取新 quote。
- 调价失败/溢出 ⇒ 不得半写 posting；保留旧 quote 并具名 ERROR（或命令 Rejected，视边界）。

## §8 失败词表、日志、不变量、验收

### 8.1 稳定词表

**业务拒绝/skip（INFO；零状态变化或仅跳过候选）**：

```text
wage-quote-missing
wage-quote-zero
wage-currency-mismatch
wage-target-negative
wage-posting-locked
wage-posting-role-unknown
wage-role-not-money
wage-organizer-self-employment
wage-market-zone-missing
wage-worker-account-missing
wage-worker-location-mismatch
wage-barter-price-missing:<commodity>
wage-barter-not-deliverable
wage-debt-terms-unsupported
wage-settlement-not-implemented
wage-posting-unit-unknown
wage-payer-account-missing
```

**契约/跨切片故障（ERROR，不降级，整候选 revision 拒绝）**：

```text
wage-quote-overflow
wage-carry-out-of-range
wage-posting-key-mismatch
```

**非失败标记/INFO 跳过**：`wage-quote-zero`（显式 0 报价合法）、`wage-debt-same-day-repay-blocked`（当日还款轮排除新工资债，不是失败）、`IDLE_CHOSEN`、`GRANTED_ZERO`、`GRANTED_NEGATIVE`。

语义补充：

- `wage-quote-missing`：缺报价，**不得当 0**；命令/seed 边界 `Rejected`，运行期持久态错误 `ERROR`。
- `wage-quote-zero`：显式 0 的读数，不是错误；U20 可用 `minStep` 离开零。
- `wage-posting-locked`：命令试图改写本 tick/历史 quote 或 U20 观测字段；只允许下一 tick 生效。
- `wage-barter-price-missing:<commodity>`：该商品缺价/0 价，skip 并保留剩余欠款；不得 1:1。
- `wage-debt-same-day-repay-blocked`：同日新工资债被还款轮排除的 INFO 读数；不是失败。
- `wage-settlement-not-implemented`：W2 尚未接 W3 时，含 MONEY_WAGE 角色的执行边界；D2 §8.4 保留。

### 8.2 日志

- **INFO**：posting set/clear、显式改价、U20 改价（old→new、reason、streak）、工资结算日汇总（payer/unit/roles/workers/due/money/barter/arrears）、同日债排除、命令成功。
- **DEBUG**：逐候选 skip、逐义务 due/paid/arrears、barter 初始排序、carry before/after、两级 cap 结果、idle 选择。
- **TRACE**：逐商品腿、逐 worker/role 明细、quote 快照。
- 业务拒绝 = INFO 具名；契约/跨切片一致性故障 = ERROR 不降级；日志失败不得影响状态（沿用 AGENTS §一.9）。
- 日志不得写载荷明文敏感内容；不得用异常消息代替稳定 reason。

### 8.3 不变量（D3-I）

1. **D3-I1 报价锁定**：本 tick 匹配/结算只读本 tick quote 快照；U20/命令改价只影响后续 tick；已发生 due 不追溯。
2. **D3-I2 三数守恒**：逐义务 `due = paidMoney + Σ paidBarterValue + arrears`；不得超付、不得重复减本金。
3. **D3-I3 共享预算**：同一付款人跨 unit/role/worker 只有一份货币+库存预算；不得复制余额/库存。
4. **D3-I4 库存不双付/冻结不越**：同一库存腿只付一次；余额/冻结/投入/权益排除只扣一次；不得越过冻结；不得用再生产权益抵工资。
5. **D3-I5 缺 quote ≠ 0**：`Optional.empty()` 与显式 0 分开；缺 quote 不得生成 0 工资/0 债务。
6. **D3-I6 欠薪不影响择业**：W1/W2 不读历史欠薪/兑付率/黑名单/信用折扣作为候选输入。
7. **D3-I7 实际劳动只计一次**：due 只按 receipt 本次 tick participant 明细；不得拿周期累计/计划劳动再算一遍；同一 `(household, role)` 不重复。
8. **D3-I8 债务本金 = 未付差额**：barter 已付部分已扣 due；债务只记最后差额；不得把已抵物再记债。
9. **D3-I9 整数/确定性**：全部整数，无随机/时钟/UUID；稳定序 `(unit, role, household)`、`(commodityId)`、`(householdId)`；并列有稳定破平。
10. **D3-I10 币种一致**：quote currency == unit 市场区 legalTender；不跨币种换算。
11. **D3-I11 carry 合法**：carry ∈ [0,999]；按 `(unit, role, worker)` 持久；不得跨户共享。
12. **D3-I12 目标改动不追溯**：`targetLaborMilliPerDay`/quote 改动只影响后续 tick；不得修改历史 readout。
13. **D3-I13 同日工资债不双付**：当日新 wage debt 被 `repayDebts` 明确排除，次日才可还。
14. **D3-I14 禁止预付**：无劳动不发生工资/口粮/冲抵；不得用普通交易原因绕出等价预付。
15. **D3-I15 账户唯写口**：只经 `AccountSession.commit`/`EconomySettlement.applyTransfer` 落账；不直接改 Map。
16. **D3-I16 名册/岗位边界**：MONEY_WAGE 实际 worker 由 allocations/receipt 决定；不得伪造 roster placeholder 或第二权威。
17. **D3-I17 W2 边界**：未接 W3 时含 MONEY_WAGE 角色的执行 `wage-settlement-not-implemented`；不得只发产品份额不记工资。
18. **D3-I18 不冒充 R2**：D3 不修共享资产/多实例占用；D4 未接时的资产限制继续具名。

### 8.4 正向验收判据

| # | 判据 | 预期 |
|---|---|---|
| P1 | 显式 `wagePerLaborMilli=0` posting | quote 存在、可匹配、U20 能从 0 加价；不报 `wage-quote-missing` |
| P2 | 缺初始 quote 的 seed/命令 | `wage-quote-missing`，零变更；不得写 0 |
| P3 | quote 币种 ≠ legalTender | `wage-currency-mismatch`；不换算、不写状态 |
| P4 | 缺工连续多 tick | `shortageStreak` 累加、加价步长随 momentum 增大、零薪离开 0 |
| P5 | 招满连续 `decreaseDelayTicks` | 之前不降；之后缓慢降、幅度递增、不穿零 |
| P6 | 重新缺工 | 立即清降价连胜、进入加价；招满清加价动量 |
| P7 | `allowIdle=true`、零工资工作 | idle 胜出，`IDLE_CHOSEN`；正收益工作仍排在 idle 前 |
| P8 | `allowIdle=false`、零/负收益工作 | 不预过滤；可被 `GRANTED_ZERO`/`GRANTED_NEGATIVE` 选择 |
| P9 | 同一户多角色 | 阶段 1 预算共享；总劳动 ≤ `HouseholdEconomy.laborMilli` |
| P10 | 产品份额角色 + 工资角色同 unit | unit cap 共享；不各发一份；两级封顶守恒 |
| P11 | organizer 本户 vs 自己 wage 角色 | `wage-organizer-self-employment` skip；不生成自付工资 |
| P12 | 缺账户/跨市场区/资格不符 | 分别 `wage-worker-account-missing`/`wage-worker-location-mismatch`/`role-qualification:...` skip |
| P13 | W1 雇主候选 | `expectedWageCostMilli` = Σ quote×expectedLabor/1000；net 扣工资；缺 quote 不按 0 |
| P14 | W1 工人候选 | income 用 quote×expectedWorkableLabor/1000；显式 0 与缺 quote 分开 |
| P15 | 工资 due 用本次 tick 明细 | 同一周期不同 tick 劳动变化 ⇒ due 随之变化；不按周期累计/计划劳动 |
| P16 | carry | quote=1、每 tick 500mh：首 tick due=0/carry=500，次 tick due=1/carry=0 |
| P17 | 货币优先 | 有足够对应货币时先付钱，不动库存 |
| P18 | 库存数量降序 | 初始可用数量最多的商品先抵；同量按 CommodityId 升序 |
| P19 | 缺价/0 价商品 | `wage-barter-price-missing:<commodity>` skip，剩余成债；不 1:1 |
| P20 | 同一付款人两工人 | 共享预算：先义务先付，后义务只拿剩余；Σ支付+欠款=Σdue |
| P21 | 同一工资债同日还款轮 | `wage-debt-same-day-repay-blocked`，不双付；次日可还 |
| P22 | 工资债条款 | `wageArrearsDefault()` 0‰；不使用 legacy 20‰ |
| P23 | 转移 | `WAGE_PAYMENT`；一条可带钱+物腿；readout 拆 money/barter/arrears |
| P24 | 账户落账 | 只出现 `AccountSession.commit`/`applyTransfer` 路径；无直接 Map 写 |
| P25 | 欠薪不影响择业 | 历史欠薪/兑付率不改变候选排序/收益；新报价仍可应募 |
| P26 | 目标/改价不追溯 | 改 target/quote 后，已完成 tick 的 due/readout 不变；下一 tick 生效 |
| P27 | 确定性 | 同输入同输出；稳定序；无随机/时钟/UUID |
| P28 | 日志 | INFO/DEBUG/TRACE 各至少一条可断言事件；拒绝有具名 reason |

### 8.5 负向/变异判据（改坏 ⇒ 当场红 ⇒ 还原 ⇒ md5 一致）

| 变异 | 应红 |
|---|---|
| 缺 quote 时按 0 计 | P2/P13/P14 |
| 用 `FIXED_MONEY_WAGE.fixedAmount` 代替 quote | P13/P14 |
| 保留 `MIN_NET_PER_LABOR_SCALED` 预过滤 | P7/P8 |
| idle 用 Map 偶然序而不是稳定 activity id | P7 |
| 同一户按角色各发一份劳动预算 | P9 |
| 产品份额/工资角色各发一份 unit cap | P10 |
| organizer 自雇 wage 角色 | P11 |
| 用周期累计/计划劳动算 due | P15 |
| carry 跨 worker 共享/复位错误 | P16 |
| 付款顺序先库存后货币 | P17 |
| 库存按价值/口粮优先排序 | P18 |
| 缺价按 1:1 或任意价 | P19 |
| 同一付款人复制预算 | P20 |
| 同日新工资债被还款轮再付 | P21 |
| 工资债套 20‰ legacy terms | P22 |
| 复用 `RELATION_PAYMENT` 冒充工资 | P23 |
| 直接改 `householdGoods`/`householdMoney` | P24 |
| 用欠薪历史/兑付率影响择业 | P25 |
| 改价追溯已完成 due | P26 |
| 用 HashMap/随机/时钟排序 | P27 |
| 迁移/命令静默改历史 posting 字段 | P26/`wage-posting-locked` |

### 8.6 数值例（W5 用）

1. **零薪离开零**：`wage=0`、`target=10000`、`matched=0`、`shortage=1000‰`、`minStep=1`、`increaseRate=100`、`momentum=1` ⇒ `step=max(1,0)×1=1`，新 wage=1；连续缺工 3 tick ⇒ momentum=3，步长累加。
2. **缺工累加**：`wage=100`、`target=10000`、`matched=7000`、`shortage=300‰`、`increaseRate=100`、momentum=3 ⇒ `raw=floor(100×100×300/1_000_000)=3`，`step=3×3=9`，新 wage=109。
3. **招满慢降**：`wage=1000`、`decreaseDelayTicks=2`、`decreaseInitialRate=10`、`accel=5`、`cap=100`；satisfiedStreak=2 时仍不降；streak=4 时 `rate=10+(4-2)×5=20`，`step=max(1,floor(1000×20/1000))=20`，新 wage=980；不穿零。
4. **价格缺失跳过**：due=10，库存只有商品 X 但 `hasPrice(X)=false` ⇒ `wage-barter-price-missing:X`，arrears=10；即使 X 数量很多也不抵。
5. **数量排序**：初始可用：fiber=500、grain=300、tool=300；排序 fiber（500）→ grain（300，同量 CommodityId 升序）→ tool（300）；不按价值排序。
6. **同一付款人两工人**：organizer 可用货币 7，worker A due=5、worker B due=5，稳定序 A→B ⇒ A 付 5、B 付 2、B 欠 3；不得 A/B 各看到 7。
7. **quote=1、500mh carry**：tick1 `num=1×500+0=500` ⇒ due=0、carry=500；tick2 `num=500+500=1000` ⇒ due=1、carry=0。
8. **同日债不双付**：due=10、现金付 4、抵物付 3、arrears=3；同日 `repayDebts` 看到新债 3 必须 skip 并记 `wage-debt-same-day-repay-blocked`；若还款轮又付 3，P21 红。

## §9 文件所有权 / W1–W5 / 未做

### 9.1 批次与 owner 边界

> 只列方向与禁止面；具体类/方法拆分由实现 owner 在实现架构账本中定。测试统一最后由 W5 独立测试 owner 写/跑。

| 批次 | 可写方向 | 禁止/边界 |
|---|---|---|
| **W1** | `simos-economy/.../time/ExpectedProfitBook.java`；`model/ProductionWageQuote.java`/`ProductionWageQuoteLookup.java`（若 D2 尚未落）；W1 所需只读 helper | 不改 `LaborQueueBook` 排序/过滤（W2）；不实现支付/抵物/债（W3）；不写测试 |
| **W2** | `simos-economy/.../EconomyData.java`、`EconomyDayView.java`、`EconomyBoundaryView.java`；`change/EconomyChangeSet.java`；`codec/EconomyCodec.java`；新增 `model/ProductionWagePosting.java`、`WagePostingKey.java`、`WageCarryKey.java`、`HouseholdLaborChoice.java`；`spi/EconomySetProductionWagePostingHandler.java`、`EconomySetHouseholdLaborChoiceHandler.java`、`EconomyPayloads.java`、`EconomySeedHandler.java`；`time/LaborQueueBook.java`、`ActivitySelector.java`（仅复用/必要可见性）、`LaborQueueSettlement.java`、新增 `time/ProductionWageBook.java`；`EconomyReferenceValidation.java` | 不实现 W3 支付/抵物/成债；不做跨区自动招募；不写产品份额分配；不改 app；不写测试 |
| **W3** | `simos-economy/.../time/EconomySettlement.java`（插工资结算、同日排除）、新增 `time/WageSettlement.java`/`WageSettlementReadout`、`ProductionSettlement.java` 的旧 wage 调用面（只删/替换名册路径）、`TransferReason.java`（新增 `WAGE_PAYMENT`）、`DebtTerms.java`（新增 `wageArrearsDefault()`）、`DebtContractBook` 如仅需读口不改语义；`model/...` 只做 D3 所需最小状态 | 不修 R2/资产占用（D4）；不碰 POM/XML；不写测试；不直接改账户 Map |
| **W4** | `simos-app/.../PopulationEconomyTimeParticipant.java`（tick 时序/Social 回写接线）、`EconomyDayStepper.java`/`EconomySettlement.step` 的调用顺序、只读 app 读口 | 不在 app 实现报价/匹配/支付公式；不复制 economy 逻辑；不写测试 |
| **W5（独立测试 owner）** | `simos-economy/src/test/**`、`simos-app/src/test/**`、`simos-production/src/test/**`、门禁守卫清单 | 按 §8 判据与 §8.6 数值例写测；含负向/变异；不由实现 owner 写/跑测试 |

### 9.2 文件所有权红线

- 同一文件同一时刻一个 owner；W1–W4 不得并行改同一文件。
- 不修改 D1/D2/status 文档；D3 对 D2 的最小修正由后续 owner 在 C0/W 批次同步，D3 不直接改 D2。
- 不改 `pom.xml`/XML/enforcer；不新增模块依赖（`ProductionRoleId` 按 D2 放 `simos-economy-api`）。
- 不写/不改测试（W5 除外）；不跑 `test`/`verify`（W5/门禁 owner 除外）。
- 不 `git add/commit/stash/checkout/merge/push`；不碰主树。
- 不把“D3 设计已落盘”写成“工资链已实现”。

### 9.3 未做/未验证清单

1. **未实现**：本文只有设计；没有 Java/XML/POM/测试改动，没有编译、没有跑 Maven/测试/仿真，没有声称工资链已实现。
2. **未验证**：U20 参数未标定；不保证收敛、盈利、充分就业或任何均衡。
3. **未修 R2**：共享资产/多实例占用仍具名未修；D3 不接管 D4。
4. **同 tick 吃饱未实现**：当前消费早于 `executeDay`；工资到账只能影响下一 tick 的消费/劳动能力，或本 tick 后续市场轮购买，不声称同 tick 已吃饱。
5. **跨区自动招募未做**：W2 只限本地市场区；跨区走既有迁移。
6. **旧 `FIXED_MONEY_WAGE` 非名册路径未删**：D3 只覆盖名册工资链；旧路径仍存在并可能继续用 legacy terms，D3 不冒充已修。
7. **`allowIdle` 默认 true 是工程默认**：未经用户逐字裁定；用户可推翻。
8. **多角色 unit cap 的角色 id 优先序是工程默认**：不声称公平；可另行设计。
9. **`ProductionReceipt` 本次 tick participant 明细未落形**：D3 在 §0.3 给出最小修正，需 D1/D2/C1 owner 在实现时同步；D3 不直接改 D2 文件。
10. **测试证据为零**：§8 判据全部没有运行记录；W5 完成后才可写“通过”。

### 9.4 复核命令（只写命令，本次不运行）

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module

# W1/W2/W3 编译（实现 owner 只到 compile，不跑 test）
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am

# W5 测试（独立测试 owner）
tools/mvn-lock.sh -pl simos-economy -am test
tools/mvn-lock.sh -pl simos-app -am test

# 最终门禁（写者停手后，主树控制方）
tools/gate.sh full

# 只读核对：不得出现第二套报价/排序/写口
rg -n "MIN_NET_PER_LABOR_SCALED|wageQuote|ProductionWagePosting|WAGE_PAYMENT|wageArrearsDefault" simos-economy/src simos-economy-api/src simos-app/src
git status --porcelain=v1
```

### 9.5 落盘状态

- 侧线工作树基线：`0128b0cc`。
- 本文为**新增未跟踪文档**；未提交、未同步主树、未 `git add`。
- 没有跑 Maven/测试/仿真；没有修改 D1/D2/status/Java/XML/POM/测试；没有修 R2；没有声称工资链已实现。
