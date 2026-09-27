# 多 Hex 货币经济：全阶段开发计划（MASTER PLAN）

> **性质**：**开工用的唯一计划**（未改一行 Java、未跑 Maven）。
> **用途**：本文件是**跨会话的记忆载体**——上下文压缩之后，只读本文件即可恢复全部已裁事项、约束与开工点。
> **口径来源（缺一不可，按优先级）**：
> 1. `docs/superpowers/specs/2026-09-27-multi-hex-monetary-economy-design.md`（**用户设计，权威**，sha256 前缀 `2a092448`）
> 2. **用户 2026-09-27 的三组裁定**（下文 §1.2 逐条记原话要点）
> 3. `docs/superpowers/specs/2026-09-27-relations-first-architecture.md`（关系面：占有/经营/劳动来源）
> 4. `docs/superpowers/reviews/2026-09-27-year-one-report-review.md`（一年期实测 + 三处更正 + M-4 缺陷）
>
> **降级/挂起**：`2026-09-27-regional-market-design.md` 的"一城一市 + tier 半径"降为 `MarketRegion` 的一种形成规则；
> `2026-09-27-regional-market-plan.md` 的 P1–P5 并入本文 M1–M2。

---

# 第一部分：背景与裁定（压缩后靠这一节恢复记忆）

## 1.1 现状一句话
> **自然经济 + 封建地租 + 一层诚实但很薄的市场**：一格一市、每周期一次、固定价、**只有家户参与**、
> **商品货币且无发行人**（创世每人 12 毫银）、**无税无国库**、债权**只记债务人**、跨格零腿。

## 1.2 用户裁定（三组，原话要点）

**A 组 —— 理论框架（唯物史观）**
- **批准**：① 实际占有关系决定分配；② 阶级由经济地位导出；③ 再生产推动结构变化。
- **否决**：三轴合一（制度/阶级/城乡）；"必须自动演化成资本主义"；"规模全部由预期利润决定"；
  "账上盈余 × 系数 = 产能"；"税 = 经济关系、征粮 = 超经济强制"的二分；
  "没有资本主义生产方式 ⇒ 没有资本、没有积累"；把"有钱有粮"读成"没有无产者"。
- **落实顺序**：**先打通「生产资料占有 → 生产组织 → 产品归属 → 再生产」，再派生阶级统计**。
- **阶级方向**：地主（控制土地收益、靠地租、自身劳动次要）/ 富农（自己劳动 + 经常雇工）/
  中农（主要家庭劳动、不依赖出卖劳动力）/ 贫农（独立经营不足、兼靠出卖劳动）/ 雇农·工人（主要靠出卖劳动力）。
  阈值与观察期是**版本化查询口径**，不冒充原文公式。
- **三处更正我方错误**（已接受）：地主粮占农村 **57.64%**（非 73%）；"利息无偿还"表述错（本金偿还在跑、**债权人侧不入账**）；
  "劳动闲置 14%"证明不了每格不缺劳动（那是**参与率折算差额**，而配额按**毛额**发）。

**B 组 —— 系统设计（多 Hex 货币经济）**
- 采用：**主体分账 · 区域市场 · 稀疏运输网络 · 分层货币工具 · 事件驱动债务 · 统一原子结算**。
- 简化原则（★ 推翻我上一版做法）：**简化应减少参与者数量、撮合范围和结算频率，不删掉交易对手、产品去向和资金来源**。
- 币种 ≠ 货币工具；守恒**逐工具** `Σ持有 = 创世 + 累计发行 − 累计注销`；**没有"全世界总量永远不变"的总不变量**。

**C 组 —— 范围（本阶段做多少）**
- **国库、外汇系统合并到后续的国家模块**（延后）；**先把一般市场做出来**；
- **三国市场不互通**（`silver` 单币种，无换汇、无跨国）；
- **C+A**：**C** = worldgen 加"格能否自养"的创世校验（把产地/人口失衡在创世期消掉）；**A** = **国内调剂**（跨国不开）。
- 模拟范围：**只跑一年**（tick 120/240/360），不再跑 600 天。

## 1.3 硬约束（代码实测，写代码前必看）

