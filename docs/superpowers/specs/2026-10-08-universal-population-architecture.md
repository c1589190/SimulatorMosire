# 2026-10-08 通用人口架构（Unit / Social / Economy / Gov 共用人）

> 来源：用户 2026-10-07/08 裁定与追问：
> “谁跟你说政府家户要给真实人口了？政府开这个生产方式不能招人？政府自己的家户人口可以设置为政府单位里的人口——哦对，Unit 和 Social、经济模块没有做人口通用……好，现在我们找到下一个架构问题了，人口通用，你准备怎么做。”

## 1. 问题：现在有三本互不相通的人口账

| 模块 | 人口表示 | 说明 |
|---|---|---|
| Social | `PopulationGroup(id=PeopleLotId, residence, sex, count, ageAtAnchorDays, anchorTick, stress)` | 人口学真值：批次、年龄、性别、居所、生理压力 |
| Economy | `ClassRow.population`、`LaborSupply`、`LaborAllocation`、`ClassFirstState` 池 | 经济视图：家户人口、劳动供给、劳动分配；由 seeder 从 social 复制/折算 |
| Unit | `Unit.manpower: List<CompositionEntry(type, amount)>` | 军队人力：类型+人数，与 social/economy 无稳定链接 |
| Gov | `GovFormation.staff: Map<StaffRole, Long>` | 官府在编人数，与 unit/social 也无链接 |
| Actor | 无人口 | 只有主体身份与账户 |

后果：
- 军队人数不是“从社会人口里抽出来的”，而是单位自己的第二本账；
- 官府编制人数也不是社会人口；
- 政府家户如果要有劳动，只能硬塞一个 `ClassRow.population`，和 Unit/Gov 的人重复或对不上；
- 无法表达“同一批人：既是政府单位的人，又是政府家户的人”；
- 招募/征兵/退伍/调任/家户迁移跨模块无法守恒。

## 2. 目标

1. **单一人口事实源**：人口数量、年龄、性别、居所、生理压力只有一个权威记录。
2. **稳定身份**：`PeopleLotId` 跨模块引用；移动/征募/退伍不换身份。
3. **角色/归属关系**：人属于哪个主体、以什么角色（家户成员、士兵、官吏、工匠、铸币工、佃农、雇农……）是显式关系，不是各模块的第二本人数账。
4. **跨模块守恒**：任何转移/招募/退伍有明确来源与去向，全局人口总量不增不减。
5. **政府家户可招募**：政府家户不需要硬编码人口；它可以按角色拿到劳动，并可以让“家户人口 = 政府单位人口”成为**同一批人的不同视图**，而不是复制。
6. **铸币等生产方式能用**：mint 组织者（政府家户）可通过人口角色/劳动分配拿到工人。

**非目标**
- 不做户籍法/身份等级/种族文化等完整社会学模型。
- 不做逐人模拟；仍是批次（`PopulationGroup`）级。
- 不做移民跨国/战俘/奴隶制等特殊人口制度，先留接口。

## 3. 方案选项

### 选项 A：只加共享 ID，不给共享状态
- 把 `PeopleLotId` 提升到公共 API；Unit/Gov 在各自状态里继续存人数，但加 `lotId` 引用 + 对账。
- 优点：改动最小。
- 缺点：人数仍是多本账；招募/退伍/政府家户复用人口无法自动守恒。

### 选项 B：新建 `simos-population` 作为唯一人口域
- 把 `PopulationGroup` 与人口学、年龄/性别、迁移、招募全部收进新模块；Social 退化为城市/区域读口。
- 优点：最干净。
- 缺点：迁移面最大：Social 的创世、人口动力学、城市归属、Economy 的 seeder、Unit 的组建、Gov 的编制全部要改。

