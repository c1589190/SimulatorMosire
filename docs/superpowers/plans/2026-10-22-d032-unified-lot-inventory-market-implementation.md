# D-032 统一 InventoryLot / 转移接口 / 市场与阶段生产实施计划（2026-10-22）

> 状态：控制方实施计划；设计已由用户确认“设计很不错”。未改 Java/测试，未提交。
> 设计入口：`docs/superpowers/specs/2026-10-22-d032-unified-lot-inventory-market-design.md`。
> 父目标运行序列：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` 的 S0–S8。
> 纪律：一个责任区一个写代码 owner；写者只写到编译过，不写/不跑测试；测试最后统一由独立 test owner 做；
> 控制方只做设计、派单、验收、证据和最终门禁/360，不自己写业务代码。

## 1. 裁定复核结论

**没有新的用户玩法裁定需要提问。** 用户 2026-10-22 已确认：

- 统一的是库存语义/转移接口，不是持久写口；
- 按产品类型自动包装到 actor/map/economy 的真实写口；
- 逻辑上所有东西是一张 `InventoryLotView` 表；
- 土地仍由 Map 保归属；
- 生产从库存 lot 读取长期生产资料进度；
- 市场统一订单类型，商品在市场上只有“预估价值 + 来源”。

剩余为工程默认，由控制方冻结：

| # | 工程默认 | 理由 |
|---|---|---|
| E1 | v1 `InventoryLot.owner` 用 `HouseholdId` | 当前经济主体统一为家户；以后扩 ActorRef 再改契约 |
| E2 | 普通商品 view 行 = `(owner, commodity, owner hex)` 聚合，`state=COMPLETE` | 保持 fungible 余额，不把 state 编进 commodity key |
| E3 | LAND 行只取 `LandTypeId.ARABLE`（当前只有它有 owner 语义） | URBAN 尚未接所有权；不在本批伪造 owner |
| E4 | 土地 owner 缺失 ⇒ S1 view fail-closed | 用户要求“土地分配必须有归属” |
| E5 | `InventoryTransferPort` 只 `plan`，app 统一提交 | 不制造万能写口；保留 Command→ChangeSet→Revision |
| E6 | `MarketListing.estimatedValueMilli` 是估值，不是成交价 | 成交价仍走现有 bid/ask/地价 + limit |
| E7 | 土地部分成交复制除 owner 外的全部属性；整块只改 owner | 用户已定“土地交易保留生产状态” |
| E8 | 挂生产的土地卖出时 detach 旧 batch；新 owner 下一 S3 从同一 state 接续 | 不静默让卖家继续种买家土地；state 不丢 |
| E9 | v1 先做 COMMODITY + LAND adapter；ASSET/stateful goods 后续 U5 | 风险从最小闭环开始 |
| E10 | `NEXT_STAGE` 允许可选中间产物；最终 HARVEST 仍产出最终品 | 用户已确认设计；阶段产出走同一库存 lot 路径 |

## 2. 派单顺序

依赖顺序（设计文档的 U 编号保留；U3 在 U2 之前派）：

```text
U1a  InventoryLot 契约 + InventoryLotViewBuilder          ✅ 已编译验收
U3   Map 土地 component 级状态/转移/拆分                ✅ 已编译/测试
U1b  InventoryTransferPort + COMMODITY/LAND adapters     ✅ 已编译/测试
U3b  ArableSupplySnapshots 组件级 state-preserving 写回   ← 下一单
U2a  统一 MarketOrder/MarketListing 契约/订单簿          ✅ 已编译/测试
U2b  统一市场接线（商品 matcher + lot-aware 土地）
U4   grain_farm 阶段批次、start/tick input、缩规模、阶段产出
U5   （可选，后置）非土地长期生产资料/stateful goods
T    独立 test owner：正负例 + 变异 + 360tick 中途改生产花费
```

每个写 owner 的退出条件：

- 只跑 `compile`，rc=0；
- 不写、不改、不跑测试；
- 交 `impl-ledger.md` + 改动文件清单 + 未完成/未验证；
- 控制方复核代码事实后才派下一单。

编译统一命令：

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -pl simos-app -am -DskipTests compile
```

## 3. U1a：InventoryLot 契约 + 统一库存视图

### 3.1 目标运行位置

- 嵌入父序列 **S1 经济载入**；
- 产物只读、不落盘、不改任何既有 tick 行为；
- 输入：actor 商品余额、economy 家户位置、map ARABLE component；
- 输出：稳定排序的 `InventoryLotView`，供 U1b/U2/U3/U4 只读消费。

### 3.2 拥有文件（唯一写面）

新增：

```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/inventory/InventoryLotId.java
simos-economy-api/src/main/java/io/mosire/simos/economy/api/inventory/InventoryLotKind.java
simos-economy-api/src/main/java/io/mosire/simos/economy/api/inventory/LotState.java
simos-economy-api/src/main/java/io/mosire/simos/economy/api/inventory/LotOrigin.java
simos-economy-api/src/main/java/io/mosire/simos/economy/api/inventory/LotOriginKind.java
simos-economy-api/src/main/java/io/mosire/simos/economy/api/inventory/InventoryLot.java
simos-economy-api/src/main/java/io/mosire/simos/economy/api/inventory/InventoryLotView.java
simos-app/src/main/java/io/mosire/simos/app/inventory/InventoryLotViewBuilder.java
```

不得改：

- `MarketIntent`/`BuyOrder`/`SellOrder`/`LandOrder`；
- `HouseholdInventory`/`ActorData`/`EconomyData`/`Map` 状态；
- 任何 tick/settlement 调用点；
- 任何测试。

### 3.3 契约形状（实现可细化，不得减字段）

