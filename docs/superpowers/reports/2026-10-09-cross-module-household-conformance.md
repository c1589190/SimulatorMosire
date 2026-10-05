# 2026-10-09 跨模块家户写回 / Unit 市场参与 / 通用扣除接口 — 只读调查

> 来源：用户 2026-10-09 对 P2 架构的四点追问。本文只做只读核对，不改代码。
> 结论先行：**当前模块边界是刻意设计的 app 协调器模式；它与用户“Unit/Eco 依赖 Social 直接复写”的意图不一致。**
> 另外，政府家户不随 GOV 单位移动是真实缺陷；军队俸禄/税收的通用扣除接口当前不存在。

## 1. Unit 能不能直接参与市场？

### 设计口径（用户）
- Unit 本身不能是市场参与者；Unit 要参与市场，必须挂到经济/社会模块的**家户主体**，由家户账户参与。

### 当前代码
- `MarketSettlement.participantsFor` 最终只生成 **家户参与者**（`HouseholdId` + `ClassRow`），
  Unit 只是被解析到组织者/经营者家户后挂到该家户的参与者上：
  - 单一家户 ⇒ 把 unit 挂到该家户；
  - 查不到单一家户、但有名下劳动家户（集体经营，如家户纺织）⇒ 记
    `MARKET_SUBJECT_COLLECTIVE` WARN，**不把聚合 unit 当市场参与者**；
  - 连劳动家户都没有的合法空壳 unit ⇒ `MARKET_SUBJECT_EMPTY_UNIT` WARN，跳过；
  - 有产能/资产却解析不到家户 ⇒ 具名抛。
- `HouseholdRouting` 的唯一账户主体解析规则：
  `unit operator → EconomicHouseholdResolver → ProductionOrganization.organizer → 劳动配额家户集合（集体）`。

### 是否符合设计
- **大方向符合**：Unit 本身没有账户，不作为市场参与者。
- **不完全符合**：集体经营 unit（如家户纺织）目前只是“具名 WARN + 产出按劳动分给家户”，
  没有把 unit 显式挂到一个（或一组）可参与市场的家户主体上。
  若按“Unit 必须挂到家户主体”的严格口径，集体 unit 也需要一个明确的组织者家户或一组主家户作为账户主体，
  而不是靠 WARN 跳过。

## 2. 跨模块对家户的修改目前怎么做？

### 当前依赖硬边界
- `simos-unit` 只声明 `simos-social-api`；pom enforcer **明确禁止** `io.mosire:simos-social`：
  `UnitSimos 与 SocialSimos 实现互不依赖（spec §3.1 / G8）`。
- `simos-economy` 同样只声明 `simos-social-api`；pom enforcer **明确禁止** `io.mosire:simos-social`。
- `simos-gov` / `simos-app` 才声明 `simos-social` 实现（gov 是下游，app 是组合根）。

### 当前跨模块写路径
跨切片修改不在 Unit/Economy 里做，而是在 **app 组合根**：

- `PopulationEconomyTimeParticipant` 是唯一同时看得见 social + economy + actor + unit + gov + map 的推进参与者；
- 它调用 app 侧的协调器/投影器：
  - `HouseholdUnitConsistency`：校核并按 `Unit.households` 单向把 Social 家户位置同步为 `UNIT(unitId)`；
  - `HouseholdClassRowProjection`：按 Social 家户重投影 ClassRow；
  - `GovernmentHouseholdWiring`：校验 GOV 家户 ↔ ClassRow ↔ Government.treasury 闭环；
  - `PopulationDynamics`：月度出生/死亡改 Social 份额，再把同一份变化回写 Economy 行/劳动配额/账户；
- 最终结果折成 `WorldTimeProposal`（SocialChangeSet/EconomyChangeSet/...）交 Core 落 revision。

### 是否符合设计
- **不符合用户“Unit/Eco 依赖 Social 直接复写”的意图。**
- 当前是刻意的反向设计：领域模块只依赖契约层（`social-api`），跨模块写集中在 app，
  以满足铁律 3（领域模块只拥有自己的数据）和模块依赖 enforcer。
- 若改成 Unit/Eco 直接依赖 `simos-social` 并复写家户状态，需要：
  1. 放松/改写 pom enforcer 与 AGENTS 模块边界；
  2. 处理 `social → economy-api` 与 `unit/economy → social` 可能形成的依赖环；
  3. 保证直接复写仍走同一 revision/ChangeSet 口径，不能出现第二个写者；
  4. 重新审视“app 是唯一组合根”的定位。
