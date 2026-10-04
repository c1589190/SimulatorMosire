# 预期利润、需求与生产方式进入（真实运行时修复设计）

> 状态：**已设计、待实现**（2026-10-06）
> 规则依据：`AGENTS.md` §一.5（一个阶段一个写代码代理，测试最后单独代理）、§一.8（派实现架构子代理前必须先保存完整架构设计文档并在派单书里复述）。
> 用户裁定来源（本会话原话）：
>
> - 「单个市场内，单个家户对于单个生产方式没有预期利润吗？能不能算？以及什么叫没有需求，给单个家户、生产方式算一下相关需求啊；这个拍板到底是 什么意思」
> - 更早的 D-022（生产方式只通过转移/新建表示）与 D-023（任意介质偿还、资产随迁、全部币种随迁、流民不得主动招募）。
>
> 关联文档：
> - `docs/superpowers/specs/2026-10-02-architecture-design-source.md` D-024（本设计对应的正式裁定）
> - `docs/superpowers/specs/2026-10-06-seven-hex-full-chain-merchant-migration.md`（本设计的 v4 追加）
> - `docs/superpowers/specs/2026-10-05-city-merchant-urbanization.md`
> - `docs/superpowers/specs/2026-10-05-tenancy-wage-production-modes.md`

## 0. 一句话目标

把"候选生产方式必须已有真实利润读数"改成"**对每个家户 × 候选生产方式 × 市场，按配方 + 市场价 + 真实需求，算预期净收益/劳动**"；
在此基础上修复真实 12hex 的城乡/工商/商户生产方式进入，再重跑 3650 tick 验收。

## 1. 需求来源、目标与非目标

### 1.1 用户已确认的判断

1. **可以且必须算预期利润**：一个家户考虑一个它当前没有从事的生产方式时，预期利润不应依赖"现在有没有别人在跑这个 mode"。
2. **必须算需求**：不能只算"产出 × 价格"；同一市场内，可销售量受其他家户的自然需求、生产引致需求、库存/在途/既有供给和购买力约束。
3. **不得要求用户"拍板"初始比例**：城镇阶层→城市岗位的初始映射是初值/派生量；选择由预期利润和需求驱动。
4. **"没有需求"不是理由**：需求可以按自然需求、生产投入需求、路线运输需求和上一轮未成交买方缺口算出来。

### 1.2 目标

1. 新增 `MarketDemandBook`：逐（市场格或市场区，商品）算出**自然需求、生产引致需求、未成交买方需求、库存、在途、可寻址需求**。
2. 新增 `ExpectedProfitBook`：逐（家户，候选 mode，hex，产业模板）算出**可行规模、可售量、预期收入、投入成本、劳动成本、地租/工资、净收益、单位劳动净收益**。
3. `ModeMigrationPolicy` 的候选门槛从 `OrganizationProfitBook.hasReading` / `labor > 0` 改为 `ExpectedProfitBook` 的预期单位劳动净收益；真实利润账只保留为事后对账/读数校准，不再当作"能不能进入这个 mode"的判据。
4. `ModeMigrationPolicy.expectedProfit`（A 规则当前户读数）改用同一个计算器，修正现在的两处口径错误：
   - 现在把 `output × 价格` 全算成收入，**没有需求上限**；
   - 现在周期产出/投入与"千分劳动/日"的劳动口径直接混用，未按 `cycleDays` 对齐。
5. 真实 12hex 播种按 `(residence, slot)` 映射生产方式：
   - 农村：`poor_peasant → wage_farm.wage_laborer`、`middle_peasant/rich_peasant → family_farm.family_farmer`、`landlord → tenancy_fixed_kind.landlord`（保持现状）；
   - 城镇：`poor_peasant → handicraft_workshop.artisan`、`middle_peasant → handicraft_workshop.workshop_owner`、`rich_peasant → merchant.self_employed`、`landlord → merchant.principal`；
   - 每格另加一条 `displaced` 家户行（`displaced.laborer`，人口按具名 GM 默认比例从该格池人口中切出；D-023：无自动劳动配额、无自动组织）。
6. 真实 12hex 补齐城市/商户真实链条：
   - 每个有城镇人口的格增加 `trade` 产业模板（`capacityPerUnit = {CATTLE: 1}`、无商品产出、`laborPerUnit = 1000`）；
   - 每个城市格播种至少一个 `MerchantFirm`（组织 id 与自动组织将创建的组织 id 一致）；
   - 为商号播种 `CATTLE`（必要时再加 `SHIP`）`AssetShare`，让 merchant mode 有真实运力；
   - `PRODUCTION_RUNTIME_ASSET_KINDS` 增加 `SHIP`/`CATTLE`。
7. 运行时版本从 `seven-hex-v1` 升为 `seven-hex-v2`：旧档/旧载荷一律作废，不做兼容迁移（D-022/D-023 既定口径）。
8. 重跑真实 12hex 3650 tick，按本文件 §8 的判据出证据。

### 1.3 非目标

1. **不做社会侧 P8/P9 城乡迁移接线**（`PopulationMigrationPlanner` 仍默认 no-op）。本轮先让经济侧的 mode 迁移与城市/商户真实链条跑起来；社会化城乡批次拆分/合并留下一批。
2. **不做动态价格**（`MARKET_ADAPTIVE_PRICING_ENABLED` 保持 false；预期利润使用当前参考价与 bid/ask 限价）。
3. **不做城市土地/城区容量/慢速扩建**（`CityLand` 仍不接日循环）。
4. **不做 GOV/商税/完整城市财政**。
5. **不做 FX、不做旧档兼容、不做 `DebtUnit` 分账**（D-023 既定）。
6. **不做商人买低卖高投机**；商人本轮仍是承运服务 + 运费收入 + porter 工资 + upkeep。
7. **不做订单簿级精确竞争**：本轮用"未成交买方需求 + 基本需求残差"作可寻址需求上限，不做多新进入者之间的逐笔重新撮合。

