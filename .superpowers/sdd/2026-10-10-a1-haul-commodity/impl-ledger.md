# 实现架构账本：A1 —— 运输服务成为商品 + `trade@hex` 产出该服务

> 责任区：**A1（商品与配方）**，约束设计书 `docs/superpowers/specs/2026-10-10-haul-service-commodity-design.md`（v1.1）
> §3.1 / §3.2 / §4 / §5 / §6 T-H1 / §7 批次表 A1 行。只写生产代码；只到编译过（未跑 test/verify/world）。
> 用户原话（逐字，见设计书 §1）：「运输服务我不是说算商品吗，只是这个商品的需求需要额外通过其他已有商品的购买来计算，要我选的话我肯定选A」
> 「跑商不是生产方式吗？难到家户不会给预估生产方式预留生产资料吗？」「我觉得更该排查的是为什么跑商作为生产方式会和其他类型的生产隔离开来」

---

## 1. 关键调查结论（file:line → 结论 → 影响）

| # | 证据 | 结论 | 影响 |
|---|---|---|---|
| I-1 | `Industry.java:130`（`Map<CommodityId, Long> outputPerUnit`）、`EconomySettlement.java:8007`（`scale × 值 × MILLI_PER_GRAIN`）、`Industry.java:48-57`（量纲陷阱） | **`outputPerUnit` 是扁平 `{商品: 量}`、值侧 = 商品单位**（不是 `{资产类:{商品:量}}`、不是毫） | 设计书 §3.2 括号里的 `{CATTLE:{haul:H}}` 形状是 `cycleInputPerUnit` 的形状；本条按**代码**落成 `Map.of(COMMODITY_HAUL, H)`，H 以**商品单位**表达（1 = 1,000 毫） |
| I-2 | `EconomySettlement.java:8024`（`if (netByCommodity.isEmpty()) return;`）、`LaborQueueBook.java:214-218`（`NO_RECIPE_OUTPUT`，maxAbsorbable=0） | trade 的**结构性隔离**两条落点：收获当场 return（收入腿消失）+ 劳动队列落 NO_RECIPE_OUTPUT | A1 给产出即可同时接回这两处（用户原话"为什么跑商作为生产方式会和其他类型的生产隔离开来"指的就是它们） |
| I-3 | `LaborQueueBook.java:554-558`（`isPreservedByQueue = LABOR_FREE \|\| NO_RECIPE_OUTPUT \|\| !outputPriced`）、`LaborQueueSettlement.java:300` | "保留既有配额、不排队"的判据有**第三条腿**：`!offer.outputPriced()` | ★★ 缺省中性的**结构性**保证：haul 无牌价 ⇒ `outputPriced=false` ⇒ 即使 reason 不再是 NO_RECIPE_OUTPUT，trade 的既有劳动配额**照旧原样保留**（逐值不变，不是靠"碰巧"） |
| I-4 | `MarketSettlement.java:1376`（`planOrders`）与 `:2119`（`ordersFor`）：`if (!market.hasPrice(commodity)) return 空订单` | "缺价 ⇒ 不交易"是**两道**现有守卫，位置在订单生成之前 | haul 无牌价 ⇒ 不挂买单/卖单 ⇒ 无成交、无冻结、无未成交读数（I-H3 缺省中性） |
| I-5 | `CapacityDemand.java:11-17,58-67`（毫商品·程）、`MerchantCapacity.java:20-23,66`（1 毫小时 ⇒ 1 毫商品运力）、`MerchantHaul.java:35-41`（一趟 = 承运 10 商品单位 = 10,000 毫商品 ≈ 20 毫银）、`EconomySeeder.java:226`（100 毫工具/规模·周期）、`MerchantPolicy.java:77`（`CITY_CAPACITY_CEILING = 100_000`） | 既有"运力"量纲 = **毫商品·程**；既有工具投入口径 = 100 规模 × 100 毫 = 10,000 毫工具/周期 = **10 趟**/周期 | ★ H 的取值必须落在这两条既有标定上（见 §4 D-A1-1）：两条口径**独立给出同一个数** 100,000 毫服务/城·周期 |
| I-6 | `AllocationRule.Split` 只被 `EconomyPayloads:1384-1392`（播种解析）与 `ApiViews:3740-3748`（读口）读；`EconomySettlement.java:140` 类注称其为"**旧口径**" | `means/laborWeightPerMille` **不参与**当前生产结算（结算走 `ProductionRules` relation） | 把 0 改成 300 **不改任何数值**；跑商劳动的**真实**约束是 `laborPerUnit`（=1,000，改前已 > 0），设计书把它写成"现状 0 ⇒ 不施加约束"是两个字段混用（§4 D-A1-2） |
| I-7 | `EconomySetMarketPriceHandler.java:26,66`（示例商品 `"wool"`，**不查词表**） | 市场价命令与词表无关；出厂价表是硬编码 5 项（`EconomySeeder.factoryPrices()`，连词表里的 `wood` 都不在） | 词表加 haul **不会**让任何格自动获得 haul 牌价 ⇒ A1 缺省中性的前提成立；A2 要牌价时两处都有现成落点 |

