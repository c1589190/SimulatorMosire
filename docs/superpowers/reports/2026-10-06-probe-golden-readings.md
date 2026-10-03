# 探针黄金读数与 probe → runtime 对照（P0 冻结，2026-10-06）

> 依据：`docs/superpowers/plans/2026-10-06-probe-to-runtime-migration-plan.md` 的 P0 节、§2 映射表、§7.2 只读规格纪律。
> 输入：四份探针报告。只读规格：`simos-economy/src/test/java/io/mosire/simos/economy/market/ProbeEconomy.java`
> 与 `market` 包下的探针测试（本文件未改它们）。
> 机器可读交付：`simos-economy/src/test/resources/fixtures/probe-golden-readings.json`
> （`version: 1`，24 个 scenario）。
>
> ★ 本文件的所有 `expected` 读数都只来自四份报告；`inputs` 来自报告 + 探针测试的只读装配。
> 报告与探针代码冲突处一律登记在 §6，不静默取一个。

## 0. 用途与纪律

1. **用途**：作为 P9 的 probe → runtime 对拍目标。fixture 的每个 scenario 有 `id`、`probeTest`、
   `inputs`、`expected`、`tolerance`、`notes` 六项。
2. **冻结对象是探针黄金读数**：expected 是报告印出的整数读数；不是正式运行时的“建议值”。
3. **实现 Agent 不得改 expected/tolerance**（plan §7.2）。P9 若因正式运行时粒度、舍入或口径差异需要放宽，
   必须单独提交 fixture override 并写明裁定理由；不允许“改断言迁就实现”。
4. **没有出处就不写**：fixture 新增数字前，必须能在四份报告之一找到对应读数。
5. 本 P0 只新增本文件与 JSON 资源；未改 `AGENTS.md`、任何测试/探针代码、任何 `src/main` 代码；
   未运行 Maven/test（JSON 校验命令见 §7）。

## 1. 来源、单位与容差

### 1.1 来源报告

| 代号 | 报告 | 本 fixture 覆盖 |
|---|---|---|
| **R-LONG** | `docs/superpowers/reports/2026-10-04-market-probe-longrun.md` | 长程饥荒、闭环正面对照、食物/劳动/生产、劳动优先级 |
| **R-TEN** | `docs/superpowers/reports/2026-10-05-tenancy-wage-cycle-results.md` | 租佃/雇农 120 轮基准、240 轮单次歉收、1460 轮年度歉收 |
| **R-DIV** | `docs/superpowers/reports/2026-10-05-diversified-transport-handicraft-results.md` | T1 运输队、H1 手工业、自然死亡、D1 三部门 365×4 |
| **R-CITY** | `docs/superpowers/reports/2026-10-05-city-merchant-probe-results.md` | 19 格中心城 3650 tick 终局读数 |

### 1.2 量纲口径

| 层 | 量纲 | 备注 |
|---|---|---|
| 探针 fixture（本文件与 JSON 的 expected） | 整数：粮/布/纤维（商品单位）、钱、劳动、‰ | 报告里的原始读数 |
| 正式运行时 | **毫单位**：毫粮/毫布/毫纤维、毫钱（银 scala=3）、千分劳动 | `EconomyVocabulary.MILLI_PER_COMMODITY_UNIT = 1000`；`Market.prices` 的量纲是“毫计价货币 / 1 商品单位” |
| 对拍规则 | 比较前由 P9 comparator 按既有换算点折算，禁止在 fixture 里混用两套单位 | 探针里“1 粮” = 正式“1,000 毫粮”；探针里“1 钱” = 正式“1,000 毫钱” |

★ **注意**：报告里的“1 粮、1 钱”是探针口径的整数单位；正式运行时是毫单位。P9 必须先做单位换算再比 expected，
不得把 100 与 100000 直接比成不一致。

### 1.3 tolerance 结构与 P0 策略

JSON 中每个 scenario 的 `tolerance` 形如：

```json
{
  "default": {"mode": "exact", "absolute": 0, "relativePerMille": 0},
  "overrides": {
    "avgIssuancePerRound": {"mode": "absolute", "absolute": 1, "relativePerMille": 0}
  },
  "unit": "probe-unit ...",
  "note": "exact = 整数逐值相等；overrides 按 expected 的键名覆盖 ..."
}
```

- **默认 exact**：`absolute = 0`、`relativePerMille = 0`，即 fixture 里印出的整数逐值相等。
- **absolute override**：`|actual − expected| ≤ absolute`。
- **relative override**：`|actual − expected| ≤ |expected| × relativePerMille / 1000`。
- 本 fixture 只对报告自身用“≈”给出的两个 D1 平均量（`avgIssuancePerRound ≈ 151,976`、
  `avgRepaymentPerRound ≈ 129,556`）给了 `absolute = 1` 的 override；其余都是 exact。
- **不允许**实现 Agent 为了让测试变绿而把 exact 改成 relative；P9 放宽必须记录裁定。

## 2. probe → runtime 对照表（plan §2）

> 正式字段名取自 `simos-economy/src/main/java/io/mosire/simos/economy/model`、
> `simos-economy-api/src/main/java/io/mosire/simos/economy/api`、
> `simos-economy/src/main/java/io/mosire/simos/economy/time` 与
> `simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java` 的现有命名；
> 计划新增类在括号里注明阶段，不把“计划”写成“已存在”。

### 2.1 市场与价格

