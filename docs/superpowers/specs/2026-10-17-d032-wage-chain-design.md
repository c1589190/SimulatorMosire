# D-032 D3 工资链与劳动匹配约束设计书（§17，2026-10-17）

> **性质：设计分册，不是实现派单书。** 本文只冻结“工资链与劳动匹配必须长成什么样”，**不实现、不编译、不跑测试、不提交、不修 R2、不声称工资链已实现**。W2a/W1/W2b/W3/W4 的实现派单必须另写，W5 测试由独立测试 owner 负责。
>
> **对应重规划**：`docs/superpowers/plans/2026-10-17-d032-production-line-replan.md` §4 Phase D 的 **D3**、Phase W 的 **W1–W5**。D3 落盘前只允许做设计调查；不得以本文代替实现证据。
>
> **输入与效力（从高到低）**：
> 1. 用户设计原稿 D-032/U15–U23（2026-10-17，逐字见 §0.1；U22/U23 另见 `2026-10-17-d032-wage-idle-barter-decision.md` §7）；U15–U21 与 D2 §1 逐字一致；
> 2. D2 合作生产与分配约束设计书：`docs/superpowers/specs/2026-10-17-d032-cooperative-production-design.md`（重点 §0/§1/§5/§6/§8.4/§10.2/§11/§12/§13/§14）；
> 3. D1 共享生产定义形状冻结：`docs/superpowers/specs/2026-10-17-d032-production-definition-v2-freeze.md`（含 D2 更正注）；
> 4. 工资裁定与历史提案，只取未被取代部分：`2026-10-17-d032-wage-settlement-simplification-decision.md`、`2026-10-17-d032-employer-posted-wage-redesign.md`、`2026-10-17-d032-tick-wage-feedback-proposal.md`（§9/U20、§11/U21 为准）、`2026-10-17-d032-distribution-wage-offer-proposal.md`、`2026-10-17-d032-preselection-wage-feasibility-analysis.md`；
> 5. 当前代码事实（§1 全部回代码核过，行号为本文落盘时侧线工作树 `0128b0cc` 实测）。
>
> **边界**：D3 只冻结工资报价状态、U20 调价、劳动匹配/闲置、工资前置、直付/抵物/成债、tick 时序与验收判据。D4/Phase A 的资产地点/占用期/统一占用（R2）仍归 D4；生产定义、角色经验、产品份额归 D1/D2/production。**2026-10-17 整合轮只统一 D1/D2/D3 设计正文；不改 status/R2/Java/XML/POM/测试，不触碰主树、不提交。**
>
> **2026-10-17 第三次定点修正注（N-01/N-02/N-04/N-05/N-06/N-08/N-09，当前有效）**：lookup 对 payer×commodity **全地点**扣预留；W1/W2b/W3 共享 `WagePayerLocationPrecheck`；`LivelihoodAlternative.incomeAvailableAtTick` 与 `maxCombinations` 时间/组合上限；`DebtSource` 全写入点/迁移/seed/codec owner；删除“D4 未完成/不改 replan”旧句；共享 economy 文件交接顺序与 N-08。以下旧 R 修正注仅作历史。
>
> **2026-10-17 D1–D4 独立复核局部修正注（R-03/R-04/R-05/R-07/R-11/R-16，当前有效）**：本册按 `2026-10-17-d032-d1-d4-design-review.md` 修正：组件号只声明 D2/D3 新增 32–35，D4 后续 36/37；§2.5.1 增补逐需求行覆盖算法与收入去重；§6.2/§6.9 改为 payer 级先聚合、跨 settlement location 整组拒绝；§2.9.1 增加可持久 `DebtSource` 与旧工资债 0‰ 收口；§9.1 明确 W3 只消费 D4-I 的 `WagePayableInventoryLookup`、不新增/不实现该文件。旧正文保留为历史 provenance。

## §0 口径溯源与状态

### 0.1 用户原话附录（逐字，2026-10-17）

> 来源：`docs/superpowers/specs/2026-10-02-architecture-design-source.md` D-032 节（U22/U23 并存于 `docs/superpowers/specs/2026-10-17-d032-wage-idle-barter-decision.md` §7）；U15–U21 与 D2 §1 逐字一致。下列均为用户实际口述日 2026-10-17；控制方解释不混入引文。

> **U15**：「不是，就是直接的产品分配需求——666，地主来收租子，你和地主说，你没下地干活，你不许拿，你看地主抽不抽你就完了；以及好像没有考虑现金结算问题，使用什么东西支付生产方式也要定义，例如佃农制就是直接分粮食，雇农制则是在农业生产方式的最底下加一档雇农，然后给作为生产组织者的地主加上随着雇农投入劳动力扩大而扩大的货币结算分成，例如雇农提供n劳动力给m当地市场区货币，没有对应货币再用地主库存里其他东西按顺序结，这个具体结算价格应该让地主自主报价、跟着市场区，问题是这里如何实现地主自主报价？思考调查一下」

> **U16**：「1 这个估算有算法吗？ 2 我不建议搞这么复杂，最好是算出来劳动价格之后直接给——是否选择进行雇农生产是家户自己的事，给钱是地主的事 3 因此也不用算这么复杂，工资库存给不起直接计成债务就行，有库存就给库存，不设置额外的土地生产资料，被用作再生产的生产资料可以视为权益了，不算做可变卖活动库存」

> **U17**：「地主支付这个环节应该算成地主的生产方式选择——家户在选择生产方式并成为地主之前，必须先根据对应生产方式，算在选用的时候，本轮能雇到人的愿给出劳动力价格，然后再把这个价格计入生产预估花费，算出这个家户选什么生产方式；因此口径应当改成不亏本的最低价——要不是能剥削谁雇雇农啊？这样的体系自洽吗？在经济景气、经济不景气的时候这样的机制分别会产生什么效果」

> **U18**：「最低工资在市场逻辑下为什么不能报0或者很低呢，流民/无产者家户有什么办法吗，它必须选一个生产方式，入不敷出是必然，地主是出价的那一方，重新设计一下」

> **U19**：「从0开始报价那完蛋了，但是我想不到其他好办法；这样，给家户同时加一个允许不从业的设定，如果本tick地主家户报价为0，那么无产者家户算了算，发现没收入还得投入劳动力，如果换不到第二tick的口粮，就算不想罢工也无法投入生产，必须要倒闭有能力选地主的家户加工资，这里就是一个动态的市场计算博弈，博弈随着tick增加而进行，有办法自恰吗？」

> **U20**：「对，可以给一个在招不到人的时候非常快乃至累加的加价速度，招到人之后缓慢增大的降价速度，还有其他需要裁决的问题吗？」

> **U21**：「1 不影响 2 不允许，地主优先给钱，没钱了再按库存最多的东西抵」

> **U22**：「我决定先修bug」

> **U23**：「1 肯定不计 2 任何家户只要条件允许（从事任何可从事的生产方式都没法换取下一tick的生存所需）都可以被裁决为不从业 3 折价可以更低一些，总之是可配置项，注意明确模块边界」

**控制方说明（非用户原话，不能冒充裁定）**：U15 中“货币结算分成”按 D2 已落盘更正理解为**雇主的工资支出义务**，不是地主收入；U16/U21 的支付顺序按原文“先对应货币 → 可支付活动库存按数量从多到少 → 剩余成债”；U18/U19 允许零/负收益工作成为候选、允许不从业，但不得虚构免费闲置或维生保障。**U22 只是执行优先级（先修 R2），不是对工资三项的裁定**；**U23 三项是用户已定**（欠薪零息 / 生存条件下可裁决不从业 / 抵物折价可配置且可更低），见 §0.2 A 表与 §0.5 总表，不再作为待裁定问题。

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
| U19 | 家户**允许不从业**；报价/选择/支付按 tick 推进（不从业的**资格条件**由 U23 收窄，不再用布尔开关） | U19 + U23 | §2.5 下一 tick 生存条件资格 + §4.4 `IdleOption` |
| U20 | 缺工时**加价快且可累加加速**；招到人后**降价缓慢、幅度渐增** | U20 | §3 U20 公式（参数为工程默认） |
| U21 | 欠薪记录**不影响**下一 tick 择业；**禁止预付** | U21 | W2/W3 禁止把历史兑付率/黑名单/预付作为择业输入 |
| U21 | 付款顺序：**对应货币 → 可支付活动库存按数量从多到少 → 剩余成债** | U21 | §6 逐字实现 |
| U21 | 库存最多按**数量**，不改成总价值最高/口粮优先/自行配置 | U21 + `tick-wage-feedback-proposal.md` §11.3 | 稳定排序：初始可用数量降序、同量 `CommodityId` 升序 |
| U16/U21 | 再生产权益/已投入资料不进入支付候选 | U16、U21 | 与 D4 占用读口配合；D3 不修 R2 |
| **U22** | **执行优先级**：先修现有 R2 bug；不是对工资三项的裁定，也不授权提交/同步 | U22（2026-10-17，逐字 §0.1） | 不改 D3 工资口径；R2 最小边界见 §6.9 说明 |
| **U23-a** | 所有由未支付工资形成的欠薪债务**利率 = 0**；不得继承 legacy 20‰；**区分工资欠款与普通贷款**；旧工资欠款也不再生息 | U23（2026-10-17，逐字 §0.1） | `DebtTerms.wageArrearsDefault()` 0‰；普通贷款规则不变；旧 `capitalizeArrears` 路径不得再对工资债 20‰ 资本化（§2.9/§6.5） |
| **U23-b** | 任何家户（不限流民/无产者；正收入但不足生存同样适用）只要**从事任何可从事的生产方式都没法换取下一 tick 生存所需**，都可以被裁决为不从业；撤回 `allowIdle` 缺行默认 | U23 | §2.5.1 `SurvivalOutlook.idleEligible` 资格 + §4.4 `idleChosen` 选择；`allowIdle`/`householdLaborChoices` 已撤回，删除其组件、命令、seed |
| **U23-c** | 抵物折价**可以更低**、总之是**可配置项**；注意明确模块边界 | U23 | D1 `BarterDiscountPolicy`（参考价仅 `LOCAL_BID`/`LOCAL_ASK` + 系数，可 <1）+ §6.4 执行；§2.11 模块边界表 |

#### B. 控制方工程默认（明确标注，用户可随时推翻；不是用户裁定）

| # | 工程默认 | 理由/边界 |
|---|---|---|
| E1 | **逐 tick 结算**：每个生产日按本日实际劳动计 due 并支付/抵物/成债 | U19/U20 的逐 tick 演进；不是把整周期工资留到关账日 |
| E2 | 报价是**持久状态**：执行期按 `(ProductionUnitId, ProductionRoleId)` 存 `ProductionWagePosting`（第 34 组件）；**候选级**按 `(plan, role, marketZone)` 存 `ProductionWageDefault`（第 33 组件，§2.2.1） | U19 的反馈需要跨 tick 记忆；default 只服务新进入候选，不是第二份执行报价 |
| E3 | U20 调价公式、动量上限、最小步长、降价延迟/加速均为**可配置参数**；§3.6 给建议值 | U20 只定方向，未指定初值/系数；建议值待仿真标定 |
| E4 | ~~抵物估值固定用当地 `MarketTable.bidPriceOf`（卖方限价）~~ **★ 已被 U23 取代（§6.4 + D1 `BarterDiscountPolicy`）**：参考价类型仅 `LOCAL_BID`/`LOCAL_ASK`，折价系数可配置且可 <1，不再固定 bid×1；仍不是 1:1、不是任意价 | U23 只要求“可更低、可配置”；具体参考价/系数由生产方式定义 |
| E5 | 工资债 `DebtTerms.wageArrearsDefault()`：**0‰ 利率** + 现有 `AFTER_REPAYMENT_ON_CLOSE`/`AVAILABLE_SURPLUS_SHARE`/`NOT_ALLOWED`/`MARK_DEFAULTED`，期限空 | **★ 已被 U23 取代：0‰ 是用户已定，不是工程默认**（§2.9/§6.5）；本金/偿还规则不变；普通贷款不受影响 |
| E6 | **当前有效（R-05）**：同一付款人在**同一 settlement location** 内跨 unit/role/worker 共享一份货币+库存预算；同一 payer 出现 >1 settlement location 时整组 `wage-payer-location-unsupported`，不做部分支付 | 防止同一笔户级余额/库存被两条义务各付一次；户级 `AccountSession` 不允许按地点各读一份 |
| E7 | ~~`allowIdle` 缺行默认 **true**~~ **★ 已被 U23 撤回（§2.5）**：不再有 `allowIdle` 状态/命令/seed；改由“下一 tick 生存条件资格”决定是否可裁决不从业 | 用户 U23 已定资格条件，不是缺行布尔默认 |
| E8 | ~~idle 排序靠 `idle:` < `unit:` 字符串~~ **★ 已被裁决 A2 取代（§4.4）**：idle 是独立控制候选 `IdleOption`/`Plan.idleChosen`，不经过 `queueable()/maxAbsorbable` 过滤；正收益工作先做，零/负收益无补偿工作后 idle 胜；不靠字符串/Map 序 | 若某“零工资”工作有额外可计价收益，其 yield 必须 >0 才会排在 idle 前 |
| E9 | 自动招募（MONEY_WAGE 角色）首期**只限本地市场区**（同 `MarketZoneId`） | W2 最小可行；跨区走既有迁移机制，不在 W2 自动招募 |
| E10 | 角色目标/unit cap **两级封顶**：先角色 target，再 unit 总劳动 cap；多角色按稳定角色 id 序消耗共享 cap | §4.6；单 wage 角色 unit 时退化为 `min(target, maxAbsorbableNonLabor)` |
| E11 | 旧 `FIXED_MONEY_WAGE` 只允许在 C3 seed 中**显式换算**成 quote（`quoteSource=GENERATED`）；运行时不得回读固定额 | U19 不得静默从 0 开始；固定额只能是一次性显式种子转换 |
| E12 | 同一天新生成的工资债，`repayDebts` **同日不再偿还**（day-local 排除集） | 防止“先成债、同 tick 又拿同一笔余额付一次”；见 §6.5 |
| E13 | 多义务付款的稳定序 = `(unit, role, household)` 的规范 id 升序 | 保证确定性与可复现；不沿用 Map 插入序 |
| E14 | `SURVIVAL_IDLE_POLICY` 默认 `PREFER_IDLE_WHEN_SUBSISTENCE_UNMET`（1）；`BEST_EFFORT_WORK`（0）为可配置替代 | 让 U23-b 的 `idleEligible=true` 有实际胜出路径；它不是 per-household `allowIdle` 开关；默认值为控制方工程默认 |

#### C. 开放项（不得替用户裁定）

| # | 开放项 | 当前处理 |
|---|---|---|
| O1 | U20 参数的具体数值、加价/降价是否收敛、是否盈利 | 只给工程建议值；不作收敛/盈利承诺；待仿真标定 |
| O2 | ~~`allowIdle` 的默认值是否应为 true/false~~ | **已关闭（U23）**：撤回 `allowIdle` 缺行默认；改 §2.5 下一 tick 生存条件资格 |
| O3 | 是否把消费/口粮结算移到工资结算之后，以支持同 tick 吃饱 | D3 只写清现状：当前消费在 `executeDay` 之前；工资在之后，不声称同 tick 已吃饱 |
| O4 | 跨市场区自动招募、工资竞价、劳动迁移 | W2 不做；归既有迁移/后续分册 |
| O5 | 旧 `FIXED_MONEY_WAGE` 非名册路径是否删除、何时删除 | D3 只管名册方案；旧路径仍具名存在，不冒充已修 |
| O6 | ~~抵物按 bid 还是其他估值、是否允许地主自定义抵物折价~~ | **已关闭（U23）**：参考价仅 `LOCAL_BID`/`LOCAL_ASK` + 折价系数由 D1 `BarterDiscountPolicy` 配置、可 <1；§6.4 执行 |
| O7 | 多角色共享 unit cap 的优先序（角色 id 升序）是否公平 | D3 选稳定 id 序作最小可行；不是用户裁定 |
| O8 | 工资债的到期周期、违约处置、~~是否计息~~ | **计息部分已关闭（U23）：0‰ 用户已定**；到期周期/违约处置仍为工程默认（§6.5） |
| O9 | Social 的下一 tick 生存需求是否已有逐商品/营养行投影与稳定 need key | 只有聚合值时，W2b 需加只读适配器；不得把聚合数均摊成商品行（§2.5.1） |
| O10 | 当地可达购买价、可购买供给量、币种组合事实 | `MarketTable` 只有 `hasPrice`/bid/ask；可达性与供给由 W2b 市场事实适配器提供；缺事实按不可折算，不按 0/1:1（§2.5.1） |
| O11 | 既有债务/不可用权益只读快照 | 由 D4/权益与占用所有者提供；D3 只消费，不复制资产账（§2.5.1） |

### 0.3 D3 对 D2 的最小修正（已并入 D2，当前仅留指针）

> **状态（2026-10-17 整合）**：旧稿在此列出的五项“待 D2 owner 同步”修正已**全部并入 D2 当前有效正文**（D2 §0.4 局部更正表；落点 §4.3/§5.2/§6.1/§7.1/§7.3/§8.2）。本节**不再保留独立规则正文**，避免两册各有一套互相矛盾的正文。若与 D2 正文冲突，以 D2 §0.4 指向的当前正文为准。

五项指针（仅索引，不重复规则）：
1. 本次 tick 逐 `(household, role)` 实际劳动 → **D2 §6.1**；
2. `MONEY_WAGE` 不强制 roster participant（需求权威 = posting/default）→ **D2 §5.2/§7.1/§10.1**；
3. `candidateHouseholdsOf` 按角色类型分流 → **D2 §4.3**；
4. 工资应收用 posting/default quote 且 **× cycleDays** → **D2 §8.2**；
5. 角色资格失败整实例拒绝只限 roster participant → **D2 §7.3**。

### 0.4 冲突检查

本次未发现 D3 设计与用户原话/五条铁律/模块边界直接冲突。若实现方发现冲突，必须按派单纪律**立即上报控制方**，由控制方转问用户；不得自行改写用户口径或把工程默认冒充裁定。

### 0.5 当前有效口径总表（先读这节；细节见所指向的节）

