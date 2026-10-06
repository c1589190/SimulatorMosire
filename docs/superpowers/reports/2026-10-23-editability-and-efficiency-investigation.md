# 调查：GM/其他模块可编辑性 与 生产效率变量（只读静态核查）

> 来源：2026-10-23 只读调查代理报告（未跑 Maven/测试/运行时；结论全部来自源码静态阅读与 `git grep`，自证边界见文末"我没核到"）。
> 触发原话（用户，逐字）：
> ① "我要求这些东西不但要能被GM改，还要能被用项目的其他模块改，有办法吗？规划调查一下"
> ② "此外还要实现一个容易修改、伴随着tick动态波动、必然被外部模块决定的生产效率变量，这个貌似已经有了，我希望以其来解决后续生产效率受到生产资料、文化、天气气候等影响时等效率变化，这个也一并调查一下吧"
> 后续裁定与冻结的落地方案见 `docs/superpowers/specs/2026-10-23-production-efficiency-framework.md`。

---

# 调查结论：EconomyData 编辑面 + 生产效率变量（只读，2026-10-23）

**读法**：路径均相对仓根；`file:line` 取当前工作树。工作树有一处已有未提交改动：`simos-app/.../EconomySeeder.java`（`GRAIN_OUTPUT_PER_MU 67→34`、`GENESIS_GRAIN_RESERVE_MULTIPLIER 30→15`），下文会区分「工作树」与 `HEAD`。本次未改任何文件、未跑 Maven；文中「代码事实」与「计划/文档措辞」分开标注，冲突以代码为准。

**先钉死两层概念（用户裁定 ③）**：

| 层 | 身份类型 | 例子 | 住在哪 |
|---|---|---|---|
| **生产模式 / 生产方式** | `ProductionModeId` | `tenancy_fixed_kind`、`wage_farm`、`handicraft_workshop`、`merchant`、`displaced` | 静态目录 `DefaultProductionModes`（`simos-economy/.../model/DefaultProductionModes.java:89-154`）；运行期权威是 `EconomyData.modes/classStructures/classPositions` |
| **产业 / 生产单位** | `IndustryId`、`ProductionUnitId` | `farm@0_0`、`craft@0_0`、`weave@0_0`、`trade@0_0`；`unit-farm@0_0-HOUSEHOLD-…` | `IndustryId` 格式 `<kind>@q_r`（`IndustryHexKeys.java:19-30`）；`ProductionUnitId` = `unit-<industry>-<operator.kind>-<operator.id>`（`simos-economy-api/.../id/ProductionUnitId.java:40-52`）；运行期权威是 `EconomyData.industries` 与 `EconomyData.units` |

用户 2026-10-23 原话：「生产方式就生产方式，产业就是产业，不要搞混」（`docs/superpowers/specs/2026-10-23-weave-not-a-market-subject.md:9`；概念纪律见该文 `:13-16`）。本报告以下绝不把两者混称：`modes` 表 ≠ `industries` 表。

---

## 1. 能力矩阵（第一部分问题 1~6）

### 1.1 全部 economy 命令（20 个 handler / 20 个 type）

`spi/*Handler.java` 共 **20 个**（`ls …/spi/*Handler.java | wc -l` = 20），逐个都有 handler。`implements CommandHandler` 计数同为 20。下表「提交面」：GM = GM 桶（`simos.command.submit` 或专用窄工具）；令 = 非 `GmOnlyCommand`、进 `DirectiveWhitelist`；裁 = 该命令还实现了 `CommandTargets` 且真的返回目标，能过 `sd.AdjudicateTick` 的目标闸。

| # | type | 文件 | GmOnly | CommandTargets | 载荷核心字段 | 写哪些表 | 专用工具 / 桶 |
|---|---|---|---|---|---|---|---|
| 1 | `economy.Seed` | `EconomySeedHandler.java:56` | 否 | 是（逐格 economy 路径） | `mapId,rulesVersion,entries[{q,r,industries[],units[],classes[],allocations[],assetShares[],…}]` | 几乎全部组件；见 §1.5 | `simos.worldgen.initialize`、`simos.region.seed`（GM 桶，均走 `submitBatch`） |
| 2 | `economy.RegisterGovernment` | `…RegisterGovernmentHandler.java:89` | **是** | 是（返回空） | `govUnitId,nationRef,q,r,governmentId?,household?,residence?,stratum?,population?,laborMilli?,participationPerMille?,classPosition?,issuable?,seignioragePerCycle?,debtIssuePerCycle?,reason?` | `classes`/`classStandings?`/`governments` | 由 `simos.gov.createOffice`、`simos.map.province.apply` 组合工具带上（GM 桶） |
| 3 | `economy.RegisterHousehold` | `…RegisterHouseholdHandler.java:67` | **是** | 是（返回空） | `household,q,r,residence,stratum,participationPerMille?,reason` | `classes` | 由 `simos.unit.raiseUnit` 组合工具带上（GM 桶） |
| 4 | `economy.UpsertHouseholdPeriodicAdjustment` | `…UpsertPeriodicAdjustmentHandler.java:55` | **是** | 是（返回空） | `id,payer,payee?,goodsPerCycle?,moneyPerCycle?,reason,periodDays,phaseDay,startsOnDay,expiresOnDay?,policySource` | `periodicAdjustments` | `simos.gm.periodicAdjustment`（GM 桶） |
| 5 | `economy.RemoveHouseholdPeriodicAdjustment` | `…RemovePeriodicAdjustmentHandler.java:37` | **是** | 是（返回空） | `id,reason?` | `periodicAdjustments` | 同上 |
| 6 | `economy.MigrateHousehold` | `…MigrateHouseholdHandler.java:64-65` | 否 | 否 | `household,toHex` | `classes`（只改 view） | 无；令白名单可见但 `AdjudicateTick` 因无目标 fail-closed |
| 7 | `economy.TransferAssetShare` | `…TransferOwnershipStakeHandler.java:92-108` | 否 | 否 | `share,quantity?,toOwner?,toOperator?,kind?` | `assetShares`（+`pledges`） | 无 |
| 8 | `economy.SetMarketPrice` | `…SetMarketPriceHandler.java:60-68` | 否 | 否 | `q,r,commodity,price` | `markets` | 无 |
| 9 | `economy.AddDemand` | `…AddDemandHandler.java:74-78` | 否 | 否 | `id?,scope,household|hex?,commodity,kind,unit,quantityPerCycle,price?,createdDay,priority` | `demands` | 无 |
| 10 | `economy.CancelDemand` | `…CancelDemandHandler.java:46` | 否 | 否 | `demand` | `demands` | 无 |
| 11 | `economy.RegisterCandidate` | `…RegisterCandidateHandler.java:63-131` | 否 | 否 | `id,version?,name?,output,outputPerUnit,inputPerUnit?,requiredAssets?,laborPerUnit?,buildDays?,cycleDays,regime,laborSource?,acceptedRightKinds?,assetSource?` | `candidates` | 无 |
| 12 | `economy.SetHouseholdClass` | `…SetHouseholdClassHandler.java:58` | 否 | **是**（可选 `at`） | `household,position,originalPosition?,reason?,day?` | `classStandings` | 无；可进决策令（目标 = 家户所住格） |
| 13 | `economy.SetHouseholdParticipation` | `…SetHouseholdParticipationHandler.java:57` | 否 | **是**（可选 `at`） | `household,positions[]?,modes[]?,reason?,day?` | `classStandings` | 无；可进决策令 |
| 14 | `economy.SetHouseholdLabor` | `…SetHouseholdLaborHandler.java:49` | 否 | **是**（可选 `at`） | `household,laborMilli?|participationPerMille?` | `classes` | 无；可进决策令 |
| 15 | `economy.UpdateDemand` | `…UpdateDemandHandler.java:57` | 否 | **是**（`at`/`hex`） | `demand,scope,household|hex,commodity,kind,unit,quantityPerCycle,price?,expiresDay?,priority` | `demands` | 无；可进决策令 |
| 16 | `economy.SwitchMode` | `…SwitchModeHandler.java:53` | **是** | 是（从 org id 解格） | `organizationId,toModeId,retainOriginalPerMille,effectiveDay?,reason?` | `modeTransitions`（只登记 PENDING） | 无；GM 直通；不标决策工具 |
| 17 | `economy.GmAdjust` | `…GmAdjustHandler.java:74` | **是** | 是（返回空） | `adjustment,parameters,reason` | 见 §1.4 十条 | **`simos.economy.adjust`**（GM 桶，preview/apply 共用 `project`） |
| 18 | `economy.ClearRegion` | `…ClearRegionHandler.java:115` | **是** | 是（返回空） | `regionId` | 见 §1.5 破坏面 | `simos.region.clearData`（GM 桶，social/actor/economy/sd 同批一条 revision） |
| 19 | `economy.UnitBorrow` | `…UnitBorrowHandler.java:83` | **是** | 是（返回空） | `unitId,borrowerHousehold,lenderHousehold,unit(money|grain),principal,interestRatePerMille,nextDueTick,terms?,reason?` | `debtContracts` | 无专用工具；资金腿须同批 `actor.TransferAccounts`（类注 `:58-70`） |
| 20 | `economy.UnitRepay` | `…UnitRepayHandler.java:64` | **是** | 是（返回空） | `unitId,borrowerHousehold,lenderHousehold,unit,amount,debtId?,reason?` | `debtContracts` | 同上 |

