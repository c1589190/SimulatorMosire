# D-032 L3 任务书：移除 Economy 持久 arable supply，改由 Map 快照注入

> **责任区：** `simos-economy` + `simos-app` 的 arable 总量持久化切源。
> **前置：** L1 Map 三档组件树、L2 Map arable 总量切源均已完成并 main 编译绿。
> **父计划：** `docs/superpowers/plans/2026-10-21-d032-land-component-refactor-plan.md` §7 B3。
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> **纪律：** 只写生产代码、只到 `-DskipTests compile`；不写/不跑测试；不 commit/push；不碰主树。

---

## 1. 责任区六要素

### 1.1 问题现场

L2 后 Map 已是 arable 总量权威，但 `EconomyData` 仍持久保存 `LandArableSupply landArableSupply`：

- `simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:402`；
- `EconomyChangeSet`/`EconomyCodec`/`EconomyPayloads`/`EconomyDataInvariants`/`EconomySeedHandler`/
  `EconomyClearRegionHandler` 仍把它当持久组件；
- app 真地图 seeder 仍把 `landArableMilliMu` 写进 economy entry（B2 只加了过渡守卫）。

当前 `LandHolding` 仍是 Economy 持久所有权账，**本批先保留**；本批只删掉“持久 arable 总量”，
把 arable supply 变成每 tick 由 app 从 Map 构造、注入 economy session 的瞬态快照。

### 1.2 责任归属

本责任区负责：

1. `EconomyData` 删除 `landArableSupply` 持久字段；
2. `LandArableSupply` 保留为**瞬态值类型**，由 app 从 Map 构造并注入 `EconomySession`/`EconomyDayStepper`；
3. `EconomyStateBuilder` 通过注入的 arable snapshot 提供只读 `landArableSupply()`；
4. ChangeSet/Codec/Payload/Seed/Clear/Invariants 去掉 arable 持久键；
5. `landHoldings` 仍保留为 Economy 持久所有权权威（本批不动）。

不负责：

- B4：owner 写 Map component 属性、删除持久 `LandHolding`；
- B5：progress/crop 属性；
- 测试/360。

### 1.3 最终能力

真地图路径：

```text
Map HexLand/components
  -> app 构造 LandArableSupply 快照
  -> EconomySession/EconomyStateBuilder
  -> LandBook / LandProductionProjection / MarketLandBook 读同一份 supply
```

`EconomyData` 不再有 arable 总量组件；旧 `landArableMilliMu` wire key 不再持久。

### 1.4 不可破坏机制

- Map 不 import Economy；economy 不写 Map（本批仍不写 Map）；
- `LandHolding` 仍是本批唯一持久 LAND 所有权数量表；
- `LandBook` 仍是标量守恒/转移唯一纯校验；
- 不新增第二个 arable 总量权威；
- 旧档不兼容，不写迁移/兼容位；
- 不改价格、工资、债务、市场节奏、生产公式；
- 所有排序确定性。

### 1.5 可动范围

允许：

```text
simos-economy/src/main/java/**
simos-app/src/main/java/io/mosire/simos/app/world/**
simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java
simos-app/src/main/java/io/mosire/simos/app/time/EconomyOwnershipTimeParticipant.java
simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java
```

必要时可改 `simos-economy-api/src/main/java/**` 的注释/瞬态类型说明，但不得把 arable supply 变回持久组件。

禁止：

- 任何 `src/test/**`；
- `simos-production`/`simos-social` 持久状态；
- pom、tools、test-world、主树；
- commit/push。

### 1.6 交账

账本：

```text
.superpowers/sdd/2026-10-21-d032-l3-arable-supply-transient/impl-ledger.md
```

报告：改动文件、编译命令与 rc、数值行为变化、旧 wire key/断言、未做/未验证、blocker。

---

## 2. 目标形状

### 2.1 持久状态

```text
EconomyData:
  landHoldings: Map<LandHoldingKey, LandHolding>   // 本批保留
  // landArableSupply 删除
```

### 2.2 瞬态注入

```text
app 真地图入口:
  GameMap -> Map<HexCoord, Long> arableByHex
  -> new LandArableSupply(arableByHex)

EconomySession/EconomyDayStepper:
  构造时显式收 LandArableSupply snapshot
  session.sheet().landArableSupply() 返回该 snapshot（只读）
```

规则：

- snapshot 不从 holdings 反推；
- app 必须对每个 holding hex 检查 `Σ holdings == snapshot.arableOfHex`；
- `LandArableSupply.empty()` 只能用于合法空土地/夹具；生产路径缺 snapshot ⇒ 失败关闭，不按 0 冒充。

