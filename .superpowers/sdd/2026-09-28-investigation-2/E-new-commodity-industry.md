# E：新商品与新产业能否在运行中进入？——只读调查（调查单第 1 题）

> **代码态**：`ts/m1` @ `a7fbfe46`（`git log -1` 的 HEAD；`git status --porcelain` 为空，工作树干净）。
> 基线文档已先读：`docs/superpowers/reports/2026-09-28-economy-system-flow-report.md`、
> `docs/superpowers/reports/2026-09-28-economy-investigation.md`。
>
> **纪律**：只读调查。未改 `src/**`、`docs/**`、既有台账；未 `git add/commit`；未跑 Maven；未跑整年。
> 探针在 `/tmp/e-probe`，只对仓库已有 `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar` 做
> `javac` + `java`（**没有重建 jar**，也**没有跑 Maven**）。
>
> **标记约定**：
> 【代码事实】= 直接读当前树或探针实测；【代码推断】= 由代码路径推出、未端到端运行；
> 【用户目标需求】= 题目背景里“希望存在的能力”，不是现状。
>
> 本文所有 `文件:行` 都按当前 HEAD 的工作树；基线报告里的行号若不一致，以本文为准。

---

## 0. 结论速览

| 问题 | 现状 |
|---|---|
| `CommodityId` 能否任意新建 | **能**：类型只拒绝 `null`/空白，没有格式约束、没有注册表、没有词表成员校验（§1）。 |
| 新商品能否进状态 | **能**：任何 `Map<CommodityId, Long>`（账户余额、市场价、配方投入/产出、`naturalNeeds`/`effectiveDemand`）都能由 `economy.Seed` / `actor.Seed` 载荷写进任意非空白商品 id（§1–§4、§6）。 |
| 新商品能否进既有市场价表 | **不能（无命令）**：参考价只在播种载荷里写；运行期唯一可能的写回是默认关闭的自适应定价，且它只改“已经定价”的商品（§5）。 |
| 新商品能否形成家庭需求 | **不能**：家庭 35 天生活保留硬编码粮/布；其他商品经 `selfNeedOf` 静默算 0；`dailyNeedsMilli` 也只产粮/布，并且每个结算日把 `naturalNeeds` 覆写掉（§3、§9）。 |
| 新商品能否形成经营者买/卖 | **买**：可以，但只能来自经营者的“必要投入”（`Industry.inputPerUnit`），且该格价表必须有该商品价；**卖**：可以，凡有价且可卖的库存都会挂卖单。家户方向的买单对非粮/布恒为 0（§4、§9）。 |
| 新产业能否进 | **按预期只能经 `economy.Seed`**：首次播种，或对“经济未占用”的新格按格追加；对已有产业/阶层行的格**整份拒绝**（§6）。 |
| 运行中能否把新产业加进既有格 | **代码里存在一个未申报目标错位的旁路**：`economy.Seed` 只按 `entries[].q/r` 判占用，不校验 `industries[].id` 里的格是否等于 entry 的格；因此一个“未被经济占用的 entry + 指向已占用格的产业 id”可绕过判重（同 id 覆盖，或新 id 追加）。探针已用 handler 的真实 `occupiedHexKeys`/`merge` 复现（§6、§11）。是否把它算“能进入”取决于是否承认这条旁路；按“受支持入口”算不能。 |
| 经营者账户能否运行期建 | **命令面只能 `actor.Seed` 且只对“没有任何账户行”的新格追加**；既有账户格不能再由命令行加账。另有结算侧“首次产出计提 → `OwnershipBooks.apply` 自动建一本商品账”的路径（§7）。 |

【用户目标需求】“运行中引入新商品/新产业”在代码里的最近落点是：
`economy.Seed` 追加新格 + `actor.Seed` 给同格建账；既有格、既有价表、家户口粮/衣着需求这三处都没有运行期扩展口。

---

## 1. `CommodityId` 的形态与守卫（能否任意新建）

### 结论
【代码事实】**能任意新建**。`CommodityId` 是一个单组件 `record`，构造器与 `parse` 只检查“非 null、非空白”，
注释明说“只校验非空白，不做格式约束（分配器属命令层）”。没有枚举、没有注册表、没有“已声明商品集合”的运行时守卫。

### 关键代码位置
- `simos-economy-api/src/main/java/io/mosire/simos/economy/api/id/CommodityId.java:8-27`：
  `record CommodityId(String value)`；`:10-14` 只拒 null/空白；`:21-27` `parse` 同样只拒 null/空白。
- 对照：`IndustryId` / `RegimeId` 也是同一形态，只拒空白（`IndustryId.java:9-28`、`RegimeId.java:17-36`）。

### 实际读写路径
1. 载荷解析对商品键**不做词表校验**：
   - `simos-economy/.../spi/EconomyPayloads.java:809-825` `commodityMap`：任意字段名都走 `CommodityId.parse`，没有 `allCommodityIds().contains` 之类的判断；
   - `:755-780` `assetMap` 只校验 `AssetKind` 名，不校验商品；
   - `:782-807` `assetCommodityMap` 只校验外层是生产资料种类、内层交给 `commodityMap`；
   - `simos-actor/.../spi/ActorPayloads.java:185-192,214-224`：账户 `balances` / `frozenBalances` 的键同样只走 `CommodityId.parse`。
2. 状态类型只做结构/数值守卫：
   - `simos-economy/.../model/Market.java:43-68`：`prices` 逐值必须 `> 0`，但**没有键的商品 id 白名单**；
   - `simos-economy/.../model/Industry.java:226-269,318-330`：投入/产出的商品键同样只判 null、值非负；
   - `simos-actor/.../model/GoodsAccount.java:157-169`：余额键只判 null、值非负。
3. 唯一“读口词表”是 `EconomyVocabulary.allCommodityIds()`；它没有被任何行为路径当白名单读（见 §2）。

### 证据（探针）
【代码事实】`Probe` 输出：`new CommodityId("nectar")` 可以构造；`Market(silver, {nectar: 7})`
的 `priceOf(nectar)=7`、`priceOf(absent)=0`；`EconomyPayloads.toData` 接受含 `nectar`/`pollen` 的市场价与配方。
完整输出见 §11。

