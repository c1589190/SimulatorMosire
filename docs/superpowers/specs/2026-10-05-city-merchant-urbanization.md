# 城市、商人/商队与城市化：调查与实施方案（2026-10-05）

> 来源：用户 2026-10-05 对城市、商人、工坊与城市化的设计裁定。
> 关联：`2026-10-04-market-debt-architecture.md`（市场/债务）、
> `2026-10-05-diversified-transport-handicraft.md`（运输/手工业探针）、
> `2026-10-04-market-debt-implementation-plan.md`（M1–M6）。
> **7 格中心城探针结果**：`docs/superpowers/reports/2026-10-05-city-merchant-probe-results.md`
> （商人阶层、农村贸易成本累积、城市承载/慢速扩建已跑通）。
> 结论：**不需要另造“城市能力”玄学；把已有地块面积、城乡批次、城市腹地、市场区/贸易路线接起来，
> 再加“商人组织 + 工坊利润 + 人口迁移”三件事即可。**

## 0. 用户裁定的一刀

- 城市建筑在**当前 hex** 算比例；
- **不做脚夫**，改成**商人**；商人自己组织商队进行交换；
- 当地商人越多 → 分配到该 hex 的**额外运输成本越低**；
- 但商人会**指数级占用城市区**；
- 城市工坊**生产成本更低** → 家户从农村工坊自然转向城市工坊 → 城市化；
- 初始化下城市本来就存在 ⇒ 只需要算**城市人口流动**；
- 城市集中人的主要拉力 = **商贸交易需求增加**。

## 1. 现状盘点：已经有什么

### 1.1 可用土地/农业支撑（已有）

`EconomySeeder` 已经算**每格可耕地**，而且与人口无关：

```text
MU_PER_HEX = 3,100 亩/格
arablePermille = terrain.food / FOOD_AT_FULL_ARABLE(3) × 1000
landMilliMuOf(terrain) = 3100 × 1000 × arablePermille / 1000   # 千分亩
```

农业配方/量纲也都是现成的：

```text
GRAIN_OUTPUT_PER_MU = 67 粮/亩
SEED_PER_MU         = 8 粮/亩
LAND_MU_PER_LABOR   = 7 亩/标准劳动
FIBER_OUTPUT_PER_MU = 6 单位/亩（副产）
```

产能侧是资产驱动的：`ProductionUnitBook.capacityScaleOf = min_k ⌊usableAssets[k] / industry.capacityPerUnit[k]⌋`；
`AssetShare.quantity` 的 LAND 就是**千分亩**。

### 1.2 城市与城乡人口（已有）

`SettlementGenerator` 已经做：

- 总人口按 `urbanizationRate` 切城市/农村；
- 按地形剩余 + 交通 + 历史分选市场镇，三级升级；
- 城市腹地竞争（`Influence = W / cost^k`，半径来自 tier）；
- `PlannedCity(population, catchmentHexes, localSurplus, tradeMultiplier, politicalMultiplier)`；
- 出口三条不变量：`Σrural == ruralTotal`、`Σcity.population == urbanTotal`、`rural + urban == total`。

`PopulationSeeder` 把城市落成 `urban:<cityId>:<sex>:<cohort>` 批次；`EconomySeeder` 再落城镇四行与作坊：

```text
workshops = 城镇人口 / URBAN_CAPITA_PER_WORKSHOP(50)
```

城市是 `GameMap.cities: CityId → City`，`City.props` 是可扩展属性表；市场区契约 `MarketRegion`/`MarketNode`
也是**从城市与半径现算的派生件**，城市权威不在 economy。

### 1.3 手工业与城乡差异（已有）

- 农村 `weave`（家户副业，`REGIME_HOUSEHOLD`）；
- 城镇 `craft`（作坊，`REGIME_HANDICRAFT`，`UNAB`：60 布/周期/座，1 标准劳动，2 件工具投入）；
- 但“城市工坊成本更低 / 家户从农村转向城市”目前只是**初始化时的固定城乡分布**，不是动态选择。

### 1.4 市场/贸易/在途货物（契约已有，运行时被删）

`simos-economy-api/market` 已经有：

