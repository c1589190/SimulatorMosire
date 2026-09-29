# Simos 经济模块理想架构设计（目标态）

> **性质**：目标架构设计，不描述当前生产代码，不承诺本版已实现。
> **本文取代**：本文件旧版《Hex 级政治经济学生产模拟：代码与机制设计》及其中已被 R2–R4/E 系列实现覆盖的旧假设。
> **纪律**：这是目标设计；实现时必须按仓库现行铁律走 `Command → ChangeSet → Revision`，所有状态有独立 Snapshot/ChangeSet/Codec，所有写入有唯一写口，所有数量用整数定点。
> **范围**：生产方式、阶层、家户/人口成分、生产组织、生产资料与租佃、货币发行、债务合同、清偿与资产处置、阶层变动、模式变迁、市场与观测。
> **非范围**：文化、宗教、农民起义、现代银行/央行、跨币种兑换、证券期货、金融衍生品、现代公司制度。

---

## 0. 一句话目标

把经济模拟从“先有生产、后算阶层”改成：

```text
生产方式 → 阶层结构 → 人口/家户纳入阶层
→ 阶层自动组织生产
→ 市场与再生产缺口
→ 债务（粮/钱/地/生产资料）
→ 粮优先偿还
→ 还不起则按制度处置核心生产资料
→ 阶层下滑或重组
→ 生产方式变迁重新生成阶层结构
```

一句话：**生产方式决定阶层结构；阶层结构决定谁提供劳动、谁组织生产、谁占有生产资料、谁承担债务；家户是阶层内部的人口成分。**

---

## 1. 设计公理

1. **阶层不能独立于生产方式被计算。**  
   阶层不是事后收入标签，而是生产关系中的位置：对生产资料的占有关系、劳动组织中的角色、剩余产品的处置权。

2. **生产方式与阶层结构一起生成。**  
   定义一个 `ProductionMode` 时，必须同时定义它允许的 `ClassStructure`；不存在“先搞一个生产方式，阶层以后再猜”的状态。

3. **家户保留，但不是阶级身份本身。**  
   家户是稳定身份，承载年龄、性别、人口、消费、账户、文化扩展点；一个阶层可以包含多个家户；一个家户在某一时刻有一个当前阶层归属；模式变迁或社会流动可以改变归属。

4. **人口成分是家户的内部明细。**  
   年龄/性别/人数是 `PopulationGroup`，通过 `Membership` 挂到家户；家户的阶层构成通过“家户 → 阶层”聚合得到。

5. **有劳动、有可用生产资料，就必须尝试按生产方式组织生产。**  
   组织不起来不是“不生产”的借口：必须落一条具名缺口（缺土地、缺工具、缺投入、缺市场、缺许可），供读口与债务/救济/清算使用。

6. **货币由政府发行，粮食由人生产。**  
   货币总量不是自然常数。发行、回笼、初始发钱都必须显式记账；粮食只能由生产产生，利息、租金、债务都不增加粮食总量。

7. **农业时代粮食是硬通货。**  
   粮债优先于货币债；货币折偿必须有有效价格或合同约定；没有价格不得硬折。

8. **债务是持续的生产关系，不是一次借款事件。**  
   同一（债务人、债权人、商品/货币、合同条款）是一条连续欠账；借入、计息、实还、折偿、减免都滚动在同一条上。

9. **还不起可以导致阶层下滑；底层债务可以累积到警告。**  
   警告是 hex 级信号，不是自动起义；起义不在本设计范围。

10. **派生读数不得变成第二真相。**  
    债务压力、货币分布、阶层下滑、市场缺口、危机信号都从源状态派生；需要持久化的，必须显式进状态、变更集与回放。

---

## 2. 概念体系

### 2.1 生产方式 `ProductionMode`

`ProductionMode` 是经济的第一性结构，包含四组东西：

```text
ProductionMode(
  id, name, version,
  classStructure,                 // 本生产方式允许的阶层位置集合
  productionTemplates,            // 产业/配方/周期/投入产出
  defaultRelations,               // 各阶层之间的默认分配关系
  assetRules,                     // 核心生产资料、租佃、抵押、处置规则
  moneyRules,                     // 货币地租/工资/债务/发行口径
  consumptionNorms,               // 各阶层基本/改善消费标准
  transitionRules                 // 本模式与其他模式的转换规则
)
```

- `ProductionMode` 不直接等于 `Industry`；`Industry` 只是它下面的技术模板。
- `ProductionMode` 不直接等于 `RegimeId`；`RegimeId` 可以保留为历史/描述字段，但经济规则以 `ProductionMode` 为准。
- 一个世界可以同时存在多个生产方式（不同地区/不同产业/过渡期），但每个生产单元必须明确属于一个 mode。

