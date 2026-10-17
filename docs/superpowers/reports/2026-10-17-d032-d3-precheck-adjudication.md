# D-032 D3 衔接预审：独立控制方复核与裁决列（2026-10-17）

> **性质**：控制方对 `2026-10-17-d032-d3-integration-precheck.md` 的独立复核与**工程裁决**。只写设计结论，不实现、不派实现单、不跑 Maven/测试/仿真、不提交、不修 R2。
> **输入**：预审报告、D1/D2/D3、重规划 v2、当前侧线源码；实查基线 `0128b0cc`。
> **效力**：本文的“裁决”均为控制方工程裁定，不是用户玩法裁定；不新增工资/分配玩法审批。若与用户原话冲突，必须按 `AGENTS.md` §一.8.1 停下上报。

## §1 复核结论

预审报告列出的 **2.1/2.2/2.3 三项 P1 全部成立**；**2.4/2.5 两项 P2 也成立**，其中 2.4 是文档内部公式/手算例矛盾，2.5 是措辞可能被实现成与 U21 相反的行为。D3 当前不能作为冻结/可派实现基线。

控制方原报的 W1/W2 依赖、W2/W3 中间态、W3/D4 活动库存三项前置**全部成立**，且必须写进统一后的 D1–D4，不得留给实现代理自行选择。

## §2 裁决列

