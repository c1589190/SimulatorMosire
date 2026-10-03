# 探针算法上正式运行时 + GM 生产方式编辑计划（2026-10-06）

> 依据：用户 2026-10-06 裁定“生产方式也得允许 GM 通过工具创建、修改；把目前验证的经济算法搬到项目上”。
> 输入：`docs/superpowers/reports/2026-10-04-market-probe-longrun.md`、
> `2026-10-05-tenancy-wage-cycle-results.md`、`2026-10-05-diversified-transport-handicraft-results.md`、
> `2026-10-05-city-merchant-probe-results.md`；
> 设计：`2026-10-04-market-debt-architecture.md`、`2026-10-05-tenancy-wage-production-modes.md`、
> `2026-10-05-diversified-transport-handicraft.md`、`2026-10-05-city-merchant-urbanization.md`。
> 纪律：一个阶段一个编码代理；阶段门禁只做编译；测试统一留到 P9（AGENTS §一.5）；
> **旧 class-first 路径保持逐值不变**，新运行时走新 profile/激活位。

## 0. 目标与非目标

**目标**

1. 把探针验证过的经济算法逐项搬进正式模块（`simos-economy` / `simos-social` / `simos-app`）；
2. 生产方式（佃农制/雇农制/手工业/商人）成为**世界状态**：`ProductionMode` / `ClassStructure` /
   `ClassPosition` / `ProductionOrganization` / `ProductionRelation` / `AssetRule` 都进 `EconomyData`；
3. GM 可用工具**创建、修改、停用**生产方式、阶层结构、关系规则、资产规则与组织；
4. 所有写入走 ChangeSet/revision，可预览、可审计、可回放；
5. 新旧世界并存：class-first 旧档逐值不变，新世界用新 profile。

**非目标**

- 不在本计划里删除 class-first；
- 不一次改完所有文件；不引入第二套货币权威；
- 不在 P1–P8 写正式测试（只编译门禁），测试集中到 P9。

## 1. 总原则

1. **世界状态是唯一权威**：`RegimeOperators`/`RegimeRelations` 的静态表降级为**种子默认值**，
   运行期结算只读 `EconomyData` 里的 mode/classStructure/relation/assetRule。
2. **GM 不改文件、直接改状态**：GM 通过 `economy.GmAdjust` 扩展项（或专用命令）编辑；
   每条命令只在命令期校验，真正结算在日结算。
3. **引用完整性 fail-closed**：mode 被组织/候选/变迁/资产规则引用时不许删；
   classStructure 的 positions 与全局 classPositions 必须一致；
   relation 必须指向存在的 unit/mode；assetRule 的 `(mode, assetKind)` 唯一。
4. **版本化**：`ProductionMode.version` 每次规则修订 +1；旧版本可被旧组织继续引用，
   新组织默认取最新版本。
5. **一个文件一个写者**：探针代码保留在测试包；正式实现落在 main 包，不互相复制第二份算法。

## 2. 探针 → 正式映射表