| 探针字段/机制 | 正式字段/类（阶段） | 期望值/出处 | 允许偏差 |
|---|---|---|---|
| `Params.alphaUpPerMille`、`alphaDownPerMille`、`undercutPerMille`、`pMax` | P4 新 `MarketSettlement` 的规则 A/B/C 参数；`Market.prices` 只是价格快照；GM 参数走 P7 `EconomyGmAdjust` | 闭环 3000 轮 `grainRef=100`、`clothRef=400` 不变（R-LONG §5.5）；长程稳定期 `ref=10000`（R-LONG §3） | exact（单位换算后） |
| `Hex.ref`、`currentPrice(hex, good)` | `Market.prices`（`EconomyData.markets` 的 `Map<HexCoord, Market>`）；`MarketNode`/`MarketRegion`；P4 `MarketReport.ref` 读口 | D1：H0 粮/纤维/布 `100/25/189`，H1 `121/25/157`（R-DIV §3） | exact（单位换算后） |
| 规则 C：本轮无成交 ⇒ `ref=0`；下一次用初始价重新定价 | P4 `MarketSettlement` 规则 C；`MarketReport.ref`；`ApiViews` 读口 | 无印出具体数字；P9 用 `RuleCNoTransactionProbeTest` 对拍 | P0 未冻结数字 |
| `recipeStopped`：`ref > 0` 且 `ref < cost` ⇒ 停产；`ref > cost` 继续 | P3 `ProductionSettlement`（计划重建）、`ProductionUnitBook` 容量/资产约束、`StressPolicy` 停产/收缩判定 | 无印出具体数字；P9 用 `ProductionExitOnLossProbeTest` 对拍 | P0 未冻结数字 |
| 买家按总财富降序；无出价方被跳过；按“最低到货价”选卖方 | P4 `MarketSettlement` 撮合：`BuyOrder`/`SellOrder` + `Budget` + `Recipient` + `MarketReport`；plan §2 的“买家财富降序、无出价、最低到货价” | D1/7HEX2 的跨格成交与 `outerBuyerDebt=11341500` 是行为样例（★ P0 勘误见 §6.4）；报告未单列排序读数 | P0 不冻结数字；P9 用独立用例验证排序与“无出价”行为 |

### 2.2 生产、劳动与土地

| 探针字段/机制 | 正式字段/类（阶段） | 期望值/出处 | 允许偏差 |
|---|---|---|---|
| `Household.population`、`needPerCapita`、`lastFoodNeed/lastFoodEaten/lastEfficiency` | `PopulationGroup`（`PeopleLotId`、`residence`、`count`、`physiologicalStress`）、`CohortKey`；`LaborSupply.grossLaborMilli`；`SubsistenceObligation`；`EconomyVocabulary.RATION_*`；P3 生产结算器 | 100 人、`laborPerCapita=1000‰`、`laborPerUnit=1`、口粮 0：need 500、eat 0、efficiency 300‰、output 30；口粮 500：eat 500、efficiency 1000‰、output 100（R-LONG §5.1–5.3） | exact |
| `Recipe.output/outputPerUnit/inputPerUnit/laborPerUnit/desiredScale` | `ProductionRecipe`（`capacityPerUnit`、`inputPerUnit`、`laborPerUnit`、`outputPerUnit`）；旧档模板 `Industry`；`ProductionUnit.cycleLaborMilli/cycleInputUsedMilli`；`ProductionOrganization` | 农场 191 劳动 → 1910 粮（R-TEN §2/§3）；断粮 30 劳动 → 30 粮（R-LONG §5.2） | exact |
| 多 recipe 优先级：主 recipe 在前，追加按加入顺序 | `LaborAllocation.laborMilli` + `ProductionUnitBook.usableAssets/capacityScaleOf` 的 `Σ劳动 ≤ 可用` 守卫；P3 `ProductionSettlement` | 100 劳动、两个各要 60 劳动 ⇒ 60/40；断粮有效劳动 30 ⇒ 高优先级 30、低优先级 0；投入受限让出 80 劳动（R-LONG §5.4） | exact |
| C1（方案甲）= 口粮 + 生产投入预留 | `SubsistenceObligation` + `ProductionRecipe.inputPerUnit`；P3 的 `C1 = desiredScale×inputPerUnit + 预估口粮` | 100 人 ×5 粮 = 500 口粮，织布 100 单位再需 100 粮投入 ⇒ C1=600（R-LONG §5.1） | exact |
| `Tenancy.landPerUnit`、`Hex.arableMu`、`availableArableMu` | `ProductionRecipe.capacityPerUnit` 的 `AssetKind.LAND` 一路；`AssetShare`（`quantity` 千分亩、`kind`）；`ProductionUnitBook.capacityScaleOf/usableAssets`；`EconomySeeder.landMilliMuOf`；P6 的 `availableArableMu` | 7HEX2：`arableC=2999`、`arableR0=3019`、`landPerUnit=22` 把农场产出压到 1370（R-CITY §1.3/§2） | exact（土地折算与运行时毫亩换算后） |
| 生产资料/产能：探针只到 `landPerUnit` 与运输运力 | `AssetShare`（`owner`/`operator`/`asset`/`quantity`/`kind`）+ P1 `AssetRule`（`isCoreMeans`/`pledgeable`/`liquidationPriority`/`rentRule`/`transferRule`）+ `ProductionUnitBook.capacityScaleOf` | 7HEX2 `landPerUnit=22` 把农场产出从 1420 压到 1370；T1 运力 100 对需求 40（R-CITY §2；R-DIV §1） | exact（探针口径）；正式资产规则由 P1/P3 接 |

### 2.3 生产方式、租佃与雇农