| 编号 | 预审主张 | 独立复核证据 | 裁决 | 统一后的设计口径 | 落点/验收 |
|---|---|---|---|---|---|
| A1 | 周期产出估价与日工资成本未对齐 | D2 `cooperative-production-design.md:463` 的 probe 劳动为 `plannedLaborMilliPerDay × cycleDays`；`:477` 工资也乘 `cycleDays`。D3 `wage-chain-design.md:617-622` 的 `expectedWageCost` 只乘 posting 日目标，`:637-643` 的 worker income 也只按日。 | **确认，D3 必须修** | 区分两个窗口：① **候选/采用比较窗口 = `definition.cycleDays()`**，产品份额、投入、工资应收/应付、劳动分母全部用周期累计；② **每 tick 匹配窗口 = 当前 tick 劳动**，用于 W2 角色 offer/实际 due。D3 的雇主 `expectedWageCost` 必须 `× cycleDays`；D2 §8.2 的按户预计工资保留 `× cycleDays`；实际 W3 due 仍按本次 tick，不得乘 `cycleDays`。 | D3 §5.1/§5.2/§5.4；D2 §8.2 保持同窗口；验收：cycle=100、日雇 1 小时、单价 2 ⇒ 候选用工工资 200；实际每 tick due=2。 |
| A2 | idle 按当前文字可能进不了队列 | D3 `:525-527` 把 `Offer.queueable()` 收成 `maxAbsorbableLaborMilli > 0`；`:533-542` 又给 idle `maxAbsorbableLaborMilli=0`。现有 `LaborQueueBook.java:147-150` 的 `queueable()` 要求 `maxAbsorbableLaborMilli > 0`，`:499-515` 的 `plan()` 对 `maxAbsorbable<=0` 只记 `IDLE_MAX_ABSORBABLE_ZERO` 并继续处理后续 offer；`LaborQueueSettlement.java:314-317` 只把非 preserved 的 production offer 放进队列。 | **确认，D3 必须修** | idle **不是生产 Offer**，而是独立控制候选 `IdleOption`（或 `Plan.idleChosen` 分支）。它不经过 `queueable()/maxAbsorbable` 过滤；在排序中 `yieldScaled=0`，正收益工作先做，零/负收益工作后 idle 胜；一旦 idle 胜出，停止给后续更低 yield 的工作授予劳动，但不取消 GOV_SERVICE/既有不排队配额。`allowIdle=false` 时不加入 idle。不能只靠 `"idle:" < "unit:"` 字符串保证全链路。 | D3 §4.3/§4.4；`LaborQueueBook`/`LaborQueueSettlement` 的 W2 实现面；验收：allowIdle=true 且只有零薪无补偿岗位 ⇒ `allocated=0`、`idle=budget`、`IDLE_CHOSEN`；有正收益工作时先分配工作、余下劳动再 idle；GOV_SERVICE 不被取消。 |
| A3 | 新进入经营家户的候选报价入口没有闭环 | D3 `:192` 的 `WagePostingKey` 是 unit+role；`:233-235` 的读口只按 unit+role；`:338` 要求 `SetProductionWagePosting` 的 unit 已存在；`:340` 初始 quote 只准显式；`:368` seed 也先确保实例存在；`:218` 的 `GENERATED` 没有定义选业前来源；`:630` 缺 quote 的候选不可估价。D2 的候选/选业在实例创建之前。 | **确认，D3 必须修** | 新增**候选级工资默认**，不依赖 unit：`ProductionWageDefault`（economy 持久表，键 = `(IndustryId plan, ProductionRoleId role, MarketZoneId zone)`），至少含 `initialWagePerLaborMilli` 与 `initialTargetLaborMilliPerDay`。候选估价读 default；选中后在同一合法 Revision 创建实例 + `ProductionWagePosting`（从 default 物化）；未选候选零实例/零 posting 残留。`ProductionWagePosting` 仍是执行期唯一实际报价权威；default 不是第二份执行报价，只是准入初始化策略。缺 default 且无实例 posting ⇒ `wage-quote-missing`，不得静默 0；缺目标 ⇒ `wage-target-missing`。 | D3 §2.2/§2.3/§2.7/§2.8/§5；D2 候选/采用路径；验收：未创建实例的候选能读到 default quote/target；选中后同批建实例+posting 成功；未选中不写任何 posting；失败不留半个实例/posting。 |
| A4 | 降价延迟公式与手算例不一致 | D3 `:445` 写 `satisfiedStreak >= decreaseDelayTicks` 就开始降价；`:984` 的手算例写 `decreaseDelayTicks=2`、`streak=2` 时仍不降。 | **确认，D3 文/式/例三处必须统一** | 采用“**满 delay 的下一 tick 才首次降价**”：条件改为 `satisfiedStreak > decreaseDelayTicks`；降价率仍为 `initial + (satisfiedStreak - decreaseDelayTicks) × accel`，封顶 cap。这样 `delay=2, streak=2` 不降；`streak=3` 首次降（rate=initial+accel）；`streak=4` 与现手算例 `rate=20` 相容。 | D3 §3.4/§3.6/§8.6 例 3；新增 `streak=3` 首次降价断言；负向：把 `>` 改回 `>=` 应红。 |
| A5 | 工资产出池表述可能误禁地主已到账库存 | D3 `:654` 写“雇主自己的产品份额不能当工资池”，但 U16/U21 要求真实到账的可支付活动库存参与抵付；D3 `:651-656` 未区分“预计/未到账份额”和“已入账库存”。 | **确认，措辞必须收窄** | 禁止的是：① 尚未到账/预计的产品份额不能当作可用现金；② 其他 participant 的份额不能拿来付地主工资；③ 再生产权益/已投入资料不能抵。**不禁止**：已经按 D2 分配到 organizer 账户、且经 D4 可支付库存读口确认属于活动库存的本人产出。来源是本人产品不改变它作为活动库存的支付资格。 | D3 §5.3/§6.3；W1/W3 验收：预计未到账份额不得计入工资支付能力；已到账且可支付的本人粮按 U21 参与抵物；他户份额/权益不得抵。 |
| B1 | W1 依赖 quote 读口、W2 才落真实状态 | D3 `:242` 说 C2 默认永远 empty、W2 才从 posting 表读；`:617-630` W1 依赖 quote；重规划 Phase W 旧顺序 W1→W2。 | **确认，需调整批次顺序** | 拆为 **W2a（候选 default + 实际 posting/quote 状态/读口）→ W1（工资前置估值）→ W2b（角色级匹配/idle/U20）→ W3（支付/抵物/成债）**。W1 只能基于 W2a 的真实读口，不得以 C2 的 empty 读口宣称生产候选能力完成。 | D3 §9.1；重规划 Phase W 顺序；W1 验收必须使用真实 posting/default fixture，不得用永远 empty 的 lookup。 |
| B2 | W2 与 W3 中间态可能产生无薪实际劳动 | D3 `:582-601` 说明 W3 未接时含 MONEY_WAGE 执行必须 `wage-settlement-not-implemented`，但未钉死拦截位置；现有 `EconomySettlement.java:1602-1613` 消费、`:1676-1677` `driveProductionDay`、`LaborQueueSettlement.java:314-317` 匹配写回都可能在前。 | **确认，需钉死 fail-closed 位置** | W2b 独立交付阶段：真实 `EconomySettlement.settleOneDayInto` 在**任何抽料、劳动匹配写回、进度、经验、实际劳动写入之前**做全 tick 预检；只要存在 MONEY_WAGE 角色而 W3 未启用，整 tick 具名拒绝 `wage-settlement-not-implemented`，不产生 allocations/进度/经验/实际劳动半状态。W2b 的匹配能力只能在显式只读/dry-run 入口验证。W3 接通后改为按实例业务拒绝（坏 posting 等），其他 unit 可继续。 | D3 §4.7/§7.2/§9.1；验收：W2b 真实 step 遇 MONEY_WAGE ⇒ `allocations`、进度、经验、实际劳动零写入；dry-run 可读匹配；W3 后正常路径不再触发该边界。 |
| B3 | W3 活动库存来源在 D4 前不明确 | D3 `:709` 要求 `活动库存 − 冻结 − 已投入/占用 − 权益/已投入资料`，但 D4 尚未定义权威读口；现有 `AccountSession` 只有余额/冻结，`OwnershipStake` 是权益而非活动库存。 | **确认，需 D4 先给读口** | D4 定义唯一 `WagePayableInventoryLookup`（或同义只读契约）：输入 payer+settlement location，返回**已扣完**冻结、生产占用、再生产权益/已投入资料后的最终可支付数量；W3 不再做第二次减法。D4 未接/返回不可用时：货币仍可按 U21 先付；库存腿全部跳过并记 `wage-inventory-scope-unavailable`，剩余成债；禁止回退到“账户余额即全部可支付”。 | D4 设计分册（首先完成）；D3 §6.3/§6.4；验收：D4 不可用 ⇒ 无库存支付、无重复扣减；D4 可用 ⇒ 每份数量仅扣一次，权益/占用为零可付。 |
| B4 | 独立复核新增：同一 organizer 跨格付款预算与 location 未定义 | D3 `:691-698` 要求同一 organizer 跨 unit/role/worker 共用一份货币+库存预算；`:757-760` 又说转移 `location=unit 结算地点`。若同一 organizer 有多个不同格 unit，预算与地点可能冲突；现有 `AccountSession` 是户级账户、`Transfer.location` 仍要求地点。 | **新增确认，W3 首期必须限制** | W3 首期只允许**同一 payer × 同一 settlement location** 的工资义务共享库存预算；同一 organizer 跨 location 的生产/工资在 D4 地点与占用读口落定前另立，不得跨格拿别处库存抵付。货币腿是否共用户级钱包需在 W3 明确为“同一户钱包、逐 location 记录”，不得用同一笔钱重复付。 | D3 §6.2/§6.6 与 D4 地点契约；验收：同 organizer 两格 unit 不得共享库存腿；跨格场景具名 `wage-payer-location-unsupported`，不静默合并。 |