| 探针机制 | 探针位置 | 正式目标 | 阶段 |
|---|---|---|---|
| 每格 ref / 规则 A/B/C | `ProbeEconomy` 市场段 | `Market` + 新 `MarketSettlement`（M4）；`MarketRegion`/`MarketNode` | P4 |
| 买家财富降序、无出价、最低到货价 | `ProbeEconomy` 撮合 | 同上；`Recipient`/`MarketReport` 读口 | P4 |
| 跨格运输 + 商人承运 | `TransportTeam`/`MerchantTier` | 新 `TransportOrganization`（或 `ProductionOrganization` + `trade` 活动）；`TradeRoute`/`ShipmentBatch`；`AssetKind.SHIP/CATTLE/TOOL` | P4/P6 |
| 单边债务 / 还债 wave / 欠租欠薪 | `ProbeEconomy` 债务段 | `DebtContract`/`DebtContractBook` + 新违约/欠租/欠薪 terms；`FlowRow` 对账 | P5 |
| 死亡删债 / 自然死亡/出生 | `ProbeEconomy` 人口段 | 已有 `PopulationDynamics`（年龄档×性别×压力），探针只做量级校准 | P5/P8 |
| 佃农制/雇农制/货币租/分成租 | `Tenancy`/`WageFarm` | `ProductionMode` + `ClassStructure` + `ClassPosition` + `ProductionRelation` + `CompensationRule` | P1/P2/P3 |
| C1（口粮+投入）、吃饱度、有效劳动 | `ProbeEconomy` | `ProductionRecipe`/`Industry` + `LaborSupply`/`LaborAllocation` + `ProductionUnitBook` | P3 |
| 劳动优先级 / Σ劳动 ≤ 可用 | `ProbeEconomy` 多 recipe | `LaborAllocation` + `ProductionUnit.cycleLaborMilli` 守卫 | P3 |
| 生产资料 / 产能 | 探针只到 `landPerUnit`/运力 | `AssetShare`（owner/operator/quantity/kind）+ `AssetRule` + `ProductionUnitBook.capacityScaleOf` | P1/P3 |
| 纤维→作坊→布 | `HandicraftProbeTest` | `handicraft_workshop` mode + `ProductionRecipe`（FIBER+劳动→CLOTH） | P2/P3 |
| 城市承载 / 扩建 / 城区占地 | `CityState`/`availableArableMu` | `City.props` 或新 `CityLand` + `SettlementGenerator` + `EconomySeeder.landMilliMuOf` | P6 |
| 辐射商路 / 道路 | `transportPerMille` / `setRoad` | `GameMap.pathways` + `TradeRoute.costPerUnit` + 城市距离折扣 | P4/P6 |
| rural→urban 迁移 | `migratePopulation` | `PopulationGroup` 拆/合 + 新 `LotMigration` + `ClassFirstPopulationWriteback`/协调器 | P8 |
| 货币发行/回笼/信用 | `ProbeEconomy` 信贷段 | `Government`/`MoneyAuthority`/`MoneyIssuance` + `MoneyIssuanceRecord`；`CreditPool`/`MoneyStock` | P5 |
| GM 编辑 | 探针参数 | `EconomyGmAdjust` + `EconomyAdjustTool` + `CatalogTool` + `ApiViews` | P7 |

## 3. 阶段计划

### P0：冻结探针黄金读数（1 个代理）

- 把四个报告里的关键读数固化成**parity fixture**（JSON/测试资源），作为正式实现的目标读数；
- 建一张 `probe → runtime` 对照表（探针字段 ↔ 正式字段 ↔ 期望值）；
- 只新增文档/资源，不动代码。

**交付**：`docs/superpowers/reports/2026-10-06-probe-golden-readings.md` + fixture 目录。

### P1：生产方式与阶层结构入世界（M1）

- 新增默认 mode 目录（静态工厂）：`tenancy_fixed_kind`、`tenancy_share`、`tenancy_cash`、
  `wage_farm`、`handicraft_workshop`；
- 每个 mode 一个 `ClassStructure` 与一组 `ClassPosition`：
  - 佃农制：`landlord`（OWNER/NONE/SURPLUS_RECEIVER）、`tenant_operator`（OPERATOR/BOTH/SURPLUS_RECEIVER）；
  - 雇农制：`landlord_operator`（OWNER/ORGANIZER/SURPLUS_RECEIVER）、`wage_laborer`（DIRECT_LABORER/PROVIDER/WAGE_EARNER）；
  - 手工业：`workshop_owner`（OWNER/ORGANIZER/SURPLUS_RECEIVER）、`artisan`（DIRECT_LABORER/PROVIDER/WAGE_EARNER）；
  - 商人：`merchant_principal`/`porter`/`self_employed`（可先只做 organization，不做 mode）。
- `EconomySeeder` 新 profile（`MARKET_RUNTIME` 或 `PRODUCTION_RUNTIME`）发 `modes`/`classStructures`/`classPositions`；
  class-first 旧 profile 行为不变；
- `EconomyData` 跨表守卫已存在，复用；新增默认数据不得引用不存在的家户。

**落点**：`simos-economy/model`、`migrate/LegacyClassStructure`、`spi/EconomyPayloads`、`app/world/EconomySeeder`。
**门禁**：编译。

### P2：关系与资产规则入世界（M2/M3 前置）

- 把 `RegimeRelations`/`RegimeOperators` 的默认值搬成**种子数据**：
  - `ProductionRelation`：operator/inputSupplier/residualOwner/laborSource/rules；
  - 三种租形规则：`FIXED_IN_KIND_RENT`/`OUTPUT_SHARE`/`FIXED_MONEY_RENT`；
  - 三种工资形：`FIXED_IN_KIND_PER_LABOR`/`FIXED_MONEY_WAGE`/混合；
  - 手工业：纤维+劳动→布；
  - `AssetRule`：LAND/TOOL/WORKSHOP/SHIP/CATTLE 的 core/pledgeable/liquidation/rent/transfer。
