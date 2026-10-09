# M-A1 实现架构账本：运力=派生量 + 逐 hex 运力池 + 运费付给提供者 + `merchantFirms` 退役

> 责任区：M-A1（口径冻结见派单书；权威设计书 = `docs/superpowers/specs/2026-10-09-commodity-freight-and-merchant-ladder-design.md` §11–§14、
> `docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md` §2.4/§2.6/§3/§6.2 V-19..V-24、`AGENTS.md` §一.11）。
> 本文件是实现方自己写的**实现架构账本**（§一.8），只记决策相关事实。

## 1. 关键调查结论（`file:line` 证据 → 结论 → 影响）

| # | 证据 | 结论 | 影响 |
|---|---|---|---|
| S-1 | `EconomyData.java:326/427/…`（≈50 处）+ `MerchantFirm.java:50-61` | `merchantFirms` 是第 30 个持久组件，30 个 `this(...)` 委托链各自逐参列出它 | 退役 = 机械删参：每个构造器删 1 行形参 + 1 行实参（record 头 + compact 构造器 + `withMerchantFirms` + 校验块） |
| S-2 | `MerchantSettlement.java:191-338`（`CarrierPool`）、`:146`（`CarrierAllocation`）、`:101`（`CarrierChoice`）、`:432`（`settleCycle`）、`:670`（`upkeepOf`）、`:353`（`feeRevenueOf`） | 旧"货主挑商号"整条路径（按有效到费率序、容量就地扣、周期末工资/upkeep/利润写回）全在 `MerchantSettlement` 一个文件里 | 该文件**整体删除**；新落点 = `MerchantCapacity` + `MerchantCapacityPool` |
| S-3 | `MarketSettlement.java:4706-4712`（路线窗口容量）、`:4815-4819`（`capacityLeft`）、`:5882-5913`（`executeTrade` 承运分支）、`:6200-6254`（`carrierChargeSplit`）、`:6367`（`carrierCostPerMille`）、`:5096`（区内跨格合成路线） | 运力硬约束有**两处**：路线窗口容量（全局常量，与商号无关）+ 承运池容量（商号） | 本批只把"承运池容量"换成逐 hex 派生运力池；路线窗口容量**不动**（冻结项 5：沿用既有运费算式，不改别的量纲） |
| S-4 | `MarketSettlement.java:3595`（有商号 ⇒ 区内撮合改单线程）、`:3751`（worker 副本给 `CarrierPool.empty()`）、`:3911-3913`（回放**无条件**扣 `remaining`） | 并行 worker 路径**不能**承载"运力不足 ⇒ 不成交"：回放不认 `executed=0`，会与串行路径漂开 | ★ 硬约束：**worker 路径里不得生成需要运力的跨格意向**。故"该格没有运力 ⇒ 不建跨格路线"（在候选生成处具名拦下），而不是只在 `executeTrade` 里拦 |
| S-5 | `MarketSettlement.java:1751-1785`（`adaptPrices` 逐 (区,商品) 汇 `ctx.buys`/`ctx.sells`）、`:1564`（调用点在撮合之后） | 喂给自适应定价的 `demand/supply` = **订单量**（非成交量） | V-20 的落点：在 `adaptPrices` 里逐槽位扣掉"因运力未获服务的量"（两侧都剔），不是改读数 |
| S-6 | 创世 `EconomySeeder.java:1462-1474`：城镇 `rich_peasant → MERCHANT.self_employed`、城镇 `landlord → MERCHANT.principal` | "选了跑商的家户" = `HouseholdClassMembership.effectivePositionIds()` 里含 `modeId == DefaultProductionModes.MERCHANT` 的位置 | 运力池的成员判据（J-A）从**既有状态**派生，零新状态 |
| S-7 | `HouseholdEconomy.laborMilli` = 每 tick 家户时间预算（毫小时，人均 ≈8000）；`participationAdjustedLaborMilli()` = 参与率折算后的投入 | "劳动投入"有现成的唯一权威读数 | 运力公式的劳动维 = `participationAdjustedLaborMilli()` |
| S-8 | 商品库存（含 `tool`）**不在** `EconomyData` 里，只在会话工作副本 `householdGoods`（`MarketRound`）；`EconomyData` 侧只有 `AssetKind.TOOL` 产权份额 | 工具维**只在市场装配处可读**；纯状态侧（迁移政策/前瞻）读不到 | 工具维用"可选入参"表达：拿不到 ⇒ 0（**下界**，只少不多 ⇒ 保守，见 §3 判断 J-3） |
| S-9 | `EconomySettlement.java:2859-2871`（`settleCycle` 唯一调用点）、`EnterpriseProfitBook.java:309-393`（商人读数累加器） | 周期末商号结算（工资/upkeep/利润）只被这一处调用 | 本批**停掉这唯一调用点**；`EnterpriseProfitBook` 的商人累加器保留给 M-C 的利润算式（否则要连带改 L3 读口，超出本批冻结范围）—— 如实记 |
| S-10 | `MarketSettlement.java:4278-4338`（跨区配对循环，源格 = `sellerHex`）、`:5096`（区内跨格） | 两条跨格路径都拿得到"发货格" | G-2（运力池挂**发货格**）落在两处：`carrierPool.hasCapacityAt(sellerHex)` |

