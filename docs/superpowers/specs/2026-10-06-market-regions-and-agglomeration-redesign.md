# 市场区与聚集节点重设计：行政区域为区、节点由经济聚集自然生成

> 状态：**重新设计、待用户确认后派实现**（2026-10-06）
> 规则依据：`AGENTS.md` §一.8（派实现架构子代理前必须先保存完整架构设计文档）。
> 本文件**取代** `docs/superpowers/specs/2026-10-06-expected-profit-demand-and-mode-entry.md` §2.1 的“市场节点 + 半径划区”口径，
> 并取代 `MarketTopologyBook.from` 当前“城市节点 + radiusHex 最近节点归属”的划区方式。
> 关联裁定：D-022（生产方式只通过转移表示）、D-023（任意介质偿还/资产随迁/流民）、D-024（预期利润与需求）、
> D-025（饥荒/城市衰亡合法，城市化率是派生量）。

## 0. 一句话目标

把市场区从“城市节点 + 半径”改回**地图提供的、GM 可编辑的行政区域**（Region；未来可由 Nation 的地方政府提供），
把“市场节点”从划区依据改成**经济聚集的自然产物**：城市化地区降低贸易成本 → 贸易与商人聚集 → 节点做大；
没有商人投机、中世纪贸易稀疏 ⇒ 城市不崛起是合法结果，不强行造节点、不强行保城市。

## 1. 需求来源与用户原话

- 「哦牛逼，你区域市场不是按一个map提供的区类型、可以被GM编辑、实际可能由Nation的单个地方政府提供的区域来划，
  是根据市场节点+半径来划的？」
- 「我的建议是让节点通过经济的聚集自然生成，为什么能自然生成？城市化地区能减贸易等的成本，就这么简单，
  目前还没做商人转手投机倒把，加上中世纪商贸不频繁，城市不崛起很正常；重新设计吧」

用户裁定要点（本文件按此设计）：

1. **市场区的第一性来源 = map 的 Region**（GM 可编辑；`RegionMeta.tag`/`annexedBy` 可表达类型与归属；
   未来可以对齐 Nation 的单个地方政府辖区）。**不是**“节点 + 半径”最近归属。
2. **节点（贸易枢纽/城市）是经济聚集的结果**，不是划区输入；聚集的机制是**城市化地区降低贸易成本**。
3. **没有商人投机倒把、中世纪贸易不频繁 ⇒ 节点/城市不崛起是正常结果**；系统不得为了“有节点”而伪造聚集或城市。
4. **D-025 继续有效**：城市可以因饥荒萎缩/归零，城市化率是派生量。

## 2. 现状与问题

### 2.1 现状（要替换的路径）

- `MarketTopologyBook.from(state)` 当前：
  1. 从 `SocialData.cities`（其次 `GameMap.cities`）取城市 → `MarketNode(nodeId, anchor, radiusHex, numeraire, receiveWith)`；
  2. `MarketTopology.of(nodes, markets, hexes, moveCost, ...)` 把**每个 hex 按“半径内最近节点”**归入一个 `MarketRegion`；
  3. 两区锚距 ≤ `rA + rB + MARKET_REGION_ADJACENCY_GAP_HEX` 即相邻供应区；跨区成交才走 `TradeRoute`/在途/承运/运费。
- 问题：
  1. **市场区不是 map 的 Region**：GM 在 map 里编辑的区划、类型、归属（`RegionMeta.tag`/`annexedBy`）根本不参与市场区划分；
     真实世界的“地方政府辖区/关税区/贸易区”表达不出来。
  2. **半径划区把空间关系强行几何化**：两格是否同市场取决于“离哪个城市锚更近”，与行政边界、道路、地方制度无关；
     GM 改地图 Region 也不会改变市场区。
  3. **节点是输入而不是结果**：城市一旦存在就有节点与半径；没有“贸易把节点养大”的正反馈。
  4. 单城市世界 = 1 节点 = 1 区 ⇒ 无跨区 lane，merchant `freightPaid` 结构恒 0；
     而真实世界里跨区贸易的“区”本来应该由行政/地方边界给出，不应该由“有没有第二个城市”决定。

