# 经济系统现状调查报告（调查单 1–8 题）

> **性质**：**只读调查报告**。只回答"现状是什么、代码在哪、代表性运行的数据是多少"，**不含任何设计方案**。
> **代码态**：`ts/m1` @ `c8520f34`（派单写的是 `5b6b2be7`；其后只多 2 篇 docs，**生产代码逐字节同源**；本文所有 `文件:行` 按当前工作树）。
> **代表性运行**：M2 一年期批次（tick 180/360）——`m2t180.json` / `m2t360.json` / `store-m2`（rev 11 = tick 360）；
> 调查期新增的只读实验：JFR + 同包探针（360→390、330→360）、双 JVM 重跑（360→390 ×2）、tick 360 状态上的干跑撮合。
> **证据草稿**（细节、逐条命令与"没做/没验证"以草稿为准）：
> `.superpowers/sdd/2026-09-28-investigation/{A-timing-counts,B-determinism-bidask,C-silver-cloth,D-code-reading}.md`
> **关联文档**：流程/SPI 报告 `docs/superpowers/reports/2026-09-28-economy-system-flow-report.md`；
> 一年期读数报告 `docs/superpowers/reviews/2026-09-28-m2-one-year-run-report.md`（市场总量已按全局 `regionId` 更正）。

---

## 1. 一年推进的耗时构成与规模计数

### 1.1 一年推进的实测墙钟（tick 0→360）

| 段 | 墙钟 | 证据 |
|---|---|---|
| worldgen（三国 3/3） | 4 s | `m2sim-batch.log` 01:28:13→01:28:17 |
| **0→180（一次推进）** | **296.1 s（4:56.20）** | 日志命令输出 |
| 180→210 / →240 / →270 / →300 / →330 / →360（六段，每 30 天） | 52 / 101 / 160 / 112 / 114 / **141**（journal）或 151（旧报告，差 10 s 未对账） | journal 相邻 advance 时间戳 |
| **一年推进合计** | **976–986 s ≈ 16.3–16.4 min** | 上表加总 |
| 每 tick dump | m2t180 ≈ 6 s；m2t360 ≈ 10 s | 日志时间戳 |
| 整批（起服务→360 落盘） | ≈ 21 min 39 s（含 01:37:05 被杀后重启） | 日志 |

> 口径：0→180 = 1.64 s/天；后 180 天 = 3.78 s/天（状态随在途/债务/订单变大，且后半年低粮追加轮更多）。

### 1.2 30 天窗口（360→390）的耗时前五项（同包探针直接墙钟，非采样）

| # | 项 | 墙钟 | 占比 | 证据 |
|---|---|---:|---:|---|
| 1 | **区域市场轮** `MarketSettlement.clearOncePerCycle`（订单生成→冻结→区内/跨区撮合→成交/在途） | **126.03 s**（12 轮，均 10.50 s） | **74.3%** | `MarketSettlement.java:363/435`；开市日 363,365,368,370,373,375,378,380,383,385,388,390 |
| 2 | **`OwnershipBooks.apply`**（每日把 fold 出的条目落账） | **29.15 s**（0.97 s/天） | 17.2% | `OwnershipBooks.java:101`；每日 1,088–1,140 条 |
| 3 | `settleOneDay` 每日末态 `EconomyData` 构造/校验 + 索引辅助 | ≈15–20 s（JFR 10.1%） | ≈9–11% | `EconomyData.java:190/249/422`、`EconomySettlement.java:2255-2360` |
| 4 | 推进前 Replay 载入/重放（11 条 revision，`changeset_json` 合计 84.6M 字符） | 5.1 s（热）/6.1 s（冷） | 3.0% | `AReplay` 探针 |
| 5 | 非市场日的其余阶段（消费/投入/劳动/借粮/到货/人口回写） | ≈3–7 s | 2–4% | JFR 各单项 ≤0.6% |