### 未验证
- 没有通过真实 MCP / 命令总线提交任意商品载荷；没有测试所有写入点。
- 没有测试“商品 id 含空格/控制字符”等边界（代码只拒 `isBlank`，探针未枚举）。

---

## 2. 商品词表是世界常量还是状态？谁读它

### 结论
【代码事实】`EconomyVocabulary` 是 `simos-util` 里的**编译期常量 + 纯函数**，不是状态：
没有字段进入 `EconomyData` / `ActorData`，没有随 revision 持久化，也没有 `withVocabulary` 之类的写口。
它只回答“这个编译版本里六个商品 id 各叫什么”，**不决定运行期允许出现哪些商品 id**。

### 关键代码位置
- `simos-util/src/main/java/io/mosire/simos/util/economy/EconomyVocabulary.java`：
  - `:44-59` 六个 id 常量：`grain` / `cloth` / `fiber` / `tool` / `iron` / `wood`；
  - `:116-124` `allCommodityIds()` 返回 `List.of(六个常量)`；
  - `:138-146` `cumulativeRationMilli(population, days)`（粮专用）；
  - `:177-185` `cumulativeClothMilli(population, days)`（布专用）；
  - `:219-224` `dailyNeedsMilli` 返回 `LinkedHashMap{grain, cloth}`（注释说“新增一档就在这里加一行”）。
- `EconomyData` 的十组件（`simos-economy/.../EconomyData.java:110-120`）里没有任何“商品词表”组件；
  `ActorData` 三组件（`simos-actor/.../ActorData.java:48-51`）同样没有。

### 实际读写路径（谁读它）
| 读者 | 读什么 | 位置 |
|---|---|---|
| 创世器 `EconomySeeder` | 只读六个 id 常量中的五个与 `cumulativeRationMilli`；**不读 `allCommodityIds()`**；出厂价表只有 grain/cloth/fiber/tool/iron，**没有 wood** | `simos-app/.../world/EconomySeeder.java:383-395,414-450,470-477,918-932`；开缸库存 `:1531-1540`；第一份 `naturalNeeds` 只写粮 `:1578` |
| 区域市场 `MarketSettlement` | 只经 `selfNeedOf` 读 `cumulativeRationMilli` / `cumulativeClothMilli`（见 §3）；市场报价表读 `market.prices()`；经营者投入读 `industry.inputPerUnit()` | `simos-economy/.../time/MarketSettlement.java:467-489,679-748,1699-1725,1727-1738,1792-1801` |
| 读口 `ApiViews` | **全仓 main 唯一读 `allCommodityIds()` 的地方**：`view.put("commodityIds", EconomyVocabulary.allCommodityIds())`；逐行 `naturalNeeds` / `effectiveDemand` 是原样发出 | `simos-app/.../gui/ApiViews.java:591-592,1206-1209` |
| `grainDiagnosis` | 只读粮：`row.naturalNeeds().getOrDefault(GRAIN,…)`、兜底 `dailyRationMilli`、`cycleNaturalNeedMilli`；它不读 `allCommodityIds()`，也不是商品扩展口 | `simos-app/.../gui/ApiViews.java:658-740`（尤其 `:676-688`） |
| 社会压力 / 危机监视 | `PopulationEconomyTimeParticipant` 读 `naturalNeeds` 的粮/布；`CrisisMonitor` 读 `cumulativeRationMilli` / `cumulativeClothMilli` | `simos-app/.../time/PopulationEconomyTimeParticipant.java:329-334`；`simos-app/.../crisis/CrisisMonitor.java:150-151` |

【代码事实】在 `simos-*/src/main` 里全仓搜索：
- `allCommodityIds` 只有 `ApiViews.java:592` 一个 main 调用点（另有 `EconomySeeder.java:238` 的注释、`MoneyVocabulary` 注释）；
- `dailyNeedsMilli` 只有 `EconomySettlement.java:4324` 一个 main 调用点；
- `cumulativeRationMilli` 的 main 读者是创世器开缸（`EconomySeeder.java:1712`）、结算/借贷/饿死、
  `CrisisMonitor`、`MarketSettlement.selfNeedOf`；
- `cumulativeClothMilli` 的 main 读者是 `CrisisMonitor.java:151` 与 `MarketSettlement.java:1797`。

### 证据
- 代码位置如上；另有 `EconomyVocabularyGuardTest`（test scope）只扫描源文件里六个字面量的拼写次数，
  它**不是**运行期白名单（例如新字面量 `new CommodityId("nectar")` 不会被它拦住）。
- 探针输出 `allCommodityIds=[grain, cloth, fiber, tool, iron, wood]`、`dailyNeedsMilli(100,1)={grain=8333, cloth=273}`。

### 未验证
- 没有把新增商品 id 写进六常量后的编译/测试行为（那会改生产代码，本调查不做）。
- 没有验证外部读口对“状态里出现词表外商品”的一致性检查（各视图直接遍历状态 map，通常照发）。

---

## 3. 需求计算：家户 35 天目标库存与经营者“必要投入”

### 3.1 家户 35 天目标库存（只认粮/布）

**结论**：【代码事实】市场给家户算 35 天目标库存的方法是
`MarketSettlement.householdLifeReserveOf(ClassRow)`，它**只调用粮、布两种口径**；
其他商品（fiber/tool/iron/wood 以及任何新 id）经 `selfNeedOf` 落到 `return 0L`，并且因为 `> 0` 才落键，
连键都不会出现在 `lifeReserves` 里。

**关键代码位置**
- `simos-economy/.../time/MarketSettlement.java:89-106`：
  `MARKET_RESTOCK_INTERVAL_DAYS=5`、`MARKET_SAFETY_STOCK_DAYS=30`、
  `MARKET_LIFE_RESERVE_DAYS = 5 + 30 = 35`。
- `:1727-1738` `householdLifeReserveOf`：只取
  `selfNeedOf(row.population(), GRAIN, 35)` 与 `selfNeedOf(row.population(), CLOTH, 35)`。
- `:1792-1801` `selfNeedOf`：
  `commodity.equals(GRAIN)` ⇒ `cumulativeRationMilli`；
  `commodity.equals(CLOTH)` ⇒ `cumulativeClothMilli`；
  **其余一律 `return 0L`**。
