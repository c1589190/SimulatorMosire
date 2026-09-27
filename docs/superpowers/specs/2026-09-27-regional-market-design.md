# 地区市场（regional market）设计草案 —— 运输成本 · 价格 · 供应优先级 · 城乡交换

> **状态**：**草案（investigation + 规划）**，未实现、未改任何 Java、未跑 Maven。
> **由来**：一年期报表评审（`docs/superpowers/reviews/2026-09-27-year-one-report-review.md`）的结论
> ——「首都在饿、全国在攒粮」是**分配**问题，而分配的唯一制度出口（跨格贸易）被明文排为"下一阶段不做"。
> 用户 2026-09-27 裁定两点：① **首都缺粮正常**（GOV 未写，首都粮食供应是 GOV 的协调职责）；
> ② 下一步要做**更详细的城乡交换货币系统**，建议做成**通用的地区市场**（运输成本 · 货物价格 · 供应优先级）。
> **本文件的地位**：开工前待用户裁的四件事写在 §7；**裁完才写实现计划**。

---

## 一、先摆清"不做什么理由"的三件既有事实（都有出处）

| # | 事实 | 出处 |
|---|---|---|
| 1 | **跨格贸易从未设计过**：计划 §八.2 把它列为不做；`MarketSettlement` 类注自认「跨格市场（同格池之外没有搬运）、市场库存/订单簿、价格随供需浮动、运输损耗与关税」全不做 | `plans/2026-09-27-economic-cycle-implementation-plan.md:299`；`simos-economy/.../MarketSettlement.java:83-84` |
| 2 | **"动态价格"有公式但被排到"价格那一轮"，且价格被定为"数据不是公式"** | `specs/2026-09-27-target-economic-cycle-design.md:83`（`P' = P×(1+k(D−S)/(D+S))`，第二版才做）；`model/Market.java:10-17` |
| 3 | ★★ **GOV 的落点早就裁好了，只是 0 实现**：不是"给政府开两个空步"（那被否了），而是**索取源协议** `SurplusClaimSource` + `SurplusClaim(claimant, commodity, amount, phase, priorityPerMille, mandatory)`；**税、军队征粮、赈济、将来市场收运脚费，全是"从经济里往外拿东西"的同一个协议** | `specs/2026-09-25-aggregate-economy-v2-design.md:288-347`；`specs/2026-09-27-money-interface-design.md:83`（"不写税、不写财政支出"）；`EconomySettlement.java:826-827`（救济第③档今天空着） |

★ **纠一个编号坑**（否则评审与计划会互相误解）：本仓 **"S4" 有两个所指**——
**S1-S4 = 跨 Hex 贸易/运输/在途/损耗**（`2026-09-26-s1-actor-property-design.md:411`）；
**裁-S4 = 应付/实付进读数、欠款按规则开关**（`reviews/2026-09-27-review-response-transfer-layer.md:624`）。
本文件一律写 `S1-S4`。

---

## 二、先回答"地区市场能不能解决首都吃饭"——**不能，但它是必要的一半**

现状实测（tick360，三国）：

| 量 | 值 | 说明 |
|---|---|---|
| 城镇持银 / 农村持银 | **8,285,229 / 134,478,771 毫**（城镇占 **5.80%**） | 逐周期在被抽走：8.55% → 7.47% → **5.80%** |
| 城镇粮库存 | 1,287,853 单位 ≈ **13 天的口粮** | 逐周期靠买 + 借续命 |
| 城镇本期缺口 | 6,068,015 单位 | 满足率 ≈ 94% |
| 全国一年粮耗 vs 货币总量 | 113,578,320 单位 vs 142,924,800 毫银 | **货币总量约合 1.26 个月**的全国口粮（粮价 1 毫/单位） |

**结论（这决定分期，不是学术讨论）**：
- 货币**在总量上够**（1.26 个月的口粮量级 ⇒ 若周转 10 次/年就够一年的粮），**问题在分布与回流**：
  农村净收银、城镇净付银且**没有任何回流通道**（税/财政缺席）⇒ 城镇的钱只会单向流干。
