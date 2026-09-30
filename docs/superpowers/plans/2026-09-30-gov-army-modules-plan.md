# GOV / Army 模块（阶段 9+）—— 用户裁定与开发计划（2026-09-30）

> 用户原话（本轮裁定，连读）：
> 「当务之急是把 Army 和 Nation 模块做出来；首先是重头戏 Nation 模块……单个无子类的 Nation 单位（我想设定的语义是古代的省政府），
> 或者说单个单位的 Nation 属性字段，其首先要拥有特定类型的人员，也就是行政人员，供养能干活的行政人员需要粮食、纸张等，总之记在这个单位的
> 每 tick 消耗上；其次，行政人员会行政，一定规模的行政人员产生覆盖固定 hex 的行政效率，其中影响因素，在 GOV 这头有暴力机器人数、
> 文员人数，对应管辖区 hex 的治安需求、文书记录需求（这个得根据 hex 算），如果不满，要么是税收不上来，要么是扣当地市场流通性等数值之类的；
> 所以说单个 GOV 单位，要给不同的人员上能被 GOV 读取的 tag；如果辖区内行政需求满足，但是治安员/文员相对更多，那为其带来指数级递减的行政效率加成，
> 例如正好满足能提升 0 市场活力/税收效率，超支 10% 能提升 5%，超支 20% 只能提升 6.7% 之类的；军队就相对简单多了，一个被打上军队模块负责标签的单位只需要
> 认一个 GOV 模块当主子、Army 代码模块自动在每一 tick 为其更新"辖区"，其实就是军队视野——本质上都是可以管到的区域，GOV 叫辖区，Army 叫视野。」
>
> 追加裁定（同一轮问答，逐条）：
> 1.「我理解的 Units 模块是只提供这个单位的成分结构、所处位置、如何移动等属性，而不提供这个单位的具体力量计算，一个政府班子的行政力和
>    一支军队的战斗力是一个维度的吗？肯定是要分开算」；2.「就是分开算，派出所警察有个屁的作战能力……还是不能一起算」；
> 3.「中央政府下令，地方完全可以已读不回/抗令嘛？中央实际拥有的强制力只有军队和神秘的外派调查组，这样的自由度和灵活性要实现……
>    调查组可以设定为，只有移动状态没有额外状态的纯人员单位，如果要搞武装调查组，事实上就是下令训练一支小军队，所以还是应该走通用接口，
>    而不是额外搞一个什么'调查组'类型的单位出来」；
> 4.「中央行政单位默认只读直辖；地图上显示的 GOV 区域，是这个中央统领的所有名义归属地方的集合」；
> 5.「0 那就不搞额外的产品，直接记 GOV 需要多少粮食和其他已有物资，这总能行吧？」；
> 6.「传令载体……可以先不算，最多给 GOV 再下挂一个通讯人员，不拆成额外可移动单位——因为这么算太麻烦了，其速度算下来可能也可以在 1tick 里
>    往返多次，如果真要模拟时间差，那直接让 GM 拖几 tick 发令不得了」；
> 7.「实际拥有行政职能的政府单位用 Gov 字段，Nation 字段是一个方便显示/后续外交功能的总概括」；
> 8.「Army 我从来没要求算战力，只是根据 GOV 的这个包对应语义，把 Nation 一起拆除去而已——鬼知道后续要不要完善？拆除去总是对的」；
> 9.「simos-gov 和 simos-army，谢谢」；
> 10.「没有数据就没有了，需要计算这个地区政府力度的时候再补也不迟！」。
>
> 性质：**实施计划**（每阶段一个写代码代理、只编译不写测试；控制方审后提交推送；测试在收尾期统一补，AGENTS.md §三.0）。

---

## 0. 裁定速览（本计划的地基）