## §3 裁决后的最小修改清单

1. **D3 正文**：A1 时间尺度、A2 idle 控制候选、A3 候选 default、A4 降价条件、A5 产品池措辞、B1 批次顺序、B2 预检位置、B3 库存读口、B4 同格限制。
2. **D2 正文**：保留 `plannedLaborMilliPerDay × cycleDays` 的候选窗口；把“MONEY_WAGE 角色可无 roster participant”与 D3 的 default/posting 边界合并；`candidateHouseholdsOf` 按角色类型分流。
3. **D4 设计**：先定义 `WagePayableInventoryLookup`/占用读口、地点与跨周期占用，再让 W3 消费；D4 未接时 D3 只能具名受限，不能冒充已可支付。
4. **重规划 Phase W**：按 W2a → W1 → W2b → W3 → W4 → W5 重排；W5 仍为独立测试 owner。
5. **验收**：A1/A2/A3/A4/B1/B2/B3/B4 各至少一条正向 + 一条负向/变异判据；不在本轮写测试。

## §4 明确未做的事

- 未修改 D1/D2/D3/D4 正文；未修改 Java/XML/POM/测试；未实现、未派实现单、未跑 Maven/测试/仿真；未修 R2；未提交/合并/推送/同步。
- 未新增用户玩法裁定；本文所有裁决均为控制方工程设计，用户可推翻。
- 预审报告与 D3 的全文终审仍未完成；统一 D1–D4 后再做一次独立复核。

