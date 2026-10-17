# D-032 共享生产定义形状冻结（D1，2026-10-17）

> **性质**：约束设计书的 **D1 分册**——只冻结"C0/C1/G 共用的生产定义形状与所有权"，不实现合作分配/工资/资产占用（它们分别等 D2/D3/D4）。
> **效力**：重规划 v2 已冻结（`2026-10-17-d032-production-line-replan-v2-review.md`）；本分册是 C0 派单前置。**仅冻结本分册不代表可派 C/W/A 全部实现**；C0 之外仍须对应设计书。
> **输入**：用户设计原稿 D-032/U13–U23（逐字，§7 附录；U22 为执行优先级、U23 为工资链三项裁决）；重规划 v2 §1/§4；当前代码（`IndustryDefinitionView`/`ProductionModeDefinitionView`/`ProductionData`/`ProductionDefinitionPayloads`/`ProductionChangeSet`/`ProductionCodec`）。
> **边界（审核已定）**：经验归一化 = **可配置工程默认**（不得写成用户唯一公式）；角色产品份额 = **直接产品份额**（不以劳动为条件，U15）；工资 = 地主报价+逐 tick 调价+直接结算（U17–U21，D3）；资产 = 现有权益/占用上表达（不另建土地账，D4）；R2 仍具名未修。
>
> **2026-10-17 D2 局部更正注**：后续 `docs/superpowers/specs/2026-10-17-d032-cooperative-production-design.md` §5.1 把 `ProductionRoleId` 的落点改为 `simos-economy-api`（否则 `HouseholdLaborCommitment` 无法携带角色而不形成 Maven 环）；§7.1 补充 `MONEY_WAGE ⇒ share=0`、`PRODUCT_SHARE ⇒ share>0` 的交叉校验；§9.1 固定 `NORMALIZED_PARTICIPATION` 的实际公式为 `laborMilli × 1000 / householdLaborBudgetMilli`（不以人口或 `standardLaborMilliPerCycle` 作经验分母；后者 0 仍表示未定）；§8.3 明确 `UNALLOCATED` = 不进入角色池、但为守恒归 organizer residual，不是无主池；§6 扩展执行请求的逐参与户属性/劳动预算/管理劳动输入；并统一 `laborMilli`/`standardLaborMilliPerCycle`/`householdLaborBudgetMilli` 为**毫小时**口径（旧“千分劳动·日”注释作废）。D1 其余冻结形状不变；C0 落形时以 D2 这些条目为准。
>
> **2026-10-17 U23 / 裁决列局部更正注**：用户 U23（欠薪零息；按下一 tick 生存条件裁决不从业；抵物折价可低于 bid 且可配置）与 D3 预审裁决列 A1–A5/B1–B4 对 D1 的**唯一形状影响**是 `RolePayment.MONEY_WAGE` 必须携带可配置抵物折价策略；已就地补在 §2.4，并同步 §6/§8。本注**不删除**上面的历史冻结内容，只标注当前有效口径：
> - **抵物折价策略归 D1/production 定义面**（§2.4 `BarterDiscountPolicy`：参考价仅 `LOCAL_BID`/`LOCAL_ASK` + 折价系数，可 <1）；**执行/估值归 economy/D3 §6.4**；production 不读市场/钱包。
> - **工资债利率不在本册**：归 economy/D3（U23 已定 0‰，不是工程默认）。
> - **`allowIdle` 已撤回**：本册不写 `allowIdle`、不写 `householdLaborChoices`、不写“缺行默认 true/false”；不从业资格见 D3 §2.5.1。
> - 其余 D1 冻结形状（角色/份额/经验/资格/升级/`AssetKind`/载荷）**不变**。
>
> **2026-10-17 第三次定点修正注（N-07，当前有效）**：§7 的 U14/U15 blockquote 已去掉控制方括注，用户原文逐字保留；控制方解释移到引文外。
>
> **2026-10-17 D1–D4 独立复核局部修正注（R-06/R-07/R-13/R-14/R-17，当前有效）**：本册按 `2026-10-17-d032-d1-d4-design-review.md` 做最小修正：§7 补 U22/U23 用户原话；§2.1 当前包名改为 `simos-economy-api`（顶部历史变更注保留）；D-I8/§2.2 的劳动量纲统一为毫小时（1 小时 = 1000 毫小时）；§2.6 的 `UNALLOCATED` 明确归 organizer residual；§2.7 增列 C0-A 的 `AssetKind` 词表与旧引用写面；文末补未实现/未编译/未验证声明。下列正文中与本节冲突的旧句以本节和就地标注为准；旧文字保留为历史 provenance。