## 2. 实现架构（组件拆分 / 数据流 / 调用次序）

### 2.1 新类型（`simos-economy/src/main/java/io/mosire/simos/economy/time/`）

**`MerchantCapacity`（record，派生读数，不落状态）**

```
MerchantCapacity(household, carrier, hex, laborMilli, toolMilli, capacityMilli, tier, serviceRadiusHex)
capacityMilli = laborMilli × CAPACITY_MILLI_PER_LABOR_HOUR(=1)
              + toolMilli  × CAPACITY_MILLI_PER_TOOL_MILLI_PER_MILLE(=1000) ÷ 1000
tier            = 由 capacityMilli 分档（PORTER / SELF_EMPLOYED / BOSS）  ← J-1：派生读数
serviceRadiusHex= 由 tier 取具名默认 (2 / 4 / 8)                          ← J-2：派生读数
```

**`MerchantCapacityPool`（逐 hex 池，一轮一份，瞬态）**

```
of(classStandings, classPositions, rows, goods)      // 装配（市场轮开始前一次）
byHex: HexCoord → [entry…]（LinkedHashMap，entries 按 household id 升序）
entry: household / carrier / laborMilli / toolMilli / capacityMilli / sharePerMille / tier / radius / remainingMilli
sharePerMille = capacityMilli × 1000 ÷ 该格运力总量        ← G-1 议价权
isEmpty() / hasCapacityAt(hex) / totalCapacityAt(hex)
select(from, to, quantityMilli) → CarrierAllocation        // 发货格 = from（G-2）
   候选 = 该格 remaining>0 且 lane 长度 ≤ 派生半径
   序   = sharePerMille 降序 → household id 升序（I7 确定性）
   分配 = 贪心 min(剩余需求, 剩余运力)，逐条扣 remaining
   不变量 Σ choices ≤ 该格运力总量（A3 退化式）
select 返回 CarrierChoice(carrier, household, hex, quantityMilli, tier)
静态派生查询（纯状态、工具维取 0）：
   hasCapacityAt(EconomyData base, HexCoord hex)   // 迁移政策用
   capacityOf(HouseholdEconomy row)                // 迁移前瞻用
```

### 2.2 数据流与调用次序（一 tick 内）

```
EconomySettlement.advance…
  ① 生产/欠租阶段（不动）
  ② ★ 运力池现算：MerchantCapacityPool.of(session.sheet().classStandings(),
        session.sheet().classPositions(), householdEconomies, householdGoods)
        —— 每轮重算、不累积、不落状态（J-B/J-C）；位置 = 市场装配之前（"生产环节之后"）
  ③ MarketRound 装配（不动）→ MarketSettlement.clearOncePerCycle(markets, round, trigger, topology,
        parallelism, capacityPool)          ← 参数从 (merchantFirms, CarrierPool) 换成 1 个池
     3a. 区内撮合：池非空 ⇒ 单线程串行（沿用 S-4 的既有约定）；池空 ⇒ 并行（worker 池也空 ⇒ 行为一致）
     3b. 跨格路线**候选生成处**拦（S-4）：源格无运力 ⇒ 不建路线，具名 LOGISTICS_CAPACITY
     3c. 逐笔成交：select → 分配量 > 0 才成交；不足 ⇒ 收缩成交量 + 记 capacityTruncatedMilli
         CARRIER_FEE 收款人 = 提供运力的**家户** actor（自承运仍跳过，沿用旧守卫）
     3d. adaptPrices：demand/supply 逐槽位扣 capacityTruncatedMilli（两侧都剔，V-20）
  ④ 周期关账：**不再**调 MerchantSettlement.settleCycle（M-C 的利润算式未做；见 S-9）
```