## 2. 现状与目标

| 维度 | 现状 | 目标 |
|---|---|---|
| 候选 mode 评估 | `ModeMigrationPolicy.buildTargets` 硬判 `book.hasReading(mode,hex) && book.labor>0`；没组织/没读数 ⇒ 权重恒 0 | `ExpectedProfitBook.prospect(h, mode, hex)`；预期单位劳动净收益 > 当前户才进权重 |
| 真实利润账用途 | 候选门槛 + A 规则 | 只做事后对账/校准；A 规则改读预期计算器 |
| 当前户预期利润 | `gross = output × price`，无需求上限；周期产出/投入与日劳动口径混用 | 同一计算器：需求上限 + `cycleDays` 对齐 + 地租/工资/分成 |
| 需求 | 候选评估里不计算 | `MarketDemandBook`：自然需求 + 引致需求 + 未成交买方 + 库存/在途 |
| 城乡 mode 映射 | `productionRuntimePositionId(slot)` 不看 residence，城镇四行被种成农业 mode | `(residence, slot)` 裁决；城镇映射到手工业/商户 |
| 商户 | `merchantFirms` 空；无 `trade` 产业；无 `SHIP/CATTLE` | 商号 + trade unit + CATTLE/SHIP 资产；运费/工资/upkeep 真实进账 |
| 流民 | 初始 0；只在 A 规则 fallback 特判 | 初始按具名比例播种；A 规则 fallback 保留 |
| 真实 12hex 3650 | 停在 `urban 875→9`、`displacedPeak=0`、`freightPaid=0`、D-022 位置下落断言 | 城镇不崩、merchant/handicraft mode 有人、freight>0、displaced>0、D-022 mode 改写 = 0 |
| 运行时版本 | `seven-hex-v1` | `seven-hex-v2`；seeder 写入新版本，旧载荷不可混入 |

## 3. 组件与数据模型

### 3.1 `MarketDemandBook`（新增，`simos-economy` 主包，纯函数）

位置：`simos-economy/src/main/java/io/mosire/simos/economy/time/MarketDemandBook.java`。

```text
record Demand(
    HexCoord marketHex,
    CommodityId commodity,
    long consumerNeedMilli,      // 视界内该市场区自然需求（粮/布）
    long inputNeedMilli,         // 视界内现有 unit + 候选 unit 的配方引致需求（纤维/工具等）
    long unfilledBuyerMilli,     // 上一轮 MarketReport 买方侧未成交量
    long stockMilli,             // 该市场区家户 + 经营者库存
    long inTransitMilli,         // 到该市场区的在途
    long externalDemandMilli,    // 路线需求 / 邻区缺口（跨区可卖）
    long addressableMilli,       // 见下
    List<String> evidence)       // 具名来源：fundamental / lastReport / route / mixed

record Book(Map<HexCoord, Map<CommodityId, Demand>> byMarketHex, long horizonDays, long day)
```

聚合口径（同一市场区内；`MarketTopology.regionOf(hex)` 给市场区，退化时一个 hex 一个区）：

```text
consumerNeed[c]  = Σ_{该区家户} row.naturalNeeds[c] × horizonDays
inputNeed[c]     = Σ_{该区现有 unit} industry.recipe.inputPerUnit[c] × plannedScale_u × ceil(horizonDays / cycleDays)
unfilledBuyer[c] = Σ_{上一轮 MarketReport.unfilled} (buyerSide && commodity==c && regionOf(hex)==该区).quantity
stock[c]         = Σ 该区家户 + 经营者 GoodsAccount[c]
inTransit[c]     = Σ 到该区、commodity==c、arrivalTick > day 的 ShipmentBatch 数量
externalDemand[c]= Σ MarketReport.routes (from 该区, commodity==c).demandMilli
addressable[c]   = max(0, max(unfilledBuyer[c], consumerNeed[c] + inputNeed[c] + externalDemand[c] − stock[c] − inTransit[c]))
```

规则：
1. **上一层报告优先**：只要上一轮有买方未成交，它就是"已经被真实价格和购买力筛过"的需求，不再拿自然需求重复加总；两者取 `max` 而不是相加，防止重复。
2. **没有报告/报告为空**：回退到 `consumerNeed + inputNeed − stock − inTransit`，`evidence = fundamental`。
3. **自给部分**：一个生产者自己消费掉的产出不进入可售量，但按 bid 价折算成"自给收益"计入收入，保证自给与出售可比。
4. **输入需求**：现有 unit 的引致需求按 `plannedCapacityScaleOf` 折；没有规模的候选 unit 由 `ExpectedProfitBook` 单独加自己的投入需求（不写回 demand book）。
5. 纯函数、确定性：所有遍历按 hex / household / unit / commodity 的规范序；无随机、无时钟。

### 3.2 `ExpectedProfitBook`（新增，`simos-economy` 主包，纯函数）

位置：`simos-economy/src/main/java/io/mosire/simos/economy/time/ExpectedProfitBook.java`。

```text
record Prospect(
    HouseholdId household,
    ProductionModeId modeId,
    ClassPositionId positionId,
    HexCoord hex,
    Optional<IndustryId> industryId,
    long feasibleScale,
    Map<CommodityId, Long> plannedOutputMilli,
    Map<CommodityId, Long> selfConsumedMilli,
    Map<CommodityId, Long> sellableMilli,
    long revenueMilli,          // 卖出收入 + 自给按 bid 价的折算
    long inputCostMilli,
    long laborCostMilli,
    long rentMilli,
    long netMilli,
    long laborNeedMilli,        // 视界内总劳动（千分劳动·日）
    long netPerLaborScaled,     // floor(net × 1_000_000 / max(1, laborNeed))
    boolean demandCapped,
    boolean feasible,
    String reason)
```