- `:1683-1695` `planFor` 把家户的 `life` 存进 `lifeReserves`；`:717` `target = household ? life : necessary`。

**实际读写路径**
- 家户“生活保留”只来自 `selfNeedOf`，**不读 `ClassRow.naturalNeeds`**。
- `naturalNeeds` 的唯一写入点是 `EconomySettlement.withDailyNeed`（`:4321-4340`，由 `:2431` 调用），
  其数据源是 `EconomyVocabulary.dailyNeedsMilli`（只有粮/布）；因此就算载荷里给某家户写了自定义商品需求，
  下一个结算日会被覆写成新表，自定义项消失。
- `effectiveDemand` 在 main 侧没有行为读者（详见 §8）。

**证据**
- 探针（反射调用包私有方法）：
  `selfNeedOf(100, nectar, 35)=0`、`selfNeedOf(100, grain, 35)=291666`；
  `householdLifeReserveOf(row whose naturalNeeds 含 nectar)` 输出 `{grain=291666, cloth=9589}`，
  新商品**不在结果里**。

**未验证**
- 没有跑一个“给家户发 nectar 库存并开市”的端到端场景；结论由代码与反射探针共同支撑。
- 没有验证 `selfNeedOf` 的调用方是否会因为缺键而在别处补 0（目前所有调用点都是“缺键=0”口径）。

### 3.2 经营者“必要投入”的来源（按商品可扩展）

**结论**：【代码事实】经营者的“必要投入”是**按配方可扩展的**：
`necessaryInputsOf` 遍历 `Industry.inputPerUnit()`（= `cycleInputPerUnit` 摊平，见 §6），
对任意 `CommodityId` 都能生成目标缺口；但它要求该参与者在本轮市场计划里存在（经营者账在会话副本里），
并要求 `capacityScaleOf(industry) > 0` 且产业是参与者供给该关系的 `inputSupplier`。

**关键代码位置**
- `MarketSettlement.java:1699-1725`：`necessary.merge(entry.getKey(), entry.getValue() * scale, Long::sum)`，
  键来自 `industry.inputPerUnit().entrySet()`。
- `simos-economy/.../model/Industry.java:297-330`：`recipe()` / `inputPerUnit()`；
  `inputPerUnit` 把 `cycleInputPerUnit` 各生产资料分路合计出来，键是任意 `CommodityId`。
- `MarketSettlement.java:679-748`：`ordersFor` 用 `necessary` 作为经营者买单的目标（`:717`），
  按 `budget` 折 `affordable`（`:730`）后生成 `BuyOrder`。
- `MarketSettlement.java:1654-1680`：经营者必须至少有一张会话账
  （`operatorGoods` / `operatorMoney` / 两张 frozen 之一），否则**不成为市场参与者**。

**实际读写路径**
- 写侧：配方的 `cycleInputPerUnit` 只来自 `economy.Seed` 载荷（`EconomyPayloads.java:611-620`）与
  每日重建 `EconomySettlement.withCycleState` 的原样透传（`:4392-4417`）。
- 读侧：`necessaryInputsOf`（经营者的目标缺口）与 `drawCycleInputs`（周期第一天从供方账户现扣）
  读同一份 `inputPerUnit`。

**证据**
- 探针 `EconomyPayloads.toData` 接受 `cycleInputPerUnit={"LAND":{"nectar":100}}`，
  读回 `industry.inputPerUnit()={nectar=100}`（见 §11）。

**未验证**
- 没有跑完整的“新商品作为投入 → 市场买单 → 下一周期扣料 → 产出”链路。
- 真档经营者中只有 craft 有货币周转金（基线文档 §7/§8），新商品投入能否买到还取决于对应商品的价表与预算。

### 3.3 经营者“生活保留”也只认粮/布

**结论**：【代码事实】`operatorLifeRetentionOf` 构造的 `requested` 表只放进粮与布；
`SubsistenceObligation.retentionOf` 只在“关系承诺过的商品”上取 `min(requested, promised)`。
因此关系即使承诺了自定义商品，requested 缺键 ⇒ 保留额为 0。

**关键代码位置**
- `MarketSettlement.java:1740-1790`：`requested` 只 `put(GRAIN)` / `put(CLOTH)`；
  随后调用 `SubsistenceObligation.retentionOf`。
- `simos-economy-api/.../relation/SubsistenceObligation.java:252-280`：
  遍历 `promisedByCommodity(of(relation, laborOfCohort))`，
  `requested = requestedRetention.getOrDefault(key, 0L)`，`retained = Math.min(requested, promised)`。

**未验证**：没有构造“关系承诺 nectar 给养”的状态来实跑；结论为代码事实（方法体逐行）。

---

## 4. `BuyOrder` 生成对商品的要求（市场里没有价格时会怎样）

### 结论
【代码事实】**没有价格 ⇒ 该商品在本格完全不交易**：
`clearOncePerCycle` 只遍历 `market.prices()` 的键；不在价表里的商品连 `ordersFor` 都不会被调用。
即使被调用，`ordersFor` 开头在 `reference/bid/ask` 任一 `<= 0` 时直接返回空订单。
对家户，非粮/布商品的 `target` 为 0（见 §3），所以也不会产生买单；对经营者，
只有 `necessary`（配方投入）或 `life`（粮/布给养）能产生买单。

### 关键代码位置
- `simos-economy/.../time/MarketSettlement.java`：
  - `:467-489` 市场轮逐格只遍历 `market.prices().entrySet()`；
  - `:419-422` `planOrders` 的“没定价的商品不交易”早退；
  - `:679-688` `ordersFor` 读 `priceOf/bidPriceOf/askPriceOf`，`<= 0` 返回空；
  - `:699-748` 逐参与者：
    `necessary = plan.necessaryInputs[actor][commodity]`、
    `life = plan.lifeReserves[actor][commodity]`、
    `target = household != null ? life : necessary`、
    `gap = max(0, target − available − incoming)`、
    `budget = spendableMoneyOf(...)`、`affordable = budget×1000/reference`、
    `quantity = min(gap, affordable)`；
  - `:1654-1680` 经营者无会话账 ⇒ 不参与本轮（因此也没有经营者订单）；
  - `:702-714` 卖单是**逐商品通用**的：`sellable = max(0, stock − frozen − necessary − life) > 0` 即挂卖单。
