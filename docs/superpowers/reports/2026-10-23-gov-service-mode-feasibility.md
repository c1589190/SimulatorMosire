# 2026-10-23 政府专属生产方式（GOV 雇主 / 官吏劳动承诺 / 服务产出）只读可行性排查

> **任务**：评估用户「单个政府配置政府专属生产方式；政府所需劳动既可从政府自管家户获得，也可作为需求挂到市场让其他经济家户加入，为政府提供劳动」能否用现有机制实现、缺什么、怎么分批。
> **纪律（本次如实遵守）**：只读代码/文档/日志；**未改任何代码、未跑 Maven/测试/服务、未 `git add`/`commit`**；唯一写盘文件 = 本报告。
> **证据基线**：当前工作树 `be6ef44b`（工作树干净）；两份同日只读报告
> `docs/superpowers/reports/2026-10-23-gov-tax-treasury-investigation.md`（F1~F4，19 hex / 360 tick 真档 run5）
> 与 `docs/superpowers/reports/2026-10-23-editability-and-efficiency-investigation.md`（economy 命令/工具面能力矩阵）。
> 凡引用台账/计划措辞，均按 AGENTS §四回代码核过；代码与旧文档冲突以代码为准。
> **0 命中的自证与未核边界**见 §7；用户原话与冻结裁定引用见 §8。

---

## 0. 用户原话（逐字）与本次追加裁定

### 0.1 用户原话（逐字，不得改写；来自本会话父代理转达的任务书）

> 我的想法是，单个政府可以配置一个政府专属的生产方式，政府自己需要的劳动力，既可以从政府自己管的家户里获得，也可以作为需求，挂一个生产方式到市场，允许其他经济家户加入，然后为政府提供劳动，这个能实现吗？

### 0.2 本会话已冻结的前提（父代理 2026-10-23 转达；本报告不得与之冲突，冲突处已列 §0.3）

| # | 冻结前提 | 报告内简称 |
|---|---|---|
| P1 | **官吏=全职**：岗位是对家户劳动的显式承诺（类似生产的 `HouseholdLaborCommitment`），承诺后这部分小时不再进生产队列；不变量 `Σ承诺 ≤ 可用劳动` 延伸到 gov | 全职承诺 |
| P2 | **政府本户 `hh-gov-<unitId>` 保持 0 人口纯财政**（国库账户主体）；官吏住自己的 `hh-unit:<unitId>` 家户 | 政府本户 0 人口 |
| P3 | **行政效率公式（待落地）**：`总行政效率‰ = 治安效率‰ × 公文效率‰ ÷ 1000`；每维 `= 满足率 × 静态修正‰/1000 × 动态修正‰/1000`；供给=该维**承诺劳动**；需求=`GovDemand` 需求人数 × 标准劳动系数（全局默认 ADULT/MALE=16,000 毫小时/tick）；超出需求部分按 `有效 = 需求 + ⌊√(超额人当量 × k)⌋ × 定额`；**总效率/静态/动态全不封顶**（溢出 `Math.*Exact` 具名 ERROR）；静态修正进**新建 gov 源状态 + gov 首个 command handler + GM 工具**；动态修正每 GOV 两个‰、app 逐 tick 注入、GM 不可达；没有挂岗位家户 ⇒ 该维供给 0、效率 0、记具名 INFO | 效率公式 |
| P4 | 家户劳动权威 = `SocialData.householdLaborMilli(household, day, clock)`（成员份额 × `LaborCoefficient.milliHoursPerTick`；系数由 `simos.social.labor`/`social.SetLaborCoefficient` 配置全局默认+家户覆盖）；app 每 tick 注入经济侧 `HouseholdEconomy.laborMilli`；经济生产投入 = Σ `HouseholdLaborCommitment.laborMilli`（R2；`participationPerMille` 不参与生产门槛） | 劳动权威 |

### 0.3 父代理追加的用户裁定（2026-10-23，必须纳入方案）

| # | 追加裁定 | 报告内简称 |
|---|---|---|
| R1 | **政府产出 = 像"运力"一样的逐 tick 流量**：不能存到第二 tick、不进家户库存/商品市场存量；当 tick 产生、当 tick 被行政效率消费，未用完即失效 | 服务流量 |
| R2 | 政府配置类命令（专属生产方式、岗位角色、国库预算优先级等）由**决策人可直接调用，但走 GM 审批链**（`ToolGate.Ask → AutoApproveGate → ConfirmGate → PendingApprovals`）；不是只 GM | 决策人+审批链 |
| R3 | **岗位角色由政府设置**，当前先设 **3 档普通基层**；给出到 `StaffRole(YAMEN/SCRIBE/POST)` 与二维（治安/公文）的候选映射 | 三档岗位 |
| R4 | **国库预算优先级由政府决策人自己调整**（同走审批链）；核对现有支出顺序与可行状态形状 | 预算优先级 |
| R5 | **创建/修改产业模板（`upsertIndustry`）已纳入本批范围**（即既有报告 §2.2 第 2 条缺口）；需设计版本化、既有 unit/assetShare/进度处置、守卫、负向用例 | 产业模板编辑 |
| R6 | **gov 只读查看工具缺口已确认**（现只有 GUI `GET /api/economy/gov`，无 MCP 读工具、无 `/gov` 页）；Zones 里补 gov 只读工具（两维效率、劳动供给/需求、承诺、编制/岗位、生产方式配置、国库/预算） | gov 只读口 |

### 0.4 与代码现状的冲突清单（先读；这些是"不能照旧实现"的点）

| # | 冻结前提/裁定 | 代码现状（file:line） | 判定 |
|---|---|---|---|
| C1 | P2：官吏住 `hh-unit:<unitId>`，`hh-gov-*` 0 人口 | `GovRecruitPlan.java:54-63` 把真实成员 `TRANSFER_MEMBERS` 到 `hh-gov-<unitId>`；`GovAbsorbUnitPlan`/`GovRetireStaffPlan` 同向；`GovCreateOfficePlan.java:398-410` 只建 `hh-gov-<unitId>`，全文件无 `hh-unit`；`hh-unit:<unitId>` 目前只在 `RaiseUnitPlan.java:292-295`（以及 `GovSelectExaminees`/`GovDispatchTeam` 新建单位时）作为"单位人口家户"生成 | **冲突**：现有招募/退休/吸收工具的方向与 P2 相反；需要新增"官吏个人/岗位家户"生成与迁移路径，或改这些工具 |
| C2 | P3：效率供给 = 承诺劳动 | `GovEfficiency.java:95-106` 供给 = `staff[YAMEN]` / `staff[SCRIBE]+staff[POST]`；`GovDaily.java:488-494` `totalStaff` 直接加 `staff`；`simos-gov/src/main` 对 `HouseholdLaborCommitment`/`laborMilli`/`16000` **0 命中**（§7 自证 3） | **结构性冲突**：gov 模块今天看不见承诺劳动，供给必须由 app 计算后作为输入传入（模块边界） |
| C3 | P3：全不封顶 | `GovOfficeState.java:105-118` 构造期把 `security/paperworkCoverage∈[0,1000]`、`efficiency∈[0,1100]`、`bonus∈[0,100]` 判死；`GovEfficiency.Efficiency` 同款边界 `GovEfficiency.java:187-217` | **冲突**：改公式前必须先改读数类型/边界，否则具名抛 |
| C4 | P1：岗位=显式劳动承诺；P2：官吏住独立家户 | `GovernmentFormation.staff` 仍是显式人数权威（`GovernmentFormation.java:48-55`）；`governmentPostsOfHousehold` 只绑家户+角色、**不存人数/小时**（`GovernmentPostOfHousehold.java:28-41`）；`staffIsHouseholdProjection()` 只有定义、全 main 无调用者（`GovernmentFormation.java:106-109`）；`GovApplyStaffing` 仍可直写 staff（`GovApplyStaffingTool.java:244-278`，不受 `requireStaffNotProjected` 拦） | **冲突**：staff/posts/承诺三者没有一处真相 |
| C5 | R1：逐 tick 流量，不进库存/市场 | 现有生产产出一律经 `EconomySettlement.harvest` 进家户库存/ledger（`EconomySettlement.java:7260-7284`）；商品市场 `Market` 只有 `numeraire+prices`（`Market.java:46-70`）；期初/期末没有任何"过期服务"类型 | **冲突**：政府产出不能走既有商品产出路径 |
| C6 | 用户说"挂到市场" | 现有 `Market`/`MarketSettlement` 是纯商品现货市场；`economy-api/market` 包内 `labor` 0 命中（§7 自证 4） | **冲突**：没有劳动力市场；"挂到市场"必须解释为 `ProductionEnterprise.laborSources` + 劳动队列（行政分配队列）或新建岗位需求原语 |
| C7 | P1：承诺后不再进生产队列 | `LaborQueueSettlement` 只有"非排队 unit 的既有承诺保留"（`LaborQueueSettlement.java:202-216`）；若 GOV unit 的 offer 可排队，其承诺会被队列按利润率重算甚至缩小；`preserveIntoBudget` 在超预算时还会按比例缩保留量（`:554-586`） | **冲突**：全职承诺目前没有"不可缩/最高优先级"语义，需要新 kind/新优先级或队列改动 |
| C8 | P3：需求 = GovDemand 人数 × 16,000 | 16,000 在代码里有**两个拼写点**：`SocialProvisioning.java:167` 的 ADULT/MALE 默认（FEMALE=8,000、CHILD=4,000、ELDER=0，`:163-171`；这是当前 `householdLaborMilli` 的实际权威链）与 `HouseholdLaborTimeTable.DEFAULT`（`simos-economy-api/.../labor/HouseholdLaborTimeTable.java:47-48`，4,000/16,000/8,000/0 的旧表默认）；GovDemand 输出的是人数（`GovDemand.java:62-96`），gov 模块无任何人数→小时换算 | **缺口**：标准系数是"某一档"的默认（且有两处同名默认需要收口），不是通用每人系数；维数/档位口径待裁定 |
| C9 | R2：决策人可调用配置命令 | 现有三条决策人窄写（`SetDiplomaticRelation`/`RecordDiplomaticEvent`/`GovPay`）是"决策人桶 + 白名单 + 审批链"的现成形态：`DecisionCallerFactory.java:105-163`、`SimosToolSource.java:778-806`、`Shell.java:848-872`；`AskGate` 在 AgentLib（`io/mosire/agentlib/approval/ToolGate$Ask.class`），本仓只有 `AutoApproveGate→ConfirmGate→PendingApprovals` 装配 | **不是冲突，是落点**：新 gov 配置工具照 `GovPayTool` 形态做"身份派生 + 决策人桶 + 白名单"，GM 侧并列一条无脑过的同命令工具 |

---

## 1. 一句话结论 + 最小可用形态

**结论：能实现，但今天零代码只能把"数据形状"搭出来，运行期建产业/建承诺/算效率/发工资四段都缺；最小可用形态需要"空商品产出的服务产业 + GOV 岗位承诺 + 逐 tick 服务流量读数 + 两维承诺劳动效率 + ADMIN_SALARY 工资 + `upsertIndustry` + 决策人审批链工具"六件套一起补。**

**最小可用形态（建议口径，等 §6 裁定后冻结）**：

1. **雇主主体**：`ActorRef(HOUSEHOLD, "hh-gov-<govUnitId>")` 当 `ProductionProcess.operator` 与 `ProductionEnterprise.organizer`（0 人口、国库账户；数据形状今天已支持，`EconomyData` 不限制 operator kind；见 Q1）。
2. **产业/生产方式**：新增 gov 专属 `ProductionMode`（如 `government_office`）+ 3 个 `ClassPosition`（对应三档基层岗位），在 GOV 座位格建 `office@<hex>` 类 `Industry`：`capacityPerUnit` 非空（用一个象征性锚，如 `{TOOL:1}`）、`laborPerUnit=标准劳动系数`、**`outputPerUnit = Map.of()`（空商品产出；服务流量不进商品表）**；由 R5 的 `upsertIndustry` 在已播种格运行期创建。
3. **岗位承诺**：官吏家户 `hh-unit:<unitId>`（P2）的成员劳动，以 `HouseholdLaborCommitment`（或带显式 kind 的新承诺表）挂到 GOV unit 的 activity，`actor=hh-gov-*`；三档由 `GovernmentFormation.governmentPostsOfHousehold` + 新 gov 源状态的 3 档角色表配置；Σ承诺计入 `EconomyData` 的 `Σ ≤ laborMilli` 不变量，并被劳动队列视为不可缩的 preserved/最高优先级。
4. **服务流量（R1）**：每个 tick 由 app 的推进参与者从承诺劳动 + `GovDemand` 现算 `GovServiceFlow`（进程内读数，形如 `MarketReport`/`MarketReadout`：不落盘、重启即失、`unavailable` 具名）；只把结果读数（两维满足率/效率）写进 `GovOfficeState`；服务量**不**进 `EconomyData` 库存、**不**进 `Market`、**不**进家户账户。
5. **效率**：`GovEfficiency.of` 改为接收"该 GOV 两维承诺劳动"输入（app 计算），按 P3 公式输出；`GovOfficeState`/`Efficiency` 的上限守卫同步改为"不封顶 + 溢出 `Math.*Exact` 具名 ERROR"。
6. **工资**：新增 `DeductionReason.ADMIN_SALARY`（不要复用明文定义为 sink 的 `ADMIN_UPKEEP`），由"承诺小时 × 工资率"的 bridge 每天派生 `HouseholdPeriodicAdjustment`（`periodDays=1`），付款方 `hh-gov-*`、收款方官吏家户；国库先由 bootstrap/财政工具注资；预算优先级按 R4 的 per-GOV 有序类别表。
7. **工具面**：配置命令 = 一条非 `GmOnly` 命令 + 决策人窄工具（身份派生自己的 GOV，`DecisionCallerFactory.WHITELIST` + `addDecisionAgentWrites`，走审批链）；GM 侧并列一条同命令的无脑过工具；另加 R6 的 gov 只读工具。

---

## 2. 现有机制复用表（机制 → 位置 → 能直接用的部分 → 缺什么）

