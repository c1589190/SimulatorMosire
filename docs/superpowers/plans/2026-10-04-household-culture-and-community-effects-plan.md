# 家户文化 / 基层组织效果——未来规划（2026-10-04 起）

> **状态：只登记规划，不实现。** 本文件不改变任何代码、不进入任何运行时版本；真正开工时按 AGENTS §一.8
> 先补完整架构设计，再派实现。
>
> 来源：用户 2026-10-04 口述——
> 「不排除后续给单个家户打文化/基层组织效果，例如如果这个家户是穆斯林，那么放贷额度 −90%；
> 只写进规划，目前开发很成功。」
> 以及同轮指出的：「你违反古兰经了知不知道？」——指当前市场信用对所有家户统一收
> `BORROW_RATE_PER_MILLE_PER_CYCLE = 20‰` 利息；宗教/文化规则尚未建模。

## 0. 现在家户存在哪（给后续实现的坐标）

| 数据 | 位置 | 说明 |
|---|---|---|
| 家户行本体 | `simos-economy` → `EconomyData.classes()`：`Map<HouseholdId, ClassRow>` | 人口、劳动、view(hex/residence/stratum)、自然需求、债务引用等 |
| 家户当前阶层位置 | `EconomyData.classStandings()`：`Map<HouseholdId, ClassStanding>` | `householdId + currentPositionId + retainedShares` |
| 家户周期流水 | `EconomyData.flows()`：`Map<HouseholdId, FlowRow>` | 本期所得/消费/借还/出生死亡等 |
| 家户-人口批次份额 | `EconomyData.memberships()`：`Map<MembershipId, Membership>` | ΣMembership == 行人口 |
| 劳动供给/配额 | `EconomyData.laborSupply()`、`allocations()` | 键含批次/家户 actor |
| 债务合同 | `EconomyData.debtContracts()`：`Map<DebtContractId, DebtContract>` | 两端是 `HouseholdId debtor/creditor`（当前唯一信用主体） |
| actor 身份 | `simos-actor` → `ActorData.actors()`：`Map<ActorRef, Actor>` | 家户 actor（`HouseholdActors.of(householdId)`）或组织 actor |
| 真实商品/货币余额 | `ActorData.accounts()`：`Map<GoodsAccountKey, GoodsAccount>`，键 = `(ActorRef, HexCoord)` | 家户/组织的货与钱真值；余额与身份同源 |
| 持久化 | `EconomySnapshot`（状态模块 `"economy"`）→ `EconomyCodec` / `EconomyChangeSet` | 家户表随快照序列化/差分 |
| 播种 | `EconomySeeder.plan` → worldgen 载荷 → `EconomyPayloads` → `EconomyData` | `HouseholdId.ofSeedRole(...)` 生成稳定身份 |

`HouseholdId` 是稳定身份；`CohortKey.view`（hex/residence/stratum）只是投影/读口，不是身份。
**当前没有文化、宗教、民族、基层组织这些字段。**

## 1. 未来目标（草案，待裁定）

给**单个家户**挂“文化 / 基层组织效果”，效果可以改它的经济行为，而不用改家户身份或债务合同端点。

第一批候选效果（用户点名）：

- **放贷额度修正**：例如穆斯林家户 `lendingQuotaModifier = −900‰`（可借头寸 ×10%）；
- **利息规则**：例如穆斯林家户的贷款禁止 `riba`（利息），需要按文化/规则选择合同条款：
  - `qard hasan`（无息贷款）；
  - 利润分成/合伙类合同（mudarabah/musharakah 方向，具体口径要另裁）；
  - 或直接禁止该家户参与有息信用，只允许无息/实物互助。
- **基层组织效果**：行会、教会、村社、互助会等给成员家户加修正（信用、救济、市场准入、迁移、消费等），
  可叠加、可有期限、可由 GM 编辑。

## 2. 设计草案（不在本批实现）

### 2.1 数据形状（候选）

