# G 只读调查 2：市场竞争识别与两个数字的对账（第 3、5 题）

> **性质**：只读调查。未改 `src/**`、`docs/**`、既有台账；未 `git add/commit`；**本文件是本次唯一仓内写盘产物**（临时物全在 `/tmp`）。
> **代码态**：`ts/m1` @ `a7fbfe46`。`git log` 显示 `c8520f34..a7fbfe46` 只有 docs 提交 ⇒ **生产代码与 `5b6b2be7`/`c8520f34` 同源**；下文 `文件:行` 按当前工作树。
> **基线**：`docs/superpowers/reports/2026-09-28-economy-investigation.md`（§1.4 写"成交 2,114 笔"、§3 写"该日 5,483 笔"）；草稿 `.superpowers/sdd/2026-09-28-investigation/{A-timing-counts,B-determinism-bidask,C-silver-cloth}.md`。
> **标注约定**：`[代码事实]` = 回代码逐行核对过；`[探针实测]` = 本轮真跑过并留下输出；`[推断]` = 从代码结构推出、**没有构造变异体实测**；`[用户目标]` = 调查单里的假设性说法，代码里没有这个术语/定义。
> **纪律**：每条给 结论 / 关键代码位置 / 实际读写路径 / 探针证据 / 未验证。短探针只 `javac` 对既有 `simos-*/target/classes`（未跑 Maven、未跑整年）；所有临时物在 `/tmp`。

---

## 0. 结论速览

### 第 3 题（市场能否识别"竞争失利"）

1. **会竞争同一个买方，但不是价格/成本竞争**：同商品卖单之间共享买方的**订单量预算**；区内所有卖单被池在一起按"剩余可卖量"比例分派买方的匹配量，跨区只对**直接邻接区**按 `(q,r)` 顺序逐路线尝试，运力按窗口限制。谁先进入 `ctx.sells` 只决定**配对身份、最大余数法的 ±1 残差、跨区路线访问顺序**，不决定"出价高低"。
2. **代码里没有任何"按价格/成本排序"这一步**：`MarketSettlement` 里的排序只有格坐标 `(q,r)`、承运人 `ActorRef.id`、商品 id 字典序（`MarketSettlement.java:454/949/972/1932/1943`）；并列断法是 `ProportionalSplit` 的"余数降序、同余数下标升序"（`ProportionalSplit.java:69-99`）。
3. **成交价与运费不参与"优劣选择"**：区内成交价 = 集散节点参考价；跨区成交价 = 该路线卖方格参考价的**最大值**（`unitPriceOf:1415-1423`）；`bid/ask` 只是限价过滤。运费（`freightPerUnitOf:1425-1427`）只进入"买方买得起多少"与总价复核（`:1443-1497`），**不进入卖方选择/排序**；`maxLandedPrice` 的跨区比较只比 `unitPrice`、**不含运费**（`:1079`）。实际运输投入 `costPerUnit = 距离×moveCost`（`:1027`）只进路线读数（`MarketReport.RouteUsage`），不参与任何比较。
4. **四件事的区分**：
   - **没有"每卖方生产成本"这一维**：`SellOrder` / `BuyOrder` / `Industry` / `MarketReport.Fill` 都没有货币成本字段；匹配代码里 `cost/costPerUnit` 只有"运输实际投入"；`src/main` 无 `productionCost` 之类的名字。`EconomySeeder.java:422` 的注释自己写着"**固定价不看成本**是本批的已知简化"。[代码事实]
   - **买方没有货币**：门在 `ordersFor:723-733`（预算 ≤0 不下单）、`commitFreezes:792-798`、`affordableQuantity:1443-1455`（含 2 毫边距 `:159`）、`payableMoneyOf:1463-1467`、`pairUp:1162-1173`、`collectUnfilled:1563-1565`；`spendableMoneyOf:1889-1893` = `max(0, money - frozenMoney)`。[代码事实]
   - **买方已有库存**：`gap = max(0, target - available - incoming)`（`:719`），`gap ≤ 0` 直接 `continue`（`:720-722`）；家户 `target` = 35 天生活保留（`:104-106` + `householdLifeReserveOf:1727-1738`），经营者 `target` = 必要投入（`:717` + `necessaryInputsOf:1699-1725`）。[代码事实]
   - **名单序对"谁和谁配对"有影响，但对区内"卖多少"不是先到先得**：区内卖单份额按 `remaining` 比例切（`matchGroup:918-929`），名单序主要决定配对身份/并列残差；跨区的 `buyerHex`→`sellerHex` 双重 `(q,r)` 顺序 + 每路线最多 4 个运力窗口（`:949/972/1037`）会让靠前的卖方格先吃到同一个买方的剩余需求与预算——这是"顺序性优势"的真实位置。[代码事实；`[推断]`：换序对总量的影响未构造变异体实测，见 B 草稿 §6]

### 第 5 题（两个数字的对账 + 百分比分母）

1. **2,114 vs 5,483 已核实，不是不同日/不同 `MarketTrigger`**：两者都是 **day 365、`PERIODIC`**。差别是装置：
   - **A**（`AProbe`）：在 tick360 快照上**直接干跑**一轮 `clearOncePerCycle`，**不经 361–364** ⇒ 2,114 笔（grain 2,057 / fiber 46 / cloth 11）。
   - **B**（`FillProbe`）：从同一份 tick360 快照先 `step(361)…step(364)`（含 day361 周期首日扣投入/劳动重排、day363 `LOW_GRAIN_STOCK` 追加轮 4,706 笔），再 `step(365)` 取 day365 例行轮 ⇒ 5,483 笔（grain 2,816 / cloth 2,168 / fiber 499）。
   - ⇒ 报告两处应分别写成"tick360 快照上**直接干跑** day365"与"从 tick360 快照**真步进到 day365 后**的例行轮"；**不是**同一状态上的两个口径，也不是年度累计。
2. **前五项百分比相加 >100% 的根因是混了三个分母族**：
   - 总墙钟：`201.9 s`（JFR 装置下的 360→390 advance）/ `≈175 s`（同次无 JFR 的推导总墙钟，不是独立实测整段）；
   - 嵌套计时：`169.722 s` = `step 140.229 + OwnershipBooks.apply 29.147 + land* 0.346`；
   - JFR 采样：`16,275` 个 `ExecutionSample`，其中 `15,776`（`96.93%`）在日循环内。
   `§1.2` 的第 1/2/4/5 项的百分比都是 `÷169.722`（其中第 4 项 Replay 其实在 day-loop 之外，属分子分母口径错配）；第 3 项的"10.1%"是 `1,644/16,275`（采样分母），"≈9–11%"又是 `15–20 s ÷ 169.722`（墙钟分母）。把这几项直接相加，等于把"step 内部的子项"（第 3 项）叠加在"step 的两个切片"（第 1、5 项）上，还把"day-loop 外的 Replay"塞进 day-loop 分母。**可加的只有同一分母下的互斥划分**（见 §5.2）。

---

## 第 3 题：市场能否识别"竞争失利"

### 3.1 同一商品的卖单是否竞争同一个买方；撮合主流程