| 机制 | 位置（file:line） | 能直接用的部分 | 缺什么 |
|---|---|---|---|
| `ProductionProcess.operator` 是不透明 `ActorRef` | `simos-economy/.../model/ProductionProcess.java:45-59`；`ActorRef.java`；`ActorKind.java` | 数据形状允许 `HOUSEHOLD:hh-gov-*`（甚至可以填 UNIT/GOVERNMENT kind；但账户主体解析只认家户，见 Q1） | 运行期建 unit/industry 的命令；`EconomyEntrySettlement` 只允许有户口的家户当 operator，GOV 本户 0 人口进不去 |
| `ProductionEnterprise` + `upsertProductionOrganization` | `EconomyGmAdjustments.java:968-1144`；`ProductionEnterprise.java:44-120`；`EconomyData.java:1310-1350` | organizer/laborSources/inputSources/outputOwnership/status 都是显式字段；organizer 可 HOUSEHOLD；`laborSources` 是"其他家户加入"的现成入口 | `upsertProductionOrganization` 只写组织，不建 unit/industry/assetShare；organizer 必须与 unit.operator 逐值一致 |
| `EconomyData.allocations` + `HouseholdLaborCommitment` | `HouseholdLaborCommitment.java:48-81`；`EconomyData.java:233,803-890` | 跨家户承诺合法：`household`=出劳动的家户、`actor`=收劳动的 unit.operator（可 `hh-gov-*`）；构造期强制 `Σ commitments(household) ≤ laborMilli` | 无 kind/role 字段；无直接写命令（只有 Seed/队列/候选/自动组织/迁移）；GOV 岗位无创建路径；队列可缩承诺（C7） |
| 每 tick 劳动队列 | `LaborQueueSettlement.java:120-143,181-216,228-284,406-438,503-552`；`LaborQueueBook.java:171-239` | 多户可给同一 unit 出劳动；候选户 = operator/组织者 organizer/`laborSources`/既有承诺户；写回时 `actor=unit.operator` | offer 门槛是"产能锚 × 投入 × 有价产出"；空产出服务 unit 不进队列（只 preserved）；排队按 unit profitability 而非工人工资；无"岗位需求"原语 |
| 生产关系的货币工资 | `RuleType.java:40-50`；`ProductionSettlement.java:368-411`；`EconomySettlement.java:7260-7284,7307-7311` | `FIXED_MONEY_WAGE` 可指定币种、受方 `Payee.ToHousehold`，付款方=relation.operator，上限=付方可用货币；欠额进读数不落债 | `harvest` 在无任何正产出时**整段早退**，货币规则也不结算；工资与"承诺小时"无关；工资仍依赖一个产出商品 |
| 军事俸禄桥（政府工资最佳先例） | `MilitaryPayRuleBridge.java:42-57,85-251`；`PeriodicHouseholdAdjustmentExecutor.java:212-325`；`HouseholdPeriodicAdjustment.java:48-120` | payer=`hh-gov-<masterGov>`、payee=政策家户；日派生、逐腿 `min(可用,请求)`、全 0 具名 `no-payable-leg`、有实付 `transfer`；不依赖产出/地点 | `DeductionReason` 无 ADMIN_SALARY（`DeductionReason.java:19-35`；ADMIN_UPKEEP 明文是 sink）；按承诺小时算工资需新 bridge；P4c 无共享预算（`PopulationEconomyTimeParticipant.java:529-602` 固定顺序） |
| `GovEfficiency`/`GovDaily` 纯函数 + `GovState` 读数 | `GovEfficiency.java:65-106,128-217`；`GovDaily.java:224-258,488-494`；`GovState.java:13-39`；`GovOfficeState.java:43-118` | "纯函数 + ‰ + 读数落盘 + 具名缺口"形态可复用；`GovState` 已是 gov 唯一组件 | 供给读 staff；公式/上限是旧口径；gov 无 handler、无源状态编辑口；两维承诺劳动要 app 传入 |
| 逐 tick 进程内读数（R1 同形机制） | `MarketReport.java:1-80`（不落盘、重启即失）；`MarketReadout.java:30-90,600-660`（读不到进 `unavailable`、不填 0）；`MerchantFirm.java:243-268` + `MerchantSettlement.java:520`（capacityPerRound + 每轮 reset） | 三种同形先例：① 纯进程内报告；② 派生只读读数；③ 持久容量+每轮归零+未用读数 | gov 没有这类流量的产生/消费接线；`GovOfficeState` 现在只存结果、且封顶 |
| `GovernmentFormation.governmentPostsOfHousehold` | `GovernmentFormation.java:48-109`；`GovernmentPostOfHousehold.java:28-41`；`SetGovernmentFormationHandler.java:88-94`；`UnitOperations.java:812-860` | 已是"家户+角色"的槽位；`UnitState` 守卫 posts 键 ⊆ `Unit.households`；`setGovernmentFormation` 自动把 gov 家户编入列表 | 不含人数/小时/维度权重；只覆盖领导层语义；与 staff 无一处真相；官吏家户落点与 P2 冲突（C1） |
| `economy.Seed` | `EconomySeedHandler.java:88-134`；`EconomyPayloads.java:304-550` | 首次播种可整份写 industries/units/assetShares/relations/allocations/classes；已激活后**按格追加**，格无产业/阶层行即可 | 已播种格整拒；无运行期 upsert；不能指定"只加一个产业"到已有经济的格 |
| 决策人工具 + 审批链 | `DecisionCallerFactory.java:105-163`（WHITELIST）；`SimosToolSource.java:778-806`（决策人桶）；`Shell.java:848-872`（两条 authorizer）；AgentLib `ToolGate$Ask/AutoApproveGate/ConfirmGate/PendingApprovals` | 新 gov 配置命令照 `GovPayTool` 形态：身份派生自己的 GOV、只进决策人桶+白名单、自动走审批链；GM 桶并列同命令工具 | 尚无 gov 配置命令/工具；`economy.GmAdjust` 是 `GmOnly`（三层排除），不能直接给决策人用 |
| GUI gov 读口 | `ApiViews.java` 的 `economyGovernment`（`/api/economy/gov`，只读投影 `treasuryAccounts`） | 数据齐全：政府列表、国库库存、staff（经 unit 视图）；AGENTS §8.3 要求 GUI/MCP 共用同一份视图层 | 无 MCP 读工具、无 `/gov` 页（R6）；也没有两维效率/承诺/预算读口 |
| `upsertIndustry` 的先例（R5） | 版本化先例 = `ProductionMode.version` + `EconomyGmAdjustments.java:304-366`（新建缺省 1、修改必须显式推进、同 version 改值具名拒、幂等重放 no-op）；写口先例 = `GmAdjust` 的 12 个 action（`EconomyGmAdjustments.java:92-128,182-194`） | 版本规则、幂等/审计/ChangeSet 形态可直接照抄 | 现有 12 action **无** industry/unit/assetShare/allocation（§7 自证 1）；`Industry` 无 version 字段、unit 只引用 `IndustryId`，原地改会静默影响既有 unit |

## 3. 逐问题（1~10）

> 每条按「事实链（file:line）→ 缺口 → 候选方案（改动面/影响/与铁律 2/3/4 的关系/风险）→ 分类」写。
> 代码现状与冻结前提冲突处已在前文 §0.4；这里不重复。

### Q1 政府当经营者/雇主：GOV 或 `hh-gov-<unitId>` 能不能成为生产单元/组织的 operator/organizer？

**事实链**

1. **operator 的类型不限制 kind**：`ProductionProcess.operator` 是 `ActorRef`，构造期只判非 null（`simos-economy/.../model/ProductionProcess.java:45-59`）；`ActorRef` = `(ActorKind, String)`，`ActorKind` 词表含 `PEOPLE_LOT/UNIT/GOVERNMENT/ORGANIZATION/HOUSEHOLD`（`simos-actor-api/.../ActorKind.java`）。`EconomyData` 对 unit 的守卫只要求：键 == id、industry 模板存在、`progressDays ≤ cycleDays`；**没有 operator kind 限制**（`EconomyData.java:1012-1042`）；`units` 不要求必须有 assetShare（`EconomyData.java:1043-1046`）。
2. **但账户主体只认家户**：`HouseholdRouting.requireHouseholdOf` 对非 `HOUSEHOLD` actor 具名抛（“账户主体只有家户”，`HouseholdRouting.java:44-51`）；`subjectOf` 的解析顺序是 ① `SettlementIndex.economicHouseholdOf(unit)`（= `EconomicHouseholdResolver.resolve`：operator 若 HOUSEHOLD → ② relation.residualOwner 若是家户 actor → ③ relation.inputSupplier（ToHousehold/ToActor 反查）→ ④ unit 名下 OwnershipStake 的 owner/operator 反查）→ ② unit.operator 本身若 HOUSEHOLD → ③ enterprise.organizer 若 HOUSEHOLD → ④ 该 unit 名下承诺的家户集合（集体主体）→ ⑤ unresolved（`HouseholdRouting.java:95-139`；`EconomicHouseholdResolver.java:60-110`）。
   ⇒ **`hh-gov-<unitId>`（HOUSEHOLD actor）作 operator 可以直接把产出/付款路由到国库家户账户**；**GOV Unit（ActorKind.UNIT）作 operator 本身不能持账**，必须靠 relation.residualOwner/inputSupplier 或份额 owner 指向 `hh-gov-*` 才能把账落到国库（`EconomicHouseholdResolver.java:73-105`）。
3. **`upsertProductionOrganization` 的守卫**（`EconomyGmAdjustments.java:968-1144`）：
   - `modeId`/`classPositionId` 必须已存在，且 position 属于 mode（`:979-1006`）；
   - organizer 任意 `ActorRef`；**id 缺省派生时才要求 organizer 是 HOUSEHOLD**（`:1010-1042`）；
   - 给 `unitId` 时要求 unit 存在且 `unit.operator().equals(organizer)`（`:1047-1060`）；
   - ACTIVE/EXITING 必须有 unitId，SHORTAGE 必须有具名 reason（`:1110-1125`）；
   - laborSources/assetSources/inputSources 引用必须存在且形状合法（`:1062-1108`）。
   ⇒ 数据上 organizer 可以就是 `HOUSEHOLD:hh-gov-*`（甚至 `UNIT:gov-unit-*`，只要与 unit.operator 一致），但**它不创建 unit/industry/份额**。
4. **`EconomyData` 的组织/关系跨表守卫**：relation.operator == unit.operator（`EconomyData.java:941-948`）；enterprise.organizer == unit.operator（`:1310-1326`）；enterprise.assetSources 的份额 operator == organizer（`:1335-1345`）。所以“GOV Unit 当 operator + 政府家户当 organizer”被拒；若走 GOV Unit operator，只能自己当 organizer（非家户）或让 relation.residualOwner 指政府家户。
5. **unit 的创建路径**（今天）：
   - `economy.Seed`：payload 显式 `units[]` + `industries[]` + `assetShares[]` + `allocations[]` + `relations[]`（`EconomyPayloads.java:304-550`；unit 解析 `:366-450`；relation 推导/校验 `:945-1005`）。首次播种（`meta` 空）整份替换；已激活后**按格追加**，任一格已有产业/阶层行 ⇒ 整份拒（`EconomySeedHandler.java:88-134`、占用判据 `:117-134`、`occupiedHexKeys :241-252`）。
   - `EconomyEntrySettlement` 候选采用：operator = 采用者家庭 actor（`HouseholdActors.of(household)`），要求 `householdEconomy.population() > 0`（`:356-360`），unitId = `ProductionUnitId.idOf(industry, actor)`（`:385`），materialize industry/unit/relation/condition/commitment（`:868-915,952-963`）。
   - `EconomyEnterpriseSettlement` 自动组织：只对 `economy.classes` 里有 class membership 的家户组织，operator/organizer = `HouseholdActors.of(household)`（`:304-360,440-456,497-527,699-727`）。
   - 旧档迁移/E2b 试产等：`LegacyHouseholdMigration`、`EconomySettlement` 的试产路径；都不是 GM 可调命令。
6. **政府登记与建 GOV 组合工具**：
   - `economy.RegisterGovernment`（GM-only，`EconomyRegisterGovernmentHandler.java:89` 起）：写 `classes`（政府家户，默认 population/labor/participation=0）、可选 `classStandings`、`governments`；`Government.treasury` = `HouseholdActors.of(hh-gov-<govUnitId>)`；`governmentId=gov-unit-<govUnitId>`（`:100-140` 类注 + handler 主体）。**不建 unit、不建 industry、不建 shares/allocations**。
   - `simos.gov.createOffice`（组合工具，`GovCreateOfficePlan.java:327-347`）：`unit.CreateUnit → social.CreateHousehold(hh-gov-<unitId>, location=UNIT) → actor.EnsureHouseholdAccount → unit.SetGovFormation → economy.RegisterGovernment → [jurisdiction] → sd.CreateDecisionMaker → PutInfo`；`registerGovernmentPayloadJson` 不传 population/labor/issuable/seigniorage/debt（`:419-425`）；**不写 posts、不建 hh-unit、不建经济 unit/industry**。
7. **前置**：
   - `Industry` 模板必须存在（unit 守卫；`EconomyData.java:1028-1032`）；
   - `AssetShare` 的 `industry` 必须已存在（`EconomyData.java:902-912`）；production scale 只从 `Σ OwnershipStake{industry==unit.industry && operator==unit.operator}` 派生（`ProductionProcessBook.capacityScaleOf`，见既有报告 §3.1）；
   - `ProductionRules` relation 可以缺（`Relations` 表缺键 ⇒ 产出全留 operator 的等价路径，`EconomySettlement.java:7285-7288`），但要有工资/分账就必须显式 relation；
   - `OperatorCondition` 可以缺（`ProductionProcessBook` 视作 ACTIVE/中性 1000‰，既有报告 §3.1；`GmAdjust` 明确拒直写 operatorConditions）；
   - `AssetRule` 只在 E2 需要租佃/资产规则时必需（`EconomyEnterpriseSettlement` 的 rentRule 路径）；显式份额不走它；
   - `ProductionMode`/`ClassStructure`/`ClassPosition` 只在建立 `ProductionEnterprise` 或 E2 自动组织时需要（`upsertProductionOrganization` 引用守卫）；一个裸 unit 没有 enterprise 也可以被劳动队列按“operator/既有承诺户”加入，但无法把其他 `laborSources` 户列为候选。

**缺口**

- **A. 已播种格上建 office 产业/unit**：`economy.Seed` 整格拒；`GmAdjust` 12 个 action 无 `upsertIndustry`/`upsertUnit`/`upsertAssetShare`/`upsertAllocation`（§7 自证 1、2）；候选采用不能建**新产业**（见 Q2 事实链 4）、且要求 operator 户人口 > 0，GOV 本户 0 人口进不去。
- **B. 政府家户的 assetShares**：没有“新建份额给已存在产业”的 GM action；`economy.TransferAssetShare` 只能转移既有份额到新 owner/operator（`TransferOwnershipStakeHandler.java:92-108` 载荷），不能凭空建；Seed 可以，但受格占用限制。
- **C. 政府家户的 entrepreneur/组织**：`upsertProductionOrganization` 可以建组织，但必须先有 unit（且 unit.operator == organizer）。
- **D. `hh-gov` 人口/劳动**：今天默认为 0；若走候选采用/自动组织路径会失败；冻结 P2 又明确它应保持 0 ⇒ 不能靠给政府家户“造人口”绕过，必须新增 GOV 专属的建 unit/承诺路径。
- **E. GOV Unit 与国库**：若坚持“GOV 单位本身当 operator”，必须额外把 relation.residualOwner 或某一份额 owner/operator 指向 `hh-gov-*` 才能把账落到国库；且 enterprise.organizer 必须等于 unit.operator（GOV Unit），不能是政府家户。工程上更绕，且 `HouseholdRouting` 的 `direct operator` 路径只在 HOUSEHOLD 时命中。

**候选方案**

| 方案 | 改动面 | 影响 | 铁律 2/3/4 | 风险 |
|---|---|---|---|---|
| **Q1-A（推荐）`hh-gov-<unitId>` 当 operator/organizer** | 新增 GOV 专属的 unit/industry/组织创建命令或 bootstrap 载荷；不动 operator 类型 | 产出/工资/账户都天然落到国库；组织/laborSources 可挂在同一 actor；与 P2 一致 | 写口走命令/revision（2）；economy 只认 ActorRef，不依赖 unit/gov（3）；app 组合（4） | 政府家户 0 人口 ⇒ 不能作为 laborSource/operator 户参与劳动力贡献；它的 `laborMilli=0`，Σ 不变量只允许其他家户的承诺（actor=它）——需要确认这不触发任何“operator 必须有劳动”的假设（现有队列只把 operator 户当候选，0 预算跳过；产出主体仍可解析） |
| **Q1-B GOV Unit 作 operator + 政府家户作 relation.residualOwner/份额 owner** | 新建 unit 时允许 operator=UNIT:gov-unit-*；组织 organizer 必须也是 GOV Unit；relation 显式 residualOwner=hh-gov | 账户路由靠 `EconomicHouseholdResolver` 的 ②/③；组织/工具面要接受非家户 organizer；`candidateHouseholdsOf` 不把 GOV Unit 解析成家户，但会从 laborSources/既有承诺取户 | 同 A，但跨表语义更绕；需要确保 `unit.operator` 与 relation.operator 都是 GOV Unit 时，工资/产出的付款方解析不失败 | `HouseholdRouting.subjectOf` 的账户解析在“无 residualOwner/无份额/无承诺”时会 unresolved；enterprise organizer 也必须是 GOV Unit（不能是政府家户），组织和财政主体分离，读口更难懂 |
| **Q1-C GOV Unit operator，产出/付款走集体家户（worker collective）** | 无新增；全靠 commitments 的 household 集合 | 产出分给劳动者，不是政府；不符合“政府雇主”语义 | 形式合法 | 语义错误，不推荐 |

