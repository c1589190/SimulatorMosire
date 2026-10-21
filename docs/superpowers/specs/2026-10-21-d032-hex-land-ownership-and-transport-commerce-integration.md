# D-032 新版单 hex 城区/耕地标量、土地所有权与运输商业接入提案（2026-10-21）

> **状态：设计提案，不是实现验收，也不是用户已逐条确认的冻结约束。**
> 本文把用户 2026-10-21 的新版城市/耕地/运输商业设计意图，嵌进 D-032 父目标运行序列
> `docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` 的 S0–S8；
> 对旧城市/土地/运输设计的取用只写“可复用 / 已过时 / 需改造”，不把旧文档重新冒充成当前设计。
>
> **父设计锚点：** v2 §1（S0–S8）、§4.1（标量 LAND）、§5（统一市场）、§8.10（简单运力）。
> **本文性质：** 只写增量；用户原话仍是最高权威，控制方建议可被用户推翻。
>
> **局部取代（2026-10-21）：** 本文 §6–§9（运输/商业接入）已由
> `docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md` 取代；
> §1–§5 的城区/耕地/所有权部分仍作历史设计参考。冲突时以新设计为准。

---

## 0. 一句话结论

1. **Map 模块已经有正确的落点**：`HexCell.areas` 是一张逐格属性表，内置 `urban` / `rural` 两项，
   其中 `urban` 默认 0、`rural` 默认按地形算出可耕地；已有 `map.SetHexArea` 命令和 GM 窄工具。
   现在缺的不是“再造一套城市数据”，而是把 `rural` 正名为 `arable`（耕地），并让生产方式/经济真正读它。
2. **Map 只拥有“这一格有多少城区、多少耕地”两个标量；家户所有权归 Economy。**
   Economy 继续保存 `LandHolding(owner, hex, quantityMilliMu)` 的绝对数量；
   `Σ holdings(hex) == arableMilliMu(hex)`；家户所有权比例是 app 层读出来的派生量，不另存第二份比例权威。
3. **当前 360 运行期仍是旧状态**：`ProductionRuntimeSeeder` 用 `landMilliMuOf(terrain)` 重新算地，
   `EconomyData` 持久保存 `landArableSupply`，Map 的 `areas` 对生产零调用者。
   所以本次接入的核心是**砍掉 Economy 的 arable 权威、改由 Map 提供**，不是改地块算法本身。
4. **运输先按当前市场算法接“运力 + 运费”，不要先做独立运输服务订单簿。**
   当前 360 已有 `MerchantCapacityPool`、`CARRIER_FEE`、`ShipmentBatch` 和
   `haul` 服务 token 的 RC8a 双分支；在统一市场里直接再加一个 `haul` 商品撮合会形成“货单依赖运力、运力订单又依赖货单”的循环。
5. **商业（买低卖高）用普通买卖订单 + 库存 + 在途，不另开商户市场。**
   先决条件是世界里至少有多个市场区/价差；当前 32hex 是单市场区，商户套利没有可验证的价差，
   不能把“机制在、世界不触发”误当成实现失败。

---

## 1. 用户原话（2026-10-21，逐字）

> 我来统一一下新版的城市设计，首先，对于单个hex，没有特殊的城市概念，单个hex维护一个城区标量数据、耕地标量数据，两者都是自由变量，未来可以用机制或者GM工具修改，交给Map模块出接口维护；
> 然后是生产方式中的耕地标量数据，直接取决于前者的耕地标量数据，也就是说，你在app层访问这个耕地标量数据，理论上是可以拿到什么什么家户对于这个hex耕地的拥有比例的；
> 城区目前不设自由增长机制，只在世界初始化时给固定值，也就是所有地块都为0——目前不实现城市相关机制！只留接口
> 这里需要重点再设计的，很显然是耕地（Map模块单hex数值）的家户所有权（家户-生产方式模块对前者的对接），我需要你首先给出一个靠谱的接入方式和数据结构
> 然后是运输商业，我需要你把之前的设计翻出来，然后告诉我你认为结合现在的市场算法，可以如何接入
> 此外我压缩了你的上下文，必要的工作状态恢复记得做好

### 1.1 由原话直接确定的语义

| # | 原话 | 本提案的落点 |
|---|---|---|
| U1 | 单个 hex 没有特殊城市概念 | 不新增“城市点/城市对象”作为地块权威；`City` 继续只是地图上的稳定实体，不承载地块数量 |
| U2 | 单个 hex 维护城区标量、耕地标量 | `HexCell.areas[urban]`、`HexCell.areas[arable]`；均以毫亩为整数 |
| U3 | 两者都是自由变量，未来可以由机制/GM 修改 | Map 只做通用标量读写接口；现在不自动写、不自动增长 |
| U4 | 生产方式中的耕地标量取决于 Map 的耕地标量 | Map 是总耕地唯一权威；Economy 不再持久保存第二份 `landArableSupply` |
| U5 | app 层可拿到某家户对该 hex 耕地的拥有比例 | app 组合 `Map.arable + Economy.landHoldings` 生成只读 `HexLandOwnershipView` |
| U6 | 城区只在创世给固定值，目前所有地块为 0 | `urban` 读侧默认 0；创世不写非 0；不实现城区增长机制 |
| U7 | 目前不实现城市相关机制，只留接口 | 不改 `City`/城市化迁移/城区容量；只保留 Map 读写接口和读口 |
| U8 | 运输商业要结合现在的市场算法 | 不另造市场；运输走现有 `MarketRound`/运力池/路线，商业走普通订单 |