**结论**
- **同一个市场区（region）内**：是的。`clearOncePerCycle` 先把全部格的全部参与者、全部有价商品生成订单（`:451-489`），然后 `matchWithinRegions` 按 `(region, commodity)` 把**该区所有买/卖单池在一起**（不管卖方是家户、weave 经营者还是 craft 作坊，也不管来自区内哪个 hex），`matchGroup` 先算 `matched = min(总可负担需求, 总可卖剩余)`，再把 `matched` 按各卖单剩余量比例切给卖方、按各买单 `min(remaining, affordable)` 比例切给买方，最后 `pairUp` 按名单序逐笔配对（`:865-931`、`:1130-1182`）。
- **跨区**：只有**直接邻接区**（`MarketTopology.adjacent:199-207`）且只处理"区内撮合后仍有剩余"的买方；`matchAcrossRegions` 对每个商品按 `buyerHex` `(q,r)` 升序、再按 `sellerHex` `(q,r)` 升序逐路线尝试（`:949/972/990`）。同一个买方的 `remaining` 与预算会被多条路线依次消耗，因此**不同邻接区的卖方确实在竞争同一个买方的剩余需求**；但先来后到由路线顺序 + 每路线 4 个运力窗口决定，而不是价格排序。
- **区内优先**：`clearOncePerCycle:495-497` 先 `matchWithinRegions` 再 `matchAcrossRegions` ⇒ 同区卖单先消耗买方的需求；只有区内买不完的剩余才会给外区卖方。跨区单价 = 卖方格参考价最大值，买方限价不足会被整条路线挡住（`:1079`）。

**关键代码位置**
- 入口与建单：`MarketSettlement.java:435-489`（`clearOncePerCycle`；订单来源唯一 = `ordersFor`）。
- 区内：`MarketSettlement.java:865-903`（`matchWithinRegions`）、`:906-931`（`matchGroup`）。
- 跨区：`MarketSettlement.java:935-999`（`matchAcrossRegions`）、`:1006-1120`（`matchRoute`）。
- 配对：`MarketSettlement.java:1130-1182`（`pairUp`）；一笔成交：`:1186-1353`（`executeTrade`）。
- 区域拓扑：`MarketTopology.java:177-207`（`regions/adjacent`）、`:220-222`（`travelTicks`）。

**实际读写路径**
- 读：`EconomyData` 的 `markets/classes/industries/relations/allocations/shipments`（`EconomySettlement.java:853-874` 组装 `MarketRound`）+ `ActorData.accounts` 经 `OwnershipBooks.load*` 载入的会话副本（`AProbe.java:130-137` 同款路径）。
- 写：只写**轮内会话副本**（`householdGoods/Money/Frozen*`、`operator*`、`shipments` 副本、ledger），经唯一 applier `EconomySettlement.applyTransfer`（`:1213/1245/1266`）；`MarketOutcome.markets` 交回 `EconomySettlement`（默认固定价下逐值不变）；`MarketReport` 只进 `ledger.recordMarketReport` / app 的进程内 `MarketReportFeed`，**不落盘**（`EconomySettlement.java:875-882`、`MarketReportFeed.java:10-33`）。
- 不写：`SellOrder/BuyOrder`、`Fill/Unfilled` 都不进 `EconomyData`/`ActorData`/SQLite。

**探针证据**
- `[探针实测]` `/tmp/g-gprobe.txt`（本轮，`GProbe` 在 tick360 状态上干跑 day365）：**一个买方** `HOUSEHOLD:-39_-71:rural:landlord` 的 192 毫匹布买入量（C 草稿探针记该行下单价 = 192），被 **11 笔卖单**（7 笔家户 + 3 笔 weave 经营者 + 1 笔 craft 作坊）在 3 个不同卖方格（`-40_-71`、`-40_-70`、`-39_-71`）全部即时成交（`immediate=true`，单价全 5）——这就是"不同经营者/不同格卖单共享同一买方订单量"的直接样例。
- `[探针实测]` `/tmp/g-fillprobe.txt` day365（B 路径）：5,483 笔全部 `immediate=true`、`cross=0`；说明该窗口实际成交全走区内池；跨区代码路径有规划（A 干跑 `routeRows=112,698`）但没有成交。
- `[探针实测]` A 干跑（`/tmp/g-aprobe.json`）：`buyOrders 1,822 / sellOrders 10,002`、`fills 2,114`、`crossRegionFills 0`。

**未验证**
- 跨区路线顺序对"谁吃到买方剩余"的量化影响未构造变异体（没有打乱 `buyerHex/sellerHex` 顺序重跑对比）；本窗口 `crossRegionFills=0`，无法从成交样本观察。
- 未测 `MatchContext` 之外的第二条合并/排序路径（代码里没有第二条，但只做了静态核对）。

---

### 3.2 候选卖方/买方按什么排序与分配；并列断法

**结论（排序维度表）**

| 维度 | 排序？ | 代码位置 | 备注 |
|---|---|---|---|
| 成交价/限价 | **不排序** | 无 | `bid/ask` 只做 `>=`/`<=` 过滤（`:882/892/1079`） |
| 生产成本 | **不排序、不存在** | 无字段 | 见 §3.4(a) |
| 格顺序 | 是，`(q,r)` 数值升序 | `MarketSettlement.java:454`（建单）、`:949/972`（跨区买卖格） | 跨区只保留直接邻接区（`:975-977`） |
| 家户行顺序 | 是，`(stratum.value 字典序, residence)` | `EconomySettlement.rowsByHex:3982-3990` | `rowsByHex` 的**格间**键是字符串自然序；市场只取当前格的 list，故格间序不影响单格参与者列表 |
| 经营者顺序 | 是，`industryId` 字典序 | `IndustryHexKeys.at:69-76` | 与 `industries` 表插入序无关 |
| 参与者先后 | 家户在前、经营者追加在后 | `MarketSettlement.participantsFor:1654-1679` | 同 actor 去重 |
| 商品顺序 | 是，`CommodityId.value` 字典序 | `MarketSettlement.orderedCommodities:1936-1945` | |
| 承运人 | 是，`ActorRef.id` 字典序取第一个 | `MarketSettlement.carrierOf:1925-1934` | 本批无 ORGANIZATION 承运人 |
| 区内配额 | `matched = min(需求, 供给)` 按卖方 `remaining` / 买方 `min(remaining, affordable)` 比例切，最大余数法 | `matchGroup:908-930` + `ProportionalSplit.byDenominator:37-61` | 并列断法 `:69-99` |
| 逐笔配对 | 买方表序 × 卖方表序，`sellerIndex` 单向 | `pairUp:1130-1182` | 决定"谁配谁"，不改变各自份额 |
| 跨区运力 | 每路线最多 `MARKET_MAX_TRANSPORT_ROUNDS=4` 个窗口 | `MarketSettlement.java:132/1037-1119` | 每窗口 `capacityPerWindow = max(1, 100M×8/distance)`（`:139/142/1021-1026`） |
| 多张买单共享同一 owner×currency 预算 | 按 `requestedMoney` 比例切 | `commitFreezes:779-816` | 同款 `ProportionalSplit` |

**并列断法（精确）**
- `ProportionalSplit.distributeByLargestRemainder`：先 `remainder/n` 人人摊，再按**余数降序、同余数下标升序**给前 `remainder%n` 名各 +1（`ProportionalSplit.java:69-99`；插入排序在 `:86-95`）。
- ⇒ 同价、同权重、同余数的并列，**按传入数组下标（即上面的名单序）断**，不是按 actor id、不是 HashMap 序。
- 区内 `matchGroup` 传入的 `buys/sells` 顺序就是全局订单表过滤后的顺序（先格 `(q,r)`，再家户行序/经营者 id 序）。

**实际读写路径**
- 排序输入来自只读状态与会话副本；`PlannedOrders`/`MatchContext` 都是**瞬时**（`MarketSettlement.java:285-293/2054-2086`），没有落盘；`MarketReport.Fill` 的先后 = `ctx.fills` 追加顺序（`executeTrade:1289+`）。

**探针证据**
- `[探针实测]` `/tmp/probe30-out.json` 的 `dayTimings` 12 个开市日（363,365,368,370,373,375,378,380,383,385,388,390）与 `MarketTrigger` 判据逐日一致；单轮 7,612–12,503 ms。
- `[探针实测]` A 干跑的 `msPlanOrders` 约 1.7 s（原 30 天探针 `/tmp/probe30-out.json` = 1,708 ms；本轮 5 天重跑 `/tmp/g-aprobe.json` = 1,722 ms）；`marketRoundDryRun.sellsByCommodity` 的条数与清单（cloth 4,724 / fiber 799 / grain 3,475 / iron 803 / tool 201）就是该顺序的产物。
- `[代码事实]` 全文件 `grep sort(/Comparator` 只有 §3.2 表中那几个（`MarketSettlement.java:454/949/972/1932/1943`）。