### 2.2 阶层结构 `ClassStructure` 与阶层位置 `ClassPosition`

```text
ClassStructure(
  id, modeId,
  positions: Map<ClassPositionId, ClassPosition>,
  defaultSharesPerMille: Map<ClassPositionId, Long>
)

ClassPosition(
  id, modeId, name,
  relationToMeans: OWNER | OPERATOR | DIRECT_LABORER | MIXED,
  laborRole: ORGANIZER | PROVIDER | BOTH | NONE,
  surplusRole: SURPLUS_RECEIVER | WAGE_EARNER | SELF_SUBSISTENCE | DEPENDENT,
  assetRights: 该位置可占有/可使用的生产资料种类,
  laborObligation: 劳动义务/可支配劳动规则,
  consumptionNorms: 基本口粮、再生产品、改善消费,
  debtRules: 可借什么、向谁借、利率/期限/清偿优先,
  liquidationRules: 还不起时核心生产资料的处置规则,
  upward/downwardRules: 与其他位置的转换条件
)
```

- 阶层有“固定经济状态”的意思是：**在给定 mode 下，这个位置的结构规则固定**；不是收入永远不变。
- 阶层位置不是永久身份：人口/家户可以在同一阶层内迁移，也可以在模式变迁或社会流动中改变位置。
- 阶层位置不直接持有账户：账户仍属于家户/经营者 actor；阶层通过成员关系和资产权利聚合。
- **禁止**把阶层当成 `Industry.slots` 的附属品；`Industry.slots` 只能作为旧档模板投影，不能定义阶层结构。

### 2.3 家户与人口成分

#### 家户 `Household`

```text
Household(
  id,                      // 稳定身份；改名、迁居、阶层变化都不变
  currentClassPositionId,  // 当前阶层位置；可随时间变化
  memberships,             // 人口成分内部明细
  consumptionProfile,      // 基于年龄/性别/阶层的消费标准
  accountRefs,             // 指向 actor 账户
  culturalTags             // 文化系统预留扩展点
)
```

- 家户承载年龄/性别：未来文化、教育、宗教、习俗都可挂在这里。
- 家户保留账户与债务身份：阶层变化不换账户、不丢债务。
- 家户可以有多个：同一阶层位置下允许多种家户，不同人口构成、不同劳动能力、不同消费需求。

#### 人口成分 `PopulationComposition`

人口成分是家户的内部明细，建议由现有 `PopulationGroup` + `Membership` 演进：

```text
PopulationGroup(
  id, residence, sex, count,
  ageAtAnchorDays, anchorTick,
  physiologicalStress
)

Membership(
  id, lotId, householdId, count,
  classPositionId?,        // 可选冗余；权威仍由 Household.currentClassPosition 派生
  fromDay, toDay?
)
```

- 年龄/性别不进入家户字段复制一遍；从成员批次现算。
- 同一家户可含多个性别/年龄批次；同一批次可按比例分属多个家户（最大余数法 + 稳定 ID 尾差）。
- 阶层的“人口成分”= 该阶层全部家户的成员按年龄/性别聚合。
- 生产方式变化时，改变的是 `Household.currentClassPositionId` 与/或 `Membership` 归属，不改人口身份。

### 2.4 生产组织 `ProductionOrganization`

生产组织阶段回答一个唯一问题：

> 按当前生产方式、阶层结构、各阶层可支配劳动与生产资料，**本期应该存在哪些生产单元？**

```text
ProductionOrganization(
  id, modeId, classPositionId,
  unitId,                  // 对应的 ProductionUnit
  organizer,               // ActorRef：谁组织/经营
  laborSources,            // 参与劳动的家户/批次
  assetSources,            // 使用的 AssetShare
  inputSources,            // 谁出种子/原料/工具
  outputOwnership,         // 产出先归谁
  relationTemplateId,      // 按 mode 生成的分账规则模板
  status                   // ACTIVE / SHORTAGE / SUSPENDED / EXITING
)
```

- 组织者 `organizer` 是一个经济主体身份（ActorRef），可以是庄园、作坊、家户、政府或单位；它不等于阶层本身，也不等于地主。
- 有劳动 + 有可用生产资料 ⇒ 自动产出/激活 `ProductionUnit`；不够规模时按最紧约束缩产，并记录 `SHORTAGE` 原因。
- 没有生产资料但有人口/劳动：该阶层可以成为雇工/佃农/流民候选；是否借贷、租地、迁移由 debt/market/mode 规则决定，不允许静默不处理。
- `ProductionUnit` 仍是唯一的生产进度与投入实体；`ProductionOrganization` 是它与阶层/生产方式之间的桥。

