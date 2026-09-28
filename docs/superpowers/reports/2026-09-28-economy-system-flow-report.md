# 经济系统：运作流程、模块对接与 SPI 报告（面向政治经济学分析）

> **用途**：给后续"政治经济学原理分析"提供**代码事实基线** —— 每个范畴在代码里对应什么、在哪里读写、边界在哪、哪里还是空白。
> **代码态**：`ts/m1` @ `5b6b2be7`（M0–M2 全部生产代码 + 收尾测试；一年期读数见
> `docs/superpowers/reviews/2026-09-28-m2-one-year-run-report.md`）。
> **纪律**：本报告只描述**现状**（回代码核过），不做主张；引用"计划/架构文档里的提案"时会**明确标注"未实现"**。

---

## 〇、一句话总览

现在的经济系统是「**自然经济 + 封建地租 + 一层已经能运转的区域市场**」：

- **生产资料**（土地 / 工具 / 作坊容量）以**产业产能**（`Industry.capacity`）记录，**谁经营**由 `Industry.operator` / `ProductionRelation.operator` 记录；
- **产品归属与剩余分配**由**生产关系**（`ProductionRelation`：`inputSupplier` / `rules` / `residualOwner`）与四档制度模板（`RegimeRelations`）决定；
- **劳动**以"阶层参与率 × 配额"（`LaborSupply` / `LaborAllocation`）分配，**实物给养义务**已具名（`SubsistenceObligation`）；
- **交换**走"每 5 天一轮的区域市场"（订单 → 区内/跨区撮合 → **唯一写口** `applyTransfer` 落账 → 跨区在途）；
- **账户与库存的唯一真源**在 actor 切片（`GoodsAccount`），经济侧只持有**会话副本**；
- 一切修改都必须走 **`Command → ChangeSet → Revision`**（铁律 2），状态形状由 `EconomyData`（10 组件）与 `EconomyChangeSet`（10 个 `FieldDelta`）**一一对应**（铁律 5）。

用政治经济学的说法：**生产力**的代码落点是「地图 + 产能 + 配方」，**生产关系**的落点是「`ProductionRelation` + 制度模板 + 劳动配额」，**流通**的落点是「区域市场 + 货币 + 运输」，而**上层建筑**（国家/税/国库/暴力）**尚未接入**——`ActorKind.GOVERNMENT` 与 `FlowRow.taxPaid` 只是留了座位。

---

## 一、位置与边界：谁认识谁

```
util ─→ map ─→ { social, unit, sd }
util ─→ economy-api ─→ { economy }          （economy-api 依赖 actor-api；actor 切片依赖 util+map+economy-api）
core 只依赖 util（+agentlib/sqlite/jackson）；领域模块在 core 里只许 test scope
app 是唯一组合根：认识全部领域模块
```

| 模块 | 在经济系统中的角色 | 硬边界 |
|---|---|---|
| `simos-util` | 状态/命令/时间/地址/Facet 的**协议层** | 不碰任何 simos 领域模块 |
| `simos-map` | **物质条件**：格身份 `HexCoord`、地形 `TerrainType.moveCost`、城市 `City`、道路 | 永不 import social/unit |
| `simos-economy-api` | **经济契约层**：稳定 ID、`CohortKey`、劳动/市场/货币/关系/转移的类型 | 只放稳定契约，无 Snapshot、无存储、无公式 |
| `simos-economy` | **经济本体的状态 + 结算**：产业/阶层行/债务/流水/劳动/关系/市场/在途 | 禁 unit/sd/core/app；**可**依赖 map（运输代价要用地形） |
| `simos-actor-api` | 主体与产权的最底层契约：`ActorRef` / `ActorKind` / `AssetKind` | 主依赖为零 |
| `simos-actor` | **主体身份 + 产权**：`ActorData` / `GoodsAccount`（余额+冻结）/ `ActorCodec` | 与 economy 是同层兄弟（它依赖 economy-api，反之不成立） |
| `simos-social` | **人口**：批次 `PopulationGroup`、出生/死亡 `PopulationDynamics`、城市 `SocialCity` | economy **不认识** social |
| `simos-unit` / `simos-sd` | **暴力与决策**：编制/移动；军队/决策人/裁定 | 与 economy **目前零直接耦合** |
| `simos-app` | **组合根**：创世、协调器、拓扑/读数装配、GUI/MCP 路由与工具 | 唯一同时认识所有模块的地方 |