---

## 2. 目标运行序列（本提案嵌在父 S0–S8 的位置）

> 只写本增量，不重抄父设计全部阶段。

| 父阶段 | 本增量做什么 | 读 | 写 | 失败/边界 |
|---|---|---|---|---|
| S1 经济载入 | app 从 Map 取逐格 `urban/arable`，构造**瞬态** `ArableSupplySnapshot`；Economy 载入 `landHoldings` | `GameMap.hexes`、`LandHolding` | 会话内瞬态 supply 快照 | Map 缺格、负值、持有合计 != Map arable ⇒ 本次 advance fail-closed |
| S2 进入/迁移/组织 | 家户土地所有权仍只改 `LandHolding`；Map 不作为写入口 | `landHoldings`、Map 快照 | `landHoldings` | 所有权转移仍只走 `LandBook`/`LandLedgerState` |
| S3 生产位置选择 | D4/production 的 LAND 投影由 `landHoldings` + Map arable 快照生成；比例只作读口 | holdings、Map arable | 瞬态 D4 allocation | 本轮新取得土地仍按既有 `landAcquiredThisRound` 排除 |
| S4 生产日结算 | 生产仍只收 `Map<AssetKind, Long>`；不再从 Economy 的 arable 组件取总量 | D4 allocation | 库存/日效率/账单 | 土地不足 ⇒ 既有具名 `asset-short:LAND`，不伪造 |
| S5 收获/毛产分配 | 按实际参与土地份额分给 owner；owner 的绝对持有量来自 `LandHolding` | holdings、实际播种 | 毛产/库存 | 未种地不参与本周期 `u/a` |
| S7 市场/债务 | LAND 订单、债务抵地仍走现有 `LandBook`；`LandBook` 的总量输入改为 app 传入的 Map 快照 | Map 快照、holdings | holdings、市场/债务 | 0 价合法；本轮新取得下一生产 tick 才可用 |
| S8 Social 事实回传 | 不变 | S4 实际温饱 | Social 事实槽 | 不用市场新买库存回头补本 tick |
| app 读口 | `ApiViews.economyHex`/专用 land 读口组合城区、耕地、持有、比例 | Map + Economy | 无（只读） | 无 Map 快照 ⇒ 具名 `unavailable`，不填 0 冒充 |

### 2.1 单 tick 读写顺序（land 子链）

```text
S1
  MapSnapshot.hexes -> resolve(urban, arable) -> ArableSupplySnapshot
  EconomyData.landHoldings -> holdings
  validate: 每个 holding.hex 在 Map 中；每个 hex 的 Σ holdings == Map.arable
S2
  entry/migration/organization 只改 holdings；Map 不写
S3/S4/S5
  Economy 用同一份 ArableSupplySnapshot 给 D4/production 生成 LAND 投影
S7
  LandBook 以同一份快照校验/转移 holdings
  市场轮结束后 holdings 写回 EconomyData 工作副本
app
  Map.urban/arable + holdings -> ownership-per-mille 视图
```

**禁止路径：**

- 不得让 Economy 再持久保存一份可坑位漂移的 `landArableSupply`；
- 不得让 production 直接 import `HouseholdId`/`LandHolding`，生产方式仍只收 `Map<AssetKind,Long>`；
- 不得让 Map 反向 import Economy/Social，Map 只出标量和通用写接口；
- 不得在校验失败时静默补 0 / 截断持有 / 按比例重分配；必须具名 fail-closed。

---

## 3. Map 模块：城区/耕地标量的数据结构

### 3.1 现有落点（实测）

- `HexCell` 已经是 `record HexCell(double height, Map<HexAreaKey, Long> areas)`：
  `simos-map/src/main/java/io/mosire/simos/map/HexCell.java:30`。
- `areas` 是**显式值表**：键不在表里 = 走地形默认；负值构造期拒绝；
  `HexCell.java:40-56`。
- 内置键当前是 `URBAN("urban")`、`RURAL("rural")`：
  `simos-map/src/main/java/io/mosire/simos/map/HexAreaKey.java:51-53`。
- `HexAreas.resolved` 先读显式值，否则 `defaultOf`：
  `simos-map/src/main/java/io/mosire/simos/map/HexAreas.java:47-52`；
  `defaultOf` 中 `URBAN -> 0`、`RURAL -> 地形默认可耕地`：
  `HexAreas.java:61-70`。
- 创世 `HexAreas.initialCell` 只写 `RURAL` 初值，不写 urban：
  `HexAreas.java:132-139`。