### 2.5 生产资料与租佃

```text
AssetShare(
  id, industry, asset,      // LAND / CATTLE / TOOL / WORKSHOP / MACHINE / SHIP
  owner, operator,          // 所有权 vs 实际使用/经营
  quantity, kind            // OWNED / TENANCY / COMMUNAL
)

AssetRule(
  modeId, assetKind,
  isCoreMeans: boolean,     // 是否核心生产资料
  pledgeable: boolean,      // 可否抵押/质押
  liquidationPriority: int, // 清算顺序
  rentRule: RentRule,
  transferRule: TransferRule
)
```

- `owner != operator`：租佃、委托、占用；`kind=TENANCY` 明确表达租来的土地/作坊/工具。
- 农业时代的土地、作坊时代的作坊/机床都可以是核心生产资料。
- 租金可以是：
  - 固定实物（粮/布/铁）；
  - 固定货币；
  - 产出分成；
  - 组合（实物租 + 货币附加）。
- “租地要花钱”不是特例，而是 `RentRule` 的一种；生产方式决定哪些阶层租何种生产资料、以什么形式付租。
- 租佃本身不是债务；**欠租资本化**才是债务。当前租金应付未付部分可在下一结算步转为 `DebtContract`。

### 2.6 货币与政府发行

```text
Government(
  id, nationRef,
  moneyAuthority,           // 发行/回笼权限
  treasuryAccount,          // ActorRef(GOVERNMENT, ...)
  financeController,
  financeOperators,
  violenceManager
)

MoneyIssuance(
  id, governmentId, day,
  currency, amount,
  kind: INITIAL_ENDOWMENT | FISCAL_ISSUE | WITHDRAWAL,
  reason, auditRef
)
```

- 货币由 `Government` 发行；`MoneyAuthority` 是发行/回笼的唯一合法主体。
- 初始化由 GM 代行 GOV：一次性 `INITIAL_ENDOWMENT` 发给家户/经营者/官署；这是显式发行记录，不是“天上掉的钱”。
- 运行期普通主体不能造钱；发行主体账户可以有发行腿（单边铸/回笼），普通账户货币余额不得为负。
- 钱通过真实收支流动：
  - 地租/税/货款把钱从下层/买方抽到上层/卖方；
  - 工资、救济、政府采购、上层消费把钱送回下层；
  - 储蓄/窖藏/存款是另立工具，不是默认行为。
- 目标不是“钱自动平均”，而是**可观察的货币集中与回流**：税、租、工资、市场、政府支出各自有账。

### 2.7 债务合同 `DebtContract`

```text
DebtContract(
  id,
  debtor, creditor,          // 先 HouseholdId，理想态可扩展到 ActorRef
  unit,                      // 粮 / 货币 / 其他商品
  principal,                 // 连续余额
  terms: {
    interestRatePerCycle,
    interestTiming,          // 先计息后还 / 先还后计 / 按日计
    repaymentRule,           // 每期应还比例/固定额/到期
    monetaryConversion,      // 是否允许、价格口径、币种
    collateralRefs,          // 抵押/质押的 AssetShare
    defaultRemedy            // 宽限 / 强制处置 / 转给债权人 / 减免
  },
  openedDay, lastInterestDay, dueCycle?,
  status: NORMAL | DELINQUENT | DEFAULTED | SETTLED | FORGIVEN
)
```

- 同一 `(debtor, creditor, unit, terms)` 是**一条连续欠账**；跨周期不新开条。
- 不同条款（利率、折偿、抵押）必须分开，不能被“同债务人同债权人”悄悄合并；阶层/地区读数再汇总总额。
- 借粮、借钱、借地/生产资料都只是 `DebtContract.unit` 与 `terms.collateralRefs` 的不同取值。
- 借地/借生产资料可以表达为：
  - 租约（recurring rent，不是债务）；
  - 欠租资本化（成为 DebtContract）；
  - 抵押/质押（贷款以 AssetShare 为担保）。

### 2.8 清偿、违约、资产处置与阶层变动

