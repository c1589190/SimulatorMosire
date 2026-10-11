# W7「运力与跑商」实现架构账本（2026-10-11）

- **责任区**：W7 —— 运力与跑商（让"粮运得出去、跑商自己长出来"）。
- **权威**：`docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` **v3.26/v3.27 §7**
  （运力池全局共享 + 运力调查结论）；调查证据 `.superpowers/sdd/2026-10-11-haul-supply-investigation/report.md`（430 行）。
- **基线**：HEAD `be963bbb`（W8）；工作树开工时干净。
- **纪律**：只写生产代码（`simos-economy/src/main`、`simos-app/src/main`）；未写/未跑任何测试；未 commit；
  真命令只有 `tools/mvn-lock.sh -q spotless:apply` 与 `-DskipTests compile -am`。

---

## 1. 关键调查结论（file:line → 结论 → 影响）

| # | file:line | 结论 | 对实现的影响 |
|---|---|---|---|
| 1 | `MerchantCapacityPool.assemble` 旧 `:499-520` / `select` 旧 `:887` | 池按**发货格**取候选（`byHex.getOrDefault(from, …)`）；发货格没有承运方 ⇒ 该 lane 一个候选都看不到 | ① **全局分配序** `globalOrder`（唯一分配次序），候选集 = 全池 |
| 2 | `MarketSettlement` 旧 `:5196`、`:6105` | 候选生成处两道闸都只看**发货格**（`carrierPool.hasCapacityAt(sellerHex)`）⇒ 跨格车道**根本不建**，需求连一次求值机会都没有 | 两道闸都改成 `hasCapacityAt(hex) || totalCapacityMilli() <= 0`（全池口径） |
| 3 | `ExpectedProfitBook:628-635` | 非城市格 merchant 候选第一关 `NO_MERCHANT_CAPACITY:no-trade-industry`（无产业模板 ⇒ 整条链不被求值） | 本格无 merchant 模板时改判"**自备承运资产**"（CATTLE），有 ⇒ 用按需模板估；无 ⇒ 具名 `no-carrier-asset` |
| 4 | `ModeMigrationPolicy.perMoveNewRoom`（旧 `:1252-1281`） | **新建户**的承载判据要求"该格有该 mode 的产业模板 + 闲置资产够"；非城市格 `industriesForMode(merchant)` 回空 ⇒ 新建容量恒 0 ⇒ **一笔也不会起草** | 新增"按需模板"分支：用 `MerchantTradeTemplate.industry(hex)` 估，可用 CATTLE = **该户随迁携带**的那一份（按人口比例取整，与执行期同式） |
| 5 | `ModeMigrationPolicy.bestExistingTarget`（旧 `:1315`） | 非城市格的 merchant **壳户**（人口 0 ⇒ room 最大）会被当成并入目标 ⇒ 人到了、**没有 unit、活干不了** | merchant 目标格无产业模板 ⇒ `bestExistingTarget` 返 null（强制走"新建户"⇒ 建 unit） |
| 6 | `MerchantCapacityPool.sharePerMilleAsProviderAt` 旧 `:617-646` + `PrimaryModeRanking.merchantGateReason` 旧 `:287` | 决策层读"劳动 + 工具"、结算层读"手上的服务货"⇒ **两把尺**；`hh-0_2-urban-landlord` 实读 `no-bargaining-power` | ④ 统一到 `MerchantCapacityPool.providerCapacityMilli`（一处拼写，两侧同读） |
| 7 | `MarketSettlement.adaptPrices`（旧 `:2427-2505`） | 服务不进订单簿 ⇒ `haul` 的 D=S=0 ⇒ `adaptiveNextPrice` 恒等 ⇒ **牌价恒 4 毫、缺口不改变任何价格** | ② 把逐区运力 D/S 注入 `quantities[haul]`，交给**既有唯一算式** `adaptiveNextPrice` |
| 8 | `MerchantCapacityPool.LogisticsBlock.NO_SERVICE_GOODS` ≡ `HaulService.NO_SERVICE_SUPPLY_REASON`（同值 `no-haul-service-in-shipping-hex`） | 一个词被读成三件事（"没牌价"/"没承运方"/"没服务货"）；调查报告 §2.5-A2 实测归因被读反过一次 | ⑥ 拆成两个不同且自解释的名字 |
| 9 | `ModeMigrationPolicy` 旧 `:832-834` `continue` | 具名失败**一行日志都不打**（全仓 `grep NO_MERCHANT_CAPACITY` 只命中 `ExpectedProfitBook`） | ⑤ 逐候选 DEBUG + **轮级** DEBUG（含逐格候选数与首个失败理由） |
| 10 | `EconomySeeder:3534/3537/3573-3577` | `trade` 只在 `hasCraft = urbanPopulation > 0` 的格播种 | ③ 不改编世（创世仍是"城市格有商号"），只补上"**创世之外**也能长出商号" |

## 2. 实现架构

### 2.1 新增（economy：一处拼写、两侧引用）

- **`economy.model.MerchantTradeTemplate`**（新，模板唯一拼写点）：`industryId(hex)`、`industry(hex)`（纯函数，逐字段等于
  `EconomySeeder.trade(...)` 播出来的那一份）、以及全部具名常量（`CYCLE_DAYS=120`、`CATTLE_PER_UNIT=1`、
  `LABOR_MILLI_PER_UNIT=1000`、`HAUL_PER_UNIT_CYCLE=1`、`TOOL_MILLI_PER_UNIT_CYCLE=100`、`SPLIT 700/300`、
  `GENESIS_CATTLE_PER_CITY=100`）。
  **决策层的预估**（`ExpectedProfitBook`）与**执行期的写入**（`ModeMigrationSettlement`）与**承载判据**
  （`ModeMigrationPolicy.perMoveNewRoom`）读的是**同一份值** ⇒ "预估的那个商号"与"真长出来的那个商号"不可能漂开。
- **`economy.model.IndustryClassSlots`**（新）：四阶层槽位（id/名/劳动参与率‰）上移到 economy，
  `EconomySeeder.CLASS_IDS/CLASS_NAMES/CLASS_LABOR_PER_MILLE` 改为**指向本类**（名字与类型一字不变、逐值不变）。
  理由：按需创建的 `trade@hex` 在 economy 内部建，看不到 app 的常量。

### 2.2 ① 运力池全局共享（`MerchantCapacityPool`）

```
assemble：poolTotalMilli = Σ 全池预算；每条目的 sharePerMille 分母 = 全池（改前 = 本格）
          globalOrder = 全部条目按 [限价升序] → 全池议价权降序 → 家户 id 升序（I7：内容的纯函数）
select  ：候选集 = globalOrder（改前 = byHex.get(发货格)）
          三重闸（只可能更严，绝不放松既有分配）：
            ① servesTrip(from,to)：行程 = d(自家格,发货格) + d(发货格,收货格) ≤ 派生半径
                ★ 承运方在发货格时逐值退化为改前的 `hex.distanceTo(to) <= radius`
            ② 口径一致：服务成市的 lane 只由服务货口径的承运方接（否则 deliverHaulService 会 ERROR 并废单）
            ③ 剩余预算 > 0 且 ≥ 一个整商品单位（既有）
归因     ：② 的判据从"本格预算 ≤ 0"改成"**全池**装配时预算 ≤ 0"（全局共享后本格为 0 不再是拒绝理由）
```

### 2.3 ② 运力需求进价格 + 可被接单（`MarketSettlement` + `CapacityDemandBook`）

```
MarketSettlement.adaptPrices（既有唯一算式 adaptiveNextPrice 一字未改）：
  逐市场区注入 quantities[haul]：
    D = Σ 本区**成员格作为发货格**本轮派生出来的运输服务需求**全额**（毫商品·程；D 不扣运力截断 = §2.1 的刻意不对称）
    S = Σ 本区承运方手上**可交付**的运输服务货（= 池的逐格预算）
  缺运力 ⇒ D > S ⇒ 走既有的缺货分支（每轮 ≤ α）；运力充足 ⇒ S > D ⇒ 回落
  读数：INFO HAUL_PRICE_SIGNAL（逐区 D/S/未服务/α/牌价前后/providersWithService/demandByShippingHex）
CapacityDemandBook：新增 demandWorkByShippingHex() / servedWorkByShippingHex()（只读、保序）
```