`prospect(...)` 入参（只读，不写状态）：
`EconomyData base`、`HouseholdId household`、`ProductionModeId modeId`、`HexCoord hex`、`Industry industry`（可为空）、
`Market market`、`MarketDemandBook.Book demand`、`Map<AssetShareId, AssetShare> assetShares`、
`AccountSession accounts`、`Map<ProductionUnitId, ProductionUnit> units`、
`Map<ProductionUnitId, ProductionRelation> relations`、`Map<ProductionOrganizationId, MerchantFirm> merchantFirms`、
`MarketTopology topology`、`long day`。

#### 3.2.1 规模（`feasibleScale`）

```text
assetScale = min_k floor(可用资产[k] / capacityPerUnit[k])          // 自有自营资产 + 同格可租闲置（AssetRule/RentRule 允许）
laborScale = floor(row.participationAdjustedLaborMilli() / laborPerUnit)
scale      = min(assetScale, laborScale)
```

★ **量纲说明（D-024 实施修正）**：`ClassRow.participationAdjustedLaborMilli()` 是**千分劳动/日**，`laborPerUnit` 是**千分劳动/日/单位规模**。
"视界内劳动 / 视界内单位劳动" = `(row × horizonDays) / (laborPerUnit × horizonDays)` = `row / laborPerUnit`。
实现取后者这个等价形式；测试代理手算时按此口径，不要再把它当成"漏乘 horizonDays"。

- `可用资产` 的取法沿用 `ModeMigrationPolicy` 现有闲置判据（`isIdleShare` + `claimedByOrganizations` 排除），
  新进入者的份额按执行期同样的 `reserveIdleAssetsFor` 规则；**没有资产又租不到 ⇒ `feasible=false, scale=0`**，不凭空造资产。
- 对 `wage_laborer` / `porter` 位置：产出归雇主，规模取"目标组织可提供的劳动缺口"；没有目标组织 ⇒ 用同一格现有组织缺口的合计；
  全部为 0 ⇒ `feasible=false`。
- 对 `landlord` / `principal` 这类不直接生产的纯收租/本金位置：不进入候选生产位置（`shouldProduce` 同 E2 口径）。

#### 3.2.2 产出、需求上限与可售量

```text
plannedOutput[c] = outputPerUnit[c] × scale                 // 商品单位 × scale；换算成毫单位后内部统一
selfConsumed[c]  = min(plannedOutput[c], row 的自然需求(c) × horizonDays)
remainingNeed[c] = max(0, demand.addressable(hex, c) − selfConsumed[c])
sellable[c]      = min(plannedOutput[c] − selfConsumed[c], remainingNeed[c])
```

- `horizonDays = industry.cycleDays`（一次产出周期）作为默认视界；调用方可传更长视界，但本轮固定用 `cycleDays`。
- `demandCapped = Σ sellable < Σ (plannedOutput − selfConsumed)`。
- 没有市场/没有价格：`sellable=0`，收入只算自给折算；`reason` 带 `NO_MARKET` / `NO_PRICE`。
- **不做订单簿竞争重排**：`addressable` 已经扣除库存/在途；同一轮多个新进入者可能重复使用同一份未成交需求——这是本批的具名近似，见 §9。

#### 3.2.3 收入与成本

```text
bidPrice(c) = market.bidPriceOf(c)        // 卖侧保守价
askPrice(c) = market.askPriceOf(c)        // 买侧保守价
revenue     = Σ (sellable[c] × bidPrice(c) + selfConsumed[c] × bidPrice(c))
inputCost   = Σ (inputPerUnit[c] × scale × askPrice(c) / MILLI_PER_COMMODITY_UNIT)   // 毫商品 × 毫银/商品单位
laborNeed   = laborPerUnit × scale × horizonDays                                     // 千分劳动·日
laborCost   = Σ laborNeed × subsistenceMilliPerLabor / 1000 × grainPrice / 1000       // 毫粮 → 毫银
rent        = ProducerCostBook.assetRentPerUnit(...) × scale                          // 固定租/分成租按关系/AssetRule
net         = revenue − inputCost − laborCost − rent
```

量纲固定：
- 数量一律毫单位；价格是 **毫计价货币 / 1 商品单位**（与 `Market` 注释一致，`/1000` 换毫商品）。
- `laborPerUnit` 是 **千分劳动/日/单位规模**；周期劳动必须乘 `horizonDays`。
- 分成分成、固定工资：从 `ProductionRelation.rules()` / `RegimeRelations.defaultRelation(...)` 取；没有显式关系时按 `RegimeRelations` 的制度默认。
- 收入/成本内部用放大刻度（至少 `10^6`）计算，最后再向下取整；避免劳动成本在毫银下被截成 0（沿用 `OrganizationProfitBook.PER_LABOR_SCALE` 与 `ProducerCostBook.ESTIMATE_SCALE` 的既有教训）。

#### 3.2.4 各位置口径

| position 类型 | 预期收入 | 预期成本 |
|---|---|---|
| `SELF_SUBSISTENCE` / `OWNER` + `SURPLUS_RECEIVER` | 上式的 revenue | input + labor + rent |
| `TENANCY`（佃农） | 分成/固定租后的产出（按 `RentRule` / `ProductionRelation`） | input + labor |
| `WAGE_EARNER`（雇工/porter） | 工资规则（`FIXED_MONEY_WAGE` / `FIXED_IN_KIND_PER_LABOR`） × labor | 自身无投入；labor = 自身劳动 |
| `DIRECT_LABORER` / `PROVIDER` | 同上，按关系或制度默认给养折算 | 自身无投入 |
| `DEPENDENT` / `laborRole NONE` | 不组织、不生产；`feasible=false` | — |

#### 3.2.5 `merchant` mode 专用

`ExpectedProfitBook` 对 `DefaultProductionModes.MERCHANT` 走独立分支，不套商品配方收入：

