# 只读调查：市场挂单对象 / 支付腿币种 / 区级调控与口岸政策（task-6）

- 日期：2026-10-08
- 角色：只读调查代理（`exch-scout`）。**未改任何生产代码/测试、未跑 Maven、未 commit**。
- 本文独占 `docs/superpowers/reports/2026-10-08-market-exchange-and-port-policy-investigation.md`。
- 同批兄弟报告：`docs/superpowers/reports/2026-10-08-market-zone-and-currency-investigation.md`（task-1，市场区/拓扑/币种）。
  凡与该报告重叠的结论，本文都**回代码自核过**；本文另补了它未列的跨币种求和点与调控可达性证据（见 §6/§4）。
- 纪律：每条结论带 `file:line`；零命中写清检索写法（不接管道、看 `grep` 退出码，见 §11）；不确定写「未核到」。

## 用户 2026-10-08 设计原话（逐字，最高权威）

> 「…发货币的政府的总疆域…默认就是这种货币的市场区…；有了市场区之后，GOV就可以管控关口市场关口…（口岸维护效率，另有人查）；
> **然后这个口岸效率能干什么？必须搭配口岸政策使用，也就是每个行政区可以对其管理的市场区（行政区-本国市场区从属部分）进行口岸管理政策，
> 当地管理口岸的行政家户本身可以承担兑换本币为外币的"购买"行为，同时限制口岸通过的各类货物，限制效率，与管理的市场区内非政府币种兑换的被压制率
> （本地其他家户肯定也有外汇储备嘛，外汇自然也能挂到市场上进行兑换牟利），由口岸行政效率决定**；重叠辖区是不被允许的…；
> 三不管地带的市场行为完全自由，全由市场机制调控」

本报告只回答 task-6 的 8 问；「口岸维护效率从哪来」不在本文范围（用户原话注明"另有人查"）。

---

## 0. 一句话结论

**今天市场上只有商品能挂单、货币只能当"支付腿"，而且支付腿的币种只有买方一个维度**
（`BuySlot.currency` = 买方所在格 `Market.numeraire`，`SellSlot` **根本没有币种字段**，成交路径**零币种相等校验**，
唯一的币种校验在借贷路径 `MarketSettlement.java:1707`）；
⇒ 用户设想的「口岸行政家户把本币兑换成外币 + 外汇挂到市场上兑换牟利」在今天的**订单形状 / 撮合索引 / 支付落账 / 政策承载体**四层上
**都没有落点**：没有汇率（`ExchangeRate` 全仓 0 命中，且 6 处注释明文禁止跨币种求和折算），没有"以货币为标的"的订单与撮合键（全是 `CommodityId`），
没有逐行政区/逐区的政策存储（`MarketRegulation` 是**逐轮瞬态**、一轮**只作用于一个区**、生产路径**恒传默认值** ⇒ 现有全部调控旋钮今天**不可达**），
且全仓已有 **4 处"行为因果"级 + 4 处读数级 + 1 处 1:1 价值** 的跨币种求和点（§6）。
**这是设计级冲突**（新需求直接推翻 M1-A / M1② / D-023 的"不做汇率"，而最新落盘文档仍写"不做汇率"）⇒ 按 §一.8.1 应上报用户裁定，不由实现方静默改。

---

## 1. Q1：市场能挂什么？`SellSlot`/`BuySlot` 逐字段

### 1.1 订单标的类型只有商品

| 类型 | 标的字段 | 币种/工具字段 | file:line |
|---|---|---|---|
| `BuyOrder` | `CommodityId commodity` | `InstrumentId payWith`（支付**工具**，不是币种） | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/market/BuyOrder.java:38-46` |
| `SellOrder` | `CommodityId commodity` | `InstrumentId receiveWith`（收款工具） | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/market/SellOrder.java:35-42` |

- `CurrencyId` **不出现在任何"标的"位置**：价格表键、订单标的、撮合索引键全是 `CommodityId`（`Market.java:46`、`BuyOrder.java:41`、`SellOrder.java:38`）。
- 商品词表 = `grain / cloth / fiber / tool / iron / wood` 六项，**没有"银"这类货币商品**（`simos-util/src/main/java/io/mosire/simos/util/economy/EconomyVocabulary.java:123-131`）
  ⇒ 也不存在"把货币当商品交易"的绕道。
- `payWith` / `receiveWith` 是**死字段**：订单生成处硬编码 `SILVER_SPECIE`（`MarketSettlement.java:250` 常量、`:1344` 卖单、`:1379-1380` 买单），
  且 `BuySlot` 的 `currency` **不读** `order.payWith()`（见 1.2）。

### 1.2 `BuySlot` 逐字段（`MarketSettlement.java:5121-5173`）

```
order           : BuyOrder            // 标的 = CommodityId
buyer           : Participant
hex             : HexCoord
region          : MarketRegion
currency        : CurrencyId          // ★ :5126 —— 唯一的币种位；构造于 :977 = market.numeraire()（买方所在格）
regionId        : String              // D-027 区规范 id（价格口径）
orderIndex      : int                 // 全局下标
remaining / frozenRemaining / baseFrozenMoney / requestedMoney / spentMilli : long
noMoney         : boolean
blocked         : MarketUnfilledReason
```
构造：`buys.add(new BuySlot(order, buyer, hex, region, market.numeraire()))` —— `MarketSettlement.java:977`。

### 1.3 `SellSlot` 逐字段（`MarketSettlement.java:5176-5243`）

```
order           : SellOrder           // 标的 = CommodityId
seller          : Participant
hex             : HexCoord
market          : Market              // ★ :5180 —— 只有整张 Market，没有独立的币种字段
region          : MarketRegion
regionId        : String              // D-027
costEstimate    : ProducerCostBook.Estimate   // S3 卖方成本排序
costTieBreak    : String
orderIndex      : int
remaining / frozenRemaining / baseFrozenGoods : long
capacityBlocked : boolean             // 2026-10-09 承运运力不足
```
构造：`sells.add(new SellSlot(order, seller, hex, market, region, planningRound))` —— `MarketSettlement.java:985`。

**`SellSlot` 没有 `currency`**：卖方的报价币只能从 `sell.market.numeraire()` 间接得到，而**支付路径从不读它**（§2）。

### 1.4 结论