| # | 裁定 | 对实现的影响 |
|---|---|---|
| 1 | Unit 只提供**成分结构/位置/移动**，不算力量 | `Unit` 的编制字段只放人员/角色/政策；任何"力量值"不落 Unit |
| 2 | 行政力与战斗力**分开算**；衙门（治安编制）不计战力 | `simos-gov` 与 `simos-army` 两套算法互不引用；结构性测试钉住 |
| 3 | 一单位**至多一个**编制标签 | `Optional<UnitModule>`（sealed `Gov | Army`），互斥由类型保证 |
| 4 | 传令**不拆可移动单位**；最多 GOV 编制内加**驿传**；时间差由 GM 拖 tick | 无信使单位、无 effect 投递；`StaffRole.POST` 只是编制角色 |
| 5 | 实际行政实体用 **Gov 字段**；**Nation = 显示/外交总概括** | 中央也是 GOV 单位；`NationSummary` 是**派生只读视图**，不是行政状态 |
| 6 | Army **不算战力**，只把 `Nation` 依赖拆掉 | `simos-army` 一期只做编制/隶属/视野派生；`sd.Army` 去 `nationId` |
| 7 | 不新增产品；GOV 消耗 = **粮食 + 其他已有物资** | 用 grain / cloth / money；`levyRegion` 补 cloth 一路；缺料只发信号 |
| 8 | 无数据就没有；不做迁移兼容 | `Jurisdiction.administrationPerMille` 退役；无 GOV ⇒ 无行政数据、不征 |
| 9 | 模块名 = `simos-gov` / `simos-army`；编制类型在 unit、算法在模块、每 tick 读数在 gov 片 | 分层见 §2 |
| 10 | GOV 默认**必须绑决策人**；调查组/科举子单位 = **无标签纯人员 Unit**；退休待遇 = 政策支付项 | `Affiliation.Gov` + 创建期强绑；人员流转走通用批模式 |

**非目标（明确挂起）**：战斗力计算与自动战斗结算；市场撮合/市场活力；纸/文化等新商品与工坊生产；信使单位与投递时间模拟；
退休官员的身份阶层；多级（县/乡）政府；外交动作本身。

---

## 1. 现状接缝（实测，逐条有据）

| 面 | 现状 | 约束 |
|---|---|---|
| `Unit` | 15 组件，`Optional<Jurisdiction>`；拷贝点漏传=静默丢字段（反射守卫把守） | 第 16 组件要走完整兼容流程：构造器 9/13/14/15/16、codec、全部拷贝点、守卫更新 |
| 时间推进 | 三参与者：`UnitTimeParticipant`(unit)、`SdTimeParticipant`(sd)、`ClassFirstPopulationEconomyTimeParticipant`(economy/social/actor)；`TimeProposalResolver` 同名模块 = **拒整次推进** | "每 tick 变"的 GOV 读数不能写在 unit；必须放独立 gov 片，由已有 actor 写者代写 |
| actor 国库 | `ActorRef(UNIT, unitId)` 的 `GoodsAccount`（任意 `CommodityId` 余额 + 货币 + 冻结）；`actor.AdjustAccounts` 已具备有符号净增量、原子、不侵冻结 | GOV 消耗支付有现成原语 |
| 物资 | classfirst **活产出只有 grain + cloth**（cloth 是唯一非必需维度）；actor 账可持有 grain/cloth/money；fiber/iron 只在创世种了一笔（无产出）；tool/wood/land **actor 账没有维度**（writeback 具名 gap） | GOV 日常消耗只能用 **grain/cloth/money**；用其它=永久 shortfall 假账 |
| 征收 | 日税收 grain+silver；`simos.unit.levyRegion` 只支持 grain/money | 若 GOV 消耗 cloth，需给 levy 补 cloth（`RegionAllocations` 已通用，改动小） |
| 信号 | `HexCrisisSignal{hex,kind,severity,day,evidence,...}` 已有表（7 种 Kind，暂无生成者） | 缺料/缺员 → 写信号即可；新增 Kind 是小改 |
| 市场 | `Market{numeraire,prices}` 只有价格；旧撮合 `MarketSettlement` 已删 | "扣市场流通性"无消费者；本计划不碰 |
| `economy.AddDemand` | handler 在，但 **classfirst 世界被具名拒**（结算不读 demands）；且**没有配套工具** | 本计划不走它；GOV 消耗记账自给 |
| 决策人 | `Affiliation` sealed(Nation/Army) + `DecisionScopeFunctions` 注册表（未注册=响亮抛）；`AdjudicateTickTool` 按**出令者可达面**逐命令授权 | 加 `Affiliation.Gov` + `GovScope` = 注册表一行；中央指挥不动省资源是**既有机制**，无需新规则 |
| sd 战略层 | `sd.Nation(nationId,name,homeRegion,adminBudgetPerTick)`（budget 死旋钮）；`sd.Army(armyId,nationId,rootUnit,name)` | `adminBudgetPerTick` 退役；`sd.Army` 去 `nationId`、改认 `masterGov` |
| 文档 | `DecisionDoc`（`sd:doc.*`）+ `sd.PutInfo`（GM 写）；决策人不可嵌 `sd.*` | 上级"命令"/调查报告 = GM 写的文档；发送时间差由 GM 拖 tick |
| 跨片先例 | `SdCommandDrain`：参与者只写自己片，跨片动作推进后经 submit 落 revision（**非原子**） | 本计划不用它做 GOV 日结（并入 actor 写者，保原子） |

