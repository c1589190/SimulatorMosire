# D-032 L1 任务书：Map 三档土地组件树（Map-only 生产代码，到编译绿）

> **责任区：** `simos-map` 单 hex 土地组件树与纯操作。
> **状态：** 用户已选方案 A；这是完整重构规划 B1/L1，只派这一个写代码 Agent。
> **父计划：** `docs/superpowers/plans/2026-10-21-d032-land-component-refactor-plan.md`。
> **前置设计：** `docs/superpowers/specs/2026-10-21-d032-land-component-tree-and-arable-slice.md`。
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> **纪律：** 只写生产代码、只到 `-DskipTests compile`；不写/不跑测试；不 commit/push；不碰主树。

---

## 1. 用户原话（逐字，派单依据）

> 你猜猜我为什么要把这个重构单独提出来，很显然，我选方案A；注意做好合并管理，最直接的合并原则是两块土地成分除了面积啥都不一样，那就合并，按照这个写一下完整的重构规划，没有需要做的裁定就开始派单，有就告诉我你想怎么裁

> 先实现统一口径的单格耕地吧，不过你漏考虑了一点，那就是后面我要做独立的耕地进度，因此地块数据类型必须被拆成旗下有多个部分，每一部分规定具体数量和抽象状态参数，其中抽象状态参数表里可以加家户所有权，也可以在后面加农业生产相关属性，也就是说，单hex实际土地数据类型必须成为 土地类型（如耕地）：面积成分：抽象属性 三档，这个机制能实现吗？

---

## 2. 责任区六要素

### 2.1 问题现场

当前 Map 土地是 flat 形状：

- `HexCell.areas: Map<HexAreaKey, Long>`：
  `simos-map/src/main/java/io/mosire/simos/map/HexCell.java:30`；
- `HexAreaKey.Builtin` = `URBAN("urban")` / `RURAL("rural")`：
  `simos-map/src/main/java/io/mosire/simos/map/HexAreaKey.java:51-53`；
- `HexAreas.resolved/defaultOf/initialCell` 直接操作 flat map：
  `simos-map/src/main/java/io/mosire/simos/map/HexAreas.java:47-70,132-139`；
- `HexAreaOperations` 只改一个 flat 标量：
  `simos-map/src/main/java/io/mosire/simos/map/ops/HexAreaOperations.java:110-168`；
- 当前没有 component id、没有抽象属性表、没有合并规则。

### 2.2 责任归属

你（实现 Agent）负责 **B1/L1**：在 Map 模块内建立三档土地组件树、纯操作、Map 读写面和 main 编译适配。
不负责 Economy/Social/Production 的土地状态迁移（后续 B2–B5）。

### 2.3 最终能力

完成后系统必须具有：

```text
HexCell
  └─ HexLand
       └─ Map<LandTypeId, LandTypeArea>
            └─ List<LandComponent>(id, quantityMilliMu, attributes)
```

并满足：

1. 一个 hex 可以有多个 land type；每个 type 可以有多个 component；
2. 每个 component 有稳定 id、数量、抽象属性 map；
3. `owner` 作为 `LandAttributeKey.OWNER` 的字符串属性可存在；
4. 总量由组件数量求和；
5. 同一 type 下“除 id/quantity 外属性全同”的 component 自动合并；
6. 部分数量转移可拆分/合并，数量守恒；
7. 旧 flat `areas` 不再是持久状态，只作为读口派生 totals 的兼容显示。

### 2.4 不可破坏机制

- 五条铁律；尤其铁律 3（Map 不 import Social/Economy 领域类型）与铁律 5（ChangeSet 往返）；
- 现有 `Command → ChangeSet → Revision` 写路径；
- Map 模块持久状态不依赖 Social/Economy；
- 确定性：禁止 HashMap 迭代序、`Map.copyOf`/`Set.copyOf`、随机 UUID、墙钟；
- `HexCell`/`HexLand`/组件/属性均不可变，构造后不暴露可写视图；
- Map 不写 Economy/Social，不读 `HouseholdId` 类型；
- `owner` 只作不透明字符串。

### 2.5 可动范围

**允许写：**

```text
simos-map/src/main/java/io/mosire/simos/map/**
```

以及仅当 main 编译必须时：

```text
simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java
```

**禁止写：**

- 任何 `src/test/**`；
- `simos-economy/**`、`simos-economy-api/**`、`simos-production/**`、`simos-social/**`；
- 任何 `pom.xml`；
- `test-world/**`、`tools/**`、主树 `/home/cna/SimulatorMosire`；
- `docs/**`（除本任务书已有内容不修改）。

### 2.6 交账方式

实现架构账本：

```text
.superpowers/sdd/2026-10-21-d032-l1-map-land-component-tree/impl-ledger.md
```

报告必须列：

- 改动文件；
- 编译命令与真实 rc；
- 会改变数值行为/读口形状的清单；
- 受影响的旧字面量/旧 wire key；
- 与本文不一致处及原因；
- 未做/未验证项。

---

## 3. 冻结数据形状