- `MarketRegion(node, members)`：城市的市场区，成员格 = 腹地；
- `MarketNode(nodeId=cityId, anchor, radiusHex, numeraire, receiveWith)`；
- `TradeRoute(from,to,capacityPerWindow,travelTicks,costPerUnit,lossPerMille)`：
  运力/时间/实际投入/损耗，**运费留给结算层按具名费率铸腿**；
- `ShipmentBatch(route, commodity, dispatchTick, arrivalTick, quantity, allocations)`：
  跨 tick 在途货、逐票分配、到货前目的格不得消费。

**缺口**：旧 `MarketSettlement` 已删，新的结算/承运运行时（M4）没接；`TradeRoute` 注释明确说
“只有在世界里真有承运 actor（`ActorKind.ORGANIZATION`）时才收运费”——这正是商人/商队的位置。

### 1.5 人口迁移（缺口最大）

`PopulationDynamics.monthly` 只有**出生/死亡**；`LotChange(group, at, births, deaths)` 没有迁入/迁出；
`ClassFirstPopulationWriteback` 只能按人口权重分摊出生/死亡，不能把一批人从 rural lot 搬到 urban city lot。
所以“工坊人员从农村转到城市”需要**新的跨切片迁移机制**，这是本轮最大的工程量。

## 2. 实施方案

### C0：每格土地预算 = 可耕地 − 城区占地

在现有 `landMilliMuOf` 上拆出：

```text
hexAreaMu        = 3,100
arableMu         = hexAreaMu × terrainArablePermille / 1000
cityBuiltMu      = baseMu(tier)
                 + cityPopulation × muPerUrbanCapita
                 + workshops      × muPerWorkshop
                 + merchantDistrictCost(merchants)
availableArableMu = max(0, arableMu − cityBuiltMu)
buildingRatio     = cityBuiltMu / hexAreaMu
```

- `cityBuiltMu` 写进 `City.props`（或新 `CityLand` 类型，落进 map/social 状态）；
- `EconomySeeder.landMilliMuOf` 新增可用土地口径（保留旧方法给不关心城区的路径）；
- 农业可支撑人口 = 由 `availableArableMu`、`GRAIN_OUTPUT_PER_MU`、`SEED_PER_MU`、`LAND_MU_PER_LABOR`
  和口粮需求一起取最紧一路；农村剩余 = 产出 − 农村口粮 − 种子 − 折旧；
- 城市粮食缺口 = 城市人口 × 口粮；缺口必须由商队输入或饿死/迁出。

### C1：商人/商队替代脚夫

- **行为主体**：`ActorKind.ORGANIZATION` 的商人组织（或新增 `ActorKind.MERCHANT`），
  `ProductionOrganization` 里 `organizer=商人`、`assetSources={SHIP,CATTLE,TOOL}`、`inputSources=商队开销`；
- **商人数量**：`merchants(city) = f(城镇人口, 城市 tier, 贸易需求, 制度)`，写进 `City.props`/社会状态；
- **运力**：商队容量 = 船只/牲畜/车辆 × 单位载量 × 路况；
  `TradeRoute.capacityPerWindow` 由同路线商人运力汇总；
- **成本**：基础 `costPerUnit` 来自 `TradeRoute`（hexDistance × moveCost），
  当地商人越多，**额外运输成本**越低：

```text
effectiveTransportCost = baseCost × (1 − min(maxDiscount, merchantsOnRoute × discountPerMerchant))
```

  或等价地 `capacityPerWindow += merchants × capacityPerMerchant`；
- **指数级占城区**：

```text
merchantDistrictCost(n) = baseDistrict × growth^n        # growth > 1
cityDistrictCapacity(tier, terrain) 是硬上限
```

  超过上限 ⇒ 新商人无法落脚（租金暴涨/拒绝注册），形成“城市越大、交易越密、但拥挤成本也越高”的平衡；
- **在途**：成交后建 `ShipmentBatch`；到达日才允许目的格消费；损耗进损失账户；
- **盈亏**：商人买低卖高 − 路线成本 − 在途损耗；亏损 → 退出/破产；盈利 → 新商人进入。

### C2：工坊利润与“农村 → 城市”迁移

- **单位利润比较**：

```text
ruralWeaveProfit = 布价×农村产出 − 纤维价×投入 − 家庭劳动保留工资
cityCraftProfit  = 布价×城市产出 − 纤维价×投入 − 工资 − 城市租金/摊派
                   + 聚集折价(merchants, demandDensity, transportHub)
```