### 选项 C（推荐）：两层 + 公共契约 + 投影
**第一层 demographics（人口学）**：`Social` 仍持有 `PopulationGroup` 作为唯一人口学真值（人数/年龄/性别/居所/压力）。
**第二层 assignment（归属与角色）**：新增 `simos-population` 域切片，持有 `PopulationAssignment`（批次 → 主体 + 角色 + 数量 + primary/secondary）。
**公共契约**：新增 `simos-population-api`，Unit/Social/Economy/Gov 都只依赖契约，不互相依赖域实现。
**各模块投影**：
- Economy 的 `ClassRow.population` / `LaborSupply` / `LaborAllocation` 从 population 关系投影；
- Unit 的 `manpower` 从 `SOLDIER/OFFICER` 角色投影；
- Gov 的 `staff` 从 `OFFICIAL` 角色投影；
- 家户人口从 `HOUSEHOLD_MEMBER` 主归属投影。

优点：不立即搬走 Social 的人口学；先建立“同一批人”的公共语义；投影可渐进替换；符合铁律 3。

## 4. 数据模型（选项 C 的推荐形状）

### 4.1 人口学记录（Social，现有）
`PopulationGroup(id=PeopleLotId, residence, sex, count, ageAtAnchorDays, anchorTick, physiologicalStress)`

保持现有含义；**不新增**“属于谁/职业/兵役/官府”字段——那些是 assignment。

### 4.2 人口归属（新 `simos-population`）
```
PopulationAssignment(
  id: PopulationAssignmentId,        // 稳定身份
  lot: PeopleLotId,                  // 哪一批人
  owner: ActorRef,                   // 归属主体：HOUSEHOLD / GOVERNMENT / UNIT / ESTATE / WORKSHOP / ORGANIZATION
  role: PopulationRole,              // 角色
  count: long,                       // 人数（≤ lot.count）
  tier: PRIMARY | SECONDARY,         // 主归属 / 兼属
  sinceDay: long,
  untilDay: 可选
)
```

**角色词表（起步）**：
`HOUSEHOLD_MEMBER`、`DEPENDENT`、`TENANT`、`WAGE_LABORER`、`ARTISAN`、`MINT_WORKER`、`SOLDIER`、`OFFICER`、`OFFICIAL`、`APPRENTICE`、`DISPLACED`。
后续可扩展，不在本批铺满。

**primary/secondary 语义**：
- 每个 lot 的 `Σ PRIMARY.count` 必须 = `lot.count`（所有人都有且只有一个主归属；没归属也显式算某个默认主体，比如所在 Region）。
- SECONDARY 是兼任/在册关系（例如“军队在役”同时“仍是某家户成员”、或“政府单位的人”同时“政府家户的人”），不参与主人口投影。
- 这样即可表达用户要的“政府家户人口 = 政府单位人口”：同一 lot 的 primary 归政府家户，secondary 归政府单位（或反过来，按哪个投影算“主人口”决定）。

### 4.3 读接口
`simos-population-api` 提供只读 SPI：
```
PopulationLookup {
  long countByOwner(ActorRef owner, PopulationRole role, Tier tier);
  List<PopulationAssignmentView> byOwner(ActorRef owner);
  List<PopulationAssignmentView> byLot(PeopleLotId lot);
  long lotCount(PeopleLotId lot);
  long totalPopulation();
}
```
Unit/Economy/Gov 通过注入的 SPI 读，不直接依赖 `simos-population` 的状态/Codec。

### 4.4 投影与对账
- Economy：`ClassRow.population` 不再作为唯一权威，而是 `PRIMARY + HOUSEHOLD_MEMBER` 投影或主归属投影；`LaborSupply` 从投影重算。
- Unit：`manpower` 改为从 `SOLDIER/OFFICER` assignment 派生的只读投影；或先保留缓存，但每 tick/命令后由 app 对账（不一致 ⇒ 具名日志 + 拒绝推进）。
- Gov：`GovFormation.staff` 改为 `OFFICIAL` 投影。
- Social：仍持有 demographics；`headlinePopulationAt` 不变。

