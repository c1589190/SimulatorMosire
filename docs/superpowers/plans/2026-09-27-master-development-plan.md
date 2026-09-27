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

# 第三部分：M0 —— 仪器（先决，不碰模型）

### M0.1 推进路径一致性 ★★
- **做什么**：同初态同终点，**一次 360 天 == 三次 120 天**，且**余额 / 库存 / 债权 / 在途 / 累计流水逐项相同**。
- **落点**：新用例（照 `EconomySettlementEndToEndTest:587 oneHundredFiftyDaysInOneCommandEqualsOneHundredFiftyDailySteps` 的形状扩到 360 vs 120×3）。
- **判据**：逐项相等；不等就打印**首个不等项**（黄金文件式对拍）。
- **不做**：不改任何生产代码（除非暴露真缺陷）。
- **备注**：现有同族用例**只到 150 天**，没有 360 那一档。

### M0.2 单位与统计窗口归位（纯口径）
- **做什么**：把 `consumed` 的"留种/损耗"三处漂移定为一处（`FlowRow.java:48` 注 vs `EconomySettlement.java:133` vs v2 §3.4）；
  在读数里标明 `unmetNeed` 是**本周期累计**（关账日归档、次日清零）。
- **落点**：注释 + 报表口径文字；**不改行为**。
- **判据**：三处文字与代码一致；报表口径节写明窗口。

### M0.3 每格粮食诊断七项 ★
- **做什么**：逐格输出 ①生产自给率（**必须标明是否扣种子与损耗**）②可用库存覆盖天数 ③预计进口需求
  ④**有效购买力缺口** ⑤**物流缺口** ⑥币种/支付缺口 ⑦实际满足率与未满足人日。
- **落点**：`simos-app/.../gui/ApiViews.java` 新 `economyDiagnosis(HexCoord, EconomyData, ActorData)` +
  MCP 只读工具（照 `EconomyHexTool` 的形状）；聚合脚本 `h6agg.py` 加一节。
- **判据**：一年期报表能逐格给出七项；★ **不设 ≥100% 门槛**（用户裁定：诊断读数）。
- **不做**：不改状态、不加组件。

### M0.4 未成交原因归因
- **做什么**：把"未成交"归因为：缺实物 / 缺购买力 / 缺运力 / 到货太晚 / 限价不合 / **算法候选未覆盖** /
  （延后：缺外汇 / 贸易限制）。允许复合，标主因。
- **落点**：`MarketSettlement` 的早退分支各加一条**归因计数**（局部累加器 → 进 ledger 读数）。
- **判据**：报表能区分"世界没余粮"与"算法没搜到"。

### M0.5 回归网（三条守恒）★★
- **做什么**：①**关系守恒** `Σ各主体所得 = 净产`（逐商品逐周期，且每条能追到一条关系）；
  ②**市场守恒** `Σ买方实付 = Σ卖方实收`（货腿钱腿成对）；③**债务守恒** `Δ本金 = 新借 + 计息 − 偿还`。
- **落点**：`simos-app/src/test/java/io/mosire/simos/app/world/EconomySettlementEndToEndTest`（追加三条用例）。
- **判据**：三条绿；★ 在它们绿之前**不许动** `MarketSettlement` 与偿还链。
- **为什么**：这两条链**今天零测试**（实测）。

### M0.6（C 裁定）worldgen 自养校验
- **做什么**：创世期对每格算"本地可用粮 ÷ 全格需求"，**标出必须靠输入的格**，并**校验集散节点（城）周边 R8/R16 内可调余粮能否覆盖其缺口**。
- **落点**：`simos-app/.../world/EconomySeeder.java` 的 plan 阶段 + 读口（可复用 M0.3）。
- **判据**：奥斯特马克那 138 格被**明确标出**（首都只能补 1.6%）；★ **不**据此自动改人口（那要另裁）。
- **备注**：用户裁定 **C+A**：C = 把失衡在创世期**暴露**（本轮先"暴露"，不擅自改数）；A = 国内调剂、跨国不开。

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
第一批（不碰模型，风险最低）：M0.1 → M0.5 → M0.2 → M0.3 → M0.4 → M0.6
第二批（账户与关系）：        M1.1/M1.2/M1.3 → M1.4 → M1.5 → M1.6 → M1.7 → M1.8
第三批（一般市场＝本轮目标）： M2.0 定案 → M2.1 → M2.2 → M2.4 → M2.3 → M2.5 → M2.6 → M2.7
可选增厚：                    M3
后续独立立项：                M4+（国家模块：国库/发行/税/外汇/银行）
```

★ **每批结束都要**：跑全仓门禁 + 出一份一年期读数对比 + 把"达成/未达成/没跑"写进
`.superpowers/sdd/<本阶段目录>/progress.md`。
