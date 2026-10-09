# D2：生活保留额改成「遍历本户注入的 naturalNeeds 全部键」——实现架构账本（责任区 D2，2026-10-09）

> **任务**：解掉 D 批上报的 **BLOCKED**——「工具需求已产生、但经济侧不产生买单」。
> **任务书**：控制方给的 D2 任务书（见会话内派单书；本目录只有本账本）。**唯一权威文档** = D 批的
> `docs/superpowers/specs/2026-10-09-commodity-freight-and-merchant-ladder-design.md` §4.2 / §5 I-F4，
> 以及 D 批账本 `.superpowers/sdd/2026-10-09-workshop-demand/impl-ledger.md` §1 S4 / §9 B1（BLOCKED 的实证与建议的最小额外范围）。
> **基态**：工作树 = `7cc31e3c`（HEAD）+ D 批未提交改动（`simos-social/**` + `simos-app/**` 的 6 个文件）。
> **对照轮**：`/tmp/d2probe/before-economy-classes`（**同一工作树、改动前**的 `simos-economy/target/classes` 快照，
> `MarketSettlement.class` md5 `adf91628c209866a66f1c3b8e93556c9`），与新码 `e2ca54b9feb2397184086e6ad2377765` 逐轮对照。
> **任务板**：`team_task_get task-26` 返回「不是 active Agent Team 成员」⇒ **未能 claim / complete**（按任务书"不要卡住"继续执行，如实报）。
> ★ 本账本只记决策相关事实与真实读数，不抄工具输出、不写流水账。

---

## 0. 一句话结论

**BLOCKED 解除**：家户生活保留额的键集由**代码写死的粮/布两行**改成**本户当前注入的 `naturalNeeds` 全部键**（Social 仍是需求唯一权威）。
实测（`three-powers` 真创世 + 真推进 30 天，342 户）：`tool` 由
`effectiveDemandMilli=0 / buyerOutcomes=0 / orderedQty=0` 变成
**`effectiveDemandMilli=39340 / 30485 / 24570`（三区）、`buyerOutcomes=200`、`orderedQty=94535`**；
同期 **粮/布两行的读数、家户行（342 行逐字段）、人口/家户账逐值不变**（HOUSEHOLD digest 两轮**逐字相同**）。
★ 边界如实记：本批只到"**买单/有效需求**"这一层——三个受测世界里 `tool supplyMilli=0`（没有工具卖方），故 `FILLS tool count=0`。

---

## 1. 关键调查结论（`file:line`，2026-10-09 于本工作树核实）

| # | 证据 | 结论 / 影响 |
|---|---|---|
| D1 | `MarketSettlement.householdLifeReserveOf`（改前 `:5829-5844`）**只取两行**：`expectedNeedMilli(GRAIN/CLOTH, MARKET_LIFE_RESERVE_DAYS)` | **BLOCKED 的阻断点**：`tool` 的 `life` 恒空 ⇒ `ordersFor` 的 `baseTarget = life`（`:1604`）恒 0 ⇒ `desiredQuantity`（`:1694`）恒 0 ⇒ **零买单** |
| D2 | `lifeReserves` 全仓读取点**只有一个**：`ordersFor` `:1571` `plan.lifeReserves.getOrDefault(actor, Map.of()).getOrDefault(commodity, 0L)`（另两处使用点 `:5246` `sellerSelfUsable`、`:5479` `lifeReserveOfParticipant` 同样是 `getOrDefault`） | **键序与取值无关** ⇒ 把键集从"粮→布"换成"naturalNeeds 的键序"**不改变任何查找结果**；`life` 的迭代序不进任何判据 |
| D3 | `HouseholdEconomy` 规范构造器把 `naturalNeeds` 冻成**保序不可变 `LinkedHashMap`**（`:122-134`，`Collections.unmodifiableMap`，注释明写"绝不用 `Map.copyOf`——迭代序不是内容的纯函数"） | 遍历 `naturalNeeds().keySet()` 的次序 = **app 注入序**，是内容的纯函数 ⇒ 确定性不成问题 |
| D4 | `HouseholdEconomy.expectedNeedMilli(c, days)`（`:228-238`）：`naturalNeeds.getOrDefault(c,0) × days`；份额 0 或 `days ≤ 0` ⇒ **0**；溢出走 `Math.multiplyExact` 具名抛 | 逐商品算式**与改前逐字相同**（改前就是调它）⇒ 粮/布两行必然逐值不变；值为 0 的键被 `need > 0` 过滤，与改前 `if (grain > 0)` 同语义 |
| D5 | `operatorLifeRetentionOf`（改前 `:5846-5900`）**也硬编码粮/布**（`grain`/`cloth` 两个累加器）；它把 `requested` 交给 `SubsistenceObligation.retentionOf`（`:5888`） | 同族口径，一并改成按户遍历；**但它当前不可达**：`participantsFor` 的三处 `new Participant(` 全部带非 null `household`（`:5596` `key`、`:5696` 与 `:5707` `household`）⇒ `planFor` 的 `participant.household == null` 分支（`:5733-5734`）在运行期永不进入。实测佐证：37 格 × 2 轮的 DEBUG 行里 **`operators=0` 74/74 条**（§6.5） |
| D6 | `SubsistenceObligation.retentionOf`（`simos-economy-api` `:268-291`）：`promised = promisedByCommodity(of(relation, labor))`（**由 `relation.rules()` 派生**），`retained[c] = min(requested[c], promised[c])` 且**只遍历 `promised` 的键** | 请求键集扩大**不可能凭空产生保留额**（请求了但没承诺的商品不进结果）⇒ 单位户侧的改动即便将来可达也**不是**"多扣一份"的口子（探针实证见 §6.4） |
| D7 | `MarketSettlement.lowGrainStock`（`:917-947`）里还有 `naturalNeeds[GRAIN]` 的**唯一**硬编码读取，但它是**开市调度判据**（"可用粮覆盖天数 < 10 天 ⇒ 追加一轮"，类注 `:911-916` 明写"逐行判、不是逐格求和"） | **故意不动**：它是粮食安全的调度口径，不是生活保留额；把它泛化成"任一商品低库存就加开一轮"会改变开市频率（越出本批"结算层一处口径"的边界） |
| D8 | `moneyReserveOfHousehold`（`:6000-6026`）**早已遍历全部 `naturalNeeds`** 算货币保留额 | `tool` 的**货币**保留额在改前就已生效（D 批账本 N-3 记过）⇒ 本批**不新增**货币侧行为，只补"买目标"这一侧 |
| D9 | `MarketReadout.CommodityReadout` 的 `effectiveDemandMilli = Σ buy.quantity()`（`MarketReadout:246-249`、`:308`），而买单必须"该商品在本格**已定价**"（`ordersFor:1549-1551`） | `tool` 在世界价格表里有报价（实测 `reference=20`→27）⇒ 一旦 `life` 有值，`effectiveDemandMilli` 与 `buyerOutcomes` 必然 > 0（这正是判据要贴的那两列） |