## 5. 模块与依赖

新增：
- `simos-population-api`（无 simos 主依赖，或仅 util）：
  - `PeopleLotId`（从 `economy-api` 迁移到这里）；
  - `PopulationAssignmentId`、`PopulationRole`、`Tier`、`PopulationAssignmentView`、`PopulationLookup`。
- `simos-population`（域切片）：
  - `PopulationAssignment` 状态 + Snapshot/Codec/ChangeSet；
  - 命令：`population.Assign` / `Transfer` / `Recruit` / `Release` / `SetPrimary`；
  - 守恒守卫、日志、读口。

依赖调整：
- `simos-unit` 增加 `simos-population-api`（只读 SPI + ID）；
- `simos-social` / `simos-economy-api` / `simos-economy` / `simos-gov` / `simos-actor` 增加 `simos-population-api`；
- `simos-app` 组合 `simos-population` 与各投影写回；
- enforcer 同步更新，防反向依赖。

## 6. 政府家户与铸币如何落在这套上

- 政府单位人口（兵、官） = `owner=GOV_UNIT` 的 `SOLDIER/OFFICIAL` assignments。
- 政府家户人口 = 投影 `owner=GOV_HOUSEHOLD` 的主归属或成员角色。
- 用户要的“政府家户人口设置为政府单位里的人口”有两种实现，待裁定：
  - **C1 主归属在家户**：这些 lot 的 PRIMARY 给政府家户，SECONDARY 给政府单位（军队/官府拿兵役/官职视图）；家户人口是主投影。
  - **C2 主归属在单位**：单位拿 PRIMARY，政府家户拿 `HOUSEHOLD_MEMBER`/`ADMIN_STAFF` SECONDARY；家户人口是角色投影。
- 铸币：mint unit 的 operator = 政府家户；劳动通过 `MINT_WORKER` assignment + `LaborAllocation` 指向 mint unit；政府家户不需要硬编码人口，只要拿到工人分配。
- 招募：`population.Recruit` 把一批人的 assignment 从区域/家户/单位移到政府家户或 mint unit；主归属变化必须守恒并写日志。
- 军队/官府消费：各自人口按 assignment 投影，粮饷/俸禄按投影结算，不再使用彼此独立的人数表。

## 7. 迁移与旧档

- 用户既定口径：旧档可重置、不做迁移兼容。
- 本批新增的 `simos-population` 只在**新世界**播种。
- 如果必须读旧档：一次性迁移器把
  - Social 的 `PopulationGroup` → PRIMARY assignment（默认 owner = 所在 Region 或家户）；
  - Unit 的 `manpower` → `SOLDIER/OFFICER` assignment；
  - Gov 的 `staff` → `OFFICIAL` assignment；
  - 迁移后旧字段清零或标记只读。  
  这条作为可选项，不阻塞新世界。

## 8. 日志（必须）

沿用 `EconomyLog` / 新增 `population` 子 logger：
- `POPULATION_ASSIGN`、`POPULATION_TRANSFER`、`POPULATION_RECRUIT`、`POPULATION_RELEASE`、`POPULATION_PRIMARY_SET`；
- `POPULATION_CONSERVATION_CHECK`（lot 级：Σprimary == count；owner 级投影；失败 ⇒ ERROR/拒绝）；
- `POPULATION_PROJECTION_REBUILD`（unit/gov/economy 三侧投影重建与差异）；
- TRACE：逐 lot/逐 owner 的 assignment 明细。
- 规则：INFO 生命周期/汇总；DEBUG 投影/对账；TRACE 逐条；结构化 `key=value`。