**为什么这样就能"被接单"**（用户派单 ② 要求说明）：需求侧的量**不再是拒绝日志**——
① 它进了 §2.1 的 D ⇒ 缺口直接抬高 `haul` 牌价（`route.freightPerUnit` 由牌价派生）⇒ 承运更值钱；
② 全局池让**任意格持有服务货的商号**都能被分配到这条 lane（`select` 的候选集 = 全池，不再是发货格）；
③ `HAUL_PRICE_SIGNAL.demandByShippingHex` 把"谁在等运力"写成公开读数，`providersWithService` 同时给出供给侧家户数。

### 2.4 ③ 非城市格长出跑商（`ExpectedProfitBook` + `ModeMigrationPolicy` + `ModeMigrationSettlement`）

```
决策层 ExpectedProfitBook.merchantProspect：
  本格无 merchant 模板 ⇒ 该户**自有 CATTLE > 0** 才可行（具名 NO_MERCHANT_CAPACITY:no-carrier-asset），
  可行则用 MerchantTradeTemplate.industry(hex) 做**瞬态**预估（不落状态）
规划层 ModeMigrationPolicy：
  bestExistingTarget：merchant ∧ 本格无模板 ⇒ 返 null（不许并进没有 unit 的壳户）
  perMoveNewRoom：merchant ∧ 本格无模板 ⇒ 用按需模板 + "随迁携带的 CATTLE"（按人口比例 floor，与执行期同式）判承载
执行层 ModeMigrationSettlement：
  ① 逐目标格按需创建 trade@hex（写 EconomySession 工作副本 ⇒ 随本 tick 变更集落同一 revision；
     与 EconomyUpsertIndustry 同形：同一份字段形状/同一份构造期守卫）
  ② 执行前**具名过滤**：带不来 CATTLE 的按需笔整笔跳过（INFO ONDEMAND_TRADE_MIGRATION_SKIPPED）
  ③ industries 工作副本贯穿 hostIndustryFor / createEnterpriseAndProcess / OwnershipStakeBook.apply|rebuild /
     buildMigrationRelation（同一 tick 内后续笔看得到刚落的那一条）
  ★ 不凭空造资产：模板只是配方；CATTLE 只能由该户**自备/随迁携带**（D-023 §4 跨 hex 重建同 AssetKind、源份额同量减少）
```

### 2.5 ④ 两把尺统一

```
MerchantCapacityPool.providerCapacityMilli(base, household, hex, goods)   ← 唯一派生量
  服务成市的格：max(手上的服务货, 劳动 + 工具能产出的量)
  其余格      ：劳动 + 工具（逐值退回改前）
读它的三处：ExpectedProfitBook.merchantProspect（预估的运力规模/tier）
            PrimaryModeRanking.merchantGateReason（候选门槛）
            MerchantCapacityPool.sharePerMilleAsProviderAt（议价权占比）
```
**选了哪一侧、为什么**（用户要求具名）：**以结算层"能真交付服务"为主，并保留决策层"本周期能产出多少"**
⇒ 取**较大者**。只取服务货会"先有鸡还是先有蛋"（刚开张的商号本周期还没有服务货 ⇒ 永远当不上主业 ⇒ 永远不产出）；
只取劳动 + 工具就是改前那处误判（有服务货、劳动 0 的户被判"没有运力"）。取并集 ⇒ 两种误判同时消失，且
**方向上单调**：服务成市格里新判据 ≥ 改前判据（决策层只会接受更多候选，每一个仍要过诚实预估与"自备承运资产"约束）。
★ **结算层的运力预算一个字没动**（仍 = 手上的服务货，I-H2：卖多少就得有多少货）。

### 2.6 ⑤ 具名失败可观测 / ⑥ 改名

```
⑤ 逐候选 DEBUG MODE_MIGRATION_PROSPECT_INFEASIBLE（只对 NO_MERCHANT_CAPACITY:* 一族发）
     字段：household / modeId / hex / reason / deliverableCapacityMilli（关键量级）
   轮级 DEBUG MODE_MIGRATION_CANDIDATES（**有没有迁移都发**）
     字段：candidateRows / merchantCandidates / byHex / merchantCandidatesByHex /
           merchantFirstFailureByHex（每格首个失败理由）/ failuresByReason / moves / movedPopulation
   ★ 级别取舍：§一.9 把"业务拒绝"定为 INFO，但这里**不逐候选发 INFO**（非城市格每天每户都会产生同样的拒绝，
     逐行 INFO 会淹掉"这一轮发生了什么"）⇒ 轮级一行承担可读性，逐候选明细留 DEBUG。已记入偏离。
⑥ HaulService.NO_SERVICE_SUPPLY_REASON → NO_DELIVERABLE_SERVICE_GOODS_REASON = "no-deliverable-haul-service-goods"
     （货侧：承运方手上没有可交付的服务货）
   MerchantCapacityPool.LogisticsBlock.NO_SERVICE_GOODS → NO_CARRIER_WITH_SERVICE
     = "no-carrier-with-deliverable-service"（池侧：全池没有一个能覆盖本 lane 且手上有服务货的承运方）
```

## 3. 关键判断（为什么这样拆 / 推翻过什么）

1. **半径口径 = 总行程**（`MerchantCapacity.servesTrip`，新增）。全局共享后承运方不一定住在发货格，
   用户 M0d 的口径是"「运力过来同样需要运力」⇒ 深度 1 + 固定附加费"，附加费仍是固定 25‰（不随距离）。
   物理可达性由"总行程 ≤ 派生半径"表达 ⇒ 唯一既**不让既有世界变松**（H == 发货格时逐值退化）、
   又**不让远格白蹭**（多一段非负里程）的写法。
2. **`sharePerMille` 的分母改成全池**：分配序已经是一份全局序，分母若仍是"本格"就会出现"占比 1000‰ 的脚夫
   排在占比 100‰ 的富商前面"（改前逐格语境的残留）。只有一个非空格的世界里两种分母逐值相同。
3. **议价权仍留在分配序里**（价格优先之后）：既有 Q-25/G-1 口径不动；W7 只换分母与候选集。
4. **服务 lane 与未定价 lane 不许互相承接**：全局共享把两类承运方（服务货口径 / 劳动+工具口径）放进同一候选集，
   若混接，`deliverHaulService` 会在"劳动+工具"口径的户上找不到 `haul` 货 ⇒ 具名 ERROR 且整笔废单。
   加一道口径闸（两个判据在未定价世界里恒等 ⇒ 缺省中性）。
5. **按需创建放在"执行前"而不是"用完再建"**：同一 tick 内 `hostIndustryFor`（重建 CATTLE 的宿主）与
   `createEnterpriseAndProcess`（找模板 + 建 unit + 建关系）都要看到它；两处都读工作副本。
6. **执行期要有一道"带不来牲口就跳过"的闸**：`createEnterpriseAndProcess` 在"新建户凑不齐目标产业租赁资产"时
   **当场抛**（fail-closed），而按需建的 `trade@H` 下唯一能有的 CATTLE 就是这一笔背过来的 ⇒ 判据在决策层挡过一轮，
   执行期再挡一轮（INFO 具名 + 不搬人不搬资产），绝不让 tick 抛（世界会卡死）。
7. **推翻过什么**：
   - 先想过"让迁移执行对被并入的零人口壳户补建 unit"（方案 B），推翻了：`bestExistingTarget` 直接不选它们更短、
     更不容易漏（且新建户本来就走既有的 `createEnterpriseAndProcess` 全套）。
   - 先想过"空闲 CATTLE ≥ 1 即算自备"（用 `claimedByEnterprises` 排除），推翻了：该集合**包含该户自己组织**的
     `assetSources` ⇒ 会把"已在自己商号里的牲口"误判成不可用（那恰恰是最常见的形态）。
   - 先想过把 `NO_SERVICE_GOODS` 的池侧判据保留"本格"，推翻了：那正是 W7 要拆掉的那道限制。

## 4. 偏离记录（控制方口径 / 我自定的口径）