**实际 `GmOnlyCommand` 实现**（`git grep "GmOnlyCommand {" …/spi/`）：`ClearRegion`、`GmAdjust`、`RegisterGovernment`、`RegisterHousehold`、`Remove/UpsertPeriodicAdjustment`、`SwitchMode`、`UnitBorrow`、`UnitRepay` = **9 条**。其余 11 条非 GmOnly；但其中只有 5 条（`Seed`、`SetHouseholdClass`、`SetHouseholdParticipation`、`SetHouseholdLabor`、`UpdateDemand`）实现了非空 `CommandTargets`，能真正过裁决目标闸（`AdjudicateTickTool.java:499-518`：无 `CommandTargets` 或无目标 ⇒ 暂不可裁决）。`SetMarketPrice` 的类注自己写明「directive 内会被 fail-closed 拒」（`:39`）。

### 1.2 app 侧 economy 工具与桶

- **写**：`EconomyAdjustTool`（`app/tools/write/EconomyAdjustTool.java:72`）只注册在 `SimosToolSource.addGmWrites`（`SimosToolSource.java:719`），名字 `simos.economy.adjust`；决策人桶 `addDecisionAgentWrites`（`:780-831`）里没有任何 economy 工具；`DecisionCallerFactory.WHITELIST` 里与 economy 有关的只有读工具 `EconomyHexTool.NAME`（`app/access/DecisionCallerFactory.java:137`）。
- **组合写入**（GM 桶、`submitBatch` 一条 revision）：`WorldgenInitializeTool`（含 `economy.Seed`）、`RegionSeedTool`（同）、`RegionClearDataTool`（含 `economy.ClearRegion`）、`GovCreateOfficeTool`/`ProvinceApplyTool`（含 `RegisterGovernment`）、`RaiseUnitTool`（含 `RegisterHousehold`）、`GmPeriodicAdjustmentTool`（含两条 periodic 命令）。
- **读**：`EconomyHexTool` 四桶共享（`SimosToolSource.java:855`，类注 `EconomyHexTool.java:19-27`）；`EconomyOwnershipTool` 标 `GmOnlyRead`（`:858`，类注 `EconomyOwnershipTool.java:28-36`）。GUI 只有 `/api/economy/overview|gov|hex|ownership` 四个只读口（`GuiServer.java:294-324`），无 economy 写端点。
- **GM 通用口**：`simos.command.submit`（`CommandSubmitTool.java:36`）注册在 `addGenericWrites`，仅 GM 桶（`SimosToolSource.java:486-494`）⇒ GM 可提交全部 20 条 economy 命令。**不存在** `simos.command.submitBatch` 工具（全仓代码检索 0 命中；`docs/.../2026-10-23-planned-not-implemented-inventory.md:87` 也记为未做）。
- **Catalog 可见性**：GM 看全量（含 GmOnly）；决策人看「自己权限内的工具 ∪ `DirectiveWhitelist` 可嵌入类型」（`CatalogVisibility.java:66-73`）⇒ 决策人**看不到** `economy.GmAdjust`/`SwitchMode`（与 `SimosToolSource.java:820` 注释一致）；但会看到部分其实无法裁决的非 GmOnly economy 命令（如 `AddDemand`）——这是 catalog 的已知过度近似，真正执行仍由 `AdjudicateTick` 的 `CommandTargets` 闸挡住。
- **决策人令白名单**：`DirectiveWhitelist` 由注册面 − `sd.*` − 通用写派生（`simos-sd/.../spi/DirectiveWhitelist.java:31-50`）；economy 命令是否在册由 `Shell` 注册时过滤（`Shell.java:733-756`）。**没有任何 economy 专属决策人窄工具**；`ProposalCatalog` 的工具清单里 economy 0 命中（`app/decision/ProposalCatalog.java` 全文检索 `economy`/`Economy` = 0，已用已知串自证工具有效）。

### 1.3 `simos-economy-api` 今天暴露什么

包清单（`find simos-economy-api/src/main/java -name '*.java'`，共 11 包）：

| 包 | 关键类型 |
|---|---|
| `api` | `package-info`（边界声明） |
| `api.id` | 全部稳定 ID：`IndustryId`、`ProductionUnitId`、`ProductionModeId`、`ClassStructureId`、`ClassPositionId`、`AssetRuleId`、`AssetShareId`、`ProductionOrganizationId`、`CandidateId`、`DebtContractId`、`CommodityId`、`CurrencyId`、`GovernmentId`、`TransferId`…（40+ 个） |
| `api.cohort` | `CohortKey`、`HouseholdActors`、`HouseholdIds`、`ResidenceKind` |
| `api.relation` | `ProductionRules`、`CompensationRule`、`Payee`、`Pool`、`Weight`、`RuleType`、`LaborSource`、`Basis`、`SubsistenceObligation` |
| `api.transfer` | `Transfer`、`TransferReason` |
| `api.market` | `BuyOrder`、`SellOrder`、`MarketRegion`、`MarketNode`、`TradeRoute`、`ShipmentBatch`、`PriceMode`、`MarketUnfilledReason`… |
| `api.money` | `MoneyAuthority`、`MoneyIssuance`、`MoneyIssuanceRecord`、`CurrencyDef`、`MoneyVocabulary`… |
| `api.debt` | `DebtTerms`、`DebtStatus`、`DebtUnit`、`RepaymentRule`、`InterestTiming`… |
| `api.labor` | `HouseholdLaborCommitment`、`HouseholdLaborTimeTable` |
| `api.population` | `LotChange`、`LotMigration` |
| `api.stock` | `HouseholdStockDeduction`、`DeductionReason`、`HouseholdPeriodicAdjustment`、`PeriodicHouseholdAdjustmentId` |

**明确写「没有」**：economy-api 里**没有任何命令请求 record / builder / handler / `CommandEnvelope`**。检索 `record .*Command`、`record .*Request`、`class .*Builder`、`CommandHandler`、`CommandEnvelope` = 0（`git grep` exit 1；同命令先用已知存在的 `public record` 命中自证）。现有的「最接近命令契约」的是 `HouseholdStockDeduction`（`api/stock/HouseholdStockDeduction.java`，typed record + 封闭 `DeductionReason`），但它对应的是 **actor 切片**的 `actor.DeductHouseholdStock` 命令，不是 economy 命令。

### 1.4 `economy.GmAdjust` 十条 action（可改对象 / 守卫 / 被谁调用）

十条白名单与语义唯一落点：`EconomyGmAdjustments.java:87-117`、`project()` `:149-178`；handler 只做形状校验后调 `project`（`GmAdjustHandler.java:274`）；GM 工具 preview/apply 也调同一个 `project`（`EconomyAdjustTool.java:231`）。「被谁调用」= handler + `simos.economy.adjust`，无第三处。

| action | 改的对象 | 引用/版本守卫（具名拒） |
|---|---|---|
| `forgiveDebt` | `debtContracts`（减/清本金） | 合同存在、本金 >0、`0<amount≤本金`；不碰粮/钱（`:182-218`） |
| `setLiquidationPolicy` | `liquidationPolicies` | 引用的 `AssetRule` 必须存在；`maxLiquidatePerMille∈[0,1000]`、`protectedReserve≥0`、`priceSource` 与 `policyValue` 一致（`:222-284`） |
| `upsertProductionMode` | `modes` | `classStructureId` 已存在；新 id 缺省 version=1；既有 id 修改必须显式 **version 严格推进**，同 version 同值可幂等重放（`:304-366`） |
| `deactivateProductionMode` | `modes`（删除） | 被 `classStructures/classPositions/productionOrganizations/assetRules/modeTransitions/pledges` 任一引用 ⇒ 拒并列出引用者（`:378-459`） |
| `upsertClassStructure` | `classStructures` + `classPositions`（成对写口） | `modeId` 存在；既有结构不可换绑 modeId；位置要存在/形状一致；`defaultSharesPerMille` 键必须是本结构位置且 ≥0；新建必须给非空 positions（`:469-616`） |
| `upsertClassPosition` | `classPositions` 及所有结构内副本 | `modeId` 存在；既有位置 modeId 不可改；孤儿位置必须给 `classStructureId`（`:632-754`） |
| `upsertProductionRelation` | `relations`（键 = unit id） | `activity` 必须对应既有 unit；`operator` 必须与 `unit.operator` 逐值一致；rules 形状校验（`:769-855`） |
| `upsertAssetRule` | `assetRules`（键由 `(modeId,assetKind)` 派生） | `modeId` 存在；显式 `id` 必须等于派生值；新建后四字段必填；`liquidationPriority≥0`（`:864-956`） |
| `upsertProductionOrganization` | `productionOrganizations` | `modeId`/`classPositionId`/`unitId` 引用存在且位置属于 mode；`organizer` 与 unit.operator 一致；`ACTIVE/EXITING` 必须有 unitId，`SHORTAGE` 必须有具名 reason；资产/家户引用 fail-closed（`:968-1144`） |
| `upsertCandidate` | `candidates` | **显式 modeId ⇒ 拒**（模型无此字段）；`regime` 必须已登记；version 必须推进；新建 `output/outputPerUnit/cycleDays/regime` 必填（`:1157-1305`） |