| # | 约束 | 出处 |
|---|---|---|
| 1 | `simos-economy` **可** import `simos-map`；pom **禁 `simos-unit`** ⇒ 运输代价**不能**复用 `PathFinder`，要自写 | `simos-economy/pom.xml:34-37,66-78` |
| 2 | `Transfer` **只有一个 `location`**（无起终点对/在途日/运费/承运人）⇒ 跨格搬运 = **两条腿**，或在 M2 扩形状 | `economy-api/.../transfer/Transfer.java:84-93` |
| 3 | **动态价格连写入口都没有**（无"设价"命令；`withMarkets` 生产零调用；`economy.Seed` 对已占用格整份拒绝） | `EconomyPayloads.java:127`、`EconomySeedHandler.java:76-81` |
| 4 | `EconomyData` **9 组件**里没有地图/地形/邻接；`settleOneDay` 入参**没有 `GameMap`** ⇒ 地图事实必须**生成期播进数据** | `EconomyData.java:104-113`、`EconomySettlement.java:606-977` |
| 5 | `map` 侧**没有** `neighbors(HexCoord)`/`moveCost(from,to)`/寻路；只有 `HexCoord.neighbors()/distanceTo()`、`TerrainType.moveCost`、`Pathway/EdgeRef/EdgeTags` | 代码调查（`GameMap` 全文 grep 零命中） |
| 6 | `ActorKind` **已有 `GOVERNMENT` 与 `ORGANIZATION`** ⇒ 国家/承运主体**零契约改动** | `ActorKind.java:29-43` |
| 7 | `EconomyChangeSet` **恰好 9 个 `FieldDelta`**，由 `EconomyRoundTripTest` 的**反射枚举**把守（铁律 5：加组件必回填） | `EconomyChangeSet.java:53-63,31` |
| 8 | `SocialClassId` 词表 = `poor_peasant/middle_peasant/rich_peasant/landlord`（四档，闭集） | `SocialClassId.java:31-32` |
| 9 | 四档制度 = `feudal/household/handicraft/tenant`（`tenant` **真档零使用**） | `RegimeOperators.java:34-47` |
| 10 | ★★ **今天没有"订单"类型**：市场就地算局部变量（`sellers/buyers/supply/totalDemand`），**订单簿是 M2 要新建的** | `MarketSettlement.java:187-208` |

## 1.4 已实测的关键数字（判据基线，改完要对得上）

| 量 | 值 | 出处 |
|---|---|---|
| 真档世界 | 799 格 / 11,349,353 人 / 1,799 产业（farm 799 · weave 799 · craft 201） | tick360 原始读数 |
| 三国粮收支 | 德意志 **119.2%** 自给 · 奥斯特马克 **79.4%**（★结构性缺口）· 霍赫兰 **146.6%** | 按国汇总 |
| 奥斯特马克国内余粮 | 全国 Σ余 **7,281**/周期；首都缺 444,561 ⇒ **国内调剂只能补 1.6%** | 同上 |
| 首都格 `(-39,-71)` | 273,770 人（城镇 259,184）；3,100 亩；可用粮 176,669/周期；需求 2,737,700 ⇒ **自给 7.7 天** | 同上 |
| 首都缺口可覆盖半径 | R2 **21.8%** · R4 56.9% · **R8 156.1%** · R16 426.9%（= `tierRadiiHex` MajorCity [8,16]） | 同上 |
| 运输耗时（平原） | **1 天/格**；R8 ⇒ 8 天；整国 430 格宽 ⇒ **200+ 天（不可用）** | 单位移动口径 |
| 货币 | 142,924,800 毫银 ≈ **1.26 个月**全国口粮；城镇占 **5.80%**（逐周期被抽干 8.55→7.47→5.80%） | tick 三期 |
| 劳动 | 行侧毛劳动 6,387,681 与 Σ(毛×参与率) 5,491,860 差 **14%**（= 读数口径差，**非闲置**） | 实测 |
| 一年曲线 | 缺口 72.5M→12.7M→11.3M；债务本金 ×102；**作坊第 2 周期起 201/201 格停工** | `year-one-curve.md` |

## 1.5 三条"不要重蹈"的教训
1. **幻影判别力**（已第 4 例）：注释点名的 `EconomyRealScaleClothTest#probeFibreAccountsPerCycle` **在仓库里不存在**。
   ⇒ 写"某测试守着它"之前**先核它存在**。
2. **无回归网的链路**：`MarketSettlement` **整类零测试**、偿还链（`repayDebts`/`LOAN_REPAYMENT`）**零测试**、
   §6.1 主守恒式**是纯注释**。⇒ **M0.5 必须先补网**，否则改动静默漂开。
3. **口径/窗口踩过 6 次**：`consumed` 混合桶（日耗 + 生产投入含种子 + 取材转出，实测 3,931 行 `consumed > 3×needs`）；
   `unmetNeed` 按周期清零；关账日本期流水天然为 0。⇒ 报任何数之前先核**统计窗口**。

---

# 第二部分：阶段总览

```
M0 仪器 ──→ M1 货币与关系 ──→ M2 一般市场（本期目标）──→ M3 运输与在途（可选增厚）
                                                    └──→ [国家模块：国库/发行/税/外汇/银行]
                                                                   ↑ 用户已裁：合并到后续国家模块
每阶段：任务 → 落点 → 判据 → 不做。阶段边界跑全仓 `clean verify` + 一年期真档读数对比。
```