| # | 项 | 性质 |
|---|---|---|
| D1 | 按需创建 `trade@hex` + **牲口自备/随迁携带** | **控制方冻结口径**（任务书 §2-③），照此实现 |
| D2 | 全局共享的运费计价与跨币折算"照 D-023 §2（按付款方的家户价目表）" | ★ **与用户 2026-10-10 第二批第 6 条冲突**：家户价目表已被该裁定废止（价格表市场区一张）。本实现走**既有的** `BuySlot.payAmountOf`（按付款方对**本区价表**的估值折算；说不出价 ⇒ 具名跳过 `logFreightCurrencyUnvalued` + 本笔不成交）⇒ 只做了"跨区/跨币也能被雇"（用户本轮裁定），**没有**恢复家户级价表。理由：最新裁定优先（AGENTS §四.1）。**未上报用户**（不涉及用户原话的直接否定，属既有取代链的落实） |
| D3 | "运力过来"的可达性 = **总行程 ≤ 半径**（不是"固定附加费"之外的第二个价） | 控制方口径只写了"深度 1 + 固定附加费"（价格侧），没写半径口径；我选了 fail-closed 的总行程。**可被推翻** |
| D4 | ⑤ 的级别：逐候选 = DEBUG（不是 INFO） | §一.9 把业务拒绝定为 INFO；我按"轮级 INFO 可读性 > 逐行 INFO 噪声"取舍（理由与量级见 §2.6）。**可被推翻** |
| D5 | `providerCapacityMilli` 取"较大者"（并集）而不是纯服务货 | 用户派单 ④ 允许"以能真交付服务的那一侧为准**或**两侧都读同一份派生量"；我取后者并保留两条交付路径（理由见 §2.5）。**可被推翻** |
| D6 | 无 merchant 模板的格 ⇒ `bestExistingTarget` 恒返 null（不许并入壳户） | 任务书未点名；不加它则"并入没有 unit 的户"⇒ 人到了活干不了（③ 的判据不成立）。**控制方口径的自然延伸** |
| D7 | `MerchantTradeTemplate` 的常量由 seeder **别名引用**（`EconomySeeder` 改 10 行常量定义） | 任务书允许改 `simos-app/src/main`；不这么做就有两份模板拼写点。**值逐字未改** |
| D8 | **按需建商号的规模从 1 单位起步**（规划层的资产门槛传 `laborPerUnit` ⇒ scale = 1，不是 `laborEstimate`） | ★ 这是本批**实现期发现并修掉的自身缺陷**（详见 §6）—— 执行期新建行的 labor = 0（P0 投影）⇒ 它的 `scale` 本来就是 1；规划层若按"整户劳动撑满的规模"要 CATTLE，会把这条路在规划期自己堵死（城镇商号 612,000 劳动 ⇒ 要 612 头，而它只有 100 头）。改后产业规模仍由**资产派生**（带 10 头 ⇒ 10 单位规模）。**可被推翻** |

## 5. 交账自检

| 项 | 结果 |
|---|---|
| 改动面 | 只改 `simos-economy/src/main`（10 文件）+ `simos-app/src/main`（1 文件）+ 2 个新文件；`git status` 无测试/文档/其他模块 |
| Maven | `tools/mvn-lock.sh -q spotless:apply` rc=0；`tools/mvn-lock.sh -q -DskipTests compile -am` rc=0（多轮） |
| 测试 | **一条没写、一条没跑**（§一.5） |
| commit | 没有 |
| 未验证面 | 见交账报告 §9（无真实 world 读数：本批纪律只允许编译，运行级证据欠给测试批） |

---

## 6. 实现期自查抓到的两处自身缺陷（如实记）

1. **`TOOL_MILLI_PER_UNIT_CYCLE` 一度写成 12,000（真值 = 100）**：`EconomySeeder` 的原式是
   `HAUL_PER_TRADE_UNIT_CYCLE × MILLI_PER_COMMODITY_UNIT × HAUL_TOOL_PER_MILLE_OF_SERVICE ÷ 1000` = 1×1000×100÷1000 = **100**
   （12,000 是 `MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI` 那一份**创世禀赋**，不是产业周期投入）。
   抓到方式 = 读 `HaulServiceCommoditySeedTest:135-141` 的断言式（逐值 100）。**已改回 100**；
   若不改，按需创建的 `trade@hex` 的工具投入会与创世那一份差 120 倍（"同一份模板"当场不成立）。
2. **规划层与执行期的规模口径不一致**（见 D8）：规划层用 `laborEstimate` 推规模 ⇒ 门槛比执行期严 6 倍以上 ⇒
   `perMoveNewRoom` 对任何既有家户都返回 0 ⇒ ③ 的判据在真实世界里**一笔也长不出来**。已按执行期口径改成 scale = 1。
   ★ 这两处都不是"编译能发现"的错（编译绿），是**回代码核口径 + 读既有测试断言**抓到的。

---

# W7-fix（取消牲口门槛：运力 = 劳动 + 工具）

- **责任区**：W7-fix —— 按用户裁定 v3.28 §7 取消"自备牲口"门槛，跑商投入口径改成**劳动 + 工具**；
  顺手修 F1（读侧归因）与 F2（`HaulService.NO_DELIVERABLE_SERVICE_GOODS_REASON` 零发射点）。
- **用户原话（逐字，最高权威）**：「**能不能先把牲口要求取消掉啊？目前是在跑测试，算作工具需求得了**」
- **权威文档**：`docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` **v3.28 §7**；
  既有 J-B 口径 = `docs/superpowers/specs/2026-10-09-commodity-freight-and-merchant-ladder-design.md` §14.3
  「运力 = f(劳动投入, 工具消耗)」。
- **基线**：W7 未提交工作树（HEAD 仍是 `be963bbb`）。
- **纪律**：只写生产代码（`simos-economy/src/main`、`simos-app/src/main`）；未写/未跑任何测试；未 commit。

## 1. 关键调查结论（file:line → 结论 → 影响）

| # | file:line | 结论 | 对实现的影响 |
|---|---|---|---|
| 1 | `ExpectedProfitBook:627-648`（旧）、`:685-688`（旧） | W7 的门槛 = "该户**自备 CATTLE > 0**"（否则 `no-carrier-asset`）；实测真世界 9,927(sw)/22,368(tp) 行因此被拦、`MERCHANT_TRADE_INDUSTRY_CREATED` = 0 | 换判据：劳动 + 工具（新具名 `no-labor` / `no-tool-input`），删 `no-carrier-asset` |
| 2 | `Industry:363-366` | **`capacityPerUnit` 不得为空**（构造期抛：它是"单位规模"的锚，"没有它规模无上界"） | ⇒ 不能把模板的 `capacityPerUnit` 删空；作业资本那一维必须落到某个 `AssetKind` |
| 3 | `ProductionProcessBook:126-131`、`SettlementIndex:458-462`、`EconomySettlement:8641` | 生产规模 = `min(可用资产 ÷ capacityPerUnit)`；**空表 ⇒ 规模 0 ⇒ 无产出**（`harvest` 的 `netByCommodity.isEmpty() ⇒ return`） | ⇒ 若 `capacityPerUnit` 为空，按需建的商号是**零产出的空壳**；故作业资本必须**真实存在**（工具） |
| 4 | `ModeMigrationPolicy:1397-1418`（旧 `carriedCarrierAssetsForMove`）、`ModeMigrationSettlement:1739-1758`（旧 `carriedCattle`） | 规划层与执行期各有**一份**"随迁携带 CATTLE"的算式 | 两处都改成读 `MerchantTradeTemplate.capacityKinds()`（键集唯一拼写点） |
| 5 | `ModeMigrationSettlement:1665-1733`（旧 `filterOnDemandMerchantMoves`） | 执行期闸门：带不来 CATTLE 的按需迁移笔整笔跳过（`no-carrier-asset-to-host-on-demand-trade-industry`） | 判据随之改成"作业资本（工具）"，理由改名 `no-tool-input-to-host-on-demand-trade-industry`（§一.11 不留旧名） |
| 6 | `MerchantCapacityPool:1016`（旧）、`logisticsBlockOf` 旧形参 `pool` | 归因用的"池"是**发货格的切片**，而候选集自 W7 起已是**全池** ⇒ 发货格无承运方时误报 `no-merchant-household-in-shipping-hex`（哪怕全池有货、只是都超半径） | F1：归因改读 `globalOrder`（纯读数面，数值不动） |
| 7 | `HaulService:124`、`MerchantCapacityPool:232`（旧） | `NO_DELIVERABLE_SERVICE_GOODS_REASON` 在 main **零发射点**；池侧另有一串同义的 `merchant-household-holds-no-service-goods`（真有发射点） | F2：两串合并 —— 唯一拼写点留在 `HaulService`，池侧常量**引用**它 |
| 8 | `EconomySeeder:3687-3704`（`TRADE.equals(plan.kind()) ⇒ 不重发 unit`）、`:5191-5207`（`ownershipStakesOf`） | trade 的 unit 由解析器从 industry 的 legacy operator 合成，作业资本走 `assetShares` 出口 | 创世那份作业资本必须**显式播种**（否则新 unit 无份额 ⇒ 规模 0） |