**分类**：Q1-A = **结构改动**（需要新命令/组合工具，跨 economy+app；若走 Seed 只在未占用格=零代码可搭一部分）；Q1-B = **结构改动**（类型面小，但语义绕）；Q1-C = 不采纳。**待用户裁定 1：雇主主体选 A 还是 B**（建议 A，理由是 P2 的 0 人口政府家户 + 单一账户主体）。

---

### Q2 产业模板创建：`office@hex` 今天怎么建；空 `outputPerUnit` 是否允许；`upsertIndustry` 缺口

**事实链**

1. **`Industry` 形状与空 output 合法性**（`Industry.java:104-138,192-329`）：
   - 12 参新构造器字段：`id,name,regime,cycleDays,capacityPerUnit,dailyInputPerUnit,dailyLaborPerUnit,laborPerUnit,outputPerUnit,cycleInputPerUnit,slots,allocation`；
   - `outputPerUnit` **可以为空 map**（`null` 才抛；构造期逐值只要求 ≥0，`Industry.java:262-278`；类注明确“无产出用空 map”）；
   - `capacityPerUnit` **不得为空**，且逐值必须 > 0（它是“单位规模”的锚，`:280-298`）；
   - `slots` 非空、id 不重复（`:309-329`）；`cycleDays ≥1`（`:205-210`）；`laborPerUnit ≥0`、`dailyLaborPerUnit ≥0`；`allocation`/`regime` 非 null。
2. **今天建产业的路径**：
   - **`economy.Seed`**：首次整份替换 / 已激活按格追加（未占用格）；`EconomyPayloads.toData` 解析 `industries[]` 并落 `Industry`（`EconomyPayloads.java:304-550`，industry 解析约 `:341-350`）。`economy.Seed` 没有专门的 GM 窄工具，但 GM 可用 `simos.command.submit`（GM 桶）直接提交。
   - **候选采用物化**：`EconomyEntrySettlement.execute` 在可行性通过后 `tables.industries.putIfAbsent(intent.industryId(), templateFor(candidate, industryId))`（`EconomyEntrySettlement.java:868-869`），`templateFor` 由 `ProductionCandidate.requiredAssets/laborPerUnit/outputPerUnit/inputPerUnit/cycleDays/regime` 拼 `Industry`（`:1012-1047`），`capacityPerUnit` 取 requiredAssets 正数项（空则 `IllegalStateException`），slots 用固定四阶层模板。
   - **破坏性重建**：`economy.ClearRegion` 删该 region 的 industries/units/relations/assetShares/classes 等（`EconomyClearRegionHandler.java:300-420`；有意不清 modes/classStructures/positions/assetRules/governments/candidates），之后 `simos.region.seed`/`worldgen.initialize` 重新 Seed。`RegionClearDataTool`/`RegionSeedTool` 是 GM 组合工具。
3. **无 `upsertIndustry`**：`EconomyGmAdjustments` 当前 12 个 action：`forgiveDebt,setLiquidationPolicy,upsertProductionMode,deactivateProductionMode,upsertClassStructure,upsertClassPosition,upsertProductionRelation,upsertAssetRule,upsertProductionOrganization,upsertCandidate,setOutputQuantity,clearOutputQuantity`（`:92-128,182-194`）；`upsertIndustry/UPSERT_INDUSTRY` 全 main 0 命中（§7 自证 1）。`GmAdjust` 也明确拒直写 industries/assetShares/units（类注 `:75-81`）。
4. **候选采用不能建新产业（关键限制）**：
   - `assess` 要求 `householdEconomy.population()>0`（`:356-360`），GOV 本户 0 人口 ⇒ 不能采用；
   - `ownAssets`/`sourceAssets` 都要求 `share.industry().equals(industryId)`（`:723-787`），而 `industryId = IndustryHexKeys.id(candidate.id().value(), hex)`（`:385`）；`EconomyData` 的 assetShares 守卫又要求 industry 已存在（`EconomyData.java:902-912`）。⇒ **要采用候选，先得有该 (candidate kind, hex) 的 Industry 和挂在它名下的份额**；候选路径实际是“既有产业 + 新经营者/新单位”，不是“新产业模板”。
   - 候选还需要：主产出在该户所在格有有效需求（HOUSEHOLD/HEX 需求，窗口 ≥ buildDays+cycleDays，`:672-697`）；市场存在且主产出/正投入/给养粮有价（`:523-581`）；本户库存覆盖投入+口粮（`:476-521`）。空 output 候选无法通过主产出需求/价格 score。
5. **空 output 产业的实际行为**：
   - `EconomySettlement.harvest`：`gross/net` 从 `industry.recipe().outputPerUnit()` 遍历；若 `netByCommodity.isEmpty()` ⇒ **直接 return，relation 完全不结算**（`EconomySettlement.java:7260-7284`）。因此“劳动即服务、无商品产出”的 unit 不会产生任何商品、不会给任何受方转账（包括 `FIXED_MONEY_WAGE`）、不会写产出 ledger；只有 `unit.cycleLaborMilli` 每天累加。
   - 劳动队列：`LaborQueueBook.offer` 对 `laborPerUnit>0` 且 `outputPerUnit` 为空的活动给 `reason=NO_RECIPE_OUTPUT`、`maxAbsorbable=0`（`:214-239`）；`isPreservedByQueue` 对 `NO_RECIPE_OUTPUT` 返回 true（`:521-527`）⇒ **既有承诺原样保留、但不新分配**。
   - 规模：`LaborQueueBook.maxAbsorbableLaborMilli`/`ProductionProcessBook` 仍会算 `capacityScale`；若 `capacityPerUnit` 非空且份额存在，unit 的 `cycleLaborMilli` 可以累加，收获日的劳动瓶颈公式仍会走（但它不产任何商品）。
   ⇒ 空 output 是 R1“服务流量不进商品表/库存”的自然载体，但**必须另接**：① 承诺创建路径；② 服务流量计算/消费；③ 工资路径（不能靠 relation 货币规则，因为 harvest 早退）。

**缺口**

- 运行期（已播种格）没有建产业/unit/份额的命令；`economy.Seed` 只能用于未占用格/首次播种。
- `upsertIndustry` 缺失（R5 已纳入本批）。
- 候选路径不能建新产业，且 GOV 本户 0 人口不能走候选。
- 空 output 产业不与现有关系结算/工资/劳动新分配兼容，需要新桥。

**候选方案（含 R5 `upsertIndustry` 设计）**

| 方案 | 改动面 | 影响 | 铁律 2/3/4/5 | 风险/负向用例 |
|---|---|---|---|---|
| **Q2-A Seed-only（过渡）** | 零新代码；用 `economy.Seed`（首次或未占用格）或 `ClearRegion→Seed` | 可在新世界/清空区域搭出 office 产业/unit/份额/承诺 | 全走命令（2）；economy 自己写（3）；app 组合（4） | 已播种格不可用；`ClearRegion` 破坏性；不能作为常驻 GM 配置能力 |
| **Q2-B `upsertIndustry` 原地改模板** | 新增 `GmAdjust` action + `Industry` 原地 upsert | 改动最小，但**所有引用同一 `IndustryId` 的既有 unit 会静默改变产出/劳动/周期** | 形式合法 | **拒绝**：违反“既有 unit 行为不得静默变值”的纪律；负向用例必须包括“有 ACTIVE unit 引用时改模板 ⇒ 具名拒” |
| **Q2-C `upsertIndustry` 版本化（推荐，R5）** | 新 action + 版本策略；见下 | 老 unit/份额/进度保持原模板，新 unit 用新版本 | 全走命令（2）；economy 数据（3）；app 工具（4）；若给 `Industry` 加 `version` 字段则必须 ChangeSet/Codec 往返（5） | 需要明确版本键/迁移；负向用例覆盖版本倒退、空 capacity、坏引用 |
| **Q2-D 新 service recipe 类型** | `Industry`/`ProductionRecipe` 加服务产出语义 + settlement/queue/readout | 语义最干净，但跨 economy-api+economy+app，且与 R1 的“不落库存”要专门设计 | 类型改动可能触 5；模块边界仍 economy 拥有 | 最大改动面；建议在 A/C 后再评估 |

**Q2-C 版本化设计（R5，建议）**

- **版本载体两种选一**：
  1. **新 id 即新版本（推荐 MVP）**：`<kind>_v<N>@<q>_<r>`（如 `office_v2@0_0`）。`IndustryHexKeys` 用 `lastIndexOf('@')` 解析格键（`IndustryHexKeys.java:64-72`），kind 里的 `_v2` 不影响；老 unit 继续引用 `office@0_0`，新 unit 用 `office_v2@0_0`；`IndustryHexKeys.at` 仍按格归组。**无需给 `Industry` 加字段、无需 ChangeSet/Codec 形状变更**；审计靠 id+name+revision。
  2. **`Industry` 加 `version` 字段 + unit 引用版本**：更显式，但 unit 现在只存 `IndustryId`，要实现“同一 id 多版本”必须给 `ProductionProcess` 加 `industryVersion`/`recipeRef`，并把所有 `industries.get(unit.industry())` 的读点改成按 (id, version) 解析——跨模块大改，建议后置。
- **守卫**（无论选哪种）：
  - 新建：`capacityPerUnit` 非空且逐值 >0；`cycleDays ≥1`；`laborPerUnit ≥0`；`outputPerUnit` 可空（服务）；`slots` 非空；`allocation/regime` 非 null；新版本 id 不含 `.` 且能解出格键、kind/版本不撞既有；
  - 修订既有：若**任一 unit/assetShare 引用该 industry** 且请求企图原地改 `capacityPerUnit/laborPerUnit/outputPerUnit/cycleInputPerUnit/cycleDays` ⇒ **具名拒**，指路“新建版本 + 新 unit”；仅允许不动语义的 `name`/`allocation`（若用户裁定 allocation 可变）；
  - `regime`/`slots` 改动一律视为新版本；
  - 引用不存在/版本倒退/空 capacity/负值/带 `.`/解不出 hex ⇒ 具名拒；
  - 与 `ProductionCandidate`/`ProductionMode` 的引用关系：candidate 的 `id` 是 kind，materialize 时用 `<kind>@hex`；若同格有多个版本，需要 candidate 显式指定版本（建议在 candidate/mode payload 上加 `industryVersion` 或让 candidate id 带版本）。
- **既有 unit/assetShare/进度处置**：老 unit 不动、进度不重置；新 unit 从头 `progressDays=0`；老 assetShares 仍挂老 industry；要迁到新版本需要显式 `TransferAssetShare`/新组织流程（或用未来的 `migrateUnitIndustry`，本批不做）。
- **负向用例（测试代理输入）**：① 对 ACTIVE unit 引用的模板原地改 capacity/labor/output ⇒ 拒，且世界零变化；② 版本倒退 ⇒ 拒；③ `capacityPerUnit={}` ⇒ 拒；④ `laborPerUnit<0` ⇒ 拒；⑤ id 带 `.`/无 `@`/hex 不存在 ⇒ 拒；⑥ 同 id 同版本逐值重放 ⇒ 幂等 no-op（照 `upsertProductionMode` 的 `:304-366` 先例）；⑦ 新版本不影响老 unit 的 `ProductionProcess` 与 assetShare 归属；⑧ 老 unit 的收获仍逐值按老配方。

**分类**：Q2-A = 零代码（受限）；Q2-B = **拒绝**；Q2-C = **结构改动**（R5 本批；economy handler + app 工具 + 测试）；Q2-D = 结构改动（后置）。

---

### Q3 服务产出语义：三种表示 + R1 逐 tick 流量

**事实链（R1 的"运力"同形机制先钉死）**

1. **运力真正的承载**（三层，缺一不可）：
   - **持久容量**：`MerchantFirm.capacityPerRound`（>0 的持久字段，`MerchantFirm.java:55,80-82`）；
   - **本轮消费 + 轮初归零**：`capacityUsedThisRound`（`:56,84-86`），`withCapacityUsedThisRound`（`:245-268`），`MerchantSettlement` 每轮调用 `.withRoundReset()`（`MerchantSettlement.java:520`；`MerchantFirm.withRoundReset()` `:264-268`），`MerchantPolicy.withRoundCapacityReset()` 同款（`MerchantPolicy.java:272-284`）；
   - **未用/瓶颈读数**：`MarketReport.RouteUsage`（运力、用量、瓶颈）+ `MarketReadout.CommodityMatchReadout.unusedCapacityMilli`，`= Σ max(0, 每窗运力×最大轮数 − 已用)`（`MarketReadout.java:600-660`）；`MarketReport` 明确**不落盘**、`MarketReadout` 没有报告时进 `unavailable`、不填 0（`MarketReport.java:1-80`；`MarketReadout.java:30-90`）。
   ⇒ “流量”在代码里有两种等价形态：**(i) 纯进程内报告/读数**（MarketReport/MarketReadout；重启即失）；**(ii) 持久容量 + 每轮重置的已用量 + 读数**（MerchantFirm/round reset）。两者都满足“不能存到第二 tick、未用完即失效”；差别在是否有持久字段承载“本轮已用”。
2. **政府产出不是商品**：没有 `admin_service/security_service` 商品定义；`CommodityId` 只校验非空白，技术上可造任意 id（`CommodityId.java`）；但 `EconomyVocabulary` 是六个商品 id 的唯一拼写点（`EconomyVocabulary.java:77-93` + `EconomyVocabularyGuardTest` 源扫描），新增伪商品需要同步词表与护栏；且产出经 `harvest` 会进 operator 家户库存（`EconomySettlement.java:7258-7281`），违反 R1。
3. **效率消费点在 app/gov**：`PopulationEconomyTimeParticipant.efficiencyTable`（`:849-881`）逐 GOV 调 `GovEfficiency.of`；`GovDaily.settle` 也调（`:224-225`）；读数写 `GovOfficeState`（`tick+六表+四个‰`，`GovOfficeState.java:43-118`）；`GovernmentUpkeepOracle` 在同一个日循环花国库（`:529-602`）。

**三种表示对比**

| 方案 | 类型合法性 | 结算行为 | 市场/库存 | 效率消费 | R1 兼容 | 改动面/风险 |
|---|---|---|---|---|---|---|
| **(a) 空 output 产业**（`outputPerUnit={}`） | 合法（Q2 事实 1） | harvest 早退，relation 不结算；劳动承诺仍被占用/累加 | 完全无商品、无库存、无市场 | 由 app 另算服务流量（见下） | **兼容**（本就不产生存量） | 最小类型改动；但工资/关系/劳动新分配都断；需要新承诺+流量+工资三桥 |
| **(b) 伪商品**（`admin_service` 等） | 技术上合法（CommodityId 任意），但词表/护栏要改 | harvest 正常结算；`FIXED_MONEY_WAGE` 可从国库发工资；产出进 operator 国库库存，不进市场除非挂单 | 会进库存/可能进市场 ⇒ **违反 R1** | 效率可直接读产出量 | **不兼容** | 语义污染、库存/FlowRow/价格/守恒都要为伪商品让路；**不推荐**（只作对照） |
| **(c) 新 service recipe 类型** | 需要新类型/字段（如 `Industry.serviceOutput` 或 `ProductionRecipe.service`） | 结算专门分支：产出为“服务流量”而非商品；可定义消费/过期 | 可设计成不进库存/市场 | 效率直接消费流量 | **兼容**，最干净 | 跨 economy-api/economy/app，类型/Codec/ChangeSet/往返；改动最大，建议 MVP 后用 (a)+流量桥等价实现，稳定后再收紧为显式类型 |
| **(a+R1) 空 output + 进程内 `GovServiceFlow`（推荐 MVP）** | 复用空 output，零新商品/零库存 | 服务流量在 app 每 tick 现算、同 tick 消费；不写 `EconomyData`、不写家户账、不写市场 | 无 | app 把流量/承诺/需求传给 `GovEfficiency`；结果读数进 `GovOfficeState` | **兼容** | 新增进程内报告/桥 + gov 状态读数字段；无新 EconomyData 组件（不触铁律 5）；风险是“读数不可重启复现”需日志/审计补齐 |