| 阶段 | 一句话 | 退出条件 | 依赖 |
|---|---|---|---|
| **M0** | 修**仪器**：可复现、可归因、有回归网 | ①360==120×3 逐项一致 ②七项诊断出表 ③未成交原因可分 ④三条守恒判据绿 | 无（**先决**） |
| **M1** | 修**账**：唯一余额 + 预留 + 原子结算 + **双向债权** + 关系面 | 不多不少、不能双花、债权债务同源、占有与经营可分 | M0 |
| **M2** | **一般市场**（本期目标）：订单体系 + 经营者入市 + 区域撮合 + 区内即时/跨区 ETA | 两格粮布/纤维循环成立，**不靠财政兜底** | M1 |
| **M3** | 运输增厚：运力占用、损耗账户、路线缓存、司机（承运主体） | 有货有路运力不足时**报物流瓶颈** | M2 |
| **M4+** | **国家模块**（用户已裁合并）：国库、ISSUE/REDEEM、税、外汇、银行 | —— | 独立立项 |

★ **M2 是本轮目标**；M3 是"如果 M2 的运力假设被数据打脸就要做"的可选增厚；M4+ 不在本计划内。

---

# 第三部分：M0 —— 仪器（先决，不碰模型）—— ✅ **已完工（2026-09-27）**

> ★★ **M0.1/M0.2/M0.3/M0.5 已实现并过门禁；M0.4/M0.6 改走"报表侧"（零 Java 改动）并已完成。**
> 台账：`.superpowers/sdd/2026-09-27-m0-instrument/progress.md`（逐任务的达成/未达成/没跑）。
> 提交：`5c14782f`（M0.1+M0.5）、`c70f0d14`（M0.3）、`b872c91a`（M0.4）、`4db936b8`（M0.6）。
> 门禁：全仓 `clean verify` 绿 —— **11 模块 / 2467 条 / 0 失败**；前端门禁 **297/297**。

### M0.1 推进路径一致性 ★★ —— ✅ 达成
- **落点**：`EconomySettlementEndToEndTest#threeHundredSixtyDaysInOneCommandEqualsThreeBatchesOfOneHundredTwenty`。
- **判据**：一次 `advance(0→360)` == 三次 120 天，`EconomyData` + `ActorData` **两层逐值相等**。
- **不做**：revision 条数（那只是"分几条"）。

### M0.2 单位与统计窗口归位 —— ✅ 达成（纯注释 + 报表口径）
- `FlowRow` 的 `consumed` / `unmetNeed` / `deaths` 三处注释与代码对齐（旧口径**就地标作废**）；
- `year-one-curve.md` 加"读表前必读：三处口径"（窗口 + `deaths` 不是饿死数）。
- ★ **实测确认**：`consumed` = 日耗 + **从家户账扣的**投入；"供方 == 经营者"那一支**刻意不记**
  （`recordOperatorInputDraw`）。两处都不许重复记（M0.5 的全局守恒式就按这条写成两支）。

### M0.3 每格粮食诊断 —— ✅ 达成（四项可算 + 三项具名做不到）
- **落点**：`ApiViews.economyHex` 新栏 `grainDiagnosis`（同一份视图 ⇒ MCP 工具/GUI 路由/报表 dump 自动都带上）。
- 可算四项：`coverageDays`（库存 ÷ **日耗**）、`importDemand`（= 本周期累计未满足）、
  `affordableGrain` + `purchasingGap`、`satisfactionPerMille` + `unmetPersonDays`（两条路互证）。
- ★★ **算不出的三项具名列在 `unavailable`、绝不填 0**：`productionSelfSufficiency`（要 ledger，**当日丢弃**）、
  `logisticsGap`（M2.4 前无定义）、`paymentInstrumentGap`（属 M1）。
- 判据：`EconomyGrainDiagnosisTest`。

### M0.4 未成交原因归因 —— ✅ 达成（**落点从 Java 改到报表**）
- **改动**：新增 `m04_attribution.py` + `m04-attribution.md`；`year-one-curve.md` §六。
  ★ **不往 `MarketSettlement` 里记账**：ledger **当日丢弃** ⇒ 记了报表也读不到；而已落盘的逐格读数
  （`grainStock` / `grainDailyConsumption` / 逐行 `unmetNeed` / `actorMoneyTotal` / `market.prices`）
  **足够事后推导**归因。⇒ 零 Java 改动、零持久化组件。