- 这是架构级裁定，不能由实现 Agent 自行改；需要用户明确选择“保持 app 协调器”还是“改为直接依赖 Social”。

## 3. 政府家户为什么不跟随 GOV 单位移动？

### 当前事实
- `GovCreateOfficePlan` / `ProvinceApplyPlan` 建政府家户时，位置写的是 GOV 单位**当刻位置的 HEX**：
  `location = HEX(unit 的 at)`，不是 `UNIT(unitId)`。
- 政府家户存在 `GovFormation.households` 里，**不在** `Unit.households` 里。
- `unit.PlaceAt` / `PlaceAtHandler` 只改 unit 的 position；没有同步 Social 家户位置。
- `GovernmentHouseholdWiring` 只校核：
  `GovFormation 里恰一个政府家户 ↔ Social 家户存在 ↔ economy ClassRow 存在 ↔ Government.treasury 指向该家户`，
  **不校核家户位置与 GOV 单位位置是否一致**。
- `HouseholdUnitConsistency` 只按 `Unit.households` 同步 Social 位置；政府家户不在 `Unit.households`，
  因此也不会被同步。

### 结论
- **政府家户不跟随 GOV 单位移动，是当前实现的真实缺陷。**
- 后果：GOV 迁都/行军/换格后，政府家户仍在旧 HEX，国库账户/市场参与位置仍在旧格。
- 可选修法：
  1. 政府家户位置改为 `UNIT(unitId)`，市场参与时从 unit effectivePosition 派生 hex；
  2. 或保留 HEX，但在 `unit.PlaceAt` / 组合工具中同批 `social.SetHouseholdLocation`；
  3. 或由 app 协调器每 tick 把政府家户位置同步为 GOV 单位当刻位置。
  选 1 最符合“家户可挂 UNIT”的既有模型，但要补“UNIT 家户如何参与市场”的位置派生。

## 4. 军队俸禄 / 税收 / 通用库存扣除接口

### 用户口径
- 军队俸禄：军队走到哪，要俸禄就通过工具 + 决策人决策，决策后给下一 tick 记一笔“军队俸禄”为理由的库存扣除。
- 政府税收同理；经济模块应提供**通用扣除接口**：传入 `(家户, 扣除的库存, 扣除理由)` 即可扣除。
- 经济模块不应该自己维护税收机制。

### 当前代码
- 军队俸禄：**没有实现**。`GovDaily` 只读 `GovFormation`，不处理 `ArmyFormation`；没有“军队俸禄”命令/状态。
- 政府日税：`JurisdictionDailyTax`（app 侧）在日循环里计算税基、税率、效率、缺口，
  再直接调用 `AccountSession.commit` 扣家户账户、加政府家户账户。税收规则确实不在 `simos-economy` 核心里，
  但也没有通用扣除命令。
- 现有最接近的通用原语：
  - `actor.AdjustAccounts`：按家户 + 商品/货币**有符号净增量**修改账户，整条原子；
    但它**没有 reason 字段**，且是 `GmOnlyCommand`；
  - `actor.TransferAccounts`：两个账户间转移，带 reason 文本；
  - `AccountSession.commit`：economic 会话内部的唯一落账口，不是公开命令。
- `TransferReason` 是**封闭枚举**（production_output / relation_payment / input_requisition / loan_principal /
  loan_repayment / market_trade / carrier_fee），没有任意“军队俸禄/税收”原因。

### 是否符合设计
- **不符合。** 目前没有“`(household, stock, reason) → deduct`”的通用接口。
- 税收规则目前在 app 侧 `JurisdictionDailyTax`，而不是由“通用扣除接口 + 调用方给 reason”表达；
  `actor.AdjustAccounts` 缺 reason，且不能被决策令直接使用（GM-only）。
- 需要补的后端原型（建议）：
  - 一个通用 `economy.DeductStock` / `economy.AdjustStock` 命令或服务接口，输入家户、商品/货币、数量、reason；
  - 军队俸禄、政府税、行政俸禄等都调用它；
  - 税收“税率/税基/辖区”留在 gov/app 政策层，只通过通用扣除接口落账；
  - reason 词表是否开放/闭集，需另裁定；至少“军队俸禄”“辖区税”等要有具名取值和日志。

## 5. 其他

- “旧档直接报废”按用户口径是**好事，不算问题**；本文不将其列为缺陷。
- 本次只读调查未改任何代码，未跑测试。

---

## 6. 2026-10-09 用户第二轮裁定后的追加调查