## 2. 实现架构（改了什么、口径落在哪）

```
① 决策层可行性（ExpectedProfitBook）
   merchantInputFailureReason(householdEconomy, household, accounts)   ← 新增，唯一判据
     劳动 = householdEconomy.participationAdjustedLaborMilli() > 0 ? 继续 : NO_MERCHANT_CAPACITY:no-labor
     工具 = accounts.householdGoods()[household][tool] > 0        ? 继续 : NO_MERCHANT_CAPACITY:no-tool-input
   ★ 判据位置挪到"解析产业模板之前"（改前它在 no-trade-industry 之后）⇒ 理由永远具体；
     本格有模板的户同样适用（改前那种情形落到下游更笼统的 no-capacity）。
   ★ 删：selfSuppliedCarrierAssetMilli（唯一消费者是本闸）+ 理由 no-carrier-asset（grep 确认无其它消费者）。
   ★ 顺带删 W7 留下的死变量 selfSuppliedTradeIndustry（写后不读，§一.11）。

② 运力派生量（MerchantCapacityPool.providerCapacityMilli）—— **一行未改**
   劳动 + 工具能产出的量 = MerchantCapacity.capacityMilliOf(participationAdjustedLaborMilli, tool 存量)
   ⇒ 用户裁定要的"运力 = 劳动 + 工具"**本来就是这一份**（W7 的两把尺统一已把它接到决策/结算两侧）；
     本批只把"可行性"从牲口挪到这条算式。结算层的规模口径（手上的服务货）**一字未动**（I-H2）。

③ 模板（MerchantTradeTemplate）—— 牲口一个字段都不留
   capacityPerUnit   : {CATTLE: 1}            → {TOOL: TOOL_PER_UNIT}(= 100 毫工具)      ← 作业资本
   cycleInputPerUnit : {CATTLE: {tool: 100}}  → {tool: {tool: 100}}                     ← 只换分路键，值未改
   capacityKinds()   : 新增 —— 作业资本那一维的**唯一拼写点**（返回 {TOOL}），industry() 由它派生键集
   删常量：GENESIS_CATTLE_PER_CITY、CATTLE_PER_UNIT（§一.11）

④ 创世（EconomySeeder）
   trade(...) 的 capacity        : {CATTLE: 100} → {TOOL: MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI}(12,000)
   trade(...) 的 capacityPerUnit : {CATTLE: 1}   → {TOOL: MerchantTradeTemplate.TOOL_PER_UNIT}
   cycleInputPerUnit 的分路键     : CATTLE → TOOL
   删常量：MERCHANT_CATTLE_PER_CITY（唯一读者就是上面那一行；不留"第二个拼写点"）
   ★ 不新增任何商品量：新份额 = 商号本金主**已经拿到**的那份启动工具，只多一条产权份额登记。

⑤ 迁移三处判据（Policy 规划层 / Settlement 执行层）
   carriedCarrierAssetsForMove : asset == CATTLE → asset ∈ MerchantTradeTemplate.capacityKinds()（= TOOL）
   filterOnDemandMerchantMoves : carriedCattle → carriedCarrierAssets；日志字段 carriedCattle →
                                 carriedToolInput；理由 no-carrier-asset-… → no-tool-input-…
   MERCHANT_TRADE_INDUSTRY_CREATED : 字段 cattlePerUnit → toolPerUnitMilli（读模板 capacityPerUnit）

⑥ F1（MerchantCapacityPool.select）
   candidatePool = globalOrder（新增局部量）；logisticsBlockOf / laneBlockedReason 的"池非空"判据改读它
   ⇒ 全池有货但全超半径 ⇒ out-of-derived-radius；全池真空 ⇒ 才报 no-merchant-household-in-shipping-hex
   ★ choices / unallocatedMilli / 扣减：一字不动（归因是读数）。

⑦ F2（HaulService + MerchantCapacityPool）
   MerchantCapacityPool.NO_SERVICE_GOODS_REASON = HaulService.NO_DELIVERABLE_SERVICE_GOODS_REASON
   ⇒ 该常量从"main 零发射点"变成"唯一拼写点"（真发射点 = 装配期 serviceAbsent 计数，进逐轮 INFO 的
      serviceGoodsAbsentHouseholds 栏）。

⑧ 日志面（§一.9）
   MODE_MIGRATION_PROSPECT_INFEASIBLE 增两栏 laborMilli / toolMilli —— 新理由（no-labor / no-tool-input）
   必须能在同一行里自解释（只报 deliverableCapacityMilli 时"运力明明 > 0 却不可行"读不出原因）。
```

## 3. 关键判断（为什么这样拆）

| # | 判断 | 理由 / 代价 |
|---|---|---|
| J1 | **作业资本那一维 = `TOOL`（不是把 `capacityPerUnit` 删空）** | `Industry` 的构造期守卫不允许空表（它是"单位规模"的锚），且空表 ⇒ 生产规模恒 0 ⇒ 按需建的商号是零产出空壳（结论 #3）。落到 TOOL 既满足"投入 = 劳动 + 工具"，又让规模有上界 |
| J2 | **`TOOL_PER_UNIT = TOOL_MILLI_PER_UNIT_CYCLE`（= 100，一个周期的工具投入）** | 让"压住的作业资本"与"一周期要烧的工具"同值 ⇒ 规模 = 工具存量 ÷ 每周期工具强度。创世禀赋 12,000 毫 ⇒ 城市格规模 **120**（旧 CATTLE 锚下是 100）⇒ 同量级，且"一个周期恰好烧完一个禀赋"这条自洽性逐值成立。★ **这是标定选择，不是推出来的真理，可被推翻** |
| J3 | **创世那份作业资本逐值取 `MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI`（不新拍数）** | 同一家户同一份工具，只多一条产权份额登记 ⇒ 不新增商品量、不改工具市场 |
| J4 | **决策层判据挪到"解析模板之前"** | 缺投入与本格有没有模板是两件事；挪前面才能给出具体理由（no-labor / no-tool-input 而不是笼统的 no-capacity）。★ 方向 fail-closed（新判据只**收紧**，不放宽），且它服务的正是用户裁定 |
| J5 | **F2 取"接到发射点"而不是"删常量"** | 用户派单给了二选一。两串说的是同一件事（承运方手上可交付服务货为 0），真正要分辨的那一刀是"没有提供商 vs 有提供商没货"（各自**本来就不同值**，合并货侧两串不会混）。删掉会让货侧那个自解释名字消失，接上则两个名字都活 |
| J6 | **F1 改归因的"池"操作数，不改候选集** | 候选集自 W7 起已是全池；归因读本格切片是**漏改**（同一批的第二种"两把尺"）。纯读数面 ⇒ 数值零影响 |

## 4. 偏离记录 / 待控制方裁定

| # | 偏离 | 说明 |
|---|---|---|
| E1 | 模板的作业资本取 `AssetKind.TOOL`，且 **`capacityPerUnit` 的量纲 = "每 1 单位规模压 100 毫工具"** | 任务书 ③ 说"把模板里的投入口径改成工具"；同时 §一.11 要求不留没人读的 CATTLE 字段，而模型不允许 `capacityPerUnit` 为空 ⇒ 只能落到某个 `AssetKind`。我选了 TOOL（= 工具），标定值取"一个周期的工具投入"（见 J2）。**若控制方要的是"工具只作为周期投入、规模锚另立"，需要先改 `Industry` 的锚语义（本批不动）** |
| E2 | 城市格 `trade` unit 的规模从 120 起步（旧：100） | 会改变数值行为（见下方 §5.1 清单第 1 条）。它不是我"顺手改"的：它是"作业资本从牲口换成工具"的**直接后果**（旧规模被 100 头牲口钉住）。★ 若要求与改前逐值相同 ⇒ 需把创世工具禀赋提到 100,000 毫（8.3×），那会显著改变工具市场 ⇒ 本批不做，交控制方裁定 |
| E3 | 只给**商号本金主**（城镇 landlord 家户）播了作业资本份额 | 与 W7 的 CATTLE 口径一致（旧世界也只有城市格的两户持 CATTLE）。后果：其它城镇跑商家户**没有**作业资本份额 ⇒ 它们往非城市格"按需建商号"的迁移会被执行期闸门跳过（会具名 INFO）。★ 让它们也有份 = 要给每个城镇 merchant 家户播 TOOL 份额（会显著改变创世）⇒ 本批不做，如实记 |
| E4 | 删了 W7 的死变量 `selfSuppliedTradeIndustry` | §一.11：写后不读的变量直接删（编译期无警告，但它是"看起来在记"的一族） |
| E5 | 日志字段改名（`carriedCattle`→`carriedToolInput`、`cattlePerUnit`→`toolPerUnitMilli`） | §一.11 不留旧名。会影响读数面/测试断言（见交账 §6） |