---

## 2. 实现架构（本批实际长出来的形状）

```
① 家户生活保留（唯一落点）
   MarketSettlement.householdLifeReserveOf(HouseholdEconomy)      :5829-5850
     for (CommodityId c : householdEconomy.naturalNeeds().keySet()) {      // 保序：照注入序展开
       long need = householdEconomy.expectedNeedMilli(c, MARKET_LIFE_RESERVE_DAYS);
       if (need > 0L) { life.put(c, need); }                                // 0 份额 = 没有这一行需要
     }
   → HexPlan.lifeReserves → ordersFor 的 baseTarget（:1604）→ desiredQuantity（:1694）→ BuyOrder

② 单位户经营者保留（同族一处，当前不可达）
   MarketSettlement.operatorLifeRetentionOf(...)                  :5848-5895
     Map<CommodityId, Long> requested = new LinkedHashMap<>();
     for (HouseholdId key : round.index.householdsOf(id))
       for (CommodityId c : householdEconomy.naturalNeeds().keySet())       // 与 ① 同一口径
         requested.merge(c, expectedNeedMilli(c, 窗口), Math::addExact);    // 保留改前的溢出语义
     → SubsistenceObligation.retentionOf(relation, labor, requested)         // 输出仍只落在"承诺了给养"的商品上

③ 日志（§一.9 口径修正的"看到什么"）
   MarketSettlement.logLifeReserves(round, hex, plan)             :5900-5958   ← 新
     一条 / 格 / 轮：day / hex / households / householdReserve=[cloth=..,grain=..,tool=..] / operators / operatorReserve
     调用点：settlement 路径的逐格 worker（clearOncePerCycle :1195 之后，紧跟 planFor）
   事件名 MARKET_LIFE_RESERVE，来源沿用 EconomyLogSource.ECONOMY_MARKET（tick 类，带 day）——**不新增来源表项**
   reserveSummary(map)                                            :5961-5974   ← 新（商品 id 升序的规范串）
```

**数据流（不变）**：Social 逐户展开 → app 注入 `HouseholdEconomy.naturalNeeds`（唯一写入点 `EconomySettlement.applyNaturalNeedsInto`）→
`MarketSettlement.planFor` 逐户读**同一份状态**做多日前瞻 → 买目标缺口 → `BuyOrder` → 撮合。**经济侧不新增需求来源、不加默认表。**

---

## 3. 键集选择与理由（★ 任务书要求的"说明理由"）

**选 `householdEconomy.naturalNeeds().keySet()`（本户**实际被注入**的需求集）。** 理由逐条：