### 3.1 新类型

```java
public record LandTypeId(String wireValue) {
  // 内置常量 ARABLE("arable") / URBAN("urban")
  // 自定义可接受 "custom:<name>" 或等价开放表示
}

public record LandComponentId(String value) {
  // 形如 "arable@0_0:0"
}

public enum LandAttributeKind { LONG, STRING, BOOLEAN }

public record LandAttributeValue(
    LandAttributeKind kind,
    String canonicalValue
) {
  // 静态工厂：ofLong、ofString、ofBoolean
  // 读取：longValue()/stringValue()/booleanValue()，非法 canonicalValue 具名抛
}

public record LandAttributeKey(String wireValue) {
  // 内置常量 OWNER("owner") / PROGRESS("progress") / CROP("crop") / FERTILITY("fertility")
  // 自定义 "custom:<name>"
}

public record LandComponent(
    LandComponentId id,
    long quantityMilliMu,
    Map<LandAttributeKey, LandAttributeValue> attributes
) {}

public record LandTypeArea(
    LandTypeId type,
    List<LandComponent> components,
    long nextComponentOrdinal
) {}

public record HexLand(
    Map<LandTypeId, LandTypeArea> byType
) {}

public record HexCell(
    double height,
    HexLand land
) {
  public HexCell(double height) { ... } // 空 land
}
```

### 3.2 构造期必须守的规则

- `LandComponentId` 非空白；同 type 下唯一；
- `quantityMilliMu > 0`（0 行不允许存在；操作层先删 0）；
- `LandAttributeValue.kind` 非空，`canonicalValue` 非空白；LONG 必须规范十进制 long；BOOLEAN 只接受 `"true"/"false"`；
- 属性 map 非 null、无 null key/value，按 key wireValue 规范升序冻结；
- `LandTypeArea.components` 按 component id 规范升序冻结；`nextComponentOrdinal > max(现有 ordinal)`（解析 id 的最后 `:<n>`，非法 id 拒绝）；
- `HexLand.byType` 按 type wireValue 规范升序冻结；
- 0 数量组件在 `LandComponent` 构造层直接拒绝；纯操作层负责先删除。
- 禁止 `Map.copyOf`/`Set.copyOf`；用 `LinkedHashMap` + `Collections.unmodifiableMap` + 显式排序。

### 3.3 兼容读口

保留 `HexAreaKey`，但它只是**scalar view key**：

- `HexAreaKey.Builtin.ARABLE` -> `LandTypeId.ARABLE`；
- `HexAreaKey.Builtin.URBAN` -> `LandTypeId.URBAN`；
- 旧 `RURAL` 若保留，只能作为 `ARABLE` 的读口别名；建议直接正名 `ARABLE`，Alpha 不兼容旧 wire；
- `HexAreas.resolved(cell,key,terrain)` = 该 type 的组件总量；若无组件，`urban` 0、`arable` 按地形默认；
- `HexAreas.putInto(view, cell, terrain)` 输出：
  - `landTypes`：每个 type 的总量与组件只读视图；
  - `components`：逐 component 的 id/quantity/attributes；
  - 兼容 `areas`：`urban`/`arable` 的派生总量；
  - `areaSources` 可保留但只能是派生说明（无显式 flat 存储时如实标 `derived`，不得伪造 `explicit`）。

### 3.4 旧 `map.SetHexArea` 语义

本批不新增命令；保留 `map.SetHexArea` 作为**单 component type total setter**：

- 该 type 无 component，或恰有 1 个 component ⇒ `set/add` 改该 component 数量；
- 该 type 有 ≥2 个 component ⇒ 具名拒绝：
  `land-type-has-multiple-components`（不静默合并/重建）；
- `add` 结果 0 ⇒ 删除该 component；结果 <0 ⇒ 拒绝；
- `urban` 默认 0：无 component 时 `resolved=0`，`add` 从 0 起算；
- `arable` 无 component 时 `resolved` 按地形默认（创世 `initialCell` 会写一个 component）；
- 所有修改仍走现有 `map.SetHexArea` handler/tool，不改命令名/注册面。

---

## 4. 组件纯操作

新建 `LandComponentOperations`（名称可按 Map 风格微调）：

```text
static HexLand seedSingle(HexCoord hex, LandTypeId type, long quantity, Map<LandAttributeKey,LandAttributeValue> attrs)
static long total(HexLand land, LandTypeId type)
static HexLand upsertComponent(HexLand land, HexCoord hex, LandTypeId type, LandComponent component)
static HexLand removeComponent(HexLand land, HexCoord hex, LandTypeId type, LandComponentId id)
static HexLand setComponentAttribute(HexLand land, HexCoord hex, LandTypeId type, LandComponentId id, LandAttributeKey key, LandAttributeValue value)
static HexLand transferQuantity(HexLand land, HexCoord hex, LandTypeId type, String fromOwner, String toOwner, long quantity)
static LandTypeArea mergeEqualComponents(LandTypeArea area)
static HexLand normalize(HexLand land, HexCoord hex, LandTypeId type)
```