```text
DebtCapacity(
  repayableSurplusF,          // 可偿还流量
  pledgeableAssetValue,       // 可质押真实资产价值
  explicitOutputRights,       // 明确的下一季产出权益
  existingDebt                // 现存本金 D
)

newCreditHeadroom = max(0, κ × F + pledgeableAssetValue
                              + explicitOutputRights − existingDebt)

F = max(0, 本期分配后粮所得
           − 基本口粮
           − 下一轮必要投入
           − 实缴税)
```

清偿顺序（默认制度，可由 mode/合同加严或放宽）：

```text
1. 必要口粮与下一轮生产投入（先保留再生产底线）
2. 实物债偿还：粮债优先，真实转粮
3. 货币折偿：合同允许且有有效价格/约定价格时，用真钱偿还
4. 核心生产资料处置：按清算政策卖地/卖作坊/转给债权人
5. 余债挂账、减免或按制度转入下一期
```

- **粮优先**：农业时代粮是硬通货；实物粮债排在货币债前。
- **不硬折**：没有市场价/合同价时，不得把布、银、土地硬折成粮；记 `unpriced`。
- **不造粮**：利息、租金、债务、发行都只搬价值/记债权，不增加粮食总量。
- **还不起**：
  - 先按合同给予宽限或重组；
  - 触发清算时，按 `LiquidationPolicy` 对核心生产资料按比例处置；
  - 触发阶层下滑：家户 `currentClassPositionId` 向下迁移，成员份额按规则随迁；
  - 底层债务可以继续累积到阈值，产生 hex 警告；
  - 起义不在本设计内。

### 2.9 模式变迁 `ModeTransition`

```text
ModeTransition(
  id, householdId / classPositionId,
  fromModeId, toModeId,
  proportion: { 资产比例、劳动比例、成员比例 },
  retainOriginalClassPerMille,  // 保留原所属的比例
  newClassPositionId,           // 转入的新位置
  phase: PLANNED | ORGANIZING | ACTIVE | SETTLED,
  startedDay, settledDay,
  reason
)
```

- 方式改变时，旧 `ProductionUnit` 不原地改 `modeKey`；而是：
  1. 计划：确定新 mode、新阶层结构、新旧位置映射；
  2. 组织：按比例迁移成员、劳动、资产、债务、既得权利；
  3. 激活：新 unit/relation/organization 生效，旧 unit 进入 `EXITING`；
  4. 结算：旧 unit 退出处置，阶层写回新位置。
- “保留原所属（按比例计算）”：用 `retainOriginalClassPerMille` 或 `ClassShare` 表达混合归属；单值 `currentClassPositionId` 表达不了时，需要多值份额状态。

---

## 3. 状态模型

### 3.1 切片与所有权

```text
map       —— 地形、区域、城市落点、距离、路径
social    —— PopulationGroup、城市节点、人口动态
unit      —— 军事单位与移动（与经济生产单位分离）
sd        —— 国家、决策人、Directive、战斗；不拥有经济状态
actor     —— ActorRef 身份 + GoodsAccount（商品/货币/冻结）唯一账本
economy   —— 生产方式、阶层结构、家户、生产组织、债务、资产份额、市场、需求、流动
gov       —— 政府、官署、财政、税则、货币权威、救济；发行与财政的显式主体
core      —— Command/ChangeSet/Revision/时间推进，不认识领域类型
app       —— 组合根；跨切片协调器、读口、工具
```

### 3.2 目标 `EconomyData` 组件

推荐目标态组件（数量随实现收敛，不要求一次到位）：

```text
meta
modes                 Map<ProductionModeId, ProductionMode>
classStructures       Map<ClassStructureId, ClassStructure>
classPositions        Map<ClassPositionId, ClassPosition>
households            Map<HouseholdId, Household>
memberships           Map<MembershipId, Membership>
productionUnits       Map<ProductionUnitId, ProductionUnit>
productionOrgs        Map<ProductionOrgId, ProductionOrganization>
industries            Map<IndustryId, Industry>          // 技术模板
relations             Map<ProductionUnitId, ProductionRelation>
assetShares           Map<AssetShareId, AssetShare>
laborSupply           Map<PeopleLotId, LaborSupply>
laborAllocations      Map<LaborAllocationId, LaborAllocation>
debtContracts         Map<DebtContractId, DebtContract>
pledges               Map<PledgeId, Pledge>
classStandings        Map<HouseholdId, ClassStanding>    // 持久化原所属/下滑/比例
modeTransitions       Map<ModeTransitionId, ModeTransition>
markets               Map<HexCoord, Market>
shipments             Map<ShipmentId, ShipmentBatch>
demandBook            Map<DemandId, DemandEntry>
flows                 Map<HouseholdId, FlowRow>
crisisSignals         Map<HexCoord, HexCrisisSignal>     // 或派生读口
```