## §5 需要用户裁决与无需用户裁决的边界

### 5.1 真正会影响真实世界行为、建议在 W3 冻结前由用户拍板的项

| # | 项 | 现有口径/事实 | 控制方建议 | 若不特别指定 |
|---|---|---|---|---|
| U-D3-1 | 工资欠薪是否计息 | U16/U21 只说“不足成债”，未说利率；现有 `EconomySettlement.capitalizeArrears` 对旧 `FIXED_MONEY_WAGE` 欠款使用 `DebtTerms.legacyDefault()` 的 **20‰/周期**；D3 目前写的是 0‰ 工程默认。 | **0‰**：欠薪是未付工资，不是双方约定的生息贷款；若要计息应显式配置。 | 按 0‰ 工程默认推进，不阻塞 D3/D4 设计；旧路径的 20‰ 需后续按此裁决修正。 |
| U-D3-2 | `allowIdle` 缺行默认值 | U19 只要求“增加允许不从业的设定”，未说新世界默认 true/false；D3 现写缺行默认 true。 | **true**：新世界默认允许闲置；需要强制从业的户由 seed/命令显式 false。 | 按 true 工程默认推进；该值可逐户覆盖。 |
| U-D3-3 | 抵物估值用 bid/ask/其他 | U21 只钉死“库存数量从多到少”，未钉死折价尺；D3 现写当地 `bidPriceOf`。 | **bid**：按工人能实际卖出的价格保守折算，避免用 ask 高估支付物。 | 按 bid 工程默认推进；缺价/0 价仍必须 skip 并保留债务。 |

以上三项都不是当前 R2 代码 bug 修复的前置；可以等 W3 冻结前再定，未定期间不得把工程默认写成用户裁定。

### 5.2 不需要用户裁决、控制方直接定的工程项

A1 时间尺度、A2 idle 控制候选、A3 候选工资默认、A4 降价延迟边界、A5 产品池措辞、B1 W2a→W1→W2b 顺序、B2 W2/W3 fail-closed 位置、B3 D4 可支付库存读口、B4 同 payer×location 限制，均为工程契约/实现边界，不需要用户选类名或参数。

### 5.3 当前真实代码 bug 与“设计债”的区分

- **已修并由独立测试验证**：R1/R3/R4/R5/R6 + F5（见 `2026-10-17-d032-production-runtime-migration-result.md` §7）。
- **仍未修的真实代码 bug**：**R2**（同方案多实例重复获得整份资产；独立复现 `owned=100 allocated-to-two-units=200`）。修 R2 需要先有 D4 的实例/地点占用与统一预算设计，不能只在 `ProductionProcessBook` 里做局部比例拆分。
- **D3 的 A1–A5 是设计书内部不一致**，不是当前已发布运行路径的代码 bug；其中 A2/A3 在 W2 实现前不会产生新世界状态。
- **旧工资路径的 20‰ 资本化**是行为口径问题，需 U-D3-1 裁决后才能决定是否作为 bugfix 修；不能默认把它当“必然错误”。
- **`LaborQueueBook` 非正收益过滤、`ExpectedProfitBook.wageProspect` 固定额、`ProductionSettlement` 无库存抵付**：这些是 D3/W1/W2/W3 尚未实现的能力缺口，不是可以脱离 idle/报价/支付链单独修的局部 bug；单独删过滤会让负收益工作被直接选中，反而更坏。

**建议的 bug-first 顺序**：先做 R2 所需的最小 D4 占用/可支付库存设计 → 派 R2 修复代码单并独立复现验证 → 再修 D3/D2 设计债 → 统一 D1–D4 → 复核 → 处理提交与 C0。此顺序不采用“第二控制方”的 D4 全量优先顺序；D4 只为 R2 bug 开辟最小范围。