| # | 主题 | 当前有效口径（一句话） | 所在节 |
|---|---|---|---|
| 1 | 候选/采用时间窗口（A1） | 比较窗口 = `definition.cycleDays()`：产品份额、投入、工资应收/应付、劳动分母全用周期累计；**实际 W3 due 按本次 tick 实际劳动，不乘 cycleDays** | §5.1/§5.2/§5.4、§6.1、D2 §8.2 |
| 2 | 不从业资格（U23-b） | `idleEligible=true` 仅当“所有真正可从事方式的整户组合都无法取得下一 tick 生存所需”；不限流民/无产者，正收入但不足生存同样适用。它是资格，不等于 `idleChosen`；资格条件本身不是开关 | §2.5、§4.4 |
| 3 | idle 选择（A2） | `idleChosen` 由 `EconomyPolicyKey.SURVIVAL_IDLE_POLICY` 决定：默认 `PREFER_IDLE_WHEN_SUBSISTENCE_UNMET` ⇒ `idleEligible=true` 时对可自由分配剩余劳动 `IDLE_CHOSEN`；`BEST_EFFORT_WORK` ⇒ 正收益工作先选，只有零/负收益无补偿工作时 idle 胜出。不靠字符串/Map 序 | §2.5、§4.4 |
| 4 | 候选报价来源（A3） | 实例存在前读 `ProductionWageDefault`（键 `(plan, role, marketZone)`）；选中后同一合法 Revision 建实例 + `ProductionWagePosting`；未选零实例/零 posting | §2.2.1、§2.6、§2.10 |
| 5 | 执行期报价权威（A3） | `ProductionWagePosting` 是执行期**唯一**实际报价权威；default 不是第二份执行报价 | §2.2、§2.3 |
| 6 | 降价延迟（A4） | `satisfiedStreak > decreaseDelayTicks` 才首次降价；rate = `initial + (streak - delay) × accel`，封顶 | §3.4、§3.6、§8.6 例 3 |
| 7 | 抵物折价（U23-c） | 单位折价 = 当地参考价 × `discountPerMille/1000`，系数可 <1；参考价仅 `LOCAL_BID`/`LOCAL_ASK`，由 economy 唯一 helper 经 `hasPrice` + `bidPriceOf`/`askPriceOf` 求值；策略只在 D1 定义面；缺价/0 价/折后 0/缺币种 ⇒ skip 并保留债务 | D1 §2.4、§6.4 |
| 8 | 工资池（A5） | 禁止未到账/预计份额、他户份额、再生产权益抵工资；**已按 D2 分配到 organizer 账户且经 D4 可支付库存读口确认属于活动库存的本人产出仍按 U21 参与抵物** | §5.3、§6.3 |
| 9 | 工资债零息（U23-a） | 所有未支付工资形成的欠薪债务利率 = 0；不得继承 legacy 20‰；区分工资欠款与普通贷款；旧工资欠款也不再生息 | §2.9、§6.5 |
| 10 | 可支付库存读口（B3） | 唯一 `WagePayableInventoryLookup`（`ActorRef payer` + `production.contract.ProductionLocation` → 已扣完冻结/占用/权益/已投入资料的最终可支付量）；W3 不二次扣减；D4 未接 ⇒ 货币先付、库存腿跳过并记 `wage-inventory-scope-unavailable`，余额成债 | §6.9、§6.3 |
| 11 | payer 级地点（B4/R-05） | W3 在任何支付/lookup 前按 payer 聚合；同一 payer >1 settlement location ⇒ 整组 `wage-payer-location-unsupported`，零部分支付；仅单地点组共享一份库存预算；账户权威是 `AccountSession` 户级账 | §6.2、§6.6、§6.9 |
| 12 | W2/W3 中间态（B2） | W2b 独立阶段，`EconomySettlement.settleOneDayInto` 在任何抽料/劳动写回/进度/经验/实际劳动写入之前全 tick 预检；有 MONEY_WAGE 角色而 W3 未启用 ⇒ 整 tick `wage-settlement-not-implemented`，零半状态 | §4.7、§7.2 |
| 13 | 批次顺序（B1） | W2a（default+posting/quote 读口）→ W1（工资前置估值）→ W2b（角色级匹配/idle/U20）→ W3（支付/抵物/债）→ W4（tick/Social）→ W5（独立测试） | §9.1 |
| 14 | 组件号 | 31 基线；32 `productionRosters` / 33 `productionWageDefaults` / 34 `productionWagePostings` / 35 `wageCarryRemainders`；**D2/D3 只新增 32–35，D4 后续 36 `assetOccupancies` / 37 `inventoryReservations`；不把 36 让给 `allowIdle`/`householdLaborChoices`**（两者已撤回，历史项，不列组件） | §2.6 |
| 15 | 模块边界 | production 定规则/配置；economy 判定/估值/结算；Social 生存/劳动/人口；账户原所有者落账；app/Core 只编排 | §2.11 |

**关键数值判据（A1）**：`cycle=100`、日雇 1 小时（1000 mh/日）、单价 2 货币/小时 ⇒ 候选周期劳动 `100,000 mh`、候选工资 `200 货币`（`quote=2000 毫货币/小时`）；实际每 tick due = `2000×1000/1000 = 2000 毫货币 = 2 货币`，**不乘 cycleDays**。

**关键数值判据（A4）**：`decreaseDelayTicks=2`：`satisfiedStreak=2` **不降**；`streak=3` 首次降（`rate=initial+accel`）；`streak=4` ⇒ `rate=initial+2×accel`。

### 0.6 被取代的旧段落登记（逐条；历史正文保留，但不再有效）

| 旧段落 | 处置 | 取代者 |
|---|---|---|
| §2.5 `allowIdle` / `HouseholdLaborChoice`（缺行默认 true） | **整节退役**；组件/命令/seed 一并删除 | §2.5 生存条件资格 |
| 旧 §8.4 P7/P8 的 `allowIdle=true/false` 判据 | **改写**为 §2.5 生存条件 + A2 `IdleOption` 判据（P7/P8 正文已同步） | §8.4 P7/P8 |
| §2.6 第 35 组件 `householdLaborChoices` + `SetHouseholdLaborChoice` + `Seed.householdLaborChoices` | **删除**；组件号 35 让给 `wageCarryRemainders` | §2.6 |
| §4.4 “idle 是 `maxAbsorbableLaborMilli=0` 的 Offer、靠 `"idle:" < "unit:"`” | **作废** | §4.4 `IdleOption` |
| §2.4 carry“第 34 组件” | 改为**第 35 组件** | §2.4/§2.6 |
| §5.1/§5.2/§5.4 只按日工资、未乘 `cycleDays` | **修正为周期窗口** | §0.5 #1 |
| §3.4 `satisfiedStreak >= decreaseDelayTicks` | **修正为 `>`** | §3.4/§8.6 例 3 |
| §5.3 “雇主自己的产品份额不能当工资池”无条件表述 | **收窄**为禁止未到账/预计份额、他户份额、再生产权益 | §5.3/§6.3 |
| §6.3 “活动库存 − 冻结 − 已投入/占用 − 权益”二次扣减写法 | **改为**唯一 `WagePayableInventoryLookup` | §6.9 |
| §6.4 固定 `bidPriceOf` 折价 | **改为** D1 `BarterDiscountPolicy`（参考价仅 `LOCAL_BID`/`LOCAL_ASK` + 系数，可 <1） | D1 §2.4、§6.4 |
| E4/E5/E7/E8、O2/O6/O8（见 §0.2 B/C） | 已逐行标注取代/关闭 | §0.2 |
| §9.1 旧 W1→W2 顺序 | **改为** W2a→W1→W2b→W3→W4→W5 | §9.1 |
| §2.9 “工资债 0‰ 是工程默认（E5）” | **改为用户已定** | §2.9/§6.5 |

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
| 20 | `simos-economy/src/main/java/io/mosire/simos/economy/model/MarketTable.java:181,269,281` | 市场表只有 `hasPrice`/`bidPriceOf`/`askPriceOf`；`hasPrice` 区分“未定价”和“明确 0 价”，两个价格方法在未定价或 0 价时都返回 0；**没有最后成交价事实/读法** | 抵物必须先用 `hasPrice`，再按 `BarterDiscountPolicy.referencePrice` 只选 `LOCAL_BID` → `bidPriceOf` 或 `LOCAL_ASK` → `askPriceOf`；缺价/0 价/折后 0 skip，不得 1:1；未知枚举 fail-closed，不新增市场状态 |
| 21 | `simos-production/src/main/java/io/mosire/simos/production/contract/ProductionReceipt.java:51-67` | `laborContributionsMilli` 是 `Map<HouseholdId, Long>`（本次按户）；`cycleContributions` 是 `Map<HouseholdId, CycleContribution>`（本周期按户） | D2 已改 cycle 为 participant；D3 还要求本次 tick 逐角色，故 §0.3 修正 1 |
| 22 | `simos-production/src/main/java/io/mosire/simos/production/contract/ProductionProcess.java:75-82,178-203` | 实例的 `cycleContributions` 按户；`cycleLaborByHousehold()` 是派生合计 | D2 改 participant 后，W3 读 receipt 的 tick 明细，不读实例累计 |
| 23 | `simos-production/src/main/java/io/mosire/simos/production/contract/CycleContribution.java:50-51` | `CycleContribution(household, laborMilli, efficiencyWeightedLaborMilli)` 无 role | D2 加 role；D3 仍要求另有本次 tick 的 participant 明细 |
| 24 | `simos-production/src/main/java/io/mosire/simos/production/contract/ProductionDayRequest.java:68-75`、`ProductionExecutionRequest.java:44-51` | 执行请求/日步的 `laborContributionsMilli` 是 `Map<HouseholdId, Long>` | D2 改为 `List<ParticipantContribution>`；W1/W2b 的匹配结果必须能落到角色分配 |
| 25 | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/labor/HouseholdLaborCommitment.java:52-60,149-162` | `activity` 是 unit id；id 为 `alloc-<unit>-<group>-<household>`，无 role | D2 加 `productionRole` 并改 id；D3 的逐角色分配依赖它 |
| 26 | `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:310-397` | 当前 **31 个 record 组件**（逐行计数；历史注释里的 40 是旧编号） | D2 加第 32 `productionRosters`；D3 加第 **33 `productionWageDefaults` / 34 `productionWagePostings` / 35 `wageCarryRemainders`**（§2.6）；D4 后续新增 36/37；D2/D3 不新增 36，但 36 不是永久禁用；`householdLaborChoices`/`allowIdle` 已撤回（历史项，不新增） |
| 27 | 同上 `:818-890` | `allocations` 构造期检查：键=id、家户存在、`Σ PRODUCTION ≤ max(0, 时间预算 − min(Σ GOV_SERVICE, 预算))` | W2 写回 allocations 后仍受此不变量；同一户多角色总劳动不得超预算 |
| 28 | `simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java:652` | 日循环 `for (day = from+1; day <= to; day++)` | W4 的插入点必须在这条循环内 |
| 29 | 同上 `:664-668` | ① Social 投影：`updateComposition`、`recomputeLaborBudgets`、`updateNaturalNeeds` | 工资匹配前的劳动预算来自 Social 投影 |
| 30 | 同上 `:681` | ② `productionRuntime.beginTick(day)`，每 tick 恰一次，先衰减经验再冻结本 tick 条件 | W2a 的 posting 快照/W2b 的 U20 观察必须与它同一 tick 身份 |
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
| 41 | `simos-economy/src/test/java/io/mosire/simos/economy/change/EconomyRoundTripTest.java:549` | 当前断言 `EconomyChangeSet` 有 31 个 record 组件 | D2 加 32、D3 加 33 `productionWageDefaults`/34 `productionWagePostings`/35 `wageCarryRemainders` 后由 **W5 独立测试 owner** 更新，不由实现 owner 顺手改 |
| 42 | `simos-production/src/main/java/io/mosire/simos/production/contract/ProductionLocation.java:31` | 已有 `record ProductionLocation(String value)`（非空白；`UNSPECIFIED` 只作防御）；生产实例地点权威是 `ProductionProcess.location()` | W3 的 `WagePayableInventoryLookup` 直接使用该类型；不新增临时/重复地点值类型 |

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

### 2.2 `ProductionWagePosting`（第 34 组件；执行期唯一实际报价权威）

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

enum WageQuoteSource { SEED, EXPLICIT, GENERATED, DEFAULT_MATERIALIZED }
```

字段语义：

- `unit`/`role`：稳定身份；键 == 值内 `(unit, role)`；同一 `(unit, role)` 至多一条 posting。
- `targetLaborMilliPerDay ≥ 0`：地主本 tick 希望吸收的日劳动；`0` 合法（显式关闭岗位、不调价）；**缺失**（既无 posting 也无候选 default）⇒ `wage-target-missing`，不得把“缺”当 0。
- `currency`：必须等于 unit 所在市场区 `legalTender`；不做跨币种换算。
- `wagePerLaborMilli ≥ 0`：**0 是合法报价**（U18），不是缺报价；缺报价用 `Optional.empty()`。
- `quoteSource`：`SEED`（`economy.Seed.wagePostings`）、`EXPLICIT`（`economy.SetProductionWagePosting`）、`GENERATED`（仅 C3 从旧 `FIXED_MONEY_WAGE` 显式换算）、`DEFAULT_MATERIALIZED`（选中候选时由 `ProductionWageDefault` 物化，§2.2.1）；不得是运行时静默兜底。
- 所有权：**default / posting / carry** 状态归 economy；production 不持久化、不读写这些表，只从执行请求拿 allocations/receipt。
- **唯一执行权威（A3）**：已存在实例的匹配/结算只读本表；`ProductionWageDefault`（§2.2.1）只服务实例存在前的候选估价，**不是第二份执行报价**。
- `shortageStreak`/`satisfiedStreak ≥ 0`：连续缺工/连续满足的 tick 计数；见 §3。
- `lastAdjustedTick ≥ 0`：最近一次实际改价的 world day；无改价可为 0。
- `lastAdjustmentDelta`：最近一次改价的带符号差（毫货币/小时；负=降价）；无改价 0。
- `lastObservedMatchedLabor`/`lastObservedEffectiveTarget`/`lastMaxAbsorbable`：上一 tick 末的观测值；只用于读数和下一次调价，不参与当期 due。
- 构造期校验：`targetLaborMilliPerDay ≥ 0`、`wagePerLaborMilli ≥ 0`、streak/观测值 ≥ 0、`currency != null`、`quoteSource != null`；不做跨切片 legalTender 校验（由 `EconomyReferenceValidation`/命令层做）。

### 2.2.1 `ProductionWageDefault`（第 33 组件；候选级工资默认）

> **裁决 A3 落地**：旧稿只按 `(unit, role)` 存 posting，并要求实例已存在才能给 quote ⇒ 新进入候选可能永远因缺价被排除。新增**不依赖 unit** 的候选级 default；它只服务准入初始化与候选估价。

```java
record WageDefaultKey(
    IndustryId plan,
    ProductionRoleId role,
    MarketZoneId marketZone) {}

record ProductionWageDefault(
    IndustryId plan,
    ProductionRoleId role,
    MarketZoneId marketZone,
    long initialWagePerLaborMilli,
    long initialTargetLaborMilliPerDay) {}
```

语义：

- 键 == 值内 `(plan, role, marketZone)`；每个键至多一条；空表合法。
- `initialWagePerLaborMilli ≥ 0`：**0 合法**（U18）；缺由 default 与实例 posting 共同决定 ⇒ `wage-quote-missing`。
- `initialTargetLaborMilliPerDay > 0`：候选“预计用工”目标；**缺目标**（既无 default 也无实例 posting 给 target）⇒ `wage-target-missing`，不得静默 0 当“没有岗位”。
- 币种由 `marketZone.legalTender` 派生（§2.9），default 不单独存可矛盾币种；`role` 必须存在于该 `plan.roles` 且 `payment == MONEY_WAGE`，否则命令/seed 边界 `Rejected`。
- **候选/新进入者**在实例存在前读 default：`quote = initialWagePerLaborMilli`、`target = initialTargetLaborMilliPerDay`，用于 §5 的工资前置估值。
- **选中后同一合法 Revision**：创建 production 实例 + 写 `ProductionWagePosting`（从 default 物化，`quoteSource=DEFAULT_MATERIALIZED`）。**未选候选零实例、零 posting 残留**。
- **运行时不得回读旧 `FIXED_MONEY_WAGE.fixedAmount`**；default 不参与已存在实例的匹配/报价快照/支付，posting 仍是执行期唯一实际报价权威。
- 命令：`economy.SetProductionWageDefault`，载荷 `{ "plan": ..., "marketZone": ..., "roles": [ {role, initialWagePerLaborMilli, initialTargetLaborMilliPerDay}, ... ] }`，**整表替换该 `(plan, marketZone)` 的 default 集合**；`roles: []` 清空（与 §2.10 同形）。
- seed：`economy.Seed.wageDefaults: [ ... ]`，**与 `economy.Seed.wagePostings` 分开**，不混写同一数组。
- 业务拒绝（INFO、零变更）：`wage-default-role-unknown`、`wage-default-role-not-money`、`wage-default-market-zone-missing`、`wage-default-target-missing`、`wage-default-quote-negative`、`wage-default-target-negative`。
- 构造期校验：`initialWagePerLaborMilli ≥ 0`、`initialTargetLaborMilliPerDay > 0`、键/值内 `(plan, role, marketZone)` 一致、非 null。

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
- C2 默认实现永远 `empty()`；**W2a 的真实实现**从 `productionWagePostings` 读（执行期）；W1 只通过该读口取值，不直接读 Map。
- **候选/新进入者另走 default 读口**（实例尚不存在，不能用 unit 键）：
  ```java
  interface ProductionWageDefaultLookup {
    Optional<ProductionWageDefault> defaultFor(
        IndustryId plan, ProductionRoleId role, MarketZoneId zone);
  }
  ```
  选中后由同一合法 Revision 物化为 posting；`ProductionWagePosting` 仍是执行期唯一实际报价权威。
- 读口必须返回**本 tick 锁定的 quote 快照**；U20 调价只写下一 tick 的 posting，不改当前快照。

### 2.4 逐户工资进位余数

新增持久状态（**第 35 组件 `wageCarryRemainders`**；0..999）：

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

### 2.5 不从业资格：下一 tick 生存条件（撤回 `allowIdle`）

> **局部取代（U23-b + 裁决 A2）**：本节**取代**旧 §2.5 `HouseholdLaborChoice`/`allowIdle` 缺行默认、旧 §2.6 第 35 组件 `householdLaborChoices`、旧命令 `economy.SetHouseholdLaborChoice`、旧 seed 键 `householdLaborChoices`、旧开放项 O2、旧工程默认 E7/E8、旧 P7/P8 的 `allowIdle` 判据。**不保留“缺行默认 true/false”语义**。

不从业不是布尔开关，也不是“永远不可能胜出的 idle 候选”。U23-b 把两件事分开：

- **`idleEligible` = 资格**：仅当整户所有可行组合（含“不工作、只吃现有库存”的空组合）都无法取得下一 tick 生存所需时为 `true`；正收入但不足生存同样为 `true`。
- **`idleChosen` = 选择结果**：由 §4.4 的显式控制候选/选择规则决定，独立于资格。`idleEligible=true` **不自动等于** `idleChosen=true`；默认 `PREFER_IDLE_WHEN_SUBSISTENCE_UNMET` 置 idle，`BEST_EFFORT_WORK` 下正收益工作仍可按既有选择/排序被选中。

1. **Social 权威输入**：下一 tick 生存需求（口粮/营养等）、当前营养/劳动能力/人口事实由 **Social** 提供；economy 不重写饥饿/人口公式，不自己编生存需求。
2. **economy 预测**：economy 在本 tick 对家户**所有真正可从事方式**（`MONEY_WAGE` 工资岗位、`PRODUCT_SHARE` 自营/产品份额、其他可行活动）做下一 tick 生存可达性预测，使用当刻报价、本地价格、商品可达/供给事实与该户可执行方案。预测实现必须是 §2.5.1 的纯只读 `SurvivalOutlook`。
3. **整户组合比较**：必须按**整户组合**比较多条可兼做方式；**同一劳动/资源预算不得重复使用**（不能把同一份劳动同时记进两份收入），也不能因为各副业单独不足就误判合计不足。
4. **资格范围**：适用于**任何家户**，不限流民/无产者；**正收入但不足生存同样适用**（例：每 tick 所需 10、最好的可行所得 1，仍得到 `idleEligible=true`）。
5. **“可以被裁决”必须有实际可胜出的选择路径**：资格成立后，由 `EconomyPolicyKey.SURVIVAL_IDLE_POLICY` 决定本 tick 的裁决；它不是 per-household `allowIdle` 开关，也不恢复 `householdLaborChoices` 状态。策略值：
   - `PREFER_IDLE_WHEN_SUBSISTENCE_UNMET`（默认，值 1）：`idleEligible=true` 时，对可自由分配的剩余劳动直接选择 idle，记 `IDLE_ELIGIBLE` + `IDLE_CHOSEN`；正收益工作即使存在也不强制该户劳动，`GOV_SERVICE`/既有不排队配额不取消；
   - `BEST_EFFORT_WORK`（值 0）：`idleEligible=true` 时仍可按 §4.4 选择正收益工作；没有正收益工作时，零/负收益且无其他可计价补偿的工作才可由 idle 胜过。
   两种策略下结果都必须**明确、可读**；不保证从业者必能维生，也不得只放一个永远不可能胜出的 idle 候选。