> 政治经济学读法：这条依赖线就是「**物质条件 → 生产关系 → 流通 → 暴力/决策**」的实现顺序；
> 越靠右的层今天越薄，甚至完全缺席（国家、暴力对经济的占用与再分配）。

---

## 二、完整运作流程：从创世到一年

### 2.1 创世：把"世界"写成数据

```
MCP simos.worldgen.initialize（GM 工具）
  → app/EconomySeeder + HouseholdSeeder + WorldgenInitializeTool
    → 提交命令 economy.Seed
      → EconomySeedHandler.handle(state, payloadJson)     // CommandHandler SPI
        → EconomyPayloads 解析出 EconomyData（9+1 张表）
        → 同时在 actor 切片建账（家户/经营者各一本 GoodsAccount）
```

`EconomyData` 的十张表（这就是"经济世界"的全部状态）：

| 表 | 记什么（政治经济学范畴） |
|---|---|
| `industries` | 产业：产能（**生产资料**）、配方（**生产条件**）、经营者、制度 `regime` |
| `classes` | 阶层行（**阶级**的落点）：人口、劳动、参与率、货币、债务、自然需求/有效需求 |
| `debts` | 债务：**同源**的 debtor+creditor、本金、利率、到期、违约 |
| `flows` | 本期流水（所得/消费/税**恒 0**/利息/借贷/净盈余/未满足/死亡） |
| `laborSupply` / `allocations` | 劳动供给与**配额**（谁为哪个产业出多少劳动） |
| `relations` | **生产关系**：`inputSupplier` / 补偿规则 / `residualOwner` |
| `markets` | 每格市场：计价货币 + **参考价**表（→ 派生出 bid/ask 限价） |
| `shipments` | **在途**批次：跨区贸易的"货还在路上"状态（M2.4） |
| `meta` | 周期/关账元数据 |

### 2.2 推进：两阶段提案 + 日循环

```
MCP simos.advance {from,to}
  → app/AdvanceTool → CommandBus 提交 core.AdvanceTime
    → core/TimeAdvance：对每个 TimeParticipant 调 simulateWorld(base, range)   // 纯函数提案，不写状态
      → app/PopulationEconomyTimeParticipant.simulateWorld(...)               // ★ 唯一同时看见 social+economy 的参与者
           for day in range:
             ledger = stepper.step(day)                     // = EconomySettlement.settleOneDay(...)
             books  = OwnershipBooks.apply(books, OwnershipBooks.fold(ledger, REASONS_NOT_FOLDED))
             books  = OwnershipBooks.landHouseholdGoods/HouseholdMoney/OperatorGoods/OperatorMoney
           return WorldTimeProposal{EconomyChangeSet.between(…), ActorChangeSet.between(…)}
    → 合并提案 → 写入一条 Revision（铁律 2）
```

**日循环内部大致依次**（`EconomySettlement.settleOneDay`）：

1. **到货**：`ShipmentBatch.arrivalTick ≤ day` 的批次落回买方账户（损耗记 `market-transport` 账户）；
2. **周期投入**：`drawCycleInputs`（三遍式：调查 → 配给 → 落账）；封建档的地租、口粮、种子在这里计提；
3. **生产与分配**：`ProductionSettlement` 按关系规则逐条结算（R6 封顶：**实付 ≤ 本周期可用产出**）；
4. **劳动再分配**：`reallocateLabor` 按"缺口信号"把停工产业的配额吸走（工业吸走农业劳动力）；
5. **区域市场**：每 5 天一轮 + 低库存追加轮（`MarketTrigger`）；
6. **借粮 → 偿还 → 计息**：`lendDeficits`（最后手段）→ `repayDebts` → `chargeInterest`（按**当日起始本金**计息）；
7. **人口回写**：social 的出生/死亡经 `applyPopulationChange` 落到阶层行与劳动配额；
8. **流水**：`ProductionLedger` 收当天的转移与损耗，供读口与守恒网。

