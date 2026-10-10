# 实现架构账本：A2 —— 运输服务牌价 + 跨格货单派生需求 + 服务成交（并回标准管线第二步）

> 责任区：**A2（派生需求与成交）**，约束设计书 `docs/superpowers/specs/2026-10-10-haul-service-commodity-design.md`（v1.1）
> §3.3（需求由跨格货单派生）/§3.4（退役清单，本批只停用一部分）/§5（I-H1..I-H7）/§6（T-H2/T-H3/N-H1）/§7 A2 行。
> 只写生产代码；只到编译过（未跑 test/verify/world）。前置：A1（`a15fb3ec`）已把 `haul` 进词表、`trade@hex` 产出服务。
> 用户原话（逐字，设计书 §1）：「运输服务我不是说算商品吗，只是这个商品的需求需要额外通过其他已有商品的购买来计算，
> 要我选的话我肯定选A」「算了，把运力也改成可计算的吧……按照市场上最低价的运力提供商买运力」
> 「运力作为特殊商品不可储存转卖，只能计算后在生产环节统一兑现、自动算收益」

---

## 1. 关键调查结论（file:line → 结论 → 影响）

| # | 证据 | 结论 | 影响 |
|---|---|---|---|
| I-1 | `EconomySettlement:2013`（**唯一**生产装配点）、`MarketSettlement:1428/:4131`（`empty()` 夹具路径） | 运力池在生产路径上**只有一个**构造点 | 供给面的改写只需动这一处 + 池内一处；夹具/旧调用方走 4/5/6 参重载 ⇒ 逐值不变 |
| I-2 | `MerchantCapacityPool:625`（改后 `remainingWorkMilli = workBudgetMilli`；改前 = `capacity.capacityMilli()`）、`:863-875`（`maxGoodsFor` / `workConsumedBy` / 每趟扣 1,000 毫工具） | 池的"运力预算"在报价口径下**已经是毫商品·程**、分配时按 `workPerGood` 扣 | 服务货（毫运输服务）可以**1:1** 当预算用（A1 的单位锚）⇒ 不需要第二套换算 |
| I-3 | `ProductionEfficiencyBook.evaluateHarvest`：`scaleBase = min(laborScale, capPlanned)`，`laborScale = (cycleLabor/D)/laborPerUnit` | `laborPerUnit > 0` 且**没有劳动分配 ⇒ scale = 0 ⇒ 一件服务也产不出来** | "服务货从哪来"与"劳动队列给不给跑商配额"是同一个问题（本批最大的一处口径张力，见 §4 D-A2-1） |
| I-4 | `LaborQueueBook:255-259,279,365`（`net = bidPriceOf(产出) − Σ askPriceOf(投入)/1000`，`MIN_NET_PER_LABOR_SCALED = 1`）、`Market.BID_PER_MILLE=990`、`ASK_PER_MILLE=1010`、`EconomySeeder:LBR/工具价=20` | 跑商要拿到劳动配额需 `⌊牌价×0.99⌋ − 2 ≥ 1` ⇒ **牌价 ≥ 4**（3 ⇒ 0 ⇒ IDLE；2 ⇒ −1） | 牌价 = 4 是本批唯一能让机制**真的跑起来**的最小整数（牌价定理由此可逐值复核） |
| I-5 | `MerchantCapacityPool.select:863-875`（池内只算不写账）、`MarketSettlement.settleHaulRuns`（`consumeForLoss` 走 `TOOL_BURN_ACCOUNT`，实扣在 `executeTrade` 之后） | 池内**只算不写账**；实扣（工具）在 `executeTrade` 之后由 `settleHaulRuns` 落 | 服务交付也按同一形制：池扣预算、`executeTrade` 落账（同一张活表、同一线程） |
| I-6 | `MarketSettlement:6707`（货腿）、`:6756`（货款钱腿）、`:6786-6790`（运费腿 reason 的三元选择） | 运费腿此前**必铸** `TransferReason.CARRIER_FEE` | I-H5 的落点就是把这一处换成服务成交的钱腿（`MARKET_TRADE`），两条腿由一个三元表达式**结构互斥** |
| I-7 | `EconomySettlement.consumeForLoss:8809`（账户减 + 损耗账加，"非换手损耗的唯一写口"）、设计书 §5 I-H2 | "服务货物被消耗"的**既有合法落点**已经存在 | 服务不必造新写口：卖方账户 −Q、`market-haul-service` 损耗账 +Q，Σ余额 + losses 守恒 |
| I-8 | 全部 4 处 `freightUnitMilli(` 调用点：两处**路线构造**（`:5305/:5551` 一带）+ 两处**结算/读数**（`carrierChargeSplit` 的 `:7282`、`settleHaulRuns` 的 `:7458`）；下游一律读 `route.freightPerUnit` | "单位运费"全仓只有一个传播载体 | 只改两处构造点即可让**预判（可负担量/总价上限）与结算**用同一个数 ⇒ 不产生"判得起却付不起" |