- `simos-economy/.../model/Market.java:70-73` `priceOf`：缺键 ⇒ `0`，不抛；
  `:43-68` 构造器要求“逐值 > 0”，但允许空价表。
- 读口：`simos-economy/.../time/MarketReadout.java:197` 逐区读数也只遍历锚点市场的 `prices` 键；
  没定价的商品不会出现在 `marketReadout` 的 `commodities[]` 里。

### 实际读写路径
- **有价、有货、有目标/预算**：新商品完全可以生成卖单，也可以生成经营者买单；
  下游匹配、成交、在途、到货都按 `CommodityId` 泛化（订单/转移/在途对象都带商品键）。
- **有价但无家户目标**：家户买单不生成；经营者的必要投入买单才可能生成。
- **无价**：订单两侧都为空；库存在本格不可交易，读口也不列。

### 证据
- 代码位置如上；探针证明 `Market` 接受任意商品价、缺价返回 0。
- 基线报告 §5.2 已给出同一条结论（“市场买量完全不读 `naturalNeeds`/`effectiveDemand`”），
  本文补上 `clearOncePerCycle`/`ordersFor` 的价表遍历点与家户目标点。

### 未验证
- 没有在真档状态上注入价表外商品并跑 `clearOncePerCycle`；没有实跑新商品的买卖成交。
- 没有验证跨区匹配对“只在成员格有价、锚点格无价”的完整行为（`adaptPrices` 与读数有锚点过滤；
  本文只覆盖了订单生成点）。

---

## 5. 市场参考价（`Market.prices`）的写入面

### 结论
【代码事实】参考价的全部写面只有两处：
1. **播种载荷**：`economy.Seed` 顶层 `markets` → `EconomyPayloads.market` → `Market`；
2. **可选自适应定价**：`MarketSettlement.adaptPrices`，默认关闭（`false`），且只改**该区锚点已经定过价**的商品；
   开时也只改价格值，**不会给缺价的商品造一行、不会移除已有行**。
【代码事实】没有 GM 设价命令；`EconomyData.withMarkets` 的注释明说“本批还没有‘设价’命令”。

### 关键代码位置
- 初始写入：
  - `simos-app/.../world/EconomySeeder.java:918-932` `factoryPrices()`：grain=1 / cloth=5 / fiber=1 / tool=20 / iron=10（**没有 wood**）；
  - `:470-477` `MARKET_PRICES_FACTORY` / `MARKET_FACTORY`；
  - `:892` 每个经济 entry 的格都放同一个 `MARKET_FACTORY`；
  - `:725-736` `jsonOf` 把逐格市场写进 `economy.Seed` 载荷文本；
  - `simos-economy/.../spi/EconomyPayloads.java:366-370` `market(node)`：`numeraire` 必填、`prices` 可缺；
    `:331-350` `markets(payload, entries)` 校验“市场格必须在 entries 里”；
    `:809-825` `commodityMap`：价格键只走 `CommodityId.parse`。
- 模型守卫：`Market.java:43-68` 逐值 `> 0`、冻结不可变；`:70-73` `priceOf` 缺键 0。
- 状态构造：`EconomyData.java:381-390` 只判 null/结构，注释明确“价格是数据，没有‘哪些商品该有价’的判据”。
- 命令写入：`simos-economy/.../spi/EconomySeedHandler.java:97` `merge(base.markets(), seeded.markets())`；
  占用判据只覆盖 entry 格（`:75-80,105-115`），且 `merge` 是 `putAll`（`:117-121`）。
- 运行期自适应：
  - `MarketSettlement.java:178` `MARKET_ADAPTIVE_PRICING_ENABLED = false`；
  - `:574-626` `adaptPrices`：默认直接原样返回；开启后遍历 `orderedCommodities(ctx)`（全市场价表键的并集）、
    跳过锚点未定价商品、且只对“成员格当前已有该商品价”的格 `prices.put` 新值（`:612-622`）；
  - `EconomySettlement.java:669-672` 工作副本、`:876-881` 采纳 `outcome.markets()`、
    `:1046-1052` 把工作副本交回 `EconomyData`（唯一状态写回）。
- 无命令：`EconomyData.java:560-566` `withMarkets` 的注释；
  `Shell.java:439` 注册的 economy handler 只有 `EconomySeedHandler` 一个。

### 实际读写路径与边界
- 既有经济格的参考价：**运行期没有任何命令能改**；`economy.Seed` 对已占用格整份拒绝。
- 新格：播种时一次性给定（同一个 `MARKET_FACTORY`），之后也只能被默认关闭的自适应定价改已有行。
- 【代码推断】如果某格只有市场、没有产业/阶层行（“market-only 格”），`occupiedHexKeys` 不把它算占用，
  第二次 `economy.Seed` 可以再次写这个 entry；`merge` 的 `putAll` 会**整张覆盖**该格旧 `Market`。
  这是当前代码里唯一能“替换一整张既有价表”的命令路径，但它服务的是一个没有参与者的格，
  不是给运行中的城市市场添加商品。本文未在真档里构造这种格验证，列为代码推断。

### 未验证
- 没有实跑“market-only 格二次播种覆盖”的端到端命令；
- 没有验证自适应定价开启后的完整读数/守恒（本批默认关，且它会改生产代码常量，不在本次调查范围）。

---

## 6. 产业配方与 `Industry` 的创建路径

### 结论
【代码事实】`Industry` 一个实例只有**一份配方**（`capacityPerUnit` / `laborPerUnit` / `inputPerUnit` / `outputPerUnit`），
没有“配方列表/切换配方”字段；`inputPerUnit` 是 `cycleInputPerUnit` 的派生合计，不是载荷字段。
【代码事实】`Industry` 的 main 构造点只有两个：
1. 载荷边缘 `EconomyPayloads.industry` → `new Industry(...)`（唯一的“创建新产业”入口）；
2. 每日重建 `EconomySettlement.withCycleState`（原样透传，不改变配方/产能/operator）。
唯一注册的 economy 写命令是 `economy.Seed`；没有扩容/停业/换配方/加产业的专用命令。

