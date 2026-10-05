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