**JFR 对第 1 项的细分**：`matchAcrossRegions` 31.95% + **`GameMap.terrainIndex()` 每轮重建 59,223 条目 22.66%** + `refreshSellFrozen` 7.65%（其余为区内撮合/订单生成/落账）。
**窗口 B（330→360，含关账日）补充**：`step` 87.82 s + `apply` 28.87 s；其中 **day 360 单日 `step` 13.21 s、`apply` 17.49 s（fold 条目 21,721，是平常日的 20–80 倍）** ⇒ 三个关账日的 `apply` 各约 17.5 s（约 50 s/年）。

### 1.3 `settleOneDay` 各阶段与其余链路的分开计时（360→390；JFR 样本占比 / 直接墙钟）

| 阶段 | 代码位置 | 样本占比 | 直接墙钟/备注 |
|---|---|---:|---|
| 0b 到货 | `EconomySettlement.java:1060-1106` | 0 | 窗口内在途 669 批陆续到，但**采样 0 ⇒ 只能判"很轻"，不能判"没发生"** |
| 1 投入计提（周期第一天） | `:1507/1631/1717/1820` | 0.04% | 很轻 |
| 2 消费 `consumeOwnStock` | `:2423` | 0.07% | |
| 3 收获/契约分账 | `:3135` + `ProductionSettlement` | 0.06% | 窗口内无关账日 ⇒ **0 样本不等于没成本** |
| 4 劳动再分配 `reallocateLabor` | `:2840` | 0.57% | 含饿死缩编 `scaleLaborOfGroup` |
| 4 区域市场 | `MarketSettlement.java:332/435` | **66.95%** | **126.03 s**（12 开市日） |
| 4b 借粮 / 4c 还债 / 4d 饿死 / 5 计息 | `:2507/2752/3804/3864` | 0（还债/计息只看关账日） | 窗口内无这些事件 |
| 人口回写 | `:3672` 邻域 | 0.31% | day 390 月末一次 |
| 每日末态构造/校验 | `EconomyData.java:190/249/422` | 7.99% | ≈12–20 s |
| 每日索引辅助 | `EconomySettlement.java:2255-2360` | 2.11% | ≈3–6 s |
| 产权落账（step 之后） | `OwnershipBooks.java:101` 等 | fold 0；apply 17.98%；land* 0.30% | apply 29.15 s；land* 0.35 s |

**其余链路**：`EconomyChangeSet.between` **15–16 ms**（`EconomyChangeSet.java:111-138`）；`ActorChangeSet.between` 6–8 ms；`fold` <1 ms/天；`land*` 0.35 s/30 天；变更集序列化（`Timeline.java:362-370`）≈20 ms；SQLite 落盘 ≈0.14 s；`EconomyCodec.encodeSnapshot` 89–113 ms / `decodeSnapshot` **643–688 ms**（**本批未触发**：checkpoint 间隔 100，revision 1→12 不含 checkpoint）；`ActorCodec` 14–16 ms / 26–30 ms。

### 1.4 规模计数（基准 = tick 360）

| 量 | 数 | 来源 |
|---|---|---|
| 地图 hex | **59,223** | 状态探针 |
| 国家/经济 hex | **799**（德 430 / 奥 138 / 霍 231；全激活） | `m2t360.json` |
| 市场格 / 市场区 | **799 格 / 201 区**（其中 **7 个区**被跨国界的格报告） | 探针 + dump |
| 产业 | **1,799**（farm 799 + weave 799 + craft 201） | dump |
| 阶层行 | **6,392** = 799 × 8（rural/urban 各 3,196；四档各 1,598） | dump |
| 账户（`GoodsAccount`） | **8,191**：HOUSEHOLD 7,191（6,392 家户 + 799 织户经营者）、ESTATE 799、WORKSHOP 201；5,078 本有非空货币；**冻结非空 0** | 探针 |
| 订单（tick 360 状态干跑 day 365 一轮） | 买 **1,822** / 卖 **10,002** 条 | 同包探针（与 `ordersFor` 同一实现） |
| 成交（同一干跑轮） | **2,114 笔**（跨区 0）；grain 2,057 / fiber 46 / cloth 11 | 同上 |
| 在途 | **669 批 / 2,291 allocations**（grain 1,135,465,869 + fiber 15,134,255 毫） | 探针 |
| 债务 / 劳动供给 / 配额 / 生产关系 | debts 699 / laborSupply 4,000 / allocations 6,312 / relations 1,799 | 探针 + dump |

