# D-032 单 hex 土地组件树与首批耕地切片（2026-10-21）

> **状态：设计提案 / 首批实现范围建议，不是已验收实现。**
> 父目标运行序列：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` §1 S0–S8。
> 前置提案：`docs/superpowers/specs/2026-10-21-d032-hex-land-ownership-and-transport-commerce-integration.md`。
> 本文只处理“单 hex 土地数据类型必须拆成类型 → 面积成分 → 抽象属性”这一新增设计；
> 运输商业不在这里重复。用户原话仍是最高权威，控制方建议可被用户推翻。

---

## 1. 用户原话（2026-10-21，逐字）

> 先实现统一口径的单格耕地吧，不过你漏考虑了一点，那就是后面我要做独立的耕地进度，因此地块数据类型必须被拆成旗下有多个部分，每一部分规定具体数量和抽象状态参数，其中抽象状态参数表里可以加家户所有权，也可以在后面加农业生产相关属性，也就是说，单hex实际土地数据类型必须成为 土地类型（如耕地）：面积成分：抽象属性 三档，这个机制能实现吗？

---

## 2. 结论：能实现，而且建议一开始就按三档做

可以。关键不是“能不能塞一个 `Map<String,Object>`”，而是：

1. **面积成分必须是稳定的实体**，不能在每次结算时把整张表按位置重建；
2. **抽象属性必须是可落盘、可 diff、可往返的类型**，不能用裸 `Object` / 任意 Java 对象；
3. **面积成分必须有稳定 id**，否则后面的独立耕地进度、买卖拆地、债务抵地都会失去身份；
4. **所有权只能有一个权威**：要么在 Map 的组件属性表里，要么在 Economy 的组件归属表里；
   不能 Map 的属性表写一份 owner、Economy 的 `LandHolding` 再写一份 owner。

---

## 3. 目标数据结构

### 3.1 逻辑三档

```text
单 hex 土地
  └─ 土地类型（如 arable / urban / forest / ...）
       └─ 面积成分（多个 component，每个有数量和稳定 id）
            └─ 抽象属性（owner、progress、crop、fertility、...）
```

对应形状建议：

```java
record LandTypeId(String value) {}          // 开放词表；内置至少 "arable" / "urban"

record LandComponentId(String value) {}     // 稳定身份；不是列表下标

sealed interface LandAttributeValue
    permits LandAttributeValue.LongValue,
            LandAttributeValue.StringValue,
            LandAttributeValue.BooleanValue {
  record LongValue(long value) implements LandAttributeValue {}
  record StringValue(String value) implements LandAttributeValue {}
  record BooleanValue(boolean value) implements LandAttributeValue {}
}

record LandComponent(
    LandComponentId id,
    long quantityMilliMu,
    Map<LandAttributeKey, LandAttributeValue> attributes
) {}

record LandTypeArea(
    LandTypeId type,
    List<LandComponent> components
) {}

record HexLand(
    Map<LandTypeId, LandTypeArea> byType
) {}
```

`HexCell` 不再直接持有 `Map<HexAreaKey, Long> areas`，而是持有这个 `HexLand`：

```java
record HexCell(double height, HexLand land) {}
```

### 3.2 属性键

`LandAttributeKey` 可以是开放 String 键（与 `LandTypeId` 同制），至少预留：

```text
owner              // opaque 家户引用，如 "hh-0_0-rural-landed_peasant"
progress           // 独立耕地进度（整数；具体量纲由生产域定义）
crop               // 作物/生产方案引用
fertility          // 肥力等生产属性
```

**Map 不 import Social/Production 的领域类型**：`owner` 只是不透明字符串；
真实家户是否存在由 app/economy 在写入口校验。

### 3.3 数量与总量

- `LandComponent.quantityMilliMu` 是这一部分的实际面积；
- `LandTypeArea.totalMilliMu()` = `Σ components.quantityMilliMu`，是**派生量**；
- 单 hex 的 arable 总量 = `land.byType.get("arable").totalMilliMu()`；
- 不再额外保存一份 flat `areas["arable"]` 作为第二权威；
- 若某类型没有 component，总量读作 0（不是“缺数据”）；
- 不允许负数量；component 数量为 0 时是否保留由写口规定：
  建议 **0 数量 component 直接删**，与现有 `LandHolding` 的“无 0 行”同制。

### 3.4 组件 id 的稳定规则

组件 id 必须是**内容身份**，不能依赖：
- 列表下标；
- HashMap 迭代序；
- 随机 UUID；
- 墙钟。

建议：

```text
创世：arable:<q>_<r>:0、urban:<q>_<r>:0
拆分：保留原 component id 给其中一份；新 component id 由命令显式给出，
      或由 Map 的确定性序号生成器给出（序号必须落盘，不能用进程内计数器）
