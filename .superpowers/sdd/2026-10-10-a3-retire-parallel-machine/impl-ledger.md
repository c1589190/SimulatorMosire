# A3 实现架构账本 —— 退役跑商的平行机器 + 工具消耗单套化 + 撤回 §16 预留特例

> 责任区：A3（设计书 `docs/superpowers/specs/2026-10-10-haul-service-commodity-design.md` **v1.2** §3.4 / §5 / §6 / §7-A3）
> 前置：A1 `a15fb3ec`、A2 `4194e90a`。本批**只到编译过**（`-DskipTests compile -am`），未跑 test/verify/world。
> 允许写：`simos-economy/src/main/**`、`simos-economy-api/src/main/**`、`simos-app/src/main/**`。
> ★ 纯生产代码；未 `git commit`；未碰 `src/test/**`、`pom.xml`、`docs/**`、`AGENTS.md`。

---

## 1. 关键调查结论（`file:line` → 结论 → 影响）

| # | 证据（改前） | 结论 | 影响 |
|---|---|---|---|
| K-1 | `EconomySeeder.java:226` `TOOL_MILLI_PER_TRADE_UNIT_CYCLE=100` 与 `MerchantHaul.java:60` `TOOL_MILLI_PER_HAUL=1000` | **两套工具消耗并存**：前者是产业周期投入（100 毫/规模·周期），后者是市场轮每趟烧 1000 毫。100 规模 × 100 = 10,000 = 10 趟 × 1000 ⇒ 两者在"每城·每周期"上**恰好同量级但机制不同**（A1 类注 :240-258 已把这条闭合写下来）。A1 若同时扣 = 双扣，故设计书 v1.1 把单套化归 A3 | 取**产业投入**那一套（§3.2），删趟耗那一套 |
| K-2 | `MarketSettlement.java:8886-8897`（§16 特例）+ `:8927-8932` `merchantHaulToolReserveMilli` | 特例 = `necessary(tool) = max(declared, 1000)` 的"下夹"。**declared 从哪来**：`necessaryInputsOf:8879-8884` 的标准循环读 `industry.inputPerUnit()`，而 `trade` 持有 100 单位 CATTLE ⇒ `scale=100` ⇒ `declared = 100×100 = 10,000 > 1,000` ⇒ **旧 `put` 不执行**。⇒ 对**有 trade unit** 的跑商家户，特例是**恒等操作** | 证明 §16 特例"对有 unit 的户零贡献"（撤回第一个直证） |
| K-3 | `MerchantCapacityPool.java:392-405`（A2 前改后）`:396-405` 服务口径：`workBudget = haulServiceHexes.contains(hex) ? max(0, haul 现货 − haul 冻结) : capacity.capacityMilli()` | A2 起"一条承运要成立"必须有**运输服务货**；服务货只能来自 `trade@hex` 的产出，且产出全归 operator（`EconomySeeder.tradeRelation:4317-4319`） | 没有 trade unit 的跑商家户服务货恒 0 ⇒ 运力预算恒 0 ⇒ **结构上分不到承运** ⇒ 给它的 1,000 毫保留是**死重**（撤回第二个直证） |
| K-4 | `MarketSettlement.java:7384-7451`（旧 `settleHaulRuns`）+ `:828-862`（旧 `select` 门槛） | 工具门槛有**两个面**：市场轮准入（`select`）与提交侧实扣（`settleHaulRuns`），都读 `MerchantHaul`。特例存在的唯一目的就是喂这道门槛 | 门槛删 ⇒ 特例没有要保护的判据（撤回第三个直证） |
| K-5 | `LaneUnservedBook.java` 被 `MarketSettlement.markCapacityBlocked:7095` / `blockLaneWithoutCapacity:7140` 读，返回值 → `BuySlot/SellSlot.capacityTruncatedMilli` → **V-20 截断剔除（价格统计输入）** | ★ §3.4 把它写成"为私有门槛服务的读数"是**措辞与代码不符**（§四）—— 它是**判据面**（改价格输入） | **不能删** ⇒ 本批分类为"留"，并把这个事实写进类注 |
| K-6 | `CapacityDemand` 被 `HaulService:28-31`、`CapacityDemandBook:108-109`、`MerchantCapacityPool.select:workConsumedBy` 读 | ★ §3.4 把它列进退役清单，但 A2 已把它变成**活的唯一算式**（不是被 A2 取代的旧机器） | **不能删** ⇒ 分类为"留" |
| K-7 | `CapacityQuoteBook.selfQuoted()` 无逐户覆写 ⇒ `askPerMilleOf = carrierCostPerMille(tier) + 25‰`，是 `tier` 的**纯函数** | 生产路径下"自报价簿"其实只是一张**空簿 + 纯派生算式** ⇒ 删掉簿、把算式内联，**逐值不变** | 删除 `CapacityQuote`/`CapacityQuoteBook` 是**等价替换**（不改变任何数） |
| K-8 | `EnterpriseProfitBook.java:511-515`：`MARKET_TRADE` / `CARRIER_FEE` 钱腿按**卖方组织**计入 revenue；`MarketSettlement.java:6658` 服务 lane 的钱腿已是 `MARKET_TRADE` | 服务成交的收入**结构上**进得了标准企业利润 | T-H3 可满足：删 `MerchantProfitBook` 不丢"跑商收益"这个事实 |