```text
InventoryLotId(String value)  // opaque，非空白；factory 是唯一拼写点
InventoryLotKind { COMMODITY, LAND, ASSET }
LotOriginKind { CURRENT_STOCK, PRODUCED, SPLIT, TRANSFERRED, UNSPECIFIED }

LotState（sealed）
  Complete
  Idle
  InProduction(
    planId: String,
    stageId: String,
    stageProgressDays: long,
    cycleProgressDays: long,
    facts: Map<String, Long>)

LotOrigin(
  kind,
  sourceLotId: Optional<InventoryLotId>,
  runId: Optional<String>,
  tick: long)

InventoryLot(
  lotId,
  itemKind,
  itemTypeId,
  owner: HouseholdId,
  location: Optional<HexCoord>,
  quantityMilli,
  state: LotState,
  origin: LotOrigin,
  fungible: boolean)

InventoryLotView(
  List<InventoryLot> lots)
  require(InventoryLotId) -> InventoryLot
  byOwner(HouseholdId) -> List<InventoryLot>
  byKind(InventoryLotKind) -> List<InventoryLot>
```

### 3.4 构造/排序不变量

- `quantityMilli > 0`；
- `InventoryLotId` 非空白、非 null；
- `itemTypeId` 非空白；
- `LAND` 必须 `location` present、`fungible=false`；
- `COMMODITY` 必须 `state=Complete`、`fungible=true`；
- `InProduction` 的 `stageProgressDays >= 0`、`cycleProgressDays >= 0`、`facts` 不可变；
- `InventoryLotView` 构造期按规范序排序并检查重复 lotId：
  `kind.ordinal → itemTypeId → location q/r（empty 最后）→ owner.value() → lotId.value()`；
- view 的 `lots()` 是保序不可变列表；`require` 缺失具名抛；
- 禁止 `Map.copyOf`/`HashMap` 迭代序进入排序。

### 3.5 builder 行为

签名由实现决定，但必须能拿到：

```text
ActorData.accounts()
EconomyData.classes()  // HouseholdEconomy.view().hex()
GameMap.hexes()        // HexCell.land()
```

商品行：

- 遍历 `ActorData.accounts()`；
- 每个 `HouseholdInventory.balances()` 中 `quantity > 0` 的 commodity 生成一条 COMMODITY lot；
- location = 同 `HouseholdId` 的 `HouseholdEconomy.view().hex()`；
- 无 location 且余额 > 0 ⇒ 具名 `inventory-lot-location-missing`；
- state = Complete；origin = CURRENT_STOCK/UNSPECIFIED；fungible = true；
- lotId 由 `InventoryLotId.forCommodity(owner, location, commodity)` 生成；
- 同一 `(owner, commodity)` 只能出现一条商品 lot（当前 account key 已保证一户一本账）。

土地行：

- 遍历 `GameMap.hexes()` 的 `HexCell.land()`；
- 只取 `LandTypeId.ARABLE` 的 component；
- owner 从 `LandAttributeKey.OWNER` 读取；缺失/空白 ⇒ 具名 `inventory-lot-owner-missing`；
- owner 必须能 `HouseholdId.parse`，失败保留原异常/具名；
- state = Idle（U3 再接 stage/progress 属性；U1a 不解析阶段状态）；
- origin = CURRENT_STOCK/UNSPECIFIED；fungible = false；
- lotId = `InventoryLotId.forLand(component.id())`；
- itemTypeId = `LandTypeId.ARABLE.toString()`；
- quantity = component.quantityMilliMu()。

builder 不得写任何状态、不得缓存到静态字段、不得读时钟/随机数。

### 3.6 U1a 验收锚点（测试由后续 test owner 写）

正例：

1. h1 有 grain 1000、map 0,0 有 10,000 毫亩 arable（owner=h1）⇒ view 恰有 1 条 COMMODITY + 1 条 LAND；
2. 同 owner 两个不同状态的 arable component ⇒ view 两行，不聚合丢失；
3. 两次 build 输出顺序/字段逐值相同。

负例：

1. arable component 无 OWNER ⇒ `inventory-lot-owner-missing`；
2. OWNER 空白/不可解析 ⇒ 具名失败；
3. 同一 lotId 重复 ⇒ view 构造期拒；
4. 0/负商品余额不生成行；负数量构造期拒；
5. LAND 无 location ⇒ 构造期拒。

### 3.7 U1a 编译与交账

```bash
tools/mvn-lock.sh -pl simos-app -am -DskipTests compile
```

交账必须写：

- 改动文件；
- 实际编译 rc/关键日志；
- 哪些契约字段实现了、哪些没实现；
- 未做：tick 接线、测试、市场、土地状态、迁移；
- 有没有偏离本任务书。

## 4. U1b：InventoryTransferPort + 最小 adapter（U3 之后派）

### 4.1 目标运行位置

- 嵌在父序列 S7 市场成交/债务抵地、以及未来的赠予/分配之前；
- 输入当前 `ActorData`、`GameMap`、`EconomyData` 和 U1a 的 `InventoryLotView`；
- 输出 `InventoryTransferPlan`：域级 mutation + 对应 `ChangeSet` + 收据；
- 只做 `plan`，不直接写持久对象；app 后续把 `moduleChanges` 装进 `WorldChangeSet` 原子提交。

### 4.2 拥有文件

economy-api 新增：

