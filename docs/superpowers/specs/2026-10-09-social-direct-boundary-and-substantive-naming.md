# 2026-10-09 方案 A：Social 直连边界 / Unit 家户单一列表 / 实质化命名（设计草案，待实现）

> 来源：用户 2026-10-09 裁定：
> ① 选方案 A：`simos-social-api` 可以留着，但后面后端业务模块都应允许大方依赖 `simos-social`；
> ② Unit 里的家户列表才是容纳该 Unit 人员实质的单位；
> ③ 变量/类型名要带其表示实质，不要只写类型名；
> ④ 调查取证默认可改设计，旧边界不设死（见 `AGENTS.md §四.2`）。
> 本文只做设计，不改代码。

## 1. 方案 A：模块边界

### 1.1 目标依赖图

```
util -> map -> calendar
                 ↘
                  social（人员/家户状态 + 人员流动服务）
                 ↗    ↑        ↑        ↑
        economy-api   unit   economy   gov/army/sd
```

- `simos-social-api` **保留**，但降级为“稳定 ID + 只读跨域契约”：
  - `HouseholdId`、`PeopleLotId`、`HouseholdLocation`、`HouseholdLookup`、`PopulationLookup`；
  - 不再承担“唯一允许的跨模块家户接口”角色。
- **允许直接依赖 `simos-social` 的模块**：`simos-unit`、`simos-economy`、`simos-gov`、`simos-army`、
  `simos-sd`、`simos-app`（app 本来就是组合根，`gov/sd` 已在依赖）。
- `simos-core` 继续只做组合/调度，不直接依赖 `simos-social` 实现。
- `simos-social` 继续禁止反向依赖 `unit/economy/actor/sd/core` 实现，防环。

### 1.2 为什么不能零 API

实测依赖环：

- `simos-social` → `simos-economy-api`（用 `CohortKey` / `ResidenceKind` / `SocialClassId`）；
- `simos-economy-api` → `simos-social-api`（用 `HouseholdId` / `PeopleLotId`）。

若把 `social-api` 并进 `social`，就出现 `economy-api → social → economy-api` 的 Maven 环。
⇒ 方案 A 保留 `simos-social-api` 作为**低层身份契约**，但不再作为“家户状态访问的唯一门”。

### 1.3 需要改的 pom/enforcer

- `simos-unit`：移除 `ban simos-social`；依赖从 `simos-social-api` 改为同时依赖 `simos-social-api`（ID）与
  `simos-social`（状态/服务）。
- `simos-economy`：同上；`ban simos-social` 删除，保留 `ban unit/sd/core/app` 等防环。
- `simos-army`：允许 `simos-social` / `simos-economy-api` / `simos-actor-api`；保留禁 `economy`/`actor` 实现。
- `simos-sd`：已有 `simos-social`，补 `simos-economy-api`（如需）。
- `simos-social-api`：描述改为“低层人口/家户身份契约”，不再宣称是唯一接口层。

## 2. 跨模块写口：多模块 ChangeSet

直接依赖 ≠ handler 能直接写另一模块。当前 `CommandHandler` / `HandlerOutcome.Applied` / `CommandBus` 只支持单
namespace ChangeSet。要让 `unit.RecruitStaff`、`economy` 迁移、`gov` 家户变更在一条 revision 内同时写 Social：

- 新增 `HandlerOutcome.AppliedWorld(Map<String, ChangeSet> changes)`；
- `CommandHandler` 增加 `Set<String> targetNamespaces()`；
- `CommandBus` 把多模块变更折进同一条 revision：
  - 一条命令事件，`modules=[unit,social]`；
  - 每个模块的 ChangeSet 仍由该模块完整状态派生（铁律 5）；
  - 模块集合的授权/资源围栏在 `CommandTargets` 上扩展；
- 旧单模块 handler 保持兼容：CommandBus 把它包装成单模块 map；
- TimeParticipant 的 `WorldTimeProposal` 继续服务时间推进；两条路径语义对齐。