- **七档**：`NO_MARKET` / `NO_NEED` / `SUPPLY_ZERO_(NO_SELLER|ALL_RESERVED)` /
  `NO_PURCHASING_POWER` / `LOCAL_SUPPLY_SHORT` / `ALGORITHM_UNCOVERED` / `RESOLVED`。
- **三问的答案**：① tick120 是**世界没余粮**（可售够缺口 54.85%），tick240/360 反过来
  （可售是缺口 5.57/8.68 倍）⇒ 后两期是**撮合问题**；② **购买力封锁 0 格**（候选格里最穷的
  购买力 68,604,000 毫粮，离"买不起 1 毫"差 7 个数量级）★ 但穷格先被 `SUPPLY_ZERO_*` 吃掉；
  ③ 首都三 tick 全落 `SUPPLY_ZERO_NO_SELLER`（有 4,387,092 毫银 ⇒ **不是买不起，是一粒不卖**）。

### M0.5 回归网（守恒）★★ —— ✅ 达成
- **落点**：`EconomyConservationNetTest`（一条用例，窗口 = 一个周期；自己走日循环接住当天的 ledger）。
- **四条判据**：① 关系守恒（计提 == 毛产 − 损耗；0 ≤ 实付 ≤ 净产）+ ①b **余额归 operator 的账目实测**；
  ② 市场守恒（货腿钱腿成对、货款 == `⌈量×价÷1000⌉`、逐日全账货币总额 == 创世禀赋）；
  ③ 债务守恒（平时 `本金_今 == 昨 + 放出 − 偿还`，计息日 `+ ⌊昨×率÷1000⌋`，**计息自算不抄流水**）；
  ④ 全局商品守恒（`ΔΣ账本库存 == 毛产 − 损耗 − Σconsumed − 未并入 consumed 的投入`）。
- ★★ 判据自身被实测更正 **4 处**（**错的是判据不是代码**），逐条留痕在用例注释里；
  详见 §1.6。

### M0.6（C 裁定）worldgen 自养校验 —— ✅ 达成（暴露，不改数）
- **落点**：`m06_self_sufficiency.py` + `m06-self-sufficiency.md`；`year-one-curve.md` §七。
- 逐格自养（799×3）、集散节点 R8/R16（节点判据 = 有 `craft@` 的 **201** 格、距离用 **cube**）、三国收支复算。
- ★ 覆盖率是**上界**（邻域**毛**余粮、不扣邻格缺口、无运输）；★ **未据此自动改人口**（那要另裁）。

---

## 1.6 M0 批实测更正（这批最重要的产出：**判据/口径错了四处**）

| # | 原先的说法（错的） | 实测 | 正确的说法 |
|---|---|---|---|
| 1 | "关系实付之和 == 净产" | `farm@0_0`：70,929,264 vs 201,469,000 | 封建档 = 毛产 300‰ 地租 + 按劳动量的实物口粮，**余下按定义归 operator**（`residualOwner`）⇒ 判据是"计提 == 净产 + 实付 ∈ [0, 净产]"，另加**账目实测**钉自留 |
| 2 | "`consumed` 里没有投入" | tool：输入 12,000 **不在** `Σconsumed`(0) 里；grain 的投入是 0 | **两条支路**：从家户账扣的投入**记进** `consumed`；"供方 == 经营者"那一支**刻意不记**（`recordOperatorInputDraw`） |
| 3 | 期初库存取 actor 侧的账 | fiber 期初读作 0、期末 63,490,860 ⇒ 等式左边恒 0 | 两族会话副本是从 actor **载入**的 ⇒ 期初必须取**会话开头的副本快照** |
| 4 | 流水在 `stepper.data()` 里 | 恒空（那份要 `finish()` 才挂上） | 当日读 `stepper.flows()`；整周期读 `stepper.finish().flows()` |

★★ **M0.4 挖出的真问题（比上面四条更要紧）：市场被"自留参数"锁死，不是被算法卡住。**
`MARKET_SELF_RESERVE_PER_MILLE = 1000‰` 让每个家户先扣下**整周期需求**再挂牌 ⇒
在缺粮世界里（首都只有 7.7 天粮、自留要 120 天）**没有任何一格有粮可卖，且不分穷富**。
⇒ "自留倍数"从"实现期再裁的调参"升级为 **M2 开工前必须首先裁定的问题**（见 §8 待裁）。

---

## 1.7 M0 批暴露的口径疑点（待裁，未动数据）