```text
capacityScale     = 商号 capacityPerRound（已有 MerchantFirm）或 该户/同格闲置 SHIP/CATTLE 资产折算的运力
剩余路线需求      = Σ topology 可服务 lane 的 (RouteUsage.demandMilli − RouteUsage.used)
externalDemand    = Σ MarketReport.unfilled（buyerSide && reason.logistics()）到 home 区
可承运量          = min(capacityScale, max(剩余路线需求, externalDemand))
运费收入          = 可承运量 × topology.freightPerMilleBetween(lane) 折毫银
成本              = MerchantPolicy.upkeepPerDistrictUse() × tier 城区当量
                  + porter 工资（有 porter 关系时按工资规则；没有 porter 关系则为 0）
laborNeed         = principal/self-employed 劳动 + porter 劳动
netPerLaborScaled = (运费收入 − 成本) × 1_000_000 / max(1, laborNeed)
```

- 没有 `trade` 产业模板 / 没有运力资产 ⇒ `feasible=false`，理由 `NO_MERCHANT_CAPACITY`；不凭空造运力。
- `MerchantSettlement` 仍是真实收入/工资/upkeep 的执行方；`ExpectedProfitBook` 只做前瞻预估。

### 3.3 `ModeMigrationPolicy` 改动

1. `plan(...)` 增加入参：`MarketTopology topology`、`List<MarketReport> marketReports`。
   - `EconomySettlement` 在周期关账钩子处已有 `topology` 局部变量，并可从 `profitCycle.marketReports()` 取报告（无报告 ⇒ 空表）。
2. `plan` 开头构建一次 `MarketDemandBook.Book`；对每个源家户构建 `currentProspect = prospect(当前 mode/hex)`。
3. `buildTargets` 改为：对每个距离 ≤ 1 的 hex、每个 mode（跳过 `DISPLACED`，它仍走 A 规则 fallback）：
   - 仍要求该 hex 有产业模板（`hasIndustryTemplate`）或该 mode 是 `merchant` 且该 hex 有商号运力；
   - 取该 mode 的最佳可生产位置（`producingPositionsByMode`），对源家户调用 `ExpectedProfitBook.prospect(...)`；
   - `feasible=false` / `scale=0` ⇒ 跳过；
   - `weight = max(0, targetProspect.netPerLaborScaled − currentProspect.netPerLaborScaled)`；
   - **删除 `book.hasReading` / `book.labor>0` 门槛**；`OrganizationProfitBook` 只用于 `FlowRow`/读数校准。
4. `planForSource` 的 A 规则条件改用 `currentProspect`：`netMilli < 0 && liquidity < nextCycleInputNeed`；
   `nextCycleInputNeed` 由同一计算器的 `inputCost` 给出（库存/可变现资产不足时触发）。
5. 目标选择（已有户优先、无户则计划新建）与 `MAX_HOUSEHOLD_POPULATION`、闲置资产预留逻辑保持；
   只把"有没有真实读数"判据换成"预期净收益更高且可行"。
6. 执行后源户 mode/standing/org/unit.modeKey 一字不改（D-022）；同 mode 内由 `EconomyLiquidationSettlement`
   做的阶层下落（`currentPositionId` 变化）**不算 D-022 违规**，但本设计不改 `EconomyLiquidationSettlement` 的逻辑。

### 3.4 `EconomyOrganizationSettlement` 改动

1. `preferredRegime` / `selectIndustry` 增加 merchant 分支：`position.modeId() == MERCHANT` ⇒ 优先 regime 为 `merchant` 的产业（`trade`），没有才回退现有顺序。
2. `RegimeOperators` 增加 `MERCHANT = "merchant"` 常量（唯一拼写点）。
3. 保持 D-023：`DISPLACED` 位置不自动组织、不发劳动配额。

### 3.5 `EconomySeeder` 改动（production-runtime path）

1. `productionRuntimeClassStandings(entries)` 读取每行的 `residence`，按 `(residence, slot)` 调新的 `productionRuntimePositionId(residence, slot)`：
   - 农村映射保持现状；
   - 城镇按 §1.2 第 5 条映射到手工业/商户；
   - displaced 行 ⇒ `displaced.laborer`。
2. `urbanCohort` / `productionRuntime` 的家户行：
   - 现有四行继续按 `CLASS_IDS` 切人口；在此基础上追加一条 displaced 行；
   - **槽位实现口径（Agent B 实测修正）**：`SocialClassId` 是封闭词表（`poor_peasant/middle_peasant/rich_peasant/landlord/landless_laborer/artisan/official`），没有 `"displaced"` ⇒ 该行的 `slot`/`view.stratum` 用词表内既有的 **`landless_laborer`**，家户 id 用
     `HouseholdId.ofSeedRole(hex, residence, new SocialClassId("landless_laborer"), "displaced")` 保留显式痕迹；
     `productionRuntimePositionId` 对 `landless_laborer` 槽位裁决为 `displaced.laborer`。这是与既有 `SevenHexFullChain3650Test` 的 `DISPLACED="landless_laborer"` 约定一致的口径。
   - displaced 人口 = `floor(池人口 × DISPLACED_SEED_PER_MILLE / 1000)`（具名 GM 默认 `50`=5%），从该池 `CLASS_IDS[0]`（最贫一档）人口里扣除，保证该池 `Σ 行人口` 逐值不变；
   - displaced 行 `laborMilli=0`，不发 `memberships`；四行常规 membership 仍按**扣减前**的阶层权重切（`Σ Membership.count == Σ ClassRow.population` 全局守恒仍成立，代价是 class0 常规户的 membership 计数可能略高于其扣减后的行人口——已记为具名近似）。
   - 若池人口不足以切出 ≥1 人的 displaced 行，则不发该行（不造 0 人以下人口）。