## 5. 交账自检

| 项 | 结果 |
|---|---|
| 改动面 | `simos-economy/src/main`（5 文件）+ `simos-app/src/main`（1 文件）；未动任何 `src/test`、未动创世之外的模块 |
| Maven | `tools/mvn-lock.sh -q spotless:apply` rc=0；`tools/mvn-lock.sh -DskipTests compile -am` **BUILD SUCCESS** rc=0（多轮） |
| 测试编译 | `-DskipTests test-compile -am` **rc=1**：唯一失败 = `MerchantOnDemandTradeW7Test:182` 引用已删的 `MerchantTradeTemplate.CATTLE_PER_UNIT`（**只报不改**，测试迁移归测试批） |
| 测试 | **一条没写、一条没跑**（§一.5） |
| commit | 没有 |
| 未验证面 | 无运行级读数：F1/F2 与"非城市格长出跑商"的实际读数欠给测试批（test-compile 被上面那条断言挡住 ⇒ 本 Agent 跑不了单类回归） |

## 5.1 会改变数值行为的清单（交测试批的输入）

| # | 改前 | 改后 | 影响面 |
|---|---|---|---|
| 1 | 城市格 `trade` unit 的规模锚 = `{CATTLE: 1}` × 100 头牲口 ⇒ 规模上界 **100** | 规模锚 = `{TOOL: 100}` × 12,000 毫工具 ⇒ 规模上界 **120**；真正规模 = min(劳动那一路, 投入满足率那一路) | 逐格运输服务产量、周期工具消耗、`haul` 牌价（自 W7 起由 D/S 自适应）、跨格成交量 |
| 2 | 非城市格的 merchant 候选：**自备 CATTLE > 0** 才可行 | **劳动 > 0 ∧ 工具 > 0** 才可行（不再看 CATTLE） | 决策层候选集（变宽）、`MODE_MIGRATION_CANDIDATES` 的 `failuresByReason` 分档 |
| 3 | 按需迁移执行闸门看"能带多少 CATTLE" | 看"能带多少作业资本（TOOL 份额）" | `ONDEMAND_TRADE_MIGRATION_SKIPPED` 的条数与理由字符串 |
| 4 | 创世每个城市格播 `{CATTLE: 100}` 份额给商号本金主 | 播 `{TOOL: 12,000 毫}` 份额（= 同一家户已拿到的启动工具） | 该户的 `OwnershipStake` 组成、资产规则链、`HouseholdClassRule` 的资产档判据（TOOL 走既有 TOOL 档） |
| 5 | F1 归因：发货格无承运方 ⇒ `no-merchant-household-in-shipping-hex` | 全池为空才报它；全池有货但全超半径 ⇒ `out-of-derived-radius` | **只读**：DEBUG 行 `reason` / `logisticsDetail`。数值零影响 |
| 6 | F2 货侧缺货键 = `merchant-household-holds-no-service-goods` | = `no-deliverable-haul-service-goods`（`HaulService` 的串） | **只读**：逐轮 INFO 的 `serviceGoodsAbsentHouseholds` 键名。数值零影响 |
| 7 | `MODE_MIGRATION_PROSPECT_INFEASIBLE` 只有 `deliverableCapacityMilli` | 另加 `laborMilli` / `toolMilli` | **只读**（DEBUG 行字段增加） |

## 5.2 硬编码字面量

`MerchantTradeTemplate.TOOL_PER_UNIT = TOOL_MILLI_PER_UNIT_CYCLE`（= 100 毫工具/单位规模）；
`MerchantTradeTemplate.capacityKinds() = {AssetKind.TOOL}`；
`EconomySeeder` trade 的 `capacity = {TOOL: MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI}`（= 12,000 毫，既有常量）；
理由字面量 `NO_MERCHANT_CAPACITY:no-labor` / `NO_MERCHANT_CAPACITY:no-tool-input` / `no-tool-input-to-host-on-demand-trade-industry`；
日志字段名 `carriedToolInput` / `toolPerUnitMilli` / `laborMilli`（在 `MODE_MIGRATION_PROSPECT_INFEASIBLE` 行内）。

## 5.3 会让既有测试失效的清单（**只报不改**）

| 类 | 失效点 | 需要的迁移 |
|---|---|---|
| `MerchantOnDemandTradeW7Test` | **编译不过**：`:182` 引用已删的 `MerchantTradeTemplate.CATTLE_PER_UNIT`；另 `:129` 断言 `no-carrier-asset`（该理由已删）、`:222` 断言 `reason=no-carrier-asset-to-host-on-demand-trade-industry`（改名）、夹具 `:390-402` 只给 CATTLE 份额且账户无工具（新判据下必然 `no-tool-input`） | 按新裁定迁成"无劳动/无工具 ⇒ 不可行" + "带得来作业资本（TOOL）⇒ 建商号、守恒" |
| `ExpectedProfitBookTest:310/328` | 注释说 `NO_MERCHANT_CAPACITY`（D-024 §3.2.5）；断言用 `startsWith("NO_MERCHANT_CAPACITY")` ⇒ 只要夹具户有劳动/工具就仍绿，否则理由变成 `no-tool-input` | 需测试批复核夹具的劳动/工具 |
| `HaulGlobalPoolW7Test` | `:341-361` 断言两条理由**互不相同**且 `HaulService` 那串 = `no-deliverable-haul-service-goods` | 本批**不破**（两条仍不同值）★ 但它是 F1 的最小复现夹具（去掉 `emptyLocalMerchant` 即变） |
| 其它引用 trade 作业资本的测试 | `grep "CATTLE" simos-economy/src/test` 只命中上面两类；`simos-app` 测试未查全（test-compile 在 economy 就停了） | 测试批需重跑 `test-compile -am` 看全 |

---

# W7-fix2（E1 接受 / E2 保规模锚 100 / E3 记为遗留门槛）

> 批次：W7-fix2（2026-10-11，控制方裁定，写码 Agent 执行）。上文 W7 / W7-fix 各节**不改**（留痕纪律）；本节只记本批的改动、口径与遗留。
> 硬边界：只动 `simos-economy/src/main/**`（实际 **1 文件**）；**未**碰 `src/test/**`、其他模块、`docs/**`、`AGENTS.md`；**未** commit；**未**跑 `test` / `verify` / `package` / `clean`。

## 1. 改动点（file:line，前后对照）

唯一改动文件 = `simos-economy/src/main/java/io/mosire/simos/economy/model/MerchantTradeTemplate.java`（生产代码 **1 个常量** + 类型注）。

| 行 | 改前 | 改后 |
|---|---|---|
| `:152-153`（常量本体） | `public static final long TOOL_PER_UNIT = TOOL_MILLI_PER_UNIT_CYCLE;`（= 100） | `public static final long TOOL_PER_UNIT = 12_000L / 100L;`（= **120**） |
| `:38`（类注「形状」） | `capacityPerUnit = {TOOL: 100}`（100 = 一个周期的工具投入） | `{TOOL: 120}`（120 = 12,000 ÷ 100 ⇒ 城市格规模锚**保持 100 单位**） |
| `:60-64`（类注「规模锚 / 周期投入」） | 两行并列、未说明二者关系 | 明确**不是同一个量**：120 = 每 1 单位规模**压住**的作业资本（存量）、100 = 每 1 单位规模每周期**烧掉**的（流量），本批刻意**不要求相等** |
| `:69-71`（类注「逐条后果 ③」） | 锚"从牲口头数变成工具作业资本量 ⇒ **会改变数值行为**" | 锚那一**维**变、锚**值**仍 = 100 单位规模 ⇒ 相对 W7 前**数值行为不变**（变的只是压住规模的资产**种类**） |
| `:105-108`（`TOOL_MILLI_PER_UNIT_CYCLE` 注） | "它同时是作业资本那一维的每单位参照" | 更正：它**不再**兼任锚的取值；两者是不同量、不要求相等 |
| `:111-153`（`TOOL_PER_UNIT` 注） | 取值理由 = "与周期投入同值 ⇒ 规模 120，'一个周期恰好烧完禀赋'自洽" | 重写：120 的算式与来源、量纲、**进入哪些算式**、为什么退回"保规模 100"、以及被放弃的那条自洽性（代价如实记） |