## 9. 分阶段落地

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 | `simos-population-api` + `PeopleLotId` 迁移 + 依赖/enforcer | 全模块编译；unit/social/economy/gov 都能引用人口 ID，无重复 ID 类型 |
| P1 | `simos-population` 域：assignment 状态 + 命令 + 守恒 + 日志 | 新增世界能建主归属；Σprimary == lot.count；命令可重放 |
| P2 | 投影：Economy ClassRow/LaborSupply、Unit manpower、Gov staff | 三侧投影由 assignment 现算/对账，差异具名日志；结算行为不漂 |
| P3 | 招募/退伍/调任：政府家户、mint、军队 | 政府家户可招募工人；招募/退伍守恒；mint 能用 |
| P4 | GUI/读口：人口、家户、单位、官府、铸币工人视图 | GUI 看到同一批人的不同角色视图，数字互相对得上 |

## 10. 家户主体归属（2026-10-08 追加讨论）

用户提出：后面文化、宗教等模块都要大量“家户”，要不要把**家户主体直接沉到 Social 模块**。

### 10.1 问题本质
家户已经不是 economy 的私有物：
- Economy 需要它（生产、消费、债务、劳动）；
- Actor 需要它（账户主体）；
- 未来的 Culture/Religion 需要它（信仰、习俗、节日、禁忌）；
- Gov 需要它（户籍、赋役、征兵、救济）；
- Social 需要它（人口、家庭、居所、生命历程）。
把家户身份留在 economy，未来每个模块都会各自复制一份“家户表”。

### 10.2 三种沉法
- **H1：家户主体沉到 Social**
  - Social 拥有 `HouseholdId` + 家户状态；其他模块依赖 Social 或读 Social 投影。
  - 风险：Social 变成 god module；economy/unit/gov/culture/religion 都要依赖它；`simos-unit` 目前只依赖 util+map，Unit 若也要家户会被迫改依赖；与铁律 3 “领域模块互不依赖”冲突。
- **H2（推荐）：家户身份沉到共用契约层，家户事实按域分片**
  - 新增 `simos-household-api`（或并入 `simos-population-api`）：`HouseholdId`、`HouseholdRef`、`HouseholdKind`、`HouseholdLookup`。
  - Social 拥有“家户的社会事实”：成员、居所、人口/生命周期。
  - Economy 拥有“家户的经济事实”：ClassRow、生产参与、需求、债务投影。
  - Actor 拥有账户（`ActorRef(HOUSEHOLD, id)`）。
  - Culture/Religion/Gov 未来各自拥有自己的家户关系表/ facet，键 = `HouseholdId`。
  - App 组合根按 facet 拼出完整家户视图。
- **H3：家户并入“人口/人”大域**
  - 新建 `simos-people`（人口 + 家户 + assignment）作为唯一真源；Social 退为城市/区域聚合。
  - 最彻底，但迁移面最大，可作为长期终局。

### 10.3 推荐
**短期走 H2，长期可收敛到 H3。**
理由：
- 家户身份是跨域稳定 ID，应该和 `ActorRef` 同级下沉，而不是由某个领域模块“拥有”；
- Social 负责“家户是谁、有哪些人、住哪、什么文化/宗教背景”——这与它现在的人口学职责一致；
- Economy/Culture/Religion/Gov 只持有自己的 facet，同一 `HouseholdId` 可被任意投影；
- 避免 Social 成为 god module，也避免每个模块复制家户表；
- 和通用人口方案天然对齐：`HouseholdId` 与 `PeopleLotId` 一起进 `simos-population-api`，Social 持有 demographics，`simos-population` 持有 assignment。

### 10.4 需要一起裁定的点
- `HouseholdId` 最终放 `simos-population-api` 还是新 `simos-household-api`？
- Social 的家户状态是否升级为正式 `Household` record（成员、居所、生命周期），还是继续由 `ClassRow` + `PopulationGroup` 拼？
- 文化/宗教未来是 facet（读侧拼装）还是各自独立的关系表（写侧各自命令）？
- 家户账户主体是否统一为 `ActorRef(HOUSEHOLD, id)`，由 actor 层只做账户、不做家户语义？

