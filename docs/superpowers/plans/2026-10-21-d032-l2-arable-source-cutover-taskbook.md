# D-032 L2 任务书：创世土地组件与 arable 总量切源

> **责任区：** app 世界生成/Seeder 与 production 的 arable 总量来源切到 Map 组件树。
> **前置：** L1（B1）Map 三档组件树已完成并 main 编译绿。
> **父计划：** `docs/superpowers/plans/2026-10-21-d032-land-component-refactor-plan.md` §7 B2。
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> **纪律：** 只写生产代码、只到 `-DskipTests compile`；不写/不跑测试；不 commit/push；不碰主树。

---

## 1. 责任区六要素

### 1.1 问题现场

L1 后 Map 已是三档：

```text
HexCell -> HexLand -> Map<LandTypeId, LandTypeArea> -> List<LandComponent>
```

但真实创世仍有两处旧口径：

1. `HexAreas.initialCell(double, String)` 的旧重载没有 `HexCoord`，
   `MapGenerator` / `SmallWorld` / `ThreePowersWorld` / `CorridorWorld` 仍只传 height/terrain，
   component id 可能都是 `arable@0_0:0`；
2. `ProductionRuntimeSeeder` 仍用：
   `landMilliMuOf(terrainOf.apply(hex))`
   从地形现算可耕地，而不是读 Map 的 `arable` 组件总量：
   `simos-app/src/main/java/io/mosire/simos/app/world/ProductionRuntimeSeeder.java:153`；
3. `RuralLaborSeeder.landMilliMuOf(terrain)` 仍是地形公式：
   `simos-app/src/main/java/io/mosire/simos/app/world/RuralLaborSeeder.java:137-138`。

### 1.2 责任归属

本责任区负责 L2/B2：

- 所有真实世界生成器给 `HexAreas.initialCell` 传真实 `HexCoord`；
- `EconomySeeder` 真地图入口从 `GameMap.hexes()` 的 `HexLand` 读出每个 hex 的 arable 总量；
- `ProductionRuntimeSeeder` 使用传入的 `arableOf(hex)`，不再用 terrain 公式；
- Economy 的现有 `landArableSupply` 暂时保留，但必须由 Map arable 总量生成（B3 再删持久组件）；
- app 读口补 `arableMilliMu` / `urbanMilliMu` / `landComponents` 只读字段。

不负责：

- 删除 `LandArableSupply`/`LandHolding`（B3）；
- Market/debt 组件转移（B4）；
- progress/crop 属性（B5）；
- 测试/360 重跑。

### 1.3 最终能力

真实创世完成后：

```text
Map 每个 hex 有 arable 组件（当前一个 component）
arableMilliMu(hex) = Map components 的数量和
ProductionRuntimeSeeder 的 LAND 总量 = 该 Map arableMilliMu(hex)
Economy 的 landArableSupply（过渡期保留）= 同一 Map arable 总量
```

### 1.4 不可破坏机制

- Map 不 import Social/Economy；
- production 仍只收 `Map<AssetKind,Long>`；
- 不新增/删除持久 Economy 土地组件（B3 再做）；
- 不写 `landHoldings`/`LandHolding` 新结构（B4 再做）；
- 旧档不兼容；
- 所有排序确定性、禁 HashMap 迭代序/随机/墙钟；
- 不改生产公式/价格/债务/工资/市场节奏。

### 1.5 可动范围

允许：

```text
simos-map/src/main/java/io/mosire/simos/map/HexAreas.java
simos-map/src/main/java/io/mosire/simos/map/generate/MapGenerator.java
simos-app/src/main/java/io/mosire/simos/app/world/**
simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java
```

如果 `EconomySeeder` 需要用 `HexAreas`，可加一个只读 helper 到 Map 模块；
不允许改 `simos-economy`/`simos-economy-api`/`simos-production`/`simos-social` 持久状态。

禁止：

- 任何 `src/test/**`（含 `Rural32HexWorld`，测试 owner 后续迁移）；
- `pom.xml`、`tools/**`、`test-world/**`、主树；
- commit/push。

### 1.6 交账

账本：

```text
.superpowers/sdd/2026-10-21-d032-l2-arable-source-cutover/impl-ledger.md
```

报告必须列：改动文件、编译命令与 rc、会改变数值的行为、受影响的旧常量和断言、未做/未验证。

---

## 2. 具体交付

### 2.1 Map 增加只读 scalar helper

建议在 `HexAreas` 增加：

```java
public static long arableMilliMu(HexCell cell, String terrain)
public static long urbanMilliMu(HexCell cell, String terrain)
public static Map<LandTypeId, Long> landTotals(HexCell cell, String terrain)
```

