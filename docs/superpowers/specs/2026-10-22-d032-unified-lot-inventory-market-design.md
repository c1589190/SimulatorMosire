# D-032 统一物品 Lot、库存表与通用市场订单设计（2026-10-22）

> **局部取代（2026-10-22）：** `ProductionProcess.stageState` / `ProductionData.instances` 作为土地长期生产进度权威的部分，由 `docs/superpowers/specs/2026-10-22-d032-lot-carried-production-architecture-v1.md` 取代；统一 InventoryLot / 转移接口 / 市场订单部分继续有效。

> 状态：设计草案，未改 Java/XML/POM/测试，未提交。
> 父设计锚点：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` 的 `S0`–`S8`。
> 本文是大架构重设计，取代 `.superpowers/sdd/2026-10-22-d032-production-stages-v1/design-note.md` 中
> “土地/商品分开的 order 变体、ProductionLot 只作只读生产视图”的部分；旧文档仍保留调查与用户裁定留痕。
> 本文不取代标准农业的 S0–S8 主链，只扩展 S1/S3/S4/S5/S7 的物品、库存、市场与长期生产资料接缝。

## 0. 先给结论

采用一个统一的 **`InventoryLot` 物品批次**：

```text
库存中的每一行 = InventoryLot
  lotId
  itemKind        COMMODITY | LAND | ASSET | ...
  itemTypeId      商品 id / 土地类型 / 资产类型
  owner
  location?
  quantityMilli
  state           COMPLETE | IDLE | IN_PRODUCTION(...)
  origin?         初始/生产/转移/拆分来源
```

它是 **逻辑上的一张库存表**，不是新的持久化权威：

- `COMMODITY` 行由 actor 的 `HouseholdInventory.balances` 支撑；
- `LAND` 行由 map 的 `LandComponent` 支撑；
- `ASSET` 行由 economy 的资产/份额账支撑；
- 需要独立生产状态的普通物品，行由 actor 或 economy 的持久 lot 表支撑。

市场只看到由这些行派生出的 **`MarketListing`**：

```text
MarketListing
  sourceLotId
  itemType
  quantityMilli
  estimatedValueMilli
```

即用户口径中的“商品在市场上只有预估价值和来源”。生产状态不复制进订单；成交时按
`sourceLotId` 找到库存行，状态随 lot 一起转移。

所有买卖共用 **一个 `MarketOrder` 类型**，不再有 `BuyOrder` / `SellOrder` / `LandOrder`
三套形状；土地只是 `itemKind=LAND` 的一种 lot 策略，不再是一个独立的订单分支。

## 1. 目标运行序列（本设计嵌入 S0–S8 的位置）

### 1.1 S1 经济载入：构造唯一 InventoryLotView

每 tick 载入完成后、S2/S3 开始前，由 app/economy 组装只读的 `InventoryLotView`：

```text
actor HouseholdInventory.balances
  → 每个 (owner, commodity, owner 当前所在 hex) 一条 COMMODITY lot，state=COMPLETE

map HexLand[hex].byType(ARABLE)
  → 每个 LandComponent 一条 LAND lot
     lotId = LandComponentId
     owner = component.attributes[OWNER] 解析出的 HouseholdId
     state = IDLE 或 IN_PRODUCTION(
         plan= CROP, stage= STAGE, stageProgress= PROGRESS,
         cycleProgress= CYCLE_PROGRESS, crop= CROP_OUTPUT, ...)

economy OwnershipStake / stateful asset lots
  → 每个资产 lot 一条 ASSET lot（v1 可只读，不参与农业）

actor/economy stateful goods lots（按需新增）
  → 每个持久 lot 一条 COMMODITY/ASSET 行，state 来自该行本身
```

规则：

- view 是**只读投影**；不落进 `EconomyData`，不进 `EconomyChangeSet`/Codec；
- 同一 lot 在 view 中只能出现一次；重复 id、缺 owner、负数量、跨 hex 数量不守恒 ⇒
  契约故障，S1 fail-closed；
- view 的排序键固定为 `(itemKind, typeId, location, owner, lotId)`，不依赖 Map 迭代序；
- 普通商品没有持久 lot id 时，view 生成确定的聚合 id；它只是 view 的稳定键，不是第二本身份。

### 1.2 S3 生产位置选择：从库存 lot 读长期生产资料进度

生产候选不再只读 `LandHolding` 或聚合 `ProductionProcess.progressDays`，而是从
`InventoryLotView` 找可用的长期生产资料：

```text
ProductionBatchCandidate
  planId
  carrierLotId?          // 土地/机器等长期生产资料的库存行
  availableQuantity
  currentState?          // 从 carrierLot.state 读取
  expectedInput
  expectedOutput
  expectedNet