**今天货币不能作为挂单对象被交易。** "货币"在世界里只有三种身份：
① 账户余额的键（`HouseholdInventory.money: Map<CurrencyId,Long>`，`simos-actor/.../model/HouseholdInventory.java:105`）、
② 转移腿的键（`Transfer.money`，`simos-economy-api/.../api/transfer/Transfer.java:90`）、
③ 逐格/逐区计价单位（`Market.numeraire`，`Market.java:46`）。
要在市场上"挂外币"，至少需要新增：标的可表达货币、以货币为键的撮合索引（现有索引键全为 `CommodityId`：`MarketSettlement.java:2338-2341`、`:2537-2560`）、
以及"报价/成交价"的币对语义。

---

## 2. Q2：`executeTrade` 的支付腿用谁的币种？异币会怎样？

### 2.1 逐腿事实（`MarketSettlement.executeTrade`，定义 `:3438-3687`）

| 腿 | 代码 | file:line |
|---|---|---|
| 货款 | `Transfer moneyLeg = ledger.mint(buy.buyer.actor, sell.seller.actor, location, Map.of(), Map.of(buy.currency, payment), MARKET_TRADE)` | `:3556-3563`（币种在 `:3562`） |
| 运费 | `Map.of(buy.currency, charge.amountMilli())` → 承运人 | `:3574-3582`（币种在 `:3581`） |
| 货腿 | `Map.of(commodity, executed)` 卖方 → 买方（无币种） | `:3512-3519` |

- 两条钱腿的币种**都只有 `buy.currency`**，而 `buy.currency` = 买方所在格 `market.numeraire()`（`:977`）。
- 预算与冻结也全按 `buy.currency`：`spendableMoneyOf(..., buy.currency)`（`:2074`、`:3980`、`:4097`、`:4230`）、
  冻结轴键 `…+ buy.currency.value()`（`:1484`、`:1560`）、冻结写入（`:1536`、`:1572`）。

### 2.2 异币场景会怎样？—— **不报错、不折算、不兑换，直接落账**

1. 落账侧按 `CurrencyId` 键直接加钱，**没有币种匹配检查**：
   `for (Map.Entry<CurrencyId, Long> leg : transfer.money().entrySet()) { addMoney(householdMoney, to, leg.getKey(), leg.getValue()); }`
   —— `EconomySettlement.java:7765-7767`（`addMoney`/`setMoney` 见 `:8535-8557`；缺该币种键就新建键）。
2. 撮合侧**没有币种维度**：`matchAcrossRegions` 只判"区是否邻接"（`MarketSettlement.java:3025-3070`，尤其 `:3050-3056`），
   `matchWithinRegions` 按区/商品分组（`:2458+`、`:2529+`），卖方选择按成本序（`:3001-3015`）。
3. 全文件**唯一的币种相等判断**在**借贷**路径：
   `if (candidate.remaining <= 0L || !candidate.currency.equals(buy.currency) || …) { index++; continue; }` —— `MarketSettlement.java:1706-1711`。
   ⇒ **买货不校验、借钱校验**，口径不一致（这一条比兄弟报告的表述更精确：不是"完全没有校验"，而是"只有借贷有"）。
4. 因此若买方格 numeraire = B、卖方格 = A，实际结果 = **卖方收到 B 币**（不兑换、不折算）。
   后果有两层：
   - 卖方拿到的 B 币在**自己本格的市场上花不出去**——预算只读本格 numeraire（`:1352`、`:4494`、`:4537`），
   - 但读口/利润/债务压力可能把它与 A 币**当成同一把尺相加**（§6）⇒ 量纲静默错位。
5. 唯一会"抛"的相邻路径是**透支**（`EconomySettlement.debitHouseholdMoney` 里 `balance < amount` ⇒ `MoneyIssuance.requireIssuerOf`），
   即"钱从哪来"有守卫，而"币种对不对"没有守卫。

### 2.3 作为"兑换地基"的评估

`executeTrade` 提供的是一个**单边币种的支付管道**：它能把商品从 A 换到 B（货腿），把 `buy.currency` 从买方移到卖方/承运人（钱腿）。
**要表达"兑换"，缺的是"同一笔里两种币相向而行"的腿形状**：今天 `Transfer.money` 是**一张表**（一个方向、多币种并列），
`Transfer` 只有 `from`/`to` 两端与一个 `reason`（`TransferReason` 七值里没有兑换，见 §7）。
把"用本币买外币"写成两条 `Transfer`（本币腿一条、外币腿一条）在账面上可表达，但**没有成交价/汇率来源**（§7），
也没有任何撮合器会把两条腿配对（`executeTrade` 只处理"商品 ↔ 买方币种"这一种配对）。

---

## 3. Q3：逐格 `Market.numeraire` 与价格表；第二种货币出现时各读哪个余额

### 3.1 计价口径

- 量纲 = **毫计价货币 / 1 商品单位**（1 商品单位 = 1000 毫商品）：`Market.java:19-25`、`:43-44`；
  货款算式 `⌈数量 × 单价 ÷ 1000⌉` 在 `MarketSettlement.java:3491-3492`。
- **每格唯一的计价货币**，且类注明文写「**不做汇率**……跨币种的兑换在本批**不存在**，币种之间不许求和、也不许折算」——
  `simos-economy/src/main/java/io/mosire/simos/economy/model/Market.java:13-14`、`:43-44`。
- 参考价是**数据不是公式**：`Market.java:16-17`（谁定价是 GM 的事）。2026-10-07 起另有**自适应改价**（`MARKET_ADAPTIVE_PRICING_ENABLED = true`，`MarketSettlement.java:203`、`:1085-1088`），
  只改区锚格的价表并经 `markets` 组件回写（`:1174-1182`）。

### 3.2 各环节读哪个币种余额（逐条给行号）

