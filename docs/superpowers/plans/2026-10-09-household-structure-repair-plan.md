# 2026-10-09 家户结构修复：Social 人口/劳动/需求唯一权威 + Economy 投影消费（开发计划 / 架构设计）

> 状态：**用户已确认方案 B；本文件开始作为施工依据。**
> 基线：`HEAD 3e41963c`（本文件初版提交后）；工作树干净。
> 上游裁定：见 `docs/superpowers/reports/2026-10-09-p2-population-economy-bug-investigation.md`
> 本文件把用户 2026-10-09 的新口径落成可执行的当前阶段计划。
>
> ★★ 用户确认后的追加口径：
> 1. **选 B：直接上年龄/性别分档默认值**，不做“先全档同值”的过渡；
> 2. **一切从新，旧有数据作废**：不为旧档做迁移/双读/兼容 shim，新字段缺键 = 旧档不可读是合法结果；
> 3. **Log 写全**：全局默认/家户覆盖/需求劳动展开/投影对账都要有可查日志；
> 4. **关键在于机制/算法过程正确**：数值是初值、可 GM 调；先保证逐成员求和、时间口径、舍入与守恒的机制对；
> 5. **文档改全**：本计划、调查报告、handoff、AGENTS 相关口径随实现同步更新，不留下旧说法。

## 1. 用户已确认的口径

1. **需求系数默认按年龄分档**；当前 `AgeBracket` 三档（`0-14 / 15-59 / 60+`）够用；
2. **需求系数必须区分性别**；否则 Social 单独维护人口、劳动力、需求表就没有意义；
3. 每个家户中各年龄段/性别的**各项产品需求可用 GM 工具修改**；有一个**全局通用默认值**；
4. **暂不做按收入等级/阶层变化的需求差异**；
5. 劳动力、需求都必须从 Social 家户成员结构**逐人统计求和**得出；
6. 需求不应平均；`HouseholdEconomyProjection` 里的旧“组内平均/加权分摊”是身份模型遗留，应改按 `HouseholdId` 1:1 对账；
7. Unit 可挂多个家户；Unit 决策人维护内部利益规则；经济账始终按家户分别结算；
8. 当前阶段先把“家户结构 bug”修掉：即 Social 真值 → 经济投影/需求/劳动的权威链。

## 2. 模块边界（本阶段确认版）

### 2.1 Social（`simos-social` / `simos-social-api`）

**人员/家户的唯一域**，拥有：

- 家户身份、位置、成员份额 `Household.members`；
- 人口批次 `PopulationGroup`：人数、性别、年龄、生理压力；
- 出生/死亡/生命事件与 `vitalRates`；
- **劳动力供给表**：按 `(年龄档, 性别)` 的每 tick 时间预算；
- **人口需求表**：按 `(年龄档, 性别, 商品)` 的每人需求系数；
- 全局默认表 + 家户级覆盖表；
- Social 工单 `SubmitHouseholdWorkOrder`：人员/家户属性的唯一变更受理口。

Social 继续禁止反向依赖 unit/economy/actor/sd/core 实现，防环。

### 2.2 Economy（`simos-economy` / `simos-economy-api`）

**生产、市场、债务、账务结算的域**，消费 Social 投影出来的：

```text
HouseholdId
  → population（人口摘要）
  → laborMilli（当日劳动时间预算）
  → naturalNeeds（当日逐商品需求）
```

Economy **不持有第二本人口/劳动/需求权威**：

- `HouseholdEconomy.population/laborMilli/naturalNeeds` 是**当日物化读模型**；
- 任何“从 population 反推需求/劳动”的公式都废弃；
- 经济侧不直接 import `SocialData`；跨域由 app 注入。

### 2.3 Actor（`simos-actor`）

- 账户/库存的唯一域；
- 账户键 = `HouseholdId`，一家户一本账；
- 不装人口/劳动/需求；
- 经济结算产生的资产/库存变化仍由 actor 落账。

### 2.4 Unit（`simos-unit`）