## §1 目标与总不变量

**目标**：让"一个生产方案"在 production 内成为**唯一技术定义面**，能表达用户 U1/U4/U13/U14/U15 要求：技术升级方案、角色/阶层参与、角色产品份额、每角色经验量可配置、资格按位置、支付物/工资义务、区域覆盖；实例只引用方案，economy 只持有实际参与关系/分配/收付。

**形状不变量（D-I）**：
- **D-I1 唯一权威**：方案定义只存 `ProductionData.industries`（`IndustryDefinitionView`）；economy/app 不得持有可独立修改的第二份定义/数值（沿用 I1/I4）。
- **D-I2 往返**：`apply(changeSet, base)` 逐字段重建 target（铁律 5）；新增组件进 ChangeSet/codec/snapshot/往返守卫。
- **D-I3 角色稳定**：`ProductionRoleId` 是稳定身份；方案内不重复；引用不存在角色的条件/经验/支付配置 ⇒ fail-closed（构造期/解析期拒绝，不默认）。
- **D-I4 份额守恒**：角色产品份额逐角色千分比、**合计 ≤ 1000**；余数/未进入角色池的处理规则显式（缺省：`UNALLOCATED` = 不进入角色池，最终按 D2 §8.3 全部归 organizer residual，并给具名读数；不是留在无主池）；**不以劳动为分配条件**。
- **D-I5 经验独立**：同一经验条（`ExperienceTrackId`）可被多角色共用；每个 (家户 × 经验条) 数值仍独立（现有 `ProductionData.householdExperience` 不变）；每角色增长系数/基数是配置。
- **D-I6 资格 fail-closed**：角色资格/采用者资格只声明"要什么、门槛"，属性值由调用方传入（U5）；缺失/未知 ⇒ 不满足并具名拒绝；不得因"没配"而放行。
- **D-I7 一次物理生产**：多户多角色只算**一份**总产出；方案定义的 I/O 是实例级，不按角色复制配方。
- **D-I8 区域/规模口径**：`scale` 单位 = **1 区域当量**；`laborPerUnit` = **毫小时/区域**（如 200 = 0.2 小时/区域 = 1 小时覆盖 5 区域；不得再读成“千分劳动/区域”）；`outputPerUnit` = 商品单位/区域；`capacityPerUnit` = 每区域所需资产。**不新增"区域"字段**；现有毫单位已可表达 U1 例。
- **D-I9 归属**：定义（角色/份额/经验/资格/支付/升级元数据）归 production；**实际参与关系、名义/实际投入、估值、分配账、收付**归 economy；**账户变更**只经原所有者变更集；production 不写账户/人口。
- **D-I10 不兼容旧档**：形状变更按 Alpha 直接改；旧档不迁移（"读不回来"不是缺陷），唯一要求 = 同一版本内自洽（往返）。

## §2 类型形状（冻结；C0 按此落形）

### 2.1 `ProductionRoleId`（新，`simos-economy-api`）
稳定 ID（非空白字符串包装；不绑定 hex/方案）。**当前有效落点 = `simos-economy-api/.../api/production/ProductionRoleId.java`**；顶部历史注记录的 `production.contract` 是旧稿，已作废，不得同时保留两个有效包名。与 economy 的 `ClassPositionId`/`SocialClassId` 是**不同东西**：角色是方案内位置，阶层是社会经济分类。