6. **具名原因**：没有任何真正可从事方式（非空可行替代集合为空/全部被预算或资源限制排除）且空组合仍不足 ⇒ `no-feasible-livelihood`；至少有一个可行方式但整户最好组合仍不足 ⇒ `subsistence-shortfall`。**不得对空集合求最大值或抛异常**。
7. **闲置后果**：仍消耗生活物资、保留实际缺口及 Social 后果；**不产生工资、免费食物、生产经验或新生产权益**；不新增预付/救济/无限借款。
8. **与 U21 一致**：预测可以落空（未收到的钱粮不算已实现消费）；实际缺口仍反馈饱食度与劳动能力；不用历史欠薪/兑付率折信誉。
9. **策略落点**：`SURVIVAL_IDLE_POLICY` 是 economy 的只读政策键（`EconomyPolicyKey`）；production 不读、不定义它；Social 只提供生存/劳动事实；app/Core 只调度。默认值 1 是控制方工程默认，用户可另行配置为 0，不改 U23 的资格条件。

#### 2.5.1 `SurvivalOutlook` 纯只读契约（economy；不新增持久状态）

> **目的**：让实现 owner 能回答“输入是什么、输出什么、何时 `idleEligible=true`、失败怎么报”。它是经济选择前的**纯函数/只读结果**，只读取输入快照，不写状态、不写账户、不生成 ChangeSet、不重写 Social 公式、不选择职业。`allowIdle`/`householdLaborChoices` 已撤回，本契约**不**新增对应状态或开关。

```java
// 纯函数：无副作用；同输入同输出；不读全局可变状态。
interface SurvivalOutlookEvaluator {
  SurvivalOutlook evaluate(SurvivalOutlookRequest request);
}

record SurvivalOutlookRequest(
    HouseholdId household,
    NextTickSubsistenceNeed nextTickNeed,          // Social 权威投影
    SurvivalResourceFacts currentResources,        // 当前可用库存/可花货币；只读
    long laborBudgetMilli,                         // 本户下一 tick 可用劳动预算
    List<LivelihoodAlternative> feasibleAlternatives, // 已按资格/可执行性过滤；可为空
    LocalReachabilityFacts localFacts,             // 当地可达价格/供给事实
    ExistingClaims existingClaims,                 // 既有债务/不可用权益；只读
    int maxCombinations) {}                         // >0；工程默认 4096，可配置；超过即具名失败

record NextTickSubsistenceNeed(long forTick, List<SubsistenceNeedLine> lines) {}

record SubsistenceNeedKey(String value) {}        // Social 稳定键：商品或营养组；非空白

record SubsistenceNeedLine(
    SubsistenceNeedKey key,                        // Social 稳定键：商品或营养组
    CommodityId commodity,                         // 满足该行的具体商品；营养组映射由 Social 给出
    long requiredQuantityMilli) {}

record SurvivalResourceFacts(
    Map<CommodityId, Long> usableInventoryMilli,   // 当前已入库、且已扣除不可用/预留的库存
    Map<CurrencyId, Long> spendableMoneyMilli) {}  // 当前可花货币；不得与预计收入重复

record LivelihoodAlternativeId(String value) {}   // 稳定、非空白、在同一请求内唯一
record ResourceClaimKey(String value) {}           // 稳定资源/占用键；只用于组合内互斥

record LivelihoodAlternative(
    LivelihoodAlternativeId id,
    long laborMilli,
    Set<ResourceClaimKey> resourceClaims,          // 同一资源只能被一个选中方式占用
    long incomeAvailableAtTick,                     // 该替代收入最早可消费的世界 tick；必须显式给出
    Map<CurrencyId, Long> expectedMoneyIncomeMilli, // 仅该 tick 可交付的货币收入；不是周期累计
    Map<CommodityId, Long> expectedInKindMilli,      // 仅该 tick 可交付的实物收入；不是周期累计
    Map<CommodityId, Long> expectedProductShareMilli) {} // 仅该 tick 可交付的产品份额实物；不得先折成钱

record LocalReachabilityFacts(Map<CommodityId, LocalCommodityReachability> byCommodity) {}

record LocalCommodityReachability(
    CurrencyId currency,
    boolean reachable,
    long purchasePriceMilli,                       // 毫货币/商品单位；不是 1:1 代换
    long reachableSupplyMilli) {}                  // 可达供给（毫商品单位）

record DebtClaimFact(
    DebtContractId debt,
    CurrencyId currency,
    long principalMilli,
    long dueTick) {}

record ExistingClaims(
    List<DebtClaimFact> debts,                     // 只读事实，不当作收入/免费资源
    Set<ResourceClaimKey> unavailableResourceClaims) {}

record SurvivalCoverageLine(
    long requiredQuantityMilli,
    long fromInventoryMilli,
    long fromExpectedInKindMilli,
    long fromExpectedProductShareMilli,
    long fromMoneyMilli,
    long coveredQuantityMilli) {}                   // 来源拆分；covered 必须等于四项来源之和且 ≤ required

record SurvivalCoverage(
    Map<SubsistenceNeedKey, Long> coveredQuantityMilli,
    Map<SubsistenceNeedKey, Long> coveragePerMille,
    Map<SubsistenceNeedKey, SurvivalCoverageLine> provenance, // 逐需求行来源，供去重/守恒断言
    long bottleneckCoveragePerMille) {}             // 逐需求行覆盖率的最小值

record SurvivalShortfall(
    Map<SubsistenceNeedKey, Long> quantityMilli,   // max(0, required-covered)，逐需求行
    long worstShortfallPerMille) {}                // 1000 - bottleneckCoveragePerMille

record SurvivalOutlook(
    HouseholdId household,
    NextTickSubsistenceNeed nextTickNeed,
    SurvivalCoverage bestAchievableCoverage,
    SurvivalShortfall shortfall,
    boolean idleEligible,
    SurvivalOutlookReason reason) {}

enum SurvivalOutlookReason {
  SURVIVAL_REACHABLE,
  NO_FEASIBLE_LIVELIHOOD,
  SUBSISTENCE_SHORTFALL
}
```

#### 2.5.1.1 逐需求行覆盖算法（当前有效，R-04）

> 目的：把 `expectedMoneyIncomeMilli` / `expectedInKindMilli` / `expectedProductShareMilli` 真正接入 coverage，且任何一份库存/收入/劳动预算只计一次。以下顺序是**唯一拼写点**，实现不得另写第二套。

1. **选组合（含上限，N-09）**：按 `LivelihoodAlternativeId.value` 稳定升序做确定性 DFS，枚举满足 §2.5.1 第 4 条（劳动预算、资源 claim 两两不相交）的替代子集，空子集也评估。`maxCombinations` 必须是正数（缺省/非法 ⇒ `survival-input-invalid`）；非空子集评估数一旦超过它 ⇒ **ERROR `survival-combination-limit`，不返回截断后的 best/idleEligible，也不写任何状态**。不得静默截断、不得用近似值冒充穷举；提高配置后可重新计算。未被选中的替代收入一律不进入任何池。
2. **时间过滤（N-04）**：只有 `incomeAvailableAtTick <= nextTickNeed.forTick` 的替代，其 `expected*` 才能进入本次覆盖；`expected*` 表示 `incomeAvailableAtTick` 那个 tick 的可交付量，**不是周期累计**。整季结束才分配的 product share（`incomeAvailableAtTick=cycleEnd`）在 `cycleEnd > nextTick` 时覆盖为 0；D2 的 `× cycleDays` 周期预计不得原样塞入本契约。
3. **建当前库存池**：`inventoryLeft = copy(usableInventoryMilli)`。它已是“当前可用”，不得再把 `existingClaims` 当免费库存，也不得把预计收入并进当前库存。
4. **建预计实物池**：只从被选中且通过时间过滤的替代收集 `expectedInKindMilli` 与 `expectedProductShareMilli`，二者表示**该 tick 尚未入库**的增量。同一替代内同一 `CommodityId` 不得同时出现在两个 map（同一实物两处拼写）⇒ ERROR `survival-income-double-count`；若同一商品有多个合法来源，调用方必须先合并为一个值。`expected*` 若已实际入库/已转出，必须在入口前从 map 删除；evaluator 不从 `usableInventoryMilli` 生成或回填 `expected*`。
5. **逐需求行先实物**：按 `SubsistenceNeedKey.value` 稳定升序处理每个 `SubsistenceNeedLine`。每行先取 `inventoryLeft[c]`，再取通过时间过滤的预计实物池（先 `expectedInKindMilli`，后 `expectedProductShareMilli`，仅因确定性；两者互斥），逐次扣减；已入库的同一单位不得再出现在预计池。`requiredQuantityMilli==0` 直接记 1000 覆盖。
6. **再货币**：只对仍 `short > 0` 的需求行做货币腿。先按币种建 `moneyBudget = spendableMoneyMilli + Σ selected(incomeAvailableAtTick <= nextTick).expectedMoneyIncomeMilli`（每个替代/每种币种只加一次）；未来 tick 的货币收入不计入本 tick 预算。货币池不得重复使用。仅当 `localFacts.byCommodity[c]` 明确 `reachable=true && purchasePriceMilli>0 && reachableSupplyMilli>0` 时才折算；可用供给也要逐次扣减。`affordableQ = floor(moneyBudget[currency] × 1000 / purchasePriceMilli)`，`qPay = min(short, affordableQ, supplyLeft[c])`，`cost = ceil(qPay × purchasePriceMilli / 1000)`，再扣 `moneyBudget[currency] -= cost`、`supplyLeft[c] -= qPay`。缺价/不可达/0 价/0 供给 ⇒ 该行货币腿不覆盖，不得当 0 或 1:1。
7. **覆盖率与来源**：`covered = fromInventory + fromExpectedInKind + fromExpectedProductShare + fromMoney`（每项已被 `short` 截断，故恒 `covered ≤ required`）；`provenance` 逐行记录四项来源且 `covered == 四项之和`。`coveragePerMille = required==0 ? 1000 : floor(covered × 1000 / required)`（`covered ≤ required` 故 ≤1000）；`bottleneckCoveragePerMille` 取各行最小值。
8. **去重/已实现边界**：同一商品单位只能被一条需求行、一个来源使用一次；同一劳动/资源预算不得进入两个替代；`expectedMoneyIncomeMilli` 只加预算、不直接算作已买到实物；预计产品份额只作实物池、不得先按价格折成货币；`existingClaims.debts` 不是收入；`unavailableResourceClaims` 不是库存；账户实际已入库/已转出的数量由当前库存与 reservation 反映，不得再由 `expected*` 重算；`expected*` 与 `incomeAvailableAtTick` 不一致/缺失 ⇒ `survival-input-invalid`。
9. **预测不是到手**：上述 coverage 只是下一 tick 可达性预测；实际未收到钱粮时，消费/饱食/劳动能力后果仍按 Social 实际链发生。evaluator 只填 `idleEligible` 与 reason，不填 `idleChosen`、不写状态。

**口径（必须逐条写进实现/测试）：**

1. **需求口径**：Social 给的是逐需求行（商品/营养组）的下一 tick 实物数量；economy 先按需求行算物理覆盖，不把不同商品直接相加成一个“钱数”。`requiredQuantityMilli==0` 的需求行按 1000 覆盖处理；负数拒绝。若 Social 表达可替代的营养组，必须由 Social 给出组键/映射；economy 不自行发明营养换算或替代关系。
2. **库存、预计收入、实物收入去重**：`usableInventoryMilli`、`expectedInKindMilli`、`expectedProductShareMilli` 各自是**一份物理池**；`expected*` 必须只表示尚未入库的增量，已经计入当前库存的产出不得再算一次。同一商品单位只能被一条需求行使用一次；重复商品的需求行按 `SubsistenceNeedKey` 稳定升序处理并逐次扣减，禁止同一库存/同一预计实物被两条需求重复计入。
3. **货币折算**：只有直接覆盖后的剩余需求才进入货币折算。仅当 `localFacts.byCommodity[c]` 明确 `reachable=true && purchasePriceMilli>0 && reachableSupplyMilli>0` 时，才用**同一币种**的 `spendableMoneyMilli` 按 `purchasePriceMilli` 购买并转为该需求行的实物覆盖；每笔同时扣减该币种货币池和可达供给池，二者都只能使用一次，多币种之间不折算/不混用。**未定价/不可达/0 价/无供给不得当 0 元或 1:1**；明确免费配给若存在，必须作为具名实物收入输入，不得靠价格 0 偷造。
4. **同一预算不重复使用**：每个 `LivelihoodAlternative` 只能被组合选一次；组合内 `Σ laborMilli ≤ laborBudgetMilli`；`resourceClaims` 必须两两不相交。不得把同一份劳动/同一块资源同时计入两个收入。
5. **组合与比较**：在 `maxCombinations` 上限内评估所有满足上条且收入可按 §2.5.1.1 时间过滤的替代子集，**包括空子集**（只吃现有库存、不工作）；超限 ⇒ `survival-combination-limit`，不得静默截断。对每个组合按第 1–3 条逐需求行计算覆盖，再按以下稳定序选最好：① `bottleneckCoveragePerMille` 降序；② `Σ coveragePerMille` 降序；③ `Σ laborMilli` 升序；④ 按替代 id 升序的字典序。该排序是工程默认，只为确定性，不是“所有不足者必须闲置”的玩法规则。
6. **`idleEligible` 判真**：`bestAchievableCoverage.bottleneckCoveragePerMille < 1000` 时为 `true`；等于 1000 时为 `false`。`shortfall.quantityMilli[key] = max(0, requiredQuantityMilli - coveredQuantityMilli)`，`shortfall.worstShortfallPerMille = 1000 - bottleneckCoveragePerMille`。`reason`：等于 1000 ⇒ `SURVIVAL_REACHABLE`/`survival-reachable`；不足且没有可行非空替代 ⇒ `NO_FEASIBLE_LIVELIHOOD`/`no-feasible-livelihood`；不足但至少有一个可行非空替代被评估 ⇒ `SUBSISTENCE_SHORTFALL`/`subsistence-shortfall`。空需求集按 1000 覆盖处理，但需由 Social 明确给出“无需求”，不能把缺行当无需求。
7. **空集合/失败**：没有可行组合不是“求最大值”的空集合；仍先评估空子集（现有库存）并返回具名原因。缺 `nextTickNeed`/tick 不符 ⇒ ERROR `survival-need-missing`/`survival-need-stale`；负预算/负数量/重复替代 id/负收入/负 `incomeAvailableAtTick` ⇒ ERROR `survival-input-invalid`；非空子集评估数超过 `maxCombinations` ⇒ ERROR `survival-combination-limit`；`localFacts` 整体缺失 ⇒ ERROR `survival-market-facts-missing`。某商品缺可达事实只表示“该商品不能用货币腿覆盖”，不是把价格当 0，也不是整次判定失败。
8. **预测不是到手**：`expectedMoneyIncomeMilli`/`expectedInKindMilli`/`expectedProductShareMilli` 只是预测；未收到钱粮不抵实际消费，实际缺口仍由 Social 的消费/饱食/劳动能力链产生。`existingClaims.debts` 不得当收入，也不自动豁免；`unavailableResourceClaims` 不得进入可用库存。若某债务在下一 tick 到期且必须预占资源，调用方应把已预占量体现在 `currentResources`，evaluator 不重写债务/还款公式。
9. **选择边界**：本函数只填 `idleEligible` 与生存原因；`idleChosen` 由 §4.4 选择器按 `SURVIVAL_IDLE_POLICY` 填，不能在 evaluator 里暗设。默认 `PREFER_IDLE_WHEN_SUBSISTENCE_UNMET` 时 `idleEligible=true` 会对可自由分配剩余劳动置 `IDLE_CHOSEN`；`BEST_EFFORT_WORK` 时正收益工作（即使不足生存）可被选中，只有零/负收益且无可计价补偿工作时才由 idle 显式胜出。

**开放工程项（不得伪造数值口径）**：
- **O9 Social 下一 tick 需求投影**：需要核对/补齐 Social 是否已提供逐商品/营养行的下一 tick 需求值与稳定 need key；若现有投影只有聚合值，W2b 必须加只读适配器并记录为开放项，不得用总数除商品数。
- **O10 当地可达购买价/供给事实**：`MarketTable` 现有 `hasPrice`/`bidPriceOf`/`askPriceOf`，但不提供“该家户可达的购买价、可购买供给量、币种”这一组合事实；W2b 必须从市场区/市场轮只读投影装配 `LocalReachabilityFacts`，缺该事实时按“不能用货币腿覆盖”处理，不得把价格当 0 或 1:1。
- **O11 既有债务/不可用权益快照**：`ExistingClaims` 的逐项来源与冻结/占用权威仍由 D4/权益与占用所有者提供；W2b 只消费快照，D3 不复制资产账。

### 2.6 `EconomyData` / ChangeSet / Codec / Snapshot / 读口 / 命令 / Seed

在 D2 第 32 个组件 `productionRosters` 之后新增**三个且仅三个**组件（与 D2 §0.4 一致）：**D3 只占 33–35；D4 后续新增 36/37；36 不得让给已撤回的 `householdLaborChoices`/`allowIdle`，但也不是永久禁用**：“无第 36 个”只描述 D2/D3 的写面，不是全库绝对禁令。

| 组件号 | 名称 | 类型 | 说明 |
|---|---|---|---|
| 33 | `productionWageDefaults` | `Map<WageDefaultKey, ProductionWageDefault>` | **候选级**工资默认（键 `(plan, role, marketZone)`）；空表合法；只服务实例存在前的候选估价/物化（§2.2.1） |
| 34 | `productionWagePostings` | `Map<WagePostingKey, ProductionWagePosting>` | **执行期唯一实际报价权威**（键 `(unit, role)`）；空表合法 |
| 35 | `wageCarryRemainders` | `Map<WageCarryKey, Long>` | 逐 `(unit, role, worker)` 进位余数；值 0..999；空表=全 0 |

必须同步的面（**W2a** 落 33/34 与读口，**W2b** 落 35 与匹配写回；W5 测试 owner 另测）：