| # | 疑点 | 实测 | 影响 |
|---|---|---|---|
| 甲 | **首都格的人口基线对不上** | `(-39,-71)` 实测 人口 **360,862**（城镇 345,908）、3,100 亩、库存 236,854,357、日耗 30,381,003 ⇒ **覆盖 7.744 天（= 基线那个 7.7）**、整周期需求 3.61B、缺口 1.74B | §1.4 记的"273,770 人 / 259,184 城镇 / 需求 2,737,700"**同一格同一亩数却对不上人口** ⇒ 要么基线记错、要么世界被改过 |
| 乙 | **三国自给率差 ≤0.13 个百分点** | 复算 119.07 / 79.36 / 146.51% vs 基线 119.2 / 79.4 / 146.6% | 成因未坐实；§1.4 那三个数**已降级为近似** |
| 丙 | **master plan 的几个数复现不出** | 「奥斯特马克 Σ余 7,281」「首都缺 444,561」「R2–R16 四连」 | 以数据为准；这些数**从 §1.4 撤下**（见下） |
| 丁 | **`ΣgrainDailyConsumption × 120` 与 `Σpop × 10,000` 差 1.58~5.34%** | 随 tick 变大、方向一律偏高 | **未解释**；已做敏感性（不翻转 M0.4 的结论） |

★ **§1.4 的修订口径**：保留下面这些**已独立复核过**的数；撤下那几个复现不出的。

| 量 | 值（已复核） | 出处 |
|---|---|---|
| 真档世界 | 799 格 / 11,349,353 人 / 1,799 产业（farm 799 · weave 799 · craft 201） | tick360 |
| 三国生产自给率 | 德意志 **119.07%** · 奥斯特马克 **79.36%**（★ 结构性缺口）· 霍赫兰 **146.51%** | M0.6 复算 |
| 首都格 `(-39,-71)`（★ 属**德意志第二帝国**，非奥斯特马克） | 人口 **360,862**（城镇 345,908）· 3,100 亩 · 库存 236,854,357 · 日耗 30,381,003 ⇒ **覆盖 7.744 天** · 整周期需求 3.61B · 缺口 1.74B · 可售余量 **恒 0**（自留要 3.65B）· 有 4,387,092 毫银 | M0.6 + 我复核 |
| 集散节点 | **201** 格有 `craft@` 产业（= 报表"城市格 201"）；R8/R16 覆盖率见 `m06-self-sufficiency.md`（★ 上界） | M0.6 |
| 货币 | 142,924,800 毫银 ≈ **1.26 个月**全国口粮 | tick 三期 |
| 一年曲线 | 缺口 72.5M→12.7M→11.3M；债务本金 ×102；作坊第 2 周期起 201/201 格停工 | §1.4 原值（未复核，仍可用） |
| M0.4 三问 | 缺口的档位分布、购买力封锁 0 格、首都落 `SUPPLY_ZERO_NO_SELLER` | `m04-attribution.md` |

### M0 批的工具教训（写进 AGENT.md §七，代价三轮返工）
1. **`-Dtest='A+B'` 是 JUnit5 的 tag 表达式、不是"或"** ⇒ 不匹配任何类、surefire **一条不跑**、
   打印 `test` 目标、**退出 0**。正确写法是**逗号**。★ 差点把"一条都没跑"当成"通过"。
2. **Maven 增量编译不可全信**：实测类文件比源文件**新 21 秒**却没重编 ⇒ 两轮诊断跑在旧字节码上。
   **纪律：改完生产代码，先删 `target/classes`（或 `target/test-classes`）再跑测试。**

---

# 第四部分：M1 —— 货币、账与关系（不碰市场撮合）

### M1.1 币种与货币工具身份
- **新增（`simos-economy-api/.../money/`）**：
  ```
  record CurrencyDef(String id, int scale)                     // 计价单位 + 最小单位精度
  record MoneyInstrument(InstrumentId id, CurrencyId currency, InstrumentKind kind,
                         ActorRef issuer, Optional<ActorRef> redeemer)
      // InstrumentKind ∈ { SPECIE 金属币, STATE_NOTE 国币, BANK_DEPOSIT 银行存款 }
  ```
- **判据**：`silver` 迁成"一个 `CurrencyDef` + 一个 `SPECIE` 工具"；**旧的 `CurrencyId` 读口保留**（兼容）。
- **不做**：铸熔、成色、兑现（M4+）。

### M1.2 `FinancialAccount` + 冻结
- **新增（`simos-economy-api/.../money/`）**：
  ```
  record FinancialAccount(AccountId id, ActorRef owner, InstrumentId instrument,
                          long balance, long frozen)            // 0 <= frozen <= balance
  ```
- **落点**：`EconomyData` 加组件（第 10 个）⇒ **必须回填 `EconomyChangeSet`**（铁律 5，`EconomyRoundTripTest` 自动把守）；
  `EconomyCodec` / `EconomyPayloads` / `ApiViews` 同步。
- **判据**：冻结计入余额、**不重复相加**；`frozen > balance` 当场抛。

