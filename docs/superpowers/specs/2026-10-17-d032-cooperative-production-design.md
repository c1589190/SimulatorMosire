# D-032 D2 合作生产与分配约束设计书（§16，2026-10-17）

> **性质**：约束设计书的 **D2 分册**，对应重规划 `2026-10-17-d032-production-line-replan.md` §4 Phase D 的 D2。它冻结“多家户、多角色合作生产”在 **economy 侧的真实参与名册、按户预计所得、角色两层分配、资格主体、迁移/合户经验** 上的形状与边界；**不是实现派单书**，也不启动 C2/C3/C4。
>
> **效力与输入（从高到低）**：
> 1. 用户设计原稿 D-032/U13–U23（逐字，§1 全文照录；U22 为执行优先级、U23 为工资链三项裁决）——最高权威；
> 2. D1 共享生产定义形状冻结：`docs/superpowers/specs/2026-10-17-d032-production-definition-v2-freeze.md`（2026-10-17）；
> 3. 重规划 v2 与复核：`docs/superpowers/plans/2026-10-17-d032-production-line-replan.md`、`docs/superpowers/reports/2026-10-17-d032-production-line-replan-v2-review.md`（2026-10-17）；
> 4. 当前代码事实（§3 全部回代码核过，行号为本文落盘时侧线工作树实测）。
>
> **边界**：D2 只冻结“世界必须长成什么样”。D3 的工资报价/调价/支付顺序、D4 的资产地点/占用期/统一占用（R2）仍归各自分册；D2 只定义它们的**接入点与禁止绕过边界**。**本文不实现、不编译、不跑测试、不提交、不同步、不触碰主树。**
>
> **当前工程结论**：C2 的首期能力是 **“有名册的合作生产”**：名册由创世/命令显式建立，单户方案由自动组织路径生成默认名册；**不在 C2 内实现自动招募、逐 tick 工资博弈或自动匹配**——这些属于 D3/W。C2 的验收不得把“自动招募/工资支付已完成”写成通过。
>
> **2026-10-17 U23 / 裁决列局部更正注（当前有效）**：用户 U23 三项（欠薪零息；按下一 tick 生存条件裁决不从业；抵物折价可低于 bid 且可配置）与 D3 预审裁决 A1–A5/B1–B4 直接改到本册的只有“工资岗位 vs 名册”的接入口，已就地更新，不重写整册：
> - **`MONEY_WAGE` 角色不再强制 roster participant**；需求权威 = `ProductionWagePosting.targetLaborMilliPerDay`（执行期）/ `ProductionWageDefault`（新进入候选，按 `(plan, role, marketZone)` 读），实际 worker 由 allocations/receipt 决定（§5.2/§6.5/§7.1）。
> - **`candidateHouseholdsOf` 按角色类型分流**：`PRODUCT_SHARE` 读 roster，`MONEY_WAGE` 读 posting/default + 本地市场区（§4.3）。
> - **§8.2 工资预计**对 `MONEY_WAGE` 改用候选 default / 实际 posting 的 quote，**仍乘 `cycleDays`**；缺 quote ⇒ `wage-quote-missing`，**不按 0**。
> - **D3 旧 §0.3 的五项“待同步修正”已并入本册**（§0.4 逐条登记）；两册不得各留一套。
> - **`allowIdle` 已撤回**（U23）：本册不出现 `allowIdle`/`householdLaborChoices`；不从业资格归 D3 §2.5.1；工资债利率归 economy/D3 §6.5。
>
> **2026-10-17 F-05/F-06/F-07/F-08 定点修正注（当前有效）**：D4-C 必选只读接口 `WagePayableInventoryLookup` + `AssetOccupancyLookup`（含精确签名与 fail-closed 适配器）先于 C2；C2=32、W2a=33/34、W2b=35、D4-I=36/37；共享 economy 文件顺序改为 `C0/C0-A → D4-C → C2 → W2a → W1 → W2b → D4-I → D4-T → W3`。D4 P18d 补跨地点 overcommit 反向判据。
>
> **2026-10-17 第三次定点修正注（N-04/N-08，当前有效）**：§8.2 明确 D2 周期预计不得直接当 D3 下一 tick 生存输入；§13 写死共享 economy 文件 owner 交接顺序 `C0/C0-A → D4-C → C2 → W2a → W1 → W2b → D4-I → D4-T → W3`。
>
> **2026-10-17 D1–D4 独立复核局部修正注（R-02/R-03/R-06/R-09，当前有效）**：本册按 `2026-10-17-d032-d1-d4-design-review.md` 修正：§1 补 U22/U23 原话；D2-I2 改为“每个 `PRODUCT_SHARE` 角色至少一个 participant，`MONEY_WAGE` 可无 participant”；§0.4 的组件号约束改为“D2/D3 只新增 32–35，D4 后续 36/37”，不再有“无第 36”的绝对禁令；§13 增列 C0-A 的 `AssetKind` 词表与旧引用写面。旧文字保留为历史 provenance。

## §0 口径溯源与裁定状态（先核，避免重复问用户）

| 候选裁定 | 已有口径 / 出处 | 本文处置 |
|---|---|---|
| 多户、多角色、同经验条、角色经验可配置 | 用户 D-032/U13–U14（2026-10-17，§1 逐字）；D1 §2.2/§2.5 | **已有裁定**；不再问用户，按 D1 形状落地 |
| 角色产品份额不以下地劳动为条件；工资是地主支出 | 用户 D-032/U15 及更正（2026-10-17）；重规划 v2 §1.1 | **已有裁定**；D2-I5/I8 |
| 工资直接结算、库存不足成债、再生产权益不入活动库存 | 用户 D-032/U16（2026-10-17） | **已有裁定**；支付细节归 D3 |
| 工资费用前置进本户候选收益 | 用户 D-032/U17（2026-10-17）；重规划 v2 §1.1/§4 W1 | **已有裁定**；D2 只定义金额接入点，报价状态归 D3 |
| 地主可报 0/很低；劳动户可不从业 | 用户 D-032/U18–U19（2026-10-17）；U19 允许不从业 | **已有裁定**；匹配/闲置选择归 D3/W。**负收益不预过滤是本设计为落实 U18/U19 的工程默认**，不冒充用户逐字裁定 |
| 缺工快加价、招满慢降价 | 用户 D-032/U20（2026-10-17） | **已有裁定**；参数/实现归 D3 |
| 欠薪不影响择业、禁止预付、先钱后库存数量降序再成债 | 用户 D-032/U21（2026-10-17）；`2026-10-17-d032-tick-wage-feedback-proposal.md` §11 | **已有裁定**；D2 不重问，支付归 D3 |
| 经验增长是否按 10 人/100 人同速 | 重规划 v2 §5：唯一待定项，可由工程按 (c) 可配置、默认按参与强度归一化；“你不特别指定就按此默认走，不需要再答” | **无需用户新裁定**；D2 §9.1 固定分母为家户当日可用劳动，等价于“同参与比例同速” |
| R2 同经营者共享资产池分配 | `2026-10-17-d032-final-independent-review.md` R2；重规划 v2 §3、Phase A/D4 | **不是用户业务裁定**；工程归 D4/A，C2 只 fail-closed 记录限制 |
| 自动招募/工资支付/匹配 | 重规划 v2 Phase C/W；D3 未落盘 | **不属于 D2 用户裁定**；C2 不实现、不冒称通过 |
| 欠薪零息（工资债不得继承 legacy 20‰；区分工资欠款与普通贷款） | **用户 U23**（2026-10-17，逐字见 `2026-10-17-d032-wage-idle-barter-decision.md` §7；D3 §0.1） | **已有裁定**；利率/债务结算归 economy/D3 §2.9/§6.5，D2 不写利率 |
| 按下一 tick 生存条件裁决不从业（撤回 `allowIdle` 缺行默认） | **用户 U23**（同上） | **已有裁定**；资格/选择归 D3 §2.5.1/§4.4（`idleEligible` 与 `idleChosen` 分离）；`allowIdle`/`householdLaborChoices` 已撤回，D2 不写其规则/状态 |
| 抵物折价可更低、可配置、明确模块边界 | **用户 U23**（同上） | **已有裁定**；策略定义归 D1 production、执行归 economy/D3 §6.4；D2 只读 quote/不复制策略 |

**结论**：D2 不需要新增用户业务裁定。U23 三项均为**用户已定**（不再列待裁定）。本分册中所有“控制方建议/工程默认”均已明确标注，用户可随时推翻；未把默认值写成用户已裁定。

### 0.4 D3 旧 §0.3 五项最小修正的合并结果（当前有效；取代 D3 旧 §0.3）

> **状态**：D3 旧 §0.3 曾把下列五项写成“待 D2 owner 同步、D3 以其为准”。2026-10-17 整合后**全部并入本册当前正文**；D3 §0.3 只留指针，不再保留独立规则正文。两册冲突时以本表指向的本册正文为准。

| # | D3 旧 §0.3 修正 | 本册当前有效正文 | 合并后的口径 |
|---|---|---|---|
| 1 | `ProductionReceipt` 给出本次 tick 逐 `(household, role)` 实际劳动 | §6.1 | `laborContributionsMilli` 改为 `Map<ProductionParticipantKey, Long>`（本次 tick；只含 >0）；按户合计只作派生 `laborByHouseholdMilli()`；`ProductionProcess.cycleContributions` 仍按角色**周期累计**，二者同源、不互相替代 |
| 2 | `MONEY_WAGE` 角色不被“每角色至少一个 participant”绑死 | §5.2 / §7.1 / §10.1 | `PRODUCT_SHARE` 角色必须有 participant；`MONEY_WAGE` 角色可无 participant，需求权威 = `ProductionWagePosting.targetLaborMilliPerDay`（新进入候选读 `ProductionWageDefault`）；实际 worker 由 allocations/receipt 决定；**不得伪造 placeholder，不建第二套“名册 worker”权威** |
| 3 | `candidateHouseholdsOf` 按角色类型分流 | §4.3 | `PRODUCT_SHARE` 读 roster 的 `plannedLaborMilliPerDay > 0` participant；`MONEY_WAGE` 读 posting/default + 本地市场区 + 资格/账户规则，**不读 roster**；`laborSources` 仍只是已确认 roster 的派生摘要 |
| 4 | §8.2 工资应收改用 posting 目标 | §8.2 | `MONEY_WAGE` 应收/应付 = `quote(候选 default / 实际 posting) × targetLaborMilliPerDay × cycleDays / 1000`（或预计可匹配劳动 × cycleDays）；**仍乘 `cycleDays`**；缺 quote ⇒ `wage-quote-missing`，不按 0；显式 0 合法 |
| 5 | 角色资格失败整实例拒绝只适用 roster participant | §7.3 | roster 内 `PRODUCT_SHARE` participant 资格失败 ⇒ 整实例本日拒绝；`MONEY_WAGE` 自动候选不合格 ⇒ **候选 skip**（具名），无候选只形成 shortage 驱动 U20 |

**组件号约束（与 D3/D4 一致，当前有效）**：31 为当前基线；本册新增第 32 组件 `productionRosters`；D3 只新增 **33 `productionWageDefaults` / 34 `productionWagePostings` / 35 `wageCarryRemainders`**；D4 后续新增 **36 `assetOccupancies` / 37 `inventoryReservations`**。因此 **D2/D3 不新增 36，但“36”不是永久禁用**，且 36 不得让给已撤回的 `allowIdle`/`householdLaborChoices`。`allowIdle`/`householdLaborChoices` 已撤回（历史项，不新增规则/状态/命令/seed/判据）。

---

## §1 用户原话附录（逐字，不改写、不缩写）

> 来源：`docs/superpowers/specs/2026-10-02-architecture-design-source.md` D-032 节；日期均按用户实际口述日 2026-10-17。
> 控制方对 U14 后附解释、U15 的更正不是用户原话，已用“控制方说明”分开；不混入引文。

> **U13**：「——注意到我们之前讨论的生产方式一直围绕小生产而不是多家户合作的大生产，例如农业，单个佃农产业就同时有地主、中农、佃农等阶层位置，我的建议是，不同阶层积累的生产经验算同一个，收益模型则是由生产方式按比例定，具体调查一下，告诉我你认为应该怎么设计，我修改、确认」

> **U14**：「你的建议很合理，因此在单个生产方式中，每一个阶层的家户参与生产，能获得的不同经验量可以是有所区别的，这个也要支持具体配置；以及，分配我建议按照劳动力*系数来算，例如定义农业生产方式，地主投入n，中农投入10n，佃农投入10n，地主的分配系数是40%，中农是40%，佃农是20%，假设当前tick这个地块这个生产方式总产出1000单位，就按400、400、200分给地主、中农、佃农，这样设计合理吗？」

