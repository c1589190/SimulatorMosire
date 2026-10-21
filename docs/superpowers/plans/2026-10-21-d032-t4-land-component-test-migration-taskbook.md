# D-032 T4 任务书：土地三档组件树测试迁移与首轮行为复验

> **责任区：** 测试 owner。只改测试/夹具/test-world 运行辅助，不改生产代码。
> **前置：** B1 Map 三档组件树、B2 arable 切源、B3 arable supply 瞬态化、B4 Map owner 权威均已完成 main 编译绿。
> **父计划：** `docs/superpowers/plans/2026-10-21-d032-land-component-refactor-plan.md` §7 B5 前置。
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> **纪律：** 不 commit/push，不碰主树；测试/夹具可改，生产代码只允许报告问题，不得自行改设计。

---

## 1. 当前已知失败

控制方实跑：

```bash
tools/mvn-lock.sh -q -DskipTests test-compile -pl simos-app -am
```

当前真实 rc=1，集中在：

- `EconomyData.withLandState(LandArableSupply, Map<LandHoldingKey,LandHolding>)` 已删除；
- `EconomyData.landArableSupply()` / `landHoldings()` 已删除；
- `EconomyChangeSet.landArableSupply()` / `landHoldings()` 已删除；
- `HouseholdMergeBook.plan` 新增 `Map<LandHoldingKey,LandHolding>` 入参；
- `LandStateMerge` 已删除；
- Map `HexCell.areas`/`HexAreaKey.RURAL` 已变成 `HexCell.land`/`LandTypeId`/`LandComponent`；
- `ArableSupplySnapshots.from` 已变为 `landLedgerFrom` 等新接口。

这些是 Alpha 不兼容旧测试，按“老架构老测试删/迁移，新架构新测试另写”处理。

---

## 2. 责任区六要素

### 2.1 问题现场

见 §1 的失败清单。测试 owner 需要复跑 `test-compile` 获取完整错误清单，而不是只信摘要。

### 2.2 责任归属

你负责：

1. 把所有现有测试/夹具迁移到新土地形状，使 `test-compile` 与全仓测试能跑；
2. 给 B1–B4 的目标行为补最小但可判别的新测试/负例；
3. 更新 `Rural32HexWorld` 等测试世界，使 arable component 有 owner 属性并能走 `map.SeedArableOwnership` / 同一份 seed；
4. 运行模块测试、全仓测试（若规模可承受）与 32hex 360 tick；
5. 如实报告未验证项。

不负责：

- 改生产设计/生产代码；若生产行为与设计冲突，先报告，不自行放宽断言；
- 新增 progress/crop/fertility 生产语义（B5 未做）。

### 2.3 最终能力

```text
tools/mvn-lock.sh -q -DskipTests test-compile -pl simos-app -am  rc=0
tools/mvn-lock.sh -q test -pl simos-app -am                      rc=0（或给出真实失败清单）
tools/multitick-sim.sh rural-32hex-360d                         rc=0（OK=360 / REJECTED=0）
```

### 2.4 不可破坏机制

- 测试按验收判据写，不按实现反推；
- 负向用例必须有：未知 hex/重复 component id、同 type 合并判据、不同 owner/进度不合并、
  部分转移拆分守恒、缺 owner fail-closed、`map.SeedArableOwnership` 的重复/不等拒绝；
- 不把“没跑”写成“通过”；报告必须有“未做/未验证”。

### 2.5 可动范围

允许：

```text
simos-*/src/test/**
test-world/**
tools/multitick-sim.sh（仅当测试运行辅助必须）
```

但 **不得**改：

- 生产代码 `src/main/**`（发现生产 bug 先报告）；
- 主树、pom、`docs/**`、`.superpowers/**`；
- 任何权限/状态形状/公式。

### 2.6 交账

测试报告：

```text
.superpowers/sdd/2026-10-21-d032-t4-land-component-tests/task-1-report.md
```

证据落同目录 `evidence/`。

---

## 3. 迁移清单

### 3.1 Map 测试

- `HexCell` 构造：旧 `(height, Map<HexAreaKey,Long>)` → `HexCell(height, HexLand)`；
- `HexAreaKey.RURAL` → `ARABLE`；wire `rural` → `arable`；
- `HexAreas.putInto` 新增 `landTypes`/`components`，`areas` 为派生 totals；
- 新测试：
  - 同 type 下除 id/quantity 外 attrs 全同 ⇒ 合并、id 取最小、quantity 相加；
  - 不同 owner/progress/crop/fertility ⇒ 不合并；
  - `setArableOwners` 部分/整体 owner 转移、0 删除、next ordinal 不复用；
  - `map.SeedArableOwnership` 重复/未知/负数/合计不等负例；
  - `MapChangeSet`/`MapCodec` 对 `HexLand` 的往返。

