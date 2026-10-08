# 只读调查：口岸维护效率公式与现有行政/生产效率框架的接线缺口（task-5）

> 只读调查，**未改任何生产代码/测试、未跑 Maven、未 commit**。全部结论来自静态阅读 + `rg` 检索；
> 凡"未核到"的写在本报告 §5，不得当结论用。
> 最高权威：用户 2026-10-08 原话（本报告 §0 逐字抄录）与 `docs/superpowers/specs/2026-10-23-gov-service-mode-design.md`（Z0 约束设计书）。
> 兄弟报告（同批、只读引用）：`2026-10-08-admin-region-and-extraction-investigation.md`（行政区/抽税）、
> `2026-10-08-market-zone-and-currency-investigation.md`（市场区/货币）。

---

## 0. 用户原话（逐字，派单书所载；本报告不改写）

> 「…发货币的政府的总疆域…默认就是这种货币的市场区…；**有了市场区之后，GOV就可以管控关口市场关口，每一个隶属市场区内货币发行者（前文提到的发货币的政府）及其下属，根据其行政区内，所属市场区和其他市场区有多少相邻边界（就算挨着也算上），计算维护成本，开一个和治安维护、行政与税收维护并列的行政生产方式，也就是口岸维护，在里面设编制家户，算一个额外的口岸效率，其中，经济口岸维护效率=（行政效率*（1-行政效率对口岸补偿）+行政效率对口岸补偿）*口岸行政劳动力/口岸劳动力需求，基本就是这样**；然后这个口岸效率能干什么？…（口岸政策/兑换，另有人查）；重叠辖区是不被允许的…」

---

## 1. 一句话结论

**用户公式的"行政效率""劳动力/需求"两项在代码里今天都有对应量（`GovEfficiency` 的千分制效率与 `satisfaction` 满足率形态），但公式里的 `补偿` 项、`(1−补偿)` 的加性/下限（floor）形态、以及"口岸"这个维/生产方式在全仓 0 命中——真正卡住的不是"有没有地方放"，而是三处量纲/合成口径未定（效率是 ‰ 且不封顶而非 [0,1]、第三维与总效率如何合成、边界计数没有现成几何函数且 `MarketTopology` 在 gov 的 enforcer 禁列）**；预算侧则**不需要**新增类别（现有五类按"支付性质"分，不按服务维分）。

---

## 2. 逐条回答派单书 8 问

### Q1. `GovAdministrationPlan` 现形状：几维？计划劳动 / 3 档岗位 / 4 静态修正 / 动态注入面

**几维：两维**（治安 `security` / 公文 `paperwork`）。record 共 **8 个组件**（不是 4 个）：

| 组件 | 含义 | file:line |
|---|---|---|
| `securityPlannedLaborMilli` | 治安计划劳动量（毫小时/tick，≥0 不封顶） | `simos-gov/src/main/java/io/mosire/simos/gov/GovAdministrationPlan.java:44`、`:34` |
| `paperworkPlannedLaborMilli` | 公文计划劳动量（同上） | 同 `:45`、`:35` |
| `postTiers` | 3 档岗位目录（每档两维权重‰） | `:46`、`:36` |
| `securitySupplyStaticModifierPerMille` | 治安**供给**静态修正（‰） | `:47`、`:37` |
| `paperworkSupplyStaticModifierPerMille` | 公文供给静态修正（‰） | `:48`、`:38` |
| `securityDemandStaticModifierPerMille` | 治安**需求**静态修正（‰） | `:49`、`:39` |
| `paperworkDemandStaticModifierPerMille` | 公文需求静态修正（‰） | `:50`、`:40` |
| `supernumerarySqrtCoefficient` | 「超标项」= 超编开方系数 `k`（默认 1） | `:51`、`:57` |

- **"计划劳动"**= 上面两个 `*PlannedLaborMilli`；类注 `:13-21` 明写"公式永远用计划量，**不用** `GovDemand` 的建议值（建议值只用于默认/告警）"。
- **"3 档岗位"**= `GovPostTier(tierId, securityWeightPerMille, paperworkWeightPerMille)`（`GovPostTier.java:20-21`），默认目录 tier-1 治安 1000/公文 0、tier-2 治安 0/公文 1000、tier-3 各 500（`GovAdministrationPlan.java:59-70`）；"恰 3 档"在构造期冻结（`:72-84`，`REQUIRED_TIER_COUNT = 3`）。
- **"4 个静态修正"**= 供给/需求 × 两维，中性默认 1000‰（`:54`、`:108-119`）；只判非负、**不封顶**（`:26-28`、`:121-125`）。
- **存哪**：`GovState` 第 2 个组件 `Map<UnitId, GovAdministrationPlan> administrationPlans`（`GovState.java:41`）；读 `administrationPlan(UnitId)`（`:127`）、`administrationPlanOrDefault`（`:133-134`，缺键 = `neutral()`）；写 `withAdministrationPlan`（`:100-104`）。
- **GM 能不能改：能。** 命令 `gov.SetAdministrationPlan`（`simos-gov/.../spi/SetAdministrationPlanHandler.java:61` 的 `TYPE`；`:58` 实现 `GmOnlyCommand`；`:50` "GM-only（Z2 冻结）"）——但**标记只影响令白名单/RegisterEffect/决策人 catalog**，决策人窄工具直接提交同一命令是受控的（审批链承担门禁）。窄工具 `simos.gov.setEstablishment`（`simos-app/src/main/java/io/mosire/simos/app/tools/write/GovSetEstablishmentTool.java:47`）：GM 桶走 `GmAutoApproveGate`，决策人桶走 `AutoApproveGate → ConfirmGate → PendingApprovals`，且只能操作自己所属 GOV（`:33-39`）。
- **载荷口径**：`GovPayloads.administrationPlan(JsonNode)`（`simos-gov/.../spi/GovPayloads.java:84-123`）——**每个字段都可缺省**，缺省即中性（`:85-113`）；`postTiers` 缺省 = `DEFAULT_POST_TIERS`（`:87-88`）。
- **动态修正从哪注入：不是 `ProductionEfficiencyBook`**（派单书这句需要更正，见 Q6）：gov 侧是 typed record `GovEfficiencyModifier`（`simos-gov/.../GovEfficiencyModifier.java:34-41`，四个 ‰：供给/需求 × 两维，`source`/`reason` 必填）→ app `PopulationEconomyTimeParticipant.updateGovEfficiencyModifiers(List)` **整表替换**（`simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java:231-260`；重复 GOV/未知 GOV/null 具名拒 `:240-256`）→ 日循环取走当日注入集（`:711-723`）→ 逐 GOV 取四项传入 `GovEfficiency.of(...)`（`:1291-1319`）→ 消费后清空（`drainGovEfficiencyModifiers` `:1420-1429`）。**GM 不可达**（类注 `:6-10`、`:20-22`）。

