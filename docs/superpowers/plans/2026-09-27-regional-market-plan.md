# 地区市场实现计划（P1–P5）—— 城乡交换 · 运输成本 · 价格 · 供应优先级

> **状态**：**计划（待开工）**，未改任何 Java、未跑 Maven。前置设计 = `docs/superpowers/specs/2026-09-27-regional-market-design.md`。
> **用户 2026-09-27 四项裁定**（本计划据此定形）：
> ① 市场形状 = **一城一市 + tier 半径**
> ② 运费落点 = **铸腿给承运主体**（不是记损耗）
> ③ 价格 = **基准价 + 档位危机溢价**（事件式、下周期生效）
> ④ GOV 主体 = **加一档**
> ★★ **④ 的实测更正（开工前必读）**：`ActorKind` **已经有 `GOVERNMENT`**（还有 `ORGANIZATION`）
> —— `simos-actor-api/.../ActorKind.java:29-43` 的七档是
> `PEOPLE_LOT, UNIT, GOVERNMENT, ORGANIZATION, HOUSEHOLD, ESTATE, WORKSHOP`。
> ⇒ **不需要扩枚举、不破坏任何既有契约**（`ActorTypesTest.actorKindCoversTheSevenDocumentedKinds` 的逐值断言**一字不用改**）；
> `ActorRef` 只校验 `kind != null` + `id` 非空白（`ActorRef.java:32-33`），对 kind **没有**别的守卫。
> ⇒ GOV 主体用 `GOVERNMENT`、承运主体用 `ORGANIZATION`（若裁定用"商号"）**或** `GOVERNMENT` 下的一个 id 约定。
> ★ 这一条把 ④ 的代价从"动稳定契约"降到 **0**；设计草案 §七-D 的三个选项按此收敛。

---

## 一、开工前必须认账的五条硬事实（代码实测，决定任务怎么切）

| # | 事实 | 出处 | 对任务的影响 |
|---|---|---|---|
| 1 | `simos-economy` **可以** import `simos-map`（已显式依赖），但 pom **禁 `simos-unit`** | `simos-economy/pom.xml:34-37`（map 依赖）、`:66-78`（bans 含 `simos-unit`） | **不能复用** `PathFinder` / `MovementCost` / `TerrainMovementCost`（它们在 unit）⇒ 运输成本必须**自己写**（或先把它抽象上移，见 T1.0） |
| 2 | `Transfer` **只有一个 `location`**，没有起点/终点对、没有在途日、没有运费、没有承运人 | `simos-economy-api/.../transfer/Transfer.java:84-93` | 跨格搬运 = **两条腿**（各自落在自己那格），或在 P4 才扩形状；P1 **不扩 `Transfer`** |
| 3 | **动态价格连写入口都没有**：没有"设价"命令；`withMarkets` 生产零调用；`economy.Seed` 对已占用格**整份拒绝** | `EconomyPayloads.java:127`；`EconomySeedHandler.java:76-81` | 档位溢价要**新建 handler + 新 command type**（T2.3），并进 `Shell.java:402` 的登记清单 |
| 4 | `EconomyData` **9 个组件**里没有地图/地形/邻接；`settleOneDay` 的入参里**没有 `GameMap`** | `EconomyData.java:113`（markets 是第 9 个）；`EconomySettlement.java:563-571` | **地图事实必须"播进数据"**（生成期算好写进 `Market`），结算期不许去问地图（否则要改 9 组件签名 + 破边界） |
| 5 | `map` 侧**没有** `neighbors(HexCoord)` / `moveCost(from,to)` / 路径 / 可达域；只有 `HexCoord.neighbors()`、`HexCoord.distanceTo()`、`TerrainType.moveCost`、路网 `Pathway`/`EdgeRef`/`EdgeTags` | 代码调查报告 §4（`GameMap` 全文 grep 零命中） | 格间代价要新写；口径可抄 `SettlementGenerator.java:257-261`（`cost = hexDistance × moveCost(目标格)`） |

★ 两条**好消息**：`EconomyChangeSet` 有**反射守卫**（`EconomyRoundTripTest` 会逼着新组件进变更集，铁律 5 自动把守，`EconomyChangeSet.java:31`）；
`FieldDelta` + `between/apply/isEmpty` 的加法是**纯追加**。

---

## 二、分期与验收

### P1 —— 地区市场（纯市场：不造钱、不造粮）