**为什么这样能保住规模锚 100（算式）**：规模 = `⌊可用[TOOL] ÷ capacityPerUnit[TOOL]⌋`（`ProductionProcessBook.capacityScaleOf`，`simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionProcessBook.java:127`）；创世给城市格 `trade@hex` 登记的作业资本 = `{TOOL: 12,000}` 毫工具（`EconomySeeder.trade(...)` 的 `capacity`）⇒ `⌊12,000 ÷ 120⌋ = 100`（= W7 的 CATTLE 口径 `{CATTLE: 1} × 100 头`）。
★ **产物实测（非推断）**：`javap -constants` 得 `TOOL_PER_UNIT = 120l`、`TOOL_MILLI_PER_UNIT_CYCLE = 100l`；`EconomySeeder.class` 的 `trade(HexCoord, ActorRef)` 里 `capacityPerUnit` 已内联为 `long 120l`（常量被 javac 内联 ⇒ 本轮 `-am` 真重编了 economy 217 源文件 + app 332 源文件才有意义）。

## 2. `capacityPerUnit` 进入哪些算式的结论（回代码核，★ 任务书要求）

| 用途 | 落点（file:line） | 算式 |
|---|---|---|
| **规模反解**（唯一影响数值的那一路） | `ProductionProcessBook.java:127`；`SettlementIndex.java:458`；`ExpectedProfitBook.java:843`；`EconomyEnterpriseSettlement.java:1056` | `规模 = min_k ⌊可用[k] ÷ capacityPerUnit[k]⌋` |
| 按目标规模补资产 / 迁移覆盖 | `EconomyEnterpriseSettlement.java:885-895`；`ModeMigrationSettlement.java:994` | `需求 = 规模 × capacityPerUnit[k]` |
| 只读键集判断（不产生量） | `OwnershipStakeBook.java:309`；`ModeMigrationSettlement.java:213-218`（日志栏 `toolPerUnitMilli`） | `containsKey` / 日志读数 |

**它不进运力算式**：运力 = `MerchantCapacity.capacityMilliOf(劳动投入, 工具**商品账**存量)`（`MerchantCapacityPool.java:698` → `MerchantCapacity.java:198`），两个操作数都是**流量 / 现货**，与锚无关。
⇒ 口径定稿：锚 = "**每单位规模的资本占用**"（规模上界 = 作业资本**存量** ÷ 锚值），只经"规模"**间接**进产出（`产出 = outputPerUnit × 规模`），**不是**"每周期成本"，也**不是**生产函数里让资本直接乘产出的系数。

## 3. 三条裁定的落账

| 裁定 | 落账 |
|---|---|
| **E1 接受**（作业资本必须有锚；模型不允许 `capacityPerUnit` 空表） | 保留 `capacityKinds() = {TOOL}` + `capacityPerUnit = {TOOL: TOOL_PER_UNIT}`；类型注写明"作业资本 = **TOOL**，量纲 = 每 1 单位规模压 **120 毫工具**" |
| **E2 改判：城市格规模锚保持 100，不许顺带改标定** | `TOOL_PER_UNIT` 100 → **120**（= 12,000 ÷ 100）；类型注写明"`capacityPerUnit`（作业资本锚）与 `cycleInputPerUnit`（每周期烧的工具）**不是同一个量，不要求相等**；本批刻意让锚保持既有的 **100 单位规模**" |
| **E3 本批接受（最小改动），记为遗留门槛** | 代码**一字未改**（不在本批实现），只入账 ⇒ 见 §4 |

## 4. 遗留门槛（E3：**仍然存在的门槛**，本批不实现）

**门槛**：非城市格要"按需长出跑商"，源户必须**带得来作业资本（`TOOL` 份额）** —— 决策层 `ModeMigrationPolicy.carriedCarrierAssetsForMove` 只数 `MerchantTradeTemplate.capacityKinds()` 那一维的份额，执行期 `ModeMigrationSettlement` 再按同一维具名跳过；而**现在只有"商号本金主"（城市格 merchant principal 家户）持有这份 `TOOL` 份额**（创世播种口径，与旧 CATTLE 口径一致）。
⇒ 若真世界里**一户新跑商都没出现**，这就是**下一个要裁的结构项**。候选（**未选、未做**）：
1. 给**每个城镇 merchant 家户**播一份 `TOOL` 作业资本份额；
2. 让按需商号的规模**直接由该户自己的工具与劳动派生**，不要求"份额"这一前置。

## 5. 交账自检

| 项 | 结果 |
|---|---|
| 改动面 | `simos-economy/src/main` **1 文件**（1 常量 + 类型注）；未碰 `src/test/**` / 其他模块 / `docs/**` / `AGENTS.md` |
| `tools/mvn-lock.sh -q spotless:apply` | **rc=0**；实测只重写了本文件（mtime 19:19:32），`git status` 文件集与本批开工前**逐项相同** ⇒ spotless 未顺带动别的文件（§五 的同族坑已核） |
| `tools/mvn-lock.sh -DskipTests compile -am` | **rc=0 / BUILD SUCCESS**（EconomySimos 编译 217 源文件、SimosApp 332 源文件；总用时 5.75s） |
| 编译产物核对 | `javap -constants`：`TOOL_PER_UNIT = 120l`、`TOOL_MILLI_PER_UNIT_CYCLE = 100l`；`EconomySeeder.trade(...)` 内联值 = `long 120l`；class mtime（19:19:39 / 19:19:43）> 源 mtime（19:19:32）⇒ 非陈旧产物（§三.5） |
| **冲突核查**（★ 任务书"停手上报"条件） | 查遍 `src/test` 与 main：**无任何硬编码 120/100 关系式与既有断言**依赖旧取值（`grep capacityPerUnit()` 全仓测试只 4 处：1 处是农田 `LAND:1000`、1 处断言表**大小**=1、1 处引已删常量而本已编译不过、1 处不可变断言）⇒ **不触发停手**；仅有**注释陈旧**（见"未完成"②） |
| 测试 | **一条没写、一条没跑**（§一.5）；`test-compile` **未跑**（W7-fix 已实测它因 `MerchantOnDemandTradeW7Test:182` 引已删常量而红，属测试批） |
| commit | 无 |
| **会改变数值行为的清单（相对 W7-fix 工作树）** | **1 条**：城市格 `trade@hex` 规模锚 `12,000 ÷ 100 = 120` → `12,000 ÷ 120 = 100`；随之该格一周期运输服务产量 120,000 毫 → **100,000 毫**、周期工具投入 12,000 毫 → **10,000 毫**（禀赋 12,000 毫由"恰好烧完"回到"留 2,000 毫余量"）。**相对已提交的 W7 前基线（`be963bbb`）本清单为**空**：规模 100 与 W7 逐值相同，变的只是"压住规模的那一维资产"（CATTLE → TOOL）—— 本批**不**顺带改标定 |
| 未完成 / 未验证 | ① **运行级读数未跑**（城市格规模真值 = 100、一周期产量 / 工具消耗）：本批禁 `test`，只给算术 + 字节码证据；② `simos-app/src/main/.../EconomySeeder.java` 的**注释**仍留陈旧口径（`:278-287` "锚 `TOOL_PER_UNIT`(100) ⇒ 规模上界 120 单位"、`:4745` / `:4777` "100 毫工具作业资本"）—— 本批硬边界禁改 app，**只报不改**；③ 测试批待办：`MerchantOnDemandTradeW7Test` 迁移（含 `:180-184` 的 CATTLE 断言）＋ 一条**跨模块守卫**（`EconomySeeder.MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI ÷ MerchantTradeTemplate.TOOL_PER_UNIT == 100`），因为分子在 app、锚在 economy，**跨模块不可引用**，只能靠测试钉住 |

## 6. 偏离记录（本批）

| # | 偏离 | 说明 |
|---|---|---|
| D1 | 任务书写 `TOOL_PER_UNIT = MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI / 100`，实现落成 `12_000L / 100L` | `simos-app` 在 economy 的 enforcer 禁列（`simos-economy/pom.xml:89` `<exclude>io.mosire:simos-app</exclude>`）⇒ **跨模块不可引用**。分子以字面量复述，并在类型注里点明"这是与创世禀赋之间**唯一**的耦合点，改任一侧都必须回来改这里"。★ 这是**纯实现困难**（模块边界红线，非设计冲突）⇒ fail-closed 实现 + 记偏离，**不是**停手项；补强手段 = §5 未完成 ③ 的跨模块守卫测试 |