## 2. 实现架构（数据流与调用次序）

**新文件** `simos-economy/.../time/HaulService.java`（A2 的**口径唯一拼写点**，只读、无状态）：
`HAUL_COMMODITY` / `SERVICE_CONSUMED_ACCOUNT("market-haul-service")` / `NO_SERVICE_SUPPLY_REASON` /
`pricedAt(Market)`（成市判据）/ `unitFreightMilli(服务耗用‰, 牌价)`（单位运费，与既有 `freightUnitMilli` 同骨架）。
派生需求量**不在这里重写**：它就是既有的 `CapacityDemand.workMilliOf`（`CapacityDemandBook:108-109` 与池的
`workConsumedBy` 两处读同一份算式）。

```
① 创世牌价      EconomySeeder:820 MARKET_PRICE_HAUL=4 → :1833 出厂价表末位追加（既有五项相对序不动）
② 劳动队列      跑商家户 trade unit：outputPriced=true（有牌价）+ net=1 ⇒ GRANTED ⇒ 一个周期后产 97,000 毫服务/城
③ 运力池装配    EconomySettlement:2023 传 haulServiceHexes(base)（= 给 haul 定过价的格）→ 池的 workBudget
                = max(0, haul 现货 − haul 冻结)（服务货口径；其余格仍是 劳动+工具 算式）
④ 路线          MarketSettlement:5305/5551 发货格成市 ⇒ freightPerUnit = HaulService.unitFreightMilli(耗用‰, 牌价)
⑤ 需求派生      executeTrade 取 workPerGoodPerMille → 池 select（限价升序 → 议价权 → 家户 id）分配
                ⇒ CapacityDemandBook.record(...) 记 requested/served 与 demandWork/servedWork（既有簿）
⑥ 服务交付      executeTrade:6650 → deliverHaulService:7153：逐条目 consumeForLoss(卖方, haul, consumedWork)
                （账户减 + market-haul-service 损耗账加）；取不到货 ⇒ ERROR + 本笔 return 0（不成交、不动账）
⑦ 钱腿          :6790 三元表达式：成市 ⇒ MARKET_TRADE（服务成交，CARRIER_FEE 停铸）；不成市 ⇒ 原样 CARRIER_FEE
⑧ 日志          TRACE HAUL_SERVICE_DELIVERED/:6819 HAUL_SERVICE_PAID；INFO :1705 HAUL_SERVICE_SETTLED；
                ERROR :1729 HAUL_SERVICE_DELIVERY_FAULTS；DEBUG 归因进了池的 laneBlockedReason（新增
                no-haul-service-in-shipping-hex）
```

## 3. 关键判断