- 城市工坊因需求密度/商人集中/运输枢纽获得**成本折价**；
- 当 `cityCraftProfit > ruralWeaveProfit + threshold` 且城市有食物/住房余量：
  按比例把农村 `weave` 劳动/家户迁入城市，转为 `craft`；
- 反向亏损时允许迁回或转产；
- **迁移机制**（依赖 C3）：拆分/合并 `PopulationGroup`（按性别×年龄档），
  从 `rural:<hex>` 批次移到 `urban:<cityId>` 批次；更新 `SocialData` +
  `ClassFirst` 家户账户（人口/劳动/债务随人走）；新增 `LotMigration` 形态，
  不允许再用 `LotChange` 的出生/死亡两个数表达“换居住地”。

### C3：城市人口流动 = 商贸需求拉动

- **拉力**（用户裁定：主要来自商贸）：

```text
urbanPull = w1×tradeVolumePerCapita
          + w2×merchantCount/需求密度
          + w3×cityCraftProfit
          − w4×foodPrice/粮食缺口
          − w5×rent/congestion(buildingRatio)
```

- 月度/周期计算 `flow = clamp(urbanPull × ruralPop × rate, 0, maxFlow)`；
- 迁入必须同时满足：城市食物供给（含商队输入）和城区容量；
- 初始化下城市已存在 ⇒ **只做流量，不做“平地建城”**；
- 城市越大 → 腹地/商路需求越大 → 商人越多 → 工坊利润越高 → 迁入越多，直到
  城区拥挤/粮食缺口/运输成本把边际收益吃掉。

## 3. 实施批次建议

| 批次 | 内容 | 落点 | 门禁 |
|---|---|---|---|
| C0a | `CityLand`/城市占地 + `availableArableMu`；更新农业可支撑人口与农村剩余 | map/social/economy | 编译 |
| C0b | 当前 hex 建筑比例读数（城区/可耕地/剩余） | 读口/报告 | 编译 |
| C1a | 商人数/商队容量/路线成本折扣 + 指数城区占用 | `City.props`/市场层 | 编译 |
| C1b | M4 市场结算接 `MarketRegion`/`TradeRoute`/`ShipmentBatch`；运费付给商人 | market 运行时 | 编译 |
| C2a | 农村/城市工坊利润比较与聚集折价 | 生产/关系层 | 编译 |
| C3a | 人口迁移机制（rural lot → urban lot，债务/劳动随行） | social+economy+app | 编译 |
| C3b | 商贸拉动的城市人口流；365×4 城市世界验收 | 测试/报告 | 全绿 |

## 4. 立即可做的最小实验（探针先跑）

在 `ProbeEconomy` 上先把机制跑出来，再进正式状态：

1. 给 H0/H1 各加 `cityBuiltMu` 与 `availableArableMu`，城市人口/工坊/商人按比例占城区；
2. 把 `TransportTeam` 改为 `Merchant`：商人有商队容量、路线折扣、城区占用指数；
3. 给城市 `WageFarm` 加 `costDiscount`，农村家户比较两种毛利后按比例转城；
4. 跑 365×4：看城市人口、粮价、布价、商人数、城区比例、农村耕地是否形成稳定平衡。

## 5. 需要用户裁定的点

1. 城区面积单位：用“亩”还是抽象“街区/区划单位”？
2. 商人作为 `ORGANIZATION` 还是新增 `ActorKind.MERCHANT`？
3. 迁移的最小单位：拆 `PopulationGroup`（人）还是整户迁移？
4. 城市工坊成本折价：按商人数、需求密度还是腹地剩余算？出厂值多少？
5. 城区拥挤上限：由城市 tier 定，还是由地形/可耕地比例定？
6. 政府发行权已列入清单；城市财政/商税是否也走同一套 GOV 账？

## 6. 与政府货币发行权的关系

用户 2026-10-05 已裁定：**各政府有无货币发行权必须逐国显式**。
城市/商人体系必须读同一份 `Government.issuable`，不允许“某城自己铸钱/发钞”绕过
`MoneyIssuanceRecord`；城市财政、商税、跨区汇兑都走国家/市场区的货币层，不另造第二套货币权威。