- ⇒ **地区市场能让"有钱的缺口"买到粮**（把粮食送到出价最高的格、并给运输定价），
  但**买不起的格照样饿**（"没钱的缺口不是有效需求"是既有立场，`MarketSettlement.java:40-42`）。
- ⇒ **首都那 26% 的缺口只能靠 GOV 的强制索取（税/征粮 → 国家粮库 → 配给）**。
  用户裁定与此一致。**地区市场 + 索取源协议 = 城乡交换的完整回路**：
  市场负责"怎么换"（价格/运费/优先级），GOV 负责"拿什么换"（税基/粮库/配给）。

---

## 三、设计草案：地区市场（Regional Market）

### 3.1 机构形状：**一城一市，腹地按 tier 半径**（复用已经活着的参数）

`config/worldgen/v17levant-nations.json` 里 `tierRadiiHex` **本来就叫"市场半径"**，且**已经在生效**
（城市腹地归属用它：`SettlementParams.java:18-19`、`SettlementGenerator.java:42-44`）：

```
MarketTown [1,2] · Town [2,4] · City [4,8] · MajorCity [8,16]（★ MajorCity 用上界表达"跨区域"）
```

⇒ **地区市场 = 以城市节点的格为锚，半径 = 该城 tier 的上界**；一个格可以同时属于多个市场（越近越优先）。
★ 这条**不是新发明**：它把"腹地归属"与"市场范围"合成同一个概念，省掉第二套半径。

**新契约形状（草案）**：

```
record Market(
    CurrencyId numeraire,                    // 保留：单一计价货币（裁定 M1）
    HexCoord anchor,                         // 新增：锚（城市节点的格）
    int radiusHex,                           // 新增：半径（= tier 上界）
    Map<CommodityId, Long> basePrices,       // 保留（改名：出厂价/基准价）
    Map<HexCoord, Long> transportPerMille)   // 新增：逐格的运输成本（‰，相对锚）
```

★ **为什么把运输成本放进 `Market`（数据）而不是运行时算**：
① `Market` 的既有立场是"价格是数据，不是公式"（`Market.java:10-17`）——运输成本与价格同族；
② **模块边界干净**：`economy` 不去做寻路（`PathFinder` 在 `simos-unit`，`economy` 看不见 unit）；
③ 生成期已经手上有 `cost = hexDistance × moveCost(格)`（`config/...json:58` 的 `travelCostNote`）
与 `transportBonus`（riverJunction 1.5 / navigableRiver 1.35 / river 1.15 / coastal 1.4 / mountainPass 1.3）
⇒ 直接沿用，**只在 worldgen 里算一次**。

### 3.2 运输成本怎么进价格（**运费 = 买卖双方的价差，且必须显式落账**）

```
逐（卖家格 s，市场锚 a）：
  transportPerMille(s→a) = f(hexDistance, 沿途地形, 河流/海岸)      ← 生成期算好、进 Market 数据
  卖家净得价 = basePrices[j]                                        （基准价）
  买家在 a 的到货价 = ceil(basePrices[j] × (1000 + transportPerMille) ÷ 1000)
  运费那一截 = 到货价 − 净得价                                        ← 不做"平白消失"的差价
```

**运费那一截必须有落点**（守恒律要求，`POLITICAL_ECONOMY_DESIGN.md:157` 的"市场交易不创造商品或货币"）：
草案给两条腿、**默认取 A**：

- **A（推荐，最保守）**：`TransferReason.CARRIER_FEE` + **明示损耗**——运费的一部分计为
  "途中损耗"（从 Σ库存里减掉、在流水里**记出来**），另一部分铸一条腿给**承运主体**（今天没有承运人 ⇒ 先全记损耗）。
- **B**：全部作为损耗（等于对所有跨格成交征一道隐形税，**账面看得见**）。

★ 与既有禁令一致：**不许让差价静默消失**（"反对看起来在记、其实永远是 0"），
也**不新增第二个兑换机制**（裁定 M1③）。

### 3.3 价格：**基准价 + 危机溢价（事件式）**，不做连续动态价