- `ProductionOrganization` 初值：organizer/laborSources/assetSources/inputSources/outputOwnership/relationTemplateRef；
- 组织按格 + mode + 位置确定性生成，供 P3 结算器读。

**落点**：`model/ProductionRelation`、`model/AssetRule`、`spi/EconomyPayloads`、`EconomySeeder`。
**门禁**：编译。

### P3：生产运行时（M2）

- 重建 `ProductionSettlement`（旧类已删）：
  - C1 = desiredScale × inputPerUnit + 预估口粮；
  - 劳动优先级：家庭/佃农/雇农按 `LaborSupply`/`LaborAllocation` 分配；
  - `actualScale = min(目标, 有效劳动/laborPerUnit, 投入/inputPerUnit, 资产容量)`；
  - 吃饱度 → 效率；产出归 outputOwner；
  - 租/工资结算：货币腿上限=付方可见货币，不足落欠租/欠薪（P5 接债务）。
- 产能：`ProductionUnitBook.usableAssets` + `capacityScaleOf` 从 `AssetShare` 派生；
- **只读状态表**，不读 `RegimeRelations` 静态表。

**落点**：`simos-economy/time`、`model`、`classfirst` 只会被新路径旁路，不改旧引擎。
**门禁**：编译。

### P4：市场运行时（M4）

- 每个市场区（`MarketRegion`）每格订单表；价格规则 A/B/C 与 `ref`；
- 买家按财富降序、无出价、最低到货价；跨格运输走 `TradeRoute`；
- 运输费付给承运商人组织；没有承运组织时不许钱凭空消失；
- 在途货 `ShipmentBatch`：到达日才能消费、逐票损耗；
- 道路：`GameMap.pathways` 影响 `TradeRoute.costPerUnit`；
- 探针的 `transportPerMille` 辐射/道路公式搬进正式 `TradeRoute` 的 `costPerUnit` 计算。

**落点**：`simos-economy-api/market`、`simos-economy/time`、`model/Market`。
**门禁**：编译。

### P5：债务 / 信贷 / 货币（M3/M5）

- 单边负债、还债 wave、欠租/欠薪 terms、利息、死亡删债；
- `CreditPool`/`MoneyStock`：发行/回笼显式、逐币种守恒；
- `Government.issuable` 逐国生效，一个币种一个发行主体；
- 运行期发行/回笼必须落 `MoneyIssuanceRecord`（`INITIAL_ENDOWMENT`/`FISCAL_ISSUE`/`WITHDRAWAL`）；
- 普通账户不得透支；GM 可查看/干预发行权与信贷参数。

**落点**：`model/DebtContract`、`model/Government`、`api/money`、`spi/EconomyGmAdjust*`。
**门禁**：编译。

### P6：商人 / 城市 / 城市化

- 商人组织：层级（脚夫/个体户/老板）、城区占用、商队资产（SHIP/CATTLE/TOOL）、运力；
- 辐射成本与道路：离城越近越便宜；城市商人折扣按距离衰减；
- 城市承载/扩建：每格土地预算 `availableArableMu = 可耕地 − 城区占地`；
- 城市工坊聚集折价、农村工坊→城市迁移压力；
- 初始化城市已存在 ⇒ 只做人口流动，不做“平地建城”。

**落点**：`simos-map/City`、`simos-social/gen`、`simos-economy/model`、`app/world/EconomySeeder`、新 `MarketSettlement`。
**门禁**：编译。

### P7：GM 编辑工具（生产方式创建/修改/停用）

- 扩 `EconomyGmAdjust` 白名单（唯一语义落点 `EconomyGmAdjustments.project`）：
  - `upsertProductionMode`：`{id,name,version,classStructureId}`；version 必须递增；
  - `deactivateProductionMode`：被组织/候选/变迁/assetRule 引用 ⇒ 具名拒绝；
  - `upsertClassStructure`：`{id,modeId,positions,defaultSharesPerMille}`；
  - `upsertClassPosition`：`{id,modeId,name,relationToMeans,laborRole,surplusRole,ruleExtensions}`；
  - `upsertProductionRelation`：`{activity,operator,inputSupplier,rules,residualOwner,laborSource}`；
  - `upsertAssetRule`：`{modeId,assetKind,isCoreMeans,pledgeable,liquidationPriority,rentRule?,transferRule}`；
  - `upsertProductionOrganization`：`{id,modeId,classPositionId,unitId?,organizer,laborSources,assetSources,inputSources,outputOwnership,status,statusReason}`；
  - `upsertCandidate`：生产方式候选，供 P3 自动选择；
  - `setMerchantPolicy`/`upsertCityLand`（P6 完成后追加）。
