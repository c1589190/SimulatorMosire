# W4 验收报告 —— 家户的位置（排序结算 + 阶层滑落）

- **角色**：W4 验收 Agent（**只读验收** —— 不改仓库任何源码/测试/文档；装置、store、脚本、日志全落 `/tmp/w4acc/**`）
- **被验对象**：`3b380e3a`（HEAD）= W4 生产码 + W4-fix / fix2 / fix3 / fix4；jar md5 **`8d3c37ba44e018960c714b30ab99e97e`**（07:22 从当前 HEAD 现构）
- **判据来源**：设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` **v3.13** §2 S-1/S-4、§4.2 N-1、§6.1 的 W4 行（判据 ①–⑥）、§6.2 G1–G7；实现账本 `.superpowers/sdd/2026-10-11-w4-ordering-and-class-mobility/impl-ledger.md`（W4 + fix1–fix4）；本任务书 §1 的 10 项
- **时间**：2026-10-11 07:22 – 07:36（CST）
- **一句话结论**：**10 项全部绿**（项 2 有一处"未做"如实标注）——确定性跨进程逐字节一致；**禁自指有 309/309 的窗口吻合 + 4/96 轮反事实反例 + 家户侧 pre/post dump 复算对照**；稀缺品确实先到排序靠前的户（65 个同格组：拿到货的买户名次全部优于没拿到的）；阶层滑落真发生（63 次 / 60 户）、**逐级不跳级**（levels 全 = 1、同一户同日最多 1 次、96 个市场轮各只调用一次）、速度随债/资比变（公式 63/63 逐值自证、实测每级 40/60/63 天）；时点有同日日志行号顺序（统计簿 → `CLASS_SLIDE`）；Δ=1、B2 三份 dump 状态 md5 全等（0/87,137 叶差）；W3 的 D1 修好了（37/37 格 HTTP 200、FOOD 灯 34/37、MORTALITY 灯 36/37 带 `mortalityPerMille`）；老守卫在 0→240 的 96 轮里**零成交轮次 = 0、最小 17 笔**（与 W4-fix2 账本分布**逐项相同**）；人口/货币精确守恒。
  **四条既存/口径异常**（非 W4 引入）：① 账本 G 节把 96 轮写成"48 轮"（D1）；② `population == 0` 的行（180 行：136 行创世未播人口 + 44 行死绝）仍持有 tool 87.7%/fiber 35.3%/grain 14.6% 的库存与 9 笔未偿债、无接管路径（D2，含本批自己的一次读数更正）；③ 36.5% 的滑落评估是 `no-lower-target`（"级"= 角色元组，"富→中→贫"这条阶梯在模型里不存在，账本已具名，D3）；④ N-8 说"国库户按同一规则参与排序"，实现把人口=0 的 `hh-gov-*` 排除在家户序外（本世界国库不下家户单 ⇒ 不可观测，D7）。

---

## 1. 装置

### 1.1 jar（从头构建；**未覆盖任何运行中服务的 jar**）

```bash
# 开工时 pgrep -af java → 无 simos 服务在跑；git status --porcelain → 空；HEAD = 3b380e3a
tools/mvn-lock.sh -DskipTests package -pl simos-app -am      # BUILD SUCCESS，16/16，3.299 s
md5sum simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
```

| | 值 |
|---|---|
| 被验 jar | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，**md5 `8d3c37ba44e018960c714b30ab99e97e`**（27,122,091 字节，07:22） |
| 构建日志 | `/tmp/w4acc-build.log`（`BUILD SUCCESS` / `Total time: 3.299 s` / `rc=0`） |
| 快照副本（本次所有跑用它） | `/tmp/w4acc/w4.jar`（同 md5；`tools/run-shaded.sh` 会把 jar 复制成进程独占快照） |
| W1 基线 jar（性能 A/B 对照） | `/tmp/simos-shaded-EZy0IT.jar`，md5 `a90e11a9ec533010a8b8b568efdad067`（= W3 验收报告 §1.1 的同一份基线） |

**jar 内容自证**（不是只看 md5）：

```
io/mosire/simos/economy/time/SettlementOrderBook.class          True
io/mosire/simos/economy/time/ClassLadderBook.class              True
io/mosire/simos/economy/time/ClassSlideSettlement.class         True
MarketSettlement.class:   "MARKET_PAIRING_SHORTFALL" in bytes = True
ClassSlideSettlement.class: CLASS_SLIDE / CLASS_SLIDE_ROUND / CLASS_SLIDE_SPEED / SLIDE_SPEED_PER_MILLE_PER_RATIO = True
```

### 1.2 启动命令 / store / 端口 / 日志档

```bash
bash tools/run-shaded.sh /tmp/w4acc/w4.jar --store <store> --world=<three-powers|small-world> \
  --config /tmp/w4acc/shell-*.json --gui-port P --mcp-port P+1 --approval-port P+2