## 2. 实现改动（落点）

| 文件 | 落点 | 改了什么 |
|---|---|---|
| `simos-util/.../util/economy/EconomyVocabulary.java` | `:91`（常量 `HAUL_COMMODITY_ID = "haul"`，中文"运输服务"）、`:149-158`（`allCommodityIds()`）、`:23`/`:122`（类注与读口注：六个 → 七个） | 词表加一项，**追加在末尾**（既有六项相对序逐字不动） |
| `simos-economy/.../economy/EconomyCommodities.java` | `:35`（`HAUL` 类型化常量） | 照 `GRAIN`/`CLOTH` 的既有形制包一层（不新增第二处字面量） |
| `simos-app/.../app/world/EconomySeeder.java` | `:259`（`HAUL_PER_TRADE_UNIT_CYCLE = 1`）、`:279`/`:285`（split 700/300）、`:735`（`COMMODITY_HAUL` 别名）、`:4233-4252`（`trade(...)` 的 `outputPerUnit` + 分配模板）、`:4290` 一带（`tradeRelation` 类注） | `outputPerUnit = {haul: 1}`、`laborWeightPerMille: 0 → 300`；**`cycleInputPerUnit` 一字未改**（工具消耗单套化 = A3） |
| `simos-economy/.../economy/time/EconomySettlement.java` | `:511-618`（新 `logHaulServiceOutput`）、`:1885-1886`（调用点，收获块内）、`:5`（import） | §一.9 日志：INFO `HAUL_SERVICE_PRODUCED`（发生了什么 + 具名计数）+ DEBUG `HAUL_SERVICE_PRODUCED_DETAIL`（为什么） |

**数据流（改后）**：`trade@hex` 单元 → 周期末 `harvest` 逐商品毛产 `100 规模 × 1 × 1000 = 100,000 毫` → 3% 既有损耗（`FEED_PER_MILLE=0 + DEPRECIATION_PER_MILLE=30`）→ 净产 97,000 毫**入 operator 家户账**（`creditOutput`，单一主体一条计提）→ `ProductionRules`（空规则 ⇒ 全留 residualOwner，**不产生转移**）→ 日志两条。订单侧：两处 `hasPrice` 守卫 ⇒ haul 无牌价 ⇒ 零订单、零成交。

## 3. 关键判断

- **D-A1-1｜`H = 1` 商品单位（= 1,000 毫运输服务）/规模·周期**。两条独立既有口径给出同一个城市格总量 100,000 毫/周期：
  ① `MERCHANT_CATTLE_PER_CITY(100) × 1,000 = 100,000` = `MerchantPolicy.CITY_CAPACITY_CEILING`（改前每城每轮运力量级）；
  ② `TOOL_MILLI_PER_TRADE_UNIT_CYCLE(100) × 100 规模 = 10,000 毫工具/周期 ÷ 1,000 毫工具/趟 = 10 趟 × 10,000 毫商品/趟 = 100,000`。
  ⇒ 不新造量纲（1 毫运输服务 ≡ 1 毫商品·程）、不新拍数。★ 工具投入**不参与**这条算式的一致性（A3 才单套化），只是给出"10 趟"的量级锚。
- **D-A1-2｜`laborWeightPerMille 0 → 300`（means 1000 → 700）**，理由：与农业同为"资本主导"档（700/300），不新造比例；且 trade 的经营主体是商号本金主家户、劳动来源 `LaborSource.SELF`。**如实记**：该字段是旧口径、当前结算不读它 ⇒ 本改动不改数值；跑商劳动的**真实**约束在 `laborPerUnit`（本来 = 1,000 > 0）。设计书 §3.2 把两者混为一谈 —— 不因此改 `laborPerUnit`（它已非 0，改了才是真的改数值）。
- **D-A1-3｜本批不给 haul 出厂牌价**。这是缺省中性的关键：`MARKET_PRICES_FACTORY` 若有 haul，trade 的 `offer.outputPriced` 会转 true ⇒ 劳动队列从"保留"转"排队"（净收益可能转正）⇒ **改既有数值**，直接违反任务书 item 4 与 I-H3。牌价与派生需求**必须同批**（A2）落地。设计书 §3.1"进价格表 = 进"与判据 T-H1 的"价格表"一项因此**本批未做**（§5 偏离 D-1）。
- **D-A1-4｜日志落在 `harvest` 汇总处、按商品键判**（而不是在 `creditOutput` 逐户打）：INFO 一天最多一条、只在真有 haul 毛产时刷；`logHaulServiceOutput` 只读 ledger（不写状态、不改公式）。逐条明细已由既有 `HARVEST_BY_INDUSTRY`（TRACE）覆盖（它的 `gross/losses` 表现在含 haul）。
- **D-A1-5｜不新增/不改持久状态组件**（设计书 §4）：`haul` 只是既有 `Map<CommodityId, …>` 的新键；`CommodityId` 是 `record(String)`。**未触碰铁律 5**（无 Codec/ChangeSet/往返改动）。
- **D-A1-6｜确定性 I7**：新表都是单键 `Map.of`（单键无迭代序抖动；与既有工具投入表同款），`allCommodityIds()` 用保序 `List.of` 且新项追加末尾；日志字段用 `LinkedHashMap` 系（`LogEvent.of` 保序）。**未引入** `Map.copyOf`/`Set.copyOf`。