### Q2. 「行政效率」当前完整算式在哪一行？取值范围 [0,1] 还是无上限？

**冻结公式的书写版**在类注 `simos-gov/.../GovEfficiency.java:20-28`；**实现**在 `compute(...)` `:133-202`。逐段落点：

| 公式段 | 实现 | 备注 |
|---|---|---|
| `需求劳动_d = P_d × 需求静态‰/1000 × 需求动态‰/1000` | `:142-151` → `demandLabor` `:205-208`（两次 `perMille` 向下取整） | `perMille` `:211-213` |
| `超额_d = max(0, S_d − 需求劳动_d)` | `effectiveLabor` `:221-231` | |
| `有效劳动_d = 需求 + ⌊√(超额×k÷定额)⌋×定额` | 同上 `:226-230`（**超编开方就在这两行**） | 整数平方根 `integerSqrt` `:254-269`（先 `Math.sqrt` 近似、再用除法比较校正，防溢出） |
| `满足率_d = 有效劳动_d × 1000 ÷ 需求劳动_d`（需求 0 ⇒ 1000‰） | `satisfaction` `:234-240` | |
| `效率_d‰ = 满足率_d × 供给静态‰/1000 × 供给动态‰/1000` | `:172-185` → `applyModifiers` `:243-246`；**任一维供给 = 0 ⇒ 该维效率 0** `:171-185` | |
| `总效率‰ = 效率_治安‰ × 效率_公文‰ ÷ 1000` | `:187-190` | |

**取值范围：千分制（`‰`，1.0 = 1000），只判 ≥ 0、不封顶 ⇒ [0, +∞)。** 依据：类注三条硬口径 `:30-39`（"全不封顶"、乘法走 `multiplyExact`、溢出即具名 ERROR + `IllegalStateException`）、`Efficiency` 构造期校验 `:422-433`（"Z2 起不封顶"）、`GovOfficeState` 同样（`:18-29`、`:90-95`、`:182-187`）。千分制换算的唯一常量是 `GovRules.PER_MILLE = 1000L`（`simos-gov/.../GovRules.java:87`）。

**消费侧实际用法**（说明"效率"对外是什么量）：长期税把效率当 `‰` 缩放因子——`JurisdictionDailyTax.java:163-167`（缺失键 ⇒ 整单位跳过、不征）、`:489`、`:498` `scalePerMille(assessed, efficiencyPerMille)`；读口 `GovInfoTool.java:446`。

### Q3. 全仓有没有 `口岸`/`关口`/`port`/`customs`/`checkpoint` 命中？（含注释）

**答：`口岸`/`关口`/`海关`/`关税`/`税关`/`报关`/`Customs`/`PortMaintenance`/`PortEfficiency`/`PortAuthority` 在全部 `.java`（main + test，含注释）里 0 命中。** 检索写法逐条留痕（防"glob pathspec 静默 0 命中"和"正则写错"两类假阴性）：

```bash
cd /home/cna/SimulatorMosire
# ① 中文概念词（全仓所有 .java，含注释）
rg -n --glob '*.java' -e '口岸' -e '关口' -e '海关' -e '关税' -e '税关' -e '报关'
#    ⇒ 无输出，rc=1
# ② 英文标识符
rg -n --glob '*.java' -e 'Customs' -e 'PortMaintenance' -e 'PortEfficiency' -e 'PortAuthority'
#    ⇒ 无输出，rc=1
# ③ 裸词 port（-w 精确词，不会命中 porter）
rg -l --glob '*.java' -w port | wc -l
#    ⇒ 24 个文件，全部是 TCP 端口号（--gui-port/--mcp-port/boundGuiPort/ServerSocket/new InetSocketAddress）
# ④ 非 java、非剧本正文（配置/前端/JSON）
rg -n --glob '!docs/worlds/**' --glob '!target/**' -e '口岸' -e '关口' -e '海关' -e '关税' -e '税关'
#    ⇒ 只命中 docs/** 的设计文档（关税/税关都是"留位/未实现"措辞），无代码/配置/前端命中
rg -n -i -e '口岸' -e 'customs' simos-app/src/main/resources/webui/
#    ⇒ 无输出，rc=1
```

补充三条"看起来像但不是"的命中（避免误判）：