- 写口 `map.SetHexArea` 已存在（一次一格一个字段、`set|add`、GM-only）：
  `simos-map/src/main/java/io/mosire/simos/map/spi/SetHexAreaHandler.java:20-49`；
  `simos-map/src/main/java/io/mosire/simos/map/tools/write/MapSetHexAreaTool.java:27-54`。
- `MapChangeSet`/`MapCodec` 通过 `FieldDelta<HexCell>` 已覆盖 `areas`：
  `simos-map/src/main/java/io/mosire/simos/map/change/MapChangeSet.java:36-42,84-105`。

### 3.2 本提案建议的规范形状

```text
Map 模块（唯一权威）
  HexCell.areas:
    urbanMilliMu  // 城市/建成区面积，毫亩，>= 0，缺省 0
    arableMilliMu // 可耕地面积，毫亩，>= 0，缺省按地形可耕系数
    自定义字段（后续扩展，不进入生产方式默认读口）
```

建议把 `RURAL("rural")` **正名为** `ARABLE("arable")`：

- 现有 `rural` 的实际语义来自 `TerrainCatalog.defaultUsableAreaMilliMu`，就是可耕地；
- `rural` 容易和 `ResidenceKind.RURAL`（农村居住）混淆；
- 用户最新用词是“耕地”；Alpha 阶段按既有裁定**不做旧档兼容**，旧 wire key 直接删，不保留别名；
- 如果暂时不想改 wire key，最小实现可以保留 `rural` 但必须把类注/读口明确成“可耕地”，不能继续叫乡村面积。

建议增加一个**只读组合值类型**（不新增持久组件）：

```java
public record HexLandScalars(HexCoord hex, long urbanMilliMu, long arableMilliMu) {}
```

放在 Map 模块；由 `HexAreas` 提供唯一构造/读取口，例如：

```java
HexLandScalars s = HexAreas.landScalars(hex, cell, terrain);
```

这样 app 不再手写 `areas.get(URBAN) == null ? 0 : ...` 之类的兜底。

### 3.3 不变式

| # | 不变式 |
|---|---|
| M1 | `urbanMilliMu >= 0`、`arableMilliMu >= 0` |
| M2 | 两张标量都由 Map 管；Economy/Social 不写 `HexCell.areas` |
| M3 | 现在不实现城区自动增长；`urban` 创世默认 0，只有 GM/未来机制显式写才变 |
| M4 | `urban` 与 `arable` 是**相互独立的标量**；本提案**不**计算 `arable - urban` 或比例；旧 `CityLand.availableArableMu` 是历史模型，不接当前运行期 |
| M5 | 读口必须区分“显式 0”和“缺省 0/地形默认”；缺省语义仍是 0/地形默认，不是旧档兼容位 |
| M6 | 所有写仍走 `Command -> ChangeSet -> Revision`；`map.SetHexArea` 是唯一通用写口 |

### 3.4 arable 的运行时编辑规则

Map 模块本身不知道 Economy 的 holdings，因此：

- 创世：由 Map 的 arable 值决定 seed 的总量；
- 运行期：**不要直接用裸 `map.SetHexArea(key=arable)` 改变一个已存在世界的总量**，
  否则下一 tick 会出现 `Σ holdings != Map.arable`；
- 未来 GM/机制改 arable 时，应走 app 层组合命令：
  1. `map.SetHexArea` 改 Map 总量；
  2. 同一个 revision/批中按明确规则调整 `landHoldings`（或拒绝减少到低于现有持有合计）；
  3. 任一步失败 ⇒ 整批拒绝，零半提交。
- 本提案不实现这个组合命令，只把“单写 Map 会 fail-closed”写成契约；
  当前世界没有城区机制，也暂不需要运行时改 arable。

---

## 4. Economy/Social：家户土地所有权的数据结构

### 4.1 继续用绝对数量，不把比例存成第二权威

现有 `LandHolding` 已经符合用户此前原话“`0,0` 有 X 亩、`0,1` 有 Y 亩”：

```text
LandHolding {
  owner: HouseholdId
  hex: HexCoord
  quantityMilliMu: long   // > 0, 无 0 行
}
```

代码：`simos-economy-api/src/main/java/io/mosire/simos/economy/api/land/LandHolding.java:13-37`。
`LandHoldingKey = (hex, owner)`，自然序先 hex 再 owner：
同目录 `LandHoldingKey.java`。

**建议保留绝对数量，不新增 `sharePermille` 持久字段。** 理由：

1. 市场成交、债务抵地、任意数量转移都需要精确整数数量；
2. `quantityMilliMu` 已经是用户原话“X 亩/Y 亩”的直接表达；
3. 比例是总量变化时的派生读法；把比例另存一份会变成 second authority，且整数比例会引入舍入；
4. 当前 v2 已经冻结 `Σ holdings == arable`，归属比例可以作为只读视图计算。

### 4.2 去掉 Economy 的 `landArableSupply` 持久组件

当前问题（实测）：

- `EconomyData` 仍持久保存 `LandArableSupply landArableSupply`：
  `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:402`；
- `LandProductionProjection.project(...)` 收 `LandArableSupply`：
  `simos-economy/src/main/java/io/mosire/simos/economy/land/LandProductionProjection.java:98-115`；