> ★ 订单/成交笔数**没有落盘**（dump 只有 `tradedMilli` + 未成交计数）⇒ 上表用**同包探针干跑**同一 `ordersFor` 得到；探针边界见草稿 A。

---

## 2. 资源竞争、遍历顺序与重跑一致性

### 2.1 哪些结算操作竞争同一主体的商品/货币/冻结额

**默认日序**（`EconomySettlement.settleOneDay`）：到货(`:1070-1106`) → 投入计提(`:1507/1805-1923`) → 消费(`:2423-2463`) → 收获/契约分账(`:3135-3235` + `ProductionSettlement:332-390`) → 市场(`:435/769-826/865-1332`) → 借粮(`:2507-2620`) → 还债(`:2752-2805`) → 饿死(`:3804-3833`) → 计息(`:3864-3882`)。

| 操作 | 商品 | 货币 | 冻结 | 在途 |
|---|---|---|---|---|
| 到货 | 写（买方账户 +） | — | — | 写（批次 −） |
| 投入计提 | 读写（供方 −） | 读写（货币投入档） | — | — |
| 消费 | 读 | — | — | — |
| 契约分账 | 读写（受方 +） | 读写（货币档） | — | — |
| 市场撮合 | 读写（双方） | 读写（货款/运费/承运费） | **唯一写者** | 写（跨区 +） |
| 借粮 / 还债 | 读写（粮） | 不涉 | — | — |
| 饿死/计息 | 不涉库存 | — | — | — |

- 唯一换手写口 = `EconomySettlement.applyTransfer`（`:3288`，两遍式，校验冻结）；app 侧 `OwnershipBooks.fold` **排除 `MARKET_TRADE`**（`OwnershipBooks.java:90`）后按绝对值 `land*`。
- 同格中间品存在"池子"式竞争（`drawCycleInputs` 的供方按持仓比例分派），见草稿 B §1.3。

### 2.2 遍历顺序是否影响结果

- **市场没有价格排序**：`MarketSettlement` 的排序只有格坐标（`:454/949/972`）、承运人 id（`:1932`）、商品名（`:1943`）。
  同价并列的"谁先" = **名单序**：格 `(q,r)` 升序 → 家户行（`stratum, residence`）→ 经营者 `industryId` 字典序；区内按剩余需求/供给比例配给，最大余数法并列按**数组下标升序**（`ProportionalSplit.java:69-99`）。
- ⇒ 同价订单的**成交身份/Fill 顺序**由名单序决定（可复现），在**稀缺供给**下"先到先得"；总量上只差并列残差。
- **劳动配额**：`reallocateLabor` 按缺口排序、并列按 `industryId`；`appendAllocation` 预算封顶按池顺序、并列按下标；插入序风险点仅 `lastResort`/`addLabor`（多个产粮产业时）。
- `grep -rn "HashMap" simos-economy/.../time` = 201 命中**全是 `LinkedHashMap`**（裸 `HashMap` 迭代 0 处）。

### 2.3 固定输入重复运行：状态逐字段一致（实测）

两个独立 JVM（GUI 5847/5857、MCP 5745/5755）各跑一次 **360→390**（约 183–189 s）：

| 比对对象 | 结果 |
|---|---|
| rev12 `changeset_json` | **逐字节相同**（md5 `ee81091287cb8716cc546a7f8b1a084e`），仅 `command_id/correlation_id` 为随机 UUID |
| Replay 重建后的 `SimulationState.equals` | **true**；六个切片 `Snapshot.equals` 全 true |
| 全量 `ownership`（8,191 本账含 goods/money/frozen） | **逐字节相同**（md5 `02c0c109615198d4dc1af5a972dced84`） |
| HTTP 三国 799 格 ownership | md5 相同（`97b20164c774845e6fba1b2550858511`） |