## 4. 词表联动面逐条复核（任务书 item 1）

| 面 | 落点 | 结论 |
|---|---|---|
| 商品词表常量 | `EconomyVocabulary:91` | **需补 → 已补** |
| `allCommodityIds()` | `EconomyVocabulary:149-158` | **需补 → 已补**（末尾追加，保序） |
| 类型化 `CommodityId` | `EconomyCommodities:35` | **需补 → 已补**（既有形制） |
| 口岸政策校验（写前守卫） | `GovPortPolicyGuard.java:78` 走 `allCommodityIds()` | **自动覆盖**（未改） |
| 口岸政策校验（app 桥，运行期） | `PortRegimeBridge.java:770` 走 `allCommodityIds()` | **自动覆盖**（未改） |
| 运费表校验命令 | `EconomySetCommodityFreightHandler.java:90` 走 `allCommodityIds()`；拒因文本里的"恰 N 种"是**现算**的 | **自动覆盖**（未改；`unknown-commodity` 从此接受 `haul`） |
| 商品基础运费缺省分档 | `CommodityFreightBase.legacyMilli`：未登记 ⇒ `DEFAULT_MILLI = 1` | **自动覆盖**（haul = 1 毫/单位/程；不新增档位，不改既有四档） |
| GUI 读口 | `ApiViews.java:1441` `commodityIds = allCommodityIds()` | **自动覆盖**（6 → 7 项，新项在末尾） |
| webui JS | `simos-app/src/main/resources/webui/**`：`grep commodityIds` 与硬编码商品清单**均 0 命中**（中文只在提示文案里） | **不涉及**（读口发什么显示什么） |
| 货币词表 | `MoneyVocabulary.java:17` 只在 javadoc 引 `allCommodityIds()` 作先例，**零代码联动** | **不涉及** |
| 商品中文名 | 本仓**没有**运行时中文名表；中文写在词表 javadoc（"纤维的商品 id"一族） | **需补 → 已补**（常量 javadoc"中文'运输服务'"） |
| 出厂价表 | `EconomySeeder.factoryPrices()` 硬编码 5 项（`wood` 也不在）⇒ 词表**不**自动覆盖 | **本批刻意不补**（D-A1-3 / 偏离 D-1），A2 补 |
| 全仓第二份商品清单 | `grep -rl allCommodityIds` = 7 文件，逐一核对；无第二份清单 | **不存在**（不需要新造） |

## 5. 偏离记录（与约束设计书不一致之处及原因）

- **D-1｜`outputPerUnit` 形状**：设计书 §3.2 与 v1.1 修订记录写 `{资产类:{商品:量}}`；代码是**扁平 `Map<CommodityId,Long>`、值侧商品单位**（I-1）。⇒ 按**代码**落成 `Map.of(COMMODITY_HAUL, 1)`；H 的"1,000 毫/规模·周期"由注释写清（值侧 ×1000）。派单书已授权"形状按 `Industry` 实际口径核对"。
- **D-2｜"进价格表"未做**（设计书 §3.1 商品表"进价格表？进"、判据 T-H1"出现在…价格表"）：理由见 D-A1-3（会破坏 I-H3 缺省中性）。★ T-H1 另含"只剩一套工具消耗"一项，那本属 A3 ⇒ T-H1 是**跨 A1–A3 的终态判据**，不是 A1 单批判据。
- **D-3｜`laborWeightPerMille` 的语义**：设计书写"现状 0 ⇒ 不施加约束"；实测该字段是旧口径、不参与结算，真实劳动约束是 `laborPerUnit`（已 > 0）。本批**照设计书把 0 改成 300**（形状自相一致），但**不改** `laborPerUnit`，并把这条不准确具名在此（I-6 / D-A1-2）。
- **D-4｜`trade` 的产出损耗**：产出运输服务同样过 `FEED+DEPRECIATION = 3%` 既有损耗口径（毛产 100,000 → 净产 97,000）。设计书未提；本批**不特例**（特例=改公式，属另批裁定），仅记录。
- **D-5｜无新增状态组件** ⇒ 与设计书 §4 一致（未停手、未上报 blocker）。