**明确不覆盖**：`Industries`（产业模板）、`assetShares`（实物份额本体）、`units`（生产单元本体）、`flows`/`operatorConditions`/`crisisSignals`/`debtCapacity` 等派生读数（类注 `:75-81` 明文拒绝直写）。

### 1.5 产业（Industry）编辑面：没有

**代码事实**：全仓 main 里 `withIndustries` 只有 **1 个调用点**：`EconomyClearRegionHandler.java:375`（删除）。`EconomySeedHandler` 不走 `withIndustries`，而是 `merge(base.industries(), seeded.industries())` 后 `new EconomyData(...)`（`:155-157`）。`EconomyGmAdjustments` 与所有 economy 命令的载荷字段里都没有 `capacityPerUnit`/`laborPerUnit`/`outputPerUnit`（`outputPerUnit` 只出现在 `upsertCandidate` 的 `ProductionCandidate`，不是 `Industry`）。**结论：没有任何编辑既有产业模板（`farm@hex` 的产能/投入/产出/周期/分配）的命令或工具。**

现有产业模板的来源/改写只有三条：

1. **创世/追加 Seed**：`economy.Seed` 载荷里的 `industries[]` 由 app `EconomySeeder` 生成（`agriculture:3823`、`householdWeaving:3869`、`handicraft:3904`、`trade:3947`），经 `EconomyPayloads` 落成 `Industry`（`EconomyPayloads.java:1353-1410`）。
2. **候选采用时物化新模板**：`EconomyEntrySettlement.execute` 在候选可行时 `tables.industries.putIfAbsent(intent.industryId(), templateFor(candidate, industryId))`（`EconomyEntrySettlement.java:868`），`templateFor` 由 `ProductionCandidate` 的 `requiredAssets/laborPerUnit/outputPerUnit/inputPerUnit/cycleDays/regime` 拼出一个 `Industry`（`:1012-1047`）。这不是「编辑已有模板」，且模式仍是候选字段的投影。
3. **破坏性重建**：`economy.ClearRegion` 删产业，再 `simos.region.seed`/`worldgen.initialize` 重新 Seed（`RegionSeedTool`、`WorldgenInitializeTool` 的 `submitBatch`）。

**`economy.Seed` 的合并语义与「同格已有经济状态 ⇒ 拒」**（`EconomySeedHandler.java`）：

- `base.meta()` 空 ⇒ **首次播种 = 整份载荷替换**（`:88-116`）：`EconomyChangeSet.between(base, seeded)`，载荷未声明的组件（如 GM 先写的 `demands`/`markets`）会按 `between` 语义被移除。这是一个值得注意的破坏面。
- `base.meta()` 非空 ⇒ **按格追加**（`:117-238`）：先算「已占用格键」= 该格有 `industries` id 或家户行（`occupiedHexKeys:241-252`），载荷里任一格已占用 ⇒ **整份拒绝**并点名该格（`:119-134`）；否则逐组件 `merge`/`putIfAbsent` 合并，`meta` 不覆盖；`demands`/`candidates`/`periodicAdjustments` 直接取 base（Seed 不声明它们，`:179-215`）；`governments` 是 `mergeKeepingExisting`（先到者胜，`:262-268`）。
- `rulesVersion`/`activatedDay` 只在首次写入；追加不覆盖。

**`economy.ClearRegion` / `simos.region.clearData` 的破坏面**（`EconomyClearRegionHandler.java:69-104`、清理体 `:300-420`）：

- **会清**：`industries`（按格）、`markets`（按格）、`relations`/`units`/`operatorConditions`（按产业/unit）、`assetShares` 及引用它的 `pledges`、`classes`（按 view.hex）、`flows`、`classStandings`、`classShares`、`allocations`、涉及家户的 `debtContracts`、`demands`（按格或家户）、`crisisSignals`、`productionOrganizations`（unit/劳动来源/资产任一被清）及引用它的 `modeTransitions`、`merchantFirms`。
- **有意不清**（类注 `:84-104`）：`meta`、`shipments`（跨区货权）、`laborSupply`、`candidates`、`modes`、`classStructures`、`classPositions`、`assetRules`、`liquidationPolicies`、`governments`、`moneyIssuances`、`periodicAdjustments`。
- 目标 Region 必须在 `map.regions` 里；`regionId` 查不到 ⇒ `Rejected`；命令 GM-only。app 侧 `simos.region.clearData` 把 `social.ClearRegion → actor.ClearRegion → economy.ClearRegion → sd.PutInfo` 打成一**条 revision**，只对有命中的域下单（`RegionClearDataTool.java:41-48,288`）。

### 1.6 生成器常数：不是 EconomyData 状态，也没有运行期写口

`EconomySeeder`（app 模块）里的关键常量：`MU_PER_HEX=3100`（`:245`）、`GRAIN_OUTPUT_PER_MU` 工作树 = **34** / HEAD = 67（`:264`，diff 已确认）、`SEED_MILLI_PER_MU=8000`（`:280`）、`FIBER_OUTPUT_PER_MU=6`（`:314`）、`GENESIS_GRAIN_RESERVE_MULTIPLIER` 工作树 = **15** / HEAD = 30（`:501`），另有 `LABOR_MILLI_PER_MU`、`MARKET_PRICE_FIBER` 等。

- 它们是 `public static final` 编译期常量，**不在 `EconomyData` 的任何组件里**；`EconomyData` 只含 `meta` + 28 张 Map（record `:225-254`；注意类注里「30/31 个组件」的措辞与 record 实际 29 个字段不一致，以 record 为准）。
- 没有任何命令/工具写它们。它们只在 **seed 时**被 `EconomySeeder` 用来构造 `Industry`/`assetShares`/储备等载荷；seed 之后这些数值以 `Industry.outputPerUnit`/`cycleInputPerUnit`、`OwnershipStake.quantity` 等形态成为 `EconomyData` 状态——但同样没有编辑端口（§1.5）。
- 同类还有 `StressPolicy` 阈值与缩产系数（`plannedScalePerMille:78-92`）、`EconomySettlement.FEED_PER_MILLE=0`/`DEPRECIATION_PER_MILLE=30`（`:283,292`）、`FAMINE_MORTALITY_PER_MILLE=0`（`:409`）、`MerchantPolicy`/`CityLand` 常量——都不是世界状态，GM 只能改代码/配置后重建。
- 另有 `config/worldgen/v17levant-nations.json` 的随机化范围（人口/城市化等）在 `WorldgenInitializeTool` 加载期读取；仍不是世界状态、不落 revision。

### 1.7 能力矩阵（EconomyData 组件 × 写口）

说明：GM = `simos.command.submit`/GM 窄工具；令 = 决策令可嵌（还要过目标闸）；模块/桥 = 只有 app 组合根能经 `submitBatch`/`TimeParticipant`/桥；结算 = 无命令、由结算内部写。**「其他模块经现有契约提交」这一列对所有行都是同一个答案：当前没有一个领域模块能直接提交任何 economy 命令**（原因见 §2）。

| 组件 | 有写命令？ | 命令/工具 | 谁能提交 | 其他模块经现有契约？ |
|---|---|---|---|---|
| `meta` | 仅 Seed | `economy.Seed`；worldgen/region.seed | GM 工具/令（Seed 有目标） | 否；只有 app seed 桥 |
| `industries` | 无编辑命令；Seed 追加 / Clear 删 / 候选采用物化 | `economy.Seed`、`economy.ClearRegion`、`EconomyEntrySettlement:868` | GM 工具；结算内部 | 否；候选物化是结算副作用 |
| `classes` | 有 | Seed / RegisterHousehold / RegisterGovernment / SetHouseholdLabor / MigrateHousehold / ClearRegion；另有 app 日注入 | GM；令（SetHouseholdLabor）；app 参与者 | 否；app 参与者可注投影（无命令契约） |
| `debtContracts` | 有 | Seed（只收空数组）/ UnitBorrow / UnitRepay / GmAdjust.forgiveDebt / ClearRegion | GM（Unit*/GmAdjust 为 GmOnly） | 否 |
| `flows` | 无（派生） | 结算写；ClearRegion 删；GmAdjust 明确拒直写 | 结算 | 否 |
| `allocations` | 仅 Seed | Seed / ClearRegion / 结算劳动队列 | GM（Seed）；结算 | 否 |
| `relations` | 有 | Seed / `GmAdjust.upsertProductionRelation` / ClearRegion / 组织阶段建默认关系 | GM | 否 |
| `markets` | 有（价格） | Seed / `economy.SetMarketPrice` / ClearRegion / 结算自适应定价 | GM（SetMarketPrice 无目标 ⇒ 实际只 GM） | 否 |
| `shipments` | 无（派生） | 结算写；ClearRegion **有意不清** | 结算 | 否 |
| `assetShares` | 有（份额） | Seed / `economy.TransferAssetShare` / ClearRegion / 组织与迁移 | GM（TransferAssetShare 无目标 ⇒ 实际只 GM） | 否 |
| `operatorConditions` | 无（派生） | `OperatorSettlement` 写；ClearRegion 删；GmAdjust 拒 | 结算 | 否 |
| `units` | 无直接命令 | Seed 追加 / ClearRegion 删 / 组织阶段与迁移写；无 `upsertProductionProcess` | GM（Seed） | 否 |
| `demands` | 有 | AddDemand / CancelDemand / UpdateDemand / ClearRegion / 结算消费 | GM；令仅 UpdateDemand 可裁 | 否 |
| `candidates` | 有 | RegisterCandidate / `GmAdjust.upsertCandidate` / Seed 不声明 | GM（RegisterCandidate 无目标；GmAdjust GmOnly） | 否 |
| `modes` | 有 | Seed 合并 / `GmAdjust.upsert...Mode / deactivate...Mode` / ClearRegion 不清 | GM（GmAdjust） | 否 |
| `classStructures` | 有 | Seed / `GmAdjust.upsertClassStructure` | GM | 否 |
| `classPositions` | 有 | Seed / `GmAdjust.upsertClassPosition` | GM | 否 |
| `classStandings` | 有 | Seed / SetHouseholdClass / SetHouseholdParticipation / RegisterGovernment(可选) / ClearRegion | GM；令（两条有目标可裁） | 否 |
| `productionOrganizations` | 有 | Seed / `GmAdjust.upsertProductionOrganization` / ClearRegion / 组织阶段 | GM；结算 | 否 |
| `assetRules` | 有 | Seed / `GmAdjust.upsertAssetRule` | GM | 否 |
| `governments` | 有 | Seed（先到者胜）/ RegisterGovernment（GM） / 结算铸币相关 | GM | 否；gov 模块本身无 handler（`simos-gov` handler 数 = 0），政策纯函数由 app 调 |
| `moneyIssuances` | 有（结算/Seed） | Seed 的 `INITIAL_ENDOWMENT` / 政府铸币结算 | 结算/GM seed | 否 |
| `pledges` | 有（间接） | Seed（只收空数组）/ TransferAssetShare / ClearRegion / 清算结算 | GM/结算 | 否 |
| `liquidationPolicies` | 有 | Seed / `GmAdjust.setLiquidationPolicy` | GM | 否 |
| `crisisSignals` | 有（间接） | Seed / app `stepper.putCrisisSignal`（GovDaily 信号）/ ClearRegion | app 参与者/GM | 否 |
| `modeTransitions` | 有 | Seed / `economy.SwitchMode`（GmOnly）/ ClearRegion / 迁移结算执行 | GM；结算 | 否 |
| `classShares` | 无直接命令 | Seed 空表 / ClearRegion 删 / 迁移结算写 | 结算 | 否 |
| `merchantFirms` | 无直接命令 | Seed / ClearRegion 随组织删 / MerchantSettlement 写 | 结算/GM seed | 否 |
| `periodicAdjustments` | 有 | Seed 透传 / Upsert/Remove（GmOnly）/ 执行器消费 | GM | 否 |