**R1 推荐落点（与既有 MarketReport/MarketReadout 同形）**

- **数据形状**：新增 `GovServiceFlow`（进程内只读 record）：`(govUnitId, tick, securityCommittedMilli, paperworkCommittedMilli, securityDemandMilli, paperworkDemandMilli, securityFlowMilli, paperworkFlowMilli, securityUnusedMilli, paperworkUnusedMilli, staticModifierPerMille, dynamicModifierPerMille, provenance, unavailable)`。
- **产生**：app 推进参与者在日循环内（税/GovDaily 之前或同一阶段）从 economy session 读 GOV 承诺（按角色聚合），从 `GovDemand` 读人数、乘标准系数，按 P3 公式算两维有效流量；`Math.*Exact` 溢出 ⇒ 具名 ERROR 并 fail-closed（P3）。
- **消费**：同一 tick 的 `GovEfficiency.of`（新的输入 overload）消费；`GovDaily` 的俸禄/编制继续读同一份投影；**不写入 economy 库存/市场**；只把结果读数写 `GovOfficeState`（必要时加 `securityFlowMilli/paperworkFlowMilli/unused...` 读数字段，每 tick 覆盖）。
- **未用完即失效**：不落盘的流量天然失效；若为了审计在 `GovOfficeState` 存“本 tick 流量/未用量”，下一 tick 覆盖即失效（这正是 `MerchantFirm` 每轮 reset 的同形；不要做一个可累加的库存字段）。
- **与空 output 产业的关系**：空 output 提供“劳动被占用的制度载体”（`ProductionProcess`/commitment/岗位），服务流量提供“产物”。两者用一个 GOV 专属 mode/industry 绑定；若用户不想复用产业框架，也可只保留承诺表 + 服务流量桥（把“生产方式”降为角色目录），但那会失去 `ProductionMode`/`ClassPosition`/`LaborSource` 的现成结构。

**分类**：(a+R1) = **结构改动**（app 流量桥 + gov 公式/状态 + 承诺路径）；(b) = 不采纳（违 R1）；(c) = 结构改动（后置，等 (a+R1) 稳定后再类型化）。**待用户裁定 2：服务产出表示选 (a+R1) 还是 (c) 直接做新 recipe 类型**（建议 (a+R1) 起步）。

**R1 与改动面的诚实反证（第二轮 Q1~Q3 子代理回核）**：该轮子代理**在未纳入 R1 的假设下**给出“近期选 (b) 伪商品”的建议，因为它是**零 schema/ChangeSet/Codec 改动**的最小路径（`CommodityId` 无白名单；`SetMarketPrice`/`AddDemand`/候选/劳动队列全部按 `CommodityId` 打通）。**但父代理 2026-10-23 追加的 R1 明确“不进家户库存/商品市场存量、未用完即失效”** ⇒ (b) 的产出会经 `harvest` 进 operator 家户库存（`EconomySettlement.java:7258-7281`）并被市场/账本当作商品，**与 R1 直接冲突**。因此：**若 R1 可被用户放宽/限定（例如伪商品只作内部计价、立即清零），(b) 是改动面最小的 MVP；若 R1 是硬约束，必须走 (a+R1)**。这一分歧已作为“待用户裁定 2”的选项 A/B 列出，不要在实现时静默选 (b)。

**Q1~Q3 第二轮补充事实（静态回核，均已回代码验）**：
- **用户口中的 `ProductionUnit`/`ProductionOrganization` 并不是值类型名**：实际是 `ProductionUnitId` + `ProductionProcess`、`ProductionOrganizationId` + `ProductionEnterprise`（`ProductionUnitId.java:16`；`ProductionProcess.java:45-52`；`ProductionOrganizationId.java:23`；`ProductionEnterprise.java:73-85`）。`ProductionOrganization` record 不存在；按旧名搜代码会 0 命中，不要误判为“功能不存在”。
- **operator/organizer 的 kind 都不拦**：`ProductionProcess` 只判 `operator != null`（`:54-57`）；`ProductionEnterprise` 只判 `organizer != null`（`:99-105`）；`EconomyData` 的 units 守卫只判键/id/industry/progress（`:1017-1042`）。真正的限制是**账户解析**（`AccountSnapshot.actorKeyOrNull` 对非 HOUSEHOLD 返回 null，`:152-169`；`HouseholdRouting` 的解析顺序，`:105-135`）——这支持 Q1-A（hh-gov 当 operator）。
- **`RegimeOperators` 默认**：feudal/handicraft → `ORGANIZATION`，household/tenant → `HOUSEHOLD`（`RegimeOperators.java:66-75,89-102`）；它只是“载荷缺 operator 时的默认”，不是 kind 守卫。
- **`OperatorCondition` 的写者**：候选进入写 `TRIALING`（`EconomyEntrySettlement.java:889-915`）；`OperatorSettlement` 在有市场卖方证据时补中性并关账写回（`:168-181,201-241,396-422`）；`economy.Seed`/`EconomyEnterpriseSettlement` 都不写 condition（空表合法，结算按 ACTIVE）。
- **`AssetKind` 没有 OFFICE**：只有 `LAND/CATTLE/TOOL/WORKSHOP/MACHINE/SHIP`（`simos-actor-api/.../asset/AssetKind.java:8-15`）。由于 `capacityPerUnit` 不得为空且逐值 >0，office 服务产业必须借一个现有资产作“规模锚”（如 `TOOL`/`WORKSHOP`），或新增 `AssetKind`（enum 追加通常低风险，但会影响线格式/读口，需核）。
- **候选的硬约束**：`ProductionCandidate.outputPerUnit` **必须非空且包含 `output`**（`ProductionCandidate.java:100-120`）；`RegisterCandidate` 同款（`EconomyRegisterCandidateHandler.java:124-127`）；`EconomyEntrySettlement` 还要求主产出有价、需求窗口足够、requiredAssets 至少一个正数项（`:389-402,523-581,672-697`）。⇒ 候选路径**不可能**产生空 output 服务；空 output 只能走 Seed（或新命令）。
- **市场对无价/零供需商品**：无价商品不进撮合（`MarketSettlement.java:714-716,4978-4985,1208-1212`）；有价但零供需只是静态价格行、不自动生成供需（`:1114-1152`）；市场不会替伪商品自动挂价，必须显式 `economy.SetMarketPrice`。
- **16,000 的两处默认**：除 C8 所述两处，当前 `GovRules` 没有任何“人数 × 系数”的行政劳动标准（`GovRules.java:48-75` 只有 500/1000/40/60 需求常量）。
- **“无 upsertIndustry”在 `docs/**` 里有提议文本**：`2026-10-23-editability-and-efficiency-investigation.md`、`2026-10-23-production-efficiency-framework.md` 都提过，但都是设计/报告文本；可执行 action 里 0 命中（§7 自证 1 已限定在 `*.java`）。

---

### Q4 其他家户"加入"：劳动队列的 offer/承诺/资格；"挂到市场"到底走哪条路

**事实链**（本报告采用只读子代理对 Q4 的全量代码核对，关键点已回核）

1. **offer 的来源与算式**：`EconomySettlement` 在 modes 非空时调 `LaborQueueSettlement.apply`（`:1265-1275,1301-1309`）；`LaborQueueSettlement` 逐候选 unit 调 `LaborQueueBook.offer`（`:181-199`）。`LaborQueueBook.offer`：`capacityScale = plannedCapacityScaleOf`、`inputScale = min(⌊cycleInputUsedMilli/inputPerUnit⌋)`，`maxAbsorbableLaborMilli = min(capacityScale,inputScale) × laborPerUnit`（`LaborQueueBook.java:185-239,387-408`）。offer/Decision/Plan 是**进程内纯函数**，`LaborQueueReport` 明说“不进 EconomyData、不落盘”（`LaborQueueReport.java:10-22`；`HouseholdLaborCommitmentFeed.java:9-21`）。
2. **候选户集合**（谁有资格被队列考虑）：`candidateHouseholdsOf` 只取四类：unit.operator 若是 HOUSEHOLD ⇒ 该户；`ProductionEnterprise.organizer` 解析到的户；`enterprise.laborSources()` 的户；已有 commitment 的 household（`LaborQueueSettlement.java:406-438`）。**不读** `classPositions`/`AllocationRule`/`ProductionRules`/`LaborSourcePolicy`/`participationPerMille`（子代理自证 G：这些 token 在该文件 0 命中）。
3. **分配与写回**：逐户预算 = `householdEconomy.laborMilli()`（`:201`）；非排队 unit 的既有承诺作为 preserved 先占预算（`:202-209`）；`LaborQueueBook.plan` 按 unit 每劳动预期净收益降序、给 `min(remaining, maxAbsorbable)`（`:332-370`）；逐 unit 全局封顶按比例缩（`:228-284`）；`applyPlan` 写回：有旧行复用 id/group/actor/period 只改 `laborMilli`，无旧行用 `HouseholdLaborCommitment.idOf(unitId, lot, household)` 新建，`actor=unit.operator()`、`activity=unitId.value()`、`period=1`（`:503-552`）。
4. **跨家户加入是合法的**：`EconomyData` 守卫只要求“activity 若命中现存 unit，则 commitment.actor == unit.operator”（`EconomyData.java:847-866`）；`household` 是出劳动的家户，二者可以不同。`hh-gov-*` 作为 actor/operator，其他家户作为 household，数据形状成立。
5. **无劳动力市场**：`Market` = `numeraire + Map<CommodityId,Long> prices`（`Market.java:46-70`）；`MarketDemandBook` 只按商品；`economy-api/market` 包内 `labor` 0 命中；订单只有 `BuyOrder/SellOrder`；`MarketSettlement` 对 `laborCommitments` 只当构造参数传递、从不业务读取（`MarketSettlement.java:283,464,510,2545,2780`）。⇒ **“挂到市场”不能按字面读成“挂到商品市场”**。
6. **候选采用不是“多户加入同一 unit”**：`EconomyEntrySettlement` 逐户评估、每户各建自己的 unit（operator=该户），只给该户发一条 commitment（`:232-260,385,839,952-963`）；assetSource 只借资产，不带来劳动。真正“多户给同一 unit 出劳动”的路径只有**劳动队列 + enterprise.laborSources + 既有 commitments**。

**缺口**

- 让其他家户“加入”的**唯一多户写口**是 `economy.GmAdjust/upsertProductionOrganization` 的 `laborSources`（`EconomyGmAdjustments.java:1062-1074,1134-1147`），即 GM-only；决策人/其他模块没有写口（R2 要补）。
- 即使加入 `laborSources`，若 unit 不“可排队”（空 output/无产能锚/无正投入/产出无价），队列只把它当 preserved，不会给新劳动分配；纯行政服务正是这一类。
- 队列排序用 unit 的预期产出净收益，**不含工资分配**（`LaborQueueBook.java` 类注明确近似：租金/工资等 relation 分账不在本类重算），所以“工人按工资高低选雇主”的市场语义不存在。
- 参与率不参与资格（预算用原始 `laborMilli`），`participationPerMille` 只在旧 `laborOfCohort` 与读口用。
- 资格规则（`classPositions`/`AllocationRule`）不在队列路径上；若要“只有某阶层/某家户能应募某岗位”，需要新原语或在候选集合上新增筛选来源。

**候选方案**

| 方案 | 语义 | 改动面 | 铁律 2/3/4 | 风险 |
|---|---|---|---|---|
| **Q4-A 复用劳动队列 + `laborSources`（行政分配队列）** | “挂到市场”= 政府把岗位作为 unit 的 laborSources 公开，家户被队列按预期收益/预算分配；雇主=`hh-gov-*` | ① 让 GOV unit 成为可排队 offer（空 output 也要能被 GOV 专属规则处理）；② 决策人/GM 工具写 `laborSources`；③ 队列把 GOV 承诺列为不可缩优先级（C7） | 写口走命令/revision（2）；economy 不依赖 gov（3）；app 组合（4） | 这不是“家户自愿应募/按工资选雇主”，而是“行政分配”；若用户要市场语义，会失望 |
| **Q4-B 新“岗位需求”原语（推荐长期）** | 新增 `GovJobPosting`/`GovLaborDemand`（雇主、role、每 tick 劳动量、工资、资格、有效期），队列消费它；家户按工资/资格应募 | 新持久状态 ⇒ `EconomyData`/`EconomyChangeSet`/Codec/往返（铁律 5）；`LaborQueueBook/Settlement` 支持无 unit/无产业的需求；app 桥把 GovDemand/工资/预算接进来 | 新状态仍属 economy（3）；写口走命令（2）；app 组合（4）；往返必须补（5） | 语义最干净但最大；与现有 queue 的关系/优先级要设计 |
| **Q4-C 只走生产 relation 的货币工资** | `FIXED_MONEY_WAGE` 给劳动者付工资 | 无劳动分配；工资仍需产出非空 | 形式合法 | **不能单独用**（不产生劳动）；只能作为 A/B 的工资侧 |
| **Q4-D 商品市场 + 伪商品** | 伪商品+需求+价格 | 违 R1 | —— | 不采纳 |

**分类**：Q4-A = **结构改动**（队列 + 工具，仍保留行政分配语义）；Q4-B = **结构改动**（新原语 + 队列改造，长期推荐）；Q4-C = 辅助；Q4-D = 拒绝。**待用户裁定 3：“挂到市场”的语义 = 行政劳动队列（A）还是新岗位需求/应募原语（B）**（建议 MVP 用 A + 明确“这不是市场”；目标态 B）。

---

### Q5 工资与国库：关系/周期转移怎么结；国库付款可行性；P4c 与 F2

**事实链**

