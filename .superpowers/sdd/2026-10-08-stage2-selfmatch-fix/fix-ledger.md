# 商品撮合"同户自配对"修复账本（task-13）

> 责任区：`simos-economy/src/main/**` 的**一处**缺陷 —— three-powers 世界推进 90→120 时整轮被 400 拒
> （`Transfer 的两端不得相等`，`tr-120-31`，`from = to = HOUSEHOLD:hh--1_1-rural-middle_peasant`，
> 货腿 `goods={cloth=617}`，栈：`ProductionLedger.mint ← MarketSettlement.executeTrade ← pairUp ← matchGroup ← matchWithinRegionsSerial`）。
> 本账本记**机制结论 / 守卫落点 / 取舍后果 / 其它腿复核 / 改动 / 编译 / 数值影响 / 未验证项**。
> ★ 任务板报"agent 不是 active team 成员" ⇒ **未能 claim task-13**（按任务书继续执行，交账时说明）。

---

## ① 机制结论：同一户为什么会在同一市场同时被撮成买卖双方

### 结论（一句话）

**同一户的买腿只可能来自"家户套利买盘"这一层叠加**；卖腿来自同一次订单生成里算出的卖单余量。
两者能同时为正，是因为**套利决定取自"劳动阶段"的当日快照，而卖单余量取"开市阶段"的实时库存**，
当日**收获/产出**恰好落在这两个阶段之间 ⇒ 同一户"劳动时缺布（⇒ 挂套利买盘）"而"开市时布有余量（⇒ 挂卖单）"。
撮合层（`pairUp`）只按成本层 + 比例分配配对，**从不比较两端是否同一主体** ⇒ 把它配给了自己。

### 逐步证据（`file:line`）

1. **每个市场主体在本轮对同一商品至多一买一卖，且两端是同一个 actor。**
   - `MarketSettlement.participantsFor`（`MarketSettlement.java:5083` 起，`byActor` 按 actor 去重；
     unit 的 operator/经营者**解析到家户后合并进同一个 Participant**——`MarketSettlement.java:5227-5242`）
     ⇒ 家户与其名下 unit **共用一个 `Participant.actor`**（`HouseholdActors.of(household)`）。
   - 槽位**只有两个创建点**：`MarketSettlement.java:1194`（`new BuySlot`）与 `:1202`（`new SellSlot`），
     两者都来自**同一次** `ordersFor(...)`（`:1179`）⇒ 同户的买腿与卖腿只能出自同一次调用。
2. **卖单余量算式**（`MarketSettlement.java:1556-1557`）：
   `retention = life + demandTarget`；`sellable = max(0, 库存 − 冻结 − 必要投入 − retention)`；
   其中家户的生活保留 `life = expectedNeedMilli(商品, 35 天)`（`MARKET_LIFE_RESERVE_DAYS = 5 + 30`，
   `MarketSettlement.java:124/127`；`householdLifeReserveOf` `:5362`）。
   `sellable > 0` ⇒ `可用(库存−冻结) > necessary + life + demandTarget ≥ life + demandTarget`。
3. **买单量算式**（`MarketSettlement.java:1584-1604`）：
   - 家户 `baseTarget = life`（`:1573`），目标缺口 `desiredQuantity(life, demandParts, available, incoming)`
     （`:1585` / 定义 `:1663`）；信用世界走它、非信用世界走 `allocateQuantity`（`:1586`）。
   - **证明：`sellable > 0` 时目标缺口恒为 0。** 由 ② 有 `covered = available + incoming ≥ available > life + demandTarget`：
     基线缺口 `max(0, life − covered) = 0`；`residualCover = covered − life ≥ demandTarget`，
     逐个需求分段 `max(0, part − residualCover) = 0` ⇒ `desiredQuantity = 0`；`allocateQuantity` 同理为 0。
     ⇒ **同户买腿不可能来自需求缺口，只能来自第 4 条那一行加法。**