### 6.1 配方字段与守卫
- `simos-economy/.../model/Industry.java:108-125`：记录组件；
- `:127-289` 构造期守卫：
  - `capacityPerUnit` 非 null；`:270-273` **不得为空**（“单位规模的锚”）；
  - `:191-225` `capacityPerUnit` 逐值 `> 0`；`capacity` 逐值 `≥ 0` 且**键必须是 `capacityPerUnit` 键的子集**；
  - `:235-247` `outputPerUnit` 逐值 `≥ 0`；
  - `:248-269` `cycleInputPerUnit` / `cycleInputUsedMilli` 逐值 `≥ 0`；
  - `:176-184` `laborPerUnit` / `dailyLaborPerUnit` / `cycleLaborMilli` 不得为负；
  - `:274-288` `slots` 非空、id 不重复。
- `:297-330` `recipe()` / `inputPerUnit()`：`inputPerUnit` 是 `cycleInputPerUnit` 各路合计的唯一拼写点。
- `simos-economy/.../model/ProductionRecipe.java:51-123` 同款守卫（`capacityPerUnit` 非空、逐值正；
  投入/产出逐值非负）。
- `RecipeId` 类型存在但 main 代码零引用；没有配方目录/注册表（可由 `grep -rn "RecipeId" simos-*/src/main` 复核）。

### 6.2 创建入口与 `economy.Seed` 的占格语义
- `Shell.java:439`：`new EconomySeedHandler()`；`EconomySeedHandler.java:49-51` `type()="economy.Seed"`。
  除此之外 economy 没有第二个 `CommandHandler`（`grep implements CommandHandler` 已复核）。
- 运行期提交路径：`simos.command.submit`（`CommandSubmitTool.java:24,96-110`）是通用信封，
  可提交任何已注册命令类型；`CatalogTool.java:100-119` 给出 `economy.Seed` / `actor.Seed` 的 payload 提示。
  GM 权限集显式给 `economy` / `actor` 命名空间 unlimited（`Shell.java:895-912`），
  所以 GM 面在运行期手工构造 `economy.Seed`/`actor.Seed` 载荷是被允许的。
- `EconomyPayloads.java:228-306` `toData`：逐 entry 解析 `industries`（**`requireArray`，可为空数组**）：
  `:257-266` `industry(node)` + `relations.put(id, relation(...))`。
- `EconomyPayloads.java:587-670` `industry(node)`：
  - id/name/regime 只做非空白；
  - `operator` 可缺省，缺省时按 `RegimeOperators.defaultOperator(regime, id)` 推导
    （`RegimeOperators.java:99-121`；未登记 regime ⇒ 抛）；
  - `capacityPerUnit` 可缺省但随后由 `Industry` 守卫拒空；
  - `cycleInputPerUnit` / `outputPerUnit` / `dailyInputPerUnit` / `cycleInputUsedMilli` 的键都走
    `commodityMap`/`assetCommodityMap`，**没有商品词表校验**；
  - `allocation` 必须是对象并通过 Jackson 多态绑定；`:654-661` **只接受 `AllocationRule.Split`**，
    `WageFirst` 在播种期拒；
  - `slots` 必填非空（由 `Industry` 构造期判）。
  - **不校验 `IndustryId` 的格式，也不校验 id 里的格与 entry 的 `q/r` 一致**。
- `EconomySeedHandler.java:60-101`：
  - `base.meta().isEmpty()` ⇒ 首次播种，整份落盘；
  - 否则按格追加：`:75-80` 对**载荷 entry 的每个格**判重，任一 entry 格已被占用 ⇒
    **整份 Rejected** 并点名该格，不做“部分生效”；
  - `:105-115` `occupiedHexKeys` 只从“已有产业的 id 格”和“已有家户行的格”计算；
  - `:82-101` 通过 `merge`（`putAll`，`:117-121`）把新表并入旧表。
- `EconomyData.java:426-449` 的 `requireStratumAllowed` 只保证“每个家户行的阶层被该格某个产业允许”，
  不保证产业 id 的格与 entry 格一致。

### 6.3 旁路：entry 格与产业 id 格不一致（重点）
**结论**：【代码事实】`economy.Seed` 按 **entry 的格**判占用，但产业真正的落点是 `IndustryId` 里的 `@q_r`。
两者之间的对应关系在 `EconomyPayloads.industry` 与 `EconomyData` 都没有守卫。
因此一个“entry 是未被经济占用的新格、但 `industries[].id` 指向已占用格”的载荷可以：
- **同 id 覆盖**既有产业（配方/产能/operator/slots 被替换，`merge` 的 `putAll` 后写覆盖前写）；
- **新 id 追加**到既有格（`merge` 直接新增键）；
- 命令目标/权限声明只会看到 entry 格（`EconomySeedHandler.targetPaths` 走 `EconomyPayloads.entryHexKeys`，
  `:54-56`；实际写到的却是 id 指向的格）。

**证据（探针）**：
- `Probe2`：base 在 entry `0_0` 有 `farm@0_0`；overwrite payload 的 entry 是 `5_5`，但产业 id 仍是 `farm@0_0`。
  用 `EconomySeedHandler` 真实的 `occupiedHexKeys` 得到 `[0_0]`，`entryHexKeys` 得到 `[5_5]` ⇒ 判重交为空集；
  再用真实的 `merge` 并表后，`farm@0_0` 的 `outputPerUnit` 变成新载荷的 `{grain=99}`，且
  `new EconomyData(...)` 构造成功。
- `Probe3`：base `farm@0_0`，overwrite payload entry `5_5` + 新 id `kiln@0_0`；真实的 `merge` 后
  `merged industries=[farm@0_0, kiln@0_0]`，即**新产业已落在 0_0 上**；按 `handle` 同一并表形制构造的
  `EconomyData` 也被构造期守卫接受（classes=空）。
- 两个探针都调用 handler 的真实私有方法，但没有走完整 `handle(SimulationState, payloadJson)`
  （见 §12）。按调用链，`handle` 在判重后正是同一套 `merge`，没有第二处格一致性检查。

### 6.4 新产业真正跑起来还需要什么（按代码推断）
- `EconomyData` 的交叉引用：家户行需要该格某产业允许其阶层（`:426-449`）；
  劳动分配按 `actor.id` 归属，`ESTATE`/`WORKSHOP` 型 actor id 必须等于某个存在的产业
  （`:288-310`）；生产关系键必须指向存在的产业且 operator 一致（`:352-378`）。