### 2.3 日志（§一.9）

| 级别 | 事件 | 内容 |
|---|---|---|
| INFO | `MERCHANT_CAPACITY_POOL_HEX` | 每格：家户数 / 运力总量 / 最大占比；分配后每格：已用运力、成交笔数 |
| DEBUG | `MERCHANT_CAPACITY_HOUSEHOLD` | 谁有运力（家户/劳动/工具/运力/占比/tier/半径） |
| DEBUG | `MERCHANT_CAPACITY_LANE_BLOCKED` | 为什么截断（源格无运力 / 分配不足 / 半径外） |
| TRACE | `CARRIER_FEE_PAID` | 逐笔运费（付款人 → 收款家户、金额、币种、lane） |

## 3. 关键判断（为什么这样拆 / 为什么不走另一条路线）

- **J-1 工具维表达成"可选入参"而不是新增权威**：商品账在 actor 切片/会话副本里，`EconomyData` 里没有（S-8）。
  把 `tool` 商品搬进 economy 状态 = 触碰铁律 5 且造第二本账 ⇒ 否决。⇒ 公式签名 `capacityMilliOf(labor, tool)`，
  读不到工具的地方传 0。
- **J-2 工具项只增不减 ⇒ 拿不到工具数据时是下界**：因此"说没有运力"必然真的没有运力（fail-closed 方向正确）。
  代价如实记：迁移政策/迁移前瞻只看劳动项，与市场结算处的值可以差一个工具项（**具名偏差**，M-C 应统一）。
- **J-3 半径仍作触达判据**：J-2/§4.4 明写"规模决定触达范围"；半径虽降为**派生读数**，但它派生出来的值仍用来
  判"这条 lane 够不够得着"（沿用旧 `servesLane` 语义：lane 长度 ≤ 半径）。推导：家户就在发货格 ⇒ 到自己格距离 0，
  只剩终点那一端。若把半径彻底降为纯展示，城市商号可服务任意远的 lane ⇒ 行为变化大于设计授权。
- **J-4 跨格候选生成处拦运力，而不是只靠 `executeTrade`**：由 S-4 逼出来（并行回放不认 `executed=0`）。
  两处都保留：候选生成处拦"整格没运力"，`executeTrade` 拦"运力被本格别的车道吃光了"（只有串行路径会走到后者）。
- **J-5 删除 `MerchantSettlement` 整个文件**而不是留壳：它 100% 由 `MerchantFirm` 派生（S-2）。
- **J-6 V-20 用"逐槽位截断量"而不是"改读数"**：`adaptPrices` 读的是订单量（S-5）；新增
  `BuySlot/SellSlot.capacityTruncatedMilli`，在**真正因运力拦下的三个点**累加（候选生成处 / 分配不足处 /
  旧池耗尽处），`adaptPrices` 逐槽位扣除（上限 = 该槽本轮统计量，不会扣成负数）。
  并行路径要把它带回来 ⇒ `BuySlotState`/`SellSlotState` 各加一个字段（否则协调器侧恒 0 = 静默不生效）。
- **J-7 `EnterpriseProfitBook` 的商人累加器不删**：唯一写入点（`settleCycle`）已停；累加器是 M-C 利润算式的
  落点，删了要连带改 L3 读口/报告（超本批范围）。⇒ 保留并如实记"本批读数为 0"。

## 4. 偏离记录（与约束设计书不一致之处，主动记录）