1. `checkpoint`（大写 `Checkpoint[Store|Encoder]`）是**存储检查点**（`simos-core/.../store/CheckpointStore.java:35`、`CoreSimos.java:302`），与"海关检查站"无关；`checkpoints[]` 在 `docs/worlds/**` 里是剧本存档元素（离线导出，非系统数据）。
2. `port` 的唯一"港口"味命中是测试夹具的自由 props 键：`simos-map/src/test/java/io/mosire/simos/map/CityTest.java:23` 的 `"port"`；`City.props()` 是 `Map<String,Object>` 自由表，**不是建模字段**。世界生成配置里有一处真实数据 `config/worldgen/v17levant-nations.json:151` `{"name":"威廉港","kind":"port",...}`——同样只是城市 props 的键，无任何 Java 类型/结算读它。
3. **"补偿"在代码里已是专义词**：`CompensationRule`（产出分配规则，`simos-economy-api/.../relation/CompensationRule.java:8`、`Payee.java:10`、`RuleType.java:6`）。用户公式里的"行政效率对口岸补偿"与它**不是一回事**（那是乘数/floor 参数，这是分配规则）⇒ 命名沿用会撞车，建议实现时换词（如 `portFloorPerMille`）。

⇒ **结论：这条设计在代码里是"从零开始"**：无类型、无枚举、无状态、无命令、无工具、无 Codec。

### Q4. 「行政生产方式」是不是 `ProductionMode`？治安维护/行政与税收维护对应哪些代码？新增第三种要动哪些地方？

**不是。** economy 的 `ProductionMode` 是**社会生产方式**（身份 + 版本 + 绑定的阶层结构：`simos-economy/.../model/ProductionMode.java:21-22`），默认目录 8 个 mode：`tenancy_fixed_kind / tenancy_share / tenancy_cash / wage_farm / handicraft_workshop / family_farm / merchant / displaced`（`DefaultProductionModes.java:88-112`）——**没有** office、治安、公文、口岸任何一个。

**"治安维护/行政与税收维护"这两个词面在全仓 0 命中**（同 Q3 的检索法）。它们在代码里的等价物是 **gov 专属服务生产方式 `office` 的两个服务维**：

- 生产方式：`office` 产业族（base kind `OFFICE_BASE_KIND = "office"`，`simos-economy/.../spi/EconomyGovUnitUpserts.java:99-100`；版本约定 = 新版本新 id `office_v2…`，`EconomyIndustryUpserts.java:32`、`:61`、`:335`）。
- 生产 unit：operator = `HOUSEHOLD:hh-gov-<govId>`（`EconomyGovUnitUpserts.java:35`、`:44`），产业模板由 GM 工具 `economy.upsertIndustry` 建；bootstrap 实参：周期 30 天、`laborPerUnit=1000`、空产出、1 件 TOOL 产能锚（`GovWorldBootstrap.java:141-151`）。
- 两维：`security`（治安）由 `StaffRole.YAMEN` 供给（`GovEfficiency.java:332-336`、`StaffRole.java:18`）；`paperwork`（公文/文书）由 `SCRIBE + POST` 供给（`GovEfficiency.java:338-343`、`StaffRole.java:15`/`:21`）。

**⇒ 结构错位（重要）**：用户说"开一个**和治安维护、行政与税收维护并列**的行政生产方式"，隐含"两个并列 mode"；代码里它们是**同一个 `office` 生产方式下的两个维度**（+ 3 档岗位权重）。所以"口岸维护"映射到现有结构时有两条路：**(a) 第三维**（同一 office 生产方式里的第三个服务维）；**(b) 第二个 gov 专属产业**（新 base kind，如 `port`）。二者代价完全不同，**属设计歧义 ⇒ 应上报裁定**，不由实现方选。

**若选 (a) 第三维，要动的地方（列落点，不是施工图）**：

| # | 落点 | 现状 |
|---|---|---|
| 1 | `GovAdministrationPlan`（计划量 +1、4 修正 → 6 修正） | record 8 组件 `:43-51`；载荷 `GovPayloads.java:84-123`；handler 校验 `SetAdministrationPlanHandler.java`；`GovChangeSet.administrationPlans`（`change/GovChangeSet.java:34`） |
| 2 | `GovPostTier`（两维权 → 三维权） | `GovPostTier.java:20-21`；默认档 `GovAdministrationPlan.java:59-70`；拆分式 `⌊L×w_i÷Σw⌋ + 余数归最后一维`（`GovernmentServiceLaborBridge.java:265-278`，`:53` 明写"只判非负、不强制合计 1000"） |
| 3 | `GovEfficiency`（入参 +2、`Efficiency` 10 字段 → 15、总效率合成式 `:187-190`） | `GovEfficiency.java:85-95`、`:410-433` |
| 4 | `GovEfficiencyModifier`（四 ‰ → 六 ‰）+ app 注入/替换/drain/日志 | `GovEfficiencyModifier.java:34-41`；`PopulationEconomyTimeParticipant.java:231-260`、`:711-723`、`:1291-1319`、`:1420-1429`、`:1450+` |
| 5 | `GovServiceFlow`（逐维 6 读数 × 2 → × 3） | `GovServiceFlow.java:44-58`、`of()` `:103-129` |
| 6 | `GovOfficeState`（两维 per-mille 累计字段）+ 告警 kind | `GovOfficeState.java:56-70`；`GovDaily.java:93-118`（9 个 KIND）；`simos-economy/.../model/HexCrisisSignal.java:66-98`（Kind 枚举） |
| 7 | 读口/工具视图 | `GovInfoTool.java:286-314`、`CatalogTool.java:367-376`、`GovSetEstablishmentTool` 的 view、`SimosToolSource.java`（工具描述含 paperwork） |
| 8 | 测试面 | 实测：main 里 **21 个文件**含 `paperwork`（gov 11 / app 9 / economy 1），test 里 **14 个文件** |

### Q5. 「编制家户」怎么表示？GOV 有哪些编制户、谁养、怎么算劳动供给？

**两类户，都是 `Unit` 的成员户（或外部岗位），不是新类型**：

