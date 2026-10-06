# 2026-10-09 Social 每 tick 生死引擎 + 率表 + Unit 对接规划

> 状态：**Batch A/B/C 已实现并跑通 fresh small-world 0→360；§7.2 P0 迁移写人旁路 2026-10-10 已关闭；
> Next = §7.3 Unit 对接四件套 + 决策人模型。**
> 基线：`HEAD c9186fd4`（30× 储备粮验证后）；本批实现含余数初相位修正，见 §3.4 / §10.3。
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
- 当 lot 被删/移走时清理对应余数；没有余数不落键；
- ★★ **首次见到键时用稳定哈希给 [0, 999_999] 的初相位，不是 0**（施工后 smoke 修正，见 §10.3）：
  - 零初值会让每个小批次的首事件被推迟到 `1_000_000 ÷ (份额 × 率)` 个 tick——7 岁儿童批次（rate 67 ppm、share≈20）
    要等约 745 天，成年批次（rate 33 ppm、share≈30）要等约 1010 天才第一次死人；
  - 哈希用 FNV-1a 64（只含稳定 `householdId|lotId|kind.name()`），同状态重放逐字节相同，无随机源；
  - 这让有限窗口内的事件数期望等于率表连续期望，余数仍跨 tick 累加 ⇒ 长期速率不变。

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

### 7.2 Unit 对接前必须先关掉的旁路（P0，任何 Unit 批次之前）—— ✅ 2026-10-10 已关闭

> 实施记录见 `docs/superpowers/plans/2026-10-10-p0-mode-migration-social-outbox.md` §5。fresh 0→360 复验：
> day 120/240/360 有 `MIGRATION_APPLIED`；逐户 138/138 Social == Economy；世界总人口 4100 = 4100；
> 0 ERROR / 0 投影 unresolved；`work-order:mode-migration:*` 15 条、0 duplicate/rejected。
> 以下原文保留为问题留痕。

**实测（2026-10-09 Batch C，fresh small-world 0→360）**：`ModeMigrationSettlement.apply` 在 day 120/240/360
各执行 7 次迁移，直接改 `HouseholdEconomy.population`（并迁移资产/钱/债），**Social 完全不知道**。360 天后
全世界人口总数仍相等（Social 4101 = Economy 4101），但逐户已经错开：

```text
hh-0_1-rural-middle_peasant   Social 72  Economy 69
hh-0_1-rural-rich_peasant     Social 30  Economy 27
hh-0_2-urban-middle_peasant   Social 110 Economy 131
hh-0_2-rural-middle_peasant   Social 73  Economy 70
hh-0_2-rural-rich_peasant     Social 30  Economy 27
hh-1_1-rural-middle_peasant   Social 70  Economy 67
hh-1_1-rural-rich_peasant     Social 31  Economy 28
```

这不是投影 bug，而是**经济域仍在写“人”**。在它关掉之前，Unit 的征兵/退伍/伤亡一定会复制同一条旁路。
修复口径（下一批，先于 Unit）：

1. `ModeMigrationSettlement` 不再直接把人当成 `HouseholdEconomy.population` 的私有字段移动；它只把
   `(sourceHousehold, targetHousehold, count, targetHex, targetMode, newTarget?)` 记进**当日只读 outbox**
   （建议 `EconomySession.pendingPopulationTransfers()`，不新增持久组件；迁移本身仍按现有逻辑改资产/组织/位置/账）。
2. `PopulationEconomyTimeParticipant` 在 `stepper.step(day)` 之后读取 outbox，逐条翻译成
   `social.SubmitHouseholdWorkOrder` 的 `CREATE_HOUSEHOLD`（仅 newTarget）/ `TRANSFER_MEMBERS` 步骤：
   - 源户按成员批次确定性选出恰好 `count` 人（同 id 序 + 最大余数法，避免跨户平均/随机）；
   - 目标户先 `CREATE_HOUSEHOLD`（位置=`HEX(targetHex)`、profile 由 mode 派生、`vitalRates` 空表走全局默认）；
   - 整批工单在**同一条 revision** 内与 Unit/Economy 迁移一起提交；工单失败 ⇒ 整个 advance 具名拒，不允许
     economy 已移人而 Social 没移。
3. Social 工单成功后，下一日 `settleOneTick` / `composition` / `labor` / `needs` 自然看到新人；经济行的
   资产/钱/债已经由迁移路径落好，两边只有人口一条权威。
4. 防回归：加“经济域不得写人口”的静态/运行守卫（至少 `ModeMigrationSettlement` 的 `population` 写入只准
   经 outbox 适配器；测试迁移时补一条“day 120 迁移后 Social 逐户人口 == Economy 逐户人口”）。