- **D-A2-1｜牌价 = 4 是"能让机制跑起来"的最小值，不是"等于改前运费"的值**。改前单位运费口径（粮、3 hex、脚夫 25‰）
  = 2 毫银/商品单位，折成服务单价恰等于本产业的**工具成本**（100 毫工具/规模·周期 ÷ 1000 毫服务 = 2）⇒ 牌价 2 时
  跑商净收益为 0、**拿不到劳动配额 ⇒ 一件服务也产不出来**（I-3+I-4 的合流），跨格货单会一律"买不到运力"。
  取 4（净收益 1 毫银/规模）是唯一能让"派生的需求真的被服务"的整数档。★ 代价如实记：买方的单位运费
  ≈ 改前的 2.1 倍（粮 5 毫/商品单位 vs 2）⇒ 跨格贸易的量与结构会变（A4 的第一项对照组）。
- **D-A2-2｜"服务成市"的开关 = `Market.hasPrice(haul)`，不是"牌价 > 0"**。本仓明文区分"从未定价 ⇒ 不交易"与
  "明确 0 价 ⇒ 免费交易"（`Market:88-115`（`hasPrice`/`isFree`/`priceOf` 的三态口径））⇒ 用价格数值当开关会把"GM 设成免费服务"静默读成"没有服务市场"。
- **D-A2-3｜服务的货不走"转移给买方"，而是当场消耗**（用户原话「运力…不可储存转卖」）。设计书 §3.1 写"可存
  （作为普通商品）"⇒ **本批按用户原话取"成交即消耗"**（§一.8.1 原话优先），落点是既有的 `consumeForLoss`，
  损耗账具名为 `market-haul-service`。副作用：买方不会累积服务货、不可能二次转卖（`ordersFor:2169` 也因此
  在成市的格不挂服务单——否则卖家自己的卖单会冻结要卖的服务，与交付点的"可用量"判据结构性冲突）。
- **D-A2-4｜不新写需求量算式**：需求量 = `CapacityDemand.workMilliOf`（毫商品·程）；服务商品账上的"毫"与它 1:1。
- **D-A2-5｜照旧不碰铁律 5**：`haul` 是既有 `Map<CommodityId,…>` 的新键；本批**没有**新/改持久状态组件、无 Codec/
  ChangeSet/往返改动（`haulsService*` 那几个累加器都是 `MatchContext` 的**瞬态**字段，与 `capacityDemands` 同款）。
- **D-A2-6｜池的供给面改动只走"新 7 参重载"**：4/5/6 参重载一行未改 ⇒ 夹具/纯状态读者/旧调用方逐值不变。

## 4. 偏离记录（与约束设计书不一致处及原因）

- **D-1｜§3.1"可储存（作为普通商品）"未采**：见 D-A2-3（用户原话"不可储存转卖"优先）。
- **D-2｜§3.3"服务商品走市场自然议价"落成"池的既有提供者队列 + 市场牌价"**：买方**不另挂服务订单**（需求是派生的，
  用户原话"额外通过其他已有商品的购买来计算"），卖方也不进订单簿；"按最低价提供者买"由**既有**"限价升序 →
  议价权降序 → 家户 id 升序"实现（不新增任何队列），成交价统一取**市场牌价**。★ 完整的"服务订单簿"（服务卖单 +
  派生买单进同一个 `pairUp`）需要把派生需求摊平到"每格每 lane 一份挂单 + 独立冻结"，属 A3 的重构面，本批未做。
- **D-3｜§3.4 的退役清单本批只"停用"一项**：`CARRIER_FEE` 腿在成市的 lane 上停铸；运力池/报价簿/需求簿/`MerchantHaul`
  趟耗/`MerchantProfitBook`/`LaneUnserved*` 全部**保留**（A3 的整体退役不在本批）。