> 用户口径：Social 管人员流动；经济、军事、国家都会导致人员流动；**没必要给家户单独安排一个 API**，
> 后面大量包可以直接用 Social 模块；旧框架不要设死，调查取证默认可进行设计修改。

### 6.1 直接依赖 Social 的可行性（依赖图实测）

- `simos-social` 当前依赖：`simos-util`、`simos-map`、`simos-calendar`、`simos-social-api`、`simos-economy-api`。
- `simos-social` main 的 import 分布：self 164、util 116、map 54、calendar 8、economy-api 2；**不 import unit/economy/actor 实现**。
- `simos-unit` 当前只 import `social.api.id.HouseholdId` / `GovernmentHouseholds` 等 13 处；
  `simos-economy` import social-api 86 处（HouseholdId/PeopleLotId/GovernmentHouseholds）。
- `simos-gov` **已经依赖并使用** `simos-social` 实现（`SocialData`/`SocialCity`）；
  `simos-sd` 也已在 main import `social.SocialData`（它的 pom 未禁 social）。
- enforcer 禁令：`simos-unit` 禁 `simos-social`；`simos-economy` 禁 `simos-social`；`simos-army` 禁
  `simos-economy`/`economy-api`/`actor`/`actor-api`/`social`；`simos-social` 自己禁 unit/core/economy/actor 实现。

**结论：**
- 从 Maven 依赖图看，`unit/economy → social → economy-api` **不构成与 unit/economy 实现的环**；
  `social → economy-api` 是既有明文允许方向，`unit/economy` 已经用 `economy-api` 的 ID。
- 因此“允许 Unit/Eco 直接依赖 Social 实现、把 `simos-social-api` 并回 Social（或只留 ID 包）”在依赖图上是可行的；
  真要拦的应是 `social → unit/economy 实现` 这条反向边，而不是 unit/economy → social。
- 旧禁令的来源是 M0 时代的模块草图（`simos-social` 的 ban 注释自己写着“本表停在 M0，此后新增模块从未回填”），
  **不是不可推翻的天花板**；按用户最新设计，应把它降为历史约束，重新评估。

### 6.2 但“直接依赖”不等于“handler 能直接写另一模块”

CommandBus 实测：

- `HandlerOutcome.Applied(ChangeSet)` 返回**一个** ChangeSet；`CommandBus` 用命令类型推导出**一个** namespace
  （`CommandBus.namespaceOf(type)`，`:743`/`:846`），再包成单 namespace 的 `WorldChangeSet`。
- 所以一个 `unit.*` / `economy.*` handler **不能在同一条命令里直接返回 Social 的 ChangeSet**；
  同理一个 `social.*` handler 也不能返回 Unit/Economy 的 ChangeSet。
- 当前跨模块原子写只有两条路：
  1. **app 组合工具**用 `CommandBus.submitBatch` 把多 namespace 命令打成一条 revision（现状）；
  2. **TimeParticipant** 在推进时提案多个模块的变更（`WorldTimeProposal`，现状 `PopulationEconomyTimeParticipant`）。

⇒ 若要让 Unit/Eco/Gov/Army 直接调用 Social 写家户，需要先决定：
- 保持“命令仍是单 namespace + app/batch 组合”，只是允许 handler 内部 import `simos-social` 的**纯函数**做推导；
  还是
- 新增“多 namespace 命令/组合命令”原语，让一个 handler 能提交多个模块 ChangeSet。
这是个真正的架构选择，不能只改 pom ban。

### 6.3 人员流动矩阵（现有写口）

| 事件 | 发起域 | 当前谁写 Social | 现状 |
|---|---|---|---|
| 创世人口/家户 | app seed | `PopulationSeeder` → `social.SeedGroups` | 已是 Social 命令 |
| 出生/死亡/压力 | Social | `PopulationDynamics` | Social 内 |
| 家户位置、成员增删、成员转移 | Social/GM | `HouseholdBook.*` + social handlers | Social 内 |
| 经济迁移/城市化 | Economy/app | `PopulationEconomyTimeParticipant` 直接构造 `SocialData`（`applyDailyStress`、`planMigrations` 等） | **app 协调器直接改 Social 状态** |
| 模式变迁/阶层转移 | Economy | 目前多只改 Economy 行/配额/资产/账户，Social 成员写回不完整 | 缺口 |
| 征兵/入编/退伍/编制 | Unit/app | `RaiseUnitPlan` 用 `HouseholdBook.create/transferMembers`；`unit.RecruitStaff` 只改 roster | 组合工具在 app；Unit 自己不写 Social |
| 军队移动/单位家户容纳 | Unit | `Unit.households` 列表；位置由 `HouseholdUnitConsistency` app 侧同步 | 依赖 app 校核/同步 |
| 政府家户/领导层/军官小家户 | Gov/Army/app | `GovCreateOfficePlan`、`ProvinceApplyPlan` 调 `social.CreateHousehold` | app 组合工具 |
| 战斗伤亡 | Army/sd | 目前改 unit 装备/人力（manpower 已退役）；Social 成员扣减未完整接 | 缺口 |
| 征服/迁都/区划/外交导致迁移 | Nation/sd/map/app | `MoveCapitalPlan` 目前不搬人口；区划下游重算未做 | 缺口 |