#### 7.2.1 经济腿的债务/资产/钱货口径（2026-10-09 用户确认：选接口级 policy、默认跟人比例）

- **两条腿、一个 migrationId、同一条 revision**：Social 工单只搬人；Economy（与 Actor）只搬经济状态；
  App 是唯一编排者，任一腿失败整批拒；经济行 `population` 最终由 App 按 Social 真值落，不作为独立权威。
- **债务默认 `FOLLOWS_POPULATION`**：按迁移人数比例切本金；实现复用
  `ProportionalSplit.byDenominator`（最大余数法、Σ 守恒）+ `DebtContractBook.reduce/upsert`（唯一写口）。
  全额迁移 `movedPopulation == sourcePopulation` ⇒ 本金 100% 转移、源合同清零；部分迁移的整数余数留在源合同
  （随剩余人口，后续再迁也不会丢）。
- **合同级 policy 预留但不现在做规则引擎**：接口/terms 留
  `FOLLOWS_POPULATION（默认） | STAYS_WITH_ORIGINAL | CALLABLE_ON_MIGRATION`；后续 GM/决策人可定制，
  现在只实现默认分支。
- **债务人 / 债权人两侧对称**：源户是债务人 ⇒ 目标成为新债务人；源户是债权人 ⇒ 按比例把应收转给目标
  （目标成为新债权人）。现有 P8 `LotMigrationBook` 只做了债务人侧，P0 要补齐债权人侧。
- **质押/抵押 MVP 留源合同**：源户若要消亡而质押仍挂着 ⇒ 整笔迁移具名拒，不静默解除质押；以后再做
  “质押随份额”。
- **源户清空**：合同端点/不可移动资产残留时允许保留 0 人口经济空壳（沿用 `retireSource` 口径），但必须
  具名审计；“清户”只有在所有债权/债务/质押都转移或结清后才允许。
- **钱/货/资产**：货币与商品在 actor 账户 ⇒ App 编排 actor 腿按比例搬或显式留源；`OwnershipStake` 按数量
  比例拆；跨 hex/不可移动资产沿用现有 D-023 留源 + audit；劳动配额/供给在人口迁移后必须按新 Social
  重算，防止破 `Σ allocated ≤ available`。
- **日志归属**：经济腿（`MIGRATION_PLAN/MOVE/APPLIED`、逐合同本金迁移）记 `EconomyLog.migration()`；
  Social 腿（工单、TRANSFER_IN/OUT）记 `SocialLog.workOrder()`/`population()`；跨域编排汇总（同一
  migrationId 两腿结果、守恒审计）记 `AppLog.time()`（或 app 新增分类）；**util 不放日志门面**，见
  AGENTS.md §一.9 的模块日志纪律。

### 7.3 Unit 对接批次（四件套 + 决策人模型）

> **2026-10-14 进度**：P1.0 share-aware 选人层/安全闸（`999b1db9`）、P1.1 GovRecruit 工单化（`7adb748b`）、
> P1.2 GovRetire 工单化（`ac5ca16e`）、P1.3 RaiseUnit 工单化（`c0218a5d`）、P3 UNIT 家户经济行登记
> （`0fded9d2`）、P2 战斗伤亡回写 Social（`d1b2ba17`）、P4a 通用周期规则表 + 无状态到期执行器
> （`b663b357`）均已完成并推送。施工文档：`2026-10-11-unit-integration-p1.md`、
> `2026-10-12-unit-integration-p3.md`、`2026-10-13-unit-integration-p2.md`、
> `2026-10-14-unit-integration-p4a.md`。P4b 军俸政策/内部分摊（`HEAD P4b`，见 `2026-10-15-unit-integration-p4b.md`）已完成；剩余：P4c（GM/决策人窄工具/白名单/军俸 FlowRow 维度）、
> GovSelectExaminees/GovDispatchTeam/SpawnArmy 的完整接线、测试迁移与 clean verify。以下原文保留。

1. **征兵/退伍：组合工具迁移到 Social 工单**
   - 现状：app 组合旧 `social.*` 命令；
   - 目标：Army/GOV 工具只提交 `social.SubmitHouseholdWorkOrder`
     （`CREATE_HOUSEHOLD` + `TRANSFER_MEMBERS` / `SET_LOCATION`）+ `unit.SetUnitHouseholds`，必要时
     `submitBatch` 成一条 revision；禁止工具直接拼 `SocialData` 写口。