| 环节 | 读的币种 | file:line |
|---|---|---|
| 逐格计价货币 | 该格 `Market.numeraire` | `Market.java:46`；读口 `ApiViews.java:4343`、`EconomySeeder.java:1521` |
| 挂牌参考价 / 限价 | **本格**价表（调控覆盖优先） | `MarketSettlement.java:1295-1300`、`:1255-1272` |
| 区内成交价 | **区锚格**价表（`anchorMarket`） | `:2542-2545`、`:2713-2718` |
| 跨区成交价 | **卖方格**参考价的上界（`unitPriceOf`） | `:3842-3851`，调用 `:3192` |
| 买单预算（挂单量折算） | **买方本格** `market.numeraire()` 的余额 | `:1352` |
| 买单预算（未成交原因/复核） | 同上 | `:4494`、`:4537` |
| 冻结/解冻 | `buy.currency`（买方本格） | `:1484`、`:1536`、`:1560`、`:1572` |
| 信用出借人候选 | **出借人所在格** `market.numeraire()`，且必须 == 买槽币种 | `:2161`、`:1707` |
| 余额读取原语 | 逐币种按键取 | `:5034-5038`（`moneyOf`）、`:5059-5063`（`spendableMoneyOf`） |
| 债务估值 | 价目表 `numeraire` 不同 ⇒ 拒绝折算（返回缺价 0） | `simos-economy/.../time/DebtValuation.java:528-532`、`:296-301` |
| 债务可动货币 | 逐币种、不跨币种求和（D-023） | `EconomySettlement.java:6645-6647` |
| 区级读口 | 锚格 numeraire / 区 node numeraire | `MarketReadout.java:282`、`:318` |
| 商人工资/欠薪 | 商号 `homeHex` 的 `market.numeraire()`（缺市场退回"第一个币种"） | `MerchantSettlement.java:427-429`、`:793-795` |

### 3.3 结论：**两个"币种权威"，且都不守"成员币种一致"**

- **逐格权威**（持久）：`EconomyData.markets`（第 9 个组件，`EconomyData.java:284-312`）里每格 `Market.numeraire`。
- **逐区权威**（派生、不落盘）：`MarketNode(numeraire, receiveWith)` / `MarketRegion.numeraire()`（`simos-economy-api/.../api/market/MarketNode.java:26-45`、`MarketRegion.java:51-59`），
  其值来自该区锚格的 `Market.numeraire`（`MarketTopology.java:203-215`、`MarketTopologyBook.java:254`、`MarketTopology.java:417-424`）。
- **区成员归属不校验币种**：`MarketTopology.of` 的逐格归属只用 `hex.distanceTo(node.anchor())` 与 `radiusHex`（`MarketTopology.java:369-393`，★ 已逐行自核：该循环里没有 `numeraire` 任何出现）；
  `MarketTopologyBook.addNode` 对非银币种锚格**直接 return**（`MarketTopologyBook.java:246-248`）⇒ 非银城市**进不了节点表**。
- 生产路径还有一个硬闸：`MarketTopologyBook.sameNumeraire`（`:209-219`）+ 分支（`:125-138`）——
  **全格同币 ⇒ 全区一个区**；**币种不一致 ⇒ 退回"城市节点 + tier 半径"路径**，而该路径又丢弃非银锚点。
- ⇒ 一旦某格出现第二种货币，可能出现：该格被邻区吸收（成员币种 ≠ 区币种）⇒ **按 A 币的锚格价、用 B 币支付**（§2.2），
  或退化成"单格区"（`MarketTopology.java:417-424`，此时 node numeraire 才 = 该格币种）。

---

## 4. Q4：现有"限制/调控"手段清单（`MarketRegulation` 及同层手段）

### 4.1 `MarketRegulation` 逐字段与生效行

定义：`simos-economy/src/main/java/io/mosire/simos/economy/time/MarketRegulation.java:50-58`。

| 字段 | 语义 | 生效点（file:line） | 落盘 | GM 工具 |
|---|---|---|---|---|
| `anchor` | 参考价锚格；**只用来把调控绑到一个区** | 区映射 `MarketSettlement.java:5409-5415`；不在任何区 ⇒ 空表 | ✗ | ✗ |
| `referencePrices` | 区级参考价（覆盖各格价表；空 = 沿用） | `regulation.referencePriceOf` `MarketRegulation.java:121-126` → `:5463-5468` → 订单生成 `:1298` | ✗ | ✗ |
| `bidPerMille` / `askPerMille` | 卖方底价 / 买方限价（‰；0 = 沿用 `Market` 常量） | `MarketRegulation.java:142-152`、`:158-169` → 订单限价 `:1299-1300` | ✗ | ✗ |
| `quotaPerWindow` | 商品 → **卖方成交量上限**（毫商品；空 = 无配额；0 = 用尽） | 建表 `:5424-5434`；扣减唯一落点 `:3495`；闸门 `:1732-1739`、`:1779-1784`、`:1805`、`:1904`、`:2972-2974`、`:3276-3279`；用尽 ⇒ 具名 `REGULATION_QUOTA` `:4091-4092` | ✗ | ✗ |
| `tariffPerUnit` | 商品 → 单位税费**读数**（毫计价货币/商品单位） | 读取 `:5476-5479`；生效 `:3615`（逐笔取价）与 `:3633-3636`（**只累计读数，不搬钱、不铸币、不落债**——见 `:3634` 注释） | ✗ | ✗ |
| `open` | `false` = 该区本轮不撮合（返回空报告，不抛） | `:911-914`（排在冻结之前） | ✗ | ✗ |
| `rules` | 只读制度标签（保真序） | **只有 `defined()` 读它**（`MarketRegulation.java:107-115`）；全 `src/main` 无第二个消费点（已在 `MarketSettlement` 检索 `rules()` = 0 命中） | ✗ | ✗ |

### 4.2 三条硬事实（这是本节最重要的结论）

1. **不落盘**：类注明文「纯值类型——不落盘、不进 `EconomyData`/Codec/ChangeSet，本批只作为逐轮瞬态传入 `MarketSettlement.MarketRound`；GM 命令面/落盘是后续批次」
   （`MarketRegulation.java:16-17`）。代码核对：`MarketRegulation` 在 `EconomyData`/`EconomyCodec`/`EconomyChangeSet` 里 **0 命中**（检索见 §11.4）。
2. **一轮只作用于一个区**：`MatchContext` 构造里 `regulationByRegionId = anchorRegionId == null ? Map.of() : Map.of(anchorRegionId, regulation)`
   （`MarketSettlement.java:5416-5422`）⇒ **单实例单区**，天然无法表达"每个行政区各自一套口岸政策"。
3. **生产路径恒传默认值 ⇒ 全部旋钮今天不可达**：唯一的生产构造是
   `MarketRegulation.defaultsFor(markets)`（`EconomySettlement.java:1664`），
   其余全是 `MarketRegulation.none()`（`MarketSettlement.java:379`、`:2878`、`MarketReadout.java:185`）。
   ⇒ 没有任何 GM 命令/工具能传一个非默认 `MarketRegulation` 进来。