1. **它是"状态"，不是"代码"**：任务书要的正是"哪些商品算生活必需由状态决定"。`naturalNeeds` 是 Social 唯一权威（I-F4）当日
   逐成员展开后由 app 注入的物化视图；键集变了（Social 加一行默认值 / GM 用 `social.SetDemandCoefficient` 给某个家户加一条），
   经济侧的买目标**自动**跟着变，不必再改经济侧代码——本次 BLOCKED 的根因就是"同一件事在代码里存了第二份（粮+布）"。
2. **不越权**：本方法只对**已经存在**的键做 `× 天数` 线性前瞻，不产键、不补默认值、不读 provisioning 表 ⇒ 需求权威仍在 Social。
3. **保序是天然的**：`naturalNeeds` 是保序不可变 `LinkedHashMap`（D3）；而 `life` 的键序**不进任何判据**（D2：全部消费者都是
   `getOrDefault`）⇒ 用注入序即可，不必人工排序（人工排序反而会丢掉"与注入序同源"这条可追溯性）。
4. **不选"需求表全键"（`SocialProvisioning` 全局默认表 × 年龄性别档）**：那等于让经济侧自己把 provisioning 表再展开一次
   ——① 需要 economy 认识 `AgeBracket`/`Sex`/家户成员结构（越界，且 Social 才是成员结构的权威）；② 会把"GM 只给某几户配的需求"
   抹成"所有户都有的需求"（**凭空造需求**）；③ 注入链已经替我们做完这件事（app 的 `naturalNeedsOf`）。
5. **不选 `effectiveDemand` 的键**：它是"有支付力的那部分"（§十四/§十五 的分野），在旧档/未结算日可能为空；而生活保留额是
   **分子侧的自然需要**，必须用 `naturalNeeds`（语义与本方法的既有 Javadoc 一致）。用它还会把"买不起 ⇒ 无需求"写成自证循环。
6. **不选 `cycleNaturalNeedMilli`**：那是**粮专用**的周期累加器（单值，不是逐商品表），别的商品没有同窗口累计值（D 批账本 §6.1 已记
   `naturalNeedWindow=last-settled-day`）——用它只能继续特化粮。

---

## 4. 偏离任务书 / 设计书处（主动报）

| # | 项 | 事实与理由 |
|---|---|---|
| **P-1** | ★★ **任务书对 `small-world` 的前提不成立**：任务书写"单币旧世界 small-world …**它没有 tool 需求** ⇒ 键集只有粮/布 ⇒ 行为应逐字相同" | **实测它`有` tool 需求**：30 天读数 `toolMilli=2045 toolHouseholds=107`（178 户、`population=4811`）。原因是 D 批把 `tool` 加进了 `SocialProvisioning.defaults()` 的**全局**默认表，全局默认对所有世界生效 ⇒ small-world 也逐日展开出 `naturalNeeds[tool]`。⇒ "small-world 逐值不变"这条判据**在 premise 上不成立**，我按实测如实报（§6.3），并给出真正成立的回归判据：**粮/布读数与结局逐值不变**（已成立）+ tool 侧的变化被完整量化。 |
| **P-2** | 改动**不是"只改一处"**：任务书②要求核 `operatorLifeRetentionOf`，我把它一并按同族口径改了 | 它读的是**逐户 `naturalNeeds` × 窗口**（与 ① 同一个算式族），留着"粮/布两行"就是同一件事的第二份拼写点。★ 如实边界：该分支当前**不可达**（D5），所以这个改动在本批**不产生任何运行期数值差异**（实测 `operators=0` 74/74，且新码/旧码全商品成交逐字相同）；改它是为了将来经营者重新入市时不再留一处写死的商品表。 |
| **P-3** | 新增 DEBUG 事件 `MARKET_LIFE_RESERVE`（任务书要求"至少一条能看出保留额含哪些商品的读数"） | ★ **没有**复用既有读数：现有市场 DEBUG 只有"轮开始（`MARKET_ROUND_START`）"与"钱的价（`MARKET_CURRENCY_VALUATION`）"，**都不含逐商品保留额**；读口（`MarketReadout`）有 `effectiveDemandMilli` 但那是"结果"，答不出"保留额里有没有这个商品"（BLOCKED 排查时正是要这一条）。 |
| **P-4** | 这条 DEBUG **放在结算路径的逐格 worker 里，一条 / 格 / 轮**（不是一条 / 户，也不放进 `planFor`） | ① 逐户会按家户数放大（三区 342 户/轮）；② `planFor` 还被**读口** `planOrders`（`:1004`）逐商品调用（`MarketReadout` 逐区逐商品调它）⇒ 记在那里会按"商品数 × 格数"放大 GUI 读路径的日志。逐格汇总两个问题都没有，且 37 格 × 2 轮 = 74 条 / 5 天（实测）。 |
| **P-5** | 顺带修掉任务书没提的一处**风格**问题（两行 101/102 列） | 提交前手工折行到 ≤100 列（Spotless 的列宽）；折行后**重新编译并重跑两轮**，digest 与折行前**逐字相同**（`after` vs `after2` 的 4 个 DIGEST + 30 条 DAYH 全同）⇒ 纯格式、零行为。 |