- `EconomyAdjustTool` 已支持预览/前后差异/原因/审计，只需扩 catalog/doc 与参数形状；
- `ApiViews` 增加 mode/classStructure/relation/assetRule/org 读口与编辑预览；
- `CatalogTool` 登记新 adjustment（GM-only，决策人不可见）；
- **所有编辑走 ChangeSet revision**，幂等 ID 由现有 `Id` 拼写点派生，不手写第二份。

**落点**：`spi/EconomyGmAdjustHandler`、`spi/EconomyGmAdjustments`、`app/tools/write/EconomyAdjustTool`、
`app/gui/ApiViews`、`app/tools/read/CatalogTool`。
**门禁**：编译。

### P8：人口迁移与跨切片城市化（C3a）

- 新增 `LotMigration`（源 lot → 目标城市 lot、人数、债务/劳动随行），
  不能用 `LotChange` 的出生/死亡两数表达“换居住地”；
- `PopulationDynamics`/协调器按月或按触发条件执行迁移；
- `ClassFirstPopulationWriteback` 扩展为可处理迁入/迁出，保持人口守恒；
- 迁移拉力：`urbanPull = w1×人均交易量 + w2×商人密度 + w3×城市作坊利润 − w4×粮价/缺口 − w5×城区拥挤`；
- 迁移后同步更新 labor allocation、classFirst 家户账户、债务。

**落点**：`simos-social/population`、`simos-economy-api/population`、`app/time`。
**门禁**：编译。

### P9：统一测试与长程验收（M6）

- 用 P0 的 golden fixture 对拍正式运行时；
- 判据：守恒、不丢失、静默付 0、断粮、欠租欠薪、自然死亡/出生、发行权、Σ劳动；
- 跑 3650 tick 城市世界，断言探针报告里的量级与关系；
- 旧 class-first 世界回归不变。

## 4. GM 工具示例

```json
{"adjustment":"upsertProductionMode",
 "parameters":{"id":"tenancy_share","name":"分成租佃","version":1,
               "classStructureId":"tenancy-share-structure"},
 "reason":"GM 新增分成租佃"}
```

```json
{"adjustment":"upsertProductionRelation",
 "parameters":{"activity":"unit-farm@0_0-estate",
               "operator":{"kind":"ESTATE","id":"farm@0_0"},
               "inputSupplier":{"@class":"to_actor","actor":{"kind":"ESTATE","id":"farm@0_0"}},
               "rules":[{"type":"FIXED_IN_KIND_PER_LABOR","recipient":{"@class":"to_cohort","cohort":"0_0|rural|poor_peasant"},
                         "pool":"NET_AFTER_INPUTS","weight":"LABOR_AMOUNT","ratePerMille":0,
                         "fixedAmount":20000000,"commodity":"grain","priority":10}],
               "residualOwner":{"kind":"ESTATE","id":"farm@0_0"},
               "laborSource":"WAGE"},
 "reason":"GM 把该庄园改成雇农制"}
```

- 预览：`EconomyAdjustTool` 用 `EconomyGmAdjustments.project` 算前后差异；
- 提交：同一 payload 走 `economy.GmAdjust`，落一条 revision；
- 幂等：同 id + 同值 ⇒ 空变更集；
- 失败：引用不存在/版本倒退/被引用不能删 ⇒ 具名 `Rejected`。

## 5. 风险与待裁定

1. **class-first 与新运行时并存**：新 profile 叫什么、如何激活、旧档是否迁移；
2. **land/city 权威**：城市占地与可耕地在 map/social/economy 之间只能有一个权威；
3. **价格稳定**：探针里长程价格会掉到 5–7（规则 A/B/C + 库存），正式版需要 GM 参数/稳定项；
4. **利息与信用稳定**：探针已暴露“无收入闭环时死亡删债不能稳定存量信贷”；
5. **商人交易者 vs 承运人**：买低卖高、跨市场区套利需要 `ShipmentBatch` 与商队资本；
6. **迁移跨切片**：social/economy/app 三方写者，必须按协调器一 revision 原子；
7. **测试纪律**：P1–P8 只编译，P9 统一测试；新代码不得绕过 `ChangeSet`/`Codec`。