3. 每个有 `urbanPool` 的格追加 `trade` 产业模板：
   - `capacityPerUnit = {CATTLE: 1}`、`outputPerUnit = {}`、`cycleInputPerUnit = {}`、`laborPerUnit = 1000`、`cycleDays = CYCLE_DAYS`；
   - 经营者 = 商号 principal 家户 actor；资产份额由第 4 条发。
   - ★ **载荷解析适配（Agent B 实测修正）**：`merchant` regime 尚未登记进 `RegimeOperators.BY_REGIME`/`RegimeRelations`，
     而 `EconomyPayloads.industrySpec` 对无 `operator` 键的 industry 会先走 `RegimeOperators.defaultOperator` ⇒ trade 节点若按纯新形状发会在解析期 fail-closed。
     因此 trade 载荷显式带 `operator`（principal）与 `relation`（最小自留关系），解析器按 legacy 合成路径建出 trade unit；seeder 不重复发显式 trade unit。
     本批不扩这两张登记表，留作后续缺口（见 §9）。
4. 商号与运力资产：
   - 每个城市格播种一个 `MerchantFirm`：`organizationId = ProductionOrganizationId.idOf(MERCHANT, merchantPrincipalPositionId, merchantPrincipalHousehold, hexKey)`，
     tier `PORTER`（默认）或 `SELF_EMPLOYED`（按 `MERCHANT_TIER` 具名常量），`homeHex = hex`、`homeIsCity = true`、
     `capacityPerRound = MERCHANT_CAPACITY_PER_CITY`（具名 GM 默认，建议 `100_000`）、`capacityUsedThisRound = 0`、
     serviceRadius 用 `MerchantFirm.defaultServiceRadiusHex(tier)`、penalty/fee/upkeep/profit 初值 0；
   - 给商号所在的 `trade@hex` 播种 `CATTLE` `AssetShare`（owner = operator = principal actor，kind OWNED，数量 = 具名常量，至少 1）；
   - `EconomySeeder.Seed` / `jsonOf` 增加可选顶层 `merchantFirms` 键（载荷形状与 `EconomyPayloads.parseMerchantFirms` 逐字一致；空表时该批不发出，保持旧形态）。
5. `PRODUCTION_RUNTIME_ASSET_KINDS` 增加 `SHIP`、`CATTLE`。
6. `productionRuntimeRulesVersion()` 改为写 `EconomyMeta.RUNTIME_VERSION_SEVEN_HEX_V2`；seeder 自检也用 V2。
   ★ **版本门同步**：`EconomyMeta.isCurrentRuntimeVersion()` / `isCurrentRuntimeVersion(String)` / `requireCurrentRuntimeVersion*` 的“当前版本”比较目标从 V1 切到 V2（旧 V1 一律视为旧档）；这是 V2 写入路径的必要条件，由 Agent B 一并改（见 §10 文件所有权）。

### 3.6 版本与旧数据

- `EconomyMeta` 增加 `RUNTIME_VERSION_SEVEN_HEX_V2 = "seven-hex-v2"`；`RUNTIME_VERSION_SEVEN_HEX_V1` 保留为历史常量。
  ★ `isCurrentRuntimeVersion()` / `requireCurrentRuntimeVersion*` 的当前版本比较目标切成 V2；**激活/加载路径的调用接线仍不在本批**（见 §9 第 6 条）。
- production-runtime seed 写 V2；旧 V1 载荷/存档一律视为旧档（本批不做兼容读取）。
- 现有"激活/加载时拒绝旧版本"的缺口不在本批实现（保留在 §9 已知缺口）；本轮至少保证 seeder 自检与新档写入 V2。

## 4. 数据流与调用次序

```text
创世（app 组合根）：
  WorldgenInitializeTool(economyProfile=production-runtime)
    → EconomySeeder.planProductionRuntime
        · (residence, slot) → 阶层位置（含手工业/商户/流民）
        · 农村：农业 + 家庭纺织；城镇：手工业 + trade
        · 商号 + CATTLE/SHIP 资产
        · 顶层载荷：modes/classStructures/classPositions/classStandings/assetRules/liquidationPolicies/merchantFirms
    → EconomyPayloads 解析 → EconomyData（31 组件，merchantFirms 非空）

每日（EconomyDayStepper / EconomySettlement）：
  ① 到货 → ② 自动组织（merchant position 选 trade） → ③ 投入/生产/收获
  ④ 市场撮合（merchantFirms 非空 ⇒ 真实承运商；写 MarketReport：fill/unfilled/routes/freight）
  ⑤ 关系实付/消费/死亡 → ⑥ 市场报告累加进 profitCycle
  周期关账日：
  ⑦ OrganizationProfitBook.collect（真实账，仍保留作对账）
  ⑦a MerchantSettlement.settleCycle（真实运费/porter 工资/upkeep/运力）
  ★ 新增：MarketDemandBook.build(profitCycle.marketReports(), topology, rows/units/industries/shares/shipments/accounts/markets)
  ★ ⑧ ModeMigrationPolicy.plan(..., topology, marketReports, demandBook)
        · currentProspect = ExpectedProfitBook.prospect(源户当前 mode/hex)
        · 候选 = 距离≤1 的 hex × mode；无 hasReading 门槛；预期净收益 > 当前才进权重
        · A 规则 = currentProspect.net < 0 && liquidity < currentProspect.nextCycleInputNeed
  ⑨ ModeMigrationSettlement.apply（D-022/D-023 不变：只转移/新建，不改源户 mode/standing/org/unit.modeKey）
  ⑩ profitCycle.resetForNextCycle
```

## 5. 接口/契约

### 5.1 新增主类

- `MarketDemandBook`：唯一公开静态入口
  `public static MarketDemandBook.Book build(List<MarketReport> reports, MarketTopology topology, Map<HouseholdId, ClassRow> rows, Map<ProductionUnitId, ProductionUnit> units, Map<IndustryId, Industry> industries, Map<AssetShareId, AssetShare> shares, Map<ShipmentId, ShipmentBatch> shipments, AccountSession accounts, Map<HexCoord, Market> markets, long day, long horizonDays)`。
