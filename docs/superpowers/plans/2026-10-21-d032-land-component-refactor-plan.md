# D-032 单 hex 土地组件树完整重构规划（2026-10-21）

> **状态：实现规划。用户已选方案 A：owner 写在 Map 土地组件的抽象属性里，Map 是土地组件/所有权唯一权威。**
> 本文只规划，不代表已实现。用户原话 > 本文工程默认 > 旧文档。
>
> 父目标运行序列：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` §1 S0–S8。
> 前置设计：`docs/superpowers/specs/2026-10-21-d032-land-component-tree-and-arable-slice.md`。
> 本文落地目标：把当前 flat `HexCell.areas` + Economy `LandHolding` 双权威，重构成
> `Map HexLand → LandTypeArea → LandComponent(quantity, attributes)` 的单权威结构。

---

## 1. 用户原话（逐字）

> 你猜猜我为什么要把这个重构单独提出来，很显然，我选方案A；注意做好合并管理，最直接的合并原则是两块土地成分除了面积啥都一样，那就合并，按照这个写一下完整的重构规划，没有需要做的裁定就开始派单，有就告诉我你想怎么裁

同轮前置原话（2026-10-21）：

> 先实现统一口径的单格耕地吧，不过你漏考虑了一点，那就是后面我要做独立的耕地进度，因此地块数据类型必须被拆成旗下有多个部分，每一部分规定具体数量和抽象状态参数，其中抽象状态参数表里可以加家户所有权，也可以在后面加农业生产相关属性，也就是说，单hex实际土地数据类型必须成为 土地类型（如耕地）：面积成分：抽象属性 三档，这个机制能实现吗？

---

## 2. 目标与非目标

### 2.1 目标

1. **Map 唯一权威**：单 hex 的每种土地类型由零个或多个 `LandComponent` 组成；
2. 每个 component 有：稳定 id、具体数量、抽象属性表；
3. `owner` 是 component 属性，值为家户 id 的不透明字符串；Map 不 import `HouseholdId`；
4. 总量（arable/urban）由 component 数量求和派生，不再另存 flat 总量；
5. 自动合并：同一土地类型下，两个 component 的**属性表逐项完全相等** ⇒ 合并数量；
6. 后续独立耕地进度、作物、肥力等都可作为属性加入，不需要再次改土地形状；
7. 市场/债务/迁移/合户中土地所有权变化，最终写回 Map component 属性；
8. Economy 不再持久保存 `LandArableSupply` 与 `LandHolding` 作为第二本土地账；需要时用瞬态组件账本投影。

### 2.2 非目标（本重构不做）

- 不做城区自动增长、城区容量池、工坊城区加成；
- 不实现城市化迁移；
- 不实现具体农艺进度公式（只留属性位和组件拆分/合并规则）；
- 不做旧档兼容（Alpha：旧土地形状直接重建）；
- 不改商品/土地市场价格公式、不改债务/工资规则；
- 不新增第二个土地市场或商户市场。

---

## 3. 目标数据形状

### 3.1 值类型

```java
record LandTypeId(String wireValue) {}
// 内置：arable / urban；自定义：custom:<name>（或等价开放表示）

record LandComponentId(String value) {}
// 形如：arable@0_0:0

enum LandAttributeKind { LONG, STRING, BOOLEAN }

record LandAttributeValue(
    LandAttributeKind kind,
    String canonicalValue
) {}
// 例：LONG "0"、STRING "hh-0_0-rural-landed_peasant"、BOOLEAN "true"

record LandAttributeKey(String wireValue) {}
// 内置：owner / progress / crop / fertility；自定义：custom:<name>

record LandComponent(
    LandComponentId id,
    long quantityMilliMu,
    Map<LandAttributeKey, LandAttributeValue> attributes
) {}

record LandTypeArea(
    LandTypeId type,
    List<LandComponent> components,
    long nextComponentOrdinal
) {}

record HexLand(
    Map<LandTypeId, LandTypeArea> byType
) {}