```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/inventory/InventoryTransferId.java
simos-economy-api/src/main/java/io/mosire/simos/economy/api/inventory/InventoryTransferReason.java
simos-economy-api/src/main/java/io/mosire/simos/economy/api/inventory/InventoryTransferRequest.java
simos-economy-api/src/main/java/io/mosire/simos/economy/api/inventory/InventoryTransferPort.java
simos-economy-api/src/main/java/io/mosire/simos/economy/api/inventory/InventoryTransferPlan.java
simos-economy-api/src/main/java/io/mosire/simos/economy/api/inventory/InventoryTransferReceipt.java
simos-economy-api/src/main/java/io/mosire/simos/economy/api/inventory/InventoryBackendKind.java
simos-economy-api/src/main/java/io/mosire/simos/economy/api/inventory/InventoryLotMutation.java
```

app 新增：

```text
simos-app/src/main/java/io/mosire/simos/app/inventory/InventoryTransferContext.java
simos-app/src/main/java/io/mosire/simos/app/inventory/InventoryTransferAdapter.java
simos-app/src/main/java/io/mosire/simos/app/inventory/InventoryTransferAdapterRegistry.java
simos-app/src/main/java/io/mosire/simos/app/inventory/InventoryTransferPortImpl.java
simos-app/src/main/java/io/mosire/simos/app/inventory/CommodityTransferAdapter.java
simos-app/src/main/java/io/mosire/simos/app/inventory/LandLotTransferAdapter.java
```

允许修 `InventoryLotId` 增加 `landComponentId()` 逆方法（只允许该既有文件的小改）。

不得改测试、POM、tick 调用点、市场撮合、ActorData/GameMap/EconomyData 状态形状。

### 4.3 契约形状

```text
InventoryTransferId(String value)       // 非空白；唯一拼写点在自身

InventoryTransferReason
  MARKET_TRADE | DEBT | GIFT | OTHER

InventoryTransferRequest(
  InventoryTransferId transferId,
  InventoryLotId sourceLotId,
  long quantityMilli,                  // > 0
  HouseholdId targetOwner,
  Optional<HexCoord> targetLocation,
  InventoryTransferReason reason)

InventoryTransferPort
  InventoryTransferPlan plan(InventoryTransferRequest request)

InventoryTransferReceipt(
  InventoryTransferId transferId,
  InventoryLotId sourceLotId,
  long movedQuantityMilli,
  List<InventoryLotId> targetLotIds,
  InventoryBackendKind backendKind,    // ACTOR_GOODS / MAP_LAND
  boolean statePreserved)

InventoryTransferPlan(
  InventoryTransferId transferId,
  InventoryLotId sourceLotId,
  long movedQuantityMilli,
  List<InventoryLotMutation> mutations,
  Map<String, ChangeSet> moduleChanges,
  Set<String> reads,
  Set<String> writes,
  InventoryTransferReceipt receipt)

InventoryLotMutation（sealed）
  CommodityBalanceMutation(
    HouseholdId sourceOwner, HouseholdId targetOwner,
    CommodityId commodity, long quantityMilli)
  LandComponentMutation(
    HexCoord hex, LandTypeId landType,
    LandComponentId sourceComponentId, HouseholdId targetOwner,
    long quantityMilli)
```

约束：

- `moduleChanges` 的键 v1 只允许 `"actor"` / `"map"`；
- 一个 plan 的 `sourceLotId`、moved quantity、mutation、moduleChanges 必须一致；
- `reads/writes` 用现有 canonical address 形制，稳定保序；
- plan 不直接修改传入的 `ActorData`/`GameMap`/`EconomyData`。

### 4.4 CommodityTransferAdapter

- source lot 必须是 `COMMODITY`；
- source `HouseholdInventory.balances[commodity] − frozenBalances[commodity]` 必须 ≥ quantity；
- target owner 与 source owner 不同；target location 缺省 = source lot.location；
- 构造 target `ActorData`（source 减、target 加；保留 0 余额与两张冻结表），再 `ActorChangeSet.between(base, target)`；
- mutation = `CommodityBalanceMutation`；
- `backendKind=ACTOR_GOODS`、`statePreserved=true`；
- reads/writes 命中 source/target 的 `actor:<mapId>:goods.<household>` 地址。

### 4.5 LandLotTransferAdapter

- source lot 必须是 `LAND`；
- 从 `InventoryLotId.landComponentId()` 取 component id，location 取 lot.location，type 取 itemTypeId；
- `LandComponentOperations.componentOf` 校验 component 存在，且 owner 与 lot.owner 一致；
- 调 U3 `transferFromComponent`；targetOwner 为 request.targetOwner；targetLocation 若给出必须等于 land hex；
- 构造新 `GameMap`（只替换该 hex 的 `HexCell.withLand`），再 `MapChangeSet.between(base, target)`；
- mutation = `LandComponentMutation`；
- `backendKind=MAP_LAND`、`statePreserved=true`；
- reads/writes 命中 `map:<mapId>:land.<hex>` canonical 地址；
- targetLotIds 取 transfer 后该 hex 上 targetOwner 名下的 LAND component ids（U2 可细化 receipt，不重复状态）。

### 4.6 拒绝语义

以下全部零 plan、具名拒：

```text
inventory-transfer-lot-missing
inventory-transfer-kind-unsupported
inventory-transfer-source-owner-mismatch
inventory-transfer-target-owner-same
inventory-transfer-quantity-exceeds-available
inventory-transfer-frozen-shortfall
inventory-transfer-location-missing
inventory-transfer-target-location-mismatch
inventory-transfer-empty-module-changes
```

### 4.7 不接范围

- 不接市场/债务/tick；只证明“给 sourceLotId + 目标 owner + 数量，能自动产出一个域级变更集”；
- 不实现 ASSET/stateful goods adapter；
- 不实现 app 的最终 `WorldChangeSet` 提交；U2 接。

## 5. U3：Map 土地 component 级状态（下一单）