### 2.2 目标

1. **区 = map Region**：`EconomyData.markets` 的逐格价目表继续存在，但“哪些格属于同一个市场区”由 map 的 `Region`（GM 可编辑）决定；
   市场区可以有 0 个、1 个或多个节点。
2. **节点 = 经济聚集产物**：节点/贸易枢纽在区内或跨区道路上由贸易量、商人数量、城市人口/密度共同养出来；
   节点提高后降低该地区的贸易成本，进一步吸引贸易与人 —— 正反馈；达不到阈值就不生成。
3. **贸易成本含聚集折价**：`effectiveTradeCost = baseRouteCost × (1 − agglomerationDiscount)`；
   `agglomerationDiscount` 由节点（城市化地区）的城市人口/密度、商号数量、累计贸易量派生。
4. **不做商人投机**：商号仍是承运服务（运费收入 + porter 工资 + upkeep）；跨区价差与买低卖高不在本批。
5. **不强行城市化**：D-025 继续有效；节点生成/升级可长期不发生。

### 2.3 非目标

- 不做商人买低卖高的跨区套利；不做动态价格（价格仍是市场表数据）。
- 不在本批实现 Nation 地方政府的完整财政/关税；只留下“Region ← 地方政府辖区”的接口位。
- 不新增 `EconomyData` 持久组件来存节点表；节点/区/道路是派生视图，驱动量放社会/地图既有状态或读数。
- 不做“每格一个节点/每格一个区”的碎裂化方案。

## 3. 新架构

### 3.1 三层结构

```text
① 行政/市场区层（map 权威，GM 可编辑）
   Region(id, name, hexes, boundary, meta)
   · meta.tag        = 区类型（例如 market / trade / closed / frontier；GM 可改）
   · meta.annexedBy  = 归属（Nation/地方政府的接口位）
   · 市场区 = 一个参与市场的 Region（或 GM 指定的 Region 组合）
   · hex → 市场区 的归属 = Region 的 hexes（显式，无半径、无最近节点）

② 经济节点层（经济聚集派生，不落盘）
   TradeHub(hubId, regionId, anchor, tier, urbanPopulation, merchantCapacity, cumulativeTradeVolume,
            agglomerationDiscountPerMille)
   · 初始 hub = 已有的城市/市镇（社会/地图的聚落）
   · hub 可由贸易/商人/人口越过阈值后“升级/新建”（政策在组合根/社会侧执行，经济只出读数）
   · hub 不参与“hex 归哪个区”的判定

③ 路线层（道路 + 节点连接）
   MarketRoute(fromHub, toHub, capacityPerWindow, travelTicks, costPerUnit, lossPerMille,
               agglomerationDiscountApplies)
   · 节点之间走真实道路/地形；区内无 hub 时只做本区即时交易
   · 运费收入 → 承运 MerchantFirm；成本被两端 hub 的聚集折价调制
```

### 3.2 区的归属（替换半径规则）

- `MarketTopology` / 新的 `MarketNetwork` 接收**显式的 `Map<HexCoord, RegionId>`**（来自 map 的 `Region.hexes`）。
- 没有“最近节点”这一步；一个 hex 属于哪个区，完全由 GM 编辑的 Region 决定。
- Region 没有 hub 时：
  - 该区仍可有逐格市场与区内即时交易；
  - 不产生跨区路线；若要跨区贸易，必须 GM/政策指定该区的 hub 或先在区内养出 hub。
- Region 类型 `meta.tag`：
  - `market`/缺省：普通市场区；
  - `trade`：允许作为跨区通道/集散候选；
  - `closed`：不参与跨区交易（关税/封锁/制度）；
  - 具体词表由 GM 政策表定义，economy 只读 tag 做成员判定，不猜语义。

### 3.3 节点自然生成（聚集正反馈）

**驱动量**（每周期/每窗口聚合，来自真实账与市场报告）：