- `ExpectedProfitBook`：唯一公开静态入口
  `public static Prospect prospect(EconomyData base, HouseholdId household, ProductionModeId modeId, HexCoord hex, Industry industryOrNull, Market marketOrNull, MarketDemandBook.Book demand, Map<AssetShareId, AssetShare> shares, AccountSession accounts, Map<ProductionUnitId, ProductionUnit> units, Map<ProductionUnitId, ProductionRelation> relations, MarketTopology topology, long day)`。
  - 内部用 `base.merchantFirms()` 处理 merchant 分支。

### 5.2 修改的签名

- `ModeMigrationPolicy.plan(...)` 增加最后两个参数：`MarketTopology topology`、`List<MarketReport> marketReports`。
  - 旧包内调用点（`EconomySettlement`）必须传真实拓扑与 `profitCycle.marketReports()`；手工测试调用点由测试代理更新。
- `EconomySeeder.jsonOf(...)` / `Seed` 增加 `merchantFirms` 载荷字段；为空时输出逐字节与旧路径相同（不新增键）。
- `HouseholdId.ofSeedRole(HexCoord, ResidenceKind, SocialClassId, String roleSuffix)`（新工厂，格式 `hh-<hex>-<residence>-<stratum>-<roleSuffix>`；仅 seeder 使用）。

### 5.3 失败语义

- 没有市场、没有价格、没有需求 ⇒ `feasible=true` 但 `sellable=0`、`reason` 带具名原因；不抛、不静默当"高利润"。
- 没有资产/没有运力/没有产业模板 ⇒ `feasible=false`，具名原因（`NO_ASSET` / `NO_MERCHANT_CAPACITY` / `NO_INDUSTRY`），该候选权重 0。
- `ModeMigrationPolicy` 遇到不可行候选跳过；不能因为"没有读数"整段 no-op。
- 数据坏（重复 household、负数、未知 commodity）仍按现有 fail-closed 抛。

## 6. 版本、激活与重置

- 新档 = `seven-hex-v2`；旧档 = 任何非 v2 的 production-runtime 档，视为不可用。
- 本批不实现激活/加载拒绝（已知缺口）；seeder 写入前 `requireCurrentRuntimeVersionTag(V2)`。
- 没有 GM `resetEconomy`：仍按既有工具面缺口处理，测试用新档重播。

## 7. 迁移/回滚/部署

- 无旧档迁移；回滚 = 回到 `seven-hex-v1` 代码与旧档，但旧档已被本批新语义弃用。
- 部署影响：`simos-economy` 新增两个类 + `ModeMigrationPolicy` 签名变化；`simos-app` seeder 载荷新增可选键。
- 兼容性：`merchantFirms` 键缺失时解析为空表（已有行为）；空表时运费按旧 fallback 路径。
- 文档先行：本文件提交后派实现代理；实现代理不得改本文件，发现设计错误在报告里具名列出。

## 8. 验收判据与证据

### 8.1 单元/模块判据

1. `MarketDemandBook`：
   - 有上一轮买方未成交时，`addressable` 取未成交而非自然需求重复相加；
   - 没有报告时回退基本需求 − 库存 − 在途；
   - 同一输入两次 build 逐值相同。
2. `ExpectedProfitBook`：
   - 给一个**没有任何既有组织**的 mode/hex + 有价格 + 有可寻址需求 ⇒ `feasible=true`、`netPerLaborScaled != 0`、`sellable>0`；
   - 需求为 0 ⇒ `sellable=0`、`netPerLaborScaled<=0`、`demandCapped=true`；
   - 两个候选 mode 的预期净收益排序与手算一致；
   - 缺资产/缺运力 ⇒ `feasible=false` 且理由具名；
   - 同 mode 不同 hex 独立计算。
3. `ModeMigrationPolicy`：
   - 存在 `hasReading=false` 但 `ExpectedProfitBook` 预期正收益的候选时，仍能产出非空迁移计划；
   - 源户 mode/standing 在执行前后不变（D-022）；
   - A 规则使用预期利润（不是只看真实账）。

### 8.2 真实 12hex 3650 tick 判据（`RealTwelveHexProductionRuntime3650Test`）

| 判据 | 期望 |
|---|---|
| tick0 生产方式 | `handicraft_workshop > 0`、`merchant > 0`、`displaced > 0`；农村 farm mode 仍 > 0 |
| 城市人口 | 终局 urban 人口 ≥ 初始的 50%（本轮先以"不 875→9"为硬判据；具体阈值写入测试报告） |
| merchant | `merchantFirms` 非空；至少一个边界 `freightPaid > 0` 或 `freightUncollected=0` 且真实运费进账 |
| displaced | `displacedPeak > 0`、`displacedLast >= 0`；DISPLACED 无劳动配额/无自动组织 |
| 迁移事件 | merges > 0、creations > 0、populationZeroed > 0（真实读数） |
| 债务/断粮 | `maxDebt > 0`、`maxUnmetNeed > 0`，且终局不因负值崩溃 |
| 守恒 | 逐币种货币 == 初始；AssetKind 总量 == 初始；无负值 |
| D-022 | mode / `ProductionOrganization.modeId` / `ProductionUnit.modeKey` 不得原地改写；同 mode 的 `ClassStanding.currentPositionId` 下落（债务驱动阶层下落）不计违规 |
| D-023 | 任意介质偿还路径无回归；全部币种随迁；流民无自动招募 |

证据：surefire XML/TXT + `/tmp/real12-v2.log`（或测试报告指名路径），由测试代理在报告里逐条贴读数。

## 9. 已知缺口与风险（如实记）