### 2.2 `ProductionRoleDefinition`（新，production.contract）
```
record ProductionRoleDefinition(
    ProductionRoleId id,                       // 稳定；方案内唯一
    String name,                               // 非空白
    Optional<SocialClassId> socialClass,       // 可缺：该角色期望对应的阶层（仅元数据/选择参考，不因此自动获利）
    long sharePerMille,                        // 角色产品份额（千分比；0..1000；方案内合计 ≤1000）
    IntraRoleSplitRule intraRoleSplit,         // 同角色多户拆分规则（见 2.3）
    long standardLaborMilliPerCycle,           // 标准劳动量（毫小时/周期；用于份数归一化/默认权重；0 = 未定）
    Optional<ExperienceGrowth> experienceGrowth,// 可缺 = 该角色不增长经验（如纯收租默认）
    List<ProductionAvailabilityCondition> conditions, // 该角色资格条件（可空 list；fail-closed）
    RolePayment payment)                       // 该角色的支付形态（见 2.4）
```
校验：`sharePerMille ∈ [0,1000]`、`standardLaborMilliPerCycle ≥ 0`、`conditions` 无 null、`experienceGrowth` 非 null record、`payment` 非 null；`roles` 列表非空（纯单户方案也要有一个"经营者"角色）、id 唯一、保序不可变。

### 2.3 `IntraRoleSplitRule`（新，枚举 + 可选权重表）
```
INTRA_ROLE_SPLIT: BY_RECOGNIZED_LABOR | BY_RIGHTS | EQUAL | CONFIGURED_WEIGHTS
```
- `BY_RECOGNIZED_LABOR`：按经济提供的"该角色内各户实际认可劳动"比例（分母=Σ；0 劳动户不分；全 0 ⇒ 该角色池留未分配，给具名读数）。
- `BY_RIGHTS`：按经济提供的该角色内各户权益量（如出租土地份额；由 economy 计算传入）。
- `EQUAL`：按参与户数均分（最大余数法，稳定 id 破平）。
- `CONFIGURED_WEIGHTS`：定义自带 `Map<HouseholdId, Long>`？**不允许**（定义不写实际 HouseholdId，见 D-I9）⇒ 落地为"由 economy 传权重表"；本枚举只声明**规则种类**。
> 默认工程建议（可配置、非用户已定）：劳动型角色 `BY_RECOGNIZED_LABOR`；收租型角色 `BY_RIGHTS`；其余 `EQUAL`。**具体规则是每角色配置**，不写死一种全局公式。

### 2.4 `RolePayment`（新，sealed）
```
PRODUCT_SHARE
    // 从可分配实物池按 2.2 份额取得（佃农/地主等）

MONEY_WAGE {
    currency = LOCAL_MARKET_AREA,     // 当地市场区货币（U15/U16/U21；报价/结算在 economy，D3）
    barterDiscount: BarterDiscountPolicy   // U23：可配置抵物折价策略（见下）
}
```

```java
record BarterDiscountPolicy(
    BarterReferencePrice referencePrice,   // 参考价类型：LOCAL_BID（默认）| LOCAL_ASK；只有这两种
    long discountPerMille)                 // 折价系数；0..1000+，可 <1000（即 <1）
```

**契约语义（U23 用户已定 + 控制方形状细化）**：
- **参考价类型只有 `LOCAL_BID` / `LOCAL_ASK` 两种**，分别映射现有 `MarketTable.hasPrice` + `bidPriceOf` / `askPriceOf`；不新增“最后成交价”或其他市场事实/状态。
- 抵物单位折价 = **当地所选参考价格 × `discountPerMille` / 1000**；系数允许小于 1000（如 500 = 参考价五折），**不得把 1 写死**。
- **production 只声明规则/纯配置**（参考价仅 `LOCAL_BID`/`LOCAL_ASK` + 系数）：不读市场、不读钱包、不读他户状态；**economy 读当地市场价并执行估值/抵付**（D3 §6.4）。production 不因此产生对 economy 实现的反向依赖。
- **单一来源**：策略只存在于方案定义；economy 不得保存第二份可独立修改的折价配置，也不得在 economy 侧另定一个默认策略。
- 付款顺序仍是用户已定的 U21（对应货币 → 可支付活动库存按数量降序 → 剩余成债）；**定义面不重复配置顺序**，只声明“工资形态 + 抵物折价策略”。
- 缺价、折价后为 0、缺必要币种事实 ⇒ 该商品**不可用于本次抵付**，剩余债务保留（D3 §6.3/§6.4）。
- 默认工程值（可配置、**非用户已定**）：`referencePrice=LOCAL_BID`、`discountPerMille=1000`（= 现行 bid ×1 行为）；U23 只要求“可更低、可配置”，具体数值由 seed/命令给。
- 校验：`referencePrice` 非 null、`discountPerMille ≥ 0`；未知枚举/负系数 ⇒ 解析期具名拒绝（不得静默按 1000）。
- 本册**不写**工资债利率（归 economy/D3：U23 已定 0‰）；`allowIdle` / `householdLaborChoices` **已撤回**，本册不写其规则、状态或判据。