### 4.1 合并规则

同一 `(hex, landType)` 内：

```text
A.attributes.equals(B.attributes)  ⇒ 合并
quantity = A.quantity + B.quantity
id       = min(A.id, B.id)
```

- 不比较 `id`、`quantity`；
- 必须比较所有 attributes（含 owner/progress/crop/fertility）；
- 不同 land type 绝不合并；
- 合并后重新排序、重新验证 nextComponentOrdinal。

### 4.2 部分转移规则

`transferQuantity(hex,type,fromOwner,toOwner,q)`：

1. `q > 0`、`fromOwner != toOwner`；
2. `fromOwner`/`toOwner` 非空白；
3. 找该 type 下 owner 属性 == fromOwner 的 components，按 id 升序；
4. 累计数量不足 ⇒ 具名拒绝（不得只转一部分后返回半成品）；
5. 整块转走 ⇒ 只改 owner 属性，id 不变；
6. 部分转走 ⇒ 原 id 留卖方剩余，新 id 用本 type 的 `nextComponentOrdinal`，买方新 component 的属性 = 原属性但 owner 改为 toOwner；
7. 删除 0 数量 component；
8. 立即执行合并（买方已有同属性 component 时合并；卖方剩余同属性也合并）；
9. 返回新 `HexLand`，原对象不变；失败不返回半成品。

### 4.3 组件 id 生成

- 每个 `LandTypeArea.nextComponentOrdinal` 从 1 起；
- 新 component id = `type.wireValue() + "@" + hex.q() + "_" + hex.r() + ":" + ordinal`；
- 序号必须写回 `LandTypeArea.nextComponentOrdinal`；
- 不得使用进程内 AtomicLong/随机/墙钟。

---

## 5. Map 读写面

### 5.1 读口

更新：

- `HexAreas` 内部只从 `HexLand` 组件求和；
- `MapHexTool` / `ApiViews` 继续通过 `HexAreas.putInto`，不各写一份视图；
- 输出新的 `landTypes/components`，同时保留派生 `areas` totals；
- 所有视图保序（type wireValue、component id、attribute key 规范序）。

### 5.2 写口

- 本批保留 `map.SetHexArea`；`HexAreaOperations` 改为操作组件总量；
- `SetHexAreaHandler`/`MapSetHexAreaTool`/`MapPayloads` 按需改 key 解析；
- 新增组件级命令**不是本批必需**；若实现方认为没有组件级命令无法保证后续，可只在 Map 内先落纯操作，命令留到 B1b，但必须在账本中写明。
- `TerrainOperations.withHeight` 必须保留全部 `land`（不得重建空 land）。

### 5.3 ChangeSet/Codec

- `MapChangeSet` 仍通过 `FieldDelta<HexCell>` 覆盖 `HexCell.land`，不新增组件；
- `MapCodec` 通用 Jackson 必须能往返嵌套 `HexLand` 与 Map keys；
- `LandTypeId`/`LandAttributeKey`/`LandComponentId` 的 wire 解析必须严格 fail-closed；
- Alpha 不兼容旧 `areas` 持久形状，不写迁移；
- 不要求本批跑测试；但代码必须在 `-DskipTests compile` 下编译。

---

## 6. 明确不做

- 不改 EconomyData/LandHolding/LandArableSupply；
- 不把 owner 写入 Economy 或 Social；
- 不实现生产进度 formula；
- 不新增城区增长/城市机制；
- 不写/跑测试；
- 不 commit/push；
- 不碰主树。

---

## 7. 编译命令

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-map -am
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

要求：

- 两条 compile 都真实 rc=0；
- 若 `simos-app -am` 因测试类/旧 API 编译失败，只允许修 main 编译适配；
- **不跑** `test`/`test-compile`/`verify`/`package`；
- 不 commit/push。

---

## 8. 验收锚点（测试 owner 后补）

1. 32hex 平原每个 hex `arable` 组件总量 = 3,100,000 毫亩；
2. `urban` 无组件时读 0；
3. `HexLand` 三档结构可 ChangeSet/Codec 往返；
4. 同 type 下 owner/progress/crop/fertility 全同、仅 quantity/id 不同的两 component 合并；
5. 不同 owner 或不同 progress 的 component 不合并；
6. 部分转移拆分后总量守恒、买方/卖方同属性组件合并、0 行删除；
7. `map.SetHexArea` 对 0/1 component 工作；对 ≥2 component 具名拒绝；
8. 同一输入两次序列化字节相同（map/set 保序）；
9. `withHeight`/`SetTerrain` 不丢 component 属性；
10. 旧 flat `areas` 不作为持久状态（只作为读口派生 totals）。

---

## 9. 报告模板

```text
责任区：B1/L1 Map 三档土地组件树
改动文件：
编译结果：
  命令：
  rc：
会改变数值/读口形状的行为：
受影响的旧字面量/旧 wire key：
与任务书不一致处及原因：
未做/未验证/blocker：
账本落点：
```