| 任务 | 内容 | 落点 | 验收（判据） |
|---|---|---|---|
| **T1.0**（可选前置） | 决定运输代价函数住哪：**A** 经济侧自写一个"格间代价"（只用 `map`：`distanceTo` + `TerrainCatalog.moveCost` + river/road 边）；**B** 把代价抽象上移到 `simos-util.spi`，unit 与 economy 共用 | A：`simos-economy/.../time/TransportCost.java`（新）；B：`simos-util/.../spi/`（动 unit） | A：**不改 unit**、零跨界；B：unit 侧要改实现 + 测试。**推荐 A**（P1 先落地，将来要共用再上移） |
| **T1.1** | `Market` 加三字段：`anchor: HexCoord`、`radiusHex: int`、`transportPerMille: Map<HexCoord, Long>`；`prices` 语义改为**基准价** | `simos-economy/.../model/Market.java` | 构造期守卫（anchor 非 null、radius ≥ 1、transportPerMille 逐值 ≥ 0）；`EconomyRoundTripTest` 绿 |
| **T1.2** | 生成期算运输成本并入载荷 | `simos-app/.../world/EconomySeeder.java`（`MARKET_FACTORY` → 逐城市场）；`config/worldgen/v17levant-nations.json`（`tierRadiiHex` 已就绪、`transportBonus` 复用） | 真档三种 tier 各抽一格：`transportPerMille` 逐值可核（给出算式）；**同格 = 0** |
| **T1.3** | 清算改"**逐市场**、跨格、按到货价撮合"：买家在锚格出价 = `基准价 × (1000+运费‰)`；卖家按**净得价**（= 基准价）结算 | `simos-economy/.../time/MarketSettlement.java`（`clearOncePerCycle` 从"逐格"→"逐市场"） | ① 跨格成交**存在**（逐笔可读出发/到格）；② 买家实付 = 到货价、卖家实收 = 净得价，**差额 = 运费**；③ 供需守恒式仍平 |
| **T1.4** | **承运主体**（裁定②）：`ORGANIZATION`（或 `GOVERNMENT`）的 id 约定 = 每市场一个承运人（如 `carrier@<anchor>`）；运费那一截铸 `TransferReason.CARRIER_FEE` 给承运人 | `simos-economy-api/.../transfer/TransferReason.java`（+ 一档）；`MarketSettlement.trade(...)` | 承运人账上运费**逐值可核**；商品与货币**双守恒** |
| **T1.5** | **经营者参与市场**（H6-lite 的"正解"，`progress.md:435`）：作坊/庄园的布、工具、纤维可以挂牌卖出 | `MarketSettlement`（供给侧纳入 `operatorGoods`） | 城镇布/工具有**卖出记录**；作坊不再"只有付出没有收入" |
| **T1.6** | 修文档漂移 + `AGENT.md` §〇 表（本轮实测：economy 已依赖 map；`simos-economy/pom.xml:77` 的 message 文案陈旧；`AGENT.md:54` 漏 map/actor-api） | `Market.java:30-31`（"只给城市格播种"vs 实际每格）；`AGENT.md` §〇 | 两处口径一致；台账记明"pom 才是事实" |
| **T1.7** | **市场读数**（否则曲线看不见市场）：逐市场 `supply / demand / 成交量 / 到货价 / 运费总额` 落进读口 | `ApiViews.economyHex` 或新只读工具 | 一年期报表能加一节"市场"（不必再靠反推） |

### P2 —— 索取源协议 = GOV 的槽（城乡交换的另一半）

| 任务 | 内容 | 落点 | 验收 |
|---|---|---|---|
| **T2.1** | 实现 `SurplusClaimSource` + `SurplusClaim(claimantId, commodityId, amount, phase, priorityPerMille, mandatory)`（**0 实现 → 1 个 provider**） | `simos-economy-api/.../spi/`（新）；`EconomySettlement` 加具名 phase | 协议有写者、有读者；"扣不动就记欠、不凭空造粮"（`aggregate-economy-v2-design.md:333-335`）逐条有用例 |
| **T2.2** | **实物税/征粮**（第一个 provider：按地或按产出，GM 可调） + **国家粮库**（`GOVERNMENT` actor 的 `GoodsAccount`） | `economy`（provider）+ `app`（GOV actor 的账，走 `OwnershipBooks` 同款落账） | 税收**逐值入国库**；`FlowRow.taxPaid` 从"恒 0"变成**有读者**（它本来就是本协议的槽） |
| **T2.3** | **城市配给**（粮库 → 城镇 cohort，按桶 1"生存"优先）+ 市场**档位危机溢价**（`D > S×阈值` ⇒ 下周期改写 `basePrices`，新 command type） | `economy` 的 phase + 新 handler + `Shell.java:402` 登记 | ★ **首都缺口下降 ≥ 1 个数量级**；"农村净收银、城镇净付银"出现**回流**；逐币种守恒仍成立 |
| **T2.4** | 优先级**桶**（生存 → 契约 → 一般 → 奢侈）接到市场清算 | `MarketSettlement` | 桶间严格串行、桶内比例配给（逐值可核） |