- `Unit.households` 是“谁在这个 Unit 里”的唯一关系列表；
- Unit 不持人口、不持账户、不持需求；
- `GovernmentFormation` / `ArmyFormation` 只放编制/角色/隶属；
- Unit 决策人维护“内部分摊规则”：哪些家户承担/获得什么；
- 经济侧只按 `HouseholdId` 分别执行，不把 Unit 压成一户、不做跨户平均。

### 2.5 App（`simos-app`）

**唯一同时看见 Social / Economy / Actor / Unit 的组合根**，负责：

- 从 Social 逐户逐成员展开 population / labor / demand；
- 注入 `EconomyDayStepper`；
- 把 Economy 结算产生的账户结果落回 Actor；
- Social 工单与经济命令的原子组合（必要时 `CommandBus.submitBatch`）。

### 2.6 Core（`simos-core`）

只做命令/变更集/时间推进编排，不认识任何领域类型。

### 2.7 边界图

```text
Social（人口/成员/年龄/性别/劳动力表/需求表）
    │
    │  app 逐户展开：population / labor / naturalNeeds
    ▼
Economy（生产/市场/债务/结算，消费投影）
    │
    │  账户会话绝对值/条目
    ▼
Actor（按 HouseholdId 的库存与货币）

Unit（households 关系 + 决策人内部分摊规则）
    └── 只影响“规则/关系”，不直接改人口或账
```

## 3. 数据模型设计

### 3.1 年龄与性别

- 年龄档：现有 `AgeBracket` 三档（`0-14 / 15-59 / 60+`）；
- 性别：`Sex`（`MALE / FEMALE`）；
- 需求键 = `(AgeBracket, Sex, CommodityId)`；
- 劳动键 = `(AgeBracket, Sex)`。

### 3.2 需求系数

```text
DemandCoefficient:
  amountMilli  — 最小计量单位（粮=毫粮、布=毫布）
  basis        — 时间口径：
                 PER_N_DAYS(n)   （粮：10,000 毫 / 人 / 120 天）
                 PER_CALENDAR_YEAR（布：1,000 毫 / 人 / 历年）
```

**不把 10,000/120 退化成“每人每天 83 毫”**：保留分子/分母或周期口径，逐日差分不丢残差。

全局默认：

```text
GlobalDemandTable:
  (AgeBracket, Sex, CommodityId) → DemandCoefficient
```

家户覆盖：

```text
HouseholdDemandOverride:
  HouseholdId → (AgeBracket, Sex, CommodityId) → DemandCoefficient
```

查找顺序：

```text
家户覆盖 > 全局默认
```

### 3.3 劳动力系数

现有 `HouseholdLaborTimeTable` 的默认值（未成年 4h / 成年男 16h / 成年女 8h / 老年 0h）保留，作为**全局默认**。

新增家户覆盖：

```text
HouseholdLaborOverride:
  HouseholdId → (AgeBracket, Sex) → 每 tick 毫小时
```

### 3.4 存放位置（关键边界决定）

- 需求与劳动是**人口属性**，归 Social；
- 但 `simos-social-api` 不许依赖 economy-api，而需求要带 `CommodityId`；因此：

```text
SocialData 增组件：
  globalDemandTable:        GlobalDemandTable
  householdDemandOverrides: Map<HouseholdId, HouseholdDemandOverride>
  householdLaborOverrides:  Map<HouseholdId, HouseholdLaborOverride>
```

- 不把这些字段塞进 `Household` record（避免 `Household` 的成员/位置/编码拷贝点爆炸）；用 `SocialData` 的旁表按 `HouseholdId` 关联；
- 经济仍只收到 app 注入的 `Map<HouseholdId, Map<CommodityId, Long>>`，不 import Social 的这些类型。

### 3.5 默认值策略（用户已选 B）

**直接上分档默认值；数值是初值，机制先做对。**

初始全局默认表（单位：粮=毫粮，布=毫布；劳动=毫小时/tick）：