### 4.3 与市场有关的 GM 工具/命令面（唯一一个）

- 命令面：`economy.SetMarketPrice`（`simos-economy/.../spi/EconomySetMarketPriceHandler.java:44`）：
  - **只改价**：`markets.put(hex, new Market(existing.numeraire(), prices))` —— `:113`；
  - **新建市场时币种硬编码 silver**：`new Market(MoneyVocabulary.SILVER_CURRENCY, …)` —— `:109`；
  - 拒绝非正价（`:69-86`），落盘走 `EconomyChangeSet`（`:134`）。
- 工具面：通用写入口 `simos.command.submit`（`simos-app/src/main/java/io/mosire/simos/app/tools/write/CommandSubmitTool.java:30-31`，`type + payloadJson`）
  ⇒ 命令**可达**，但 `economy.SetMarketPrice` 的载荷只有 `q/r/commodity/price`，**没有 numeraire、没有调控字段**（`:59-68`）。
- **没有**任何设置 numeraire / 配额 / 关税 / 开闭市 / 制度标签的命令或 GM 工具（`simos.gm.*` 全集见 §11.5；写工具目录无 market/price/tariff 类工具）。

### 4.4 同层手段（不是 `MarketRegulation`，但会被"口岸政策"借用）

| 手段 | 事实 | file:line |
|---|---|---|
| 市场准入（排除家户） | `marketExcludedHouseholds` 逐轮瞬态集合；被排除的家户**不生成任何买单/卖单** | 构造 `EconomySettlement.java:768`、`:1668`；闸门 `MarketSettlement.java:1307-1310`、`:4658`、`:4759`；两层防线注释 `:1305-1306` |
| 单 hex 贸易成本 | 只表达**实物损耗**（`2‰ × 距离 × moveCost`，上限 500‰），货币侧恒 0 | `HexTradeCost.java:16-17`、`:44-50`（三常量 `:44`/`:47`/`:50`） |
| 承运运力 | 逐商号/路线运力不足 ⇒ 具名 `LOGISTICS_CAPACITY` | `MarketSettlement.java:3457-3489`、`:3689-3701`；reason 枚举 `simos-economy-api/.../market/MarketUnfilledReason.java:48` |
| 自适应调价 | 开关 + α=50‰ + 下限 0，只改区锚格价 | `MarketSettlement.java:203`、`:210`、`:1075-1090`、`:1174-1182` |
| 借贷币种闸 | 出借人币种必须 == 买槽币种 | `:1706-1711` |

⇒ 若要复用"限制口岸通过的各类货物"，**现有最接近的形状是 `quotaPerWindow`（商品 → 量上限）**；
若要"禁运/只许某几类通过"，今天的 `MarketRegulation` **没有集合型（白/黑名单）字段**——只有数值型两个（quota/tariff）与标量两个（bid/ask）加一个布尔（open）。

---

## 5. Q5：「压制率/禁止/效率限制」今天的承载体（只列候选落点，不设计）

### 5.1 今天能"装一个数"的地方（逐个给形状与粒度）

| 候选落点 | 形态 | 粒度 | 持久 | 缺口 |
|---|---|---|---|---|
| `MarketRegulation` | 逐轮瞬态 record（10 字段） | **一轮 × 一个区** | ✗ | 数量维（多区）与持久维都不够；`anchor` 只能绑一个区 |
| `Market`（第 9 个 `EconomyData` 组件） | 逐格持久 record（`numeraire` + `prices`） | **逐格** | ✓（ChangeSet/Codec/往返齐备） | 与"按行政区管理市场区"的粒度不符；加字段须同步 ChangeSet+Codec+往返（铁律 5） |
| `Government`（`EconomyData` 组件） | `id/nationRef/treasury/issuable/seignioragePerCycle` | 逐政府 | ✓ | 没有"管辖范围（哪些区）"字段 |
| `EconomyData` 新组件 | 需新增第 32 个状态组件 | 任意（自己定键） | ✓（但要造 ChangeSet/Codec/往返） | 无现成形状；`EconomyData` 现有 31 个组件里**没有 region/政策类组件**（`EconomyData.java:284-312`） |
| `MarketRegion`/`MarketNode`（api，派生） | 每轮从城市+tier 现算 | 逐区 | ✗ | 不落盘、不校验成员币种（§3.3） |
| map 的 `Region`（行政区） | map 侧有 `region` 包与改名 handler | 逐行政区 | map 侧 | **"行政区 → 市场区从属部分"的映射我没核到**（见 §10） |

### 5.2 三种政策参数不是同一种形状（决定它们不能都塞进同一个字段）

- **「效率」**（用户原话"限制效率…由口岸行政效率决定"）= 标量乘子（‰），作用于**通过量或兑换量**；
- **「压制率」**（"非政府币种兑换的被压制率"）= 标量（‰），作用于**某个币种的兑换/挂单被接受的概率或额度**；
- **「禁止/限制通过的货物」** = **集合**（商品清单，白名单或黑名单）。
  ⇒ 今天 `MarketRegulation` 里**只有** `quotaPerWindow`/`tariffPerUnit` 是"商品 → 数"的映射，**没有商品集合字段**。

### 5.3 现有代码里"效率→通过量"最接近的三个位（供后续设计定位，不构成设计）

1. 订单生成口径：`ordersFor` 的可卖量/需求量算式（`MarketSettlement.java:1331-1380`）；
2. 撮合前的闸：`open`（`:911`）与配额的 `min(demand, supply, quotaLeft)`（`:2972-2973`、`:3276-3278`）；
3. 逐笔计量位（唯一扣减点）：`ctx.consumeQuota(sell.region, commodity, executed)`（`:3495`）与税费读数（`:3615`、`:3633-3636`）。

---

## 6. Q6：家户持多币种在类型上成立吗？跨币种求和点全表

### 6.1 类型上成立（逐条证据）

- `HouseholdInventory(HouseholdAccountKey key, Map<CommodityId,Long> balances, Map<CurrencyId,Long> money, Map<CommodityId,Long> frozenBalances, Map<CurrencyId,Long> frozenMoney)`
  —— `simos-actor/src/main/java/io/mosire/simos/actor/model/HouseholdInventory.java:102-107`；
  逐币种**非负、0 保留、冻结 ≤ 余额**的构造期校验见 `:144-183`、`:210-235`。