**两处"表示层"非确定性（状态相同、字节不同，需知晓）**：
① `Region.hexes` 用 `Set.copyOf`（`Region.java:28`）⇒ 快照 JSON 里 `map.regions.*.hexes` 的**数组排列**有 227 处不同（canonical JSON 相同）；
② `CrisisMonitor.Light` 用 `Map.copyOf`（`:305`，12 处）⇒ `social.crisis[].evidence` 键序不同（canonical 相同）。
⇒ **逐字节比对原始 JSON 会被这两处误判为"状态不同"**；状态层结论是"逐字段一致"。

---

## 3. `bid=990‰ / ask=1010‰` 的口径与一笔成交

- **定义**：`Market.bidPriceOf = max(1, ⌊p·990‰⌋)`（卖方底价，`Market.java:87-90`）→ `SellOrder.minPrice`；
  `Market.askPriceOf = max(bid+1, ⌈p·1010‰⌉)`（买方限价，`:104-111`）→ `BuyOrder.maxLandedPrice`。
  ★ 命名与常规买卖报价相反：**990‰ 是卖方限价、1010‰ 是买方限价**。
- **成交价 = 参考价**：区内 = 集散节点参考价；跨区 = 卖方格参考价的最大值（`MarketSettlement.unitPriceOf:1415`）；付款 = `⌈数量×成交价÷1000⌉`（`:1196`）；跨区运费另算，`maxLandedPrice` **不含运费**。
- **为什么同一参考价仍有成交**：按构造 `bid ≤ p ≤ ask` 恒成立 ⇒ 卖方接受（`p ≥ minPrice=bid`，`:892`）、买方接受（`p ≤ maxLandedPrice=ask`，`:882`），p 落在中间；价差只放宽/收紧限价过滤，**没有做市商截留**。
- **实测一笔**（探针在 rev11 tick360 状态上跑到 day365 例行轮）：
  - 布：`-20_-83 → -19_-83`，卖方 `HOUSEHOLD:-20_-83:rural:middle_peasant`，买方 `HOUSEHOLD:-19_-83:urban:landlord`，qty **44**，单价 **5**，双方 `[ref,bid,ask] = [5,4,6]`，付款 **1** 毫，运费 0；
  - 粮：qty **1,388**，单价 **1**，双方 `[1,1,2]`，付款 **2** 毫。
  - 该日 5,483 笔逐笔 `unitPrice == 双方 ref` 且 `sellerBid ≤ unitPrice ≤ buyerAsk`。

---

## 4. 城镇消失的 7,173,772 毫银去哪了；农村为什么不买布

### 4.1 银：**存量归因**（单位 = 毫银；年度总量精确 142,924,800）

- 城镇 **7,404,906 → 231,134**，净减 **7,173,772**（= 717.3772 万毫，对用户"约 717 万"）。
  分阶层减少：地主 −1,176,199 / 中农 −2,380,034 / 富农 −2,059,730 / 贫农 −1,557,809。
- **净去向**：农村 **+6,684,360（93.2%）**、经营者账户 **+489,412（6.8%）**、**冻结 0**、未覆盖 0。
  农村内部再分配巨大：**农村地主 +52,604,273**，其余三档合计 **−45,919,913**。
- 年末分布逐毫对上：城镇 231,134 + 农村 142,125,688 + `ESTATE:farm` 566,002 + `HOUSEHOLD:weave` 1,762 + `WORKSHOP:craft` 214 = **142,924,800**。
  @180 经营者合计 78,566（farm 7,338 / weave 59,921 / craft 11,307）；**8,191 本账 frozenMoney 两个时点全 0**（冻结是轮内临时占用，轮末 release）。首都单格 2,178,587 → 225,420（占城镇失血 **26.3%**）。
- ★ **只能做"净去向"，不能做"谁付谁"**：`ProductionLedger` 当日 fold 后丢弃、`MarketReportFeed` 不落盘；本年度债务全是**粮债**（无银债），制度支付全实物（handicraft 货币工资受付方余额封顶）⇒ 银的换手通道只剩 `MARKET_TRADE`。

### 4.2 农村不买布：**35 天生活保留口径**下的分解

- 市场总量（**全局 `regionId` 去重 = 201 区**，@360）：布供给 **30,864,321,363**、读时有效需求 **178,213**、成交 **196,119**；
  卖方未成交 `no_budget` **4,721 笔 / 30.87B**；买方未成交 `algorithm_uncovered` 267 笔 / 464,160。`needsButCannotAfford` 对布**恒 0 是结构性的**（代码只对粮算）。