> **U15**：「不是，就是直接的产品分配需求——666，地主来收租子，你和地主说，你没下地干活，你不许拿，你看地主抽不抽你就完了；以及好像没有考虑现金结算问题，使用什么东西支付生产方式也要定义，例如佃农制就是直接分粮食，雇农制则是在农业生产方式的最底下加一档雇农，然后给作为生产组织者的地主加上随着雇农投入劳动力扩大而扩大的货币结算分成，例如雇农提供n劳动力给m当地市场区货币，没有对应货币再用地主库存里其他东西按顺序结，这个具体结算价格应该让地主自主报价、跟着市场区，问题是这里如何实现地主自主报价？思考调查一下」

> **U16**：「1 这个估算有算法吗？ 2 我不建议搞这么复杂，最好是算出来劳动价格之后直接给——是否选择进行雇农生产是家户自己的事，给钱是地主的事 3 因此也不用算这么复杂，工资库存给不起直接计成债务就行，有库存就给库存，不设置额外的土地生产资料，被用作再生产的生产资料可以视为权益了，不算做可变卖活动库存」

> **U17**：「地主支付这个环节应该算成地主的生产方式选择——家户在选择生产方式并成为地主之前，必须先根据对应生产方式，算在选用的时候，本轮能雇到人的愿给出劳动力价格，然后再把这个价格计入生产预估花费，算出这个家户选什么生产方式；因此口径应当改成不亏本的最低价——要不是能剥削谁雇雇农啊？这样的体系自洽吗？在经济景气、经济不景气的时候这样的机制分别会产生什么效果」

> **U18**：「最低工资在市场逻辑下为什么不能报0或者很低呢，流民/无产者家户有什么办法吗，它必须选一个生产方式，入不敷出是必然，地主是出价的那一方，重新设计一下」

> **U19**：「从0开始报价那完蛋了，但是我想不到其他好办法；这样，给家户同时加一个允许不从业的设定，如果本tick地主家户报价为0，那么无产者家户算了算，发现没收入还得投入劳动力，如果换不到第二tick的口粮，就算不想罢工也无法投入生产，必须要倒闭有能力选地主的家户加工资，这里就是一个动态的市场计算博弈，博弈随着tick增加而进行，有办法自恰吗？」

> **U20**：「对，可以给一个在招不到人的时候非常快乃至累加的加价速度，招到人之后缓慢增大的降价速度，还有其他需要裁决的问题吗？」

> **U21**：「1 不影响 2 不允许，地主优先给钱，没钱了再按库存最多的东西抵」

> **U22**：「我决定先修bug」

> **U23**：「1 肯定不计 2 任何家户只要条件允许（从事任何可从事的生产方式都没法换取下一tick的生存所需）都可以被裁决为不从业 3 折价可以更低一些，总之是可配置项，注意明确模块边界」

控制方说明（非用户原话）：
- U15 更正：工资是**地主/雇主的支出义务**，不是地主的收入；地主的产品份额不以下地劳动为条件。
- U16/U21 的支付顺序与“库存最多”均按原文：先对应货币 → 可支付活动库存按**数量**从多到少 → 剩余成债；再生产权益/已投入资料不在可支付库存中。
- U18/U19 允许劳动户选择零/负收益工作，也允许不从业；不得预过滤所有非正收益项，不得虚构免费闲置。该动态工资博弈的实现归 D3/W，D2 只保留“负收益不得被 D2 的预计所得逻辑过滤”的口径。
- U22 只决定**执行优先级**（先修现有 R2 bug），不是对工资三项的裁定，也不授权提交/同步。
- U23 三项均为用户已定：U23-a 工资欠款 0‰；U23-b 按下一 tick 生存条件裁决不从业（撤回 per-household `allowIdle` 缺行默认）；U23-c 抵物折价可配置且可低于 bid。D2 只采用其名册/工资边界，不重定义利率/折价公式。

---

## §2 范围与非目标

### 2.1 本分册交付（C2 必须能回答）

1. 一个生产实例的**真实参与名册**：哪些家户、在哪个 `ProductionRoleId`、谁是组织者、每户计划劳动、角色内权利权重/配置权重来自哪里；
2. 名册的**持久状态 / ChangeSet / Codec / 命令 / 读口**；
3. 一个名册候选的**按户产品份额预计所得**（含投入负担），并明确“负收益不清除”；工资义务/应收只定义**金额公式与读口**，真实报价状态与正式验收在 D3/W2a（C2 对 wage 角色只做结构/缺报价拒绝、候选读 `ProductionWageDefault`，不冒称工资已结算）；
4. 周期关账时的**两层分配**：先角色池，再角色内按规则拆到户；未分配余数归组织者 residual，不丢货；
5. **角色资格**按位置检查、组织经营者从名册解析、计划级资格仍按组织者检查；
6. **经验增长**按角色系数/基数计算，按 `(家户, 经验条)` 每 tick 只归并一次；迁移/合户复用现有经验合并口径；
7. 与 D3（工资报价/支付）、D4（资产占用/R2）的**接口与失败边界**，使 C2 不会偷偷实现第二套工资/资产权威。

### 2.2 非目标 / 后续分册

- **D3**：工资报价的持久状态、U20 非对称调价、劳动匹配、闲置选择、直付/抵物/成债、付款顺序。D2 只定义预计/实际工资的**金额接入点**和“缺报价不得按 0”的失败语义。
- **D4/A**：资产地点、占用期、跨周期锁定、统一占用分配、R2 关闭。D2 只使用 D4 的占用读口；**C2 不声称 R2 已修**。
- **自动招募/自动匹配**：C2 不实现“地主发布岗位、家户自动应募、逐 tick 竞价”的完整博弈；名册由创世/`economy.SetProductionRoster` 显式建立，或在单角色方案自动组织时生成默认名册。D3/W 才允许在该名册契约上增加报价与匹配。
- **GUI / GM 工具面 / U10 全部写入口**：D2 只定义命令类型与载荷；工具注册、权限、Catalog、前端展示由 C3/U10。
- **文化/宗教 D-033**：不在本分册。
- **旧档兼容**：Alpha 口径，旧档不迁移；本分册只要求同一版本内往返自洽。

---

## §3 现状事实（回代码核过）

| 事实 | 证据（当前侧线工作树） | 对 D2 的影响 |
|---|---|---|
| `EconomyData` 当前是 **31 个 record 组件**（逐行计数 31；历史注释里的 40 是旧编号），生产实例已不在 economy；参与关系没有一等组件 | `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:310-397` | C2 要新增第 32 个名册组件，并同步 ChangeSet/codec/读口 |
| `ProductionEnterprise` 仍是“一个组织”的单位置摘要：`classPositionId`、`laborSources`、`assetSources`、`outputOwnership` | `simos-economy/src/main/java/io/mosire/simos/economy/model/ProductionEnterprise.java:73-85` | 它不能表达同一实例的多个角色/多家户；名册必须是新权威，`laborSources` 只能做派生摘要 |
| `ProductionEnterprise` 的 `laborSources` 当前单户创建路径写入一个家户；迁移/清理路径也会复制该字段 | `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyEnterpriseSettlement.java:774-787`；`EconomySettlement.java:7032-7060` | C2 的单户默认名册可从该路径生成；此后 `laborSources` 作为**参与户全集**的派生摘要（纯收租/零劳动参与户也包含，避免旧债务/迁移消费者漏户）；多角色不得复制多份组织 |
| 预计利润按“一个 unit × 一个家户”试算，物理量已改走 `runtime.probe` | `simos-economy/src/main/java/io/mosire/simos/economy/time/ExpectedProfitBook.java:402-466` | C2 要把“按户分享”加到估价之前，不能把 unit 总利润当本户所得 |
| 劳动队列的 `Offer` 仍以 unit + 单一 household 为粒度 | `simos-economy/src/main/java/io/mosire/simos/economy/time/LaborQueueBook.java:222-315` | C2 需要角色粒度；自动匹配本身归 D3/W |
| 生产日 contribution 汇总仍按 `Map<HouseholdId, Long>`，没有角色 | `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java:4222-4284` | `ProductionExecutionRequest` 与 `ProductionDayRequest.Step` 必须改成参与角色列表 |
| 执行请求的劳动表是 `Map<HouseholdId, Long>`，回执的 cycle contribution 也是单户粒度 | `simos-production/src/main/java/io/mosire/simos/production/contract/ProductionExecutionRequest.java:44-51`、`ProductionDayRequest.java:68-75`、`ProductionReceipt.java:51-67`、`CycleContribution.java:50-51` | 这是 C1 的契约变更点；D1 §2.9 已冻结方向，D2 补齐角色/属性/经验输入 |
| `HouseholdLaborCommitment` 的 id 与记录没有角色；同户同 unit 多角色会撞 id | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/labor/HouseholdLaborCommitment.java:52-60,127-162` | C2 必须给生产承诺加角色并改 id 组成，否则同户多角色静默丢配额 |
| `ProductionExperience.accrueFrom` 以 `(家户, 经验条)` 汇总劳动后一次换算；没有角色系数输入 | `simos-production/src/main/java/io/mosire/simos/production/ProductionExperience.java:87-117` | D2 要定义按角色先把精确增长分子归并到 track，再一次性写回 |
| 经验合户已按“来源∪目标、缺行=0、人口加权、永久解锁”实现 | `simos-production/src/main/java/io/mosire/simos/production/ProductionData.java:426-485` | D2 直接复用，不新写第二套合户公式 |
| 旧产出分配仍是 `ProductionRules` + `CompensationRule` + `ProductionSettlement.settle` | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/relation/ProductionRules.java:89-95`、`CompensationRule.java:60-69`、`simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionSettlement.java:109-254` | 两套产出分配并存会双记；C2 必须明确旧 `rules` 不再作为名册方案的产出分配权威 |
| 资产份额 `OwnershipStake` 已有 `industry/owner/operator/quantity/kind`，但无地点/占用期 | `simos-economy/src/main/java/io/mosire/simos/economy/model/OwnershipStake.java:35-42` | D2 的 `BY_RIGHTS` 只引用份额、不复制数量；地点/占用期归 D4 |
| D1 **旧稿曾**把 `ProductionRoleId` 写在 `production.contract`（当前 D1 §2.1 已改为 `simos-economy-api`）；`HouseholdLaborCommitment` 在 `economy-api`，后者不能依赖 production | `docs/superpowers/specs/2026-10-17-d032-production-definition-v2-freeze.md` §2.1；`simos-economy-api/pom.xml` 依赖面 | **必须把稳定角色 ID 放到 `economy-api`**，否则为携带角色会形成 Maven 环（见 §5.1） |
| 当前 `ProductionSettlement` 仍从 `ProductionRules.rules` 读取产出/工资/地租规则 | `simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionSettlement.java:245-254`；`RegimeRelations.java:583-683` | C2 若同时保留旧规则会与角色份额双分；D2 规定名册方案 `rules` 必须为空，D3 再接管货币工资 |

---

## §4 所有权总则与数据流

### 4.1 唯一权威

| 事实 | 唯一权威 | 读取方 |
|---|---|---|
| 角色定义：角色 id、名称、阶层引用、角色份额、角色内拆分规则、标准劳动、经验增长配置、角色条件、支付形态 | production `IndustryDefinitionView.roles`（D1 冻结） | economy 只读 `production.contract` |
| 共享生产定义、实例、进度、实际投入、经验/解锁、效率余数 | production | economy 经 `ProductionRuntime` / 只读投影 |
| **真实参与名册**：哪个 unit、哪些家户、哪些 role、谁 organizer、计划劳动、`BY_RIGHTS` 引用、`CONFIGURED_WEIGHTS` 权重 | economy 新增 `EconomyData.productionRosters` | production 只从执行请求接收，不持久持有名册 |
| 每 tick/每周期实际劳动承诺 | economy `allocations`（`HouseholdLaborCommitment`，加角色） | production 从执行请求接收 |
| 资产份额与数量 | economy `assetShares`（D4 前） | 名册只引用 id；D4 占用来回填 |
| 市场价、需求、按户估价、工资报价/支付、债务 | economy（D3 负责报价/支付字段） | 不得由 production 计算货币收益/支付 |
| 账户/库存的实际落账 | actor 原所有者；economy 生成 intents | production 不写账户/人口 |

**硬边界**：名册不是第二份定义；角色份额/经验系数只来自 production。economy 不得把名册的 role 文本反推出 I/O、工资或经验系数。

### 4.2 一个生产周期的数据流