---

## 2. 目标架构

### 2.1 Unit 通用化：编制模块（`simos-unit`）

```java
public record Unit(
    UnitId id, String name,
    SegmentedSeries<Optional<UnitId>> parent,
    SegmentedSeries<Optional<HexCoord>> position,
    int member, Map<String, Integer> equipment, int speed, int mobilityPerMille,
    Optional<Movement> movement, UnitStatus status,
    SegmentedSeries<Boolean> attached, SegmentedSeries<Optional<RelativeOffset>> offset,
    Optional<UnitId> rejoinTarget, int visionRadius,
    Optional<Jurisdiction> jurisdiction,
    Optional<UnitModule> module) { ... }

@JsonTypeInfo(use = Id.NAME, include = As.PROPERTY, property = "@class")
@JsonSubTypes({@Type(GovFormation.class, name = "gov"), @Type(ArmyFormation.class, name = "army")})
public sealed interface UnitModule {

  record GovFormation(
      Map<StaffRole, Long> staff,          // SCRIBE（书吏）/ YAMEN（衙门）/ POST（驿传）；值 ≥ 0，保序不可变
      OfficePolicy policy,                 // 定额/上限/退休待遇
      Optional<UnitId> superiorGov,        // 层级：中央为空；多数省直接指中央
      GovLevel level                       // CENTRAL / PROVINCE
  ) implements UnitModule {}

  record ArmyFormation(
      Optional<UnitId> masterGov,          // 认领的 GOV（指挥/视野关系）；旧档缺键 ⇒ empty
      String role                          // 兵种/职责短名，词表后置
  ) implements UnitModule {}
}

public enum StaffRole { SCRIBE, YAMEN, POST }
public enum GovLevel { CENTRAL, PROVINCE }
public record OfficePolicy(
    long grainPerStaffPerTick,      // 默认 = RATION_MILLI_PER_PERSON（复用既有常量）
    long clothPerStaffPerCycle,     // 默认 = CLOTH_MILLI_PER_PERSON，按周期折日
    long moneyPerStaffPerTick,      // 俸禄，默认 0
    long retirementPerStaff,        // 退休/遣散一次性安置，默认 0（Q11：待遇由决策人政策定）
    Map<StaffRole, Long> staffCap   // 编制上限，GM 可调
) {}
```

- **不变量**：旧档缺 `module` 键 ⇒ `Optional.empty()`；构造器矩阵 9/13/14/15/16；所有生产拷贝点原样带过（照 `jurisdiction` 的先例，反射测试逼红）。
- **Unit 不出现任何"行政效率/战力"字段**（裁定 1/2）。

### 2.2 `simos-gov`（行政模块）

- **依赖**：`simos-util` + `simos-map` + `simos-unit` + `simos-social` + `simos-economy-api`（只用 id 与 ActorRef；**不依赖 app/core**）。
- **状态切片 `gov`**（每 tick 读数；编制慢变事实在 Unit）：
  ```java
  public record GovState(Map<UnitId, GovOfficeState> offices) {}
  public record GovOfficeState(
      Map<String, Long> lastAssessed,   // "grain"/"cloth"/"silver" → 量（毫）
      Map<String, Long> lastPaid,
      Map<String, Long> lastShortfall,
      long securityCoveragePerMille,    // 0..1000（衙门 vs 治安需求）
      long paperworkCoveragePerMille,   // 0..1000（书吏+驿传 vs 文书需求）
      long efficiencyPerMille,          // 用于税：coverage ≥0，加成 ≤ +10%
      long bonusPerMille,               // 超编加成（0..100）
      long tick) {}
  ```
  - 合法性：所有值 ≥ 0；`efficiencyPerMille ≤ 1100`；键 = UnitId 且单位确有 `GovFormation`（跨表守卫）。