## 2. 本批实现架构（改了什么、落在哪）

### 2.1 工具消耗单套化（§3.2，T-H1）
```
唯一口径  EconomySeeder.HAUL_TOOL_PER_MILLE_OF_SERVICE = 100      （毫工具 / 毫服务，:262）
派生 1    TOOL_MILLI_PER_TRADE_UNIT_CYCLE                          （:289）
          = HAUL_PER_TRADE_UNIT_CYCLE(1 商品单位) × 1000 毫/单位 × 100‰ ÷ 1000 = 100 毫工具/规模·周期
派生 2    MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI                 （:518）
          = 12 × GOODS_UNITS_PER_HAUL(10) × 1000 × 100‰ ÷ 1000 = 12,000 毫工具（逐值不变）
落点      trade@hex 的 cycleInputPerUnit = {CATTLE:{tool:100}}（未改一字）→ Industry.inputPerUnit()
          → ① 现扣 drawCycleInputs（生产阶段）② 挂单保留 necessaryInputsOf（标准循环）③ 补货需求
删除      市场轮"每趟烧 1000 毫工具"整族（MerchantHaul 类 122 行 + 池内门槛 + 提交侧实扣 + 决策层门槛）
```
★ 与旧趟耗的**逐值可对上**：旧 1,000 毫工具/趟 ÷ 一趟 10,000 毫服务 = 100‰ = 本常量 ⇒ 单套化**不改标定值**。

### 2.2 §16 预留特例撤回（I-H6，T-H4）
- `MarketSettlement.necessaryInputsOf`（`:8640` 起）：删"下夹一趟"整段（14 行）+ `merchantHaulToolReserveMilli` 方法。
- 连带删除（特例的专用管路，全部是**逐轮瞬态**、非持久组件 ⇒ 不触铁律 5）：
  `MarketRound.merchantHouseholds` 字段 + getter + `withMerchantHouseholds` + `freezeMerchantHouseholds`
  + 8 处克隆携带 + `clearOncePerCycle` 的防复发守卫 + `merchantHouseholdsLostByClone`（ERROR 行）
  + `MerchantIdentity.merchants(...)` + `EconomySettlement` 注入点 + `MarketReadout` 读口注入点。
- **撤回后的证明**：见 §1 K-2/K-3/K-4 三支直证；核心一句 = "预留改由**产业声明的投入**自动覆盖"
  （`trade.cycleInputPerUnit = {CATTLE:{tool:100}}` → `industry.inputPerUnit()` → 标准循环给 100 规模 × 100 = 10,000 毫）。

