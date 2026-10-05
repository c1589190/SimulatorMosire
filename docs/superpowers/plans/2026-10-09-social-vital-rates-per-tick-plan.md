# 2026-10-09 Social 每 tick 生死引擎 + 率表 + Unit 对接规划

> 状态：**用户已确认设计口径；GM/决策人工具后置；本文件作为施工与后续补账依据。**
> 基线：`HEAD c9186fd4`（30× 储备粮验证后）。
> 纪律：Social 是出生/死亡唯一域；外部模块只能调 Social 接口；旧数据作废；测试迁移仍后置。

## 1. 用户已确认口径

1. **出生率和死亡率统一归 Social 管**；其他模块不能直接改 `SocialData`，只能调 Social 接口。
2. 保持现有 `(AgeBracket, Sex)` 三档（`0-14 / 15-59 / 60+` × 男/女）。
3. **每 tick 计算**出生/死亡（不再 30 天月结）。
4. **日初结算**：在每个世界日、经济日结算之前先算出生/死亡。
5. **删除旧的基础死亡率传导**；`physiologicalStress` 与压力自动传导**一起删**。
6. 默认率表要给初始值（见 §2.2），后续由外部模块按社会问题调整。
7. 出生率、死亡率、直接加减人口都必须可由 Social 接口操作；**GM/决策人工具本批不做，后续单独强化**，但要把要补的接口/工具清单记全。
8. 自然年龄增长 = 每 tick 由 `ageDaysAt(day)` 现算 `AgeBracket`，不做“档位搬迁”。

## 2. 目标数据模型

### 2.1 率表归属与形状

- 率表仍用 `HouseholdVitalRate` 的语义，但单位改为 **ppm/tick（每百万分之一每 tick）**：
  - `birthRatePerMillionPerTick`
  - `deathRatePerMillionPerTick`
  - 键 = `(AgeBracket, Sex)`。
- 为什么不用原 `perMillePerTick`：现有月率折到每天是 0.0x‰ 级，整数 `‰/tick` 表达不了；ppm/tick 可以精确表达当前量级，也配合余数累加器。
- 存放：
  - 全局默认率表：新增 Social 组件（建议 `SocialVitalRates` 或并入 `SocialProvisioning`，施工时定一个唯一落点）。
  - 逐家户覆盖：`Household.vitalRates` 保留为覆盖表；空表/缺键 ⇒ 回落全局默认。
  - 查找顺序：家户覆盖 > 全局默认；两边都没有 ⇒ 具名拒（不静默给 0）。
- 出生率的自然窗口：
  - 仍按**精确年龄 15 ≤ ageYears < 45**（`CalendarAge` 现算）判断育龄；
  - 率值来自率表 `(15-59, FEMALE)`；
  - 这样 45 岁以上不会被错误纳入生育，但率表仍按三档维护。

### 2.2 初始默认值（用户要求给初始值）

以现有月度基础死亡率/生育率折算到 ppm/tick：

```text
死亡（ppm/tick，男女同值作为初值）：
  0-14    = 67     （≈ 2‰/月）
  15-59   = 33     （≈ 1‰/月）
  60+     = 667    （≈ 20‰/月）

出生（ppm/tick）：
  15-59 FEMALE = 667（≈ 20‰/月；仅 15-44 岁精确育龄窗口生效）
  其余键      = 0
```

这些是**初始策略值**，不是硬编码传导；后续可由外部模块用 Social 接口增减。

## 3. 每 tick 生死算法

### 3.1 日初调用顺序（App 组合根）

在 `PopulationEconomyTimeParticipant` 每日循环最开始：

```text
1. SocialVitalService.settleOneTick(currentSocial, day, clock)
     → 新 currentSocial + 出生/死亡事件 + 逐家户人口变化
2. 用新 currentSocial 刷新经济侧 composition / labor / naturalNeeds
3. EconomyDayStepper.step(day)
```

原 monthly `PopulationDynamics.monthly(...)` 调用与日末 `applyDailyStress(...)` 一并删除。

### 3.2 死亡

对每个 `(household, lot)`：

```text
rate = 该户覆盖 ?? 全局默认[(ageBracket, sex)].deathRatePerMillionPerTick
numerator = remainder.death + share × rate
deaths = numerator / 1_000_000
remainder.death = numerator % 1_000_000
```

- `deaths > 0` ⇒ 生成 `DEATH` 事件（带 lotId），走 `HouseholdBook.applyEvents` 精确削减该户份额；
- 余数必须跨 tick 保留，否则 ppm 级死亡率会被逐 tick 整除吞光；
- 同一天重复调用由事件 id 幂等守卫拒绝。

### 3.3 出生

对每个 `(household, lot)`，先判断：

```text
group.sex == FEMALE
15 ≤ ageYears < 45
```

再：

```text
rate = 该户覆盖 ?? 全局默认[(15-59 档, FEMALE)].birthRatePerMillionPerTick
numerator = remainder.birth + share × rate
births = numerator / 1_000_000
remainder.birth = numerator % 1_000_000
```