---

## 2. 现有跨模块写路径与缺口（第一部分问题 7~8）

### 2.1 其他模块经现有机制写 economy 的路径

**结论先行**：**当前没有任何领域模块能直接提交 economy 命令**；所有 economy 写入要么由 GM 直通、要么由决策令（仅少数有目标）、要么由 **app 组合根**在 `submitBatch`/`TimeParticipant` 里编排。现有可用机制如下：

| 机制 | 能覆盖什么 | 不能覆盖什么 |
|---|---|---|
| **`CommandEnvelope`（core 不透明载荷，ADR-1）** | 命令的通用形状 = `type + payloadJson`；Core 不 parse、不 instanceof（`simos-core/.../command/CommandEnvelope.java:6-19`；AGENTS §〇 `:82-83`）。app 可以用它提交任意已注册命令，包括 economy 的 20 条 | 领域模块 **无法** 使用：core 在模块边界外（AGENTS 模块表 `:50,62`；economy 的 enforcer 也禁 core，`simos-economy/pom.xml:85-95`）；handler 只能产出**一个** namespace 的 ChangeSet（`HandlerOutcome.Applied(ChangeSet)`；`CommandBus.namespaceOf(type)` `:920-922` 按 type 前缀切 namespace） |
| **app `CoreSimos.submitBatch`（同 branch + 同 expectedRevision，一批一条 revision）** | 一次推进/一次工具把跨 namespace（social/actor/economy/unit…）命令打成一条 revision，批内后一条看见前一条效果（`CommandBus.java:240-330`）。现有先例：`RegionClearDataTool`、`WorldgenInitializeTool`、`RegionSeedTool`、`GovCreateOfficeTool`、`ProvinceApplyTool`、`RaiseUnitTool`、`MoveCapitalPlan` 等 | 只是 **app API**，不是命令、不是工具；`simos.command.submitBatch` 工具尚未实现（代码 0 命中；未实现清单 `:87`）。领域模块自己看不到 core，用不了 |
| **app TimeParticipant / `WorldTimeProposal`** | 一次 advance 内一个参与者可提**多 namespace** 的 ChangeSet（`util/spi/WorldTimeProposal.java:20-40`）；现有唯一生产路径 = `PopulationEconomyTimeParticipant`（Shell 注册 `:800-803`），同时写 economy/social/actor/gov | 参与者类在 app；领域模块要参与必须由 app 注册/调用其纯函数。一个 tick 一条 revision，跨 tick 不可回退 |
| **outbox / bridge 先例** | ① economy→app→social：`ModeMigrationSettlement` 写瞬态 `EconomyPopulationTransfer` outbox，`PopulationEconomyTimeParticipant` 取走后 `MigrationSocialBridge.apply` 翻译成 Social 工单并回写经济行 delta（`EconomyPopulationTransfer.java:15-30`；`MigrationSocialBridge.java:26-36`）。② unit→app→economy：`MilitaryPayRuleBridge` 每天从 `ArmyFormation.militaryPayPolicy` 现算瞬态 `HouseholdPeriodicAdjustment` 规则，交给 P4a 无状态执行器；**不把政策复制进 EconomyData**（`MilitaryPayRuleBridge.java:33-46`）。③ economy→actor：`ProductionLedger`/`OwnershipBooks` 把产出条目落到 actor 账。④ actor 账扣除：`HouseholdStockDeduction`（economy-api typed record + `DeductionReason` 封闭词表）+ app `StockDeductionService`（`StockDeductionService.java:33-50`），税/行政俸禄/军俸共用；另有 GM-only 命令 `actor.DeductHouseholdStock` | outbox 是**单向**的（economy→app）；bridge 是 app 组合根里的**具体类**，不是通用契约；没有「经济编辑意图」的 typed record 供外部模块产出 |
| **`sd.RegisterEffect` + `SdCommandDrain`（延迟命令）** | GM 可在 sd 里登记 `Action.EnqueueUnitCommand`（type + payload），advance 成功后由 app `SdCommandDrain` 逐条 `core.submit` 落成**后续 revision**（`SdCommandDrain.java:24-34`）。可携带非 GmOnly、非 sd 的 economy 类型（`RegisterEffectHandler.java:158` 白名单 = `drainableCommandTypes`，Shell 构造 `:733-742`） | 提交后再提交、**跨 revision 不原子**（类注自己承认）；只支持 GM 登记效果（决策人桶没有 `sd.RegisterEffect` 工具），且 GmOnly 命令不能入队 |
| **Facet（只读）** | 跨模块可见性协议：模块实现 `FacetProvider`，app `FacetRegistry.register`（`util/facet/FacetProvider.java:12-20`；Shell 注册 `:826-827`）。MapSimos 对提供者完全不知情 | **只读**，不产生写入；当前只注册了 `UnitsHereFacet`/`PopulationFacet`，economy 无 Facet |
| **时间参与者（单/多切片）** | `TimeParticipant.simulateWorld` 纯函数提交提案；`WorldTimeProposal` 可多模块（`TimeParticipant.java:12-40`） | 只 app 能注册；参与者拿同一份 base，不能互相看到中间态；跨参与者冲突在 Core 的读写集合检查处才暴露 |

### 2.2 缺口（按现架构还差什么）

1. **economy 侧没有类型化命令/编辑契约**：所有 economy 命令都是「JSON 字符串 + handler 里 `requireText/optionalLong` 手工解析」；`EconomyGmAdjustments.project` 是唯一语义落点（好的先例），但它住在 `simos-economy` 实现模块，**不在 economy-api**，其他模块即使依赖 economy-api 也拿不到 typed 编辑意图。⇒ 外部模块无法类型安全地「表达我要改什么」；错误只能在 app 拼 JSON 时才暴露。
2. **产业模板（Industry）无编辑命令**：只有 Seed（追加、占格即整拒）和 ClearRegion（删除）⇒ 想改一个已有 `farm@hex` 的 `outputPerUnit`/`laborPerUnit`/`capacityPerUnit`/`cycleDays`，只能清区域重播（破坏性）或改 `EconomySeeder` 常量后重建。需要专门的 **`upsertIndustry`**（带版本/引用守卫，并明确「改模板后既有 units/assetShares/进度如何处置」——见 §5 待裁定）。
3. **生成参数没有升格为世界状态**：`GRAIN_OUTPUT_PER_MU`、`MU_PER_HEX` 等是编译期常量；`config/worldgen/*.json` 只在创世读；`StressPolicy` 阈值、`FEED/DEPRECIATION_PER_MILLE` 也是常量。GM 无法在运行期通过 revision 化命令调整「世界级生产参数」；其他模块更不可能。
4. **「其他模块的工具归模块管」尚未立项**：用户 2026-10-23 原话「APP 只是一个管前端包装、程序启动项的模块，理论上各个模块的工具归各个模块管，然后统一复写工具协议，为啥要 APP 管？」（`docs/.../2026-10-02-undeveloped-features.md:180-193`）。现状：app 里有 91 个 main 源文件 import `io.mosire.agentlib.tool.AgentTool`，**economy/gov/army/social/unit/map/sd/actor/calendar 领域模块 0 个工具类**（仅 core 1）。⇒ 要「其他模块能改 economy」，当前要么把编辑逻辑做成 app 桥，要么先补这个未立项的工具归属重构。
5. **跨 namespace 原子写只有 app 能表达**：一个 economy handler 只能写 economy namespace；「改生产规则的同时扣/还 actor 资产或改 social 家庭」必须由 app `submitBatch`/`TimeParticipant` 组合，或者新增「多 namespace 命令」原语（那会碰 ADR-1 与铁律 3/4，需用户裁定）。
6. **缺 app 级「模块编辑意图」执行器**：`WorldTimeProposal`/`submitBatch` 是通用机制，但没有「接收 economy-api typed intent → 转 `CommandEnvelope` → 同 revision 落盘」的通用执行器。`EconomyGmAdjustments.project` 可作为其语义核心，但需要上移到/镜像到 economy-api 才对外可用。
7. **权限模型未定**：新增的编辑命令是 `GmOnlyCommand`（只 GM/模块桥能提交）还是非 GmOnly + `CommandTargets`（决策令可嵌），要靠用户裁定权限单调性；不能顺手放宽。