### 2.5 `ExperienceGrowth`（新，production.contract）
```
record ExperienceGrowth(
    ExperienceTrackId track,        // 增长到哪条经验条（同一方案多角色可指向同一 track = U13"算同一个"）
    GrowthBasis basis,              // ACTUAL_LABOR | NORMALIZED_PARTICIPATION（可配置工程默认；见 §5）
    long coefficientPerMille,       // 每角色不同的增长系数（0 = 不涨）
    long maxPerTickValue,           // 单 tick 增长上限（0 = 不设额外上限；防异常配置）
    boolean countsManagement)       // 管理投入是否计入（默认 false；真值需 economy 提供管理投入事实）
```
校验：`coefficientPerMille ≥ 0`、`maxPerTickValue ≥ 0`；`basis == NORMALIZED_PARTICIPATION` 时归一化分母口径见 §5。
**增长仍写现有 `householdExperience`（主值+余数）**；`ACTUAL_LABOR` 以"该角色内该户实际认可劳动"为输入，`NORMALIZED_PARTICIPATION` = 实际劳动 ÷ 本户参与强度（人口/可用劳动，口径由 economy 传入，不在 production 猜人口）。

### 2.6 `IndustryDefinitionView` 新增/变更字段（冻结）
保留现有 18 组件；**新增**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `roles` | `List<ProductionRoleDefinition>` | 非空、id 唯一；纯单户方案 = 1 个"经营者"角色（份额 1000、支付=PRODUCT_SHARE） |
| `roleShareRemainderRule` | `RoleShareRemainder`（新枚举：`UNALLOCATED`（默认）\| `TO_LAST_ROLE`） | 合计 <1000 时余数处理；`UNALLOCATED` = 不进入角色池，最终按 D2 §8.3 全部归 organizer residual，并给具名读数（不得留在无主池） |
| `upgradesTo` | `List<IndustryId>` | 技术升级元数据（可空）：本方案可升级到的方案 id（仅登记/读口/选择参考；不产生行为；未知 id 在使用处 fail-closed） |
| `mode` | `Optional<ProductionModeId>` | 可缺：绑定的生产方式/阶层结构（元数据；不改变现有 mode 定义面） |

`availability` 保留语义 = **采用者/经营者资格**（现有 R3 路径继续使用）；**角色资格**用 `roles[].conditions`。两者都 fail-closed。
`experience`（`ProductionExperienceUse`）保留 = 效率公式 a/b 条 + 解锁门槛；角色增长条目的 track 可与 a/b 相同或另加；`tracksToGrow` 取并集（去重有序）。