- **逐农村行（3,196 行）**：**3,195 行 gap = 0**（布持有 ≥ 35 天生活保留；@180 为 3,196/3,196）；
  农村布覆盖中位数 **575 天**、合计 623 天；唯一 gap 行 = 首都农村地主（gap=192，有钱并真下单）。tick0 布库存 = 0（布是实物分成堆出来的：cycle3 农村布 income 7.94B vs consumed 3.35B）。
- **①~⑤ 分解**：
  | 档 | 数/结论 |
  |---|---|
  | ① 无需求/保留已足 | **99.97%**（3,195/3,196 行 gap=0）——主因 |
  | ② 无预算 | 农村 **0 行**（不解释农村）；**城镇**才是"想买没钱"：265 个缺口行合计只有 **902 毫银** |
  | ③ 价格/限价 | **0 行被挡**（全 5/4/6；按构造 bid≤ref≤ask） |
  | ④ 订单生成 | `naturalNeeds` 有布（行行 >0），但 `effectiveDemand` main 无写入点（全空）；取整为 0 的农村行 0；**真正卡城镇的是撮合侧 `MARKET_MONEY_ROUNDING_MARGIN_MILLI=2`**（178,213 毫订单最多只能成交 74,145） |
  | ⑤ 市场可达性 | **0 个** `no_adjacent_supply`/`no_seller`/`all_reserved`/`price_limit`/`logistics_*` ⇒ 不是约束 |
- 探针（生产字节，`MarketSettlement.class` md5 与运行 jar 同 entry）真跑 `planOrders`：@360 rural 1 单(192)、urban 265 单(178,021)，合计 **178,213** 与 dump 读时 `effectiveDemandMilli` 逐值相等；@180 两居住地全 0 单。
- ★ **窗口警告**：读时 demand 是结算日结束后重算，`match` 是当天开市报告 ⇒ 两窗口不可混算（@360 读时 178,213 vs 开市未成交买单 464,160；@180 读时 0 vs 当轮成交 15,902,560）。
- ★ **口径更正**：此前报告"208 区 / 布成交 224,004"是 `(国,区)` 去重（7 个跨国区被计两次）；**全局去重后为 201 区、布成交 196,119**（m2@180 布成交亦由 16,789,695 更正为 **15,902,560**）。

---

## 5. `naturalNeeds → effectiveDemand → BuyOrder → 成交` 与"自定义需求"的写入面

### 5.1 每一步在哪、何时写/清零（`文件:行`）

| 对象 | 生命周期 |
|---|---|
| `naturalNeeds`（粮/布） | **唯一写点** `EconomySettlement.withDailyNeed`（`:4306-4340`），在每个结算日的消费步 `:2431` **先覆写后读**；创世只写**粮第 1 天**（`EconomySeeder:1578`，无布）；周期边界**不清零**（`:4386`）。行为读者只有：消费 `:2436/2443`、低粮追加轮 `MarketSettlement:367/381`、社会压力（`PopulationEconomyTimeParticipant:329-334`）——三者读的都是**结算刚覆写的那一份**。 |
| `effectiveDemand` | **main 结算零写入、零行为读者**（只创世写空表 `EconomySeeder:1579` + 载荷原样透传 `EconomyPayloads:721` + 读口显示 `ApiViews:1209`）。 |
| `BuyOrder` | **只**在市场轮 `MarketSettlement.ordersFor:679-748` 瞬时生成，**不落盘**；有独立的读口派生量 `MarketReadout.CommodityReadout.effectiveDemandMilli`（= 本轮买订单数量之和，`:202-218/275`）。 |
| 生成公式 | `gap = max(0, target − available − incoming)`（`:719`）；`target` = 家户「人口×35 天生活保留」或经营者「必要投入」；预算 = actor 货币会话副本（`:723/1889-1893`）；`affordable = ⌊budget×1000÷参考价⌋`（`:730`）；`quantity = min(gap, affordable)`（`:731`）；付款 `⌈q×价÷1000⌉`（`:1196`）。 |

