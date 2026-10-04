# 单市场区经济循环设计（本批：先把一个市场区跑通）

> 状态：**设计已记录、待实现**（2026-10-06）
> 规则依据：`AGENTS.md` §一.8；用户裁定 **D-027**（同币即同区；分单 hex 贸易成本与市场总调控；跨市场区暂缓）。
> 关联文档：
> - `docs/superpowers/specs/2026-10-02-architecture-design-source.md` D-022 / D-023 / D-024 / D-025 / D-027
> - `docs/superpowers/specs/2026-10-06-expected-profit-demand-and-mode-entry.md`（预期利润/需求，D-024）
> - `docs/superpowers/specs/2026-10-06-market-regions-and-agglomeration-redesign.md`（D-026 跨区/聚集节点设计，**本批暂缓**）

## 0. 一句话目标

在一个市场区内跑通完整经济循环：生产/劳动/投入/收获 → 区内跨 hex 成交（带**单 hex 贸易成本**）→ **市场总调控** →
消费/出生死亡 → 债务/利息/偿还 → 家户按预期利润迁移（D-022/D-024）→ 流民与饥荒（D-023/D-025）。
本批**不**做跨市场区 lane / 在途 / 承运 / 聚集节点生成；同币国家常常就是一个市场区。

## 1. 需求来源（用户判定的约束）

1. **同币即同区**：两个市场区若用同一种货币，事实上就是一个市场区；一个国家内常常形成一整个市场区。
2. **跨市场区暂缓**：本批不做跨市场区贸易；先把单个市场区的经济循环按既有裁定跑通。
3. **两层必须分开**：
   - **单 hex 贸易成本**：货物在市场区内从一个 hex 到另一个 hex 的物流/通行成本（地形、道路、距离、城区折价），逐 hex/逐段计量；
   - **市场总调控**：市场区这一层的聚合规则（参考价/限价、总供需撮合、配额/配给、税费/准入、开市/闭市），按区施加一次。
   两层不得互相顶替。
4. **D-025 继续有效**：城市可以饥荒萎缩/归零；城市化率是 `urban/(rural+urban)` 的派生量。
5. **D-022/D-023/D-024 继续有效**：生产方式只通过转移/新建改变；偿还/资产随迁/流民语义；预期利润+需求不再依赖既存读数。

## 2. 现状与目标

| 维度 | 现状 | 目标 |
|---|---|---|
| 市场区 | `MarketTopologyBook.from` 从城市节点 + `radiusHex` 最近归属；同币多城会被拆成多个区 | **同币/同制度世界 = 一个市场区**；本批 `production-runtime` 12hex 强制单区；跨区暂缓 |
| 区内跨 hex 成交 | 同区成员 hex 之间可即时成交，**没有逐 hex 物流成本**、没有在途/运费 | 保留即时/区内撮合，但每条跨 hex 成交带**单 hex 贸易成本**（逐段计量；损耗/承运费至少一项可读） |
| 市场总调控 | `Market.prices` 逐格固定价；没有区级聚合调控 | 新增区级 `MarketRegulation`（参考价/限价/配额/开闭市/税费）；按区施加一次 |
| 商人 | 无跨区业务时 `freightPaid=0`；单区内没有承运概念 | 单区内可选承运人（商号/组织）承担逐 hex 运费；无承运人时成本以实物损耗/明标读数表达，钱货不凭空消失 |
| 城市存亡 | 上一轮测试要求城市人口 ≥ 初始 50%（已按 D-025 撤销） | 城市可饥荒归零；城市化率为派生读数；不保城市 |
| 验收世界 | 12hex 只有一个城市、同币 | 单区 12hex 是本批主夹具；`merchant` 跨区运费不作为判据 |

## 3. 组件与数据模型

### 3.1 市场区（单区）

- `MarketTopology` 增加**单区入口**（或把生产路径切到单区口径）：
  ```text
  MarketTopology.singleRegion(
      Map<HexCoord, Market> markets,
      Set<HexCoord> marketHexes,              // 本批 = 有 Market 的全部 hex
      ToIntFunction<HexCoord> moveCostAt,
      ToIntBiFunction<HexCoord,HexCoord> roadBottleneckBetween,
      TransportTariff tariff)
  ```
  产出：一个 `MarketRegion`（成员 = 全部有市场的 hex），没有跨区 lane；`regionOf(hex)` 对成员返回该区。