1. **生产关系的货币工资**：`RuleType.FIXED_MONEY_WAGE`（`RuleType.java:40-50`）走 `ProductionSettlement.settleMoneyRule`，`due=fixedAmount`、`avail=facts.availableMoney[currency]−已付`、`paid=min`（`ProductionSettlement.java:368-411`）；付款方恒为 `relation.operator()`（`:80,271,281-287,404-410`）；受方 `Payee.ToHousehold` 可指向官吏家户；欠额进 `RuleSettlement` 读数、**不落债权**。实物工资/租等 `Pool/Weight` 见 `CompensationRule.java`。
2. **但 `harvest` 在无正产出时整段早退**：`netByCommodity.isEmpty()` ⇒ `return`（`EconomySettlement.java:7282-7284`），**任何 relation（含货币工资）都不结算**。所以空 output 服务单位不能靠 relation 发工资；伪商品单位可以，但违 R1。
3. **军俸链是政府工资的最佳模板**：`MilitaryPayRuleBridge` 每天从 unit 政策现算瞬态 `HouseholdPeriodicAdjustment`（payer=`hh-gov-<masterGov>`、payee=政策家户；`:42-57,106-248`）；`PeriodicHouseholdAdjustmentExecutor` 到期执行、逐腿 `min(可用,请求)`、全 0 ⇒ `skip no-payable-leg`、有实付 ⇒ `transfer`（`:212-325`）；不依赖产出/地点。日循环顺序：税 → GovDaily → 军俸/周期规则（`PopulationEconomyTimeParticipant.java:528-602`），共用同一 `AccountSession`/`TAX_AND_UPKEEP` 阶段。
4. **`DeductionReason` 没有 `ADMIN_SALARY`**：只有 `MILITARY_SALARY/JURISDICTION_TAX/ADMIN_UPKEEP/CORVEE`（`DeductionReason.java:23-35`；§7 自证 6 正对照）。`ADMIN_UPKEEP` 注释明文“无收款方 ⇒ 明确 sink”；`GovernmentUpkeepOracle` 当前正是 `HouseholdStockDeduction.sink(ADMIN_UPKEEP)`（`:128-148`），即**行政俸禄是焚毁而非支付**。
5. **从 `hh-gov-*` 付款可行**：账户键就是 `HouseholdId`；`StockDeductionService.deduct` 原子、缺账/不足/侵占冻结都具名拒（`:253-278,317-360`）；`HouseholdStockDeduction.transfer` 已有；`AvailableStock` 缺账=0；执行器缺账 `skip` 有具名 gap。问题不是“能不能付”，而是**账户里有没有钱 + 用什么 reason/规则 + 与行政 upkeep/军俸的优先级**。
6. **注资口可达性**（既有报告 §4.2 + 本次核）：`actor.AdjustAccounts`（GM-only 裸原语，仅纯正增量可新建）；`actor.RemitGovTreasury`（household 级，通用 `simos.command.submit` 只在 GM 桶可达）；`simos.gm.periodicAdjustment`（GM 桶，可周期转 world-silver 的银，但没有粮/布）；`simos.gov.remit`（两端必须是带 `GovernmentFormation` 的 Unit，够不着 world-silver）；`simos.gov.pay`（决策人，payer 由 Affiliation.Gov 解析，payee 也必须是 GOV Unit ⇒ 付不了官吏家户）；`simos.unit.levyRegion`（受 cap，run5 为 0）。
7. **F2/P4c**：19 hex run5 中两个 GOV 国库零库存、税因 efficiency=0 实收 0、军俸 3 个周期全部 `no-payable-leg`；P4c（军俸与行政俸禄共享预算/优先级）未做，现状为固定顺序先到先得（`docs/.../2026-10-23-planned-not-implemented-inventory.md:42-44`；AGENTS §〇 P4a/P4b/P4c）。

**缺口**

- 无 `ADMIN_SALARY`（或等效民用俸禄 reason）；`ADMIN_UPKEEP` 是 sink，直接复用会语义矛盾。
- 无“承诺小时 × 工资率”的 bridge；periodic adjustment 只有固定额。
- 国库零注资（F2）⇒ 任何工资链都 `no-payable-leg`；且没有 GOV 语义的注资工具（world-silver 够不着运行期 GOV）。
- 无共享预算/优先级（P4c）：税/行政/军俸/工资顺序固定，先到先得；`GovDaily` 的 sink 还会先把国库“烧掉”。
- 决策人配置工资/预算的窄工具+审批链不存在（R2/R4）。

**候选方案**

| 方案 | 改动面 | 影响 | 铁律 2/3/4 | 风险 |
|---|---|---|---|---|
| **Q5-A（推荐）新 `ADMIN_SALARY` + salary bridge + 国库注资** | `DeductionReason` 新档；`simos-app/time` 新 bridge（读承诺→每天派生 period=1 的 `HouseholdPeriodicAdjustment`）；注资走现有 `actor.AdjustAccounts`/`actor.RemitGovTreasury` 或新 GOV 窄工具；先做 G1 注资工具 | 官吏家户真实收到工资；国库支出可审计；与军俸同一条执行器/部分支付/skip 语义 | 账户变化在 advance revision（2）；桥在 app（4）；承诺数据归 economy（3）；periodicAdjustment 是 economy 状态，已有 Codec/ChangeSet | 每天派生规则的性能/日志噪声；无预算优先级时先到先得；F2 未注资则仍 no-payable-leg |
| **Q5-B 改 `GovernmentUpkeepOracle` sink→transfer** | `GovernmentUpkeepOracle.java:128-148` + 收款户/分账政策 | 现有 upkeep 直接支付给官吏户；语义从“焚毁”变“支付” | 同上 | 现口径是人数×`OfficePolicy`，不是承诺小时；需要把 staff 换投影或改公式；reason 语义要同步 |
| **Q5-C 生产 relation `FIXED_MONEY_WAGE`** | 需要非空产出的 GOV unit | 复用生产结算 | 形式合法 | 空 output 早退 ⇒ 必须伪商品（违 R1）；不推荐 |
| **Q5-D 仅手动 GM 转账** | 现有 `actor.AdjustAccounts`/`periodicAdjustment` | 能演示“国库→官吏户” | 合规 | 不是持续工资/按承诺；审计弱 |

**R4 预算优先级设计（建议）**

- **状态形状**：在 `EconomyData` 新增组件 `govBudgetPolicies: Map<GovernmentId, GovBudgetPolicy>`（或扩展 `Government`）：
  `GovBudgetPolicy(governmentId, List<BudgetCategory> orderedCategories)`，`BudgetCategory(category: ADMIN_UPKEEP|ADMIN_SALARY|MILITARY_SALARY|DEBT_SERVICE|OTHER, minSharePerMille?/capPerCycle?, note?)`；顺序即优先级；同一 GOV 一条。
- **执行**：app 日循环在税之后、各支出之前读该 GOV 的 policy，按 orderedCategories 在**同一国库可支配余额**上依次分配（把现在固定顺序变成数据）；每个类别仍走各自执行器（GovDaily/军俸 bridge/salary bridge），但先算预算帽/保留，再付款；余额不足时按类别顺序耗尽并记具名 shortfall。
- **命令**：非 `GmOnly` 的 `economy.UpsertGovBudgetPolicy`（新 handler）或作为 `GmAdjust` 的新 action（但 GmAdjust 是 GmOnly，不满足 R2）；决策人窄工具（payer GOV 由调用者身份派生）在 `addDecisionAgentWrites` + `DecisionCallerFactory.WHITELIST`，走 `AutoApproveGate→ConfirmGate→PendingApprovals`；GM 侧并列一条同命令的无脑过工具。
- **铁律**：新状态必须 ChangeSet/Codec/往返（2/5）；数据归 economy（3）；工具/审批链在 app（4）。
- **风险**：与 `GovDaily` 的 sink 语义、军俸执行器的 id 顺序、税收入不足时的欠额资本化（P4c 另议）交叉；需要用户裁定 4 类别的顺序与是否允许比例分配。

**分类**：Q5-A = **结构改动**（app bridge + economy-api reason + 工具）；Q5-B = 小改但语义需重裁；Q5-C = 不采纳；Q5-D = 零代码过渡。R4 = **结构改动**（新 EconomyData 组件 + 执行顺序改造 + 审批链工具）。**待用户裁定 4：工资规则/工资率/币种、`ADMIN_UPKEEP` 是否拆分、预算类别顺序与是否允许比例分配、国库注资选哪条口**。

### Q6 劳动需求怎么表达 + 角色映射（含 R3 三档岗位）

**事实链**

1. **单元侧劳动需求 = 配方 × 可吸收规模**：`LaborQueueBook.offer` 的 `maxAbsorbableLaborMilli = min(plannedCapacityScale, inputScale) × industry.recipe().laborPerUnit`（`LaborQueueBook.java:185-239,387-408`）；`Industry.laborPerUnit` 是“每 1 单位规模需要多少劳动”（`Industry.java:121-138`）；`Industry.slots/ClassSlot` 在 settlement/queue **不读**（`EconomyData.java:704-707` 明说旧参与率守卫已删）；`ProductionProcess.cycleLaborMilli` 是历史累计实际投入，不是需求（`ProductionProcess.java:45-52`）。键 = `ProductionUnitId`，不是 `(hex,industry)`。
2. **GOV 按 GovDemand 折算劳动需求今天不能**：`GovDemand` 输出治安/文书**人数**（`GovDemand.java:62-96,132-152`）；`GovEfficiency` 供给读 `staff[YAMEN]`/`staff[SCRIBE]+staff[POST]`（`GovEfficiency.java:95-106`）；`simos-gov/src/main` 对 `labor|commitment|16000` 0 命中（§7 自证 3）。若要把“人数 × 系数”挂进现有结构，最低条件：为 GOV 家户建 `ProductionProcess` + 一个 `laborPerUnit=系数` 的 `Industry`，并让 `LaborQueueBook` 对“纯劳动、无资产/无商品产出”的 unit 生成可排队 offer（当前会因 `NO_RECIPE_OUTPUT`/`NO_CAPACITY` 置 0）。
3. **角色映射今天不存在**：`StaffRole{SCRIBE,YAMEN,POST}`（`simos-unit/.../StaffRole.java:12-21`）与 `ProductionRole`（`simos-economy/.../ProductionRole.java:30-37`）之间无 mapping；`StaffRole` 在 economy/economy-api main 0 命中；`ProductionRole` 在 unit main 0 命中（§7 自证 2）。唯一相关映射是 `EconomyEnterpriseSettlement.laborSourceOf(ProductionRole)` 输出 `LaborSource{SELF,FAMILY,TENANT,SERF,WAGE}`（`:1252-1262`），不含 StaffRole。
4. **16,000 只是 ADULT/MALE 默认（且有两处同名默认）**：`SocialProvisioning.java:163-171`（ADULT/MALE=16,000、ADULT/FEMALE=8,000、CHILD=4,000、ELDER=0；当前 `householdLaborMilli` 的实际权威链）与 `HouseholdLaborTimeTable.DEFAULT`（`simos-economy-api/.../labor/HouseholdLaborTimeTable.java:47-48`，4,000/16,000/8,000/0 的旧表默认）；`LaborCoefficient` 是 (年龄档,性别) 键 + 家户覆盖（`LaborCoefficient.java:29-42`）。冻结 P3 的“标准劳动系数=ADULT/MALE=16,000”是一个**选定档**，不是“每人 16,000”，且两处默认需收口。
5. **`GovernmentFormation.staff` vs posts**（见 Q7）：staff 是人数、posts 是家户+角色；两者没有换算/同步。`GovApplyStaffing` 按 `GovDemand` 精确配 `staff{YAMEN=securityDemand, SCRIBE=paperworkDemand}`、POST 不设（`GovApplyStaffingTool.java:47-58,244-278`），只改编制、不建经济需求。

**R3 三档岗位的候选映射**

| 映射 | 三档语义 | 治安维权重 | 公文维权重 | 兼容 `StaffRole` | 评价 |
|---|---|---|---|---|---|
| **M1 一档一维 + 第三档同维** | 档 1 治安、档 2 文书、档 3 文书（更高阶/驿传） | 档1=1000‰，档2/3=0 | 档1=0，档2/3=1000‰ | YAMEN/SCRIBE/POST | 最小改动；但公文维供给=SCRIBE+POST 两条都加，三档在效率公式里“2 档=3 档”，除非再给权重 |
| **M2 三档带二维权重（推荐）** | 三档都是“基层岗位”，每档可配置对两维的贡献权重（Σ=1000‰）；默认 M1 的取值，档 3 可设 500/500 或 1000/0 | 逐档独立 | 逐档独立 | 每档仍可指定一个 `StaffRole` 兼容键 | 一套状态同时表达“三档”和“跨维”；效率供给 = Σ (该档承诺小时 × 该维权‰/1000)；档 3 若 500/500 则一半治安一半公文 |
| **M3 三维（治安/公文/驿传）** | 档 3 成为第三维 | —— | —— | POST 独立维 | 需要新增需求公式与效率维度，**超出冻结 P3 的两维口径**；不建议本批 |

**建议状态/命令形状（M2）**

- **角色目录（慢变、按 GOV 配置）**：新 gov 源状态（与 P3 的静态修正同一处最自然）
  `GovPostRolePolicy(govUnitId, List<GovPostTier>)`；
  `GovPostTier(tierId: string, name: string, staffRole: StaffRole 兼容键, securityWeightPerMille: long, paperworkWeightPerMille: long, standardLaborMilli: long=16000, headOfGovernment: bool)`；
  三档默认 `TIER_1=YAMEN(1000,0)`、`TIER_2=SCRIBE(0,1000)`、`TIER_3=POST(0,1000)`（或用户裁定的跨维权重）。
- **岗位指派（谁在哪档）**：继续用 `GovernmentFormation.governmentPostsOfHousehold`（household→role/level/head），或扩展为 `household→tierId`；键必须 ⊆ `Unit.households`（`UnitState.java:254-291`）。
- **工时承诺（多少小时）**：归 economy 的 `HouseholdLaborCommitment`（或 Q8 的新 kind），`activity` 指向 GOV 服务/office unit；role/tier 可从指派/服务 id 推导。
- **配置命令**：新增非 `GmOnly` 的 `gov.SetPostRolePolicy`（或 `unit.SetGovPostRoles`，取决于状态归属）；决策人窄工具 `simos.gov.postRoles`（身份派生自己的 GOV、`addDecisionAgentWrites` + `DecisionCallerFactory.WHITELIST`、走审批链）；GM 侧并列 `simos.gm.gov.postRoles`（无脑过）。审批链现状：`Shell.java:848-872`（决策链 `AutoApproveGate→ConfirmGate→PendingApprovals`；GM 链 `GmAutoApproveGate`），`AskGate` 在 AgentLib jar（`io/mosire/agentlib/approval/ToolGate$Ask.class`）。
- **铁律**：角色目录是新 gov 状态 ⇒ gov 的 Snapshot/Codec/ChangeSet 往返（2/5）；岗位指派归 unit（3）；工时承诺归 economy（3）；app 组合装配与审批链（4）。
- **风险**：状态放 gov 还是 unit 需要裁（gov 模块目前 0 handler，R2 要求决策人可达；unit 的 `SetGovFormation` 已是现成写口但决策人不可达）；三档的权重/系数口径需与 P3 公式一起冻结；`staff` 兼容投影的派生规则要一次定死。

**分类**：Q6 的“人数→劳动”接线 + M2 角色目录 = **结构改动**（gov 状态/handler + app 桥 + 工具/审批链）；若只做 M1 + 不改公式，可降为**小改**但达不到 P3。

**待用户裁定 5：三档映射选 M1（一档一维+第三档同维）还是 M2（二维权重）；第三档是否要独立第三维（M3）**（建议 M2，避免新增第三维）。

---

### Q7 与 `GovernmentFormation` / `governmentPostsOfHousehold` / `staff` 的关系

**事实链**（要点，详见本会话只读子代理对 Q7 的全量核对）