★ **没有偏离**：`MARKET_LIFE_RESERVE_DAYS`（= 5+30 = 35）的值未动；算式结构（`naturalNeeds × days`）未动；
`expectedNeedMilli` 语义未动；没新增状态组件 / 命令 / 工具 / 写资源；没碰 `simos-social/**`（D 批已完成）、货运表、口岸/关税；
经济侧**没有**第二本需求权威（只读状态里的键）。

---

## 5. 探针（/tmp，不进仓库、不进 `src/test`）

**装置**：`/tmp/d2probe/`（探针源码 `/tmp/d2probe/src/io/mosire/simos/app/time/{LifeReserveProbe,RetentionProbe}.java`）

- `LifeReserveProbe`：**走真实生产装配** —— `Shell.start` + `WorldRegistry.genesis` + `CoreSimos` 的 `AdvanceTime` 逐日推进；
  读数取 app 的 `ApiViews.economyData/actorData` 与 `MarketReadoutAssembly.contextFor`（GUI/MCP 共用的同一份读数装配）+
  `MarketReport`（买单结局/成交）。逐户 dump `HouseholdEconomy` 的**全部九个组件**（人口/劳动/参与率/货币/两类需求/周期累计）
  + actor 切片的家户库存账（`balances`/`frozenBalances`/`money`）；逐格价格表；逐区逐商品读数；逐日 digest（价格与家户账**分列**：
  `DAY` = 家户账 + 价格，`DAYH` = 只家户账）。每组行打 SHA-256 ⇒ 两轮直接 `diff` 或比 digest。
- `RetentionProbe`：直打生产 API `SubsistenceObligation.retentionOf`（经营者保留额的唯一入口），做"请求键集扩大"的对照。
- **对照轮的做法**：改动前先在**同一工作树**（含 D 批未提交的 social 改动）跑一次授权编译，把 `simos-economy/target/classes`
  整目录快照到 `/tmp/d2probe/before-economy-classes`；改动后同样快照到 `after-economy-classes`。
  运行时 classpath = `探针 out : 该轮 economy classes : 其余模块 target/classes : shaded jar` ⇒ **同一个世界、同一份探针源码、
  只有 economy 一个模块的字节不同**（其它模块两轮完全相同）。

**跑法**（每条真跑，exit=0）：

```bash
cd /home/cna/SimulatorMosire
CPB=$(cat /tmp/d2probe/cp-before.txt)   # before-economy-classes:其余模块:shaded jar
CPA=$(cat /tmp/d2probe/cp-after.txt)    # after-economy-classes :其余模块:shaded jar
java -cp "/tmp/d2probe/out:$CPB" io.mosire.simos.app.time.LifeReserveProbe three-powers /tmp/d2probe/store-3p-before 30 > logs/before-3p.log
java -cp "/tmp/d2probe/out:$CPA" io.mosire.simos.app.time.LifeReserveProbe three-powers /tmp/d2probe/store-3p-after  30 > logs/after2-3p.log
java -cp "/tmp/d2probe/out:$CPB" io.mosire.simos.app.time.LifeReserveProbe small-world /tmp/d2probe/store-sw-before 30 > logs/before-sw.log
java -cp "/tmp/d2probe/out:$CPA" io.mosire.simos.app.time.LifeReserveProbe small-world /tmp/d2probe/store-sw-after  30 > logs/after2-sw.log
java -Dsimos.economy.logLevel=DEBUG -cp "/tmp/d2probe/out:$CPA" … three-powers /tmp/d2probe/store-dbg-3p 5 > logs/DEBUG-3p-after.log
java -cp "/tmp/d2probe/out:$(cat /tmp/d2probe/cp-before.txt)" io.mosire.simos.app.time.RetentionProbe
```

★ 两轮都只跑探针 JVM；Maven 只跑了任务书授权的那一条（§7 U1），**没有**跑 `test/verify/package`。

---

## 6. 探针输出（真跑读数）

### 6.1 ★ 正面判据：`tool` 出现买单（three-powers，真创世 + 30 天，342 户 / 37 格）