**未验证**
- 未构造"打乱 `rows`/`industries`/`allocations` 插入序"的变异体；B 草稿 §6 已把这条列为未做。

---

### 3.3 成交价与运输成本是否参与优劣选择

**结论**
- **成交价不参与"选谁"**：区内成交价固定 = 集散节点参考价（`:871-876`、`matchGroup` 传入 `price`），跨区 = 卖方格参考价最大值（`unitPriceOf:1415-1423`）。价格只在两处做**过滤**：区内 `maxLandedPrice >= price` 与 `minPrice <= price`（`:882/892`）；跨区 `maxLandedPrice < unitPrice` 挡买方（`:1079`）。没有"更便宜/限价更低的卖方优先"的比较。
- **`freightPerUnitOf` 不参与"选谁"**：它只用于 `affordableQuantity` 的 `unitCost = unitPrice + route.freightPerUnit`（`:1450`）、`totalCostAtMost` 的精确总价复核（`:1488-1496`），即影响**买方在这条路线上能买多少**。路线顺序仍由 `(q,r)` 决定，不按运费/距离排序。
- **`maxLandedPrice` 过滤不含运费**：跨区比较只比 `unitPrice`（`:1079`），字段名叫 landed 但语义是"货款价上限"（`ordersFor:735-739` 明说运费另计）。⇒ 运费不会让一条"货款价合格"的路线被限价挡掉；它只会缩小可买量。
- **`costPerUnit = distance × moveCost`（`:1027`）是运输实际投入，不是经济选择变量**：它只进 `TradeRoute` / `RouteAccumulator` / `MarketReport.RouteUsage`（`:1027/1035/1063/2004-2010`、`MarketReport.java:203-208`），撮合里没有任何消费它的比较。

**关键代码位置**
- 区内价格过滤：`MarketSettlement.java:882/892`；`matchGroup` 传入价：`:900`。
- 跨区：`unitPriceOf:1415-1423`（在 `:1054` 调用）、`freightPerUnitOf:1425-1427`（在 `:1055` 调用）、`freightOf:1430-1433`、限价过滤 `:1079`、可买量 `:1086`、总价复核 `:1473-1497`。
- 付款/运费：`executeTrade:1196-1199`、`:1281`。
- 路线运力：`:1021-1026`（距离越远运力越小），轮次 `:1037`。

**实际读写路径**
- 成交价/数量通过 `executeTrade` 写轮内会话副本（唯一 applier），并追加 `MarketReport.Fill`；`RouteUsage` 只进报告；`TradeRoute` 只进报告/在途批次的元数据，都不参与下一次选择。

**探针证据**
- `[探针实测]` B day365 全 5,483 笔逐笔核验（`/tmp/g-fillprobe.txt`）：`unitPrice == 卖方格 ref == 买方格 ref`、`seller.bid ≤ unitPrice ≤ buyer.ask` 5,483/5,483；`freight=0`、`immediate=true` 5,483/5,483。**注意这是 B 的步进路径，不是 tick360 状态的干跑。**
- `[探针实测]` A 干跑 11 笔布成交（`/tmp/g-gprobe.txt`）全部 `unitPrice=5`、`immediate=true`；该 dry-run `crossRegionFills=0`、`freightPaid/Uncollected=0`，因此跨区价格/运费路径只有代码事实，没有本轮成交样本。
- `[代码事实]` `RouteUsage.costPerUnit` 只在 `clearOncePerCycle` 的 513 行被写入报告，无反向读入。

**未验证**
- 本窗口没有跨区/带运费的成交 Fill（A 干跑与 B 步进都 `cross=0`）；跨区 `unitPriceOf`/运费的行为是回代码核对的，不是实测成交。
- 未测 `MARKET_CROSS_REGION_SETTLEMENT_IMMEDIATE=true` 下"付款日 ≠ 到货日"的账务路径。

---

### 3.4 四件事的证据区分

#### (a) 生产成本较高而失去订单

**结论**：**代码里没有"每卖方成本"这一维**，因此无法用现有数据把"没卖出去"归因成"成本较高"。匹配的选择规则里，卖方侧只有三个量：`sellable`（剩余可卖量）、`minPrice`（由**卖方格市场参考价**派生的 bid）、以及跨区路线用的**卖方格参考价**本身（`unitPriceOf:1415-1423` 取这对路线上的最大值）；三者都来自同一张市场价表，与卖方的生产成本没有代码联系。区内过滤另看 `availableFromTick`（订单生成时恒 = `round.day`；跨区路径不查它）。没有成本、利润、报价差。

**关键代码位置**
- 卖单字段：`simos-economy-api/.../SellOrder.java:35-42`（supplier/dispatchFrom/commodity/sellable/minPrice/availableFromTick/receiveWith）；买单字段 `BuyOrder.java:38-46`；`Industry` 记录 `Industry.java:108-125`（capacity/inputPerUnit/laborPerUnit/outputPerUnit/…，**无货币成本**）。
- `ordersFor:679-748`：`reference = market.priceOf(commodity)`；`bid/ask` 由 `Market.bidPriceOf/askPriceOf` 现算（`Market.java:87-111`）；卖单写 `minPrice = bid`（`:703-714`）。
- 匹配无成本比较：`matchWithinRegions:865-903`、`matchGroup:906-931`、`matchAcrossRegions:935-999`、`matchRoute:1006-1120`、`pairUp:1130-1182`；全文件排序只有 §3.2 表那几处。
- `costPerUnit` 搜索：`grep -rn "cost\|costPerUnit" simos-economy/src/main` 只命中运输（`MarketSettlement:1027/1035/1063/2004-2010`、`MarketReport:208`）与注释；`productionCost` 0 命中。
- 注释自述：`simos-app/.../EconomySeeder.java:422` "两条技术路线在同一挂牌价下的毛利率不同：**固定价不看成本**是本批的已知简化"；`:439` "铁**没有生产成本可推**"。

**实际读写路径**：读物理投入/产出与市场参考价；写 `SellOrder.minPrice`；没有成本量进入任何状态/报告字段。生产侧投入在 `drawCycleInputs` 按实物扣账，产出进库存，**只通过"有多少货可卖/要多少投入"间接影响市场**。

**探针证据**：`[探针实测]` `/tmp/g-gprobe.txt` A 干跑 11 笔布成交里，三种生产者都出现——`weave@` 经营者 3 笔、`craft` 作坊 1 笔、家户 7 笔——全部成交，单价全是 5；因此至少可以说"作坊/经营者并没有因为生产方式被代码挡在成交之外"。[代码事实] 三条路线用同一参考价 5、同一 bid/ask 4/6（C 草稿 §2.1 实测 799 格布价全 5）。

**未验证**：没有构造"同一商品、两个成本不同的卖方、同样 sellable"的变异世界；也不存在这种成本字段可构造。没有货币化生产成本数据。

#### (b) 买方没有货币（预算/可花余额的门）

**结论**：有明确的门，且分四层：① 下单时预算 ≤0 不生成买订单；② 生成时按 `budget×1000/参考价` 折量，≤0 不下单；③ 冻结时按 owner×currency 的总可花封顶，可花 0 则整组标 `NO_BUDGET`；④ 逐笔成交前用会话余额 + 本单剩余冻结逐笔复核，归零则把该买方标 `noMoney`。