1. `GovernmentFormation` 五字段：`staff(Map<StaffRole,Long>)`、`governmentPostsOfHousehold(Map<HouseholdId,GovernmentPostOfHousehold>)`（JSON 键 `householdPosts`）、`policy(OfficePolicy)`、`superiorGov(Optional<UnitId>)`、`level`（`GovernmentFormation.java:48-56`）。构造期守卫见 `:58-104`。
2. `governmentPostsOfHousehold` 不是 lookup 方法，是 record 组件；值 `GovernmentPostOfHousehold(householdId,role,level,headOfGovernment)`（`GovernmentPostOfHousehold.java:28-41`）；**不存人数/小时**。来源 = `unit.SetGovFormation.householdPosts` 载荷（`UnitPayloads.java:665-697`；handler 缺省保持既有 `SetGovernmentFormationHandler.java:88-94`）；不是从 staff/成员推导。
3. **消费者**：GUI `ApiViews.java:5071-5094`；`HouseholdUnitConsistency.staffHouseholdProjection` 只读聚合家户人口当告警（`:211-230`，调用点 `PopulationEconomyTimeParticipant.java:213-225`）；`UnitState.java:254-291` 守卫 posts 键 ⊆ `Unit.households`；`UnitOperations.recruitStaff/dismissStaff` 在 posts 非空时禁改 staff（`:1029,1095,1190-1201`）。`GovEfficiency/GovDaily` **不读 posts**（§7 自证 2）。
4. **拷贝纪律**：`withGovernmentPolicy/Superior/Staff` 都显式带过 posts（`UnitOperations.java:1205-1235`）；更底层的 canonical copy（`:1901-1928,1975-1995,2103-2122,2133-2153`）逐字段列全，**不会漏带**。真正风险在 `setGovernmentFormation` 是**同类型整体替换**（`:812-860`）和任何自行 `new GovernmentFormation(...)` 的调用点；`SetGovernmentFormationHandler` 用 `existingGovPosts` 兜“载荷没给”，但直接 Java 调用者没有这层兜底。
5. `HouseholdUnitConsistency` 只校/同步 `Unit.households ↔ Social Household.location(UNIT)`（五类 mismatch `:65-121`；单向同步 `:155-204`），**不校承诺**；posts 键⊆households 由 `UnitState` 另判。若官吏户挂到 GOV 单位，现有位置同步已覆盖；承诺是另一张表，不需要（也不应该）塞进它。
6. `staffIsHouseholdProjection()` 只有定义、全 main 无调用者（`GovernmentFormation.java:106-109`）；`GovApplyStaffing` 仍可直写 staff 且不受 `requireStaffNotProjected` 拦（`GovApplyStaffingTool.java:244-278`）⇒ **“staff=posts 投影”目前只是注释承诺，不是不变量**。

**一处真相方案**（同 Q8 的三种；推荐 C）

| 方案 | 权威 | 要动的读/写 | 评价 |
|---|---|---|---|
| A. staff 权威、posts 派生 | staff | 需 staff→家户身份映射，但 staff 只有人数 ⇒ **不可行** |
| B. posts 权威、staff 派生 | posts + 家户人口 | GovEfficiency/GovDaily 改读投影；堵 `GovApplyStaffing`/`SetGovFormation` 直写 staff；招募改走 Social | 可作过渡；只有人数、没有小时，达不到 P1/P3 |
| **C. 新 gov 源状态/承诺权威，staff+posts 都派生（推荐）** | 承诺小时（economy allocations 或新 gov 承诺表） | 供给=Σ承诺×权重；staff=派生投影；posts=承诺户/角色投影；承诺写口走命令；旧 staff 写口改拒/派生 | 与 P1/P3 同构；需新命令/状态/往返；跨模块契约要把 role 做成 opaque 词表（unit 的 StaffRole 不进 economy） |

**分类**：C = **结构改动**；B = 小改但只解决人数口径；A = 不可行。**待用户裁定 6：staff/posts/承诺三者的权威选 C 还是先 B 过渡**（建议 C，B 只作 GUI 兼容）。

---

### Q8 全职承诺的落点：复用还是新 kind；不变量在哪；两类家户是否同形

**事实链**（要点，详见本会话只读子代理对 Q8 的全量核对）

1. `HouseholdLaborCommitment` 全字段：`id(LaborAllocationId), group(PeopleLotId), household(HouseholdId), actor(ActorRef), activity(String), laborMilli(long), period(long)`（`:48-55`）；守卫 `:57-81`；`activity` 类型是 String，R3B.2 语义=unit id，但**非 unit 的 activity 合法**（`:42-44`）。`laborMilli` 单位=毫小时；家户每 tick 预算=`HouseholdEconomy.laborMilli`；不变量 `Σ allocations(household) ≤ HouseholdEconomy.laborMilli`（`:25-29`）。
2. 存在 `EconomyData.allocations`（record 字段 `:233`）；键/值一致性 `:803-818`；家户存在 `:819-830`；actor↔industry 撞名 `:831-846`；**activity 命中现存 unit ⇒ commitment.actor 必须等于 unit.operator**（`:847-866`）；Σ 预算不变量 `:867-890`。ChangeSet/Codec/StateBuilder 都有 allocations 透传（`EconomyChangeSet.java:115,271,309`；`EconomyCodec.java:1506-1510`；`EconomyStateBuilder.java:154,349`）。
3. **写者**：Seed 载荷（`EconomyPayloads.java:517,1140-1240`）；候选进入（`EconomyEntrySettlement.java:952-964`）；E2 自动组织（`EconomyEnterpriseSettlement.java:701-715`）；模式迁移（`ModeMigrationSettlement.java:430-457,1050-1075`）；**每 tick 劳动队列**（`LaborQueueSettlement.java:36,503-552`）；旧档周期首日修剪（`EconomySettlement.java:6623-6737`）。**无专用 allocation 命令 handler**（§7 自证 7）。
4. **生命周期**：allocations 是持久状态，随 revision/快照落盘；有 modes 的世界每天被劳动队列重写；旧档（modes 为空）只在周期第一天 `reallocateLabor` 修剪。生产结算的 `Facts.laborOfHousehold` **不直接读 commitment**，而是按本格 `HouseholdEconomy.participationAdjustedLaborMilli() × cycleDays` 现算（`EconomySettlement.java:7874-7897`）；commitments 用于配额/守恒/排队。
5. **同一家户可有多条 commitment**：按 household 分组（`LaborQueueSettlement.java:110-119`；旧路径 `EconomySettlement.java:6695-6736`）；承诺是“扣减可用预算”不是排他占用；preserved 占预算、生产候选分剩余（`LaborQueueSettlement.java:201-216`）；**preserved 超预算时会被 `preserveIntoBudget` 按比例缩**（`:210-215,554-586`），构造期 Σ 不变量是硬兜底（`:874-890`）。
6. **两类承诺数据同形**：政府自己的家户 vs 市场家户用同一个 record/表/不变量；区别只在 `activity`/`actor` 取值。`hh-gov` 0 人口、`laborMilli=0` ⇒ **不能承载承诺**；承诺必须落在官吏户（有劳动预算），activity/actor 指向 GOV 服务/政府主体。
7. **activity 类型能不能塞 GOV 岗位**：类型上任意非空 String 可以；`EconomyData` 只对 `"unit-"` 前缀做“unit 必须存在 + actor 一致”守卫（`:847-866`，`UNIT_ID_PREFIX` 在 `:3094`）；非 unit activity 被当“自由家户劳动”，只进守恒/读口，**不被生产结算认识，也不会自动获得 GOV 保护**。若改用 `ProductionUnitId/IndustryId/OrganizationId` 类型，各构造器只校验非空白/格式，但运行期路径会把每条 activity 当 `ProductionUnitId` 包一层；GOV 岗位仍只会落进“非 unit、保留”桶。

**候选方案**

| 方案 | 形状 | 优点 | 缺点/风险 | 推荐 |
|---|---|---|---|---|
| **Q8-A 复用同表 + 受控 activity 词** | `activity="gov-service:<govUnitId>:<tierId>"`（或 office unit id）；actor=hh-gov | 零新表/新组件；Σ 不变量自动覆盖；Seed 可写 | 无 kind，分类靠字符串；与“自由家户劳动”混淆；无 role/工资字段；需要新命令写；全职优先级仍缺 | 过渡可用 |
| **Q8-B 复用同表 + 加 `kind` 字段** | `kind ∈ {PRODUCTION, GOV_SERVICE}` + 可选 role | 类型级分类；仍共用 Σ 不变量 | record/Codec/ChangeSet/Seed/迁移全链路（铁律 5）；旧档缺省 | **推荐 MVP**（若改动可控） |
| **Q8-C 新增专用 `govServiceCommitments` 组件** | 键=(govUnitId,tier,household)，值=hours/terms | 语义最干净；gov 承诺独立读口；可为工资/效率直接消费 | 新 EconomyData 组件 + ChangeSet/Codec/往返；与 allocations 的 Σ 预算必须跨表合并判；gov/economy 边界要设计 | 长期候选（若 B 的 kind 不够） |

**全职承诺的关键语义（必须补）**

- 现在 preserved 是“普通保留”，超预算会按比例缩；P1 要“承诺后小时不再进生产队列” ⇒ 需要在 `LaborQueueSettlement` 把 GOV 承诺标为**不可缩优先级**（例如新 kind 进 `preserved` 且 `preserveIntoBudget` 不缩它；若 Σ > budget 则 fail-closed/具名 ERROR），或在状态构造期对 GOV 承诺单独 fail-closed。
- 若同一官吏户还有生产 commitment，GOV 承诺应优先占预算；生产部分只能用剩余（或全禁，取决于用户裁定）。
- 不变量要延伸为“production allocations + gov commitments ≤ laborMilli”；两类承诺都必须在 `EconomyData` 构造期算进 `allocatedPerHousehold`（`:867-890`），否则会出现“承诺 100% + 生产又分一份”的超发。

**分类**：Q8-B/C = **结构改动**（economy-api/economy + 新命令 + 往返）；Q8-A = 小改但语义弱。**待用户裁定 7：全职承诺用 B（同表+kind）还是 C（专用表）；承诺是否允许该户再接生产**（建议 B + 允许剩余预算接生产，gov 承诺不可缩）。

---

### Q9 零新增命令能走多远：手工最小演示逐步核对

**逐步表**（基于只读子代理 Q9 的全量核对；每一步都标明可用命令与缺口）

| # | 步骤 | 可用命令/工具 | 结论 | 缺什么 |
|---|---|---|---|---|
| 1 | 建 GOV 单位 + `GovernmentFormation` + `hh-gov` + 零余额国库 | `simos.gov.createOffice`（q/r/level/regions/superiorGov/staff/policy）；批序 `unit.CreateUnit → social.CreateHousehold(hh-gov) → actor.EnsureHouseholdAccount → unit.SetGovFormation → economy.RegisterGovernment → [unit.SetJurisdiction] → sd.CreateDecisionMaker → PutInfo`（`GovCreateOfficePlan.java:327-347,383-434`） | **能**（一条 revision） | 不注资；不建官吏户；不建 office 产业 |
| 2 | 建官吏家户 `hh-unit:<govUnitId>` 住到 GOV 所在格 | `simos.social.household.create`（location=UNIT(govUnitId)）+ `simos.social.household.members`（add/transfer）+ `simos.unit.assignHousehold`；GOV 单位允许多户但必须保留恰一个 hh-gov（`UnitState.java:269-291`） | **部分能**（手工建户/加人） | 没有自动官吏户生成；`simos.gov.recruit` 目标是 hh-gov（与 P2 冲突）；`GovApplyStaffing` 只改人数；无“岗位劳动承诺”直接写口 |
| 3 | 在已有 `farm@hex` 的格追加 `office@hex` 产业 | 追加 Seed：格已占用 ⇒ 整份拒（`EconomySeedHandler.java:117-134`）；raw `economy.RegisterCandidate` + `economy.SetMarketPrice` + `economy.AddDemand`；候选采用在日结算触发（`EconomySettlement.java:916-935`）；采用时 `putIfAbsent` 只在 **`office@hex` 产业与挂在该产业名下的份额已存在** 时才命中并建 unit（`EconomyEntrySettlement.java:405-430,723-787,868-888,1012-1046`） | **不能对已占用格追加**；候选路径**不能创建新产业本身**，只能为已存在的 office 产业建**私营** unit（operator=采用户），不能指定政府 | 缺 `upsertIndustry`（R5）；候选要求采用户人口>0、正资产（份额 industry 必须=office@hex）、同格需求/价、劳动余量；GOV 本户 0 人口进不去 |
| 4 | 让 GOV/政府家户成为该 unit 的 operator/organizer | `GmAdjust.upsertProductionOrganization`（organizer 必须 == unit.operator）；`upsertProductionRelation`（relation.operator 必须 == unit.operator）；unit 来源：候选采用=采用户、E2=家户、Seed 可显式 operator | **不能**（零新增、非破坏性路径）；Seed 可显式 operator 但只能未占用格/首次播种 | 缺“创建/更新 unit + 显式 operator”的命令；候选/E2 无政府例外 |
| 5 | 挂 wage relation | `upsertProductionRelation` + `CompensationRule(FIXED_MONEY_WAGE, Payee.ToHousehold)`；付款人恒为 relation.operator | **部分**（语法可写；若 4 不过就断） | 按劳动量付酬的货币规则（现有只有实物 `FIXED_IN_KIND_PER_LABOR`）；unit.operator 创建/改写 |
| 6 | 让其他家户加入 | `upsertProductionOrganization.laborSources` 写候选池；下一日 `advance` 由劳动队列分配并写 commitment（`LaborQueueSettlement.java:407-438,503-552`） | **部分**（laborSources 可加户；空产出 GOV unit 不会被新分配） | 无直接写 allocation/commitment 的命令；队列排序不含工资/关系；承诺可被重排 |
| 7 | 工资从 hh-gov 付给家户 | `economy.UpsertHouseholdPeriodicAdjustment` + `simos.gm.periodicAdjustment`（payer/payee/goods/money/reason/period） | **能付固定周期俸禄**（如 payer=hh-gov、payee=hh-unit、moneyPerCycle=silver、reason=admin_upkeep） | 与劳动/出勤无关、不按工时；无 `ADMIN_SALARY`；无预算优先级 |
| 8 | 国库注资 | `actor.AdjustAccounts`（GM 窄工具/裸原语）；raw `actor.TransferAccounts`；raw `actor.RemitGovTreasury`（household 级，GM 通用 submit）；`GovernmentSeigniorage`（周期铸币，可 re-register 打开） | **能（手工）** | world-silver 够不着 `simos.gov.remit`；无税收—预算共享；G1/G4 未落地 |

**零代码能搭到哪一步**

- **能完整搭出**：GOV 单位/编制/政府家户/国库账户 → 官吏家户（手工建）→ 国库注资 → 固定周期俸禄（periodicAdjustment）。这是一条可运行的“政府发俸禄”基线（但俸禄与岗位/工时/效率无关）。
- **不能零代码搭出**：已占用格上的 `office@hex` 政府服务产业、政府作为 operator 的生产 unit、按承诺小时的全职劳动、效率供给=承诺劳动、按工时的工资。最远只能：候选采用出一个私营 office unit（operator=某家户；**前提是 office@hex 产业与份额已由 Seed/其他路径存在**），或破坏性 `economy.ClearRegion` + 手写自定义 `economy.Seed`（未验证、不可持续）。

**最小代码集（按模块）**