- **纯函数**（`simos-gov`，不碰 app）：
  - `GovDemand.of(map, social, unit) -> Map<HexCoord, HexDemand{security, paperwork}>`
    - `securityDemand(hex) = ceil(population(hex) / SECURITY_PER_OFFICER) + cityWeight(hex)`
    - `paperworkDemand(hex) = ceil(population(hex) / PAPERWORK_PER_SCRIBE) + cityWeight(hex)`
    - 常量与默认值放 `GovRules`（single spelling point）；城市权重取 `SocialData.cities()`/`City.at()`。
  - `GovEfficiency.of(roster, demand) -> {coverage, bonus, efficiency}`
    - 覆盖：`min(1, Σ供给 / Σ需求)` 按辖区 hex 汇总（衙门对治安、书吏+驿传对文书）；
    - 加成（仅当覆盖 = 1）：`surplus = (供给 − 需求)/需求`；`bonus = 0.1·surplus/(surplus+0.1)`（上限 10%，精确拟合 +10%→5%、+20%→6.67%）；
    - `efficiency = coverage × (1 + bonus)`（≤ 1.1）。
  - `GovDaily.settle(govState, unitState, map, social, actor, tick) -> (GovState', 支付/信号计划)`
    - 消耗：`assessed_g = Σ_staff staff × policy.rate[g]`（g ∈ grain/cloth/money，cloth 按周期折日）；
    - 支付：从该 GOV 单位国库 actor 账扣（`actor.AdjustAccounts` 语义，不侵冻结；缺账/不足 ⇒ 记 shortfall，**不静默**）；
    - 缺料/缺员：产出 `HexCrisisSignal`（治安/文书/补给三类，Kind 词表按 §3 的裁决补），**不自动扣市场**；
    - 退休/遣散：`DismissStaff` 时按 `policy.retirementPerStaff` 从国库一次性支付（支付不足 ⇒ 记 shortfall）。
- **命令与工具**（★ 控制方实施修订 2026-10-01：编制字段在 `Unit.module`（unit 片），单条命令只能写自己命名空间 ⇒ 命令一律 `unit.*`，工具面仍叫 `simos.gov.*`）：
  - `unit.SetGovPolicy`（定额/上限/退休待遇）
  - `unit.RecruitStaff`（roster+，载荷带**逐来源**：社会批次或人口单位）
  - `unit.DismissStaff`（roster−，按政策支付退休待遇）
  - `unit.SetGovSuperior`（层级；中央为 null）
  - （阶段 10a 已落：`unit.SetGovFormation` / `unit.SetArmyFormation`；`gov` 切片无命令，阶段 11 由 participant 写读数）
  - 工具批（app 组合，一条 revision）：招募 = `social.SeedGroups`（扣人）+ `unit.RecruitStaff`（入编）+ `sd.PutInfo`（记录）；
    建 GOV = `unit.CreateUnit`（或对既有单位 `unit.SetModule`）+ `sd.CreateDecisionMaker{Affiliation.Gov(unitId)}` +
    `sd.SetDecisionMakerAccess` + `sd.PutInfo`——**GOV 必须带决策人**（裁定 10）。
- **范围（scope）**：
  - `GovScope`：**直辖**——自己所在格 + 本级 `Unit.jurisdiction` 的 Region hex 集 + 对应 `map:<mapId>/region/<rid>` + 本级单位前缀；
  - `GovTerritory`：**名义全境**——沿 `superiorGov` 聚合下级 GOV（及再下级）的 jurisdiction 并集；**只给 GUI/显示与 NationSummary，绝不进权限判定**（裁定 4）；
  - 注册：`DecisionScopeFunctions.defaults()` 加 `Affiliation.Gov.class -> GovScope.INSTANCE`。

### 2.3 `simos-army`（军事模块，一期最小）

- **依赖**：`simos-util` + `simos-map` + `simos-unit`（不依赖 economy/social/core）。
- 一期只做三件：
  1. `ArmyFormation` 的构造期校验与读取辅助（认领 GOV 存在性由命令期经 unit 切片判）；
  2. **视野辖区派生视图**：`effectivePosition + visionRadius` → hex 集（`HexGrid.withinRadius`），**不落盘**（裁定 6/原 Q10）；供 GUI/读工具/决策人范围参考；
  3. 认领/解除主子：`army.AssignGov` 命令 + GM 工具（批内改 `ArmyFormation.masterGov` + `sd.Army.masterGovUnit` 同步）。