### 5.1 目标运行位置

- 嵌在父序列 S7 土地成交/S4 土地缩规模之前的 Map 纯操作层；
- 只提供“按 component id 转移/拆分/改属性”的纯函数；
- 不接生产批次、不接市场、不写 ChangeSet；写回仍由 app 生成 `MapChangeSet`。

### 5.2 拥有文件

只允许改：

```text
simos-map/src/main/java/io/mosire/simos/map/LandAttributeKey.java
simos-map/src/main/java/io/mosire/simos/map/ops/LandComponentOperations.java
```

不得改测试、POM、actor/economy/app。

### 5.3 新增属性键

```text
STAGE              当前阶段 id（STRING）
CYCLE_PROGRESS     整周期已过天数（LONG）
START_INPUT_PAID   阶段开始投入是否已付（BOOLEAN）
```

保留现有 `PROGRESS` 为当前阶段已过天数；保留 `CROP`/`OWNER`/`FERTILITY`。

### 5.4 新增纯操作

```text
transferFromComponent(
  HexLand land, HexCoord hex, LandTypeId type,
  LandComponentId sourceComponentId, String toOwner, long quantityMilliMu) -> HexLand
```

- source component 必须存在；
- 校验 source owner、toOwner 非空白/不同、quantity >0 且 ≤ source.quantity；
- 整块：只把 source 的 OWNER 改成 toOwner，id/quantity/其它属性不变；
- 部分：source 数量减少，新建 component（next ordinal）承载转移量，复制**除 OWNER 外全部属性**，再把 OWNER 设为 toOwner；
- 最后 `normalize`；数量守恒。

```text
splitComponentWithOverrides(
  HexLand land, HexCoord hex, LandTypeId type,
  LandComponentId sourceComponentId, long splitQuantityMilliMu,
  Map<LandAttributeKey, LandAttributeValue> overrides) -> HexLand
```

- 用于“覆盖部分继续、未覆盖部分重置”：source 保留原属性（数量减少），新 component 获取 source 全部属性 + overrides（overrides 可改 STAGE/PROGRESS/CYCLE_PROGRESS 等），id 用 next ordinal；
- 必须 `0 < splitQuantity < source.quantity`；整块重置请走 `setComponentAttributes`；
- overrides 不得包含 OWNER（owner 只能由 transfer 改）；
- 最后 normalize；若 overrides 后与其它同属性 component 相同，允许按既有规则合并。

```text
setComponentAttributes(
  HexLand land, HexCoord hex, LandTypeId type,
  LandComponentId componentId,
  Map<LandAttributeKey, LandAttributeValue> attributes) -> HexLand
```

- 只覆盖给定键，不清除未给出的键；
- 不得包含 OWNER（具名 `land-component-owner-write-through-transfer-only`）；
- 空 map = 原样返回；
- 最后 normalize。

```text
removeComponentAttributes(
  HexLand land, HexCoord hex, LandTypeId type,
  LandComponentId componentId, Set<LandAttributeKey> keys) -> HexLand
```

- 用于清理生产状态键（STAGE/PROGRESS/CYCLE_PROGRESS/CROP/START_INPUT_PAID）；
- 不得删除 OWNER（具名拒绝）；
- 空 set = 原样返回；最后 normalize。

可增加只读 helper `componentOf(HexLand, LandTypeId, LandComponentId)`，但不得造第二套 component 查找/身份拼写点。

### 5.5 不变量

- 所有操作保持同 type 下总数量守恒（transfer/split）或数量不变（set/remove）；
- 所有操作保留非 owner 状态直到明确 overrides/remove；
- 不得调用 `setArableOwners` 处理 stateful component；
- 失败零半成品：全部前置校验完成后才构造新 `HexLand`；
- 排序/合并仍走现有 `normalize`；id 序号仍走 `nextComponentOrdinal()`，禁时钟/随机数/进程计数器。

### 5.6 U3b：把 component 状态穿过 scalar 土地写回

当前 `ArableSupplySnapshots.applyLedgerToMap` 在轮末用 `setArableOwners` 按 owner 聚合写回：
同 owner 多状态 component 会被拒绝或丢失状态。U2b 之前的桥接单：

唯一允许文件：

```text
simos-app/src/main/java/io/mosire/simos/app/world/ArableSupplySnapshots.java
```

目标：
- `applyLedgerToMap` 不再直接 `setArableOwners`；
- 以 Map 现有 component 树为基底，按最终 `LandComponentLedger.holdings` 的 owner 总量做
  “净差配对”：
  1. 计算每 owner `currentQuantity - targetQuantity`；
  2. 正差 = 卖方，负差 = 买方，按 owner id 稳定序配对；
  3. 每对调用现有 `LandComponentOperations.transferQuantity`，从卖方按 component id
     消耗，整块只改 OWNER、部分复制除 OWNER 外全部属性；
  4. 写完逐 hex 校验总数量 == ledger arable，并确认没有丢失/新增非目标 owner；
- 这样现有 scalar 市场/债务只改 owner 总量时，component 的 CROP/STAGE/PROGRESS 等状态
  自动随被转移的 component 保留；
- 不改变 `LandComponentLedger`/`LandHolding`/市场/债务的现有 API；
- 不允许静默重置状态，不允许把多个不同状态 component 先合并再按 owner 写回；
- 保持其它 LandType 与 Map 其它字段不变；
- 失败前置校验、返回新 GameMap，失败不产生半成品。

测试锚点（T 后续）：
- 10,000 毫亩 stage2/progress10 + 1,000 毫亩 idle 的 owner 从 h1 整体变 h2：
  两个 component 都保留原状态，只改 owner；