---

## 3. 生产效率现状与插入点（第二部分 1~2）

### 3.1 现状盘点：economy 里与「规模/效率」有关的一切

**结论先行**：生产规模公式已经是「产能 × 状态系数（‰）× 最紧约束（劳动/投入）」，但**没有任何外部模块按 tick 影响的效率变量**；最接近的两个形态是内部的 `StressPolicy.plannedScalePerMille`（按市场/债务状态、按周期关账变）和 gov 的 `GovEfficiency`（按 tick、行政税用，不是生产）。

| 机制 | 位置 | 现状/公式 | 是否外部可写 |
|---|---|---|---|
| 产业配方 | `Industry` record `:121-138` | `capacityPerUnit`（每单位规模要多少资产）、`laborPerUnit`、`outputPerUnit`（商品单位/规模）、`cycleInputPerUnit`、`cycleDays`、`slots`、`allocation` | 只 Seed 时写入；无编辑口 |
| 实物产能 | `ProductionProcessBook.capacityScaleOf` `:94-131` | `min_k ⌊usableAssets[k] ÷ capacityPerUnit[k]⌋`；`usableAssets` = `Σ OwnershipStake{industry==unit.industry && operator==unit.operator}` `:45-55` | 由 `assetShares` 派生；`TransferAssetShare`/组织阶段/迁移可改份额 |
| 状态缩产系数 | `StressPolicy.plannedScalePerMille` `:78-92`；`ProductionProcessBook.plannedCapacityScaleOf` `:136-157` | `ACTIVE/TRIALING=1000‰、CONTRACTING=500‰、INDEBTED=250‰、SUSPENDED/EXITED…=0`；`plannedCapacityScale = capacityScale × stress‰ /1000` | 由 `OperatorCondition.status` 驱动（内部结算），GM 只能间接改状态 |
| 经营者状态 | `OperatorCondition` `:44-90` | 每 unit 的 `status` + 连续滞销/投入不足/债务压力计数 + 现金/债务/周期证据 | 结算 `OperatorSettlement` 写；无命令；`GmAdjust` 明确拒直写 |
| 生产单元 | `ProductionProcess` `:45-52` | `id, industry, operator, modeKey, progressDays, cycleLaborMilli, cycleInputUsedMilli` | Seed/组织/结算写；无直接命令 |
| 实际规模 | `EconomySettlement.scaleOf` `:7795-7812` | `scale = capacityScale × plannedPerMille/1000`；再 `min(⌊avgLaborMilli/laborPerUnit⌋, ⌊drawn_j/inputPerUnit_j⌋)` | 结算内部 |
| 产量 | `EconomySettlement.harvest` `:7182-7195`；类注 `:7119-7125` | `gross_j = scale × outputPerUnit_j × 1000`；`loss = gross × (FEED‰+DEPRECIATION‰)/1000`；`net = gross − loss`；`net` 按 relation 结算 | 结算内部；`FEED=0‰`/`DEPRECIATION=30‰` 是常量 |
| 组织规模 | `EconomyEnterpriseSettlement` `:560-643` | 由资产/劳动/投入三路算 target scale，必要时按 `AssetRule.rentRule` 拆租佃份额；`capacityScaleOf` 是产能路 | 结算内部；受 `assetRules`/`assetShares` 影响 |
| 规划读数 | `LaborQueueBook:186/396`、`MarketDemandBook:272`、`DebtCapacityBook:421`、`MarketSettlement:4213/4777`、`OperatorSettlement:382` | 都读 `plannedCapacityScaleOf`（含 stress 系数）来安排劳动/投入需求/信用/预期 | 结算内部 |
| 预期利润 | `ExpectedProfitBook:323/1124` | 候选利润 = 配方 + 市场价 + 需求 + 家户资产/劳动 + 关系/地租；规模读 `capacityScaleOf`（**注意：不是 `plannedCapacityScaleOf`**） | 纯函数只读；`D-024` 设计 |
| 家户劳动/参与率/需求 | `HouseholdEconomy.laborMilli/participationPerMille/naturalNeeds` | **app 每 tick 从 Social 展开注入**（`PopulationEconomyTimeParticipant` 日初调 `updateComposition`/`recomputeLaborBudgets`/`updateNaturalNeeds` `:449-453`；`EconomyDayStepper` `:312-327`） | 是「外部模块经 app 注入」的现成先例 |
| 创世效率 | `EconomySeeder.GRAIN_OUTPUT_PER_MU` 等 | 直接烘进 `Industry.outputPerUnit`/`cycleInputPerUnit`；2026-10-23 工作树把粮产与初始粮砍半（34/15），刻意造债务累积 | 无运行期写口 |
| 生产损耗/饥荒 | `EconomySettlement.FEED_PER_MILLE:283`、`DEPRECIATION_PER_MILLE:292`、`FAMINE_MORTALITY_PER_MILLE:409` | 常量，非状态 | 无写口 |
| gov 行政效率（对照） | `GovEfficiency` `:54-93` | 纯函数，按编制供给/辖区需求算 `efficiencyPerMille∈[0,1100]`；读数是 `GovOfficeState` 持久字段；税用 `attainable = assessed × efficiency‰ /1000`（`JurisdictionDailyTax:468-482`） | 由编制/需求间接决定；无直接写口 |

### 3.2 产量公式与「最自然插入点」候选

产量主路径只有一处（`EconomySettlement.harvest` `:7185-7187`）：

> `gross = scale × outputPerUnit × 1000`，其中 `scale = scaleOf(...)`（`:7799-7812`）已经是 `capacityScale × plannedPerMille/1000` 与劳动/投入三路取 min 之后的结果。

候选插入点（按语义分行）：

| 候选 | 位置 | 语义 | 影响面 |
|---|---|---|---|
| **A. 计划规模系数（pre-scale）** | `EconomySettlement.java:1412` 的 `StressPolicy.plannedScalePerMille(...)` 处，与外部效率相乘后再传入；或 `scaleOf` 里 `:7801-7802` 的 `plannedPerMille` | 「产能折减/开工率」：外部系数直接压计划规模，劳动与投入需求随之减少；`scaleOf` 后面的 min 照旧 | 只改 `harvest` 路径；若不同步改 `ProductionProcessBook.plannedCapacityScaleOf`，市场/labor/debt 规划读数会与实产不一致 |
| **B. 产量折减（post-scale）** | `EconomySettlement.java:7186` 的 `gross = scale * outputPerUnit * 1000` | 「天气/技艺」：同样投入同样的工，产出打折（`scale` 不变，劳动/投入照扣） | 只影响 `gross` 及由其派生的 `loss`/`net`；规划读数保持满计划 |
| **C. 规划读数系数** | `ProductionProcessBook.plannedCapacityScaleOf:136-157`（两个重载） | 让劳动队列、投入需求、信用额度、预期利润**提前知道**效率；不影响实际收获 | 覆盖 `LaborQueueBook/MarketDemandBook/DebtCapacityBook/MarketSettlement/OperatorSettlement`；**但 `EconomySettlement.scaleOf` 不走它**，必须 A/B 同步改，否则两套口径 |
| **D. 组织阶段系数** | `EconomyEnterpriseSettlement:587-643` | 让「是否组织/租佃多少/试产规模」也看效率 | 影响单位生成；与 A/B 的取舍要一致 |
| **E. 候选预期利润** | `ExpectedProfitBook:323/1124` | 让模式/候选选择考虑效率（例：坏天气下雇农制利润预期变化） | 影响 `EconomyEntrySettlement` 进入决策；不接也能跑，但「必然被外部决定」就不完整 |

**最自然的最小改动**：如果用户要的是「天气/文化导致同投入不同产出」= **B**（只在 `:7186` 乘一个千分系数，或把该系数塞进 `HarvestWork`）；如果用户要的是「生产资料/组织效率导致开工不足」= **A**（在 `:1412` 与 stress 合并，并同步 C）。无论选哪个，**C 的那条不对称（`scaleOf` 不走 `plannedCapacityScaleOf`）必须先显式处理**，否则同一效率在两个地方读数不同。