- 结算：旧方法 `harvest` 按产业循环发产出（`EconomySettlement.java:769-840,3136-3200`），
  `hexOfIndustry` 要求产业 id 中有格键，否则在需要账户地点时抛
  （`:3722-3730`）；因此任意 id 若没有 `@q_r`，可能在第一个收获日让推进失败。
- 配方：产能/劳动/投入/产出都可扩展；但 `EconomyData` 的 `laborSupply`/`allocations`、
  actor 侧账户、`relations` 规则需要按新产业补齐，否则不生产/买不到料。

### 未验证
- 没有走完整 `EconomySeedHandler.handle` + `CommandBus` + revision 应用；没有跑一次日结算验证新产业产出。
- 没有构造“同 id 覆盖 + 既有 classes 的 slot 上限”组合的最坏情况；是否总会被 `requireStratumAllowed`
  或 `EconomyData` 其他守卫拒掉，取决于新 slots 的 `laborParticipationPerMille` 是否覆盖既有行（探针只显示了无 classes 的情形）。
- 没有验证权限层是否会因 `targetPaths` 只声明 entry 而拦住/放过这条旁路。

---

## 7. 经营者账户（`GoodsAccount` / `ActorData`）如何建立

### 结论
【代码事实】账户（`GoodsAccount`，键 `(owner, location)`，含商品余额、货币余额、两张冻结表）的建立只有两类路径：
1. **创世批**：`HouseholdSeeder.books/payload` 一次把家户与经营者主体 + 同格账户交给 `actor.Seed`；
2. **运行期命令**：`actor.Seed` 按格追加；它只拒绝“该 entry 格已有任何账户行”，因此**既有账户格不能再由命令加新账**，
   只能对没有账户行的新格建账。
另有一条**结算副作用**：首次正向产出计提会让 `OwnershipBooks.apply` 为不存在的 `(operator, location)` 建一本账
（这不是命令，是 `AdvanceTime` 提案的一部分）。

### 关键代码位置
- 模型：`simos-actor/.../model/GoodsAccount.java:103-108`（五组件）、`:122-136`（两个便捷构造器）、
  `:138-...`（键/余额/冻结守卫：余额非负、`0 ≤ 冻结 ≤ 余额`）。
- 载荷创建：`simos-actor/.../spi/ActorPayloads.java:176-237`：
  `goods(node, atHex, declared)` → 任意商品键 `CommodityId.parse` → `new GoodsAccount(...)`；
  `:255-259` 悬空 owner ⇒ 拒；`:262-270` `location` 必须等于 entry 的 `q/r`。
- 命令处理：`ActorSeedHandler.java:75-94`：`meta` 空 ⇒ 首次整份；非空 ⇒ 逐 entry 格判重，
  任一格已有账户行 ⇒ **整份拒绝**；`:104-110` 占用集 = 现有账户的 `location` 集合。
- 创世装配：
  - `EconomySeeder.java:866-883` 生成 `OperatorSeed`（farm=ESTATE、weave=HOUSEHOLD、craft=WORKSHOP）；
  - `HouseholdSeeder.java:99-152` 把家户与经营者写进同一份 `actor.Seed` 的 `actors[]`/`goods[]`；
  - `HouseholdSeeder.java:186-222` `books(...)` 直接建同样的 `ActorData`（测试/夹具）；
  - `WorldgenInitializeTool.java:557-580` 把 `EconomySeeder.plan` 的同一份计划喂给
    `economy.Seed` 与 `actor.Seed`（同一批、同一 revision）。
- 结算侧自动建账：
  - `EconomySettlement.java:3136-3200` 收获时 `ledger.addOutputAccrual(new ActorEntry(operator, location, commodity, net))`；
  - `simos-app/.../time/OwnershipBooks.java:132-145` `fold` 原样带过产出计提；
  - `:149-190` `apply` 对每个条目：`account == null ? new LinkedHashMap<>() : ...`，
    最后 `books.withAccount(new GoodsAccount(...))` ⇒ **账户缺席时建一本空账再落这笔增量**；
  - `EconomySettlement.java:4177-4195` `creditOutput` 只在该 operator 已在 `operatorGoods` 会话副本里时才写副本；
    因此“账尚未存在”的那一天，产出只进 ledger/actor 侧，次日 `OwnershipBooks.loadOperatorGoods`
    才会把它载进会话（`OwnershipBooks.java:424-465`：位置由 `IndustryHexKeys.hexKeyOf(industryId)` 推出）。
- 市场参与面：`MarketSettlement.java:1654-1680`，没有会话账的经营者不成为参与者，也就不会生成/接受订单。

### 运行期“为新产业主体建账”的可能组合（按代码推断）
- 新产业在**经济未占用的新格**：
  1. `economy.Seed`（entry 新格）种产业/配方/劳动配额/关系/市场；
  2. `actor.Seed`（同一新格）种主体与账户（家户必须一一对应，经营者可显式给开缸商品/货币）。
  2 若不做：有**人口**的家户会在推进前被 `EconomySettlement.requireHouseholdAccounts` 拒
  （`EconomySettlement.java:677-681,4240-4305`）；经营者若没有账但能满足投入/劳动（例如由 relation
  指名本格家户代理供料），首次产出计提可在 actor 侧自动建账（上一条），但**在那之前它不参与市场**。
- 新产业在**已有经济状态的格**：按受支持命令语义不可（`economy.Seed` 整份拒绝）；
  §6.3 的 id/entry 错位旁路可以在不建新账户的情况下把产业塞进去，但账户仍需既有 actor 侧账户或首次产出自动建账，
  且该写法属于权限目标错位。
- 新商品进既有市场：与账户无关，仍受 §5 的价表写面限制。

### 证据
- 代码位置如上；`ActorPayloads` 的 owner/location 守卫与 `ActorSeedHandler` 的按格拒重都是方法体事实。
- 探针未直接覆盖 `actor.Seed`；自动建账路径也未实跑（§12）。

### 未验证
- 没有实际执行 `economy.Seed` + `actor.Seed` 的追加序列；
- 没有实跑“新产业首次产出 → 自动建账 → 次日载入 → 参与市场”的链路；
- 没有验证 `operatorLocations` 对“同一 operator 出现在多个产业/格”的覆盖顺序（当前 `put` 后写覆盖，
  但这些主体通常 id 含格、每格一个）。

