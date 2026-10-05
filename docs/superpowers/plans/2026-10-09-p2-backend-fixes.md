# 2026-10-09 P2 后端修复清单（准备稿）

> 状态：**P2 尚未开工。** 本文件把 P0/P1 实验批次暴露出的后端问题整理成可派单的修复面。
> 基线：`HEAD ce55cb63`，回退点 tag `agent-experiment-baseline-20261009`。
> 纪律：测试迁移是 P2 的前置；前端不急。实现批次沿用 AGENTS §一.10，compile 门禁推进，测试后置；
> 用户已裁定的口径（唯一 `production-runtime`、政府内置、`Unit.manpower` 退役、装备保留、账户/家户合并方向、
> 家户每 tick 劳动时间有限、利润率排队等）不得被实现 Agent 颠覆。

## 0. P2-0 前置：测试迁移（必须先做）

**已暴露事实：**

- P0/P1 只跑了 `./mvnw -q -pl simos-app -am -DskipTests compile`；`test-compile`/`test`/`verify` 全未跑。
- 旧测试仍引用已删除的 `ClassFirst*`、`Unit.manpower()` 与旧 API，整仓 test 编译必然失败。
- 后果：`package` 走不通 ⇒ `run-small-world.sh` 的真实路径无法验证；`clean verify` 无法作为总验收。

**P2-0 要做：**

1. 删除/迁移直接引用旧架构的测试（class-first 一族、manpower 一族、旧 `ClassRow`/`ClassStanding` 语义的测试）；
2. 保留并迁移铁律 5 往返、守恒、模块边界、审批/权限等仍有效的护栏；
3. 补新架构的入口级测试骨架，先恢复 `test-compile`；
4. 之后才允许做 P2 的任何新功能实现与真实起服。

## P2-A：身份 / 账户 / Membership / 劳动口径统一（地基层）

| ID | 问题 | 当前证据 | 影响 | 建议 |
|---|---|---|---|---|
| A1 | **两套 HouseholdId** | Social 播种 `hh:rural:<hex>` / `hh:urban:<cityId>`（`PopulationSeeder`）；Economy `ClassRow` 用 `HouseholdIds.ofSeed` 的 `hh-<hex>-<residence>-<stratum>` | 账户、Unit.households、Membership、Economy 行各指一套身份，无法真正“跟着家户走” | 裁定唯一家户身份；Economy 侧改为 `HouseholdId` 的 facet 表，而不是另造经济家户 id |
| A2 | **账户键是 `(ActorRef, HexCoord)`** | `GoodsAccountKey` | 家户换 hex 账户不自动跟走；同一家户可有多本账；与“单个家户不能跨 hex”冲突 | 账户身份收敛到家户/actor；位置由 `Household.location` 派生；旧账走 `actor.MoveAccount` 或旧世界废弃重建 |
| A3 | **Membership 在 EconomyData** | `Membership(lot, household, count)` + `MembershipWriteback` | 本该 Social 维护的“谁属于哪个家户”的经济侧有了第二本计数 | 迁到 Social（`Household` 成员份额表）；Economy 只读 projection |
| A4 | **劳动三本账** | `ClassRow.laborMilli`（日毛量）/ `LaborSupply`（批次、周期容量）/ `LaborAllocation`（批次+家户→unit、千分劳动·日） | 没有“家户每 tick 有限时间预算”的唯一权威；日/周期口径也不齐 | 统一为家户每 tick 时间预算；`LaborAllocation` 只表示预算在生产方式/unit 间的分配 |
| A5 | **Membership 数量与 Social 关系不对应** | Social `Household.memberLots` 只列 lot；数量在 `PopulationGroup`；跨户拆分靠 Economy `Membership` | 一处人属于多户时 Social 无法独立表达份额 | Social 持 `(lot, household, count)` 份额；Economy 不得再持计数 |
| A6 | **ClassRow 1:N 与身份** | 一个 Social 地点家户 ↔ 经济侧 4 条阶层行（不同 `HouseholdId`） | “家户经济属性”被拆成多个主体 | 裁定：经济侧是否需要 4 个独立家户；若要，Social 也必须建同数家户；否则 Economy 用 `(HouseholdId, ClassPositionId)` facet |