### 2.7 `AssetKind`（actor-api）扩类
**冻结**：`enum AssetKind { LAND, CATTLE, SMALL_TOOL, LARGE_TOOL, WORKSHOP, MACHINE, SHIP }`。
- `TOOL` 退役（Alpha：直接替换所有引用/播种/测试期望；不做别名/兼容位）。
- `CATTLE` 保留（U1 说牲畜暂不做出来，但词表保留；零份额零配方仍合法）。
- 影响面：`EconomySeeder`、`OwnershipStake` 数据、配方/容量表、goldens、测试；由 **C0-A** 一次改齐（D2 §13 owner 表）：写面 = `simos-actor-api/.../AssetKind.java` + **`rg -l 'AssetKind\.TOOL|"TOOL"' simos-*/src/main` 命中的全部 main 文件**（以扫描清单为准，含 production 载荷/seed、economy 的资产/模板/迁移/结算引用、app `EconomySeeder`、util 词表等）；测试引用归 C4 test owner。C0-A 必须交 `TOOL` 旧引用的 `rg` 清零清单（main 面）与 `SMALL_TOOL`/`LARGE_TOOL` 各自可往返证据。C0-A 先于 C0，且不拥有 `ProductionRoleId`/角色契约。
> **历史工程提案（已作废，不再有效）**：曾写“若认为不需要区分大小农具，可只加 `LARGE_TOOL` 并保留 `TOOL`”；该提案与 U23 后的当前冻结词表冲突，当前只保留 `SMALL_TOOL`/`LARGE_TOOL`，`TOOL` 出现即拒。

### 2.8 载荷（GM 命令）形状
`production.UpsertIndustry` 与 `production.Seed.industries[]` 的 JSON 增加：
```
roles: [ { id, name, socialClass?, sharePerMille, intraRoleSplit, standardLaborMilliPerCycle,
           experienceGrowth?: { track, basis, coefficientPerMille, maxPerTickValue?, countsManagement? },
           conditions?: [ ...现有 availability 条件的 JSON 形制... ],
           payment: "product_share"
                  | { kind: "money_wage", currency?: "LOCAL_MARKET_AREA",
                      barterDiscount?: { referencePrice?: "local_bid" | "local_ask",
                                         discountPerMille?: 1000 } } } ],
roleShareRemainderRule?: "unallocated" | "to_last_role",
upgradesTo?: [ "planId", ... ],
mode?: "modeId",
```
- 缺省：`roles` 缺省 ⇒ **单角色**（id=`operator`，name="经营者"，sharePerMille=1000，intraRoleSplit=EQUAL，payment=product_share，growth 缺省、conditions 空）= 与今天单户方案逐值等价的行为（D-I10 同版本内自洽，旧 JSON 仍需补 roles 或按本缺省解析——**本批允许缺省兼容"单户旧载荷"**，因为这是同一版本内的"未配置角色"形态，不是旧档迁移）。
- `payment.money_wage.barterDiscount` 缺省 = `{ referencePrice: local_bid, discountPerMille: 1000 }`（= 现行 bid×1 行为）；给负系数/未知参考价 ⇒ 解析期具名拒绝，不得静默按 1000。
- 未知键/未知角色引用/份额合计 >1000/重复 id ⇒ 解析期具名拒绝。
- `mode`/`upgradesTo` 只校验非空文本；跨定义引用完整性在使用处 fail-closed（定义可先于被引用方案存在）。
- `AssetKind` 词表解析：未知值拒绝；`TOOL` 出现即具名拒（"已退役"）。

### 2.9 实例与执行契约的最小变更（冻结；C1 落形）
- `ProductionProcess`（实例）**不加**角色/份额字段（实例只引用方案 + 地点 + 进度 + 台账）；实际参与关系归 economy。
- `ProductionExecutionRequest.laborContributionsMilli` 由 `Map<HouseholdId,Long>` 变为 **`List<ParticipantContribution(HouseholdId household, ProductionRoleId role, long laborMilli)>`**（保序不可变；`(household,role)` 唯一；逐项 >0；同一户可多角色）。`totalLaborMilli()` = Σ。**这是 C1 的契约变更点；F2/T9 的在飞实现按旧形编译，C1 一次改齐并同步测试（测试 owner）**。
- `ProductionReceipt.cycleContributions` 保留逐 (家户×角色) 明细（`CycleContribution` 增 role）；经验增长按 `roles[].experienceGrowth` 逐 (家户 × track × role) 计算后归并到 `(household, track)`（同一条只记一次、贡献不重复计）。
- `probe` 入参不变（单户条件 + 方案 + 占用 + 余数 + 属性）；probe 回执**不**含份额（分配是 economy 的事）。
- 纯分配函数（**规则解释唯一拼写点**）：`production.contract.RoleDistribution.allocate(definition, participants, recognizedLabor, rightsWeights, distributablePools)` → 逐户逐商品的分配结果 + 具名未分配读数；**只做算术，不写状态/账户**；economy 调它并按结果落账。C1 实现，独立测试 owner 验（含余数守恒/最大余数法确定性）。