### 2.3 会话副本与唯一写口（经济侧的"会计"）

经济侧**不直接写** actor 的账；它拿的是四份**会话副本** + 四张**冻结表**：

```java
Map<CohortKey, Map<CommodityId, Long>> householdGoods;  // 家户商品
Map<CohortKey, Map<CurrencyId, Long>>  householdMoney;  // 家户货币
Map<ActorRef,  Map<CommodityId, Long>> operatorGoods;   // 经营者商品
Map<ActorRef,  Map<CurrencyId, Long>>  operatorMoney;   // 经营者货币
// + 同名四张 frozen*（M1.2「已明确的占用」：挂单/已承诺交付；生活保留不在这里）
```

所有换手只有**一个写口**（两遍式：先全量校验、再统一落账）：

```java
EconomySettlement.applyTransfer(householdGoods, householdMoney, operatorGoods, operatorMoney,
    householdFrozenGoods, householdFrozenMoney, operatorFrozenGoods, operatorFrozenMoney,
    householdOfActor, transfer);   // 市场、投入、地租、工资、借贷、偿还全都经它
```

推进结束时，app 用**绝对值**把会话副本落回 actor 账（`landHouseholdGoods` / `landHouseholdMoney` /
`landOperatorGoods` / `landOperatorMoney`），并**排除**市场成交在 actor 侧的重复折叠
（`OwnershipBooks.REASONS_NOT_FOLDED = {MARKET_TRADE}`——市场效果已由副本覆盖；折叠跨区货腿会在异地键上造幽灵账，2026-09-28 实测修掉）。

### 2.4 流通：区域市场怎么跑

- **调度**：每 **5 天**一轮（`MARKET_RESTOCK_INTERVAL_DAYS=5`）+ 粮覆盖 <10 天时窗口第 3 天追加一轮；
- **区**（`MarketTopology`）：城市节点 + 该城 tier 半径（MajorCity 16）——**现状不按国界**（实测 7 个区跨国界，见裂缝）；
- **价格**（`Market`）：参考价 + `bid=990‰ / ask=1010‰` 两个独立常量；自适应定价**默认关**（α≤5%/轮，定点）；
- **订单**（`BuyOrder` / `SellOrder` + 独立 `Budget`）：
  - 家户 `可卖 = max(0, 持有 − 已冻结 − 必要生产投入 − 生活保留)`，其中**生活保留 = 5 天 + 30 天安全库存**（用户裁定）；
  - 经营者同时挂**投入需求**与**产品卖单**；
- **撮合**：区内优先 → 跨区（第一版只考**直接邻接供应区**）→ 限价/预算/运力过滤 → 逐笔 `applyTransfer`；
- **跨区**：发运即结算（`MARKET_CROSS_REGION_SETTLEMENT_IMMEDIATE=true`），货进 `ShipmentBatch`（在途），
  **到货前目的地消费不到**；损耗逐票由**买方**承担（`LossBearer.BUYER`）；运费→承运人（**无 ORGANIZATION 时实收 0**）；
- **读口**：`MarketReadout`（经济侧 `public` 只读派生）+ `ApiViews.economyHex`（GUI 与 MCP **共用同一份视图**）。

### 2.5 铁律 5 的闭环（状态 ↔ 变更集）

```java
record EconomyData(…, Map<HexId, Market> markets, Map<ShipmentId, ShipmentBatch> shipments) {}  // 10 组件
record EconomyChangeSet(FieldDelta<…> meta, …, FieldDelta<Map<ShipmentId,ShipmentBatch>> shipments) {} // 10 个 FieldDelta
// EconomyRoundTripTest 用反射枚举把守：组件集必须逐项相同、apply(between(base,target), base).equals(target)
```

