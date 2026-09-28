# 经济系统演化能力：当前 HEAD 代码事实与缺口表（2026-09-29）

> **性质**：只读核查报告。基于《2026-09-28 经济系统「演化能力边界」调查报告》（下称《边界报告》）逐条回代码复核；
> **只写当前代码事实、缺口与未核实项，不写方案**（方案在 `docs/superpowers/plans/2026-09-29-economy-evolution-r4-plan.md`）。
> **基线**：`HEAD = 6170760fbc48b62cef754d12828b7903873b4bd6`（分支 `ts/m1-r3b2`，2026-09-29 01:12
> `feat(economy): R3B.1 UseRight→AssetShare（owner/operator 份额；仅编译，未接线生产）`）。
> **工作树**：`git status --porcelain` 为空；无 stash；无在跑的 Maven/Surefire（核查时）。⇒ 没有需要保护的未提交修改，
> 但也意味着《边界报告》所称“R3 生产代码未提交”已在 `d0860915…6170760f` 各提交中落盘，本文以 **HEAD 实际代码**为准。
> **与用户口径的差异（如实记）**：用户描述“R3B.1 尚未完成、AssetShare 只有部分状态与迁移、生产不读取它、
> ProductionUnit 只有 ID 契约、已有 ProducerCostBook/OperatorCondition/稳定 HouseholdId”**与 HEAD 完全一致**，
> 本文按此口径复核并展开到四项演化目标的缺口。
> **纪律**：每一条都标“代码位置 / 台账读数 / 未核实”；不把旧 M2 报告或决策单提案当成已实现代码。

---

## 1. HEAD 状态核对

| 项 | 事实 | 证据 |
|---|---|---|
| HEAD | `6170760f`（R3B.1：UseRight→AssetShare；仅编译，未测试、未接线生产） | `git rev-parse HEAD` |
| 工作树 | 干净；无 stash；无未提交文件 | `git status --porcelain` = 空 |
| 已落地且被当前代码使用的 R3 能力 | `ProducerCostBook`、`OperatorCondition` 状态机、`StressPolicy`、`OperatorSettlement`、`HouseholdClassRule` 派生分类与 `ClassRow.withView` 写回、`HouseholdCondition` 派生读数组件 | 见 §2 |
| 未完成 | `AssetShare` 不是生产规模的权威来源（生产仍读 `Industry.capacity`）；`ProductionUnit` 只有 `ProductionUnitId` 契约；`ProductionRelation` 仍按 `IndustryId` 挂；无经验、无候选生产方式、无运行期需求账本；退出只处置债务、不处置资产份额与劳动 | 见 §2/§3 |
| 台账最新读数（**未复跑**） | `progress.md`：tick360 人口 11,562,855、货币 142,924,800、债务 1,053 条/本金 3,169,875,457；operatorConditions 1,548 ACTIVE / 188 CONTRACTING / 63 OVERSUPPLIED；阶层 landless_laborer 5,326 / landlord 799 / artisan 267 | `.superpowers/sdd/2026-09-28-pre-modern-economy/progress.md` §R3 120→360 |

---

## 2. 代码事实与缺口表

判定口径沿用《边界报告》§6：**现成可用** / **只有状态容器、缺运行期转移** / **连状态表达都缺**。

### 2.1 身份、阶层与债务