```

- 有 `IN_PRODUCTION` 状态的 lot：按其现有 stage/progress 继续，不重置；
- `IDLE` lot：从 stage 0 开始新批次；
- 同一户同一 hex 可以同时有多条不同 stage/progress 的 lot，因此天然表达
  “10 亩在第二阶段 10%、1 亩刚归零”；
- 选择仍按用户已确定的综合效益评分：
  `expectedNet / max(1, expectedInputValue)`，再按输入低、产出高、lot id 稳定序破平；
- 选中后对 lot 做 **LotReservation/占用**，防止同一长期生产资料被两个批次重复使用；
- 新取得土地仍按既有 S0–S8 口径从下一生产 tick 才可用。

### 1.3 S4 生产日结算：lot 状态是唯一载体

每个 `ProductionBatch` 有且只有一个状态权威：

- `carrierLotId` 存在 ⇒ 状态权威是该库存 lot；batch 只持有引用和运行上下文，不复制
  stage/progress；
- 没有 carrier lot ⇒ 状态权威是 production 的 batch 状态（服务、无固定载体的生产方式）。

每日顺序：

1. 读 `carrierLot.state` 与当前阶段的 start/tick 投入要求；
2. 计算可用劳动、可投入商品、长期生产资料占用；
3. 不足时按“可用量缩规模”：
   - 覆盖部分继续当前 stage；
   - 未覆盖部分从原 lot 拆出，`state=IDLE, stage=0, progress=0`；
   - 同一 revision 内完成拆 lot、数量和归属校验；
4. 扣减当日商品投入、推进 stage progress；
5. 阶段结束：
   - `NEXT_STAGE` ⇒ 保持 carrier lot，切换 stage，progress 归零/保留 stage-start 状态；
   - `HARVEST`/最终阶段 ⇒ 产出商品 lot，清理 carrier lot 的生产状态（回到 `IDLE`）。
   阶段结束时允许产出中间商品 lot；普通中间商品进 actor 库存并按 `COMPLETE` 处理。

失败语义：批次失败不得留下“扣了一半的投入/推进了一半的进度/拆了一半的 lot”。

### 1.4 S5 收获/产出：产出也是库存 lot

- 产出先形成物理商品 lot，再按既有生产分配/权益规则落到具体 owner；
- 普通商品 lot 的 `state=COMPLETE`，可以合并成 fungible stack；
- 需要保留生产状态的东西（未完成工具、在建作坊、带作物的土地等）形成
  `IN_PRODUCTION` lot，不能压成普通 `CommodityId -> long`；
- `origin` 记录 run/tick/sourceLot，作为审计来源，不参与价格形成。

### 1.5 S7 市场：统一 listing + 统一 order

市场轮开始时，从 S1 的 `InventoryLotView` 生成本轮 `MarketListing`：

```text
每个可卖 lot → 一条 listing
  sourceLotId = lot.lotId
  itemType    = lot.itemType
  quantity    = 可卖数量的快照
  estimatedValue = 当前市场价 × 数量（无价则 empty）
```

订单：

```text
MarketOrder
  orderId
  side            BUY | SELL
  actor           买方/卖方
  location        BUY 的交割格 / SELL 的发货格（土地 = 该 hex）
  item            MarketItemRef
  sourceLotId?    SELL 必填；BUY 可指定具体 lot，也可留空让 matcher 选
  quantityMilli
  limitUnitValue? SELL 最低价；BUY 可空（价格接受者）
  payWith
  budget?         BUY 可带；空 = 由 planner 按价格接受者/信用派生
  priority