### M1.3 唯一余额权威 ★
- **做什么**：`GoodsAccount.money` 保留为**兼容读口**，底层**只留一份权威**；不允许两处可写。
- **落点**：`simos-actor/.../model/GoodsAccount.java` + `OwnershipBooks` 四个 `load*/land*` + `applyTransfer` 的货币腿。
- **判据**：新旧两条读路逐值一致（对拍用例）；写入只经一处。

### M1.4 预留 + 原子结算（`SettlementBatch`）
- **新增（`simos-economy-api/.../settle/`）**：
  ```
  record SettlementBatch(BatchId id, long tick, List<Entry> entries, BatchStatus status)
  record Entry(ActorRef actor, InstrumentId|CommodityId asset, long delta, EntryKind kind)
  ```
- **流程**：冻结资源 → 验证双方与（将来的）路线条件 → **一次提交全部分录** → 释放预留。
- **判据**：`applyTransfer` 仍是**唯一写口**（不新增第二个 applier）；半途失败不留半笔。

### M1.5 `Claim` 双向视图 ★
- **新增（`simos-economy-api/.../claim/`）**：
  ```
  record Claim(ClaimId id, ActorRef creditor, ActorRef debtor, AssetRef principal,
               long principalAmount, long accruedInterest, long dueTick,
               int ratePerMillePerCycle, ClaimState state)
      // state ∈ { ACTIVE, OVERDUE, RESTRUCTURED, WRITTEN_DOWN, EXTINGUISHED }
  ```
- **落点**：替换/包裹今天的 `Debt`（粮债）；**债权人的"应收"必须落账**（今天**零**）。
- **判据**：**一笔债两视图同源**；计息**只增应收/应付、不自动增可花余额**；★ 债权侧入账**在此完成，不等国家模块**。
- **不做**：违约处置、重组、抵押执行（M4+）。

### M1.6 逐工具守恒
- **做什么**：判据从"`Σ银恒定`"改为 **`Σ持有账户 = 创世 + 累计发行 − 累计注销`**（本阶段 `发行=注销=0` ⇒ 退化为今天的形态）。
- **落点**：`EconomyMoneyInvariantTest` 扩写；读数里把**私人流通 / 全部基础货币 / （将来）银行存款**分栏。
- **判据**：★ 明写**没有"全世界总量永远不变"的总不变量**。

### M1.7 关系面（B 组架构）★★
- **做什么**：`ProductionRelation` 加三面：
  ```
  + List<AssetOccupation> occupations   // (holder, kind, quantity, sharePerMille)
  + List<UseRight> useRights            // (user, kind, basis∈{OWNED,TENURED,JOINT,OTHER}, grantor)
  + LaborSource laborSource             // FAMILY_SELF / TENANCY / DEPENDENT / EMPLOYED(employer, laborMilli, rewardRule)
  ```
- **落点**：`simos-economy-api/.../relation/`（新类型）+ `RegimeRelations` 的 `defaultRelation` 加缺省展开；
  `EconomyChangeSet` 回填；`ApiViews` 读口。
- **判据**：① `Σ occupations[kind] ≤ capacity[kind]`（新不变量，构造期守卫）；
  ② **地租受方 = 土地关系里的权利主体**（改一条关系就换收租人）；
  ③ **雇佣量 ≤ 支配规模所需劳动**；
  ④ 四档人均劳动**不再齐次**（修 M-4：`participationPerMille` 进计算，见 M1.8）。
- **不做**：资产市场、抵押、土地买卖。

### M1.8 修 M-4（`participationPerMille` 进计算）
- **病灶**：配额预算用 `grossLaborMilli`（`EconomySeeder.java:1083/1132`）⇒ 地主 100‰ 与贫农 950‰ 的差别**不进任何计算**；
  真档实测四阶层 `labor/pop` **全部 = 562.8**。
- **做什么**：配额预算改为"**按阶层参与率折扣后的可用劳动**"。
- **判据**：四档人均劳动**拉开**（地主 ≈100‰ vs 贫农 ≈950‰，约 9.5 倍）；`Σ allocated ≤ available` 仍绿。

---

# 第五部分：M2 —— 一般市场（**本轮目标**）

### M2.0 市场区与区内手续（前置定案）
```
区：以城市节点为集散点，半径 = 该城 tier 的 tierRadiiHex（★ MajorCity 取 8~16 —— 实测 R8 覆盖首都缺口 156%）
    ⇒ **不按国界**；★ 绝不把"一个王国"当一个区（430 格宽 ⇒ 200+ 天，比周期还长）
货币：单币种 silver，无换汇、无跨国（用户裁定）
手续：冻结 → 验证 → 原子提交 → 释放
交割：区内即时；跨区按 TradeRoute 的 ETA/损耗/运力
频率：商品撮合**每 5 天**一轮；粮食库存低于阈值可追加一轮
★ 必须在场景配置与报表里标注的简化：跨区结算**暂设即时**（设计允许，但要求写明）
```