- `births > 0` ⇒ 按性别各半拆成 `BIRTH` 事件，落进该家户新的 0 岁批次；
- 出生事件 id 含 `household + day + mother lot + sex`，同日幂等。

### 3.4 余数累加器

- 键：`(HouseholdId, PeopleLotId, BIRTH|DEATH)`；
- 值：`long numerator`（0..999_999）；
- 新增 Social 状态组件保存；旧数据作废，不做迁移；
- 当 lot 被删/移走时清理对应余数；没有余数不落键。

### 3.5 自然年龄增长

- `PopulationGroup` 仍只存 `ageAtAnchorDays + anchorTick`；
- 每天 `ageDaysAt(day)` 现算；
- `AgeBracket.of(clock.system(), dayNumber, ageDays)` 现算档位；
- 出生 = 新建 0 岁批次；
- 死亡 = 按份额削减；
- **不需要任何“档位搬迁”状态**。

### 3.6 经济侧同步

`settleOneTick` 返回逐家户人口变化，App 调 `EconomyDayStepper.applyHouseholdPopulationDeltas(...)`：

- 经济行 `HouseholdEconomy.population` 按 delta 更新；
- 劳动预算随后由 Social 成员现算覆盖；
- 需求随后由 Social 逐成员展开注入；
- 不再需要旧的 `LotChange.at(HexCoord)` 月度回写路径，UNIT 家户不再因“没有 HEX”在生死结算时抛。

## 4. 要删除 / 废弃的东西

| 对象 | 处置 |
|---|---|
| `PopulationDynamics.monthly` 生产调用 | **删除** |
| `PopulationDynamics` 的 `BASE_MORTALITY_PER_MILLE_PER_MONTH` | **删除** |
| `PopulationDynamics` 的 `MORTALITY_PER_STRESS` / `STRESS_MORTALITY_THRESHOLD` | **删除** |
| `PopulationDynamics` 的 `FERTILITY_PER_MILLE_PER_MONTH` / `FERTILITY_SUPPRESSION_PER_STRESS` | **删除** |
| `PopulationDynamics.stressAfter` | **删除** |
| `PopulationEconomyTimeParticipant.applyDailyStress` + 日末压力块 | **删除** |
| `PopulationGroup.physiologicalStress` | **删除**（用户：一起删） |
| `PopulationSeeder` / `PopulationLots` 中 stress 字段 | **删除** |
| `ApiViews` / `CrisisMonitor` / GUI 的 physiologicalStress 读口 | **改 unavailable/删除** |
| `PopulationDynamics` 类本身 | 生产路径删除后，若全仓无 main 引用则整体删除 |
| 旧档兼容 | 不做迁移；新世界重建 |
| `HouseholdBook.settleVitalEvents` | **保留骨架**，升级为唯一生产引擎，加 ppm + 余数累加器 |

## 5. 要新增 / 修改的 Social 接口

### 5.1 率表接口（外部模块与 GM 共用语义）

```text
social.AdjustVitalRate:
  {"householdId"?: "hh-1",  // 缺席 = 全局默认
   "ageBracket": "0-14|15-59|60+",
   "sex": "MALE|FEMALE",
   "birthDeltaPerMillionPerTick"?: 整数（可正可负）,
   "deathDeltaPerMillionPerTick"?: 整数（可正可负）,
   "reason": "...",
   "source": {"module":"economy|gov|army|sd", ...}}
```

- 在当前值上做 delta；
- 结果下限 0；
- 家户覆盖可只覆盖个别键；
- 产出 `RATE_SET` 或新增 `RATE_ADJUST` 事件；
- 全表替换仍保留 `social.SetHouseholdVitalRates` / `SET_VITAL_RATES`。

### 5.2 直接加减人口接口

现有命令/工单已具备，固定为官方语义：

```text
social.AdjustPopulation:
  {"householdId", "sex", "ageBracket", "delta", "reason", "source"}
```

- 走 `HouseholdBook.adjustPopulation`；
- 外部模块只提交命令 / Social 工单（`ADJUST_POPULATION` / `ADD_MEMBERS` / `REMOVE_MEMBERS` / `TRANSFER_MEMBERS`），不直接写 `SocialData`；
- 旧 `GM_ADJUST` 事件名是否改为 `POPULATION_ADJUST`，施工时决定；线格式可改，旧档作废。

## 6. GM / 决策人工具：本批不做，但必须记全

用户裁定：**GM 工具先不做**，等算法稳定后单独搞 GM/决策人工具端强化。后续要补：

1. `simos.social.vitalRate` GM 窄工具：调全局/家户出生率、死亡率，支持 delta、查看 before/after。
2. `simos.social.population` 或扩展 `simos.social.household.members`：GM 直接加减任意年龄段人口。
3. 决策人版受限工具：只能对决策人可见/管辖的家户调率/加减人口，走权限+审批链。
4. Catalog / `PAYLOAD_HINTS` / 工具桶名单 / `SimosToolsTest` 同步。
5. 读口：全局默认率表、家户覆盖、当前有效率、每 tick 出生/死亡读数。
6. 前端/GUI 面板：率表与人口调整入口。
7. 日志事件：`RATE_ADJUSTED` / `POPULATION_ADJUSTED` 的 source/reason 审计。
8. 测试迁移时补：率表不变量、余数累加器、同日幂等、外部模块只能走接口的护栏。