- `ProductionRuntimeSeeder` 用 `landMilliMuOf(terrainOf.apply(hex))` 重新从地形算，并写 entry：
  `simos-app/src/main/java/io/mosire/simos/app/world/ProductionRuntimeSeeder.java:153,548`；
- `RuralLaborSeeder.landMilliMuOf(terrain)` 是地形公式：
  `simos-app/src/main/java/io/mosire/simos/app/world/RuralLaborSeeder.java:137-138`；
- Map 的 `areas` 反而没有生产调用者（`HexAreas` 只在 Map 读口/世界生成器使用）。

目标：

```text
持久 EconomyData:
  landHoldings: Map<LandHoldingKey, LandHolding>   // 仍唯一持久 LAND 所有权权威

瞬态（每 tick 由 app 从 Map 构造，不落盘）:
  ArableSupplySnapshot {
    Map<HexCoord, Long> arableMilliMuByHex
  }

Economy/生产:
  LandBook.from(snapshot, holdings)
  -> 每个 hex 的 Σ holdings == snapshot.arable
```

`LandArableSupply` 可以：

- 方案 A（推荐）：删除持久组件，保留/重命名 value 类型为瞬态 `ArableSupplySnapshot`；
- 方案 B：保留类名，但只作为 app 构造的瞬态快照，不再进 `EconomyData` Component/ChangeSet/Codec。

无论哪种，**EconomyData 中不能再有 arable 总量字段**；否则 Map arable 和 Economy arable 会继续双权威。

★ 特别禁止把持有合计反推成“总量权威”。当前 `MarketRoundRunner.arableSupplyOf(holdings)`
只是旧重载/夹具的派生入口（`MarketRoundRunner.java:956`）；
生产路径必须由 app 传入 Map 快照，不能在缺快照时用 holdings 总和冒充 Map arable。

### 4.3 `LandBook` 的 invariant

现有 `LandBook` 已经守：

- `(hex, owner)` 唯一；
- 无 0 行、无负数、未知 hex 拒绝；
- 每 hex `Σ holdings == arableSupply`：
  见 `simos-economy/src/main/java/io/mosire/simos/economy/land/LandBook.java` 的类注与 `validated(...)`。

接入 Map 后仍保持**严格相等**：

```text
for each hex in Map:
  Σ holdings(hex) == Map.arableMilliMu(hex)
```

理由：用户说的是“拥有比例”，默认所有可耕地都有归属；若允许未分配，必须另行定义
“无主耕地”的产出归属（本提案不做，避免偷偷出现第三本账）。

### 4.4 app 层所有权视图

app 不新增持久组件，只组合两张已有表：

```java
record HouseholdLandShareView(
    HouseholdId owner,
    long quantityMilliMu,   // exact
    long sharePerMille      // derived view; largest-remainder if we want Σ==1000
)

record HexLandOwnershipView(
    HexCoord hex,
    long urbanMilliMu,
    long arableMilliMu,
    long ownedMilliMu,
    long unownedMilliMu,    // 本提案严格模型下恒 0；用于诊断
    List<HouseholdLandShareView> holders
)
```

构造：

```text
arable = HexAreas.landScalars(hex, cell, terrain).arableMilliMu()
holders = EconomyData.landHoldings() where hex == this hex
quantity = holding.quantityMilliMu()
sharePerMille = floor(quantity * 1000 / arable)  // arable==0 => 0
```

如果显示要给“比例总和恰好 1000”，用**最大余数法**按 `owner.value()` 稳定破平；
这只是显示规则，不能反写 `LandHolding`。

推荐落点：

- GUI/MCP 共用 `ApiViews.economyHex(coord, state)`，它已有 `SimulationState`，可同时读 MapSnapshot；
- 在现有 `landMilliMu`/`landHoldings` 栏旁加：
  - `arableMilliMu`（来自 Map）
  - `urbanMilliMu`（来自 Map）
  - `landOwnership`（持有 + sharePerMille）
- 不要在 `simos-map` 的 `MapHexTool` 里读 Economy；Map 工具只能显示 Map 自己的 `areas`。
  家户所有权是 app 组合读口，不是 Map 权威。

### 4.5 生产方式如何直接依赖 Map 的耕地总量

生产方式仍只收：

```text
Map<AssetKind, Long> assetAllocation  // D4 预算的 LAND 项
```

但它的来源链改成：

```text
Map.arableMilliMu(hex)
  -> app 构造 ArableSupplySnapshot
  -> Economy 以 snapshot + landHoldings 校验
  -> SettlementIndex/LandProductionProjection 合成瞬态 LAND 份额给 D4
  -> production 只收 Map<AssetKind, Long>
```

这样：

- 生产方式不需要知道 Map/Social 的所有权细节；
- 生产方式拿到的 LAND 总量最终由 Map 的 arable 决定；
- app 层可以另外读 `landHoldings` 得到“谁拥有这个 hex 的比例”；
- 没有第二份 arable 持久字段。

---

## 5. 与旧城市设计的口径关系

### 5.1 2026-10-05“城区占地比例制”