### 2.3 退役清单分类（设计书 §3.4，逐项）
| 项 | 分类 | 理由（证据） |
|---|---|---|
| `MerchantHaul`（趟耗 / "一趟"门槛 / 两种归因 / 烧工具损耗账） | **删** | §3.2 单套化取产业投入 ⇒ 趟耗那套没有位置；门槛的两个面（`select` / `settleHaulRuns`）与决策层面（`PrimaryModeRanking`）同批删净 |
| `MerchantProfitBook`（平行利润读数 397 行） | **删** | T-H3：收益走标准 `EnterpriseProfitBook`（K-8）；它是**影子账**，与账本漂开且不属于任何组织的真实利润 |
| `CapacityQuote` / `CapacityQuoteBook`（逐户自报价簿） | **删** | §3.3：价格走市场牌价。K-7：生产路径无逐户覆写 ⇒ 内联为纯派生算式，逐值不变 |
| `MerchantCapacityPool`（运力池 / 门槛 / 烧工具） | **降级**（保留为"派生需求 → 服务货分配"的现算视图） | 门槛/烧工具/自报价簿已删（2.1/2.3）；剩下的**只有**"按格把一份派生需求分给持有服务货的家户"——服务货来自标准生产（I-H2），钱走市场牌价。★ 设计书 §3.4 要求它逐项"删/降级"，本项取降级并写明"不再做门槛/定价/利润" |
| `CapacityDemand` | **留** | K-6：A2 已把它变成活的唯一算式（`HaulService`/`CapacityDemandBook`/`select` 三处读），删了跨格货运就没有运力扣减算式 |
| `CapacityDemandBook` | **降级为只读（保持）** | 本来就零判据（唯一调用点 `record(...)` 返回值无人使用）；A3 复核后仍是纯读数 ⇒ 类注写明"只是读数" |
| `LaneUnservedBook`（D-1b 净额簿） | **留** | K-5：它是**价格统计的输入**（V-20 截断剔除），不是"私有门槛的读数" ⇒ §3.4 定性有误，按 §四回代码核 |
| `LaneUnservedObservationBook` | **降级为只读（保持）** | 类注原文即"只服务日志读数、不进判据"；A3 grep 复核确认零判据 ⇒ 保留（日志净额是排查手段，§一.9） |
| 市场轮"趟"结算 + `CARRIER_FEE` 私有腿 | **CARRIER_FEE 降级为只读 / 服务 lane 停铸（A2 已落）** | `MarketSettlement:6658` 一个三元表达式二选一 ⇒ 结构上不可能双记（I-H5） |

### 2.4 日志（§一.9）
- **INFO 退役具名行**：`MerchantCapacityPool.RETIRED_PARALLEL_MACHINE_FAMILIES` / `..._REPLACEMENTS`（`:167`/`:170`），
  打进每轮 `MERCHANT_CAPACITY_POOL` 的两栏 `retiredFamilies` / `replacedBy`；`EconomySettlement` 的
  `CAPACITY_PRICING_ASSEMBLY`（原 `CAPACITY_QUOTE_BOOK`）同样两栏。
- **DEBUG 判据**：`MERCHANT_CAPACITY_LANE_TRUNCATED`（去 `tool*` 字段，`reason` 只剩服务货/半径/亚单位残余）、
  `CAPACITY_PRICING_ASSEMBLY`。
- **TRACE 逐笔**：`HAUL_SERVICE_DELIVERED`（A2 原有）、`MERCHANT_HAUL_RUN`（删 `toolBurnedMilli`，加 `serviceMilli`）、
  `CAPACITY_DEMAND_INSTANCE`。
- **消失的事件**（如实记）：`MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT`、`MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT`、
  `MERCHANT_HAUL_TOOL_UNPRICED`、`MERCHANT_PROFIT_TOTAL`、`MERCHANT_PROFIT_HOUSEHOLD`、
  `MERCHANT_HOUSEHOLDS_LOST_BY_CLONE`；改名 1 条：`CAPACITY_QUOTE_BOOK` → `CAPACITY_PRICING_ASSEMBLY`。

## 3. 关键判断（为什么这样拆 / 不用另一条路线）

- **J-1 为什么按"可独立编译的层"自拆**：批次 = 16 文件 / −1574 行。拆成三层，每层编译绿：
  A = 工具单套化 + §16 撤回；B = 删 `MerchantProfitBook`；C = 删自报价簿。实际执行时 A+B 在同一轮编辑
  （两者都落在 `MarketSettlement`/`MerchantCapacityPool`，分两轮会产生"半成品中间态"），编译门禁跑 3 次。
- **J-2 为什么"生产口径的限价算式"留下、而"簿"删**：K-7 —— 簿在生产路径是空的，算式是纯派生量。删簿 = 删
  一个恒空的容器（§3.3 要退役的是"逐户挂单/自报价"这套机制），保算式 = 缺省中性（I-C2）不被破坏。