- `GoodsAccount` 仍住 actor 切片，economy 只持 `ActorRef` 与账户位置。
- `Government/MoneyIssuance` 住 gov/actor（或 economy 的财政扩展）；国库是 actor 账户，不在 gov 复制余额。
- `Flows` 按家户稳定 ID 汇总；阶层/生产方式读数从 households/classStandings/memberships 现算。

### 3.3 关键身份

```text
ProductionModeId         稳定身份
ClassStructureId         稳定身份
ClassPositionId          稳定身份
HouseholdId              稳定身份（账户、债务、文化扩展点）
PeopleLotId              人口批次身份（年龄/性别/居所）
ProductionUnitId         稳定身份（生产活动）
DebtContractId           稳定身份（连续欠账；条款维在 id 内或单独 termsId）
AssetShareId             稳定身份（实物份额）
```

**唯一拼写点**：每个稳定 ID 的格式只在一个文件定义，`toString + parse` 成对；所有 map 键 = 值内 id；不用随机数、时间戳、UUID 作为状态身份。

---

## 4. 运行顺序

### 4.1 初始化

```text
1. 生成地图、区域、城市、人口批次（social）
2. 生成生产方式与阶层结构（economy）
3. 把人口/家户分配到阶层位置（economy）
4. 生成生产组织与生产单元（economy）
5. 生成资产份额、租佃关系、劳动关系（economy）
6. GM 代 GOV 发行初始货币，发给家户/经营者/官署（gov/actor）
7. 如需要，播种已有债务/租约/抵押（economy/gov）
8. 一批命令落一条 revision；做守恒检查
```

- 初始化发钱不是债务；它是显式 `INITIAL_ENDOWMENT` 发行记录。
- 初始债务必须配套已有库存/权利/产出权益，不能只给一份名册。

### 4.2 每日结算

```text
① 维护/激活生产组织
   按 mode + class structure + labor + assets 计算本期应存在的 unit
   不足则缩产/记录 SHORTAGE，不静默不生产

② 周期第一天：扣下一轮投入（种子/原料/工具）
   明确投入来源：阶层/operator/inputSupplier

③ 到货：ShipmentBatch 到达日入目的地账户

④ 消费：家户按人口成分与阶层消费标准吃自家库存
   不足先记 unmetNeed / deficit，不立即借

⑤ 生产推进：progressDays、劳动投入、周期累计

⑥ 周期末：
   收获 → 毛产/损耗/净产
   净产先归 output ownership
   按 relationTemplate 分账：地租、工资、分成、给养
   更新各阶层/家户 FlowRow

⑦ 市场：按必要购买/投入 → 债务占用 → 改善性购买分层
   商品交易走 Transfer + ShipmentBatch
   货币交易走 Transfer；无假赊购

⑧ 债务：
   计息 → 实物偿还 → 货币折偿 → 违约判定
   粮债优先；无价格不折

⑨ 资产处置/阶层变动：
   按 LiquidationPolicy 处置核心生产资料
   还不起 → 阶层下滑/重组
   底层债务超阈值 → hex 警告

⑩ 流水平移/清理：新周期第一天清零时点周期量
```

### 4.3 债务结算顺序

```text
1. 计算每户/每主体 F = 可偿还流量
2. 计算 newCreditHeadroom = max(0, κF + 可质押资产 + 明确产出权益 − D)
3. 优先向本地/有生产关系/确有余粮的债权人借
4. 借款真实转移；借粮/借钱分别落 DebtContract 的 unit
5. 计息：只增加债务余额，不增加粮/钱总量
6. 还债：
   a. 实物债：粮优先，真实转粮
   b. 货币债：合同允许且有有效价格时，用真钱折偿
   c. 余债挂账；触发清算条件则进入资产处置
7. 违约处置：
   a. 宽限/重组/减免（按合同）
   b. 核心生产资料按比例处置
   c. 阶层下滑/重组
   d. 底层债务累积到阈值 → hex 警告
```

### 4.4 生产方式变迁

```text
1. 计划 ModeTransition：fromMode→toMode、比例、保留原所属规则
2. 生成目标 ClassStructure/ClassPosition
3. 按比例迁移：
   - 成员/家户：ClassMembership/ClassStanding
   - 劳动：LaborAllocation
   - 资产：AssetShare owner/operator/kind
   - 债务/租约/抵押：DebtContract/Pledge
   - 既得权利：ClassShare/retainedPerMille
4. 新生产组织生效；旧 unit → EXITING → 退出处置
5. 一次 revision 内完成，失败整批回滚
```