### 10.5 2026-10-08 用户修订：Social 管人是天经地义；Actor 不是阶层

用户裁定/追问：
- Social 本来就是管人的；人自然组成群体，**家户归 Social 管是天经地义**；
- Social 模块**还没有大到必须拆**；
- Actor 不应该是阶层；需要说清“账户主体”是什么。

据此修订：
- **家户主体（Household）与人口/群体事实归 Social**，不再另起人口域，也不把 household 拆出 Social；
- 只把**跨域共享的身份/角色词汇**放低层契约模块（暂名 `simos-people-api`，或先沿用 `economy-api` 中的 `HouseholdId`）：`HouseholdId`、`PeopleLotId`、`PopulationRole`、`HouseholdLookup`/`PopulationLookup` 只读 SPI；
- Economy 仍只持有经济投影（ClassRow/需求/债务/生产参与），键 = `HouseholdId`；Culture/Religion/Gov 未来各自持有自己的关系表/facet，键 = `HouseholdId`；
- Actor **不是阶层**：它是持有账户/商品/货币/资产的**经济主体身份层**（`ActorRef` + `ActorKind` + `GoodsAccount`）。阶层是 Economy 的 `SocialClassId`/`ClassPosition`/`ClassStanding`/`ClassRow.view.stratum`，是**可变的视图/位置**，不是稳定账户身份；
- 账户主体必须是稳定身份（ActorRef），因为家户会改变阶层、改变生产方式、迁移；若拿阶层当账户键，阶层一变账就乱。庄园/作坊/政府/组织也需要账户，它们并没有“阶层”。

因此本文件原来的 H2/H3 表述作废，改为 **H1.5**：
- Social 拥有 `Household` + `PopulationGroup` + `PopulationAssignment`（归属/角色关系）；
- `simos-people-api`（或等价低层契约）只放 ID/角色/只读 SPI，**不放状态**；
- Unit/Economy/Gov/Culture/Religion 通过契约与只读 SPI 引用同一批人，不再各存一本头部账。

### 10.6 Actor 与阶层的职责边界（写死）

| 概念 | 归属 | 性质 |
|---|---|---|
| `ActorRef` / `ActorKind` | actor-api | 跨域稳定主体身份（谁持有） |
| `GoodsAccount` | actor | 商品/货币/资产账（持有什么） |
| `Household` / `PopulationGroup` / 归属关系 | social | 人是谁、组成什么群体、属于谁 |
| `SocialClassId` / `ClassPosition` / `ClassStanding` / `ClassRow.view.stratum` | economy | 阶层位置与当前视图（可变的） |
| `ProductionMode` / `ClassStructure` | economy | 生产方式与位置目录 |
| 文化/宗教关系 | 未来 culture/religion 模块 | 各自关系表，键 = `HouseholdId` |

## 11. 待裁定

1. 家户/人口域：按用户修订走 **H1.5**（Social 拥有 household/population/assignment；低层 `simos-people-api` 只放 ID/角色/SPI；不另起人口域、不拆 Social）。
2. `HouseholdId`/`PeopleLotId` 迁移到 `simos-people-api`（推荐）还是继续沿用 `economy-api`？
3. primary/secondary 语义：政府家户与政府单位谁拿 PRIMARY（C1 vs C2）。
4. Unit `manpower`：直接改为投影，还是先保留缓存 + 对账。
5. Gov `staff`：same。
6. 招募规则：从 Region / 家户 / 单位怎么抽；是否消耗钱粮；是否有征募上限/批复。
7. 家户消费与粮饷：按 primary 还是按角色投影计算。
8. 旧档：重置（推荐）还是一次性迁移。
9. 日志分类：新增 `.population.assignment` 还是沿用 `.population`。
10. Actor 职责：保持“账户/持有主体”身份层，不引入阶层语义；阶层继续归 economy。