```

`MarketItemRef` 只有三种语义：

```text
COMMODITY  { commodityId }
LOT_TYPE   { itemKind, itemTypeId, location? }   // 任意同类型 lot
LOT        { lotId }                             // 指定具体 lot
```

匹配：

1. 先按 `itemKind` 选择 item policy；
2. `COMMODITY` 仍走 fungible 聚合/数量撮合；
3. `LAND`/`ASSET`/其他可状态化物品按具体 `sourceLotId` 撮合，状态不进入订单；
4. `LOT_TYPE` 的 BUY 订单由 matcher 按 `(location, sourceLotId)` 稳定序选择卖方 lot；
5. 卖方 lot 的 quantity 在成交前冻结；
6. 部分成交时拆分 lot，复制除 owner 外的全部状态；整 lot 成交只改 owner；
7. 成交后买方的 inventory view 自动看到同一 state 的新 owner。

价格：

- `estimatedValueMilli` 是 listing 的市场估值，用于排序、财富/债务读数，
  **不是成交价本身**；
- 成交仍使用既有 bid/ask/market price；`limitUnitValueMilli` 是订单保护价；
- 土地仍按市场区每 mu 地价，状态不单独计价（v1）；以后可加基于阶段/进度的估值器；
- 土地无运费；商品/可运输资产沿用现有运力/运费；
- 0 价成交合法但债务清算中不自动用 0 价土地冲本金（延续既有裁定）。

### 1.6 对父序列的影响表

| 维度 | 影响 |
|---|---|
| 顺序 | 不新增 tick 阶段；S1 多一次只读 view 组装，S3/S4/S5 改读 lot 状态，S7 改用 listing/统一 order；S0/S2/S6/S8 语义不变 |
| 预算/冻结 | fungible 商品仍按 `(owner, commodity)` 冻结；有状态 lot 按 `lotId` + 数量冻结；listing 的 source lot 在成交前必须冻结，防止同一 lot 双卖 |
| 身份/持久化 | `lotId` 稳定；LAND 用 `LandComponentId`；普通商品 view id 派生；不新增第二份可写库存；非土地持久 lot 只在 U5 新增 actor/economy 组件 |
| 失败/回滚 | view 组装失败 = S1 契约故障；lot 拆分/状态推进/货币债务/土地 owner 必须在同一 revision；任何一步失败 ⇒ 零半提交 |
| 日志 | INFO：listing/trade/lot 状态转移/缩规模；DEBUG：候选与 source lot 选择；ERROR：重复 lot、owner 缺失、数量不守恒、同 lot 双占 |
| 测试 | U1 一表完整性；U2 一订单类型与旧数值不变；U3 component 状态转移；U4 多 stage/缩规模；U5 长期资料进度与占用；U6 读口/负例/守恒 |

## 2. 为什么不能把持久化合成一张物理表

用户要求的“所有东西在一张表”在**读模型**中实现，不在持久化层强行合并：

| 事实 | 唯一权威 | 理由 |
|---|---|---|
| 商品数量 | actor `HouseholdInventory.balances` | 现有商品库存守恒与账户键已在此 |
| 土地面积/空间归属 | map `LandComponent` | Map 拥有 LAND；hex 固定耕地与 component id 是空间事实 |
| 土地生产状态 | map component attributes | 用户口径：状态数据放 Map，生产解释/维护 |
| 资产/占用 | economy 资产账 |  Economy 拥有资产份额与生产占用 |
| 生产 batch/run | production | 生产定义、阶段和批次推进归 production |
| 市场 listing/order | economy 轮内瞬态 | 不落盘；只引用源 lot |

如果让 actor 或一个新模块同时存土地行和商品行，会产生：

- 同一块土地的 owner/quantity 两处可漂移；
- 同一商品余额的守恒式两处；
- ChangeSet/Codec 需要跨模块写同一事实；
- 市场冻结和物理库存无法确定唯一真源。

因此：

- `InventoryLotView` 是**一张逻辑表**，由各域权威行拼成；
- 任何写操作仍回到所属域的 ChangeSet；
- 如果未来确实需要物化表，只能做**可重建索引**（lotId + backer ref），
  数量、owner、state 仍不得复制成第二权威。

## 3. 统一数据形状

### 3.1 `InventoryLot`

```text
InventoryLot
  lotId: InventoryLotId
  itemKind: COMMODITY | LAND | ASSET | ...
  itemTypeId: String
  owner: HouseholdId（v1；以后可扩为 ActorRef）
  location: Optional<HexCoord>
  quantityMilli: long
  state: LotState
  origin: LotOrigin
  fungible: boolean
```

约束：

- `quantityMilli > 0`；
- `LAND` 必须有 location，`lotId` 必须是 `LandComponentId` 的规范映射；
- `COMMODITY` 聚合行 `state=COMPLETE`、`fungible=true`；
- `LAND`/`ASSET` 默认 `fungible=false`，但允许按 item policy 做同状态合并；
- `IN_PRODUCTION` 状态必须带 plan/stage/progress；进度不得超过 stage 定义；
- `owner` 必须能在 S1 解析到活家户/合法账户；v1 只接受 household owner。

`LotOrigin` 记录来源，只作审计/追溯，不参与价格形成：

```text
LotOrigin
  kind        INITIAL | PRODUCED | SPLIT | TRANSFERRED
  sourceLotId?  // SPLIT 的来源；TRANSFERRED 的源 lot
  runId?        // PRODUCED 的生产批次
  tick          // 发生日
```

逻辑表示例（不是物理单表）：

| lotId | kind | typeId | owner | location | quantity | state |
|---|---|---|---|---|---|---|
| `goods:h1:grain` | COMMODITY | `grain` | h1 | 0,0 | 1000 毫粮 | `COMPLETE` |
| `land:arable:0_0:7` | LAND | `ARABLE` | h1 | 0,0 | 10,000 毫亩 | `IN_PRODUCTION(wheat, stage2, 10%)` |
| `land:arable:0_0:8` | LAND | `ARABLE` | h1 | 0,0 | 1,000 毫亩 | `IDLE` |
| `asset:h1:tool:3` | ASSET | `LARGE_TOOL` | h1 | 0,0 | 1 件 | `IN_PRODUCTION(build, stage1, 40%)` |

### 3.2 `LotState`

```text
sealed LotState
  COMPLETE
  IDLE
  IN_PRODUCTION(ProductionLotState)

ProductionLotState
  planId
  stageId
  stageProgressDays
  cycleProgressDays
  optional crop/outputType
  extraFacts: Map<LotFactKey, Long>