1. `EconomyData` 新增上述三个 record 组件；构造期检查键==值内 id、carry ∈ [0,999]、非 null、default 键 `(plan, role, marketZone)` 一致。
2. `EconomyChangeSet` 新增三个 `FieldDelta`（`productionWageDefaults`/`productionWagePostings`/`wageCarryRemainders`）；`between`/`apply`/`applyTo`/`isEmpty` 全接。
3. `EconomyCodec` 读写三组件；同一版本写出的 JSON 必须逐字段往返；缺键/未知键按 Alpha 口径 fail-closed。
4. `EconomySnapshot`/`EconomySnapshots` 自动携带 `EconomyData`；若实现有显式字段复制清单，必须补三组件。
5. `EconomyDayView` 增加只读访问器：`productionWageDefaults()`、`productionWagePostings()`、`wageCarryRemainders()`；`EconomyData` 直接实现，`WorkingDayView` 工作副本优先。
6. `EconomyBoundaryView` 增加只读访问器，供 app/GM/readout 使用。
7. `EconomyReferenceValidation` 增加 default/posting/roster/unit/role/currency/legalTender 跨片校验；`MONEY_WAGE ⇒ share=0` 仍按 D2。
8. 命令：`economy.SetProductionWageDefault`（§2.10）、`economy.SetProductionWagePosting`（§2.7）；seed：`economy.Seed.wageDefaults`（§2.10）、`economy.Seed.wagePostings`（§2.8）。**default 与 posting 的 seed/命令分开，不混写同一数组**。`householdLaborChoices` 已撤回，**没有**对应命令/seed 键。
9. `ProductionWageQuoteLookup` 的真实实现从 `productionWagePostings` 读（执行期）；`ProductionWageDefaultLookup` 从 `productionWageDefaults` 读（候选）；不得另建第二份 quote/default 表。
10. `EconomyRoundTripTest` 的组件计数/JSON 用例由 **W5 独立测试 owner** 更新为 32→35 的当前形状；实现 owner 不改断言。
11. `SURVIVAL_IDLE_POLICY` 走既有 `EconomyPolicyKey`/`economy.SetEconomyPolicy` 政策面，**不是**第四个 EconomyData 组件、不是 `productionRosters` 名册字段、不是 per-household `allowIdle`；W2b 只新增键与读取点。

### 2.6.1 `economy.SetProductionRoster` / `economy.Seed.productionRosters` 与 D3 的衔接

D3 不改 D2 的名册载荷形状，只把工资岗位的边界接上：

- `economy.SetProductionRoster` 仍是**整表替换**（D2 §5.4）：`participants: []` 清空该 unit 名册；不做 patch；`plan` 由 production 实例读出，不由载荷给。
- `roster-locked` 语义不变：production 实例 `progressDays > 0` 时不得替换名册；名册周期内不变，保证产品份额/贡献可复算。
- **工资角色例外**：`MONEY_WAGE` 角色可以没有 roster participant；执行期需求由 `ProductionWagePosting.targetLaborMilliPerDay` 表达，新进入候选由 `ProductionWageDefault`（§2.2.1）表达，实际 worker 由每 tick allocations/receipt 决定。`SetProductionRoster` 不得为工资角色伪造 placeholder household，也不得把工资工人写进第二份“名册 worker”权威。
- `economy.Seed.productionRosters` 仍只写已确认 participant；**候选 default** 由 `economy.Seed.wageDefaults`（§2.10）、**执行期 posting** 由 `economy.Seed.wagePostings`（§2.8）另表给，三表同批写入、互不混写。
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
- `unit` 必须存在；`role` 必须存在于 plan 的 `roles` 且 `payment == MONEY_WAGE`。**实例不存在 ⇒ `Rejected("unit-unknown")`，不得隐式创建实例**；新进入候选先走 §2.2.1 default，选中后再由同一合法 Revision 建实例 + posting。
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
6. **本 seed 只写执行期 posting**；候选级 default 走 `economy.Seed.wageDefaults`（§2.10），两表分开；未建实例的新进入候选**不在此写 posting**。

### 2.9 币种与工资债条款

- **币种**：`posting.currency` 必须等于 unit 所在市场区 `legalTender`（`MarketZoneBook.zoneOfHex` → `MarketZone.legalTender`）。不一致 ⇒ `wage-currency-mismatch`，不做跨币种换算；不得用 ask/bid 或官方汇率折成另一种币。
- **工资债条款（U23 用户已定：0‰；结构为工程默认）**：新增工厂

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
- **区分工资欠款与普通贷款（U23 用户已定 + R-11）**：零息只适用于 `DebtSource.WAGE_ARREARS`；工资债必须同时有 `WAGE_ARREARS` 来源与 `wageArrearsDefault()`，普通借贷保留各自 `DebtTerms` 并写 `ORDINARY_LOAN`。仅凭 `TransferReason.WAGE_PAYMENT` 或 terms 不能替代来源字段；不得把普通贷款一并改零息。
- **旧工资欠款也不再生息**：新链产生的工资债一律 `WAGE_ARREARS + wageArrearsDefault()`；同一版本内 `capitalizeArrears` 遇到 `Arrear.Kind.WAGE` 即写 `WAGE_ARREARS + 0‰`。无来源旧债不猜测（Alpha 重建），`LEGACY_UNKNOWN` 不冒充工资债。
- **不得用 legacy 20‰ 冒充“工资债默认”**：`DebtTerms.legacyDefault()` 只服务普通贷款；工资债套用它 ⇒ `wage-debt-terms-unsupported`。

#### 2.9.1 债务来源 `DebtSource`（R-11，当前有效）

> **现状**：`DebtContract` 当前只有 `(debtor, creditor, unit, terms, principal, openedDay, lastInterestDay, dueCycle, status)`，没有“工资欠款 vs 普通贷款”的可持久来源；旧 `capitalizeArrears` 对所有 `Arrear.Kind` 都取 `DebtTerms.legacyDefault()`（20‰）。仅靠 terms 不能可靠识别工资债（普通贷款也可能是 0‰），因此增加来源字段。

```java
// simos-economy-api/.../api/debt/DebtSource.java
enum DebtSource {
  WAGE_ARREARS,   // 未付工资形成；利率必须 0‰
  RENT_ARREARS,   // 生产结算的欠租；保留既有条款，不是工资
  ORDINARY_LOAN,  // 普通借贷；保留各自原 terms
  LEGACY_UNKNOWN  // 仅历史/诊断；不得据此冒充工资债或自动改 0‰
}

// DebtContract 新增可持久组件（位置在 terms 后；所有 withX/构造点逐值保留）
record DebtContract(..., DebtTerms terms, DebtSource source, long principal, ...) {}
```

- **持久面**：`source` 进 `debtContracts` 表值、`EconomyChangeSet` 的债务表 diff、`EconomyCodec`、snapshot 与往返守卫；`apply(changeSet, base)` 必须逐字段重建 source。旧档按 Alpha **重建**，不写迁移/键归一；读不到 source 的新版本档 fail-closed `debt-source-missing`，**不猜测**。
- **身份与合并**：`DebtContractId.idOf(debtor, creditor, unit, terms)` 的既有四元组不变；`source` 是合同值字段但不是 id 新段。`DebtContractBook.upsert` 增加 source 入参：同 id 已有合同且 source 不同 ⇒ 具名 `debt-source-mismatch` fail-closed，不得把工资债和普通贷款静默并成一条。
- **写入映射（当前版本统一打 source）**：
  - W3 未付工资成债 ⇒ `DebtSource.WAGE_ARREARS` + `DebtTerms.wageArrearsDefault()`（0‰）；
  - `capitalizeArrears` 的 `ProductionLedger.Arrear.Kind.WAGE` ⇒ `WAGE_ARREARS` + `wageArrearsDefault()`（0‰），**旧工资路径在本版本内即停止 20‰**；
  - `Arrear.Kind.RENT` ⇒ `RENT_ARREARS` + 既有 `legacyDefault()`（20‰）——不是工资，不误改；
  - `UnitBorrowHandler`/市场延期支付/铸币等普通借贷 ⇒ `ORDINARY_LOAN` + 各自原 terms，**不因 U23 改利率**；
  - `LEGACY_UNKNOWN` 只可出现在历史/诊断读面；当前写路径不得新写；不得自动套 `wageArrearsDefault()`，也不得把未知来源当普通贷款去覆盖原 terms。
- **`wageArrearsDefault()`** 仍是唯一工资债工厂（0‰），但**必须同时有 `WAGE_ARREARS` 来源**才能证明它是工资债；只有 terms 匹配不足以判定。
- **旧工资欠款迁移口径**：Alpha 不兼容旧档，重建世界；同一版本内旧 `FIXED_MONEY_WAGE` 路径若仍进入 `capitalizeArrears`，其 `Arrear.Kind.WAGE` 已按上一条走 0‰。没有来源的旧债务不猜测、不冒充工资债；用户若要求保留旧档再另立迁移 spec。
- **全部当前写入点/owner（N-05，回源码核过）**：增加必填 source 后，下列调用点必须逐一改齐并入 W3 owner（或按共享 economy 文件交接顺序串行）：
  - `simos-economy/src/main/java/io/mosire/simos/economy/spi/UnitBorrowHandler.java:170` ⇒ 普通借入 `ORDINARY_LOAN`，保原 terms；
  - `simos-economy/src/main/java/io/mosire/simos/economy/time/MoneyMarketSettlement.java:640` ⇒ 货币市场借款 `ORDINARY_LOAN`；
  - `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java:7827` ⇒ 市场延期支付/欠款 `ORDINARY_LOAN`；
  - `simos-economy/src/main/java/io/mosire/simos/economy/time/MintSettlement.java:607` ⇒ 铸币/相关债 `ORDINARY_LOAN`（若该路径实际生成债务）；
  - `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java:7255` ⇒ `capitalizeArrears` 的 WAGE/RENT source 映射（WAGE=0‰，RENT=旧条款）；
  - `ModeMigrationSettlement.java:1700`、`LotMigrationBook.java:279` ⇒ 迁移/合户/重放必须**逐值保留原 source**，不得默认成 `ORDINARY_LOAN` 或 `LEGACY_UNKNOWN`；
  - `EconomyPayloads.java:1795`（及 `EconomySeedHandler`）⇒ seed/JSON 必须显式读写 `source`；普通贷款 seed 明确 `ORDINARY_LOAN`，缺 source ⇒ `debt-source-missing`，不得静默给 `LEGACY_UNKNOWN`/工资债；
  - `EconomyCodec`/`EconomyChangeSet`/`DebtContractBook.upsert` ⇒ source 必填、进往返与债务表 diff；`DebtContractBook.upsert` 全部调用点必须 source 非空且与既有同 id 合同一致。
- **负向（N-05）**：任一 `DebtContractBook.upsert`/`new DebtContract` 漏 source ⇒ 编译红；普通贷款被写成 `WAGE_ARREARS`、迁移被默认成 `ORDINARY_LOAN`、seed 缺 source 走 `LEGACY_UNKNOWN` ⇒ P29b 红。

### 2.10 `economy.SetProductionWageDefault` / `economy.Seed.wageDefaults`

候选级 default 的命令与 seed **与 posting 分开**（A3）：

```json
{
  "plan": "farm",
  "marketZone": "zone-0_0",
  "roles": [
    {
      "role": "hired-hand",
      "initialWagePerLaborMilli": 1000,
      "initialTargetLaborMilliPerDay": 5000
    }
  ]
}
```

语义：

- **整表替换该 `(plan, marketZone)` 的 default 集合**；`roles: []` = 清空该 plan×zone 的 default；不做 patch。
- `plan` 必须已注册；`role` 必须存在于 `plan.roles` 且 `payment == MONEY_WAGE`；`marketZone` 必须存在。
- `initialWagePerLaborMilli ≥ 0`（0 合法，U18）；`initialTargetLaborMilliPerDay > 0`；缺目标 ⇒ `Rejected("wage-default-target-missing")`，不得静默 0。
- 币种由 `marketZone.legalTender` 派生；default 不存可矛盾币种。
- 业务拒绝（INFO、零变更）：`wage-default-plan-unknown`、`wage-default-role-unknown`、`wage-default-role-not-money`、`wage-default-market-zone-missing`、`wage-default-target-missing`、`wage-default-quote-negative`、`wage-default-target-negative`。
- 成功：`HandlerOutcome.Applied(EconomyChangeSet)`，INFO 记录 plan/zone/roles/replaced。
- **seed**：`economy.Seed.wageDefaults: [ { plan, marketZone, roles: [ ... ] } ]`，与 `economy.Seed.wagePostings` 分开；创世先确保 `plan` 已注册（同批 production seed），缺 role/目标 ⇒ 具名失败，不静默补。
- **运行时不得把 default 当第二份执行报价**：已存在实例只读 posting；default 只服务候选估价与选中物化。

### 2.11 模块边界（U23 §5 逐字口径）

| 内容 | 权威与责任 |
|---|---|
| 角色、配方、经验/资格、产品份额、支付物规则、可配置的抵物折价策略/参数定义 | **production** 的生产方式定义面；只声明规则和纯配置，不读取市场、钱包或他户状态 |
| 当前市场价格、报价/调价、候选收入成本、生存目标经济可达性、不从业资格/选择、统一资源分配、抵物估值与工资/欠薪结算 | **economy**；读取 production 规则、Social 生存需求和原所有者资源事实，计算与变更本域状态 |
| 生存需求/人口结构、饱食度、饥饿对劳动能力的实际影响、人口后果 | **Social**；不读取市场报价来替 economy 选职业，不由 economy 重写饥饿/人口公式 |
| 实际货币和商品账户余额、冻结、真实转移落账 | **actor 等原账户所有者**；economy 经既有转移与 ChangeSet 路径请求应用，不能直接改其 Map |
| 再生产权益、占用和最终可支付库存事实 | 既有权益/占用所有者的唯一权威及只读契约；D4 明确排除量，W3 不二次扣减、不回退整账户余额 |
| 组合输入、时序与同 Revision 原子提交 | **app/Core**；只调度，不实现生存判定、工资定价、抵物折价或利息公式 |

折价的**规则定义**在 production，调用市场参考价并算出本次付款价值的**执行**在 economy；production 不因此产生对 economy 实现的反向依赖。值类型继续放合法契约面，economy-api 不反向引用 production 类型。

**D3 对 production contract 的只读依赖**：`WagePayableInventoryLookup` 的结算地点形参固定使用已存在的 `io.mosire.simos.production.contract.ProductionLocation`；不新增临时地点值类型，也不在 economy-api 复制地点值类型。economy 只读 contract，不反向写 production 状态。

不从业是经济活动的判定结果：`idleEligible` 由 §2.5.1 只读预测给出，`idleChosen` 由 §4.4 选择器显式给出；二者都不授权 production 改家户人口/阶层，也不授权 economy 改其他领域持久状态。

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
    if satisfiedStreak > decreaseDelayTicks:          // A4：满 delay 的下一 tick 才首次降价
        rate = min(
            decreaseRateCapPerMille,
            decreaseInitialRatePerMille
              + (satisfiedStreak - decreaseDelayTicks) × decreaseRateAccelPerMille)
        step = max(1, floor(wagePerLaborMilli × rate / 1000))
        wage' = max(0, wagePerLaborMilli - step)
    else:
        wage' = wagePerLaborMilli
```

- **边界（A4，取代旧 `>=`）**：`satisfiedStreak > decreaseDelayTicks` 才首次降价；`streak == delay` 时**不降**。例：`delay=2` ⇒ `streak=2` 不降，`streak=3` 首次降（`rate = initial + 1×accel`），`streak=4` ⇒ `rate = initial + 2×accel`。
- 降价率从 `decreaseInitialRatePerMille` 起，随 `satisfiedStreak` 增加，封顶 `decreaseRateCapPerMille`；`streak` 只在 `>` 分支参与 `(streak - delay)`，不出现负值。
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
| `decreaseDelayTicks` | 3 | **连续满足 tick 数必须 `>` 该值才首次降价**（`=3` 不降，第 4 个满足 tick 首次降）；A4 判据 |
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
  - `MONEY_WAGE` 角色：执行期 posting（候选/新进入用 default）+ 本地市场区 + 资格/账户规则（§4.5），不读 roster worker。
- `maxAbsorbableLaborMilli` = 该角色本 tick 可吸收量，受两级 cap（§4.6）约束。
- `netPerLaborScaled` = 该角色每单位劳动的预期净收益（W1/W2b 共用同一估值口径）。
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
- **idle 不在此过滤之内**：`IdleOption` 是独立控制候选（§4.4），不受 `queueable()`/`maxAbsorbableLaborMilli > 0` 约束；删除 `IDLE_EXPECTED_NON_POSITIVE` 后，“空闲”只能由 `IdleOption` 显式胜出，不能靠暗判。

### 4.4 不从业：独立控制候选 `IdleOption`（取代旧“idle Offer”）

> **局部取代（U23-b + 裁决 A2）**：旧写法“idle 是 `maxAbsorbableLaborMilli=0` 的普通生产 Offer、靠 `"idle:" < "unit:"` 字典序保证排序”**作废**。idle 不是生产 Offer，不经过 `Offer.queueable()`/`maxAbsorbableLaborMilli > 0` 过滤。

- **独立控制候选**：`IdleOption`（或等价 `Plan.idleChosen` 分支）由 `plan()` 在**生产 Offer 之外**显式构造；不进入 `queueable()`/`maxAbsorbableLaborMilli` 过滤，不占 unit cap，也不作为 offer 参与 `maxAbsorbable` 汇总。`IdleOption.idleEligible` 直接取 §2.5.1 `SurvivalOutlook.idleEligible`，它是资格读数，不是“自动选中”或“禁止一切工作”的开关。
- **两个词不混写**：`idleEligible` 只表示 U23-b 的生存资格；`idleChosen`/`IDLE_CHOSEN` 只表示本 tick 选择器最终选择了 idle。资格为 true 不自动写 `idleChosen=true`；资格为 false 也不得用“必须工作”硬塞零/负收益工作。
- **策略分支（显式胜负，不靠字符串）**：
  0. `SURVIVAL_IDLE_POLICY` 只作用于 `idleEligible=true` 且存在可自由分配剩余劳动的家户；`GOV_SERVICE`/既有不排队配额先整额保留，不参与 idle 预算。
  1. `idleEligible=false`：不构造可胜出的 idle；正/零/负收益工作按 `ActivitySelector` 正常排序；剩余劳动记 `UNALLOCATED_LABOR`。
  2. `idleEligible=true` 且策略=`PREFER_IDLE_WHEN_SUBSISTENCE_UNMET`（默认）：可自由分配劳动全部记 idle，`idleChosen=true`/`IDLE_CHOSEN`；不再授予正/零/负收益工作，正收益候选进 `IDLE_FOREGONE_POSITIVE` 读数，不得静默；`GOV_SERVICE`/不排队配额保留。
  3. `idleEligible=true` 且策略=`BEST_EFFORT_WORK`：正收益工作按 `ActivitySelector` 先分配；选中至少一个正收益工作 ⇒ `idleChosen=false`，剩余劳动 `UNALLOCATED_LABOR`；没有正收益工作被选中时，零/负收益且无额外可计价补偿的工作才可被 idle 击败，idle 记 `IDLE_CHOSEN`。
  4. 零/负收益工作若有额外可计价补偿，其 offer yield 必须已 >0，按正收益处理；W2b 不把未定价经验/身份收益偷算成货币收益。
  5. idle 胜出后停止给后续更低 yield 的工作授予劳动；但**不得取消**已保留的 `GOV_SERVICE` 整额与既有不排队配额。
- **排序保证**：胜负由上述显式策略比较决定；`activityId = "idle:" + household.value()` 只用于稳定读数/破平，**不是**全链路正确性保证；不得依赖 `HashMap` 迭代序。
- **选中后**：当 idle 作为本轮最终选择时，`idleChosen=true`、`outcome=IDLE_CHOSEN`；`idleLaborMilli` 是**扣除已保留 GOV_SERVICE/不排队配额后的剩余预算**，`allocatedLaborMilli` 只含已保留部分；不产生工资、产品份额、经验、免费食物或救济；生活需求/缺口/人口后果照常（§2.5 第 7 条）。若只是正收益工作后剩余劳动无处可投，则 `outcome=UNALLOCATED_LABOR`，不是 `IDLE_CHOSEN`。
- **资格读数**：`idleEligible=true` 但策略为 `BEST_EFFORT_WORK` 并选中正收益工作时，输出 `IDLE_ELIGIBLE` + 实际工作结果（可同时保留 `subsistence-shortfall` 的缺口读数），不得断言 `IDLE_CHOSEN`；`idleEligible=true` 且 idle 最终胜出时，输出 `IDLE_ELIGIBLE` + `IDLE_CHOSEN` + 生存原因。
- **有较好工作时**：只有 `BEST_EFFORT_WORK` 策略先分配该工作；不得把“有不从业资格”实现成“一律停工”，也不得把默认 `PREFER_IDLE_WHEN_SUBSISTENCE_UNMET` 误写成强制所有家户闲置。
- **零可行候选**：`SurvivalOutlook`/`plan()` 返回具名 `no-feasible-livelihood`/`subsistence-shortfall`，不得对空集合求最大值；idle 是否最终选中仍按策略分支决定。

> 边界：如果某“零工资”工作有额外可计价收益（如 W1 估值中的产品份额/实物），其 offer yield 必须 >0，才会排在 idle 前；W2b 不把未定价的经验/身份收益偷算成货币收益。

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

### 4.7 W2b 首期能力边界（W2a 先行交付）

**能做的**：

- W2a 负责持久化 33 `productionWageDefaults` / 34 `productionWagePostings` 与 quote/default 读口；W2b 负责持久化 35 `wageCarryRemainders`；D4 后续新增 36 `assetOccupancies` / 37 `inventoryReservations`；`householdLaborChoices`/`allowIdle` 已撤回，36 不得让给它们；D2/D3 不新增 36 不等于全库禁止 36；
- 读取/校验 quote，执行 U20 调价（只写下一 tick）；
- 角色级 offer + `IdleOption` 独立控制候选 + 去除非正收益过滤；
- 按家户预算、角色目标、unit cap 写回 allocations；
- 产出具名 readout/日志；缺报价、无账户、跨区等稳定拒绝/跳过。

**不能做的**：

- 工资直付/抵物/成债（W3）；
- 在不接 W3 的情况下执行含 MONEY_WAGE 角色的生产：必须 `wage-settlement-not-implemented`（D2 §8.4 边界）。**B2 钉死位置**：真实 `EconomySettlement.settleOneDayInto` 必须在**任何抽料、劳动匹配写回、进度、经验、实际劳动写入之前**做**全 tick 预检**；只要存在 `MONEY_WAGE` 角色而 W3 未启用 ⇒ **整 tick 具名拒绝** `wage-settlement-not-implemented`，不产生 allocations/进度/经验/实际劳动半状态。W2b 的匹配能力只能在显式只读/dry-run 入口验证；W3 接通后改为按实例业务拒绝（坏 posting 等），其他 unit 可继续；
- 跨市场区自动招募/工资竞价/劳动迁移；
- 产品份额分配（D2 C2 已做）；
- 资产占用/R2（D4）；
- 自动招募 PRODUCT_SHARE 角色（仍由 D2 roster 显式建立）。

**自动招募范围**：W2b 首期只限**同一 `MarketZoneId`**的本地家户；跨区候选一律 `wage-worker-location-mismatch`，由既有迁移机制另案处理。

### 4.8 匹配失败/跳过词表（与 §8.1 合并使用）

- 候选 skip：`wage-worker-location-mismatch`、`wage-worker-account-missing`、`wage-organizer-self-employment`、`role-qualification:<role>:<household>:<reason>`。
- posting/default/命令错误：`wage-quote-missing`、`wage-target-missing`、`wage-currency-mismatch`、`wage-posting-role-unknown`、`wage-role-not-money`、`wage-target-negative`、`wage-market-zone-missing`、`wage-posting-locked`、`wage-quote-overflow`、`wage-default-role-unknown`、`wage-default-role-not-money`、`wage-default-market-zone-missing`、`wage-default-target-missing`。
- W2 边界：`wage-settlement-not-implemented`。
- 所有拒绝必须带稳定 reason；不得用异常消息代替 reason。

## §5 工资前置（W1）

### 5.1 雇主候选：全部应付工资进入成本

对每个候选经营单位，**先做 W1 共享 payer 级地点预检**（§6.9.1）：把该候选当前涉及的 settlement location 去重；同一 payer >1 location ⇒ 候选不可行/`wage-payer-location-unsupported`，**不调用 `WagePayableInventoryLookup`、不算工资成本/候选净额**；通过后才进入下列成本计算。

```text
targetLaborMilliPerDay(role)
  = 候选 default.initialTargetLaborMilliPerDay（实例存在前）
    或 posting.targetLaborMilliPerDay（执行期，唯一执行报价权威）