## §3 状态/ChangeSet/codec/往返（C0 落形要求）

1. `ProductionData.industries` 值类型升级为上述 `IndustryDefinitionView`（新组件）；`ProductionChangeSet` 的 industries 维按**新值** diff；`ProductionCodec` 读写全部新字段；`ProductionSnapshots`/`ProductionSnapshot` 同步。
2. **往返**：`apply(changeSet, base)` 逐字段重建 target（含 roles/份额/增长/条件/支付/升级/mode）；新增组件必须有往返测试（独立测试 owner）。
3. **注册**：`registerPlan` 复用 `DefinitionRegistration` 的合并规则（同 id 同值幂等；同 id 不同值冲突具名拒）；**不得**在 economy 侧合成定义（只读或请求）。
4. **种子/创世**：`EconomySeeder` 的产业载荷改为新形状 + 新 `AssetKind`；`production.Seed`/`UpsertIndustry` 工具 schema 提示同步。
5. **读口**：`ProductionDefinitionLookup` 仍返回 `IndustryDefinitionView`；economy 的读路径只读不复制。

## §4 消费者影响面（C0/C1 必改，逐项清单）

| 消费者 | 变更 |
|---|---|
| `ProductionRuntimeEngine`（probe/execute/executeDay/experience） | 执行入参 participants；经验增长按角色；`tracksToGrow` 并集；其余物理公式不变 |
| `ProductionData` / `ProductionExperience` | 角色增长累加与归并（(household, track) 唯一）；不改精度/余数机制 |
| `EconomyEntrySettlement` / `EconomyEnterpriseSettlement` / `ModeMigrationSettlement` | 创建实例/采用方案时参与者=当前单户"经营者"角色；读定义新字段；不得合成第二定义 |
| `ExpectedProfitBook` / `LaborQueueBook` / `LaborQueueSettlement`（R1/F2 已改 probe 入口） | 预计所得按"本户在某角色"计算（C2，D2 设计）；本分册只冻结形状，不要求本批改 |
| app 桥/读口/工具 schema | 新字段透传/展示；`AssetKind` 词表同步 |
| 测试 | 由独立 test owner 按新形状改写；goldens 允许按新口径重算 |

## §5 经验增长的默认口径（工程默认，非用户唯一公式）

- **默认**：`basis=NORMALIZED_PARTICIPATION`，分母 = 本户该角色**实际认可劳动 ÷ 本户参与强度**（参与强度由 economy 传入：可用劳动/人口，二选一由 D2 固定）；再 × `coefficientPerMille`。
- **可配置**：角色可改 `ACTUAL_LABOR`（绝对劳动累加）或改系数/上限；不得把默认写成"用户已裁定大户小户必同速/必不同速"。
- 被动收租角色默认 `experienceGrowth=empty`（不增长）；管理投入默认不计（`countsManagement=false`），需要时由 economy 提供事实。
- 同一条 track 多角色 ⇒ 都累加到同一 (household, track)，贡献去重（同一实际劳动只计一次）。

## §6 验收判据与负向（C0/C1 交付必须带）