1. **P8/P9 社会侧城乡迁移未接**：经济侧 mode 迁移可以跨 hex 搬家户/劳动，但 `SocialData` 的 residence 批次不跟着拆合；本轮读数以 economy 侧 `ClassRow.view().residence()` 为准，社会侧可能出现不一致（测试代理必须在报告里报出偏差）。
2. **需求竞争是近似的**：多个新进入者可能同时把同一份未成交买方需求计入自己的收入上限；本轮不做订单簿级重新撮合。
3. **动态价格未开**：预期利润全部按当前固定价 + bid/ask；价格不随供需调整。
4. **商人 upkeep 仍非现金支出**：D-023 第 4 条（维护/upkeep 真实现金支出）本批仍未做；`MerchantSettlement` 的 upkeep 继续按既有计提口径。
5. **CityLand/城区容量/慢速扩建未接**：城市人口不因城区拥挤被限制。
6. **激活/加载版本门未接**：V2 会在 seeder 写入前自检，且 `EconomyMeta` 的当前版本判据切到 V2；但"世界激活/载入时拒绝 V1"的调用接线仍不在本批，旧档仍可能被别的读路径读入，需要后续 GM reset/激活门。
7. **merchant 组织的产业模板选择需要新分支**：若 `EconomyOrganizationSettlement` 未改成功，merchant principal 可能回退到 farm/craft；测试代理以"merchantFirms 非空 + 运费进账"为判据暴露。
8. **没有 `trade` 模板的旧世界**：candidate merchant `feasible=false`，不会凭空造运力。
9. **merchant regime 尚未登记进 `RegimeOperators.BY_REGIME`/`RegimeRelations`**：trade 载荷用显式 `operator`/`relation` 走 legacy 合成路径规避解析失败；merchant 新 unit 的关系仍可能是最小自留 + 具名审计 `merchant-regime-unregistered`。后续应把 merchant 登记进两张表或惰性化 legacy 推导。
10. **小城市商号孤儿风险**：若某格 `urbanPool>0` 但城镇地主人口切分为 0（城市人口 <20 时可能发生），该格的 `merchantFirms` 组织 id 不会被自动组织创建，而 `productionOrganizations` 非空后守卫可能抛。测试代理必须专门验证（建小城夹具或检查真档城市规模）。
11. **displaced membership 近似**：displaced 行不发 membership；四条常规行 membership 仍按扣减前阶层权重切，故 class0 常规户的 membership 计数可能略高于其扣减后的行人口；全局 `Σ Membership.count == Σ ClassRow.population` 仍成立。
12. **本设计不覆盖**：GOV/税、FX、城市财政、商人买低卖高、完整城区租金。

## 10. 文件所有权（本阶段）

### Agent A（`simos-economy` 生产代码，只写 main，不写/不跑测试，不 commit）

允许：
- `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketDemandBook.java`（新增）
- `simos-economy/src/main/java/io/mosire/simos/economy/time/ExpectedProfitBook.java`（新增）
- `simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationPolicy.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java`（仅周期关账调用点/传参）
- `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomyOrganizationSettlement.java`（merchant 模板/位置支持）
- `simos-economy/src/main/java/io/mosire/simos/economy/model/EconomyMeta.java`（新增 V2 常量）
- `simos-economy/src/main/java/io/mosire/simos/economy/model/RegimeOperators.java`（新增 `MERCHANT` regime 常量）
- `simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationSettlement.java`（仅当 merchant/新 mode 目标建户必须选产业模板/资产时）
禁止：任何测试文件、`simos-app/**`、`docs/**`；不得 `git commit`。

### Agent B（`simos-app` 生产代码 + `simos-economy-api` 的 `HouseholdId` 工厂 + `EconomyMeta` 版本门）

依赖：Agent A 的 `RUNTIME_VERSION_SEVEN_HEX_V2` 已编译进 economy。

允许：
- `simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java`
- `simos-economy-api/src/main/java/io/mosire/simos/economy/api/id/HouseholdId.java`（新增 `ofSeedRole`）
- `simos-economy/src/main/java/io/mosire/simos/economy/model/EconomyMeta.java`（**只把当前版本判据切到 V2**：`isCurrentRuntimeVersion()`/`isCurrentRuntimeVersion(String)`/`requireCurrentRuntimeVersion*` 的比较目标与消息；不改 record 形状）
- 若载荷 plumbing 必须：`simos-app/src/main/java/io/mosire/simos/app/tools/write/WorldgenInitializeTool.java`、`RegionSeedPlan.java`（只做 merchantFirms 透传）
禁止：任何测试文件、其余 `simos-economy/src/main/**`（只可 import/读）、`docs/**`；不得 `git commit`。

### Agent C（测试代理，只写测试，不改 main）

允许：
- `simos-economy/src/test/java/io/mosire/simos/economy/time/**`
- `simos-app/src/test/java/io/mosire/simos/app/world/**`
- 必要时新增 fixture；`RealTwelveHexProductionRuntime3650Test` 的 D-022 口径按本文件 §8.2 修正
禁止：任何 main 代码、`docs/**`；不得为迁就实现改断言（发现不一致先报告）。

## 11. 第一轮重跑结果与修复队列（2026-10-06，测试代理回收后追加）

### 11.1 第一轮结果（证据：`/tmp/testagent/real12-final3.log`）

生产代码和测试代理按本文件 §1~§8 完成后，真实 12hex 已能跑到 3650 tick，且 D-024 的核心结构生效：

- tick0 economy 生产方式：`displaced=167`、`family_farm=1311`、`handicraft_workshop=657`、`merchant=175`、`tenancy_fixed_kind=134`、`wage_farm=1056`；`merchantFirms=1`（CATTLE 100）。
- D-022 违规（mode/org.modeId/unit.modeKey 原地改写）= 0；同 mode 阶层下落 17 条单列。
- 货币/资产守恒、负值扫描、D-023 DISPLACED 无劳动配额/无自动组织均通过；债务/断粮路径正常。
- 但 **city 主判据仍红**：economy urban `875 → 78`（终局 8.9%，设计阈值 ≥50%）；`handicraft_workshop 657→228`、`merchant 175→2`；迁移把城市户大量迁往农村 farm/tenancy 目标。
- 本 12hex 夹具只有 1 个 SocialCity / 1 个市场节点 ⇒ 没有跨区 lane，`shipments/freightPaid/crossRegionFills` 恒 0；§8.2 的“merchant freight 进账”判据在该夹具下结构不可达（不是实现通过）。