expectedLaborMilliPerCycle(role)
  = min(targetLaborMilliPerDay(role), maxAbsorbableNonLaborForRole)
    × definition.cycleDays()                        // A1：候选/采用比较窗口

expectedWageCostMilli
  = Σ_{role ∈ MONEY_WAGE} floor(
        quoteSource(role).wagePerLaborMilli × expectedLaborMilliPerCycle(role) / 1000)
```

规则：

- **全部应付工资**都进成本；不得只计当前现金能付的部分，不得因现金不足让工资成本消失。
- **周期窗口（A1）**：`expectedWageCostMilli` 是**周期累计**金额（`× cycleDays`），与产品份额值、投入负担、劳动分母同窗口；**实际 W3 due 按本次 tick 实际劳动，不乘 `cycleDays`**。
- `expectedLaborMilliPerCycle(role)` 用 default/posting target 与共享 unit cap；**预计值不替代实际匹配**；实际 due 仍按 receipt 的本次 tick 实际劳动。
- 多 wage 角色按 §4.6 的稳定角色 id 序消耗共享 unit cap，不重复给每个角色整份 cap。
- **quote 来源（A3）**：实例存在前读 `ProductionWageDefault`（键 `(plan, role, marketZone)`，§2.2.1）；执行期读 `ProductionWagePosting`。缺 quote ⇒ `wage-quote-missing`；缺目标 ⇒ `wage-target-missing`；不得按 0 计，运行时不得回读旧 `FIXED_MONEY_WAGE.fixedAmount`。
- 显式 0 quote：`expectedWageCost=0`，但候选仍保留 `WAGE_QUOTE_ZERO` 读数；不因此生成“缺工资规则”。
- **A1 数值判据**：`cycle=100`、日雇 1 小时（1000 mh/日）、单价 2 货币/小时（`quote=2000`）⇒ `expectedLaborMilliPerCycle=100,000`、`expectedWageCost=200,000 毫货币=200 货币`；实际每 tick due=`2000×1000/1000=2000 毫货币=2 货币`。若候选只扣 2 而非 200 ⇒ 判据红。

### 5.2 雇工候选收入

对每个可成为 MONEY_WAGE worker 的家户，**先做 W2b 共享 payer 级地点预检**（§6.9.1）：把该匹配候选当前涉及的 settlement location 去重；同一 payer >1 location ⇒ 该候选 skip/`wage-payer-location-unsupported`，**不调用 lookup、不进入排序、不写 allocations**；通过后才进入下列收入计算。

```text
expectedWorkableLaborMilliPerCycle
  = min(该户每日可分配劳动,
        targetLaborMilliPerDay(role),
        该角色在共享 unit cap 下的日容量)
    × definition.cycleDays()                          // A1：候选/采用比较窗口
wageIncomeMilli
  = floor(quoteSource.wagePerLaborMilli × expectedWorkableLaborMilliPerCycle / 1000)
```

- 不能再用 `FIXED_MONEY_WAGE.fixedAmount`。
- **周期窗口（A1）**：雇工候选收入也是**周期累计**（`× cycleDays`），与产品份额、投入、劳动分母同窗口；实际 W3 due 按本次 tick 实际劳动、不乘 `cycleDays`。
- **quote 来源（A3）**：新进入候选读 `ProductionWageDefault`，执行期读 `ProductionWagePosting`；缺 quote ⇒ `wage-quote-missing`，缺目标 ⇒ `wage-target-missing`，不得当 0。
- 显式 0 quote ⇒ income=0，仍可作为候选（若 §2.5 判定其所有可行组合不足生存，或该工作有额外可计价收益）；这不是“缺报价”。
- 预计 income 不是“承诺已到手”；实际支付/欠薪仍按 W3。
- 历史欠薪/兑付率不得进入该 income 或候选排序（U21）。

### 5.3 产品份额、经验与工资池

- **工资是独立义务**：工资不是产品份额、也不是经验/身份收益；**预计/未到账**的产品份额不得当工资池。
- **A5 收窄（取代旧无条件表述）**：禁止 ① 尚未到账/预计的产品份额当作可用现金；② 其他 participant 的份额拿来付 organizer 工资；③ 再生产权益/已投入资料抵工资。**不禁止**：已按 D2 分配到 organizer 账户、且经 §6.9 `WagePayableInventoryLookup`（D4 可支付库存读口）确认属于活动库存的**本人产出**——来源是本人产品**不改变**它作为活动库存的支付资格，仍按 U21 参与抵物。
- 工资成本不得因现金不足而消失；现金不足在 W3 变成债务，不是 W1 的负成本/折扣。
- 预计产品份额/预计工资应收仍按 D2 §8.2 分户计算；D3 不重写产品份额公式。

### 5.4 W1 要改的 `Prospect` 字段/理由词（最小）

现状 `Prospect`（`ExpectedProfitBook.java:133-152`）已有 `laborCostMilli`、`rentMilli`、`netMilli`、`laborNeedMilli`、`reason`。W1 最小改动：

1. **新增 `long expectedWageCostMilli`**（或等价字段）：仅 MONEY_WAGE 角色的全部预计应付，**周期窗口**（`× cycleDays`，A1）；`netMilli = revenue − inputCost − subsistenceLaborCost − expectedWageCostMilli − rentMilli`，因此 `netMilli` 也是周期净额、不是单 tick 净额。
2. **保留 `laborCostMilli` 语义为“生存/非工资劳动折算”**，或明确把它拆成 `subsistenceLaborCostMilli + expectedWageCostMilli`；不得再把工资和生存折算混成一个无法解释的数。
3. **wageProspect**：把 `income` 改为 quote 收入（§5.2），不再读 `FIXED_MONEY_WAGE.fixedAmount`；`netMilli` 仍是该户预计工资收入。
4. **理由词**：
   - 删除/替换 `NO_WAGE_RULE` 的模糊语义；缺 quote ⇒ `WAGE_QUOTE_MISSING`；缺目标 ⇒ `WAGE_TARGET_MISSING`；显式 0 ⇒ `WAGE_QUOTE_ZERO`；正常 ⇒ 可保留 `WAGE_POSITION`。
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
- **B4 首期前置（当前有效，R-05/N-02）**：W1/W2b/W3 共享 §6.9.1 `WagePayerLocationPrecheck`。W3 在任何支付、库存查询或债务写入**之前**，先按 **payer** 聚合本次 tick 的全部工资义务；若同一 payer 出现 **>1 个不同 settlement location**，整组具名业务拒绝 `wage-payer-location-unsupported`，**不做任何部分支付、不调用 lookup、不写新债、不改 carry**。W1/W2b 在候选成本/排序前同样用该预检，>1 location 的候选不可行/skip。不得按 location 分别继续，也不得把一个地点当作“主地点”吞掉其他义务。
- **唯一地点才可结算**：通过上述预检的 payer 只有一个 settlement location；该组调用一次 §6.9 lookup，作为该组共享库存预算。不得跨格合并库存，也不得用 A 格库存抵 B 格工资。
- **货币腿（户级账户）**：货币余额/冻结的权威是 `AccountSession` 的**户级账户**，不是 `HouseholdInventory` 直接结构；W3 只读 `AccountSession` 快照并按稳定义务序逐笔扣减，**不得用同一笔钱重复付**。同一 payer 多地点情形已在预检整组拒绝，故不会出现两个地点各读一份户级余额再支付。
- 义务表先算全部 due，再按稳定序逐条支付；后续义务看到的是前面已扣减后的共享预算。
- 同一 worker 的多个义务可以合并成一条转移（若实现方便），但 readout 必须按 `(unit, role, household)` 拆分；不得合并到无法归因。

### 6.3 支付顺序（U21）

对通过 payer 级地点预检的唯一 `(payer, settlementLocation)` 组，逐义务按以下顺序：

1. **当地对应货币**：
   - 可用量 = 该币种 `余额 − 冻结`（不含其他币种；不发钞、不透支、发行人也不因工资缺口铸币）。
   - `paidMoney = min(dueRemaining, availableMoney)`。
   - 不得把其他币种按汇率折进来；不得用 `MoneyIssuance` 补工资缺口。
2. **可支付活动库存（唯一读口，B3）**：
   - 按**初始可用数量降序**、同量 `CommodityId` 升序（稳定序；不是价值序）。
   - 可用量**唯一来源** = §6.9 `WagePayableInventoryLookup.payableInventory(payer, productionLocation)` 返回的**已扣完** `AccountSession` 户级冻结、**该 payer 全部地点** ACTIVE `InventoryReservation`、再生产权益/已投入资料后的最终可支付数量（`productionLocation` 类型 = `production.contract.ProductionLocation`）；**W3 不再做第二次减法**。
   - 读口不可用（D4 未接 / `Optional.empty()`）⇒ **库存腿全部跳过**，记 `wage-inventory-scope-unavailable`，剩余 due 全部成债；**不得回退“账户余额即全部可支付”**。
   - 每件商品按 §6.4 折价支付；不足继续下一商品。
   - **A5**：已经按 D2 分配到 organizer 账户、且经该读口确认属于活动库存的**本人产出**照常参与抵物；只排除未到账/预计份额、他户份额、再生产权益/已投入资料。
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
market = unit 所在市场区的 MarketTable 快照（只读）
policy = 该角色/支付形态的 BarterDiscountPolicy（D1 §2.4；唯一来源）
if market == null or !market.hasPrice(c):
    skip c with "wage-barter-price-missing:<commodity>"; continue

// economy 内唯一参考价 helper；只有现有市场事实，不新增最后成交价状态。
ref = policy.referencePrice() == LOCAL_BID ? market.bidPriceOf(c)
    : policy.referencePrice() == LOCAL_ASK ? market.askPriceOf(c)
    : fail-closed with "wage-barter-reference-price-unsupported"

if ref <= 0:                                  // 未定价（已由 hasPrice 排除）或明确 0 价
    skip c with "wage-barter-price-missing:<commodity>"; continue
unitValue = floor(ref × policy.discountPerMille / 1000)         // 抵物单位折价；可 < ref
if unitValue <= 0:
    skip c with "wage-barter-discount-zero:<commodity>"; continue
if 缺必要币种事实 or 商品在结算地点不可交付（账户/地点/冻结/占用不允许）:
    skip c with "wage-barter-not-deliverable"; continue

availableQ = payableInventory.get(c)          // §6.9 已扣完；W3 不再做二次减法
qWanted = floor(remainingDue × 1000 / unitValue)   // 毫商品单位
qPay = min(availableQ, qWanted)
paidValue = floor(qPay × unitValue / 1000)         // 毫货币；≤ remainingDue
```

- **参考价只有两种**：`LOCAL_BID → market.bidPriceOf(c)`、`LOCAL_ASK → market.askPriceOf(c)`；`hasPrice` **先判**，未定价/0 价 → `wage-barter-price-missing:<commodity>` skip；未知枚举 → `wage-barter-reference-price-unsupported` fail-closed。`MarketTable` 只有 `hasPrice`/`bidPriceOf`/`askPriceOf`，没有最后成交价事实，**不得调用不存在的市场参考价读法，也不得新增市场状态**。

- **U23-c 可配置折价**：单位折价 = 当地所选参考价 × `discountPerMille/1000`；参考价类型**仅** `LOCAL_BID`/`LOCAL_ASK`；系数**可 <1**（如 500 = 半价，同额工资需要双倍实物）；不得把 1 写死。
- **单一来源**：`BarterDiscountPolicy` 只在 D1/production 定义面；economy 读取并执行，**不得复制第二份可独立修改的策略**，也不得在 economy 侧另定默认策略。
- 缺价/0 价/**折后为 0**/缺币种/不可交付 ⇒ **skip，不得 1:1、不得任意价、不得算已清偿**，剩余债务保留。
- `paidValue ≤ remainingDue`；若因整数除法有零头，剩余继续下一商品或成债；**不得超付、不得重复减本金、不得把已付伪造成清偿**。
- 初始可用数量排序在支付前拍一次快照；后续商品被前面用掉不改变排序。
- 库存腿与货币腿可放在同一条 `Transfer`（`WAGE_PAYMENT`），但 readout 必须拆出 money/barter/arrears。

### 6.5 工资债条款与同日排除

- 新建工资债：`DebtUnit.Money(currency)` + `DebtTerms.wageArrearsDefault()`（**0‰，U23 用户已定**）+ `DebtSource.WAGE_ARREARS`（R-11）；`openedDay=day`；`dueCycle=当前周期+1`（工程默认 O8）。
- 多 unit/role/worker 同币种工资债合并：`DebtContractBook.upsert` 同 `(debtor, creditor, DebtUnit.Money(currency), terms)` 且 **source 相同**才累加本金；source 不同 ⇒ `debt-source-mismatch` fail-closed。
- **同日不双付**（E12）：
  - W3 在当日返回 `WageSettlementResult.newWageDebtIds`（transient day-local `Set<DebtContractId>`）。
  - 同日 `repayDebts` 在构造 `owed` 列表时排除这些 id；若本会选中则记 INFO `wage-debt-same-day-repay-blocked`（worker、creditor、debt id、amount）。
  - 排除集只活当天，不落持久态；次日工资债可正常进入还款轮。
  - `capitalizeArrears` 不得对 W3 工资债再资本化（W3 直接 upsert，不把 wage arrear 塞进旧 `ProductionLedger.Arrear`）。
- 工资债本金 = 未付差额；抵物已付部分已从 dueRemaining 扣掉，不得再次减本金。`DebtContractBook.upsert` 要求本金增量 >0，`arrears=0` 时不得调用。
- `wage-debt-terms-unsupported`：terms/币种/单位不符合 `wageArrearsDefault()` 时 fail-closed。
- **区分工资欠款与普通贷款（U23 用户已定 + R-11）**：0‰ 只适用于 `DebtSource.WAGE_ARREARS`；普通借贷继续按各自 `DebtTerms` 并写 `ORDINARY_LOAN`，不得被一并改零息。工资债必须有可持久来源字段（§2.9.1），仅 `WAGE_PAYMENT`/terms 不足以判定。
- **旧工资欠款也不再生息**：同一版本内 `capitalizeArrears` 的 `Arrear.Kind.WAGE` 必须写 `WAGE_ARREARS + wageArrearsDefault()`（0‰）；`RENT` 保留 20‰ 旧条款；无来源旧债不猜测（Alpha 重建）。
- **库存读口不可用**：库存腿跳过后剩余全部成债（`wage-inventory-scope-unavailable`），债务本金 = 未付差额；不得把不可用库存记成已付。

### 6.6 转移、原因与账户写口

- 新增 `TransferReason.WAGE_PAYMENT("wage_payment")`；不得复用 `RELATION_PAYMENT` 冒充工资（读账要能分开工资 vs 分成/地租）。
- 一条 `Transfer` 可同时带货币腿与商品腿；`from=organizer actor`、`to=worker household actor`、`location=unit 的 ProductionLocation`；`from != to`（organizer 自雇已在匹配阶段排除）。
- **B4（当前有效，R-05）**：W3 首期在支付/查询前按 payer 聚合义务；同一 payer 有 >1 个 settlement location ⇒ 整组 `wage-payer-location-unsupported`，零支付/零 lookup/零新债/零 carry 变化，不做部分支付。货币腿仍是 `AccountSession` 户级钱包，但绝不允许在多个 location 组各读一份同一余额。
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