### P3 —— 承运与在途（裁定②的下半）

- **T3.1** 承运主体制度化（今天只有"每市场一个约定 id"）：谁能承运、承运能力（运力）从哪来。
- **T3.2** 在途**延迟**（v1 口径：在到货前不可被目的地消费 —— `POLITICAL_ECONOMY_DESIGN.md:157`）：
  ★ 这需要 `Transfer` **加形状**（起点格/终点格/在途日）或引入 `Shipment` 组件（`ShipmentId` 已是孤儿 ID，`simos-ledger` 退役后它指向的落点没了）。
  ⇒ **必须回填 `EconomyChangeSet` + `EconomyCodec` + 往返测试**（铁律 5）。
- **T3.3** 路网对运费的修正（今天 `roadModifier/riverModifier` 在 unit 侧是**恒等占位**）。

### P4 —— 多币种 / 跨政权（裁定 M1④ 的落点）

- 货币发行人（今天 `MoneyIssuance.REGISTERED = List.of()`，`requireIssuerOf` **恒抛**）；跨政权贸易的结算；**逐币种守恒**仍成立。
  ★ 前置：**"货币从哪来"是层 3 开工前必须裁的第一件事**（`target-economic-cycle-design.md:164`）。

---

## 三、明确不做（沿用设计草案 §六）

不做订单簿/逐笔挂单/锁余额（D2b）· 不做汇率与跨币种折算（M1②）· 不做资产市场/土地买卖 · 不做跨格搬人（属 social 侧）·
不做连续动态价（P2 只做档位）· **不让运费静默消失**（裁定②：铸腿给承运人）· **不给政府开空步后门**（走索取源协议）。

---

## 四、开工顺序与依赖（★ 不许反）

```
T1.0（代价函数住哪）→ T1.1（Market 加字段）→ T1.2（生成期播种）
   → T1.3（跨格清算）→ T1.4（承运腿）→ T1.5（经营者参与）→ T1.7（读数）
   → T2.1（索取源协议）→ T2.2（税/粮库）→ T2.3（配给 + 档位溢价）→ T2.4（桶）
   → T3（在途）→ P4（多币种）
```
★ **T1.5 必须排在 T2.3 之前**：首都的粮食要么靠"买"（需要 T1.5 让城镇卖得出去东西、银回流），
要么靠"配给"（T2.3）；先做 T2.3 会掩盖"市场根本不转"这件事。
★ **T1.7（读数）不能省**：没有读数，"缺口降了多少"只能靠反推（本轮评审就是这么踩的）。

---

## 五、纪律与验证（照 `AGENT.md`）

- **Maven 串行**（`tools/mvn-lock.sh`）；**别在服务在跑时 `package`**（§二）。
- 每期收口：`./mvnw clean verify`（前台，全仓；§七 三条门禁一起跑）。
- **一年期判据**：改完跑「一年（tick 120/240/360）」再对比本轮的基线读数
  （`h6raw_tick{120,240,360}.json` 已入库）——**不要**再跑 600 天（用户 2026-09-27 已裁定只跑一年）。
- 铁律 5：**加组件/加字段必须回填变更集**，否则 `EconomyRoundTripTest` 自动红。
- 收尾如实记：哪些判据达成、哪些没跑、哪些是**后补**的测试（`AGENT.md` §三.0）。

---

## 六、本计划没有回答的（留给实施期的裁定）

1. **运费系数怎么标定**（每 hex 多少‰、河流/海岸折多少）——今天只有 worldgen 的启发式乘数，无实测量级。
2. **承运人的运力从哪来**（P3）：是"无限运力"（先做）还是"按人口/道路算"（后做）。
3. **国家粮库的容量与周转规则**（囤多少、何时放、放给谁）——GOV 制度，P2 只给最小可用规则。
4. **税基与税率**（按人/按地/按产出）——GOV 制度。
5. **档位溢价的阈值与档位**（`D > S×?` ⇒ 涨多少‰）——需要标定，先给可调参数 + 保守出厂值。
6. **在途损耗率**（P3）与**承运人是否承担损耗**。