| 模块 | 最小新增/改动 | 为什么 |
|---|---|---|
| `simos-economy-api` | ① `RuleType.FIXED_MONEY_PER_LABOR`（或 `CompensationRule` 加 `moneyPerLaborMilli`）；② 若走新原语：`GovJobPosting/LaborDemand` 契约；③ `Payee` 可暂不扩（`ToHousehold` 够官吏户）；④ 若 Q8-B：`HouseholdLaborCommitment.kind` | 现有货币工资是固定额，不能按承诺小时付酬 |
| `simos-economy` | ① `upsertIndustry`（R5，版本化）；② `UpsertProductionUnit`（显式 operator，可向既有格追加 industry/unit/assetShares/allocations，或拆分更细的命令）；③ 直接写/移除 `HouseholdLaborCommitment` 的命令（或新 gov 承诺组件）；④ `ProductionSettlement` 支持按劳动量货币工资；⑤ `LaborQueueBook/Settlement` 对 GOV 服务 unit 的承诺保留/不可缩/无产出处理；⑥ `EconomyData` 跨表守卫与 Σ 预算适配 | 缺的核心就是“政府可作为 operator 的生产 unit”+“可写劳动承诺”+“按工时付酬” |
| `simos-gov` | 新 gov 源状态（P3 静态修正 + R3 三档角色表 + Q8 读口所需的承诺投影）、`GovEfficiency.of` 新入参/公式（两维相乘÷1000、不封顶、`Math.*Exact`）、`GovOfficeState` 取消 1100/1000 上限并加流量读数、gov 首个 command handler（配置静态修正/角色表/预算；R2） | P3/R2/R3 的落点；gov 仍是纯函数 + 读数，不持第二本经济权威 |
| `simos-app` | ① `GovServiceFlow` 进程内桥（R1）+ 在日循环把承诺/需求传给 `GovEfficiency`；② `GovSalaryRuleBridge`（承诺→每日 periodicAdjustment，新 `ADMIN_SALARY`）；③ 预算优先级执行（R4）；④ 工具：`simos.economy.adjust` 扩 `upsertIndustry`/unit；决策人窄工具（身份派生 GOV）进 `addDecisionAgentWrites`+`WHITELIST`，GM 侧并列；⑤ gov 只读 MCP 工具（R6，复用 `ApiViews.economyGovernment`）；⑥ SmallWorld/EconomySeeder bootstrap 扩展（Q10） | app 是唯一同时认识 social/economy/unit/gov 的模块（铁律 4）；审批链/工具桶都在 app |
| `simos-unit` | `GovernmentFormation`/`GovernmentPostOfHousehold` 扩展（三档 role/tier、可选 office unit 引用），`UnitOperations` 写口与拷贝纪律，staff 直写收口 | 岗位指派与编制投影归 unit；避免三处真相 |
| `simos-social` | 最小不改：复用 create/members/transfer；若要求“官吏身份”显式化，加 PopulationAssignment/角色桥（当前 universal-population spec 未落地） | 人口真值在 Social；劳动承诺在 economy |
| 测试 | 按 AGENTS §三.0：生产代码全部落地后统一测试代理；R5/R2/P3 的负向用例见各节 | 本批不逐任务写测试 |

**分类**：骨架（建 GOV/官吏户/注资/固定俸禄）= **零代码**；R5 + 承诺命令 + 服务流量 + 效率 + 工资 = **结构改动**；R6 读工具 = 小改（app 单模块）。

---

### Q10 bootstrap / 小世界：19 hex 要跑通这条链需要什么

**现状**（Q9/Q10 子代理核对 + 本报告回核）

1. `SmallWorld` 创世命令序列硬编码在 `SmallWorld.state`：`social.SetPopulation → social.CreateCity ×2 → social.SeedGroups → economy.Seed → actor.Seed`（`SmallWorld.java:222-275`）；**不含 unit/army/gov/sd**（`:106-107`）。
2. `EconomySeeder.planProductionRuntime` 逐格建 `farm@hex`（农村还有 `weave@hex`，城镇 `craft@hex`+`trade@hex`），配 assetShares/allocations/classes/市场价表（`EconomySeeder.java:3059-3101,3188-3215`；市场 5 商品价 `:600-671`）。**`office` 在 `EconomySeeder` 0 命中**。
3. 政府：`PopulationSeeder` 只在给定 `governmentRef` 时建 `hh-gov-world-silver`（人口最多格、0 人口、official 槽位，`PopulationSeeder.java:165-186`）；`EconomySeeder.seedGovernmentHousehold` 空 stocks/money、行 population/labor/participation=0（`:3403-3461`），并写 `Government(world-silver)`（treasury=该家户、seignioragePerCycle=2000、debtIssuePerCycle=5000，`:3463-3472`）。**没有 GOV 单位**；run5 的两个 GOV 是 tick0 手工 `simos.gov.createOffice` 建的。
4. 启动链：`run-small-world.sh:101-106` → `ShellMain.seedGenesisIfEmpty:241-247` → `WorldRegistry.require:123-136`；空库首启才种富世界（AGENTS §8.3）。
5. 既有计划/规范：`docs/superpowers/plans/2026-10-08-small-world-gov-mint-plan.md`（铸币=工匠类生产方式、政府家户为组织者、劳动+工具投入；与当前“政府家户 0 人口、官吏住 hh-unit”口径冲突，且产出是货币不是服务）；`docs/superpowers/specs/2026-10-07-government-household-pilot.md`（政府家户不生产、0 人口、无劳动/unit/资产——与新需求冲突，按 AGENTS §四.1 以最新用户裁定为准，应视为被 P2/R1 覆盖）；`docs/superpowers/specs/2026-10-08-universal-population-architecture.md`（PopulationAssignment 方案稿，未落地）；`docs/superpowers/status/2026-10-23-planned-not-implemented-inventory.md`（M1 铸币未实现、P4c 未做）。全仓 `office@` 0 命中。

**缺口清单**

1. 每 GOV 座位格的 `office@hex` 产业：Seeder 没有；Seed 对已占用格整拒；需要 R5 的 `upsertIndustry` 或把 office 模板编进 `EconomySeeder`（创世即建）。
2. 政府家户的 office 资产份额：createOffice/Seeder 都给 0 资产；需要 Seeder 或新命令把 `OwnershipStake{industry=office@hex, owner/operator=hh-gov-<unitId>}` 落进状态（否则 `capacityScale=0`、unit 不生产）。
3. mode/classStructure/classPosition/relation 模板：`DefaultProductionModes` 只有 8 个传统 mode，无 GOV/office；运行期可由 `GmAdjust` upsert 自定义 mode/结构/位置/关系，但 candidate 的 regime 必须已登记、且想与“政府专属生产方式”绑定还需一层显式映射（R3）。
4. 国库注资：单位 GOV 创世零库存；world-silver 只有每 120 天 2000 毫银；需要 G1 的 GOV↔world-silver/任意家户转账工具，或 Seeder 给初始粮/布/银，或给单位 GOV 打开 seigniorage。
5. 初始官吏家户与人口：PopulationSeeder/EconomySeeder 不建 `hh-unit:<govUnitId>`；需要 bootstrap 建户、加成员、开账户、登记 posts、写承诺。
6. 劳动/工资桥：没有直接写 commitment 的命令；固定 periodicAdjustment 不按工时；需要 Q8/Q9 的最小代码集。
7. 读口/校核：`GovernmentHouseholdWiring`（GOV↔政府家户↔国库四边）已有；需扩展 `HouseholdUnitConsistency`/读口把官吏户、office unit、承诺、两维效率、预算纳入一致性与 R6 只读工具。

**最小 bootstrap 清单（建议）**

1. **Social**：为每个 GOV 建 `hh-unit:<govUnitId>`（location=UNIT(govUnitId)），加成人成员；gov 家户保持 0 人口。
2. **Economy seed**：为每个 GOV 座位格加 `office@<hex>`（空 `outputPerUnit` 或按 Q3 选型）+ unit（operator=`HOUSEHOLD:hh-gov-<unitId>`）+ assetShares（office 所需资产，owner/operator=政府家户）+ relation（工资规则）+ allocations（官吏户承诺，或由新命令在 bootstrap 后写）。
3. **GOV 单位**：`SmallWorld.state` 在 Seed 后追加真命令批：`unit.CreateUnit → unit.SetGovFormation → [unit.SetJurisdiction] → social.CreateHousehold(hh-gov, UNIT) → actor.EnsureHouseholdAccount → economy.RegisterGovernment`（顺序与 `GovCreateOfficePlan` 同源）；或用 `simos.gov.createOffice` 组合工具在 bootstrap 脚本里跑。
4. **国库/账户**：为 hh-gov/hh-unit 开账户；`actor.AdjustAccounts` 或 Seeder 注入初始粮/布/银；可选打开 seigniorage。
5. **承诺**：用新 economy 命令或 seed allocations 写“官吏户→GOV office unit”的全职承诺；确保 `Σ ≤ laborMilli` 且不可被日队列缩。
6. **校核/读口**：扩展一致性 + 新增 gov 只读 MCP 工具。

**与既有 G1/G2/G3 的关系**

- **G1（财政工具可达面，report1 §10:686-697）**：**前置**；它解决“把任意政府家户（含 world-silver）的 grain/cloth/money 转给任意 GOV 家户”；本批 bootstrap 的注资/工资应接 G1 的具名窄工具，而不是长期依赖裸 `actor.AdjustAccounts`。
- **G2（小世界 GOV bootstrap 与配员，report1 §10:699-707）**：**最自然的承载批次**；本报告建议把“官吏户 + office 产业 + assetShares + 承诺 + 注资”追加进 G2 的范围，而不是另开一个孤立批次；可复用 `simos.gov.createOffice`/`GovApplyStaffing`/`actor.EnsureHouseholdAccount`/`economy.RegisterGovernment`。
- **G3（税收效率/税基，report1 §10:708-716）**：不是最小演示前置；决定政府财政可持续性。若 bootstrap 用 GM 注资，可后置；若要做“自筹”，G3 必须先修 efficiency/税。
- **G4（国库预算与俸禄/军俸优先级，report1 §10:717-725）**：当同时有行政俸禄 + 军俸时需要（R4）；最小演示单俸禄可暂缓。
- **G5（M1 铸币生产方式，report1 §10:726-735）**：与 Q1/Q2/Q9 共享同一个缺失核心——“政府作为 operator 的 production unit + 劳动/资产投入”；建议 office 与 mint 复用同一套“政府生产 unit”基础设施，避免两套实现。
- **依赖顺序建议**：G1 → G2 扩展（GOV/官吏户/office/资产/承诺 bootstrap）→（本批）R5 upsertIndustry + 政府生产 unit + 服务流量/效率 + 工资/审批链工具 → G3 → G4 → G5。若必须先做 office 服务，则把“政府生产 unit”作为 **G2.5** 插在 G2 与 G3 之间。

---

## 4. 分类：零代码 / 小改 / 结构改动 / 待用户裁定

| # | 能力/缺口 | 分类 | 依据 |
|---|---|---|---|
| 1 | 建 GOV + 编制 + hh-gov + 国库账户；手工建官吏户/注资/固定俸禄 | **零代码可搭** | Q9 步骤 1/2/7/8；`simos.gov.createOffice`/`social.*`/`actor.AdjustAccounts`/`simos.gm.periodicAdjustment` |
| 2 | 未占用格/首次播种的 office 产业 + unit + assetShares + allocations + relation | **零代码可搭（受限）** | `economy.Seed` 首次/空闲格；破坏性 ClearRegion 不推荐 |
| 3 | gov 只读 MCP 工具（R6） | **小改**（app 单模块） | 复用 `ApiViews.economyGovernment` + `readTools`/`addDecisionAgentWrites`；无领域状态改动 |
| 4 | `ADMIN_SALARY` 新 reason | **小改**（economy-api 单模块）+ 测试 | `DeductionReason` 加一档；旧档缺省不影响读 |
| 5 | 固定周期俸禄桥（不按工时） | **小改**（app） | 复用 `HouseholdPeriodicAdjustment`/executor；不新增状态 |
| 6 | R5 `upsertIndustry`（版本化；新 id 方案） | **结构改动**（economy handler + app 工具；若只新 id 方案不触 5） | 新 action/守卫/工具/负向用例；见 Q2-C |
| 7 | 运行期建 GOV 生产 unit + 显式 operator + assetShares | **结构改动**（economy 新命令/载荷 + app 组合） | Q1/Q9 步骤 4；需新状态写口；铁律 2/5 |
| 8 | 岗位承诺（Q8-B/C）+ 不可缩优先级 | **结构改动**（economy-api/economy + 新命令 + 往返） | Q8；P1 |
| 9 | R1 服务流量 + P3 效率公式 + gov 源状态/handler | **结构改动**（gov + app + 可能 economy-api） | Q3/Q6/Q7；P3/R1/R2/R3 |
| 10 | R4 预算优先级（per-GOV 有序类别表） | **结构改动**（EconomyData 新组件 + 执行顺序 + 审批链工具） | Q5；P4c |
| 11 | 按承诺小时付酬（`FIXED_MONEY_PER_LABOR` 或 salary bridge） | **结构改动**（economy-api/economy + app） | Q5/Q9 |
| 12 | 19 hex bootstrap（官吏户/office/资产/承诺/注资） | **结构改动**（app world/seeder + 组合工具） | Q10；依赖 G1/G2 |
| 13 | 雇主主体、服务表示、三档映射、预算顺序、工资率/币种、市场语义、upsertIndustry 范围 | **待用户裁定** | 见 §6 |

---

## 5. 建议责任区/批次（Zones）与依赖顺序

> 遵守 AGENTS §一.5（一个责任区一个写代码代理、只写到编译过、测试最后统一）、§一.8（先落"约束设计书"再派实现 Agent；本文只是可行性报告，不是实现派单书）、§一.10（只给目标/边界/六要素，不给逐文件施工图；compile 只是入口门，最终看真实行为证据）。

| Zone | 目标（可独立编译/验收） | 可动模块（方向） | 依赖 | 关键验收（含负向） |
|---|---|---|---|---|
| **Z0 约束设计书** | 把 §0 冻结前提 + §6 裁定固化成 `docs/superpowers/specs/2026-10-23-gov-service-mode-design.md`（含约束设计书 + 用户原话附录，照 §一.8/§一.8.1） | docs only | 用户裁定 1~11 | 无代码；文档齐全才能派 Z1+ |
| **Z1 产业/单元基础设施（R5 + 政府生产 unit）** | `upsertIndustry`（版本化/守卫/负向）；创建/更新 GOV 生产 unit + assetShares + relation 的命令或组合工具；不建承诺/工资 | `simos-economy`、`simos-economy-api`（若加版本字段）、`simos-app` 的对应 GM 工具 | Z0；可与 Z2 并行但一次只一个写代码代理 | 新版本不改变老 unit 逐值行为；负向：原地改被 ACTIVE unit 引用的模板 ⇒ 具名拒；空 capacity/负值/坏 hex/版本倒退 ⇒ 拒 |
| **Z2 gov 源状态 + 效率公式（P3/R3/R2）** | 新 gov 源状态（静态修正 + 三档角色表）；`GovEfficiency.of` 新入参/公式（两维相乘÷1000、不封顶、`Math.*Exact`）；`GovOfficeState` 取消上限 + 流量读数；gov 首个 command handler | `simos-gov`（+ `simos-app` 工具后在 Z3） | Z0；需要 Z1 的承诺契约（若先把供给入参定义为 abstract，可与 Z1 并行但需冻结接口） | 公式逐值可复算；负向：无岗位入户 ⇒ 两维 0 + 具名 INFO；溢出 ⇒ 具名 ERROR；旧档缺 gov 源状态 ⇒ 中性默认 |
| **Z3 app 编排 + 服务流量 + 工资/预算 + 审批链工具** | `GovServiceFlow`（R1）逐 tick 产生/消费；承诺→供给的桥；新 `ADMIN_SALARY` + salary bridge；R4 预算执行；决策人/ GM 工具 + WHITELIST；R6 只读工具 | `simos-app`、`simos-economy-api`（reason/规则）、`simos-economy`（若按劳动量工资） | Z1 + Z2；审批链在 app | 同 tick 流量不落库；重启后不冒充有数据（`unavailable` 具名）；决策人工具走审批链、越权 GOV 被拒；GM 工具并列 |
| **Z4 unit/social 一处真相** | 三档岗位指派（posts/tier）、官吏户 `hh-unit:<unitId>`、staff 派生/收口、`withGovernment*`/一致性同步；招募/退休/吸收工具与 P2 对齐 | `simos-unit`、`simos-social`（可能需要人口桥）、`simos-app` 的 GOV 组合工具 | Z0；与 Z1/Z2 的 role 契约对齐 | posts 键 ⊆ Unit.households；staff 直写被拒/派生；旧档迁移可读回；负向：同一户两单位/位置不符 ⇒ 具名拒 |
| **Z5 bootstrap（19 hex）** | `SmallWorld`/`EconomySeeder`/`PopulationSeeder`（或 app 组合 bootstrap）建 GOV/官吏户/office 资产/承诺/注资；可复用 G1/G2 | `simos-app` world/tools | Z1~Z4；G1/G2 前置 | 真小世界 tick0 后：GOV office unit 存在、官吏户有承诺、国库有初始库存、效率读数非 0；负向：缺任一四边一致 ⇒ fail-closed/具名 gap |
| **Z6 统一测试** | 按 §三.0 在全部生产代码落地后：按验收判据写测试（含负向）、关键四项变异自证、全仓 `clean verify`、真实 world 读数 | tests only（测试代理） | Z1~Z5 | 守恒式/不丢失/静默付 0/断粮四类；权限不能放大；数据往返成立；旧档可读 |

