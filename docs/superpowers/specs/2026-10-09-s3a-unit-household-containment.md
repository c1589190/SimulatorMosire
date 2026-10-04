# 2026-10-09 S3a：Unit/Gov 家户容纳与实时人口读口

> 来源：S1/S2 完成后的 S3 首段。目标：让 Unit（尤其 GOV）能容纳多个家户，人口从 Social 家户实时汇总；先不把 Army 战斗人力从 `Unit.manpower` 切走，也不重构 Economy `ClassRow.population`。
> 前置：`docs/superpowers/specs/2026-10-09-social-api-household-architecture.md`（已验证）
> 纪律：AGENTS.md §一.5/§一.8；一个实现子 Agent 只写生产代码、只过 compile；测试后置单独 Agent。

## 1. 目标与非目标

**目标**
1. `Unit` 新增 `List<HouseholdId> households`（第 18 个组件）：一个 Unit 可容纳 0..N 个家户；GOV 单位可同时有多个群体家户（印度教/华人示例）。
2. `GovFormation` 新增 `List<HouseholdId> households`：官府编制不再只靠 `staff` 人数表；`staff` 保留兼容，后续再降为投影。
3. `simos-unit` 依赖 `simos-social-api`（只引 `HouseholdId`/契约，不依赖 `simos-social` 实现）。
4. Unit/Gov 人口实时从 Social 家户汇总：`unitPopulation(unitId) = Σ household.memberLots.count`；不落第二本 headcount。
5. 家户可 `HEX↔UNIT` 移动；加入 Unit 时 Unit.households 与 Household.location 原子一致（同一批命令 = 一条 revision）。
6. GM 工具：创建/移动家户、给 Unit 增加/移除家户、增减成员、调出生/死亡率；日志齐全。

**非目标**
- 不切换 Army 战斗人力：`Unit.manpower` 暂时保留，作为“军事编制/兵种构成”投影，后续 S3b 再决定。
- 不做 Economy `ClassRow.population`/`LaborSupply` 与 Social 家户的 ID 统一（S3b）。
- 不做铸币/产能 GM 工具（S3c）。
- 不做旧世界迁移。

## 2. 现状

- `Unit`：17 组件，`manpower: List<CompositionEntry(type,amount)>` 是独立 headcount。
- `GovFormation`：`staff: Map<StaffRole,Long>`，独立编制人数。
- `Social`：`Household` + `memberLots` 已是家户人口真值；`PopulationLookup.unitPopulation(unitId)` 已能按 Unit 汇总。
- `simos-unit` 目前只依赖 util+map；本次增加 `simos-social-api` 契约依赖。

## 3. 数据模型

### 3.1 Unit
```
Unit(
  ... 现有 17 组件 ...,
  List<HouseholdId> households   // 新第 18 组件；不得 null、不得重复、冻结不可变
)
```
- 首建/旧调用点默认空表。
- 所有重建 Unit 的拷贝点必须原样带过 `before.households()`（漏传 = 静默丢家户，最贵教训）。
- `UnitState` 构造期增加：同一 household 在多个 unit 的 households 列表里出现 ⇒ 拒（一个家户只能属于一个 Unit 或 Hex）。

### 3.2 GovFormation
```
GovFormation(
  Map<StaffRole,Long> staff,   // 保留兼容
  List<HouseholdId> households,// 新组件；官府下辖家户
  OfficePolicy policy, Optional<UnitId> superiorGov, GovLevel level
)
```
- 现有构造器兼容（households 默认空表）。
- `staff` 与 `households` 并存；本阶段不自动推导 staff，读口两者都返回。

### 3.3 一致性
- `Household.location = Unit(unitId)` 且 `unit.households` 包含该家户 —— 必须同时成立。
- 通过 app 组合工具用同一批命令写入：
  1. `social.SetHouseholdLocation(householdId, UNIT(unitId))`
  2. `unit.SetUnitHouseholds(unitId, ...)`
- 两命令同 branch + 同 expectedRevision ⇒ 一条 revision；任一侧失败则整批回滚。

## 4. 命令与工具