旧文件：`docs/superpowers/specs/2026-10-05-city-merchant-urbanization.md` §C0。

旧口径：

```text
cityBuiltMu = 基座 + 城市人口 × 人均城区 + 工坊 × 每坊 + 商人区
availableArableMu = max(0, arableMu − cityBuiltMu)
buildingRatio = cityBuiltMu / hexAreaMu
```

**与 2026-10-21 用户原话的关系：**

- 用户现在说城区标量与耕地标量“两者都是自由变量”，且生产方式“直接取决于前者的耕地标量数据”；
- 因此当前运行期**不要**再做 `availableArable = arable - urban` 的自动扣减；
- 旧 C0 的 `CityLand`/`CityLandBook` 仍是零生产调用者的纯模型，保留为历史；
- 若未来要接“城区占耕地”，那应由未来机制**显式修改 arable 标量**，而不是在每次读生产时自动相减。

### 5.2 2026-10-10“城区独立池”

旧文件：`docs/superpowers/specs/2026-10-10-urbanization-and-industry-pool-design.md`。

旧口径：城区不是比例，是独立容量池；Q1–Q11 当时未裁定、未实现。

**当前处置：**

- 本提案不实现城区独立池；
- 用户 2026-10-21 明确“目前不实现城市相关机制，只留接口”；
- `urban` 只是 Map 上的一个自由标量，当前创世为 0；
- 不把 `urban` 接到工坊容量、人口迁移或城区加成上。

### 5.3 `City` 实体

`City` 仍是 `(id, name, at, region, props)`：
`simos-map/src/main/java/io/mosire/simos/map/City.java`。
它不承载人口/耕地/城区面积。当前 `PlannedCity.population` 只是 seed 输入；
实际人口仍在 Social 的批次/家户。本提案不改这一点。

---

## 6. 运输商业：旧设计翻查与当前算法对照

### 6.1 已翻到的旧设计

| 文件 | 关键思想 | 当前可用性 |
|---|---|---|
| `docs/superpowers/specs/2026-10-05-diversified-transport-handicraft.md` | 运输队闭环、运力收入、跨格贸易 | 历史方向；当年“独立运输队组织”未成为当前状态 |
| `docs/superpowers/specs/2026-10-09-commodity-freight-and-merchant-ladder-design.md` | 运费商品表、商号=家户、运力=劳动、买低卖高、商户阶梯 | 运费表/商户家户/运力派生已被当前代码吸收；“商号行”已退役 |
| `docs/superpowers/specs/2026-10-10-haul-service-commodity-design.md` | 运输服务=`haul` 商品，需求由其他商品购买派生 | 当前代码保留 `haul` 服务 token，但**不把 haul 挂进普通商品订单簿**；服务成市格走运力 token 分支 |
| `docs/superpowers/specs/2026-10-06-seven-hex-full-chain-merchant-migration.md` | 商人阶层、真实利润、加权迁移 | `MerchantFirm` 持久组件后来已退役；商人身份回到“家户 + 生产方式” |
| `docs/superpowers/specs/2026-10-06-market-regions-and-agglomeration-redesign.md` | 市场区/聚集节点/路线 | 持久市场区与 `MarketTopology` 已存在；当前 32hex 仍是单区 |
| `docs/superpowers/specs/2026-10-18-d032-p2-unified-market-debt-constraint.md` | 商品+土地同一订单簿、区级地价 | 当前统一市场的基础；标量 LAND 后又经 RA1b2 改造 |

### 6.2 当前市场/运输代码事实

- 统一市场入口只有一个：`MarketMatcher.matchAll` 内依次
  `matchWithinRegions` → `matchAcrossRegions` → `matchLand`：
  `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketMatcher.java:60-65`。
- 区内跨 hex / 跨区货物在 `MarketPairingBook.pairUp` 里合成 `intraRegionFreightRoute`
  或 `MarketRouteMatcher.matchRoute`；真正落账时 `MarketTradeExecutor.executeTrade`
  先选承运、再落货腿/钱腿/运费腿：
  `MarketPairingBook.java:162-251`，`MarketTradeExecutor.java:70-240`。
- 运力池 `MerchantCapacityPool` 是逐轮派生，不落状态；运力来自：
  - 非服务成市格：`MerchantCapacity.of(labor, tool)` = 劳动 + 工具；
  - 服务成市格：`MerchantCapacity.ofService(serviceAvailable)` = 可交付 `haul` 货库存：
    `MerchantCapacityCore.java:96-143`。
- `haulServiceHexes` 现在要求“有 haul 牌价 ∧ 该 hex 至少一户有可交付 haul 货”：
  `ShipmentSettlement.java:231-252`。
- 普通商品意向生成器 **明确跳过** `haul`：
  `MarketIntentBook.java:241-244`：
  “运输服务不进账单商品订单簿…成市格的服务需求由其他商品购买派生，供给由运力池在跨格成交那一刻现卖；
  挂成普通买/卖会与冻结/交付腿冲突（RC8a 边界）。”
- 服务成市时，运费只走服务商品成交的 `MARKET_TRADE` 钱腿；不成市时走 `CARRIER_FEE`：
  `MarketTradeExecutor.java:389-415`。