**结论：** 当前 Social 是人员事实的持有者，但跨域写回高度依赖 app 协调器；经济出生/死亡、
Unit 征兵/移动、政府/军队人员变化都没有统一的“直连 Social”路径。

### 6.4 通用库存扣除接口（用户已裁定“加上”）

当前事实：

- 账户真值仍在 actor 切片：`GoodsAccount(household, balances/money/frozen)`（P2-A 已把键改成 `HouseholdId`）。
- `actor.AdjustAccounts`：家户商品/货币有符号净增量、整条原子、走 `AvailableStock`；
  但**没有 reason 字段**，且是 `GmOnlyCommand`。
- `actor.TransferAccounts`：两个账户间转移，有 reason 文本。
- `AccountSession.commit`：economy 结算会话内部唯一落账口，不是公开命令。
- `TransferReason` 是封闭枚举，没有“军队俸禄/辖区税/行政俸禄”等 reason。
- `JurisdictionDailyTax`（app）直接 `AccountSession.commit` 扣家户/加政府家户；
  `GovDaily` 用 `PaymentOracle` 处理行政俸禄；军队俸禄不存在。

**设计建议（待用户最终拍板）：**

1. 新增通用扣减契约，建议名 `economy.DeductStock` 或 `actor.DeductStock`；最小字段：
   `household`、`goods`/`money`（二选一或同时）、`amount > 0`、`reason`、可选 `detail/source`；
   数量为正表示扣减（不是有符号增量）。
2. 成功 = 一条 revision 内的账户负增量 + 审计日志；失败 = 余额不足/冻结不足/家户不存在/account 不存在，
   全部具名拒；不静默扣 0。
3. reason 用**封闭枚举 + 自由 detail**：至少 `MILITARY_SALARY`、`JURISDICTION_TAX`、`ADMIN_UPKEEP`；
   新增 reason 是显式代码改动，不允许随便塞字符串。
4. 落点建议在 **economy-api 契约 + economy/actor 实现**，但注意：账户状态在 actor 切片，
   单 namespace handler 不能同时写 Social/Economy；因此要么
   - 做成 `actor.*` 命令（改 actor 账，reason 只是审计），要么
   - 做成 app 组合工具（同批调用 `actor.AdjustAccounts`/`TransferAccounts` + 需要的 economy/social 命令）。
5. 税收/俸禄接入：
   - 税收保留“税率/税基/辖区”政策计算，但落账统一调通用扣减，不再由 `JurisdictionDailyTax` 直接碰 `AccountSession`；
   - 军队俸禄：决策/令产生一条“下 tick 扣俸禄”的计划（可用 `sd` effect/命令 + 显式 reason），
     下一 tick app/economy participant 读计划，调通用扣减；不足记 shortfall/信号。
6. 这条与“经济模块不自己维护税收机制”一致：economy 只提供通用扣除原语；
   税收政策/军俸政策在 gov/app/sd 层。

### 6.5 结论表

| 用户点 | 当前是否符合 | 建议 |
|---|---|---|
| Unit 不能直接做市场参与者，必须挂家户 | 大方向符合；集体 unit 仍 WARN 跳过 | 给集体 unit 明确组织者家户/主家户账户 |
| Unit/Eco 直接依赖 Social 并写家户 | **不符合**（pom 禁、app 协调器模式） | 推翻旧禁令，允许直接依赖；同时决定多 namespace 写口 |
| GOV 家户跟随 GOV 单位 | **不符合**（位置固定在创建时 HEX） | 改为 UNIT 家户 + 位置投影，或在 PlaceAt/协调器同步 |
| 通用 `(household, stock, reason)` 扣除 | **不存在** | 新增通用扣减契约；税/军俸只给 reason 与政策 |