- h1 部分减少、h2 增加，数量守恒，state 不丢；
- 同 owner 多状态 component 不再触发 `land-owner-aggregation-unsupported`；
- 0/负/不守恒/未知 hex ⇒ 具名拒。

### 5.7 验收锚点（测试 later）

1. 10,000 毫亩 stage2/progress10 土地转 3,000 毫亩给 h2：source 7,000 仍 stage2，target 3,000 同 stage2，id 两个，总量不变；
2. 整块转移只改 OWNER，id/state 不变；
3. split 1,000 毫亩并 overrides 成 stage0/progress0：新 component 状态归零，source 保持 stage2；
4. set/remove 不误删 OWNER；
5. 同 owner 多状态 component 不再走 owner 聚合写回；
6. 0/负数量、超量、缺 component、OWNER 写入/删除、空 owner ⇒ 具名拒。

## 6. U2：统一 MarketOrder/MarketListing

U2 拆为两个责任区：U2a 先建统一订单/挂单契约与订单簿；U2b 再接现有市场撮合。

### 6.1 U2a：统一 MarketOrder/MarketListing 契约（下一单）

目标运行位置：S7 市场轮开盘前，把 U1a 的 `InventoryLotView` 转成本轮只读
`MarketListing`，并允许生成统一 `MarketOrder`；**本单不接 matcher、不改现有市场行为**。

拥有文件：

economy-api 新增：
```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/market/MarketOrderId.java
.../MarketOrderSide.java
.../MarketItemRef.java
.../MarketOrder.java
.../MarketListing.java
.../MarketOrderBook.java
```

app 新增：
```text
simos-app/src/main/java/io/mosire/simos/app/inventory/MarketListingBook.java
```

不得改测试/POM/既有市场类/tick 调用点；需要扩面 ⇒ BLOCKED。

形状：
```text
MarketOrderId(String value)             // opaque、非空白

MarketOrderSide { BUY, SELL }

MarketItemRef（sealed）
  Commodity(CommodityId)
  LotType(InventoryLotKind kind, String typeId, Optional<HexCoord> location)
  SpecificLot(InventoryLotId lotId)

MarketOrder(
  MarketOrderId orderId,
  MarketOrderSide side,
  ActorRef actor,
  HexCoord location,
  MarketItemRef item,
  Optional<InventoryLotId> sourceLotId,
  long quantityMilli,
  OptionalLong limitUnitValueMilli,
  CurrencyId payWith,
  Optional<Budget> budget,
  int priority)

MarketListing(
  InventoryLotId sourceLotId,
  MarketItemRef item,
  long quantityMilli,
  OptionalLong estimatedValueMilli)

MarketOrderBook(
  List<MarketListing> listings,
  List<MarketOrder> orders)
  withListing(...)
  withOrder(...)
  requireOrder(...)
  requireListing(...)
```

校验：
- SELL 必须带 `sourceLotId`；BUY 可带可空；
- `quantityMilli > 0`、`limitUnitValueMilli >= 0`、`priority >= 0`；
- `budget` 存在时其 currency 必须等于 `payWith`；
- `SpecificLot` 可直接作为 item；`LotType` 用于“任意同类型 lot”的买单；
- `MarketOrderBook` 构造期检查 orderId / sourceLotId 不重复，按
  `(side, itemKind/typeId, location, orderId)` 稳定排序，列表不可变；
- `MarketListingBook.from(InventoryLotView, Function<InventoryLot, OptionalLong> valuation)`：
  - 每个正数量 lot 一条 listing；
  - COMMODITY item = `Commodity`；LAND/ASSET item = `SpecificLot`；
  - `estimatedValueMilli` 由注入的估值函数纯派生，缺价 = empty；
  - 排序按 `sourceLotId.value()`；
  - 不改 lot、不读时钟/随机数。
- 不接现有 `BuyOrder/SellOrder/LandOrder`；不接 matcher；本单只证明统一契约可表达商品与土地。

### 6.2 U2b：统一市场订单簿接线（下一单）

目标运行位置：S7 市场轮 `prepareMarketRound` → 注入订单 → `MarketMatcher.matchAll` 之前。
本单把 `MarketRound` 的注入订单列表改为唯一 `List<MarketOrder>`；现有
`MarketIntentBook`/`MarketLandIntentBook` 暂时保留为生产侧意图 DTO，由 bridge 立即包装成
`MarketOrder`，不进入市场订单簿。U2c 再把生产侧 DTO 也迁成 `MarketOrder`（如果需要）。

允许修改/新增：
```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/market/MarketOrder.java
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketOrderBridge.java      新增
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketRoundCore.java
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayMarketPipeline.java
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketRoundRunner.java
```

不得改 matcher 价格/运费/冻结/债务公式，不得改 `MarketIntentBook`/`MarketLandIntentBook`，
不得改测试、POM、app/tick 调用点；需要扩面 ⇒ BLOCKED。

语义：
- `MarketRoundCore` 只保存 `List<MarketOrder> injectedMarketOrders`，不再保存
  `List<MarketIntent> injectedMarketIntents`；
- `MarketSettlement` 暴露 `injectedMarketOrders`；
- `SettlementDayMarketPipeline.prepareMarketRound(ctx, List<MarketIntent>)` 对每个 intent
  调 `MarketOrderBridge.fromIntent`，只把 `MarketOrder` 加入 round；
- `MarketOrderBridge` 负责：
  * COMMODITY intent → `MarketItemRef.Commodity`，sourceLotId = empty；
  * LAND intent → `MarketItemRef.LotType(LAND, ARABLE, location)`，sourceLotId = empty；
  * orderId 确定性（内容纯函数，禁 UUID/时钟）；
  * `toBuyOrder`/`toSellOrder`/`toLandOrder` 把统一订单转成现有 typed matcher 输入；
  * 兼容现有预算计算：`toBuyOrder` 接受外部 `budgetMilli`（沿用 `cashBudgetMilliOf`）；
  * SELL 的 limit 必须 present；BUY 可 empty；