## 6. 最先开工的批次

建议顺序：

```text
P0 冻结读数
→ P1 生产方式/阶层结构入世界（含新 profile）
→ P2 关系/资产规则入世界
→ P3 生产运行时
→ P4 市场运行时
→ P5 债务/货币
→ P6 商人/城市
→ P7 GM 编辑工具
→ P8 迁移/城市化
→ P9 统一测试
```

P0/P1 可以立即派一个编码代理；P1 完成前不要动 P3+ 的结算器。

## 7. 实现 Agent 与测试案例的使用纪律（用户 2026-10-06 要求）

用户原话：“记得让代码实现 Agent 有需求就根据我们的测试案例进行代码编写，这个方法可靠吗？”
**结论：可靠，但只能把测试案例当作“可执行规格 + 黄金读数”，不能当作唯一真理，也不能让实现 Agent 改测试来通过。**

### 7.1 与 AGENTS §一.5 对齐的分工

- **实现 Agent**：只写生产代码、只过编译（`-DskipTests compile`）；**不写/不改测试、不跑 test/verify**；
- **测试 Agent**：最后统一写测试；实现 Agent 的交付报告是它的输入；
- **控制方**：派单、审、验收、提交。

### 7.2 探针测试案例的正确用法（实现 Agent）

实现 Agent 允许**只读**以下内容：

```text
docs/superpowers/specs/       # 设计语义
docs/superpowers/plans/       # 本计划
docs/superpowers/reports/     # 探针读数（黄金值）
simos-economy/src/test/java/io/mosire/simos/economy/market/  # 探针案例（只读）
```

禁止：

- 改探针测试/探针代码；
- 把 `ProbeEconomy` 的代码复制进 main；
- 用“让探针测试通过”替代设计语义；
- 跑/改正式测试（测试阶段不归它）。

### 7.3 派给实现 Agent 的任务书必须包含

1. 本阶段 spec 段落 + 本计划的对应 P 阶段；
2. P0 的 golden fixture / 报告读数：输入、期望输出、允许偏差；
3. 探针字段 → 正式字段映射表（§2）；
4. 明确“探针里哪些是简化、正式版要换成什么”（例：单边债务→`DebtContract`；
   池级资产→`AssetShare`；`migratePopulation`→`PopulationGroup` 拆合；`TransportTeam`→正式商人组织）；
5. 本阶段**只编译**的门禁命令；
6. 报告模板：改动文件、编译结果、**会改变数值行为的改动清单**、受影响的硬编码字面量、
   与探针案例不一致的地方及原因。

### 7.4 测试 Agent 最后怎么用这些案例

- 直接用 P0 fixtures 做 **probe → runtime 差分/对拍**；
- 再加四类独立验证：
  1. **不变量**：守恒、Σ劳动 ≤ 可用、货币=初始+发行−回笼、债务=发行−还款−删债（利息项单列）；
  2. **往返**：`ChangeSet`/`Codec` 重建逐字段相等（铁律 5）；
  3. **变异**：改一个 GM 参数/规则，读数按预期方向变化（不是只看固定值）；
  4. **对抗**：坏载荷、引用不存在、版本倒退、付方余额不足、静默付 0、断粮。
- 若正式实现与探针案例不一致：先判定是**探针案例错、规格错，还是实现错**；
  改案例/规格必须单独提交并写明原因，不许测试 Agent 为迁就实现而改断言。

### 7.5 可靠性评级

| 用法 | 可靠性 |
|---|---|
| 当回归/黄金读数，防搬错 | 高 |
| 当行为规格/验收案例 | 中高（要配 §2 映射与 spec） |
| 当唯一正确性来源 | 低（探针是简化模型，含标定值、整数取整、价格塌陷等已知瑕疵） |
| 当“让测试变绿”的目标 | 不可靠（会诱导实现 Agent 过拟合/复制探针） |

一句话：**测试案例可靠，前提是“只读作规格 + 差分对拍 + 独立不变量/变异验证”；
把它当唯一真理或让实现 Agent 改测试，就不可靠。**