**正向**：
1. 用新形状定义"手工农业（单角色）"与"合作农业（地主/中农/佃农三角色，份额 400/400/200）"，往返逐字段重建；`probe/execute` 物理结果与旧单户定义逐值不变（同 I/O 时）。
2. 角色份额合计 1000 时按配置分配（400/400/200 例子作为**配置表达**，不是分配公式）；合计 <1000 的余数给具名未分配读数。
3. 同角色多户按 `intraRoleSplit` 拆分且**不重复领整份**（如 3:2 ⇒ 300/200）；不同角色可同户兼任，但实际劳动/资产不重复计。
4. 每角色 `experienceGrowth` 按配置增长到同一 track（不同系数 ⇒ 不同增量）；`countsManagement` 事实缺省 ⇒ 不计。
5. `AssetKind` 新词表贯通播种/份额/配方/往返；`SMALL_TOOL` 与 `LARGE_TOOL` **各自**经 payload→state→codec→apply 往返且逐字段相等；`TOOL` 出现具名拒（`asset-kind-retired:TOOL`）。
6. `registerPlan` 同 id 同值幂等、不同值冲突具名拒；economy 侧无第二定义写口（扫描）。
7. **U23 抵物折价策略**：`RolePayment.MONEY_WAGE.barterDiscount` 随定义往返（`referencePrice`/`discountPerMille` 逐字段）；`referencePrice=LOCAL_BID` 与 `LOCAL_ASK` 都合法且可读；`discountPerMille=500` 合法；缺省 `LOCAL_BID/1000` 与现行 bid×1 行为逐值一致；production 侧不出现市场/钱包读取。
8. **D1 量纲判据**：`laborPerUnit=200` 即 0.2 小时/区域；`standardLaborMilliPerCycle=1000` 即 1 小时/周期；`1 小时 = 1000 毫小时`。把任一字段按“千分劳动/周期”解释 ⇒ 该判据红。
9. **`UNALLOCATED` 数值例**：三角色份额 `400/400/100`、net=1000 ⇒ 角色池 400/400/100，`unallocated=100` 全部归 organizer residual，逐商品 `loss+shares+residual=gross` 守恒；不得把 100 留在无主池。

**负向（改坏⇒红⇒还原⇒md5）**：删往返字段⇒往返红；角色份额合计>1000⇒构造红；未知角色条件引用⇒解析红；`TOOL` 旧值或 `SMALL_TOOL`/`LARGE_TOOL` 任一不能往返⇒解析/往返红；同角色多户各领整份⇒分配判据红；复用旧 `Map<HouseholdId,Long>` 入参⇒编译/契约红；`discountPerMille<0` 或未知 `BarterReferencePrice` 未拒⇒解析红；新增第三种参考价类型或引用不存在的市场读法⇒文档/契约红；production 读市场/钱包或 economy 另存第二份折价策略⇒模块边界判据红；把工资债利率写进 D1⇒文档验收红；把 `laborPerUnit`/`standardLaborMilliPerCycle` 按“千分劳动”解释⇒§6 P8/P9 红；`UNALLOCATED` 留无主池⇒P9 红。

## §7 用户原话附录（逐字，2026-10-17，摘自设计原稿 D-032）

> **U13**：「——注意到我们之前讨论的生产方式一直围绕小生产而不是多家户合作的大生产，例如农业，单个佃农产业就同时有地主、中农、佃农等阶层位置，我的建议是，不同阶层积累的生产经验算同一个，收益模型则是由生产方式按比例定，具体调查一下，告诉我你认为应该怎么设计，我修改、确认」

> **U14**：「你的建议很合理，因此在单个生产方式中，每一个阶层的家户参与生产，能获得的不同经验量可以是有所区别的，这个也要支持具体配置；以及，分配我建议按照劳动力*系数来算，例如定义农业生产方式，地主投入n，中农投入10n，佃农投入10n，地主的分配系数是40%，中农是40%，佃农是20%，假设当前tick这个地块这个生产方式总产出1000单位，就按400、400、200分给地主、中农、佃农，这样设计合理吗？」

> **U15**：「不是，就是直接的产品分配需求——666，地主来收租子，你和地主说，你没下地干活，你不许拿，你看地主抽不抽你就完了；以及好像没有考虑现金结算问题，使用什么东西支付生产方式也要定义，例如佃农制就是直接分粮食，雇农制则是在农业生产方式的最底下加一档雇农，然后给作为生产组织者的地主加上随着雇农投入劳动力扩大而扩大的货币结算分成，例如雇农提供n劳动力给m当地市场区货币，没有对应货币再用地主库存里其他东西按顺序结，这个具体结算价格应该让地主自主报价、跟着市场区，问题是这里如何实现地主自主报价？思考调查一下」