- `MarketRoundRunner` 的 M3 自动商品订单替换循环改读 `injectedMarketOrders`：
  * 只处理 `item instanceof MarketItemRef.Commodity`；
  * 用 `order.side()`/`order.actor()`/commodity 组替换 key；
- 商品仍走既有 fungible matcher 算法；土地仍走现有 scalar matcher + U3b
  component-state-preserving 写回；**本单不改价格/运费/冻结/债务数值**；
- `MarketOrder` 的 SELL 规则改为：`SpecificLot` 侧 SELL 必须有 sourceLotId；
  Commodity/LotType 聚合 SELL 允许 sourceLotId 为空（聚合来源由 location/actor 表达）；
  SpecificLot 的 sourceLotId 若存在必须等于 item.lotId。
- 不接 U4 阶段生产；不实现 ASSET/stateful goods matcher。

## 7. U4：grain_farm 阶段批次

U4 拆为 U4a 定义 schema、U4b 运行批次。

### 7.1 U4a：ProductionStageDefinition schema（下一单）

目标：只把多阶段定义加入 production revision 状态；不改现有单周期 runtime。

允许新增/修改：
```text
simos-production/src/main/java/io/mosire/simos/production/contract/ProductionStageDefinition.java   新增
simos-production/src/main/java/io/mosire/simos/production/contract/ProductionStageEndAction.java    新增
simos-production/src/main/java/io/mosire/simos/production/contract/IndustryDefinitionView.java
simos-production/src/main/java/io/mosire/simos/production/spi/ProductionDefinitionPayloads.java
```

形状：
```text
ProductionStageEndAction { NEXT_STAGE, HARVEST }

ProductionStageDefinition(
  String stageId,
  long durationDays,
  Map<CommodityId, Long> startInputPerUnit,
  Map<CommodityId, Long> tickInputPerUnit,
  ProductionStageEndAction endAction,
  Map<CommodityId, Long> outputPerUnit)
```

规则：
- `stageId` 非空白；`durationDays >= 1`；
- 两个 input map 与 output map 非 null、值 >= 0；键非 null；保序不可变；
- `IndustryDefinitionView` 末尾新增 `List<ProductionStageDefinition> stages`；
  为兼容现有构造调用，保留旧 arity 构造器，默认 `stages=List.of()`；
- 有 stages 时：
  * `cycleDays == Σ durationDays`；
  * stageId 不重复；
  * 最后一项必须是 `HARVEST`；`HARVEST` 不能出现在非最后项；
  * `NEXT_STAGE` 必须有下一项；
  * legacy `cycleInputPerUnit` / `dailyInputPerUnit` / `outputPerUnit` 必须为空（避免两套真相）；
  * v1 不允许阶段级 capacity/labor；沿用 industry 级。
- 无 stages 时：现有 legacy 字段原样，行为不变。
- `ProductionDefinitionPayloads` 解析可选 `stages[]`，缺省空表；每个 stage 的输入/输出 map 用现有 commodityMap helper 同口径。
- 不解析/运行 stage；不新增 ProductionProcess 字段；不改 economy/app。
- 只做到编译通过，不写/不跑测试。

### 7.2 U4b1：ProductionProcess 阶段状态 + 引擎阶段推进（下一单）

目标：先打通 production 引擎层，让同一个 `ProductionProcess` 能在
`ProductionStageDefinition` 上推进；不接 economy S4 input settlement，不接
TransportCapacitySink。

允许新增/修改：
```text
simos-production/src/main/java/io/mosire/simos/production/contract/ProductionStageState.java（新增）
simos-production/src/main/java/io/mosire/simos/production/contract/ProductionProcess.java
simos-production/src/main/java/io/mosire/simos/production/ProductionRuntimeEngine.java
```

形状：
```text
ProductionStageState(
  String stageId,
  long stageProgressDays,
  Map<CommodityId, Long> stageStartInputsUsedMilli)
```

`ProductionProcess` 末位新增 `Optional<ProductionStageState> stageState`：
- legacy 空 = 现有单周期行为不变；
- 保留旧 arity 构造器（默认 empty）；
- `withContribution`/`withIdentity`/`withCycleState` 保留 stageState；
- `withCycleState(0,...)` 关账时清空 stageState（下一周期由引擎初始化第一段）。

`ProductionRuntimeEngine` 仅当 `plan.stages()` 非空时走 staged path：
- 初始化第一段；校验 `stageStartInputsUsedMilli`/`tickInputPerUnit` 与
  `request.finalInputsMilli()`（v1 只做一致性与累计，不重新扣库存）；
- `progressDays += 1`，`stageProgressDays += 1`；
- 阶段结束：
  - NEXT_STAGE → 切换下一段，stageProgress=0；
  - HARVEST → cycleClosed=true，final stage output 进入 ProductionReceipt.grossOutput；
- `grossOutput` 按 final stage `outputPerUnit × finalScale × 1000` 计；
  最终 stage 的 `outputPerUnit` 可以包含 `transport_capacity_service` 服务键；
- legacy plan.stages 空时逐值走旧路径；
- 失败零半提交（不推进/不累计）。

不在本单：
- economy 的 `ProductionInputSettlement` 仍不读 stages；
- `TransportCapacitySink` 仍不接 staged harvest；
- Map/land carrier lot 状态；
- 真正 180 tick/JSON 测试属 T3。