合并：目标保留一个 id；被合并 id 删除
```

★ 如果第一批不做拆分/合并，也仍应先定 component id 字段和稳定序；
否则后面做独立进度时会再次改整个持久形状。

---

## 4. 所有权：两条候选权威，不能双写

### 4.1 方案 A：owner 写在 Map 组件属性里（Map 是所有权权威）

```text
LandComponent.attributes["owner"] = "hh-..."
Economy 的 LandHolding 降为每 tick 从 Map 组件派生的只读缓存 / 账本投影。
市场/债务发生土地转移时，Economy 只产出“组件转移意向”，
由 app 在同一 revision 里写回 Map 的组件 owner/quantity。
```

优点：

- 最贴近用户原话“抽象属性表里可以加家户所有权”；
- 组件、owner、progress 都在同一个对象里，生产读口最直接；
- 未来按组件独立进度时不需要再拆一层主键。

代价：

- 当前 Economy 的 `LandHolding` 持久组件要删除或降为瞬态投影；
- 市场、债务、迁移、读口、ChangeSet/Codec 都要改；
- app 必须增加“经济 land intent → Map 组件变更”的同 revision 写入。

### 4.2 方案 B：owner 归 Economy，Map 只做物理组件

```text
Map: LandComponent(id, quantity, attributes)       // 属性不含 owner
Economy: LandComponentOwner(componentId, owner)    // 归属权威
App: 读口把二者合成组件视图，owner 作为合成属性
```

优点：

- Map 不参与经济所有权，模块边界更干净；
- 市场/债务仍可只改 Economy 的组件归属表；
- 将来 progress 放 Map 组件属性，owner 放 Economy，仍可按 componentId 组合。

代价：

- 用户要求的“抽象属性表里可以加家户所有权”在持久层不是字面成立；
  只能在 app 的**合并组件视图**里成立；
- 需要把现在的 `LandHolding(hex, owner, quantity)` 改成按 componentId 的归属。

### 4.3 方案 C：第一批只做物理组件，owner 仍走现有 `LandHolding`

```text
Map: LandComponent(id, quantity, attributes)   // attributes 先为空/只放生产属性
Economy: 现有 LandHolding(hex, owner, quantity)  // 暂不改
```

这是**最小可编译切片**，但只能保证“单 hex 耕地总量统一”；不能保证
“component ↔ owner”一一对应，也还不能做真正的按 owner/组件独立进度。

### 4.4 控制方建议

- **长期目标采用方案 A**：组件属性是土地状态的唯一权威，owner 作为不透明引用；
- **第一批采用方案 C 的裁剪版**：先把 Map 三档结构和 arable 总量接上生产，
  但**不把 owner 写进 Map 属性**，避免在迁移完成前出现两本 owner 账；
- 等独立耕地进度真正开始实现时，再用单独责任区把 `LandHolding` 切成
  `LandComponentOwner(componentId, owner)`，然后决定是否把 owner 属性并入 Map。

★ 关键红线：**不允许第一批同时**：

```text
Map 组件 attrs 写 owner
Economy LandHolding 也持久写同一 owner
```

那会形成两本所有权账，是比自己写组件树更严重的错误。

---

## 5. 目标运行序列（第一批裁剪版）

| 阶段 | 本切片做什么 | 读 | 写 | 失败 |
|---|---|---|---|---|
| 创世 | Map 每个 hex 生成 `arable` 下的一个 component，数量 = 地形默认可耕地；`urban` 类型不生成或总量 0 | 地形 | `HexCell.land` | 负数量、重复 component id ⇒ 拒绝 |
| S1 载入 | app 从 Map 读 arable 总量，构造瞬态 supply 快照；Economy 仍读现有 `LandHolding` | Map、holdings | 瞬态 | 缺格/总量与 holdings 合计不等 ⇒ fail-closed |
| S3/S4 | 生产方式仍只收 `Map<AssetKind,Long>`；其 LAND 总量来自 Map 的 arable 总量快照 | D4 | 瞬态 | 土地不足 ⇒ `asset-short:LAND` |
| S5 | 产出按现有 holdings/实际参与土地分 | holdings | 产出 | 不使用 component attrs |
| S7 | 土地市场/债务仍走现有 `LandHolding`；Map component 只提供总量 | Map、holdings | holdings | 同现有 |
| app 读口 | 显示 `landTypes`、`components`、`arableMilliMu`、`urbanMilliMu`；owner 仍取 Economy | Map + Economy | 无 | 缺 Map 快照 ⇒ 具名 unavailable |

**第一批不做：**

- 不把 owner 写进 Map 组件属性；
- 不做组件拆分/合并命令；
- 不做 progress/crop/fertility 的实际生产消费；
- 不改市场/债务的 land 写路径；
- 不接城区增长、CityLand、城市化迁移。

---

## 6. 第一批验收锚点（测试 owner 后补）

1. `HexCell.land` 的 ChangeSet/Codec 往返逐字段一致；
2. 32hex 平原每个 hex 的 `arable` 总量 = 3,100,000 毫亩；
3. `urban` 总量 = 0，不因 `City` 或城镇人口自动变；
4. 生产/economy 的 arable 来源是 Map，不再用 `landMilliMuOf(terrain)` 现算；
5. `LandHolding` 合计仍与 Map arable 逐 hex 相等；
6. 旧 `areas["urban"|"rural"]` wire 形状 **按 Alpha 不兼容直接重建**，不写旧档迁移；
7. 无 component 的 land type 读作 0；负数量/重复 id 具名拒绝；
8. 同一输入两次序列化字节相同（map/set 保序，禁 `Map.copyOf`/`Set.copyOf`）。

---

## 7. 与现有代码的冲突点

| 现状 | 目标 |
|---|---|
| `HexCell.areas: Map<HexAreaKey, Long>` | `HexCell.land: HexLand` |
| `HexAreas.resolved/defaultOf/initialCell` 操作 flat map | 改为按 `LandTypeId` 的 component 总量 |
| `map.SetHexArea` 一次改一个 flat 标量 | 第一批可保留为“单 component 类型总量 setter”；多 component 时拒 |
| `ProductionRuntimeSeeder.landMilliMuOf(terrain)` | 改为读 Map 的 arable 总量 |
| `EconomyData.landArableSupply` 持久 | 未来改为瞬态 Map 快照；第一批至少不能继续由地形公式现算 |
| `LandHolding(hex, owner, quantity)` | 第一批保留；后续再按 componentId 关联 |
| `MapChangeSet` 已有 `FieldDelta<HexCell>` | 形状变更后仍随 `hexes` 一起进 ChangeSet，无新增组件 |
| `MapCodec` 通用 Jackson | 需验证 sealed attr value / record 嵌套的往返；必要时显式 codec |

---

## 8. 组件拆分/合并的后续规则（不在第一批实现）

- 拆分必须显式给出新 quantity 和 component id；
- 合并必须保留一个 component id，并禁止两个 component 的数量/属性悄悄相加；
- 组件 owner 转移必须改 owner 属性或归属表，不得改 component id；
- 进度/作物属性只能改属性表，不得改 component id；
- 任何组件数量变化都必须保持该 hex 该 land type 的总量可核对；
- 所有变更仍走 `Command → ChangeSet → Revision`，组件级变更也不例外。

---

## 9. 结论

**能实现。**  
正确的数据形状是：

```text
HexLand -> LandTypeArea(type) -> LandComponent(id, quantity, attributes)
```

不是：

```text
HexCell.areas: Map<String, Long>
```

但第一阶段必须先把“Map 物理组件 + arable 总量接生产”做对；
owner 是否进 Map 组件属性、progress 是否进 Map 组件属性，
需要和所有权/生产状态权威一起分阶段迁移，不能靠双写补丁。