2. **战斗伤亡 → Social 人口减少**
   - 现状：`ResolveCombatPlan` 明确拒绝人力战损；
   - 目标：战斗结算把“哪个 Unit、损失多少人”作为结果；Army/GOV 按 `Unit.households` 拆到具体家户，提交
     Social `ADJUST_POPULATION`（能定位批次时用 `REMOVE_MEMBERS`）；Unit 侧只改装备/编制/士气，不碰人口。
3. **单位移动 / 迁都 → UNIT 家户位置**
   - `HouseholdLocation.UNIT(unitId)` 已设计，`HouseholdPositionResolver` 已把 UNIT 当刻 effective hex 给经济视图；
   - 需要把 `unit.PlaceAt` / 行军 / 迁都的调用点统一走 resolver，确认 `unit.SetUnitHouseholds` 一致；
   - 不再用 `social.SetHouseholdLocation(HEX)` 钉位置（迁移期可留读旧档兼容，不双写）。
4. **军俸/维护费 → 经济通用“周期家户库存增减”机制（用户点名）**
   - 经济提供**通用的**周期库存/货币扣增原语（例如 `economy.HouseholdPeriodicAdjustment` 或结算内建
     `PeriodicHouseholdCharge`）：输入 `(household, 商品/币种, 额/率, 周期, reason, policySource)`，
     按户落账、缺货具名、可审计；**不含任何 Army 专属常量**；
   - Unit 侧只提供“哪些家户、按什么内部规则分摊、发给谁”的决策人规则（工资/给养/军装/退伍金）；
   - 外部可定制 = 规则表/命令来自 Unit 决策人或 GM，经济只执行通用机制；
   - 与征兵/伤亡一样，钱的移动留在 Economy，人的移动仍走 Social 工单。
5. **Unit 决策人 / 利益集团模型**
   - 现状：`Unit` 没有决策人/代表账户/内部分摊规则字段；
   - 需要 Unit 侧内部政策/规则表（谁代表军人、军饷标准、战利品/伤亡抚恤分配），由决策人维护；
   - 经济侧只按 `HouseholdId` 执行，不把 Unit 压成一户、不做跨户平均。
6. **UNIT 家户的生死/经济回写 smoke**
   - 本批每 tick 引擎用“逐户 population delta”更新经济行，不再依赖 `LotChange.at(HexCoord)` ⇒ UNIT 家户
     出生/死亡不会因无 HEX 抛；
   - 但 UNIT 家户的有效位置、经济 view 对齐、征募后的家户增减还没跑过；下一批补 small-world
     `unit.raiseUnit` + `SetUnitHouseholds` + 若干天 smoke。

### 7.4 结论

- **不能“自动直接接”**。Social 侧接口已稳定（每 tick 引擎、余数累加器、工单、率表/人口调整入口），
  Unit 能直接复用；
- 但接之前必须先关掉 §7.2 的经济写人旁路；否则征兵/伤亡/军俸都会绕过 Social 权威；
- Unit 侧还要补：征兵退伍迁移、战斗伤亡回写、军俸通用周期扣增 + Unit 决策人内部分摊、UNIT 家户 smoke
  四件套。它们是下一阶段的独立批次，顺序建议：
  **P0 迁移 outbox→Social 工单 → P1 征兵/退伍 → P2 伤亡 → P3 UNIT 家户 smoke → P4 通用周期扣增 + Unit 决策人规则**。


## 8. 实施批次与状态

### Batch A：Social 率表 + 每 tick 引擎（✅ 2026-10-09 完成，随本批提交）

- 新增全局默认率表 + 家户覆盖；
- `HouseholdVitalRate` 改 ppm/tick；
- 改造 `HouseholdBook.settleVitalEvents`：每 tick、ppm、余数累加器、`VitalSettlementResult`；
- `SocialData` 加余数组件；codec/changeset 同步；
- 删除 stress / `PopulationDynamics` 生产路径；
- compile 门禁：`tools/mvn-lock.sh -q -pl simos-social -am -DskipTests clean compile` rc=0。

### Batch B：App 日初接线 + 经济行同步（✅ 2026-10-09 完成，随本批提交）

- 每日日初调用 Social 每 tick 生死；
- 删除月结 / `applyDailyStress`；
- `EconomyDayStepper.applyHouseholdPopulationDeltas(...)`；
- 用 Social 新状态刷新 composition/labor/needs；
- compile 门禁：`tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` rc=0。

### Batch C：删除读口 + smoke + 相位修正（✅ 2026-10-09 完成，随本批提交）

- 清 `physiologicalStress` 读口；
- fresh small-world `0→360`；
- 检查人口年龄结构、出生/死亡、经济行人口；
- **修正余数零初值的首年低速率偏差**（§3.4 / §10.3）；
- 文档更新。

### Batch D：Unit 对接（⏳ 下一批）