改完能实现：

- `Unit` 命令原子“移家户 + 改编制”；
- `Economy` 命令原子“改生产/分配 + 写回 Social 人口份额”；
- `Gov/Army` 命令原子“改政策/编制 + 移家户/扣人口”。

## 3. Unit 家户列表：单一实质列表

### 3.1 现状问题

当前有**两份家户列表**：

- `Unit.households: List<HouseholdId>`：S3a 加的“单位容纳家户”；
- `GovFormation.households: List<HouseholdId>`：P2-C 另加的“官府下辖家户”。

后果：

- 政府家户可以只出现在 `GovFormation.households`，不在 `Unit.households`；
- `HouseholdUnitConsistency` 只同步 `Unit.households`，政府家户不跟随；
- 位置/人员/编制三处事实容易漂。

### 3.2 目标形状

- **`Unit.households` 是唯一“谁在这个 Unit 里”的实质列表**；
- 删除 `GovFormation.households` 组件；
- `GovFormation.householdPosts: Map<HouseholdId, GovernmentHouseholdPost>` 只是角色配置，
  键必须 ⊆ `Unit.households`（现有 `UnitState` 已这么做，改成只依赖 Unit.households）；
- `ArmyFormation.householdDuties` 同理：配置键 ⊆ `Unit.households`；
- 政府家户也放进 `Unit.households`；中央/地方 GOV 单位的 `Unit.households` 恰含一个 `hh-gov-<unitId>`；
- `UnitState` 构造期校验：GOV 单位 `Unit.households` 里恰一个政府家户，且 id 逐字等于 `hh-gov-<unitId>`。

### 3.3 政府家户位置跟随

- 政府家户（以及军官小家户）位置用 `UNIT(unitId)`，不再在创建时钉 HEX；
- 新增 `HouseholdPositionResolver`：`UNIT(unitId)` → 该 unit 当刻 effectivePosition 的 hex；
- 市场/账户/生产读位置一律走这个 resolver；
- 这样 `unit.PlaceAt` / 行军到达 / 迁都后，政府家户的“有效 hex”自动跟随，无需单独同步 Social 位置；
- `GovernmentHouseholdWiring` 增加位置闭环校核，拒绝旧 HEX 僵尸国库。

## 4. 实质化命名（命名审计与迁移）

### 4.1 命名原则

1. 名字先说**是什么事实/主体**，再说**它在系统里的角色**；
2. 禁止用缩写/实现词当领域名（`Row`、`Entry`、`Ref`、`Alloc`、`Org`、`Unit` 单独出现要谨慎）；
3. 同义概念只允许一个词：家户 = household，人口批次 = people lot，账户 = account，库存 = inventory，
   生产方式 = production mode，生产活动 = production process，单位/编制 = unit/formation；
4. 先改新代码/设计文档；旧类型如要改名，单独做迁移批，保持线格式兼容或按“旧档报废”直接换。

### 4.2 候选重命名表（代码层，wire key 另议）