- **不算战力**（裁定 6）：`simos-army` 不产出 combat power；sd 的战斗仍由决策人/GM 按 `CombatStage` 裁定。后续若要完善，新增 `ArmyPower` 纯函数 + 读工具即可，不动本计划的形状。
- **拆除 Nation 依赖**：`sd.Army` 的 `nationId` 改为可选/废弃，新增 `masterGovUnitId`（旧档缺键 ⇒ empty，兼容构造器保留旧调用点）；`ArmyScope` 不变（它本就只看 `rootUnit`）。
- 移动仍归 unit 通用（裁定 1）。

### 2.4 Nation = 显示/外交总概括（派生）

- `NationSummary`（`simos-gov` 只读视图）：`{ nationKey, displayName, centralGovUnit, List<UnitId> govUnits, Set<RegionId> nominalRegions, long totalPopulation }`。
- 生成：按 GOV 层级找 `level=CENTRAL` 的根（其下所有 GOV 归它）；**纯派生，零新状态**；GUI/地图显示用它（"GOV 区域 = 名义归属集合"）。
- `sd.Nation`（政治实体）暂不动：留作后续外交/决策归属；`Affiliation.Nation` 与新 `Affiliation.Gov` 并存（中央行政用 Gov，政治/外交用 Nation，按用户裁定 5/7）。

### 2.5 每 tick 结算与税耦合

- 执行者：并入 `ClassFirstPopulationEconomyTimeParticipant` 日循环（actor 唯一写者；一笔推进 revision 原子；`WorldTimeProposal` 多一片 `gov`）。
- 次序（每天）：结算 + actor 写回 → **GovDaily.settle**（消耗/支付/信号/读数）→ 人口学 → 只读投影。
- 税：`JurisdictionDailyTax` 的 admin 改为读**该单位 GOV 的 `efficiencyPerMille`**（无 GOV ⇒ 0，不征，裁定 8）；`administrationPerMille` 退役（从 `Jurisdiction` 删除或标 deprecated 且不再读，实施时按"删除+旧档缺键默认"处理）。
- 物资：日税/levy 仍收 grain/money；`simos.unit.levyRegion` 增加 `cloth?` 一路（`RegionAllocations` 通用；`actor.AdjustAccounts` 通吃 CommodityId）。
- 信号：`HexCrisisSignal.Kind` 增加 `ADMIN_SECURITY`（治安不足）、`ADMIN_PAPERWORK`（文书不足）、`ADMIN_SUPPLY`（行政物资/俸禄不足）；只发信号，不扣市场（裁定 5/10）。

### 2.6 人员流转（全部走通用批模式）

- **招募**：从辖区内社会批次（`SocialData.groups()`，MALE+ADULT 瀑布，复用 `RegionAllocations`）或从人口单位扣人 → `unit.RecruitStaff` 入编；来源逐条进载荷与 `sd.PutInfo`。
- **科举（用户原话场景）**：省决策人组织考试（GM 裁定录取名单）→ 从家户/批次选出 N 人 → **无标签纯人员 Unit**（`unit.CreateUnit`，member=N）→ `unit.PlanRoute` 移动至中央人口容纳单位 → 中央用 `unit.ApplyCasualties(减员)` + `unit.RecruitStaff`（来源=该单位）吸收，最后 `unit.DisbandUnit`；一条 revision 一批。
- **调查组**：同样是无标签纯人员 Unit（高速 `speed`），`gov` 只负责"从编制里出人"（roster−）与建单位；结果由 GM 按移动路径写成 `DecisionDoc` 给决策人（引擎不做自动情报）。
- **武装调查组**：即给该单位加 `ArmyFormation`（训练小军队），走同一通用接口（裁定 10）。
- **离编/退休**：`unit.DismissStaff` roster−；按 `policy.retirementPerStaff` 从国库支付；人员回写社会批次（指定 hex/批次，或记具名缺口——一期"不自动找地方塞"）。
- **驿传**：`StaffRole.POST` 计入文书覆盖（与 SCRIBE 同口径），不产生任何可移动单位；命令/信息的时间差由 GM 拖 tick 表达（裁定 4）。

---

## 3. 公式与默认口径（实施时按此，若与预期不符再单裁）