- 新增 `HouseholdCulture` / `HouseholdEffect` 类的**派生/持久组件**（二选一，待架构裁定）：
  - 方案 A：`EconomyData` 新增一张 `Map<HouseholdId, HouseholdTraits>`（文化标签 + 效果列表）；
  - 方案 B：actor 侧 `Actor` 增加文化/组织成员属性，economy 读 actor；
  - 方案 C：基层组织做成 `ActorKind.ORGANIZATION` 实体，家户通过 membership 关联，效果由组织成员身份派生。
- 效果统一表达为**具名、可叠、可读**的修正值（不要散落 if）：
  - 维度：lendingQuotaPerMille、interestPolicy、borrowingQuotaPerMille、consumption、migration、marketAccess…
  - 来源：culture / grassroots organization / 政策 / 事件；
  - 作用域：单个家户、组织成员、同格同文化群体；
  - 期限：永久 / 到 tick / 条件解除；
  - 叠加规则：乘算/加算/取最严（待裁定）。

### 2.2 集成点（实现时必须逐处接，不许在中间层暗改）

- 货币放贷池：`MarketSettlement.CreditPools.build` 的 `lendable` 计算；
- 同格借粮：`EconomySettlement.lendableOf` / `lendDeficitsInHex` 的放贷方余粮；
- 债务条款：`DebtContract.terms`（利息率、计息时机、偿还规则）；需要按家户文化选不同 `DebtTerms`，
  而不是全局 `BORROW_RATE_PER_MILLE_PER_CYCLE`；
- 计息：`EconomySettlement.chargeInterest` / `DebtContractBook.compoundInterest`；
- 读口/日志：`MarketReport.CreditFill.ratePerMille`、`DebtContract.terms`、经济日志 `DEBT_*`/`MARKET_CREDIT_FILL`；
- GM 工具：文化标签、基层组织 membership、效果编辑与读口（后置）。

### 2.3 与现有实现的关系（必须显式处理）

- 当前 `DebtContract` 端点只能是 `HouseholdId`；文化效果只改家户的行为参数，不改合同端点；
- 当前利息是全局统一 `20‰/周期`。文化规则落地后，**同一个市场轮里可能出现“有息合同 + 无息合同”并存**；
  读口/守恒/计息路径都要能按合同条款分别处理；
- 文化不是阶级：`ClassPosition` 继续表达生产关系位置，文化/基层组织是**正交**维度；
- 文化不是信用主体本身：信用主体仍是家户；基层组织若要做主体，另见“组织信用主体”缺口。

## 3. 古兰经 / riba 的当前事实（如实记）

- 当前实现**没有宗教字段**，所以运行结果不区分穆斯林/犹太教徒/其他人；统一收息是“未建模宗教规则”的结果，
  不是系统判定“穆斯林应收息”。
- 一旦文化效果接线，穆斯林家户的有息放贷/借贷必须按用户后续裁定改成无息或利润分成等允许形态；
  在此之前，任何涉及宗教合规性的验收都不能算通过。

## 4. 开放问题（待用户裁定，不先写）

1. 文化/宗教是**家户属性**还是**基层组织成员身份**派生？一个家户能否同时属于多个文化/组织？
2. “放贷额度 −90%”是乘在放贷人可借头寸上，还是乘在借款额度上？是否也影响借入？
3. 无息贷款的具体形态：`qard hasan`（纯无息、到期还本）还是利润分成？坏账/违约怎么处理？
4. 基层组织的效果是否影响救济、税、迁移、消费？范围与叠加规则？
5. 文化效果是否要进 `EconomyData` 变更集/存档，还是纯 GM 瞬态效果？
6. 与 `ClassStanding` / `ProductionOrganization` 的交互：同文化不同阶级是否共享效果？

## 5. 非目标（本规划不做的）

- 本批不改任何信用、利息、放贷代码；
- 不新增宗教/文化字段，不改 `EconomyData`/`DebtContract` 形状；
- 不把文化/基层组织做成信用主体（那是“组织信用主体”的另一个缺口）；
- 不做 GUI/MCP 工具，等设计裁定后再排期。