**依赖顺序**：Z0 →（Z1、Z2 可并行但写代码代理一次一个）→ Z4 → Z3 → Z5 → Z6。若用户要求“先看 GUI/只读”，R6（Z3 的只读工具）可提前独立做（只读、零状态），不阻塞其余。

---

## 6. 待用户裁定清单（问题/选项/建议）

| # | 问题 | 选项 | 建议 |
|---|---|---|---|
| 1 | **服务产出表示**（R1 已限定为逐 tick 流量，但载体未定） | A. 空 `outputPerUnit` 产业 + 进程内 `GovServiceFlow`（R1 同形）；B. 伪商品+过期/清零；C. 新 service recipe 类型 | **A** 起步（不触铁律 5、零库存/市场；与 R1 同形）；C 后置为显式类型；B 不采纳 |
| 2 | **雇主主体** | A. `hh-gov-<unitId>`（HOUSEHOLD）当 operator/organizer；B. GOV Unit 当 operator + 政府家户当 residualOwner/份额 owner；C. GOV Unit + 集体劳动者 | **A**（P2 的 0 人口纯财政家户 + 单一账户主体；数据形状已支持；B 的 organizer==operator 约束使其更绕） |
| 3 | **劳动来源优先级** | A. GOV 承诺绝对优先（不足则 fail-closed）；B. GOV 承诺优先、剩余可接生产；C. 与生产同权按队列重排 | **B**（全职承诺不可缩；剩余预算允许生产；但 `Σ > laborMilli` 要具名 ERROR）。需同时裁“同一官吏户可否接生产” |
| 4 | **工资规则与国库注资** | 工资：A. 新 `ADMIN_SALARY` + salary bridge；B. 改 `GovernmentUpkeepOracle` sink→transfer；C. `FIXED_MONEY_WAGE` 生产 relation。注资：A. G1 具名 GOV 转账工具；B. 现有 `actor.AdjustAccounts`/`actor.RemitGovTreasury` 临时；C. Seeder 初始注资；D. 打开 seigniorage | 工资 **A**（reason 语义干净、可按承诺小时；B 作为过渡但要同步 staff 口径；C 需伪商品违 R1）；注资 **A 为主 + C 做 bootstrap**，B 只作临时 |
| 5 | **角色映射（R3 三档）** | M1 一档一维+第三档同维；M2 三档二维权重；M3 第三维 | **M2**（三档可跨维、不增第三维；默认 YAMEN=(1000,0)、SCRIBE=(0,1000)、POST=(0,1000) 或用户裁 POST=(500,500)） |
| 6 | **staff/posts/承诺一处真相** | A. staff 权威；B. posts 权威+staff 派生；C. 承诺权威+staff/posts 派生 | **C**（B 只作过渡；A 信息不足不可行） |
| 7 | **全职承诺落点** | A. 复用同表+受控 activity 词；B. 同表+`kind` 字段；C. 新专用组件 | **B**（若改动可控；否则 A 过渡，C 长期）。必须同时定“不可缩优先级”与 fail-closed 语义 |
| 8 | **“挂到市场”的语义** | A. 复用劳动队列+`laborSources`（行政分配队列）；B. 新 `GovJobPosting/LaborDemand` 原语（应募/工资/资格）；C. 商品市场伪商品 | **A 做 MVP + 明确不是市场；目标态 B**（C 拒绝）。若用户坚持“市场”字面，直接做 B |
| 9 | **`upsertIndustry` 本批范围**（R5 已纳入） | A. 只新建版本、不改既有；B. 允许原地改（危险）；C. 新 id 即新版本；D. 给 `Industry` 加 version 字段 + unit 版本引用 | **C 为 MVP**（不触 ChangeSet/Codec 形状；老 unit 零影响）；D 作为长期审计增强；B 拒绝 |
| 10 | **R4 预算类别顺序/比例** | A. 有序类别、顺序耗尽；B. 有序 + 每类 minShare/cap；C. 固定顺序不改 | **B**（用户可调、预算语义明确；类别顺序建议 行政俸禄→军俸→行政工资→债务→其他，或用户指定） |
| 11 | **gov 只读工具范围**（R6） | A. 复用 `ApiViews.economyGovernment` 全量；B. 只暴露两维效率/承诺/预算；C. GM-only vs 四桶共享 | **A + 决策人可见（受 gov 视野收窄）**；工具应 `GmOnlyRead` 还是四桶共享需裁（建议决策人可读自己的 GOV） |

---

## 7. 自证与边界

### 7.1 “0 命中”自证（同一命令在已知命中串上生效）

以下都在仓库根 `/home/cna/SimulatorMosire` 执行（未跑 Maven/测试/服务）：

1. **`upsertIndustry` 0 命中；正对照 `UPSERT_ASSET_RULE` 命中**
```bash
git grep -n 'upsertIndustry\|UPSERT_INDUSTRY' -- simos-economy simos-economy-api simos-app simos-unit simos-gov
# → 无输出（rc=1）
git grep -c 'UPSERT_ASSET_RULE' -- simos-economy
# → EconomyGmAdjustHandler.java:1、EconomyGmAdjustments.java:7（rc=0）
```

2. **economy 侧没有 `StaffRole`；正对照 `ProductionRole` 命中**
```bash
git grep -n 'YAMEN\|SCRIBE\|POST' -- simos-economy/src/main/java simos-economy-api/src/main/java
# → 无输出（rc=1）
git grep -c 'ProductionRole' -- simos-economy/src/main/java | head -3
# → EconomyData.java:14、EconomyChangeSet.java:2、LegacyClassStructure.java:22（rc=0）
```

3. **gov 侧没有承诺劳动/16,000；正对照 `GovDemand` 命中**
```bash
git grep -niE 'labor|commitment|16_?000|16000' -- simos-gov/src/main
# → 无输出（rc=1）
git grep -n 'GovDemand' -- simos-gov/src/main | head -3
# → GovDaily.java:117/224、GovEfficiency.java:62（rc=0）
```

4. **没有劳动力市场/劳动订单类型；正对照 `MarketSettlement` 命中**
```bash
git grep -niE 'laborOrder|laborMarket|laborPrice|wageOrder|laborDemandBook' -- simos-economy/src/main/java simos-economy-api/src/main/java simos-app/src/main/java
# → 无输出（rc=1）
git grep -c 'MarketSettlement' -- simos-economy/src/main/java | head -3
# → 多文件命中（rc=0）
```

5. **`EconomyGmAdjustments` 12 个 action 里没有 industry/unit/assetShare/allocation；正对照 `UPSERT_PRODUCTION_ORGANIZATION` 命中**
```bash
git grep -nE 'upsertUnit|upsertAssetShare|upsertAllocation|upsertCommitment|UPSERT_UNIT|UPSERT_ALLOCATION' -- simos-economy simos-app simos-economy-api
# → 无输出（rc=1）
git grep -c 'UPSERT_PRODUCTION_ORGANIZATION' -- simos-economy
# → EconomyGmAdjustHandler.java:1、EconomyGmAdjustments.java:7（rc=0）
```

6. **`GovernmentPostOfHousehold` 没有 laborMilli/hours/commitment；正对照 `headOfGovernment` 命中**
```bash
git grep -nE 'laborMilli|hours|commitment' -- simos-unit/src/main/java/io/mosire/simos/unit/GovernmentPostOfHousehold.java
# → 无输出（rc=1）
git grep -c 'headOfGovernment' -- simos-unit/src/main/java/io/mosire/simos/unit/GovernmentPostOfHousehold.java
# → 4（rc=0）
```

7. **`simos-gov` 没有 command handler；正对照 `simos-unit` 有 34 个文件**
```bash
git grep -lF 'CommandHandler' -- simos-gov/src/main/java | wc -l
# → 0
git grep -lF 'CommandHandler' -- simos-unit/src/main/java | wc -l
# → 34
```

8. **工具陷阱自证（glob pathspec 静默 0 命中；AGENTS §8.6）**
```bash
git grep -c 'hh-gov' -- 'simos-*/src/main/java' | wc -l
# → 0（glob 写法静默 0 命中）
git grep -n 'hh-gov' -- simos-app simos-unit simos-social simos-economy simos-gov | wc -l
# → 90（显式目录命中）
```

9. **子代理侧补充自证**（同一命令负例/正例均给出；本次已回核关键文件）：
- `ADMIN_SALARY` 0 命中 vs `MILITARY_SALARY` 命中（`DeductionReason.java:19,26`）；
- `governmentPostsOfHousehold` 在 `simos-gov/src/main` 0 命中 vs `GovernmentFormation/GovDaily` 命中；
- `HouseholdLaborCommitment` 在 `simos-gov/src/main` 0 命中 vs `GovDemand` 命中；
- `hh-unit` 在 `GovCreateOfficePlan.java` 0 命中 vs `GovernmentHouseholds` 命中（`:9,:385`）；
- `staffIsHouseholdProjection` 仅定义无调用 vs `staffHouseholdProjection` 有调用（`:211/:215`）；
- `*Allocation*Handler.java` 0 个 vs `*PeriodicAdjustment*Handler.java` 2 个；
- `office@` / `moneyOutputPerUnit|MintRule|MintPolicy` 全 Java 源码 0 命中 vs `farm@`/`mint` 文档命中；
- `SmallWorld` 中 `SetGovFormation|CreateUnit|RegisterGovernment|createOffice` 0 命中 vs `economy.Seed` 命中（`:91/:228/:259`）。

### 7.2 未核到的点（如实记）

- **未跑任何 Maven/测试/服务/DB/GUI**；所有运行时数值、旧档兼容、并发与审批链实际拦截行为均未验证。
- **未逐行重核税链**（Q5 的 F1 数字采用既有报告 `docs/superpowers/reports/2026-10-23-gov-tax-treasury-investigation.md` §3）；未逐行重核 `GovernmentSeigniorage`、`JurisdictionDailyTax`。
- **未验证**候选采用在真实 `simos.advance` 下物化 `office@hex` 的行为；Q9 的“候选路径可物化私营 office unit”是静态结论，且前置是“office 产业与份额已存在”（候选不能创建新产业，见 Q2 事实链 4）。
- **未验证** `upsertIndustry` 新 id 方案与 `IndustryHexKeys`/`ProductionUnitId`/`EconomyChangeSet/Codec` 的所有读点（尤其 GUI/ledger/ExpectedProfitBook）是否都按 `<kind>@hex` 解析；需要实现时逐点核。
- **未验证**非 `unit-` activity 的 GOV 承诺在真实 `LaborQueueSettlement`/`reallocateLabor` 里的逐日保留/缩比行为。
- **未验证** GovEfficiency 公式切换（乘积÷1000 + 16,000ms 系数 + 不封顶）后的 golden/旧档数值回归；`GovOfficeState` 取消上限对 GUI/API 的序列化影响未核。
- **未验证** `ADMIN_SALARY` 新增后的 codec/roundtrip 与旧档读入；`HouseholdLaborCommitment` 加 kind 字段的迁移路径未设计细节。
- **未验证** R4 预算优先级改动与现有 `GovDaily`/军俸执行器的交互（尤其 `GovernmentUpkeepOracle` 的 sink、`PeriodicHouseholdAdjustmentExecutor` 的 id 排序）。
- **未验证** 19 hex bootstrap 扩展后的端到端 tick0→tickN 读数；未跑 `run-small-world.sh`。
- **未核** AgentLib `AskGate` 的实际判定语义（只核到 jar 里有 `ToolGate$Ask.class`；本仓装配是 `AutoApproveGate→ConfirmGate→PendingApprovals`）。

---

## 8. 附录

### 8.1 用户原话（逐字）

> 我的想法是，单个政府可以配置一个政府专属的生产方式，政府自己需要的劳动力，既可以从政府自己管的家户里获得，也可以作为需求，挂一个生产方式到市场，允许其他经济家户加入，然后为政府提供劳动，这个能实现吗？

### 8.2 本会话冻结裁定/追加裁定的引用位置

> 这些是父代理在本会话转达的 2026-10-23 裁定；**尚未见独立落盘文档**（全仓 `政府专属` 0 命中）。按 AGENTS §一.8/§一.8.1，Z0 必须先把它们固化成 `docs/superpowers/specs/2026-10-23-gov-service-mode-design.md`（含"用户原话附录"），再派实现 Agent。

- **P1~P4 冻结前提**：本报告 §0.2；来源=父代理任务书（2026-10-23）；相关既有落盘证据：`docs/superpowers/specs/2026-10-23-production-efficiency-framework.md`（生产效率动态修正先例）、`docs/superpowers/specs/2026-10-08-universal-population-architecture.md`（人口/劳动权威方向）、`AGENTS.md` §〇（Social 是人口/劳动/需求唯一权威；`HouseholdEconomy.laborMilli` 是注入视图）。
- **R1（逐 tick 流量）**：本报告 §0.3；代码同形证据 `MarketReport.java`/`MarketReadout.java`/`MerchantFirm.java:243-268`/`MerchantSettlement.java:520`。
- **R2（决策人+审批链）**：本报告 §0.3；代码落点 `DecisionCallerFactory.java:105-163`、`SimosToolSource.java:778-806`、`Shell.java:848-872`；AgentLib `ToolGate$Ask/AutoApproveGate/ConfirmGate/PendingApprovals`。
- **R3（三档岗位）**：本报告 §Q6；代码现状 `StaffRole.java:12-21`、`GovernmentPostOfHousehold.java:28-41`、`GovEfficiency.java:95-106`。
- **R4（预算优先级）**：本报告 §Q5；代码现状 `PopulationEconomyTimeParticipant.java:528-602`、`docs/superpowers/status/2026-10-23-planned-not-implemented-inventory.md:42-44`。
- **R5（upsertIndustry 本批）**：本报告 §Q2-C；版本先例 `EconomyGmAdjustments.java:304-366`。
- **R6（gov 只读工具）**：本报告 §Q9/Q10；现有读口 `ApiViews.economyGovernment`（`/api/economy/gov`）；AGENTS §8.3（GUI/MCP 共用同一视图层）。
- **既有报告引用**：`docs/superpowers/reports/2026-10-23-gov-tax-treasury-investigation.md`（F1~F4、G1~G7、run5 真档）、`docs/superpowers/reports/2026-10-23-editability-and-efficiency-investigation.md`（economy 20 命令/GmAdjust 能力矩阵、产业无编辑口）、`docs/superpowers/status/2026-10-23-planned-not-implemented-inventory.md`（M1/P4c 未做）。

### 8.3 一句话给裁定的最短版

**能实现**：`hh-gov-<unitId>` 当政府雇主的数据形状、其他家户对同一 unit 出承诺劳动、从国库付款三条底层都已存在；但零新增命令只能搭出“政府发固定俸禄”，要实现用户原话必须补 **R5 产业模板编辑 + 政府生产 unit 创建 + 岗位承诺/不可缩优先级 + 逐 tick 服务流量 + 两维承诺劳动效率 + ADMIN_SALARY/预算优先级 + 决策人审批链工具/gov 只读口**；建议按 Z0→Z1/Z2→Z4→Z3→Z5→Z6 分批，先裁 §6 的 11 个问题。