| 年龄档 | 性别 | 粮 / 人 / 120 天 | 布 / 人 / 历年 | 劳动 / 人 / tick |
|---|---|---:|---:|---:|
| 0-14 | MALE | 6,000 | 600 | 4,000 |
| 0-14 | FEMALE | 6,000 | 600 | 4,000 |
| 15-59 | MALE | 10,000 | 1,000 | 16,000 |
| 15-59 | FEMALE | 9,000 | 1,200 | 8,000 |
| 60+ | MALE | 7,000 | 800 | 0 |
| 60+ | FEMALE | 7,000 | 900 | 0 |

初始值的依据（只作初值，不是不可改的裁定）：

- 粮：成年男 = 1.0 基准；成年女 0.9；未成年 0.6；老年 0.7；
- 布：成年男 1,000（= 现状口径）；成年女 1,200；未成年 600；老年男 800 / 老年女 900；
- 劳动：沿用现有 `HouseholdLaborTimeTable.DEFAULT`（未成年 4h / 成年男 16h / 成年女 8h / 老年 0h）；
- 全部值都可由 GM 工具改家户覆盖；全局默认也可由 GM 命令改。

**机制必须先定死（比数值更重要）**：

1. 逐成员展开：先按 `(AgeBracket, Sex, CommodityId)` 取覆盖 ?? 全局默认，再乘成员人数，**最后在家户层求和**；
2. 粮的时间口径 = 每人每 120 天，家户累计 = `Σ(成员人数 × 该成员系数) × days / 120`，**只在家户层取整一次**，每日值 = 累计差分；不允许逐成员/逐批次整除丢残差；
3. 布的时间口径 = 每人每历法年，家户累计走 `YearFraction.multiplyFloor(Σ(成员人数 × 系数))`，每日值 = 相邻日累计差分；
4. 劳动是“每人每 tick 毫小时”，直接逐成员求和，本身无时间分数；
5. 覆盖删除 ⇒ 回落全局默认；家户不存在/年龄档不合法/系数为负 ⇒ 具名拒，不静默给 0；
6. 旧档不作迁移：新字段缺键 = 旧档不可读是合法结果；新世界重建。

## 4. 计算与注入流程

### 4.1 Social 侧纯函数

```text
householdPopulation(h)
  = Σ_{lot ∈ h.members} count

householdLaborMilli(h, day, clock)
  = Σ_{lot} count × (家户劳动覆盖(h, bracket, sex) ?? 全局默认(bracket, sex))

householdNaturalNeeds(h, day, clock)
  = Σ_{commodity}
      Σ_{lot} count × (家户需求覆盖(h, bracket, sex, commodity) ?? 全局默认(...)) 的当日份额
```

### 4.2 App 侧展开与注入

`PopulationEconomyTimeParticipant` 每日循环前：

```text
populationByHousehold = social.householdPopulation(...)
laborByHousehold      = social.householdLaborMilli(..., day, clock)
needsByHousehold      = social.householdNaturalNeeds(..., day, clock)

stepper.updateHouseholdPopulation(populationByHousehold)   // 若投影已 1:1，可直接对账
stepper.recomputeLaborBudgets(laborByHousehold)            // 现有接口
stepper.updateNaturalNeeds(needsByHousehold)               // 新增接口
```

### 4.3 Economy 侧消费

- `EconomySettlement.withDailyNeed(population)` **废弃**；
- `step(day)` 使用注入的 `needsByHousehold` 作为 `naturalNeeds`；
- 市场自用保留、放贷/债务容量、饥荒、压力、迁移全部读同一份 `naturalNeeds` / `laborMilli`；
- 不再各自 `population × 人均定额` 算一遍。

### 4.4 投影修复（本阶段核心 bug）

`HouseholdEconomyProjection` 新算法：