- **D-4｜I-H3"无跨格需求 ⇒ 逐值不变"只兑现了两条腿**：
  - ✅ **无牌价**（本格没给 haul 定价）：`haulServiceAt=false` ⇒ 路线/单位运费/钱腿/池预算**一行判据都不变**
    （逐表达式可核；经济模块的既有夹具正是这种市场 ⇒ 那批测试逐值不变）。
  - ✅ **无跑商家户**：池空 ⇒ 跨格路线根本不建（既有 `LOGISTICS_CAPACITY`）；劳动队列里也没有 trade unit。
  - ❌ **"无跨格需求但有牌价"⇒ 不是逐值不变**：牌价一落，跑商家户的 `trade` 劳动 offer 就从"保留"转为"排队"
    （`LaborQueueBook.isPreservedByQueue:551-557` 的第三腿 `!outputPriced` 不再命中）⇒ 该户把劳动投给跑商、每周期
    产出服务（无人买时在货物账里累积）。**这是 A1 D-2 预警的那条**，本批**没有**消除它：要消除必须让"劳动队列的
    预期收益"看得见**需求**，而需求只在市场轮（生产阶段之后）才存在 ⇒ 需要一个跨阶段的需求信号（前瞻簿/新状态），
    那会触碰铁律 5 的"新增持久状态组件"红线 ⇒ **本批停手上报，不自行造**。★ 影响面：只有**同时**满足"有牌价 +
    有 merchant 家户"的世界；"有牌价但没有任何跨格需求"的世界里，变化被限制在"该户的劳动分配 + 服务货累积"
    （没有钱动、没有货动、没有跨格成交）。
- **D-5｜前 120 天（首个周期）没有运力**：服务是按周期产出的（`cycleDays=120`），创世不给服务库存 ⇒ 世界在第一次
  收获前"一件服务也没有" ⇒ 跨格货单一律不成交（具名 `no-haul-service-in-shipping-hex`）。**如实记**：这与改前
  "跨格运力从第 1 天就有"是**结构性差异**。★ 未做创世服务库存（那要新造一个创世数量，且要动按阶层发开缸库存的
  落点）⇒ 留给 A4 与用户裁定（候选口径：`HAUL_PER_TRADE_UNIT_CYCLE × MERCHANT_CATTLE_PER_CITY × 1000` =
  一个周期的产出 = 100,000 毫服务/城）。

## 5. 会改变数值行为的清单（含缺省中性论证）

1. **创世价表 5 → 6 项**（末位追加 `haul=4`）⇒ 所有出厂市场多一行价；`CompactThreeNationsWorld` 的
   `commodities` 读数 6→7、`ApiViews.commodityIds` 6→7（A1 已改词表，本批只是让价表跟上）。
2. **跑商家户的劳动分配**：从"trade 不排队、劳动闲置"变成"trade 排队并拿到配额"（牌价 ≥ 4 的必然结果）。
3. **跨格成交的运力硬上界**从"劳动+工具派生的运力"换成"**手上的服务货**"（服务成市的格）；服务为 0 ⇒ 不成交。
4. **买方运费水平**：单位运费 = `max(1, ⌈耗用‰ × 牌价 ÷ 1000⌉)`（粮 3-hex：5 毫/商品单位，改前 2）。
5. **`CARRIER_FEE` 腿在成市 lane 上不再铸**（改铸 `MARKET_TRADE` 服务成交腿）⇒ 按 reason 分组的既有读数/断言会变。
6. **新增两处读数/日志**：`HAUL_SERVICE_SETTLED`(INFO)/`HAUL_SERVICE_PAID`(TRACE)/`HAUL_SERVICE_DELIVERED`(TRACE)/
   `HAUL_SERVICE_DELIVERY_FAULT(S)`(ERROR)/池归因 `no-haul-service-in-shipping-hex`(DEBUG)。