---

# W7-fix3（2 条 SpotBugs + 陈旧注释 + 日志 day 字段）

**责任区**：`simos-economy` 的 2 条 SpotBugs（W7/W7-fix 新引入）+ `simos-app` 侧 3 处陈旧注释 + `MERCHANT_CAPACITY_LANE_TRUNCATED` 缺 `day`。
**允许面**：`simos-economy/src/main/**`、`simos-economy-api/src/main/**`、`simos-app/src/main/**`；未碰 `src/test/**`（例外见 §6 偏离 D1）、其他模块、`docs/**`、`AGENTS.md`。**不 commit**；**不跑** `test`/`verify`/`package`/`clean`；一次一个 Maven。

**开工 → 收工 md5（本批实际改的 4 个 main 文件）**

| 文件 | 开工 md5 | 收工 md5 |
|---|---|---|
| `simos-economy/.../time/MerchantCapacityPool.java` | `18b0a212035d965a1ec65e3eaecc5d20` | `95a48cc9b37902cfb2a501f02d93dacf` |
| `simos-economy/.../time/ModeMigrationPolicy.java` | `8e00368800ce46ca485f6263d56fb220` | `04b4ff52df4e03116f165a8b69cca0e5` |
| `simos-app/.../world/EconomySeeder.java` | `dd9c4f672681e6c71e6e932ebcffce8c` | `a65f82c7f57dc48a5c362d2c0d647323` |
| `simos-economy/.../time/EconomySettlement.java` | `7ca2907b60c6339082844b76ccf0e338` | `d001867ea40271ce9d5a6a398a503018` |

## 1. 四条逐条（file:line + 前后各一行）

### D1 死存储（`MerchantCapacityPool.select`）
- **前**（开工时 `:1039`；SpotBugs 报的就是这一行，性质 `NO_LOADS=true`）：
  `    List<Entry> pool = byHex.getOrDefault(from, List.of());`
- **后**：该行**删除**（收工时同位是 `:1113 List<Entry> candidatePool = globalOrder;`）。
- 同处注释改写（`:1104-1112`）：F1 那段原写"本格的池切片**只用于逐格读数**（日志字段 `hexTotalCapacityMilli`）"——**与代码不符**（逐格读数读的是 `totalCapacityByHex.getOrDefault(from, 0L)`，见 `:1225-1227`），改为"归因判据读全池 / 逐格读数不经过池切片 / 死存储已删（删它不动任何数值）"。
- ★ **未加任何 `@SuppressFBWarnings`**（要求是删死存储本身）。

### D2 装箱往返（`ModeMigrationPolicy.CandidateTally.failure`）
- **前**（开工时 `:2203-2207`，SpotBugs 的 `Long.valueOf(long)` 关联行）：
  `"toolMilli", goods == null ? 0L : goods.getOrDefault(household, Map.of()).getOrDefault(MerchantTradeTemplate.TOOL_COMMODITY, 0L)`
  —— 三目里 `long` 与 `Long` 混用 ⇒ **拆箱**；varargs 立刻**再装箱** ⇒ `BX_UNBOXING_IMMEDIATELY_REBOXED`。
- **后**（收工 `:2188-2194` + `:2209`）：**对象口径取值，全程不拆箱**
  `Long toolMilli = 0L;` → `if (goods != null) { toolMilli = goods.getOrDefault(household, Map.of()).getOrDefault(MerchantTradeTemplate.TOOL_COMMODITY, 0L); }` → `"toolMilli", toolMilli`
- **日志字段类型 / 输出形状：不变**。`LogEvent.fields` 是 `Map<String,Object>`、渲染 `String.valueOf(value)`（`LogEvent:117-126`）；改前两支都装箱成 `Long`（缺账 = `Long.valueOf(0)`，有账 = 账上那个 `Long`），改后传的是**同一个对象** ⇒ 行文本逐字相同。样例：`event=MODE_MIGRATION_PROSPECT_INFEASIBLE origin=economy_migration originKind=... toolMilli=0`（`goods == null`）/ `toolMilli=12500`（有账）。
- **字节码证据**：`javap -p -c 'ModeMigrationPolicy$CandidateTally'` ⇒ `longValue` 调用 **0 处**（只有 5 处 `Long.valueOf` = varargs 打包）。

### D3 陈旧注释（`simos-app/.../world/EconomySeeder.java`，main 允许改）

| 处 | 前 | 后 |
|---|---|---|
| `:277-283`（`TOOL_MILLI_PER_TRADE_UNIT_CYCLE` 的"量级"块） | "规模锚 = `TOOL_PER_UNIT`(**100**) … ⇒ 规模上界 **120 单位** … 12,000 毫工具**恰好**支撑 120 单位规模一个周期 ⇒ 每周期投入 = 120×100 = **12,000 毫**（= 禀赋，**自洽**）" | "锚 = `TOOL_PER_UNIT`(**120**) ⇒ 规模上界 = ⌊12,000 ÷ 120⌋ = **100 单位**；每周期投入 = 100 × 100 = **10,000 毫工具 = 10 商品单位**；禀赋 12,000 毫**留 2,000 毫余量**" + ★★ "**锚 ≠ 周期投入、不要求相等**"（锚 = 存量 / 资本占用；周期投入 = 流量），并写明"改前那条'恰好烧完'的自洽性**已被刻意放弃**" |
| `:4745` | `capacityPerUnit = {TOOL: MerchantTradeTemplate.TOOL_PER_UNIT}  // ★ W7-fix：100 毫工具作业资本 / 1 单位运力` | `… // ★ W7-fix2：**120** 毫工具作业资本 / 1 单位规模（存量；≠ 周期投入 100，不要求相等）` |
| `:4777` | `capacityPerUnit = …TOOL_PER_UNIT}(100)  ← 作业资本那一维（= 一周期工具投入）` | `…}(120)  ← 作业资本那一维（**存量**：每 1 单位规模压住的毫工具；★ ≠ 下一行的周期投入 100，**不是同一个量、不要求相等**）` |

口径来源（当场回代码核）：`MerchantTradeTemplate.TOOL_PER_UNIT = 12_000L / 100L = 120`（存量锚）、`TOOL_MILLI_PER_UNIT_CYCLE = 100`（流量）、`EconomySeeder.MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI = 12,000`。★ 这三处正是 W7-fix2 账本 §5"未完成 ②"点名留给本批的（`只报不改` → 本批改注释）。

### D4 日志补 `day`（`MERCHANT_CAPACITY_LANE_TRUNCATED`）
- **前**（开工时 `:1124` 起）：字段表 19 项 = `from/to/requestedMilli/allocatedMilli/unallocatedMilli/unallocatedNetMilli/unallocatedObservationIndex/requestKeyTracked/workPerGoodPerMille/requestedWorkMilli/allocatedWorkMilli/hexTotalCapacityMilli/outOfRadiusWorkMilli/subUnitResidualWorkMilli/differentMarketKindCarriers/priced/reason/logisticsDetail/businessRejection` —— **无 `day`**。
- **后**：**`day` 打头**（字段名与位置与同族 `MERCHANT_CAPACITY_POOL` / `_POOL_HEX` / `_HOUSEHOLD` 一致）；**级别（DEBUG）与发射条件（`demandLeft > 0` ∧ DEBUG 开）一字未改**，其余 19 个字段名与顺序**逐项未动**。
- **日的来源**（本条唯一的机制新增，且只服务读数）：池新增 `private final long day`（`:350`，**全类只被 `:1211-1213` 的字段表读**，逐处 `grep` 核过），由 tick 面装配点注入 —— 新 8 参重载 `of(..., haulServiceHexes, long day)`（`:518`）；生产路径 `EconomySettlement:2118-2134` 传 `day`（与同轮 `MarketSettlement.MarketRound.day` **同源同一局部量** ⇒ 不可能对不上日）；原 4/5/7 参重载**全部保留**并委托 `NO_WORLD_DAY = -1L`（`:218`、`:235`、`:407/:435/:456`）。
- **缺省中性（D4 纪律：绝不拿假日凑数）**：`day < 0`（夹具 / 纯状态读者装配的池）⇒ 该行**不带 `day` 字段**（字段缺席，而不是 `day=-1`）——与 `EconomySettlement.logEmptiedHouseholdDebtRetained` 的既有守卫同形（同一 `NO_WORLD_DAY` 口径）。
- **前后样例（同一事件）**：
  - 前：`event=MERCHANT_CAPACITY_LANE_TRUNCATED origin=economy_organization originKind=... from=... to=... requestedMilli=...`
  - 后（生产，池带日）：`event=MERCHANT_CAPACITY_LANE_TRUNCATED origin=... originKind=... day=360 from=... to=... requestedMilli=...`
  - 后（夹具，池不带日）：与"前"逐字相同（无 `day`）。