语义：

- 有对应 `LandTypeArea` ⇒ 读 `area.totalMilliMu()`（空 area 即 0）；
- 无对应 area ⇒ arable 按地形默认、urban 0；
- 不写状态、不做第二套比例。

### 2.2 所有真实世界生成器改用 coord-aware `initialCell`

更新：

```text
simos-map/src/main/java/io/mosire/simos/map/generate/MapGenerator.java
simos-app/src/main/java/io/mosire/simos/app/world/SmallWorld.java
simos-app/src/main/java/io/mosire/simos/app/world/ThreePowersWorld.java
simos-app/src/main/java/io/mosire/simos/app/world/CorridorWorld.java
```

规则：

- 有真实 `HexCoord` 时一律调用：
  `HexAreas.initialCell(hex, height, terrain)`；
- 保留旧 `initialCell(double, terrain)` 仅给尚未迁移的测试夹具；
- 真实世界 component id 必须是 `arable@<q>_<r>:0`，不得全部撞 `0_0`。

### 2.3 EconomySeeder 真地图入口切到 Map arable

在真 `GameMap` 入口：

```text
Map<HexCoord, String> terrain = map.terrainIndex();
Function<HexCoord, Long> arableOf =
    hex -> HexAreas.arableMilliMu(map.hexes().get(hex), terrain.get(hex));
```

把 `arableOf` 显式传到 `ProductionRuntimeSeeder.planProductionRuntime`。

纯 function 入口（无 GameMap，主要测试/夹具）允许保留 terrain fallback：

```text
arableOf = hex -> RuralLaborSeeder.landMilliMuOf(terrainOf.apply(hex))
```

但必须在账本里点名：真实 map 入口已切 Map，纯 function 入口只作测试过渡。

### 2.4 ProductionRuntimeSeeder 收 `arableOf`

签名增加 `Function<HexCoord, Long> arableOf`（放在 terrainOf 旁），并把：

```java
long landMilliMu = landMilliMuOf(terrainOf.apply(hex));
```

改为：

```java
long landMilliMu = arableOf.apply(hex);
```

规则：

- `arableOf` 必须非 null，返回 `>=0`；
- 任何未知 hex/负数 fail-closed；
- 该值同时用于 farm plan、landHoldings、landArableMilliMu 的当前过渡载荷；
- 不改变现有 landHoldings 的 owner 分配算法。

### 2.5 app 读口

在 `ApiViews.economyHex` 增加：

- `arableMilliMu`：从 `MapSnapshot.map().hexes().get(coord)` + terrain 读；
- `urbanMilliMu`：同上；
- `landComponents`：Map 组件的 `id/landType/quantity/attributes` 只读视图；
- 原 `landMilliMu`/`landHoldings` 保留（当前 Economy 权威/过渡投影），直到 B3/B4 删除。

不能填 0 冒充缺失：Map 切片缺失或 hex 缺失 ⇒ 具名 `null` + 原因字段，不伪造。

### 2.6 过渡期一致性守卫

Economy 仍持久 `landArableSupply`。在真地图创世路径必须保证：

```text
每个 hex:
  Economy landArableSupply[hex] == Map.arableMilliMu(hex)
```

可在 app seeder 构造后加校验；失败 fail-closed，不允许两份数漂开。

---

## 3. 明确不做

- 不删 `LandArableSupply`/`LandHolding`；
- 不写 owner 属性；
- 不改 component 拆分/合并（L1 已实现，B2 不触发多 component）；
- 不改测试夹具/测试；
- 不跑 360；
- 不 commit/push。

---

## 4. 编译命令

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-map -am
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

两条 compile 必须真实 rc=0。不跑 test/test-compile/verify/package。

---

## 5. 验收锚点（测试 owner 后补）

1. 32hex 平原真实世界每个 hex Map arable = 3,100,000 毫亩；
2. 每个真实 hex 的 arable component id 带真实坐标，不再全为 `arable@0_0:0`；
3. `ProductionRuntimeSeeder` 不再调用 `landMilliMuOf(terrain)` 作为真实 arable 来源；
4. Economy 过渡 `landArableSupply` 与 Map arable 逐 hex 相等；
5. 现有 `LandHolding` 分配/生产公式不变；
6. `urbanMilliMu` 全 0；
7. app 读口同时显示 Map 标量和 Economy 过渡所有权，不伪造缺失值。

---

## 6. 报告模板

```text
责任区：L2/B2 arable 总量切源
改动文件：
编译命令 / rc：
会改变数值行为的地方：
旧常量/旧调用面：
过渡期双账校验：
与任务书不一致处及原因：
未做/未验证/blocker：
账本：
```