- 同币判断不在 economy 内做城市半径计算：组合根在 `MarketTopologyBook` 里按"所有 market.numeraire 相同 / 或 GM 指定"选择单区入口。
  本批 production-runtime 世界统一走单区；旧半径入口只留给旧兼容测试。

### 3.2 单 hex 贸易成本（本批第一版的具体口径）

新增 `HexTradeCost` 纯策略（`simos-economy/time/HexTradeCost.java`）：

```text
同格（from == to）：
  lossPerMille = 0，costMilliPerUnit = 0
跨格（同一区内）：
  lossPerMille = min(MAX_HEX_TRADE_LOSS_PER_MILLE,
                     HEX_TRADE_LOSS_PER_MILLE_PER_HEX
                     × from.distanceTo(to)
                     × max(1, topology.moveCostAt(to)))
  costMilliPerUnit = 0        // 第一版单区内不产生货币运费；成本以实物损耗表达
```

具名默认（agent 可命名常量，但数值先按此写死并具名注释）：

```text
HEX_TRADE_LOSS_PER_MILLE_PER_HEX = 2
MAX_HEX_TRADE_LOSS_PER_MILLE     = 500
```

- **第一版不做单区内货币承运费**：区内即时成交路径没有 `MerchantFirm` lane，货币运费留给后续批次；这样避免“没有承运人时钱付给谁”的未决问题，同时保证钱货守恒。
- 跨格即时成交时：`loss = fill.quantity × lossPerMille / 1000`；买方**收到净量**、按毛量付款（与既有“买方承担在途损耗”口径一致）；`loss` 记入 `ProductionLedger.losses`（损耗账户），并写进该 `MarketReport.Fill.lossMilli`。
  ★ **守恒实现口径（唯一写口 + 非换手落点）**：货腿由唯一 applier 走**毛量** `sell → buy`；
  随后由买方侧按 `loss` 做一次**非换手扣减**（与 `MarketSettlement.loadInTransit` 把买方货物移进在途是同一类“货物离开账户但未换手”的落点），
  并在 `ProductionLedger.losses[TRANSPORT_LOSS_ACCOUNT]` 留下唯一凭据。于是 `Σ余额 + losses` 守恒、卖方毛量出、买方净量入。
  **不得**只把货腿写成 `quantity − loss` 再记一笔 loss（那会让损耗凭空多出来，实测守恒式 `Σ余额+losses = 初始 + loss`）。
- 复用现有字段，不新增 `Fill` 字段：`lossMilli` 表达单 hex 损耗；`freightPerUnitMilli`/`freightMilli` 仍只服务跨区承运（本批单区恒 0）。
- `MarketReport` 增加只读聚合辅助（不改 record 形状）：
  `immediateCrossHexFills()`、`immediateCrossHexLossMilli()`（对 `immediate && from != to` 的 fill 计数/求和）。
- 失败语义：同格/无拓扑/无市场 ⇒ 0；坏数据（负距离等）由既有类型守卫 fail-closed。

### 3.3 市场总调控（本批第一版的具体口径）

新增区级 `MarketRegulation`（`simos-economy/time/MarketRegulation.java`；纯值类型，**本批不落盘**）：

```text
record MarketRegulation(
    HexCoord anchor,                        // 区级参考价锚格；单区 = 规范序第一个有市场的 hex
    Map<CommodityId, Long> referencePrices, // 空 = 沿用原 Market.prices（出厂默认）
    long bidPerMille, long askPerMille,     // 0 = 沿用 Market.BID_PER_MILLE/ASK_PER_MILLE
    Map<CommodityId, Long> quotaPerWindow,  // 空 = 无配额
    Map<CommodityId, Long> tariffPerUnit,   // 空/0 = 无税费
    boolean open,                           // 默认 true；false = 本轮不撮合
    List<String> rules)                     // 具名制度标签，只读
```

- 默认实例 `MarketRegulation.defaults(anchor)`：`referencePrices` 空、bid/ask=0、quota/tariff 空、`open=true`、rules 空。
  **默认行为与现状逐值相同**：`MarketSettlement` 仍读各 hex 的 `Market.prices` 与既有 `BID_PER_MILLE/ASK_PER_MILLE`。