```text
hub.tradeVolume        = 经该 hub 的跨区成交/在途量（毫商品；按窗口累计）
hub.merchantCount      = 以该 hub 为 home 的 MerchantFirm 数（或运力当量）
hub.urbanPopulation    = 社会城市人口（social 权威）
hub.urbanization       = urbanPopulation / 该区总人口
hub.agglomeration      = f(urbanPopulation, density, merchantCount, cumulativeTradeVolume, infrastructure)
hub.discountPerMille   = min(AGGLOMERATION_DISCOUNT_CAP_PER_MILLE,
                             agglomeration × POLICY_SCALE_PER_MILLE)
```

**正反馈**：

```text
更多贸易/商人/城市人口
  → 更高的 agglomerationDiscount
  → effectiveTradeCost 更低
  → 该 hub 的路线更有竞争力
  → 更多贸易/商人迁入
  → 回到第一步
```

**阈值与节点生成/升级**（政策在组合根/社会侧，不在 economy 直接写 social/map）：

```text
如果某聚落（或某区的候选锚）连续 N 个窗口：
    tradeVolume ≥ HUB_PROMOTE_VOLUME
    且 merchantCount ≥ HUB_PROMOTE_MERCHANTS
    且 urbanPopulation ≥ HUB_PROMOTE_URBAN_POP
则：组合根发 Society/Map Command，把该聚落升级为城市/贸易节点（或让 GM 确认）
```

- **不做自动“造城”的另一种写法**：阈值不满足就什么都没有；允许长期为零。
- **降级/消亡**：人口/贸易跌回阈值以下可降级/撤销节点；城市化率随人口下降自然下降（D-025）。
- **中世纪稀疏贸易**：没有商人套利、价格固定 ⇒ 贸易量可能长期达不到阈值；这正是合法结果，不触发任何补偿。

### 3.4 贸易成本与运费

- 基础运输成本仍来自 `TransportTariff` + 道路/地形（现行 `MarketTopology.freightPerMilleBetween` 口径）。
- 新增聚集折价：

```text
baseFreightPerMille = TransportTariff.freightPerMilleBetween(from, to, roadBottleneck, distance)
discountFromHub     = hub(from).discountPerMille + hub(to).discountPerMille
effectivePerMille   = max(MIN_FREIGHT_PER_MILLE,
                          baseFreightPerMille × (1000 − min(900, discountFromHub)) / 1000)
```

- 折价只作用于**路线运费**，不改商品参考价；买卖限价、成交价仍按 `Market`/`MarketSettlement` 既有口径。
- 运力：`lane.capacityPerWindow = min(道路容量上限, Σ 该 lane 上 MerchantFirm 剩余运力)`；
  商号不足 ⇒ 未收运费/瓶颈读数，钱货不凭空消失。
- 承运人：`MerchantSettlement` 维持现行选择（服务半径/剩余运力/费率）；本设计只替换“节点/区”的来源与折价口径。

### 3.5 数据归属与切片边界

| 数据 | 权威 | 说明 |
|---|---|---|
| Region/hex 归属、GM 编辑 | `simos-map` | `Region` + `RegionMeta`；市场区直接读它 |
| 城市/市镇/人口 | `simos-social` | hub 的 urbanPopulation/city tier 来源 |
| 逐格市场价目表 | `simos-economy` | `EconomyData.markets`；不存区、不存 hub |
| 商号/运力/贸易读数 | `simos-economy`（`merchantFirms` + `MarketReport`） | hub 的 merchantCount/tradeVolume 读数来源 |
| hub/区/路线视图 | 组合根派生（`simos-app`） | 不落盘；每周期按 map+social+economy 现算 |
| 节点升级/降级命令 | 组合根 → map/social Command | economy 不直接写 social/map |

## 4. 数据流与调用次序

```text
每日/每窗口：
  ① MarketSettlement 在“市场区（map Region）+ 路线图（hub 间道路）”上撮合；
     区内即时成交；跨区建 ShipmentBatch + carrier + freight
  ② MarketReport 产出 fills/unfilled/routes/freight/merchant 读数

周期/窗口关账：
  ③ TradeAgglomerationBook.collect(map/social/economy 读数)
       → 逐 hub/逐区：tradeVolume、merchantCount、urbanPopulation、agglomeration、discount
  ④ MarketTopologyBook（新）构建：
       regions = map.Region（GM 编辑）
       hubs    = social 城市/市镇 + 已确认的升级候选
       routes  = 道路网 + hub 对；cost 应用 ③ 的折价
  ⑤ 组合根聚合升级判定：达到阈值 → 发 map/social Command 新建/升级聚落；
     跌回阈值 → 降级/撤销；未达阈值 → 不动
  ⑥ 下一窗口的 MarketSettlement 用新 hub/折价重算
```

