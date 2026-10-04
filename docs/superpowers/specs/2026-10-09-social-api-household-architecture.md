# 2026-10-09 Social API + 家户/人口架构

> 用户 2026-10-08 最终裁定：家户归 Social；Social 是“家户在哪、由谁组成、怎么增减人”的接口层；出生率/死亡率/人员事件由 Social 维护；家户可挂 `HEX` 或 `UNIT`；Unit/Gov 人数实时从家户汇总；旧世界不做迁移。
>
> 本文件是实现前架构（AGENTS.md §一.8）；开发拆解见 `docs/superpowers/plans/2026-10-09-social-api-household-plan.md`。

## 1. 目标与非目标

**目标**
1. 新建独立契约模块 `simos-social-api`：只放稳定 ID、地址、家户画像、人口事件/率形状、只读 SPI；不放状态/Codec/结算/Jackson 实现。
2. 把 `HouseholdId`、`PeopleLotId`、`Sex` 等跨模块人口/家户词汇迁入 `simos-social-api`；全仓只保留一份定义。
3. `simos-social` 实现家户状态与生命周期：家户位置、成员批次、增人/减人/转移、逐家户率与人口事件。
4. `PopulationGroup` 复用为家户成员批次；删除 `residence` 的位置语义，位置统一归 `Household.location`。
5. Unit/Gov/Economy/Culture/Religion 只通过 `social-api` 引用 `HouseholdId`/`PeopleLotId`，不再复制家户表。
6. Social 与新模块补齐结构化日志：创建家户、位置/画像、成员增删、率修改、出生/死亡/转移事件、守恒检查。
7. 本批**不**做旧世界迁移；新世界按新模型播种。

**非目标**
- 本批不实现 Culture/Religion/Gov 的实际效果，只保留 `HouseholdId` 挂接点。
- 本批不实现 GUI 家户面板（读口接口留出）。
- 不做逐人模拟；仍是 `PopulationGroup` 批次级。

## 2. 当前状态

| 类型 | 当前归属 | 问题 |
|---|---|---|
| `HouseholdId` | `economy-api` | 经济私有身份，Unit/Social/Culture/Religion 无法稳定引用 |
| `PeopleLotId` | `economy-api` | 人口批次 ID 在 economy，Social 反向依赖 economy-api |
| `PopulationGroup` | `social` | 带 `residence: HexCoord`，无法表达 Unit 家户；批次与家户没有正式关系 |
| `ClassRow.population` | `economy` | 经济侧第二份家户人口 |
| `Unit.manpower` | `unit` | 军队独立 headcount，与 Social 人口无链接 |
| `GovFormation.staff` | `unit` | 官府独立 headcount，与 Social 人口无链接 |
| Social 日志 | 无 | 家户/人口/事件没有结构化日志 |

## 3. 模块与依赖

### 3.1 新模块 `simos-social-api`
- 依赖：`simos-map`（`HexCoord`）；不依赖任何 simos 领域模块、不依赖 Jackson、无状态、无 Codec。
- 包名：`io.mosire.simos.social.api`。
- 只放：
  - `id.HouseholdId`、`id.PeopleLotId`（从 economy-api 迁入，旧包删除）；
  - `population.Sex`（从 social 迁入；简单枚举）；
  - `household.HouseholdLocation`：`Hex(HexCoord)` / `Unit(String unitId)` 两档，Unit 用不透明字符串避免依赖 `simos-unit`；
  - `household.HouseholdProfile`：`name`、`description`、`metadata: Map<String,String>`；
  - `population.AgeBracketView`、`population.HouseholdVitalRates`、`population.HouseholdPopulationEvent`、`population.PopulationEventType`；
  - `lookup.HouseholdLookup`、`lookup.PopulationLookup` 只读 SPI。
- 不放：`Household` 状态本体、`PopulationGroup` 状态本体、任何 ChangeSet/Snapshot/Codec。

### 3.2 依赖调整
- `simos-social` 增加 `simos-social-api` 依赖；`PeopleLotId`/`Sex`/`HouseholdId` 全部改 import 新包。
- `simos-economy-api` 增加 `simos-social-api`，删除自己的 `HouseholdId`/`PeopleLotId`。
- `simos-economy` / `simos-actor` / `simos-gov` / `simos-app` 通过传递依赖或显式声明 `simos-social-api`。
- `simos-unit` 本批只在确实引用新 ID 时增加依赖；否则留到 Unit 集成阶段。
- root `pom.xml`：modules + dependencyManagement 增加 `simos-social-api`；相关模块 enforcer 增加禁反向依赖的规则。
- 旧 `io.mosire.simos.economy.api.id.HouseholdId` / `PeopleLotId` 删除，不留 deprecated 双轨。

## 4. 数据模型

### 4.1 `Household`（Social 域状态，非 api）
```
Household(
  HouseholdId id,
  HouseholdLocation location,
  HouseholdProfile profile,
  List<PeopleLotId> memberLots,
  Map<AgeBracketKey, HouseholdVitalRate> vitalRates
)
```
- `memberLots` 是**唯一成员关系**：不再建 `HouseholdMember` 表。
- `vitalRates` 建议按 `(ageBracket, sex)` 键存；本阶段先存“每 tick 死亡率”和“育龄段每 tick 生育率”，其他年龄段只有事件、没有率。
- 家户位置只有 `HEX | UNIT` 两种；社会组织都用 Unit 实现，不新增 anchor。