4. **套利买盘是唯一的加量处**（`MarketSettlement.java:1594-1604`，`quantity = addExact(quantity, arbitrageQuantity)`）：
   - 决定在**劳动阶段**做：`EconomySettlement.java:1290`（调 `LaborQueueSettlement.apply`）内
     `HouseholdValuationBook.derive(...)`（`:204`）与 `buildArbitrageSnapshots(...)`（`:201` / 定义 `:613`）——
     快照库存 = `账户余额 − 冻结`，取"当日劳动分配时刻"的可动余额。
   - 判定"有价差可套"需要 **保留价 ≥ 市价 × 1.1**（`TradeArbitrageActivity.java:281` 的 `evaluate`，
     `MIN_EDGE_PER_MILLE = 100` `:137`，判据 `:319-321`），而保留价的压力项
     （`HouseholdValuationBook.java:214-221`）= `+500‰ × 短缺率 − 400‰ × 饱和率`
     ⇒ **必须"快照时库存 ≤ 约 0.8 × 30 天目标"**（30 天 = `HOLD_DAYS` `:52`）才够 10% 价差。
   - ⇒ 卖腿要求 **开市时** 库存 > 35 天需求；买腿要求 **快照时** 库存 ≤ 0.8 × 30 天需求。
     两者**在同一个库存值上互斥**，因此必然发生了**库存增长（或需求行变化）**。
5. **两个阶段之间确实隔着当日产出**：`EconomySettlement` 的日序 = 投入扣减/劳动分配
   （`EconomySettlement.java:1272/1290`，套利快照在此） → 消费 → 关账收获
   （`harvestPartitioned` `:1532`，产出按关系入各户账户） → **市场**（`clearOncePerCycle` `:1729`）。
6. **撮合层没有同户判据**：`matchWithinRegionsSerial`（`MarketSettlement.java:2831`）→ `matchGroup`（`:3241`）
   → `pairUp`（定义 `:3660`；原诊断栈的 `pairUp(:3659)` = 该方法里那一行 `executeTrade(...)`，本批加守卫后移到 `:3778`）
   只按"成本层 → 比例分配（`ProportionalSplit`）"配对；
   唯一漏斗 `executeTrade`（`:3866`）的货腿在 `:3951` 铸 `sell.seller.actor → buy.buyer.actor`。
   ⇒ 两端相等时 `Transfer` 构造器 fail-closed（`Transfer.java:114`，`ProductionLedger.java:721` 的 `mint` 只是记录者）。

### 世界侧旁证（已冻结运行目录里的日志）

`/home/cna/simos-runs/2026-10-08-sw19-arb/b-3p-120/service.log`：

```
22:32:58.193 INFO economy.settlement - event=ARBITRAGE_ROUND origin=economy-arbitrage day=120
  households=104 buys=104 holds=0 totalQuantityMilli=1121600 ... commodities=cloth,grain
22:34:04.172 INFO economy.settlement - event=HARVEST ... day=120 closedUnits=271 gross=2178690000 ...
22:34:04.173 INFO economy.settlement - event=ARBITRAGE_ROUND ... day=120 ... commodities=cloth,grain
22:34:04.176 INFO app.gui - event=GUI_ACCESS method=POST path=/api/advance status=400
```

- day=95/100/105/110 只有 `commodities=cloth`（93~95 户），day=115/120 扩到 `cloth,grain`（100/104 户）
  ⇒ 布上的套利买盘**长期存在**，自配对只在"某一户同时有卖单余量且被配到自己"时命中（撮合巧合，不是新机制）。
- 日志级别下没有逐户明细（`ARBITRAGE_BUY_ORDER` 是 TRACE、`ARBITRAGE_GRANTED` 是 DEBUG，均未开）
  ⇒ 无法从日志点名"哪一户双挂"，但**买腿只能来自套利**这条是上面第 3 条的静态证明，与具体户无关。

### 为什么"单币/单区世界没踩到"

自配对需要"同一户在同一（区, 商品）组里同时有买槽与卖槽"。单币/单区世界里该户的卖单余量与套利买盘
同样可能同时为正（机制与币种无关）；本世界把它变成**必然**的是两点：① 3 区拓扑 ⇒ 区内跨格撮合 +
商号承运走 `matchWithinRegionsSerial`（多格同区 ⇒ 配对池更大）；② 3 币世界下买币恒为 `SILVER_SPECIE`
而卖方收款币是各格 `Market.numeraire()`（`SellSlot.receiveCurrency`）⇒ 大量组合在 `executeTrade` 第一行
就被 `MARKET_CURRENCY_MISMATCH_REJECTED` 早退（`service.log` 里 day=3 起成片），
**自转移只在"币种相同 + 被配到自己"的那一次才真的走到 `mint`**。

---

## ② 守卫放在哪一层、为什么

