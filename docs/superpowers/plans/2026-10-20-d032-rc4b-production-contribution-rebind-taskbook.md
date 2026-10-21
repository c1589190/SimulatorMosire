# RC4-B 任务书：production 周期贡献随合户重键

> **状态：约束设计书/实现任务书；只写生产代码到编译过，不写/不跑测试。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父设计：v4 R1/R5、v2 S3–S7。
> 依赖：RC4-A merge plan 已 compile 绿。
> 本单只补 production 契约口，不改 economy 合户写口/资产/roster/enterprise。

## 1. 目标

同 key 家户 `source → survivor` 合并时，source 在**任何生产实例**的本周期
`cycleContributions` 必须能随人口贡献转到 survivor，避免：

```text
population 已归入 survivor，但本周期劳动/加权劳动仍记在 source 0 人口壳；
收获/经验/效率按 source 结算 → 幽灵贡献。
```

新增最小 production 契约口：

```text
ProductionRuntime.rekeyCycleContributions(
    ProductionUnitId unit, HouseholdId from, HouseholdId to)
```

只改该实例 `cycleContributions` 的 household 键/值；不改 operator、modeKey、进度、投入、地点。
source 是 unit.operator 的重绑仍由 RC4-D 用 `reoperateInstance` 完成。

## 2. 当前事实（file:line）

- `ProductionProcess.cycleContributions` 是 `Map<ProductionParticipantKey, CycleContribution>`（`ProductionProcess.java:76-90`）。
- `ProductionProcess.withIdentity(...)` 原样带过 `cycleContributions`（`ProductionProcess.java:243-267`），不会重键 household。
- `ProductionRuntime.reoperateInstance(...)` 只改 operator/modeKey（`ProductionRuntime.java:161-169`；Engine `:419-451`）。
- `CycleContribution` 自带 `laborMilli/efficiencyWeightedLaborMilli/plus(...)`（`CycleContribution.java`）。
- `ProductionParticipantKey = (household, role)`（`ProductionParticipantKey.java`）。
- 收获/分配读 `receipt.cycleContributions()`（`SettlementDayProductionPipeline.java` 410 行附近）⇒ source 历史贡献不重键就会继续留在 source。

## 3. 冻结契约

### 3.1 ProductionRuntime 新方法

```text
ProductionInstanceOutcome rekeyCycleContributions(
    ProductionUnitId unit,
    HouseholdId from,
    HouseholdId to)
```

语义：

- 实例不存在 ⇒ `Rejected("instance-unknown")`（与 reoperateInstance 同形）；
- `from == to` ⇒ `Rejected("same-household")` 或业务拒（具名，不静默）；
- 遍历该实例 `cycleContributions`：
  - 只处理 `key.household == from` 的项；
  - 目标 key = `(to, role)`；
  - 若目标 key 已存在 ⇒ `laborMilli`/`efficiencyWeightedLaborMilli` 精确相加（`Math.addExact`）；
  - 若不存在 ⇒ 新建 `CycleContribution(to, role, labor, weighted)`；
  - 源 key 删除；非 source key 原样保留；
  - 输入序稳定，输出仍 `LinkedHashMap`/不可变（`ProductionProcess` 构造器冻结）；
- 不改变 `productionDay`/`progressDays`/`cycleInputUsedMilli`/`operator`/`modeKey`/`location`；
- 无 source key 时返回 `Written(existing)`（幂等 no-op，不改状态）；
- 所有键/值一致性由 `ProductionProcess` 构造器与 `CycleContribution` 构造器继续守卫；
- 日志：
  - INFO `PRODUCTION_CYCLE_CONTRIBUTIONS_REKEYED`（unit/from/to/movedEntries/mergedEntries/changed）
  - DEBUG 逐 role 读数；
  - 无变化 DEBUG `PRODUCTION_CYCLE_CONTRIBUTIONS_REKEY_NOOP`。

### 3.2 只做 production

- 本单不碰 `EconomyData`/ChangeSet/Codec；只新增 interface + engine 实现 + 必要注释。
- `ProductionData` 的 `householdExperience`/`unlocks` 仍由既有 `mergeExperience` 负责，RC4-D 调用；本单不改。
- 不改 unit id / org id / operator / roster。

## 4. 必须达到的行为

1. 一个实例有 `(source, roleA)=10/20`，`(survivor, roleA)=3/4`；rekey 后只剩 `(survivor, roleA)=13/24`；
2. 不同 role 的 source 项分别转到 survivor 对应 role，不相互合并；
3. 没有 source 项时 no-op 且不产生新实例；
4. 非 source 的 participant 项逐值不动；
5. 对不存在的 unit 具名拒绝，不伪造空实例；
6. 不改 operator/modeKey/progress/cycleInputUsed/location；
7. 所有输入/输出稳定、无 Map 迭代序依赖；
8. 不新增持久组件。

## 5. 允许修改的文件

```text
simos-production/src/main/java/io/mosire/simos/production/contract/ProductionRuntime.java
simos-production/src/main/java/io/mosire/simos/production/ProductionRuntimeEngine.java
simos-production/src/main/java/io/mosire/simos/production/contract/ProductionProcess.java（只加/复用不改变 wire 的方法）
simos-production/src/main/java/io/mosire/simos/production/contract/ProductionInstanceOutcome.java（只在必要时补注释/新 outcome，不改既有档语义）
```

必要时只做只读适配的 economy `ProductionReferenceValidation` 注释；**禁止**改 economy 持久状态、roster、enterprise、asset、LAND、seeder、tests、主树。

## 6. 验收锚点（测试 owner 后补）

正例：

1. 合并同 role 的 labor/weighted 精确相加；
2. 不同 role 不串；
3. 无 source 项幂等；
4. 源 contribution 清除后，production `changeSet()` 的实例 delta 正确；
5. `cycleContributions` 键仍等于值内 participant key。

负例：

- 源项未删除 ⇒ 红；
- 目标项被覆盖而不是相加 ⇒ 红；
- 改了 operator/modeKey/progress/input ⇒ 红；
- 不存在 unit 被静默当成功 ⇒ 红；
- from==to 静默通过 ⇒ 红。

## 7. 编译与交账

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-production,simos-economy -am
```

- 只写生产代码；不写/不跑 test/test-compile/verify/package；不 commit/push。
- 账本：`.superpowers/sdd/2026-10-20-d032-rc4b-production-contribution-rebind/impl-ledger.md`。
- 报告列：改动文件、编译 rc、接口/幂等/合并规则、日志、可能失效测试断言、未验证项。

## 8. 本责任区不做

- 不实现 economy 合户写口/RC4-C/D；
- 不改 roster/enterprise/asset/relation/occupancy/reservation；
- 不改 LAND/账户/人口；
- 不做 S2 接线、S7-B/C、flow table；
- 不做 §8 整组织交易。

## 9. 用户原话附录（逐字）

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md` §1.2，2026-10-20。

```text
R1：如果一个hex上有多个同一文化属性、同一生产方式和对应阶级性的家户，只是库存和人口不同，那么这两个家户应当合并，目前的有地农民家户早已脱离地主语义了，事实上是小农和地主的集合体——地主导致的灾难性阶级分化事实上也能表示，就是单个hex的有地农民人口极少土地极多，大部分人口都只能接受雇佣
R5：是的，随着人走
```