## P2-B：多生产方式 / 利润率排队 / 生产配置

| ID | 问题 | 当前证据 | 影响 | 建议 |
|---|---|---|---|---|
| B1 | **一家户只有一个当前生产方式** | `ClassStanding.currentPositionId` 单值；`EconomyOrganizationSettlement.organize` 只给当前 position 建组织 | 男种田后女/小孩不能自动接纺织；无法多生产方式组合 | 家户持“生产方式组合/可参与集合”；`ClassStanding` 不再兼职“唯一职业” |
| B2 | **劳动分配不是利润率优先** | `reallocateLabor` 按 unit 缺口大者先分、最后兜底产粮 unit | 不能实现“种田最多 20h，剩 4h 按利润率接纺织” | 每个生产方式/unit 求“单位劳动净收益 + 本 tick 最大可吸收劳动”，按利润率降序填家户时间预算 |
| B3 | **ExpectedProfit 只用于迁移，不用于家户内部排队** | `ExpectedProfitBook` → `ModeMigrationPolicy` → 新家户/新 mode | 一家户不能在同一 tick 内按利润组合多个 mode | 抽出“单位劳动预期净收益 + 可吸收量”作为家户劳动分配排序键 |
| B4 | **缺家户需求/产能/生产方式配置工具** | `economy.AddDemand` 是后端命令；`EconomyGmAdjust` 有 mode/structure/position/org/relation/assetRule/candidate；缺家户 demand/capacity/mintScale 窄工具 | 决策人/GM 不能方便地把某个政府家户加入市场、配置生产 | 后端命令先行：家户 demand、家户产能/参与率、家户可参与 mode、生产组织 upsert；Agent 工具后置 |
| B5 | **自定义 Class 只有数据级入口** | `ProductionMode`/`ClassStructure`/`ClassPosition` 可由 `economy.GmAdjust` upsert；`ClassPosition` 仅三固定维 + `ruleExtensions: Map<String,String>` | 新类型化阶层规则仍要改 API；家户不能直接挂任意自定义 Class | 明确 `ruleExtensions` 与类型化 facet 的边界；必要时新增“家户 Class 接入”后端命令 |
| B6 | **生产组织对政府家户不可达** | production-runtime 内置 official 家户 population=0、无 ClassStanding/labor/资产 | 政府家户只当国库，不能需求/生产/消费 | 给政府家户补人口/劳动/资产/ClassStanding，或让 Gov 有自己的组织路径 |

## P2-C：政府家户进入市场与生产

| ID | 问题 | 当前证据 | 影响 | 建议 |
|---|---|---|---|---|
| C1 | official 家户零人口、无 ClassStanding、无劳动/资产 | `EconomySeeder.governmentClassRow` population/labor=0；无 standing | 地方/中央政府不能被“加入市场” | 政府家户人口来源、政府职位/阶层、劳动预算、初始资产/库存要可配置 |
| C2 | `Government.treasury` 是家户 actor，但无消费/需求/生产 | `Government` record；`GovernmentSeigniorage`/`GovernmentDebtIssuance` | 政府只发币/发债，无政府需求与政府采购路径 | 给政府家户 `DemandEntry` + 预算/政策；采购走市场/关系 |
| C3 | 多 region 政府先到者胜 | P0.1 报告：每个 seed 生成 official 家户，世界政府只认第一份 | 多国/多省时的政府语义未闭环 | 定义政府家户与 region/nation 的映射；一政府一家户或一政府多户 |
| C4 | 铸币生产式算法未做 | 小世界计划 M1；当前只有 cycle `seignioragePerCycle` | 生产式铸币/政府家户劳动投入不存在 | 按用户“先 GM 工具意思意思、后实验算法”的顺序，放到 P2 后期 |
| C5 | 政府读口只有后端 `/api/economy/gov` | P1.4 已加；未真实 HTTP 验证 | 无 | 测试迁移后补真实起服冒烟 |