```text
1. Social household id → population 1:1 映射；
2. 对每个 HouseholdEconomy：
     用 row.id() 直接查 Social 家户；
     找到 ⇒ 人口 = Σ members（现成投影），劳动按 Social 重新展开；
     找不到 ⇒ unresolved（具名：经济侧幽灵户）；
3. UNIT 家户：继续按现有 UNIT resolver 对齐视图，不并入 HEX 分组；
4. 零成员家户：按 id 保留 population=0，不再因“推不出 residence”整批失败；
5. 政府家户：按 id 对账；UNIT 位置由 resolver 处理；
6. 任何情况下都不做“跨户/跨阶层平均”。
```

验收读数：small-world 创世态的 `unresolved=0`；`HouseholdEconomy.population == Social household population` 逐户成立。

## 5. 需要废弃 / 降级的东西

### 5.1 彻底废弃

| 对象 | 原因 |
|---|---|
| `HouseholdEconomyProjection` 的 `(格,居住)` 分组 + 组内加权分摊 | 旧身份模型遗留；改为按 `HouseholdId` 1:1 |
| `EconomyVocabulary.dailyNeedsMilli(long population, ...)` 作为生产权威 | 只按总人口；改为按成员展开 |
| `EconomyVocabulary.dailyRationMilli / cumulativeRationMilli / dailyClothNeedMilli / cumulativeClothMilli` 的 `long population` 生产重载 | 同上 |
| `EconomySettlement.withDailyNeed(population)` | 改为消费 app 注入的 needs |
| `PopulationEconomyTimeParticipant.applyDailyStress` 的 `(hex, 居住类型)` 聚合 | 需求按户后，满足率/压力也必须按户/同需求组 |
| `PopulationDynamics` 的硬编码生育/死亡生产路径 | 与 `HouseholdBook.settleVitalEvents` 二选一；按家户表方向收敛到后者 |
| EconomySeeder/经济结算里“按年龄比例二次推断人口/需求”的部分 | Social 才是人口真值；economy 不再自己分人 |

### 5.2 降级（保留字段，但不再是权威）

| 对象 | 新语义 |
|---|---|
| `HouseholdEconomy.population` | Social 投影/派生摘要；不再用于反推需求/劳动 |
| `HouseholdEconomy.laborMilli` | app 注入的当日劳动预算物化结果 |
| `HouseholdEconomy.naturalNeeds` | app 注入的当日需求物化读模型 |
| `HouseholdEconomy.cycleNaturalNeedMilli` | 日结物化累加器，不是独立口径 |
| `EconomyVocabulary.RATION_MILLI_PER_PERSON / RATION_CYCLE_DAYS / CLOTH_MILLI_PER_PERSON` | 默认表的默认行数值，不再全局唯一口径 |

### 5.3 保留并扩展

| 对象 | 处置 |
|---|---|
| `Household.members` / `PopulationGroup` / `AgeBracket` / `Sex` | 人口真值，继续用 |
| `HouseholdVitalRates` | 家户级参数表模板；接入生产生死引擎 |
| `HouseholdLaborTimeTable` | 保留为全局默认；新增家户覆盖 |
| `LotMigrationBook` / `ModeMigrationPolicy` | 保留迁移/阶级转换；输入改读投影 |
| `HouseholdDemand`（市场侧有效需求） | 保留；底层 needs 改为成员展开结果 |
| `MarketSettlement` / `Debt*` / `DemandTargets` | 保留，但全部改读 `naturalNeeds/laborMilli` 物化值 |

## 6. 本阶段实施批次（确认后执行）

### Batch 0：设计确认

- 用户已确认 B：直接上年龄/性别分档默认值；旧数据作废；Log 写全；机制/算法正确优先；文档同步改全。
- 本文件即施工依据，下面 Batch 1–4 按此执行。

### Batch 1：Social 权威表

- `SocialData` 加全局默认表 + 家户覆盖表；
- 家户逐成员展开的纯函数（population / labor / needs）；
- GM 命令与窄工具：改/清家户覆盖；改全局默认（可后置）；
- `clean compile` 门禁。

### Batch 2：投影修复（核心 bug）