```

- `COMPLETE`：最终商品状态；普通商品永远是它；
- `IDLE`：土地/长期生产资料当前不生产；
- `IN_PRODUCTION`：该 lot 自己的生产进度；由 production 解释、由 map/actor/economy
  的所属写口落盘；
- `extraFacts` 只放生产域定义并解释的有限状态（例如已付 start input、质量），
  不允许放“第二个 owner”或价格。

### 3.3 `MarketListing`

```text
MarketListing
  sourceLotId
  itemType
  quantityMilli
  estimatedValueMilli?   // derived，不是第二份库存
```

- 一张 listing 只有一个源 lot；
- fungible 商品可把同 `(owner, commodity)` 的余额聚合成一条 listing；
- 有状态的 lot 每个 component/row 一条 listing；
- `estimatedValue` 由价格表/地价纯派生，缺价时为 empty，不生成可成交订单
  （除非未来明确引入 barter）；
- listing 只在市场轮内存在，不落盘。

### 3.4 `MarketOrder`

```text
MarketOrder
  orderId
  side
  actor
  location: HexCoord
  item: MarketItemRef
  sourceLotId: Optional<InventoryLotId>
  quantityMilli
  limitUnitValueMilli: OptionalLong
  payWith
  budget: Optional<Budget>
  priority
```

- `location`：BUY = 交割格，SELL = 发货格；土地订单两边都等于该土地 hex；
- SELL：`sourceLotId` 必填，且必须属于 `actor`、在存量中，且其 location 与订单 location 一致；
- BUY：`sourceLotId` 可空；空时按 `LOT_TYPE` 在 `location` 对应市场/hex 选卖方 lot；
- 订单绝不携带 stage/progress/state 快照；状态从 source lot 现读；
- 这样“可卖的有阶段生产属性的东西”不需要新增订单类型；
- `MarketIntentKind`、`LandOrder` 以及商品/土地两套订单形状退出目标模型。

### 3.5 统一库存转移接口（不是统一写口）

用户补充口径（2026-10-22）：

> 写和修改肯定不能统一啊，不过可以做一个整合起来的接口，就是选择在库存语义上转移，
> 那就根据产品类型自动包装之类的。

目标实现为 **`InventoryTransferPort`**：一个统一的选择/规划接口，背后按产品类型自动选择
backend adapter。它不是万能写口，也不直接改状态。

```text
InventoryTransferRequest
  transferId
  sourceLotId
  quantityMilli
  targetOwner
  targetLocation?
  reason            MARKET_TRADE | DEBT | GIFT | ...
```

```text
InventoryTransferPort
  InventoryTransferPlan plan(InventoryTransferRequest request)
```

```text
InventoryTransferPlan
  transferId
  sourceLotId
  movedQuantityMilli
  List<InventoryLotMutation> mutations
  Map<String, ChangeSet> moduleChanges
  Set<String> reads
  Set<String> writes
```

```text
InventoryTransferReceipt
  transferId
  sourceLotId
  movedQuantityMilli
  targetLotIds          // 部分转移时由 adapter 创建的 lot id
  backendKind           // ACTOR_GOODS / MAP_LAND / ECONOMY_ASSET / STATEFUL_GOODS
  statePreserved
```

`InventoryLotMutation` 是适配层产出的域级动作描述，例如：

```text
CommodityBalanceMutation    // actor: balances[commodity] 减/加
LandComponentMutation       // map: component split / owner attr 更新
AssetStakeMutation          // economy: asset share/lot 转移
StatefulGoodsLotMutation    // actor/economy: lot split / owner 更新
```

适配器注册：

```text
InventoryTransferAdapter
  ItemKind kind()
  InventoryTransferPlan plan(TransferContext ctx, InventoryTransferRequest request)