## P2-D：P0.1 删除 class-first 时一起消失的功能

| ID | 丢失功能 | 证据 | 影响 | 建议 |
|---|---|---|---|---|
| D1 | `GovDaily` 行政税/俸禄、`JurisdictionDailyTax` | P0.1 报告；`EconomySettlement` 新参与者未调用 | 政府单位日常维持、税/俸禄循环消失 | 把 GovDaily/JurisdictionDailyTax 重新接到 `PopulationEconomyTimeParticipant`/`EconomySettlement` 的新路径 |
| D2 | `economy.UnitBorrow` / `UnitRepay` | P0.1 删除 handler + `UnitDebtPlan` + `IssueDebtTool`/`RepayDebtTool` | 单位向放贷方借还的入口消失 | 基于新账户/家户身份重建，或明确由 GOV 家户/财政路径替代 |
| D3 | 多 region 政府语义 | 同上 | 多国/多省不一致 | 与 C3 一起裁定 |
| D4 | 前端 classFirst 读口 | webui 未适配；不在 P2 前端范围 | 页面可能读空/NaN | 前端另批，不阻塞 P2 后端 |
| D5 | 旧 class-first 测试 | 24 个测试文件 | test 编译失败 | P2-0 测试迁移处理 |

## P2-E：经济正确性的正式验收

| ID | 问题 | 当前证据 | 影响 | 建议 |
|---|---|---|---|---|
| E1 | 质押跟随无正式测试 | P0.2 只有 /tmp 探针；`RealTwelveHex*` 未跑 | 真实 12hex 质押路径未验收 | P2-0 后跑 `RealTwelveHexProductionRuntime3650Test`、`RealTwelveOneTickTraceTest`、`RealTwelveMarketFreezeDiagnosisTest` |
| E2 | 承运/商号实收无正式测试 | P0.2 探针显示 merchantFee>0；`SevenHexFullChain3650Test` 未跑 | 7hex 商号收入未验收 | 跑正式测试；若仍红，查 `MarketReport` lane/bottleneck/LOGISTICS_CAPACITY |
| E3 | 0 价免费/运费解耦未标定 | 当前运费基数 1/1/2/3、承运成本 25‰/档 是粗估；`freightOf` 已解耦货价 | 数值可能离谱，但方向正确 | 按用户“可写不准、必须有表示”保留；后续调参并补验收 |
| E4 | 自承运没有独立运费读数 | P0.2 具名收窄：`principal == buyer` 不铸 `CARRIER_FEE` | “自承运”读不出运费 | 需要时给 MarketReport 加具名读数或规则 |
| E5 | `HexTradeCost` 货币侧仍 0 | D-027 口径；单 hex 贸易成本只有实物损耗 | 读口“单 hex 成本”看不到钱 | 与 B2 的运输成本统一 |
| E6 | ExpectedProfit 负值只修了溢出，未跑正式断言 | P0.2 报告 | 仍有其他负值来源可能 | P2-0 后跑 `SevenHexNatural3650Test`；若红按 `ModeMigrationPolicy` 守卫定位 |
| E7 | 商号运力只在关账日重置 | P0.2 具名收窄；`anyCycleClosed=false` 时运力不重置 | 跨周期行为可疑 | 与 merchant cycle 生命周期一起收口 |
| E8 | 商号世界区内撮合退化单线程 | P0.2 为全局运力正确性牺牲并行 | 性能下降 | 后续修复；不得为并行牺牲运力唯一性 |

## P2-F：行政 / 世界 / Log 剩余缺口