### M2.1 订单体系（**今天不存在**）
- **新增（`simos-economy-api/.../market/`）**：
  ```
  record BuyOrder(ActorRef requester, HexCoord deliverTo, CommodityId commodity, long quantity,
                  long maxLandedPrice, long latestArrivalTick, Budget budget, InstrumentId payWith)
  record SellOrder(ActorRef supplier, HexCoord dispatchFrom, CommodityId commodity, long sellable,
                   long minPrice, long availableFromTick, InstrumentId receiveWith)
  ```
- **生成规则**（**主体各自生成**，不是按格打包）：
  家户 `max(0, 下次补货前消费预测 + 安全库存 − 现有可用 − 该时限前确定到货)`；
  经营者提**投入需求 + 生产资料维护需求 + 产品出售意愿**；**预算独立算**（不许拿需求冒充信用额度）。
- **判据**：订单只从**真实缺口/剩余**产生；已售出/已冻结/在途部分**不得再卖**。

### M2.2 参与者扩容 ★
- **做什么**：**经营者（庄园/作坊）入市** + 运输经营者（`ActorKind.ORGANIZATION`）+ 家户；
  **统计标签（贫农/中农/地主）不承担资格开关**。
- **判据**：作坊**能卖掉布与工具** ⇒ 形成销售收入循环（今天"只有付出没有收入"，周转金 1.2 个周期见底）。
- **备注**：这是 H6-lite 自认的"正解"（`progress.md:435`）。

### M2.3 区域撮合（区内优先 + 稀疏跨区）
- **流程**：按 `(region, commodity, price bucket)` 聚合（保留来源索引）→ ①区内优先 → ②跨区候选（**第一版只考直接邻接供应区**）
  → ③判到货价/量/期限 → ④有限轮 I 内分运力 → ⑤按原始限价与预算分配回主体 → ⑥原子提交、建在途。
- **落点**：`simos-economy/.../time/MarketSettlement.java`（`clearOncePerCycle` → `clearRegion`）；
  `IndustryHexKeys` / 新增 `MarketRegion`（成员格 + 集散节点 + 报价币种 + 接收工具）。
- **判据**：跨格成交**可追到**发货格/收货格/路线/ETA；未成交原因出表（M0.4）。

### M2.4 在途与运输（最小版）
- **新增**：`TradeRoute(from, to, capacityPerWindow, travelTicks, costPerUnit, lossPerMille)`；
  `ShipmentBatch(route, commodity, dispatchTick, arrivalTick, quantity, allocations[])`。
- **运输成本拆五件**（设计 §5.4，**不许一个系数兼三职**）：运费（→ 运输主体收入）· 实际投入（劳动/饲料/维护）·
  损耗（在途实物减少，计入损耗账户）· **时间**（ETA，**到货前目的地不能消费**）· 运力（每段时间最大发运量）。
- **落点**：运输代价自写（**`economy` 禁 `simos-unit`**）——口径抄 `SettlementGenerator.java:257-261` 的 `hexDistance × moveCost(目标格)`；
  地形 `moveCost` 来自 `map`（平原 1 … 山地 6 … 高原山地 12；海洋 999 = 不可通行）。
- **判据**：★ "有货、有路、**运力不足** ⇒ 城市仍可能缺粮，系统报物流瓶颈"。

### M2.5 基线合同（风险分担）
- **约定**：发运时**买方付货款与运费**，**货物所有权转给买方**（进入其在途资产）；卖方库存减少；
  到达时在途减、目的地库存增；**约定由买方承担运输损耗**（其他分担后续扩展）。
- **判据**：在途批次里**保留谁承担损失**的信息（不为聚合丢掉）。

### M2.6 价格（第一版固定 + 可选适应）
- **第一版**：每区每商品一个**本轮固定报价** + 有限买卖价差；订单按限价过滤。
- **可选（默认关）**：`z = clamp((有预算且合限价的需求 − 可出售供给)/max(需求+供给, ε), −1, 1)`；
  `p_next = max(p_min, round(p × (1 + α·z)))`；α 起步 ≤5%/轮，**定点数**。
- **判据**：★ 生理需要与有效需求**分别记录**；穷人没购买力时**不许报告"无人缺粮"**；★ 不开价格时报表要标注。

### M2.7 市场读数（与 M0.3 配套）
- 逐区逐商品：供给 / 需求 / 成交量 / **到货价** / 运费总额 / 损耗 / **未成交原因分布** / **未利用运力**。