---

## 5. 货币、债务、租金与清偿

### 5.1 货币

- 币种与工具：先保留 `silver`；未来可加国币、存款工具，但不得在无发行主体时造钱。
- 发行主体：`Government`；`MoneyAuthority` 是唯一可在账户不足时单边发行/回笼的主体。
- 初始发钱：GM 代 GOV 一次发行，记录 `INITIAL_ENDOWMENT`；可按人口/阶层/家户加权，参数来自世界预设。
- 普通账户余额非负；发行主体账户允许负余额表示净发行，或使用“发行腿不扣”的单边记录。
- 货币流动必须可在读口按家户/阶层/actor kind 聚合，并显式区分发行量与流通量。

### 5.2 债务规模与信用

```text
需求缺口 = 基本需求 + 下一轮投入 − 自有产出/库存 − 市场可支付买入
F        = max(0, 本期分配后粮所得 − 基本口粮 − 下一轮投入 − 实缴税)
头寸     = max(0, κF + 可质押资产价值 + 明确下一季产出权益 − 现有债务)
```

- “债务规模”不是凭税率或信用线凭空生成，而是由缺口、可购买量、偿还依据、抵押物共同决定。
- 无 F、无资产、无明确产出权益 ⇒ 私人信用为 0；缺粮留在 `unmetNeed`，等市场、官仓救济、制度救助。
- 借入必须有真实债权人库存转出；利息只加债权，不造粮/钱。
- 借地/借生产资料两种形式：
  1. 租约（recurring rent）；
  2. 抵押/质押债务（以 AssetShare 为 collateral）。

### 5.3 偿还顺序

```text
1. 保留必要口粮、种子、下一轮投入
2. 实物偿还：粮债优先（农业时代粮是硬通货）
3. 货币折偿：合同允许 + 有效价格/约定价格 + 真实货币转移
4. 核心生产资料处置：按 policy 卖/抵/转给债权人
5. 余债挂账、重组、减免或转下一期
```

- 清偿顺序是制度参数，可由 mode/合同加严（例如“强制先征债粮”）或放宽（例如先保口粮）。
- 没有价格时，不能把银/布/土地硬折成粮；记 `unpriced` 并挂账。
- 债务高不会直接把“想要的需求”删掉；它减少可用预算、可获新信用和最终成交量。

### 5.4 抵押、清算与核心生产资料

```text
Pledge(
  id, debtContractId, assetShareId,
  quantity, modeId,
  priority, status
)

LiquidationPolicy(
  modeId, assetKind,
  coreMeans: boolean,
  liquidationPriority,
  maxLiquidatePerMille,     // 按规定比例，不是无限卖
  protectedReserve,         // 口粮/种粮/最低生产资料
  priceSource: AGREED | MARKET | POLICY,
  recipientRule: CREDITOR_FIRST | MARKET_FIRST
)
```

- 只有 `owner == debtor` 的自有份额可被直接处置；租佃份额不能由佃户卖。
- 处置要按稳定顺序：核心程度、抵押优先级、AssetShareId。
- 处置必须与债务本金扣减、资产份额转移、阶层归属变化在同一原子流程里完成。
- 记账价优先用合同价；有资产市场时可用市场价；都没有则只能做“制度折算/抵债”，并明确标注来源。

---

## 6. 市场

- 市场保留“想要多少”的需求；成交量由真实库存、真实货币、可获新信用和运输能力决定。
- 买入资源分层：
  1. 维生购买 + 下一轮生产投入；
  2. 按合同/制度执行的债务偿还；
  3. 改善性消费及其他可推迟购买。
- 实物债偿还只减少粮/商品库存，不直接减少银预算；只有货币折偿或货币债务才减少银预算。
- 不允许假装存在“市场赊购”：若没有卖方同意、应收款、违约条款，就不存在“市场信用”。
- 跨 hex 交易必须走实际运输：`ShipmentBatch`、ETA、损耗、运力；在途不能消费。
- 价格是数据/制度，不是隐式公式；自适应价格只是可选 policy。

---

## 7. 阶层、家户与人口

### 7.1 家户

- 家户是人口成分的载体：年龄、性别、人数、健康、文化标签。
- 一个阶层可包含多个家户；家户可有不同人口构成与消费需求。
- 家户持有 actor 账户、债务身份、消费流水；阶层变化不改变这些身份。
- 生产方式变化时，家户的阶层归属/份额可改变，身份保持稳定。

### 7.2 阶层下滑/上升