- **基准价 = 数据**（`basePrices`，GM 可调；粮价 1 是整表的基准）。
- **危机溢价**（草案）：某市场在**开市当天**读完供需后，若 `D > S × 阈值`，把该商品价格
  **按档位乘一个系数**并在**下个周期生效**（写回 `Market.basePrices`，走 `Command → ChangeSet → Revision`，
  因此**可审计、可回滚**）。★ 与 `P' = P(1+k(D−S)/(D+S))`（第二版公式）相比，档位制的优点是
  "GM 看得懂、改得动"，且**不引入浮点**（全整数千分比）。
- ★ 明确**不报"S3 之前的价格/工资结论"**（裁定 C1 的边界，`review-response-transfer-layer.md:590`）。

### 3.4 供应优先级：**索取源协议就是优先级机制**（不要另造一套）

既有裁定已经把优先级排好了（`aggregate-economy-v2-design.md:333-335`）：
> 同一 `phase` 内按 `priorityPerMille` **降序**；同优先级按 `claimantId` 字典序；依次从该格该阶层的库存里扣；
> **扣不动就记欠，不凭空造粮**。

草案把它接到市场清算上，**两段式**：

```
第 1 段（强制/制度）：SurplusClaimSource 的索取（税、征粮、赈济、军队就地补给）
                     —— mandatory=true 的先扣；扣不动 ⇒ 记欠（欠税/欠粮）
第 2 段（市场）      ：剩下的可售余量按**需求桶**排序，逐桶清算：
                       桶 1 生存（口粮/衣着）· 桶 2 契约（工钱/地租的实物腿）
                       桶 3 一般需求 · 桶 4 奢侈（今天没有奢侈商品 ⇒ 空）
                     桶内按既有口径（需求量比例 + 最大余数），桶间严格串行
★ "钱"的约束不变：每个桶内仍然先算 afford（没钱的缺口不是有效需求）
```

★ 为什么优先级必须挂在索取源而不是市场：**市场只认钱**，而"军队征粮"、"首都配给"这类**不是买卖**，
用钱去表达等于把政府伪装成一个买家（那正是"给政府留两个空步"被否掉的原因）。

### 3.5 城乡交换（货币回路）

```
农村：卖余粮 → 得银（今天已经在发生：农村持银 94.2%）
城镇：卖布/工具/手工品 → 得银（★ 今天几乎不发生：城镇收入只有 handicraft 的货币工钱）
      + 买粮（今天的唯一活路）
缺口：城镇净付银、农村净收银、**没有回流** ⇒ 城镇的钱逐周期流干（8.55% → 5.80%）
⇒ 回路必须补两条腿之一：
  ① GOV 强制索取（税/征粮）→ 国家粮库 → 城市配给（**用户已裁定这是 GOV 的职责**）
  ② 城镇向农村**卖得出去东西**（布/工具/手工品），让银回流
★ ②这一条今天被两个原因堵住：**作坊停工**（H6-lite 已修，待线上验证）与**经营者不参与市场**
  （`MarketSettlement.java:86-89`：买卖双方只有家户 ⇒ 作坊的布/工具根本没有挂到市场上）
```

⇒ **本设计把"经营者参与市场"从 H6-lite 的"正解（以后做）"提为 P1**
（`progress.md:435` 原文：「正解是让经营者参与市场（H4 的市场今天只有家户参与）」）。

---

## 四、分期（草案；每期都有可判死的验收，按"一年期报表"读数）