---

# 第六部分：阶段纪律（每个阶段都执行）

1. **Maven 串行**：用 `tools/mvn-lock.sh`；**服务在跑时不许 `package`**。
2. **门禁**：阶段边界跑**全仓** `./mvnw clean verify`（Spotless + Checkstyle + SpotBugs + Surefire + 前端门禁），
   不是模块级；`-am` 不能省。
3. **测试数**：报数前先 `rm -rf */target/surefire-reports`；★ 若 `simos-app/target/surefire-reports/` **整个不存在** ⇒
   说明 app 侧没跑到 test 阶段，**不能**读成"app 没测试"。基线：**11 模块 / 2460 条 / 0 失败**，前端门禁 **297/297**。
4. **铁律 5**：加组件/字段必回填 `EconomyChangeSet`（`EconomyRoundTripTest` 反射守卫会红）。
5. **判据用一年期**（tick 120/240/360）对比已入库的基线读数（`h6raw_tick{120,240,360}.json`）；**不跑 600 天**。
6. **如实记**：哪些判据达成、哪些没跑、哪些是后补测试；★ 报"某测试守着它"之前**先核它存在**。

---

# 第七部分：明确的边界（不做清单）

**用户已裁不做**：三轴合一 · 自动演化成资本主义 · `规模=f(预期利润)` · `盈余×系数=产能` ·
"税=经济关系/征粮=超经济强制"的二分 · 资产市场与抵押撮合 · 浮动工资与劳动市场 · 把"经营盈余"命名成剩余价值 ·
**国库/外汇/银行（合并到后续国家模块）** · **跨国贸易** · 600 天模拟。

**本计划自行排除**：全局一般均衡 · 逐家庭逐商品跨世界竞价 · 每笔存款一个币种 · 默认自由汇兑 ·
无产权依据的自动抵押执行 · 用通胀公式改写钱包 · 把新增货币当新增粮食 · `N²` 格对或 `A²` 主体对搜索 ·
每日遍历全部历史债条。

**本计划没有回答的**（实现期再裁）：运费系数标定 · 承运主体的运力从哪来 · 市场区的最优半径（先用 tier 半径）·
订单刷新频率的调参 · 损耗率 · α 与 p_min · 税基（国家模块）。

---

# 第八部分：开工顺序（压缩后照这个走）

```
第一批（仪器）        ✅ 已完成：M0.1 → M0.5 → M0.2 → M0.3 → M0.4 → M0.6
第二批（账户与关系）  ← 下一步：M1.1/M1.2/M1.3 → M1.4 → M1.5 → M1.6 → M1.7 → M1.8
第三批（一般市场＝本轮目标）： M2.0 定案 → M2.1 → M2.2 → M2.4 → M2.3 → M2.5 → M2.6 → M2.7
可选增厚：                    M3
后续独立立项：                M4+（国家模块：国库/发行/税/外汇/银行）
```

★ **每批结束都要**：跑全仓门禁 + 出一份一年期读数对比 + 把"达成/未达成/没跑"写进
`.superpowers/sdd/<本阶段目录>/progress.md`。

★★ **第一批留下的三个待裁（进第二批之前先答，否则 M2 会在错的地基上开工）**：

| # | 待裁 | 为什么必须在 M2 之前答 | 数据 |
|---|---|---|---|
| **甲** | **自留倍数 `MARKET_SELF_RESERVE_PER_MILLE`（现 1000‰）怎么办** | 它让"缺粮时**没有任何一格**有粮可卖"（不分穷富）⇒ 不裁它，M2 的订单体系与撮合**上线第一天就全被自留吃掉**，报出来的缺口还是今天这个 | 首都 7.7 天粮 vs 自留 120 天；M0.4：三 tick 的缺口八成卡在 `SUPPLY_ZERO_*` |
| **乙** | **首都格人口基线以哪个为准** | §1.4 记 273,770、实测 360,862（同格同亩数）⇒ 一年期报表的历史数字要不要重修 | M0.6 + 我按原始 JSON 复核 |
| **丙** | **`ΣgrainDailyConsumption × 120` 与 `Σpop × 10,000` 差 1.58~5.34%（未解释）** | 它是 M0.3/M0.4/M0.6 三份读数的共同分母；不解释掉，后面每个"自给率"都带这个不确定度 | M0.4 §2.4、M0.6 |

★ 另有两个**已知但不阻塞**的账：① §1.4 里"奥斯特马克 Σ余 7,281 / 首都缺 444,561 / R2–R16 四连"
**复现不出**（已从 §1.4 撤下，以 M0.6 的读数为准）；② 三国自给率基线降级为近似（差 ≤0.13 个百分点）。