record HexCell(
    double height,
    HexLand land
) {}
```

### 3.2 为什么不是 `Map<String,Object>`

- `Object` 无法可靠 Codec/ChangeSet 往返；
- 值的类型、整数/布尔规范串、排序都必须唯一；
- `LandAttributeValue` 用 `kind + canonicalValue`，Jackson 可稳定往返，消费者按类型解析，非法值具名拒；
- 属性表按 `LandAttributeKey.wireValue()` 规范升序冻结，保证确定性。

### 3.3 组件 id 与序号

- 每个 `LandTypeArea` 存 `nextComponentOrdinal`（≥ 1）；
- 创世第一个 component 用 ordinal 0：
  `arable@0_0:0`，然后 `nextComponentOrdinal = 1`；
- 每次真正创建新 component：
  `id = type + "@" + hex + ":" + nextComponentOrdinal`，然后 ordinal + 1；
- 删除 component **不回收**序号；
- 合并保留**字典序最小**的 component id，删除其他 id；
- 序号落盘，不能用进程内计数器/随机 UUID/墙钟。

### 3.4 数量与不变量

| # | 不变量 |
|---|---|
| I1 | 同一 `(hex, type)` 下 component id 唯一 |
| I2 | `quantityMilliMu >= 0`；0 数量 component 不持久存在 |
| I3 | 每个 land type 的总量 = `Σ components.quantityMilliMu` |
| I4 | `arable` 每个 component 必须有非空 `owner` 属性（本重构冻结；无主耕地语义未裁定，不做） |
| I5 | 同一 `(hex, type)` 下，除 `id` 与 `quantityMilliMu` 外，属性表逐项完全相同的 component 必须已合并 |
| I6 | 不同 land type 的 component 永不合并 |
| I7 | component 属性变化不改 component id；owner 变化不改 id |
| I8 | 所有属性 map 按 key 规范升序；component list 按 component id 规范升序 |

---

## 4. 合并规则（用户原话落地）

### 4.1 合并判据

对同一 `(hex, landType)` 下的两个 component `A`、`B`：

```text
A.attributes == B.attributes      // 逐 key、逐 kind、逐 canonicalValue 完全相等
```

则合并：

```text
quantity = A.quantity + B.quantity
id       = min(A.id, B.id)         // 字典序最小，稳定
attributes 保留同一份
```

**不参与判等的字段：**

- `id`（不同 identity 本来就可以合并）；
- `quantityMilliMu`（用户明确“除了面积啥都一样”）。

**必须参与判等的字段：**

- land type 外层已经限定相同；
- 所有属性，包括 `owner`、`progress`、`crop`、`fertility` 以及未来自定义属性。

### 4.2 合并时机

每次会改变 component 数量/属性/owner 的操作结束后，立即对受影响的 `LandTypeArea` 执行：

```text
removeZeroQuantityComponents()
mergeEqualAttributeComponents()
sortByComponentId()
```

包括：

- 初始播种；
- GM 设置/删除 component；
- 市场土地成交（部分数量转移）；
- 债务抵地；
- 合户土地合并；
- 迁移/清区；
- 未来 progress/crop 属性更新（如果同一 owner/进度变得相同也会合并）。

### 4.3 部分数量转移

`fromOwner → toOwner` 转移 `q` 时：

1. 选 `fromOwner` 的属性为 owner 的 component，按 component id 升序；
2. 逐 component 取数量；
3. 若刚好整块转走：只改该 component 的 `owner` 属性，id 不变；
4. 若需要拆分：
   - 原 component 保留卖方剩余数量、原 id；
   - 新建买方 component，id 用本类型下一个序号，属性 = 原属性但 owner 改为买方；
   - 数量为转移量；
5. 执行合并规则：
   - 买方已有同属性（同 owner、同 progress/crop/fertility）component ⇒ 面积相加、id 取最小；
   - 卖方剩余同属性 component ⇒ 同理合并；
6. 若某 component 数量归 0 ⇒ 删除；
7. 任何一步数量不守恒 ⇒ fail-closed，不允许负数量。

### 4.4 转移顺序

同一 owner 有多个 component 时，市场/债务的“任意数量”转移默认按 **component id 升序** 消耗。
这是工程默认（确定性），不是用户已裁定的经济偏好；若以后要按 progress/crop 优先卖某块，另开裁定。

---

## 5. 所有权权威与跨模块写回

### 5.1 目标

```text
Map HexLand.component.attributes["owner"] = "hh-..."
```

是土地所有权的唯一权威。

Economy 不再持久保存：

```text
EconomyData.landArableSupply
EconomyData.landHoldings
```

### 5.2 运行期写回链

```text
app S1:
  从 Map 读 HexLand 组件 → 构造 Economy 会话的瞬态 LandComponentLedger