### 7.3 U4b2a：阶段有效配方投影（下一单）

目标：让 economy 的候选/试算/排产路径看到 staged plan 的“有效周期投入/最终产出”，
不改变 legacy plan 的 recipe() 结果。

允许修改：
```text
simos-production/src/main/java/io/mosire/simos/production/contract/IndustryDefinitionView.java
simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionProbeInputs.java
simos-production/src/main/java/io/mosire/simos/production/ProductionRuntimeEngine.java（仅 probe 的投入/产出读口）
```

规则：
- stages 空 ⇒ `recipe()` 逐值同旧。
- stages 非空：
  * `effectiveInputPerUnit()` = Σ_stage(startInput + tickInput × durationDays)；
  * `effectiveOutputPerUnit()` = 最后 HARVEST 阶段 outputPerUnit（NEXT_STAGE 中间产出 v1 不计入计划产出）；
  * `recipe().inputPerUnit()/outputPerUnit()` 用上述有效表；capacity/labor/durable/money 仍取 industry 级；
  * `cycleInputPerUnit()/dailyInputPerUnit()/outputPerUnit()` 仍为空（不写回第二套真相）。
- `ProductionProbeInputs.inputsOf` 改读 `industry.recipe().inputPerUnit()`。
- `ProductionRuntimeEngine.probe` 的投入/产出通道改读 `plan.recipe()`，使 staged plan 的候选估算非空；
  execute 的 staged path不变。
- 不接 S4 扣料/ S5 sink；不接 Map lot。

### 7.4 U4b2b：S4 阶段扣料（U4b2a 之后）

- `ProductionInputSettlement`/`ProductionInputDrawSettlement` 按当前 `ProductionProcess.stageState`
  选择当前阶段的 start+tick 需求；
- staged unit 每个 tick 都进入现扣，不再只认 `progressDays == 0`；
- start 只在阶段第一天扣，tick 每日扣；
- `DailyDemandBill` 的 PRODUCTION_INPUT/PRODUCTION_DAILY_EXTRA 按当前 stage 需求生成；
- legacy stages 空时保持 `progressDays == 0` 门与旧配方逐值不变。

### 7.5 U4b2c：S5 阶段产出/SERVICE_OUTPUT（U4b2b 之后）

- `ProductionRunSettlement` 的产出表改读 `industry.recipe().outputPerUnit()`（最终 HARVEST）；
- `transport_capacity_service` 从 staged final harvest 走 `TransportCapacitySink`，不进普通库存；
- `ProductionSettlement.Facts` 的 output facts 同步；
- 接 staged 收获的 sink/库存并补正负例。

### 7.6 U4b3：Map land component 状态（后续）

- 生产批次从 Map component 读 carrier state；
- 劳动/投入不足按可用量缩规模，未覆盖 lot 拆出并归零；
- 不改 S0/S2/S6/S8 顺序。

## 8. 测试 owner（当前进入 T1 基础契约检查点；T2 360 另派）

### 8.1 T1：U1a/U1b/U3/U2a 正负例 + 变异

允许写面（只新增测试文件，不改生产代码）：
```text
simos-map/src/test/java/io/mosire/simos/map/ops/LandComponentStateOpsTest.java
simos-app/src/test/java/io/mosire/simos/app/inventory/InventoryLotViewBuilderTest.java
simos-app/src/test/java/io/mosire/simos/app/inventory/InventoryTransferPortTest.java
simos-app/src/test/java/io/mosire/simos/app/inventory/MarketOrderContractTest.java
simos-app/src/test/java/io/mosire/simos/app/inventory/InventoryTestFixtures.java（可选 helper）
```

必须覆盖：
- U1a：商品行、ARABLE 土地行、同 owner 多状态 component 两行、稳定排序、缺 owner/不可解析/重复 id/负数量负例；
- U1b：商品余额转移 + 冻结不足拒；土地部分转移复制 state；target owner/location 校验；plan 不改 base 状态；moduleChanges 非空；receipt 一致；
- U3：整块/部分转移属性保留、split overrides 重置、set/remove 不写 OWNER、数量守恒、0/负/超量负例；
- U2a：SELL 必须 sourceLotId、BUY 可空；budget 币种一致；MarketOrderBook 排序/重复拒；MarketListingBook 商品与土地两类 listing。

测试命令：
```bash
tools/mvn-lock.sh -pl simos-app -am -Dtest=InventoryLotViewBuilderTest,InventoryTransferPortTest,MarketOrderContractTest,LandComponentStateOpsTest -DfailIfNoTests=false test
```
通过后跑一次 app 模块全测试（若全测试时间过长，至少跑 `simos-app -am test` 并记录 rc/测试数；不许把没跑写成通过）：
```bash
tools/mvn-lock.sh -pl simos-app -am test
```

变异自证（至少 3 组，改坏→目标测试当场红→还原→md5 一致）：
1. 删 U1a 土地 owner 缺失 fail-closed；
2. 删 U3 部分转移的 state 复制（只改 owner）；
3. 删 U1b 商品 frozen 不足检查。
变异只允许临时改生产文件，恢复后必须 `md5sum` 与备份一致；测试 owner 不得把临时改坏留在工作树。

### 8.1b T3：JSON 驱动的分阶段验收（无 MCP GM）

另派测试 owner，使用 U4b1 production 引擎直接验收（不依赖 MCP GM 工具）：
- 农业：
  * 从 JSON A 解析一个完整的两阶段 `IndustryDefinitionView`（阶段 A 大开始投入、阶段 B 持续低耗，最终 HARVEST 产 grain）；
  * `ProductionRuntimeEngine.execute` 推进 180 tick；每 tick 根据 `stageState` 供应当前阶段的 start/tick 输入；
  * 180 tick 后读取 JSON B（修改部分数值并新增一个阶段），用新的 `ProductionData.withIndustry` 构造新 engine，继续跑 10-20 tick；
  * 断言新阶段被进入、改后的 stage/tick input 或 output 生效，180 tick 前无异常。