| 项 | 默认 |
|---|---|
| 治安需求/格 | `ceil(population / SECURITY_PER_OFFICER) + (cityOnHex ? CITY_SECURITY_WEIGHT : 0)` |
| 文书需求/格 | `ceil(population / PAPERWORK_PER_SCRIBE) + (cityOnHex ? CITY_PAPERWORK_WEIGHT : 0)` |
| 供给 | 治安 ← `YAMEN` 人数；文书 ← `SCRIBE + POST` 人数；**互不通用** |
| 覆盖 | `coverage = min(1000, 供给×1000/需求)`（需求 0 格记 1000） |
| 加成 | 仅 `coverage=1000` 时：`bonus‰ = 100·s/(s+10)`（s = 超支百分数，如 s=10 ⇒ 50‰、s=20 ⇒ 66.7‰、上限 100‰）；否则 0 |
| 效率 | `efficiency = coverage × (1 + bonus)`（≤ 1100‰） |
| 税 | `attainable = assessed × efficiency / 1000`（无 GOV ⇒ 0） |
| 口粮 | 复用 `EconomyVocabulary.RATION_MILLI_PER_PERSON`（单一拼写点） |
| 行政物资 | 默认复用 `CLOTH_MILLI_PER_PERSON` 按 `CLOTH_CYCLE_DAYS` 折日；俸禄默认 0 |
| 常量位置 | `GovRules`（simos-gov，只此一处）；未来 GM 可调时再开 `gov.SetTuning` |
| 缺料 | 只记 `lastShortfall` + 发 `HexCrisisSignal`，不自动扣市场、不自动裁人 |

---

## 4. 阶段计划（阶段 9–13）

> 每阶段：**一个写代码代理**，只做该阶段范围；门禁 = `spotless:apply` + 指定 `compile`；**不写测试**；控制方审 diff 后提交推送。
> 测试统一在阶段 13B 补（各阶段自己那波判据），变异自证只在阶段 13B 对四类关键项做。

### 阶段 9：Unit 通用化（编制模块）
- 产出：`UnitModule` sealed + `GovFormation/ArmyFormation/StaffRole/GovLevel/OfficePolicy`；Unit 第 16 组件；9/13/14/15/16 参构造器矩阵；codec（`@JsonTypeInfo` 子类型 + 旧档缺键 ⇒ empty）；全部生产拷贝点；`Unit` 反射守卫同步。
- 门禁：`spotless:apply`；`compile -pl simos-unit -am`；`compile -pl simos-app -am`。
- 验收输入（阶段 13B）：16 组件逐名/构造器；旧档缺 `module` 往返；拷贝点不丢失（变异靶子）；GOV/Army 互斥（sealed 不能同时）。

### 阶段 10：`simos-gov` 骨架（切片 + 命令 + 决策人绑定 + 范围）
- 产出：新模块 `simos-gov`（pom + `GovState/GovOfficeState` + codec + change set + 跨表守卫）；`GovRules`；
  `Affiliation.Gov` + `GovScope` + `GovTerritory` + 注册表一行；
  **10a（已完成）**：`simos-gov` 切片骨架 + `unit.SetGovFormation`/`unit.SetArmyFormation` 两条立编制命令与 GM 工具；
  **10b**：`unit.SetGovPolicy`/`unit.RecruitStaff`/`unit.DismissStaff`/`unit.SetGovSuperior` 四条命令与 handler；
  app 工具批：`simos.gov.createOffice`（建 GOV 单位 + 同批绑决策人 + 访问范围 + 文档）、`simos.gov.recruit`、`simos.gov.dismiss`、`simos.gov.setPolicy`；
  `Shell` 注册（codec/handler）；`CatalogTool.PAYLOAD_HINTS` 同步。
- 门禁：`spotless:apply`；`compile -pl simos-gov -am`；`compile -pl simos-app -am`。
- 验收输入：切片往返；GOV 必绑决策人（缺则创建期拒/运行期具名 gap）；`GovScope` 直辖；`GovTerritory` 名义聚合（不进权限）；招募来源保真与守恒。

### 阶段 11：每 tick 行政结算 + 税耦合 + 物资/信号
- 产出：`GovDemand/GovEfficiency/GovDaily` 纯函数；并入 `ClassFirstPopulationEconomyTimeParticipant`（gov 片 + actor 支付，原子）；
  `JurisdictionDailyTax` 改读 GOV efficiency；`administrationPerMille` 退役（删除/停读 + 旧档兼容）；
  `simos.unit.levyRegion` 补 `cloth?` 一路；`HexCrisisSignal.Kind` 补三类；日志汇总（照长期税一条）。