---

## 8. 特别核实①：为什么只写 `naturalNeeds` 或 `effectiveDemand` 不会形成订单

### 结论
【代码事实】市场订单的输入是 `plan.lifeReserves` 与 `plan.necessaryInputs`（以及会话账余额），
**没有任何一处读 `ClassRow.naturalNeeds` 或 `ClassRow.effectiveDemand` 来决定买量**。
写 `naturalNeeds` 还会在下一个结算日被覆写；写 `effectiveDemand` 则是一个“只进不出”的死字段。

### 读写点逐条
1. `naturalNeeds`：
   - **写**：创世 `EconomySeeder.java:1578`（只写粮第 1 天）；每结算日
     `EconomySettlement.withDailyNeed`（`:4321-4340`，调用点 `consumeOwnStock` 的 `:2431`），
     数据源 `dailyNeedsMilli`（只有粮/布）；
   - **行为读者**：消费粮 `EconomySettlement.java:2436`、消费布 `:2443`；
     低粮追加轮 `MarketSettlement.java:367`；社会侧日压力
     `PopulationEconomyTimeParticipant.java:329-334`；读口 `ApiViews.java:530,1206`、`MarketReadout.java:227`；
   - **不是订单读者**：`ordersFor` 的 `life` 来自 `householdLifeReserveOf`（`MarketSettlement.java:1727-1738`）
     与 `selfNeedOf`（`:1792-1801`），与 `naturalNeeds` 无关。
2. `effectiveDemand`：
   - 全 main 搜索：写入只有载荷解析 `EconomyPayloads.java:718-721` 与创世 `EconomySeeder.java:1579`（空表）；
     `EconomySettlement.java:4338/4354/4368/4387` 是 `new ClassRow` 时的原样透传；
     读只有 `ApiViews.java:1209` 的读口；
   - `MarketSettlement` / `EconomySettlement` 的行为路径没有 `effectiveDemand` 读取点。
3. 市场生成买量的实际输入（`ordersFor`）：
   - `necessary`：`plan.necessaryInputs`（来自 `necessaryInputsOf`，读 `industry.inputPerUnit`）；
   - `life`：家户来自 `householdLifeReserveOf`、经营者来自 `operatorLifeRetentionOf`；
   - `target = household ? life : necessary`（`MarketSettlement.java:717`）；
   - `gap/budget/affordable/quantity`（`:719-734`）。
   ⇒ 只改 `naturalNeeds`/`effectiveDemand` 不会进入任何一项。

### 证据
- 上面 `文件:行` 的 grep/阅读；
- 基线调查报告 §5.1/§5.7 已独立给出同一结论；
- 探针反射证明 `householdLifeReserveOf` 不读 `naturalNeeds` 的商品内容（只按人口算粮/布）。

### 未验证
- 没有在运行中通过 `economy.Seed` 注入自定义 `naturalNeeds`/`effectiveDemand` 再开市观察；
  结论是代码事实，不是运行观测。

---

## 9. 特别核实②：市场实际读取的“35 天目标库存”定义在哪、能否按商品扩展

### 结论
【代码事实】定义在 `MarketSettlement.householdLifeReserveOf(ClassRow)`，
由 `selfNeedOf(population, commodity, MARKET_LIFE_RESERVE_DAYS)` 逐商品算；
`MARKET_LIFE_RESERVE_DAYS = MARKET_RESTOCK_INTERVAL_DAYS(5) + MARKET_SAFETY_STOCK_DAYS(30) = 35`。
`selfNeedOf` 只认 `EconomySettlement.GRAIN` 与 `EconomySettlement.CLOTH`；
**其他所有商品一律返回 0**，且因 `life` 只落正值键，它们根本不会出现在 `lifeReserves` 里。

### 会被静默算成 0 的商品
- 家户 35 天生活保留：`fiber`、`tool`、`iron`、`wood`，以及任何新 `CommodityId`（例如探针的 `nectar`）——
  全部 0；
- 经营者生活保留：同样的 `selfNeedOf` 只把粮/布放进 `requested`；
  关系即使承诺了别的商品，`SubsistenceObligation.retentionOf` 也会取 `min(0, promised)=0`；
- 结果：这些商品不会产生家户买单，也不会形成经营者“生活保留”侧的买单；
  它们可能产生的买单只来自经营者的 `necessary`（配方投入）。

### 关键代码位置
- `MarketSettlement.java:89-106`（常量 5/30/35）；
- `:1683-1695`（`planFor` 把家户 life 与经营者 life 分开）；
- `:1727-1738`（家户 life 只粮/布）；
- `:1740-1790`（经营者 requested 只粮/布）；
- `:1792-1801`（`selfNeedOf` 默认 0）；
- `SubsistenceObligation.java:252-280`（保留额 = `min(requested, promised)`）。

### 证据
- 探针输出：`selfNeedOf(100,nectar,35)=0`；
  `householdLifeReserveOf(row.naturalNeeds 含 nectar)={grain=291666, cloth=9589}`。
- 基线报告 §4.2 的实测也显示“布以外”没有家户买量（布本身因库存充足 gap=0），本文补的是“新商品连目标数都不会有”。

### 未验证
- 没有跑真档市场轮验证“新商品家户买单恒 0”的读口输出；
  结论由 `selfNeedOf` 与订单生成方法体的确定性推出。

---

## 10. 对“新商品/新产业能否在运行中进入”的分层回答