## 7. Unit 对接规划（回答“实现完能直接接 Unit 吗”）

### 7.1 能直接复用的

- `Unit.households` 是唯一“谁在这个 Unit 里”的关系列表；
- `HouseholdPositionResolver`：`UNIT(unitId)` → 当刻 effective hex；
- `EconomyDayStepper` 已有 Social → 经济的人口/劳动/需求注入接口；
- Social 工单/命令已支持 `TRANSFER_MEMBERS` / `ADJUST_POPULATION` / `SET_VITAL_RATES`；
- app `submitBatch` 已能把 Social + Unit 命令打成一条 revision。

### 7.2 还缺、要单独排的 Unit 对接批次

1. **征兵/退伍组合工具迁移到 Social 工单**
   - 现在是 app 组合旧 `social.*` 命令；
   - 目标：Army/GOV 工具提交 `social.SubmitHouseholdWorkOrder`（TRANSFER_MEMBERS / SET_LOCATION）+ `unit.SetUnitHouseholds`，必要时 `submitBatch`。
2. **战斗伤亡 → Social 人口减少**
   - 当前 `ResolveCombatPlan` 明确拒绝人力战损；
   - 目标：Army/GOV 根据战损结果，按 `Unit.households` 提交 Social `ADJUST_POPULATION`（可带年龄/性别/批次），Unit 侧只改装备/编制状态。
3. **单位移动/迁都 → 政府/军官家户位置**
   - `HouseholdLocation.UNIT(unitId)` 已设计；
   - 需要把 `unit.PlaceAt` / 行军 / 迁都的调用点统一走 resolver，并确认经济行 view 对齐；
   - 不再用 `social.SetHouseholdLocation(HEX)` 钉位置。
4. **军俸/维护费 → 通用周期库存扣除 + Unit 决策人规则**
   - Social/Unit 定义“哪些家户、每周期扣什么”；
   - Economy 提供通用周期库存增减原语；
   - Unit 决策人维护内部利益分摊，不做跨户平均、不把 Unit 压成一户。
5. **Unit 决策人/利益集团模型**
   - 现在 `Unit` 没有决策人/代表账户/内部分摊规则字段；
   - 需要 Unit 侧内部政策/规则表，由决策人维护，经济侧只按 `HouseholdId` 执行。
6. **UNIT 家户的生死/经济回写**
   - 本批每 tick 引擎用“逐家户 population delta”更新经济行，不再依赖 `LotChange.at(HexCoord)`，因此 UNIT 家户的出生/死亡不会因无 HEX 抛；
   - 但 UNIT 家户的“有效位置”仍由 resolver 给经济读口，需要 smoke 覆盖。

### 7.3 结论

- **不能“自动直接接”**：Social 生死引擎稳定后，Unit 能复用人口/劳动/需求接口和工单组合；
- 但 Unit 侧还要补：征兵退伍迁移、战斗伤亡回写、军俸周期扣除、Unit 决策人内部分摊四件套；
- 这些应作为下一阶段的独立批次，不在本批生死引擎里混做。

## 8. 实施批次

### Batch A：Social 率表 + 每 tick 引擎

- 新增全局默认率表 + 家户覆盖；
- `HouseholdVitalRate` 改 ppm/tick；
- 改造 `HouseholdBook.settleVitalEvents`：每 tick、ppm、余数累加器、`VitalSettlementResult`；
- `SocialData` 加余数组件；codec/changeset 同步；
- 删除 stress / `PopulationDynamics` 生产路径；
- compile 门禁。

### Batch B：App 日初接线 + 经济行同步

- 每日日初调用 Social 每 tick 生死；
- 删除月结 / `applyDailyStress`；
- `EconomyDayStepper.applyHouseholdPopulationDeltas(...)`；
- 用 Social 新状态刷新 composition/labor/needs；
- compile 门禁。

### Batch C：删除读口 + smoke

- 清 `physiologicalStress` 读口；
- fresh small-world `0→360`；
- 检查人口年龄结构、出生/死亡、经济行人口；
- 文档更新。

### Batch D：Unit 对接（后续）

按 §7.2 独立排。

## 9. 验收标准

1. `clean compile` rc=0；
2. `0→360` smoke 无 Runtime ERROR；
3. 出生/死亡每天发生（不再只在 day30 倍数）；
4. 长期出生率/死亡率与配置 ppm 一致（余数累加器自证）；
5. 经济行人口每天跟随 Social 变化；
6. 无 `physiologicalStress`、无 `PopulationDynamics` 月度调用；
7. UNIT 家户出生/死亡不因无 HEX 抛；
8. GM/决策人工具未做，但 §6 清单已落盘。