- `Transfer.money` 按币种，且类注明文写「**跨币种求和是没有意义的运算**」——`simos-economy-api/.../transfer/Transfer.java:31`、`:90`。
- 会话/快照/落账全部逐币种：`AccountSnapshot.java:39-41`、`AccountDelta.java:36-115`、`EconomySettlement.java:7765-7767`（加钱）、`:8535-8557`（钱包读写）。
- 迁移/清偿/发行也逐币种：`ModeMigrationSettlement.java:1127-1170`（逐币种按人口比例 floor）、`GovernmentSeigniorage.java:101-102`、`GovernmentDebtIssuance.java:88`。
⇒ **结论：一个家户同时持有银、金、国币在类型上完全成立**；`1 毫银 + 1 毫金` 就是两个键。

### 6.2 跨币种求和点（**全表**；按"是否进入行为因果"分档）

A 档 = **进入行为因果**（不同币种被当成同一把尺参与判断/落账）：

| # | 落点 | 代码 | 性质 |
|---|---|---|---|
| A1 | 货款腿 | `Map.of(buy.currency, payment)` — `MarketSettlement.java:3562` | 真·跨币种支付**落账**（无校验、无折算） |
| A2 | 运费腿 | `Map.of(buy.currency, charge.amountMilli())` — `:3581` | 同上（承运人收款） |
| A3 | 债务压力判据 | `OperatorSettlement.cashOf` 把钱包**全币种**相加（`:497-507`）→ `available`（`:254-258`）→ `if (due > available) debtStress = true;`（`:260`） | **行为因果**：决定 `debtStressCycles`/行业状态迁移（`:262-266`）。★ 兄弟报告未列此点 |
| A4 | 商号运费实收 | `MerchantSettlement.feeRevenueOf` 把 `transfer.money().values()` 累加（`:341-357`）→ `revenue`（`:412`）→ 商号工资/欠薪折算 | **行为因果**（进入商号雇工结算） |
| A5 | 政府预算帽/下限 | `GovBudgetExecutionBridge.ResourceVector.value()` = `grain + cloth + silver` **1:1**（`:1008-1010`）→ `capEffective`（`:260-265`）、分配 `left`（`:301-311`）、缺口 evidence（`:443-462`） | **行为因果**：三类资源腿共用同一个标量预算；类注明文承认 1:1（`:70`） |

B 档 = **读数/对账/守卫**（不直接改账，但会污染读数或掩盖问题）：

| # | 落点 | 代码 | 性质 |
|---|---|---|---|
| B1 | 产业利润 | `EnterpriseProfitBook.addRevenue/addCost` 把 `money.values()` 全币种相加成一个 long（`:662-676`、`:677-689`；注释自认「跨币种求和的量纲缺口见收口报告」`:671`），`numeraire` 参数**被忽略** | 读口/对账（D-024 后不再是迁移候选门槛，`ModeMigrationPolicy.java:53-55`），但 `net` 仍汇总进 `Book`/`ModeHex`（`:571-594`） |
| B2 | DEBUG 追踪 | `EconomySettlement.traceTotalMoney`（`:500-508`） | 仅日志 |
| B3 | 逐 reason 汇总 | `EconomySettlement.java:2793-2800`（`transfer.money().values()` 相加） | 读口/诊断 |
| B4 | 退出留存读数 | `EconomySettlement.java:6076-6082`（`keptMoneyMilli`） | 读数 |
| B5 | 迁移前"残余必须为 0"守卫 | `ModeMigrationSettlement.java:605`（全币种求和判 `!= 0`） | 守卫（零判据，量纲危害小） |

C 档 = **逐币种正确**（作为对照，说明本仓并非到处串币）：

- `EconomySeeder.genesisEndowmentOf` 逐币种 merge（`EconomySeeder.java:1688-1698`）；
- `TestConditions.report()` 逐币种 merge（`TestConditions.java:185-192`）；
- `EconomySettlement.availableMoneyOf` 逐币种（`:7090-7105`）→ `DebtValuation.choosePayment` 按 `DebtUnit` 区分（`DebtValuation.java:449-465`）；
- `ApiViews.moneyTotals` / `moneyLayers` 逐币种（`ApiViews.java:4153-4159`、`:4116-4148`）；
- `EconomySettlement.spendableMoney` 逐币种且明文"不跨币种求和/折换（D-023：不做 FX）"（`:6645-6647`）；
- `DebtValuation.Pricing.priceOf` 两个价目表 numeraire 不同 ⇒ 返回缺价 0（`:528-532`）；
- `EconomyLiquidationSettlement.java:79`：货币/其它商品债**需要汇率，没有就不折**。

**⇒ 汇率一旦落地，A 档 5 处必须同时改**（否则第一笔跨币种成交就会污染债务压力/利润/运费实收/预算帽）。

---

## 7. Q7：「兑换」所需但今天完全不存在的东西（清单）