| 期 | 内容 | 验收（真档一年读数） |
|---|---|---|
| **P0（前置）** | 裁定"世界生成 vs 经济层"的对齐口径（评审 A-1）：**保留首都规模**（承认它靠外部输入）+ 明确"在 GOV 落地前，首都缺口是预期现象"写进报表口径 | 报表口径那一节改文；不再把首都缺口当缺陷报 |
| **P1（地区市场）** | ① `Market` 加锚/半径/运输成本（契约 + 生成期算出）；② 清算改"逐市场、跨格、按到货价撮合"；③ **经营者参与市场**（作坊的布/工具能卖）；④ 市场播种范围的文档漂移对齐（`Market.java:30-31` vs `EconomySeeder.java:859-861`） | 城镇布/工具**有**卖出记录；跨格成交存在且运费可见；全国缺口下降；**货币逐币种仍守恒** |
| **P2（索取源协议 = GOV 的槽）** | 实现 `SurplusClaimSource`（0 实现 → 一个 provider：**实物税/征粮**）+ **国家粮库**（一个 actor：`GOV`/`NATION` 的 `GoodsAccount`）+ **城市配给**（按桶 1 发放） | 首都缺口下降 ≥ 1 个数量级；**"农村净收银、城镇净付银"出现回流**；税入国库**逐值可核**（不凭空造粮） |
| **P3（在途/路线/损耗）** | 运输**延迟**与**在途账**（只有一个 owner、到货前不可消费——v1 §7 的口径，`POLITICAL_ECONOMY_DESIGN.md:157`）；损耗率 | 在途商品在账上可见；跨格成交的**货到时间**可核 |
| **P4（多币种/跨政权）** | 裁定 M1④ 的落点 + 货币发行人（今天 0 实现，`MoneyIssuance.REGISTERED = List.of()`） | 跨政权成交；**逐币种守恒**仍成立 |

★ **P1 与 P2 的顺序不能反**：P1 让"有钱的缺口"买到粮，P2 才让"没钱的缺口"活下来；
但 **P2 依赖 P1 的市场形状**（粮库把粮投到哪个市场、配给按哪个价折），所以先 P1。

---

## 五、实现落点与模块边界（回代码核过）

| 要改的 | 落点 | 边界约束 |
|---|---|---|
| `Market` 加字段 | `simos-economy/.../model/Market.java` | 纯数据契约；`economy` 已依赖 `simos-map`（`pom` 实测）⇒ **可以**用 `HexCoord`，**不可以**用 `simos-unit.PathFinder` |
| 运输成本算一次 | `simos-app/.../world/EconomySeeder.java`（生成期）+ `config/worldgen/v17levant-nations.json` | 生成期在 app 层，**能同时看见 map 与 economy** ⇒ 这里是唯一合法的"跨模块拼装点" |
| 跨格清算 | `simos-economy/.../time/MarketSettlement.java`（`clearOncePerCycle` 从"逐格"改"逐市场"） | 仍然只改**会话工作副本**，成交一律铸 `Transfer` 走唯一 applier |
| 索取源协议 | `simos-economy-api/.../spi/`（新 `SurplusClaimSource`）+ `EconomySettlement` 的具名 phase | 协议在 `spi`（新增契约一律放 `util.spi`/`economy-api` 的既有先例）；**不许给某个模块开后门** |
| 新转移原因 | `simos-economy-api/.../transfer/TransferReason.java`（+ `CARRIER_FEE` / `TAX_IN_KIND` / `RATION`） | 枚举是 fail-closed 的（未知值抛）⇒ 加值要同步所有 switch |
| GOV 主体的账 | `simos-actor`（`ActorKind` 是否加一档？→ **待裁**，见 §7-D） | `ActorKind` 是稳定契约，加档要动 `actor-api` |

---

## 六、明确不做（边界，避免又变成"600 tick"那种越界）

1. **不做订单簿 / 逐笔挂单 / 锁余额**（裁定 D2b：市场 = 一组发出转移的规则，不是撮合引擎）。
2. **不做汇率 / 跨币种折算**（裁定 M1②，`Market.java:13-14`：币种之间不许求和、不许折算）。
3. **不做资产市场 / 土地买卖**（计划 §八.6 已封存）。
4. **不做跨格搬人**（跨格迁移属 social 侧，另裁；本设计只搬货）。
5. **不做连续动态价格**（基准价 + 档位溢价；公式版留"价格那一轮"）。
6. **不让运费静默消失**（要么记损耗、要么铸腿给承运人）。
7. **不给政府开空步后门**（必须走索取源协议）。

---

## 七、★ 待用户裁的四件事（裁完才写实现计划）