- 施加点：`MarketSettlement` 新增可选 `MarketRegulation` 入参（旧调用委托为默认实例）；**每轮按区读一次**，
  区内所有 hex 共用。`EconomySettlement` 生产路径先传 `MarketRegulation.defaults(单区锚格)`；测试/未来 GM 可直接构造自定义实例。
  建议把 regulation 放进 `MarketSettlement.MarketRound`（逐轮瞬态，不落盘），`clearOncePerCycle` 从 round 里读；旧调用点不受影响。
  自定义 regulation 时：
  1. `referencePrices` 非空 ⇒ 覆盖该区所有 hex 的参考价（区内一价 + 单 hex 损耗另计）；
  2. `quotaPerWindow` 非空 ⇒ 该商品本轮该区总成交量上限（超出部分记 `Unfilled` 具名原因 `REGULATION_QUOTA`）；
  3. `open=false` ⇒ 该区本轮不撮合（`MarketReport.empty` + 具名 trigger/readout，不抛）；
  4. `tariffPerUnit` 非空 ⇒ 成交时对买方加一条区级费用腿（收款方本批留空：只记读数、不凭空铸钱；后续接地方政府）；
  5. `bidPerMille/askPerMille` 非 0 ⇒ 覆盖 `Market` 的挂牌价差。
- 市场总调控**不得**被实现成逐 hex 的 extra cost；单 hex 贸易成本也不得承担价格/配额职能。
- 未决项（本批明确不做）：税费收款方、配额的管理主体、regulation 落盘/GM 工具。

### 3.4 循环各环节

1. **生产/劳动/投入/收获**：沿用 `EconomySettlement` 现有日序；D-024 预期利润/需求继续生效。
2. **区内市场**：`MarketSettlement` 在单区上撮合；跨 hex 成交套 §3.2 成本；market regulation 套 §3.3。
3. **消费/出生死亡**：`withDailyNeed`/`PopulationDynamics` 不变；粮/布缺口合法（D-025）。
4. **债务/利息/偿还**：D-023 不变。
5. **周期关账**：`OrganizationProfitBook` → `MarketDemandBook`/`ExpectedProfitBook` → `ModeMigrationPolicy` → `ModeMigrationSettlement`；D-022 不变。
6. **城市/城市化**：城市人口可下降；`urbanization = urban/(rural+urban)` 作为读口派生量；不做城市维持硬判据。

## 4. 数据流与调用次序

```text
每日：
  ① 到货/自动组织/投入开扣/劳动分配/生产/收获（现有）
  ② MarketSettlement（单区）：
       读 MarketRegulation（区级一次）
       → 区内撮合买卖（沿用现有 buyer/seller 订单）
       → 跨 hex 成交：套 HexTradeCost（第一版 = 实物损耗；承运人货币运费留后续）
       → 写 MarketReport：fills/unfilled/hexTradeCost/transportLoss
  ③ 关系实付/消费/出生死亡/债务/利息/偿还（现有）

周期关账：
  ④ OrganizationProfitBook.collect（对账）
  ⑤ MarketDemandBook.build（按单区聚合需求）
  ⑥ ExpectedProfitBook（家户 × mode 预期利润）
  ⑦ ModeMigrationPolicy.plan → ModeMigrationSettlement.apply（D-022/D-024）
```

## 5. 接口/契约

- `MarketTopology.singleRegion(...)`：新增；旧城市半径入口保留为兼容路径，生产路径不再用。
- `HexTradeCost`：纯函数/策略，至少暴露 `lossPerMille(from,to)`（`<=0` 明确拒绝/0=同格）；
  第一版 `costMilliPerUnit` 恒 0（单区内不产生货币运费），坏数据（缺格、负距离）fail-closed 具名。
- `MarketRegulation`：区级不可变值；缺省 = 锚格 `Market.prices` + 现有 bid/ask + open=true + 无配额/税费。
- `MarketReport`：**复用现有 `Fill.lossMilli`** 表达单 hex 损耗；新增只读聚合辅助
  `immediateCrossHexFills()` / `immediateCrossHexLossMilli()`；不新增 `Fill` 字段。
- 失败语义：无市场/闭市 ⇒ 不撮合；无价 ⇒ 不成交；无承运人 ⇒ 只扣实物损耗、不收运费；配额用尽 ⇒ 未成交具名原因。

## 6. 版本、激活与旧数据