## 6. 会改变数值行为的清单（含缺省中性论证）

1. **新增键（不是改既有值）**：`trade@hex` 的家户 `goods` 多出 `haul` 键（净产 97%），`ProductionLedger.gross/losses/outputAccruals` 多出 haul 条目。
2. **`LaborQueueBook` 的 reason 字符串**：trade 从 `NO_RECIPE_OUTPUT` 变为 `NO_INPUT:tool:…` 或 `IDLE_EXPECTED_NON_POSITIVE`；**处置不变**（`isPreservedByQueue` 第三腿 `!outputPriced` 命中 ⇒ 保留既有配额）⇒ **劳动分配逐值不变**（I-3）。
3. **`MarketDemandBook.commodityUniverse`** 多一项 `haul`（`MarketDemandBook.java:410`）⇒ 报表商品域多一列；可寻址需求恒 0（无自然需求、无投入需求、无未成交买单）⇒ `sellable(haul)=0`。
4. **订单/成交/货币**：两处 `hasPrice` 守卫（I-4）⇒ haul **零订单**（既不挂卖单也不挂买单）⇒ 无成交、无冻结、无未成交、无运费、无税、钱一分不动。
5. **日志面**：`HAUL_SERVICE_PRODUCED`（INFO）只在真有 haul 毛产的日子出现；无跑商/无产出世界一条都不刷。
- **缺省中性论证（I-H3）**：无 haul 牌价（D-A1-3）+ 无派生需求（A2）⇒ ①订单侧被 `hasPrice` 挡死（2 道）②劳动侧被 `!outputPriced` 保留（1 道）③需求侧无任何来源 ⇒ **除"新键 haul"外无既有数值变化**。★ 真实验证（两套世界 360 tick / md5 对照）属 **A4**，本批未跑（§7）。

## 7. 会让既有测试失效 / 需要新护栏的清单（给测试 Agent）

- **预期不失效的**（已核）：`ProductionRuntimeSeedSmokeTest`（只判非空）、`EconomyCycleHealthTest`（判人口/粮布/报告结构）、`GuiApiTest`（未引用 commodityIds/配方）、`CrossProcessDeterminismTest`（两跑自比，不比对 golden）。
- **需要新增护栏（当前是缺口，不是失效）**：`EconomyVocabularyGuardTest` 的两条只钉粮/布/纤维/工具/铁/木 ⇒ **`haul` 字面量目前无人钉**。建议加入：`HAUL_COMMODITY_ID = "haul"` 恰一处（`EconomyVocabulary`）、`CommodityId("haul")` 零命中。
- **需要新判据（A1 验收）**：`allCommodityIds()` 含 haul 且**前六项相对序不变**；`trade@hex` 的 `outputPerUnit = {haul: 1}`、`laborWeightPerMille = 300`、`cycleInputPerUnit` **仍是** `{CATTLE:{tool:100}}`（防 A3 提前单套化）；缺牌价世界 haul 零订单/零成交（负向）；`logHaulServiceOutput` 只在有产出时刷。
- **未核的**：`simos-app` 侧任何对 `ApiViews` 经济视图整体快照/键集合相等的断言（本次只 grep 了 `commodityIds`，未逐文件读 GUI 断言）。

## 8. 未完成 / 未验证

1. **未跑** `test` / `verify` / 真实 world（任务书纪律：只到编译过）⇒ 第 6 节的数值结论**全部是静态推演**（每条给了 file:line 证据），**没有**任何运行时实测数字。缺省中性的真实对照留给 **A4**。
2. **未做**：haul 出厂牌价、派生需求、跨格货单派生服务需求、市场成交（**A2**）；工具消耗单套化、`CARRIER_FEE` 去双记、`MerchantProfitBook` 收口（**A3**）。
3. **未核**：`wood` 之外的留位商品与 `MARKET_PRICES_FACTORY` 缺项是否有测试钉住"恰 5 项"（grep 未见，未逐文件读）。
4. **编译结果**：`tools/mvn-lock.sh -q spotless:apply` rc=0；`tools/mvn-lock.sh -DskipTests compile -am` **BUILD SUCCESS**
   （16 模块全 SUCCESS）。★ 为防"增量编译没重编我的字节"，最后一轮**先 `rm -rf simos-{util,economy,app}/target/classes`** 再跑：
   实测 `UtilSimos 编译 62 源文件` / `EconomySimos 编译 201 源文件` / `SimosApp 编译 328 源文件` ⇒ 本次结果**确实**来自改后的源码字节。