```
[改前 before-economy-classes = adf91628…]
READOUT region=c-tp-copper commodity=tool reference=20 naturalNeedMilli=1125 window=last-settled-day effectiveDemandMilli=0 supplyMilli=0 … buyerOutcomes=0
READOUT region=c-tp-gold   commodity=tool reference=20 naturalNeedMilli=872  … effectiveDemandMilli=0 supplyMilli=0 … buyerOutcomes=0
READOUT region=c-tp-silver commodity=tool reference=20 naturalNeedMilli=702  … effectiveDemandMilli=0 supplyMilli=0 … buyerOutcomes=0
BUYER_OUTCOMES commodity=tool count=0 desiredQty=0 orderedQty=0 filledQty=0 unfilledReasons={}
FILLS commodity=tool count=0 quantity=0

[改后 after-economy-classes = e2ca54b9…]
READOUT region=c-tp-copper commodity=tool reference=27 naturalNeedMilli=1125 window=last-settled-day effectiveDemandMilli=39340 supplyMilli=0 needsButCannotAffordMilli=0 match=traded=0 buyerOutcomes=85 unfilledBuyCounts={NO_LENDABLE_GOODS=85}
READOUT region=c-tp-gold   commodity=tool reference=27 naturalNeedMilli=872  … effectiveDemandMilli=30485 … buyerOutcomes=65 …
READOUT region=c-tp-silver commodity=tool reference=27 naturalNeedMilli=702  … effectiveDemandMilli=24570 … buyerOutcomes=50 …
BUYER_OUTCOMES commodity=tool count=200 desiredQty=94535 orderedQty=94535 filledQty=0 unfilledReasons={ALGORITHM_UNCOVERED=200}
FILLS commodity=tool count=0 quantity=0
```

⇒ **`effectiveDemandMilli>0` 与 `buyerOutcomes>0` 两条同时成立**（任务书的"或"判据的第一种形态）。
★ **`FILLS tool count=0` 如实报**：三个受测世界里 `tool supplyMilli=0`（没有工具卖方投放到市场）⇒ 有买单、无成交。
D 批账本 §6.1 与本轮改前读数**逐字复现**（`naturalNeedMilli=1125`、`effectiveDemandMilli=0`、`buyerOutcomes=0`）⇒ 装置可信。

### 6.2 ★★ 粮/布逐值不变（最重要的回归判据，three-powers 同世界同时长）