### 10.1 新商品
| 环节 | 现状 | 依据 |
|---|---|---|
| 作为账户余额（商品键） | **可以**，任意非空白 id | `ActorPayloads.java:185-192`；`GoodsAccount.java:157-169` |
| 作为市场价表商品 | **可以**：只能随一次 `economy.Seed` 写进该 entry 格的市场；**不能**事后加进既有市场（无设价命令） | §5 |
| 作为配方投入/产出 | **可以**：`cycleInputPerUnit`/`outputPerUnit` 没有词表白名单；产出由 `harvest` 逐商品入账 | `EconomyPayloads.java:611-620`；`Industry.java:318-330`；`EconomySettlement.java:3147-3195` |
| 作为家户自然需求/消费 | **不可以**：`dailyNeedsMilli` 只有粮/布，且每日覆写 `naturalNeeds`；消费只吃粮/布 | `EconomyVocabulary.java:219-224`；`EconomySettlement.java:2431-2460,4321-4340` |
| 作为家户 35 天保留/买单 | **不可以（静默 0）** | `MarketSettlement.java:1727-1738,1792-1801` |
| 作为经营者必要投入/买单 | **可以**，前提：该格市场有该商品价、经营者有会话账且 `capacityScaleOf>0` | `MarketSettlement.java:1699-1725,1654-1680` |
| 作为卖单 | **可以**：价表有价即逐商品通用生成 | `MarketSettlement.java:702-714` |
| 跨区在途/到货 | **可以**：对象按 `CommodityId` 泛化 | `EconomySettlement.java:1070-1102`；`MarketSettlement` 跨区路径 |
| 读口 `commodityIds` 词表 | **不会出现**：读口固定六常量（这不是状态） | `ApiViews.java:591-592`；`EconomyVocabulary.java:116-124` |

【代码推断】因此“新商品”在现有代码里是**一条完整的实物/配方流通链**，但**没有家户需求链**，
也没有“给既有市场加一行价”的命令。它更像“新播种一个带新商品的孤立经济节点”，不是给运行中的城市经济加新商品。

### 10.2 新产业
- **受支持入口**：`economy.Seed`（唯一 economy handler），每个 entry 代表一格；对经济未占用的格可追加，
  对已有产业/阶层行的格整份拒绝。新产业要真正参与结算，还需要同格的劳动供给/配额、家户行与账户、关系规则。
- **运行期给既有格加新产业**：按受支持语义**不能**；§6.3 的 entry/id 错位旁路可以绕过判重，
  但这是**未申报目标/权限错位**的代码漏洞，且若新 slots 不覆盖既有家户参与率，`EconomyData` 构造期会拒。
- **配方**：一个产业一份配方，没有配方目录/切换命令；新配方只能来自新的 `economy.Seed` 载荷。
  扩容/停业/重开也没有状态入口（`withIndustries` 只是 copy-with，main 无调用；
  `capacityScaleOf` 是只读）。

【用户目标需求】如果目标是“运行中给既有市场/产业动态添加商品/配方/产能”，
当前命令面缺的不是商品类型本身，而是：
1. 一个能改既有格 `markets` 的设价/加价命令；
2. 一个能改既有产业配方/产能/operator 的命令（或允许按格追加到已占用格）；
3. 家庭需求口径的扩展点（`dailyNeedsMilli` / `selfNeedOf` / 消费 / 饿死）；
4. 账户的追加写入口（或者对既有账户格允许新主体）。
——以上是现状缺口，不是设计建议。

---

## 11. 探针记录（/tmp，未进仓库）

- 运行方式：`javac -proc:none -cp simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar ...`，
  然后 `java -cp /tmp/e-probe-out:<shaded jar> ...`。**未重建 jar、未跑 Maven**。
- `Probe`（词表/任意商品/家庭保留/载荷解析）关键输出：

```text
allCommodityIds=[grain, cloth, fiber, tool, iron, wood]
dailyNeedsMilli(100,1)={grain=8333, cloth=273}
priceOf(nectar)=7 priceOf(absent)=0
selfNeedOf(100,nectar,35)=0
selfNeedOf(100,grain,35)=291666
householdLifeReserveOf row(naturalNeeds has nectar)={grain=291666, cloth=9589}
industry.id=orchard@0_0
inputPerUnit={nectar=100}
outputPerUnit={pollen=10}
market.prices={nectar=7, pollen=11}
commodityIds (readout constant)=[grain, cloth, fiber, tool, iron, wood]
```

- `Probe2`（entry 格与产业 id 格不一致 ⇒ 同 id 覆盖）关键输出：

```text
base occupied entry-keys (computed by handler)=[0_0]
seeded entryHexKeys (declared targets)=[5_5]
merged industry farm@0_0 name=farm@0_0
merged industry farm@0_0 outputPerUnit={grain=99}
merged markets keys=[0_0, 5_5]
```

- `Probe3`（entry 格与产业 id 格不一致 ⇒ 新 id 加到已占用格）关键输出：

```text
base industries=[farm@0_0]
seeded industries=[kiln@0_0]
merged industries=[farm@0_0, kiln@0_0]
new industry @ occupied hex exists after merge=true
merged EconomyData accepted, class count=0
```

- 探针代码使用了 `EconomySeedHandler` 的私有 `occupiedHexKeys` / `merge`（反射），
  以及 `MarketSettlement.selfNeedOf` / `householdLifeReserveOf`（反射，因为类是包私有）；
  并手工按 `handle` 的同一形制装配了合并后的 `EconomyData`。完整 handler/CommandBus/revision 路径未跑。

---

## 12. 我没做 / 没验证的

- **没有改任何生产代码**；没有 `git add/commit`；没有跑 Maven；没有跑整年；没有起服务或调 MCP。
- 没有走完整的命令链：`simos.command.submit` → `CommandBus` → `EconomySeedHandler.handle` →
  `EconomyChangeSet` → revision 应用；§6.3 的旁路是用 handler 真实私有方法和手工并表证明的，不是端到端命令。
- 没有实际执行 `actor.Seed` 的按格追加，也没有跑“新产业首次产出 → 自动建账 → 次日参与市场”。
- 没有构造并跑一次含新商品/新产业的日结算、市场轮、跨区运输、收获/分配。
- 没有验证权限与目标声明层是否会拦截/放过 entry 与产业 id 不一致的载荷；
  只确认 `targetPaths` 来自 entry 格（`EconomySeedHandler.java:54-56`）。
- 没有验证 `EconomySeedHandler.merge` 覆盖市场键时是否有其它守卫；只读了 `merge`/`EconomyData` 的代码路径。
- 没有检查 `.claude/worktrees/**` 下的工作副本；本文所有引用都来自当前主工作树。
- 没有复核基线两篇报告里与本文冲突的行号；本文行号以当前 HEAD 为准。
- 没有验证新商品在“只在成员格有价、锚点格无价”时的完整市场/读数行为（只覆盖了订单生成与价表写面）。