---

## 三、与其他模块怎么对接（逐模块）

| 对接对象 | 接口形态 | 谁实现/调用 | 现状注意点 |
|---|---|---|---|
| **social（人口）** | 不互相 import；由 app 的协调器转换：`PopulationGroup → ClassRow`（人口/劳动/参与率），死亡经 `applyPopulationChange` 回写 | `PopulationEconomyTimeParticipant` | **经济侧看不见 social 类型**；"人"的再生产只在协调器里缝合 |
| **map（地理）** | `HexCoord` 身份、`GameMap.terrainIndex/terrainTypes.moveCost`、`City`、`Pathway` | economy 直接依赖 map；`IndustryHexKeys` 是经济侧唯一的格键拼法 | 运输代价自写（economy 禁 unit，不能复用 `PathFinder`） |
| **actor（产权/账户）** | `actor-api`：`ActorRef/ActorKind/AssetKind`；`actor`：`GoodsAccount`/`ActorCodec` | 经济侧只依赖 **actor-api**；账户读写都在 app | **账户是库存的唯一真源**；经济侧只有会话副本 |
| **unit / sd（暴力与决策）** | **目前没有接口** | —— | 军队消耗、军费、征服、决策人经济行为**全空白**（后续分析重点） |
| **core（编排）** | `CommandHandler` / `TimeParticipant` / `ModuleCodec` / `ChangeSet` / `Revision` | `EconomySeedHandler`（命令）、两个 app 协调器（时间） | Core 只认 type 字符串与 namespace，**不 instanceof 领域类型** |
| **app（组合根）** | `EconomySeeder`/`HouseholdSeeder`（创世）、`MarketTopologyBook`（城市→区）、`MarketReadoutAssembly`（读数）、`OwnershipBooks`（账务） | app | 只有这里同时看得见 map/social/economy/actor |
| **GUI / MCP** | 读口 `ApiViews.economyHex/economyOwnership`；写工具走 GM 面 | `GuiServer`、`ToolSupport`、75 个 MCP 工具 | 读工具四桶共享；`simos.advance` 属 GM 面（"MCP 与 GM 同级"） |

---

## 四、SPI 在哪（逐个点名）

| SPI | 位置 | 形状（要点） | 经济侧的实现/使用 |
|---|---|---|---|
| `CommandHandler` | `simos-util/.../util/spi/CommandHandler.java` | `type()` + `handle(state, payloadJson)`，**纯函数、不写状态** | `simos-economy/.../economy/spi/EconomySeedHandler.java`（`type = "economy.Seed"`） |
| `TimeParticipant` | `simos-util/.../util/spi/TimeParticipant.java` | `simulate`（单切片）或 `simulateWorld`（多切片提案）；每个参与者拿同一份 base ⇒ **调用顺序不影响结果** | app 的 `PopulationEconomyTimeParticipant` / `EconomyOwnershipTimeParticipant`（多切片） |
| `WorldTimeProposal` / `TimeProposal` | `simos-util/.../util/spi/` | 提案 = 若干模块的 `ChangeSet` | 经济+actor 的双切片提案 |
| `ModuleCodec` / `ModuleDiffer` | `simos-util/.../util/spi/` | 状态快照/变更集的编解码与施加；**Core 与模块之间唯一触碰状态形状的地方** | `simos-economy/.../codec/EconomyCodec.java`；`ActorCodec` |
| `ChangeSet` / `Snapshot` / `StateMeta` | `simos-util/.../util/state/` | 铁律 5 的接口面 | `EconomyChangeSet` / `EconomySnapshot` |
| `CommandTargets` / `MutationGuard` / `AgentAttachPolicy` / `ResourcePaths` | `simos-util/.../util/spi/` | 命令目标解析、写保护、agent 挂接策略、资源路径 | 经济命令的落点校验 |
| `FacetProvider` / `FacetRegistry` | `simos-util/.../util/facet/` | **跨模块可见性协议**（"某个 hex 上有哪些单位"这类查询） | ★ **经济切片目前未注册 Facet**；跨模块读取走 app 读口 |
| `EconomyPayloads` / `EconomySnapshots` | `simos-economy/.../economy/spi/` | 载荷解析（**模块自己的 JSON 形状**）、快照类型 | 创世/追加播种的入口 |
| `ActorPayloads` / `ActorSeedHandler` / `ActorSnapshots` | `simos-actor/.../actor/spi/` | 账本侧同款 SPI | 创世建账、旧档兼容 |
| **契约层（不是 SPI）** | `simos-economy-api/.../economy/api/**`（`id` / `cohort` / `labor` / `market` / `money` / `relation` / `transfer` / `population`） | 稳定 ID 与稳定类型；**只读引用，不反向依赖** | 领域模块与 app 共用 |