| ID | 问题 | 证据 | 建议 |
|---|---|---|---|
| F1 | map 区划命令下游重算未做 | P1.2 报告：Merge/Split/Reassign 只改 map；jurisdiction/城市/税率/编制重算未编排 | app 组合根一条 revision 协调 |
| F2 | `MoveCapitalPlan` 不搬人口 | P1.2 报告 | 首都迁都时显式调用 `social.MovePopulationLots` 或新组合 |
| F3 | `DeleteNation` 的 Directive 清理路径缺 | P1.2 报告 | 补删/作废 Directive 的后端入口，或明确拒绝语义 |
| F4 | `DeleteCity(deletePopulation=true)` 不检查 economy 引用 | P1.2 报告 | 显式清理/具名拒绝 |
| F5 | `social.UpdateCity` 仍静默忽略 `at` | 旧缺口 | 改为具名拒或接受 `at` |
| F6 | `config/shell.json` 默认已切 `small-world` | P1.4 报告 | 由用户裁定保留还是回退 `v17levant` |
| F7 | 未真实起 Shell/DB/GUI、未跑 `run-small-world.sh` | P1.4 报告；test-compile 失败 | P2-0 后 package + 小世界冒烟 |
| F8 | Log 只做了骨架，未真实采集 | P1.3 报告；sd 约 11 handler 未覆盖；gov 无生产调用者 | 接线后逐模块补，运行时验证 |
| F9 | 全仓 spotless/spotbugs 仍有既有债 | P0/P1 报告 | 独立清理批，勿混行为修复 |

## P2 建议执行顺序（未定稿）

```
P2-0 测试迁移（恢复 test-compile，旧架构测试删/迁）
  ↓
P2-A 身份/账户/Membership/劳动口径统一（地基，先设计文档）
  ↓
P2-B 多生产方式 + 利润率劳动排队 + 家户生产配置工具
  ↓
P2-C 政府家户进入市场/生产 + 多 region 政府语义
  ↓
P2-D 恢复 GovDaily / UnitBorrow / UnitRepay 等被删功能
  ↓
P2-E 经济正确性正式验收（质押/承运/0 价/ExpectedProfit）
  ↓
P2-F 行政/世界/Log 剩余缺口 + 真实小世界冒烟
  ↓
最后：clean verify + 新架构测试 + 前端适配另批
```

## 需要用户先裁定/确认的点

1. **唯一家户身份**：Social 与 Economy 用同一个 `HouseholdId`；当前 Economy 一侧的 4 条阶层行如何处理（改成一个家户的多个 facet，还是 Social 也建 4 个家户）？
2. **账户身份**：账户键去掉 hex，改为 actor 唯一；旧账用 `MoveAccount` 合并还是旧世界重建（按既有“旧世界不迁移、重建”口径）？
3. **Membership 迁移到 Social**：Social 是否新增 `(lot, household, count)` 份额表；Economy 只读 projection？
4. **劳动口径**：确认统一为“家户每 tick 有限时间预算”，单位用 `laborMilli` 还是直接时间（分钟/小时）？男女小孩合算系数是否固定为“男 × 3/4”这种口径？
5. **利润率排队**：排序键是“预期单位劳动净收益”还是“真实已实现利润”？并列/限额/无吸收时如何落读口？
6. **多生产方式**：同一家户是否允许同时存在多个 organization/unit；还是“同一 tick 内按顺序填满”即可？
7. **自定义 Class**：`ClassPosition` 的类型化扩展边界；是否新增直接给家户挂/改 ClassStanding 的后端命令？
8. **政府家户**：中央/地方是各自一个家户还是多个；人口/劳动/资产从哪里来；铸币生产式算法是否仍按“后置”处理？
9. **P2 范围**：是否包含前端适配与 Agent/MCP 工具包装；还是继续只做后端。

## 总验收口径（沿用 AGENTS）

- 实现阶段：每批只要求 `-DskipTests compile` 过；
- 总验收：P2 全部落地后跑 `clean verify` + 新架构测试 + 真实小世界冒烟 + 用户判据；
- 未跑、未验证的一律具名写入报告，不写成“通过”。

---

## 13. 用户第二轮确认后的口径（2026-10-09，supersedes §12 冲突处）

> 本节是 P2 的当前设计口径；前面“需要用户裁定”里已确认的条目以本节为准。

### 13.1 家户与阶层