```text
ClassStanding(
  householdId,
  originalClassPositionId,
  currentClassPositionId,
  retainedShares: Map<ClassPositionId, Long>,  // 可选：按比例保留
  consecutiveDebtStressCycles,
  lastTransitionDay,
  transitionReason
)
```

- 下滑判据至少包括：可偿还余量 F、债务本金 D、违约状态、核心生产资料是否已处置、再生产是否还能维持。
- 读 `defaulted`、`F`、`D`、利息、投入缺口、口粮缺口，不只看账面收入。
- 阶层变化持久化；读口可回溯“从哪档到哪档、为什么”。
- 模式变迁时，`retainedShares` 可表达“保留原所属（按比例）”。

### 7.3 hex 警告

```text
HexCrisisSignal(
  hex, kind, severity, day,
  evidence,                 // 触发量：债务/产出、利息/F、口粮缺口、投入缺口
  households, classes
)
Kind: FOOD | CLOTH | MORTALITY | DEBT_EXPLOSION |
      CLASS_DECLINE | INPUT_SHORTFALL | LABOR_BURDEN
```

- 警告是读口/信号，不是自动起义；起义不在当前范围。
- 底层债务累积到爆炸阈值时发 `DEBT_EXPLOSION`；阶层下滑发 `CLASS_DECLINE`。
- 信号可派生或持久化；需要跨重启保留时，进 economy 状态与 codec。

---

## 8. 命令与接口（目标态）

```text
economy.DefineMode          定义生产方式 + 阶层结构 + 规则模板
economy.AssignPopulation    把人口/家户纳入阶层位置
economy.OrganizeProduction  按 mode/阶层/劳动/资产组织生产
gov.IssueMoney              GOV 发行/回笼
gov.InitialEndowment        GM 代 GOV 初始化发钱
economy.SetRelation         设置租佃/工资/分成合同（窄命令）
economy.Borrow              发起债务合同（粮/钱/地/生产资料）
economy.Repay               实物/货币偿还
economy.PledgeAsset         抵押/质押
economy.Liquidate           核心生产资料处置
economy.SwitchMode          生产方式变迁
economy.TransferAssetShare  人工资产转移（已有）
```

命令只提交结构化数据；不允许 LLM/GM 直接改派生读数。GM 调整工具必须预览、原因、前后差异、审计，且不能成为普通 GOV Agent 绕过政治能力的入口。

---

## 9. 观测

至少按以下窗口分别读：

```text
时点存量：债务本金、土地/作坊占有、货币余额、人口成分
本期流量：产出、消费、租金、工资、利息、借入、实还、税、救济
本期派生：F、信用头寸、债务/产出、利息/F、基本需求缺口、下一轮投入缺口
结构状态：阶层分布、家户阶层归属、模式、生产组织、危机信号
```

- 货币分布按家户/阶层/actor kind 聚合，并区分初始发行、运行期发行、流通量。
- 债务读口要能分清：有债但还能再生产；利息超过 F 债务自增；无偿付依据被拒贷；欠债群体改善性购买下降。
- 所有读数标注窗口（时点/本期/累计），禁止混窗口比较。

---

## 10. 守恒与回放

- 商品守恒：生产、消费、投入、损耗、在途、库存逐商品对账。
- 货币守恒：流通货币 + 发行主体净发行/回笼 = 初始发行 + 运行期发行；普通账户不得为负。
- 人口守恒：出生、死亡、迁移、成员份额逐值守恒。
- 资产守恒：逐 `(industry, asset)` 的 AssetShare quantity 总量在转移/抵押/清算前后不变（只改 owner/operator/kind）。
- 债务守恒：`期末欠额 = 期初欠额 + 真实借入 + 计息 − 实物偿还 − 货币折偿 − 减免`；利息不增加粮/钱总量。
- 确定性：所有排序用稳定 ID；1/4/8 线程、分段推进、分支重放结果一致。
- 每个状态组件同时进 ChangeSet/Codec/反射往返；旧档显式迁移。

---

## 11. 与当前代码的迁移原则

当前代码可复用为底层执行机制：

- `PopulationGroup` / `Membership` → 人口成分；
- `AssetShare` → 生产资料占有与租佃；
- `ProductionUnit` / `ProductionRelation` / `LaborAllocation` → 生产组织执行；
- `GoodsAccount` / `Transfer` / `applyTransfer` → 价值搬运唯一写口；
- `MarketSettlement` / `ShipmentBatch` → 商品市场与运输；
- `ProductionCandidate` → 模式变迁的候选与试产机制；
- `CrisisMonitor` → hex 警告雏形；
- `CommandBus.submitBatch` / `WorldTimeProposal` → 多切片原子提交。