- 运力：
  * JSON C：`caravan_transport` 两阶段：stage1 1 tick 大量物资准备（startInput 大），stage2 4 tick 低耗/无大耗持续，最终 HARVEST outputPerUnit = `transport_capacity_service`；
  * 跑 5 tick；断言第 1 tick 消耗大物资、后 4 tick 不重复大消耗、最终 `ProductionReceipt.grossOutput` 含服务键且 cycleClosed=true。
- 允许测试文件：
```text
simos-production/src/test/java/io/mosire/simos/production/spi/ProductionStageJsonAcceptanceTest.java
（或生产模块内等价测试类 + 可选 test-only helper）
```
- 不得改生产代码；若测试触发 U4b1 bug，报告 BLOCKED/失败，不修生产。
- 目标命令：`tools/mvn-lock.sh -pl simos-production -am -Dtest=ProductionStageJsonAcceptanceTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test`
- 记录 rc/测试数/关键读数；不要求接 economy S4/S5 或 Map land lot。

### 8.1c T4：真实经济世界 staged 农业 180 tick（无 MCP）

目标：在现有 32hex/单市场区真实世界夹具上，替换一个正在使用的 grain_farm 定义为 staged 版本，
用 Shell 跑 180 tick；再用测试侧 JSON B + `CoreSimos.submitRestore` 提交一条 ProductionChangeSet
revision（不依赖 MCP），继续跑 10-20 tick，验证新阶段/新数值生效。

允许测试文件：
```text
simos-app/src/test/java/io/mosire/simos/app/sim/StagedAgricultureEconomy180Test.java
（可选 test-only helper）
```

实现建议：
- 以 `Rural32HexWorld.state(MAP_ID)` 为创世；从 production 切片选一个带实例的 grain_farm plan；
- 在 state 上替换该 plan 为 staged plan（同 id、同 capacity/labor/roles 等，cycleDays 保持原值；
  stages 总时长必须等于 cycleDays；legacy input/output 表清空）；
- 如现有家户粮食不够 stage 第一 tick 的大宗投入，可在**测试创世状态**里给相关家户库存加测试粮食；
- `Shell.start` + `bootstrapGenesis`，循环 `advanceAndDrain(AdvanceTime)` 180 次；
- 180 后从 JSON B 构造新 stages/plan；用 `ProductionChangeSet.between(oldProductionData,newProductionData)`
  和 `CoreSimos.submitRestore(branch, expectedRevision, initiator, WorldChangeSet(Map.of("production", cs)))`
  提交一条 revision；再跑 10-20 tick；
- 断言：180 tick 无异常、目标 unit 的 stageState 进入过 sow/grow（或等价阶段）、180 后新 stage 进入、
  新 tick/output 数值在 receipt/库存/生产读数中可见；legacy 其他 plan 不崩；
- 若真实经济路径暴露 S4/S5 未接/无法运行，保留失败并报告 BLOCKED；不要改生产代码。

目标命令：
```bash
tools/mvn-lock.sh -pl simos-app -am -Dtest=StagedAgricultureEconomy180Test -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test
```
记录 rc/测试数/关键读数/耗时；不得把没跑写成通过。

### 8.2 正/负例

- U1a/U1b/U3/U2/U4 各自的验收锚点；
- 跨域守恒：商品余额、土地面积、owner、state、lot id 不漂；
- 多状态土地：10 亩阶段 2 + 1 亩归零；
- 部分卖地：状态复制、数量守恒、旧 batch detach；
- 同一 lot 不得双冻结/双占用；
- 订单不带 state、sourceLotId 缺失/伪造 ⇒ 具名拒。

### 8.2 变异测试

至少做 3 组“改坏 ⇒ 当场红 ⇒ 还原 ⇒ md5 一致”：

1. 把 U1a 土地 owner 缺失的 fail-closed 删掉；
2. 把土地部分转移的 state 复制删掉（只改 owner）；
3. 把缩规模的 uncovered lot 归零删掉。

### 8.3 360 tick 中途修改生产花费

在现有 32hex/360d sim 基础上新增或扩展一个 case：

- 0→179 tick 正常跑；
- tick 180 提交一次 `production.UpsertIndustry`，修改真实在生产中使用的产业定义
  （优先改 `cycleInputPerUnit` 或 `outputPerUnit`，保留原有其它字段）；
- 180→360 继续；
- 断言：
  - 命令成功、产生新 revision；
  - 生产未因定义缓存/旧快照而中断；
  - 修改后的成本/产出在后续周期读数中可见；
  - 商品/货币/土地/债务守恒无负数/无重复 lot；
  - 360 结束 rc=0，CSV/MD 落盘；
- 如果当前生产周期在 180 尚未关账，必须覆盖“周期中途改定义”而不是只改下一个周期；
- 保留真实命令与输出证据。

## 9. 用户原话附录

```text
设计很不错，再检查一下有没有要做的裁定，没有的话就开始一个一个派单，派单完简单跑一下变异测试等确保项目可行性（如360tick中途修改生产花费之类的）
```

## 10. 未做/边界

- 本计划本身未改代码、未跑 Maven、未提交；
- U5（非土地长期生产资料/stateful goods）是否进入本轮由 U1–U4 完成后的状态决定；
- 不碰主树 `/home/cna/SimulatorMosire`；
- 不 commit/push，除非用户另行明确。