### 3.2 Economy 测试

- 删除以 `EconomyData.landArableSupply/landHoldings` 为持久组件的旧断言；
- `withLandState` 改用当前 holdings-only / ledger 入口；
- `EconomyRoundTripTest`/`EconomyCodecTest` 组件数、字段数更新；
- `EconomyChangeSet` 不再含 landArableSupply/landHoldings；
- `EconomyPayloads` 对旧 `landArableMilliMu`/`landHoldings` 显式拒绝；
- `LandComponentLedger` 正例/负例：缺 owner、聚合、replaceHoldings、Map owner 派生；
- `HouseholdMergeBook.plan` 新入参迁移；
- `LandStateMerge` 删除后相关测试删除/迁移；
- `LandBook`/`LandLedgerState` 仍按标量守恒测试。

### 3.3 app 测试/夹具

- `EconomySeeder` economy payload 不再含 `landHoldings`/`landArableMilliMu`；
- `EconomySeeder.Seed.landOwnershipSeedPayload()` 的 `map.SeedArableOwnership` 载荷与 entries 同源；
- `SmallWorld`/`ThreePowersWorld`/`WorldgenInitializeTool`/`RegionSeedTool` 种出的 map component owner 可读，合计等于 arable；
- `Rural32HexWorld` 必须用真实 coord-aware `HexAreas.initialCell` + `map.SeedArableOwnership`（或同一 helper）播种 owner；
- `ApiViews.economyHex` 的 `arableMilliMu/urbanMilliMu/landComponents/landSource` 字段；
- 若测试夹具必须手搭 land，使用 `HexLand`/`LandComponent` 的规范构造器，不写旧 `areas`。

### 3.4 市场/债务/合户负例

- 市场土地成交后：Map owner/quantity 与瞬态聚合一致；
- 债务抵地后：同上，0 价语义不变；
- 合户/迁移后：owner 属性转移、合并规则触发；
- 同 owner 多个不同其它属性 component 的聚合拒绝 `land-owner-aggregation-unsupported`；
- `MapChangeSet` 与 economy/social/actor/production 同 revision 的 app 级测试（如已有夹具可扩展，不塞长跑）。

---

## 4. 运行命令

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module

# 1) 迁移期先编译测试
tools/mvn-lock.sh -q -DskipTests test-compile -pl simos-app -am

# 2) 相关模块测试（先 map/economy，再 app）
tools/mvn-lock.sh -q test -pl simos-map -am
tools/mvn-lock.sh -q test -pl simos-economy -am
tools/mvn-lock.sh -q test -pl simos-app -am

# 3) 32hex 360（独立模拟，不进 surefire）
tools/multitick-sim.sh rural-32hex-360d

# 4) 如果前三步已绿且时间允许，再跑全量门禁
tools/gate.sh full
```

要求：

- 每一步记录真实 rc、测试数/失败数、失败原因；
- 不忽略/跳过失败；`@Disabled` 只允许用于真实 LLM 环境门控，不允许藏新坏断言；
- 360 读 `test-world/multitick/rural-32hex-360d/` 的 CSV/MD，至少报告 OK/REJECTED、土地总量、owner 聚合、市场/债务是否异常；
- 若生产 bug 导致测试红：停止测试修改，提交 blocker 报告（问题现场 + 最小复现 + 建议生产责任区）。

---

## 5. 验收锚点

1. `test-compile` 全仓 rc=0；
2. `simos-map`/`simos-economy`/`simos-app` 测试绿（或列出真实红项与原因）；
3. 新负例有判别力（可对关键合并/缺 owner/合计不等等做变异自证；做不到如实说）；
4. 360 `OK=360 / REJECTED=0`；
5. 报告中明确：新增/修改了哪些测试、哪些旧测试删除/迁移、未验证项、生产问题 blocker。

---

## 6. 报告模板

```text
责任区：T4 土地组件测试迁移与复验
改动测试文件：
旧测试删除/迁移清单：
新增正例/负例：
test-compile rc：
各模块测试读数：
360 读数（OK/REJECTED/关键守恒）：
变异自证：
未做/未验证：
生产 blocker：
证据路径：
```