| 能力 | 事实（文件:行） | 判定 |
|---|---|---|
| 稳定家户身份 | `HouseholdId` 已存在（`simos-economy-api/.../id/HouseholdId.java`）；`ClassRow.id` / `FlowRow.id` / `Debt.debtor/creditor` 都是 `HouseholdId`（`model/ClassRow.java:66`、`model/FlowRow.java:75`、`model/Debt.java:39`） | 现成可用 |
| 阶层视图与写回 | `ClassRow.view` 是 `CohortKey`；关账日按 `HouseholdClassRule.classify` 写回（`EconomySettlement.java:1022-1045`、`ClassRow.withView:160`） | 现成可用（但见下条） |
| 阶层槽位回退 | `HouseholdClassRule.feasibleStratum` 仍按 `Industry.slots` 上限把派生阶层降级，并写 `slotCapFallback` 理由（`HouseholdClassRule.java:756`）；派生只写 `view`，不改劳动/资产/账户（`withView` 承诺） | 只有状态、缺“纯派生”收口（R3B.4 未做） |
| 使用权/份额 | `AssetShare(id, industry, asset, owner, operator, quantity, kind)`（`model/AssetShare.java:34-42`）；旧档 `Industry.capacity + operator` 一对一物化成 `owner==operator`（`migrate/LegacyHouseholdMigration.java:248-273`）；新世界播种同样整额 `OWNED` 给 industry operator（`app/world/EconomySeeder.java:1697-1730`） | 状态容器已在；**生产不读** |
| `ProductionUnit` | 只有 `ProductionUnitId`（`simos-economy-api/.../id/ProductionUnitId.java`）；main 无 `ProductionUnit` 模型、无 `units` 状态组件 | 连状态表达都缺 |
| 生产关系 | `ProductionRelation` 键/字段 `activity` 是 `IndustryId`（`api/relation/ProductionRelation.java:88-95`）；`EconomyData` 守卫要求 `relation.operator == Industry.operator`（`EconomyData.java:480-500`） | 只能一产业一经营者；多 unit 连表达都缺 |
| 债务主体 | `Debt.debtor/creditor` 是 `HouseholdId`；ESTATE/WORKSHOP/`weave@…` 这类产业 operator 无法作为债务主体（`Debt.java:39-47`） | 连状态表达都缺（“经营者债务”） |
| 经营者→家户解析 | 结算只用 `householdActorsOf(rows)`，它只把 `HouseholdActors.of(HouseholdId)` 放进映射（`EconomySettlement.java:4664-4670`）；ESTATE/WORKSHOP/`weave@…` operator 取不到家户（`OperatorSettlement.java:145`） | 现成路径只覆盖“operator 恰是家户 actor” |
| c1 孤儿债对账 | 仓库只有 `migrate/LegacyHouseholdMigration.java` 一个迁移器；未发现以债务表为权威重建 `ClassRow.debts` 的代码/命令 | 连状态转移入口都缺 |

### 2.2 资产、生产与规模

| 能力 | 事实（文件:行） | 判定 |
|---|---|---|
| 技术配方 | `Industry` 同时携带模板与实例：`operator`、`capacity`、`progressDays`、`cycleLaborMilli`、`cycleInputUsedMilli`（`model/Industry.java:108-125`）；`inputPerUnit()` 由 `cycleInputPerUnit` 纯派生（`:294-320`） | 现成可用；但一产业只能一个 operator/一份进度 |
| 生产规模（产能路） | `capacityScaleOf` 读 `industry.capacity()`（`EconomySettlement.java:3138-3146`）；`plannedCapacityScaleOf` 再乘 `StressPolicy` 计划系数（`:3154-3158`） | **AssetShare 未接线** |
| 生产规模（投入/劳动路） | 现扣投入/收获等仍以 `Industry.cycleInputUsedMilli` / `cycleLaborMilli` 为准（`EconomySettlement.java:4196、4834` 附近注释与算式） | 状态在 Industry 上，未按 unit 分账 |
| 分类器读资产 | `HouseholdClassRule` 同时读 `AssetShare`（`:163/310`）与 `Industry.capacity`（`:382/403`）作为近似 | 两条事实源并存，迁移期会漂 |
| 读写口 | `ApiViews` 仍用 `industry.capacity()` 汇总土地/资产（`ApiViews.java:524、1282`） | 读口未改 |
| 约束变化 | `EconomyData` 明确“不再有 Σ quantity ≤ Industry.capacity 的上界守卫”（`EconomyData.java:427-432`）⇒ 实物总账权威已是 `AssetShare`，但生产还没跟上 | 半迁移态 |
| `ProducerCostBook` | 唯一单位成本估计拼写点（`time/ProducerCostBook.java:45-122`），卖方成本排序已接入市场 | 现成可用 |
| `OperatorCondition` | 状态机 ACTIVE/TRIALING/OVERSUPPLIED/CONTRACTING/INDEBTED/SUSPENDED/EXITING/EXITED/ABANDONED（`model/OperatorCondition.java:71-90`）；市场证据累加与关账推进（`time/OperatorSettlement.java:77-320`） | 现成状态机 |
| 退出处置 | `SUSPENDED→EXITED` 产出 `Exit(industry, operator, household, reason)`（`OperatorSettlement.java:243-257`）；消费方 `settleOperatorExits` **只按债务表还债/置 defaulted**，剩余库存/货币留在原主体账（`EconomySettlement.java:3645-3780`） | 只有债务处置；资产份额、劳动、库存去向缺 |
| 被动缩产 | `StressPolicy.plannedScalePerMille` 只压“本周期计划”，不销毁 capacity/AssetShare（`time/StressPolicy.java:48-75`、`EconomySettlement.java:985/3151-3158`） | 现成可用 |
| 停业/退出触发面 | `OperatorSettlement.advance` 的债务压力分支要求 `householdOfActor` 命中；未命中的 ESTATE/WORKSHOP/weave operator 债务压力恒 false ⇒ 实际很难走到 INDEBTED/SUSPENDED/EXITED（代码推断，见 §4） | 只有状态、缺触发路径 |
| 劳动再分配 ≠ 转业 | `reallocateLabor` 只改 `LaborAllocation` 的 actor/量（`EconomySettlement.java:3875` 起）；行、阶层、账户、债务、人口不动；方法注释明确 `laborSupply` 不改（R2 口径） | “转业/失去生计”连状态都缺 |