## 5. 接口/契约

### 5.1 新类型（建议，具体命名可在实现时按仓库既有拼写纪律定）

```java
// 行政市场区（地图 Region 的投影；不落盘）
record MarketJurisdiction(RegionId regionId, String typeTag, Set<HexCoord> members) {}

// 派生 hub（不落盘）
record TradeHub(
    String hubId, RegionId regionId, HexCoord anchor, String tier,
    long urbanPopulation, long merchantCount, long cumulativeTradeVolume,
    long agglomerationDiscountPerMille) {}

// 派生路线（不落盘）
record MarketRoute(
    String fromHub, String toHub, long capacityPerWindow, long travelTicks,
    long costPerUnit, int lossPerMille) {}
```

### 5.2 `MarketTopology` 的替换点

- 旧的 `MarketTopology.of(nodes, markets, hexes, moveCost, ...)`（半径归属）**保留为旧测试兼容路径**，
  但生产路径（组合根）改为新的“显式区归属 + hub + route”入口：
  ```java
  MarketTopology.ofNetwork(
      List<MarketJurisdiction> jurisdictions,
      List<TradeHub> hubs,
      List<MarketRoute> routes,
      Map<HexCoord, Market> markets,
      ToIntFunction<HexCoord> moveCostAt,
      ToIntBiFunction<HexCoord,HexCoord> roadBottleneckBetween,
      ToIntFunction<HexCoord> nearestHubDistance,
      TransportTariff tariff,
      ToLongBiFunction<HexCoord,HexCoord> cityDiscountPerMilleBetween,
      ToLongBiFunction<HexCoord,HexCoord> ruralPenaltyPerMilleBetween)
  ```
- `regionOf(hex)` 从“最近节点成员表”改为“查显式 Region 归属表”；查不到 ⇒ 空/退化（不猜）。
- `adjacent(...)` 从“节点 anchor 距离 ≤ rA+rB+1”改为“route graph 上是否有一条有效 MarketRoute”；
  没有道路/路线 ⇒ 不跨区。
- 失败语义：区没有 hub 且没有 route ⇒ 只有区内交易；坏数据（重复 hex 归属、route 端点无 hub）⇒ fail-closed 具名抛。

### 5.3 与 D-024 预期利润的接法

- `ExpectedProfitBook.merchantProspect` 的 lane 需求/费率改为读**新 route graph**（路线需求、未用量、折价后的运费）；
  没有 route 或 route 容量为 0 ⇒ `NO_LANE`/`NO_MERCHANT_CAPACITY`，不假装有收入。
- 生产 mode 的预期利润继续按 D-024 的配方/需求/价格计算，不因本设计改变。
- `MarketDemandBook` 的市场区归属改为按 map Region 聚合（同一 Region 的 hex 共享需求池），不再按“节点半径”。

## 6. 版本、激活与旧数据

- 本设计**不新增持久组件**；Region 归属是 map 权威，hub/route 是派生件。
- 旧的“半径划区”行为在新 profile/版本位下关闭；旧的已落盘世界若仍带 “seven-hex-v2” 需要 GM 重置（沿用 D-024 的“旧档作废”口径）。
- 如果实现需要区分新旧拓扑入口，建议用 profile/版本位 `market-network-v1`，seeder/激活门一并声明；
  不在旧档上做兼容迁移。

## 7. 验收判据

### 7.1 区归属

1. GM 修改 map `Region.hexes` 后，同一批 hex 的 `MarketJurisdiction.members` 逐格一致；不再出现“最近节点”改变归属。
2. 一个 Region 内所有 hex 的 `regionOf(hex)` 恒返回该 Region；跨 Region 的 hex 不因半径相同而误并。
3. 没有 hub 的 Region：区内交易可发生（有 Market 价表时）；跨区路线为空；不抛、不造默认 hub。