```text
Definition（production）
  + Roster（economy，unit × household × role）
  + 当日资源/市场/属性事实（economy/app）
        ↓
production.probe（无副作用；organizer 条件 + 总劳动/占用/投入）
        ↓
economy 按户估价（产品份额 + 工资应收/应付 - 投入负担）
        ↓
（C2：名册已由创世/命令确定；D3/W 才做自动报价与匹配）
        ↓
统一分配劳动/资产/投入（D4 替换资产那一路；劳动仍按全局预算）
        ↓
production.executeDay（按最终投入；每实例只算一份毛产）
        ↓
revision 内：production receipt + economy 名册/分配/账户 intents 同批提交
        ↓
周期关账：现有 FEED+DEPRECIATION 损耗先扣（毛产 → net）
        ↓
RoleDistribution.allocate（net 池 → 角色池 → 角色内 → 户）
        ↓
按户 credit 实物；未分配余数归 organizer residual
        ↓
D3 工资支付（货币 → 库存数量降序 → 债务）
```

### 4.3 与 `ProductionEnterprise` / `ProductionRules` 的关系

- `ProductionRoster` 是“谁以什么角色参与”的**唯一权威**。
- `ProductionEnterprise` 保留为“组织/经营摘要”（mode、classPosition、assetSources、inputSources、outputOwnership），但：
  - `laborSources` 只能由名册派生（按 `household.value()` 排序、去重、等于**名册全部 participant 的 household 集合**，包括纯收租/零计划劳动户）；C2 不得提供独立写 `laborSources` 的入口；不一致 ⇒ `enterprise-roster-mismatch` 契约故障；
  - `classPositionId` 只表达**组织者**的当前阶层位置，不表示所有参与者同阶层；多角色不得据此推导角色；
  - `outputOwnership` 必须是 `Payee.ToActor(organizerActor)`（字段类型是 `Payee`，不是 `ActorRef`）；`ProductionRules.residualOwner` 也必须等于 organizer actor，二者不一致是契约故障；
  - `ProductionRules.operator` 必须等于 unit.operator 与 organizer actor；`inputSupplier` 只支持 `Payee.ToActor`，且必须解析到 organizer 或名册内某户；`Payee.ToCohort` 或其他形态 ⇒ `input-supplier-unsupported`。
  - 旧消费者 `DebtPartyResolver.resolveViaEnterprises`、`ModeMigrationSettlement`、`EconomyClearRegionHandler`、`ApiViews` 若读 `laborSources`，C2 继续以“名册全户派生”语义供数；不需要为纯收租户另开字段，但**不得**再由这些路径反向写 `laborSources`。
  - `LaborQueueSettlement.candidateHouseholdsOf` **不得**把 `laborSources` 当劳动候选全集，且必须**按角色类型分流**（D3 §0.4 #3，当前有效）：
    - `PRODUCT_SHARE` 角色：读 `ProductionRoster`，只选该角色 `plannedLaborMilliPerDay > 0` 的 participant；纯收租/零计划劳动户不能进入自动劳动匹配；
    - `MONEY_WAGE` 角色：**不读 roster**，由 `ProductionWagePosting`（执行期）或 `ProductionWageDefault`（新进入候选，键 `(plan, role, marketZone)`）+ 本地市场区 + 资格/账户/劳动规则产生候选；
    - `laborSources` 始终只是“已确认 roster 全户”的派生摘要，**不得**当作工资工人的全集。该消费面的改写归 C2/W2b，旧注释不得继续冒充。
- **旧 `CompensationRule` 产出分配路径**：对名册方案，C2 的分配只走 `RoleDistribution`；`ProductionRules.rules` 必须为空。若名册方案的 `rules` 非空，C2 具名拒绝 `legacy-compensation-rules-present`，不得“先旧规则再角色分配”双记。D3 实现工资后可以删除/替换该字段；D2 不保留旧产出规则作为兼容。
- `ProductionSettlement` 的产出/地租/给养分腿在 C2 由角色分配替代；C2 可以保留其纯函数供其他非名册调用，但**名册方案不得调用**。这条是 C2 的删除面，不是可选项。
- **创世迁移**：`EconomySeeder` / `RegimeRelations` 里现有的 `OUTPUT_SHARE` / `FIXED_IN_KIND_*` / `FIXED_MONEY_*` 规则必须在 C0/C3 的 seed 重写中落成角色（地主/佃农/雇农/经营者等）；C2 不保留“旧规则 + 新名册”双轨，也不承诺旧规则数值逐值保持。P1 的逐值等价只覆盖“无旧规则的单角色 plan”。逐 `RuleType` 的处置表：

| 旧规则 | D2 处置 | 落点 |
|---|---|---|
| `SELF_RETENTION` | 无需角色；未分配/剩余归 organizer residual | organizer |
| `OUTPUT_SHARE`（`GROSS_OUTPUT`/`NET_AFTER_INPUTS`/`OPERATOR_SURPLUS`） | 改成 `RolePayment.PRODUCT_SHARE` 角色；`ratePerMille` → `sharePerMille`；`Weight.LABOR_AMOUNT` → `BY_RECOGNIZED_LABOR`，`Weight.NONE` → `EQUAL`，`Weight.ASSET_QUANTITY` → `BY_RIGHTS`（需有真实 owner 份额） | C0 definition + C3 seed |
| `FIXED_IN_KIND_PER_LABOR` | **不可表示为 D1 现有角色支付形态**；C2 不迁移，seed 必须删掉或另开分册加固定实物工资类型 | C3/后续 |
| `FIXED_IN_KIND_RENT` | **不可表示为 D1 现有角色支付形态**；改成 PRODUCT_SHARE 或另开分册 | C3/后续 |
| `FIXED_MONEY_WAGE` | 改成 `RolePayment.MONEY_WAGE` 角色；固定额 → D3 的固定 quote（按实际劳动/周期结算） | D3/W2a |
| `FIXED_MONEY_RENT` | D1 没有“货币地租”角色类型；改成 PRODUCT_SHARE 或另开分册 | C3/后续 |

- 任何“不可表示”的旧规则出现在名册方案的 seed/创世里 ⇒ `legacy-rule-not-representable:<type>`，C3 必须显式删除/重设计，不得静默丢规则或把它塞进 `PRODUCT_SHARE`。

---

## §5 持久状态与公开契约

### 5.1 `ProductionRoleId` 的位置（D2 对 D1 的必要修正）

D1 **旧稿曾**把 `ProductionRoleId` 放在 `production.contract`（当前 D1 §2.1 已改为 `simos-economy-api`）。D2 发现 `HouseholdLaborCommitment` 在 `simos-economy-api`，而 `simos-economy-api` **不能依赖** production（`production → economy-api` 已是既有边，反向会成环）。故 C0 落形前必须做这一处局部修正（历史说明保留）：

- 新稳定 ID：`io.mosire.simos.economy.api.production.ProductionRoleId`（`simos-economy-api`），形如非空白字符串包装，构造期拒绝空白；为地址/配额 id 安全，角色 id 也不得含 `.`（与 `OwnershipStake.idOf` 同款约束）；
- `production.contract.ProductionRoleDefinition`、`RoleDistribution` 等从 economy-api import 该 ID；
- `HouseholdLaborCommitment`、`ProductionRoster` 等 economy 类型直接使用同一 ID；
- 不复制第二个“role key 字符串”字段，不在 economy 侧用 `String` 猜角色。

这是**工程必要修正**，不改变 D1 的角色语义；D1 文件在 C0 落形时必须同步这一行，否则以本分册为准。

### 5.2 `ProductionRoster` / `ProductionParticipant`

新增 economy 持久值类型（建议放 `simos-economy/.../model/ProductionRoster.java`）：

```java
record ProductionRoster(
    ProductionUnitId unit,
    IndustryId plan,
    List<ProductionParticipant> participants)

record ProductionParticipant(
    HouseholdId household,
    ProductionRoleId role,
    boolean organizer,
    long plannedLaborMilliPerDay,
    List<AssetShareId> rightsStakes,
    long configuredWeight)
```

字段语义：

- `unit`：生产实例稳定 ID；名册表的键必须等于它。
- `plan`：实例引用的方案 ID；必须与 production 实例的 plan 一致（跨切片校验）。
- `participants`：非空、保序不可变、逐项非 null；`(household, role)` 不允许重复；同一户可在多个角色出现（例如地主兼经营者），但角色条目不同。
- `organizer`：整个名册**恰好一个 true**；组织者是名册解析出的主体，不是从 `ProductionEnterprise` 另猜一个。
- `plannedLaborMilliPerDay`：该户在该角色上的**计划劳动/日**（毫小时/日；与 `HouseholdEconomy.laborMilli`、`HouseholdLaborCommitment.laborMilli` 同量纲），≥ 0；它是预计所得的输入，不是实际劳动权威；实际劳动只看 allocations/receipt。
- **`MONEY_WAGE` 角色例外（U23/A3，当前有效）**：`MONEY_WAGE` 角色的需求权威**不是** roster 的 `plannedLaborMilliPerDay`，而是执行期 `ProductionWagePosting.targetLaborMilliPerDay` / 新进入候选的 `ProductionWageDefault.initialTargetLaborMilliPerDay`。名册可以完全没有 `MONEY_WAGE` participant；若 seed/命令显式写了，它只作“已确认/历史读数”，**不得**创造第二个工人集合、第二份工资或第二份 unit cap。实际 worker 一律由 allocations/receipt 决定。
- `rightsStakes`：只在角色 `intraRoleSplit == BY_RIGHTS` 时使用；只存 `AssetShareId` 引用，不复制 `quantity`；同一份额不得被两个 participant/role 重复引用；权威数量仍从 `assetShares` 当刻读取。
- **量纲统一（D2 工程裁定）**：`laborMilli` / `plannedLaborMilliPerDay` / `householdLaborBudgetMilli` / `HouseholdLaborCommitment.laborMilli` 一律按 **毫小时/日**；`standardLaborMilliPerCycle` 按 **毫小时/周期**；工资 quote 按 **毫货币 / 1000 毫小时（= 毫货币/小时）**；工资 = `quote × laborMilli / 1000`。现有代码注释中“千分劳动·日”的旧措辞必须在 C2 统一，不得把毫小时与千分劳动·日混算。
- `configuredWeight`：只在 `CONFIGURED_WEIGHTS` 时使用，≥ 0；0 = 该户不参与该角色池；角色内至少一项 > 0，否则该角色池进未分配余数。其他规则必须为 0（防止“看起来配了权重但没人读”）。

构造期只做**不依赖 production 定义**的校验（null、非空、键、organizer 唯一、非负、rightsStakes 去重、冻结）。涉及 role 存在性、角色规则、份额合计、资格、owner 的校验放 §9 的 `EconomyReferenceValidation`。

### 5.3 `EconomyData` / ChangeSet / Codec / 读口

- `EconomyData` 新增第 **32** 个组件：`Map<ProductionUnitId, ProductionRoster> productionRosters`；键 == 值内 `unit`；空表合法（= 没有任何名册）。
- 新增 `withProductionRoster` / `withProductionRosters` / `withoutProductionRoster`，逐组件写口，不绕过 ChangeSet。
- `EconomyChangeSet` 新增 `FieldDelta<ProductionRoster> productionRosters`，`between`/`apply`/`applyTo`/`isEmpty` 全接。
- `EconomyCodec` 读写该组件；Jackson 缺键/unknown 键按本仓 Alpha 口径处理，但**同一版本写出的 JSON 必须逐字段往返**。
- `EconomyDayView` 新增 `default Map<ProductionUnitId, ProductionRoster> productionRosters()`；`EconomyData` 直接实现，`WorkingDayView` 工作副本优先。
- `EconomyBoundaryView` 增加只读访问器，供 app/readout 使用。
- 往返守卫：`EconomyRoundTripTest` 的反射组件计数与 JSON 用例由**独立测试 owner** 在 C4 更新，不由实现 owner 顺手改断言。

### 5.4 `economy.SetProductionRoster` 命令

```json
{
  "unit": "unit-farm@0_0-hh-1",
  "participants": [
    {
      "household": "hh-landlord",
      "role": "landlord",
      "organizer": true,
      "plannedLaborMilliPerDay": 0,
      "rightsStakes": [],
      "configuredWeight": 0
    },
    {
      "household": "hh-tenant",
      "role": "tenant",
      "organizer": false,
      "plannedLaborMilliPerDay": 500,
      "rightsStakes": [],
      "configuredWeight": 0
    }
  ]
}
```

语义：