---

## 4. 外部决定机制的方案对比（第二部分 3~5）

### 4.1 `GovEfficiency` 是不是用户说的形态？——半是，半不是

- **像**：它是「另一个模块（gov）的纯函数 + 每 tick 现算 + 持久化为读数 + 被 app 消费」（`GovEfficiency.of` `:65-93`；`GovOfficeState.efficiencyPerMille` `GovOfficeState.java:40/54/77`；app `efficiencyTable` `PopulationEconomyTimeParticipant.java:844-883`；税用 `JurisdictionDailyTax.java:160-167,468-482`；`GovDaily.settle` 也调 `:225`）。这是**可复用形态模板**：纯函数 + per-mille + 边界校验 + 读数落盘/审计 + 缺值语义显式。
- **不像**：它不可被 GM 直接改，也不能被任意模块自由决定——它只由编制供给和辖区需求推出；而且它服务于税，不是生产。用户要的「生产效率变量」当前**不存在**（economy main 检索 `efficiency`/`效率` 均 0 命中，已用已知串自证工具有效）。

**其他同类先例**：

| 先例 | 形态 | 对本需求的借鉴 |
|---|---|---|
| `MarketReadout`（`simos-economy/.../time/MarketReadout.java:30-50`；app 装配 `MarketReadoutAssembly.java:33-50`） | 每 tick 只读派生、不进 EconomyData；读不到的项用 `unavailable` 具名，**不填 0** | 「读数」而非「状态」的写法；若要效率只是给 UI/审计看，用这一形态 |
| `ExpectedProfitBook`（`:60-70`） | 纯函数预期利润，`D-024` 要求现算、不得用「有没有既存读数」当门 | 外部因素进预期利润的落点 |
| `SocialProvisioning`（`simos-social/.../provisioning/SocialProvisioning.java:74-78`） | 持久组件：全局默认 + 家户覆盖；GM-only 命令 `social.SetDemandCoefficient`/`SetLaborCoefficient` 编辑；app 每 tick 展开注入 economy | **最接近「GM 可改 + 模块可注入」的现成系数表模板**（只是键是 `(AgeBracket,Sex,CommodityId)`，不是 unit） |
| `OperatorCondition` + `StressPolicy` | 每 unit 持久状态 + ‰ 系数 + 中性 1000 | 效率表可以复用「每 unit + ‰ + 中性 1000 + 具名状态/原因」的形状 |
| `MilitaryPayRuleBridge`（`:33-46`） | 政策唯一权威在 unit；app 每天现算瞬态规则，不写 EconomyData | 「外部模块决定、经济只消费瞬态输入」的写法；避免第二份真相 |

### 4.2 外部供给源接线现状

** `simos-calendar` 产出什么、谁能读**：

- 纯计算模块（无状态、无 Snapshot/Codec/存储；AGENTS §〇 `:58`）。核心读口：`ZonedSeasonSystem.seasonOf(long dayNumber, HexCoord at) → SeasonState(ClimatePhase phase, int dayOfSeason, int daysInSeason, double progress)`（`ZonedSeasonSystem.java:77-99`；`SeasonState.java:19-24`）。`progress` 的类注明确写「给农业/经济连续曲线（农忙/农闲）的钩子；本批只提供 SeasonState，不接任何结算/存储」（`SeasonState.java:20-21`）。
- `ClimatePhase` = `TemperateSeason`（春/夏/秋/冬）或 `TropicalSeason`（雨季/旱季）（`ClimatePhase.java:8-15`）；另有 `SolarTerm` 24 节气（`CalendarService.solarTermAt:176`）。
- app 侧装配：`CalendarService.load(coreSimos)`（`Shell.java:484`）读 `store_meta.calendar`（没有则用 `CalendarConfig.defaults()` 且不写盘）；读口 `CalendarService.config()/clock()/seasonAt(tick, at)`（`:151-181`）。GM 配置口 `simos.calendar.configure` **只 GM 桶、写 store_meta、不落 revision**（`CalendarConfigureTool.java:37` 类注）；`simos.calendar.info` 四桶共享（`SimosToolSource.java:832`）。
- 现有读季节的只有 `CalendarInfoTool` 和 GUI `ApiViews`（`CalendarService.java:181` 的调用点）；**economy 主源对 `calendar/climate/season/ZonedSeason/SolarTerm` 0 命中**（`git grep` exit 1；同工具先以 `HouseholdId` 命中 440 次自证；economy pom 也未声明 calendar 依赖，但 enforcer 注释说 calendar 不在禁列，`simos-economy/pom.xml:85`）。
- **文档/代码冲突**：`Shell.java:800` 注释写「C5：历法与人口/经济同取一份 CalendarService 快照（生产路径必须由 CalendarService.load 注入）」，但实际 `new PopulationEconomyTimeParticipant(config.mapId())`（`:802`）没有传 `CalendarService`；参与者内部四处用硬编码 `CalendarClock.julianDefault()`（`:452,566,1039,1072`）。⇒ 生产路径**没有**接 `CalendarService`，GM 改历法锚点不会影响生产/人口日期。这是本次调查发现的真实缺口（计划也承认：`docs/.../calendar-and-seasons-plan.md:177`「`EconomySeeder.GENESIS_CLOCK` 未接 `CalendarService`」；未实现清单 `:79`「季节结算钩子未接」）。

**社会/文化现状**：

- `SocialData` 8 个组件：`populations/cities/groups/households/populationEvents/provisioning/vitalRates/vitalRemainders`（`SocialData.java:78-86`）；**没有 culture/religion/民族/基层组织字段**。
- `HouseholdEconomy` 字段：`id,view,population,laborMilli,participationPerMille,money,debts,naturalNeeds,effectiveDemand,cycleNaturalNeedMilli`（`HouseholdEconomy.java:69-79`）——没有 culture。
- `HouseholdProfile` 有个自由 `Map<String,String> metadata`（`simos-social-api/.../HouseholdProfile.java:30-37`），但当前只是画像元数据，没有规则语义；`simos-social-api/.../lookup/HouseholdLookup.java:10-20` 是给未来 Culture/Religion 模块的只读 SPI 预留。
- 「家户文化规划」**存在但未实现**：`docs/superpowers/plans/2026-10-04-household-culture-and-community-effects-plan.md`（用户 2026-10-04 明示「只写进规划，不实现」；`:28`「当前没有文化、宗教、民族、基层组织这些字段」；方案 A/B/C 与 6 条待裁定见 `:49-51,86-91`）。未实现清单 `:45` 复核一致。

### 4.3 机制对比

| 机制 | 确定性 / 可 replay | 审计 | 谁能写 | 旧档策略 | 与铁律 2/3 的关系 | 覆盖/不能覆盖 |
|---|---|---|---|---|---|---|
| **a. 持久状态组件 + 命令写口**（如 `productionEfficiency` 表；键 unit/hex/industry 待定；值 `‰`） | 值随 revision 落盘 ⇒ replay/分叉逐值可复现；外生来源只影响「写入那一刻」 | 最好：revision diff + `reason` + 逐 tick 日志 | GM 命令直写；模块/桥经 app `submitBatch` 提交；可给/不给决策令 | 新组件缺键（旧档）⇒ 空表 = 1000‰ 中性，或按 Social 的 fail-closed 先例具名拒；需用户裁 | 完全走 Command→ChangeSet→Revision；仍只由 economy handler 写 economy 数据（铁律 2/3 满足） | 覆盖「GM/模块可改 + 每 tick 可波动 + 可审计」；需新增组件（约 10~12 个 main 文件 + 往返测试，参考 `periodicAdjustments` 的引用面） |
| **b. 每 tick 现算的只读输入**（app 参与者从 calendar/social 读，传 transient `EfficiencyInput` 给 `EconomyDayStepper`，像 `composition`） | 结算输入不落状态；依赖的外部源若本身是 revision 状态则确定性好；若是 `store_meta.calendar` 则改配置会改变「重新推进」结果 | 中：只能靠日志/`ProductionLedger` 读数；不查日志无法从状态反推「当时为什么这个效率」 | 模块的纯函数/pure provider 决定；GM 改**来源**（历法配置/文化标签）而非效率本身 | 无新组件 ⇒ 无旧档迁移；缺输入 = 中性或 fail-closed 由调用契约定 | 输出仍进 revision（结算结果落 change set）；输入不写状态，不违反铁律 2（状态写入仍只有结算/命令） | 覆盖「必然由外部模块按 tick 决定」；**不能**直接让 GM 改一个数（只能改来源），审计和 replay 较弱 |
| **c. SPI 贡献者**（economy-api 定义纯函数接口如 `ProductionEfficiencyContributor`；模块实现；app 装配期注册，run 时按 tick 调） | 与 b 相同；接口纯函数 + 显式输入可做到确定性 | 中；可在 app 汇总日志（哪个 contributor、什么输出） | 模块实现逻辑；GM 只能改贡献者读的源/配置；可作为 a 的「外部输入源」 | 无状态组件本身；若贡献者读旧档字段需迁移策略 | 只读贡献 + app/economy 写；符合铁律 3（economy 不反向依赖文化/日历模块） | 覆盖「模块拥有算法」；实现/装配更重；当前仓没有「注入 SPI 进结算器」的先例（只有 Facet/ResolverRegistry 等读侧注册） |
| **d. Facet / outbox / 延迟命令** | Facet 只读；outbox 是 app 私有的具体桥；`SdCommandDrain` 跨 revision 不原子 | Facet 无写；outbox 有日志；drain 有事件链但非原子 | Facet 任何模块注册；outbox 由 app 翻译；drain 由 GM 登记效果 | 不涉及 | outbox 需要 app 桥；drain 是「提交后再提交」 | 不能承担「每 tick 生产结算输入」；Facet 只能读；outbox 适合单向事实（如迁移），不适合双向可编辑规则 |