### 11.2 已定位的根因（按证据强度排序）

1. **`ExpectedProfitBook` 用错 claimed 集合（本轮必修）**：
   `prospect(...)` 在候选/当前资产规模里用
   `ModeMigrationPolicy.claimedAssetShares(base.productionOrganizations())` 重算"已被组织使用"的份额；
   而周期关账时 `base` 是**本周期开始时的 revision**（tick0 种子状态），`productionOrganizations` 为空 ⇒
   **所有 `owner==operator` 的份额（包括 ESTATE 名下全部土地）都被判成"闲置可租"**。
   于是城市户可以无限抢占 ESTATE 土地、计划新建农村 farm 目标，形成 `handicraft/merchant → family_farm/tenancy` 的单向抽水。
   证据：`ModeMigrationPolicy.plan` 自己已经从**当天工作副本** `organizations` 正确算出 `claimedByOrganizations` 并用于闲置预留，
   但 `ExpectedProfitBook.prospect` 不接收它、在内部重算了一份基于 `base` 的错误副本——同一判据两处拼写、且其中一处是过期快照。
2. **需求上限仍偏"想要"而非"买得起"（下一轮候选）**：
   `MarketDemandBook.unfilledBuyer` 取 `MarketReport.unfilled` 的数量，未按 `BuyerOutcome.affordableQty/filledQty`
   折算购买力；`addressable` 会高于真实可成交需求，进一步放大"看起来高利润"的 farm 目标权重。
3. **单节点夹具无法让 merchant 真实运转（夹具/世界结构问题）**：
   商人运费只在跨区 lane 上产生；1 城市 = 1 市场节点 ⇒ 跨区 lane 为空，merchant mode 没有真实收入来源，
   商号只能吃 upkeep、最终被抽走。要满足 §8.2 的 freight 判据，必须有 ≥2 个市场节点/城市（或在后续批次把市场镇接入节点生成）。

### 11.3 修复队列（修复 1 已做；1b 为第二轮证据追加）

- **修复 1（已做，economy main）**：把 plan 已从工作副本算出的 `claimedByOrganizations` 作为**唯一判据**传入
  `ExpectedProfitBook.prospect(...)`；删除 `ExpectedProfitBook` 内部基于 `base.productionOrganizations()` 的重算。
  所有调用点（`ModeMigrationPolicy.planForSource` / `buildTargets` / 未来测试）共用这一份集合。
  验收：真实 12hex 重跑后 `family_farm/tenancy` 目标不再把 ESTATE 已使用土地当闲置；城市人口不再单向抽水；
  若仍不达标，按 11.2 第 2/3 条继续。
  ★ **第二轮结果（Agent C2，2026-10-06）**：修复 1 实现正确（两条回归 + 双向变异锁住），但 12hex 读数与第一轮**逐字相同**。
  证据：`/tmp/testagent/r2/real12-final3.log`，tick=120 工作副本有 81 个组织 / 76 条 claimed 份额，城市源户的计划目标全是
  `wage_farm`；`wageProspect`/`employerOf` 不经 claimed 资产判据，且 `employerOf` 读 `base.productionOrganizations()`
  过期快照。⇒ 修复 1 必要但不充分，追加 1b。
- **修复 1b（本轮追加，economy main）**：
  1. **claimed 集合按"在产 unit"补全**：`claimedAssetShares(...)` 的集合 = `organizations.assetSources` ∪
     { 所有 `assetShares` 中满足 `share.industry()==unit.industry() && share.operator()==unit.operator()` 的份额 }；
     这样 seeder 直接以 ESTATE operator 建立的 farm/weave/craft/trade unit 所占的土地/工具/作坊/船畜不再被当成
     "无组织引用的闲置份额"。`ModeMigrationPolicy.plan` 与 `ModeMigrationSettlement` 的闲置判据共用同一份新集合。
  2. **`employerOf` 不再读 `base.productionOrganizations()`**：改为从**当前 unit 表**按"同格、同产业、operator != 本户"
     选产能规模最大的 unit 作为雇主（mode 兼容性按 `unit.modeKey`/产业 regime 校验；找不到就 `NO_EMPLOYER`）。
     这样 wage 目标使用当天真实雇主，不用 tick0 过期快照。
  3. 验收：真实 12hex 重跑后城市户不再被 `wage_farm`/`family_farm` 目标单向抽水；若仍红，按 11.2 第 2/3 条继续。
- **修复 2（下一轮，economy main）**：`MarketDemandBook` 的买方需求改用 `BuyerOutcome` 的有效购买力口径
  （`affordableQty`/`filledQty`/`desiredQty` 与 `unfilled` 的 max/交叉校验），并在 `addressable` 里扣除**正在生产的既有供给**。
- **修复 3（下一轮，测试/世界结构）**：为一个 ≥2 城市/市场的真实小地图补 merchant freight 端到端证据；
  或在组合根把市场镇/手工业格接入市场节点生成（需单独设计，不在修复 1 内顺手做）。

### 11.4 文件所有权（Agent A2 完成修复 1；Agent A3 做修复 1b）

Agent A2（已做）允许：`ExpectedProfitBook.java`、`ModeMigrationPolicy.java`（+ 必要的 `MarketDemandBook.java` 签名联动）。

Agent A3（修复 1b）允许：
- `simos-economy/src/main/java/io/mosire/simos/economy/time/ExpectedProfitBook.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationPolicy.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationSettlement.java`（仅同步 claimed 集合/雇主判据）
禁止：任何测试、`simos-app/**`、`docs/**`、其余 economy main、`git commit`。