# 日志档（JAVA_TOOL_OPTIONS）：-Dsimos.economy.logLevel=… [-Dsimos.economy.traceLevel=…] [-Dsimos.social.logLevel=…]
# 推进：POST /api/advance {"branch":"main","expectedRevision":R,"from":A,"to":B}
# GM： POST /api/command {"type":"…","payloadJson":"{…}","branch":"main","expectedRevision":R}
# 读口：GET /api/social/population?q=&r= / GET /api/economy/overview
```

| 配置 | 内容 | md5 |
|---|---|---|
| `/tmp/w4acc/shell-tp.json` | three-powers / checkpointInterval=1 | `8d877ef31c88e149632692d8fe55f767` |
| `/tmp/w4acc/shell-sw.json` | small-world / checkpointInterval=1 | `7c1a82f8507e27d74287e8d1f558158b` |

**运行清单**（**一次只有一个世界实例**、一次只有一个 Maven；端口 103xx/104xx）：

| tag | 世界 | 推进 | 端口 | 日志档 | Δrev | 墙钟(s) | HTTP | 结果 dump md5 |
|---|---|---|---|---|---|---|---|---|
| `tp240trace` | three-powers | 0→240 单次 + 37 格 GET | 10301 | economy **TRACE** + trace TRACE + social DEBUG | 1 | 37.050 | 全 200 | `e42e703f4e2b…` |
| `tpslide` | three-powers | 0→32→33→132→133（4 段，4 dump） | 10311 | economy DEBUG | 4 | 14.897 | 全 200 | `d62e3e5893fc…` |
| `gap118` | three-powers | 0→118 单次 + 37 格 GET | 10341 | economy INFO | 1 | 6.755 | 全 200 | `8b923455769a…` |
| `orderA` / `orderB` | three-powers | 240→241→242→243（**同基态两份 store 副本，两进程各跑一遍**） | 10321 / 10331 | economy TRACE | 4 / 4 | 1.475 / 1.587 | 全 200 | 两份逐日 dump **字节相同** |
| `b2one` | small-world | 0→360 单次 | 10430 | INFO | 1 | 7.404 | 200 | `7f0704b2e87b…` |
| `b2seg` | small-world | 0→120→240→360 | 10434 | INFO | 3 | 9.537 | 全 200 | `17ccaf1ee1d6…` |
| `b2daily` | small-world | **逐 tick 360 次**（`--prune`） | 10438 | INFO | 360 | 31.660 | 全 200 | `c1625f718bc7…` |
| `perf-sw-1..3` | small-world | 0→360 单次 ×3 | 10461–63 | INFO | 1 | 7.103 / 7.073 / 7.246 | 200 | 同 `7f0704b2e87b…` |
| `perf-tp360` | three-powers | 0→360 单次 | 10330 | INFO | 1 | 23.748 | 200 | `0e26df94d7f4…` |
| `ab-w4-1..3` / `ab-w1-1..3` | small-world | 0→360 单次，**两 jar 交替 A/B** | 10471–73 / 10481–83 | INFO | 1 | 见 §2.6 | 200 | — |

### 1.3 脚本（全在 `/tmp/w4acc`，**未落仓**）

| 脚本 / plan | 作用 | md5 |
|---|---|---|
| `runner.py` | 起服务→等就绪→按 plan 推进/发命令/落 dump/HTTP 只读探针→收进程；结果含 revisions 表 | `f5a2ab48e341142ad408e8d4a0c7f33d` |
| `canon2.py` | checkpoint→只含状态的规范串（剔各模块 ref/timestamp）+ 逐叶深比对（与 W3 同一把尺） | `a565a5c506c29ed06c4e63d2ce5b3c4d` |
| `recompute_order.py` | **独立复算**开盘前结算序（家户净值 + 债务估值口径回代码核）并与运行期日志比对 | `364600304918ab8f12bfad8cf550a912` |
| `conserve.py` | 守恒读数（货币/货物/债务/人口） | `8f97f8e6a723c22c80bccc48a8d588d3` |
| `plan-tp240trace.json` | 0→240 + 37 格 GET | `74bb6af9e75e31e2aaaca4cc6fbeb14f` |
| `plan-tpslide.json` | 4 段 + 4 dump（滑落前后） | `2c2075afc894a4ca33bd785f4b908942` |
| `plan-order243.json` | 240→243 + 3 dump | `7e2b87b9a450c6ff79a9315615fa1fcc` |
| `plan-gap118.json` | 0→118 + 37 格 GET | `6a52ee1098d61155296f7067818b4a06` |
| `plan-b2-{one,seg,daily}.json` | B2 三份 | `cb451e03736023d6a62e0db9b7cdae40` / `53d4d010a14d142b4b2545e2afc338e9` / `8a1a19727328428b25341df33169abad` |
| `perf-ab.sh` / `runs-more.sh` / `runs-phase2.sh` | 各轮驱动 | — |

**读数四路**：① `<store>/checkpoints/main/<rev>.json` 全量信封；② `revisions` 表（Revision Δ 的真数）；③ 服务进程 stdout 的具名事件日志；④ HTTP 读口。

**★ 装置自证**：`canon2.py` 是 W3 验收用过的同一脚本（md5 与 W3 报告 §1.3 所列逐字相同），本批直接复用它做 B2 对比 ⇒ 与 W3/W2 的 md5 口径是同一把尺。

---

## 2. 逐项真数字

### 2.1 项 1 —— 纯函数确定性：同一状态 ⇒ 同一结算序（跨进程）

**装置**：把 `store-tp240trace`（rev 2 = day 240 的完整档）复制成两份（`store-orderA` / `store-orderB`），
两个**独立进程**（端口 10321 / 10331，日志档 `economy=TRACE`）各自从同一天起推进 `240→241→242→243`。

| 读出 | 进程 A | 进程 B | 判定 |
|---|---|---|---|
| 结算序日志行（`MARKET_ORDER_APPLIED` + `WHY` + `HOUSEHOLDS`，**去掉墙钟戳后的内容**）md5 | `d3caeeb415647b8ac66ae0278fa39828` | `d3caeeb415647b8ac66ae0278fa39828` | **逐字节相同** |
| day 241 存档 md5 | `e125aaeb0385bed5eae024b3bea7c7d2` | 同 | 相同 |
| day 242 存档 md5 | `dc1793ec375bc426042f930e8ad3d2fa` | 同 | 相同 |
| day 243 存档 md5 | `d82fc636ff9acadc9c58d6bac1fa43f8` | 同 | 相同 |
| 全日志（56074 行）去墙钟戳后逐行 diff | — | — | 只差 **24 行**，全是进程身份：`run-shaded` 快照路径、`SHELL_*` 的 store/端口、`GUI_ACCESS` 的 millis、3 条 `ADVANCE_END` 的 commandId/correlationId（UUID） |

**逐户序前 N 项**（day 243，两进程同值，`rank:household=净值`）：

```
0:hh-3_0-urban-rich_peasant=13524000   1:hh--3_3-urban-rich_peasant=12059000
2:hh-unit:gov-tp-silver=7744000        3:hh--1_1-rural-landlord=3539000
4:hh-0_2-rural-poor_peasant=2449000    5:hh-1_0-rural-landlord=2028000
6:hh-0_0-rural-landless_laborer-displa… （共 159 户，全序；末位 = 名次 158）
```

**区序**（同日，`rank:zone=上周期/本周期`）：`0:c-tp-copper=309784/0, 1:c-tp-silver=35732/0, 2:c-tp-gold=28191/0`。
**判定：绿** —— 同一 dump、同一读口（`MARKET_ORDER_*` 日志）、两个进程，序与存档都逐字节一致。

### 2.2 项 2 —— ★ 禁自指（负向）

#### (a) 代码级证据：快照生成点 vs 当轮写点

| 环节 | file:line（HEAD `3b380e3a`） | 说明 |
|---|---|---|
| **快照生成** | `EconomySettlement.java:2317-2319` `SettlementOrderBook.snapshot(day, topology, markets, householdEconomies, debts, accounts, dayView)` | 在市场轮闸门内、**`clearOncePerCycle` 之前**构造一次；结果作**不可变参数**传入下一行 |
| 当轮唯一大动作 | `EconomySettlement.java:2321` `MarketSettlement.clearOncePerCycle(…, ordering)` | 撮合 + **自适应价写回**（`MarketSettlement.java:2359` `adaptiveNextPrice` → `withPrice`，`:2340-2369`）；期间**没有**任何代码回写 `ordering`（该类无 setter、字段 final） |
| 统计簿落盘 | `EconomySettlement.java:2648` `MARKET_ROUND_STATS_RECORDED` | 在 `clearOncePerCycle` **之后** |
| 阶层滑落 | `EconomySettlement.java:2702` `ClassSlideSettlement.settleAtMarketRoundEnd(…)` | 更后 |
| 家户序的输入集 | `SettlementOrderBook.java:168-216`（家户循环） | 只读 `accounts` / `marketTables` / `topology` / `householdEconomies` / `debts` —— **`statsView` 一列都没进** |
| 区序的输入集 | `SettlementOrderBook.java:218-239`（区循环） | 只读 `MarketRoundStatsBook.previousCycleTurnover` / `cycleTurnover` |

#### (b) 可复算的对照（three-powers 0→240，96 个市场轮）

用运行期自己的两个读数口互证：`MARKET_ORDER_WHY` 打印的窗口值 ×  `MARKET_ROUND_STATS_DETAIL` 打印的**逐轮**成交额。

| # | 对照 | 结果 |
|---|---|---|
| 1 | 用打印出来的 `(上周期, 本周期, 区id)` **独立复算**区序 == 运行期实际区序 | **0 / 96 轮不符** |
| 2 | 快照窗口 == "**严格过去**的逐轮成交额之和"（本周期列 + 上周期列） | **309 / 309 项吻合** |
| 3 | **反事实**：若把**当轮**成交额也计入 ⇒ 区序会变的轮数 | **4 / 96 轮**（day **3 / 15 / 20 / 28**，四条逐一轮出） |

反例原文（day 3，本轮真有 66 笔成交）：

```
实际序（运行期用的）      = c-tp-copper, c-tp-gold, c-tp-silver        ← 三区上周期=0、本周期=0 ⇒ 退区 id 序
计入当轮成交额后的序      = c-tp-silver, c-tp-copper, c-tp-gold        ← (copper 3206, gold 3206, silver 3223)
```

⇒ 这一轮**真的有当轮成交**（66 笔；三区名义成交额 3,206 / 3,206 / 3,223 毫），而运行期排序用的是"当轮之前"的窗口（三区都是 0）。

#### (c) 家户侧：用 **dump 独立复算** 对照"有当轮成交 vs 无当轮成交"

`recompute_order.py` 按 `SettlementOrderBook.valuate` / `PrimaryModeRanking.inventoryMilli` / `DebtValuation.commonUnitValue`
的**同一算式**从存档现算 159 户净值与序（day 243 那一轮，本轮 `fills=46`）：

| 复算所用状态 | 逐值完全一致（名次+净值） | 名次一致 | Σ\|Δ净值\| | 最大 \|Δ\| |
|---|---|---|---|---|
| **d242 = 开盘前**（= 快照真正读的时点） | **138 / 159** | 155 / 159 | 23,000 | 2,000 |
| d241（更早一天） | 122 / 159 | 150 / 159 | 78,000 | 32,000 |
| **d243 = 收盘后**（含本轮自己的 46 笔成交） | **17 / 159** | 60 / 159 | 6,849,000 | 424,000 |

⇒ 运行期用的序是"**不含本轮成交**"的那一份（差 2 个数量级）；**若自指，它就应当等于收盘后那一份**。
★ 残余（21 户、≤ 2,000 = 2 毫的库存估值）**未完全定位**：当日 `MARKET_LIFE_RESERVE`（day 243 共 17 行）等日初/轮内预留
发生在存档边界与快照点之间，量级与符号（复算值偏高）都与之相符，但没有逐户读数可对 ⇒ **如实记，不声称"逐值闭合"**。

#### (d) 未做（如实标注）

- **未做世界级 GM"人为放大当轮成交额"**：能写成交额的 GM 口不存在 —— `EconomyAdjustTool` 的 12 条白名单（其描述原文列出）
  **不含** `marketRoundStats`（"派生读数不可由 GM 调整工具直写"），唯一的价格写口 `economy.SetMarketPrice` 改的是**价表（输入）**、
  不是"当轮成交额"。⇒ 本项的负向由 (a)(b)(c) 承担，(d) 记"未做"。
- **未做"两块基态：有当轮成交 / 无当轮成交"的 A/B**：只能靠改输入（搬走库存/改价/撤需求）造出"零成交轮"，
  而那会同时改掉排序的**输入**（净值/价表）⇒ 不是干净的负向，**不做**（改用 (c) 的收盘后对照实现同一意图）。

**判定：绿**（世界级窗口负向 + 反事实反例 + dump 复算对照齐；GM 放大变体如实标"未做"）。

### 2.3 项 3 —— 稀缺品"先到排序靠前的户"

**(a) 同格抢同一批供给的逐户样例**（three-powers day 3，hex `-1,-1`，商品 grain；该格卖方只有一户）：

| 结算序名次 | 户 | 想要 | 拿到 | 归因 | 实际成交原文 |
|---|---|---|---|---|---|
| **#6** | `hh--1_-1-rural-poor_peasant` | 3,484,345 | **225,656** | LOGISTICS_CAPACITY（未满足的那部分） | `MARKET_FILL day=3 from=-1,-1 to=-1,-1 commodity=grain seller=hh--1_-1-rural-landless_laborer-displaced buyer=hh--1_-1-rural-poor_peasant quantity=225656 …` |
| #36 | `hh--1_-1-rural-middle_peasant` | 3,070,250 | **0** | LOGISTICS_CAPACITY | — |
| #86 | `hh--1_-1-rural-rich_peasant` | 1,262,345 | **0** | LOGISTICS_CAPACITY | — |
| #123 | `hh--1_-1-rural-landlord` | 19,345 | **0** | LOGISTICS_CAPACITY | — |

该格该商品**只有 1 笔成交**（225,656 毫）——卖方在该格能上架的量被**名次最靠前的那一户**一次取完。

**(b) 全样本统计（96 轮）**：把每个 `(day, 商品, 格)` 当作一个竞争组，筛出"**拿到货的买户名次全部优于没拿到的**"的组：

```
命中组数 = 65   （例：day3 grain hex=-1,-1 最差拿到者名次=6、最好没拿到者名次=36；
                        day3 tool hex=3,0  最差拿到者名次=83、最好没拿到者名次=117）