- 跨区交易需要拓扑的相邻区；当前 32hex 世界是单市场区/单 Region，`matchAcrossRegions` 没有可用的区对；
  因而当前 360 没有真正的跨区商户贸易。

### 6.3 两个必须承认的硬约束

1. **当前统一市场没有“商户专用市场”。** 若再开一个 `MerchantSettlement` 或独立服务市场，
   会违反“商品+土地同一订单簿/同一撮合入口”的父设计；也会让同一批钱货在两条路径上重复结算。
2. **运输服务订单与货物订单有循环依赖。**
   运输需求由“需要跨格运的货单”派生，而货物成交又需要运力；
   若两者在同一轮各自作为普通订单先挂后撮合，无法在不定义“先买运力还是先成交货物”的情况下保持原子性。
   当前 RC8a 已把这个矛盾显式化成两条互斥腿：
   - 有可交付 `haul` 货 ⇒ 服务 token 腿；
   - 没有可交付 `haul` 货 ⇒ 生产性运力 + `CARRIER_FEE` 腿。
   不能在两者之间再加“劳动+工具造服务容量”的第三态。

---

## 7. 推荐接入方式

### 7.1 运输：先接“运力 + 运费”，不要把 `haul` 变成第二订单簿

**推荐方案 A（当前市场算法的最小改动）**

```text
商户家户选择 trade/merchant 生产方式
  -> 生产轮产出 labor/tool 可投入量（或产出 haul 服务货）
  -> S7 市场轮装配 MerchantCapacityPool（瞬态）
  -> 货物跨格/跨区撮合时，executeTrade 按 (from,to,commodity) 现选承运
  -> 运费进入买方现金腿，CARRIER_FEE/服务 token 收款给承运家户
  -> 服务成市格只用可交付 haul 货；非服务成市格只用劳动+工具
```

这已经是当前代码的主链，不需要新增：

- `MerchantFirm` 持久组件（已退役，不要复活）；
- `MerchantProfitBook` 平行账（已退役，收益走标准 ledger/enterprise）；
- 第二个 `MerchantSettlement` 或 `TransportMarket`。

需要补的只是：

1. 运输容量/路线读数与 `MerchantCapacityPool` 的日志/验收；
2. `haul` service token 只在真的有可交付货时成市（RC8a 已完成）；
3. 跨区拓扑要真的有多个区/邻接路线，否则 32hex 的 `matchAcrossRegions` 永远无事可做；
4. 服务 token 与 `CARRIER_FEE` 两条腿保持互斥，绝不同时收费。

**数据形状（瞬态，不落状态）**

```text
TransportCapacityProvider {
  household: HouseholdId
  homeHex: HexCoord
  capacityMilli: long
  askPerMille: long
  kind: LABOR_TOOL | HAUL_SERVICE_STOCK
}

RouteQuote {
  fromHex, toHex: HexCoord
  fromZone, toZone: MarketZoneId
  commodity: CommodityId
  travelTicks: long
  freightPerUnitMilli: long
  carrierChoices: List<CarrierChoice>
  serviceToken: boolean
}
```

- `TransportCapacityProvider` 对应现有 `MerchantCapacity`；
- `RouteQuote` 对应现有 `RouteContext` + `CarrierAllocation`；
- 两者都不需要 Codec/ChangeSet/持久组件。

### 7.2 如果一定要把 haul 做成“真正挂单的商品”

只有在用户明确要求“运输服务必须像粮/布一样在订单簿挂买卖单”时才做，且必须选下列之一：

**方案 B1：承运承诺前置（推荐给真正服务市场）**

```text
S7 装配：
  ① 从全部货单估算各 lane 的运输需求（数量、from/to、commodity）
  ② 商户按运力上限提交 haul SELL；买方/货主提交 haul BUY
  ③ 先在同一 MarketRound 内匹配 haul 服务，产出 LaneCapacityCommitment
  ④ 货物撮合只使用已承诺的 lane 容量；无承诺的跨格货单不成交（NO_CARRIER 具名）
  ⑤ 成交时消耗 haul 服务 token，服务钱腿只走 MARKET_TRADE；不再铸 CARRIER_FEE
```

这是**同一市场轮内的服务前置子阶段**，不是第二个市场；但会改变 S7 内部顺序和预算/冻结，
必须另写约束设计书，不能直接塞进当前 `MarketIntentBook`。

**方案 B2：两阶段市场**

服务市场先结算，商品市场后结算。这会把当前 S7 拆成两个 revision 或两个市场日，
与“一次市场轮只开一次统一订单簿”冲突；不推荐。

**本提案不选 B1/B2 作为第一批。** 先把 A 的运力/运费链跑通，等真有跨区价格和商户需求，
再决定是否值得为服务订单簿付出上述顺序改造。

### 7.3 商业：普通买卖单 + 库存 + 在途，商户只写在 app/economy 的瞬态意向里

商户不是独立 Actor 类型，仍是家户 + 生产方式/位置：