| # | 缺什么 | 现状证据（零命中写法见 §11） |
|---|---|---|
| 1 | **汇率/比价的任何类型** | `ExchangeRate` 全仓 java 源 **0 命中**（`grep_rc=1`）；`汇率` 7 命中**全是"没有汇率"的声明**（`MoneyVocabulary.java:36`、`CurrencyDef.java:24`、`CurrencyId.java:20`、`InstrumentId.java:17`、`Market.java:14`、`EconomyLiquidationSettlement.java:79`、`GovBudgetExecutionBridge.java:70`） |
| 2 | **以货币为标的的订单/撮合** | `BuyOrder`/`SellOrder` 标的 = `CommodityId`；索引键 = `CommodityId`（§1） |
| 3 | **报价/币对（pair）/价差/成交价** | 无任何 pair 类型；价格表只有 `CommodityId → 单价`（`Market.java:46`） |
| 4 | **兑换的流水原因/发行类别** | `TransferReason` 七值：`PRODUCTION_OUTPUT / RELATION_PAYMENT / INPUT_REQUISITION / LOAN_PRINCIPAL / LOAN_REPAYMENT / MARKET_TRADE / CARRIER_FEE`（`simos-economy-api/.../transfer/TransferReason.java:31-54`）；`MoneyIssuanceKind` 三值：`INITIAL_ENDOWMENT / FISCAL_ISSUE / WITHDRAWAL`（`.../api/money/MoneyIssuanceKind.java:15-17`）——**没有 FX/兑换/口岸费** |
| 5 | **工具维余额**（银币 vs 国币 vs 存款） | 余额键是 `CurrencyId`（`HouseholdInventory.java:105`），不是 `InstrumentId` ⇒ 同一币种的两种工具在账上**不可区分**；`MoneyVocabulary` 只登记 `silver-specie` 一种（`MoneyVocabulary.java:71-98`）；订单的 `payWith/receiveWith` 构造处硬编码（`MarketSettlement.java:1344`、`:1379-1380`） |
| 6 | **逐行政区/逐区的政策存储与 GM 工具** | `MarketRegulation` 瞬态 + 单区 + 生产恒默认（§4）；唯一市场命令只改价、不改币种/政策（§4.3） |
| 7 | **"谁可以持外币/谁可以兑"的资格语义** | 无；唯一准入机制是"家户是否生成订单"的排除集（§4.4） |
| 8 | **"效率 → 通过量/兑换量"的作用点** | 无乘子位；最接近的是配额上限与承运运力（§5.3） |
| 9 | **"外汇储备"口径的读口/审计** | 逐币种余额可存（§6.1），但没有"外汇"分类口径；`外汇/外币/foreignCurrency/fxRate` 在 java main 里 8 命中**全是"不做 FX"的声明**（`ModeMigrationSettlement.java:71/481/1149`、`ProductionLedger.java:443`、`DebtValuation.java:64`、`ModeMigrationPolicy.java:124/1217`、`EconomySettlement.java:6645`） |
| 10 | **兑换对手方（"当地管理口岸的行政家户"）的权威数据** | `ActorKind` 有 `GOVERNMENT`/`ORGANIZATION` 等档，但"哪个家户管哪个口岸"没有任何存储（**未核到**，见 §10） |
| 11 | **口岸/关口这一概念本身** | java main 检索：`口岸|关口|customs` = **0 命中**；`\bport\b` 的 30 处**全是 HTTP 端口**（`GuiServer.java:542-573` 等）；`tariff` 106 处全是 `MarketRegulation.tariffPerUnit`/`TransportTariff`（运费费率） |
| 12 | **"三不管地带 = 完全自由"的可表达状态** | `MarketRegulation.none()`（`MarketRegulation.java:102-104`）语义上可用作"无政策"，但**"哪个区归谁管"这一维不存在**，因此无法与"有政策区"区分 |

---

## 8. Q8：缺口三档

### A 档：有代码、可用（可直接复用的地基）

1. **逐币种账户与落账**：`HouseholdInventory.money/frozenMoney`（`HouseholdInventory.java:105-107`）、`Transfer.money`（`Transfer.java:90`）、
   逐币种冻结/解冻与唯一写口 `applyTransfer`（`EconomySettlement.java:7765-7767`、`MarketSettlement.java:3512-3591`）；
   **发行 fail-closed 守卫**（`MoneyIssuance.requireIssuerOf`，透支时抛；`EconomySettlement.debitHouseholdMoney`）。
2. **逐格计价与价表**：`Market(numeraire, prices)` + bid/ask 两个独立常量 + 0 价/未定价的区分（`Market.java:46`、`:104-145`、`:81-89`）；自适应改价已跑（`:203`）。
3. **区级调控的"形状"与生效点**：参考价/限价/配额/开闭市/税费读数五件套 + 逐条生效行（§4.1）。
4. **单区映射**：`anchor → region` 映射与 `regulationFor`（`MarketSettlement.java:5409-5422`、`:5457-5459`）。
5. **市场准入排除集**：`marketExcludedHouseholds` 两层防线（`MarketSettlement.java:1305-1310`、`:4658`、`:4759`）。
6. **订单契约里的工具维字段**：`Budget.instrument` / `BuyOrder.payWith` / `SellOrder.receiveWith`（`BuyOrder.java:45-46`、`:75-80`）——**形状在、接线不在**。
7. **已有的"不折算"正确范式**：`DebtValuation.java:528-532`、`EconomySettlement.java:6645`——可作为新汇率接口的对照口径。

### B 档：半成品 / 未接线 / 未守

| # | 半成品 | 事实 |
|---|---|---|
| B1 | `MarketRegulation` **全部旋钮今天不可达** | 生产恒 `defaultsFor`（`EconomySettlement.java:1664`）⇒ 无 GM 路径注入非默认值 |
| B2 | `MarketRegulation` **不落盘** | 瞬态 record，`EconomyData`/Codec/ChangeSet 0 引用（`MarketRegulation.java:16-17`） |
| B3 | **单区限制** | `Map.of(anchorRegionId, regulation)` 单实例单区（`MarketSettlement.java:5416-5422`） |
| B4 | **税费只记读数不搬钱** | `:3633-3636`（注释 `:3634`） |
| B5 | `rules` 是**死标签** | 只有 `defined()` 读（`MarketRegulation.java:113-114`），无第二个消费点 |
| B6 | `payWith`/`receiveWith`/`Budget.instrument` **死字段** | 构造处硬编码 `SILVER_SPECIE`（`MarketSettlement.java:250`、`:1344`、`:1379-1380`）；`SellSlot` 无币种 |
| B7 | **跨币种支付路径已在但无校验**（静默） | `:3562`、`:3581`；唯一校验在借贷 `:1707` |
| B8 | 逐格 numeraire **只能创世指定** | `economy.Seed` 载荷 `numeraire`（解析 `:640-642`；`markets` 解析段的范围 `:583-645` 引自 task-1 报告，我自核的是 `:640-642`）；`SetMarketPrice` 新建市场硬编码 silver（`:109`）、改价保留原币种（`:113`） |
| B9 | `MarketNode.numeraire/receiveWith` 是**派生件**且非银锚点被丢弃 | `MarketTopologyBook.java:246-254`、`MarketTopology.java:417-424` |
| B10 | 跨币种求和的 **5 处 A 档 + 5 处 B 档** | §6.2 |

### C 档：完全没有

1. 汇率/比价（任何形态）与报价、币对撮合、兑换成交账（§7-1/2/3/4）。
2. **工具维余额**（`InstrumentId` 不进账户键；§7-5）。
3. **口岸/关口概念**（java main `口岸|关口|customs` = 0 命中；§7-11）。
4. **逐行政区 × 市场区的政策存储**（效率/压制率/禁运清单）与 GM 工具（§7-6、§5）。
5. **"效率 → 通过量/兑换量"的乘子位**（§7-8）。
6. **外汇持仓口径读口与审计**（§7-9）。
7. **"行政区 → 市场区从属部分"的映射权威**（我**未核到**；§10）。
8. **走私/黑市/三不管地带**对应的"无政策 + 自由市场"表达（§7-12）。