- `HouseholdEconomyProjection` 改 `HouseholdId` 1:1；
- 删分组 Average；空成员/UNIT/政府/流民具名处理；
- 新 smoke：`CLASSROW_POPULATION_PROJECTION_UNRESOLVED` 必须消失；
- 逐户 population 对齐探针。

### Batch 3：Economy 消费改造

- `withDailyNeed` 改为注入 needs；
- `applyDailyStress` 改按户/同需求组；
- 市场、债务、饥荒、迁移、政府发债全部切换；
- 删除 `population`-only 生产重载调用点；
- `clean compile` 门禁 + 365 天 smoke。

### Batch 4（本阶段收尾）

- GM 工具与读口：全局默认、家户覆盖、有效 needs/labor 读回；
- 审计 `householdEconomy.population()` 的全部读取点并分类；
- 跑真实 365 天 smoke，确认没有新的不一致；
- 测试迁移仍后置；本阶段只要求 compile + smoke 自证。

### 后续批次（不在本阶段）

- Batch 5：首日播种/口粮次序与初始库存；
- Batch 6：出生逐批次整除 + 生死引擎统一；
- Batch 7：小世界 GOV/Army 补建；
- Batch 8：通用周期库存增减 + Unit 决策人军俸规则；
- Batch 9：Economy → Social 工单（P9）；
- Batch 10：测试迁移 + `clean verify`。

## 7. 验收标准（本阶段）

1. `clean compile` 绿（`./mvnw -q -pl simos-app -am -DskipTests clean compile`）；
2. small-world 创世态 `HouseholdEconomyProjection.unresolved=0`；
3. 逐户 `HouseholdEconomy.population == Social.householdPopulation`；
4. 逐户 `naturalNeeds == Σ 成员 × 系数`，`laborMilli == Σ 成员 × 劳动系数`；
5. 365 天 smoke 无 `CLASSROW_POPULATION_PROJECTION_UNRESOLVED` 警告，且人口/劳动/需求读数与 Social 逐户一致；
6. 因为选 B，smoke 的粮食/市场/人口曲线与本调查基线不同是**预期差异**；必须能逐项解释差异来自分档系数，并给出逐户展开对照读数；
7. 测试迁移/`clean verify` 不在本阶段，但仍如实记为未做；
8. 文档同步检查：本计划、调查文档、handoff、AGENTS/依赖表中出现“需求按 population 统一口径 / 投影按 (格,居住) 平均 / 旧值保持”的旧说法，必须更新或追加取代说明。

## 8. 风险

- `SocialData` 是新组件，codec / ChangeSet / 不变量要同步；旧世界按既有“旧档报废、新世界重建”口径。
- “Social 拥有需求表”需要一个不引入 Maven 环的落点：需求表本体在 `simos-social`（已依赖 economy-api），经济侧只收注入结果。
- 消费者很多：`population()` 在 economy 主路径有数十处调用点，Batch 3 需要逐项审计，不能只改 `withDailyNeed`。
- 本阶段默认值若与现状同值，能隔离“结构修复”和“数值校准”；建议按此执行。
- 首日播种/口粮问题仍可能让 smoke 呈现饥荒；本阶段不修，但必须继续如实记录，避免把粮食问题误判为投影修复失败。

## 9. 已确认口径（取代上一轮待确认项）

1. **默认值策略 = B**：直接上 `(AgeBracket, Sex)` 分档默认值；初值见 §3.5，数值可 GM 调；
2. **一切从新**：不为旧档做迁移/双读/兼容 shim；新字段缺键 = 旧档不可读是合法结果；新世界重建；
3. **Log 写全**：全局默认/家户覆盖/需求劳动展开/投影对账都必须有可查日志；
4. **机制正确优先**：逐成员求和、时间口径、家户层一次取整、覆盖回落、具名拒绝，都要由探针与 smoke 证明；
5. **文档改全**：实现完成后同步更新本计划、调查报告、handoff、AGENTS 相关描述，旧说法标注被取代。
