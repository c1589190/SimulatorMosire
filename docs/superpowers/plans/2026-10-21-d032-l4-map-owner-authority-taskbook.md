# D-032 L4 任务书：Map 组件 owner 成为土地所有权唯一权威

> **责任区：** `simos-map` + `simos-economy` + `simos-app` 的 LAND 所有权权威迁移。
> **前置：** L1 Map 三档组件树、L2 arable 总量切源、L3 arable supply 瞬态化均已完成并 main 编译绿。
> **父计划：** `docs/superpowers/plans/2026-10-21-d032-land-component-refactor-plan.md` §7 B4。
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> **纪律：** 只写生产代码、只到 `-DskipTests compile`；不写/不跑测试；不 commit/push；不碰主树。

---

## 1. 用户原话（逐字）

> 你猜猜我为什么要把这个重构单独提出来，很显然，我选方案A；注意做好合并管理，最直接的合并原则是两块土地成分除了面积啥都不一样，那就合并，按照这个写一下完整的重构规划，没有需要做的裁定就开始派单，有就告诉我你想怎么裁

---

## 2. 责任区六要素

### 2.1 问题现场

L3 完成后：

- `EconomyData` 已无 `landArableSupply`；
- Map `HexLand` 已有组件树与 arable 总量；
- 但仍持久保存 `EconomyData.landHoldings`（`(hex, owner) -> quantity`）：
  `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java`；
- Map 的 `LandAttributeKey.OWNER` 还没有被播种/写回；
- market/debt/merge 仍通过 `LandHolding`/`LandBook` 聚合账本写。

本批要完成方案 A：

```text
Map component.attributes["owner"] = "hh-..."
= LAND 所有权唯一权威
EconomyData 不再持久 landHoldings
```

### 2.2 责任归属

本责任区负责：

1. Map `HexLand` component owner 属性的种子/读取/写回；
2. Economy 持久 `landHoldings` 删除，改为瞬态 `LandComponentLedger` 聚合视图；
3. 市场/债务/合户/迁移现有的 LAND 转移仍保持同一语义，但轮末写回 Map component；
4. app 在同一 revision 内提交 MapChangeSet + economy/social/actor/production ChangeSet。

不负责：

- B5 progress/crop/fertility 的实际生产语义；
- 城市/城区机制；
- 测试/360 重跑。

### 2.3 最终能力

```text
Map:
  HexLand -> arable LandTypeArea -> LandComponent(id, quantity, attributes{owner,...})

Economy 会话:
  transient LandComponentLedger (从 Map 读，不持久)
  LandHolding = 聚合视图（不作为持久状态）

每 tick:
  市场/债务/合户/迁移改瞬态聚合账本
  app 把最终聚合账本写回 Map
  MapChangeSet + Economy/Social/Actor/Production 同一 revision
```

### 2.4 不可破坏机制

- Map 是 owner/quantity 唯一持久权威；
- EconomyData/ChangeSet/Codec 不再出现 landHoldings/LandArableSupply；
- 任何 LAND 转移仍保持 0 价、下一生产 tick 可用等既有语义；
- 合并规则继续：同一 type 下除 id/quantity 外 attrs 全同 ⇒ 合并，保留最小 id；
- Map 不 import Social/Economy；
- 不新增第二个 matcher/market；
- 所有排序确定性；旧档不兼容。

### 2.5 可动范围

允许：

```text
simos-map/src/main/java/**
simos-economy-api/src/main/java/**
simos-economy/src/main/java/**
simos-app/src/main/java/**
```

禁止：

- 任何 `src/test/**`；
- `simos-production`/`simos-social` 持久状态；
- pom、tools、test-world、主树；
- commit/push。

### 2.6 交账

账本：

```text
.superpowers/sdd/2026-10-21-d032-l4-map-owner-authority/impl-ledger.md
```

---

## 3. 冻结契约

### 3.1 Map owner 属性

```java
LandAttributeKey.OWNER
LandAttributeValue.ofString(householdId.value())
```

- `arable` 的每个 component 必须有非空 owner STRING；
- owner 不存在的 component ⇒ fail-closed；
- owner 属性只在 LandComponent.attributes 中，不新增 Map 持久字段；
- 其它属性（progress/crop/fertility）保留原值，owner 转移不改其它属性。

### 3.2 新增 Map 纯操作

在 `LandComponentOperations` 增加：

```java
public static HexLand setArableOwners(
    HexLand land, HexCoord hex, Map<String, Long> ownerQuantities)
```

语义：

1. 只处理 `LandTypeId.ARABLE`；
2. ownerQuantities 的 key = 家户 id 字符串，value > 0；
3. 对每个 owner：
   - 若现有 arable component 已有该 owner，复用一个 component id/attrs，数量改为该 owner 总量；
   - 若没有，生成新 component id（该 type 的 next ordinal），属性至少含 owner；
4. 不在 ownerQuantities 的 arable component 删除；
5. 执行合并规则；
6. 保留其它 land type；
7. 若现有 arable 同 owner 有多个**不同其他属性**的 component，而聚合账本无法区分 ⇒ 具名失败
   `land-owner-aggregation-unsupported`（B5 再按 component 级转移；本批只处理 owner-only attrs）。

### 3.3 新增 Map 种子命令

新增 GM-only 命令：

```text
map.SeedArableOwnership
payload:
{
  "entries": [
    {"hex": {"q":0,"r":0},
     "owners": [{"owner":"hh-...", "quantityMilliMu": 3100000}]}
  ]
}
```

- 逐 hex 调 `setArableOwners`；
- 未知/重复 hex、owner 空、数量 <= 0、同 hex 重复 owner ⇒ 具名拒绝；
- 要求每个 entry 的 `Σ ownerQuantity == Map 该 hex 现有 arable 总量`；不等则拒绝；
- 返回 MapChangeSet；写日志 INFO，逐条 TRACE。