| 户 | 身份 | 性质 | file:line |
|---|---|---|---|
| 国库户 | `hh-gov-<govUnitId>` | **0 人口纯财政**（用户 2026-10-23 冻结：`hh-gov-<unitId>` 保持 0 人口纯财政） | `simos-social-api/.../id/GovernmentHouseholds.java:26`（`PREFIX = "hh-gov-"`）、`:36-39`（`of(govUnitId)`）；Z0 设计书 `2026-10-23-gov-service-mode-design.md:38` |
| 官吏户（编制户） | `hh-unit:<unitId>` | 每 GOV 恰一户、2 名成年男性、1 个 `POST`/tier-3 岗位 | `GovWorldBootstrap.java:89-91`、`:98-101`、`:123-124`；户 id 派生 `:316` |

**归属表示**：

- 内部岗位：`GovernmentFormation.governmentPostsOfHousehold`（`Map<HouseholdId, GovernmentPostOfHousehold>`；持久化 JSON 键仍是旧名 `householdPosts`）——`simos-unit/.../GovernmentFormation.java:57-63`。
- 外部岗位（家户**不必** ∈ `Unit.households`，与内部表互斥）：`GovernmentFormation.externalPosts`——`:66`。
- 岗位本体 `GovernmentPostOfHousehold(tierId, ...)`：tierId 指向计划的 3 档目录，空串 = legacy/未指派——`simos-unit/.../GovernmentPostOfHousehold.java:21-32`。
- `GovernmentFormation.staff` 已降为**派生投影**（由岗位户人口/承诺现算）：`:146`（`staffIsHouseholdProjection`）、`:198`（`projectedStaff`）。

**谁养**：国库户 `hh-gov-*` 出钱/出物，两条腿分开：

- 俸禄/物资腿：`DeductionReason.ADMIN_UPKEEP`，由 `GovernmentUpkeepOracle` 按资源固定次序（grain → cloth → money）串行扣——`simos-app/.../time/GovernmentUpkeepOracle.java:60-64`、`:154`、`:260`。
- 官吏工资腿：`DeductionReason.ADMIN_SALARY`，`GovSalaryRuleBridge` 按 **`GOV_SERVICE` 承诺小时**逐户 `min(可用, 请求)` 支付（payer = `hh-gov-<govId>`）——`GovSalaryRuleBridge.java:39-50`、`:121-160`；规则值（bootstrap）= 粮 10 毫/承诺小时、银 1 毫/承诺小时（`GovWorldBootstrap.java:135-139`）。

**劳动供给怎么算**（一处真相链）：

1. Social 是"人口可提供劳动"的唯一权威（`SocialData.householdLaborMilli`，见 Z0 §0 尾 `:38-40`）。
2. 经济侧承诺：`HouseholdLaborCommitment` + `kind`，`GOV_SERVICE` = 政府行政岗位承诺，**不进队列、不被重算、不被按比例缩、最高优先级**——`simos-economy-api/.../labor/LaborCommitmentKind.java:12-13`、`:25`；`HouseholdLaborCommitment.java:48`。
3. app 供给桥 `GovernmentServiceLaborBridge`：逐户 `GOV_SERVICE` 承诺求和（`:142`，按 allocation id 首现序）→ Z7d-1 有效劳动 `min(承诺, 该户实际劳动)`（`:207-220`）→ 按 `tierId` 权重拆两维（`:237-307`，`:265-278` 归一化拆分 + 余数归公文）→ 未挂岗位的承诺记具名 INFO 且不计入（`:291`）。
4. 效率：`GovEfficiency.of(...)` 用两维供给 + 计划 + 四个动态修正算（`PopulationEconomyTimeParticipant.java:1286-1319`）。

**⇒ 对口岸的意义**：若口岸"设编制家户"，**不需要新类型**——沿用 tierId + `GOV_SERVICE` 承诺 + 同一拆分/发薪链即可；新增的是"第三维/第二个 office 生产方式"及其权重（Q4）。

### Q6. 静态修正 vs 动态修正的注入面；「行政效率对口岸补偿」该归哪一侧？

**现有判据（代码里可执行的形态）**：

| | 静态修正 | 动态修正 |
|---|---|---|
| 载体 | 源状态字段（`GovAdministrationPlan`） | typed record，无状态（`GovEfficiencyModifier`） |
| 谁改 | GM / 决策人（命令 + 工具 + 审批链）：`GovSetEstablishmentTool.java:30-39` | **只有程序内组件**，GM 不可达：`GovEfficiencyModifier.java:6-10`、`:17-18` |
| 生命周期 | 持久（进 `GovState` / Codec / ChangeSet） | **逐 tick 替换、当日消费后清空**：`PopulationEconomyTimeParticipant.java:240-256`、`:711-723`、`:1420-1429` |
| 缺省 | 1000‰ = 1.0（`GovAdministrationPlan.java:54`） | 未注入 = 1000‰（`GovEfficiencyModifier.java:44`） |
| 经济侧同形先例 | 产业/政策状态 | `ProductionEfficiencyModifier`（`simos-economy-api/.../production/ProductionEfficiencyModifier.java:27-43`）由 `EconomyDayStepper.updateProductionModifiers` 注入，`ProductionEfficiencyBook` 逐 tick 累计 + 周期末按天平均（`ProductionEfficiencyBook.java:70-92`、`:134-255`） |