```

**(c) 大稀缺场景**（day 113 grain，全 3 区 37 格）：有订单买户 **196**、想要合计 **251,924,476**、拿到合计 **130,225（0.0517%）**；
拿到 > 0 的 **17 户**、拿到 = 0 的 **179 户**；未拿到归因 `LOGISTICS_CAPACITY=149 / ALGORITHM_UNCOVERED=30`。

**(d) `MARKET_PAIRING_SHORTFALL` 归因读数**（day 3，c-tp-copper，grain）：

```
buyers=64 sellers=21 buyTakingRightsMilli=117912185 sellOfferedMilli=23114988 executedMilli=3087271 lostMilli=20027717
topBuyerAllocatedMilli=3477500 topBuyerHousehold=hh--3_0-rural-poor_peasant topBuyerHex=-3,0 topBuyerBlocked=LOGISTICS_CAPACITY
stalledSellerIndex=10 stalledSellerHex=0,-3 buyersWithZeroPart=0 stopReason=execute-trade-returned-zero
```

**★ 边界（如实）**：three-powers 里 `LOGISTICS_CAPACITY` 是最大未成交归因 —— **多数格没有承运运力**，跨格 lane 结构上不通，
所以"名次靠前先拿到"只在**同一卖方格内**的竞争里可观测（(a)(b) 就是这种组）；(c) 的 day113 里名次 #4/#7/#9 拿不到货，
不是排序失效，而是它们所在格**没有卖方可成交**（lane 不通）。该机制 W4 账本 G-7 已具名，本批照实记。
**判定：绿**（有逐户样例、有全样本组数、有归因读数；含上述边界说明）。

### 2.4 项 4 —— 阶层滑落真的发生、且不跳级、速度随债/资比

`three-powers 0→240`（96 个市场轮）实测：**`CLASS_SLIDE` 63 条 / 60 户**；`CLASS_SLIDE_ROUND` 96 条（= 市场轮数，逐日 1 次）。

**96 轮累计（逐户×轮）**：`evaluated=18396, slid=63, skippedNoDebt=6638, skippedNoPosition=24, skippedCooldown=4956, skippedNoLowerTarget=6715, unpricedDebtHouseholds=2759`。

#### ① 真实滑落样本（`CLASS_SLIDE` 日志 + 存档里 `classStandings` 前后值）

**样本 1 —— `hh-0_0-rural-landlord`（day 33，市场轮触发 `LOW_GRAIN_STOCK`）**

| | `currentPositionId` | `lastTransitionDay` | `reason` |
|---|---|---|---|
| 前（dump `tpslide-pre33` = rev 2） | `tenancy-fixed-kind-landlord` | **0** | `production-runtime:seedClassStanding` |
| 后（dump `tpslide-post33` = rev 3） | **`tenancy-fixed-kind-tenant-operator`** | **33** | `class-slide-speed:tenancy-fixed-kind-landlord->tenancy-fixed-kind-tenant-operator:debtRatioPerMille=78050000:speedPerMille=1000:elapsedDays=33` |

日志原文：`CLASS_SLIDE day=33 household=hh-0_0-rural-landlord from=tenancy-fixed-kind-landlord to=tenancy-fixed-kind-tenant-operator fromLevel=OWNER/SURPLUS_RECEIVER(4,3) toLevel=OPERATOR/SURPLUS_RECEIVER(2,3) levels=1 debtRatioPerMille=78050000 speedPerMille=1000 elapsedDays=33 requiredDays=5 assetCommonValue=0 debtCommonValue=78050`

**样本 2 —— `hh--3_3-urban-landlord`（day 133，同一户的第二次滑落）**

| | `currentPositionId` | `lastTransitionDay` |
|---|---|---|
| 前（`tpslide-pre133` = rev 4） | `merchant-principal` → 已滑到 `merchant-self-employed` | 93 |
| 后（`tpslide-post133` = rev 5） | **`merchant-porter`** | **133** |

日志原文：`CLASS_SLIDE day=133 household=hh--3_3-urban-landlord from=merchant-self-employed to=merchant-porter levels=1 debtRatioPerMille=1372 speedPerMille=137 elapsedDays=40 requiredDays=37`。
两处存档各有 10 / 5 条 `classStandings` 条目发生变化（其余同轮读数一并可查）。

#### ② 一个市场轮内同一户最多降一级（逐户跨轮序列）

| 检查 | 实测 |
|---|---|
| 同一 `(户, 日)` 的滑落次数最大值 | **1**（63 条滑落里无一对同日双降） |
| `levels` 字段 | **63/63 全 = 1** |
| 市场轮与滑落结算的对应 | `MARKET_ROUND_START` 96 行 / 96 个**不同日**；`CLASS_SLIDE_ROUND` 96 行、**日期集合与市场轮完全相同** ⇒ 一轮一次调用 |
| 滑落发生的日 | 33,53,55,65,68,70,83,85,93,108,113,115,120,123,128,133,138,140,150,153,158,163,168,170,173,178,183 —— **全部是市场轮日** |
| 代码面 | `EconomySettlement.java:2702` 每轮调用一次；`ClassSlideSettlement.java:242` 对同一户**只 `put` 一次**、且只写 `ClassLadderBook.nearestLower` 给出的**最近严格低位** |
| 逐户跨轮序列（≥2 级的 3 户全列） | `hh--3_3-urban-landlord`：day93（principal→self-employed, req 88）→ day133（self-employed→porter, req 37；**距上次 40 天**）<br>`hh-3_0-urban-landlord`：day108（req 99）→ day168（req 53；**距上次 60 天**）<br>`hh-0_-3-urban-landlord`：day120（req 81）→ day183（req 61；**距上次 63 天**） |

#### ③ 债/资比不同 ⇒ 速度不同（"每级天数"实测）

**算式逐值自证**（对全部 63 条）：`speed = min(1000, ⌊ratio×100÷1000⌋)`、`requiredDays = ⌈5×1000÷speed⌉` ⇒ **0 / 63 不符**。

| 户（债/资比不同） | 债/资比‰ | speed‰ | 该次 requiredDays | **实测每级天数** |
|---|---|---|---|---|
| `hh--3_3-urban-landlord`（第 2 级，day133） | 1,372 | 137 | 37 | **40 天**（day93→133） |
| `hh-3_0-urban-landlord`（第 2 级，day168） | 950 | 95 | 53 | **60 天**（day108→168） |
| `hh-0_-3-urban-landlord`（第 2 级，day183） | 824 | 82 | 61 | **63 天**（day120→183） |
| `hh-0_1-rural-landlord`（第 1 级，day33） | 1,666 | 166 | 31 | 首次（elapsedDays 33 ≥ 31 当天触发） |
| `hh-0_0-rural-landlord`（第 1 级，day33） | **78,050,000**（资产=0） | 1,000（上限） | **5** | 首次（elapsedDays 33 ≥ 5） |
| 最慢档样本 `hh--2_-1-rural-landless_laborer-displaced`（day170） | 301 | 30 | **167** | 首次 |

**逐轮决策序列（`CLASS_SLIDE_SPEED` TRACE，`hh--3_3-urban-landlord` 的第 2 级前后）** —— 这是"速度 = f(债/资)"的逐轮现场：

| day | 资产 | 负债 | 债/资比‰ | speed‰ | elapsedDays | requiredDays | decision |
|---|---|---|---|---|---|---|---|
| 108 | 1,414,000 | 1,196,000 | 845 | 84 | 15 | 60 | cooldown |
| 120 | 2,028,000 | 1,762,000 | 868 | 86 | 27 | 59 | cooldown |
| 125 | 1,395,000 | 1,378,000 | 987 | 98 | 32 | 52 | cooldown |
| 128 | 1,339,000 | 1,498,000 | 1,118 | 111 | 35 | 46 | cooldown |
| 130 | 1,283,000 | 1,573,000 | 1,226 | 122 | 37 | **41** | cooldown（37 < 41） |
| **133** | 1,227,000 | 1,684,000 | 1,372 | 137 | **40** | **37** | **slid-one-level**（40 ≥ 37） |
| 135 | 1,173,000 | 1,757,000 | 1,497 | 149 | 2 | 34 | cooldown（elapsed 归零） |
| 138 | 1,117,000 | 1,858,000 | 1,663 | 166 | 5 | 31 | cooldown |

⇒ 每轮只判一次、只降一级；`elapsedDays` 在滑落后归零重新积分；`requiredDays` 随**当轮**债/资比逐轮变（41 → 37 → 34）。

★ 实测"每级天数"= `requiredDays` 向上取到**市场轮网格**（市场轮按 ~2.5 天交替出现：3/5/8/10/13…），与算式一致。

**边界（如实，非缺陷）**：`skippedNoLowerTarget` 占评估数 **36.5%**（6,715 / 18,396）—— "级"的唯一既有语义是
`ProductionRole` 的**角色元组**（relationToMeans × surplusRole），`poor/middle/rich_peasant` 元组相同 ⇒ **互相没有高低**，
"富农→中农→贫农"这条社会阶梯**在模型里表达不出来**（要 `ruleExtensions.downwardPositionId` 显式写）。W4 账本 §一-19/§五-3 已具名上报。
**判定：绿**（① 两户 before/after 存档 + 日志齐；② 不跳级三重证据齐；③ 速度公式 63/63 自证 + 三户实测每级天数）。

### 2.5 项 5 —— 时点：滑落发生在**市场轮最末**（在价格写回/统计簿之后）

**同一轮（day 33）的日志行号与墙钟戳（`tp240trace.log`，2,627,417,600 字节）**：

```
1003963  07:24:30.297  DEBUG  MARKET_ROUND_START        day=33 trigger=LOW_GRAIN_STOCK
1004095  07:24:30.301  INFO   MARKET_ORDER_APPLIED      day=33 buySlots=559 orderedHouseholds=203 …   ← 开盘前快照序
1004096  07:24:30.301  DEBUG  MARKET_ORDER_WHY          day=33 zones=[0:c-tp-silver=18422/0, …]
1065942  07:24:30.592  DEBUG  MARKET_REPORT             day=33 fills=40 …                            ← clearOncePerCycle 已返回
1065943  07:24:30.592  INFO   MARKET_ROUND_STATS_RECORDED day=33 markets=3 …                          ← 统计簿落盘
1065944  07:24:30.592  DEBUG  MARKET_ROUND_STATS_DETAIL day=33 marketId=c-tp-copper …
1066048  07:24:30.595  INFO   CLASS_SLIDE               day=33 household=hh-0_0-rural-landlord …      ← 滑落
1066160  07:24:30.596  INFO   CLASS_SLIDE_ROUND         day=33 evaluated=203 slid=4 …
1066161  07:24:30.596  DEBUG  CLASS_SLIDE_SETTLED       day=33 trigger=LOW_GRAIN_STOCK … at=market-round-end-after-price-writeback
```

**价格写回的位置（代码面）**：它在 `MarketSettlement.clearOncePerCycle` 内（`MarketSettlement.java:2340-2369`；唯一写回路径的类注在 `:247`），
而该调用在 `EconomySettlement.java:2321`，**返回之后**才发 `MARKET_REPORT`（1065942）⇒ 价格写回 **<** 统计簿（1065943）**<** 滑落（1066048）。
另：63 条滑落**全部**落在市场轮日（`CLASS_SLIDE_ROUND` 的日期集合 == 市场轮日期集合），非市场轮日 0 条。
**判定：绿**（同日行号 + 墙钟 + 代码调用链三重一致）。

### 2.6 项 6 —— 性能

**(a) Revision Δ（一次 `advance` 一次落盘，不随天数增长）**

| 命令 | Δ | 行数 | 耗时 |
|---|---|---|---|
| small-world `0→360` 单次（b2one / perf-sw-1..3 / ab-w4-1..3） | **1** | 创世 + 1 | 见 (b) |
| three-powers `0→240` 单次（tp240trace，**TRACE 日志**） | **1** | 创世 + 1 | 37.050 s（含 2.6 GB 日志开销） |
| three-powers `0→360` 单次（INFO） | **1** | 创世 + 1 | **23.748 s** |
| small-world `0→360` 三段 | 3（= 推进次数） | 创世 + 3 | 9.537 s |
| small-world 逐 tick 360 次 | 360（= 推进次数） | 创世 + 360 | 31.660 s |

**(b) 同世界墙钟（small-world，`advance 0→360` 单次，同 config，`INFO`）**

| 侧 | jar | 样本（s） | 中位 |
|---|---|---|---|
| **本会话 W1 基线（交替 A/B）** | `a90e11a9…` | 6.994 / 7.222 / 8.875 | **7.222** |
| **W4（交替 A/B）** | `8d3c37ba…` | 7.372 / 9.101 / 7.434 | **7.434** |
| W4（全部 7 个样本） | `8d3c37ba…` | 7.073 / 7.103 / 7.246 / 7.372 / 7.404 / 7.434 / 9.101 | **7.372** |

⇒ 同会话 A/B：**W4 中位 7.434 vs W1 基线 7.222（+2.9%）**；对照 W3 报的同会话 A/B 中位 **7.250** ⇒ W4 +1.7%。
样本极差 6.994–9.101（±15% 噪声，与 W3 记的 ±13% 同带）⇒ **无显著性能劣化**。

**(c) 结构证据**：`git show 3b380e3a` 的 diff 里 `checkpoint|persist|.save(|saveState|writeCheckpoint` 命中 **0 行**；
diff 范围 = 8 个文件（3 个新 economy 类 + `MarketSettlement` / `EconomySettlement` / `EconomyLiquidationSettlement` / `ModeMigrationPolicy` + 账本），无存储路径改动。
**判定：绿**。

### 2.7 项 7 —— B2 分段等价（G3，不许丢）

| | 规范状态 md5（`canon2.py`，剔 ref/timestamp） | 原始文件 md5 | 差异叶 |
|---|---|---|---|
| `0→360` 一次（b2one） | **`11a4dba899696a4d9e6d2a35c1efb0a8`** | `7f0704b2e87b231c939a2e44dd9a0af1` | — |
| `0→120→240→360` 三段（b2seg） | **`11a4dba899696a4d9e6d2a35c1efb0a8`** | `17ccaf1ee1d6d7208156e3d992e412be` | — |
| **逐 tick 360 次**（b2daily） | **`11a4dba899696a4d9e6d2a35c1efb0a8`** | `c1625f718bc732522e5128abb951667f` | — |
| one vs seg / one vs daily 逐叶深比对 | — | — | **0 / 87,137**（两次都是 0） |

原始文件 md5 三者不同 = 只差各模块 `ref.revision` / `timestamp` 戳（与 W2/W3 报告同一现象、同一口径剔除）。
**判定：绿**。

### 2.8 项 8 —— ★ 复核 W3 的 D1 修复（`GET /api/social/population` 不 500 + MORTALITY 灯可读）

**探针**：对 three-powers 全部 **37 格**逐格 GET（两次运行：`gap118` day 118、`tp240trace` day 240）。

| 运行 | HTTP | 灯分布（格数） |
|---|---|---|
| `gap118`（day 118，缺粮期） | **37 / 37 = 200**（无 500） | FOOD **34** / CLOTH 37 / **MORTALITY 36** / LABOR_BURDEN 4 |
| `tp240trace`（day 240） | **37 / 37 = 200**（无 500） | FOOD 1 / CLOTH 30 / DEBT 30 / **MORTALITY 13** |

**原始响应片段**（`gap118-get*.json`，hex `q=-3,r=0`，HTTP 200）：

```json
{"q": -3, "r": 0, "population": 135,
 "crisis": [
   {"kind": "FOOD", "evidence": {"grainUnmetMilli": 850570, "cycleDays": 120, "grainSatisfactionPerMille": 229,
     "elapsedDays": 118, "stressByAgeBracket": {},
     "stressByAgeBracketUnavailable": "生理压力字段已退役（2026-10-09 每 tick 生死计划 §4）…本读口不再有数"}},
   {"kind": "CLOTH", "evidence": {"clothSatisfactionPerMille": 0, "clothUnmetMilli": 40231, "elapsedDays": 118, "cycleDays": 120}},
   {"kind": "MORTALITY", "evidence": {"deathsThisCycle": 16, "mortalityPerMille": 118, "population": 135}},
   {"kind": "LABOR_BURDEN", "evidence": {"utilizationPerMille": 992, "allocatedMilli": 178573, "availableMilli": 180000}}]}
```

- **FOOD 灯**（原崩溃路径）：`stressByAgeBracket` 现在是**空对象**而不是 `null`（`CrisisMonitor.java:201-205` 的注释与实现一致；`Map.copyOf` 守卫仍在 `Light` 构造期），
  34/37 格同时点亮 FOOD ⇒ **原 500 触发面被真跑到，且不再 500**。
- **MORTALITY 灯**：36 格可读，`mortalityPerMille` 真值（如 hex(-3,0) = 118‰/周期、`deathsThisCycle=16`、`population=135`）——
  W3 的 D1 里"被 FOOD 灯整个遮住"的现象**已消除**（同一响应里 FOOD 与 MORTALITY 并存）。
**判定：绿**。

### 2.9 项 9 —— ★ 老守卫的实测分布（给测试批当输入）

**世界 = three-powers，`0→240` 单次 advance，`-Dsimos.economy.logLevel=DEBUG`（`MARKET_REPORT` 行）**

| 读数 | 值 |
|---|---|
| 市场轮数 | **96** |
| **零成交轮次数** | **0** |
| 最小 / 中位 / 最大 成交笔数 | **17 / 35.0 / 131** |
| 分布（笔数:轮数） | 17×10, 18×1, 21×7, 22×9, 23×5, 24×12, 31×1, 34×1, 35×3, 36×4, 37×8, 38×1, 39×2, 40×13, 41×2, 44×1, 53×8, 54×1, 55×3, 56×2, 66×1, 131×1 |
| 逐笔交叉验证 | `Σ MARKET_REPORT.fills = 3260` == `MARKET_FILL` 行数 3260；**逐轮 0 处不符** |

**与 W4-fix2 账本 G 节对照**：

- 账本 G-3 报的分布（逐项原样抄录）与本轮实测分布 **逐项完全相同**（`{17:10, 18:1, 21:7, …, 131:1}`，合计 **96**）⇒ 世界可复现，我的口径与账本同源。
- 账本说的"**最小 17 笔**" ⇒ 实测一致（min = 17）。
- ★ **账本把轮数写成"48 轮"，但它自己那份分布逐项求和 = 96** —— 与我实测的 96 个市场轮（0→240）一致 ⇒ 判为**账本标签笔误**（见 §3-D1），不是数值冲突。

**给测试批的输入判断**：

1. 判据若改成 **"零成交轮次 = 0"**：在 three-powers `0→240` 上**会通过**（实测 0 个零成交轮），且比现在的"每段 > 0"**更硬**
   （账本 G-1 记的 W4 初版有 **15 轮零成交**却让"每段 > 0"全绿 —— 那段账本自己的轮数标签写作 48 轮，见 D1）。
2. 但"零成交 = 0"**仍是钝判据**（它只管有没有，不管有多少）：建议**同时钉一条基线**——`0→240 最小 17 笔` 或直接钉上面那份分布指纹；
   本世界该分布**逐进程可复现**（见 §2.1：两个独立进程连存档都逐字节相同）⇒ 基线守卫在本机是稳的。
3. 若只在 **0→120** 上跑（W4-fix2 的 `EconomyCycleHealthTest` 逐段口径）：**前 48 轮（day 3–120）实测零成交 = 0、min = 17、中位 22、max 131**；
   后 48 轮（day 123–240）零成交 = 0、min **34**、中位 40、max 56 ⇒ 两段都满足"零成交轮次 = 0"，但**最小笔数随周期抬升**（17 → 34），
   所以基线守卫要**分段钉**（或钉整段分布），不要只钉一个全局最小值。

### 2.10 项 10 —— 守恒（货币 / 人口 / 债务 + 死者债资处置）

**世界 = three-powers，sealed genesis（rev 1）→ day 240（rev 2）**

**(1) 人口：精确闭合**

```
6350（创世） + 259 births − 679 deaths + (TRANSFER_OUT 3000 == TRANSFER_IN 3000，净 0) = 5930 ✓ = day240 实存
Σ Social households 人口 = 5930 = Σ Economy classes.population = 5930 ；逐户 0 处不符（342 户全比）
```
（事件源：`social.populationEvents` 4206 条，按 type 聚合：DEATH 679 / BIRTH 259 / TRANSFER_OUT 1416 事件=3000 人 / TRANSFER_IN 1416 事件=3000 人。）

**(2) 货币：逐值不变（本批无铸币路径 ⇒ 唯一合法增量为 0）**

| 币 | 创世 | day 240 |
|---|---|---|
| copper | 136,600 | **136,600** |
| gold | 129,400 | **129,400** |
| silver | 424,600 | **424,600** |
| **合计** | **690,600** | **690,600** |

**(3) 债务：真数**

| | 创世 | day 240 |
|---|---|---|
| 合同数 | 0 | **755**（未偿本金 > 0 = 483；`DEFAULTED` 508、`SETTLED` 82） |
| 未偿本金（原始单位，跨单位不相加） | 0 | grain 5,744,152；fiber 285,395,384；money copper 1,997,098 / gold 1,220,276 / silver 1,343,282 |

**(4) 死者债资处置（世界里真发生）** —— ★ 本读数**复核过一次**：第一版把 `population == 0` 一律当"死绝"，得出"85.1% 的货币在死者账上"，
**那是错的** —— 该口径把 `hh-gov-*`（国库户，人口=0 是设计如此）与 136 个**创世就没播人口**的行一起算了进去。按类别拆开后的真数：

| 类别（`classes` 行） | 行数 | 货币（毫） | 占全世界货币 |
|---|---|---|---|
| `hh-gov-*` **国库户**（人口=0 是设计如此） | 3 | 576,960 | **83.5%**（创世 = 600,000 / 86.9%） |
| `hh-unit:*` 单位户 | 3 | 23,040 | 3.3% |
| **真家户 · `population == 0`** | **180**（其中 **136 个创世即为 0**、**44 个运行中死绝**） | **10,545** | **1.5%** |
| 真家户 · `population > 0`（存活） | 156 | 80,055 | 11.6% |

- **死绝户（44 户）与"从未播人口"的 136 行合起来**（180 行）持有：`tool` **87.7%**、`fiber` **35.3%**、`grain` **14.6%**、`cloth` **9.1%** 的全球库存 —— **货物大量停在人口=0 的行上**。
- 它们名下未偿债务 **9 笔**（全是货币债，原始本金合计 474,776；对照存活家户 474 笔 / 295,225,416）——
  例：`hh--1_-1-rural-middle_peasant` 95,316、`hh-0_-3-rural-poor_peasant` 76,590。
- **核销 / 清算**：本批世界日志里 `CLASS_DECLINE`（E5b 阈值支路）**0 命中** ⇒ 确已删除；债的核销路径 W3 §2.8 已核
  `writeOffDebtsForDeaths` 在 `src/main` 零调用点，本批**未新增**任何核销/继承路径（资产与债都挂家户账）。

⇒ 货币**守恒精确**，其中 **83.5% 在国库户**（设计如此）；**人口=0 的行**仍持有相当比例的货物与 9 笔未偿债且无人接管 ——
**既存行为**（W3 §2.8 同款结论），不计 W4 缺陷。★ **读数纪律**：`classes.population == 0` **不能**当"户绝"判据
（国库户 / 单位户 / 创世未播人口的行都是 0）—— 本批自己先踩了一次，见 §3-D2。
记为"继承观察"（§3-D2），不计 W4 缺陷。
**判定：绿**（含"死者债资"读数；货物仍只能单边核对，见 §5）。

---

## 3. 缺陷 / 异常清单（**只报不修**，每条最小复现）

> 本批**未发现 W4 引入的功能缺陷**（10 项判据全过）。以下 7 条：**D1** = 账本口径笔误（文档面）；
> **D2/D4/D5/D6** = 既存观察 / 读数缺口 / 口径注（D2 含**本批自己的读数更正**）；**D3** = 已具名的机制边界（"级"的语义）；
> **D7** = N-8"国库户参与排序"的**潜在口径缺口**（本世界不可观测）。全部**只报不修**。

### D1 ★【账本口径笔误·文档面】W4-fix2 账本把 96 轮写成"48 轮"

- **现场**：`.superpowers/sdd/2026-10-11-w4-ordering-and-class-mobility/impl-ledger.md` G-1「W4 初版的**全部 48 轮**读数」、G-3「**全 48 轮**无一冻结」；
  但 G-3 自己列的分布（`17×10, 18, 21×7, …, 131`）**逐项求和 = 96**。
- **最小复现**：`python3 -c "d={17:10,18:1,21:7,22:9,23:5,24:12,31:1,34:1,35:3,36:4,37:8,38:1,39:2,40:13,41:2,44:1,53:8,54:1,55:3,56:2,66:1,131:1}; print(sum(d.values()))"` ⇒ `96`；
  而 three-powers `0→240` 的市场轮数实测 = **96**（本报告 §2.9）。**期望**：轮数标签与实际口径一致；**实际**：标签 48、数据 96。
- **影响**：会让测试批把"零成交守卫"的样本量读小一半（判据强度估算错）。**不改账本**（留痕纪律），在此追加更正。

### D2 ★【既存观察 + 本批自己的读数更正】人口=0 的行仍持有货物与未偿债；且 `population == 0` **不能**当"户绝"判据

- **现场**：`dumps/tp240trace-d240.json` ⇒ 真家户里 `population == 0` 的有 **180 行**（**其中 136 行创世即为 0**、44 行运行中死绝），
  持有 `tool` 87.7% / `fiber` 35.3% / `grain` 14.6% / `cloth` 9.1% 的全球库存 + **9 笔未偿债**（474,776）；货币只有 10,545（1.5%）。
  **世界货币的 83.5% 在 `hh-gov-*` 国库户**（人口=0 是设计如此），不是"死人的钱"。
- **最小复现**：`cd /tmp/w4acc && python3 conserve.py dumps/tp240trace-genesis.json dumps/tp240trace-d240.json`（尾段就是逐类别的死者债资读数）。
- **期望 vs 实际**：设计书未规定"户绝则资产/债务如何处置"；**实际** = 原样留在该户账上（W3 §2.8 已记同一现象）。
  ⇒ 列 W6 标定/设计追认项（是"户绝即注销"还是"由继承人/债权人接管"需要口径）。
- ★ **本批自己的更正（写下来免得后人再踩）**：本报告初版把 `population == 0` 一律读作"死绝"，得出"85.1% 的货币在死者账上"——
  **错**。`population == 0` 至少覆盖三类行：国库户（`hh-gov-*`）、单位户（`hh-unit:*`，本例人口>0）、**创世未播人口的行（本例 136 行）**。
  按类别拆开后真数见 §2.10(4)。凡"死者读数"必须先按类别拆。

### D7 【口径缺口·潜在】N-8 说"国库户按同一规则参与排序"，实现里 `hh-gov-*` 被排除在家户序之外

- **现场**：`SettlementOrderBook.java:182` 的 `if (row == null || row.population() <= 0L) continue;` ⇒ 人口=0 的行**不进家户序**；
  实测 `MARKET_ORDER_HOUSEHOLDS` day3 共 **203** 户、其中 `hh-gov-*` **0 户**、`hh-unit:gov-*` **3 户**（名次 200/201/202）；
  而三个 `hh-gov-*` 国库户合计持有 **576,960 毫（世界货币的 83.5%）** —— 若按净值进序，它们会落在最前面（各自的计价币余额在各自区内计入）。
- **最小复现**：`grep " day=3 " key/MARKET_ORDER_HOUSEHOLDS.log | grep -c "hh-gov-"` ⇒ `0`；`SettlementOrderBook.java:180-184`。
- **为什么本世界**没造成可见后果**：three-powers 里国库户**根本不挂家户订单** —— `grep -c "buyer=hh-gov-"` = **0**、`seller=hh-gov-` = **0**，
  政府买单走 mandate 路径且本世界 `GOV_MARKET_MANDATE_BUY_ORDER` **0 行**、`GOV_MARKET_AUTO_ORDERS_SUPPRESSED` 1,440 行。
  ⇒ 若将来国库户直接下 household 单（或 mandate 单落进 `ctx.buys` 的同一名次键），它会以 `UNKNOWN_RANK`（= `Integer.MAX_VALUE`）**排到最后**。
- **建议**：控制方裁定 N-8 的口径 —— 要么让国库户按净值进序（改过滤条件），要么把 N-8 改成"国库户不经家户序（走 mandate/行政路径）"。

### D3 【边界·已具名上报】36.5% 的滑落评估 `no-lower-target`：模型里没有"富→中→贫"这条阶梯

- **现场**：96 轮累计 `skippedNoLowerTarget=6715 / evaluated=18396`（36.5%）。
- **最小复现**：`grep -c "skippedNoLowerTarget" key/CLASS_SLIDE_ROUND.log` + 逐行求和；或看 `ClassLadderBook` 类注（`poor/middle/rich_peasant` 元组相同）。
- **影响**：滑落只在"角色元组严格更低"的位置间发生（如 landlord→tenant-operator、merchant-principal→self-employed→porter）；
  要社会阶梯必须给位置表补 `ruleExtensions.downwardPositionId`（数据/创世面，另批小事）。**建议交用户确认预期**（W4 账本 §五-3 已请求裁定）。

### D4 【读数缺口·既存】市场轮没有"价格写回"的具名日志行

- **现场**：全模块 grep `"…PRICE…"` 事件名只有 `ECONOMY_SET_MARKET_PRICE_*` / `MARKET_ZONE_PRICE_ALPHA_*` 等命令面事件，
  **没有**"本轮自适应写回了哪些价（旧值→新值）"的 DEBUG/TRACE 行。
- **影响**：判据⑤"在价格写回之后"只能靠**代码调用链 + 统计簿行序**证明（本报告 §2.5 已给），没有一条可直接读的事件行。
  建议 W6 补一条 DEBUG（`MARKET_PRICE_WRITEBACK day=… market=… commodity=… from=… to=…`），成本一行。

### D5 【观察·已具名】three-powers 的 `LOGISTICS_CAPACITY` 长期是最大未成交归因，压低"排序"的可见效果

- **现场**：day 113 grain 179 户拿不到货，其中 149 户 `LOGISTICS_CAPACITY`；day 3 的 `MARKET_PAIRING_SHORTFALL` `topBuyerBlocked=LOGISTICS_CAPACITY`。
- **影响**：名次只在**同格**竞争里生效（§2.3 (a)(b) 的 65 个组），跨格拿不到货与名次无关。W4 账本 G-7 已具名；**列 W6 标定**（是否给 three-powers 补承运运力）。

### D6 【口径注·非缺陷】区序量是"跨币不折算的原值相加"

- **现场**：`SettlementOrderBook.java:41-44` 类注要求调用方说清；实测三区各自计价币不同（copper/gold/silver），
  `MARKET_ORDER_WHY` 打印的 `上周期/本周期` 就是**各区自己币种的名义值原样相加后的比较**。
- **判定**：与 W1 统计簿的有意口径一致（不是缺陷）；多币世界的"总 GDP"若要单一尺度，需统计簿加逐币明细（另批）。

---

## 4. 结论（逐项 绿 / 红 / 未验）

| # | 项 | 判定 | 一句依据 |
|---|---|---|---|
| 1 | 纯函数确定性（同状态 ⇒ 同序） | **绿** | 两进程同基态：结算序日志（去戳）md5 `d3caeeb4…` 双方相同；day241/242/243 存档**逐字节相同**；全日志 56,074 行只差 24 行进程身份 |
| 2 | ★ 禁自指（负向） | **绿** | 窗口吻合 309/309、区序复算 0/96 不符、**4/96 轮**"计入当轮就会改序"的反事实反例、家户侧 pre/post dump 复算 138/159 vs 17/159；**GM 放大成交额变体未做**（无写口，如实标注） |
| 3 | 稀缺品先到排序靠前的户 | **绿** | 同格样例 day3 hex(-1,-1)：名次 #6 拿满 225,656、#36/#86/#123 全 0；96 轮共 **65 个**"拿到者名次全部优于没拿到者"的组；含 `MARKET_PAIRING_SHORTFALL` 归因 |
| 4 | 阶层滑落真发生 + 不跳级 + 速度不同 | **绿** | 63 次滑落 / 60 户；`levels` 63/63 = 1、同一户同日最多 1 次、96 轮各一次调用；两户 before/after 存档；速度公式 63/63 自证、实测每级 40/60/63 天 |
| 5 | 时点（市场轮最末，价格写回/统计簿之后） | **绿** | 同日行号：统计簿 1065943 → `CLASS_SLIDE` 1066048 → `SETTLED at=market-round-end-after-price-writeback`；价格写回在 `clearOncePerCycle` 内（2340-2369）早于 `MARKET_REPORT` 1065942 |
| 6 | 性能（Δ=1 + 墙钟） | **绿** | 0→360 单次 Δ=1（small-world 与 three-powers 都验）；A/B 中位 7.434 s vs W1 基线 7.222 s（+2.9%，噪声带内）；three-powers 0→360 = 23.748 s；diff 无存盘路径 |
| 7 | B2 分段等价 | **绿** | 一次 / 三段 / 逐 tick 三份 dump 规范状态 md5 全为 `11a4dba899696a4d9e6d2a35c1efb0a8`，差异叶 **0 / 87,137**（两次比对都是 0） |
| 8 | ★ 复核 W3 D1 | **绿** | 37/37 格 GET 全 200（两个时点各一次）；FOOD 灯 34/37（`stressByAgeBracket={}` 不再 null）；MORTALITY 灯 36/37，`mortalityPerMille` 真值（例 118‰） |
| 9 | ★ 老守卫分布（给测试批） | **绿** | 96 轮、**零成交轮次 = 0**、min/中位/max = 17/35/131；分布与 W4-fix2 账本逐项相同（账本"48 轮"是标签笔误，D1） |
| 10 | 守恒 | **绿** | 人口 6350+259−679=5930（Σ Social == Σ Economy，逐户 0 不符）；货币 690,600 逐值不变（83.5% 在国库户）；债务 0→755 合同/483 未偿；死者债资读数按类别拆开（D2） |

---

## 5. 我没做 / 没验证的（如实）

1. **未做世界级 GM"人为放大当轮成交额"**：不存在能写 `marketRoundStats` 的 GM 口（`EconomyAdjustTool` 白名单 12 条不含它），
   `economy.SetMarketPrice` 改的是价表（输入）而非当轮成交额 ⇒ 该变体**没跑**（项 2 的负向由窗口验证 + 反事实 + dump 复算承担）。
2. **未做"两块基态（有/无当轮成交）"的 A/B**：造"零成交轮"必须改输入（库存/价/需求），不是干净负向 ⇒ **没跑**。
3. **家户侧复算未逐值闭合**：138/159 逐值一致、155/159 名次一致，21 户差 ≤ 2 毫（估值口径）；**根因未定位**
   （疑为存档边界与快照点之间的日初/轮内预留，如 day243 的 17 条 `MARKET_LIFE_RESERVE`）—— 没有逐户预留读数可对，**不声称闭合**。
4. **未跑 `clean verify` / 任何测试类**：本批是"只读验收 + 真世界读数"，门禁（G1）不在本任务书范围内（W4 提交信息已自带 3407/0/0/5 + SpotBugs 15/15 + 前端 412/412）。
5. **未做变异自证**：本批不新建护栏，判别力由真世界读数承担（W4-fix2 账本 G-4-4 同款声明）。
6. **货物守恒仍只能单边**（延续 W2 的 D7 / W3 §5）：我扫了本次跑出的 **26 份存档**（`dumps/*.json`，含 three-powers 的 genesis/d33/d118/d240/d241-243 与 small-world 的 9 份 tick360）
   的 `money` / `balances` / `frozenMoney` / `frozenBalances` / `classes.population` / `classes.laborMilli` / `debtContracts.principal` / `social.households.members`
   —— **负数 0 处**；但**没有产出/损耗读数面** ⇒ 给不出"产出 = 消费 + 损耗 + 存量增"的闭合式。
7. **未做 600 天 / 0→1000 的长跑**：本批按任务书跑 `0→240`（项 9）与 `0→360`（项 6/7）；W3 已跑过 600/1000。
8. **未核 `classStandings` 全部写点的行为**：仅静态审计出 **11 处** `new HouseholdClassMembership(`（
   `ModeMigrationSettlement:831`、`EconomyModeTransitionSettlement:393`、`ClassSlideSettlement:244`、`EconomyLiquidationSettlement:1152/1326`、
   `ProductionRoleResolver:113`、`EconomyRegisterGovernmentHandler:273`、`EconomyPayloads:799`、`HouseholdEconomyCommands:164`、
   `EconomySetHouseholdParticipationHandler:116`、`EconomySetHouseholdClassHandler:106`），并确认 **滑落原因串 `class-slide-speed:` 只在 `ClassSlideSettlement` 出现**
   （`EconomyLiquidationSettlement` 的两处是"缺 standing 时播种"与 `withStressCount` 保位复制，不是第二条滑落权威）；
   **没有**逐条跑这些写点做行为验证。
9. **未验 W5/W6 范围**：铸币入市、600 天标定、滑落斜率 100‰ 的标定（W4 账本 §五-4 列为 W6 首查项）**都没跑**。
10. **区序"跨币不折算"未做敏感性实验**：只核了它与 W1 账面口径一致（D6），没有做"若按单一币种排序会怎样"的对照。
11. **D7（国库户排序）只做到"静态 + 反向零命中"**：确认了 `hh-gov-*` 不进家户序、且本世界它**不下家户单**（`buyer=`/`seller=` 0 命中、mandate 单 0 行）；
    **没有**构造"国库户真的下 household 单（或 mandate 单落进同一名次键）"的场景去实测它会不会真排到最后 —— 该场景需要 GM 造单口，本批未做。

---

## 附：原始产物位置（全部在 `/tmp`，未落仓）

| 内容 | 路径 |
|---|---|
| 装置与脚本 | `/tmp/w4acc/{runner.py,canon2.py,recompute_order.py,conserve.py,perf-ab.sh,runs-*.sh}` |
| plan / 配置 | `/tmp/w4acc/plan-*.json`、`/tmp/w4acc/shell-{tp,sw}.json` |
| 各轮结果与日志 | `/tmp/w4acc/results/<tag>.result.json`、`/tmp/w4acc/results/<tag>.log`、`/tmp/w4acc/results/gap118-get*.json` |
| 存档 dump | `/tmp/w4acc/dumps/<tag>-<label>.json`（b2 三份 / orderA·B 逐日 / tpslide 前后 / gap118 / tp240trace genesis+d240） |
| 提取出的关键事件日志 | `/tmp/w4acc/key/{MARKET_REPORT,MARKET_ORDER_*,CLASS_SLIDE*,MARKET_FILL,MARKET_BUYER_OUTCOME,MARKET_ROUND_*,day33-order}.log` |
| 本报告引用的证据文件 | `/tmp/w4acc/evidence/{item2_selfref,item3_scarcity,item3_scarcity_C,item4_slides,item9_guard}.txt` |
| 构建日志 | `/tmp/w4acc-build.log` |

**完工自检**：`git status --porcelain` 为空（与开工一致）；本报告只新增 `.superpowers/sdd/2026-10-11-w4-acceptance/report.md`
（目录在 `.superpowers/sdd/.gitignore` 的 `*` 之下，不进版本库）。