**放在两处"成交前"的配对层，形制照 `FxSettlement.matchBook` 的既有做法**（`FxSettlement.java:344`：
`if (bid.owner.equals(ask.owner)) { … 跳过并具名 … }`）：

| # | 位置 | 覆盖的腿 |
|---|---|---|
| G1 | `MarketSettlement.pairUp`（定义 `:3660`，守卫体 `:3685`；两个调用点 `:3293` 区内 / `:3599` 跨区**唯一**漏斗） | 现金成交的商品腿（`:3951`）+ 货款腿（`:3995`）；区内 worker 与协调器回放走同一段代码 |
| G2 | `MarketSettlement.moneyCreditForBuy`（`:1982`，配 `CreditPools.bestCashSeller` 加排除集 `:2560`） | 货币信用三腿里的两条（`:2146` 货腿、`:2161` 货款腿） |

**为什么在配对层而不是别处**：

1. **它是唯一能"在铸转移之前"拦住的地方**，且是两条成交路径（区内 worker / 协调器回放、跨区）的公共段。
   ★ 补一句精确性：`executeTrade` 本身有**两个**调用点 —— `pairUp`（`:3776`）与 worker 意向回放
   `replayRegionOutcome`（`:3097`）。回放执行的是**同一个 worker 里由同一段 `pairUp` 算出的** `FillIntent`
   （意图里没有这一对 ⇒ 回放也不会执行它），所以 G1 对"worker + 协调器"两侧同时有效，且没有第二条入口。
2. **不能放在 `Transfer` / `mint` 里**：那是 fail-closed 契约的最后一道（任务明令不得放宽）；
   把守卫下移也只有"抛 vs 静默跳过"两种结果，静默跳过会掩盖坏数据。
3. **不能放在订单层（把套利买盘按 `sellable` 对冲/清零）**：那会**改变没有崩溃的世界的数值**
   （day=95…120 每轮 93~104 户的套利买盘都要改口径），而本仓当下的验收口径是"重跑冻结世界看读数"。
   配对层守卫的性质是"**只在旧代码会 400 的那一次出手**"，因此对不崩溃的运行逐值不变（见 ⑦ 的两个例外）。
   ★ 这是**取舍**，不是"订单层没问题"：根因（劳动阶段快照 vs 开市阶段库存）仍在，已列入 ⑧ 未做项。
4. **G2 是必须的同伴修复，不是可选项**：G1 跳过之后，同户的买槽与卖槽**都留着剩余**，
   紧接着的 `creditRound`（`:1254` 调用 / `:1902` 定义）会用 `bestCashSeller` 挑"第一个还有剩余的卖槽"——
   在旧代码里它完全可能正好挑中借款人自己的卖槽 ⇒ 货币信用三腿里两条自转移 ⇒ 换个地方照样 400。
   （`goodsCreditForBuy` 一开始就按 household 排除了自己 `:2083` 起，货币腿这条路径一直没有。）

---

## ③ 跳过 vs blocked 的取舍与后果

**选定：跳过（skip），不把槽位打成 blocked。**

| | 跳过（本次采用） | blocked（`remaining = 0` / 槽位作废，FX 的形态） |
|---|---|---|
| 语义 | 这一**对**不成交，市场继续：本买方改从下一个卖方取货 | 该**槽**本轮到顶 |
| 后果 | 被跳过的那一份卖单余量在**本轮现金撮合里**不再被取用（信用轮仍可能卖给别的主体）⇒ 没卖完就落进未成交读数（`collectUnfilled` 按既有档位归因）；买方的缺口转向别的卖方，缺口仍可能被满足 | 买方整单作废（哪怕市场上还有别的卖方）；卖方那一份也作废 |
| 对本例 | 更贴近事实：**这一户的买盘与卖盘各自与别人成交是合理的**，只有"自己跟自己"不合理 | 过度：`hh--1_1-…` 会整轮不买不卖，数值脚印更大 |
| 与 FX 的差别及理由 | —— | FX 把自配对判成"不可达的契约故障"⇒ 作废两条 + ERROR；商品面这条**可达且是设计副作用**，不是契约故障，因此按 §一.9 的"业务拒绝"档记 INFO |

**精确语义（G1）**：命中自配对时**只把"本买方 × 这个卖方"判为不可用** —— `sellerIndex` 前进一格、
`sellLeft` 换成下一份份额（`sellerIndex` 只前进不回退）。因此：