---

## 9. 矛盾点（本节是交账重点）

### C1（结构冲突，最硬）「外汇挂到市场上」与现有订单/撮合模型不兼容

- 市场标的、价格表键、撮合索引键**全是 `CommodityId`**（`BuyOrder.java:41`、`Market.java:46`、`MarketSettlement.java:2338-2341`）；
  货币只在"余额键/转移腿键/计价单位"三个位置（§1.4）。
- ⇒ 「外汇也能挂到市场上进行兑换牟利」要求**新增一个可挂单的标的维度**（货币或币对），
  且要求撮合器能对"两种币相向"配对；今天 `executeTrade` 只会把"商品 ←→ `buy.currency`"配对（`:3512-3591`）。
- 这不是给 `MarketRegulation` 加字段能解决的：**订单形状、撮合键、成交腿形状三处都要动**。

### C2（设计级冲突，需用户裁定）「兑换本币为外币」直接推翻现行"不做汇率"

- 用户原话要求"兑换本币为外币"与"非政府币种兑换的被压制率"；
- 代码与文档一侧是**明文禁止**：`Market.java:13-14`（币种之间不许求和、不许折算）、
  `MoneyVocabulary.java:36`（「本批也没有汇率……裁定 M1-A」）、`CurrencyDef.java:24`、`CurrencyId.java:20`、`InstrumentId.java:17`、
  `EconomySettlement.java:6645`（D-023：不做 FX）、`DebtValuation.java:64`/`:528-532`、`EconomyLiquidationSettlement.java:79`（需要汇率、没有就不折）、
  `ModeMigrationSettlement.java:71/481/1149`、`ProductionLedger.java:443`、`ModeMigrationPolicy.java:124/1217`；
- 且**最新落盘文档仍写不做汇率**：`docs/superpowers/plans/2026-10-08-small-world-gov-mint-plan.md:23`「不做纸币、信用货币、银行券、汇率；后期中世纪晚期再开」；
  设计文档 `docs/superpowers/specs/2026-09-27-regional-market-design.md:181`「**不做汇率 / 跨币种折算**（裁定 M1②…）」。
- ⇒ 按 AGENTS §一.8.1 / §四.1：**用户最新原话优先于旧裁定与旧文档**，但这是"设计与用户原话冲突/旧裁定待更新"，
  **必须上报用户裁定**，不得由实现方静默改口径，也不得拿旧文档否定用户新设计。**这是本任务要报给 Lead 的第一件事。**

### C3 支付腿只有买方币种 ⇒ "卖方只收本币/按汇率结算"在类型上无法表达，且当前是**静默**的

- `SellSlot` 无币种字段（`:5176-5243`）；两条钱腿只用 `buy.currency`（`:3562`、`:3581`）；零校验（唯一校验在借贷 `:1707`）。
- 后果可复现地分两种：① 卖方收到外币、在本格市场**花不出去**（预算只读本格 numeraire `:1352`/`:4494`/`:4537`）；
  ② 该外币在 A 档求和点被当本币参与判断（§6.2）。
- 也就是说：**今天"串币"不抛错、不记缺口、直接落账**——这与本仓"坏数据 fail-closed、静默 0 不许有"的纪律相反。

### C4 跨币种求和点必须先修，否则"外汇"一进世界就污染四类判断

- A1/A2（`MarketSettlement.java:3562`/`:3581`）落账；A3（`OperatorSettlement.java:497-507` → `:260`）债务压力；
  A4（`MerchantSettlement.java:341-357` → `:412`）商号运费实收；A5（`GovBudgetExecutionBridge.java:1008-1010`）预算帽 1:1。
- B 档（`EnterpriseProfitBook.java:672/686` 等）至少污染读数与对账。

### C5 现有政策承载体在"数量"和"持久性"两维都不够

- 数量：一轮只作用于一个区（`MarketSettlement.java:5416-5422`）；用户要"**每个行政区**各自的口岸政策"。
- 持久性：不落盘（`MarketRegulation.java:16-17`）⇒ 今天连"一个区的政策"都留不住，更谈不上"政策生效随时间/效率变化"。

### C6 「效率限制」与现有手段形状不匹配

- 现有可用手段：数值上限（配额 = 商品量上限，`MarketRegulation.java:41`）与运力（`LOGISTICS_CAPACITY`）；
- 用户要的是**效率乘子**（作用在通过量/兑换量上）与**压制率**（作用在非政府币种兑换上）——两者今天都**没有字段**，
  且"禁止某几类货物通过"需要**集合型**字段（今天没有）。

### C7 「三不管地带完全自由」缺"管辖归属"这一维

- `MarketRegulation.none()` 能表达"本轮没有调控"（`:102-104`），但政策与市场区之间**没有归属关系数据**，
  所以"有政策区 / 三不管区"在数据上不可区分；且用户明确"重叠辖区不被允许"——这条不变量今天**无处可守**。

### C8 文档 vs 代码漂移（按 §四纪律如实记，仅作留痕）

- `docs/superpowers/specs/2026-09-27-regional-market-design.md:181` 的"明确不做"写着「**不做订单簿 / 逐笔挂单 / 锁余额**（裁定 D2b：市场 = 一组发出转移的规则，不是撮合引擎）」；
- 而今天的代码**有**挂单槽（`BuySlot`/`SellSlot`）、**有**冻结（`commitFreezes` `MarketSettlement.java:1468-1532`、`frozenRemaining`、`baseFrozenMoney`）与撮合索引（`:2338-2341`）。
- ⇒ 该"明确不做"已被后续实现推翻（后续文档/代码为准）；提这一条只是提醒：**口岸/兑换的设计书不要引用 2026-09-27 那份的"边界"节当现行约束**。

---

## 10. 我没核到的（如实记）