| # | 偏离 | 原因 |
|---|---|---|
| D-1 | **"运力每轮在生产环节算"落点为"生产阶段之后、市场装配之前"** | 运力只在撮合时被消费；工具维只有在会话商品账可读处才拿得到（S-8）。同一 tick 内、同一份状态，不落状态、不累积 —— "每轮算"的实质不变。"生产环节统一兑现/收益自动算"（H-D/H-E）属 M-C，本批不做 |
| D-2 | **"工具消耗"本批实现为"工具可投入量（存量）"** | 扣减/门槛（单次跑商烧 tool、准入拒）是 M-C 的冻结范围（派单书"不做"清单）。本批只让工具维进入运力算式；烧掉/拒入留给 M-C ⇒ 不写"消耗"语义的假实现 |
| D-3 | **利润读数本批只到"每轮运费实收"** | M1 利润算式属 M-C；本批不发明公式。旧 `lastFee/upkeep/profit` 随 `MerchantFirm` 一起消失（不落状态，冻结项 6） |
| D-4 | **"区内成交逐值不变"读作"同 hex 成交逐值不变"** | 冻结项 8 同一句里明写"跨格货走不动"；把同区跨格读成"区内"会与前半句矛盾。⇒ 跨 hex（跨区 + 同区跨格）都要运力；同 hex 零运费即时成交一字不动 |
| D-5 | **旧"uncollectedFreight 但货照走"的兜底分支删除** | 旧 `carrierOf` 恒空 + `else { uncollectedFreight = nominal; }` ⇒ 没运力也成交（货走、运费记未收）。与冻结项 8"无运力 ⇒ 跨格货走不动"直接冲突 ⇒ 删兜底，改具名 `LOGISTICS_CAPACITY` |
| D-6 | **创世 `trade` 产业与它的 CATTLE 份额保留** | `merchantFirms` 退役 ≠ 删商人生产方式：`trade` 产业是 M5/M-D"主业副业排序"的落点，也是迁移前瞻的产业模板。只删 `merchantFirms` 载荷/创世行 |

## 5. 实施记录（落盘后的实况）

### 5.1 改动/删除文件（相对 `78bcb208`）

| 类别 | 文件 | 处置 |
|---|---|---|
| 新增 | `time/MerchantCapacity.java` | 派生算式 + 具名常量 + tier/半径派生 + 议价权占比 |
| 新增 | `time/MerchantCapacityPool.java` | 逐 hex 池装配 + `select` 分配 + 运费实收读数 + 三级日志 |
| 删除 | `model/MerchantFirm.java`（-332）、`time/MerchantSettlement.java`（-881） | 商号载体 + 旧"货主挑商号"整条路径 |
| 改 | `EconomyData`(-145)、`EconomyChangeSet`、`EconomyStateBuilder`、`EconomyClearRegionHandler`、`EconomySeedHandler`、`EconomyPayloads`、`EconomyCodec`(文档) | 第 30 个持久组件退役（record 头 / 30 处构造器委托 / compact 校验块 / `withMerchantFirms` / 变更集一维 / 工作副本 / 清区域摘要 / seed 合并 / 载荷解析） |
| 改 | `MarketSettlement`(+413/-…)、`EconomySettlement`、`ExpectedProfitBook`、`ModeMigrationPolicy`、`ModeMigrationSettlement`、`EnterpriseProfitBook`(文档) | 读者迁到派生面；承运硬约束接上逐 hex 池；V-20 截断剔除 |
| 改 | `app: world/EconomySeeder`(-121) | `Seed.merchantFirms` 组件/`jsonOf` 参数/`merchantFirmNodes`/创世播种/`MERCHANT_CAPACITY_PER_CITY`/`merchantFirm()` 全删（`trade` 产业与 CATTLE 份额保留） |

### 5.2 命令与结果（两条，本仓锁）

```
$ tools/mvn-lock.sh -q -DskipTests compile        → exit=0（16 模块全绿；checkstyle/spotless 在 compile 生命周期内已跑）
$ tools/mvn-lock.sh -q spotless:apply             → exit=0（改后重跑 compile 仍 exit=0）
```

### 5.3 运力算式与标定（实例）