**关键代码位置**
- `ordersFor:723-733`：`budget = spendableMoneyOf(...)`；`budget <= 0 ⇒ continue`；`affordable = budget*MILLI_PER_GRAIN/reference`；`quantity = min(gap, affordable)`；`quantity <= 0 ⇒ continue`。
- `spendableMoneyOf:1889-1893` = `max(0, moneyOf - frozenMoneyOf)`。
- `commitFreezes:779-816`：同 owner×currency 的买单按 `requestedMoney` 比例切 `spendable`；`total <= 0` ⇒ 全部 `NO_BUDGET`（`:794-798`）。
- `affordableQuantity:1443-1455`：`money <= MARKET_MONEY_ROUNDING_MARGIN_MILLI(2)` ⇒ 0；否则 `money -= 2`，`unitCost = unitPrice + (route? freightPerUnit : 0)`，`safeMulDiv` 折量。
- `payableMoneyOf:1463-1467`、`pairUp:1155-1173`（`payable <= 0 ⇒ noMoney=true, blocked=NO_BUDGET`）。
- `collectUnfilled:1561-1565`：买方剩余原因 `NO_BUDGET` 的兜底判据是 `requestedMoney <=0` 或 `spendable + frozen <=0`。

**实际读写路径**：读 actor 会话副本的 `money`/`frozenMoney`；写轮内冻结表与余额，成交后 `buy.spentMilli += payment+freight`（`:1281`）；最终由 app `OwnershipBooks.apply/land*` 写回 `ActorData`。

**探针证据**
- `[探针实测]` C 草稿 §2.2/§2.3（`/tmp/invC/probe_detail360.tsv`）：@360 布有缺口的 265 个城镇行合计只有 **902 毫银**（77 行 2、184 行 4、4 行 3）；读时订单量 178,213，但 `affordableQuantity` 先扣 2 毫边距后最多只能成交 74,145。
- `[探针实测]` C 草稿 §2.1 + 我按 `m2t360.json` 201 区去重复算：布 `sell no_budget 4,721 笔 / 30,866,646,662`，买方 `algorithm_uncovered 267 笔 / 464,160`——**买方侧没有一条 `no_budget`**；`[推断]` 这正是"预算门在下单/折量处已经生效，剩下的买单因 2 毫边距落进 `algorithm_uncovered` 兜底"的形态（C 草稿 §2.3 的同一解释）。
- `[探针实测]` `/tmp/g-gprobe.txt` A 干跑：`buy:cloth:ALGORITHM_UNCOVERED=265 / 178,021`、`sell:cloth:NO_BUDGET=4,724 / 30,864,321,171`（sell 残量 = 30,864,321,363 − 192 成交，逐毫对得上）。

**未验证**：未构造"给某买方加钱"的变异体；C 草稿 §2.3 的"最多 74,145"是按撮合算式复算的，不是实跑注入。

#### (c) 买方已有库存（35 天 target − available 的 gap 门）

**结论**：有。买方的订单量不是"想要多少"，而是 `gap = max(0, target − available − incoming)`；`gap ≤ 0` ⇒ 连订单都不生成。家户 `target` = 人口 × 35 天生活保留（粮/布两条 `selfNeedOf`），经营者 `target` = 必要生产投入。

**关键代码位置**
- 门：`ordersFor:699-722`：`available = max(0, stock - frozen)`（`:701`）；`target = household != null ? life : necessary`（`:717`）；`incoming = confirmedIncoming(..., deadline)`（`:718`，`deadline = round.day + 35`，`:691`）；`gap = max(0, target - available - incoming)`（`:719`）；`if (gap <= 0) continue`（`:720-722`）。
- 35 天来源：`MarketSettlement.java:96-106`（`MARKET_RESTOCK_INTERVAL_DAYS=5` + `MARKET_SAFETY_STOCK_DAYS=30` = `MARKET_LIFE_RESERVE_DAYS=35`）；`householdLifeReserveOf:1727-1738` 对 grain/cloth 各算 `selfNeedOf(row.population(), …, 35)`；`selfNeedOf:1792-1800` 走 `EconomyVocabulary.cumulativeRationMilli/cumulativeClothMilli`。
- 经营者：`necessaryInputsOf:1699-1725`（`inputPerUnit × capacityScaleOf` 的和）。

**实际读写路径**：读家户/经营者库存会话副本、`ClassRow.population`、`Industry.inputPerUnit/capacity`、在途 `ShipmentBatch.allocations`；写买订单 quantity；买家在途到货在每日 0b 步改变库存（`EconomySettlement:719-720`）。

**探针证据**
- `[探针实测]` C 草稿 §2.2（`PlanOrdersProbe` 逐行喂 `MarketSettlement.planOrders`）：@360 农村 3,196 行中 **3,195 行 `gap=0`**，唯一 gap 行 = 首都农村地主（gap=192，有钱 112,147，真下单 192）；城镇 265 行 `gap>0`、订单合计 178,021；合计订单量 **178,213** 与 dump 读时 `effectiveDemandMilli` 201 区去重和逐值相等。
- `[探针实测]` 我独立解析 `/tmp/invC/probe_detail360.tsv`：266 行（1 rural + 265 urban），qty 合计 178,213，gap 合计 10,656,694（rural 192 + urban 10,656,502），urban money 合计 902。
- `[代码事实]` 布库存中位数够 575 天（C 草稿 §2.2 逐行统计）——这是"gap 门"关着的直接体现。

**未验证**：probe_detail360.tsv 是 C 的调查产物，本轮只重算了它；没有重跑 `PlanOrdersProbe`（C 草稿已记它与运行 jar 的 `MarketSettlement.class` md5 相同）。

#### (d) 卖方因固定名单顺序吃亏（稀缺供给下先到先得）

**结论（要拆开说）**
- **区内**：卖方**总量份额不是先到先得**，而是按各卖单 `remaining` 比例切（`matchGroup:918-929`）：供给稀缺时（`matched=supply`）每个卖方按比例卖，谁在名单前后都卖光；需求稀缺时每个卖方按剩余比例被配给。名单序影响的是**配对身份**（`pairUp` 的 `sellerIndex` 单向推进）与**最大余数法的 ±1 残差**（`ProportionalSplit:69-99`）。
- **跨区**：同一个买方的剩余需求/预算在 `buyerHex` `(q,r)` 序 × `sellerHex` `(q,r)` 序的路线循环里被依次消耗；`buy.remaining` 与 `buy.noMoney` 在路线间传递（`:992` 的 `removeIf`），先被访问的卖方格有结构性的"先到"优势。每路线内仍按比例切，且被每窗口运力 `capacityPerWindow` 限制、最多 4 轮（`:1098-1119`）。
- ⇒ "稀缺供给下先到先得"这个说法**只在跨区路线访问顺序这个意义上成立**；区内总量不是先到先得。要判定"某卖方是否因名单序吃亏"，现有读数连"逐卖方被配给多少"都没有稳定出口。

**关键代码位置**
- 名单序构造：`clearOncePerCycle:451-489`（格 `(q,r)` 升序 `:454`）→ `participantsFor:1654-1679`（家户在前、经营者后）→ `EconomySettlement.rowsByHex:3972-3993`（行 `stratum.value, residence`）→ `IndustryHexKeys.at:69-76`（产业 id 字典序）。
- 区内比例切：`matchGroup:908-930`；配对顺序：`pairUp:1138-1153`。
- 并列断法：`ProportionalSplit.java:69-99`。
- 跨区顺序：`matchAcrossRegions:940-997`（`buyerHexes.sort:949`、`sellerHexes.sort:972`、邻接过滤 `:975-977`）；运力轮次 `matchRoute:1037-1119`。

**实际读写路径**：名单序只是内存 list 的构造顺序；`ctx.buys/sells` 不落盘；`MarketReport.Fill` 的追加顺序反映配对顺序，但报告瞬态。