```

- `COMMODITY` → `CommodityTransferAdapter`
- `LAND` → `LandLotTransferAdapter`
- `ASSET` → `AssetLotTransferAdapter`
- 有状态的商品 → `StatefulGoodsTransferAdapter`

每个 adapter 只做三件事：

1. 验证源 lot、owner、数量、冻结、状态；
2. 计算拆分/合并和 target lot id；
3. 产出所属模块的 mutation + ChangeSet 草案。

执行约束：

- 先 `plan`，检查全部 mutation 的数量守恒、来源/目标 owner、state 不变或按规则复制；
- 再由 app 用现有 `WorldChangeSet`/revision 机制原子提交；
- 任何 adapter 缺失、状态不匹配、数量不足、owner 不符 ⇒ 具名拒绝，零变更；
- `plan` 是纯计算/工作副本计算，不得直接改 Map/Actor/Economy 的持久对象；
- 市场成交、债务抵地、赠予、生产产出分配都可以调用同一个 `transfer` 语义；
- 生产投入的 `consume` 和生产输出的 `issue` 可以后续复用同一 adapter registry，
  但它们是不同操作，不伪装成 `transfer`。

这样“写和修改不统一”仍然成立：

```text
统一的是：请求形状、适配器选择、拆分/状态复制规则、收据和原子提交边界
不统一的是：actor 余额写口、map component 写口、economy 资产写口
```

## 4. Map 层：土地归属与状态

### 4.1 权威

- `LandComponent.attributes[OWNER]` 是土地所有权唯一权威；
- `LandComponent.id` 是 LAND lot id；
- `quantityMilliMu` 是面积；
- 生产状态放在同一 component 的属性中，不另建第二份土地生产状态账。

新增/明确的属性键：

```text
OWNER              household id（v1；现有键）
CROP               生产计划/产业 id
STAGE              当前阶段 id
PROGRESS           当前阶段已过天数（当前键）
CYCLE_PROGRESS     整个周期已过天数（新增）
START_INPUT_PAID   阶段开始投入是否已付（新增）
```

`PROGRESS` 与 `CYCLE_PROGRESS` 不能混用；有 stage 时必须二者可互相校验。

### 4.2 component 级操作

现有 `LandComponentOperations.transferQuantity` 已经能在部分转移时复制属性
（`simos-map/src/main/java/io/mosire/simos/map/ops/LandComponentOperations.java:160-240`），
但它按 owner 扫 component，不是按 lot id 指定。目标增加：

- `transferFromComponent(hex, type, sourceComponentId, toOwner, quantity)`：
  只从指定 lot 转移；部分转移复制所有非 owner 属性；整块只改 owner；
- `setComponentState(hex, type, componentId, state attributes)`；
- `splitComponent(hex, type, componentId, quantity)` 使用确定性 ordinal；
- 同 owner 多状态 component 必须保持分离；不得再调用只按 owner 聚合的写回
  `setArableOwners` 来交易有状态土地。

现有拒绝点必须消失：

- `LandComponentLedger.from` 当前把同 owner 多 component 聚合为 `(owner, hex, quantity)`，
  只读 owner/quantity（`LandComponentLedger.java:74-104`）；
- `setArableOwners` 对同 owner 不同非 owner 属性具名拒绝
  `land-owner-aggregation-unsupported`（`LandComponentOperations.java:365-390`）；
- `LandHolding` 当前无 lot id / stage / progress（`LandHolding.java:20-33`）。

目标保留 `LandHolding` 只作纯财富/聚合读口；任何带状态的转移必须经过 component id。

### 4.3 土地买卖的状态语义

- 卖的土地 lot 带 crop/stage/progress；
- 成交只改 `OWNER`，其余属性逐字保留；
- 部分成交拆 component，新 component 复制全部非 owner 属性；
- 买方在 S7 成交后 S1/S3 的下一生产 tick 可继续；卖方不得再使用已卖出 lot；
- 若成交时该 lot 仍挂在旧 production batch 上，S7 只发 `LotDetachedFromBatch` 事件；
  旧 batch 不再推进该 lot；新 owner 下一 S3 以同一 stage/progress 创建/接续批次。
  这样不把“土地卖了但卖家还继续种”变成隐含行为。

## 5. 生产层：长期生产资料与批次

### 5.1 `ProductionBatch`

```text
ProductionBatch
  batchId
  operator
  planId
  location
  carrierLotId?          // 有载体时状态只在 carrierLot
  ownState?              // 仅无载体生产使用
  quantityMilli
  laborAllocated
  inputAllocations
```

- `carrierLotId` 与 `ownState` 恰好一个存在；
- 有 `carrierLotId`：`stage/progress` 从 `InventoryLotView` 读；
- 无 `carrierLotId`：状态在 production 的 batch 中；
- 一个 batch 不复制 carrier 的 stage/progress，避免两个真相；
- 同一计划、同一 owner、不同 stage/progress 的土地拆成多个 batch；
- 长期生产资料通过 `LotReservation` 占用，不把占用当作所有权转移。

```text
LotReservation
  reservationId
  lotId
  quantityMilli
  userBatchId
  role              MEANS | INPUT | OUTPUT_CARRIER
  fromTick / toTick