- 单区拓扑与市场调控是本批新行为；production-runtime 档版本仍/再升（`seven-hex-v3` 或等价），旧档按既定"直接作废、GM 重置"处理。
- 不新增 `EconomyData` 持久组件：单区是派生视图；`MarketRegulation` 先作为组合根 policy/默认值，未定是否落盘（本批建议不落盘，GM 工具后续再加）。
- 旧半径入口只服务旧测试；生产路径不得混用。

## 7. 验收判据

### 7.1 单区拓扑

1. 12hex production-runtime 世界中，所有有市场的 hex 属于**一个** `MarketRegion`；`regionOf` 无第二区。
2. 同币多城世界（若夹具构造）仍是一个区；跨区 lane/在途/运费不是本批判据。
3. GM/夹具改 `Market` 币种造成不同币种时，才允许出现第二区（本批只设计接口，不实现跨区撮合）。

### 7.2 单 hex 贸易成本

1. 同格成交 `lossMilli=0`；跨 hex 即时成交 `lossMilli>0` 可读，且随距离/地形变化方向正确。
2. 第一版单区内**不产生货币运费/CARRIER_FEE**：只扣 `transportLoss` 实物损耗并进损耗账户；钱不凭空消失、货不凭空消失。
3. 跨区承运路径本批不改、不作为本批判据；后续批次再把货币运费接到单区/跨区承运人。
4. 成本/损耗不进入 `Market.prices` 参考价本身（两层分离）。

### 7.3 市场总调控

1. `MarketRegulation` 每轮按区读一次；改参考价/配额/开闭市会同时影响区内所有 hex。
2. 配额用尽/闭市产生具名未成交原因；不得静默跳过。
3. 单 hex 贸易成本变化不改变区级参考价/配额读数，反之亦然（两层可独立观测）。

### 7.4 单区经济循环

1. 生产/劳动/投入/收获、区内成交、消费、出生死亡、债务/利息/偿还都在 3650 tick 里真实发生（读数非空）。
2. 家户迁移按 D-022/D-024：源户 mode/standing/org/unit.modeKey 不改；合并/新建/消亡读数可读。
3. DISPLACED 无劳动配额/无自动组织（D-023）。
4. 货币/资产守恒、无负值。
5. 城市可以饥荒归零；`urbanization` 派生读数打印；不设城市维持阈值（D-025）。
6. 不要求 `freightPaid>0`、`shipments>0`（跨区暂缓）；单区内的承运费（若有承运人）单独读数。

## 8. 已知缺口与风险

1. **跨市场区整体暂缓**：D-026 的节点生成/聚集折价/跨区 lane 不在本批；本批只留接口位。
2. **同币多区判定**：单区入口需要组合根明确"同币/同制度"输入；银行/关税区/不同货币的区分留后续。
3. **单 hex 贸易成本的承担方**：第一版无承运人时用实物损耗；后续要不要收货币、收给谁（市场机构/地方政府）需制度设计。
4. **市场总调控的持久化**：本批先做 policy/默认值；GM 工具、落盘、命令面后续批次。
5. **价格口径**：区级参考价与单 hex 成本的先后（成本加在价内还是价外）需在实现时写死唯一口径并测试；不得两处各算一遍。
6. **动态价格未开**：固定价 + 市场调控政策；价格随供需调整仍属未来批次。
7. **社会侧城乡迁移未接（P8/P9）**：economy 侧迁移读数与 social 批次仍可能不一致；本批继续按 §9.1 具名记录。

## 9. 实施阶段与文件所有权（待确认后派单）

- **Phase 1（拓扑单区）**：`MarketTopology.singleRegion`（economy）+ `MarketTopologyBook` 单区选择（app）；
  生产路径切单区；旧半径入口保留兼容。
- **Phase 2（单 hex 贸易成本）**：`HexTradeCost` 策略 + `MarketSettlement` 逐成交成本/损耗 + `MarketReport` 读数。
- **Phase 3（市场总调控）**：`MarketRegulation` 值类型 + 区级施加 + 配额/开闭市/税费接 `MarketSettlement`。
- **Phase 4（测试/重跑）**：更新 `RealTwelveHexProductionRuntime3650Test` 为 D-025/D-027 判据；economy 全量 + 12hex 3650；
  单区成本/调控读数逐条断言。

按 AGENTS §一.5：一阶段一个写代码代理（只写 main、只过编译、不写测试、不 commit）；测试最后单独代理。
本文件未确认前不派实现。
