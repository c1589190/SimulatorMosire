# D-032 生产方式线：回代码调查报告（2026-10-17）

> **后续裁决/局部取代（2026-10-17）**：D3 已定直接删除；D4 已定归 production，提供家户采用方案的预计/实际产出及生产状态变化能力，economy 调用并处理经济关系。见 `docs/superpowers/specs/2026-10-17-d032-production-execution-decision.md` 与设计原稿 D-032/U8；本文 §6、§7 的“待答复”及仅迁模板建议是裁决前历史，不再作为当前状态。
> **性质**：只读调查。**不是**设计书、不是派单书，**不含**任何实现建议的裁定。所有结论给 `相对路径:行号`，可由任何人复核。
> **依据**：用户设计原稿 D-032（`docs/superpowers/specs/2026-10-02-architecture-design-source.md` 的 D-032 条）与 `docs/superpowers/specs/2026-10-17-d032-production-experience-choice-design.md`（§2 业务口径 / §5 上一版源码线索 / §7 待定项）。
> **工作树**：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`（分支 `parallel/side-module`，P1 收口状态 + 未提交改动）。★ 本文结论**只对本树当刻内容成立**。
> **方法**：3 个只读调查代理（A 定义面 / B 家户劳动选择 / C 利润·迁移·经验·公式·属性）分面取证；控制方对最吃重的断言**逐条独立复核**（下文标「★控制方复核」）。

> **用户本会话裁定（2026-10-17，逐字）**：
> > 针对相关项目的调查文档已被记录，阅读它——文化部分我决定暂时放掉，只留一个占位，先重点把生产方式相关内容给做出来，现在开始进一步调查吧
>
> ⇒ 本调查报告**只覆盖生产方式线**；文化线（D-033）保留为记录、本轮不推进。

---

## §1 定义面现状（A 面 + 控制方复核）

### 1.1 真正承载"投入/产出"的不是"生产方式"，而是按 hex 实例化的产业定义

| 对象 | 形状 | 事实 |
|---|---|---|
| `IndustryDefinitionView` | 14 个组件（`simos-production/.../contract/IndustryDefinitionView.java:52-66`） | **结算真读的定义载体**；按 `IndustryId`（带格）实例化 |
| `ProductionModeDefinitionView` | **只有 4 个**：id/name/version/classStructureId（`.../contract/ProductionModeDefinitionView.java:19-20`） | 经济侧唯一读到的语义成分是"绑哪个 ClassStructure"（`simos-economy/.../time/ExpectedProfitBook.java:270`、`EconomyModeTransitionSettlement.java:301`、`ModeMigrationSettlement.java:997`、`EconomyEnterpriseSettlement.java:323`、`EconomyReferenceValidation.java:156`）；mode 上**没有**任何 regime/投入/产出/劳动/资产/区位字段 |
| mode ↔ industry 链接 | 无字段级链接 | 靠 `regime` 偏好选择（`EconomyEnterpriseSettlement.java:763-800`）+ `ProductionProcess.modeKey = "mode:"+modeId` 字符串（`.../model/ProductionProcess.java:117,:461`） |

⇒ D-032 §1 所指"能定义投入、生产组织条件与产出的**生产方案**"，今天落在 **`IndustryDefinitionView`** 上；`ProductionModeDefinitionView` 只是制度引用。

### 1.2 字段 × 消费者（要点）

**结算真读**：`regime`、`cycleDays`、`capacityPerUnit`、`laborPerUnit`、`outputPerUnit`、`cycleInputPerUnit`（摊平成 `inputPerUnit()` 后被读）、`moneyOutputPerUnit`（唯一进结算的产出分支：`simos-economy/.../time/MintSettlement.java:198,:242`）。

**★控制方复核：两个字段全仓无结算读取**
- `dailyInputPerUnit` / `dailyLaborPerUnit`：main 里只有**载荷写入**（`simos-app/.../world/EconomySeeder.java:4104-4105,:4983-4984`、`tools/write/GovWorldBootstrap.java:668-669`、`world/ThreePowersGovBootstrap.java:833-834`）、**catalog 文本**（`tools/read/CatalogTool.java:298,:320`）与解析；`simos-economy/.../time/EconomySettlement.java:5518` 自认"零读取点"。
- `allocation`：`grep -rn "allocation()"` 在 economy/app main 只命中 `simos-app/.../gui/ApiViews.java:3722`（GUI 回显）⇒ **无结算读取**；实际分配走 `ProductionRules` 关系（`EconomySettlement.java:145` 称旧 allocation 为"旧口径"）。

**其余**：`name` 只有工具/GUI；`skill` 只有 culture composer 与 `ProductionData` 键==值守卫。

### 1.3 位置语义与产业实例

- **产业实例必须带 hex**：`IndustryId` 串形如 `<kind>@<q>_<r>`，唯一拼写点 `simos-economy-api/.../id/IndustryHexKeys.java:44-49/:52-61/:68-78`；类注 `:10-14` 明写"产业没有'我在哪一格'的字段"。写路径强制带格并拒绝前导 0/正号（`simos-production/.../spi/IndustryUpserts.java:120-150`）。
- ⇒ **不存在"跨地区共享的生产方案定义"**；唯一跨地区身份是技能键 `ProductionSkillKey`（`.../contract/ProductionSkillKey.java:4-8` 明确"不能拿 `IndustryId` 当技能键"）。
- 播种：每格 farm/craft/weave（`simos-app/.../world/EconomySeeder.java:3569-3571`）、官署铸币（`:4081`）、跑商按需新建（`simos-economy/.../model/MerchantTradeTemplate.java:185` + `.../time/ModeMigrationSettlement.java:207`）。

### 1.4 ★ 存在第二个竞争定义面（D-032 §2.1 明令禁止"另维护一份竞争权威定义"）

1. `ProductionCandidate`（`simos-economy/.../model/ProductionCandidate.java:57-70`）：自带 `output/outputPerUnit/inputPerUnit/requiredAssets/laborPerUnit/buildDays/cycleDays/regime/laborSource/acceptedRightKinds/assetSource/name`，被**入口结算真读**（`.../time/EconomyEntrySettlement.java:236,367,391,438,493,569,646`）。
2. `templateFor`（`EconomyEntrySettlement.java:1014-1051`，★控制方复核原文）把候选**合成**成一份 `IndustryDefinitionView`：`capacityPerUnit` = 正数 `requiredAssets`、`slots` **手写四档**（贫农 950/中农 900/富农 750/地主 100）、`allocation` = **固定 `Split(500,500)`**、`MoneyOutput` **空**、`skill` **空**。
3. 同一个产业载荷在 economy 侧**另有一处同形解析**（`.../spi/EconomyPayloads.java:1445,:1484`，活跃调用点 `:351`）。
⇒ 同一个"生产方案"的字段面今天有**两处**（production 契约 + economy 候选/载荷），与 D-032 §2.1 的"唯一权威"要求需要对齐（**这是设计要处理的冲突点，不是本次调查的裁定**）。

### 1.5 可行性与生产资料

- 家户级候选判据（`EconomyEntrySettlement.java`）：`ASSET_SHORT`（`:420-428`，自有 + assetSource 同格空闲份额）、`LABOR_SHORT`（`:438-471`）、`SUBSISTENCE_SHORT` 口粮预留（`:481-489`）、投入库存覆盖（`:493-500`）。**没有财富/现金判据**（grep wealth/MONEY_SHORT/CASH 在该链零命中）。
- 自动组织（`EconomyEnterpriseSettlement.java:441+`）：劳动预算→scale（`:537-554`）、投入覆盖（`:560-585`）、资产产能 + 租佃补齐（`:870-940`）、最后 `scale × laborPerUnit`（`:685-687`）。
- 预期利润侧可行性 = **劳动 + 工具**（`ExpectedProfitBook.java:735-750`）；CATTLE 门槛已删（`:669-671` 记录了"全世界只有 0_0/0_2 两户持 CATTLE ⇒ 新跑商=0"的实测）。
- `AssetKind` = **6 值编译期 enum**（`simos-actor-api/.../asset/AssetKind.java:8-14`：LAND/CATTLE/TOOL/WORKSHOP/MACHINE/SHIP）；`capacityPerUnit` 以它为键、每 1 规模单位的 `Long` 且必须 > 0（`IndustryDefinitionView.java:113-125`）。
- ⇒ D-032 农业例子里的"**小型农具 vs 大型农具**"（同种资产不同规格）今天**表达不出来**（要新增 enum 值，属编译期形状改动）；"1 件工具覆盖 5 区域"这类**每单位不足 1 件**的比例也没有分数标度，只能靠"重定规模单位"绕。
- **牲畜未实现**（四处证据）：`EconomySettlement.java:297-303`（`FEED_PER_MILLE = 0`，注"v1 不做耕牛，不是漏掉"）、`HouseholdClassRule.java:715`（"其它尚未启用的资产（CATTLE/MACHINE/SHIP）"）、`MerchantTradeTemplate.java:57-75`（W7-fix 用户裁定"先把牲口要求取消掉"）、`simos-app/.../world/EconomySeeder.java:4329`（仍把 CATTLE/SHIP 列进 `PRODUCTION_RUNTIME_ASSET_KINDS`：声明存在、无饲料口径）。

---

## §2 家户生产选择与劳动分配现状（B 面 + 控制方复核）

### 2.1 家户不绑生产方式；"能选什么"是推导出来的

- `HouseholdEconomy` 9 个组件**没有** `ProductionModeId`（`simos-economy/.../model/HouseholdEconomy.java:81-90`）。
- 绑定分三处：家户→位置 `HouseholdClassMembership.currentPositionId`（`.../model/HouseholdClassMembership.java:48`）、位置→方式 `ProductionRole.modeId`（`.../model/ProductionRole.java:30-37`）、**unit→方式只在组织行** `ProductionEnterprise.modeId`（`.../model/ProductionEnterprise.java:75`）；`ProductionProcess`（unit）只有字符串 `modeKey`。
- ★控制方复核：候选集由 `candidateHouseholdsOf`（`.../time/LaborQueueSettlement.java:822-854`）推导 —— **只有已是 operator / 组织者 / `laborSources` / 已有配额的家户**才把该 unit 当候选。
- `participatingPositionIds`（"副业"集合）在生产代码里**只被命令写**（`.../spi/EconomySetHouseholdParticipationHandler.java:31` 等载荷入口），队列与迁移都不写它（`PrimaryModeRanking.java:79-81` 自认）。

### 2.2 一个 tick 的链（外层次序与日内段序）

- 外层六步：`simos-app/.../time/PopulationEconomyTimeParticipant.java:111-121`（Social 出视图 → 经济结算一天 → 缺口 → 饱食度 → 死亡率 → 回写）。
- 日内段序（`simos-economy/.../time/EconomySettlement.java`）：候选预设 `:1279` → 模式变迁执行 `:1431` → 自动组织 `:1491` → 现扣周期投入 `:1585` / 到货 `:1600` → **劳动排队 `:1657`** → 消费 `:1674` → 进度+劳动投入 `:1724` → 市场清算 `:2063` → 偿还/计息/状态机/阶层写回 `:2765–:3096` → **利润汇总 `:3301` → 迁移计划 `:3325` → 迁移执行 `:3413`（仅关账日）**。
- ★控制方复核：`plantingDrawsFirst` 是**常量 `true`**（`EconomySettlement.java:392` 的 `PLANTING_DRAWS_BEFORE_CONSUMPTION`），四个构造重载与两处 app 构造全走同一值（`PopulationEconomyTimeParticipant.java:585`、`EconomyOwnershipTimeParticipant.java:192`）⇒ 无第二口径。
- ⇒ **生产方式归属的改变（迁移）发生在关账日、且在当日劳动排队之后**；劳动排队每天跑，只在一个**已被绑定的候选集**内选 unit。

### 2.3 劳动预算

- 唯一权威在 Social：`SocialData.java:774-816`（`baseLaborMilli = Σ 份额 × milliHoursPerTick`，再 `× satietyPerMille ÷ 1000`）；写入 `HouseholdEconomy.laborMilli` 于 `EconomySettlement.applyLaborBudgetsInto`（`:4221-4238`）。
- 硬不变量：`Σ PRODUCTION ≤ budget − min(Σ GOV_SERVICE, budget)`，越界**构造期抛**（`EconomyData.java:1124-1149`）；GOV_SERVICE 允许 over-budget。
- ★控制方复核：`participationPerMille` **不参与排队**（`plan` 只拿 `laborMilli`）；`participationAdjustedLaborMilli()` 的读者**只有 GUI**（`simos-app/.../gui/ApiViews.java:1212,1323,1371,2487,3865`）。

### 2.4 家户自身需求在不在选择里（复核 D-032 §5 那条）

- **就"生产 offer"而言成立**：`LaborQueueBook.offer`（`:171-296`）签名里**没有**家户参数，排序键 = 产出 bid 价 − 投入 ask 价，按劳动归一（`:279-281,:442-449`）；该文件 `naturalNeeds` **零出现**。
- **但两处必须写清**（否则误读成"需求完全没参与"）：
  1. 需求进了**迁移/模式变迁**那条链：`ExpectedProfitBook.java:422-425` 用 `naturalNeeds[c] × horizonDays` 当自食量、`:489-495` 用口粮成本折劳动；
  2. **套利 offer 读家户需求**（`HouseholdValuationBook.java:199,315,342`、`TradeArbitrageActivity.java:260,307`），且经 `LaborQueueSettlement.java:314-317` 进**同一个 offers 列表、同一个排序器**。

### 2.5 "主副业"的现状（★ 用户已裁定：**不是需要专门机制的设计项**）

> **2026-10-17 用户裁定（设计原稿 U6，逐字）**：「主业副业不是啥需要在乎的东西，现有的生产方式选择是自动化的，主业作用根据我的最新设计也是自动体现出优势的」。
> ⇒ 本节只作**事实记录**：既**不是冲突项**，也**不作为待改项**。选择保持自动化；专业化优势由熟练度加成自然体现（熟练度面见 §3.4）。

- ★控制方复核：`PrimaryModeRanking.Table.primary() = rows.get(0)`（`:167-170`），排序键 = **预期**收益率 desc → positionId → (q,r) → modeId（`:213-219`）；类注 `I-A`（`:28`）原文"主业/副业 = 同一张排序表的第 1 项/其余项"。
- 落点只有第 1 项：`ModeMigrationPolicy.java:580` → `ModeMigrationSettlement.java:900-903` 写成新户 `currentPositionId`（注释原文"主业 = 排序表第 1 项"）。
- ★控制方复核：`secondaryPositionIds()` **除定义处零消费者**（`grep -rn` 全仓含测试）⇒ 今天没有"副业"的下游效果。
- 该表候选集只有"当前项 + 收益率严格更高者"（`PrimaryModeRanking.java:77-81` 自认）——**这一条在讨论"候选集是否完整"时仍有意义**（见 §2.1 与 §4 §2.5 行），但与主副业标签无关。

### 2.6 剩余/被缩减劳动：留在空缺（与 D-032 §2.5 第 5 条冲突）

- 阶段 2 全局按比例缩（`LaborQueueSettlement.java:398-423`）后，阶段 3 只写回 `finalGrant`（`:431-446`），缩掉的进 `plan.idleLaborMilli()`（`:448-455`）——**没有"回填后续候选"的循环**。
- ★控制方复核两处代码把这条写成既定口径：`LaborQueueSettlement.java:49-51`、`EconomySettlement.java:7972-7974`「**劳动留在空缺，不塞给别的 unit**」。

---

## §3 预期利润 / 迁移 / 经验·公式 / 属性契约（C 面 + 控制方复核）

### 3.1 `ExpectedProfitBook`（成熟骨架，三条分支）

- 出参 `Prospect`（`simos-economy/.../time/ExpectedProfitBook.java:125-165`）；入口 `prospect(14 参)` `:194-225` → `prospect(15 参, 可指定档位)` `:240-350`（具名拒绝/分流：NO_MODE `:271`、NO_STRUCTURE `:275`、NO_POSITION `:279`、DEPENDENT `:285-290`、NO_ROW `:293`、DISPLACED_NO_WORK `:296-299`；merchant `:302-320`、wage `:324-336`、production `:337-349`）。
- `productionProspect` `:354-546`：scale = min(资产规模, 劳动规模)；自给 = `min(planned, naturalNeeds × horizonDays)` `:422-425`；可售 = min(余量, 可寻址需求 − 自给) `:427-428`；收入/投入/劳动/租/净 `:442-516`。
- `merchantProspect` `:714-875`：可行性 = **劳动 + 工具** `:741-744`（手头无工具但市场有 tool ⇒ 仍可行 `:706-708`）。
- ★控制方复核 main 调用点：`ModeMigrationPolicy.java:614-615`（当前户）、`:995`、`:1024`（候选）、`:2154-2155`（perLaborScaled）；`simos-app/.../time/GovernmentServiceDesertionBridge.java:828-829`（官员逃亡排序）。**culture/core/actor/social/production 零命中**。

### 3.2 家户"因利润流动"的现状机制

- **触发 = 经济周期关账那一刻**，不是独立机制：闸门 `EconomySettlement.java:3308`（`profitCycle != null && anyCycleClosed && !dayView.modes().isEmpty()`）→ ⑦`EnterpriseProfitBook.collect` `:3311-3322`（**只对账、已不是候选门槛**）→ ⑧`ModeMigrationPolicy.plan` `:3325-3341` → ⑨`ModeMigrationSettlement.apply` `:3413`。
- 候选与速度：源户候选 `:450-481`（人口>0 ∧ 有 standing ∧ **劳动>0**）；排序表 `:664-667` + 第 1 项提前 `:670`；速度 = A 规则（净<0 且流动性不足）⇒ 1000‰ 全户 `:674-723`，否则按**预期收益差** `:725-767`（`profitGapSpeedPerMille` `:2089`），再被 `MIGRATION_SPEED_CAP_PER_MILLE = 100` 夹一次（`:162`）。
- 落账经 outbox `:604-613` → app 排空（`PopulationEconomyTimeParticipant.java:707-708`）→ `MigrationSocialBridge.apply`；**人口权威在 Social，经济行是回写视图**（`:96-99`）。

### 3.3 迁移随人搬什么（逐项）

**搬**：① 人口（逐 `PeopleLot`，最大余数法）`MigrationSocialBridge.java:127-142`；② 新建目标户（HEX 位置、**新画像** "迁入户:<id>"/"mode-migration"、**`vitalRates` 空表**）`:117-125`；③ 货币（**全部币种**按人口比例 floor、余数留源户）`ModeMigrationSettlement.java:568-579`；④ 商品（除 `haul` 外全部可移动商品）`:581-595`；⑤ 债务逐合同 `:597-600`；⑥ 资产份额按比例（同 hex 拆、跨 hex 重建；可移动 = TOOL/SHIP/CATTLE/MACHINE，LAND/WORKSHOP 跨 hex 留原户）`:476-498,:1735-1826`；⑦ 劳动配额 `:526-566`；⑧ 新户的组织/unit/关系/阶层身份 `:499-521,:887-930`。

**不搬**：**生产经验/熟练度**（不存在）；**文化标签**（★控制方复核：`ModeMigrationSettlement` 与 `MigrationSocialBridge` 里 `culture` 命中 **0**）；画像 metadata / `vitalRates`（保留目标户自己的）；源户身份（D-022：源户 classMembership/mode/unit.modeKey 一字不改 `:92-95`）；`HOUSEHOLD` 范围需求 / `ClassShare`（源户迁空时 **fail-closed 具名抛** `:798-812`）。

### 3.4 经验 / 解锁 / 公式：机械结论（★控制方复核）

| 面 | 结论 | 证据 |
|---|---|---|
| 家户生产经验账 | **不存在** | ★控制方复核 `经验\|熟练` 全仓 main 共 26 处，**除 economy 两处注释外全在 culture 模块**（`CultureLogSource/CultureData/ProductionAptitudeComposer/CultureLookup` 等，是旧"熟练度"词面）；economy 仅 `EconomyEntrySettlement.java:50`（"不做…E3 经验"）与 `ProductionModel/ProductionProcess.java:34`（"modeKey 是 E3 的经验读物"）两处注释 |
| 解锁规则 | **不存在** | 全仓 `unlock\|解锁\|prerequisite` 仅 2 处（均为上述注释同类）；`TechnologyDefinition.requires` 只登记声明、**不做闭包/可用性判定**（`.../contract/TechnologyDefinition.java:10-11`）；`TechnologyMastery.level` 无语义（`:6`）；`TechnologyLookup.masteries` **零消费者**；世界种子**零登记** |
| 可配置公式 | **不存在** | 全仓公式/求值类检索仅 1 处命中；唯一"效率公式"是写死的 `ProductionEfficiencyBook.accrueTick`（`:70-90`，域 `[0,2000]‰` `:52-56`）与 `evaluateHarvest`（`:134-236`） |
| ★ 候选侧是否读熟练度 | **不读（0 命中）** | ★控制方复核：`productionModifiers\|ProductionEfficiencyModifier` 在 `ExpectedProfitBook` / `ModeMigrationPolicy` / `PrimaryModeRanking` 三个文件里 **0 命中** ⇒ "同一份定义"部分成立（两侧都读 `IndustryDefinitionView.recipe()`），"**同一套熟练度公式**"**不成立** |

### 3.5 属性提供契约：不存在

- `literacy|识字` **全仓 0 命中**（★控制方复核）；`ProductionModeDefinitionView`/`IndustryDefinitionView` 无"属性条件"字段；可用性判据**硬编码**在 `ExpectedProfitBook`。
- 最接近的通用协议是 `simos-util/.../facet/FacetProvider.java:12-18`（无键类型/未知值语义，且与生产路径无关）；`ProductionConsumerPort.java:20-32` 方向相反。⇒ **"属性 → 生产"的正向口不存在。**

### 3.6 生产资料与"区域覆盖"

- `AssetKind` = LAND/CATTLE/TOOL/WORKSHOP/MACHINE/SHIP（`simos-actor-api/.../asset/AssetKind.java:8-15`；LAND 千分亩、其余件 `:6`）。
- 世界种子：TOOL/LAND/WORKSHOP 有真份额（`simos-app/.../world/EconomySeeder.java:4840,:4847`）；**CATTLE/SHIP 只在资产规则词表**（`:4329`），**零份额零配方**（唯一读取 `ExpectedProfitBook.java:916` 的 upkeep 恒 0；`MERCHANT_CATTLE_PER_CITY` 已删 `:4827`）。
- 量纲冲突（如实记）：`laborPerUnit` 契约注为"千分劳动"（`IndustryDefinitionView.java:45`），实现按"千分劳动/日/规模 × cycleDays"（`ExpectedProfitBook.java:65-66,:485-488`）；土地只有 LAND 一个量纲，"1 单位劳动覆盖 **N 个 hex/区域**"今天**不可表达**（无"区域"量纲；hex 可用面积只用于创世播种 `EconomySeeder.java:2201-2202`，不进配方）。

---

## §4 差距表（对照 D-032 §2；★ 仅列本次回代码核实过的部分）

| D-032 口径 | 已有骨架 | 缺 | 冲突 |
|---|---|---|---|
| §2.1 具体生产定义归生产方式模块 | production 已是定义唯一权威（P1）；`IndustryDefinitionView` 承载投入/产出 | "生产方式"这一层没有 I/O 字段（`ProductionModeDefinitionView` 只有 4 字段）；mode↔industry 无字段级链接 | ★ **economy 侧 `ProductionCandidate` + `templateFor` 合成第二份定义视图**（§1.4） |
| §2.2 家户经验、衰减、熟练度 | **零骨架**（最近的是 `ProductionEfficiencyBook` 的逐 tick 累计 + 周期平均，但按 unit 不按家户、无增长/衰减/门槛） | 经验账、逐 tick 增长/衰减、上限/门槛、熟练度数值域全缺（§3.4） | — |
| §2.3 解锁 = 可用资格 | 契约壳有：`TechnologyDefinition.requires` / `level` / `MasteryScope.kind`（含 `"household"`） | 明说不做判定/闭包/解锁（`TechnologyDefinition.java:10-11`）；`masteries` 读口零消费者；世界种子零登记 | —（只是未实现） |
| §2.4 专门/共用经验 + 可配置公式 | **零骨架** | 无"经验条"可被多方案共用；无公式解析/求值器（§3.4） | ★ culture 的 `ProductionAptitudeRule`（文化×技能 → `[0,2000]‰`）是**要取代**的方向；其值域被 D-032 §2.4 明令不得当新公式上限 |
| §2.5 先需求—利润分配（主副业不设专门机制，2026-10-17 U6） | 候选利润排序（`LaborQueueBook:279-296`）、现有资源快照（`LaborQueueSettlement:615-649`）、依次投入（`LaborQueueBook:359-375`）；选择**自动化** | 需求**不在**生产 offer 排序键里（§2.4）；候选集是**先绑定**的（§2.1）；家户没有"想选哪个 mode"的表达 | ★ **缩减后不回填**（`LaborQueueSettlement:49-51`、`EconomySettlement:7972-7974`）——与 §2.5 第 5 条正面冲突；"主业 = 排序表第 1 项"按 U6 **不算冲突** |
| §2.6 通用迁移：经验随人、财债拆合 | **总量侧部分有**：钱/商品/债/资产/劳动配额按人口比例切 + 逐项相加，唯一实现 `simos-util/.../ProportionalSplit.java`（最大余数法） | 经验条"完整携带（不乘 p）"、合户按人口加权平均、跨领域通用特征口全缺（§3.3） | ★ 现实现是**迁移特例**（写在 `ModeMigrationSettlement` 里），D-032 §2.6 明确"不能只在某一转业特例里补经验搬运" |
| §2.7 生产可要求其他模块属性 | **无骨架** | 无"属性 → 生产"的正向口；`literacy\|识字` 全仓 0 命中（§3.5） | —（只是未实现） |

---

## §5 调查结论（控制方整理；★ 非用户原话，**不含任何设计裁定**）

1. **"生产方式"这一层今天定义不了投入/产出**：`ProductionModeDefinitionView` 只有 4 个字段，mode↔industry 连字段级链接都没有；真正的"生产方案"是**按 hex 实例化**的 `IndustryDefinitionView`（§1.1）。
2. **存在第二份竞争定义面**：economy 的 `ProductionCandidate` 被入口结算真读，并由 `templateFor` **合成**一份 `IndustryDefinitionView`（slots 手写、allocation 固定、MoneyOutput/skill 空）；产业载荷在 economy 侧另有一处同形解析（§1.4）。
3. **家户侧完全没有经验/熟练度状态**；现存的"熟练度"词面全在 culture（旧 `CultureId × 技能键 → [0,2000]‰` 表），恰是 D-032 §2.7 要解耦并取代的方向；解锁与可配置公式同样零存在（§3.4）。
4. **生产方式选择是自动的**（候选利润排序 → 依次分配）；"主业/副业"按 2026-10-17 用户裁定（U6）**不设专门机制**，专业化优势由熟练度加成自然体现 —— 故本项**不是冲突**。仍值得留意的事实是：候选集是**先绑定**的（只有已与 unit 有关系者才拿到候选），且排序表连完整候选都不是（§2.1/§2.5）。
5. **被缩减的劳动"留在空缺"是代码里写明的既定口径**，与 D-032 §2.5 第 5 条（释放资源应纳入后续分配）**正面冲突**（§2.6）。
6. **预期利润链骨架成熟**（关账日触发 → 排序 → 速度 → 落账），但**候选侧不读任何熟练度修正**（0 命中）⇒ "同一份定义"部分成立、"同一套熟练度公式"**不成立**（§3.1/§3.4）。

---

## §6 删改候选清单 v0（依据 D-032 口径与 2026-10-17 U6 删改授权；★ **待约束设计书冻结，不是派单**）

> 用户授权（设计原稿 U6，逐字）：「重点是，你要狠下心来把现有代码里不符合预期设计的给全删改掉，然后再把我的新设计应用上去」。
> **用户逐条裁定（2026-10-17 U7，逐字）**：「D1 直接删」「D2 直接删，文化部分目前都没咋做，留着模块占位置就行」；D3/D4 用户先追问"这是什么"⇒ 控制方已解释，**裁定待答复**。
> **用户补充裁定（2026-10-17，设计原稿 U10）**：D3 直接删（执行裁决 §1.1）；D4 归 production 并扩为计算职责（U8/边界裁决）；**不为 P1 设检查点提交，D-032 需要删改 P1 的代码/测试/文档时直接删**（原话："P1成功是个几把，需要删直接删"）⇒ 本清单的删改**不受"保住 P1 成果"约束**。
> 下表按"冲突 / 死面 / 形状阻碍"三类列候选；**D1/D2/D3 已定删除**，其余**删或改的最终取舍在设计书里定**，并须给出"删掉后什么能力由谁承担"。

### 6.1 冲突类（与 D-032 明确方向相反 ⇒ 删或改写）

| # | 候选 | 依据 / 裁定 |
|---|---|---|
| D1 | **科技点抽象面整体退役**：`contract/TechnologyDefinition.java`、`TechnologyId.java`、`TechnologyMastery.java`、`MasteryScope.java`、`TechnologyLookup.java`；`ProductionData` 的 `technologies`/`masteries` 组件；`spi/RegisterTechnologyHandler.java`、`SetMasteryHandler.java`（两条 GM 命令）；app 侧注册与载荷提示 | D-032 U1「不同的科技不一定必须要抽象成各种科技点…科技最终以生产方式的可选升级体现出来」；现状 `requires` 不做判定（`TechnologyDefinition.java:10-11`）、`masteries` 零消费者、种子零登记（§3.4）· ★ **用户 2026-10-17 裁定：直接删** |
| D2 | **"文化 → 生产效率倍率"整条线退役**：culture 的 `ProductionAptitudeRule`、`aptitude/ProductionAptitudeComposer`、`AptitudeBlend`、`CultureData.aptitudeRules`、`CultureLookup.rulesFor`、`culture.UpsertAptitudeRule`/`RemoveAptitudeRule`；app 的 `CultureAptitudeBridge`；economy 的 `ProductionEfficiencyModifier` 接收链（`EconomyDayStepper.updateProductionModifiers` / `EconomySession.productionModifiers` / `ProductionEfficiencyBook.accrueTick` 的 modifier 入参） | D-032 U5/§2.7「文化与生产效率解耦」；§2.4「`[0,2000]‰` 不得冒充新公式上限」· ★ **用户 2026-10-17 裁定：直接删；culture 模块本体保留为占位**（不删模块、不删文化标签面） |
| D3 | **P0 为文化合成而建、现已无新用途的口**：`simos-economy/.../EconomyProductionLaborLookup.java`、`economy-api/.../production/ProductionLaborLookup.java`、`UnitProductionFacts.java`、`HouseholdLaborShare.java` | 唯一消费者是 composer（`ProductionAptitudeComposer.java:62,72,114`）与 app 装配（`ProductionAptitudeAssembly.java:51-54`）⇒ D2 删后零消费者 · ★ **用户 2026-10-17 裁定：直接删**（含相关装配/引用）；新经验若需劳动事实，**按新生产模型提供**，不因用途相似而保留旧文化专用通路 —— `docs/superpowers/specs/2026-10-17-d032-production-execution-decision.md` §1.1 |
| D4 | **第二份竞争定义面收口**：economy 的 `model/ProductionCandidate.java`、`EconomyEntrySettlement.templateFor`（`:1014-1051`）、`EconomyPayloads` 的产业/模式解析（`:1445,:1484`） | D-032 §2.1「不能在经济/app 中另维护一份竞争权威定义」；★ **用户 2026-10-17 裁定（比本节原建议更宽）**：**D4 归生产方式模块** —— production 统一维护方案，并**提供"单个家户采用指定方案时的预计/实际产出与生产导致的家户状态变化"能力**（职责超过"保存、返回模板"）；economy 调用 production 后负责家户间经济关系；**不能再用 `templateFor` 现场拼竞争定义**；且这是**领域职责调整、不是搬文件**，"按需进入生产/创建产业实例"的既有能力必须在新分工下承接 —— 见执行裁决文档 §1.2–§1.4（★ 该文档 §头部已**局部取代**本节 D3/D4 的"待答复"状态与 §6.5 的窄建议） |

### 6.2 死面类（今天的零消费者字段/陈旧注释）

| # | 候选 | 依据 |
|---|---|---|
| D5 | `IndustryDefinitionView.dailyInputPerUnit` / `dailyLaborPerUnit`：**删**，或在"每日"维有真实语义后**赋予用途** | 全仓无结算读取（§1.2；`EconomySettlement.java:5518` 自认零读取点） |
| D6 | `IndustryDefinitionView.allocation` 死面（结算走 `ProductionRules` 关系） | `allocation()` 只有 GUI 读（§1.2）；`EconomySettlement.java:145` 称其为旧口径 |
| D7 | 陈旧文本清理：`simos-production/.../package-info.java` 的"定义尚未迁移"、`EconomySettlement.java:5421` 引用已删字段的 javadoc、`GenesisModuleSlices.java:27` 的三处标记、economy 两处"E3 经验"注释 | §8 存疑 2；D-032 取代关系 |

### 6.3 形状阻碍类（新设计必须改的形状）

| # | 阻碍 | 影响 |
|---|---|---|
| D8 | `ProductionModeDefinitionView` 只有 4 字段、mode↔industry 无字段级链接 | D-032 §1 要的"生产方案（投入/条件/产出）"今天无处安放（§1.1） |
| D9 | `AssetKind` = 6 值**编译期** enum，`capacityPerUnit` 以它为键且每 1 规模单位 `Long>0` | 用户例子的"小型/大型农具"与"1 件工具覆盖 5 区域"表达不出来（§1.5/§3.6） |
| D10 | 无"区域"量纲；产业实例强制带 hex | "1 单位劳动覆盖 N 区域"与"跨地区共享方案定义"今天不可表达（§1.3/§3.6） |
| D11 | 家户侧无经验/熟练度状态；解锁与公式零存在 | D-032 §2.2–2.4 需要**全新状态面**，且要决定它归哪个模块（§3.4） |

---

### 6.4 D3 是什么（给用户的解释）

**一句话：它是 P0 为了让"文化加成"算得出来而建的一个只读小口 —— 问"这个生产单元（unit）是谁在干、每人出了多少劳动"。**

- `ProductionLaborLookup`（接口，住在 `simos-economy-api`）：`Optional<UnitProductionFacts> factsOf(ProductionUnitId unit)`。
- `UnitProductionFacts`（record）：`unit` + `industry` + `List<HouseholdLaborShare> laborShares`。
- `HouseholdLaborShare`（record）：`household` + `laborMilli`。
- `EconomyProductionLaborLookup`（economy 侧实现，读 `EconomyData` 里的劳动承诺）。
- **谁用它**：只有 culture 的 `ProductionAptitudeComposer`（`:62,:72,:114`）与 app 的装配 `ProductionAptitudeAssembly.java:51-54` —— 用途是"按出工家户的劳动量加权"算文化倍率。**D2 删掉那条线后，它零消费者。**

**留还是删**（D-032 §2.2 的经验积累也需要"这一 tick 谁真在生产"，所以它有点像"顺手的现成读口"）：
- (a) **跟着 D2 一起删**，新设计里按经验账的需要重新开读口 —— ★ 推荐（与用户 2026-10-17 裁定一致）。理由：它的存在理由就是"给文化倍率做劳动加权"（正是被删的那条线），而且它的索引方向是"按 unit"，新经验的键是"家户 × 经验条"；留着会让后人以为它还有主。
- (b) 留着当经验积累的现成读源（省几十行，但要接受"按 unit 索引 + 带 industry 字段"的形状）。

★ **用户 2026-10-17 裁定：走 (a) —— 直接删**（含相关装配/引用）；并明确"新经验执行若需劳动事实，**按新生产模型提供**，不因相似用途而保留旧文化专用通路"（执行裁决文档 §1.1）。

### 6.5 D4 是什么（给用户的解释）

**一句话：今天"一个生产方案的投入产出"能从两个地方拿到，而且两处的数值不完全一样。**

- **第一处（P1 后的权威）**：`simos-production` 的 `IndustryDefinitionView` —— 状态在 `ProductionData.industries`，由 `production.UpsertIndustry` 命令写。
- **第二处（economy 自己那份）**：`ProductionCandidate`（`simos-economy/.../model/ProductionCandidate.java:57-70`）—— GM 登记的"预设生产方式"，字段自带 `output/outputPerUnit/inputPerUnit/requiredAssets/laborPerUnit/buildDays/cycleDays/regime/laborSource/...`；它是 **economy 状态的一个持久组件**（`EconomyData.java:320`：`Map<CandidateId, ProductionCandidate> candidates`）。
- `EconomyEntrySettlement.templateFor`（`:1014-1051`）会**用候选现场合成一份 `IndustryDefinitionView`** 当"这个产业的定义"用：`capacityPerUnit` 取 `requiredAssets`、`slots` **手写四档**（贫农 950/中农 900/富农 750/地主 100）、`allocation` **固定 `Split(500,500)`**、`MoneyOutput` **空**、`skill` **空**；入口路径就这么用（`:871`）。
- 另外 economy 侧还有**第二份产业载荷解析**（`EconomyPayloads.java:1445,:1484`，活跃调用 `:351`）。

**为什么会有它**：它是"**按需建产业**"能力的载体 —— 转业（`ModeMigrationSettlement.java:207-208` 建 trade 产业）与进入生产（`EconomyEntrySettlement.java:868`）需要在运行期造出新产业；P1 §9.1 明确要求保留这个能力。

**为什么与 D-032 冲突**：D-032 §2.1 要求"生产方式模块维护具体方案的劳动、工具、投入、产出与数值定义……不能在经济/app 中另维护一份竞争权威定义"。现在 economy 的候选既带 I/O 数值、又能合成定义视图 ⇒ 同一个方案走不同路径会得到**不同数值**（尤其 slots／分配／铸币／技能是模板凭空给的）。

**收口的两种做法**：
- (a) **把"生产方案"的定义与数值收归 production**：economy 的候选退化为**请求/意图**（要造什么、在哪一格、按哪个已存在方案），由 production 侧注册投影（P1 已有 `DefinitionRegistration` 先例）在同一次 Advance 里落成 production 定义；入口/转业改为从 production 契约**读真定义**，不再合成 —— ★ 推荐（与 U1"科技 = 可选升级方案"、§2.1"唯一权威"一致）。
- (b) 保留候选为"模板数据"，但把模板值在创世/命令时**播种成 production 定义**，入口只读 production。差别在"谁持有那份数据"。

（代价提示：(a)/(b) 都要动 `candidates` 这个 economy 组件、codec/change/往返守卫与 GUI 条目 —— 机械但面不小，须在设计书里逐个点名。）

★★ **2026-10-17 用户裁决（口径已超出上面 (a)/(b)）**：**D4 归生产方式模块**，且 production 的职责**不止于保存/返回模板** —— 它要**提供"单个家户采用指定方案时的预计/实际产出与生产导致的家户状态变化"能力**；**economy 调用 production 后负责家户间经济关系**，不能再用 `templateFor` 现场拼竞争定义。
⇒ 上面 (a)/(b) 只是"定义数据放哪"的两种做法，**正式口径以 `docs/superpowers/specs/2026-10-17-d032-production-execution-decision.md` 为准**（该文档 §2 给出责任划分与调用关系：economy 提供资源与经济条件 → production 试算 → economy 选择并分配投入 → production 按最终投入算实际生产与状态变化 → economy 处理交易与收益分配 → 各模块 ChangeSet 汇入同一 Revision）；§1.4 另明确这是**领域职责调整**，"按需进入生产/创建产业实例"的能力必须在新分工下承接。

---

## §7 下一步（控制方建议，未派单）

1. 按 D-032 + 本报告 + **执行裁决文档**（`docs/superpowers/specs/2026-10-17-d032-production-execution-decision.md`）写 **D-032 约束设计书**，需冻结：
   - **删改清单终稿**：D1/D2/D3 已定"直接删"；D4 按"归 production + 计算职责"处理；D5–D7 死面清理；D8–D11 形状改动；
   - **生产计算契约**（新职责）：production 对"给定家户条件与投入 → 可生产规模/预计消耗/产出"以及"按最终投入 → 实际消耗/产出/经验/进度变化"提供什么形状（执行裁决 §3 明确：只读定义契约要按新职责设计**计算契约**；预计无副作用；实际依据最终投入）；
   - **生产状态的归属/拆分**（实例、进度、经验、解锁各归谁；执行裁决 §3 要求在设计书明确；且不得让 production 绕过领域写入口改库存/人口）；
   - **统一预算与不重复占用**（劳动/土地/工具/原料跨方案、跨 unit 不得重复占用）；
   - **按需新建定义 vs 按需新建实例**的区分（选用已有方案 = 建**引用方案稳定 ID** 的实例，不复制定义）；
   - **模块边界与依赖**（production 要算生产就需要只读事实，可能触及现有允许面 ⇒ 需按裁决 §3 末条设计契约，不许反向依赖 economy 实现；若确需新增允许边，先报告）；
   - 验收判据与负向用例；文件 owner。
2. 设计书过用户目后，按责任区派实现（一个 owner 写生产代码、测试最后统一一个人做）。
3. 文化线（D-033）按用户 2026-10-17 裁定**暂时搁置、只留占位**（模块保留、不删标签面），不在本批动手。

---

## §8 存疑（本次未核到 / 只对本树成立）

1. 本树是**未提交工作副本**（P1 收口 + 在飞改动）：所有"权威归属/字段有无"结论**只对本树当刻字节成立**，不代表主树。
2. 陈旧文本若干：`simos-production/.../package-info.java` 仍写"生产定义尚未迁移"；`EconomySettlement.java:5421` javadoc 仍引用已不存在的 `IndustryDefinitionView#cycleInputUsedMilli()` —— **未逐处排查同类**。
3. 未核 gov/army/ga 是否读 `IndustryDefinitionView`（grep 命中只在 production/economy/culture/app）。
4. `PRODUCTION_RUNTIME_ASSET_KINDS`（`EconomySeeder.java:4329`）是否真在播种期为 CATTLE/SHIP 建份额：只确认"词表里有、配方里没有"，未逐步走完播种路径。
5. 迁移写回的 `laborSources` 是否当天就进次日候选：只确认迁移段在劳动排队**之后**、`SettlementIndex` 日内多次重建（`EconomySettlement.java:1203,1333,1525,3021,4465`），**未追跨日时序**。
6. 套利 offer 的 `maxAbsorbable` 语义与生产 offer **不同源**（`LaborQueueSettlement.java:723`），是否造成排序偏差未判。
7. `PrimaryModeRanking` 类注说"排序输入 = 议价权 + 库存"（`:28-29`），但实现排序键（`:213-219`）不含二者 ⇒ **注释比实现宽**（二者只当 merchant 门槛与读数）。
8. 量纲口径未钉死："千分亩" vs "毫亩"视为同一单位但无断言；`laborPerUnit` 契约注（千分劳动）与实现口径（千分劳动/日/规模 × cycleDays）不一致（§3.6）。
9. 本文全部为**静态阅读**结论（"零存在"是 grep 结论，不是编译期结论）；**未跑任何构建/测试**，未复跑 D-031 的中性注入。