- 本买方继续按序取后续卖方的份额；没有后续卖方 ⇒ 其缺口保留（不置 `noMoney`、不置 `blocked`）。
- 被跳过的卖方份额**留给后面的买方**？—— **不会**：单调扫的指针不回退，所以这一份在**本轮的现金撮合里**
  不再被任何买方取用（随后的**信用轮**仍可能把它卖给别的主体——货币/商品两条信用腿另有同户守卫）。
  该卖槽若最终没卖完 ⇒ `remaining > 0` ⇒ 未成交读数里看得见（这是本方案**唯一**的"浪费"，如实记）。
  ★ 想让它被后面的买方吃掉需要引入"延迟份额队列"（复杂度/不变量风险都上一个台阶，且本批不能跑测试）⇒ 不做。
- 不置 `noMoney` / `blocked`：钱与需求都没问题，下一轮照常挂单。

**记录（§一.9）**：
- 业务拒绝 = **INFO**：`MARKET_SELF_MATCH_SKIPPED`（`day / household / commodity / buyerHex / sellerHex / skippedQuantityMilli / reason`）；
- 理由 = **DEBUG**：`MARKET_SELF_MATCH_SKIPPED_WHY`（`buyRemainingMilli / sellRemainingMilli / sellAllocatedMilli / reason`）；
- 信用腿同制：`MARKET_CREDIT_SELF_MATCH_SKIPPED`（INFO）+ `…_WHY`（DEBUG）。
- 不提级到 ERROR/WARN：这是**业务拒绝**（§一.9 的 2026-10-23 裁定：业务拒绝 = INFO），
  且**不会**再触发 `Transfer` 的 fail-closed（最后一层守卫原样保留）。

---

## ④ 其它成交腿的复核结果（穷举 `MarketSettlement` 里全部 7 个 `ledger.mint`）

| 腿 | 铸点 | 两端 | 会自配对吗 | 处理 |
|---|---|---|---|---|
| 现金成交-货腿 | `:3951`（`executeTrade`，`MARKET_TRADE`） | `sell.seller.actor → buy.buyer.actor` | **会**（本次缺陷） | G1 |
| 现金成交-货款腿 | `:3995`（同方法） | `buy.buyer.actor → sell.seller.actor` | **会**（同一条配对） | G1 |
| 现金成交-运费腿 | `:4014`（`CARRIER_FEE`） | `buy.buyer.actor → charge.carrierActor()` | 不会 | `carrierChargeSplit`（`:4154`）已整条剔除自承运（`choice.principalActor().equals(buyerActor)`），旧路径 `legacyCarrier.equals(buy.buyer.actor)` 同样跳过 ⇒ 承运人恒 ≠ 买方；承运人 = 卖方只是"买方付卖方"，两端不同 |
| 货币信用-本金腿 | `:2136`（`LOAN_PRINCIPAL`） | `lender.lender.actor → buy.buyer.actor` | 不会 | 出借人池已排除借款人本人（`candidate.lender.household.equals(borrower)`，`:2031`） |
| 货币信用-货腿 | `:2146`（`MARKET_TRADE`） | `sell.seller.actor → buy.buyer.actor` | **会** | **G2**（本次一并修） |
| 货币信用-货款腿 | `:2161`（`MARKET_TRADE`） | `buy.buyer.actor → sell.seller.actor` | **会** | **G2** |
| 商品信用-货腿 | `:2234`（`LOAN_PRINCIPAL`） | `sell.seller.actor → buy.buyer.actor` | 不会 | `goodsCreditForBuy`（`:2083` 起）已按 household 排除自己（且加进 `skippedForThisBuyer`） |

**非转移但同源的两处（已复核、本批不改，如实记）**：

1. **在途批次**：跨区成交的 `ShipmentAllocation(sell.seller.actor, buy.buyer.actor, …)`（`executeTrade` ④ 段）
   由 **G1 拦在铸腿之前** ⇒ 不会出现自配对的在途票；到货段只按买方落回，不再铸转移。
2. **债务合同**：`DebtContractBook.upsert(债务人=买方, 债权人=卖方)`（`:2174` 货币信用 / `:2250` 商品信用）
   **没有**"两端不得相等"的守卫（`DebtContractBook.java:80-104` 只校验非 null/金额）。
   在本批之后它不可达（G2 保证卖方 ≠ 借款人；货币腿的出借人已排除；商品信用已排除），
   **但"自借自还"这条契约缺一道具名守卫**——不属本责任区（不是 `Transfer`，也不改变本缺陷的结论），列入 ⑧。