```
运力_户（毫商品/轮）= 劳动投入_毫小时 × 1  +  工具可投入量_毫商品 × 1000‰ ÷ 1000
tier（派生）= [0,1e5) PORTER / [1e5,4e5) SELF_EMPLOYED / [4e5,∞) BOSS
半径（派生）= 2 / 4 / 8（tier 2/4/8 具名默认；仍作 lane 触达判据）
```
19 格小世界（`SmallWorld`：首都 700 城镇人口 / 镇 300；城镇 rich=150‰、landlord=50‰）：
- 首都 ≈ `105×8000×750‰ + 35×8000×100‰` = **658,000 毫商品/轮**（旧创世常量恒 100,000）。
- 镇 ≈ **282,000**；19 个农村格 **0**（农村四行的位置都是 farm 系，没有 merchant 位置）。
- ★ 创世首日种子口径 `laborMilli ≈ 580/人`（不是 Social 的运行期 8000/人）⇒ 首日运力 ≈ 47,700，运行期跳到 ~658,000。
  这是**既有的两处劳动口径差**（seeder 的 `laborMilli(pool)` 580/人 vs Social 每 tick 8000 毫小时/人，差 ≈13.8×），
  因为运力现在从劳动派生才第一次显形 —— 如实记录，不在本批修（改它 = 改创世初值/劳动口径，超本批冻结范围）。

### 5.4 既有测试失效清单（静态核对，未跑）

| 类别 | 文件 | 原因 |
|---|---|---|
| 编译不过 | `simos-economy/.../change/EconomyRoundTripTest.java` | `import MerchantFirm` / 夹具 / `base.withMerchantFirms(...)` / `cs.merchantFirms().changed()` |
| 编译不过 | `simos-economy/.../time/ExpectedProfitBookTest.java:235` | `fixture.base().merchantFirms()).isEmpty()` |
| 行为会红（无运力世界跨格不再成交） | `MarketSettlementFixtures`（4 参重载入口）+ 用它的 `MarketRegulationTest`、`HexTradeCostTest`、`MarketTopologySingleRegionTest`、`MarketSettlementSingleHexLossTest`、`Z7MarketExclusionTest` | 夹具世界无 `classStandings` ⇒ 无运力 ⇒ 跨 hex 具名拦下；同 hex 用例不受影响 |
| 不受影响（核对过） | `simos-app/.../world/ProductionRuntimeSeedSmokeTest.java` | 它只 `readTree(economyPayload())` 并把载荷喂给 handler，不断言 `merchantFirms` 键 ⇒ 载荷少一个键不炸 |
| 不破坏 | `ProbeEconomy` / `MerchantPolicyP6Test` | 它们引的是 `MerchantPolicy.capacityPerRound`（保留的值类型） |

★ 测试侧修法（留给测试 Agent）：跨格用例要么给夹具补"选了跑商的家户"（`classStandings` + merchant 位置），要么改断言为
`LOGISTICS_CAPACITY` 具名拦下；往返不变式用例删掉商号维度（§一.10/§一.11：老架构老测试直接删/迁移）。

## 6. 未完成 / 未验证（如实记）

- **未跑任何测试/长跑**（派单纪律：只到编译过）。⇒ 运力池在真档里的实际数值、日志形态、V-20 的价格效应**均未实测**。
- 未做 M-A2（运力报价/最低价购买/深度 1 附加费）、M-C（tool 门槛与一次性扣减、纯商号/顺便分流、利润算式）、
  M-D（主业副业排序）—— 按冻结范围。
- `MerchantPolicy`（tier 词表/`districtUse`/城区折扣/农村惩罚）与 `EnterpriseProfitBook` 的商人累加器**保留**：
  不含持久状态，是 M-C 的落点（前者还被 app 的费率注入读取）。
- 半径仍是 lane 触达判据（J-3 的判断）：若用户要"半径纯展示"，改一处 `MerchantCapacity.servesLane` 的调用即可。
- `MerchantCapacityPool.of` 在**每个日推进**都现算（含不开市的日子）⇒ DEBUG 会多出行；若要只在开市日算，
  把 `EconomySettlement` 的装配点移进 `if (marketTrigger != NONE)` 即可（一处）。