命令注册同步：

- `Shell` handlers；
- `CatalogTool.PAYLOAD_HINTS`；
- 工具面如无必要可暂不暴露 GM 窄工具，但命令必须在 GM catalog 可见；
- 其它 main 编译点按需机械适配；不跑测试。

### 3.4 临时 LandComponentLedger

新增在 economy 内部（不落盘）：

```text
LandComponentLedger {
  Map<HexCoord, HexLand> componentsByHex;
  Map<HexCoord, Long> arableByHex;                 // 从 Map 派生
  LinkedHashMap<LandHoldingKey, LandHolding> holdings; // 瞬态聚合视图
}
```

API 至少：

- `from(Map<HexCoord, HexLand>)`
- `LandArableSupply arableSupply()`
- `Map<LandHoldingKey, LandHolding> holdingsOrEmpty()`
- `LandHolding holdingOf(hex, owner)`
- `void replaceHoldings(Map<LandHoldingKey, LandHolding>)`
- `Map<HexCoord, HexLand> componentsByHex()`（只读）
- `Map<String, Long> ownerQuantitiesAt(hex)`
- `boolean hasOwnerAttrs()`

行为：

- 初始化时从 Map 的 arable components 的 owner 属性聚合出 holdings；
- 若 arable component 无 owner ⇒ fail-closed（除空 arable/0 面积）；
- `replaceHoldings` 只更新瞬态聚合，不直接写 Map；
- 轮末由 app 调 `LandComponentOperations.setArableOwners` 写回。

### 3.5 EconomyData 删除持久 landHoldings

删除面：

- `EconomyData.landHoldings` 字段/构造/访问器；
- `EconomyChangeSet.landHoldings`；Codec/Payload 的 `landHoldings` 键；
- `EconomySeedHandler`/`LandStateMerge` 的 landHoldings 合并；
- `EconomyClearRegionHandler` 的 landHoldings 清理；
- `EconomyDataInvariants`/`EconomyDataTransforms` 的 landHoldings；
- `EconomyBoundaryView`/`EconomyDayView` 的持久 landHoldings 读口。

保留：

- `LandHolding`/`LandHoldingKey` 类型作为瞬态聚合；
- `LandBook`/`LandLedgerState` 纯操作；
- `MarketRoundCore.landHoldings` 瞬态工作态。

### 3.6 运行期注入与写回

`PopulationEconomyTimeParticipant`：

1. advance 起点取 `GameMap mapBase`；
2. 从 `mapBase` 的 `HexLand` 构造 `LandComponentLedger`；
3. 传给 `EconomyDayStepper`（可替换/扩展 L3 的 LandArableSupply 入参；不得 static/ThreadLocal）；
4. step 结束后从 stepper/session 取最终瞬态 holdings；
5. 对每个 hex 调 `LandComponentOperations.setArableOwners` 更新 `mapAfter`；
6. `MapChangeSet.between(mapBase, mapAfter)` 加入 moduleChanges，Map 加入 writes；
7. 同一 revision；失败零半提交。

`EconomyOwnershipTimeParticipant`（deprecated）也要编译并走同一类，不得留下旧持久读口。

---

## 4. 种子路径

### 4.1 EconomySeeder.Seed

`Seed` 继续在内存中从 `ProductionRuntimeSeeder` 的 `landHoldings` 构造土地种子，但：

- `economyPayload()` 必须**剥离** `landHoldings`/`landArableMilliMu` 旧键；
- 新增方法：

```java
String landOwnershipSeedPayload()
```

输出 `map.SeedArableOwnership` 载荷；数据来源于同一份 entries 的 landHoldings（不新增第二份 seed 计算）。

### 4.2 真世界生成

所有真世界 builder 在把 `GameMap` 放进 `SimulationState` 前应用土地种子：
可直接改 GameMap，也可走 `applyCommand("map.SeedArableOwnership", ...)`；必须使用同一份 `EconomySeeder.Seed`。

至少覆盖 main：

```text
SmallWorld
ThreePowersWorld
CorridorWorld（若它有 economy land）
RegionSeedPlan / WorldgenInitializeTool 的 seed 批
```

测试夹具 `Rural32HexWorld` 本批不改（测试 owner 后续迁移）。

---

## 5. 明确不做

- 不实现 progress/crop/fertility 消费；
- 不做城区机制；
- 不跑测试/360；
- 不 commit/push。

---

## 6. 编译命令

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-map -am
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy-api -am
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

全部必须真实 rc=0；不跑 test/test-compile/verify/package。

---

## 7. 验收锚点（测试 owner 后补）

1. `EconomyData` 无 `landArableSupply`/`landHoldings`；
2. Map arable component 有 owner STRING；`Σ quantity` 仍等于 Map arable 类型总量；
3. 初始 LandHolding 聚合视图与 Map owner 属性逐值相等；
4. 市场土地成交/债务抵地/合户后：Map owner/quantity 与瞬态聚合视图一致，Σ 守恒；
5. 同一 owner 且其它属性全同的 component 自动合并；
6. 市场新买土地下一生产 tick 可用、当前 tick 排除语义不变；
7. MapChangeSet 与 economy/social/actor/production 同 revision，失败零半提交；
8. 旧 payload 的 `landHoldings`/`landArableMilliMu` 显式拒绝；
9. 旧 world seed 路径不再产生持久 Economy 土地行。

---

## 8. 报告模板

```text
责任区：L4/B4 Map owner 权威
改动文件：
编译命令 / rc：
数值行为变化：
旧 wire key / 断言：
种子链：
tick 写回链：
未做/未验证/blocker：
账本：
```