| 探针字段/机制 | 正式字段/类（阶段） | 期望值/出处 | 允许偏差 |
|---|---|---|---|
| `Tenancy.rentForm`（`FIXED_KIND`/`SHARE`/`FIXED_CASH`）、`fixedKind`、`landlordSharePerMille`、`fixedCash`、`lastOutput/lastRentPaid/lastRentArrears` | P1 `ProductionMode`（`tenancy_fixed_kind`/`tenancy_share`/`tenancy_cash`）；P1 `ClassStructure`/`ClassPosition`；P2 `ProductionRelation`；P2 `CompensationRule` + `RuleType.FIXED_IN_KIND_RENT`/`OUTPUT_SHARE`/`FIXED_MONEY_RENT` + `Pool.FIXED_AMOUNT`/`NET_AFTER_INPUTS` + `Weight` + `LaborSource.TENANT`；P1 `AssetRule`（LAND）与 `AssetShare` | 定额/分成 215‰：产出 1910、租 410、P 1500 粮+120,000 钱、L 410 粮+40,000 钱、总债务=总货币=320,000、死亡 0；货币租 41,000 钱、总债务=总货币=321,000（R-TEN §3） | exact |
| `WageFarm`/`WageContract.cashPerLabor/grainPerLabor/fixedCashPerRound`、`lastGrainPaid/lastCashPaid/lastGrainArrears/lastCashArrears` | P1 `ProductionMode`（`wage_farm`）；P2 `ProductionOrganization`（organizer/laborSources/assetSources）；P2 `ProductionRelation` + `LaborSource.WAGE`；`CompensationRule` 的 `FIXED_IN_KIND_PER_LABOR` / `FIXED_MONEY_WAGE`；欠薪挂 `DebtContract`（P5 接管） | 基准工资 382 粮 + 111,800 钱、P 382 粮+120,000 钱、L 1528 粮+40,000 钱、总债务=总货币=320,000（R-TEN §3） | exact |
| `Tenancy.harvestPerMille`、`WageFarm.harvestPerMille`（歉收冲击） | P3 产出结算的 `× harvestPerMille/1000`；`FlowRow` 对账 | 240 轮冲击：三种模式死亡 559、终局人口 550；定额/分成终局债务 265,440,436，雇农制 274,508,108、最高欠薪 74,600、终局欠薪 81,080（R-TEN §4）；年度冲击 1460 轮终局见 §4.5 | 结构性读数 exact；长期货币存量允许 P9 重新裁定 |

### 2.4 债务、货币与人口

| 探针字段/机制 | 正式字段/类（阶段） | 期望值/出处 | 允许偏差 |
|---|---|---|---|
| `Household.debt`、全局 `creditIssued/creditRepaid/debtDeleted/newIssuance/newRepayment/totalMoney`、`transportEscrow` | `DebtContract`（`debtor/creditor/principal/status`）、`DebtContractBook`、`DebtTerms`、`DebtStatus`、`DefaultRemedy`、`RepaymentRule`；`MoneyIssuanceRecord`（`INITIAL_ENDOWMENT`/`FISCAL_ISSUE`/`WITHDRAWAL`）、`MoneyIssuanceKind`、`MoneyAuthority`、`MoneyIssuance`、`Government.issuable`；P5 `CreditPool`/`MoneyStock` | 长程饥荒 `totalDeaths=481`、`totalDebtDeleted=10881973`、`totalRepaid=100`、3000 轮债务 3116378227；闭环 200,000 发行/还款/债务/货币恒定；D1 `creditIssued=221884895`、`creditRepaid=189152409`、`deathDebtDeleted=1645425`、债务=货币差（R-LONG §3/§5.5；R-DIV §3.2） | 守恒式 exact；长程货币存量若正式运行时口径不同，P9 必须在 override 里裁定 |
| 死亡按人口比例删债 | `DebtContractBook` 的死亡/退出处置；`DebtStatus`/`DefaultRemedy`；P5 债务删除路径 | 长程饥荒 `totalDebtDeleted=10881973`；D1 `deathDebtDeleted=1645425`（R-LONG §4.1；R-DIV §3.2） | exact（探针口径） |
| 自然死亡 `naturalMortalityPerMillion`、出生 `birthsPerMillion`、`lastDeaths/lastBirths`、余数累计 | `PopulationDynamics.monthly`（年龄档×性别×压力 + 生理压力超额死亡）；`LotChange`（出生/死亡两数）；P8 的 `PopulationGroup` 拆/合与 `LotMigration` | 1000 人跑 1460 轮：自然死亡 100、出生 120、终局 1020、全程吃饱（R-DIV §3.1） | exact；正式 `PopulationDynamics` 更细，P9 若量级不一致须写明裁定 |
| 还债 wave：每户 `min(钱, 债)` | `DebtContractBook` 的还款规则；`RepaymentRule`；`MoneyStock` 回收 | 长程无收入时累计只回收开局 100 钱；闭环场景每轮回收 200,000（R-LONG §3/§5.5） | exact |
| 信贷池/货币存量守恒 | `MoneyIssuance` + `MoneyAuthority` + `MoneyIssuanceRecord`；`Government.issuable`；P5 `CreditPool`/`MoneyStock` | D1：初始现金 66,000 + 累计发行 221884895 − 累计还款 189152409 = 32798486；债务 = 221884895 − 189152409 − 1645425 = 31087061（R-DIV §3.2） | exact |

### 2.5 运输、城市与手工业