```text
MerchantHousehold:
  buys commodity in market A (low price)
  holds inventory / ships to market B
  sells commodity in market B (higher price)
  profit = sale revenue − purchase cost − freight − labor/tool cost − debt interest
```

**推荐接入点：**

1. **订单生成**：继续用 `MarketIntentBook.dailyDemandBillMarketIntents` / `MarketIntent`。
   商户的“为转售而买”作为一张独立的瞬态意向，来源标 `MERCHANT_RESALE`，
   不混入 `DailyDemandBill` 的基础温饱/生产投入行。
2. **撮合**：仍走 `MarketRoundRunner.clearOncePerCycle` / `MarketMatcher.matchAll`；
   不新增 matcher。商户买到的货按买方地址进在途/库存；到货后下一次市场日再正常卖。
3. **出售**：到货后可走普通 `SELL` 意向；若初始阶段不想保存商户成本基差，
   就按当前实际库存和市价生成卖单，盈利/亏损由真实成交决定。
4. **融资/债务**：买货现金不足仍走现有“未付成交款成普通货币债”；没有新额度/抵押机制。
5. **运力**：商户自己的运力来自 `trade/merchant` 生产方式；
   自运与他运都仍走 `MerchantCapacityPool` + `CARRIER_FEE`（或未来服务 token）。

**最小数据结构（瞬态，不落盘）**

```text
MerchantResaleIntent {
  household: HouseholdId
  commodity: CommodityId
  buyLocation: HexCoord            // 商户当前市场/落点
  sellLocation: HexCoord
  quantityMilli: long
  expectedBuyPriceMilli: long
  expectedSellPriceMilli: long
  expectedFreightMilli: long
  expectedNetMilli: long
  source: MERCHANT_RESALE
}
```

- 不新增 `merchantFirms` / `MerchantProfitBook` / 商户持久表；
- `expectedNetMilli = expectedSell − expectedBuy − expectedFreight − labor/tool/debt`；
- 只有 `expectedNetMilli > 0` 才生成买意向；
- 价格/运费全部来自当轮 `MarketTable`/`MarketTopology`/`MerchantCapacity`；
- 同收益按 `commodity.value()`、`buyLocation`、`household.value()` 稳定破平。

**必须补的市场前提：**

- 当前 32hex 是单市场区，所有 hex 共用一张价表，**没有跨区价差**；
  商户买低卖高在这个世界结构上不可能成立。
- 要验证商业，至少需要一个两市场区的世界（或测试夹具），且两区有真实供需差/价格差；
  不能通过伪造价格或写死价差来“让机制发生”。
- 如果商户在本地市场买货时本地供给会先成交、导致它买不到低价进口货，
  再评估是否给买盘加一个瞬态 `IMPORT_ONLY` 范围（仅跨区撮合）：
  - 仍进入同一 `MarketRound`、同一预算/冻结、同一 `executeTrade`；
  - 只是在区内撮合时跳过这类买盘，在跨区撮合时参与；
  - 这会改 buy slot 的匹配域，属于较大的订单变体改造，建议放到商业第二批。

### 7.4 运输商业的验收锚点（以后实现时）

- 没有可交付 `haul` 货的世界 ⇒ `CARRIER_FEE` 路径；有可交付 `haul` 货 ⇒ 服务 token 路径；
  两条腿绝不同时收费；`HAUL_SERVICE_DELIVERY_FAULT = 0`。
- 跨格/跨区货单能产生 `RouteUsage`/`ShipmentBatch`/承运收入；
  运力不够 → 具名 `LOGISTICS_CAPACITY`，不成交、不成债。
- 商户买低卖高至少出现一次真实成交，身份可核（买方/卖方/承运方/价格/运费/债）；
- 商户不凭空生成货币/货物；买卖差价 = 收入 − 成本（含运费、劳动/工具、利息）；
- 单市场区世界允许商户数为 0 / 跨区成交为 0，不能判失败；这不是“机制坏了”。

---

## 8. 失败语义与不变量

| 场景 | 处理 |
|---|---|
| Map 缺某 hex 的 arable / 负值 | `map.SetHexArea` 拒绝；advance fail-closed |
| holdings 有 Map 未知 hex | `LandBook` `LAND_UNKNOWN_HEX` |
| `Σ holdings != Map.arable` | 构造/校验具名拒绝，不按比例修补、不填 0 |
| arable=0 但 holdings>0 | 拒绝 |
| arable=0 且 holdings=0 | 合法空土地格 |
| 读口缺 Map 快照 | 具名 `arableSupplyUnavailable`，不显示 0 |
| 运输服务成市但无可交付 haul 货 | 走 CARRIER_FEE；不得用 labor+tool 冒充服务货 |
| 服务 token 交付时无货 | `HAUL_SERVICE_DELIVERY_FAULT` fail-closed；该笔不成交 |
| 无运力 | 该笔不成交、不铸运费；`LOGISTICS_CAPACITY` |
| 商户套利无价差 | 不生成商户买盘；不伪造价差 |
| 同一笔成交同时铸 CARRIER_FEE 与服务钱腿 | 契约故障；两条腿互斥 |

---