- **J-3 为什么不把 `MerchantCapacityPool` 整个删掉**：它是"派生需求"的**唯一分配点**（§3.3 的派生需求在撮合那一刻
  才知道量，无法预付订单簿）。删它就得把分配逻辑搬进 `MarketSettlement`（等于换个名字），或改成跨阶段需求信号
  （= 触碰铁律 5，A2 已上报并裁定不做）。⇒ 降级保留，并把"它不再做门槛/定价/利润"写进类注。
- **J-4 为什么 `PrimaryModeRanking` 的 `tool-stock-zero` 也要删**：它是同一道门槛在**决策层**的面。若留着，
  "决策层说这户不能跑商、市场层却放行"会自相矛盾；且用户诉求（预估生产方式预留生产资料）已由产业投入表达。
- **J-5 未动的两处（如实记）**：① `MerchantCapacity` 的"劳动 + 工具"运力算式保留（服务成市格里它只剩 tier/share
  的派生依据，不是判据）—— 改它会动排序与档位 ⇒ 需要真实 world 对照才敢动，本批不动；
  ② `CapacityDemandBook` 的类名里 "capacity" 现在指服务量 —— 改名属洁癖，本批只写清口径。

## 4. 偏离记录

- D-A3-1：设计书 §3.4 要求"逐项删/降级"，**没有**规定哪些项可以留。本账本 §2.3 对 4 项给出"留/降级为只读"，
  依据是 §四（回代码核）+ A2 已改变这些类的角色。**这不是把设计书读松**：被 §3.4 点名的"平行机器"（门槛、烧工具、
  自报价簿、平行利润、§16 特例）**全部已删**，保留的只是"活的算式"与"价格统计输入"。
- D-A3-2：`LaneUnservedBook` 的定性（K-5）与 §3.4 措辞不符，按 §四"报出来"处理（已写进类注与账本），
  **未**按措辞删它。
- D-A3-3：`CapacityDemand.workPerGoodPerMille` 的量纲说明（"毫商品·程/毫商品"）保留原文；A3 未改任何算式。
- D-A3-4：`MerchantCapacityPool.GET_READY_SURCHARGE_PER_MILLE`（25）**原值搬移**自 `CapacityQuote`（不新增标定）。
- D-A3-5：`A3 阅读提示` 一段写进 `MerchantCapacityPool` 类注：G3-fix-2/leftovers/fix-3 三段保留为**门槛演进史留痕**，
  避免后人把它们当现行判据（遗留文档不删，但标明失效）。

## 5. §16 撤回后的证明（逐条可核）

```
① 标准循环已经覆盖  necessaryInputsOf 的 unit 循环读 industry.inputPerUnit()；
   trade 的 cycleInputPerUnit = {CATTLE:{tool:100}}（EconomySeeder.trade(...) 的 Map.of）
   ⇒ inputPerUnit()[tool] = 100；该户持 100 单位 CATTLE（MERCHANT_CATTLE_PER_CITY=100, capacityPerUnit{CATTLE:1}）
   ⇒ scale = 100 ⇒ necessary[tool] = 100 × 100 = 10,000 毫工具
   旧特例 = max(declared, 1,000) 的"下夹" ⇒ declared 10,000 > 1,000 ⇒ 旧 put 不执行 ⇒ **恒等**
② 旧特例真正生效的只有"没有 trade unit 的跑商家户"（旧值 1,000）。A2 服务口径下它们 haul 现货恒 0
   （服务货只由 trade@hex 产出、产出全归 operator）⇒ 运力预算恒 0 ⇒ select 结构上永不分给它们
   ⇒ 保留 1,000 毫工具没有任何判据在读 ⇒ 死重
③ 该特例的注释自己写着"该次跑商不成立 的判据就是'可用量 ≥ 1 趟'" ⇒ 门槛（MerchantHaul）删了，
   它没有保护对象
⇒ 撤回后：跑商的生产资料预留 = 产业声明投入（I-H6 / T-H4），无硬编码分支
```

## 6. 会改变数值行为的清单