### 2.3 市场、需求与消费

| 能力 | 事实（文件:行） | 判定 |
|---|---|---|
| 订单生成 | `MarketSettlement.ordersFor` 只按 `plan.necessaryInputs`（经营者投入）与 `plan.lifeReserves`（家户保留）生成订单（`:785-875`）；无价商品整条拒绝（`:419-422`） | 现成（粮/布 + 配方内商品） |
| 家户目标库存 | `householdLifeReserveOf` = 粮/布 35 天保留（`:3013-3021`）；`selfNeedOf` 对非粮/布**静默返回 0**（`:3081-3090`） | 新商品“连状态表达都缺” |
| `naturalNeeds` | 唯一写点 `withDailyNeed`（`EconomySettlement.java:5402-5424`），来源 `EconomyVocabulary.dailyNeedsMilli`（`simos-util/.../EconomyVocabulary.java:219-224`），只给 grain/cloth；布日需已进消费步（`:1393-1425`） | 只有两商品；无层次/阈值公式 |
| `effectiveDemand` | main 里零行为读者：只被读口显示（`ApiViews.java:874/1464`）与随行搬运；订单输入不读它 | 只有状态容器、缺订单路径 |
| 消费 | `consumeOneHousehold` 直接从自有库存扣粮/布，粮缺口进 `deficitToday`（饿死/借粮判据）、布缺口只进 `unmetNeed`（`:1393-1425`） | 现成但只有“库存吃到多少算多少” |
| 市场价格 | `Market(numeraire, prices)`，价格只来自播种载荷/默认关闭的自适应定价；无运行期 GM 设价命令（`model/Market.java:43-110`） | 新商品需求缺“可定价”入口 |
| 运行期需求 | 无 `DemandBook`/需求命令；`naturalNeeds`/`effectiveDemand` 每结算日被重写，不能作为跨期注入面 | 连状态表达都缺 |
| 候选生产方式 | 无 `ProductionCandidate`/候选注册命令；`Industry` 由 `economy.Seed` 只在**新格**整份播种（`EconomySeedHandler.java`），已占用格拒绝 | 连状态表达都缺 |
| 进入决策 | 无 `EconomyEntrySettlement`；无“闲置劳动/失去生计/可调拨资产 → 试产 → 失败处置”路径 | 连状态表达都缺 |
| 竞争识别 | 已有 `SellerOutcome/BuyerOutcome`、`MarketUnfilledReason`（含 outcompeted/stock_sufficient/input_shortfall/unsold_self_usable/cannot_reproduce）、`ProducerCostBook` 成本分档 | 现成可用（P3 跨区撮合仍串行） |

### 2.4 命令、迁移、变更集与并发

| 项 | 事实（文件:行） | 判定 |
|---|---|---|
| economy 命令面 | 仅 `economy.Seed` 与 `economy.MigrateHousehold`（`Shell.java:440-442`）；`EconomyMigrateHouseholdHandler` 只改视图 | 需求/候选/设价/资产转移命令全缺 |
| 状态组件 | `EconomyData` 13 组件：meta/industries/classes/debts/flows/laborSupply/allocations/relations/markets/shipments/memberships/assetShares/operatorConditions（`EconomyData.java:130-143`） | 增组件必须同步 ChangeSet/Codec/RoundTrip 反射守卫 |
| 变更集 | `EconomyChangeSet` 逐组件映射，注释自述“与 EconomyData 一一对应”（`change/EconomyChangeSet.java:36-63`）；铁律 5 往返测试反射枚举 | 现成机制，增组件即插 |
| 旧档迁移 | `LegacyHouseholdMigration` 负责 HouseholdId/Membership/AssetShare；`EconomyCodec.migrateLegacyAssetShareComponent` 兼容旧 `use-…` 串（`codec/EconomyCodec.java:351-406`） | 现成；c1 孤儿债未对账 |
| 命令载荷提示 | `CatalogTool.PAYLOAD_HINTS` 构造期要求覆盖全部已注册 type，缺项启动即抛（`app/tools/read/CatalogTool.java:55-209`）；R3 曾因此修 `economy.MigrateHousehold` | 新命令必须同步此表 |
| 多线程 | `--economy-threads N`（`ShellMain.java:83-84`、`ShellConfig.java:71-72`）；市场按区分区并行、协调器固定回放（`MarketSettlement.clearOncePerCycle` 类注）；1/4/8 领域等价**未验** | 机制在；验收未做 |

### 2.5 经验积累