1. **行政区（province/region）的权威与"行政区 → 市场区从属部分"的映射**：我只核到 map 侧有 `simos-map/src/main/java/io/mosire/simos/map/region/` 包与 `RenameRegionHandler`、
   app 侧有 `ProvinceApplyPlan`/`ProvinceAssignCitiesPlan`/`MapRenameRegionTool` 等工具名，**没有逐行核**"行政区是否已能映射到市场区成员"。
   该题目属 task-1/行政区调查的 owner（`docs/superpowers/reports/2026-10-08-market-zone-and-currency-investigation.md` §Q2、`2026-10-08-admin-region-and-extraction-investigation.md`）。
2. **"口岸维护效率"的来源与任何 port/关口代码落点**：用户原话注明"另有人查"，我未追。
   （我核到的只是：java main 里 `口岸|关口|customs` = 0 命中，`\bport\b` 30 处全是 HTTP 端口、`tariff` 全是税费/运费费率。）
3. **`MarketTopology.of` 逐格归属**：初稿按 task-1 结论引用，**已补自核**——`MarketTopology.java:369-393` 的归属循环只有 `hex.distanceTo(node.anchor())` 与 `radiusHex`，整个循环里没有 `numeraire`（§3.3 已按自核结果改写）。
4. **GUI/读口对多币种的完整行为**：只核了 `ApiViews.moneyTotals`（`:4153-4159`）、`moneyLayers`（`:4116-4148`）、
   `MarketReadout.java:282`/`:318`，未全量核 `ApiViews` 的市场面板。
5. **`MoneyStock`（逐币种流通量读口）**：我只注意到它在 `EnterpriseProfitBook` 之外的引用情况**未核**（task-1 报告称其全仓 0 引用，我未复核该命令）。
6. **`EnterpriseProfitBook` 的读数是否真的完全不进入行为**：我核到它经 `EconomySettlement.java:2626-2627` 产出 `Book` 供 `ModeMigrationPolicy`（`:252`，D-024 已不再是候选门槛），
   但**未逐条核** `Book` 的全部下游消费点 ⇒ 我把 B1 定为"读口/对账"而非 A 档，若有反例请以代码为准。
7. **运行时验证**：本任务规定只读 ⇒ **未跑任何 Maven/测试/世界**；所有结论均为静态阅读所得，未做行为复现。
8. **`MarketReport` / `MarketReadout` 的全部字段**：只核了本文引用到的行，未做全量清点。

---

## 11. 附：关键 file:line 速查 + 检索命令原文

### 11.1 最关键的 10 行

| 事实 | file:line |
|---|---|
| 订单标的只有商品 | `simos-economy-api/.../market/BuyOrder.java:41`、`SellOrder.java:38` |
| 买槽有币种 | `MarketSettlement.java:5126`（构造 `:977`） |
| **卖槽没有币种** | `MarketSettlement.java:5176-5243` |
| 货款腿 = 买方币种 | `MarketSettlement.java:3562` |
| 运费腿 = 买方币种 | `MarketSettlement.java:3581` |
| 唯一的币种相等校验（借贷） | `MarketSettlement.java:1707` |
| 每格计价 + 明文不做汇率 | `Market.java:46`、`:13-14` |
| 跨区撮合不判币种 | `MarketSettlement.java:3025-3070` |
| 生产恒传默认调控 | `EconomySettlement.java:1664` |
| 1:1 价值求和（grain+cloth+silver） | `GovBudgetExecutionBridge.java:1008-1010` |

### 11.2 关键检索命令（本文用到的、可复核）

```bash
# 汇率零命中（★ 不接管道，直接看 grep 退出码；1 = 无命中）
grep -rn "ExchangeRate" --include=*.java . ; echo "grep_rc=$?"     # => 1，0 命中
find . -name "*ExchangeRate*" -print                                # => 空

# 「汇率」只有"没有汇率"的声明（7 命中）
grep -rn "汇率" --include=*.java simos-economy/src/main simos-app/src/main simos-economy-api/src/main

# 外汇/外币/不做 FX（8 命中，全是声明）
grep -rn "外汇\|外币\|foreignCurrency\|fxRate\|FX" --include=*.java */src/main

# 口岸/关口/customs 零命中；\bport\b 全是 HTTP 端口；tariff 全是税费/运费
grep -rniE "口岸|关口|\bport\b|customs|tariff" --include=*.java */src/main
grep -ciE "口岸|关口|customs" /tmp/port.txt                          # => 0

# 跨币种求和点（本文 A/B 档来源）
grep -rn "values())" --include=*.java */src/main | grep -iE "money|currency|wallet|balance|cash|silver"
grep -rn -B3 "for (long .*: .*\.money()\.values()" --include=*.java */src/main
```

### 11.3 关键常量/枚举速查

| 名称 | 值/形状 | file:line |
|---|---|---|
| `MarketRegulation` 字段表 | `(anchor, referencePrices, bidPerMille, askPerMille, quotaPerWindow, tariffPerUnit, open, rules)` | `MarketRegulation.java:50-58` |
| `MarketRegulation.none()` | 占位锚格 (0,0)、`defined()=false` | `MarketRegulation.java:61`、`:102-115` |
| `TransferReason` 七值 | 无 FX/口岸 | `TransferReason.java:31-54` |
| `MoneyIssuanceKind` 三值 | 无 FX | `MoneyIssuanceKind.java:15-17` |
| `MarketUnfilledReason.REGULATION_QUOTA` | 配额用尽的具名原因 | `MarketUnfilledReason.java:98` |
| 商品词表 | grain/cloth/fiber/tool/iron/wood（无货币商品） | `EconomyVocabulary.java:123-131` |
| 货币词表 | 只有 `silver` + `silver-specie` | `MoneyVocabulary.java:41-98` |
| `EconomyData` 组件 | 31 个，无 region/政策组件 | `EconomyData.java:284-312` |

### 11.4 "不落盘"的核对写法

```bash
grep -rn "MarketRegulation" --include=*.java simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java \
  simos-economy/src/main/java/io/mosire/simos/economy/codec/EconomyCodec.java ; echo "rc=$?"   # => rc=1（0 命中）
```

### 11.5 `simos.gm.*` 全集（确认无市场/口岸类工具）

```
simos.gm.adjustPopulation / approvals / approve / armyPayPolicy /
mergedPlan.apply / mergedPlan.upsert / packet / packet.decide / packet.execute /
packets / periodicAdjustment / vitalRates
```

市场侧唯一相关命令 = `economy.SetMarketPrice`（通用入口 `simos.command.submit`，`CommandSubmitTool.java:30-31`）。