```

`LotReservation` 是“谁在本段生产里占用了哪个 lot 的多少量”的 claim，不是所有权、
不是生产进度，也不复制 lot state。它由 economy 的占用/冻结账持有，production 只读并
在 batch 中保存 `reservationId`；同一 lot 的未被覆盖数量可被其他 batch 占用，但
同一数量不得双占。

### 5.2 长期生产资料进度

生产选择阶段读取：

```text
lot.state = IN_PRODUCTION(plan, stage, progress)
```

- 若计划相同且阶段可继续 ⇒ 复用已有进度；
- 若计划不同 ⇒ 不静默重置；生产必须先结束/放弃旧批次，或按策略显式接管；
- `IDLE` ⇒ 从 stage 0 开始；
- 工具/机器等长期资料如果有自己的生产/建造进度，同样由 `lot.state` 提供；
- 家户库存中的普通 `COMMODITY` 行永远 `COMPLETE`，不把进度塞进
  `CommodityId` 或 `Map<CommodityId,Long>`。

### 5.3 阶段定义

阶段定义仍归 `ProductionData` 的 revision 状态，不引入运行时 config 作为权威。
每阶段至少：

```text
stageId
durationDays
startInputPerUnit
tickInputPerUnit
endAction         NEXT_STAGE | HARVEST
outputPerUnit     可空；NEXT_STAGE 也允许产出中间商品
```

- 输入/输出使用通用 `ItemTypeRef`（商品/土地/资产），不再只允许 `AssetKind`；
- `NEXT_STAGE` 的下一阶段 = 列表下一项；
- 最终阶段必须是 `HARVEST` 或显式终止；
- 阶段结束产出与最终产出都走同一“产出 lot → owner 库存”路径；
- `cycleDays` 可由阶段时长求和派生；不再保留第二份总周期真值。

### 5.4 劳动/投入不足

用户已定：按可用量缩规模，未覆盖土地移出生产并归零。

目标算法：

```text
coverage = min(
  laborAvailable / laborPerUnit,
  startInputAvailable / startInputPerUnit,
  tickInputAvailable / tickInputPerUnit,
  reservedMeansAvailable / meansPerUnit,
  requestedQuantity
)
covered = floorToLotPrecision(coverage)
uncovered = requestedQuantity - covered
```

- covered 部分保留 carrier lot id，state 推进；
- uncovered 部分拆成新 lot，state 重置为 `IDLE`，owner/location 不变；
- 若 covered=0，batch 不推进、不扣投入、不产生任何半边写入；
- 归零的 lot 仍需在地图上保留 owner，不能被删除成无主土地。

## 6. 失败、原子性与日志

- 所有 lot 状态、商品扣减、货币/债务、Map 土地属性、production batch 更新必须在同一
  revision 内提交；任一失败 ⇒ 全部回滚；
- 市场成交前验证 `sourceLotId` 存在、owner、数量、冻结状态；
- 部分成交拆 lot 后必须满足数量守恒；
- S7 不得把买入的普通商品回写本 tick 已完成的 S4；
- 关键事件：
  - INFO：`INVENTORY_LOT_LISTED`、`MARKET_LOT_TRADE`、`LOT_STATE_TRANSITION`、
    `PRODUCTION_BATCH_SCALED`；
  - DEBUG：候选评分、lot 选择、拆分数量；
  - ERROR：lot id 重复、owner 缺失、状态与 plan 不匹配、数量不守恒、跨域写冲突；
- 所有排序使用稳定键，禁止 Map 迭代序、时间戳、随机 UUID。

## 7. 对现有代码的落点

### 7.1 现状事实

- 商品和土地确实在同一市场轮、同一 matcher 入口，但土地是独立变体：
  `MarketIntent.java:18-30,41-91`、`MarketMatcher.java:54-65`；
- `LandOrder` 只有 `(side, actor, hex, quantityMilliMu, limitPricePerMu, payWith)`，
  没有 lot id/crop/stage/progress：`MarketLandOrder.java:10-48`；
- 商品余额是 `Map<CommodityId, Long>`：`HouseholdInventory.java:102-107`；
- 土地 component 有开放属性表和 `OWNER/PROGRESS/CROP/FERTILITY`：
  `LandComponent.java`、`LandAttributeKey.java:15-25`；
- economy 聚合土地时只保留 `(owner, hex, quantity)`：
  `LandComponentLedger.java:74-104`；
- 同 owner 多状态 component 被聚合写回拒绝：
  `LandComponentOperations.java:365-390`；
- `LandHolding` 是标量 `(owner, hex, quantity)`：`LandHolding.java:20-33`。

### 7.2 目标改动面

| 层 | 目标 |
|---|---|
| economy-api | 新增 `InventoryLotId`、`InventoryLot`、`LotState`、`MarketListing`、统一 `MarketOrder`/`MarketItemRef`、`LotOrigin` |
| map | component 级 lot 转移/拆分/状态属性；保留 owner 唯一权威；禁止 stateful 走 owner 聚合写回 |
| actor | 商品余额保持 fungible；需要状态的非土地长期资料新增持久 lot 表；不把 state 塞进 commodity key |
| economy-api | 统一 `InventoryTransferPort`/`InventoryTransferPlan`/`InventoryLotMutation` 契约，adapter 只按 itemKind 选择 |
| economy | `InventoryLotView` 组装、listing 生成、统一 matcher、lot-aware 冻结/成交、债务估值 |
| production | `ProductionBatch`/carrierLotId、阶段机、超额/不足缩放拆 lot、从 view 读长期资料进度、产出 lot |
| app | 只组装 view、编排 ChangeSet、解析 owner/actor；不实现生产/价格公式 |

### 7.3 明确不做的捷径

- 不把 `stage/progress` 编码进 `CommodityId`；
- 不把土地追加到普通 `HouseholdInventory.balances`；
- 不让 `LandHolding` 继续承担状态化交易；
- 不为土地再建一套 `MarketIntentKind.LAND` 订单类型；
- 不把 `InventoryLotView` 落成第二份可写状态；
- 不在订单里复制 stage/progress，否则成交后会出现“订单状态”和“库存状态”两套真相。

### 7.4 非目标

- 不把 map/actor/economy 的持久状态物理合并成一个模块或一张表；
- 不在本设计里引入新的运行时生产配置文件；阶段定义仍是 revision 状态；
- 不改现有商品/土地价格公式和运费公式（只换成统一订单后的策略分派）；
- 不支持以物易物、拍卖、期货、预付款或非货币结算；
- 不在 v1 为普通商品逐件生成长生命周期 id；普通商品仍是 fungible stack；
- 不做旧档迁移；Alpha 直接重建新世界。

## 8. 实施顺序（仅设计，不是派单）

### U0 设计冻结

本文 + 用户确认；旧 stage 设计文档标注取代关系。没有代码。

### U1 统一库存视图（无行为变化）

- 新增 economy-api 的 lot/view 契约；
- 从 actor 商品余额和 map LandComponent 组装唯一 view；
- 断言每一条商品余额/每一个 land component 恰好出现一次；
- 旧 `LandHolding` 仍保留为聚合读口。

### U1b 统一库存转移接口

- 新增 `InventoryTransferPort`、请求/计划/收据和 `ItemKind → adapter` 注册表；
- 先实现 `COMMODITY`、`LAND` 两个 adapter；`ASSET`/stateful goods 随 U5 接入；
- 计划只产出域级 mutation/ChangeSet，不直接写持久状态；
- 验证商品从 `balances` 转、土地从 `LandComponent` 拆/转，得到同一种收据。

### U2 统一市场订单

- 新增 `MarketOrder`/`MarketItemRef`/`MarketListing`；
- 商品和土地都转成同一订单列表；
- matcher 内部按 item policy 分派，但公共入口/订单类型只有一个；
- 保持现有商品、土地、冻结、债务、运费数值行为。

### U3 土地 component 级状态

- 新增 Map 属性键和 component 级 transfer/split/state 操作；
- 停止 stateful land 的 owner 聚合写回；
- 验证 10 亩阶段 2 + 1 亩归零可并存、可部分出售、状态随 lot 转移。

### U4 grain_farm 阶段批次

- `ProductionBatch` + carrier lot；
- 阶段定义、start/tick input、NEXT_STAGE/HARVEST、阶段产出；
- 劳动/投入不足缩规模并重置未覆盖 lot；
- 成本/产出/工资仍走现有 S4–S6 出口。

### U5 非土地长期生产资料

- 给持久资产 lot 增加 state；
- 生产从 inventory view 读取进度并占用；
- 市场可按 lot 交易带状态的工具/机器/在建设施。

### U6 读口/UI/日志收口

- 一张库存表 API：商品、土地、资产、状态、估值、来源；
- 读口与 GUI 使用同一 lot view；
- 补正/负/变异测试和跨域守恒。

## 9. 验收锚点

1. **一表**：某户有 1000 毫粮、10 亩阶段 2 土地、1 亩归零土地时，`InventoryLotView`
   分别给出商品聚合行、10 亩 `IN_PRODUCTION` 行、1 亩 `IDLE` 行，数量/owner/state 全可读。
2. **一订单**：同一市场轮的订单表同时包含 `COMMODITY` 与 `LAND` 的 `MarketOrder`；
   不存在第二个订单 record 或第二个 matcher 入口。
3. **市场只有估值+来源**：sell order 必须带 `sourceLotId`；订单不带 stage/progress；
   成交时从 source lot 读取并转移。
4. **状态随土地**：10 亩阶段 2 卖 3 亩，买卖双方各有阶段 2 的 lot，progress/crop 不重置；
   数量合计守恒。
5. **多批次**：10 亩阶段 2、1 亩阶段 0 可在同一户同一 hex 并存；生产只推进被
   labor/input 覆盖的 lot。
6. **缩规模**：劳动不足时 covered 部分推进，uncovered 部分拆出并重置为 IDLE；
   不产生负数、不丢 owner、不做半边 revision。
7. **长期资料**：工具/机器/在建设施的 `IN_PRODUCTION` 状态可从库存读；
   同一 lot 不能同时被两个 batch 占用。
8. **普通商品**：普通商品永远是 `COMPLETE`；不带生产状态的商品可继续 fungible 合并/冻结/交易。
9. **债务/土地**：债务抵地保留状态；0 价土地不冲本金，市场 0 价成交仍合法。
10. **负例**：重复 lot id、缺 owner、land 无 location、订单自带 state 快照、
    同 lot 双 batch 占用、把 state 编进 commodity id —— 全部具名拒绝。

## 10. 已确认的结构性口径

用户 2026-10-22 复核设计后确认“设计很不错”，并进一步限定：

> 写和修改肯定不能统一啊，不过可以做一个整合起来的接口，就是选择在库存语义上转移，
> 那就根据产品类型自动包装之类的。

因此冻结为：

```text
一张逻辑 InventoryLotView 表；
持久化仍由 map / actor / economy 各域权威支撑；
统一的是 InventoryTransferPort 的请求/适配器/守恒/收据；
不是把土地、商品、资产的数量和状态搬进同一个物理持久表。
```

“写和修改不能统一”也意味着：`InventoryTransferPort` 不直接写持久状态，只生成
`InventoryTransferPlan`，由 app 以领域 ChangeSet 在同一 revision 原子提交。

## 10.1 阶段生产验收：农业与运力（2026-10-22 追加）

用户验收口径：

- 先创建一个完整的分阶段农业生产方式，跑 180 tick；
- 因为当前不依赖 MCP GM 工具，测试侧直接改 JSON，调整生产方式的部分数值，
  甚至新增一个阶段，观察是否生效；
- 运力生产也按同一阶段系统验收：给运力生产分成 **1 tick 大量物资准备**
  + **4 tick 低耗/无大耗持续** 两个阶段；
- 重点是证明阶段系统能表达“大宗运输准备环节”，不要求先做完整 MCP 工具。

工程映射：

```text
农业（grain_farm）
  阶段 A：开始投入大（种子/物资），持续若干 tick
  阶段 B：持续低投入，结束 HARVEST，产出 grain
  跑 180 tick；测试侧改 JSON → 替换该产业定义 → 观察后续阶段/成本/产出生效