### 5.2 关键现状（对"自定义需求"最重要的一条）

**市场买量来自"人口 × 35 天生活保留 / 经营者必要投入 + 货币预算"，完全不读 `naturalNeeds` / `effectiveDemand`。**
⇒ **只写这两个字段的"需求冲击"不会产生任何订单**（不是"延迟生效"，是**没有读取路径**，见 §8）。

### 5.3 "自定义需求"四栏在现有代码里的对应

| 想写入的东西 | 现有字段/载体 | 现状 |
|---|---|---|
| 生理需要 | `ClassRow.naturalNeeds` | 每天被结算覆写；**市场不读** |
| 消费偏好 | —— | **代码里没有这一维**（没有偏好/弹性/替代结构） |
| 实际采购订单 | `BuyOrder`（瞬时） | 只能由 `ordersFor` 在开市那一刻生成；无外部注入面 |
| 有钱支付的需求 | `MoneyFixture` 之外的"预算"= actor 侧货币副本 | 由账户余额决定；市场按 `min(gap, affordable)` 取小 |

---

## 6. 单位、最大中间乘积、除法与舍入

### 6.1 单位（摘要）

毫商品（粮/布/纤维/工具的定点最小单位）；**千分劳动**（一个人满劳动 = 1000）；毫货币（最小币值）；`LAND` = **千分亩**；`TOOL`/`WORKSHOP` = 件；人口 = 人；时间 = 世界日；率 = 千分（‰）。完整表见草稿 D §6.1。

### 6.2 除法/取整点（方向与调用处，摘要；全表 55 行在草稿 D §6.2）

- 成交付款：**ceil** `⌈q×p÷1000⌉`（`MarketSettlement:1196`）；
- 可买量：**floor** `⌊budget×1000÷price⌋`（`:730`）；
- 配给：最大余数法（保 Σ，单项可 0；`ProportionalSplit:69-99`）；
- 给养义务：**floor** `laborMilli÷1000 × perLaborMilli`（`SubsistenceObligation:135`）；
- 劳动折算：**floor** `laborMilli×participation÷1000`（`ClassRow`）；
- 口粮/衣着累计：`EconomyVocabulary.cumulativeRationMilli/cloth`（逐日差分）；
- 配额切分：`EconomySeeder` 最大余数法；scale：`capacityScaleOf` 各产能 floor 后取 min。

### 6.3 最大中间乘积与溢出（真档量级：人口 ~1.15e7、库存 ~1e11–1e12 毫、价格 1–20、劳动 ~1e10）

| 点 | 上界/风险 |
|---|---|
| **`ProportionalSplit:54 total*weights[i]`** | **唯一明确有溢出风险且无保护**：区内两因子都 ≥ ~3.04e9 即可能 > `Long.MAX`(9.22e18)；跨区单订单剩余 > ~1.15e10 即可能溢出（调用处 `MS:928/929/1102/1107` 等） |
| `ProductionSettlement:673 share*own` | 次风险：真档 ~2.14e17 安全；单格劳动 > ~4.44e10（约 37–64 万人/格）才可能溢出 |
| `quantity×price` / `labor×participation` / `inputPerUnit×scale` / `population×系数` / `money×1000` | 派单量级下**安全**（逐个给了算式与数） |

### 6.4 "小于一个计量单位永远付不出"与反向案例

- 归零案例：`laborMilli < 1000 ⇒ 给养义务 0`（`SO:135`）；`amount×rate < 1000 ⇒ 0`（`ProductionSettlement:663`）；`share×own < total ⇒ 0`（`:673`）；
  `drawn < inputPerUnit ⇒ 投入路 0 ⇒ 整格无产出`（`EconomySettlement:3765-3771`）；最大余数法只保 Σ、不保单项。
- 反向：**ceil 保证"至少 1 毫"**：`quantity×price < 1000` 时付款仍 ≥1 毫（`MS:1196`；1 毫商品@1 价也付 1 毫）。

---

## 7. `capacity` 是什么；配方可选吗；扩产/停业/重开有入口吗