### 4.2 `PopulationGroup`（Social 域状态）
```
PopulationGroup(
  PeopleLotId id,
  Sex sex,
  long count,
  long ageAtAnchorDays,
  long anchorTick,
  long physiologicalStress
)
```
- **删除 `residence: HexCoord`**；位置由所属 `Household.location` 提供。
- 批次可以跨家户转移、随家户从 hex 到 unit 移动；id 不变。
- `SocialData.groups` 改成按 `PeopleLotId` 的平表；家户成员通过 `Household.memberLots` 关联。

### 4.3 率与事件
- `HouseholdVitalRate(AgeBracketKey key, long deathRatePerMillePerTick, long birthRatePerMillePerTick)`；育龄段 birth rate 有效，其余为 0。
- `AgeBracketView(String bracketId, long minAgeDays, long maxAgeDays, Sex sex, long count, long increaseRatePerTick, long deathRatePerTick)`：
  - `deathRatePerTick` 逐年龄段；
  - `increaseRatePerTick` 只有最小年龄段（0 岁）承载出生结果；其他年龄段的增加用事件表达，不是率。
- `HouseholdPopulationEvent(id, householdId, type, sex, ageBracketId, count, day, reason, source)`：
  - 类型：`BIRTH / DEATH / TRANSFER_IN / TRANSFER_OUT / GM_ADJUST / RATE_SET`。
  - 事件落 `SocialData` 的持久事件表，进 ChangeSet/Codec，可回放。
- 每 tick：
  1. 逐家户按年龄段算死亡数（事件 DEATH）；
  2. 按育龄段算出生数，加入最小年龄段（事件 BIRTH，若 0 岁批次不存在则新建）；
  3. GM/经济/其它系统转移人口（事件 TRANSFER_IN/OUT）；
  4. Social 应用事件到 `PopulationGroup`；守恒检查 `Σ主归属 count == 批次 count`。

## 5. Social 生命周期接口

Social 域（包内/服务类）提供：
- `createHousehold(id, location, profile)`
- `setLocation(householdId, location)`
- `setProfile(householdId, profile)`
- `addMembers(householdId, sex, ageAtAnchorDays, count, reason)`
- `removeMembers(householdId, lotId, count, reason)`
- `transferMembers(from, to, lotId, count, reason)`
- `setVitalRates(householdId, rates, reason)`
- `adjustPopulation(householdId, sex, ageBracket, count, reason)`（GM 直调）
- `applyEvent(event)`（内部唯一落账口）
- 只读查询：
  - `household(householdId)`、`householdsAt(HexCoord)`、`householdsInUnit(String unitId)`
  - `populationAt(HexCoord)` / `unitPopulation(String)`
  - `ageBrackets(householdId)`

对外只读 SPI 在 `simos-social-api`：
```
HouseholdLookup { Optional<HouseholdView> household(HouseholdId); List<HouseholdView> at(HexCoord); List<HouseholdView> inUnit(String); }
PopulationLookup { long populationAt(HexCoord); long unitPopulation(String unitId); long householdPopulation(HouseholdId); List<AgeBracketView> ageBrackets(HouseholdId); }
```
Unit/Gov/Economy/Culture/Religion 只依赖 SPI，不依赖 Social 状态。

## 6. 日志

新增 `simos-social` 门面 `io.mosire.simos.social.SocialLog`：
- root：`io.mosire.simos.social`
- `.household`：创建、位置、画像、成员增删、转移、率设置
- `.population`：逐家户出生/死亡/调整汇总
- `.event`：逐事件落账
- `.trace`：逐批次/逐移出移入明细
- 级别约定：INFO 生命周期/汇总；DEBUG 池/对账/守恒；TRACE 逐条。
- 配置：`log4j2.xml` 增加 `simos.social.logLevel`（默认 INFO）、`simos.social.traceLevel`（默认 INFO）。
- 事件 key 一律 `key=value`，不记密钥；示例：
  - `event=HOUSEHOLD_CREATED id=... location=HEX:1_0`
  - `event=HOUSEHOLD_MEMBER_ADD id=... lot=... count=... sex=... ageAnchor=... reason=...`
  - `event=POPULATION_BIRTH household=... ageBracket=0-4 count=...`
  - `event=POPULATION_DEATH household=... ageBracket=... count=...`
  - `event=POPULATION_TRANSFER from=... to=... lot=... count=... reason=...`
  - `event=POPULATION_CONSERVATION_CHECK household=... ok=...`

## 7. 验收判据

1. 全模块 main compile 通过；`HouseholdId`/`PeopleLotId` 只在 `simos-social-api` 有定义，生产代码无旧包 import。
2. `SocialData` 能持有家户：创建、改位置（HEX↔UNIT）、改画像、增删成员、家户转移。
3. `PopulationGroup` 无 `residence` 字段；hex/unit 人口由家户汇总现算。
4. 逐家户率与事件可应用：死亡按年龄段扣；出生加入最小年龄段；转移守恒。
5. GM/经济开放接口传入 `reason` 后，事件可回放，守恒检查通过。
6. 日志输出与架构预期事件名/字段一致；`simos.social.logLevel` 可升降级。
7. 不修改任何测试文件（测试后置）；旧世界不迁移。

## 8. 已知缺口

- Culture/Religion 的实际字段与效果未做；只留 `HouseholdId` 挂接点。
- Unit/Gov 从“独立 headcount”切到“家户汇总”分后续阶段做。
- GUI 家户面板未做。
- 非法仿制/货币生产仍按既有 GOV 计划另阶段。