economy S2–S7:
  市场/债务/合户/迁移只改瞬态 ledger（不写 EconomyData 土地组件）
app S7 结束/本 tick 收尾:
  把 transient ledger 的组件变化写回工作 GameMap
  在同一 WorldTimeProposal 里放 MapChangeSet + Economy/Social/Actor/Production ChangeSet
  ⇒ 同一 revision，失败零半提交
```

实现落点：`PopulationEconomyTimeParticipant` 是当前唯一同时协调 economy/social/actor 的时间参与者；
它已经读 Map，需要新增 **map writes 声明** 与 `moduleChanges.put(MAP, MapChangeSet.between(mapBase, currentMap))`。
备用 `EconomyOwnershipTimeParticipant` 仍是 deprecated 孤儿，本重构不把它扩展成第二套生产写手。

### 5.3 Economy 瞬态账本

新增（住 economy 内部，不落盘）：

```text
LandComponentLedger {
  Map<HexCoord, HexLand> byHex;              // 本 tick 工作副本
  Map<HexCoord, Long> arableTotalByHex;      // 从 Map 快照派生
  Map<LandHoldingKey, LandHolding> holdings; // 兼容现有市场/债务代码的只读/派生视图
}
```

- `holdings` 从 components 的 `owner` 属性聚合得到，键 `(hex, owner)`；
- 市场/债务现有 `LandHolding` 读口先保留为**瞬态投影**；
- 任何实际转移都走 `LandComponentLedger.transfer(...)`，由它调用组件拆分/合并规则；
- 不再把 `holdings` 写进 `EconomyData`/ChangeSet/Codec。

### 5.4 删除面

| 现有 | 重构后 |
|---|---|
| `LandArableSupply` 持久组件 | 删除；arable 总量由 Map 组件求和 |
| `LandHolding` 持久组件 | 删除持久化；保留瞬态视图类型 |
| `LandHoldingKey` 持久 map key | 只作瞬态投影键 |
| `EconomyData.landArableSupply/landHoldings` | 删除 |
| `EconomyChangeSet` 两列 | 删除对应字段 |
| `EconomyCodec`/Payload 两键 | 删除对应 wire key；Alpha 重建 |
| `EconomyStateBuilder` 土地工作副本 | 改为注入瞬态 `LandComponentLedger` |
| `LandProductionProjection` 读 Economy arable | 改为读 Map 组件快照 → D4 `Map<AssetKind,Long>` |
| `LandBook` | 删除或替换为组件级纯校验/转移 helper；不再有独立 arable supply |

---

## 6. 目标运行序列（嵌在 v2 S0–S8）

| 阶段 | 本重构后的土地链 |
|---|---|
| S0 Social | 不变 |
| S1 经济载入 | app 读 Map `HexLand`；构造 `LandComponentLedger`；校验 arable component 有 owner；Economy 行仍从 Social 载入 |
| S2 进入/迁移/组织 | 合户/迁移的土地变化通过组件账本；owner 属性变更；合并规则立即执行 |
| S3 生产位置 | D4 的 LAND 投影来自组件账本；`LandHolding` 只是瞬态聚合 |
| S4 生产日 | production 仍只收 `Map<AssetKind,Long>`；不直接 import Map component 类型 |
| S5 收获 | 毛产按 actual participating component/owner 份额；未种不参与 |
| S6 工资 | 不变 |
| S7 市场/债务 | 土地订单/抵债走组件账本；0 价合法；本轮新取得仍排除当前生产 tick；轮末组件账本写回 Map |
| S8 Social 事实 | 不变 |
| app 收尾 | MapChangeSet + economy/social/actor/production 同一 revision；Map 是 owner/quantity 唯一持久权威 |

---

## 7. 分责任区与批次

> 一个责任区 = 一个写代码 Agent，一次写完该责任区全部生产代码，只到 `-DskipTests compile`。
> 测试统一后置，不在实现 Agent 批次里写/跑。

### B1：Map 土地组件树与纯操作

**责任区：** `simos-map` 的土地形状、组件运算、Map 读口/写命令。

交付：

1. `LandTypeId`、`LandComponentId`、`LandAttributeKey`、`LandAttributeKind`、`LandAttributeValue`；
2. `LandComponent`、`LandTypeArea`、`HexLand`；
3. `HexCell` 改为 `(height, land)`，保留 `HexCell(double)` 便利构造器；
4. `LandComponentOperations` 纯操作：
   - `seedSingleComponent`；
   - `setComponent`；
   - `removeComponent`；
   - `setOwner`；
   - `transferQuantity`；
   - `mergeEqualComponents`；
   - `totalMilliMu`；
5. Map 组件读写命令/工具：
   - `map.UpsertLandComponent`
   - `map.RemoveLandComponent`
   - `map.SetLandComponentAttribute`
   - `map.TransferLandQuantity`（可供 app 内部账本/未来 GM 用）
   （命令名可最终按 Map 命名纪律微调，但必须保留四个语义动作）
6. 旧 `HexAreaOperations`/`SetHexAreaHandler` 改为“仅单 component 类型总量 setter”；
   多 component 时具名拒绝，不静默合并/重建。
7. `HexAreas` 读口输出新的 `land` 树与派生 `areas` 总量；旧的 flat wire 不持久化。

**不写：** economy/social/production 持久状态；测试；pom；主树。

**编译门槛：** `tools/mvn-lock.sh -q -DskipTests compile -pl simos-map -am`；
若 app 因 `HexCell` API 编译红，允许只做 main 编译适配（`ApiViews`/世界生成器/MapHexTool），不写测试。

### B2：创世土地组件与 arable 总量切源

**责任区：** app seeder → Map 组件；production/economy 读 Map arable 总量。

交付：

1. `EconomySeeder`/`ProductionRuntimeSeeder` 生成土地组件种子：
   - 每个 hex 一个 `arable` 类型；
   - 当前 `GrainFarmUnit.landMilliMu` 按 owner 生成一个 component；
   - owner 写 `LandAttributeKey.OWNER`，其它属性先空；
   - `nextComponentOrdinal` 从 component 数起；
2. `Rural32HexWorld` 等在 Map 初始状态上应用组件种子；
3. `landMilliMuOf(terrain)` 不再作为生产 arable 来源；
4. `LandArableSupply` 若仍暂时存在，只能由 Map 组件求和生成，不能反算；
5. app 读口显示 `landTypes`、`components`、`arableMilliMu`、`urbanMilliMu`、owner 属性。

**验收锚点：** 32hex 每个 hex arable = 3,100,000 毫亩；urban = 0；component owner 可读；
LandHolding 暂留时与组件 owner 聚合逐值相等。

### B3：Economy 去掉持久土地双组件

**责任区：** `simos-economy-api` + `simos-economy` 的 land 状态形状与工作副本。

交付：

1. 删除 `EconomyData.landArableSupply/landHoldings`；
2. 删除 `EconomyChangeSet`/`EconomyCodec`/`EconomyPayloads`/`EconomyClearRegion`/`EconomySeedHandler` 中对应持久键；
3. `EconomyStateBuilder` 改为注入 `LandComponentLedger` 工作副本；
4. `LandProductionProjection`/`SettlementIndex` 改吃组件账本；
5. `MarketRound`/`MarketLandBook`/`DebtFirstSettlement`/`HouseholdMergeSettlement` 的 land 读口改到瞬态 ledger；
6. `LandBook` 删除或改为组件级守恒 helper。

**失败面：** 旧 payload 缺土地键按 Alpha 重建，不写迁移/兼容位。

### B4：市场/债务/合户/迁移写回 Map

**责任区：** economy 时间结算 + app 组合根。

交付：

1. 所有土地数量转移统一走组件账本 `transfer`；
2. `PopulationEconomyTimeParticipant` 读 Map 并写回 Map，加入 MAP 写集与 MapChangeSet；
3. 同 revision 原子提交；
4. 0 价 land、债务抵地、本轮新取得排除、下一生产 tick 可用等既有语义逐条保留；
5. 若组件 id/owner/数量在写回时不一致 ⇒ fail-closed。

### B5：独立耕地进度属性与合并验收

**责任区：** 后续生产/土地属性批次。

交付：

1. `progress`/`crop`/`fertility` 等属性键的生产语义；
2. 属性更新走 Map 组件写口；
3. 每次属性更新后执行合并规则；
4. 同一 owner、同 progress/crop/fertility 的不同 component 自动合并；
5. 为独立农艺进度预留后续字段，不改土地三档形状。

---

## 8. 工程默认（不需要用户逐项裁定）

| # | 默认 |
|---|---|
| E1 | `LandTypeId` 内置 `arable`/`urban`，自定义用开放 wire string；本批只实现 arable 生产总量和 urban=0 |
| E2 | `LandAttributeValue` 只用 LONG/STRING/BOOLEAN 三型，规范字符串落盘 |
| E3 | `owner` 属性用家户 id 的不透明字符串；Map 不 import HouseholdId |
| E4 | `arable` component 必须有非空 owner；无主耕地不实现 |
| E5 | component 数量 0 ⇒ 删除 |
| E6 | 合并保留字典序最小 id；新 id 由每类型 ordinal 递增分配 |
| E7 | 同一 owner 多 component 的默认转移顺序 = component id 升序 |
| E8 | 合并判等 = 外型相同 + attributes 全同；不包括 id/quantity |
| E9 | 经济市场/debt 暂时只交易 `arable`；`urban` 目前无机制 |
| E10 | 旧档不兼容，旧 `areas`/`LandHolding` wire 直接作废，不写迁移 |
| E11 | `LandHolding` 保留为瞬态组件账本的聚合视图，不作为持久状态 |
| E12 | app 写回 Map 的 revision 与 economy/social/actor/production 同一个 WorldTimeProposal |

**没有需要请用户裁定的项。**  
若用户不推翻以上默认，直接按 B1→B2→B3→B4→B5 派单；第一步只派 B1。

---

## 9. 验收锚点（总）

1. `HexLand` 三档结构可 Codec/ChangeSet 往返，字节确定；
2. `arable` 总量 = 组件数量求和；urban=0；
3. 同一 `(hex,type)` 中除 id/quantity 外属性全同的组件自动合并；
4. 部分数量转移正确拆分/合并，Σ数量守恒，id 稳定/新 id 不重复；
5. Map 是 owner/quantity 唯一持久权威；Economy 无 `landArableSupply`/`landHoldings` 持久行；
6. 32hex 360 tick 仍 `OK=360 / REJECTED=0`，土地所有权/债务抵地/市场成交守恒；
7. 生产位置、工资、债务、市场、Social 事实的既有语义不因土地重构改变；
8. 所有失败路径具名 fail-closed，不留半提交。

---

## 10. 用户原话附录（同轮相关）

> 你猜猜我为什么要把这个重构单独提出来，很显然，我选方案A；注意做好合并管理，最直接的合并原则是两块土地成分除了面积啥都不一样，那就合并，按照这个写一下完整的重构规划，没有需要做的裁定就开始派单，有就告诉我你想怎么裁

> 先实现统一口径的单格耕地吧，不过你漏考虑了一点，那就是后面我要做独立的耕地进度，因此地块数据类型必须被拆成旗下有多个部分，每一部分规定具体数量和抽象状态参数，其中抽象状态参数表里可以加家户所有权，也可以在后面加农业生产相关属性，也就是说，单hex实际土地数据类型必须成为 土地类型（如耕地）：面积成分：抽象属性 三档，这个机制能实现吗？

---

## 11. 执行记录

### 2026-10-21 B1/L1

- 状态：**已完成 main 编译绿**（未 commit/push，未跑测试）。
- 新增：`LandTypeId`、`LandComponentId`、`LandAttributeKind`、`LandAttributeValue`、
  `LandAttributeKey`、`LandComponent`、`LandTypeArea`、`HexLand`、`LandComponentOperations`。
- 修改：`HexCell`、`HexAreas`、`HexAreaKey`、`HexAreaOperations`、`MapCodec`、
  `MapPayloads`、`SetHexAreaHandler`、`MapSetHexAreaTool`。
- 编译：`spotless:apply rc=0`；`simos-map -am -DskipTests compile rc=0`；
  `simos-app -am -DskipTests compile rc=0`。
- 已知偏离：旧 coord-less `HexAreas.initialCell` 只能生成占位 id；B2 已把真实世界改为
  coord-aware；删除最后 component 时保留空 `LandTypeArea` 以不回收序号；B1 未强制 arable owner。

### 2026-10-21 B2/L2

- 状态：**已完成 main 编译绿**（未 commit/push，未跑测试）。
- `HexAreas` 增加 `arableMilliMu/urbanMilliMu/landTotals`。
- `MapGenerator`、`SmallWorld`、`ThreePowersWorld`、`CorridorWorld` 改真实 `HexCoord` 的 `initialCell`。
- 真 `GameMap` 入口 `EconomySeeder.plan` 构造 Map arable 来源并传给 `ProductionRuntimeSeeder`；
  `ProductionRuntimeSeeder` 不再用 terrain 现算真实 LAND。
- 过渡双账守卫：真地图创世逐 hex 校验 `Economy landArableSupply == Map arable`。
- 编译：`spotless:apply rc=0`；`simos-map -am -DskipTests compile rc=0`；
  `simos-app -am -DskipTests compile rc=0`（首轮有 seeder 引用错误，已修）。
- 未做：未跑测试/360、未删 Economy 土地持久组件、未写 owner 属性、未做 B3–B5。

### 2026-10-21 B3/L3

- 状态：**已完成 main 编译绿**（未 commit/push，未跑测试）。
- `EconomyData.landArableSupply` 持久组件删除；`LandArableSupply` 降为瞬态快照。
- `EconomyStateBuilder`/`EconomySession`/`EconomyDayStepper` 显式接收 Map 派生 supply；
  `PopulationEconomyTimeParticipant`/`EconomyOwnershipTimeParticipant` 注入。
- `EconomyChangeSet`/Codec/Payloads/SeedHandler/ClearRegion/Invariants/Transforms/BoundaryView/DayView
  删除持久 arable 键；旧 `landArableMilliMu` 显式拒绝。
- app 新增 `ArableSupplySnapshots`，逐 holding hex 校验 `Σ holdings == Map arable`。
- 编译：economy-api/economy/app `-DskipTests compile` 均 rc=0。

### 2026-10-21 B4/L4

- 状态：**已完成 main 编译绿**（未 commit/push，未跑测试）。
- Map 新增 `LandComponentOperations.setArableOwners` 与 `map.SeedArableOwnership` GM-only 命令。
- Economy 新增瞬态 `LandComponentLedger`；`EconomyData.landHoldings` 持久组件删除；
  `LandHolding`/`LandHoldingKey`/`LandBook`/`LandLedgerState` 降为瞬态聚合/纯操作。
- app 两时间参与者从 Map 构造 ledger；step 后把最终 holdings 写回 Map component owner/quantity，
  `MapChangeSet` 与 economy/social/actor/production 同一 revision。
- 种子链：`EconomySeeder.Seed.landOwnershipSeedPayload()`；
  SmallWorld/ThreePowersWorld/WorldgenInitializeTool/RegionSeedTool 加入 seed 命令。
- 迁移 `MapCodec` 旧地形快照时保留/补空 `HexLand`（T4 发现的 blocker）。
- 编译：map/economy-api/economy/app `-DskipTests compile` 均 rc=0。

### 2026-10-21 T4 测试迁移与首轮复验

- 状态：**测试迁移完成，核心复验绿。**
- 迁移 map/economy/app 测试与 `Rural32HexWorld` 夹具到三档土地/Map owner 权威。
- `test-compile` rc=0。
- 全仓 surefire（当前报告汇总）：**3671 tests / 0 failures / 0 errors / 7 skipped**。
- 前端门禁：**412/412**。
- `tools/mvn-lock.sh -q -DskipTests verify -pl simos-app -am`：**rc=0**（Spotless/Checkstyle/SpotBugs/前端/shaded guard）。
- 32hex 360：**rc=0，OK=360 / REJECTED=0**；`landTotalMilliMu` 恒 99,200,000；
  土地市场有成交，`landDebtTransferredMilliMu=0`、`wageArrearsMilli=0`；
  无 `land-writeback`/`land-owner-missing`/`land-ledger-invalid` 日志。
- 变异自证：merge、缺 owner、seed 总量守恒、Map 写回 4 项均做过改坏⇒红⇒还原⇒md5 一致。
- `tools/gate.sh full` 未完整跑完：工具 300s 上限在 app SpotBugs 阶段中断；
  它包含的全仓测试、前端门禁、`verify -DskipTests` 已分开跑绿，**不把分段绿写成 full gate 已过**。
- 未做：B5 progress/crop/fertility 的生产语义；未 commit/push。

---

## 12. 修订记录

- v1（2026-10-21）：用户选定方案 A（owner 写 Map 组件属性）；冻结
  `HexLand → LandTypeArea → LandComponent(quantity, attributes)`；冻结合并规则
  “同类型下除 id/quantity 外属性全同 ⇒ 合并，保留最小 id”；规划 B1–B5；
  明确 Economy 的土地双组件最终删除，`LandHolding` 降为瞬态聚合视图；
  当前无需要用户追加裁定的语义点。**未改代码。**