**探针证据**
- `[探针实测]` `/tmp/g-gprobe.txt` A 干跑：11 笔布成交分布在 3 个卖方格、3 种生产者上，说明区内是"按剩余量从多个卖方拼单"，不是"先到的卖方独吞"；但 A 干跑 `cross=0`，无法观察跨区顺序效应。
- `[代码事实]` `pairUp` 的 `sellerIndex` 只前进不回头（`:1138-1153`），每笔成交后 `buy.remaining/sell.remaining` 递减；`buyParts/sellParts` 是配给上限，真正逐笔量还受 `affordableQuantity`/`exactAffordableUpTo` 复核（`:1158-1163`）。
- `[推断]` 换名单序会改配对身份与并列残差；跨区会改"谁先拿到买方剩余"。**没有实测**：B 草稿 §6 明说没有打乱 `industries/rows/allocations` 插入序重跑；本轮也没做。

**未验证**：未构造"同一买方、两个邻接区卖方、供不应求"的变异世界；未量化顺序对逐卖方成交量的影响。

---

### 3.5 布市场 @tick360：`sell no_budget` 能否证明"生产方式竞争失败"？

**结论：不能。** 有三层原因，且第三层是本轮新增的正面反例。

**(1) `sell no_budget` 这个词本身不是"逐卖方因果"** `[代码事实]`
- `collectUnfilled:1581-1597`：只要**全世界**（不限同区/邻区、不看限价、不看预算）还存在**一笔同商品未成交买单**（`anyBuy`，`:1585-1590`），所有剩余卖单都盖 `NO_BUDGET`；否则盖 `NO_BUYER`。它既没有定位买方，也没有判"这些卖方是否真的与那些买方在同一区/同一价/同一时限"，更没有任何"成本/生产方式"维度。
- 对比买方侧：`collectUnfilled:1554-1580` 的买方原因链才逐一区分 `NO_BUDGET / NO_SELLER / ALL_RESERVED / NO_ADJACENT_SUPPLY / PRICE_LIMIT / ALGORITHM_UNCOVERED`。

**(2) @tick360 布市场的数字形态：卖方残量巨大，但买方残量全部是 `algorithm_uncovered`** `[探针实测]`
- 先分清两个窗口：
  - **day 360 实轮窗口**：`m2t360.json` 携带的 `marketReadout.match` 是进程内最后一轮市场报告（`marketReadout.tick=360, lastSettledDay=360`；C 草稿 §2.5 核实该 `match` 是 day360 实轮报告，落盘快照状态是日末 tick360）。我按**全局 `regionId` 去重**复算（201 区）：

| 布 @tick360 快照（day360 实轮报告 + 读时重算） | 数 |
|---|---|
| `supplyMilli`（读时卖单量之和，结算后重算） | 30,864,321,363 |
| `effectiveDemandMilli`（读时买订单量之和，结算后重算） | 178,213 |
| `match.tradedMilli`（day360 实轮成交） | 196,119 |
| 卖方未成交 | `no_budget`: 4,721 笔 / 30,866,646,662 |
| 买方未成交 | `algorithm_uncovered`: 267 笔 / 464,160（**没有 `no_budget`**） |

  - **day365 干跑窗口**（本轮 GProbe，同一 tick360 快照、开市前不经步进）：`sell:cloth:NO_BUDGET=4,724 / 30,864,321,171`（= 卖单总量 30,864,321,363 − 成交 192）；`buy:cloth:ALGORITHM_UNCOVERED=265 / 178,021`。
  - 两个窗口的日号/口径不同（C 草稿 §2.5 的"读时 ≠ 开市时"警告）；下一条的反例只用**同一个 dry-run 轮内**的数字，避免跨窗口。

**(3) 正面反例：同一轮 dry-run 里 `sell no_budget` 与"三种生产方式都有成交"同时成立** `[探针实测]`
- 就在上面那个 A dry-run day365 轮内：11 笔布成交 = **7 笔家户 + 3 笔 weave 经营者 + 1 笔 craft 作坊**，全部 `unitPrice=5`、`immediate=true`、同一个买方 `HOUSEHOLD:-39_-71:rural:landlord`，来自 3 个不同卖方格；最大的单笔是 craft 作坊的 113 毫匹（`/tmp/g-gprobe.txt` 的 `clothFill[...]` 逐笔）。
- 同一轮里却报了 `sell:cloth:NO_BUDGET=4,724` 笔、而买方剩余 265 笔全是 `ALGORITHM_UNCOVERED`。⇒ `no_budget` 这个标签是"世界上还有未成交布买单"的全局信号，**与"craft/weave 因生产方式被挤出"相矛盾**；同一轮内三种生产方式都成交了。
- `[用户目标]` "生产方式竞争失败"在这个代码里不是可判定的：代码既没有"每卖方成本/利润"，也没有"同一商品按生产方式排序/过滤"的规则；所有卖方在同一市场参考价 5 下挂 `minPrice=4`，作坊与家户的差别只体现在各自 `sellable`（库存/保留）上。

**不能下的结论**
- 不能从 4,721 笔 `sell no_budget` 推出"买方没钱"（买方侧 267 笔全是 `algorithm_uncovered`）。
- 不能推出"某种生产方式（craft/weave/家户）竞争失败"；代码没有这个维度，且 11 笔成交里三种主体都有。
- 不能推出"这些卖方本可以成交"；残量 30.86B  vs 买量 178,213（≈173,000 倍）主要是需求侧（库存已足 + 钱少 + 2 毫边距）与窗口口径，而不是卖方之间谁赢谁输。

**实际读写路径**
- 读：`EconomyData` 的价格表/阶层行/产业/关系/配额/在途 + `ActorData` 账户会话副本；写：轮内会话副本 + 瞬态 `MarketReport`；**落盘只有** `EconomyData`（价格表、在途批次等）与 `ActorData` 账户，`Fill/Unfilled` 的 actor 级明细不落盘；读口 `MarketReadout` 按 `(region, commodity, reason)` 聚合（`MarketReadout.java:329-404`）。

**缺哪项可观察数据（只列缺什么，不提方案）**
1. **逐卖方成交率**：每个卖方/经营者每轮每商品的 `matched / sellable`，含**零成交卖方**。现状：`MarketReport.Fill` 有 seller/buyer 身份，但 `MarketReadout.matchReadout:344-360` 只按 `(region, commodity)` 汇总成交量和到货价，**把 seller/buyer 身份聚合掉了**；`MarketReportFeed` 是进程内静态表（`MarketReportFeed.java:10-33`），`EconomyDayStepper.lastMarketReport` 也是瞬态（`EconomyDayStepper.java:508-514`），推进结束即丢。
2. **生产成本（货币）或可比的单位成本口径**：当前 `Industry` 只有实物 `inputPerUnit/laborPerUnit/outputPerUnit/capacity`；`ProductionLedger` 当日 fold 后丢弃（C 草稿 §1.4），没有"某卖方每单位布花了什么成本"的稳定读数。
3. **被选中的对手集合/候选集合**：每笔买单当时还有哪些卖单在同一区/邻接区、限价/时限/运力是否合格、最终被谁成交；现在只有赢家的 `Fill`（瞬态），没有输家/候选/名单位置记录。
4. **市场时点逐买/卖单的 actor/hex/原因/数量**：`MarketReport.Unfilled` 本身有 `actor/buyerSide/commodity/quantity/reason/hex`（`MarketReport.java:165-171`），但瞬态；落盘/读口只剩按原因聚合的计数与数量（`MarketReadout:361-376`）。
5. **每轮 `sellable` 的构成**：`stock/frozen/necessary/life` 四项在 `planFor`/`ordersFor` 里现算（`:693-703`），不进任何持久字段；没有它就无法区分"没货可卖"与"有货但没被选中"。
6. **多轮时序**：只有"最近一轮"报告（`EconomyDayStepper.lastMarketReport` 只保留最后一次，`EconomyDayStepper.java:479-484/508-514`）；没有逐轮历史，无法判断某卖方是被系统性排除还是单轮波动。
7. **生产方式 ↔ 卖方身份的稳定映射**：`ActorRef.id` 串里能临时看出 `weave@`/`craft@`，但没有按 technology/产业种类的读数组件；要判"生产方式竞争"需要把卖方映射到产业、投入、产出、库存，再逐轮统计。
8. **读时 vs 开市时的窗口对齐**：dump 的 `marketReadout` 是结算后重算的读时量（`effectiveDemandMilli/supplyMilli`），而 `match.unfilled*` 是当天开市报告（C 草稿 §2.5）；两者不可混算，需要市场时点的逐单快照才能把"卖不掉"精确归因。