**FX 面**：`FxSettlement.matchBook`（`FxSettlement.java:344`）已有同户跳过并记 ERROR（其自述为"不可达契约故障"）
⇒ 本批的商品面守卫与它同形，不改 FX。

---

## ⑤ 改动文件

| 文件 | 位置 | 改动 |
|---|---|---|
| `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java` | `pairUp` `:3685-3740` | G1：同户配对跳过 + INFO/DEBUG 具名记录（含机制注释） |
| 同上 | `moneyCreditForBuy` `:1967-2021` | G2：借款人自己的卖槽不进货币信用；INFO/DEBUG 具名记录 |
| 同上 | `CreditPools.bestCashSeller` `:2560-2575` | 加 `Set<SellSlot> skippedForThisBuyer` 形参（与 `bestGoodsSeller` 同形）；唯一调用点 `:1973` 同步 |
| 同上 | `ordersFor` 套利加量处 `:1594-1607` | **仅注释**：指明"这一行不与 sellable 对账"是根因、守卫在 pairUp/credit 两处（不改行为） |

未碰：`simos-economy-api`（**没有**新增 `MarketUnfilledReason` 值——理由见 ⑦）、任何 `src/test/**`、
任何 `pom.xml`、`docs/**`、`.superpowers/**`（除本目录）、`Transfer.java`（自转移守卫**原样保留**）。

---

## ⑥ 编译命令与 exit

```bash
pgrep -af "classworlds.launcher|surefirebooter"   # → 本机无 Maven（唯一命中是 pgrep 自己）
cd /home/cna/SimulatorMosire
./mvnw -q -o -DskipTests compile -pl simos-app -am
# → exit=0，stdout/stderr 0 字节（-q 无输出 = 无 WARN/ERROR）
```
（本批共跑了 5 次这条命令：每次改完源码都重跑；**全部 exit=0**，日志都是 0 字节。
另外跑了 `awk 'length>100' … | wc -l` → **0**（google-java-format 的 100 列自查）。）

编译产物核对（§三.1 的"别只看 rc"，最终一轮）：

```
simos-economy/src/main/.../MarketSettlement.java         22:43:22  md5 0ce1b4df7a297f5bbfaca029e7f8d9f9
simos-economy/target/classes/.../MarketSettlement.class  22:43:39  md5 907b932f48e1eb3c54d9d9bf40fa1153
strings .../MarketSettlement.class | grep -c "MARKET_SELF_MATCH_SKIPPED|MARKET_CREDIT_SELF_MATCH_SKIPPED" → 4
```

⇒ 源码比上一次编译**更新之后**又编译了一次（.class mtime 22:43:39 > .java mtime 22:43:22），
且新事件名确实进了字节码（不是"没重编的假绿"）。
**未跑** `test` / `verify` / `package`（任务书明令）；**未跑** `spotless:apply`（见 ⑧-4）。

---

## ⑦ 会改变数值行为的清单

1. **旧代码会 400 的那一次配对：从"整轮拒绝"变成"这一对不成交、市场继续"** —— 这是本修复的唯一目的，
   也是**唯一会被冻结世界重跑测到的差别**（day=120 起）。逐笔看：
   - 商品腿、货款腿不再铸（`:3951` / `:3995`）；该买方的缺口转向下一个卖方（可能成交，也可能不成交）；
   - 被跳过的卖槽份额在**本轮现金撮合里**不再被取用（信用轮仍可卖给他人）⇒ 该卖槽 `remaining` 比旧代码大（旧代码在这里崩，无"旧值"可比）；
   - 未成交读数：`collectUnfilled` 按既有档位归因（可能落到 `OUTCOMPETED` / `NO_BUYER` 一类），
     **不改档位词表、不加新档** —— 因为不自造 `MarketUnfilledReason` 值（新增枚举值会动 `economy-api` 的读数契约、
     可能让既有"词表条数/`parse` 往返"测试变红，而本批不跑测试、不写测试）。
2. **旧代码在自配上"早退"（不崩）的那一支：归因落在哪个卖槽上会变。** 例：3 币世界里买方币恒为
   `SILVER_SPECIE`、卖方收款币 = 该格 `Market.numeraire()` ⇒ 旧代码在 `executeTrade` 第一行
   （`rejectCurrencyMismatch`）就给**这一对**打上 `CURRENCY_MISMATCH` 并 0 成交、`pairUp` 随即 `break`；
   新代码先跳过自配对、改从**下一个卖方**取货 ⇒ 同区同币种，仍会撞同一个拒绝（成交量仍为 0），
   但 `sell.blocked = CURRENCY_MISMATCH` 落到了**另一个卖槽**上 ⇒ 未成交读数的**逐槽归因**可能移动一格。
   余额/库存/价格逐值不变。