1. **市场轮工具门槛删除**：旧 `tool-short`/`tool-frozen` 拦下的承运现在**放行**（T-H1/T-H4 的主目标）；
   跨格成交与"跑商收益"因此上升；`MERCHANT_HAUL_TOOL_*` 三个事件归零。
2. **每趟烧 1,000 毫工具删除**：承运家户的工具存量不再在市场轮减少；`market-merchant-haul` 损耗账不再有流水；
   工具改由 `trade` 产业周期投入在生产阶段扣（既有生产投入路径）。
3. **§16 保留撤回**：**选了跑商但没有 trade unit** 的家户不再被追加 1,000 毫工具保留 ⇒ 其 `tool` 卖单可卖量
   +1,000/户·轮（工具市场供给上升）。★ 对**有 trade unit** 的跑商家户**逐值不变**（K-2 证明）。
4. **决策层门槛删除**：`PrimaryModeRanking.merchantGateReason` 不再返回 `tool-stock-zero` ⇒ 原被排除的跑商候选行
   重新进排序表/候选集 ⇒ 可能改变主业排序与迁移决策（`ModeMigrationPolicy:624/:805` 两个调用点）。
5. **不改数值**：删 `MerchantProfitBook`（纯读数）、删 `CapacityQuote*`（K-7 等价替换，生产路径无逐户覆写）、
   `LaneUnserved*`/`CapacityDemand*` 保留 —— 逐值不变。

### 缺省中性论证（I-H3 v1.2 口径）
- **没有跑商家户 / 跑商模式未被选中**：`select` 不被调用 ⇒ 门槛删除无从触发；`merchantHouseholds` 为空 ⇒
  旧特例也不追加任何键 ⇒ **逐值不变**。
- **有跑商家户但 haul 无牌价**（`HaulService.pricedAt` 全假）：`haulServiceHexes` 空 ⇒ 工作预算仍 = `MerchantCapacity`
  （劳动+工具，A2 同口径）；工具门槛删除只影响"跨格承运"这条路径 ⇒ 无跨格承运的世界**逐值不变**。
- **有 merchant 家户但 tool 存量为 0**：旧特例追加 `necessary[tool]=1000`，但 `sellable = max(0, 0 − 冻结 − 1000 − 保留) = 0`
  ⇒ 与不追加**逐值相同**。
- **夹具 / 纯状态读者**：`capacityDemands`/`laneUnserved` 等保持缺省空表 ⇒ 一行不刷、字段不出现。

## 7. 会让既有测试断言失效的清单（给测试 Agent；只列，未改任何测试）

| 文件:行 | 失效原因 | 性质 |
|---|---|---|
| `simos-economy/.../MerchantCapacityAcceptanceTest.java:39` | `MerchantCapacityPool.TOOL_COMMODITY` 已收为 `private` | 编译失败 |
| 同上 `:152` | `CapacityQuoteBook.selfQuoted(Map.of(small,0L,big,500L))` 类已删 | 编译失败 + 逐户覆写语义**已退役** |
| 同上 `:175,:185,:189,:198` | `MerchantHaul.TOOL_MILLI_PER_HAUL` 已删；`pool.toolBlockedRuns()` 已删；"缺工具 ⇒ 该条不成立"整段行为退役 | 编译失败 + 断言语义失效（应改测"服务货不足 ⇒ 不成交"） |
| `simos-economy/.../CapacityTruncationPricingAcceptanceTest.java:61` | `MerchantHaul.TOOL_MILLI_PER_HAUL` 作为 `builder.carrier(...)` 的 tool 参 | 编译失败（该参已无意义） |
| `simos-economy/.../PortPolicyMerchantLaneAcceptanceTest.java:217` | `openPolicyPool.toolBlockedRuns()` 已删 | 编译失败（应删该断言） |
| 同上 `:22`（类注） | 提到 `MerchantProfitBook`（仅注释） | 仅注释 |
| `simos-economy/.../MarketSettlementFixtures.java:167`（注释）、`:187` | `MerchantCapacityPool.TOOL_COMMODITY` private；注释仍写"工具存量 ≥ 一趟是跑商门槛" | 编译失败（:187） |
| 其余引用 `MerchantCapacityPool.of(...)` 的测试 | 5/6/7 参重载的 `CapacityQuoteBook` 形参 → `boolean priced` | 编译失败（传 `true` 即可） |
| 断言日志事件名的测试（若有） | `MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT`/`..._BLOCKED_AT_SELECT`/`..._UNPRICED`/`MERCHANT_PROFIT_TOTAL`/`MERCHANT_PROFIT_HOUSEHOLD`/`MERCHANT_HOUSEHOLDS_LOST_BY_CLONE` 消失；`CAPACITY_QUOTE_BOOK` → `CAPACITY_PRICING_ASSEMBLY`；`MERCHANT_CAPACITY_HOUSEHOLD` 少 `toolRemainingMilli`/`runsAffordable`；`MERCHANT_CAPACITY_POOL` 少工具栏 | 断言失效（本批未 grep 到这类测试，但事件名清单在此） |