**未验证**
- 没有重跑 C 的 `PlanOrdersProbe`；C 的逐行 TSV 和结论是引用+复算，不是本轮重新生成。
- 没有对 @tick360 的 day360 实轮做逐卖方成交明细（该进程已结束，`Fill` 未落盘；A 干跑是 day365 的另一个窗口）。
- 没有构造"同一区、同商品、两种生产方式、供不应求"的变异实验；"生产方式竞争失败"未被任何实测证伪或证实，只是现有代码无法表达该假设。

---

## 第 5 题：两个数字的对账 + 百分比分母

### 5.1 2,114 vs 5,483：核实结果与逐项对比

**结论** `[代码事实 + 探针实测]`
- 两处都是 **day 365、`PERIODIC`**，起始 store 都是 `.superpowers/sdd/2026-09-27-m2-general-market/store-m2` 的 revision 11（tick 360）。**不是不同日、不是不同 `MarketTrigger`。**
- 差异**全部来自"轮开始之前有没有步进 361–364（以及 day365 自身的步前阶段）"**：
  - A = 在 tick360 快照上直接调用一次 `clearOncePerCycle(roundDay=365)`（跳过 361–364）⇒ 2,114 笔；
  - B = 从同一 tick360 快照 `step(361)…step(364)` 后，在 `step(365)` 内触发 day365 例行轮 ⇒ 5,483 笔。
- 1,822 / 10,002 是 A 干跑那一轮的订单条数；B 的 FillProbe 只打印成交，没有订单条数记录。两者不可用"订单数"互相横比。

**逐项对比表**

| 维度 | A：AProbe 干跑 | B：FillProbe 真步进 |
|---|---|---|
| 探针源码 | `/tmp/probe/io/mosire/simos/economy/time/AProbe.java:129-225` | `/tmp/probe/src/io/mosire/simos/app/time/FillProbe.java:67-69` |
| 起始状态 | `store-m2` rev11 = tick360（只读 replay） | 同一份 `store-m2` rev11 = tick360（只读 replay） |
| 是否步进 | **否**：建好会话副本后直接 `clearOncePerCycle`（`AProbe.java:183-184`），361–365 的步进只发生在**另一份干净副本**上（`:227-269`） | **是**：`for (day=361; day<=365; day++) stepper.step(day)`（`FillProbe.java:67-68`），取 `stepper.lastMarketReport()`（`:69-89`） |
| 轮次日 | `roundDay=365`（AProbe 参数；`:53/189`） | `reportDay=365`（输出头） |
| MarketTrigger | `triggerFor(365,false,...)`（`:152`）→ `PERIODIC`（365%5==0） | `trigger=PERIODIC`（输出头） |
| 计数点 | `outcome.report().fills().size()`（`AProbe.java:198`） | `r.fills().size()`（`FillProbe.java:82-83`） |
| 订单 | 买 1,822 / 卖 10,002（`:165-176`） | **未记录**（只打印 fills） |
| 成交笔数 | **2,114**（`immediate 2,114 / cross 0`） | **5,483**（`immediate 5,483 / cross 0`） |
| 分商品 | grain 2,057 / fiber 46 / cloth 11 | grain 2,816 / cloth 2,168 / fiber 499 |
| 成交量（毫） | grain 1,097,334 / fiber 352,998 / cloth 192 | grain 11,926,129 / cloth 289,993 / fiber 1,078,339 |
| 中间市场轮 | 无（361–364 未跑） | day363 `LOW_GRAIN_STOCK`：4,706 笔（grain 2,983 / cloth 1,374 / fiber 349） |
| 窗口口径 | tick360 快照上的 day365 干跑；不是年度累计、不是 day360 实轮 | tick360 快照真步进到 day365 后的例行轮；不是"tick360 状态上的干跑" |

**探针证据**
- `[代码事实]` 运行字节码一致性：`simos-economy/target/classes/.../MarketSettlement.class` 与 `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar` 内同一 entry 的 md5 都是 `2b8afaa28b56ada7f2c58421f44203b1`；shaded jar mtime `2026-09-28 01:25:54`，`find src -newer <jar>` 无命中。AProbe/GProbe 用 `target/classes`、FillProbe 用 shaded jar，两侧是同一份 `MarketSettlement` 实现。
- `[探针实测]` 本轮重跑 AProbe：`/tmp/g-aprobe.json` → `marketRoundDryRun {trigger PERIODIC, roundDay 365, buyOrders 1822, sellOrders 10002, fills 2114, fillsByCommodity {grain 2057, fiber 46, cloth 11}}`；其 `dayTimings` 显示 day361 step 1,202 ms、day363 marketOpened=363、day365 marketOpened=365（`/tmp/g-aprobe.json`）。
- `[探针实测]` 本轮重跑 FillProbe：`/tmp/g-fillprobe.txt` → `DAY 363 … fills=4706`、`DAY 365 reportDay=365 trigger=PERIODIC fills=5483 immediate=5483 cross=0`.
- `[探针实测]` 逐笔复核 B 的 5,483 笔（脚本对 `/tmp/g-fillprobe.txt`）：`unitPrice == 卖方格 ref == 买方格 ref` 5,483/5,483，`seller.bid ≤ unitPrice ≤ buyer.ask` 5,483/5,483，`freight=0`、`immediate=true` 5,483/5,483。
- 原 A/B 草稿产物与我的重跑逐值一致：`/tmp/probe-out.json`（A 5 天）、`/tmp/probe30-out.json`（A 30 天）、`/tmp/fill-probe-all.txt`（B，day365 在 9417 行）；`/tmp/adv390b.log` 的 `推进完成（201.9s）` 是另一个 JFR 装置的 360→390。

**建议写进报告的正确表述（只修正口径，不改数）**
1. §1.4 的订单/成交行改为：
   > 订单与成交（**tick 360 快照上直接干跑 day 365 一轮，未经 361–364**）：买 1,822 / 卖 10,002；成交 **2,114** 笔（grain 2,057 / fiber 46 / cloth 11）。
2. §3 的"该日 5,483 笔"所在句改为：
   > 同一 tick 360 快照上**从 361 真步进到 365 后**，day 365 例行轮（`PERIODIC`）的成交为 **5,483** 笔（grain 2,816 / cloth 2,168 / fiber 499）；逐笔 `unitPrice == 双方 ref`，且 `sellerBid ≤ unitPrice ≤ buyerAsk`。
3. 在任一处加一句边界（避免被读成同一状态的两个口径）：
   > 2,114 与 5,483 **同为 day365、同为 `PERIODIC`**；前者是"tick360 状态直接干跑"，后者是"从同一 tick360 快照步进到 day365 后的例行轮"，两者不可互相替代。两者也都不是 **day360 实轮**（@360 实轮布成交 196,119，来自 `m2t360.json` 201 区全局去重）。

**关键代码位置 / 实际读写路径**
- A：`AProbe.java:129-225`（干跑段；`:152` trigger、`:183-184` clearOnce、`:198` fills）；B：`FillProbe.java:67-69`（步进循环 + 取报告）、`:82-83`（fills 打印）。
- 两者都只读 `store-m2`/`store-probe` 的 revision 11（`Replay.replay`），**不写 store**；`GProbe` 也只是在内存副本上跑撮合（`/tmp/gprobe/.../GProbe.java`）。
- 报告里的 2,114 来自 `MarketReport.fills().size()`；5,483 来自 `EconomyDayStepper.lastMarketReport().fills().size()`；两条路径都进同一个 `clearOncePerCycle` 实现。