需要新增/重排为第一性状态的：

1. `ProductionMode` + `ClassStructure` + `ClassPosition`；
2. `ClassMembership` / `ClassStanding`（阶层归属与按比例保留）；
3. `ProductionOrganization`（自动生产组织）；
4. 货币发行主体与初始发行记录；
5. `DebtContract`（钱/粮/地/生产资料 + 条款 + 抵押）；
6. `Pledge` + `LiquidationPolicy`；
7. `ModeTransition`；
8. `HexCrisisSignal`/阶层下滑持久状态；
9. 读口按阶层/生产方式的聚合。

迁移顺序建议：

```text
A 生产方式/阶层结构权威化
→ B 人口成分/家户归属重排
→ C 初始发行与货币流通
→ D 租佃/租金/货币工资
→ E 债务合同与清偿
→ F 抵押/清算/阶层下滑
→ G 模式变迁
→ H GM 工具与读口
```

每个阶段必须可编译、可回放、可守恒；不一次性推翻现有切片。

---

## 12. 非目标与后续扩展

- 文化、宗教、教育：后续挂在家户的 `culturalTags`/文化状态上，不在本设计内定稿。
- 农民起义：仅在 hex 警告阈值出现，不生成起义实体/战斗。
- 现代银行、央行、部分准备金、证券、期货、跨币种汇率：不在范围。
- 现代公司、股份公司、跨国公司：不在范围。
- 货币的“储蓄/存款/窖藏”工具可后续扩展；本设计只要求货币总量与流向可对账。

---

## 13. 最小验收场景

1. **模式 → 阶层 → 生产**  
   新建一个 mode，生成其阶层结构；把人口成分放入阶层；只要有劳动和可支配资产，就自动出现对应生产单元；缺资产/投入则出现具名 SHORTAGE，不缺也不静默。

2. **多户同阶层**  
   同一阶层下多个家户，人口成分（年龄/性别）不同，消费/劳动不同；阶层读数是聚合，家户身份稳定。

3. **初始发钱与货币集中**  
   GM 初始化发钱，记录发行；钱通过地租/市场/工资流动，能观察到向上集中和向下回流；总量 = 初始发行 + 运行期发行，普通账户不为负。

4. **借粮/借钱/借地**  
   分别能建立粮债、货币债、以土地/作坊为抵押的债务；利率/条款不同不合并；放贷人真实出粮/出钱。

5. **粮优先偿还**  
   同一债务人同时有粮债与货币债时，实物粮优先偿还；没有价格时货币折偿不得硬折。

6. **还不起 → 阶层下滑 → hex 警告**  
   利息超过 F，欠额净增；触发清算后核心生产资料按比例处置；家户阶层下滑；底层债务累积到阈值发 hex 警告；起义不生成。

7. **模式变迁按比例**  
   旧 mode 切换到新 mode，成员/劳动/资产/债务按比例迁移；可保留原所属比例；旧 unit 退出，新组织生效；全程一条 revision 原子。

---

## 14. 术语表

| 中文 | 英文建议 | 说明 |
|---|---|---|
| 生产方式 | `ProductionMode` | 技术 + 生产关系，生成阶层结构 |
| 阶层结构 | `ClassStructure` | mode 下全部阶层位置的集合 |
| 阶层位置 | `ClassPosition` | 对生产资料、劳动、剩余的固定结构位置 |
| 家户 | `Household` | 稳定身份，承载人口成分与文化扩展 |
| 人口成分 | `PopulationComposition` | 家户内部的年龄/性别/人数明细 |
| 经营者 | `Operator` | 实际组织生产的经济主体；不等于地主、不等于阶层 |
| 生产组织 | `ProductionOrganization` | 阶层/生产方式的自动组织层 |
| 生产资料 | `MeansOfProduction` / `AssetShare` | 土地、工具、作坊、机器等 |
| 债务合同 | `DebtContract` | 连续欠账 + 条款 + 抵押 + 清偿 |
| 质押 | `Pledge` | 债务与资产份额的绑定 |
| 清算政策 | `LiquidationPolicy` | 核心生产资料处置比例/顺序 |
| 阶层归属 | `ClassStanding` | 原所属、当前所属、保留比例 |
| 模式变迁 | `ModeTransition` | 生产方式切换的原子过程 |
| hex 警告 | `HexCrisisSignal` | 债务/阶层/再生产危机信号，不生成起义 |