- **语义**：`capacity` / `capacityPerUnit` 的键是 `AssetKind`（`LAND`/`TOOL`/`WORKSHOP` 等），表示**本格该产业占用的生产资料总量**（K3）；
  `LAND` 单位千分亩、其余为件；**不是产权、也不是脱离生产资料的抽象产能**（`Industry.java:25-28/82-84`）。
- **创世写入**（`EconomySeeder`）：
  | 产业 | capacity | capacityPerUnit | 劳动/单位 | 产出/单位 | 投入/单位 |
  |---|---|---|---|---|---|
  | farm（恒有） | `{LAND: 该格 landMilliMu}`（`:1239-1257`） | `{LAND: 1000}` | 143 | grain 67 / fiber 6 | `LAND→grain 8000` |
  | weave（农村人口>0） | `{TOOL: 农村人口/20}`（`:793/1280-1293`） | `{TOOL: 1}` | 1000 | cloth 30 | fiber 30000 |
  | craft（城镇人口>0） | `{WORKSHOP: 城镇人口/50}`（`:794/1351-1374`） | `{WORKSHOP: 1}` | 1000 | cloth 60 / tool 5 | fiber 60000 / tool 2000 |
- **经营者不能选择别的配方**：一个 `Industry` 只有一份配方，`recipe()` 纯派生（`Industry.java:297-299`），运行期无写入口，`RecipeId` 在 main 零引用。
- **扩容 / 停业 / 重开：没有现成状态入口**：`withIndustries` 只是 copy-with、main 无调用；**唯一 economy 写命令是 `economy.Seed`**，且对已占用格整份拒绝（`EconomySeedHandler:74-80`）；`progressDays`/`cycleInputUsedMilli`/`capacityScaleOf`/`reallocateLabor` 都没有对外写入口 ⇒ **代码里没有这些命令**。

---

## 8. 年中需求冲击：现有周期下最早哪一天生效

**先分清两类"需求冲击"**：

1. **只写 `naturalNeeds` / `effectiveDemand`**：对**劳动、开工、投入采购、产出、成交全部没有生效日**——市场与结算都不读它们（§5）。
2. **走现有生产侧的冲击**（例如改人口/劳动分配/投入/产能等已有状态，或让缺粮触发市场与借粮）：以 X=181 为例的时间线：
   - **市场轮**：183（仅当低粮条件成立，`day%5==3`）、否则 **185**（`day%5==0`）⇒ 买卖订单/成交最早 183（条件性）/185（例行）；
   - **劳动**：`reallocateLabor` **每天被调用**，但只在格内有产业 `progressDays==0` 时真的重排（`EconomySettlement:2844-2856`）⇒ 同步 120 天产业的**实际重排日 = 1/121/241/361…**；本周期内最近为 **241**（月度人口回写 210 结算后、211 生效的路径另计）；
   - **投入采购**（下一个生产周期第一天现扣）：**241**（`drawCycleInputs`）；
   - **产出**：本周期**收获 240**（读当天 capacity/outputPerUnit，但受 121 已扣投入与已累计劳动约束）；**需要新投入才能改变产量的首个产出 = 360**（241 扣投入后、360 收获）。
   - 日产业的"当日劳动"只读 `LaborAllocation`（`:767/808`）；`ClassRow.laborMilli` 只喂 `laborOfCohort`（给养量），不改变当日开工。

---

## 附：证据与边界

- **四份原始草稿**（每份含逐条 `文件:行`、命令、表格与"我没做/没验证的"）：
  `A-timing-counts.md`（JFR/探针/计数）· `B-determinism-bidask.md`（竞争矩阵/重跑/成交样本）·
  `C-silver-cloth.md`（银去向/布归因）· `D-code-reading.md`（602 行：单位/取整全表/乘法上界/capacity/时序）。
- **本报告没有做的事**：没有任何设计建议；没有改任何生产代码；没有全量 profile（JFR 窗口仅 360→390）；关账日内部阶段（harvest/还债/计息）未单独计时；溢出阈值与冲击时间线为**静态推演**（未实跑构造）；
  原始 JSON 的逐字节比对会被两处 `Set.copyOf/Map.copyOf` 排列影响（状态层已用 canonical/equals 证明一致）。