## 8. N-H2 自证（命令 + 命中数）

```bash
# ① 平行运力池门槛族（代码标识符；main 全模块；排除以 * 或 // 开头的注释行）
grep -rnE "(MerchantHaul|TOOL_MILLI_PER_HAUL|TOOL_BURN_ACCOUNT|affordsRun|runsAffordable|TOOL_FROZEN_REASON|TOOL_SHORT_REASON|toolBlocked|remainingToolMilli|logToolBlocks|tool-stock-zero)" \
  --include=*.java */src/main | grep -vE "^[^:]+:[0-9]+: *(\*|//|/\*)" | wc -l
⇒ 0

# ② 双记运费族（CARRIER_FEE 与 MARKET_TRADE 服务腿）
grep -rn "TransferReason.CARRIER_FEE" --include=*.java simos-economy/src/main
⇒ 3 命中，其中铸腿点**恰 1 处**（MarketSettlement.java:6658 的三元表达式）：
   route.haulService() ? TransferReason.MARKET_TRADE : TransferReason.CARRIER_FEE
   另外 2 处是 EnterpriseProfitBook 的**只读**归集（:512 / :706，收入口径）
⇒ 结构上互斥：同一条 lane 的钱腿只能取一个理由码 ⇒ 不可能双记（I-H5）

# ③ 走私族
grep -rniE "smuggl" --include=*.java */src/main | wc -l      ⇒ 0
grep -rn "走私" --include=*.java */src/main | wc -l           ⇒ 4
   4 条**全部**是"该档已判死并已删净"的历史注释/用户原话：
   PortThrottle.java:20、PortRegimeAggregation.java:44、MarketSettlement.java:4684、PortDirection.java:16
⇒ 代码零残留
```

## 9. 未完成 / 未验证项（★ 如实记）

1. **未跑任何测试**（`test`/`verify`/world 一律未跑）⇒ 上述"会改数值"的**幅度**未自证；
   §5 的三支证明是**静态推导 + file:line**，不是运行时读数。
2. **未跑真实 world**（A4 的活）⇒ T-H5（`MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT` 归零、跨格成交回升）无实测。
3. **`trade` 产业的补货边界仍在**：工具补货需求只覆盖**经营 trade 的那个家户**（`trade@hex` 只有一个 unit、经营者一
   个）；同格其它跑商家户（如 `merchant.self_employed`）即便重获运力也要靠排序表把它们选去当 trade 经营者。
   本批**未**新开"跑商家户按剩余运力生成工具购买意图"那一档（属新机制，需设计书）。
4. **`MerchantCapacity` 的"劳动 + 工具"运力算式未动**（J-5 ①）⇒ 在服务成市格里它仍参与 tier/share 的派生
   （不是判据，但会让"占比"与真实施工量不同源）。本批记为已知残留，未改（改它会动排序与档位，需 world 对照）。
5. **`simos-app` 的读口/工具面未逐一复核**：词表联动面（`GovPortPolicyGuard`/`PortRegimeBridge`/
   `EconomySetCommodityFreightHandler`/`ApiViews`/`CommodityFreightBase`）A1 已复核，A3 未新增字段 ⇒ 未重跑。
6. **铁律 5**：本批**零**持久状态组件改动（无 `EconomyData`/`Codec`/`ChangeSet`/往返测试改动）——
   删掉的 `MarketRound.merchantHouseholds` 是**逐轮瞬态**字段（不进状态、不落盘），已自证。
7. 本批**未**`git commit`（控制方审后提交）。