### 4.4 设计选项（可落地方案）

**方案 1（推荐，偏纪律）：持久效率表 + 命令写口 + app 每 tick 注入**

- 组件形状：`EconomyData` 新增一张 `Map<ProductionUnitId, ProductionEfficiency>`（或 `Map<EfficiencyKey, …>`，键待用户裁）；值建议 `long efficiencyPerMille`（1000 = 中性，整数、禁 double，与 `StressPolicy/GovEfficiency/SocialProvisioning` 同尺），可带 `source`（封闭枚举：WEATHER/CULTURE/MATERIAL/ORGANIZATION/GM…）、`reason`、可选 `expiresDay`（或 `validFrom/validTo`）。
- 键：MVP 建议 `ProductionUnitId`（最精确，与 `OperatorCondition` 同键；同 hex 多个 operator 不会互相覆盖）；需要更粗时退回 `(hex, industry kind)`。**不建议**直接按键 `ProductionModeId`（生产模式是制度目录，不是产业实例）。
- 默认值：缺行 = 1000‰（无效果），与 `StressPolicy`（condition 缺失 ⇒ 1000）和 `GovEfficiency`（无需求 = 1000）同口径；外部输入**缺失**时是「中性 + 一条具名 INFO」还是 fail-closed，由用户裁（见 §5）。
- 谁来写：① GM 命令（新增 `economy.SetProductionEfficiency`，或作为 `GmAdjust` 第 11 个 kind），GM 桶工具可预览/apply；② app 每 tick 从 calendar/social 读取后，经 `EconomyDayStepper.updateProductionEfficiency(...)` 注入（类似 `updateNaturalNeeds`，`:323-327`），再随结算落进 revision；③ 其他模块经 economy-api typed intent + app 执行器提交。
- 失败语义：坏载荷/越界/引用不存在 ⇒ 命令边界具名 `Rejected`；外部输入缺失 ⇒ 建议 1000 + INFO（或按用户要求 fail-closed）；效率 0 必须显式（不要用缺行表达停产，停产已有 `StressPolicy` 0）。
- 与「易修改」共存：GM 的显式 override 与每 tick 外部因子建议**乘算合并**并各自 clamp（例如 `effective = clamp(gmOverride × externalFactor)`），或 GM override 优先；无论哪种，都要在状态/日志里分开记，避免 GM 手改被下一 tick 静默覆盖。用户需裁定优先级。
- 插入：按用户口径选 A 或 B（§3.2）；若要规划读数一致，同时接 `ProductionProcessBook.plannedCapacityScaleOf` 和 `EconomySettlement.scaleOf`，并决定是否接 `ExpectedProfitBook`。

**方案 2（轻状态、强外部）：每 tick 现算的只读效率 + 来源可编辑**

- 不新增 `EconomyData` 组件；app 参与者在日初从 `CalendarService.seasonAt(day, hex)`（及未来 culture/social 源）现算一个 transient `Map<ProductionUnitId, ‰>`，通过新 `stepper.updateProductionEfficiency(...)` 传入；`settleOneDayInto` 把它带到 `harvest`/`scaleOf`，用于当天结算；结果只出现在 `ProductionLedger`/日志/`MarketReadout`-style 读数里。
- GM/模块要改的**不是效率数值**，而是源头：`simos.calendar.configure`（GM，store_meta，不落 revision）、未来的 culture 标签命令、或一个可选的小 override 表（混合方案 1）。
- 优点：不污染状态、不破旧档、避免「每 tick 写一张表」的 revision 噪声；与 `composition/laborBudgets/naturalNeeds` 的现成模式同形。
- 缺点：审计弱（要读日志）；若源头是 `store_meta`（不落 revision），「重新推进同一 revision」可能得到不同结果；GM 无法直接拍一个「今年减产 30‰」。
- 适用：天气/季节这种**宽覆盖、确定性曲线**；文化这种**慢变**源。

**方案 3（模块所有权最强）：economy-api SPI 贡献者 + app 装配**

- 在 economy-api 定义纯函数接口（输入 = day/hex/industry/unit + 只读世界视图，输出 = `Map<Key, ‰>` 或 typed entry 列表）；未来 `simos-culture`/`simos-calendar`（或 app 内的纯函数）实现；app 在 advance 前调用所有 contributor，合并后按方案 2 注入（或按方案 1 落状态）。
- 优点：显式「外部模块决定」契约；模块可以拥有自己的算法与数据；纯函数可测、可 replay（若输入可复现）；与用户 2026-10-23「工具归模块管」同方向。
- 缺点：当前没有「把 SPI 注入结算器」的先例，需要新装配缝；contributor 读的输入若来自 `store_meta`/外部系统，要单独处理确定性；新增接口/装配文件较多。
- 适用：文化/宗教/基层组织这类未来独立模块；建议在方案 1/2 的接口稳定后再引入，不要第一版就做。

**推荐组合**：**方案 1 做状态与 GM 写口 + 方案 2 做每 tick 外部注入**；两者用一个 `‰` 的合并规则统一（外部因子每 tick 注入、可落状态/可只读；GM override 持久）。方案 3 作为后续文化模块的扩展点。

---

## 5. 待用户裁定清单（逐条：问题、选项、我的建议）

1. **效率乘在哪一步？** 选项：① A pre-scale（产能/开工率：少用劳动/投入）；② B post-scale（同投入、低产：天气/技艺）；③ A+B 两个独立系数；④ 仅规划读数不影响实产。**建议**：先按 B 做「天气/文化」单一系数；若还要表达「生产资料不足」，另加 A 或把 A 交给已有 `capacityScaleOf`。
2. **键/粒度？** 选项：① `ProductionUnitId`；② `(hex, industry kind)`；③ `IndustryId`（hex+kind，正好是产业模板身份）；④ 家户 `HouseholdId`；⑤ 全局单一。**建议**：MVP 用 `ProductionUnitId`（与 stress/operator 同键，最不失真）；再加「unit → hex → industry kind → 全局」的 fallback 链，避免每格每产业都要写满。
3. **量纲与边界？** 选项：① `‰` 整数 0..1000（只减不增）；② 0..2000（允许加成）；③ 允许负值（用符号表达反向）。**建议**：`long ‰`，默认 [0,1000]；如要加成另开 `bonusPerMille` 或上限 1100（照 `GovEfficiency` 的 1000+100）。**不要**用负号（与 `Transfer`/`HouseholdStockDeduction` 的「方向不用负号」纪律冲突）。
4. **缺省/缺失语义？** 选项：① 缺行 = 1000（中性）；② 缺行 = 世界基础值（如 800）；③ 活跃经济缺外部输入 ⇒ fail-closed；④ 旧档缺键 = 拒读/必须重播。**建议**：缺行 = 1000；坏值/越界 = 命令边界拒；「活跃经济但 app 没发布外部因子」= 1000 + INFO（若用户要「必然决定」则可改 fail-closed，但要有默认档/迁移策略）。
5. **天气/波动是确定性还是随机？** 选项：① 只用 `SeasonState.progress` 的确定性曲线（可 replay、不需新状态）；② 每 tick 伪随机天气（需要世界 seed + 决定 seed 存哪、如何按 `(hex,tick)` 派生、是否落 revision）；③ 事件驱动（GM/剧情给一段修正）。**建议**：第一版用 ①（`progress` 就是为此留的钩子，`SeasonState.java:20-21`）；随机天气另开批次并先定 seed 状态。
6. **文化数据形状？** 选项（照 2026-10-04 规划 `:49-51`）：A. `EconomyData` 新增 `HouseholdTraits`；B. actor 侧属性、economy 读 actor；C. 基层组织做成 `ActorKind.ORGANIZATION` + membership，效果派生；D. 未来独立 culture 模块的关系表（`HouseholdLookup` SPI 已留）。**建议**：先按 D/独立模块规划，不要塞进 `HouseholdEconomy`；本批（效率变量）只定义「外部因子接口」，文化字段另裁。
7. **GM override 与外部每 tick 因子的优先级？** 选项：① 乘算合并；② GM override 优先（外部因子被忽略）；③ GM override 只在没有外部因子时生效；④ 外部因子是唯一权威、GM 只能改来源。**建议**：乘算合并 + 两边分别 clamp + 日志分列，保证 GM 的显式修改不被静默覆盖。
8. **产业模板要不要可编辑？** 选项：① 保持创世/重建 only；② 新增 `upsertIndustry`（版本/引用守卫；既有 `units`/`assetShares`/进度如何处置要另定）；③ 把 `Industry` 拆成「不可变技术身份 + 可变世界参数」两层。**建议**：若「其他模块也要改产量常数」是硬需求，必须做 ②；但先按「模板变更 = 新增版本，旧 unit 继续引用旧版本 / 新 unit 用新版本」的口径设计（照 P7 `ProductionMode.version` 的先例），不要原地改一行让所有既有 unit 静默变值。
9. **生成参数升格为世界状态？** 选项：① 保持编译期常量 + 重建；② 新增 `WorldRules`/`EconomyGenParams` 持久组件 + GM 命令（Seed 时读取，运行期是否即时生效另定）；③ 只把少数字面量（粮产、初始粮、损耗）升格，其余保持。**建议**：先把「运行期是否即时生效」定死——即时生效会改变已有 unit 的产出，是数值行为大改；建议做成「引用的版本/参数集」，新 seed 生效、旧 unit 保持引用。
10. **「其他模块的工具归模块管」要不要现在立项？** 选项：① 先做 economy-api typed intent + app 执行器（不改工具归属）；② 同步做用户 2026-10-23 的工具归属重构（领域模块拥有工具、统一协议）；③ 只做 GM 面，其他模块暂不写。**建议**：本批只做 ①；② 单独立项（它牵涉所有模块、enforcer、审批、迁移，用户原话见 `docs/.../2026-10-02-undeveloped-features.md:180-193`）。