运力（caravan_transport）
  阶段 A：1 tick，startInput/tickInput 消耗大量物资（准备）
  阶段 B：4 tick，低耗/无大耗持续，结束 HARVEST
  最终 outputPerUnit = CaravanTransportTemplate.SERVICE_OUTPUT
  HARVEST 仍走 TransportCapacitySink，不进入普通 HouseholdInventory/商品价表
```

关键约束：

- `SERVICE_OUTPUT` 只允许在最终 HARVEST 产出，不允许作为 NEXT_STAGE 的中间库存商品；
- 阶段中间产出只能是普通商品 lot（如果以后需要）；
- 运力二阶段只是同一 `ProductionStageDefinition`/`ProductionBatch` 机制的一个样例，
  不新增第二套运力专用阶段类型；
- JSON 替换必须走 `Command → ProductionChangeSet → Revision`，不能直接改运行中的
  `ProductionData`；没有 MCP 工具时，测试 harness 可直接提交生产命令；
- 测试侧需要覆盖：180 tick 前正常、JSON 改数值后下一 tick 读到新值、新增阶段后
  batch 进入新阶段、运力 1+4 阶段在 HARVEST 产生 capacity batch。

## 11. 用户原话附录（2026-10-22，逐字）

```text
相关模型最好整合成同一个订单类型，因为后面能卖的有阶段生产属性的东西还有很多，在市场上，一个商品只有预估价值和来源，在库存中，所有东西包括土地等被列在一个表中，在map层，土地的分配必须有归属，在生产层，允许读取库存中所需要用的长期生产资料的生产进度，最好有更通用化的方案——此外我压缩了你的上下文，记得适当恢复工作状态
```

```text
写和修改肯定不能统一啊，不过可以做一个整合起来的接口，就是选择在库存语义上转移，那就根据产品类型自动包装之类的，这个能实现吗？
```

```text
设计很不错，再检查一下有没有要做的裁定，没有的话就开始一个一个派单，派单完简单跑一下变异测试等确保项目可行性（如360tick中途修改生产花费之类的）
```

```text
验收标准可以改成，先创建一个完善的分阶段农业生产方式，跑180tick，然后由于现在没有MCP GM工具，所以你手动改改json，把生产方式中的部分数值调整一下，甚至可以再新增一个阶段，看看是否生效即可，不需要依赖MCP工具；我需要额外确认一下，运力产出也是如此吗？测试项目甚至可以包括，你给运力生产分成1tick和4tick持续时间的两个阶段，第一阶段消耗大量物资，第二阶段不消耗大量物资，来模拟一下大宗运输的准备环节，懂我的实测思路吗？简单记录，实现它，让我验收
```

## 12. 修订记录

- 2026-10-22：新建本文。统一以下口径：`InventoryLot` 逻辑单表、`MarketListing`
  只带估值+源 lot、统一 `MarketOrder`、土地 component 级状态随 lot 转移、
  长期生产资料从库存 view 读进度、生产批次不复制 carrier 状态。
- 2026-10-22 追加：用户明确“写和修改不能统一，但可以做整合接口，在库存语义上按产品类型
  自动包装”。新增 `InventoryTransferPort` / `InventoryTransferPlan` /
  `InventoryLotMutation`：统一请求、适配器选择、守恒检查和原子提交，不统一持久写口。
- 2026-10-22 复核：用户确认“设计很不错”，把原“待确认的物理单表”收口为逻辑单表 +
  域写口；准备按 U1a/U1b/U2/U3/U4/U5 逐责任区派单，最后统一测试/变异/360。
- 2026-10-22 验收口径追加：改用“分阶段农业 180 tick + 测试侧改 JSON/加阶段”和
  “运力 1 tick 大耗准备 + 4 tick 低耗持续 → SERVICE_OUTPUT”作为可验收场景；
  不依赖 MCP GM 工具。详见 §10.1。
- 取代关系：`.superpowers/sdd/2026-10-22-d032-production-stages-v1/design-note.md`
  中“市场层先保留 COMMODITY 与 LAND_LOT 两种订单变体”的建议，由本文统一订单模型取代；
  该文档的 `ProductionLot`、Map 状态、缩规模、综合效益等调查与裁定继续有效。