### 4.1 Social 侧命令（新增）
- `social.CreateHousehold`：`{householdId, location, profile, vitalRates?, reason}`
- `social.SetHouseholdLocation`：`{householdId, location, reason}`
- `social.AddHouseholdMembers`：`{householdId, lotId?, sex, count, ageAtAnchorDays?, anchorTick?, reason}`
- `social.RemoveHouseholdMembers`：`{householdId, lotId, count, reason}`
- `social.TransferHouseholdMembers`：`{from,to,lotId,count,reason}`
- `social.SetHouseholdVitalRates`：`{householdId, rates:[{bracketId,sex,birthRatePerMillePerTick,deathRatePerMillePerTick}], reason}`
- `social.AdjustHouseholdPopulation`：`{householdId, sex, ageBracketId, delta, reason}`
- 全部写 `SocialData`；走 `SocialChangeSet`；日志用 `SocialLog`。

### 4.2 Unit 侧命令（新增）
- `unit.SetUnitHouseholds`：`{unitId, households:[...], reason}`（整体替换，保持顺序；重复/不存在家户校验由 app 工具/域守卫）。
- `unit.AssignHousehold` 可暂不做，由 app 工具组合。

### 4.3 App GM 工具
- `simos.social.household.create`
- `simos.social.household.move`（HEX↔UNIT）
- `simos.social.household.members`（增/减/转移）
- `simos.social.household.rates`（出生/死亡率）
- `simos.unit.assignHousehold`：组合 `social.SetHouseholdLocation` + `unit.SetUnitHouseholds`，preview/apply、reason、GM-only。
- `simos.unit.detachHousehold`：移到 HEX + 从 unit 列表移除。
- 所有工具：`Command → ChangeSet → Revision`，同批原子。

## 5. 读口与 GUI

- `PopulationLookup.unitPopulation(unitId)` 已存在；本次接：
  - `ApiViews` 的 unit 详情/`/api/unit/{id}` 增加 `households[]` 与 `population`（来自 lookup）。
  - GUI Unit 页面显示家户列表与总人口。
- `GovFormation.households` 读口同样返回；Gov 面板显示“该政府下辖哪些家户”。

## 6. 日志

- Social 侧走 `SocialLog`（已就位）。
- Unit 侧新增轻量 `UnitLog` 或复用 `org.slf4j`：
  - `event=UNIT_HOUSEHOLDS_SET unit=... households=... reason=...`
  - `event=UNIT_HOUSEHOLD_ASSIGN unit=... household=... location=UNIT:... reason=...`
  - `event=UNIT_HOUSEHOLD_DETACH unit=... household=... location=HEX:... reason=...`
- 级别：INFO 生命周期；DEBUG 对账（家户位置 ↔ unit 列表一致）；TRACE 逐项。

## 7. 验收判据

1. `./mvnw -q -pl simos-app -am -DskipTests compile` 绿。
2. `Unit` JSON/ChangeSet 往返保留 households；旧构造器默认空表。
3. GOV 单位可同时容纳 `hh-hindu-001` 与 `hh-han-001` 两个家户；`unitPopulation` = 两户成员之和。
4. 家户从 HEX 移到 UNIT：Social location 与 Unit.households 同时变化；移回 HEX 同时清理。
5. 一批命令任一失败 ⇒ 整批不落 revision。
6. 日志有 UNIT_HOUSEHOLD_ASSIGN/DETACH 与 Social 侧事件。

## 8. 文件所有权（实现子 Agent）

- 允许：`simos-unit/**` main、`simos-social/**` main、`simos-social-api/**` main、`simos-app/**` main、相关 `pom.xml`/enforcer、`log4j2.xml`。
- 禁止：任何 `src/test/**`；任何 git commit/push。
- 编译门：`./mvnw -q -pl simos-app -am -DskipTests compile`。

## 9. 待裁定

- `GovFormation.staff` 与 `households` 并存期间，staff 是否允许为空、是否需要“staff 必须由 households 投影”的守卫。
- Unit 与 Household 的一致性守卫放在 app 工具（推荐）还是 Unit 域构造期（跨切片做不到，只能做单侧）。