★ **派单书的一处措辞要更正**：`ProductionEfficiencyBook` 是**经济侧产业的效率账本**（有跨 tick 累计与四余数结转），它**不是** gov 效率的动态注入面；gov 侧是 `GovEfficiencyModifier` → `updateGovEfficiencyModifiers`，**没有账本**（gov 只看当日值，`GovOfficeState` 明确"不得把昨日读数当次日输入"，`GovServiceFlow.java:22-23`）。
★ 另一处不一致（若要统一口径必须知道）：经济侧动态修正**有硬上限 [0,2000]‰**（`ProductionEfficiencyModifier.java:36`、`ProductionEfficiencyBook.java:53 MAX_MODIFIER_PER_MILLE=2000`），gov 侧**不封顶**（`GovEfficiencyModifier.java:20-22`）。

**「行政效率对口岸补偿」按现有纪律应归静态**——理由：它是"描述世界结构/政策的常数"（同族最近先例 = `k` 开方系数，Z0 §3 `:93` 明写「存 gov 源状态、GM 可改」；四个静态修正的来源是用户原话「静态修正是方便GM改的」，Z0 §0 `:13`）。**但**：
- 若其语义是"其他模块对口岸的补贴/代偿"（按 tick 或事件算出来），则应归**动态**（+ 需要新增第 5/7 个动态修正分量）。
- 用户原话没给判据 ⇒ 属**原话歧义**，按 AGENTS §一.8.1 应上报控制方转问用户，**不由实现方自行选**。

### Q7. 「相邻边界计数」：map 能不能算"某 hex 集的邻接外部区域"？有没有现成几何工具？

**能拿到全部原料，但没有"计数"这个函数。**

**已有（可用）**：
1. 逐格邻居：`HexCoord.neighbor(HexDirection)`（`simos-map/.../hex/HexCoord.java:67`）、`neighbors()`（`:72-73`，6 方向）。
2. **暴露边几何原语**：`RegionBoundary.of(Set<HexCoord>)`（`simos-map/.../region/RegionBoundary.java:68-82`）——算法就是用户要的形态：逐格逐方向 `if (hexes.contains(hex.neighbor(d))) continue; // 邻居也在集合里 ⇒ 内部边`，否则取该边两个端点；**但返回的是顶点环 `List<List<HexVertex>>`（`:32`、`:81`），没有"暴露边条数"访问器、也没有"这条边对面是哪个区"**。
3. 逐格归属（行政区）：`RegionIndex.regionOf(HexCoord)` / `hasRegion`（`simos-map/.../region/RegionIndex.java:59`、`:65`）——**一个 hex 可属多个区域**（多对多，`:12-16`、`:42-56`）。
4. 逐格归属（市场区）：`MarketTopology.regionOf(HexCoord)`（`simos-economy/.../time/MarketTopology.java:448`）、`regions()`（`:443`）、`contains`（`:461`）；一个 hex **恰属一个**区（类注 `:24-25`；构造 `regionByHex` `:217`、`:375`）。
5. 市场区成员集：`MarketRegion.members()`（`simos-economy-api/.../api/market/MarketRegion.java:26`、`:34-38`）。

**没有 / 不能直接用**：
1. **没有任何"两个 hex 集之间的共享边界条数 / 相邻区列表"的函数**。全仓扫描 `adjacen|neighbor|touches|borders`（非 import 行）：只有上述逐格 API、`RegionBoundary`（顶点环）、`MarketTopology.adjacent`，以及 `PathFinder.java:88`、`TerrainBlocks.java:56`、`RiverBuilder.java:149` 的逐格邻居用法。
2. **`MarketTopology.adjacent(a,b)` 的口径与用户要的不一样**：它是 `a.anchor().distanceTo(b.anchor()) <= rA + rB + 1`（`MarketTopology.java:474-482`），类注 `:39-41`、`:57-63` 明写"**不按国界、也不按行政相邻**""不是成员格贴边"——而用户要的是"**就算挨着也算上**"（成员格贴边）。两者在多数布局下结论不同。
3. **模块边界硬约束**：`MarketTopology` 在 `simos-economy`，而 `simos-gov/pom.xml:80` 用 enforcer **明禁** `io.mosire:simos-economy`（`:76-83` 的 message："不得反向依赖 sd 或兄弟切片 economy/actor"）。gov 能看见的只有 `simos-economy-api` 的 `MarketRegion`（纯数据、**没有**从城市/半径构造拓扑的能力）。
   ⇒ **边界计数不能写进 `simos-gov`**（除非按 AGENTS §四.2 提出改 enforcer，需裁定）；**app 是唯一同时看得见 map + social + economy 的地方**。
4. **现成注入点已有**：同一个日循环类里已经现算一份市场区拓扑——`simos-app/.../time/PopulationEconomyTimeParticipant.java:564` `MarketTopology flightTopology = MarketTopologyBook.from(state);`（`MarketTopologyBook.from` 在 `simos-app/.../time/MarketTopologyBook.java:88`/`:107`），而算 gov 效率的 `computeGovEfficiency` 就在同一个类（`:1248`）⇒ app 侧把"辖区 hex 集 × 市场区成员集"喂给新维是可行的。
5. **辖区重叠会让边界重复计**：map 侧允许一个 hex 属多个行政区（`RegionIndex.java:12-16`；兄弟报告 `2026-10-08-admin-region-and-extraction-investigation.md:391-393` 记了"税在重叠下逐区域累加、run6 实证重复征税"），而用户这次说"**重叠辖区是不被允许的**"⇒ 若按"辖区 hex 集"计边界，同一段边界会被两个 GOV 各记一次；须先定辖区互斥（兄弟报告 `:383-399` 已把 D1 列待裁定）。

**"多少相邻边界"的两种可实施方案（供裁定，不在本报告选型）**：
- (甲) **逐格暴露边计数**：对 GOV 辖区 hex 集 `H`，对每格每方向 `d`，若 `neighbor(h,d) ∉ H` 且该邻居属**本 GOV 发行的市场区**则 +0（内部）、属**其他市场区**则 +1 —— 即"与其他市场区共享的边长（以格边为单位）"。注意共享边会被两侧各数一次（同一段边 2 个格各自暴露）⇒ 需除以 2 或只在规范侧计。
- (乙) **相邻区种类计数**：对每个邻居格取其市场区 id，收集 `{本区} → {其他区}` 的**集合大小** —— 即"挨着几个别的市场区"（"就算挨着也算上"更贴这一读法）。