## 9. 建议实施顺序（仅设计，不是派单）

### L1：Map 标量接生产（最小、可独立验证）

1. `RURAL -> ARABLE` 正名（或先保留 wire key，仅改语义/读口）；
2. 增加 `HexAreas.landScalars(...)` / `arableMilliMu(...)` 只读口；
3. `ProductionRuntimeSeeder`/`EconomySeeder` 的 `landMilliMuOf(terrain)` 改为读 Map arable；
4. app 构造瞬态 `ArableSupplySnapshot`；
5. `EconomyData` 移除持久 `landArableSupply`；`LandBook` 改收瞬态快照；
6. `ApiViews.economyHex` 增加 `urban`/`arable`/`landOwnership` 只读栏。

验收：32hex 每个 hex 的 Map arable == 当前 seed 的 3,100,000 毫亩；holdings Σ 相等；
360 重跑现有数值不变（纯切源，不改公式）。

### L2：运输/商业的现状收口

1. 保持 RC8a 的 `haul` 双分支互斥；
2. 给 `MerchantCapacityPool` 加逐日/逐 lane 读数（已有大量日志，不重造）；
3. 世界构造至少一个多市场区夹具（不是改 32hex 单区）用于跨区路线验证；
4. 不改价格公式、不改市场节奏。

### L3：商户转售意向（可选，等 L2 有价差）

1. 增加瞬态 `MERCHANT_RESALE` 买意向；
2. 仍走普通 `MarketIntent`/`MarketMatcher`/`executeTrade`；
3. 到货后普通卖单；
4. 不做 `merchantFirms`、不做第二本利润账。

### L4：真正的 haul 服务订单簿（仅在用户明确要求时）

按 §7.2 B1 的“承运承诺前置”另写约束设计书；不得从当前 RC8a 直接插一个普通 haul 买单。

---

## 10. 需要用户确认的两个语义点

> 以下不是重复旧裁定，而是 2026-10-21 新设计尚未明说的两点；控制方建议已给出。

1. **所有权存绝对数量还是比例？**
   - 建议：存绝对 `quantityMilliMu`，比例只作 app 读口；`Σ holdings == Map.arable`。
   - 若你希望 Map arable 可任意改而不调整 holdings，则要改成“比例权威”；但那会让市场任意数量交易出现舍入问题，需另立规则。

2. **arable 运行期改动是否必须同步 holdings？**
   - 建议：必须。裸改 Map arable 会让下一 tick fail-closed；未来用 app 组合命令原子完成“改总量 + 调持有/拒绝减少”。
   - 若你希望“裸改 Map 就是允许的”，那必须定义“无主耕地”归谁产出，本提案不擅自补。

---

## 11. 修订记录

- v1（2026-10-21）：首版提案。恢复工作树并回代码核过 Map `HexCell.areas`、
  `HexAreas`、`MapSetHexArea`、Economy 标量 LAND、Map 生产 callers、当前统一市场/运力池/`haul` 双分支；
  提出“Map 城区/耕地标量 + Economy 绝对持有 + app 派生比例”的接入方式；
  翻出 2026-10-05/09/10/06 运输商业旧设计并说明哪些被当前代码吸收、哪些未实现；
  明确当前 32hex 单区无价差，商业不能在该世界验证。**未改代码、未跑构建。**

---

## 12. 口径溯源表（本提案引用）

| 结论 | 出处 | 日期 | 状态 |
|---|---|---|---|
| 城市 = 单 hex 比例状态，Map 维护固定数值 | `docs/superpowers/reports/2026-10-12-hex-ratio-city-gov-semantics-feasibility.md` §8.1–§8.2 | 2026-10-12 | 用户原话，早于本提案；本提案按 2026-10-21 新词“城区标量/耕地标量”落点 |
| 城区独立池子 | `docs/superpowers/specs/2026-10-10-urbanization-and-industry-pool-design.md` §1 D10 | 2026-10-10 | 设计记录，Q1–Q11 未裁定；本提案不实现 |
| 城区占地比例/availableArable | `docs/superpowers/specs/2026-10-05-city-merchant-urbanization.md` §C0 | 2026-10-05 | 旧设计；运行期未接；本提案不启用 |
| LAND 是 `(hex, quantityMilliMu)` 标量 | `docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` §4.1、C10–C18 | 2026-10-19 | v2 冻结设计，当前代码已部分实现 |
| 商品+土地统一订单簿、区级地价 | `docs/superpowers/specs/2026-10-18-d032-p2-unified-market-debt-constraint.md` §7 | 2026-10-18 | 当前代码已部分实现 |
| 简单运力 + `CARRIER_FEE`，不新增运输生产方式 | `docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` §8.10、§1 S7 | 2026-10-19 | v2 工程默认 |
| `haul` 服务成市格只认可交付服务货 | `docs/superpowers/plans/2026-10-20-d032-rc8a-transport-contract-taskbook.md` §3 | 2026-10-20 | RC8a 约束，当前代码已实现 |
| 运输服务不进普通商品订单簿 | `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketIntentBook.java:241-244` | 2026-10-21 回核 | 当前代码事实 |