### 7.2 节点生成（聚集）

1. 构造两个区/两个聚落 + 一条路：初始 hub 跟随 social 聚落；如果贸易量/商人/人口均低于阈值，**不生成新 hub**（允许长期为零）。
2. 人为把某聚落的 tradeVolume/merchantCount/urbanPopulation 推过阈值（通过真实成交/商号迁移，而不是直接改数）⇒ 组合根发出升级 Command；
   升级后 `agglomerationDiscountPerMille` 上升、同一条路线的 `effectiveFreightPerMille` 下降。
3. 把人口/贸易降回阈值以下 ⇒ 允许降级/撤销；城市化率随人口下降（D-025）。
4. 全程无“凭空造城/凭空造节点”。

### 7.3 merchant 运费与聚集折价

1. 两个 hub + 一条真实道路 + 一边供给/一边缺口 ⇒ 出现 `ShipmentBatch`、`crossRegionFills`、`freightPaid > 0`；
   principal 收到 `CARRIER_FEE`、porter 拿到工资、upkeep 进成本。
2. 同一路线在其他条件不变、只提高 hub 聚集折价时，`effectiveFreightPerMille` 下降、承运量/成交上升（正反馈方向）。
3. 没有道路/没有 route ⇒ 仍是 `NO_LANE`；没有商号 ⇒ `NO_MERCHANT_CAPACITY` / 未收运费，不凭空收钱。
4. 单 hub 世界：允许 `freightPaid=0`，不判失败（D-025）。
5. 饥荒/城市衰落合法：城市可清零；只验人口/货币/资产守恒与派生城市化率读数。

## 8. 已知缺口与风险

1. **商人投机/跨区套利未做**（本批非目标）：没有价差套利 ⇒ 贸易量可能永远过不了节点升级阈值；这是用户明说的正常结果，不得用假贸易填充。
2. **Nation 地方政府辖区还没有完整模型**：本设计只让 `Region.meta.annexedBy`/tag 作为接口位；地方政府成立/辖区变更的接线在后续批次。
3. **hub 升级/降级需要跨切片命令**：本批只设计“读数 → 组合根 policy → Command”的顺序；具体社会侧城市创建/升级命令若不存在，需要先补 GM/社会命令面。
4. **固定价格**：跨区价差只表现为买卖限价差异，不会自动产生套利；动态价格仍属未来批次。
5. **道路网稀疏**：真实地图可能没有道路/hub 对；路线图可能长期为空，`freightPaid=0` 是合法结果。
6. **性能**：route graph 的 hub 对数量受聚落数限制（不是每格），预期远小于半径划区的 O(hex × node)；仍需在真实大图上测。
7. **旧半径路径的测试迁移**：现有 `MarketTopologyBook` 半径行为的测试要么保留为旧兼容用例，要么按新入口重写；不把新旧口径混用。

## 9. 实施阶段与文件所有权（待用户确认后派单）

- **Phase 1（economy-api + economy + app 组合根）**：
  - 新增/替换 `MarketTopology` 的显式区归属入口；
  - `MarketTopologyBook` 改为从 map `Region` 构建 `MarketJurisdiction`，hub 从 social 城市/市镇派生，route 从道路 + hub 对派生；
  - 删除“最近节点 + radiusHex”生产路径（保留旧测试兼容入口）。
- **Phase 2（economy）**：
  - `TradeAgglomerationBook`（或等价读数）：逐 hub/区的 tradeVolume、merchantCount、urbanPopulation、discount；
  - `MarketSettlement` 应用聚集折价到路线运费；`MarketDemandBook` 按 Region 聚合需求。
- **Phase 3（app/social/map 命令面）**：
  - hub 升级/降级策略与 Command 发射；GM 可编辑区类型/归属；城市化率派生读数；
  - ≥2 hub 的真实小世界验收（merchant 运费 + 聚集反馈）。
- **Phase 4（测试）**：按 §7 判据逐条测试；旧半径行为只在旧兼容用例里保留。

本阶段文件所有权在派单时按 AGENTS §一.5 一阶段一代理、测试最后单独代理执行。