- W1/W2b 的 worker 候选收入/排序**不得**读取历史欠薪、兑付率、黑名单、信用折扣；只看当前 quote 与可得实物/工资形态。
- 欠薪仍是真实债权/债务，影响库存/需求/后续劳动能力按既有机制发生；不得因为“已经欠薪”而禁止地主再雇人或家户再应募。
- 不得用“兑付率折价”把欠薪合同当成已兑现收入；也不得用它生成新的拒绝条件。

### 6.9 `WagePayableInventoryLookup`（W1/W2b/W3/D4 唯一可支付库存读口，B3）

> **状态（N-06 当前更正）**：D4 设计已完整落盘；本节只冻结 **D3 的读取契约**，`WagePayableInventoryLookup` 仍未实现，不冒充 D4 已实现。`asset-scope-shared:awaiting-D4` 是 **R2 最小边界**（共享 scope 先 fail-closed），**不是** D4 完整关闭；`wage-inventory-scope-unavailable` 是 W3 依赖 D4 未接时的**库存受限词**。

```java
interface WagePayableInventoryLookup {
  Optional<Map<CommodityId, Long>> payableInventory(
      ActorRef payer,
      io.mosire.simos.production.contract.ProductionLocation settlementLocation);
}
```

语义：

- **结算地点类型使用已有的 `io.mosire.simos.production.contract.ProductionLocation`**（D1/A 批已存在；`ProductionProcess.location()` 的权威地点类型）。不新增临时地点值类型或 economy-api 的重复值类型；D3 可依赖 production 的只读 contract 包（模块边界表允许）。
- 输入 **payer + settlement location**；返回该付款人**最终可支付**的逐商品数量（毫商品单位）。**扣减按 payer×commodity 全部地点的 ACTIVE `InventoryReservation` 汇总，不按 settlement location 过滤**；location 只作为本次结算/预算分组上下文，不得把其他地点预留当可花。
- 返回量必须**已经扣完**：`AccountSession` 户级冻结、**该 payer 全部地点**的 ACTIVE 生产占用（`InventoryReservation`）、再生产权益/已投入资料（由 D4/权益与占用所有者唯一权威计算）。**W1/W2b/W3 不再做第二次减法**，也**不得回退**到“账户余额即全部可支付”。
- **`Optional.empty()` 条件（写死，R-05）**：仅当 ① D4 lookup 尚未接线/明确 disabled；② payer 无法解析为已登记家户账户（`AccountSession` 无该户）；③ 实现明确不可用。**不得**因商品余额为 0、没有 ACTIVE 预留、没有该商品或该 place 暂无商品而返回 empty——这些是可用读口的空 `Map`。location 为 `null`/`UNSPECIFIED`/非法格式时不进入 lookup，按 `asset-location-missing` fail-closed。
- **`Optional.empty()` 的 W3 行为**：**货币腿仍可按 U21 先付**；**库存腿全部跳过**，记 `wage-inventory-scope-unavailable`；剩余 due 成债。不得回退账户原始余额。
- **payer 级地点预检先于 lookup**：W1/W2b/W3 共享 §6.9.1 `WagePayerLocationPrecheck`；同一 payer 当前操作涉及 >1 个 settlement location ⇒ W3 整组 `wage-payer-location-unsupported`，W1/W2b 候选不可行/skip，**均不调用 lookup、不逐 location 复用、不做部分支付**（B4/N-02）。通过预检的单地点组只调用一次 lookup，作为该组义务的共享库存预算快照；lookup 内部仍扣除该 payer 全部地点的 ACTIVE 预留（N-01）。
- **账户权威**：lookup 读取的 payer 商品余额/冻结必须来自 **`AccountSession` 的户级账户快照**（`AccountSession` 是货币/商品余额与冻结的权威读面）；`HouseholdInventory` 只是 actor 侧存储表示，D3 不得直接按地点拆一份 `HouseholdInventory` 当第二本库存。`settlementLocation` 不能改变户级余额的扣减基数。
- **权威归属（R-16）**：读口由 **D4-I 唯一新增/实现**；W3 只消费 D4-I 已编译的读口，**不新增、不实现 `WagePayableInventoryLookup.java`**，不写回、不复制第二套减法。D4 未接时 D3 只写接口契约，不冒充 D4 已实现。
- 预计/排队与执行使用同一读口（D4 规则）；W1/W2b 不自行扫份额/库存，也不另写第二套可支付量公式。W1/W2b 在调用读口前必须先执行 §6.9.1 的 payer 级地点预检。

### 6.9.1 `WagePayerLocationPrecheck`（W1/W2b/W3 共享，N-02）

```java
record WagePayerLocationCheck(
    ActorRef payer,
    java.util.Set<io.mosire.simos.production.contract.ProductionLocation> locations) {}

enum WagePayerLocationDecision { OK, MULTIPLE_LOCATIONS }

interface WagePayerLocationPrecheck {
  WagePayerLocationDecision evaluate(WagePayerLocationCheck check);
}
```

- **单一实现/owner**：`WagePayerLocationPrecheck` 由 **W2a** 新增并唯一实现（纯函数，无状态、无账户读取）；W1/W2b/W3 只消费同一实现，不得各自复制地点聚合逻辑。
- **输入**：当前操作涉及的 payer + 该操作的 settlement location 集合。W1 用候选/实例当前涉及的 posting/default 地点；W2b 用匹配候选涉及的 unit 地点；W3 用本次 tick 全部工资义务的地点。
- **规则**：`locations` 去重后 >1 ⇒ `MULTIPLE_LOCATIONS`，在所有 lookup/候选成本/匹配/支付之前失败。W1/W2b 返回候选不可行/skip（具名 `wage-payer-location-unsupported`），W3 整组业务拒绝（同词），均零 lookup、零部分支付、零新债、零 carry 变化。`locations` 为空/单地点 ⇒ `OK`，但仍须继续做 §6.9 lookup 的全地点预留扣减。
- **证明边界**：预检不能替代 N-01 的 lookup 全地点预留扣减；它解决“多地点义务各自读同一户级余额”，N-01 解决“其他地点生产预留被当可花”。两者都必须通过。

## §7 tick 时序

### 7.1 顺序表

| 步 | 动作 | 现有落点/新增落点 | 说明 |
|---|---|---|---|
| 0 | **W2b fail-closed 全 tick 预检（B2）** | `EconomySettlement.settleOneDayInto` 最前段（消费/抽料/劳动写回之前） | 存在 `MONEY_WAGE` 角色而 W3 未启用 ⇒ **整 tick** `wage-settlement-not-implemented`；不得留下 allocations/进度/经验/实际劳动半状态 |
| 1 | Social 投影 | `PopulationEconomyTimeParticipant.java:664-668` | composition/laborBudget/naturalNeeds 进经济会话 |
| 2 | `production.beginTick(day)` | 同文件 `:681` | 每 tick 一次；经验衰减/条件冻结 |
| 3 | **default/posting/roster 校验** + 本 tick quote 快照 | W2a 新增（`EconomySettlement.step` 前段或 `beginTick` 后） | 执行期只读 posting 快照；候选 default 只在选业/估价入口读；缺 quote/目标/币种错误 fail-closed |
| 4 | 生存预测 → 匹配/择业（角色 offer + `IdleOption`） | `LaborQueueSettlement.apply`；W2b 改角色级 | 先调只读 `SurvivalOutlookEvaluator.evaluate` 得到 `idleEligible`；再按 §4.4 选择，阶段 1 逐户排队、阶段 2 两级 cap、阶段 3 写 allocations；`IdleOption` 独立于 offer 过滤，`idleChosen` 只由选择结果写 |
| 5 | 统一分配（劳动/资产/投入） | 现有 `EconomySettlement` 劳动/投入段；D4 接资产 | 同一个 unit 的角色共享 unit cap |
| 6 | 抽料 | 现有 `drawCycleInputsPartitioned` | 与 D2/D4 既有输入链一致 |
| 7 | 生产 `executeDay` | `EconomySettlement.java:1676-1677` → `:4222-4284` | 回执含本次 tick participant 明细（D3 修正 1） |
| 8 | 工资结算/抵物/成债 | W3 新增，位于 `driveProductionDay` 后、市场轮/还债前 | 付款人共享预算；`WAGE_PAYMENT`；同日债排除 |
| 9 | 市场/生活/债务/缺口 | 现有市场轮、消费、`repayDebts`、报缺口段 | 工资到账发生在本步之前；消费已发生时只能影响下一 tick |
| 10 | Social 回写 | `PopulationEconomyTimeParticipant.java:738-795` | 缺口→饱食度→人口；工资事实只经既有 outbox/桥 |
| 11 | 下一 tick 调价生效 | W2b U20 在 tick 末写 posting；下一 tick 步 3 读取 | 不追溯本 tick due |

### 7.2 插入到 `EconomySettlement.step`