| 探针字段/机制 | 正式字段/类（阶段） | 期望值/出处 | 允许偏差 |
|---|---|---|---|
| `TransportTeam.fromHex/toHex/capacityPerRound/tier/homeIsCity/homeHex/serviceRadiusHex/ruralTradeCostPenaltyPerMille/lastUnitsMoved/lastFeeEarned` | P4/P6 新 `TransportOrganization`（或 `ProductionOrganization` + `trade` 活动）；`AssetKind.SHIP`/`CATTLE`/`TOOL`；`TradeRoute`（`capacityPerWindow`、`travelTicks`、`costPerUnit`、`lossPerMille`）；`ShipmentBatch` + `ShipmentAllocation`；`SellOrder`/`BuyOrder` | T1 满运力 30 粮/运费 450 全进运输队、escrow=0；运力不足 10 粮 ⇒ 运输队 150、剩余 30 粮的 450 落 escrow（R-DIV §1）；D1 运 602/602 与 400/400、承运家户 1672（R-DIV §3） | exact |
| `TransportTeam` 运费兜底 escrow | P4 `MarketSettlement` 的承运兜底/报告字段（正式字段以 P4 实现为准；禁止“没有承运组织时钱凭空消失”） | 同上：满运力 escrow=0；运力不足 escrow=450（R-DIV §1） | exact（探针口径；正式字段 P9 再钉） |
| `transportPerMille` + `roadLevels`（基础距离费 + 离城 20‰/hex − 道路 50‰/级 + 农村累积 − 城市折扣） | `TradeRoute.costPerUnit`；`GameMap.pathways`（`PathwayGroup`/`Pathway`/`EdgeTags`）；P4/P6 的城市距离折扣与道路走廊 | 7HEX2：`R1→C=0`、`R1→R2=14`、`R1→R2` 修一级路后 0、`O0→O1(road)=0`、`O2→O11(off-road)=70`、`C→O2(road)=0`、`C→O11(off-road)=20`、`R0→C` 由 0→67（R-CITY §1.2/§2） | exact（逐公式）；serviceRadius 口径见 §6.1 |
| `CityState.capacity/usedCapacity/expansionCount/builtAreaPerMille/cumulativeTradeVolume` | `simos-map/City.props`（或新 `CityLand`）；`MarketNode.radiusHex`；P6 `SettlementGenerator`/`EconomySeeder.landMilliMuOf`；`availableArableMu = arableMu − cityBuiltAreaMu` | 城市承载 10→370、`used=7549`、`expansions=18`、`builtAreaPerMille=360`、`cumulativeTrade=7534716`、`arableC=2999`、`arableR0=3019`（R-CITY §2） | exact（探针口径）；正式城市字段由 P6 裁定 |
| `migratePopulation(from,to,count)`（人口与债务随人走；每户至少留 15 人） | `PopulationGroup` 拆/合 + P8 新 `LotMigration`（源 lot → 目标城市 lot、人数、债务/劳动随行）；`ClassFirstPopulationWriteback` | 7HEX2：`migratedToCity=175`、`cityLaborer=175`（R-CITY §2） | exact |
| 手工业 `Recipe`（FIBER + 劳动 → CLOTH）、`WageFarm` 作坊工资 | P2 `handicraft_workshop` `ProductionMode`；`ProductionRecipe`（`inputPerUnit[FIBER]`、`outputPerUnit[CLOTH]`、`laborPerUnit`）；`ProductionOrganization`；`AssetRule`（WORKSHOP/TOOL）；`CompensationRule` 货币工资 | H1：作坊产 400 布、工资 60,000、欠薪 0；纤维户 100 人产 400 纤维、吃 100 粮；雇工吃满 600（R-DIV §2） | exact |
| 商人层级/城市池运力自增长（盈利 +5/轮，亏损 −5/轮） | P6 商人层级（脚夫/个体户/老板）+ `ProductionOrganization`；`City` 城区占用；P4 `TradeRoute.capacityPerWindow` | 7HEX2：`cityPoolMoved=1965`、`cityPoolProfit=-400`、`cityPoolCapacity=20750`（R-CITY §2；★ P0 勘误见 §6.4） | exact（探针口径；radius 冲突见 §6.1） |

### 2.6 GM 编辑生产方式（plan §2 最后一行）

| 探针字段/机制 | 正式字段/类（阶段） | 期望值/出处 | 允许偏差 |
|---|---|---|---|
| 探针参数（mode/租金/工资/资产规则）现在只能手改测试代码 | P7 `EconomyGmAdjust` + `EconomyAdjustTool` + `CatalogTool` + `ApiViews`；唯一语义落点 `EconomyGmAdjustments.project`；所有写入走 ChangeSet/revision | R-TEN §5.1 证明“生产方式仍是手工固定的，不会因亏损自动切换”，这是 P7 要消灭的缺口；报告没有 GM 编辑后的数值读数 | P0 不冻结数值；P9 用“预览/审计/幂等/引用完整性/版本递增/被引用不可删”用例判定 |

## 3. Fixture scenario 清单（24 个）