### 2.3 `EconomyData` 删除面

删除：

- `landArableSupply` record component、构造期默认、invariants、transforms builder 字段；
- `EconomyChangeSet` 的 `landArableSupply` 字段/diff/rebuild/isEmpty 判断；
- `EconomyCodec`/`EconomyPayloads` 的 `landArableMilliMu` 读写；
- `EconomySeedHandler` 的 arable supply 合并/日志；
- `EconomyClearRegionHandler` 的 arable 表清理；
- `EconomyDataTransforms.withAssetSharesAndLandState` 中的 arable 参数（保留 holdings 部分，方法名按需改）；
- `EconomyBoundaryView`/`EconomyDayView`/`WorkingDayView` 中把 `data.landArableSupply()` 当持久读口的部分。

保留：

- `LandHolding`/`LandHoldingKey` 持久；
- `LandArableSupply` 类型本身（瞬态快照）；
- `LandBook` 纯校验；
- `LandLedgerState.from(LandArableSupply, holdings)`。

### 2.4 Seeder

- `ProductionRuntimeSeeder` 不再写 `entry.put("landArableMilliMu", landMilliMu)`；
- `landHoldings` 仍写，owner 分配算法不变；
- `EconomySeeder` B2 的双账守卫改为：
  `Σ entry.landHoldings[hex].quantityMilliMu == Map.arableMilliMu(hex)`；
- app 真地图路径构造 `LandArableSupply` 并注入 economy session/stepper；
- `EconomyPayloads` 不再解析 `landArableMilliMu`；若旧 payload 带该键，按 Alpha 直接拒绝或忽略并记具名 INFO？本批选 **拒绝未知旧键**还是忽略需在账本写明。默认：**拒绝**（旧档不兼容）。

### 2.5 Session/Stepper

建议：

```java
EconomySession(
  EconomyData base,
  ...,
  LandArableSupply landArableSupply)
```

- `EconomyStateBuilder(base, definitions, runtime, landArableSupply)`
- `EconomyStateBuilder.landArableSupply()` 返回字段；
- `build()` 不再把 supply 放进 `EconomyData`；
- `EconomyDayStepper` 增加同样入参；旧构造器可传 `LandArableSupply.empty()` 只用于夹具，但真生产入口必须显式给 snapshot。

如果改构造器面太大，可在 `EconomySession` 增加静态工厂/new overload，
但**不得**用静态可变字段或 ThreadLocal 传递 arable snapshot。

### 2.6 app 注入

`PopulationEconomyTimeParticipant`：

- 在每次 advance 起点从 `mapOfOrNull(state)` 构造 arable snapshot；
- 传给 `EconomyDayStepper`；
- 若 map 切片缺失但 economy 有 landHoldings 非空 ⇒ fail-closed，具名 `arable-supply-map-missing`；
- 若 map 存在但某 holding hex 缺 arable/合计不等 ⇒ fail-closed。

`EconomyOwnershipTimeParticipant` 是 deprecated 备用路径：
同样从 state map 构造 snapshot；不得让备用路径绕过。

---

## 3. 明确不做

- 不改 `LandHolding` 持久表；
- 不写 Map component owner；
- 不写组件级 MapChangeSet；
- 不改市场/debt 的土地转移聚合口径；
- 不跑测试/360；
- 不 commit/push。

---

## 4. 编译命令

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy-api -am
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

三条 compile 必须真实 rc=0；不跑 test/test-compile/verify/package。

---

## 5. 验收锚点（测试 owner 后补）

1. `EconomyData` 无 `landArableSupply` 组件；Codec/ChangeSet 不含 `landArableMilliMu`；
2. `LandHolding` 仍持久、可往返；
3. 真地图创世 `Σ holdings(hex) == Map.arableMilliMu(hex)`；
4. app 从 Map 构造 supply 后注入 session；`sheet.landArableSupply()` 与 Map 逐 hex 相等；
5. map 缺失/hex 缺失/总量不等 ⇒ 具名 fail-closed；
6. 市场/债务 land 读到的 arable 总量与 Map 同源；
7. 旧 payload 的 `landArableMilliMu` 不再被持久读入。

---

## 6. 报告模板

```text
责任区：L3/B3 arable supply 瞬态化
改动文件：
编译命令 / rc：
数值行为变化：
旧 wire key / 断言：
注入链：
未做/未验证/blocker：
账本：
```