- 门禁：`spotless:apply`；`compile -pl simos-app -am`；`compile -pl simos-economy -am`。
- 验收输入：需求/覆盖/加成逐值（含 +10%→5%、+20%→6.67%）；支付守恒（国库减少==roster 消耗==审计）；缺料不侵冻结、只发信号；
  无 GOV 世界逐字节不变；`administrationPerMille` 不再被任何生产路径读取。

### 阶段 12：`simos-army`（最小）+ 拆 Nation + NationSummary
- 产出：新模块 `simos-army`（`ArmyFormation` 校验辅助 + 视野辖区派生视图 + `army.AssignGov` 命令/handler + GM 工具）；
  `sd.Army` 去 `nationId`、改 `masterGovUnitId`（旧档缺键 ⇒ empty，兼容构造器保旧调用点）；`ArmyScope` 不动；
  `NationSummary` 只读视图（中央根 + 名义区域 + 人口汇总）；GUI 读口（如 `simos.map.overview` 增补或新读工具）后置到阶段 13 的机械回归/场景验收里一并测。
- 门禁：`spotless:apply`；`compile -pl simos-army -am`；`compile -pl simos-app -am`。
- 验收输入：Army 认领/解除主子；视野派生（位置+半径，不落盘）；旧档 `Army` 兼容；NationSummary 与层级一致、**不参与任何授权判定**。

### 阶段 13（合并原 13–15）：人员流转 + 机械回归 + 真 LLM 多决策人协作验收

> 用户裁定：13–15 都是针对具体功能场景的验收，**不是测经济**；需要的是**多开几个决策人、观察其协作是否符合要求** ⇒
> 合并为「针对新开发内容的**真 LLM 具体内容测试**」。不做 360 tick 经济长跑。

**A. 人员流转实现**（先做）
- `gov` 侧出人/收人原语与工具批：`simos.gov.dispatchTeam`、`simos.gov.absorbUnit`；科举两段（省选人 → 无标签子单位移动 → 中央吸收）；
  调查组 = 无标签 Unit；武装调查组 = 加 `ArmyFormation`（通用接口）；退休待遇政策 + 离编回写（回写指定批次或记具名缺口，不静默）；
  调查结果 = GM 用既有 `sd.PutInfo`/`DecisionDoc` 按调查组**移动路径**写文档，引擎不做自动情报。
- 门禁：`spotless:apply`；`compile -pl simos-app -am`。

**B. 机械回归（仓库卫生，必须有）**
- 四波测试（照阶段 5–8 模式）：1) Unit（16 组件/构造器/旧档/拷贝点/互斥）；2) `simos-gov`（切片往返、需求/覆盖/加成逐值、
  scope/决策人绑定、招募/离编守恒）；3) 结算与税（GovDaily 并入 participant 的每日守恒、缺料信号、无 GOV 恒等、levy cloth）；
  4) Army/Nation（认领、视野派生、旧档兼容、NationSummary 显示与权限隔离）+ 人员流转（科举/调查组/退休）。
- **变异自证四类**（仅这四类）：守恒式、不丢失（`UnitModule` 拷贝点/招募来源）、静默付 0（缺料必须信号）、断粮（无粮 → 信号/效率下降）。
- 镜像同步：`Unit` 反射守卫、MCP 工具/命令计数、`PAYLOAD_HINTS`、`AdjudicateTick` 白名单与目标样本。
- 门禁：全仓 `clean verify`（模块测试 + SpotBugs + Checkstyle + Spotless + 前端 297）。

**A 的定位（用户裁定 2026-10-01）**：A 的四类人员流转（科举 / 调查组 / 吸收 / 退休回写）**不作为独立机械验收**；
它们是 C 里**交给决策人的任务目标**——把测试目的事先告诉三国中央决策人，让它指挥下属地方政府去做。
为保证测试推进：**若中央决策人在若干轮内不作为，GM 把首都人口砍半**（GM 侧用 `social.SeedGroups`/`social.SetPopulation` 实施并留文档），
让决策人看到后果后再观察其是否开始行动。A 的产出 = 四个可被 GM 代执行的组合工具（`simos.gov.selectExaminees`/`dispatchTeam`/`absorbUnit`/`retireStaff`）。