| # | scenario id | 探针测试 | 来源报告 | tolerance |
|---:|---|---|---|---|
| 1 | `long_run_famine_3000` | `LongRunMortalityProbeTest#mortalityDeletesDebtButStockNeedsIncomeOrCreditLimit` | R-LONG | exact |
| 2 | `circular_flow_credit_3000` | `CircularFlowCreditProbeTest#circularIncomeLetsRepaymentStabilizeCredit` | R-LONG | exact |
| 3 | `food_labor_shortage` | `FoodLaborProductionProbeTest#foodShortageLowersEfficiencyAndLaborLimitedOutput` | R-LONG | exact |
| 4 | `food_labor_full` | `FoodLaborProductionProbeTest#fullFoodAllowsFullLaborAndOutput` | R-LONG | exact |
| 5 | `food_labor_c1_reserve` | `FoodLaborProductionProbeTest#c1ReservesFoodAndProductionInputSoTheyAreNotSold` | R-LONG | exact |
| 6 | `labor_priority_normal` | `LaborPriorityProbeTest#laborIsAllocatedInPriorityOrderAcrossRecipes` | R-LONG | exact |
| 7 | `labor_priority_hunger` | `LaborPriorityProbeTest#hungerShrinksLaborBudgetAndPriorityKeepsFirstRecipeAlive` | R-LONG | exact |
| 8 | `labor_priority_input_release` | `LaborPriorityProbeTest#inputLimitedRecipeReleasesLaborToNextRecipe` | R-LONG | exact |
| 9 | `tenancy_fixed_kind_baseline_120` | `ProductionModeEconomyProbeTest#fixedKindTenancyRunsClosedCycle` | R-TEN | exact |
| 10 | `tenancy_share_215_baseline_120` | `ProductionModeEconomyProbeTest#shareTenancyRunsClosedCycle` | R-TEN | exact |
| 11 | `tenancy_cash_baseline_120` | `ProductionModeEconomyProbeTest#cashTenancyRunsClosedCycle` | R-TEN | exact |
| 12 | `wage_farm_baseline_120` | `ProductionModeEconomyProbeTest#wageFarmRunsClosedCycle` | R-TEN | exact |
| 13 | `harvest_shock_240_fixed_kind` | `ProductionModeEconomyProbeTest#fixedKindTenancyBearsHarvestRiskButKeepsPayingFixedRent` | R-TEN | exact（报告 §4 单次冲击口径） |
| 14 | `harvest_shock_240_share_215` | `ProductionModeEconomyProbeTest#shareTenancySplitsHarvestRiskWithLandlord` | R-TEN | exact（报告 §4 单次冲击口径） |
| 15 | `harvest_shock_240_wage_farm` | `ProductionModeEconomyProbeTest#wageFarmKeepsPayingTheLongTermWorkerWhileOperatorBearsLoss` | R-TEN | exact（报告 §4 单次冲击口径） |
| 16 | `annual_harvest_shock_1460_fixed_kind` | 同 `fixedKindTenancyBearsHarvestRisk...` | R-TEN | exact |
| 17 | `annual_harvest_shock_1460_share_215` | 同 `shareTenancySplitsHarvestRisk...` | R-TEN | exact |
| 18 | `annual_harvest_shock_1460_wage_farm` | 同 `wageFarmKeepsPayingTheLongTermWorker...` | R-TEN | exact |
| 19 | `transport_full_capacity` | `TransportTeamProbeTest#transportFeeGoesToCarrierInsteadOfEscrow` | R-DIV | exact |
| 20 | `transport_capacity_shortage` | `TransportTeamProbeTest#capacityShortageFallsBackToEscrowForTheRemainder` | R-DIV | exact |
| 21 | `handicraft_workshop` | `HandicraftProbeTest#workshopTurnsFiberIntoClothAndPaysCashWage` | R-DIV | exact |
| 22 | `natural_mortality_1460` | `NaturalMortalityProbeTest#naturalMortalityAndBirthsRunForFourYears` | R-DIV | exact |
| 23 | `diversified_three_sector_1460` | `DiversifiedEconomyProbeTest#diversifiedThreeSectorEconomyRunsFourYears` | R-DIV | exact；`avgIssuancePerRound`/`avgRepaymentPerRound` 允许 ±1 |
| 24 | `seven_hex_city_3650` | `SevenHexCityMerchantProbeTest#sevenHexCityMerchantsAndSlowExpansionRunTenYears` | R-CITY | exact（探针口径；radius 冲突见 §6.1） |

## 4. 关键黄金读数与出处

> 每张表的“值”都是 fixture 的 expected；出处精确到报告小节/代码块。

### 4.1 长程饥荒（scenario 1，R-LONG §2–§4）

| 读数 | 值 | 出处 |
|---|---:|---|
| 初始人口 | 510 | R-LONG §2、§3 `round=1 pop=510` |
| 终局人口 | 29 | R-LONG §3 `round=3000 pop=29`、`finalPopulation=29` |
| 总死亡 | 481 | R-LONG §3 `totalDeaths=481` |
| 累计删债 | 10,881,973（fixture `10881973`） | R-LONG §3 `totalDebtDeleted=10881973` |
| 累计还款 | 100 | R-LONG §3 `totalRepaid=100` |
| 第 3 轮总债务下降 | 964,400 → 895,762 | R-LONG §3、§4.1 |
| 第 500 轮债务 | 491,378,227 | R-LONG §3 `round=500` |
| 第 1000 轮债务 | 1,016,378,227 | R-LONG §3 `round=1000` |
| 第 3000 轮债务 | 3,116,378,227 | R-LONG §3 `round=3000`、`lateDebt=3116378227` |
| 第 3000 轮粮 ref | 10,000 | R-LONG §3 `round=3000 ref=10000` |
| 稳定期每轮发行 | 1,050,000 | R-LONG §3 `lateIssuance/round=1050000` |
| 前 200 轮累计发行 | 186,745,800 | R-LONG §3 `earlyIssuance(200轮)=186745800` |
| 后 200 轮累计发行 | 210,000,000 | R-LONG §3 `lateIssuance(200轮)=210000000` |
| 第 1/2/3/10 轮发行与债务 | 514500/964400/895762/540715 | R-LONG §3 对应打印行 |

### 4.2 闭环正面对照（scenario 2，R-LONG §5.5）

| 读数 | 值 | 出处 |
|---|---:|---|
| 人口 | 510（3000 轮不变） | R-LONG §5.5 `[CIRCLE]` 三行 |
| 每轮发行 | 200,000 | 同上 `newIssuance=200000` |
| 每轮还款 | 200,000（第 1 轮为 0） | 同上 `repayment=200000`/首行 `repayment=0` |
| 债务 | 200,000（3000 轮不变） | 同上 `debt=200000` |
| 货币 | 200,000（3000 轮不变） | 同上 `money=200000` |
| 粮 ref | 100 | 同上 `grainRef=100` |
| 布 ref | 400 | 同上 `clothRef=400` |
| 死亡 | 0 | R-LONG §5.5 “人口稳定 510、无死亡” |

### 4.3 生产、劳动与 C1（scenarios 3–8，R-LONG §5.1–5.4）