**未验证 / 不能从本轮推出**
- B 的 day365 **市场时点订单条数**未记录（`FillProbe` 只打印 fills；step 之后会话余额/库存已被成交改变，事后再调 `planOrders` 得到的是"读时"口径，不是开市时刻——C 草稿 §2.5 已记同款窗口问题）。
- 本轮没有重跑 `AProbe` 的 30 天窗口（只跑了 5 天版；30 天数字引用既有 `/tmp/probe30-out.json` 与 A 草稿）。
- 没有把 B 的 361–365 逐日状态差拆成 "day361 周期首日 / day363 追加轮 / 日常消费到货" 各贡献多少笔成交。

---

### 5.2 360→390 前五项百分比为何相加 >100%：三个分母族的对账

**结论** `[代码事实 + 直接数表复算]`
- 报告 §1.2 的五项百分比不是同一个分母：第 1/2/4/5 项写作 `÷169.722 s`（第 4 项其实在 day-loop 之外，是分子分母口径错配）；第 3 项同时出现"JFR 10.1%"（`1,644/16,275` 采样分母）和"≈9–11%"（`15–20 s ÷ 169.722` 墙钟分母）。
- 把报告里的值直接相加：`74.3 + 17.2 + (9~11) + 3.0 + (2~4) = 105.5% ~ 109.5%`。**>100% 不是数据错，而是"把 step 的内部子项叠加到 step 的两个切片上"+"把 day-loop 外的 Replay 塞进 day-loop 分母"+"采样百分比与墙钟百分比混加"。**

**三个分母族（四个数）**

| 代号 | 数值 | 定义 | 证据 |
|---|---|---|---|
| **D1** | **201.9 s** | JFR 装置下 360→390 `simos.advance` 的推进墙钟（`/usr/bin/time` 202.57 s 为进程口径） | `/tmp/adv390b.log`；A 草稿 §1.3 |
| **D2** | **≈175 s** | 同次**无 JFR** 的 360→390**推导**总墙钟 = D3 `169.722` + Replay `5.1` + diff/序列化/落盘 `<0.3`；**不是独立实测的整段墙钟** | A 草稿 §0.2 / §2.2；`/tmp/probe30-out.json` |
| **D3** | **169.722 s** | 嵌套计时 day-loop + 落账 = `step 140.229 + OwnershipBooks.apply 29.147 + land* 0.346` | `/tmp/probe30-out.json` `dayTimings` 逐日求和；A 草稿 §2.2 |
| **D4** | **16,275 个样本**（日循环 15,776 = **96.93%**） | JFR `jdk.ExecutionSample` 总数；`A-final2.json` 的 level1/level2 百分比一律 `样本数/16,275` | `/tmp/A-final2.json`；`/tmp/jfr_final2.py`（`tot=len(samples)`，`v/tot*100`） |

**项 × 分母 × 百分比对照表**

| §1.2 行 | 报告值 | 报告百分比 | 该百分比实际分母 | 同分母精确值 | 换成其它分母 |
|---|---|---:|---|---:|---|
| 1 区域市场轮 | 126.031 s（12 轮） | **74.3%** | **D3** | 126.031/169.722 = **74.26%** | D1 = 62.42%；D2 = 72.02%；D4 的"市场样本" = 10,896/16,275 = 66.95%（**不同项目**：样本归因 `MarketTrigger+clearOncePerCycle`） |
| 2 `OwnershipBooks.apply` | 29.147 s | **17.2%** | **D3** | 29.147/169.722 = **17.17%** | D1 = 14.44%；D2 = 16.66%；D4 = 2,926/16,275 = 17.98% |
| 3 每日末态构造/校验 + 索引辅助 | JFR 1,644 样本；墙钟估 15–20 s | **10.1%**（JFR）/ **≈9–11%** | **D4**（10.1%）/ **D3**（9–11%） | 1,644/16,275 = **10.10%**；15–20/169.722 = **8.84–11.78%** | D1 折算 10.10%×201.9 ≈ 20.4 s；D3 折算 10.10%×169.722 ≈ 17.1 s |
| 4 推进前 Replay | 5.1 s（热）/6.1 s（冷） | **3.0%** | **D3**（报告如此；但 Replay 在 day-loop 之外 ⇒ 口径错配） | 5.1/169.722 = **3.00%** | 用 **D2** = 2.91%；用 **D1** = 2.53%（对整段而言 D2/D1 才是正确分母） |
| 5 非市场日其余阶段 | ≈3–7 s | **2–4%** | **D3** | 3–7/169.722 = **1.77–4.12%** | D2 = 1.71–4.00%；D1 = 1.49–3.47%；此项按 A 草稿是"非市场日 step 扣掉第 3 项后的部分" |
| （第 3 项的同族：land*） | 0.346 s | 未列入前五 | D3 | 0.346/169.722 = **0.20%** | — |

**为什么不能相加（逐条）**
1. **第 3 项是 `step` 的内部子项，第 1、5 项是 `step` 的两个切片**：`step 140.229 = 开市日 step 126.031 + 非开市日 step 14.198`（A 草稿 §2.2）。第 3 项（`EconomyData.<init>/requireStratumAllowed` + 索引辅助）分布在**开市日和非开市日**的 step 里——`settleOneDay` 每个结算日返回前都会 new 一次 `EconomyData`（A 草稿 §2.6③；`EconomyData.java:190/249/422`；`EconomySettlement.java` 的每步收口）；报告第 5 项自述"非市场日 step 扣掉每日末态构造后的部分"，即已经减过一次第 3 项，但第 1 项**没有减**。于是 `第1项(含其第3项份额) + 第3项(全量) + 第5项(净额)` 把第 3 项里的开市日份额算了两遍。
2. **第 3 项的 `10.1%` 是 D4（16,275 个样本）的份额，不是 D3（169.722 s 墙钟）的份额**：采样只覆盖 Java 线程 runnable 的 ~10 ms 一拍，不含阻塞/GC；JFR 下 201.9 s vs 无 JFR ~175 s 是两种装置，不能把 D4 的百分比直接当 D3 的墙钟百分比。报告 §1.2 的表头写"同包探针直接墙钟，非采样"，但第 3 行的 `10.1%` 是例外——这就是混加的来源。
3. **第 4 项 Replay 在 `step`/`apply` 之外**：D3 的定义（`:140.229+29.147+0.346`）从 `stepper.step` 第一天才开始，Replay 是推进前的状态载入。5.1 s 除以 169.722 s 得到的 3.0% 只表示"相对于 day-loop 的大小"，不是整段中的份额；放进整段饼应除以 D2（~175 s）或 D1（201.9 s）。
4. **第 5 项是"扣减后的残差估算"**，不是独立测量：A 草稿写"非市场日 step 合计 14.20 s 中扣掉每日末态构造后的部分"；它本身依赖第 3 项的 15–20 s 估计，且仍嵌在 `step` 里。
5. **96.93% 只是 D4 里"日循环"的份额，不是其它百分比的分母**：level2 的 `31.95% / 22.66% / …` 是 `样本数/16,275`；如果归一化到日循环，`matchAcrossRegions` 是 `5,200/15,776 = 32.96%`，不是 31.95%。96.93% 不能加到任何项上。