| 判据 | 改前 | 改后 | diff |
|---|---|---|---|
| `HOUSEHOLD` 逐行（342 行：人口/劳动/参与率/货币/`naturalNeeds`/`effectiveDemand`/`cycleNaturalNeed`/**商品库存**/**冻结**/**账户货币**） | digest `7f988a0bc7c9425c1ea82dd3a158bfba8619fa3042f7c1412c34dd656eab3bedd` | **同值** | **0 行** |
| `READOUT` 的 grain / cloth 行（含 `naturalNeedMilli`/`effectiveDemandMilli`/`supplyMilli`/`needsButCannotAfford`/`match.traded`/`buyerOutcomes`/`unfilledBuyCounts`） | 9 行中的 6 行 | 同值 | **0 行** |
| `BUYER_OUTCOMES` + `FILLS` 的 grain / cloth（count/desired/ordered/filled/未成交理由） | — | 同值 | **0 行** |
| **全商品**成交（`ALL_FILLS`）与买单户数（`ALL_BUYER_OUTCOMES`，6 商品） | — | 只多出 1 行 | `3a4 > ALL_BUYER_OUTCOMES commodity=tool count=200`（**唯一**差异） |
| 37 格价格表逐商品对比 | — | 仅 `tool` | **`tool` 37/37 格 20→27**；`grain/cloth/fiber/iron` **逐格完全相同** |

⇒ "理论上应完全不变"成立：**粮/布一行一值都没动**；变化只出现在"新增了 tool 需求"这一侧（含它引起的 tool 价自适应上调）。

### 6.3 small-world（30 天，178 户 / 19 格）——★ 与任务书预期**不同**，如实报

```
[改前] NEED_TOTALS grainMilli=321425 clothMilli=11785 clothHouseholds=107 toolMilli=2045 toolHouseholds=107 population=4811
       READOUT region=single-region commodity=tool reference=20 naturalNeedMilli=2045 … effectiveDemandMilli=0 buyerOutcomes=0
       BUYER_OUTCOMES commodity=tool count=0  orderedQty=0 ；FILLS commodity=tool count=0
[改后] READOUT region=single-region commodity=tool reference=27 naturalNeedMilli=2045 … effectiveDemandMilli=32576 buyerOutcomes=105
       BUYER_OUTCOMES commodity=tool count=105 desiredQty=72030 orderedQty=72030 filledQty=0 unfilledReasons={ALGORITHM_UNCOVERED=105}  ；FILLS count=0
```

**① 它"有" tool 需求（107/178 户、2045 毫/日）** ⇒ 前提不成立，见 §4 P-1。**② 逐值不变的部分**（真成立）：`grain`/`cloth` 的
READOUT 行与 `BUYER_OUTCOMES`/`FILLS` 行 **diff = 0 行**；`ALL_FILLS` 全商品**只多出 tool 的买单行**（无任何成交量变化）。

**③ 变了的部分（3/178 户，二阶效应）**：

```
### hh--2_2-rural-landlord   goods: {fiber=898598, grain=23011} → {fiber=899598, grain=23011}      （+1 单位纤维）
### hh-0_0-urban-landlord   goods: fiber 96415→95415 ；accountMoney: {silver=11583} → {silver=11584}
### hh-0_2-urban-landlord   accountMoney: {silver=249} → {silver=248}
```

**④ 因果链（逐日 digest 定位 + 全商品成交对照）**：
- **DAYH/DAY 首次分叉**：价格表**第 3 天**先变（`tool` 20→21，自适应定价对新增买单的反应）；家户账**第 10 天**才出现第一处分叉。
- **第 10 天那一处是什么**（bundle 到 day10 的对照轮）：`ALL_FILLS commodity=grain count=89 → 90`，而**总量完全相同**
  （`quantity=1677046` 两轮一致）⇒ 同一批粮被**多切了一笔**（撮合批次/信用顺序因新增 tool 买单而偏移），货币随之在 3 户之间
  重分配 ±1~2 银（`hh--2_2` 10→12、`hh-0_0` 2273→2272、`hh-0_2` 249→248，**合计守恒**）。
⇒ **不是粮/布口径被改坏**（粮/布读数与结局逐值不变、总成交量一致），而是"新增的买单进入既有撮合/信用流水"带来的**批次级二阶扰动**。
★ 量级：货币 ±1~2（账户量级 249~11,583 银）、纤维 ±1,000 毫（= 1 单位，账户量级 9.6 万毫）。

### 6.4 单位户侧（`operatorLifeRetentionOf`）对照读数

- **运行期不可达的直接证据**：5 天 × 37 格 = **74 条** `MARKET_LIFE_RESERVE` 行，**每一条都是 `operators=0 operatorReserve=[]`**
  （形如 `households=9 householdReserve=[cloth=12530,grain=344785,tool=2205]`）⇒ 走的一直是家户分支。
- **口径等价性（针对粮/布）**：新码对 `grain`/`cloth` 的累加 = `Σ_户 expectedNeedMilli(c, 91)`（`Math.addExact` 语义不变），
  与改前的两个累加器**逐值相同**；`requested` 的键序不进 `retentionOf` 的结果（D6）。
- **"请求键集扩大不产生新保留额"的直打证据**（`RetentionProbe`，生产 API，exit=0）：

```
RETENTION promised=grain-only labor={hh-probe=5000} requested={grain=999999, cloth=123, tool=456} retained={grain=5000}
RETENTION promised=grain+tool requested={grain=999999, cloth=123, tool=456} retained={grain=5000, tool=456}
```

⇒ 关系没承诺的商品**不会**因为"请求里有它"而获得保留额；只有承诺了（`relation.rules()`）才被 `min(请求, 承诺)` 封顶。
⇒ 单位户侧的改动在本批**数值上零影响**（且全商品成交/家户账的实测对照也印证：两轮除 tool 侧外逐字相同）。

### 6.5 日志（§一.9）：DEBUG 能看到"保留额现在含哪些商品"

```
DEBUG io.mosire.simos.economy.market - event=MARKET_LIFE_RESERVE origin=economy-market originKind=tick day=3 hex=-3_0
      households=9 householdReserve=[cloth=12530,grain=344785,tool=2205] operators=0 operatorReserve=[]
```

- 5 天 / `three-powers`（37 格 × 2 轮）共 **74 条**，其中含 `tool=` 的 **74 条**；**旧码同一世界同天数 ⇒ 0 条**（事件是新加的）。
- 只在 `DEBUG` 打开时构造（`MARKET.isDebugEnabled()` 先判）⇒ 默认 INFO 下零开销、零输出；不进读口路径（P-4）。
- 级别与来源沿用既有约定：DEBUG（阶段池子/汇总）、`origin=economy-market`、`originKind=tick`、带 `day`。

---

## 7. 会改变数值行为的清单（★ 给测试代理当输入）

| # | 何时改变 | 改什么 | 量级（实测） |
|---|---|---|---|
| **N-1** | **无条件**（只要该商品在 `naturalNeeds` 里且份额 > 0、且该格已给它定价） | 该商品从"无买目标"变成"买目标 = 日份额 × 35 天" ⇒ 产生买单、进入 `effectiveDemandMilli`/`buyerOutcomes`（并参与信用/撮合）；**同一份键集还喂另外两处**：`sellerSelfUsable`（`:5246`）⇒ 持该商品的家户**更不愿卖**（它被算作"自用保留"）、`lifeReserveOfParticipant`（`:5479`）⇒ 买方 `gap` 归因口径同步 | `three-powers` 30 天：tool 由 0 → `effectiveDemandMilli=39340/30485/24570`、`buyerOutcomes=200`、`orderedQty=94535`；`small-world`：0 → 32576 / 105 / 72030 |
| **N-2** | 同上 | 新增买单会把该商品价格推上去（自适应定价）：`tool` 20 → 27（三区 37/37 格、small-world 同）；**其它商品价格逐格不变** | `tool` +35%；首次分叉 `small-world` 第 3 天 |
| **N-3** | 二阶（仅在某些世界） | 新增买单进入既有撮合/信用流水 ⇒ 批次级重分配：`small-world` 30 天后 **3/178 户** 有 ±1~2 银 / ±1,000 毫纤维；总成交量不变 | 见 §6.3；`three-powers` **零**（HOUSEHOLD digest 逐字相同） |
| **N-4** | 只要 GM 用 `social.SetDemandCoefficient` 给**任一商品**配了需求（全局默认或家户覆盖） | 该商品现在也会产生家户买目标（改前只有粮/布会） | 口径变化本身即判据；**这是本批的设计意图**（需求由状态决定） |
| **N-5** | ★ **不改变** | `MARKET_LIFE_RESERVE_DAYS`（35）、`expectedNeedMilli` 语义、粮/布两行的一切读数与结局、家户行/人口/家户账（three-powers 逐字相同）、单位户侧（不可达） | §6.2 / §6.4 |
| **N-6** | ★ **不改变** | `moneyReserveOfHousehold`（早已遍历全键）、`MarketDemandBook`/`ExpectedProfitBook`/`HouseholdValuationBook` 的 30 天目标（D 批 N-3/N-4 已生效） | 本批未碰这些文件 |

**受影响硬编码字面量/常量清单（给测试代理）**：
- **删掉**的硬编码：`householdLifeReserveOf` 里的 `EconomySettlement.GRAIN` / `EconomySettlement.CLOTH` 两个商品常量；
  `operatorLifeRetentionOf` 里的 `grain`/`cloth` 两个累加器与 `EconomySettlement.GRAIN/CLOTH` 两处引用。
- **新增**具名常量：**无**（`MARKET_LIFE_RESERVE_DAYS` 等一律未动；事件名字符串 `"MARKET_LIFE_RESERVE"` 是新字面量）。
- **保留**（故意不动）：`MarketSettlement.lowGrainStock` `:927/:935/:940` 的三处 `EconomySettlement.GRAIN`（开市调度判据，D7）。

---

## 8. 会让既有测试失效的清单（★ 我**没改**任何测试、**没跑**任何测试）

**编译层：预期零失效**（只改了一个 private static 方法体 + 新增两个 private static 方法；**没有**删除/改名/改签名任何公开 API）。

**行为层候选（判据 = 是否"真创世/真推进 + 对该商品的需求/价格/家户账下断言"）：**

| # | 测试 | 为何可能受影响 | 我的判断（**未跑**，测试代理实跑确认） |
|---|---|---|---|
| T-1 | `simos-app` `gov/GovGenesisZ6Test`（`GovZ6WorldFixture` = **small-world** + 真推进 **120 天**，`:465`） | small-world 30 天就有 3 户货币/纤维差异，120 天可能放大；它断言 gov/国库侧读数 | **风险最高**，建议实跑 |
| T-2 | `simos-app` `tools/write/GovToolsZ6Test`（D 批账本 T-1 已点名；small-world 真推进 + 对 gov 国库 money 下断言） | 同上（货币重分配） | 建议实跑 |
| T-3 | `simos-app` `time/Z7RemittanceE2ETest`（真推进，断言 `GOV_REMITTANCE_DAY`/`GOV_ADMIN_SALARY_DAY` 等事件与金额） | 若其世界有家户经济，货币池会因 tool 买单轻微偏移 | 建议实跑 |
| T-4 | `simos-app` `sd/SdCombatEndToEndTest`（推进 4~5 天）、`sd/SdCommandDrainTest`（推进 5 天） | ≥3 天即可能出现 **tool 价格**差异；若断言里含市场读数/价格则红 | 中低（两者断言的是 sd/战斗） |
| T-5 | `simos-app` `UnitExtensionEndToEndTest`（推进 23 天） | economy 切片可能是 `EconomyData.empty()`（D 批账本 T-2 记"无市场 ⇒ 不受影响"） | 低 |
| T-6 | `simos-economy` 全部（`MarketSettlementFixtures` 的 `ruralRow` 注入 **`naturalNeeds={grain}`**、市场只给 grain 定价 `:82-99`） | 键集仍是 `{grain}` ⇒ 与改前逐字相同 | **预期仍绿**（夹具口径与改动正交） |
| T-7 | 任何"日志事件全集/来源表覆盖"类用例 | 新事件 `MARKET_LIFE_RESERVE` 沿用既有来源 `ECONOMY_MARKET` ⇒ 不新增来源表项、不改既有事件 | 预期仍绿（未跑） |
| T-8 | 任何断言"市场保留额只在粮/布上"的用例 | 若存在，语义已按本批意图改变 | **未发现此类断言**。实测全 `simos-economy/src/test` 里注入 `naturalNeeds` 的商品只有 `grain` / `cloth` / 空表三类（`ExpectedProfitBookTest:167` `Map.of(CLOTH,100)`、`MarketDemandBookTest:245` `Map.of(GRAIN,100,CLOTH,7)` 等）——**布本来就在旧硬编码名单里** ⇒ 键集仍等价；`fiber/tool/iron` 只出现在**价格表与配方投入**里，不在 `naturalNeeds` 里 |

★ **仍要为"新判据"补用例**（下一批测试代理）：tool 进买目标（`effectiveDemandMilli>0`）、粮/布逐值不变、
"GM 给某商品配需求 ⇒ 该商品进买目标且不越权造需求"、单位户侧 `retentionOf` 的承诺封顶（§6.4 两条读数可直接做成断言）。

---

## 9. 未完成 / 未验证 / BLOCKED

| # | 项 | 状态 |
|---|---|---|
| U1 | **编译**（本批唯一获授权的 Maven）：`tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am` | ✅ **exit=0**（跑前 `pgrep` 确认本仓无 `classworlds.launcher`/`surefirebooter`；跑了 **4 次**：改动前基线、改动后、风格折行后、交账前复跑；末次在所有源码改完之后，`MarketSettlement.class` md5 由 `adf91628…` → `e2ca54b9…`，确认真的重编过，且 `find src/main -newer …class` 为空） |
| U2 | `test` / `verify` / `package` / Spotless / Checkstyle / SpotBugs / 前端门禁 | ❌ **未跑**（任务书禁止）。已手工把新增/修改行控在 ≤100 列（`awk length>100` = 0 行），但 **Spotless 是否逐字满意未验证** |
| U3 | `FILLS tool count>0`（成交） | ⚠️ **未达成，也不在本批范围**：三个受测世界 `tool supplyMilli=0`（无卖方）⇒ 只有买单、无成交。判据的"或"第一种形态（`effectiveDemandMilli>0 && buyerOutcomes>0`）已达成；"工具为什么没有卖方"属供给面下一批 |
| U4 | 测试影响面（§8 的 T-1~T-5） | ❌ **未跑**（任务书禁止跑测试）。已用逐日 digest 给出**可判定的边界**：small-world 第 3 天价格变、第 10 天家户账变；three-powers 30 天家户账**零变化** |
| U5 | `v17levant` 大世界 / 其它世界 | ❌ 未跑（体量/时间）。正面与回归证据来自 `three-powers`（30 天）+ `small-world`（30 天） |
| U6 | "GM 用 `social.SetDemandCoefficient` 给**非粮布**商品配需求 ⇒ 该商品出现买单"的端到端 | ❌ 未单独打（本批只证到"Social 注入什么键，经济就为谁建目标"这一读取口径；GM 配需求 ⇒ 注入 ⇒ 买目标这条链由 `naturalNeeds` 的注入路径保证，D 批已证注入链是多商品的） |
| U7 | 往返不变式（铁律 5） | ✅ **不涉及**：未改任何状态组件/Codec/ChangeSet 形状（只改结算层读法 + 一条日志） |
| U8 | 任务板 claim/complete | ⚠️ `task-26` 返回「不是 active Agent Team 成员」⇒ **未能 claim/complete**（按任务书继续执行，如实报） |

---

## 10. 改动文件（1 改，全在允许面内）

| 文件 | 改了什么 | md5 |
|---|---|---|
| `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java` | ① `householdLifeReserveOf`（`:5829-5850`）键集改为遍历 `naturalNeeds().keySet()`；② `operatorLifeRetentionOf`（`:5848-5895`）同族改为逐户遍历键集 + `requested` 前置；③ 新增 `logLifeReserves`（`:5900-5958`）与 `reserveSummary`（`:5961-5974`）+ 结算 worker 里的调用点（`:1195`） | 源码 `f7269c803849efae7446d5a02daa7b3f`；`MarketSettlement.class` `e2ca54b9feb2397184086e6ad2377765` |

**未碰**：任何 `src/test/**`、任何 `pom.xml`、`docs/**`、`.superpowers/**`（除本目录）、**`simos-social/**`**、
`simos-economy/EconomyLog*.java`（未新增来源表项）、货运表（F/F2）、口岸/关税。`git status --porcelain` 里 economy 侧**只有这一个文件**。

**探针（不进仓库）**：`/tmp/d2probe/{src,out,logs,cp-before.txt,cp-after.txt,before-economy-classes,after-economy-classes,store-*}`。