| 读数 | 值 | 出处 |
|---|---:|---|
| 生产者规模 | 100 人；`laborPerCapita=1000‰`；`laborPerUnit=1` | R-LONG §5.2 |
| 断粮口粮需求/实吃 | 500 / 0 | R-LONG §5.1、§5.2 上下文（100 人 × 5 粮） |
| 断粮效率保底 | 300‰ | R-LONG §5.2 |
| 断粮有效劳动/产出 | 30 / 30 | R-LONG §5.2 |
| 口粮 500 时效率/产出 | 1000‰ / 100 | R-LONG §5.3 |
| C1 预留 | 口粮 500 + 投入 100 = 600；无可售余粮、买家借不到粮 | R-LONG §5.1 |
| 高/低优先级产出（两个各要 60 劳动） | 60 / 40 | R-LONG §5.4 |
| 断粮时有效劳动/低优先级劳动 | 30 / 0 | R-LONG §5.4 |
| 高优先级投入受限 / 让给下一条 | 20 劳动 / 剩余 80 | R-LONG §5.4 |

### 4.4 租佃/雇农 120 轮基准（scenarios 9–12，R-TEN §3）

| 生产方式 | 产出/轮 | 租/工资 | 农家/雇农期末 | 地主/经营者期末 | 总债务=总货币 | 死亡 | 出处 |
|---|---:|---:|---|---|---:|---:|---|
| 定额实物租 | 1910 粮 | 410 粮 | P 1500 粮、120,000 钱 | L 410 粮、40,000 钱 | 320,000 | 0 | R-TEN §3 第 1 行 |
| 分成租 215‰ | 1910 粮 | 410 粮 | P 1500 粮、120,000 钱 | L 410 粮、40,000 钱 | 320,000 | 0 | R-TEN §3 第 2 行 |
| 货币租 | 1910 粮 | 41,000 钱 | P 1910 粮、120,000 钱 | L 0 粮、41,000 钱 | 321,000 | 0 | R-TEN §3 第 3 行 |
| 雇农制 | 1910 粮 | 382 粮 + 111,800 钱 | P 382 粮、120,000 钱 | L 1528 粮、40,000 钱 | 320,000 | 0 | R-TEN §3 第 4 行 |

★ R-TEN §4.5 确认：四条基准循环跑满 1460 轮后与 120 轮读数完全一致（无死亡、债务/货币稳定在 32 万左右）。
fixture 的四个 benchmark scenario 把 `rounds=120` 冻结为报告 §3 的基准读数。

### 4.5 歉收冲击：240 轮单次冲击（scenarios 13–15，R-TEN §4）

| 生产方式 | 冲击期租/工资 | 农户/雇农吃 | 经营者/地主冲击末期库存 | 欠薪/欠租 | 冲击期死亡 | 终局人口 | 终局债务 |
|---|---|---|---:|---|---:|---:|---:|
| 定额实物租 | 仍收 410 粮 | P 吃满 300/300 | L 410 粮 | 0 | 559 | 550 | 265,440,436 |
| 分成租 215‰ | 降到 164 粮 | P 吃满 300/300 | L 164 粮 | 0 | 559 | 550 | 265,440,436 |
| 雇农制 | 粮工资 382、欠薪最高 74,600 | P 吃满 300/300 | L 冲击末期 382（基准 1528） | 冲击期最高 74,600；终局 81,080 | 559 | 550 | 274,508,108 |

### 4.6 年度歉收长程 365×4 = 1460 轮（scenarios 16–18，R-TEN §4.5）

| 模式 | 第一年死亡 | 织户 W 第一年后 | 总死亡 | 终局人口 | 终局总债务 | 终局总货币 | 地主/经营者 | 农家/雇农 |
|---|---:|---:|---:|---:|---:|---:|---|---|
| 定额租 | 559 | 240 | 560 | 550 | 2,297,110,036 | 2,297,302,376 | L 810 粮、0 钱 | P 585,110 粮、9,120 钱 |
| 分成租 215‰ | 559 | 240 | 560 | 550 | 2,297,248,136 | 2,297,440,476 | L 810 粮、138,100 钱 | P 585,110 粮、9,120 钱 |
| 雇农制 | 559 | 240 | 560 | 550 | 2,380,612,788 | 2,297,302,376 | L 584,882 粮、0 钱 | P 1,038 粮、9,120 钱；终局欠薪 102,680 |

★ 每次年度歉收，定额租/分成租的租额照旧执行；雇农制继续出现欠薪（累计/终局 102,680）。

### 4.7 运输队 T1 与 H1 手工业（scenarios 19–21，R-DIV §1–2）

| 读数 | 值 | 出处 |
|---|---:|---|
| T1 满运力承运量/运费/运输队所得 | 30 粮 / 450 / 450 | R-DIV §1 |
| T1 满运力 `escrow` | 0 | R-DIV §1 |
| 运力不足只承运 | 10 粮 ⇒ 运输队 150 | R-DIV §1 |
| 运力不足剩余 | 30 粮 ⇒ 450 落 `escrow` | R-DIV §1 |
| H1 纤维户 | 100 人、产 400 纤维、吃 100 粮 | R-DIV §2 |
| H1 作坊 | 买 400 纤维、雇 600 人（400 劳动）、产 400 布 | R-DIV §2 |
| H1 工资 | 150 钱/劳动 = 60,000；连续两轮 400 布/60,000 工资 | R-DIV §2 |
| H1 欠薪 | 0（雇工吃满 600 粮） | R-DIV §2 |

### 4.8 D1 三部门 365×4（scenario 23，R-DIV §3/§3.2）