3. **信用腿**：借款人自己的卖槽不再被 `bestCashSeller` 选中 ⇒ 不再出现"出借人→买方"成立但货腿自转移的组合；
   该买方若没有别的卖方，则本轮的货币信用 0 成交（旧代码在此 400）。逐槽/逐合同差异同上性质。
4. **不改变**：价格口径（`adaptiveNextPrice` 只看订单量）、区级配额（`consumeQuota` 只在真成交时扣）、
   冻结/释放（`commitFreezes` 与自配对无关）、套利计划本身（`MarketArbitragePlan` 一字未动）、
   日志开关语义、`Transfer` 契约。
5. **硬编码字面量清单（给测试代理当输入）**：本批**没有**新增/修改任何数值常量；新增的是两个事件名
   （`MARKET_SELF_MATCH_SKIPPED` / `MARKET_CREDIT_SELF_MATCH_SKIPPED` 与各自 `_WHY`）与两个 `reason` 字面量。

---

## ⑧ 未验证项 + 重跑步骤

**没做 / 没验证的（如实记）**：

1. **没跑任何测试**：`test` / `verify` / `package` 一次都没跑（任务书明令）；没有写/改任何测试文件。
   因此"新事件名进了字节码"是**编译级**证据，**没有**运行时证据。
2. **没起服务、没重跑世界**：`bench` 用 `simos-app/target/*-shaded.jar`，而那是 `package` 的产物
   （任务书禁止）⇒ 真跑由控制方执行，步骤与预期见 `repro.md`（同目录）。**jar 重建前，本修复不在运行路径上。**
3. **没做变异自证**（不写测试、不改坏代码跑红）。
4. **格式门禁未跑**：`spotless:apply` / `checkstyle` / `spotbugs` 未跑（任务书只允许 compile）。
   新增代码按 google-java-format 形制手写（2 空格缩进、无 >100 列行——已用 `awk length>100` 自查为 0 命中），
   但**不保证**与 Spotless 的正式结果逐字节一致 ⇒ 关账前请跑一次 `tools/mvn-lock.sh -q spotless:apply`。
5. **根因未在订单层处理**：① 套利决定用的**劳动阶段快照**与开市实时库存之间的时间差（当日收获/产出夹在中间）
   仍然存在 ⇒ 同户双挂仍可能每轮出现，只是不再崩（命中的那几对会各记一条 `MARKET_SELF_MATCH_SKIPPED` INFO；
   ★ 本次失败的 day=120 已确认至少一次命中，其它轮是否出现**未验证**）。
   候选后续（本批**故意不做**，因为它改变未崩溃世界的数值、且必须由控制方在真跑里验收）：
   a. 订单层：`ordersFor` 里若 `sellable > 0`，则该商品的套利买量按 `sellable` 对冲/清零（并记 INFO 说明劳动白花）；
   b. 劳动层：套利快照改用"开市前"口径（或把 `quantity` 的决定推迟到开市阶段）。
6. **被跳过的卖槽份额在本轮现金撮合里无人取用**（信用轮仍可能卖/借给别的主体；见 ③ 的取舍后果）：
   未实现"延迟份额队列"让其被后面的买方吃掉。
7. **`DebtContractBook.upsert` 没有"债务人 ≠ 债权人"的具名守卫**（④ 第 2 条）：本批后不可达，未加固。
8. **未能 claim task-13**：任务板报 `agent … is not a member of an active Agent Team`。

**重跑步骤**：见同目录 `repro.md`（含 jar 重建、起服务命令、预期日志行、以及"崩/不崩"的判据）。

**提交提示**：`.superpowers/sdd/.gitignore` 里是 `*` ⇒ **新文件默认被 ignore**（姊妹台账都是 `git add -f` 进去的）。
本目录两个文件要用 `git add -f .superpowers/sdd/2026-10-08-stage2-selfmatch-fix/`；代码改动只有
`simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java` 一个文件
（`git diff --stat` = 122 insertions / 3 deletions；工作树里 `simos-app/**` 的改动**不是本批的**）。