**控制方说明（非用户原话，放在引文外）**：U14 中“劳动力×系数”的两种旧解释不再作为设计依据；U15 的“货币结算分成”按 D2/重规划已落盘更正理解为**雇主的工资支出义务**，不是地主收入；U15 的产品份额不以下地劳动为条件。

> **U16**：「1 这个估算有算法吗？ 2 我不建议搞这么复杂，最好是算出来劳动价格之后直接给——是否选择进行雇农生产是家户自己的事，给钱是地主的事 3 因此也不用算这么复杂，工资库存给不起直接计成债务就行，有库存就给库存，不设置额外的土地生产资料，被用作再生产的生产资料可以视为权益了，不算做可变卖活动库存」

> **U17**：「地主支付这个环节应该算成地主的生产方式选择——家户在选择生产方式并成为地主之前，必须先根据对应生产方式，算在选用的时候，本轮能雇到人的愿给出劳动力价格，然后再把这个价格计入生产预估花费，算出这个家户选什么生产方式；因此口径应当改成不亏本的最低价——要不是能剥削谁雇雇农啊？这样的体系自洽吗？在经济景气、经济不景气的时候这样的机制分别会产生什么效果」

> **U18**：「最低工资在市场逻辑下为什么不能报0或者很低呢，流民/无产者家户有什么办法吗，它必须选一个生产方式，入不敷出是必然，地主是出价的那一方，重新设计一下」

> **U19**：「从0开始报价那完蛋了，但是我想不到其他好办法；这样，给家户同时加一个允许不从业的设定，如果本tick地主家户报价为0，那么无产者家户算了算，发现没收入还得投入劳动力，如果换不到第二tick的口粮，就算不想罢工也无法投入生产，必须要倒闭有能力选地主的家户加工资，这里就是一个动态的市场计算博弈，博弈随着tick增加而进行，有办法自恰吗？」

> **U20**：「对，可以给一个在招不到人的时候非常快乃至累加的加价速度，招到人之后缓慢增大的降价速度，还有其他需要裁决的问题吗？」

> **U21**：「1 不影响 2 不允许，地主优先给钱，没钱了再按库存最多的东西抵」

> **U22**：「我决定先修bug」

> **U23**：「1 肯定不计 2 任何家户只要条件允许（从事任何可从事的生产方式都没法换取下一tick的生存所需）都可以被裁决为不从业 3 折价可以更低一些，总之是可配置项，注意明确模块边界」

**控制方说明（非用户原话）**：U22 只决定执行优先级（先修 R2 bug），不是对工资三项的裁定，也不授权提交/同步；U23-a 是工资欠款 0‰，U23-b 是下一 tick 生存条件资格（不是 per-household `allowIdle` 开关），U23-c 是抵物折价可配置且可低于 bid。三项均为用户已定，工程公式/接口为控制方默认或契约，不得冒充用户原话。

## §8 未定/后续（不在 D1）

- 分配执行细则（可分配池边界、毛产/费用后池、跨商品处理、余数归属）→ **D2**；工资报价/调价/匹配/结算 → **D3**；资产地点/占用期/统一分配/R2 → **D4**。
- **抵物折价策略形状**归本册 §2.4（production 定义面，参考价仅 `LOCAL_BID`/`LOCAL_ASK`）；**折价执行/估值**与**工资债利率**归 economy/D3（§6.4/§6.5）。D1 不写利率；`allowIdle`/`householdLaborChoices` 已撤回，D1 不写其规则、状态或判据。
- `upgradesTo` 的行为语义（采用引导/UI/统计）→ D2/后续；C0 只登记与往返。
- `mode` 链接的行为语义（迁移/阶层结构解析）→ D2。
- 经验归一化分母的最终选择 → D2 工程默认（本分册 §5 已给可配置默认）。
- 测试由独立 test owner 写/跑；C0 实现 owner 不写测试、不跑测试。

## §9 交付声明

本册只写设计：**未实现、未编译、未跑测试/仿真/门禁**；C0/C0-A/C1/C4/T 的证据另立。任何“形状已冻结”不等于“代码已落形”，也不等于“验收通过”。本册未改 Java/XML/POM/测试，未提交/推送/同步主树。