## 2. D1「`select` 归因 / 扣减 / 分配逐值不变」的论证

1. **被删语句是纯读**：`byHex.getOrDefault(from, List.of())` 只做一次不可变 Map 查询（无副作用；`byHex` 由构造器赋值、恒非 null），其值在删除前**一次也没被读**（SpotBugs `DLS_DEAD_LOCAL_STORE` + `DeadLocalStoreProperty.NO_LOADS='true'`；`grep -n '\bpool\b'` 在本方法体内只有这一处）。
2. **方法内不再有任何 `byHex` 读取（字节码级）**：`javap -p -c MerchantCapacityPool` 的 `select` 体里 `byHex` 的 `getfield` = **0**；只剩 `globalOrder`（pc 59 / 86 = 候选集与循环）与 `totalCapacityByHex`（pc 783 → 紧跟**唯一**一处 `Map.getOrDefault` = 日志字段 `hexTotalCapacityMilli`）。`javap -l` 的 `select` 局部变量表里**没有名为 `pool` 的槽**。
3. **三段源码逐行未动**：`select` 内本批只动两处 —— ① 删上面那行（+ 注释）；② 末尾 DEBUG 行的字段表由"直接 varargs"改成"先建 `List<Object> fields` 再 `fields.toArray()`"（`day` 条件插入）。循环体（`servesTrip` / 口径闸 / `maxGoods` / `take` / `consumed` / `item.remainingWorkMilli -=` / `choices.add` / `demandLeft -=`）、`allocated`、`logisticsBlockOf(...)` 的入参与返回值 `new CarrierAllocation(...)` **一字未改**。
4. **新增的 `day` 只进日志**：`select` 里除字段表外没有任何 `day` 引用；`CarrierChoice` / `CarrierAllocation` 的任何数值都不读它。
5. **无抑制、无新分支**：删的就是死代码本身；没有 `@SuppressFBWarnings`，也没有"为了过静态检查"新增的条件分支。

## 3. 命令与 rc（真跑；一次一个 Maven；跑前 `pgrep -af "surefirebooter|classworlds.launcher"` = 无命中）

| 命令 | rc | 备注 |
|---|---|---|
| `bash tools/mvn-lock.sh -q spotless:apply` | **0** | 全仓。**先给 `simos-economy`+`simos-app` 的 759 个 `.java` 做了 md5 快照**，跑完逐个对比 ⇒ 只重写 **2 个文件**：本批的 `MerchantCapacityPool.java`（`:1252-1253` 一行折行）+ **越界的** `EconomyCycleHealthTest.java`（见 §6 偏离 D1） |
| `bash tools/mvn-lock.sh -DskipTests compile -am` | **0** | `BUILD SUCCESS`，16 个模块全 SUCCESS（EconomySimos 2.671s / SimosApp 2.598s；总 8.694s，19:57:42）。app 侧两条 `non-varargs call of varargs method` 警告在 `EconomySeeder:4174/4200`，属**既有**代码（本批对 app 只改注释，不产生 javac 警告） |

## 4. 只读 SpotBugs 自证（★ **非门禁等价**）

- 参考 XML 先备份（`md5 795be4213585458a6cc2cd5f88966191`）→ 跑 → **跑完原样还原**（还原后 md5 **逐字节相同**，已核；参考文件里那 2 条 bug 仍在，未被覆盖）。
- `bash tools/mvn-lock.sh -Dspotbugs.failOnError=false spotbugs:check -pl simos-economy` ⇒ **rc=0**、`BugInstance size is 0`、`No errors/warnings found`（19:57:54；新 XML 里 `DLS_DEAD_LOCAL_STORE` / `BX_UNBOXING_IMMEDIATELY_REBOXED` 各 **0** 次；分析对象 = `simos-economy/target/classes`，即本批新编的类）。
- **证据强度边界（三条，如实记）**：① `spotbugs:check` **不跑生命周期、不编译**（本次能分析到新类，是因为**先**跑了 `compile -am`；在没编过的树里它 rc=0 也不提示）；② aux classpath 走 `~/.m2` 的**旧 SNAPSHOT**（跨模块类型可见性与真门禁可能不同）；③ `spotbugs:check` 与 `verify` 的类集 / 插件配置不同（§三"分析器的判定不是被分析文件的纯函数"）⇒ **只能当旁证**。为补强，另给了**与门禁无关的字节码证据**（§1 D1/D2）与源码逐行对照（§2）。

## 5. 会改变数值行为的清单

**空。** 逐项自查（本批没改任何数值字面量、公式、门槛、分配序、价格、日志级别 / 发射条件）：

| 面 | 结论 |
|---|---|
| `select` 的归因 / 扣减 / 分配 / 返回值 | 未动（§2 ③，源码 + 字节码两级对照） |
| `MERCHANT_CAPACITY_LANE_TRUNCATED` | 新增一个**只读**字段 `day`；级别与发射条件未动；其余 19 字段逐项未动 |
| `MerchantCapacityPool.of(...)` | 原 4/5/7 参重载**签名不变、行为不变**（新参 = **新增** 8 参重载）；池的装配算式（`sharePerMille` / `askPerMille` / 两个比较器）未动 |
| `EconomySettlement` 装配点 | 只把已有的局部量 `day` 多传一个参数给 `of(...)` |
| `EconomySeeder` | **只改注释三处**（本批对 app 的 hunk 全部落在 `*` 注释行内） |
| `ModeMigrationPolicy` | 只改 `toolMilli` 的取值形态（`long` 往返 → 对象口径）；字段名 / 值 / 级别 / 发射条件不变 |

## 6. 未完成 / 未验证 / 偏离

**未完成 / 未验证（如实记）**

1. **运行级证据没跑**（硬性禁 `test`/`verify`/`package`/`clean`）⇒ ① "真世界 DEBUG 档下该行确实多出 `day=NNN`"只有源码 + 编译证据，**没有日志样本**；② D1 的"逐值不变"没有运行级逐值对照（只有源码 / 字节码论证）。
2. **SpotBugs 自证非门禁等价**（§4 三条边界）；门禁等价结论要等控制方跑 `clean verify`。
3. `select` 的 3/4 参重载（夹具 / 纯状态读者入口）若被用在**带日的生产池**上，仍会打 `day`（本批的有意口径：日随**池**的身份、不随调用入口）；若要"4 参路径一律不带日"，那是另一条设计口径，需另裁。

**偏离（1 条）**

| # | 偏离 | 说明 |
|---|---|---|
| D1 | `spotless:apply`（**允许**的仓库级命令）顺带重排了 `simos-app/src/test/java/io/mosire/simos/app/time/EconomyCycleHealthTest.java`（**另一批 owner** 的文件；硬边界禁碰 `src/test/**`） | **只改格式**（spotless = google-java-format 美化器，不改 AST / 语义 / 任何字面量）。md5：越界前 `52e09cf3cd87e96eac976c756db1a85e` → 越界后 `44011f7b1ed6d478f801bfda60397d98`。★ **未还原**：原文未留副本（git 里只有 HEAD 版本，该批改动未提交）。尝试两条重建路线并**均不采信**：① `git fsck` 悬空 blob 无命中；② 从 DSH 会话 transcript 逐帧解 zstd + 逐条 `edit` 重放（`1b19b61a…` 等 7 个会话，19:48 是最后一次编辑该文件的会话）⇒ 多数 `old_string` 在当前状态 **0 命中**（帧 / 链不完整），且 `read` 结果只覆盖局部行 ⇒ 重建**无法用 md5 自证**，按"**宁可留一处格式差，不可手写重建引入错误字节**"处理。**上报控制方定夺**（取舍：让该文件 owner 自行重跑 `spotless:apply` / 重写；或控制方接受这处纯格式差）。★ 已核：该文件的内容未被本批改动，也未参与本批除 `spotless:apply` 外的任何命令。 |