---

## 五、政治经济学范畴 ↔ 代码落点（后续分析的接口表）

| 范畴 | 代码对应 | 读口 | 现状/缺口 |
|---|---|---|---|
| **生产资料占有** | `Industry.capacity`（土地/工具/作坊容量）+ `Industry.operator` | `ApiViews.industryView` | **缺** `AssetOccupation`/`UseRight`（架构文档提案，**未实现**）⇒ "谁占有"与"谁经营"还不能分离 |
| **生产组织** | `ProductionRelation.operator` + `Industry.regime`（feudal/household/handicraft/tenant） | `economyHex.industries[]` | 四档是**模板**（`RegimeRelations`），展开后才是数据 |
| **劳动来源** | `participationPerMille`（阶层投入率）、`LaborSupply`/`LaborAllocation` | `classes[].participationAdjustedLaborMilli` | **缺** `LaborSource`（家庭/租佃/依附/雇佣）——"劳动力如何成为商品"还答不出 |
| **产品归属 / 剩余分配** | `ProductionRelation.residualOwner` + `CompensationRule`（`RuleType`×`Pool`×`Weight`×`Recipient`） | `RuleSettlement`（当日；ledger 当日丢弃） | **R6 封顶**（实付 ≤ 本周期可用产出）；欠额不跨周期累计 |
| **实物给养义务** | `SubsistenceObligation`（谁→向谁→按什么劳动量→每周期多少）+ `retentionOf` | `industries[].subsistenceObligations` | 已具名、可查、保留有封顶；**无跨周期债务** |
| **地租** | `RegimeRelations.feudalRules`（受方 = `ToCohort(地主)`） | 同上 | ★ 仍是"**按名字（阶层）收租**"；"按占有关系收租"是待改点 |
| **债务 / 利息** | `Debt`（debtor+creditor **同源**）、`chargeInterest`（当日起始本金）、`repayDebts`、`lendDeficits` | `classes[].debtDetails`（双向）、`debtCount/creditCount` | 债权人侧不记"应收"（`FlowRow.income` 是粮口径）；不自动增可花余额 |
| **货币** | `MoneyVocabulary`（`CurrencyDef`/`MoneyInstrument`，`issuer/redeemer` 留位）、`MoneyIssuance`（REGISTERED 空） | `moneyLayers`（私人流通/基础货币/存款/未归类） | 逐工具守恒 `Σ持有 = 创世 + 累计发行 − 累计注销`；**发行/注销未实现** |
| **流通 / 市场** | `Market`（参考价+bid/ask）、`BuyOrder/SellOrder`、`MarketSettlement` 撮合、`TradeRoute/ShipmentBatch` | `marketReadout`（逐区逐商品：供给/需要/有效需求/成交/到货价/运费/损耗/未成交原因/闲置运力） | 价格固定（自适应默认关）；跨区只考邻接区；运费无承运人；**7 个区跨国界** |
| **再生产（物质补偿）** | `Industry.inputPerUnit`/`cycleInputPerUnit`、播种 `plantingDrawsFirst`、`naturalNeeds`/`effectiveDemand`、`GoodsAccount` 库存 | `grainDiagnosis`、`economyHex` | 生产投入与生活消费**分账**；种子的季节相位已建模 |
| **人的再生产** | social `PopulationDynamics`（出生/死亡）经协调器回写 `ClassRow` | `social.population`、`classes[].flow.{births,deaths}` | `deaths` **不是**饿死数（M0.2 口径）；生理压力→死亡率的链条在 social |
| **国家 / 上层建筑** | `ActorKind.GOVERNMENT`（留位）、`FlowRow.taxPaid`（**恒 0**） | —— | **国库/税/发行/银行 = 后置国家模块**（用户已裁）；再分配目前只能靠市场与借贷 |
| **暴力 / 决策对经济的占用** | 无 | —— | unit/sd 与经济**零耦合**：军费、军粮、战利品、征服后的产权变更全空白 |