| 能力 | 事实 | 判定 |
|---|---|---|
| 经验状态/读数 | 全 main 无 `Experience`/`learning`/`skill`/`proficiency` 类型；grep 唯一命中是 `EconomySettlement.java:2604` 的“经验实证”措辞 | 连状态表达都缺 |
| 稳定主体上的经验延续 | 无状态可挂；`ClassRow.view` 变化只改标签（`withView`）⇒ 若将来挂在 HouseholdId 上，阶层变化不会丢；家户拆分/迁移规则未实现 | 缺 |
| 地区产业实际规模 → 经验速度 | 无该输入/公式；产业实际规模目前只有 `Industry.progress/cycleInputUsed`（未按 unit 分账） | 缺 |
| 工艺质变 | 只有 `economy.Seed` 静态配方；无 GM 登记新预设/版本的命令面 | 缺 |

---

## 3. 四项演化目标 × 当前缺口（汇总）

| 目标 | 已经能用的部分 | 必须补的状态/路径（按依赖排序） |
|---|---|---|
| **1. 旧生产方式衰退，家户与经营者改变处境** | 被动缩产（StressPolicy）；市场原因读数组件；`OperatorCondition` 状态机；家户派生 `HouseholdCondition`；退出时债务偿还/违约 | ① `ProductionUnit`（让同一产业的经营者可分别结算）；② operator→经济家户解析（ESTATE/WORKSHOP 债务/生存）；③ 退出时 `AssetShare.operator` 合法处置（退回 owner / 转移给具名主体）与劳动配额释放；④ 经营者库存/货币去向与“可自用维生≠破产”的门；⑤ c1 孤儿债对账 |
| **2. 新需求引发已有/新预设的采用** | `economy.Seed` 可造新格产业/配方；`Market` 可带任意商品价格（播种态）；`ProducerCostBook` 可算可观察成本 | ① GM 设价命令；② 运行期需求账本 + `AddDemand/CancelDemand`；③ `ProductionCandidate` 预设库（含版本）+ `RegisterCandidate`；④ 订单生成读需求（不只粮/布 35 天）；⑤ `EconomyEntrySettlement` 可行性/生计/试产；⑥ 资产使用权转移命令；⑦ 无可行预设时的“未满足”读数 |
| **3. 从生产实践积累经验** | 实际生产规模/劳动/产出在会话与账本里可见；家户身份稳定 | ① 经验状态（挂稳定 HouseholdId × 生产方式键）；② 只在**实际劳动+实际完成产出**时计提；③ 所有者/纯收租者不计提；④ 拆分/转业/迁移的保留/分配规则；⑤ 按当前阶层×生产方式聚合读口；⑥ 地区规模的有界渐近系数；⑦ 预设内效率增量上限 + GM 读口，不自动质变 |
| **4. 收入与需求层次梯度消费** | 粮/布日需口径；35 天保留；自有库存消费；`naturalNeeds/effectiveDemand` 字段形状 | ① 需求层次配置（基本/期望/上限/递减阈值）；② 人均四项量的计算并聚合到家户；③ `effectiveDemand` 进订单路径；④ 可支配资源=货币+实物+库存−租赋−偿债；⑤ 基本缺口与改善缺口分开；⑥ 新需求反馈到目标 2 的进入决策 |

---

## 4. 本报告未核实 / 需在实施切片里验证的点

1. **未复跑**任何 tick：所有宏观数字来自台账，不是本次实测；1/4/8 线程在 R3B.1 后尚未重比。
2. **operator→家户解析的现状后果**（ESTATE/WORKSHOP 不会 INDEBTED/SUSPENDED/EXITED）是**代码推断**：`OperatorSettlement.advance` 的债务压力分支要求 `householdOfActor.get(industry.operator())` 非空；未跑出“确实无退出”的受控探针。
3. **c1 孤儿债**：只在《边界报告》与 R3 决策单里读到，未在当前 store 上统计条数、未验迁移器是否会碰到。
4. **社会侧 `PopulationGroup` 与 `Membership` 的逐 lot 运行时对账**：迁移器/协调器有 fail-closed 代码，但未在真档上跑过“逐 lot 相等”的断言。
5. **`economy.Seed` 的 id-格错位旁路**：R0 提交自称已在载荷层修（`EconomyPayloads` 校验 entry 格与 industry id 格一致），本次未构造完整命令链复核权限层。
6. **新世界的多 unit 可行性**：seeder 目前一产业一 unit 形状，未验“同一 hex 两个 unit 且各自产出/账户”在现有结算中的实际表现（预期失败，因 relations/capacity 仍按 IndustryId）。
7. **性能/内存**：360 tick 全年与峰值内存没有本 HEAD 的实测；台账中的 90 tick 时长属于 R3 之前/之中的代码态。