| # | 决策点 | 选项 | 我推荐的理由 |
|---|---|---|---|
| **A** | 地区市场的形状 | ① 一城一市 + tier 半径（§3.1）② 固定半径（如 6 格）与城市无关 ③ 先只做"最近城市"单归属 | **①**：`tierRadiiHex` 已经是活的参数，且与城市腹地同义，省一套半径 |
| **B** | 运费那一截的落点 | ① 明示损耗（先全记损耗）② 铸腿给承运主体（今天没有承运人 ⇒ 先造一个 `CARRIER` actor）③ 挂账（记欠） | **①**：最保守、守恒式最简单；等 P3 有在途再谈承运人 |
| **C** | 价格机制 | ① 基准价 + 档位危机溢价（事件式，下周期生效）② 公式版 `P' = P(1+k(D−S)/(D+S))` ③ 完全不动价（只做运费） | **①**：全整数、GM 可调、可审计；②的 `k` 没有标定依据，③会让"运输成本"成为唯一价差（信息太少） |
| **D** | GOV 主体的身份 | ① `ActorKind` 加一档 `NATION`/`GOV` ② 复用 `ESTATE`（像今天的经营者）③ 复用 `HOUSEHOLD` 的"政府家户" | **①**：裁定 S1 当初"复用 `HOUSEHOLD` 不加档"是为**家户**；政府与家户的语义差别（强制索取 vs 自愿交换）比家户内部差别大得多。★ 代价：动 `actor-api` 的稳定契约 |
| **E**（附带） | `Market` 加字段会不会破坏往返 | 加字段必须同步 `EconomyChangeSet` + `EconomyCodec` + 往返不变式测试（铁律 5） | 这不是选择，是纪律：**加组件/加字段必须回填变更集**，否则重演 `MapDiff` 那次静默丢字段 |

---

## 八、本文件**没有**回答的（要用户或下一轮裁定）

1. **运费系数怎么标定**（每 hex 多少‰、河流/海岸折多少）——今天只有 worldgen 的启发式乘数，**没有实测量级**。
2. **国家粮库的容量与周转规则**（囤多少、什么时候放、放给谁）——属 GOV 制度，本草案只给"槽位"。
3. **税基与税率**（按人、按地、按产出？）——属 GOV 制度。
4. **在途损耗率**（P3）。
5. **跨政权贸易的货币结算**（P4；今天三个政权其实都用 `silver`，`RegimeRelations.DEFAULT_CURRENCY` 是唯一拼写点）。
6. **经营者参与市场后，作坊的布/工具怎么定价**（`basePrices` 里已有 `cloth=5 / tool=20`，但没有"经营者挂单"的口径）。

---

## 九、可复用/可核的锚点（给实现者）

| 用途 | 现成件 | 位置 |
|---|---|---|
| 距离 | `HexCoord.distanceTo` / `neighbors()` | `simos-map/.../hex/HexCoord.java:25,72` |
| 地形成本 | `TerrainType.moveCost`（平原 1 · 山地 6 · 海洋 999=不可通行哨兵） | `simos-map/.../terrain/TerrainCatalog.java` |
| 城市腹地与半径 | `tierRadiiHex` + `SettlementGenerator` 的 `Influence = W / cost^k` | `config/worldgen/v17levant-nations.json:49-55`；`SettlementGenerator.java:42-44` |
| 单位移动的 A*（**不能直接用**，只作口径参考） | `PathFinder.findPath(...)` + `MovementCost` | `simos-unit/.../move/PathFinder.java:59` |
| 市场清算 | `MarketSettlement.clearOncePerCycle` / `clearOneCommodity` / `effectiveDemandOf` | `simos-economy/.../time/MarketSettlement.java:123,170,370` |
| 转移原语（唯一合法写入口） | `Transfer` + `applyTransfer` | `simos-economy-api/.../transfer/Transfer.java`；`EconomySettlement.applyTransfer` |
| 索取源协议（0 实现） | `SurplusClaimSource` / `SurplusClaim` | `specs/2026-09-25-aggregate-economy-v2-design.md:288-347` |