- **缺省中性（逐表达式）**：`haulServiceAt(ctx,hex) = HaulService.pricedAt(markets.get(hex))`；该值为 false 时
  ① 路线构造走 `freightUnitMilli`（原式）② `deliverHaulService` 不被调用 ③ 钱腿 reason 仍是 `CARRIER_FEE`
  ④ 池的 `workBudgetMilli` 仍 = `capacity.capacityMilli()`（`haulServiceHexes` 不含该格）⑤ `ordersFor` 的
  服务分支不触发 ⑥ 三条 A2 日志的条件全假 ⇒ **除"价表多一行"外无既有数值变化**。★ 真实验证（两套 world 360 tick /
  N-H1 状态对照）留 A4，本批未跑。

## 6. 会让既有测试断言失效 / 需要新护栏的清单（给测试 Agent）

- **预计不受影响**（已核）：`simos-economy` 各夹具用**只给粮定价**的市场（`MarketSettlementFixtures.grainMarket:89`）
  ⇒ 服务不成市 ⇒ 全部走回改前路径（含 `MarketSettlementSingleHexLossTest` 的 `CARRIER_FEE` 断言、
  `MerchantCapacityAcceptanceTest` 的池读数、`PortPolicyMerchantLaneAcceptanceTest`）。池的 4/5/6 参重载签名未改
  ⇒ 夹具仍编译。
- **预计失效**（用真实播种世界、且断言下列事实的用例）：① 断言出厂价表"恰 5 项"/市场价表键集的内容；
  ② 断言 `CompactThreeNationsWorld` 的 `commodities`/`commodityIds` 快照；③ 断言"首个周期内就有跨格成交"
  （前 120 天现在是 0）；④ 断言跑商家户/具体家户的劳动配额明细；⑤ 断言 `CARRIER_FEE` 腿出现在真实世界的
  `Transfer`/读数里（成市世界已改 `MARKET_TRADE`）；⑥ 任何 golden 数字对照（运费、跨格成交量、商号利润）。
- **需要新增护栏**（当前是缺口）：`haul` 牌价恰一行且**末位**、前五项相对序不变（`factoryPrices()`）；
  `HaulService.unitFreightMilli` 与 `MarketSettlement.freightUnitMilli` 在 `牌价=(1000+承运成本‰)/1000` 处的等价性
  （本批"不新造量纲"的判据）；成市 lane 上**不得**出现 `CARRIER_FEE` 腿（负向）；服务为 0 ⇒ 该笔不成交且
  `haul` 货物账**不为负**（I-H2）；`Σ haul 账户 + market-haul-service 损耗 = 常数`（I-H2 守恒式）。

## 7. 未完成 / 未验证

1. **未跑** `test` / `verify` / 真实 world（任务书纪律：只到编译过）⇒ §5 的数值结论**全部是静态推演**（逐条给了
   file:line），**没有任何运行时数字**。缺省中性的真实对照（N-H1）与 T-H5 的"跨格成交回升"都留给 **A4**。
2. **未做（属 A3）**：运力池/报价簿/需求簿/`MerchantHaul` 趟耗/`MerchantProfitBook`/`LaneUnserved*` 的整体退役、
   工具消耗单套化、§16 预留特例撤回。
3. **未做（本批上报的 blocker）**：I-H3"无跨格需求"那条腿（见 §4 D-4）——需要的"需求前瞻信号"会触碰铁律 5。
4. **未做**：创世服务库存（见 §4 D-5）；服务订单簿化（见 §4 D-2）。
5. **未核**：牌价 4 之下买方运费的抬升对跨格贸易量的真实影响（A4）；`laborPerUnit=1000` 与跑商家户人口
   是否支撑满规模产出（`scale = avgLabor/laborPerUnit`，需真实读数）。
6. **编译结果**：`tools/mvn-lock.sh -q spotless:apply` rc=0；`rm -rf simos-{util,economy,app}/target/classes` 后
   `tools/mvn-lock.sh -DskipTests compile -am` **BUILD SUCCESS**（16/16；日志实测 `Compiling 202 source files`
   = Economy、`328` = App ⇒ 结果确实来自改后的源码字节）。