- **整表替换**：`unit` 的旧名册整体替换；`participants: []` = 清除该 unit 名册（等价 `withoutProductionRoster`）；不做 patch 语义，避免“少传一个角色 = 静默保留旧人”。
- `plan` 不由载荷给，由当前 production 实例读出；载荷给 plan 只会制造第二拼写点，故不接受。
- 命令类型字面量：`economy.SetProductionRoster`；GM-only；不进 DM 权限面。
- 业务拒绝（INFO、零变更）：`unit-unknown`、`roster-locked`、`role-unknown`、`organizer-missing`、`organizer-multiple`、`role-unfilled`（**仅 `PRODUCT_SHARE` 角色无 participant**）、`participant-duplicate`、`rights-not-owned`、`planned-labor-negative`、`configured-weight-invalid` 等；`MONEY_WAGE` 角色空 participant 不是 `role-unfilled`，缺 target 归 `wage-target-missing`。具体稳定词表见 §11。
- 成功：`HandlerOutcome.Applied(EconomyChangeSet)`，记录 INFO（unit、plan、participants、roles、organizer、replaced=true）。
- **单户自动名册**：`EconomyEnterpriseSettlement` / `EconomyEntrySettlement` 创建**单角色且 `ProductionRules.rules` 为空**的 plan 的 unit 时，先算该户 `remaining = household.laborMilli − Σ现有名册.plannedLaborMilliPerDay`；仅当 `remaining > 0`，同批写入一条默认名册：organizer、`role = plan.roles.get(0).id()`（D1 默认单角色时即 `operator`）、`plannedLaborMilliPerDay = min(remaining, 若 standardLabor>0 则 standardLabor/cycleDays 否则 remaining)`、rightsStakes 空、configuredWeight 0。多角色 plan、已有旧规则、或 `remaining <= 0` ⇒ 不做自动名册，记录具名 shortage `roster-required`；不得按角色各建一份组织。该规则允许同一户有多个 unit，只要计划劳动总和仍在预算内。
- `production.CreateInstance` 的 GM 组合调用：多角色 plan 必须与 `economy.SetProductionRoster` 在同一 `submitBatch` 原子批内；只建实例不建名册 = 后续执行 `roster-missing`，不得半成功。

### 5.5 `economy.Seed` 的名册键

`economy.Seed` 载荷新增顶层 `productionRosters: [ ... ]`，每项与 §5.4 同形。C3 的创世器必须：

1. 先确保 production 实例已存在（同一 revision/同一批的 production seed 或创建请求）；
2. 再写名册；
3. 单户 plan 可以由自动路径补默认名册，但 seed 若显式给名册则以其为准；
4. 多角色 plan 没有显式名册 ⇒ 创世失败（具名 `seed-roster-missing:<unit>`），不静默生成单户。

### 5.6 `HouseholdLaborCommitment` 增加生产角色

同户同 unit 多角色若只按 `(unit, group, household)` 拼 id 会键撞车；C2 必须改：

```java
record HouseholdLaborCommitment(
    LaborAllocationId id,
    PeopleLotId group,
    HouseholdId household,
    ActorRef actor,
    String activity,              // unit id
    long laborMilli,
    long period,
    LaborCommitmentKind kind,
    Optional<ProductionRoleId> productionRole) {}
```

- `kind == PRODUCTION` ⇔ `productionRole.isPresent()`；`GOV_SERVICE` ⇔ empty；其余组合构造期拒绝。
- 生产承诺 id 改为 `idOfProduction(unit, role, group, household)`，角色段进入 id；`GOV_SERVICE` 仍用 `idOfGovService(unit, group, household)`（或保留旧名，但不得与生产 id 混用）。
- 所有复制/缩放/迁移点必须显式带过 `productionRole`；漏带 = 多角色静默合并，C2 必须用构造器/测试堵死。
- 该字段跨 economy-api 与 economy 实现，归 C2 文件所有权；C1 只消费 `ProductionExecutionRequest` 里的角色列表。

---

## §6 生产契约变更（C1，D2 要求）

### 6.1 参与角色列表取代单户劳动表

`ProductionExecutionRequest` 与 `ProductionDayRequest.Step` 的 `Map<HouseholdId, Long> laborContributionsMilli` 改为：

```java
record ParticipantContribution(
    HouseholdId household,
    ProductionRoleId role,
    long laborMilli) {}
```