---

## 六、已知裂缝（后续分析最该盯的地方）

1. **货币回流断裂**（一年期实测）：城镇银 7.4M → 231k（人均 5.8 → 0.2），布成交仅 224k（供给 32B+）⇒
   城市买粮的钱单向流入农村、无回流通道，城市买不起粮、缺口回升到 61.9 亿（详见一年期报告）。
2. **7 个市场区跨国界**：与"三国市场不互通"的裁定冲突（要么按国界裁剪区，要么补跨境结算口径）。
3. **运费 = 0**：创世没有 `ORGANIZATION` 承运主体 ⇒ 运输成本这一维实际上没被检验。
4. **收租按阶层名而非占有关系**：`ToCohort(地主)` 的写死形态（架构文档 §1.2 的待改点）。
5. **`ProductionRelation` 缺三面**：`occupations` / `useRights` / `laborSource`（架构文档提案，**未实现**）。
6. **M1.8 的"分不满"**：配额预算按折算后可用劳动封顶 ⇒ 默认配置下 Σ配额 < 折算后日劳动（合法但需知悉）。
7. **代理指标**：`participationPerMille` 是**投入率**而不是雇佣量；"劳动闲置/失业"目前只有折算差，没有失业量。
8. **读数窗口纪律**：`FlowRow` 各族按周期清零、`marketReadout` 每格携带整区读数（求和要按 `regionId` 去重）、
   `cycleNaturalNeedMilli` 用逐日日初人口 —— 任何跨期/跨区比较前先核窗口。

---

## 附：关键文件索引

| 概念 | 文件 |
|---|---|
| 经济状态（10 组件） | `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java` |
| 变更集（10 个 FieldDelta） | `simos-economy/.../change/EconomyChangeSet.java` |
| 切片刻度/写口 | `simos-economy/.../time/EconomySettlement.java`（`settleOneDay` / `applyTransfer`） |
| 日推进器（会话副本） | `simos-economy/.../time/EconomyDayStepper.java` |
| 生产与分配 | `simos-economy/.../time/ProductionSettlement.java` |
| 制度模板（四档） | `simos-economy/.../model/RegimeRelations.java` |
| 市场撮合 | `simos-economy/.../time/MarketSettlement.java` |
| 区域拓扑 / 读数组件 | `simos-economy/.../time/MarketTopology.java` / `MarketReadout.java` |
| 给养义务 | `simos-economy-api/.../api/relation/SubsistenceObligation.java` |
| 债务双向索引 | `simos-economy/.../model/DebtIndex.java` |
| 货币词表 / 发行留位 | `simos-economy-api/.../api/money/MoneyVocabulary.java` / `MoneyIssuance.java` |
| 账户（余额+冻结） | `simos-actor/.../model/GoodsAccount.java` |
| 账务落回（app） | `simos-app/.../time/OwnershipBooks.java` |
| 人口—经济协调器 | `simos-app/.../time/PopulationEconomyTimeParticipant.java` |
| 组合根装配（拓扑/读数） | `simos-app/.../time/MarketTopologyBook.java` / `MarketReadoutAssembly.java` |
| 读口（GUI/MCP 共用） | `simos-app/.../gui/ApiViews.java` |
| SPI 协议层 | `simos-util/.../util/spi/`（`CommandHandler`/`TimeParticipant`/`ModuleCodec`/…） |