**C. 真 LLM 多决策人协作验收（本阶段主体，合并原阶段 15）**
- 场景世界：紧凑三国 classfirst，每国 1 个 CENTRAL GOV + 2–3 个 PROVINCE GOV + 若干 Army 单位，各自绑定真 provider 决策人
  （`mosire-flash`）；GM 由测试脚本/控制方担任。
- 场景清单（逐条观察，**只钉可观察行为，不钉模型具体文本**）：
  1. **命令不强制服从**：中央出令/发文 → 省决策人自己决定照办/已读不回/抗令；中央命令越界（指向省资源）在 `AdjudicateTick` 必拒；
  2. **编制与效率**：省决策人读 GOV 读数后自主招募书吏/衙门/驿传 ⇒ 覆盖率/加成按公式变化；超编加成的递减可被观察到；
  3. **缺料/缺员信号**：只发信号；决策人自己决定扩招/加税/降定额/削编，下一轮观察恢复或继续恶化（都由决策人负责）；
  4. **科举链路**：省选人 → 无标签子单位移动 → 中央吸收（member 守恒、来源可追溯、一条 revision）；
  5. **调查组与信息**：派出调查组 → GM 按路径写 `DecisionDoc` → 中央/省决策人依据文档行动（信息**不**经数据层直读）；
  6. **Army 认领**：Army 认领 GOV、视野派生范围、跨辖调动；中央只能指挥真正认领它的 Army（认领关系可被决策人/GM 调整）；
  7. **范围两层**：中央只读直辖（读不到省数据）；名义全境只出现在显示面（NationSummary）；越权读/写必拒；
  8. **审批链**：`sd.IssueDirective` 进审批、GM 裁决后执行、被拒命令的拒因进决策结果。
- 判据：每轮都有真轨迹/真 revision；越权必拒；不存在"自动服从"；编制/库存/人口在轮次间守恒或按政策单调；
  协作链路（令 → 文档 → 执行）可追溯；连续 ≥3 轮无 `TOOL_ERROR`/未捕获异常（依赖隔壁修好 thinking 模式回放）。
- 载体：把 env 门控的 `RealLlmUnitDecisionLoopTest` 扩成多 DM 场景 harness（逐轮轨迹矩阵 + 原始记录），不新增"模拟器"生产代码。
- 最终判据：`clean verify` 绿 + 真 LLM 多决策人场景矩阵通过；不达/阻塞项如实报告（不粉饰、不把"没跑"写成"通过"）。

---

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| Unit 第 16 组件破坏面（拷贝点/档/codec） | 照 `jurisdiction` 既有流程：兼容构造器 + 缺键默认 + 反射守卫 + 全拷贝点扫描（阶段 9 门禁 + 阶段 13B 测试波 1） |
| module clash（gov 读数每 tick 写） | gov 片由 population participant 代写（唯一 actor 写者），不另起参与者 |
| 无 GOV 世界行为突变（税不再征） | 用户已裁"无数据就没有"（裁定 8）；阶段 13B 测试波 3 显式钉"无 GOV ⇒ 不征"并写入计划留痕 |
| `administrationPerMille` 退役的兼容面 | 旧档缺字段按默认处理；生产路径零读取（阶段 11 门禁 + 阶段 13B 测试波 3） |
| GOV 未绑决策人 | 创建期强绑（阶段 10）；运行期缺失 ⇒ 具名 gap、不崩 |
| 真 LLM 验收不稳定（模型输出漂移） | 判据只钉可观察不变量（越权必拒/守恒/审批/轮次），不钉具体文本；env 门控 + 多轮矩阵；不达项如实报告 |
| 名义全境被误用为权限 | `GovTerritory` 不进任何授权判定（阶段 13B 测试波 2 结构性断言） |
| 通讯/传令被做成隐式时间模拟 | 明确不做投递载体；GM 拖 tick；POST 只是编制角色（裁定 4） |
| 战力被顺手实现 | 本计划明列非目标；`simos-army` 无 combat power 出口（测试波 4 断言模块不产出） |

---

## 6. 控制方纪律

- 每阶段一个写代码代理：只写该阶段生产代码，不写/不改测试，不 commit；
- 控制方审 diff + 提交推送；
- 测试在阶段 13B 统一做（AGENTS §三.0），四类变异自证只做一次；
- 每阶段报"未做/未验证"一节；不把"没跑"写成"通过"。