**哪些可以相加（同一分母、互斥划分）**
- **D3 的互斥划分**：`开市日 step 126.031（74.26%）+ 非开市日 step 14.198（8.37%）+ apply 29.147（17.17%）+ land* 0.346（0.20%）= 169.722（100%）`。若要把第 3 项单列，应从 `step` 两个切片里各减掉其份额，而不是在三项之外再加一次。
- **D4 的互斥划分**：A 草稿 §2.3 的阶段表 `level2` 38 个桶逐样本唯一归类，`Σ=16,275=100%`；这些桶互相可加。但它们**不能**与 D3/D2/D1 的百分比相加。
- **整段（D2 或 D1）的互斥划分**：若要一张"占整段多少"的饼，用 D2/D1 当分母，把 day-loop、apply、land、Replay、diff/序列化/落盘各自列为互斥项；此时同一项的值会变（如市场 126.031/175 = 72.0%，而不是 74.3%）。
- **不可相加清单**：D4 的任意百分比 × D3/D2/D1 的任意百分比；第 3 项 × 第 1 项 + 第 5 项（系数已重复）；第 4 项的"3.0%（D3 分母）" × 其它 D2/D1 项；96.93% × 任何东西。

**为什么不估并行加速比**：D1 是 JFR 装置、D2 是无 JFR 推导、D3 是嵌套计时、D4 是采样计数，四者分别是四种测量配置；本报告不据此计算任何并行加速/串行占比，也不把 `201.9/175` 之类的比值当作加速比。

**探针证据**
- `[探针实测]` `/tmp/probe30-out.json`：`step` 30 天合计 140,229 ms（12 开市日 126,031 + 18 非开市日 14,198）、`apply` 29,147 ms、`land*` 346 ms、`fold` 0；`marketOpened` 的 12 天 = 363,365,368,370,373,375,378,380,383,385,388,390。
- `[探针实测]` `/tmp/A-final2.json`：`total=16,275`；`level1: dayloop=15,776/96.93, load=435/2.67, …`；`level2: matchAcrossRegions=5,200/31.95, terrainIndex=3,688/22.66, apply=2,926/17.98, X3=1,300/7.99, refreshSellFrozen=1,245/7.65, L1=435/2.67, X4=344/2.11…`。`/tmp/jfr_final2.py` 的 `dump()` 用 `v/tot*100` ⇒ level2 分母恒为 16,275。
- `[直接复算]` `1,644/16,275 = 10.10%`；`15/169.722=8.84%`、`20/169.722=11.78%`；`74.3+17.2+10.1+3.0+4.0=108.6%`（下界用 9/2 则为 105.5%）。

**关键代码位置 / 实际读写路径**
- 百分比来源是只读计数：`/tmp/probe30-out.json` 的 `dayTimings`（探针在内存里逐日计时）、`/tmp/A.jfr` → `/tmp/jfr_final2.py` → `/tmp/A-final2.json`（JFR 解析）、`/tmp/adv390b.log`（JFR 装置的 advance 输出）。
- 这些都不是状态字段；没有对 `src/**` 或 store 的写。

**未验证**
- D2 ≈175 s 是 A 草稿的推导值，**没有一段"无 JFR、只跑 360→390 advance"的独立墙钟**；`AProbe` 的 30 天进程墙钟 199.2 s 含干跑+codec+载入，不能直接当 D2。
- 第 3 项没有独立墙钟（报告写"估算 15–20 s"），只有 D4 的 1,644 样本；把它折成墙钟依赖 D1 或 D3 的选择。
- 没有对 JFR 下的 201.9 s 与无 JFR 的推进做同输入双跑计时对账（JFR 服务与探针不是同一进程/同一装置）。

---

## 附：本轮探针与命令（可复现的最小集）

```bash
cd /home/cna/SimulatorMosire
CP=$(cat /tmp/probe-cp.txt)   # target/classes 各模块 + shaded jar（探针与运行 jar 同一 MarketSettlement 实现）

# ① A 路径：tick360 上直接干跑 day365（顺带 dayTimings 361..365）
java -cp "/tmp/probe-classes:$CP" io.mosire.simos.economy.time.AProbe /tmp/store-probe 360 365 365 \
  > /tmp/g-aprobe.json
#    → fills=2114，buyOrders=1822，sellOrders=10002

# ② B 路径：从 tick360 真步进 361..365，取 day365 报告
java -cp "/tmp/probe/classes:$CP" io.mosire.simos.app.time.FillProbe /tmp/store-probe 11 360 365 \
  > /tmp/g-fillprobe.txt
#    → DAY 363 fills=4706；DAY 365 trigger=PERIODIC fills=5483

# ③ G 探针：把 ① 与 ② 放在同一程序里，并额外输出逐卖方成交构成 / 未成交原因
mkdir -p /tmp/gprobe-classes
javac -proc:none -cp "$CP" -d /tmp/gprobe-classes \
  /tmp/gprobe/io/mosire/simos/economy/time/GProbe.java
java -cp "/tmp/gprobe-classes:$CP" io.mosire.simos.economy.time.GProbe /tmp/store-probe 360 365 365 \
  > /tmp/g-gprobe.txt
#    → A dry-run fills=2114（cloth 11=家户7+weave3+craft1；
#      sell:cloth:NO_BUDGET=4724，buy:cloth:ALGORITHM_UNCOVERED=265）；B fills=5483（cloth 2168=家户1553+weave487+craft128）
```

- `/tmp/store-probe` 是 `.superpowers/sdd/2026-09-27-m2-general-market/store-m2` 的只读副本（tick360 = revision 11）；探针只 `Replay.replay`，不写 store。
- `m2t360.json` 的 201 区去重复算用一个一次性 `python3` 脚本完成（读 `nations[*][*].economy.marketReadout.regions`，以 `regionId` 为键去重后按商品求和）；本轮未把它落成第二个仓内文件。

---

## 附：我没做 / 没验证的

- **没改任何 `src/**`、`docs/**`、既有台账**；没 `git add/commit`；**本文件是唯一仓内写盘产物**；临时物（`/tmp/gprobe/**`、`/tmp/g-*.txt/json`、复用的 `/tmp/probe*`、`/tmp/A*`、`/tmp/fill-probe*`）都在 `/tmp`。
- **没跑 Maven**：GProbe 只用 `javac -proc:none` 对既有 `simos-*/target/classes` + shaded jar 编译；AProbe/FillProbe 复用既有 `/tmp` 探针类；没有重编译生产代码，没有跑 `compile/test/verify/package`，没有跑整年。
- **没有为"竞争失利"构造变异世界**：没有造"同商品、两个成本不同/顺序不同的卖方、供不应求"的夹具；关于名单序对总量/配对的影响是代码结构 + `ProportionalSplit`/`pairUp` 的静态推断，**没有换序实测**。
- **没有逐笔捕获 tick360 day360 实轮的卖方成交明细**：`MarketReport.Fill` 不落盘，dump 只有按区的聚合；本报告里的 @360 布成交 196,119、卖方 `no_budget` 4,721、买方 `algorithm_uncovered` 267 是从 `m2t360.json` 的 `marketReadout`（201 区去重）**复算**的，不是逐卖方实测。
- **没有重跑 C 的 `PlanOrdersProbe`**：`3,195/3,196` 的 gap0 计数、唯一农村 gap 行（192）等来自 C 草稿的探针输出；我只独立解析了 `/tmp/invC/probe_detail360.tsv`，复算出 **266 行（1 rural + 265 urban）、qty 合计 178,213、urban money 合计 902、gap 合计 10,656,694**，与 C 的结论逐值一致。
- **没有看到跨区/带运费的成交 Fill**：A 干跑与 B 步进的 day363/365 都 `cross=0`；`unitPriceOf`、`freightPerUnitOf`、`maxLandedPrice` 的跨区行为是回代码核对的，不是实测成交。
- **没有验证 D2 ≈175 s 的独立可测性**；没有做无 JFR 的 360→390 整段墙钟对照（只用了既有 A 探针的 199.2 s 进程墙钟作旁证，含额外装置）。
- **没有估算任何并行加速比**；没有把 JFR 201.9 s / 无 JFR 推导 175 s 折算成"并行度"或"串行占比"。
- **没有把两处表示层非决定性（`Region.hexes=Set.copyOf`、`CrisisMonitor.Light.evidence=Map.copyOf`）与本次数字对账关联**；它们不影响这里的状态值和计数。