### Q8. 要不要新增预算类别？（现有 5 类，V1 不强制齐全）

**答：加第三维（口岸维护）不需要新增预算类别。**

- 现有 5 类 = 闭枚举 `GovBudgetCategory`：`ADMIN_STIPEND`（行政俸禄）/`MILITARY_STIPEND`（军俸）/`ADMIN_SALARY`（行政工资）/`DEBT_SERVICE`（债务）/`OTHER`（其他）——`GovBudgetCategory.java:13-27`；默认优先级 = `orderedCategories()` 列表序，**V1 只冻结词表、不强制五类齐全**（`GovBudgetPolicy.java:12-16`）。
- 类别是**按"支付性质"**分的，**不按服务维**分：`GovBudgetExecutionBridge.java:194-203` 把 `ADMIN_STIPEND ↔ GovDaily.assessUpkeep`（俸禄/物资）、`MILITARY_STIPEND ↔ 军俸`、`ADMIN_SALARY ↔ 工资`；随后 `allocate(policy, demands, available)` 只按 policy 的列表序走（`:204-205`、`:249+`）——全程没有 `security`/`paperwork` 之分。
- ⇒ 口岸编制户的俸禄走 `ADMIN_STIPEND`、工资走 `ADMIN_SALARY`（`GovSalaryRuleBridge` 按承诺小时支付，与维无关）即可，**零改动**。只有"给口岸单独设 min/cap/优先级"才需要第 6 个类别。
- 若确实要加第 6 类，落点与代价（已核）：
  - `GovBudgetCategory` 是闭枚举，线格式 = Jackson 常量名，载荷侧 `GovBudgetCategory.valueOf(text)`（`GovPayloads.java:263`）⇒ 未知类别**当场拒**（fail-closed）。
  - 全仓 `switch (category)` = **0 命中**（实测 `rg -n 'switch \(.*[Cc]ategory'`）⇒ 加常量不会漏分支。
  - 但 `GovWorldBootstrap.java:696-697` 按 `GovBudgetCategory.values()` **全量播种五类**（新常量会自动进 bootstrap 的世界，需确认是否符合意图）。
- 用户设计的**收入侧**（关税/过境税/兑换）不在本任务范围（"另有人查"），但顺带记两条事实：① 现有税收入只有长期税（`Jurisdiction.taxRatePerMilleByRegion` 的 key 集 = 辖区，`Jurisdiction.java:40-45`；征收 `JurisdictionDailyTax.java`），**没有任何"关税/过境税"口径**（`关税` 只出现在 docs 的"留位/未实现"表格里）；② `Jurisdiction.administrationPerMille` 已退役、生产路径零读取（`Jurisdiction.java:27-30`、`:38`）。

---

## 3. 公式落点矛盾点（交账项核心）

用户公式：`经济口岸维护效率 =（行政效率 ×（1 − 行政效率对口岸补偿）+ 行政效率对口岸补偿）× 口岸行政劳动力 ÷ 口岸劳动力需求`