- `EconomyDayStepper.step(day)`（`:427`）调 `EconomySettlement.settleOneDayInto`；后者内部顺序是：劳动分配 → 消费 → 抽料 → `driveProductionDay`（`:1676`）→ 关账/收获（`:1900+`）→ `capitalizeArrears`（`:1981`）→ 市场轮（`:2050+`）→ 借/还债 → 报缺口。
- **B2 预检位置（钉死，W2b 独立阶段）**：`EconomySettlement.settleOneDayInto` 必须在**任何抽料、劳动匹配写回、进度、经验、实际劳动写入之前**做**全 tick 预检**；只要存在 `MONEY_WAGE` 角色而 W3 未启用 ⇒ **整 tick 具名拒绝** `wage-settlement-not-implemented`，不产生 allocations/进度/经验/实际劳动半状态。W2b 的匹配能力只能在**显式只读/dry-run 入口**验证；W3 接通后改为**按实例业务拒绝**（坏 posting/缺目标等），其他 unit 可继续。
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
wage-target-missing
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
wage-barter-discount-zero:<commodity>
wage-barter-not-deliverable
wage-debt-terms-unsupported
wage-settlement-not-implemented
wage-posting-unit-unknown
wage-payer-account-missing
wage-payer-location-unsupported
wage-inventory-scope-unavailable
no-feasible-livelihood
subsistence-shortfall
wage-default-plan-unknown
wage-default-role-unknown
wage-default-role-not-money
wage-default-market-zone-missing
wage-default-target-missing
wage-default-quote-negative
wage-default-target-negative
```

**契约/跨切片故障（ERROR，不降级，整候选 revision 拒绝）**：

```text
wage-quote-overflow
wage-carry-out-of-range
wage-posting-key-mismatch
wage-barter-reference-price-unsupported
survival-need-missing
survival-need-stale
survival-input-invalid
survival-market-facts-missing
survival-combination-limit
debt-source-missing
debt-source-mismatch
```

**非失败标记/INFO 跳过**：`wage-quote-zero`（显式 0 报价合法）、`wage-debt-same-day-repay-blocked`（当日还款轮排除新工资债，不是失败）、`wage-inventory-scope-unavailable`（库存受限，不是“已付清”）、`IDLE_ELIGIBLE`（资格读数，不等于已选 idle）、`IDLE_CHOSEN`、`UNALLOCATED_LABOR`、`IDLE_FOREGONE_POSITIVE`（默认 idle 策略下被放弃的正收益候选，不得静默）、`GRANTED_ZERO`、`GRANTED_NEGATIVE`。

语义补充：

- `wage-quote-missing`：缺报价，**不得当 0**；命令/seed 边界 `Rejected`，运行期持久态错误 `ERROR`。
- `wage-quote-zero`：显式 0 的读数，不是错误；U20 可用 `minStep` 离开零。
- `wage-posting-locked`：命令试图改写本 tick/历史 quote 或 U20 观测字段；只允许下一 tick 生效。
- `wage-barter-price-missing:<commodity>`：该商品未定价/0 价，skip 并保留剩余欠款；不得 1:1。
- `wage-barter-reference-price-unsupported`：`BarterDiscountPolicy.referencePrice` 不是 `LOCAL_BID`/`LOCAL_ASK`；契约/跨切片故障，ERROR 且不得降级成默认 bid。
- `IDLE_ELIGIBLE`：`SurvivalOutlook.idleEligible=true` 的资格读数，**不等于** `IDLE_CHOSEN`；最终是否闲置看 §4.4 选择结果。
- `UNALLOCATED_LABOR`：正收益工作已选中后剩余未花劳动；不是 `IDLE_CHOSEN`。
- `survival-need-missing`/`survival-need-stale`/`survival-input-invalid`/`survival-market-facts-missing`/`survival-combination-limit`：§2.5.1 `SurvivalOutlook` 的契约失败；ERROR 不降级，不得把缺输入当无需求/零价/空可行集合继续算；组合超限不得返回截断 best 或写 `idleEligible`。
- `wage-debt-same-day-repay-blocked`：同日新工资债被还款轮排除的 INFO 读数；不是失败。
- `wage-settlement-not-implemented`：W2b 尚未接 W3 时，含 MONEY_WAGE 角色的**整 tick** 执行边界；预检在任何抽料/写回/进度/经验/实际劳动之前（§4.7/§7.2）；D2 §8.4 保留。
- `wage-target-missing`：缺目标（既无候选 default 也无实例 posting 给 `targetLaborMilliPerDay`）；不得静默 0 当“没有岗位”。
- `wage-barter-discount-zero:<commodity>`：`BarterDiscountPolicy` 折后单位价值 ≤ 0；该商品不可用于本次抵付，剩余债务保留。
- `wage-inventory-scope-unavailable`：D4/`WagePayableInventoryLookup` 未接或不可用；货币仍可先付，库存腿全部跳过，剩余成债；**不得回退“账户余额即全部可支付”**。
- `wage-payer-location-unsupported`：同一 payer 当前操作涉及 >1 个 settlement location；W1/W2b 候选不可行/skip，W3 在 lookup/支付前整组具名拒绝；均零 lookup/零部分支付/零新债/零 carry 变化；不得跨格合并或跨格抵付。
- `no-feasible-livelihood` / `subsistence-shortfall`：`SurvivalOutlook` 的生存资格结果（无任何可行方式 / 有可行方式但整户合计仍不足生存）；evaluator 只置 `idleEligible=true`，**不自动**置 `idleChosen`；`idleChosen` 由 §4.4 的 `SURVIVAL_IDLE_POLICY` 决定（默认策略对可自由分配劳动置 `IDLE_CHOSEN`）；不抛空集合最大值。

### 8.2 日志

- **INFO**：posting set/clear、显式改价、U20 改价（old→new、reason、streak）、工资结算日汇总（payer/unit/roles/workers/due/money/barter/arrears）、同日债排除、命令成功。
- **DEBUG**：逐候选 skip、逐义务 due/paid/arrears、barter 初始排序、carry before/after、两级 cap 结果、`SurvivalOutlook` 的 household/nextTickNeed/bestAchievableCoverage/shortfall/idleEligible/reason、idle 选择。
- **TRACE**：逐商品腿、逐 worker/role 明细、quote 快照。
- 业务拒绝 = INFO 具名；契约/跨切片一致性故障 = ERROR 不降级；日志失败不得影响状态（沿用 AGENTS §一.9）。
- 日志不得写载荷明文敏感内容；不得用异常消息代替稳定 reason。

### 8.3 不变量（D3-I）

1. **D3-I1 报价锁定**：本 tick 匹配/结算只读本 tick quote 快照；U20/命令改价只影响后续 tick；已发生 due 不追溯。
2. **D3-I2 三数守恒**：逐义务 `due = paidMoney + Σ paidBarterValue + arrears`；不得超付、不得重复减本金。
3. **D3-I3 共享预算（B4/R-05）**：W3 在任何支付/lookup 前先按 payer 聚合；同一 payer 有 >1 个 `production.contract.ProductionLocation` 的工资义务 ⇒ 整组 `wage-payer-location-unsupported`，不做部分支付。通过预检的 payer 只有一个地点，其该组义务只有一份**库存**预算；货币腿是 `AccountSession` 同一户钱包，逐笔扣减，不得双付。
4. **D3-I4 库存不双付/冻结不越（B3/N-01）**：`WagePayableInventoryLookup` 的形参地点类型必须是已有 `production.contract.ProductionLocation`；返回量必须按 **payer×commodity 全地点**扣除 `AccountSession` 冻结与全部 ACTIVE `InventoryReservation`，location 只作结算/预算分组；W1/W2b/W3 不再二次减冻结/占用/权益；D4 未接 ⇒ 库存腿跳过并记 `wage-inventory-scope-unavailable`、余款成债；不得回退“账户余额即全部可支付”；不得用再生产权益抵工资。
5. **D3-I5 缺 quote ≠ 0**：`Optional.empty()` 与显式 0 分开；缺 quote 不得生成 0 工资/0 债务。
6. **D3-I6 欠薪不影响择业**：W1/W2b 不读历史欠薪/兑付率/黑名单/信用折扣作为候选输入。
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
17. **D3-I17 W2b 边界（B2）**：未接 W3 时，任何含 MONEY_WAGE 角色的真实 tick 必须在抽料/劳动写回/进度/经验/实际劳动写入之前**整 tick** `wage-settlement-not-implemented`，零半状态；不得只发产品份额不记工资。
18. **D3-I18 不冒充 R2**：D3 不修共享资产/多实例占用；`asset-scope-shared:awaiting-D4` 是 R2 最小边界，不是 D4 完整关闭；D4 未接时的资产限制继续具名。
19. **D3-I19 周期窗口（A1）**：候选/采用比较窗口 = `definition.cycleDays()`；产品份额、投入、工资应收/应付、劳动分母同一窗口；**实际 due 按本次 tick 实际劳动、不乘 cycleDays**。
20. **D3-I20 生存资格与选择分离（U23-b）**：`idleEligible=true` 当且仅当整户所有可行组合（含空组合）都无法取得下一 tick 生存所需；不限流民/无产者，正收入不足同样适用；`idleEligible` 不自动等于 `idleChosen`。`idleChosen` 由 `SURVIVAL_IDLE_POLICY` 决定：默认 `PREFER_IDLE_WHEN_SUBSISTENCE_UNMET` 时对可自由分配剩余劳动置 `idleChosen=true`；`BEST_EFFORT_WORK` 时正收益工作可被选中，`idleChosen=false`。零可行/不足分别具名 `no-feasible-livelihood`/`subsistence-shortfall`；闲置不发工资/经验/免费食物且仍消耗生活物资。
21. **D3-I21 idle 独立候选（A2）**：`IdleOption` 不经过 `queueable()`/`maxAbsorbable>0`；`idleEligible=true` 且策略=`PREFER_IDLE_WHEN_SUBSISTENCE_UNMET`（默认）时可自由分配劳动记 `IDLE_CHOSEN`；策略=`BEST_EFFORT_WORK` 时正收益工作先选，只有零/负收益且无可计价补偿的工作可被 idle 胜过；idle 胜出不取消 GOV_SERVICE/既有不排队配额。`IDLE_FOREGONE_POSITIVE` 是默认策略下被放弃正收益候选的读数，不是静默丢弃。
22. **D3-I22 候选 default 闭环（A3）**：新进入候选读 `ProductionWageDefault`；选中后同一合法 Revision 建实例 + posting；未选候选零实例/零 posting；default 不是第二份执行报价；缺 default 且无 posting ⇒ `wage-quote-missing`，缺目标 ⇒ `wage-target-missing`。
23. **D3-I23 折价可配置（U23-c）**：`BarterDiscountPolicy` 单一来源、参考价类型**仅 `LOCAL_BID`/`LOCAL_ASK`** + 系数可 <1；economy 唯一 helper 先 `hasPrice` 再选 `bidPriceOf`/`askPriceOf`；折后 0/缺价/缺币种 ⇒ 该商品 skip 且保留剩余债务；排序仍按可用数量降序，不按价值。
24. **D3-I24 工资债零息与来源（U23-a/R-11）**：工资债同时带 `DebtSource.WAGE_ARREARS` + `wageArrearsDefault()` 0‰；普通贷款带 `ORDINARY_LOAN` 并保原 terms；`capitalizeArrears(WAGE)` 写 `WAGE_ARREARS` 0‰；旧工资欠款不再生息；不得套 legacy 20‰；source 进 codec/ChangeSet 往返。
25. **D3-I25 payer 级地点闭合（B4/R-05）**：W3 在任何支付/lookup/新债前先按 payer 聚合；同一 payer >1 settlement location ⇒ 整组 `wage-payer-location-unsupported`，零部分支付、零 lookup；库存余额的账户权威是 `AccountSession` 户级账户，地点类型统一 `production.contract.ProductionLocation`；同一账户、两地、一份库存不得双付。
26. **D3-I26 生存预测只读（U23-b）**：`SurvivalOutlookEvaluator.evaluate` 是纯只读函数，输入/输出/组合确定性/空集合具名原因/缺价不可达不折算/预测不抵实收均按 §2.5.1；不写状态；`allowIdle`/`householdLaborChoices` 已撤回，不新增对应状态。

### 8.4 正向验收判据

| # | 判据 | 预期 |
|---|---|---|
| P1 | 显式 `wagePerLaborMilli=0` posting | quote 存在、可匹配、U20 能从 0 加价；不报 `wage-quote-missing` |
| P2 | 缺初始 quote 的 seed/命令 | `wage-quote-missing`，零变更；不得写 0 |
| P3 | quote 币种 ≠ legalTender | `wage-currency-mismatch`；不换算、不写状态 |
| P4 | 缺工连续多 tick | `shortageStreak` 累加、加价步长随 momentum 增大、零薪离开 0 |
| P5 | 招满连续 tick 与 `decreaseDelayTicks`（A4） | `streak == delay` **不降**；`streak == delay+1` 首次降，`rate=initial+accel`；之后幅度递增、封顶、不穿零 |
| P6 | 重新缺工 | 立即清降价连胜、进入加价；招满清加价动量 |
| P7 | §2.5 判定不足生存 + 只有零工资无补偿工作（U23-b/A2） | `SurvivalOutlook.idleEligible=true`；本轮无正收益工作可选，`IdleOption` 显式胜出：`idleChosen=true`/`IDLE_CHOSEN`，`idleLaborMilli` 为扣除 GOV_SERVICE/不排队配额后的剩余预算；GOV_SERVICE 不被取消 |
| P8 | 有可行组合可达生存、但存在零/负收益工作（U23-b/A2） | `idleEligible=false`；不预过滤零/负收益工作，可被 `GRANTED_ZERO`/`GRANTED_NEGATIVE` 选择；不得把 `idleEligible=false` 误读成强制接受零/负收益，也不得把可达生存者一律强制闲置 |
| P9 | 同一户多角色 | 阶段 1 预算共享；总劳动 ≤ `HouseholdEconomy.laborMilli` |
| P10 | 产品份额角色 + 工资角色同 unit | unit cap 共享；不各发一份；两级封顶守恒 |
| P11 | organizer 本户 vs 自己 wage 角色 | `wage-organizer-self-employment` skip；不生成自付工资 |
| P12 | 缺账户/跨市场区/资格不符 | 分别 `wage-worker-account-missing`/`wage-worker-location-mismatch`/`role-qualification:...` skip |
| P13 | W1 雇主候选（A1/A3） | `expectedWageCostMilli` = Σ quote×`expectedLaborMilliPerCycle`/1000（**×cycleDays**）；net 扣工资；缺 quote/target 不按 0；实例存在前用 default |
| P14 | W1 工人候选（A1/A3） | income 用 quote×`expectedWorkableLaborMilliPerCycle`/1000（**×cycleDays**）；显式 0 与缺 quote 分开；实例存在前用 default |
| P15 | 工资 due 用本次 tick 明细 | 同一周期不同 tick 劳动变化 ⇒ due 随之变化；不按周期累计/计划劳动 |
| P16 | carry | quote=1、每 tick 500mh：首 tick due=0/carry=500，次 tick due=1/carry=0 |
| P17 | 货币优先 | 有足够对应货币时先付钱，不动库存 |
| P18 | 库存数量降序 | 初始可用数量最多的商品先抵；同量按 CommodityId 升序 |
| P19 | 缺价/0 价商品 + 参考价选择 | 先 `hasPrice`；`LOCAL_BID` 只读 `bidPriceOf`、`LOCAL_ASK` 只读 `askPriceOf`；缺价/0 价 ⇒ `wage-barter-price-missing:<commodity>` skip，剩余成债；未知枚举 ⇒ `wage-barter-reference-price-unsupported` fail-closed；不 1:1、不调用不存在的市场参考价读法 |
| P20 | 同一付款人两工人 | 共享预算：先义务先付，后义务只拿剩余；Σ支付+欠款=Σdue |
| P21 | 同一工资债同日还款轮 | `wage-debt-same-day-repay-blocked`，不双付；次日可还 |
| P22 | 工资债条款（U23-a） | `wageArrearsDefault()` **0‰（用户已定）**；普通贷款仍按各自 terms；旧工资欠款不再生息；不使用 legacy 20‰ |
| P23 | 转移 | `WAGE_PAYMENT`；一条可带钱+物腿；readout 拆 money/barter/arrears |
| P24 | 账户落账 | 只出现 `AccountSession.commit`/`applyTransfer` 路径；无直接 Map 写 |
| P25 | 欠薪不影响择业 | 历史欠薪/兑付率不改变候选排序/收益；新报价仍可应募 |
| P26 | 目标/改价不追溯 | 改 target/quote 后，已完成 tick 的 due/readout 不变；下一 tick 生效 |
| P27 | 确定性 | 同输入同输出；稳定序；无随机/时钟/UUID |
| P28 | 日志 | INFO/DEBUG/TRACE 各至少一条可断言事件；拒绝有具名 reason |
| P29 | **U23-a 工资债零息 + 来源分离（R-11）** | 未付工资成债 ⇒ `DebtSource.WAGE_ARREARS` + `wageArrearsDefault()` 0‰；同额普通贷款 ⇒ `ORDINARY_LOAN` 保持原 terms（20‰ 例仍 102）；`capitalizeArrears(WAGE)` 写 `WAGE_ARREARS` 0‰，旧工资债本金不再增长；`LEGACY_UNKNOWN` 不冒充工资债；同四元组不同 source ⇒ `debt-source-mismatch`；source 经 codec/往返逐值保留 |
| P29b | **DebtSource 全写入点/迁移/seed（N-05）** | `UnitBorrowHandler`/`MoneyMarketSettlement`/`MarketSettlement`/`MintSettlement` 的普通债务 ⇒ `ORDINARY_LOAN`；`ModeMigrationSettlement`/`LotMigrationBook` 逐值保留 source；`EconomyPayloads`/`EconomySeedHandler` 显式 source（普通贷款 seed 为 `ORDINARY_LOAN`，缺 source ⇒ `debt-source-missing`）；`DebtContractBook.upsert`/`new DebtContract` 全调用点 source 非空且同类一致；漏一调用点/默认 `LEGACY_UNKNOWN` ⇒ 红 |
| P30 | **U23-b 默认生存不足可裁决不从业** | 每 tick 所需 10、整户所有可行组合合计最好 1 ⇒ `subsistence-shortfall` + `idleEligible=true`；默认 `PREFER_IDLE_WHEN_SUBSISTENCE_UNMET` ⇒ `idleChosen=true`/`IDLE_CHOSEN`，正收益候选只进 `IDLE_FOREGONE_POSITIVE` 读数、不授予劳动；仍消耗生活物资，无工资/经验/免费食物；不限流民/无产者 |
| P30b | **U23-b 最佳努力策略不强制闲置** | 同上输入，策略=`BEST_EFFORT_WORK` ⇒ `idleEligible=true`，正收益所得 1 可被选中 ⇒ `idleChosen=false` 且保留缺口读数；若没有正收益工作被选中，零/负收益工作才可被 idle 击败并记 `IDLE_CHOSEN` |
| P31 | **U23-b 组合可达时不强制闲置** | 整户可行组合合计可达生存 ⇒ `idleEligible=false`；正收益工作先做，剩余劳动按 `idleChosen`/`UNALLOCATED_LABOR` 显式落 readout，不得断言一律 `IDLE_CHOSEN` |
| P32 | **U23-c 折价可 <1** | `discountPerMille=500`、`ref=10` ⇒ `unitValue=5`，同额 `due=100` 需 `qWanted=20,000` 毫商品（系数 1000 时仅 10,000）；`LOCAL_BID`/`LOCAL_ASK` 各走现有 bid/ask 读法；`paidValue≤remaining`；折后 0 ⇒ skip 留债 |
| P33 | **A1 周期窗口** | `cycle=100`、日雇 1h、单价 2 ⇒ 候选工资 200 货币；实际每 tick due=2 货币；候选只扣 2 ⇒ 红 |
| P34 | **A3 候选 default 闭环** | 未建实例候选读 default；选中同批建实例 + posting；未选零 posting；缺 default 且无 posting ⇒ `wage-quote-missing`；缺 target ⇒ `wage-target-missing` |
| P35 | **A4 首次降价边界** | `delay=2`：`streak=2` 不降；`streak=3` 首次降 `rate=initial+accel`；`streak=4` ⇒ `rate=initial+2×accel` |
| P36 | **A2 idle 独立候选与策略** | `IdleOption` 不经 `queueable()/maxAbsorbable`；`idleEligible=true` 时，默认策略 `PREFER_IDLE_WHEN_SUBSISTENCE_UNMET` ⇒ 可自由分配剩余劳动 `IDLE_CHOSEN`，正收益候选进 `IDLE_FOREGONE_POSITIVE`；`BEST_EFFORT_WORK` 策略下正收益先工作 → `idleChosen=false`，零/负收益无补偿且无正收益工作被选中时才 `IDLE_CHOSEN`；GOV_SERVICE 保留 |
| P37 | **B2 W2b 整 tick 预检** | W2b 真实 `settleOneDayInto` 遇 MONEY_WAGE 且 W3 未启用 ⇒ 整 tick `wage-settlement-not-implemented`；allocations/进度/经验/实际劳动零写入；dry-run 可读匹配 |
| P38 | **B3 可支付库存读口** | `WagePayableInventoryLookup.payableInventory(ActorRef, production.contract.ProductionLocation)`；D4 lookup 不可用 ⇒ 货币先付、库存腿跳过 + `wage-inventory-scope-unavailable`、余款成债；可用 ⇒ 每份只扣一次，权益/占用为零可付 |
| P38b | **全地点 ACTIVE 预留扣除（N-01）** | `grain=1000`、冻结 0、B 地点有 ACTIVE 预留 300、A 是唯一 W3 地点且只有工资义务 ⇒ `lookup(A)` 返回 `grain=700`；只扣 A 地点预留 ⇒ P38b 红；支付/成债不得超过 700 |
| P39 | **B4 payer 级地点预检（R-05）** | 同一 payer 两 location ⇒ W1/W2b 候选不可行/skip、W3 在 lookup/支付前整组 `wage-payer-location-unsupported`，均零部分支付、零 lookup、零新债、零 carry 变化；同账户、两地、一份库存不得被双付；通过预检的单地点组只调用一次 lookup，共享预算 |
| P39b | **W1/W2b 共用预检（N-02）** | 同一 payer 两地点在 W1/W2b 也只能得到候选不可行/skip，不能先按 location 各 lookup 一次；W1 不计算工资成本、W2b 不进入排序/allocations；W2a 的 `WagePayerLocationPrecheck` 是 W1/W2b/W3 唯一实现 |
| P40 | **A5 工资池收窄** | 预计/未到账份额不计入支付能力；已到账且经 lookup 确认的本人粮按 U21 参与抵物；他户份额/权益不得抵 |
| P41 | **SurvivalOutlook 纯只读契约** | 缺 Social 下一 tick 需求 ⇒ ERROR `survival-need-missing`；组合不重复劳动/资源；空可行不判最大值；缺价/不可达不作 0 或 1:1；返回 `idleEligible` 与具名 reason，不写 `idleChosen`；调用前后状态零变化 |
| P42 | **R-04 收入接入 coverage：0/10 与 1/10** | 需求 10、当前库存 0；预计货币收入 10、当地可达价 1、供给 ≥10 ⇒ `coverage=1000‰`、`idleEligible=false`；同例预计收入改成 1 ⇒ `coverage=100‰`、`subsistence-shortfall`、`idleEligible=true`；`provenance.fromMoney` 分别=10/1 |
| P43 | **R-04 组合可达与同预算不重复** | 需求 10；两份替代各得 5，劳动之和 ≤ 预算且资源 claim 不相交 ⇒ `coverage=1000‰`、`idleEligible=false`；若两份都占用同一 `ResourceClaimKey` 或同一小时预算 ⇒ 不得相加，最好仍 5 ⇒ `idleEligible=true` |
| P44 | **R-04 预计实物/产品份额接入与来源守恒** | 需求 10；当前库存 6、预计实物 4、货币 0 ⇒ `coverage=1000‰`、`idleEligible=false`；`provenance` 的 `fromInventory=6`、`fromExpectedInKind=4`、其余 0；预计产品份额同额路径同样可覆盖且 `fromExpectedProductShare` 单列 |
| P45 | **R-04 已入库去重** | 收获/工资已实际入账后，调用方必须把同量从 `expected*` 移除；夹具：当前库存 10、预计池空 ⇒ `fromInventory=10`、`fromExpected*=0`；若实现把库存再灌入预计池/重复相加，P44/P45 的来源守恒红 |
| P46 | **R-04 缺价/不可达/0 价** | 需求 10、当前库存 4、预计货币 10，但 `localFacts.byCommodity[c]` 缺行/`reachable=false`/`purchasePriceMilli=0`/`reachableSupplyMilli=0` ⇒ `coverage=400‰`、`idleEligible=true`，不得按 0 元或 1:1 记满 |
| P47 | **R-04 同商品双拼写** | 同一替代同一商品同时出现在 `expectedInKindMilli` 与 `expectedProductShareMilli` ⇒ ERROR `survival-income-double-count`，不得相加 |
| P48 | **量纲口径（R-07）** | `1 小时 = 1000 毫小时`；`laborMilli=1000` 即 1 小时；`standardLaborMilliPerCycle=1000` 即 1 小时/周期；`quote=2000` 表示 2000 毫货币/小时 = 2 货币/小时；工资 = `quote × laborMilli / 1000`（laborMilli 为毫小时）。任一按“千分劳动”解释 ⇒ 红 |
| P49 | **下一 tick/周期时间门槛（N-04）** | `nextTickNeed.forTick=1`；product share 1000 单位只在 `incomeAvailableAtTick=100`（cycle=100）交付 ⇒ 本 tick 覆盖必须为 0（或仅计另一份 `incomeAvailableAtTick<=1` 的 10 单位）；把整季 1000 当下一 tick 口粮 ⇒ P49 红；D2 `×cycleDays` 周期预计不得原样传入 SurvivalOutlook |
| P50 | **组合枚举上限（N-09）** | `maxCombinations=3` 且可行替代产生 >3 个非空子集 ⇒ ERROR `survival-combination-limit`，不返回截断 best/idleEligible；提高上限后同一输入可确定复算；同一上限重复运行结果一致；不得静默近似 |

### 8.5 负向/变异判据（改坏 ⇒ 当场红 ⇒ 还原 ⇒ md5 一致）

| 变异 | 应红 |
|---|---|
| 缺 quote 时按 0 计 | P2/P13/P14 |
| 用 `FIXED_MONEY_WAGE.fixedAmount` 代替 quote | P13/P14 |
| 保留 `MIN_NET_PER_LABOR_SCALED` 预过滤 | P7/P8 |
| idle 作为 `maxAbsorbable=0` 的普通 Offer / 经 `queueable()` 过滤 | P7/P36（idle 永远进不了队列） |
| 靠 `"idle:" < "unit:"` 字符串或 `HashMap` 顺序决定 idle 胜负 | P36 |
| 零/负收益工作被暗判成“空闲”，或未按 `IdleOption` 显式落选择结果 | P7/P8 |
| 忽略 `SURVIVAL_IDLE_POLICY`：默认策略仍强塞正收益工作，或 `BEST_EFFORT_WORK` 下仍强制 idle | P7/P30/P30b/P36 |
| `idleEligible=false` 仍构造可胜出的 idle，或 idle 胜出时取消 GOV_SERVICE/不排队配额 | P7/P8/P31/P36 |
| 正收益工作已选中仍把剩余劳动记 `IDLE_CHOSEN` | P31/P36 |
| 同一户按角色各发一份劳动预算 | P9 |
| 产品份额/工资角色各发一份 unit cap | P10 |
| organizer 自雇 wage 角色 | P11 |
| 用周期累计/计划劳动算 due | P15 |
| carry 跨 worker 共享/复位错误 | P16 |
| 付款顺序先库存后货币 | P17 |
| 库存按价值/口粮优先排序 | P18 |
| 缺价按 1:1 或任意价；未先 `hasPrice` | P19 |
| 调用不存在的市场参考价读法、新增第三种参考价类型、或未知枚举不 fail-closed | P19/P32 |
| 同一 `(payer, location)` 复制预算 / 同一 organizer 跨 location 共享库存预算 | P20/P39 |
| `WagePayableInventoryLookup` 用悬空/另造的地点类型而非 `production.contract.ProductionLocation` | P38/文档验收 |
| W1/W2b 不做 payer 级地点预检、逐地点 lookup | P39b |
| 同日新工资债被还款轮再付 | P21 |
| 工资债套 20‰ legacy terms | P22 |
| 工资债只改 terms、不写 `DebtSource.WAGE_ARREARS`；或普通贷款因 U23 被改 0‰ | P29 |
| 普通贷款/迁移/seed 漏 source，或迁移默认改写 source | P29b |
| 把 `LEGACY_UNKNOWN` 来源当工资债自动 0‰ | P29 |
| 同一合同 id 不同 source 静默合并 | P29 |
| `DebtSource` 漏进 codec/ChangeSet 往返 | P29 |
| 复用 `RELATION_PAYMENT` 冒充工资 | P23 |
| 直接改 `householdGoods`/`householdMoney` | P24 |
| 用欠薪历史/兑付率影响择业 | P25 |
| 改价追溯已完成 due | P26 |
| 用 HashMap/随机/时钟排序 | P27 |
| 迁移/命令静默改历史 posting 字段 | P26/`wage-posting-locked` |
| 正收入（如 1）但不足生存就取消不从业资格 | P30 |
| 各副业单独不足就判整户不足（重复使用同一预算/不按组合合计） | P30/P31 |
| 零可行候选对空集合求最大值/抛异常 | P30 |
| 不从业仍发工资/经验/免费食物 | P30 |
| 恢复已撤回的 `allowIdle` 缺行默认或 `householdLaborChoices` 状态/命令/seed | 文档验收 + P7 |
| 工资债套 legacy 20‰ / 把普通贷款也改零息 | P22/P29 |
| 旧工资欠款继续 legacy 生息 | P29 |
| 抵物固定 bid×1 / 折价系数写死 1000 | P32 |
| 折后为 0 仍按任意价抵付或算已清偿 | P32/P19 |
| 取整导致超付、重复减本金或把已付伪造成清偿 | P32/P38 |
| 候选/采用比较只按日、不乘 `cycleDays` | P13/P14/P33 |
| 实际 due 乘 `cycleDays` | P15/P33 |
| 新进入候选必须先建实例才能估价 / 未选候选残留实例或 posting | P34 |
| 缺 default 静默按 0 或运行时回读旧固定额 | P34/P2 |
| `satisfiedStreak >= decreaseDelayTicks` 即降价 | P5/P35 |
| W2b 在抽料/写回之后才拒绝（留下半状态） | P37 |
| W3 二次扣减冻结/占用/权益 | P38 |
| D4 不可用时回退“账户余额即全部可支付” | P38 |
| 同一 organizer 跨 location 共享库存预算 | P39 |
| lookup 只扣当前 location 的 ACTIVE 预留，漏其他地点 | P38b |
| 同一 payer 多地点时逐 location 继续支付/查询（部分支付） | P39 |
| 用 `HouseholdInventory` 或账户原始余额按地点另造第二本库存 | P38/P39 |
| 把“余额为 0/无预留/无该商品”当 `Optional.empty()` 而不是空 `Map` | P38 |
| 把他户份额或再生产权益计入工资池 | P40 |
| 一律禁止已到账本人产出抵物（A5 过收窄） | P40 |
| SurvivalOutlook 重复计库存/实物收入、重复用劳动/资源预算 | P41/P42–P47/P30/P31 |
| 把 `expectedMoneyIncomeMilli` 只放进输出却不接入购买预算 | P42 |
| 把预计产品份额先按价格折成货币，或把同一商品同时计入 in-kind/product-share | P44/P47 |
| 缺价/不可达/0 价按 0 或 1:1 覆盖 | P46 |
| 已入库数量又出现在 `expected*` 被再加一次 | P44/P45 |
| 空可行集合求最大值、把缺 Social 需求当无需求、缺价/不可达当 0 或 1:1 | P41 |
| 把整季 product share/工资周期累计当下一 tick 可交付收入 | P49 |
| 忽略 `incomeAvailableAtTick` 或缺失字段仍继续 | P49/`survival-input-invalid` |
| 组合数超过 `maxCombinations` 后静默截断或返回近似 best | P50/`survival-combination-limit` |
| 生存预测写入状态或由 evaluator 暗设 `idleChosen` | P41/P30 |

### 8.6 数值例（W5 用）

1. **零薪离开零**：`wage=0`、`target=10000`、`matched=0`、`shortage=1000‰`、`minStep=1`、`increaseRate=100`、`momentum=1` ⇒ `step=max(1,0)×1=1`，新 wage=1；连续缺工 3 tick ⇒ momentum=3，步长累加。
2. **缺工累加**：`wage=100`、`target=10000`、`matched=7000`、`shortage=300‰`、`increaseRate=100`、momentum=3 ⇒ `raw=floor(100×100×300/1_000_000)=3`，`step=3×3=9`，新 wage=109。
3. **招满慢降（A4）**：`wage=1000`、`decreaseDelayTicks=2`、`decreaseInitialRate=10`、`accel=5`、`cap=100`；`satisfiedStreak=2` **不降**（`>` 边界）；`streak=3` 首次降：`rate=10+(3-2)×5=15`，`step=15`，wage=985；`streak=4`：`rate=20`，`step=20`，wage=980；不穿零。
4. **价格缺失跳过**：due=10，库存只有商品 X 但 `hasPrice(X)=false` ⇒ `wage-barter-price-missing:X`，arrears=10；即使 X 数量很多也不抵。
5. **数量排序**：初始可用：fiber=500、grain=300、tool=300；排序 fiber（500）→ grain（300，同量 CommodityId 升序）→ tool（300）；不按价值排序。
6. **同一付款人两工人**：organizer 可用货币 7，worker A due=5、worker B due=5，稳定序 A→B ⇒ A 付 5、B 付 2、B 欠 3；不得 A/B 各看到 7。
7. **quote=1、500mh carry**：tick1 `num=1×500+0=500` ⇒ due=0、carry=500；tick2 `num=500+500=1000` ⇒ due=1、carry=0。
8. **同日债不双付**：due=10、现金付 4、抵物付 3、arrears=3；同日 `repayDebts` 看到新债 3 必须 skip 并记 `wage-debt-same-day-repay-blocked`；若还款轮又付 3，P21 红。
9. **A1 周期窗口**：`cycle=100`、日雇 1 小时（1000 mh/日）、单价 2 货币/小时（`quote=2000`）⇒ 候选周期劳动 100,000 mh、候选工资 `200,000 毫货币=200 货币`；实际每 tick 劳动 1000 mh ⇒ `due=2000×1000/1000=2000 毫货币=2 货币`。候选只扣 2 ⇒ 红。
10. **U23-b 生存资格与选择**：生存所需 10；只有一份可行工作、每 tick 最好所得 1 ⇒ `SurvivalOutlook` 返回 `subsistence-shortfall` + `idleEligible=true`。默认策略 `PREFER_IDLE_WHEN_SUBSISTENCE_UNMET` ⇒ 可自由分配劳动 `IDLE_CHOSEN`，正收益候选记 `IDLE_FOREGONE_POSITIVE`；`BEST_EFFORT_WORK` 策略 ⇒ 正收益所得 1 可被选中、`idleChosen=false` 并保留缺口读数。若另一份副业可兼做且合计 10 ⇒ 组合可达，`idleEligible=false`；若两份副业都只能吃同一份 1 小时预算、各报 1 ⇒ **不得**相加成 2（同一预算不得重复使用），且评估要返回具名原因/空集合处理，不得对空集合求最大值。
11. **U23-c 折价**：`ref=10`、`discountPerMille=500` ⇒ `unitValue=5`；`due=100` ⇒ `qWanted=floor(100×1000/5)=20,000` 毫商品；系数 1000 时只需 10,000。`ref` 只能由 `LOCAL_BID→bidPriceOf` 或 `LOCAL_ASK→askPriceOf` 取得（先 `hasPrice`）；同额工资折价更低 ⇒ 需要更多实物；折后 0 ⇒ skip 留债。
12. **U23-a 零息与来源（R-11）**：工资债本金 100、source=`WAGE_ARREARS`、`wageArrearsDefault()` 0‰ ⇒ 下一周期仍 100；同额普通贷款 source=`ORDINARY_LOAN`、20‰ ⇒ 102；`capitalizeArrears(WAGE)` 产生 `WAGE_ARREARS`+0‰，`capitalizeArrears(RENT)` 产生 `RENT_ARREARS`+旧 20‰；`LEGACY_UNKNOWN` 不被自动改成 0‰。同 id/source 不同 ⇒ `debt-source-mismatch`；把工资债按 20‰ 记、把普通贷款也改 0‰、或漏 source 往返 ⇒ 红。
13. **A3 候选 default**：未建实例时读到 `ProductionWageDefault(plan=farm, role=hired-hand, zone=z, wage=1000, target=5000)` ⇒ 候选可估价；选中后同一 Revision 建实例 + `ProductionWagePosting`；未选候选**无实例/无 posting**。缺 default 且无 posting ⇒ `wage-quote-missing`；缺 target ⇒ `wage-target-missing`。
14. **B3/B4 库存与地点（R-05）**：`due=10`、现金 4、`WagePayableInventoryLookup` 返回 empty ⇒ 付 4、库存腿跳过、记 `wage-inventory-scope-unavailable`、arrears=6；不得用账户余额 100 当可支付库存。同一 payer 在 location A/B 各有一笔义务 ⇒ **支付/lookup 前整组** `wage-payer-location-unsupported`，A/B 都不发生支付/查询/新债/carry 变化；不得按地点各读一次户级余额。若只有 A 一个地点且账户一份库存，则 A 组只调用一次 lookup。

## §9 文件所有权 / W1–W5 / 未做

### 9.1 批次与 owner 边界

> 只列方向与禁止面；具体类/方法拆分由实现 owner 在实现架构账本中定。测试统一最后由 W5 独立测试 owner 写/跑。
>
> **批次顺序（B1，当前有效）**：**W2a（候选 default + posting/quote 读口）→ W1（工资前置估值）→ W2b（角色级匹配/idle/U20）→ W3（支付/抵物/债）→ W4（tick 时序/Social 反馈）→ W5（独立测试）**。**重规划 Phase W 的旧 `W1→W2` 顺序已由本局部更正取代**；replan 已在 2026-10-17 修正轮局部同步，历史旧句仅作 provenance。
>
> **命名兼容**：本册其它节若仍写“W2”，按本表理解为 **W2a**（default/posting/quote 读口）或 **W2b**（角色级匹配/idle/U20），以该处描述的内容为准。
>
> **共享 economy 文件 owner 交接顺序（N-08，当前有效）**：`C0/C0-A → C2 → W2a → W1 → W2b → D4-I → D4-T → W3`。C1/C3 只处理各自 production/app 面，不插入共享 economy 文件交接；C2/W2a/W2b/D4-I 不得并行改同一 `EconomyData`/`EconomyChangeSet`/`EconomyCodec`/`ExpectedProfitBook`/`LaborQueueBook`/`EconomySettlement`；同一文件同一时刻一个 writer。

| 批次 | 可写方向 | 禁止/边界 |
|---|---|---|
| **W2a（候选 default + posting/quote 读口 + `WagePayerLocationPrecheck`）** | `simos-economy/.../EconomyData.java`、`EconomyDayView.java`、`EconomyBoundaryView.java`；`change/EconomyChangeSet.java`；`codec/EconomyCodec.java`；新增 `model/ProductionWageDefault.java`、`WageDefaultKey.java`、`ProductionWagePosting.java`、`WagePostingKey.java`、`ProductionWageDefaultLookup.java`、`ProductionWageQuote.java`、`ProductionWageQuoteLookup.java`；`spi/EconomySetProductionWageDefaultHandler.java`、`EconomySetProductionWagePostingHandler.java`、`EconomyPayloads.java`、`EconomySeedHandler.java`；`EconomyReferenceValidation.java`；新增 `time/ProductionWageBook.java`（default/posting 读口）；新增并唯一实现 `time/WagePayerLocationPrecheck.java`（§6.9.1 纯函数，供 W1/W2b/W3 共享） | **不实现**匹配/idle/U20（W2b）、支付/抵物/债（W3）；`householdLaborChoices`/`allowIdle` 已撤回，不新增对应状态/命令/seed；不写测试。W1 依赖本批的真实读口，C2 的永远 empty lookup **不得**被当作候选能力完成 |
| **W1（工资前置估值）** | `simos-economy/.../time/ExpectedProfitBook.java`（`expectedWageCostMilli`/`wageProspect`/理由词）；W1 所需只读 helper | 只基于 W2a 的真实 default/posting 读口和 `WagePayerLocationPrecheck`；>1 settlement location 的 payer 候选不可行，不 lookup；不改 `LaborQueueBook` 排序/过滤；不实现支付/抵物/债；不写测试 |
| **W2b（角色级匹配/idle/U20）** | `simos-economy/.../EconomyData.java` 补第 35 `wageCarryRemainders`、`EconomyDayView`/`EconomyBoundaryView`；`change/EconomyChangeSet.java`、`codec/EconomyCodec.java`；新增 `WageCarryKey.java`；`time/ProductionWageBook.java`（carry/U20）；新增 `time/SurvivalOutlook.java`/`SurvivalOutlookEvaluator.java` 及其只读入参值类型（§2.5.1）；`model/EconomyPolicyKey.java`（`SURVIVAL_IDLE_POLICY` 策略键）；`time/LaborQueueBook.java`、`ActivitySelector.java`（仅复用/必要可见性）、`LaborQueueSettlement.java`（角色级 offer、`IdleOption`、生存资格接入）；`EconomyReferenceValidation.java` | **B2**：W3 未启用时真实 `settleOneDayInto` 在任何抽料/写回/进度/经验/实际劳动之前整 tick `wage-settlement-not-implemented`；**只消费 W2a 的 `WagePayerLocationPrecheck`，>1 settlement location 的候选 skip 且不 lookup**；匹配只在只读/dry-run 验证；不实现 W3 支付；不跨区自动招募；不写产品份额分配；不改 app；不写测试；`SurvivalOutlook` 纯只读；`allowIdle`/`householdLaborChoices` 已撤回，不新增对应状态 |
| **W3（支付/抵物/债，含 N-05 debt source 写面）** | `simos-economy/.../time/EconomySettlement.java`（工资结算、同日排除、`capitalizeArrears` WAGE/RENT source 映射）、新增 `time/WageSettlement.java`/`WageSettlementReadout`、`ProductionSettlement.java` 旧 wage 调用面、`TransferReason.java`（`WAGE_PAYMENT`）、`DebtTerms.java`（`wageArrearsDefault()`）、`simos-economy-api/.../api/debt/DebtSource.java`（新增）、`model/DebtContract.java`（加 source）、`time/DebtContractBook.java`（source 入参与不合并）、`change/EconomyChangeSet.java`、`codec/EconomyCodec.java`/债务表往返；**普通贷款/迁移/seed 写入点**：`UnitBorrowHandler.java`、`MoneyMarketSettlement.java`、`MarketSettlement.java`、`MintSettlement.java`、`ModeMigrationSettlement.java`、`LotMigrationBook.java`、`EconomyPayloads.java`、`EconomySeedHandler.java`（各自按 §2.9.1 映射） | **只消费 D4-I 已编译的 `WagePayableInventoryLookup`**；不新增/不实现 `WagePayableInventoryLookup.java`；不修 R2/资产占用（D4）；**不二次扣减**库存（B3）；W1/W2b/W3 共享 `WagePayerLocationPrecheck`，payer 级地点预检先于 lookup；普通贷款 `ORDINARY_LOAN`、迁移保 source、seed 显式 source；不改 AssetKind/POM/XML；不写测试；不直接改账户 Map；与 W2a/W2b/D4-I 共享 economy 文件时严格按 N-08 顺序串行 |
| **W4（tick/Social）** | `simos-app/.../PopulationEconomyTimeParticipant.java`（tick 时序/Social 回写接线）、`EconomyDayStepper.java`/`EconomySettlement.step` 的调用顺序、只读 app 读口 | 不在 app 实现生存判定/报价/匹配/支付/折价公式；不复制 economy 逻辑；不写测试 |
| **W5（独立测试 owner）** | `simos-economy/src/test/**`、`simos-app/src/test/**`、`simos-production/src/test/**`、门禁守卫清单 | 按 §8 判据与 §8.6 数值例写测；含负向/变异；不由实现 owner 写/跑测试 |

### 9.2 文件所有权红线

- 同一文件同一时刻一个 owner；W2a/W1/W2b/W3/W4 不得并行改同一文件。
- 本整合轮已把 D3 对 D2 的最小修正并入 **D2 §0.4**；W 实现 owner 不得再反向改写 D2 规则，D3 不再保留第二套规则正文。`allowIdle`/`householdLaborChoices` 已撤回，不恢复其状态/命令/seed/判据；第 35 组件只能是 `wageCarryRemainders`。
- 不改 `pom.xml`/XML/enforcer；不新增模块依赖（`ProductionRoleId` 按 D2 放 `simos-economy-api`；`WagePayableInventoryLookup` 的地点只读使用既有 `simos-production` contract 的 `ProductionLocation`）。**`WagePayableInventoryLookup.java` 的唯一新增/实现 owner = D4-I；W3 只消费其已编译读口，不新增/不实现该文件**；批次顺序 D4-I→D4-T→W3。
- 不写/不改测试（W5 除外）；不跑 `test`/`verify`（W5/门禁 owner 除外）。
- 不 `git add/commit/stash/checkout/merge/push`；不碰主树。
- 不把“D3 设计已落盘”写成“工资链已实现”。

### 9.3 未做/未验证清单

1. **未实现**：本文只有设计；没有 Java/XML/POM/测试改动，没有编译、没有跑 Maven/测试/仿真，没有声称工资链已实现。
2. **未验证**：U20 参数未标定；不保证收敛、盈利、充分就业或任何均衡。
3. **未修 R2**：共享资产/多实例占用仍具名未修；D3 不接管 D4。
4. **同 tick 吃饱未实现**：当前消费早于 `executeDay`；工资到账只能影响下一 tick 的消费/劳动能力，或本 tick 后续市场轮购买，不声称同 tick 已吃饱。
5. **跨区自动招募未做**：W2b 只限本地市场区；跨区走既有迁移。
6. **旧 `FIXED_MONEY_WAGE` 非名册路径未删**：D3 只覆盖名册工资链；旧路径仍存在。但 U23 要求旧工资欠款不得继续 legacy 生息，W3 接管时必须按 0‰ 收口；D3 不冒充已修。
7. **U23 三项已由用户裁定、但未实现**：欠薪零息、生存条件不从业、可配置抵物折价都只有设计；`allowIdle` 已撤回，本册不再保留其默认值。
8. **多角色 unit cap 的角色 id 优先序是工程默认**：不声称公平；可另行设计。
9. **`ProductionReceipt` 本次 tick participant 明细未落形**：最小修正已并入 **D2 §6.1**；C1/实现 owner 仍需落形与编译验证，D3 不把设计当编译通过。
10. **测试证据为零**：§8 判据全部没有运行记录；W5 完成后才可写“通过”。
11. **D4/可支付库存未实现**：§6.9 只是 D3 的读取契约；D4 设计已落盘但 `WagePayableInventoryLookup` 仍 **未实现**，其唯一新增/实现 owner = **D4-I**，W3 只消费已编译读口，不新增/不实现 `WagePayableInventoryLookup.java`。地点形参固定为已有的 `production.contract.ProductionLocation`。`asset-scope-shared:awaiting-D4` 是 R2 最小边界，不是 D4 完整关闭。
12. **A1/A2/A3/A4/B3/B4 未实现**：本文只统一设计口径，没有改代码；所有正负判据待 W5 独立测试 owner 运行。
13. **SurvivalOutlook 的 O9–O11 未实现**：Social 下一 tick 需求投影适配、当地可达购买价/供给事实装配、既有债务/不可用权益快照都需 W2b/D4 提供只读来源；缺来源时按 §2.5.1 具名失败或保守不折算，不伪造数值口径。

### 9.4 复核命令（只写命令，本次不运行）

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module

# W2a/W1/W2b/W3 编译（实现 owner 只到 compile，不跑 test）
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am

# W5 测试（独立测试 owner）
tools/mvn-lock.sh -pl simos-economy -am test
tools/mvn-lock.sh -pl simos-app -am test

# 最终门禁（写者停手后，主树控制方）
tools/gate.sh full

# 只读核对：不得出现第二套报价/排序/写口；地点读口必须用 ProductionLocation
rg -n "MIN_NET_PER_LABOR_SCALED|wageQuote|ProductionWageDefault|ProductionWagePosting|WAGE_PAYMENT|wageArrearsDefault|WagePayableInventoryLookup|ProductionLocation" simos-economy/src simos-economy-api/src simos-app/src
git status --porcelain=v1
```

### 9.5 落盘状态

- **本轮整合基线**：侧线工作树 HEAD `5f517492`（D3 初稿落盘时的历史基线为 `0128b0cc`）。
- 本轮只改 **D1/D2/D3 设计正文**；未改 status/R2/Java/XML/POM/测试，未跑 Maven/测试/仿真，未 `git add/commit/push/merge/sync`。
- 没有修 R2；没有声称工资链已实现。D4 设计已落盘，§6.9 只是 D3 的读取契约，`WagePayableInventoryLookup` 仍未实现。
- 本轮定点修正仅涉及：抵物参考价收敛为 `LOCAL_BID`/`LOCAL_ASK` 与现有 `MarketTable` 读法、`WagePayableInventoryLookup` 地点改用 `ProductionLocation`、`idleEligible`/`idleChosen` 分离、补 `SurvivalOutlook` 只读契约；均未实现/未验证。