顺序 = §7.2 P0（✅ 2026-10-10 已关闭）→ §7.3 四件套 + 决策人模型；不在本批生死引擎里混做。

## 9. 验收标准与实测

1. `clean compile` rc=0 —— ✅（`simos-app -am`；`package` 也 rc=0，前端门禁 412/412）；
2. `0→360` smoke 无 Runtime ERROR —— ✅（fresh store，0 ERROR / 0 Exception / 0 投影 warning）；
3. 出生/死亡每天发生（不再只在 day30 倍数）—— ✅（360 个 `POPULATION_SETTLE`，逐日事件分布）；
4. 长期出生率/死亡率与配置 ppm 一致（余数累加器自证）—— ✅（相位修正后：360 tick 出生 258、死亡 157；
   FNV 相位逐步模拟预期 259 / 156）；
5. 经济行人口每天跟随 Social 变化 —— ✅ 2026-10-10 P0 关闭旁路后：fresh 0→360 逐户 138/138
   Social == Economy，世界总人口 Social = Economy = 4100；day 120/240/360 迁移均经
   `MigrationSocialBridge` 落 Social 工单并回写经济 delta；详见 §7.2 上方实施记录；
6. 无 `physiologicalStress`、无 `PopulationDynamics` 月度调用 —— ✅（main 静态审计：旧类 0 命中；
   `physiologicalStress` 只剩两个 `null` 不可用读口键）；
7. UNIT 家户出生/死亡不因无 HEX 抛 —— ⏳ 未实测（small-world 当前无 Unit/Army；代码路径已改为逐户 delta，
   不读 HEX，但要等 Unit smoke）；
8. GM/决策人工具未做，但 §6 清单已落盘 —— ✅。

## 10. 2026-10-09 Batch A/B/C 实测留档

### 10.1 命令

```bash
cd /home/cna/SimulatorMosire
tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile        # rc=0
tools/mvn-lock.sh -q -pl simos-app -am -Dmaven.test.skip=true package   # rc=0；前端 412/412
# fresh store / 独立端口
JAVA_TOOL_OPTIONS='-Dsimos.social.logLevel=DEBUG -Dsimos.economy.logLevel=DEBUG' \
SIMOS_SMALL_WORLD_STORE=/tmp/batchc-phase-store \
SIMOS_SMALL_WORLD_GUI_PORT=5931 SIMOS_SMALL_WORLD_MCP_PORT=5935 \
SIMOS_SMALL_WORLD_APPROVAL_PORT=5933 ./run-small-world.sh
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"branch":"main","expectedRevision":1,"from":0,"to":360}' \
  http://127.0.0.1:5931/api/advance
```

### 10.2 读数

```text
revision 1 -> 2（tick 0 -> 360）
POPULATION_SETTLE days = 360
births = 258   deaths = 157
final social population = 4101
final economy population = 4101
CLASSROW_POPULATION_PROJECTION_UNRESOLVED = 0
runtime ERROR = 0
day 120 / 240 / 360：MIGRATION_APPLIED moves = 7 rowsBefore = rowsAfter = 138
```

### 10.3 零初值问题与修正（本批最重要的算法留痕）

修正前（余数一律从 0 起）：

```text
360 tick 实测 deaths = 20；按率表连续期望 = 156
Python 逐步模拟（同初始批次、同 rate）= 20
⇒ 136.0 人的差额全部冻在 450 个批次各自的余数里，不是丢失，但 360 天窗口内实际死亡率只有配置值的 13%
```

修正后（首次见键给 FNV-1a 稳定哈希相位）：

```text
360 tick 实测 deaths = 157，出生 = 258
FNV 相位模拟预期 deaths = 156，出生 = 259
⇒ 有限窗口内的事件数期望 = 率表连续期望；余数仍跨 tick 累加，长期速率不变
```

### 10.4 本批未做 / 未验证

- 测试迁移：`test-compile` 仍红（旧 API 引用未迁）；`test` / `verify` 未跑；
- `FlowRow.births/deaths` 仍不承载每 tick 生死（`CrisisMonitor.MORTALITY` 只看 famine 路，默认看不到 ppm 死亡）；
- ~~`ModeMigrationSettlement` 旁路未关（§7.2 P0）~~ —— ✅ 2026-10-10 已关闭，逐户对账见 §7.2 实施记录；
- GM/决策人工具未做（§6 清单已落盘）；
- UNIT 家户 / GOV / Army smoke 未做（small-world 目前没有 Unit/Army）；
- 迁移期时序：迁移在 `step(day)` 内发生、Social 工单要同日补账（§7.2 P0 的设计已经按“同 revision 落齐”写死）。