| 用户公式项 | 今天的对应量（file:line） | 能否直接落 | 矛盾 / 缺口 |
|---|---|---|---|
| 行政效率 | `GovEfficiency.Efficiency.efficiencyPerMille()`（`GovEfficiency.java:187-190`、`:402`） | 有 | **量纲冲突**：用户 2026-10-23 原话是"行政效率（**百分比**，决定实际税收等）"，代码是**千分制且不封顶**（`:30-39`、`:422-433`）。`(1−补偿)` 里的 "1" 与 `行政效率` 不同量纲，照抄必错。两条出路：① 全式改千分制 `(E‰×(1000−c‰) + c‰×1000) ÷ 1000`；② 把 E 钳到 [0,1000] —— 而"钳"直接违反用户"**都不封顶**"裁定（`:33-35`）⇒ 只能选 ① 或另立 `‰`↔`[0,1]` 的显式换算，需裁定 |
| （1 − 补偿） | **无对应量**（全仓 0 命中，见 Q3） | 无 | 现有 4 个静态修正全是**乘性**（`×‰/1000`，`GovEfficiency.java:243-246`）＋ `k` 是开方系数 —— **没有任何"加性/下限（floor）"形态的先例**。用户式是 `(1−c)·E + c` 的**加权向 floor 收敛**，是一种全新公式形态：需要新增字段（且要决定它的定义域：`c ∈ [0,1]` 还是 `c‰ ∈ [0,1000]`；`c>1` 时是否允许外推——"不封顶"精神下似乎允许，但语义变成"超过全额补偿"） |
| 补偿 归静态还是动态 | 静态面 `GovAdministrationPlan`（`:47-50`）/ 动态面 `GovEfficiencyModifier`（`:34-41`） | 待裁定 | 见 Q6 判据表。按"政策常数 → 静态"的最近先例（`k`，Z0 §3 `:93`）应归**静态**（+GM 工具载荷 `GovPayloads.java:84-123` + 决策人审批链）；若语义是"他模块按 tick 给口岸的补贴"则归**动态**（+ 第 5/7 个分量）。**用户原话没给判据 ⇒ 属原话歧义，按 §一.8.1 上报** |
| 口岸行政劳动力 | `GovServiceFlow` 的 committed/effective labor（`GovServiceFlow.java:31-42`）；`GovernmentServiceLaborBridge.Supply`（`:196-220`） | 有（同一套） | 量纲是"毫小时/tick"。要与"口岸劳动力需求"同量纲；需求方要有"计划量 `P_口岸`"（现有 `需求劳动 = P × 需求静态 × 需求动态`，`GovEfficiency.java:142-151`） |
| ÷ 口岸劳动力需求 | `satisfaction(effective, demand)`（`GovEfficiency.java:234-240`）：`有效劳动×1000 ÷ 需求劳动`，需求 0 ⇒ 1000‰ | 有形态 | **两处口径差异要定**：① 现有满足率分子是**有效劳动**（含超编开方，`:153-164`、`:221-231`），用户写的是"劳动力/需求"（**不带开方**）⇒ 口岸吃不吃开方？② 现有满足率是 **‰**（分母 0 ⇒ 1000‰），用户式是纯比值（分母 0 未定义）⇒ 需定 0 需求语义（沿用 1000‰ 还是别的） |
| 整体合成 | 总效率 = 两维效率**相乘** ÷1000（`GovEfficiency.java:187-190`） | 形态有 | **第三维合成口径未定**：是 `总行政效率 × 口岸效率 ÷ 1000`（三维连乘，`‰²` 量纲要处理），还是口岸效率作为**独立读数**（用户说"算一个**额外的**口岸效率"，且"口岸政策/兑换，另有人查"）？两者对外契约不同：前者改 `efficiencyPerMille`（会影响**征税**，`JurisdictionDailyTax.java:163-167`、`:498`）；后者不动现有征税读数、只新增一个读口 |
| 「口岸维护」生产方式 | gov 专属 `office` 产业的一个维（`EconomyGovUnitUpserts.java:99-100`） | 需澄清 | 用户把"治安维护/行政与税收维护"当**并列的生产方式**，代码里它们是**同一 office 生产方式下的两个维**（Q4）⇒ 字面映射不到；第三维 vs 第二个 office 产业，代价不同 ⇒ 需裁定 |
| 相邻边界计数 | 无现成函数（Q7） | 缺 | 需新写"辖区 hex 集 × 市场区成员集"的逐格边界计数；`MarketTopology` 在 gov 的 enforcer 禁列（`simos-gov/pom.xml:80`）⇒ 落点只能在 app（或改边界，需裁定）；辖区重叠会重复计（Q7-5） |
| 维护成本 | `GovBudgetExecutionBridge` 的类别分配（`:194-205`）＋俸禄/工资腿（`GovernmentUpkeepOracle.java:60-64`、`GovSalaryRuleBridge.java:39-50`） | 有 | "算维护成本"本身可落进既有预算链，**不需要新预算类别**（Q8）；但"成本 = f(相邻边界数)"这个函数还没有任何形态 |

★ **最重要的一条**：**用户公式的输入/输出量纲必须先裁定**——(a) 效率用 `‰` 还是 `[0,1]`；(b) `补偿` 用什么量纲、归静态还是动态；(c) 口岸满足率吃不吃超编开方、需求 0 的语义；(d) 口岸效率是并入总行政效率（影响征税）还是独立读数。这四条不裁定，实现方只能猜；按 AGENTS §一.8.1 属"用户原话歧义 ⇒ 上报"。

---

## 4. 三档缺口清单

### A. 已有代码、可直接复用（有落点、有守卫）

1. 两维计划/3 档岗位/4 静态修正/`k` + GM 工具 + 载荷 + Codec + ChangeSet：`GovAdministrationPlan.java:43-51`、`GovPostTier.java:20-29`、`GovPayloads.java:84-123`、`GovSetEstablishmentTool.java:47`、`SetAdministrationPlanHandler.java:58/61`、`GovChangeSet.java:34`。
2. 行政效率冻结公式（需求劳动/超额/开方/满足率/供给修正/相乘、全不封顶、溢出 fail-closed）：`GovEfficiency.java:133-202`、`:205-269`、`:422-433`。
3. 动态修正注入面（typed record、GM 不可达、逐 tick 替换、消费后清空）：`GovEfficiencyModifier.java:34-41` + `PopulationEconomyTimeParticipant.java:231-260`、`:711-723`、`:1291-1319`、`:1420-1429`。
4. 编制家户/承诺/劳动拆分/发薪链：`GovernmentServiceLaborBridge.java:142`、`:196-220`、`:237-307`；`LaborCommitmentKind.java:25`；`GovSalaryRuleBridge.java:39-50`；`GovernmentUpkeepOracle.java:60-64`；`GovWorldBootstrap.java:89-101`。
5. 逐格邻居与"暴露边"几何原语：`HexCoord.java:67`/`:72`；`RegionBoundary.java:68-82`。
6. 预算五类 + min/cap + 有序分配：`GovBudgetCategory.java:13-27`、`GovBudgetLine.java:17`、`GovBudgetExecutionBridge.java:194-205`。
7. 组合根已在日循环里现算市场区拓扑（同一个人口-经济参与者）：`PopulationEconomyTimeParticipant.java:564` + `MarketTopologyBook.java:88/107`。

### B. 半成品 / 有形状未接线

1. **"两维"是硬编码的**：没有泛化到 N 维的容器。实测 main 里 21 个文件含 `paperwork`（gov 11 / app 9 / economy 1），test 14 个 ⇒ 加维是**跨 3 模块的横向改动**，不是加个字段。
2. `GovServiceFlow` 是"逐维 6 读数 × 2"的手写平铺（`GovServiceFlow.java:44-58`），加维要成对扩。
3. 市场区**成员集**拿得到（`MarketRegion.members()`），但"**区与区贴边**"没有判据：`MarketTopology.adjacent` 是**锚距**口径（`:474-482`），与用户"挨着就算"不同族。
4. 告警面：`HexCrisisSignal.Kind` 有 `ADMIN_SECURITY`/`ADMIN_PAPERWORK`（`HexCrisisSignal.java:82-84`）、`GovDaily` 有 9 个 KIND（`:93-118`），**没有口岸类**；加维要新增 kind。
5. 预算类别是"支付性质"分类：口岸维护**不需要**新类别，但也**没有**"按服务维设 cap/优先级"的表达（Q8）。
6. `GovInfoTool`/`CatalogTool` 的视图与工具描述都按两维写死（`GovInfoTool.java:286-314`、`CatalogTool.java:367-376`、`SimosToolSource.java`）。