- **家户 = 某个阶层里的具体社会团体**；一个 hex 可以有多个不同阶层的家户。
- 初始默认：**一个 `(hex, 居住类型, 阶层)` 一户**；模型允许同一阶层多户，后续文化系统/组织系统再扩展。
- **阶层由 Economy 维护**：`ClassRow` / `ClassStanding` 是经济面事实；Social 的 `Household` 只存“谁、住哪、由哪些人组成”。
- **家户间成员转移 = 阶层转移的表达**；同一 `PeopleLotId` 可按 count 拆给多个家户（Membership 支持份额）。
- Social 与 Economy 必须用**同一个 `HouseholdId`**，不再有 `hh:rural:<hex>` 与 `hh-<hex>-<residence>-<stratum>` 两套。

### 13.2 Membership

- `Membership` 是**家户实际人口组成表**，归 **Social**。
- 形状：`(PeopleLotId, HouseholdId, count)`；同一批次可被多个家户按 count 持有。
- Economy 只读 projection，不再在 `EconomyData.memberships` 持第二本计数。

### 13.3 账户

- 账户是**家户经济状态**的一部分，不应从家户里拆出来。
- 目标形状：**一个家户一本账**；账户键不再带 `HexCoord`，位置由家户的 `HouseholdLocation` 派生。
- 旧账户**直接报废**，按既有“旧世界不迁移、新世界重建”口径处理。
- 待确认的边界：庄园/作坊/政府/组织这些非家户经济主体，是各自一本按稳定 actor 的账户，还是也必须映射到家户账户。

### 13.4 劳动

- 单位改为**小时**；每个家户每个 tick 有有限劳动时间预算。
- **每 tick 重新计算**分配，不在生产周期开始时锁死。
- 男女/小孩不逐人算：先取一组合理默认系数（写成可调参数），后面调试再校准。
- `LaborAllocation` 只表示“这笔家户时间分给哪个生产活动/unit”；`LaborSupply` 不再作为第二权威。
- 分配必须考虑投入成本：**如果需要的生产资料借都借不到，该生产方式本 tick 可吸收劳动为 0，对应劳动就是空缺**，不能凭空开工。

### 13.5 多生产方式与利润率排队

- 同一家户同一 tick 可以挂多个生产方式/生产单元。
- 排序键 = **预期单位劳动时间净收益**（不是每单位产出利润率；两者在劳动约束下不同）。
- 每个生产方式本 tick 有“最大可吸收劳动”（由产能/投入/资产约束决定）。
- 从预期单位劳动净收益最高的生产方式开始填，填满再填下一个，直到家户时间用完或没有可吸收的生产方式。
- 并列时待定 tie-break；预期为负的生产方式不参与或排最后，按设计文档写死。

### 13.6 可编辑性

- 理论口径：**凡能记录的家户经济状态都应能通过后端命令修改**；前端/MCP 工具后置。
- P2 后端至少补：
  - 家户 Class（`ClassStanding`）编辑；
  - 家户需求（demand）配置；
  - 家户劳动时间/参与率/产能配置；
  - 家户可参与生产方式/生产组织/关系/资产规则配置（复用 `economy.GmAdjust` 并补齐）；
  - 政府家户人口层/经济层配置。
- 决策人/GM 都能调用这些后端写口；具体权限与审批链在 P2 设计里定。

### 13.7 政府家户

- 中央和地方**各自恰一个**政府家户。
- 决策人/GM 可以直接配置该家户的**人口层、经济层**（成员、劳动、Class、需求、生产、资产、国库）。
- `Government.treasury` 指向该家户；政府需求/采购/生产走家户经济链路。
- 铸币生产式算法仍后置；P2 先保证政府家户可被加入市场、可被配置需求与生产。

### 13.8 P2 范围

- P2 **只做后端** state / command / handler / 读口后端；不包 MCP 工具、不包 GUI、不动前端。
- P2-0 测试迁移仍是前置：先删/迁旧架构测试，恢复 `test-compile`。
- 总验收最后再跑 `clean verify` + 新架构测试 + 真实小世界冒烟 + 用户判据。