- 列表保序不可变；`(household, role)` 唯一；逐项 > 0；同一户可多角色；`laborMilli` 沿用 `HouseholdEconomy.laborMilli` 的**毫小时/日**单位（D2 统一口径；旧注释里的“千分劳动·日”是历史措辞，C2 必须改注释/夹具，不得混算）。
- `totalLaborMilli()` 由引擎/值类型计算，不另存。
- `ProductionReceipt.cycleContributions` 改为 `Map<ProductionParticipantKey, CycleContribution>`，`CycleContribution` 增加 `ProductionRoleId role`；同一户同角色多次出工累计，不同角色分行。
- **实例侧也要保留角色**：`ProductionProcess.cycleContributions` 由 `Map<HouseholdId, CycleContribution>` 改为 `Map<ProductionParticipantKey, CycleContribution>`；`ProductionSnapshot`/`ProductionCodec`/`ProductionChangeSet` 同步。否则 `EconomySettlement` 按户 merge 后，后续分配/经验都拿不到 role（这是 D2 的必改点，不是只改 Request/Receipt）。
- `ProductionReceipt.laborContributionsMilli` **改为 `Map<ProductionParticipantKey, Long>`（本次 tick；只含 >0 项）**，不得成为第二权威；按户合计只作派生方法 `laborByHouseholdMilli()`。总经验/分配/**W3 due** 以本次 tick 的 role 明细为准；`ProductionProcess.cycleContributions` 继续按角色**周期累计**，二者同源、不互相替代（D3 §0.4 #1，当前有效）。
- `ProductionReceipt.experienceGrowth` 仍按 `(household, track)` 汇总；另加可审计的 `experienceGrowthLines`（household × role × track × 增量），汇总值必须逐项等于持久化 `householdExperience` 的本 tick 增量。

### 6.2 逐参与户属性事实与劳动预算

`ProductionExecutionRequest` 新增（与 D1 §2.9 的“只变参与表”兼容）：

```java
Map<HouseholdId, ExternalAttributeFacts> participantAttributes
Map<HouseholdId, Long> householdLaborBudgetMilli   // 当日可用劳动预算
Map<HouseholdId, Long> managementLaborMilli       // 仅 countsManagement=true 的角色需要
```

- 计划级 `IndustryDefinitionView.availability` 的主体 = organizer 家户；用其 `participantAttributes` + production 自己持有的经验/解锁判。
- 角色级 `roles[].conditions` 的主体 = 该角色每个参与户；`MinimumExperience` 读 production 的 `experienceOf(household, track)`，`ExternalAttribute` 读该户 facts；缺 facts ⇒ fail-closed。
- `householdLaborBudgetMilli` 只服务 D2 的归一化经验增长；不是第二份劳动配额，实际分配仍由 economy 的 allocation 表/全局预算判。
- `countsManagement=true` 且 `managementLaborMilli` 缺失 ⇒ 业务拒绝 `management-labor-missing:<role>:<household>`，不得按 0 静默。
- `ProductionDayRequest.Step` 必须逐字段镜像上述新增输入（`laborContributions`（`List<ParticipantContribution>`）、`participantAttributes`、`householdLaborBudgetMilli`、`managementLaborMilli`）；`EconomySettlement.driveProductionDay` 当前在 `EconomySettlement.java:4268-4284` 把单一 `Map<HouseholdId,Long>` 和 `ExternalAttributeFacts.EMPTY` 直接塞进 Step，C2 必须改成从名册/allocations/属性桥逐户取值，不再用 `EMPTY` 兜底。

### 6.3 纯分配函数 `RoleDistribution`

在 `production.contract` 新增纯函数（无状态、无账户、无市场价）：

```java
record ProductionParticipantKey(HouseholdId household, ProductionRoleId role) {}
record RoleDistributionParticipant(
    HouseholdId household, ProductionRoleId role, long configuredWeight) {}
record RoleDistributionRequest(
    IndustryDefinitionView definition,
    List<RoleDistributionParticipant> participants,
    Map<ProductionParticipantKey, Long> recognizedLaborMilli,
    Map<ProductionParticipantKey, Long> rightsWeights,
    Map<CommodityId, Long> distributablePools) {}
record RoleDistributionResult(
    Map<ProductionParticipantKey, Map<CommodityId, Long>> shares,
    Map<CommodityId, Long> unallocated,
    Map<CommodityId, Long> poolTotal) {}
```

`RoleDistribution.allocate(request)` 的职责只有算术：

1. 对每个 `PRODUCT_SHARE` 角色，按 `sharePerMille` 从每个**可分配净池**（`distributablePools`，即 gross − FEED − DEPRECIATION 后的 net）切角色池；`MONEY_WAGE` 角色的角色池为 0（其 `sharePerMille` 必须为 0，见 §7.1）。角色池/余数也在本函数内一次算完，economy 不另写第二套角色池算术。
2. 角色内按 `intraRoleSplit` 取权重：`BY_RECOGNIZED_LABOR` 用 `recognizedLaborMilli`；`BY_RIGHTS` 用 `rightsWeights`；`EQUAL` 每户 1；`CONFIGURED_WEIGHTS` 用 `configuredWeight`。
3. 最大余数法：逐商品、逐角色，先 `floor(pool × weight / Σweight)`，再按余数降序、`household.value()` 升序补足；权重和为 0 ⇒ 该商品该角色不拆，记入 `unallocated`。
4. 角色池有两类余数，均显式处理：① **份额余数**（`1000 − Σrole.sharePerMille` 对应的 `net × 余份 / 1000`）；② **整数除法余数**（逐角色 `net × share` 除以 1000 后剩下的个位）。`roleShareRemainderRule == TO_LAST_ROLE` ⇒ 两类余数都加到最后**一个 PRODUCT_SHARE 角色**；`UNALLOCATED` ⇒ 两类余数都进 `unallocated`；零权重角色池也进 `unallocated`；不得静默丢货。
5. 总守恒：`Σ shares + unallocated == poolTotal`（逐商品）；`unallocated` 由 economy 归 organizer residual，见 §8.3。
6. 任何份额合计 > 1000、未知角色、重复参与者、负数权重、`MONEY_WAGE` 角色 nonzero share ⇒ 契约异常/具名拒绝；不在纯函数内静默修正。

该函数是角色内拆分的**唯一拼写点**；economy 的预计与实付都调它，不得各写一套最大余数。

### 6.4 角色 ID 与体验条依赖

`RoleDistribution` 只依赖 `economy-api` 的 `ProductionRoleId`、`social-api` 的 `HouseholdId`、`economy-api` 的 `CommodityId` 与 production 定义；不 import economy 实现。`ExperienceTrackId` 仍由 production.contract 提供；economy 通过 D1 的 `IndustryDefinitionView.experience()` / `roles[].experienceGrowth()` 读取，不复制经验系数。

### 6.5 D2/D3 工资报价读口（只定边界，不建 D3 状态）

C2 不持久化工资报价；D3 才拥有报价状态。为让 C2 编译并提供 W2a/W1 的最小读口，定义最小只读值：

```java
record ProductionWageQuote(
    CurrencyId currency,
    long wagePerLaborMilli) {}   // >= 0；0 是合法报价，缺报价用 Optional.empty()

interface ProductionWageQuoteLookup {
  Optional<ProductionWageQuote> quoteFor(
      ProductionUnitId unit, ProductionRoleId role);
}
```

- C2 的默认 lookup = 永远 empty；含 MONEY_WAGE 角色的候选得到 `wage-quote-missing:<role>`。这是 **C2 边界**，不是“工资岗位已可估价”。
- **W2a 落真实实现（A3/B1）**：执行期从 `productionWagePostings` 读；**实例存在前的候选/新进入者**从候选级 `ProductionWageDefault` 读（键 `(IndustryId plan, ProductionRoleId role, MarketZoneId zone)`，含 `initialWagePerLaborMilli`/`initialTargetLaborMilliPerDay`，D3 §2.2.1）。选中后由同一合法 Revision 创建实例并把 default 物化为 posting。`ProductionWagePosting` 仍是**执行期唯一实际报价权威**；default **不是**第二份执行报价，也不参与已存在实例的匹配/支付。
- `wagePerLaborMilli` = **毫货币 / 1000 毫小时（= 毫货币/小时）**，所以工资 = `quote × laborMilli / 1000`。
- **候选/采用比较窗口 = `definition.cycleDays()`（A1）**：预计工资 = `quote.wagePerLaborMilli() × plannedLaborMilliPerDay × cycleDays / 1000`（对 MONEY_WAGE 用 default/posting 的 target × cycleDays）；产品份额值、投入负担、劳动分母同窗口。**实际 W3 due 按本次 tick 实际 role laborMilli，不乘 `cycleDays`**：`quote × 实际 tick laborMilli / 1000`。例：quote=1000（=1 货币/小时）、实际 5,000 mh（5 小时）⇒ 5,000 毫货币（=5 货币）。该公式在 D2 只用于预计/读数，实际支付顺序/抵物/债务在 D3。
- 不得用 `Map<role,Long>` 裸表绕过该读口，也不得把缺报价当 0；**缺目标**（既无 default 也无实例 posting 给 target）⇒ `wage-target-missing`，不得静默 0 当“没有岗位”。

### 6.6 Phase M 前置：`production.RebindInstanceOperator`

组织者合户不能只改名册。Phase M0（新增，排在 M 主批次之前）必须补一条 production 命令：

```json
{"unit":"unit-farm@0_0-hh-a","fromOperator":{"kind":"HOUSEHOLD","id":"hh-a"},
 "toOperator":{"kind":"HOUSEHOLD","id":"hh-b"}}
```

- 前置：实例存在；`progressDays == 0`（周期边界）；当前 `unit.operator == fromOperator`；`toOperator` 是 HOUSEHOLD；economy 当前名册的 organizer 也是 `fromOperator` household。
- 成功：production 改 `ProductionProcess.operator` 并返回 production ChangeSet；同一 `submitBatch` 再更新 economy 名册 organizer/participant、`ProductionEnterprise.organizer`、`ProductionEnterprise.classPositionId`（取 to 的当前阶层）、`ProductionEnterprise.outputOwnership = Payee.ToActor(to)`、`ProductionEnterprise.laborSources`（按更新后的名册重新派生）、`ProductionEnterprise.inputSources`（若含 ToActor(from) 则替换为 to）、`ProductionRules.operator`、`ProductionRules.residualOwner = to`、`ProductionRules.inputSupplier`（若原为 ToActor(from) 则改 to），以及 `assetShares.operator == fromOperator` 的份额（D4 规则）。
- 失败（INFO、零变更）：`unit-unknown`、`operator-mismatch`、`cycle-open`、`organizer-actor-mismatch`。
- 该命令不在 C1/C2 首期实现；没有 M0 时 organizer 合户一律 `organizer-merge-requires-rebind`，不得半改。

---

## §7 角色、组织主体与资格

### 7.1 角色定义/名册的交叉校验

在 `EconomyReferenceValidation` 新增 `validateProductionRosters(...)`，用当刻 production 只读投影 + economy 候选态：

**定义级（`production.UpsertIndustry` / `production.Seed` 注册时）**：

- `roles` 非空、id 唯一、份额逐值 `[0,1000]`、合计 ≤ 1000；
- 至少一个 `PRODUCT_SHARE` 角色 `sharePerMille > 0`（ organizer 必须有可归的 residual）；
- `RolePayment.MONEY_WAGE` ⇒ `sharePerMille == 0` 且 `standardLaborMilliPerCycle > 0`；
- `RolePayment.PRODUCT_SHARE` ⇒ `sharePerMille > 0`（0 份额的角色没有支付物，不允许）；
- `conditions` 逐项非 null；`roleShareRemainderRule` 非 null。

**名册级（每次候选 revision）**：

- `unit` 存在，且 production 实例的 plan == roster.plan；
- 角色 id 全部存在于 plan.roles；**每个 `PRODUCT_SHARE` 定义角色至少一个 participant**（C2 的有名册合作是“已确认的周期承诺”，不是 D3 的公开岗位市场）。`MONEY_WAGE` 角色**可以没有 participant**：需求由 `ProductionWagePosting`/`ProductionWageDefault` 表达，实际 worker 由 allocations/receipt 决定；不得为工资角色伪造 placeholder household，也不得把空工资角色写进“名册 worker”权威。可选 `PRODUCT_SHARE` 角色应通过 plan 版本表达；
- `(household, role)` 唯一；
- 恰好一个 organizer；organizer 的 role 必须是 `PRODUCT_SHARE`；
- `unit.operator` 必须是 organizer 的 household actor（`HouseholdActors.of(organizerHousehold)`）；非 household operator / 解析失败 ⇒ `organizer-actor-mismatch`；
- `plannedLaborMilliPerDay`：`PRODUCT_SHARE` 或 `standardLaborPerCycle > 0` 的非工资角色至少一名 participant > 0；纯收租/非劳动角色可为 0；`MONEY_WAGE` 角色不要求 roster participant（目标由 posting/default 校验，缺 ⇒ `wage-target-missing`）；
- 每户名册计划劳动之和 ≤ 该户当刻 `HouseholdEconomy.laborMilli`（当日预算；同一户多个 unit/roles 必须合并检查）；超出 ⇒ `household-labor-overcommitted`；
- `BY_RIGHTS`：C2 只支持**所有者侧**权利：每个 `rightsStake` 必须存在、`industry == plan`、`owner == HouseholdActors.of(household)`；不得跨 participant 重复引用。`operator` 侧的 use-right/租佃占用是 D4 的经营占用，不作为 C2 角色分配权重；若将来需要按 operator 侧权利分配，另立 `rightsBasis`，不在 C2 偷用 `operator` 字段；
- `CONFIGURED_WEIGHTS`：每个 participant 的 `configuredWeight` 只在该规则下允许非零，且角色内至少一项 > 0；
- `EQUAL/BY_RECOGNIZED_LABOR`：`configuredWeight`、`rightsStakes` 必须为空/0（避免“配了但不读”的假配置）。

### 7.2 组织主体解析

- 名册是 organizer 的**唯一来源**；`ProductionEnterprise.organizer`（字段就是这个名字）、`ProductionRules.operator`、`ProductionCreationRequest.operator`、`ProductionProcess.operator` 必须与 organizer household actor 一致。
- C2 只支持 organizer 为 `ActorKind.HOUSEHOLD` 的实例；若将来引入组织 actor，必须另立“组织主体名册”，不能把 `ESTATE/WORKSHOP` 强行塞进 household 名册。C2 遇到非 household organizer ⇒ `organizer-not-household`，不得猜。
- 组织者的 role 是 `PRODUCT_SHARE`；组织者因此是 wage roles 的付款义务人（D3 执行具体支付）。若定义没有 product-share 角色，注册期即拒绝。
- `ProductionEnterprise.classPositionId` 只填 organizer 的当前阶层位置；缺失 ⇒ `organizer-class-position-missing`（单户自动路径通常可从 `ProductionRoleResolver` 取到）。

### 7.3 资格

- **计划级**：`IndustryDefinitionView.availability` + 计划解锁门槛，主体 = organizer；缺属性/经验不足 ⇒ 具名 `plan-qualification:<reason>`。
- **角色级**：`roles[].conditions` 的每条只约束该角色参与者；同一户在两个角色时分别检查；缺 facts/经验 → 该条件不满足。
- **执行时再查**：probe 之前的候选检查不替代 execute 的检查；`ProductionRuntimeEngine.execute` 必须在任何进度/经验/投入写入之前重查，避免“probe 拒绝后直接 execute 仍生产”（R3 的同类教训）。
- **拒绝粒度**：`role-qualification:<roleId>:<household>:<reason>`；任何一条不满足 ⇒ 整个实例本日业务拒绝、零状态变化，不按“部分角色满足”生产。
- **`MONEY_WAGE` 自动候选例外（D3 §0.4 #5，当前有效）**：单个家户不合格 = **候选 skip**（具名 `role-qualification:...`），**不是**整 unit 故障；无候选只形成 shortage 并驱动 U20。roster 内 `PRODUCT_SHARE` participant 的资格失败仍按上一条整实例拒绝。

---

## §8 预计所得与两层分配

### 8.1 候选试算（按户）

对每个名册候选：

1. 名册已通过 §7 校验；
2. 计划级资格用 organizer；角色级资格逐户检查；
3. `probe` 入参（D1 §2.9 不变）：
   - `conditions.household = organizer`
   - `conditions.laborMilli` = **周期窗口劳动**：`PRODUCT_SHARE` 角色取 `Σ participant.plannedLaborMilliPerDay × definition.cycleDays`；`MONEY_WAGE` 角色取 `min(posting/default.targetLaborMilliPerDay, maxAbsorbableNonLaborForRole) × definition.cycleDays`。两类**不得重复使用同一份劳动预算**；这是 A1 的候选/采用比较窗口（`cycleDays`），实际执行仍逐 tick。
   - `conditions.assets = D4 占用读口给的 unit 占用`（C2 若 D4 未接，见 §15 的 R2 限制）
   - `conditions.inputs = inputSupplier 的当刻库存（按配方键）`
   - `mastery/experience = organizer`（计划级效率/解锁主体；D2 不发明多户经验聚合公式）
   - `attributes = organizer facts`
4. 不可行 ⇒ 具名 `PROBE:<reason>`；不得给 0 产出继续估价；
5. 可行 ⇒ `expectedOutput` 先经与 harvest 同一个 `ProductionOutputNetting` 扣 FEED+DEPRECIATION 得到预计 net 池；`RoleDistribution.allocate` 用 **planned** labor/rights/weights（不是实际）。

### 8.2 按户预计所得

对名册内每个户逐项计算：

```text
产品份额值 = 该户由 RoleDistribution 得到的逐商品份额，按本户需求/市场/可寻址需求估价
工资应收   = Σ_{该户为被匹配 MONEY_WAGE worker} quoteSource × expectedMatchedLaborMilliPerDay × cycleDays / 1000
工资应付   = Σ_{该户为 organizer} quoteSource × expectedLaborMilliPerDay × cycleDays / 1000
投入负担   = 若 relation.inputSupplier 是 Payee.ToActor 且解析到该户，则 probe.inputRequired 按本地 ask 价估值的成本；否则归 organizer；Payee.ToCohort/其他形态 ⇒ input-supplier-unsupported
预计净额   = 产品份额值 + 工资应收 - 工资应付 - 投入负担
```

规则：

- **产品份额不以劳动为条件**：纯收租地主的 `plannedLabor=0` 仍拿定义份额；其份额不因“没下地”被清零。
- **工资是组织者的支出**：U15 更正不可回退；不得把工资算成组织者收入。
- **`quoteSource`（A3，当前有效）**：实例存在前，候选/新进入者读候选级 `ProductionWageDefault`（键 `(plan, role, marketZone)`，D3 §2.2.1）；执行期读 `ProductionWagePosting`。`ProductionWagePosting` 是执行期**唯一实际报价权威**，default 不是第二份执行报价。
- **窗口（A1）**：以上工资应收/应付都是**周期累计**（`× cycleDays`），与产品份额值、投入负担、劳动分母同窗口；实际 W3 due 按每 tick 实际劳动，**不乘 `cycleDays`**。
- **生存预测时间口径（N-04/F-04）**：本节的周期预计值**不是** D3 `SurvivalOutlook` 的下一 tick 可交付量。唯一转换器 = W2b 所有的 pure `NextTickIncomeProjection`（`simos-economy/.../time/NextTickIncomeProjection.java`，见 D3 §2.5.1.0）：输入本节 cycle `Prospect`/候选 + `nextTick` + production cycleStart/cycleEnd + current quote/target + 下一 tick 劳动计划；输出下一 tick 可交付的 `expectedMoney/InKind/ProductShare` 与 `incomeAvailableAtTick`。产品份额只有 `cycleEnd <= nextTick` 才计入，否则该 tick 为 0；周期末才分配的 product share 不得直接当下一 tick 口粮。缺 cycle 起止/quote/target/劳动计划 ⇒ `survival-income-projection-missing`/`survival-income-timing-unknown` fail-closed，不静默当 0。
- **劳动分母（A1）**：`PRODUCT_SHARE` 角色的预计劳动仍用 `plannedLaborMilliPerDay × cycleDays`；`MONEY_WAGE` 角色用 `targetLaborMilliPerDay × cycleDays`（或预计可匹配劳动 × cycleDays）。两类**同一周期窗口**，不得重复使用同一份劳动预算。
- **缺工资报价/缺目标**：`wage-quote-missing:<role>` / `wage-target-missing:<role>`，不得按 0 报价。D3 可以报 0（U18），但“缺”与“0”必须分开。
- **负净额保留（工程默认，落实 U18/U19）**：预计所得表必须保留 `negative=true` 的行，禁止像旧 `LaborQueueBook` 那样预过滤非正收益。D2 不做自动选择，故负数只影响读数和后续 D3 选择，不在 C2 被删除；用户未逐字指定过滤算法。
- **需求不预留**：市场可寻址需求是预测，不因候选而扣减；同一商品多户预计卖出可能高估，实际市场结算仍是权威。D2 不建“预期需求配额”第二账。
- **估值复用**：产品/投入估值必须复用 `ExpectedProfitBook` 现有的“自用+可售、缺价记 0 并标缺价、投入按 ask”口径，提取为唯一纯 helper；不得在 D2 新写第二套价格公式。

### 8.3 周期关账的两层分配

只在 `ProductionReceipt.cycleClosed == true` 时执行；每日执行不分配产品。

1. **可分配净池**：沿用 `EconomySettlement.harvest` 现有的 `FEED_PER_MILLE + DEPRECIATION_PER_MILLE` 损耗公式，逐商品 `net = gross − loss`；`loss` 仍进 ledger 的 ΣLoss，不归任何户。角色分配只使用 `net`，**不得再按 gross 分一次**，也**不得 credit 给 operator 后再分一次**。
2. **角色池**：对每个 `PRODUCT_SHARE` 角色、每个商品，`rolePool = floor(net[c] × sharePerMille / 1000)`。该计算由 `RoleDistribution` 内部完成，§8.3 只是说明。
3. **角色内拆**：按 §6.3 的 `RoleDistribution`，以**实际** `cycleContributions`（按角色）和实际 rights 权重为输入。
4. **余数**：
   - `roleShareRemainderRule == TO_LAST_ROLE` ⇒ 逐商品的**份额余数**（`1000 − ΣsharePerMille`）和**整数除法余数**都加到最后**一个 `PRODUCT_SHARE` 角色**（不是最后一条 MONEY_WAGE 角色）的池，再走角色内拆分；
   - `UNALLOCATED`（默认）⇒ 逐商品余数进入 `unallocated`；
   - 角色内零权重/全零权重 ⇒ 该角色池进入 `unallocated`；
   - **`unallocated` 全部归 organizer residual**，以具名 `ROLE_SHARE_UNALLOCATED_RESIDUAL` 记入 organizer 的账户/流水；**不得丢货、不得留在无主池**。
5. **落账**：逐户逐商品生成账目 intent，直接计入 participant household 的库存（实例地点）；不先记给 organizer 再转出（避免双记）。货币产出（`mintCandidates`）仍按现有铸币路径归 operator/organizer，不参与角色产品份额（D2 非目标）。
6. **守恒校验**：逐商品 `loss + Σ户份额 + organizer residual == gross`，等价于 `Σ户份额 + organizer residual == net`；任何差额 ⇒ 契约故障（ERROR），不得写半截 revision。
7. **旧路径禁止**：名册方案不得再调用 `ProductionSettlement.settle` 的产出分配，也不得先调用 `creditOutput` 把 net 记给 operator；旧 `CompensationRule` 非空 ⇒ `legacy-compensation-rules-present` 契约故障。
8. **次序**：先扣损耗得 net、再按角色分产品，最后由 D3 从 organizer 的实际货币/活动库存支付工资（U21 顺序）；D2 不实现支付，只保证产品份额先到账，D3 可以使用刚分到的产品库存。

### 8.4 工资/资产的分册边界

- **D3**：报价状态（33 `productionWageDefaults` / 34 `productionWagePostings` / 35 `wageCarryRemainders`）、U20 调价、劳动匹配、下一 tick 生存条件不从业（U23，`idleEligible` 资格与 `idleChosen` 选择分离，D3 §2.5.1/§4.4）、U21 现金→库存数量降序→债务、欠薪零息与“不影响择业”。D2 只定义 quote 读口与金额公式；C2 遇到无报价的 `MONEY_WAGE` 角色返回 `wage-quote-missing`、缺目标返回 `wage-target-missing`，不得假装已支付。**W2b 独立阶段**（W3 未接）对真实 `settleOneDayInto` 必须在任何抽料/劳动写回/进度/经验/实际劳动写入**之前**整 tick 拒绝 `wage-settlement-not-implemented`（D3 §4.7/§7.2），不得只发产品份额而把工资静默留空；W3 接通后改为按实例业务拒绝。
- **D4/A**：`assetOccupancy` 必须来自 D4-C 的必选只读 `AssetOccupancyLookup`：C2 只调用 `occupancyOf(unit, fromTick, toTickExclusive)`，把返回 `AssetOccupancySnapshot.allocation` 原样用于 `ProductionProbeRequest/ExecutionRequest.assetOccupancy`；`committed/unallocated/rejection` 只读，不另外推导。`Optional.empty()` ⇒ C2 具名 `asset-occupancy-unavailable`/零产出，不得读成 0、不得回退旧整池扫描 `usableAssets`；空 Map = 有权威结果、合法零占用。snapshot 必须同 revision/同 interval，unit/location/interval mismatch ⇒ ERROR `asset-occupancy-snapshot-mismatch`。D4-C 提供 fail-closed `UnavailableAssetOccupancyLookup`，D4-I 提供唯一真实实现（`AssetOccupancyLookupImpl`）。在 D4 未接时，C2 对同一 `(plan, operator)` 的多个实例、或共享同一 `AssetShareId` 的多实例，具名拒绝 `asset-scope-shared:awaiting-D4`；单实例路径可继续，且 C2 验收不得声称 R2 已关闭。
- **现有 `usableAssets` 的定位**：D4-C 接口装配后，C2 不得再用它；仅允许作为 D4-C/D4-I 未接前的 fail-closed 适配器内部实现细节，且必须返回 `Optional.empty()`/具名 `asset-occupancy-unavailable`，不得在 `ExpectedProfitBook`/`LaborQueueBook`/`EconomySettlement` 各写一份，也不得回退整池扫描。

---

## §9 工作经验增长、迁移与合户

### 9.1 每角色增长，按 `(家户, 经验条)` 一次归并

对每个实际 `(household, role, laborMilli)`：

- `roles[role].experienceGrowth` 为空 ⇒ 该角色不增长；
- `coefficient = experienceGrowth.coefficientPerMille`；
- `basis = ACTUAL_LABOR` ⇒ `effectiveLaborMilli = laborMilli`；
- `basis = NORMALIZED_PARTICIPATION` ⇒
  ```text
  participationPerMille = laborMilli × 1000 / householdLaborBudgetMilli
  effectiveLaborMilli = participationPerMille
  ```
  其中 `householdLaborBudgetMilli` 是 economy 传入的**当日**家户可用劳动，量纲与 `HouseholdEconomy.laborMilli` / `HouseholdLaborCommitment.laborMilli` 一致；`effectiveLaborMilli` 是“全时当量毫劳动”（1000 = 该户全部可用劳动投入该角色）。该式表达“该户把多大比例的家户劳动投入到该角色”，**同样参与比例下 10 人户与 100 人户同速**；不以人口作分母，也不乘 `standardLaborMilliPerCycle`（后者只服务角色标准劳动/计划量，不改变经验速度）。
- `countsManagement=true` ⇒ 把 `managementLaborMilli` 加进 `laborMilli` 后再算比例，并夹到 `householdLaborBudgetMilli`；缺失 ⇒ 业务拒绝 `management-labor-missing:<role>:<household>`。
- 每角色先乘 `coefficientPerMille`，再按 `(household, track)` 汇总**精确分子**；同一 tick 同一 `(household, track)` 只做一次除法/余数结转，不得按角色/实例各自截断。
- `maxPerTickValue > 0` 时对角色本 tick 的**精确增长分子**做上限（`maxPerTickValue × 1000`），再进入 track 汇总；上限只截该角色，不撤销已有经验/解锁。
- 经验主值/余数仍写现有 `ProductionData.householdExperience`；解锁仍只增不减。D1 的 `ExperienceTrack.growthPerLaborMilli` 继续是基础增长率的唯一拼写点；`effectiveLaborMilli` 的单位按“全时当量毫劳动”解释，角色系数负责差异。
- `ProductionExperience.accrueFrom` 现签名只接受整数 `aggregatedLaborMilli`；C1 可以把它扩展为“接受已归并的精确分子/全时当量劳动”，但**持久状态仍只有主值+余数**，不得新增第二套经验余数表。实现架构账本必须写明扩展方式与溢出策略（优先 `BigInteger`/`Math.multiplyExact` fail-closed）。

### 9.2 多角色/多实例不重复

- 同一户在同一天于两个角色/两个实例出工：各角色各算精确增长分子，按 track 汇总一次；不得因“同一计划/同一 track”重复计同一劳动。
- 同一实际劳动只属于一个 `(household, role, unit)`；economy 的全局劳动预算不变量继续保证 Σ allocations ≤ 家户当日 `laborMilli`。
- 经验汇总后的 `ProductionReceipt.experienceGrowth` 必须与 role 明细的汇总逐项相等（C4 断言）。

### 9.3 迁移/合户

- 生产经验继续直接调用现有 `ProductionData.mergeExperienceInto(from, movedPeople, to, targetPeople)`：来源∪目标、缺行=0、人口加权、余数一起、解锁并集、源户不删；**D2 不新写第二套合户公式**。
- 名册迁移新增纯函数 `ProductionRosters.mergeHousehold(rosters, from, to, movedPeople)`（建议在 `simos-economy/.../model/` 或 time 包）：
  - 每个含 `from` 的名册把 participant.household 改为 `to`；
  - 若 `to` 已在该 roster 的同一 role：plannedLabor 相加、rightsStakes 并集去重、configuredWeight 相加、organizer 取 OR；
  - 若合并后同 roster 出现多个 organizer（只可能因两个组织者合户）⇒ 保留一个（被合并后的同一主体），但若 `to` 原本是另一 role 的 organizer 且角色不同，仍是一个 organizer，保留；
  - `rightsStakes` 只移动引用，不复制数量；权威 `OwnershipStake.owner` 若不随人口迁移更新，后续 reference validation 以 `rights-not-owned` 拒绝，不静默改资产；
  - 源户人口清零后，纯劳动角色的名册项若 plannedLabor=0 可删除；纯收租/非劳动项可保留为 0 人口壳（与现有“保留空壳户”一致）。
- **组织者合户的额外门槛**：`unit.operator`/`ProductionEnterprise.organizer`/`ProductionRules.operator`/`assetShares.operator` 是 production/economy 的持久字段；`ProductionRosters.mergeHousehold` **不能**只改名册。C2 允许非 organizer 的合户（同一 roster 内 participant 合并）；若 `from` 是 organizer，必须走 Phase M 的 `production.RebindInstanceOperator`（周期边界、同批更新 unit/enterprise/relation/asset operator），在命令落盘前返回 `organizer-merge-requires-rebind`，不得留下“名册已换、unit.operator 未换”的半状态。
- 迁移/合户与名册改动必须在同一个 revision 内；名册因 `cycle-open` 被锁时，迁移器只能等周期边界或按 §11 的锁定失败，不得半改。

---

## §10 名册生命周期与失败语义

### 10.1 生命周期

- **创建**：单角色自动路径同批写默认名册；多角色由 `economy.SetProductionRoster` 或 seed 显式写；`production.CreateInstance` 本身不写 economy 名册。
- **锁定**：实例 `progressDays > 0`（周期已开始）时，`SetProductionRoster` 返回 `roster-locked`；名册在周期内不变，保证分配份额/实际贡献可复算。周期关账后（progress 归零/新周期）可替换。
- **清除**：`participants: []` 清除；之后执行 ⇒ `roster-missing`，不得回退到单户隐式路径。
- **实例删除/区域清理**：`EconomyClearRegionHandler` 删除 unit 时同批删除对应名册；留下悬空名册 ⇒ revision 拒绝（`roster-instance-unknown`）。
- **多实例**：每个 unit 一名册；同一户可出现在多个 unit 名册中，但全局劳动预算/资产占用仍由 economy 统一检查。**F-01 payer 地点快照**：W1/W2b/W3 的 `WagePayerLocationSnapshot` 必须汇总该 payer 当前 revision 的全部已有 production unit/roster/posting 地点 ∪ 候选/本次 unit 地点；已有 unit A + candidate B 时不得只把 B 传给 `WagePayerLocationPrecheck`，否则候选阶段会漏判、W3 才拒绝。

### 10.2 失败语义（稳定词表）

业务拒绝（INFO，零状态变化）：

```text
unit-unknown
roster-missing
roster-required
roster-instance-unknown
roster-locked
roster-plan-mismatch
role-unknown:<role>
role-unfilled:<role>
participant-duplicate:<role>:<household>
organizer-missing
organizer-multiple
organizer-not-household
organizer-actor-mismatch
organizer-class-position-missing
household-labor-overcommitted:<household>
rights-not-owned:<share>
rights-duplicate:<share>
configured-weight-invalid:<role>:<household>
plan-qualification:<reason>
role-qualification:<role>:<household>:<reason>
wage-quote-missing:<role>
wage-target-missing:<role>
wage-settlement-not-implemented:<role>
management-labor-missing:<role>:<household>
input-supplier-unsupported
enterprise-roster-mismatch
asset-occupancy-unavailable
asset-occupancy-snapshot-mismatch
asset-scope-shared:awaiting-D4
legacy-compensation-rules-present
legacy-rule-not-representable:<type>
roster-merge-weight-unresolved
organizer-merge-requires-rebind
seed-roster-missing:<unit>
```

契约/跨切片故障（ERROR，不降级，整个候选 revision 拒绝）：

- 名册键与 `unit` 不一致；
- 名册通过候选校验后，执行时发现 unit/plan/role 消失或 production 投影不一致；
- 分配守恒失败、负数份额、重复组织者等**进入持久态后**才发现的内部矛盾；
- 名册 `plan` 与 production 实例 plan 不一致且不是命令边界可归因的输入错误；
- 任何“按 0 兜底”的工资/资产占用/经验缺口。

### 10.3 日志

- INFO：名册 set/clear（unit、plan、participants、roles、organizer、replaced）、单户默认名册生成、周期分配完成（unit、gross/loss/net lines、unallocated、organizer residual）。
- DEBUG：候选拒绝理由、按户预计所得（household/role/net/negative）、角色池/权重/余数。
- TRACE：逐户逐商品份额、逐角色经验增长分子、迁移/合户名册项。
- 日志不得写载荷明文中的敏感内容；日志失败不影响状态（沿用 AGENTS §一.9）。

---

## §11 不变量清单（D2-I）

1. **D2-I1 名册唯一**：每个 unit 至多一条 `ProductionRoster`，键 == `unit`；名册是参与角色/计划劳动的唯一权威。
2. **D2-I2 角色完整**：`plan.roles` 中**每个 `PRODUCT_SHARE` 角色至少一个 participant**；`MONEY_WAGE` 角色**可以没有 participant**，其需求由 `ProductionWagePosting`（执行期）/`ProductionWageDefault`（新进入候选）表达，实际 worker 由 allocations/receipt 决定。名册不得含未知角色；不得为工资角色伪造 placeholder household。
3. **D2-I3 组织者唯一**：整份名册恰好一个 organizer；organizer 角色为 `PRODUCT_SHARE`；unit.operator / enterprise.organizer / relation.operator 均为 organizer household actor。
4. **D2-I4 份额合法**：角色份额合计 ≤ 1000；`MONEY_WAGE ⇒ share=0`；`PRODUCT_SHARE ⇒ share>0`；至少一个 product-share 角色。
5. **D2-I5 直接份额**：角色产品份额不以下地/实际劳动为条件；劳动只影响角色内 `BY_RECOGNIZED_LABOR` 拆分与经验。
6. **D2-I6 两层守恒**：逐商品 `loss + Σ参与者份额 + organizer residual == gross`（即 `Σ份额 + residual == net`）；角色池/角色内整数余数不得丢。
7. **D2-I7 未分配归 residual**：`unallocated`、零权重角色池全部归 organizer residual，并具名记账。
8. **D2-I8 工资分离**：工资是 organizer 的货币/库存/债务义务，不是产品份额；MONEY_WAGE 角色不得拿产品份额；缺报价不得按 0。
9. **D2-I9 负收益保留（工程默认，落实 U18/U19）**：预计所得不得预过滤负净额；D2 不做自动匹配，D3 才做选择；用户未逐字指定过滤算法。
10. **D2-I10 资格 fail-closed**：计划级主体 organizer；角色级主体每个 participant；缺 facts/经验不满足；execute 与 probe 同口径。
11. **D2-I11 经验一次归并**：按角色算精确增长分子，按 `(家户, 经验条)` 每 tick 只除一次；迁移复用 `mergeExperienceInto`，解锁不撤销。
12. **D2-I12 归一化口径**：`NORMALIZED_PARTICIPATION` 用 `laborMilli × 1000 / householdLaborBudgetMilli`，分母是家户当日可用劳动（`householdLaborBudgetMilli`），不是人口；同参与比例同速；`standardLaborMilliPerCycle` 不进入经验分母。
13. **D2-I13 全局预算**：同一户所有名册 planned labor 之和 ≤ 当日 `HouseholdEconomy.laborMilli`；实际 allocations 仍受既有全局不变量约束。
14. **D2-I14 资产单引用**：`BY_RIGHTS` 只引用 `AssetShareId`，不复制 quantity；同一份额不得跨 participant/role 重复；C2 只认可 owner 侧权利（owner/industry 匹配），operator 侧 use-right 归 D4。
15. **D2-I15 名册周期冻结**：周期开始后不得替换名册；周期关账后才可改。
16. **D2-I16 单户默认**：单角色 plan 且该户 `remaining = laborMilli − Σ现有名册.plannedLabor` > 0 时，自动路径生成默认名册（role id 取 plan.roles[0]、planned = min(remaining, 标准日劳动或 remaining)）；多角色 plan、有旧规则、或 `remaining <= 0` ⇒ `roster-required`，不得按角色建多份组织。
17. **D2-I17 旧分配禁用**：名册方案的 `ProductionRules.rules` 必须为空；不得同时调用 `ProductionSettlement` 产出分配。
18. **D2-I18 资产边界**：D2 不关闭 R2；D4 未接时共享 `(plan, operator)`/共享份额的多实例 fail-closed `asset-scope-shared:awaiting-D4`。
19. **D2-I19 账户边界**：production 不写账户/库存/人口；economy 只经账目 intent/原有变更集落账。
20. **D2-I20 确定性**：所有遍历按 unit/role/household/commodity 稳定 id 排序；无随机、无时钟、无 UUID；最大余数并列有稳定破平。
21. **D2-I21 同版本往返**：名册、角色、条件、经验配置、参数全部进 ChangeSet/codec，`apply(changeSet, base)` 逐字段重建。
22. **D2-I22 不建第二权威**：economy 不复制 I/O/经验系数/工资公式；production 不持久化名册；工资/资产状态不在 D2 新造。

---

## §12 验收判据

### 12.1 正向判据（C2 + C4 必须逐条有载体）

| # | 判据 | 预期 |
|---|---|---|
| P1 | **单角色、无旧 `CompensationRule`** 的 plan + 单户默认名册 | probe/execute/关账净产与 C2 前**无规则单户路径**逐值相同（同 I/O、同经验）；有旧规则的 plan 必须先转成角色，P1 不冒充旧规则等价 |
| P2 | 三角色 `400/400/200`、各一户、**可分配 net 池=1000** | 分配 `400/400/200`，合计 1000；地主 plannedLabor=0 仍拿 400 |
| P3 | 同角色两户、`BY_RECOGNIZED_LABOR`、实际劳动 600/400 | 角色池 400 ⇒ `240/160`；总守恒 |
| P4 | `BY_RIGHTS`：两户 rights 3:2 | 按 `OwnershipStake.quantity` 拆；份额 id 不被复制；owner 错/缺 ⇒ 拒绝 |
| P5 | `EQUAL` / `CONFIGURED_WEIGHTS` | 最大余数、稳定破平；权重全 0 ⇒ 角色池进 unallocated 归 organizer |
| P6 | `UNALLOCATED` 与 `TO_LAST_ROLE` | 份额 400/400/100、net=1000：前者 residual=100，后者最后 PRODUCT_SHARE 角色=200；两种都逐商品 `loss+shares+residual=gross` 守恒 |
| P7a | MONEY_WAGE 角色的**C2 结构边界** | share 必须 0；缺 quote ⇒ `wage-quote-missing`；D3 支付路径未接 ⇒ `wage-settlement-not-implemented`；不得只分产品不记工资 |
| P7b | MONEY_WAGE 的预计所得（**W2a+W1**，不是 C2 的正式验收） | C2 只用 fixture quote 做算术自证；真实报价状态/选择/支付属于 D3/W2a+W1，C2 交账不得写“工资已完成” |
| P8 | 负净额 | 保留 `negative=true`，不被 D2 过滤；无“必须不亏本”闸（工程默认，不是用户逐字裁定） |
| P9 | 角色资格 | 某角色某户缺 literacy/经验 ⇒ `role-qualification:...`，零产出/零经验/零消耗 |
| P10 | 组织者解析 | 0 个/2 个 organizer、operator 不匹配、非 household ⇒ 具名拒绝 |
| P11 | 经验三角色同 track、不同系数 | 按角色精确分子汇总一次；不同系数不同增量；同户同 track 不重复 |
| P12 | 10 人 vs 100 人、同参与比例 | 用“人口不同、劳动预算也不同”的夹具（10 人预算 10,000、投入 5,000；100 人预算 50,000、投入 25,000，二者参与比例都是 50%）：按 `householdLaborBudgetMilli` 归一化两者 `effectiveLaborMilli=500`；若变异成按人口，结果为 500 vs 250；`ACTUAL_LABOR` 下与劳动成比例 |
| P13 | 迁移/合户 | 经验复用 R4 例子（100×10 + 0×10）/20=50；**非 organizer** 名册 participant 合并、权重/labor 相加；organizer 合户必须 `organizer-merge-requires-rebind`（Phase M） |
| P14 | 名册往返 | 全字段 JSON 往返；空/单户/多角色/多 rights/weights 均逐字段相等 |
| P15 | 命令/seed | `SetProductionRoster` 整表替换/清空；`production.CreateInstance` + 名册同批原子；单户自动名册 |
| P16 | 旧规则禁用 | 名册方案 `ProductionRules.rules` 非空 ⇒ 具名拒绝，不双分 |
| P17 | 全局预算 | 同户多个名册 planned labor 超预算 ⇒ `household-labor-overcommitted` |
| P18 | 日志 | INFO/DEBUG/TRACE 各至少一条可断言事件；拒绝有具名 reason |
| P19 | 毫小时量纲 | `1 小时 = 1000 毫小时`；`plannedLaborMilliPerDay=1000` 表示 1 小时/日；`standardLaborMilliPerCycle=1000` 表示 1 小时/周期；任一字段按“千分劳动”解释 ⇒ 红 |
| P20 | `MONEY_WAGE` 空 participant | `PRODUCT_SHARE` 角色无 participant ⇒ `role-unfilled:<role>`；`MONEY_WAGE` 角色无 participant 但 posting/default target 完整 ⇒ 合法，不伪造 placeholder；缺 target ⇒ `wage-target-missing:<role>` |
| P21 | 周期预计 vs 下一 tick（N-04） | D2 的 `产品份额/工资 × cycleDays` 只能进周期候选净额；D3 SurvivalOutlook 只接收 `incomeAvailableAtTick <= nextTick` 的可交付量。整季 1000/期末交付、下一 tick 需求 10 ⇒ 生存覆盖 0（另有同 tick 10 时只计 10），不得把 1000 当下一 tick 口粮 |
| P22 | payer 已有 + 候选地点快照（F-01） | 同一 payer 已有 unit A（location A），候选 B（location B）：`WagePayerLocationSnapshot` 必须得到 `{A,B}`；W1/W2b 候选不可行/skip、W3 整组拒绝，均零 lookup；只传 `{B}` 的候选阶段通过 ⇒ 红 |
| P23 | D2→D3 next-tick adapter（F-04） | `NextTickIncomeProjection` 是 W2b 唯一实现；cycle=100、nextTick=1、期末 product share=1000 ⇒ 下一 tick 投影 0；cycleEnd=nextTick ⇒ 可计对应交付量；缺 cycle 起止/quote/target/劳动计划 ⇒ `survival-income-projection-missing`/`survival-income-timing-unknown`，不得静默 0 |
| P24 | C2 只依赖 D4-C 接口（F-07/F-08） | C2 仅凭 D4-C 的 `AssetOccupancyLookup`/`WagePayableInventoryLookup` 可编译；`AssetOccupancyLookup` 返回 empty ⇒ `asset-occupancy-unavailable`/零产出；空 Map ⇒ 合法 0；snapshot mismatch ⇒ ERROR `asset-occupancy-snapshot-mismatch`；旧 `usableAssets` 回退 ⇒ 红；D4-I 接手后无第二套实现 |

### 12.2 负向/变异判据（改坏 ⇒ 当场红 ⇒ 还原 ⇒ md5 一致）

| 变异 | 应红 |
|---|---|
| 把地主份额改成“按劳动条件” | P2 |
| 角色池/角色内整数余数丢掉 | P3/P6 守恒 |
| `unallocated` 不归 organizer | P6 |
| 多户同角色各领整份 | P3/P5 总量 |
| 同一 `AssetShareId` 跨 participant 重复 | P4 |
| MONEY_WAGE 角色拿 product share | P7 |
| 缺 quote 时按 0 | P7 |
| 预过滤负净额 | P8 |
| 角色条件只在 probe 查、execute 不查 | P9 |
| organizer 从 `ProductionEnterprise` 另猜 | P10 |
| 经验按 `(家户,track)` 聚合后乘一个角色系数 | P11 |
| 归一化用人口作分母 | P12 |
| 合户缺行当“不参与” | P13 |
| Codec 漏一个名册字段 | P14 |
| 单户自动路径不写名册 | P1/P15 |
| 名册方案仍调用旧 `ProductionSettlement` | P16 |
| 全局预算只查单 unit | P17 |
| 多实例共享资产仍放行 | D4 未接时必须 `asset-scope-shared:awaiting-D4` |
| 把 `MONEY_WAGE` 空 participant 当 `role-unfilled`，或为它伪造 placeholder | P20 |
| 把 `standardLaborMilliPerCycle`/`laborMilli` 按“千分劳动”而非毫小时解释 | P19 |
| 把 D2 周期产品份额/工资预计直接塞入 D3 下一 tick SurvivalOutlook | P21 |
| W1/W2b 的 payer 地点 snapshot 漏已有 unit/roster/posting 地点 | P22 |
| 把 D2 cycle Prospect 直接塞进 D3 next-tick expected 字段 | P23/P51 |
| cycleEnd > nextTick 的 product share 仍计入下一 tick | P23/P49 |
| adapter 缺 cycle 边界/quote/target/劳动计划却静默按 0 继续 | P23/`survival-income-projection-missing` |
| C2 复制临时 AssetOccupancyLookup/回退旧 usableAssets | P24 |
| AssetOccupancyLookup 的 empty/空 Map 语义混淆 | P24 |

### 12.3 数值例（C4 用）

- 下列例子都先假设 `net=1000`（毛产与 3% 损耗另按 §8.3 单列，不进入份额算术）。
- 三角色 `400/400/200`，net=1000，各一户 ⇒ 400/400/200；organizer residual=0。
- 角色份额 `400/400/100`（合计 900），net=1000，无 TO_LAST_ROLE ⇒ 角色池 400/400/100，unallocated=100 → organizer residual；总 1000。
- 同角色两户 600/400 劳动，角色池 400 ⇒ 240/160；若 net=1000 且该角色 share=400，其余角色/受租另计。
- 10 人户预算 10,000、投入 5,000（50%）；100 人户预算 50,000、投入 25,000（同为 50%）⇒ 按预算归一化两者都是 500；若按人口归一化则为 500 vs 250，P12 必须能红。
- 合户例：目标 10 人经验 100，来源 10 人缺行（0）⇒ `(100×10 + 0×10)/20 = 50`。

---

## §13 文件所有权与实现批次

> 只列方向与禁止面；具体类/方法拆分由实现 owner 在实现架构账本中定。

| 批次 | owner 可写 | 禁止/边界 |
|---|---|---|
| **C0（D1 落形）** | `simos-economy-api/.../api/production/ProductionRoleId.java`（新增）；`simos-production/.../contract/`（角色/份额/经验/支付/条件/`RoleDistribution`）；D1 文档的局部更正注 | 不改 economy 实现、不改 app、不做名册状态；**不拥有 `AssetKind`**（见 C0-A） |
| **C0-A（`AssetKind` 词表与旧引用，D1 §2.7；先于 C0）** | 写面 = `simos-actor-api/.../asset/AssetKind.java`（删 `TOOL`、加 `SMALL_TOOL`/`LARGE_TOOL`）+ `rg -l 'AssetKind\.TOOL|"TOOL"' simos-*/src/main` 命中的**全部 main 文件**（含 `simos-production` 载荷/seed、`simos-economy` 的 `MerchantTradeTemplate`/`MerchantCapacity`/`ModeMigration*`/`EconomySettlement`/`HouseholdClassRule`/`EconomyPayloads`/`EconomyGovUnitUpserts`、`simos-app/.../EconomySeeder`、`simos-util/EconomyVocabulary` 等；以扫描清单为准，不靠记忆补漏） | 只做词表与直接旧引用替换，不改分配/占用行为；不得保留别名/兼容位；**测试断言归 C4**（C0-A 不写/不跑测试）；交 `rg -n 'AssetKind\.TOOL|"TOOL"' simos-*/src/main` 清零清单，以及 `SMALL_TOOL`/`LARGE_TOOL` 各自 seed→state→codec→apply 往返证据；不拥有 `ProductionRoleId`/角色契约（归 C0） |
| **C1（production 角色执行）** | `simos-production/src/main/java/.../ProductionData.java`、`ProductionRuntimeEngine.java`、`ProductionExperience.java`、`ProductionProcess.java`、`ProductionSnapshot.java`、`codec/ProductionCodec.java`、`change/ProductionChangeSet.java`；`ProductionExecutionRequest/DayRequest/Receipt/CycleContribution`；`RoleDistribution` | 不写 economy 名册/账户；不实现工资支付/资产分配 |
| **D4-C（只读接口，F-05/F-07/F-08；先于 C2）** | **必选**：`simos-economy/.../time/WagePayableInventoryLookup.java`、`simos-economy/.../time/AssetOccupancyLookup.java`（含 `AssetOccupancySnapshot` 精确签名）、`simos-economy/.../time/UnavailableAssetOccupancyLookup.java`（fail-closed 返回 empty）；必要时最小读口 | **只新增接口/无状态适配器，不新增 EconomyData 组件、不实现状态、不写账户/资产**；不把接口放 economy-api；D4-C 必须先于 C2，C2/W2a/W1/W2b/D4-I 不并行改同一 economy 文件 |
| **D4-I（真实 lookup + 36/37）** | `AssetOccupancyLookup`/`WagePayableInventoryLookup` 的唯一真实实现（如 `AssetOccupancyLookupImpl`/`WagePayableInventoryLookupImpl`）；`EconomyData` 第 36/37 组件；`AssetOccupancyBook`/`AssetOccupancyGrant`/`InventoryReservation` 等 D4 状态 | D4-I 在 W2b 后；不得改 D4-C 接口签名，不新增第二套读口；只编译，不写/不跑测试 |
| **C2（economy 名册/分配）** | `simos-economy-api/.../api/labor/HouseholdLaborCommitment.java`（加 role/改 id）；`simos-economy/src/main/java/.../EconomyData.java`、`EconomyDayView.java`、`EconomyBoundaryView.java`；`change/EconomyChangeSet.java`、`codec/EconomyCodec.java`；`model/ProductionRoster*`、`model/ProductionWageQuote.java`（最小读值，D3 可扩展）、`model/RegimeRelations.java`（旧规则删除/映射）；`spi/EconomySetProductionRosterHandler.java`、`EconomyPayloads.java`、`EconomySeedHandler.java`；`time/EconomyEnterpriseSettlement.java`、`EconomyEntrySettlement.java`、`EconomySettlement.java`、`ExpectedProfitBook.java`、`LaborQueueBook.java`、`time/ProductionOutputNetting.java`（新增，毛产→net 的唯一拼写点）、`ProductionSettlement` 的调用面、`LaborQueueSettlement.java`（劳动候选面）；`EconomyReferenceValidation.java` | 不 import production 实现；不改 POM/enforcer；不实现 D3 工资支付/D4 资产占用；不改 app |
| **C3（app 装配/创世/读口）** | `simos-app/.../Shell.java`、`AppProductionConsumerPort.java`、`world/*Seeder`、`world/GenesisModuleSlices`、工具/Catalog 载荷提示、只读视图 | 不复制 economy 分配公式；不新增空推进器；不改测试断言 |
| **C4（独立测试 owner）** | `simos-production/src/test/**`、`simos-economy/src/test/**`、`simos-app/src/test/**`、门禁守卫清单 | 实现 owner 不写/不跑测试；测试按 §12 判据，不按实现反推 |
| **M0（组织者合户前置）** | `simos-production/.../spi/RebindInstanceOperatorHandler.java`（新增）；production `ProductionProcess` 写口；economy 同批 `ProductionEnterprise`/`ProductionRules`/roster/asset operator 更新 | 只在周期边界；C2 首期不实现，不冒充已支持 |
| **D3 / D4 后续** | 各自分册另行派单 | 在 D3/D4 落盘前，C2 不得以“临时公式”实现工资/资产分配 |

**共享 economy 文件顺序（F-05）**：C0-A（`AssetKind` 词表与全部 main 旧引用扫描）→ C0（ID/契约）→ C1（production 编译过）→ **D4-C（只读 lookup 接口）→ C2（32）→ W2a（33/34）→ W1 → W2b（35）→ D4-I（36/37 + lookup 实现）→ D4-T → W3**。C3（app 装配）只碰 app/只读视图文件，可按自身依赖推进，但不得插入共享 economy 文件写面；C4 测试在对应实现 owner 交账后执行。D4-C 只新增接口，不落 `EconomyData`。

**共享 economy 文件 owner 交接顺序（F-05，当前有效）**：`C0/C0-A → D4-C → C2 → W2a → W1 → W2b → D4-I → D4-T → W3`。D4-C 只冻结只读接口；C2 落 32，W2a 落 33/34，W2b 落 35，D4-I 落 36/37；D4-T 在 D4-I 后看最终 37 组件；W3 最后。C1/C3 只处理各自 production/app 文件，不插入共享 economy 文件交接；C2/W2a/W2b/D4-I 不得并行改同一 `EconomyData`/`EconomyChangeSet`/`EconomyCodec`/`ExpectedProfitBook`/`LaborQueueBook`/`EconomySettlement`。同一文件同一时刻一个 writer；`EconomySeeder.java` 的后续 location 写面归 D4-I，必须等 C0-A/C3 交账并停手后再串行。

---

## §14 未做 / 未验证 / 开放工程项

1. **本文只写设计**：未改业务/测试、未编译、未跑 Maven/仿真/门禁，未提交/同步/推送。
2. **自动招募/匹配未做**：C2 的名册由 seed/命令/单户默认生成；U19 的逐 tick 报价博弈、零工资并列、闲置选择在 D3/W。D2 不声称这些已实现。
3. **D3 工资支付未做**：C2 只做预计金额与读口；`wage-quote-missing`、`wage-target-missing`、`wage-settlement-not-implemented` 是已知边界。W2b 独立阶段对真实 `settleOneDayInto` 必须整 tick fail-closed（任何抽料/写回之前）；W3 落盘前，含 `MONEY_WAGE` 角色的名册不得执行、不得以“已支付”验收。
4. **D4/R2 未关闭**：C2 不替代 D4；共享资产多实例必须 fail-closed 或明确标注限制，不能在 C2 宣称 R2 已修。
5. **旧 `CompensationRule` 清理**：C2 只禁用名册方案的旧 rules，D3 再决定删除/替换字段；本文不把旧规则删除写成已完成。
6. **expected-demand 不预留**：同一商品多户预计卖出可能高估；这是预测与市场结算的已登记边界，不是 D2 的隐藏保证。
7. **计划级效率主体**：D2 暂定 organizer 的家户经验/属性为计划级效率/解锁主体；不聚合多户经验。若后续要让角色经验直接进入效率公式，必须另开设计，不得在实现中临时加权。
8. **角色/阶层映射**：`ProductionRoleDefinition.socialClass` 是元数据，不自动决定参与者阶层；角色由名册真实选择，阶层由 `HouseholdClassMembership` 权威。
9. **未验证的 D1 修正**：`ProductionRoleId` 移入 economy-api、`RoleDistribution` 形状、`ProductionExecutionRequest` 扩展均需 C0/C1 编译验证；本文不把设计当编译通过。
10. **未验证的测试/门禁**：§12 的所有判据当前均无实际运行记录；C4 完成后才可写“通过”。

---

## §15 复核命令（实现后照抄，不是本次已跑）

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module

# C0/C1 编译
tools/mvn-lock.sh -q -DskipTests compile -pl simos-production -am

# C2 编译
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am

# 模块测试（C4 独立测试 owner）
tools/mvn-lock.sh -pl simos-production -am test
tools/mvn-lock.sh -pl simos-economy -am test

# 最终门禁（主树控制方，写者停手后）
tools/gate.sh full
```

**本文落盘时的状态**：D2 初稿基线 `0128b0cc`。**2026-10-17 U23 / 裁决列整合**：在侧线工作树 HEAD `5f517492` 上局部更新本册（§0.4 与相关段落），仍未提交、未同步主树、未改业务/测试。设计是否通过评审、C2 是否可派单，仍以控制方复核和 C4 证据为准。