### C. 完全没有（从零开始）

1. **口岸/关口/海关/关税/税关/报关/Customs/PortXxx 在全部 `.java` 里 0 命中**（检索写法见 Q3）：无类型、无枚举、无状态、无命令、无工具、无 Codec、无前端字段。
2. **「行政效率对口岸补偿」参数**：无字段、无常量、无注入路径；且现有 4 个修正全是乘性、**没有加性/floor 形态的先例**（§3）。
3. **「共享边界计数」函数**：无。`RegionBoundary` 只吐顶点环（不吐边数/对区），`MarketTopology.adjacent` 是锚距不是贴边。
4. **口岸收入侧**（关税/过境税/兑换）与"市场区归属 vs 辖区"的持久关系：无（市场区是零持久化派生件；行政区与市场区代码上零关系——兄弟报告 `2026-10-08-market-zone-and-currency-investigation.md:45-58`）。
5. **辖区互斥不变式**：无。map 允许一个 hex 属多个 Region（`RegionIndex.java:12-16`），与用户这次"**重叠辖区是不被允许的**"直接冲突 ⇒ 需裁定（兄弟报告 `2026-10-08-admin-region-and-extraction-investigation.md:383-399`）。
6. `simos-sd` 不 import gov（实测 `rg -l 'io.mosire.simos.gov' simos-sd/src/main/java` = 空）⇒ 加维不会波及 sd，但也意味着**没有第三处消费者**可参考。

---

## 5. 我没核到的（未验证清单，不得当结论）

1. **没有运行任何代码/Maven/测试**（红线）：本报告全部结论是静态阅读 + `rg`，**无行为证据**、无 surefire 数字、无 run 读数。
2. 未核 `GovCodec` / `GovSnapshot` / `GovChangeSet` 对 `GovAdministrationPlan` **逐字段**的线格式与旧档兼容细节（只确认它是 record、随 `GovState` 树整体过 Jackson，未逐字段定制）；"加一个字段后旧档怎么读"未验证。
3. 未核 `GovernmentFormation` 的**全部** `with*` 重建点是否都带过 posts/源状态（Z0 §4.3 `:129` 要求"任何 `withGovernment*` 重建点不得漏带 posts/源状态"）；本次只看了 record 形状、`projectedStaff`、外部岗位。
4. 未核 19 hex 世界（SmallWorld / GovWorldBootstrap 跑出来）里**实际有几个市场区、相邻关系如何**——那要跑 world 才能看；本报告只读了拓扑的构造代码。
5. 未核招募两路（`simos.gov.expandHousehold` / `openPostsToMarket`）的 V1 落地程度，也未核"口岸编制户扩张"会走哪条路。
6. 未核 `GovDaily.assessUpkeep` 的逐资源定额算式与"编制人数 → 定额"的系数细节（只看清了它在预算里对应 `ADMIN_STIPEND`，`GovBudgetExecutionBridge.java:194-198`）。
7. 未核 `MarketTopologyBook` 的 tier→半径表与"同币即同区"在生产世界的实际取值（只读到 `:125-139`、`:75-83` 的口径；具体映射表内容未逐项读）。
8. 未核 WebUI 面板/API 是否已有"行政效率"展示点会受影响（只核了 `口岸/customs` 关键字 0 命中，未读 JS 面板结构）。
9. 未核 `docs/superpowers/status/2026-10-23-planned-not-implemented-inventory.md` 全表里与口岸/关税相关的既列项（只核了兄弟报告引用的 `:141` D-026 那条）。
10. 未核"口岸政策/兑换"另一调查的任何结论（Lead 明说另有人查）——本报告不越界。

---

## 6. 交账速览

- **一句话**：公式的两项输入在代码里都有对应量，但 `补偿` 项、`(1−c)` 的 floor 形态、"口岸"这个维/生产方式全仓 0 命中；真正卡住的是四分量纲/合成口径未定 + 边界计数无现成函数且 `MarketTopology` 在 gov 禁列。
- **三档缺口**：A 可复用 7 条（§4.A）／B 半成品 6 条（§4.B，核心是"两维硬编码"跨 21 个 main 文件）／C 完全没有 6 条（§4.C）。
- **矛盾点**：§3 表格 8 行，最重的四条 = 效率量纲（‰ 不封顶 vs 百分比）、补偿归属（静态 vs 动态）、口岸满足率是否吃开方 + 需求 0 语义、口岸效率是否并入总行政效率（影响征税 `JurisdictionDailyTax.java:498`）。
- **关键 file:line**：`GovAdministrationPlan.java:43-51`／`GovEfficiency.java:133-202`、`:187-190`、`:221-269`／`GovEfficiencyModifier.java:34-41`／`PopulationEconomyTimeParticipant.java:231-260`、`:564`、`:1248-1319`／`GovernmentServiceLaborBridge.java:142/196-220/237-307`／`GovBudgetCategory.java:13-27`／`GovBudgetExecutionBridge.java:194-205`／`RegionBoundary.java:68-82`／`HexCoord.java:67`／`MarketTopology.java:443-482`／`MarketRegion.java:26`／`simos-gov/pom.xml:76-83`／`GovPostTier.java:20-21`。