| 现名 | 实质 | 建议名 |
|---|---|---|
| `ClassRow` | 家户的经济状态/行 | `HouseholdEconomy` / `HouseholdEconomicState` |
| `ClassStanding` | 家户的阶层身份 | `HouseholdClassMembership` / `HouseholdStratum` |
| `LaborAllocation` | 家户把劳动时间分给某生产活动 | `HouseholdLaborCommitment` |
| `LaborTimeTable` | 家户各年龄/性别的每 tick 时间系数 | `HouseholdLaborTimeTable` |
| `GoodsAccount` | 家户在一处的库存/钱包 | `HouseholdInventory` / `HouseholdAccount` |
| `GoodsAccountKey` | 家户户号（P2-A 后键=HouseholdId） | `HouseholdAccountKey` / 直接用 `HouseholdId` |
| `AssetShare` | 家户对资产的份额/权利 | `OwnershipStake` / `HouseholdAssetRight` |
| `DemandEntry` | 家户的一条需求 | `HouseholdDemand` |
| `ProductionOrganization` | 一条生产活动的组织主体 | `ProductionEnterprise` / `ProductionActivity` |
| `ProductionUnit` | 一条实际生产活动/工艺实例 | `ProductionProcess` |
| `ProductionRelation` | 生产活动的分配规则 | `ProductionRules` / `DistributionRules` |
| `Recipient` | 收款/受益主体 | `Payee` / `Beneficiary` |
| `ProductionMode` | 生产方式/制度模板 | `ProductionMode`（保留；已具实质） |
| `ClassStructure` / `ClassPosition` | 生产方式下的阶层结构/角色 | `ClassStructure` / `ProductionRole` |
| `ActorRef` | 经济主体稳定身份 | `EconomicSubjectRef`（契约层，可后议） |
| `Unit` | 单位/编制组织 | 保留 `Unit`，但字段名必须写 `households`/`equipment`/`formation` 等实质 |
| `GovFormation.households` | 与 Unit.households 重复 | **删除**，迁到 `Unit.households` |
| `householdPosts` / `householdDuties` | 政府/军队里的家户角色配置 | 保留但字段名可改 `governmentPostsOfHousehold` / `militaryDutiesOfHousehold` |

### 4.3 迁移方式

- 新增代码/新文档一律用实质名；
- 旧类型重命名分三步：
  1. 代码内改名（不改 wire key、ID 字符串、模块名）；
  2. 需要改 wire key 的，按“旧世界报废、新世界重建”口径直接改，不做双读；
  3. 更新日志 `event=` 字段名与 API 读口字段，让读口也能看懂“这是什么事实”。

## 5. 通用库存扣除接口（与前一报告一致）

- 新增 `economy.DeductStock`（或最终定名 `DeductHouseholdStock`）：
  `household + goods/money + amount>0 + reason + detail + optional toHousehold`；
- reason 封闭枚举 + 自由 detail：`MILITARY_SALARY` / `JURISDICTION_TAX` / `ADMIN_UPKEEP` / `CORVEE`；
- 税收/俸禄政策层只算“谁、多少、为什么”，落账统一走该接口；
- 军队俸禄：决策/令生成下一 tick 的扣除计划，由 time participant/app 调该接口；
- 不再由 `JurisdictionDailyTax` 自己直接 `AccountSession.commit`。

## 6. 改完能实现什么

| 层面 | 改完后 |
|---|---|
| 架构 | Social 是人员/家户唯一域；后端业务模块直连；`social-api` 只剩低层 ID 契约；Unit 只有一份家户实质列表 |
| 命令执行 | 一条 revision 可原子写 Unit+Social / Economy+Social / Gov+Social；不再靠 app 到处搭协调器 |
| 政府 | GOV 家户在 `Unit.households`；位置 `UNIT(unitId)` 随单位移动；国库不再停在旧 HEX |
| 经济 | 税/行政/军队俸禄统一走通用扣除；economy 不维护税制，只提供原语 |
| 命名 | 新代码/读口/日志用实质名；旧类型可分批重命名；旧世界本来就重建 |
| 测试 | 可写跨模块 revision、家户位置跟随、税/俸禄扣除的端到端不变量用例 |

## 7. 剩余风险

- `social-api` 还要保留以破 Maven 环；如果用户坚持完全取消，需要先迁移 `CohortKey`/`ResidenceKind`/`SocialClassId`。
- 多模块 ChangeSet 要动 Core，影响所有 handler 的授权/资源围栏，需单独测试批。
- 重命名是大 diff，必须按批走、每批 compile 门禁；不能顺手混进功能改动。
- 通用扣除若做成 sink，需在守恒式中显式列“行政/军俸消耗”；若是转移，需给 `toHousehold` 并保证同 revision 原子。