| 部门/读数 | 值 | 出处 |
|---|---:|---|
| H0 农场产出 / 租 | 1020 / 410 | R-DIV §3 表 |
| H0 佃农 P | 存粮 610、钱 31,000 | R-DIV §3 表 |
| H0 纤维户 F | 存纤维 400、钱 10,000 | R-DIV §3 表 |
| H1 作坊 | 产 400 布、工资 60,000、欠薪 0 | R-DIV §3 表 |
| H1 作坊主 O | 存布 400、钱 506,834 | R-DIV §3 表 |
| H1 匠户 A | 吃 592/600、钱 60,000 | R-DIV §3 表 |
| 运输 H0→H1 | 运 602、运费 602 | R-DIV §3 表 |
| 运输 H1→H0 | 运 400、运费 400；运输队家户 1,672 | R-DIV §3 表 |
| 人口 | 初始 1030 → 终局 1038；死亡 115、出生 123 | R-DIV §3 表、§3 结论 |
| 总债务/总货币/escrow | 31,087,061 / 32,798,486 / 0 | R-DIV §3 表 |
| 信用累计 | issued 221,884,895；repaid 189,152,409；死亡删债 1,645,425；初始现金 66,000 | R-DIV §3.2 |
| 平均每轮发行/还款 | ≈151,976 / ≈129,556 | R-DIV §3.2 |
| H0 价格（粮/纤维/布） | 100 / 25 / 189 | R-DIV §3 表 |
| H1 价格（粮/纤维/布） | 121 / 25 / 157 | R-DIV §3 表 |

### 4.9 自然死亡/出生（scenario 22，R-DIV §3.1）

| 读数 | 值 | 出处 |
|---|---:|---|
| 初始人口 / 轮数 | 1000 / 1460 | R-DIV §3.1 |
| 自然死亡 | 100 | R-DIV §3.1 |
| 出生 | 120 | R-DIV §3.1 |
| 终局人口 | 1020 | R-DIV §3.1 |
| 全程吃饱 | 是（探针口径） | R-DIV §3.1 |

### 4.10 19 格中心城 3650 tick（scenario 24，R-CITY §1.2/§2）

| 读数 | 值 | 出处 |
|---|---:|---|
| 人口 / 死亡 | 1455 / 0 | R-CITY §2 |
| R0→C 运输费率 | 0 → 67（`ruralPenalty=100`） | R-CITY §2 |
| R1→C 运输费率 | 0 → 0 | R-CITY §2 |
| 城市商人池 | `moved=1965`、`profit=-400`、`capacity=20750` | R-CITY §2（★ 见 §6.4 勘误） |
| 城市 | `capacity=10→370`、`used=7549`、`expansions=18`、`builtAreaPerMille=360` | R-CITY §2 |
| 可耕地 | `arableC=2999`、`arableR0=3019` | R-CITY §2 |
| 累计贸易量 | 7,534,716 | R-CITY §2 |
| 辐射费率 | `R1→C=0`、`R1→R2=14`、`R1→R2 afterRoad(level1)=0` | R-CITY §2 |
| 道路/无路对照 | `O0→O1(road)=0`、`O2→O11(off-road)=70`、`C→O2(road)=0`、`C→O11(off-road)=20` | R-CITY §2 |
| 外环买家债务 | 11,341,300 | R-CITY §2 |
| 城市化 | `migratedToCity=175`、`cityLaborer=175` | R-CITY §2 |
| 生产终局 | `workshopOutput=650`、`farmOutput=1370`（`landPerUnit=22` 压住）、`rent=410` | R-CITY §2 |

## 5. 对拍时的读法（P9 用）

1. **先按 §1.2 换算单位**：探针读数 → 正式毫单位或反向；换算点用 `EconomyVocabulary` 既有常量，
   禁止在 comparator 里手写第二个 1000。
2. **按 scenario 逐 id 比 expected 的每个叶子键**；`tolerance.default` 管未覆盖键，
   `tolerance.overrides` 管具名键。
3. **先比守恒/不变量，再比绝对读数**：货币守恒、债务守恒、Σ劳动 ≤ 可用、不丢失身份；
   绝对读数不一致时，先判“探针口径问题 / 规格问题 / 实现问题”，不要直接改 expected（plan §7.4）。
4. **报告只给方向/关系而未印数字的**（例如 §2.1 的规则 C、§2.2 的停产逻辑），
   用探针测试 `RuleCNoTransactionProbeTest`、`ProductionExitOnLossProbeTest` 或独立不变量对拍，P0 不伪造数字。
5. **本 fixture 的 expected 不是“正式实现唯一正确性来源”**：正式运行时还有 class-first 旧档回归、
   spec 语义、独立不变量与变异测试（plan §7.5）。

## 6. 已知不一致与 P9 待裁定（不静默取一个）

### 6.1 7HEX2：报告写 `serviceRadiusHex=4`，探针代码是 2

- R-CITY §1.2 文字写城市商人总池 `serviceRadiusHex=4`；`SevenHexCityMerchantProbeTest` 构造
  `city-pool` 时实际传的是 **2**（测试第 147 行）。
- 用当前公式代入 2 才能复现报告读数：`R0→C` 终局 67、`O2→O11` 70、`C→O11` 20；
  若代入 4，城市折扣更大，这三处会变成 60、50、0（与报告 67/70/20 不符）。
- **fixture 选择**：`inputs.transportTeams[city-pool].serviceRadiusHex = 2`（以能复现报告读数的探针代码为准），
  并在 JSON `notes` 与 §6.1 里登记报告文字值 4。
- **P9 必须先裁定**：正式运行时用 2 还是 4；裁定后如果改 4，expected 中 `r0ToCFinalCostPerMille`、
  `o2ToO11OffRoadCostPerMille`、`cToO11OffRoadCostPerMille` 必须由 P9 单独提交修正，并写明新旧值和裁定依据。

### 6.2 R-TEN §4 的“240 轮单次冲击”与探针测试当前循环口径不一致

- R-TEN §4 明确写“跑完 240 轮”，表里给出冲击期死亡 559、终局人口 550、
  终局债务 265,440,436 / 274,508,108、终局欠薪 81,080。