**机械实现（不需要用户裁定，方案定了就能做）**：新增组件/命令的 `EconomyData`/`EconomyChangeSet`/`EconomyCodec`/`EconomyStateBuilder`/`EconomyClearRegion` 透传/现有 Seed 透传、`EconomyGmAdjustments` 新 kind 与 handler 形状校验、`CatalogTool` PAYLOAD_HINTS、`EconomyAdjustTool` schema/描述、`EconomyDayStepper` 注入方法、日志（新状态写口 INFO、判据 DEBUG、逐笔 TRACE，照 AGENTS §一.9）、旧档缺键策略实现、以及 `EconomyRoundTripTest`/boundary tests 的补测。

---

## 6. 建议批次

> 遵守 AGENTS §一.5/§一.8/§一.10：一个责任区一个写代码代理、只写生产代码到编译过、测试最后统一；每批开工前先有约束设计书（§一.8），文中含用户原话附录（§一.8.1）。

| 批 | 内容 | 依赖 | 大概动哪些模块 | 验收要点 |
|---|---|---|---|---|
| **B0. 裁定与约束设计书**（不写代码） | 把 §5 的 10 条逐条裁定；写 `docs/superpowers/specs/<date>-production-efficiency.md`：用户原话附录、效率口径/key/量纲/默认/优先级/天气确定性、与 `modes` vs `industries` 的边界 | 用户当场裁定 | `docs/**`（只文档） | 文档能回答外部契约/数据流/失败语义；不含占位 |
| **B1. economy 状态 + 命令 + 写口** | 新增效率组件 + typed entry（或 `GmAdjust` 第 11 kind，或独立 `economy.SetProductionEfficiency`）；守卫、幂等、命令边界错误；`EconomyDayStepper` 注入方法；`ClearRegion`/Seed 透传；读口进 `ApiViews`/`MarketReadout` | B0 | `simos-economy`（`EconomyData/ChangeSet/Codec/StateBuilder/spi/time`）、`simos-economy-api`（typed record/enum）、`simos-app`（`EconomyAdjustTool`/`CatalogTool`/`ApiViews`） | 编译过；`EconomyRoundTripTest` 后续补；旧档缺键逐值策略落地 |
| **B2. app 每 tick 外部注入（calendar 优先，culture 占位）** | 把 `CalendarService` 真正注入 `PopulationEconomyTimeParticipant`（修 `Shell:800` 注释/构造器；同一实例，像 GUI 读口那样）；用 `seasonAt(day,hex)` + `progress` 现算确定性因子；调 `stepper.updateProductionEfficiency`；每 tick 一条具名日志；unit/hex 聚合规则照 B0 | B0、B1（若 B1 是持久表）；若只做方案 2 可只依赖 B0 | `simos-app`（`time/PopulationEconomyTimeParticipant`、`Shell`、可能 `CalendarService`）、`simos-calendar` 只读 | 编译过；与已有 `updateNaturalNeeds/laborBudgets` 同日序；`CalendarClock.julianDefault` 的遗留点有具名清单 |
| **B3. 产业模板编辑命令**（若用户裁定要） | `upsertIndustry`（或 `GmAdjust.upsertIndustry`）：`capacityPerUnit/laborPerUnit/outputPerUnit/cycleInputPerUnit/cycleDays/allocation` 的版本化编辑；引用守卫（哪些 unit/assetShare/affects 现有流程）；旧 unit 绑定策略 | B0（第 8 条裁定） | `simos-economy`（`Industry`/`EconomyGmAdjustments`/handler）、`simos-app`（工具/schema/catalog） | 编译过；负向用例清单（引用不存在/版本倒退/改模板后旧 unit 行为逐值可解释） |
| **B4. 生成参数升格**（若用户裁定要） | 新增 `WorldRules`/`EconomyGenParams` 持久组件（或明确「genesis-only + 重建」政策）；GM 命令；Seed 读取顺序 | B0（第 9 条裁定）、B1 的组件添加经验 | `simos-economy`、`simos-app`（EconomySeeder/Worldgen/RegionSeed/工具） | 编译过；旧档/新档参数来源可辨；「运行期即时生效 vs 下一 seed 生效」判据 |
| **B5. 模块工具归属重构**（用户 2026-10-23 设计意图，未立项） | 按 `docs/.../undeveloped-features.md:180-193` 单独立项：工具协议归属、enforcer、审批、迁移 | B0 第 10 条裁定 | 全模块 | 不在本经济批次内混做 |
| **B6. 统一测试与验收**（最后） | 按 B0 判据逐条测：往返、边界、GM/模块权限不能放大、缺值语义、replay/日志、旧档；四类关键项变异自证（守恒/不丢失/静默 0/断粮） | 全部代码批 | 测试与文档 | 全仓 `clean verify` + 真实行为证据（先确认本仓无 Maven 冲突） |

依赖关系：**B0 是所有代码批的前置**；B1 是 B2 的接口前置（若 B2 选纯 transient，则 B1 可只做 typed contract 不落状态）；B3/B4 与 B1 都会碰 `EconomyGmAdjustments`/`EconomyAdjustTool`，建议**串行**或严格分文件所有权；B5 不得与经济数值批并行改同一批工具文件。

---

## 7. 我没核到 / 不确定的（自证与边界）

- **没有跑任何 Maven/测试/运行时**（硬性纪律）。所有结论来自源码静态阅读与 `git grep`；因此「循环次数、实际读数、效率改动后的数值行为」都未验证。
- **工具自证**：报告中所有「0 命中」都做了已知串自证：economy main 搜 `calendar/climate/season` = 0，同写法下 `HouseholdId` 在 `EconomySettlement.java` 命中 440 次；搜 `WorldRules` = 0，同文件 `EconomyMeta` 有命中；搜 `ProductionEfficiency/EfficiencyTable` = 0，同目录 `GovEfficiency` 有命中；economy-api 搜命令 record/handler/builder = 0，同命令 `public record` 命中；`withIndustries` main 调用点只有 `ClearRegion:375`，同 grep 命中定义与测试调用。**一次失误已修正**：早期用过 `-- 'simos-*/src/main/java'` 这类 glob pathspec 导致静默 0 命中，后全部改为显式目录路径重跑。
- **工作树 dirty**：`EconomySeeder.java` 在我开始前就是 ` M`（用户 2026-10-23 对半砍产量/初始粮的改动未提交）；报告中的 34/15 是工作树值，HEAD 是 67/30。我没有改它；`git status --short` 结束时仍只有这一处。
- **文档/代码不一致（以代码为准）**：① `Shell.java:800` 注释称 CalendarService 已注入生产路径，实际参与者用 `CalendarClock.julianDefault()`（`:452/566/1039/1072`）；② `EconomyData` 类注「30/31 个组件」与 record 实际 29 个字段不一致；③ `EconomySettlement` 类注仍写「亩产 67 粮/亩」，工作树常量已是 34；④ `2026-10-02-calendar-and-seasons-plan.md:177` 自己也记了 `GENESIS_CLOCK` 未接。
- **不确定项**：① `economy.Seed` 首次播种是否会清掉「meta 空但已有 GM 写的 demands/markets」——按 `EconomyChangeSet.between(base, seeded)` 语义我判断会，但没有跑真实用例验证；② `GmAdjust.upsertProductionOrganization` 与 `EconomyEnterpriseSettlement` 自动组织的交互边界（谁能覆盖谁）我只读了类注与写口，没有逐路径核完；③ 决策令嵌入 `economy.Seed` 的实际可达面（`CommandTargets` 只说逐格 economy 路径，具体 `ResourceScopeMap` 是否把 economy 路径纳入辖区）我只核到 `AdjudicateTickTool` 的通用闸，没有逐个决策人围栏跑过；④ `MarketReadout/ExpectedProfitBook` 是否还有第三处「效率类」读取点，我用 `efficiency/效率` 全 main 检索为 0，但注释里可能有同义措辞（如「缩产」「按比例」）未穷尽。
- **未读/未核**：`AGENTS.md` §四及之后的大部分历史台账我只按需读了与问题相关的节；`.superpowers/sdd/**` 未展开；`ProductionSettlement`/`MarketSettlement` 的逐行公式只读到与规模相关处，没有逐条核对租/工资结算的数值细节；「生产模式/产业」的 GUI 编辑面（无写端点）只核到 `GuiServer` 路径清单，没有核前端 JS。