- 当前 `ProductionModeEconomyProbeTest` 的冲击测试方法实际循环 **1,460 轮**，并且每年第 51–90 天重复歉收
  （`isHarvestShock` 按 `(round-1)%365+1` 判断），因此当前代码直接跑出的是 R-TEN §4.5 的年度长程数
  （终局债务 2,297,110,036 / 2,297,248,136 / 2,380,612,788，终局欠薪 102,680）。
- **fixture 选择**：把两套读数分开冻结为两组 scenario——
  `harvest_shock_240_*`（R-TEN §4 单次冲击表）与 `annual_harvest_shock_1460_*`（R-TEN §4.5 年度冲击表）；
  两组 `notes` 都写明口径。
- **P9 必须先裁定**：正式运行时采用“单次 240 轮冲击”还是“每年重复歉收”；两者不能混用，
  也不能只挑其中一组数字来迁就实现。

### 6.3 其他注释/代码/报告不一致（已按报告读数冻结）

| # | 冲突 | 处置 |
|---|---|---|
| 1 | `ProductionModeEconomyProbeTest` 类注释写“发 191 粮 + 131,026 钱工资”，但代码是 `hire(P, 585, 2).fixedCash(65)`，实际为 382 粮 + 111,800 钱；R-TEN §3 表也是 382 + 111,800 | fixture 冻结报告/代码实际值 `382` 与 `111800`；注释属陈旧文字，P9 对拍时以测试断言和报告为准 |
| 2 | `DiversifiedEconomyProbeTest` 顶注写“布 205”“雇工 165 钱/劳动”，但代码初始 `CLOTH=176`、`workshop.hire(...,150L,...)`；R-DIV §3 终局价格为 189/157、工资 60,000 | fixture 的 `inputs.initialPrices.CLOTH=176`、`cashPerLabor=150`；终局 expected 用 R-DIV §3 的 189/157 与 60,000 |
| 3 | R-TEN §4 表“冲击期死亡 559、终局人口 550”，而 §4.5 写“总死亡 560、终局人口 550”（1,110−560=550；559 是冲击期口径） | fixture 同时保留：240 轮 scenario 用 559/550，1460 轮 scenario 用 560/550；不把两个口径平均或互换 |
| 4 | R-TEN §4.5 基准“跑满 1460 轮后与 120 轮读数完全一致”，但 R-TEN §3 表格标题是 120 轮 | fixture benchmark scenario 输入 `rounds=120`，expected 取 §3 表；`notes` 写明 §4.5 的 1460 轮同一性 |
| 5 | `ProbeEconomy` 是测试包简化模型（单边债务、池级资产、手工固定 mode、价格规则 A/B/C 已知塌陷等） | fixture 只冻结报告读数；正式实现必须按 plan §2/§7 替换为正式字段（`DebtContract`、`AssetShare`、`ProductionOrganization`、`MarketSettlement` 等），禁止把 `ProbeEconomy` 复制进 main |

## 7. 校验与边界声明

### 7.1 JSON 校验命令与结果

```bash
python3 -m json.tool simos-economy/src/test/resources/fixtures/probe-golden-readings.json
```

实际结果：JSON 可解析（`json.tool` 无报错，exit 0）；fixture 顶层含 `version: 1`、`sourceReports`、
`scenarios`（24 个），每个 scenario 含 `id`、`probeTest`、`inputs`、`expected`、`tolerance`、`notes`。
所有 expected 读数为 JSON number（不是带逗号的字符串）。

### 7.2 未做/未验证

- **未运行 Maven / test / verify**；本 P0 不接受“测试通过”作为交付条件，也没有跑探针复现读数。
- **未改任何 `src/main` 代码、测试/探针代码、`AGENTS.md`**；只新增本报告与 JSON 资源。
- fixture 的 `inputs` 是只读探针装配的摘要，不是 `ProbeEconomy` 的可执行序列化；完整装配以对应
  `probeTest` 类为准。
- §6 的不一致只做了静态核对（报告文字 vs 只读探针代码），没有重新编译/运行来验证“换 4 会得到 60/50/0”；
  该结论来自对 `ProbeEconomy.transportPerMille` 公式的逐项代入（base − road discount − city discount + rural penalty）。

## 8. 附：scenario → 来源报告速查

| scenario | 来源 |
|---|---|
| `long_run_famine_3000`、`circular_flow_credit_3000`、`food_labor_*`、`labor_priority_*` | R-LONG |
| `tenancy_*`、`wage_farm_baseline_120`、`harvest_shock_240_*`、`annual_harvest_shock_1460_*` | R-TEN |
| `transport_*`、`handicraft_workshop`、`natural_mortality_1460`、`diversified_three_sector_1460` | R-DIV |
| `seven_hex_city_3650` | R-CITY |


## 6.4 P0 勘误（2026-10-06 控制方裁定）

- 当前 HEAD 的 `SevenHexCityMerchantProbeTest` 两次独立运行一致打印：
  `cityPoolMoved=1965`、`cityPoolProfit=-400`、`cityPoolCapacity=20750`、`outerBuyerDebt=11341500`；
  其余 7HEX2 读数（pop=1455、capacity=370、used=7549、expansions=18、builtAreaPerMille=360、
  arableC=2999、arableR0=3019、migratedToCity=175、workshopOutput=650、farmOutput=1370、rent=410）与本文一致。
- 本文/`2026-10-05-city-merchant-probe-results.md` 原抄的 `cityPoolCapacity=20780`、`outerBuyerDebt=11341300`
  无法由当前探针代码复现，故按可复现读数更正为 **20750 / 11341500**；机器可读 fixture
  `simos-economy/src/test/resources/fixtures/probe-golden-readings.json` 已同步。
- 这是一次**只改读数、不改公式/探针**的勘误；P9 的 `ProbeGoldenReadingsFixtureTest` 以更正后的值为准。
